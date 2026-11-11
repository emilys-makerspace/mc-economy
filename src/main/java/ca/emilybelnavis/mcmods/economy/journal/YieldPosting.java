package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.FxFacts;
import ca.emilybelnavis.mcmods.economy.institution.*;
import java.math.*;
import java.util.*;

/** Claims are paired with depositor receivables; only a funded payout becomes customer cash. */
public final class YieldPosting {
  private YieldPosting() {}

  public static InstitutionCatalog.InstitutionId depositor(PlayerId owner) {
    return new InstitutionCatalog.InstitutionId("economy:depositor/" + owner.value());
  }

  public record Payment(
      JournalEngine.Commit commit, List<PostingBatch.Leg> legs, String conversion) {}

  public static Payment prepare(
      JournalEngine.ReadView reads,
      WorldId world,
      PlayerId owner,
      InstitutionCatalog.InstitutionId bank,
      EconomyCurrency currency,
      long recognize,
      long pay,
      AccountId destination,
      FxFacts.Publication fx) {
    try {
      return prepareChecked(reads, world, owner, bank, currency, recognize, pay, destination, fx);
    } catch (ArithmeticException overflow) {
      return null;
    }
  }

  private static Payment prepareChecked(
      JournalEngine.ReadView reads,
      WorldId world,
      PlayerId owner,
      InstitutionCatalog.InstitutionId bank,
      EconomyCurrency currency,
      long recognize,
      long pay,
      AccountId destination,
      FxFacts.Publication fx) {
    if (recognize < 0 || pay < 0) throw new IllegalArgumentException("Negative yield movement");
    var planned = new ArrayList<FinancialAccounts.Account>();
    var cash = account(planned, world, bank, FinancialAccounts.Role.CASH, currency);
    var payable = account(planned, world, bank, FinancialAccounts.Role.PAYABLE, currency);
    var expense = account(planned, world, bank, FinancialAccounts.Role.EXPENSE, currency);
    var receivable =
        account(planned, world, depositor(owner), FinancialAccounts.Role.RECEIVABLE, currency);
    var income = account(planned, world, depositor(owner), FinancialAccounts.Role.INCOME, currency);
    var legs = new ArrayList<PostingBatch.Leg>();
    if (recognize > 0) {
      legs.add(leg(expense, recognize));
      legs.add(leg(payable, -recognize));
      legs.add(leg(receivable, recognize));
      legs.add(leg(income, -recognize));
    }
    String conversion = "";
    if (pay > 0) {
      if (destination == null) throw new IllegalArgumentException("Yield destination required");
      long nativeCash = Settlement.posted(reads, cash.id(), currency);
      if (nativeCash < pay) {
        var entry = InstitutionCatalog.require(bank);
        var base = entry.baseCurrency();
        if (base == currency
            || fx == null
            || !fx.rates().containsKey(base)
            || !fx.rates().containsKey(currency)) return null;
        long credit = Math.subtractExact(pay, nativeCash);
        var from = account(planned, world, bank, FinancialAccounts.Role.CASH, base);
        long debit =
            BigDecimal.valueOf(credit)
                .multiply(fx.rates().get(currency))
                .multiply(BigDecimal.valueOf(base.minorUnitsPerMajor()))
                .divide(
                    fx.rates()
                        .get(base)
                        .multiply(BigDecimal.valueOf(currency.minorUnitsPerMajor())),
                    0,
                    RoundingMode.CEILING)
                .longValueExact();
        if (debit <= 0 || Settlement.posted(reads, from.id(), base) < debit) return null;
        legs.add(leg(from, -debit));
        legs.add(clearing(base, debit));
        legs.add(clearing(currency, -credit));
        legs.add(leg(cash, credit));
        var rounding =
            account(planned, world, bank, FinancialAccounts.Role.ROUNDING, fx.reference());
        conversion = conversion(fx, base, debit, currency, credit, rounding.id());
      }
      var destinations =
          reads.query(
              "SELECT currency FROM account_catalog WHERE account_id=? AND purpose='BANK' AND status='OPEN'",
              List.of(destination.value().toString()),
              1);
      if (destinations.isEmpty()) return null;
      var destinationCurrency = EconomyCurrency.valueOf(destinations.getFirst().get("currency"));
      long credited = pay;
      legs.add(leg(payable, pay));
      legs.add(leg(cash, -pay));
      legs.add(leg(receivable, -pay));
      if (destinationCurrency != currency) {
        if (fx == null
            || !fx.rates().containsKey(currency)
            || !fx.rates().containsKey(destinationCurrency)) return null;
        credited =
            BigDecimal.valueOf(pay)
                .multiply(fx.rates().get(currency))
                .multiply(BigDecimal.valueOf(destinationCurrency.minorUnitsPerMajor()))
                .divide(
                    fx.rates()
                        .get(destinationCurrency)
                        .multiply(BigDecimal.valueOf(currency.minorUnitsPerMajor())),
                    0,
                    RoundingMode.FLOOR)
                .longValueExact();
        if (credited <= 0) return null;
        legs.add(clearing(currency, pay));
        legs.add(clearing(destinationCurrency, -credited));
        var rounding =
            account(planned, world, bank, FinancialAccounts.Role.ROUNDING, fx.reference());
        conversion +=
            (conversion.isEmpty() ? "" : ";")
                + conversion(fx, currency, pay, destinationCurrency, credited, rounding.id());
      }
      legs.add(
          new PostingBatch.Leg(
              destination.value(),
              PostingBatch.Role.ACCOUNT,
              destinationCurrency,
              new Money(destinationCurrency, credited)));
    }
    if (legs.isEmpty()) return new Payment(null, List.of(), "");
    var decision = InstitutionSettlement.prepare(reads, legs, planned);
    if (!(decision instanceof JournalEngine.Commit financial)) return null;
    var records = new ArrayList<>(financial.records());
    if (reads
        .query(
            "SELECT institution_key FROM institution_parties WHERE world_id=? AND institution_key=?",
            List.of(world.value().toString(), depositor(owner).key()),
            1)
        .isEmpty())
      records.add(
          new Journal.Record(
              "economy:a6/yield-depositor",
              1,
              world.value()
                  + "|"
                  + owner.value()
                  + "|"
                  + new InstitutionCatalog.SystemActor(world, depositor(owner)).actorId().value()
                  + "|"
                  + currency));
    for (var a : planned)
      if (FinancialAccounts.find(reads, a.id()).isEmpty())
        records.add(
            new Journal.Record(
                "economy:a6/institution-account",
                1,
                a.id()
                    + "|"
                    + world.value()
                    + "|"
                    + a.owner().key()
                    + "|"
                    + a.role()
                    + "|"
                    + a.currency()));
    var commit =
        new JournalEngine.Commit(
            records,
            financial.result(),
            tx -> {
              String key = depositor(owner).key();
              tx.execute(
                  "INSERT OR IGNORE INTO institution_parties(world_id,institution_key,actor_id,display_name,base_currency,tier,enabled) VALUES(?,?,?,?,?,'INTERNAL',1)",
                  List.of(
                      world.value().toString(),
                      key,
                      new InstitutionCatalog.SystemActor(world, depositor(owner))
                          .actorId()
                          .value()
                          .toString(),
                      "Depositor yield claims",
                      currency.name()));
              for (var a : planned) FinancialAccounts.register(tx, a);
              financial.projections().apply(tx);
            });
    return new Payment(commit, List.copyOf(legs), conversion);
  }

  private static String conversion(
      FxFacts.Publication fx,
      EconomyCurrency source,
      long debit,
      EconomyCurrency target,
      long credit,
      UUID rounding) {
    var residual =
        BigDecimal.valueOf(debit)
            .multiply(fx.rates().get(source))
            .divide(BigDecimal.valueOf(source.minorUnitsPerMajor()))
            .subtract(
                BigDecimal.valueOf(credit)
                    .multiply(fx.rates().get(target))
                    .divide(BigDecimal.valueOf(target.minorUnitsPerMajor())));
    if (residual.signum() < 0) throw new IllegalStateException("Negative yield FX residual");
    return fx.updateId()
        + ","
        + source
        + ","
        + debit
        + ","
        + target
        + ","
        + credit
        + ","
        + fx.reference()
        + ","
        + residual.toPlainString()
        + ","
        + rounding;
  }

  private static FinancialAccounts.Account account(
      List<FinancialAccounts.Account> planned,
      WorldId world,
      InstitutionCatalog.InstitutionId owner,
      FinancialAccounts.Role role,
      EconomyCurrency currency) {
    var a = FinancialAccounts.account(world, owner, role, currency);
    if (!planned.contains(a)) planned.add(a);
    return a;
  }

  private static PostingBatch.Leg leg(FinancialAccounts.Account a, long units) {
    return new PostingBatch.Leg(
        a.id(),
        a.role() == FinancialAccounts.Role.CASH
            ? PostingBatch.Role.INSTITUTION_CASH
            : PostingBatch.Role.INSTITUTION_CLAIM,
        a.currency(),
        new Money(a.currency(), units));
  }

  private static PostingBatch.Leg clearing(EconomyCurrency c, long amount) {
    return new PostingBatch.Leg(
        Settlement.clearingAccount(c), PostingBatch.Role.CLEARING, c, new Money(c, amount));
  }
}
