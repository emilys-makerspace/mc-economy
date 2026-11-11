package ca.emilybelnavis.mcmods.economy.banker.desk;

import ca.emilybelnavis.mcmods.economy.banker.StaffingRuntime;
import com.mojang.serialization.MapCodec;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.shapes.*;

/** One identity and one drop for the four-cell desk. Incomplete unloaded observations defer. */
public final class DeskBlock extends Block implements EntityBlock {
  public enum Wood implements StringRepresentable {
    OAK,
    JUNGLE,
    ACACIA,
    SPRUCE;

    public String getSerializedName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
  public static final IntegerProperty CELL = IntegerProperty.create("cell", 0, 3);
  public static final EnumProperty<Wood> WOOD = EnumProperty.create("wood", Wood.class);

  private record Guard(Level level, BlockPos anchor) {}

  private static final Set<Guard> MUTATING = new HashSet<>();

  public DeskBlock(Properties properties) {
    super(properties);
    registerDefaultState(
        stateDefinition
            .any()
            .setValue(FACING, Direction.NORTH)
            .setValue(CELL, 0)
            .setValue(WOOD, Wood.OAK));
  }

  @Override
  protected MapCodec<? extends Block> codec() {
    return simpleCodec(DeskBlock::new);
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(FACING, CELL, WOOD);
  }

  @Override
  protected BlockState rotate(BlockState state, Rotation rotation) {
    return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
  }

  @Override
  protected BlockState mirror(BlockState state, Mirror mirror) {
    return state.rotate(mirror.getRotation(state.getValue(FACING)));
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return state.getValue(CELL) == 0 ? new DeskBlockEntity(position, state) : null;
  }

  static void begin(Level level, BlockPos anchor) {
    MUTATING.add(new Guard(level, anchor.immutable()));
  }

  static void end(Level level, BlockPos anchor) {
    MUTATING.remove(new Guard(level, anchor));
  }

  public boolean canPlaceAll(BlockPlaceContext context, BlockState state) {
    var level = context.getLevel();
    var anchor = context.getClickedPos();
    if (!DeskGeometry.supported(level, anchor, state.getValue(FACING))) return false;
    for (int cell = 0; cell < 4; cell++) {
      var pos = DeskGeometry.cell(anchor, state.getValue(FACING), cell);
      var player = context.getPlayer();
      if (!level.isInWorldBounds(pos)
          || !level.getWorldBorder().isWithinBounds(pos)
          || !level.hasChunkAt(pos)
          || !level.getBlockState(pos).canBeReplaced(context)
          || level.getBlockEntity(pos) != null) return false;
      if (player != null
          && (!level.mayInteract(player, pos)
              || !player.mayUseItemAt(pos, context.getClickedFace(), context.getItemInHand())))
        return false;
      if (!level.isUnobstructed(
          state.setValue(CELL, cell),
          pos,
          player == null ? CollisionContext.empty() : CollisionContext.of(player))) return false;
    }
    return true;
  }

  @Override
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    var state =
        defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    return canPlaceAll(context, state) ? state : null;
  }

  @Override
  protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
    return DeskGeometry.supported(level, DeskGeometry.anchor(pos, state), state.getValue(FACING));
  }

  @Override
  public void setPlacedBy(
      Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
    if (level instanceof ServerLevel server
        && level.getBlockEntity(pos) instanceof DeskBlockEntity entity)
      StaffingRuntime.deskLoaded(server, pos, entity);
  }

  @Override
  protected void onPlace(
      BlockState state, Level level, BlockPos pos, BlockState previous, boolean moving) {
    super.onPlace(state, level, pos, previous, moving);
    if (!level.isClientSide && previous.getBlock() != this)
      level.scheduleTick(pos, this, 20 + state.getValue(CELL));
  }

  @Override
  protected void tick(
      BlockState state, ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random) {
    neighborChanged(state, level, pos, this, pos, false);
    if (level.getBlockState(pos).getBlock() == this) level.scheduleTick(pos, this, 200);
  }

  @Override
  protected void neighborChanged(
      BlockState state,
      Level level,
      BlockPos pos,
      Block neighbor,
      BlockPos changed,
      boolean moving) {
    if (level.isClientSide) return;
    var anchor = DeskGeometry.anchor(pos, state);
    if (MUTATING.contains(new Guard(level, anchor))) return;
    for (var target : DeskGeometry.cells(anchor, state))
      if (!level.hasChunkAt(target) || !level.hasChunkAt(target.below())) return;
    if (!(level.getBlockEntity(anchor) instanceof DeskBlockEntity entity)
        || !DeskGeometry.matches(level, anchor, entity.generation()))
      teardown(level, pos, state, true);
  }

  @Override
  public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
    if (!level.isClientSide) teardown(level, pos, state, !player.isCreative());
    return super.playerWillDestroy(level, pos, state, player);
  }

  @Override
  protected void onRemove(
      BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
    if (!level.isClientSide && state.getBlock() != replacement.getBlock())
      teardown(level, pos, state, true);
    super.onRemove(state, level, pos, replacement, moving);
  }

  private void teardown(Level level, BlockPos pos, BlockState state, boolean drop) {
    var anchor = DeskGeometry.anchor(pos, state);
    var guard = new Guard(level, anchor);
    if (!MUTATING.add(guard)) return;
    try {
      var entity =
          level.hasChunkAt(anchor) && level.getBlockEntity(anchor) instanceof DeskBlockEntity found
              ? found
              : null;
      boolean owned =
          entity != null
              && entity.validIdentity()
              && entity.getBlockState().equals(state.setValue(CELL, 0));
      if (owned) {
        if (level instanceof ServerLevel server)
          StaffingRuntime.deskRemoved(server, anchor, entity);
        entity.invalidateIdentity();
      }
      for (int cell = 3; cell >= 0; cell--) {
        var target = DeskGeometry.cell(anchor, state.getValue(FACING), cell);
        if (level.hasChunkAt(target)
            && level.getBlockState(target).equals(state.setValue(CELL, cell)))
          level.setBlock(
              target,
              Blocks.AIR.defaultBlockState(),
              Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
      }
      if (owned && drop) Block.popResource(level, anchor, new ItemStack(this));
    } finally {
      MUTATING.remove(guard);
    }
  }

  @Override
  protected VoxelShape getShape(
      BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
    if (state.getValue(CELL) < 2) return Block.box(0, 0, 0, 16, 16, 16);
    return switch (state.getValue(FACING)) {
      case NORTH -> Block.box(2, 0, 8, 14, 11, 14);
      case SOUTH -> Block.box(2, 0, 2, 14, 11, 8);
      case EAST -> Block.box(2, 0, 2, 8, 11, 14);
      default -> Block.box(8, 0, 2, 14, 11, 14);
    };
  }
}
