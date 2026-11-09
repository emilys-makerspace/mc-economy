package ca.emilybelnavis.mcmods.economy.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineWalTest {
  @TempDir Path temporary;

  @Test
  void inspectionReadsCommittedWalWithoutChangingSourceCompanions() throws Exception {
    Path root = temporary.toRealPath(), source = Files.createDirectory(root.resolve("source"));
    var store = new SqliteStorage();
    store.initialize(source);
    store.close();
    Path copy = Files.createDirectories(root.resolve("copy/economy")),
        db = source.resolve("economy/economy.sqlite");
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + db);
        var sql = c.createStatement()) {
      sql.execute("PRAGMA wal_autocheckpoint=0");
      sql.execute("UPDATE runtime_state SET clean_shutdown=0");
      // Construct a quiescent WAL fixture; this is not the production backup procedure.
      Files.copy(db, copy.resolve("economy.sqlite"));
      Files.copy(db.resolveSibling("economy.sqlite-wal"), copy.resolve("economy.sqlite-wal"));
    }
    var metadata = OfflineInspector.metadata(copy.getParent());
    assertFalse(metadata.databaseClean());
    assertFalse(Files.exists(copy.resolve("economy.sqlite-shm")));
  }
}
