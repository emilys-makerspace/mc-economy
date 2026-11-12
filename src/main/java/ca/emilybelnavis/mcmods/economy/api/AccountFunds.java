package ca.emilybelnavis.mcmods.economy.api;

import java.util.Objects;

/** Version 2 ledger view. Available funds may be negative for an overdrawn bank account. */
public record AccountFunds(
    EconomyCurrency currency,
    long availableMinorUnits,
    long reservedMinorUnits,
    long restrictedMinorUnits) {
  public static final int VERSION = 2;

  public AccountFunds {
    Objects.requireNonNull(currency, "currency");
    if (reservedMinorUnits < 0 || restrictedMinorUnits < 0) {
      throw new IllegalArgumentException("Negative reserved or restricted funds");
    }
    total(availableMinorUnits, reservedMinorUnits, restrictedMinorUnits);
  }

  public int version() {
    return VERSION;
  }

  /** Signed available funds plus reserved and restricted funds give the posted balance. */
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
