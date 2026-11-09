package ca.emilybelnavis.mcmods.economy.storage;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** A Minecraft-save completion witness, separate from the earlier JDBC close. */
public final class WorldShutdown {
  private WorldShutdown() {}

  public record Evidence(String worldId, int schema, long lastJournal, long economicSequence) {}

  public static void active(Path world) throws IOException {
    Path path = world.resolve("economy/world-clean.properties");
    StorageFiles.rejectLinks(path);
    Path session = world.resolve("session.lock");
    StorageFiles.rejectLinks(session);
    if (!Files.exists(session)) Files.createFile(session);
    Properties state = new Properties();
    state.setProperty("status", "ACTIVE");
    state.setProperty("session-stamp", Files.getLastModifiedTime(session).toString());
    state.setProperty("session", UUID.randomUUID().toString());
    StorageFiles.replace(path, UpgradeBackup.properties(state));
  }

  public static void complete(Path world, Evidence expected) throws IOException {
    try (var minecraft = WorldOwnership.acquire(world.resolve("session.lock"));
        var ownership = WorldOwnership.acquire(world.resolve("economy/owner.lock"))) {
      var active = UpgradeBackup.load(world.resolve("economy/world-clean.properties"));
      if (!"ACTIVE".equals(active.getProperty("status"))
          || !Files.getLastModifiedTime(world.resolve("session.lock"))
              .toString()
              .equals(active.getProperty("session-stamp")))
        throw new IOException("Minecraft session changed before clean witness");
      var actual = OfflineInspector.metadata(world);
      if (!expected.equals(actual.evidence()) || !actual.databaseClean())
        throw new IOException("Financial close evidence changed");
      writeClean(world, expected);
    }
  }

  static void writeClean(Path world, Evidence evidence) throws IOException {
    Path session = world.resolve("session.lock");
    StorageFiles.rejectLinks(session);
    if (!Files.exists(session)) Files.createFile(session);
    Properties state = new Properties();
    state.setProperty("status", "CLEAN");
    state.setProperty("world-id", evidence.worldId());
    state.setProperty("schema", Integer.toString(evidence.schema()));
    state.setProperty("last-journal", Long.toString(evidence.lastJournal()));
    state.setProperty("economic-sequence", Long.toString(evidence.economicSequence()));
    state.setProperty("session-stamp", Files.getLastModifiedTime(session).toString());
    state.setProperty("storage-fingerprint", UpgradeBackup.fingerprint(world.resolve("economy")));
    state.setProperty("saved-at-utc", Instant.now().toString());
    StorageFiles.replace(
        world.resolve("economy/world-clean.properties"), UpgradeBackup.properties(state));
  }

  static void requireClean(Path world, Evidence evidence) throws IOException {
    Path marker = world.resolve("economy/world-clean.properties");
    StorageFiles.rejectLinks(marker);
    Properties state = UpgradeBackup.load(marker);
    if (!"CLEAN".equals(state.getProperty("status"))
        || !evidence.worldId().equals(state.getProperty("world-id"))
        || !Integer.toString(evidence.schema()).equals(state.getProperty("schema"))
        || !Long.toString(evidence.lastJournal()).equals(state.getProperty("last-journal"))
        || !Long.toString(evidence.economicSequence())
            .equals(state.getProperty("economic-sequence"))
        || !Files.getLastModifiedTime(world.resolve("session.lock"))
            .toString()
            .equals(state.getProperty("session-stamp"))
        || !UpgradeBackup.fingerprint(world.resolve("economy"))
            .equals(state.getProperty("storage-fingerprint")))
      throw new IOException(
          "Clean complete-world shutdown not established; start and stop cleanly first");
  }
}
