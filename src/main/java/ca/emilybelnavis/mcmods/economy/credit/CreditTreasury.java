package ca.emilybelnavis.mcmods.economy.credit;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.fx.FxFacts;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import java.math.*;
import java.time.*;
import java.util.*;

/** Funds a committed line with segregated real cash; customer deposits never supply capital. */
public final class CreditTreasury {
  public static final String FACT = "economy:a6/credit-treasury";

  private CreditTreasury() {}

  public static FxFacts.Publication publication(JournalEngine.ReadView reads, long now) {
    var fx = ca.emilybelnavis.mcmods.economy.fx.FxService.latest(reads);
    if (fx == null) return null;
    long age =
        Math.subtractExact(
            now,
            ca.emilybelnavis.mcmods.economy.fx.FxService.effectiveMillis(reads, fx.updateId()));
    return age >= 0 && age <= 172_800_000L ? fx : null;
  }

  public static JournalEngine.Decision reserve(
      JournalEngine.ReadView reads,
      WorldId world,
      InstitutionCatalog.InstitutionId bank,
      EconomyCurrency currency,
      long amount,
      long now) {
    try {
      return checked(reads, world, bank, currency, amount, now);
    } catch (ArithmeticException invalid) {
      return reject("CREDIT_FUNDING_OVERFLOW");
    }
  }

  private static JournalEngine.Decision checked(
      JournalEngine.ReadView reads,
      WorldId world,
      InstitutionCatalog.InstitutionId bank,
      EconomyCurrency currency,
      long amount,
      long now) {
    if (amount <= 0) return reject("CREDIT_FUNDING_AMOUNT");
    if (reads
        .query(
            "SELECT state FROM institution_capital_manifest WHERE world_id=? AND state='ACTIVE'",
            List.of(world.value().toString()),
            1)
        .isEmpty()) return reject("CREDIT_CAPITAL_UNAVAILABLE");
    var allocation =
        reads.query(
            "SELECT base_currency,operating_floor_minor FROM institution_capital_allocations WHERE world_id=? AND institution_key=?",
            List.of(world.value().toString(), bank.key()),
            1);
    if (allocation.isEmpty()
        || ca.emilybelnavis.mcmods.economy.interest.DepositYield.distressed(reads, bank))
      return reject("CREDIT_CAPITAL_UNAVAILABLE");
    var base = EconomyCurrency.valueOf(allocation.getFirst().get("base_currency"));
    long floor = Long.parseLong(allocation.getFirst().get("operating_floor_minor"));
    var cash = FinancialAccounts.account(world, bank, FinancialAccounts.Role.CASH, currency);
    var reserve =
        FinancialAccounts.account(world, bank, FinancialAccounts.Role.RESERVATION, currency);
    var baseCash = FinancialAccounts.account(world, bank, FinancialAccounts.Role.CASH, base);
    var planned = new ArrayList<>(List.of(cash, reserve));
    var legs = new ArrayList<PostingBatch.Leg>();
    long nativeCash = Settlement.posted(reads, cash.id(), currency);
    long baseBalance = Settlement.posted(reads, baseCash.id(), base);
    long debit = 0, credit = Math.max(0, Math.subtractExact(amount, nativeCash));
    String conversion = "NONE";
    if (base == currency) {
      if (Math.subtractExact(baseBalance, amount) < floor) return reject("CREDIT_OPERATING_FLOOR");
    } else {
      if (baseBalance < floor) return reject("CREDIT_OPERATING_FLOOR");
      if (credit > 0) {
        var fx = publication(reads, now);
        if (fx == null || !fx.rates().containsKey(base) || !fx.rates().containsKey(currency))
          return reject("CREDIT_FX_UNAVAILABLE");
        debit =
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
        if (debit < 1 || Math.subtractExact(baseBalance, debit) < floor)
          return reject("CREDIT_OPERATING_FLOOR");
        planned.add(baseCash);
        legs.add(leg(baseCash, -debit));
        legs.add(clearing(base, debit));
        legs.add(clearing(currency, -credit));
        legs.add(leg(cash, credit));
        var residual =
            BigDecimal.valueOf(debit)
                .multiply(fx.rates().get(base))
                .divide(BigDecimal.valueOf(base.minorUnitsPerMajor()))
                .subtract(
                    BigDecimal.valueOf(credit)
                        .multiply(fx.rates().get(currency))
                        .divide(BigDecimal.valueOf(currency.minorUnitsPerMajor())));
        var rounding =
            FinancialAccounts.account(world, bank, FinancialAccounts.Role.ROUNDING, fx.reference());
        planned.add(rounding);
        conversion =
            fx.updateId()
                + ","
                + base
                + ","
                + debit
                + ","
                + currency
                + ","
                + credit
                + ","
                + fx.reference()
                + ","
                + residual.toPlainString()
                + ","
                + rounding.id();
      }
    }
    legs.add(leg(cash, -amount));
    legs.add(leg(reserve, amount));
    var decision = InstitutionSettlement.prepare(reads, legs, planned);
    if (!(decision instanceof JournalEngine.Commit financial)) return decision;
    var facts = new ArrayList<>(financial.records());
    for (var a : planned)
      if (FinancialAccounts.find(reads, a.id()).isEmpty())
        facts.add(
            new Journal.Record(
                "economy:a6/institution-account",
                1,
                a.id()
                    + "|"
                    + world.value()
                    + "|"
                    + bank.key()
                    + "|"
                    + a.role()
                    + "|"
                    + a.currency()));
    facts.add(
        new Journal.Record(
            FACT,
            1,
            world.value()
                + "|"
                + bank.key()
                + "|"
                + currency
                + "|"
                + amount
                + "|"
                + floor
                + "|"
                + now
                + "|"
                + conversion));
    return new JournalEngine.Commit(
        facts,
        new Journal.Result(1, "CREDIT_RESERVED", Long.toString(amount)),
        tx -> {
          for (var a : planned) FinancialAccounts.register(tx, a);
          financial.projections().apply(tx);
        });
  }

  private static PostingBatch.Leg leg(FinancialAccounts.Account a, long amount) {
    return new PostingBatch.Leg(
        a.id(),
        a.role() == FinancialAccounts.Role.CASH
            ? PostingBatch.Role.INSTITUTION_CASH
            : PostingBatch.Role.INSTITUTION_CLAIM,
        a.currency(),
        new Money(a.currency(), amount));
  }

  private static PostingBatch.Leg clearing(EconomyCurrency c, long amount) {
    return new PostingBatch.Leg(
        Settlement.clearingAccount(c), PostingBatch.Role.CLEARING, c, new Money(c, amount));
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog.with(new RecordCatalog.Key(FACT, 1), CreditTreasury::validateFunding);
  }

  public static void validateFunding(String payload) {
    validateFunding(payload, false);
  }

  public static void validateLoanFunding(String payload) {
    validateFunding(payload, true);
  }

  public static void validateCollectionFunding(String payload) {
    var fields = payload.split("\\|", -1);
    if (fields.length != 7 || !fields[1].equals(InstitutionCatalog.WAYSTONE.key()))
      throw new IllegalArgumentException("Collection treasury issuer");
    // Reuse the historical numeric/FX validator under its already supported internal lender
    // identity.
    fields[1] = InstitutionCatalog.IRONBRIDGE.key();
    validateLoanFunding(String.join("|", fields));
  }

  private static void validateFunding(String payload, boolean loan) {
    var p = payload.split("\\|", -1);
    if (p.length != 7) throw new IllegalArgumentException("Treasury fields");
    UUID.fromString(p[0]);
    var lender = new InstitutionCatalog.InstitutionId(p[1]);
    if (!loan || !lender.equals(InstitutionCatalog.IRONBRIDGE)) InstitutionCatalog.require(lender);
    EconomyCurrency.valueOf(p[2]);
    if (Long.parseLong(p[3]) <= 0 || Long.parseLong(p[4]) < 0 || Long.parseLong(p[5]) < 0)
      throw new IllegalArgumentException("Treasury values");
    if (!p[6].equals("NONE")) {
      var f = p[6].split(",", -1);
      if (f.length != 8) throw new IllegalArgumentException("Treasury FX");
      UUID.fromString(f[0]);
      UUID.fromString(f[7]);
      EconomyCurrency.valueOf(f[1]);
      EconomyCurrency.valueOf(f[3]);
      EconomyCurrency.valueOf(f[5]);
      if (Long.parseLong(f[2]) <= 0
          || Long.parseLong(f[4]) <= 0
          || new BigDecimal(f[6]).signum() < 0)
        throw new IllegalArgumentException("Treasury FX values");
    }
  }
}
