package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.cash.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import com.mojang.authlib.GameProfile;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.PlayerDataStorage;

/** Actual full-batch inventory admission and saved delivery-attempt replay. */
public final class WithdrawalInventoryProbe {
  private WithdrawalInventoryProbe() {}

  public static void run(MinecraftServer server) throws Exception {
    var profile = new GameProfile(UUID.randomUUID(), "WithdrawFixture");
    var player =
        new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
    var owner = new PlayerId(profile.getId());
    var world = new WorldId(UUID.randomUUID());
    var reservation = UUID.randomUUID();
    var operation = UUID.randomUUID();
    var attempt = UUID.randomUUID();
    var lots =
        List.of(
            new CashIssuance.Lot(
                world,
                UUID.randomUUID(),
                "economy:cad_bill_20",
                1,
                1,
                CashIssuance.State.ACTIVE,
                1,
                "fixture"),
            new CashIssuance.Lot(
                world,
                UUID.randomUUID(),
                "economy:cad_bill_50",
                1,
                1,
                CashIssuance.State.ACTIVE,
                1,
                "fixture"));
    var original =
        new Journal.Intent(
            new OperationId(operation),
            new RequestId(UUID.randomUUID()),
            owner,
            new Journal.Kind("economy:withdraw_fixture", "economy:commit"),
            new Journal.CanonicalRequest(1, Map.of("reservation", reservation.toString())),
            null,
            null);
    var terms =
        new Custody.Terms(
            owner,
            WithdrawalDelivery.items(lots),
            "fixture",
            Journal.actorKey(owner),
            Custody.financialIntent(original));
    var delivery =
        new Custody.Case(
            reservation,
            terms,
            Custody.State.DELIVERING,
            reservation,
            operation,
            attempt,
            null,
            null,
            null,
            Custody.Evidence.unknown(),
            "fixture",
            1,
            null);
    for (int index = 0; index < 36; index++)
      player.getInventory().setItem(index, new ItemStack(Items.COBBLESTONE, 64));
    player.getInventory().setItem(0, ItemStack.EMPTY);
    require(!WithdrawalInventory.fits(player, 2), "entire batch must fit");
    require(
        WithdrawalInventory.deliverOnce(player, delivery) == WithdrawalInventory.Result.UNDELIVERED,
        "capacity loss leaves every cash item undelivered");
    require(player.getInventory().getItem(0).isEmpty(), "no partial batch insertion");
    player.getInventory().setItem(1, ItemStack.EMPTY);
    require(WithdrawalInventory.fits(player, 2), "full batch capacity");
    require(
        WithdrawalInventory.deliverOnce(player, delivery) == WithdrawalInventory.Result.DELIVERED,
        "delivery");
    require(
        WithdrawalInventory.deliverOnce(player, delivery) == WithdrawalInventory.Result.REPLAY,
        "same attempt replay");
    var field = MinecraftServer.class.getDeclaredField("playerDataStorage");
    field.setAccessible(true);
    var storage = (PlayerDataStorage) field.get(server);
    storage.save(player);
    var restored =
        new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
    require(storage.load(restored).isPresent(), "saved player");
    require(
        WithdrawalInventory.deliverOnce(restored, delivery) == WithdrawalInventory.Result.REPLAY,
        "saved attempt replay");
    int total = 0;
    for (int index = 0; index < 36; index++)
      if (CashStackIdentity.mark(restored.getInventory().getItem(index), world).isPresent())
        total += restored.getInventory().getItem(index).getCount();
    require(total == 2, "exact batch count after saved replay");
    System.out.println(
        "WITHDRAWAL_INVENTORY_PASS full batch capacity loss saved replay exact count");
  }

  private static void require(boolean condition, String message) {
    if (!condition)
      throw new IllegalStateException("Withdrawal inventory fixture failed: " + message);
  }
}
