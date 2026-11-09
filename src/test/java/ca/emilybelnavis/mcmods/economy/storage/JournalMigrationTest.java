package ca.emilybelnavis.mcmods.economy.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JournalMigrationTest {
  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(
      ints = {
        2, 3, 4, 5, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
        30, 31, 32, 33, 34
      })
  void upgradeRequiresBackupAndPreservesFoundationIdentityAndHistory(int schema) throws Exception {
    Path world = temporary.toRealPath();
    Path directory = Files.createDirectory(world.resolve("economy"));
    UUID id = UUID.randomUUID();
    String historicalUtc;
    try (var connection =
        DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("economy.sqlite"))) {
      SchemaMigrations.apply(
          connection, id, 0, schema, SchemaMigrations.REGISTRY.subList(0, schema));
      try (var sql = connection.createStatement();
          var rows =
              sql.executeQuery("SELECT applied_at_utc FROM migration_history WHERE version=1")) {
        assertTrue(rows.next());
        historicalUtc = rows.getString(1);
      }
    }
    StorageFiles.writeNew(directory.resolve("world-identity"), SqliteStorage.encodeIdentity(id));
    Files.writeString(world.resolve("level.dat"), "disposable complete-world fixture");
    assertEquals(
        StorageException.Kind.SCHEMA,
        assertThrows(StorageException.class, () -> new SqliteStorage().initialize(world)).kind());
    UpgradeBackup.prepare(world);
    var store = new SqliteStorage();
    store.initialize(world);
    assertEquals(id, store.snapshot().worldId().value());
    assertEquals(SchemaMigrations.CURRENT, store.snapshot().schemaVersion());
    assertEquals(
        historicalUtc,
        store
            .transaction(
                tx ->
                    tx.query(
                        "SELECT applied_at_utc FROM migration_history WHERE version=1",
                        List.of(),
                        1))
            .getFirst()
            .get("applied_at_utc"));
    assertEquals(
        "0",
        store
            .transaction(
                tx -> tx.query("SELECT count(*) AS count FROM operation_outcomes", List.of(), 1))
            .getFirst()
            .get("count"));
    store.close();
  }

  @Test
  void removedProtectionFailsClosedOnReopen() throws Exception {
    Path world = temporary.toRealPath();
    var store = new SqliteStorage();
    store.initialize(world);
    store.close();
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + world.resolve("economy/economy.sqlite"));
        var sql = connection.createStatement()) {
      sql.execute("DROP TRIGGER immutable_journal_records_update");
    }
    assertEquals(
        StorageException.Kind.SCHEMA,
        assertThrows(StorageException.class, () -> new SqliteStorage().initialize(world)).kind());
  }
}
