package ca.emilybelnavis.mcmods.economy.terminal;

import ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy;
import com.mojang.serialization.MapCodec;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Loaded-only multiblock structure, one anchor identity and one teardown drop. */
public class WideTerminalBlock extends Block implements EntityBlock {
  public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
  public static final IntegerProperty CELL = IntegerProperty.create("cell", 0, 3);
  public static final IntegerProperty BANK = IntegerProperty.create("bank", 0, 23);
  private static final Set<Guard> MUTATING = new HashSet<>();

  private record Guard(Level level, BlockPos anchor) {}

  private final TerminalPolicy.Kind kind;

  public WideTerminalBlock(BlockBehaviour.Properties properties, TerminalPolicy.Kind kind) {
    super(properties);
    this.kind = kind;
    registerDefaultState(
        stateDefinition
            .any()
            .setValue(FACING, Direction.NORTH)
            .setValue(cellProperty(), 0)
            .setValue(BANK, 0));
  }

  public TerminalPolicy.Kind kind() {
    return kind;
  }

  public IntegerProperty cellProperty() {
    return CELL;
  }

  public int cellCount() {
    return 4;
  }

  /** State-aware forms let legacy and expanded kiosks coexist under the same registered ID. */
  public int cellCount(BlockState state) {
    return cellCount();
  }

  public BlockPos cellPosition(BlockPos anchor, BlockState state, int cell) {
    return cellPosition(anchor, state.getValue(FACING), cell);
  }

  public boolean sameGeometry(BlockState first, BlockState second) {
    return first.getBlock() == second.getBlock();
  }

  public BlockPos cellPosition(BlockPos anchor, Direction facing, int cell) {
    return TerminalGeometry.cell(anchor, facing, cell);
  }

  public BlockPos anchorPosition(BlockPos position, BlockState state) {
    int cell = state.getValue(cellProperty());
    return position.relative(state.getValue(FACING).getClockWise(), cell & 1).below(cell >> 1);
  }

  protected boolean hasSupport(LevelReader level, BlockPos anchor, Direction facing) {
    return supported(level, anchor, facing);
  }

  protected boolean hasSupport(LevelReader level, BlockPos anchor, BlockState state) {
    return hasSupport(level, anchor, state.getValue(FACING));
  }

  @Override
  protected MapCodec<? extends Block> codec() {
    return simpleCodec(p -> new WideTerminalBlock(p, kind));
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(FACING, cellProperty(), BANK);
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return state.getValue(cellProperty()) == 0 ? new TerminalBlockEntity(position, state) : null;
  }

  public static BlockPos resolveAnchor(LevelReader level, BlockPos position) {
    return TerminalGeometry.resolveAnchor(level, position);
  }

  public static boolean supported(LevelReader level, BlockPos anchor, Direction facing) {
    for (int cell = 0; cell < 2; cell++) {
      var floor = TerminalGeometry.cell(anchor, facing, cell).below();
      if (!level.hasChunkAt(floor)
          || !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) return false;
    }
    return true;
  }

  public boolean canPlaceAll(BlockPlaceContext context, BlockState state) {
    var level = context.getLevel();
    var anchor = context.getClickedPos();
    var facing = state.getValue(FACING);
    if (!hasSupport(level, anchor, state)) return false;
    for (int cell = 0; cell < cellCount(state); cell++) {
      var position = cellPosition(anchor, state, cell);
      if (!level.isInWorldBounds(position)
          || !level.getWorldBorder().isWithinBounds(position)
          || !level.hasChunkAt(position)
          || !level.getBlockState(position).canBeReplaced(context)) return false;
      var player = context.getPlayer();
      if (player != null
          && (!level.mayInteract(player, position)
              || !player.mayUseItemAt(position, context.getClickedFace(), context.getItemInHand())))
        return false;
      var collision = player == null ? CollisionContext.empty() : CollisionContext.of(player);
      if (!level.isUnobstructed(state.setValue(cellProperty(), cell), position, collision))
        return false;
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
  protected boolean canSurvive(BlockState state, LevelReader level, BlockPos position) {
    return hasSupport(level, TerminalGeometry.anchor(position, state), state);
  }

  /** Guard neighbor notifications while all cells are installed or rolled back. */
  static void beginPlacement(Level level, BlockPos anchor) {
    MUTATING.add(new Guard(level, anchor.immutable()));
  }

  static void endPlacement(Level level, BlockPos anchor) {
    MUTATING.remove(new Guard(level, anchor));
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
  protected void onPlace(
      BlockState state, Level level, BlockPos position, BlockState previous, boolean moving) {
    super.onPlace(state, level, position, previous, moving);
    if (!level.isClientSide && previous.getBlock() != this)
      level.scheduleTick(position, this, 20 + state.getValue(cellProperty()));
  }

  @Override
  protected void tick(
      BlockState state,
      ServerLevel level,
      BlockPos position,
      net.minecraft.util.RandomSource random) {
    neighborChanged(state, level, position, this, position, false);
    var current = level.getBlockState(position);
    if (current.getBlock() == this
        && current.getValue(cellProperty()).equals(state.getValue(cellProperty())))
      level.scheduleTick(position, this, 200);
  }

  @Override
  protected void neighborChanged(
      BlockState state,
      Level level,
      BlockPos position,
      Block neighbor,
      BlockPos neighborPosition,
      boolean moving) {
    if (level.isClientSide) return;
    var anchor = TerminalGeometry.anchor(position, state);
    if (MUTATING.contains(new Guard(level, anchor))) return;
    // Kiosk upgrade recovery owns uncertain geometry; startup never converts a partial observation
    // to a drop.
    if (this instanceof BranchKioskBlock
        && level.hasChunkAt(anchor)
        && level.getBlockEntity(anchor) instanceof TerminalBlockEntity entity
        && (entity.geometryPending() || !entity.policyReady())) return;
    for (var target : TerminalGeometry.cells(anchor, state)) {
      if (!level.hasChunkAt(target) || !level.hasChunkAt(target.below())) return;
    }
    if (!hasSupport(level, anchor, state) || !complete(level, anchor, state))
      teardown(level, position, state, true);
  }

  private boolean complete(LevelReader level, BlockPos anchor, BlockState state) {
    if (!(level.getBlockEntity(anchor) instanceof TerminalBlockEntity entity)
        || !entity.validIdentity()) return false;
    var facing = state.getValue(FACING);
    for (int cell = 0; cell < cellCount(state); cell++) {
      var position = cellPosition(anchor, state, cell);
      // Do not turn an unloaded neighbor chunk into a destructive observation.
      if (!level.hasChunkAt(position)) return true;
      var part = level.getBlockState(position);
      if (!sameGeometry(state, part)
          || part.getValue(FACING) != facing
          || part.getValue(cellProperty()) != cell) return false;
    }
    return true;
  }

  @Override
  public BlockState playerWillDestroy(
      Level level, BlockPos position, BlockState state, Player player) {
    if (!level.isClientSide) teardown(level, position, state, !player.isCreative());
    return super.playerWillDestroy(level, position, state, player);
  }

  @Override
  protected void onRemove(
      BlockState state, Level level, BlockPos position, BlockState replacement, boolean moving) {
    if (!level.isClientSide && state.getBlock() != replacement.getBlock())
      teardown(level, position, state, true);
    super.onRemove(state, level, position, replacement, moving);
  }

  private void teardown(Level level, BlockPos position, BlockState state, boolean drop) {
    var anchor = TerminalGeometry.anchor(position, state);
    var guard = new Guard(level, anchor);
    if (!MUTATING.add(guard)) return;
    try {
      // A satellite can only produce an item while the unique live anchor still exists.
      var entity =
          level.hasChunkAt(anchor)
                  && level.getBlockEntity(anchor) instanceof TerminalBlockEntity found
              ? found
              : null;
      boolean ownedAnchor =
          entity != null
              && entity.validIdentity()
              && sameGeometry(state, entity.getBlockState())
              && entity.getBlockState().getValue(cellProperty()) == 0
              && entity.getBlockState().getValue(FACING) == state.getValue(FACING);
      if (ownedAnchor) {
        if (level instanceof ServerLevel server) TerminalLifecycle.removed(server, anchor, entity);
        entity.invalidateIdentity();
      }
      var facing = state.getValue(FACING);
      for (int cell = cellCount(state) - 1; cell >= 0; cell--) {
        var target = cellPosition(anchor, state, cell);
        if (!level.hasChunkAt(target)) continue;
        var part = level.getBlockState(target);
        if (sameGeometry(state, part)
            && part.getValue(FACING) == facing
            && part.getValue(cellProperty()) == cell)
          level.setBlock(
              target,
              Blocks.AIR.defaultBlockState(),
              Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
      }
      if (ownedAnchor && drop) {
        var item = new ItemStack(this);
        // Cosmetic only: placement deliberately ignores all item components and starts unbound.
        if (kind == TerminalPolicy.Kind.KIOSK && state.getValue(BANK) > 0)
          item.set(
              net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA,
              new net.minecraft.world.item.component.CustomModelData(state.getValue(BANK)));
        Block.popResource(level, anchor, item);
      }
    } finally {
      MUTATING.remove(guard);
    }
  }

  @Override
  protected net.minecraft.world.ItemInteractionResult useItemOn(
      ItemStack stack,
      BlockState state,
      Level level,
      BlockPos position,
      Player player,
      net.minecraft.world.InteractionHand hand,
      net.minecraft.world.phys.BlockHitResult hit) {
    if (ca.emilybelnavis.mcmods.economy.card.CardCatalog.find(stack).isEmpty())
      return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    useWithoutItem(state, level, position, player, hit);
    return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
  }

  @Override
  protected net.minecraft.world.InteractionResult useWithoutItem(
      BlockState state,
      Level level,
      BlockPos position,
      Player player,
      net.minecraft.world.phys.BlockHitResult hit) {
    var anchor = TerminalGeometry.anchor(position, state);
    if (level instanceof ServerLevel server
        && level.hasChunkAt(anchor)
        && level.getBlockEntity(anchor) instanceof TerminalBlockEntity entity) {
      if (!entity.policyReady()) {
        if (kind == TerminalPolicy.Kind.KIOSK)
          KioskUpgradeController.loaded(server, anchor, entity);
        TerminalLifecycle.loaded(server, anchor, entity);
      }
      if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
        ca.emilybelnavis.mcmods.economy.banking.BankingController.open(serverPlayer, anchor);
    }
    return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
  }

  @Override
  protected VoxelShape getShape(
      BlockState state, BlockGetter level, BlockPos position, CollisionContext context) {
    return switch (state.getValue(FACING)) {
      case NORTH -> Block.box(0, 0, 3, 16, 16, 15);
      case SOUTH -> Block.box(0, 0, 1, 16, 16, 13);
      case EAST -> Block.box(1, 0, 0, 13, 16, 16);
      default -> Block.box(3, 0, 0, 15, 16, 16);
    };
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
