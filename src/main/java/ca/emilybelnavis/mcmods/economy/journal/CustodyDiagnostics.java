package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.ActorId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import java.nio.file.Path;
import java.util.*;

/** Authorized, bounded observations. No reconciliation or recovery mutations occur here. */
public final class CustodyDiagnostics {
  public enum Filter {
    ALL,
    QUARANTINED,
    UNRESOLVED
  }

  public enum Slot {
    RESERVATION,
    DELIVERY
  }

  public enum WitnessStatus {
    ABSENT,
    MATCH,
    MISMATCH,
    MISSING,
    SCAN_LIMIT,
    UNSAFE_LOCATION,
    UNAVAILABLE,
    UNSUPPORTED
  }

  public record Page(List<Custody.Case> cases, UUID nextCursor, boolean pageLimitReached) {
    public Page {
      cases = List.copyOf(cases);
    }
  }

  public record Witness(Slot slot, Custody.Evidence evidence, WitnessStatus status) {}

  public record Audit(
      UUID operation,
      UUID transaction,
      long sequence,
      String actor,
      Custody.Action action,
      String resultCode,
      String auditUtc,
      Custody.Case snapshot) {}

  private CustodyDiagnostics() {}

  /** Filter and owner scope are applied before pagination. There is no unbounded backlog count. */
  public static Page page(
      JournalEngine.ReadView reads,
      ActorId actor,
      PaymentDiagnostics.Operators operators,
      Filter filter,
      UUID after,
      int limit) {
    if (limit < 1 || limit > Custody.MAX_PAGE_SIZE)
      throw new IllegalArgumentException("Custody page size");
    Objects.requireNonNull(actor);
    Objects.requireNonNull(filter);
    boolean operator = operators.allows(actor, reads);
    var state =
        switch (filter) {
          case ALL -> "1=1";
          case QUARANTINED -> "state='QUARANTINED'";
          case UNRESOLVED -> "state NOT IN ('DELIVERED','COMPENSATED')";
        };
    var rows =
        reads.query(
            "SELECT id FROM custody_cases WHERE id>? AND (?='1' OR owner=?) AND "
                + state
                + " ORDER BY id LIMIT ?",
            List.of(
                after == null ? "" : after.toString(),
                operator ? "1" : "0",
                Journal.actorKey(actor),
                Integer.toString(limit + 1)),
            limit + 1);
    var cases =
        rows.stream()
            .limit(limit)
            .map(
                row ->
                    Custody.find(reads, actor, UUID.fromString(row.get("id")), operator)
                        .orElseThrow())
            .toList();
    boolean more = rows.size() > limit;
    return new Page(cases, more ? cases.getLast().id() : null, more);
  }

  /** Observe only the authorized case's recorded location, never a caller-supplied file path. */
  public static Optional<Witness> witness(
      JournalEngine.ReadView reads,
      ActorId actor,
      PaymentDiagnostics.Operators operators,
      UUID id,
      Slot slot,
      Path worldDirectory,
      WorldId world) {
    Objects.requireNonNull(slot);
    return Custody.find(reads, actor, id, operators.allows(actor, reads))
        .map(
            current -> {
              var encoded =
                  slot == Slot.RESERVATION
                      ? current.reservationWitness()
                      : current.deliveryWitness();
              if (encoded == null) return new Witness(slot, null, WitnessStatus.ABSENT);
              var evidence = Custody.decodeEvidence(encoded);
              try {
                var parts = evidence.saveToken().split(":", -1);
                if (parts.length != 5 || !parts[0].equals("v1"))
                  return new Witness(slot, evidence, WitnessStatus.UNSUPPORTED);
                var expected =
                    new CustodySaveWitness.Expected(
                        new WorldId(UUID.fromString(parts[1])),
                        UUID.fromString(parts[2]),
                        UUID.fromString(parts[3]),
                        evidence.location(),
                        parts[4]);
                if (!expected.world().equals(world) || !expected.custody().equals(id))
                  return new Witness(slot, evidence, WitnessStatus.MISMATCH);
                return new Witness(
                    slot,
                    evidence,
                    WitnessStatus.valueOf(
                        CustodySaveWitness.observe(worldDirectory, expected).name()));
              } catch (IllegalArgumentException malformed) {
                return new Witness(slot, evidence, WitnessStatus.UNSUPPORTED);
              }
            });
  }

  /** Exact operation correlation with its immutable custody snapshot and save evidence. */
  public static Optional<Audit> audit(
      JournalEngine.ReadView reads,
      ActorId actor,
      PaymentDiagnostics.Operators operators,
      UUID custody,
      UUID operation) {
    if (Custody.find(reads, actor, custody, operators.allows(actor, reads)).isEmpty())
      return Optional.empty();
    var outcomes =
        reads.query(
            """
                SELECT * FROM operation_outcomes WHERE operation_id=? AND service='economy:custody' AND status='COMMITTED'
                """,
            List.of(operation.toString()),
            1);
    if (outcomes.isEmpty()) return Optional.empty();
    var outcome = outcomes.getFirst();
    var facts =
        reads.query(
            """
                SELECT payload FROM journal_records WHERE sequence=? AND record_type=? AND record_version=1
                ORDER BY position LIMIT 64
                """,
            List.of(outcome.get("sequence"), Custody.FACT),
            64);
    for (var row : facts) {
      var snapshot = Custody.snapshot(row.get("payload"));
      if (!snapshot.id().equals(custody)) continue;
      var action =
          Custody.Action.valueOf(row.get("payload").substring(0, row.get("payload").indexOf('|')));
      return Optional.of(
          new Audit(
              operation,
              UUID.fromString(outcome.get("transaction_id")),
              Long.parseLong(outcome.get("sequence")),
              outcome.get("actor"),
              action,
              outcome.get("result_code"),
              outcome.get("audit_utc"),
              snapshot));
    }
    return Optional.empty();
  }
}
