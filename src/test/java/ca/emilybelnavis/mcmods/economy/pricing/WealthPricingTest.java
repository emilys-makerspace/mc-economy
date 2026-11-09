package ca.emilybelnavis.mcmods.economy.pricing;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.math.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WealthPricingTest {
  @TempDir Path path;
  private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
  private static final PlayerId OWNER = new PlayerId(new UUID(1, 1));
  private static final UUID ACCOUNT = new UUID(2, 2);
  private static final InstitutionCatalog.InstitutionId HYBRID =
      new InstitutionCatalog.InstitutionId("economy:bank/crownstone");

  private final class Fixture implements AutoCloseable {
    final SqliteStorage store = new SqliteStorage();
    final WorldId world;

    Fixture(long cadMinor) throws Exception {
      store.initialize(path.toRealPath());
      world = store.snapshot().worldId();
      var engine =
          new JournalEngine(
              store,
              (a, k) -> true,
              Map.of(FxService.OPEN, FxService.opening(Clock.fixed(NOW, ZoneOffset.UTC))));
      assertEquals(
          Journal.Status.COMMITTED,
          engine
              .submit(
                  intent(
                      FxService.OPEN, new Journal.CanonicalRequest(1, Map.of("currency", "CAD"))))
              .status());
      store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                List.of(ACCOUNT.toString(), OWNER.value().toString()));
            tx.execute(
                "INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",
                List.of(ACCOUNT.toString(), HYBRID.key()));
            if (cadMinor != 0)
              tx.execute(
                  "INSERT INTO ledger_balances VALUES(?,'CAD',?)",
                  List.of(ACCOUNT.toString(), Long.toString(cadMinor)));
            return null;
          });
    }

    WealthValuation.Snapshot snapshot(WealthValuation valuation, Instant now) {
      return store.transaction(tx -> valuation.snapshot(tx::query, world, OWNER, now));
    }

    void balance(long amount) {
      store.transaction(
          tx -> {
            ca.emilybelnavis.mcmods.economy.storage.LedgerProjection.write(
                tx, ACCOUNT, CAD, amount);
            return null;
          });
    }

    public void close() throws Exception {
      store.close();
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

  private static WealthValuation.Source source(
      String id,
      WealthValuation.Kind kind,
      boolean mark,
      AtomicReference<WealthValuation.SourceSnapshot> state) {
    return new WealthValuation.Source() {
      public String id() {
        return id;
      }

      public WealthValuation.Kind kind() {
        return kind;
      }

      public boolean requiresFreshMark() {
        return mark;
      }

      public WealthValuation.SourceSnapshot read(
          JournalEngine.ReadView reads, WorldId world, PlayerId owner, Instant now) {
        return state.get();
      }
    };
  }

  private static WealthValuation.SourceSnapshot state(
      long rev, Instant marked, String id, String claim, EconomyCurrency currency, String major) {
    return new WealthValuation.SourceSnapshot(
        true,
        rev,
        id,
        marked,
        List.of(new WealthValuation.Component(claim, currency, new BigDecimal(major))));
  }

  @Test
  void exactThresholdHeldMoneyCountsOnceAndPhysicalCapabilitiesAreExplicitlyUnsupported()
      throws Exception {
    try (var f = new Fixture(9_999_999_999L)) {
      var valuation = new WealthValuation();
      assertFalse(f.snapshot(valuation, NOW).eligible());
      f.balance(10_000_000_000L);
      var exact = f.snapshot(valuation, NOW);
      assertTrue(exact.available());
      assertTrue(exact.eligible());
      assertEquals(0, new BigDecimal("100000000").compareTo(exact.netWorth()));
      assertEquals(
          1, exact.components().size()); // No additional available/held representation is summed.
      f.balance(10_000_000_001L);
      assertTrue(f.snapshot(valuation, NOW).eligible());
      assertTrue(valuation.unsupportedCapabilities().contains("physical-cash"));
      assertTrue(
          valuation.unsupportedCapabilities().contains("securities-without-installed-adapter"));
    }
  }

  @Test
  void borrowingProceedsAndMatchingLiabilityDoNotIncreaseWealthAndDebtCannotDisappearAtWriteoff()
      throws Exception {
    try (var f = new Fixture(10_000_000_000L)) {
      var debt = new AtomicReference<>(state(1, NOW, "debt-v1", "facility:loan-1", CAD, "1000"));
      var valuation =
          new WealthValuation(
              List.of(source("fixture:debt", WealthValuation.Kind.LIABILITY, false, debt)));
      assertFalse(f.snapshot(valuation, NOW).eligible());
      f.balance(10_000_100_000L);
      assertTrue(f.snapshot(valuation, NOW).eligible());
      assertEquals(0, new BigDecimal("100000000").compareTo(f.snapshot(valuation, NOW).netWorth()));
      debt.set(state(2, NOW, "creditor-writeoff", "facility:loan-1", CAD, "1000"));
      assertEquals(0, new BigDecimal("100000000").compareTo(f.snapshot(valuation, NOW).netWorth()));
      debt.set(WealthValuation.SourceSnapshot.unavailable(3, NOW));
      var unavailable = f.snapshot(valuation, NOW);
      assertFalse(unavailable.available());
      assertNull(unavailable.netWorth());
    }
  }

  @Test
  void unavailableStaleFutureOrDuplicatedInstalledClaimsCannotQualify() throws Exception {
    try (var f = new Fixture(10_000_000_000L)) {
      var mark =
          new AtomicReference<>(
              state(1, NOW.minusMillis(1_200_001), "mark-v1", "security:aggregate", CAD, "5"));
      var valuation =
          new WealthValuation(
              List.of(source("fixture:securities", WealthValuation.Kind.ASSET, true, mark)));
      assertFalse(f.snapshot(valuation, NOW).available());
      mark.set(state(1, NOW.plusMillis(1), "future", "security:aggregate", CAD, "5"));
      assertFalse(f.snapshot(valuation, NOW).available());
      mark.set(state(1, NOW, "fresh", "ledger:" + ACCOUNT, CAD, "5"));
      assertFalse(f.snapshot(valuation, NOW).available());
      mark.set(state(1, NOW, "fresh", "security:aggregate", CAD, "5"));
      assertTrue(f.snapshot(valuation, NOW).available());
      assertFalse(f.snapshot(valuation, NOW.plus(Duration.ofHours(49))).available());
    }
  }

  @Test
  void quotesBindCurrentAssetsSourcesDenominationFxAndPolicyAndExpireAtEarlierUnderlyingTime()
      throws Exception {
    try (var f = new Fixture(10_000_000_000L)) {
      var debt = new AtomicReference<>(state(1, NOW, "debt-v1", "facility:loan-1", CAD, "0"));
      var pricing =
          new ServicePricing(
              new WealthValuation(
                  List.of(source("fixture:debt", WealthValuation.Kind.LIABILITY, false, debt))));
      var quote =
          f.store.transaction(
              tx ->
                  pricing.quote(
                      tx::query,
                      f.world,
                      OWNER,
                      HYBRID,
                      ServicePricing.Service.FX,
                      new Money(CAD, 1000),
                      NOW,
                      NOW.plusSeconds(20)));
      assertEquals(0, quote.fee().minorUnits());
      assertEquals(NOW.plusSeconds(20), quote.expires());
      f.store.transaction(
          tx -> {
            assertEquals(
                quote, pricing.requireFresh(quote, tx::query, f.world, OWNER, NOW.plusSeconds(1)));
            return null;
          });
      assertThrows(
          IllegalArgumentException.class,
          () ->
              f.store.transaction(
                  tx ->
                      pricing.requireFresh(quote, tx::query, f.world, OWNER, NOW.plusSeconds(20))));
      debt.set(state(2, NOW, "debt-v2", "facility:loan-1", CAD, "1"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              f.store.transaction(
                  tx ->
                      pricing.requireFresh(quote, tx::query, f.world, OWNER, NOW.plusSeconds(1))));
      var downgraded =
          f.store.transaction(
              tx ->
                  pricing.quote(
                      tx::query,
                      f.world,
                      OWNER,
                      HYBRID,
                      ServicePricing.Service.FX,
                      new Money(CAD, 101),
                      NOW,
                      NOW.plusSeconds(60)));
      assertEquals(1, downgraded.fee().minorUnits());
      assertFalse(downgraded.tierTwo());
      var fact = ServicePricing.fact(downgraded);
      ServicePricing.register(new RecordCatalog(Map.of()))
          .validate(fact.type(), fact.version(), fact.payload());
    }
  }

  @Test
  void denominationAndCrossCurrencyLiabilitiesUseOneCapturedPublication() throws Exception {
    try (var f = new Fixture(10_000_000_000L)) {
      var debt = new AtomicReference<>(state(1, NOW, "usd-debt", "facility:usd", USD, "1000"));
      var valuation =
          new WealthValuation(
              List.of(source("fixture:debt", WealthValuation.Kind.LIABILITY, false, debt)));
      var snapshot = f.snapshot(valuation, NOW);
      assertFalse(snapshot.eligible());
      assertEquals(CAD, snapshot.denomination());
      f.store.transaction(
          tx -> {
            tx.execute(
                "UPDATE bank_account_providers SET revision=2 WHERE account_id=?",
                List.of(ACCOUNT.toString()));
            return null;
          });
      assertNotEquals(snapshot.fingerprint(), f.snapshot(valuation, NOW).fingerprint());
    }
  }

  @Test
  void unavailableDailyReevaluationRetainsDisplayedTierButCannotSupplyTierTwoBenefits()
      throws Exception {
    try (var f = new Fixture(10_000_000_000L)) {
      var source = new AtomicReference<>(state(1, NOW, "mark", "security:owned", CAD, "0"));
      var valuation =
          new WealthValuation(
              List.of(source("fixture:mark", WealthValuation.Kind.ASSET, true, source)));
      var first =
          f.store.transaction(
              tx ->
                  TierReevaluation.evaluate(
                      tx::query, f.world, OWNER, valuation, NOW.toEpochMilli()));
      f.store.transaction(
          tx -> {
            first.effects().apply(tx);
            return null;
          });
      assertTrue(
          f.store
              .transaction(tx -> TierReevaluation.status(tx::query, OWNER).orElseThrow())
              .eligible());
      source.set(WealthValuation.SourceSnapshot.unavailable(2, NOW.plusMillis(1_200_000)));
      var next =
          f.store.transaction(
              tx ->
                  TierReevaluation.evaluate(
                      tx::query, f.world, OWNER, valuation, NOW.toEpochMilli() + 1_200_000));
      f.store.transaction(
          tx -> {
            next.effects().apply(tx);
            return null;
          });
      var retained =
          f.store.transaction(tx -> TierReevaluation.status(tx::query, OWNER).orElseThrow());
      assertTrue(retained.eligible());
      assertFalse(retained.available());
      assertEquals("100000000", retained.netWorth());
      assertFalse(
          f.store.<Boolean>transaction(
              tx -> TierReevaluation.tierTwoAt(tx::query, OWNER, NOW.toEpochMilli() + 1_200_000)));
    }
  }

  @Test
  void unavailableInstalledValuationKeepsProtectedFxAvailableAtTierOneWithoutClaimingZeroBenefits()
      throws Exception {
    try (var f = new Fixture(10_000_000_000L)) {
      var liability = new AtomicReference<>(WealthValuation.SourceSnapshot.unavailable(7, NOW));
      var pricing =
          new ServicePricing(
              new WealthValuation(
                  List.of(
                      source(
                          "fixture:liability", WealthValuation.Kind.LIABILITY, false, liability))));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              f.store.transaction(
                  tx ->
                      pricing.quote(
                          tx::query,
                          f.world,
                          OWNER,
                          HYBRID,
                          ServicePricing.Service.FX,
                          new Money(CAD, 1000),
                          NOW,
                          NOW.plusSeconds(60))));
      var protectedQuote =
          f.store.transaction(
              tx ->
                  pricing.quote(
                      tx::query,
                      f.world,
                      OWNER,
                      HYBRID,
                      ServicePricing.Service.PROTECTED_WITHDRAWAL_FX,
                      new Money(CAD, 1000),
                      NOW,
                      NOW.plusSeconds(60)));
      assertFalse(protectedQuote.valuation().available());
      assertEquals(5, protectedQuote.fee().minorUnits());
      assertFalse(protectedQuote.tierTwo());
      f.store.transaction(
          tx -> {
            pricing.requireFresh(protectedQuote, tx::query, f.world, OWNER, NOW.plusSeconds(1));
            return null;
          });
      liability.set(state(8, NOW, "restored", "facility:loan-1", CAD, "0"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              f.store.transaction(
                  tx ->
                      pricing.requireFresh(
                          protectedQuote, tx::query, f.world, OWNER, NOW.plusSeconds(1))));
    }
  }

  @Test
  void feeRoundingIsOncePerCumulativeOrderNotOncePerPartialFill() {
    var one =
        ServicePricing.fee(new Money(CAD, 1), ServicePricing.Service.FX, ServicePricing.Tier.TIER1);
    assertEquals(1, one.minorUnits());
    assertEquals(
        0,
        ServicePricing.fee(
                new Money(CAD, Long.MAX_VALUE),
                ServicePricing.Service.FX,
                ServicePricing.Tier.TIER2)
            .minorUnits());
    var accumulator = CumulativeTradingFee.Accumulator.empty(CAD);
    long sum = 0;
    for (int i = 0; i < 1000; i++) {
      var fill = accumulator.fill(new Money(CAD, 1), ServicePricing.Tier.TIER1);
      sum += fill.incrementalFee().minorUnits();
      accumulator = fill.accumulator();
    }
    assertEquals(3, sum);
    assertEquals(new BigDecimal("0.025000000000000000"), accumulator.exactFeeMajor());
    var cancelled = accumulator.fill(new Money(CAD, 0), ServicePricing.Tier.TIER1);
    assertEquals(0, cancelled.incrementalFee().minorUnits());
    assertEquals(
        0,
        CumulativeTradingFee.Accumulator.empty(CAD)
            .fill(new Money(CAD, 1000), ServicePricing.Tier.TIER2)
            .incrementalFee()
            .minorUnits());
  }

  @Test
  void configuredPrimeIsFutureBoundaryVersionedAndInvalidatesAnOldPolicyQuote() throws Exception {
    try (var f = new Fixture(10000)) {
      long origin = NOW.toEpochMilli(), boundary = origin + 1_200_000;
      f.store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO financial_calendar(singleton,version,origin_ms,observed_wall_ms,last_effective_ms,completed_effects) VALUES(1,1,?,?,?,0)",
                List.of(Long.toString(origin), Long.toString(origin), Long.toString(origin)));
            return null;
          });
      var change = new PricingPolicy.Change(boundary, new BigDecimal("7.1234"));
      var operation = intent(PricingPolicy.CHANGE, change.request());
      var engine =
          new JournalEngine(
              f.store,
              (a, k) -> true,
              Map.of(
                  PricingPolicy.CHANGE,
                  PricingPolicy.change(change, Clock.fixed(NOW, ZoneOffset.UTC))));
      var result = engine.submit(operation);
      assertEquals(Journal.Status.COMMITTED, result.status());
      assertEquals(result, engine.submit(operation));
      assertEquals(
          new BigDecimal("0.06"),
          f.store.transaction(tx -> PricingPolicy.current(tx::query, boundary - 1)).primeApr());
      assertEquals(
          new BigDecimal("0.071234"),
          f.store.transaction(tx -> PricingPolicy.current(tx::query, boundary)).primeApr());
      var bad = new PricingPolicy.Change(origin + 1, new BigDecimal("8"));
      var rejected =
          new JournalEngine(
                  f.store,
                  (a, k) -> true,
                  Map.of(
                      PricingPolicy.CHANGE,
                      PricingPolicy.change(bad, Clock.fixed(NOW, ZoneOffset.UTC))))
              .submit(intent(PricingPolicy.CHANGE, bad.request()));
      assertEquals("PRICING_FUTURE_DAY_REQUIRED", rejected.result().code());
      var pricing = new ServicePricing(new WealthValuation());
      var before = Instant.ofEpochMilli(boundary - 30_000);
      var quote =
          f.store.transaction(
              tx ->
                  pricing.quote(
                      tx::query,
                      f.world,
                      OWNER,
                      HYBRID,
                      ServicePricing.Service.FX,
                      new Money(CAD, 1000),
                      before,
                      before.plusSeconds(60)));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              f.store.transaction(
                  tx ->
                      pricing.requireFresh(
                          quote, tx::query, f.world, OWNER, Instant.ofEpochMilli(boundary))));
    }
  }

  @Test
  void primeConfigurationRejectsInvalidPrecisionAndRangeRatherThanClamping() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PricingPolicy.Change(0, new BigDecimal("50.0001")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PricingPolicy.Change(0, new BigDecimal("1.00000")));
    assertThrows(
        IllegalArgumentException.class, () -> new PricingPolicy.Change(0, new BigDecimal("-0.1")));
    assertDoesNotThrow(() -> new PricingPolicy.Change(0, new BigDecimal("50.0000")));
    assertDoesNotThrow(() -> new PricingPolicy.Change(0, BigDecimal.ZERO));
  }
}
