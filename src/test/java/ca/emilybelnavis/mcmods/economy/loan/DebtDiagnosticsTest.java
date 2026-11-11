package ca.emilybelnavis.mcmods.economy.loan;

import static ca.emilybelnavis.mcmods.economy.debt.DebtRecords.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.clock.DeadlineDispatcher;
import ca.emilybelnavis.mcmods.economy.credit.CreditScoring;
import ca.emilybelnavis.mcmods.economy.credit.ScorePolicy;
import ca.emilybelnavis.mcmods.economy.debt.*;
import ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.LedgerProjection;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebtDiagnosticsTest {
  @TempDir Path path;

  @Test
  void originalObligationsExplainArrearsAndDormantWorldAlignedScoreBoundary() throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      var loan = fixture.originate();
      var dispatcher = scheduler(fixture);
      long now = loan.nextMonth() + DAY;
      catchup(fixture, dispatcher, now);
      var view =
          fixture.store.transaction(
              tx ->
                  DebtDiagnostics.inspect(
                          tx::query, fixture.owner, Kind.LOAN, loan.id(), now, null, 1)
                      .orElseThrow());
      assertEquals(loan.nextMonth(), view.oldestUnmetDueAt());
      assertEquals(loan.nextMonth() + DEFAULT_DELAY, view.defaultDueAt());
      assertTrue(view.debt().isEmpty());
      var obligation = view.obligations().getFirst();
      assertEquals(DAY, obligation.arrearsAgeMillis());
      assertEquals("ARREARS_REPORT_PENDING", obligation.reason());
      assertEquals(loan.nextMonth() + 2 * DAY, obligation.reportDueAt());
      assertEquals(loan.anchor() + 2 * ScorePolicy.CYCLE_MILLIS, view.nextScoreBoundary());
      assertEquals(CreditScoring.PRODUCTION_STATUS, view.scoreStatus());
      assertTrue(view.reconciliation().receivableMatches());
      assertTrue(view.reconciliation().liabilityMatches());
      assertTrue(view.reconciliation().facilityMatches());
      assertTrue(
          fixture
              .store
              .transaction(
                  tx ->
                      DebtDiagnostics.inspect(
                          tx::query, stranger(fixture), Kind.LOAN, loan.id(), now, null, 1))
              .isEmpty());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.store.transaction(
                  tx ->
                      DebtDiagnostics.inspect(
                          tx::query, fixture.owner, Kind.LOAN, loan.id(), now, null, 17)));
    }
  }

  @Test
  void reportsPageImmutableMissedSnapshotCureAndEffectiveCorrectionWithoutDoubleCounting()
      throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      var loan = fixture.originate();
      var dispatcher = scheduler(fixture);
      long reportAt = loan.nextMonth() + 2 * DAY;
      catchup(fixture, dispatcher, reportAt);
      var original = fixture.get(loan.id()).servicing().requirements().getFirst();
      fixture.clock.incrementAndGet();
      var quote =
          fixture.store.transaction(
              tx ->
                  Loans.quote(
                      tx::query,
                      fixture.owner,
                      loan.id(),
                      fixture.account,
                      original.unmetMinor(),
                      false,
                      fixture.clock.get()));
      assertEquals(
          "LOAN_REPAID",
          fixture
              .submit(
                  Loans.REPAY,
                  Loans.repayment(fixture.owner, quote, fixture.clock::get),
                  fixture.intent(Loans.REPAY, quote.request().request()))
              .result()
              .code());
      long frontier = fixture.store.lastCommittedSequence();
      var first =
          fixture.store.transaction(
              tx ->
                  DebtDiagnostics.ownerReports(
                      tx::query,
                      fixture.owner,
                      DebtLifecycle.ReportCursor.start(),
                      1,
                      fixture.clock.get()));
      assertEquals(1, first.entries().size());
      assertTrue(first.entries().getFirst().visible());
      assertEquals("ORIGINAL_OBLIGATION_MISSED_AT_REPORT", first.entries().getFirst().reason());
      assertNotNull(first.nextCursor());
      var second =
          fixture.store.transaction(
              tx ->
                  DebtDiagnostics.ownerReports(
                      tx::query, fixture.owner, first.nextCursor(), 1, fixture.clock.get()));
      var cure = second.entries().getFirst();
      assertTrue(cure.report().cured());
      assertFalse(cure.report().metAtSnapshot());
      assertFalse(cure.visible());
      assertEquals("LATER_CURE_ORIGINAL_MISSED_SNAPSHOT_RETAINED", cure.reason());
      assertNull(second.nextCursor());
      assertEquals(frontier, fixture.store.lastCommittedSequence());
      assertTrue(
          fixture
              .store
              .transaction(
                  tx ->
                      DebtDiagnostics.ownerReports(
                          tx::query,
                          stranger(fixture),
                          DebtLifecycle.ReportCursor.start(),
                          16,
                          fixture.clock.get()))
              .entries()
              .isEmpty());

      // A fixture-only correction exercises compatibility without adding a production correction
      // operation.
      var originalReport = first.entries().getFirst().report();
      long correctedAt = fixture.clock.get() + 1;
      var correction =
          new Report(
              originalReport.world(),
              originalReport.id(),
              originalReport.kind(),
              originalReport.facility(),
              originalReport.owner(),
              originalReport.obligation(),
              originalReport.dueAt(),
              originalReport.reportAt(),
              true,
              false,
              originalReport.originalMinor(),
              0,
              3,
              correctedAt);
      fixture.store.transaction(
          tx -> {
            DebtRecords.write(tx, correction);
            return null;
          });
      var before =
          fixture.store.transaction(
              tx ->
                  DebtDiagnostics.ownerReports(
                      tx::query,
                      fixture.owner,
                      DebtLifecycle.ReportCursor.start(),
                      16,
                      correctedAt - 1));
      assertTrue(before.entries().getFirst().visible());
      assertFalse(before.entries().getLast().visible());
      var after =
          fixture.store.transaction(
              tx ->
                  DebtDiagnostics.ownerReports(
                      tx::query,
                      fixture.owner,
                      DebtLifecycle.ReportCursor.start(),
                      16,
                      correctedAt));
      assertFalse(after.entries().getFirst().visible());
      assertEquals("REPORT_REVISION_SUPERSEDED", after.entries().getFirst().reason());
      assertTrue(after.entries().getLast().visible());
      var expired =
          fixture.store.transaction(
              tx ->
                  DebtDiagnostics.ownerReports(
                      tx::query,
                      fixture.owner,
                      DebtLifecycle.ReportCursor.start(),
                      16,
                      reportAt + 364 * DAY));
      assertTrue(expired.entries().stream().noneMatch(DebtDiagnostics.ReportView::visible));
      assertEquals("PAYMENT_SCORE_WINDOW_EXPIRED", expired.entries().getFirst().reason());
    }
  }

  @Test
  void collectionHistoryPagesSalePaymentAndWriteoffAndReconcilesCurrentCreditor() throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      var loan = fixture.originate();
      var dispatcher = scheduler(fixture);
      long defaultAt = loan.nextMonth() + DEFAULT_DELAY;
      catchup(fixture, dispatcher, defaultAt);
      var debt =
          fixture.store.transaction(
              tx -> DebtLifecycle.facilityCase(tx::query, Kind.LOAN, loan.id()).orElseThrow());
      var atDefault = DebtDiagnostics.caseView(debt, defaultAt);
      assertTrue(atDefault.collectionEligible());
      assertEquals("DEFAULT", atDefault.reportStatus());
      catchup(fixture, dispatcher, defaultAt + DAY);
      var quote =
          fixture.store.transaction(
              tx ->
                  DebtPayments.quote(
                      tx::query,
                      fixture.owner,
                      debt.id(),
                      fixture.account,
                      100,
                      fixture.clock.get()));
      assertEquals(
          "COLLECTION_REPAID",
          fixture
              .submit(
                  DebtPayments.PAY,
                  DebtPayments.registration(fixture.owner, quote, fixture.clock::get),
                  fixture.intent(DebtPayments.PAY, quote.request()))
              .result()
              .code());
      var paid =
          fixture.store.transaction(
              tx -> DebtLifecycle.findCase(tx::query, debt.id()).orElseThrow());
      assertEquals(defaultAt + DAY, paid.acknowledgedAt());
      var boundary = DebtDiagnostics.caseView(paid, paid.collectionExpires());
      assertFalse(boundary.collectionEligible());
      assertEquals("COLLECTION_WINDOW_EXPIRED_TRANSITION_PENDING", boundary.collectionReason());
      catchup(fixture, dispatcher, paid.collectionExpires());
      var view =
          fixture.store.transaction(
              tx ->
                  DebtDiagnostics.inspect(
                          tx::query,
                          fixture.owner,
                          Kind.LOAN,
                          loan.id(),
                          fixture.clock.get(),
                          null,
                          16)
                      .orElseThrow());
      assertEquals("UNCOLLECTABLE", view.debt().orElseThrow().state());
      assertTrue(view.debt().orElseThrow().reportVisible());
      assertTrue(view.reconciliation().receivableMatches());
      assertTrue(view.reconciliation().liabilityMatches());
      assertTrue(view.reconciliation().facilityMatches());
      assertEquals(paid.debtMinor(), view.reconciliation().expectedReceivableMinor());
      assertEquals(-paid.debtMinor(), view.reconciliation().expectedLiabilityMinor());
      assertTrue(
          view.obligations().stream()
              .allMatch(o -> o.reason().equals("REQUIREMENT_CLEARED_UNCOLLECTABLE_DEBT_REMAINS")));
      var kinds = new ArrayList<String>();
      var cursor = DebtDiagnostics.EventCursor.start();
      do {
        var after = cursor;
        var page =
            fixture.store.transaction(
                tx ->
                    DebtDiagnostics.ownerEvents(tx::query, fixture.owner, debt.id(), after, 1)
                        .orElseThrow());
        assertEquals(1, page.entries().size());
        kinds.add(page.entries().getFirst().kind());
        cursor = page.nextCursor();
      } while (cursor != null);
      assertEquals(List.of("SALE", "PAYMENT", "WRITE_OFF"), kinds);
      assertTrue(
          fixture
              .store
              .transaction(
                  tx ->
                      DebtDiagnostics.ownerEvents(
                          tx::query,
                          stranger(fixture),
                          debt.id(),
                          DebtDiagnostics.EventCursor.start(),
                          16))
              .isEmpty());
      var invisible = DebtDiagnostics.caseView(paid, paid.reportExpires());
      assertFalse(invisible.reportVisible());
      assertEquals("DEFAULT_REPORT_EXPIRED", invisible.reportStatus());

      var foreign =
          new DebtRecords.Case(
              debt.world(),
              debt.id(),
              debt.kind(),
              debt.facility(),
              new PlayerId(UUID.randomUUID()),
              debt.origin(),
              debt.creditor(),
              debt.currency(),
              debt.principalMinor(),
              debt.interestMinor(),
              debt.defaultAt(),
              debt.acknowledgedAt(),
              debt.collectionExpires(),
              debt.reportExpires(),
              debt.sale(),
              debt.collectability(),
              debt.payment(),
              debt.discountRemainingMinor(),
              debt.revision());
      var kind = new Journal.Kind("fixture:debt", "fixture:foreign-event");
      fixture.submit(
          kind,
          new JournalEngine.Registration(
              1,
              (intent, reads) ->
                  new JournalEngine.Commit(
                      List.of(
                          new Journal.Record(
                              DebtFinance.ACCOUNTING_FACT,
                              1,
                              debt.world().value()
                                  + "|"
                                  + debt.id()
                                  + "|PAYMENT|"
                                  + fixture.clock.get()
                                  + "|1|0"),
                          DebtRecords.fact(foreign)),
                      new Journal.Result(1, "FIXTURE", ""),
                      tx -> {})),
          fixture.intent(kind, new Journal.CanonicalRequest(1, Map.of())));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.store.transaction(
                  tx ->
                      DebtDiagnostics.ownerEvents(
                          tx::query,
                          fixture.owner,
                          debt.id(),
                          DebtDiagnostics.EventCursor.start(),
                          16)));
    }
  }

  @Test
  void boundedCasePagesKeepSettledUncollectableAndPendingSaleReasonsDistinct() throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      var loan = fixture.originate();
      var collectorCash =
          FinancialAccounts.id(
              fixture.owner.world(),
              InstitutionCatalog.WAYSTONE,
              FinancialAccounts.Role.CASH,
              EconomyCurrency.CAD);
      fixture.store.transaction(
          tx -> {
            LedgerProjection.write(tx, collectorCash, EconomyCurrency.CAD, 4_000_000_000L);
            return null;
          });
      var dispatcher = scheduler(fixture);
      long defaultAt = loan.nextMonth() + DEFAULT_DELAY;
      catchup(fixture, dispatcher, defaultAt);
      var pending =
          fixture.store.transaction(
              tx ->
                  DebtDiagnostics.inspect(
                          tx::query, fixture.owner, Kind.LOAN, loan.id(), defaultAt, null, 16)
                      .orElseThrow());
      assertEquals("CREDIT_OPERATING_FLOOR", pending.saleReason());
      assertEquals(defaultAt + DAY, pending.nextSaleAttemptAt());
      assertTrue(pending.debt().orElseThrow().collectionEligible());
      assertEquals(
          "ORIGIN_CREDITOR_COLLECTION_ELIGIBLE_SALE_PENDING",
          pending.debt().orElseThrow().collectionReason());
      for (int index = 0; index < 16; index++) {
        var extra =
            new DebtRecords.Case(
                fixture.owner.world(),
                UUID.randomUUID(),
                Kind.LOAN,
                UUID.randomUUID(),
                fixture.actor,
                LoanTestFixture.BANK,
                LoanTestFixture.BANK,
                EconomyCurrency.CAD,
                0,
                0,
                defaultAt,
                defaultAt,
                defaultAt + COLLECTION_WINDOW,
                defaultAt + REPORT_WINDOW,
                Sale.PENDING,
                Collectability.COLLECTIBLE,
                Payment.SETTLED,
                0,
                1);
        fixture.store.transaction(
            tx -> {
              DebtRecords.write(tx, extra);
              return null;
            });
        assertEquals("DEBT_SETTLED", DebtDiagnostics.caseView(extra, defaultAt).collectionReason());
        assertEquals("PAID_DEFAULT", DebtDiagnostics.caseView(extra, defaultAt).reportStatus());
      }
      var first =
          fixture.store.transaction(
              tx -> DebtDiagnostics.ownerCases(tx::query, fixture.owner, null, 16, defaultAt));
      assertEquals(16, first.entries().size());
      assertNotNull(first.nextCursor());
      var second =
          fixture.store.transaction(
              tx ->
                  DebtDiagnostics.ownerCases(
                      tx::query, fixture.owner, first.nextCursor(), 16, defaultAt));
      assertEquals(1, second.entries().size());
      assertNull(second.nextCursor());
      assertTrue(
          fixture
              .store
              .transaction(
                  tx ->
                      DebtDiagnostics.ownerCases(tx::query, stranger(fixture), null, 16, defaultAt))
              .entries()
              .isEmpty());
    }
  }

  private static PartyRef stranger(LoanTestFixture fixture) {
    return new PartyRef(
        fixture.owner.world(), new PartyRef.Player(new PlayerId(UUID.randomUUID())));
  }

  private static DeadlineDispatcher scheduler(LoanTestFixture fixture) {
    var activation = new Journal.Kind("fixture:debt", "fixture:activation");
    fixture.submit(
        activation,
        new JournalEngine.Registration(
            1,
            (intent, reads) ->
                new JournalEngine.Commit(
                    List.of(
                        new Journal.Record(
                            DeadlineDispatcher.ACTIVATION, 1, "1|" + fixture.clock.get())),
                    new Journal.Result(1, "FIXTURE", ""),
                    tx -> {})),
        fixture.intent(activation, new Journal.CanonicalRequest(1, Map.of())));
    var providers = new ArrayList<>(DebtRuntime.providers());
    providers.add(LoanRuntime.provider());
    var wall =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId zone) {
            return this;
          }

          public Instant instant() {
            return Instant.ofEpochMilli(fixture.clock.get());
          }
        };
    var dispatcher = new DeadlineDispatcher(fixture.store, wall, providers, 1);
    dispatcher.prepare();
    return dispatcher;
  }

  private static void catchup(LoanTestFixture fixture, DeadlineDispatcher dispatcher, long at) {
    fixture.clock.set(at);
    int turns = 0;
    while (!dispatcher.progress().ready() && turns++ < 1024) dispatcher.advance();
    assertTrue(dispatcher.progress().ready(), "Diagnostics fixture catch-up must finish");
  }
}
