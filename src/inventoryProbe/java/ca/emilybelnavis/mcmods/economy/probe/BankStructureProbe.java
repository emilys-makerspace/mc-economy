package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.village.BankPoints;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans;
import ca.emilybelnavis.mcmods.economy.village.GenerationRuntime;
import ca.emilybelnavis.mcmods.economy.village.generation.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** Native vanilla locate dispatcher evidence in a guarded disposable normal world. */
public final class BankStructureProbe {
  public static volatile String result, failure;
  private static long deadline;
  private static final BlockPos ORIGIN = new BlockPos(17560, 90, -152);

  private BankStructureProbe() {}

  public static boolean tick(MinecraftServer server) throws Exception {
    if (result != null || failure != null) return true;
    if (server.getPlayerList().getPlayers().isEmpty()) return false;
    var level = server.overworld();
    if (deadline == 0) {
      var root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
      require(
          root.getFileName().toString().matches("economy-bank-structure-[0-9a-f-]{36}"),
          "unique normalized disposable world");
      require(level.getSeed() == 704L, "fixed normal world seed");
      deadline = server.getTickCount() + 5000;
    }
    require(server.getTickCount() < deadline, "bounded publication wait");
    var publication = GenerationRuntime.publication(level.getChunkSource().getGenerator());
    if (publication == null || publication.snapshot() == null) return false;
    var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
    var id = ResourceLocation.fromNamespaceAndPath("economy", "bank");
    var bank = registry.get(id);
    require(bank instanceof BankLandmarkStructure, "genuine registered native bank structure");
    var tag =
        registry
            .getTag(
                TagKey.create(
                    Registries.STRUCTURE,
                    ResourceLocation.fromNamespaceAndPath("economy", "banks")))
            .orElseThrow();
    require(
        tag.size() == 1 && tag.stream().allMatch(h -> h.value() == bank),
        "native banks tag contains only economy:bank");
    require(
        level.getChunkSource().getChunkNow(1097, -10) == null,
        "remote native start initially not FULL");
    require(
        server.getPlayerList().getPlayers().stream()
            .allMatch(p -> p.position().distanceToSqr(Vec3.atCenterOf(ORIGIN)) > 10000D * 10000D),
        "players remain at distant spawn");
    require(
        level
            .getPoiManager()
            .findClosest(h -> h.is(BankPoints.BANK.getKey()), ORIGIN, 256, PoiManager.Occupancy.ANY)
            .isEmpty(),
        "unexplored remote bank has no native POI");
    var source =
        server.createCommandSourceStack().withLevel(level).withPosition(Vec3.atCenterOf(ORIGIN));
    int direct =
        server.getCommands().getDispatcher().execute("locate structure economy:bank", source);
    int tagged =
        server.getCommands().getDispatcher().execute("locate structure #economy:banks", source);
    require(
        direct >= 0 && direct < 32 && tagged >= 0 && tagged < 32,
        "actual vanilla locate commands find known nearby source: " + direct + "/" + tagged);
    var chunk = level.getChunk(1097, -10, ChunkStatus.STRUCTURE_STARTS);
    require(chunk != null, "native command generated cached STRUCTURE_STARTS");
    var marker = chunk.getStartForStructure(bank);
    require(
        marker != null && marker.isValid() && !marker.getPieces().isEmpty(),
        "valid native bank marker start");
    require(
        marker.getPieces().stream().allMatch(p -> p instanceof BankLandmarkPiece),
        "marker contains passive landmark pieces only");
    BranchPlans.VillagePlanV1 plan = null;
    for (var start : chunk.getAllStarts().values())
      if (start.isValid() && start.getStructure() instanceof BankedVillageStructure)
        for (var piece : start.getPieces())
          if (piece instanceof VillageGenerationPiece metadata && metadata.branchSlot() == -1)
            plan = metadata.plan();
    require(
        plan != null && plan.captureStatus() == BranchPlans.CaptureStatus.CAPTURED,
        "original natural village captured once");
    for (var piece : marker.getPieces()) {
      var passive = (BankLandmarkPiece) piece;
      var choice =
          plan.branches().stream()
              .filter(c -> c.slot() == passive.slot())
              .findFirst()
              .orElseThrow();
      require(
          choice.state() == BranchPlans.State.RESERVED && choice.bounds() != null,
          "marker references actual reserved bank choice");
      var a = choice.bounds();
      var b = passive.getBoundingBox();
      require(
          a.minX() == b.minX()
              && a.minY() == b.minY()
              && a.minZ() == b.minZ()
              && a.maxX() == b.maxX()
              && a.maxY() == b.maxY()
              && a.maxZ() == b.maxZ(),
          "passive marker exactly matches captured actual bank bounds");
      require(
          level.getChunkSource().getChunkNow(b.getCenter().getX() >> 4, b.getCenter().getZ() >> 4)
              == null,
          "bank chunk remains below FULL");
    }
    var context = StructurePieceSerializationContext.fromLevel(level);
    var position = new ChunkPos(1097, -10);
    var serialized = marker.createTag(context, position);
    var loaded = StructureStart.loadStaticStart(context, serialized, level.getSeed());
    require(
        loaded != null
            && loaded.isValid()
            && serialized.equals(loaded.createTag(context, position)),
        "exact registered native marker NBT roundtrip");
    require(
        level.getChunkSource().getChunkNow(1097, -10) == null,
        "native search never promoted source to FULL");
    result =
        "PASS actual vanilla /locate structure economy:bank and #economy:banks; native tag contains only bank; distances="
            + direct
            + "/"
            + tagged
            + "; known natural captured village matches passive bank bounds; exact registered NBT roundtrip; remote native POI absent; players stay at spawn; source and bank remain below FULL. No financial operations.";
    System.out.println("BANK_STRUCTURE_PASS " + result);
    return true;
  }

  private static void require(boolean value, String message) {
    if (!value) throw new IllegalStateException(message);
  }
}
