package ca.emilybelnavis.mcmods.economy.trade.pricing;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import ca.emilybelnavis.mcmods.economy.trade.catalog.*;
import ca.emilybelnavis.mcmods.economy.trade.market.*;
import ca.emilybelnavis.mcmods.economy.trade.stock.*;
import ca.emilybelnavis.mcmods.economy.valuation.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real worker-boundary activation witness; native permission/menu delivery is checked separately. */
class ReferenceActivationTest {
    @TempDir Path path;

    @Test void reviewedActivationRequiresCurrentAdmissionAndUsesPublishedUnitRatherThanEmeraldCount() throws Exception {
        var store=new SqliteStorage();store.initialize(path.toRealPath());
        try {
            long now=Instant.parse("2026-11-08T12:00:00Z").toEpochMilli();
            var actor=new SystemActorId(UUID.randomUUID());
            var fxKind=FxService.OPEN;UUID open=UUID.randomUUID();
            var opening=new JournalEngine(store,(a,k)->true,Map.of(fxKind,FxService.opening(Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC))))
                    .submit(new Journal.Intent(new OperationId(open),new RequestId(open),actor,fxKind,new Journal.CanonicalRequest(1,Map.of("currency","CAD")),null,null));
            assertEquals(Journal.Status.COMMITTED,opening.status());
            UUID trader=UUID.randomUUID();var position=new MarketFacts.Position("minecraft:overworld",0,64,0);
            for(int n=0;n<100;n++)if(!MarketStore.resolve(store,trader,position,position,EconomyCurrency.CAD,true).pending())break;
            var offer=new TradeCatalog.Offer(0,Pricing.Side.BUY,new TradeCatalog.Goods("minecraft:bread",TradeCatalog.Match.EXACT_COMPONENTS,"{}"),1,7,List.of(),16,2);
            var capture=CatalogStore.capture(store,new TradeCatalog.Capture(trader,1,List.of(offer)));StockStore.install(store,trader,1,false);
            var saved=publish(store,now);var world=store.snapshot().worldId();
            var review=ReferenceTrading.review(store,world,trader,saved.id());
            assertEquals(saved.id(),review.snapshot());assertEquals(1,review.catalogRevision());
            assertTrue(review.registry().ordinaryComplete(),review.registry().unavailable().toString());
            assertTrue(review.offers().getFirst().available(),review.offers().getFirst().reason());
            var originalStock=store.transaction(tx->StockStore.trader(tx::query,trader));
            UUID refused=UUID.randomUUID();
            try {
                var rejected=ReferenceTrading.activate(store,world,trader,actor,refused,review,()->false);
                assertNotEquals(Journal.Status.COMMITTED,rejected.status());
            } catch(IllegalArgumentException|IllegalStateException|SecurityException rejected) {
                // Revoked owner-thread readiness can reject before an operation is admitted.
            }
            assertTrue(store.transaction(tx->tx.query("SELECT item FROM trade_source_active",List.of(),16)).isEmpty());
            UUID operation=UUID.randomUUID();
            var committed=ReferenceTrading.activate(store,world,trader,actor,operation,review,()->true);
            assertEquals(Journal.Status.COMMITTED,committed.status(),committed.result().code());
            long sequence=store.lastCommittedSequence();
            assertEquals(committed,ReferenceTrading.activate(store,world,trader,actor,operation,review,()->true));
            assertEquals(sequence,store.lastCommittedSequence(),"Original activation replay creates no new epoch");
            var sources=store.transaction(tx->ReferenceTrading.sources(tx::query,world,capture,now));
            var source=sources.get(0);assertNotNull(source);
            assertEquals(1000,source.unitMinor());
            assertEquals(1000L*offer.standardQuantity(),source.basis(offer.emeraldCount(),offer.goodsCount(),offer.standardQuantity()).minorPerStandard().numerator().longValueExact());
            assertEquals(saved.id(),source.ref().valuationManifest());
            assertEquals(originalStock,store.transaction(tx->StockStore.trader(tx::query,trader)),"Activation does not reset stock/progression");
            assertEquals(capture,store.transaction(tx->CatalogStore.read(tx::query,trader,1)),"Activation preserves original offers/emerald provenance");
        } finally {store.close();}
        var reopened=new SqliteStorage();reopened.initialize(path.toRealPath());
        try {assertEquals(1,reopened.transaction(tx->tx.query("SELECT operation_id FROM reference_activations",List.of(),16)).size());}
        finally {reopened.close();}
    }

    /** Explicit fixture anchors make this a numeric coverage witness, not a fake precomputed report. */
    private static ValuationStore.Snapshot publish(SqliteStorage store,long now) {
        var ids=ValuationData.catalog().entries().keySet();var items=new TreeMap<String,VariantGraph.Item>();
        var overrides=new ArrayList<ValuationConfig.Entry>();
        for(var entry:ValuationData.catalog().entries().values()) {
            items.put(entry.item(),new VariantGraph.Item(entry.item(),0,64,0,Set.of()));
            if(entry.classification()==ValuationData.Classification.DERIVED||entry.classification()==ValuationData.Classification.VARIANT)
                overrides.add(new ValuationConfig.Entry(entry.item(),EconomyCurrency.CAD,"10",1));
        }
        var recipes=new RecipeGraph.Graph(List.of(),Map.of(),Map.of());
        var water=new BrewingGraph.Potion("minecraft:water",List.of());
        var brewing=new BrewingGraph.Graph(1,BrewingGraph.ADAPTER,BrewingGraph.RULES_HASH,true,Map.of(water.id(),water),new ArrayList<>(BrewingGraph.FORMS),List.of(
                new BrewingGraph.Mix("fixture:splash",new BrewingGraph.State("minecraft:potion",water.id()),new BrewingGraph.State("minecraft:splash_potion",water.id()),"minecraft:gunpowder","NONE"),
                new BrewingGraph.Mix("fixture:lingering",new BrewingGraph.State("minecraft:splash_potion",water.id()),new BrewingGraph.State("minecraft:lingering_potion",water.id()),"minecraft:dragon_breath","GLASS_BOTTLE_NO_SALVAGE")),Map.of(),ValuationData.hash("activation-fixture"));
        var variants=new VariantGraph.Graph(1,VariantGraph.ADAPTER,VariantGraph.RULES_HASH,items,Map.of(),Map.of(),Map.of(),Map.of());
        var inputs=new ValuationData.Inputs(ids,ValuationData.hash("recipes"),ValuationData.hash("tags"),ValuationData.hash("features"));
        var manifest=ValuationData.prepare(inputs,new ValuationConfig.Config(List.of(),overrides),List.of(),recipes,brewing,variants);
        return ValuationStore.publish(store,ValuationData.encode(manifest),now,new ValuationStore.Gate(),0);
    }
}
