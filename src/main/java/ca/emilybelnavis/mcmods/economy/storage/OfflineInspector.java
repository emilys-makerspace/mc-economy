package ca.emilybelnavis.mcmods.economy.storage;

import ca.emilybelnavis.mcmods.economy.recovery.*;
import ca.emilybelnavis.mcmods.economy.clock.EconomicRecord;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Read-only offline validation, only while the caller owns stopped-world maintenance locks. */
final class OfflineInspector {
    record Metadata(WorldShutdown.Evidence evidence,boolean databaseClean) {}
    static Metadata metadata(Path world) throws IOException {
        try(var opened=open(world)) {
            var c=opened.connection();
            int schema=Integer.parseInt(scalar(c,"PRAGMA user_version"));
            String id=scalar(c,"SELECT world_id FROM foundation_metadata");
            long last=schema>=3?Long.parseLong(scalar(c,"SELECT COALESCE(max(sequence),0) FROM journal_transactions")):0;
            long economic=schema>=4?Long.parseLong(scalar(c,"SELECT global_sequence FROM economic_metadata")):0;
            boolean clean=schema>=4 && scalar(c,"SELECT clean_shutdown FROM runtime_state").equals("1");
            return new Metadata(new WorldShutdown.Evidence(id,schema,last,economic),clean);
        } catch(SQLException|RuntimeException e) { throw new IOException("Cannot inspect offline economy",e); }
    }
    static void validate(Path world,FoundationRuntime.Configuration configuration) throws IOException {
        StorageFiles.rejectLinks(world);
        try(var opened=open(world)) {
            var c=opened.connection();
            int schema=Integer.parseInt(scalar(c,"PRAGMA user_version"));
            if((schema<4 || schema>SchemaMigrations.CURRENT) || !scalar(c,"PRAGMA application_id").equals(Integer.toString(SchemaMigrations.APPLICATION_ID))) throw new IOException("Unsupported backup schema/application");
            String id=scalar(c,"SELECT world_id FROM foundation_metadata"); UUID.fromString(id);
            if(!Files.readString(world.resolve("economy/world-identity")).equals(SqliteStorage.encodeIdentity(UUID.fromString(id)))) throw new IOException("Backup world identity mismatch");
            if(!scalar(c,"PRAGMA quick_check").equals("ok")) throw new IOException("Corrupt backup database");
            try(var sql=c.createStatement();var rows=sql.executeQuery("PRAGMA foreign_key_check")) { if(rows.next()) throw new IOException("Broken backup references"); }
            JournalSchema.validate(c);RecoverySchema.validate(c);
            if (schema >= 5) FxSchema.validate(c);
            if (schema >= 6) AccountSchema.validate(c);
            if (schema >= 20) ca.emilybelnavis.mcmods.economy.institution.InstitutionSchema.validate(c);
            if (schema >= 21) {
                ca.emilybelnavis.mcmods.economy.pricing.PricingSchema.validate(c); ca.emilybelnavis.mcmods.economy.interest.InterestSchema.validate(c);
                ca.emilybelnavis.mcmods.economy.pricing.ValuationSources.validate((q,p,max)->query(c,q,p,max),new ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId(UUID.fromString(id)),configuration.valuation());
            }
            if (schema >= 22) TerminalBankingSchema.validate(c);
            if (schema >= 23) ca.emilybelnavis.mcmods.economy.card.CardSchema.validate(c);
            if (schema >= 24) ca.emilybelnavis.mcmods.economy.credit.CreditProfileSchema.validate(c);
            if (schema >= 25) ca.emilybelnavis.mcmods.economy.credit.CreditFacilitySchema.validate(c);
            if (schema >= 26) ca.emilybelnavis.mcmods.economy.credit.CreditAuditFacts.validateSchema(c);
            if (schema >= 27) ca.emilybelnavis.mcmods.economy.credit.CreditEvents.validateSchema(c);
            if (schema >= 28) ca.emilybelnavis.mcmods.economy.loan.LoanSchema.validate(c);
            if (schema >= 29) ca.emilybelnavis.mcmods.economy.loan.LoanDiagnosticSchema.validate(c);
            if (schema >= 30) ca.emilybelnavis.mcmods.economy.loan.LoanEvents.validateSchema(c);
            if (schema >= 31) ca.emilybelnavis.mcmods.economy.debt.DebtLifecycleSchema.validate(c);
            if (schema >= 32) ca.emilybelnavis.mcmods.economy.debt.DebtDiagnosticSchema.validate(c);
            if (schema >= 34) ca.emilybelnavis.mcmods.economy.collection.CollectionPresenceSchema.validate(c);
            if (schema >= 35) ca.emilybelnavis.mcmods.economy.autopay.AutopaySchema.validate(c);
            if (schema >= 36) VillageBranchSchema.validate(c);
            if (schema >= 37) ca.emilybelnavis.mcmods.economy.journal.KioskUpgrades.validate(c);
            if (schema >= 38) BranchPlacementSchema.validate(c);
            if (schema >= 39) BankStaffingSchema.validate(c);
            if (schema >= 40) ca.emilybelnavis.mcmods.economy.simulation.activity.ActivitySchema.validate(c);
            if (schema >= 43) ca.emilybelnavis.mcmods.economy.trade.stock.StockSchema.validate(c);
            if (schema >= 44) {
                ca.emilybelnavis.mcmods.economy.journal.NpcSettlement.validate(c);
                ca.emilybelnavis.mcmods.economy.trade.settlement.NpcSchema.validate(c);
                if(schema>=45) ca.emilybelnavis.mcmods.economy.trade.cash.NpcCashSchema.validate(c);
                if(schema>=46) { ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimeSchema.validate(c);
                    ca.emilybelnavis.mcmods.economy.simulation.plan.ActivationModelPlans.validate(c); }
            }
            if (schema >= 42) {
                ca.emilybelnavis.mcmods.economy.trade.market.MarketSchema.validate(c);
                ca.emilybelnavis.mcmods.economy.trade.catalog.CatalogStore.validate(c);
                ca.emilybelnavis.mcmods.economy.trade.catalog.VillageMarketSource.validate(c);
            }
            if (schema >= 41) {
                ca.emilybelnavis.mcmods.economy.simulation.window.WindowSchema.validate(c);
                ca.emilybelnavis.mcmods.economy.simulation.plan.ModelPlanStore.validate(c);
            }
            if (schema >= 33) {
                ca.emilybelnavis.mcmods.economy.debt.DebtLegacySchema.validate(c);
                ca.emilybelnavis.mcmods.economy.credit.CreditReportingSchema.validate(c);
            }
            if (schema >= 7) LedgerProjection.validateSchema(c);
            if (schema >= 8) HoldSchema.validate(c);
            if (schema >= 9) FxSettlementSchema.validate(c);
            if (schema >= 10) LedgerHistorySchema.validate(c);
            var validation=new JournalCompatibility(c,configuration.records(),id); while(!validation.step()) {}
            if (schema >= 18) FinancialCalendarSchema.validate(c);
            if (schema >= 18) ca.emilybelnavis.mcmods.economy.clock.DeadlineDispatcher.validateProviders(
                    (query, parameters, max) -> query(c, query, parameters, max), configuration.deadlineProviders());
            long last=Long.parseLong(scalar(c,"SELECT COALESCE(max(sequence),0) FROM journal_transactions"));
            Map<String,ProjectionManager.Definition> projections=new HashMap<>(); configuration.projections().forEach(d->projections.put(d.name(),d));
            try(var sql=c.createStatement();var rows=sql.executeQuery("SELECT h.name,g.* FROM projection_heads h LEFT JOIN projection_generations g ON g.name=h.name AND g.generation=h.active_generation")) {
                int count=0;
                while(rows.next()) {
                    if(++count>32) throw new IOException("Projection registry bound");
                    var definition=projections.get(rows.getString("name"));
                    if(definition==null || !"ACTIVE".equals(rows.getString("state")) || definition.version()!=rows.getInt("version") || rows.getLong("last_sequence")>last) throw new IOException("Incompatible/incomplete projection checkpoint");
                    var values=new TreeMap<String,String>(); int bytes=0;
                    try(var cells=c.prepareStatement("SELECT key,value FROM projection_cells WHERE name=? AND generation=? ORDER BY key")) {
                        cells.setString(1,definition.name());cells.setString(2,rows.getString("generation"));
                        try(var cell=cells.executeQuery()) { while(cell.next()) { values.put(cell.getString(1),cell.getString(2));bytes+=cell.getString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+cell.getString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(values.size()>1024||bytes>1048576)throw new IOException("Projection bound"); } }
                    }
                    long checkpointSequence=rows.getLong("last_sequence");String tx=rows.getString("last_transaction");
                    if(!ProjectionManager.digest(definition.version(),checkpointSequence,tx,values).equals(rows.getString("checksum")) || !definition.validator().test(new ProjectionManager.Snapshot(definition.name(),definition.version(),rows.getString("generation"),checkpointSequence,tx,values))) throw new IOException("Invalid projection checksum");
                    if(checkpointSequence>0) try(var checkpoint=c.prepareStatement("SELECT transaction_id FROM journal_transactions WHERE sequence=?")) {checkpoint.setLong(1,checkpointSequence);try(var row=checkpoint.executeQuery()){if(!row.next()||!row.getString(1).equals(tx))throw new IOException("Projection transaction mismatch");}}
                }
            }
            var systems=new HashMap<String,ca.emilybelnavis.mcmods.economy.clock.EconomicClock.SystemDefinition>();configuration.systems().forEach(d->systems.put(d.id(),d));
            try(var sql=c.createStatement();var rows=sql.executeQuery("SELECT * FROM economic_systems")) {
                int count=0;
                while(rows.next()) {
                    var definition=systems.get(rows.getString("system_id"));
                    var deferred=configuration.deferredSystems().get(rows.getString("system_id"));
                    Long origin=definition == null ? null : Long.valueOf(definition.originMillis());
                    if (deferred != null) origin=deferred.origin().read((query, parameters, max) -> query(c, query, parameters, max));
                    if(++count>64 || definition==null || origin==null || definition.cadenceMillis()!=rows.getLong("cadence_ms") || origin!=rows.getLong("origin_ms")) throw new IOException("Unsupported economic system");
                    try(var versions=c.prepareStatement("SELECT * FROM economic_versions WHERE system_id=?")) {
                        versions.setString(1,definition.id());try(var version=versions.executeQuery()) {int n=0;while(version.next()){var handler=definition.versions().get(version.getInt("version"));if(++n>16||handler==null||!ca.emilybelnavis.mcmods.economy.clock.EconomicClock.signature(definition,handler,origin).equals(version.getString("implementation_id")))throw new IOException("Unsupported historical simulator");}}
                    }
                    try(var latest=c.prepareStatement("SELECT * FROM economic_updates WHERE system_id=? ORDER BY economic_sequence DESC LIMIT 1")) {
                        latest.setString(1,definition.id());try(var row=latest.executeQuery()) {if(row.next()){if(row.getLong("interval_end_ms")!=rows.getLong("frontier_ms")||row.getLong("economic_sequence")!=rows.getLong("last_sequence"))throw new IOException("Economic checkpoint mismatch");}else if(rows.getLong("frontier_ms")!=origin||rows.getLong("last_sequence")!=0)throw new IOException("Initial checkpoint mismatch");}
                    }
                }
            }
            long economic=Long.parseLong(scalar(c,"SELECT global_sequence FROM economic_metadata"));
            if(economic!=Long.parseLong(scalar(c,"SELECT count(*) FROM economic_updates")) || economic!=Long.parseLong(scalar(c,"SELECT COALESCE(max(economic_sequence),0) FROM economic_updates"))) throw new IOException("Economic completion sequence mismatch");
            try(var sql=c.createStatement();var rows=sql.executeQuery("SELECT u.*,r.payload FROM economic_updates u JOIN journal_records r ON r.sequence=u.journal_sequence AND r.record_type='foundation:economic-update'")) {
                while(rows.next()) {var record=EconomicRecord.decode(rows.getString("payload"));if(!record.updateId().toString().equals(rows.getString("update_id"))||record.economicSequence()!=rows.getLong("economic_sequence")||!record.output().equals(rows.getString("output")))throw new IOException("Economic output/completion mismatch");}
            }
        } catch(SQLException|RuntimeException e) { throw new IOException("Backup compatibility validation failed",e); }
    }
    private static List<Map<String,String>> query(Connection connection, String sql, List<String> parameters, int maximumRows) {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) statement.setString(i + 1, parameters.get(i));
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<Map<String,String>>();
                while (rows.next()) {
                    if (result.size() == maximumRows) throw new IllegalArgumentException("Offline query row limit");
                    var row = new HashMap<String,String>();
                    var columns = rows.getMetaData();
                    for (int i = 1; i <= columns.getColumnCount(); i++) row.put(columns.getColumnLabel(i), rows.getString(i));
                    result.add(Collections.unmodifiableMap(row));
                }
                return List.copyOf(result);
            }
        } catch (SQLException error) {
            throw StorageException.sql(error);
        }
    }

    private record Inspection(Connection connection,Path scratch) implements AutoCloseable {
        public void close() throws SQLException,IOException {
            try { connection.close(); } finally { if(scratch!=null) removeScratch(scratch); }
        }
    }
    private static Inspection open(Path world) throws SQLException,IOException {
        Path db=world.resolve("economy/economy.sqlite"),wal=db.resolveSibling("economy.sqlite-wal");
        StorageFiles.rejectLinks(db);StorageFiles.rejectLinks(wal);SqliteStorage.verifyDriver();
        if(!Files.exists(wal)||Files.size(wal)==0)
            return new Inspection(DriverManager.getConnection("jdbc:sqlite:"+db.toUri()+"?mode=ro&immutable=1"),null);
        // Immutable SQLite ignores a nonempty WAL on this pinned driver. Recover a private DB+WAL
        // image instead; never create companions, checkpoint, or change a sealed source backup.
        Path scratch=Files.createTempDirectory("economy-offline-inspection-");
        try {
            String dbHash=StorageFiles.hash(db),walHash=StorageFiles.hash(wal);
            Files.copy(db,scratch.resolve("economy.sqlite"));Files.copy(wal,scratch.resolve("economy.sqlite-wal"));
            if(!dbHash.equals(StorageFiles.hash(db))||!walHash.equals(StorageFiles.hash(wal)))throw new IOException("Offline source changed while inspecting");
            return new Inspection(DriverManager.getConnection("jdbc:sqlite:"+scratch.resolve("economy.sqlite")),scratch);
        } catch(SQLException|IOException|RuntimeException e) {removeScratch(scratch);throw e;}
    }
    private static void removeScratch(Path scratch)throws IOException {
        try(var paths=Files.walk(scratch)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    private static String scalar(Connection c,String query) throws SQLException {try(var sql=c.createStatement();var row=sql.executeQuery(query)){if(!row.next())throw new SQLException("Missing metadata");return row.getString(1);}}
}
