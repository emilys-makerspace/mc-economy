package ca.emilybelnavis.mcmods.economy.foundation;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.banking.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FinanceProtocolTest {
  @Test
  void financeActionsAreFiniteAndFitExistingPacketBounds() {
    var actions = new HashSet<>(CreditMenus.actions());
    actions.addAll(LoanMenus.actions());
    actions.addAll(AutopayMenus.actions());
    actions.addAll(FinanceCollections.actions());
    actions.addAll(
        Set.of(
            "finance_home",
            "finance_wealth",
            "finance_cases",
            "finance_cases_next",
            "finance_report",
            "finance_report_next",
            "credit_advance",
            "credit_adv_preview",
            "credit_adv_confirm"));
    for (var action : actions) {
      assertTrue(action.length() <= 24, action);
      assertTrue(FinanceActions.handles(action), action);
      assertDoesNotThrow(
          () -> new BankingNetwork.Action(1, UUID.randomUUID(), 0, action, ""), action);
    }
    assertFalse(FinanceActions.handles("finance_set_score"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BankingNetwork.Action(1, UUID.randomUUID(), 0, "finance_set_score", "900"));
  }

  @Test
  void restrictedMutationsRequireKioskAndAdvancesRequirePhysicalCashTerminal() {
    for (var action :
        Set.of(
            "finance_credit_apply",
            "finance_credit_confirm",
            "finance_credit_repl_ok",
            "finance_loan_apply",
            "finance_loan_confirm")) {
      assertTrue(BankingMenu.Access.KIOSK.allows(action), action);
      for (var access :
          Set.of(
              BankingMenu.Access.CARD,
              BankingMenu.Access.ATM,
              BankingMenu.Access.TELLER,
              BankingMenu.Access.REGISTER))
        assertFalse(access.allows(action), access + " " + action);
    }
    assertTrue(BankingMenu.Access.CARD.allows("finance_credit_pay"));
    assertTrue(BankingMenu.Access.CARD.allows("finance_debt_confirm"));
    assertTrue(BankingMenu.Access.CARD.allows("finance_loan_pay_confirm"));
    for (var action : Set.of("credit_advance", "credit_adv_preview", "credit_adv_confirm")) {
      for (var access :
          Set.of(BankingMenu.Access.ATM, BankingMenu.Access.TELLER, BankingMenu.Access.KIOSK))
        assertTrue(access.allows(action), access + " " + action);
      for (var access :
          Set.of(
              BankingMenu.Access.CARD,
              BankingMenu.Access.REGISTER,
              BankingMenu.Access.ADMIN,
              BankingMenu.Access.WORLD_SETUP))
        assertFalse(access.allows(action), access + " " + action);
    }
  }
}
