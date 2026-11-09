package ca.emilybelnavis.mcmods.economy.probe;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Checks actual region-file persistence and bounded observations of known chunk locations. */
public final class CustodyChunkInventoryProbe {
  private static final BlockPos SOURCE = new BlockPos(4096, 100, 4096);
  private static final BlockPos MOVED = SOURCE.offset(2, 0, 0);
  private static final UUID MARKER = UUID.fromString("7d769d1b-46f4-4016-96cc-f42d03ff07a3");

  private CustodyChunkInventoryProbe() {}

  public static void prepare(MinecraftServer server) {
    var level = server.overworld();
    level.getChunkAt(SOURCE);
    level.setBlock(SOURCE, Blocks.CHEST.defaultBlockState(), 3);
    level.setBlock(MOVED, Blocks.CHEST.defaultBlockState(), 3);
    var chest = (ChestBlockEntity) level.getBlockEntity(SOURCE);
    var stack = new ItemStack(Items.EMERALD, 5);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal("Saved custody chunk"));
    chest.setItem(0, stack);
    chest.getPersistentData().putUUID("custody", MARKER);
    chest.setChanged();
    // The server's normal shutdown writes this actual chunk. Restart verifies its contents.
    System.out.println("CUSTODY_CHUNK_PHASE1_PREPARED actual chest chunk awaiting normal save");
  }

  public static void verifyRestartAndMove(MinecraftServer server) {
    require(knownLoadedChest(server, SOURCE).isEmpty(), "far chunk should begin unavailable");
    require(
        server.overworld().getChunkSource().getChunkNow(SOURCE.getX() >> 4, SOURCE.getZ() >> 4)
            == null,
        "bounded inspection must not load an unavailable chunk");
    server.overworld().getChunkAt(SOURCE);
    var source = knownLoadedChest(server, SOURCE).orElseThrow();
    require(
        MARKER.equals(source.getPersistentData().getUUID("custody")),
        "persisted chest custody marker");
    var item = source.getItem(0);
    require(item.is(Items.EMERALD) && item.getCount() == 5, "persisted chest item/count");
    require(
        Component.literal("Saved custody chunk").equals(item.get(DataComponents.CUSTOM_NAME)),
        "persisted chest component");
    var destination = knownLoadedChest(server, MOVED).orElseThrow();
    destination.setItem(0, source.removeItemNoUpdate(0));
    source.setChanged();
    destination.setChanged();
    require(source.getItem(0).isEmpty(), "known original location changed");
    require(destination.getItem(0).getCount() == 5, "moved items still exist");
    require(
        MARKER.equals(source.getPersistentData().getUUID("custody")),
        "a marker alone cannot prove current item contents");
    System.out.println(
        "CUSTODY_CHUNK_PHASE2_PASS actual region save/reload unavailable chunk and moved items");
  }

  private static Optional<ChestBlockEntity> knownLoadedChest(
      MinecraftServer server, BlockPos position) {
    var chunk =
        server.overworld().getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
    if (chunk == null) return Optional.empty();
    var entity = chunk.getBlockEntity(position);
    return entity instanceof ChestBlockEntity chest ? Optional.of(chest) : Optional.empty();
  }

  private static void require(boolean condition, String reason) {
    if (!condition) throw new IllegalStateException("Custody chunk fixture failed: " + reason);
  }
}
