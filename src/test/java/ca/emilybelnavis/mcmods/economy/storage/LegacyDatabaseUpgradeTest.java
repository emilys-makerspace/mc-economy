package ca.emilybelnavis.mcmods.economy.storage;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyDatabaseUpgradeTest {
  private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000010");
  private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000020");
  private static final List<String> IDS =
      List.of(
          "00000000-0000-0000-0000-000000000101",
          "00000000-0000-0000-0000-000000000102",
          "00000000-0000-0000-0000-000000000103",
          "00000000-0000-0000-0000-000000000104");
  private static final List<String> CURRENCIES = List.of("CAD", "JPY", "CHF", "CAD");
  private static final List<Long> BALANCES = List.of(12345L, 8765L, 135L, 4321L);
  @TempDir Path temporary;

  @org.junit.jupiter.api.BeforeEach
  void canonicalTemporaryDirectory() throws Exception {
    temporary = temporary.toRealPath();
  }

  @Test
  void approvedLegacyImportPreservesMinorUnitsAndArchivesAndReopens() throws Exception {
    Path world = legacy("known");
    String originalHash = StorageFiles.hash(database(world));
    var review = DatabaseUpgrade.review(world);
    assertEquals(0, review.sourceSchema());
    assertEquals(SchemaMigrations.CURRENT, review.targetSchema());
    assertEquals(4, review.accountCount());
    DatabaseUpgrade.approve(world, review, ADMIN, () -> true);
    DatabaseUpgrade.apply(world);

    try (var connection = connect(world)) {
      assertEquals(SchemaMigrations.CURRENT, scalar(connection, "PRAGMA user_version"));
      assertEquals(4, scalar(connection, "SELECT count(*) FROM account_catalog"));
      for (int i = 0; i < IDS.size(); i++) {
        try (var query =
            connection.prepareStatement(
                "SELECT owner_id,purpose,currency,is_primary,status FROM account_catalog WHERE account_id=?")) {
          query.setString(1, IDS.get(i));
          try (var rows = query.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(OWNER.toString(), rows.getString("owner_id"));
            assertEquals(i == 3 ? "TRADING" : "BANK", rows.getString("purpose"));
            assertEquals(i == 3 ? null : CURRENCIES.get(i), rows.getString("currency"));
            assertEquals(i == 0 ? 1 : 0, rows.getInt("is_primary"));
            assertEquals("OPEN", rows.getString("status"));
            assertFalse(rows.next());
          }
        }
        try (var query =
            connection.prepareStatement(
                "SELECT minor_units FROM ledger_balances WHERE account_id=? AND currency=?")) {
          query.setString(1, IDS.get(i));
          query.setString(2, CURRENCIES.get(i));
          try (var rows = query.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(BALANCES.get(i).longValue(), rows.getLong(1));
          }
        }
      }
      assertEquals(
          4,
          scalar(
              connection,
              "SELECT count(*) FROM journal_records WHERE record_type='"
                  + LegacyImportFacts.FACT
                  + "'"));
      Map<String, Long> sums = new HashMap<>();
      try (var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT payload FROM journal_records WHERE record_type='economy:posting'")) {
        int postings = 0;
        while (rows.next()) {
          var fields = rows.getString(1).split("\\|", -1);
          sums.merge(fields[2], Long.parseLong(fields[3]), Math::addExact);
          postings++;
        }
        assertEquals(8, postings);
      }
      assertEquals(Map.of("CAD", 0L, "JPY", 0L, "CHF", 0L), sums);
      assertEquals(4, scalar(connection, "SELECT count(*) FROM legacy_v0_accounts"));
      assertEquals(7, scalar(connection, "SELECT quantity FROM legacy_v0_holdings"));
      assertEquals("12.3400", text(connection, "SELECT price FROM legacy_v0_stock_prices"));
      assertEquals("OPEN", text(connection, "SELECT status FROM legacy_v0_orders"));
      assertEquals(
          "1999-01-02T03:04:05Z", text(connection, "SELECT timestamp FROM legacy_v0_trades"));
      assertEquals("legacy memo", text(connection, "SELECT memo FROM legacy_v0_ledger"));
      assertEquals(
          "12.340000", text(connection, "SELECT display_price FROM legacy_v0_daily_stock_prices"));
      assertEquals(
          "CAD", text(connection, "SELECT currency FROM legacy_v0_ledger_legacy_currencies"));
      assertEquals(
          12345, scalar(connection, "SELECT balance FROM legacy_v0_ledger_display_values"));
      try (var statement = connection.createStatement()) {
        assertThrows(
            SQLException.class,
            () -> statement.execute("UPDATE legacy_v0_holdings SET quantity=8"));
        assertThrows(SQLException.class, () -> statement.execute("DELETE FROM legacy_v0_orders"));
        assertThrows(
            SQLException.class,
            () -> statement.execute("INSERT INTO legacy_v0_stocks VALUES('NEW','new')"));
      }
    }
    reopen(world);
    var completed =
        UpgradeBackup.load(world.resolve("economy/database-upgrade-completed.properties"));
    Path backup = Path.of(completed.getProperty("backup"));
    assertEquals("disposable level data", Files.readString(backup.resolve("world/level.dat")));
    assertEquals(originalHash, StorageFiles.hash(backup.resolve("world/economy/economy.sqlite")));
    try (var connection =
        DriverManager.getConnection(
            "jdbc:sqlite:" + backup.resolve("world/economy/economy.sqlite"))) {
      assertEquals(0, scalar(connection, "PRAGMA user_version"));
      assertEquals(4, scalar(connection, "SELECT count(*) FROM accounts"));
    }
  }

  @Test
  void missingRevokedAndStaleApprovalCannotChangeSource() throws Exception {
    Path world = legacy("approval");
    String original = StorageFiles.hash(database(world));
    assertThrows(IOException.class, () -> DatabaseUpgrade.apply(world));
    assertEquals(original, StorageFiles.hash(database(world)));
    var review = DatabaseUpgrade.review(world);
    assertThrows(
        IOException.class, () -> DatabaseUpgrade.approve(world, review, ADMIN, () -> false));
    assertFalse(Files.exists(world.resolve("economy/database-upgrade-approved.properties")));
    assertEquals(original, StorageFiles.hash(database(world)));
    DatabaseUpgrade.approve(world, review, ADMIN, () -> true);
    try (var connection = connect(world);
        var statement = connection.createStatement()) {
      statement.execute("UPDATE accounts SET balance=balance+1 WHERE is_primary=1");
    }
    String changed = StorageFiles.hash(database(world));
    assertNotEquals(original, changed);
    assertThrows(IOException.class, () -> DatabaseUpgrade.apply(world));
    assertThrows(
        IOException.class, () -> DatabaseUpgrade.approve(world, review, ADMIN, () -> true));
    assertEquals(changed, StorageFiles.hash(database(world)));
    var fresh = DatabaseUpgrade.review(world);
    DatabaseUpgrade.approve(world, fresh, ADMIN, () -> true);
    Path receipt = world.resolve("economy/database-upgrade-approved.properties");
    var properties = UpgradeBackup.load(receipt);
    properties.setProperty("target-schema", Integer.toString(SchemaMigrations.CURRENT + 1));
    StorageFiles.replace(receipt, UpgradeBackup.properties(properties));
    assertThrows(IOException.class, () -> DatabaseUpgrade.apply(world));
    assertEquals(changed, StorageFiles.hash(database(world)));
  }

  @Test
  void unknownLegacyLayoutAndNewerSchemaRemainUntouched() throws Exception {
    Path unknown = legacy("unknown");
    try (var connection = connect(unknown);
        var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE accounts ADD COLUMN unrelated TEXT");
    }
    String unknownHash = StorageFiles.hash(database(unknown));
    assertThrows(IOException.class, () -> DatabaseUpgrade.review(unknown));
    assertThrows(IOException.class, () -> DatabaseUpgrade.apply(unknown));
    assertEquals(unknownHash, StorageFiles.hash(database(unknown)));

    Path newer = Files.createDirectory(temporary.resolve("newer"));
    var store = new SqliteStorage();
    try {
      store.initialize(newer);
    } finally {
      store.close();
    }
    try (var connection = connect(newer);
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA user_version=" + (SchemaMigrations.CURRENT + 1));
    }
    String newerHash = StorageFiles.hash(database(newer));
    assertThrows(IOException.class, () -> DatabaseUpgrade.review(newer));
    assertThrows(IOException.class, () -> DatabaseUpgrade.apply(newer));
    assertEquals(newerHash, StorageFiles.hash(database(newer)));
  }

  @Test
  void supportedSchemaAlsoRequiresApprovalAndPreservesIdentityAndHistory() throws Exception {
    Path world = Files.createDirectory(temporary.resolve("supported"));
    Files.createDirectory(world.resolve("economy"));
    Files.writeString(world.resolve("level.dat"), "supported world fixture");
    UUID identity = UUID.randomUUID();
    int sourceVersion = SchemaMigrations.CURRENT - 1;
    String originalHistory;
    try (var connection = connect(world)) {
      SchemaMigrations.apply(
          connection,
          identity,
          0,
          sourceVersion,
          SchemaMigrations.REGISTRY.subList(0, sourceVersion));
      originalHistory =
          text(connection, "SELECT applied_at_utc FROM migration_history WHERE version=1");
    }
    StorageFiles.writeNew(
        world.resolve("economy/world-identity"), SqliteStorage.encodeIdentity(identity));
    assertUpgradeRequired(world);
    UpgradeBackup.prepare(world);
    assertUpgradeRequired(world);
    assertThrows(IOException.class, () -> DatabaseUpgrade.apply(world));
    var review = DatabaseUpgrade.review(world);
    assertEquals(sourceVersion, review.sourceSchema());
    DatabaseUpgrade.approve(world, review, ADMIN, () -> true);
    DatabaseUpgrade.apply(world);
    var store = SqliteStorage.serverStorage(RecordCatalog.core(), 5000);
    try {
      store.initialize(world);
      assertEquals(identity, store.snapshot().worldId().value());
      assertEquals(SchemaMigrations.CURRENT, store.snapshot().schemaVersion());
    } finally {
      store.close();
    }
    try (var connection = connect(world)) {
      assertEquals(
          originalHistory,
          text(connection, "SELECT applied_at_utc FROM migration_history WHERE version=1"));
      assertEquals(0, scalar(connection, "SELECT count(*) FROM operation_outcomes"));
    }
  }

  @Test
  void interruptedInstallBlocksStartupAndResumesWithoutDuplicateOpenings() throws Exception {
    Path world = legacy("interrupted");
    var review = DatabaseUpgrade.review(world);
    DatabaseUpgrade.approve(world, review, ADMIN, () -> true);
    assertThrows(
        IllegalStateException.class,
        () ->
            DatabaseUpgrade.apply(
                world,
                () -> {
                  throw new IllegalStateException("simulated stop after database swap");
                }));
    assertTrue(Files.exists(world.resolve("economy/" + DatabaseUpgrade.INSTALL)));
    assertUpgradeRequired(world);
    DatabaseUpgrade.apply(world);
    assertFalse(Files.exists(world.resolve("economy/" + DatabaseUpgrade.INSTALL)));
    reopen(world);
    try (var connection = connect(world)) {
      assertEquals(4, scalar(connection, "SELECT count(*) FROM account_catalog"));
      assertEquals(
          4,
          scalar(
              connection,
              "SELECT count(*) FROM journal_records WHERE record_type='"
                  + LegacyImportFacts.FACT
                  + "'"));
      assertEquals(4, scalar(connection, "SELECT count(*) FROM legacy_v0_accounts"));
    }
  }

  private void assertUpgradeRequired(Path world) throws Exception {
    var store = SqliteStorage.serverStorage(RecordCatalog.core(), 5000);
    try {
      var error = assertThrows(StorageException.class, () -> store.initialize(world));
      assertEquals(StorageException.Kind.UPGRADE_REQUIRED, error.kind());
    } finally {
      store.close();
    }
  }

  private static void reopen(Path world) throws Exception {
    var store = new SqliteStorage(RecordCatalog.core());
    try {
      store.initialize(world);
      assertEquals(SchemaMigrations.CURRENT, store.snapshot().schemaVersion());
    } finally {
      store.close();
    }
  }

  private Path legacy(String name) throws Exception {
    Path world = Files.createDirectory(temporary.resolve(name));
    Files.createDirectory(world.resolve("economy"));
    Files.writeString(world.resolve("level.dat"), "disposable level data");
    try (var connection = connect(world);
        var statement = connection.createStatement()) {
      for (String sql :
          List.of(
              "CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT)",
              "CREATE TABLE accounts(id TEXT PRIMARY KEY,owner TEXT,currency TEXT,kind TEXT,is_primary INTEGER,balance INTEGER)",
              "CREATE TABLE ledger(sequence INTEGER PRIMARY KEY,operation TEXT,account TEXT REFERENCES accounts(id),timestamp TEXT,delta INTEGER,balance INTEGER,memo TEXT,transaction_id TEXT,currency TEXT)",
              "CREATE TABLE exchange_rates(currency TEXT,rate TEXT)",
              "CREATE TABLE historical_rates(date TEXT,currency TEXT,rate TEXT)",
              "CREATE TABLE stocks(id TEXT PRIMARY KEY,name TEXT)",
              "CREATE TABLE stock_prices(stock TEXT REFERENCES stocks(id),price TEXT)",
              "CREATE TABLE stock_price_history(sequence INTEGER PRIMARY KEY,stock TEXT,price TEXT,timestamp TEXT,currency TEXT)",
              "CREATE TABLE holdings(owner TEXT,stock TEXT,quantity INTEGER)",
              "CREATE TABLE orders(id TEXT PRIMARY KEY,owner TEXT,stock TEXT,side TEXT,quantity INTEGER,price TEXT,status TEXT,currency TEXT)",
              "CREATE TABLE trades(id TEXT PRIMARY KEY,order_id TEXT REFERENCES orders(id),account TEXT REFERENCES accounts(id),quantity INTEGER,price TEXT,timestamp TEXT,currency TEXT)",
              "CREATE TABLE daily_stock_prices(day TEXT,stock TEXT,price TEXT,currency TEXT,display_price TEXT,display_currency TEXT)",
              "CREATE TABLE ledger_display_values(sequence INTEGER,currency TEXT,delta INTEGER,balance INTEGER)",
              "CREATE TABLE ledger_legacy_currencies(account TEXT,currency TEXT)"))
        statement.execute(sql);
      statement.execute("INSERT INTO metadata VALUES('world_currency','CAD')");
      for (int i = 0; i < IDS.size(); i++) {
        try (var insert = connection.prepareStatement("INSERT INTO accounts VALUES(?,?,?,?,?,?)")) {
          insert.setString(1, IDS.get(i));
          insert.setString(2, OWNER.toString());
          insert.setString(3, CURRENCIES.get(i));
          insert.setString(4, i == 3 ? "TRADING" : "BANK");
          insert.setInt(5, i == 0 ? 1 : 0);
          insert.setLong(6, BALANCES.get(i));
          insert.executeUpdate();
        }
      }
      statement.execute(
          "INSERT INTO ledger VALUES(1,'old-operation','"
              + IDS.getFirst()
              + "','1999-01-02T03:04:05Z',12345,12345,'legacy memo','old-transaction','CAD')");
      statement.execute("INSERT INTO stocks VALUES('LEGACY_A','Legacy A')");
      statement.execute("INSERT INTO stock_prices VALUES('LEGACY_A','12.3400')");
      statement.execute(
          "INSERT INTO stock_price_history VALUES(1,'LEGACY_A','12.3400','1999-01-02T03:04:05Z','CAD')");
      statement.execute("INSERT INTO holdings VALUES('" + OWNER + "','LEGACY_A',7)");
      statement.execute(
          "INSERT INTO orders VALUES('order-one','"
              + OWNER
              + "','LEGACY_A','BUY',3,'12.3400','OPEN','CAD')");
      statement.execute(
          "INSERT INTO trades VALUES('trade-one','order-one','"
              + IDS.getFirst()
              + "',2,'12.3400','1999-01-02T03:04:05Z','CAD')");
      statement.execute("INSERT INTO exchange_rates VALUES('CAD','1.0000')");
      statement.execute("INSERT INTO historical_rates VALUES('1999-01-02','CAD','1.0000')");
      statement.execute(
          "INSERT INTO daily_stock_prices VALUES('1999-01-02','LEGACY_A','12.3400','CAD','12.340000','CAD')");
      statement.execute("INSERT INTO ledger_display_values VALUES(1,'CAD',12345,12345)");
      statement.execute(
          "INSERT INTO ledger_legacy_currencies VALUES('" + IDS.getFirst() + "','CAD')");
    }
    return world;
  }

  private static Path database(Path world) {
    return world.resolve("economy/economy.sqlite");
  }

  private static Connection connect(Path world) throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + database(world));
  }

  private static long scalar(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }

  private static String text(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getString(1);
    }
  }
}
