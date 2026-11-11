package ca.emilybelnavis.mcmods.economy.credit;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Trusted worker-only immutable source for future obligation/default/reporting consumers. */
public final class CreditEvents {
  private static final String TYPES =
      "record_type IN('economy:a6/credit-facility','economy:a6/credit-statement','economy:a6/credit-daily-witness','economy:a6/credit-payment-allocation')";
  public static final String INDEX =
      "CREATE INDEX credit_event_feed ON journal_records(sequence,position) WHERE " + TYPES;
  // Keep the cursor on the ordered feed instead of sorting matches from the general record-type
  // index.
  static final String QUERY =
      """
            SELECT r.sequence,r.position,r.record_type,r.record_version,r.payload,t.world_id,t.transaction_id,
                   t.operation_id,t.audit_utc,t.economic_update_id,t.correction_of
            FROM journal_records r INDEXED BY credit_event_feed
            JOIN journal_transactions t ON t.sequence=r.sequence
            WHERE r.%s AND r.sequence<=? AND (r.sequence,r.position)>(?,?)
            ORDER BY r.sequence,r.position LIMIT ?
            """
          .formatted(TYPES);

  private CreditEvents() {}

  public record Cursor(WorldId world, long sequence, int position, long frontier) {
    public Cursor {
      Objects.requireNonNull(world);
      if (sequence < 0
          || frontier < sequence
          || position < -1
          || position > 63
          || sequence == 0 && position != -1)
        throw new IllegalArgumentException("Credit event cursor");
    }
  }

  public record Envelope(
      long sequence,
      int position,
      WorldId world,
      UUID transaction,
      UUID operation,
      Instant auditedAt,
      UUID economicUpdate,
      UUID correctionOf) {}

  public sealed interface Payload
      permits FacilityChanged, StatementClosed, InterestPosted, PaymentReceived {}

  public record FacilityChanged(CreditFacilities.Facility snapshot) implements Payload {}

  /** Original requirement identity/due/amount remains distinct from monetary debt. */
  public record StatementClosed(CreditFacilities.StatementSnapshot snapshot) implements Payload {}

  public record InterestPosted(CreditAuditFacts.Daily witness) implements Payload {}

  public record PaymentReceived(CreditAuditFacts.Payment witness) implements Payload {}

  public record Event(Envelope envelope, int recordVersion, Payload payload) {}

  public record Page(List<Event> events, Cursor checkpoint, Cursor nextCursor) {
    public Page {
      events = List.copyOf(events);
    }
  }

  public static Cursor open(JournalEngine.ReadView reads) {
    return open(reads, null);
  }

  /**
   * Renew a completed checkpoint to a new immutable frontier; never silently rewind a restored
   * world.
   */
  public static Cursor open(JournalEngine.ReadView reads, Cursor checkpoint) {
    var world = world(reads);
    long frontier = CreditDiagnostics.frontier(reads);
    if (checkpoint != null
        && (!checkpoint.world().equals(world) || checkpoint.frontier() > frontier))
      throw new IllegalArgumentException("Credit event world/frontier");
    return new Cursor(
        world,
        checkpoint == null ? 0 : checkpoint.sequence(),
        checkpoint == null ? -1 : checkpoint.position(),
        frontier);
  }

  public static Page read(JournalEngine.ReadView reads, Cursor cursor, int limit) {
    Objects.requireNonNull(cursor);
    if (limit < 1 || limit > 16) throw new IllegalArgumentException("Credit event page bounds");
    if (!cursor.world().equals(world(reads))
        || cursor.frontier() > CreditDiagnostics.frontier(reads))
      throw new IllegalArgumentException("Credit event world/frontier");
    var rows =
        reads.query(
            QUERY,
            List.of(
                Long.toString(cursor.frontier()),
                Long.toString(cursor.sequence()),
                Integer.toString(cursor.position()),
                Integer.toString(limit + 1)),
            limit + 1);
    var events = rows.stream().limit(limit).map(CreditEvents::event).toList();
    for (var e : events)
      if (!e.envelope().world().equals(cursor.world()))
        throw new IllegalArgumentException("Credit event envelope world");
    var end =
        events.isEmpty()
            ? cursor
            : new Cursor(
                cursor.world(),
                events.getLast().envelope().sequence(),
                events.getLast().envelope().position(),
                cursor.frontier());
    return new Page(events, end, rows.size() > limit ? end : null);
  }

  private static Event event(Map<String, String> row) {
    int version = Integer.parseInt(row.get("record_version"));
    if (version != 1 && !(version == 2 && row.get("record_type").equals(CreditAuditFacts.DAILY)))
      throw new IllegalArgumentException("Unsupported credit event version");
    var world = new WorldId(UUID.fromString(row.get("world_id")));
    String payload = row.get("payload");
    Payload value;
    WorldId sourceWorld;
    switch (row.get("record_type")) {
      case CreditFacilities.FACT -> {
        var snapshot = CreditFacilities.decodeSnapshot(payload);
        value = new FacilityChanged(snapshot);
        sourceWorld = snapshot.world();
      }
      case CreditFacilities.STATEMENT_FACT -> {
        var snapshot = CreditFacilities.decodeStatementSnapshot(payload);
        value = new StatementClosed(snapshot);
        sourceWorld = snapshot.world();
      }
      case CreditAuditFacts.DAILY -> {
        var witness = CreditAuditFacts.decodeDaily(payload);
        if (witness.policyVersion() != version)
          throw new IllegalArgumentException("Credit daily witness version");
        value = new InterestPosted(witness);
        sourceWorld = witness.world();
      }
      case CreditAuditFacts.PAYMENT -> {
        var witness = CreditAuditFacts.decodePayment(payload);
        value = new PaymentReceived(witness);
        sourceWorld = witness.world();
      }
      default -> throw new IllegalArgumentException("Unsupported credit source");
    }
    if (!sourceWorld.equals(world))
      throw new IllegalArgumentException("Credit source world binding");
    return new Event(
        new Envelope(
            Long.parseLong(row.get("sequence")),
            Integer.parseInt(row.get("position")),
            world,
            UUID.fromString(row.get("transaction_id")),
            UUID.fromString(row.get("operation_id")),
            Instant.parse(row.get("audit_utc")),
            nullable(row.get("economic_update_id")),
            nullable(row.get("correction_of"))),
        version,
        value);
  }

  private static UUID nullable(String value) {
    return value == null || value.isEmpty() ? null : UUID.fromString(value);
  }

  private static WorldId world(JournalEngine.ReadView reads) {
    return new WorldId(
        UUID.fromString(
            reads
                .query("SELECT world_id FROM foundation_metadata", List.of(), 1)
                .getFirst()
                .get("world_id")));
  }

  public static void validateSchema(Connection connection) throws SQLException {
    try (var q =
            connection.prepareStatement(
                "SELECT sql FROM sqlite_schema WHERE name='credit_event_feed'");
        var r = q.executeQuery()) {
      if (!r.next() || !INDEX.equals(r.getString(1)))
        throw new StorageException(StorageException.Kind.SCHEMA, "Missing credit event feed index");
    }
  }
}
