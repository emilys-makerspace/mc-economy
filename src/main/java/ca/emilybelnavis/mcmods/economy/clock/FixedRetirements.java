package ca.emilybelnavis.mcmods.economy.clock;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.FxFacts;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Prospective terminal boundary for a fixed system. No completion/frontier/signature is rewritten. */
public final class FixedRetirements {
    private FixedRetirements() {}
    public static final String FACT="foundation:fixed-retirement", MACRO_SUCCESSOR="economy:a8/macro-rates";
    private static final Journal.Kind KIND=new Journal.Kind("foundation:clock","foundation:retire-fixed");
    private static final Gson JSON=new Gson();
    public record Receipt(WorldId world,String system,long originMillis,long cadenceMillis,int version,String signature,String versions,
                          long takeoverMillis,long firstExcludedMillis,long scheduledMillis,long schedulingFrontier,
                          String successor,String successorImplementations,UUID successorEvent,int successorVersion) {
        public Receipt {
            Objects.requireNonNull(world);Objects.requireNonNull(successorEvent);new Journal.Kind(system,"foundation:interval");new Journal.Kind(successor,"foundation:successor");
            if(originMillis<0||cadenceMillis<1||version<1||signature==null||!signature.matches("[0-9a-f]{64}")||versions==null||versions.isEmpty()||versions.length()>4096
                    ||successorImplementations==null||successorImplementations.isEmpty()||successorImplementations.length()>4096||successorVersion<1
                    ||scheduledMillis<0||schedulingFrontier<scheduledMillis||takeoverMillis<=schedulingFrontier||firstExcludedMillis<=schedulingFrontier
                    ||firstExcludedMillis<takeoverMillis||firstExcludedMillis<=originMillis||(firstExcludedMillis-originMillis)%cadenceMillis!=0
                    ||firstExcludedMillis-takeoverMillis>=cadenceMillis)throw new IllegalArgumentException("Prospective fixed retirement");
        }
    }
    public static RecordCatalog register(RecordCatalog catalog){return catalog.with(new RecordCatalog.Key(FACT,1),FixedRetirements::decode).with(new RecordCatalog.Key(FACT,2),FixedRetirements::decode);}
    public static List<String> statements(){return List.of(
        "CREATE TABLE fixed_system_retirements(system_id TEXT PRIMARY KEY,world_id TEXT NOT NULL,first_excluded_ms INTEGER NOT NULL,takeover_ms INTEGER NOT NULL,operation_id TEXT NOT NULL UNIQUE,payload TEXT NOT NULL)",
        "CREATE INDEX fixed_retirement_facts ON journal_records(record_type,sequence) WHERE record_type='foundation:fixed-retirement'",
        "CREATE TRIGGER fixed_retirement_update BEFORE UPDATE ON fixed_system_retirements BEGIN SELECT RAISE(ABORT,'immutable fixed retirement'); END",
        "CREATE TRIGGER fixed_retirement_delete BEFORE DELETE ON fixed_system_retirements BEGIN SELECT RAISE(ABORT,'immutable fixed retirement'); END",
        "CREATE TRIGGER fixed_retirement_replace BEFORE INSERT ON fixed_system_retirements WHEN EXISTS(SELECT 1 FROM fixed_system_retirements WHERE system_id=NEW.system_id OR operation_id=NEW.operation_id) BEGIN SELECT RAISE(ABORT,'existing fixed retirement'); END"
    );}
    public static Receipt read(JournalEngine.ReadView reads,String system){var rows=reads.query("SELECT payload FROM fixed_system_retirements WHERE system_id=?",List.of(system),1);return rows.isEmpty()?null:decode(rows.getFirst().get("payload"));}
    public static boolean excludes(JournalEngine.ReadView reads,String system,long end){var receipt=read(reads,system);return receipt!=null&&end>=receipt.firstExcludedMillis();}
    public static long eligibleTarget(JournalEngine.ReadView reads,String system,long target){var receipt=read(reads,system);return receipt==null?target:Math.min(target,receipt.firstExcludedMillis()-receipt.cadenceMillis());}
    public static String encode(Receipt receipt){String text=JSON.toJson(receipt);if(text.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("Retirement fact bound");return text;}
    public static Receipt decode(String text){if(text==null||text.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("Retirement fact bound");var receipt=JSON.fromJson(text,Receipt.class);if(receipt==null||!encode(receipt).equals(text))throw new IllegalArgumentException("Retirement canonical encoding");return receipt;}
    public static boolean retains(String installed,String captured){if(installed==null||captured==null)return false;return new HashSet<>(Arrays.asList(installed.split(";"))).containsAll(Arrays.asList(captured.split(";")));}
    static String versions(JournalEngine.ReadView reads,String system){return reads.query("SELECT version,effective_end_ms,implementation_id FROM economic_versions WHERE system_id=? ORDER BY effective_end_ms",List.of(system),16).stream().map(r->r.get("version")+":"+r.get("effective_end_ms")+":"+r.get("implementation_id")).reduce((a,b)->a+";"+b).orElseThrow();}
    static Receipt retire(FoundationStore store,Clock wall,EconomicClock.SystemDefinition definition,long takeover,DeadlineDispatcher.Successor successor) {
        String system=definition.id();var retained=store.transaction(tx->read(tx::query,system));
        if(retained!=null){if(retained.takeoverMillis()!=takeover||!retained.successor().equals(successor.event().provider())||!retained.successorEvent().equals(successor.event().id()))throw new IllegalArgumentException("Original retirement changed");return retained;}
        long now=Math.max(0,wall.millis());var receipt=store.transaction(tx->capture(tx::query,store.snapshot().worldId(),definition,takeover,successor,now));
        UUID operation=UUID.nameUUIDFromBytes(("economy:fixed-retirement:v1|"+receipt.world().value()+"|"+system).getBytes(StandardCharsets.UTF_8));String payload=encode(receipt);
        var request=new Journal.CanonicalRequest(1,Map.of("receipt",payload));var actor=new SystemActorId(UUID.nameUUIDFromBytes(("economy:fixed-retirement-actor|"+receipt.world().value()).getBytes(StandardCharsets.UTF_8)));
        var registration=new JournalEngine.Registration(1,(intent,reads)->{
            if(!request.equals(intent.request())||read(reads,system)!=null||!capture(reads,store.snapshot().worldId(),definition,takeover,successor,now).equals(receipt))throw new IllegalArgumentException("Retirement admission changed");
            return new JournalEngine.Commit(List.of(new Journal.Record(FACT,1,payload)),new Journal.Result(1,"FIXED_SYSTEM_RETIRED",system),tx->tx.execute("INSERT INTO fixed_system_retirements VALUES(?,?,?,?,?,?)",List.of(system,receipt.world().value().toString(),Long.toString(receipt.firstExcludedMillis()),Long.toString(takeover),operation.toString(),payload)));
        });
        var outcome=new JournalEngine(store,(a,k)->a.equals(actor)&&k.equals(KIND),Map.of(KIND,registration)).submit(new Journal.Intent(new OperationId(operation),new RequestId(operation),actor,KIND,request,null,null));
        if(outcome.status()!=Journal.Status.COMMITTED)throw new IllegalArgumentException("Retirement unresolved: "+outcome.result().code());return receipt;
    }
    public static JournalEngine.Commit prepare(JournalEngine.ReadView reads,WorldId world,EconomicClock.SystemDefinition definition,long takeover,DeadlineDispatcher.Successor successor,long now,UUID operation) {
        if(read(reads,definition.id())!=null)throw new IllegalArgumentException("Fixed retirement already captured");
        var receipt=capture(reads,world,definition,takeover,successor,now);String payload=encode(receipt);
        return new JournalEngine.Commit(List.of(new Journal.Record(FACT,2,payload)),new Journal.Result(1,"FIXED_SYSTEM_RETIRED",definition.id()),tx->
            tx.execute("INSERT INTO fixed_system_retirements VALUES(?,?,?,?,?,?)",List.of(definition.id(),world.value().toString(),Long.toString(receipt.firstExcludedMillis()),Long.toString(takeover),operation.toString(),payload)));
    }
    private static Receipt capture(JournalEngine.ReadView reads,WorldId world,EconomicClock.SystemDefinition definition,long takeover,DeadlineDispatcher.Successor successor,long now) {
        if(!world.equals(successor.world())||successor.event().effectiveMillis()!=takeover||successor.event().stage()!=0)throw new IllegalArgumentException("Installed stage-zero successor required");
        var states=reads.query("SELECT * FROM economic_systems WHERE system_id=?",List.of(definition.id()),1);if(states.isEmpty())throw new IllegalArgumentException("Fixed system not opened");var state=states.getFirst();long origin=Long.parseLong(state.get("origin_ms")),cadence=Long.parseLong(state.get("cadence_ms"));
        if(cadence!=definition.cadenceMillis()||takeover<=origin)throw new IllegalArgumentException("Retirement cadence/origin");long intervals=(Math.subtractExact(takeover,origin)-1)/cadence+1;long excluded=Math.addExact(origin,Math.multiplyExact(intervals,cadence));
        long frontier=now;for(String sql:List.of("SELECT observed_wall_ms AS value FROM economic_metadata WHERE singleton=1","SELECT MAX(frontier_ms) AS value FROM economic_systems","SELECT MAX(active_observed_ms) AS value FROM economic_systems","SELECT observed_wall_ms AS value FROM financial_calendar WHERE singleton=1","SELECT last_effective_ms AS value FROM financial_calendar WHERE singleton=1","SELECT MAX(effective_ms) AS value FROM financial_deadline_events")){
            var rows=reads.query(sql,List.of(),1);if(!rows.isEmpty()&&rows.getFirst().get("value")!=null)frontier=Math.max(frontier,Long.parseLong(rows.getFirst().get("value")));}
        if(takeover<=frontier||excluded<=frontier)throw new IllegalArgumentException("Retirement cannot erase due or observed work");
        if(!reads.query("SELECT update_id FROM economic_updates WHERE system_id=? AND interval_end_ms>=? LIMIT 1",List.of(definition.id(),Long.toString(excluded)),1).isEmpty())throw new IllegalArgumentException("Retirement excludes actual completion");
        if(definition.id().equals(FxService.SYSTEM)&&!reads.query("SELECT payload FROM journal_records WHERE record_type=? AND substr(payload,1,10)>=? LIMIT 1",List.of(FxFacts.POLICY,Instant.ofEpochMilli(excluded).atZone(ZoneOffset.UTC).toLocalDate().toString()),1).isEmpty())throw new IllegalArgumentException("Future legacy FX policy conflicts with retirement");
        var chosen=reads.query("SELECT version,implementation_id FROM economic_versions WHERE system_id=? AND effective_end_ms<=? ORDER BY effective_end_ms DESC LIMIT 1",List.of(definition.id(),Long.toString(excluded)),1).getFirst();int version=Integer.parseInt(chosen.get("version"));
        if(!chosen.get("implementation_id").equals(EconomicClock.signature(definition,Objects.requireNonNull(definition.versions().get(version)),origin)))throw new IllegalArgumentException("Retirement original implementation changed");
        var provider=reads.query("SELECT implementations FROM financial_deadline_providers WHERE provider=?",List.of(successor.event().provider()),1);if(provider.isEmpty()||!retains(provider.getFirst().get("implementations"),successor.implementations()))throw new IllegalArgumentException("Successor not installed");
        return new Receipt(world,definition.id(),origin,cadence,version,chosen.get("implementation_id"),versions(reads,definition.id()),takeover,excluded,now,frontier,successor.event().provider(),successor.implementations(),successor.event().id(),successor.event().version());
    }
    public static void validate(Connection c)throws SQLException {
        try {
            for(String sql:statements()){String name=sql.split(" ")[2].split("\\(",2)[0];if(!sql.equals(scalar(c,"SELECT sql FROM sqlite_schema WHERE name=?",name)))throw new SQLException("Retirement schema");}
            String world=scalar(c,"SELECT world_id FROM foundation_metadata WHERE singleton=1");long count=0;
            try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT * FROM fixed_system_retirements")){while(rows.next()) {
                count++;var receipt=decode(rows.getString("payload"));String system=receipt.system();
                if(!receipt.world().value().toString().equals(world)||!world.equals(rows.getString("world_id"))||!system.equals(rows.getString("system_id"))||receipt.firstExcludedMillis()!=rows.getLong("first_excluded_ms")||receipt.takeoverMillis()!=rows.getLong("takeover_ms"))throw new SQLException("Retirement projection");
                String expectedOperation=UUID.nameUUIDFromBytes(("economy:fixed-retirement:v1|"+world+"|"+system).getBytes(StandardCharsets.UTF_8)).toString();
                String sourceVersion=scalar(c,"SELECT r.record_version FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE t.operation_id=? AND r.record_type=?",rows.getString("operation_id"),FACT);
                if("1".equals(sourceVersion)?!expectedOperation.equals(rows.getString("operation_id")):!"2".equals(sourceVersion)||!ca.emilybelnavis.mcmods.economy.simulation.macro.MacroActivation.owns(c,rows.getString("operation_id")))throw new SQLException("Retirement original operation identity/version");
                if(!"1".equals(scalar(c,"SELECT COUNT(*) FROM journal_transactions t JOIN journal_records r ON r.sequence=t.sequence WHERE t.operation_id=? AND r.record_type=? AND r.record_version IN (1,2) AND r.payload=?",rows.getString("operation_id"),FACT,rows.getString("payload"))))throw new SQLException("Retirement immutable source");
                if(!Long.toString(receipt.originMillis()).equals(scalar(c,"SELECT origin_ms FROM economic_systems WHERE system_id=?",system))||!Long.toString(receipt.cadenceMillis()).equals(scalar(c,"SELECT cadence_ms FROM economic_systems WHERE system_id=?",system))||!receipt.signature().equals(scalar(c,"SELECT implementation_id FROM economic_versions WHERE system_id=? AND effective_end_ms<=? ORDER BY effective_end_ms DESC LIMIT 1",system,Long.toString(receipt.firstExcludedMillis()))))throw new SQLException("Retirement original signature/cadence");
                if(!Integer.toString(receipt.version()).equals(scalar(c,"SELECT version FROM economic_versions WHERE system_id=? AND effective_end_ms<=? ORDER BY effective_end_ms DESC LIMIT 1",system,Long.toString(receipt.firstExcludedMillis()))))throw new SQLException("Retirement original version identity");
                var manifest=new ArrayList<String>();try(var q=c.prepareStatement("SELECT version,effective_end_ms,implementation_id FROM economic_versions WHERE system_id=? ORDER BY effective_end_ms")){q.setString(1,system);try(var v=q.executeQuery()){while(v.next())manifest.add(v.getString(1)+":"+v.getString(2)+":"+v.getString(3));}}
                if(!String.join(";",manifest).equals(receipt.versions())||!retains(scalar(c,"SELECT implementations FROM financial_deadline_providers WHERE provider=?",receipt.successor()),receipt.successorImplementations()))throw new SQLException("Retirement version/successor evidence");
                if(!"0".equals(scalar(c,"SELECT COUNT(*) FROM economic_updates WHERE system_id=? AND interval_end_ms>=?",system,Long.toString(receipt.firstExcludedMillis())))||Long.parseLong(scalar(c,"SELECT frontier_ms FROM economic_systems WHERE system_id=?",system))>=receipt.firstExcludedMillis())throw new SQLException("Excluded fixed work was completed");
                if(!Integer.toString(receipt.successorVersion()).equals(scalar(c,"SELECT version FROM financial_deadline_versions WHERE provider=? AND effective_ms<=? ORDER BY effective_ms DESC LIMIT 1",receipt.successor(),Long.toString(receipt.takeoverMillis()))))throw new SQLException("Retirement successor policy changed");
                if(system.equals(FxService.SYSTEM)&&!"0".equals(scalar(c,"SELECT COUNT(*) FROM journal_records WHERE record_type=? AND substr(payload,1,10)>=?",FxFacts.POLICY,Instant.ofEpochMilli(receipt.firstExcludedMillis()).atZone(ZoneOffset.UTC).toLocalDate().toString())))throw new SQLException("Unreconciled future FX policy after retirement");
                String eventTime=scalar(c,"SELECT effective_ms FROM financial_deadline_events WHERE event_id=?",receipt.successorEvent().toString());
                if(eventTime!=null&&(!eventTime.equals(Long.toString(receipt.takeoverMillis()))||!receipt.successor().equals(scalar(c,"SELECT provider FROM financial_deadline_events WHERE event_id=?",receipt.successorEvent().toString()))||!"0".equals(scalar(c,"SELECT stage FROM financial_deadline_events WHERE event_id=?",receipt.successorEvent().toString()))||!Integer.toString(receipt.successorVersion()).equals(scalar(c,"SELECT version FROM financial_deadline_events WHERE event_id=?",receipt.successorEvent().toString()))))throw new SQLException("Retirement successor event mismatch");
            }}
            if(!Long.toString(count).equals(scalar(c,"SELECT COUNT(*) FROM journal_records WHERE record_type=?",FACT)))throw new SQLException("Orphan retirement source");
        }catch(RuntimeException bad){throw new SQLException("Invalid fixed retirement",bad);}
    }
    private static String scalar(Connection c,String sql,String...args)throws SQLException{try(var q=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)q.setString(i+1,args[i]);try(var rows=q.executeQuery()){return rows.next()?rows.getString(1):null;}}}
}
