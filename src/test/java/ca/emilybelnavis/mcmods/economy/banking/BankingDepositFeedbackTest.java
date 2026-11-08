package ca.emilybelnavis.mcmods.economy.banking;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Rejections must explain the action a player can take, without exposing protocol identifiers. */
class BankingDepositFeedbackTest {
    @Test void disabledTerminalAndUnacceptedCashHaveDifferentUsefulMessages() {
        var disabled = message("DEPOSIT_MODE");
        assertTrue(disabled.contains("disabled on this ATM"));
        assertTrue(disabled.contains("owner"));
        var unaccepted = message("DEPOSIT_DENOMINATION");
        assertTrue(unaccepted.contains("does not accept that cash"));
        assertTrue(unaccepted.contains("bills only"));
        assertNotEquals(disabled, unaccepted);
    }

    @Test void emptyInputsAndForeignIssuedCashDoNotReceiveAnOpaqueFailure() {
        assertTrue(message("DEPOSIT_SLOT_COUNT").contains("Place cash"));
        assertTrue(message("DEPOSIT_WORLD").contains("another world"));
        assertTrue(message("DEPOSIT_CASH_RESERVED").contains("reserved"));
        assertTrue(message("DEPOSIT_UNRECOGNIZED").contains("issued value"));
        assertTrue(message("DEPOSIT_ACCOUNT_STALE").contains("Choose an open account"));
        assertTrue(message("DEPOSIT_FX_STALE").contains("rates changed"));
    }

    @Test void unexpectedFailuresDoNotLeakServerExceptionText() {
        String result = message("private unexpected diagnostic");
        assertFalse(result.contains("private unexpected diagnostic"));
        assertTrue(result.contains("cash remains protected"));
        assertEquals(result, UiText.render(BankingController.depositProblem(new IllegalArgumentException()), "en_gb"));
    }

    private static String message(String code) {
        String result = UiText.render(BankingController.depositProblem(new IllegalArgumentException(code)), "en_gb");
        assertFalse(result.contains(code));
        return result;
    }
}
