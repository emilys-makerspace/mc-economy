package ca.emilybelnavis.mcmods.economy.village.generation;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.banker.desk.DeskBlock;
import ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy.Kind;
import ca.emilybelnavis.mcmods.economy.terminal.BranchKioskBlock;
import ca.emilybelnavis.mcmods.economy.terminal.WideTerminalBlock;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans.*;
import ca.emilybelnavis.mcmods.economy.village.GenerationRuntime;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;

/**
 * Bounded deterministic site/path planning. Loaded retries only add into AIR and never clear
 * construction.
 */
public final class VillageSites {
  private static final int BANK_WIDTH = 17, BANK_HEIGHT = 15, BANK_DEPTH = 17;
  private static final Point BANK_ENTRANCE = new Point(0, 1, 8);
  private static final Point BANK_REST = new Point(14, 1, 9);

  public record Cell(
      Point position, String blockState, String blockEntitySnbt, String expectedBefore) {
    public Cell(Point position, String blockState, String blockEntitySnbt) {
      this(position, blockState, blockEntitySnbt, null);
    }
  }

  public record Candidate(
      BranchPlanV1 choice, List<Cell> cells, List<Point> supports, String reason) {
    public Candidate {
      cells = List.copyOf(cells);
      supports = List.copyOf(supports);
    }
  }

  public record Search(Optional<Candidate> candidate, String reason) {}

  private interface Terrain {
    int surface(int x, int z);

    BlockState block(int x, int y, int z);

    boolean available(int x, int z);

    StructureTemplateManager templates();

    HolderLookup<Block> blocks();

    boolean additive();

    long seed();

    int minY();

    int maxY();

    default void beginBand(int band) {}
  }

  private static final class TerrainSampleBudget extends RuntimeException {
    private TerrainSampleBudget() {
      super(null, null, false, false);
    }
  }

  private VillageSites() {}

  public static Search search(
      Structure.GenerationContext context,
      WorldId world,
      VillagePlanV1 plan,
      BranchPlanV1 branch,
      List<Bounds> reservations) {
    Terrain terrain =
        new Terrain() {
          final Set<Long> sampled = new HashSet<>();
          int bandSamples, bandLimit;

          public void beginBand(int band) {
            bandSamples = 0;
            bandLimit = 1024;
          }

          private void sample(int x, int z) {
            long key = net.minecraft.world.level.ChunkPos.asLong(x, z);
            if (!sampled.contains(key)) {
              if (bandSamples >= bandLimit) throw new TerrainSampleBudget();
              sampled.add(key);
              bandSamples++;
            }
          }

          final Map<Long, Integer> heights = new HashMap<>();
          final Map<Long, net.minecraft.world.level.NoiseColumn> columns = new HashMap<>();

          public int surface(int x, int z) {
            sample(x, z);
            return heights.computeIfAbsent(
                net.minecraft.world.level.ChunkPos.asLong(x, z),
                key ->
                    context
                        .chunkGenerator()
                        .getBaseHeight(
                            x,
                            z,
                            Heightmap.Types.WORLD_SURFACE_WG,
                            context.heightAccessor(),
                            context.randomState()));
          }

          public BlockState block(int x, int y, int z) {
            sample(x, z);
            return columns
                .computeIfAbsent(
                    net.minecraft.world.level.ChunkPos.asLong(x, z),
                    key ->
                        context
                            .chunkGenerator()
                            .getBaseColumn(x, z, context.heightAccessor(), context.randomState()))
                .getBlock(y);
          }

          public boolean available(int x, int z) {
            return true;
          }

          public StructureTemplateManager templates() {
            return context.structureTemplateManager();
          }

          public HolderLookup<Block> blocks() {
            return context
                .registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK);
          }

          public boolean additive() {
            return false;
          }

          public long seed() {
            return context.seed();
          }

          public int minY() {
            return context.heightAccessor().getMinBuildHeight();
          }

          public int maxY() {
            return context.heightAccessor().getMaxBuildHeight();
          }
        };
    return search(terrain, world, plan, branch, reservations);
  }

  public static Optional<Candidate> searchLoaded(
      ServerLevel level, VillagePlanV1 plan, BranchPlanV1 branch) {
    var publication = GenerationRuntime.publication(level.getChunkSource().getGenerator());
    if (publication == null || publication.world() == null) return Optional.empty();
    Terrain terrain =
        new Terrain() {
          public int surface(int x, int z) {
            return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
          }

          public BlockState block(int x, int y, int z) {
            return level.getBlockState(new BlockPos(x, y, z));
          }

          public boolean available(int x, int z) {
            return level.hasChunkAt(new BlockPos(x, level.getMinBuildHeight(), z));
          }

          public StructureTemplateManager templates() {
            return level.getStructureManager();
          }

          public HolderLookup<Block> blocks() {
            return level.holderLookup(net.minecraft.core.registries.Registries.BLOCK);
          }

          public boolean additive() {
            return true;
          }

          public long seed() {
            return level.getSeed();
          }

          public int minY() {
            return level.getMinBuildHeight();
          }

          public int maxY() {
            return level.getMaxBuildHeight();
          }
        };
    return search(
            terrain,
            publication.world(),
            plan,
            branch,
            plan.branches().stream()
                .filter(b -> b.slot() != branch.slot() && b.bounds() != null)
                .map(BranchPlanV1::bounds)
                .toList())
        .candidate();
  }

  private static Search search(
      Terrain terrain,
      WorldId world,
      VillagePlanV1 plan,
      BranchPlanV1 branch,
      List<Bounds> reservations) {
    if (branch.institution() == null) return new Search(Optional.empty(), "PROVIDER_PENDING");
    int w = BANK_WIDTH, d = BANK_DEPTH, h = BANK_HEIGHT;
    var original = union(plan.originalBounds());
    String shortBank = branch.institution().substring(branch.institution().lastIndexOf('/') + 1);
    var id = ResourceLocation.fromNamespaceAndPath("economy", "banks/" + shortBank);
    var template = terrain.templates().get(id);
    if (template.isEmpty()) return new Search(Optional.empty(), "TEMPLATE_UNAVAILABLE");
    var size = template.get().getSize();
    if (size.getX() != w || size.getY() != h || size.getZ() != d)
      return new Search(Optional.empty(), "TEMPLATE_ENVELOPE");
    String last = "TERRAIN_EXHAUSTED";
    boolean exhausted = false;
    int facingOffset = siteFacing(terrain.seed(), plan.source(), branch.slot());
    for (int band = 0; band < 3; band++) {
      terrain.beginBand(band);
      try {
        int budget = band == 2 ? 512 : 256, tested = 0;
        var candidates = new ArrayList<int[]>();
        int range = band == 0 ? 0 : band == 1 ? 32 : 96;
        for (int x = original.minX() - range; x <= original.maxX() + range; x += 4)
          for (int z = original.minZ() - range; z <= original.maxZ() + range; z += 4)
            candidates.add(new int[] {x, z});
        candidates.sort(
            Comparator.<int[]>comparingDouble(p -> distance(p[0], p[1], original))
                .thenComparingInt(p -> p[0])
                .thenComparingInt(p -> p[1]));
        for (var point : candidates) {
          if (tested >= budget) break;
          boolean inBand = false;
          for (int facingIndex = 0; facingIndex < 4; facingIndex++) {
            int turn = (facingIndex + facingOffset) & 3;
            int rw = (turn & 1) == 0 ? w : d, rd = (turn & 1) == 0 ? d : w;
            var entrance =
                bankPoint(new Transform(new Point(point[0], 0, point[1]), turn), BANK_ENTRANCE);
            double distance = distance(entrance.x() + .5, entrance.z() + .5, original);
            boolean inside =
                point[0] - 1 >= original.minX()
                    && point[0] + rw <= original.maxX()
                    && point[1] - 1 >= original.minZ()
                    && point[1] + rd <= original.maxZ();
            if (band == 0 && !inside
                || band == 1 && (distance <= 0 || distance > 32)
                || band == 2 && (distance <= 32 || distance > 96)) continue;
            inBand = true;
            var box =
                new Bounds(point[0], -2048, point[1], point[0] + rw - 1, 2047, point[1] + rd - 1);
            if (!referenceReach(box, plan.source())) {
              last = "REFERENCE_REACH";
              continue;
            }
            if (collides(box, plan.originalBounds(), 1) || collides(box, reservations, 1)) {
              last = "PRESERVATION_OBSTACLE";
              continue;
            }
            var result =
                candidate(
                    terrain,
                    world,
                    plan,
                    branch,
                    point[0],
                    point[1],
                    turn,
                    w,
                    d,
                    h,
                    template.get(),
                    id.toString(),
                    reservations);
            if (result.isPresent()) return new Search(result, "SITE_FOUND");
            last =
                terrain.additive()
                    ? "RETRY_REQUIRES_CLEAR_SUPPORTED_AIR"
                    : "TERRAIN_OR_PATH_REJECTED";
          }
          if (inBand) tested++;
        }
      } catch (TerrainSampleBudget limit) {
        exhausted = true;
      }
    }
    return new Search(Optional.empty(), exhausted ? "TERRAIN_SAMPLE_BUDGET" : last);
  }

  private static Optional<Candidate> candidate(
      Terrain t,
      WorldId world,
      VillagePlanV1 village,
      BranchPlanV1 branch,
      int x,
      int z,
      int turn,
      int w,
      int d,
      int h,
      StructureTemplate template,
      String templateId,
      List<Bounds> reservations) {
    int rw = (turn & 1) == 0 ? w : d, rd = (turn & 1) == 0 ? d : w;
    int cornerMin = Integer.MAX_VALUE, cornerMax = Integer.MIN_VALUE;
    for (int dx : new int[] {0, rw - 1})
      for (int dz : new int[] {0, rd - 1}) {
        if (!t.available(x + dx, z + dz)) return Optional.empty();
        int height = t.surface(x + dx, z + dz);
        cornerMin = Math.min(cornerMin, height);
        cornerMax = Math.max(cornerMax, height);
      }
    if (cornerMax - cornerMin > 9) return Optional.empty();
    var heights = new ArrayList<Integer>();
    for (int dx = 0; dx < rw; dx++)
      for (int dz = 0; dz < rd; dz++) {
        if (!t.available(x + dx, z + dz)) return Optional.empty();
        heights.add(t.surface(x + dx, z + dz));
      }
    Collections.sort(heights);
    int floor = heights.get((heights.size() - 1) / 2) - (t.additive() ? 0 : 1);
    for (int first : heights) if (floor - first > 6 || first - floor > 3) return Optional.empty();
    if (floor < Math.max(-2038, t.minY() + 7) || floor + h - 1 >= Math.min(2047, t.maxY()))
      return Optional.empty();
    var origin = new Point(x, floor, z);
    var box = new Bounds(x, floor - 6, z, x + rw - 1, floor + h - 1, z + rd - 1);
    var transform = new Transform(origin, turn);
    var writes = new LinkedHashMap<Point, Cell>();
    var supports = new ArrayList<Point>();
    for (int dx = 0; dx < rw; dx++)
      for (int dz = 0; dz < rd; dz++) {
        int gx = x + dx, gz = z + dz, first = t.surface(gx, gz), depth = floor - first;
        if (depth > 6 || first - floor > 3) return Optional.empty();
        int support = floor - 1, filled = 0;
        while (support >= floor - 7 && t.block(gx, support, gz).isAir()) {
          filled++;
          support--;
        }
        if (support < floor - 7 || !sturdy(t.block(gx, support, gz))) return Optional.empty();
        supports.add(new Point(gx, support, gz));
        for (int y = support + 1; y < floor; y++)
          writes.put(
              new Point(gx, y, gz), new Cell(new Point(gx, y, gz), "minecraft:stone_bricks", null));
        for (int y = floor; y < floor + h; y++) {
          var current = t.block(gx, y, gz);
          if (t.additive() ? !current.isAir() : !naturalOrAir(current)) return Optional.empty();
          var p = new Point(gx, y, gz);
          writes.put(p, new Cell(p, "minecraft:air", null));
        }
      }
    var rotation = Rotation.values()[turn];
    var entrance = bankPoint(transform, BANK_ENTRANCE);
    var facing = rotation.rotate(Direction.WEST);
    var pathStart =
        new Point(entrance.x() + facing.getStepX(), floor, entrance.z() + facing.getStepZ());
    var path =
        path(
            t,
            pathStart,
            village.originalBounds(),
            village.junctions(),
            reservations,
            box,
            village.source(),
            village.style());
    if (path.isEmpty()) return Optional.empty();
    var raw = template.save(new CompoundTag());
    var palette = raw.getList("palette", Tag.TAG_COMPOUND);
    var blocks = raw.getList("blocks", Tag.TAG_COMPOUND);
    var kiosks = new ArrayList<Point>();
    var atms = new ArrayList<Point>();
    var desks = new ArrayList<Point>();
    for (int i = 0; i < blocks.size(); i++) {
      var info = blocks.getCompound(i);
      var p = info.getList("pos", Tag.TAG_INT);
      int lx = p.getInt(0), ly = p.getInt(1), lz = p.getInt(2);
      var position = local(origin, turn, w, d, lx, ly, lz);
      var state =
          NbtUtils.readBlockState(t.blocks(), palette.getCompound(info.getInt("state")))
              .rotate(rotation);
      String nbt =
          info.contains("nbt", Tag.TAG_COMPOUND) ? info.getCompound("nbt").toString() : null;
      writes.put(position, new Cell(position, BlockStateParser.serialize(state), nbt));
      var localPosition = new Point(lx, ly, lz);
      if (state.getBlock() instanceof BranchKioskBlock
          && state.getValue(BranchKioskBlock.WIDE)
          && state.getValue(BranchKioskBlock.CELL) == 0) kiosks.add(localPosition);
      if (state.getBlock() instanceof WideTerminalBlock terminal
          && terminal.kind() == Kind.ATM
          && state.getValue(terminal.cellProperty()) == 0) atms.add(localPosition);
      if (state.getBlock() instanceof DeskBlock && state.getValue(DeskBlock.CELL) == 0)
        desks.add(localPosition);
    }
    if (kiosks.size() != 1 || atms.size() != 2 || desks.size() != 1) return Optional.empty();
    path.get().cells().forEach(cell -> writes.putIfAbsent(cell.position(), cell));
    supports.addAll(path.get().supports());
    UUID branchId = BranchPlans.branchId(BranchPlans.id(world, village.source()), branch.slot());
    var services = new ArrayList<Anchor>();
    services.add(
        new Anchor(
            0,
            bankPoint(transform, kiosks.getFirst()),
            BranchPlans.generation(branchId, 0),
            "wide"));
    atms.sort(
        Comparator.comparingInt(Point::z).thenComparingInt(Point::x).thenComparingInt(Point::y));
    for (int i = 0; i < atms.size(); i++) {
      int slot = i + 1;
      services.add(
          new Anchor(
              slot,
              bankPoint(transform, atms.get(i)),
              BranchPlans.generation(branchId, slot),
              "atm"));
    }
    var desk =
        new Anchor(
            0,
            bankPoint(transform, desks.getFirst()),
            BranchPlans.generation(branchId, Size.STANDARD.deskGenerationSlot(0)),
            "desk");
    var chunks = new TreeSet<Long>();
    for (var p : writes.keySet())
      chunks.add(net.minecraft.world.level.ChunkPos.asLong(p.x() >> 4, p.z() >> 4));
    var manifests = new ArrayList<Manifest>();
    var captured = new ArrayList<Cell>();
    for (var cell : writes.values()) {
      var p = cell.position();
      captured.add(
          new Cell(
              p,
              cell.blockState(),
              cell.blockEntitySnbt(),
              t.additive() ? BlockStateParser.serialize(t.block(p.x(), p.y(), p.z())) : null));
    }
    String digest = afterDigest(captured);
    for (long packed : chunks) {
      var chunk = new net.minecraft.world.level.ChunkPos(packed);
      manifests.add(
          new Manifest(
              chunk.x,
              chunk.z,
              hash(
                  t.additive()
                      ? captured.stream()
                          .filter(
                              c ->
                                  (c.position().x() >> 4) == chunk.x
                                      && (c.position().z() >> 4) == chunk.z)
                          .map(c -> c.position() + "=" + c.expectedBefore())
                          .toList()
                          .toString()
                      : "UNOBSERVED_GENERATION_TERRAIN"),
              digest,
              ManifestState.PLANNED,
              BranchPlans.operation(branchId, 0, "chunk/" + chunk.x + "/" + chunk.z, 1)));
    }
    var choice =
        new BranchPlanV1(
            branch.slot(),
            branch.required(),
            Size.STANDARD,
            branch.institution(),
            branch.nativeCurrency(),
            templateId,
            transform,
            box,
            entrance,
            services,
            List.of(desk),
            manifests,
            State.RESERVED,
            "SITE_RESERVED",
            branch.placementRevision() + 1,
            branch.optionalRoll());
    return Optional.of(new Candidate(choice, captured, supports, "SITE_FOUND"));
  }

  private record Node(int x, int z, int y, int cost, int heuristic, Node previous) {}

  private record Path(List<Cell> cells, List<Point> supports) {}

  private static Optional<Path> path(
      Terrain t,
      Point start,
      List<Bounds> obstacles,
      List<Point> junctions,
      List<Bounds> reserved,
      Bounds bank,
      Source source,
      String style) {
    var targets = new ArrayList<Point>();
    for (var j : junctions) {
      for (var b : obstacles)
        if (j.x() >= b.minX() - 2
            && j.x() <= b.maxX() + 2
            && j.z() >= b.minZ() - 2
            && j.z() <= b.maxZ() + 2) {
          targets.add(
              new Point(b.minX() - 2, j.y(), Math.max(b.minZ(), Math.min(j.z(), b.maxZ()))));
          targets.add(
              new Point(b.maxX() + 2, j.y(), Math.max(b.minZ(), Math.min(j.z(), b.maxZ()))));
          targets.add(
              new Point(Math.max(b.minX(), Math.min(j.x(), b.maxX())), j.y(), b.minZ() - 2));
          targets.add(
              new Point(Math.max(b.minX(), Math.min(j.x(), b.maxX())), j.y(), b.maxZ() + 2));
        }
    }
    targets.removeIf(
        p ->
            Math.abs((p.x() >> 4) - source.startX()) > 8
                || Math.abs((p.z() >> 4) - source.startZ()) > 8);
    if (targets.isEmpty()) return Optional.empty();
    targets.sort(
        Comparator.comparingDouble(p -> Math.abs(p.x() - start.x()) + Math.abs(p.z() - start.z())));
    if (targets.size() > 32) targets.subList(32, targets.size()).clear();
    // Keep the nearest-32 budget fixed: impossible targets are not replaced.
    targets.removeIf(
        p -> {
          if (p.x() == start.x() && p.z() == start.z()) return false;
          int x = p.x(), z = p.z();
          if (!t.available(x, z)
              || Math.abs((x >> 4) - source.startX()) > 8
              || Math.abs((z >> 4) - source.startZ()) > 8) return true;
          var footprint = new Bounds(x - 1, -2048, z - 1, x + 1, 2047, z + 1);
          if (collides(footprint, obstacles, 0)
              || collides(footprint, reserved, 0)
              || collides(footprint, List.of(bank), 0)) return true;
          int surface = t.surface(x, z), y = surface - (t.additive() ? 0 : 1);
          var below = t.block(x, surface - 1, z);
          if (!below.getFluidState().isEmpty()) {
            if (!below.getFluidState().is(FluidTags.WATER)) return true;
            y = surface;
          }
          return Math.abs(p.y() - 1 - y) > 1;
        });
    if (targets.isEmpty()) return Optional.empty();
    Comparator<Node> order =
        Comparator.comparingInt((Node n) -> n.cost + n.heuristic)
            .thenComparingInt(Node::cost)
            .thenComparingInt(Node::x)
            .thenComparingInt(Node::z);
    var open = new PriorityQueue<>(order);
    var best = new HashMap<Long, Integer>();
    open.add(
        new Node(
            start.x(), start.z(), start.y(), 0, heuristic(start.x(), start.z(), targets), null));
    int expanded = 0;
    while (!open.isEmpty() && expanded++ < 4096) {
      var node = open.remove();
      if (node.cost > 256) continue;
      long key = net.minecraft.world.level.ChunkPos.asLong(node.x, node.z);
      if (best.getOrDefault(key, Integer.MAX_VALUE) <= node.cost) continue;
      best.put(key, node.cost);
      if (targets.stream()
          .anyMatch(p -> p.x() == node.x && p.z() == node.z && Math.abs(p.y() - 1 - node.y) <= 1)) {
        var route = new ArrayList<Node>();
        for (var n = node; n != null; n = n.previous) route.add(n);
        Collections.reverse(route);
        return pathCells(t, route, obstacles, reserved, bank, source, style);
      }
      for (var direction :
          new Direction[] {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
        int x = node.x + direction.getStepX(), z = node.z + direction.getStepZ();
        if (best.getOrDefault(net.minecraft.world.level.ChunkPos.asLong(x, z), Integer.MAX_VALUE)
            <= node.cost + 1) continue;
        if (!t.available(x, z)
            || Math.abs((x >> 4) - source.startX()) > 8
            || Math.abs((z >> 4) - source.startZ()) > 8) continue;
        if (collides(new Bounds(x - 1, -2048, z - 1, x + 1, 2047, z + 1), obstacles, 0)
            || collides(new Bounds(x - 1, -2048, z - 1, x + 1, 2047, z + 1), reserved, 0)
            || collides(new Bounds(x - 1, -2048, z - 1, x + 1, 2047, z + 1), List.of(bank), 0))
          continue;
        int surface = t.surface(x, z), y = surface - (t.additive() ? 0 : 1);
        var below = t.block(x, surface - 1, z);
        if (!below.getFluidState().isEmpty()) {
          if (!below.getFluidState().is(FluidTags.WATER)) continue;
          y = surface;
        }
        if (Math.abs(y - node.y) > 1) continue;
        open.add(new Node(x, z, y, node.cost + 1, heuristic(x, z, targets), node));
      }
    }
    return Optional.empty();
  }

  private static Optional<Path> pathCells(
      Terrain t,
      List<Node> route,
      List<Bounds> obstacles,
      List<Bounds> reserved,
      Bounds bank,
      Source source,
      String style) {
    var result = new LinkedHashMap<Point, Cell>();
    var supports = new LinkedHashMap<Point, Point>();
    int waterRun = 0;
    for (int i = 1; i + 1 < route.size(); i++) {
      var before = route.get(i - 1);
      var at = route.get(i);
      var after = route.get(i + 1);
      boolean turn = (at.x - before.x) != (after.x - at.x) || (at.z - before.z) != (after.z - at.z);
      if (turn) {
        if (i < 2 || i + 2 >= route.size()) return Optional.empty();
        for (int j = i - 2; j <= i + 2; j++) if (route.get(j).y != at.y) return Optional.empty();
      }
    }
    for (int index = 0; index < route.size(); index++) {
      var n = route.get(index);
      if (n.y - 1 < t.minY() || n.y + 3 >= t.maxY()) return Optional.empty();
      boolean water = !t.block(n.x, n.y - 1, n.z).getFluidState().isEmpty();
      waterRun = water ? waterRun + 1 : 0;
      if (waterRun > 5) return Optional.empty();
      if (water && (index == 0 || index == route.size() - 1)) return Optional.empty();
      if (water) {
        var prev = route.get(index - 1);
        var next = route.get(index + 1);
        if (prev.y != n.y
            || next.y != n.y
            || (n.x - prev.x) != (next.x - n.x)
            || (n.z - prev.z) != (next.z - n.z)) return Optional.empty();
      }
      boolean horizontal =
          index + 1 < route.size()
              ? route.get(index + 1).x != n.x
              : index > 0 && route.get(index - 1).x != n.x;
      for (int side = water ? -2 : -1; side <= (water ? 2 : 1); side++) {
        int x = n.x + (horizontal ? 0 : side), z = n.z + (horizontal ? side : 0);
        if (!t.available(x, z)
            || Math.abs((x >> 4) - source.startX()) > 8
            || Math.abs((z >> 4) - source.startZ()) > 8) return Optional.empty();
        var point = new Point(x, n.y, z);
        if (bank.contains(point)) continue;
        if (collides(new Bounds(x, -2048, z, x, 2047, z), obstacles, 0)
            || collides(new Bounds(x, -2048, z, x, 2047, z), reserved, 0)) return Optional.empty();
        var current = t.block(x, n.y, z);
        if (water) {
          if (!current.isAir() || !t.block(x, n.y - 1, z).getFluidState().is(FluidTags.WATER))
            return Optional.empty();
          var first = route.get(index - 1);
          if (waterRun == 1)
            for (int lane = -2; lane <= 2; lane++) {
              int ax = first.x + (horizontal ? 0 : lane), az = first.z + (horizontal ? lane : 0);
              if (!t.available(ax, az) || !sturdy(t.block(ax, first.y - 1, az)))
                return Optional.empty();
            }
          if (t.block(route.get(index + 1).x, route.get(index + 1).y - 1, route.get(index + 1).z)
              .getFluidState()
              .isEmpty())
            for (int lane = -2; lane <= 2; lane++) {
              var last = route.get(index + 1);
              int ax = last.x + (horizontal ? 0 : lane), az = last.z + (horizontal ? lane : 0);
              if (!t.available(ax, az) || !sturdy(t.block(ax, last.y - 1, az)))
                return Optional.empty();
            }
        }
        if (t.additive() ? !current.isAir() : !naturalOrAir(current)) return Optional.empty();
        for (int y = n.y + 1; y <= n.y + 3; y++)
          if (!t.block(x, y, z).isAir()) return Optional.empty();
        if (!water) {
          int supportY = n.y - 1;
          if (!t.additive()
              && supportY > t.minY()
              && sturdy(t.block(x, supportY - 1, z))
              && naturalOrAir(t.block(x, supportY, z))) {
            // Reserve one block below the path to bridge shallow gaps in generated terrain.
            var foundation = new Point(x, supportY, z);
            result.put(foundation, new Cell(foundation, "minecraft:stone_bricks", null));
            supportY--;
          }
          if (!sturdy(t.block(x, supportY, z))) return Optional.empty();
          supports.put(point, new Point(x, supportY, z));
        }
        String wood =
            switch (style) {
              case "desert" -> "jungle";
              case "savanna" -> "acacia";
              case "snowy", "taiga" -> "spruce";
              default -> "oak";
            };
        String material = water ? "minecraft:" + wood + "_planks" : "minecraft:stone_bricks";
        if (index + 1 < route.size() && route.get(index + 1).y > n.y) {
          var next = route.get(index + 1);
          String facing =
              next.x > n.x ? "east" : next.x < n.x ? "west" : next.z > n.z ? "south" : "north";
          material =
              "minecraft:stone_brick_stairs[facing="
                  + facing
                  + ",half=bottom,shape=straight,waterlogged=false]";
        }
        result.put(point, new Cell(point, material, null));
        for (int y = n.y + 1; y <= n.y + 3; y++) {
          var clear = new Point(x, y, z);
          result.put(clear, new Cell(clear, "minecraft:air", null));
        }
        if (water && Math.abs(side) == 2) {
          var rail = new Point(x, n.y + 1, z);
          result.put(
              rail,
              new Cell(
                  rail,
                  "minecraft:"
                      + wood
                      + "_fence[east="
                      + horizontal
                      + ",north="
                      + (!horizontal)
                      + ",south="
                      + (!horizontal)
                      + ",waterlogged=false,west="
                      + horizontal
                      + "]",
                  null));
        }
      }
    }
    return Optional.of(
        new Path(new ArrayList<>(result.values()), new ArrayList<>(supports.values())));
  }

  private static int heuristic(int x, int z, List<Point> targets) {
    return targets.stream()
        .mapToInt(p -> Math.abs(x - p.x()) + Math.abs(z - p.z()))
        .min()
        .orElse(0);
  }

  static boolean naturalVegetation(BlockState state) {
    return state.is(BlockTags.LOGS)
        || state.is(BlockTags.LEAVES)
        || state.getBlock() instanceof BushBlock
        || state.getBlock() instanceof VineBlock
        || state.is(Blocks.CACTUS)
        || state.is(Blocks.SUGAR_CANE);
  }

  static boolean naturalOrAir(BlockState state) {
    if (state.isAir()) return true;
    if (!state.getFluidState().isEmpty() || state.hasBlockEntity()) return false;
    return naturalVegetation(state)
        || state.is(BlockTags.TERRACOTTA)
        || Set.of(
                Blocks.GRASS_BLOCK,
                Blocks.DIRT,
                Blocks.COARSE_DIRT,
                Blocks.PODZOL,
                Blocks.ROOTED_DIRT,
                Blocks.STONE,
                Blocks.ANDESITE,
                Blocks.DIORITE,
                Blocks.GRANITE,
                Blocks.DEEPSLATE,
                Blocks.SAND,
                Blocks.RED_SAND,
                Blocks.SANDSTONE,
                Blocks.SNOW_BLOCK,
                Blocks.SNOW,
                Blocks.SHORT_GRASS,
                Blocks.TALL_GRASS,
                Blocks.FERN,
                Blocks.LARGE_FERN,
                Blocks.DEAD_BUSH)
            .contains(state.getBlock());
  }

  private static boolean sturdy(BlockState state) {
    return state.getFluidState().isEmpty()
        && !state.isAir()
        && !state.hasBlockEntity()
        && state.isSolidRender(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
        && !(state.getBlock() instanceof FallingBlock);
  }

  public static Point bankRest(Transform transform) {
    return bankPoint(transform, BANK_REST);
  }

  private static Point bankPoint(Transform transform, Point point) {
    return local(
        transform.origin(),
        transform.quarterTurns(),
        BANK_WIDTH,
        BANK_DEPTH,
        point.x(),
        point.y(),
        point.z());
  }

  public static Point local(Point origin, int turn, int w, int d, int x, int y, int z) {
    int rx =
        switch (turn) {
          case 0 -> x;
          case 1 -> d - 1 - z;
          case 2 -> w - 1 - x;
          default -> z;
        };
    int rz =
        switch (turn) {
          case 0 -> z;
          case 1 -> x;
          case 2 -> d - 1 - z;
          default -> w - 1 - x;
        };
    return new Point(origin.x() + rx, origin.y() + y, origin.z() + rz);
  }

  public static Bounds union(List<Bounds> boxes) {
    return new Bounds(
        boxes.stream().mapToInt(Bounds::minX).min().orElseThrow(),
        boxes.stream().mapToInt(Bounds::minY).min().orElseThrow(),
        boxes.stream().mapToInt(Bounds::minZ).min().orElseThrow(),
        boxes.stream().mapToInt(Bounds::maxX).max().orElseThrow(),
        boxes.stream().mapToInt(Bounds::maxY).max().orElseThrow(),
        boxes.stream().mapToInt(Bounds::maxZ).max().orElseThrow());
  }

  private static double distance(double x, double z, Bounds b) {
    return Math.hypot(
        Math.max(Math.max(b.minX() - x, 0), x - (b.maxX() + 1.0)),
        Math.max(Math.max(b.minZ() - z, 0), z - (b.maxZ() + 1.0)));
  }

  private static boolean collides(Bounds candidate, List<Bounds> boxes, int padding) {
    return boxes.stream()
        .anyMatch(
            b ->
                candidate.minX() <= b.maxX() + padding
                    && candidate.maxX() >= b.minX() - padding
                    && candidate.minZ() <= b.maxZ() + padding
                    && candidate.maxZ() >= b.minZ() - padding);
  }

  private static boolean referenceReach(Bounds b, Source source) {
    return Math.abs((b.minX() >> 4) - source.startX()) <= 8
        && Math.abs((b.maxX() >> 4) - source.startX()) <= 8
        && Math.abs((b.minZ() >> 4) - source.startZ()) <= 8
        && Math.abs((b.maxZ() >> 4) - source.startZ()) <= 8;
  }

  /**
   * The site stream shares the captured selection seed format, but consumes no provider or vanilla
   * RNG state.
   */
  private static int siteFacing(long seed, Source source, int slot) {
    try {
      var seedBytes = new ByteArrayOutputStream();
      try (var out = new DataOutputStream(seedBytes)) {
        string(out, "economy:a7:selection:v1");
        out.writeLong(seed);
        string(out, source.dimension());
        string(out, source.structure());
        out.writeInt(source.startX());
        out.writeInt(source.startZ());
      }
      byte[] selection = MessageDigest.getInstance("SHA-256").digest(seedBytes.toByteArray());
      var streamBytes = new ByteArrayOutputStream();
      try (var out = new DataOutputStream(streamBytes)) {
        string(out, "economy:a7:stream:v1");
        out.write(selection);
        string(out, "site");
        out.writeByte(slot);
        out.writeLong(0);
      }
      long word =
          ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(streamBytes.toByteArray()))
              .getLong();
      // Bound four divides the complete unsigned 64-bit space, so rejection sampling has zero
      // threshold.
      return (int) (word & 3);
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void string(DataOutputStream out, String text) throws IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  public static String afterDigest(List<Cell> cells) {
    return hash(
        cells.stream()
            .map(c -> c.position() + "=" + c.blockState() + "/" + c.blockEntitySnbt())
            .toList()
            .toString());
  }

  private static String hash(String input) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
