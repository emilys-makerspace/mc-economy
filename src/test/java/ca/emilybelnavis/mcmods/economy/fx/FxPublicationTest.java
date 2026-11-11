package ca.emilybelnavis.mcmods.economy.fx;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.EconomicClock;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime;
import ca.emilybelnavis.mcmods.economy.storage.FailureHarness;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import ca.emilybelnavis.mcmods.economy.storage.WorldBackup;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FxPublicationTest {
  @TempDir Path directory;
  private final MutableClock wall = new MutableClock();
  private final SystemActorId selector = new SystemActorId(UUID.randomUUID());
  private final PlayerId admin = new PlayerId(UUID.randomUUID());

  @Test
  void activationPolicyCarryForwardAndRestart() throws Exception {
    Path world = Files.createDirectory(directory.toRealPath().resolve("world"));
    var configuration = FoundationRuntime.Configuration.production(wall);
    var opening = intent(selector, FxService.OPEN, Map.of("currency", "JPY"));
    FxFacts.Publication first;
    FxFacts.Publication next;
    var runtime = open(world, configuration);
    try (AutoCloseable close = runtime::close) {
      var clock = runtime.clock();
      wall.at("2030-01-01T12:00:00Z");
      assertEquals(0, clock.runBatch(16).outstandingIntervals());
      assertNull(latest(runtime));
      assertNull(runtime.readFxDiagnostics().reference());
      wall.at("2026-11-03T23:00:00Z");

      var journal = journal(runtime);
      var opened = journal.submit(opening);
      assertEquals(Journal.Status.COMMITTED, opened.status());
      first = latest(runtime);
      assertEquals(LocalDate.parse("2026-11-03"), first.day());
      assertEquals(new BigDecimal("1.000000000000"), first.rates().get(JPY));
      assertEquals(new BigDecimal("23.541851510000"), first.rates().get(DKK));
      assertEquals(0, clock.currentProgress().outstandingIntervals());
      assertEquals(0, clock.runBatch(16).completedSequence());

      assertEquals(
          "ACCESS_DENIED",
          journal.submit(intent(selector, FxService.EDIT, settings("CAD", "110"))).result().code());
      assertEquals(
          "FX_INVALID_POLICY",
          journal.submit(intent(admin, FxService.EDIT, settings("CAD", "0"))).result().code());
      var edit = intent(admin, FxService.EDIT, settings("CAD", "110"));
      assertEquals("2026-11-04", journal.submit(edit).result().payload());
      var replacement = intent(admin, FxService.EDIT, settings("CAD", "112"));
      assertEquals("2026-11-04", journal.submit(replacement).result().payload());
      var healthy = runtime.readFxDiagnostics();
      assertEquals(0, healthy.backlog());
      assertEquals(first.policyId(), healthy.effectivePolicy());
      assertEquals(replacement.operationId().value(), healthy.pending().id());
      long journalSequence = runtime.lastCommittedSequence();

      wall.at("2026-11-05T00:00:00Z");
      var overdue = runtime.readFxDiagnostics();
      assertEquals(2, overdue.backlog());
      assertFalse(overdue.waitingForWall());
      wall.at("2026-11-03T23:00:00Z");
      assertEquals(
          0, runtime.readFxDiagnostics().backlog()); // Inspection did not observe durable debt.
      assertEquals(journalSequence, runtime.lastCommittedSequence());
      assertEquals(first, latest(runtime));

      wall.at("2026-11-04T00:00:00Z");
      var staged = clock.prepareStep().work();
      assertNotNull(staged);
      var computed = staged.calculate();
      assertEquals(computed.payload(), staged.calculate().payload());
      assertEquals(1, clock.commit(staged, computed).completedSequence());
      next = latest(runtime);
      assertEquals(replacement.operationId().value(), next.policyId());
      assertEquals(
          first.rates().get(CAD), next.rates().get(CAD)); // zero draw and pull, within the new band
      assertEquals(first.rates().get(JPY), next.rates().get(JPY));
      assertNull(runtime.readFxDiagnostics().pending());
      wall.at("2026-11-03T23:59:59Z");
      assertTrue(runtime.readFxDiagnostics().waitingForWall());
      assertEquals(next, latest(runtime));
      wall.at("2026-11-04T00:00:00Z");
      assertEquals(opened, journal.submit(opening));
      assertEquals(
          "FX_ALREADY_OPEN",
          journal
              .submit(intent(selector, FxService.OPEN, Map.of("currency", "CAD")))
              .result()
              .code());
    }
    var reopened = open(world, configuration);
    try {
      assertEquals(next, latest(reopened));
      wall.at("2026-11-06T00:00:00Z");
      assertEquals(3, reopened.clock().runBatch(16).completedSequence());
      assertEquals(LocalDate.parse("2026-11-06"), latest(reopened).day());
      assertEquals(
          first, reopened.transaction(tx -> FxService.publication(tx::query, first.day())));
    } finally {
      reopened.close();
    }
    reopened.afterMinecraftStopped();
    Path backup = WorldBackup.create(world, configuration);
    assertEquals(
        3,
        WorldBackup.restore(backup, directory.toRealPath().resolve("restored"), configuration)
            .economicCheckpoint());
  }

  @Test
  void dailyPublicationRollsBackWithClockAndCanRetry() throws Exception {
    var harness = new FailureHarness();
    var store = harness.store;
    store.initialize(Files.createDirectory(directory.toRealPath().resolve("rollback")));
    try {
      var clock =
          new EconomicClock(
              store,
              wall,
              List.of(FxService.definition()),
              Map.of(FxService.SYSTEM, FxService.deferred()));
      clock.prepare();
      assertEquals(
          Journal.Status.COMMITTED,
          journal(store)
              .submit(intent(selector, FxService.OPEN, Map.of("currency", "CAD")))
              .status());
      var opening = latest(store);
      wall.at("2026-11-04T00:00:00Z");
      var staged = clock.prepareStep().work();
      var output = staged.calculate();
      harness.fault.set(FailureHarness.Fault.BEFORE_COMMIT);
      assertThrows(StorageException.class, () -> clock.commit(staged, output));
      assertEquals(opening, latest(store));
      assertEquals(0, clock.currentProgress().completedSequence());
      var retry = clock.prepareStep().work();
      assertEquals(output.payload(), retry.calculate().payload());
      harness.fault.set(FailureHarness.Fault.AFTER_COMMIT);
      assertEquals(1, clock.commit(retry, retry.calculate()).completedSequence());
      assertEquals(LocalDate.parse("2026-11-04"), latest(store).day());
      assertEquals(1, clock.runBatch(16).completedSequence());
    } finally {
      store.close();
    }
  }

  @Test
  void eurOpeningAcrossMidnightAndPreparedOutcomeSurvivesRestart() throws Exception {
    Path world = Files.createDirectory(directory.toRealPath().resolve("eur"));
    var configuration = FoundationRuntime.Configuration.production(wall);
    var selection = intent(selector, FxService.OPEN, Map.of("currency", "EUR"));
    var unopened = open(world, configuration);
    assertNull(latest(unopened));
    unopened.close();

    var runtime = open(world, configuration);
    String expected;
    try (AutoCloseable close = runtime::close) {
      Clock crossing =
          new MutableClock() {
            @Override
            public Instant instant() {
              wall.at("2026-11-04T00:00:00Z");
              return Instant.parse("2026-11-03T23:59:59Z");
            }
          };
      var opener =
          new JournalEngine(
              runtime,
              (actor, kind) -> actor.equals(selector),
              Map.of(FxService.OPEN, FxService.opening(crossing)));
      assertEquals(Journal.Status.COMMITTED, opener.submit(selection).status());
      assertEquals(LocalDate.parse("2026-11-03"), latest(runtime).day());
      assertEquals(EUR, latest(runtime).reference());
      assertEquals(1, runtime.clock().currentProgress().outstandingIntervals());
      expected = runtime.clock().prepareStep().work().calculate().payload();
    }
    var restarted = open(world, configuration);
    try (AutoCloseable close = restarted::close) {
      assertEquals(expected, latest(restarted).encode());
      long sequence = restarted.lastCommittedSequence();
      assertEquals(Journal.Status.COMMITTED, journal(restarted).submit(selection).status());
      assertEquals(1, restarted.clock().runBatch(1).completedSequence());
      assertEquals(sequence, restarted.lastCommittedSequence());
    }
  }

  @Test
  void continuousAndRestartedCatchUpPublishIdenticalHistory() throws Exception {
    Path world = Files.createDirectory(directory.toRealPath().resolve("continuous"));
    var configuration = FoundationRuntime.Configuration.production(wall);
    var continuous = open(world, configuration);
    assertEquals(
        Journal.Status.COMMITTED,
        journal(continuous)
            .submit(intent(selector, FxService.OPEN, Map.of("currency", "EUR")))
            .status());
    assertEquals(
        Journal.Status.COMMITTED,
        journal(continuous).submit(intent(admin, FxService.EDIT, settings("CAD", "1"))).status());
    continuous.close();
    continuous.afterMinecraftStopped();
    Path branch = directory.toRealPath().resolve("restarted");
    WorldBackup.restore(WorldBackup.create(world, configuration), branch, configuration);

    var live = open(world, configuration);
    var expected = new java.util.ArrayList<FxFacts.Publication>();
    try (AutoCloseable close = live::close) {
      wall.at("2026-11-07T00:00:00Z");
      for (int day = 1; day <= 4; day++) {
        var work = live.clock().prepareStep().work();
        var output = java.util.concurrent.CompletableFuture.supplyAsync(work::calculate);
        var before = live.readFxDiagnostics();
        assertEquals(5 - day, before.backlog());
        assertEquals(day, live.clock().commit(work, output.get()).completedSequence());
        var after = live.readFxDiagnostics();
        var publication = latest(live);
        assertEquals(before.lastDay().plusDays(1), after.lastDay());
        assertEquals(publication.policyId(), after.effectivePolicy());
        expected.add(publication);
      }
    }
    var restarted = open(branch, configuration);
    try (AutoCloseable close = restarted::close) {
      for (var publication : expected) {
        assertEquals(
            publication,
            restarted.transaction(tx -> FxService.publication(tx::query, publication.day())));
      }
      assertEquals(4, restarted.clock().runBatch(1).completedSequence());
    }
  }

  @Test
  void quotesExpireAndHistoryKeepsItsUpperDayAcrossPublication() throws Exception {
    var runtime = open(directory.toRealPath(), FoundationRuntime.Configuration.production(wall));
    try (AutoCloseable close = runtime::close) {
      journal(runtime).submit(intent(selector, FxService.OPEN, Map.of("currency", "CAD")));
      var world = runtime.snapshot().worldId();
      var pair = new FxQuotes.Pair(USD, CAD);
      var book = new FxQuotes(world);
      var opening = latest(runtime);
      Instant now = wall.instant();
      var quote =
          book.issue(
              admin,
              pair,
              FxQuotes.Side.EXACT_DEBIT,
              new ca.emilybelnavis.mcmods.economy.api.Money(USD, 2000),
              opening,
              now,
              Duration.ofSeconds(60));
      assertEquals(2792, quote.credit().minorUnits());
      assertEquals(quote, book.requireFresh(quote.id(), admin, world, opening, 1, now));
      assertThrows(
          IllegalArgumentException.class,
          () -> book.requireFresh(quote.id(), admin, world, opening, 1, now.plusSeconds(60)));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              book.requireFresh(
                  quote.id(), new PlayerId(UUID.randomUUID()), world, opening, 1, now));
      assertThrows(
          IllegalArgumentException.class,
          () -> new FxQuotes(world).requireFresh(quote.id(), admin, world, opening, 1, now));

      wall.at("2026-11-05T00:00:00Z");
      runtime.clock().runBatch(2);
      var latest = latest(runtime);
      assertThrows(
          IllegalArgumentException.class,
          () -> book.requireFresh(quote.id(), admin, world, latest, 1, now));
      var first = runtime.transaction(tx -> FxPairHistory.page(tx::query, world, pair, null, 1));
      wall.at("2026-11-06T00:00:00Z");
      runtime.clock().runBatch(1);
      var rest =
          runtime.transaction(tx -> FxPairHistory.page(tx::query, world, pair, first.next(), 100));
      assertEquals(
          List.of(opening.day().plusDays(1), opening.day()),
          rest.days().stream().map(FxPairHistory.Day::date).toList());
      assertNull(rest.next());
      assertTrue(
          runtime
              .transaction(tx -> FxPairHistory.day(tx::query, pair, opening.day().minusDays(1)))
              .missing());
      var inverse = new FxQuotes.Pair(CAD, USD);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              runtime.transaction(
                  tx -> FxPairHistory.page(tx::query, world, inverse, first.next(), 1)));
    }
  }

  @Test
  void fixtureConsumesQuoteAtomicallyAndResolvesOriginalOperationFirst() throws Exception {
    var harness = new FailureHarness();
    var store = harness.store;
    store.initialize(directory.toRealPath());
    try (AutoCloseable close = store::close) {
      journal(store).submit(intent(selector, FxService.OPEN, Map.of("currency", "CAD")));
      var world = store.snapshot().worldId();
      var book = new FxQuotes(world);
      var quote =
          book.issue(
              admin,
              new FxQuotes.Pair(USD, CAD),
              FxQuotes.Side.EXACT_DEBIT,
              new ca.emilybelnavis.mcmods.economy.api.Money(USD, 2000),
              latest(store),
              wall.instant(),
              Duration.ofSeconds(60));
      var kind = new Journal.Kind("fixture:trade", "fixture:post");
      var fields = Map.of("quote", quote.id().toString());
      var engine =
          new JournalEngine(
              store,
              (actor, operation) -> admin.equals(actor) && kind.equals(operation),
              Map.of(
                  kind,
                  new JournalEngine.Registration(
                      1,
                      (request, reads) -> {
                        String id = request.request().fields().get("quote");
                        if (!reads
                            .query(
                                "SELECT sequence FROM journal_records WHERE record_type='fixture:first' "
                                    + "AND payload=? LIMIT 1",
                                List.of(id),
                                1)
                            .isEmpty()) {
                          return new JournalEngine.Reject(
                              new Journal.Result(1, "QUOTE_CONSUMED", ""));
                        }
                        try {
                          book.requireFresh(
                              UUID.fromString(id),
                              admin,
                              world,
                              FxService.latest(reads),
                              1,
                              wall.instant());
                        } catch (IllegalArgumentException expired) {
                          return new JournalEngine.Reject(
                              new Journal.Result(1, "QUOTE_UNAVAILABLE", ""));
                        }
                        return new JournalEngine.Commit(
                            List.of(new Journal.Record("fixture:first", 1, id)),
                            new Journal.Result(1, "TRADE_FIXTURE", id),
                            tx -> {});
                      })));
      var operation = intent(admin, kind, fields);
      harness.fault.set(FailureHarness.Fault.BEFORE_COMMIT);
      assertEquals(Journal.Status.NOT_ACCEPTED, engine.submit(operation).status());
      var committed = engine.submit(operation);
      assertEquals(Journal.Status.COMMITTED, committed.status());
      assertEquals("QUOTE_CONSUMED", engine.submit(intent(admin, kind, fields)).result().code());
      wall.at("2026-11-04T00:00:00Z");
      assertEquals(committed, engine.submit(operation));
    }
  }

  private FoundationRuntime open(Path world, FoundationRuntime.Configuration configuration) {
    var runtime = new FoundationRuntime(configuration);
    runtime.initialize(world);
    for (int step = 0; step < 100; step++) if (runtime.startupStep().ready()) return runtime;
    throw new AssertionError("Runtime failed to reach readiness");
  }

  private JournalEngine journal(FoundationStore store) {
    return new JournalEngine(
        store,
        (actor, kind) ->
            actor.equals(selector) && kind.equals(FxService.OPEN)
                || actor.equals(admin) && kind.equals(FxService.EDIT),
        Map.of(FxService.OPEN, FxService.opening(wall), FxService.EDIT, FxService.editing(wall)));
  }

  private static FxFacts.Publication latest(FoundationStore store) {
    return store.transaction(tx -> FxService.latest(tx::query));
  }

  private static Map<String, String> settings(String currency, String baseline) {
    return Map.of(
        "currency",
        currency,
        "baseline",
        baseline,
        "variation",
        "0",
        "pull",
        "0",
        "bounds",
        "50000");
  }

  private static Journal.Intent intent(
      ActorId actor, Journal.Kind kind, Map<String, String> fields) {
    return new Journal.Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        actor,
        kind,
        new Journal.CanonicalRequest(1, fields),
        null,
        null);
  }

  private static class MutableClock extends Clock {
    private Instant instant = Instant.parse("2026-11-03T10:00:00Z");

    void at(String time) {
      instant = Instant.parse(time);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
