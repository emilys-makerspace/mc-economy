package ca.emilybelnavis.mcmods.economy.recovery;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeadlineDispatcherTest {
  @TempDir Path temporary;

  private static final class Wall extends Clock {
    long millis;

    Wall(long value) {
      millis = value;
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return Instant.ofEpochMilli(millis);
    }

    public long millis() {
      return millis;
    }
  }

  private static class Provider implements DeadlineDispatcher.Provider {
    final List<Long> times;
    boolean fail;

    Provider(Long... times) {
      this.times = List.of(times);
    }

    public String id() {
      return "fixture:deadlines";
    }

    public Map<Integer, String> versions() {
      return Map.of(1, "fixture-v1", 2, "fixture-v2");
    }

    public DeadlineDispatcher.Event next(
        EconomicClock.Queries reads, long origin, DeadlineDispatcher.Event completed) {
      return times.stream()
          .filter(t -> completed == null || t > completed.effectiveMillis())
          .findFirst()
          .map(
              time ->
                  new DeadlineDispatcher.Event(
                      UUID.nameUUIDFromBytes(
                          (id() + time).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                      id(),
                      time,
                      2,
                      DeadlineDispatcher.scheduledVersion(reads, id(), time)))
          .orElse(null);
    }

    public List<String> targets(
        DeadlineDispatcher.Event event, String cursor, int limit, EconomicClock.Queries reads) {
      return List.of("a", "b", "c").stream()
          .filter(target -> target.compareTo(cursor) > 0)
          .limit(limit)
          .toList();
    }

    public EconomicClock.Output prepare(
        DeadlineDispatcher.Event event, String target, EconomicClock.Queries reads) {
      return new EconomicClock.Output(
          target,
          tx -> {
            tx.execute(
                "INSERT INTO fixture_effects VALUES(?,?,?)",
                List.of(event.id().toString(), target, Integer.toString(event.version())));
            if (fail) throw new IllegalStateException("fixture rollback");
          });
    }
  }

  private SqliteStorage open() throws Exception {
    return open(temporary);
  }

  private SqliteStorage open(Path world) throws Exception {
    java.nio.file.Files.createDirectories(world);
    var store = new SqliteStorage();
    store.initialize(world.toRealPath());
    store.close();
    try (var connection =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + world.toRealPath().resolve("economy/economy.sqlite"));
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE IF NOT EXISTS fixture_effects(event TEXT,target TEXT,version INTEGER,PRIMARY KEY(event,target))");
    }
    store = new SqliteStorage();
    store.initialize(world.toRealPath());
    return store;
  }

  private static long count(SqliteStorage store) {
    return store.transaction(
        tx ->
            Long.parseLong(
                tx.query("SELECT count(*) AS n FROM fixture_effects", List.of(), 1)
                    .getFirst()
                    .get("n")));
  }

  @Test
  void eachTurnCommitsOneChildAndRestartRetainsOriginAndExclusiveCursor() throws Exception {
    var wall = new Wall(123);
    var provider = new Provider(456L, 789L);
    var store = open();
    var scheduler = new DeadlineDispatcher(store, wall, List.of(provider), 2);
    scheduler.prepare();
    wall.millis = 1000;
    scheduler.advance();
    assertEquals(1, count(store));
    assertFalse(scheduler.progress().ready());
    store.close();
    store = open();
    scheduler = new DeadlineDispatcher(store, wall, List.of(provider), 2);
    scheduler.prepare();
    assertEquals(
        "123",
        store.transaction(
            tx ->
                tx.query("SELECT origin_ms FROM financial_calendar", List.of(), 1)
                    .getFirst()
                    .get("origin_ms")));
    for (int i = 0; i < 20 && !scheduler.progress().ready(); i++) scheduler.advance();
    assertTrue(scheduler.progress().ready());
    assertEquals(6, count(store));
    assertEquals(6, scheduler.progress().completedEffects());
    assertEquals(
        "2",
        store.transaction(
            tx ->
                tx.query(
                        "SELECT count(*) AS n FROM financial_deadline_events WHERE state='COMPLETE'",
                        List.of(),
                        1)
                    .getFirst()
                    .get("n")));
    store.close();
  }

  @Test
  void childEffectsAndCursorRollbackTogether() throws Exception {
    var wall = new Wall(0);
    var provider = new Provider(17L);
    provider.fail = true;
    var store = open();
    var scheduler = new DeadlineDispatcher(store, wall, List.of(provider), 16);
    scheduler.prepare();
    wall.millis = 20;
    assertThrows(IllegalStateException.class, scheduler::advance);
    assertEquals(0, count(store));
    assertEquals(0, scheduler.progress().completedEffects());
    provider.fail = false;
    scheduler.advance();
    assertEquals(1, count(store));
    assertEquals(1, scheduler.progress().completedEffects());
    store.close();
  }

  @Test
  void backwardUtcRetainsDebtAndFuturePolicyDoesNotRewriteBacklog() throws Exception {
    var wall = new Wall(0);
    var provider = new Provider(15L, 30L);
    var store = open();
    var scheduler = new DeadlineDispatcher(store, wall, List.of(provider), 16);
    scheduler.prepare();
    scheduler.scheduleVersion(provider.id(), 2, 25);
    assertThrows(
        IllegalArgumentException.class, () -> scheduler.scheduleVersion(provider.id(), 2, 0));
    wall.millis = 40;
    scheduler.observeCurrentDebt();
    wall.millis = 1;
    assertTrue(scheduler.progress().waitingForWall());
    scheduler.advance();
    assertEquals(0, count(store));
    wall.millis = 40;
    for (int i = 0; i < 20 && !scheduler.progress().ready(); i++) scheduler.advance();
    assertEquals(
        List.of("1", "1", "1", "2", "2", "2"),
        store
            .transaction(
                tx -> tx.query("SELECT version FROM fixture_effects ORDER BY rowid", List.of(), 16))
            .stream()
            .map(row -> row.get("version"))
            .toList());
    wall.millis = 1;
    assertTrue(scheduler.progress().waitingForWall());
    assertFalse(scheduler.progress().ready());
    store.close();
  }

  @Test
  void missingInitialAndUnjournaledFuturePoliciesFailClosed() throws Exception {
    var wall = new Wall(7);
    var provider = new Provider(15L);
    for (int corruption = 0; corruption < 3; corruption++) {
      for (boolean readOnlyGate : new boolean[] {false, true}) {
        var store = open(temporary.resolve("corruption-" + corruption + "-" + readOnlyGate));
        try {
          var scheduler = new DeadlineDispatcher(store, wall, List.of(provider), 16);
          scheduler.prepare();
          final int selected = corruption;
          store.transaction(
              tx -> {
                if (selected == 0)
                  tx.execute(
                      "DELETE FROM financial_deadline_versions WHERE provider=?",
                      List.of(provider.id()));
                else if (selected == 1)
                  tx.execute(
                      "UPDATE financial_deadline_versions SET effective_ms=8 WHERE provider=?",
                      List.of(provider.id()));
                else
                  tx.execute(
                      "INSERT INTO financial_deadline_versions VALUES(?,?,2)",
                      List.of(provider.id(), "20"));
                return null;
              });
          if (readOnlyGate)
            assertThrows(
                StorageException.class,
                () ->
                    store.transaction(
                        tx -> {
                          DeadlineDispatcher.validateProviders(tx::query, List.of(provider));
                          return null;
                        }));
          else assertThrows(StorageException.class, scheduler::prepare);
        } finally {
          store.close();
        }
      }
    }
  }

  @Test
  void committedChildWithLostResponseResolvesOriginalOutcomeWithoutRepeatingEffects()
      throws Exception {
    var wall = new Wall(0);
    var provider = new Provider(15L);
    var actual = open();
    try {
      boolean[] armed = {false};
      int[] recoveries = {0};
      FoundationStore lostResponse =
          new FoundationStore() {
            public void initialize(Path world) {
              actual.initialize(world);
            }

            public Snapshot snapshot() {
              return actual.snapshot();
            }

            public long lastCommittedSequence() {
              return actual.lastCommittedSequence();
            }

            public RecordCatalog recordCatalog() {
              return actual.recordCatalog();
            }

            public <T> T transaction(Work<T> work) {
              long before = armed[0] ? count(actual) : -1;
              T result = actual.transaction(work);
              if (armed[0] && count(actual) > before) {
                armed[0] = false;
                throw new StorageException(
                    StorageException.Kind.UNKNOWN_COMMIT, "fixture lost committed child response");
              }
              return result;
            }

            public void recover() {
              recoveries[0]++;
              actual.recover();
            }

            public void close() throws Exception {
              actual.close();
            }
          };
      var scheduler = new DeadlineDispatcher(lostResponse, wall, List.of(provider), 16);
      scheduler.prepare();
      wall.millis = 20;
      armed[0] = true;
      scheduler.advance();
      assertEquals(1, recoveries[0]);
      assertFalse(armed[0]);
      assertEquals(1, count(actual));
      assertEquals(1, scheduler.progress().completedEffects());
      assertEquals(
          "a",
          actual.transaction(
              tx ->
                  tx.query(
                          "SELECT cursor FROM financial_deadline_events WHERE state='APPLYING'",
                          List.of(),
                          1)
                      .getFirst()
                      .get("cursor")));
      assertEquals(
          "1",
          actual.transaction(
              tx ->
                  tx.query(
                          "SELECT count(*) AS n FROM journal_records WHERE record_type=?",
                          List.of(DeadlineDispatcher.PROGRESS_RECORD),
                          1)
                      .getFirst()
                      .get("n")));
      for (int i = 0; i < 10 && !scheduler.progress().ready(); i++) scheduler.advance();
      assertTrue(scheduler.progress().ready());
      assertEquals(3, count(actual));
      assertEquals(3, scheduler.progress().completedEffects());
      assertEquals(1, recoveries[0]);
    } finally {
      actual.close();
    }
  }

  @Test
  void changedOrMissingHistoricalProviderFailsClosedAndInvalidBoundsReject() throws Exception {
    var wall = new Wall(0);
    var provider = new Provider(15L);
    var store = open();
    var scheduler = new DeadlineDispatcher(store, wall, List.of(provider), 16);
    scheduler.prepare();
    assertThrows(
        StorageException.class, () -> new DeadlineDispatcher(store, wall, List.of(), 16).prepare());
    var changed =
        new Provider(15L) {
          public Map<Integer, String> versions() {
            return Map.of(1, "different");
          }
        };
    assertThrows(
        StorageException.class,
        () -> new DeadlineDispatcher(store, wall, List.of(changed), 16).prepare());
    assertThrows(
        IllegalArgumentException.class,
        () -> new DeadlineDispatcher(store, wall, List.of(provider), 17));
    store.close();
  }
}
