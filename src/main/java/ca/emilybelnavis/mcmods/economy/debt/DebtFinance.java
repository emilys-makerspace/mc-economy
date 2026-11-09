package ca.emilybelnavis.mcmods.economy.debt;

import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.loan.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.*;

/**
 * Financial preparation for frozen claims. The lifecycle owns case revisions and clock transitions.
 */
public final class DebtFinance {
  public static final String FREEZE_FACT = "economy:a6/debt-freeze";
  public static final String TREASURY_FACT = "economy:a6/debt-treasury";
  public static final String ACCOUNTING_FACT = "economy:a6/debt-accounting";
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(18);

  private DebtFinance() {}

  /** Carries an ordinary ledger rejection across the typed financial-plan seam. */
  public static final class FinancialRejection extends RuntimeException {
    private final Decision decision;

    FinancialRejection(Decision decision) {
      super("Debt financial preparation rejected");
      this.decision = decision;
    }

    public Decision decision() {
      return decision;
    }
  }

  public record Frozen(
      Commit commit,
      WorldId world,
      PlayerId owner,
      InstitutionCatalog.InstitutionId origin,
      EconomyCurrency currency,
      long principalMinor,
      long interestMinor) {}

  public record PaymentPlan(
      Commit commit, long principalMinor, long interestMinor, long discountReleasedMinor) {}

  public static Frozen freeze(
      ReadView reads, DebtRecords.Kind kind, UUID facility, long effective) {
    if (effective < 0) throw new IllegalArgumentException("Default instant");
    return kind == DebtRecords.Kind.CARD
        ? freezeCard(reads, facility, effective)
        : freezeLoan(reads, facility, effective);
  }

  private static Frozen freezeCard(ReadView reads, UUID id, long effective) {
    var old =
        CreditFacilities.find(reads, id)
            .orElseThrow(() -> new IllegalArgumentException("Credit facility unavailable"));
    if (old.status() != CreditFacilities.Status.ACTIVE
        || effective < old.lastDay()
        || effective >= old.nextDay()
        || effective >= old.nextStatement()) {
      throw new IllegalArgumentException("Credit default requires current scheduled work");
    }
    var purchase = freezeBucket(old.currency(), old.purchase());
    var advance = freezeBucket(old.currency(), old.advance());
    long recognized = Math.addExact(purchase.posted(), advance.posted());
    var next =
        card(
            old,
            purchase.bucket(),
            advance.bucket(),
            old.requirements(),
            old.slices(),
            CreditFacilities.Status.DEFAULTED);
    var accounts = new ArrayList<FinancialAccounts.Account>();
    var legs = new ArrayList<PostingBatch.Leg>();
    recognize(
        accounts,
        legs,
        old.world(),
        old.issuer(),
        borrower(DebtRecords.Kind.CARD, old.owner()),
        old.currency(),
        recognized);
    if (old.access() != CreditFacilities.Access.CLOSED) {
      long release = Math.max(0, Math.subtractExact(old.limitMinor(), old.principalMinor()));
      if (release > 0) {
        var reserve =
            account(old.world(), old.issuer(), FinancialAccounts.Role.RESERVATION, old.currency());
        var cash = account(old.world(), old.issuer(), FinancialAccounts.Role.CASH, old.currency());
        accounts.addAll(List.of(reserve, cash));
        legs.add(leg(reserve, -release));
        legs.add(leg(cash, release));
      }
    }
    var facts =
        List.of(
            CreditFacilities.fact(next),
            freezeFact(
                old.world(),
                DebtRecords.Kind.CARD,
                id,
                effective,
                recognized,
                purchase.disposition().add(advance.disposition())));
    var commit =
        compose(
            reads, legs, accounts, facts, "DEBT_FROZEN", tx -> CreditFacilities.write(tx, next));
    return new Frozen(
        commit,
        old.world(),
        old.owner(),
        old.issuer(),
        old.currency(),
        next.principalMinor(),
        Math.subtractExact(next.owedMinor(), next.principalMinor()));
  }

  private record FrozenBucket(CardServicing.Bucket bucket, long posted, BigDecimal disposition) {}

  private static FrozenBucket freezeBucket(EconomyCurrency currency, CardServicing.Bucket old) {
    long posted = minor(currency, old.residualMajor());
    long interest = Math.addExact(old.interestMinor(), posted);
    if (interest < 0) throw new IllegalArgumentException("Negative frozen interest");
    var next =
        new CardServicing.Bucket(
            old.kind(), old.principalMinor(), interest, ZERO, old.periodEarnedMajor(), old.apr());
    return new FrozenBucket(
        next, posted, old.residualMajor().subtract(major(currency, posted)).setScale(18));
  }

  private static Frozen freezeLoan(ReadView reads, UUID id, long effective) {
    var old =
        Loans.find(reads, id).orElseThrow(() -> new IllegalArgumentException("Loan unavailable"));
    if (old.status() != Loans.Status.ACTIVE
        || effective < old.servicing().lastAccrued()
        || effective >= old.nextMonth()) {
      throw new IllegalArgumentException("Loan default requires current scheduled work");
    }
    var accrued = LoanServicing.accrue(old.servicing(), effective);
    var unposted = accrued.residualMajor().add(accrued.accruedMajor());
    long recognized = minor(old.currency(), unposted);
    long interest = Math.addExact(accrued.interestMinor(), recognized);
    var frozen =
        new LoanServicing.State(
            accrued.currency(),
            accrued.apr(),
            accrued.principalMinor(),
            interest,
            ZERO,
            ZERO,
            major(old.currency(), interest).setScale(18),
            accrued.periodStart(),
            effective,
            accrued.installmentMinor(),
            accrued.completedPeriods(),
            accrued.totalPeriods(),
            false,
            accrued.requirements());
    var next = loan(old, frozen, Loans.Status.DEFAULTED);
    var accounts = new ArrayList<FinancialAccounts.Account>();
    var legs = new ArrayList<PostingBatch.Leg>();
    recognize(
        accounts,
        legs,
        old.world(),
        old.lender(),
        borrower(DebtRecords.Kind.LOAN, old.owner()),
        old.currency(),
        recognized);
    var facts =
        List.of(
            Loans.fact(next),
            freezeFact(
                old.world(),
                DebtRecords.Kind.LOAN,
                id,
                effective,
                recognized,
                unposted.subtract(major(old.currency(), recognized))));
    var commit = compose(reads, legs, accounts, facts, "DEBT_FROZEN", tx -> Loans.write(tx, next));
    return new Frozen(
        commit,
        old.world(),
        old.owner(),
        old.lender(),
        old.currency(),
        frozen.principalMinor(),
        interest);
  }

  /**
   * Returns only money effects. The caller persists SOLD and the new creditor with this same
   * commit.
   */
  public static Decision sale(ReadView reads, DebtRecords.Case debt, long effective) {
    requireFrozen(reads, debt);
    if (debt.sale() != DebtRecords.Sale.PENDING
        || debt.collectability() != DebtRecords.Collectability.COLLECTIBLE
        || debt.payment() == DebtRecords.Payment.SETTLED
        || effective < debt.defaultAt()
        || effective >= debt.collectionExpires()) {
      return decline("DEBT_SALE_UNAVAILABLE");
    }
    long price = purchasePrice(debt.principalMinor());
    Commit funding = null;
    ReadView fundedReads = reads;
    if (price > 0) {
      var decision =
          CreditTreasury.reserve(
              reads, debt.world(), InstitutionCatalog.WAYSTONE, debt.currency(), price, effective);
      if (!(decision instanceof Commit commit)) return decision;
      funding =
          new Commit(
              commit.records().stream()
                  .map(
                      record ->
                          record.type().equals(CreditTreasury.FACT)
                              ? new Journal.Record(TREASURY_FACT, 1, record.payload())
                              : record)
                  .toList(),
              commit.result(),
              commit.projections());
      fundedReads = FeeSettlement.overlay(reads, funding.records());
    }
    long face = debt.debtMinor();
    long discount = Math.subtractExact(face, price);
    var sellerCash =
        account(debt.world(), debt.creditor(), FinancialAccounts.Role.CASH, debt.currency());
    var sellerClaim =
        account(debt.world(), debt.creditor(), FinancialAccounts.Role.RECEIVABLE, debt.currency());
    var sellerLoss =
        account(debt.world(), debt.creditor(), FinancialAccounts.Role.EXPENSE, debt.currency());
    var collectorReserve =
        account(
            debt.world(),
            InstitutionCatalog.WAYSTONE,
            FinancialAccounts.Role.RESERVATION,
            debt.currency());
    var collectorClaim =
        account(
            debt.world(),
            InstitutionCatalog.WAYSTONE,
            FinancialAccounts.Role.RECEIVABLE,
            debt.currency());
    var collectorAllowance =
        account(
            debt.world(),
            InstitutionCatalog.WAYSTONE,
            FinancialAccounts.Role.ALLOWANCE,
            debt.currency());
    var accounts =
        List.of(
            sellerCash,
            sellerClaim,
            sellerLoss,
            collectorReserve,
            collectorClaim,
            collectorAllowance);
    var legs = new ArrayList<PostingBatch.Leg>();
    add(legs, sellerCash, price);
    add(legs, sellerClaim, -face);
    add(legs, sellerLoss, discount);
    add(legs, collectorReserve, -price);
    add(legs, collectorClaim, face);
    add(legs, collectorAllowance, -discount);
    var decision = InstitutionSettlement.prepare(fundedReads, legs, accounts);
    if (!(decision instanceof Commit financial)) return decision;
    var records = new ArrayList<Journal.Record>();
    if (funding != null) records.addAll(funding.records());
    records.addAll(accountFacts(fundedReads, accounts));
    records.addAll(financial.records());
    records.add(accountingFact(debt, "SALE", effective, face, discount));
    Commit reserved = funding;
    return new Commit(
        records,
        new Journal.Result(1, "DEBT_SOLD", Long.toString(price)),
        tx -> {
          if (reserved != null) reserved.projections().apply(tx);
          register(tx, accounts);
          financial.projections().apply(tx);
        });
  }

  public static long purchasePrice(long principalMinor) {
    if (principalMinor < 0) throw new IllegalArgumentException("Collection principal");
    return principalMinor == 0 ? 0 : Math.max(1, principalMinor / 10);
  }

  public static PaymentPlan payment(
      ReadView reads,
      DebtRecords.Case debt,
      long amount,
      long effective,
      List<PostingBatch.Leg> fundingLegs,
      List<FinancialAccounts.Account> planned) {
    requireFrozen(reads, debt);
    if (amount <= 0
        || amount > debt.debtMinor()
        || debt.payment() == DebtRecords.Payment.SETTLED
        || debt.collectability() != DebtRecords.Collectability.COLLECTIBLE
        || effective < debt.defaultAt()
        || effective >= debt.collectionExpires())
      throw new IllegalArgumentException("Collection payment unavailable");
    long discount =
        amount == debt.debtMinor()
            ? debt.discountRemainingMinor()
            : BigInteger.valueOf(debt.discountRemainingMinor())
                .multiply(BigInteger.valueOf(amount))
                .divide(BigInteger.valueOf(debt.debtMinor()))
                .longValueExact();
    var cash = account(debt.world(), debt.creditor(), FinancialAccounts.Role.CASH, debt.currency());
    var claim =
        account(debt.world(), debt.creditor(), FinancialAccounts.Role.RECEIVABLE, debt.currency());
    var liability =
        account(
            debt.world(),
            borrower(debt.kind(), debt.owner()),
            FinancialAccounts.Role.LIABILITY,
            debt.currency());
    var accounts = new ArrayList<>(planned);
    accounts.addAll(List.of(cash, claim, liability));
    var legs = new ArrayList<>(fundingLegs);
    add(legs, cash, amount);
    add(legs, claim, -amount);
    add(legs, liability, amount);
    if (discount > 0) {
      var allowance =
          account(debt.world(), debt.creditor(), FinancialAccounts.Role.ALLOWANCE, debt.currency());
      var income =
          account(debt.world(), debt.creditor(), FinancialAccounts.Role.INCOME, debt.currency());
      accounts.addAll(List.of(allowance, income));
      add(legs, allowance, discount);
      add(legs, income, -discount);
    }
    if (debt.kind() == DebtRecords.Kind.CARD) {
      var old = CreditFacilities.find(reads, debt.facility()).orElseThrow();
      var paid =
          CardServicing.repay(
              old.currency(),
              List.of(old.purchase(), old.advance()),
              old.requirements(),
              old.slices(),
              amount,
              effective);
      var purchase =
          paid.buckets().stream()
              .filter(b -> b.kind() == CardServicing.Kind.PURCHASE)
              .findFirst()
              .orElseThrow();
      var advance =
          paid.buckets().stream()
              .filter(b -> b.kind() == CardServicing.Kind.ADVANCE)
              .findFirst()
              .orElseThrow();
      var next =
          card(
              old,
              purchase,
              advance,
              paid.requirements().stream().filter(r -> r.unmetMinor() > 0).toList(),
              paid.slices().stream()
                  .filter(s -> s.principalMinor() > 0 || s.interestMinor() > 0)
                  .toList(),
              amount == debt.debtMinor()
                  ? CreditFacilities.Status.SETTLED
                  : CreditFacilities.Status.DEFAULTED);
      var commit =
          compose(
              reads,
              legs,
              accounts,
              List.of(
                  CreditFacilities.fact(next),
                  accountingFact(debt, "PAYMENT", effective, amount, discount)),
              "COLLECTION_PAID",
              tx -> CreditFacilities.write(tx, next));
      return new PaymentPlan(
          commit,
          next.principalMinor(),
          Math.subtractExact(next.owedMinor(), next.principalMinor()),
          discount);
    }
    var old = Loans.find(reads, debt.facility()).orElseThrow();
    var paid = LoanServicing.repay(old.servicing(), amount, old.servicing().lastAccrued());
    var next =
        loan(
            old,
            paid.state(),
            paid.state().closed() ? Loans.Status.SETTLED : Loans.Status.DEFAULTED);
    var commit =
        compose(
            reads,
            legs,
            accounts,
            List.of(Loans.fact(next), accountingFact(debt, "PAYMENT", effective, amount, discount)),
            "COLLECTION_PAID",
            tx -> Loans.write(tx, next));
    return new PaymentPlan(
        commit, next.servicing().principalMinor(), next.servicing().interestMinor(), discount);
  }

  public static Commit writeOff(ReadView reads, DebtRecords.Case debt) {
    requireFrozen(reads, debt);
    if (debt.payment() == DebtRecords.Payment.SETTLED
        || debt.collectability() != DebtRecords.Collectability.COLLECTIBLE) {
      throw new IllegalArgumentException("Write-off unavailable");
    }
    long loss = Math.subtractExact(debt.debtMinor(), debt.discountRemainingMinor());
    var expense =
        account(debt.world(), debt.creditor(), FinancialAccounts.Role.EXPENSE, debt.currency());
    var allowance =
        account(debt.world(), debt.creditor(), FinancialAccounts.Role.ALLOWANCE, debt.currency());
    var legs = new ArrayList<PostingBatch.Leg>();
    add(legs, expense, loss);
    add(legs, allowance, -loss);
    var facts = new ArrayList<Journal.Record>();
    facts.add(accountingFact(debt, "WRITE_OFF", debt.collectionExpires(), debt.debtMinor(), loss));
    Projection clearRequirements;
    if (debt.kind() == DebtRecords.Kind.CARD) {
      var old = CreditFacilities.find(reads, debt.facility()).orElseThrow();
      var next =
          card(
              old,
              old.purchase(),
              old.advance(),
              List.of(),
              List.of(),
              CreditFacilities.Status.DEFAULTED);
      facts.add(CreditFacilities.fact(next));
      clearRequirements = tx -> CreditFacilities.write(tx, next);
    } else {
      var old = Loans.find(reads, debt.facility()).orElseThrow();
      var state = old.servicing();
      var cleared =
          new LoanServicing.State(
              state.currency(),
              state.apr(),
              state.principalMinor(),
              state.interestMinor(),
              state.residualMajor(),
              state.accruedMajor(),
              state.capitalizedInterestMajor(),
              state.periodStart(),
              state.lastAccrued(),
              state.installmentMinor(),
              state.completedPeriods(),
              state.totalPeriods(),
              false,
              List.of());
      var next = loan(old, cleared, Loans.Status.DEFAULTED);
      facts.add(Loans.fact(next));
      clearRequirements = tx -> Loans.write(tx, next);
    }
    return compose(
        reads, legs, List.of(expense, allowance), facts, "DEBT_WRITTEN_OFF", clearRequirements);
  }

  private static void requireFrozen(ReadView reads, DebtRecords.Case debt) {
    if (debt.kind() == DebtRecords.Kind.CARD) {
      var source =
          CreditFacilities.find(reads, debt.facility())
              .orElseThrow(() -> new IllegalArgumentException("Credit source unavailable"));
      if (source.status() != CreditFacilities.Status.DEFAULTED
          || !source.world().equals(debt.world())
          || !source.owner().equals(debt.owner())
          || !source.issuer().equals(debt.origin())
          || source.currency() != debt.currency()
          || source.principalMinor() != debt.principalMinor()
          || source.owedMinor() != debt.debtMinor()
          || source.access() != CreditFacilities.Access.CLOSED)
        throw new IllegalArgumentException("Frozen credit binding");
    } else {
      var source =
          Loans.find(reads, debt.facility())
              .orElseThrow(() -> new IllegalArgumentException("Loan source unavailable"));
      if (source.status() != Loans.Status.DEFAULTED
          || !source.world().equals(debt.world())
          || !source.owner().equals(debt.owner())
          || !source.lender().equals(debt.origin())
          || source.currency() != debt.currency()
          || source.servicing().principalMinor() != debt.principalMinor()
          || source.owedMinor() != debt.debtMinor()
          || source.servicing().residualMajor().signum() != 0
          || source.servicing().accruedMajor().signum() != 0) {
        throw new IllegalArgumentException("Frozen loan binding");
      }
    }
  }

  private static CreditFacilities.Facility card(
      CreditFacilities.Facility old,
      CardServicing.Bucket purchase,
      CardServicing.Bucket advance,
      List<CardServicing.Requirement> requirements,
      List<CardServicing.DebtSlice> slices,
      CreditFacilities.Status status) {
    return new CreditFacilities.Facility(
        old.world(),
        old.id(),
        old.owner(),
        old.issuer(),
        old.network(),
        old.currency(),
        old.limitMinor(),
        old.tier(),
        old.anchor(),
        old.lastDay(),
        old.lastStatement(),
        Math.addExact(old.revision(), 1),
        status,
        CreditFacilities.Access.CLOSED,
        purchase,
        advance,
        requirements,
        slices);
  }

  private static Loans.Contract loan(
      Loans.Contract old, LoanServicing.State state, Loans.Status status) {
    return new Loans.Contract(
        old.world(),
        old.id(),
        old.owner(),
        old.lender(),
        old.product(),
        old.currency(),
        old.originalPrincipalMinor(),
        old.years(),
        old.apr(),
        old.primeApr(),
        old.primeVersion(),
        old.capturedScore(),
        old.profileRevision(),
        old.tier(),
        old.policyVersion(),
        old.denominationRevision(),
        old.fxPublication(),
        old.worldCurrency(),
        old.anchor(),
        old.scheduleHash(),
        Math.addExact(old.revision(), 1),
        status,
        state,
        old.primePolicyId(),
        old.primePolicyRevision());
  }

  private static void recognize(
      List<FinancialAccounts.Account> accounts,
      List<PostingBatch.Leg> legs,
      WorldId world,
      InstitutionCatalog.InstitutionId lender,
      InstitutionCatalog.InstitutionId borrower,
      EconomyCurrency currency,
      long amount) {
    if (amount == 0) return;
    var claim = account(world, lender, FinancialAccounts.Role.RECEIVABLE, currency);
    var income = account(world, lender, FinancialAccounts.Role.INCOME, currency);
    var expense = account(world, borrower, FinancialAccounts.Role.EXPENSE, currency);
    var liability = account(world, borrower, FinancialAccounts.Role.LIABILITY, currency);
    accounts.addAll(List.of(claim, income, expense, liability));
    legs.addAll(
        List.of(
            leg(claim, amount),
            leg(income, -amount),
            leg(expense, amount),
            leg(liability, -amount)));
  }

  private static Commit compose(
      ReadView reads,
      List<PostingBatch.Leg> legs,
      List<FinancialAccounts.Account> accounts,
      List<Journal.Record> extraFacts,
      String code,
      Projection projection) {
    Commit financial = null;
    var records = new ArrayList<>(extraFacts);
    records.addAll(accountFacts(reads, accounts));
    if (!legs.isEmpty()) {
      var decision = InstitutionSettlement.prepare(reads, legs, accounts);
      if (!(decision instanceof Commit commit)) throw new FinancialRejection(decision);
      financial = commit;
      records.addAll(commit.records());
    }
    if (records.isEmpty()) throw new IllegalArgumentException("Empty debt financial commit");
    Commit money = financial;
    return new Commit(
        records,
        new Journal.Result(1, code, ""),
        tx -> {
          register(tx, accounts);
          if (money != null) money.projections().apply(tx);
          projection.apply(tx);
        });
  }

  private static FinancialAccounts.Account account(
      WorldId world,
      InstitutionCatalog.InstitutionId owner,
      FinancialAccounts.Role role,
      EconomyCurrency currency) {
    return FinancialAccounts.account(world, owner, role, currency);
  }

  private static InstitutionCatalog.InstitutionId borrower(DebtRecords.Kind kind, PlayerId owner) {
    return new InstitutionCatalog.InstitutionId(
        (kind == DebtRecords.Kind.CARD ? "economy:borrower/" : "economy:loan-borrower/")
            + owner.value());
  }

  private static void add(
      List<PostingBatch.Leg> legs, FinancialAccounts.Account account, long amount) {
    if (amount != 0) legs.add(leg(account, amount));
  }

  private static PostingBatch.Leg leg(FinancialAccounts.Account account, long amount) {
    return new PostingBatch.Leg(
        account.id(),
        account.role() == FinancialAccounts.Role.CASH
            ? PostingBatch.Role.INSTITUTION_CASH
            : PostingBatch.Role.INSTITUTION_CLAIM,
        account.currency(),
        new Money(account.currency(), amount));
  }

  private static List<Journal.Record> accountFacts(
      ReadView reads, List<FinancialAccounts.Account> accounts) {
    return accounts.stream()
        .distinct()
        .filter(a -> FinancialAccounts.find(reads, a.id()).isEmpty())
        .map(
            a ->
                new Journal.Record(
                    "economy:a6/institution-account",
                    a.role() == FinancialAccounts.Role.ALLOWANCE ? 2 : 1,
                    a.id()
                        + "|"
                        + a.world().value()
                        + "|"
                        + a.owner().key()
                        + "|"
                        + a.role()
                        + "|"
                        + a.currency()))
        .toList();
  }

  private static void register(
      FoundationStore.Transaction tx, List<FinancialAccounts.Account> accounts) {
    for (var account : accounts.stream().distinct().toList())
      FinancialAccounts.register(tx, account);
  }

  private static long minor(EconomyCurrency currency, BigDecimal major) {
    return major
        .multiply(BigDecimal.valueOf(currency.minorUnitsPerMajor()))
        .setScale(0, RoundingMode.HALF_EVEN)
        .longValueExact();
  }

  private static BigDecimal major(EconomyCurrency currency, long minor) {
    return BigDecimal.valueOf(minor).divide(BigDecimal.valueOf(currency.minorUnitsPerMajor()));
  }

  private static Journal.Record freezeFact(
      WorldId world,
      DebtRecords.Kind kind,
      UUID facility,
      long effective,
      long recognized,
      BigDecimal disposition) {
    return new Journal.Record(
        FREEZE_FACT,
        1,
        String.join(
            "|",
            world.value().toString(),
            kind.name(),
            facility.toString(),
            Long.toString(effective),
            Long.toString(recognized),
            disposition.setScale(18).toPlainString()));
  }

  private static Journal.Record accountingFact(
      DebtRecords.Case debt, String event, long effective, long amount, long allowance) {
    return new Journal.Record(
        ACCOUNTING_FACT,
        1,
        String.join(
            "|",
            debt.world().value().toString(),
            debt.id().toString(),
            event,
            Long.toString(effective),
            Long.toString(amount),
            Long.toString(allowance)));
  }

  public static void validateAccounting(String payload) {
    var fields = payload.split("\\|", -1);
    if (fields.length != 6 || !Set.of("SALE", "PAYMENT", "WRITE_OFF").contains(fields[2])) {
      throw new IllegalArgumentException("Debt accounting fields");
    }
    uuid(fields[0]);
    uuid(fields[1]);
    long effective = number(fields[3]);
    long amount = number(fields[4]);
    long allowance = number(fields[5]);
    if (effective < 0 || amount < 0 || allowance < 0 || allowance > amount)
      throw new IllegalArgumentException("Debt accounting amount");
  }

  private static Decline decline(String code) {
    return new Decline(new Journal.Result(1, code, ""));
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog
        .with(new RecordCatalog.Key(FREEZE_FACT, 1), DebtFinance::validateFreeze)
        .with(new RecordCatalog.Key(TREASURY_FACT, 1), CreditTreasury::validateCollectionFunding)
        .with(new RecordCatalog.Key(ACCOUNTING_FACT, 1), DebtFinance::validateAccounting)
        .with(
            new RecordCatalog.Key("economy:a6/institution-account", 2),
            DebtFinance::validateAllowanceAccount);
  }

  public static void validateAllowanceAccount(String payload) {
    var fields = payload.split("\\|", -1);
    if (fields.length != 5 || !fields[3].equals("ALLOWANCE"))
      throw new IllegalArgumentException("Allowance account fields");
    var world = new WorldId(uuid(fields[1]));
    var owner = new InstitutionCatalog.InstitutionId(fields[2]);
    var currency = EconomyCurrency.valueOf(fields[4]);
    InstitutionCatalog.require(owner, currency);
    new FinancialAccounts.Account(
        uuid(fields[0]), world, owner, FinancialAccounts.Role.ALLOWANCE, currency);
  }

  private static UUID uuid(String text) {
    var value = UUID.fromString(text);
    if (!value.toString().equals(text)) throw new IllegalArgumentException("Debt financial UUID");
    return value;
  }

  private static long number(String text) {
    long value = Long.parseLong(text);
    if (!Long.toString(value).equals(text))
      throw new IllegalArgumentException("Debt financial integer");
    return value;
  }

  public static void validateFreeze(String payload) {
    var fields = payload.split("\\|", -1);
    if (fields.length != 6) throw new IllegalArgumentException("Debt freeze fields");
    uuid(fields[0]);
    DebtRecords.Kind.valueOf(fields[1]);
    uuid(fields[2]);
    if (number(fields[3]) < 0) throw new IllegalArgumentException("Debt freeze instant");
    number(fields[4]);
    var disposition = new BigDecimal(fields[5]);
    if (disposition.scale() != 18
        || disposition.precision() > 50
        || !disposition.toPlainString().equals(fields[5])) {
      throw new IllegalArgumentException("Debt freeze residual");
    }
  }
}
