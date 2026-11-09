package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCapital;
import ca.emilybelnavis.mcmods.economy.storage.LedgerProjection;
import java.util.*;

/** One-time equity-backed seed. Only InstitutionCapital owns its durable admission and cursor. */
public final class CapitalPosting {
  private CapitalPosting() {}

  public static InstitutionCapital.FundingPoster poster() {
    return new InstitutionCapital.FundingPoster() {
      public JournalEngine.Commit issue(
          JournalEngine.ReadView reads, InstitutionCapital.Opening o) {
        return prepare(
            reads,
            List.of(
                leg(o.treasuryCash(), o.totalServerMinor()),
                leg(o.treasuryEquity(), -o.totalServerMinor())),
            PostingBatch.Authority.CAPITAL_ISSUE,
            List.of(o.treasuryCash(), o.treasuryEquity(), o.treasuryRounding()));
      }

      public JournalEngine.Commit allocate(
          JournalEngine.ReadView reads, InstitutionCapital.Allocation a) {
        // Move cash through A4 clearing, then recognize the same capital contribution as equity.
        // These separately balanced entries cancel clearing without treating claims as liquidity.
        var legs = new ArrayList<PostingBatch.Leg>();
        legs.add(leg(a.treasuryCash(), -a.serverMinor()));
        legs.add(clearing(a.serverCurrency(), a.serverMinor()));
        legs.add(clearing(a.cash().currency(), -a.baseMinor()));
        legs.add(leg(a.cash(), a.baseMinor()));
        legs.add(clearing(a.serverCurrency(), -a.serverMinor()));
        legs.add(leg(a.treasuryEquity(), a.serverMinor()));
        legs.add(clearing(a.cash().currency(), a.baseMinor()));
        legs.add(leg(a.equity(), -a.baseMinor()));
        return prepare(
            reads, legs, PostingBatch.Authority.FX, List.of(a.cash(), a.equity(), a.rounding()));
      }
    };
  }

  private static PostingBatch.Leg leg(FinancialAccounts.Account a, long delta) {
    return new PostingBatch.Leg(
        a.id(),
        a.role().spendable()
            ? PostingBatch.Role.INSTITUTION_CASH
            : PostingBatch.Role.INSTITUTION_CLAIM,
        a.currency(),
        new Money(a.currency(), delta));
  }

  private static PostingBatch.Leg clearing(EconomyCurrency c, long delta) {
    return new PostingBatch.Leg(
        Settlement.clearingAccount(c), PostingBatch.Role.CLEARING, c, new Money(c, delta));
  }

  private record Key(UUID id, EconomyCurrency currency) {}

  private static JournalEngine.Commit prepare(
      JournalEngine.ReadView reads,
      List<PostingBatch.Leg> legs,
      PostingBatch.Authority authority,
      List<FinancialAccounts.Account> planned) {
    var decision = PostingBatch.prepareInstitution(legs, authority);
    if (!(decision instanceof JournalEngine.Commit batch))
      throw new IllegalStateException("Capital posting: " + decision);
    var accounts = new HashMap<UUID, FinancialAccounts.Account>();
    planned.forEach(a -> accounts.put(a.id(), a));
    var deltas = new LinkedHashMap<Key, Long>();
    for (var leg : legs) {
      if (leg.role() != PostingBatch.Role.CLEARING) {
        var a = accounts.get(leg.account());
        if (a == null) a = FinancialAccounts.find(reads, leg.account()).orElseThrow();
        if (a.currency() != leg.accountCurrency()
            || (a.role().spendable()) != (leg.role() == PostingBatch.Role.INSTITUTION_CASH))
          throw new IllegalStateException("Capital account type");
        accounts.put(a.id(), a);
      } else if (!leg.account().equals(Settlement.clearingAccount(leg.accountCurrency())))
        throw new IllegalStateException("Capital clearing");
      deltas.merge(
          new Key(leg.account(), leg.accountCurrency()), leg.amount().minorUnits(), Math::addExact);
    }
    var balances = new LinkedHashMap<Key, Long>();
    deltas.forEach(
        (key, delta) -> {
          long next = Math.addExact(Settlement.posted(reads, key.id(), key.currency()), delta);
          var a = accounts.get(key.id());
          if (a != null) {
            boolean invalid =
                switch (a.role()) {
                  case CASH, RESERVATION, RECEIVABLE, EXPENSE -> next < 0;
                  case LIABILITY, PAYABLE, INCOME, EQUITY, ALLOWANCE -> next > 0;
                  case ROUNDING -> false;
                };
            if (invalid) throw new IllegalStateException("Capital normal balance");
          }
          balances.put(key, next);
        });
    return new JournalEngine.Commit(
        batch.records(),
        batch.result(),
        tx ->
            balances.forEach(
                (key, value) -> LedgerProjection.write(tx, key.id(), key.currency(), value)));
  }
}
