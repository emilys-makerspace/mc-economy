package ca.emilybelnavis.mcmods.economy.storage;

import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Ordered trusted SQL; the complete pending chain shares one transaction. */
final class SchemaMigrations {
    static final int MINIMUM_SUPPORTED = 1;
    static final int CURRENT = 52;
    static final int APPLICATION_ID = 0x45434F4E;
    record Migration(int version, String name, List<String> statements) {
        Migration { statements = List.copyOf(statements); }
    }
    static final List<Migration> REGISTRY = List.of(
            new Migration(1, "foundation identity and migration history", List.of(
                    "CREATE TABLE foundation_metadata (singleton INTEGER PRIMARY KEY CHECK(singleton=1), world_id TEXT NOT NULL, schema_version INTEGER NOT NULL, product TEXT NOT NULL CHECK(product='economy-foundation'))",
                    "CREATE TABLE migration_history (version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at_utc TEXT NOT NULL)")),
            new Migration(2, "explicit world metadata protocol", List.of(
                    "ALTER TABLE foundation_metadata ADD COLUMN identity_protocol INTEGER NOT NULL DEFAULT 1 CHECK(identity_protocol=1)")),
            new Migration(3, "immutable journal and durable operation outcomes", JournalSchema.statements()),
            new Migration(4, "projection generations and deterministic economic checkpoints", RecoverySchema.statements()),
            new Migration(5, "bounded FX publication and policy lookups", FxSchema.statements()),
            new Migration(6, "player account catalog and constraints", AccountSchema.statements()),
            new Migration(7, "ledger balance projection", List.of(LedgerProjection.CREATE_TABLE)),
            new Migration(8, "ledger holds", HoldSchema.statements()),
            new Migration(9, "durable FX quote consumption", FxSettlementSchema.statements()),
            new Migration(10, "indexed account posting history", List.of(LedgerHistorySchema.INDEX)),
            new Migration(11, "merchant payments and authorization deadlines", List.of(
                    "CREATE TABLE payment_merchants(actor TEXT PRIMARY KEY, enabled INTEGER NOT NULL CHECK(enabled IN (0,1)))",
                    "CREATE TABLE payment_clock(singleton INTEGER PRIMARY KEY CHECK(singleton=1), millis INTEGER NOT NULL)",
                    "INSERT INTO payment_clock VALUES(1,0)",
                    "CREATE TABLE payment_invoices(id TEXT PRIMARY KEY, merchant TEXT NOT NULL, payer TEXT NOT NULL, source TEXT NOT NULL, target TEXT NOT NULL, currency TEXT NOT NULL, units INTEGER NOT NULL, state TEXT NOT NULL, hold TEXT, deadline INTEGER)",
                    "CREATE INDEX payment_expiry ON payment_invoices(state,deadline,id)",
                    "CREATE TABLE payment_receipts(operation_id TEXT PRIMARY KEY, invoice_id TEXT NOT NULL REFERENCES payment_invoices(id), action TEXT NOT NULL, UNIQUE(invoice_id,action))",
                    "CREATE TABLE journal_retry_intents(operation_id TEXT PRIMARY KEY, actor TEXT NOT NULL, service TEXT NOT NULL, operation_type TEXT NOT NULL, canonical_request TEXT NOT NULL)")),
            new Migration(12, "durable custody and owner claims", CustodySchema.statements()),
            new Migration(13, "world-bound cash issuance quantities", CashIssuanceSchema.statements()),
            new Migration(14, "generation-bound terminal policy", TerminalPolicySchema.statements()),
            new Migration(15, "reserved deposits and original cash returns",
                    java.util.stream.Stream.concat(DepositSchema.statements().stream(),
                            DepositReturnSchema.statements().stream()).toList()),
            new Migration(16, "exact withdrawals and committed delivery intents", WithdrawalSchema.statements()),
            new Migration(17, "owner-priced register sales and stock custody", RegisterSalesSchema.statements()),
            new Migration(18, "financial calendar and paged domain deadlines", FinancialCalendarSchema.statements()),
            new Migration(19, "minor-unit CHF posting history", List.of("DROP INDEX ledger_posting_account", LedgerHistorySchema.V2_INDEX)),
            new Migration(20, "institution capital and branch-bound account providers", institutionStatements()),
            new Migration(21,"wealth pricing and funded deposit yield",pricingStatements()),
            new Migration(22,"explicit bank terminal binding and legacy migration",TerminalBankingSchema.statements()),
            new Migration(23,"owner-bound issued debit card lifecycle",ca.emilybelnavis.mcmods.economy.card.CardSchema.statements()),
            new Migration(24,"dormant credit profiles and score policy history",ca.emilybelnavis.mcmods.economy.credit.CreditProfileSchema.statements()),
            new Migration(25,"funded credit facilities and immutable statements",ca.emilybelnavis.mcmods.economy.credit.CreditFacilitySchema.statements()),
            new Migration(26,"bounded credit diagnostics and immutable servicing witnesses",ca.emilybelnavis.mcmods.economy.credit.CreditAuditFacts.statements()),
            new Migration(27,"ordered immutable credit lifecycle event source",List.of(ca.emilybelnavis.mcmods.economy.credit.CreditEvents.INDEX)),
            new Migration(28,"funded fixed loans and monthly schedules",ca.emilybelnavis.mcmods.economy.loan.LoanSchema.statements()),
            new Migration(29,"bounded immutable loan diagnostics",List.of(ca.emilybelnavis.mcmods.economy.loan.LoanDiagnosticSchema.INDEX)),
            new Migration(30,"ordered immutable loan obligation source",List.of(ca.emilybelnavis.mcmods.economy.loan.LoanEvents.INDEX)),
            new Migration(31,"shared debt obligations reporting and collection clocks",ca.emilybelnavis.mcmods.economy.debt.DebtLifecycleSchema.statements()),
            new Migration(32,"bounded debt diagnostics",ca.emilybelnavis.mcmods.economy.debt.DebtDiagnosticSchema.statements()),
            new Migration(33,"accepted debt reporting and recurring score sources",debtAcceptanceStatements()),
            new Migration(34,"collection presentation leases and death cooldowns",ca.emilybelnavis.mcmods.economy.collection.CollectionPresenceSchema.statements()),
            new Migration(35,"consented automatic payments and exact statement targets",ca.emilybelnavis.mcmods.economy.autopay.AutopaySchema.statements()),
            new Migration(36,"durable village branches and trusted hardware activation",VillageBranchSchema.statements()),
            new Migration(37,"recoverable legacy kiosk geometry upgrades",ca.emilybelnavis.mcmods.economy.journal.KioskUpgrades.statements()),
            new Migration(38,"versioned branch placement attempts and safe terrain retry",BranchPlacementSchema.statements()),
            new Migration(39,"durable bank employee assignments and loaded respawn progress",BankStaffingSchema.statements()),
            new Migration(40,"atomic semantic activity attribution and capacity summaries",ca.emilybelnavis.mcmods.economy.simulation.activity.ActivitySchema.statements()),
            new Migration(41,"immutable activity windows and deterministic model plans",simulationStatements()),
            new Migration(42,"durable trade markets catalogs and detached price state",tradeStatements()),
            new Migration(43,"trader stock loaded ticks and original progression",ca.emilybelnavis.mcmods.economy.trade.stock.StockSchema.statements()),
            new Migration(44,"atomic account NPC settlement and private bridge postings",npcStatements()),
            new Migration(45,"NPC cash settlement and physical custody",ca.emilybelnavis.mcmods.economy.trade.cash.NpcCashSchema.statements()),
            new Migration(46,"currency prime history and loan policy provenance",currencyPrimeStatements()),
            new Migration(47,"prospective fixed retirement and exact-time FX",effectiveFxStatements()),
            new Migration(48,"financial-day macro provider and atomic handover",macroStatements()),
            new Migration(49,"production simulation manifest",ca.emilybelnavis.mcmods.economy.simulation.SimulationLifecycle.statements()),
            new Migration(50,"immutable item valuation snapshots",ca.emilybelnavis.mcmods.economy.valuation.ValuationStore.statements()),
            new Migration(51,"prospective reference trade sources and no-carry facts",referenceTradeStatements()),
            new Migration(52,"authenticated reference trader activation",ca.emilybelnavis.mcmods.economy.trade.pricing.ReferenceTrading.statements()));

    private static List<String> referenceTradeStatements(){
        var statements=new java.util.ArrayList<String>();
        statements.addAll(ca.emilybelnavis.mcmods.economy.trade.pricing.TradeSources.statements());
        statements.addAll(ca.emilybelnavis.mcmods.economy.trade.settlement.ReferenceNpcSchema.statements());
        statements.addAll(ca.emilybelnavis.mcmods.economy.trade.cash.ReferenceCashSchema.statements());
        return java.util.List.copyOf(statements);
    }
    private static List<String> macroStatements() {
        var statements=new java.util.ArrayList<String>();
        statements.addAll(ca.emilybelnavis.mcmods.economy.simulation.macro.MacroActivation.statements());
        statements.addAll(ca.emilybelnavis.mcmods.economy.simulation.macro.MacroSchema.statements());
        statements.addAll(ca.emilybelnavis.mcmods.economy.simulation.macro.MacroPlans.statements());
        return List.copyOf(statements);
    }
    private static List<String> effectiveFxStatements() {
        var statements=new java.util.ArrayList<String>();
        statements.addAll(ca.emilybelnavis.mcmods.economy.clock.FixedRetirements.statements());
        statements.addAll(ca.emilybelnavis.mcmods.economy.fx.EffectiveFxSchema.statements());
        return List.copyOf(statements);
    }

    private static List<String> currencyPrimeStatements() {
        var statements=new java.util.ArrayList<String>();
        statements.addAll(ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimeSchema.statements());
        statements.addAll(ca.emilybelnavis.mcmods.economy.simulation.plan.ActivationModelPlans.statements());
        return List.copyOf(statements);
    }

    private static List<String> npcStatements() {
        var statements = new java.util.ArrayList<String>();
        statements.add("DROP INDEX ledger_posting_account");
        statements.add(LedgerHistorySchema.V4_INDEX);
        statements.addAll(ca.emilybelnavis.mcmods.economy.journal.NpcSettlement.statements());
        statements.addAll(ca.emilybelnavis.mcmods.economy.trade.settlement.NpcSchema.statements());
        return List.copyOf(statements);
    }

    private static List<String> tradeStatements() {
        var statements = new java.util.ArrayList<String>();
        statements.addAll(ca.emilybelnavis.mcmods.economy.trade.market.MarketSchema.statements());
        statements.addAll(ca.emilybelnavis.mcmods.economy.trade.catalog.CatalogStore.statements());
        statements.addAll(ca.emilybelnavis.mcmods.economy.trade.catalog.VillageMarketSource.statements());
        return List.copyOf(statements);
    }

    private static List<String> simulationStatements() {
        var statements = new java.util.ArrayList<String>();
        statements.addAll(ca.emilybelnavis.mcmods.economy.simulation.window.WindowSchema.statements());
        statements.addAll(ca.emilybelnavis.mcmods.economy.simulation.plan.ModelPlanStore.statements());
        return List.copyOf(statements);
    }

    private static List<String> debtAcceptanceStatements() {
        var statements=new java.util.ArrayList<String>();
        statements.addAll(ca.emilybelnavis.mcmods.economy.debt.DebtLegacySchema.statements());
        statements.addAll(ca.emilybelnavis.mcmods.economy.credit.CreditReportingSchema.statements());
        return List.copyOf(statements);
    }

    private static List<String> pricingStatements() {
        var sql=new java.util.ArrayList<String>();
        sql.addAll(ca.emilybelnavis.mcmods.economy.pricing.PricingSchema.statements());
        sql.addAll(ca.emilybelnavis.mcmods.economy.interest.InterestSchema.statements());
        return List.copyOf(sql);
    }

    private static List<String> institutionStatements() {
        var sql = new java.util.ArrayList<String>();
        sql.addAll(ca.emilybelnavis.mcmods.economy.institution.InstitutionSchema.statements());
        sql.addAll(ca.emilybelnavis.mcmods.economy.institution.ProviderSchema.statements());
        sql.add("DROP TRIGGER account_insert_guard");
        sql.add(AccountSchema.fourAccountGuard());
        sql.add("DROP INDEX ledger_posting_account");
        sql.add(LedgerHistorySchema.V3_INDEX);
        return List.copyOf(sql);
    }

    static void apply(Connection connection, UUID worldId, int from, int target, List<Migration> registry) {
        int expected = 1;
        for (Migration migration : registry) {
            if (migration.version() != expected++) throw new StorageException(StorageException.Kind.SCHEMA, "Migration registry is not contiguous");
        }
        if (target != registry.size() || from >= target) throw new StorageException(StorageException.Kind.SCHEMA, "Invalid migration target");
        boolean canReset = true;
        try {
            connection.setAutoCommit(false);
            for (Migration migration : registry) {
                if (migration.version() <= from) continue;
                for (String sql : migration.statements()) {
                    try (Statement statement = connection.createStatement()) { statement.execute(sql); }
                }
                if (migration.version() == 1) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO foundation_metadata(singleton,world_id,schema_version,product) VALUES(1,?,1,'economy-foundation')")) {
                        statement.setString(1, worldId.toString()); statement.executeUpdate();
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO migration_history VALUES(?,?,?)")) {
                    statement.setInt(1, migration.version()); statement.setString(2, migration.name());
                    statement.setString(3, Instant.now().toString()); statement.executeUpdate();
                }
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE foundation_metadata SET schema_version=" + migration.version() + " WHERE singleton=1");
                    statement.execute("PRAGMA user_version=" + migration.version());
                    statement.execute("PRAGMA application_id=" + APPLICATION_ID);
                }
            }
            connection.commit();
        } catch (Throwable e) {
            try { connection.rollback(); }
            catch (SQLException rollback) {
                canReset = false;
                e.addSuppressed(rollback);
                try { connection.close(); } catch (SQLException close) { e.addSuppressed(close); }
            }
            if (e instanceof Error error) throw error;
            throw new StorageException(StorageException.Kind.MIGRATION,
                    "Migration failed; no automatic retry. Inspect schema before restart", e);
        } finally {
            if (canReset) {
                try { connection.setAutoCommit(true); }
                catch (SQLException e) { throw StorageException.sql(e); }
            }
        }
    }
}
