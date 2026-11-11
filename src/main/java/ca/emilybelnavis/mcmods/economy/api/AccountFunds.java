package ca.emilybelnavis.mcmods.economy.api;

import java.util.Objects;

/** Version 1 ledger view for one currency of a nonnegative account product. */
public record AccountFunds(
    EconomyCurrency currency,
    long availableMinorUnits,
    long reservedMinorUnits,
    long restrictedMinorUnits) {
  public static final int VERSION = 1;

  public AccountFunds {
    Objects.requireNonNull(currency, "currency");
    if (availableMinorUnits < 0 || reservedMinorUnits < 0 || restrictedMinorUnits < 0) {
      throw new IllegalArgumentException("Negative account funds category");
    }
    total(availableMinorUnits, reservedMinorUnits, restrictedMinorUnits);
  }

  public int version() {
    return VERSION;
  }

  /** The ledger assigns each posted minor unit to exactly one of the three categories. */
  public long postedMinorUnits() {
    return total(availableMinorUnits, reservedMinorUnits, restrictedMinorUnits);
  }

  public Money posted() {
    return new Money(currency, postedMinorUnits());
  }

  private static long total(long available, long reserved, long restricted) {
    return Math.addExact(Math.addExact(available, reserved), restricted);
  }
}
