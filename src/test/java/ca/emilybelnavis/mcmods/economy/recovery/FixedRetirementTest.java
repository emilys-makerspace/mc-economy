package ca.emilybelnavis.mcmods.economy.recovery;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixedRetirementTest {
  @TempDir Path path;
  private static final long DAY = 86_400_000L,
      START = Instant.parse("2026-11-07T00:00:00Z").toEpochMilli();

  private static final class Wall extends Clock {
    long time = START;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return Instant.ofEpochMilli(time);
    }
  }

  private static final class Successor implements DeadlineDispatcher.Provider {
    final long takeover;
    final UUID event = UUID.randomUUID();
    boolean enabled = true;

    Successor(long takeover) {
      this.takeover = takeover;
    }

    public String id() {
      return FixedRetirements.MACRO_SUCCESSOR;
    }

    public Map<Integer, String> versions() {
      return Map.of(1, "retirement-fixture-successor-v1");
    }

    public DeadlineDispatcher.Event next(
        EconomicClock.Queries reads, long origin, DeadlineDispatcher.Event completed) {
      return completed == null && enabled
          ? new DeadlineDispatcher.Event(event, id(), takeover, 0, 1)
          : null;
    }

    public List<String> targets(
        DeadlineDispatcher.Event event, String cursor, int limit, EconomicClock.Queries reads) {
      return cursor.isEmpty() ? List.of("fixture-handover") : List.of();
    }

    public EconomicClock.Output prepare(
        DeadlineDispatcher.Event event, String target, EconomicClock.Queries reads) {
      return new EconomicClock.Output("fixture-authoritative-successor", tx -> {});
    }
  }

  private static EconomicClock clock(SqliteStorage store, Wall wall, Successor successor) {
    return new EconomicClock(
        store,
        wall,
        List.of(FxService.definition()),
        Map.of(FxService.SYSTEM, FxService.deferred()),
        DeadlineDispatcher.implementations(List.of(successor)));
  }

  private static void open(SqliteStorage store, Wall wall) {
    var id = UUID.randomUUID();
    var outcome =
        new JournalEngine(store, (a, k) -> true, Map.of(FxService.OPEN, FxService.opening(wall)))
            .submit(
                new Journal.Intent(
                    new OperationId(id),
                    new RequestId(id),
                    new SystemActorId(UUID.randomUUID()),
                    FxService.OPEN,
                    new Journal.CanonicalRequest(1, Map.of("currency", "CAD")),
                    null,
                    null));
    assertEquals(Journal.Status.COMMITTED, outcome.status());
  }

  @Test
  void retirementPreservesActualDailyCompletionAndStopsDebtWithoutFabricatingExcludedWork()
      throws Exception {
    var store = new SqliteStorage();
    var wall = new Wall();
    long takeover = START + DAY + 1_200_000L;
    var successor = new Successor(takeover);
    store.initialize(path.toRealPath());
    FixedRetirements.Receipt receipt;
    try {
      var domain = new DeadlineDispatcher(store, wall, List.of(successor), 16);
      domain.prepare();
      var clock = clock(store, wall, successor);
      clock.prepare();
      open(store, wall);
      wall.time = START + DAY;
      assertEquals(1, clock.runBatch(16).completedSequence());
      receipt = clock.retire(FxService.SYSTEM, takeover, domain, successor.id());
      assertEquals(START + 2 * DAY, receipt.firstExcludedMillis());
      assertNull(clock.nextDueMillis());
      assertEquals(
          START + DAY,
          store
              .transaction(
                  tx ->
                      Long.parseLong(
                          tx.query(
                                  "SELECT frontier_ms FROM economic_systems WHERE system_id=?",
                                  List.of(FxService.SYSTEM),
                                  1)
                              .getFirst()
                              .get("frontier_ms")))
              .longValue());
      assertThrows(
          IllegalArgumentException.class,
          () -> clock.scheduleVersion(FxService.SYSTEM, 2, START + 3 * DAY));
      wall.time = START + 4 * DAY;
      assertTrue(clock.runBatch(16).ready());
      assertEquals(1, clock.currentProgress().completedSequence());
      assertEquals(0, clock.currentProgress().outstandingIntervals());
      assertEquals(
          receipt,
          clock.retire(FxService.SYSTEM, takeover, domain, successor.id()),
          "Original receipt precedes new time/provider admission");
      assertEquals(
          2,
          store
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT payload FROM journal_records WHERE record_type=?",
                          List.of(FxFacts.PUBLICATION),
                          16))
              .size(),
          "Opening and one real legacy publication only");
      successor.enabled = false;
      assertThrows(RuntimeException.class, domain::progress);
      successor.enabled = true;
    } finally {
      store.close();
    }
    var reopened = new SqliteStorage();
    reopened.initialize(path.toRealPath());
    try {
      assertEquals(
          receipt, reopened.transaction(tx -> FixedRetirements.read(tx::query, FxService.SYSTEM)));
      var valid = clock(reopened, wall, successor);
      valid.prepare();
      assertTrue(valid.currentProgress().ready());
      assertNull(valid.nextDueMillis());
      var resumed = new DeadlineDispatcher(reopened, wall, List.of(successor), 16);
      resumed.prepare();
      for (int i = 0; i < 4 && !resumed.progress().ready(); i++) resumed.advance();
      assertTrue(resumed.progress().ready());
    } finally {
      reopened.close();
    }
  }

  @Test
  void retirementCannotEraseDueOrPreviouslyPreparedWorkAcrossClockRewind() throws Exception {
    var store = new SqliteStorage();
    var wall = new Wall();
    var successor = new Successor(START + DAY / 2);
    store.initialize(path.toRealPath());
    try {
      var domain = new DeadlineDispatcher(store, wall, List.of(successor), 16);
      domain.prepare();
      var clock = clock(store, wall, successor);
      clock.prepare();
      open(store, wall);
      wall.time = START + DAY;
      var staged = clock.prepareStep().work();
      assertNotNull(staged);
      var output = staged.calculate();
      assertThrows(
          IllegalArgumentException.class,
          () -> clock.retire(FxService.SYSTEM, successor.takeover, domain, successor.id()));
      wall.time = START;
      assertThrows(
          IllegalArgumentException.class,
          () -> clock.retire(FxService.SYSTEM, successor.takeover, domain, successor.id()));
      assertNull(store.transaction(tx -> FixedRetirements.read(tx::query, FxService.SYSTEM)));
      wall.time = START + DAY;
      assertEquals(1, clock.commit(staged, output).completedSequence());
      assertEquals(START + 2 * DAY, clock.nextDueMillis().longValue());
    } finally {
      store.close();
    }
  }
}
