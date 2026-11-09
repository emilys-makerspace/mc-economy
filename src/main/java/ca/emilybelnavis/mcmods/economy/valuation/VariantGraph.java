package ca.emilybelnavis.mcmods.economy.valuation;

import com.google.gson.GsonBuilder;
import java.util.*;

/** Frozen vanilla defaults, enchantment membership and explicit variant material rules. */
public final class VariantGraph {
  private VariantGraph() {}

  public static final String ADAPTER = "economy:vanilla-variants/v1";
  public static final String RULES_HASH =
      ValuationData.hash(
          ADAPTER
              + "|compatible-premiums|curse-before-damage|"
              + "shell-children-published|container-depth8-visits4096|typed-buckets-bees-fireworks-stew|unknown-refuses");

  public record Item(String id, int maxDamage, int maxStack, int slots, Set<String> defaults) {
    public Item {
      ValuationData.itemId(id);
      if (maxDamage < 0
          || maxDamage > 10_000_000
          || maxStack < 1
          || maxStack > 99
          || slots < 0
          || slots > 256) throw new IllegalArgumentException("Variant item bounds");
      defaults = Collections.unmodifiableSortedSet(new TreeSet<>(defaults));
      defaults.forEach(ValuationData::itemId);
    }
  }

  public record Enchant(
      String id,
      int maxLevel,
      Set<String> supported,
      Set<String> exclusive,
      List<String> premiums,
      boolean curse) {
    public Enchant {
      ValuationData.itemId(id);
      if (maxLevel < 1 || maxLevel > 255 || premiums.size() > 255)
        throw new IllegalArgumentException("Enchantment levels");
      supported = Collections.unmodifiableSortedSet(new TreeSet<>(supported));
      exclusive = Collections.unmodifiableSortedSet(new TreeSet<>(exclusive));
      supported.forEach(ValuationData::itemId);
      exclusive.forEach(ValuationData::itemId);
      premiums = List.copyOf(premiums);
      for (String amount : premiums)
        new ValuationConfig.Entry(
            "economy:premium", ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD, amount, 1);
      if (curse && !Set.of("minecraft:binding_curse", "minecraft:vanishing_curse").contains(id))
        throw new IllegalArgumentException("Unknown curse");
    }
  }

  public record Term(String item, int numerator, int denominator) {
    public Term {
      ValuationData.itemId(item);
      if (numerator < 1 || numerator > 1_000_000 || denominator < 1 || denominator > 1_000_000)
        throw new IllegalArgumentException("Variant term");
    }

    public Term(String item) {
      this(item, 1, 1);
    }
  }

  /**
   * SUM adds exact materials/content; RECIPE allows ordinary shell derivation; TYPED requires
   * components.
   */
  public record Rule(String kind, List<Term> materials, String contentCad) {
    public Rule {
      if (!Set.of("SUM", "RECIPE", "TYPED").contains(kind))
        throw new IllegalArgumentException("Variant rule kind");
      materials = List.copyOf(materials);
      if (materials.size() > 9) throw new IllegalArgumentException("Variant materials");
      if (kind.equals("SUM") && materials.isEmpty() && contentCad == null)
        throw new IllegalArgumentException("Empty variant source");
      if (contentCad != null)
        new ValuationConfig.Entry(
            "economy:content",
            ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD,
            contentCad,
            1);
    }
  }

  public record StewEffect(String id, int duration) {
    public StewEffect {
      ValuationData.itemId(id);
      if (duration < 1 || duration > 1_000_000) throw new IllegalArgumentException("Stew duration");
    }
  }

  public record Graph(
      int version,
      String adapter,
      String rulesHash,
      Map<String, Item> items,
      Map<String, Enchant> enchantments,
      Map<String, Rule> rules,
      Map<String, List<StewEffect>> flowers,
      Map<Integer, String> fireworkColors) {
    public Graph {
      if (version != 1 || !ADAPTER.equals(adapter) || !RULES_HASH.equals(rulesHash))
        throw new IllegalArgumentException("Variant version");
      items = sorted(items);
      enchantments = sorted(enchantments);
      rules = sorted(rules);
      if (items.size() > 100_000 || enchantments.size() > 4_096 || rules.size() > 4_096)
        throw new IllegalArgumentException("Variant graph bounds");
      for (var e : items.entrySet())
        if (!e.getKey().equals(e.getValue().id()))
          throw new IllegalArgumentException("Variant item identity");
      for (var e : enchantments.entrySet())
        if (!e.getKey().equals(e.getValue().id()))
          throw new IllegalArgumentException("Enchantment identity");
      var captured = new TreeMap<String, List<StewEffect>>();
      flowers.forEach(
          (id, effects) -> {
            ValuationData.itemId(id);
            if (effects.size() > 16) throw new IllegalArgumentException("Stew effects");
            captured.put(id, List.copyOf(effects));
          });
      if (captured.size() > 4_096) throw new IllegalArgumentException("Flower bound");
      flowers = Collections.unmodifiableSortedMap(captured);
      fireworkColors = Collections.unmodifiableSortedMap(new TreeMap<>(fireworkColors));
      if (fireworkColors.size() > 16) throw new IllegalArgumentException("Vanilla dye bound");
    }

    public String hash() {
      return ValuationData.hash(new GsonBuilder().disableHtmlEscaping().create().toJson(this));
    }
  }

  public static Graph withPremiums(Graph graph, Map<String, List<String>> replacements) {
    var enchants = new TreeMap<>(graph.enchantments());
    for (var e : replacements.entrySet()) {
      var old = enchants.get(e.getKey());
      if (old == null || old.curse() || e.getValue().size() != old.premiums().size())
        throw new IllegalArgumentException("Unsupported premium override");
      enchants.put(
          e.getKey(),
          new Enchant(
              old.id(), old.maxLevel(), old.supported(), old.exclusive(), e.getValue(), false));
    }
    return new Graph(
        1,
        ADAPTER,
        RULES_HASH,
        graph.items(),
        enchants,
        graph.rules(),
        graph.flowers(),
        graph.fireworkColors());
  }

  public static void validate(Graph graph, ValuationData.Inputs inputs) {
    if (!inputs.items().containsAll(graph.items().keySet()))
      throw new IllegalArgumentException("Unknown variant item");
    for (var e : graph.enchantments().values())
      if (!inputs.items().containsAll(e.supported()))
        throw new IllegalArgumentException("Unknown enchantment member");
    for (var e : graph.rules().entrySet()) {
      if (!inputs.items().contains(e.getKey()))
        throw new IllegalArgumentException("Unknown variant rule");
      for (var term : e.getValue().materials())
        if (!inputs.items().contains(term.item()))
          throw new IllegalArgumentException("Unknown variant material");
    }
  }

  private static <V> Map<String, V> sorted(Map<String, V> values) {
    return Collections.unmodifiableSortedMap(new TreeMap<>(values));
  }
}
