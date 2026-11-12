package ca.emilybelnavis.mcmods.economy.api;

import ca.emilybelnavis.mcmods.economy.foundation.PartyAuthorization;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.loan.Loans;
import ca.emilybelnavis.mcmods.economy.pricing.*;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Version-three server integration contract. Grants come from authenticated server context. */
public interface BankingIntegration {
  int VERSION = 3;

  /** Legacy global configured-policy access; does not infer a currency. */
  CompletionStage<Optional<PricingPolicy.Policy>> prime(PartyAuthorization.Grant grant);

  /** Explicit currency policy; empty before activation or while publication is incomplete. */
  CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes.Policy>>
      prime(PartyAuthorization.Grant grant, EconomyCurrency currency);

  /** Historical explicit-currency policy at a past/current trusted UTC instant. */
  CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes.Policy>>
      primeAt(PartyAuthorization.Grant grant, EconomyCurrency currency, long effectiveMillis);

  CompletionStage<Optional<WealthValuation.Snapshot>> valuation(PartyAuthorization.Grant grant);

  CompletionStage<Optional<ServicePricing.Quote>> feeQuote(
      PartyAuthorization.Grant grant,
      InstitutionCatalog.InstitutionId institution,
      ServicePricing.Service service,
      Money principal);

  /** Capture pricing/revisions from server context, then confirm using a sealed physical proof. */
  CompletionStage<
          Optional<ca.emilybelnavis.mcmods.economy.foundation.LedgerService.CreditDrawQuote>>
      quoteCreditDraw(
          PartyAuthorization.Grant grant,
          java.util.UUID token,
          ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy.Anchor anchor,
          java.util.UUID generation,
          Identifiers.AccountId funding,
          long amount,
          boolean purchase);

  /**
   * Advances credit an owned BANK account. Physical cash remains a separately confirmed A5
   * withdrawal.
   */
  ca.emilybelnavis.mcmods.economy.journal.JournalService.Submission drawCredit(
      PartyAuthorization.Grant grant,
      Identifiers.OperationId operation,
      Identifiers.RequestId attempt,
      ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DrawRequest request,
      ca.emilybelnavis.mcmods.economy.credit.CreditDrawAdmission.Proof proof);

  /**
   * Trusted server integration override of contractual credit headroom. This flag must never be
   * accepted from a client packet. The physical proof and all other risk/authority checks remain.
   */
  ca.emilybelnavis.mcmods.economy.journal.JournalService.Submission drawCredit(
      PartyAuthorization.Grant grant,
      Identifiers.OperationId operation,
      Identifiers.RequestId attempt,
      ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DrawRequest request,
      ca.emilybelnavis.mcmods.economy.credit.CreditDrawAdmission.Proof proof,
      boolean allowOverLimit);

  /**
   * Install only as the register COMMIT composer, with its exact frozen canonical credit binding.
   */
  ca.emilybelnavis.mcmods.economy.journal.RegisterSales.SettlementComposer creditCheckout(
      PartyAuthorization.Grant grant,
      ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DrawRequest request,
      ca.emilybelnavis.mcmods.economy.credit.CreditDrawAdmission.Proof proof);

  /**
   * Trusted over-limit variant. Freeze request.request(allowOverLimit) into the register's
   * credit_request binding; ordinary checkout callers retain the unchanged overload above.
   */
  ca.emilybelnavis.mcmods.economy.journal.RegisterSales.SettlementComposer creditCheckout(
      PartyAuthorization.Grant grant,
      ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DrawRequest request,
      ca.emilybelnavis.mcmods.economy.credit.CreditDrawAdmission.Proof proof,
      boolean allowOverLimit);

  /**
   * Bank debit authority must be chosen by trusted integration code, never copied from a packet.
   * PRE_AUTHORIZED preserves a declined debit while charging its applicable insufficient-funds fee.
   * INTEGRATION_OVERRIDE permits spending beyond the overdraft allowance.
   */
  ca.emilybelnavis.mcmods.economy.journal.JournalService.Submission debitAccount(
      PartyAuthorization.Grant grant,
      Identifiers.OperationId operation,
      Identifiers.RequestId attempt,
      Identifiers.AccountId source,
      Identifiers.AccountId destination,
      Money amount,
      ca.emilybelnavis.mcmods.economy.overdraft.Overdrafts.DebitKind authority);

  // Integration mods rely on both VERSION and version().
  @SuppressWarnings("java:S1845")
  default int version() {
    return VERSION;
  }

  default Loans.Capabilities collateralCapabilities() {
    return Loans.capabilities();
  }
}
