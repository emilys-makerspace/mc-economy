package ca.emilybelnavis.mcmods.economy.simulation.prime;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.DeadlineDispatcher;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.PricingPolicy;
import ca.emilybelnavis.mcmods.economy.simulation.activity.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.MacroModel;
import ca.emilybelnavis.mcmods.economy.simulation.plan.ActivationModelPlans;
import ca.emilybelnavis.mcmods.economy.simulation.window.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real SQLite immutable-source witnesses, with no provider/clock registration or fake operator
 * time.
 */
class CurrencyPrimesTest {
  @TempDir Path path;

  @Test
  void distinctCurrencyPoliciesHoldHistoryAndReplayWhilePartialBasketsStayUnavailable()
      throws Exception {
    CurrencyPrimes.Policy cad, usd;
    UUID activation;
    long boundary;
    try (var f = new Fixture(path)) {
      var config = UUID.randomUUID();
      long a = f.origin + CurrencyPrimes.DAY_MILLIS;
      boundary = a + CurrencyPrimes.DAY_MILLIS;
      activation = UUID.randomUUID();
      var inherited =
          f.store.transaction(tx -> CurrencyPrimes.select(tx::query, f.world(), CAD, a - 1));
      assertFalse(inherited.currencySpecific());
      assertEquals(1, inherited.legacyVersion());
      var captured = CurrencyPrimes.activate(f.store, activation, a, config, f.wall.instant());
      assertEquals(
          captured, CurrencyPrimes.activate(f.store, activation, a, config, f.wall.instant()));
      assertEquals(
          inherited.id(),
          f.store.transaction(tx -> CurrencyPrimes.select(tx::query, f.world(), USD, a - 1)).id(),
          "Before takeover both explicitly read the same configured global policy");
      assertTrue(f.store.<Boolean>transaction(tx -> CurrencyPrimes.ready(tx::query, f.world(), a)));
      // Retain the old complete day; the additive activation plan filters it rather than rewriting
      // it.
      f.close(a, config, captured.fxSnapshot());
      f.now = a + 1;
      f.adjust(f.cad, CAD, 100_000, true);
      f.adjust(f.usd, USD, 100_000, false);
      UUID window = f.close(boundary, config, captured.fxSnapshot());
      var plan =
          ActivationModelPlans.prepare(
              f.store,
              activation,
              window,
              captured.reference(),
              captured.anchors(),
              MacroModel.Config.defaults(config, 1));
      assertEquals(1, plan.input().windows().get(CAD).days());
      assertEquals(0, plan.input().windows().get(CAD).baselineDays());
      cad = CurrencyPrimes.publish(f.store, window, CAD, CurrencyPrimes.Mode.AUTO);
      assertFalse(
          f.store.<Boolean>transaction(tx -> CurrencyPrimes.ready(tx::query, f.world(), boundary)));
      assertThrows(
          IllegalStateException.class,
          () ->
              f.store.transaction(
                  tx -> CurrencyPrimes.select(tx::query, f.world(), CAD, boundary)));
      assertEquals(cad, CurrencyPrimes.publish(f.store, window, CAD, CurrencyPrimes.Mode.AUTO));
      for (var c : EconomyCurrency.all())
        if (c != CAD) CurrencyPrimes.publish(f.store, window, c, CurrencyPrimes.Mode.AUTO);
      usd =
          f.store.transaction(
              tx -> CurrencyPrimes.current(tx::query, f.world(), USD, boundary).orElseThrow());
      assertTrue(cad.primeApr().compareTo(captured.inheritedApr()) > 0);
      assertTrue(usd.primeApr().compareTo(captured.inheritedApr()) < 0);
      assertTrue(
          cad.primeApr().subtract(captured.inheritedApr()).abs().compareTo(new BigDecimal("0.001"))
              <= 0);
      var done = CurrencyPrimes.complete(f.store, window);
      assertEquals(done, CurrencyPrimes.complete(f.store, window));
      assertTrue(
          f.store.<Boolean>transaction(tx -> CurrencyPrimes.ready(tx::query, f.world(), boundary)));
      var selected =
          f.store.transaction(tx -> CurrencyPrimes.select(tx::query, f.world(), CAD, boundary));
      assertEquals(cad.id(), selected.id());
      assertTrue(selected.currencySpecific());
      assertEquals(0, selected.legacyVersion());
      assertEquals(
          captured.inheritedApr(),
          f.store
              .transaction(tx -> CurrencyPrimes.current(tx::query, f.world(), CAD, a).orElseThrow())
              .primeApr());
    }
    var reopened = new SqliteStorage();
    reopened.initialize(path.toRealPath());
    try {
      var world = reopened.snapshot().worldId();
      assertEquals(
          cad,
          reopened.transaction(
              tx -> CurrencyPrimes.byId(tx::query, world, cad.id()).orElseThrow()));
      assertEquals(
          usd,
          reopened.transaction(
              tx -> CurrencyPrimes.current(tx::query, world, USD, boundary).orElseThrow()));
      assertEquals(
          activation,
          reopened
              .transaction(tx -> CurrencyPrimes.activation(tx::query, world).orElseThrow())
              .id());
    } finally {
      reopened.close();
    }
  }

  @Test
  void futureLegacyEditsAndOutOfRangeInheritedPrimeRejectActivationWithoutChangingHistory()
      throws Exception {
    try (var f = new Fixture(path)) {
      long a = f.origin + CurrencyPrimes.DAY_MILLIS;
      var change = new PricingPolicy.Change(a, new BigDecimal("7"));
      f.submit(PricingPolicy.CHANGE, PricingPolicy.change(change, f.wall), change.request());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              CurrencyPrimes.activate(
                  f.store,
                  UUID.randomUUID(),
                  a + CurrencyPrimes.DAY_MILLIS,
                  UUID.randomUUID(),
                  f.wall.instant()));
      assertTrue(
          f.store.transaction(tx -> CurrencyPrimes.activation(tx::query, f.world())).isEmpty());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new CurrencyPrimes.Policy(
                  UUID.randomUUID(),
                  f.world(),
                  CAD,
                  1,
                  new BigDecimal("0.250001"),
                  a,
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  CurrencyPrimes.Mode.BOOTSTRAP));
    }
    Path second = java.nio.file.Files.createDirectory(path.resolve("out-of-range"));
    try (var f = new Fixture(second)) {
      long a = f.origin + CurrencyPrimes.DAY_MILLIS;
      var change = new PricingPolicy.Change(a, new BigDecimal("30"));
      f.submit(PricingPolicy.CHANGE, PricingPolicy.change(change, f.wall), change.request());
      f.now = a;
      assertThrows(
          IllegalArgumentException.class,
          () ->
              CurrencyPrimes.activate(
                  f.store,
                  UUID.randomUUID(),
                  a + CurrencyPrimes.DAY_MILLIS,
                  UUID.randomUUID(),
                  f.wall.instant()));
      assertEquals(
          new BigDecimal("0.30"),
          f.store.transaction(tx -> PricingPolicy.current(tx::query, a)).primeApr());
      assertTrue(
          f.store.transaction(tx -> CurrencyPrimes.activation(tx::query, f.world())).isEmpty());
    }
  }

  private static final class Fixture implements AutoCloseable {
    final SqliteStorage store = new SqliteStorage();
    final PlayerId actor = new PlayerId(UUID.randomUUID());
    final AccountId cad = new AccountId(UUID.randomUUID()), usd = new AccountId(UUID.randomUUID());
    long now = Instant.parse("2026-11-07T12:00:00Z").toEpochMilli();
    final long origin = now;
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
      submit(
          FxService.OPEN,
          FxService.opening(wall),
          new Journal.CanonicalRequest(1, Map.of("currency", "CAD")));
      new DeadlineDispatcher(store, wall, List.of(), 16).prepare();
      store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                List.of(cad.value().toString(), actor.value().toString()));
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','USD','OPEN',1,0)",
                List.of(usd.value().toString(), actor.value().toString()));
            return null;
          });
      adjust(cad, CAD, 1_000_000, true);
      adjust(usd, USD, 1_000_000, true);
      var activity = new ActivityRuntime(store, wall);
      store.commitObserver((i, r, c) -> activity.observe(i, r, c, now));
      for (int turn = 0; turn < 100; turn++) if (activity.prepare()) break;
      assertTrue(store.<Boolean>transaction(tx -> ActivityStore.ready(tx::query)));
    }

    WorldId world() {
      return store.snapshot().worldId();
    }

    void adjust(AccountId account, EconomyCurrency currency, long amount, boolean credit) {
      var request =
          new SameCurrency.Adjustment(
              actor, new SameCurrency.Revision(account, 1), new Money(currency, amount));
      var kind = credit ? SameCurrency.CREDIT : SameCurrency.DEBIT;
      submit(
          kind,
          SameCurrency.registrations(world(), (i, instruction, r) -> true).get(kind),
          request.request());
    }

    UUID close(long boundary, UUID config, UUID anchor) {
      var open = WindowStore.open(store, boundary, boundary, config, anchor);
      for (var c : EconomyCurrency.all()) WindowStore.close(store, open.id(), c);
      return open.id();
    }

    void submit(
        Journal.Kind kind,
        JournalEngine.Registration registration,
        Journal.CanonicalRequest request) {
      UUID id = UUID.randomUUID();
      var outcome =
          new JournalEngine(store, (a, k) -> true, Map.of(kind, registration))
              .submit(
                  new Journal.Intent(
                      new OperationId(id), new RequestId(id), actor, kind, request, null, null));
      assertEquals(Journal.Status.COMMITTED, outcome.status(), outcome.result().code());
    }

    public void close() throws Exception {
      store.close();
    }
  }
}
