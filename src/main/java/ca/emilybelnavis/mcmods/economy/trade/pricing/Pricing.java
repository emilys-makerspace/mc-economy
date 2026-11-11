package ca.emilybelnavis.mcmods.economy.trade.pricing;

import static ca.emilybelnavis.mcmods.economy.simulation.model.Rational.*;

import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import java.math.BigInteger;
import java.util.*;

/** Exact detached pricing. A returned quote is a proposal, never financial/item authority. */
public final class Pricing {
  private Pricing() {}

  public static final long HALF_LIFE_TICKS = 24_000;
  private static final Rational MIN = of("0.5"),
      MAX = of(2),
      SENSITIVITY = of("0.01"),
      SPREAD = of("0.8"),
      REPUTATION = of("0.25");

  /** Allocation guard; the owning immutable fact envelope can impose a tighter bound. */
  private static final int MAX_EXACT_BITS = 32_768;

  public enum Side {
    BUY,
    SELL
  }

  public record Key(UUID market, String item, UUID actor, Side side, UUID epoch) {
    public Key {
      Objects.requireNonNull(market);
      Objects.requireNonNull(actor);
      Objects.requireNonNull(side);
      Objects.requireNonNull(epoch);
      if (item == null || !item.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("Exact goods key");
    }
  }

  /** Deviation from neutral one at the last real trade or explicit source epoch. */
  public record Demand(Rational deviation, long anchoredEligibleTick) {
    public Demand {
      Objects.requireNonNull(deviation);
      if (deviation.compareTo(MIN.subtract(ONE)) < 0
          || deviation.compareTo(MAX.subtract(ONE)) > 0
          || anchoredEligibleTick < 0) throw new IllegalArgumentException("Demand anchor");
    }
  }

  public record Carry(Key key, Rational residual) {
    public Carry {
      Objects.requireNonNull(key);
      Objects.requireNonNull(residual);
      if (key.side() == Side.BUY
          ? residual.compareTo(ONE.negate()) <= 0 || residual.compareTo(ZERO) > 0
          : residual.compareTo(ZERO) < 0 || residual.compareTo(ONE) >= 0)
        throw new IllegalArgumentException("Rounding carry");
    }

    public static Carry empty(Key key) {
      return new Carry(key, ZERO);
    }
  }

  /**
   * Trusted complete equivalent-offer comparison. Ingredients/components must already be resolved.
   */
  public record Comparison(String equivalenceKey, Rational neutralAsk, Rational neutralBid) {
    public Comparison {
      if (equivalenceKey == null
          || !equivalenceKey.matches("[0-9a-f]{64}")
          || neutralAsk.compareTo(ZERO) <= 0
          || neutralBid.compareTo(ZERO) <= 0
          || neutralBid.compareTo(neutralAsk.multiply(SPREAD)) > 0)
        throw new IllegalArgumentException("Neutral comparison/spread");
    }

    public static Comparison neutral(String key, Rational originalAsk, Rational originalBid) {
      return new Comparison(key, originalAsk, originalBid.min(originalAsk.multiply(SPREAD)));
    }
  }

  public record Request(
      Key key,
      TradePriceSource.Basis basis,
      int standardQuantity,
      long goods,
      Demand demand,
      long eligibleTick,
      Rational reputation,
      List<Comparison> comparisons,
      Carry carry) {
    public Request {
      Objects.requireNonNull(key);
      Objects.requireNonNull(basis);
      Objects.requireNonNull(demand);
      Objects.requireNonNull(reputation);
      Objects.requireNonNull(carry);
      comparisons = List.copyOf(comparisons);
      if (standardQuantity <= 0
          || goods <= 0
          || eligibleTick < demand.anchoredEligibleTick()
          || !carry.key().equals(key)
          || comparisons.size() > 16) throw new IllegalArgumentException("Detached price request");
    }
  }

  public record Quote(
      Rational exactPrincipal,
      long minor,
      Carry nextCarry,
      Demand nextDemand,
      Rational reputationFactor,
      Rational currentMultiplier) {
    public Quote {
      Objects.requireNonNull(exactPrincipal);
      Objects.requireNonNull(nextCarry);
      Objects.requireNonNull(nextDemand);
      Objects.requireNonNull(reputationFactor);
      Objects.requireNonNull(currentMultiplier);
      if (exactPrincipal.compareTo(ZERO) <= 0 || minor <= 0)
        throw new IllegalArgumentException("Positive trade principal");
    }
  }

  public record Outcome(boolean available, String reason, Quote quote) {
    public Outcome {
      Objects.requireNonNull(reason);
      if (available != (quote != null)) throw new IllegalArgumentException("Price outcome");
    }
  }

  /**
   * Bind a raw source quote to the established neutral pair without changing provenance. A narrow
   * original spread reduces its bid; a larger original spread is retained by Comparison.neutral.
   * The caller must establish exact offer/ingredient equivalence first.
   */
  public static TradePriceSource.Basis normalizedBasis(
      Side side, TradePriceSource.Basis raw, Comparison comparison) {
    Objects.requireNonNull(side);
    Objects.requireNonNull(raw);
    Objects.requireNonNull(comparison);
    Rational neutral = side == Side.BUY ? comparison.neutralAsk() : comparison.neutralBid();
    if (side == Side.BUY
        ? !raw.minorPerStandard().equals(neutral)
        : raw.minorPerStandard().compareTo(neutral) < 0)
      throw new IllegalArgumentException("Source principal does not support neutral comparison");
    return new TradePriceSource.Basis(
        raw.source(), raw.revision(), raw.fxSnapshot(), raw.currency(), neutral);
  }

  /** Checkpoints call this against the original anchor; they must not replace that anchor. */
  public static Rational recover(Demand demand, long eligibleTick) {
    if (eligibleTick < demand.anchoredEligibleTick())
      throw new IllegalArgumentException("Eligible tick rewind");
    long elapsed = Math.subtractExact(eligibleTick, demand.anchoredEligibleTick());
    if (demand.deviation().equals(ZERO)) return ONE;
    long halves = elapsed / HALF_LIFE_TICKS;
    if (halves > MAX_EXACT_BITS)
      throw new IllegalArgumentException("Recovery exact-arithmetic bound");
    Rational decay = new Rational(BigInteger.ONE, BigInteger.ONE.shiftLeft((int) halves));
    Rational interpolation =
        ONE.subtract(of(elapsed % HALF_LIFE_TICKS).divide(of(2 * HALF_LIFE_TICKS)));
    Rational result = ONE.add(demand.deviation().multiply(decay).multiply(interpolation));
    if (result.numerator().bitLength() > MAX_EXACT_BITS
        || result.denominator().bitLength() > MAX_EXACT_BITS)
      throw new IllegalArgumentException("Recovery exact-arithmetic bound");
    return result;
  }

  public static Outcome quote(Request request) {
    if (request.comparisons().isEmpty()) return unavailable("COMPARISON_UNAVAILABLE");
    for (var comparison : request.comparisons())
      if (!request.key().item().equals(comparison.equivalenceKey()))
        return unavailable("COMPARISON_UNRESOLVED");
    boolean matches =
        request.comparisons().stream()
            .anyMatch(
                c ->
                    request
                        .basis()
                        .minorPerStandard()
                        .equals(
                            request.key().side() == Side.BUY ? c.neutralAsk() : c.neutralBid()));
    if (!matches) return unavailable("PRICE_BASIS_MISMATCH");
    Rational ask =
        request.comparisons().stream()
            .map(Comparison::neutralAsk)
            .min(Rational::compareTo)
            .orElseThrow();
    Rational bid =
        request.comparisons().stream()
            .map(Comparison::neutralBid)
            .max(Rational::compareTo)
            .orElseThrow();
    if (bid.compareTo(ask.multiply(SPREAD)) > 0)
      return unavailable("CROSS_OFFER_SPREAD_UNAVAILABLE");
    Rational reputation = request.reputation().max(REPUTATION.negate()).min(REPUTATION);
    Rational t = reputation;
    if (t.compareTo(ZERO) > 0)
      t = t.min(ask.multiply(SPREAD).subtract(bid).divide(ask.multiply(SPREAD).add(bid)));
    Rational multiplier;
    try {
      multiplier = recover(request.demand(), request.eligibleTick());
    } catch (IllegalArgumentException bound) {
      return unavailable("RECOVERY_ARITHMETIC_UNAVAILABLE");
    }
    Rational quantity = of(request.goods()).divide(of(request.standardQuantity()));
    Rational buyIntegral = integral(multiplier, quantity, Side.BUY),
        sellIntegral = integral(multiplier, quantity, Side.SELL);
    Rational askExact = ask.multiply(ONE.subtract(t)).multiply(buyIntegral);
    Rational bidExact = bid.multiply(ONE.add(t)).multiply(sellIntegral);
    Rational factor = request.key().side() == Side.BUY ? ONE.subtract(t) : ONE.add(t);
    Rational exact =
        request
            .basis()
            .minorPerStandard()
            .multiply(factor)
            .multiply(request.key().side() == Side.BUY ? buyIntegral : sellIntegral);
    Rational accumulated = exact.add(request.carry().residual());
    BigInteger rounded = round(accumulated, request.key().side());
    if (rounded.signum() <= 0) return unavailable("LARGER_POSITIVE_BATCH_REQUIRED");
    // Opposite carry is not caller controlled here. Worst-case integer bounds prove the
    // final 20% gap for every reachable opposite carry, across all comparison offers.
    BigInteger minimumAsk = floor(askExact), maximumBid = ceil(bidExact);
    if (request.key().side() == Side.BUY) minimumAsk = minimumAsk.min(rounded);
    else maximumBid = maximumBid.max(rounded);
    if (minimumAsk.signum() <= 0 || of(maximumBid).compareTo(of(minimumAsk).multiply(SPREAD)) > 0)
      return unavailable("LARGER_SPREAD_SAFE_BATCH_REQUIRED");
    long minor;
    try {
      minor = rounded.longValueExact();
    } catch (ArithmeticException overflow) {
      return unavailable("PRINCIPAL_BOUND");
    }
    Rational next =
        multiplier
            .add(
                (request.key().side() == Side.BUY ? SENSITIVITY : SENSITIVITY.negate())
                    .multiply(quantity))
            .max(MIN)
            .min(MAX);
    return new Outcome(
        true,
        "AVAILABLE",
        new Quote(
            exact,
            minor,
            new Carry(request.key(), accumulated.subtract(of(rounded))),
            new Demand(next.subtract(ONE), request.eligibleTick()),
            t,
            multiplier));
  }

  /** Integral of the bounded marginal multiplier over positive standard quantities. */
  public static Rational integral(Rational initial, Rational quantity, Side side) {
    if (initial.compareTo(MIN) < 0 || initial.compareTo(MAX) > 0 || quantity.compareTo(ZERO) <= 0)
      throw new IllegalArgumentException("Progressive integral");
    Rational boundary = side == Side.BUY ? MAX : MIN;
    Rational toBound =
        (side == Side.BUY ? boundary.subtract(initial) : initial.subtract(boundary))
            .divide(SENSITIVITY);
    Rational moving = quantity.min(toBound);
    Rational slope = side == Side.BUY ? SENSITIVITY : SENSITIVITY.negate();
    return initial
        .multiply(moving)
        .add(slope.multiply(moving).multiply(moving).divide(of(2)))
        .add(boundary.multiply(quantity.subtract(moving)));
  }

  private static Outcome unavailable(String reason) {
    return new Outcome(false, reason, null);
  }

  private static BigInteger round(Rational value, Side side) {
    return side == Side.BUY ? ceil(value) : floor(value);
  }

  private static BigInteger floor(Rational value) {
    BigInteger[] qr = value.numerator().divideAndRemainder(value.denominator());
    return value.numerator().signum() < 0 && qr[1].signum() != 0
        ? qr[0].subtract(BigInteger.ONE)
        : qr[0];
  }

  private static BigInteger ceil(Rational value) {
    return floor(value.negate()).negate();
  }
}
