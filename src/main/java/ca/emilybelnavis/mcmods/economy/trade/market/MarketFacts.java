package ca.emilybelnavis.mcmods.economy.trade.market;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.trade.pricing.Pricing;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Detached identity/spatial/price state; no entity, inventory or monetary authority. */
public final class MarketFacts {
  private MarketFacts() {}

  public static final String TYPE = "economy:a8/market-state";
  private static final Gson JSON =
      new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

  public enum Kind {
    VILLAGE,
    DYNAMIC,
    WANDERING
  }

  public record Position(String dimension, int x, int y, int z) {
    public Position {
      if (dimension == null
          || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
          || Math.abs((long) x) > 30_000_000
          || Math.abs((long) z) > 30_000_000
          || y < -2048
          || y > 2047) throw new IllegalArgumentException("Market position");
    }

    public long distance(Position other) {
      if (!dimension.equals(other.dimension)) return Long.MAX_VALUE;
      long dx = (long) x - other.x, dy = (long) y - other.y, dz = (long) z - other.z;
      return dx * dx + dy * dy + dz * dz;
    }
  }

  public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public Box {
      if (minX > maxX || minY > maxY || minZ > maxZ)
        throw new IllegalArgumentException("Market bounds");
    }

    public boolean contains(Position p) {
      return p.x >= minX && p.x <= maxX && p.y >= minY && p.y <= maxY && p.z >= minZ && p.z <= maxZ;
    }
  }

  public record Market(
      UUID id,
      WorldId world,
      Kind kind,
      EconomyCurrency currency,
      Position anchor,
      Box bounds,
      String source,
      List<EconomyCurrency> manifest) {
    public Market {
      Objects.requireNonNull(id);
      Objects.requireNonNull(world);
      Objects.requireNonNull(kind);
      Objects.requireNonNull(currency);
      Objects.requireNonNull(anchor);
      Objects.requireNonNull(bounds);
      if (source == null || source.length() > 256)
        throw new IllegalArgumentException("Market source");
      manifest = List.copyOf(manifest);
      if (kind == Kind.WANDERING) {
        if (manifest.isEmpty()
            || manifest.size() > 11
            || !manifest.equals(
                manifest.stream().distinct().sorted(Comparator.comparing(Enum::name)).toList())
            || !manifest.contains(currency))
          throw new IllegalArgumentException("Wandering manifest");
      } else if (!manifest.isEmpty()) throw new IllegalArgumentException("Ordinary manifest");
    }
  }

  public record Binding(UUID trader, UUID market, Position workstation, long revision) {
    public Binding {
      Objects.requireNonNull(trader);
      Objects.requireNonNull(market);
      if (revision < 1) throw new IllegalArgumentException("Binding revision");
    }
  }

  public record Index(UUID market, long start, long next, long total, boolean ready) {
    public Index {
      Objects.requireNonNull(market);
      if (start < 0
          || next < start
          || next - start > 16
          || next > total
          || total < 1
          || ready != (next == total)) throw new IllegalArgumentException("Market index page");
    }
  }

  public record Search(
      UUID trader,
      Position target,
      boolean assigned,
      long spatialRevision,
      EconomyCurrency fallback,
      int phase,
      String cursor,
      UUID best,
      long distance,
      long revision) {
    public Search {
      Objects.requireNonNull(trader);
      Objects.requireNonNull(target);
      Objects.requireNonNull(fallback);
      if (spatialRevision < 0
          || phase < 0
          || phase > 1
          || cursor == null
          || (!cursor.isEmpty() && !UUID.fromString(cursor).toString().equals(cursor))
          || distance < 0
          || revision < 1
          || (best == null && distance != Long.MAX_VALUE))
        throw new IllegalArgumentException("Membership cursor");
    }
  }

  public record Price(
      UUID market, String item, long revision, Pricing.Demand demand, long eligibleTick) {
    public Price {
      Objects.requireNonNull(market);
      Objects.requireNonNull(demand);
      key(item);
      if (revision < 1 || eligibleTick < demand.anchoredEligibleTick())
        throw new IllegalArgumentException("Price revision/tick");
    }
  }

  public record Carry(Pricing.Key key, Rational residual) {
    public Carry {
      new Pricing.Carry(key, residual);
    }

    public Pricing.Carry pricing() {
      return new Pricing.Carry(key, residual);
    }
  }

  /** At most one row of each changing identity, plus an index page <=16 cells. */
  public record Event(
      WorldId world,
      UUID operation,
      Market market,
      Binding binding,
      Index index,
      Search search,
      Price price,
      Carry carry) {
    public Event {
      Objects.requireNonNull(world);
      Objects.requireNonNull(operation);
      if (market == null
          && binding == null
          && index == null
          && search == null
          && price == null
          && carry == null) throw new IllegalArgumentException("Empty market fact");
      if (market != null && !market.world.equals(world))
        throw new IllegalArgumentException("Market world");
    }
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog.with(new RecordCatalog.Key(TYPE, 1), MarketFacts::decode);
  }

  public static String encode(Event event) {
    String value = JSON.toJson(event);
    if (value.getBytes(StandardCharsets.UTF_8).length > 16384)
      throw new IllegalArgumentException("Market fact size");
    return value;
  }

  public static Event decode(String payload) {
    if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > 16384)
      throw new IllegalArgumentException("Market fact size");
    try {
      Event event = JSON.fromJson(payload, Event.class);
      if (event == null || !encode(event).equals(payload))
        throw new IllegalArgumentException("Noncanonical market fact");
      return event;
    } catch (JsonParseException bad) {
      throw new IllegalArgumentException("Malformed market fact", bad);
    }
  }

  public static UUID stable(String value) {
    return UUID.nameUUIDFromBytes(
        ("economy:a8:market:v1|" + value).getBytes(StandardCharsets.UTF_8));
  }

  public static void key(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Market item key");
  }
}
