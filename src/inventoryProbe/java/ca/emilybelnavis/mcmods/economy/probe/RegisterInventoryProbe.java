package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.cash.RegisterInventory;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.PlayerDataStorage;

/**
 * Actual source replacement, hopper-like mutation, full-batch capacity and saved delivery replay.
 */
public final class RegisterInventoryProbe {
  private RegisterInventoryProbe() {}

  public static void run(MinecraftServer server) throws Exception {
    var level = server.overworld();
    var anchor = new BlockPos(32, 101, 32);
    level.getChunkAt(anchor);
    level.setBlock(anchor.below(), Blocks.CHEST.defaultBlockState(), 3);
    var chest = (ChestBlockEntity) level.getBlockEntity(anchor.below());
    var template = new ItemStack(Items.DIAMOND);
    template.set(DataComponents.CUSTOM_NAME, Component.literal("Exact register parcel"));
    chest.setItem(0, template.copyWithCount(5));
    RegisterInventory.initializeSource(server, "minecraft:overworld", anchor);
    var profile = new GameProfile(UUID.randomUUID(), "RegisterFixture");
    var buyer = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
    fill(buyer);
    buyer.getInventory().setItem(0, template.copyWithCount(63));
    var expected = RegisterInventory.snapshot(server, "minecraft:overworld", anchor, template, 4);
    denied(() -> RegisterInventory.reserve(server, buyer, expected, UUID.randomUUID()));
    require(chest.getItem(0).getCount() == 5, "part-batch space leaves stock intact");
    buyer.getInventory().setItem(0, ItemStack.EMPTY);
    var mismatch = template.copy();
    mismatch.set(DataComponents.CUSTOM_NAME, Component.literal("Different parcel"));
    var wrong = RegisterInventory.snapshot(server, "minecraft:overworld", anchor, mismatch, 4);
    denied(() -> RegisterInventory.reserve(server, buyer, wrong, UUID.randomUUID()));
    require(chest.getItem(0).getCount() == 5, "components must match exactly");
    chest.removeItem(0, 1);
    denied(() -> RegisterInventory.reserve(server, buyer, expected, UUID.randomUUID()));
    require(chest.getItem(0).getCount() == 4, "hopper-like mutation cannot reserve stale stock");
    chest.setItem(0, template.copyWithCount(5));
    var beforeReplacement =
        RegisterInventory.snapshot(server, "minecraft:overworld", anchor, template, 4);
    level.setBlock(anchor.below(), Blocks.AIR.defaultBlockState(), 3);
    level.setBlock(anchor.below(), Blocks.CHEST.defaultBlockState(), 3);
    chest = (ChestBlockEntity) level.getBlockEntity(anchor.below());
    chest.setItem(0, template.copyWithCount(5));
    RegisterInventory.initializeSource(server, "minecraft:overworld", anchor);
    denied(() -> RegisterInventory.reserve(server, buyer, beforeReplacement, UUID.randomUUID()));
    require(chest.getItem(0).getCount() == 5, "replacement cannot validate old generation");

    var current = RegisterInventory.snapshot(server, "minecraft:overworld", anchor, template, 4);
    var cancelled = UUID.randomUUID();
    var held = RegisterInventory.reserve(server, buyer, current, cancelled);
    require(
        held.available() == 1 && held.revision() == current.revision() + 1,
        "exact full stock reservation");
    require(
        RegisterInventory.returnToSource(server, held, cancelled),
        "cancel returns exact original goods");
    require(RegisterInventory.returnToSource(server, held, cancelled), "cancel replay");
    require(chest.getItem(0).getCount() == 5, "cancel cannot duplicate stock");
    level.save(null, true, false);
    require(
        savedChest(server, anchor.below())
            .getPersistentData()
            .getCompound("economy_register_reservations")
            .getCompound(cancelled.toString())
            .getString("state")
            .equals("RETURNED"),
        "saved cancellation restock");
    var cancellation =
        new ca.emilybelnavis.mcmods.economy.journal.Journal.Outcome(
            new ca.emilybelnavis.mcmods.economy.api.Identifiers.OperationId(UUID.randomUUID()),
            new ca.emilybelnavis.mcmods.economy.api.Identifiers.RequestId(UUID.randomUUID()),
            ca.emilybelnavis.mcmods.economy.journal.Journal.Status.COMMITTED,
            new ca.emilybelnavis.mcmods.economy.api.Identifiers.TransactionId(UUID.randomUUID()),
            new ca.emilybelnavis.mcmods.economy.api.Identifiers.JournalSequence(1),
            new ca.emilybelnavis.mcmods.economy.journal.Journal.Result(
                1, "SALE_CANCEL", cancelled.toString()));
    RegisterInventory.clearRestocked(server, held, cancelled, cancellation);
    require(
        !RegisterInventory.returnToSource(server, held, cancelled),
        "retired restock cannot repeat");
    current = RegisterInventory.snapshot(server, "minecraft:overworld", anchor, template, 4);
    var sale = UUID.randomUUID();
    held = RegisterInventory.reserve(server, buyer, current, sale);
    require(
        RegisterInventory.reservationMatches(server, held, sale),
        "held originals survive source mutation boundary");
    level.save(null, true, false);
    var savedSource = savedChest(server, anchor.below());
    require(savedSource.getItem(0).getCount() == 1, "persisted source excludes reserved goods");
    var savedHeld =
        savedSource
            .getPersistentData()
            .getCompound("economy_register_reservations")
            .getCompound(sale.toString());
    require(savedHeld.getString("state").equals("HELD"), "actual region file records held source");
    var savedOriginal =
        ItemStack.parseOptional(server.registryAccess(), savedHeld.getCompound("original"));
    require(
        savedOriginal.getCount() == 4
            && ItemStack.isSameItemSameComponents(savedOriginal, template),
        "actual region file preserves exact reserved originals");
    fill(buyer);
    var attempt = UUID.randomUUID();
    require(
        RegisterInventory.deliverOnce(server, buyer, held, sale, attempt)
            == RegisterInventory.Result.UNDELIVERED,
        "post-settlement space loss leaves full batch held");
    require(chest.getItem(0).getCount() == 1, "held paid goods are excluded from available stock");
    buyer.getInventory().setItem(0, ItemStack.EMPTY);
    require(
        RegisterInventory.deliverOnce(server, buyer, held, sale, attempt)
            == RegisterInventory.Result.DELIVERED,
        "full batch delivery");
    require(
        RegisterInventory.deliverOnce(server, buyer, held, sale, attempt)
            == RegisterInventory.Result.REPLAY,
        "delivery replay");
    var field = MinecraftServer.class.getDeclaredField("playerDataStorage");
    field.setAccessible(true);
    var storage = (PlayerDataStorage) field.get(server);
    storage.save(buyer);
    var restored = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
    require(storage.load(restored).isPresent(), "buyer data saved");
    require(
        RegisterInventory.deliverOnce(server, restored, held, sale, attempt)
            == RegisterInventory.Result.REPLAY,
        "saved replay cannot duplicate goods");
    require(
        restored.getInventory().getItem(0).getCount() == 4
            && ItemStack.isSameItemSameComponents(restored.getInventory().getItem(0), template),
        "saved exact components and batch");
    require(
        !RegisterInventory.returnToSource(server, held, sale),
        "paid delivered goods cannot be restocked");
    level.save(null, true, false);
    var retiredSource =
        savedChest(server, anchor.below())
            .getPersistentData()
            .getCompound("economy_register_reservations")
            .getCompound(sale.toString());
    require(
        retiredSource.getString("state").equals("DELIVERED") && !retiredSource.contains("original"),
        "actual region file retires delivered source originals");
    var terms =
        new ca.emilybelnavis.mcmods.economy.journal.Custody.Terms(
            new ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId(restored.getUUID()),
            "register-goods-v1|" + held.batch() + "\n" + held.template(),
            "register-stock:" + held.sourceGeneration() + ":" + sale,
            "fixture",
            sale
                + "\nplayer:"
                + restored.getUUID()
                + "\neconomy:register_sales\neconomy:commit\nfixture");
    var completed =
        new ca.emilybelnavis.mcmods.economy.journal.Custody.Case(
            sale,
            terms,
            ca.emilybelnavis.mcmods.economy.journal.Custody.State.DELIVERED,
            sale,
            sale,
            attempt,
            "fixture",
            "fixture",
            "fixture",
            ca.emilybelnavis.mcmods.economy.journal.Custody.Evidence.unknown(),
            "fixture",
            1,
            null);
    RegisterInventory.clearDelivered(server, restored, held, sale, completed);
    boolean blocked = false;
    try {
      RegisterInventory.deliverOnce(server, restored, held, sale, attempt);
    } catch (IllegalStateException retired) {
      blocked = true;
    }
    require(
        blocked && restored.getInventory().getItem(0).getCount() == 4,
        "pruned completion cannot recreate originals");
    System.out.println(
        "REGISTER_INVENTORY_PASS part capacity components hopper replacement cancellation full batch saved replay");
  }

  private static ChestBlockEntity savedChest(MinecraftServer server, BlockPos position)
      throws Exception {
    var type = net.minecraft.world.level.chunk.storage.RegionFileStorage.class;
    var constructor =
        type.getDeclaredConstructor(
            net.minecraft.world.level.chunk.storage.RegionStorageInfo.class,
            java.nio.file.Path.class,
            boolean.class);
    constructor.setAccessible(true);
    var info =
        new net.minecraft.world.level.chunk.storage.RegionStorageInfo(
            "register-fixture", net.minecraft.world.level.Level.OVERWORLD, "chunk");
    var storage =
        constructor.newInstance(
            info,
            server
                .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("region"),
            true);
    try {
      var chunk = storage.read(new net.minecraft.world.level.ChunkPos(position));
      require(chunk != null, "persisted source chunk");
      var entities = chunk.getList("block_entities", 10);
      for (int i = 0; i < entities.size(); i++) {
        var tag = entities.getCompound(i);
        if (tag.getInt("x") != position.getX()
            || tag.getInt("y") != position.getY()
            || tag.getInt("z") != position.getZ()) continue;
        var chest = new ChestBlockEntity(position, Blocks.CHEST.defaultBlockState());
        chest.loadWithComponents(tag, server.registryAccess());
        return chest;
      }
      throw new IllegalStateException("Source absent from actual region file");
    } finally {
      storage.close();
    }
  }

  private static void fill(ServerPlayer player) {
    for (int index = 0; index < 36; index++)
      player.getInventory().setItem(index, new ItemStack(Items.COBBLESTONE, 64));
  }

  private static void denied(Runnable operation) {
    boolean denied = false;
    try {
      operation.run();
    } catch (IllegalStateException expected) {
      denied = true;
    }
    require(denied, "unsafe stock operation must reject");
  }

  private static void require(boolean condition, String message) {
    if (!condition)
      throw new IllegalStateException("Register inventory fixture failed: " + message);
  }
}
