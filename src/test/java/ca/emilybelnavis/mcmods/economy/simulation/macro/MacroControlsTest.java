package ca.emilybelnavis.mcmods.economy.simulation.macro;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.simulation.activity.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.*;
import ca.emilybelnavis.mcmods.economy.simulation.prime.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Scoped real SQLite schedules plus exact manual bound witnesses. No provider or activation
 * rollout.
 */
class MacroControlsTest {
  @TempDir Path path;

  @Test
  void schedulesAppendWithoutOverwritingAndOriginalReplayPreservesFrozenConfig() throws Exception {
    UUID last;
    long effective;
    try (var f = new Fixture(path)) {
      f.finish(f.f);
      long first = f.f + CurrencyPrimes.DAY_MILLIS;
      var pause = f.policy(2, first, MacroControls.Mode.PAUSED, Map.of(), Map.of(), f.config);
      MacroControls.schedule(f.store, pause, f.wall.instant());
      var manual =
          f.policy(
              3,
              first + CurrencyPrimes.DAY_MILLIS,
              MacroControls.Mode.MANUAL,
              deltas(),
              multipliers(),
              f.config);
      MacroControls.schedule(f.store, manual, f.wall.instant());
      var resume =
          f.policy(
              4,
              first + 2 * CurrencyPrimes.DAY_MILLIS,
              MacroControls.Mode.AUTO,
              Map.of(),
              Map.of(),
              f.config);
      MacroControls.schedule(f.store, resume, f.wall.instant());
      last = resume.id();
      effective = resume.effectiveMillis();
      assertEquals(
          manual,
          MacroControls.schedule(f.store, manual, f.wall.instant()),
          "Original operation is resolved before later revision checks");
      assertEquals(
          manual,
          f.store.transaction(
              tx -> MacroControls.current(tx::query, f.world(), manual.effectiveMillis())));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              MacroControls.schedule(
                  f.store,
                  f.policy(
                      5,
                      manual.effectiveMillis(),
                      MacroControls.Mode.PAUSED,
                      Map.of(),
                      Map.of(),
                      f.config),
                  f.wall.instant()));
      var changed =
          new MacroModel.Config(
              f.config.id(),
              f.config.revision(),
              Rational.of(2000),
              f.config.capacityFactor(),
              f.config.primeWeights(),
              f.config.fxWeights());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              MacroControls.schedule(
                  f.store,
                  f.policy(
                      5,
                      effective + CurrencyPrimes.DAY_MILLIS,
                      MacroControls.Mode.AUTO,
                      Map.of(),
                      Map.of(),
                      changed),
                  f.wall.instant()));
      assertEquals(
          4,
          f.store
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT id FROM macro_control_policies ORDER BY revision", List.of(), 16))
              .size());
    }
    var reopened = new SqliteStorage();
    reopened.initialize(path.toRealPath());
    try {
      assertEquals(
          last,
          reopened
              .transaction(
                  tx -> MacroControls.current(tx::query, reopened.snapshot().worldId(), effective))
              .id());
    } finally {
      reopened.close();
    }
  }

  @Test
  void manualOutcomeRejectsInvalidBoundsAndPreservesExactRequestedVector() {
    WorldId world = new WorldId(UUID.randomUUID());
    var config = MacroModel.Config.defaults(UUID.randomUUID(), 1);
    var changes = deltas();
    changes.put(CAD, new BigDecimal("0.001"));
    changes.put(USD, new BigDecimal("-0.001"));
    var multipliers = multipliers();
    multipliers.put(USD, new BigDecimal("1.004"));
    var policy =
        new MacroControls.Policy(
            UUID.randomUUID(),
            world,
            3,
            123,
            MacroControls.Mode.MANUAL,
            config,
            changes,
            multipliers);
    var primes = vector("0.06");
    primes.put(CAD, new BigDecimal("0.249"));
    primes.put(USD, new BigDecimal("0.001"));
    var anchors = vector("1.000000000000");
    var prior = vector("1.000000000000");
    prior.put(USD, new BigDecimal("1.990000000000"));
    var output = MacroControls.apply(policy, primes, prior, anchors, CAD);
    assertEquals(new BigDecimal("0.250000"), output.primes().get(CAD));
    assertEquals(new BigDecimal("0.000000"), output.primes().get(USD));
    assertEquals(Rational.ONE, output.alpha());
    assertEquals(BigDecimal.ONE.setScale(12), output.fx().get(CAD));
    assertEquals(new BigDecimal("1.997960000000"), output.fx().get(USD));
    assertEquals(output, MacroControls.apply(policy, primes, prior, anchors, CAD));
    var outsidePrime = new EnumMap<>(primes);
    outsidePrime.put(CAD, new BigDecimal("0.2495"));
    assertThrows(
        IllegalArgumentException.class,
        () -> MacroControls.apply(policy, outsidePrime, prior, anchors, CAD));
    var atAnchor = new EnumMap<>(prior);
    atAnchor.put(USD, new BigDecimal("2.000000000000"));
    assertThrows(
        IllegalArgumentException.class,
        () -> MacroControls.apply(policy, primes, atAnchor, anchors, CAD));
    var invalid = multipliers();
    invalid.put(USD, new BigDecimal("1.006"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MacroControls.Policy(
                UUID.randomUUID(),
                world,
                4,
                456,
                MacroControls.Mode.MANUAL,
                config,
                deltas(),
                invalid));
  }

  private static EnumMap<EconomyCurrency, BigDecimal> vector(String value) {
    var result = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
    for (var c : EconomyCurrency.all()) result.put(c, new BigDecimal(value));
    return result;
  }

  private static EnumMap<EconomyCurrency, BigDecimal> deltas() {
    return vector("0");
  }

  private static EnumMap<EconomyCurrency, BigDecimal> multipliers() {
    return vector("1");
  }

  private static final class Fixture implements AutoCloseable {
    final SqliteStorage store = new SqliteStorage();
    long now = Instant.parse("2026-11-07T23:20:00Z").toEpochMilli();
    final long a = now + CurrencyPrimes.DAY_MILLIS, f = a + CurrencyPrimes.DAY_MILLIS;
    final MacroModel.Config config = MacroModel.Config.defaults(UUID.randomUUID(), 1);
    final MacroProvider provider = new MacroProvider();
    final DeadlineDispatcher dispatcher;
    final EconomicClock clock;
    final Clock wall =
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

    Fixture(Path path) throws Exception {
      store.initialize(path.toRealPath());
      dispatcher = new DeadlineDispatcher(store, wall, List.of(provider), 16);
      dispatcher.prepare();
      clock =
          new EconomicClock(
              store,
              wall,
              List.of(FxService.definition()),
              Map.of(FxService.SYSTEM, FxService.deferred()),
              DeadlineDispatcher.implementations(List.of(provider)));
      var actor = new SystemActorId(UUID.randomUUID());
      UUID opening = UUID.randomUUID();
      var fx =
          new JournalEngine(store, (ac, k) -> true, Map.of(FxService.OPEN, FxService.opening(wall)))
              .submit(
                  new Journal.Intent(
                      new OperationId(opening),
                      new RequestId(opening),
                      actor,
                      FxService.OPEN,
                      new Journal.CanonicalRequest(1, Map.of("currency", "CAD")),
                      null,
                      null));
      assertEquals(Journal.Status.COMMITTED, fx.status());
      clock.prepare();
      var activity = new ActivityRuntime(store, wall);
      store.commitObserver((i, r, c) -> activity.observe(i, r, c, now));
      for (int n = 0; n < 100; n++) if (activity.prepare()) break;
      assertTrue(store.<Boolean>transaction(tx -> ActivityStore.ready(tx::query)));
      var initial = policy(1, a, MacroControls.Mode.PAUSED, Map.of(), Map.of(), config);
      MacroActivation.activate(
          store, clock, dispatcher, UUID.randomUUID(), a, initial, wall.instant());
    }

    void finish(long time) {
      now = time;
      assertTrue(clock.runBatch(16).ready());
      for (int n = 0; n < 40; n++) {
        dispatcher.advance();
        if (dispatcher.progress().ready()) return;
      }
      fail("Paused predecessor did not finish");
    }

    WorldId world() {
      return store.snapshot().worldId();
    }

    MacroControls.Policy policy(
        long revision,
        long time,
        MacroControls.Mode mode,
        Map<EconomyCurrency, BigDecimal> primes,
        Map<EconomyCurrency, BigDecimal> fx,
        MacroModel.Config cfg) {
      return new MacroControls.Policy(
          UUID.randomUUID(), world(), revision, time, mode, cfg, primes, fx);
    }

    public void close() throws Exception {
      store.close();
    }
  }
}
