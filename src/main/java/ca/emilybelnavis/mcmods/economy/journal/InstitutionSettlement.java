package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts;
import ca.emilybelnavis.mcmods.economy.storage.LedgerProjection;
import java.util.*;

/** Trusted composition for registered institution claims/cash and existing customer accounts. */
public final class InstitutionSettlement {
  private InstitutionSettlement() {}

  private record Key(UUID id, EconomyCurrency currency) {}

  public static JournalEngine.Decision prepare(
      JournalEngine.ReadView reads,
      List<PostingBatch.Leg> legs,
      List<FinancialAccounts.Account> planned) {
    return prepare(
        reads,
        legs,
        planned,
        ca.emilybelnavis.mcmods.economy.overdraft.Overdrafts.DebitKind.NORMAL);
  }

  /** Caller binds this trusted authority to the canonical domain operation. */
  public static JournalEngine.Decision prepare(
      JournalEngine.ReadView reads,
      List<PostingBatch.Leg> legs,
      List<FinancialAccounts.Account> planned,
      ca.emilybelnavis.mcmods.economy.overdraft.Overdrafts.DebitKind debitKind) {
    var authority =
        legs.stream().anyMatch(l -> l.role() == PostingBatch.Role.CLEARING)
            ? PostingBatch.Authority.FX
            : PostingBatch.Authority.TRANSFER;
    var decision = PostingBatch.prepareInstitution(legs, authority);
    if (!(decision instanceof JournalEngine.Commit batch)) return decision;
    var accounts = new HashMap<UUID, FinancialAccounts.Account>();
    for (var a : planned) {
      var existing = FinancialAccounts.find(reads, a.id());
      if (existing.isPresent() && !existing.get().equals(a)) return reject("FINANCIAL_ACCOUNT");
      accounts.put(a.id(), a);
    }
    var deltas = new LinkedHashMap<Key, Long>();
    try {
      for (var leg : legs) {
        switch (leg.role()) {
          case ACCOUNT -> {
            if (!Settlement.openBank(reads, leg.account(), leg.accountCurrency()))
              return reject("POSTING_ACCOUNT");
          }
          case CLEARING -> {
            if (!leg.account().equals(Settlement.clearingAccount(leg.accountCurrency())))
              return reject("POSTING_ROLE");
          }
          case INSTITUTION_CASH, INSTITUTION_CLAIM -> {
            var a = accounts.get(leg.account());
            if (a == null) a = FinancialAccounts.find(reads, leg.account()).orElse(null);
            if (a == null
                || a.currency() != leg.accountCurrency()
                || a.role().spendable() != (leg.role() == PostingBatch.Role.INSTITUTION_CASH))
              return reject("FINANCIAL_ACCOUNT");
            accounts.put(a.id(), a);
          }
          default -> {
            return reject("POSTING_ROLE");
          }
        }
        deltas.merge(
            new Key(leg.account(), leg.accountCurrency()),
            leg.amount().minorUnits(),
            Math::addExact);
      }
      var balances = new LinkedHashMap<Key, Long>();
      for (var entry : deltas.entrySet()) {
        var key = entry.getKey();
        long next =
            Math.addExact(Settlement.posted(reads, key.id(), key.currency()), entry.getValue());
        var a = accounts.get(key.id());
        if (a == null
            && !key.id().equals(Settlement.clearingAccount(key.currency()))
            && !ca.emilybelnavis.mcmods.economy.overdraft.Overdrafts.permits(
                reads,
                key.id(),
                key.currency(),
                Settlement.posted(reads, key.id(), key.currency()),
                next,
                Holds.reserved(reads, key.id(), key.currency()),
                debitKind)) return reject("INSUFFICIENT_FUNDS");
        if (a != null) {
          boolean invalid =
              switch (a.role()) {
                case CASH, RESERVATION, RECEIVABLE, EXPENSE -> next < 0;
                case LIABILITY, PAYABLE, INCOME, EQUITY, ALLOWANCE -> next > 0;
                case ROUNDING -> false;
              };
          if (invalid) return reject("INSTITUTION_FUNDS");
        }
        balances.put(key, next);
      }
      return new JournalEngine.Commit(
          batch.records(),
          batch.result(),
          tx ->
              balances.forEach(
                  (key, value) -> LedgerProjection.write(tx, key.id(), key.currency(), value)));
    } catch (ArithmeticException overflow) {
      return reject("POSTING_OVERFLOW");
    }
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }
}
