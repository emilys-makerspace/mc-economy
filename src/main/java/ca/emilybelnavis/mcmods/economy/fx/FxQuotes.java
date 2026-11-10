package ca.emilybelnavis.mcmods.economy.fx;

import ca.emilybelnavis.mcmods.economy.api.CurrencyConversion;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.api.Money;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** One trusted worker-confined book per server session. A4 owns durable consumption. */
public final class FxQuotes {
  public enum Side {
    EXACT_DEBIT,
    EXACT_CREDIT
  }

  public record Pair(EconomyCurrency source, EconomyCurrency target) {
    public Pair {
      if (source == null || target == null || source == target)
        throw new IllegalArgumentException("Invalid pair");
    }
  }

  public record Quote(
      UUID id,
      PlayerId actor,
      WorldId world,
      Pair pair,
      Side side,
      Money requested,
      UUID publication,
      int pricingVersion,
      Instant expires,
      Money fee,
      Money debit,
      Money credit,
      ca.emilybelnavis.mcmods.economy.pricing.ServicePricing.Quote pricing) {
    public Quote(
        UUID id,
        PlayerId actor,
        WorldId world,
        Pair pair,
        Side side,
        Money requested,
        UUID publication,
        int pricingVersion,
        Instant expires,
        Money fee,
        Money debit,
        Money credit) {
      this(
          id,
          actor,
          world,
          pair,
          side,
          requested,
          publication,
          pricingVersion,
          expires,
          fee,
          debit,
          credit,
          null);
    }
  }

  private static final int CAPACITY = 1024;
  static final int PRICING_VERSION = 1;
  private final WorldId world;
  private final Map<UUID, Quote> quotes = new HashMap<>();

  public FxQuotes(WorldId world) {
    this.world = java.util.Objects.requireNonNull(world);
  }

  /** Actor comes from authenticated server admission and publication from the world store. */
  public Quote issue(
      PlayerId actor,
      Pair pair,
      Side side,
      Money requested,
      FxFacts.Publication publication,
      Instant now,
      Duration lifetime) {
    java.util.Objects.requireNonNull(actor);
    java.util.Objects.requireNonNull(side);
    if (publication == null) throw new IllegalStateException("FX_UNINITIALIZED");
    if (lifetime.isNegative()
        || lifetime.isZero()
        || lifetime.compareTo(Duration.ofSeconds(60)) > 0
        || requested.minorUnits() <= 0) throw new IllegalArgumentException("FX_INVALID_QUOTE");
    boolean exactDebit = side == Side.EXACT_DEBIT;
    var source = exactDebit ? pair.source() : pair.target();
    var target = exactDebit ? pair.target() : pair.source();
    if (requested.currency() != source) throw new IllegalArgumentException("FX_INVALID_QUOTE");

    if (!publication.rates().containsKey(pair.source())
        || !publication.rates().containsKey(pair.target()))
      throw new IllegalArgumentException("FX_RATE_UNAVAILABLE");

    quotes.values().removeIf(quote -> !fresh(quote, publication, PRICING_VERSION, now));
    if (quotes.size() >= CAPACITY) throw new IllegalStateException("FX_QUOTE_OVERLOAD");

    var converted =
        CurrencyConversion.convert(
            requested,
            target,
            new CurrencyConversion.Rates(publication.reference(), publication.rates()),
            exactDebit ? CurrencyConversion.receivedRounding(target) : RoundingMode.HALF_UP);
    long units = converted.minorUnits();
    if (units < 0 || (!exactDebit && units == 0))
      throw new IllegalArgumentException("FX_ZERO_CREDIT");
    var quote =
        new Quote(
            UUID.randomUUID(),
            actor,
            world,
            pair,
            side,
            requested,
            publication.updateId(),
            PRICING_VERSION,
            now.plus(lifetime),
            new Money(pair.source(), 0),
            exactDebit ? requested : converted,
            exactDebit ? converted : requested);
    quotes.put(quote.id(), quote);
    return quote;
  }

  /** Live exact-time source; the Publication overload remains for explicitly legacy fixtures. */
  public Quote issue(
      PlayerId actor,
      Pair pair,
      Side side,
      Money requested,
      EffectiveFxSnapshot snapshot,
      Instant now,
      Duration lifetime) {
    if (lifetime == null
        || lifetime.isNegative()
        || lifetime.isZero()
        || lifetime.compareTo(Duration.ofSeconds(60)) > 0)
      throw new IllegalArgumentException("FX_INVALID_QUOTE");
    requireEffective(snapshot, now);
    if (!world.equals(snapshot.world())) throw new IllegalArgumentException("FX_WORLD");
    Instant maximum = Instant.ofEpochMilli(snapshot.effectiveMillis()).plus(Duration.ofHours(48));
    Duration bounded =
        Duration.between(now, now.plus(lifetime).isBefore(maximum) ? now.plus(lifetime) : maximum);
    return issue(actor, pair, side, requested, snapshot.publication(), now, bounded);
  }

  public Quote requireFresh(
      UUID id,
      PlayerId actor,
      WorldId requestWorld,
      EffectiveFxSnapshot snapshot,
      int pricing,
      Instant now) {
    requireEffective(snapshot, now);
    if (!requestWorld.equals(snapshot.world()) || !world.equals(snapshot.world()))
      throw new IllegalArgumentException("FX_WORLD");
    return requireFresh(id, actor, requestWorld, snapshot.publication(), pricing, now);
  }

  private static void requireEffective(EffectiveFxSnapshot snapshot, Instant now) {
    if (snapshot == null) throw new IllegalStateException("FX_UNINITIALIZED");
    Instant at = Instant.ofEpochMilli(snapshot.effectiveMillis());
    if (now.isBefore(at) || !now.isBefore(at.plus(Duration.ofHours(48))))
      throw new IllegalArgumentException("FX_SOURCE_STALE");
  }

  public Quote price(
      Quote original, ca.emilybelnavis.mcmods.economy.pricing.ServicePricing.Quote pricing) {
    if (!original.equals(quotes.get(original.id()))
        || !pricing.principal().equals(original.debit()))
      throw new IllegalArgumentException("FX_PRICING_PRINCIPAL");
    var priced =
        new Quote(
            original.id(),
            original.actor(),
            original.world(),
            original.pair(),
            original.side(),
            original.requested(),
            original.publication(),
            2,
            pricing.expires(),
            pricing.fee(),
            original.debit(),
            original.credit(),
            pricing);
    quotes.put(priced.id(), priced);
    return priced;
  }

  /** Call inside A4 authoritative preparation, after original-operation lookup. */
  public Quote requireFresh(
      UUID id,
      PlayerId actor,
      WorldId requestWorld,
      FxFacts.Publication publication,
      int currentPricingVersion,
      Instant processingStart) {
    var quote = quotes.get(id);
    if (quote == null
        || !quote.actor().equals(actor)
        || !quote.world().equals(requestWorld)
        || !fresh(quote, publication, currentPricingVersion, processingStart)) {
      throw new IllegalArgumentException("FX_QUOTE_UNAVAILABLE");
    }
    return quote;
  }

  private static boolean fresh(
      Quote quote, FxFacts.Publication publication, int pricing, Instant now) {
    return publication != null
        && quote.publication().equals(publication.updateId())
        && quote.pricingVersion() == (quote.pricing() == null ? pricing : 2)
        && now.isBefore(quote.expires());
  }
}
