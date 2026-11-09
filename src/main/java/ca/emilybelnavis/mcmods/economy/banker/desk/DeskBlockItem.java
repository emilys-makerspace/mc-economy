package ca.emilybelnavis.mcmods.economy.banker.desk;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

/** Guarded multiblock placement; item NBT never supplies placement identity or satellite state. */
public final class DeskBlockItem extends BlockItem {
  public DeskBlockItem(Block block, Properties properties) {
    super(block, properties);
  }

  @Override
  public InteractionResult place(BlockPlaceContext original) {
    var context = updatePlacementContext(original);
    if (context == null
        || !getBlock().isEnabled(context.getLevel().enabledFeatures())
        || !context.canPlace()) return InteractionResult.FAIL;
    var state = getPlacementState(context);
    if (state == null || !placeBlock(context, state)) return InteractionResult.FAIL;
    var level = context.getLevel();
    var anchor = context.getClickedPos();
    var player = context.getPlayer();
    // Deliberately do not apply BLOCK_STATE or BLOCK_ENTITY_DATA from the item.
    state.getBlock().setPlacedBy(level, anchor, state, player, context.getItemInHand());
    if (player instanceof ServerPlayer serverPlayer)
      CriteriaTriggers.PLACED_BLOCK.trigger(serverPlayer, anchor, context.getItemInHand());
    var sound = state.getSoundType(level, anchor, player);
    level.playSound(
        player,
        anchor,
        getPlaceSound(state, level, anchor, player),
        SoundSource.BLOCKS,
        (sound.getVolume() + 1.0F) / 2.0F,
        sound.getPitch() * 0.8F);
    level.gameEvent(GameEvent.BLOCK_PLACE, anchor, GameEvent.Context.of(player, state));
    context.getItemInHand().consume(1, player);
    return InteractionResult.sidedSuccess(level.isClientSide);
  }

  @Override
  protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
    if (!(getBlock() instanceof DeskBlock wide)) return super.placeBlock(context, state);
    if (!wide.canPlaceAll(context, state)) return false;
    var level = context.getLevel();
    var anchor = context.getClickedPos();
    var cells = DeskGeometry.cells(anchor, state);
    var before = cells.stream().map(level::getBlockState).toList();
    DeskBlock.begin(level, anchor);
    boolean placed = false;
    try {
      for (int cell = 0; cell < 4; cell++) {
        if (!level.setBlock(
            cells.get(cell),
            state.setValue(DeskBlock.CELL, cell),
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) {
          for (int restore = cell; restore >= 0; restore--)
            level.setBlock(
                cells.get(restore),
                before.get(restore),
                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
          return false;
        }
      }
      placed = true;
    } catch (RuntimeException failure) {
      for (int restore = cells.size() - 1; restore >= 0; restore--)
        level.setBlock(
            cells.get(restore),
            before.get(restore),
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
      return false;
    } finally {
      DeskBlock.end(level, anchor);
    }
    if (placed) for (BlockPos position : cells) level.updateNeighborsAt(position, getBlock());
    return placed;
  }
}
