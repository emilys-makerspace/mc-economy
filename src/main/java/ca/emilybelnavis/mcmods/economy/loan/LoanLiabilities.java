package ca.emilybelnavis.mcmods.economy.loan;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.WealthValuation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Required loan debt source includes earned unposted segments without treating receivables as cash.
 */
public final class LoanLiabilities implements WealthValuation.Source {
  public String id() {
    return "economy:loan-liabilities";
  }

  public WealthValuation.Kind kind() {
    return WealthValuation.Kind.LIABILITY;
  }

  public WealthValuation.SourceSnapshot read(
      JournalEngine.ReadView reads, WorldId world, PlayerId owner, Instant now) {
    long revision = 0;
    try {
      var loans = Loans.owned(reads, new PartyRef(world, new PartyRef.Player(owner)));
      if (loans.size() > Loans.MAX_LIVE) return WealthValuation.SourceSnapshot.unavailable(0, now);
      var posted = new EnumMap<EconomyCurrency, Long>(EconomyCurrency.class);
      var exact = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
      for (var loan : loans) {
        revision = Math.max(revision, loan.revision());
        var s = loan.servicing();
        if (loan.status() == Loans.Status.ACTIVE) {
          if (now.toEpochMilli() < s.lastAccrued() || now.toEpochMilli() > loan.nextMonth())
            return WealthValuation.SourceSnapshot.unavailable(revision, now);
          s = LoanServicing.accrue(s, now.toEpochMilli());
        }
        posted.merge(loan.currency(), loan.owedMinor(), Math::addExact);
        exact.merge(loan.currency(), s.exactDebtMajor(), BigDecimal::add);
      }
      var borrower = new InstitutionCatalog.InstitutionId("economy:loan-borrower/" + owner.value());
      var components = new ArrayList<WealthValuation.Component>();
      String authority =
          loans.stream()
              .map(l -> l.id() + "/" + l.revision() + "/" + l.status() + "/" + l.servicing())
              .collect(java.util.stream.Collectors.joining("\n"));
      for (var currency : EconomyCurrency.all()) {
        var account =
            FinancialAccounts.id(world, borrower, FinancialAccounts.Role.LIABILITY, currency);
        long debt = Math.negateExact(Settlement.posted(reads, account, currency));
        if (debt < 0 || debt != posted.getOrDefault(currency, 0L))
          return WealthValuation.SourceSnapshot.unavailable(revision, now);
        revision = Math.max(revision, WealthValuation.ledgerRevision(reads, account));
        var amount = exact.getOrDefault(currency, BigDecimal.ZERO);
        if (amount.signum() < 0) return WealthValuation.SourceSnapshot.unavailable(revision, now);
        if (amount.signum() > 0)
          components.add(new WealthValuation.Component("loan-debt:" + currency, currency, amount));
      }
      return new WealthValuation.SourceSnapshot(
          true,
          revision,
          "loan-v1:" + WealthValuation.digest(authority),
          Instant.EPOCH,
          components);
    } catch (ArithmeticException | IllegalArgumentException | IllegalStateException unavailable) {
      return WealthValuation.SourceSnapshot.unavailable(revision, now);
    }
  }
}
