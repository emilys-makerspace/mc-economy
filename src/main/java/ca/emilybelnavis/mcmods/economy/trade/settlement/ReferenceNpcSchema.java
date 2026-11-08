package ca.emilybelnavis.mcmods.economy.trade.settlement;

import ca.emilybelnavis.mcmods.economy.trade.catalog.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.ServicePricing;
import ca.emilybelnavis.mcmods.economy.trade.market.MarketFacts;
import ca.emilybelnavis.mcmods.economy.trade.pricing.*;
import ca.emilybelnavis.mcmods.economy.trade.stock.StockFacts;
import java.sql.*;
import java.util.*;
import static ca.emilybelnavis.mcmods.economy.trade.settlement.ReferenceNpcFacts.*;

/** Strict additive quote/receipt projections for source-neutral account settlement. */
public final class ReferenceNpcSchema {
    private ReferenceNpcSchema() {}
    public static List<String> statements(){var sql=new ArrayList<>(List.of(
        "CREATE TABLE reference_npc_trade_adapter(singleton INTEGER PRIMARY KEY CHECK(singleton=1),world_id TEXT NOT NULL,operation_id TEXT NOT NULL,payload TEXT NOT NULL)",
        "CREATE TABLE reference_npc_trade_quotes(id TEXT PRIMARY KEY,world_id TEXT NOT NULL,operation_id TEXT NOT NULL UNIQUE,financial_operation TEXT NOT NULL UNIQUE,payload TEXT NOT NULL)",
        "CREATE TABLE reference_npc_trade_receipts(operation_id TEXT PRIMARY KEY,quote_id TEXT NOT NULL UNIQUE REFERENCES reference_npc_trade_quotes(id),world_id TEXT NOT NULL,effective_ms INTEGER NOT NULL CHECK(typeof(effective_ms)='integer' AND effective_ms>=0),fx_quote_id TEXT UNIQUE,payload TEXT NOT NULL)",
        "CREATE INDEX reference_npc_trade_fact_lookup ON journal_records(record_type,record_version,payload) WHERE record_type IN ('economy:a9/reference-npc-adapter','economy:a9/reference-npc-quote','economy:a9/reference-npc-trade')"));
        for(String table:List.of("reference_npc_trade_adapter","reference_npc_trade_quotes","reference_npc_trade_receipts")){
            for(String action:List.of("UPDATE","DELETE"))sql.add("CREATE TRIGGER "+table+"_"+action.toLowerCase(Locale.ROOT)+" BEFORE "+action+" ON "+table+" BEGIN SELECT RAISE(ABORT,'immutable NPC source'); END");
            String conflict=table.equals("reference_npc_trade_adapter")?"singleton=NEW.singleton":table.equals("reference_npc_trade_quotes")?"id=NEW.id OR operation_id=NEW.operation_id OR financial_operation=NEW.financial_operation":"operation_id=NEW.operation_id OR quote_id=NEW.quote_id";
            sql.add("CREATE TRIGGER "+table+"_replace BEFORE INSERT ON "+table+" WHEN EXISTS(SELECT 1 FROM "+table+" WHERE "+conflict+") BEGIN SELECT RAISE(ABORT,'existing NPC source'); END");
        }return List.copyOf(sql);}
    public static void validate(Connection c)throws SQLException {
        try {
            for(String expected:statements()){String name=expected.split(" ")[2].split("\\(",2)[0],actual=scalar(c,"SELECT sql FROM sqlite_master WHERE name=?",name);if(actual==null||!normalize(expected).equals(normalize(actual)))throw invalid("NPC schema: "+name);}
            String world=scalar(c,"SELECT world_id FROM foundation_metadata WHERE singleton=1");
            requireCounts(c,"reference_npc_trade_quotes",QUOTE);requireCounts(c,"reference_npc_trade_receipts",RECEIPT);requireCounts(c,"reference_npc_trade_adapter",ADAPTER);
            try(var s=c.createStatement();var rows=s.executeQuery("SELECT * FROM reference_npc_trade_quotes")){while(rows.next()){
                var q=decodeQuote(rows.getString("payload"));
                if(!q.world().value().toString().equals(world)||!world.equals(rows.getString("world_id"))||!q.id().toString().equals(rows.getString("id"))||!q.terms().operation().toString().equals(rows.getString("financial_operation"))||!stable("book|"+world+"|"+q.id()).toString().equals(rows.getString("operation_id")))throw invalid("NPC quote identity");
                long seq=requireFact(c,rows.getString("operation_id"),QUOTE,rows.getString("payload"));validateQuote(c,q,seq);
            }}
            long receipts=0;
            try(var s=c.createStatement();var rows=s.executeQuery("SELECT * FROM reference_npc_trade_receipts")){while(rows.next()){
                receipts++;var receipt=decodeReceipt(rows.getString("payload"));String payload=scalar(c,"SELECT payload FROM reference_npc_trade_quotes WHERE id=?",receipt.quote().toString());if(payload==null)throw invalid("NPC receipt quote missing");var q=decodeQuote(payload);
                if(!receipt.world().value().toString().equals(world)||!world.equals(rows.getString("world_id"))||!receipt.operation().toString().equals(rows.getString("operation_id"))||!receipt.quote().toString().equals(rows.getString("quote_id"))||receipt.effectiveMillis()!=rows.getLong("effective_ms"))throw invalid("NPC receipt projection");
                long seq=requireFact(c,receipt.operation().toString(),RECEIPT,rows.getString("payload"));
                TradeSources.requireAt(reads(c),q.terms().source().ref(),seq);
                String groups=scalar(c,"SELECT payload FROM journal_records WHERE sequence=? AND record_type=? AND record_version=1",Long.toString(seq),NpcSettlement.GROUPS);
                if(groups==null||!"1".equals(scalar(c,"SELECT COUNT(*) FROM journal_records WHERE sequence=? AND record_type=?",Long.toString(seq),NpcSettlement.GROUPS))||!NpcSettlement.decode(groups).world().equals(q.world())||!NpcSettlement.decode(groups).groups().equals(ReferenceNpcTrades.expectedGroups(q)))throw invalid("NPC posting groups differ from captured quote");
                if(!"0".equals(scalar(c,"SELECT COUNT(*) FROM journal_records WHERE sequence=? AND record_type=?",Long.toString(seq),FxSettlement.FACT)))throw invalid("Duplicate generic NPC FX source");
                if(!Objects.equals(rows.getString("fx_quote_id"),q.fx()==null?null:q.fx().conversion().id().toString()))throw invalid("NPC FX consumption owner");
                if(q.fx()!=null){if(!"1".equals(scalar(c,"SELECT COUNT(*) FROM ledger_fx_quotes WHERE quote_id=?",q.fx().conversion().id().toString())))throw invalid("NPC FX quote was not consumed");
                    var pricingFact=ServicePricing.fact(q.fx().pricing());if(!"1".equals(scalar(c,"SELECT COUNT(*) FROM journal_records WHERE sequence=? AND record_type=? AND record_version=? AND payload=?",Long.toString(seq),pricingFact.type(),Integer.toString(pricingFact.version()),pricingFact.payload())))throw invalid("NPC supplier fee capture");}
                String bookSequence=scalar(c,"SELECT t.sequence FROM reference_npc_trade_quotes q JOIN journal_transactions t ON t.operation_id=q.operation_id WHERE q.id=?",q.id().toString());if(bookSequence==null||Long.parseLong(bookSequence)>=seq)throw invalid("NPC quote chronology");
                if(!receipt.operation().equals(q.terms().operation())||!receipt.actor().equals(q.terms().actor())||!receipt.account().equals(q.terms().account().value())||!receipt.trader().equals(q.terms().trader())||!receipt.market().equals(q.market())||receipt.side()!=q.priceRequest().key().side()||receipt.currency()!=q.priceRequest().basis().currency()||receipt.goodsPrincipal()!=q.price().minor()||receipt.effectiveMillis()<q.issuedMillis()||receipt.effectiveMillis()>=q.expiresMillis()||!Objects.equals(receipt.inputCustody(),q.terms().inputCustody())||!Objects.equals(receipt.outputCustody(),q.terms().outputCustody()))throw invalid("NPC receipt terms");
                if(q.fx()==null){if(receipt.publication()!=null)throw invalid("Unexpected NPC FX");}
                else {var fx=q.fx();if(!fx.conversion().publication().equals(receipt.publication())||receipt.fxSourceCurrency()!=fx.conversion().pair().source()||receipt.fxTargetCurrency()!=fx.conversion().pair().target()||receipt.fxSourcePrincipal()!=fx.conversion().debit().minorUnits()||receipt.fxTargetPrincipal()!=fx.conversion().credit().minorUnits()||receipt.feeMinor()!=fx.pricing().fee().minorUnits())throw invalid("NPC FX principal/fee provenance");}
                String stock=scalar(c,"SELECT payload FROM trade_stock_events WHERE operation_id=? AND sequence=?",receipt.operation().toString(),Long.toString(seq));
                if(stock==null||StockFacts.decode(stock).action()!=StockFacts.Action.TRADE||!StockFacts.decode(stock).progression().originalOperation().equals(receipt.operation()))throw invalid("NPC atomic stock/progression linkage");
                TradeSources.requirePriceCommit(reads(c),q.world(),receipt.operation(),q.market(),q.priceRequest().key().item(),q.priceRevision(),q.eligibleTick(),q.price(),seq);
                validateCustody(c,q,receipt);
            }}
            String adapter=scalar(c,"SELECT payload FROM reference_npc_trade_adapter WHERE singleton=1");
            if((receipts==0)!=(adapter==null))throw invalid("NPC adapter installation");
            if(adapter!=null){var value=decodeAdapter(adapter);String operation=scalar(c,"SELECT operation_id FROM reference_npc_trade_adapter WHERE singleton=1");if(!value.world().value().toString().equals(world)||!world.equals(scalar(c,"SELECT world_id FROM reference_npc_trade_adapter WHERE singleton=1"))||scalar(c,"SELECT operation_id FROM reference_npc_trade_receipts WHERE operation_id=?",operation)==null)throw invalid("NPC adapter first receipt");long seq=requireFact(c,operation,ADAPTER,adapter);long first=Long.parseLong(scalar(c,"SELECT MIN(t.sequence) FROM reference_npc_trade_receipts r JOIN journal_transactions t ON t.operation_id=r.operation_id"));if(seq!=first)throw invalid("NPC adapter source chronology");}
        }catch(RuntimeException malformed){throw new SQLException("Invalid NPC source evidence",malformed);}
    }
    private static void validateQuote(Connection c,Quote q,long sequence)throws SQLException {
        if(!q.financialIntent().equals(ReferenceNpcTrades.financialIntent(q.world(),q.terms().operation(),q.terms().actor())))throw invalid("NPC canonical original intent");
        var calculated=ReferencePricing.quote(q.priceRequest());if(!calculated.available()||!calculated.quote().equals(q.price()))throw invalid("NPC detached pricing mismatch");
        String catalog=scalar(c,"SELECT payload FROM trade_catalog_offers WHERE trader_id=? AND revision=? AND ordinal=?",q.terms().trader().toString(),Long.toString(q.terms().catalogRevision()),Integer.toString(q.terms().offerOrdinal()));if(catalog==null)throw invalid("NPC captured offer missing");var offer=CatalogFacts.decodeEntry(catalog).offer();
        if(!q.priceRequest().key().item().equals(offer.goods().priceKey())||q.priceRequest().key().side()!=offer.side()||q.priceRequest().goods()!=Math.multiplyExact((long)q.terms().executions(),offer.goodsCount())||q.priceRequest().standardQuantity()!=offer.standardQuantity()||!q.priceRequest().reputation().equals(q.terms().reputation())||!q.priceRequest().comparisons().equals(q.terms().comparisons()))throw invalid("NPC offer/price terms");
        var raw=q.terms().source().basis(offer.emeraldCount(),offer.goodsCount(),offer.standardQuantity());boolean matches=false;for(var comparison:q.terms().comparisons())try{if(ReferencePricing.normalizedBasis(offer.side(),raw,comparison).equals(q.priceRequest().basis()))matches=true;}catch(IllegalArgumentException different){}if(!matches)throw invalid("NPC source basis mismatch");
        String binding=scalar(c,"SELECT payload FROM trade_market_events WHERE binding_trader=? AND sequence<? ORDER BY sequence DESC LIMIT 1",q.terms().trader().toString(),Long.toString(sequence));if(binding==null||MarketFacts.decode(binding).binding().revision()!=q.bindingRevision()||!MarketFacts.decode(binding).binding().market().equals(q.market()))throw invalid("NPC binding snapshot");
        String stock=scalar(c,"SELECT payload FROM trade_stock_events WHERE trader_id=? AND sequence<? ORDER BY sequence DESC LIMIT 1",q.terms().trader().toString(),Long.toString(sequence));if(stock==null||StockFacts.decode(stock).trader().revision()!=q.stockRevision()||StockFacts.decode(stock).trader().catalogRevision()!=q.terms().catalogRevision())throw invalid("NPC stock snapshot");
        var price=TradeSources.priceBefore(reads(c),q.market(),offer.goods().priceKey(),sequence);
        if(q.priceRevision()!=(price==null?0:price.revision())||!q.priceRequest().demand().equals(price==null?new Pricing.Demand(ca.emilybelnavis.mcmods.economy.simulation.model.Rational.ZERO,0):price.demand()))throw invalid("NPC demand snapshot");
        q.terms().source().validateHistorical(reads(c),offer,sequence);
        var capturedFx=ca.emilybelnavis.mcmods.economy.fx.FxService.at(reads(c),q.issuedMillis(),sequence);
        if(capturedFx==null||!capturedFx.id().equals(q.terms().source().fxSnapshot()))throw invalid("NPC captured FX frontier");
        var captured=CatalogStore.read(reads(c),q.terms().trader(),q.terms().catalogRevision());
        if(captured==null||!q.terms().comparisons().equals(q.terms().source().comparisonsHistorical(reads(c),captured,offer,sequence)))throw invalid("NPC complete historical comparison mismatch");
        String ticks=scalar(c,"SELECT payload FROM trade_stock_events WHERE market_id=? AND sequence<? ORDER BY sequence DESC LIMIT 1",q.market().toString(),Long.toString(sequence));if(q.marketTickRevision()!=(ticks==null?0:StockFacts.decode(ticks).market().revision())||q.eligibleTick()!=(ticks==null?0:StockFacts.decode(ticks).market().eligibleTicks()))throw invalid("NPC market-clock snapshot");
        if(q.fx()!=null&&(!q.fx().account().id().equals(q.terms().account())||q.fx().account().revision()!=q.accountRevision()||q.fx().account().currency()!=q.accountCurrency()||!q.fx().supplier().equals(q.issuer())||q.fx().goodsGross().minorUnits()!=q.price().minor()||q.fx().goodsGross().currency()!=q.priceRequest().basis().currency()))throw invalid("NPC explicit FX quote binding");
    }
    private static void validateCustody(Connection c,Quote q,Receipt receipt)throws SQLException {
        for(UUID id:Arrays.asList(receipt.inputCustody(),receipt.outputCustody()))if(id!=null){String operation=scalar(c,"SELECT financial_operation FROM custody_cases WHERE id=?",id.toString());String financial=scalar(c,"SELECT financial_intent FROM custody_cases WHERE id=?",id.toString());if(!receipt.operation().toString().equals(operation)||!q.financialIntent().equals(financial))throw invalid("NPC custody/financial linkage");
            String items=scalar(c,"SELECT items FROM custody_cases WHERE id=?",id.toString());
            String catalog=scalar(c,"SELECT payload FROM trade_catalog_offers WHERE trader_id=? AND revision=? AND ordinal=?",q.terms().trader().toString(),Long.toString(q.terms().catalogRevision()),Integer.toString(q.terms().offerOrdinal()));
            boolean input=id.equals(receipt.inputCustody());String expected=input?q.terms().exactInputItems():ReferenceNpcTrades.outputItems(CatalogFacts.decodeEntry(catalog).offer(),q.terms().executions());if(!expected.equals(items))throw invalid("NPC exact custody stacks");
            if(!("player:"+q.terms().actor().value()).equals(scalar(c,"SELECT owner FROM custody_cases WHERE id=?",id.toString()))||!(input?ReferenceNpcTrades.inputSource(q.terms()):ReferenceNpcTrades.outputSource(q.terms())).equals(scalar(c,"SELECT source FROM custody_cases WHERE id=?",id.toString()))||!(input?ReferenceNpcTrades.inputDestination(q.terms()):ReferenceNpcTrades.outputDestination(q.terms())).equals(scalar(c,"SELECT destination FROM custody_cases WHERE id=?",id.toString())))throw invalid("NPC custody owner/location");
            if(input&&scalar(c,"SELECT reservation_witness FROM custody_cases WHERE id=?",id.toString())==null)throw invalid("NPC input lacks persisted save witness");}
    }
    private static void requireCounts(Connection c,String table,String type)throws SQLException{if(count(c,"SELECT COUNT(*) FROM "+table)!=Long.parseLong(scalar(c,"SELECT COUNT(*) FROM journal_records WHERE record_type=?",type)))throw invalid("NPC fact/projection count: "+table);}
    private static long requireFact(Connection c,String operation,String type,String payload)throws SQLException{String seq=scalar(c,"SELECT t.sequence FROM journal_transactions t JOIN journal_records r ON r.sequence=t.sequence WHERE t.operation_id=? AND r.record_type=? AND r.record_version=1 AND r.payload=?",operation,type,payload);if(seq==null||!"1".equals(scalar(c,"SELECT COUNT(*) FROM journal_transactions t JOIN journal_records r ON r.sequence=t.sequence WHERE t.operation_id=? AND r.record_type=? AND r.record_version=1 AND r.payload=?",operation,type,payload)))throw invalid("Missing NPC immutable fact");return Long.parseLong(seq);}
    /** Bounded immutable validator reads; no caller SQL enters this seam. */
    private static JournalEngine.ReadView reads(Connection c) {
        return (sql,parameters,maxRows)->{
            if(maxRows<1||maxRows>4096)throw new IllegalArgumentException("Reference validator row bound");
            try(var statement=c.prepareStatement(sql)) {
                for(int i=0;i<parameters.size();i++)statement.setString(i+1,parameters.get(i));
                try(var rows=statement.executeQuery()) {
                    var result=new ArrayList<Map<String,String>>();long bytes=0;
                    while(rows.next()) {
                        if(result.size()>=maxRows)throw new IllegalArgumentException("Reference validator row limit");
                        var row=new LinkedHashMap<String,String>();
                        for(int column=1;column<=rows.getMetaData().getColumnCount();column++) {
                            String value=rows.getString(column);if(value!=null)bytes+=value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                            row.put(rows.getMetaData().getColumnLabel(column),value);
                        }
                        if(bytes>32L*1024*1024)throw new IllegalArgumentException("Reference validator byte limit");
                        result.add(Collections.unmodifiableMap(row));
                    }
                    return List.copyOf(result);
                }
            }catch(SQLException failure){throw new IllegalArgumentException("Reference validator query failed",failure);}
        };
    }
    private static String scalar(Connection c,String sql,String...params)throws SQLException{try(var s=c.prepareStatement(sql)){for(int i=0;i<params.length;i++)s.setString(i+1,params[i]);try(var r=s.executeQuery()){return r.next()?r.getString(1):null;}}}
    private static long count(Connection c,String sql)throws SQLException{return Long.parseLong(scalar(c,sql));}
    private static String normalize(String s){return s.replaceAll("\\s+"," ").trim();}
    private static SQLException invalid(String s){return new SQLException(s);}
}
