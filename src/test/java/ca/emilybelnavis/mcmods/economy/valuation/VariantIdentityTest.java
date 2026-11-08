package ca.emilybelnavis.mcmods.economy.valuation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VariantIdentityTest {
    @BeforeAll static void bootstrap() {
        if (net.neoforged.fml.loading.LoadingModList.get() == null)
            net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static JsonObject identity(ItemStack stack) {
        return JsonParser.parseString(VariantIdentity.components(stack)).getAsJsonObject();
    }
    private static void refuses(String reason, ItemStack stack) {
        assertEquals(reason, assertThrows(IllegalArgumentException.class,
                () -> VariantIdentity.components(stack)).getMessage());
    }

    @Test void containersKeepSlotsCountsAndCanonicalChildPropertiesWithoutMutatingStacks() {
        var sword = new ItemStack(Items.IRON_SWORD);
        sword.set(DataComponents.DAMAGE, 125);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Keepsake"));
        sword.set(DataComponents.REPAIR_COST, 3);
        sword.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        var original = sword.copy();
        var chest = new ItemStack(Items.CHEST);
        chest.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(
                List.of(ItemStack.EMPTY, sword, new ItemStack(Items.DIAMOND, 2))));
        var entries = identity(chest).getAsJsonArray("minecraft:container");
        assertEquals(2, entries.size());
        assertEquals(1, entries.get(0).getAsJsonObject().get("slot").getAsInt());
        var capturedSword = entries.get(0).getAsJsonObject().getAsJsonObject("item");
        assertEquals("minecraft:iron_sword", capturedSword.get("id").getAsString());
        var properties = capturedSword.getAsJsonObject("components");
        assertEquals(125, properties.get("minecraft:damage").getAsInt());
        assertEquals(250, properties.get("minecraft:max_damage").getAsInt());
        assertEquals("Keepsake", properties.getAsJsonObject("minecraft:custom_name").get("text").getAsString());
        assertEquals(3, properties.get("minecraft:repair_cost").getAsInt());
        assertEquals(2, entries.get(1).getAsJsonObject().getAsJsonObject("item").get("count").getAsInt());
        assertTrue(ItemStack.isSameItemSameComponents(original, sword));
        assertEquals(VariantIdentity.components(chest), VariantIdentity.components(chest.copy()));
        assertEquals(List.of("minecraft:custom_name", "minecraft:damage", "minecraft:enchantment_glint_override",
                "minecraft:max_damage", "minecraft:repair_cost"), properties.keySet().stream().toList());
    }

    @Test void booksFireworksAndBeeDataRetainTypedIdentity() {
        var writable = new ItemStack(Items.WRITABLE_BOOK);
        writable.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(
                List.of(new Filterable<>("original page", Optional.of("filtered page")))));
        var page = identity(writable).getAsJsonObject("minecraft:writable_book_content")
                .getAsJsonArray("pages").get(0).getAsJsonObject();
        assertEquals("original page", page.get("raw").getAsString());
        assertEquals("filtered page", page.get("filtered").getAsString());
        var written = new ItemStack(Items.WRITTEN_BOOK);
        written.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                new Filterable<>("Title", Optional.empty()), "Author", 1,
                List.of(new Filterable<>(Component.literal("Page"), Optional.of(Component.literal("Safe page")))), true));
        var book = identity(written).getAsJsonObject("minecraft:written_book_content");
        assertEquals("Author", book.get("author").getAsString());
        assertEquals("Safe page", book.getAsJsonArray("pages").get(0).getAsJsonObject()
                .getAsJsonObject("filtered").get("text").getAsString());
        var star = new FireworkExplosion(FireworkExplosion.Shape.STAR, IntList.of(0xff0000), IntList.of(0x0000ff), true, true);
        var rocket = new ItemStack(Items.FIREWORK_ROCKET);
        rocket.set(DataComponents.FIREWORKS, new Fireworks(2, List.of(star)));
        var fireworks = identity(rocket).getAsJsonObject("minecraft:fireworks");
        assertEquals(2, fireworks.get("flight_duration").getAsInt());
        assertEquals("star", fireworks.getAsJsonArray("explosions").get(0).getAsJsonObject().get("shape").getAsString());
        var bee = new CompoundTag();
        bee.putString("id", "minecraft:bee");
        bee.putBoolean("HasNectar", true);
        bee.putFloat("Health", 8.5f);
        var hive = new ItemStack(Items.BEEHIVE);
        hive.set(DataComponents.BEES, List.of(new BeehiveBlockEntity.Occupant(CustomData.of(bee), 5, 2400)));
        var occupant = identity(hive).getAsJsonArray("minecraft:bees").get(0).getAsJsonObject();
        assertTrue(occupant.getAsJsonObject("entity_data").get("HasNectar").getAsBoolean());
        assertEquals(8.5, occupant.getAsJsonObject("entity_data").get("Health").getAsDouble());
        assertEquals(5, occupant.get("ticks_in_hive").getAsInt());
    }

    @Test void unsafeOrExcessiveNativeDataCannotProduceAValuationIdentity() {
        refuses("EMPTY_STACK_IDENTITY", ItemStack.EMPTY);
        var custom = new ItemStack(Items.DIAMOND);
        custom.set(DataComponents.CUSTOM_DATA, CustomData.of(new CompoundTag()));
        refuses("UNKNOWN_OR_REMOVED_COMPONENT", custom);
        var removed = new ItemStack(Items.IRON_SWORD);
        removed.remove(DataComponents.MAX_DAMAGE);
        refuses("UNKNOWN_OR_REMOVED_COMPONENT", removed);
        var altered = new ItemStack(Items.IRON_SWORD);
        altered.set(DataComponents.MAX_DAMAGE, 999);
        refuses("ALTERED_MAX_DAMAGE", altered);
        var styled = new ItemStack(Items.DIAMOND);
        styled.set(DataComponents.CUSTOM_NAME, Component.literal("styled").withStyle(net.minecraft.ChatFormatting.BOLD));
        refuses("UNSUPPORTED_TEXT_COMPONENT", styled);
        var translated = new ItemStack(Items.DIAMOND);
        translated.set(DataComponents.CUSTOM_NAME, Component.translatable("item.minecraft.diamond"));
        refuses("UNSUPPORTED_TEXT_CONTENTS", translated);
        var chest = new ItemStack(Items.CHEST);
        chest.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 65))));
        refuses("CONTAINED_STACK_COUNT", chest);
        var nested = new ItemStack(Items.CHEST);
        for (int i = 0; i < 9; i++) {
            var outer = new ItemStack(Items.CHEST);
            outer.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(nested)));
            nested = outer;
        }
        refuses("CONTAINER_DEPTH_LIMIT", nested);
        var fish = new ItemStack(Items.COD_BUCKET);
        var unknown = new CompoundTag(); unknown.putString("Command", "not admissible");
        fish.set(DataComponents.BUCKET_ENTITY_DATA, CustomData.of(unknown));
        refuses("UNSUPPORTED_ENTITY_DATA_KEY", fish);
    }

    @Test void cosmeticAndContentVariantsKeepTheirDistinguishingProperties() {
        var armor = new ItemStack(Items.LEATHER_CHESTPLATE);
        armor.set(DataComponents.DYED_COLOR, new DyedItemColor(0x123456, false));
        var color = identity(armor).getAsJsonObject("minecraft:dyed_color");
        assertEquals(0x123456, color.get("rgb").getAsInt());
        assertFalse(color.get("show_in_tooltip").getAsBoolean());
        var shield = new ItemStack(Items.SHIELD);
        shield.set(DataComponents.BASE_COLOR, DyeColor.BLUE);
        assertEquals("blue", identity(shield).get("minecraft:base_color").getAsString());
        var map = new ItemStack(Items.FILLED_MAP);
        map.set(DataComponents.MAP_ID, new MapId(37));
        assertEquals(37, identity(map).get("minecraft:map_id").getAsInt());
        var omen = new ItemStack(Items.OMINOUS_BOTTLE);
        omen.set(DataComponents.OMINOUS_BOTTLE_AMPLIFIER, 4);
        assertEquals(4, identity(omen).get("minecraft:ominous_bottle_amplifier").getAsInt());
        assertEquals(4, identity(new ItemStack(Items.DECORATED_POT)).getAsJsonArray("minecraft:pot_decorations").size());
        var axolotl = new ItemStack(Items.AXOLOTL_BUCKET);
        var data = new CompoundTag(); data.putInt("Variant", 3); data.putBoolean("NoAI", false);
        axolotl.set(DataComponents.BUCKET_ENTITY_DATA, CustomData.of(data));
        var bucket = identity(axolotl).getAsJsonObject("minecraft:bucket_entity_data");
        assertEquals(3, bucket.get("Variant").getAsInt());
        assertFalse(bucket.get("NoAI").getAsBoolean());
    }
}
