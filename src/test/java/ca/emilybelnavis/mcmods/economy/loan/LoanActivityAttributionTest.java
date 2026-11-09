package ca.emilybelnavis.mcmods.economy.loan;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.institution.BankProviders;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.*;
import ca.emilybelnavis.mcmods.economy.simulation.activity.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityFacts.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reuses the real funded-loan fixture: principal is not inferred from total debt or payments. */
class LoanActivityAttributionTest {
    @TempDir Path path;

    @Test void actualDefaultSaleKeepsActivityObserverReadyWithNonspendableAllowance() throws Exception {
        try (var f = new LoanTestFixture(path.toRealPath())) {
            observe(f);
            var loan = f.originate();
            DebtAcceptanceTest.activate(f);
            var scheduler = DebtAcceptanceTest.scheduler(f);
            DebtAcceptanceTest.catchup(f, scheduler, loan.nextMonth()
                    + ca.emilybelnavis.mcmods.economy.debt.DebtRecords.DEFAULT_DELAY);
            var debt = f.store.transaction(tx -> ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle
                    .facilityCase(tx::query, ca.emilybelnavis.mcmods.economy.debt.DebtRecords.Kind.LOAN, loan.id()).orElseThrow());
            assertEquals(ca.emilybelnavis.mcmods.economy.debt.DebtRecords.Sale.SOLD, debt.sale());
            assertTrue(debt.discountRemainingMinor() > 0);
            var allowance = ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts.account(
                    debt.world(), debt.creditor(),
                    ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts.Role.ALLOWANCE, debt.currency());
            assertFalse(allowance.role().spendable());
            assertEquals(-debt.discountRemainingMinor(), (long) f.store.transaction(tx ->
                    Settlement.posted(tx::query, allowance.id(), debt.currency())));
            assertEquals(100_000, total(f, CREDIT_DRAW));
            assertEquals(0, total(f, OPERATOR_CREATE));
            long sequence = f.store.lastCommittedSequence();
            scheduler.advance();
            assertEquals(sequence, f.store.lastCommittedSequence());
        }
    }

    @Test void actualPrincipalDrawAndRepaymentExcludeInterestAndReplay() throws Exception {
        try (var f = new LoanTestFixture(path.toRealPath())) {
            observe(f);
            var loan = f.originate();
            assertEquals(100_000, total(f, CREDIT_DRAW));
            f.clock.addAndGet(ActivityFacts.DAY_MILLIS / 2);
            var quote = f.store.transaction(tx -> Loans.quote(tx::query, f.owner, loan.id(), f.account, 10_000, false, f.clock.get()));
            var intent = f.intent(Loans.REPAY, quote.request().request());
            var registration = Loans.repayment(f.owner, quote, f.clock::get);
            var receipt = f.submit(Loans.REPAY, registration, intent);
            assertEquals("LOAN_REPAID", receipt.result().code());
            long principal = loan.servicing().principalMinor() - f.get(loan.id()).servicing().principalMinor();
            assertTrue(principal > 0 && principal < 10_000);
            assertEquals(principal, total(f, CREDIT_REPAYMENT));
            assertEquals(receipt, f.submit(Loans.REPAY, registration, intent));
            assertEquals(principal, total(f, CREDIT_REPAYMENT));
            assertEquals(0, total(f, OPERATOR_CREATE));
        }
    }

    @Test void foreignLoanDisbursementEmitsCustomerFxPrincipalSeparatelyFromFees() throws Exception {
        try (var f = new LoanTestFixture(path.toRealPath())) {
            observe(f);
            var destination = new AccountId(UUID.randomUUID());
            f.store.transaction(tx -> tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK','USD','OPEN',1,0)",
                    List.of(destination.value().toString(), f.actor.value().toString())));
            var a = f.application(CreditUnderwriting.Product.BANK_LOAN, 100_000, 1);
            var application = new Loans.Application(a.proof(), a.application(), a.loan(), a.product(), a.currency(),
                    a.principalMinor(), a.years(), destination, 1, a.profileRevision(), a.tierRevision(), a.primeVersion(),
                    a.fxPublication(), a.calendarOrigin(), a.rateMode(), a.secured());
            var pricing = new ServicePricing(new WealthValuation(List.of(new CreditLiabilities(), new LoanLiabilities())));
            var quote = f.store.transaction(tx -> LoanDisbursementFx.capture(tx::query, f.owner, application, pricing, f.clock.get()));
            var registration = LoanDisbursementFx.application(f.owner, quote, BankProviders.NO_TIER2, f::inputs, pricing, f.clock::get);
            assertEquals("LOAN_ORIGINATED", f.submit(Loans.APPLY, registration, f.intent(Loans.APPLY, quote.canonical())).result().code());
            assertEquals(100_000, total(f, CREDIT_DRAW));
            assertEquals(100_000, total(f, FX_SELL));
            assertEquals(quote.creditedMinor(), total(f, FX_BUY));
            assertEquals(500, quote.pricing().fee().minorUnits());
            assertEquals(0, total(f, OPERATOR_CREATE));
        }
    }

    private static void observe(LoanTestFixture f) {
        var clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(f.clock.get()); }
        };
        var runtime = new ActivityRuntime(f.store, clock);
        f.store.commitObserver((i, r, c) -> runtime.observe(i, r, c, f.clock.get()));
        for (int turn = 0; turn < 100; turn++) if (runtime.prepare()) return;
        fail("Activity preparation did not finish");
    }

    private static long total(LoanTestFixture f, ActivityFacts.Kind kind) {
        return f.store.transaction(tx -> Long.parseLong(tx.query("SELECT COALESCE(sum(minor),0) AS total FROM activity_day_counters WHERE kind=?",
                List.of(kind.name()), 1).getFirst().get("total")));
    }
}
