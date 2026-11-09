package ca.emilybelnavis.mcmods.economy.valuation;

import com.google.gson.*;
import java.util.*;

/**
 * Immutable, callback-free contribution data. Lifecycle admission authenticates provider identity
 * separately.
 */
public final class ValuationContributions {
  public static final String ORDINARY = "economy:ordinary-recipes/v1";
  public static final String MATERIALS = "economy:component-values/v1";

  private ValuationContributions() {}

  public record Selector(String item, String canonicalComponents, ValuationConfig.Entry value) {
    public Selector {
      ValuationData.itemId(item);
      Objects.requireNonNull(value);
      if (!item.equals(value.item()))
        throw new IllegalArgumentException("Selector source identity");
      canonicalComponents = ValuationContributions.canonicalComponents(canonicalComponents);
    }
  }

  public record Provider(
      String providerId,
      String revision,
      Set<String> claimedItems,
      List<ValuationConfig.Entry> bases,
      List<Selector> exactSelectors,
      Set<String> derivationOptIns,
      Map<String, VariantGraph.Rule> variantRules,
      Set<String> trustedAdapterVersions) {
    public Provider {
      // Reuse the original namespace, source and revision validation without changing its
      // historical codec.
      var legacy = new ValuationData.Contribution(providerId, revision, claimedItems, bases);
      claimedItems = legacy.claimedItems();
      bases = legacy.bases();
      derivationOptIns =
          Collections.unmodifiableSortedSet(
              new TreeSet<>(Objects.requireNonNull(derivationOptIns)));
      variantRules =
          Collections.unmodifiableSortedMap(new TreeMap<>(Objects.requireNonNull(variantRules)));
      trustedAdapterVersions =
          Collections.unmodifiableSortedSet(
              new TreeSet<>(Objects.requireNonNull(trustedAdapterVersions)));
      if (!Set.of(ORDINARY, MATERIALS).containsAll(trustedAdapterVersions))
        throw new IllegalArgumentException("Unsupported trusted adapter version");
      if (!claimedItems.containsAll(derivationOptIns)
          || !claimedItems.containsAll(variantRules.keySet()))
        throw new IllegalArgumentException("Unclaimed derivation identity");
      if (!derivationOptIns.isEmpty() && !trustedAdapterVersions.contains(ORDINARY))
        throw new IllegalArgumentException("Ordinary adapter opt-in required");
      if (!variantRules.isEmpty() && !trustedAdapterVersions.contains(MATERIALS))
        throw new IllegalArgumentException("Material adapter opt-in required");
      for (var rule : variantRules.values())
        if (!rule.kind().equals("SUM"))
          throw new IllegalArgumentException("Only detached SUM material rules are public");
      var selectors = new TreeMap<String, Selector>();
      for (var selector : Objects.requireNonNull(exactSelectors)) {
        if (!claimedItems.contains(selector.item()))
          throw new IllegalArgumentException("Unclaimed exact selector");
        if (selectors.putIfAbsent(selector.item() + "\n" + selector.canonicalComponents(), selector)
            != null) throw new IllegalArgumentException("Duplicate exact selector");
      }
      exactSelectors = List.copyOf(selectors.values());
      if ((long) bases.size()
              + exactSelectors.size()
              + derivationOptIns.size()
              + variantRules.size()
          > ValuationConfig.MAX_ENTRIES) throw new IllegalArgumentException("Provider entry limit");
    }
  }

  public static String canonicalComponents(String text) {
    String result =
        canonical(ValuationConfig.object(ValuationConfig.parseIdentity(text))).toString();
    if (ValuationConfig.bytes(result) > 65_536)
      throw new IllegalArgumentException("Canonical identity byte limit");
    return result;
  }

  private static JsonElement canonical(JsonElement value) {
    if (value.isJsonObject()) {
      var result = new JsonObject();
      new TreeMap<>(value.getAsJsonObject().asMap()).forEach((k, v) -> result.add(k, canonical(v)));
      return result;
    }
    if (value.isJsonArray()) {
      var result = new JsonArray();
      value.getAsJsonArray().forEach(v -> result.add(canonical(v)));
      return result;
    }
    return value.deepCopy();
  }

  public static Provider owner(ValuationData.Manifest manifest, String item) {
    return manifest.providers().stream()
        .filter(p -> p.claimedItems().contains(item))
        .findFirst()
        .orElse(null);
  }

  static Selector selector(ValuationData.Manifest manifest, String item, String components) {
    var provider = owner(manifest, item);
    if (provider == null) return null;
    String canonical = canonicalComponents(components);
    return provider.exactSelectors().stream()
        .filter(s -> s.item().equals(item) && s.canonicalComponents().equals(canonical))
        .findFirst()
        .orElse(null);
  }

  static VariantGraph.Graph extend(VariantGraph.Graph graph, List<Provider> providers) {
    var items = new TreeMap<>(graph.items());
    var rules = new TreeMap<>(graph.rules());
    for (var provider : providers) {
      for (String id : provider.claimedItems())
        items.put(id, new VariantGraph.Item(id, 0, 64, 0, Set.of()));
      rules.putAll(provider.variantRules());
    }
    return new VariantGraph.Graph(
        graph.version(),
        graph.adapter(),
        graph.rulesHash(),
        items,
        graph.enchantments(),
        rules,
        graph.flowers(),
        graph.fireworkColors());
  }

  static void validate(
      ValuationData.Inputs inputs,
      List<Provider> providers,
      List<ValuationData.Contribution> legacy) {
    if (providers.size() > ValuationConfig.MAX_ENTRIES)
      throw new IllegalArgumentException("Provider count limit");
    var names = new HashSet<String>();
    var claims = new HashSet<String>();
    int entries = 0;
    for (var old : legacy) {
      names.add(old.providerId());
      claims.addAll(old.claimedItems());
    }
    for (var p : providers) {
      if (!names.add(p.providerId()))
        throw new IllegalArgumentException("Duplicate provider identity");
      entries =
          Math.addExact(
              entries,
              p.bases().size()
                  + p.exactSelectors().size()
                  + p.derivationOptIns().size()
                  + p.variantRules().size());
      if (entries > ValuationConfig.MAX_ENTRIES)
        throw new IllegalArgumentException("Aggregate contribution entry limit");
      for (String item : p.claimedItems()) {
        if (!inputs.items().contains(item))
          throw new IllegalArgumentException("Unregistered provider identity");
        if (!claims.add(item))
          throw new IllegalArgumentException("Conflicting provider claim: " + item);
      }
      for (var rule : p.variantRules().values())
        for (var term : rule.materials())
          if (!inputs.items().contains(term.item()))
            throw new IllegalArgumentException("Unregistered material identity");
    }
    if (claims.size() > ValuationConfig.MAX_ENTRIES)
      throw new IllegalArgumentException("Aggregate provider claim limit");
  }
}
