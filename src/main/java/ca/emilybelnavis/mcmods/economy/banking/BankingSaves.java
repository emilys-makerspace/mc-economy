package ca.emilybelnavis.mcmods.economy.banking;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.CustodySaveWitness;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

/** Actual persisted semantics, not event notifications or ledger acknowledgment. */
public final class BankingSaves {
  public record Proof(String reference, String location, String token) {}

  private static final int LIMIT = 1_048_576;

  private BankingSaves() {}

  public static Proof player(ServerPlayer player, WorldId world, UUID operation) {
    if (!player.server.isSameThread()) throw new IllegalStateException("Server thread required");
    if (player.server.getPlayerList().getPlayer(player.getUUID()) != player)
      throw new IllegalStateException("PLAYER_SESSION_ENDED");
    var marker = new CompoundTag();
    marker.putUUID("world", world.value());
    marker.putUUID("operation", operation);
    marker.putUUID("save", UUID.randomUUID());
    player.getPersistentData().put("economy_banking_save", marker);
    var expected = player.saveWithoutId(new CompoundTag());
    bounded(expected);
    player.server.getPlayerList().save(player);
    boolean integratedOwner =
        !player.server.isDedicatedServer()
            && player.server.isSingleplayerOwner(player.getGameProfile());
    if (integratedOwner)
      player.server.storageSource.saveDataTag(
          player.server.registryAccess(),
          player.server.getWorldData(),
          player.server.getPlayerList().getSingleplayerData());
    var root = player.server.getWorldPath(LevelResource.ROOT);
    var relative = "playerdata/" + player.getUUID() + ".dat";
    var file = safe(root, relative);
    try {
      if (Files.size(file) > LIMIT) throw new IllegalStateException("PLAYER_SAVE_LIMIT");
      var actual = NbtIo.readCompressed(file, NbtAccounter.create(LIMIT));
      if (!Objects.equals(expected.get("Inventory"), actual.get("Inventory"))
          || !Objects.equals(expected.get("NeoForgeData"), actual.get("NeoForgeData")))
        throw new IllegalStateException("PLAYER_SAVE_NOT_OBSERVED");
      if (integratedOwner) {
        relative = "level.dat";
        file = safe(root, relative);
        if (Files.size(file) > LIMIT) throw new IllegalStateException("WORLD_METADATA_SAVE_LIMIT");
        var host =
            NbtIo.readCompressed(file, NbtAccounter.create(LIMIT))
                .getCompound("Data")
                .getCompound("Player");
        if (!Objects.equals(expected.get("Inventory"), host.get("Inventory"))
            || !Objects.equals(expected.get("NeoForgeData"), host.get("NeoForgeData")))
          throw new IllegalStateException("INTEGRATED_PLAYER_SAVE_NOT_OBSERVED");
      }
      var digest = CustodySaveWitness.digest(Files.readAllBytes(file));
      return new Proof(
          relative,
          "player:" + player.getUUID(),
          "bank-v1:" + world.value() + ":" + operation + ":" + digest);
    } catch (IOException failure) {
      throw new IllegalStateException("PLAYER_SAVE_UNAVAILABLE", failure);
    }
  }

  /**
   * Snapshot one already-loaded source chunk, await its own store future, then read its actual
   * region entry.
   */
  public static CompletionStage<Proof> source(
      ServerPlayer player, BlockPos source, WorldId world, UUID operation) {
    var level = player.serverLevel();
    var chunk = level.getChunkSource().getChunkNow(source.getX() >> 4, source.getZ() >> 4);
    var entity = level.hasChunkAt(source) ? level.getBlockEntity(source) : null;
    if (chunk == null || entity == null)
      return CompletableFuture.failedFuture(new IllegalStateException("SOURCE_UNAVAILABLE"));
    var expected = entity.saveWithFullMetadata(level.registryAccess()).copy();
    var chunkTag = ChunkSerializer.write(level, chunk);
    bounded(chunkTag);
    var position = new ChunkPos(source);
    var folder =
        DimensionType.getStorageFolder(
                level.dimension(), player.server.getWorldPath(LevelResource.ROOT))
            .resolve("region");
    var services = Economy.serverServices(player.server);
    var result = new CompletableFuture<Proof>();
    level
        .getChunkSource()
        .chunkMap
        .write(position, chunkTag)
        .whenComplete(
            (ignored, error) ->
                player.server.execute(
                    () -> {
                      if (error != null || services == null) {
                        result.completeExceptionally(
                            new IllegalStateException("SOURCE_SAVE_UNAVAILABLE", error));
                        return;
                      }
                      var dimension =
                          level.dimension(); // immutable registry key only; worker never accesses
                      // the level.
                      services
                          .storage(
                              new PlayerId(player.getUUID()),
                              () -> {
                                var region =
                                    folder.resolve(
                                        "r."
                                            + position.getRegionX()
                                            + "."
                                            + position.getRegionZ()
                                            + ".mca");
                                if (Files.isSymbolicLink(folder)
                                    || Files.isSymbolicLink(region)
                                    || !Files.isRegularFile(region))
                                  throw new IllegalStateException("SOURCE_SAVE_UNAVAILABLE");
                                try (var file =
                                        new RegionFile(
                                            new RegionStorageInfo(
                                                "economy-banking", dimension, "chunk"),
                                            region,
                                            folder,
                                            false);
                                    var input = file.getChunkDataInputStream(position)) {
                                  if (input == null)
                                    throw new IllegalStateException("SOURCE_SAVE_MISSING");
                                  var actual = NbtIo.read(input, NbtAccounter.create(LIMIT));
                                  var entities = actual.getList("block_entities", Tag.TAG_COMPOUND);
                                  CompoundTag found = null;
                                  for (int index = 0; index < entities.size(); index++) {
                                    var candidate = entities.getCompound(index);
                                    if (candidate.getInt("x") == source.getX()
                                        && candidate.getInt("y") == source.getY()
                                        && candidate.getInt("z") == source.getZ()) {
                                      found = candidate.copy();
                                      found.remove("keepPacked");
                                    }
                                  }
                                  if (!expected.equals(found))
                                    throw new IllegalStateException("SOURCE_SAVE_NOT_OBSERVED");
                                  return new Proof(
                                      region.getFileName() + ":" + position.x + ":" + position.z,
                                      "source:" + source.toShortString(),
                                      "bank-source-v1:"
                                          + world.value()
                                          + ":"
                                          + operation
                                          + ":"
                                          + CustodySaveWitness.digest(bounded(expected)));
                                } catch (IOException failure) {
                                  throw new IllegalStateException(
                                      "SOURCE_SAVE_UNAVAILABLE", failure);
                                }
                              })
                          .whenComplete(
                              (proof, failure) -> {
                                if (failure != null) result.completeExceptionally(failure);
                                else result.complete(proof);
                              });
                    }));
    return result;
  }

  private static Path safe(Path root, String relative) {
    var current = root;
    for (var component : Path.of(relative)) {
      current = current.resolve(component);
      if (Files.isSymbolicLink(current)) throw new IllegalStateException("UNSAFE_SAVE_PATH");
    }
    return current;
  }

  private static byte[] bounded(CompoundTag tag) {
    try {
      var bytes = new ByteArrayOutputStream();
      NbtIo.write(
          tag,
          new DataOutputStream(
              new FilterOutputStream(bytes) {
                int count;

                @Override
                public void write(int value) throws IOException {
                  if (++count > LIMIT) throw new IOException("SAVE_LIMIT");
                  out.write(value);
                }

                @Override
                public void write(byte[] value, int offset, int length) throws IOException {
                  count = Math.addExact(count, length);
                  if (count > LIMIT) throw new IOException("SAVE_LIMIT");
                  out.write(value, offset, length);
                }
              }));
      return bytes.toByteArray();
    } catch (IOException failure) {
      throw new IllegalStateException("SAVE_LIMIT", failure);
    }
  }
}
