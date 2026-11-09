package ca.emilybelnavis.mcmods.economy.credit;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class ScorePolicyTest {
  private static final long DAY = ScorePolicy.DAY_MILLIS, CYCLE = ScorePolicy.CYCLE_MILLIS;
  private final UUID facility = UUID.randomUUID(), obligation = UUID.randomUUID();

  private ScorePolicy.Utilization utilization(
      long boundary, long owed, long limit, boolean active) {
    return ScorePolicy.accumulate(
        0,
        boundary - CYCLE,
        boundary,
        List.of(new ScorePolicy.Segment(boundary - CYCLE, boundary, owed, limit, active, true)),
        true);
  }

  private ScorePolicy.Input input(
      long boundary,
      List<ScorePolicy.PaymentReport> payments,
      List<ScorePolicy.DefaultReport> defaults,
      List<ScorePolicy.Inquiry> inquiries,
      ScorePolicy.Utilization utilization) {
    return new ScorePolicy.Input(0, 0, boundary, payments, defaults, inquiries, utilization, true);
  }

  @Test
  void approvedExamplesRecomputeFromBaselineRatherThanAddingDeltas() {
    var paid = new ScorePolicy.PaymentReport(obligation, facility, CYCLE, CYCLE, 1, true, false);
    var missed = new ScorePolicy.PaymentReport(obligation, facility, CYCLE, CYCLE, 1, false, false);
    var good =
        ScorePolicy.calculate(
            input(CYCLE, List.of(paid), List.of(), List.of(), utilization(CYCLE, 8, 100, true)));
    assertEquals(716, good.score());
    assertEquals(650, good.components().baseline());
    assertEquals(4, good.components().payment());
    assertEquals(
        697,
        ScorePolicy.calculate(
                input(
                    CYCLE, List.of(missed), List.of(), List.of(), utilization(CYCLE, 8, 100, true)))
            .score());
    var report =
        new ScorePolicy.DefaultReport(
            UUID.randomUUID(), facility, CYCLE - 1, CYCLE, CYCLE, 1, true);
    var bad =
        ScorePolicy.calculate(
            input(
                CYCLE,
                List.of(missed),
                List.of(report),
                List.of(),
                utilization(CYCLE, 0, 0, false)));
    assertEquals(532, bad.score());
    assertEquals(0, bad.components().payment());
    assertEquals(120, bad.components().defaults());
    assertEquals(
        bad,
        ScorePolicy.calculate(
            input(
                CYCLE,
                List.of(missed),
                List.of(report),
                List.of(),
                utilization(CYCLE, 0, 0, false))));
    assertEquals(ScorePolicy.hash(good.snapshot()), good.fingerprint());
  }

  @Test
  void compactPagedSummariesUseTheIdenticalPolicyFormulaAndSnapshot() {
    var paid = new ScorePolicy.PaymentReport(obligation, facility, CYCLE, CYCLE, 1, true, false);
    var missed = new ScorePolicy.PaymentReport(obligation, facility, CYCLE, CYCLE, 1, false, false);
    var defaulted =
        new ScorePolicy.DefaultReport(
            UUID.randomUUID(), facility, CYCLE - 1, CYCLE, CYCLE, 1, true);
    var goodArea = utilization(CYCLE, 8, 100, true);
    var none = utilization(CYCLE, 0, 0, false);
    var rawGood =
        ScorePolicy.calculate(input(CYCLE, List.of(paid), List.of(), List.of(), goodArea));
    var rawMiss =
        ScorePolicy.calculate(input(CYCLE, List.of(missed), List.of(), List.of(), goodArea));
    var rawDefault =
        ScorePolicy.calculate(input(CYCLE, List.of(missed), List.of(defaulted), List.of(), none));
    var summaryGood =
        ScorePolicy.calculate(summary(CYCLE, 1, 0, 0, 0, 0, 0, goodArea, sourceDigest(rawGood)));
    var summaryMiss =
        ScorePolicy.calculate(summary(CYCLE, 0, 1, 0, 0, 0, 0, goodArea, sourceDigest(rawMiss)));
    var summaryDefault =
        ScorePolicy.calculate(summary(CYCLE, 0, 0, 1, 1, 0, 0, none, sourceDigest(rawDefault)));
    assertEquals(716, summaryGood.score());
    assertEquals(697, summaryMiss.score());
    assertEquals(532, summaryDefault.score());
    assertEquals(rawGood, summaryGood);
    assertEquals(rawMiss, summaryMiss);
    assertEquals(rawDefault, summaryDefault);
  }

  @Test
  void compactSummariesSupportLargePortfoliosWithoutOverflowOrRawHistoryScans() {
    var none = utilization(CYCLE, 0, 0, false);
    var large =
        ScorePolicy.calculate(
            summary(
                CYCLE,
                100_000,
                100_000,
                100_000,
                100_000,
                100_000,
                100_000,
                none,
                ScorePolicy.hash("paged-100000-inputs")));
    assertTrue(large.available());
    assertEquals(-90, large.components().payment());
    assertEquals(360, large.components().defaults());
    assertEquals(300, large.score());
    var extreme =
        ScorePolicy.calculate(
            summary(
                CYCLE,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                none,
                ScorePolicy.hash("max-long-input-counts")));
    assertTrue(extreme.available());
    assertEquals(large.components(), extreme.components());
    assertTrue(extreme.snapshot().length() < 8192);
    var hugePaid =
        ScorePolicy.calculate(
            summary(CYCLE, Long.MAX_VALUE, 0, 0, 0, 0, 0, none, ScorePolicy.hash("huge-paid")));
    assertEquals(100, hugePaid.components().payment());
    assertFalse(ScorePolicy.calculate(summary(CYCLE, 0, 0, 0, 0, 0, 0, none, "")).available());
    var wrongPeriod =
        new ScorePolicy.Utilization(
            DAY, CYCLE, BigInteger.ZERO, BigInteger.ONE, 0, true, "period-source");
    assertFalse(
        ScorePolicy.calculate(
                summary(CYCLE, 0, 0, 0, 0, 0, 0, wrongPeriod, ScorePolicy.hash("missing-coverage")))
            .available());
    var duplicatedBuckets =
        new ScorePolicy.SummaryInput(
            0,
            0,
            CYCLE,
            0,
            0,
            0,
            0,
            0,
            0,
            List.of(1L, 1L),
            none,
            true,
            ScorePolicy.hash("duplicate-inquiry-bucket"));
    assertFalse(ScorePolicy.calculate(duplicatedBuckets).available());
  }

  private static String sourceDigest(ScorePolicy.Result result) {
    var fields = result.snapshot().split("\\|", -1);
    return fields[fields.length - 1];
  }

  private static ScorePolicy.SummaryInput summary(
      long boundary,
      long met,
      long missed,
      long suppressed,
      long young,
      long middle,
      long old,
      ScorePolicy.Utilization utilization,
      String sourceDigest) {
    return new ScorePolicy.SummaryInput(
        0,
        0,
        boundary,
        met,
        missed,
        suppressed,
        young,
        middle,
        old,
        List.of(),
        utilization,
        true,
        sourceDigest);
  }

  @Test
  void utilizationThresholdsAreExactAndNoActiveIsDifferentFromZeroDebt() {
    long[] owed = {0, 1, 10, 11, 30, 31, 50, 51, 75, 76, 100, 101};
    int[] points = {30, 60, 60, 30, 30, 0, 0, -30, -30, -60, -60, -90};
    for (int i = 0; i < owed.length; i++)
      assertEquals(
          points[i],
          ScorePolicy.utilizationPoints(utilization(CYCLE, owed[i], 100, true)),
          "owed " + owed[i]);
    assertEquals(0, ScorePolicy.utilizationPoints(utilization(CYCLE, 0, 0, false)));
    var exact =
        new ScorePolicy.Utilization(
            0,
            CYCLE,
            BigInteger.valueOf(CYCLE).multiply(BigInteger.valueOf(100_000_001)),
            BigInteger.valueOf(1_000_000_000),
            CYCLE,
            true,
            "captured");
    assertEquals(30, ScorePolicy.utilizationPoints(exact));
  }

  @Test
  void eventTimeWeightsTheLastSecondInsteadOfTreatingCutoffAsEntireDay() {
    var area =
        ScorePolicy.accumulate(
            0,
            0,
            CYCLE,
            List.of(
                new ScorePolicy.Segment(0, CYCLE - 1000, 0, 100, true, true),
                new ScorePolicy.Segment(CYCLE - 1000, CYCLE, 100, 100, true, true)),
            true);
    assertEquals(CYCLE, area.activeMillis());
    assertEquals(BigInteger.valueOf(1000), area.areaNumerator());
    assertEquals(60, ScorePolicy.utilizationPoints(area));
    var partial =
        ScorePolicy.accumulate(
            0,
            0,
            CYCLE,
            List.of(
                new ScorePolicy.Segment(0, CYCLE - 1000, 0, 0, false, true),
                new ScorePolicy.Segment(CYCLE - 1000, CYCLE, 100, 100, true, true)),
            true);
    assertEquals(1000, partial.activeMillis());
    assertEquals(-60, ScorePolicy.utilizationPoints(partial));
  }

  @Test
  void dailyAreasPreservePartialCoverageAndRecombineExactRatios() {
    long start = 1000, end = CYCLE + 1000;
    var segments =
        List.of(
            new ScorePolicy.Segment(start, CYCLE, 8, 100, true, true, "fx-publication-A"),
            new ScorePolicy.Segment(CYCLE, end, 9, 100, true, true, "fx-publication-B"));
    var daily = ScorePolicy.dailyAreas(0, start, end, segments, true);
    assertEquals(29, daily.size());
    assertEquals(DAY - 1000, daily.getFirst().coverageMillis());
    assertEquals(1000, daily.getLast().coverageMillis());
    var original = ScorePolicy.accumulate(0, start, end, segments, true);
    var combined = ScorePolicy.combineDaily(start, end, daily, true);
    assertEquals(original.areaNumerator(), combined.areaNumerator());
    assertEquals(original.areaDenominator(), combined.areaDenominator());
    assertEquals(original.activeMillis(), combined.activeMillis());
    assertThrows(
        IllegalArgumentException.class,
        () -> ScorePolicy.combineDaily(start, end, daily.subList(1, daily.size()), true));
  }

  @Test
  void missingSnapshotsAndUnboundedIntervalsNeverBecomeZeroUtilization() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ScorePolicy.accumulate(
                0,
                0,
                Long.MAX_VALUE,
                List.of(new ScorePolicy.Segment(0, Long.MAX_VALUE, 1, 1, true, true)),
                true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ScorePolicy.accumulate(
                0, 0, CYCLE, List.of(new ScorePolicy.Segment(1, CYCLE, 1, 1, true, true)), true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ScorePolicy.Utilization(
                0, CYCLE, BigInteger.ZERO, BigInteger.ONE, CYCLE, true, " "));
    var incomplete =
        ScorePolicy.accumulate(
            0, 0, CYCLE, List.of(new ScorePolicy.Segment(0, CYCLE, 0, 100, true, false)), true);
    assertFalse(
        ScorePolicy.calculate(input(CYCLE, List.of(), List.of(), List.of(), incomplete))
            .available());
    var newborn =
        new ScorePolicy.Input(
            0,
            CYCLE - 1,
            CYCLE,
            List.of(),
            List.of(),
            List.of(),
            utilization(CYCLE, 0, 100, true),
            true);
    assertFalse(ScorePolicy.calculate(newborn).available());
    assertFalse(
        ScorePolicy.calculate(
                new ScorePolicy.Input(
                    0,
                    0,
                    CYCLE,
                    List.of(),
                    List.of(),
                    List.of(),
                    utilization(CYCLE, 0, 0, false),
                    false))
            .available());
    assertFalse(
        ScorePolicy.calculate(
                2, input(CYCLE, List.of(), List.of(), List.of(), utilization(CYCLE, 0, 0, false)))
            .available());
  }

  @Test
  void correctionsPreserveMissedSnapshotOnLaterCureButRepairWrongData() {
    var initial =
        new ScorePolicy.PaymentReport(obligation, facility, CYCLE, CYCLE, 1, false, false);
    var cure = new ScorePolicy.PaymentReport(obligation, facility, CYCLE, 2 * CYCLE, 2, true, true);
    var cured =
        ScorePolicy.calculate(
            input(
                2 * CYCLE,
                List.of(initial, cure),
                List.of(),
                List.of(),
                utilization(2 * CYCLE, 0, 0, false)));
    assertEquals(-15, cured.components().payment());
    var corrected =
        new ScorePolicy.PaymentReport(obligation, facility, CYCLE, 2 * CYCLE, 3, true, false);
    var result =
        ScorePolicy.calculate(
            input(
                2 * CYCLE,
                List.of(corrected, initial, cure),
                List.of(),
                List.of(),
                utilization(2 * CYCLE, 0, 0, false)));
    assertEquals(4, result.components().payment());
    var future =
        new ScorePolicy.PaymentReport(obligation, facility, CYCLE, 2 * CYCLE + 1, 4, false, false);
    assertEquals(
        result,
        ScorePolicy.calculate(
            input(
                2 * CYCLE,
                List.of(future, cure, corrected, initial),
                List.of(),
                List.of(),
                utilization(2 * CYCLE, 0, 0, false))));
    var conflict =
        new ScorePolicy.PaymentReport(obligation, facility, CYCLE, CYCLE, 1, true, false);
    assertFalse(
        ScorePolicy.calculate(
                input(
                    2 * CYCLE,
                    List.of(initial, conflict),
                    List.of(),
                    List.of(),
                    utilization(2 * CYCLE, 0, 0, false)))
            .available());
  }

  @Test
  void defaultBandsExpireAtOriginalDateAndSuppressOnlyTheirFacility() {
    long boundary = 2800 * DAY;
    long[] ages = {364 * DAY - 1, 364 * DAY, 728 * DAY - 1, 728 * DAY, 2548 * DAY - 1, 2548 * DAY};
    int[] penalties = {120, 90, 90, 60, 60, 0};
    for (int i = 0; i < ages.length; i++) {
      var report =
          new ScorePolicy.DefaultReport(
              UUID.randomUUID(),
              facility,
              boundary - ages[i],
              boundary - ages[i],
              boundary - ages[i],
              1,
              true);
      var payment =
          new ScorePolicy.PaymentReport(obligation, facility, boundary, boundary, 1, false, false);
      var result =
          ScorePolicy.calculate(
              input(
                  boundary,
                  List.of(payment),
                  List.of(report),
                  List.of(),
                  utilization(boundary, 0, 0, false)));
      assertEquals(penalties[i], result.components().defaults());
      assertEquals(penalties[i] == 0 ? -15 : 0, result.components().payment());
    }
  }

  @Test
  void inquiriesGroupAcceptedBusinessDecisionsByWorldWeekAndExcludeFirstIssuance() {
    long boundary = 112 * DAY;
    var inquiries =
        List.of(
            new ScorePolicy.Inquiry(UUID.randomUUID(), 28 * DAY, true, false),
            new ScorePolicy.Inquiry(UUID.randomUUID(), 28 * DAY + 1, true, false),
            new ScorePolicy.Inquiry(UUID.randomUUID(), 29 * DAY, true, false),
            new ScorePolicy.Inquiry(UUID.randomUUID(), 35 * DAY + 1, true, false),
            new ScorePolicy.Inquiry(UUID.randomUUID(), 42 * DAY, true, true),
            new ScorePolicy.Inquiry(UUID.randomUUID(), 49 * DAY, false, false));
    assertEquals(
        10,
        ScorePolicy.calculate(
                input(
                    boundary, List.of(), List.of(), inquiries, utilization(boundary, 0, 0, false)))
            .components()
            .inquiry());
    var repeated = new ArrayList<>(inquiries);
    repeated.add(inquiries.get(1));
    Collections.reverse(repeated);
    assertEquals(
        ScorePolicy.calculate(
            input(boundary, List.of(), List.of(), inquiries, utilization(boundary, 0, 0, false))),
        ScorePolicy.calculate(
            input(boundary, List.of(), List.of(), repeated, utilization(boundary, 0, 0, false))));
  }

  @Test
  void boundsAndPaymentWindowUseFixedBaselineWithSaturatedComponents() {
    long boundary = 1260 * DAY;
    var paid = new ArrayList<ScorePolicy.PaymentReport>();
    for (int i = 0; i < 30; i++)
      paid.add(
          new ScorePolicy.PaymentReport(
              UUID.randomUUID(), facility, boundary, boundary, 1, true, false));
    var maximum =
        ScorePolicy.calculate(
            input(boundary, paid, List.of(), List.of(), utilization(boundary, 8, 100, true)));
    assertEquals(900, maximum.score());
    assertEquals(100, maximum.components().payment());
    assertEquals(90, maximum.components().age());
    var missed = new ArrayList<ScorePolicy.PaymentReport>();
    var defaults = new ArrayList<ScorePolicy.DefaultReport>();
    var inquiries = new ArrayList<ScorePolicy.Inquiry>();
    for (int i = 0; i < 20; i++)
      missed.add(
          new ScorePolicy.PaymentReport(
              UUID.randomUUID(), facility, boundary, boundary, 1, false, false));
    for (int i = 0; i < 4; i++)
      defaults.add(
          new ScorePolicy.DefaultReport(
              UUID.randomUUID(), UUID.randomUUID(), boundary - 1, boundary, boundary, 1, true));
    for (int i = 0; i < 7; i++)
      inquiries.add(
          new ScorePolicy.Inquiry(UUID.randomUUID(), boundary - i * 7 * DAY, true, false));
    var minimum =
        ScorePolicy.calculate(
            input(boundary, missed, defaults, inquiries, utilization(boundary, 101, 100, true)));
    assertEquals(300, minimum.score());
    assertEquals(360, minimum.components().defaults());
    assertEquals(30, minimum.components().inquiry());
    var old =
        new ScorePolicy.PaymentReport(
            obligation, facility, boundary - 364 * DAY, boundary - 364 * DAY, 1, true, false);
    assertEquals(
        0,
        ScorePolicy.calculate(
                input(
                    boundary,
                    List.of(old),
                    List.of(),
                    List.of(),
                    utilization(boundary, 0, 0, false)))
            .components()
            .payment());
  }
}
