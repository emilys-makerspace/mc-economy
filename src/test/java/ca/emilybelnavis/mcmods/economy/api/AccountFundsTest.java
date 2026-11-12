package ca.emilybelnavis.mcmods.economy.api;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD;
import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.USD;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.AccountIdentity.Purpose;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity.Status;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountFundsTest {
  private static final PartyRef OWNER =
      new PartyRef(
          new WorldId(UUID.randomUUID()), new PartyRef.Player(new PlayerId(UUID.randomUUID())));

  @Test
  void postedSumsDisjointMinorUnitCategories() {
    var view = new AccountFunds(CAD, 7_000, 2_500, 500);
    assertEquals(2, view.version());
    assertEquals(10_000, view.postedMinorUnits());
    assertEquals(new Money(CAD, 10_000), view.posted());
    assertEquals(
        Long.MAX_VALUE, new AccountFunds(CAD, Long.MAX_VALUE - 2, 1, 1).postedMinorUnits());
    assertEquals(0, new AccountFunds(CAD, 0, 0, 0).postedMinorUnits());
  }

  @Test
  void acceptsOverdraftBalancesButRejectsNegativeReservationsAndOverflow() {
    assertEquals(-1, new AccountFunds(CAD, -1, 0, 0).postedMinorUnits());
    assertThrows(IllegalArgumentException.class, () -> new AccountFunds(CAD, 0, -1, 0));
    assertThrows(IllegalArgumentException.class, () -> new AccountFunds(CAD, 0, 0, -1));
    assertThrows(ArithmeticException.class, () -> new AccountFunds(CAD, Long.MAX_VALUE, 1, 0));
    assertThrows(ArithmeticException.class, () -> new AccountFunds(CAD, Long.MAX_VALUE - 1, 1, 1));
  }

  @Test
  void fundingChecksMetadataAndReportsUnavailableLedgerWithoutInferringZero() {
    var service = FundingEligibility.withoutLedger();
    var bank = account(Purpose.BANK, CAD, Status.OPEN);
    var closed = account(Purpose.BANK, CAD, Status.CLOSED);
    var trading = account(Purpose.TRADING, null, Status.OPEN);
    var amount = new Money(CAD, 1);

    assertEquals(FundingEligibility.Status.LEDGER_UNAVAILABLE, service.evaluate(bank, 2, amount));
    assertEquals(
        FundingEligibility.Status.LEDGER_UNAVAILABLE, service.evaluate(trading, 2, amount));
    assertEquals(FundingEligibility.Status.STALE, service.evaluate(bank, 1, amount));
    assertEquals(
        FundingEligibility.Status.CURRENCY_INELIGIBLE,
        service.evaluate(bank, 2, new Money(USD, 1)));
    assertEquals(
        FundingEligibility.Status.ACCOUNT_UNAVAILABLE, service.evaluate(closed, 2, amount));
    assertEquals(FundingEligibility.Status.ACCOUNT_UNAVAILABLE, service.evaluate(null, 2, amount));
    assertThrows(
        IllegalArgumentException.class, () -> service.evaluate(bank, 2, new Money(CAD, 0)));
    assertThrows(
        IllegalArgumentException.class, () -> service.evaluate(bank, 2, new Money(CAD, -1)));
    assertThrows(IllegalArgumentException.class, () -> service.evaluate(bank, 0, amount));
  }

  private static AccountIdentity account(Purpose purpose, EconomyCurrency currency, Status status) {
    return new AccountIdentity(
        new AccountId(UUID.randomUUID()), OWNER, purpose, currency, status, 2, false);
  }
}
