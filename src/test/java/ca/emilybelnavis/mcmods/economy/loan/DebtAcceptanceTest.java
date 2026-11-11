package ca.emilybelnavis.mcmods.economy.loan;

import static ca.emilybelnavis.mcmods.economy.debt.DebtRecords.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.debt.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exact boundary and real SQLite restart acceptance for offline debtors. */
class DebtAcceptanceTest {
  @TempDir Path path;

  static DeadlineDispatcher scheduler(LoanTestFixture fixture) {
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
    var scheduler = new DeadlineDispatcher(fixture.store, wall, providers, 1);
    scheduler.prepare();
    return scheduler;
  }

  static void activate(LoanTestFixture fixture) {
    var kind = new Journal.Kind("fixture:debt-acceptance", "fixture:activate");
    var commit =
        new JournalEngine.Registration(
            1,
            (i, r) ->
                new JournalEngine.Commit(
                    List.of(
                        new Journal.Record(
                            DeadlineDispatcher.ACTIVATION, 1, "1|" + fixture.clock.get())),
                    new Journal.Result(1, "FIXTURE", ""),
                    tx -> {}));
    fixture.submit(kind, commit, fixture.intent(kind, new Journal.CanonicalRequest(1, Map.of())));
  }

  static void catchup(LoanTestFixture fixture, DeadlineDispatcher scheduler, long at) {
    fixture.clock.set(at);
    int turns = 0;
    while (!scheduler.progress().ready()) {
      assertTrue(++turns < 4096, "Bounded debt catch-up");
      long before = fixture.store.lastCommittedSequence();
      scheduler.advance();
      assertTrue(fixture.store.lastCommittedSequence() - before <= 1);
    }
  }

  private static DebtRecords.Case debt(LoanTestFixture fixture, UUID loan) {
    return fixture.store.transaction(
        tx -> DebtLifecycle.facilityCase(tx::query, Kind.LOAN, loan).orElseThrow());
  }

  private static Journal.Outcome pay(
      LoanTestFixture fixture, DebtRecords.Case debt, long amount, long at) {
    fixture.clock.set(at);
    var quote =
        fixture.store.transaction(
            tx ->
                DebtPayments.quote(
                    tx::query, fixture.owner, debt.id(), fixture.account, amount, at));
    var intent = fixture.intent(DebtPayments.PAY, quote.request());
    var handler = DebtPayments.registration(fixture.owner, quote, fixture.clock::get);
    var outcome = fixture.submit(DebtPayments.PAY, handler, intent);
    assertEquals(outcome, fixture.submit(DebtPayments.PAY, handler, intent));
    return outcome;
  }

  @Test
  void reportAndDefaultTransitionsOccurExactlyOnceAtTheirOriginalBoundaries() throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      var loan = fixture.originate();
      activate(fixture);
      var scheduler = scheduler(fixture);
      long report = loan.nextMonth() + 2 * DAY;
      catchup(fixture, scheduler, report - 1);
      assertTrue(
          fixture
              .store
              .transaction(
                  tx ->
                      DebtLifecycle.reports(
                          tx::query, fixture.owner, DebtLifecycle.ReportCursor.start(), 16))
              .entries()
              .isEmpty());
      catchup(fixture, scheduler, report);
      var reported =
          fixture.store.transaction(
              tx ->
                  DebtLifecycle.reports(
                      tx::query, fixture.owner, DebtLifecycle.ReportCursor.start(), 16));
      assertEquals(1, reported.entries().size());
      assertFalse(reported.entries().getFirst().metAtSnapshot());
      assertEquals(loan.nextMonth(), reported.entries().getFirst().dueAt());
      long frontier = fixture.store.lastCommittedSequence();
      catchup(fixture, scheduler, report + 1);
      assertEquals(frontier, fixture.store.lastCommittedSequence());
      long defaultAt = loan.nextMonth() + DEFAULT_DELAY;
      catchup(fixture, scheduler, defaultAt - 1);
      assertTrue(
          fixture
              .store
              .transaction(tx -> DebtLifecycle.facilityCase(tx::query, Kind.LOAN, loan.id()))
              .isEmpty());
      catchup(fixture, scheduler, defaultAt);
      var original = debt(fixture, loan.id());
      assertEquals(defaultAt, original.defaultAt());
      assertEquals(Sale.SOLD, original.sale());
      frontier = fixture.store.lastCommittedSequence();
      catchup(fixture, scheduler, defaultAt + 1);
      assertEquals(original, debt(fixture, loan.id()));
      fixture.reopen();
      scheduler = scheduler(fixture);
      catchup(fixture, scheduler, defaultAt + 1);
      assertEquals(original, debt(fixture, loan.id()));
    }
  }

  @Test
  void partialNearExpiryFullSettlementAndExpiredOfflineCasesKeepSeparateReportClocksAfterRestart()
      throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      var partialLoan = fixture.originate();
      var paidLoan = fixture.originate();
      var expiredLoan = fixture.originate();
      activate(fixture);
      var scheduler = scheduler(fixture);
      long defaultAt = partialLoan.nextMonth() + DEFAULT_DELAY;
      catchup(fixture, scheduler, defaultAt);
      var partial = debt(fixture, partialLoan.id());
      var paid = debt(fixture, paidLoan.id());
      var expired = debt(fixture, expiredLoan.id());
      long expiry = partial.collectionExpires();
      catchup(fixture, scheduler, expiry - 1);
      assertTrue(DebtLifecycle.demands(partial, expiry - 1));
      assertEquals("COLLECTION_REPAID", pay(fixture, partial, 100, expiry - 1).result().code());
      assertEquals(
          "COLLECTION_SETTLED", pay(fixture, paid, paid.debtMinor(), expiry - 1).result().code());
      var renewed = debt(fixture, partialLoan.id());
      var settled = debt(fixture, paidLoan.id());
      assertEquals(expiry - 1 + COLLECTION_WINDOW, renewed.collectionExpires());
      assertEquals(partial.reportExpires(), renewed.reportExpires());
      assertEquals(Payment.SETTLED, settled.payment());
      assertEquals(paid.acknowledgedAt(), settled.acknowledgedAt());
      var stale =
          fixture.store.transaction(
              tx ->
                  DebtPayments.quote(
                      tx::query, fixture.owner, expired.id(), fixture.account, 100, expiry - 1));
      fixture.clock.set(expiry);
      var rejected =
          fixture.submit(
              DebtPayments.PAY,
              DebtPayments.registration(fixture.owner, stale, fixture.clock::get),
              fixture.intent(DebtPayments.PAY, stale.request()));
      assertNotEquals(Journal.Status.COMMITTED, rejected.status());
      catchup(fixture, scheduler, expiry);
      assertFalse(DebtLifecycle.demands(debt(fixture, expiredLoan.id()), expiry));
      assertEquals(Collectability.UNCOLLECTABLE, debt(fixture, expiredLoan.id()).collectability());
      assertEquals("PAID_DEFAULT", DebtLifecycle.bureauStatus(settled, expiry));
      long liability =
          fixture.amount(
              new InstitutionCatalog.InstitutionId(
                  "economy:loan-borrower/" + fixture.actor.value()),
              FinancialAccounts.Role.LIABILITY);
      fixture.reopen();
      scheduler = scheduler(fixture);
      catchup(fixture, scheduler, renewed.collectionExpires());
      assertEquals(Collectability.UNCOLLECTABLE, debt(fixture, partialLoan.id()).collectability());
      assertEquals(
          liability,
          fixture.amount(
              new InstitutionCatalog.InstitutionId(
                  "economy:loan-borrower/" + fixture.actor.value()),
              FinancialAccounts.Role.LIABILITY));
      for (long at :
          List.of(
              partial.reportExpires() - 1, partial.reportExpires(), partial.reportExpires() + 1)) {
        catchup(fixture, scheduler, at);
        for (var id : List.of(partialLoan.id(), paidLoan.id(), expiredLoan.id())) {
          var state = debt(fixture, id);
          assertEquals(at < state.reportExpires(), DebtLifecycle.reportVisible(state, at));
          assertFalse(DebtLifecycle.demands(state, at));
        }
      }
      assertEquals(Payment.SETTLED, debt(fixture, paidLoan.id()).payment());
      assertEquals(
          liability,
          fixture.amount(
              new InstitutionCatalog.InstitutionId(
                  "economy:loan-borrower/" + fixture.actor.value()),
              FinancialAccounts.Role.LIABILITY));
    }
  }
}
