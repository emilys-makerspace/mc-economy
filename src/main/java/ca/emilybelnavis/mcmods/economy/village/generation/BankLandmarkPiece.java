package ca.emilybelnavis.mcmods.economy.village.generation;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/** Native structure bounds only: no blocks, entities, or terrain adjustment. */
public final class BankLandmarkPiece extends StructurePiece
    implements net.neoforged.neoforge.common.world.PieceBeardifierModifier {
  private final int slot;

  public BankLandmarkPiece(int slot, BoundingBox bounds) {
    super(VillageGeneration.BANK_PIECE.get(), 0, bounds);
    if (slot < 0 || slot > 2) throw new IllegalArgumentException("Bank slot");
    this.slot = slot;
  }

  public BankLandmarkPiece(StructurePieceSerializationContext context, CompoundTag tag) {
    super(VillageGeneration.BANK_PIECE.get(), tag);
    slot = tag.getInt("bank_slot");
    if (tag.getInt("bank_version") != 1 || slot < 0 || slot > 2)
      throw new IllegalArgumentException("Bank landmark version/slot");
  }

  public int slot() {
    return slot;
  }

  @Override
  protected void addAdditionalSaveData(
      StructurePieceSerializationContext context, CompoundTag tag) {
    tag.putInt("bank_version", 1);
    tag.putInt("bank_slot", slot);
  }

  @Override
  public TerrainAdjustment getTerrainAdjustment() {
    return TerrainAdjustment.NONE;
  }

  @Override
  public BoundingBox getBeardifierBox() {
    return getBoundingBox();
  }

  @Override
  public int getGroundLevelDelta() {
    return 0;
  }

  @Override
  public void postProcess(
      WorldGenLevel level,
      StructureManager structures,
      ChunkGenerator generator,
      RandomSource random,
      BoundingBox clip,
      ChunkPos chunk,
      BlockPos origin) {}
}
