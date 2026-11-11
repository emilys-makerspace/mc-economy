package ca.emilybelnavis.mcmods.economy.loan;

import ca.emilybelnavis.mcmods.economy.debt.DebtClaims;
import ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;

/** Saved claim witnesses keep repayment reconciliation independent of portfolio size. */
final class LoanAccounting {
  private LoanAccounting() {}

  static boolean current(JournalEngine.ReadView reads, Loans.Contract loan) {
    return DebtClaims.matches(
            reads,
            loan.world(),
            Loans.borrower(loan.owner()),
            FinancialAccounts.Role.LIABILITY,
            loan.currency())
        && DebtClaims.matches(
            reads, loan.world(), loan.lender(), FinancialAccounts.Role.RECEIVABLE, loan.currency());
  }
}
