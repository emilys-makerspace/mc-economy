package ca.emilybelnavis.mcmods.economy.api;

import java.math.BigDecimal;
import java.util.Objects;

/** A signed amount in whole minor units. Sign constraints belong to callers. */
public record Money(EconomyCurrency currency, long minorUnits) {
  public Money {
    Objects.requireNonNull(currency, "currency");
  }

  public BigDecimal majorUnits() {
    return BigDecimal.valueOf(minorUnits, currency.fractionDigits());
  }

  public Money add(Money other) {
    requireSameCurrency(other);
    return new Money(currency, Math.addExact(minorUnits, other.minorUnits));
  }

  public Money subtract(Money other) {
    requireSameCurrency(other);
    return new Money(currency, Math.subtractExact(minorUnits, other.minorUnits));
  }

  public Money negate() {
    return new Money(currency, Math.negateExact(minorUnits));
  }

  private void requireSameCurrency(Money other) {
    if (currency != other.currency) {
      throw new IllegalArgumentException(
          "Currency mismatch: " + currency + " and " + other.currency);
    }
  }
}
