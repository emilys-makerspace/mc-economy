package ca.emilybelnavis.mcmods.economy.storage;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Narrow offline pre-upgrade backup utility; no online backup or restore operations. */
public final class UpgradeBackup {
  private UpgradeBackup() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 1)
      throw new IllegalArgumentException("Supply -PeconomyWorld=/absolute/stopped/world");
    System.out.println("Validated pre-upgrade world backup: " + prepare(Path.of(args[0])));
  }

  public static Path prepare(Path world) throws IOException {
    return prepare(
        world,
        false,
        ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime.Configuration.core());
  }

  static Path prepareApprovedDatabaseUpgrade(Path world) throws IOException {
    return prepare(
        world,
        false,
        ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime.Configuration.core(),
        true);
  }

  static Path prepare(
      Path world,
      boolean requireClean,
      ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime.Configuration configuration)
      throws IOException {
    return prepare(world, requireClean, configuration, false);
  }

  private static Path prepare(
      Path world,
      boolean requireClean,
      ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime.Configuration configuration,
      boolean allowLegacy)
      throws IOException {
    world = world.toAbsolutePath().normalize();
    StorageFiles.rejectLinks(world);
    if (!Files.isDirectory(world.resolve("economy"))
        || !Files.isRegularFile(world.resolve("economy/economy.sqlite")))
      throw new IOException("Existing redesigned economy database required");
    Path directory = world.resolve("economy");
    StorageFiles.rejectLinks(world.resolve("session.lock"));
    StorageFiles.rejectLinks(directory.resolve("owner.lock"));
    StorageFiles.rejectLinks(directory.resolve("upgrade-backup.properties"));
    // Minecraft's own lock establishes stopped state; economy lock excludes maintenance/other
    // owners.
    try (WorldOwnership minecraft = WorldOwnership.acquire(world.resolve("session.lock"));
        WorldOwnership economy = WorldOwnership.acquire(directory.resolve("owner.lock"))) {
      OfflineInspector.Metadata metadata;
      try {
        metadata = OfflineInspector.metadata(world);
      } catch (IOException invalid) {
        if (!allowLegacy) throw invalid;
        try {
          metadata = DatabaseUpgrade.legacyBackupMetadata(world);
        } catch (Exception legacy) {
          throw new IOException("Database is not a supported upgrade source", legacy);
        }
      }
      if (requireClean) {
        if (!metadata.databaseClean()) throw new IOException("Economy did not close cleanly");
        WorldShutdown.requireClean(world, metadata.evidence());
        OfflineInspector.validate(world, configuration);
      }
      String source = fingerprint(directory);
      Path backupRoot = world.resolveSibling(world.getFileName() + "-economy-upgrade-backups");
      StorageFiles.rejectLinks(backupRoot);
      Files.createDirectories(backupRoot);
      Path backup = Files.createDirectory(backupRoot.resolve(UUID.randomUUID().toString()));
      Path contents = Files.createDirectory(backup.resolve("world"));
      Properties manifest = new Properties();
      try (var paths = Files.walk(world)) {
        for (var iterator = paths.iterator(); iterator.hasNext(); ) {
          Path path = iterator.next();
          if (Thread.currentThread().isInterrupted())
            throw new java.io.InterruptedIOException("Backup interrupted");
          StorageFiles.rejectLinks(path);
          Path relative = world.relativize(path);
          if (excluded(relative)) continue;
          Path destination = contents.resolve(relative);
          if (Files.isDirectory(path)) Files.createDirectories(destination);
          else if (Files.isRegularFile(path)) {
            Files.copy(path, destination);
            try (var channel =
                java.nio.channels.FileChannel.open(destination, StandardOpenOption.WRITE)) {
              channel.force(true);
            }
            String hash = StorageFiles.hash(path);
            if (!hash.equals(StorageFiles.hash(destination)))
              throw new IOException("Backup copy mismatch: " + relative);
            manifest.setProperty(relative.toString(), hash);
          } else throw new IOException("Unsupported world file: " + path);
        }
      }
      try (var copied = Files.walk(contents)) {
        for (Path path :
            copied.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList())
          StorageFiles.forceDirectory(path);
      }
      StorageFiles.forceDirectory(backupRoot);
      if (!source.equals(fingerprint(directory)))
        throw new IOException("Storage changed during offline backup");
      StorageFiles.writeNew(backup.resolve("manifest.properties"), properties(manifest));
      Properties foundation = new Properties();
      foundation.setProperty(
          "format",
          metadata.evidence().schema() == 0
              ? "economy-legacy-backup-v1"
              : "economy-world-backup-v1");
      foundation.setProperty("world-id", metadata.evidence().worldId());
      foundation.setProperty("mod-version", "0.1.0");
      foundation.setProperty("schema-version", Integer.toString(metadata.evidence().schema()));
      foundation.setProperty("journal-version", metadata.evidence().schema() >= 3 ? "1" : "0");
      foundation.setProperty(
          "last-journal-sequence", Long.toString(metadata.evidence().lastJournal()));
      foundation.setProperty(
          "economic-checkpoint", Long.toString(metadata.evidence().economicSequence()));
      foundation.setProperty("clean-shutdown", Boolean.toString(requireClean));
      foundation.setProperty("backup-utc", Instant.now().toString());
      StorageFiles.writeNew(
          backup.resolve("foundation-manifest.properties"), properties(foundation));
      Properties receipt = new Properties();
      receipt.setProperty("format", "economy-upgrade-backup-v1");
      receipt.setProperty("backup", backup.toString());
      receipt.setProperty("storage-fingerprint", source);
      receipt.setProperty(
          "manifest-hash", StorageFiles.hash(backup.resolve("manifest.properties")));
      receipt.setProperty("created-at-utc", Instant.now().toString());
      receipt.setProperty(
          "foundation-manifest-hash",
          StorageFiles.hash(backup.resolve("foundation-manifest.properties")));
      StorageFiles.writeNew(backup.resolve("receipt.properties"), properties(receipt));
      validateBackup(backup, receipt);
      // Publish receipt last; an interrupted/incomplete backup can never authorize migration.
      StorageFiles.replace(directory.resolve("upgrade-backup.properties"), properties(receipt));
      return backup;
    }
  }

  private static boolean excluded(Path relative) {
    String name = relative.toString().replace('\\', '/');
    return name.equals("session.lock")
        || name.equals("economy/owner.lock")
        || name.equals("economy/upgrade-backup.properties")
        || name.equals("economy/economy.sqlite-shm");
  }

  static String fingerprint(Path directory) throws IOException {
    StringBuilder value = new StringBuilder();
    for (String name :
        List.of("economy.sqlite", "economy.sqlite-wal", "world-identity", "identity.pending")) {
      Path path = directory.resolve(name);
      StorageFiles.rejectLinks(path);
      value
          .append(name)
          .append(':')
          .append(Files.exists(path) ? StorageFiles.hash(path) : "absent")
          .append('\n');
    }
    return value.toString();
  }

  static void validate(Path directory, String source) {
    try {
      Path path = directory.resolve("upgrade-backup.properties");
      StorageFiles.rejectLinks(path);
      Properties receipt = load(path);
      if (!source.equals(receipt.getProperty("storage-fingerprint")))
        throw new IOException("Backup does not match pre-upgrade storage");
      Path backup = Path.of(receipt.getProperty("backup")).toAbsolutePath().normalize();
      if (backup.startsWith(directory.getParent()))
        throw new IOException("Backup must be outside world");
      validateBackup(backup, receipt);
    } catch (IOException | RuntimeException e) {
      throw new StorageException(
          StorageException.Kind.SCHEMA,
          "Upgrade requires a validated complete-world stopped-state backup. Stop Minecraft and run prepareEconomyUpgrade -PeconomyWorld=/absolute/world",
          e);
    }
  }

  static void validateBackup(Path backup, Properties receipt) throws IOException {
    StorageFiles.rejectLinks(backup);
    if (!"economy-upgrade-backup-v1".equals(receipt.getProperty("format")))
      throw new IOException("Backup format");
    Path manifestPath = backup.resolve("manifest.properties");
    StorageFiles.rejectLinks(manifestPath);
    if (!StorageFiles.hash(manifestPath).equals(receipt.getProperty("manifest-hash")))
      throw new IOException("Backup manifest changed");
    if (receipt.getProperty("foundation-manifest-hash") != null) {
      StorageFiles.rejectLinks(backup.resolve("foundation-manifest.properties"));
      if (!StorageFiles.hash(backup.resolve("foundation-manifest.properties"))
          .equals(receipt.getProperty("foundation-manifest-hash")))
        throw new IOException("Backup metadata manifest changed");
    }
    Properties manifest = load(manifestPath);
    if (!manifest.containsKey("economy/economy.sqlite"))
      throw new IOException("Backup lacks database");
    Path contents = backup.resolve("world");
    Set<String> actual = new HashSet<>();
    try (var paths = Files.walk(contents)) {
      for (var iterator = paths.iterator(); iterator.hasNext(); ) {
        Path path = iterator.next();
        if (Thread.currentThread().isInterrupted())
          throw new java.io.InterruptedIOException("Backup validation interrupted");
        StorageFiles.rejectLinks(path);
        if (Files.isRegularFile(path)) actual.add(contents.relativize(path).toString());
        if (actual.size() > manifest.size()) throw new IOException("Unexpected backup files");
      }
    }
    if (!actual.equals(manifest.stringPropertyNames()))
      throw new IOException("Incomplete or modified backup inventory");
    for (String name : manifest.stringPropertyNames()) {
      Path file = contents.resolve(name).normalize();
      if (!file.startsWith(contents) || !StorageFiles.hash(file).equals(manifest.getProperty(name)))
        throw new IOException("Backup file changed: " + name);
    }
    if (!fingerprint(contents.resolve("economy"))
        .equals(receipt.getProperty("storage-fingerprint")))
      throw new IOException("Backup storage fingerprint mismatch");
  }

  static Properties load(Path path) throws IOException {
    if (Files.size(path) > 16 * 1024 * 1024) throw new IOException("Metadata too large");
    Properties properties = new Properties();
    properties.load(new StringReader(Files.readString(path)));
    return properties;
  }

  static String properties(Properties properties) throws IOException {
    StringWriter text = new StringWriter();
    properties.store(text, null);
    return text.toString();
  }
}
