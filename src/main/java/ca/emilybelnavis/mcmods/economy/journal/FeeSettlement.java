package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.pricing.*;
import java.util.*;

/** Fees are earned by the supplying institution, with a separate source-currency cash transfer. */
public final class FeeSettlement {
  private FeeSettlement() {}

  private static PostingBatch.Leg leg(
      UUID id, PostingBatch.Role role, EconomyCurrency currency, long units) {
    return new PostingBatch.Leg(id, role, currency, new Money(currency, units));
  }

  public static JournalEngine.Decision composeCash(
      JournalEngine.ReadView reads, JournalEngine.Commit base, List<CashPricing.Quote> quotes) {
    var result = base;
    for (var quote : quotes) {
      var prepared = fee(overlay(reads, result.records()), quote.pricing(), quote.payer());
      if (!(prepared instanceof JournalEngine.Commit fee)) return prepared;
      var previous = result;
      var records = new ArrayList<>(previous.records());
      records.addAll(fee.records());
      if (records.size() > 60)
        return new JournalEngine.Reject(new Journal.Result(1, "PRICING_BATCH_LIMIT", ""));
      result =
          new JournalEngine.Commit(
              records,
              base.result(),
              tx -> {
                previous.projections().apply(tx);
                fee.projections().apply(tx);
              });
    }
    return result;
  }

  public static JournalEngine.Decision fee(
      JournalEngine.ReadView reads, ServicePricing.Quote quote, UUID payer) {
    long amount = quote.fee().minorUnits();
    if (amount == 0)
      return new JournalEngine.Commit(
          List.of(ServicePricing.fact(quote)), new Journal.Result(1, "FEE_ZERO", ""), tx -> {});
    var charge =
        charge(reads, quote.world(), quote.owner(), quote.institution(), quote.fee(), payer);
    if (!(charge instanceof JournalEngine.Commit commit)) return charge;
    var records = new ArrayList<>(commit.records());
    records.add(ServicePricing.fact(quote));
    return new JournalEngine.Commit(records, commit.result(), commit.projections());
  }

  /**
   * Trusted callers validate their captured fee policy before composing this cash/income transfer.
   */
  public static JournalEngine.Decision charge(
      JournalEngine.ReadView reads,
      ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId world,
      ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId owner,
      InstitutionCatalog.InstitutionId bank,
      Money fee,
      UUID payer) {
    long amount = fee.minorUnits();
    if (amount <= 0) throw new IllegalArgumentException("Positive fee required");
    var currency = fee.currency();
    var depositor = new InstitutionCatalog.InstitutionId("economy:depositor/" + owner.value());
    var cash = FinancialAccounts.account(world, bank, FinancialAccounts.Role.CASH, currency);
    var income = FinancialAccounts.account(world, bank, FinancialAccounts.Role.INCOME, currency);
    var expense =
        FinancialAccounts.account(world, depositor, FinancialAccounts.Role.EXPENSE, currency);
    var accounts = List.of(cash, income, expense);
    var legs =
        List.of(
            leg(
                payer == null ? Settlement.issuanceAccount(currency) : payer,
                payer == null ? PostingBatch.Role.ISSUANCE : PostingBatch.Role.ACCOUNT,
                currency,
                -amount),
            leg(cash.id(), PostingBatch.Role.INSTITUTION_CASH, currency, amount));
    JournalEngine.Decision money;
    if (payer == null) {
      money = PostingBatch.prepareInstitution(legs, PostingBatch.Authority.ISSUE);
      if (money instanceof JournalEngine.Commit batch) {
        long nextCash = Math.addExact(Settlement.posted(reads, cash.id(), currency), amount);
        long nextIssuance =
            Math.subtractExact(
                Settlement.posted(reads, Settlement.issuanceAccount(currency), currency), amount);
        money =
            new JournalEngine.Commit(
                batch.records(),
                batch.result(),
                tx -> {
                  ca.emilybelnavis.mcmods.economy.storage.LedgerProjection.write(
                      tx, cash.id(), currency, nextCash);
                  ca.emilybelnavis.mcmods.economy.storage.LedgerProjection.write(
                      tx, Settlement.issuanceAccount(currency), currency, nextIssuance);
                });
      }
    } else
      money =
          InstitutionSettlement.prepare(
              reads,
              legs,
              accounts,
              ca.emilybelnavis.mcmods.economy.overdraft.Overdrafts.DebitKind.BANK_CHARGE);
    if (!(money instanceof JournalEngine.Commit transfer)) return money;
    var recognition =
        InstitutionSettlement.prepare(
            reads,
            List.of(
                leg(income.id(), PostingBatch.Role.INSTITUTION_CLAIM, currency, -amount),
                leg(expense.id(), PostingBatch.Role.INSTITUTION_CLAIM, currency, amount)),
            accounts);
    if (!(recognition instanceof JournalEngine.Commit recognized)) return recognition;
    var records = new ArrayList<>(transfer.records());
    records.addAll(recognized.records());
    for (var account : accounts)
      if (FinancialAccounts.find(reads, account.id()).isEmpty())
        records.add(
            new Journal.Record(
                InstitutionCapital.ACCOUNT,
                1,
                account.id()
                    + "|"
                    + world.value()
                    + "|"
                    + account.owner().key()
                    + "|"
                    + account.role()
                    + "|"
                    + currency));
    return new JournalEngine.Commit(
        records,
        new Journal.Result(1, "FEE_PAID", Long.toString(amount)),
        tx -> {
          tx.execute(
              "INSERT OR IGNORE INTO institution_parties VALUES(?,?,?,?,?,'INTERNAL',1)",
              List.of(
                  world.value().toString(),
                  depositor.key(),
                  new InstitutionCatalog.SystemActor(world, depositor).actorId().value().toString(),
                  "Depositor accounting",
                  currency.name()));
          accounts.forEach(a -> FinancialAccounts.register(tx, a));
          transfer.projections().apply(tx);
          recognized.projections().apply(tx);
        });
  }

  /**
   * The later leg preparation sees earlier planned balances without mutating before journal commit.
   */
  public static JournalEngine.ReadView overlay(
      JournalEngine.ReadView reads, List<Journal.Record> records) {
    var changes = new HashMap<String, Long>();
    for (var record : records)
      if (record.type().equals("economy:posting")) {
        var p = record.payload().split("\\|", -1);
        changes.merge(p[0] + "|" + p[2], Long.parseLong(p[3]), Math::addExact);
      }
    return (sql, args, max) -> {
      if (sql.contains("FROM ledger_balances")
          && args.size() == 2
          && sql.stripLeading().startsWith("SELECT minor_units")) {
        long value =
            Math.addExact(
                Settlement.posted(
                    reads, UUID.fromString(args.get(0)), EconomyCurrency.valueOf(args.get(1))),
                changes.getOrDefault(args.get(0) + "|" + args.get(1), 0L));
        return value == 0 ? List.of() : List.of(Map.of("minor_units", Long.toString(value)));
      }
      return reads.query(sql, args, max);
    };
  }
}
