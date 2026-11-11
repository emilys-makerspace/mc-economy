package ca.emilybelnavis.mcmods.economy.storage;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.recovery.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldBackupClockTest {
  @TempDir Path temporary;

  static final class Wall extends Clock {
    long now = 2000;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return Instant.ofEpochMilli(now);
    }

    public long millis() {
      return now;
    }
  }

  private FoundationRuntime ready(Path world, FoundationRuntime.Configuration config) {
    var runtime = new FoundationRuntime(config);
    runtime.initialize(world);
    for (int i = 0; i < 100; i++) if (runtime.startupStep().ready()) return runtime;
    throw new AssertionError("No ready state");
  }

  @Test
  void restoredEconomicCheckpointCatchesUpWithoutRerollingCommittedPrefix() throws Exception {
    Path root = temporary.toRealPath(), world = Files.createDirectory(root.resolve("world"));
    Files.writeString(world.resolve("level.dat"), "saved world");
    var wall = new Wall();
    var version =
        new EconomicClock.Version(
            1,
            "restore-fixture-v1",
            update ->
                new EconomicClock.Output(
                    Long.toString(new SplittableRandom(update.randomSeed()).nextLong()), tx -> {}));
    var definition =
        new EconomicClock.SystemDefinition(
            "fixture:restore-clock", 1000, 0, 1, Map.of(1, version), Set.of());
    var config =
        new FoundationRuntime.Configuration(
            RecordCatalog.core(), List.of(), List.of(definition), wall);
    var original = ready(world, config);
    var prefix =
        original.transaction(
            tx ->
                tx.query(
                    "SELECT update_id,output FROM economic_updates ORDER BY economic_sequence",
                    List.of(),
                    10));
    assertEquals(2, prefix.size());
    original.close();
    original.afterMinecraftStopped();
    Path backup = WorldBackup.create(world, config), destination = root.resolve("restored");
    var restored = WorldBackup.restore(backup, destination, config);
    assertEquals(2, restored.economicCheckpoint());
    wall.now = 5000;
    var resumed = ready(destination, config);
    var history =
        resumed.transaction(
            tx ->
                tx.query(
                    "SELECT update_id,output FROM economic_updates ORDER BY economic_sequence",
                    List.of(),
                    10));
    assertEquals(5, history.size());
    assertEquals(prefix, history.subList(0, 2));
    resumed.close();
    resumed.afterMinecraftStopped();
    var source = new SqliteStorage();
    source.initialize(world);
    assertEquals(
        "2",
        source
            .transaction(
                tx -> tx.query("SELECT global_sequence FROM economic_metadata", List.of(), 1))
            .getFirst()
            .get("global_sequence"));
    source.close();
  }
}
