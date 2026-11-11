package ca.emilybelnavis.mcmods.economy.banker.desk;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.*;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/** Two-wide desk; the anchor is the lower left cell seen from the customer side. */
public final class DeskGeometry {
  private DeskGeometry() {}

  public static BlockPos cell(BlockPos anchor, Direction facing, int cell) {
    if (cell < 0 || cell > 3 || !facing.getAxis().isHorizontal())
      throw new IllegalArgumentException("Desk cell");
    return anchor.relative(facing.getClockWise(), cell & 1).above(cell >> 1);
  }

  public static List<BlockPos> cells(BlockPos anchor, BlockState state) {
    var facing = state.getValue(DeskBlock.FACING);
    return List.of(
        cell(anchor, facing, 0),
        cell(anchor, facing, 1),
        cell(anchor, facing, 2),
        cell(anchor, facing, 3));
  }

  public static BlockPos anchor(BlockPos position, BlockState state) {
    int cell = state.getValue(DeskBlock.CELL);
    return position
        .relative(state.getValue(DeskBlock.FACING).getCounterClockWise(), cell & 1)
        .below(cell >> 1);
  }

  public static BlockPos standing(BlockPos anchor, Direction facing) {
    return anchor.relative(facing.getOpposite());
  }

  public static boolean supported(LevelReader level, BlockPos anchor, Direction facing) {
    for (int cell = 0; cell < 2; cell++) {
      var floor = cell(anchor, facing, cell).below();
      if (!level.hasChunkAt(floor)
          || !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) return false;
    }
    return true;
  }

  public static boolean matches(LevelReader level, BlockPos anchor, UUID generation) {
    if (!level.hasChunkAt(anchor)
        || !(level.getBlockEntity(anchor) instanceof DeskBlockEntity entity)
        || !entity.validIdentity()
        || !entity.generation().equals(generation)) return false;
    var state = level.getBlockState(anchor);
    if (!(state.getBlock() instanceof DeskBlock) || state.getValue(DeskBlock.CELL) != 0)
      return false;
    for (int cell = 0; cell < 4; cell++) {
      var pos = cell(anchor, state.getValue(DeskBlock.FACING), cell);
      if (!level.hasChunkAt(pos)
          || !level.getBlockState(pos).equals(state.setValue(DeskBlock.CELL, cell))) return false;
    }
    return supported(level, anchor, state.getValue(DeskBlock.FACING));
  }
}
