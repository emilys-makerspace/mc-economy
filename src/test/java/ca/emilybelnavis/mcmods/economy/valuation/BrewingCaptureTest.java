package ca.emilybelnavis.mcmods.economy.valuation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.neoforge.common.brewing.IBrewingRecipe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BrewingCaptureTest {
  @BeforeAll
  static void bootstrap() {
    if (net.neoforged.fml.loading.LoadingModList.get() == null)
      net.neoforged.fml.loading.LoadingModList.of(
          List.of(), List.of(), List.of(), List.of(), Map.of());
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  private static PotionBrewing.Builder vanilla() {
    var builder = new PotionBrewing.Builder(FeatureFlags.DEFAULT_FLAGS);
    PotionBrewing.addVanillaMixes(builder);
    return builder;
  }

  private static BrewingGraph.Graph capture(PotionBrewing brewing) {
    return VanillaBrewingCapture.capture(brewing, FeatureFlags.DEFAULT_FLAGS);
  }

  @Test
  void nativeVanillaListsCaptureEffectsFormsAndDragonBreathRemainder() {
    var graph = capture(vanilla().build());
    assertTrue(graph.vanillaCompatible(), graph.exclusions().toString());
    assertTrue(graph.exclusions().isEmpty());
    assertEquals(46, graph.potions().size());
    assertEquals(BrewingGraph.FORMS, new java.util.HashSet<>(graph.containers()));
    assertTrue(graph.potions().get("minecraft:water").effects().isEmpty());
    var healing = graph.potions().get("minecraft:strong_healing").effects().getFirst();
    assertEquals("minecraft:instant_health", healing.id());
    assertEquals(1, healing.amplifier());
    for (String form : BrewingGraph.FORMS)
      assertTrue(
          graph.mixes().stream()
              .anyMatch(
                  m ->
                      m.from().equals(new BrewingGraph.State(form, "minecraft:water"))
                          && m.to().equals(new BrewingGraph.State(form, "minecraft:awkward"))
                          && m.ingredient().equals("minecraft:nether_wart")));
    assertTrue(
        graph.mixes().stream()
            .anyMatch(
                m ->
                    m.from()
                            .equals(
                                new BrewingGraph.State(
                                    "minecraft:splash_potion", "minecraft:awkward"))
                        && m.to()
                            .equals(
                                new BrewingGraph.State(
                                    "minecraft:lingering_potion", "minecraft:awkward"))
                        && m.remainder().equals("GLASS_BOTTLE_NO_SALVAGE")));
    assertEquals(graph.hash(), capture(vanilla().build()).hash());
  }

  @Test
  void modifiedNativeListsKeepOnlyReviewedMixesAndRecordCustomRegistryWithoutCallbacks() {
    var builder = vanilla();
    builder.addMix(Potions.WATER, Items.DIAMOND, Potions.HEALING);
    builder.addRecipe(
        new IBrewingRecipe() {
          @Override
          public boolean isInput(ItemStack input) {
            throw new AssertionError("No recipe predicate during capture");
          }

          @Override
          public boolean isIngredient(ItemStack ingredient) {
            throw new AssertionError("No recipe predicate during capture");
          }

          @Override
          public ItemStack getOutput(ItemStack input, ItemStack ingredient) {
            throw new AssertionError("No brewing callback during capture");
          }
        });
    var original = capture(vanilla().build());
    var changed = capture(builder.build());
    assertFalse(changed.vanillaCompatible());
    assertEquals("NONVANILLA_ORDERED_POTION_MIXES", changed.exclusions().get("potion_mixes"));
    assertEquals(
        "CUSTOM_RECIPES_REQUIRE_ADAPTER:count=1", changed.exclusions().get("custom_registry"));
    assertEquals(original.mixes(), changed.mixes());
    assertNotEquals(original.capturedListsHash(), changed.capturedListsHash());
    assertTrue(changed.mixes().stream().noneMatch(m -> m.ingredient().equals("minecraft:diamond")));
  }

  @Test
  void missingNativeListsCannotClaimVanillaCompatibility() {
    var graph = capture(new PotionBrewing.Builder(FeatureFlags.DEFAULT_FLAGS).build());
    assertFalse(graph.vanillaCompatible());
    assertEquals(
        Map.of(
            "containers",
            "NONVANILLA_CONTAINER_LIST",
            "potion_mixes",
            "NONVANILLA_ORDERED_POTION_MIXES",
            "form_mixes",
            "NONVANILLA_ORDERED_FORM_MIXES"),
        graph.exclusions());
    assertTrue(graph.mixes().isEmpty());
    assertTrue(graph.containers().isEmpty());
    assertEquals(46, graph.potions().size());
  }
}
