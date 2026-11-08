package ca.emilybelnavis.mcmods.economy.trade.settlement;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.DeadlineDispatcher;
import ca.emilybelnavis.mcmods.economy.clock.EconomicClock;
import ca.emilybelnavis.mcmods.economy.simulation.macro.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.MacroModel;
import ca.emilybelnavis.mcmods.economy.simulation.plan.ActivationModelPlans;
import ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes;
import ca.emilybelnavis.mcmods.economy.simulation.window.WindowStore;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.*;
import ca.emilybelnavis.mcmods.economy.simulation.activity.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.storage.*;
import ca.emilybelnavis.mcmods.economy.trade.catalog.*;
import ca.emilybelnavis.mcmods.economy.trade.custody.NpcDeliveries;
import ca.emilybelnavis.mcmods.economy.trade.market.*;
import ca.emilybelnavis.mcmods.economy.trade.pricing.*;
import ca.emilybelnavis.mcmods.economy.trade.stock.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static ca.emilybelnavis.mcmods.economy.trade.settlement.ReferenceNpcFacts.*;

import ca.emilybelnavis.mcmods.economy.valuation.*;
/** Real SQLite proof of the new source-neutral account boundary; native custody evidence is reused upstream. */
class ReferenceNpcTradesTest {
    @TempDir Path path;
    @Test void staleSourceRefusesWithoutEffectsAndCommittedOriginalReplaysAfterSwitchAndReopen() throws Exception {
        Journal.Outcome committed;UUID operation;PlayerId owner;long finalBalance,sequence;
        try(var f=new Fixture(path)) {
            var stale=f.quote(0,f.cad);long before=f.balance(f.cad),stockBefore=f.store.transaction(tx->StockStore.trader(tx::query,f.trader)).revision();
            f.switchSource();
            var refused=f.trades.settle(ReferenceNpcTrades.intent(f.world(),stale.terms().operation(),f.actor));
            assertNotEquals(Journal.Status.COMMITTED,refused.status());
            assertEquals(before,f.balance(f.cad));assertEquals(0,f.bridge(CAD));
            assertEquals(stockBefore,f.store.transaction(tx->StockStore.trader(tx::query,f.trader)).revision());
            assertTrue(f.store.transaction(tx->tx.query("SELECT operation_id FROM reference_npc_trade_receipts",List.of(),16)).isEmpty());
            assertTrue(f.store.transaction(tx->Custody.find(tx::query,f.actor,stale.terms().outputCustody(),false)).isEmpty());
            assertTrue(f.store.transaction(tx->tx.query("SELECT id FROM activity_observations WHERE operation_id=?",List.of(stale.terms().operation().toString()),16)).isEmpty());
            var fresh=f.quote(0,f.cad);operation=fresh.terms().operation();owner=f.actor;
            committed=f.trades.settle(ReferenceNpcTrades.intent(f.world(),operation,f.actor));
            assertEquals(Journal.Status.COMMITTED,committed.status(),committed.result().code());
            assertEquals(Math.multiplyExact(fresh.price().unitMinor(),fresh.priceRequest().goods()),fresh.price().minor());
            finalBalance=before-fresh.price().minor();assertEquals(finalBalance,f.balance(f.cad));assertEquals(0,f.bridge(CAD));
            assertEquals(0,f.store.transaction(tx->tx.query("SELECT payload FROM trade_market_carries WHERE epoch=?",List.of(f.active.epoch().toString()),16)).size(),"Reference pricing creates no carry");
            f.switchSource();f.now+=31_000;f.allowed=false;
            sequence=f.store.lastCommittedSequence();
            assertEquals(committed,f.trades.settle(ReferenceNpcTrades.intent(f.world(),operation,f.actor)),"Original outcome precedes source, expiry and admission");
            assertEquals(sequence,f.store.lastCommittedSequence());assertEquals(finalBalance,f.balance(f.cad));
        }
        var reopened=new SqliteStorage();reopened.initialize(path.toRealPath());
        try {
            var replay=new ReferenceNpcTrades(reopened,null,Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"),ZoneOffset.UTC),(phase,terms,quote,reads)->false);
            assertEquals(committed,replay.settle(ReferenceNpcTrades.intent(reopened.snapshot().worldId(),operation,owner)));
            assertEquals(sequence,reopened.lastCommittedSequence());
            assertEquals(finalBalance,reopened.transaction(tx->Settlement.posted(tx::query,UUID.nameUUIDFromBytes("npc-test-cad".getBytes(java.nio.charset.StandardCharsets.UTF_8)),CAD)).longValue());
            assertNotNull(reopened.transaction(tx->ReferenceNpcTrades.receipt(tx::query,operation)));
        } finally {reopened.close();}
    }
    private static final class Fixture implements AutoCloseable {
        final SqliteStorage store=new SqliteStorage();final PlayerId actor=new PlayerId(UUID.randomUUID());final UUID trader=UUID.randomUUID(),source=UUID.randomUUID(),epoch=UUID.randomUUID();
        final AccountId cad=new AccountId(UUID.nameUUIDFromBytes("npc-test-cad".getBytes(java.nio.charset.StandardCharsets.UTF_8))),usd=new AccountId(UUID.randomUUID());long now=Instant.parse("2026-11-07T12:00:00Z").toEpochMilli();boolean allowed=true;
        final Clock wall=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}};
        final List<TradeCatalog.Offer> offers=List.of(
            new TradeCatalog.Offer(0,Pricing.Side.BUY,new TradeCatalog.Goods("minecraft:bread",TradeCatalog.Match.EXACT_COMPONENTS,"{}"),1,2,List.of(),16,2),
            new TradeCatalog.Offer(1,Pricing.Side.SELL,new TradeCatalog.Goods("minecraft:bread",TradeCatalog.Match.EXACT_COMPONENTS,"{}"),1,1,List.of(),16,2));
        final ReferenceNpcTrades trades;
        SourceRef active;ValuationStore.Snapshot valuation;
        Fixture(Path path)throws Exception {
            store.initialize(path.toRealPath());submit(FxService.OPEN,FxService.opening(wall),request("currency","CAD"));
            var capital=new InstitutionCapital(store,new SystemActorId(UUID.randomUUID()),CapitalPosting.poster());for(int n=0;n<26;n++)assertEquals(Journal.Status.COMMITTED,capital.advance(wall.instant()).status());
            store.transaction(tx->{for(var a:List.of(cad,usd)){tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK',?,'OPEN',1,?)",List.of(a.value().toString(),actor.value().toString(),a.equals(cad)?"CAD":"USD",a.equals(cad)?"1":"0"));tx.execute("INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",List.of(a.value().toString(),a.equals(cad)?"economy:bank/crownstone":"economy:bank/crosswind"));}return null;});
            for(var a:List.of(cad,usd)){EconomyCurrency c=a.equals(cad)?CAD:USD;var legs=List.of(new PostingBatch.Leg(Settlement.issuanceAccount(c),PostingBatch.Role.ISSUANCE,c,new Money(c,-1_000_000)),new PostingBatch.Leg(a.value(),PostingBatch.Role.ACCOUNT,c,new Money(c,1_000_000)));var kind=new Journal.Kind("fixture:npc","fixture:npc-fund");submit(kind,Settlement.registrationMinor((i,l,r)->PostingBatch.Authority.ISSUE),Settlement.minorRequest(legs));}
            new DeadlineDispatcher(store,wall,List.of(),16).prepare();var activity=new ActivityRuntime(store,wall);store.commitObserver((i,r,c)->activity.observe(i,r,c,now));for(int turn=0;turn<100;turn++)if(activity.prepare())break;
            assertTrue(store.<Boolean>transaction(tx->ActivityStore.ready(tx::query)));
            var position=new MarketFacts.Position("minecraft:overworld",0,64,0);for(int turn=0;turn<100;turn++)if(!MarketStore.resolve(store,trader,position,position,CAD,true).pending())break;
            CatalogStore.capture(store,new TradeCatalog.Capture(trader,1,offers));StockStore.install(store,trader,1,false);
            valuation=publishValuation();
            switchSource();
            trades=new ReferenceNpcTrades(store,new NpcAccountFx(new FxQuotes(world()),new ServicePricing(new WealthValuation())),wall,(phase,terms,quote,reads)->allowed);
        }
        WorldId world(){return store.snapshot().worldId();}
        Quote quote(int ordinal,AccountId account){return quote(ordinal,account,1);}
        Quote quote(int ordinal,AccountId account,int executions){var offer=offers.get(ordinal);UUID operation=UUID.randomUUID(),input=offer.side()==Pricing.Side.SELL?UUID.randomUUID():null,output=offer.side()==Pricing.Side.BUY?UUID.randomUUID():null;
            var publication=store.transaction(tx->FxService.at(tx::query,now));
            var priceSource=ReferenceSource.capture(active,valuation,offer.goods().item(),offer.goods().components(),CAD,publication);
            var compared=store.transaction(tx->priceSource.comparisons(tx::query,CatalogStore.read(tx::query,trader,1),offer));
            var terms=new Terms(operation,actor,UUID.randomUUID(),trader,1,ordinal,executions,account,active.epoch(),priceSource,Rational.ZERO,1,"trusted-reputation",compared,input,input==null?"":ReferenceNpcTrades.inputItems(new TradeCatalog.Offer(offer.ordinal(),offer.side(),new TradeCatalog.Goods(offer.goods().item(),TradeCatalog.Match.EXACT_COMPONENTS,offer.goods().components()),offer.goodsCount(),offer.emeraldCount(),offer.secondary(),offer.originalMaxUses(),offer.villagerXp()),executions),output,"capacity-reservation:"+operation,1);
            if(input!=null){var custodyTerms=new Custody.Terms(actor,terms.exactInputItems(),ReferenceNpcTrades.inputSource(terms),ReferenceNpcTrades.inputDestination(terms),ReferenceNpcTrades.financialIntent(world(),operation,actor));var reserve=new Journal.Intent(new OperationId(input),new RequestId(input),actor,Custody.Action.RESERVE.kind(),Custody.reserveRequest(custodyTerms),null,null);submit(Custody.Action.RESERVE.kind(),Custody.registration(Custody.Action.RESERVE,(a,i,c,r)->true,(c,e,r)->true,null),reserve);
                var evidence=new Custody.Evidence(Custody.EvidenceKind.RESERVATION_SAVED,"independent-fixture-save",ReferenceNpcTrades.inputSource(terms),"saved-token");submit(Custody.Action.RESERVATION_SAVED.kind(),Custody.registration(Custody.Action.RESERVATION_SAVED,(a,i,c,r)->true,(c,e,r)->true,null),Custody.request(input,null,evidence,"fixture witnessed goods"));}
            return trades.quote(terms);
        }
        ValuationStore.Snapshot publishValuation() {
            var inputIds=ValuationData.catalog().entries().keySet();
            var items=new TreeMap<String,VariantGraph.Item>();
            for(String id:inputIds)items.put(id,new VariantGraph.Item(id,0,64,0,Set.of()));
            var recipes=new RecipeGraph.Graph(List.of(),Map.of(),Map.of());
            var brewing=new BrewingGraph.Graph(1,BrewingGraph.ADAPTER,BrewingGraph.RULES_HASH,true,Map.of(),new ArrayList<>(BrewingGraph.FORMS),List.of(),Map.of(),ValuationData.hash("reference-account-fixture"));
            var variants=new VariantGraph.Graph(1,VariantGraph.ADAPTER,VariantGraph.RULES_HASH,items,Map.of(),Map.of(),Map.of(),Map.of());
            var inputs=new ValuationData.Inputs(inputIds,ValuationData.hash("recipes"),ValuationData.hash("tags"),ValuationData.hash("features"));
            var config=new ValuationConfig.Config(List.of(),List.of(new ValuationConfig.Entry("minecraft:bread",CAD,"10",1)));
            var manifest=ValuationData.prepare(inputs,config,List.of(),recipes,brewing,variants);
            return ValuationStore.publish(store,ValuationData.encode(manifest),now,new ValuationStore.Gate(),0);
        }
        void switchSource() {
            var manifest=ValuationData.decode(valuation.payload());
            UUID market=store.transaction(tx->MarketStore.binding(tx::query,trader)).market();
            var next=new SourceRef(world(),market,offers.getFirst().goods().priceKey(),source,active==null?1:active.revision()+1,UUID.randomUUID(),valuation.id(),manifest.configHash(),manifest.inputs().recipeHash(),manifest.providerHash(),ReferencePricing.VERSION,ReferencePricing.ROUNDING,active==null?UUID.randomUUID():active.activation());
            var outcome=TradeSources.transition(store,next,active,UUID.randomUUID(),new SystemActorId(UUID.randomUUID()));
            assertEquals(Journal.Status.COMMITTED,outcome.status(),outcome.result().code());active=next;
        }
        long balance(AccountId id){return store.transaction(tx->Settlement.posted(tx::query,id.value(),id.equals(cad)?CAD:USD));}
        long bridge(EconomyCurrency c){return store.transaction(tx->Settlement.posted(tx::query,NpcSettlement.bridge(world(),c),c));}
        long total(ActivityFacts.Kind kind){return store.transaction(tx->Long.parseLong(tx.query("SELECT COALESCE(SUM(minor),0) AS total FROM activity_day_counters WHERE kind=?",List.of(kind.name()),1).getFirst().get("total")));}
        Journal.CanonicalRequest request(String key,String value){return new Journal.CanonicalRequest(1,Map.of(key,value));}
        void submit(Journal.Kind kind,JournalEngine.Registration reg,Journal.CanonicalRequest request){submit(kind,reg,new Journal.Intent(new OperationId(UUID.randomUUID()),new RequestId(UUID.randomUUID()),actor,kind,request,null,null));}
        void submit(Journal.Kind kind,JournalEngine.Registration reg,Journal.Intent intent){var outcome=new JournalEngine(store,(a,k)->true,Map.of(kind,reg)).submit(intent);assertEquals(Journal.Status.COMMITTED,outcome.status(),outcome.result().code());}
        public void close()throws Exception{store.close();}
    }
}
