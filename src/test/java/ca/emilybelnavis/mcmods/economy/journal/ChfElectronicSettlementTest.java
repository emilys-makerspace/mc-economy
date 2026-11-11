package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.account.AccountLifecycle;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChfElectronicSettlementTest {
  @TempDir Path temporary;
  private static final PlayerId ACTOR = new PlayerId(UUID.randomUUID());
  private static final PlayerId OTHER = new PlayerId(UUID.randomUUID());
  private static final Kind RESERVE = new Kind("fixture:chf", "fixture:reserve");
  private static final Kind RELEASE = new Kind("fixture:chf", "fixture:release");
  private static final Kind CAPTURE = new Kind("fixture:chf", "fixture:capture");

  private static Intent intent(Kind kind, CanonicalRequest request) {
    return new Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        ACTOR,
        kind,
        request,
        null,
        null);
  }

  private static Intent retry(Intent original) {
    return new Intent(
        original.operationId(),
        new RequestId(UUID.randomUUID()),
        original.actor(),
        original.kind(),
        original.request(),
        null,
        null);
  }

  private static void account(
      SqliteStorage store,
      AccountId id,
      PlayerId owner,
      EconomyCurrency currency,
      boolean primary) {
    store.transaction(
        tx -> {
          tx.execute(
              "INSERT INTO account_catalog VALUES(?,?,'BANK',?,'OPEN',1,?)",
              List.of(
                  id.value().toString(),
                  owner.value().toString(),
                  currency.code(),
                  primary ? "1" : "0"));
          return null;
        });
  }

  private static JournalEngine ordinary(SqliteStorage store) {
    var registrations =
        new HashMap<>(
            SameCurrency.registrations(
                store.snapshot().worldId(), (intent, instruction, reads) -> true));
    Holds.Authority authority = (intent, source, target, reads) -> true;
    registrations.put(RESERVE, Holds.reserve(authority));
    registrations.put(RELEASE, Holds.release(authority));
    registrations.put(CAPTURE, Holds.capture(authority));
    return new JournalEngine(store, (actor, kind) -> actor.equals(ACTOR), registrations);
  }

  private static Outcome credit(
      JournalEngine engine, AccountId account, EconomyCurrency currency, long units) {
    return engine.submit(
        intent(
            SameCurrency.CREDIT,
            new SameCurrency.Adjustment(
                    ACTOR, new SameCurrency.Revision(account, 1), new Money(currency, units))
                .request()));
  }

  private static long balance(SqliteStorage store, AccountId id, EconomyCurrency currency) {
    return store.transaction(tx -> Settlement.posted(tx::query, id.value(), currency));
  }

  private static long available(SqliteStorage store, AccountId id, EconomyCurrency currency) {
    return store.transaction(tx -> Holds.available(tx::query, id.value(), currency));
  }

  @Test
  void chfCentPaymentHoldCaptureReleaseAndReplayPreserveBalancesAndHistoricalVersions()
      throws Exception {
    var store = new SqliteStorage();
    store.initialize(temporary.toRealPath());
    var source = new AccountId(UUID.randomUUID());
    var target = new AccountId(UUID.randomUUID());
    var cad = new AccountId(UUID.randomUUID());
    Intent capture;
    Outcome captured;
    try {
      account(store, source, ACTOR, CHF, true);
      account(store, target, OTHER, CHF, true);
      account(store, cad, ACTOR, CAD, false);
      var engine = ordinary(store);
      assertEquals(Status.COMMITTED, credit(engine, source, CHF, 5).status());
      var payment =
          new SameCurrency.Payment(
              ACTOR,
              new SameCurrency.Revision(source, 1),
              OTHER,
              new SameCurrency.Revision(target, 1),
              new Money(CHF, 1));
      var payIntent = intent(SameCurrency.PAY, payment.request());
      var paid = engine.submit(payIntent);
      assertEquals(Status.COMMITTED, paid.status());
      assertEquals(paid, engine.submit(retry(payIntent)));
      assertEquals(4, balance(store, source, CHF));
      assertEquals(1, balance(store, target, CHF));
      var reserve = intent(RESERVE, Holds.reserveRequest(source.value(), new Money(CHF, 1)));
      assertEquals(Status.COMMITTED, engine.submit(reserve).status());
      assertEquals(3, available(store, source, CHF));
      capture =
          intent(CAPTURE, Holds.captureRequest(reserve.operationId().value(), target.value()));
      captured = engine.submit(capture);
      assertEquals(Status.COMMITTED, captured.status());
      assertEquals(3, balance(store, source, CHF));
      assertEquals(2, balance(store, target, CHF));
      var releaseReservation =
          intent(RESERVE, Holds.reserveRequest(source.value(), new Money(CHF, 2)));
      assertEquals(Status.COMMITTED, engine.submit(releaseReservation).status());
      assertEquals(1, available(store, source, CHF));
      assertEquals(
          Status.COMMITTED,
          engine
              .submit(
                  intent(RELEASE, Holds.releaseRequest(releaseReservation.operationId().value())))
              .status());
      assertEquals(3, available(store, source, CHF));
      assertEquals(
          "INVALID_INSTRUCTION",
          credit(engine, cad, CAD, 1).result().code(),
          "Legacy currencies retain their existing whole-major admission");
      assertEquals(
          "HOLD_REQUEST",
          engine
              .submit(intent(RESERVE, Holds.reserveRequest(cad.value(), new Money(CAD, 1))))
              .result()
              .code());
      var facts =
          store.transaction(
              tx ->
                  tx.query(
                      "SELECT record_version,payload FROM journal_records WHERE record_type=? ORDER BY sequence,position",
                      List.of(Holds.FACT),
                      16));
      assertEquals(4, facts.size());
      for (var fact : facts) {
        assertEquals("2", fact.get("record_version"));
        Holds.validateFactV2(fact.get("payload"));
        assertThrows(IllegalArgumentException.class, () -> Holds.validateFact(fact.get("payload")));
      }
    } finally {
      store.close();
    }
    var reopened = new SqliteStorage();
    reopened.initialize(temporary.toRealPath());
    try {
      assertEquals(captured, ordinary(reopened).submit(retry(capture)));
      assertEquals(3, balance(reopened, source, CHF));
      assertEquals(2, balance(reopened, target, CHF));
      var owner = new PartyRef(reopened.snapshot().worldId(), new PartyRef.Player(ACTOR));
      var history =
          reopened
              .transaction(tx -> LedgerHistory.page(tx::query, owner, source, null, 10))
              .orElseThrow();
      assertEquals(
          List.of(5L, -1L, -1L),
          history.entries().stream().map(entry -> entry.amount().minorUnits()).toList());
    } finally {
      reopened.close();
    }
  }

  @Test
  void chfPairKeepsBothElectronicMinorSidesConsumesQuotesAndPreservesHistory() throws Exception {
    var store = new SqliteStorage();
    store.initialize(temporary.toRealPath());
    try {
      var source = new AccountId(UUID.randomUUID());
      var target = new AccountId(UUID.randomUUID());
      account(store, source, ACTOR, CAD, true);
      account(store, target, ACTOR, CHF, false);
      assertEquals(Status.COMMITTED, credit(ordinary(store), source, CAD, 100).status());
      var now = Instant.parse("2026-11-05T12:00:00Z");
      var opening =
          new JournalEngine(
              store,
              (actor, kind) -> actor.equals(ACTOR),
              Map.of(FxService.OPEN, FxService.opening(Clock.fixed(now, ZoneOffset.UTC))));
      assertEquals(
          Status.COMMITTED,
          opening
              .submit(intent(FxService.OPEN, new CanonicalRequest(1, Map.of("currency", "CAD"))))
              .status());
      var publication = store.transaction(tx -> FxService.latest(tx::query));
      var book = new FxQuotes(store.snapshot().worldId());
      var registration =
          FxSettlement.registration(
              store.snapshot().worldId(),
              (id, actor, world, reads) ->
                  book.requireFresh(id, actor, world, FxService.latest(reads), 1, now),
              (intent, exchange, reads) -> true);
      var engine =
          new JournalEngine(
              store,
              (actor, kind) -> actor.equals(ACTOR),
              Map.of(FxSettlement.EXCHANGE, registration));
      var first =
          book.issue(
              ACTOR,
              new FxQuotes.Pair(CAD, CHF),
              FxQuotes.Side.EXACT_DEBIT,
              new Money(CAD, 8),
              publication,
              now,
              Duration.ofSeconds(60));
      assertEquals(new Money(CHF, 5), first.credit());
      var exchange =
          intent(
              FxSettlement.EXCHANGE,
              new FxSettlement.Exchange(
                      first.id(),
                      ACTOR,
                      new SameCurrency.Revision(source, 1),
                      new SameCurrency.Revision(target, 1))
                  .request());
      var settled = engine.submit(exchange);
      assertEquals(Status.COMMITTED, settled.status());
      assertEquals(settled, engine.submit(retry(exchange)));
      assertEquals(
          "FX_QUOTE_CONSUMED",
          engine.submit(intent(FxSettlement.EXCHANGE, exchange.request())).result().code());
      assertEquals(92, balance(store, source, CAD));
      assertEquals(5, balance(store, target, CHF));
      var reverse =
          book.issue(
              ACTOR,
              new FxQuotes.Pair(CHF, CAD),
              FxQuotes.Side.EXACT_DEBIT,
              new Money(CHF, 5),
              publication,
              now,
              Duration.ofSeconds(60));
      assertEquals(new Money(CAD, 8), reverse.credit());
      assertEquals(
          Status.COMMITTED,
          engine
              .submit(
                  intent(
                      FxSettlement.EXCHANGE,
                      new FxSettlement.Exchange(
                              reverse.id(),
                              ACTOR,
                              new SameCurrency.Revision(target, 1),
                              new SameCurrency.Revision(source, 1))
                          .request()))
              .status());
      assertEquals(100, balance(store, source, CAD));
      assertEquals(0, balance(store, target, CHF));
      var owner = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(ACTOR));
      var history =
          store
              .transaction(tx -> LedgerHistory.page(tx::query, owner, source, null, 10))
              .orElseThrow();
      assertEquals(
          List.of(100L, -8L, 8L),
          history.entries().stream().map(entry -> entry.amount().minorUnits()).toList());
      var facts =
          store.transaction(
              tx ->
                  tx.query(
                      "SELECT record_version,payload FROM journal_records WHERE record_type=? ORDER BY sequence",
                      List.of(FxSettlement.FACT),
                      8));
      assertEquals(2, facts.size());
      for (var fact : facts) {
        assertEquals("2", fact.get("record_version"));
        FxSettlement.validateFactV2(fact.get("payload"));
        assertThrows(
            IllegalArgumentException.class, () -> FxSettlement.validateFact(fact.get("payload")));
      }
      var oversized =
          new FxQuotes.Quote(
              UUID.randomUUID(),
              ACTOR,
              store.snapshot().worldId(),
              new FxQuotes.Pair(CAD, CHF),
              FxQuotes.Side.EXACT_DEBIT,
              new Money(CAD, Long.MAX_VALUE),
              publication.updateId(),
              1,
              now.plusSeconds(60),
              new Money(CAD, 1),
              new Money(CAD, Long.MAX_VALUE),
              new Money(CHF, 1));
      var overflowEngine =
          new JournalEngine(
              store,
              (actor, kind) -> actor.equals(ACTOR),
              Map.of(
                  FxSettlement.EXCHANGE,
                  FxSettlement.registration(
                      store.snapshot().worldId(),
                      (id, actor, world, reads) -> oversized,
                      (intent, instruction, reads) -> true)));
      var overflow =
          intent(
              FxSettlement.EXCHANGE,
              new FxSettlement.Exchange(
                      oversized.id(),
                      ACTOR,
                      new SameCurrency.Revision(source, 1),
                      new SameCurrency.Revision(target, 1))
                  .request());
      assertEquals("POSTING_OVERFLOW", overflowEngine.submit(overflow).result().code());
      assertEquals(100, balance(store, source, CAD));
      assertEquals(0, balance(store, target, CHF));
    } finally {
      store.close();
    }
  }

  @Test
  void fractionalLegacyCurrencyBalanceFromChfCashCanDrainToItsPrimaryAtMinorPrecision()
      throws Exception {
    var store = new SqliteStorage();
    store.initialize(temporary.toRealPath());
    try {
      var source = new AccountId(UUID.randomUUID());
      var primary = new AccountId(UUID.randomUUID());
      account(store, source, ACTOR, CAD, false);
      account(store, primary, ACTOR, USD, true);
      var owner = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(ACTOR));
      var now = Instant.parse("2026-11-05T12:00:00Z");
      var opener =
          new JournalEngine(
              store,
              (actor, kind) -> actor.equals(ACTOR),
              Map.of(FxService.OPEN, FxService.opening(Clock.fixed(now, ZoneOffset.UTC))));
      assertEquals(
          Status.COMMITTED,
          opener
              .submit(intent(FxService.OPEN, new CanonicalRequest(1, Map.of("currency", "USD"))))
              .status());
      // Trusted minor-unit issuance models the CAD0.08 credit from a CHF0.05 cash deposit.
      var seedKind = new Kind("fixture:chf", "fixture:minor-seed");
      var legs =
          List.of(
              new PostingBatch.Leg(
                  source.value(), PostingBatch.Role.ACCOUNT, CAD, new Money(CAD, 8)),
              new PostingBatch.Leg(
                  Settlement.issuanceAccount(CAD),
                  PostingBatch.Role.ISSUANCE,
                  CAD,
                  new Money(CAD, -8)));
      var seed =
          new JournalEngine(
              store,
              (actor, kind) -> actor.equals(ACTOR),
              Map.of(
                  seedKind,
                  Settlement.registrationMinor(
                      (request, entries, reads) -> PostingBatch.Authority.ISSUE)));
      assertEquals(
          Status.COMMITTED, seed.submit(intent(seedKind, Settlement.minorRequest(legs))).status());
      var book = new FxQuotes(store.snapshot().worldId());
      var publication = store.transaction(tx -> FxService.latest(tx::query));
      var proof =
          ca.emilybelnavis.mcmods.economy.institution.BranchFixtures.closure(store, owner, source);
      var pricing =
          new ca.emilybelnavis.mcmods.economy.pricing.ServicePricing(
              ca.emilybelnavis.mcmods.economy.pricing.PricingRuntime.wealth());
      var principal = new Money(CAD, 7); // CAD0.08 budget includes CAD0.01 source fee.
      var raw =
          book.issue(
              ACTOR,
              new FxQuotes.Pair(CAD, USD),
              FxQuotes.Side.EXACT_DEBIT,
              principal,
              publication,
              now,
              Duration.ofSeconds(60));
      var fee =
          store.transaction(
              tx ->
                  pricing.quote(
                      tx::query,
                      owner.world(),
                      ACTOR,
                      proof.context().bank(),
                      ca.emilybelnavis.mcmods.economy.pricing.ServicePricing.Service
                          .PROTECTED_CLOSURE_FX,
                      principal,
                      now,
                      raw.expires()));
      var quote = book.price(raw, fee);
      assertEquals(new Money(USD, 5), quote.credit());
      var instruction =
          new FundedClosure.Instruction(
              new AccountLifecycle.Close(source, 1, primary, 1L), quote.id());
      var registration =
          FundedClosure.registrationAtBranch(
              owner,
              instruction,
              (id, actor, world, reads) ->
                  book.requireFresh(id, actor, world, FxService.latest(reads), 1, now),
              (reads, account) -> Optional.of(new FundedClosure.Claims(false, false)),
              proof,
              (request, reads) -> true);
      var engine =
          new JournalEngine(
              store,
              (actor, kind) -> actor.equals(ACTOR),
              Map.of(
                  ca.emilybelnavis.mcmods.economy.institution.BankProviders.CLOSE, registration));
      var fields =
          new ca.emilybelnavis.mcmods.economy.institution.BankProviders.Request(
                  proof, 0, instruction.request().fields())
              .request()
              .fields();
      var close =
          intent(
              ca.emilybelnavis.mcmods.economy.institution.BankProviders.CLOSE,
              new CanonicalRequest(1, fields));
      var result = engine.submit(close);
      assertEquals(Status.COMMITTED, result.status());
      assertEquals("BANK_CLOSED", result.result().code());
      assertEquals(result, engine.submit(retry(close)));
      assertEquals(0, balance(store, source, CAD));
      assertEquals(5, balance(store, primary, USD));
      assertEquals(
          AccountIdentity.Status.CLOSED,
          store
              .transaction(tx -> AccountCatalog.find(tx::query, owner, source).orElseThrow())
              .status());
      var history =
          store
              .transaction(tx -> LedgerHistory.page(tx::query, owner, primary, null, 10))
              .orElseThrow();
      assertEquals(
          List.of(5L),
          history.entries().stream().map(entry -> entry.amount().minorUnits()).toList());
      var facts =
          store.transaction(
              tx ->
                  tx.query(
                      "SELECT record_version,payload FROM journal_records WHERE record_type=?",
                      List.of(FxSettlement.FACT),
                      1));
      assertEquals("3", facts.getFirst().get("record_version"));
      FxSettlement.validateFactV3(facts.getFirst().get("payload"));
      assertThrows(
          IllegalArgumentException.class,
          () -> FxSettlement.validateFact(facts.getFirst().get("payload")));
    } finally {
      store.close();
    }
  }
}
