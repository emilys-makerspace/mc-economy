package ca.emilybelnavis.mcmods.economy.village.generation;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.terminal.TerminalBlockEntity;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans.*;
import java.util.*;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * Versioned, chunk-clipped generation effect. Shared piece fields are immutable across concurrent
 * chunks.
 */
public final class VillageGenerationPiece extends StructurePiece
    implements net.neoforged.neoforge.common.world.PieceBeardifierModifier {
  private final VillagePlanV1 plan;
  private final UUID world;
  private final int branchSlot;
  private final List<VillageSites.Cell> cells;
  private final List<Point> supports;

  public VillageGenerationPiece(
      VillagePlanV1 plan,
      WorldId world,
      int branchSlot,
      List<VillageSites.Cell> cells,
      List<Point> supports,
      BoundingBox bounds) {
    super(VillageGeneration.PIECE.get(), 0, bounds);
    this.plan = plan;
    this.world = world == null ? null : world.value();
    this.branchSlot = branchSlot;
    this.cells = List.copyOf(cells);
    this.supports = List.copyOf(supports);
    if (cells.size() > 20000) throw new IllegalArgumentException("Generation cell limit");
    validate();
  }

  public VillageGenerationPiece(StructurePieceSerializationContext context, CompoundTag tag) {
    super(VillageGeneration.PIECE.get(), tag);
    if (tag.getInt("a7_version") != 1)
      throw new IllegalArgumentException("Unsupported A7 generation piece");
    this.plan = BranchPlans.decode(tag.getString("a7_plan"));
    this.world = tag.hasUUID("a7_world") ? tag.getUUID("a7_world") : null;
    this.branchSlot = tag.getInt("a7_slot");
    var entries = tag.getList("a7_cells", Tag.TAG_COMPOUND);
    if (entries.size() > 20000 || branchSlot < -1 || branchSlot > 2)
      throw new IllegalArgumentException("Generation piece bounds");
    var result = new ArrayList<VillageSites.Cell>();
    for (int i = 0; i < entries.size(); i++) {
      var e = entries.getCompound(i);
      String state = e.getString("state"),
          nbt = e.contains("nbt") ? e.getString("nbt") : null,
          before = e.contains("before") ? e.getString("before") : null;
      if (state.length() > 1024 || nbt != null && nbt.length() > 16384)
        throw new IllegalArgumentException("Generation cell payload");
      result.add(
          new VillageSites.Cell(
              new Point(e.getInt("x"), e.getInt("y"), e.getInt("z")), state, nbt, before));
    }
    cells = List.copyOf(result);
    var supportEntries = tag.getList("a7_supports", Tag.TAG_COMPOUND);
    if (supportEntries.size() > 20000)
      throw new IllegalArgumentException("Generation support limit");
    var supporting = new ArrayList<Point>();
    for (int i = 0; i < supportEntries.size(); i++) {
      var e = supportEntries.getCompound(i);
      supporting.add(new Point(e.getInt("x"), e.getInt("y"), e.getInt("z")));
    }
    supports = List.copyOf(supporting);
    validate();
  }

  @Override
  public BoundingBox getBeardifierBox() {
    return boundingBox;
  }

  @Override
  public TerrainAdjustment getTerrainAdjustment() {
    return TerrainAdjustment.NONE;
  }

  @Override
  public int getGroundLevelDelta() {
    return 0;
  }

  private void validate() {
    if (branchSlot == -1) {
      if (!cells.isEmpty() || !supports.isEmpty())
        throw new IllegalArgumentException("Metadata piece has physical effects");
      return;
    }
    if (world == null || plan.captureStatus() != CaptureStatus.CAPTURED)
      throw new IllegalArgumentException("Physical piece lacks world/capture");
    var branch =
        plan.branches().stream()
            .filter(b -> b.slot() == branchSlot)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Missing branch slot"));
    if (branch.state() != State.RESERVED || cells.isEmpty())
      throw new IllegalArgumentException("Unreserved physical piece");
    String digest = VillageSites.afterDigest(cells);
    var chunks = new HashSet<String>();
    var positions = new HashSet<Point>();
    for (var cell : cells) {
      var p = cell.position();
      var block = new BlockPos(p.x(), p.y(), p.z());
      if (!positions.add(p)
          || !boundingBox.isInside(block)
          || Math.abs((p.x() >> 4) - plan.source().startX()) > 8
          || Math.abs((p.z() >> 4) - plan.source().startZ()) > 8)
        throw new IllegalArgumentException("Physical cell outside captured bounds");
      if (plan.originalBounds().stream()
          .anyMatch(
              b ->
                  p.x() >= b.minX() && p.x() <= b.maxX() && p.z() >= b.minZ() && p.z() <= b.maxZ()))
        throw new IllegalArgumentException("Physical cell overlaps original village");
      chunks.add((p.x() >> 4) + "," + (p.z() >> 4));
    }
    var expected = new HashSet<String>();
    for (var manifest : branch.manifests()) {
      if (!manifest.afterDigest().equals(digest))
        throw new IllegalArgumentException("Physical piece digest mismatch");
      expected.add(manifest.chunkX() + "," + manifest.chunkZ());
    }
    if (!chunks.equals(expected))
      throw new IllegalArgumentException("Physical piece manifest mismatch");
  }

  public VillagePlanV1 plan() {
    return plan;
  }

  public int branchSlot() {
    return branchSlot;
  }

  public Optional<WorldId> world() {
    return world == null ? Optional.empty() : Optional.of(new WorldId(world));
  }

  public List<VillageSites.Cell> cells() {
    return cells;
  }

  @Override
  protected void addAdditionalSaveData(
      StructurePieceSerializationContext context, CompoundTag tag) {
    tag.putInt("a7_version", 1);
    tag.putString("a7_plan", BranchPlans.encode(plan));
    if (world != null) tag.putUUID("a7_world", world);
    tag.putInt("a7_slot", branchSlot);
    var list = new ListTag();
    for (var cell : cells) {
      var e = new CompoundTag();
      e.putInt("x", cell.position().x());
      e.putInt("y", cell.position().y());
      e.putInt("z", cell.position().z());
      e.putString("state", cell.blockState());
      if (cell.blockEntitySnbt() != null) e.putString("nbt", cell.blockEntitySnbt());
      if (cell.expectedBefore() != null) e.putString("before", cell.expectedBefore());
      list.add(e);
    }
    tag.put("a7_cells", list);
    var supportList = new ListTag();
    for (var p : supports) {
      var e = new CompoundTag();
      e.putInt("x", p.x());
      e.putInt("y", p.y());
      e.putInt("z", p.z());
      supportList.add(e);
    }
    tag.put("a7_supports", supportList);
  }

  private static boolean supportSafe(WorldGenLevel level, BlockPos position) {
    var p = position;
    for (int i = 0; i <= 6; i++) {
      if (p.getY() < level.getMinBuildHeight() || p.getY() >= level.getMaxBuildHeight())
        return false;
      var state = level.getBlockState(p);
      if (!state.getFluidState().isEmpty()
          || state.hasBlockEntity()
          || !state.isFaceSturdy(level, p, Direction.UP)) return false;
      if (!(state.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)) return true;
      p = p.below();
    }
    return false;
  }

  @Override
  public void postProcess(
      WorldGenLevel level,
      StructureManager structures,
      ChunkGenerator generator,
      RandomSource random,
      BoundingBox clip,
      ChunkPos chunk,
      BlockPos origin) {
    if (!(level instanceof net.minecraft.server.level.WorldGenRegion)
        || branchSlot < 0
        || world == null
        || !level.getLevel().dimension().location().toString().equals(plan.source().dimension()))
      return;
    var branch =
        plan.branches().stream().filter(b -> b.slot() == branchSlot).findFirst().orElseThrow();
    UUID branchId =
        BranchPlans.branchId(BranchPlans.id(new WorldId(world), plan.source()), branchSlot);
    var current = level.getChunk(chunk.x, chunk.z);
    var manifest =
        branch.manifests().stream()
            .filter(m -> m.chunkX() == chunk.x && m.chunkZ() == chunk.z)
            .findFirst()
            .orElse(null);
    if (manifest == null) return; // Bounding-box-only chunks have no fragment to place.
    if (VillageGeneration.receipt(current, branchId, chunk, manifest)
        != VillageGeneration.Receipt.MISSING) return;
    var fragment =
        cells.stream()
            .filter(
                c ->
                    clip.isInside(
                        new BlockPos(c.position().x(), c.position().y(), c.position().z())))
            .toList();
    if (fragment.isEmpty()) return;
    for (var support : supports) {
      var p = new BlockPos(support.x(), support.y(), support.z());
      if ((p.getX() >> 4) != chunk.x || (p.getZ() >> 4) != chunk.z) continue;
      var state = level.getBlockState(p);
      if (!supportSafe(level, p)) {
        VillageGeneration.conflict(
            current,
            branchId,
            chunk,
            "SUPPORT_UNSAFE " + p + " " + BlockStateParser.serialize(state));
        return;
      }
    }
    // Original layout boxes and actual per-chunk before-images are preserved; uncertain prior
    // effects are never filled in.
    var cuts = new HashMap<Long, Integer>();
    var columnFloors = new HashMap<Long, Integer>();
    for (var cell : fragment) {
      var p = cell.position();
      long column = ChunkPos.asLong(p.x(), p.z());
      if (p.x() >= branch.bounds().minX()
          && p.x() <= branch.bounds().maxX()
          && p.z() >= branch.bounds().minZ()
          && p.z() <= branch.bounds().maxZ()) {
        columnFloors.put(column, branch.transform().origin().y());
      } else {
        // Paths reserve three clearance cells above their walking surface. Foundations
        // below that surface are support work, not additional terrain leveling.
        columnFloors.merge(column, p.y() - 3, Math::max);
      }
    }
    for (var cell : fragment) {
      var p = new BlockPos(cell.position().x(), cell.position().y(), cell.position().z());
      if (!level.getBlockState(p).isAir()
          && !VillageSites.naturalVegetation(level.getBlockState(p))
          && !BlockStateParser.serialize(level.getBlockState(p)).equals(cell.blockState())
          && p.getY() >= columnFloors.get(ChunkPos.asLong(p.getX(), p.getZ()))) {
        long column = ChunkPos.asLong(p.getX(), p.getZ());
        int count = cuts.merge(column, 1, Integer::sum);
        if (count > 3) {
          VillageGeneration.conflict(current, branchId, chunk, "LEVELING_LIMIT " + p);
          return;
        }
      }
      if (p.getY() < level.getMinBuildHeight()
          || p.getY() >= level.getMaxBuildHeight()
          || level.getBlockEntity(p) != null
          || !VillageSites.naturalOrAir(level.getBlockState(p))
          || cell.expectedBefore() != null
              && !BlockStateParser.serialize(level.getBlockState(p))
                  .equals(cell.expectedBefore())) {
        VillageGeneration.conflict(
            current,
            branchId,
            chunk,
            "BLOCK_PROTECTED " + p + " " + BlockStateParser.serialize(level.getBlockState(p)));
        return;
      }
    }
    String before =
        fragment.stream()
            .map(
                c -> {
                  var p = new BlockPos(c.position().x(), c.position().y(), c.position().z());
                  return c.position() + "=" + BlockStateParser.serialize(level.getBlockState(p));
                })
            .toList()
            .toString();
    VillageGeneration.beforeImage(current, branchId, chunk, before, manifest);
    try {
      for (var cell : fragment) {
        var p = new BlockPos(cell.position().x(), cell.position().y(), cell.position().z());
        var state =
            BlockStateParser.parseForBlock(
                    level.holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                    cell.blockState(),
                    false)
                .blockState();
        if (!level.setBlock(p, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)
            && !level.getBlockState(p).equals(state))
          throw new IllegalStateException("Generation block placement denied");
        if (state.getBlock()
                instanceof ca.emilybelnavis.mcmods.economy.terminal.WideTerminalBlock wide
            && state.getValue(wide.cellProperty()) != 0) {
          // WorldGenRegion queues DUMMY NBT for every EntityBlock state, including satellites.
          current.removeBlockEntity(p);
          continue;
        }
        if (state.getBlock() instanceof ca.emilybelnavis.mcmods.economy.banker.desk.DeskBlock
            && state.getValue(ca.emilybelnavis.mcmods.economy.banker.desk.DeskBlock.CELL) != 0) {
          current.removeBlockEntity(p);
          continue;
        }
        var entity = level.getBlockEntity(p);
        if (entity != null && cell.blockEntitySnbt() != null) {
          var nbt = TagParser.parseTag(cell.blockEntitySnbt());
          nbt.putInt("x", p.getX());
          nbt.putInt("y", p.getY());
          nbt.putInt("z", p.getZ());
          entity.loadWithComponents(nbt, level.registryAccess());
          entity.setChanged();
        }
        if (entity instanceof TerminalBlockEntity terminal) {
          var anchor =
              branch.services().stream()
                  .filter(a -> a.position().equals(cell.position()))
                  .findFirst()
                  .orElse(null);
          if (anchor == null)
            throw new IllegalStateException("Generated terminal outside captured service anchors");
          terminal.generatedPlacement(
              branchId,
              anchor.generation(),
              BranchPlans.operation(branchId, anchor.slot(), "generated-placement", 1));
        }
        if (entity instanceof ca.emilybelnavis.mcmods.economy.banker.desk.DeskBlockEntity desk) {
          var anchor =
              branch.desks().stream()
                  .filter(a -> a.position().equals(cell.position()))
                  .findFirst()
                  .orElseThrow(
                      () -> new IllegalStateException("Generated desk outside captured anchors"));
          desk.generatedPlacement(
              branchId,
              anchor.generation(),
              BranchPlans.operation(branchId, anchor.slot(), "generated-desk", 1));
        }
      }
      VillageGeneration.receipt(current, branchId, chunk, VillageGeneration.Receipt.COMPLETE);
    } catch (Exception conflict) {
      // No rollback after uncertain world mutation: later startup may observe partial effects,
      // never rebuild them.
      VillageGeneration.conflict(
          current,
          branchId,
          chunk,
          "PLACEMENT_EXCEPTION "
              + conflict.getClass().getSimpleName()
              + " "
              + conflict.getMessage());
    }
  }
}
