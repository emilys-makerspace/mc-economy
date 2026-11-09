package ca.emilybelnavis.mcmods.economy.credit;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.clock.FinancialCalendar;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreditScoringTest {
  @TempDir Path world;
  private static final long CYCLE = ScorePolicy.CYCLE_MILLIS;

  private ScorePolicy.Result score(long boundary, boolean onTime, long revision) {
    var area =
        ScorePolicy.accumulate(
            0,
            boundary - CYCLE,
            boundary,
            List.of(new ScorePolicy.Segment(boundary - CYCLE, boundary, 8, 100, true, true)),
            true);
    var report =
        new ScorePolicy.PaymentReport(
            new UUID(0, 1), new UUID(0, 2), CYCLE - 1, CYCLE - 1, revision, onTime, false);
    return ScorePolicy.calculate(
        new ScorePolicy.Input(0, 0, boundary, List.of(report), List.of(), List.of(), area, true));
  }

  private CreditScoring.Request request(long revision, ScorePolicy.Result result) {
    return new CreditScoring.Request(
        revision, 0, result.boundary(), 1, result.boundary(), 7, result.fingerprint());
  }

  private Journal.Outcome submit(
      CreditFixtures f,
      CreditScoring.Request request,
      CreditScoring.ReportSource source,
      Journal.Intent original) {
    var intent =
        original == null ? f.intent(CreditScoring.RECALCULATE, request.request(f.owner)) : original;
    return new JournalEngine(
            f.store,
            (a, k) -> true,
            Map.of(
                CreditScoring.RECALCULATE,
                CreditScoring.futureRegistration(f.owner, request, source)))
        .submit(intent);
  }

  private CreditScoring.ReportSource ready(ScorePolicy.Result result) {
    return (reads, owner, origin, boundary) ->
        Optional.of(
            new CreditScoring.ReportedScore(
                true, FinancialCalendar.Stage.REPORTING, boundary, 7, 0, result));
  }

  @Test
  void productionScoringIsDormantAndMissingReportingCannotCreateHistory() throws Exception {
    try (var f = new CreditFixtures(world, UUID.randomUUID(), true)) {
      var opening = f.initial("crownstone");
      f.issue(opening, f.intent(CreditProfiles.FIRST_ISSUANCE, opening.request()), f.actual());
      var result = score(CYCLE, true, 1);
      var request = request(1, result);
      var intent = f.intent(CreditScoring.RECALCULATE, request.request(f.owner));
      var dormant =
          new JournalEngine(
              f.store,
              (a, k) -> true,
              Map.of(CreditScoring.RECALCULATE, CreditScoring.dormant(f.owner, request)));
      assertEquals(Journal.Status.NOT_ACCEPTED, dormant.submit(intent).status());
      assertEquals(650, f.profile().score());
      assertEquals(1, f.count("credit_score_history"));
      var wrongStage =
          (CreditScoring.ReportSource)
              (reads, owner, origin, boundary) ->
                  Optional.of(
                      new CreditScoring.ReportedScore(
                          true, FinancialCalendar.Stage.PAYMENT, boundary, 7, 0, result));
      assertEquals("REPORTING_UNAVAILABLE", submit(f, request, wrongStage, null).result().code());
      var incomplete =
          (CreditScoring.ReportSource)
              (reads, owner, origin, boundary) ->
                  Optional.of(
                      new CreditScoring.ReportedScore(
                          true, FinancialCalendar.Stage.REPORTING, boundary - 1, 7, 0, result));
      assertEquals("REPORTING_UNAVAILABLE", submit(f, request, incomplete, null).result().code());
      var skip = request(1, score(2 * CYCLE, true, 1));
      assertEquals(
          "SCORE_PRIOR_PERIOD_INCOMPLETE",
          submit(f, skip, ready(score(2 * CYCLE, true, 1)), null).result().code());
    }
  }

  @Test
  void fullBaselineHistoryIsIdempotentAndCorrectionsApplyOnlyToLaterPeriods() throws Exception {
    UUID actor = UUID.randomUUID();
    try (var f = new CreditFixtures(world, actor, true)) {
      var opening = f.initial("crownstone");
      f.issue(opening, f.intent(CreditProfiles.FIRST_ISSUANCE, opening.request()), f.actual());
      var missed = score(CYCLE, false, 1);
      assertEquals(697, missed.score());
      var request = request(1, missed);
      var intent = f.intent(CreditScoring.RECALCULATE, request.request(f.owner));
      var first = submit(f, request, ready(missed), intent);
      assertEquals(Journal.Status.COMMITTED, first.status());
      assertEquals(first, submit(f, request, ready(missed), intent));
      assertEquals(697, f.profile().score());
      assertEquals(2, f.count("credit_score_history"));
      var correction = score(CYCLE, true, 2);
      assertEquals(
          "SCORE_PERIOD_ALREADY_APPLIED",
          submit(f, request(2, correction), ready(correction), null).result().code());
      var later = score(2 * CYCLE, true, 2);
      assertEquals(718, later.score());
      assertEquals(
          Journal.Status.COMMITTED, submit(f, request(2, later), ready(later), null).status());
      assertEquals(718, f.profile().score());
      assertEquals(3, f.count("credit_score_history"));
      var page = f.store.transaction(tx -> CreditProfiles.history(tx::query, f.owner, 0, 1));
      assertEquals("INITIAL", page.entries().getFirst().kind());
      assertNotNull(page.nextCursor());
      var second =
          f.store.transaction(
              tx -> CreditProfiles.history(tx::query, f.owner, page.nextCursor(), 1));
      assertEquals(697, second.entries().getFirst().score());
      assertEquals(650, second.entries().getFirst().components().baseline());
    }
    try (var f = new CreditFixtures(world, actor, false)) {
      assertEquals(718, f.profile().score());
      var next = score(3 * CYCLE, true, 2);
      var unknown = new CreditScoring.Request(3, 0, 3 * CYCLE, 2, 3 * CYCLE, 7, next.fingerprint());
      assertEquals(
          "SCORE_POLICY_UNAVAILABLE", submit(f, unknown, ready(next), null).result().code());
      assertEquals(3, f.count("credit_score_history"));
      assertEquals(
          Journal.Status.COMMITTED, submit(f, request(3, next), ready(next), null).status());
      assertEquals(720, f.profile().score());
    }
  }

  @Test
  void reportingFrontierCannotMoveBackwardsDuringBacklog() throws Exception {
    try (var f = new CreditFixtures(world, UUID.randomUUID(), true)) {
      var opening = f.initial("crownstone");
      f.issue(opening, f.intent(CreditProfiles.FIRST_ISSUANCE, opening.request()), f.actual());
      var first = score(CYCLE, true, 1);
      var request = new CreditScoring.Request(1, 0, CYCLE, 1, 10 * CYCLE, 7, first.fingerprint());
      var ahead =
          (CreditScoring.ReportSource)
              (reads, owner, origin, boundary) ->
                  Optional.of(
                      new CreditScoring.ReportedScore(
                          true, FinancialCalendar.Stage.REPORTING, 10 * CYCLE, 7, 0, first));
      assertEquals(Journal.Status.COMMITTED, submit(f, request, ahead, null).status());
      var next = score(2 * CYCLE, true, 1);
      assertEquals(
          "REPORTING_FRONTIER_STALE",
          submit(f, request(2, next), ready(next), null).result().code());
      assertEquals(10 * CYCLE, f.profile().reportingFrontier());
      assertEquals(2, f.count("credit_score_history"));
    }
  }

  @Test
  void issuanceAtAWorldBoundaryKeeps650UntilTheNextBoundary() throws Exception {
    try (var f = new CreditFixtures(world, UUID.randomUUID(), true)) {
      f.time.set(CYCLE);
      var opening = f.initial("crownstone");
      f.issue(opening, f.intent(CreditProfiles.FIRST_ISSUANCE, opening.request()), f.actual());
      var result = score(CYCLE, true, 1);
      assertEquals(
          "SCORE_BOUNDARY", submit(f, request(1, result), ready(result), null).result().code());
      assertEquals(650, f.profile().score());
      assertEquals(CYCLE, f.profile().firstIssuedAt());
      assertEquals(1, f.count("credit_score_history"));
    }
  }
}
