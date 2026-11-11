package ca.emilybelnavis.mcmods.economy.debt;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebtFinanceTest {
  private static final EconomyCurrency CAD = EconomyCurrency.CAD;
  private static final InstitutionCatalog.InstitutionId BANK =
      new InstitutionCatalog.InstitutionId("economy:bank/crownstone");
  private static final long DEFAULT = 126 * DebtRecords.DAY;
  @TempDir Path path;

  private final class Fixture implements AutoCloseable {
    final SqliteStorage store = new SqliteStorage();
    final PlayerId player = new PlayerId(UUID.randomUUID());
    final AccountId funding = new AccountId(UUID.randomUUID());
    final UUID facility = UUID.randomUUID();
    final WorldId world;
    final CreditFacilities.Facility initial;

    Fixture() throws Exception {
      this(false);
    }

    Fixture(boolean interestOnly) throws Exception {
      store.initialize(path.toRealPath());
      world = store.snapshot().worldId();
      committed(
          submit(
              FxService.OPEN,
              new Journal.CanonicalRequest(1, Map.of("currency", "CAD")),
              FxService.opening(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))));
      var capital =
          new InstitutionCapital(
              store, new SystemActorId(UUID.randomUUID()), CapitalPosting.poster());
      for (int turn = 0; turn < 26; turn++) committed(capital.advance(Instant.EPOCH));
      var borrower = borrower();
      store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO institution_parties VALUES(?,?,?,?,?,'INTERNAL',1)",
                List.of(
                    world.value().toString(),
                    borrower.key(),
                    new InstitutionCatalog.SystemActor(world, borrower)
                        .actorId()
                        .value()
                        .toString(),
                    "Fixture borrower",
                    "CAD"));
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                List.of(funding.value().toString(), player.value().toString()));
            return null;
          });
      var purchase =
          new CardServicing.Bucket(
              CardServicing.Kind.PURCHASE,
              interestOnly ? 0 : 10000,
              500,
              new BigDecimal("0.002000000000000000"),
              new BigDecimal("1.000000000000000000"),
              new BigDecimal("0.1999"));
      var advance =
          new CardServicing.Bucket(
              CardServicing.Kind.ADVANCE,
              interestOnly ? 0 : 5000,
              50,
              new BigDecimal("-0.001000000000000000"),
              new BigDecimal("0.100000000000000000"),
              new BigDecimal("0.2199"));
      String statement = UUID.randomUUID().toString();
      var requirement =
          new CardServicing.Requirement(
              statement, 28 * DebtRecords.DAY, 42 * DebtRecords.DAY, 300, 300);
      initial =
          new CreditFacilities.Facility(
              world,
              facility,
              player,
              BANK,
              InstitutionCatalog.Network.ORBIT,
              CAD,
              250000,
              1,
              0,
              DEFAULT,
              112 * DebtRecords.DAY,
              1,
              CreditFacilities.Status.ACTIVE,
              CreditFacilities.Access.ENABLED,
              purchase,
              advance,
              List.of(requirement),
              List.of());
      var cash = financial(BANK, FinancialAccounts.Role.CASH);
      var reserve = financial(BANK, FinancialAccounts.Role.RESERVATION);
      var claim = financial(BANK, FinancialAccounts.Role.RECEIVABLE);
      var liability = financial(borrower, FinancialAccounts.Role.LIABILITY);
      var accounts = List.of(cash, reserve, claim, liability);
      long undrawn = initial.limitMinor() - initial.principalMinor();
      var legs =
          List.of(
              leg(cash, -undrawn),
              leg(reserve, undrawn),
              leg(claim, initial.owedMinor()),
              leg(liability, -initial.owedMinor()));
      committed(
          submit(
              new Journal.Kind("fixture:debt-finance", "fixture:seed"),
              new Journal.CanonicalRequest(1, Map.of()),
              new JournalEngine.Registration(
                  1,
                  (intent, reads) -> {
                    var money =
                        (JournalEngine.Commit) InstitutionSettlement.prepare(reads, legs, accounts);
                    var facts = new ArrayList<>(money.records());
                    facts.add(CreditFacilities.fact(initial));
                    for (var account : accounts)
                      if (FinancialAccounts.find(reads, account.id()).isEmpty())
                        facts.add(accountFact(account));
                    return new JournalEngine.Commit(
                        facts,
                        new Journal.Result(1, "SEEDED", ""),
                        tx -> {
                          for (var account : accounts) FinancialAccounts.register(tx, account);
                          money.projections().apply(tx);
                          CreditFacilities.write(tx, initial);
                        });
                  })));
      var customerLegs =
          List.of(
              new PostingBatch.Leg(
                  Settlement.issuanceAccount(CAD),
                  PostingBatch.Role.ISSUANCE,
                  CAD,
                  new Money(CAD, -100000)),
              new PostingBatch.Leg(
                  funding.value(), PostingBatch.Role.ACCOUNT, CAD, new Money(CAD, 100000)));
      committed(
          submit(
              new Journal.Kind("fixture:debt-finance", "fixture:fund"),
              Settlement.minorRequest(customerLegs),
              Settlement.registrationMinor((intent, leg, reads) -> PostingBatch.Authority.ISSUE)));
    }

    InstitutionCatalog.InstitutionId borrower() {
      return new InstitutionCatalog.InstitutionId("economy:borrower/" + player.value());
    }

    FinancialAccounts.Account financial(
        InstitutionCatalog.InstitutionId party, FinancialAccounts.Role role) {
      return FinancialAccounts.account(world, party, role, CAD);
    }

    long balance(InstitutionCatalog.InstitutionId party, FinancialAccounts.Role role) {
      var id = financial(party, role).id();
      return store.transaction(tx -> Settlement.posted(tx::query, id, CAD));
    }

    Journal.Outcome submit(
        Journal.Kind kind, Journal.CanonicalRequest request, JournalEngine.Registration handler) {
      return new JournalEngine(store, (actor, requested) -> true, Map.of(kind, handler))
          .submit(
              new Journal.Intent(
                  new OperationId(UUID.randomUUID()),
                  new RequestId(UUID.randomUUID()),
                  player,
                  kind,
                  request,
                  null,
                  null));
    }

    Journal.Outcome commit(JournalEngine.Commit commit) {
      return submit(
          new Journal.Kind("fixture:debt-finance", "fixture:commit"),
          new Journal.CanonicalRequest(1, Map.of()),
          new JournalEngine.Registration(1, (intent, reads) -> commit));
    }

    DebtRecords.Case freeze() {
      var frozen =
          store.transaction(
              tx -> DebtFinance.freeze(tx::query, DebtRecords.Kind.CARD, facility, DEFAULT));
      committed(commit(frozen.commit()));
      return debt(
          frozen.principalMinor(),
          frozen.interestMinor(),
          BANK,
          DebtRecords.Sale.PENDING,
          0,
          DebtRecords.Payment.UNPAID,
          1);
    }

    DebtRecords.Case debt(
        long principal,
        long interest,
        InstitutionCatalog.InstitutionId creditor,
        DebtRecords.Sale sale,
        long discount,
        DebtRecords.Payment payment,
        long revision) {
      return new DebtRecords.Case(
          world,
          facility,
          DebtRecords.Kind.CARD,
          facility,
          player,
          BANK,
          creditor,
          CAD,
          principal,
          interest,
          DEFAULT,
          DEFAULT,
          DEFAULT + DebtRecords.COLLECTION_WINDOW,
          DEFAULT + DebtRecords.REPORT_WINDOW,
          sale,
          DebtRecords.Collectability.COLLECTIBLE,
          payment,
          discount,
          revision);
    }

    @Override
    public void close() throws Exception {
      store.close();
    }
  }

  @Test
  void freezeReleasesOnlyUndrawnCashAndPreservesOriginalRepayableClaim() throws Exception {
    try (var fixture = new Fixture()) {
      long cash = fixture.balance(BANK, FinancialAccounts.Role.CASH);
      var debt = fixture.freeze();
      var frozen =
          fixture.store.transaction(
              tx -> CreditFacilities.find(tx::query, fixture.facility).orElseThrow());
      assertEquals(CreditFacilities.Status.DEFAULTED, frozen.status());
      assertEquals(CreditFacilities.Access.CLOSED, frozen.access());
      assertEquals(fixture.initial.owedMinor(), debt.debtMinor());
      assertEquals(0, fixture.balance(BANK, FinancialAccounts.Role.RESERVATION));
      assertEquals(
          cash + fixture.initial.limitMinor() - fixture.initial.principalMinor(),
          fixture.balance(BANK, FinancialAccounts.Role.CASH));
      assertEquals(fixture.initial.requirements(), frozen.requirements());
      assertEquals(0, frozen.purchase().residualMajor().signum());
      assertEquals(0, frozen.advance().residualMajor().signum());
      assertEquals(
          -debt.debtMinor(), fixture.balance(fixture.borrower(), FinancialAccounts.Role.LIABILITY));
    }
  }

  @Test
  void salePaymentAndFinalRecoveryKeepFaceDiscountAndBorrowerDebtSeparate() throws Exception {
    try (var fixture = new Fixture()) {
      var pending = fixture.freeze();
      long sellerCash = fixture.balance(BANK, FinancialAccounts.Role.CASH);
      long collectorCash =
          fixture.balance(InstitutionCatalog.WAYSTONE, FinancialAccounts.Role.CASH);
      var sale = fixture.store.transaction(tx -> DebtFinance.sale(tx::query, pending, DEFAULT + 1));
      committed(fixture.commit((JournalEngine.Commit) sale));
      long price = 1500;
      long discount = pending.debtMinor() - price;
      assertEquals(sellerCash + price, fixture.balance(BANK, FinancialAccounts.Role.CASH));
      assertEquals(
          collectorCash - price,
          fixture.balance(InstitutionCatalog.WAYSTONE, FinancialAccounts.Role.CASH));
      assertEquals(0, fixture.balance(BANK, FinancialAccounts.Role.RECEIVABLE));
      assertEquals(
          pending.debtMinor(),
          fixture.balance(InstitutionCatalog.WAYSTONE, FinancialAccounts.Role.RECEIVABLE));
      assertEquals(
          -discount,
          fixture.balance(InstitutionCatalog.WAYSTONE, FinancialAccounts.Role.ALLOWANCE));
      var sold =
          fixture.debt(
              pending.principalMinor(),
              pending.interestMinor(),
              InstitutionCatalog.WAYSTONE,
              DebtRecords.Sale.SOLD,
              discount,
              DebtRecords.Payment.UNPAID,
              2);
      var funding =
          List.of(
              new PostingBatch.Leg(
                  fixture.funding.value(), PostingBatch.Role.ACCOUNT, CAD, new Money(CAD, -1000)));
      var partial =
          fixture.store.transaction(
              tx -> DebtFinance.payment(tx::query, sold, 1000, DEFAULT + 2, funding, List.of()));
      committed(fixture.commit(partial.commit()));
      assertEquals(discount * 1000 / pending.debtMinor(), partial.discountReleasedMinor());
      var remainder =
          fixture.debt(
              partial.principalMinor(),
              partial.interestMinor(),
              InstitutionCatalog.WAYSTONE,
              DebtRecords.Sale.SOLD,
              discount - partial.discountReleasedMinor(),
              DebtRecords.Payment.PARTIAL,
              3);
      var finalFunding =
          List.of(
              new PostingBatch.Leg(
                  fixture.funding.value(),
                  PostingBatch.Role.ACCOUNT,
                  CAD,
                  new Money(CAD, -remainder.debtMinor())));
      var paid =
          fixture.store.transaction(
              tx ->
                  DebtFinance.payment(
                      tx::query,
                      remainder,
                      remainder.debtMinor(),
                      DEFAULT + 3,
                      finalFunding,
                      List.of()));
      committed(fixture.commit(paid.commit()));
      assertEquals(0, paid.principalMinor() + paid.interestMinor());
      assertEquals(
          0, fixture.balance(InstitutionCatalog.WAYSTONE, FinancialAccounts.Role.RECEIVABLE));
      assertEquals(
          0, fixture.balance(InstitutionCatalog.WAYSTONE, FinancialAccounts.Role.ALLOWANCE));
      assertEquals(0, fixture.balance(fixture.borrower(), FinancialAccounts.Role.LIABILITY));
      assertEquals(
          discount, -fixture.balance(InstitutionCatalog.WAYSTONE, FinancialAccounts.Role.INCOME));
    }
  }

  @Test
  void expiryWriteoffRetainsHistoricalFaceAndLiabilityAndRejectsLatePayment() throws Exception {
    try (var fixture = new Fixture()) {
      var pending = fixture.freeze();
      long debtor = fixture.balance(fixture.borrower(), FinancialAccounts.Role.LIABILITY);
      long cash = fixture.balance(BANK, FinancialAccounts.Role.CASH);
      committed(
          fixture.commit(
              fixture.store.transaction(tx -> DebtFinance.writeOff(tx::query, pending))));
      assertEquals(pending.debtMinor(), fixture.balance(BANK, FinancialAccounts.Role.RECEIVABLE));
      assertEquals(-pending.debtMinor(), fixture.balance(BANK, FinancialAccounts.Role.ALLOWANCE));
      assertEquals(debtor, fixture.balance(fixture.borrower(), FinancialAccounts.Role.LIABILITY));
      assertEquals(cash, fixture.balance(BANK, FinancialAccounts.Role.CASH));
      var funding =
          List.of(
              new PostingBatch.Leg(
                  fixture.funding.value(), PostingBatch.Role.ACCOUNT, CAD, new Money(CAD, -1)));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.store.transaction(
                  tx ->
                      DebtFinance.payment(
                          tx::query, pending, 1, pending.collectionExpires(), funding, List.of())));
      assertEquals(1, DebtFinance.purchasePrice(1));
      assertEquals(0, DebtFinance.purchasePrice(0));
    }
  }

  @Test
  void pureInterestSaleHasZeroConsiderationAndZeroCarryingWriteoffStillClosesRequirements()
      throws Exception {
    try (var fixture = new Fixture(true)) {
      var pending = fixture.freeze();
      assertEquals(0, pending.principalMinor());
      long cash = fixture.balance(InstitutionCatalog.WAYSTONE, FinancialAccounts.Role.CASH);
      var sale = fixture.store.transaction(tx -> DebtFinance.sale(tx::query, pending, DEFAULT + 1));
      committed(fixture.commit((JournalEngine.Commit) sale));
      assertEquals(cash, fixture.balance(InstitutionCatalog.WAYSTONE, FinancialAccounts.Role.CASH));
      assertEquals(
          -pending.debtMinor(),
          fixture.balance(InstitutionCatalog.WAYSTONE, FinancialAccounts.Role.ALLOWANCE));
      var sold =
          fixture.debt(
              0,
              pending.interestMinor(),
              InstitutionCatalog.WAYSTONE,
              DebtRecords.Sale.SOLD,
              pending.debtMinor(),
              DebtRecords.Payment.UNPAID,
              2);
      committed(
          fixture.commit(fixture.store.transaction(tx -> DebtFinance.writeOff(tx::query, sold))));
      assertEquals(
          -pending.debtMinor(),
          fixture.balance(InstitutionCatalog.WAYSTONE, FinancialAccounts.Role.ALLOWANCE));
      assertEquals(0, fixture.balance(InstitutionCatalog.WAYSTONE, FinancialAccounts.Role.EXPENSE));
      var source =
          fixture.store.transaction(
              tx -> CreditFacilities.find(tx::query, fixture.facility).orElseThrow());
      assertTrue(source.requirements().isEmpty());
      assertTrue(source.slices().isEmpty());
      assertEquals(pending.debtMinor(), source.owedMinor());
    }
  }

  private static void committed(Journal.Outcome outcome) {
    assertEquals(Journal.Status.COMMITTED, outcome.status(), outcome.toString());
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

  private static Journal.Record accountFact(FinancialAccounts.Account account) {
    return new Journal.Record(
        "economy:a6/institution-account",
        1,
        account.id()
            + "|"
            + account.world().value()
            + "|"
            + account.owner().key()
            + "|"
            + account.role()
            + "|"
            + account.currency());
  }
}
