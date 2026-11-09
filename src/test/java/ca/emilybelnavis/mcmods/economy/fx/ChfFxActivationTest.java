package ca.emilybelnavis.mcmods.economy.fx;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.account.*;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.EconomicClock;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.math.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChfFxActivationTest {
  @TempDir Path temporary;

  private static final class Wall extends Clock {
    Instant now = Instant.parse("2026-11-03T12:00:00Z");

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

  private static Journal.Intent intent(Journal.Kind kind, Map<String, String> fields) {
    return new Journal.Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        new SystemActorId(UUID.randomUUID()),
        kind,
        new Journal.CanonicalRequest(1, fields),
        null,
        null);
  }

  private static EconomicClock clock(SqliteStorage store, Wall wall) {
    var clock =
        new EconomicClock(
            store,
            wall,
            List.of(FxService.definition()),
            Map.of(FxService.SYSTEM, FxService.deferred()));
    clock.prepare();
    return clock;
  }

  private static FxFacts.Publication latest(SqliteStorage store) {
    return store.transaction(tx -> FxService.latest(tx::query));
  }

  private static FxFacts.Publication legacy(
      SqliteStorage store, EconomyCurrency reference, BigDecimal cadRate) {
    var request = intent(FxService.OPEN, Map.of("currency", reference.code()));
    var rates = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
    var settings = new EnumMap<EconomyCurrency, FxMath.Settings>(EconomyCurrency.class);
    for (var currency : FxFacts.currencies(1)) {
      rates.put(currency, FxMath.opening(reference).get(currency));
      settings.put(currency, FxMath.defaults(reference).get(currency));
    }
    if (cadRate != null) {
      rates.put(CAD, cadRate);
      settings.put(CAD, new FxMath.Settings(cadRate, 0, 0, 0));
    }
    var policy =
        new FxFacts.Policy(
            LocalDate.parse("2026-11-03"), request.operationId().value(), reference, settings);
    var publication =
        new FxFacts.Publication(
            policy.effectiveDay(), reference, request.operationId().value(), policy.id(), rates);
    var engine =
        new JournalEngine(
            store,
            (actor, kind) -> true,
            Map.of(
                FxService.OPEN,
                new JournalEngine.Registration(
                    1,
                    (ignored, reads) ->
                        new JournalEngine.Commit(
                            List.of(policy.record(), publication.record()),
                            new Journal.Result(1, "FX_OPENED", publication.encode()),
                            tx -> {}))));
    assertEquals(Journal.Status.COMMITTED, engine.submit(request).status());
    return publication;
  }

  private static Journal.Outcome primary(SqliteStorage store, PartyRef owner, PlayerId player) {
    var canonical =
        new Journal.CanonicalRequest(1, Map.of("economy:owner", Journal.actorKey(player)));
    var request =
        new Journal.Intent(
            new OperationId(UUID.randomUUID()),
            new RequestId(UUID.randomUUID()),
            player,
            AccountCatalog.INITIALIZE_PRIMARY,
            canonical,
            null,
            null);
    var engine =
        new JournalEngine(
            store,
            (actor, kind) -> actor.equals(player),
            Map.of(
                AccountCatalog.INITIALIZE_PRIMARY,
                ca.emilybelnavis.mcmods.economy.support.LegacyAccounts.initialization(
                    owner, AccountIdentity.Purpose.BANK)));
    return engine.submit(request);
  }

  private static Journal.Outcome secondary(SqliteStorage store, PartyRef owner, PlayerId player) {
    var open = new AccountLifecycle.Open(CHF, 1);
    var fields = new HashMap<>(open.request().fields());
    fields.put("economy:owner", Journal.actorKey(player));
    var request =
        new Journal.Intent(
            new OperationId(UUID.randomUUID()),
            new RequestId(UUID.randomUUID()),
            player,
            AccountLifecycle.OPEN,
            new Journal.CanonicalRequest(1, fields),
            null,
            null);
    var engine =
        new JournalEngine(
            store,
            (actor, kind) -> actor.equals(player),
            Map.of(
                AccountLifecycle.OPEN,
                ca.emilybelnavis.mcmods.economy.support.LegacyAccounts.opening(owner, open)));
    return engine.submit(request);
  }

  private static void missingRebaseRateRejectsWithoutEffects(
      SqliteStorage store, PartyRef owner, PlayerId player) {
    var world = store.snapshot().worldId();
    var session = UUID.randomUUID();
    var confirmations = new DenominationChange(world, session);
    var proof =
        confirmations.confirm(confirmations.first(player, CHF, 0, true, 0), player, 0, true, 1);
    AccountRebasePlanner.ClaimsReader clear =
        (reads, id) -> Optional.of(new AccountRebasePlanner.Claims(false, false, false));
    var plan =
        store
            .transaction(
                tx ->
                    AccountRebasePlanner.plan(
                        tx::query,
                        world,
                        session,
                        player,
                        proof,
                        RebaseSettlement.denomination(tx::query).orElseThrow(),
                        clear))
            .plan();
    assertNotNull(plan);
    UUID publication = latest(store).updateId();
    var request =
        new Journal.Intent(
            new OperationId(UUID.randomUUID()),
            new RequestId(UUID.randomUUID()),
            player,
            RebaseSettlement.KIND,
            RebaseSettlement.request(plan, publication),
            null,
            null);
    var engine =
        new JournalEngine(
            store,
            (actor, kind) -> actor.equals(player),
            Map.of(
                RebaseSettlement.KIND,
                RebaseSettlement.registration(
                    world, session, proof, plan, publication, (actor, reads) -> true, clear)));
    long before = store.lastCommittedSequence();
    var result = engine.submit(request);
    assertEquals(Journal.Status.DURABLY_REJECTED, result.status());
    assertEquals("REBASE_MISSING_RATE", result.result().code());
    assertEquals(before, store.lastCommittedSequence());
    assertEquals(
        EUR,
        store.transaction(tx -> AccountCatalog.primary(tx::query, owner).orElseThrow()).currency());
  }

  @Test
  void historicalVersionOneHasFrozenTenCurrencyBytesAndExplicitMissingChf() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    String payload =
        "2026-11-03|CAD|"
            + id
            + "|"
            + id
            + "|1.000000000000|1.558200000000|1.869900000000|0.981300000000|0.786500000000"
            + "|1.396000000000|0.177900000000|0.008870000000|0.209800000000|0.208816222930";
    var legacy = FxFacts.Publication.decode(payload, 1);
    assertEquals(10, legacy.rates().size());
    assertFalse(legacy.rates().containsKey(CHF));
    assertEquals(payload, legacy.encode());
    RecordCatalog.core().validate(FxFacts.PUBLICATION, 1, payload);
    assertThrows(
        StorageException.class,
        () -> RecordCatalog.core().validate(FxFacts.PUBLICATION, 2, payload));
    var missing =
        assertThrows(
            CurrencyConversion.Rejected.class,
            () ->
                CurrencyConversion.withdrawalDebit(
                    new Money(CHF, 100), CAD, legacy.conversionRates()));
    assertEquals(CurrencyConversion.Failure.MISSING_RATE, missing.failure());
    var quotes = new FxQuotes(new WorldId(UUID.randomUUID()));
    for (var pair : List.of(new FxQuotes.Pair(CHF, CAD), new FxQuotes.Pair(CAD, CHF))) {
      var error =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  quotes.issue(
                      new PlayerId(UUID.randomUUID()),
                      pair,
                      FxQuotes.Side.EXACT_DEBIT,
                      new Money(pair.source(), 100),
                      legacy,
                      Instant.EPOCH,
                      Duration.ofSeconds(60)));
      assertEquals("FX_RATE_UNAVAILABLE", error.getMessage());
    }
  }

  @Test
  void legacyWorldActivatesOnlyAtFutureBoundaryFromSameDayCadAndSurvivesRestart() throws Exception {
    var wall = new Wall();
    var store = new SqliteStorage();
    store.initialize(temporary.toRealPath());
    FxFacts.Publication original;
    List<Map<String, String>> prefix;
    var player = new PlayerId(UUID.randomUUID());
    var owner = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(player));
    try {
      original = legacy(store, EUR, new BigDecimal("0.750000000000"));
      assertEquals("ACCOUNT_READY", primary(store, owner, player).result().code());
      assertEquals("CURRENCY_UNAVAILABLE", secondary(store, owner, player).result().code());
      missingRebaseRateRejectsWithoutEffects(store, owner, player);
      var clock = clock(store, wall);
      wall.now = Instant.parse("2026-11-05T12:00:00Z");
      assertTrue(clock.runBatch(16).ready());
      assertFalse(
          latest(store).rates().containsKey(CHF), "Legacy backlog must not invent CHF history");
      prefix =
          store.transaction(
              tx ->
                  tx.query(
                      "SELECT record_type,record_version,payload FROM journal_records ORDER BY sequence,position",
                      List.of(),
                      32));
      FxService.prepareChfActivation(store, clock, wall);
      FxService.prepareChfActivation(store, clock, wall);
      assertEquals(
          LocalDate.parse("2026-11-06"),
          store.transaction(tx -> FxService.chfActivationDay(tx::query)));
      assertEquals(10, store.transaction(tx -> FxService.availableCurrencies(tx::query)).size());
      assertFalse(store.transaction(tx -> FxDiagnostics.read(tx, wall.instant())).chfAvailable());
    } finally {
      store.close();
    }
    store = new SqliteStorage();
    store.initialize(temporary.toRealPath());
    try {
      var clock = clock(store, wall);
      wall.now = Instant.parse("2026-11-05T23:59:59Z");
      assertTrue(clock.runBatch(1).ready());
      assertFalse(latest(store).rates().containsKey(CHF));
      wall.now = Instant.parse("2026-11-06T00:00:00Z");
      assertTrue(clock.runBatch(1).ready());
      var opening = latest(store);
      assertEquals(2, opening.version());
      assertEquals(new BigDecimal("1.200000000000"), opening.rates().get(CHF));
      assertEquals(
          opening
              .rates()
              .get(CAD)
              .multiply(new BigDecimal("1.6"))
              .setScale(12, RoundingMode.HALF_EVEN),
          opening.rates().get(CHF));
      assertEquals(EUR, opening.reference());
      assertEquals(11, store.transaction(tx -> FxService.availableCurrencies(tx::query)).size());
      assertEquals("BANK_OPENED", secondary(store, owner, player).result().code());
      assertEquals(
          EUR,
          store
              .transaction(tx -> AccountCatalog.primary(tx::query, owner).orElseThrow())
              .currency());
      assertEquals(
          original, store.transaction(tx -> FxService.publication(tx::query, original.day())));
      var worldId = store.snapshot().worldId();
      var history =
          store.transaction(
              tx -> FxPairHistory.page(tx::query, worldId, new FxQuotes.Pair(CAD, CHF), null, 10));
      assertFalse(history.days().getFirst().missing());
      assertTrue(
          history.days().stream()
              .skip(1)
              .allMatch(day -> day.missing() && day.currencyUnavailable()));
      assertTrue(history.unexpectedMissingDays().isEmpty());
      assertEquals(
          prefix,
          store
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT record_type,record_version,payload FROM journal_records ORDER BY sequence,position",
                          List.of(),
                          32))
              .subList(0, prefix.size()));
      long sequence = store.lastCommittedSequence();
      clock.runBatch(1);
      assertEquals(sequence, store.lastCommittedSequence());
      var quote =
          new FxQuotes(store.snapshot().worldId())
              .issue(
                  new PlayerId(UUID.randomUUID()),
                  new FxQuotes.Pair(CHF, CAD),
                  FxQuotes.Side.EXACT_DEBIT,
                  new Money(CHF, 100),
                  opening,
                  wall.instant(),
                  Duration.ofSeconds(60));
      assertEquals(new Money(CAD, 160), quote.credit());
    } finally {
      store.close();
    }
  }

  @Test
  void freshOpeningIncludesGameSeedAndMissingPreviousPublicationBlocksActivation()
      throws Exception {
    var wall = new Wall();
    var store = new SqliteStorage();
    store.initialize(temporary.toRealPath());
    try {
      var update =
          new EconomicClock.Update(
              UUID.randomUUID(),
              FxService.SYSTEM,
              0,
              Instant.parse("2026-11-04T00:00:00Z").toEpochMilli(),
              2,
              1);
      assertThrows(
          StorageException.class,
          () -> store.transaction(tx -> FxService.deferred().prepare().read(update, tx::query)));
    } finally {
      store.close();
    }
    // A failed schema read can poison its connection, so fresh opening uses a distinct world.
    Path fresh = java.nio.file.Files.createDirectory(temporary.toRealPath().resolve("fresh"));
    var current = new SqliteStorage();
    current.initialize(fresh);
    try {
      var engine =
          new JournalEngine(
              current, (actor, kind) -> true, Map.of(FxService.OPEN, FxService.opening(wall)));
      var request = intent(FxService.OPEN, Map.of("currency", "CAD"));
      assertEquals(Journal.Status.COMMITTED, engine.submit(request).status());
      var publication = latest(current);
      assertEquals(2, publication.version());
      assertEquals(new BigDecimal("1.600000000000"), publication.rates().get(CHF));
      assertEquals(
          LocalDate.parse("2026-11-03"),
          current.transaction(tx -> FxService.chfActivationDay(tx::query)));
      assertEquals(
          LocalDate.parse("2026-11-03"),
          current.transaction(tx -> FxDiagnostics.read(tx, wall.instant())).chfScheduledDay());
      var clock = clock(current, wall);
      assertEquals(
          "2",
          current
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT version FROM economic_versions WHERE system_id=? ORDER BY effective_end_ms",
                          List.of(FxService.SYSTEM),
                          1))
              .getFirst()
              .get("version"));
      wall.now = Instant.parse("2026-11-04T00:00:00Z");
      assertTrue(clock.runBatch(1).ready());
      assertEquals(11, latest(current).rates().size());
    } finally {
      current.close();
    }
  }

  @Test
  void freshChfWorldInitializesItsProtectedPrimaryInChf() throws Exception {
    var store = new SqliteStorage();
    store.initialize(temporary.toRealPath());
    try {
      var wall = new Wall();
      var engine =
          new JournalEngine(
              store, (actor, kind) -> true, Map.of(FxService.OPEN, FxService.opening(wall)));
      assertEquals(
          Journal.Status.COMMITTED,
          engine.submit(intent(FxService.OPEN, Map.of("currency", "CHF"))).status());
      assertEquals(CHF, latest(store).reference());
      assertEquals(new BigDecimal("1.000000000000"), latest(store).rates().get(CHF));
      var player = new PlayerId(UUID.randomUUID());
      var owner = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(player));
      assertEquals("ACCOUNT_READY", primary(store, owner, player).result().code());
      var primary = store.transaction(tx -> AccountCatalog.primary(tx::query, owner).orElseThrow());
      assertEquals(CHF, primary.currency());
      assertTrue(primary.primary());
    } finally {
      store.close();
    }
  }
}
