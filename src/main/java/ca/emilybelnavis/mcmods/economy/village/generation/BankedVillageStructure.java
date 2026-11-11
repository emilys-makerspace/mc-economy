package ca.emilybelnavis.mcmods.economy.village.generation;

import ca.emilybelnavis.mcmods.economy.village.BranchPlans.*;
import ca.emilybelnavis.mcmods.economy.village.BranchSelection;
import ca.emilybelnavis.mcmods.economy.village.GenerationRuntime;
import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;

/**
 * Public-API composition preserving original vanilla identity, generation context, layout order and
 * settings.
 */
public final class BankedVillageStructure extends Structure {
  public static final MapCodec<BankedVillageStructure> CODEC =
      RecordCodecBuilder.mapCodec(
          i ->
              i.group(
                      settingsCodec(i),
                      JigsawStructure.CODEC.codec().fieldOf("delegate").forGetter(s -> s.delegate),
                      Codec.STRING.fieldOf("style").forGetter(s -> s.style))
                  .apply(i, BankedVillageStructure::new));
  private final JigsawStructure delegate;
  private final String style;

  public BankedVillageStructure(
      StructureSettings settings, JigsawStructure delegate, String style) {
    super(settings);
    this.delegate = Objects.requireNonNull(delegate);
    this.style = Objects.requireNonNull(style);
    if (!Set.of("plains", "desert", "savanna", "snowy", "taiga").contains(style)
        || !settings.equals(
            delegate.modifiableStructureInfo().getOriginalStructureInfo().structureSettings()))
      throw new IllegalArgumentException("Village wrapper settings/style");
  }

  @Override
  public StructureType<?> type() {
    return VillageGeneration.TYPE.get();
  }

  @Override
  protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
    // Vanilla tests this same stub biome before materializing its jigsaw layout.
    // Reject ineligible biome probes before doing any bank terrain search.
    var original = delegate.findValidGenerationPoint(context);
    if (original.isEmpty()) return Optional.empty();
    var stub = original.get();
    return Optional.of(
        new GenerationStub(
            stub.position(),
            builder -> {
              if (context.heightAccessor()
                  instanceof net.minecraft.world.level.chunk.ChunkAccess chunk) {
                var cache = chunk.getData(VillageGeneration.LAYOUT_CACHE.get());
                synchronized (cache) {
                  var pieces =
                      cache.computeIfAbsent(
                          style, key -> List.copyOf(decorate(context, stub).build().pieces()));
                  for (var piece : pieces) builder.addPiece(piece);
                }
              } else
                for (var piece : decorate(context, stub).build().pieces()) builder.addPiece(piece);
            }));
  }

  private StructurePiecesBuilder decorate(GenerationContext context, GenerationStub stub) {
    var builder = stub.getPiecesBuilder();
    var pieces = builder.build().pieces();
    var result = VillageClassifier.classify(style, pieces, context);
    if (!result.supported() || result.abandoned() || result.buildings() == 0) return builder;
    var publication = GenerationRuntime.publication(context.chunkGenerator());
    if (publication == null || !publication.dimension().equals("minecraft:overworld"))
      return builder;
    var boxes = pieces.stream().map(p -> bounds(p.getBoundingBox())).toList();
    var junctions = new ArrayList<Point>();
    for (var piece : pieces)
      if (piece instanceof PoolElementStructurePiece p)
        for (var j : p.getJunctions()) {
          var point = new Point(j.getSourceX(), j.getSourceGroundY(), j.getSourceZ());
          if (!junctions.contains(point)) junctions.add(point);
        }
    if (junctions.isEmpty()) return builder;
    var source =
        new Source(
            publication.dimension(),
            "minecraft:village_" + style,
            context.chunkPos().x,
            context.chunkPos().z);
    var uncaptured =
        new VillagePlanV1(
            source,
            style,
            result.buildings(),
            boxes,
            junctions,
            null,
            CaptureStatus.CAPTURE_PENDING,
            List.of(),
            "AWAITING_PLANNING_PUBLICATION");
    VillagePlanV1 plan = uncaptured;
    var candidates = new ArrayList<VillageSites.Candidate>();
    if (publication.world() != null && publication.snapshot() != null) {
      var chosen = BranchSelection.capture(uncaptured, publication.snapshot(), context.seed());
      var branches = new ArrayList<BranchPlanV1>();
      var reserved = new ArrayList<Bounds>();
      for (var branch : chosen.branches()) {
        if (branch.state() == State.OMITTED || branch.state() == State.PROVIDER_PENDING) {
          branches.add(branch);
          continue;
        }
        var site = VillageSites.search(context, publication.world(), chosen, branch, reserved);
        if (site.candidate().isPresent()) {
          var candidate = site.candidate().get();
          branches.add(candidate.choice());
          var all = candidate.cells().stream().map(VillageSites.Cell::position).toList();
          reserved.add(
              new Bounds(
                  all.stream().mapToInt(Point::x).min().orElseThrow(),
                  all.stream().mapToInt(Point::y).min().orElseThrow(),
                  all.stream().mapToInt(Point::z).min().orElseThrow(),
                  all.stream().mapToInt(Point::x).max().orElseThrow(),
                  all.stream().mapToInt(Point::y).max().orElseThrow(),
                  all.stream().mapToInt(Point::z).max().orElseThrow()));
          candidates.add(candidate);
        } else
          branches.add(
              new BranchPlanV1(
                  branch.slot(),
                  branch.required(),
                  branch.size(),
                  branch.institution(),
                  branch.nativeCurrency(),
                  null,
                  null,
                  null,
                  null,
                  List.of(),
                  List.of(),
                  List.of(),
                  State.SITE_PENDING,
                  site.reason(),
                  0,
                  branch.optionalRoll()));
      }
      plan =
          new VillagePlanV1(
              source,
              style,
              result.buildings(),
              boxes,
              junctions,
              chosen.snapshot(),
              CaptureStatus.CAPTURED,
              branches,
              "PINNED_LAYOUT_CAPTURED");
    }
    builder.addPiece(
        new VillageGenerationPiece(
            plan,
            publication.world(),
            -1,
            List.of(),
            List.of(),
            new BoundingBox(
                context.chunkPos().getMinBlockX(),
                stub.position().getY(),
                context.chunkPos().getMinBlockZ(),
                context.chunkPos().getMinBlockX(),
                stub.position().getY(),
                context.chunkPos().getMinBlockZ())));
    for (var candidate : candidates) {
      var points = candidate.cells().stream().map(VillageSites.Cell::position).toList();
      var box =
          new BoundingBox(
              points.stream().mapToInt(Point::x).min().orElseThrow(),
              points.stream().mapToInt(Point::y).min().orElseThrow(),
              points.stream().mapToInt(Point::z).min().orElseThrow(),
              points.stream().mapToInt(Point::x).max().orElseThrow(),
              points.stream().mapToInt(Point::y).max().orElseThrow(),
              points.stream().mapToInt(Point::z).max().orElseThrow());
      builder.addPiece(
          new VillageGenerationPiece(
              plan,
              publication.world(),
              candidate.choice().slot(),
              candidate.cells(),
              candidate.supports(),
              box));
    }
    return builder;
  }

  private static Bounds bounds(BoundingBox b) {
    return new Bounds(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
  }
}
