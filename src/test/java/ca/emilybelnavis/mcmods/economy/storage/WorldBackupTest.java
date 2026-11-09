package ca.emilybelnavis.mcmods.economy.storage;

import static ca.emilybelnavis.mcmods.economy.recovery.RecoveryFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.recovery.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class WorldBackupTest {
  @TempDir Path temporary;
  Path root, world;
  FoundationRuntime.Configuration configuration;

  @BeforeEach
  void setup() throws Exception {
    root = temporary.toRealPath();
    world = Files.createDirectory(root.resolve("world"));
    create(world, 3);
    Files.writeString(world.resolve("level.dat"), "world-save");
    Files.createDirectory(world.resolve("region"));
    Files.writeString(world.resolve("region/test.mca"), "chunk-save");
    configuration =
        new FoundationRuntime.Configuration(
            FixtureRecords.catalog(),
            List.of(projection(1)),
            List.of(),
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    cleanStop(world);
  }

  private void cleanStop(Path world) throws Exception {
    var runtime = new FoundationRuntime(configuration);
    runtime.initialize(world);
    boolean ready = false;
    for (int i = 0; i < 100; i++)
      if (runtime.startupStep().ready()) {
        ready = true;
        break;
      }
    assertTrue(ready);
    runtime.close();
    runtime.afterMinecraftStopped();
  }

  @Test
  void completeWorldRestoresSameIdentityCommitAndCheckpointWithoutLaterActions() throws Exception {
    Path backup = WorldBackup.create(world, configuration);
    var manifest = UpgradeBackup.load(backup.resolve("foundation-manifest.properties"));
    assertEquals("true", manifest.getProperty("clean-shutdown"));
    assertEquals("3", manifest.getProperty("last-journal-sequence"));
    var original = open(world);
    journal(original).submit(intent());
    assertEquals(4, authority(original));
    original.close();
    Path destination = root.resolve("restored");
    var result = WorldBackup.restore(backup, destination, configuration);
    assertEquals(3, result.lastJournal());
    assertEquals(manifest.getProperty("world-id"), result.worldId());
    assertEquals("world-save", Files.readString(destination.resolve("level.dat")));
    assertEquals("chunk-save", Files.readString(destination.resolve("region/test.mca")));
    var restored = open(destination);
    assertEquals(3, authority(restored));
    assertEquals(result.worldId(), restored.snapshot().worldId().value().toString());
    restored.close();
    original = open(world);
    assertEquals(4, authority(original));
    original.close();
    cleanStop(destination);
    assertTrue(
        Files.exists(
            WorldBackup.create(destination, configuration)
                .resolve("foundation-manifest.properties")));
  }

  @Test
  void corruptionIncompatibleManifestAndExistingDestinationRejectWithoutReplacement()
      throws Exception {
    Path backup = WorldBackup.create(world, configuration);
    Path existing = Files.createDirectory(root.resolve("current"));
    Files.writeString(existing.resolve("keep.txt"), "preserve");
    assertThrows(
        java.io.IOException.class, () -> WorldBackup.restore(backup, existing, configuration));
    assertEquals("preserve", Files.readString(existing.resolve("keep.txt")));
    Files.writeString(backup.resolve("world/region/test.mca"), "corrupt");
    Path rejected = root.resolve("rejected");
    assertThrows(
        java.io.IOException.class, () -> WorldBackup.restore(backup, rejected, configuration));
    assertFalse(Files.exists(rejected));
    Path incompatible = WorldBackup.create(world, configuration);
    var metadata = UpgradeBackup.load(incompatible.resolve("foundation-manifest.properties"));
    metadata.setProperty("schema-version", "999");
    StorageFiles.replace(
        incompatible.resolve("foundation-manifest.properties"), UpgradeBackup.properties(metadata));
    var receipt = UpgradeBackup.load(incompatible.resolve("receipt.properties"));
    receipt.setProperty(
        "foundation-manifest-hash",
        StorageFiles.hash(incompatible.resolve("foundation-manifest.properties")));
    StorageFiles.replace(
        incompatible.resolve("receipt.properties"), UpgradeBackup.properties(receipt));
    assertThrows(
        java.io.IOException.class,
        () -> WorldBackup.restore(incompatible, rejected, configuration));
    assertFalse(Files.exists(rejected));
  }

  @Test
  void activeOrUncertifiedWorldCannotBeCalledCleanBackup() throws Exception {
    var runtime = new FoundationRuntime(configuration);
    runtime.initialize(world);
    assertThrows(Exception.class, () -> WorldBackup.create(world, configuration));
    for (int i = 0; i < 100 && !runtime.startupStep().ready(); i++) {}
    runtime.close();
    // A JDBC close without the Minecraft post-save witness is insufficient.
    assertThrows(java.io.IOException.class, () -> WorldBackup.create(world, configuration));
    runtime.afterMinecraftStopped();
    try (var lock = WorldOwnership.acquire(world.resolve("session.lock"))) {
      assertThrows(StorageException.class, () -> WorldBackup.create(world, configuration));
    }
    Files.setLastModifiedTime(
        world.resolve("session.lock"),
        java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10000));
    assertThrows(java.io.IOException.class, () -> WorldBackup.create(world, configuration));
  }
}
