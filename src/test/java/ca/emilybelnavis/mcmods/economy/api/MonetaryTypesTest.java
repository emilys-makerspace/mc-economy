package ca.emilybelnavis.mcmods.economy.api;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MonetaryTypesTest {
  @Test
  void historicalRegistryMatchesAllApprovedFixtures() {
    var rates =
        Map.of(
            "CAD",
            "1",
            "EUR",
            "1.5582",
            "GBP",
            "1.8699",
            "AUD",
            "0.9813",
            "NZD",
            "0.7865",
            "USD",
            "1.3960",
            "HKD",
            "0.1779",
            "JPY",
            "0.008870",
            "CNY",
            "0.2098",
            "DKK",
            "0.20881622293");
    assertEquals(rates.size() + 1, EconomyCurrency.all().size());
    assertEquals(new BigDecimal("1.600000000000"), CHF.historicalCadRate());
    assertEquals(100, CHF.minorUnitsPerMajor());
    rates.forEach(
        (code, rate) -> {
          EconomyCurrency currency = EconomyCurrency.fromCode(code);
          assertEquals(code, currency.code());
          assertEquals(new BigDecimal(rate), currency.historicalCadRate());
          assertEquals(currency == JPY ? 1 : 100, currency.minorUnitsPerMajor());
        });
    assertThrows(UnsupportedOperationException.class, () -> EconomyCurrency.all().clear());
    assertEquals(CHF, EconomyCurrency.fromCode("CHF"));
    assertThrows(IllegalArgumentException.class, () -> EconomyCurrency.fromCode("cad"));
    assertThrows(IllegalArgumentException.class, () -> EconomyCurrency.fromCode(null));
  }

  @Test
  void signedMoneyIsExactAndChecked() {
    Money max = new Money(CAD, Long.MAX_VALUE);
    Money min = new Money(CAD, Long.MIN_VALUE);
    Money one = new Money(CAD, 1);
    assertEquals(new BigDecimal("92233720368547758.07"), max.majorUnits());
    assertEquals(new BigDecimal("-92233720368547758.08"), min.majorUnits());
    assertEquals(new Money(CAD, -1), max.add(min));
    assertEquals(new Money(CAD, 0), min.subtract(min));
    assertEquals(new Money(CAD, -1), one.negate());
    assertThrows(ArithmeticException.class, () -> max.add(one));
    assertThrows(ArithmeticException.class, () -> min.subtract(one));
    assertThrows(ArithmeticException.class, min::negate);
    assertThrows(IllegalArgumentException.class, () -> one.add(new Money(USD, 1)));
    assertThrows(IllegalArgumentException.class, () -> one.subtract(new Money(USD, 1)));
    assertThrows(NullPointerException.class, () -> new Money(null, 1));
  }

  @Test
  void pricesRetainFractionalMinorUnits() {
    BigDecimal fractional = new BigDecimal("-0.001250");
    assertEquals(fractional, new Price(CAD, fractional).majorUnits());
    assertEquals(fractional, new Price(JPY, fractional).majorUnits());
    assertThrows(NullPointerException.class, () -> new Price(CAD, null));
    assertThrows(NullPointerException.class, () -> new Price(null, fractional));
  }

  @Test
  void displayUsesLocaleAndScaleWithoutLosingPrecision() {
    assertEquals("1.234,05 CAD", MoneyFormatter.format(new Money(CAD, 123405), Locale.GERMANY));
    assertEquals("1,234 JPY", MoneyFormatter.format(new Money(JPY, 1234), Locale.JAPAN));
    assertEquals("0.01 CHF", MoneyFormatter.format(new Money(CHF, 1), Locale.ROOT));
    assertEquals("1.00 CAD", MoneyFormatter.format(new Money(CAD, 100), null));
    assertEquals("1.00 USD", MoneyFormatter.format(new Money(USD, 100), Locale.ROOT));
    assertEquals("1.00", MoneyFormatter.format(new Money(USD, 100), Locale.CANADA, false));
    assertEquals(
        "-92,233,720,368,547,758.08 CAD",
        MoneyFormatter.format(new Money(CAD, Long.MIN_VALUE), Locale.CANADA));
    assertEquals(
        "9,223,372,036,854,775,807 JPY",
        MoneyFormatter.format(new Money(JPY, Long.MAX_VALUE), Locale.JAPAN));
  }
}
