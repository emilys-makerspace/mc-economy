package ca.emilybelnavis.mcmods.economy.simulation;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.journal.*;
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
    var runtime =
        new ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime(
            ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime.Configuration.production(
                wall),
            5000,
            SimulationConfig.defaults());
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
