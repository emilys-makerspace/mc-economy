package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.banking.BankingController;
import ca.emilybelnavis.mcmods.economy.banking.BankingMenu;
import ca.emilybelnavis.mcmods.economy.terminal.TerminalBlockEntity;
import ca.emilybelnavis.mcmods.economy.terminal.TerminalCatalog;
import ca.emilybelnavis.mcmods.economy.terminal.TerminalLifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Disposable fixture access through a real teller, without creating banking credentials. */
final class FixtureBankingTerminal {
  private FixtureBankingTerminal() {}

  /** Retry on a later tick while normal terminal registration completes. */
  static boolean open(ServerPlayer player, BlockPos anchor) {
    var level = player.serverLevel();
    level.setChunkForced(anchor.getX() >> 4, anchor.getZ() >> 4, true);
    level.getChunkAt(anchor);
    player.teleportTo(anchor.getX() + 3, anchor.getY(), anchor.getZ() - 2);
    if (!(level.getBlockEntity(anchor) instanceof TerminalBlockEntity)) {
      for (var position :
          BlockPos.betweenClosed(anchor.offset(-2, -1, -2), anchor.offset(2, -1, 2)))
        level.setBlockAndUpdate(position, Blocks.STONE.defaultBlockState());
      player.setYRot(Direction.SOUTH.toYRot());
      var previous = player.getMainHandItem();
      var stack = new ItemStack(TerminalCatalog.get("teller").item().get());
      player.setItemInHand(InteractionHand.MAIN_HAND, stack);
      var hit =
          new BlockHitResult(
              Vec3.atCenterOf(anchor.below()).add(0, .5, 0), Direction.UP, anchor.below(), false);
      try {
        if (!((BlockItem) stack.getItem())
            .place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit))
            .consumesAction()) throw new IllegalStateException("Could not place fixture teller");
      } finally {
        player.setItemInHand(InteractionHand.MAIN_HAND, previous);
      }
    }
    if (!(level.getBlockEntity(anchor) instanceof TerminalBlockEntity entity)
        || !TerminalLifecycle.matches(level, anchor, entity.generation())) return false;
    BankingController.open(player, anchor);
    return player.containerMenu instanceof BankingMenu menu && !menu.remote() && menu.valid(player);
  }
}
