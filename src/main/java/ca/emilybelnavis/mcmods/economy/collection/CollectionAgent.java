package ca.emilybelnavis.mcmods.economy.collection;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/** Nonhostile representative. Only automatically bound presentations carry a server-owned lease. */
public final class CollectionAgent extends PathfinderMob {
  private UUID debtor;
  private UUID presentation;
  private boolean observedDeath;

  public CollectionAgent(EntityType<? extends CollectionAgent> type, Level level) {
    super(type, level);
    setPersistenceRequired();
    setCanPickUpLoot(false);
    if (getNavigation() instanceof GroundPathNavigation ground) {
      ground.setCanOpenDoors(false);
      ground.setCanPassDoors(false);
      ground.setCanFloat(false);
    }
  }

  public static AttributeSupplier.Builder attributes() {
    return Mob.createMobAttributes()
        .add(Attributes.MAX_HEALTH, 20)
        .add(Attributes.MOVEMENT_SPEED, 0.25)
        .add(Attributes.FOLLOW_RANGE, 32);
  }

  public UUID debtor() {
    return debtor;
  }

  public UUID presentation() {
    return debtor == null ? getUUID() : presentation;
  }

  public boolean managed() {
    return debtor != null;
  }

  public void bind(UUID owner, UUID lease) {
    if (debtor != null || owner == null || lease == null)
      throw new IllegalStateException("Collection presentation already bound");
    debtor = owner;
    presentation = lease;
  }

  @Override
  public boolean canTrample(
      net.minecraft.world.level.block.state.BlockState state,
      net.minecraft.core.BlockPos pos,
      float distance) {
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
  protected void registerGoals() {}

  @Override
  public boolean doHurtTarget(Entity target) {
    return false;
  }

  @Override
  public void setTarget(LivingEntity target) {}

  @Override
  public void tick() {
    super.tick();
    if (!level().isClientSide) CollectionAgents.entityTick(this);
  }

  @Override
  protected InteractionResult mobInteract(Player player, InteractionHand hand) {
    if (player instanceof ServerPlayer serverPlayer
        && CollectionAgents.canInteract(serverPlayer, this)) {
      CollectionDialogue.open(serverPlayer, this);
      return InteractionResult.CONSUME;
    }
    return InteractionResult.PASS;
  }

  @Override
  public void die(DamageSource source) {
    super.die(source);
    if (!level().isClientSide && dead && !observedDeath) {
      observedDeath = true;
      CollectionAgents.died(this);
    }
  }

  @Override
  public void onRemovedFromLevel() {
    super.onRemovedFromLevel();
    if (!level().isClientSide) {
      if (getRemovalReason() == RemovalReason.KILLED && !observedDeath) {
        observedDeath = true;
        CollectionAgents.died(this);
      } else if (!observedDeath) CollectionAgents.removed(this);
    }
  }

  @Override
  protected void createWitherRose(LivingEntity killer) {}

  @Override
  protected void dropAllDeathLoot(ServerLevel level, DamageSource source) {}

  @Override
  protected void dropExperience(Entity source) {}

  @Override
  public boolean isPushable() {
    return false;
  }

  @Override
  public void push(Entity other) {}

  @Override
  protected void doPush(Entity other) {}

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

  @Override
  public boolean shouldBeSaved() {
    return !managed() && super.shouldBeSaved();
  }
}
