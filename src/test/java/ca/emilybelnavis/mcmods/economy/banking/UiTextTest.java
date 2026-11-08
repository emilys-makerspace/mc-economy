package ca.emilybelnavis.mcmods.economy.banking;

import ca.emilybelnavis.mcmods.economy.api.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UiTextTest {
    @Test void exactDisplayedMoneyDoesNotBecomeLocalizedFinancialInputOrAlterCanonicalCursorText() {
        var value = new Money(EconomyCurrency.CAD, 123456);
        String deferred = UiText.money(value);
        String french = UiText.render(deferred, "fr_ca");
        assertEquals("1234,56CAD", french.replace("\u00a0", "").replace("\u202f", "").replace(" ", ""));
        assertEquals("100,05 CHF", UiText.render(UiText.money(new Money(EconomyCurrency.CHF, 10005)), "fr_fr"));
        assertEquals("25 JPY", UiText.render(UiText.money(new Money(EconomyCurrency.JPY, 25)), "fr_ca"));
        assertEquals(french, UiText.render(UiText.mapText(deferred, ignored -> "redacted"), "fr_ca"), "Redaction cannot change typed minor units");
        assertEquals("after 1000 -1", UiText.render("after 1000 -1", "fr_ca"), "Canonical cursor strings stay ungrouped");
        assertEquals(125, BankingWithdrawals.parse("CAD 1.25").cash().minorUnits());
        assertThrows(IllegalArgumentException.class, () -> BankingWithdrawals.parse("CAD 1,25"));
        assertThrows(IllegalArgumentException.class, () -> BankingWithdrawals.parse("CAD 1 000.25"));
    }

    @Test void publicationResolvesMoneyBeforeWrappingAndRefusesAnIncompleteView() {
        String source = "a".repeat(250) + UiText.money(new Money(EconomyCurrency.CAD, 125)) + "b".repeat(250);
        var view = UiPublication.render(List.of(source), "Ready", List.of("Confirm"), "fr_ca", 32);
        assertEquals(UiText.render(source, "fr_ca"), String.join("", view.lines()));
        assertTrue(view.lines().stream().allMatch(line -> line.length() <= 256));
        assertFalse(String.join("", view.lines()).contains("\u001e"));
        assertThrows(IllegalArgumentException.class, () -> UiPublication.render(List.of(source), "Ready", List.of("Confirm"), "fr_ca", 1));
    }

    @Test void longLocalizedReviewMovesCompleteTermsIntoBodyWithoutBreakingUnicode() {
        String terms = "Conditions : " + "é".repeat(226) + "💰" + " — " + UiText.money(new Money(EconomyCurrency.CAD, 12345))
                + " ; " + "fin".repeat(100);
        var view = UiPublication.render(List.of(""), terms, List.of("Confirmer"), "fr_ca", 8);
        assertEquals("", view.lines().getFirst());
        assertEquals(UiText.render(terms, "fr_ca"), String.join("", view.lines()));
        assertEquals(UiText.render(UiText.t("ui.common.read_complete_terms"), "fr_ca"), view.status());
        assertEquals(List.of("Confirmer"), view.labels());
        for (String line : view.lines()) {
            assertTrue(line.length() <= 240);
            if (!line.isEmpty()) {
                assertFalse(Character.isLowSurrogate(line.charAt(0)));
                assertFalse(Character.isHighSurrogate(line.charAt(line.length() - 1)));
            }
        }
    }

    @Test void localizedReviewRejectsUtf8OverflowAndOversizedControlsInsteadOfTruncating() {
        var lines = java.util.Collections.nCopies(17, "界".repeat(240));
        assertThrows(IllegalArgumentException.class,
                () -> UiPublication.render(lines, "Prêt", List.of("Confirmer"), "fr_ca", 32));
        assertThrows(IllegalArgumentException.class,
                () -> UiPublication.render(List.of("Conditions complètes"), "Prêt", List.of("é".repeat(129)), "fr_ca", 32));
    }

}
