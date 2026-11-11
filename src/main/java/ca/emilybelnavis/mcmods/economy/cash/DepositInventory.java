package ca.emilybelnavis.mcmods.economy.cash;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.journal.CashIssuance;
import ca.emilybelnavis.mcmods.economy.journal.Deposits;
import java.io.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/** Server-thread physical boundary for an admitted deposit session. */
public final class DepositInventory {
  private static final String RESERVATIONS = "economy_deposit_originals";

  public record Returned(int delivered, int remaining, boolean replay) {}

  private DepositInventory() {}

  public static List<Deposits.Slot> snapshot(
      ServerPlayer player, SimpleContainer inputs, WorldId world) {
    thread(player);
    if (inputs.getContainerSize() != 24) throw new IllegalArgumentException("DEPOSIT_SLOT_COUNT");
    var slots = new ArrayList<Deposits.Slot>();
    int bytes = 0;
    for (int index = 0; index < 24; index++) {
      var stack = inputs.getItem(index);
      if (stack.isEmpty()) continue;
      var encoded = encode(player, stack);
      boolean unmarked = !stack.has(CashStackIdentity.CASH_LOT.get());
      CashIssuance.Claim claim;
      if (unmarked) {
        var denomination =
            CashCatalog.find(stack)
                .orElseThrow(() -> new IllegalArgumentException("DEPOSIT_DENOMINATION"));
        var identity =
            UUID.nameUUIDFromBytes(
                ("economy:unmarked-cash:"
                        + world.value()
                        + ":"
                        + player.getUUID()
                        + ":"
                        + index
                        + ":"
                        + encoded)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        claim =
            new CashIssuance.Claim(
                world, identity, "economy:" + denomination.id(), stack.getCount());
      } else {
        claim =
            CashStackIdentity.claim(stack, world)
                .orElseThrow(() -> new IllegalArgumentException("DEPOSIT_WORLD"));
      }
      bytes = Math.addExact(bytes, encoded.length());
      if (bytes > Deposits.MAX_COMPONENT_BYTES)
        throw new IllegalArgumentException("DEPOSIT_COMPONENT_BUDGET");
      slots.add(new Deposits.Slot(index, claim, encoded, unmarked));
    }
    if (slots.isEmpty()) throw new IllegalArgumentException("DEPOSIT_SLOT_COUNT");
    return List.copyOf(slots);
  }

  /**
   * After journal reservation, persist these originals with the player before admitting settlement.
   */
  public static void reserve(
      ServerPlayer player,
      SimpleContainer inputs,
      WorldId world,
      UUID deposit,
      List<Deposits.Slot> originals) {
    thread(player);
    var reservations = player.getPersistentData().getCompound(RESERVATIONS);
    if (reservations.contains(deposit.toString()))
      throw new IllegalStateException("DEPOSIT_ALREADY_RESERVED");
    if (reservations.size() >= 16) throw new IllegalStateException("DEPOSIT_PENDING_LIMIT");
    if (!snapshot(player, inputs, world).equals(originals))
      throw new IllegalStateException("DEPOSIT_INPUT_CHANGED");
    var held = new CompoundTag();
    for (var slot : originals) {
      var entry = new CompoundTag();
      entry.put("stack", inputs.getItem(slot.index()).save(player.registryAccess()));
      held.put(Integer.toString(slot.index()), entry);
    }
    reservations.put(deposit.toString(), held);
    player.getPersistentData().put(RESERVATIONS, reservations);
    for (var slot : originals) inputs.removeItemNoUpdate(slot.index());
    inputs.setChanged();
  }

  /** After durable return BEGIN, insert what fits and retain the rest with the player save. */
  public static Returned returnOnce(
      ServerPlayer player, UUID deposit, int slot, UUID attempt, int admitted) {
    thread(player);
    Objects.requireNonNull(attempt);
    if (slot < 0 || slot >= 24 || admitted < 1 || admitted > 64)
      throw new IllegalArgumentException("DEPOSIT_RETURN_REQUEST");
    var reservations = player.getPersistentData().getCompound(RESERVATIONS);
    var held = reservations.getCompound(deposit.toString());
    var key = Integer.toString(slot);
    if (!held.contains(key)) throw new IllegalStateException("DEPOSIT_ORIGINAL_MISSING");
    var entry = held.getCompound(key);
    if (entry.hasUUID("attempt")) {
      if (!entry.getUUID("attempt").equals(attempt))
        throw new IllegalStateException("DEPOSIT_RETURN_ATTEMPT");
      return new Returned(entry.getInt("delivered"), entry.getInt("remaining"), true);
    }
    var original = ItemStack.parseOptional(player.registryAccess(), entry.getCompound("stack"));
    if (original.isEmpty() || original.getCount() < admitted)
      throw new IllegalStateException("DEPOSIT_ORIGINAL_MISSING");
    var offered = original.copyWithCount(admitted);
    player.getInventory().add(offered);
    int delivered = admitted - offered.getCount();
    original.shrink(delivered);
    entry.put("stack", original.saveOptional(player.registryAccess()));
    entry.putUUID("attempt", attempt);
    entry.putInt("delivered", delivered);
    entry.putInt("remaining", original.getCount());
    held.put(key, entry);
    reservations.put(deposit.toString(), held);
    player.getPersistentData().put(RESERVATIONS, reservations);
    return new Returned(delivered, original.getCount(), false);
  }

  /** Retire only originals included in a successful, resolved financial commit. */
  public static void consumeCommitted(
      ServerPlayer player, UUID deposit, List<Deposits.Slot> payable) {
    thread(player);
    var reservations = player.getPersistentData().getCompound(RESERVATIONS);
    if (!reservations.contains(deposit.toString()))
      throw new IllegalStateException("DEPOSIT_ORIGINAL_MISSING");
    var held = reservations.getCompound(deposit.toString());
    for (var slot : payable) {
      var entry = held.getCompound(Integer.toString(slot.index()));
      if (entry.getBoolean("consumed")) continue;
      var stack = ItemStack.parseOptional(player.registryAccess(), entry.getCompound("stack"));
      if (stack.isEmpty() || !encode(player, stack).equals(slot.components()))
        throw new IllegalStateException("DEPOSIT_INPUT_CHANGED");
    }
    for (var slot : payable) {
      var entry = held.getCompound(Integer.toString(slot.index()));
      entry.put("stack", new CompoundTag());
      entry.putBoolean("consumed", true);
    }
    player.getPersistentData().put(RESERVATIONS, reservations);
  }

  /** Call after a matching saved completion, and only if all originals were returned. */
  public static void clearDelivered(ServerPlayer player, UUID deposit) {
    thread(player);
    var reservations = player.getPersistentData().getCompound(RESERVATIONS);
    var held = reservations.getCompound(deposit.toString());
    for (var key : held.getAllKeys())
      if (!ItemStack.parseOptional(
              player.registryAccess(), held.getCompound(key).getCompound("stack"))
          .isEmpty()) throw new IllegalStateException("DEPOSIT_ORIGINALS_REMAIN");
    reservations.remove(deposit.toString());
    player.getPersistentData().put(RESERVATIONS, reservations);
  }

  private static String encode(ServerPlayer player, ItemStack stack) {
    try {
      var output = new ByteArrayOutputStream();
      NbtIo.writeCompressed((CompoundTag) stack.save(player.registryAccess()), output);
      var encoded = Base64.getEncoder().encodeToString(output.toByteArray());
      if (encoded.length() > 2048) throw new IllegalArgumentException("DEPOSIT_COMPONENT_BUDGET");
      return encoded;
    } catch (IOException error) {
      throw new IllegalStateException("DEPOSIT_SERIALIZATION", error);
    }
  }

  private static void thread(ServerPlayer player) {
    if (!player.server.isSameThread()) throw new IllegalStateException("Requires server thread");
  }
}
