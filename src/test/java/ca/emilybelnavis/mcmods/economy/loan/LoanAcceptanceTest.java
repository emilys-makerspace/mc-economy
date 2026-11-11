package ca.emilybelnavis.mcmods.economy.loan;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.credit.CreditLiabilities;
import ca.emilybelnavis.mcmods.economy.credit.CreditUnderwriting;
import ca.emilybelnavis.mcmods.economy.institution.BankProviders;
import ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.journal.PostingBatch;
import ca.emilybelnavis.mcmods.economy.journal.Settlement;
import ca.emilybelnavis.mcmods.economy.pricing.WealthValuation;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.storage.LedgerProjection;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite acceptance witnesses for the fixed loan accounting boundary. */
class LoanAcceptanceTest {
  private static final EconomyCurrency CAD = EconomyCurrency.CAD;
  @TempDir Path path;

  @Test
  void scoreBandTermEdgesProduceCapturedContractsAndPreciseDenials() throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      setScore(fixture, 620, 2);
      var bank = fixture.application(CreditUnderwriting.Product.BANK_LOAN, 100_000, 2);
      assertEquals(
          "LOAN_ORIGINATED",
          fixture.apply(bank, fixture.intent(Loans.APPLY, bank.request())).result().code());
      var bankContract = fixture.get(bank.loan());
      assertEquals(new BigDecimal("0.14"), bankContract.apr());
      assertEquals(26, bankContract.servicing().totalPeriods());
      assertEquals(new BigDecimal("0.06"), bankContract.primeApr());
      assertEquals(620, bankContract.capturedScore());

      var bankTooLong = fixture.application(CreditUnderwriting.Product.BANK_LOAN, 100_000, 3);
      var bankDenial =
          fixture.apply(bankTooLong, fixture.intent(Loans.APPLY, bankTooLong.request()));
      assertEquals("LOAN_APPLICATION_DENIED", bankDenial.result().code());
      assertEquals("TERM_NOT_PERMITTED", bankDenial.result().payload());

      setScore(fixture, 500, 3);
      var subprime = fixture.application(CreditUnderwriting.Product.SUBPRIME_LOAN, 50_000, 3);
      assertEquals(
          "LOAN_ORIGINATED",
          fixture.apply(subprime, fixture.intent(Loans.APPLY, subprime.request())).result().code());
      var subprimeContract = fixture.get(subprime.loan());
      assertEquals(new BigDecimal("0.3499"), subprimeContract.apr());
      assertEquals(39, subprimeContract.servicing().totalPeriods());
      assertEquals(0, subprimeContract.primeVersion());

      var subprimeTooLong =
          fixture.application(CreditUnderwriting.Product.SUBPRIME_LOAN, 50_000, 4);
      var subprimeDenial =
          fixture.apply(subprimeTooLong, fixture.intent(Loans.APPLY, subprimeTooLong.request()));
      assertEquals("LOAN_APPLICATION_DENIED", subprimeDenial.result().code());
      assertEquals("TERM_NOT_PERMITTED", subprimeDenial.result().payload());
      assertEquals(bankContract, fixture.get(bank.loan()));
      assertEquals(
          2,
          fixture
              .store
              .transaction(tx -> Loans.history(tx::query, fixture.owner, null, 16))
              .size());
    }
  }

  @Test
  void borrowedProceedsCannotPromoteAnOwnerJustBelowTheNetWorthThreshold() throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      long belowThreshold = 9_999_999_999L;
      long existing =
          fixture.store.transaction(
              tx -> Settlement.posted(tx::query, fixture.account.value(), CAD));
      var topUp = new Journal.Kind("fixture:loan-acceptance", "fixture:threshold-funding");
      var amount = belowThreshold - existing;
      var legs =
          List.of(
              new PostingBatch.Leg(
                  Settlement.issuanceAccount(CAD),
                  PostingBatch.Role.ISSUANCE,
                  CAD,
                  new Money(CAD, -amount)),
              new PostingBatch.Leg(
                  fixture.account.value(), PostingBatch.Role.ACCOUNT, CAD, new Money(CAD, amount)));
      assertEquals(
          Journal.Status.COMMITTED,
          fixture
              .submit(
                  topUp,
                  Settlement.registrationMinor(
                      (intent, leg, reads) -> PostingBatch.Authority.ISSUE),
                  fixture.intent(topUp, Settlement.minorRequest(legs)))
              .status());
      var valuation = new WealthValuation(List.of(new CreditLiabilities(), new LoanLiabilities()));
      var before =
          fixture.store.transaction(
              tx ->
                  valuation.snapshot(
                      tx::query,
                      fixture.owner.world(),
                      fixture.actor,
                      Instant.ofEpochMilli(fixture.clock.get())));
      assertTrue(before.available());
      assertFalse(before.eligible());
      assertEquals(0, new BigDecimal("99999999.99").compareTo(before.netWorth()));

      var loan = fixture.originate();
      long spendable =
          fixture.store.transaction(
              tx -> Settlement.posted(tx::query, fixture.account.value(), CAD));
      assertTrue(spendable >= 10_000_000_000L);
      var after =
          fixture.store.transaction(
              tx ->
                  valuation.snapshot(
                      tx::query,
                      fixture.owner.world(),
                      fixture.actor,
                      Instant.ofEpochMilli(fixture.clock.get())));
      assertTrue(after.available());
      assertFalse(after.eligible());
      assertEquals(0, before.netWorth().compareTo(after.netWorth()));
      assertEquals(
          loan.owedMinor(),
          fixture.amount(LoanTestFixture.BANK, FinancialAccounts.Role.RECEIVABLE));
      assertEquals(
          1, after.components().stream().filter(c -> c.claimId().startsWith("loan-debt:")).count());
    }
  }

  @Test
  void payoffRejectsExtraOrMissingBorrowerAndLenderPrincipalWithoutMovingMoney() throws Exception {
    for (String claim : List.of("borrower", "lender")) {
      for (long difference : new long[] {-1, 1}) {
        var directory = Files.createDirectory(path.resolve(claim + "-" + difference));
        try (var fixture = new LoanTestFixture(directory)) {
          var loan = fixture.originate();
          var payoff =
              fixture.store.transaction(
                  tx ->
                      Loans.quote(
                          tx::query,
                          fixture.owner,
                          loan.id(),
                          fixture.account,
                          0,
                          true,
                          fixture.clock.get()));
          var queryLimits = new ArrayList<Integer>();
          boolean completeClaims =
              fixture.store.transaction(
                  tx -> {
                    JournalEngine.ReadView boundedOverflow =
                        (sql, arguments, maxRows) -> {
                          queryLimits.add(maxRows);
                          if (sql.startsWith(
                              "SELECT debt_minor FROM loans WHERE world_id=? AND lender=?")) {
                            throw new AssertionError(
                                "Repayment must use constant-size claim witnesses");
                          }
                          return tx.query(sql, arguments, maxRows);
                        };
                    return LoanAccounting.current(boundedOverflow, loan);
                  });
          assertTrue(completeClaims);
          assertTrue(queryLimits.stream().allMatch(limit -> limit <= 1));
          var claimOwner = claim.equals("borrower") ? Loans.borrower(fixture.actor) : loan.lender();
          var role =
              claim.equals("borrower")
                  ? FinancialAccounts.Role.LIABILITY
                  : FinancialAccounts.Role.RECEIVABLE;
          var claimAccount = FinancialAccounts.id(fixture.owner.world(), claimOwner, role, CAD);
          long expected = claim.equals("borrower") ? -loan.owedMinor() : loan.owedMinor();
          // Deliberate projection corruption proves settlement cannot erase an unreconciled claim.
          fixture.store.transaction(
              tx -> {
                LedgerProjection.write(tx, claimAccount, CAD, expected + difference);
                return null;
              });
          long cash = fixture.amount(loan.lender(), FinancialAccounts.Role.CASH);
          long funding =
              fixture.store.transaction(
                  tx -> Settlement.posted(tx::query, fixture.account.value(), CAD));
          long sequence = fixture.store.lastCommittedSequence();
          var outcome =
              fixture.submit(
                  Loans.REPAY,
                  Loans.repayment(fixture.owner, payoff, fixture.clock::get),
                  fixture.intent(Loans.REPAY, payoff.request().request()));
          assertEquals(
              "LOAN_ACCOUNTING_UNAVAILABLE", outcome.result().code(), claim + " " + difference);
          assertNotEquals(Journal.Status.COMMITTED, outcome.status());
          assertEquals(loan, fixture.get(loan.id()));
          assertEquals(cash, fixture.amount(loan.lender(), FinancialAccounts.Role.CASH));
          long remainingFunding =
              fixture.store.transaction(
                  tx -> Settlement.posted(tx::query, fixture.account.value(), CAD));
          long remainingClaim =
              fixture.store.transaction(tx -> Settlement.posted(tx::query, claimAccount, CAD));
          assertEquals(funding, remainingFunding);
          assertEquals(expected + difference, remainingClaim);
          assertEquals(sequence, fixture.store.lastCommittedSequence());
        }
      }
    }
  }

  @Test
  void payingOneLoanAndReplayingItsOutcomePreservesTheOtherFundedClaim() throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      var first = fixture.originate();
      var second = fixture.originate();
      long reservation = fixture.amount(first.lender(), FinancialAccounts.Role.RESERVATION);
      var payoff =
          fixture.store.transaction(
              tx ->
                  Loans.quote(
                      tx::query,
                      fixture.owner,
                      first.id(),
                      fixture.account,
                      0,
                      true,
                      fixture.clock.get()));
      var intent = fixture.intent(Loans.REPAY, payoff.request().request());
      var registration = Loans.repayment(fixture.owner, payoff, fixture.clock::get);
      var settled = fixture.submit(Loans.REPAY, registration, intent);
      assertEquals("LOAN_SETTLED", settled.result().code());
      assertEquals(Loans.Status.SETTLED, fixture.get(first.id()).status());
      assertEquals(0, fixture.get(first.id()).owedMinor());
      assertEquals(second, fixture.get(second.id()));
      assertEquals(
          second.owedMinor(), fixture.amount(first.lender(), FinancialAccounts.Role.RECEIVABLE));
      assertEquals(reservation, fixture.amount(first.lender(), FinancialAccounts.Role.RESERVATION));
      long sequence = fixture.store.lastCommittedSequence();
      assertEquals(settled, fixture.submit(Loans.REPAY, registration, intent));
      assertEquals(sequence, fixture.store.lastCommittedSequence());
      var inspection =
          fixture.store.transaction(
              tx ->
                  LoanDiagnostics.inspect(
                          tx::query,
                          fixture.owner,
                          second.id(),
                          fixture.clock.get(),
                          LoanDiagnostics.EventCursor.start(),
                          4)
                      .orElseThrow());
      assertTrue(inspection.reconciliation().ownerMatches());
      assertTrue(inspection.reconciliation().issuerMatches());
      assertTrue(inspection.reconciliation().ownerJournalMatches());
      assertTrue(inspection.reconciliation().issuerJournalMatches());
    }
  }

  @Test
  void uncertaintyAfterARealOriginationCommitResolvesTheOriginalOutcomeOnce() throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      var armed = new AtomicBoolean(true);
      var recoveries = new AtomicInteger();
      var preparations = new AtomicInteger();
      FoundationStore uncertain =
          new FoundationStore() {
            public void initialize(Path ignored) {
              throw new UnsupportedOperationException();
            }

            public Snapshot snapshot() {
              return fixture.store.snapshot();
            }

            public long lastCommittedSequence() {
              return fixture.store.lastCommittedSequence();
            }

            public RecordCatalog recordCatalog() {
              return fixture.store.recordCatalog();
            }

            public void recover() {
              recoveries.incrementAndGet();
              fixture.store.recover();
            }

            public void close() throws Exception {
              fixture.store.close();
            }

            public JournalEngine.Decision decorateCommit(
                Journal.Intent intent, JournalEngine.ReadView reads, JournalEngine.Commit commit) {
              return fixture.store.decorateCommit(intent, reads, commit);
            }

            public <T> T transaction(Work<T> work) {
              T result = fixture.store.transaction(work);
              if (result instanceof Journal.Outcome outcome
                  && outcome.status() == Journal.Status.COMMITTED
                  && armed.getAndSet(false)) {
                throw new StorageException(
                    StorageException.Kind.UNKNOWN_COMMIT,
                    "Injected after a real committed loan acceptance transaction");
              }
              return result;
            }
          };
      var application = fixture.application(CreditUnderwriting.Product.BANK_LOAN, 100_000, 1);
      var original =
          Loans.application(
              fixture.owner,
              application,
              BankProviders.NO_TIER2,
              fixture::inputs,
              fixture.clock::get);
      var registration =
          new JournalEngine.Registration(
              1,
              (intent, reads) -> {
                preparations.incrementAndGet();
                return original.handler().prepare(intent, reads);
              });
      var engine =
          new JournalEngine(uncertain, (actor, kind) -> true, Map.of(Loans.APPLY, registration));
      var intent = fixture.intent(Loans.APPLY, application.request());
      long sequence = fixture.store.lastCommittedSequence();
      long cash = fixture.amount(LoanTestFixture.BANK, FinancialAccounts.Role.CASH);
      var outcome = engine.submit(intent);
      assertEquals("LOAN_ORIGINATED", outcome.result().code());
      assertEquals(Journal.Status.COMMITTED, outcome.status());
      assertEquals(1, preparations.get());
      assertEquals(1, recoveries.get());
      assertEquals(sequence + 1, fixture.store.lastCommittedSequence());
      assertEquals(
          cash - application.principalMinor(),
          fixture.amount(LoanTestFixture.BANK, FinancialAccounts.Role.CASH));
      assertEquals(
          application.principalMinor(),
          fixture.amount(LoanTestFixture.BANK, FinancialAccounts.Role.RECEIVABLE));
      assertEquals(outcome, engine.submit(intent));
      assertEquals(1, preparations.get());
      assertEquals(sequence + 1, fixture.store.lastCommittedSequence());
      assertEquals(
          1,
          fixture
              .store
              .transaction(tx -> Loans.history(tx::query, fixture.owner, null, 16))
              .size());
    }
  }

  private static void setScore(LoanTestFixture fixture, int score, long revision) {
    // Explicit profile-projection fixture keeps production scoring activation outside this test.
    fixture.clock.addAndGet(2);
    fixture.store.transaction(
        tx -> {
          tx.execute(
              "UPDATE credit_profiles SET score=?,revision=?,last_boundary=? WHERE owner_id=?",
              List.of(
                  Integer.toString(score),
                  Long.toString(revision),
                  Long.toString(fixture.clock.get() - 1),
                  fixture.actor.value().toString()));
          return null;
        });
  }
}
