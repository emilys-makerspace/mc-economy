package ca.emilybelnavis.mcmods.economy.valuation;

import com.google.gson.*;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.*;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Bounded typed vanilla stack projection. Native custody remains responsible for exact original
 * stacks.
 */
public final class VariantIdentity {
  private VariantIdentity() {}

  private static final Set<String> PATCHES =
      Set.of(
          "repair_cost",
          "damage",
          "max_damage",
          "enchantments",
          "stored_enchantments",
          "custom_name",
          "enchantment_glint_override",
          "container",
          "potion_contents",
          "bucket_entity_data",
          "bees",
          "pot_decorations",
          "fireworks",
          "firework_explosion",
          "suspicious_stew_effects",
          "writable_book_content",
          "written_book_content",
          "map_id",
          "dyed_color",
          "base_color",
          "banner_patterns",
          "ominous_bottle_amplifier");
  private static final Set<String> BUCKET =
      Set.of(
          "BucketVariantTag",
          "Variant",
          "Age",
          "HuntingCooldown",
          "Health",
          "NoAI",
          "Silent",
          "NoGravity",
          "Glowing",
          "Invulnerable");
  private static final Set<String> BEE =
      Set.of(
          "id",
          "Health",
          "Age",
          "ForcedAge",
          "InLove",
          "LoveCause",
          "HasNectar",
          "HasStung",
          "AngerTime",
          "AngryAt",
          "flower_pos",
          "PersistenceRequired",
          "NoAI",
          "Silent",
          "Invulnerable",
          "Glowing",
          "CustomName",
          "CustomNameVisible",
          "Tags",
          "Attributes",
          "AbsorptionAmount",
          "active_effects",
          "TicksFrozen");
  private static final Set<String> FLAGS =
      Set.of(
          "NoAI",
          "Silent",
          "NoGravity",
          "Glowing",
          "Invulnerable",
          "HasNectar",
          "HasStung",
          "PersistenceRequired",
          "CustomNameVisible");

  private static final class Work {
    int contained, nodes;

    void node() {
      if (++nodes > 16_384) throw bad("IDENTITY_WORK_LIMIT");
    }
  }

  public static String components(ItemStack stack, MinecraftServer server) {
    if (!server.isSameThread())
      throw new IllegalStateException("Stack capture requires server thread");
    return components(stack);
  }

  static String components(ItemStack stack) {
    var value = project(stack, new Work(), 0);
    String text = canonical(value, 0).toString();
    if (ValuationConfig.bytes(text) > 65_536) throw bad("IDENTITY_BYTE_LIMIT");
    return text;
  }

  private static JsonObject project(ItemStack stack, Work work, int containers) {
    work.node();
    if (stack.isEmpty()) throw bad("EMPTY_STACK_IDENTITY");
    String id = itemId(stack);
    if (!ValuationData.catalog().entries().containsKey(id))
      throw bad("UNREGISTERED_COMPONENT_ADAPTER");
    VariantCapture.requirePrototype(stack.getItem());
    for (var type : stack.getPrototype().keySet()) {
      var key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
      if (key == null
          || !key.getNamespace().equals("minecraft")
          || key.getPath().equals("custom_data")) throw bad("UNKNOWN_PROTOTYPE_COMPONENT");
    }
    var patched = new HashSet<String>();
    for (var entry : stack.getComponentsPatch().entrySet()) {
      var key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.getKey());
      if (key == null
          || !key.getNamespace().equals("minecraft")
          || !PATCHES.contains(key.getPath())
          || entry.getValue().isEmpty()) throw bad("UNKNOWN_OR_REMOVED_COMPONENT");
      patched.add(key.getPath());
    }
    var out = new JsonObject();
    Integer maximum = stack.get(DataComponents.MAX_DAMAGE),
        damage = stack.get(DataComponents.DAMAGE);
    if (maximum != null) {
      if (!Objects.equals(maximum, stack.getPrototype().get(DataComponents.MAX_DAMAGE)))
        throw bad("ALTERED_MAX_DAMAGE");
      out.addProperty("minecraft:max_damage", maximum);
      out.addProperty("minecraft:damage", damage == null ? 0 : damage);
    } else if (damage != null || patched.contains("damage"))
      out.addProperty("minecraft:damage", damage == null ? 0 : damage);
    var applied = stack.get(DataComponents.ENCHANTMENTS);
    if (applied != null && (!applied.isEmpty() || patched.contains("enchantments")))
      out.add("minecraft:enchantments", enchants(applied));
    var stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
    if (stored != null) out.add("minecraft:stored_enchantments", enchants(stored));
    var name = stack.get(DataComponents.CUSTOM_NAME);
    if (name != null) out.add("minecraft:custom_name", literal(name));
    var repair = stack.get(DataComponents.REPAIR_COST);
    if (repair != null && patched.contains("repair_cost")) {
      if (repair < 0 || repair > 1_000_000) throw bad("REPAIR_COST_BOUND");
      out.addProperty("minecraft:repair_cost", repair);
    }
    var glint = stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
    if (glint != null) out.addProperty("minecraft:enchantment_glint_override", glint);
    var contents = stack.get(DataComponents.CONTAINER);
    if (contents != null) {
      int slots = VariantCapture.slots(id);
      if (slots == 0) throw bad("CONTAINER_NOT_APPLICABLE");
      int next = containers + 1;
      if (next > 8) throw bad("CONTAINER_DEPTH_LIMIT");
      if (contents.getSlots() > slots) throw bad("CONTAINER_SLOT_LIMIT");
      var values = new JsonArray();
      for (int slot = 0; slot < contents.getSlots(); slot++) {
        var child = contents.getStackInSlot(slot);
        if (child.isEmpty()) continue;
        if (++work.contained > 4_096) throw bad("CONTAINER_VISIT_LIMIT");
        VariantCapture.requirePrototype(child.getItem());
        int max = child.getPrototype().getOrDefault(DataComponents.MAX_STACK_SIZE, 64);
        if (child.getCount() < 1 || child.getCount() > max) throw bad("CONTAINED_STACK_COUNT");
        var item = new JsonObject();
        item.addProperty("id", itemId(child));
        item.addProperty("count", child.getCount());
        item.add("components", project(child, work, next));
        var entry = new JsonObject();
        entry.addProperty("slot", slot);
        entry.add("item", item);
        values.add(entry);
      }
      out.add("minecraft:container", values);
    }
    var potion = stack.get(DataComponents.POTION_CONTENTS);
    if (potion != null) {
      if (!potion.customEffects().isEmpty() || potion.customColor().isPresent())
        throw bad("CUSTOM_POTION_COMPONENTS");
      var value = new JsonObject();
      value.addProperty(
          "potion", key(potion.potion().orElseThrow(() -> bad("ABSENT_POTION_IDENTITY"))));
      out.add("minecraft:potion_contents", value);
    }
    var bucket = stack.get(DataComponents.BUCKET_ENTITY_DATA);
    if (bucket != null)
      out.add("minecraft:bucket_entity_data", entity(raw(bucket), BUCKET, false, work));
    var bees = stack.get(DataComponents.BEES);
    if (bees != null) {
      if (bees.size() > 3) throw bad("BEE_COUNT_LIMIT");
      var values = new JsonArray();
      for (var bee : bees) {
        if (bee.ticksInHive() < 0 || bee.minTicksInHive() < 0) throw bad("BEE_TIMELINE");
        var value = new JsonObject();
        value.add("entity_data", entity(raw(bee.entityData()), BEE, true, work));
        value.addProperty("ticks_in_hive", bee.ticksInHive());
        value.addProperty("min_ticks_in_hive", bee.minTicksInHive());
        values.add(value);
      }
      out.add("minecraft:bees", values);
    }
    var decorations = stack.get(DataComponents.POT_DECORATIONS);
    if (decorations != null) {
      var values = new JsonArray();
      for (var item : decorations.ordered())
        values.add(BuiltInRegistries.ITEM.getKey(item).toString());
      out.add("minecraft:pot_decorations", values);
    }
    var explosion = stack.get(DataComponents.FIREWORK_EXPLOSION);
    if (explosion != null) out.add("minecraft:firework_explosion", explosion(explosion));
    var fireworks = stack.get(DataComponents.FIREWORKS);
    if (fireworks != null) {
      if (fireworks.explosions().size() > 7) throw bad("FIREWORK_STAR_LIMIT");
      var value = new JsonObject();
      value.addProperty("flight_duration", fireworks.flightDuration());
      var values = new JsonArray();
      fireworks.explosions().forEach(e -> values.add(explosion(e)));
      value.add("explosions", values);
      out.add("minecraft:fireworks", value);
    }
    var stew = stack.get(DataComponents.SUSPICIOUS_STEW_EFFECTS);
    if (stew != null) {
      if (stew.effects().size() > 16) throw bad("STEW_EFFECT_LIMIT");
      var values = new JsonArray();
      for (var effect : stew.effects()) {
        var value = new JsonObject();
        value.addProperty("id", key(effect.effect()));
        value.addProperty("duration", effect.duration());
        values.add(value);
      }
      out.add("minecraft:suspicious_stew_effects", values);
    }
    var writable = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
    if (writable != null) {
      if (writable.pages().size() > 100) throw bad("BOOK_PAGE_LIMIT");
      var value = new JsonObject();
      var pages = new JsonArray();
      writable.pages().forEach(p -> pages.add(filterString(p, 1024)));
      value.add("pages", pages);
      out.add("minecraft:writable_book_content", value);
    }
    var written = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
    if (written != null) {
      if (written.pages().size() > 100) throw bad("BOOK_PAGE_LIMIT");
      var value = new JsonObject();
      value.add("title", filterString(written.title(), 32));
      value.addProperty("author", bounded(written.author(), 256));
      value.addProperty("generation", written.generation());
      value.addProperty("resolved", written.resolved());
      var pages = new JsonArray();
      for (var page : written.pages()) {
        var p = new JsonObject();
        p.add("raw", literal(page.raw()));
        page.filtered().ifPresent(v -> p.add("filtered", literal(v)));
        pages.add(p);
      }
      value.add("pages", pages);
      out.add("minecraft:written_book_content", value);
    }
    var map = stack.get(DataComponents.MAP_ID);
    if (map != null) out.addProperty("minecraft:map_id", map.id());
    var dyed = stack.get(DataComponents.DYED_COLOR);
    if (dyed != null) {
      var value = new JsonObject();
      value.addProperty("rgb", dyed.rgb());
      value.addProperty("show_in_tooltip", dyed.showInTooltip());
      out.add("minecraft:dyed_color", value);
    }
    var base = stack.get(DataComponents.BASE_COLOR);
    if (base != null) out.addProperty("minecraft:base_color", base.getName());
    var patterns = stack.get(DataComponents.BANNER_PATTERNS);
    if (patterns != null) {
      if (patterns.layers().size() > 16) throw bad("BANNER_LAYER_LIMIT");
      var values = new JsonArray();
      for (var layer : patterns.layers()) {
        var value = new JsonObject();
        value.addProperty("pattern", key(layer.pattern()));
        value.addProperty("color", layer.color().getName());
        values.add(value);
      }
      out.add("minecraft:banner_patterns", values);
    }
    var amplifier = stack.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER);
    if (amplifier != null) out.addProperty("minecraft:ominous_bottle_amplifier", amplifier);
    return out;
  }

  private static JsonObject enchants(ItemEnchantments value) {
    if (value.getClass() != ItemEnchantments.class || value.size() > 42)
      throw bad("CUSTOM_ENCHANTMENT_DATA");
    var result = new JsonObject();
    var levels = new JsonObject();
    for (var entry : value.entrySet()) levels.addProperty(key(entry.getKey()), entry.getIntValue());
    result.add("levels", levels);
    result.addProperty("show_in_tooltip", value.equals(value.withTooltip(true)));
    return result;
  }

  private static JsonObject literal(Component value) {
    if (value.getClass() != MutableComponent.class
        || !value.getStyle().isEmpty()
        || !value.getSiblings().isEmpty()) throw bad("UNSUPPORTED_TEXT_COMPONENT");
    var contents = value.getContents();
    String text;
    if (contents == PlainTextContents.EMPTY) text = "";
    else if (contents.getClass() == PlainTextContents.LiteralContents.class)
      text = ((PlainTextContents.LiteralContents) contents).text();
    else throw bad("UNSUPPORTED_TEXT_CONTENTS");
    var result = new JsonObject();
    result.addProperty("text", bounded(text, 16_384));
    return result;
  }

  private static JsonObject filterString(Filterable<String> value, int limit) {
    var result = new JsonObject();
    result.addProperty("raw", bounded(value.raw(), limit));
    value.filtered().ifPresent(s -> result.addProperty("filtered", bounded(s, limit)));
    return result;
  }

  private static JsonObject explosion(FireworkExplosion value) {
    if (!Set.of(
            FireworkExplosion.Shape.SMALL_BALL,
            FireworkExplosion.Shape.LARGE_BALL,
            FireworkExplosion.Shape.STAR,
            FireworkExplosion.Shape.CREEPER,
            FireworkExplosion.Shape.BURST)
        .contains(value.shape())) throw bad("CUSTOM_FIREWORK_SHAPE");
    String shape = value.shape().getSerializedName();
    if (value.colors().size() > 8 || value.fadeColors().size() > 8)
      throw bad("FIREWORK_COLOR_LIMIT");
    var result = new JsonObject();
    result.addProperty("shape", shape);
    var colors = new JsonArray();
    value.colors().forEach((int i) -> colors.add(i));
    var fade = new JsonArray();
    value.fadeColors().forEach((int i) -> fade.add(i));
    result.add("colors", colors);
    result.add("fade_colors", fade);
    result.addProperty("has_trail", value.hasTrail());
    result.addProperty("has_twinkle", value.hasTwinkle());
    return result;
  }

  private static JsonObject entity(CompoundTag tag, Set<String> allowed, boolean bee, Work work) {
    if (tag.getClass() != CompoundTag.class || !allowed.containsAll(tag.getAllKeys()))
      throw bad("UNSUPPORTED_ENTITY_DATA_KEY");
    Tag identity = tag.get("id");
    if (bee
        && (identity == null
            || identity.getClass() != StringTag.class
            || !((StringTag) identity).getAsString().equals("minecraft:bee")))
      throw bad("NON_BEE_CONTENT");
    var result = new JsonObject();
    for (String name : tag.getAllKeys()) {
      Tag value = tag.get(name);
      if (FLAGS.contains(name)) {
        if (!(value instanceof ByteTag b) || b.getAsByte() < 0 || b.getAsByte() > 1)
          throw bad("ENTITY_FLAG_TYPE");
        result.addProperty(name, ((ByteTag) value).getAsByte() != 0);
      } else result.add(name, nbt(value, work, 0));
    }
    return result;
  }

  private static CompoundTag raw(CustomData value) {
    CompoundTag tag = value.getUnsafe();
    if (tag.getClass() != CompoundTag.class) throw bad("CUSTOM_ENTITY_COMPOUND");
    return tag;
  }

  private static JsonElement nbt(Tag value, Work work, int depth) {
    work.node();
    if (depth > 16) throw bad("ENTITY_DATA_DEPTH");
    if (value != null && value.getClass() == StringTag.class)
      return new JsonPrimitive(bounded(((StringTag) value).getAsString(), 4096));
    if (value != null
        && Set.of(
                ByteTag.class,
                ShortTag.class,
                IntTag.class,
                LongTag.class,
                FloatTag.class,
                DoubleTag.class)
            .contains(value.getClass())) {
      NumericTag number = (NumericTag) value;
      double d = number.getAsDouble();
      if (!Double.isFinite(d) || Math.abs(d) > 1e12) throw bad("ENTITY_NUMBER_RANGE");
      return new JsonPrimitive(number.getAsNumber());
    }
    if (value != null && value.getClass() == CompoundTag.class) {
      CompoundTag compound = (CompoundTag) value;
      if (compound.size() > 256) throw bad("ENTITY_FIELD_LIMIT");
      var result = new JsonObject();
      for (String key : compound.getAllKeys()) {
        bounded(key, 128);
        result.add(key, nbt(compound.get(key), work, depth + 1));
      }
      return result;
    }
    if (value != null && value.getClass() == ListTag.class) {
      ListTag list = (ListTag) value;
      if (list.size() > 256) throw bad("ENTITY_LIST_LIMIT");
      var result = new JsonArray();
      for (Tag child : list) result.add(nbt(child, work, depth + 1));
      return result;
    }
    if (value instanceof IntArrayTag array) {
      if (array.getAsIntArray().length > 256) throw bad("ENTITY_ARRAY_LIMIT");
      var result = new JsonArray();
      for (int n : array.getAsIntArray()) result.add(n);
      return result;
    }
    if (value instanceof LongArrayTag array) {
      if (array.getAsLongArray().length > 256) throw bad("ENTITY_ARRAY_LIMIT");
      var result = new JsonArray();
      for (long n : array.getAsLongArray()) result.add(n);
      return result;
    }
    if (value instanceof ByteArrayTag array) {
      if (array.getAsByteArray().length > 256) throw bad("ENTITY_ARRAY_LIMIT");
      var result = new JsonArray();
      for (byte n : array.getAsByteArray()) result.add(n);
      return result;
    }
    throw bad("CUSTOM_ENTITY_TAG");
  }

  private static JsonElement canonical(JsonElement value, int depth) {
    if (depth > 64) throw bad("IDENTITY_JSON_DEPTH");
    if (value.isJsonObject()) {
      var result = new JsonObject();
      var keys = new TreeSet<>(value.getAsJsonObject().keySet());
      for (String key : keys)
        result.add(key, canonical(value.getAsJsonObject().get(key), depth + 1));
      return result;
    }
    if (value.isJsonArray()) {
      var result = new JsonArray();
      for (JsonElement child : value.getAsJsonArray()) result.add(canonical(child, depth + 1));
      return result;
    }
    return value.deepCopy();
  }

  private static String key(Holder<?> holder) {
    if (holder.getClass() != Holder.Reference.class) throw bad("UNREVIEWED_HOLDER");
    return holder.unwrapKey().orElseThrow().location().toString();
  }

  private static String itemId(ItemStack stack) {
    return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
  }

  private static String bounded(String value, int limit) {
    if (value.length() > limit) throw bad("IDENTITY_TEXT_LIMIT");
    return value;
  }

  private static IllegalArgumentException bad(String reason) {
    return new IllegalArgumentException(reason);
  }
}
