package ca.emilybelnavis.mcmods.economy.banking;

import ca.emilybelnavis.mcmods.economy.foundation.*;
import java.util.Set;

/** Finite finance protocol extension and physical-surface restrictions. */
public final class FinanceActions {
  private FinanceActions() {}

  public static boolean handles(String action) {
    return AutopayMenus.handles(action)
        || action.equals("finance_home")
        || CreditMenus.handles(action)
        || LoanMenus.handles(action)
        || FinanceReports.handles(action)
        || FinanceCollections.handles(action)
        || CreditUse.handles(action);
  }

  public static boolean input(String action) {
    return Set.of(
            "finance_credit_preview",
            "finance_credit_amount",
            "finance_credit_limit",
            "finance_loan_preview",
            "finance_loan_repay",
            "finance_debt_amount",
            "credit_adv_preview")
        .contains(action);
  }

  public static boolean allowed(BankingMenu.Access access, String action) {
    if (!handles(action)) return false;
    if (CreditUse.handles(action))
      return access == BankingMenu.Access.ATM
          || access == BankingMenu.Access.TELLER
          || access == BankingMenu.Access.KIOSK;
    if (access != BankingMenu.Access.ATM
        && access != BankingMenu.Access.TELLER
        && access != BankingMenu.Access.KIOSK
        && access != BankingMenu.Access.CARD) return false;
    if (Set.of(
            "finance_credit_apply",
            "finance_credit_network",
            "finance_credit_preview",
            "finance_credit_confirm",
            "finance_credit_raise",
            "finance_credit_limit",
            "finance_credit_raise_ok",
            "finance_credit_replace",
            "finance_credit_fee",
            "finance_credit_repl_ok",
            "finance_loan_apply",
            "finance_loan_product",
            "finance_loan_destination",
            "finance_loan_preview",
            "finance_loan_confirm")
        .contains(action)) return access == BankingMenu.Access.KIOSK;
    return true;
  }
}
