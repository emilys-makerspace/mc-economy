package ca.emilybelnavis.mcmods.economy.valuation;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrewingValuesTest {
  @TempDir Path temporary;

  private static BrewingGraph.State state(String form, String potion) {
    return new BrewingGraph.State("minecraft:" + form, "minecraft:" + potion);
  }

  private static BrewingGraph.Mix mix(
      String id, BrewingGraph.State from, BrewingGraph.State to, String ingredient) {
    return new BrewingGraph.Mix(
        "fixture:" + id,
        from,
        to,
        "minecraft:" + ingredient,
        ingredient.equals("dragon_breath") ? "GLASS_BOTTLE_NO_SALVAGE" : "NONE");
  }

  private static RecipeGraph.Node node(
      String id, String serializer, String output, int count, String... ingredients) {
    return new RecipeGraph.Node(
        "fixture:" + id,
        "minecraft:" + serializer,
        "minecraft:" + output,
        count,
        Arrays.stream(ingredients)
            .map(i -> RecipeGraph.Ingredient.item("minecraft:" + i))
            .toList());
  }

  private static RecipeGraph.Graph materials() {
    return new RecipeGraph.Graph(
        List.of(
            node("glass", "smelting", "glass", 1, "sand"),
            node("bottles", "crafting_shaped", "glass_bottle", 3, "glass", "glass", "glass"),
            node("powder", "crafting_shapeless", "blaze_powder", 2, "blaze_rod")),
        Map.of(),
        Map.of());
  }

  private static BrewingGraph.Graph brewing(boolean compatible, List<BrewingGraph.Mix> extra) {
    var definitions = new TreeMap<String, BrewingGraph.Potion>();
    for (String name : List.of("water", "awkward", "mundane", "luck"))
      definitions.put("minecraft:" + name, new BrewingGraph.Potion("minecraft:" + name, List.of()));
    var mixes = new ArrayList<BrewingGraph.Mix>();
    mixes.add(mix("wart", state("potion", "water"), state("potion", "awkward"), "nether_wart"));
    // Form transitions apply to exact potion identity, preserving the prior bottle/content cost.
    for (String name : List.of("water", "awkward", "mundane", "luck")) {
      mixes.add(
          mix(name + "_splash", state("potion", name), state("splash_potion", name), "gunpowder"));
      mixes.add(
          mix(
              name + "_lingering",
              state("splash_potion", name),
              state("lingering_potion", name),
              "dragon_breath"));
    }
    mixes.addAll(extra);
    return new BrewingGraph.Graph(
        1,
        BrewingGraph.ADAPTER,
        BrewingGraph.RULES_HASH,
        compatible,
        definitions,
        new ArrayList<>(BrewingGraph.FORMS),
        mixes,
        compatible ? Map.of() : Map.of("custom_registry", "UNSUPPORTED_CUSTOM_RECIPE"),
        ValuationData.hash("captured vanilla lists"));
  }

  private static ValuationData.Manifest manifest(
      RecipeGraph.Graph recipes, BrewingGraph.Graph brewing, ValuationConfig.Config config) {
    var inputs =
        new ValuationData.Inputs(
            ValuationData.catalog().entries().keySet(),
            ValuationData.hash(recipes.hash() + brewing.hash()),
            ValuationData.hash("tags"),
            ValuationData.hash("features"));
    return ValuationData.prepare(inputs, config, List.of(), recipes, brewing);
  }

  private static EffectiveFxSnapshot fx(SqliteStorage store) {
    var wall = Clock.fixed(Instant.parse("2026-11-08T12:00:00Z"), ZoneOffset.UTC);
    UUID op = UUID.randomUUID();
    assertEquals(
        Journal.Status.COMMITTED,
        new JournalEngine(store, (a, k) -> true, Map.of(FxService.OPEN, FxService.opening(wall)))
            .submit(
                new Journal.Intent(
                    new OperationId(op),
                    new RequestId(op),
                    new PlayerId(UUID.randomUUID()),
                    FxService.OPEN,
                    new Journal.CanonicalRequest(1, Map.of("currency", "CAD")),
                    null,
                    null))
            .status());
    return store.transaction(tx -> FxService.at(tx::query, wall.millis()));
  }

  private static RecipeValues.Result value(
      ValuationData.Manifest manifest,
      String form,
      String potion,
      long count,
      EconomyCurrency currency,
      EffectiveFxSnapshot fx) {
    return RecipeValues.evaluate(
        manifest,
        "minecraft:" + form,
        BrewingGraph.components("minecraft:" + potion),
        count,
        currency,
        fx);
  }

  @Test
  void transformedBatchKeepsOneBottleExactMaterialsAndCapturedForeignRounding() throws Exception {
    var store = new SqliteStorage();
    store.initialize(temporary.toRealPath());
    try {
      var fx = fx(store);
      var manifest =
          manifest(materials(), brewing(true, List.of()), ValuationConfig.Config.empty());
      assertEquals(
          10,
          value(manifest, "potion", "water", 1, EconomyCurrency.CAD, fx).unitMoney().minorUnits());
      assertEquals(
          50,
          value(manifest, "potion", "awkward", 1, EconomyCurrency.CAD, fx)
              .unitMoney()
              .minorUnits());
      var splash = value(manifest, "splash_potion", "awkward", 1, EconomyCurrency.CAD, fx);
      assertEquals(Rational.of(220).divide(Rational.of(3)), splash.exactUnitMinor());
      assertEquals(73, splash.unitMoney().minorUnits());
      var lingering = value(manifest, "lingering_potion", "awkward", 3, EconomyCurrency.CAD, fx);
      assertEquals(RecipeValues.Status.READY, lingering.status());
      assertEquals(Rational.of(2750).divide(Rational.of(3)), lingering.exactUnitMinor());
      assertEquals(917, lingering.unitMoney().minorUnits());
      assertEquals(2751, lingering.totalMoney().minorUnits());
      assertEquals(
          1,
          lingering.explanation().steps().stream()
              .filter(s -> s.kind().equals("WATER_FREE_CONTENT_BOTTLE_ONCE"))
              .count());
      // One potion bottle plus the bottle already purchased inside the shared dragon-breath input.
      assertEquals(
          2,
          lingering.explanation().steps().stream()
              .filter(s -> s.item().equals("minecraft:glass_bottle"))
              .count());
      assertTrue(
          lingering.explanation().steps().stream()
              .anyMatch(s -> s.kind().contains("GLASS_BOTTLE_NO_SALVAGE")));
      var foreign = value(manifest, "lingering_potion", "awkward", 3, EconomyCurrency.USD, fx);
      assertEquals(Rational.of(687500).divide(Rational.of(1047)), foreign.exactUnitMinor());
      assertEquals(657, foreign.unitMoney().minorUnits());
      assertEquals(1971, foreign.totalMoney().minorUnits());
      assertEquals(fx.id(), foreign.fxPublication());
      assertTrue(foreign.explanation().steps().size() <= 256);
    } finally {
      store.close();
    }
  }

  @Test
  void supportedPathsStayCheapestWhileCustomStatesAndJoinedDepthLimitsRefuse() throws Exception {
    var store = new SqliteStorage();
    store.initialize(temporary.toRealPath());
    try {
      var fx = fx(store);
      var graph =
          brewing(
              true,
              List.of(
                  mix("expensive", state("potion", "water"), state("potion", "awkward"), "diamond"),
                  mix(
                      "cycle_out",
                      state("potion", "awkward"),
                      state("potion", "mundane"),
                      "redstone"),
                  mix(
                      "cycle_in",
                      state("potion", "mundane"),
                      state("potion", "awkward"),
                      "nether_wart")));
      var manifest = manifest(materials(), graph, ValuationConfig.Config.empty());
      assertEquals(
          50,
          value(manifest, "potion", "awkward", 1, EconomyCurrency.CAD, fx)
              .unitMoney()
              .minorUnits());
      var unknown = value(manifest, "potion", "luck", 1, EconomyCurrency.CAD, fx);
      assertNotEquals(RecipeValues.Status.READY, unknown.status());
      assertNull(unknown.unitMoney());
      assertEquals(
          RecipeValues.Status.UNSUPPORTED_IDENTITY,
          RecipeValues.evaluate(
                  manifest,
                  "minecraft:potion",
                  "{\"minecraft:potion_contents\":{\"potion\":\"minecraft:awkward\",\"custom_color\":1}}",
                  1,
                  EconomyCurrency.CAD,
                  fx)
              .status());
      assertEquals(
          RecipeValues.Status.UNSUPPORTED_IDENTITY,
          RecipeValues.evaluate(manifest, "minecraft:potion", "{}", 1, EconomyCurrency.CAD, fx)
              .status());
      assertEquals(
          RecipeValues.Status.UNSUPPORTED_RECIPE,
          value(
                  manifest(materials(), brewing(false, List.of()), ValuationConfig.Config.empty()),
                  "potion",
                  "awkward",
                  1,
                  EconomyCurrency.CAD,
                  fx)
              .status());
      // A material path legal alone becomes too deep when it follows a brewing transition.
      var ids = new ArrayList<String>();
      ids.add("blaze_powder");
      ValuationData.catalog().entries().values().stream()
          .filter(
              e ->
                  e.classification() == ValuationData.Classification.DERIVED
                      && !RecipeGraph.PROCESSING_INPUTS.containsKey(e.item())
                      && !Set.of(
                              "minecraft:glass", "minecraft:glass_bottle", "minecraft:blaze_powder")
                          .contains(e.item()))
          .map(e -> e.item().substring(10))
          .limit(31)
          .forEach(ids::add);
      var deep = new ArrayList<RecipeGraph.Node>();
      deep.addAll(
          materials().recipes().stream()
              .filter(n -> !n.output().equals("minecraft:blaze_powder"))
              .toList());
      for (int i = 0; i < ids.size(); i++)
        deep.add(
            node(
                "depth_" + i,
                "crafting_shapeless",
                ids.get(i),
                1,
                i + 1 < ids.size() ? ids.get(i + 1) : "dirt"));
      var deepGraph = new RecipeGraph.Graph(deep, Map.of(), Map.of());
      var joined = manifest(deepGraph, brewing(true, List.of()), ValuationConfig.Config.empty());
      assertEquals(
          RecipeValues.Status.READY,
          RecipeValues.evaluate(joined, "minecraft:blaze_powder", "{}", 1, EconomyCurrency.CAD, fx)
              .status());
      var limited = value(joined, "potion", "awkward", 1, EconomyCurrency.CAD, fx);
      assertEquals(RecipeValues.Status.LIMIT_EXCEEDED, limited.status());
      assertNull(limited.unitMoney());
    } finally {
      store.close();
    }
  }

  @Test
  void v3RetainsItsBrewingInputsWithoutReinterpretingOrdinaryOrBaseHistory() throws Exception {
    var store = new SqliteStorage();
    store.initialize(temporary.toRealPath());
    UUID oldId, brewingId;
    RecipeValues.Result original;
    try {
      var fx = fx(store);
      var graph = brewing(true, List.of());
      var current = manifest(materials(), graph, ValuationConfig.Config.empty());
      var legacy =
          ValuationData.prepare(current.inputs(), ValuationConfig.Config.empty(), List.of());
      var ordinary =
          ValuationData.prepare(
              current.inputs(), ValuationConfig.Config.empty(), List.of(), materials());
      assertFalse(ValuationData.encode(legacy).contains("\"brewing\":"));
      assertFalse(ValuationData.encode(legacy).contains("\"recipes\":"));
      assertFalse(ValuationData.encode(ordinary).contains("\"brewing\":"));
      var gate = new ValuationStore.Gate();
      var old = ValuationStore.publish(store, ValuationData.encode(ordinary), 1, gate, 0);
      oldId = old.id();
      assertEquals(
          RecipeValues.Status.UNSUPPORTED_IDENTITY,
          value(ordinary, "potion", "awkward", 1, EconomyCurrency.CAD, fx).status());
      var saved = ValuationStore.publish(store, ValuationData.encode(current), 2, gate, 0);
      brewingId = saved.id();
      var cache = new ValuationCache();
      cache.activate(saved, current);
      original =
          cache.evaluate(
              saved,
              "minecraft:lingering_potion",
              BrewingGraph.components("minecraft:awkward"),
              1,
              EconomyCurrency.CAD,
              fx);
      var config =
          new ValuationConfig.Config(
              List.of(),
              List.of(
                  new ValuationConfig.Entry("minecraft:nether_wart", EconomyCurrency.CAD, "2", 1)));
      var changed = manifest(materials(), graph, config);
      var newer = ValuationStore.publish(store, ValuationData.encode(changed), 3, gate, 0);
      cache.activate(newer, changed);
      assertEquals(
          950,
          cache
              .evaluate(
                  newer,
                  "minecraft:lingering_potion",
                  BrewingGraph.components("minecraft:awkward"),
                  1,
                  EconomyCurrency.CAD,
                  fx)
              .unitMoney()
              .minorUnits());
      assertEquals(
          original,
          cache.evaluate(
              saved,
              "minecraft:lingering_potion",
              BrewingGraph.components("minecraft:awkward"),
              1,
              EconomyCurrency.CAD,
              fx));
    } finally {
      store.close();
    }
    var reopened = new SqliteStorage();
    reopened.initialize(temporary.toRealPath());
    try {
      var ordinary = ValuationData.decode(ValuationStore.load(reopened, oldId).payload());
      assertEquals(2, ordinary.version());
      assertNull(ordinary.brewing());
      var saved = ValuationData.decode(ValuationStore.load(reopened, brewingId).payload());
      assertEquals(3, saved.version());
      var fx = reopened.transaction(tx -> FxService.snapshot(tx::query, original.fxPublication()));
      assertEquals(
          original, value(saved, "lingering_potion", "awkward", 1, EconomyCurrency.CAD, fx));
    } finally {
      reopened.close();
    }
  }
}
