package ca.emilybelnavis.mcmods.economy.trade.catalog;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Worker-only original offer capture. No entity/catalog factory, financial or item mutations. */
public final class CatalogStore {
    private CatalogStore() {}
    private static final Journal.Kind KIND=new Journal.Kind("economy:a8","economy:a8/catalog-capture");
    public static List<String> statements() {
        var sql=new ArrayList<String>(List.of(
                "CREATE TABLE trade_catalogs(trader_id TEXT NOT NULL,revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision>0),world_id TEXT NOT NULL,operation_id TEXT NOT NULL UNIQUE,payload TEXT NOT NULL,PRIMARY KEY(trader_id,revision))",
                "CREATE TABLE trade_catalog_offers(trader_id TEXT NOT NULL,revision INTEGER NOT NULL,ordinal INTEGER NOT NULL CHECK(typeof(ordinal)='integer' AND ordinal BETWEEN 0 AND 31),payload TEXT NOT NULL,PRIMARY KEY(trader_id,revision,ordinal),FOREIGN KEY(trader_id,revision) REFERENCES trade_catalogs(trader_id,revision))",
                "CREATE INDEX trade_catalog_facts ON journal_records(record_type,record_version,payload) WHERE record_type IN ('economy:a8/trade-catalog','economy:a8/trade-offer')"));
        for(String table:List.of("trade_catalogs","trade_catalog_offers")) {
            for(String action:List.of("UPDATE","DELETE"))sql.add("CREATE TRIGGER "+table+"_"+action.toLowerCase(Locale.ROOT)+" BEFORE "+action+" ON "+table+" BEGIN SELECT RAISE(ABORT,'immutable captured catalog'); END");
            String identity=table.equals("trade_catalogs")?"trader_id=NEW.trader_id AND revision=NEW.revision OR operation_id=NEW.operation_id":"trader_id=NEW.trader_id AND revision=NEW.revision AND ordinal=NEW.ordinal";
            sql.add("CREATE TRIGGER "+table+"_replace BEFORE INSERT ON "+table+" WHEN EXISTS(SELECT 1 FROM "+table+" WHERE "+identity+") BEGIN SELECT RAISE(ABORT,'existing captured catalog'); END");
        }
        return List.copyOf(sql);
    }
    public static TradeCatalog.Capture capture(FoundationStore store,TradeCatalog.Capture capture) {
        var world=store.snapshot().worldId();UUID operation=operation(world,capture.trader(),capture.revision());
        var head=new CatalogFacts.Head(world,capture.trader(),capture.revision(),capture.offers().size(),CatalogFacts.digest(capture));
        var actor=new SystemActorId(UUID.nameUUIDFromBytes(("economy:a8:catalog-actor|"+world.value()).getBytes(StandardCharsets.UTF_8)));
        var request=new Journal.CanonicalRequest(1,Map.of("digest",head.digest()));
        var registration=new JournalEngine.Registration(1,(intent,reads)->{
            var prior=reads.query("SELECT revision FROM trade_catalogs WHERE trader_id=? ORDER BY revision DESC LIMIT 1",List.of(capture.trader().toString()),1);
            long expected=prior.isEmpty()?1:Math.addExact(Long.parseLong(prior.getFirst().get("revision")),1);
            if(expected!=capture.revision())return new JournalEngine.Reject(new Journal.Result(1,"CATALOG_REVISION_CONFLICT",""));
            var records=new ArrayList<Journal.Record>();records.add(new Journal.Record(CatalogFacts.HEAD,1,CatalogFacts.encode(head)));
            for(var offer:capture.offers())records.add(new Journal.Record(CatalogFacts.OFFER,1,CatalogFacts.encode(new CatalogFacts.Entry(world,capture.trader(),capture.revision(),offer))));
            return new JournalEngine.Commit(records,new Journal.Result(1,"CATALOG_CAPTURED",head.digest()),tx->{
                tx.execute("INSERT INTO trade_catalogs VALUES(?,?,?,?,?)",List.of(capture.trader().toString(),Long.toString(capture.revision()),world.value().toString(),operation.toString(),CatalogFacts.encode(head)));
                for(var offer:capture.offers())tx.execute("INSERT INTO trade_catalog_offers VALUES(?,?,?,?)",List.of(capture.trader().toString(),Long.toString(capture.revision()),Integer.toString(offer.ordinal()),CatalogFacts.encode(new CatalogFacts.Entry(world,capture.trader(),capture.revision(),offer))));
            });
        });
        var engine=new JournalEngine(store,(a,k)->a.equals(actor)&&k.equals(KIND),Map.of(KIND,registration));
        var outcome=engine.submit(new Journal.Intent(new OperationId(operation),new RequestId(operation),actor,KIND,request,null,null));
        if(outcome.status()!=Journal.Status.COMMITTED)throw new IllegalArgumentException("Catalog capture: "+outcome.result().code());
        return store.transaction(tx->read(tx::query,capture.trader(),capture.revision()));
    }
    public static TradeCatalog.Capture read(JournalEngine.ReadView reads,UUID trader,long revision) {
        var heads=reads.query("SELECT payload FROM trade_catalogs WHERE trader_id=? AND revision=?",List.of(trader.toString(),Long.toString(revision)),1);
        if(heads.isEmpty())return null;
        var head=CatalogFacts.decodeHead(heads.getFirst().get("payload"));
        var offers=new ArrayList<TradeCatalog.Offer>();
        for(var row:reads.query("SELECT payload FROM trade_catalog_offers WHERE trader_id=? AND revision=? ORDER BY ordinal",List.of(trader.toString(),Long.toString(revision)),32)) {
            var entry=CatalogFacts.decodeEntry(row.get("payload"));
            if(!entry.world().equals(head.world())||!entry.trader().equals(trader)||entry.revision()!=revision)throw new IllegalStateException("Catalog entry linkage");
            offers.add(entry.offer());
        }
        var capture=new TradeCatalog.Capture(trader,revision,offers);
        if(head.count()!=offers.size()||!head.digest().equals(CatalogFacts.digest(capture)))throw new IllegalStateException("Captured catalog digest");
        return capture;
    }
    public static void validate(Connection c)throws SQLException {
        try {
            for(var declaration:statements()) {
                String name=declaration.split(" ")[2].split("\\(")[0];
                try(var q=c.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
                    q.setString(1,name);try(var r=q.executeQuery()) {if(!r.next()||!normalize(declaration).equals(normalize(r.getString(1))))throw new SQLException("Changed catalog schema: "+name);}
                }
            }
            String world=scalar(c,"SELECT world_id FROM foundation_metadata WHERE singleton=1");
            long heads=0,offers=0;String previousTrader="";long previousRevision=0;
            try(var q=c.createStatement();var rows=q.executeQuery("SELECT * FROM trade_catalogs ORDER BY trader_id,revision")) {
                while(rows.next()) {
                    var head=CatalogFacts.decodeHead(rows.getString("payload"));heads++;
                    String trader=head.trader().toString();long revision=head.revision();
                    long expected=previousTrader.equals(trader)?previousRevision+1:1;
                    if(!head.world().value().toString().equals(world)||!rows.getString("world_id").equals(world)
                            ||!rows.getString("trader_id").equals(trader)||rows.getLong("revision")!=revision||expected!=revision
                            ||!rows.getString("operation_id").equals(operation(head.world(),head.trader(),revision).toString()))throw new SQLException("Catalog identity/revision mismatch");
                    previousTrader=trader;previousRevision=revision;
                    requireFact(c,rows.getString("operation_id"),CatalogFacts.HEAD,rows.getString("payload"));
                    var captured=new ArrayList<TradeCatalog.Offer>();
                    try(var query=c.prepareStatement("SELECT ordinal,payload FROM trade_catalog_offers WHERE trader_id=? AND revision=? ORDER BY ordinal")) {
                        query.setString(1,trader);query.setLong(2,revision);try(var r=query.executeQuery()) {
                            while(r.next()) {
                                if(captured.size()>=32)throw new SQLException("Catalog offer bound");
                                var entry=CatalogFacts.decodeEntry(r.getString("payload"));
                                if(!entry.world().equals(head.world())||!entry.trader().equals(head.trader())||entry.revision()!=revision||entry.offer().ordinal()!=r.getInt("ordinal"))throw new SQLException("Catalog offer linkage");
                                captured.add(entry.offer());offers++;requireFact(c,rows.getString("operation_id"),CatalogFacts.OFFER,r.getString("payload"));
                            }
                        }
                    }
                    var capture=new TradeCatalog.Capture(head.trader(),revision,captured);
                    if(captured.size()!=head.count()||!CatalogFacts.digest(capture).equals(head.digest()))throw new SQLException("Captured catalog mismatch");
                }
            }
            if(heads!=Long.parseLong(scalar(c,"SELECT count(*) FROM journal_records WHERE record_type='"+CatalogFacts.HEAD+"'"))
                    ||offers!=Long.parseLong(scalar(c,"SELECT count(*) FROM journal_records WHERE record_type='"+CatalogFacts.OFFER+"'"))
                    ||offers!=Long.parseLong(scalar(c,"SELECT count(*) FROM trade_catalog_offers")))throw new SQLException("Orphan catalog evidence");
        }catch(RuntimeException invalid){throw new SQLException("Invalid catalog evidence",invalid);}
    }
    private static UUID operation(WorldId world,UUID trader,long revision){return UUID.nameUUIDFromBytes(("economy:a8:catalog:v1|"+world.value()+"|"+trader+"|"+revision).getBytes(StandardCharsets.UTF_8));}
    private static String normalize(String text){return text.replaceAll("\\s+"," ").strip();}
    private static String scalar(Connection c,String sql)throws SQLException{try(var q=c.createStatement();var r=q.executeQuery(sql)){if(!r.next())throw new SQLException("Missing catalog scalar");return r.getString(1);}}
    private static void requireFact(Connection c,String operation,String type,String payload)throws SQLException {
        try(var q=c.prepareStatement("SELECT count(*) FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE t.operation_id=? AND r.record_type=? AND r.record_version=1 AND r.payload=?")) {
            q.setString(1,operation);q.setString(2,type);q.setString(3,payload);try(var r=q.executeQuery()){if(!r.next()||r.getInt(1)!=1)throw new SQLException("Catalog missing immutable fact");}
        }
    }
}
