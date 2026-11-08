package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.cash.CashCatalog;
import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankingSettingsTest {
    private static final List<CashCatalog.Denomination> CATALOG = List.of(
            new CashCatalog.Denomination("cad_bill_20", EconomyCurrency.CAD, 2_000, true, null),
            new CashCatalog.Denomination("cad_coin_25", EconomyCurrency.CAD, 25, false, null),
            new CashCatalog.Denomination("usd_bill_20", EconomyCurrency.USD, 2_000, true, null));
    private static CashCatalog.Denomination denomination(String id) {
        return CATALOG.stream().filter(value -> ("economy:" + value.id()).equals(id)).findFirst().orElseThrow();
    }

    @Test void lifecycleRejectionsExplainTheAccountLimitAndResolution() {
        assertTrue(UiText.render(BankingAccounts.failureMessage("BANK_CAP_REACHED"), "en_gb").contains("4 open accounts"));
        assertTrue(UiText.render(BankingAccounts.failureMessage("BANK_CAP_REACHED"), "en_gb").contains("bank branch"));
        assertTrue(UiText.render(BankingAccounts.failureMessage("BANK_CURRENCY_EXISTS"), "en_gb").contains("already have"));
        assertTrue(UiText.render(BankingAccounts.failureMessage("ACCOUNT_PROTECTED"), "en_gb").contains("cannot be closed"));
        assertTrue(UiText.render(BankingAccounts.failureMessage("ACTIVE_HOLD"), "en_gb").contains("reserved"));
        assertTrue(UiText.render(BankingAccounts.failureMessage("UNRESOLVED_OBLIGATION"), "en_gb").contains("deliveries"));
    }

    @Test void allCashMeansOnlyAcceptedCurrenciesAndAtmBills() {
        var atm = BankingAccounts.parseSettings("BOTH;CAD,USD;all", TerminalPolicy.Kind.ATM, CATALOG);
        assertEquals(Set.of(EconomyCurrency.CAD, EconomyCurrency.USD), atm.currencies());
        assertFalse(atm.denominations().isEmpty());
        for (var id : atm.denominations()) {
            var denomination = denomination(id);
            assertTrue(denomination.bill(), "ATM may not enable a coin through all");
            assertTrue(atm.currencies().contains(denomination.currency()));
        }
        var teller = BankingAccounts.parseSettings("DEPOSIT;CAD;all", TerminalPolicy.Kind.TELLER, CATALOG);
        assertTrue(teller.denominations().stream().map(id -> denomination(id)).anyMatch(item -> !item.bill()));
        assertTrue(teller.denominations().stream().map(id -> denomination(id)).allMatch(item -> item.currency() == EconomyCurrency.CAD));
    }

    @Test void simplePlayerFacingSettingsMapToSafeFullAllowlists() {
        var both = BankingAccounts.parseSettings("BOTH CAD", TerminalPolicy.Kind.ATM, CATALOG);
        assertEquals(TerminalPolicy.Mode.BOTH, both.mode());
        assertEquals(Set.of(EconomyCurrency.CAD), both.currencies());
        assertEquals(Set.of("economy:cad_bill_20"), both.denominations());
        assertEquals(TerminalPolicy.Settings.disabled(), BankingAccounts.parseSettings("OFF", TerminalPolicy.Kind.TELLER, CATALOG));
    }

    @Test void explicitCashSelectionCannotBypassKindCurrencyOrCatalog() {
        assertThrows(IllegalArgumentException.class,
                () -> BankingAccounts.parseSettings("BOTH;CAD;economy:cad_coin_25", TerminalPolicy.Kind.ATM, CATALOG));
        assertThrows(IllegalArgumentException.class,
                () -> BankingAccounts.parseSettings("BOTH;CAD;economy:usd_bill_20", TerminalPolicy.Kind.TELLER, CATALOG));
        assertThrows(IllegalArgumentException.class,
                () -> BankingAccounts.parseSettings("BOTH;CAD;economy:missing_cash_item", TerminalPolicy.Kind.TELLER, CATALOG));
        assertThrows(IllegalArgumentException.class,
                () -> BankingAccounts.parseSettings("BOTH;CAD;minecraft:diamond", TerminalPolicy.Kind.TELLER, CATALOG));
        var explicit = BankingAccounts.parseSettings("WITHDRAW;CAD;cad_bill_20", TerminalPolicy.Kind.ATM, CATALOG);
        assertEquals(Set.of("economy:cad_bill_20"), explicit.denominations());
    }

    @Test void registerCashModesStayDisabledAndClearAllowlists() {
        assertEquals(TerminalPolicy.Settings.disabled(),
                BankingAccounts.parseSettings("DISABLED;CAD;all", TerminalPolicy.Kind.REGISTER, CATALOG));
        assertEquals(TerminalPolicy.Settings.disabled(),
                BankingAccounts.parseSettings("DISABLED;;", TerminalPolicy.Kind.ATM, CATALOG));
        assertThrows(IllegalArgumentException.class,
                () -> BankingAccounts.parseSettings("BOTH;CAD;all", TerminalPolicy.Kind.REGISTER, CATALOG));
        assertThrows(IllegalArgumentException.class,
                () -> BankingAccounts.parseSettings("DEPOSIT;CAD;all", TerminalPolicy.Kind.REGISTER, CATALOG));
    }

    @Test void malformedSettingsCannotCreateAnEnabledEmptyAllowlist() {
        assertThrows(IllegalArgumentException.class,
                () -> BankingAccounts.parseSettings("BOTH;;all", TerminalPolicy.Kind.ATM, CATALOG));
        assertThrows(IllegalArgumentException.class,
                () -> BankingAccounts.parseSettings("BOTH;CAD;", TerminalPolicy.Kind.ATM, CATALOG));
        assertThrows(IllegalArgumentException.class,
                () -> BankingAccounts.parseSettings("BOTH;CAD;all;extra", TerminalPolicy.Kind.ATM, CATALOG));
        assertThrows(IllegalArgumentException.class,
                () -> BankingAccounts.parseSettings("BOTH;NOT;all", TerminalPolicy.Kind.TELLER, CATALOG));
    }
}
