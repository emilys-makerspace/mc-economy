package ca.emilybelnavis.mcmods.economy.simulation.window;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.DeadlineDispatcher;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.simulation.activity.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.MacroModel;
import ca.emilybelnavis.mcmods.economy.simulation.plan.ModelPlanStore;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite/journal witnesses for prospective coverage, aging and immutable plan replay. */
class WindowStoreTest {
  @TempDir Path path;
  private long now = Instant.parse("2026-11-07T12:00:00Z").toEpochMilli();
  private final Clock wall =
      new Clock() {
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId z) {
          return this;
        }

        public Instant instant() {
          return Instant.ofEpochMilli(now);
        }
      };
  private final PlayerId player = new PlayerId(UUID.randomUUID());
  private final AccountId account = new AccountId(UUID.randomUUID());

  @Test
  void warmupSealsLateSourcesAgesSevenDaysAndReplaysImmutablePlanAcrossRestart() throws Exception {
    UUID config = UUID.randomUUID(), anchor = UUID.randomUUID(), firstOpen;
    ModelPlanStore.Plan original;
    var store = new SqliteStorage();
    store.initialize(path.toRealPath());
    try {
      initializeActivity(store);
      store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                List.of(account.value().toString(), player.value().toString()));
            return null;
          });
      var state = store.transaction(tx -> ActivityStore.activation(tx::query));
      long origin = state.originMillis();
      credit(store, 100_000);
      var open =
          WindowStore.open(
              store,
              origin + ActivityFacts.DAY_MILLIS,
              origin + ActivityFacts.DAY_MILLIS,
              config,
              anchor);
      firstOpen = open.id();
      assertThrows(
          RuntimeException.class,
          () ->
              WindowStore.open(
                  store,
                  origin + 2 * ActivityFacts.DAY_MILLIS,
                  origin + 2 * ActivityFacts.DAY_MILLIS,
                  config,
                  anchor));
      closeAll(store, open.id());
      var snapshot = store.transaction(tx -> WindowStore.read(tx::query, open.id(), CAD));
      assertEquals(1, snapshot.modelWindow().days());
      assertEquals(0, snapshot.modelWindow().baselineDays());
      assertEquals(
          BigInteger.valueOf(100_000),
          snapshot.modelWindow().totals().get(ActivityFacts.Kind.OPERATOR_CREATE));
      assertEquals(snapshot, WindowStore.close(store, open.id(), CAD));
      // A backward trusted clock cannot reopen a sealed accounting day.
      credit(store, 50_000);
      var days =
          store.transaction(
              tx ->
                  tx.query(
                      "SELECT day_index FROM activity_observations ORDER BY day_index",
                      List.of(),
                      16));
      assertEquals(List.of("0", "1"), days.stream().map(r -> r.get("day_index")).toList());
      var rates = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
      var primes = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
      for (var c : EconomyCurrency.all()) {
        rates.put(c, c.historicalCadRate());
        primes.put(c, new BigDecimal("0.05"));
      }
      original =
          ModelPlanStore.prepare(
              store, open.id(), CAD, rates, rates, primes, MacroModel.Config.defaults(config, 1));
      assertEquals(
          original,
          ModelPlanStore.prepare(
              store, open.id(), CAD, rates, rates, primes, MacroModel.Config.defaults(config, 1)));
      var alteredAnchor = new EnumMap<>(rates);
      alteredAnchor.put(EconomyCurrency.USD, new BigDecimal("1.4"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ModelPlanStore.prepare(
                  store,
                  open.id(),
                  CAD,
                  alteredAnchor,
                  rates,
                  primes,
                  MacroModel.Config.defaults(config, 1)));
      var baseConfig = MacroModel.Config.defaults(config, 1);
      var alteredConfig =
          new MacroModel.Config(
              config,
              1,
              ca.emilybelnavis.mcmods.economy.simulation.model.Rational.of(2000),
              baseConfig.capacityFactor(),
              baseConfig.primeWeights(),
              baseConfig.fxWeights());
      assertThrows(
          IllegalArgumentException.class,
          () -> ModelPlanStore.prepare(store, open.id(), CAD, rates, rates, primes, alteredConfig));
      for (int day = 1; day <= 8; day++) {
        long end = origin + (day + 1) * ActivityFacts.DAY_MILLIS;
        var next = WindowStore.open(store, end, end, config, anchor);
        closeAll(store, next.id());
        if (day == 8) {
          var aged =
              store.transaction(tx -> WindowStore.read(tx::query, next.id(), CAD)).modelWindow();
          assertEquals(7, aged.days());
          assertEquals(2, aged.baselineDays());
          assertTrue(aged.totals().isEmpty());
          assertEquals(
              BigInteger.valueOf(150_000),
              aged.baselineTotals().get(ActivityFacts.Kind.OPERATOR_CREATE));
        }
      }
    } finally {
      store.close();
    }
    var reopened = new SqliteStorage();
    reopened.initialize(path.toRealPath());
    try {
      assertEquals(original, reopened.transaction(tx -> ModelPlanStore.read(tx::query, firstOpen)));
    } finally {
      reopened.close();
    }
  }

  @Test
  void partialActivationIsNotInventedAsACompleteZeroDay() throws Exception {
    var store = new SqliteStorage();
    store.initialize(path.toRealPath());
    try {
      new DeadlineDispatcher(store, wall, List.of(), 16).prepare();
      long origin =
          store.transaction(
              tx ->
                  Long.parseLong(
                      tx.query(
                              "SELECT origin_ms FROM financial_calendar WHERE singleton=1",
                              List.of(),
                              1)
                          .getFirst()
                          .get("origin_ms")));
      now += 10;
      initializeActivity(store);
      UUID config = UUID.randomUUID(), anchor = UUID.randomUUID();
      assertThrows(
          RuntimeException.class,
          () ->
              WindowStore.open(
                  store,
                  origin + ActivityFacts.DAY_MILLIS,
                  origin + ActivityFacts.DAY_MILLIS,
                  config,
                  anchor));
      var open =
          WindowStore.open(
              store,
              origin + 2 * ActivityFacts.DAY_MILLIS,
              origin + 2 * ActivityFacts.DAY_MILLIS,
              config,
              anchor);
      closeAll(store, open.id());
      var window =
          store.transaction(tx -> WindowStore.read(tx::query, open.id(), CAD)).modelWindow();
      assertEquals(1, window.days());
      assertTrue(window.totals().isEmpty());
      assertEquals(1, open.firstDay());
    } finally {
      store.close();
    }
  }

  private void initializeActivity(SqliteStorage store) {
    new DeadlineDispatcher(store, wall, List.of(), 16).prepare();
    var activity = new ActivityRuntime(store, wall);
    store.commitObserver((i, r, c) -> activity.observe(i, r, c, now));
    for (int turn = 0; turn < 100; turn++) if (activity.prepare()) return;
    fail("Activity startup did not finish");
  }

  private void credit(SqliteStorage store, long minor) {
    var engine =
        new JournalEngine(
            store,
            (a, k) -> true,
            SameCurrency.registrations(store.snapshot().worldId(), (i, instruction, r) -> true));
    UUID id = UUID.randomUUID();
    var request =
        new SameCurrency.Adjustment(
            player, new SameCurrency.Revision(account, 1), new Money(CAD, minor));
    var outcome =
        engine.submit(
            new Journal.Intent(
                new OperationId(id),
                new RequestId(id),
                player,
                SameCurrency.CREDIT,
                request.request(),
                null,
                null));
    assertEquals(Journal.Status.COMMITTED, outcome.status(), outcome.result().code());
  }

  private static void closeAll(SqliteStorage store, UUID open) {
    for (var currency : EconomyCurrency.all()) WindowStore.close(store, open, currency);
  }
}
