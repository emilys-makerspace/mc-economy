package ca.emilybelnavis.mcmods.economy.pricing;

import ca.emilybelnavis.mcmods.economy.api.*;
import java.math.*;
import java.util.Objects;

/**
 * B4 may persist this per-order accumulator. Only incremental earned rounding is payable per fill.
 */
public final class CumulativeTradingFee {
  private CumulativeTradingFee() {}

  public record Accumulator(EconomyCurrency currency, BigDecimal exactFeeMajor, long postedMinor) {
    public Accumulator {
      Objects.requireNonNull(currency);
      Objects.requireNonNull(exactFeeMajor);
      if (exactFeeMajor.signum() < 0
          || exactFeeMajor.precision() > 34
          || exactFeeMajor.scale() > 18
          || postedMinor < 0
          || BigDecimal.valueOf(postedMinor)
                  .compareTo(
                      exactFeeMajor
                          .multiply(BigDecimal.valueOf(currency.minorUnitsPerMajor()))
                          .setScale(0, RoundingMode.CEILING))
              != 0) throw new IllegalArgumentException("Trading accumulator");
    }

    public static Accumulator empty(EconomyCurrency currency) {
      return new Accumulator(currency, BigDecimal.ZERO, 0);
    }

    public Fill fill(Money cashNotional, ServicePricing.Tier tier) {
      if (cashNotional.currency() != currency || cashNotional.minorUnits() < 0)
        throw new IllegalArgumentException("Executed trading notional");
      BigDecimal earned =
          tier == ServicePricing.Tier.TIER2
              ? BigDecimal.ZERO
              : BigDecimal.valueOf(cashNotional.minorUnits())
                  .divide(BigDecimal.valueOf(currency.minorUnitsPerMajor()))
                  .multiply(PricingPolicy.TRADING_RATE);
      var exact =
          exactFeeMajor.add(earned, WealthValuation.MATH).setScale(18, RoundingMode.HALF_EVEN);
      long total =
          exact
              .multiply(BigDecimal.valueOf(currency.minorUnitsPerMajor()))
              .setScale(0, RoundingMode.CEILING)
              .longValueExact();
      return new Fill(
          new Accumulator(currency, exact, total),
          new Money(currency, Math.subtractExact(total, postedMinor)));
    }
  }

  public record Fill(Accumulator accumulator, Money incrementalFee) {}
}
