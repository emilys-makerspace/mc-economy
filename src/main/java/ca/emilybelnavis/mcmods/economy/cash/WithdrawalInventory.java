package ca.emilybelnavis.mcmods.economy.cash;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Server-thread full-batch admission and saved attempt markers for committed cash delivery. */
public final class WithdrawalInventory {
  private static final String ATTEMPTS = "economy_withdrawal_attempts";

  public enum Result {
    DELIVERED,
    UNDELIVERED,
    REPLAY
  }

  private WithdrawalInventory() {}

  /**
   * Newly issued lot components require fresh stack space. No stack is charged against armor slots.
   */
  public static boolean fits(ServerPlayer player, int stacks) {
    thread(player);
    int empty = 0;
    for (int index = 0; index < 36; index++)
      if (player.getInventory().getItem(index).isEmpty()) empty++;
    return stacks > 0
        && stacks <= empty
        && player.getPersistentData().getCompound(ATTEMPTS).size() < 16;
  }

  /**
   * Invoke only with the current admitted custody attempt after the financial outcome is resolved.
   */
  public static Result deliverOnce(ServerPlayer player, Custody.Case delivery) {
    thread(player);
    if (!delivery.terms().owner().equals(new PlayerId(player.getUUID()))
        || delivery.state() != Custody.State.DELIVERING
        || delivery.deliveryAttempt() == null)
      throw new IllegalArgumentException("WITHDRAW_DELIVERY_AUTHORITY");
    var attempts = player.getPersistentData().getCompound(ATTEMPTS);
    var key = delivery.id().toString();
    if (attempts.contains(key)) {
      var prior = attempts.getCompound(key);
      if (!prior.getUUID("attempt").equals(delivery.deliveryAttempt()))
        throw new IllegalStateException("WITHDRAW_DELIVERY_ATTEMPT");
      return prior.getBoolean("delivered") ? Result.REPLAY : Result.UNDELIVERED;
    }
    var claims = WithdrawalDelivery.claims(delivery);
    var stacks = new ArrayList<ItemStack>();
    for (var claim : claims) {
      var denomination = CashCatalog.byId(claim.item()).orElseThrow();
      stacks.add(
          CashStackIdentity.marked(
              new ItemStack(denomination.item().get(), claim.count()), claim.world(), claim.lot()));
    }
    if (!fits(player, stacks.size())) return Result.UNDELIVERED;
    for (var stack : stacks) {
      player.getInventory().add(stack);
      if (!stack.isEmpty()) throw new IllegalStateException("WITHDRAW_DELIVERY_UNCERTAIN");
    }
    var marker = new CompoundTag();
    marker.putUUID("attempt", delivery.deliveryAttempt());
    marker.putBoolean("delivered", true);
    attempts.put(key, marker);
    player.getPersistentData().put(ATTEMPTS, attempts);
    return Result.DELIVERED;
  }

  /** Release completed markers only after verified save and a current terminal custody result. */
  public static void clearDelivered(ServerPlayer player, Custody.Case delivery) {
    thread(player);
    if (!delivery.terms().owner().equals(new PlayerId(player.getUUID()))
        || delivery.state() != Custody.State.DELIVERED)
      throw new IllegalArgumentException("WITHDRAW_DELIVERY_AUTHORITY");
    var attempts = player.getPersistentData().getCompound(ATTEMPTS);
    attempts.remove(delivery.id().toString());
    player.getPersistentData().put(ATTEMPTS, attempts);
  }

  private static void thread(ServerPlayer player) {
    if (!player.server.isSameThread()) throw new IllegalStateException("Requires server thread");
  }
}
