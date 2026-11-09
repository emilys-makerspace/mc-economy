package ca.emilybelnavis.mcmods.economy.banker.entity;

import ca.emilybelnavis.mcmods.economy.banker.StaffingRuntime;
import ca.emilybelnavis.mcmods.economy.banker.dialogue.BankerDialogue;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;

/**
 * A persistent civilian presentation. Saved assignment identifiers never grant banking authority.
 */
public final class BankerEntity extends PathfinderMob {
  private UUID branch, deskGeneration, assignment;
  private int deskSlot = -1;
  private long revision;
  private boolean deathReported;
  private BlockPos work, rest;
  private boolean workEnabled;

  public BankerEntity(EntityType<? extends BankerEntity> type, Level level) {
    super(type, level);
    setPersistenceRequired();
    setCanPickUpLoot(false);
    xpReward = 0;
    if (getNavigation() instanceof GroundPathNavigation ground) {
      ground.setCanOpenDoors(false);
      ground.setCanPassDoors(false);
      ground.setCanFloat(true);
    }
  }

  public static AttributeSupplier.Builder attributes() {
    return Mob.createMobAttributes()
        .add(Attributes.MAX_HEALTH, 20)
        .add(Attributes.MOVEMENT_SPEED, 0.24)
        .add(Attributes.FOLLOW_RANGE, 16);
  }

  public void bind(UUID branch, int slot, UUID generation, UUID assignment, long revision) {
    if (assigned() || slot < 0 || slot > 1 || revision < 0)
      throw new IllegalStateException("Invalid banker assignment");
    this.branch = Objects.requireNonNull(branch);
    deskSlot = slot;
    deskGeneration = Objects.requireNonNull(generation);
    this.assignment = Objects.requireNonNull(assignment);
    this.revision = revision;
  }

  public boolean assigned() {
    return branch != null && deskGeneration != null && assignment != null && deskSlot >= 0;
  }

  public UUID branchId() {
    return branch;
  }

  public int deskSlot() {
    return deskSlot;
  }

  public UUID deskGeneration() {
    return deskGeneration;
  }

  public UUID assignmentId() {
    return assignment;
  }

  public long assignmentRevision() {
    return revision;
  }

  public void assignmentRevision(long value) {
    if (value < revision) throw new IllegalArgumentException("Assignment revision cannot decrease");
    revision = value;
  }

  /**
   * Refreshed by server authority; targets are transient and never recovered as authority from NBT.
   */
  public void workTarget(BlockPos work, BlockPos rest, boolean enabled) {
    this.work = work == null ? null : work.immutable();
    this.rest = rest == null ? null : rest.immutable();
    workEnabled = enabled;
    if (!enabled) {
      getNavigation().stop();
      if (isSleeping()) stopSleeping();
    }
  }

  @Override
  protected void registerGoals() {
    goalSelector.addGoal(0, new FloatGoal(this));
    goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 6));
    goalSelector.addGoal(2, new RandomLookAroundGoal(this));
  }

  @Override
  public void tick() {
    super.tick();
    if (level().isClientSide) return;
    StaffingRuntime.entityTick(this);
    if (isSleeping()) {
      if (level().isDay()
          || !workEnabled
          || rest == null
          || getSleepingPos().filter(rest::equals).isEmpty()) stopSleeping();
      else return;
    }
    if (!workEnabled || work == null || tickCount % 40 != 0) return;
    var target = level().isDay() ? work : (rest == null ? work : rest);
    if (level().isDay() && tickCount % 200 == 0 && work.distSqr(blockPosition()) <= 16) {
      int direction = Math.floorMod(getUUID().hashCode() + tickCount / 200, 8);
      int[] dx = {2, 2, 0, -2, -2, -2, 0, 2}, dz = {0, 2, 2, 2, 0, -2, -2, -2};
      target = work.offset(dx[direction], 0, dz[direction]);
    }
    // Never request chunks or navigate beyond the local branch envelope.
    if (work.distSqr(blockPosition()) > 32 * 32
        || work.distSqr(target) > 24 * 24
        || !navigationRegionLoaded(target)) {
      getNavigation().stop();
      return;
    }
    if (!level().isDay() && rest != null && rest.distSqr(blockPosition()) <= 4) {
      var bed = level().getBlockState(rest);
      if (bed.is(BlockTags.BEDS)
          && bed.hasProperty(BedBlock.OCCUPIED)
          && !bed.getValue(BedBlock.OCCUPIED)) {
        getNavigation().stop();
        startSleeping(rest);
        return;
      }
    }
    if (target.distSqr(blockPosition()) <= 2.25) {
      getNavigation().stop();
      return;
    }
    var path = getNavigation().createPath(target, 0);
    if (path == null) return;
    for (int i = 0; i < path.getNodeCount(); i++) {
      var node = path.getNode(i);
      var position = new BlockPos(node.x, node.y, node.z);
      if (work.distSqr(position) > 32 * 32 || !level().hasChunkAt(position)) {
        getNavigation().stop();
        return;
      }
    }
    getNavigation().moveTo(path, 0.65);
  }

  /** PathNavigationRegion reads every chunk in this rectangle while constructing a path. */
  private boolean navigationRegionLoaded(BlockPos target) {
    if (!(level() instanceof ServerLevel server)) return false;
    int radius = (int) Math.ceil(getAttributeValue(Attributes.FOLLOW_RANGE)) + 8;
    if (radius < 8 || radius > 32) return false;
    var position = blockPosition();
    int minX = Math.min(position.getX() - radius, target.getX()) >> 4,
        maxX = Math.max(position.getX() + radius, target.getX()) >> 4;
    int minZ = Math.min(position.getZ() - radius, target.getZ()) >> 4,
        maxZ = Math.max(position.getZ() + radius, target.getZ()) >> 4;
    for (int x = minX; x <= maxX; x++)
      for (int z = minZ; z <= maxZ; z++)
        if (server.getChunkSource().getChunkNow(x, z) == null) return false;
    return true;
  }

  @Override
  protected InteractionResult mobInteract(Player player, InteractionHand hand) {
    if (player instanceof ServerPlayer serverPlayer
        && hand == InteractionHand.MAIN_HAND
        && StaffingRuntime.context(serverPlayer, this).isPresent()) {
      BankerDialogue.open(serverPlayer, this);
      return InteractionResult.CONSUME;
    }
    return InteractionResult.PASS;
  }

  @Override
  public void addAdditionalSaveData(CompoundTag tag) {
    super.addAdditionalSaveData(tag);
    if (assigned()) {
      var data = new CompoundTag();
      data.putInt("version", 1);
      data.putUUID("branch", branch);
      data.putInt("slot", deskSlot);
      data.putUUID("desk", deskGeneration);
      data.putUUID("assignment", assignment);
      data.putLong("revision", revision);
      tag.put("economy_banker", data);
    }
  }

  @Override
  public void readAdditionalSaveData(CompoundTag tag) {
    super.readAdditionalSaveData(tag);
    branch = null;
    deskGeneration = null;
    assignment = null;
    deskSlot = -1;
    revision = 0;
    var data = tag.getCompound("economy_banker");
    int slot = data.getInt("slot");
    if (data.getInt("version") == 1
        && data.hasUUID("branch")
        && data.hasUUID("desk")
        && data.hasUUID("assignment")
        && slot >= 0
        && slot <= 1
        && data.getLong("revision") >= 0)
      bind(
          data.getUUID("branch"),
          slot,
          data.getUUID("desk"),
          data.getUUID("assignment"),
          data.getLong("revision"));
    setPersistenceRequired();
    setCanPickUpLoot(false);
  }

  @Override
  public void die(DamageSource source) {
    super.die(source);
    if (!level().isClientSide && dead && !deathReported) {
      deathReported = true;
      StaffingRuntime.died(this);
    }
  }

  @Override
  public void onRemovedFromLevel() {
    super.onRemovedFromLevel();
    if (level().isClientSide || deathReported) return;
    if (getRemovalReason() == RemovalReason.KILLED) {
      deathReported = true;
      StaffingRuntime.died(this);
    } else if (getRemovalReason() == RemovalReason.DISCARDED) StaffingRuntime.removed(this);
  }

  @Override
  public boolean doHurtTarget(Entity entity) {
    return false;
  }

  @Override
  public void setTarget(LivingEntity target) {}

  @Override
  public boolean removeWhenFarAway(double distance) {
    return false;
  }

  @Override
  public boolean canTrample(
      net.minecraft.world.level.block.state.BlockState state, BlockPos pos, float distance) {
    return false;
  }

  @Override
  public boolean isIgnoringBlockTriggers() {
    return true;
  }

  @Override
  public boolean isSteppingCarefully() {
    return true;
  }

  @Override
  protected void createWitherRose(LivingEntity killer) {}

  @Override
  protected void dropAllDeathLoot(ServerLevel level, DamageSource source) {}

  @Override
  protected void dropExperience(Entity source) {}

  @Override
  public boolean canBeLeashed() {
    return false;
  }

  @Override
  public boolean canUsePortal(boolean passenger) {
    return false;
  }

  @Override
  public boolean startRiding(Entity vehicle, boolean force) {
    return false;
  }

  @Override
  protected boolean canAddPassenger(Entity passenger) {
    return false;
  }
}
