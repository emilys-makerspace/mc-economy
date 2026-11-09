package ca.emilybelnavis.mcmods.economy.simulation.macro;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.simulation.activity.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.*;
import ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual SQLite, fixed retirement and dispatcher children, including partial-event reopen. */
class MacroProviderTest {
  @TempDir Path path;

  @Test
  void midnightHandoverFreezesActivityPlanAcrossPartialRestartAndWaitsForDispatcherCompletion()
      throws Exception {
    try (var f = new Fixture(path, MacroControls.Mode.AUTO)) {
      assertEquals(
          f.f,
          f.activation.handoverMillis(),
          "F=H excludes the old daily interval at the same instant");
      var event = MacroProvider.event(f.world(), f.f);
      var manifest = f.store.transaction(tx -> f.provider.targets(event, "", 16, tx::query));
      assertEquals(16, manifest.size());
      assertEquals("00-open", manifest.getFirst());
      f.now = f.a + 1;
      f.adjust(100_000);
      f.now = f.f;
      assertTrue(f.clock.runBatch(16).ready());
      assertEquals(0, f.clock.currentProgress().completedSequence());
      assertNull(f.clock.nextDueMillis());
      for (int n = 0; n < 16; n++) f.dispatcher.advance();
      UUID window = MacroProvider.windowId(f.world(), f.f);
      var selected = f.store.transaction(tx -> MacroPlans.read(tx::query, window));
      assertNotNull(selected);
      assertTrue(
          selected.primes().get(CAD).compareTo(selected.primes().get(USD)) > 0,
          "Real CAD money creation affects the frozen model");
      assertEquals(
          1,
          f.store
              .transaction(
                  tx ->
                      ca.emilybelnavis.mcmods.economy.simulation.plan.ActivationModelPlans.read(
                          tx::query, window))
              .input()
              .windows()
              .get(CAD)
              .days());
      assertFalse(
          f.store.<Boolean>transaction(tx -> MacroActivation.ready(tx::query, f.world(), f.f)));
      f.reopen();
      assertEquals(selected, f.store.transaction(tx -> MacroPlans.read(tx::query, window)));
      assertEquals(
          manifest,
          f.store.transaction(tx -> f.provider.targets(event, "", 16, tx::query)),
          "Target membership never shrinks with published projections");
      for (int n = 16; n < 26; n++) f.dispatcher.advance();
      assertNotNull(f.store.transaction(tx -> MacroPlans.completion(tx::query, window)));
      assertFalse(
          f.store.<Boolean>transaction(tx -> CurrencyPrimes.ready(tx::query, f.world(), f.f)),
          "Prime admission also waits for dispatcher completion");
      assertFalse(
          f.store.<Boolean>transaction(tx -> MacroActivation.ready(tx::query, f.world(), f.f)),
          "The basket child is not dispatcher completion");
      var fx = f.store.transaction(tx -> FxService.snapshot(tx::query));
      assertEquals(f.f, fx.effectiveMillis());
      assertEquals(window, fx.window());
      for (var currency : EconomyCurrency.all()) {
        var prime =
            f.store.transaction(
                tx -> CurrencyPrimes.current(tx::query, f.world(), currency, f.f).orElseThrow());
        assertEquals(f.f, prime.effectiveMillis());
        assertEquals(window, prime.window());
        assertEquals(0, selected.primes().get(currency).compareTo(prime.primeApr()));
        assertEquals(0, selected.fx().get(currency).compareTo(fx.rates().get(currency)));
      }
      f.dispatcher.advance();
      assertTrue(f.dispatcher.progress().ready());
      assertTrue(
          f.store.<Boolean>transaction(tx -> MacroActivation.ready(tx::query, f.world(), f.f)));
      assertTrue(
          f.store.<Boolean>transaction(tx -> CurrencyPrimes.ready(tx::query, f.world(), f.f)));
      assertEquals(
          1,
          f.store
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT payload FROM journal_records WHERE record_type=?",
                          List.of(FxFacts.PUBLICATION),
                          16))
              .size(),
          "No excluded daily publication is fabricated");
      f.reopen();
      assertTrue(f.dispatcher.progress().ready());
      assertEquals(selected, f.store.transaction(tx -> MacroPlans.read(tx::query, window)));
    }
  }

  @Test
  void emptyWindowsPauseManualOnceAndResumeWithoutDeferredAutomaticMoves() throws Exception {
    try (var f = new Fixture(path, MacroControls.Mode.PAUSED)) {
      var original = f.store.transaction(tx -> FxService.snapshot(tx::query));
      f.finish(f.f);
      var first = f.outcome(f.f);
      assertEquals(MacroControls.Mode.PAUSED, first.mode());
      assertVector(original.rates(), first.fx());
      var deltas = vector("0");
      deltas.put(CAD, new BigDecimal("0.0005"));
      var multipliers = vector("1");
      multipliers.put(USD, new BigDecimal("1.002"));
      var manual =
          new MacroControls.Policy(
              UUID.randomUUID(),
              f.world(),
              2,
              f.f + CurrencyPrimes.DAY_MILLIS,
              MacroControls.Mode.MANUAL,
              f.config,
              deltas,
              multipliers);
      MacroControls.schedule(f.store, manual, f.wall.instant());
      var resume =
          new MacroControls.Policy(
              UUID.randomUUID(),
              f.world(),
              3,
              f.f + 3 * CurrencyPrimes.DAY_MILLIS,
              MacroControls.Mode.AUTO,
              f.config,
              Map.of(),
              Map.of());
      MacroControls.schedule(f.store, resume, f.wall.instant());
      f.finish(manual.effectiveMillis());
      var once = f.outcome(manual.effectiveMillis());
      assertEquals(MacroControls.Mode.MANUAL, once.mode());
      assertEquals(Rational.ONE, once.alpha());
      assertEquals(
          0,
          first.primes().get(CAD).add(new BigDecimal("0.0005")).compareTo(once.primes().get(CAD)));
      assertEquals(
          0, first.fx().get(USD).multiply(new BigDecimal("1.002")).compareTo(once.fx().get(USD)));
      f.finish(f.f + 2 * CurrencyPrimes.DAY_MILLIS);
      var held = f.outcome(f.f + 2 * CurrencyPrimes.DAY_MILLIS);
      assertEquals(MacroControls.Mode.PAUSED, held.mode());
      assertEquals(manual.id(), held.policyId());
      assertEquals(once.primes(), held.primes());
      assertEquals(once.fx(), held.fx());
      f.finish(resume.effectiveMillis());
      var automatic = f.outcome(resume.effectiveMillis());
      assertEquals(MacroControls.Mode.AUTO, automatic.mode());
      assertEquals(held.primes(), automatic.primes());
      assertEquals(held.fx(), automatic.fx());
      f.reopen();
      assertEquals(automatic, f.outcome(resume.effectiveMillis()));
      assertTrue(f.dispatcher.progress().ready());
    }
  }

  private static EnumMap<EconomyCurrency, BigDecimal> vector(String value) {
    var map = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
    for (var c : EconomyCurrency.all()) map.put(c, new BigDecimal(value));
    return map;
  }

  private static void assertVector(
      Map<EconomyCurrency, BigDecimal> a, Map<EconomyCurrency, BigDecimal> b) {
    assertEquals(a.keySet(), b.keySet());
    for (var c : a.keySet()) assertEquals(0, a.get(c).compareTo(b.get(c)));
  }

  static final class Fixture implements AutoCloseable {
    final Path path;
    SqliteStorage store;
    final MacroProvider provider = new MacroProvider();
    DeadlineDispatcher dispatcher;
    EconomicClock clock;
    ActivityRuntime activity;
    final PlayerId actor = new PlayerId(UUID.randomUUID());
    final AccountId account = new AccountId(UUID.randomUUID());
    long now = Instant.parse("2026-11-07T23:20:00Z").toEpochMilli();
    final long origin = now,
        a = origin + CurrencyPrimes.DAY_MILLIS,
        f = a + CurrencyPrimes.DAY_MILLIS;
    final Clock wall =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId zone) {
            return this;
          }

          public Instant instant() {
            return Instant.ofEpochMilli(now);
          }
        };
    final MacroModel.Config config = MacroModel.Config.defaults(UUID.randomUUID(), 1);
    MacroActivation.Receipt activation;

    Fixture(Path path, MacroControls.Mode mode) throws Exception {
      this.path = path;
      store = new SqliteStorage();
      store.initialize(path.toRealPath());
      wire();
      dispatcher.prepare();
      submit(
          FxService.OPEN,
          FxService.opening(wall),
          new Journal.CanonicalRequest(1, Map.of("currency", "CAD")));
      clock.prepare();
      store.transaction(
          tx ->
              tx.execute(
                  "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                  List.of(account.value().toString(), actor.value().toString())));
      installActivity();
      var initial =
          new MacroControls.Policy(
              UUID.randomUUID(), world(), 1, a, mode, config, Map.of(), Map.of());
      activation =
          MacroActivation.activate(
              store, clock, dispatcher, UUID.randomUUID(), a, initial, wall.instant());
    }

    WorldId world() {
      return store.snapshot().worldId();
    }

    void wire() {
      dispatcher = new DeadlineDispatcher(store, wall, List.of(provider), 16);
      clock =
          new EconomicClock(
              store,
              wall,
              List.of(FxService.definition()),
              Map.of(FxService.SYSTEM, FxService.deferred()),
              DeadlineDispatcher.implementations(List.of(provider)));
    }

    void installActivity() {
      activity = new ActivityRuntime(store, wall);
      store.commitObserver((i, r, c) -> activity.observe(i, r, c, now));
      for (int n = 0; n < 100; n++) if (activity.prepare()) return;
      fail("Activity seed did not become ready");
    }

    void reopen() throws Exception {
      store.close();
      store = new SqliteStorage();
      store.initialize(path.toRealPath());
      wire();
      clock.prepare();
      dispatcher.prepare();
      installActivity();
    }

    void finish(long time) {
      now = time;
      assertTrue(clock.runBatch(16).ready());
      for (int n = 0; n < 40; n++) {
        dispatcher.advance();
        if (dispatcher.progress().ready()) return;
      }
      fail("Macro event did not finish in 26 children plus completion");
    }

    MacroPlans.Outcome outcome(long time) {
      return store.transaction(
          tx -> MacroPlans.read(tx::query, MacroProvider.windowId(world(), time)));
    }

    void adjust(long amount) {
      var request =
          new SameCurrency.Adjustment(
              actor, new SameCurrency.Revision(account, 1), new Money(CAD, amount));
      submit(
          SameCurrency.CREDIT,
          SameCurrency.registrations(world(), (i, instruction, r) -> true).get(SameCurrency.CREDIT),
          request.request());
    }

    void submit(
        Journal.Kind kind,
        JournalEngine.Registration registration,
        Journal.CanonicalRequest request) {
      UUID op = UUID.randomUUID();
      var result =
          new JournalEngine(store, (a, k) -> true, Map.of(kind, registration))
              .submit(
                  new Journal.Intent(
                      new OperationId(op), new RequestId(op), actor, kind, request, null, null));
      assertEquals(Journal.Status.COMMITTED, result.status(), result.result().code());
    }

    public void close() throws Exception {
      store.close();
    }
  }
}
