package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.card.IssuedCards;
import ca.emilybelnavis.mcmods.economy.credit.CreditFacilities;
import ca.emilybelnavis.mcmods.economy.fx.FxQuotes;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import java.util.*;

/** Reuses captured fee calculation; the target UUID is a facility, never a customer account. */
public final class CreditReplacementPricing {
  public static final String FACT = "economy:a6/credit-card-replacement-fee";
  private final CardReplacementPricing fees = new CardReplacementPricing();

  public IssuedCards.FeeQuote capture(
      JournalEngine.ReadView reads,
      PartyRef owner,
      InstitutionCatalog.InstitutionId issuer,
      UUID facility,
      AccountIdentity funding,
      FxQuotes.Quote fx,
      long now) {
    return fees.capture(reads, owner, issuer, new AccountId(facility), funding, fx, now);
  }

  public JournalEngine.Decision prepare(
      Journal.Intent intent,
      IssuedCards.FeeQuote quote,
      JournalEngine.ReadView reads,
      java.util.function.Function<FxQuotes.Quote, FxQuotes.Quote> requireFx,
      long now) {
    if (!intent.kind().equals(CreditFacilities.REPLACE))
      return new JournalEngine.Reject(new Journal.Result(1, "CREDIT_REPLACEMENT_REQUEST", ""));
    var fields = new TreeMap<>(intent.request().fields());
    fields.put("account", fields.get("facility"));
    fields.put("bank", fields.get("issuer"));
    var adapted =
        new Journal.Intent(
            intent.operationId(),
            intent.requestId(),
            intent.actor(),
            intent.kind(),
            new Journal.CanonicalRequest(1, fields),
            intent.economicUpdateId(),
            intent.correctionOf());
    var decision = fees.prepare(adapted, quote, reads, requireFx, now);
    if (!(decision instanceof JournalEngine.Commit commit)) return decision;
    return new JournalEngine.Commit(
        commit.records().stream()
            .map(
                r ->
                    r.type().equals(CardReplacementPricing.FACT)
                        ? new Journal.Record(FACT, 1, r.payload())
                        : r)
            .toList(),
        commit.result(),
        commit.projections());
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog.with(new RecordCatalog.Key(FACT, 1), CardReplacementPricing::validateFact);
  }
}
