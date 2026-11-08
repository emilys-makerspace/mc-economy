package ca.emilybelnavis.mcmods.economy.trade.cash;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.DeadlineDispatcher;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.*;
import ca.emilybelnavis.mcmods.economy.simulation.activity.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import ca.emilybelnavis.mcmods.economy.trade.catalog.*;
import ca.emilybelnavis.mcmods.economy.trade.custody.NpcDeliveries;
import ca.emilybelnavis.mcmods.economy.trade.market.*;
import ca.emilybelnavis.mcmods.economy.trade.pricing.*;
import ca.emilybelnavis.mcmods.economy.trade.stock.StockStore;
import ca.emilybelnavis.mcmods.economy.trade.settlement.ReferenceInputReturns;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static ca.emilybelnavis.mcmods.economy.trade.cash.ReferenceCashFacts.*;
import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;

import ca.emilybelnavis.mcmods.economy.valuation.*;
/** Real cash/custody source-epoch acceptance; no native inventory or game-save claim. */
class ReferenceCashTradesTest {
    @TempDir Path path;
    @Test void untouchedCashCancellationReleasesQuantityLocksAndCannotCancelSavedOrCommittedCash()throws Exception {
        PlayerId owner;UUID quoteId,input,lot;
        try(var f=new Fixture(path)) {
            var terms=f.terms(false);var quote=f.trades.quote(terms);owner=f.actor;quoteId=quote.id();input=terms.inputCustody();lot=f.lot;
            var original=f.store.transaction(tx->CashIssuance.find(tx::query,f.world(),f.lot).orElseThrow());
            assertEquals("ACTIVE",f.store.transaction(tx->tx.query("SELECT state FROM custody_cash_reservations WHERE custody_id=?",List.of(input.toString()),1)).getFirst().get("state"));
            assertThrows(IllegalArgumentException.class,()->ReferenceInputReturns.abandon(f.store,new PlayerId(UUID.randomUUID()),quote.id(),true,()->true));
            assertThrows(IllegalArgumentException.class,()->ReferenceInputReturns.abandon(f.store,f.actor,quote.id(),true,()->false));
            ReferenceInputReturns.abandon(f.store,f.actor,quote.id(),true,()->true);
            long sequence=f.store.lastCommittedSequence();
            ReferenceInputReturns.abandon(f.store,f.actor,quote.id(),true,()->true);
            assertEquals(sequence,f.store.lastCommittedSequence());
            assertEquals("RELEASED",f.store.transaction(tx->tx.query("SELECT state FROM custody_cash_reservations WHERE custody_id=?",List.of(input.toString()),1)).getFirst().get("state"));
            assertEquals(Custody.State.QUARANTINED,f.store.transaction(tx->Custody.find(tx::query,f.actor,input,false).orElseThrow()).state());
            assertEquals(original,f.store.transaction(tx->CashIssuance.find(tx::query,f.world(),f.lot).orElseThrow()));
            assertTrue(f.store.transaction(tx->NpcDeliveries.page(tx::query,f.actor,null,16)).isEmpty());
            assertEquals("NPC_CANCELLED",f.trades.settle(ReferenceCashTrades.intent(f.world(),terms.operation(),f.actor)).result().code());

            var saved=f.terms(false);var savedQuote=f.trades.quote(saved);f.saved(saved);
            assertThrows(IllegalArgumentException.class,()->ReferenceInputReturns.abandon(f.store,f.actor,savedQuote.id(),true,()->true));
            assertEquals(Journal.Status.COMMITTED,f.trades.settle(ReferenceCashTrades.intent(f.world(),saved.operation(),f.actor)).status());
            assertThrows(IllegalArgumentException.class,()->ReferenceInputReturns.abandon(f.store,f.actor,savedQuote.id(),true,()->true));
            assertEquals(1,f.store.transaction(tx->CashIssuance.find(tx::query,f.world(),f.lot).orElseThrow()).remaining());
        }
        var reopened=new SqliteStorage();reopened.initialize(path.toRealPath());
        try {
            long sequence=reopened.lastCommittedSequence();
            ReferenceInputReturns.abandon(reopened,owner,quoteId,true,()->true);
            assertEquals(sequence,reopened.lastCommittedSequence());
            assertEquals(Custody.State.QUARANTINED,reopened.transaction(tx->Custody.find(tx::query,owner,input,false).orElseThrow()).state());
            assertEquals(1,reopened.transaction(tx->CashIssuance.find(tx::query,reopened.snapshot().worldId(),lot).orElseThrow()).remaining());
        } finally {reopened.close();}
    }
    @Test void sourceChangeRefusesUncommittedCashQuoteBeforeCashStockOrCustodyConsumption()throws Exception {
        try(var f=new Fixture(path)){
            var terms=f.terms(false);var quote=f.trades.quote(terms);f.saved(terms);
            var stock=f.store.transaction(tx->StockStore.trader(tx::query,f.trader));
            var cash=f.store.transaction(tx->CashIssuance.find(tx::query,f.world(),f.lot).orElseThrow());
            f.switchSource();
            var refused=f.trades.settle(ReferenceCashTrades.intent(f.world(),terms.operation(),f.actor));
            assertEquals(Journal.Status.DURABLY_REJECTED,refused.status());
            assertEquals(cash,f.store.transaction(tx->CashIssuance.find(tx::query,f.world(),f.lot).orElseThrow()));
            assertEquals(stock,f.store.transaction(tx->StockStore.trader(tx::query,f.trader)));
            assertEquals(Custody.State.WITNESSED,f.store.transaction(tx->Custody.find(tx::query,f.actor,terms.inputCustody(),false).orElseThrow()).state());
            assertTrue(f.store.transaction(tx->tx.query("SELECT operation_id FROM reference_cash_receipts",List.of(),1)).isEmpty());
            assertEquals(0,f.total(ActivityFacts.Kind.GOODS_SALE));
            assertEquals(0,f.store.transaction(tx->Settlement.posted(tx::query,NpcSettlement.bridge(f.world(),USD),USD)).longValue());
            assertEquals(quote,ReferenceCashFacts.decodeQuote(ReferenceCashFacts.encode(quote)));
        }
        var reopened=new SqliteStorage();reopened.initialize(path.toRealPath());reopened.close();
    }
    @Test void committedCashOriginalReplaysAfterSourceChangeExpiryAndCurrentAdmissionRefusal()throws Exception {
        UUID operation;long principal;
        try(var f=new Fixture(path)){
            var terms=f.terms(false);var quote=f.trades.quote(terms);f.saved(terms);
            var committed=f.trades.settle(ReferenceCashTrades.intent(f.world(),terms.operation(),f.actor));
            assertEquals(Journal.Status.COMMITTED,committed.status(),committed.result().code());operation=terms.operation();principal=quote.pricing().price().minor();
            assertEquals(Math.multiplyExact(quote.pricing().price().unitMinor(),quote.pricing().request().goods()),principal);
            var cash=f.store.transaction(tx->CashIssuance.find(tx::query,f.world(),f.lot).orElseThrow());
            var stock=f.store.transaction(tx->StockStore.trader(tx::query,f.trader));
            f.switchSource();f.now+=31_000;f.allowed=false;
            assertEquals(committed,f.trades.settle(ReferenceCashTrades.intent(f.world(),operation,f.actor)));
            assertEquals(cash,f.store.transaction(tx->CashIssuance.find(tx::query,f.world(),f.lot).orElseThrow()));
            assertEquals(stock,f.store.transaction(tx->StockStore.trader(tx::query,f.trader)));
            assertEquals(principal,f.total(ActivityFacts.Kind.NPC_RETIRE));
        }
        var reopened=new SqliteStorage();reopened.initialize(path.toRealPath());
        try{assertEquals(principal,reopened.transaction(tx->ReferenceCashTrades.receipt(tx::query,operation)).goodsPrincipal());}finally{reopened.close();}
    }
    @Test void v2OneSidedCashQuoteCommitsAndReopensUnderItsCapturedPricingImplementation()throws Exception {
        UUID operation;Quote captured;
        try(var f=new Fixture(path,true,ReferencePricing.V2)){
            var terms=f.terms(false);captured=f.trades.quote(terms);operation=terms.operation();
            assertEquals(ReferencePricing.V2,captured.terms().source().ref().implementation());
            assertFalse(ReferencePricing.quote(captured.pricing().request()).available(),"The historical v1 envelope refuses this exact .8 cohort");
            assertTrue(ReferencePricing.quote(captured.pricing().request(),ReferencePricing.V2).available());
            assertEquals(100,captured.pricing().price().unitMinor());assertEquals(100,captured.pricing().price().minor());
            f.saved(terms);
            var committed=f.trades.settle(ReferenceCashTrades.intent(f.world(),operation,f.actor));
            assertEquals(Journal.Status.COMMITTED,committed.status(),committed.result().code());
            assertEquals(100,f.total(ActivityFacts.Kind.NPC_RETIRE));
        }
        var reopened=new SqliteStorage();reopened.initialize(path.toRealPath());
        try{
            var retained=reopened.transaction(tx->ReferenceCashTrades.read(tx::query,captured.id()));
            assertEquals(captured,retained);
            assertEquals(ReferencePricing.V2,retained.terms().source().ref().implementation());
            assertEquals(100,reopened.transaction(tx->ReferenceCashTrades.receipt(tx::query,operation)).goodsPrincipal());
        }finally{reopened.close();}
    }
    private static final class Fixture implements AutoCloseable {
        final SqliteStorage store=new SqliteStorage();final PlayerId actor=new PlayerId(UUID.randomUUID());final UUID trader=UUID.randomUUID(),lot=UUID.randomUUID(),source=UUID.randomUUID(),epoch=UUID.randomUUID();long now=Instant.parse("2026-11-07T12:00:00Z").toEpochMilli();boolean allowed=true;
        final Clock wall=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}};
        final String implementation;
        final TradeCatalog.Offer offer=new TradeCatalog.Offer(0,Pricing.Side.BUY,new TradeCatalog.Goods("minecraft:bread",TradeCatalog.Match.EXACT_COMPONENTS,"{}"),1,2,List.of(),16,2);final ReferenceCashTrades trades;SourceRef active;ReferenceSource priceSource;ValuationStore.Snapshot valuationSnapshot;ValuationData.Manifest manifest;
        Fixture(Path path)throws Exception {this(path,false,ReferencePricing.VERSION);}
        Fixture(Path path,boolean oneSided,String implementation)throws Exception {this.implementation=implementation;store.initialize(path.toRealPath());var opening=FxService.opening(wall);var registration=new JournalEngine.Registration(1,(i,r)->{var decision=opening.handler().prepare(i,r);if(!(decision instanceof JournalEngine.Commit base))return decision;return CashIssuance.composeIssuance(world(),List.of(new CashIssuance.Lot(world(),lot,"economy:usd_bill_5",2,2,CashIssuance.State.ACTIVE,1,"fixture-original-cash")),base,r);});submit(FxService.OPEN,registration,new Journal.CanonicalRequest(1,Map.of("currency","CAD")));
            var capital=new InstitutionCapital(store,new SystemActorId(UUID.randomUUID()),CapitalPosting.poster());for(int n=0;n<26;n++)assertEquals(Journal.Status.COMMITTED,capital.advance(wall.instant()).status());
            new DeadlineDispatcher(store,wall,List.of(),16).prepare();var activity=new ActivityRuntime(store,wall);store.commitObserver((i,r,c)->activity.observe(i,r,c,now));for(int n=0;n<100;n++)if(activity.prepare())break;assertTrue(store.<Boolean>transaction(tx->ActivityStore.ready(tx::query)));
            var position=new MarketFacts.Position("minecraft:overworld",0,64,0);for(int n=0;n<100;n++)if(!MarketStore.resolve(store,trader,position,position,USD,true).pending())break;CatalogStore.capture(store,new TradeCatalog.Capture(trader,1,oneSided?List.of(offer):List.of(offer,new TradeCatalog.Offer(1,Pricing.Side.SELL,offer.goods(),1,1,List.of(),16,2))));StockStore.install(store,trader,1,false);
            var recipes=new RecipeGraph.Graph(List.of(),Map.of(),Map.of());
            var brewing=new BrewingGraph.Graph(1,BrewingGraph.ADAPTER,BrewingGraph.RULES_HASH,true,Map.of("minecraft:water",new BrewingGraph.Potion("minecraft:water",List.of())),new ArrayList<>(BrewingGraph.FORMS),List.of(),Map.of(),ValuationData.hash("fixture:brewing"));
            var items=new TreeMap<String,VariantGraph.Item>();for(var id:ValuationData.catalog().entries().keySet())items.put(id,new VariantGraph.Item(id,0,64,0,Set.of()));
            var variants=new VariantGraph.Graph(1,VariantGraph.ADAPTER,VariantGraph.RULES_HASH,items,Map.of(),Map.of(),Map.of(),Map.of());
            var inputs=new ValuationData.Inputs(ValuationData.catalog().entries().keySet(),ValuationData.hash(recipes.hash()+brewing.hash()+variants.hash()),ValuationData.hash("fixture:tags"),ValuationData.hash("fixture:features"));
            manifest=ValuationData.prepare(inputs,new ValuationConfig.Config(List.of(),List.of(new ValuationConfig.Entry("minecraft:bread",USD,"1",1))),List.of(),recipes,brewing,variants);
            valuationSnapshot=ValuationStore.publish(store,ValuationData.encode(manifest),now,new ValuationStore.Gate(),0);
            switchSource();
            trades=new ReferenceCashTrades(store,new NpcCashFx(new FxQuotes(world()),new ServicePricing(new WealthValuation())),wall,(phase,terms,quote,reads)->allowed);
        }
        void switchSource(){
            var market=store.transaction(tx->MarketStore.binding(tx::query,trader)).market();
            var next=new SourceRef(world(),market,offer.goods().priceKey(),source,active==null?1:active.revision()+1,UUID.randomUUID(),valuationSnapshot.id(),manifest.configHash(),manifest.inputs().recipeHash(),manifest.providerHash(),implementation,ReferencePricing.ROUNDING,active==null?UUID.randomUUID():active.activation());
            assertEquals(Journal.Status.COMMITTED,TradeSources.transition(store,next,active,UUID.randomUUID(),new SystemActorId(UUID.randomUUID())).status());active=next;
            var fx=store.transaction(tx->FxService.at(tx::query,now));priceSource=ReferenceSource.capture(active,valuationSnapshot,"minecraft:bread","{}",USD,fx);
        }
        WorldId world(){return store.snapshot().worldId();}
        Terms terms(boolean unmarked){return terms(unmarked,USD);}
        Terms terms(boolean unmarked,EconomyCurrency selected){var compared=store.transaction(tx->priceSource.comparisons(tx::query,CatalogStore.read(tx::query,trader,1),offer));UUID original=UUID.randomUUID(),input=UUID.randomUUID();var slot=new Deposits.Slot(0,new CashIssuance.Claim(world(),unmarked?UUID.randomUUID():lot,selected==EUR?"economy:eur_bill_5":"economy:usd_bill_5",1),"exact-saved-full-components",unmarked);
            var terms=new Terms(original,actor,UUID.randomUUID(),trader,1,0,1,selected,List.of(slot),active.epoch(),priceSource,Rational.ZERO,1,"authoritative-reputation",compared,input,"fixture-exact-input:"+original,UUID.randomUUID(),UUID.randomUUID(),"combined-capacity:"+original,1);
            var custodyTerms=new Custody.Terms(actor,terms.exactInputItems(),ReferenceCashTrades.inputSource(terms),ReferenceCashTrades.inputDestination(terms),ReferenceCashTrades.financialIntent(world(),original,actor));var intent=new Journal.Intent(new OperationId(input),new RequestId(input),actor,Custody.Action.RESERVE.kind(),Custody.reserveRequest(custodyTerms),null,null);submit(Custody.Action.RESERVE.kind(),Custody.registration(Custody.Action.RESERVE,(a,i,c,r)->true,(c,e,r)->true,null),intent);return terms;
        }
        void saved(Terms terms){var evidence=new Custody.Evidence(Custody.EvidenceKind.RESERVATION_SAVED,"independent-fixture-save",ReferenceCashTrades.inputSource(terms),"save-token");submit(Custody.Action.RESERVATION_SAVED.kind(),Custody.registration(Custody.Action.RESERVATION_SAVED,(a,i,c,r)->true,(c,e,r)->true,null),Custody.request(terms.inputCustody(),null,evidence,"fixture saved originals"));}
        void submit(Journal.Kind kind,JournalEngine.Registration reg,Journal.CanonicalRequest request){submit(kind,reg,new Journal.Intent(new OperationId(UUID.randomUUID()),new RequestId(UUID.randomUUID()),actor,kind,request,null,null));}
        void submit(Journal.Kind kind,JournalEngine.Registration reg,Journal.Intent intent){var outcome=new JournalEngine(store,(a,k)->true,Map.of(kind,reg)).submit(intent);assertEquals(Journal.Status.COMMITTED,outcome.status(),outcome.result().code());}
        long total(ActivityFacts.Kind kind){return store.transaction(tx->Long.parseLong(tx.query("SELECT COALESCE(SUM(minor),0) AS total FROM activity_day_counters WHERE kind=?",List.of(kind.name()),1).getFirst().get("total")));}
        public void close()throws Exception{store.close();}
    }
}
