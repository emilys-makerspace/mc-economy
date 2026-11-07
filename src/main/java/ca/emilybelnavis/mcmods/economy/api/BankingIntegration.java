package ca.emilybelnavis.mcmods.economy.api;

import ca.emilybelnavis.mcmods.economy.foundation.PartyAuthorization;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.pricing.*;
import ca.emilybelnavis.mcmods.economy.loan.Loans;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Version-two server integration contract. Grants come from authenticated server context. */
public interface BankingIntegration {
    int VERSION = 2;
    /** Legacy global configured-policy access; does not infer a currency. */
    CompletionStage<Optional<PricingPolicy.Policy>> prime(PartyAuthorization.Grant grant);
    /** Explicit currency policy; empty before activation or while publication is incomplete. */
    CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes.Policy>> prime(
            PartyAuthorization.Grant grant, EconomyCurrency currency);
    /** Historical explicit-currency policy at a past/current trusted UTC instant. */
    CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes.Policy>> primeAt(
            PartyAuthorization.Grant grant, EconomyCurrency currency, long effectiveMillis);
    CompletionStage<Optional<WealthValuation.Snapshot>> valuation(PartyAuthorization.Grant grant);
    CompletionStage<Optional<ServicePricing.Quote>> feeQuote(PartyAuthorization.Grant grant,
            InstitutionCatalog.InstitutionId institution, ServicePricing.Service service, Money principal);
    /** Capture pricing/revisions from server context, then confirm using a sealed physical proof. */
    CompletionStage<Optional<ca.emilybelnavis.mcmods.economy.foundation.LedgerService.CreditDrawQuote>> quoteCreditDraw(
            PartyAuthorization.Grant grant, java.util.UUID token, ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy.Anchor anchor,
            java.util.UUID generation, Identifiers.AccountId funding, long amount, boolean purchase);
    /** Advances credit an owned BANK account. Physical cash remains a separately confirmed A5 withdrawal. */
    ca.emilybelnavis.mcmods.economy.journal.JournalService.Submission drawCredit(PartyAuthorization.Grant grant,
            Identifiers.OperationId operation, Identifiers.RequestId attempt,
            ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DrawRequest request, ca.emilybelnavis.mcmods.economy.credit.CreditDrawAdmission.Proof proof);
    /** Install only as the register COMMIT composer, with its exact frozen canonical credit binding. */
    ca.emilybelnavis.mcmods.economy.journal.RegisterSales.SettlementComposer creditCheckout(PartyAuthorization.Grant grant,
            ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DrawRequest request, ca.emilybelnavis.mcmods.economy.credit.CreditDrawAdmission.Proof proof);
    default int version() { return VERSION; }
    default Loans.Capabilities collateralCapabilities() { return Loans.capabilities(); }
}
