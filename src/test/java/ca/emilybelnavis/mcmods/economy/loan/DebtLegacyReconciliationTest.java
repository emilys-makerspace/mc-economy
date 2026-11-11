package ca.emilybelnavis.mcmods.economy.loan;

import static ca.emilybelnavis.mcmods.economy.debt.DebtRecords.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.debt.*;
import ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebtLegacyReconciliationTest {
  @TempDir Path path;

  private Loans.Contract closeMonth(LoanTestFixture fixture) {
    var loan = fixture.originate();
    fixture.clock.set(loan.nextMonth());
    var kind = new Journal.Kind("fixture:legacy", "fixture:month");
    fixture.submit(
        kind,
        new JournalEngine.Registration(
            1, (intent, reads) -> Loans.month(reads, loan.id(), loan.nextMonth())),
        fixture.intent(
            kind,
            new Journal.CanonicalRequest(1, Map.of("effective", Long.toString(loan.nextMonth())))));
    return fixture.get(loan.id());
  }

  private void eraseLoanLifecycle(LoanTestFixture fixture, long target) {
    fixture.store.transaction(
        tx -> {
          for (String table :
              List.of(
                  "debt_reports",
                  "debt_obligations",
                  "debt_due_queue",
                  "debt_default_heads",
                  "debt_facility_states"))
            tx.execute("DELETE FROM " + table + " WHERE kind='LOAN'", List.of());
          tx.execute(
              "UPDATE financial_calendar SET last_effective_ms=?", List.of(Long.toString(target)));
          return null;
        });
  }

  private void reconcile(LoanTestFixture fixture) {
    boolean ready = false;
    for (int step = 0; step < 100 && !ready; step++)
      ready = DebtLegacyReconciliation.step(fixture.store);
    assertTrue(ready);
  }

  @Test
  void replaysRemovedOriginalObligationAndCureWithoutChangingMoneyOrCalendar() throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      var loan = closeMonth(fixture);
      var original = loan.servicing().requirements().getFirst();
      fixture.clock.set(original.dueAt() + 2 * DAY + 1);
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
      var saved = fixture.get(loan.id());
      long cash = fixture.amount(saved.lender(), FinancialAccounts.Role.CASH);
      long target = fixture.clock.get() + DAY;
      eraseLoanLifecycle(fixture, target);
      assertFalse(DebtLegacyReconciliation.step(fixture.store));
      assertFalse(DebtLegacyReconciliation.step(fixture.store));
      fixture.store.recover();
      reconcile(fixture);
      assertEquals(saved, fixture.get(loan.id()));
      assertEquals(cash, fixture.amount(saved.lender(), FinancialAccounts.Role.CASH));
      var obligation =
          fixture.store.transaction(
              tx ->
                  DebtLifecycle.obligation(tx::query, Kind.LOAN, UUID.fromString(original.id()))
                      .orElseThrow());
      assertEquals(original.originalMinor(), obligation.originalMinor());
      assertEquals(original.dueAt(), obligation.dueAt());
      assertEquals(0, obligation.unmetMinor());
      assertTrue(obligation.reported());
      assertFalse(obligation.metAtReport());
      var reports =
          fixture
              .store
              .transaction(
                  tx ->
                      DebtLifecycle.reports(
                          tx::query, fixture.owner, DebtLifecycle.ReportCursor.start(), 16))
              .entries();
      assertEquals(2, reports.size());
      assertFalse(reports.getFirst().cured());
      assertTrue(reports.getLast().cured());
      assertEquals(
          target,
          Long.parseLong(
              fixture
                  .store
                  .transaction(
                      tx ->
                          tx.query(
                              "SELECT last_effective_ms FROM financial_calendar", List.of(), 1))
                  .getFirst()
                  .get("last_effective_ms")));
      long sequence = fixture.store.lastCommittedSequence();
      assertTrue(DebtLegacyReconciliation.step(fixture.store));
      assertEquals(sequence, fixture.store.lastCommittedSequence());
    }
  }

  @Test
  void trackedFacilityWithMissingHistoricalObligationCannotSilentlyScoreAsZero() throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      var saved = closeMonth(fixture);
      fixture.store.transaction(
          tx -> {
            tx.execute("DELETE FROM debt_obligations WHERE kind='LOAN'", List.of());
            return null;
          });
      var failure = assertThrows(RuntimeException.class, () -> reconcile(fixture));
      assertTrue(
          failure.getMessage().contains("DEBT_TRACKED_HISTORY_REQUIRES_RECONCILIATION"),
          failure.toString());
      fixture.store.recover();
      assertEquals(saved, fixture.get(saved.id()));
    }
  }

  @Test
  void rejectsRetrospectiveDefaultWithoutRewritingCoreBalancesOrCalendar() throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      var saved = closeMonth(fixture);
      long target = saved.servicing().requirements().getFirst().dueAt() + DEFAULT_DELAY + 1;
      long cash = fixture.amount(saved.lender(), FinancialAccounts.Role.CASH);
      eraseLoanLifecycle(fixture, target);
      var failure = assertThrows(RuntimeException.class, () -> reconcile(fixture));
      assertTrue(
          failure.getMessage().contains("DEBT_LEGACY_DEFAULT_REQUIRES_ACCOUNTING_RECONCILIATION"),
          failure.toString());
      fixture.store.recover();
      assertEquals(saved, fixture.get(saved.id()));
      assertEquals(cash, fixture.amount(saved.lender(), FinancialAccounts.Role.CASH));
      assertTrue(
          fixture
              .store
              .transaction(tx -> DebtLifecycle.facilityCase(tx::query, Kind.LOAN, saved.id()))
              .isEmpty());
      assertEquals(
          target,
          Long.parseLong(
              fixture
                  .store
                  .transaction(
                      tx ->
                          tx.query(
                              "SELECT last_effective_ms FROM financial_calendar", List.of(), 1))
                  .getFirst()
                  .get("last_effective_ms")));
    }
  }
}
