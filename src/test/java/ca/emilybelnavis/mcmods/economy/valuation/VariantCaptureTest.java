package ca.emilybelnavis.mcmods.economy.valuation;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Lifecycle;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class VariantCaptureTest {
  @BeforeAll
  static void bootstrap() {
    if (net.neoforged.fml.loading.LoadingModList.get() == null)
      net.neoforged.fml.loading.LoadingModList.of(
          List.of(), List.of(), List.of(), List.of(), Map.of());
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  @Test
  void vanillaRegistryCaptureFreezesPremiumsPrototypesFlowersAndDyeMaterials() {
    var vanilla = VanillaRegistries.createLookup();
    // Datagen tag wrappers are deliberately outside the reviewed HolderSet types.
    // A detached runtime-style registry uses real records and explicit direct membership.
    assertTrue(VariantCapture.capture(vanilla).enchantments().isEmpty());
    var registry = new MappedRegistry<Enchantment>(Registries.ENCHANTMENT, Lifecycle.stable());
    vanilla
        .lookupOrThrow(Registries.ENCHANTMENT)
        .listElements()
        .forEach(
            holder -> {
              var value = holder.value();
              var definition = value.definition();
              var supported = HolderSet.direct(Items.IRON_SWORD.builtInRegistryHolder());
              var plain =
                  new Enchantment.EnchantmentDefinition(
                      supported,
                      Optional.empty(),
                      definition.weight(),
                      definition.maxLevel(),
                      definition.minCost(),
                      definition.maxCost(),
                      definition.anvilCost(),
                      definition.slots());
              Registry.register(
                  registry,
                  holder.key().location(),
                  new Enchantment(value.description(), plain, HolderSet.direct(), value.effects()));
            });
    registry.freeze();
    var graph =
        VariantCapture.capture(HolderLookup.Provider.create(Stream.of(registry.asLookup())));
    assertEquals(42, graph.enchantments().size());
    var sharpness = graph.enchantments().get("minecraft:sharpness");
    assertEquals(5, sharpness.maxLevel());
    assertEquals(5, sharpness.premiums().size());
    assertFalse(sharpness.curse());
    assertTrue(sharpness.supported().contains("minecraft:iron_sword"));
    assertTrue(graph.enchantments().get("minecraft:vanishing_curse").curse());
    var sword = graph.items().get("minecraft:iron_sword");
    assertEquals(250, sword.maxDamage());
    assertEquals(1, sword.maxStack());
    assertTrue(sword.defaults().contains("minecraft:max_damage"));
    assertEquals(27, graph.items().get("minecraft:shulker_box").slots());
    assertEquals(5, graph.items().get("minecraft:hopper").slots());
    assertEquals(3, graph.items().get("minecraft:furnace").slots());
    assertEquals(9, graph.items().get("minecraft:dispenser").slots());
    assertEquals(6, graph.items().get("minecraft:chiseled_bookshelf").slots());
    assertEquals(1, graph.items().get("minecraft:decorated_pot").slots());
    assertEquals(0, graph.items().get("minecraft:diamond").slots());
    assertTrue(graph.flowers().containsKey("minecraft:dandelion"));
    assertFalse(graph.flowers().get("minecraft:dandelion").isEmpty());
    assertEquals(16, graph.fireworkColors().size());
    assertEquals("minecraft:red_dye", graph.fireworkColors().get(DyeColor.RED.getFireworkColor()));
    assertEquals(VariantDefaults.rules(), graph.rules());
    assertThrows(UnsupportedOperationException.class, () -> graph.items().clear());
  }
}
