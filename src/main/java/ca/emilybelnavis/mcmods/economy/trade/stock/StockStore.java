package ca.emilybelnavis.mcmods.economy.trade.stock;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.trade.catalog.*;
import ca.emilybelnavis.mcmods.economy.trade.market.*;
import ca.emilybelnavis.mcmods.economy.trade.pricing.Pricing;
import java.util.*;
import static ca.emilybelnavis.mcmods.economy.trade.stock.StockFacts.*;

/** Worker-only, explicitly installed stock. Future settlement composes prepared records atomically. */
public final class StockStore {
    private StockStore() {}
    private static final Journal.Kind KIND=new Journal.Kind("economy:a8","economy:a8/stock/change");
    public static Trader trader(JournalEngine.ReadView reads,UUID id){var rows=reads.query("SELECT payload FROM trade_stock_traders WHERE trader_id=?",List.of(id.toString()),1);return rows.isEmpty()?null:decode(rows.getFirst().get("payload")).trader();}
    public static MarketTicks market(JournalEngine.ReadView reads,UUID id){var rows=reads.query("SELECT payload FROM trade_stock_markets WHERE market_id=?",List.of(id.toString()),1);return rows.isEmpty()?null:decode(rows.getFirst().get("payload")).market();}
    public static Progression progression(JournalEngine.ReadView reads,UUID original){var rows=reads.query("SELECT payload FROM trade_stock_progressions WHERE original_operation=?",List.of(original.toString()),1);return rows.isEmpty()?null:decode(rows.getFirst().get("payload")).progression();}
    public static Trader install(FoundationStore store,UUID trader,long catalogRevision,boolean wandering) {
        UUID operation=stable("install|"+store.snapshot().worldId().value()+"|"+trader+"|"+catalogRevision);
        var retained=store.transaction(tx->trader(tx::query,trader));
        if(retained!=null&&retained.catalogRevision()==catalogRevision){if(retained.wandering()!=wandering)throw new IllegalArgumentException("Trader type changed");return retained;}
        var state=store.transaction(tx->installed(tx::query,trader,catalogRevision,wandering));
        submit(store,new Event(store.snapshot().worldId(),operation,Action.INSTALL,state,null,null,null,null,0));return store.transaction(tx->trader(tx::query,trader));
    }
    static Trader installed(JournalEngine.ReadView reads,UUID trader,long catalogRevision,boolean wandering) {
        var capture=CatalogStore.read(reads,trader,catalogRevision);if(capture==null)throw new IllegalArgumentException("Captured catalog required");
        var old=trader(reads,trader);if(old!=null&&(catalogRevision<=old.catalogRevision()||old.wandering()!=wandering))throw new IllegalArgumentException("Stock catalog revision conflict");
        var binding=MarketStore.binding(reads,trader);if(binding==null||MarketStore.market(reads,binding.market()).kind()==MarketFacts.Kind.WANDERING!=wandering)throw new IllegalArgumentException("Stock trader binding/type");
        var offers=new ArrayList<OfferStock>();for(var offer:capture.offers()) {
            int remaining=offer.originalMaxUses();
            if(old!=null)for(var prior:old.offers())if(prior.identity().equals(offer.identity())){remaining=prior.remaining();break;}
            offers.add(new OfferStock(offer.ordinal(),offer.identity(),offer.side(),offer.originalMaxUses(),remaining,offer.villagerXp()));
        }
        return new Trader(trader,catalogRevision,old==null?1:Math.addExact(old.revision(),1),wandering,old==null?0:old.restockTicks(),old==null?null:old.session(),old==null?0:old.lastObservedServerTick(),offers);
    }
    public static Trader checkpointTrader(FoundationStore store,UUID trader,TickSample sample,Eligibility eligibility) {
        UUID operation=stable("trader-tick|"+store.snapshot().worldId().value()+"|"+trader+"|"+sample.session()+"|"+sample.observedServerTick());
        var replay=store.transaction(tx->event(tx::query,operation));if(replay!=null){if(!sample.equals(replay.sample())||!eligibility.equals(replay.eligibility()))throw new IllegalArgumentException("Tick replay conflict");return replay.trader();}
        var next=store.transaction(tx->ticked(trader(tx::query,trader),sample,eligibility));
        submit(store,new Event(store.snapshot().worldId(),operation,Action.TRADER_TICKS,next,null,null,sample,eligibility,0));return store.transaction(tx->trader(tx::query,trader));
    }
    static Trader ticked(Trader old,TickSample sample,Eligibility eligibility) {
        if(old==null)throw new IllegalArgumentException("Stock not installed");checkSample(old.session(),old.lastObservedServerTick(),sample);
        int progress=old.restockTicks();boolean refill=false;
        if(!old.wandering()&&eligibility.eligible()){progress=Math.addExact(progress,sample.eligibleTicks());if(progress>=RESTOCK_TICKS){progress-=RESTOCK_TICKS;refill=true;}}
        var offers=new ArrayList<OfferStock>();for(var offer:old.offers())offers.add(refill&&offer.side()==Pricing.Side.BUY?new OfferStock(offer.ordinal(),offer.identity(),offer.side(),offer.maxUses(),offer.maxUses(),offer.villagerXp()):offer);
        return new Trader(old.trader(),old.catalogRevision(),Math.addExact(old.revision(),1),old.wandering(),progress,sample.session(),sample.observedServerTick(),offers);
    }
    public static MarketTicks checkpointMarket(FoundationStore store,UUID market,TickSample sample) {
        UUID operation=stable("market-tick|"+store.snapshot().worldId().value()+"|"+market+"|"+sample.session()+"|"+sample.observedServerTick());
        var replay=store.transaction(tx->event(tx::query,operation));if(replay!=null){if(!sample.equals(replay.sample()))throw new IllegalArgumentException("Tick replay conflict");return replay.market();}
        var next=store.transaction(tx->{if(MarketStore.market(tx::query,market)==null)throw new IllegalArgumentException("Unknown market");return tickedMarket(market,market(tx::query,market),sample);});
        submit(store,new Event(store.snapshot().worldId(),operation,Action.MARKET_TICKS,null,next,null,sample,null,0));return store.transaction(tx->market(tx::query,market));
    }
    static MarketTicks tickedMarket(UUID id,MarketTicks old,TickSample sample){if(old!=null)checkSample(old.session(),old.lastObservedServerTick(),sample);return new MarketTicks(id,old==null?1:Math.addExact(old.revision(),1),Math.addExact(old==null?0:old.eligibleTicks(),sample.eligibleTicks()),sample.session(),sample.observedServerTick());}
    private static void checkSample(UUID previous,long tick,TickSample sample){if(Objects.equals(previous,sample.session())&&(sample.observedServerTick()<=tick||sample.observedTicks()>sample.observedServerTick()-tick))throw new IllegalArgumentException("Overlapping/reversed running tick sample");}
    /** This prepared commit must be combined with money, goods custody and market pricing by the owning settlement. */
    public static JournalEngine.Commit prepareTrade(JournalEngine.ReadView reads,WorldId world,UUID operation,UUID trader,long catalogRevision,long bindingRevision,long stockRevision,int offerOrdinal,int executions) {
        var old=trader(reads,trader);var binding=MarketStore.binding(reads,trader);
        if(old==null||old.catalogRevision()!=catalogRevision||old.revision()!=stockRevision||binding==null||binding.revision()!=bindingRevision)throw new IllegalArgumentException("Stale stock/catalog/binding quote");
        if(offerOrdinal<0||offerOrdinal>=old.offers().size()||executions<1||executions>1024)throw new IllegalArgumentException("Trade execution count");
        if(progression(reads,operation)!=null)throw new IllegalArgumentException("Original trade already committed");
        var offer=old.offers().get(offerOrdinal);var nextOffers=new ArrayList<>(old.offers());
        if(offer.side()==Pricing.Side.BUY){if(executions>offer.remaining())throw new IllegalArgumentException("Sale stock exhausted");nextOffers.set(offerOrdinal,new OfferStock(offer.ordinal(),offer.identity(),offer.side(),offer.maxUses(),offer.remaining()-executions,offer.villagerXp()));}
        var next=new Trader(trader,catalogRevision,Math.addExact(stockRevision,1),old.wandering(),old.restockTicks(),old.session(),old.lastObservedServerTick(),nextOffers);
        var intent=new Progression(operation,trader,catalogRevision,offerOrdinal,offer.identity(),executions,offer.villagerXp(),Stage.PENDING,1,"");
        return prepare(reads,new Event(world,operation,Action.TRADE,next,null,intent,null,null,bindingRevision));
    }
    /** Nonpersisted, unforgeable proof that this worker invocation performed the first dispatch. */
    public static final class FreshDispatch {
        private final Progression progression;
        private boolean claimed;
        private FreshDispatch(Progression progression){this.progression=progression;}
        public Progression progression(){return progression;}
        public synchronized Progression claim(){if(claimed)return null;claimed=true;return progression;}
    }
    public static FreshDispatch dispatch(FoundationStore store,UUID original,String evidence) {
        var prior=store.transaction(tx->progression(tx::query,original));
        if(prior==null||prior.stage()!=Stage.PENDING)return null;
        return new FreshDispatch(markProgression(store,original,Stage.PENDING,Stage.DISPATCHED,evidence));
    }
    public static Progression markProgression(FoundationStore store,UUID original,Stage expected,Stage next,String evidence) {
        UUID operation=stable("progression|"+store.snapshot().worldId().value()+"|"+original+"|"+next);
        var replay=store.transaction(tx->event(tx::query,operation));if(replay!=null){if(!replay.progression().evidence().equals(evidence))throw new IllegalArgumentException("Progression witness conflict");return replay.progression();}
        var prior=store.transaction(tx->progression(tx::query,original));if(prior==null||prior.stage()!=expected)throw new IllegalArgumentException("Progression stage conflict");
        var state=new Progression(original,prior.trader(),prior.catalogRevision(),prior.offerOrdinal(),prior.offerIdentity(),prior.executions(),prior.villagerXp(),next,Math.addExact(prior.revision(),1),evidence);
        submit(store,new Event(store.snapshot().worldId(),operation,Action.PROGRESSION,null,null,state,null,null,0));return store.transaction(tx->progression(tx::query,original));
    }
    static Trader traded(Trader old,Progression p) {
        if(old==null||!old.trader().equals(p.trader())||old.catalogRevision()!=p.catalogRevision()||p.offerOrdinal()>=old.offers().size()||p.stage()!=Stage.PENDING||p.revision()!=1||!p.evidence().isEmpty())throw new IllegalArgumentException("Original stock/progression mismatch");
        var offer=old.offers().get(p.offerOrdinal());if(!offer.identity().equals(p.offerIdentity())||offer.villagerXp()!=p.villagerXp())throw new IllegalArgumentException("Original offer progression mismatch");
        var offers=new ArrayList<>(old.offers());if(offer.side()==Pricing.Side.BUY){if(offer.remaining()<p.executions())throw new IllegalArgumentException("Sale stock exhausted");offers.set(offer.ordinal(),new OfferStock(offer.ordinal(),offer.identity(),offer.side(),offer.maxUses(),offer.remaining()-p.executions(),offer.villagerXp()));}
        return new Trader(old.trader(),old.catalogRevision(),Math.addExact(old.revision(),1),old.wandering(),old.restockTicks(),old.session(),old.lastObservedServerTick(),offers);
    }
    static void progressionTransition(Progression old,Progression next){if(old==null||next.revision()!=old.revision()+1||!old.originalOperation().equals(next.originalOperation())||!old.trader().equals(next.trader())||old.catalogRevision()!=next.catalogRevision()||old.offerOrdinal()!=next.offerOrdinal()||!old.offerIdentity().equals(next.offerIdentity())||old.executions()!=next.executions()||old.villagerXp()!=next.villagerXp())throw new IllegalArgumentException("Progression identity changed");
        boolean valid=old.stage()==Stage.PENDING&&next.stage()==Stage.DISPATCHED||old.stage()==Stage.DISPATCHED&&(next.stage()==Stage.ACKNOWLEDGED||next.stage()==Stage.AMBIGUOUS)||old.stage()==Stage.AMBIGUOUS&&next.stage()==Stage.ACKNOWLEDGED;
        if(!valid)throw new IllegalArgumentException("Unsafe progression transition");}
    static UUID expectedOperation(Event e){String world=e.world().value().toString();return switch(e.action()){
        case INSTALL->stable("install|"+world+"|"+e.trader().trader()+"|"+e.trader().catalogRevision());
        case TRADER_TICKS->stable("trader-tick|"+world+"|"+e.trader().trader()+"|"+e.sample().session()+"|"+e.sample().observedServerTick());
        case MARKET_TICKS->stable("market-tick|"+world+"|"+e.market().market()+"|"+e.sample().session()+"|"+e.sample().observedServerTick());
        case PROGRESSION->stable("progression|"+world+"|"+e.progression().originalOperation()+"|"+e.progression().stage());
        case TRADE->e.progression().originalOperation();};}
    static JournalEngine.Commit prepare(JournalEngine.ReadView reads,Event e) {
        if(!expectedOperation(e).equals(e.operation()))throw new IllegalArgumentException("Stock operation identity");
        if(!e.world().value().toString().equals(reads.query("SELECT world_id FROM foundation_metadata WHERE singleton=1",List.of(),1).getFirst().get("world_id")))throw new IllegalArgumentException("Stock world");
        validate(reads,e);String payload=encode(e);return new JournalEngine.Commit(List.of(new Journal.Record(TYPE,1,payload)),new Journal.Result(1,"STOCK_RECORDED",e.operation().toString()),tx->apply(tx,e,payload));
    }
    static void validate(JournalEngine.ReadView reads,Event e){switch(e.action()){
        case INSTALL->{if(!e.trader().equals(installed(reads,e.trader().trader(),e.trader().catalogRevision(),e.trader().wandering())))throw new IllegalArgumentException("Stock installation changed");}
        case TRADER_TICKS->{if(!e.trader().equals(ticked(trader(reads,e.trader().trader()),e.sample(),e.eligibility())))throw new IllegalArgumentException("Trader ticks changed");}
        case MARKET_TICKS->{if(MarketStore.market(reads,e.market().market())==null||!e.market().equals(tickedMarket(e.market().market(),market(reads,e.market().market()),e.sample())))throw new IllegalArgumentException("Market ticks changed");}
        case PROGRESSION->progressionTransition(progression(reads,e.progression().originalOperation()),e.progression());
        case TRADE->{var old=trader(reads,e.trader().trader());var binding=MarketStore.binding(reads,e.trader().trader());if(!e.trader().equals(traded(old,e.progression()))||!e.operation().equals(e.progression().originalOperation())||binding==null||binding.revision()!=e.bindingRevision()||progression(reads,e.progression().originalOperation())!=null)throw new IllegalArgumentException("Trade stock state");}
    }}
    private static void submit(FoundationStore store,Event event){String payload=encode(event);var actor=new SystemActorId(stable("actor|"+event.world().value()));var request=new Journal.CanonicalRequest(1,Map.of("event",payload));
        var engine=new JournalEngine(store,(a,k)->a.equals(actor)&&k.equals(KIND),Map.of(KIND,new JournalEngine.Registration(1,(i,r)->{if(!request.equals(i.request()))throw new IllegalArgumentException("Stock request");return prepare(r,event);})));
        var result=engine.submit(new Journal.Intent(new OperationId(event.operation()),new RequestId(event.operation()),actor,KIND,request,null,null));if(result.status()!=Journal.Status.COMMITTED)throw new IllegalArgumentException("Stock commit: "+result.result().code());}
    static Event event(JournalEngine.ReadView reads,UUID operation){var rows=reads.query("SELECT payload FROM trade_stock_events WHERE operation_id=?",List.of(operation.toString()),1);return rows.isEmpty()?null:decode(rows.getFirst().get("payload"));}
    static void apply(FoundationStore.Transaction tx,Event e,String payload){String operation=e.operation().toString(),sequence=tx.query("SELECT sequence FROM journal_transactions WHERE world_id=? AND operation_id=?",List.of(e.world().value().toString(),operation),1).getFirst().get("sequence");
        tx.execute("INSERT INTO trade_stock_events VALUES(?,?,?,?,?,?,?)",List.of(operation,e.world().value().toString(),sequence,e.trader()==null?"":e.trader().trader().toString(),e.market()==null?"":e.market().market().toString(),e.progression()==null?"":e.progression().originalOperation().toString(),payload));
        if(e.trader()!=null)tx.execute("INSERT INTO trade_stock_traders VALUES(?,?,?) ON CONFLICT(trader_id) DO UPDATE SET operation_id=excluded.operation_id,payload=excluded.payload",List.of(e.trader().trader().toString(),operation,payload));
        if(e.market()!=null)tx.execute("INSERT INTO trade_stock_markets VALUES(?,?,?) ON CONFLICT(market_id) DO UPDATE SET operation_id=excluded.operation_id,payload=excluded.payload",List.of(e.market().market().toString(),operation,payload));
        if(e.progression()!=null)tx.execute("INSERT INTO trade_stock_progressions VALUES(?,?,?) ON CONFLICT(original_operation) DO UPDATE SET operation_id=excluded.operation_id,payload=excluded.payload",List.of(e.progression().originalOperation().toString(),operation,payload));}
}
