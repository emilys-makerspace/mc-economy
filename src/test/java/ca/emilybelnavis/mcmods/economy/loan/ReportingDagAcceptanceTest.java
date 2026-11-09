package ca.emilybelnavis.mcmods.economy.loan;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.debt.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportingDagAcceptanceTest {
  @TempDir Path path;

  @Test
  void realScoreRunsAfterSameInstantPaymentAndReportBeforeTierAndResumesDuringFold()
      throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      long origin = fixture.clock.get();
      DebtAcceptanceTest.activate(fixture);
      fixture.clock.set(origin + 26 * DebtRecords.DAY);
      var loan = fixture.originate();
      long boundary = origin + 2 * ScorePolicy.CYCLE_MILLIS;
      assertEquals(boundary, loan.nextMonth() + 2 * DebtRecords.DAY);
      for (int page = 0; !CreditReporting.bootstrap(fixture.store); page++) assertTrue(page < 32);
      assertTrue(CreditScoreRuntime.bootstrap(fixture.store));
      var providers = new ArrayList<>(DebtRuntime.providers());
      providers.add(LoanRuntime.provider());
      providers.addAll(CreditScoreRuntime.providers());
      providers.add(
          new DeadlineDispatcher.Provider() {
            public String id() {
              return "fixture:report-payment";
            }

            public Map<Integer, String> versions() {
              return Map.of(1, "fixture-v1");
            }

            public DeadlineDispatcher.Event next(
                EconomicClock.Queries r, long o, DeadlineDispatcher.Event completed) {
              return completed == null
                  ? new DeadlineDispatcher.Event(DebtLifecycle.stable(id()), id(), boundary, 3, 1)
                  : null;
            }

            public List<String> targets(
                DeadlineDispatcher.Event e, String after, int limit, EconomicClock.Queries r) {
              return after.isEmpty() ? List.of(loan.id().toString()) : List.of();
            }

            public EconomicClock.Output prepare(
                DeadlineDispatcher.Event e, String target, EconomicClock.Queries queries) {
              JournalEngine.ReadView reads = queries::query;
              var current = Loans.find(reads, loan.id()).orElseThrow();
              long amount = current.servicing().requiredMinor();
              try {
                var quote =
                    Loans.quote(
                        reads, fixture.owner, loan.id(), fixture.account, amount, false, boundary);
                var decision =
                    Loans.repay(
                        reads,
                        current,
                        quote,
                        boundary,
                        List.of(
                            new PostingBatch.Leg(
                                fixture.account.value(),
                                PostingBatch.Role.ACCOUNT,
                                EconomyCurrency.CAD,
                                new Money(EconomyCurrency.CAD, -amount))),
                        List.of());
                assertInstanceOf(JournalEngine.Commit.class, decision);
                var commit = (JournalEngine.Commit) decision;
                return new EconomicClock.Output(target, commit.projections(), commit.records());
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            }
          });
      providers.add(
          new DeadlineDispatcher.Provider() {
            public String id() {
              return "fixture:report-tier";
            }

            public Map<Integer, String> versions() {
              return Map.of(1, "fixture-v1");
            }

            public DeadlineDispatcher.Event next(
                EconomicClock.Queries r, long o, DeadlineDispatcher.Event completed) {
              return completed == null
                  ? new DeadlineDispatcher.Event(DebtLifecycle.stable(id()), id(), boundary, 7, 1)
                  : null;
            }

            public List<String> targets(
                DeadlineDispatcher.Event e, String after, int limit, EconomicClock.Queries r) {
              return after.isEmpty() ? List.of(loan.id().toString()) : List.of();
            }

            public EconomicClock.Output prepare(
                DeadlineDispatcher.Event e, String target, EconomicClock.Queries queries) {
              var profile = CreditProfiles.find(queries::query, fixture.owner).orElseThrow();
              assertEquals(boundary, profile.lastBoundary());
              assertEquals(653, profile.score());
              return new EconomicClock.Output(
                  target,
                  tx -> {},
                  List.of(
                      new Journal.Record(
                          DebtLifecycle.ATTEMPT,
                          1,
                          loan.id() + "|" + boundary + "|TIER_AFTER_SCORE")));
            }
          });
      fixture.clock.set(boundary);
      var wall = Clock.fixed(Instant.ofEpochMilli(boundary), ZoneOffset.UTC);
      var scheduler = new DeadlineDispatcher(fixture.store, wall, providers, 1);
      scheduler.prepare();
      boolean restarted = false;
      int turns = 0;
      while (!scheduler.progress().ready()) {
        assertTrue(++turns < 512);
        scheduler.advance();
        var tasks =
            fixture.store.transaction(
                tx -> tx.query("SELECT step FROM credit_score_tasks LIMIT 1", List.of(), 1));
        if (!restarted && !tasks.isEmpty() && Long.parseLong(tasks.getFirst().get("step")) >= 8) {
          fixture.reopen();
          scheduler = new DeadlineDispatcher(fixture.store, wall, providers, 1);
          scheduler.prepare();
          restarted = true;
        }
      }
      assertTrue(restarted);
      var reports =
          fixture.store.transaction(
              tx ->
                  DebtLifecycle.reports(
                      tx::query, fixture.owner, DebtLifecycle.ReportCursor.start(), 16));
      assertEquals(1, reports.entries().size());
      assertTrue(reports.entries().getFirst().metAtSnapshot());
      var history =
          fixture.store.transaction(tx -> CreditProfiles.history(tx::query, fixture.owner, 0, 16));
      assertEquals(3, history.entries().size());
      assertEquals(653, history.entries().getLast().score());
      assertEquals(4, history.entries().getLast().components().payment());
      long sequence = fixture.store.lastCommittedSequence();
      scheduler.advance();
      assertEquals(sequence, fixture.store.lastCommittedSequence());
    }
  }
}
