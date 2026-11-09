package ca.emilybelnavis.mcmods.economy.loan;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LoanServicingTest {
  private static final EconomyCurrency CAD = EconomyCurrency.CAD;
  private static final BigDecimal APR = new BigDecimal("0.13");

  private static LoanServicing.State initial() {
    return LoanServicing.initial(CAD, 100000, APR, 1, false, 1000);
  }

  @Test
  void installmentsUseIndependentFormulaAndThirteenPaymentsPerYear() {
    for (int years : new int[] {1, 5, 8}) {
      boolean subprime = years == 8;
      int n = 13 * years;
      var apr = new BigDecimal("0.1999");
      long principal = 12345678;
      var mc = new MathContext(80, RoundingMode.HALF_EVEN);
      var r = apr.divide(BigDecimal.valueOf(13), mc);
      var growth = BigDecimal.ONE.add(r, mc).pow(n, mc);
      long expected =
          BigDecimal.valueOf(principal)
              .multiply(r, mc)
              .multiply(growth, mc)
              .divide(growth.subtract(BigDecimal.ONE, mc), mc)
              .setScale(0, RoundingMode.CEILING)
              .longValueExact();
      var schedule = LoanServicing.schedule(CAD, principal, apr, years, subprime);
      assertEquals(n, schedule.periods());
      assertEquals(n, schedule.installments().size());
      assertEquals(expected, schedule.installmentMinor());
      assertEquals(0, schedule.installments().getLast().remainingPrincipalMinor());
      assertEquals(
          principal,
          schedule.installments().stream()
              .mapToLong(LoanServicing.Installment::principalMinor)
              .sum());
      for (var row : schedule.installments()) assertTrue(row.paymentMinor() <= expected);
    }
  }

  @Test
  void zeroRateAndSmallestUnitFinalPaymentCloseWithoutPhantomDebt() {
    var schedule = LoanServicing.schedule(EconomyCurrency.JPY, 10000, BigDecimal.ZERO, 1, false);
    assertEquals(770, schedule.installmentMinor());
    assertEquals(13, schedule.installments().size());
    assertEquals(760, schedule.installments().getLast().paymentMinor());
    assertEquals(
        10000,
        schedule.installments().stream().mapToLong(LoanServicing.Installment::paymentMinor).sum());
    var state =
        LoanServicing.initial(EconomyCurrency.JPY, 1, new BigDecimal("0.1999"), 1, false, 0);
    var quote = LoanServicing.payoff(state, LoanServicing.MONTH / 2);
    assertEquals(1, quote.amountMinor());
    var payment = LoanServicing.settlePayoff(state, quote, quote.capturedAt() + 1);
    assertTrue(payment.state().closed());
    assertEquals(0, payment.state().exactDebtMajor().signum());
    assertTrue(payment.residualDisposition().signum() > 0);
    assertTrue(payment.additionalInterestWaived().signum() > 0);
  }

  @Test
  void earlyPrincipalPaymentPreservesOldSegmentAndNeverCompoundsCurrentInterest() {
    var state = initial();
    long half = state.periodStart() + LoanServicing.MONTH / 2;
    var earned = LoanServicing.accrue(state, half);
    assertEquals(new BigDecimal("5.000000000000000000"), earned.accruedMajor());
    var again = LoanServicing.accrue(earned, half + LoanServicing.MONTH / 4);
    assertEquals(new BigDecimal("7.500000000000000000"), again.accruedMajor());
    var payment = LoanServicing.repay(state, 10500, half);
    assertEquals(500, payment.recognizedInterestMinor());
    assertEquals(500, payment.interestPaid());
    assertEquals(10000, payment.principalPaid());
    assertEquals(90000, payment.state().principalMinor());
    assertEquals(state.installmentMinor(), payment.state().installmentMinor());
    var close = LoanServicing.monthClose(payment.state(), "first", state.nextDue());
    assertEquals(new BigDecimal("4.500000000000000000"), close.earnedMajor());
    assertEquals(450, close.postedInterestMinor());
    assertEquals(450, close.state().interestMinor());
    assertEquals(new BigDecimal("4.500000000000000000"), close.state().capitalizedInterestMajor());
  }

  @Test
  void unpaidCompletedInterestCompoundsAndOldestRequirementKeepsItsDate() {
    var state = initial();
    var first = LoanServicing.monthClose(state, "first", state.nextDue());
    assertEquals(1000, first.postedInterestMinor());
    var second = LoanServicing.monthClose(first.state(), "second", first.state().nextDue());
    assertEquals(1010, second.postedInterestMinor());
    assertEquals(2010, second.state().interestMinor());
    var oldest = second.state().requirements().getFirst();
    var payment =
        LoanServicing.repay(second.state(), oldest.unmetMinor(), second.state().lastAccrued());
    assertTrue(payment.allocations().stream().allMatch(a -> a.requirementId().equals("first")));
    assertTrue(payment.allocations().getFirst().interest());
    assertEquals(1, payment.state().requirements().size());
    assertEquals("second", payment.state().requirements().getFirst().id());
    assertEquals(second.requirement().dueAt(), payment.state().requirements().getFirst().dueAt());
  }

  @Test
  void earlyRepaymentKeepsContractInstallmentAndShortensRemainingPeriods() {
    var state = initial();
    long fixed = state.installmentMinor();
    state =
        LoanServicing.repay(state, 50000, state.periodStart() + LoanServicing.MONTH / 4).state();
    int actual = 0;
    while (!state.closed()) {
      var close = LoanServicing.monthClose(state, "period-" + actual, state.nextDue());
      state = close.state();
      assertEquals(fixed, state.installmentMinor());
      state =
          LoanServicing.repay(state, Math.min(fixed, state.debtMinor()), state.lastAccrued())
              .state();
      actual++;
    }
    assertTrue(actual < 13);
    assertEquals(0, state.exactDebtMajor().signum());
    assertEquals(state, LoanServicing.accrue(state, Long.MAX_VALUE));
  }

  @Test
  void payoffFreezesAmountOnlyWithinItsRevisionAndBoundaryWindow() {
    var state = initial();
    long at = state.periodStart() + LoanServicing.MONTH / 2;
    var quote = LoanServicing.payoff(state, at);
    assertEquals(100500, quote.amountMinor());
    assertEquals(at + 30000, quote.expiresAt());
    var paid = LoanServicing.settlePayoff(state, quote, at + 1000);
    assertTrue(paid.state().closed());
    assertEquals(500, paid.recognizedInterestMinor());
    assertTrue(paid.additionalInterestWaived().signum() > 0);
    assertThrows(
        IllegalArgumentException.class,
        () -> LoanServicing.settlePayoff(state, quote, quote.expiresAt()));
    var changed = LoanServicing.repay(state, 1, at).state();
    assertThrows(
        IllegalArgumentException.class, () -> LoanServicing.settlePayoff(changed, quote, at + 1));
    var boundary = LoanServicing.payoff(state, state.nextDue() - 1);
    assertEquals(state.nextDue(), boundary.expiresAt());
    assertThrows(
        IllegalArgumentException.class, () -> LoanServicing.repay(state, 100, state.nextDue()));
    assertThrows(
        IllegalArgumentException.class,
        () -> LoanServicing.repay(state, Long.MAX_VALUE, state.periodStart()));
  }

  @Test
  void invalidTermsAndUnrepresentableAmountsFailRatherThanClamp() {
    assertThrows(
        IllegalArgumentException.class, () -> LoanServicing.initial(CAD, 10000, APR, 6, false, 0));
    assertThrows(
        IllegalArgumentException.class, () -> LoanServicing.initial(CAD, 10000, APR, 9, true, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> LoanServicing.initial(CAD, 10000, new BigDecimal("-0.01"), 1, false, 0));
    assertThrows(
        ArithmeticException.class,
        () -> LoanServicing.installment(CAD, Long.MAX_VALUE, BigDecimal.ONE, 1));
  }

  @Test
  void onlyPriorPeriodRoundingCarryCanEnterTheCompoundingBase() {
    var current = LoanServicing.initial(CAD, 100000, new BigDecimal("0.12"), 1, false, 0);
    var partial = LoanServicing.repay(current, 10000, LoanServicing.MONTH / 2);
    assertTrue(partial.state().residualMajor().signum() < 0);
    assertEquals(
        0,
        partial.state().capitalizedInterestMajor().signum(),
        "Current-period rounding carry is not prior capitalized interest");
    var rest = LoanServicing.accrue(partial.state(), LoanServicing.MONTH);
    var expected =
        BigDecimal.valueOf(partial.state().principalMinor(), 2)
            .multiply(new BigDecimal("0.12"), LoanServicing.MC)
            .divide(BigDecimal.valueOf(13), LoanServicing.MC)
            .divide(BigDecimal.valueOf(2), LoanServicing.MC)
            .setScale(18, RoundingMode.HALF_EVEN);
    assertEquals(expected, rest.accruedMajor());
    var old = LoanServicing.initial(CAD, 100000, new BigDecimal("0.12345"), 1, false, 0);
    var closed = LoanServicing.monthClose(old, "old", old.nextDue());
    assertTrue(closed.state().residualMajor().signum() < 0);
    var paidInterest =
        LoanServicing.repay(
            closed.state(), closed.state().interestMinor(), closed.state().lastAccrued());
    assertEquals(
        closed.state().residualMajor(),
        paidInterest.state().capitalizedInterestMajor(),
        "Prior signed carry remains in its originating compounding base");
  }
}
