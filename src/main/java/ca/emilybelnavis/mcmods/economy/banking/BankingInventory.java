package ca.emilybelnavis.mcmods.economy.banking;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.cash.CashStackIdentity;
import ca.emilybelnavis.mcmods.economy.journal.Custody;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/**
 * Exact original-item custody for menu returns and deposit claims. Never creates goods from
 * templates.
 */
public final class BankingInventory {
  private static final String ORIGINALS = "economy_banking_originals";
  private static final String ATTEMPTS = "economy_banking_deliveries";
  private static final String DEPOSITS = "economy_deposit_originals";

  private BankingInventory() {}

  public static String encode(ServerPlayer player, ItemStack stack) {
    thread(player);
    if (stack.isEmpty() || stack.getCount() > 64)
      throw new IllegalArgumentException("BANKING_STACK");
    try {
      var tag = (CompoundTag) stack.save(player.registryAccess());
      NbtIo.write(
          tag, new DataOutputStream(new LimitedOutput(OutputStream.nullOutputStream(), 8192)));
      var bytes = new ByteArrayOutputStream();
      NbtIo.writeCompressed(tag, new LimitedOutput(bytes, 1536));
      String encoded = Base64.getEncoder().encodeToString(bytes.toByteArray());
      if (encoded.length() > 2048) throw new IllegalArgumentException("BANKING_COMPONENT_LIMIT");
      decode(player, encoded); // The same NBT accounting budget applies in both directions.
      return encoded;
    } catch (IOException failure) {
      throw new IllegalArgumentException("BANKING_COMPONENT_LIMIT", failure);
    }
  }

  public static ItemStack decode(ServerPlayer player, String encoded) {
    thread(player);
    if (encoded == null || encoded.isEmpty() || encoded.length() > 2048)
      throw new IllegalArgumentException("BANKING_COMPONENT_LIMIT");
    try {
      var bytes = Base64.getDecoder().decode(encoded);
      var tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(8192));
      var stack = ItemStack.parseOptional(player.registryAccess(), tag);
      if (stack.isEmpty() || stack.getCount() > 64)
        throw new IllegalArgumentException("BANKING_STACK");
      return stack;
    } catch (IOException | RuntimeException failure) {
      throw new IllegalArgumentException("BANKING_COMPONENTS", failure);
    }
  }

  public static boolean fits(ServerPlayer player, List<ItemStack> stacks) {
    thread(player);
    if (stacks.size() > 36) return false;
    var simulated = new ArrayList<ItemStack>(36);
    for (int index = 0; index < 36; index++)
      simulated.add(player.getInventory().getItem(index).copy());
    for (var stack : stacks) {
      if (stack.isEmpty() || stack.getCount() > 64) return false;
      var remaining = stack.copy();
      insert(simulated, remaining);
      if (!remaining.isEmpty()) return false;
    }
    return true;
  }

  public static List<ItemStack> depositClaimItems(ServerPlayer player, Custody.Case delivery) {
    thread(player);
    var fields = delivery.terms().items().split("[|]", -1);
    if (fields.length != 6 || !fields[0].equals("deposit-original-v1"))
      throw new IllegalArgumentException("BANKING_DEPOSIT_CLAIM");
    var world = new WorldId(UUID.fromString(fields[1]));
    var lot = UUID.fromString(fields[2]);
    int count = Integer.parseInt(fields[4]);
    if (count < 1 || count > 64) throw new IllegalArgumentException("BANKING_CLAIM_COUNT");
    byte[] componentBytes = Base64.getUrlDecoder().decode(fields[5]);
    if (componentBytes.length > 2048) throw new IllegalArgumentException("BANKING_COMPONENT_LIMIT");
    var original = decode(player, new String(componentBytes, StandardCharsets.UTF_8));
    if (original.getCount() < count) throw new IllegalArgumentException("BANKING_ORIGINAL_COUNT");
    var stack = original.copyWithCount(count);
    var claim =
        CashStackIdentity.claim(stack, world)
            .orElseThrow(() -> new IllegalArgumentException("BANKING_CASH_MARK"));
    if (!claim.lot().equals(lot) || !claim.item().equals(fields[3]))
      throw new IllegalArgumentException("BANKING_CASH_MARK");
    return List.of(stack);
  }

  public static boolean supports(Custody.Case delivery) {
    return (delivery.terms().items().startsWith("deposit-original-v1|")
            && delivery.terms().source().startsWith("deposit-return:"))
        || (delivery.terms().items().startsWith("menu-original-v1|")
            && delivery.terms().source().startsWith("menu-return:"));
  }

  /**
   * Reserve room for the next menu before accepting its inputs; existing originals are never
   * discarded.
   */
  public static boolean canStageInputs(ServerPlayer player) {
    thread(player);
    return player.getPersistentData().getCompound(ORIGINALS).size() <= 24;
  }

  /**
   * Close returns what fits to the 36 main slots, then protects every exact leftover under a fresh
   * ID.
   */
  public static List<UUID> stashInputs(ServerPlayer player, SimpleContainer inputs) {
    thread(player);
    if (inputs.getContainerSize() != 24) throw new IllegalArgumentException("BANKING_INPUT_COUNT");
    var stashes = player.getPersistentData().getCompound(ORIGINALS);
    var ids = new ArrayList<UUID>();
    for (int index = 0; index < 24; index++) {
      var original = inputs.getItem(index);
      if (original.isEmpty()) continue;
      var originalTag = (CompoundTag) original.save(player.registryAccess());
      var id = UUID.randomUUID();
      var held = new CompoundTag();
      held.put("stack", originalTag);
      held.putString("state", "STASHING");
      stashes.put(id.toString(), held);
      player.getPersistentData().put(ORIGINALS, stashes);
      var offered = inputs.removeItemNoUpdate(index);
      insertMain(player, offered);
      if (offered.isEmpty()) stashes.remove(id.toString());
      else {
        var remainder = originalTag.copy();
        remainder.putInt("count", offered.getCount());
        held.put("stack", remainder);
        held.putString("state", "HELD");
        ids.add(id);
      }
    }
    inputs.setChanged();
    player.getInventory().setChanged();
    return List.copyOf(ids);
  }

  public static ItemStack stashed(ServerPlayer player, UUID id) {
    thread(player);
    var held = player.getPersistentData().getCompound(ORIGINALS).getCompound(id.toString());
    return held.getString("state").equals("HELD")
        ? ItemStack.parseOptional(player.registryAccess(), held.getCompound("stack"))
        : ItemStack.EMPTY;
  }

  public static String menuClaimItems(ServerPlayer player, UUID id) {
    return "menu-original-v1|" + encode(player, stashed(player, id));
  }

  /**
   * Current journal admission and saved witnesses are supplied by the caller, never inferred from
   * these markers.
   */
  public static boolean deliverOnce(ServerPlayer player, Custody.Case delivery) {
    thread(player);
    authority(player, delivery, Custody.State.DELIVERING);
    if (delivery.deliveryAttempt() == null || !supports(delivery))
      throw new IllegalArgumentException("BANKING_CLAIM_TYPE");
    var attempts = player.getPersistentData().getCompound(ATTEMPTS);
    var key = delivery.id().toString();
    var signature = signature(delivery);
    if (attempts.contains(key)) {
      var marker = attempts.getCompound(key);
      if (!marker.hasUUID("attempt")
          || !marker.getUUID("attempt").equals(delivery.deliveryAttempt())
          || !marker.getString("signature").equals(signature))
        throw new IllegalStateException("BANKING_DELIVERY_ATTEMPT");
      if (!marker.getString("state").equals("DELIVERED"))
        throw new IllegalStateException("BANKING_DELIVERY_UNCERTAIN");
      return true;
    }
    var stacks =
        delivery.terms().items().startsWith("deposit-original-v1|")
            ? depositClaimItems(player, delivery)
            : List.of(
                decode(player, delivery.terms().items().substring("menu-original-v1|".length())));
    var original = heldSource(player, delivery, stacks.getFirst());
    if (attempts.size() >= 16 || !fits(player, stacks)) return false;
    var marker = new CompoundTag();
    marker.putUUID("attempt", delivery.deliveryAttempt());
    marker.putString("signature", signature);
    marker.putString("state", "DELIVERING");
    attempts.put(key, marker);
    player.getPersistentData().put(ATTEMPTS, attempts);
    var heldStack = ItemStack.parseOptional(player.registryAccess(), original.getCompound("stack"));
    heldStack.shrink(stacks.getFirst().getCount());
    original.put("stack", heldStack.saveOptional(player.registryAccess()));
    if (delivery.terms().source().startsWith("menu-return:"))
      original.putString("state", "CONSUMED");
    for (var stack : stacks) {
      var offered = stack.copy();
      insertMain(player, offered);
      if (!offered.isEmpty()) throw new IllegalStateException("BANKING_DELIVERY_UNCERTAIN");
    }
    marker.putString("state", "DELIVERED");
    player.getInventory().setChanged();
    return true;
  }

  private static CompoundTag heldSource(
      ServerPlayer player, Custody.Case delivery, ItemStack expected) {
    String source = delivery.terms().source();
    CompoundTag held;
    if (source.startsWith("deposit-return:")) {
      String[] parts = source.split(":", -1);
      if (parts.length != 3) throw new IllegalArgumentException("BANKING_CLAIM_SOURCE");
      UUID deposit = UUID.fromString(parts[1]);
      int slot = Integer.parseInt(parts[2]);
      if (slot < 0 || slot >= 24) throw new IllegalArgumentException("BANKING_CLAIM_SOURCE");
      held =
          player
              .getPersistentData()
              .getCompound(DEPOSITS)
              .getCompound(deposit.toString())
              .getCompound(Integer.toString(slot));
      if (held.getBoolean("consumed")) throw new IllegalStateException("BANKING_ORIGINAL_MISSING");
    } else {
      var id = UUID.fromString(source.substring("menu-return:".length()));
      held = player.getPersistentData().getCompound(ORIGINALS).getCompound(id.toString());
      if (!held.getString("state").equals("HELD"))
        throw new IllegalStateException("BANKING_ORIGINAL_MISSING");
    }
    var stack = ItemStack.parseOptional(player.registryAccess(), held.getCompound("stack"));
    if (!ItemStack.isSameItemSameComponents(stack, expected)
        || stack.getCount() < expected.getCount()
        || (source.startsWith("menu-return:") && stack.getCount() != expected.getCount()))
      throw new IllegalStateException("BANKING_ORIGINAL_MISMATCH");
    return held;
  }

  public static void clearDelivered(ServerPlayer player, Custody.Case delivery) {
    thread(player);
    authority(player, delivery, Custody.State.DELIVERED);
    var attempts = player.getPersistentData().getCompound(ATTEMPTS);
    var key = delivery.id().toString();
    if (!attempts.contains(key)) return;
    var marker = attempts.getCompound(key);
    if (!marker.hasUUID("attempt")
        || !marker.getUUID("attempt").equals(delivery.deliveryAttempt())
        || !marker.getString("signature").equals(signature(delivery))
        || !marker.getString("state").equals("DELIVERED"))
      throw new IllegalStateException("BANKING_DELIVERY_ATTEMPT");
    if (delivery.terms().source().startsWith("menu-return:")) {
      var id = UUID.fromString(delivery.terms().source().substring("menu-return:".length()));
      var stashes = player.getPersistentData().getCompound(ORIGINALS);
      if (!stashes.getCompound(id.toString()).getString("state").equals("CONSUMED"))
        throw new IllegalStateException("BANKING_ORIGINAL_MISMATCH");
      stashes.remove(id.toString());
    }
    attempts.remove(key);
    if (delivery.terms().source().startsWith("deposit-return:")) {
      var fields = delivery.terms().source().split(":", -1);
      try {
        ca.emilybelnavis.mcmods.economy.cash.DepositInventory.clearDelivered(
            player, UUID.fromString(fields[1]));
      } catch (IllegalStateException otherOriginalsRemain) {
        /* Other pending claims keep their originals protected. */
      }
    }
  }

  private static void authority(
      ServerPlayer player, Custody.Case delivery, Custody.State expected) {
    if (!delivery.terms().owner().equals(new PlayerId(player.getUUID()))
        || delivery.state() != expected)
      throw new IllegalArgumentException("BANKING_DELIVERY_AUTHORITY");
  }

  private static String signature(Custody.Case delivery) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      (delivery.terms().source() + "\n" + delivery.terms().items())
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void insertMain(ServerPlayer player, ItemStack offered) {
    var main = new ArrayList<ItemStack>(36);
    for (int index = 0; index < 36; index++) main.add(player.getInventory().getItem(index));
    insert(main, offered);
    for (int index = 0; index < 36; index++) player.getInventory().setItem(index, main.get(index));
  }

  private static void insert(List<ItemStack> inventory, ItemStack offered) {
    for (var existing : inventory) {
      if (offered.isEmpty()) return;
      if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, offered)) {
        int amount =
            Math.min(
                offered.getCount(),
                Math.max(0, Math.min(64, existing.getMaxStackSize()) - existing.getCount()));
        existing.grow(amount);
        offered.shrink(amount);
      }
    }
    for (int index = 0; index < inventory.size() && !offered.isEmpty(); index++)
      if (inventory.get(index).isEmpty()) {
        int amount = Math.min(offered.getCount(), Math.min(64, offered.getMaxStackSize()));
        inventory.set(index, offered.copyWithCount(amount));
        offered.shrink(amount);
      }
  }

  private static void thread(ServerPlayer player) {
    if (!player.server.isSameThread()) throw new IllegalStateException("Requires server thread");
  }

  private static final class LimitedOutput extends FilterOutputStream {
    private int remaining;

    LimitedOutput(OutputStream output, int limit) {
      super(output);
      remaining = limit;
    }

    @Override
    public void write(int value) throws IOException {
      if (--remaining < 0) throw new IOException("NBT limit");
      out.write(value);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      if (length > remaining) throw new IOException("NBT limit");
      remaining -= length;
      out.write(bytes, offset, length);
    }
  }
}
