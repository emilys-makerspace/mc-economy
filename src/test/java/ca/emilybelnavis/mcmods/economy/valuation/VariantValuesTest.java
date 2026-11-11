package ca.emilybelnavis.mcmods.economy.valuation;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import com.google.gson.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VariantValuesTest {
  @TempDir Path path;

  private static RecipeGraph.Node node(String id, String output, int count, String... inputs) {
    return new RecipeGraph.Node(
        "fixture:" + id,
        "minecraft:crafting_shapeless",
        "minecraft:" + output,
        count,
        Arrays.stream(inputs).map(s -> RecipeGraph.Ingredient.item("minecraft:" + s)).toList());
  }

  private static RecipeGraph.Graph recipes() {
    return new RecipeGraph.Graph(
        List.of(
            node("planks", "oak_planks", 4, "oak_log"),
            new RecipeGraph.Node(
                "fixture:iron",
                "minecraft:smelting",
                "minecraft:iron_ingot",
                1,
                List.of(RecipeGraph.Ingredient.item("minecraft:raw_iron"))),
            node("sword", "iron_sword", 1, "iron_ingot", "iron_ingot", "stick"),
            node("bucket", "bucket", 1, "iron_ingot", "iron_ingot", "iron_ingot"),
            node("glass", "glass", 1, "sand"),
            node("bottle", "glass_bottle", 3, "glass", "glass", "glass"),
            node("powder", "blaze_powder", 2, "blaze_rod"),
            node("paper", "paper", 3, "sugar_cane", "sugar_cane", "sugar_cane"),
            node("book", "book", 1, "paper", "paper", "paper", "leather")),
        Map.of(),
        Map.of());
  }

  private static BrewingGraph.Graph brewing() {
    var potions = new TreeMap<String, BrewingGraph.Potion>();
    for (String id : List.of("water", "awkward"))
      potions.put("minecraft:" + id, new BrewingGraph.Potion("minecraft:" + id, List.of()));
    return new BrewingGraph.Graph(
        1,
        BrewingGraph.ADAPTER,
        BrewingGraph.RULES_HASH,
        true,
        potions,
        new ArrayList<>(BrewingGraph.FORMS),
        List.of(
            new BrewingGraph.Mix(
                "fixture:wart",
                new BrewingGraph.State("minecraft:potion", "minecraft:water"),
                new BrewingGraph.State("minecraft:potion", "minecraft:awkward"),
                "minecraft:nether_wart",
                "NONE"),
            new BrewingGraph.Mix(
                "fixture:splash",
                new BrewingGraph.State("minecraft:potion", "minecraft:awkward"),
                new BrewingGraph.State("minecraft:splash_potion", "minecraft:awkward"),
                "minecraft:gunpowder",
                "NONE"),
            new BrewingGraph.Mix(
                "fixture:linger",
                new BrewingGraph.State("minecraft:splash_potion", "minecraft:awkward"),
                new BrewingGraph.State("minecraft:lingering_potion", "minecraft:awkward"),
                "minecraft:dragon_breath",
                "GLASS_BOTTLE_NO_SALVAGE")),
        Map.of(),
        ValuationData.hash("vanilla fixture"));
  }

  private static VariantGraph.Graph variants() {
    var items = new TreeMap<String, VariantGraph.Item>();
    for (String id : ValuationData.catalog().entries().keySet())
      items.put(id, new VariantGraph.Item(id, 0, 64, 0, Set.of()));
    items.put(
        "minecraft:iron_sword",
        new VariantGraph.Item(
            "minecraft:iron_sword",
            250,
            1,
            0,
            Set.of("minecraft:damage", "minecraft:max_damage", "minecraft:enchantments")));
    items.put(
        "minecraft:shulker_box",
        new VariantGraph.Item("minecraft:shulker_box", 0, 1, 27, Set.of("minecraft:container")));
    items.put(
        "minecraft:enchanted_book",
        new VariantGraph.Item(
            "minecraft:enchanted_book", 0, 1, 0, Set.of("minecraft:stored_enchantments")));
    var enchants = new TreeMap<String, VariantGraph.Enchant>();
    enchants.put(
        "minecraft:sharpness",
        new VariantGraph.Enchant(
            "minecraft:sharpness",
            5,
            Set.of("minecraft:iron_sword"),
            Set.of("minecraft:smite"),
            List.of("20", "45", "75", "110", "150"),
            false));
    enchants.put(
        "minecraft:smite",
        new VariantGraph.Enchant(
            "minecraft:smite",
            5,
            Set.of("minecraft:iron_sword"),
            Set.of("minecraft:sharpness"),
            List.of("15", "30", "50", "75", "110"),
            false));
    enchants.put(
        "minecraft:unbreaking",
        new VariantGraph.Enchant(
            "minecraft:unbreaking",
            3,
            Set.of("minecraft:iron_sword"),
            Set.of(),
            List.of("50", "110", "200"),
            false));
    enchants.put(
        "minecraft:mending",
        new VariantGraph.Enchant(
            "minecraft:mending",
            1,
            Set.of("minecraft:iron_sword"),
            Set.of(),
            List.of("300"),
            false));
    enchants.put(
        "minecraft:vanishing_curse",
        new VariantGraph.Enchant(
            "minecraft:vanishing_curse",
            1,
            Set.of("minecraft:iron_sword"),
            Set.of(),
            List.of(),
            true));
    enchants.put(
        "minecraft:binding_curse",
        new VariantGraph.Enchant(
            "minecraft:binding_curse", 1, Set.of(), Set.of(), List.of(), true));
    return new VariantGraph.Graph(
        1,
        VariantGraph.ADAPTER,
        VariantGraph.RULES_HASH,
        items,
        enchants,
        VariantDefaults.rules(),
        Map.of(),
        Map.of());
  }

  private static ValuationConfig.Config config() {
    return new ValuationConfig.Config(
        List.of(),
        List.of(
            new ValuationConfig.Entry("minecraft:shulker_box", EconomyCurrency.CAD, "80", 1),
            new ValuationConfig.Entry("minecraft:dirt", EconomyCurrency.CAD, "0.01", 3)));
  }

  private static ValuationData.Manifest manifest(ValuationConfig.Config config) {
    var recipes = recipes();
    var brewing = brewing();
    var variants = variants();
    var inputs =
        new ValuationData.Inputs(
            ValuationData.catalog().entries().keySet(),
            ValuationData.hash(recipes.hash() + brewing.hash() + variants.hash()),
            ValuationData.hash("tags"),
            ValuationData.hash("features"));
    return ValuationData.prepare(inputs, config, List.of(), recipes, brewing, variants);
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

  private static String sword() {
    return "{\"minecraft:damage\":125,\"minecraft:enchantments\":{\"levels\":{\"minecraft:sharpness\":3,\"minecraft:unbreaking\":2,\"minecraft:vanishing_curse\":1}}}";
  }

  private static RecipeValues.Result value(
      ValuationData.Manifest manifest, String item, String components, EffectiveFxSnapshot fx) {
    return RecipeValues.evaluate(
        manifest, "minecraft:" + item, components, 1, EconomyCurrency.CAD, fx);
  }

  private static JsonObject stack(String id, int count, String components) {
    var item = new JsonObject();
    item.addProperty("id", "minecraft:" + id);
    item.addProperty("count", count);
    item.add("components", JsonParser.parseString(components));
    return item;
  }

  private static String container(JsonObject... items) {
    var entries = new JsonArray();
    for (int i = 0; i < items.length; i++) {
      var entry = new JsonObject();
      entry.addProperty("slot", i);
      entry.add("item", items[i]);
      entries.add(entry);
    }
    var object = new JsonObject();
    object.add("minecraft:container", entries);
    return object.toString();
  }

  private static String nested() {
    String inner =
        container(
            stack("iron_sword", 1, sword()),
            stack("lingering_potion", 1, BrewingGraph.components("minecraft:awkward")),
            stack("dirt", 3, "{}"));
    return container(stack("shulker_box", 1, inner), stack("diamond", 2, "{}"));
  }

  @Test
  void compatiblePremiumsCurseThenDamageAndStoredBooksPreserveTheirProperties() throws Exception {
    var store = new SqliteStorage();
    store.initialize(path.toRealPath());
    try {
      var fx = fx(store);
      var manifest = manifest(config());
      var result = value(manifest, "iron_sword", sword(), fx);
      assertEquals(RecipeValues.Status.READY, result.status(), result.reason());
      assertEquals(Rational.of("7396.875"), result.exactUnitMinor());
      assertEquals(7397, result.unitMoney().minorUnits());
      var named = JsonParser.parseString(sword()).getAsJsonObject();
      named.addProperty("minecraft:custom_name", "Same price");
      assertEquals(
          result.unitMoney(), value(manifest, "iron_sword", named.toString(), fx).unitMoney());
      assertNotEquals(
          result.explanation().rootHash(),
          value(manifest, "iron_sword", named.toString(), fx).explanation().rootHash());
      assertEquals(
          1,
          value(manifest, "iron_sword", "{\"minecraft:damage\":250}", fx).unitMoney().minorUnits());
      for (String bad :
          List.of(
              "{\"minecraft:max_damage\":251}",
              "{\"minecraft:damage\":251}",
              "{\"minecraft:enchantments\":{\"levels\":{\"minecraft:sharpness\":6}}}",
              "{\"minecraft:enchantments\":{\"levels\":{\"minecraft:sharpness\":1,\"minecraft:smite\":1}}}",
              "{\"minecraft:custom_data\":{}}")) {
        var rejected = value(manifest, "iron_sword", bad, fx);
        assertNotEquals(RecipeValues.Status.READY, rejected.status());
        assertNull(rejected.unitMoney());
      }
      var book =
          value(
              manifest,
              "enchanted_book",
              "{\"minecraft:stored_enchantments\":{\"levels\":{\"minecraft:mending\":1,\"minecraft:binding_curse\":1,\"minecraft:vanishing_curse\":1}}}",
              fx);
      assertEquals(15040, book.unitMoney().minorUnits());
      var adjusted =
          new ValuationConfig.Config(
              config().bases(),
              config().overrides(),
              Map.of("minecraft:sharpness", List.of("20", "45", "80", "110", "150")));
      assertEquals(
          7584, value(manifest(adjusted), "iron_sword", sword(), fx).unitMoney().minorUnits());
    } finally {
      store.close();
    }
  }

  @Test
  void nestedContentsUsePublishedChildUnitsAndRefuseUnknownOrExcessDepth() throws Exception {
    var store = new SqliteStorage();
    store.initialize(path.toRealPath());
    try {
      var fx = fx(store);
      var manifest = manifest(config());
      var result = value(manifest, "shulker_box", nested(), fx);
      assertEquals(RecipeValues.Status.READY, result.status(), result.reason());
      assertEquals(Rational.of(84317), result.exactUnitMinor());
      assertEquals(84317, result.unitMoney().minorUnits());
      assertEquals(
          168634,
          RecipeValues.evaluate(
                  manifest, "minecraft:shulker_box", nested(), 2, EconomyCurrency.CAD, fx)
              .totalMoney()
              .minorUnits());
      var unknown =
          container(
              stack("diamond", 1, "{}"), stack("iron_sword", 1, "{\"minecraft:custom_data\":{}}"));
      assertNull(value(manifest, "shulker_box", unknown, fx).unitMoney());
      String chain = "{}";
      for (int n = 0; n < 8; n++) chain = container(stack("shulker_box", 1, chain));
      // Root plus the eight contained container shells exceed the frozen eight levels.
      assertEquals(
          RecipeValues.Status.LIMIT_EXCEEDED, value(manifest, "shulker_box", chain, fx).status());
      String allowed = "{}";
      for (int n = 0; n < 7; n++) allowed = container(stack("shulker_box", 1, allowed));
      assertEquals(RecipeValues.Status.READY, value(manifest, "shulker_box", allowed, fx).status());
      // Actual sherd materials remain priceable when the default brick recipe is absent.
      var patterned =
          value(
              manifest,
              "decorated_pot",
              "{\"minecraft:pot_decorations\":[\"minecraft:angler_pottery_sherd\",\"minecraft:angler_pottery_sherd\",\"minecraft:angler_pottery_sherd\",\"minecraft:angler_pottery_sherd\"]}",
              fx);
      assertEquals(RecipeValues.Status.READY, patterned.status(), patterned.reason());
      assertEquals(
          4 * value(manifest, "angler_pottery_sherd", "{}", fx).unitMoney().minorUnits(),
          patterned.unitMoney().minorUnits());
      assertEquals(
          1800,
          value(manifest, "water_bucket", "{}", fx).unitMoney().minorUnits()); // 3 iron, free water
      assertEquals(2100, value(manifest, "lava_bucket", "{}", fx).unitMoney().minorUnits());
    } finally {
      store.close();
    }
  }

  @Test
  void v4RetainsExactIdentityAndPremiumsWhileOlderVersionsStayUnchanged() throws Exception {
    var store = new SqliteStorage();
    store.initialize(path.toRealPath());
    UUID oldId, newId;
    RecipeValues.Result original;
    try {
      var fx = fx(store);
      var current = manifest(config());
      var gate = new ValuationStore.Gate();
      var old = ValuationData.prepare(current.inputs(), config(), List.of(), recipes(), brewing());
      String oldJson = ValuationData.encode(old);
      assertFalse(oldJson.contains("\"variants\":"));
      assertFalse(oldJson.contains("\"enchantmentPremiums\":"));
      var savedOld = ValuationStore.publish(store, oldJson, 1, gate, 0);
      oldId = savedOld.id();
      assertEquals(
          RecipeValues.Status.UNSUPPORTED_IDENTITY, value(old, "iron_sword", sword(), fx).status());
      var saved = ValuationStore.publish(store, ValuationData.encode(current), 2, gate, 0);
      newId = saved.id();
      var cache = new ValuationCache();
      cache.activate(saved, current);
      original =
          cache.evaluate(saved, "minecraft:shulker_box", nested(), 1, EconomyCurrency.CAD, fx);
      var changedConfig =
          new ValuationConfig.Config(
              List.of(),
              List.of(
                  new ValuationConfig.Entry("minecraft:shulker_box", EconomyCurrency.CAD, "80", 1),
                  new ValuationConfig.Entry("minecraft:dirt", EconomyCurrency.CAD, "0.01", 3),
                  new ValuationConfig.Entry("minecraft:diamond", EconomyCurrency.CAD, "400", 1)));
      var changed = manifest(changedConfig);
      var newer = ValuationStore.publish(store, ValuationData.encode(changed), 3, gate, 0);
      cache.activate(newer, changed);
      assertEquals(
          104317,
          cache
              .evaluate(newer, "minecraft:shulker_box", nested(), 1, EconomyCurrency.CAD, fx)
              .unitMoney()
              .minorUnits());
      assertEquals(
          original,
          cache.evaluate(saved, "minecraft:shulker_box", nested(), 1, EconomyCurrency.CAD, fx));
    } finally {
      store.close();
    }
    var reopened = new SqliteStorage();
    reopened.initialize(path.toRealPath());
    try {
      var old = ValuationData.decode(ValuationStore.load(reopened, oldId).payload());
      assertEquals(3, old.version());
      assertNull(old.variants());
      var current = ValuationData.decode(ValuationStore.load(reopened, newId).payload());
      var fx = reopened.transaction(tx -> FxService.snapshot(tx::query, original.fxPublication()));
      assertEquals(original, value(current, "shulker_box", nested(), fx));
    } finally {
      reopened.close();
    }
  }
}
