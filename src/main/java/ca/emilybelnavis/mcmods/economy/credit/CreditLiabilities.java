package ca.emilybelnavis.mcmods.economy.credit;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.pricing.WealthValuation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Required authoritative liability source; receiving a draw cannot manufacture net worth. */
public final class CreditLiabilities implements WealthValuation.Source {
  public String id() {
    return "economy:credit-facility-liabilities";
  }

  public WealthValuation.Kind kind() {
    return WealthValuation.Kind.LIABILITY;
  }

  public WealthValuation.SourceSnapshot read(
      JournalEngine.ReadView reads, WorldId world, PlayerId owner, Instant now) {
    var rows =
        reads.query(
            "SELECT currency,SUM(debt_minor) AS debt,MAX(revision) AS rev FROM credit_facilities WHERE world_id=? AND owner_id=? GROUP BY currency ORDER BY currency",
            List.of(world.value().toString(), owner.value().toString()),
            11);
    var debtByCurrency = new EnumMap<EconomyCurrency, Long>(EconomyCurrency.class);
    long revision = 0;
    for (var row : rows) {
      var currency = EconomyCurrency.valueOf(row.get("currency"));
      long debt = Long.parseLong(row.get("debt"));
      if (debt < 0) throw new IllegalStateException("Negative credit liability");
      revision = Math.max(revision, Long.parseLong(row.get("rev")));
      debtByCurrency.put(currency, debt);
    }
    var components = new ArrayList<WealthValuation.Component>();
    var borrower =
        new ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.InstitutionId(
            "economy:borrower/" + owner.value());
    for (var currency : EconomyCurrency.all()) {
      var id =
          ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts.id(
              world,
              borrower,
              ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts.Role.LIABILITY,
              currency);
      long debt =
          Math.negateExact(
              ca.emilybelnavis.mcmods.economy.journal.Settlement.posted(reads, id, currency));
      if (debt < 0 || debt != debtByCurrency.getOrDefault(currency, 0L))
        return WealthValuation.SourceSnapshot.unavailable(revision, now);
      revision = Math.max(revision, WealthValuation.ledgerRevision(reads, id));
      if (debt > 0)
        components.add(
            new WealthValuation.Component(
                "credit-debt:" + currency,
                currency,
                BigDecimal.valueOf(debt)
                    .divide(BigDecimal.valueOf(currency.minorUnitsPerMajor()))));
    }
    return new WealthValuation.SourceSnapshot(
        true, revision, "credit-ledger-v1", Instant.EPOCH, components);
  }
}
