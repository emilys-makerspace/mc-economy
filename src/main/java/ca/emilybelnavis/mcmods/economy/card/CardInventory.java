package ca.emilybelnavis.mcmods.economy.card;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.Custody;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Non-atomic inventory effect follows the saved A5 custody attempt; uncertainty must quarantine.
 */
public final class CardInventory {
  private static final String ATTEMPTS = "economy_card_delivery_attempts";

  public enum Result {
    DELIVERED,
    UNDELIVERED,
    REPLAY
  }

  public record Delivery(WorldId world, UUID token, String item) {}

  private CardInventory() {}

  public static boolean fits(ServerPlayer player) {
    thread(player);
    if (!player.isAlive()
        || player.server.getPlayerList().getPlayer(player.getUUID()) != player
        || player.getPersistentData().getCompound(ATTEMPTS).size() >= 16) return false;
    for (int index = 0; index < 36; index++)
      if (player.getInventory().getItem(index).isEmpty()) return true;
    return false;
  }

  /**
   * Decode only the committed protected payload; none of these fields is supplied by a client
   * packet.
   */
  public static Delivery decode(Custody.Case delivery) {
    String payload = delivery.terms().items();
    if (payload.length() > 256) throw new IllegalArgumentException("CARD_DELIVERY_FORMAT");
    var lines = payload.split("\n", -1);
    if (lines.length != 2 || !lines[0].equals("issued-card-v1"))
      throw new IllegalArgumentException("CARD_DELIVERY_FORMAT");
    var fields = lines[1].split("\\|", -1);
    if (fields.length != 3) throw new IllegalArgumentException("CARD_DELIVERY_FORMAT");
    var world = new WorldId(canonical(fields[0]));
    var token = canonical(fields[1]);
    String item =
        CardDesigns.legacyAliases()
            .getOrDefault(
                fields[2], CreditCardDesigns.legacyAliases().getOrDefault(fields[2], fields[2]));
    if ((CardDesigns.byId(item).isEmpty() && CreditCardDesigns.byId(item).isEmpty())
        || !delivery.id().equals(token)
        || !token.equals(delivery.financialOperation())
        || !delivery.terms().source().equals("issued-card:" + token)
        || !delivery.terms().destination().equals(Journal.actorKey(delivery.terms().owner())))
      throw new IllegalArgumentException("CARD_DELIVERY_BINDING");
    return new Delivery(world, token, item);
  }

  public static Result deliverOnce(
      ServerPlayer player, WorldId expectedWorld, Custody.Case delivery) {
    thread(player);
    if (player.server.getPlayerList().getPlayer(player.getUUID()) != player
        || !delivery.terms().owner().equals(new PlayerId(player.getUUID()))
        || delivery.state() != Custody.State.DELIVERING
        || delivery.deliveryAttempt() == null)
      throw new IllegalArgumentException("CARD_DELIVERY_AUTHORITY");
    var decoded = decode(delivery);
    if (!decoded.world().equals(expectedWorld))
      throw new IllegalArgumentException("CARD_DELIVERY_WORLD");
    var attempts = player.getPersistentData().getCompound(ATTEMPTS);
    var key = delivery.id().toString();
    if (attempts.contains(key)) {
      var prior = attempts.getCompound(key);
      if (!prior.hasUUID("attempt")
          || !prior.hasUUID("world")
          || !prior.getUUID("attempt").equals(delivery.deliveryAttempt())
          || !prior.getUUID("world").equals(expectedWorld.value()))
        throw new IllegalStateException("CARD_DELIVERY_ATTEMPT");
      return prior.getBoolean("delivered") ? Result.REPLAY : Result.UNDELIVERED;
    }
    if (!fits(player)) return Result.UNDELIVERED;
    var entry = CardCatalog.byId(decoded.item()).orElseThrow();
    var stack =
        CardStackIdentity.marked(new ItemStack(entry.item().get()), expectedWorld, decoded.token());
    player.getInventory().add(stack);
    if (!stack.isEmpty()) throw new IllegalStateException("CARD_DELIVERY_UNCERTAIN");
    var marker = new CompoundTag();
    marker.putUUID("attempt", delivery.deliveryAttempt());
    marker.putUUID("world", expectedWorld.value());
    marker.putBoolean("delivered", true);
    attempts.put(key, marker);
    player.getPersistentData().put(ATTEMPTS, attempts);
    return Result.DELIVERED;
  }

  /**
   * Caller must have verified the player save and re-read current DELIVERED custody before cleanup.
   */
  public static void clearDelivered(
      ServerPlayer player, WorldId expectedWorld, Custody.Case delivery) {
    thread(player);
    if (player.server.getPlayerList().getPlayer(player.getUUID()) != player
        || !delivery.terms().owner().equals(new PlayerId(player.getUUID()))
        || delivery.state() != Custody.State.DELIVERED
        || !decode(delivery).world().equals(expectedWorld))
      throw new IllegalArgumentException("CARD_DELIVERY_AUTHORITY");
    var attempts = player.getPersistentData().getCompound(ATTEMPTS);
    attempts.remove(delivery.id().toString());
    player.getPersistentData().put(ATTEMPTS, attempts);
  }

  private static UUID canonical(String text) {
    if (text.length() != 36) throw new IllegalArgumentException("CARD_DELIVERY_UUID");
    var id = UUID.fromString(text);
    if (!id.toString().equals(text)) throw new IllegalArgumentException("CARD_DELIVERY_UUID");
    return id;
  }

  private static void thread(ServerPlayer player) {
    if (!player.server.isSameThread()) throw new IllegalStateException("Requires server thread");
  }
}
