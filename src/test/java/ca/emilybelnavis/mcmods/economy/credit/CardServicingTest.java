package ca.emilybelnavis.mcmods.economy.credit;

import static ca.emilybelnavis.mcmods.economy.credit.CardServicing.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CardServicingTest {
  private static final EconomyCurrency CAD = EconomyCurrency.CAD;
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(18);

  private static Bucket purchase(long principal) {
    return new Bucket(Kind.PURCHASE, principal, 0, ZERO, ZERO, new BigDecimal("0.1999"));
  }

  private static Bucket advance(long principal) {
    return new Bucket(Kind.ADVANCE, principal, 0, ZERO, ZERO, new BigDecimal("0.2199"));
  }

  @Test
  void firstDayAndTwentyEightDaysRetainExactCompoundingAndSignedCarry() {
    var b = purchase(12300);
    var first = day(CAD, b);
    assertEquals(new BigDecimal("0.067548626373626374"), first.earnedMajor());
    assertEquals(7, first.postedMinor());
    assertTrue(first.bucket().residualMajor().signum() < 0);
    for (int n = 0; n < 28; n++) b = day(CAD, b).bucket();
    BigDecimal actual = BigDecimal.valueOf(b.interestMinor(), 2).add(b.residualMajor());
    BigDecimal expected =
        new BigDecimal("123")
            .multiply(
                BigDecimal.ONE
                    .add(new BigDecimal("0.1999").divide(BigDecimal.valueOf(364), MC), MC)
                    .pow(28, MC)
                    .subtract(BigDecimal.ONE),
                MC);
    assertTrue(actual.subtract(expected).abs().compareTo(new BigDecimal("0.0000000000000001")) < 0);
    assertEquals(0, actual.compareTo(b.periodEarnedMajor()));
  }

  @Test
  void separateNetworkBucketsAreNotBlended() {
    assertEquals(new BigDecimal("0.2199"), purchaseApr("economy:keystone"));
    assertEquals(new BigDecimal("0.2399"), advanceApr("economy:keystone"));
    assertEquals(new BigDecimal("0.1999"), purchaseApr("economy:orbit"));
    assertThrows(IllegalArgumentException.class, () -> purchaseApr("economy:unknown"));
    var p = day(CAD, purchase(10000));
    var a = day(CAD, advance(10000));
    assertTrue(a.earnedMajor().compareTo(p.earnedMajor()) > 0);
    assertEquals(6, a.postedMinor());
    assertEquals(5, p.postedMinor());
  }

  @Test
  void minimumUsesOnlyCurrentEarnedInterestAndNeverRebillsArrears() {
    long close = MONTH_MILLIS * 2;
    var p =
        new Bucket(
            Kind.PURCHASE, 10000, 400, ZERO, new BigDecimal("0.50"), new BigDecimal("0.1999"));
    var old = new Requirement("old", MONTH_MILLIS, MONTH_MILLIS + 14 * DAY_MILLIS, 10000, 10000);
    var statement = statement(CAD, "new", close, List.of(p, advance(0)), List.of(old));
    assertEquals(250, statement.newRequirementMinor());
    assertEquals(10250, statement.totalRequiredMinor());
    var capped =
        statement(
            CAD,
            "capped",
            close,
            List.of(p, advance(0)),
            List.of(
                new Requirement(
                    "old", MONTH_MILLIS, MONTH_MILLIS + 14 * DAY_MILLIS, 10300, 10300)));
    assertEquals(100, capped.newRequirementMinor());
    assertEquals(10400, capped.totalRequiredMinor());
    assertEquals(ZERO, nextPeriod(p).periodEarnedMajor());
    assertEquals(400, nextPeriod(p).interestMinor());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            statement(
                CAD,
                "bad",
                close,
                List.of(p, advance(0)),
                List.of(
                    new Requirement(
                        "old", MONTH_MILLIS, MONTH_MILLIS + 14 * DAY_MILLIS, 10401, 10401))));
  }

  @Test
  void oldestOverdueDebtPrecedesNewerHigherAprAndRequirementsAreSatisfiedOnce() {
    long now = MONTH_MILLIS * 3;
    var p = new Bucket(Kind.PURCHASE, 1000, 50, ZERO, ZERO, new BigDecimal("0.1999"));
    var a = new Bucket(Kind.ADVANCE, 1000, 70, ZERO, ZERO, new BigDecimal("0.2199"));
    var requirements =
        List.of(
            new Requirement("new", MONTH_MILLIS * 2, MONTH_MILLIS * 2 + 14 * DAY_MILLIS, 200, 200),
            new Requirement("old", MONTH_MILLIS, MONTH_MILLIS + 14 * DAY_MILLIS, 100, 100));
    var old =
        new DebtSlice("old", MONTH_MILLIS, MONTH_MILLIS + 14 * DAY_MILLIS, Kind.PURCHASE, 400, 50);
    var payment = repay(CAD, List.of(p, a), requirements, List.of(old), 300, now);
    assertEquals(new Allocation("old", Kind.PURCHASE, true, 50), payment.allocations().get(0));
    assertEquals(new Allocation("old", Kind.PURCHASE, false, 250), payment.allocations().get(1));
    assertEquals(750, payment.buckets().get(0).principalMinor());
    assertEquals(70, payment.buckets().get(1).interestMinor());
    assertTrue(payment.requirements().stream().allMatch(r -> r.unmetMinor() == 0));
    assertEquals(150, payment.slices().getFirst().principalMinor());
  }

  @Test
  void otherInterestPrecedesHighestAprPrincipalAndEqualAprAdvanceWins() {
    var p = new Bucket(Kind.PURCHASE, 1000, 50, ZERO, ZERO, new BigDecimal("0.2199"));
    var a = new Bucket(Kind.ADVANCE, 1000, 70, ZERO, ZERO, new BigDecimal("0.2199"));
    var payment = repay(CAD, List.of(p, a), List.of(), List.of(), 220, 0);
    assertEquals(new Allocation("", Kind.ADVANCE, true, 70), payment.allocations().get(0));
    assertEquals(new Allocation("", Kind.PURCHASE, true, 50), payment.allocations().get(1));
    assertEquals(new Allocation("", Kind.ADVANCE, false, 100), payment.allocations().get(2));
  }

  @Test
  void beforeDuePaymentConsumesOldStatementBeforeLaterSpendingInSameBucket() {
    var p = purchase(1000);
    long close = MONTH_MILLIS;
    var old = new DebtSlice("statement", close, close + 14 * DAY_MILLIS, Kind.PURCHASE, 400, 0);
    var requirement = new Requirement("statement", close, close + 14 * DAY_MILLIS, 100, 100);
    var payment =
        repay(
            CAD,
            List.of(p, advance(0)),
            List.of(requirement),
            List.of(old),
            300,
            close + DAY_MILLIS);
    assertEquals(700, payment.buckets().getFirst().principalMinor());
    assertEquals(100, payment.slices().getFirst().principalMinor());
    assertEquals(0, payment.requirements().getFirst().unmetMinor());
    assertEquals(
        List.of(new Allocation("statement", Kind.PURCHASE, false, 300)), payment.allocations());
    var rest =
        repay(
            CAD,
            payment.buckets(),
            payment.requirements(),
            payment.slices(),
            200,
            close + 2 * DAY_MILLIS);
    assertEquals(0, rest.slices().getFirst().principalMinor());
    assertEquals(
        List.of(
            new Allocation("statement", Kind.PURCHASE, false, 100),
            new Allocation("", Kind.PURCHASE, false, 100)),
        rest.allocations());
  }

  @Test
  void tinyRemainingPrincipalRetainsNegativeCarryWithoutNegativeNextDayPosting() {
    var charged = day(CAD, purchase(12300)).bucket();
    assertTrue(charged.residualMajor().signum() < 0);
    var partial =
        repay(
            CAD,
            List.of(charged, advance(0)),
            List.of(),
            List.of(),
            charged.debtMinor() - 1,
            DAY_MILLIS);
    var remaining = partial.buckets().getFirst();
    assertEquals(1, remaining.principalMinor());
    assertEquals(0, remaining.interestMinor());
    assertEquals(charged.residualMajor(), remaining.residualMajor());
    assertEquals(ZERO, partial.waivedResidualMajor());
    var next = day(CAD, remaining);
    assertEquals(0, next.postedMinor());
    assertEquals(0, next.bucket().interestMinor());
    assertTrue(next.earnedMajor().signum() > 0);
    assertTrue(next.bucket().residualMajor().compareTo(remaining.residualMajor()) > 0);
    var last =
        repay(CAD, List.of(next.bucket(), advance(0)), List.of(), List.of(), 1, 2 * DAY_MILLIS);
    assertEquals(next.bucket().residualMajor(), last.waivedResidualMajor());
    assertEquals(ZERO, day(CAD, last.buckets().getFirst()).earnedMajor());
  }

  @Test
  void fullPayoffDisposesSignedResidualAndStopsAccrualWithoutDeletingHistoricalInterest() {
    var p = day(CAD, purchase(12300)).bucket();
    var payoff =
        repay(CAD, List.of(p, advance(0)), List.of(), List.of(), p.debtMinor(), DAY_MILLIS);
    var cleared = payoff.buckets().getFirst();
    assertEquals(0, cleared.debtMinor());
    assertEquals(ZERO, cleared.residualMajor());
    assertEquals(p.periodEarnedMajor(), cleared.periodEarnedMajor());
    assertEquals(p.residualMajor(), payoff.waivedResidualMajor());
    assertEquals(ZERO, day(CAD, cleared).earnedMajor());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            repay(
                CAD, List.of(p, advance(0)), List.of(), List.of(), p.debtMinor() + 1, DAY_MILLIS));
  }

  @Test
  void statementAttributionAndOverflowFailClosed() {
    var p = purchase(100);
    var s = new DebtSlice("bad", 0, 14 * DAY_MILLIS, Kind.PURCHASE, 101, 0);
    assertThrows(
        IllegalArgumentException.class,
        () -> repay(CAD, List.of(p, advance(0)), List.of(), List.of(s), 1, MONTH_MILLIS));
    assertThrows(ArithmeticException.class, () -> draw(purchase(Long.MAX_VALUE), 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            day(
                CAD,
                new Bucket(
                    Kind.PURCHASE, 1, 0, new BigDecimal("0.006"), ZERO, new BigDecimal("0.1"))));
  }
}
