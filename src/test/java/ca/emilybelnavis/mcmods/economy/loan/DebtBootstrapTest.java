package ca.emilybelnavis.mcmods.economy.loan;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.debt.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebtBootstrapTest {
  @TempDir Path path;

  private Loans.Contract legacyFixture(LoanTestFixture fixture) {
    var loan = fixture.originate();
    var kind = new Journal.Kind("fixture:debt-upgrade", "fixture:month");
    fixture.submit(
        kind,
        new JournalEngine.Registration(1, (i, r) -> Loans.month(r, loan.id(), loan.nextMonth())),
        fixture.intent(
            kind,
            new Journal.CanonicalRequest(1, Map.of("effective", Long.toString(loan.nextMonth())))));
    fixture.store.transaction(
        tx -> {
          for (String table :
              List.of(
                  "debt_obligations",
                  "debt_due_queue",
                  "debt_default_heads",
                  "debt_facility_states"))
            tx.execute("DELETE FROM " + table + " WHERE kind='LOAN'", List.of());
          return null;
        });
    return fixture.get(loan.id());
  }

  @Test
  void boundedBootstrapRetainsOriginalObligationAndDoesNotRepeatFinancialEffects()
      throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      var saved = legacyFixture(fixture);
      long cash =
          fixture.amount(
              saved.lender(),
              ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts.Role.CASH);
      boolean ready = false;
      for (int page = 0; page < 12 && !ready; page++)
        ready = DebtLifecycle.bootstrap(fixture.store);
      assertTrue(ready);
      var original = saved.servicing().requirements().getFirst();
      var obligation =
          fixture.store.transaction(
              tx ->
                  DebtLifecycle.obligation(
                          tx::query, DebtRecords.Kind.LOAN, UUID.fromString(original.id()))
                      .orElseThrow());
      assertEquals(original.dueAt(), obligation.dueAt());
      assertEquals(original.originalMinor(), obligation.originalMinor());
      assertEquals(original.unmetMinor(), obligation.unmetMinor());
      assertEquals(saved, fixture.get(saved.id()));
      assertEquals(
          cash,
          fixture.amount(
              saved.lender(),
              ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts.Role.CASH));
      long frontier = fixture.store.lastCommittedSequence();
      assertTrue(DebtLifecycle.bootstrap(fixture.store));
      assertEquals(frontier, fixture.store.lastCommittedSequence());
    }
  }

  @Test
  void historicalDeadlineBehindFinancialFrontierRequiresReconciliationWithoutBackdating()
      throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      var saved = legacyFixture(fixture);
      fixture.store.transaction(
          tx -> {
            tx.execute(
                "UPDATE financial_calendar SET last_effective_ms=?",
                List.of(Long.toString(saved.servicing().periodStart() + 10)));
            return null;
          });
      assertFalse(DebtLifecycle.bootstrap(fixture.store));
      assertFalse(DebtLifecycle.bootstrap(fixture.store));
      long frontier = fixture.store.lastCommittedSequence();
      assertThrows(RuntimeException.class, () -> DebtLifecycle.bootstrap(fixture.store));
      fixture.store.recover();
      assertEquals(frontier, fixture.store.lastCommittedSequence());
      assertEquals(saved, fixture.get(saved.id()));
      assertTrue(
          fixture
              .store
              .transaction(
                  tx ->
                      DebtLifecycle.obligation(
                          tx::query,
                          DebtRecords.Kind.LOAN,
                          UUID.fromString(saved.servicing().requirements().getFirst().id())))
              .isEmpty());
    }
  }
}
