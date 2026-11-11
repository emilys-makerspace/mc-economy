package ca.emilybelnavis.mcmods.economy.simulation.window;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityFacts;
import ca.emilybelnavis.mcmods.economy.simulation.model.MacroModel;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Version-one immutable worker facts. A close contains at most fourteen detached day rows. */
public final class WindowFacts {
  private WindowFacts() {}

  public static final String OPEN = "economy:a8/window-open", CLOSE = "economy:a8/window-close";

  public record Open(
      UUID id,
      WorldId world,
      long originMillis,
      long boundaryMillis,
      long firstDay,
      long frontier,
      UUID config,
      UUID anchor,
      String sources,
      List<EconomyCurrency> currencies) {
    public Open {
      Objects.requireNonNull(world);
      Objects.requireNonNull(config);
      Objects.requireNonNull(anchor);
      currencies = List.copyOf(currencies);
      if (originMillis < 0
          || boundaryMillis <= originMillis
          || firstDay < 0
          || frontier < 1
          || (boundaryMillis - originMillis) % ActivityFacts.DAY_MILLIS != 0
          || firstDay > (boundaryMillis - originMillis) / ActivityFacts.DAY_MILLIS - 1
          || !ActivityFacts.SOURCE_MANIFEST.equals(sources)
          || !currencies.equals(EconomyCurrency.all())
          || !stable("open|" + world.value() + "|" + boundaryMillis).equals(id))
        throw new IllegalArgumentException("Window open identity");
    }

    public long day() {
      return (boundaryMillis - originMillis) / ActivityFacts.DAY_MILLIS - 1;
    }
  }

  public record Amount(long count, long minor) {
    public Amount {
      if (count <= 0 || minor <= 0) throw new IllegalArgumentException("Window counter");
    }
  }

  public record Day(long index, Map<ActivityFacts.Kind, Amount> amounts, long capacityMinor) {
    public Day {
      if (index < 0 || capacityMinor < 0) throw new IllegalArgumentException("Window day");
      amounts = Collections.unmodifiableMap(new EnumMap<>(copyAmounts(amounts)));
    }
  }

  public record Snapshot(Open open, EconomyCurrency currency, List<Day> days) {
    public Snapshot {
      Objects.requireNonNull(open);
      Objects.requireNonNull(currency);
      days = List.copyOf(days);
      int expected =
          (int) Math.min(14, Math.addExact(Math.subtractExact(open.day(), open.firstDay()), 1));
      if (!open.currencies().contains(currency) || days.size() != expected)
        throw new IllegalArgumentException("Missing covered days");
      long start = open.day() - expected + 1;
      for (int i = 0; i < days.size(); i++)
        if (days.get(i).index() != start + i)
          throw new IllegalArgumentException("Noncontiguous covered days");
    }

    public UUID id() {
      return stable("close|" + open.id() + "|" + currency);
    }

    public String hash() {
      return digest(encode(this));
    }

    public MacroModel.Window modelWindow() {
      int n = Math.min(7, days.size()), b = days.size() - n;
      var w = new EnumMap<ActivityFacts.Kind, BigInteger>(ActivityFacts.Kind.class);
      var baseline = new EnumMap<ActivityFacts.Kind, BigInteger>(ActivityFacts.Kind.class);
      BigInteger capacity = BigInteger.ZERO;
      for (int i = 0; i < days.size(); i++) {
        var target = i < b ? baseline : w;
        for (var e : days.get(i).amounts().entrySet())
          target.merge(e.getKey(), BigInteger.valueOf(e.getValue().minor()), BigInteger::add);
        if (i >= b) capacity = capacity.add(BigInteger.valueOf(days.get(i).capacityMinor()));
      }
      return new MacroModel.Window(n, b, true, w, baseline, capacity);
    }
  }

  private static EnumMap<ActivityFacts.Kind, Amount> copyAmounts(
      Map<ActivityFacts.Kind, Amount> values) {
    var result = new EnumMap<ActivityFacts.Kind, Amount>(ActivityFacts.Kind.class);
    result.putAll(values);
    return result;
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog
        .with(new RecordCatalog.Key(OPEN, 1), WindowFacts::decodeOpen)
        .with(new RecordCatalog.Key(CLOSE, 1), WindowFacts::decodeSnapshot)
        .with(new RecordCatalog.Key(OPEN, 2), WindowFacts::decodeOpen)
        .with(new RecordCatalog.Key(CLOSE, 2), WindowFacts::decodeSnapshot);
  }

  public static UUID stable(String value) {
    return UUID.nameUUIDFromBytes(
        ("economy:a8:window:v1|" + value).getBytes(StandardCharsets.UTF_8));
  }

  public static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  public static String encode(Open value) {
    return write(
        out -> {
          out.writeInt(1);
          open(out, value);
        });
  }

  public static String encode(Snapshot value) {
    return write(
        out -> {
          out.writeInt(1);
          open(out, value.open());
          out.writeUTF(value.currency().name());
          out.writeInt(value.days().size());
          for (var day : value.days()) {
            out.writeLong(day.index());
            out.writeLong(day.capacityMinor());
            out.writeInt(day.amounts().size());
            for (var kind : ActivityFacts.Kind.values())
              if (day.amounts().containsKey(kind)) {
                var amount = day.amounts().get(kind);
                out.writeUTF(kind.name());
                out.writeLong(amount.count());
                out.writeLong(amount.minor());
              }
          }
        });
  }

  public static Open decodeOpen(String payload) {
    var value =
        read(
            payload,
            in -> {
              version(in);
              return open(in);
            });
    if (!encode(value).equals(payload))
      throw new IllegalArgumentException("Noncanonical window open");
    return value;
  }

  public static Snapshot decodeSnapshot(String payload) {
    var value =
        read(
            payload,
            in -> {
              version(in);
              var open = open(in);
              var currency = EconomyCurrency.valueOf(in.readUTF());
              int count = count(in, 14);
              var days = new ArrayList<Day>();
              for (int i = 0; i < count; i++) {
                long day = in.readLong(), capacity = in.readLong();
                int size = count(in, ActivityFacts.Kind.values().length);
                var amounts = new EnumMap<ActivityFacts.Kind, Amount>(ActivityFacts.Kind.class);
                for (int k = 0; k < size; k++)
                  if (amounts.put(
                          ActivityFacts.Kind.valueOf(in.readUTF()),
                          new Amount(in.readLong(), in.readLong()))
                      != null) throw new IllegalArgumentException("Duplicate window kind");
                days.add(new Day(day, amounts, capacity));
              }
              return new Snapshot(open, currency, days);
            });
    if (!encode(value).equals(payload))
      throw new IllegalArgumentException("Noncanonical window snapshot");
    return value;
  }

  private static void open(DataOutputStream out, Open o) throws IOException {
    uuid(out, o.id());
    uuid(out, o.world().value());
    out.writeLong(o.originMillis());
    out.writeLong(o.boundaryMillis());
    out.writeLong(o.firstDay());
    out.writeLong(o.frontier());
    uuid(out, o.config());
    uuid(out, o.anchor());
    out.writeUTF(o.sources());
    out.writeInt(o.currencies().size());
    for (var currency : o.currencies()) out.writeUTF(currency.name());
  }

  private static Open open(DataInputStream in) throws IOException {
    UUID id = uuid(in);
    var world = new WorldId(uuid(in));
    long origin = in.readLong(),
        boundary = in.readLong(),
        first = in.readLong(),
        frontier = in.readLong();
    UUID config = uuid(in), anchor = uuid(in);
    String sources = in.readUTF();
    int count = count(in, 11);
    var currencies = new ArrayList<EconomyCurrency>();
    for (int i = 0; i < count; i++) currencies.add(EconomyCurrency.valueOf(in.readUTF()));
    return new Open(
        id, world, origin, boundary, first, frontier, config, anchor, sources, currencies);
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
        writer.write(out);
      }
      String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
      if (payload.length() > 16384) throw new IllegalArgumentException("Window fact size");
      return payload;
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static <T> T read(String payload, Reader<T> reader) {
    if (payload == null || payload.length() > 16384)
      throw new IllegalArgumentException("Window fact size");
    try (var in =
        new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(payload)))) {
      T value = reader.read(in);
      if (in.available() != 0) throw new IllegalArgumentException("Trailing window bytes");
      return value;
    } catch (IOException malformed) {
      throw new IllegalArgumentException("Malformed window fact", malformed);
    }
  }

  private static void version(DataInputStream in) throws IOException {
    if (in.readInt() != 1) throw new IllegalArgumentException("Window version");
  }

  private static int count(DataInputStream in, int max) throws IOException {
    int n = in.readInt();
    if (n < 0 || n > max) throw new IllegalArgumentException("Window count");
    return n;
  }

  private static void uuid(DataOutputStream out, UUID id) throws IOException {
    out.writeLong(id.getMostSignificantBits());
    out.writeLong(id.getLeastSignificantBits());
  }

  private static UUID uuid(DataInputStream in) throws IOException {
    return new UUID(in.readLong(), in.readLong());
  }
}
