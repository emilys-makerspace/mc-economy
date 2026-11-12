package ca.emilybelnavis.mcmods.economy.overdraft;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.card.IssuedCards;
import ca.emilybelnavis.mcmods.economy.debt.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import java.util.*;

/** Involuntary BANK closure and reclassification of its implicit overdraft into a frozen claim. */
public final class OverdraftCollections {
  public static final String FACT = "economy:overdraft/default";

  private OverdraftCollections() {}

  /** Called only by the trusted overdraft deadline provider after its recovery-clock check. */
  public static JournalEngine.Decision defaultAccount(
      JournalEngine.ReadView reads, UUID accountId, long now) {
    var rows =
        reads.query(
            "SELECT owner_id,currency,status,revision FROM account_catalog WHERE account_id=? AND purpose='BANK'",
            List.of(accountId.toString()),
            1);
    if (rows.isEmpty() || !rows.getFirst().get("status").equals("OPEN") || now < 0)
      return reject("OVERDRAFT_DEFAULT_STALE");
    if (DebtLifecycle.facilityCase(reads, DebtRecords.Kind.OVERDRAFT, accountId).isPresent())
      return reject("OVERDRAFT_ALREADY_DEFAULTED");
    var row = rows.getFirst();
    var currency = EconomyCurrency.valueOf(row.get("currency"));
    long posted = Settlement.posted(reads, accountId, currency);
    if (posted > 0) return reject("OVERDRAFT_RECOVERY_PENDING");
    long debt = Math.negateExact(posted);
    var world =
        new WorldId(
            UUID.fromString(
                reads
                    .query("SELECT world_id FROM foundation_metadata", List.of(), 1)
                    .getFirst()
                    .get("world_id")));
    var owner = new PlayerId(UUID.fromString(row.get("owner_id")));
    var id = new AccountId(accountId);
    var binding = BankProviders.find(reads, id).orElse(null);
    if (binding == null
        || InstitutionCatalog.find(new InstitutionCatalog.InstitutionId(binding.bankKey()))
            .isEmpty()) return reject("OVERDRAFT_PROVIDER_UNAVAILABLE");
    var bank = new InstitutionCatalog.InstitutionId(binding.bankKey());
    long revision = Long.parseLong(row.get("revision"));
    long nextRevision = Math.addExact(revision, 1);
    long providerRevision = Math.addExact(binding.revision(), 1);
    long debitEpoch = Math.addExact(binding.debitEpoch(), 1);
    var borrower = DebtClaims.borrower(DebtRecords.Kind.OVERDRAFT, owner);
    var claim = FinancialAccounts.account(world, bank, FinancialAccounts.Role.RECEIVABLE, currency);
    var equity = FinancialAccounts.account(world, bank, FinancialAccounts.Role.EQUITY, currency);
    var liability =
        FinancialAccounts.account(world, borrower, FinancialAccounts.Role.LIABILITY, currency);
    var accounts = List.of(claim, equity, liability);
    var caseId =
        DebtLifecycle.stable("case|" + world.value() + "|OVERDRAFT|" + accountId + "|" + now);
    var frozen =
        new DebtRecords.Case(
            world,
            caseId,
            DebtRecords.Kind.OVERDRAFT,
            accountId,
            owner,
            bank,
            bank,
            currency,
            debt,
            0,
            now,
            now,
            Math.addExact(now, DebtRecords.COLLECTION_WINDOW),
            Math.addExact(now, DebtRecords.REPORT_WINDOW),
            DebtRecords.Sale.PENDING,
            DebtRecords.Collectability.COLLECTIBLE,
            debt == 0 ? DebtRecords.Payment.SETTLED : DebtRecords.Payment.UNPAID,
            0,
            1);
    var records = new ArrayList<Journal.Record>();
    long held = Holds.reserved(reads, accountId, currency);
    records.add(
        new Journal.Record(
            FACT,
            1,
            String.join(
                "|",
                world.value().toString(),
                accountId.toString(),
                owner.value().toString(),
                currency.name(),
                Long.toString(revision),
                Long.toString(nextRevision),
                Long.toString(posted),
                Long.toString(now),
                Long.toString(held),
                caseId.toString())));
    records.add(
        new Journal.Record(
            DebtFinance.FREEZE_FACT,
            1,
            world.value() + "|OVERDRAFT|" + accountId + "|" + now + "|0|0.000000000000000000"));
    JournalEngine.Commit money = null;
    if (debt > 0) {
      // The negative BANK balance already represents the borrower's debt. Reclassify it, then
      // recognize the bank's previously implicit claim against nonspendable opening equity.
      // No issuance, cash/reservation change or new customer purchasing power occurs here.
      var legs =
          List.of(
              new PostingBatch.Leg(
                  accountId, PostingBatch.Role.ACCOUNT, currency, new Money(currency, debt)),
              leg(liability, -debt),
              leg(claim, debt),
              leg(equity, -debt));
      JournalEngine.ReadView releasedHolds =
          (sql, parameters, max) -> {
            if (sql.contains("COALESCE(SUM(minor_units),0) AS held")
                && parameters.equals(List.of(accountId.toString(), currency.name())))
              return List.of(Map.of("held", "0"));
            return reads.query(sql, parameters, max);
          };
      var prepared = InstitutionSettlement.prepare(releasedHolds, legs, accounts);
      if (!(prepared instanceof JournalEngine.Commit commit)) return prepared;
      money = commit;
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
      records.addAll(commit.records());
    }
    records.add(DebtRecords.fact(frozen));
    records.add(
        new Journal.Record(
            "economy:bank-provider",
            1,
            "closed|"
                + accountId
                + "|"
                + bank.key()
                + "|"
                + providerRevision
                + "|"
                + (binding.legacy() ? "1" : "0")
                + "|"
                + debitEpoch));
    var revoke = IssuedCards.invalidateAccount(reads, world, id).orElse(null);
    if (revoke != null) records.addAll(revoke.records());
    var financial = money;
    return new JournalEngine.Commit(
        records,
        new Journal.Result(1, "OVERDRAFT_ACCOUNT_DEFAULTED", caseId.toString()),
        tx -> {
          tx.execute(
              "UPDATE ledger_holds SET state='RELEASED' WHERE account_id=? AND state='ACTIVE'",
              List.of(accountId.toString()));
          if (financial != null) {
            tx.execute(
                "INSERT OR IGNORE INTO institution_parties VALUES(?,?,?,?,?,'INTERNAL',1)",
                List.of(
                    world.value().toString(),
                    borrower.key(),
                    new InstitutionCatalog.SystemActor(world, borrower)
                        .actorId()
                        .value()
                        .toString(),
                    "Overdraft borrower",
                    currency.name()));
            for (var account : accounts) FinancialAccounts.register(tx, account);
            financial.projections().apply(tx);
          }
          if (tx.execute(
                  "UPDATE account_catalog SET status='CLOSED',revision=? WHERE account_id=? AND revision=? AND status='OPEN'",
                  List.of(
                      Long.toString(nextRevision), accountId.toString(), Long.toString(revision)))
              != 1)
            throw new IllegalStateException("Overdraft closure lost its original account revision");
          if (revoke != null) revoke.projections().apply(tx);
          tx.execute(
              "UPDATE bank_account_providers SET revision=?,debit_epoch=? WHERE account_id=?",
              List.of(
                  Long.toString(providerRevision),
                  Long.toString(debitEpoch),
                  accountId.toString()));
          tx.execute(
              "DELETE FROM bank_provider_switches WHERE account_id=?",
              List.of(accountId.toString()));
          DebtRecords.write(tx, frozen);
          if (debt > 0)
            tx.execute(
                "INSERT INTO debt_sale_attempts VALUES(?,?,?)",
                List.of(world.value().toString(), caseId.toString(), Long.toString(now)));
        });
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog.with(new RecordCatalog.Key(FACT, 1), OverdraftCollections::validate);
  }

  private static void validate(String payload) {
    var p = payload.split("\\|", -1);
    if (p.length != 10) throw new IllegalArgumentException("Overdraft default fields");
    UUID.fromString(p[0]);
    UUID.fromString(p[1]);
    UUID.fromString(p[2]);
    EconomyCurrency.valueOf(p[3]);
    UUID.fromString(p[9]);
    long oldRevision = Long.parseLong(p[4]);
    if (oldRevision < 1
        || Long.parseLong(p[5]) != Math.addExact(oldRevision, 1)
        || Long.parseLong(p[6]) > 0
        || Long.parseLong(p[7]) < 0
        || Long.parseLong(p[8]) < 0) throw new IllegalArgumentException("Overdraft default state");
  }

  private static PostingBatch.Leg leg(FinancialAccounts.Account account, long units) {
    return new PostingBatch.Leg(
        account.id(),
        PostingBatch.Role.INSTITUTION_CLAIM,
        account.currency(),
        new Money(account.currency(), units));
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }
}
