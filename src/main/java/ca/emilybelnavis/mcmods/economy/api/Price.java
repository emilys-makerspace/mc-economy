package ca.emilybelnavis.mcmods.economy.api;

import java.math.BigDecimal;
import java.util.Objects;

/** An exact decimal price in major units, which may include fractions of a minor unit. */
public record Price(EconomyCurrency currency, BigDecimal majorUnits) {
  public Price {
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(majorUnits, "majorUnits");
  }
}
