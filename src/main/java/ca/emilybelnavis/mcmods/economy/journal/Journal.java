package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable journal contracts. Only trusted server services construct intents/records. */
public final class Journal {
  private Journal() {}

  public static final int ENVELOPE_VERSION = 1;

  public enum Status {
    COMMITTED,
    DURABLY_REJECTED,
    NOT_ACCEPTED,
    UNKNOWN
  }

  public enum Receipt {
    ACCEPTED_PENDING,
    NOT_ACCEPTED
  }

  public record Kind(String service, String operationType) {
    public Kind {
      key(service);
      key(operationType);
    }
  }

  public record CanonicalRequest(int version, Map<String, String> fields) {
    public CanonicalRequest {
      if (version < 1 || version > 1024 || fields.size() > 64)
        throw new IllegalArgumentException("Canonical request bounds");
      TreeMap<String, String> copy = new TreeMap<>();
      fields.forEach(
          (key, value) -> {
            text(key, 128);
            text(value, 16384);
            copy.put(key, value);
          });
      fields = Collections.unmodifiableMap(copy);
    }
  }

  public record Intent(
      OperationId operationId,
      RequestId requestId,
      ActorId actor,
      Kind kind,
      CanonicalRequest request,
      UUID economicUpdateId,
      TransactionId correctionOf) {
    public Intent {
      Objects.requireNonNull(operationId);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(actor);
      Objects.requireNonNull(kind);
      Objects.requireNonNull(request);
      Journal.canonical(
          request,
          economicUpdateId,
          correctionOf); // Early bounds checking consumes no operation ID.
    }

    public String canonical() {
      return Journal.canonical(request, economicUpdateId, correctionOf);
    }
  }

  public record Record(String type, int version, String payload) {
    public Record {
      key(type);
      positive(version);
      text(payload, 16384);
    }
  }

  /** Persisted record with its explicit zero-based position in an immutable envelope. */
  public record Entry(int position, String type, int version, String payload) {
    public Entry {
      if (position < 0 || position >= 64) throw new IllegalArgumentException("Record position");
      key(type);
      positive(version);
      text(payload, 16384);
    }
  }

  public record Result(int version, String code, String payload) {
    public Result {
      positive(version);
      if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}"))
        throw new IllegalArgumentException("Result code");
      text(payload, 16384);
    }
  }

  public record Outcome(
      OperationId operationId,
      RequestId originalRequestId,
      Status status,
      TransactionId transactionId,
      JournalSequence sequence,
      Result result) {
    public Outcome {
      Objects.requireNonNull(operationId);
      Objects.requireNonNull(originalRequestId);
      Objects.requireNonNull(status);
      Objects.requireNonNull(result);
      if ((status == Status.COMMITTED) != (transactionId != null && sequence != null))
        throw new IllegalArgumentException("Outcome identity");
      if (status != Status.COMMITTED && (transactionId != null || sequence != null))
        throw new IllegalArgumentException("Noncommit identity");
    }
  }

  public record Envelope(
      WorldId worldId,
      TransactionId transactionId,
      OperationId operationId,
      RequestId originalRequestId,
      ActorId actor,
      Kind kind,
      JournalSequence sequence,
      AuditInstant auditInstant,
      int version,
      int canonicalVersion,
      List<Entry> records,
      Result result,
      UUID economicUpdateId,
      TransactionId correctionOf) {
    public Envelope {
      records = List.copyOf(records);
      for (int i = 0; i < records.size(); i++)
        if (records.get(i).position() != i)
          throw new IllegalArgumentException("Envelope record ordering");
    }
  }

  public static Outcome nonterminal(Intent intent, Status status, String code) {
    if (status != Status.NOT_ACCEPTED && status != Status.UNKNOWN)
      throw new IllegalArgumentException("Nonterminal status");
    return new Outcome(
        intent.operationId(), intent.requestId(), status, null, null, new Result(1, code, ""));
  }

  public static String actorKey(ActorId actor) {
    if (actor instanceof PlayerId player) return "player:" + player.value();
    if (actor instanceof OrganizationId organization) return "organization:" + organization.value();
    if (actor instanceof SystemActorId system) return "system:" + system.value();
    throw new IllegalArgumentException("Actor type");
  }

  static ActorId actor(String value) {
    String[] parts = value.split(":", 2);
    if (parts.length != 2) throw new IllegalArgumentException("Stored actor format");
    UUID id = UUID.fromString(parts[1]);
    return switch (parts[0]) {
      case "player" -> new PlayerId(id);
      case "organization" -> new OrganizationId(id);
      case "system" -> new SystemActorId(id);
      default -> throw new IllegalArgumentException("Stored actor type");
    };
  }

  private static String canonical(
      CanonicalRequest request, UUID economicUpdateId, TransactionId correctionOf) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream output = new DataOutputStream(bytes);
      output.writeInt(request.version());
      output.writeInt(request.fields().size());
      for (var field : request.fields().entrySet()) {
        encode(output, field.getKey());
        encode(output, field.getValue());
      }
      encode(output, economicUpdateId == null ? "" : economicUpdateId.toString());
      encode(output, correctionOf == null ? "" : correctionOf.value().toString());
      if (bytes.size() > 16384)
        throw new IllegalArgumentException("Canonical request exceeds 16KiB");
      return Base64.getEncoder().encodeToString(bytes.toByteArray());
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void encode(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  static void text(String value, int maxBytes) {
    Objects.requireNonNull(value);
    if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes)
      throw new IllegalArgumentException("UTF-8 payload bounds");
    // Reject malformed UTF-16 instead of canonicalizing distinct invalid strings to replacement
    // bytes.
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i)))
          throw new IllegalArgumentException("Malformed UTF-16");
      } else if (Character.isLowSurrogate(c))
        throw new IllegalArgumentException("Malformed UTF-16");
    }
  }

  private static void key(String value) {
    if (value == null || value.length() > 128 || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
      throw new IllegalArgumentException("Namespaced journal key");
  }

  private static void positive(int value) {
    if (value < 1 || value > 1_000_000) throw new IllegalArgumentException("Format version");
  }
}
