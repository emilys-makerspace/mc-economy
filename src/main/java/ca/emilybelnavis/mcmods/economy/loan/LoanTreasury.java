package ca.emilybelnavis.mcmods.economy.loan;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.credit.CreditTreasury;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;

/**
 * Temporary origination funding reservation; the loan commit disburses it with one posting overlay.
 */
public final class LoanTreasury {
  public static final String FACT = "economy:a6/loan-treasury";

  private LoanTreasury() {}

  public static JournalEngine.Decision prepare(
      JournalEngine.ReadView reads,
      WorldId world,
      InstitutionCatalog.InstitutionId lender,
      EconomyCurrency currency,
      long amount,
      long effective) {
    var decision = CreditTreasury.reserve(reads, world, lender, currency, amount, effective);
    if (!(decision instanceof JournalEngine.Commit commit)) return decision;
    return new JournalEngine.Commit(
        commit.records().stream()
            .map(
                r ->
                    r.type().equals(CreditTreasury.FACT)
                        ? new Journal.Record(FACT, 1, r.payload())
                        : r)
            .toList(),
        commit.result(),
        commit.projections());
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog.with(new RecordCatalog.Key(FACT, 1), CreditTreasury::validateLoanFunding);
  }
}
