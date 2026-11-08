package ca.emilybelnavis.mcmods.economy.recovery;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.account.AccountLifecycle;
import ca.emilybelnavis.mcmods.economy.clock.EconomicRecord;
import ca.emilybelnavis.mcmods.economy.foundation.InspectionAudit;
import ca.emilybelnavis.mcmods.economy.fx.FxFacts;
import ca.emilybelnavis.mcmods.economy.journal.FxSettlement;
import ca.emilybelnavis.mcmods.economy.journal.Holds;
import ca.emilybelnavis.mcmods.economy.journal.Custody;
import ca.emilybelnavis.mcmods.economy.journal.CashIssuance;
import ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy;
import ca.emilybelnavis.mcmods.economy.journal.Deposits;
import ca.emilybelnavis.mcmods.economy.journal.Withdrawals;
import ca.emilybelnavis.mcmods.economy.journal.RegisterSales;
import ca.emilybelnavis.mcmods.economy.journal.RegisterSaleSettlement;
import ca.emilybelnavis.mcmods.economy.journal.DepositReturns;
import ca.emilybelnavis.mcmods.economy.journal.MerchantPayments;
import ca.emilybelnavis.mcmods.economy.journal.RebaseSettlement;
import ca.emilybelnavis.mcmods.economy.storage.LedgerProjection;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.util.*;
import java.util.function.Consumer;

/** Explicit historical decoders. Missing types/versions fail closed; never silently skip facts. */
public final class RecordCatalog {
    public record Key(String type, int version) {}
    private final Map<Key, Consumer<String>> validators;
    public RecordCatalog(Map<Key, Consumer<String>> validators) { this.validators = Map.copyOf(validators); }
    public static RecordCatalog core() {
        var catalog = new RecordCatalog(Map.of(new Key(EconomicRecord.TYPE, 1), EconomicRecord::decode));
        catalog = ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityFacts.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.simulation.window.WindowFacts.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.simulation.model.ModelFacts.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.trade.market.MarketFacts.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.trade.stock.StockFacts.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.journal.NpcSettlement.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.trade.settlement.NpcFacts.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.trade.cash.NpcCashFacts.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimeFacts.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.simulation.plan.ActivationModelPlans.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.clock.FixedRetirements.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.fx.EffectiveFxFacts.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.trade.settlement.NpcInputReturns.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.simulation.SimulationLifecycle.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.valuation.ValuationStore.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.trade.pricing.TradeSources.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.trade.settlement.ReferenceNpcFacts.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.trade.settlement.ReferenceInputReturns.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.trade.cash.ReferenceCashFacts.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.simulation.macro.MacroActivation.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.simulation.macro.MacroFacts.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.simulation.macro.MacroPlans.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.trade.catalog.CatalogFacts.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.trade.catalog.VillageMarketSource.register(catalog);
        catalog = ca.emilybelnavis.mcmods.economy.clock.DeadlineDispatcher.register(InspectionAudit.register(FxFacts.register(catalog)));
        catalog = ca.emilybelnavis.mcmods.economy.pricing.ValuationSources.register(ca.emilybelnavis.mcmods.economy.pricing.ServicePricing.register(ca.emilybelnavis.mcmods.economy.interest.DepositYield.register(catalog)));
        catalog=ca.emilybelnavis.mcmods.economy.autopay.Autopay.register(catalog);
        catalog=catalog.with(new Key(ca.emilybelnavis.mcmods.economy.journal.BankStaffing.FACT,1),ca.emilybelnavis.mcmods.economy.journal.BankStaffing::validateFact);
        catalog=catalog.with(new Key(ca.emilybelnavis.mcmods.economy.journal.BranchPlacement.FACT,1),ca.emilybelnavis.mcmods.economy.journal.BranchPlacement::validateFact);
        catalog=catalog.with(new Key(ca.emilybelnavis.mcmods.economy.journal.KioskUpgrades.FACT,1),ca.emilybelnavis.mcmods.economy.journal.KioskUpgrades::validateFact);
        catalog=catalog.with(new Key(ca.emilybelnavis.mcmods.economy.journal.Branches.FACT,1),ca.emilybelnavis.mcmods.economy.journal.Branches::validateEvent)
                .with(new Key(ca.emilybelnavis.mcmods.economy.journal.Branches.PLAN_PART,1),ca.emilybelnavis.mcmods.economy.journal.Branches::validatePart);
        catalog=ca.emilybelnavis.mcmods.economy.card.IssuedCards.register(catalog);
        catalog=ca.emilybelnavis.mcmods.economy.credit.CreditProfiles.register(catalog);
        catalog=ca.emilybelnavis.mcmods.economy.collection.CollectionPresence.register(catalog);
        catalog=ca.emilybelnavis.mcmods.economy.credit.CreditReporting.register(catalog);
        catalog=ca.emilybelnavis.mcmods.economy.debt.DebtLegacyReconciliation.register(catalog);
        catalog=ca.emilybelnavis.mcmods.economy.debt.DebtPayments.register(ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle.register(catalog));
        catalog=ca.emilybelnavis.mcmods.economy.loan.Loans.register(ca.emilybelnavis.mcmods.economy.loan.LoanTreasury.register(ca.emilybelnavis.mcmods.economy.loan.LoanDisbursementFx.register(ca.emilybelnavis.mcmods.economy.loan.LoanRepaymentFx.register(catalog))));
        catalog=ca.emilybelnavis.mcmods.economy.credit.CreditAuditFacts.register(catalog);
        catalog=ca.emilybelnavis.mcmods.economy.credit.CreditRepaymentFx.register(ca.emilybelnavis.mcmods.economy.foundation.CreditReplacementPricing.register(catalog));
        catalog=ca.emilybelnavis.mcmods.economy.credit.CreditTreasury.register(ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.register(catalog));
        catalog=catalog.with(new Key(ca.emilybelnavis.mcmods.economy.foundation.CardReplacementPricing.FACT,1),ca.emilybelnavis.mcmods.economy.foundation.CardReplacementPricing::validateFact);
        return ca.emilybelnavis.mcmods.economy.institution.InstitutionCapital.register(ca.emilybelnavis.mcmods.economy.institution.BankProviders.register(AccountLifecycle.register(AccountCatalog.register(catalog))))
                .with(new Key("economy:posting", 1), LedgerProjection::validatePosting)
                .with(new Key("economy:posting", 2), LedgerProjection::validatePostingV2)
                .with(new Key("economy:posting", 3), LedgerProjection::validatePostingV3)
                .with(new Key("economy:posting", 4), LedgerProjection::validatePostingV4)
                .with(new Key(Holds.FACT, 1), Holds::validateFact)
                .with(new Key(Holds.FACT, 2), Holds::validateFactV2)
                .with(new Key(Custody.FACT, 1), Custody::validateFact)
                .with(new Key(CashIssuance.FACT, 1), CashIssuance::validateFact)
                .with(new Key(TerminalPolicy.FACT, 1), TerminalPolicy::validateFact)
                .with(new Key(ca.emilybelnavis.mcmods.economy.journal.TerminalBanking.FACT, 1), ca.emilybelnavis.mcmods.economy.journal.TerminalBanking::validateFact)
                .with(new Key(Deposits.FACT, 1), Deposits::validateFact)
                .with(new Key(Deposits.FACT, 2), Deposits::validateFactV2)
                .with(new Key(Deposits.FACT, 3), Deposits::validateFactV3)
                .with(new Key(Withdrawals.FACT, 1), Withdrawals::validateFact)
                .with(new Key(Withdrawals.FACT, 2), Withdrawals::validateFactV2)
                .with(new Key(Withdrawals.FACT, 3), Withdrawals::validateFactV3)
                .with(new Key(RegisterSales.FACT, 1), RegisterSales::validateFact)
                .with(new Key(RegisterSaleSettlement.RETURN, 1), RegisterSaleSettlement::validateReturn)
                .with(new Key(DepositReturns.FACT, 1), DepositReturns::validateFact)
                .with(new Key(MerchantPayments.FACT, 1), MerchantPayments::validateFact)
                .with(new Key(FxSettlement.FACT, 1), FxSettlement::validateFact)
                .with(new Key(FxSettlement.FACT, 2), FxSettlement::validateFactV2)
                .with(new Key(FxSettlement.FACT, 3), FxSettlement::validateFactV3)
                .with(new Key(RebaseSettlement.FACT, 1), RebaseSettlement::validateFact)
                .with(new Key(RebaseSettlement.MAPPING, 1), RebaseSettlement::validateMapping);
    }
    public RecordCatalog with(Key key, Consumer<String> validator) {
        var copy = new HashMap<>(validators); if (copy.putIfAbsent(key, validator) != null) throw new IllegalArgumentException("Duplicate decoder");
        return new RecordCatalog(copy);
    }
    public void validate(String type, int version, String payload) {
        var decoder = validators.get(new Key(type, version));
        if (decoder == null) throw new StorageException(StorageException.Kind.SCHEMA, "Unknown committed record: " + type + " v" + version);
        try { decoder.accept(payload); }
        catch (RuntimeException e) { throw new StorageException(StorageException.Kind.CORRUPTION_IO, "Invalid committed record: " + type, e); }
    }
}
