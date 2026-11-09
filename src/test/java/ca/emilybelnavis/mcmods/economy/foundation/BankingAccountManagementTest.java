package ca.emilybelnavis.mcmods.economy.foundation;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BankingAccountManagementTest {
  private final PartyRef owner =
      new PartyRef(
          new WorldId(UUID.randomUUID()), new PartyRef.Player(new PlayerId(UUID.randomUUID())));

  @Test
  void openingOffersOnlyCurrenciesWithoutAnOpenBankAccount() {
    var primary = bank(CAD, true, AccountIdentity.Status.OPEN);
    var closed = bank(USD, false, AccountIdentity.Status.CLOSED);
    var trading =
        new AccountIdentity(
            new AccountId(UUID.randomUUID()),
            owner,
            AccountIdentity.Purpose.TRADING,
            null,
            AccountIdentity.Status.OPEN,
            1,
            false);
    var available = BankingAccounts.availableCurrencies(List.of(primary, closed, trading));
    assertFalse(available.contains(CAD));
    assertTrue(
        available.contains(USD), "A closed currency account can be reopened under a new identity");
    assertEquals(EconomyCurrency.all().size() - 1, available.size());
    assertThrows(UnsupportedOperationException.class, () -> available.add(CAD));
  }

  @Test
  void chfOpeningIsOfferedOnlyFromAPublishedRateSnapshot() {
    var primary = bank(CAD, true, AccountIdentity.Status.OPEN);
    var legacy = EnumSet.allOf(EconomyCurrency.class);
    legacy.remove(CHF);
    assertFalse(BankingAccounts.availableCurrencies(List.of(primary), legacy).contains(CHF));
    assertTrue(
        BankingAccounts.availableCurrencies(List.of(primary), EnumSet.allOf(EconomyCurrency.class))
            .contains(CHF));
    var secondary = bank(CHF, false, AccountIdentity.Status.OPEN);
    assertFalse(
        BankingAccounts.availableCurrencies(
                List.of(primary, secondary), EnumSet.allOf(EconomyCurrency.class))
            .contains(CHF));
    assertEquals(
        4, AccountIdentity.MAX_OPEN_BANK_ACCOUNTS, "The total includes the permanent primary");
  }

  @Test
  void chfExchangeInputsPreserveExactMinorUnitsWithoutChangingLegacyInputRules() {
    assertEquals(new Money(CHF, 5), BankingAccounts.exchangeAmount(CHF, CAD, "0.05"));
    assertEquals(new Money(CAD, 8), BankingAccounts.exchangeAmount(CAD, CHF, "0.08"));
    assertEquals(new Money(CAD, 100), BankingAccounts.exchangeAmount(CAD, USD, "1"));
    assertThrows(
        NumberFormatException.class, () -> BankingAccounts.exchangeAmount(CAD, USD, "0.08"));
    assertThrows(
        IllegalArgumentException.class, () -> BankingAccounts.exchangeAmount(CHF, CAD, "0.001"));
    assertThrows(ArithmeticException.class, () -> BankingAccounts.exchangeAmount(JPY, CHF, "0.05"));
    assertThrows(
        ArithmeticException.class,
        () -> BankingAccounts.exchangeAmount(CHF, CAD, "92233720368547758.08"));
  }

  @Test
  void primaryAccountCountsTowardTheFourAccountCap() {
    var primary = bank(CAD, true, AccountIdentity.Status.OPEN);
    var usd = bank(USD, false, AccountIdentity.Status.OPEN);
    var euro = bank(EUR, false, AccountIdentity.Status.OPEN);
    assertFalse(BankingAccounts.availableCurrencies(List.of(primary, usd)).isEmpty());
    assertFalse(BankingAccounts.availableCurrencies(List.of(primary, usd, euro)).isEmpty());
    assertTrue(
        BankingAccounts.availableCurrencies(
                List.of(primary, usd, euro, bank(GBP, false, AccountIdentity.Status.OPEN)))
            .isEmpty());
  }

  @Test
  void
      closureControlsProtectPrimaryWorldCurrencyAndReservedFundsAndRemainReadOnlyAwayFromTellers() {
    var usd = bank(USD, false, AccountIdentity.Status.OPEN);
    var balance = new AccountFunds(USD, 123400, 0, 0);
    assertTrue(BankingAccounts.mayClose(usd, CAD, balance, true));
    assertFalse(BankingAccounts.mayClose(usd, CAD, balance, false));
    assertFalse(BankingAccounts.mayClose(usd, USD, balance, true));
    assertFalse(
        BankingAccounts.mayClose(
            bank(CAD, true, AccountIdentity.Status.OPEN),
            USD,
            new AccountFunds(CAD, 100, 0, 0),
            true));
    assertFalse(
        BankingAccounts.mayClose(
            bank(USD, false, AccountIdentity.Status.CLOSED), CAD, balance, true));
    assertFalse(BankingAccounts.mayClose(usd, CAD, new AccountFunds(USD, 0, 100, 0), true));
    assertFalse(BankingAccounts.mayClose(usd, CAD, new AccountFunds(USD, 0, 0, 100), true));
  }

  private AccountIdentity bank(
      EconomyCurrency currency, boolean primary, AccountIdentity.Status status) {
    return new AccountIdentity(
        new AccountId(UUID.randomUUID()),
        owner,
        AccountIdentity.Purpose.BANK,
        currency,
        status,
        1,
        primary);
  }
}
