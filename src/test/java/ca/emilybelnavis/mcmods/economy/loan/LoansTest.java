package ca.emilybelnavis.mcmods.economy.loan;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.math.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoansTest {
  @TempDir Path path;
  private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
  private static final InstitutionCatalog.InstitutionId BANK =
      new InstitutionCatalog.InstitutionId("economy:bank/crownstone");

  @Test
  void originationFundsOneFixedContractAndApplicationIdentityReplaysOnce() throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var a = f.application(CreditUnderwriting.Product.BANK_LOAN, 100000, 1);
      var intent = f.intent(Loans.APPLY, a.request());
      long before = f.amount(BANK, FinancialAccounts.Role.CASH),
          reserve = f.amount(BANK, FinancialAccounts.Role.RESERVATION);
      var first = f.apply(a, intent);
      assertEquals("LOAN_ORIGINATED", first.result().code());
      assertEquals(first, f.apply(a, intent));
      var loan = f.get(a.loan());
      assertEquals(new BigDecimal("0.12"), loan.apr());
      assertEquals(650, loan.capturedScore());
      assertEquals(13, loan.servicing().totalPeriods());
      assertEquals(100000, loan.owedMinor());
      assertEquals(before - 100000, f.amount(BANK, FinancialAccounts.Role.CASH));
      assertEquals(reserve, f.amount(BANK, FinancialAccounts.Role.RESERVATION));
      assertEquals(100000, f.amount(BANK, FinancialAccounts.Role.RECEIVABLE));
      assertEquals(
          13,
          f.store.transaction(tx -> Loans.schedule(tx::query, f.owner, a.loan(), 0, 16)).size());
      long frontier = f.store.lastCommittedSequence();
      var decision =
          f.store.transaction(
              tx -> Loans.applicationDecision(tx::query, f.owner, a.application()).orElseThrow());
      assertTrue(decision.approved());
      assertTrue(decision.established());
      assertEquals(650, decision.capturedScore());
      assertEquals(100000, decision.requestedMinor());
      assertEquals(1, decision.years());
      assertEquals(new BigDecimal("0.12"), decision.apr());
      assertEquals(10_000_000, decision.maximumPrincipalWorldMinor());
      assertEquals(15_000_000, decision.totalExposureCeilingWorldMinor());
      assertEquals(frontier, f.store.lastCommittedSequence());
      assertEquals("LEASE_USED", f.apply(a, f.intent(Loans.APPLY, a.request())).result().code());
      var freshProof =
          BranchFixtures.proof(
              new BranchAdmission.Context(
                  f.owner.world(),
                  UUID.randomUUID(),
                  f.actor,
                  BranchAdmission.Action.LOAN_APPLY,
                  BANK,
                  null,
                  null,
                  0,
                  0,
                  0));
      var duplicate =
          new Loans.Application(
              freshProof,
              a.application(),
              UUID.randomUUID(),
              a.product(),
              a.currency(),
              a.principalMinor(),
              a.years(),
              a.destination(),
              a.destinationRevision(),
              a.profileRevision(),
              a.tierRevision(),
              a.primeVersion(),
              a.fxPublication(),
              a.calendarOrigin(),
              a.rateMode(),
              a.secured());
      assertEquals(
          "LOAN_APPLICATION_REUSED",
          f.apply(duplicate, f.intent(Loans.APPLY, duplicate.request())).result().code());
      assertEquals(
          1, f.store.transaction(tx -> Loans.history(tx::query, f.owner, null, 16)).size());
    }
  }

  @Test
  void subprimeUsesSharedLenderAndUnsupportedRequestsCreateNoLoans() throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var a = f.application(CreditUnderwriting.Product.SUBPRIME_LOAN, 50000, 8);
      assertEquals(
          "LOAN_ORIGINATED", f.apply(a, f.intent(Loans.APPLY, a.request())).result().code());
      var loan = f.get(a.loan());
      assertEquals(InstitutionCatalog.IRONBRIDGE, loan.lender());
      assertEquals(new BigDecimal("0.1999"), loan.apr());
      assertEquals(104, loan.servicing().totalPeriods());
      var base = f.application(CreditUnderwriting.Product.BANK_LOAN, 100000, 1);
      var variable =
          new Loans.Application(
              base.proof(),
              base.application(),
              base.loan(),
              base.product(),
              base.currency(),
              base.principalMinor(),
              base.years(),
              base.destination(),
              base.destinationRevision(),
              base.profileRevision(),
              base.tierRevision(),
              base.primeVersion(),
              base.fxPublication(),
              base.calendarOrigin(),
              Loans.RateMode.VARIABLE,
              false);
      assertEquals(
          "VARIABLE_LOAN_UNSUPPORTED",
          f.apply(variable, f.intent(Loans.APPLY, variable.request())).result().code());
      assertTrue(Loans.capabilities().fixed());
      assertFalse(Loans.capabilities().secured());
      assertFalse(Loans.capabilities().variable());
      var foreign =
          new PartyRef(f.owner.world(), new PartyRef.Player(new PlayerId(UUID.randomUUID())));
      assertTrue(f.store.transaction(tx -> Loans.history(tx::query, foreign, null, 16)).isEmpty());
    }
  }

  @Test
  void monthAndPartialPayoffPreserveEarnedInterestAndCloseWithoutAnotherCharge() throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var loan = f.originate();
      long half = loan.anchor() + Loans.MONTH / 2;
      f.clock.set(half);
      var quote =
          f.store.transaction(
              tx -> Loans.quote(tx::query, f.owner, loan.id(), f.account, 10000, false, half));
      var paid =
          f.submit(
              Loans.REPAY,
              Loans.repayment(f.owner, quote, f.clock::get),
              f.intent(Loans.REPAY, quote.request().request()));
      assertEquals("LOAN_REPAID", paid.result().code());
      var reduced = f.get(loan.id());
      assertTrue(reduced.servicing().principalMinor() < 100000);
      assertEquals(loan.servicing().installmentMinor(), reduced.servicing().installmentMinor());
      long due = loan.anchor() + Loans.MONTH;
      f.clock.set(due);
      var kind = new Journal.Kind("fixture:loans", "fixture:month");
      var intent =
          f.intent(kind, new Journal.CanonicalRequest(1, Map.of("at", Long.toString(due))));
      var registration =
          new JournalEngine.Registration(1, (i, r) -> Loans.month(r, loan.id(), due));
      var closed = f.submit(kind, registration, intent);
      assertEquals("LOAN_MONTH_CLOSED", closed.result().code());
      assertEquals(closed, f.submit(kind, registration, intent));
      var latest = f.get(loan.id());
      assertEquals(1, latest.servicing().requirements().size());
      assertEquals(
          1,
          f.store
              .transaction(tx -> Loans.installments(tx::query, f.owner, loan.id(), 0, 16))
              .installments()
              .size());
      f.clock.set(due + 1000);
      var payoff =
          f.store.transaction(
              tx -> Loans.quote(tx::query, f.owner, loan.id(), f.account, 0, true, f.clock.get()));
      f.clock.addAndGet(1000);
      assertEquals(
          "LOAN_SETTLED",
          f.submit(
                  Loans.REPAY,
                  Loans.repayment(f.owner, payoff, f.clock::get),
                  f.intent(Loans.REPAY, payoff.request().request()))
              .result()
              .code());
      var settled = f.get(loan.id());
      assertEquals(Loans.Status.SETTLED, settled.status());
      assertEquals(0, settled.owedMinor());
      assertEquals(0, settled.servicing().residualMajor().signum());
      assertEquals(0, settled.servicing().accruedMajor().signum());
      assertEquals(
          "LOAN_MONTH_STALE",
          f.submit(
                  kind,
                  new JournalEngine.Registration(
                      1, (i, r) -> Loans.month(r, loan.id(), due + Loans.MONTH)),
                  f.intent(
                      kind,
                      new Journal.CanonicalRequest(
                          1, Map.of("at", Long.toString(due + Loans.MONTH)))))
              .result()
              .code());
    }
  }

  @Test
  void staleTierAndUnavailablePrimeFailClosedWithoutApplicationOrDebt() throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var stale = f.application(CreditUnderwriting.Product.BANK_LOAN, 100000, 1);
      var tier =
          (BankProviders.TierEligibility) (r, o) -> new BankProviders.TierSnapshot(true, true, 9);
      assertEquals(
          "LOAN_TIER_STALE",
          f.submit(
                  Loans.APPLY,
                  Loans.application(f.owner, stale, tier, f::inputs, f.clock::get),
                  f.intent(Loans.APPLY, stale.request()))
              .result()
              .code());
      f.store.transaction(
          tx -> {
            tx.execute("DELETE FROM pricing_policies", List.of());
            return null;
          });
      var unavailable = f.application(CreditUnderwriting.Product.BANK_LOAN, 100000, 1);
      assertEquals(
          "LOAN_PRIME_UNAVAILABLE",
          f.apply(unavailable, f.intent(Loans.APPLY, unavailable.request())).result().code());
      assertTrue(f.store.transaction(tx -> Loans.history(tx::query, f.owner, null, 16)).isEmpty());
      assertEquals(
          0,
          f.store.<Integer>transaction(
              tx ->
                  Integer.parseInt(
                      tx.query("SELECT count(*) AS n FROM loan_application_ids", List.of(), 1)
                          .getFirst()
                          .get("n"))));
    }
  }

  @Test
  void capturedLowScoreDeniesBankButAllowsBoundedSharedSubprime() throws Exception {
    try (var f = new LoanTestFixture(path)) {
      // Detached authoritative-score projection fixture; production reporting remains dormant.
      f.clock.addAndGet(2);
      f.store.transaction(
          tx -> {
            tx.execute(
                "UPDATE credit_profiles SET score=619,revision=2,last_boundary=? WHERE owner_id=?",
                List.of(Long.toString(NOW.toEpochMilli() + 1), f.actor.value().toString()));
            return null;
          });
      var bank = f.application(CreditUnderwriting.Product.BANK_LOAN, 100000, 1);
      var denied = f.apply(bank, f.intent(Loans.APPLY, bank.request()));
      assertEquals("LOAN_APPLICATION_DENIED", denied.result().code());
      assertEquals("SCORE_BELOW_PRODUCT_FLOOR", denied.result().payload());
      var decision =
          f.store.transaction(
              tx ->
                  Loans.applicationDecision(tx::query, f.owner, bank.application()).orElseThrow());
      assertFalse(decision.approved());
      assertEquals(619, decision.capturedScore());
      assertEquals(0, decision.maximumPrincipalWorldMinor());
      assertEquals("SCORE_BELOW_PRODUCT_FLOOR", decision.reason());
      var foreign =
          new PartyRef(f.owner.world(), new PartyRef.Player(new PlayerId(UUID.randomUUID())));
      assertTrue(
          f.store
              .transaction(tx -> Loans.applicationDecision(tx::query, foreign, bank.application()))
              .isEmpty());
      var alternative = f.application(CreditUnderwriting.Product.SUBPRIME_LOAN, 50000, 8);
      assertEquals(
          "LOAN_ORIGINATED",
          f.apply(alternative, f.intent(Loans.APPLY, alternative.request())).result().code());
      assertEquals(new BigDecimal("0.2499"), f.get(alternative.loan()).apr());
      assertEquals(619, f.get(alternative.loan()).capturedScore());
    }
  }

  @Test
  void capitalAndAggregateExposureCannotBeReusedAcrossApplications() throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var first = f.application(CreditUnderwriting.Product.BANK_LOAN, 10_000_000, 1);
      assertEquals(
          "LOAN_ORIGINATED",
          f.apply(first, f.intent(Loans.APPLY, first.request())).result().code());
      var next = f.application(CreditUnderwriting.Product.BANK_LOAN, 10_000_000, 1);
      var denied = f.apply(next, f.intent(Loans.APPLY, next.request()));
      assertEquals("LOAN_APPLICATION_DENIED", denied.result().code());
      assertEquals("OWNER_EXPOSURE_CEILING", denied.result().payload());
      assertEquals(
          1, f.store.transaction(tx -> Loans.history(tx::query, f.owner, null, 16)).size());
      long floor =
          f.store.transaction(
              tx ->
                  Long.parseLong(
                      tx.query(
                              "SELECT operating_floor_minor FROM institution_capital_allocations WHERE institution_key=?",
                              List.of(BANK.key()),
                              1)
                          .getFirst()
                          .get("operating_floor_minor")));
      f.store.transaction(
          tx -> {
            LedgerProjection.write(
                tx,
                FinancialAccounts.id(
                    f.owner.world(), BANK, FinancialAccounts.Role.CASH, EconomyCurrency.CAD),
                EconomyCurrency.CAD,
                floor);
            return null;
          });
      var cash = f.application(CreditUnderwriting.Product.BANK_LOAN, 100000, 1);
      var unfunded = f.apply(cash, f.intent(Loans.APPLY, cash.request()));
      assertEquals("LOAN_APPLICATION_DENIED", unfunded.result().code());
      assertEquals("CREDIT_OPERATING_FLOOR", unfunded.result().payload());
    }
  }

  @Test
  void destinationOverflowReturnsRejectionWithoutAReservedOrPersistedLoan() throws Exception {
    try (var f = new LoanTestFixture(path)) {
      // Fault fixture: an unrepresentable destination must fail closed before any loan projection.
      f.store.transaction(
          tx -> {
            LedgerProjection.write(tx, f.account.value(), EconomyCurrency.CAD, Long.MAX_VALUE);
            return null;
          });
      long cash = f.amount(BANK, FinancialAccounts.Role.CASH),
          reserve = f.amount(BANK, FinancialAccounts.Role.RESERVATION),
          sequence = f.store.lastCommittedSequence();
      var request = f.application(CreditUnderwriting.Product.BANK_LOAN, 100000, 1);
      assertEquals(
          "POSTING_OVERFLOW",
          f.apply(request, f.intent(Loans.APPLY, request.request())).result().code());
      assertEquals(cash, f.amount(BANK, FinancialAccounts.Role.CASH));
      assertEquals(reserve, f.amount(BANK, FinancialAccounts.Role.RESERVATION));
      assertEquals(sequence, f.store.lastCommittedSequence());
      assertTrue(f.store.transaction(tx -> Loans.history(tx::query, f.owner, null, 16)).isEmpty());
    }
  }

  @Test
  void unestablishedDecisionHasNoFabricatedScoreAndFrozenOfferSurvivesLaterPolicyChanges()
      throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var newcomer = new PlayerId(UUID.randomUUID());
      var owner = new PartyRef(f.owner.world(), new PartyRef.Player(newcomer));
      var account = new AccountId(UUID.randomUUID());
      f.store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                List.of(account.value().toString(), newcomer.value().toString()));
            return null;
          });
      var proof =
          BranchFixtures.proof(
              new BranchAdmission.Context(
                  owner.world(),
                  UUID.randomUUID(),
                  newcomer,
                  BranchAdmission.Action.LOAN_APPLY,
                  BANK,
                  null,
                  null,
                  0,
                  0,
                  0));
      var publication = f.store.transaction(tx -> FxService.latest(tx::query)).updateId();
      var request =
          new Loans.Application(
              proof,
              UUID.randomUUID(),
              UUID.randomUUID(),
              CreditUnderwriting.Product.BANK_LOAN,
              EconomyCurrency.CAD,
              100000,
              1,
              account,
              1,
              0,
              0,
              1,
              publication,
              NOW.toEpochMilli(),
              Loans.RateMode.FIXED,
              false);
      var intent =
          new Journal.Intent(
              new OperationId(UUID.randomUUID()),
              new RequestId(UUID.randomUUID()),
              newcomer,
              Loans.APPLY,
              request.request(),
              null,
              null);
      var outcome =
          f.submit(
              Loans.APPLY,
              Loans.application(owner, request, BankProviders.NO_TIER2, f::inputs, f.clock::get),
              intent);
      assertEquals("LOAN_APPLICATION_DENIED", outcome.result().code());
      assertEquals("CREDIT_UNESTABLISHED", outcome.result().payload());
      var original =
          f.store.transaction(
              tx ->
                  Loans.applicationDecision(tx::query, owner, request.application()).orElseThrow());
      assertFalse(original.established());
      assertEquals(0, original.capturedScore());
      assertEquals(0, original.profileRevision());
      assertEquals("CREDIT_UNESTABLISHED", original.reason());
      assertTrue(f.store.transaction(tx -> CreditProfiles.find(tx::query, owner)).isEmpty());
      assertTrue(
          f.store
              .transaction(
                  tx -> Loans.applicationDecision(tx::query, f.owner, request.application()))
              .isEmpty());
      f.store.transaction(
          tx -> {
            tx.execute("UPDATE pricing_policies SET prime_apr='0.10' WHERE version=1", List.of());
            return null;
          });
      assertEquals(
          original,
          f.store.transaction(
              tx ->
                  Loans.applicationDecision(tx::query, owner, request.application())
                      .orElseThrow()));
      assertEquals(new BigDecimal("0.06"), original.primeApr());
      assertEquals(new BigDecimal("0.12"), original.apr());
    }
  }
}
