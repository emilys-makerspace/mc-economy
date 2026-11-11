package ca.emilybelnavis.mcmods.economy.valuation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.Ingredient;

/** Server-thread snapshot of the pinned vanilla lists; no brewing or custom predicates execute. */
final class VanillaBrewingCapture {
  private static final int MAX_BYTES = 32 * 1024 * 1024;
  private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
  // Actual pinned Potions.java registrations, not translation names or guessed potion states.
  private static final List<String> POTIONS =
      List.of(
          "water",
          "mundane",
          "thick",
          "awkward",
          "night_vision",
          "long_night_vision",
          "invisibility",
          "long_invisibility",
          "leaping",
          "long_leaping",
          "strong_leaping",
          "fire_resistance",
          "long_fire_resistance",
          "swiftness",
          "long_swiftness",
          "strong_swiftness",
          "slowness",
          "long_slowness",
          "strong_slowness",
          "turtle_master",
          "long_turtle_master",
          "strong_turtle_master",
          "water_breathing",
          "long_water_breathing",
          "healing",
          "strong_healing",
          "harming",
          "strong_harming",
          "poison",
          "long_poison",
          "strong_poison",
          "regeneration",
          "long_regeneration",
          "strong_regeneration",
          "strength",
          "long_strength",
          "strong_strength",
          "weakness",
          "long_weakness",
          "luck",
          "slow_falling",
          "long_slow_falling",
          "wind_charged",
          "weaving",
          "oozing",
          "infested");

  private record Tuple(String from, String ingredient, String to) {}

  private record Lists(
      List<String> containers,
      List<Tuple> potions,
      List<Tuple> forms,
      List<String> customClasses) {}

  private VanillaBrewingCapture() {}

  static BrewingGraph.Graph capture(MinecraftServer server) {
    if (!server.isSameThread())
      throw new IllegalStateException("Brewing capture requires server thread");
    return capture(server.potionBrewing(), server.getWorldData().enabledFeatures());
  }

  static BrewingGraph.Graph capture(PotionBrewing actual, FeatureFlagSet features) {
    // addVanillaMixes is reviewed static registration data. Unlike bootstrap, it posts no mod
    // event.
    var builder = new PotionBrewing.Builder(features);
    PotionBrewing.addVanillaMixes(builder);
    var expected = builder.build();
    var exclusions = new TreeMap<String, String>();
    Lists baseline = lists(expected);
    Lists captured = lists(actual);
    if (!captured.containers().equals(baseline.containers()))
      exclusions.put("containers", "NONVANILLA_CONTAINER_LIST");
    if (!captured.potions().equals(baseline.potions()))
      exclusions.put("potion_mixes", "NONVANILLA_ORDERED_POTION_MIXES");
    if (!captured.forms().equals(baseline.forms()))
      exclusions.put("form_mixes", "NONVANILLA_ORDERED_FORM_MIXES");
    if (!captured.customClasses().isEmpty())
      exclusions.put(
          "custom_registry",
          "CUSTOM_RECIPES_REQUIRE_ADAPTER:count=" + captured.customClasses().size());
    var definitions = definitions(exclusions);
    var containers =
        captured.containers().stream()
            .filter(BrewingGraph.FORMS::contains)
            .distinct()
            .sorted()
            .toList();
    var mixes = new TreeMap<String, BrewingGraph.Mix>();
    var knownPotions = new HashSet<>(baseline.potions());
    for (Tuple tuple : captured.potions()) {
      if (!knownPotions.contains(tuple)
          || !definitions.containsKey(tuple.from())
          || !definitions.containsKey(tuple.to())) continue;
      for (String form : containers)
        add(
            mixes,
            "potion",
            new BrewingGraph.State(form, tuple.from()),
            new BrewingGraph.State(form, tuple.to()),
            tuple.ingredient());
    }
    var knownForms = new HashSet<>(baseline.forms());
    for (Tuple tuple : captured.forms()) {
      if (!knownForms.contains(tuple)
          || !containers.contains(tuple.from())
          || !containers.contains(tuple.to())) continue;
      for (String potion : definitions.keySet())
        add(
            mixes,
            "form",
            new BrewingGraph.State(tuple.from(), potion),
            new BrewingGraph.State(tuple.to(), potion),
            tuple.ingredient());
    }
    String listText = JSON.toJson(captured);
    if (listText.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IllegalArgumentException("BREWING_CAPTURE_BYTE_LIMIT");
    var graph =
        new BrewingGraph.Graph(
            1,
            BrewingGraph.ADAPTER,
            BrewingGraph.RULES_HASH,
            exclusions.isEmpty(),
            definitions,
            containers,
            List.copyOf(mixes.values()),
            exclusions,
            ValuationData.hash(listText));
    if (BrewingGraph.encode(graph).getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IllegalArgumentException("BREWING_GRAPH_BYTE_LIMIT");
    return graph;
  }

  private static Lists lists(PotionBrewing brewing) {
    boolean exactType = brewing.getClass() == PotionBrewing.class;
    if (brewing.containers.size() > 4_096
        || brewing.potionMixes.size() > RecipeGraph.MAX_RECIPES
        || brewing.containerMixes.size() > RecipeGraph.MAX_RECIPES
        || (exactType && brewing.getRecipes().size() > 4_096))
      throw new IllegalArgumentException("BREWING_CAPTURE_COUNT_LIMIT");
    var containers = brewing.containers.stream().map(VanillaBrewingCapture::ingredient).toList();
    var potions =
        brewing.potionMixes.stream()
            .map(m -> new Tuple(key(m.from()), ingredient(m.ingredient()), key(m.to())))
            .toList();
    var forms =
        brewing.containerMixes.stream()
            .map(m -> new Tuple(key(m.from()), ingredient(m.ingredient()), key(m.to())))
            .toList();
    // Class/count diagnostics only: do not call any method on an IBrewingRecipe.
    var custom =
        exactType
            ? brewing.getRecipes().stream().map(recipe -> recipe.getClass().getName()).toList()
            : List.of("!NONVANILLA_BREWING_CLASS:" + brewing.getClass().getName());
    return new Lists(containers, potions, forms, custom);
  }

  private static String ingredient(Ingredient ingredient) {
    if (ingredient.isCustom()) return "!CUSTOM_INGREDIENT";
    var values = ingredient.getValues();
    if (values.length != 1 || !(values[0] instanceof Ingredient.ItemValue value))
      return "!NON_ITEM_INGREDIENT";
    var stack = value.item();
    if (stack.isEmpty() || stack.getCount() != 1 || !stack.isComponentsPatchEmpty())
      return "!PATCHED_INGREDIENT";
    var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
    if (id == null || !id.getNamespace().equals("minecraft")) return "!NONVANILLA_INGREDIENT";
    return id.toString();
  }

  private static String key(Holder<?> holder) {
    // Only registry references are captured; a mod's arbitrary Holder implementation is not
    // invoked.
    if (holder == null || holder.getClass() != Holder.Reference.class)
      return "!NON_REGISTRY_HOLDER";
    return holder.unwrapKey().map(k -> k.location().toString()).orElse("!UNKEYED_HOLDER");
  }

  private static Map<String, BrewingGraph.Potion> definitions(Map<String, String> exclusions) {
    var result = new TreeMap<String, BrewingGraph.Potion>();
    for (String name : POTIONS) {
      String id = "minecraft:" + name;
      Potion potion =
          BuiltInRegistries.POTION.get(ResourceLocation.fromNamespaceAndPath("minecraft", name));
      if (potion == null
          || potion.getClass() != Potion.class
          || !ResourceLocation.fromNamespaceAndPath("minecraft", name)
              .equals(BuiltInRegistries.POTION.getKey(potion))) {
        exclusions.put(id, "UNSUPPORTED_POTION_DEFINITION");
        continue;
      }
      var effects = new ArrayList<BrewingGraph.Effect>();
      boolean supported = potion.getEffects().size() <= 32;
      if (supported)
        for (var effect : potion.getEffects()) {
          if (effect.getClass() != MobEffectInstance.class
              || effect.hiddenEffect != null
              || effect.getCures().size() > 3) {
            supported = false;
            break;
          }
          String effectId = key(effect.getEffect());
          var cures = effect.getCures().stream().map(cure -> cure.name()).sorted().toList();
          if (!effectId.startsWith("minecraft:")
              || effect.getAmplifier() < 0
              || effect.getAmplifier() > 255
              || effect.getDuration() < -1
              || !Set.of("milk", "honey", "protected_by_totem").containsAll(cures)) {
            supported = false;
            break;
          }
          effects.add(
              new BrewingGraph.Effect(
                  effectId,
                  effect.getAmplifier(),
                  effect.getDuration(),
                  effect.isAmbient(),
                  effect.isVisible(),
                  effect.showIcon(),
                  cures));
        }
      if (supported) result.put(id, new BrewingGraph.Potion(id, effects));
      else exclusions.put(id, "UNSUPPORTED_POTION_EFFECT_STATE");
    }
    return result;
  }

  private static void add(
      Map<String, BrewingGraph.Mix> target,
      String family,
      BrewingGraph.State from,
      BrewingGraph.State to,
      String ingredient) {
    String id =
        "economy:brew/"
            + family
            + "/"
            + ValuationData.hash(from.key() + "|" + ingredient + "|" + to.key());
    String remainder =
        ingredient.equals("minecraft:dragon_breath") ? "GLASS_BOTTLE_NO_SALVAGE" : "NONE";
    target.putIfAbsent(id, new BrewingGraph.Mix(id, from, to, ingredient, remainder));
    if (target.size() > RecipeGraph.MAX_RECIPES)
      throw new IllegalArgumentException("BREWING_MIX_LIMIT");
  }
}
