package ca.emilybelnavis.mcmods.economy.simulation;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime;
import ca.emilybelnavis.mcmods.economy.simulation.activity.*;
import ca.emilybelnavis.mcmods.economy.simulation.macro.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimulationIntegrationTest {
  @TempDir Path path;

  @Test
  void productionRuntimeActivatesAfterFirstCurrencySelectionWithRealObservers() throws Exception {
    var wall = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);
    seedActiveEconomy(wall);
  }

  private void seedActiveEconomy(Clock wall) throws Exception {
    var runtime =
        new FoundationRuntime(
            FoundationRuntime.Configuration.production(wall), 5000, SimulationConfig.defaults());
    runtime.initialize(path.toRealPath());
    try {
      boolean ready = false;
      for (int n = 0; n < 1000; n++)
        if (runtime.startupStep().ready()) {
          ready = true;
          break;
        }
      assertTrue(ready);
      var actor = new PlayerId(UUID.randomUUID());
      UUID op = UUID.randomUUID();
      var opened =
          new JournalEngine(
                  runtime, (a, k) -> true, Map.of(FxService.OPEN, FxService.opening(wall)))
              .submit(
                  new Journal.Intent(
                      new OperationId(op),
                      new RequestId(op),
                      actor,
                      FxService.OPEN,
                      new Journal.CanonicalRequest(1, Map.of("currency", "CAD")),
                      null,
                      null));
      assertEquals(Journal.Status.COMMITTED, opened.status());
      for (int n = 0; n < 100; n++) {
        var step = runtime.prepareEconomicStep();
        if (runtime.simulationStatus().installed()) break;
      }
      assertTrue(runtime.simulationStatus().installed(), runtime.simulationStatus().reason());
    } finally {
      runtime.close();
    }
  }

  @Test
  void reopenedWorldCommitsMultipleOverdueCalendarStepsBetweenServerTicks() throws Exception {
    var origin = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);
    seedActiveEconomy(origin);
    var wall = Clock.offset(origin, Duration.ofMillis(5 * FinancialCalendar.DAY_MILLIS));
    var runtime =
        new FoundationRuntime(
            FoundationRuntime.Configuration.production(wall), 5000, SimulationConfig.defaults());
    long started = System.nanoTime();
    var services = new ServerServices(path.toRealPath(), runtime);
    try {
      long previousTurns = 0;
      long largestBatch = 0;
      int ticks = 0;
      while (services.state() == ServerServices.State.INITIALIZING
          && System.nanoTime() - started < Duration.ofSeconds(30).toNanos()) {
        Thread.sleep(50);
        long turns = services.observedDeadlineTurns();
        largestBatch = Math.max(largestBatch, turns - previousTurns);
        previousTurns = turns;
        services.tick();
        ticks++;
      }
      assertEquals(
          ServerServices.State.READY, services.state(), () -> String.valueOf(services.failure()));
      assertTrue(previousTurns > 32, "Seed enough overdue work to require multiple batches");
      assertTrue(largestBatch > 1, "Calendar catch-up must progress between Minecraft ticks");
      var elapsed = Duration.ofNanos(System.nanoTime() - started);
      System.out.printf(
          Locale.ROOT,
          "Five financial days, production SQLite fixture: %d deadline turns, %d server ticks, "
              + "%d ms to ready, up to %d turns between ticks. At one turn per tick the same "
              + "deadline work alone required at least %d ms at 20 TPS.%n",
          previousTurns,
          ticks,
          elapsed.toMillis(),
          largestBatch,
          (previousTurns - 1) * 50);
    } finally {
      assertTrue(services.stop(Duration.ofSeconds(5)));
    }
    var store = new SqliteStorage();
    try {
      store.initialize(path.toRealPath());
      var events =
          store.transaction(
              tx ->
                  tx.query(
                      "SELECT payload FROM journal_records WHERE record_type=? ORDER BY sequence, position",
                      List.of(DeadlineDispatcher.PROGRESS_RECORD),
                      1024));
      assertFalse(events.isEmpty());
      long previousTime = origin.millis();
      for (var event : events) {
        long effectiveTime = Long.parseLong(event.get("payload").split("\\|", -1)[3]);
        assertTrue(effectiveTime >= previousTime, "Overdue transactions must commit in date order");
        previousTime = effectiveTime;
      }
      assertEquals(wall.millis(), previousTime);
    } finally {
      store.close();
    }
  }

  @Test
  void productionManifestSchedulesFutureWarmupAndOperatorPolicyWhileReadsRemainPure()
      throws Exception {
    var config = SimulationConfig.defaults();
    long now = Instant.parse("2026-11-07T12:00:00Z").toEpochMilli();
    var wall = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC);
    var actor = new PlayerId(UUID.randomUUID());
    var store = new SqliteStorage();
    try {
      store.initialize(path.toRealPath());
      var provider = new MacroProvider();
      var dispatcher = new DeadlineDispatcher(store, wall, List.of(provider), 16);
      dispatcher.prepare();
      UUID op = UUID.randomUUID();
      var outcome =
          new JournalEngine(store, (a, k) -> true, Map.of(FxService.OPEN, FxService.opening(wall)))
              .submit(
                  new Journal.Intent(
                      new OperationId(op),
                      new RequestId(op),
                      actor,
                      FxService.OPEN,
                      new Journal.CanonicalRequest(1, Map.of("currency", "CAD")),
                      null,
                      null));
      assertEquals(Journal.Status.COMMITTED, outcome.status());
      var clock =
          new EconomicClock(
              store,
              wall,
              List.of(FxService.definition()),
              Map.of(FxService.SYSTEM, FxService.deferred()),
              DeadlineDispatcher.implementations(List.of(provider)));
      clock.prepare();
      var activity = new ActivityRuntime(store, wall);
      for (int n = 0; n < 100; n++) if (activity.prepare()) break;
      SimulationLifecycle.Status status = null;
      for (int n = 0; n < 8; n++) {
        status = SimulationLifecycle.prepare(store, clock, dispatcher, config, wall.instant());
        if (status.installed()) break;
      }
      assertTrue(status.installed(), status.reason());
      var manifest = store.transaction(tx -> SimulationLifecycle.read(tx::query));
      assertEquals(config, manifest.config());
      var activation =
          store.transaction(
              tx -> MacroActivation.read(tx::query, store.snapshot().worldId()).orElseThrow());
      assertTrue(activation.activationMillis() > now);
      assertEquals(
          activation.activationMillis() + ActivityFacts.DAY_MILLIS,
          activation.firstEffectiveMillis());
      assertEquals(activation.firstEffectiveMillis(), dispatcher.nextEvent().effectiveMillis());
      long before = store.lastCommittedSequence();
      var view = SimulationDiagnostics.read(store, now, status.reason());
      assertFalse(view.ready());
      assertEquals("WARMUP_COMPLETE_DAY_REQUIRED", view.reason());
      assertEquals(0, view.coverageDays());
      assertTrue(SimulationDiagnostics.history(store, Long.MAX_VALUE, 16).isEmpty());
      assertEquals(before, store.lastCommittedSequence());
      var pause =
          MacroPolicyRequests.mode(activation.firstEffectiveMillis(), MacroControls.Mode.PAUSED);
      var p = MacroPolicyRequests.schedule(store, pause, wall.instant(), actor);
      assertEquals(p, MacroPolicyRequests.schedule(store, pause, wall.instant(), actor));
      assertEquals(
          "player:" + actor.value(),
          store
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT actor FROM operation_outcomes WHERE operation_id=?",
                          List.of(
                              MacroFacts.stable(
                                      "schedule|"
                                          + store.snapshot().worldId().value()
                                          + "|"
                                          + p.id())
                                  .toString()),
                          1))
              .getFirst()
              .get("actor"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              MacroPolicyRequests.schedule(
                  store,
                  MacroPolicyRequests.mode(p.effectiveMillis(), MacroControls.Mode.AUTO),
                  wall.instant(),
                  actor));
      var invalid = new Properties();
      invalid.setProperty("unknown", "1");
      assertThrows(IllegalArgumentException.class, () -> SimulationConfig.parse(invalid));
      invalid.clear();
      invalid.setProperty("primeWeights", "1,1,1");
      assertThrows(IllegalArgumentException.class, () -> SimulationConfig.parse(invalid));
    } finally {
      store.close();
    }
    var reopened = new SqliteStorage();
    reopened.initialize(path.toRealPath());
    assertEquals(config, reopened.transaction(tx -> SimulationLifecycle.read(tx::query)).config());
    reopened.close();
  }
}
