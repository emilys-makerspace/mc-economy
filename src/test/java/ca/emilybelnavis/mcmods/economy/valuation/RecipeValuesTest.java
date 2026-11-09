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

class RecipeValuesTest {
  @TempDir Path path;
  private static final String PREFIX = "minecraft:";

  private static RecipeGraph.Ingredient ingredient(String item) {
    return RecipeGraph.Ingredient.item(PREFIX + item);
  }

  private static RecipeGraph.Node node(String id, String output, int count, String... inputs) {
    return new RecipeGraph.Node(
        "fixture:" + id,
        "minecraft:crafting_shapeless",
        PREFIX + output,
        count,
        Arrays.stream(inputs).map(RecipeValuesTest::ingredient).toList());
  }

  private static RecipeGraph.Node cook(String id, String output, String input) {
    return new RecipeGraph.Node(
        "fixture:" + id, "minecraft:smelting", PREFIX + output, 1, List.of(ingredient(input)));
  }

  private static RecipeGraph.Graph graph(List<RecipeGraph.Node> nodes) {
    return new RecipeGraph.Graph(nodes, Map.of(), Map.of());
  }

  private static ValuationData.Inputs inputs(RecipeGraph.Graph graph) {
    var items = new TreeSet<>(ValuationData.catalog().entries().keySet());
    items.add("fixture:unregistered");
    return new ValuationData.Inputs(
        items, graph.hash(), ValuationData.hash("tags"), ValuationData.hash("features"));
  }

  private static ValuationData.Manifest manifest(RecipeGraph.Graph graph) {
    return ValuationData.prepare(inputs(graph), ValuationConfig.Config.empty(), List.of(), graph);
  }

  private static EffectiveFxSnapshot fx(SqliteStorage store) {
    var wall = Clock.fixed(Instant.parse("2026-11-08T12:00:00Z"), ZoneOffset.UTC);
    UUID op = UUID.randomUUID();
    var actor = new PlayerId(UUID.randomUUID());
    assertEquals(
        Journal.Status.COMMITTED,
        new JournalEngine(store, (a, k) -> true, Map.of(FxService.OPEN, FxService.opening(wall)))
            .submit(
                new Journal.Intent(
                    new OperationId(op),
                    new RequestId(op),
                    actor,
                    FxService.OPEN,
                    new Journal.CanonicalRequest(1, Map.of("currency", "CAD")),
                    null,
                    null))
            .status());
    return store.transaction(tx -> FxService.at(tx::query, wall.millis()));
  }

  private static RecipeValues.Result value(
      ValuationData.Manifest manifest, String item, EffectiveFxSnapshot fx) {
    return RecipeValues.evaluate(manifest, PREFIX + item, "{}", 1, EconomyCurrency.CAD, fx);
  }

  @Test
  void ordinaryOutputsUseExactInputsCanonicalSticksAndOneProcessingDifference() throws Exception {
    var store = new SqliteStorage();
    store.initialize(path.toRealPath());
    try {
      var fx = fx(store);
      var nodes =
          List.of(
              node("oak_planks", "oak_planks", 4, "oak_log"),
              node("cherry_planks", "cherry_planks", 4, "cherry_log"),
              node("cheap_bamboo_sticks", "stick", 1, "bamboo", "bamboo"),
              node("oak_sticks", "stick", 4, "oak_planks", "oak_planks"),
              node(
                  "cherry_doors",
                  "cherry_door",
                  3,
                  "cherry_planks",
                  "cherry_planks",
                  "cherry_planks",
                  "cherry_planks",
                  "cherry_planks",
                  "cherry_planks"),
              cook("iron_smelting", "iron_ingot", "raw_iron"),
              cook("copper_smelting", "copper_ingot", "raw_copper"),
              cook("gold_smelting", "gold_ingot", "raw_gold"),
              cook("cheap_nether_ore", "gold_ingot", "nether_gold_ore"),
              cook("beef_cooking", "cooked_beef", "beef"),
              cook("glass_smelting", "glass", "sand"),
              node(
                  "iron_block",
                  "iron_block",
                  1,
                  "iron_ingot",
                  "iron_ingot",
                  "iron_ingot",
                  "iron_ingot",
                  "iron_ingot",
                  "iron_ingot",
                  "iron_ingot",
                  "iron_ingot",
                  "iron_ingot"),
              node("reverse_iron_block", "iron_ingot", 9, "iron_block"),
              node("iron_nuggets", "iron_nugget", 9, "iron_ingot"),
              node(
                  "pickaxe",
                  "iron_pickaxe",
                  1,
                  "iron_ingot",
                  "iron_ingot",
                  "iron_ingot",
                  "stick",
                  "stick"));
      var manifest = manifest(graph(nodes));
      assertEquals(50, value(manifest, "oak_planks", fx).unitMoney().minorUnits());
      assertEquals(200, value(manifest, "cherry_planks", fx).unitMoney().minorUnits());
      assertEquals(400, value(manifest, "cherry_door", fx).unitMoney().minorUnits());
      assertEquals(25, value(manifest, "stick", fx).unitMoney().minorUnits());
      assertEquals(300, value(manifest, "copper_ingot", fx).unitMoney().minorUnits());
      assertEquals(600, value(manifest, "iron_ingot", fx).unitMoney().minorUnits());
      assertEquals(2400, value(manifest, "gold_ingot", fx).unitMoney().minorUnits());
      assertEquals(5400, value(manifest, "iron_block", fx).unitMoney().minorUnits());
      var nuggets = value(manifest, "iron_nugget", fx);
      assertEquals(Rational.of(200).divide(Rational.of(3)), nuggets.exactUnitMinor());
      assertEquals(67, nuggets.unitMoney().minorUnits());
      assertEquals(1850, value(manifest, "iron_pickaxe", fx).unitMoney().minorUnits());
      var cooked = value(manifest, "cooked_beef", fx);
      assertEquals(Rational.of(1372).divide(Rational.of(3)), cooked.exactUnitMinor());
      assertEquals(457, cooked.unitMoney().minorUnits());
      assertEquals(
          10, value(manifest, "glass", fx).unitMoney().minorUnits()); // no general fuel/labour fee
      assertTrue(
          cooked.explanation().steps().stream()
              .anyMatch(
                  s ->
                      s.kind().equals("PROCESSING_VALUE")
                          && s.processingValue().compareTo(Rational.ZERO) > 0));
      // Named calibrated counterparts stay canonical even when an intact-ore/other path costs less.
      var mixed =
          new RecipeGraph.Node(
              "fixture:mixed",
              "minecraft:smelting",
              "minecraft:iron_ingot",
              1,
              List.of(
                  new RecipeGraph.Ingredient(
                      List.of("#fixture:mixed"),
                      List.of("minecraft:raw_iron", "minecraft:stone"))));
      var mixedGraph = graph(List.of(mixed));
      var config =
          new ValuationConfig.Config(
              List.of(),
              List.of(new ValuationConfig.Entry("minecraft:stone", EconomyCurrency.CAD, "5", 1)));
      assertEquals(
          600,
          value(
                  ValuationData.prepare(inputs(mixedGraph), config, List.of(), mixedGraph),
                  "iron_ingot",
                  fx)
              .unitMoney()
              .minorUnits());
    } finally {
      store.close();
    }
  }

  @Test
  void pathsStayContextCorrectTiesStableAndLimitsNeverReturnPartialMinimums() throws Exception {
    var store = new SqliteStorage();
    store.initialize(path.toRealPath());
    try {
      var fx = fx(store);
      var nodes =
          List.of(
              node("stone_from_glass", "stone", 1, "glass"),
              node("glass_from_stone", "glass", 1, "stone"),
              node("stone_from_dirt", "stone", 1, "dirt"),
              node("granite", "granite", 1, "stone", "glass"),
              node("a_tie", "polished_granite", 1, "granite"),
              node("z_tie", "polished_granite", 1, "granite"));
      var result = value(manifest(graph(nodes)), "polished_granite", fx);
      assertEquals(RecipeValues.Status.READY, result.status());
      assertEquals(20, result.unitMoney().minorUnits());
      assertEquals("fixture:a_tie", result.explanation().steps().getFirst().recipe());
      var shuffled = new ArrayList<>(nodes);
      Collections.reverse(shuffled);
      assertEquals(result, value(manifest(graph(shuffled)), "polished_granite", fx));
      var cyclic =
          manifest(
              graph(
                  List.of(node("stone", "stone", 1, "glass"), node("glass", "glass", 1, "stone"))));
      assertEquals(RecipeValues.Status.CYCLE, value(cyclic, "stone", fx).status());
      var ids =
          ValuationData.catalog().entries().values().stream()
              .filter(
                  e ->
                      e.classification() == ValuationData.Classification.DERIVED
                          && !RecipeGraph.PROCESSING_INPUTS.containsKey(e.item()))
              .map(ValuationData.CatalogEntry::item)
              .limit(35)
              .toList();
      var deep = new ArrayList<RecipeGraph.Node>();
      for (int i = 0; i < ids.size(); i++)
        deep.add(
            new RecipeGraph.Node(
                "fixture:z_depth_" + i,
                "minecraft:crafting_shapeless",
                ids.get(i),
                1,
                List.of(
                    RecipeGraph.Ingredient.item(
                        i + 1 < ids.size() ? ids.get(i + 1) : "minecraft:dirt"))));
      deep.add(
          new RecipeGraph.Node(
              "fixture:a_cheap",
              "minecraft:crafting_shapeless",
              ids.getFirst(),
              1,
              List.of(ingredient("dirt"))));
      var limited =
          RecipeValues.evaluate(
              manifest(graph(deep)), ids.getFirst(), "{}", 1, EconomyCurrency.CAD, fx);
      assertEquals(RecipeValues.Status.LIMIT_EXCEEDED, limited.status());
      assertNull(limited.unitMoney());
      var all = new ArrayList<>(ValuationData.catalog().entries().keySet());
      var broad = new RecipeGraph.Ingredient(List.of("#fixture:all"), all);
      var workGraph =
          graph(
              List.of(
                  node("a_cheap", "stone", 1, "dirt"),
                  new RecipeGraph.Node(
                      "fixture:z_work",
                      "minecraft:crafting_shapeless",
                      "minecraft:stone",
                      1,
                      Collections.nCopies(9, broad))));
      assertEquals(
          RecipeValues.Status.LIMIT_EXCEEDED, value(manifest(workGraph), "stone", fx).status());
      var emptyTag = new RecipeGraph.Ingredient(List.of("#fixture:empty"), List.of());
      var unsupported =
          manifest(
              graph(
                  List.of(
                      new RecipeGraph.Node(
                          "fixture:empty",
                          "minecraft:crafting_shaped",
                          "minecraft:stone",
                          1,
                          List.of(new RecipeGraph.Ingredient(List.of(), List.of()), emptyTag)))));
      assertEquals(RecipeValues.Status.MISSING_INPUT, value(unsupported, "stone", fx).status());
    } finally {
      store.close();
    }
  }

  @Test
  void retainedGraphContentAndRootCacheStaySnapshotScopedWithoutReinterpretingV1()
      throws Exception {
    var store = new SqliteStorage();
    store.initialize(path.toRealPath());
    UUID legacyId, firstId;
    RecipeValues.Result before;
    try {
      var fx = fx(store);
      var gate = new ValuationStore.Gate();
      var cache = new ValuationCache();
      var firstGraph = graph(List.of(node("planks", "oak_planks", 4, "oak_log")));
      var legacy =
          ValuationData.prepare(inputs(firstGraph), ValuationConfig.Config.empty(), List.of());
      String legacyPayload = ValuationData.encode(legacy);
      assertFalse(legacyPayload.contains("\"recipes\":"));
      var old = ValuationStore.publish(store, legacyPayload, 1, gate, 0);
      legacyId = old.id();
      assertEquals(
          RecipeValues.Status.MISSING_INPUT,
          RecipeValues.evaluate(legacy, "minecraft:oak_planks", "{}", 1, EconomyCurrency.CAD, fx)
              .status());
      var first = manifest(firstGraph);
      var saved = ValuationStore.publish(store, ValuationData.encode(first), 2, gate, 0);
      firstId = saved.id();
      cache.activate(saved, first);
      before = cache.evaluate(saved, "minecraft:oak_planks", "{}", 3, EconomyCurrency.CAD, fx);
      assertEquals(150, before.totalMoney().minorUnits());
      assertSame(
          before,
          cache.cached(saved.id(), "minecraft:oak_planks", "{}", 3, EconomyCurrency.CAD, fx.id()));
      var secondGraph = graph(List.of(node("planks", "oak_planks", 2, "oak_log")));
      var second = manifest(secondGraph);
      var newer = ValuationStore.publish(store, ValuationData.encode(second), 3, gate, 0);
      cache.activate(newer, second);
      assertEquals(
          300,
          cache
              .evaluate(newer, "minecraft:oak_planks", "{}", 3, EconomyCurrency.CAD, fx)
              .totalMoney()
              .minorUnits());
      assertEquals(
          before, cache.evaluate(saved, "minecraft:oak_planks", "{}", 3, EconomyCurrency.CAD, fx));
      var modNode =
          new RecipeGraph.Node(
              "fixture:mod",
              "minecraft:crafting_shapeless",
              "fixture:unregistered",
              1,
              List.of(ingredient("dirt")));
      assertEquals(
          RecipeValues.Status.MOD_UNREGISTERED,
          RecipeValues.evaluate(
                  manifest(graph(List.of(modNode))),
                  "fixture:unregistered",
                  "{}",
                  1,
                  EconomyCurrency.CAD,
                  fx)
              .status());
    } finally {
      store.close();
    }
    var reopened = new SqliteStorage();
    reopened.initialize(path.toRealPath());
    try {
      var old = ValuationData.decode(ValuationStore.load(reopened, legacyId).payload());
      assertEquals(1, old.version());
      assertNull(old.recipes());
      var saved = ValuationData.decode(ValuationStore.load(reopened, firstId).payload());
      var fx = reopened.transaction(tx -> FxService.snapshot(tx::query, before.fxPublication()));
      assertEquals(
          before,
          RecipeValues.evaluate(saved, "minecraft:oak_planks", "{}", 3, EconomyCurrency.CAD, fx));
    } finally {
      reopened.close();
    }
  }
}
