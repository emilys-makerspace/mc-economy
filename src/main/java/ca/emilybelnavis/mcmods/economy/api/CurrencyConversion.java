package ca.emilybelnavis.mcmods.economy.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic settlement and display calculations over a single rate publication. */
public final class CurrencyConversion {
  private CurrencyConversion() {}

  public enum Failure {
    MISSING_RATE,
    INVALID_INPUT,
    OUT_OF_RANGE,
    ZERO_CREDIT
  }

  public static final class Rejected extends IllegalArgumentException {
    private final Failure failure;

    private Rejected(Failure failure, String message) {
      super(message);
      this.failure = failure;
    }

    public Failure failure() {
      return failure;
    }
  }

  /** Rates from one publication, expressed as reference major units per currency major unit. */
  public record Rates(EconomyCurrency reference, Map<EconomyCurrency, BigDecimal> perMajorUnit) {
    public Rates {
      require(reference != null && perMajorUnit != null, "A reference and rate map are required");
      var copy = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
      perMajorUnit.forEach(
          (currency, rate) -> {
            require(
                currency != null && rate != null && rate.signum() > 0,
                "Each rate needs a currency and a positive value");
            require(
                currency != reference || rate.compareTo(BigDecimal.ONE) == 0,
                "The reference rate must equal one");
            copy.put(currency, rate);
          });
      perMajorUnit = Map.copyOf(copy);
    }

    private BigDecimal rate(EconomyCurrency currency) {
      BigDecimal rate = perMajorUnit.get(currency);
      if (rate == null) {
        throw new Rejected(Failure.MISSING_RATE, "Missing rate for " + currency.code());
      }
      return rate;
    }
  }

  /** Source subtotals are ordered by registry order and retain their original currency. */
  public record Deposit(Money credit, List<Money> payable, List<Money> skipped) {
    public Deposit {
      payable = List.copyOf(payable);
      skipped = List.copyOf(skipped);
    }
  }

  public static Deposit deposit(List<Money> inputs, EconomyCurrency accountCurrency, Rates rates) {
    require(inputs != null && !inputs.isEmpty(), "A deposit needs at least one amount");
    require(accountCurrency != null && rates != null, "An account currency and rates are required");

    var subtotals = new EnumMap<EconomyCurrency, Long>(EconomyCurrency.class);
    for (Money input : inputs) {
      requirePositive(input);
      subtotals.merge(input.currency(), input.minorUnits(), CurrencyConversion::add);
    }

    List<Money> payable = new ArrayList<>();
    List<Money> skipped = new ArrayList<>();
    long credit = 0;
    for (var subtotal : subtotals.entrySet()) {
      Money source = new Money(subtotal.getKey(), subtotal.getValue());
      long converted = ratio(source, accountCurrency, rates).round(RoundingMode.FLOOR);
      if (converted == 0) {
        skipped.add(source);
      } else {
        payable.add(source);
        credit = add(credit, converted);
      }
    }
    if (credit == 0) {
      throw new Rejected(Failure.ZERO_CREDIT, "No deposit group credits a whole minor unit");
    }
    return new Deposit(new Money(accountCurrency, credit), payable, skipped);
  }

  public static Money withdrawalDebit(Money cash, EconomyCurrency accountCurrency, Rates rates) {
    requirePositive(cash);
    long debit = ratio(cash, accountCurrency, rates).round(RoundingMode.CEILING);
    return new Money(accountCurrency, debit);
  }

  /** Historical display rounds half up after identifying signed sub-minor amounts. */
  public static String formatHistorical(
      Money source, EconomyCurrency target, Rates rates, Locale locale) {
    Ratio ratio = ratio(source, target, rates);
    int sign = ratio.numerator().signum();
    if (sign != 0 && ratio.numerator().abs().compareTo(ratio.denominator()) < 0) {
      String threshold = MoneyFormatter.format(new Money(target, 1), locale);
      return sign < 0 ? "−(< " + threshold + ")" : "< " + threshold;
    }
    return MoneyFormatter.format(new Money(target, ratio.round(RoundingMode.HALF_UP)), locale);
  }

  private static Ratio ratio(Money source, EconomyCurrency target, Rates rates) {
    require(
        source != null && target != null && rates != null,
        "An amount, target and rates are required");
    BigDecimal numerator =
        BigDecimal.valueOf(source.minorUnits())
            .multiply(rates.rate(source.currency()))
            .multiply(BigDecimal.valueOf(target.minorUnitsPerMajor()));
    BigDecimal denominator =
        rates.rate(target).multiply(BigDecimal.valueOf(source.currency().minorUnitsPerMajor()));
    return new Ratio(numerator, denominator);
  }

  private record Ratio(BigDecimal numerator, BigDecimal denominator) {
    long round(RoundingMode rounding) {
      try {
        return numerator.divide(denominator, 0, rounding).longValueExact();
      } catch (ArithmeticException ex) {
        throw new Rejected(Failure.OUT_OF_RANGE, "Converted amount exceeds the supported range");
      }
    }
  }

  private static long add(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException ex) {
      throw new Rejected(Failure.OUT_OF_RANGE, "Settlement subtotal exceeds the supported range");
    }
  }

  private static void requirePositive(Money amount) {
    require(amount != null && amount.minorUnits() > 0, "Settlement amounts must be positive");
  }

  private static void require(boolean valid, String message) {
    if (!valid) {
      throw new Rejected(Failure.INVALID_INPUT, message);
    }
  }
}
