package ca.emilybelnavis.mcmods.economy.foundation;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import org.junit.jupiter.api.Test;

class CreditMenuAmountsTest {
  @Test
  void limitsRequireExactPositiveWholeUnits() {
    assertEquals(250_000, CreditMenus.wholeLimit("2500", EconomyCurrency.CHF));
    assertEquals(2500, CreditMenus.wholeLimit("2500", EconomyCurrency.JPY));
    for (var input : new String[] {"0", "-1", "1.0", "1e3", " 2500", "999999999999999999"})
      assertThrows(
          RuntimeException.class, () -> CreditMenus.wholeLimit(input, EconomyCurrency.CHF));
  }

  @Test
  void paymentsUseExactCurrencyPrecisionAndCheckedRange() {
    assertEquals(101, CreditMenus.paymentAmount("1.01", EconomyCurrency.CHF));
    assertEquals(1, CreditMenus.paymentAmount("1.00", EconomyCurrency.JPY));
    assertEquals(
        Long.MAX_VALUE, CreditMenus.paymentAmount("92233720368547758.07", EconomyCurrency.CAD));
    for (var input : new String[] {"0", "-1", "0.001", "1e3", " 1", "92233720368547758.08"})
      assertThrows(
          RuntimeException.class, () -> CreditMenus.paymentAmount(input, EconomyCurrency.CAD));
    assertThrows(
        ArithmeticException.class, () -> CreditMenus.paymentAmount("1.01", EconomyCurrency.JPY));
  }
}
