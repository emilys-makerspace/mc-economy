package ca.emilybelnavis.mcmods.economy.recovery;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual runtime merge/gating, rather than testing two schedulers in isolation. */
class FinancialSchedulingTest {
  @TempDir Path temporary;

  private static DeadlineDispatcher.Provider provider(String id, long deadline, int stage) {
    return new DeadlineDispatcher.Provider() {
      public String id() {
        return id;
      }

      public Map<Integer, String> versions() {
        return Map.of(1, "fixture-v1");
      }

      public DeadlineDispatcher.Event next(
          EconomicClock.Queries reads, long origin, DeadlineDispatcher.Event completed) {
        return completed == null
            ? new DeadlineDispatcher.Event(
                UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                id,
                Math.addExact(origin, deadline),
                stage,
                1)
            : null;
      }

      public List<String> targets(
          DeadlineDispatcher.Event event, String cursor, int limit, EconomicClock.Queries reads) {
        return List.of("a", "b").stream()
            .filter(key -> key.compareTo(cursor) > 0)
            .limit(limit)
            .toList();
      }

      public EconomicClock.Output prepare(
          DeadlineDispatcher.Event event, String target, EconomicClock.Queries reads) {
        return new EconomicClock.Output(
            id + ":" + target,
            tx ->
                tx.execute(
                    "INSERT INTO fixture_effects(value) VALUES(?)", List.of(id + ":" + target)));
      }
    };
  }

  @Test
  void chronologicalMergeStagesReadinessAndOneChildPerTurn() throws Exception {
    var wall = new EconomicClockTest.MutableClock(0);
    var version =
        new EconomicClock.Version(
            1,
            "fixed-v1",
            update ->
                new EconomicClock.Output(
                    "fixed",
                    tx ->
                        tx.execute(
                            "INSERT INTO fixture_effects(value) VALUES('fixed')", List.of())));
    var fixed =
        new EconomicClock.SystemDefinition(
            "fixture:fixed", 1000, 0, 1, Map.of(1, version), Set.of());
    var providers =
        List.of(
            provider("fixture:payment", 1000, 3),
            provider("fixture:early", 777, 1),
            provider("fixture:accrual", 1000, 1));
    var config =
        new FoundationRuntime.Configuration(
            RecordCatalog.core(),
            List.of(),
            List.of(fixed),
            wall,
            Map.of(),
            true,
            providers,
            new FinancialCalendar.Config(2));
    var seed = new ca.emilybelnavis.mcmods.economy.storage.SqliteStorage();
    seed.initialize(temporary.toRealPath());
    seed.close();
    try (var connection =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + temporary.toRealPath().resolve("economy/economy.sqlite"));
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE fixture_effects(sequence INTEGER PRIMARY KEY,value TEXT NOT NULL)");
    }
    var runtime = new FoundationRuntime(config);
    runtime.initialize(temporary.toRealPath());
    try {
      for (int turn = 0; !runtime.startupStep().ready(); turn++) assertTrue(turn < 100);
      wall.now = 1000;
      assertEquals(
          StorageException.Kind.ECONOMIC_CATCH_UP,
          assertThrows(StorageException.class, runtime::requireEconomicCurrent).kind());
      int count = 0;
      boolean ready = false;
      for (int turn = 0; turn < 30; turn++) {
        var step = runtime.prepareEconomicStep();
        var progress =
            step.calculation() == null
                ? step.progress()
                : step.commit().apply(step.calculation().get());
        int after =
            runtime
                .transaction(
                    tx ->
                        tx.query(
                            "SELECT value FROM fixture_effects ORDER BY sequence", List.of(), 20))
                .size();
        assertTrue(after - count <= 1, "one child per worker turn");
        count = after;
        if (progress.ready()) {
          ready = true;
          break;
        }
      }
      assertTrue(ready);
      assertDoesNotThrow(runtime::requireEconomicCurrent);
      var effects =
          runtime.transaction(
              tx -> tx.query("SELECT value FROM fixture_effects ORDER BY sequence", List.of(), 20));
      assertEquals(
          List.of(
              "fixture:early:a",
              "fixture:early:b",
              "fixed",
              "fixture:accrual:a",
              "fixture:accrual:b",
              "fixture:payment:a",
              "fixture:payment:b"),
          effects.stream().map(row -> row.get("value")).toList());
      assertEquals(1, runtime.clock().currentProgress().completedSequence());
    } finally {
      runtime.close();
    }
  }

  @Test
  void productionActivatesOriginOnceWithoutInstallingFutureProducts() throws Exception {
    var wall = new EconomicClockTest.MutableClock(12345);
    var config = FoundationRuntime.Configuration.production(wall);
    String origin = null;
    for (int restart = 0; restart < 2; restart++) {
      var runtime = new FoundationRuntime(config);
      runtime.initialize(temporary.toRealPath());
      try {
        for (int turn = 0; !runtime.startupStep().ready(); turn++) assertTrue(turn < 100);
        assertTrue(runtime.schedulesEconomy());
        assertDoesNotThrow(runtime::requireEconomicCurrent);
        var records =
            runtime.transaction(
                tx ->
                    tx.query(
                        "SELECT payload FROM journal_records WHERE record_type='economy:a6/calendar-activation'",
                        List.of(),
                        2));
        assertEquals(1, records.size());
        if (origin == null) origin = records.getFirst().get("payload");
        else assertEquals(origin, records.getFirst().get("payload"));
      } finally {
        runtime.close();
      }
      wall.now += 100000;
    }
  }

  @Test
  void calendarOriginSurvivesSealedBackupAndSeparateRestore() throws Exception {
    Path world = java.nio.file.Files.createDirectory(temporary.toRealPath().resolve("world"));
    java.nio.file.Files.writeString(world.resolve("level.dat"), "disposable saved fixture");
    var wall = new EconomicClockTest.MutableClock(12345);
    var config = FoundationRuntime.Configuration.production(wall);
    var runtime = new FoundationRuntime(config);
    runtime.initialize(world);
    for (int turn = 0; !runtime.startupStep().ready(); turn++) assertTrue(turn < 100);
    runtime.close();
    runtime.afterMinecraftStopped();
    Path backup = ca.emilybelnavis.mcmods.economy.storage.WorldBackup.create(world, config);
    Path destination = temporary.toRealPath().resolve("restored");
    ca.emilybelnavis.mcmods.economy.storage.WorldBackup.restore(backup, destination, config);
    wall.now = 999999;
    var restored = new FoundationRuntime(config);
    restored.initialize(destination);
    try {
      for (int turn = 0; !restored.startupStep().ready(); turn++) assertTrue(turn < 100);
      assertEquals(
          "12345",
          restored
              .transaction(tx -> tx.query("SELECT origin_ms FROM financial_calendar", List.of(), 1))
              .getFirst()
              .get("origin_ms"));
    } finally {
      restored.close();
    }
  }
}
