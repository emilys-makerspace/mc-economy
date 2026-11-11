package ca.emilybelnavis.mcmods.economy.valuation;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.WitherRoseBlock;

/**
 * Authorized-thread capture of vanilla prototypes and declarative enchantment/flower membership.
 */
public final class VariantCapture {
  private VariantCapture() {}

  public static VariantGraph.Graph capture(MinecraftServer server) {
    if (!server.isSameThread())
      throw new IllegalStateException("Variant capture requires server thread");
    return capture(server.registryAccess());
  }

  static VariantGraph.Graph capture(HolderLookup.Provider registries) {
    var catalog = ValuationData.catalog().entries();
    var items = new TreeMap<String, VariantGraph.Item>();
    for (String id : catalog.keySet()) {
      Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
      try {
        requirePrototype(item);
      } catch (IllegalArgumentException unsupported) {
        continue;
      }
      var defaults = item.components();
      var keys = new TreeSet<String>();
      boolean supported = true;
      for (var type : defaults.keySet()) {
        var key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
        if (key == null
            || !key.getNamespace().equals("minecraft")
            || key.getPath().equals("custom_data")) {
          supported = false;
          break;
        }
        keys.add(key.toString());
      }
      if (!supported) continue;
      int damage = defaults.getOrDefault(DataComponents.MAX_DAMAGE, 0);
      int stack = defaults.getOrDefault(DataComponents.MAX_STACK_SIZE, 64);
      items.put(id, new VariantGraph.Item(id, damage, stack, slots(id), keys));
    }
    var registry = registries.lookupOrThrow(Registries.ENCHANTMENT);
    var enchants = new TreeMap<String, VariantGraph.Enchant>();
    for (JsonElement row : premiums()) {
      var object = row.getAsJsonObject();
      String id = object.get("id").getAsString();
      Enchantment enchantment =
          registry
              .get(ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.parse(id)))
              .map(Holder::value)
              .orElse(null);
      if (enchantment == null || enchantment.getClass() != Enchantment.class)
        throw new IllegalArgumentException("Missing pinned enchantment " + id);
      if (!plainSet(enchantment.definition().supportedItems())
          || !plainSet(enchantment.exclusiveSet())) continue;
      var members = new TreeSet<String>();
      enchantment.definition().supportedItems().stream()
          .forEach(
              h -> {
                String key = key(h);
                if (catalog.containsKey(key)) members.add(key);
              });
      var exclusive = new TreeSet<String>();
      enchantment.exclusiveSet().stream().forEach(h -> exclusive.add(key(h)));
      var amounts = new ArrayList<String>();
      object.getAsJsonArray("premiums").forEach(v -> amounts.add(v.getAsString()));
      enchants.put(
          id,
          new VariantGraph.Enchant(
              id,
              enchantment.definition().maxLevel(),
              members,
              exclusive,
              amounts,
              object.get("curse").getAsBoolean()));
    }
    var flowers = new TreeMap<String, List<VariantGraph.StewEffect>>();
    for (String id : catalog.keySet()) {
      var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
      if (item.getClass() != BlockItem.class) continue;
      var block = ((BlockItem) item).getBlock();
      if (block.getClass() != FlowerBlock.class && block.getClass() != WitherRoseBlock.class)
        continue;
      var effects = new ArrayList<VariantGraph.StewEffect>();
      for (var effect : ((FlowerBlock) block).getSuspiciousEffects().effects())
        effects.add(new VariantGraph.StewEffect(key(effect.effect()), effect.duration()));
      flowers.put(id, List.copyOf(effects));
    }
    var colors = new TreeMap<Integer, String>();
    for (DyeColor dye : DyeColor.values())
      colors.put(dye.getFireworkColor(), "minecraft:" + dye.getName() + "_dye");
    return new VariantGraph.Graph(
        1,
        VariantGraph.ADAPTER,
        VariantGraph.RULES_HASH,
        items,
        enchants,
        VariantDefaults.rules(),
        flowers,
        colors);
  }

  static void requirePrototype(Item item) {
    try {
      if (item == null || item.getClass().getMethod("components").getDeclaringClass() != Item.class)
        throw new IllegalArgumentException("Custom item prototype callback");
    } catch (ReflectiveOperationException error) {
      throw new IllegalArgumentException("Unreviewed item prototype", error);
    }
  }

  static int slots(String id) {
    String path = id.substring(id.indexOf(':') + 1);
    if (path.equals("shulker_box")
        || path.endsWith("_shulker_box")
        || Set.of("chest", "trapped_chest", "barrel").contains(path)) return 27;
    return switch (path) {
      case "hopper" -> 5;
      case "furnace", "blast_furnace", "smoker" -> 3;
      case "dispenser", "dropper", "crafter" -> 9;
      case "brewing_stand" -> 5;
      case "chiseled_bookshelf" -> 6;
      case "decorated_pot" -> 1;
      default -> 0;
    };
  }

  private static JsonArray premiums() {
    try (var input =
        VariantCapture.class.getResourceAsStream("/data/economy/valuation/enchantments.json")) {
      if (input == null) throw new IllegalStateException("Missing approved premiums");
      byte[] data = input.readNBytes(1_048_577);
      if (data.length > 1_048_576) throw new IllegalArgumentException("Premium data limit");
      var result = ValuationConfig.parse(ValuationConfig.utf8(data), 1_048_576).getAsJsonArray();
      if (result.size() != 42) throw new IllegalArgumentException("Pinned premium inventory");
      return result;
    } catch (IOException error) {
      throw new IllegalStateException("Cannot read approved premiums", error);
    }
  }

  private static boolean plainSet(HolderSet<?> value) {
    return value.getClass() == HolderSet.Named.class || value.getClass() == HolderSet.Direct.class;
  }

  private static String key(Holder<?> holder) {
    if (holder.getClass() != Holder.Reference.class)
      throw new IllegalArgumentException("Unreviewed registry holder");
    return holder.unwrapKey().orElseThrow().location().toString();
  }
}
