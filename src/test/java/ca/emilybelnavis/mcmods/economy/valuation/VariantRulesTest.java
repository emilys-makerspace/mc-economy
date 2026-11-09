package ca.emilybelnavis.mcmods.economy.valuation;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.fx.EffectiveFxSnapshot;
import ca.emilybelnavis.mcmods.economy.fx.FxFacts;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class VariantRulesTest {
  private static final EffectiveFxSnapshot FX = fx();

  private static EffectiveFxSnapshot fx() {
    var rates = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
    for (var currency : EconomyCurrency.values()) rates.put(currency, BigDecimal.ONE.setScale(12));
    return EffectiveFxSnapshot.legacy(
        new WorldId(UUID.randomUUID()),
        new FxFacts.Publication(
            LocalDate.of(2026, 11, 8),
            EconomyCurrency.CAD,
            UUID.randomUUID(),
            UUID.randomUUID(),
            rates));
  }

  private static ValuationData.Manifest manifest() {
    var items = new TreeMap<String, VariantGraph.Item>();
    for (String id : ValuationData.catalog().entries().keySet())
      items.put(id, new VariantGraph.Item(id, 0, 64, 0, Set.of()));
    var effects = List.of(new VariantGraph.StewEffect("minecraft:night_vision", 100));
    var graph =
        new VariantGraph.Graph(
            1,
            VariantGraph.ADAPTER,
            VariantGraph.RULES_HASH,
            items,
            Map.of(),
            VariantDefaults.rules(),
            Map.of("minecraft:poppy", effects, "minecraft:dandelion", effects),
            Map.of(0xff0000, "minecraft:red_dye", 0x0000ff, "minecraft:blue_dye"));
    var overrides = new ArrayList<ValuationConfig.Entry>();
    for (String id :
        List.of(
            "paper",
            "gunpowder",
            "feather",
            "glowstone_dust",
            "red_dye",
            "blue_dye",
            "fire_charge",
            "gold_nugget",
            "bowl",
            "red_mushroom",
            "brown_mushroom",
            "dandelion",
            "glass_bottle",
            "beehive",
            "axolotl_bucket",
            "cod_bucket",
            "tropical_fish_bucket"))
      overrides.add(new ValuationConfig.Entry("minecraft:" + id, EconomyCurrency.CAD, "1", 1));
    overrides.add(new ValuationConfig.Entry("minecraft:diamond", EconomyCurrency.CAD, "10", 1));
    overrides.add(new ValuationConfig.Entry("minecraft:poppy", EconomyCurrency.CAD, "2", 1));
    overrides.add(
        new ValuationConfig.Entry("minecraft:skeleton_skull", EconomyCurrency.CAD, "3", 1));
    overrides.add(new ValuationConfig.Entry("minecraft:creeper_head", EconomyCurrency.CAD, "5", 1));
    var recipes = new RecipeGraph.Graph(List.of(), Map.of(), Map.of());
    var brewing =
        new BrewingGraph.Graph(
            1,
            BrewingGraph.ADAPTER,
            BrewingGraph.RULES_HASH,
            true,
            Map.of("minecraft:water", new BrewingGraph.Potion("minecraft:water", List.of())),
            new ArrayList<>(BrewingGraph.FORMS),
            List.of(),
            Map.of(),
            ValuationData.hash("fixture"));
    return ValuationData.prepare(
        new ValuationData.Inputs(
            items.keySet(),
            ValuationData.hash("recipes"),
            ValuationData.hash("tags"),
            ValuationData.hash("features")),
        new ValuationConfig.Config(List.of(), overrides),
        List.of(),
        recipes,
        brewing,
        graph);
  }

  private static RecipeValues.Result value(
      ValuationData.Manifest manifest, String item, String json) {
    return RecipeValues.evaluate(manifest, "minecraft:" + item, json, 1, EconomyCurrency.CAD, FX);
  }

  private static void ready(RecipeValues.Result result, long minor) {
    assertEquals(RecipeValues.Status.READY, result.status(), result.reason());
    assertEquals(minor, result.unitMoney().minorUnits());
  }

  private static void refused(
      ValuationData.Manifest manifest, String item, String json, String reason) {
    var result = value(manifest, item, json);
    assertEquals(RecipeValues.Status.UNSUPPORTED_IDENTITY, result.status());
    assertEquals(reason, result.reason());
    assertNull(result.unitMoney());
  }

  @Test
  void fireworksUseExactMaterialsCheapestAvailableHeadAndThreeRocketOutputs() {
    var manifest = manifest();
    String explosion =
        "{\"shape\":\"burst\",\"colors\":[16711680],\"fade_colors\":[255],\"has_trail\":true,\"has_twinkle\":true}";
    ready(
        value(manifest, "firework_star", "{\"minecraft:firework_explosion\":" + explosion + "}"),
        1500);
    var rocket =
        value(
            manifest,
            "firework_rocket",
            "{\"minecraft:fireworks\":{\"flight_duration\":2,\"explosions\":[" + explosion + "]}}");
    ready(rocket, 600);
    assertEquals(Rational.of(600), rocket.exactUnitMinor());
    assertTrue(
        rocket.explanation().steps().stream()
            .anyMatch(
                s -> s.kind().equals("FIREWORK_ROCKET_THREE_OUTPUTS") && s.outputCount() == 3));
    ready(
        value(
            manifest,
            "firework_star",
            "{\"minecraft:firework_explosion\":{\"shape\":\"creeper\",\"colors\":[16711680]}}"),
        500);
    ready(
        value(
            manifest,
            "firework_star",
            "{\"minecraft:firework_explosion\":{\"shape\":\"large_ball\",\"colors\":[16711680]}}"),
        300);
    ready(
        value(
            manifest,
            "firework_star",
            "{\"minecraft:firework_explosion\":{\"shape\":\"star\",\"colors\":[16711680]}}"),
        300);
    refused(
        manifest,
        "firework_star",
        "{\"minecraft:firework_explosion\":{\"shape\":\"small_ball\",\"colors\":[]}}",
        "FIREWORK_COLOR_REQUIRED");
    refused(
        manifest,
        "firework_star",
        "{\"minecraft:firework_explosion\":{\"shape\":\"small_ball\",\"colors\":[123]}}",
        "UNKNOWN_FIREWORK_COLOR");
    refused(
        manifest,
        "firework_star",
        "{\"minecraft:firework_explosion\":{\"shape\":\"unknown\",\"colors\":[255]}}",
        "UNKNOWN_FIREWORK_SHAPE");
  }

  @Test
  void stewOmenAndBeeContentsUseCapturedRulesAndRejectUnrecognizedLivingData() {
    var manifest = manifest();
    ready(
        value(
            manifest,
            "suspicious_stew",
            "{\"minecraft:suspicious_stew_effects\":[{\"id\":\"minecraft:night_vision\",\"duration\":100}]}"),
        400);
    refused(
        manifest,
        "suspicious_stew",
        "{\"minecraft:suspicious_stew_effects\":[{\"id\":\"minecraft:night_vision\",\"duration\":101}]}",
        "UNSUPPORTED_STEW_EFFECTS");
    ready(value(manifest, "ominous_bottle", "{\"minecraft:ominous_bottle_amplifier\":4}"), 12600);
    String entity =
        "{\"id\":\"minecraft:bee\",\"Health\":8,\"Age\":-100,\"HasNectar\":true,\"AbsorptionAmount\":0,\"LoveCause\":[1,2,3,4],\"flower_pos\":{\"X\":1,\"Y\":64,\"Z\":2},\"Tags\":[\"fixture\"],\"CustomName\":\"Bee\",\"active_effects\":[],\"Attributes\":[{\"Name\":\"minecraft:generic.max_health\",\"Base\":10,\"Modifiers\":[]}]}";
    String occupied =
        "{\"minecraft:bees\":[{\"entity_data\":"
            + entity
            + ",\"ticks_in_hive\":0,\"min_ticks_in_hive\":2400}]}";
    ready(value(manifest, "beehive", occupied), 300);
    ready(value(manifest, "beehive", "{\"minecraft:bees\":[]}"), 100);
    refused(
        manifest,
        "beehive",
        occupied.replace("minecraft:bee\"", "minecraft:pig\""),
        "UNSUPPORTED_BEE_IDENTITY");
    refused(
        manifest,
        "beehive",
        occupied.replace("minecraft:generic.max_health", "fixture:attribute"),
        "UNKNOWN_BEE_ATTRIBUTE");
    refused(manifest, "beehive", occupied.replace("[1,2,3,4]", "[1,2,3]"), "ENTITY_UUID_BOUND");
    ready(
        value(
            manifest,
            "axolotl_bucket",
            "{\"minecraft:bucket_entity_data\":{\"Variant\":4,\"Age\":-100,\"HuntingCooldown\":10,\"Health\":5.5,\"NoAI\":false}}"),
        100);
    ready(
        value(
            manifest,
            "tropical_fish_bucket",
            "{\"minecraft:bucket_entity_data\":{\"BucketVariantTag\":123,\"Silent\":1}}"),
        100);
    refused(
        manifest,
        "cod_bucket",
        "{\"minecraft:bucket_entity_data\":{\"Variant\":1}}",
        "BUCKET_VARIANT_APPLICABILITY");
    refused(
        manifest,
        "cod_bucket",
        "{\"minecraft:bucket_entity_data\":{\"Command\":\"unknown\"}}",
        "UNKNOWN_BUCKET_ENTITY_DATA");
  }
}
