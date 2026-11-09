package ca.emilybelnavis.mcmods.economy.terminal;

import ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class RegisterBlock extends Block implements EntityBlock {
  public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
  private final TerminalPolicy.Kind kind;

  public RegisterBlock(BlockBehaviour.Properties properties, TerminalPolicy.Kind kind) {
    super(properties);
    this.kind = kind;
    registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
  }

  public TerminalPolicy.Kind kind() {
    return kind;
  }

  @Override
  protected MapCodec<? extends Block> codec() {
    return simpleCodec(p -> new RegisterBlock(p, kind));
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(FACING);
  }

  @Override
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return new TerminalBlockEntity(position, state);
  }

  @Override
  public void setPlacedBy(
      Level level, BlockPos position, BlockState state, LivingEntity placer, ItemStack stack) {
    if (level instanceof ServerLevel server
        && placer != null
        && level.getBlockEntity(position) instanceof TerminalBlockEntity entity) {
      entity.newPlacement(placer.getUUID());
      TerminalLifecycle.placed(server, position, entity);
    }
  }

  @Override
  protected void onRemove(
      BlockState state, Level level, BlockPos position, BlockState replacement, boolean moving) {
    if (state.getBlock() != replacement.getBlock()
        && level instanceof ServerLevel server
        && level.getBlockEntity(position) instanceof TerminalBlockEntity entity
        && entity.validIdentity()) {
      TerminalLifecycle.removed(server, position, entity);
      entity.invalidateIdentity();
    }
    super.onRemove(state, level, position, replacement, moving);
  }

  @Override
  protected boolean canSurvive(BlockState state, LevelReader level, BlockPos position) {
    var below = position.below();
    return level.hasChunkAt(below)
        && (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
            || level.getBlockEntity(below) instanceof net.minecraft.world.Container);
  }

  @Override
  protected void neighborChanged(
      BlockState state,
      Level level,
      BlockPos position,
      Block neighbor,
      BlockPos neighborPosition,
      boolean moving) {
    if (!level.isClientSide
        && level.hasChunkAt(position.below())
        && !canSurvive(state, level, position)) level.destroyBlock(position, true);
  }

  @Override
  protected net.minecraft.world.InteractionResult useWithoutItem(
      BlockState state,
      Level level,
      BlockPos position,
      net.minecraft.world.entity.player.Player player,
      net.minecraft.world.phys.BlockHitResult hit) {
    if (level instanceof ServerLevel server
        && level.getBlockEntity(position) instanceof TerminalBlockEntity entity) {
      if (!entity.policyReady()) TerminalLifecycle.loaded(server, position, entity);
      if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
        ca.emilybelnavis.mcmods.economy.banking.BankingController.open(serverPlayer, position);
    }
    return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
  }

  @Override
  protected VoxelShape getShape(
      BlockState state, BlockGetter level, BlockPos position, CollisionContext context) {
    return Block.box(1, 0, 2, 15, 12, 14);
  }

  @Override
  protected BlockState rotate(BlockState state, Rotation rotation) {
    return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
  }

  @Override
  protected BlockState mirror(BlockState state, Mirror mirror) {
    return state.rotate(mirror.getRotation(state.getValue(FACING)));
  }
}
