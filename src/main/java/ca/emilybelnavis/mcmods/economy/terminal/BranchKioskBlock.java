package ca.emilybelnavis.mcmods.economy.terminal;

import ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Stable kiosk ID with explicit legacy narrow and two-wide service-console geometry. */
public final class BranchKioskBlock extends WideTerminalBlock {
  public static final IntegerProperty CELL = IntegerProperty.create("cell", 0, 3);
  public static final BooleanProperty WIDE = BooleanProperty.create("wide");

  public BranchKioskBlock(BlockBehaviour.Properties properties) {
    super(properties, TerminalPolicy.Kind.KIOSK);
    // Old palette entries have no wide property and decode to this unchanged narrow default.
    registerDefaultState(defaultBlockState().setValue(WIDE, false));
  }

  @Override
  protected MapCodec<? extends Block> codec() {
    return simpleCodec(BranchKioskBlock::new);
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    super.createBlockStateDefinition(builder);
    builder.add(WIDE);
  }

  @Override
  public IntegerProperty cellProperty() {
    return CELL;
  }

  /** Historical callers without a state describe the legacy vertical kiosk. */
  @Override
  public int cellCount() {
    return 2;
  }

  @Override
  public int cellCount(BlockState state) {
    return state.getValue(WIDE) ? 4 : 2;
  }

  @Override
  public BlockPos cellPosition(BlockPos anchor, Direction facing, int cell) {
    if (cell < 0 || cell >= cellCount() || !facing.getAxis().isHorizontal())
      throw new IllegalArgumentException("Legacy kiosk cell");
    return anchor.above(cell);
  }

  @Override
  public BlockPos cellPosition(BlockPos anchor, BlockState state, int cell) {
    if (cell < 0 || cell >= cellCount(state)) throw new IllegalArgumentException("Kiosk cell");
    return state.getValue(WIDE)
        ? TerminalGeometry.cell(anchor, state.getValue(FACING), cell)
        : cellPosition(anchor, state.getValue(FACING), cell);
  }

  @Override
  public BlockPos anchorPosition(BlockPos position, BlockState state) {
    return state.getValue(WIDE)
        ? super.anchorPosition(position, state)
        : position.below(state.getValue(CELL));
  }

  @Override
  public boolean sameGeometry(BlockState first, BlockState second) {
    return super.sameGeometry(first, second) && first.getValue(WIDE).equals(second.getValue(WIDE));
  }

  @Override
  protected boolean hasSupport(LevelReader level, BlockPos anchor, Direction facing) {
    var floor = anchor.below();
    return level.hasChunkAt(floor)
        && level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
  }

  @Override
  protected boolean hasSupport(LevelReader level, BlockPos anchor, BlockState state) {
    return state.getValue(WIDE)
        ? supported(level, anchor, state.getValue(FACING))
        : hasSupport(level, anchor, state.getValue(FACING));
  }

  @Override
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    var state =
        defaultBlockState()
            .setValue(FACING, context.getHorizontalDirection().getOpposite())
            .setValue(WIDE, true);
    return canPlaceAll(context, state) ? state : null;
  }

  /** Preserve facing/brand while an explicitly authorized upgrade installs each target cell. */
  public static BlockState wideState(BlockState narrow, int cell) {
    if (!(narrow.getBlock() instanceof BranchKioskBlock) || cell < 0 || cell > 3)
      throw new IllegalArgumentException("Kiosk upgrade state");
    return narrow.setValue(WIDE, true).setValue(CELL, cell);
  }

  @Override
  protected VoxelShape getShape(
      BlockState state, BlockGetter level, BlockPos position, CollisionContext context) {
    if (state.getValue(WIDE))
      return switch (state.getValue(FACING)) {
        case NORTH, SOUTH -> Block.box(0, 0, 1, 16, 16, 15);
        default -> Block.box(1, 0, 0, 15, 16, 16);
      };
    return Block.box(2, 0, 2, 14, 16, 14);
  }
}
