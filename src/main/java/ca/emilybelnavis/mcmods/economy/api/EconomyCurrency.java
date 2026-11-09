package ca.emilybelnavis.mcmods.economy.api;

import java.math.BigDecimal;
import java.util.List;

/** The fixed currency registry shared by integrated and dedicated servers. */
public enum EconomyCurrency {
  CAD(100, "1"),
  EUR(100, "1.5582"),
  GBP(100, "1.8699"),
  AUD(100, "0.9813"),
  NZD(100, "0.7865"),
  USD(100, "1.3960"),
  HKD(100, "0.1779"),
  JPY(1, "0.008870"),
  CNY(100, "0.2098"),
  DKK(100, "0.20881622293"),
  CHF(100, "1.600000000000");

  private static final List<EconomyCurrency> ALL = List.of(values());

  private final int minorUnitsPerMajor;
  private final BigDecimal historicalCadRate;

  EconomyCurrency(int minorUnitsPerMajor, String historicalCadRate) {
    this.minorUnitsPerMajor = minorUnitsPerMajor;
    this.historicalCadRate = new BigDecimal(historicalCadRate);
  }

  public static List<EconomyCurrency> all() {
    return ALL;
  }

  /** Resolves an exact, uppercase registry code. */
  public static EconomyCurrency fromCode(String code) {
    for (EconomyCurrency currency : ALL) {
      if (currency.code().equals(code)) {
        return currency;
      }
    }
    throw new IllegalArgumentException("Unknown currency code: " + code);
  }

  public String code() {
    return name();
  }

  public int minorUnitsPerMajor() {
    return minorUnitsPerMajor;
  }

  public int fractionDigits() {
    return minorUnitsPerMajor == 1 ? 0 : 2;
  }

  /** Approved opening CAD per major unit: historical fixtures plus the CHF game calibration. */
  public BigDecimal historicalCadRate() {
    return historicalCadRate;
  }
}
