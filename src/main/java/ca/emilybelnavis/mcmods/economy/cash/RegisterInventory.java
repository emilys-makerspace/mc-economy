package ca.emilybelnavis.mcmods.economy.cash;

import java.io.*;
import java.security.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Exact, bounded stock custody on the server thread. No automatic drops are used. */
public final class RegisterInventory {
  private static final String GENERATION = "economy_register_source_generation";
  private static final String OBSERVED = "economy_register_source_fingerprint";
  private static final String REVISION = "economy_register_source_revision";
  private static final String HELD = "economy_register_reservations";
  private static final String RECEIPTS = "economy_register_deliveries";

  public enum Result {
    DELIVERED,
    UNDELIVERED,
    REPLAY
  }

  public record Snapshot(
      String dimension,
      BlockPos anchor,
      UUID sourceGeneration,
      String fingerprint,
      String template,
      int batch,
      int available,
      long revision) {
    public Snapshot {
      Objects.requireNonNull(dimension);
      anchor = anchor.immutable();
      Objects.requireNonNull(sourceGeneration);
      Objects.requireNonNull(fingerprint);
      Objects.requireNonNull(template);
      if (revision < 1 || batch < 1 || batch > 64 || available < 0 || template.length() > 2048)
        throw new IllegalArgumentException("REGISTER_STOCK_BOUNDS");
    }
  }

  private record Source(BlockEntity entity, Container inventory) {}

  private RegisterInventory() {}

  /** Only a trusted placement/owner configuration path may initialize an existing source. */
  public static UUID initializeSource(MinecraftServer server, String dimension, BlockPos anchor) {
    var source = source(server, dimension, anchor);
    var data = source.entity().getPersistentData();
    if (!data.hasUUID(GENERATION)) {
      data.putUUID(GENERATION, UUID.randomUUID());
      data.putLong(REVISION, 1);
      source.entity().setChanged();
    }
    if (!data.contains(REVISION)) {
      data.putLong(REVISION, 1);
      source.entity().setChanged();
    }
    return data.getUUID(GENERATION);
  }

  public static Snapshot snapshot(
      MinecraftServer server, String dimension, BlockPos anchor, ItemStack template, int batch) {
    var source = source(server, dimension, anchor);
    if (!source.entity().getPersistentData().hasUUID(GENERATION))
      throw new IllegalStateException("REGISTER_SOURCE_UNIDENTIFIED");
    if (template.isEmpty() || batch < 1 || batch > 64)
      throw new IllegalArgumentException("REGISTER_TEMPLATE");
    var normalized = template.copyWithCount(1);
    int available = 0;
    for (int index = 0; index < source.inventory().getContainerSize(); index++) {
      var stack = source.inventory().getItem(index);
      if (ItemStack.isSameItemSameComponents(stack, normalized)) available += stack.getCount();
    }
    var observed = fingerprint(server, source);
    var data = source.entity().getPersistentData();
    if (data.contains(OBSERVED) && !data.getString(OBSERVED).equals(observed))
      data.putLong(REVISION, Math.addExact(data.getLong(REVISION), 1));
    if (!data.getString(OBSERVED).equals(observed)) {
      data.putString(OBSERVED, observed);
      source.entity().setChanged();
    }
    return new Snapshot(
        dimension,
        anchor,
        data.getUUID(GENERATION),
        observed,
        encode(server, normalized),
        batch,
        available,
        data.getLong(REVISION));
  }

  /**
   * After durable intent admission, remove only the exact whole batch that still matches the
   * snapshot.
   */
  public static Snapshot reserve(
      MinecraftServer server, ServerPlayer buyer, Snapshot expected, UUID reservation) {
    thread(server);
    var template = decode(server, expected.template());
    if (!fitsBuyer(buyer, template, expected.batch()))
      throw new IllegalStateException("REGISTER_CAPACITY");
    var source = matchingSource(server, expected);
    var held = source.entity().getPersistentData().getCompound(HELD);
    if (held.contains(reservation.toString()))
      throw new IllegalStateException("REGISTER_ALREADY_RESERVED");
    if (held.size() >= 16) throw new IllegalStateException("REGISTER_RESERVATION_LIMIT");
    var current =
        snapshot(server, expected.dimension(), expected.anchor(), template, expected.batch());
    if (current.revision() != expected.revision()
        || !current.fingerprint().equals(expected.fingerprint()))
      throw new IllegalStateException("REGISTER_STOCK_STALE");
    if (current.available() < expected.batch()) throw new IllegalStateException("REGISTER_STOCK");
    long nextRevision = Math.addExact(current.revision(), 1);
    var entry = new CompoundTag();
    entry.put("original", template.copyWithCount(expected.batch()).save(server.registryAccess()));
    entry.putString("state", "RESERVING");
    entry.putString("template", expected.template());
    entry.putInt("batch", expected.batch());
    held.put(reservation.toString(), entry);
    source.entity().getPersistentData().put(HELD, held);
    int remaining = expected.batch();
    for (int index = 0; index < source.inventory().getContainerSize() && remaining > 0; index++) {
      var stack = source.inventory().getItem(index);
      if (!ItemStack.isSameItemSameComponents(stack, template)) continue;
      int taken = Math.min(remaining, stack.getCount());
      stack.shrink(taken);
      remaining -= taken;
    }
    entry.putString("state", "HELD");
    source.inventory().setChanged();
    source.entity().getPersistentData().putLong(REVISION, nextRevision);
    source.entity().getPersistentData().putString(OBSERVED, fingerprint(server, source));
    source.entity().setChanged();
    return snapshot(server, expected.dimension(), expected.anchor(), template, expected.batch());
  }

  public static boolean reservationMatches(
      MinecraftServer server, Snapshot expected, UUID reservation) {
    try {
      var source = matchingSource(server, expected);
      var entry =
          source.entity().getPersistentData().getCompound(HELD).getCompound(reservation.toString());
      return entry.getString("state").equals("HELD")
          && entry.getString("template").equals(expected.template())
          && entry.getInt("batch") == expected.batch();
    } catch (IllegalStateException unavailable) {
      return false;
    }
  }

  /** Observe the exact restock marker before admitting cancellation completion. */
  public static boolean returnedToSource(
      MinecraftServer server, Snapshot expected, UUID reservation) {
    try {
      var source = matchingSource(server, expected);
      var entry =
          source.entity().getPersistentData().getCompound(HELD).getCompound(reservation.toString());
      return entry.getString("state").equals("RETURNED")
          && entry.getString("template").equals(expected.template())
          && entry.getInt("batch") == expected.batch();
    } catch (IllegalStateException unavailable) {
      return false;
    }
  }

  /**
   * Paid goods deliver as one full batch. Caller must supply the current admitted custody attempt.
   */
  public static Result deliverOnce(
      MinecraftServer server,
      ServerPlayer buyer,
      Snapshot expected,
      UUID reservation,
      UUID attempt) {
    thread(server);
    var receipts = buyer.getPersistentData().getCompound(RECEIPTS);
    if (receipts.contains(reservation.toString())) {
      var receipt = receipts.getCompound(reservation.toString());
      if (!receipt.getUUID("attempt").equals(attempt)
          || !receipt.getString("template").equals(expected.template())
          || receipt.getInt("batch") != expected.batch())
        throw new IllegalStateException("REGISTER_DELIVERY_ATTEMPT");
      return Result.REPLAY;
    }
    var source = matchingSource(server, expected);
    var entry =
        source.entity().getPersistentData().getCompound(HELD).getCompound(reservation.toString());
    if (!entry.getString("state").equals("HELD")
        || !entry.getString("template").equals(expected.template())
        || entry.getInt("batch") != expected.batch())
      throw new IllegalStateException("REGISTER_DELIVERY_UNCERTAIN");
    var original = ItemStack.parseOptional(server.registryAccess(), entry.getCompound("original"));
    if (!ItemStack.isSameItemSameComponents(original, decode(server, expected.template()))
        || original.getCount() != expected.batch())
      throw new IllegalStateException("REGISTER_ORIGINAL_MISMATCH");
    if (!fitsBuyer(buyer, original, expected.batch()) || receipts.size() >= 16)
      return Result.UNDELIVERED;
    entry.putString("state", "DELIVERING");
    source.entity().setChanged();
    int remaining = original.getCount();
    while (remaining > 0) {
      var offered = original.copyWithCount(Math.min(remaining, original.getMaxStackSize()));
      int count = offered.getCount();
      buyer.getInventory().add(offered);
      if (!offered.isEmpty()) throw new IllegalStateException("REGISTER_DELIVERY_UNCERTAIN");
      remaining -= count;
    }
    var receipt = new CompoundTag();
    receipt.putUUID("attempt", attempt);
    receipt.putString("template", expected.template());
    receipt.putInt("batch", expected.batch());
    receipts.put(reservation.toString(), receipt);
    buyer.getPersistentData().put(RECEIPTS, receipts);
    entry.putString("state", "DELIVERED");
    entry.remove("original");
    source.entity().setChanged();
    return Result.DELIVERED;
  }

  /** Only a prepayment cancellation may use this after proving original stock remains held. */
  public static boolean returnToSource(
      MinecraftServer server, Snapshot expected, UUID reservation) {
    thread(server);
    Source source;
    try {
      source = matchingSource(server, expected);
    } catch (IllegalStateException unavailable) {
      return false;
    }
    var entry =
        source.entity().getPersistentData().getCompound(HELD).getCompound(reservation.toString());
    if (entry.getString("state").equals("RETURNED")) return true;
    if (!entry.getString("state").equals("HELD")
        || !entry.getString("template").equals(expected.template())
        || entry.getInt("batch") != expected.batch()) return false;
    var original = ItemStack.parseOptional(server.registryAccess(), entry.getCompound("original"));
    if (original.getCount() != expected.batch()
        || !ItemStack.isSameItemSameComponents(original, decode(server, expected.template()))
        || !capacity(source.inventory(), original, original.getCount())) return false;
    long nextRevision = Math.addExact(source.entity().getPersistentData().getLong(REVISION), 1);
    entry.putString("state", "RETURNING");
    source.entity().setChanged();
    insert(source.inventory(), original.copy());
    source.entity().getPersistentData().putLong(REVISION, nextRevision);
    source.entity().getPersistentData().putString(OBSERVED, fingerprint(server, source));
    entry.putString("state", "RETURNED");
    entry.remove("original");
    source.entity().setChanged();
    return true;
  }

  /** Call only with a current committed cancellation, after the exact restock save was verified. */
  public static void clearRestocked(
      MinecraftServer server,
      Snapshot expected,
      UUID sale,
      ca.emilybelnavis.mcmods.economy.journal.Journal.Outcome outcome) {
    thread(server);
    if (outcome.status() != ca.emilybelnavis.mcmods.economy.journal.Journal.Status.COMMITTED
        || !outcome.result().code().equals("SALE_CANCEL")
        || !outcome.result().payload().equals(sale.toString()))
      throw new IllegalArgumentException("REGISTER_CANCELLATION");
    clearSource(server, expected, sale, "RETURNED");
  }

  /** Current terminal custody plus independently verified source/player saves authorize pruning. */
  public static void clearDelivered(
      MinecraftServer server,
      ServerPlayer recipient,
      Snapshot expected,
      UUID sale,
      ca.emilybelnavis.mcmods.economy.journal.Custody.Case completed) {
    thread(server);
    var terms = completed.terms();
    if (completed.state() != ca.emilybelnavis.mcmods.economy.journal.Custody.State.DELIVERED
        || !terms
            .owner()
            .equals(
                new ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId(recipient.getUUID()))
        || !terms.source().equals("register-stock:" + expected.sourceGeneration() + ":" + sale)
        || !terms
            .items()
            .equals("register-goods-v1|" + expected.batch() + "\n" + expected.template()))
      throw new IllegalArgumentException("REGISTER_DELIVERY_COMPLETION");
    var receipts = recipient.getPersistentData().getCompound(RECEIPTS);
    var receipt = receipts.getCompound(sale.toString());
    if (!receipt.hasUUID("attempt")
        || !receipt.getUUID("attempt").equals(completed.deliveryAttempt())
        || !receipt.getString("template").equals(expected.template())
        || receipt.getInt("batch") != expected.batch())
      throw new IllegalStateException("REGISTER_DELIVERY_ATTEMPT");
    clearSource(server, expected, sale, "DELIVERED");
    receipts.remove(sale.toString());
  }

  private static void clearSource(
      MinecraftServer server, Snapshot expected, UUID sale, String state) {
    var source = matchingSource(server, expected);
    var held = source.entity().getPersistentData().getCompound(HELD);
    var entry = held.getCompound(sale.toString());
    if (!entry.getString("state").equals(state)
        || !entry.getString("template").equals(expected.template())
        || entry.getInt("batch") != expected.batch()
        || entry.contains("original"))
      throw new IllegalStateException("REGISTER_COMPLETION_UNCERTAIN");
    held.remove(sale.toString());
    source.entity().setChanged();
  }

  public static boolean fitsBuyer(ServerPlayer buyer, ItemStack template, int count) {
    thread(buyer.server);
    if (count < 1 || count > 64) return false;
    int room = 0;
    for (int index = 0; index < 36; index++) {
      var existing = buyer.getInventory().getItem(index);
      if (existing.isEmpty()) room += template.getMaxStackSize();
      else if (ItemStack.isSameItemSameComponents(existing, template))
        room += Math.max(0, Math.min(existing.getMaxStackSize(), 64) - existing.getCount());
    }
    return room >= count && buyer.getPersistentData().getCompound(RECEIPTS).size() < 16;
  }

  public static ItemStack decodeTemplate(MinecraftServer server, String encoded) {
    thread(server);
    return decode(server, encoded);
  }

  private static Source matchingSource(MinecraftServer server, Snapshot expected) {
    var source = source(server, expected.dimension(), expected.anchor());
    if (!source.entity().getPersistentData().hasUUID(GENERATION)
        || !source
            .entity()
            .getPersistentData()
            .getUUID(GENERATION)
            .equals(expected.sourceGeneration()))
      throw new IllegalStateException("REGISTER_SOURCE_REPLACED");
    return source;
  }

  private static Source source(MinecraftServer server, String dimension, BlockPos anchor) {
    thread(server);
    var level =
        server.getLevel(
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension)));
    if (level == null) throw new IllegalStateException("REGISTER_DIMENSION");
    var position = anchor.below();
    var chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
    if (chunk == null) throw new IllegalStateException("REGISTER_CHUNK_UNAVAILABLE");
    var entity = chunk.getBlockEntity(position);
    if (entity == null
        || !(entity instanceof Container container)
        || container.getContainerSize() > 54) throw new IllegalStateException("REGISTER_SOURCE");
    return new Source(entity, container);
  }

  private static String fingerprint(MinecraftServer server, Source source) {
    var tag = new CompoundTag();
    var list = new ListTag();
    for (int index = 0; index < source.inventory().getContainerSize(); index++) {
      var item = source.inventory().getItem(index);
      var entry = (CompoundTag) item.saveOptional(server.registryAccess());
      entry.putInt("slot", index);
      list.add(entry);
    }
    tag.put("items", list);
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      var output = new java.security.DigestOutputStream(OutputStream.nullOutputStream(), digest);
      NbtIo.write(tag, new DataOutputStream(new LimitedOutput(output, 65_536)));
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException limit) {
      throw new IllegalArgumentException("REGISTER_SOURCE_LIMIT", limit);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String encode(MinecraftServer server, ItemStack stack) {
    var encoded = encodeTag((CompoundTag) stack.save(server.registryAccess()));
    if (encoded.length() > 2048) throw new IllegalArgumentException("REGISTER_COMPONENT_LIMIT");
    if (decode(server, encoded).isEmpty()) throw new IllegalArgumentException("REGISTER_TEMPLATE");
    return encoded;
  }

  private static String encodeTag(CompoundTag tag) {
    try {
      var output = new ByteArrayOutputStream();
      NbtIo.writeCompressed(tag, output);
      return Base64.getEncoder().encodeToString(output.toByteArray());
    } catch (IOException failure) {
      throw new IllegalStateException("REGISTER_SERIALIZATION", failure);
    }
  }

  private static ItemStack decode(MinecraftServer server, String encoded) {
    if (encoded.length() > 2048) throw new IllegalArgumentException("REGISTER_COMPONENT_LIMIT");
    try {
      return ItemStack.parseOptional(
          server.registryAccess(),
          NbtIo.readCompressed(
              new ByteArrayInputStream(Base64.getDecoder().decode(encoded)),
              NbtAccounter.create(8192)));
    } catch (IOException | RuntimeException failure) {
      throw new IllegalArgumentException("REGISTER_SERIALIZATION", failure);
    }
  }

  private static boolean capacity(Container container, ItemStack template, int count) {
    int room = 0;
    for (int index = 0; index < container.getContainerSize(); index++) {
      var stack = container.getItem(index);
      if (stack.isEmpty())
        room += Math.min(container.getMaxStackSize(), template.getMaxStackSize());
      else if (ItemStack.isSameItemSameComponents(stack, template))
        room +=
            Math.max(
                0,
                Math.min(container.getMaxStackSize(), stack.getMaxStackSize()) - stack.getCount());
    }
    return room >= count;
  }

  private static void insert(Container container, ItemStack original) {
    for (int index = 0; index < container.getContainerSize() && !original.isEmpty(); index++) {
      var stack = container.getItem(index);
      if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, original)) {
        int moved =
            Math.min(
                original.getCount(),
                Math.min(container.getMaxStackSize(), stack.getMaxStackSize()) - stack.getCount());
        if (moved > 0) {
          stack.grow(moved);
          original.shrink(moved);
        }
      }
    }
    for (int index = 0; index < container.getContainerSize() && !original.isEmpty(); index++) {
      if (!container.getItem(index).isEmpty()) continue;
      int moved =
          Math.min(
              original.getCount(),
              Math.min(container.getMaxStackSize(), original.getMaxStackSize()));
      container.setItem(index, original.copyWithCount(moved));
      original.shrink(moved);
    }
    if (!original.isEmpty()) throw new IllegalStateException("REGISTER_RETURN_UNCERTAIN");
    container.setChanged();
  }

  private static final class LimitedOutput extends OutputStream {
    private final OutputStream output;
    private final int limit;
    private int written;

    private LimitedOutput(OutputStream output, int limit) {
      this.output = output;
      this.limit = limit;
    }

    @Override
    public void write(int value) throws IOException {
      if (written == limit) throw new IOException("Register stock byte limit");
      written++;
      output.write(value);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      if (length > limit - written) throw new IOException("Register stock byte limit");
      written += length;
      output.write(bytes, offset, length);
    }
  }

  private static void thread(MinecraftServer server) {
    if (!server.isSameThread()) throw new IllegalStateException("Requires server thread");
  }
}
