package ca.emilybelnavis.mcmods.economy.valuation;

import com.google.gson.GsonBuilder;
import java.util.*;

/** Detached ordinary-recipe data only. Version one freezes capture, arithmetic and search rules. */
public final class RecipeGraph {
  private RecipeGraph() {}

  public static final int MAX_RECIPES = 100_000, MAX_ALTERNATIVES = 4_096;
  public static final int MAX_DEPTH = 32, MAX_WORK = 10_000, MAX_EXPLANATION = 256;
  public static final Set<String> SERIALIZERS =
      Set.of(
          "minecraft:crafting_shaped",
          "minecraft:crafting_shapeless",
          "minecraft:stonecutting",
          "minecraft:smelting",
          "minecraft:blasting",
          "minecraft:smoking",
          "minecraft:campfire_cooking");
  public static final Map<String, String> PROCESSING_INPUTS =
      Map.ofEntries(
          Map.entry("minecraft:copper_ingot", "minecraft:raw_copper"),
          Map.entry("minecraft:iron_ingot", "minecraft:raw_iron"),
          Map.entry("minecraft:gold_ingot", "minecraft:raw_gold"),
          Map.entry("minecraft:cooked_porkchop", "minecraft:porkchop"),
          Map.entry("minecraft:cooked_chicken", "minecraft:chicken"),
          Map.entry("minecraft:cooked_beef", "minecraft:beef"),
          Map.entry("minecraft:cooked_salmon", "minecraft:salmon"),
          Map.entry("minecraft:cooked_rabbit", "minecraft:rabbit"),
          Map.entry("minecraft:cooked_cod", "minecraft:cod"),
          Map.entry("minecraft:cooked_mutton", "minecraft:mutton"),
          Map.entry("minecraft:baked_potato", "minecraft:potato"),
          Map.entry("minecraft:dried_kelp", "minecraft:kelp"),
          Map.entry("minecraft:popped_chorus_fruit", "minecraft:chorus_fruit"));
  public static final String RULES_HASH =
      ValuationData.hash(
          "ordinary-v1|exact-class-capture-v1|vanilla-remainders-v1|"
              + "stick=2*oak_planks/4|processing=canonical-raw-before-recipes;4/3|"
              + new TreeMap<>(PROCESSING_INPUTS)
              + "|depth=32|work=10000|explanation=256|path-local-cycles|root-cache-only");

  /** No selectors means a truly empty shaped slot; selectors with no items are unsatisfiable. */
  public record Ingredient(List<String> selectors, List<String> items) {
    public Ingredient {
      selectors = strings(selectors, MAX_ALTERNATIVES);
      for (String selector : selectors)
        ValuationData.itemId(selector.startsWith("#") ? selector.substring(1) : selector);
      items = strings(items, MAX_ALTERNATIVES);
      items.forEach(ValuationData::itemId);
      if (selectors.isEmpty() && !items.isEmpty())
        throw new IllegalArgumentException("Ingredient selectors required");
    }

    public static Ingredient item(String item) {
      return new Ingredient(List.of(item), List.of(item));
    }

    public boolean emptySlot() {
      return selectors.isEmpty();
    }
  }

  public record Node(
      String id,
      String serializer,
      String output,
      int count,
      List<Ingredient> ingredients,
      List<String> exclusions) {
    public Node {
      ValuationData.itemId(id);
      ValuationData.itemId(output);
      if (!SERIALIZERS.contains(serializer) || count < 1 || count > 1_000_000)
        throw new IllegalArgumentException("Ordinary recipe domain");
      ingredients = List.copyOf(ingredients);
      if (ingredients.isEmpty()
          || ingredients.size() > 9
          || ingredients.stream().allMatch(Ingredient::emptySlot))
        throw new IllegalArgumentException("Recipe consumed ingredient bound");
      ingredients.forEach(Objects::requireNonNull);
      exclusions = strings(exclusions, 40_000);
    }

    public Node(
        String id, String serializer, String output, int count, List<Ingredient> ingredients) {
      this(id, serializer, output, count, ingredients, List.of());
    }

    public boolean cooking() {
      return Set.of(
              "minecraft:smelting",
              "minecraft:blasting",
              "minecraft:smoking",
              "minecraft:campfire_cooking")
          .contains(serializer);
    }
  }

  public record Graph(
      int version,
      String rulesHash,
      List<Node> recipes,
      Map<String, String> exclusions,
      Map<String, List<String>> tags) {
    public Graph {
      if (version != 1 || !RULES_HASH.equals(rulesHash))
        throw new IllegalArgumentException("Recipe graph rules version");
      var ordered = new TreeMap<String, Node>();
      for (Node node : recipes)
        if (ordered.putIfAbsent(node.id(), node) != null)
          throw new IllegalArgumentException("Duplicate recipe identity");
      recipes = List.copyOf(ordered.values());
      var rejected = new TreeMap<String, String>();
      for (var entry : exclusions.entrySet()) {
        ValuationData.itemId(entry.getKey());
        if (entry.getValue() == null
            || entry.getValue().isBlank()
            || entry.getValue().length() > 256
            || ordered.containsKey(entry.getKey()))
          throw new IllegalArgumentException("Recipe exclusion identity");
        rejected.put(entry.getKey(), entry.getValue());
      }
      exclusions = Collections.unmodifiableSortedMap(rejected);
      if ((long) recipes.size() + exclusions.size() > MAX_RECIPES)
        throw new IllegalArgumentException("Recipe count limit");
      var captured = new TreeMap<String, List<String>>();
      for (var entry : tags.entrySet()) {
        ValuationData.itemId(entry.getKey());
        var members = strings(entry.getValue(), MAX_ALTERNATIVES);
        members.forEach(ValuationData::itemId);
        captured.put(entry.getKey(), members);
      }
      if (captured.size() > MAX_RECIPES) throw new IllegalArgumentException("Tag count limit");
      tags = Collections.unmodifiableSortedMap(captured);
    }

    public Graph(
        List<Node> recipes, Map<String, String> exclusions, Map<String, List<String>> tags) {
      this(1, RULES_HASH, recipes, exclusions, tags);
    }

    public String hash() {
      return ValuationData.hash(encode(this));
    }
  }

  public static String encode(Graph graph) {
    return new GsonBuilder().disableHtmlEscaping().create().toJson(graph);
  }

  public static void validate(Graph graph, ValuationData.Inputs inputs) {
    for (Node node : graph.recipes()) {
      if (!inputs.items().contains(node.output()))
        throw new IllegalArgumentException("Unknown recipe output");
      for (Ingredient ingredient : node.ingredients())
        if (!inputs.items().containsAll(ingredient.items()))
          throw new IllegalArgumentException("Unknown recipe input");
    }
    for (var members : graph.tags().values())
      if (!inputs.items().containsAll(members))
        throw new IllegalArgumentException("Unknown tag item");
  }

  private static List<String> strings(List<String> values, int limit) {
    if (values.size() > limit) throw new IllegalArgumentException("Recipe list limit");
    var sorted = new TreeSet<String>();
    for (String value : values) {
      if (value == null || value.length() > 512)
        throw new IllegalArgumentException("Recipe text bound");
      sorted.add(value);
    }
    return List.copyOf(sorted);
  }
}
