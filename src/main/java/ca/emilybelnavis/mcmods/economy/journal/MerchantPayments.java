package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;
import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Role.ACCOUNT;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

/** Trusted payment workflow. All business decisions run on the world storage worker. */
public final class MerchantPayments {
  public static final String FACT = "economy:merchant_payment";
  public static final Duration AUTHORIZATION_LIFETIME = Duration.ofMinutes(5);
  private static final SystemActorId ESCROW =
      new SystemActorId(UUID.fromString("42cf3946-816e-484d-9809-8ef477be596d"));

  public enum Action {
    GRANT,
    REVOKE,
    OPEN,
    AUTHORIZE,
    CAPTURE,
    CANCEL,
    REFUND,
    EXPIRE;

    public Kind kind() {
      return new Kind("economy:merchant_payment", "economy:" + name().toLowerCase(Locale.ROOT));
    }
  }

  public record Terms(ActorId payer, UUID source, UUID target, Money amount) {
    public Terms {
      Objects.requireNonNull(payer);
      Objects.requireNonNull(source);
      Objects.requireNonNull(target);
      if (source.equals(target)
          || amount.minorUnits() <= 0
          || amount.minorUnits() % amount.currency().minorUnitsPerMajor() != 0)
        throw new IllegalArgumentException("Invalid payment terms");
    }

    public CanonicalRequest request() {
      return new CanonicalRequest(
          1,
          Map.of(
              "payer",
              actorKey(payer),
              "source",
              source.toString(),
              "target",
              target.toString(),
              "currency",
              amount.currency().name(),
              "units",
              Long.toString(amount.minorUnits())));
    }
  }

  /** Trusted integration policy. Check live account control and operator authority here. */
  @FunctionalInterface
  public interface Authority {
    boolean allows(Action action, ActorId actor, ActorId merchant, Terms terms, ReadView reads);
  }

  private enum State {
    OPEN,
    AUTHORIZED,
    CAPTURED,
    CANCELLED,
    REFUNDED,
    EXPIRED
  }

  private record Invoice(
      UUID id, ActorId merchant, Terms terms, State state, UUID hold, long deadline) {}

  private MerchantPayments() {}

  public static CanonicalRequest invoiceRequest(UUID invoice) {
    return new CanonicalRequest(1, Map.of("invoice", invoice.toString()));
  }

  public static CanonicalRequest merchantRequest(ActorId merchant) {
    return new CanonicalRequest(1, Map.of("merchant", actorKey(merchant)));
  }

  public static Registration registration(Action action, Authority authority, Clock wall) {
    if (action == Action.EXPIRE) throw new IllegalArgumentException("Expiry is internal");
    return handler(action, Objects.requireNonNull(authority), wall);
  }

  /**
   * Trusted register-owner checkout. Caller validates current pricing, primaries and reserved
   * stock.
   */
  public static Decision checkout(
      Intent intent,
      UUID invoice,
      ActorId merchant,
      UUID source,
      UUID target,
      Money amount,
      ReadView reads,
      Clock wall)
      throws Exception {
    var terms = new Terms(intent.actor(), source, target, amount);
    if (!reads
        .query("SELECT id FROM payment_invoices WHERE id=?", List.of(invoice.toString()), 1)
        .isEmpty()) return reject("PAYMENT_INVOICE_EXISTS");
    var holdIntent =
        new Intent(
            intent.operationId(),
            intent.requestId(),
            ESCROW,
            intent.kind(),
            Holds.reserveRequest(source, amount),
            null,
            null);
    var decision = Holds.checkout(holdIntent, source, target, amount, reads);
    if (decision instanceof Reject rejected
        && rejected.result().code().equals("INSUFFICIENT_FUNDS"))
      return retry("INSUFFICIENT_FUNDS");
    if (!(decision instanceof Commit captured)) return decision;
    long now =
        Math.max(
            wall.millis(),
            Long.parseLong(
                reads
                    .query("SELECT millis FROM payment_clock WHERE singleton=1", List.of(), 1)
                    .getFirst()
                    .get("millis")));
    var operation = intent.operationId().value();
    return commit(
        Action.CAPTURE,
        invoice,
        operation + "|" + now + "|" + operation,
        now,
        captured,
        tx -> {
          tx.execute(
              "INSERT INTO payment_invoices VALUES(?,?,?,?,?,?,?,'CAPTURED',?,?)",
              List.of(
                  invoice.toString(),
                  actorKey(merchant),
                  actorKey(terms.payer()),
                  source.toString(),
                  target.toString(),
                  amount.currency().name(),
                  Long.toString(amount.minorUnits()),
                  operation.toString(),
                  Long.toString(now)));
          tx.execute(
              "INSERT INTO payment_receipts VALUES(?,?,'CAPTURE')",
              List.of(operation.toString(), invoice.toString()));
          tx.execute(
              "INSERT OR IGNORE INTO register_merchants(owner) VALUES(?)",
              List.of(actorKey(merchant)));
        });
  }

  private static Registration handler(Action action, Authority authority, Clock wall) {
    return new Registration(
        1,
        (intent, reads) ->
            prepare(
                action,
                authority,
                intent,
                reads,
                Math.max(
                    wall.millis(),
                    Long.parseLong(
                        reads
                            .query(
                                "SELECT millis FROM payment_clock WHERE singleton=1", List.of(), 1)
                            .getFirst()
                            .get("millis")))));
  }

  /** Persist the high-water mark even when there are no overdue invoices. */
  public static void expireBatch(FoundationStore store, Clock wall) {
    var due =
        store.transaction(
            tx -> {
              tx.execute(
                  "UPDATE payment_clock SET millis=max(millis,CAST(? AS INTEGER)) WHERE singleton=1",
                  List.of(Long.toString(Math.max(0, wall.millis()))));
              return tx.query(
                  """
                    SELECT id FROM payment_invoices WHERE state='AUTHORIZED'
                    AND deadline<=(SELECT millis FROM payment_clock WHERE singleton=1)
                    ORDER BY deadline,id LIMIT 16
                    """,
                  List.of(),
                  16);
            });
    if (due.isEmpty()) return;
    var action = Action.EXPIRE;
    var engine =
        new JournalEngine(
            store,
            (actor, kind) -> actor.equals(ESCROW) && kind.equals(action.kind()),
            Map.of(
                action.kind(), handler(action, (a, actor, merchant, terms, reads) -> true, wall)));
    for (var row : due) {
      var id = UUID.fromString(row.get("id"));
      var op =
          UUID.nameUUIDFromBytes(("economy:payment-expiry:" + id).getBytes(StandardCharsets.UTF_8));
      engine.submit(
          new Intent(
              new OperationId(op),
              new RequestId(UUID.randomUUID()),
              ESCROW,
              action.kind(),
              invoiceRequest(id),
              null,
              null));
    }
  }

  private static Decision prepare(
      Action action, Authority authority, Intent intent, ReadView reads, long now)
      throws Exception {
    var fields = intent.request().fields();
    var id = intent.operationId().value();
    if (action == Action.GRANT || action == Action.REVOKE) {
      var merchant = actor(fields.get("merchant"));
      if (!merchantRequest(merchant).equals(intent.request())) return reject("PAYMENT_REQUEST");
      if (!authority.allows(action, intent.actor(), merchant, null, reads))
        return decline("PAYMENT_ACCESS");
      return commit(
          action,
          id,
          actorKey(merchant),
          now,
          null,
          tx ->
              tx.execute(
                  """
                    INSERT INTO payment_merchants VALUES(?,?) ON CONFLICT(actor) DO UPDATE SET enabled=excluded.enabled
                    """,
                  List.of(actorKey(merchant), action == Action.GRANT ? "1" : "0")));
    }
    if (action == Action.OPEN) {
      var terms = terms(fields);
      if (!terms.request().equals(intent.request())) return reject("PAYMENT_REQUEST");
      var merchant = actorKey(intent.actor());
      if (!granted(reads, intent.actor())
          || !authority.allows(action, intent.actor(), intent.actor(), terms, reads))
        return decline("PAYMENT_ACCESS");
      if (!Settlement.openBank(reads, terms.source(), terms.amount().currency())
          || !Settlement.openBank(reads, terms.target(), terms.amount().currency()))
        return reject("PAYMENT_ACCOUNT");
      return commit(
          action,
          id,
          String.join(
              "|",
              merchant,
              actorKey(terms.payer()),
              terms.source().toString(),
              terms.target().toString(),
              terms.amount().currency().name(),
              Long.toString(terms.amount().minorUnits())),
          now,
          null,
          tx ->
              tx.execute(
                  """
                    INSERT INTO payment_invoices VALUES(?,?,?,?,?,?,?,'OPEN',NULL,NULL)
                    """,
                  List.of(
                      id.toString(),
                      merchant,
                      actorKey(terms.payer()),
                      terms.source().toString(),
                      terms.target().toString(),
                      terms.amount().currency().name(),
                      Long.toString(terms.amount().minorUnits()))));
    }
    var invoiceId = UUID.fromString(fields.get("invoice"));
    if (!invoiceRequest(invoiceId).equals(intent.request())) return reject("PAYMENT_REQUEST");
    var invoice =
        reads
            .query("SELECT * FROM payment_invoices WHERE id=?", List.of(invoiceId.toString()), 1)
            .stream()
            .map(MerchantPayments::invoice)
            .findFirst()
            .orElse(null);
    if (invoice == null
        || !authority.allows(action, intent.actor(), invoice.merchant(), invoice.terms(), reads))
      return decline("PAYMENT_ACCESS");
    var terms = invoice.terms();
    boolean merchant =
        intent.actor().equals(invoice.merchant()) && granted(reads, invoice.merchant());
    boolean payer = intent.actor().equals(terms.payer());
    return switch (action) {
      case AUTHORIZE -> {
        if (!payer || !granted(reads, invoice.merchant())) yield decline("PAYMENT_ACCESS");
        if (invoice.state() != State.OPEN) yield reject("PAYMENT_STATE");
        var reserve =
            hold(
                intent,
                reads,
                Holds.reserve((i, s, t, r) -> true),
                Holds.reserveRequest(terms.source(), terms.amount()));
        yield transition(
            action,
            invoice,
            State.AUTHORIZED,
            id,
            Math.addExact(now, AUTHORIZATION_LIFETIME.toMillis()),
            intent,
            now,
            reserve);
      }
      case CAPTURE -> {
        if (!merchant) yield decline("PAYMENT_ACCESS");
        if (invoice.state() != State.AUTHORIZED) yield reject("PAYMENT_STATE");
        if (now >= invoice.deadline())
          yield new Reject(new Result(1, "PAYMENT_DEADLINE", ""), tx -> advanceClock(tx, now));
        yield transition(
            action,
            invoice,
            State.CAPTURED,
            invoice.hold(),
            invoice.deadline(),
            intent,
            now,
            hold(
                intent,
                reads,
                Holds.capture((i, s, t, r) -> true),
                Holds.captureRequest(invoice.hold(), terms.target())));
      }
      case CANCEL, EXPIRE -> {
        if (action == Action.CANCEL && !merchant && !payer) yield decline("PAYMENT_ACCESS");
        if (invoice.state() != State.AUTHORIZED) yield reject("PAYMENT_STATE");
        if (action == Action.EXPIRE && now < invoice.deadline()) yield decline("PAYMENT_NOT_DUE");
        yield transition(
            action,
            invoice,
            action == Action.CANCEL ? State.CANCELLED : State.EXPIRED,
            invoice.hold(),
            invoice.deadline(),
            intent,
            now,
            hold(
                intent,
                reads,
                Holds.release((i, s, t, r) -> true),
                Holds.releaseRequest(invoice.hold())));
      }
      case REFUND -> {
        if (!merchant && intent.actor().equals(invoice.merchant())) yield decline("PAYMENT_ACCESS");
        if (invoice.state() != State.CAPTURED) yield reject("PAYMENT_STATE");
        var amount = terms.amount();
        var legs =
            List.of(
                new PostingBatch.Leg(terms.target(), ACCOUNT, amount.currency(), amount.negate()),
                new PostingBatch.Leg(terms.source(), ACCOUNT, amount.currency(), amount));
        var refund = Settlement.prepare(reads, legs, PostingBatch.Authority.TRANSFER, null);
        if (refund instanceof Reject rejection
            && rejection.result().code().equals("INSUFFICIENT_FUNDS"))
          yield retry("INSUFFICIENT_FUNDS");
        yield transition(
            action,
            invoice,
            State.REFUNDED,
            invoice.hold(),
            invoice.deadline(),
            intent,
            now,
            refund);
      }
      default -> throw new IllegalStateException("Unexpected invoice action");
    };
  }

  private static boolean granted(ReadView reads, ActorId merchant) {
    return !reads
            .query(
                "SELECT actor FROM payment_merchants WHERE actor=? AND enabled=1",
                List.of(actorKey(merchant)),
                1)
            .isEmpty()
        || !reads
            .query(
                "SELECT owner FROM register_merchants WHERE owner=?",
                List.of(actorKey(merchant)),
                1)
            .isEmpty();
  }

  private static Terms terms(Map<String, String> fields) {
    return new Terms(
        actor(fields.get("payer")),
        UUID.fromString(fields.get("source")),
        UUID.fromString(fields.get("target")),
        new Money(
            EconomyCurrency.valueOf(fields.get("currency")), Long.parseLong(fields.get("units"))));
  }

  private static Invoice invoice(Map<String, String> row) {
    return new Invoice(
        UUID.fromString(row.get("id")),
        actor(row.get("merchant")),
        terms(row),
        State.valueOf(row.get("state")),
        row.get("hold") == null ? null : UUID.fromString(row.get("hold")),
        row.get("deadline") == null ? 0 : Long.parseLong(row.get("deadline")));
  }

  private static Decision hold(
      Intent intent, ReadView reads, Registration registration, CanonicalRequest request)
      throws Exception {
    return registration
        .handler()
        .prepare(
            new Intent(
                intent.operationId(),
                intent.requestId(),
                ESCROW,
                intent.kind(),
                request,
                null,
                null),
            reads);
  }

  private static Decision transition(
      Action action,
      Invoice invoice,
      State state,
      UUID hold,
      long deadline,
      Intent intent,
      long now,
      Decision base) {
    if (!(base instanceof Commit composed)) return base;
    var operation = intent.operationId().value();
    return commit(
        action,
        invoice.id(),
        hold + "|" + deadline + "|" + operation,
        now,
        composed,
        tx -> {
          tx.execute(
              "UPDATE payment_invoices SET state=?,hold=?,deadline=? WHERE id=?",
              List.of(
                  state.name(), hold.toString(), Long.toString(deadline), invoice.id().toString()));
          if (action == Action.CAPTURE || action == Action.REFUND)
            tx.execute(
                "INSERT INTO payment_receipts VALUES(?,?,?)",
                List.of(operation.toString(), invoice.id().toString(), action.name()));
        });
  }

  private static Commit commit(
      Action action, UUID id, String detail, long now, Commit base, Projection projection) {
    var records = new ArrayList<Journal.Record>();
    if (base != null) records.addAll(base.records());
    records.add(new Journal.Record(FACT, 1, action.name() + "|" + id + "|" + now + "|" + detail));
    return new Commit(
        records,
        new Result(1, "PAYMENT_" + action.name(), id.toString()),
        tx -> {
          if (base != null) base.projections().apply(tx);
          projection.apply(tx);
          advanceClock(tx, now);
        });
  }

  private static void advanceClock(FoundationStore.Transaction tx, long now) {
    tx.execute(
        "UPDATE payment_clock SET millis=max(millis,CAST(? AS INTEGER)) WHERE singleton=1",
        List.of(Long.toString(now)));
  }

  public record ReceiptView(UUID operation, UUID invoice, Action action, Terms terms) {}

  @FunctionalInterface
  public interface ReceiptAuthority {
    boolean allows(ActorId actor, ActorId merchant, Terms terms, ReadView reads);
  }

  public static Optional<ReceiptView> receipt(
      ReadView reads, ActorId actor, UUID operation, ReceiptAuthority authority) {
    return reads
        .query(
            """
                SELECT i.*,r.action FROM payment_receipts r JOIN payment_invoices i ON i.id=r.invoice_id
                WHERE r.operation_id=?
                """,
            List.of(operation.toString()),
            1)
        .stream()
        .filter(
            row -> authority.allows(actor, Journal.actor(row.get("merchant")), terms(row), reads))
        .map(
            row ->
                new ReceiptView(
                    operation,
                    UUID.fromString(row.get("id")),
                    Action.valueOf(row.get("action")),
                    terms(row)))
        .findFirst();
  }

  public static void validateFact(String payload) {
    var parts = payload.split("\\|", -1);
    var action = Action.valueOf(parts[0]);
    UUID.fromString(parts[1]);
    Long.parseLong(parts[2]);
    switch (action) {
      case GRANT, REVOKE -> {
        if (parts.length != 4) throw new IllegalArgumentException("Merchant fact");
        actor(parts[3]);
      }
      case OPEN -> {
        if (parts.length != 9) throw new IllegalArgumentException("Invoice fact");
        actor(parts[3]);
        terms(
            Map.of(
                "payer",
                parts[4],
                "source",
                parts[5],
                "target",
                parts[6],
                "currency",
                parts[7],
                "units",
                parts[8]));
      }
      default -> {
        if (parts.length != 6) throw new IllegalArgumentException("Payment transition fact");
        UUID.fromString(parts[3]);
        Long.parseLong(parts[4]);
        UUID.fromString(parts[5]);
      }
    }
  }

  private static Decline decline(String code) {
    return new Decline(new Result(1, code, ""));
  }

  private static Reject reject(String code) {
    return new Reject(new Result(1, code, ""));
  }

  private static Retry retry(String code) {
    return new Retry(new Result(1, code, ""));
  }
}
