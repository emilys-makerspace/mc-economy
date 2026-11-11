package ca.emilybelnavis.mcmods.economy.trade.pricing;

import static ca.emilybelnavis.mcmods.economy.simulation.model.Rational.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import java.util.Objects;
import java.util.UUID;

/** Detached A9 replacement seam. A source supplies an original principal, never posts money. */
public interface TradePriceSource {
  record Basis(
      UUID source,
      int revision,
      UUID fxSnapshot,
      EconomyCurrency currency,
      Rational minorPerStandard) {
    public Basis {
      Objects.requireNonNull(source);
      Objects.requireNonNull(fxSnapshot);
      Objects.requireNonNull(currency);
      if (revision < 1 || minorPerStandard.compareTo(ZERO) <= 0)
        throw new IllegalArgumentException("Price basis");
    }
  }

  Basis basis(long emeralds, long goods, int standardQuantity);

  /** Version one: original emerald principal, captured CAD-per-market-major FX, no live reads. */
  record EmeraldV1(
      UUID source,
      int revision,
      Rational cadPerEmerald,
      UUID fxSnapshot,
      EconomyCurrency marketCurrency,
      Rational cadPerMarketMajor)
      implements TradePriceSource {
    public EmeraldV1 {
      Objects.requireNonNull(source);
      Objects.requireNonNull(fxSnapshot);
      Objects.requireNonNull(marketCurrency);
      if (revision < 1
          || cadPerEmerald.compareTo(ZERO) <= 0
          || cadPerMarketMajor.compareTo(ZERO) <= 0)
        throw new IllegalArgumentException("Emerald price source");
    }

    @Override
    public Basis basis(long emeralds, long goods, int standardQuantity) {
      if (emeralds <= 0 || goods <= 0 || standardQuantity <= 0)
        throw new IllegalArgumentException("Catalog principal");
      Rational principal =
          of(emeralds)
              .multiply(cadPerEmerald)
              .divide(cadPerMarketMajor)
              .multiply(of(marketCurrency.minorUnitsPerMajor()))
              .multiply(of(standardQuantity))
              .divide(of(goods));
      return new Basis(source, revision, fxSnapshot, marketCurrency, principal);
    }
  }
}
