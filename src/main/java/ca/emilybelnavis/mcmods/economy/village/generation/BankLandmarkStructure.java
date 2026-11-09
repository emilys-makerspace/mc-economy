package ca.emilybelnavis.mcmods.economy.village.generation;

import ca.emilybelnavis.mcmods.economy.village.BranchPlans;
import com.mojang.serialization.MapCodec;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.*;

/** Passive native bank index, mirroring the first valid winner of the original village set. */
public final class BankLandmarkStructure extends Structure {
  public static final MapCodec<BankLandmarkStructure> CODEC =
      simpleCodec(BankLandmarkStructure::new);

  public BankLandmarkStructure(StructureSettings settings) {
    super(settings);
  }

  @Override
  public StructureType<?> type() {
    return VillageGeneration.BANK_TYPE.get();
  }

  @Override
  protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
    var center = context.chunkPos().getMiddleBlockPosition(0);
    // Presence checks have no chunk and cannot know captured plans. Stay conservative;
    // actual generation below alone determines whether a valid bank start exists.
    if (!(context.heightAccessor() instanceof ChunkAccess chunk)) {
      int y =
          context
              .chunkGenerator()
              .getFirstOccupiedHeight(
                  center.getX(),
                  center.getZ(),
                  Heightmap.Types.WORLD_SURFACE_WG,
                  context.heightAccessor(),
                  context.randomState());
      return Optional.of(
          new GenerationStub(new BlockPos(center.getX(), y, center.getZ()), builder -> {}));
    }
    var sets = context.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
    var source = sets.get(ResourceLocation.withDefaultNamespace("villages"));
    if (source == null) return Optional.empty();
    var placementState =
        ChunkGeneratorStructureState.createForNormal(
            context.randomState(), context.seed(), context.biomeSource(), sets.asLookup());
    if (!source
        .placement()
        .isStructureChunk(placementState, context.chunkPos().x, context.chunkPos().z))
      return Optional.empty();
    // Same weighted-without-replacement sequence as ChunkGenerator.createStructures.
    var remaining = new ArrayList<>(source.structures());
    int total = remaining.stream().mapToInt(StructureSet.StructureSelectionEntry::weight).sum();
    var random = new WorldgenRandom(new LegacyRandomSource(0L));
    random.setLargeFeatureSeed(context.seed(), context.chunkPos().x, context.chunkPos().z);
    while (!remaining.isEmpty()) {
      int roll = random.nextInt(total), index = 0;
      for (var entry : remaining) {
        roll -= entry.weight();
        if (roll < 0) break;
        index++;
      }
      var entry = remaining.get(index);
      var original = entry.structure().value();
      var start = chunk.getStartForStructure(original);
      if (start == null || !start.isValid())
        start =
            original.generate(
                context.registryAccess(),
                context.chunkGenerator(),
                context.biomeSource(),
                context.randomState(),
                context.structureTemplateManager(),
                context.seed(),
                context.chunkPos(),
                start == null ? 0 : start.getReferences(),
                chunk,
                original.biomes()::contains);
      if (start.isValid()) {
        // A valid bankless village is still the winner. Never fall through to another style.
        if (!(original instanceof BankedVillageStructure)) return Optional.empty();
        var markers = new ArrayList<BankLandmarkPiece>();
        for (var piece : start.getPieces())
          if (piece instanceof VillageGenerationPiece metadata
              && metadata.branchSlot() == -1
              && metadata.plan().captureStatus() == BranchPlans.CaptureStatus.CAPTURED) {
            for (var choice : metadata.plan().branches())
              if (choice.state() == BranchPlans.State.RESERVED && choice.bounds() != null)
                markers.add(
                    new BankLandmarkPiece(choice.slot(), BankLandmarks.bounds(choice.bounds())));
          }
        if (markers.isEmpty()) return Optional.empty();
        var position = markers.getFirst().getBoundingBox().getCenter();
        return Optional.of(
            new GenerationStub(position, builder -> markers.forEach(builder::addPiece)));
      }
      remaining.remove(index);
      total -= entry.weight();
    }
    return Optional.empty();
  }
}
