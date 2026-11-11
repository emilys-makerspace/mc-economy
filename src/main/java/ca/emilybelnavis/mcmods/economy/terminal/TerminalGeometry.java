package ca.emilybelnavis.mcmods.economy.terminal;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/** Geometry helpers preserve existing four-cell terminals and the vertical two-cell kiosk. */
public final class TerminalGeometry {
  private TerminalGeometry() {}

  public static BlockPos cell(BlockPos anchor, Direction facing, int cell) {
    if (cell < 0 || cell > 3 || !facing.getAxis().isHorizontal())
      throw new IllegalArgumentException("Terminal cell");
    return anchor.relative(facing.getCounterClockWise(), cell & 1).above(cell >> 1);
  }

  public static List<BlockPos> cells(BlockPos anchor, Direction facing) {
    return List.of(
        cell(anchor, facing, 0),
        cell(anchor, facing, 1),
        cell(anchor, facing, 2),
        cell(anchor, facing, 3));
  }

  public static List<BlockPos> cells(BlockPos anchor, BlockState state) {
    if (!(state.getBlock() instanceof WideTerminalBlock wide)) return List.of(anchor.immutable());
    var result = new java.util.ArrayList<BlockPos>(wide.cellCount(state));
    for (int cell = 0; cell < wide.cellCount(state); cell++)
      result.add(wide.cellPosition(anchor, state, cell));
    return List.copyOf(result);
  }

  public static BlockPos anchor(BlockPos position, BlockState state) {
    return state.getBlock() instanceof WideTerminalBlock wide
        ? wide.anchorPosition(position, state)
        : position.immutable();
  }

  public static BlockPos resolveAnchor(LevelReader level, BlockPos position) {
    return anchor(position, level.getBlockState(position));
  }

  /** A failed match proves damage only when the complete footprint and support can be observed. */
  public static boolean fullyLoaded(LevelReader level, BlockPos anchor) {
    if (!level.hasChunkAt(anchor)) return false;
    for (var position : cells(anchor, level.getBlockState(anchor))) {
      if (!level.hasChunkAt(position) || !level.hasChunkAt(position.below())) return false;
    }
    return true;
  }

  public static boolean matches(LevelReader level, BlockPos anchor, UUID generation) {
    if (!level.hasChunkAt(anchor)
        || !(level.getBlockEntity(anchor) instanceof TerminalBlockEntity entity)
        || !entity.generation().equals(generation)
        || !entity.validIdentity()
        || entity.geometryPending()) return false;
    var state = level.getBlockState(anchor);
    if (state.getBlock() instanceof RegisterBlock) return true;
    if (!(state.getBlock() instanceof WideTerminalBlock wide)
        || state.getValue(wide.cellProperty()) != 0) return false;
    var facing = state.getValue(WideTerminalBlock.FACING);
    for (int cell = 0; cell < wide.cellCount(state); cell++) {
      var position = wide.cellPosition(anchor, state, cell);
      if (!level.hasChunkAt(position)) return false;
      var part = level.getBlockState(position);
      if (!wide.sameGeometry(state, part)
          || part.getValue(WideTerminalBlock.FACING) != facing
          || part.getValue(wide.cellProperty()) != cell) return false;
    }
    return wide.hasSupport(level, anchor, state);
  }
}
