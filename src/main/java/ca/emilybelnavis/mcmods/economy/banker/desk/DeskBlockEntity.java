package ca.emilybelnavis.mcmods.economy.banker.desk;

import ca.emilybelnavis.mcmods.economy.banker.StaffingRuntime;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Placement identity is server-owned and is never restored from a desk item. */
public final class DeskBlockEntity extends BlockEntity {
  private UUID branch, generation = UUID.randomUUID(), operation;
  private boolean valid = true;

  public DeskBlockEntity(BlockPos position, BlockState state) {
    super(DeskCatalog.BLOCK_ENTITY.get(), position, state);
  }

  public Optional<UUID> branchId() {
    return Optional.ofNullable(branch);
  }

  public UUID generation() {
    return generation;
  }

  public Optional<UUID> operationId() {
    return Optional.ofNullable(operation);
  }

  public boolean validIdentity() {
    return valid;
  }

  public void generatedPlacement(UUID branch, UUID generation, UUID operation) {
    this.branch = Objects.requireNonNull(branch);
    this.generation = Objects.requireNonNull(generation);
    this.operation = Objects.requireNonNull(operation);
    valid = true;
    setChanged();
  }

  public void invalidateIdentity() {
    valid = false;
    setChanged();
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (level instanceof ServerLevel server)
      StaffingRuntime.deskLoaded(server, worldPosition, this);
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    tag.putUUID("generation", generation);
    tag.putBoolean("valid", valid);
    if (branch != null) tag.putUUID("branch", branch);
    if (operation != null) tag.putUUID("operation", operation);
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    generation = tag.hasUUID("generation") ? tag.getUUID("generation") : UUID.randomUUID();
    branch = tag.hasUUID("branch") ? tag.getUUID("branch") : null;
    operation = tag.hasUUID("operation") ? tag.getUUID("operation") : null;
    valid = tag.contains("valid") && tag.getBoolean("valid");
    if ((branch == null) != (operation == null)) valid = false;
  }
}
