package ca.emilybelnavis.mcmods.economy.simulation.window;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityFacts;
import java.sql.*;
import java.util.*;
import static ca.emilybelnavis.mcmods.economy.simulation.window.WindowFacts.*;

/** Additive immutable window projections; validation never reruns financial classifiers. */
public final class WindowSchema {
    private WindowSchema() {}
    public static List<String> statements() {
        return List.of(
                "CREATE TABLE macro_window_opens(id TEXT PRIMARY KEY,day_index INTEGER NOT NULL UNIQUE CHECK(typeof(day_index)='integer' AND day_index>=0),boundary_ms INTEGER NOT NULL CHECK(typeof(boundary_ms)='integer' AND boundary_ms>0),input_frontier INTEGER NOT NULL CHECK(typeof(input_frontier)='integer' AND input_frontier>0),operation_id TEXT NOT NULL UNIQUE,payload TEXT NOT NULL)",
                "CREATE TABLE macro_window_closes(currency TEXT NOT NULL,day_index INTEGER NOT NULL CHECK(typeof(day_index)='integer' AND day_index>=0),open_id TEXT NOT NULL REFERENCES macro_window_opens(id),operation_id TEXT NOT NULL UNIQUE,payload TEXT NOT NULL,hash TEXT NOT NULL,PRIMARY KEY(currency,day_index),UNIQUE(open_id,currency))",
                "CREATE INDEX activity_seed_ready_time ON activity_batches(accounting_ms) WHERE seed=1",
                "CREATE INDEX activity_window_batches_day ON activity_batches(day_index,operation_id)",
                "CREATE TRIGGER macro_window_opens_replace BEFORE INSERT ON macro_window_opens WHEN EXISTS(SELECT 1 FROM macro_window_opens WHERE id=NEW.id OR day_index=NEW.day_index OR operation_id=NEW.operation_id) BEGIN SELECT RAISE(ABORT,'existing window open'); END",
                "CREATE TRIGGER macro_window_closes_replace BEFORE INSERT ON macro_window_closes WHEN EXISTS(SELECT 1 FROM macro_window_closes WHERE (currency=NEW.currency AND day_index=NEW.day_index) OR (open_id=NEW.open_id AND currency=NEW.currency) OR operation_id=NEW.operation_id) BEGIN SELECT RAISE(ABORT,'existing window close'); END",
                immutable("macro_window_opens","UPDATE"),immutable("macro_window_opens","DELETE"),
                immutable("macro_window_closes","UPDATE"),immutable("macro_window_closes","DELETE"));
    }
    private static String immutable(String table,String action) { return "CREATE TRIGGER "+table+"_"+action.toLowerCase(Locale.ROOT)+" BEFORE "+action+" ON "+table+" BEGIN SELECT RAISE(ABORT,'immutable window evidence'); END"; }

    public static void validate(Connection c)throws SQLException {
        try {
            // Require the owning declarations, including immutable triggers/indexes, rather than accepting lookalike tables.
            for(String declaration:statements()) {
                String[] parts=declaration.split(" "); String name=parts[2].split("\\(",2)[0];
                try(var s=c.prepareStatement("SELECT sql FROM sqlite_master WHERE name=?")) {
                    s.setString(1,name);try(var r=s.executeQuery()){if(!r.next()||!normalize(declaration).equals(normalize(r.getString(1))))throw invalid("Window schema declaration: "+name);}
                }
            }
            String world=scalar(c,"SELECT world_id FROM foundation_metadata WHERE singleton=1");
            long openCount=count(c,"SELECT COUNT(*) FROM macro_window_opens"),closeCount=count(c,"SELECT COUNT(*) FROM macro_window_closes");
            if(openCount!=count(c,"SELECT COUNT(*) FROM journal_records WHERE record_type='"+OPEN+"'")
                    ||closeCount!=count(c,"SELECT COUNT(*) FROM journal_records WHERE record_type='"+CLOSE+"'"))throw invalid("Window fact/projection count");
            long prior=-1,first=-1,last=-1; Open previous=null;
            try(var s=c.createStatement();var rows=s.executeQuery("SELECT * FROM macro_window_opens ORDER BY day_index")) {
                while(rows.next()) {
                    var open=decodeOpen(rows.getString("payload"));
                    if(!open.world().value().toString().equals(world)||!open.id().toString().equals(rows.getString("id"))
                            ||open.day()!=rows.getLong("day_index")
                            ||open.boundaryMillis()!=rows.getLong("boundary_ms")||open.frontier()!=rows.getLong("input_frontier"))throw invalid("Window open projection");
                    long sourceSequence=requireFact(c,open.world().value().toString(),rows.getString("operation_id"),OPEN,rows.getString("payload"));
                    if(open.frontier()>=sourceSequence)throw invalid("Window frontier is not prior to open");
                    try(var late=c.prepareStatement("SELECT 1 FROM activity_batches b JOIN journal_transactions t ON t.operation_id=b.operation_id WHERE b.day_index<=? AND t.sequence>? LIMIT 1")) {
                        late.setLong(1,open.day());late.setLong(2,open.frontier());try(var found=late.executeQuery()){if(found.next())throw invalid("Late source in globally sealed day");}
                    }
                    boolean provider=providerFact(c,rows.getString("operation_id"),OPEN,open.id().toString(),"00-open",open.boundaryMillis());
                    boolean transition=provider&&open.day()==open.firstDay();
                    if(transition) {
                        var a=ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimeFacts.decodeActivation(scalar(c,"SELECT payload FROM currency_prime_activation WHERE world_id=?",world));
                        long activated=Long.parseLong(scalar(c,"SELECT activated_ms FROM activity_activation WHERE singleton=1"));
                        long seeded=Long.parseLong(scalar(c,"SELECT MAX(accounting_ms) FROM activity_batches WHERE seed=1"));
                        if(open.originMillis()!=a.originMillis()||open.originMillis()!=Long.parseLong(scalar(c,"SELECT origin_ms FROM activity_activation WHERE singleton=1"))||open.firstDay()!=(a.effectiveMillis()-a.originMillis())/ActivityFacts.DAY_MILLIS||open.boundaryMillis()!=a.firstEventMillis()||!open.anchor().equals(a.fxSnapshot())||Math.max(activated,seeded)>a.effectiveMillis()||previous!=null&&(previous.day()>=open.day()||closeCount(c,previous.id().toString())!=11))throw invalid("Provider initial coverage");
                        first=open.firstDay();
                    } else if(previous==null) {
                        long origin=Long.parseLong(scalar(c,"SELECT origin_ms FROM activity_activation WHERE singleton=1"));
                        long activated=Long.parseLong(scalar(c,"SELECT activated_ms FROM activity_activation WHERE singleton=1"));
                        String ready=scalar(c,"SELECT MAX(accounting_ms) FROM activity_batches WHERE seed=1");
                        if(ready==null||!"1".equals(scalar(c,"SELECT capacity_ready FROM activity_activation WHERE singleton=1")))throw invalid("Window has incomplete source coverage");
                        long elapsed=Math.subtractExact(Math.max(activated,Long.parseLong(ready)),origin);
                        first=Math.addExact(elapsed/ActivityFacts.DAY_MILLIS,elapsed%ActivityFacts.DAY_MILLIS==0?0:1);
                        if(open.day()!=first||open.firstDay()!=first||open.originMillis()!=origin)throw invalid("Window initial coverage");
                    } else {
                        if(open.day()!=prior+1||open.firstDay()!=first||open.originMillis()!=previous.originMillis()||!open.anchor().equals(previous.anchor()))throw invalid("Skipped window day or changed anchor");
                        if(closeCount(c,previous.id().toString())!=previous.currencies().size())throw invalid("Unfinished earlier window");
                    }
                    previous=open;prior=open.day();last=open.day();
                }
            }
            String seal=scalar(c,"SELECT sealed_through_day FROM activity_activation WHERE singleton=1");
            if(seal!=null && Long.parseLong(seal)!=last)throw invalid("Window seal lacks immutable open evidence");
            try(var s=c.createStatement();var rows=s.executeQuery("SELECT * FROM macro_window_closes ORDER BY currency,day_index")) {
                while(rows.next()) {
                    var snapshot=decodeSnapshot(rows.getString("payload")); var open=snapshot.open();
                    if(!snapshot.currency().name().equals(rows.getString("currency"))||open.day()!=rows.getLong("day_index")
                            ||!open.id().toString().equals(rows.getString("open_id"))
                            ||!snapshot.hash().equals(rows.getString("hash")))throw invalid("Window close projection");
                    String openPayload=scalar(c,"SELECT payload FROM macro_window_opens WHERE id=?",open.id().toString());
                    if(openPayload==null||!decodeOpen(openPayload).equals(open))throw invalid("Window close/open linkage");
                    requireFact(c,world,rows.getString("operation_id"),CLOSE,rows.getString("payload"));
                    providerFact(c,rows.getString("operation_id"),CLOSE,snapshot.id().toString(),"01-close/"+snapshot.currency().name(),open.boundaryMillis());
                    for(var day:snapshot.days()) {
                        if(day.index()==open.day())validateDay(c,snapshot.currency(),open,day);
                        else {
                            String payload=scalar(c,"SELECT payload FROM macro_window_closes WHERE currency=? AND day_index=?",snapshot.currency().name(),Long.toString(day.index()));
                            if(payload==null||!decodeSnapshot(payload).days().getLast().equals(day))throw invalid("Window historical day mismatch");
                        }
                    }
                }
            }
        }catch(RuntimeException malformed){throw new SQLException("Invalid window evidence",malformed);}
    }
    private static void validateDay(Connection c,EconomyCurrency currency,Open open,Day day)throws SQLException {
        var amounts=new EnumMap<ActivityFacts.Kind,Amount>(ActivityFacts.Kind.class);
        try(var s=c.prepareStatement("SELECT kind,count,minor FROM activity_day_counters WHERE day_index=? AND currency=?")) {
            s.setLong(1,day.index());s.setString(2,currency.name());try(var r=s.executeQuery()){while(r.next()) amounts.put(ActivityFacts.Kind.valueOf(r.getString(1)),new Amount(r.getLong(2),r.getLong(3)));}
        }
        if(!amounts.equals(day.amounts()))throw invalid("Sealed day counters changed");
        String capacity=scalar(c,"SELECT free_minor FROM activity_capacity_totals_history WHERE currency=? AND accounting_ms<? AND journal_sequence<=? ORDER BY accounting_ms DESC,journal_sequence DESC LIMIT 1",currency.name(),Long.toString(open.boundaryMillis()),Long.toString(open.frontier()));
        if(day.capacityMinor()!=(capacity==null?0:Long.parseLong(capacity)))throw invalid("Window historical capacity mismatch");
        try(var s=c.prepareStatement("SELECT 1 FROM activity_batches b JOIN journal_transactions t ON t.operation_id=b.operation_id WHERE b.day_index=? AND t.sequence>? LIMIT 1")) {
            s.setLong(1,day.index());s.setLong(2,open.frontier());try(var r=s.executeQuery()){if(r.next())throw invalid("Late source in sealed day");}
        }
    }
    private static boolean providerFact(Connection c,String operation,String type,String legacy,String target,long boundary)throws SQLException {
        String version=scalar(c,"SELECT r.record_version FROM journal_transactions t JOIN journal_records r ON r.sequence=t.sequence WHERE t.operation_id=? AND r.record_type=?",operation,type);
        if("1".equals(version)){if(!operation.equals(legacy))throw invalid("Legacy window operation");return false;}
        if(!"2".equals(version))throw invalid("Window source version");
        ca.emilybelnavis.mcmods.economy.simulation.macro.MacroActivation.requireChild(c,operation,target,boundary);
        return true;
    }
    private static long closeCount(Connection c,String id)throws SQLException{return Long.parseLong(scalar(c,"SELECT COUNT(*) FROM macro_window_closes WHERE open_id=?",id));}
    private static long requireFact(Connection c,String world,String operation,String type,String payload)throws SQLException {
        try(var s=c.prepareStatement("SELECT t.sequence FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE t.world_id=? AND t.operation_id=? AND r.record_type=? AND r.record_version IN (1,2) AND r.payload=?")) {
            s.setString(1,world);s.setString(2,operation);s.setString(3,type);s.setString(4,payload);
            try(var r=s.executeQuery()){if(!r.next())throw invalid("Missing window source fact");long sequence=r.getLong(1);if(r.next())throw invalid("Duplicate window fact");return sequence;}
        }
    }
    private static String scalar(Connection c,String sql,String...params)throws SQLException {
        try(var s=c.prepareStatement(sql)){for(int i=0;i<params.length;i++)s.setString(i+1,params[i]);try(var r=s.executeQuery()){return r.next()?r.getString(1):null;}}
    }
    private static long count(Connection c,String sql)throws SQLException{return Long.parseLong(scalar(c,sql));}
    private static String normalize(String sql){return sql.replaceAll("\\s+"," ").trim().replace(";","");}
    private static SQLException invalid(String message){return new SQLException(message);}
}
