package ca.emilybelnavis.mcmods.economy.trade.settlement;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.trade.cash.ReferenceCashTrades;
import java.sql.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Trusted cancellation of untouched originals, and independently witnessed account-input returns.
 */
public final class ReferenceInputReturns {
  private ReferenceInputReturns() {}

  public static final String ABANDON = "economy:a9/reference-untouched-abandon";
  private static final Journal.Kind KIND =
      new Journal.Kind("economy:a9", "economy:a9/reference-npc/abandon");

  private record Source(UUID world, boolean cash, UUID quote, UUID original, UUID input) {}

  private static Source decode(String payload) {
    if (payload == null || payload.length() > 256)
      throw new IllegalArgumentException("Abandon bound");
    var p = payload.split("\\|", -1);
    if (p.length != 6 || !p[0].equals("1") || !Set.of("true", "false").contains(p[2]))
      throw new IllegalArgumentException("Abandon fields");
    return new Source(
        UUID.fromString(p[1]),
        Boolean.parseBoolean(p[2]),
        UUID.fromString(p[3]),
        UUID.fromString(p[4]),
        UUID.fromString(p[5]));
  }

  private static String encode(Source s) {
    return "1|"
        + s.world()
        + "|"
        + s.cash()
        + "|"
        + s.quote()
        + "|"
        + s.original()
        + "|"
        + s.input();
  }

  public static RecordCatalog register(RecordCatalog c) {
    return c.with(
        new RecordCatalog.Key(ABANDON, 1),
        p -> {
          if (!encode(decode(p)).equals(p)) throw new IllegalArgumentException("Abandon canonical");
        });
  }

  private record Original(Journal.Intent intent, UUID input, String items) {}

  private static Original original(
      FoundationStore store, PlayerId actor, UUID quote, boolean cash) {
    var world = store.snapshot().worldId();
    return store.transaction(
        tx -> {
          if (cash) {
            var q = ReferenceCashTrades.read(tx::query, quote);
            if (q == null || !q.terms().actor().equals(actor))
              throw new IllegalArgumentException("Cash quote owner");
            return new Original(
                ReferenceCashTrades.intent(world, q.terms().operation(), actor),
                q.terms().inputCustody(),
                q.terms().exactInputItems());
          }
          var q = ReferenceNpcTrades.read(tx::query, quote);
          if (q == null || !q.terms().actor().equals(actor))
            throw new IllegalArgumentException("Account quote owner");
          return new Original(
              ReferenceNpcTrades.intent(world, q.terms().operation(), actor),
              q.terms().inputCustody(),
              q.terms().exactInputItems());
        });
  }

  private static void close(
      FoundationStore store,
      Original source,
      java.util.function.Predicate<JournalEngine.ReadView> proof) {
    var kind = source.intent().kind();
    var result =
        new JournalEngine(
                store,
                (a, k) -> a.equals(source.intent().actor()) && k.equals(kind),
                Map.of(
                    kind,
                    new JournalEngine.Registration(
                        1,
                        (i, r) ->
                            proof.test(r)
                                ? new JournalEngine.Reject(
                                    new Journal.Result(
                                        1, "NPC_CANCELLED", "Original permanently closed"))
                                : new JournalEngine.Decline(
                                    new Journal.Result(1, "NPC_RETURN_PROOF_REQUIRED", "")))))
            .submit(source.intent());
    if (result.status() != Journal.Status.DURABLY_REJECTED)
      throw new IllegalArgumentException("Committed or unresolved original cannot be returned");
  }

  /** The caller proves physical reservation never began and revokes the ability to start it. */
  public static void abandon(
      FoundationStore store, PlayerId actor, UUID quote, boolean cash, BooleanSupplier untouched) {
    var source = original(store, actor, quote, cash);
    close(
        store,
        source,
        r -> {
          if (!untouched.getAsBoolean()) return false;
          if (source.input() == null) return true;
          var input = Custody.find(r, actor, source.input(), false).orElse(null);
          return input != null
              && input.state() == Custody.State.RESERVED
              && input.reservationWitness() == null
              && input.financialOperation() == null
              && input.terms().items().equals(source.items());
        });
    if (source.input() == null) return;
    var fact =
        new Source(
            store.snapshot().worldId().value(),
            cash,
            quote,
            source.intent().operationId().value(),
            source.input());
    String payload = encode(fact);
    UUID op = stable("abandon|" + fact.world() + "|" + quote);
    var request = new Journal.CanonicalRequest(1, Map.of("source", payload));
    var registration =
        new JournalEngine.Registration(
            1,
            (i, r) -> {
              var input = Custody.find(r, actor, source.input(), false).orElseThrow();
              if (!untouched.getAsBoolean()
                  || input.state() != Custody.State.RESERVED
                  || input.reservationWitness() != null
                  || input.financialOperation() != null
                  || !input.terms().items().equals(source.items()))
                return new JournalEngine.Decline(
                    new Journal.Result(1, "NPC_UNTOUCHED_PROOF_REQUIRED", ""));
              var child =
                  new Journal.Intent(
                      i.operationId(),
                      i.requestId(),
                      actor,
                      Custody.Action.QUARANTINE.kind(),
                      Custody.request(
                          input.id(),
                          null,
                          Custody.Evidence.unknown(),
                          "Cancelled untouched input; no physical return required"),
                      null,
                      null);
              var decision =
                  Custody.registration(
                          Custody.Action.QUARANTINE,
                          (a, in, c, rs) -> c.equals(input),
                          (c, e, rs) -> false,
                          null)
                      .handler()
                      .prepare(child, r);
              if (!(decision instanceof JournalEngine.Commit quarantined)) return decision;
              var records = new ArrayList<>(quarantined.records());
              records.add(new Journal.Record(ABANDON, 1, payload));
              return new JournalEngine.Commit(
                  records,
                  new Journal.Result(1, "NPC_UNTOUCHED_ABANDONED", quote.toString()),
                  tx -> {
                    quarantined.projections().apply(tx);
                    if (cash)
                      tx.execute(
                          "UPDATE custody_cash_reservations SET state='RELEASED' WHERE custody_id=? AND state='ACTIVE'",
                          List.of(input.id().toString()));
                  });
            });
    var result =
        new JournalEngine(
                store, (a, k) -> a.equals(actor) && k.equals(KIND), Map.of(KIND, registration))
            .submit(
                new Journal.Intent(
                    new OperationId(op), new RequestId(op), actor, KIND, request, null, null));
    if (result.status() != Journal.Status.COMMITTED)
      throw new IllegalArgumentException(result.result().code());
  }

  /**
   * Account goods/secondary inputs only; cash returns retain their original cash-specific service.
   */
  public static Custody.Case accountReturn(
      FoundationStore store,
      PlayerId actor,
      UUID quote,
      Custody.Evidence evidence,
      Custody.EvidenceVerifier verifier,
      BooleanSupplier live) {
    var source = original(store, actor, quote, false);
    if (source.input() == null) throw new IllegalArgumentException("No account input custody");
    java.util.function.Predicate<JournalEngine.ReadView> proven =
        r -> {
          var input = Custody.find(r, actor, source.input(), false).orElseThrow();
          return live.getAsBoolean()
              && input.financialOperation() == null
              && evidence.kind() == Custody.EvidenceKind.UNDELIVERED
              && input.terms().source().equals(evidence.location())
              && verifier.verifies(input, evidence, r);
        };
    close(store, source, proven);
    UUID op = stable("account-return|" + source.intent().operationId().value()),
        returned = stable("account-return-custody|" + source.intent().operationId().value());
    var kind = new Journal.Kind("economy:a9", "economy:a9/reference-npc/account-return");
    var request =
        new Journal.CanonicalRequest(
            1, Map.of("quote", quote.toString(), "evidence", evidence.saveToken()));
    var registration =
        new JournalEngine.Registration(
            1,
            (i, r) -> {
              var input = Custody.find(r, actor, source.input(), false).orElseThrow();
              if (!live.getAsBoolean()
                  || input.financialOperation() != null
                  || evidence.kind() != Custody.EvidenceKind.UNDELIVERED
                  || !verifier.verifies(input, evidence, r))
                return new JournalEngine.Decline(
                    new Journal.Result(1, "NPC_RETURN_PROOF_REQUIRED", ""));
              var child =
                  new Journal.Intent(
                      i.operationId(),
                      i.requestId(),
                      actor,
                      Custody.Action.QUARANTINE.kind(),
                      Custody.request(
                          input.id(), null, evidence, "Rejected account original held inputs"),
                      null,
                      null);
              var d =
                  Custody.registration(
                          Custody.Action.QUARANTINE,
                          (a, in, c, rs) -> c.equals(input),
                          (c, e, rs) -> false,
                          null)
                      .handler()
                      .prepare(child, r);
              if (!(d instanceof JournalEngine.Commit quarantine)) return d;
              var claim =
                  Custody.returnCase(
                      i,
                      returned,
                      input.id(),
                      actor,
                      input.terms().items(),
                      input.terms().source(),
                      evidence,
                      "Original rejected account input return",
                      Custody.State.CLAIM,
                      r);
              if (claim == null) throw new IllegalArgumentException("Return already exists");
              var records = new ArrayList<>(quarantine.records());
              records.addAll(claim.records());
              return new JournalEngine.Commit(
                  records,
                  new Journal.Result(1, "NPC_ACCOUNT_INPUT_RETURN", returned.toString()),
                  tx -> {
                    quarantine.projections().apply(tx);
                    claim.projections().apply(tx);
                  });
            });
    var result =
        new JournalEngine(
                store, (a, k) -> a.equals(actor) && k.equals(kind), Map.of(kind, registration))
            .submit(
                new Journal.Intent(
                    new OperationId(op), new RequestId(op), actor, kind, request, null, null));
    if (result.status() != Journal.Status.COMMITTED)
      throw new IllegalArgumentException(result.result().code());
    return store.transaction(tx -> Custody.find(tx::query, actor, returned, false).orElseThrow());
  }

  public static void validate(Connection c) throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT r.payload,t.operation_id FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE r.record_type=?")) {
      q.setString(1, ABANDON);
      try (var rows = q.executeQuery()) {
        while (rows.next()) {
          var s = decode(rows.getString(1));
          if (!rows.getString(2).equals(stable("abandon|" + s.world() + "|" + s.quote()).toString())
              || !s.world()
                  .toString()
                  .equals(scalar(c, "SELECT world_id FROM foundation_metadata WHERE singleton=1"))
              || !"DURABLY_REJECTED"
                  .equals(
                      scalar(
                          c,
                          "SELECT status FROM operation_outcomes WHERE world_id=? AND operation_id=?",
                          s.world().toString(),
                          s.original().toString()))
              || !"QUARANTINED"
                  .equals(
                      scalar(c, "SELECT state FROM custody_cases WHERE id=?", s.input().toString()))
              || scalar(
                      c,
                      "SELECT reservation_witness FROM custody_cases WHERE id=?",
                      s.input().toString())
                  != null
              || scalar(
                      c,
                      "SELECT financial_operation FROM custody_cases WHERE id=?",
                      s.input().toString())
                  != null) throw new SQLException("Untouched abandon source");
          String payload =
              scalar(
                  c,
                  "SELECT payload FROM "
                      + (s.cash() ? "reference_cash_quotes" : "reference_npc_trade_quotes")
                      + " WHERE id=?",
                  s.quote().toString());
          if (payload == null) throw new SQLException("Abandon quote missing");
          UUID original =
              s.cash()
                  ? ca.emilybelnavis.mcmods.economy.trade.cash.ReferenceCashFacts.decodeQuote(
                          payload)
                      .terms()
                      .operation()
                  : ReferenceNpcFacts.decodeQuote(payload).terms().operation();
          UUID input =
              s.cash()
                  ? ca.emilybelnavis.mcmods.economy.trade.cash.ReferenceCashFacts.decodeQuote(
                          payload)
                      .terms()
                      .inputCustody()
                  : ReferenceNpcFacts.decodeQuote(payload).terms().inputCustody();
          if (!s.original().equals(original)
              || !s.input().equals(input)
              || s.cash()
                  && !"0"
                      .equals(
                          scalar(
                              c,
                              "SELECT COUNT(*) FROM custody_cash_reservations WHERE custody_id=? AND state='ACTIVE'",
                              input.toString())))
            throw new SQLException("Abandon exact original/locks");
        }
      }
    } catch (RuntimeException bad) {
      throw new SQLException("Invalid untouched abandon", bad);
    }
  }

  private static UUID stable(String text) {
    return UUID.nameUUIDFromBytes(
        ("economy:reference-input-return:v1|" + text)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static String scalar(Connection c, String sql, String... args) throws SQLException {
    try (var q = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) q.setString(i + 1, args[i]);
      try (var r = q.executeQuery()) {
        return r.next() ? r.getString(1) : null;
      }
    }
  }
}
