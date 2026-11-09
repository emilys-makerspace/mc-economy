package ca.emilybelnavis.mcmods.economy.api;

import static ca.emilybelnavis.mcmods.economy.api.CurrencyConversion.*;
import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CurrencyConversionTest {
  @Test
  void historicalFixtureAndCrossPairOrientation() {
    var fixture = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
    EconomyCurrency.all().forEach(currency -> fixture.put(currency, currency.historicalCadRate()));
    Rates rates = new Rates(CAD, fixture);
    fixture.clear();

    assertEquals(new Money(CAD, 2792), deposit(List.of(new Money(USD, 2000)), CAD, rates).credit());
    assertEquals(new Money(CAD, 887), deposit(List.of(new Money(JPY, 1000)), CAD, rates).credit());
    assertEquals(new Money(USD, 2000), deposit(List.of(new Money(CAD, 2792)), USD, rates).credit());
    assertEquals(new Money(USD, 635), deposit(List.of(new Money(JPY, 1000)), USD, rates).credit());
    assertEquals(new Money(JPY, 3147), deposit(List.of(new Money(USD, 2000)), JPY, rates).credit());
  }

  @Test
  void depositRoundsPerGroupAndReportsCashToRetain() {
    Rates rates =
        new Rates(
            JPY, Map.of(JPY, BigDecimal.ONE, CAD, new BigDecimal("75"), USD, new BigDecimal("80")));
    Money cent = new Money(CAD, 1);
    Money skipped = new Money(USD, 1);
    Deposit result = deposit(List.of(cent, skipped, cent), JPY, rates);
    assertEquals(new Money(JPY, 1), result.credit());
    assertEquals(List.of(new Money(CAD, 2)), result.payable());
    assertEquals(List.of(skipped), result.skipped());
    assertThrows(UnsupportedOperationException.class, () -> result.skipped().clear());
    rejects(Failure.ZERO_CREDIT, () -> deposit(List.of(cent, skipped), JPY, rates));
  }

  @Test
  void withdrawalRoundsUpAfterConvertingRequestedCash() {
    Rates rates = new Rates(JPY, Map.of(JPY, BigDecimal.ONE, CAD, new BigDecimal("150")));
    assertEquals(new Money(CAD, 2), withdrawalDebit(new Money(JPY, 2), CAD, rates));
    assertEquals(new Money(CAD, 2), withdrawalDebit(new Money(JPY, 3), CAD, rates));
  }

  @Test
  void historyDistinguishesTinySignedAmountsFromZero() {
    Rates rates = new Rates(JPY, Map.of(JPY, BigDecimal.ONE, CAD, new BigDecimal("150")));
    assertEquals("< 0,01 CAD", formatHistorical(new Money(JPY, 1), CAD, rates, Locale.GERMANY));
    assertEquals("−(< 0.01 CAD)", formatHistorical(new Money(JPY, -1), CAD, rates, Locale.CANADA));
    assertEquals("0.00 CAD", formatHistorical(new Money(JPY, 0), CAD, rates, Locale.CANADA));
    assertEquals("2 JPY", formatHistorical(new Money(CAD, 1), JPY, rates, Locale.CANADA));
  }

  @Test
  void rejectsMissingMalformedAndOutOfRangeRequests() {
    Rates rates = new Rates(CAD, Map.of(CAD, BigDecimal.ONE, USD, BigDecimal.TWO));
    Money max = new Money(CAD, Long.MAX_VALUE);
    Money cent = new Money(CAD, 1);
    assertEquals(max, deposit(List.of(max), CAD, rates).credit());
    rejects(Failure.MISSING_RATE, () -> deposit(List.of(cent, new Money(JPY, 1)), CAD, rates));
    rejects(Failure.MISSING_RATE, () -> withdrawalDebit(cent, JPY, rates));
    rejects(Failure.INVALID_INPUT, () -> new Rates(CAD, Map.of(USD, BigDecimal.ZERO)));
    rejects(Failure.INVALID_INPUT, () -> new Rates(CAD, Map.of(USD, BigDecimal.ONE.negate())));
    rejects(Failure.INVALID_INPUT, () -> new Rates(CAD, Map.of(CAD, BigDecimal.TWO)));
    rejects(Failure.INVALID_INPUT, () -> deposit(List.of(), CAD, rates));
    rejects(Failure.INVALID_INPUT, () -> deposit(List.of(cent, cent.negate()), CAD, rates));
    rejects(Failure.INVALID_INPUT, () -> withdrawalDebit(new Money(CAD, 0), CAD, rates));
    rejects(Failure.OUT_OF_RANGE, () -> deposit(List.of(max, cent), CAD, rates));
    rejects(Failure.OUT_OF_RANGE, () -> deposit(List.of(max, new Money(USD, 1)), CAD, rates));
    rejects(
        Failure.OUT_OF_RANGE, () -> withdrawalDebit(new Money(USD, Long.MAX_VALUE), CAD, rates));
  }

  private static void rejects(Failure failure, Runnable operation) {
    assertEquals(failure, assertThrows(Rejected.class, operation::run).failure());
  }
}
