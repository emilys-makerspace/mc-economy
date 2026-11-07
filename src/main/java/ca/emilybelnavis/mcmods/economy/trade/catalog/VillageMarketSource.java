package ca.emilybelnavis.mcmods.economy.trade.catalog;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.trade.market.*;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans;
import java.sql.*;
import java.util.*;

/** Explicit bounded A7 source importer. New captured plans invalidate the completion witness. */
public final class VillageMarketSource {
    private VillageMarketSource() {}
    public static final String FACT="economy:a8/market-source-sync";
    private static final Journal.Kind KIND=new Journal.Kind("economy:a8","economy:a8/market-source-sync");
    public record State(WorldId world,long revision,long frontier,String cursor,boolean complete) {
        public State {Objects.requireNonNull(world);if(revision<1||frontier<0||cursor==null||(!cursor.isEmpty()&&!UUID.fromString(cursor).toString().equals(cursor)))throw new IllegalArgumentException("Market source state");}
    }
    public static RecordCatalog register(RecordCatalog catalog){return catalog.with(new RecordCatalog.Key(FACT,1),VillageMarketSource::decode);}
    private static String encode(State s){return s.world().value()+"|"+s.revision()+"|"+s.frontier()+"|"+s.cursor()+"|"+(s.complete()?"1":"0");}
    private static State decode(String text) {
        String[] p=text.split("\\|",-1);if(p.length!=5||(!p[4].equals("0")&&!p[4].equals("1")))throw new IllegalArgumentException("Market source fact");
        var s=new State(new WorldId(UUID.fromString(p[0])),Long.parseLong(p[1]),Long.parseLong(p[2]),p[3],p[4].equals("1"));
        if(!encode(s).equals(text))throw new IllegalArgumentException("Noncanonical market source");return s;
    }
    public static List<String> statements(){return List.of(
            "CREATE TABLE trade_market_source_sync(singleton INTEGER PRIMARY KEY CHECK(singleton=1),revision INTEGER NOT NULL,frontier INTEGER NOT NULL,cursor TEXT NOT NULL,complete INTEGER NOT NULL CHECK(complete IN (0,1)),payload TEXT NOT NULL)",
            "CREATE TABLE trade_market_source_events(revision INTEGER PRIMARY KEY,operation_id TEXT NOT NULL UNIQUE,payload TEXT NOT NULL)",
            "CREATE INDEX trade_market_a7_frontier ON journal_records(record_type,sequence)",
            "CREATE TRIGGER trade_market_source_events_update BEFORE UPDATE ON trade_market_source_events BEGIN SELECT RAISE(ABORT,'immutable market source'); END",
            "CREATE TRIGGER trade_market_source_events_delete BEFORE DELETE ON trade_market_source_events BEGIN SELECT RAISE(ABORT,'immutable market source'); END",
            "CREATE TRIGGER trade_market_source_events_replace BEFORE INSERT ON trade_market_source_events WHEN EXISTS(SELECT 1 FROM trade_market_source_events WHERE revision=NEW.revision OR operation_id=NEW.operation_id) BEGIN SELECT RAISE(ABORT,'existing source receipt'); END");}
    private static long frontier(JournalEngine.ReadView reads) {
        var rows=reads.query("SELECT sequence FROM journal_records WHERE record_type=? ORDER BY sequence DESC LIMIT 1",List.of(Branches.PLAN_PART),1);
        return rows.isEmpty()?0:Long.parseLong(rows.getFirst().get("sequence"));
    }
    private static State state(JournalEngine.ReadView reads) {
        var rows=reads.query("SELECT payload FROM trade_market_source_sync WHERE singleton=1",List.of(),1);
        return rows.isEmpty()?null:decode(rows.getFirst().get("payload"));
    }
    public static boolean ready(JournalEngine.ReadView reads) {
        long frontier=frontier(reads);var s=state(reads);
        if(frontier==0)return reads.query("SELECT village_id FROM village_plans LIMIT 1",List.of(),1).isEmpty();
        return s!=null&&s.frontier()==frontier&&s.complete();
    }
    /** One journal mutation at most: initialize/reset, import, cell page, cursor or completion. */
    public static boolean step(FoundationStore store,EconomyCurrency serverCurrency) {
        if(store.transaction(tx->ready(tx::query)))return true;
        var old=store.transaction(tx->state(tx::query));long frontier=store.transaction(tx->frontier(tx::query));
        if(frontier==0)throw new IllegalArgumentException("A7 plans lack source facts");
        if(old==null||old.frontier()!=frontier) {
            save(store,old,new State(store.snapshot().worldId(),old==null?1:old.revision()+1,frontier,"",false));return false;
        }
        var rows=store.transaction(tx->tx.query("SELECT village_id,plan FROM village_plans WHERE world_id=? AND village_id>? ORDER BY village_id LIMIT 1",List.of(old.world().value().toString(),old.cursor()),1));
        if(rows.isEmpty()){save(store,old,new State(old.world(),old.revision()+1,frontier,old.cursor(),true));return true;}
        var row=rows.getFirst();var plan=BranchPlans.decode(row.get("plan"));UUID id=BranchPlans.id(old.world(),plan.source());
        if(!id.toString().equals(row.get("village_id")))throw new IllegalArgumentException("A7 source identity mismatch");
        if(plan.captureStatus()==BranchPlans.CaptureStatus.CAPTURE_PENDING)
            throw new ca.emilybelnavis.mcmods.economy.storage.StorageException(ca.emilybelnavis.mcmods.economy.storage.StorageException.Kind.ECONOMIC_CATCH_UP,"A7 source capture is still pending");
        if(store.transaction(tx->MarketStore.market(tx::query,id))==null) {
            var bounds=envelope(plan.originalBounds());
            var slot=store.transaction(tx->Branches.find(tx::query,old.world(),BranchPlans.branchId(id,0)));
            EconomyCurrency currency=serverCurrency;
            if(slot.isPresent()&&slot.get().choice().nativeCurrency()!=null)currency=slot.get().choice().nativeCurrency();
            var anchor=new MarketFacts.Position(plan.source().dimension(),Math.multiplyExact(plan.source().startX(),16),bounds.minY(),Math.multiplyExact(plan.source().startZ(),16));
            MarketStore.importVillage(store,plan.source(),bounds,anchor,currency);return false;
        }
        var index=store.transaction(tx->tx.query("SELECT ready FROM trade_market_indexes WHERE market_id=?",List.of(id.toString()),1));
        if(index.isEmpty()||!index.getFirst().get("ready").equals("1")){MarketStore.indexPage(store,id);return false;}
        save(store,old,new State(old.world(),old.revision()+1,frontier,id.toString(),false));return false;
    }
    private static BranchPlans.Bounds envelope(List<BranchPlans.Bounds> boxes) {
        int minX=Integer.MAX_VALUE,minY=minX,minZ=minX,maxX=Integer.MIN_VALUE,maxY=maxX,maxZ=maxX;
        for(var b:boxes){minX=Math.min(minX,b.minX());minY=Math.min(minY,b.minY());minZ=Math.min(minZ,b.minZ());maxX=Math.max(maxX,b.maxX());maxY=Math.max(maxY,b.maxY());maxZ=Math.max(maxZ,b.maxZ());}
        return new BranchPlans.Bounds(minX,minY,minZ,maxX,maxY,maxZ);
    }
    private static void save(FoundationStore store,State old,State next) {
        String payload=encode(next);UUID operation=MarketFacts.stable("source-sync|"+payload);
        var actor=new SystemActorId(MarketFacts.stable("source-sync-actor|"+next.world().value()));
        var request=new Journal.CanonicalRequest(1,Map.of("state",payload));
        var registration=new JournalEngine.Registration(1,(intent,reads)->{
            if(!Objects.equals(old,state(reads))||next.frontier()!=frontier(reads))throw new IllegalArgumentException("Source sync changed");
            return new JournalEngine.Commit(List.of(new Journal.Record(FACT,1,payload)),new Journal.Result(1,"MARKET_SOURCE_SYNCED",next.cursor()),tx->{
                tx.execute("INSERT INTO trade_market_source_events VALUES(?,?,?)",List.of(Long.toString(next.revision()),operation.toString(),payload));
                tx.execute("INSERT INTO trade_market_source_sync VALUES(1,?,?,?,?,?) ON CONFLICT(singleton) DO UPDATE SET revision=excluded.revision,frontier=excluded.frontier,cursor=excluded.cursor,complete=excluded.complete,payload=excluded.payload",List.of(Long.toString(next.revision()),Long.toString(next.frontier()),next.cursor(),next.complete()?"1":"0",payload));
            });
        });
        var engine=new JournalEngine(store,(a,k)->a.equals(actor)&&k.equals(KIND),Map.of(KIND,registration));
        var receipt=engine.submit(new Journal.Intent(new OperationId(operation),new RequestId(operation),actor,KIND,request,null,null));
        if(receipt.status()!=Journal.Status.COMMITTED)throw new IllegalArgumentException("Source sync failed");
    }
    public static void validate(Connection c)throws SQLException {
        try {
            for(var expected:statements()) {
                String name=expected.split(" ")[2].split("\\(")[0];
                try(var q=c.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")){q.setString(1,name);try(var r=q.executeQuery()){if(!r.next()||!normalize(expected).equals(normalize(r.getString(1))))throw new SQLException("Changed market source schema");}}
            }
            State last=null;long count=0;String world=scalar(c,"SELECT world_id FROM foundation_metadata WHERE singleton=1");
            try(var q=c.createStatement();var r=q.executeQuery("SELECT * FROM trade_market_source_events ORDER BY revision")) {
                while(r.next()) {
                    var s=decode(r.getString("payload"));count++;
                    if(s.revision()!=count||r.getLong("revision")!=count||!s.world().value().toString().equals(world)
                            ||!MarketFacts.stable("source-sync|"+encode(s)).toString().equals(r.getString("operation_id"))
                            ||last!=null&&(s.frontier()<last.frontier()||s.frontier()==last.frontier()&&(last.complete()||s.cursor().compareTo(last.cursor())<0)))throw new SQLException("Market source chronology");
                    try(var fact=c.prepareStatement("SELECT count(*) FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE t.world_id=? AND t.operation_id=? AND r.record_type=? AND r.record_version=1 AND r.payload=?")) {
                        fact.setString(1,world);fact.setString(2,r.getString("operation_id"));fact.setString(3,FACT);fact.setString(4,encode(s));try(var f=fact.executeQuery()){if(!f.next()||f.getLong(1)!=1)throw new SQLException("Market source fact linkage");}
                    }
                    last=s;
                }
            }
            String projection=scalar(c,"SELECT payload FROM trade_market_source_sync WHERE singleton=1");
            if(!Objects.equals(last==null?null:encode(last),projection)||count!=Long.parseLong(scalar(c,"SELECT count(*) FROM journal_records WHERE record_type='"+FACT+"'")))throw new SQLException("Market source projection mismatch");
            if(last!=null && last.complete()) {
                long latest=Long.parseLong(scalar(c,"SELECT COALESCE(MAX(sequence),0) FROM journal_records WHERE record_type='"+Branches.PLAN_PART+"'"));
                if(last.frontier()==latest) {
                    try(var q=c.createStatement();var r=q.executeQuery("SELECT v.village_id FROM village_plans v LEFT JOIN trade_markets m ON m.id=v.village_id LEFT JOIN trade_market_indexes i ON i.market_id=m.id WHERE m.id IS NULL OR m.kind!='VILLAGE' OR i.ready!=1 OR i.ready IS NULL LIMIT 1")) {
                        if(r.next())throw new SQLException("Source completion omits a village/index");
                    }
                }
            }
            if(last!=null) {
                try(var q=c.createStatement();var r=q.executeQuery("SELECT * FROM trade_market_source_sync")) {
                    if(!r.next()||r.getLong("revision")!=last.revision()||r.getLong("frontier")!=last.frontier()||!r.getString("cursor").equals(last.cursor())||(r.getInt("complete")==1)!=last.complete())throw new SQLException("Market source head fields");
                }
            }
        }catch(RuntimeException invalid){throw new SQLException("Invalid market source",invalid);}
    }
    private static String normalize(String text){return text.replaceAll("\\s+"," ").strip();}
    private static String scalar(Connection c,String sql)throws SQLException{try(var q=c.createStatement();var r=q.executeQuery(sql)){return r.next()?r.getString(1):null;}}
}
