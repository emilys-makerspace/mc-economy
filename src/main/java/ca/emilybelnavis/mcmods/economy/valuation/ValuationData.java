package ca.emilybelnavis.mcmods.economy.valuation;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/**
 * Immutable versioned manifests. Historical base-only encodings retain their original semantics.
 */
public final class ValuationData {
  public static final int MAX_MANIFEST_BYTES = 32 * 1_048_576;
  public static final String ALGORITHM = "economy:base-values/v1";
  public static final String RECIPE_ALGORITHM = "economy:ordinary-recipes/v1";
  public static final String BREWING_ALGORITHM = "economy:brewing-recipes/v1";
  public static final String CONTRIBUTION_ALGORITHM = "economy:contributed-values/v1";
  public static final String VARIANT_ALGORITHM = "economy:component-values/v1";
  private static final Gson JSON =
      new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

  private ValuationData() {}

  public enum Classification {
    BASE,
    DERIVED,
    VARIANT,
    UNPRICED_OPERATOR
  }

  public record CatalogEntry(
      String item, Classification classification, String amount, String rule) {
    public CatalogEntry {
      itemId(item);
      Objects.requireNonNull(classification);
      Objects.requireNonNull(rule);
      if (rule.length() > 256) throw new IllegalArgumentException("Catalog rule bound");
      if (classification == Classification.BASE) {
        amount =
            new ValuationConfig.Entry(
                    item, ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD, amount, 1)
                .amount();
      } else if (amount != null)
        throw new IllegalArgumentException("Only base catalog rows have amounts");
    }
  }

  public record Catalog(Map<String, CatalogEntry> entries) {
    public Catalog {
      entries = sorted(entries);
      if (entries.size() != 1_333) throw new IllegalArgumentException("Pinned catalog inventory");
      for (var pair : entries.entrySet()) {
        if (!pair.getKey().equals(pair.getValue().item())
            || !pair.getKey().startsWith("minecraft:"))
          throw new IllegalArgumentException("Catalog identity mismatch");
      }
    }

    public String hash() {
      return ValuationData.hash(JSON.toJson(entries));
    }
  }

  public record Inputs(Set<String> items, String recipeHash, String tagsHash, String featuresHash) {
    public Inputs {
      items = ids(items);
      if (items.isEmpty() || items.size() > 100_000)
        throw new IllegalArgumentException("Registry item limit");
      requireHash(recipeHash);
      requireHash(tagsHash);
      requireHash(featuresHash);
    }

    public String registryHash() {
      return hash(JSON.toJson(items));
    }
  }

  /**
   * Trusted internal intake only; no public lifecycle registration surface is installed by A9-02.
   */
  public record Contribution(
      String providerId,
      String revision,
      Set<String> claimedItems,
      List<ValuationConfig.Entry> bases) {
    public Contribution {
      itemId(providerId);
      if (revision == null || revision.isBlank() || revision.length() > 128)
        throw new IllegalArgumentException("Provider revision");
      claimedItems = ids(claimedItems);
      if (claimedItems.size() > ValuationConfig.MAX_ENTRIES)
        throw new IllegalArgumentException("Provider claim limit");
      bases = new ValuationConfig.Config(bases, List.of()).bases();
      String namespace = providerId.substring(0, providerId.indexOf(':'));
      if (namespace.equals("minecraft") || namespace.equals("economy"))
        throw new IllegalArgumentException("Reserved provider namespace");
      for (String item : claimedItems)
        if (!item.startsWith(namespace + ':'))
          throw new IllegalArgumentException("Provider does not own claimed namespace");
      for (var entry : bases)
        if (!claimedItems.contains(entry.item()))
          throw new IllegalArgumentException("Unclaimed provider base");
    }
  }

  public record Source(ValuationConfig.Entry entry, String kind, String provider, String revision) {
    public Source {
      Objects.requireNonNull(entry);
      Objects.requireNonNull(kind);
      Objects.requireNonNull(provider);
      Objects.requireNonNull(revision);
      if (!Set.of("BUILTIN_BASE", "PROVIDER_BASE", "SERVER_BASE", "SERVER_OVERRIDE").contains(kind))
        throw new IllegalArgumentException("Base source kind");
    }
  }

  public record Manifest(
      int version,
      String algorithm,
      Inputs inputs,
      Catalog catalog,
      ValuationConfig.Config config,
      List<Contribution> contributions,
      String catalogHash,
      String configHash,
      String providerHash,
      Map<String, Source> sources,
      RecipeGraph.Graph recipes,
      BrewingGraph.Graph brewing,
      VariantGraph.Graph variants,
      List<ValuationContributions.Provider> providers) {
    public Manifest {
      providers = providers == null ? List.of() : List.copyOf(providers);
      if (!((version == 1
              && ALGORITHM.equals(algorithm)
              && recipes == null
              && brewing == null
              && variants == null)
          || (version == 2
              && RECIPE_ALGORITHM.equals(algorithm)
              && recipes != null
              && brewing == null
              && variants == null)
          || (version == 3
              && BREWING_ALGORITHM.equals(algorithm)
              && recipes != null
              && brewing != null
              && variants == null)
          || (version == 4
              && VARIANT_ALGORITHM.equals(algorithm)
              && recipes != null
              && brewing != null
              && variants != null)
          || (version == 5
              && CONTRIBUTION_ALGORITHM.equals(algorithm)
              && recipes != null
              && brewing != null
              && variants != null)))
        throw new IllegalArgumentException("Valuation manifest version");
      if (version < 5 && !providers.isEmpty())
        throw new IllegalArgumentException("Providers require v5");
      Objects.requireNonNull(inputs);
      Objects.requireNonNull(catalog);
      Objects.requireNonNull(config);
      contributions = List.copyOf(contributions);
      sources = sorted(sources);
      requireHash(catalogHash);
      requireHash(configHash);
      requireHash(providerHash);
      if (recipes != null) RecipeGraph.validate(recipes, inputs);
      if (brewing != null) BrewingGraph.validate(brewing, inputs);
      if (variants != null) VariantGraph.validate(variants, inputs);
    }

    public Manifest(
        int version,
        String algorithm,
        Inputs inputs,
        Catalog catalog,
        ValuationConfig.Config config,
        List<Contribution> contributions,
        String catalogHash,
        String configHash,
        String providerHash,
        Map<String, Source> sources,
        RecipeGraph.Graph recipes,
        BrewingGraph.Graph brewing,
        VariantGraph.Graph variants) {
      this(
          version,
          algorithm,
          inputs,
          catalog,
          config,
          contributions,
          catalogHash,
          configHash,
          providerHash,
          sources,
          recipes,
          brewing,
          variants,
          List.of());
    }

    public Manifest(
        int version,
        String algorithm,
        Inputs inputs,
        Catalog catalog,
        ValuationConfig.Config config,
        List<Contribution> contributions,
        String catalogHash,
        String configHash,
        String providerHash,
        Map<String, Source> sources,
        RecipeGraph.Graph recipes,
        BrewingGraph.Graph brewing) {
      this(
          version,
          algorithm,
          inputs,
          catalog,
          config,
          contributions,
          catalogHash,
          configHash,
          providerHash,
          sources,
          recipes,
          brewing,
          null);
    }

    public Manifest(
        int version,
        String algorithm,
        Inputs inputs,
        Catalog catalog,
        ValuationConfig.Config config,
        List<Contribution> contributions,
        String catalogHash,
        String configHash,
        String providerHash,
        Map<String, Source> sources,
        RecipeGraph.Graph recipes) {
      this(
          version,
          algorithm,
          inputs,
          catalog,
          config,
          contributions,
          catalogHash,
          configHash,
          providerHash,
          sources,
          recipes,
          null);
    }

    public Manifest(
        int version,
        String algorithm,
        Inputs inputs,
        Catalog catalog,
        ValuationConfig.Config config,
        List<Contribution> contributions,
        String catalogHash,
        String configHash,
        String providerHash,
        Map<String, Source> sources) {
      this(
          version,
          algorithm,
          inputs,
          catalog,
          config,
          contributions,
          catalogHash,
          configHash,
          providerHash,
          sources,
          null,
          null);
    }

    public String hash() {
      return ValuationData.hash(encode(this));
    }
  }

  private static final class Builtin {
    private static final Catalog CATALOG = readCatalog();
  }

  public static Catalog catalog() {
    return Builtin.CATALOG;
  }

  private static Catalog readCatalog() {
    try (var stream =
        ValuationData.class.getResourceAsStream("/data/economy/valuation/catalog.json")) {
      if (stream == null) throw new IllegalStateException("Missing valuation catalog");
      byte[] bytes = stream.readNBytes(ValuationConfig.MAX_BYTES + 1);
      if (bytes.length > ValuationConfig.MAX_BYTES)
        throw new IllegalStateException("Builtin catalog bound");
      JsonElement raw =
          ValuationConfig.parse(ValuationConfig.utf8(bytes), ValuationConfig.MAX_BYTES);
      if (!raw.isJsonArray()) throw new IllegalArgumentException("Catalog array required");
      var entries = new TreeMap<String, CatalogEntry>();
      for (JsonElement element : raw.getAsJsonArray()) {
        var object = ValuationConfig.object(element);
        ValuationConfig.keys(object, Set.of("item", "classification", "amount", "rule"));
        CatalogEntry entry = JSON.fromJson(object, CatalogEntry.class);
        if (entries.putIfAbsent(entry.item(), entry) != null)
          throw new IllegalArgumentException("Duplicate catalog identity");
      }
      return new Catalog(entries);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read valuation catalog", e);
    }
  }

  public static Manifest prepare(
      Inputs inputs, ValuationConfig.Config config, List<Contribution> contributions) {
    return assemble(inputs, catalog(), config, contributions);
  }

  public static Manifest prepare(
      Inputs inputs,
      ValuationConfig.Config config,
      List<Contribution> contributions,
      RecipeGraph.Graph recipes) {
    var base = assemble(inputs, catalog(), config, contributions);
    return withRecipes(base, recipes);
  }

  private static Manifest withRecipes(Manifest base, RecipeGraph.Graph recipes) {
    return new Manifest(
        2,
        RECIPE_ALGORITHM,
        base.inputs(),
        base.catalog(),
        base.config(),
        base.contributions(),
        base.catalogHash(),
        base.configHash(),
        base.providerHash(),
        base.sources(),
        Objects.requireNonNull(recipes));
  }

  public static Manifest prepare(
      Inputs inputs,
      ValuationConfig.Config config,
      List<Contribution> contributions,
      RecipeGraph.Graph recipes,
      BrewingGraph.Graph brewing) {
    return withBrewing(prepare(inputs, config, contributions, recipes), brewing);
  }

  private static Manifest withBrewing(Manifest ordinary, BrewingGraph.Graph brewing) {
    return new Manifest(
        3,
        BREWING_ALGORITHM,
        ordinary.inputs(),
        ordinary.catalog(),
        ordinary.config(),
        ordinary.contributions(),
        ordinary.catalogHash(),
        ordinary.configHash(),
        ordinary.providerHash(),
        ordinary.sources(),
        ordinary.recipes(),
        Objects.requireNonNull(brewing));
  }

  public static Manifest prepare(
      Inputs inputs,
      ValuationConfig.Config config,
      List<Contribution> contributions,
      RecipeGraph.Graph recipes,
      BrewingGraph.Graph brewing,
      VariantGraph.Graph variants) {
    return withVariants(
        withBrewing(
            withRecipes(assemble(inputs, catalog(), config, contributions), recipes), brewing),
        variants);
  }

  private static Manifest withVariants(Manifest base, VariantGraph.Graph variants) {
    return new Manifest(
        4,
        VARIANT_ALGORITHM,
        base.inputs(),
        base.catalog(),
        base.config(),
        base.contributions(),
        base.catalogHash(),
        base.configHash(),
        base.providerHash(),
        base.sources(),
        base.recipes(),
        base.brewing(),
        VariantGraph.withPremiums(
            Objects.requireNonNull(variants), base.config().enchantmentPremiums()));
  }

  /**
   * Public-lifecycle intake: all candidate conflicts are checked before producing an immutable v5
   * manifest.
   */
  public static Manifest prepareWithProviders(
      Inputs inputs,
      ValuationConfig.Config config,
      List<Contribution> legacy,
      RecipeGraph.Graph recipes,
      BrewingGraph.Graph brewing,
      VariantGraph.Graph variants,
      List<ValuationContributions.Provider> providers) {
    return withProviders(inputs, catalog(), config, legacy, recipes, brewing, variants, providers);
  }

  private static Manifest withProviders(
      Inputs inputs,
      Catalog catalog,
      ValuationConfig.Config config,
      List<Contribution> legacy,
      RecipeGraph.Graph recipes,
      BrewingGraph.Graph brewing,
      VariantGraph.Graph variants,
      List<ValuationContributions.Provider> providers) {
    var ordered =
        providers.stream()
            .sorted(Comparator.comparing(ValuationContributions.Provider::providerId))
            .toList();
    ValuationContributions.validate(inputs, ordered, legacy);
    long entryCount = (long) config.bases().size() + config.overrides().size();
    for (var old : legacy) entryCount += old.bases().size();
    for (var p : ordered)
      entryCount +=
          (long) p.bases().size()
              + p.exactSelectors().size()
              + p.derivationOptIns().size()
              + p.variantRules().size();
    if (entryCount > ValuationConfig.MAX_ENTRIES)
      throw new IllegalArgumentException("Aggregate configuration/contribution entry limit");
    var combined = new ArrayList<>(legacy);
    for (var p : ordered)
      combined.add(new Contribution(p.providerId(), p.revision(), p.claimedItems(), p.bases()));
    var base = assemble(inputs, catalog, config, combined);
    var extended =
        VariantGraph.withPremiums(
            ValuationContributions.extend(variants, ordered), config.enchantmentPremiums());
    return new Manifest(
        5,
        CONTRIBUTION_ALGORITHM,
        inputs,
        catalog,
        config,
        legacy,
        base.catalogHash(),
        base.configHash(),
        hash(JSON.toJson(List.of(base.contributions(), ordered))),
        base.sources(),
        recipes,
        brewing,
        extended,
        ordered);
  }

  private static Manifest assemble(
      Inputs inputs,
      Catalog catalog,
      ValuationConfig.Config config,
      List<Contribution> contributions) {
    Objects.requireNonNull(inputs);
    Objects.requireNonNull(catalog);
    Objects.requireNonNull(config);
    if (!inputs.items().containsAll(catalog.entries().keySet()))
      throw new IllegalArgumentException("Registry omits pinned vanilla identities");
    // Encoding enforces the aggregate config byte limit even for trusted in-memory callers.
    String configText = ValuationConfig.encode(config);
    if (contributions.size() > ValuationConfig.MAX_ENTRIES)
      throw new IllegalArgumentException("Provider count limit");
    var providers = new TreeMap<String, Contribution>();
    var claims = new TreeMap<String, String>();
    int entryCount = config.bases().size() + config.overrides().size();
    for (Contribution contribution : contributions) {
      Objects.requireNonNull(contribution);
      if (providers.putIfAbsent(contribution.providerId(), contribution) != null)
        throw new IllegalArgumentException("Duplicate provider identity");
      entryCount = Math.addExact(entryCount, contribution.bases().size());
      if (entryCount > ValuationConfig.MAX_ENTRIES)
        throw new IllegalArgumentException("Contribution entry limit");
      for (String item : contribution.claimedItems()) {
        if (!inputs.items().contains(item))
          throw new IllegalArgumentException("Unregistered provider identity");
        if (claims.putIfAbsent(item, contribution.providerId()) != null)
          throw new IllegalArgumentException("Conflicting provider claim: " + item);
      }
    }
    if (claims.size() > ValuationConfig.MAX_ENTRIES)
      throw new IllegalArgumentException("Aggregate provider claim limit");
    List<Contribution> ordered = List.copyOf(providers.values());
    var sources = new TreeMap<String, Source>();
    String catalogHash = catalog.hash();
    for (CatalogEntry entry : catalog.entries().values()) {
      if (entry.classification() == Classification.BASE)
        sources.put(
            entry.item(),
            new Source(
                new ValuationConfig.Entry(
                    entry.item(),
                    ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD,
                    entry.amount(),
                    1),
                "BUILTIN_BASE",
                "economy:vanilla-catalog",
                catalogHash));
    }
    for (Contribution provider : ordered)
      for (var entry : provider.bases())
        sources.put(
            entry.item(),
            new Source(entry, "PROVIDER_BASE", provider.providerId(), provider.revision()));
    String configHash = hash(configText);
    for (var entry : config.bases()) {
      validateOperatorEntry(entry, inputs, catalog, claims);
      sources.put(
          entry.item(), new Source(entry, "SERVER_BASE", "economy:server-config", configHash));
    }
    for (var entry : config.overrides()) {
      validateOperatorEntry(entry, inputs, catalog, claims);
      sources.put(
          entry.item(), new Source(entry, "SERVER_OVERRIDE", "economy:server-config", configHash));
    }
    return new Manifest(
        1,
        ALGORITHM,
        inputs,
        catalog,
        config,
        ordered,
        catalogHash,
        configHash,
        hash(JSON.toJson(ordered)),
        sources);
  }

  private static void validateOperatorEntry(
      ValuationConfig.Entry entry, Inputs inputs, Catalog catalog, Map<String, String> claims) {
    if (!inputs.items().contains(entry.item()))
      throw new IllegalArgumentException("Unknown item: " + entry.item());
    CatalogEntry vanilla = catalog.entries().get(entry.item());
    if (vanilla != null && vanilla.classification() == Classification.UNPRICED_OPERATOR)
      throw new IllegalArgumentException("Operator-only item cannot receive a reference price");
    if (vanilla == null && !claims.containsKey(entry.item()))
      throw new IllegalArgumentException("Mod item requires explicit contribution registration");
  }

  public static String encode(Manifest manifest) {
    Manifest expected;
    if (manifest.version() == 5)
      expected =
          withProviders(
              manifest.inputs(),
              manifest.catalog(),
              manifest.config(),
              manifest.contributions(),
              manifest.recipes(),
              manifest.brewing(),
              manifest.variants(),
              manifest.providers());
    else
      expected =
          assemble(
              manifest.inputs(), manifest.catalog(), manifest.config(), manifest.contributions());
    if (manifest.version() >= 2 && manifest.version() < 5)
      expected = withRecipes(expected, manifest.recipes());
    if (manifest.version() >= 3 && manifest.version() < 5)
      expected = withBrewing(expected, manifest.brewing());
    if (manifest.version() == 4) expected = withVariants(expected, manifest.variants());
    if (!manifest.equals(expected))
      throw new IllegalArgumentException("Inconsistent manifest provenance");
    var object = JSON.toJsonTree(manifest).getAsJsonObject();
    if (manifest.version() < 5) object.remove("providers");
    // v1 journal payloads predate the recipes field: preserve their byte-for-byte canonical form.
    if (manifest.version() == 1) object.remove("recipes");
    if (manifest.version() < 3) object.remove("brewing");
    if (manifest.version() < 4) {
      if (!manifest.config().enchantmentPremiums().isEmpty())
        throw new IllegalArgumentException("Premium configuration needs variant algorithm");
      object.remove("variants");
      object.getAsJsonObject("config").remove("enchantmentPremiums");
    }
    String encoded = JSON.toJson(object);
    if (ValuationConfig.bytes(encoded) > MAX_MANIFEST_BYTES)
      throw new IllegalArgumentException("Manifest byte limit");
    return encoded;
  }

  public static Manifest decode(String encoded) {
    JsonElement parsed = ValuationConfig.parse(encoded, MAX_MANIFEST_BYTES);
    Manifest manifest = JSON.fromJson(parsed, Manifest.class);
    if (manifest == null || !encode(manifest).equals(encoded))
      throw new IllegalArgumentException("Noncanonical valuation manifest");
    return manifest;
  }

  public static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static void requireHash(String hash) {
    if (hash == null || !hash.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("SHA-256 required");
  }

  static void itemId(String item) {
    if (item == null || item.length() > 256 || !item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
      throw new IllegalArgumentException("Namespaced item identity required");
  }

  private static Set<String> ids(Set<String> ids) {
    Objects.requireNonNull(ids);
    var sorted = new TreeSet<String>();
    for (String item : ids) {
      itemId(item);
      sorted.add(item);
    }
    return Collections.unmodifiableSortedSet(sorted);
  }

  private static <V> Map<String, V> sorted(Map<String, V> values) {
    Objects.requireNonNull(values);
    var sorted = new TreeMap<String, V>();
    for (var entry : values.entrySet())
      sorted.put(Objects.requireNonNull(entry.getKey()), Objects.requireNonNull(entry.getValue()));
    return Collections.unmodifiableSortedMap(sorted);
  }
}
