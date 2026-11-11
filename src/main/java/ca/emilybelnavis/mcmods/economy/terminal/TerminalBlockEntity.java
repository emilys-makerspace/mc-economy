package ca.emilybelnavis.mcmods.economy.terminal;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Only the physical anchor owns persistent placement identity. */
public final class TerminalBlockEntity extends BlockEntity {
  private UUID generation = UUID.randomUUID();
  private UUID owner;
  private UUID branchId;
  private UUID placementOperation = UUID.randomUUID();
  private long policyRevision;
  private boolean valid = true;
  private boolean policyReady;
  private boolean geometryPending;

  public boolean geometryPending() {
    return geometryPending;
  }

  public void setGeometryPending(boolean value) {
    geometryPending = value;
    if (value) policyReady = false;
    setChanged();
  }

  public TerminalBlockEntity(BlockPos position, BlockState state) {
    super(TerminalCatalog.BLOCK_ENTITY.get(), position, state);
  }

  public boolean policyReady() {
    return policyReady;
  }

  public void setPolicyReady(boolean value) {
    policyReady = value && !geometryPending;
  }

  public ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy.Kind kind() {
    if (getBlockState().getBlock() instanceof WideTerminalBlock wide) return wide.kind();
    return ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy.Kind.REGISTER;
  }

  public UUID generation() {
    return generation;
  }

  public UUID owner() {
    return owner;
  }

  public UUID branchId() {
    return branchId;
  }

  /**
   * Internal generated presentation identity; no banking authority until scoped journal activation.
   */
  public void generatedPlacement(UUID branch, UUID generation, UUID operation) {
    this.branchId = java.util.Objects.requireNonNull(branch);
    this.generation = java.util.Objects.requireNonNull(generation);
    this.placementOperation = java.util.Objects.requireNonNull(operation);
    owner = null;
    policyRevision = 0;
    valid = true;
    policyReady = false;
    setChanged();
  }

  public UUID placementOperation() {
    return placementOperation;
  }

  public long policyRevision() {
    return policyRevision;
  }

  public void setPolicyRevision(long revision) {
    policyRevision = revision;
    setChanged();
  }

  /** Informational visual cache, refreshed only from authoritative worker snapshots. */
  public void applyPolicy(
      ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy.Snapshot snapshot) {
    if (!snapshot.generation().equals(generation) || level == null || level.isClientSide) return;
    setPolicyRevision(snapshot.revision());
    if (getBlockState().getBlock() instanceof WideTerminalBlock) {
      int brand = 0;
      if (snapshot.binding() != null && snapshot.binding().bound()) {
        var banks = ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.banks();
        for (int i = 0; i < banks.size(); i++)
          if (banks.get(i).id().equals(snapshot.binding().institution())) brand = i + 1;
      }
      for (var position : TerminalGeometry.cells(worldPosition, getBlockState())) {
        if (!level.hasChunkAt(position)) return;
        var state = level.getBlockState(position);
        if (state.getBlock() != getBlockState().getBlock()) return;
        level.setBlock(
            position,
            state.setValue(WideTerminalBlock.BANK, brand),
            net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
      }
    }
  }

  public boolean validIdentity() {
    return valid;
  }

  public void setOwner(UUID value) {
    owner = value;
    setChanged();
  }

  public void newPlacement(UUID value) {
    generation = UUID.randomUUID();
    placementOperation = UUID.randomUUID();
    owner = value;
    branchId = null;
    policyRevision = 0;
    valid = true;
    policyReady = false;
    setChanged();
  }

  public void invalidateIdentity() {
    valid = false;
    policyReady = false;
    setChanged();
  }

  @Override
  public boolean onlyOpCanSetNbt() {
    return true;
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (level instanceof ServerLevel server)
      ca.emilybelnavis.mcmods.economy.village.BankPoints.loaded(
          server, worldPosition, getBlockState());
    if (level instanceof ServerLevel server
        && getBlockState().getBlock() instanceof BranchKioskBlock)
      KioskUpgradeController.loaded(server, worldPosition, this);
    if (level instanceof ServerLevel server && valid && branchId != null)
      BranchBootstrap.loaded(server, worldPosition, this);
    if (level instanceof ServerLevel server && valid && owner != null)
      TerminalLifecycle.loaded(server, worldPosition, this);
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    tag.putUUID("generation", generation);
    tag.putUUID("placement_operation", placementOperation);
    if (owner != null) tag.putUUID("owner", owner);
    if (branchId != null) tag.putUUID("village_branch", branchId);
    tag.putBoolean("geometry_pending", geometryPending);
    tag.putLong("policy_revision", policyRevision);
    tag.putBoolean("valid", valid);
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    if (tag.hasUUID("generation")) generation = tag.getUUID("generation");
    if (tag.hasUUID("placement_operation")) placementOperation = tag.getUUID("placement_operation");
    branchId = tag.hasUUID("village_branch") ? tag.getUUID("village_branch") : null;
    owner = branchId == null && tag.hasUUID("owner") ? tag.getUUID("owner") : null;
    geometryPending = tag.getBoolean("geometry_pending");
    policyRevision = tag.getLong("policy_revision");
    valid = !tag.contains("valid") || tag.getBoolean("valid");
  }
}
