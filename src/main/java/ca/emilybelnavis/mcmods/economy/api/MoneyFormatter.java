package ca.emilybelnavis.mcmods.economy.api;

import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/** Localized numeric display with an explicit currency code by default. */
public final class MoneyFormatter {
  private MoneyFormatter() {}

  public static String format(Money money, Locale playerLocale) {
    return format(money, playerLocale, true);
  }

  /** Omit the code only when the surrounding display already identifies the currency. */
  public static String format(Money money, Locale playerLocale, boolean includeCode) {
    Locale locale =
        playerLocale == null || playerLocale.getLanguage().isEmpty() ? Locale.CANADA : playerLocale;
    NumberFormat format = NumberFormat.getNumberInstance(locale);
    int digits = money.currency().fractionDigits();
    format.setMinimumFractionDigits(digits);
    format.setMaximumFractionDigits(digits);
    format.setRoundingMode(RoundingMode.UNNECESSARY);
    String amount = format.format(money.majorUnits());
    return includeCode ? amount + " " + money.currency().code() : amount;
  }
}
