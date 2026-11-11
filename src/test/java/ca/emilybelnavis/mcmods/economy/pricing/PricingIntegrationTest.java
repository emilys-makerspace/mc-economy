package ca.emilybelnavis.mcmods.economy.pricing;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.interest.DepositYield;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration at the actual settlement/production scheduling boundaries, without game or Gradle
 * processes.
 */
class PricingIntegrationTest {
  @TempDir Path path;
  private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
  private static final PlayerId OWNER = new PlayerId(new UUID(31, 1)),
      OTHER = new PlayerId(new UUID(31, 2));
  private static final AccountId SOURCE = new AccountId(new UUID(32, 1)),
      TARGET = new AccountId(new UUID(32, 2)),
      SPECIALIST = new AccountId(new UUID(32, 3));
  private static final InstitutionCatalog.InstitutionId
      BANK = new InstitutionCatalog.InstitutionId("economy:bank/crownstone"),
      SPECIALIST_BANK = new InstitutionCatalog.InstitutionId("economy:bank/atlas_reserve");

  private static final class Wall extends Clock {
    Instant now = NOW;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

  private static Journal.Intent intent(Journal.Kind kind, Journal.CanonicalRequest request) {
    return new Journal.Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        OWNER,
        kind,
        request,
        null,
        null);
  }

  private static void seedFx(FoundationStore store, Clock wall) {
    var engine =
        new JournalEngine(store, (a, k) -> true, Map.of(FxService.OPEN, FxService.opening(wall)));
    assertEquals(
        Journal.Status.COMMITTED,
        engine
            .submit(
                intent(FxService.OPEN, new Journal.CanonicalRequest(1, Map.of("currency", "CAD"))))
            .status());
  }

  private final class Fixture implements AutoCloseable {
    final SqliteStorage store = new SqliteStorage();
    final Wall wall = new Wall();
    final WorldId world;
    final ServicePricing pricing = new ServicePricing(new WealthValuation());
    final FxQuotes book;

    Fixture(long funds) throws Exception {
      store.initialize(path.toRealPath());
      world = store.snapshot().worldId();
      book = new FxQuotes(world);
      seedFx(store, wall);
      store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                List.of(SOURCE.value().toString(), OWNER.value().toString()));
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','USD','OPEN',1,0)",
                List.of(TARGET.value().toString(), OWNER.value().toString()));
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','USD','OPEN',1,0)",
                List.of(SPECIALIST.value().toString(), OTHER.value().toString()));
            tx.execute(
                "INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",
                List.of(SOURCE.value().toString(), BANK.key()));
            tx.execute(
                "INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",
                List.of(TARGET.value().toString(), "economy:bank/crosswind"));
            tx.execute(
                "INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",
                List.of(SPECIALIST.value().toString(), SPECIALIST_BANK.key()));
            tx.execute(
                "INSERT INTO institution_parties VALUES(?,?,?,?,?,'HYBRID',1)",
                List.of(
                    world.value().toString(),
                    BANK.key(),
                    new InstitutionCatalog.SystemActor(world, BANK).actorId().value().toString(),
                    "Crownstone Bank",
                    "CAD"));
            LedgerProjection.write(tx, SOURCE.value(), CAD, funds);
            return null;
          });
    }

    FxQuotes.Quote quote(boolean priced) {
      return store.transaction(
          tx -> {
            var quote =
                book.issue(
                    OWNER,
                    new FxQuotes.Pair(CAD, USD),
                    FxQuotes.Side.EXACT_DEBIT,
                    new Money(CAD, 1000),
                    FxService.latest(tx::query),
                    wall.instant(),
                    Duration.ofSeconds(60));
            return priced
                ? book.price(
                    quote,
                    pricing.quote(
                        tx::query,
                        world,
                        OWNER,
                        BANK,
                        ServicePricing.Service.FX,
                        quote.debit(),
                        wall.instant(),
                        quote.expires()))
                : quote;
          });
    }

    FxSettlement.QuoteReader reader() {
      return (id, actor, world, reads) -> {
        var quote = book.requireFresh(id, actor, world, FxService.latest(reads), 1, wall.instant());
        if (quote.pricing() != null)
          pricing.requireFresh(quote.pricing(), reads, world, actor, wall.instant());
        return quote;
      };
    }

    JournalEngine engine() {
      return new JournalEngine(
          store,
          (a, k) -> true,
          Map.of(
              FxSettlement.EXCHANGE,
              FxSettlement.registration(world, reader(), (i, e, r) -> true)));
    }

    Journal.Intent exchange(FxQuotes.Quote quote) {
      return intent(
          FxSettlement.EXCHANGE,
          new FxSettlement.Exchange(
                  quote.id(),
                  OWNER,
                  new SameCurrency.Revision(SOURCE, 1),
                  new SameCurrency.Revision(TARGET, 1))
              .request());
    }

    long balance(UUID id, EconomyCurrency currency) {
      return store.transaction(tx -> Settlement.posted(tx::query, id, currency));
    }

    void funds(long amount) {
      store.transaction(
          tx -> {
            LedgerProjection.write(tx, SOURCE.value(), CAD, amount);
            return null;
          });
    }

    public void close() throws Exception {
      store.close();
    }
  }

  @Test
  void actualPricedFxSettlementFundsSupplyingBankAndRecognizesRevenueAndOwnerExpenseExactlyOnce()
      throws Exception {
    try (var f = new Fixture(10000)) {
      var quote = f.quote(true);
      assertEquals(5, quote.fee().minorUnits());
      assertEquals(1000, quote.debit().minorUnits());
      assertEquals(716, quote.credit().minorUnits());
      var operation = f.exchange(quote);
      var engine = f.engine();
      var committed = engine.submit(operation);
      assertEquals(Journal.Status.COMMITTED, committed.status(), committed.result().code());
      assertEquals(committed, engine.submit(operation));
      assertEquals(8995, f.balance(SOURCE.value(), CAD));
      assertEquals(716, f.balance(TARGET.value(), USD));
      assertEquals(
          5, f.balance(FinancialAccounts.id(f.world, BANK, FinancialAccounts.Role.CASH, CAD), CAD));
      assertEquals(
          -5,
          f.balance(FinancialAccounts.id(f.world, BANK, FinancialAccounts.Role.INCOME, CAD), CAD));
      var depositor = new InstitutionCatalog.InstitutionId("economy:depositor/" + OWNER.value());
      assertEquals(
          5,
          f.balance(
              FinancialAccounts.id(f.world, depositor, FinancialAccounts.Role.EXPENSE, CAD), CAD));
      assertEquals(0, f.balance(Settlement.feeAccount(CAD), CAD));
      var records =
          f.store.transaction(
              tx ->
                  tx.query(
                      "SELECT record_version,payload FROM journal_records WHERE record_type=?",
                      List.of(FxSettlement.FACT),
                      4));
      assertEquals(1, records.size());
      assertEquals("3", records.getFirst().get("record_version"));
      FxSettlement.validateFactV3(records.getFirst().get("payload"));
    }
  }

  @Test
  void changedTierProviderOrFxPublicationRejectsCapturedPricedQuoteBeforeMoneyMoves()
      throws Exception {
    try (var f = new Fixture(10_000_000_000L)) {
      var qualified = f.quote(true);
      assertEquals(0, qualified.fee().minorUnits());
      f.funds(9_999_999_999L);
      assertEquals("FX_QUOTE_STALE", f.engine().submit(f.exchange(qualified)).result().code());
      var provider = f.quote(true);
      f.store.transaction(
          tx -> {
            tx.execute(
                "UPDATE bank_account_providers SET revision=2 WHERE account_id=?",
                List.of(SOURCE.value().toString()));
            return null;
          });
      assertEquals("FX_QUOTE_STALE", f.engine().submit(f.exchange(provider)).result().code());
      var fx = f.quote(true);
      var old = f.store.transaction(tx -> FxService.latest(tx::query));
      var replacement =
          new FxFacts.Publication(
              old.day().plusDays(1),
              old.reference(),
              UUID.randomUUID(),
              old.policyId(),
              old.rates());
      var kind = new Journal.Kind("fixture:pricing", "fixture:publication");
      var publish =
          new JournalEngine(
              f.store,
              (a, k) -> true,
              Map.of(
                  kind,
                  new JournalEngine.Registration(
                      1,
                      (i, r) ->
                          new JournalEngine.Commit(
                              List.of(
                                  new Journal.Record(
                                      FxFacts.PUBLICATION,
                                      replacement.version(),
                                      replacement.encode())),
                              new Journal.Result(1, "PUBLISHED", ""),
                              tx -> {}))));
      assertEquals(
          Journal.Status.COMMITTED,
          publish.submit(intent(kind, new Journal.CanonicalRequest(1, Map.of()))).status());
      assertEquals("FX_QUOTE_STALE", f.engine().submit(f.exchange(fx)).result().code());
      assertEquals(9_999_999_999L, f.balance(SOURCE.value(), CAD));
      assertEquals(0, f.balance(TARGET.value(), USD));
    }
  }

  @Test
  void postedRoundTripCannotRestoreTheWitnessOfAnOlderFeeQuote() throws Exception {
    try (var f = new Fixture(10000)) {
      var quote = f.quote(true);
      var ledger =
          new JournalEngine(
              f.store,
              (a, k) -> true,
              SameCurrency.registrations(f.world, (i, instruction, reads) -> true));
      var adjustment =
          new SameCurrency.Adjustment(
              OWNER, new SameCurrency.Revision(SOURCE, 1), new Money(CAD, 100));
      assertEquals(
          Journal.Status.COMMITTED,
          ledger.submit(intent(SameCurrency.DEBIT, adjustment.request())).status());
      assertEquals(
          Journal.Status.COMMITTED,
          ledger.submit(intent(SameCurrency.CREDIT, adjustment.request())).status());
      assertEquals(10000, f.balance(SOURCE.value(), CAD));
      assertEquals("FX_QUOTE_STALE", f.engine().submit(f.exchange(quote)).result().code());
      assertEquals(0, f.balance(TARGET.value(), USD));
    }
  }

  @Test
  void assignedSourceCannotUseUnpricedQuoteWhileExplicitLegacySourceRetainsItsOldZeroFeePath()
      throws Exception {
    try (var f = new Fixture(10000)) {
      var unpriced = f.quote(false);
      assertEquals("FX_PRICING_REQUIRED", f.engine().submit(f.exchange(unpriced)).result().code());
      f.store.transaction(
          tx -> {
            tx.execute(
                "UPDATE bank_account_providers SET bank_key=?,legacy=1,revision=2 WHERE account_id=?",
                List.of(BankProviders.UNASSIGNED_PROVIDER, SOURCE.value().toString()));
            return null;
          });
      var legacy = f.quote(false);
      assertEquals(0, legacy.fee().minorUnits());
      var result = f.engine().submit(f.exchange(legacy));
      assertEquals(Journal.Status.COMMITTED, result.status(), result.result().code());
      assertEquals(9000, f.balance(SOURCE.value(), CAD));
      assertEquals(700, f.balance(TARGET.value(), USD));
      assertEquals(
          0, f.balance(FinancialAccounts.id(f.world, BANK, FinancialAccounts.Role.CASH, CAD), CAD));
    }
  }

  @Test
  void specialistIncomingTransferGuardDoesNotBlockProtectedWithdrawalRepaymentOrRefundKinds()
      throws Exception {
    try (var f = new Fixture(10000)) {
      var decision =
          PostingBatch.prepareMinor(
              List.of(
                  new PostingBatch.Leg(
                      TARGET.value(), PostingBatch.Role.ACCOUNT, USD, new Money(USD, -100)),
                  new PostingBatch.Leg(
                      SPECIALIST.value(), PostingBatch.Role.ACCOUNT, USD, new Money(USD, 100))),
              PostingBatch.Authority.TRANSFER);
      var candidate = assertInstanceOf(JournalEngine.Commit.class, decision);
      var valuation = new WealthValuation();
      var denied =
          f.store.transaction(
              tx ->
                  PricingAdmission.check(
                      intent(SameCurrency.PAY, new Journal.CanonicalRequest(1, Map.of())),
                      tx::query,
                      candidate,
                      f.world,
                      NOW,
                      valuation));
      assertEquals(
          "SERVICE_ACCOUNT_UNAVAILABLE",
          assertInstanceOf(JournalEngine.Reject.class, denied).result().code());
      for (var kind :
          List.of(
              SameCurrency.DEBIT,
              MerchantPayments.Action.REFUND.kind(),
              new Journal.Kind("economy:a6", "economy:a6/repayment/pay"),
              new Journal.Kind("economy:accounts", "economy:read"))) {
        assertSame(
            candidate,
            f.store.transaction(
                tx ->
                    PricingAdmission.check(
                        intent(kind, new Journal.CanonicalRequest(1, Map.of())),
                        tx::query,
                        candidate,
                        f.world,
                        NOW,
                        valuation)));
      }
      f.store.transaction(
          tx -> {
            LedgerProjection.write(tx, SPECIALIST.value(), USD, 10_000_000_000L);
            return null;
          });
      assertSame(
          candidate,
          f.store.transaction(
              tx ->
                  PricingAdmission.check(
                      intent(SameCurrency.PAY, new Journal.CanonicalRequest(1, Map.of())),
                      tx::query,
                      candidate,
                      f.world,
                      NOW,
                      valuation)));
    }
  }

  @Test
  void
      productionRegistersProvidersActivatesOnceAndRunsDailyYieldBeforeTierReevaluationAcrossRestart()
          throws Exception {
    var wall = new Wall();
    var raw = new SqliteStorage();
    raw.initialize(path.toRealPath());
    try {
      seedFx(raw, wall);
      raw.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                List.of(SOURCE.value().toString(), OWNER.value().toString()));
            tx.execute(
                "INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",
                List.of(SOURCE.value().toString(), BANK.key()));
            LedgerProjection.write(tx, SOURCE.value(), CAD, 10000);
            return null;
          });
    } finally {
      raw.close();
    }
    var configuration = FoundationRuntime.Configuration.production(wall);
    var expectedProviders =
        configuration.deadlineProviders().stream().map(provider -> provider.id()).sorted().toList();
    var runtime = new FoundationRuntime(configuration);
    runtime.initialize(path.toRealPath());
    try {
      ready(runtime);
      assertEquals(1, count(runtime, "pricing_activation"));
      assertEquals(1, count(runtime, "deposit_yield_activation"));
      var registeredProviders =
          runtime
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT provider FROM financial_deadline_providers ORDER BY provider",
                          List.of(),
                          64))
              .stream()
              .map(row -> row.get("provider"))
              .toList();
      assertEquals(expectedProviders, registeredProviders);
      wall.now = NOW.plusMillis(1_200_000);
      boolean current = false;
      for (int step = 0; step < 1000; step++) {
        var work = runtime.prepareEconomicStep();
        var progress =
            work.calculation() == null
                ? work.progress()
                : work.commit().apply(work.calculation().get());
        if (progress.ready()) {
          current = true;
          break;
        }
      }
      assertTrue(current);
      runtime.requireEconomicCurrent();
      var status =
          runtime.transaction(tx -> TierReevaluation.status(tx::query, OWNER).orElseThrow());
      assertTrue(status.available());
      assertFalse(status.eligible());
      assertEquals(wall.millis(), status.evaluatedMillis());
      assertEquals(1, count(runtime, "deposit_yield_claims"));
      var order =
          runtime.transaction(
              tx ->
                  tx.query(
                      "SELECT record_type FROM journal_records WHERE record_type IN(?,?) ORDER BY sequence,position",
                      List.of(DepositYield.FACT, TierReevaluation.FACT),
                      16));
      var types = order.stream().map(r -> r.get("record_type")).toList();
      assertTrue(types.indexOf(DepositYield.FACT) >= 0);
      assertTrue(types.indexOf(DepositYield.FACT) < types.indexOf(TierReevaluation.FACT));
    } finally {
      runtime.close();
    }
    runtime = new FoundationRuntime(configuration);
    runtime.initialize(path.toRealPath());
    try {
      ready(runtime);
      assertEquals(1, count(runtime, "pricing_activation"));
      assertEquals(1, count(runtime, "deposit_yield_activation"));
      assertEquals(1, count(runtime, "pricing_tier_history"));
    } finally {
      runtime.close();
    }
  }

  private static void ready(FoundationRuntime runtime) {
    for (int step = 0; step < 1000; step++) if (runtime.startupStep().ready()) return;
    fail("Production runtime did not reach readiness");
  }

  private static long count(FoundationStore store, String table) {
    return store.transaction(
        tx ->
            Long.parseLong(
                tx.query("SELECT count(*) AS n FROM " + table, List.of(), 1).getFirst().get("n")));
  }
}
