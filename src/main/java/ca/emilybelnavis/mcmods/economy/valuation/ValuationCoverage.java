package ca.emilybelnavis.mcmods.economy.valuation;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.fx.EffectiveFxSnapshot;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;

/** Bounded detached numeric inventory review; classification is not arbitrary component support. */
public final class ValuationCoverage {
  private ValuationCoverage() {}

  /**
   * Values count one reviewed representative state per non-operator vanilla identity. Unavailable
   * contains genuine numeric source gaps, separately from adapter and budget limits.
   */
  public record Report(
      int total,
      int base,
      int derived,
      int variant,
      int operator,
      int unregistered,
      int values,
      Map<String, String> unavailable,
      Map<String, String> componentLimits,
      Map<String, String> resourceLimits) {
    public Report {
      unavailable = immutable(unavailable);
      componentLimits = immutable(componentLimits);
      resourceLimits = immutable(resourceLimits);
      if (total < 0
          || base < 0
          || derived < 0
          || variant < 0
          || operator < 0
          || unregistered < 0
          || values < 0
          || total != base + derived + variant + operator
          || total
              != operator
                  + values
                  + unavailable.size()
                  + componentLimits.size()
                  + resourceLimits.size())
        throw new IllegalArgumentException("Coverage inventory mismatch");
    }

    public boolean ordinaryComplete() {
      return unavailable.isEmpty();
    }

    public boolean representativeComplete() {
      return unavailable.isEmpty() && componentLimits.isEmpty() && resourceLimits.isEmpty();
    }

    public String summary() {
      return "Vanilla="
          + total
          + " base="
          + base
          + " derived="
          + derived
          + " variant="
          + variant
          + " operator="
          + operator
          + " representativeValues="
          + values
          + " numericGaps="
          + unavailable.size()
          + " componentLimits="
          + componentLimits.size()
          + " resourceLimits="
          + resourceLimits.size()
          + " unregisteredMod="
          + unregistered;
    }
  }

  public static Report report(
      ValuationData.Manifest manifest, EffectiveFxSnapshot fx, EconomyCurrency currency) {
    Objects.requireNonNull(manifest);
    Objects.requireNonNull(currency);
    var gaps = new TreeMap<String, String>();
    var components = new TreeMap<String, String>();
    var limits = new TreeMap<String, String>();
    int base = 0, derived = 0, variant = 0, operator = 0, values = 0;
    String hash = manifest.hash();
    for (var entry : manifest.catalog().entries().values()) {
      switch (entry.classification()) {
        case BASE -> base++;
        case DERIVED -> derived++;
        case VARIANT -> variant++;
        case UNPRICED_OPERATOR -> {
          operator++;
          continue;
        }
      }
      String identity = representativeComponents(manifest, entry.item());
      if (identity == null) {
        components.put(entry.item(), "NO_REPRESENTATIVE_TYPED_STATE");
        continue;
      }
      var value =
          manifest.variants() == null
              ? RecipeValues.evaluate(manifest, entry.item(), identity, 1, currency, fx)
              : VariantValues.evaluate(manifest, entry.item(), identity, 1, currency, fx, hash);
      if (value.status() == RecipeValues.Status.READY) {
        values++;
        continue;
      }
      String reason = value.status() + ":" + value.reason();
      if (value.status() == RecipeValues.Status.LIMIT_EXCEEDED
          || value.status() == RecipeValues.Status.OVERFLOW
          || value.status() == RecipeValues.Status.FX_UNAVAILABLE) limits.put(entry.item(), reason);
      else if (componentLimit(value)) components.put(entry.item(), reason);
      else gaps.put(entry.item(), reason);
    }
    var registered = new HashSet<String>();
    for (var provider : manifest.providers()) registered.addAll(provider.claimedItems());
    for (var provider : manifest.contributions()) registered.addAll(provider.claimedItems());
    int unregistered = 0;
    for (String id : manifest.inputs().items())
      if (!manifest.catalog().entries().containsKey(id) && !registered.contains(id)) unregistered++;
    return new Report(
        manifest.catalog().entries().size(),
        base,
        derived,
        variant,
        operator,
        unregistered,
        values,
        gaps,
        components,
        limits);
  }

  /** A reviewed witness only, never a replacement for a caller's exact component identity. */
  public static String representativeComponents(ValuationData.Manifest manifest, String item) {
    if (BrewingGraph.FORMS.contains(item) || item.equals("minecraft:tipped_arrow"))
      return BrewingGraph.components("minecraft:water");
    if (item.equals("minecraft:firework_star")) {
      if (manifest.variants() == null || manifest.variants().fireworkColors().isEmpty())
        return null;
      int rgb =
          manifest.variants().fireworkColors().keySet().stream()
              .min(Integer::compareTo)
              .orElseThrow();
      var colors = new JsonArray();
      colors.add(rgb);
      var explosion = new JsonObject();
      explosion.addProperty("shape", "small_ball");
      explosion.add("colors", colors);
      var components = new JsonObject();
      components.add("minecraft:firework_explosion", explosion);
      return components.toString();
    }
    if (item.equals("minecraft:suspicious_stew")) {
      if (manifest.variants() == null || manifest.variants().flowers().isEmpty()) return null;
      var witness =
          manifest.variants().flowers().entrySet().stream()
              .sorted(Map.Entry.comparingByKey())
              .findFirst()
              .orElseThrow();
      var effects = new JsonArray();
      for (var effect : witness.getValue()) {
        var value = new JsonObject();
        value.addProperty("id", effect.id());
        value.addProperty("duration", effect.duration());
        effects.add(value);
      }
      var components = new JsonObject();
      components.add("minecraft:suspicious_stew_effects", effects);
      return components.toString();
    }
    return "{}";
  }

  private static boolean componentLimit(RecipeValues.Result value) {
    if (value.status() != RecipeValues.Status.UNSUPPORTED_IDENTITY) return false;
    String reason = value.reason();
    return reason.contains("COMPONENT")
        || reason.contains("IDENTITY_REQUIRED")
        || reason.equals("UNSUPPORTED_ITEM_PROTOTYPE")
        || reason.equals("NO_REGISTERED_COMPONENT_ADAPTER")
        || reason.equals("VARIANT_ADAPTER_NOT_INSTALLED");
  }

  private static Map<String, String> immutable(Map<String, String> values) {
    return Collections.unmodifiableSortedMap(new TreeMap<>(values));
  }
}
