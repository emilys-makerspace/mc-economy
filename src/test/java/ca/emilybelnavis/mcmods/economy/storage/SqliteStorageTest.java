package ca.emilybelnavis.mcmods.economy.storage;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteStorageTest {
  @TempDir Path root;

  @org.junit.jupiter.api.BeforeEach
  void canonicalFixtureRoot() throws Exception {
    root = root.toRealPath();
  }

  private Path world(String name) throws Exception {
    return Files.createDirectory(root.resolve(name));
  }

  private SqliteStorage open(Path world) {
    var store = new SqliteStorage();
    store.initialize(world);
    return store;
  }

  private void failure(StorageException.Kind kind, Path world) {
    var error = assertThrows(StorageException.class, () -> open(world));
    assertEquals(kind, error.kind(), error.toString());
  }

  private UUID versionOne(Path world) throws Exception {
    Path directory = Files.createDirectories(world.resolve("economy"));
    UUID id = UUID.randomUUID();
    try (Connection connection =
        DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("economy.sqlite"))) {
      SchemaMigrations.apply(connection, id, 0, 1, SchemaMigrations.REGISTRY.subList(0, 1));
    }
    StorageFiles.writeNew(directory.resolve("world-identity"), SqliteStorage.encodeIdentity(id));
    Files.writeString(world.resolve("level.dat"), "complete-world fixture");
    Files.createDirectories(world.resolve("region"));
    Files.writeString(world.resolve("region/test.mca"), "fixture region");
    return id;
  }

  @Test
  void freshReopenAndWorldIsolation() throws Exception {
    Path one = world("one");
    Path two = world("two");
    var first = open(one);
    var snapshot = first.snapshot();
    assertEquals("wal", snapshot.journalMode());
    assertEquals(2, snapshot.synchronous());
    assertTrue(snapshot.foreignKeys());
    assertEquals(5000, snapshot.busyTimeoutMillis());
    var rows =
        first.transaction(
            tx ->
                tx.query(
                    "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name",
                    List.of(),
                    256));
    assertEquals(
        List.of(
            "account_catalog",
            "activation_model_plans",
            "activity_activation",
            "activity_batches",
            "activity_capacity",
            "activity_capacity_history",
            "activity_capacity_totals",
            "activity_capacity_totals_history",
            "activity_day_counters",
            "activity_model_plans",
            "activity_observations",
            "autopay_bootstrap",
            "autopay_settings",
            "autopay_statements",
            "autopay_windows",
            "bank_account_providers",
            "bank_provider_leases",
            "bank_provider_migration",
            "bank_provider_switches",
            "banker_employee_history",
            "banker_slot_events",
            "banker_slots",
            "cash_issuance",
            "collection_agent_state",
            "credit_facilities",
            "credit_facility_tokens",
            "credit_inquiry_applications",
            "credit_inquiry_groups",
            "credit_profiles",
            "credit_reporting_bootstrap",
            "credit_reporting_sources",
            "credit_score_heads",
            "credit_score_history",
            "credit_score_tasks",
            "credit_statements",
            "credit_utilization_days",
            "currency_prime_activation",
            "currency_prime_completions",
            "currency_prime_policies",
            "custody_cases",
            "custody_cash_reservations",
            "debt_bootstrap",
            "debt_cases",
            "debt_claim_totals",
            "debt_default_heads",
            "debt_due_queue",
            "debt_facility_states",
            "debt_legacy_reconciliation",
            "debt_obligations",
            "debt_reports",
            "debt_sale_attempts",
            "deposit_cash_reservations",
            "deposit_returns",
            "deposit_yield_activation",
            "deposit_yield_claims",
            "deposit_yield_due",
            "deposits",
            "economic_metadata",
            "economic_systems",
            "economic_updates",
            "economic_versions",
            "effective_fx_snapshots",
            "financial_calendar",
            "financial_deadline_events",
            "financial_deadline_providers",
            "financial_deadline_versions",
            "fixed_system_retirements",
            "foundation_metadata",
            "institution_capital_allocations",
            "institution_capital_manifest",
            "institution_financial_accounts",
            "institution_parties",
            "issued_card_fee_quotes",
            "issued_cards",
            "journal_records",
            "journal_retry_intents",
            "journal_transactions",
            "kiosk_geometry_upgrades",
            "ledger_balances",
            "ledger_fx_quotes",
            "ledger_holds",
            "loan_application_ids",
            "loan_installments",
            "loan_schedule",
            "loans",
            "macro_activations",
            "macro_control_policies",
            "macro_rate_completions",
            "macro_rate_plans",
            "macro_window_closes",
            "macro_window_opens",
            "migration_history",
            "npc_bridges",
            "npc_cash_adapter",
            "npc_cash_quotes",
            "npc_cash_receipts",
            "npc_cash_returns",
            "npc_trade_adapter",
            "npc_trade_quotes",
            "npc_trade_receipts",
            "operation_outcomes",
            "overdraft_accounts",
            "owner_history",
            "owner_history_state",
            "payment_clock",
            "payment_invoices",
            "payment_merchants",
            "payment_receipts",
            "pricing_activation",
            "pricing_policies",
            "pricing_tier_history",
            "pricing_tiers",
            "pricing_valuation_sources",
            "projection_cells",
            "projection_generations",
            "projection_heads",
            "reference_activations",
            "reference_cash_adapter",
            "reference_cash_quotes",
            "reference_cash_receipts",
            "reference_cash_returns",
            "reference_npc_trade_adapter",
            "reference_npc_trade_quotes",
            "reference_npc_trade_receipts",
            "reference_trade_price_events",
            "reference_trade_prices",
            "register_merchants",
            "register_prices",
            "register_sales",
            "register_stock_locks",
            "register_stock_sources",
            "runtime_state",
            "simulation_production",
            "sqlite_sequence",
            "terminal_bank_bindings",
            "terminal_generations",
            "terminal_settings",
            "trade_catalog_offers",
            "trade_catalogs",
            "trade_market_bindings",
            "trade_market_carries",
            "trade_market_cells",
            "trade_market_dimensions",
            "trade_market_events",
            "trade_market_indexes",
            "trade_market_prices",
            "trade_market_searches",
            "trade_market_source_events",
            "trade_market_source_sync",
            "trade_markets",
            "trade_source_active",
            "trade_source_events",
            "trade_source_retirements",
            "trade_stock_events",
            "trade_stock_markets",
            "trade_stock_progressions",
            "trade_stock_traders",
            "valuation_active",
            "valuation_chunks",
            "valuation_manifests",
            "village_branch_anchors",
            "village_branch_events",
            "village_branches",
            "village_placement_attempts",
            "village_plans",
            "village_provider_fills",
            "withdrawals"),
        rows.stream().map(r -> r.get("name")).toList());
    assertThrows(UnsupportedOperationException.class, () -> rows.getFirst().put("name", "changed"));
    first.close();
    var reopened = open(one);
    assertEquals(snapshot.worldId(), reopened.snapshot().worldId());
    reopened.close();
    var second = open(two);
    assertNotEquals(snapshot.worldId(), second.snapshot().worldId());
    second.close();
    assertFalse(Files.exists(one.resolve("economy/identity.pending")));
  }

  @Test
  void wrongIdentityAndMissingDatabaseNeverReplaced() throws Exception {
    Path world = world("wrong");
    var store = open(world);
    store.close();
    Path database = world.resolve("economy/economy.sqlite");
    String before = StorageFiles.hash(database);
    Files.writeString(
        world.resolve("economy/world-identity"), SqliteStorage.encodeIdentity(UUID.randomUUID()));
    failure(StorageException.Kind.IDENTITY, world);
    assertEquals(before, StorageFiles.hash(database));
    Files.move(database, world.resolve("preserved.sqlite"));
    failure(StorageException.Kind.IDENTITY, world);
    assertFalse(Files.exists(database));
  }

  @Test
  void corruptLegacyFutureAndEmptyExistingAreRejected() throws Exception {
    Path corrupt = world("corrupt");
    Files.createDirectory(corrupt.resolve("economy"));
    Files.writeString(corrupt.resolve("economy/economy.sqlite"), "not sqlite".repeat(100));
    String corruptHash = StorageFiles.hash(corrupt.resolve("economy/economy.sqlite"));
    failure(StorageException.Kind.CORRUPTION_IO, corrupt);
    assertEquals(corruptHash, StorageFiles.hash(corrupt.resolve("economy/economy.sqlite")));
    Path legacy = world("legacy");
    Files.createDirectory(legacy.resolve("economy"));
    try (var c =
            DriverManager.getConnection("jdbc:sqlite:" + legacy.resolve("economy/economy.sqlite"));
        var s = c.createStatement()) {
      s.execute("CREATE TABLE old_accounts(id TEXT)");
      s.execute("INSERT INTO old_accounts VALUES('preserve')");
    }
    String oldHash = StorageFiles.hash(legacy.resolve("economy/economy.sqlite"));
    failure(StorageException.Kind.SCHEMA, legacy);
    assertEquals(oldHash, StorageFiles.hash(legacy.resolve("economy/economy.sqlite")));
    Path future = world("future");
    var store = open(future);
    store.close();
    try (var c =
            DriverManager.getConnection("jdbc:sqlite:" + future.resolve("economy/economy.sqlite"));
        var s = c.createStatement()) {
      s.execute("PRAGMA user_version=999");
    }
    failure(StorageException.Kind.SCHEMA, future);
    Path empty = world("empty");
    Files.createDirectory(empty.resolve("economy"));
    Files.createFile(empty.resolve("economy/economy.sqlite"));
    failure(StorageException.Kind.SCHEMA, empty);
  }

  @Test
  void interruptedIdentityPublicationRecoversSameWorld() throws Exception {
    Path world = world("intent");
    Path directory = Files.createDirectory(world.resolve("economy"));
    UUID id = UUID.randomUUID();
    StorageFiles.writeNew(directory.resolve("identity.pending"), SqliteStorage.encodeIdentity(id));
    var first = open(world);
    assertEquals(id, first.snapshot().worldId().value());
    first.close();
    Files.move(directory.resolve("world-identity"), directory.resolve("identity.pending"));
    var resumed = open(world);
    assertEquals(id, resumed.snapshot().worldId().value());
    resumed.close();
    assertTrue(Files.exists(directory.resolve("world-identity")));
    assertFalse(Files.exists(directory.resolve("identity.pending")));
  }

  @Test
  void upgradesRequireOfflineCompleteBackup() throws Exception {
    Path world = world("upgrade");
    UUID id = versionOne(world);
    failure(StorageException.Kind.SCHEMA, world);
    Path backup = UpgradeBackup.prepare(world);
    assertEquals("complete-world fixture", Files.readString(backup.resolve("world/level.dat")));
    assertEquals("fixture region", Files.readString(backup.resolve("world/region/test.mca")));
    var store = open(world);
    assertEquals(id, store.snapshot().worldId().value());
    assertEquals(SchemaMigrations.CURRENT, store.snapshot().schemaVersion());
    var history =
        store.transaction(
            tx ->
                tx.query(
                    "SELECT version FROM migration_history ORDER BY version",
                    List.of(),
                    SchemaMigrations.CURRENT));
    assertEquals(
        java.util.stream.IntStream.rangeClosed(1, SchemaMigrations.CURRENT)
            .mapToObj(Integer::toString)
            .toList(),
        history.stream().map(r -> r.get("version")).toList());
    store.close();
  }

  @Test
  void changedBackupAndRunningWorldCannotAuthorizeUpgrade() throws Exception {
    Path world = world("backup");
    versionOne(world);
    try (WorldOwnership ignored = WorldOwnership.acquire(world.resolve("session.lock"))) {
      assertThrows(StorageException.class, () -> UpgradeBackup.prepare(world));
    }
    Path backup = UpgradeBackup.prepare(world);
    Files.writeString(backup.resolve("world/region/test.mca"), "tampered");
    failure(StorageException.Kind.SCHEMA, world);
    UpgradeBackup.prepare(world);
    try (var c =
            DriverManager.getConnection("jdbc:sqlite:" + world.resolve("economy/economy.sqlite"));
        var s = c.createStatement()) {
      s.execute("UPDATE foundation_metadata SET product='economy-foundation'");
      s.execute("CREATE TABLE changed_since_backup(id TEXT)");
    }
    failure(StorageException.Kind.SCHEMA, world);
  }

  @Test
  void pendingMigrationChainRollsBackTogether() throws Exception {
    Path world = world("rollback");
    UUID id = versionOne(world);
    try (var c =
        DriverManager.getConnection("jdbc:sqlite:" + world.resolve("economy/economy.sqlite"))) {
      var migrations = new ArrayList<>(SchemaMigrations.REGISTRY);
      int failingVersion = SchemaMigrations.CURRENT + 1;
      migrations.add(
          new SchemaMigrations.Migration(
              failingVersion,
              "failure fixture",
              List.of("CREATE TABLE transient_fixture(id TEXT)", "INVALID SQL")));
      StorageException error =
          assertThrows(
              StorageException.class,
              () -> SchemaMigrations.apply(c, id, 1, failingVersion, migrations));
      assertEquals(StorageException.Kind.MIGRATION, error.kind());
      try (var s = c.createStatement();
          var r = s.executeQuery("PRAGMA user_version")) {
        assertTrue(r.next());
        assertEquals(1, r.getInt(1));
      }
      try (var s = c.createStatement();
          var r = s.executeQuery("SELECT count(*) FROM migration_history")) {
        assertTrue(r.next());
        assertEquals(1, r.getInt(1));
      }
      try (var s = c.createStatement();
          var r =
              s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='transient_fixture'")) {
        assertTrue(r.next());
        assertEquals(0, r.getInt(1));
      }
      try (var s = c.createStatement();
          var r = s.executeQuery("PRAGMA table_info(foundation_metadata)")) {
        while (r.next()) assertNotEquals("identity_protocol", r.getString("name"));
      }
    }
    UpgradeBackup.prepare(world);
    var recovered = open(world);
    recovered.close();
  }

  @Test
  void ownershipIsHeldAcrossProcesses() throws Exception {
    Path world = world("process");
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp",
                System.getProperty("economy.test.classpath"),
                StorageProcessProbe.class.getName(),
                world.toString())
            .redirectErrorStream(true)
            .start();
    try {
      var line =
          CompletableFuture.supplyAsync(
                  () -> {
                    try {
                      return new java.io.BufferedReader(
                              new java.io.InputStreamReader(process.getInputStream()))
                          .readLine();
                    } catch (Exception e) {
                      throw new CompletionException(e);
                    }
                  })
              .get(15, TimeUnit.SECONDS);
      assertEquals("READY", line);
      failure(StorageException.Kind.LOCK_TIMEOUT, world);
      process.getOutputStream().close();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
      assertEquals(0, process.exitValue());
      var reopened = open(world);
      reopened.close();
    } finally {
      if (process.isAlive()) {
        process.destroy();
        process.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void lockTimeoutAndTransactionRollback() throws Exception {
    Path world = world("busy");
    var store = open(world);
    try (var other =
            DriverManager.getConnection("jdbc:sqlite:" + world.resolve("economy/economy.sqlite"));
        var lock = other.createStatement()) {
      lock.execute("BEGIN IMMEDIATE");
      long started = System.nanoTime();
      StorageException error =
          assertThrows(
              StorageException.class,
              () ->
                  store.transaction(
                      tx ->
                          tx.execute(
                              "UPDATE foundation_metadata SET identity_protocol=1", List.of())));
      assertEquals(StorageException.Kind.LOCK_TIMEOUT, error.kind());
      assertTrue(System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(4));
      assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(9));
      lock.execute("ROLLBACK");
    }
    assertThrows(
        IllegalStateException.class,
        () ->
            store.transaction(
                tx -> {
                  tx.execute(
                      "UPDATE migration_history SET name=? WHERE version=2",
                      List.of("should roll back"));
                  throw new IllegalStateException("fixture abort");
                }));
    assertEquals(
        SchemaMigrations.REGISTRY.get(1).name(),
        store
            .transaction(
                tx -> tx.query("SELECT name FROM migration_history WHERE version=2", List.of(), 1))
            .getFirst()
            .get("name"));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.transaction(tx -> tx.query("SELECT 1", List.of(), 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.transaction(tx -> tx.query("SELECT 1 UNION ALL SELECT 2", List.of(), 1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.transaction(tx -> tx.query("SELECT ?", List.of("x".repeat(1_048_577)), 1)));
    var escaped = store.transaction(tx -> tx);
    assertThrows(IllegalStateException.class, () -> escaped.query("SELECT 1", List.of(), 1));
    CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class, store::snapshot))
        .join();
    store.close();
  }

  @Test
  void unwritablePathAndMissingDriverAreTyped() throws Exception {
    Path world = world("io");
    Files.writeString(world.resolve("economy"), "not a directory");
    failure(StorageException.Kind.CORRUPTION_IO, world);
    assertEquals(
        StorageException.Kind.CONFIGURATION_DRIVER,
        assertThrows(
                StorageException.class, () -> SqliteStorage.loadDriver("missing.fixture.Driver"))
            .kind());
  }

  @Test
  void identifierTypesAndValidation() {
    UUID id = UUID.randomUUID();
    assertNotEquals(new Identifiers.PlayerId(id), new Identifiers.OrganizationId(id));
    assertNotEquals(new Identifiers.AccountId(id), new Identifiers.OperationId(id));
    assertEquals("USD", new Identifiers.CurrencyCode("USD").value());
    assertThrows(IllegalArgumentException.class, () -> new Identifiers.CurrencyCode("usd"));
    assertThrows(IllegalArgumentException.class, () -> new Identifiers.JournalSequence(0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Identifiers.CatalogId(Identifiers.CatalogKind.LISTING, "Display Name"));
  }

  @Test
  void readOnlyStorageDirectoryFailsWithoutDatabase() throws Exception {
    Path world = world("readonly");
    Path directory = Files.createDirectory(world.resolve("economy"));
    var permissions = Files.getPosixFilePermissions(directory);
    try {
      Files.setPosixFilePermissions(
          directory, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
      failure(StorageException.Kind.CORRUPTION_IO, world);
      assertFalse(Files.exists(directory.resolve("economy.sqlite")));
    } finally {
      Files.setPosixFilePermissions(directory, permissions);
    }
  }

  @Test
  void fatalErrorsRollbackAndTransactionControlIsRejected() throws Exception {
    Path world = world("atomic");
    var store = open(world);
    assertThrows(
        AssertionError.class,
        () ->
            store.transaction(
                tx -> {
                  tx.execute(
                      "UPDATE migration_history SET name=? WHERE version=2",
                      List.of("must roll back"));
                  throw new AssertionError("fixture interruption");
                }));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.transaction(tx -> tx.execute("COMMIT", List.of())));
    assertEquals(
        SchemaMigrations.REGISTRY.get(1).name(),
        store
            .transaction(
                tx -> tx.query("SELECT name FROM migration_history WHERE version=2", List.of(), 1))
            .getFirst()
            .get("name"));
    store.close();
  }
}
