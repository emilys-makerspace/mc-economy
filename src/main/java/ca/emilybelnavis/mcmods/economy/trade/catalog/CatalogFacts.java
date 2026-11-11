package ca.emilybelnavis.mcmods.economy.trade.catalog;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.trade.pricing.Pricing;
import java.io.*;
import java.util.*;

/** Immutable detached catalog v1. Native factories are never rerun by recovery. */
public final class CatalogFacts {
  private CatalogFacts() {}

  public static final String HEAD = "economy:a8/trade-catalog", OFFER = "economy:a8/trade-offer";

  public record Head(WorldId world, UUID trader, long revision, int count, String digest) {
    public Head {
      Objects.requireNonNull(world);
      Objects.requireNonNull(trader);
      if (revision < 1
          || count < 1
          || count > 32
          || digest == null
          || !digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Catalog head");
    }
  }

  public record Entry(WorldId world, UUID trader, long revision, TradeCatalog.Offer offer) {
    public Entry {
      Objects.requireNonNull(world);
      Objects.requireNonNull(trader);
      Objects.requireNonNull(offer);
      if (revision < 1) throw new IllegalArgumentException("Catalog revision");
    }
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog
        .with(new RecordCatalog.Key(HEAD, 1), CatalogFacts::decodeHead)
        .with(new RecordCatalog.Key(OFFER, 1), CatalogFacts::decodeEntry);
  }

  public static String digest(TradeCatalog.Capture capture) {
    var text =
        new StringBuilder(
            capture.trader() + "|" + capture.revision() + "|" + StandardQuantities.MANIFEST_SHA256);
    for (var offer : capture.offers()) text.append('|').append(encode(offer));
    return TradeCatalog.digest(text.toString());
  }

  public static String encode(Head value) {
    return write(
        out -> {
          uuid(out, value.world().value());
          uuid(out, value.trader());
          out.writeLong(value.revision());
          out.writeInt(value.count());
          out.writeUTF(value.digest());
          out.writeUTF(StandardQuantities.MANIFEST_SHA256);
        });
  }

  public static String encode(Entry value) {
    return write(
        out -> {
          uuid(out, value.world().value());
          uuid(out, value.trader());
          out.writeLong(value.revision());
          offer(out, value.offer());
        });
  }

  public static String encode(TradeCatalog.Offer value) {
    return write(out -> offer(out, value));
  }

  public static Head decodeHead(String payload) {
    var value =
        read(
            payload,
            in -> {
              var result =
                  new Head(
                      new WorldId(uuid(in)), uuid(in), in.readLong(), in.readInt(), in.readUTF());
              if (!in.readUTF().equals(StandardQuantities.MANIFEST_SHA256))
                throw new IllegalArgumentException("Catalog manifest identity");
              return result;
            });
    canonical(payload, encode(value));
    return value;
  }

  public static Entry decodeEntry(String payload) {
    var value =
        read(payload, in -> new Entry(new WorldId(uuid(in)), uuid(in), in.readLong(), offer(in)));
    canonical(payload, encode(value));
    return value;
  }

  private static void offer(DataOutputStream out, TradeCatalog.Offer value) throws IOException {
    out.writeInt(value.ordinal());
    out.writeUTF(value.side().name());
    goods(out, value.goods());
    out.writeInt(value.goodsCount());
    out.writeInt(value.emeraldCount());
    out.writeInt(value.secondary().size());
    for (var ingredient : value.secondary()) {
      goods(out, ingredient.goods());
      out.writeInt(ingredient.count());
    }
    out.writeInt(value.originalMaxUses());
    out.writeInt(value.villagerXp());
  }

  private static TradeCatalog.Offer offer(DataInputStream in) throws IOException {
    int ordinal = in.readInt();
    var side = Pricing.Side.valueOf(in.readUTF());
    var goods = goods(in);
    int count = in.readInt(), emeralds = in.readInt();
    int n = in.readInt();
    if (n < 0 || n > 1) throw new IllegalArgumentException("Catalog secondary count");
    var secondary = new ArrayList<TradeCatalog.Ingredient>();
    for (int i = 0; i < n; i++) secondary.add(new TradeCatalog.Ingredient(goods(in), in.readInt()));
    return new TradeCatalog.Offer(
        ordinal, side, goods, count, emeralds, secondary, in.readInt(), in.readInt());
  }

  private static void goods(DataOutputStream out, TradeCatalog.Goods value) throws IOException {
    out.writeUTF(value.item());
    out.writeUTF(value.match().name());
    out.writeUTF(value.components());
  }

  private static TradeCatalog.Goods goods(DataInputStream in) throws IOException {
    return new TradeCatalog.Goods(
        in.readUTF(), TradeCatalog.Match.valueOf(in.readUTF()), in.readUTF());
  }

  @FunctionalInterface
  private interface Writer {
    void write(DataOutputStream out) throws IOException;
  }

  @FunctionalInterface
  private interface Reader<T> {
    T read(DataInputStream in) throws IOException;
  }

  private static String write(Writer writer) {
    try {
      var bytes = new ByteArrayOutputStream();
      try (var out = new DataOutputStream(bytes)) {
        out.writeInt(1);
        writer.write(out);
      }
      String result = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
      if (result.length() > 16384) throw new IllegalArgumentException("Catalog fact bound");
      return result;
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static <T> T read(String payload, Reader<T> reader) {
    if (payload == null || payload.length() > 16384)
      throw new IllegalArgumentException("Catalog fact bound");
    try (var in =
        new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(payload)))) {
      if (in.readInt() != 1) throw new IllegalArgumentException("Catalog version");
      T value = reader.read(in);
      if (in.available() != 0) throw new IllegalArgumentException("Trailing catalog bytes");
      return value;
    } catch (IOException malformed) {
      throw new IllegalArgumentException("Malformed catalog", malformed);
    }
  }

  private static void canonical(String original, String encoded) {
    if (!original.equals(encoded)) throw new IllegalArgumentException("Noncanonical catalog fact");
  }

  private static void uuid(DataOutputStream out, UUID value) throws IOException {
    out.writeLong(value.getMostSignificantBits());
    out.writeLong(value.getLeastSignificantBits());
  }

  private static UUID uuid(DataInputStream in) throws IOException {
    return new UUID(in.readLong(), in.readLong());
  }
}
