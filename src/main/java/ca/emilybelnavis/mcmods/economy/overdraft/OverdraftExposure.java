package ca.emilybelnavis.mcmods.economy.overdraft;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.credit.CreditUnderwriting;
import ca.emilybelnavis.mcmods.economy.debt.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.WealthValuation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Outstanding bank lending and frozen overdraft collections, counted once per account. */
public final class OverdraftExposure implements WealthValuation.Source {
  public static long exposure(
      JournalEngine.ReadView reads,
      PartyRef owner,
      EconomyCurrency reference,
      CreditUnderwriting.FxSnapshot fx) {
    var player = ((PartyRef.Player) owner.owner()).id();
    var rows =
        reads.query(
            "SELECT a.currency,COALESCE(l.minor_units,0) AS balance,COALESCE(o.limit_minor,0) AS allowance FROM account_catalog a LEFT JOIN ledger_balances l ON l.account_id=a.account_id AND l.currency=a.currency LEFT JOIN overdraft_accounts o ON o.account_id=a.account_id WHERE a.owner_id=? AND a.purpose='BANK' AND a.status='OPEN' ORDER BY a.account_id LIMIT 5",
            List.of(player.value().toString()),
            5);
    if (rows.size() > 4) throw new IllegalStateException("Overdraft exposure bound");
    long total = 0;
    for (var row : rows) {
      long balance = Long.parseLong(row.get("balance"));
      long amount =
          Math.max(
              Long.parseLong(row.get("allowance")), balance < 0 ? Math.negateExact(balance) : 0);
      if (amount > 0)
        total =
            Math.addExact(
                total,
                CreditUnderwriting.exposureToWorld(
                        new Money(EconomyCurrency.valueOf(row.get("currency")), amount),
                        reference,
                        fx)
                    .minorUnits());
    }
    for (var row :
        reads.query(
            "SELECT currency,SUM(principal_minor+interest_minor) AS debt FROM debt_cases WHERE world_id=? AND owner_id=? AND kind='OVERDRAFT' AND collectability='COLLECTIBLE' GROUP BY currency",
            List.of(owner.world().value().toString(), player.value().toString()),
            11)) {
      long amount = Long.parseLong(row.get("debt"));
      if (amount > 0)
        total =
            Math.addExact(
                total,
                CreditUnderwriting.exposureToWorld(
                        new Money(EconomyCurrency.valueOf(row.get("currency")), amount),
                        reference,
                        fx)
                    .minorUnits());
    }
    return total;
  }

  public static boolean blocked(JournalEngine.ReadView reads, PartyRef owner, long now) {
    var player = ((PartyRef.Player) owner.owner()).id();
    return !reads
        .query(
            "SELECT case_id FROM debt_cases WHERE world_id=? AND owner_id=? AND kind='OVERDRAFT' AND collectability='COLLECTIBLE' AND payment<>'SETTLED' AND acknowledged_at<=? AND collection_expires>? LIMIT 1",
            List.of(
                owner.world().value().toString(),
                player.value().toString(),
                Long.toString(now),
                Long.toString(now)),
            1)
        .isEmpty();
  }

  public String id() {
    return "economy:overdraft-collection-liabilities";
  }

  public WealthValuation.Kind kind() {
    return WealthValuation.Kind.LIABILITY;
  }

  public WealthValuation.SourceSnapshot read(
      JournalEngine.ReadView reads, WorldId world, PlayerId owner, Instant now) {
    var totals = new EnumMap<EconomyCurrency, Long>(EconomyCurrency.class);
    long revision = 0;
    for (var row :
        reads.query(
            "SELECT currency,SUM(principal_minor+interest_minor) AS debt,MAX(revision) AS revision FROM debt_cases WHERE world_id=? AND owner_id=? AND kind='OVERDRAFT' GROUP BY currency",
            List.of(world.value().toString(), owner.value().toString()),
            11)) {
      totals.put(EconomyCurrency.valueOf(row.get("currency")), Long.parseLong(row.get("debt")));
      revision = Math.max(revision, Long.parseLong(row.get("revision")));
    }
    var components = new ArrayList<WealthValuation.Component>();
    var borrower =
        new InstitutionCatalog.InstitutionId("economy:overdraft-borrower/" + owner.value());
    for (var currency : EconomyCurrency.all()) {
      long debt =
          Math.negateExact(
              Settlement.posted(
                  reads,
                  FinancialAccounts.id(world, borrower, FinancialAccounts.Role.LIABILITY, currency),
                  currency));
      if (debt != totals.getOrDefault(currency, 0L) || debt < 0)
        return WealthValuation.SourceSnapshot.unavailable(revision, now);
      if (debt > 0)
        components.add(
            new WealthValuation.Component(
                "overdraft-collections:" + currency,
                currency,
                BigDecimal.valueOf(debt, currency.fractionDigits())));
    }
    return new WealthValuation.SourceSnapshot(
        true, revision, "overdraft-collection-ledger-v1", Instant.EPOCH, components);
  }
}
