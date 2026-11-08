package ca.emilybelnavis.mcmods.economy.valuation;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real retained manifests/FX, with detached data equivalent to a trusted capture. */
class ValuationIntegrationTest {
    @TempDir Path path;
    private static final Set<String> OWNED=Set.of("fixture:raw","fixture:refined","fixture:variant","fixture:stateful");
    private static RecipeGraph.Graph recipes() {
        return new RecipeGraph.Graph(List.of(new RecipeGraph.Node("fixture:refining","minecraft:crafting_shapeless",
                "fixture:refined",2,List.of(RecipeGraph.Ingredient.item("fixture:raw"),
                RecipeGraph.Ingredient.item("fixture:raw"),RecipeGraph.Ingredient.item("fixture:raw")))),Map.of(),Map.of());
    }
    private static BrewingGraph.Graph brewing() {
        return new BrewingGraph.Graph(1,BrewingGraph.ADAPTER,BrewingGraph.RULES_HASH,true,
                Map.of("minecraft:water",new BrewingGraph.Potion("minecraft:water",List.of())),
                new ArrayList<>(BrewingGraph.FORMS),List.of(),Map.of(),ValuationData.hash("fixture:brewing"));
    }
    private static VariantGraph.Graph variants() {
        var items=new TreeMap<String,VariantGraph.Item>();
        for(String item:ValuationData.catalog().entries().keySet())items.put(item,new VariantGraph.Item(item,0,64,0,Set.of()));
        return new VariantGraph.Graph(1,VariantGraph.ADAPTER,VariantGraph.RULES_HASH,items,Map.of(),VariantDefaults.rules(),Map.of(),Map.of());
    }
    private static ValuationData.Inputs inputs() {
        var registered=new TreeSet<>(ValuationData.catalog().entries().keySet());registered.addAll(OWNED);
        return new ValuationData.Inputs(registered,ValuationData.hash(recipes().hash()+brewing().hash()+variants().hash()),
                ValuationData.hash("fixture:tags"),ValuationData.hash("fixture:features"));
    }
    private static ValuationConfig.Entry price(String item,String amount) {
        return new ValuationConfig.Entry(item,EconomyCurrency.CAD,amount,1);
    }
    private static ValuationContributions.Provider provider(String revision,String raw) {
        return new ValuationContributions.Provider("fixture:provider",revision,OWNED,List.of(price("fixture:raw",raw)),
                List.of(new ValuationContributions.Selector("fixture:stateful","{\"fixture:state\":{\"quality\":2}}",price("fixture:stateful","10"))),
                Set.of("fixture:refined"),Map.of("fixture:variant",new VariantGraph.Rule("SUM",List.of(
                        new VariantGraph.Term("fixture:refined",2,1),new VariantGraph.Term("minecraft:diamond")),null)),
                Set.of(ValuationContributions.ORDINARY,ValuationContributions.MATERIALS));
    }
    private static ValuationData.Manifest manifest(ValuationConfig.Config config,List<ValuationContributions.Provider> providers) {
        return ValuationData.prepareWithProviders(inputs(),config,List.of(),recipes(),brewing(),variants(),providers);
    }
    private static EffectiveFxSnapshot fx(SqliteStorage store) {
        var wall=Clock.fixed(Instant.parse("2026-11-08T12:00:00Z"),ZoneOffset.UTC);UUID id=UUID.randomUUID();
        var result=new JournalEngine(store,(a,k)->true,Map.of(FxService.OPEN,FxService.opening(wall)))
                .submit(new Journal.Intent(new OperationId(id),new RequestId(id),new PlayerId(UUID.randomUUID()),FxService.OPEN,
                        new Journal.CanonicalRequest(1,Map.of("currency","CAD")),null,null));
        assertEquals(Journal.Status.COMMITTED,result.status());
        return store.transaction(tx->FxService.at(tx::query,wall.millis()));
    }
    private static RecipeValues.Result quote(ValuationData.Manifest manifest,String item,String components,long count,EffectiveFxSnapshot fx) {
        return RecipeValues.evaluate(manifest,item,components,count,EconomyCurrency.USD,fx);
    }

    @Test void providerRecipeAndVariantUseCapturedForeignFxAndRetainOriginalAcrossReloadRemovalAndReopen() throws Exception {
        var store=new SqliteStorage();store.initialize(path.toRealPath());UUID retained;RecipeValues.Result original;
        String historicalV4;
        try {
            var fx=fx(store);var gate=new ValuationStore.Gate();
            var old=ValuationData.prepare(inputs(),ValuationConfig.Config.empty(),List.of(),recipes(),brewing(),variants());
            historicalV4=ValuationData.encode(old);
            assertFalse(historicalV4.contains("\"providers\":"));
            assertEquals(historicalV4,ValuationData.encode(ValuationData.decode(historicalV4)));
            var first=manifest(ValuationConfig.Config.empty(),List.of(provider("1","2")));
            var saved=ValuationStore.publish(store,ValuationData.encode(first),1,gate,0);retained=saved.id();
            var cache=new ValuationCache();cache.activate(saved,first);
            original=cache.evaluate(saved,"fixture:variant","{}",3,EconomyCurrency.USD,fx);
            assertEquals(RecipeValues.Status.READY,original.status(),original.reason());
            // CAD2 raw *3 /2 outputs = CAD3 refined; two refined + diamond = CAD306.
            // Opening CAD/USD is 1/1.396: USD219.20 published per unit.
            assertEquals(Rational.of(7_650_000).divide(Rational.of(349)),original.exactUnitMinor());
            assertEquals(21_920,original.unitMoney().minorUnits());assertEquals(65_760,original.totalMoney().minorUnits());
            assertEquals(fx.id(),original.fxPublication());
            assertTrue(original.explanation().steps().stream().anyMatch(s->s.kind().equals("RECIPE")));
            assertTrue(original.explanation().steps().stream().anyMatch(s->s.kind().equals("VARIANT_MATERIAL_RULE")));
            var override=new ValuationConfig.Config(List.of(),List.of(price("fixture:raw","4")));
            var reloaded=manifest(override,List.of(provider("2","2")));
            var next=ValuationStore.publish(store,ValuationData.encode(reloaded),2,gate,0);cache.activate(next,reloaded);
            assertEquals(22_350,cache.evaluate(next,"fixture:variant","{}",1,EconomyCurrency.USD,fx).unitMoney().minorUnits());
            assertEquals(original,cache.evaluate(saved,"fixture:variant","{}",3,EconomyCurrency.USD,fx));
            var removed=manifest(ValuationConfig.Config.empty(),List.of());
            ValuationStore.publish(store,ValuationData.encode(removed),3,gate,0);
            var unavailable=quote(removed,"fixture:variant","{}",1,fx);
            assertEquals(RecipeValues.Status.MOD_UNREGISTERED,unavailable.status());assertNull(unavailable.unitMoney());
            assertEquals(List.of(next.id(),saved.id()),ValuationStore.history(store,ValuationStore.active(store).id(),16).stream().map(ValuationStore.Metadata::id).toList());
        } finally {store.close();}
        var reopened=new SqliteStorage();reopened.initialize(path.toRealPath());
        try {
            var saved=ValuationStore.load(reopened,retained);var retainedManifest=ValuationData.decode(saved.payload());
            var capturedFx=reopened.transaction(tx->FxService.snapshot(tx::query,original.fxPublication()));
            assertEquals(original,quote(retainedManifest,"fixture:variant","{}",3,capturedFx));
            assertEquals(historicalV4,ValuationData.encode(ValuationData.decode(historicalV4)));
        } finally {reopened.close();}
    }

    @Test void conflictsRejectWholeCandidateEvenWithOverrideAndExactSelectorsNeverStripUnknownState() throws Exception {
        var store=new SqliteStorage();store.initialize(path.toRealPath());
        try {
            var fx=fx(store);var first=provider("1","2");var config=ValuationConfig.Config.empty();
            var current=manifest(config,List.of(first));var gate=new ValuationStore.Gate();
            var saved=ValuationStore.publish(store,ValuationData.encode(current),1,gate,0);
            var overlapping=new ValuationContributions.Provider("fixture:other","1",Set.of("fixture:raw"),
                    List.of(price("fixture:raw","2")),List.of(),Set.of(),Map.of(),Set.of());
            var override=new ValuationConfig.Config(List.of(),List.of(price("fixture:raw","9")));
            assertThrows(IllegalArgumentException.class,()->manifest(override,List.of(first,overlapping)));
            assertEquals(saved.id(),ValuationStore.active(store).id());
            var selected=quote(current,"fixture:stateful","{\"fixture:state\":{\"quality\":2}}",1,fx);
            assertEquals(RecipeValues.Status.READY,selected.status(),selected.reason());assertEquals(716,selected.unitMoney().minorUnits());
            var changed=quote(current,"fixture:stateful","{\"fixture:state\":{\"quality\":3}}",1,fx);
            assertEquals(RecipeValues.Status.UNSUPPORTED_IDENTITY,changed.status());assertNull(changed.unitMoney());
            var extra=quote(current,"fixture:stateful","{\"fixture:state\":{\"quality\":2},\"fixture:hidden\":true}",1,fx);
            assertEquals(RecipeValues.Status.UNSUPPORTED_IDENTITY,extra.status());assertNull(extra.unitMoney());
            var overridden=manifest(new ValuationConfig.Config(List.of(),List.of(price("fixture:stateful","20"))),List.of(first));
            assertNull(quote(overridden,"fixture:stateful","{\"fixture:state\":{\"quality\":2}}",1,fx).unitMoney());
        } finally {store.close();}
    }
}
