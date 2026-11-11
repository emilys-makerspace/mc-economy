package ca.emilybelnavis.mcmods.economy.recovery;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CalendarDiagnosticsTest {
  @TempDir Path temporary;

  private static final class Wall extends Clock {
    long now;

    Wall(long now) {
      this.now = now;
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return Instant.ofEpochMilli(now);
    }

    public long millis() {
      return now;
    }
  }

  private SqliteStorage open() throws Exception {
    var store = new SqliteStorage();
    store.initialize(temporary.toRealPath());
    return store;
  }

  private static FoundationStore.Transaction readOnly(FoundationStore.Transaction tx) {
    return new FoundationStore.Transaction() {
      public int execute(String sql, List<String> parameters) {
        throw new AssertionError("Diagnostics attempted a write");
      }

      public List<Map<String, String>> query(String sql, List<String> parameters, int maximum) {
        assertTrue(sql.startsWith("SELECT "));
        assertTrue(maximum <= 64);
        return tx.query(sql, parameters, maximum);
      }
    };
  }

  @Test
  void inactiveInspectionNeverActivatesCalendar() throws Exception {
    var store = open();
    try {
      var snapshot =
          store.transaction(
              tx ->
                  CalendarDiagnostics.read(
                      readOnly(tx),
                      Instant.ofEpochMilli(123),
                      reads -> {
                        throw new AssertionError("Inactive provider was inspected");
                      }));
      assertFalse(snapshot.active());
      assertNull(snapshot.originMillis());
      assertNull(snapshot.position());
      assertEquals(List.of(), snapshot.providers());
      assertEquals(0, store.lastCommittedSequence());
      assertTrue(
          store
              .transaction(tx -> tx.query("SELECT * FROM financial_calendar", List.of(), 1))
              .isEmpty());
    } finally {
      store.close();
    }
  }

  @Test
  void exactWorldBoundariesAndLargeDebtAreReadFromOneUnchangedTransaction() throws Exception {
    var store = open();
    var wall = new Wall(123);
    try {
      new DeadlineDispatcher(store, wall, List.of(), 16).prepare();
      var before =
          store.transaction(tx -> tx.query("SELECT * FROM financial_calendar", List.of(), 1));
      long sequence = store.lastCommittedSequence();
      long now = 123 + 13 * FinancialCalendar.MONTH_MILLIS + 2 * FinancialCalendar.DAY_MILLIS;
      var event = new DeadlineDispatcher.Event(UUID.randomUUID(), "fixture:due", 124, 2, 1);
      var callbacks = new AtomicInteger();
      var snapshot =
          store.transaction(
              tx ->
                  CalendarDiagnostics.read(
                      readOnly(tx),
                      Instant.ofEpochMilli(now),
                      reads -> {
                        callbacks.incrementAndGet();
                        assertEquals(
                            "123",
                            reads
                                .query("SELECT origin_ms FROM financial_calendar", List.of(), 1)
                                .getFirst()
                                .get("origin_ms"));
                        return event;
                      }));
      assertEquals(1, callbacks.get());
      assertEquals(123L, snapshot.originMillis());
      assertEquals(123L, snapshot.observedMillis());
      assertEquals(now, snapshot.targetMillis());
      assertEquals(366, snapshot.position().completedDays());
      assertEquals(13, snapshot.position().completedMonths());
      assertEquals(1, snapshot.position().completedYears());
      assertEquals(123 + 367 * FinancialCalendar.DAY_MILLIS, snapshot.position().nextDayMillis());
      assertEquals(
          123 + 14 * FinancialCalendar.MONTH_MILLIS, snapshot.position().nextMonthMillis());
      assertEquals(123 + 2 * FinancialCalendar.YEAR_MILLIS, snapshot.position().nextYearMillis());
      assertEquals(1, snapshot.domainPendingWitness());
      assertEquals(event, snapshot.nextEvent());
      assertEquals(
          before,
          store.transaction(tx -> tx.query("SELECT * FROM financial_calendar", List.of(), 1)));
      assertEquals(sequence, store.lastCommittedSequence());
    } finally {
      store.close();
    }
  }

  @Test
  void policyAndExclusiveCursorAgreeWithPersistedSchedulerWithoutAdvancingIt() throws Exception {
    var store = open();
    var wall = new Wall(123);
    var event = new DeadlineDispatcher.Event(UUID.randomUUID(), "fixture:due", 200, 2, 1);
    var provider =
        new DeadlineDispatcher.Provider() {
          public String id() {
            return event.provider();
          }

          public Map<Integer, String> versions() {
            return Map.of(1, "fixture-v1", 2, "fixture-v2");
          }

          public DeadlineDispatcher.Event next(
              EconomicClock.Queries reads, long origin, DeadlineDispatcher.Event completed) {
            return completed == null ? event : null;
          }

          public List<String> targets(
              DeadlineDispatcher.Event due, String cursor, int limit, EconomicClock.Queries reads) {
            return List.of("a", "b").stream()
                .filter(target -> target.compareTo(cursor) > 0)
                .limit(limit)
                .toList();
          }

          public EconomicClock.Output prepare(
              DeadlineDispatcher.Event due, String target, EconomicClock.Queries reads) {
            return new EconomicClock.Output(target, tx -> {});
          }
        };
    try {
      var scheduler = new DeadlineDispatcher(store, wall, List.of(provider), 16);
      scheduler.prepare();
      scheduler.scheduleVersion(provider.id(), 2, 300);
      wall.now = 200;
      scheduler.advance();
      long sequence = store.lastCommittedSequence();
      var before =
          store.transaction(
              tx -> tx.query("SELECT * FROM financial_deadline_events", List.of(), 1));
      var snapshot =
          store.transaction(
              tx -> CalendarDiagnostics.read(readOnly(tx), wall.instant(), reads -> event));
      var state = snapshot.providers().getFirst();
      assertEquals(1, state.effectiveVersion());
      assertEquals(
          List.of(
              new CalendarDiagnostics.PolicyVersion(123, 1),
              new CalendarDiagnostics.PolicyVersion(300, 2)),
          state.versions());
      assertEquals(new CalendarDiagnostics.PolicyVersion(300, 2), state.nextPolicy());
      assertEquals("a", state.activeEvent().exclusiveCursor());
      assertEquals(1, state.activeEvent().completedChildren());
      assertEquals(1, snapshot.completedChildren());
      assertEquals(
          before,
          store.transaction(
              tx -> tx.query("SELECT * FROM financial_deadline_events", List.of(), 1)));
      assertEquals(sequence, store.lastCommittedSequence());
      wall.now = 124;
      var rewind =
          store.transaction(
              tx -> CalendarDiagnostics.read(readOnly(tx), wall.instant(), reads -> event));
      assertTrue(rewind.waitingForWall());
      assertEquals(200L, rewind.observedMillis());
      assertEquals(1, rewind.domainPendingWitness());
    } finally {
      store.close();
    }
  }

  @Test
  void fixedBacklogUsesExactRetainedTargetWithoutObservingNewTime() throws Exception {
    var store = open();
    var wall = new Wall(0);
    try {
      var version =
          new EconomicClock.Version(
              1, "fixture-v1", update -> new EconomicClock.Output("", tx -> {}));
      var definition =
          new EconomicClock.SystemDefinition(
              "fixture:fixed", 1000, 0, 1, Map.of(1, version), Set.of());
      var clock = new EconomicClock(store, wall, List.of(definition));
      clock.prepare();
      wall.now = 1_000_000_000_000L;
      var snapshot =
          store.transaction(tx -> CalendarDiagnostics.read(readOnly(tx), wall.instant(), null));
      assertFalse(snapshot.active());
      assertEquals(1_000_000_000L, snapshot.fixedBacklog());
      var fixed = snapshot.fixedSystems().getFirst();
      assertEquals(0, fixed.observedMillis());
      assertEquals(1000L, fixed.nextMillis());
      assertEquals(1, fixed.nextVersion());
      assertEquals(
          "0",
          store
              .transaction(
                  tx -> tx.query("SELECT observed_wall_ms FROM economic_metadata", List.of(), 1))
              .getFirst()
              .get("observed_wall_ms"));
      assertEquals(0, store.lastCommittedSequence());
    } finally {
      store.close();
    }
  }

  @Test
  void overflowAndRegistryBoundsAreExplicit() throws Exception {
    var store = open();
    var wall = new Wall(Long.MAX_VALUE - 100);
    try {
      new DeadlineDispatcher(store, wall, List.of(), 16).prepare();
      var snapshot =
          store.transaction(
              tx ->
                  CalendarDiagnostics.read(
                      readOnly(tx), Instant.ofEpochMilli(Long.MAX_VALUE), null));
      assertNull(snapshot.position().nextDayMillis());
      assertNull(snapshot.position().nextMonthMillis());
      assertNull(snapshot.position().nextYearMillis());
    } finally {
      store.close();
    }
    FoundationStore.Transaction tooMany =
        new FoundationStore.Transaction() {
          public int execute(String sql, List<String> parameters) {
            throw new AssertionError();
          }

          public List<Map<String, String>> query(String sql, List<String> parameters, int maximum) {
            if (sql.contains("FROM financial_calendar"))
              return List.of(
                  Map.of(
                      "version",
                      "1",
                      "origin_ms",
                      "0",
                      "observed_wall_ms",
                      "0",
                      "last_effective_ms",
                      "0",
                      "completed_effects",
                      "0"));
            if (sql.contains("FROM financial_deadline_providers"))
              return Collections.nCopies(65, Map.of("provider", "fixture:many"));
            return List.of();
          }
        };
    assertThrows(
        StorageException.class, () -> CalendarDiagnostics.read(tooMany, Instant.EPOCH, null));
  }
}
