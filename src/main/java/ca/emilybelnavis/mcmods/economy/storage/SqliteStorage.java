package ca.emilybelnavis.mcmods.economy.storage;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Single connection, confined to the lifecycle's storage worker. Never substitutes an empty store.
 */
public final class SqliteStorage implements FoundationStore {
  public static final String DRIVER = "org.sqlite.JDBC";

  @FunctionalInterface
  interface ConnectionOpener {
    Connection open(Path path, boolean create) throws SQLException;
  }

  private final ConnectionOpener opener;
  private final int lockTimeoutMillis;
  private Path databasePath;
  // If disposal fails, keep the ownership lock alive until process exit or confirmed recovery.
  private static final Set<SqliteStorage> QUARANTINED =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog catalog;

  public SqliteStorage() {
    this(ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog.core());
  }

  public SqliteStorage(ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog catalog) {
    this(catalog, 5000);
  }

  public SqliteStorage(
      ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog catalog, int lockTimeoutMillis) {
    this(
        (path, create) ->
            DriverManager.getConnection(
                create ? "jdbc:sqlite:" + path : "jdbc:sqlite:" + path.toUri() + "?mode=rw"),
        catalog,
        lockTimeoutMillis);
  }

  SqliteStorage(ConnectionOpener opener) {
    this(opener, ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog.core());
  }

  SqliteStorage(
      ConnectionOpener opener, ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog catalog) {
    this(opener, catalog, 5000);
  }

  private SqliteStorage(
      ConnectionOpener opener,
      ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog catalog,
      int lockTimeoutMillis) {
    if (lockTimeoutMillis < 1 || lockTimeoutMillis > 60000)
      throw new IllegalArgumentException("SQLite lock timeout must be between 1 and 60000 ms");
    this.opener = opener;
    this.catalog = catalog;
    this.lockTimeoutMillis = lockTimeoutMillis;
  }

  private Thread owner;
  private Connection connection;
  private WorldOwnership ownership;
  private Snapshot snapshot;
  private boolean inTransaction;
  private boolean poisoned;

  public static String verifyDriver() {
    return loadDriver(DRIVER);
  }

  static String loadDriver(String driverClass) {
    try {
      Class.forName(driverClass);
      // Instantiation also detects missing native/configuration dependencies at initialization.
      return DriverManager.getDriver("jdbc:sqlite:").getClass().getName();
    } catch (ClassNotFoundException | SQLException | LinkageError e) {
      throw new StorageException(
          StorageException.Kind.CONFIGURATION_DRIVER, "SQLite JDBC driver unavailable", e);
    }
  }

  private Path identityFile, pendingFile;
  private UUID initializingIdentity;
  private int initializingVersion;
  private String initialFingerprint;
  private JournalCompatibility compatibility;
  private OwnerHistorySchema.Initializer historyInitializer;
  private boolean initialized;
  private boolean cleanEligible;
  private volatile ca.emilybelnavis.mcmods.economy.foundation.StorageLifecycle.Progress
      storageProgress =
          new ca.emilybelnavis.mcmods.economy.foundation.StorageLifecycle.Progress(
              "storage", 0, 0, false);

  @Override
  public void initialize(Path worldDirectory) {
    beginInitialization(worldDirectory);
    while (!advanceInitialization().ready()) {
      /* Standalone/offline callers; production yields between steps. */
    }
  }

  public void beginInitialization(Path worldDirectory) {
    if (owner != null) throw new IllegalStateException("Storage already initialized");
    owner = Thread.currentThread();
    try {
      verifyDriver();
      Path directory = worldDirectory.toAbsolutePath().normalize().resolve("economy");
      StorageFiles.rejectLinks(directory);
      Files.createDirectories(directory);
      for (String file :
          List.of(
              "owner.lock",
              "economy.sqlite",
              "economy.sqlite-wal",
              "economy.sqlite-shm",
              "world-identity",
              "identity.pending",
              "upgrade-backup.properties")) StorageFiles.rejectLinks(directory.resolve(file));
      ownership = WorldOwnership.acquire(directory.resolve("owner.lock"));
      databasePath = directory.resolve("economy.sqlite");
      identityFile = directory.resolve("world-identity");
      pendingFile = directory.resolve("identity.pending");
      boolean existed = Files.exists(databasePath);
      UUID identity = readIdentity(identityFile), pending = readIdentity(pendingFile);
      if (identity != null && !existed)
        throw identity("World identity exists but its database is missing");
      if (!existed && identity == null && pending == null) {
        pending = UUID.randomUUID();
        StorageFiles.writeNew(pendingFile, encodeIdentity(pending));
      }
      if (identity != null && pending != null && !identity.equals(pending))
        throw identity("Conflicting identity intent");
      initialFingerprint = UpgradeBackup.fingerprint(directory);
      connection = opener.open(databasePath, true);
      executePragma("busy_timeout=" + lockTimeoutMillis);
      boolean empty =
          scalarInt("SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'") == 0;
      if (empty) {
        if (identity != null
            || pending == null
            || scalarInt("PRAGMA application_id") != 0
            || scalarInt("PRAGMA user_version") != 0)
          throw new StorageException(
              StorageException.Kind.SCHEMA,
              "Unrecognized or missing schema; no replacement permitted");
        initializingIdentity = pending;
        initializingVersion = 0;
      } else {
        if (scalarInt("PRAGMA application_id") != SchemaMigrations.APPLICATION_ID)
          throw new StorageException(
              StorageException.Kind.SCHEMA, "Legacy or foreign database; no importer supported");
        initializingVersion = scalarInt("PRAGMA user_version");
        if (initializingVersion < SchemaMigrations.MINIMUM_SUPPORTED
            || initializingVersion > SchemaMigrations.CURRENT)
          throw new StorageException(
              StorageException.Kind.SCHEMA, "Unsupported schema " + initializingVersion);
        initializingIdentity = readDatabaseIdentity(initializingVersion);
        if (identity == null && pending == null) throw identity("World-side identity missing");
        if (!initializingIdentity.equals(identity != null ? identity : pending))
          throw identity("Database belongs to another world");
        validateHistory(initializingVersion);
        checkIntegrity();
        if (initializingVersion >= 3) {
          JournalSchema.validate(connection);
          compatibility =
              new JournalCompatibility(connection, catalog, initializingIdentity.toString());
        }
      }
    } catch (SQLException e) {
      cleanupFailure();
      throw StorageException.sql(e);
    } catch (IOException e) {
      cleanupFailure();
      throw new StorageException(
          StorageException.Kind.CORRUPTION_IO, "Cannot initialize world storage", e);
    } catch (LinkageError e) {
      cleanupFailure();
      throw new StorageException(
          StorageException.Kind.CONFIGURATION_DRIVER, "SQLite native library unavailable", e);
    } catch (RuntimeException e) {
      cleanupFailure();
      throw e;
    }
  }

  public ca.emilybelnavis.mcmods.economy.foundation.StorageLifecycle.Progress
      advanceInitialization() {
    checkOwner();
    if (initialized) return storageProgress;
    try {
      if (historyInitializer != null) {
        if (!historyInitializer.step())
          return storageProgress =
              new ca.emilybelnavis.mcmods.economy.foundation.StorageLifecycle.Progress(
                  "owner-history", historyInitializer.cursor(), historyInitializer.target(), false);
        return completeInitialization();
      }
      if (compatibility != null) {
        boolean done = compatibility.step();
        storageProgress =
            new ca.emilybelnavis.mcmods.economy.foundation.StorageLifecycle.Progress(
                "journal-validation", compatibility.validated(), -1, false);
        if (!done) return storageProgress;
        compatibility = null;
        return storageProgress;
      }
      if (initializingVersion > 0 && initializingVersion < SchemaMigrations.CURRENT)
        UpgradeBackup.validate(databasePath.getParent(), initialFingerprint);
      configure();
      if (initializingVersion < SchemaMigrations.CURRENT)
        SchemaMigrations.apply(
            connection,
            initializingIdentity,
            initializingVersion,
            SchemaMigrations.CURRENT,
            SchemaMigrations.REGISTRY);
      JournalSchema.validate(connection);
      RecoverySchema.validate(connection);
      FxSchema.validate(connection);
      AccountSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.institution.InstitutionSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.pricing.PricingSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.interest.InterestSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.storage.TerminalBankingSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.card.CardSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.credit.CreditProfileSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.credit.CreditFacilitySchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.credit.CreditAuditFacts.validateSchema(connection);
      ca.emilybelnavis.mcmods.economy.credit.CreditEvents.validateSchema(connection);
      ca.emilybelnavis.mcmods.economy.loan.LoanSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.loan.LoanDiagnosticSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.loan.LoanEvents.validateSchema(connection);
      ca.emilybelnavis.mcmods.economy.debt.DebtLifecycleSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.debt.DebtDiagnosticSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.debt.DebtLegacySchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.credit.CreditReportingSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.collection.CollectionPresenceSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.autopay.AutopaySchema.validate(connection);
      VillageBranchSchema.validate(connection);
      BranchPlacementSchema.validate(connection);
      BankStaffingSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.simulation.activity.ActivitySchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.simulation.window.WindowSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.simulation.plan.ModelPlanStore.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.market.MarketSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.catalog.CatalogStore.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.catalog.VillageMarketSource.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.stock.StockSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.journal.NpcSettlement.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.settlement.NpcSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.cash.NpcCashSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimeSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.simulation.plan.ActivationModelPlans.validate(connection);
      ca.emilybelnavis.mcmods.economy.clock.FixedRetirements.validate(connection);
      ca.emilybelnavis.mcmods.economy.fx.EffectiveFxSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.simulation.SimulationLifecycle.validate(connection);
      ca.emilybelnavis.mcmods.economy.valuation.ValuationStore.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.pricing.TradeSources.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.pricing.ReferenceTrading.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.settlement.ReferenceNpcSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.settlement.ReferenceInputReturns.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.cash.ReferenceCashSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.simulation.macro.MacroActivation.validate(connection);
      ca.emilybelnavis.mcmods.economy.simulation.macro.MacroSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.simulation.macro.MacroPlans.validate(connection);
      ca.emilybelnavis.mcmods.economy.journal.KioskUpgrades.validate(connection);
      LedgerProjection.validateSchema(connection);
      HoldSchema.validate(connection);
      FxSettlementSchema.validate(connection);
      LedgerHistorySchema.validate(connection);
      OwnerHistorySchema.validate(connection);
      FinancialCalendarSchema.validate(connection);
      historyInitializer = new OwnerHistorySchema.Initializer(connection);
      if (!historyInitializer.step())
        return storageProgress =
            new ca.emilybelnavis.mcmods.economy.foundation.StorageLifecycle.Progress(
                "owner-history", historyInitializer.cursor(), historyInitializer.target(), false);
      return completeInitialization();
    } catch (SQLException e) {
      cleanupFailure();
      throw StorageException.sql(e);
    } catch (IOException e) {
      cleanupFailure();
      throw new StorageException(
          StorageException.Kind.CORRUPTION_IO, "Identity publication failed", e);
    } catch (RuntimeException e) {
      cleanupFailure();
      throw e;
    }
  }

  private ca.emilybelnavis.mcmods.economy.foundation.StorageLifecycle.Progress
      completeInitialization() throws SQLException, IOException {
    if (!Files.exists(identityFile))
      StorageFiles.writeNew(identityFile, encodeIdentity(initializingIdentity));
    if (Files.exists(pendingFile)) {
      Files.delete(pendingFile);
      StorageFiles.forceDirectory(databasePath.getParent());
    }
    snapshot =
        new Snapshot(
            new WorldId(initializingIdentity),
            SchemaMigrations.CURRENT,
            scalar("PRAGMA journal_mode"),
            scalarInt("PRAGMA synchronous"),
            scalarInt("PRAGMA foreign_keys") == 1,
            scalarInt("PRAGMA busy_timeout"));
    try (var statement = connection.createStatement()) {
      statement.executeUpdate("UPDATE runtime_state SET clean_shutdown=0 WHERE singleton=1");
    }
    initialized = true;
    cleanEligible = true;
    return storageProgress =
        new ca.emilybelnavis.mcmods.economy.foundation.StorageLifecycle.Progress(
            "storage-ready", 1, 1, true);
  }

  private CommitObserver commitObserver = (intent, reads, commit) -> commit;

  public void commitObserver(CommitObserver observer) {
    checkOwner();
    commitObserver = java.util.Objects.requireNonNull(observer);
  }

  @Override
  public ca.emilybelnavis.mcmods.economy.journal.JournalEngine.Decision decorateCommit(
      ca.emilybelnavis.mcmods.economy.journal.Journal.Intent intent,
      ca.emilybelnavis.mcmods.economy.journal.JournalEngine.ReadView reads,
      ca.emilybelnavis.mcmods.economy.journal.JournalEngine.Commit commit) {
    var decision = commitObserver.prepare(intent, reads, commit);
    if (decision instanceof ca.emilybelnavis.mcmods.economy.journal.JournalEngine.Commit accepted)
      decision =
          ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle.observe(intent, reads, accepted);
    if (decision instanceof ca.emilybelnavis.mcmods.economy.journal.JournalEngine.Commit accepted
        && reads
            .query(
                "SELECT ready FROM credit_reporting_bootstrap WHERE singleton=1",
                java.util.List.of(),
                1)
            .getFirst()
            .get("ready")
            .equals("1"))
      decision = ca.emilybelnavis.mcmods.economy.credit.CreditReporting.decorate(reads, accepted);
    if (decision instanceof ca.emilybelnavis.mcmods.economy.journal.JournalEngine.Commit accepted)
      decision = ca.emilybelnavis.mcmods.economy.autopay.Autopay.decorate(reads, accepted);
    return decision;
  }

  public void cleanShutdownEligible(boolean eligible) {
    checkOwner();
    cleanEligible = eligible;
  }

  static String encodeIdentity(UUID id) {
    return "economy-world-v1\n" + id + "\n";
  }

  private static UUID readIdentity(Path path) throws IOException {
    if (!Files.exists(path)) return null;
    if (Files.size(path) > 128) throw identity("Invalid identity metadata size");
    String value = Files.readString(path);
    try {
      String[] lines = value.split("\\n");
      if (lines.length != 2 || !lines[0].equals("economy-world-v1"))
        throw new IllegalArgumentException();
      UUID id = UUID.fromString(lines[1]);
      if (!id.toString().equals(lines[1])) throw new IllegalArgumentException();
      return id;
    } catch (IllegalArgumentException e) {
      throw identity("Invalid world identity metadata");
    }
  }

  private static StorageException identity(String message) {
    return new StorageException(StorageException.Kind.IDENTITY, message);
  }

  private UUID readDatabaseIdentity(int version) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT * FROM foundation_metadata")) {
      if (!rows.next()) throw identity("Missing database identity");
      String id = rows.getString("world_id");
      UUID result;
      try {
        result = UUID.fromString(id);
      } catch (IllegalArgumentException e) {
        throw identity("Invalid database identity");
      }
      if (!result.toString().equals(id)) throw identity("Noncanonical database identity");
      if (rows.getInt("singleton") != 1
          || rows.getInt("schema_version") != version
          || !"economy-foundation".equals(rows.getString("product")))
        throw new StorageException(StorageException.Kind.SCHEMA, "Inconsistent schema metadata");
      if (version >= 2 && rows.getInt("identity_protocol") != 1)
        throw identity("Unsupported identity protocol");
      if (rows.next()) throw identity("Multiple identities");
      return result;
    }
  }

  private void validateHistory(int version) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery("SELECT version,name FROM migration_history ORDER BY version")) {
      for (int i = 1; i <= version; i++) {
        if (!rows.next()
            || rows.getInt(1) != i
            || !rows.getString(2).equals(SchemaMigrations.REGISTRY.get(i - 1).name()))
          throw new StorageException(StorageException.Kind.SCHEMA, "Invalid migration history");
      }
      if (rows.next())
        throw new StorageException(StorageException.Kind.SCHEMA, "Unexpected migration history");
    }
  }

  private void checkIntegrity() throws SQLException {
    if (!"ok".equals(scalar("PRAGMA quick_check")))
      throw new StorageException(
          StorageException.Kind.CORRUPTION_IO, "SQLite integrity check failed");
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check")) {
      if (rows.next())
        throw new StorageException(
            StorageException.Kind.CORRUPTION_IO, "Broken storage references");
    }
  }

  private void configure() throws SQLException {
    executePragma("journal_mode=WAL");
    executePragma("synchronous=FULL");
    executePragma("foreign_keys=ON");
    if (!"wal".equalsIgnoreCase(scalar("PRAGMA journal_mode"))
        || scalarInt("PRAGMA synchronous") != 2
        || scalarInt("PRAGMA foreign_keys") != 1
        || scalarInt("PRAGMA busy_timeout") != lockTimeoutMillis)
      throw new StorageException(
          StorageException.Kind.CONFIGURATION_DRIVER,
          "SQLite durability settings were not applied");
  }

  // Callers supply fixed SQLite settings. The only interpolated value is an int timeout.
  @SuppressWarnings("java:S2077")
  private void executePragma(String pragma) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA " + pragma);
    }
  }

  private String scalar(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      if (!rows.next()) throw new SQLException("Missing scalar result");
      return rows.getString(1);
    }
  }

  private int scalarInt(String sql) throws SQLException {
    return Integer.parseInt(scalar(sql));
  }

  @Override
  public ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog recordCatalog() {
    return catalog;
  }

  @Override
  public Snapshot snapshot() {
    checkOwner();
    return Objects.requireNonNull(snapshot, "Storage not ready");
  }

  @Override
  public long lastCommittedSequence() {
    checkOwner();
    if (connection == null || inTransaction)
      throw new IllegalStateException("Checkpoint unavailable");
    try {
      return Long.parseLong(scalar("SELECT COALESCE(MAX(sequence),0) FROM journal_transactions"));
    } catch (SQLException error) {
      throw StorageException.sql(error);
    }
  }

  private void checkOwner() {
    if (owner != Thread.currentThread())
      throw new IllegalStateException("Requires owning storage thread");
    if (poisoned)
      throw new StorageException(
          StorageException.Kind.CORRUPTION_IO, "Connection unavailable after failed transaction");
  }

  @Override
  public <T> T transaction(Work<T> work) {
    checkOwner();
    if (connection == null || snapshot == null || inTransaction)
      throw new IllegalStateException("Transaction unavailable");
    inTransaction = true;
    SqlTransaction transaction = new SqlTransaction();
    boolean commitAttempted = false;
    boolean committed = false;
    try {
      connection.setAutoCommit(false);
      T result = work.run(transaction);
      commitAttempted = true;
      connection.commit();
      committed = true;
      connection.setAutoCommit(true);
      return result;
    } catch (Throwable error) {
      if (committed) {
        poisoned = true;
        throw new StorageException(
            StorageException.Kind.UNKNOWN_COMMIT,
            "Commit succeeded but connection cleanup failed; resolve original operation",
            error);
      }
      try {
        connection.rollback();
      } catch (SQLException rollback) {
        poisoned = true;
        error.addSuppressed(rollback);
        throw new StorageException(
            StorageException.Kind.UNSAFE_CONNECTION, "Rollback failed; connection disabled", error);
      }
      if (commitAttempted) {
        poisoned = true;
        throw new StorageException(
            StorageException.Kind.UNKNOWN_COMMIT,
            "Commit outcome uncertain; resolve original operation",
            error);
      }
      if (error instanceof StorageException storageError) poisoned |= storageError.fatal();
      if (!poisoned) {
        try {
          connection.setAutoCommit(true);
        } catch (SQLException cleanup) {
          poisoned = true;
          error.addSuppressed(cleanup);
          throw new StorageException(
              StorageException.Kind.UNSAFE_CONNECTION,
              "Transaction cleanup failed; connection disabled",
              error);
        }
      }
      if (error instanceof Error fatal) throw fatal;
      if (error instanceof RuntimeException runtime) throw runtime;
      if (error instanceof SQLException sql) {
        poisoned = true;
        throw StorageException.sql(sql);
      }
      throw new StorageException(
          StorageException.Kind.CORRUPTION_IO, "Transaction callback failed", error);
    } finally {
      transaction.active = false;
      inTransaction = false;
    }
  }

  @Override
  public void recover() {
    if (owner != Thread.currentThread() || inTransaction || ownership == null || snapshot == null)
      throw new IllegalStateException("Recovery requires the owning idle storage worker");
    poisoned = true;
    try {
      if (connection != null) connection.close();
      connection = null;
      if (!Files.isRegularFile(databasePath))
        throw identity("Database missing during recovery; no replacement permitted");
      StorageFiles.rejectLinks(databasePath);
      if (!snapshot
          .worldId()
          .value()
          .equals(readIdentity(databasePath.resolveSibling("world-identity"))))
        throw identity("World identity changed during recovery");
      connection = opener.open(databasePath, false);
      executePragma("busy_timeout=" + lockTimeoutMillis);
      if (scalarInt("PRAGMA application_id") != SchemaMigrations.APPLICATION_ID
          || scalarInt("PRAGMA user_version") != SchemaMigrations.CURRENT
          || !snapshot.worldId().value().equals(readDatabaseIdentity(SchemaMigrations.CURRENT)))
        throw identity("Storage changed during recovery");
      validateHistory(SchemaMigrations.CURRENT);
      checkIntegrity();
      JournalSchema.validate(connection);
      RecoverySchema.validate(connection);
      FxSchema.validate(connection);
      AccountSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.institution.InstitutionSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.pricing.PricingSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.interest.InterestSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.storage.TerminalBankingSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.card.CardSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.credit.CreditProfileSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.credit.CreditFacilitySchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.credit.CreditAuditFacts.validateSchema(connection);
      ca.emilybelnavis.mcmods.economy.credit.CreditEvents.validateSchema(connection);
      ca.emilybelnavis.mcmods.economy.loan.LoanSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.loan.LoanDiagnosticSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.loan.LoanEvents.validateSchema(connection);
      ca.emilybelnavis.mcmods.economy.debt.DebtLifecycleSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.debt.DebtDiagnosticSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.debt.DebtLegacySchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.credit.CreditReportingSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.collection.CollectionPresenceSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.autopay.AutopaySchema.validate(connection);
      VillageBranchSchema.validate(connection);
      BranchPlacementSchema.validate(connection);
      BankStaffingSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.simulation.activity.ActivitySchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.simulation.window.WindowSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.simulation.plan.ModelPlanStore.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.market.MarketSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.catalog.CatalogStore.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.catalog.VillageMarketSource.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.stock.StockSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.journal.NpcSettlement.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.settlement.NpcSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.cash.NpcCashSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimeSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.simulation.plan.ActivationModelPlans.validate(connection);
      ca.emilybelnavis.mcmods.economy.clock.FixedRetirements.validate(connection);
      ca.emilybelnavis.mcmods.economy.fx.EffectiveFxSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.simulation.SimulationLifecycle.validate(connection);
      ca.emilybelnavis.mcmods.economy.valuation.ValuationStore.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.pricing.TradeSources.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.pricing.ReferenceTrading.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.settlement.ReferenceNpcSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.settlement.ReferenceInputReturns.validate(connection);
      ca.emilybelnavis.mcmods.economy.trade.cash.ReferenceCashSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.simulation.macro.MacroActivation.validate(connection);
      ca.emilybelnavis.mcmods.economy.simulation.macro.MacroSchema.validate(connection);
      ca.emilybelnavis.mcmods.economy.simulation.macro.MacroPlans.validate(connection);
      ca.emilybelnavis.mcmods.economy.journal.KioskUpgrades.validate(connection);
      LedgerProjection.validateSchema(connection);
      HoldSchema.validate(connection);
      FxSettlementSchema.validate(connection);
      LedgerHistorySchema.validate(connection);
      OwnerHistorySchema.validateFacts(connection);
      FinancialCalendarSchema.validate(connection);
      var validation =
          new JournalCompatibility(connection, catalog, snapshot.worldId().value().toString());
      while (!validation.step()) {
        /* Recovery holds the sole worker; reads are paged and interruptible. */
      }
      configure();
      poisoned = false;
      QUARANTINED.remove(this);
    } catch (IOException | SQLException | RuntimeException error) {
      QUARANTINED.add(this);
      throw new StorageException(
          StorageException.Kind.UNSAFE_CONNECTION,
          "Recovery failed; writes disabled and world ownership retained",
          error);
    }
  }

  private final class SqlTransaction implements Transaction {
    private boolean active = true;

    private PreparedStatement prepare(String sql, List<String> parameters) throws SQLException {
      checkOwner();
      if (!active || !inTransaction) throw new IllegalStateException("Transaction has ended");
      if (sql.length() > 65536 || parameters.size() > 256)
        throw new IllegalArgumentException("Statement bounds");
      if (sql.indexOf(';') >= 0
          || !sql.stripLeading()
              .toUpperCase(Locale.ROOT)
              .matches("(?s)(SELECT|INSERT|UPDATE|DELETE|WITH)\\b.*"))
        throw new IllegalArgumentException(
            "Only a single data statement is allowed inside this transaction");
      PreparedStatement statement = connection.prepareStatement(sql);
      try {
        int parameterBytes = 0;
        for (int i = 0; i < parameters.size(); i++) {
          String parameter = parameters.get(i);
          if (parameter != null)
            parameterBytes =
                Math.addExact(parameterBytes, parameter.getBytes(StandardCharsets.UTF_8).length);
          if (parameterBytes > 1_048_576) throw new IllegalArgumentException("Parameter bounds");
          statement.setString(i + 1, parameter);
        }
        return statement;
      } catch (SQLException | RuntimeException e) {
        statement.close();
        throw e;
      }
    }

    public int execute(String sql, List<String> parameters) {
      try (PreparedStatement statement = prepare(sql, parameters)) {
        return statement.executeUpdate();
      } catch (SQLException e) {
        throw StorageException.sql(e);
      }
    }

    public List<Map<String, String>> query(String sql, List<String> parameters, int maxRows) {
      if (maxRows < 1 || maxRows > 1024) throw new IllegalArgumentException("Row bounds");
      try (PreparedStatement statement = prepare(sql, parameters);
          ResultSet rows = statement.executeQuery()) {
        List<Map<String, String>> result = new ArrayList<>();
        int bytes = 0;
        while (rows.next()) {
          if (result.size() == maxRows)
            throw new IllegalArgumentException("Result row limit exceeded");
          Map<String, String> row = new LinkedHashMap<>();
          for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
            String value = rows.getString(i);
            if (value != null)
              bytes = Math.addExact(bytes, value.getBytes(StandardCharsets.UTF_8).length);
            if (bytes > 1_048_576) throw new IllegalArgumentException("Result byte limit exceeded");
            row.put(rows.getMetaData().getColumnLabel(i), value);
          }
          result.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(result);
      } catch (SQLException e) {
        throw StorageException.sql(e);
      }
    }
  }

  private void cleanupFailure() {
    try {
      close();
    } catch (Exception ignored) {
      /* Original failure remains authoritative. */
    }
  }

  @Override
  public void close() throws SQLException, IOException {
    if (owner != null && owner != Thread.currentThread())
      throw new IllegalStateException("Requires owning storage thread");
    try {
      if (connection != null) {
        if (initialized && cleanEligible && !poisoned) {
          try (var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE runtime_state SET clean_shutdown=1 WHERE singleton=1");
          }
        }
        connection.close();
      }
      connection = null;
      if (ownership != null) {
        ownership.close();
        ownership = null;
      }
      snapshot = null;
      QUARANTINED.remove(this);
    } catch (SQLException | IOException error) {
      poisoned = true;
      QUARANTINED.add(this);
      throw error;
    }
  }
}
