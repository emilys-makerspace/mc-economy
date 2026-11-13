package ca.emilybelnavis.mcmods.economy.storage;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Logged-in admin approval, stopped-world backup, staged conversion and recoverable installation.
 */
public final class DatabaseUpgrade {
  private static final String APPROVAL = "database-upgrade-approved.properties";
  static final String INSTALL = "database-upgrade-install.properties";
  private static final String LEGACY = "legacy-minor-units-v0";
  private static final String CURRENT = "economy-foundation";

  public record Review(
      String token,
      String sourceFormat,
      int sourceSchema,
      int targetSchema,
      long accountCount,
      String summary) {}

  private DatabaseUpgrade() {}

  public static Review review(Path world) throws Exception {
    world = checkedWorld(world);
    try (var ownership = WorldOwnership.acquire(world.resolve("economy/owner.lock"))) {
      if (Files.exists(world.resolve("economy/" + INSTALL)))
        throw new IOException(
            "Installation was interrupted; stop the server and rerun upgradeEconomyDatabase.");
      return inspect(world);
    }
  }

  private static Review inspect(Path world) throws Exception {
    Path directory = world.resolve("economy");
    String fingerprint = UpgradeBackup.fingerprint(directory);
    int version;
    String format;
    long accounts;
    String summary;
    try (var connection = readOnly(directory.resolve("economy.sqlite"))) {
      int application = integer(connection, "PRAGMA application_id");
      version = integer(connection, "PRAGMA user_version");
      if (application == 0 && version == 0) {
        if (Files.exists(directory.resolve("world-identity"))
            || Files.exists(directory.resolve("identity.pending")))
          throw new IOException("Legacy database conflicts with current world identity files.");
        var legacy = LegacyDatabase.inspect(connection);
        format = LEGACY;
        accounts = legacy.accounts().size();
        var totals =
            new EnumMap<ca.emilybelnavis.mcmods.economy.api.EconomyCurrency, BigInteger>(
                ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.class);
        for (var account : legacy.accounts())
          totals.merge(account.currency(), BigInteger.valueOf(account.balance()), BigInteger::add);
        String amounts =
            totals.entrySet().stream()
                .map(
                    entry ->
                        entry.getKey().name()
                            + " "
                            + new BigDecimal(entry.getValue())
                                .divide(BigDecimal.valueOf(entry.getKey().minorUnitsPerMajor()))
                                .toPlainString())
                .collect(java.util.stream.Collectors.joining(", "));
        summary =
            "Opening balances: "
                + (amounts.isEmpty() ? "none" : amounts)
                + ". "
                + "Preserve account IDs, owners, currencies, primary flags and minor-unit balances. "
                + "Retain every original table as a read-only legacy_v0_ archive. "
                + "Old stock holdings/orders/trades and transaction/FX history remain archived, not active modern facilities. "
                + "World currency "
                + legacy.denomination()
                + "; current opening FX defaults. A complete stopped-world backup is required.";
      } else if (application == SchemaMigrations.APPLICATION_ID
          && version >= SchemaMigrations.MINIMUM_SUPPORTED
          && version < SchemaMigrations.CURRENT) {
        format = CURRENT;
        accounts = version >= 6 ? count(connection, "account_catalog") : 0;
        summary =
            "Apply the registered schema migrations; preserve current identities and journal history. "
                + "A complete stopped-world backup is required.";
      } else if (application == SchemaMigrations.APPLICATION_ID
          && version == SchemaMigrations.CURRENT) {
        throw new IOException(
            "Database already uses current schema " + SchemaMigrations.CURRENT + ".");
      } else {
        throw new IOException(
            "Unknown database format or unsupported/newer schema; no conversion performed.");
      }
    }
    if (!fingerprint.equals(UpgradeBackup.fingerprint(directory)))
      throw new IOException("Database changed during inspection; review again.");
    String token =
        digest(world + "\n" + format + "\n" + SchemaMigrations.CURRENT + "\n" + fingerprint);
    return new Review(token, format, version, SchemaMigrations.CURRENT, accounts, summary);
  }

  public static void approve(
      Path world, Review reviewed, UUID administrator, BooleanSupplier stillAuthorized)
      throws Exception {
    Objects.requireNonNull(administrator);
    world = checkedWorld(world);
    try (var ownership = WorldOwnership.acquire(world.resolve("economy/owner.lock"))) {
      if (Files.exists(world.resolve("economy/" + INSTALL)))
        throw new IOException("Installation is pending.");
      Review current = inspect(world);
      if (!current.equals(reviewed))
        throw new IOException("Database or target changed; review again.");
      var approval = new Properties();
      approval.setProperty("format", "economy-admin-upgrade-v1");
      approval.setProperty("token", current.token());
      approval.setProperty("administrator", administrator.toString());
      approval.setProperty("target-schema", Integer.toString(current.targetSchema()));
      approval.setProperty("approved-at-utc", Instant.now().toString());
      Path destination = world.resolve("economy/" + APPROVAL);
      StorageFiles.rejectLinks(destination);
      if (!stillAuthorized.getAsBoolean()) throw new IOException("Administrator session expired.");
      StorageFiles.replace(destination, UpgradeBackup.properties(approval));
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1)
      throw new IllegalArgumentException("Supply -PeconomyWorld=/absolute/stopped/world");
    System.out.println(apply(Path.of(args[0])));
  }

  public static String apply(Path world) throws Exception {
    return apply(world, () -> {});
  }

  /** The callback is a narrow fault boundary for exercising interrupted installation recovery. */
  static String apply(Path world, Runnable afterDatabaseInstall) throws Exception {
    world = checkedWorld(world);
    Path directory = world.resolve("economy");
    Path intent = directory.resolve(INSTALL);
    StorageFiles.rejectLinks(intent);
    if (Files.exists(intent)) {
      try (var minecraft = WorldOwnership.acquire(world.resolve("session.lock"));
          var ownership = WorldOwnership.acquire(directory.resolve("owner.lock"))) {
        install(world, UpgradeBackup.load(intent), afterDatabaseInstall);
        return "Completed previously approved database upgrade to schema "
            + SchemaMigrations.CURRENT
            + ".";
      }
    }
    Review reviewed = review(world);
    requireApproval(world, reviewed);
    // This utility takes both world locks and publishes its receipt only after all hashes validate.
    Path backup = UpgradeBackup.prepareApprovedDatabaseUpgrade(world);
    try (var minecraft = WorldOwnership.acquire(world.resolve("session.lock"));
        var ownership = WorldOwnership.acquire(directory.resolve("owner.lock"))) {
      if (!reviewed.equals(inspect(world)))
        throw new IOException("Source changed after backup; review again.");
      Properties approval = requireApproval(world, reviewed);
      UpgradeBackup.validate(directory, UpgradeBackup.fingerprint(directory));
      UUID administrator = UUID.fromString(approval.getProperty("administrator"));
      Path stage = backup.resolve("staging");
      Files.createDirectory(stage);
      Path stagedEconomy = Files.createDirectory(stage.resolve("economy"));
      for (String name :
          List.of("economy.sqlite", "economy.sqlite-wal", "world-identity", "identity.pending")) {
        Path source = backup.resolve("world/economy").resolve(name);
        if (Files.exists(source)) Files.copy(source, stagedEconomy.resolve(name));
      }
      if (reviewed.sourceFormat().equals(LEGACY)) {
        UUID identity =
            UUID.nameUUIDFromBytes(
                ("legacy-world|" + reviewed.token()).getBytes(StandardCharsets.UTF_8));
        try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + stagedEconomy.resolve("economy.sqlite"))) {
          var source = LegacyDatabase.inspect(connection);
          LegacyDatabase.archive(connection, source);
          SchemaMigrations.apply(
              connection, identity, 0, SchemaMigrations.CURRENT, SchemaMigrations.REGISTRY);
          StorageFiles.writeNew(
              stagedEconomy.resolve("identity.pending"), SqliteStorage.encodeIdentity(identity));
        }
        var store = new SqliteStorage();
        try {
          store.initialize(stage);
          // The unchanged archived source supplies the exact opening amounts, never a fabricated
          // history.
          try (var connection = readOnly(backup.resolve("world/economy/economy.sqlite"))) {
            LegacyImportFacts.importAccounts(
                store, LegacyDatabase.inspect(connection), reviewed.token(), administrator);
          }
        } finally {
          store.close();
        }
      } else {
        Files.copy(
            backup.resolve("receipt.properties"),
            stagedEconomy.resolve("upgrade-backup.properties"));
        var store = new SqliteStorage();
        try {
          store.initialize(stage);
        } finally {
          store.close();
        }
      }
      checkpoint(stagedEconomy.resolve("economy.sqlite"));
      // Full schema and historical fact compatibility validation against the staged final state.
      var verified = new SqliteStorage();
      try {
        verified.initialize(stage);
      } finally {
        verified.close();
      }
      checkpoint(stagedEconomy.resolve("economy.sqlite"));
      var installation = new Properties();
      installation.setProperty("format", "economy-database-install-v1");
      installation.setProperty("world", world.toString());
      installation.setProperty("backup", backup.toString());
      installation.setProperty("token", reviewed.token());
      installation.setProperty("administrator", administrator.toString());
      installation.setProperty("target-schema", Integer.toString(SchemaMigrations.CURRENT));
      installation.setProperty("old-db", StorageFiles.hash(directory.resolve("economy.sqlite")));
      installation.setProperty("source-fingerprint", UpgradeBackup.fingerprint(directory));
      installation.setProperty(
          "new-db", StorageFiles.hash(stagedEconomy.resolve("economy.sqlite")));
      installation.setProperty(
          "identity", Files.readString(stagedEconomy.resolve("world-identity")));
      for (String suffix : List.of("-wal", "-shm")) {
        Path path = directory.resolve("economy.sqlite" + suffix);
        StorageFiles.rejectLinks(path);
        installation.setProperty(
            "old" + suffix, Files.exists(path) ? StorageFiles.hash(path) : "absent");
      }
      StorageFiles.writeNew(intent, UpgradeBackup.properties(installation));
      install(world, installation, afterDatabaseInstall);
      return "Database upgraded to schema "
          + SchemaMigrations.CURRENT
          + ". Original complete-world backup: "
          + backup;
    }
  }

  private static void install(Path world, Properties p, Runnable afterDatabaseInstall)
      throws Exception {
    Path directory = world.resolve("economy");
    if (!"economy-database-install-v1".equals(p.getProperty("format"))
        || !world.toString().equals(p.getProperty("world"))
        || !Integer.toString(SchemaMigrations.CURRENT).equals(p.getProperty("target-schema")))
      throw new IOException("Unrecognized installation intent or changed target release.");
    Path backup = Path.of(p.getProperty("backup")).toAbsolutePath().normalize();
    if (!backup
        .getParent()
        .equals(world.resolveSibling(world.getFileName() + "-economy-upgrade-backups")))
      throw new IOException("Installation backup location mismatch.");
    UpgradeBackup.validateBackup(backup, UpgradeBackup.load(backup.resolve("receipt.properties")));
    Properties approved = UpgradeBackup.load(backup.resolve("world/economy/" + APPROVAL));
    if (!"economy-admin-upgrade-v1".equals(approved.getProperty("format"))
        || !Objects.equals(approved.getProperty("token"), p.getProperty("token"))
        || !Objects.equals(approved.getProperty("administrator"), p.getProperty("administrator"))
        || !Objects.equals(approved.getProperty("target-schema"), p.getProperty("target-schema")))
      throw new IOException("Installation does not match the archived administrator approval.");
    Path staged = backup.resolve("staging/economy/economy.sqlite");
    StorageFiles.rejectLinks(staged);
    Path active = directory.resolve("economy.sqlite");
    String actual = StorageFiles.hash(active);
    if (!actual.equals(p.getProperty("new-db"))) {
      if (!actual.equals(p.getProperty("old-db"))
          || !StorageFiles.hash(staged).equals(p.getProperty("new-db")))
        throw new IOException("Unexpected source or staged database; installation refused.");
      if (!UpgradeBackup.fingerprint(directory).equals(p.getProperty("source-fingerprint")))
        throw new IOException("Source storage changed after installation preparation.");
      try (var file = FileChannel.open(staged, StandardOpenOption.WRITE)) {
        file.force(true);
      }
      Files.move(
          staged, active, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      StorageFiles.forceDirectory(directory);
      afterDatabaseInstall.run();
    }
    // The durable intent blocks normal startup until old sidecars and new identity are reconciled.
    for (String suffix : List.of("-wal", "-shm")) {
      Path sidecar = directory.resolve("economy.sqlite" + suffix);
      StorageFiles.rejectLinks(sidecar);
      if (Files.exists(sidecar)) {
        if (!StorageFiles.hash(sidecar).equals(p.getProperty("old" + suffix)))
          throw new IOException("Sidecar changed during installation; manual inspection required.");
        Files.delete(sidecar);
      }
    }
    String identity = p.getProperty("identity");
    // Validate the exact published identity against the staged immutable metadata.
    try (var connection =
        DriverManager.getConnection("jdbc:sqlite:" + active.toUri() + "?mode=ro&immutable=1")) {
      try (var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT world_id FROM foundation_metadata WHERE singleton=1")) {
        if (!rows.next()
            || !SqliteStorage.encodeIdentity(UUID.fromString(rows.getString(1))).equals(identity))
          throw new IOException("Staged world identity mismatch.");
      }
    }
    StorageFiles.replace(directory.resolve("world-identity"), identity);
    Files.deleteIfExists(directory.resolve("identity.pending"));
    p.setProperty("completed-at-utc", Instant.now().toString());
    StorageFiles.replace(
        directory.resolve("database-upgrade-completed.properties"), UpgradeBackup.properties(p));
    Files.delete(directory.resolve(INSTALL));
    StorageFiles.forceDirectory(directory);
  }

  private static Properties requireApproval(Path world, Review review) throws IOException {
    Path path = world.resolve("economy/" + APPROVAL);
    StorageFiles.rejectLinks(path);
    if (!Files.isRegularFile(path))
      throw new IOException(
          "A logged-in administrator must run /economy upgrade review and confirm first.");
    Properties p = UpgradeBackup.load(path);
    if (!"economy-admin-upgrade-v1".equals(p.getProperty("format"))
        || !review.token().equals(p.getProperty("token"))
        || !Integer.toString(review.targetSchema()).equals(p.getProperty("target-schema")))
      throw new IOException(
          "Approval is stale or belongs to a different database/target; review again.");
    UUID.fromString(p.getProperty("administrator"));
    return p;
  }

  static OfflineInspector.Metadata legacyBackupMetadata(Path world) throws Exception {
    try (var c = readOnly(world.resolve("economy/economy.sqlite"))) {
      LegacyDatabase.inspect(c);
    }
    UUID identity =
        UUID.nameUUIDFromBytes(
            UpgradeBackup.fingerprint(world.resolve("economy")).getBytes(StandardCharsets.UTF_8));
    return new OfflineInspector.Metadata(
        new WorldShutdown.Evidence(identity.toString(), 0, 0, 0), false);
  }

  private static Path checkedWorld(Path world) throws IOException {
    world = world.toAbsolutePath().normalize();
    StorageFiles.rejectLinks(world);
    StorageFiles.rejectLinks(world.resolve("economy"));
    StorageFiles.rejectLinks(world.resolve("economy/economy.sqlite"));
    StorageFiles.rejectLinks(world.resolve("economy/owner.lock"));
    StorageFiles.rejectLinks(world.resolve("session.lock"));
    if (!Files.isRegularFile(world.resolve("economy/economy.sqlite")))
      throw new IOException("Existing economy database required.");
    return world;
  }

  private static Connection readOnly(Path path) throws SQLException, IOException {
    StorageFiles.rejectLinks(path);
    SqliteStorage.verifyDriver();
    Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path.toUri() + "?mode=ro");
    try (var statement = connection.createStatement()) {
      statement.execute("PRAGMA query_only=ON");
      statement.execute("PRAGMA busy_timeout=5000");
    }
    return connection;
  }

  private static void checkpoint(Path path) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
      if (!rows.next() || rows.getInt(1) != 0)
        throw new SQLException("Staged database checkpoint failed");
    }
  }

  private static int integer(Connection c, String sql) throws SQLException {
    try (var s = c.createStatement();
        var r = s.executeQuery(sql)) {
      r.next();
      return r.getInt(1);
    }
  }

  private static long count(Connection c, String table) throws SQLException {
    try (var s = c.createStatement();
        var r = s.executeQuery("SELECT count(*) FROM " + table)) {
      r.next();
      return r.getLong(1);
    }
  }

  private static String digest(String text) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
  }
}
