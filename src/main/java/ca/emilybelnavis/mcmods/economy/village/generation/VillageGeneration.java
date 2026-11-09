package ca.emilybelnavis.mcmods.economy.village.generation;

import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.*;

/** Registration and Minecraft-owned generation receipts; never touches financial storage. */
public final class VillageGeneration {
  private static final DeferredRegister<StructureType<?>> STRUCTURES =
      DeferredRegister.create(Registries.STRUCTURE_TYPE, "economy");
  private static final DeferredRegister<StructurePieceType> PIECES =
      DeferredRegister.create(Registries.STRUCTURE_PIECE, "economy");
  private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
      DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, "economy");
  public static final DeferredHolder<StructureType<?>, StructureType<BankedVillageStructure>> TYPE =
      STRUCTURES.register("banked_village", () -> () -> BankedVillageStructure.CODEC);
  public static final DeferredHolder<StructurePieceType, StructurePieceType> PIECE =
      PIECES.register("village_bank_piece", () -> VillageGenerationPiece::new);
  public static final DeferredHolder<AttachmentType<?>, AttachmentType<CompoundTag>> RECEIPTS =
      ATTACHMENTS.register(
          "village_generation_receipts",
          () ->
              AttachmentType.builder((java.util.function.Supplier<CompoundTag>) CompoundTag::new)
                  .serialize(CompoundTag.CODEC)
                  .build());
  public static final DeferredHolder<StructureType<?>, StructureType<BankLandmarkStructure>>
      BANK_TYPE = STRUCTURES.register("bank_landmark", () -> () -> BankLandmarkStructure.CODEC);
  public static final DeferredHolder<StructurePieceType, StructurePieceType> BANK_PIECE =
      PIECES.register("bank_landmark", () -> BankLandmarkPiece::new);
  public static final DeferredHolder<
          AttachmentType<?>,
          AttachmentType<
              java.util.Map<
                  String,
                  java.util.List<net.minecraft.world.level.levelgen.structure.StructurePiece>>>>
      LAYOUT_CACHE =
          ATTACHMENTS.register(
              "village_layout_cache",
              () ->
                  AttachmentType.builder(
                          (java.util.function.Supplier<
                                  java.util.Map<
                                      String,
                                      java.util.List<
                                          net.minecraft.world.level.levelgen.structure
                                              .StructurePiece>>>)
                              java.util.HashMap::new)
                      .build());

  public enum Receipt {
    MISSING,
    COMPLETE,
    CONFLICT
  }

  private VillageGeneration() {}

  public static void initialize(IEventBus bus) {
    STRUCTURES.register(bus);
    PIECES.register(bus);
    ATTACHMENTS.register(bus);
  }

  private static String key(UUID branch, ChunkPos chunk) {
    return branch + ":" + chunk.x + "," + chunk.z;
  }

  public static Receipt receipt(ChunkAccess chunk, UUID branch, ChunkPos position) {
    var tag = chunk.getData(RECEIPTS.get());
    String value = tag.getString(key(branch, position));
    if (value.isEmpty())
      return tag.contains(key(branch, position) + ":before") ? Receipt.CONFLICT : Receipt.MISSING;
    try {
      return Receipt.valueOf(value);
    } catch (IllegalArgumentException malformed) {
      return Receipt.CONFLICT;
    }
  }

  public static Receipt receipt(
      ChunkAccess chunk,
      UUID branch,
      ChunkPos position,
      ca.emilybelnavis.mcmods.economy.village.BranchPlans.Manifest manifest) {
    Receipt result = receipt(chunk, branch, position);
    if (result != Receipt.COMPLETE) return result;
    var tag = chunk.getData(RECEIPTS.get());
    String prefix = key(branch, position);
    return tag.getString(prefix + ":operation").equals(manifest.operationId().toString())
            && tag.getString(prefix + ":after").equals(manifest.afterDigest())
            && !tag.getString(prefix + ":before").isEmpty()
        ? Receipt.COMPLETE
        : Receipt.CONFLICT;
  }

  static void conflict(ChunkAccess chunk, UUID branch, ChunkPos position, String reason) {
    receipt(chunk, branch, position, Receipt.CONFLICT);
    var tag = chunk.getData(RECEIPTS.get()).copy();
    tag.putString(key(branch, position) + ":reason", reason);
    chunk.setData(RECEIPTS.get(), tag);
    com.mojang.logging.LogUtils.getLogger()
        .warn(
            "Village bank fragment conflict: branch={} chunk={} reason={}",
            branch,
            position,
            reason);
  }

  static void beforeImage(
      ChunkAccess chunk,
      UUID branch,
      ChunkPos position,
      String before,
      ca.emilybelnavis.mcmods.economy.village.BranchPlans.Manifest manifest) {
    try {
      var tag = chunk.getData(RECEIPTS.get()).copy();
      tag.putString(
          key(branch, position) + ":before",
          java.util.HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(before.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
      tag.putString(key(branch, position) + ":operation", manifest.operationId().toString());
      tag.putString(key(branch, position) + ":after", manifest.afterDigest());
      chunk.setData(RECEIPTS.get(), tag);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  static void receipt(ChunkAccess chunk, UUID branch, ChunkPos position, Receipt state) {
    var tag = chunk.getData(RECEIPTS.get()).copy();
    String key = key(branch, position);
    // Conflicting fragments are terminal evidence; a later callback never overwrites them with
    // success.
    if (!tag.getString(key).equals(Receipt.CONFLICT.name())) tag.putString(key, state.name());
    chunk.setData(RECEIPTS.get(), tag);
  }
}
