package ca.emilybelnavis.mcmods.economy.village.generation;

import ca.emilybelnavis.mcmods.economy.village.BranchPlans;
import java.util.ArrayList;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;

/** Loaded-only backfill into Minecraft's native structure index. */
public final class BankLandmarks {
  private BankLandmarks() {}

  public static void record(
      ServerLevel level, BranchPlans.Source source, BranchPlans.BranchPlanV1 choice) {
    if (!level.getServer().isSameThread())
      throw new IllegalStateException("Bank landmark requires server thread");
    if (choice.bounds() == null
        || !source.dimension().equals(level.dimension().location().toString())) return;
    var chunk = level.getChunkSource().getChunkNow(source.startX(), source.startZ());
    if (chunk == null) return;
    var structure =
        level
            .registryAccess()
            .registryOrThrow(Registries.STRUCTURE)
            .get(ResourceLocation.fromNamespaceAndPath("economy", "bank"));
    if (!(structure instanceof BankLandmarkStructure)) return;
    var old = chunk.getStartForStructure(structure);
    var pieces = new ArrayList<StructurePiece>();
    var box = bounds(choice.bounds());
    if (old != null && old.isValid())
      for (var piece : old.getPieces()) {
        if (piece instanceof BankLandmarkPiece bank && bank.slot() == choice.slot()) {
          var b = bank.getBoundingBox();
          if (b.minX() == box.minX()
              && b.minY() == box.minY()
              && b.minZ() == box.minZ()
              && b.maxX() == box.maxX()
              && b.maxY() == box.maxY()
              && b.maxZ() == box.maxZ()) return;
        } else pieces.add(piece);
      }
    pieces.add(new BankLandmarkPiece(choice.slot(), box));
    chunk.setStartForStructure(
        structure,
        new StructureStart(
            structure,
            chunk.getPos(),
            old == null ? 0 : old.getReferences(),
            new PiecesContainer(pieces)));
    chunk.setUnsaved(true);
    level.onStructureStartsAvailable(chunk);
  }

  static BoundingBox bounds(BranchPlans.Bounds b) {
    return new BoundingBox(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
  }
}
