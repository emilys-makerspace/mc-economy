package ca.emilybelnavis.mcmods.economy.recovery;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class EconomicClockTest {
  @TempDir Path temporary;

  static final class MutableClock extends Clock {
    long now;

    MutableClock(long now) {
      this.now = now;
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      if (!zone.equals(ZoneOffset.UTC)) throw new IllegalArgumentException();
      return this;
    }

    public Instant instant() {
      return Instant.ofEpochMilli(now);
    }

    public long millis() {
      return now;
    }
  }

  static EconomicClock.SystemDefinition definition(
      String id, Set<String> dependencies, AtomicInteger calls) {
    var v1 =
        new EconomicClock.Version(
            1,
            "fixture-v1",
            update -> {
              calls.incrementAndGet();
              return new EconomicClock.Output(
                  "v1:" + new SplittableRandom(update.randomSeed()).nextLong(), tx -> {});
            });
    var v2 =
        new EconomicClock.Version(
            2,
            "fixture-v2",
            update -> {
              calls.incrementAndGet();
              return new EconomicClock.Output(
                  "v2:" + new SplittableRandom(update.randomSeed()).nextLong(), tx -> {});
            });
    return new EconomicClock.SystemDefinition(id, 1000, 0, 1, Map.of(1, v1, 2, v2), dependencies);
  }

  static SqliteStorage open(Path path) {
    var store = new SqliteStorage();
    store.initialize(path);
    return store;
  }

  static List<Map<String, String>> updates(SqliteStorage store) {
    return store.transaction(
        tx ->
            tx.query(
                "SELECT update_id,economic_sequence,system_id,interval_end_ms,version,random_seed,output FROM economic_updates ORDER BY economic_sequence",
                List.of(),
                1024));
  }

  private void copy(Path source, Path destination) throws Exception {
    try (var paths = Files.walk(source)) {
      for (Path path : paths.toList()) {
        Path target = destination.resolve(source.relativize(path));
        if (Files.isDirectory(path)) Files.createDirectories(target);
        else Files.copy(path, target);
      }
    }
  }

  @Test
  void batchesAndRestartCannotRerollOrChangeChronologicalDependencyOrder() throws Exception {
    Path root = temporary.toRealPath(), base = Files.createDirectory(root.resolve("base"));
    var initial = open(base);
    initial.close();
    List<List<Map<String, String>>> runs = new ArrayList<>();
    for (int batch : new int[] {1, 16}) {
      Path world = root.resolve("run" + batch);
      copy(base, world);
      var store = open(world);
      var wall = new MutableClock(5000);
      var calls = new AtomicInteger();
      var definitions =
          List.of(
              definition("fixture:b", Set.of("fixture:a"), calls),
              definition("fixture:a", Set.of(), calls));
      var clock = new EconomicClock(store, wall, definitions);
      clock.prepare();
      clock.runBatch(batch);
      store.close();
      store = open(world);
      clock = new EconomicClock(store, wall, definitions);
      clock.prepare();
      for (int i = 0; i < 20 && !clock.runBatch(batch).ready(); i++) {}
      var history = updates(store);
      assertEquals(10, history.size());
      assertEquals(10, calls.get());
      assertEquals(
          List.of("fixture:a", "fixture:b"),
          history.subList(0, 2).stream().map(r -> r.get("system_id")).toList());
      wall.now = 100;
      clock.runBatch(16);
      assertEquals(history, updates(store));
      assertEquals(10, calls.get());
      runs.add(history);
      store.close();
    }
    assertEquals(runs.getFirst(), runs.getLast());
  }

  @Test
  void explicitVersionBoundaryKeepsOverdueHistoryAndForwardDebt() throws Exception {
    Path world = temporary.toRealPath();
    var store = open(world);
    var wall = new MutableClock(2500);
    var calls = new AtomicInteger();
    var definition = definition("fixture:system", Set.of(), calls);
    var clock = new EconomicClock(store, wall, List.of(definition));
    clock.prepare();
    clock.runBatch(1);
    clock.scheduleVersion(definition.id(), 2, 4000);
    assertThrows(
        IllegalArgumentException.class, () -> clock.scheduleVersion(definition.id(), 2, 2000));
    wall.now = 5000;
    while (!clock.runBatch(2).ready()) {}
    assertEquals(
        List.of("1", "1", "1", "2", "2"),
        updates(store).stream().map(r -> r.get("version")).toList());
    wall.now = 20000;
    var forward = clock.runBatch(2);
    assertTrue(forward.outstandingIntervals() > 0);
    assertFalse(forward.ready());
    int committed = updates(store).size();
    wall.now = 5000;
    var backward = clock.runBatch(2);
    assertTrue(backward.waitingForWall());
    assertFalse(backward.ready());
    assertEquals(committed, updates(store).size());
    wall.now = 20000;
    while (!clock.runBatch(3).ready()) {}
    assertEquals(20, updates(store).size());
    assertEquals(20, calls.get());
    var onlyCurrent =
        new EconomicClock.SystemDefinition(
            definition.id(), 1000, 0, 2, Map.of(2, definition.versions().get(2)), Set.of());
    var incompatible = new EconomicClock(store, wall, List.of(onlyCurrent));
    assertThrows(StorageException.class, incompatible::prepare);
    store.close();
  }

  @Test
  void ongoingCatchUpCalculatesOffStorageAndCommitsInOrder() throws Exception {
    var wall = new MutableClock(0);
    var calls = new AtomicInteger();
    var runtime =
        new FoundationRuntime(
            new FoundationRuntime.Configuration(
                RecordCatalog.core(),
                List.of(),
                List.of(
                    definition("fixture:b", Set.of("fixture:a"), calls),
                    definition("fixture:a", Set.of(), calls)),
                wall));
    try (var calculation = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      runtime.initialize(temporary.toRealPath());
      try {
        while (!runtime.startupStep().ready()) {}
        wall.now = 1000;
        assertEquals(
            StorageException.Kind.ECONOMIC_CATCH_UP,
            assertThrows(StorageException.class, runtime::requireEconomicCurrent).kind());

        for (long completed = 0; completed < 2; completed++) {
          var prepared = runtime.prepareEconomicStep();
          assertNotNull(prepared.calculation());
          assertEquals(completed, prepared.progress().completedSequence());
          assertEquals(2 - completed, prepared.progress().outstandingIntervals());
          assertEquals(completed, calls.get());
          var calculated =
              calculation
                  .submit(prepared.calculation()::get)
                  .get(5, java.util.concurrent.TimeUnit.SECONDS);
          assertEquals(completed + 1, calls.get());
          assertEquals(completed, runtime.clock().currentProgress().completedSequence());
          assertEquals(completed + 1, prepared.commit().apply(calculated).completedSequence());

          if (completed == 0) {
            wall.now = 500;
            var waiting = runtime.prepareEconomicStep();
            assertNull(waiting.calculation());
            assertTrue(waiting.progress().waitingForWall());
            assertFalse(waiting.progress().ready());
            assertEquals(1, waiting.progress().outstandingIntervals());
            assertEquals(1, calls.get());
            wall.now = 1000;
          }
        }

        assertTrue(runtime.prepareEconomicStep().progress().ready());
        assertDoesNotThrow(runtime::requireEconomicCurrent);
        var systems =
            runtime.transaction(
                tx ->
                    tx.query(
                        "SELECT system_id FROM economic_updates ORDER BY economic_sequence",
                        List.of(),
                        2));
        assertEquals(
            List.of("fixture:a", "fixture:b"),
            systems.stream().map(row -> row.get("system_id")).toList());
      } finally {
        runtime.close();
      }
    }
  }

  @Test
  void completionAndEffectsRollbackTogether() throws Exception {
    Path world = temporary.toRealPath();
    var store = open(world);
    var calls = new AtomicInteger();
    var version =
        new EconomicClock.Version(
            1,
            "fixture-fail",
            update -> {
              calls.incrementAndGet();
              return new EconomicClock.Output(
                  "decision",
                  tx -> {
                    tx.execute(
                        "UPDATE economic_systems SET frontier_ms=frontier_ms+cadence_ms,last_sequence=999",
                        List.of());
                    throw new IllegalStateException("fixture interruption");
                  });
            });
    var clock =
        new EconomicClock(
            store,
            new MutableClock(1000),
            List.of(
                new EconomicClock.SystemDefinition(
                    "fixture:failure", 1000, 0, 1, Map.of(1, version), Set.of())));
    clock.prepare();
    String before =
        store
            .transaction(tx -> tx.query("SELECT seed FROM economic_metadata", List.of(), 1))
            .getFirst()
            .get("seed");
    assertThrows(IllegalStateException.class, () -> clock.runBatch(1));
    assertEquals(0, updates(store).size());
    assertEquals(
        before,
        store
            .transaction(tx -> tx.query("SELECT seed FROM economic_metadata", List.of(), 1))
            .getFirst()
            .get("seed"));
    assertEquals(
        "0",
        store
            .transaction(
                tx -> tx.query("SELECT count(*) AS count FROM journal_transactions", List.of(), 1))
            .getFirst()
            .get("count"));
    store.close();
  }
}
