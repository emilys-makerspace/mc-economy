package ca.emilybelnavis.mcmods.economy.trade.pricing;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.trade.market.*;
import ca.emilybelnavis.mcmods.economy.valuation.*;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Trusted worker-only source transitions. No command, startup activation or monetary authority. */
public final class TradeSources {
    public static final String TYPE="economy:a9/source-transition",PRICE="economy:a9/reference-demand";
    public static final Journal.Kind TRANSITION=new Journal.Kind("economy:a9","economy:a9/source-transition");
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private TradeSources(){}
    public record Boundary(UUID operation,SourceRef previous,SourceRef next,long effectiveMillis,List<Pricing.Carry> retired){
        public Boundary{Objects.requireNonNull(operation);Objects.requireNonNull(next);retired=List.copyOf(retired);
            if(effectiveMillis<0||retired.size()>64)throw new IllegalArgumentException("Source transition bound");
            if(previous==null?next.revision()!=1:!sameScope(previous,next)||next.revision()!=Math.addExact(previous.revision(),1)||!next.source().equals(previous.source())||!next.activation().equals(previous.activation())||next.epoch().equals(previous.epoch()))throw new IllegalArgumentException("Prospective source revision");
            for(var carry:retired)if(!carry.key().market().equals(next.market())||!carry.key().item().equals(next.item())||carry.key().epoch().equals(next.epoch()))throw new IllegalArgumentException("Retired carry scope");
        }
    }
    public record PriceEvent(WorldId world,UUID operation,MarketFacts.Price previous,MarketFacts.Price next,ReferencePricing.Quote quote){
        public PriceEvent{Objects.requireNonNull(world);Objects.requireNonNull(operation);Objects.requireNonNull(next);Objects.requireNonNull(quote);
            if(previous!=null&&(!previous.market().equals(next.market())||!previous.item().equals(next.item())||next.eligibleTick()<previous.eligibleTick()))throw new IllegalArgumentException("Reference demand scope");
            if(next.revision()!=(previous==null?1:Math.addExact(previous.revision(),1))||!next.demand().equals(quote.nextDemand())||next.eligibleTick()!=quote.nextDemand().anchoredEligibleTick())throw new IllegalArgumentException("Reference demand transition");}
    }
    public static List<String> statements(){
        var sql=new ArrayList<>(List.of(
            "CREATE TABLE trade_source_events(operation_id TEXT NOT NULL,world_id TEXT NOT NULL,market_id TEXT NOT NULL,item TEXT NOT NULL,sequence INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(market_id,item,sequence),UNIQUE(market_id,item,operation_id))",
            "CREATE TABLE trade_source_active(market_id TEXT NOT NULL,item TEXT NOT NULL,operation_id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(market_id,item))",
            "CREATE TABLE trade_source_retirements(market_id TEXT NOT NULL,item TEXT NOT NULL,actor TEXT NOT NULL,side TEXT NOT NULL,epoch TEXT NOT NULL,operation_id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(market_id,item,actor,side,epoch))",
            "CREATE TABLE reference_trade_price_events(operation_id TEXT PRIMARY KEY,world_id TEXT NOT NULL,market_id TEXT NOT NULL,item TEXT NOT NULL,sequence INTEGER NOT NULL UNIQUE,payload TEXT NOT NULL)",
            "CREATE TABLE reference_trade_prices(market_id TEXT NOT NULL,item TEXT NOT NULL,operation_id TEXT NOT NULL REFERENCES reference_trade_price_events(operation_id),payload TEXT NOT NULL,PRIMARY KEY(market_id,item))",
            "CREATE INDEX trade_source_scope_history ON trade_source_events(market_id,item,sequence)",
            "CREATE INDEX reference_price_scope_history ON reference_trade_price_events(market_id,item,sequence)"));
        for(String table:List.of("trade_source_events","trade_source_retirements","reference_trade_price_events")){
            for(String action:List.of("UPDATE","DELETE"))sql.add("CREATE TRIGGER "+table+"_"+action.toLowerCase(Locale.ROOT)+" BEFORE "+action+" ON "+table+" BEGIN SELECT RAISE(ABORT,'immutable source boundary'); END");
            String identity=table.equals("trade_source_events")?"market_id=NEW.market_id AND item=NEW.item AND (operation_id=NEW.operation_id OR sequence=NEW.sequence)":table.equals("trade_source_retirements")?"market_id=NEW.market_id AND item=NEW.item AND actor=NEW.actor AND side=NEW.side AND epoch=NEW.epoch":"operation_id=NEW.operation_id OR sequence=NEW.sequence";
            sql.add("CREATE TRIGGER "+table+"_replace BEFORE INSERT ON "+table+" WHEN EXISTS(SELECT 1 FROM "+table+" WHERE "+identity+") BEGIN SELECT RAISE(ABORT,'existing source boundary'); END");
        }return List.copyOf(sql);
    }
    public static RecordCatalog register(RecordCatalog catalog){return catalog.with(new RecordCatalog.Key(TYPE,1),TradeSources::decodeBoundary).with(new RecordCatalog.Key(PRICE,1),TradeSources::decodePrice);}
    public static SourceRef current(JournalEngine.ReadView reads,UUID market,String item){return at(reads,market,item,Long.MAX_VALUE);}
    private static SourceRef at(JournalEngine.ReadView reads,UUID market,String item,long before){
        var rows=reads.query("SELECT payload FROM trade_source_events WHERE market_id=? AND item=? AND sequence<? ORDER BY sequence DESC LIMIT 1",List.of(market.toString(),item,Long.toString(before)),1);
        return rows.isEmpty()?null:decodeBoundary(rows.getFirst().get("payload")).next();
    }
    public static void requireAt(JournalEngine.ReadView reads,SourceRef ref,long before){if(!ref.equals(at(reads,ref.market(),ref.item(),before)))throw new IllegalArgumentException("Trade source epoch changed");}
    public static void requireCurrent(JournalEngine.ReadView reads,SourceRef ref){requireAt(reads,ref,Long.MAX_VALUE);}
    public static void requireLegacy(JournalEngine.ReadView reads,WorldId world,UUID market,String item){if(current(reads,market,item)!=null)throw new IllegalArgumentException("Legacy trade source retired");}
    private record Requested(SourceRef next,SourceRef previous) {}
    public static Journal.Outcome transition(FoundationStore store,SourceRef next,SourceRef expected,UUID operation,ActorId actor){
        String value=JSON.toJson(new Requested(next,expected));
        var request=new Journal.CanonicalRequest(1,Map.of("source",ValuationData.hash(value)));
        var registration=new JournalEngine.Registration(1,(i,r)->{
            if(!i.request().equals(request))throw new IllegalArgumentException("Source transition request");
            return prepareAdvance(r,next,expected,operation,System.currentTimeMillis());
        });
        return new JournalEngine(store,(a,k)->a.equals(actor)&&k.equals(TRANSITION),Map.of(TRANSITION,registration))
            .submit(new Journal.Intent(new OperationId(operation),new RequestId(operation),actor,TRANSITION,request,null,null));
    }
    public static JournalEngine.Commit prepareAdvance(JournalEngine.ReadView reads,SourceRef next,SourceRef expected,UUID operation,long now){
        requireManifest(reads,next);
        return prepareBoundary(reads,next,expected,operation,now);
    }
    private static JournalEngine.Commit prepareBoundary(JournalEngine.ReadView reads,SourceRef next,SourceRef expected,UUID operation,long now){
        if(!Objects.equals(expected,current(reads,next.market(),next.item())))throw new IllegalArgumentException("Source transition raced");
        var market=MarketStore.market(reads,next.market());if(market==null||!market.world().equals(next.world()))throw new IllegalArgumentException("Source market/world");
        var retired=new ArrayList<Pricing.Carry>();
        var rows=reads.query("SELECT c.payload FROM trade_market_carries c WHERE c.market_id=? AND c.item=? AND NOT EXISTS(SELECT 1 FROM trade_source_retirements r WHERE r.market_id=c.market_id AND r.item=c.item AND r.actor=c.actor AND r.side=c.side AND r.epoch=c.epoch) ORDER BY c.actor,c.side,c.epoch LIMIT 65",List.of(next.market().toString(),next.item()),65);
        if(rows.size()>64)throw new IllegalArgumentException("Carry retirement batch limit");
        for(var row:rows)retired.add(MarketFacts.decode(row.get("payload")).carry().pricing());
        var prior=reads.query("SELECT payload FROM trade_source_events WHERE market_id=? AND item=? ORDER BY sequence DESC LIMIT 1",List.of(next.market().toString(),next.item()),1);
        if(!prior.isEmpty())now=Math.max(now,decodeBoundary(prior.getFirst().get("payload")).effectiveMillis());
        var boundary=new Boundary(operation,expected,next,now,retired);String payload=encode(boundary);
        return new JournalEngine.Commit(List.of(new Journal.Record(TYPE,1,payload)),new Journal.Result(1,"SOURCE_TRANSITION_RECORDED",next.epoch().toString()),tx->{
            long sequence=sequence(tx::query,operation);
            tx.execute("INSERT INTO trade_source_events VALUES(?,?,?,?,?,?)",List.of(operation.toString(),next.world().value().toString(),next.market().toString(),next.item(),Long.toString(sequence),payload));
            tx.execute("INSERT INTO trade_source_active VALUES(?,?,?,?) ON CONFLICT(market_id,item) DO UPDATE SET operation_id=excluded.operation_id,payload=excluded.payload",List.of(next.market().toString(),next.item(),operation.toString(),payload));
            for(var carry:retired){var key=carry.key();tx.execute("INSERT INTO trade_source_retirements VALUES(?,?,?,?,?,?,?)",List.of(key.market().toString(),key.item(),key.actor().toString(),key.side().name(),key.epoch().toString(),operation.toString(),JSON.toJson(carry)));}
        });
    }
    /** Compose with valuation publication for already enabled scopes; never activates a new scope. */
    public static List<JournalEngine.Commit> prepareRefresh(JournalEngine.ReadView reads,UUID manifestId,ValuationData.Manifest manifest,UUID operation,long now){
        var rows=reads.query("SELECT payload FROM trade_source_active ORDER BY market_id,item LIMIT 64",List.of(),64);
        if(rows.size()>63)throw new IllegalArgumentException("Atomic source refresh scope limit");
        var commits=new ArrayList<JournalEngine.Commit>();
        for(var row:rows){var old=decodeBoundary(row.get("payload")).next();
            var next=new SourceRef(old.world(),old.market(),old.item(),old.source(),Math.addExact(old.revision(),1),
                UUID.nameUUIDFromBytes((old.epoch()+"|"+manifestId).getBytes(StandardCharsets.UTF_8)),manifestId,manifest.configHash(),manifest.inputs().recipeHash(),manifest.providerHash(),old.implementation(),old.rounding(),old.activation());
            commits.add(prepareBoundary(reads,next,old,operation,now));
        }
        return List.copyOf(commits);
    }
    private static void requireManifest(JournalEngine.ReadView reads,SourceRef ref){
        var active=reads.query("SELECT manifest_id FROM valuation_active WHERE singleton=1",List.of(),1);
        if(active.isEmpty()||!active.getFirst().get("manifest_id").equals(ref.valuationManifest().toString()))throw new IllegalArgumentException("Valuation source not current");
        var saved=ValuationStore.load(reads,ref.valuationManifest());if(saved==null)throw new IllegalArgumentException("Valuation source missing");
        var manifest=ValuationData.decode(saved.payload());
        if(!manifest.configHash().equals(ref.configHash())||!manifest.inputs().recipeHash().equals(ref.recipeHash())||!manifest.providerHash().equals(ref.providerHash()))throw new IllegalArgumentException("Source provenance mismatch");
    }
    public static MarketFacts.Price price(JournalEngine.ReadView reads,UUID market,String item){return priceBefore(reads,market,item,Long.MAX_VALUE);}
    public static MarketFacts.Price priceBefore(JournalEngine.ReadView reads,UUID market,String item,long before){
        var rows=reads.query("SELECT payload FROM reference_trade_price_events WHERE market_id=? AND item=? AND sequence<? ORDER BY sequence DESC LIMIT 1",List.of(market.toString(),item,Long.toString(before)),1);
        if(!rows.isEmpty())return decodePrice(rows.getFirst().get("payload")).next();
        rows=reads.query("SELECT payload FROM trade_market_events WHERE price_market=? AND price_item=? AND sequence<? ORDER BY sequence DESC LIMIT 1",List.of(market.toString(),item,Long.toString(before)),1);
        return rows.isEmpty()?null:MarketFacts.decode(rows.getFirst().get("payload")).price();
    }
    public static JournalEngine.Commit preparePriceTransition(JournalEngine.ReadView reads,WorldId world,UUID operation,UUID market,String item,long expectedRevision,long tick,ReferencePricing.Quote quote){
        var previous=price(reads,market,item);if((previous==null?0:previous.revision())!=expectedRevision||previous!=null&&tick<previous.eligibleTick())throw new IllegalArgumentException("Reference demand changed");
        var event=new PriceEvent(world,operation,previous,new MarketFacts.Price(market,item,Math.addExact(expectedRevision,1),quote.nextDemand(),tick),quote);
        String payload=encode(event);
        return new JournalEngine.Commit(List.of(new Journal.Record(PRICE,1,payload)),new Journal.Result(1,"REFERENCE_DEMAND_RECORDED",operation.toString()),tx->{
            long seq=sequence(tx::query,operation);
            tx.execute("INSERT INTO reference_trade_price_events VALUES(?,?,?,?,?,?)",List.of(operation.toString(),world.value().toString(),market.toString(),item,Long.toString(seq),payload));
            tx.execute("INSERT INTO reference_trade_prices VALUES(?,?,?,?) ON CONFLICT(market_id,item) DO UPDATE SET operation_id=excluded.operation_id,payload=excluded.payload",List.of(market.toString(),item,operation.toString(),payload));
        });
    }
    public static void requirePriceCommit(JournalEngine.ReadView reads,WorldId world,UUID operation,UUID market,String item,long previousRevision,long tick,ReferencePricing.Quote quote,long sequence){
        var rows=reads.query("SELECT payload,sequence FROM reference_trade_price_events WHERE operation_id=?",List.of(operation.toString()),1);
        if(rows.isEmpty()||Long.parseLong(rows.getFirst().get("sequence"))!=sequence)throw new IllegalArgumentException("Missing reference demand commit");
        var e=decodePrice(rows.getFirst().get("payload"));
        if(!e.world().equals(world)||!e.next().market().equals(market)||!e.next().item().equals(item)||e.next().revision()!=previousRevision+1||e.next().eligibleTick()!=tick||!e.quote().equals(quote))throw new IllegalArgumentException("Reference demand commit mismatch");
    }
    private static boolean sameScope(SourceRef a,SourceRef b){return a.world().equals(b.world())&&a.market().equals(b.market())&&a.item().equals(b.item());}
    public static String encode(Object value){String text=JSON.toJson(value);if(text.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("Source fact bound");return text;}
    public static Boundary decodeBoundary(String text){return decode(text,Boundary.class);}
    public static PriceEvent decodePrice(String text){return decode(text,PriceEvent.class);}
    private static <T>T decode(String text,Class<T> type){if(text==null||text.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("Source fact bound");T result=JSON.fromJson(text,type);if(result==null||!encode(result).equals(text))throw new IllegalArgumentException("Noncanonical source fact");return result;}
    private static long sequence(JournalEngine.ReadView reads,UUID op){return Long.parseLong(reads.query("SELECT sequence FROM journal_transactions WHERE operation_id=?",List.of(op.toString()),1).getFirst().get("sequence"));}
    public static JournalEngine.ReadView reads(Connection c){return(sql,params,max)->{try(var q=c.prepareStatement(sql)){for(int i=0;i<params.size();i++)q.setString(i+1,params.get(i));try(var r=q.executeQuery()){var rows=new ArrayList<Map<String,String>>();while(r.next()){if(rows.size()>=max)throw new SQLException("Source query bound");var row=new LinkedHashMap<String,String>();for(int j=1;j<=r.getMetaData().getColumnCount();j++)row.put(r.getMetaData().getColumnLabel(j),r.getString(j));rows.add(row);}return rows;}}catch(SQLException error){throw new IllegalArgumentException("Invalid source read",error);}};}
    public static void validate(Connection c)throws SQLException{
        try{
            var reads=reads(c);for(String declaration:statements()){String name=declaration.split(" ")[2].split("\\(")[0];var rows=reads.query("SELECT sql FROM sqlite_schema WHERE name=?",List.of(name),1);if(rows.isEmpty()||!normalize(declaration).equals(normalize(rows.getFirst().get("sql"))))throw new IllegalArgumentException("Source schema mismatch");}
            String world=reads.query("SELECT world_id FROM foundation_metadata",List.of(),1).getFirst().get("world_id");
            try(var q=c.createStatement();var rows=q.executeQuery("SELECT * FROM trade_source_events ORDER BY sequence")){while(rows.next()){
                var e=decodeBoundary(rows.getString("payload"));var ref=e.next();long seq=rows.getLong("sequence");
                if(!world.equals(rows.getString("world_id"))||!world.equals(ref.world().value().toString())||!ref.market().toString().equals(rows.getString("market_id"))||!ref.item().equals(rows.getString("item"))||!e.operation().toString().equals(rows.getString("operation_id"))||!Objects.equals(e.previous(),at(reads,ref.market(),ref.item(),seq)))throw new IllegalArgumentException("Source history linkage");
                requireFact(reads,e.operation(),TYPE,rows.getString("payload"),seq);
                var priorBoundary=reads.query("SELECT payload FROM trade_source_events WHERE market_id=? AND item=? AND sequence<? ORDER BY sequence DESC LIMIT 1",List.of(ref.market().toString(),ref.item(),Long.toString(seq)),1);
                if(!priorBoundary.isEmpty()&&decodeBoundary(priorBoundary.getFirst().get("payload")).effectiveMillis()>e.effectiveMillis())throw new IllegalArgumentException("Source time rewind");
                var saved=ValuationStore.load(reads,ref.valuationManifest());if(saved==null)throw new IllegalArgumentException("Missing source valuation");
                var m=ValuationData.decode(saved.payload());if(!m.configHash().equals(ref.configHash())||!m.inputs().recipeHash().equals(ref.recipeHash())||!m.providerHash().equals(ref.providerHash()))throw new IllegalArgumentException("Source historical inputs");
                for(var carry:e.retired()){
                    var k=carry.key();var original=reads.query("SELECT payload FROM trade_market_events WHERE carry_key=? AND sequence<? ORDER BY sequence DESC LIMIT 1",List.of(MarketFacts.stable(k.market()+"|"+k.item()+"|"+k.actor()+"|"+k.side()+"|"+k.epoch()).toString(),Long.toString(seq)),1);
                    if(original.isEmpty()||!MarketFacts.decode(original.getFirst().get("payload")).carry().pricing().equals(carry))throw new IllegalArgumentException("Carry retirement original changed");
                }
            }}
            try(var q=c.createStatement();var rows=q.executeQuery("SELECT * FROM reference_trade_price_events ORDER BY sequence")){while(rows.next()){
                var e=decodePrice(rows.getString("payload"));long seq=rows.getLong("sequence");if(!world.equals(rows.getString("world_id"))||!world.equals(e.world().value().toString())||!e.operation().toString().equals(rows.getString("operation_id"))||!e.next().market().toString().equals(rows.getString("market_id"))||!e.next().item().equals(rows.getString("item"))||!Objects.equals(e.previous(),priceBefore(reads,e.next().market(),e.next().item(),seq)))throw new IllegalArgumentException("Reference demand history");
                requireFact(reads,e.operation(),PRICE,rows.getString("payload"),seq);
                var receipts=reads.query("SELECT COUNT(*) AS n FROM journal_records WHERE sequence=? AND record_type IN ('economy:a9/reference-npc-trade','economy:a9/reference-cash-trade')",List.of(Long.toString(seq)),1);
                if(!receipts.getFirst().get("n").equals("1"))throw new IllegalArgumentException("Reference demand requires one committed trade");
            }}
            long retirementCount=0;
            try(var q=c.createStatement();var events=q.executeQuery("SELECT payload FROM trade_source_events")){while(events.next()){
                var boundary=decodeBoundary(events.getString(1));for(var carry:boundary.retired()){
                    retirementCount++;var key=carry.key();var rows=reads.query("SELECT operation_id,payload FROM trade_source_retirements WHERE market_id=? AND item=? AND actor=? AND side=? AND epoch=?",List.of(key.market().toString(),key.item(),key.actor().toString(),key.side().name(),key.epoch().toString()),1);
                    if(rows.isEmpty()||!rows.getFirst().get("operation_id").equals(boundary.operation().toString())||!rows.getFirst().get("payload").equals(JSON.toJson(carry)))throw new IllegalArgumentException("Retired carry projection changed");
                }
            }}
            if(retirementCount!=Long.parseLong(reads.query("SELECT COUNT(*) AS n FROM trade_source_retirements",List.of(),1).getFirst().get("n")))throw new IllegalArgumentException("Orphan retired carry");
            checkLatest(reads,"trade_source_active","trade_source_events");checkLatest(reads,"reference_trade_prices","reference_trade_price_events");
            for(var pair:List.of(List.of("trade_source_events",TYPE),List.of("reference_trade_price_events",PRICE))){var a=reads.query("SELECT COUNT(*) AS n FROM "+pair.get(0),List.of(),1);var b=reads.query("SELECT COUNT(*) AS n FROM journal_records WHERE record_type=?",List.of(pair.get(1)),1);if(!a.getFirst().get("n").equals(b.getFirst().get("n")))throw new IllegalArgumentException("Orphan source facts");}
        }catch(RuntimeException failure){throw new SQLException("Invalid reference source history",failure);}
    }
    private static void checkLatest(JournalEngine.ReadView reads,String projection,String history){
        var missing=reads.query("SELECT COUNT(*) AS n FROM "+history+" h WHERE NOT EXISTS(SELECT 1 FROM "+projection+" p WHERE p.market_id=h.market_id AND p.item=h.item)",List.of(),1);if(!missing.getFirst().get("n").equals("0"))throw new IllegalArgumentException("Missing source projection");
        for(var row:reads.query("SELECT * FROM "+projection,List.of(),4096)){var latest=reads.query("SELECT payload,operation_id FROM "+history+" WHERE market_id=? AND item=? ORDER BY sequence DESC LIMIT 1",List.of(row.get("market_id"),row.get("item")),1);if(latest.isEmpty()||!latest.getFirst().get("payload").equals(row.get("payload"))||!latest.getFirst().get("operation_id").equals(row.get("operation_id")))throw new IllegalArgumentException("Source projection mismatch");}
    }
    private static void requireFact(JournalEngine.ReadView reads,UUID op,String type,String payload,long seq){var rows=reads.query("SELECT COUNT(*) AS n FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE t.operation_id=? AND r.sequence=? AND r.record_type=? AND r.record_version=1 AND r.payload=?",List.of(op.toString(),Long.toString(seq),type,payload),1);if(!rows.getFirst().get("n").equals("1"))throw new IllegalArgumentException("Missing source journal fact");}
    private static String normalize(String value){return value.replaceAll("\\s+"," ").strip();}
}
