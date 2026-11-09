package ca.emilybelnavis.mcmods.economy.trade.stock;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.trade.pricing.Pricing;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Frozen detached stock/tick/progression facts. No clock gaps, client claims or entity mutations.
 */
public final class StockFacts {
  private StockFacts() {}

  public static final String TYPE = "economy:a8/trade-stock";
  public static final int BUFFER_TICKS = 200, RESTOCK_TICKS = 10_000;
  private static final Gson JSON =
      new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

  public enum Action {
    INSTALL,
    TRADER_TICKS,
    MARKET_TICKS,
    TRADE,
    PROGRESSION
  }

  public enum Stage {
    PENDING,
    DISPATCHED,
    ACKNOWLEDGED,
    AMBIGUOUS
  }

  public record Eligibility(
      boolean loaded, boolean work, boolean validWorkstation, boolean withinTwo) {
    public boolean eligible() {
      return loaded && work && validWorkstation && withinTwo;
    }
  }

  public record TickSample(
      UUID session, long observedServerTick, int observedTicks, int eligibleTicks) {
    public TickSample {
      Objects.requireNonNull(session);
      if (observedServerTick < 0
          || observedTicks < 1
          || observedTicks > BUFFER_TICKS
          || eligibleTicks < 0
          || eligibleTicks > observedTicks
          || observedTicks - 1L > observedServerTick)
        throw new IllegalArgumentException("Observed running tick sample");
    }
  }

  public record OfferStock(
      int ordinal, String identity, Pricing.Side side, int maxUses, int remaining, int villagerXp) {
    public OfferStock {
      Objects.requireNonNull(side);
      if (ordinal < 0
          || ordinal >= 32
          || identity == null
          || !identity.matches("[0-9a-f]{64}")
          || maxUses < 1
          || maxUses > 1024
          || remaining < 0
          || remaining > maxUses
          || villagerXp < 0
          || villagerXp > 1024) throw new IllegalArgumentException("Captured sale stock");
    }
  }

  public record Trader(
      UUID trader,
      long catalogRevision,
      long revision,
      boolean wandering,
      int restockTicks,
      UUID session,
      long lastObservedServerTick,
      List<OfferStock> offers) {
    public Trader {
      Objects.requireNonNull(trader);
      offers = List.copyOf(offers);
      if (catalogRevision < 1
          || revision < 1
          || restockTicks < 0
          || restockTicks >= RESTOCK_TICKS
          || lastObservedServerTick < 0
          || offers.isEmpty()
          || offers.size() > 32
          || wandering && restockTicks != 0)
        throw new IllegalArgumentException("Trader stock state");
      for (int i = 0; i < offers.size(); i++)
        if (offers.get(i).ordinal() != i) throw new IllegalArgumentException("Stock ordinal");
    }
  }

  public record MarketTicks(
      UUID market, long revision, long eligibleTicks, UUID session, long lastObservedServerTick) {
    public MarketTicks {
      Objects.requireNonNull(market);
      Objects.requireNonNull(session);
      if (revision < 1 || eligibleTicks < 0 || lastObservedServerTick < 0)
        throw new IllegalArgumentException("Market running tick state");
    }
  }

  public record Progression(
      UUID originalOperation,
      UUID trader,
      long catalogRevision,
      int offerOrdinal,
      String offerIdentity,
      int executions,
      int villagerXp,
      Stage stage,
      long revision,
      String evidence) {
    public Progression {
      Objects.requireNonNull(originalOperation);
      Objects.requireNonNull(trader);
      Objects.requireNonNull(stage);
      if (catalogRevision < 1
          || offerOrdinal < 0
          || offerOrdinal >= 32
          || offerIdentity == null
          || !offerIdentity.matches("[0-9a-f]{64}")
          || executions < 1
          || executions > 1024
          || villagerXp < 0
          || villagerXp > 1024
          || revision < 1
          || evidence == null
          || evidence.length() > 4096
          || stage == Stage.ACKNOWLEDGED && evidence.isEmpty())
        throw new IllegalArgumentException("Original progression receipt");
    }
  }

  public record Event(
      WorldId world,
      UUID operation,
      Action action,
      Trader trader,
      MarketTicks market,
      Progression progression,
      TickSample sample,
      Eligibility eligibility,
      long bindingRevision) {
    public Event {
      Objects.requireNonNull(world);
      Objects.requireNonNull(operation);
      Objects.requireNonNull(action);
      if (bindingRevision < 0 || action != Action.TRADE && bindingRevision != 0)
        throw new IllegalArgumentException("Binding revision");
      boolean valid =
          switch (action) {
            case INSTALL ->
                trader != null
                    && market == null
                    && progression == null
                    && sample == null
                    && eligibility == null;
            case TRADER_TICKS ->
                trader != null
                    && market == null
                    && progression == null
                    && sample != null
                    && eligibility != null;
            case MARKET_TICKS ->
                trader == null
                    && market != null
                    && progression == null
                    && sample != null
                    && eligibility == null;
            case TRADE ->
                trader != null
                    && market == null
                    && progression != null
                    && sample == null
                    && eligibility == null
                    && bindingRevision > 0;
            case PROGRESSION ->
                trader == null
                    && market == null
                    && progression != null
                    && sample == null
                    && eligibility == null;
          };
      if (!valid) throw new IllegalArgumentException("Stock event shape");
    }
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog.with(new RecordCatalog.Key(TYPE, 1), StockFacts::decode);
  }

  public static String encode(Event event) {
    String value = JSON.toJson(event);
    if (value.getBytes(StandardCharsets.UTF_8).length > 16384)
      throw new IllegalArgumentException("Stock fact size");
    return value;
  }

  public static Event decode(String payload) {
    if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > 16384)
      throw new IllegalArgumentException("Stock fact size");
    try {
      var value = JSON.fromJson(payload, Event.class);
      if (value == null || !encode(value).equals(payload))
        throw new IllegalArgumentException("Noncanonical stock fact");
      return value;
    } catch (JsonParseException malformed) {
      throw new IllegalArgumentException("Malformed stock fact", malformed);
    }
  }

  public static UUID stable(String value) {
    return UUID.nameUUIDFromBytes(
        ("economy:a8:stock:v1|" + value).getBytes(StandardCharsets.UTF_8));
  }
}
