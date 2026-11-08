package ca.emilybelnavis.mcmods.economy.trade.pricing;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import ca.emilybelnavis.mcmods.economy.trade.catalog.TradeCatalog;
import ca.emilybelnavis.mcmods.economy.trade.market.*;
import ca.emilybelnavis.mcmods.economy.valuation.*;
import com.google.gson.Gson;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real immutable carry/source evidence, separate from account and physical-cash acceptance. */
class TradeSourcesTest {
    @TempDir Path path;

    @Test void transitionRetainsNonzeroLegacyCarryAndDemandWithoutPaymentAndReplaysItsOriginal() throws Exception {
        var store=new SqliteStorage();store.initialize(path.toRealPath());
        SourceRef activated,refreshed;Pricing.Carry originalCarry;String originalCarryPayload;MarketFacts.Price anchored;UUID transition;SystemActorId actor;
        try {
            var trader=UUID.randomUUID();var position=new MarketFacts.Position("minecraft:overworld",0,64,0);
            MarketFacts.Binding binding=null;
            for(int n=0;n<100;n++) {
                var resolved=MarketStore.resolve(store,trader,position,position,EconomyCurrency.CAD,true);
                if(!resolved.pending()){binding=resolved.binding();break;}
            }
            assertNotNull(binding);UUID market=binding.market();
            var goods=new TradeCatalog.Goods("minecraft:bread",TradeCatalog.Match.EXACT_COMPONENTS,"{}");
            var key=new Pricing.Key(market,goods.priceKey(),UUID.randomUUID(),Pricing.Side.BUY,UUID.randomUUID());
            var basis=new TradePriceSource.Basis(UUID.randomUUID(),1,UUID.randomUUID(),EconomyCurrency.CAD,Rational.of(100));
            var comparison=new Pricing.Comparison(goods.priceKey(),Rational.of(100),Rational.of(50));
            var legacy=Pricing.quote(new Pricing.Request(key,basis,6,1,new Pricing.Demand(Rational.ZERO,0),0,Rational.ZERO,List.of(comparison),Pricing.Carry.empty(key)));
            assertTrue(legacy.available(),legacy.reason());originalCarry=legacy.quote().nextCarry();
            assertNotEquals(Rational.ZERO,originalCarry.residual(),"Fixture must retain a real fractional residual");
            UUID oldOperation=UUID.randomUUID();var kind=new Journal.Kind("fixture:source","fixture:legacy-price");
            var intent=new Journal.Intent(new OperationId(oldOperation),new RequestId(oldOperation),new SystemActorId(UUID.randomUUID()),kind,new Journal.CanonicalRequest(1,Map.of("legacy","price")),null,null);
            var old=new JournalEngine(store,(a,k)->true,Map.of(kind,new JournalEngine.Registration(1,(i,r)->MarketStore.preparePriceTransition(r,store.snapshot().worldId(),oldOperation,market,goods.priceKey(),0,0,legacy.quote()).commit()))).submit(intent);
            assertEquals(Journal.Status.COMMITTED,old.status());
            anchored=store.transaction(tx->MarketStore.price(tx::query,market,goods.priceKey()));
            originalCarryPayload=store.transaction(tx->tx.query("SELECT payload FROM trade_market_carries WHERE market_id=? AND item=?",List.of(market.toString(),goods.priceKey()),1)).getFirst().get("payload");
            var balances=store.transaction(tx->tx.query("SELECT account_id,currency,minor_units FROM ledger_balances ORDER BY account_id,currency",List.of(),1024));
            var saved=publishValuation(store);var manifest=ValuationData.decode(saved.payload());
            activated=new SourceRef(store.snapshot().worldId(),market,goods.priceKey(),UUID.randomUUID(),1,UUID.randomUUID(),saved.id(),manifest.configHash(),manifest.inputs().recipeHash(),manifest.providerHash(),ReferencePricing.VERSION,ReferencePricing.ROUNDING,UUID.randomUUID());
            transition=UUID.randomUUID();actor=new SystemActorId(UUID.randomUUID());
            var accepted=TradeSources.transition(store,activated,null,transition,actor);
            assertEquals(Journal.Status.COMMITTED,accepted.status(),accepted.result().code());
            long sequence=store.lastCommittedSequence();
            assertEquals(accepted,TradeSources.transition(store,activated,null,transition,actor),"Original transition request is replayable without a new timestamp");
            assertEquals(sequence,store.lastCommittedSequence());
            var event=store.transaction(tx->tx.query("SELECT payload FROM trade_source_events WHERE operation_id=?",List.of(transition.toString()),1)).getFirst().get("payload");
            assertEquals(List.of(originalCarry),TradeSources.decodeBoundary(event).retired());
            var retired=store.transaction(tx->tx.query("SELECT payload,epoch,operation_id FROM trade_source_retirements WHERE market_id=? AND item=?",List.of(market.toString(),goods.priceKey()),16));
            assertEquals(1,retired.size());assertEquals(originalCarry,new Gson().fromJson(retired.getFirst().get("payload"),Pricing.Carry.class));
            assertEquals(key.epoch().toString(),retired.getFirst().get("epoch"));assertEquals(transition.toString(),retired.getFirst().get("operation_id"));
            assertEquals(originalCarry,store.transaction(tx->MarketStore.carry(tx::query,key)));
            assertEquals(originalCarryPayload,store.transaction(tx->tx.query("SELECT payload FROM trade_market_carries WHERE market_id=? AND item=?",List.of(market.toString(),goods.priceKey()),1)).getFirst().get("payload"));
            assertEquals(anchored,store.transaction(tx->MarketStore.price(tx::query,market,goods.priceKey())));
            assertEquals(anchored,store.transaction(tx->TradeSources.price(tx::query,market,goods.priceKey())),"Reference begins from the original demand anchor");
            assertEquals(balances,store.transaction(tx->tx.query("SELECT account_id,currency,minor_units FROM ledger_balances ORDER BY account_id,currency",List.of(),1024)));
            assertThrows(IllegalArgumentException.class,()->store.transaction(tx->{TradeSources.requireLegacy(tx::query,store.snapshot().worldId(),market,goods.priceKey());return null;}));
            var raced=new SourceRef(activated.world(),market,activated.item(),activated.source(),2,UUID.randomUUID(),saved.id(),manifest.configHash(),manifest.inputs().recipeHash(),manifest.providerHash(),ReferencePricing.VERSION,ReferencePricing.ROUNDING,activated.activation());
            assertThrows(IllegalArgumentException.class,()->TradeSources.transition(store,raced,null,UUID.randomUUID(),actor));
            assertEquals(sequence,store.lastCommittedSequence());assertEquals(activated,store.transaction(tx->TradeSources.current(tx::query,market,goods.priceKey())));
            var otherGoods=new TradeCatalog.Goods("minecraft:carrot",TradeCatalog.Match.EXACT_COMPONENTS,"{}");
            var other=new SourceRef(activated.world(),market,otherGoods.priceKey(),UUID.randomUUID(),1,UUID.randomUUID(),saved.id(),manifest.configHash(),manifest.inputs().recipeHash(),manifest.providerHash(),ReferencePricing.VERSION,ReferencePricing.ROUNDING,UUID.randomUUID());
            assertEquals(Journal.Status.COMMITTED,TradeSources.transition(store,other,null,UUID.randomUUID(),actor).status());
            var reloaded=publishValuation(store,"11");
            refreshed=store.transaction(tx->TradeSources.current(tx::query,market,goods.priceKey()));
            var otherRefreshed=store.transaction(tx->TradeSources.current(tx::query,market,other.item()));
            assertEquals(reloaded.id(),refreshed.valuationManifest());assertEquals(reloaded.id(),otherRefreshed.valuationManifest());
            assertEquals(2,refreshed.revision());assertNotEquals(activated.epoch(),refreshed.epoch());
            var atomic=store.transaction(tx->tx.query("SELECT operation_id,sequence FROM trade_source_events WHERE sequence=(SELECT MAX(sequence) FROM trade_source_events) ORDER BY item",List.of(),16));
            assertEquals(2,atomic.size());assertEquals(atomic.getFirst(),atomic.getLast(),"Both enabled scopes share the valuation publication transaction");
            var publication=store.transaction(tx->tx.query("SELECT operation_id FROM valuation_manifests WHERE id=?",List.of(reloaded.id().toString()),1));
            assertEquals(publication.getFirst().get("operation_id"),atomic.getFirst().get("operation_id"));
            assertEquals(2,store.transaction(tx->tx.query("SELECT item FROM trade_source_active",List.of(),16)).size(),"No new scope activated by reload");
            assertEquals(anchored,store.transaction(tx->TradeSources.price(tx::query,market,goods.priceKey())));
            assertEquals(originalCarry,store.transaction(tx->MarketStore.carry(tx::query,key)));
            assertEquals(balances,store.transaction(tx->tx.query("SELECT account_id,currency,minor_units FROM ledger_balances ORDER BY account_id,currency",List.of(),1024)));

        } finally {store.close();}
        var reopened=new SqliteStorage();reopened.initialize(path.toRealPath());
        try {
            assertEquals(refreshed,reopened.transaction(tx->TradeSources.current(tx::query,activated.market(),activated.item())));
            assertEquals(anchored,reopened.transaction(tx->MarketStore.price(tx::query,activated.market(),activated.item())));
            assertEquals(originalCarry,reopened.transaction(tx->MarketStore.carry(tx::query,originalCarry.key())));
        } finally {reopened.close();}
    }

    @Test void nearestUnitRoundingRefusesCheapGapAndMultipliesPublishedUnitsWithoutCarry() {
        String item=TradeCatalog.digest("rounding-fixture");
        var key=new Pricing.Key(UUID.randomUUID(),item,UUID.randomUUID(),Pricing.Side.BUY,UUID.randomUUID());
        var cheap=new TradePriceSource.Basis(UUID.randomUUID(),1,UUID.randomUUID(),EconomyCurrency.CAD,Rational.of(2));
        var refused=ReferencePricing.quote(new ReferencePricing.Request(key,cheap,1,1,new Pricing.Demand(Rational.ZERO,0),0,Rational.ZERO,List.of(new Pricing.Comparison(item,Rational.of(2),Rational.of("1.6")))));
        assertFalse(refused.available());assertEquals("ROUNDED_COHORT_SPREAD_UNAVAILABLE",refused.reason());assertNull(refused.quote());
        var known=new TradePriceSource.Basis(cheap.source(),1,cheap.fxSnapshot(),EconomyCurrency.CAD,Rational.of(100));
        var priced=ReferencePricing.quote(new ReferencePricing.Request(key,known,6,3,new Pricing.Demand(Rational.ZERO,0),0,Rational.ZERO,List.of(new Pricing.Comparison(item,Rational.of(100),Rational.of(50)))));
        assertTrue(priced.available(),priced.reason());
        assertEquals(Rational.of("50.125"),priced.quote().exactPrincipal());assertEquals(17,priced.quote().unitMinor());assertEquals(51,priced.quote().minor());
    }

    private static ValuationStore.Snapshot publishValuation(SqliteStorage store) {return publishValuation(store,"10");}
    private static ValuationStore.Snapshot publishValuation(SqliteStorage store,String breadPrice) {
        var ids=ValuationData.catalog().entries().keySet();var items=new TreeMap<String,VariantGraph.Item>();
        for(String id:ids)items.put(id,new VariantGraph.Item(id,0,64,0,Set.of()));
        var recipes=new RecipeGraph.Graph(List.of(),Map.of(),Map.of());
        var brewing=new BrewingGraph.Graph(1,BrewingGraph.ADAPTER,BrewingGraph.RULES_HASH,true,Map.of(),new ArrayList<>(BrewingGraph.FORMS),List.of(),Map.of(),ValuationData.hash("source-fixture"));
        var variants=new VariantGraph.Graph(1,VariantGraph.ADAPTER,VariantGraph.RULES_HASH,items,Map.of(),Map.of(),Map.of(),Map.of());
        var inputs=new ValuationData.Inputs(ids,ValuationData.hash("recipes"),ValuationData.hash("tags"),ValuationData.hash("features"));
        var manifest=ValuationData.prepare(inputs,new ValuationConfig.Config(List.of(),List.of(new ValuationConfig.Entry("minecraft:bread",EconomyCurrency.CAD,breadPrice,1))),List.of(),recipes,brewing,variants);
        return ValuationStore.publish(store,ValuationData.encode(manifest),1,new ValuationStore.Gate(),0);
    }
}
