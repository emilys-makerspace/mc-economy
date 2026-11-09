package ca.emilybelnavis.mcmods.economy.trade.market;

import static ca.emilybelnavis.mcmods.economy.trade.market.MarketFacts.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import ca.emilybelnavis.mcmods.economy.trade.catalog.*;
import ca.emilybelnavis.mcmods.economy.trade.pricing.*;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real journal/SQLite identity and bounded spatial-resolution witnesses. */
class MarketStoreTest {
  @TempDir Path path;

  @Test
  void denseCellRemainsPendingUntilAllCandidatesAreReadAndRelocationPreservesCapturedMarkets()
      throws Exception {
    var store = new SqliteStorage();
    store.initialize(path.toRealPath());
    UUID trader = UUID.randomUUID(), other = UUID.randomUUID();
    UUID selected;
    try {
      var position = new Position("minecraft:overworld", 0, 64, 0);
      Market nearest = null;
      for (int n = 0; n < 17; n++) {
        var source =
            new BranchPlans.Source("minecraft:overworld", "minecraft:village_plains", n, 0);
        var m =
            MarketStore.importVillage(
                store,
                source,
                new BranchPlans.Bounds(-2, 60, -2, 2, 70, 2),
                new Position("minecraft:overworld", n, 64, 0),
                EconomyCurrency.CAD);
        for (int turn = 0; turn < 100 && !MarketStore.indexPage(store, m.id()); turn++) {}
        if (n == 0) nearest = m;
      }
      assertNotNull(nearest);
      selected = nearest.id();
      assertTrue(
          MarketStore.resolve(store, trader, position, position, EconomyCurrency.USD, true)
              .pending());
      assertTrue(
          MarketStore.resolve(store, trader, position, position, EconomyCurrency.USD, true)
              .pending(),
          "A full sixteen-candidate page cannot choose a truncated winner");
      assertNull(store.transaction(tx -> MarketStore.binding(tx::query, trader)));
      var result = resolve(store, trader, position, EconomyCurrency.USD);
      assertEquals(selected, result.market());
      assertEquals(selected, resolve(store, other, position, EconomyCurrency.EUR).market());
      var lostJob =
          MarketStore.resolve(
              store,
              trader,
              null,
              new Position("minecraft:overworld", 5000, 64, 5000),
              EconomyCurrency.USD,
              true);
      assertFalse(lostJob.pending());
      assertEquals(result, lostJob.binding(), "Losing JOB_SITE cannot relocate an existing market");
      // A later source import/bank change cannot reroll captured currency.
      var captured =
          MarketStore.importVillage(
              store,
              new BranchPlans.Source("minecraft:overworld", "minecraft:village_plains", 0, 0),
              new BranchPlans.Bounds(-2, 60, -2, 2, 70, 2),
              position,
              EconomyCurrency.JPY);
      assertEquals(EconomyCurrency.CAD, captured.currency());
      var moved =
          resolve(
              store,
              trader,
              new Position("minecraft:overworld", 1000, 64, 1000),
              EconomyCurrency.USD);
      assertNotEquals(selected, moved.market());
      assertEquals(2, moved.revision());
      assertEquals(
          EconomyCurrency.USD,
          store.transaction(tx -> MarketStore.market(tx::query, moved.market())).currency());
    } finally {
      store.close();
    }
    var reopened = new SqliteStorage();
    reopened.initialize(path.toRealPath());
    try {
      assertEquals(
          selected, reopened.transaction(tx -> MarketStore.binding(tx::query, other)).market());
      assertEquals(
          EconomyCurrency.CAD,
          reopened.transaction(tx -> MarketStore.market(tx::query, selected)).currency());
    } finally {
      reopened.close();
    }
  }

  @Test
  void wanderingAssignmentIsSavedOnceAndIndependentWhileFallbackRequiresAnActualTrade()
      throws Exception {
    UUID first = UUID.randomUUID(), second = UUID.randomUUID();
    Market firstMarket;
    var position = new Position("minecraft:overworld", 10, 65, 20);
    var store = new SqliteStorage();
    store.initialize(path.toRealPath());
    try {
      assertTrue(
          MarketStore.resolve(store, UUID.randomUUID(), null, position, EconomyCurrency.CAD, false)
              .pending());
      var one = MarketStore.bindWandering(store, first, 1234, position, EconomyCurrency.all());
      var two = MarketStore.bindWandering(store, second, 1234, position, EconomyCurrency.all());
      assertNotEquals(one.market(), two.market());
      firstMarket = store.transaction(tx -> MarketStore.market(tx::query, one.market()));
      assertEquals(
          one,
          MarketStore.bindWandering(
              store,
              first,
              9876,
              new Position("minecraft:the_nether", 100, 80, 200),
              List.of(EconomyCurrency.JPY)));
      assertEquals(
          firstMarket, store.transaction(tx -> MarketStore.market(tx::query, one.market())));
    } finally {
      store.close();
    }
    var reopened = new SqliteStorage();
    reopened.initialize(path.toRealPath());
    try {
      var binding = reopened.transaction(tx -> MarketStore.binding(tx::query, first));
      assertEquals(
          firstMarket, reopened.transaction(tx -> MarketStore.market(tx::query, binding.market())));
    } finally {
      reopened.close();
    }
  }

  @Test
  void newSpatialMarketRestartsAnUnfinishedSearchBeforeChoosingItsWinner() throws Exception {
    var store = new SqliteStorage();
    store.initialize(path.toRealPath());
    try {
      UUID pending = UUID.randomUUID(), creator = UUID.randomUUID();
      var target = new Position("minecraft:overworld", 0, 64, 0);
      assertTrue(
          MarketStore.resolve(store, pending, target, target, EconomyCurrency.CAD, true).pending());
      assertTrue(
          MarketStore.resolve(store, pending, target, target, EconomyCurrency.CAD, true).pending());
      long before =
          store.transaction(tx -> MarketStore.search(tx::query, pending)).spatialRevision();
      var created =
          resolve(
              store, creator, new Position("minecraft:overworld", 1, 64, 0), EconomyCurrency.USD);
      assertTrue(
          MarketStore.resolve(store, pending, target, target, EconomyCurrency.CAD, true).pending());
      var restarted = store.transaction(tx -> MarketStore.search(tx::query, pending));
      assertEquals(0, restarted.phase());
      assertTrue(restarted.spatialRevision() > before);
      assertEquals(created.market(), resolve(store, pending, target, EconomyCurrency.CAD).market());
    } finally {
      store.close();
    }
  }

  @Test
  void exactCatalogComponentsAndSecondaryInputsSurviveOriginalReplayAndReopen() throws Exception {
    var ingredient =
        new TradeCatalog.Ingredient(
            new TradeCatalog.Goods("minecraft:cod", TradeCatalog.Match.REQUIRED_COMPONENTS, "{}"),
            6);
    var goods =
        new TradeCatalog.Goods("minecraft:cooked_cod", TradeCatalog.Match.EXACT_COMPONENTS, "{}");
    var offer =
        new TradeCatalog.Offer(0, Pricing.Side.BUY, goods, 6, 1, List.of(ingredient), 16, 1);
    var capture = new TradeCatalog.Capture(UUID.randomUUID(), 1, List.of(offer));
    assertEquals(308, StandardQuantities.all().size());
    assertEquals(15, StandardQuantities.require("minecraft:cod"));
    assertEquals(6, offer.standardQuantity());
    var a =
        new TradeCatalog.Goods(
            "minecraft:enchanted_book",
            TradeCatalog.Match.EXACT_COMPONENTS,
            "{\"minecraft:custom_data\":{\"b\":2,\"a\":1}}");
    var b =
        new TradeCatalog.Goods(
            "minecraft:enchanted_book",
            TradeCatalog.Match.EXACT_COMPONENTS,
            "{\"minecraft:custom_data\":{\"a\":1,\"b\":2}}");
    var distinct =
        new TradeCatalog.Goods(
            "minecraft:enchanted_book",
            TradeCatalog.Match.EXACT_COMPONENTS,
            "{\"minecraft:custom_data\":{\"a\":2,\"b\":2}}");
    assertEquals(a.priceKey(), b.priceKey());
    assertNotEquals(a.priceKey(), distinct.priceKey());
    assertThrows(
        IllegalArgumentException.class, () -> StandardQuantities.require("other:custom_goods"));
    var source =
        new TradePriceSource.EmeraldV1(
            UUID.randomUUID(),
            1,
            Rational.ONE,
            UUID.randomUUID(),
            EconomyCurrency.CAD,
            Rational.ONE);
    var compared = CatalogPricing.compare(offer, List.of(offer), true, source);
    assertEquals(Rational.of(100), compared.basis().minorPerStandard());
    assertThrows(
        IllegalArgumentException.class,
        () -> CatalogPricing.compare(offer, List.of(offer), false, source));
    var store = new SqliteStorage();
    store.initialize(path.toRealPath());
    try {
      assertEquals(capture, CatalogStore.capture(store, capture));
      assertEquals(capture, CatalogStore.capture(store, capture));
      var trader = UUID.randomUUID();
      var market =
          resolve(store, trader, new Position("minecraft:overworld", 0, 64, 0), EconomyCurrency.CAD)
              .market();
      var key =
          new Pricing.Key(market, goods.priceKey(), trader, Pricing.Side.BUY, UUID.randomUUID());
      var request =
          new Pricing.Request(
              key,
              compared.basis(),
              offer.standardQuantity(),
              offer.goodsCount(),
              new Pricing.Demand(Rational.ZERO, 0),
              0,
              Rational.ZERO,
              compared.comparisons(),
              Pricing.Carry.empty(key));
      var quote = Pricing.quote(request).quote();
      assertNotNull(quote);
      UUID op = UUID.randomUUID();
      var kind = new Journal.Kind("economy:fixture", "economy:fixture/price");
      var priceIntent =
          new Journal.Intent(
              new OperationId(op),
              new RequestId(op),
              new SystemActorId(UUID.randomUUID()),
              kind,
              new Journal.CanonicalRequest(1, Map.of("price", quote.exactPrincipal().toString())),
              null,
              null);
      var engine =
          new JournalEngine(
              store,
              (actor, kindValue) -> true,
              Map.of(
                  kind,
                  new JournalEngine.Registration(
                      1,
                      (i, reads) ->
                          MarketStore.preparePriceTransition(
                                  reads,
                                  store.snapshot().worldId(),
                                  op,
                                  market,
                                  goods.priceKey(),
                                  0,
                                  0,
                                  quote)
                              .commit())));
      var receipt = engine.submit(priceIntent);
      assertEquals(Journal.Status.COMMITTED, receipt.status());
      assertEquals(receipt, engine.submit(priceIntent));
      assertEquals(quote.nextCarry(), store.transaction(tx -> MarketStore.carry(tx::query, key)));
      var resetKey =
          new Pricing.Key(market, goods.priceKey(), trader, Pricing.Side.BUY, UUID.randomUUID());
      var reset =
          new Pricing.Quote(
              quote.exactPrincipal(),
              quote.minor(),
              new Pricing.Carry(resetKey, quote.nextCarry().residual()),
              quote.nextDemand(),
              quote.reputationFactor(),
              quote.currentMultiplier());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              store.transaction(
                  tx ->
                      MarketStore.preparePriceTransition(
                          tx::query,
                          store.snapshot().worldId(),
                          UUID.randomUUID(),
                          market,
                          goods.priceKey(),
                          1,
                          0,
                          reset)));
      var changed =
          new TradeCatalog.Capture(
              capture.trader(),
              1,
              List.of(
                  new TradeCatalog.Offer(
                      0, Pricing.Side.BUY, goods, 6, 2, List.of(ingredient), 16, 1)));
      assertThrows(IllegalArgumentException.class, () -> CatalogStore.capture(store, changed));
    } finally {
      store.close();
    }
    var reopened = new SqliteStorage();
    reopened.initialize(path.toRealPath());
    try {
      assertEquals(
          capture, reopened.transaction(tx -> CatalogStore.read(tx::query, capture.trader(), 1)));
    } finally {
      reopened.close();
    }
  }

  @Test
  void a7SourceSyncBlocksPartialMembershipAndCapturesRequiredSlotZeroCurrency() throws Exception {
    var store = new SqliteStorage();
    store.initialize(path.toRealPath());
    UUID village;
    try {
      var world = store.snapshot().worldId();
      var source = new BranchPlans.Source("minecraft:overworld", "minecraft:village_plains", 0, 0);
      var bank =
          InstitutionCatalog.banks().stream()
              .filter(b -> b.tier1() && b.baseCurrency() == EconomyCurrency.CAD)
              .findFirst()
              .orElseThrow();
      var snapshot =
          new BranchPlans.PlanningSnapshotV1(
              EconomyCurrency.CAD,
              1,
              1,
              1,
              "pinned-resource-v1",
              List.of(new BranchPlans.Provider(bank.id().key(), EconomyCurrency.CAD, true)),
              List.of());
      var pending =
          new BranchPlans.VillagePlanV1(
              source,
              "plains",
              5,
              List.of(new BranchPlans.Bounds(-20, 60, -20, 20, 70, 20)),
              List.of(new BranchPlans.Point(0, 64, 0)),
              null,
              BranchPlans.CaptureStatus.CAPTURE_PENDING,
              List.of(),
              "");
      var plan =
          ca.emilybelnavis.mcmods.economy.village.BranchSelection.capture(pending, snapshot, 123);
      village = BranchPlans.id(world, source);
      UUID before = UUID.randomUUID();
      var beforeIntent =
          new Journal.Intent(
              new OperationId(before),
              new RequestId(before),
              BranchPlans.owner(village),
              Branches.Action.OBSERVE.kind(),
              Branches.observeRequest(pending),
              null,
              null);
      var beforeEngine =
          new JournalEngine(
              store,
              (actor, kindValue) -> true,
              Map.of(
                  Branches.Action.OBSERVE.kind(),
                  Branches.registration(
                      Branches.Action.OBSERVE, world, i -> i.equals(beforeIntent), pending)));
      assertEquals(Journal.Status.COMMITTED, beforeEngine.submit(beforeIntent).status());
      assertFalse(VillageMarketSource.step(store, EconomyCurrency.USD));
      var waiting =
          assertThrows(
              ca.emilybelnavis.mcmods.economy.storage.StorageException.class,
              () -> VillageMarketSource.step(store, EconomyCurrency.USD));
      assertEquals(
          ca.emilybelnavis.mcmods.economy.storage.StorageException.Kind.ECONOMIC_CATCH_UP,
          waiting.kind());
      assertNull(
          store.transaction(tx -> MarketStore.market(tx::query, village)),
          "Uncaptured slot-0 choices cannot become a bankless fallback");
      UUID operation = UUID.randomUUID();
      var intent =
          new Journal.Intent(
              new OperationId(operation),
              new RequestId(operation),
              BranchPlans.owner(village),
              Branches.Action.OBSERVE.kind(),
              Branches.observeRequest(plan),
              null,
              null);
      var engine =
          new JournalEngine(
              store,
              (actor, kindValue) -> true,
              Map.of(
                  Branches.Action.OBSERVE.kind(),
                  Branches.registration(
                      Branches.Action.OBSERVE, world, i -> i.equals(intent), plan)));
      assertEquals(Journal.Status.COMMITTED, engine.submit(intent).status());
      var position = new Position("minecraft:overworld", 0, 64, 0);
      assertEquals(
          "A7_SOURCE_SYNC_REQUIRED",
          MarketStore.resolve(
                  store, UUID.randomUUID(), position, position, EconomyCurrency.USD, true)
              .reason());
      boolean ready = false;
      for (int turn = 0; turn < 100; turn++)
        if (VillageMarketSource.step(store, EconomyCurrency.USD)) {
          ready = true;
          break;
        }
      assertTrue(ready);
      assertEquals(
          EconomyCurrency.CAD,
          store.transaction(tx -> MarketStore.market(tx::query, village)).currency());
      assertEquals(
          village, resolve(store, UUID.randomUUID(), position, EconomyCurrency.USD).market());
    } finally {
      store.close();
    }
    var reopened = new SqliteStorage();
    reopened.initialize(path.toRealPath());
    try {
      assertTrue(reopened.<Boolean>transaction(tx -> VillageMarketSource.ready(tx::query)));
    } finally {
      reopened.close();
    }
  }

  private static Binding resolve(
      SqliteStorage store, UUID trader, Position workstation, EconomyCurrency currency) {
    for (int turn = 0; turn < 100; turn++) {
      var result = MarketStore.resolve(store, trader, workstation, workstation, currency, true);
      if (!result.pending()) return result.binding();
    }
    throw new AssertionError("Bounded fixture resolution did not finish");
  }
}
