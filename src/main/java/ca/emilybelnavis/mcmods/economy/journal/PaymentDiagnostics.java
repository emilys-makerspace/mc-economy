package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.ActorId;
import ca.emilybelnavis.mcmods.economy.api.Money;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/** Detached, read-only payment inspection on the world storage worker. */
public final class PaymentDiagnostics {
  public static final int MAX_PAGE_SIZE = 63;

  /** Derived from authenticated server authority. Recheck operator authority on the worker. */
  @FunctionalInterface
  public interface Operators {
    boolean allows(ActorId actor, JournalEngine.ReadView reads);
  }

  public record Invoice(
      UUID id,
      ActorId merchant,
      ActorId payer,
      UUID source,
      UUID target,
      Money amount,
      String state,
      UUID hold,
      String holdState,
      Long deadline,
      boolean overdue,
      String releaseResult) {}

  public record Page(List<Invoice> invoices, UUID nextCursor, long sampledMillis) {
    public Page {
      invoices = List.copyOf(invoices);
    }
  }

  public record Audit(
      UUID operation,
      UUID transaction,
      Long sequence,
      String actor,
      String action,
      String status,
      String resultCode,
      String auditUtc,
      boolean receipt) {}

  private PaymentDiagnostics() {}

  /**
   * Ascending invoice-ID pagination. Overdue-only pages expose the visible backlog without an
   * unbounded count. A next cursor means more matching invoices exist.
   */
  public static Page page(
      JournalEngine.ReadView reads,
      ActorId actor,
      Operators operators,
      UUID after,
      int limit,
      boolean overdueOnly,
      Clock wall) {
    if (limit < 1 || limit > MAX_PAGE_SIZE) throw new IllegalArgumentException("Payment page size");
    Objects.requireNonNull(actor);
    boolean operator = operators.allows(actor, reads);
    long now =
        Math.max(
            wall.millis(),
            Long.parseLong(
                reads
                    .query("SELECT millis FROM payment_clock WHERE singleton=1", List.of(), 1)
                    .getFirst()
                    .get("millis")));
    var rows =
        reads.query(
            """
                SELECT i.*,h.state AS hold_state FROM payment_invoices i
                LEFT JOIN ledger_holds h ON h.hold_id=i.hold
                WHERE i.id>? AND (?='1' OR i.merchant=? OR i.payer=?)
                AND (?='0' OR (i.state='AUTHORIZED' AND i.deadline<=CAST(? AS INTEGER)))
                ORDER BY i.id LIMIT ?
                """,
            List.of(
                after == null ? "" : after.toString(),
                operator ? "1" : "0",
                Journal.actorKey(actor),
                Journal.actorKey(actor),
                overdueOnly ? "1" : "0",
                Long.toString(now),
                Integer.toString(limit + 1)),
            limit + 1);
    var invoices = rows.stream().limit(limit).map(row -> invoice(reads, row, now)).toList();
    return new Page(invoices, rows.size() > limit ? invoices.getLast().id() : null, now);
  }

  /** Exact operation lookup, authorized against the invoice before any audit disclosure. */
  public static Optional<Audit> audit(
      JournalEngine.ReadView reads,
      ActorId actor,
      Operators operators,
      UUID invoice,
      UUID operation) {
    var visible =
        reads.query(
            """
                SELECT id FROM payment_invoices WHERE id=? AND (?='1' OR merchant=? OR payer=?)
                """,
            List.of(
                invoice.toString(),
                operators.allows(actor, reads) ? "1" : "0",
                Journal.actorKey(actor),
                Journal.actorKey(actor)),
            1);
    if (visible.isEmpty()) return Optional.empty();
    var outcomes =
        reads.query(
            """
                SELECT o.*,r.invoice_id AS receipt_invoice FROM operation_outcomes o
                LEFT JOIN payment_receipts r ON r.operation_id=o.operation_id
                WHERE o.operation_id=? AND o.service IN ('economy:merchant_payment','economy:register_sales')
                """,
            List.of(operation.toString()),
            1);
    if (outcomes.isEmpty()) return Optional.empty();
    var outcome = outcomes.getFirst();
    // Only committed invoice facts prove association. Rejected requests have no invoice fact.
    var sequence = outcome.get("sequence");
    if (sequence == null) return Optional.empty();
    var facts =
        reads.query(
            """
                SELECT payload FROM journal_records WHERE sequence=?
                AND record_type=? AND record_version=1 ORDER BY position LIMIT 64
                """,
            List.of(sequence, MerchantPayments.FACT),
            64);
    boolean associated =
        facts.stream()
            .anyMatch(
                row -> {
                  var parts = row.get("payload").split("\\|", -1);
                  return parts.length > 1
                      && parts[1].equals(invoice.toString())
                      && !parts[0].equals("GRANT")
                      && !parts[0].equals("REVOKE");
                });
    if (!associated) return Optional.empty();
    return Optional.of(
        new Audit(
            operation,
            UUID.fromString(outcome.get("transaction_id")),
            Long.parseLong(sequence),
            outcome.get("actor"),
            outcome.get("operation_type"),
            outcome.get("status"),
            outcome.get("result_code"),
            outcome.get("audit_utc"),
            invoice.toString().equals(outcome.get("receipt_invoice"))));
  }

  private static Invoice invoice(JournalEngine.ReadView reads, Map<String, String> row, long now) {
    var id = UUID.fromString(row.get("id"));
    var deadline = row.get("deadline") == null ? null : Long.valueOf(row.get("deadline"));
    boolean overdue = row.get("state").equals("AUTHORIZED") && deadline != null && now >= deadline;
    String release = null;
    if (overdue || row.get("state").equals("EXPIRED")) {
      var operation =
          UUID.nameUUIDFromBytes(("economy:payment-expiry:" + id).getBytes(StandardCharsets.UTF_8));
      release =
          reads
              .query(
                  """
                    SELECT status,result_code FROM operation_outcomes
                    WHERE operation_id=? AND service='economy:merchant_payment' AND operation_type='economy:expire'
                    """,
                  List.of(operation.toString()),
                  1)
              .stream()
              .map(result -> result.get("status") + "/" + result.get("result_code"))
              .findFirst()
              .orElse("NO_DURABLE_OUTCOME");
    }
    return new Invoice(
        id,
        Journal.actor(row.get("merchant")),
        Journal.actor(row.get("payer")),
        UUID.fromString(row.get("source")),
        UUID.fromString(row.get("target")),
        new Money(EconomyCurrency.valueOf(row.get("currency")), Long.parseLong(row.get("units"))),
        row.get("state"),
        row.get("hold") == null ? null : UUID.fromString(row.get("hold")),
        row.get("hold_state"),
        deadline,
        overdue,
        release);
  }
}
