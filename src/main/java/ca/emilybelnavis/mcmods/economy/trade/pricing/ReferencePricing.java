package ca.emilybelnavis.mcmods.economy.trade.pricing;

import static ca.emilybelnavis.mcmods.economy.simulation.model.Rational.*;

import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import java.math.BigInteger;
import java.util.*;

/** Prospective rounded-unit pricing; legacy Pricing and its carry remain unchanged. */
public final class ReferencePricing {
  public static final String V2 = "economy:reference-unit/v2";
  public static final String VERSION = "economy:reference-unit/v1",
      ROUNDING = "economy:half-up-unit/v1";
  private static final Rational SPREAD = of("0.8"), REP = of("0.25"), SENSITIVITY = of("0.01");

  private ReferencePricing() {}

  public record Request(
      Pricing.Key key,
      TradePriceSource.Basis basis,
      int standardQuantity,
      long goods,
      Pricing.Demand demand,
      long eligibleTick,
      Rational reputation,
      List<Pricing.Comparison> comparisons) {
    public Request {
      Objects.requireNonNull(key);
      Objects.requireNonNull(basis);
      Objects.requireNonNull(demand);
      Objects.requireNonNull(reputation);
      comparisons = List.copyOf(comparisons);
      if (standardQuantity < 1
          || goods < 1
          || goods > 65_536
          || eligibleTick < demand.anchoredEligibleTick()
          || comparisons.isEmpty()
          || comparisons.size() > 16) throw new IllegalArgumentException("Reference price request");
    }
  }

  public record Quote(
      Rational exactPrincipal,
      long unitMinor,
      long minor,
      Pricing.Demand nextDemand,
      Rational reputationFactor,
      Rational currentMultiplier) {
    public Quote {
      Objects.requireNonNull(exactPrincipal);
      Objects.requireNonNull(nextDemand);
      Objects.requireNonNull(reputationFactor);
      Objects.requireNonNull(currentMultiplier);
      if (exactPrincipal.compareTo(ZERO) <= 0 || unitMinor < 1 || minor < 1)
        throw new IllegalArgumentException("Positive reference price");
    }
  }

  public record Outcome(boolean available, String reason, Quote quote) {
    public Outcome {
      Objects.requireNonNull(reason);
      if (available != (quote != null)) throw new IllegalArgumentException("Price outcome");
    }
  }

  public static TradePriceSource.Basis normalizedBasis(
      Pricing.Side side, TradePriceSource.Basis raw, Pricing.Comparison comparison) {
    return Pricing.normalizedBasis(side, raw, comparison);
  }

  public static Outcome quote(Request r) {
    return quote(r, VERSION);
  }

  public static Outcome quote(Request r, String implementation) {
    if (!Set.of(VERSION, V2).contains(implementation)) return no("PRICING_VERSION_UNAVAILABLE");
    for (var c : r.comparisons())
      if (!c.equivalenceKey().equals(r.key().item())) return no("COMPARISON_UNRESOLVED");
    if (r.comparisons().stream()
        .noneMatch(
            c ->
                r.basis()
                    .minorPerStandard()
                    .equals(r.key().side() == Pricing.Side.BUY ? c.neutralAsk() : c.neutralBid())))
      return no("PRICE_BASIS_MISMATCH");
    Rational ask =
        r.comparisons().stream()
            .map(Pricing.Comparison::neutralAsk)
            .min(Rational::compareTo)
            .orElseThrow();
    Rational bid =
        r.comparisons().stream()
            .map(Pricing.Comparison::neutralBid)
            .max(Rational::compareTo)
            .orElseThrow();
    if (bid.compareTo(ask.multiply(SPREAD)) > 0) return no("CROSS_OFFER_SPREAD_UNAVAILABLE");
    Rational t = r.reputation().max(REP.negate()).min(REP);
    if (t.compareTo(ZERO) > 0)
      t = t.min(ask.multiply(SPREAD).subtract(bid).divide(ask.multiply(SPREAD).add(bid)));
    // For every shared reachable demand m>=.5 and positive quantity, ask integral/q >= m
    // and bid integral/q <= m. HALF_UP errors are each <=.5 minor units. This sufficient
    // envelope proves the complete rounded cohort for this authenticated reputation witness.
    Rational margin =
        ask.multiply(SPREAD)
            .multiply(ONE.subtract(t))
            .subtract(bid.multiply(ONE.add(t)))
            .multiply(of("0.5"))
            .divide(of(r.standardQuantity()));
    if (implementation.equals(VERSION) && margin.compareTo(of("0.9")) < 0)
      return no("ROUNDED_COHORT_SPREAD_UNAVAILABLE");
    try {
      Rational multiplier = Pricing.recover(r.demand(), r.eligibleTick());
      if (implementation.equals(V2)) {
        Rational smallest = ONE.divide(of(r.standardQuantity()));
        Rational minimumAsk =
            ask.multiply(ONE.subtract(t))
                .multiply(Pricing.integral(multiplier, smallest, Pricing.Side.BUY));
        Rational maximumBid =
            bid.multiply(ONE.add(t))
                .multiply(Pricing.integral(multiplier, smallest, Pricing.Side.SELL));
        BigInteger roundedAsk = halfUp(minimumAsk).max(BigInteger.ONE),
            roundedBid = halfUp(maximumBid).max(BigInteger.ONE);
        if (of(roundedBid).compareTo(of(roundedAsk).multiply(SPREAD)) > 0)
          return no("ROUNDED_COHORT_SPREAD_UNAVAILABLE");
      }
      Rational q = of(r.goods()).divide(of(r.standardQuantity()));
      Rational factor = r.key().side() == Pricing.Side.BUY ? ONE.subtract(t) : ONE.add(t);
      Rational exact =
          r.basis()
              .minorPerStandard()
              .multiply(factor)
              .multiply(Pricing.integral(multiplier, q, r.key().side()));
      Rational unit = exact.divide(of(r.goods()));
      if (unit.numerator().bitLength() > 4096 || unit.denominator().bitLength() > 4096)
        return no("EXACT_PRICE_BOUND");
      long published = halfUp(unit).max(BigInteger.ONE).longValueExact();
      long total = Math.multiplyExact(published, r.goods());
      Rational next =
          multiplier
              .add(
                  (r.key().side() == Pricing.Side.BUY ? SENSITIVITY : SENSITIVITY.negate())
                      .multiply(q))
              .max(of("0.5"))
              .min(of(2));
      return new Outcome(
          true,
          "AVAILABLE",
          new Quote(
              exact,
              published,
              total,
              new Pricing.Demand(next.subtract(ONE), r.eligibleTick()),
              t,
              multiplier));
    } catch (IllegalArgumentException | ArithmeticException bound) {
      return no("REFERENCE_PRICE_BOUND");
    }
  }

  private static BigInteger halfUp(Rational value) {
    return value
        .numerator()
        .multiply(BigInteger.TWO)
        .add(value.denominator())
        .divide(value.denominator().multiply(BigInteger.TWO));
  }

  private static Outcome no(String reason) {
    return new Outcome(false, reason, null);
  }
}
