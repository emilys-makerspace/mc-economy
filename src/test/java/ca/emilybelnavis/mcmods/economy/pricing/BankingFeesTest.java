package ca.emilybelnavis.mcmods.economy.pricing;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BankingFeesTest {
  private CurrencyConversion.Rates openingRates() {
    var rates = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
    EconomyCurrency.all().forEach(currency -> rates.put(currency, currency.historicalCadRate()));
    return new CurrencyConversion.Rates(EconomyCurrency.CAD, rates);
  }

  @Test
  void feesConvertFromCadAndRoundToTargetQuarterOrTwentyFiveYen() {
    var rates = openingRates();
    assertEquals(300, BankingFees.advanceFee(EconomyCurrency.CAD, rates).minorUnits());
    assertEquals(250, BankingFees.atmFee(EconomyCurrency.CAD, "other", "bank", rates).minorUnits());
    assertEquals(200, BankingFees.advanceFee(EconomyCurrency.EUR, rates).minorUnits());
    assertEquals(150, BankingFees.atmFee(EconomyCurrency.EUR, "other", "bank", rates).minorUnits());
    assertEquals(225, BankingFees.advanceFee(EconomyCurrency.USD, rates).minorUnits());
    assertEquals(175, BankingFees.atmFee(EconomyCurrency.USD, "other", "bank", rates).minorUnits());
    assertEquals(350, BankingFees.advanceFee(EconomyCurrency.JPY, rates).minorUnits());
    assertEquals(275, BankingFees.atmFee(EconomyCurrency.JPY, "other", "bank", rates).minorUnits());
    assertEquals(0, BankingFees.atmFee(EconomyCurrency.JPY, "bank", "bank", rates).minorUnits());
    assertEquals(0, BankingFees.atmFee(EconomyCurrency.JPY, "other", null, rates).minorUnits());
  }

  @Test
  void roundingUsesExactRatioWithHalfUpTiesAndNoIntermediateMinorUnitRounding() {
    assertEquals(
        100,
        BankingFees.convert(new BigDecimal("1.1249"), EconomyCurrency.CAD, openingRates())
            .minorUnits());
    assertEquals(
        125,
        BankingFees.convert(new BigDecimal("1.125"), EconomyCurrency.CAD, openingRates())
            .minorUnits());
    assertEquals(
        50,
        BankingFees.convert(
                new BigDecimal("3"), EconomyCurrency.JPY, BigDecimal.ONE, new BigDecimal("0.08"))
            .minorUnits());
    var otherReference =
        new CurrencyConversion.Rates(
            EconomyCurrency.USD,
            Map.of(
                EconomyCurrency.USD,
                BigDecimal.ONE,
                EconomyCurrency.CAD,
                new BigDecimal("2"),
                EconomyCurrency.EUR,
                new BigDecimal("4")));
    assertEquals(150, BankingFees.advanceFee(EconomyCurrency.EUR, otherReference).minorUnits());
    assertEquals(
        0,
        BankingFees.convert(
                new BigDecimal("3"), EconomyCurrency.EUR, BigDecimal.ONE, new BigDecimal("100"))
            .minorUnits());
    assertThrows(
        IllegalArgumentException.class,
        () -> BankingFees.advanceFee(EconomyCurrency.JPY, otherReference));
  }
}
