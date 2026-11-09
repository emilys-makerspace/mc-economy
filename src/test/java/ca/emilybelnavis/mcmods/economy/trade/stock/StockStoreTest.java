package ca.emilybelnavis.mcmods.economy.trade.stock;

import static ca.emilybelnavis.mcmods.economy.trade.stock.StockFacts.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import ca.emilybelnavis.mcmods.economy.trade.catalog.*;
import ca.emilybelnavis.mcmods.economy.trade.market.*;
import ca.emilybelnavis.mcmods.economy.trade.pricing.Pricing;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite/journal witnesses for observed-tick stock and original progression boundaries. */
class StockStoreTest {
  @TempDir Path path;
  private final Eligibility eligible = new Eligibility(true, true, true, true);

  @Test
  void exactWorkingTicksRefillWithoutOfflineCatchupAndRelocationKeepsProgressAcrossRestart()
      throws Exception {
    UUID trader = UUID.randomUUID(), session = UUID.randomUUID();
    var store = new SqliteStorage();
    store.initialize(path.toRealPath());
    try {
      ordinary(store, trader, new MarketFacts.Position("minecraft:overworld", 0, 64, 0));
      capture(store, trader);
      StockStore.install(store, trader, 1, false);
      var original = trade(store, trader, 0, 2);
      assertEquals(Journal.Status.COMMITTED, original.outcome.status());
      assertEquals(
          0,
          store
              .transaction(tx -> StockStore.trader(tx::query, trader))
              .offers()
              .getFirst()
              .remaining());
      assertEquals(original.outcome, original.engine.submit(original.intent));
      for (int n = 1; n < 50; n++)
        StockStore.checkpointTrader(
            store, trader, new TickSample(session, n * 200L, 200, 200), eligible);
      var before = store.transaction(tx -> StockStore.trader(tx::query, trader));
      assertEquals(9800, before.restockTicks());
      assertEquals(0, before.offers().getFirst().remaining());
      ordinary(store, trader, new MarketFacts.Position("minecraft:overworld", 1000, 64, 1000));
      StockStore.checkpointTrader(
          store,
          trader,
          new TickSample(session, 10_000, 200, 200),
          new Eligibility(true, false, true, true));
      var refill =
          StockStore.checkpointTrader(
              store, trader, new TickSample(session, 10_200, 200, 200), eligible);
      assertEquals(0, refill.restockTicks());
      assertEquals(2, refill.offers().getFirst().remaining());
      var later =
          StockStore.checkpointTrader(
              store, trader, new TickSample(session, 1_000_000, 1, 1), eligible);
      assertEquals(1, later.restockTicks(), "Unobserved game/offline gaps contribute nothing");
      var market = store.transaction(tx -> MarketStore.binding(tx::query, trader)).market();
      var sample = new TickSample(session, 1000, 200, 200);
      var ticks = StockStore.checkpointMarket(store, market, sample);
      assertEquals(ticks, StockStore.checkpointMarket(store, market, sample));
      assertEquals(200, ticks.eligibleTicks());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              StockStore.checkpointMarket(store, market, new TickSample(session, 1100, 200, 200)));
    } finally {
      store.close();
    }
    var reopened = new SqliteStorage();
    reopened.initialize(path.toRealPath());
    try {
      var state = reopened.transaction(tx -> StockStore.trader(tx::query, trader));
      assertEquals(1, state.restockTicks());
      assertEquals(2, state.offers().getFirst().remaining());
      assertEquals(
          2,
          StockStore.checkpointTrader(
                  reopened, trader, new TickSample(UUID.randomUUID(), 0, 1, 1), eligible)
              .restockTicks());
    } finally {
      reopened.close();
    }
  }

  @Test
  void sellerHasNoPurchaseQuotaWanderingNeverRestocksAndDispatchCannotBeReacquired()
      throws Exception {
    UUID trader = UUID.randomUUID(), session = UUID.randomUUID();
    var store = new SqliteStorage();
    store.initialize(path.toRealPath());
    UUID original;
    try {
      MarketStore.bindWandering(
          store,
          trader,
          17,
          new MarketFacts.Position("minecraft:overworld", 10, 64, 10),
          EconomyCurrency.all());
      capture(store, trader);
      StockStore.install(store, trader, 1, true);
      trade(store, trader, 0, 2);
      var seller = trade(store, trader, 1, 10);
      original = seller.intent.operationId().value();
      assertEquals(
          1,
          store.transaction(tx -> StockStore.trader(tx::query, trader)).offers().get(1).remaining(),
          "SELL accepts executions beyond native purchase-use quota");
      for (int n = 1; n <= 50; n++)
        StockStore.checkpointTrader(
            store, trader, new TickSample(session, n * 200L, 200, 200), eligible);
      var stock = store.transaction(tx -> StockStore.trader(tx::query, trader));
      assertEquals(0, stock.restockTicks());
      assertEquals(0, stock.offers().getFirst().remaining());
      var dispatch = StockStore.dispatch(store, original, "worker-dispatch");
      assertNotNull(dispatch);
      assertNotNull(dispatch.claim());
      assertNull(dispatch.claim());
      assertNull(StockStore.dispatch(store, original, "worker-dispatch"));
    } finally {
      store.close();
    }
    var reopened = new SqliteStorage();
    reopened.initialize(path.toRealPath());
    try {
      assertNull(StockStore.dispatch(reopened, original, "worker-dispatch"));
      StockStore.markProgression(
          reopened, original, Stage.DISPATCHED, Stage.AMBIGUOUS, "independent-save-unavailable");
      assertThrows(
          IllegalArgumentException.class,
          () ->
              StockStore.markProgression(
                  reopened, original, Stage.AMBIGUOUS, Stage.DISPATCHED, "retry"));
      assertEquals(
          Stage.ACKNOWLEDGED,
          StockStore.markProgression(
                  reopened,
                  original,
                  Stage.AMBIGUOUS,
                  Stage.ACKNOWLEDGED,
                  "independent-saved-APPLIED-proof")
              .stage());
    } finally {
      reopened.close();
    }
  }

  private static void capture(SqliteStorage store, UUID trader) {
    CatalogStore.capture(
        store,
        new TradeCatalog.Capture(
            trader,
            1,
            List.of(
                new TradeCatalog.Offer(
                    0,
                    Pricing.Side.BUY,
                    new TradeCatalog.Goods(
                        "minecraft:bread", TradeCatalog.Match.EXACT_COMPONENTS, "{}"),
                    1,
                    1,
                    List.of(),
                    2,
                    3),
                new TradeCatalog.Offer(
                    1,
                    Pricing.Side.SELL,
                    new TradeCatalog.Goods(
                        "minecraft:wheat", TradeCatalog.Match.REQUIRED_COMPONENTS, "{}"),
                    20,
                    1,
                    List.of(),
                    1,
                    2))));
  }

  private static void ordinary(SqliteStorage store, UUID trader, MarketFacts.Position workstation) {
    for (int turn = 0; turn < 100; turn++)
      if (!MarketStore.resolve(store, trader, workstation, workstation, EconomyCurrency.CAD, true)
          .pending()) return;
    throw new AssertionError("Membership pending");
  }

  private record Submitted(JournalEngine engine, Journal.Intent intent, Journal.Outcome outcome) {}

  private static Submitted trade(SqliteStorage store, UUID trader, int offer, int executions) {
    UUID id = UUID.randomUUID();
    var kind = new Journal.Kind("fixture:stock", "fixture:stock-trade");
    var actor = new SystemActorId(UUID.randomUUID());
    var state = store.transaction(tx -> StockStore.trader(tx::query, trader));
    var binding = store.transaction(tx -> MarketStore.binding(tx::query, trader));
    var engine =
        new JournalEngine(
            store,
            (a, k) -> true,
            Map.of(
                kind,
                new JournalEngine.Registration(
                    1,
                    (i, r) ->
                        StockStore.prepareTrade(
                            r,
                            store.snapshot().worldId(),
                            id,
                            trader,
                            1,
                            binding.revision(),
                            state.revision(),
                            offer,
                            executions))));
    var intent =
        new Journal.Intent(
            new OperationId(id),
            new RequestId(id),
            actor,
            kind,
            new Journal.CanonicalRequest(
                1,
                Map.of(
                    "offer", Integer.toString(offer), "executions", Integer.toString(executions))),
            null,
            null);
    var outcome = engine.submit(intent);
    assertEquals(Journal.Status.COMMITTED, outcome.status(), outcome.result().code());
    return new Submitted(engine, intent, outcome);
  }
}
