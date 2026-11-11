package ca.emilybelnavis.mcmods.economy.card;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Public world/token references only. Every use still requires authoritative owner/status
 * validation.
 */
public final class CardStackIdentity {
  public static final String KEY = "economy_issued_card";

  public record Mark(UUID world, UUID token) {
    public Mark {
      Objects.requireNonNull(world);
      Objects.requireNonNull(token);
    }
  }

  private CardStackIdentity() {}

  public static ItemStack marked(ItemStack item, WorldId world, UUID token) {
    if (CardCatalog.find(item).isEmpty() || item.getCount() != 1)
      throw new IllegalArgumentException("CARD_ITEM");
    var stack = item.copy();
    var data = new CompoundTag();
    var marker = new CompoundTag();
    marker.putString("world", world.value().toString());
    marker.putString("token", Objects.requireNonNull(token).toString());
    data.put(KEY, marker);
    stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    return stack;
  }

  public static Optional<Mark> mark(ItemStack stack, WorldId world) {
    if (CardCatalog.find(stack).isEmpty() || stack.getCount() != 1) return Optional.empty();
    var data = stack.get(DataComponents.CUSTOM_DATA);
    // Read only the two scalar references; never clone arbitrary unrelated item NBT.
    return data == null ? Optional.empty() : parse(data.getUnsafe(), world);
  }

  /**
   * Detached bounded parser is also used by recognition tests; it confers no financial authority.
   */
  public static Optional<Mark> parse(CompoundTag data, WorldId expectedWorld) {
    if (data == null || data.size() != 1 || !data.contains(KEY, CompoundTag.TAG_COMPOUND))
      return Optional.empty();
    var marker = data.getCompound(KEY);
    if (marker.size() != 2
        || !marker.contains("world", CompoundTag.TAG_STRING)
        || !marker.contains("token", CompoundTag.TAG_STRING)) return Optional.empty();
    try {
      var world = canonical(marker.getString("world"));
      var token = canonical(marker.getString("token"));
      return world.equals(expectedWorld.value())
          ? Optional.of(new Mark(world, token))
          : Optional.empty();
    } catch (IllegalArgumentException malformed) {
      return Optional.empty();
    }
  }

  private static UUID canonical(String value) {
    if (value.length() != 36) throw new IllegalArgumentException("CARD_MARKER");
    var parsed = UUID.fromString(value);
    if (!parsed.toString().equals(value)) throw new IllegalArgumentException("CARD_MARKER");
    return parsed;
  }
}
