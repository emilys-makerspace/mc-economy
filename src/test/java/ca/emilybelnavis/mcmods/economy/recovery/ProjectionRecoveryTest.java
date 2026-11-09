package ca.emilybelnavis.mcmods.economy.recovery;

import static ca.emilybelnavis.mcmods.economy.recovery.RecoveryFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ProjectionRecoveryTest {
  @TempDir Path temporary;
  Path world;

  @BeforeEach
  void setup() throws Exception {
    world = temporary.toRealPath();
    create(world, 5);
  }

  @Test
  void resumeUsesCommittedCheckpointAndDoesNotPublishHalfBuild() throws Exception {
    var store = open(world);
    var firstManager = new ProjectionManager(store, List.of(projection(1)));
    assertEquals(1, firstManager.step(1).applied());
    assertThrows(IllegalStateException.class, () -> firstManager.snapshot("fixture:count"));
    String generation =
        store
            .transaction(
                tx ->
                    tx.query(
                        "SELECT generation FROM projection_generations WHERE state='BUILDING'",
                        List.of(),
                        1))
            .getFirst()
            .get("generation");
    store.close();
    store = open(world);
    var manager = new ProjectionManager(store, List.of(projection(1)));
    finish(manager, 2);
    var snapshot = manager.snapshot("fixture:count");
    assertEquals(generation, snapshot.generation());
    assertEquals("5", snapshot.values().get("count"));
    assertEquals(authority(store), Long.parseLong(snapshot.values().get("count")));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.values().put("count", "9"));
    assertTrue(manager.step(1).ready());
    store.close();
  }

  @Test
  void replacementGenerationActivationIsAtomicAndCorruptionRebuilds() throws Exception {
    var store = open(world);
    var old = new ProjectionManager(store, List.of(projection(1)));
    finish(old, 2);
    String first = old.snapshot("fixture:count").generation();
    var replacement = new ProjectionManager(store, List.of(projection(2)));
    replacement.step(1);
    assertEquals(first, old.snapshot("fixture:count").generation());
    assertThrows(IllegalStateException.class, () -> replacement.snapshot("fixture:count"));
    finish(replacement, 1);
    assertNotEquals(first, replacement.snapshot("fixture:count").generation());
    String active = replacement.snapshot("fixture:count").generation();
    store.transaction(
        tx -> {
          tx.execute("UPDATE projection_cells SET value='999' WHERE generation=?", List.of(active));
          return null;
        });
    assertThrows(IllegalStateException.class, () -> replacement.snapshot("fixture:count"));
    finish(replacement, 2);
    assertEquals("5", replacement.snapshot("fixture:count").values().get("count"));
    store.close();
  }

  @Test
  void unknownCommittedRecordFailsBeforeUpgradeOrRebuild() throws Exception {
    try (var c =
            DriverManager.getConnection("jdbc:sqlite:" + world.resolve("economy/economy.sqlite"));
        var s = c.createStatement()) {
      s.execute("DELETE FROM migration_history WHERE version>=4");
      s.execute("UPDATE foundation_metadata SET schema_version=3");
      s.execute("PRAGMA user_version=3");
    }
    var error = assertThrows(StorageException.class, () -> new SqliteStorage().initialize(world));
    assertEquals(StorageException.Kind.SCHEMA, error.kind());
    assertTrue(error.getMessage().contains("Unknown committed record"));
    try (var c =
            DriverManager.getConnection("jdbc:sqlite:" + world.resolve("economy/economy.sqlite"));
        var s = c.createStatement();
        var r = s.executeQuery("PRAGMA user_version")) {
      assertTrue(r.next());
      assertEquals(3, r.getInt(1));
    }
  }
}
