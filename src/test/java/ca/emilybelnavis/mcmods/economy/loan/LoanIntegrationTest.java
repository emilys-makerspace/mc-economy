package ca.emilybelnavis.mcmods.economy.loan;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.*;
import java.math.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoanIntegrationTest {
  @TempDir Path path;

  private ServicePricing pricing() {
    return new ServicePricing(
        new WealthValuation(List.of(new CreditLiabilities(), new LoanLiabilities())));
  }

  private Loans.Application destination(Loans.Application a, AccountId account) {
    return new Loans.Application(
        a.proof(),
        a.application(),
        a.loan(),
        a.product(),
        a.currency(),
        a.principalMinor(),
        a.years(),
        account,
        1,
        a.profileRevision(),
        a.tierRevision(),
        a.primeVersion(),
        a.fxPublication(),
        a.calendarOrigin(),
        a.rateMode(),
        a.secured());
  }

  @Test
  void explicitForeignDisbursementConvertsFullPrincipalAndPaysQuotedFeeWithoutExtraSourceAccount()
      throws Exception {
    try (var f = new LoanTestFixture(path.toRealPath())) {
      var target = new AccountId(UUID.randomUUID());
      f.store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','USD','OPEN',1,0)",
                List.of(target.value().toString(), f.actor.value().toString()));
            return null;
          });
      var app = destination(f.application(CreditUnderwriting.Product.BANK_LOAN, 100000, 1), target);
      var book = pricing();
      var quote =
          f.store.transaction(
              tx -> LoanDisbursementFx.capture(tx::query, f.owner, app, book, f.clock.get()));
      assertEquals(500, quote.pricing().fee().minorUnits());
      var fx = quote.fx();
      long independent =
          BigDecimal.valueOf(100000)
              .multiply(fx.rates().get(EconomyCurrency.CAD))
              .multiply(BigDecimal.valueOf(100))
              .divide(
                  fx.rates().get(EconomyCurrency.USD).multiply(BigDecimal.valueOf(100)),
                  0,
                  RoundingMode.FLOOR)
              .longValueExact();
      assertEquals(independent, quote.creditedMinor());
      long cash = f.amount(LoanTestFixture.BANK, FinancialAccounts.Role.CASH);
      var intent = f.intent(Loans.APPLY, quote.canonical());
      var registration =
          LoanDisbursementFx.application(
              f.owner, quote, BankProviders.NO_TIER2, f::inputs, book, f.clock::get);
      var result = f.submit(Loans.APPLY, registration, intent);
      assertEquals("LOAN_ORIGINATED", result.result().code());
      assertEquals(result, f.submit(Loans.APPLY, registration, intent));
      assertEquals(100000, f.get(app.loan()).owedMinor());
      assertEquals(
          cash - 100000 + 500, f.amount(LoanTestFixture.BANK, FinancialAccounts.Role.CASH));
      assertEquals(
          quote.creditedMinor() - quote.feeFundingMinor(),
          f.store.<Long>transaction(
              tx -> Settlement.posted(tx::query, target.value(), EconomyCurrency.USD)));
      assertEquals(
          1,
          f.store
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT quote_id FROM ledger_fx_quotes WHERE quote_id=?",
                          List.of(quote.id().toString()),
                          1))
              .size());
      assertEquals(
          2,
          f.store
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT account_id FROM account_catalog WHERE owner_id=?",
                          List.of(f.actor.value().toString()),
                          4))
              .size());
      var sub =
          destination(f.application(CreditUnderwriting.Product.SUBPRIME_LOAN, 50000, 8), target);
      var subQuote =
          f.store.transaction(
              tx -> LoanDisbursementFx.capture(tx::query, f.owner, sub, book, f.clock.get()));
      assertEquals(InstitutionCatalog.IRONBRIDGE, subQuote.pricing().institution());
      assertEquals(
          "LOAN_ORIGINATED",
          f.submit(
                  Loans.APPLY,
                  LoanDisbursementFx.application(
                      f.owner, subQuote, BankProviders.NO_TIER2, f::inputs, book, f.clock::get),
                  f.intent(Loans.APPLY, subQuote.canonical()))
              .result()
              .code());
      assertEquals(
          1,
          f.store
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT payload FROM journal_records WHERE record_type=? AND record_version=2",
                          List.of(ServicePricing.FACT),
                          8))
              .size());
    }
  }

  @Test
  void naturalSegmentAccrualChangesExactWealthButNotStableTierAuthorityOrUnchangedFeeQuote()
      throws Exception {
    try (var f = new LoanTestFixture(path.toRealPath())) {
      var loan = f.originate();
      var book = pricing();
      long start = f.clock.get() + 1000;
      var first =
          f.store.transaction(
              tx ->
                  book.valuation()
                      .snapshot(tx::query, f.owner.world(), f.actor, Instant.ofEpochMilli(start)));
      var quote =
          f.store.transaction(
              tx ->
                  book.quote(
                      tx::query,
                      f.owner.world(),
                      f.actor,
                      LoanTestFixture.BANK,
                      ServicePricing.Service.FX,
                      new Money(EconomyCurrency.CAD, 10000),
                      Instant.ofEpochMilli(start),
                      Instant.ofEpochMilli(start + 60000)));
      var second =
          f.store.transaction(
              tx ->
                  book.valuation()
                      .snapshot(
                          tx::query, f.owner.world(), f.actor, Instant.ofEpochMilli(start + 1000)));
      assertTrue(first.available());
      assertTrue(second.available());
      assertNotEquals(first.fingerprint(), second.fingerprint());
      assertEquals(WealthValuation.revision(first), WealthValuation.revision(second));
      assertEquals(
          quote,
          f.store.transaction(
              tx ->
                  book.requireFresh(
                      quote,
                      tx::query,
                      f.owner.world(),
                      f.actor,
                      Instant.ofEpochMilli(start + 1000))));
      f.clock.set(start + 2000);
      var payment =
          f.store.transaction(
              tx ->
                  Loans.quote(
                      tx::query, f.owner, loan.id(), f.account, 10000, false, f.clock.get()));
      assertEquals(
          "LOAN_REPAID",
          f.submit(
                  Loans.REPAY,
                  Loans.repayment(f.owner, payment, f.clock::get),
                  f.intent(Loans.REPAY, payment.request().request()))
              .result()
              .code());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              f.store.transaction(
                  tx ->
                      book.requireFresh(
                          quote,
                          tx::query,
                          f.owner.world(),
                          f.actor,
                          Instant.ofEpochMilli(start + 3000))));
      var source =
          f.store.transaction(
              tx ->
                  new LoanLiabilities()
                      .read(
                          tx::query,
                          f.owner.world(),
                          f.actor,
                          Instant.ofEpochMilli(f.clock.get())));
      assertTrue(source.available());
      assertTrue(source.components().getFirst().major().signum() > 0);
    }
  }

  @Test
  void creditDiagnosticsIncludeLoanReceivablesAndInternalLenderFactsRetainSeparateVersions()
      throws Exception {
    try (var f = new LoanTestFixture(path.toRealPath())) {
      var loan = f.originate();
      var profile =
          f.store.transaction(tx -> CreditProfiles.find(tx::query, f.owner).orElseThrow());
      var view =
          f.store.transaction(
              tx ->
                  CreditDiagnostics.inspect(
                          tx::query,
                          f.owner,
                          profile.firstFacility(),
                          f.clock.get(),
                          CreditDiagnostics.EventCursor.start(),
                          4)
                      .orElseThrow());
      assertEquals(loan.owedMinor(), view.reconciliation().issuerCurrencyDebtMinor());
      assertTrue(view.reconciliation().issuerMatches());
      assertTrue(view.reconciliation().issuerJournalMatches());
      var sub = f.application(CreditUnderwriting.Product.SUBPRIME_LOAN, 50000, 8);
      assertEquals(
          "LOAN_ORIGINATED", f.apply(sub, f.intent(Loans.APPLY, sub.request())).result().code());
      var facts =
          f.store.transaction(
              tx ->
                  tx.query(
                      "SELECT record_version,payload FROM journal_records WHERE record_type=? AND record_version=2",
                      List.of(CreditProfiles.INQUIRY_FACT),
                      8));
      assertEquals(1, facts.size());
      CreditProfiles.validateInternalInquiryFact(facts.getFirst().get("payload"));
      assertThrows(
          IllegalArgumentException.class,
          () -> CreditProfiles.validateInquiryFact(facts.getFirst().get("payload")));
    }
  }

  @Test
  void monthlyProviderCatchesUpTwoOfflinePeriodsAndRetainsStableCompletion() throws Exception {
    try (var f = new LoanTestFixture(path.toRealPath())) {
      var loan = f.originate();
      var activation = new Journal.Kind("fixture:loan-runtime", "fixture:calendar-witness");
      f.submit(
          activation,
          new JournalEngine.Registration(
              1,
              (i, r) ->
                  new JournalEngine.Commit(
                      List.of(
                          new Journal.Record(
                              ca.emilybelnavis.mcmods.economy.clock.DeadlineDispatcher.ACTIVATION,
                              1,
                              "1|" + loan.anchor())),
                      new Journal.Result(1, "FIXTURE", ""),
                      tx -> {})),
          f.intent(activation, new Journal.CanonicalRequest(1, Map.of())));
      var wall = Clock.fixed(Instant.ofEpochMilli(loan.anchor() + 2 * Loans.MONTH), ZoneOffset.UTC);
      var scheduler =
          new ca.emilybelnavis.mcmods.economy.clock.DeadlineDispatcher(
              f.store, wall, List.of(LoanRuntime.provider()), 1);
      scheduler.prepare();
      for (int n = 0; n < 32 && !scheduler.progress().ready(); n++) scheduler.advance();
      assertTrue(scheduler.progress().ready());
      assertEquals(2, f.get(loan.id()).servicing().completedPeriods());
      assertEquals(
          2,
          f.store
              .transaction(tx -> Loans.installments(tx::query, f.owner, loan.id(), 0, 16))
              .installments()
              .size());
      long sequence = f.store.lastCommittedSequence();
      scheduler.prepare();
      assertTrue(scheduler.advance().ready());
      assertEquals(sequence, f.store.lastCommittedSequence());
    }
  }
}
