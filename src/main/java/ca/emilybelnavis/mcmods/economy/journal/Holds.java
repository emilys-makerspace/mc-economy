package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Role.ACCOUNT;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.ActorId;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.util.*;

/** Worker-side reservations for authenticated workflows using the durable journal. */
public final class Holds {
  public static final String FACT = "economy:hold";

  public enum State {
    ACTIVE,
    CAPTURED,
    RELEASED
  }

  public record Hold(UUID id, UUID account, Money amount, State state) {}

  @FunctionalInterface
  public interface Authority {
    /** Check live workflow authority over source and optional capture destination. */
    boolean allows(Journal.Intent intent, UUID source, UUID target, JournalEngine.ReadView reads);
  }

  private Holds() {}

  public static Journal.CanonicalRequest reserveRequest(UUID account, Money amount) {
    return request(
        Map.of(
            "account",
            account.toString(),
            "currency",
            amount.currency().name(),
            "units",
            Long.toString(amount.minorUnits())));
  }

  public static Journal.CanonicalRequest releaseRequest(UUID hold) {
    return request(Map.of("hold", hold.toString()));
  }

  public static Journal.CanonicalRequest captureRequest(UUID hold, UUID target) {
    return request(Map.of("hold", hold.toString(), "target", target.toString()));
  }

  public static JournalEngine.Registration reserve(Authority authority) {
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          UUID account;
          Money amount;
          try {
            var fields = intent.request().fields();
            account = parseId(fields.get("account"));
            amount = parseAmount(fields.get("currency"), fields.get("units"));
            if (!reserveRequest(account, amount).equals(intent.request()))
              return reject("HOLD_REQUEST");
          } catch (IllegalArgumentException malformed) {
            return reject("HOLD_REQUEST");
          }
          if (!authority.allows(intent, account, null, reads)) return reject("HOLD_ACCESS");
          if (!Settlement.openBank(reads, account, amount.currency()))
            return reject("HOLD_ACCOUNT");
          if (available(reads, account, amount.currency()) < amount.minorUnits())
            return reject("INSUFFICIENT_FUNDS");
          var hold = new Hold(intent.operationId().value(), account, amount, State.ACTIVE);
          return commit(
              hold,
              List.of(),
              tx ->
                  tx.execute(
                      """
                    INSERT INTO ledger_holds(hold_id,account_id,currency,minor_units,actor,state)
                    VALUES(?,?,?,?,?,'ACTIVE')
                    """,
                      List.of(
                          hold.id().toString(),
                          account.toString(),
                          amount.currency().name(),
                          Long.toString(amount.minorUnits()),
                          Journal.actorKey(intent.actor()))));
        });
  }

  public static JournalEngine.Registration release(Authority authority) {
    return transition(authority, State.RELEASED);
  }

  public static JournalEngine.Registration capture(Authority authority) {
    return transition(authority, State.CAPTURED);
  }

  private static JournalEngine.Registration transition(Authority authority, State state) {
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          UUID id;
          UUID target;
          try {
            var fields = intent.request().fields();
            id = parseId(fields.get("hold"));
            target = state == State.CAPTURED ? parseId(fields.get("target")) : null;
            var canonical = target == null ? releaseRequest(id) : captureRequest(id, target);
            if (!canonical.equals(intent.request())) return reject("HOLD_REQUEST");
          } catch (IllegalArgumentException malformed) {
            return reject("HOLD_REQUEST");
          }
          var hold = find(reads, intent.actor(), id).orElse(null);
          if (hold == null || !authority.allows(intent, hold.account(), target, reads))
            return reject("HOLD_ACCESS");
          if (hold.state() != State.ACTIVE) return reject("HOLD_" + hold.state().name());

          var next = new Hold(id, hold.account(), hold.amount(), state);
          if (target == null) return commit(next, List.of(), tx -> update(tx, next));
          if (target.equals(hold.account())) return reject("HOLD_DESTINATION");
          var amount = hold.amount();
          var legs =
              List.of(
                  new PostingBatch.Leg(hold.account(), ACCOUNT, amount.currency(), amount.negate()),
                  new PostingBatch.Leg(target, ACCOUNT, amount.currency(), amount));
          var settlement = Settlement.prepare(reads, legs, PostingBatch.Authority.TRANSFER, hold);
          if (!(settlement instanceof JournalEngine.Commit postings)) return settlement;
          return commit(
              next,
              postings.records(),
              tx -> {
                postings.projections().apply(tx);
                update(tx, next);
              });
        });
  }

  /** Trusted immediate checkout still records a real reservation and captured hold together. */
  static JournalEngine.Decision checkout(
      Journal.Intent intent, UUID source, UUID target, Money amount, JournalEngine.ReadView reads)
      throws Exception {
    if (source.equals(target)) return reject("HOLD_DESTINATION");
    var reservationIntent =
        new Journal.Intent(
            intent.operationId(),
            intent.requestId(),
            intent.actor(),
            intent.kind(),
            reserveRequest(source, amount),
            null,
            null);
    var reserved = reserve((i, from, to, view) -> true).handler().prepare(reservationIntent, reads);
    if (!(reserved instanceof JournalEngine.Commit reservation)) return reserved;
    var settlement =
        Settlement.prepare(
            reads,
            List.of(
                new PostingBatch.Leg(source, ACCOUNT, amount.currency(), amount.negate()),
                new PostingBatch.Leg(target, ACCOUNT, amount.currency(), amount)),
            PostingBatch.Authority.TRANSFER,
            null);
    if (!(settlement instanceof JournalEngine.Commit postings)) return settlement;
    var captured = new Hold(intent.operationId().value(), source, amount, State.CAPTURED);
    var records = new ArrayList<>(reservation.records());
    records.addAll(postings.records());
    return commit(
        captured,
        records,
        tx -> {
          reservation.projections().apply(tx);
          postings.projections().apply(tx);
          update(tx, captured);
        });
  }

  public static long available(
      JournalEngine.ReadView reads, UUID account, EconomyCurrency currency) {
    return Math.subtractExact(
        Settlement.posted(reads, account, currency), reserved(reads, account, currency));
  }

  public static long reserved(
      JournalEngine.ReadView reads, UUID account, EconomyCurrency currency) {
    var row =
        reads
            .query(
                """
                SELECT COALESCE(SUM(minor_units),0) AS held FROM ledger_holds
                WHERE account_id=? AND currency=? AND state='ACTIVE'
                """,
                List.of(account.toString(), currency.name()),
                1)
            .getFirst();
    return Long.parseLong(row.get("held"));
  }

  public static boolean hasActiveClaim(JournalEngine.ReadView reads, UUID account) {
    return !reads
        .query(
            """
                SELECT hold_id FROM ledger_holds WHERE account_id=? AND state='ACTIVE' LIMIT 1
                """,
            List.of(account.toString()),
            1)
        .isEmpty();
  }

  public static Optional<Hold> find(JournalEngine.ReadView reads, ActorId actor, UUID id) {
    return reads
        .query(
            "SELECT * FROM ledger_holds WHERE actor=? AND hold_id=?",
            List.of(Journal.actorKey(actor), id.toString()),
            1)
        .stream()
        .map(Holds::read)
        .findFirst();
  }

  public static List<Hold> active(
      JournalEngine.ReadView reads, ActorId actor, UUID after, int limit) {
    if (limit < 1 || limit > 64) throw new IllegalArgumentException("Hold page size");
    return reads
        .query(
            """
                SELECT * FROM ledger_holds WHERE actor=? AND state='ACTIVE' AND hold_id>?
                ORDER BY hold_id LIMIT ?
                """,
            List.of(
                Journal.actorKey(actor),
                after == null ? "" : after.toString(),
                Integer.toString(limit)),
            limit)
        .stream()
        .map(Holds::read)
        .toList();
  }

  public static void validateFact(String payload) {
    validateFact(payload, false);
  }

  public static void validateFactV2(String payload) {
    validateFact(payload, true);
  }

  private static void validateFact(String payload, boolean minorChf) {
    var fields = payload.split("\\|", -1);
    if (fields.length != 5) throw new IllegalArgumentException("Hold fact fields");
    parseId(fields[0]);
    parseId(fields[1]);
    var amount = parseAmount(fields[2], fields[3]);
    if ((amount.currency() == EconomyCurrency.CHF) != minorChf)
      throw new IllegalArgumentException("Hold fact currency/version mismatch");
    if (minorChf && !Long.toString(amount.minorUnits()).equals(fields[3]))
      throw new IllegalArgumentException("Noncanonical hold fact amount");
    State.valueOf(fields[4]);
  }

  private static Hold read(Map<String, String> row) {
    return new Hold(
        parseId(row.get("hold_id")),
        parseId(row.get("account_id")),
        parseAmount(row.get("currency"), row.get("minor_units")),
        State.valueOf(row.get("state")));
  }

  private static UUID parseId(String value) {
    if (value == null) throw new IllegalArgumentException("Missing hold identity");
    var id = UUID.fromString(value);
    if (!id.toString().equals(value))
      throw new IllegalArgumentException("Noncanonical hold identity");
    return id;
  }

  private static Money parseAmount(String currency, String units) {
    if (currency == null || units == null)
      throw new IllegalArgumentException("Missing hold amount");
    var amount = new Money(EconomyCurrency.valueOf(currency), Long.parseLong(units));
    if (amount.minorUnits() <= 0
        || amount.minorUnits()
                % (amount.currency() == EconomyCurrency.CHF
                    ? 1
                    : amount.currency().minorUnitsPerMajor())
            != 0) {
      throw new IllegalArgumentException("Invalid hold amount");
    }
    return amount;
  }

  private static Journal.CanonicalRequest request(Map<String, String> fields) {
    return new Journal.CanonicalRequest(1, fields);
  }

  private static void update(FoundationStore.Transaction tx, Hold hold) {
    int updated =
        tx.execute(
            "UPDATE ledger_holds SET state=? WHERE hold_id=? AND state='ACTIVE'",
            List.of(hold.state().name(), hold.id().toString()));
    if (updated != 1) throw new IllegalStateException("Active hold changed during settlement");
  }

  private static JournalEngine.Commit commit(
      Hold hold, List<Journal.Record> postings, JournalEngine.Projection projection) {
    var records = new ArrayList<>(postings);
    records.add(
        new Journal.Record(
            FACT,
            hold.amount().currency() == EconomyCurrency.CHF ? 2 : 1,
            hold.id()
                + "|"
                + hold.account()
                + "|"
                + hold.amount().currency().name()
                + "|"
                + hold.amount().minorUnits()
                + "|"
                + hold.state().name()));
    return new JournalEngine.Commit(
        records, new Journal.Result(1, "HOLD_" + hold.state().name(), ""), projection);
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }
}
