package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.terminal.*;
import ca.emilybelnavis.mcmods.economy.village.*;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans.*;
import ca.emilybelnavis.mcmods.economy.village.generation.*;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.storage.LevelResource;

/** Bounded actual registry/serialization/natural generation and explicit loaded retry evidence. */
public final class VillageGenerationProbe {
  private static final List<String> STYLES =
      List.of("plains", "desert", "savanna", "snowy", "taiga");
  private static volatile String failure, result;
  private static volatile Path root;
  private static volatile boolean captureReady, captured;
  private static volatile Point captureCamera;
  private static ServerLevel level;
  private static ServerPlayer player;
  private static int stage, style, templates, naturalAttempt, naturalBanks;
  private static long deadline, waitUntil;
  private static boolean busy;
  private static String last = "";
  private static VillagePlanV1 naturalPlan, syntheticPlan;
  private static UUID retryBranch;
  private static String retryDigest;
  private static BlockPos gold;
  private static final List<String> evidence = new ArrayList<>();

  private VillageGenerationProbe() {}

  public static String failure() {
    return failure;
  }

  public static String result() {
    return result;
  }

  public static Path root() {
    return root;
  }

  public static boolean captureReady() {
    return captureReady;
  }

  public static void captured() {
    captured = true;
  }

  public static String progress() {
    return "stage=" + stage + " style=" + style + " natural=" + naturalAttempt + " last=" + last;
  }

  public static void fail(Throwable error) {
    var trace = new java.io.StringWriter();
    error.printStackTrace(new java.io.PrintWriter(trace));
    failure = trace.toString();
    System.err.println("VILLAGE_GENERATION_FAILED " + failure);
  }

  public static Point captureCamera() {
    return captureCamera;
  }

  public static boolean tick(MinecraftServer server) throws Exception {
    if (failure != null || result != null) return true;
    var services = Economy.serverServices(server);
    if (services == null
        || services.state() != ca.emilybelnavis.mcmods.economy.foundation.ServerServices.State.READY
        || !services.metrics().economy().ready()
        || server.getPlayerList().getPlayers().isEmpty()) return false;
    if (stage == 0) {
      level = server.overworld();
      player = server.getPlayerList().getPlayers().getFirst();
      root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
      require(
          root.getFileName().toString().startsWith("economy-a7-04-"),
          "new disposable fixture name");
      player.setInvulnerable(true);
      stage = 1;
      deadline = server.getTickCount() + 6000;
    }
    require(server.getTickCount() < deadline, "fixture tick deadline " + progress());
    if (server.getTickCount() % 100 == 0)
      System.out.println("VILLAGE_GENERATION_WAIT " + progress() + " busy=" + busy);
    if (busy) return false;
    var publication = GenerationRuntime.publication(level.getChunkSource().getGenerator());
    if (stage == 1) {
      if (publication == null || publication.snapshot() == null || publication.world() == null)
        return false;
      require(
          publication.snapshot().denomination() == EconomyCurrency.CAD, "CAD planning publication");
      for (var bank : InstitutionCatalog.banks()) {
        var id =
            ResourceLocation.fromNamespaceAndPath(
                "economy",
                "banks/" + bank.id().key().substring(bank.id().key().lastIndexOf('/') + 1));
        var template =
            level
                .getStructureManager()
                .get(id)
                .orElseThrow(() -> new IllegalStateException("Missing template " + id));
        require(template.getSize().equals(new Vec3i(17, 15, 17)), "template envelope " + id);
        var tag = template.save(new CompoundTag());
        var palette = tag.getList("palette", Tag.TAG_COMPOUND);
        var blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
        int kiosks = 0, atms = 0, desks = 0;
        for (int i = 0; i < blocks.size(); i++) {
          var state = palette.getCompound(blocks.getCompound(i).getInt("state"));
          if (!state.getCompound("Properties").getString("cell").equals("0")) continue;
          switch (state.getString("Name")) {
            case "economy:branch_kiosk" -> kiosks++;
            case "economy:white_atm" -> atms++;
            case "economy:banker_desk" -> desks++;
          }
        }
        require(
            kiosks == 1 && atms == 2 && desks == 1,
            "actual registered kiosk, ATMs and desk in " + id);
        templates++;
      }
      require(templates == 23, "all 23 banks");
      evidence.add(
          "23 actual templates load with the same envelope and registered kiosks, ATMs and desks.");
      stage = 2;
      return false;
    }
    if (stage == 2) {
      if (style == STYLES.size()) {
        stage = 3;
        return false;
      }
      String s = STYLES.get(style);
      var structure =
          level
              .registryAccess()
              .registryOrThrow(Registries.STRUCTURE)
              .get(ResourceLocation.withDefaultNamespace("village_" + s));
      System.out.println("VILLAGE_STYLE_BEGIN " + s);
      require(structure instanceof BankedVillageStructure, "registered wrapper " + s);
      ChunkPos position = null;
      StructureStart start = null;
      List<VillageGenerationPiece> bankPieces = List.of();
      for (int trial = 0; trial < 3; trial++) {
        var candidatePosition = new ChunkPos(128 + style * 24 + trial * 2, 128 + trial * 3);
        var candidateStart = generate(structure, candidatePosition);
        if (!candidateStart.isValid()) continue;
        var candidatePieces =
            candidateStart.getPieces().stream()
                .filter(p -> p instanceof VillageGenerationPiece)
                .map(p -> (VillageGenerationPiece) p)
                .toList();
        var metadata = candidatePieces.stream().filter(p -> p.branchSlot() == -1).findFirst();
        if (metadata.isPresent()
            && metadata.get().plan().style().equals(s)
            && metadata.get().plan().buildingCount() > 0
            && metadata.get().plan().captureStatus() == CaptureStatus.CAPTURED) {
          position = candidatePosition;
          start = candidateStart;
          bankPieces = candidatePieces;
          break;
        }
      }
      require(
          start != null,
          "supported nonzero captured normal wrapper layout within three candidates: " + s);
      var context = StructurePieceSerializationContext.fromLevel(level);
      var tag = start.createTag(context, position);
      var loaded = StructureStart.loadStaticStart(context, tag, level.getSeed());
      if (loaded != null && !tag.equals(loaded.createTag(context, position))) {
        Files.writeString(root.resolve("roundtrip-before.snbt"), tag.toString());
        Files.writeString(
            root.resolve("roundtrip-after.snbt"), loaded.createTag(context, position).toString());
      }
      require(loaded != null && loaded.isValid(), "valid registered start reload " + s);
      registeredRoundtrip(tag, loaded.createTag(context, position), s);
      for (var piece : bankPieces)
        require(
            BranchPlans.decode(BranchPlans.encode(piece.plan()))
                .digest()
                .equals(piece.plan().digest()),
            "captured plan roundtrip " + s);
      classifyExclusions(s, position);
      evidence.add(
          s
              + ": real registry wrapper generate + exact A7 piece/plan NBT roundtrip, vanilla children unchanged except documented expansion-box maxY normalization; root-only zero count and abandoned root excluded; A7 pieces="
              + bankPieces.size());
      System.out.println("VILLAGE_STYLE_PASS " + s);
      style++;
      return false;
    }
    if (stage == 3) {
      if (naturalBanks > 0) {
        stage = 6;
        return false;
      }
      require(naturalAttempt < 3, "at least one naturally generated branch became active; " + last);
      var target = new BlockPos(6000 + naturalAttempt * 6000, 80, 6000 - naturalAttempt * 3000);
      // This seed's savanna village is the regression case for the shared template's west path.
      var located =
          naturalAttempt == 0
              ? new BlockPos(1097 * 16, 80, -10 * 16)
              : level.findNearestMapStructure(StructureTags.VILLAGE, target, 5, false);
      if (located == null) {
        last = "No eligible natural village within bounded search near " + target;
        evidence.add(last);
        naturalAttempt++;
        return false;
      }
      var chunk =
          level.getChunk(Math.floorDiv(located.getX(), 16), Math.floorDiv(located.getZ(), 16));
      StructureStart start = null;
      for (var candidate : chunk.getAllStarts().values())
        if (candidate.getStructure() instanceof BankedVillageStructure && candidate.isValid()) {
          start = candidate;
          break;
        }
      if (start == null) {
        last = "No wrapper start in located chunk " + located;
        naturalAttempt++;
        return false;
      }
      var metadata =
          start.getPieces().stream()
              .filter(p -> p instanceof VillageGenerationPiece g && g.branchSlot() == -1)
              .map(p -> (VillageGenerationPiece) p)
              .findFirst();
      if (metadata.isEmpty()) {
        last = "Excluded/unsupported natural start at " + located;
        naturalAttempt++;
        return false;
      }
      naturalPlan = metadata.get().plan();
      for (var branch : naturalPlan.branches())
        for (var manifest : branch.manifests()) {
          level.getChunk(manifest.chunkX(), manifest.chunkZ());
          level.setChunkForced(manifest.chunkX(), manifest.chunkZ(), true);
        }
      level.setChunkForced(start.getChunkPos().x, start.getChunkPos().z, true);
      GenerationRuntime.observeLoaded(level, start.getChunkPos());
      last =
          "Natural "
              + naturalPlan.style()
              + " count="
              + naturalPlan.buildingCount()
              + " states="
              + naturalPlan.branches().stream().map(b -> b.state() + "/" + b.reason()).toList();
      System.out.println("VILLAGE_NATURAL_PLAN " + last);
      waitUntil = server.getTickCount() + 400;
      stage = 4;
      return false;
    }
    if (stage == 4) {
      if (server.getTickCount() < waitUntil) return false;
      busy = true;
      var ids =
          naturalPlan.branches().stream()
              .filter(b -> b.state() != State.OMITTED)
              .map(
                  b ->
                      BranchPlans.branchId(
                          BranchPlans.id(services.worldId(), naturalPlan.source()), b.slot()))
              .toList();
      checkNatural(
          ids,
          0,
          () -> {
            busy = false;
            evidence.add(last + " active branches=" + naturalBanks);
            naturalAttempt++;
            stage = 3;
          });
      return false;
    }
    if (stage == 6) {
      var snapshot = publication.snapshot();
      var bank = InstitutionCatalog.banks().getFirst();
      int x = 16000, z = 16000, y = 240;
      for (int cx = 998; cx <= 1004; cx++)
        for (int cz = 998; cz <= 1004; cz++) {
          level.getChunk(cx, cz);
          level.setChunkForced(cx, cz, true);
        }
      var source = new Source("minecraft:overworld", "minecraft:village_plains", 1000, 1000);
      var choice =
          new BranchPlanV1(
              0,
              true,
              Size.COMPACT,
              bank.id().key(),
              bank.baseCurrency(),
              null,
              null,
              null,
              null,
              List.of(),
              List.of(),
              List.of(),
              State.SITE_PENDING,
              "CONSTRAINED_TERRAIN",
              0,
              -1);
      syntheticPlan =
          new VillagePlanV1(
              source,
              "plains",
              5,
              List.of(new Bounds(x + 16, y - 1, z + 16, x + 24, y + 5, z + 24)),
              List.of(new Point(x + 20, y + 2, z + 24)),
              snapshot,
              CaptureStatus.CAPTURED,
              List.of(choice),
              "EXPLICIT_DISPOSABLE_RETRY_SCENARIO");
      retryDigest = syntheticPlan.digest();
      retryBranch = BranchPlans.branchId(BranchPlans.id(services.worldId(), source), 0);
      busy = true;
      var pending = services.branches().observePlan(syntheticPlan);
      pending
          .submit()
          .completion()
          .whenComplete(
              (delivery, error) ->
                  safe(
                      () -> {
                        require(
                            error == null
                                && delivery.outcome().status() == Journal.Status.COMMITTED,
                            "constrained source capture " + error);
                        busy = false;
                        stage = 7;
                      }));
      return false;
    }
    if (stage == 7) {
      busy = true;
      services
          .branches()
          .find(retryBranch)
          .whenComplete(
              (found, error) ->
                  safe(
                      () -> {
                        require(error == null && found.isPresent(), "constrained pending view");
                        GenerationRuntime.retry(
                                server,
                                found.get(),
                                new PlayerId(player.getUUID()),
                                new AtomicBoolean(true))
                            .whenComplete(
                                (reply, failure) ->
                                    safe(
                                        () -> {
                                          require(
                                              failure == null
                                                  && reply.contains("NO_SAFE_LOADED_SITE"),
                                              "unsupported terrain must remain pending: " + reply);
                                          busy = false;
                                          stage = 8;
                                        }));
                      }));
      return false;
    }
    if (stage == 8) {
      // Fixture preparation only: make a new supported AIR-only test pad, without touching any
      // vanilla village.
      for (int x = 15968; x <= 16064; x++)
        for (int z = 15968; z <= 16064; z++)
          level.setBlock(
              new BlockPos(x, 240, z),
              Blocks.STONE.defaultBlockState(),
              Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
      busy = true;
      services
          .branches()
          .find(retryBranch)
          .whenComplete(
              (found, error) ->
                  safe(
                      () -> {
                        require(
                            error == null && found.isPresent(),
                            "pending view after exhausted terrain");
                        var candidate =
                            VillageSites.searchLoaded(
                                level, found.get().plan(), found.get().choice());
                        require(candidate.isPresent(), "fixture supported clear AIR retry site");
                        var target =
                            candidate.get().cells().stream()
                                .filter(c -> !c.blockState().equals("minecraft:air"))
                                .findFirst()
                                .orElseThrow()
                                .position();
                        gold = new BlockPos(target.x(), target.y(), target.z());
                        level.setBlock(
                            gold,
                            Blocks.GOLD_BLOCK.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        GenerationRuntime.retry(
                                server,
                                found.get(),
                                new PlayerId(player.getUUID()),
                                new AtomicBoolean(true))
                            .whenComplete(
                                (reply, failure) ->
                                    safe(
                                        () -> {
                                          require(
                                              failure == null
                                                  && reply.startsWith("BRANCH_PHYSICAL_COMPLETE"),
                                              "fresh loaded safe retry: " + reply);
                                          busy = false;
                                          waitUntil = server.getTickCount() + 300;
                                          stage = 9;
                                        }));
                      }));
      return false;
    }
    if (stage == 9) {
      if (server.getTickCount() < waitUntil) return false;
      busy = true;
      services
          .branches()
          .find(retryBranch)
          .whenComplete(
              (found, error) ->
                  safe(
                      () -> {
                        require(
                            error == null
                                && found.isPresent()
                                && found.get().state().equals("ACTIVE"),
                            "retry kiosk live trusted activation: " + found);
                        var view = found.get();
                        require(
                            view.plan().digest().equals(retryDigest),
                            "retry never rerolls immutable capture");
                        require(
                            level.getBlockState(gold).is(Blocks.GOLD_BLOCK),
                            "later gold construction not overwritten");
                        var anchor = view.choice().services().getFirst();
                        var pos =
                            new BlockPos(
                                anchor.position().x(),
                                anchor.position().y(),
                                anchor.position().z());
                        require(
                            level.getBlockEntity(pos) instanceof TerminalBlockEntity terminal
                                && terminal.policyReady()
                                && terminal.generation().equals(anchor.generation()),
                            "actual wide retry hardware");
                        var facing =
                            Rotation.values()[view.choice().transform().quarterTurns()].rotate(
                                Direction.WEST);
                        var entrance = view.choice().entrance();
                        player.connection.teleport(
                            entrance.x() + .5 + facing.getStepX() * 7,
                            entrance.y(),
                            entrance.z() + .5 + facing.getStepZ() * 7,
                            facing.getOpposite().toYRot(),
                            0);
                        captureCamera =
                            new Point(
                                entrance.x() + facing.getStepX() * 7,
                                entrance.y(),
                                entrance.z() + facing.getStepZ() * 7);
                        player.closeContainer();
                        captureReady = true;
                        busy = false;
                        waitUntil = server.getTickCount() + 40;
                        stage = 10;
                      }));
      return false;
    }
    if (stage == 10) {
      if (!captured && server.getTickCount() < waitUntil + 100) return false;
      busy = true;
      services
          .branches()
          .find(retryBranch)
          .whenComplete(
              (found, error) ->
                  safe(
                      () -> {
                        require(error == null && found.isPresent(), "active view before removal");
                        for (var a : found.get().choice().services())
                          level.destroyBlock(
                              new BlockPos(a.position().x(), a.position().y(), a.position().z()),
                              false);
                        busy = false;
                        waitUntil = server.getTickCount() + 250;
                        stage = 11;
                      }));
      return false;
    }
    if (stage == 11) {
      if (server.getTickCount() < waitUntil) return false;
      busy = true;
      services
          .branches()
          .find(retryBranch)
          .whenComplete(
              (found, error) ->
                  safe(
                      () -> {
                        require(
                            error == null
                                && found.isPresent()
                                && found.get().state().equals("REMOVED"),
                            "removed branch remains tombstoned: " + found);
                        GenerationRuntime.retry(
                                server,
                                found.get(),
                                new PlayerId(player.getUUID()),
                                new AtomicBoolean(true))
                            .whenComplete(
                                (reply, failure) ->
                                    safe(
                                        () -> {
                                          require(
                                              failure == null
                                                  && reply.equals("PLACEMENT_RETRY_STATE"),
                                              "retry cannot rebuild removed branch");
                                          require(
                                              level.getBlockState(gold).is(Blocks.GOLD_BLOCK),
                                              "gold remains after removal");
                                          evidence.add(
                                              "Constrained unsupported terrain stayed pending; fresh additive loaded retry retained captured choice/generation and preserved a gold obstruction; trusted kiosk activated; removal tombstone rejected rebuilding.");
                                          level.save(null, true, false);
                                          result = "PASS " + String.join("\n", evidence);
                                          System.out.println("VILLAGE_GENERATION_PASS " + result);
                                          busy = false;
                                        }));
                      }));
    }
    return result != null || failure != null;
  }

  private static StructureStart generate(Structure structure, ChunkPos pos) {
    var generator = level.getChunkSource().getGenerator();
    return structure.generate(
        level.registryAccess(),
        generator,
        generator.getBiomeSource(),
        level.getChunkSource().randomState(),
        level.getStructureManager(),
        level.getSeed(),
        pos,
        0,
        level,
        biome -> true);
  }

  private static void registeredRoundtrip(CompoundTag before, CompoundTag after, String style) {
    var normalized = before.copy();
    var a = normalized.getList("Children", Tag.TAG_COMPOUND);
    var b = after.getList("Children", Tag.TAG_COMPOUND);
    require(a.size() == b.size(), "registered child count " + style);
    for (int i = 0; i < a.size(); i++) {
      var original = a.getCompound(i);
      var loaded = b.getCompound(i);
      if (original.getString("id").equals("minecraft:jigsaw")) {
        var x = original.getIntArray("BB");
        var y = loaded.getIntArray("BB");
        require(x.length == 6 && y.length == 6, "vanilla boxes " + style);
        for (int n = 0; n < 6; n++)
          require(
              n == 4 ? y[n] <= x[n] : x[n] == y[n],
              "only vanilla expansion maxY normalizes " + style);
        original.putIntArray("BB", y);
      }
      require(original.equals(loaded), "exact child payload " + style + " index=" + i);
    }
    require(
        normalized.equals(after), "exact start payload after vanilla box normalization " + style);
  }

  private static void classifyExclusions(String style, ChunkPos position) throws Exception {
    JsonObject manifest;
    try (var in =
        level
            .getServer()
            .getResourceManager()
            .getResourceOrThrow(
                ResourceLocation.fromNamespaceAndPath("economy", "worldgen/village_inventory.json"))
            .open()) {
      manifest = JsonParser.parseReader(new java.io.InputStreamReader(in)).getAsJsonObject();
    }
    var mapping = manifest.getAsJsonObject("styles").getAsJsonObject(style);
    var generator = level.getChunkSource().getGenerator();
    var context =
        new Structure.GenerationContext(
            level.registryAccess(),
            generator,
            generator.getBiomeSource(),
            level.getChunkSource().randomState(),
            level.getStructureManager(),
            level.getSeed(),
            position,
            level,
            biome -> true);
    for (boolean abandoned : new boolean[] {false, true}) {
      String id =
          mapping
              .getAsJsonArray(abandoned ? "abandonedStarts" : "normalStarts")
              .get(0)
              .getAsString();
      var json = new JsonObject();
      json.addProperty("element_type", "minecraft:single_pool_element");
      json.addProperty("location", id);
      json.addProperty("processors", "minecraft:empty");
      json.addProperty("projection", "rigid");
      var element =
          StructurePoolElement.CODEC
              .parse(level.registryAccess().createSerializationContext(JsonOps.INSTANCE), json)
              .getOrThrow();
      var root = new BlockPos(position.getMinBlockX(), 80, position.getMinBlockZ());
      var piece =
          new PoolElementStructurePiece(
              level.getStructureManager(),
              element,
              root,
              0,
              Rotation.NONE,
              element.getBoundingBox(level.getStructureManager(), root, Rotation.NONE),
              LiquidSettings.APPLY_WATERLOGGING);
      var result = VillageClassifier.classify(style, List.of(piece), context);
      require(
          result.supported() && result.buildings() == 0 && result.abandoned() == abandoned,
          "explicit abandoned/zero root classification " + style);
    }
  }

  private static void checkNatural(List<UUID> ids, int index, Runnable finish) {
    if (index == ids.size()) {
      finish.run();
      return;
    }
    var services = Economy.serverServices(player.server);
    services
        .branches()
        .find(ids.get(index))
        .whenComplete(
            (found, error) ->
                safe(
                    () -> {
                      require(error == null && found.isPresent(), "natural source durable handoff");
                      var view = found.get();
                      if (view.state().equals("ACTIVE")) {
                        naturalBanks++;
                        require(
                            view.choice().services().size() == 3
                                && view.choice().desks().size() == 1,
                            "natural branch hardware capacity");
                        for (var a : view.choice().services()) {
                          var p =
                              new BlockPos(a.position().x(), a.position().y(), a.position().z());
                          require(
                              level.getBlockEntity(p) instanceof TerminalBlockEntity e
                                  && e.policyReady()
                                  && e.kind() == a.serviceKind(),
                              "natural live generated " + a.serviceKind());
                        }
                      }
                      last += " / " + view.state() + ":" + view.reason();
                      checkNatural(ids, index + 1, finish);
                    }));
  }

  @FunctionalInterface
  private interface Checked {
    void run() throws Exception;
  }

  private static void safe(Checked action) {
    try {
      action.run();
    } catch (Throwable error) {
      busy = false;
      fail(error);
    }
  }

  private static void require(boolean value, String label) {
    if (!value) throw new IllegalStateException("Village fixture: " + label);
  }
}
