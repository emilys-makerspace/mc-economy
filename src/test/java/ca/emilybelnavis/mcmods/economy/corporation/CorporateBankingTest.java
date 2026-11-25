package ca.emilybelnavis.mcmods.economy.corporation;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorporateBankingTest {
  private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
  private static final Journal.Kind OPEN = new Journal.Kind("fixture:corporate", "fixture:open");
  private static final Journal.Kind PAY = new Journal.Kind("fixture:corporate", "fixture:pay");
  private static final Journal.Kind CLOSE = new Journal.Kind("fixture:corporate", "fixture:close");

  @TempDir Path path;
  private final SqliteStorage store = new SqliteStorage();
  private final PlayerId founder = new PlayerId(UUID.randomUUID());
  private final PlayerId manager = new PlayerId(UUID.randomUUID());
  private final UUID company = UUID.randomUUID();
  private final AccountId personal = new AccountId(UUID.randomUUID());

  @Test
  void contributionPaymentAndClosureUseOneLedgerAndSeparateCompanyAuthority() throws Exception {
    store.initialize(path.toRealPath());
    try {
      var personalOwner = new PartyRef(world(), new PartyRef.Player(founder));
      var companyOwner =
          new PartyRef(world(), new PartyRef.Organization(new OrganizationId(company)));
      var cadBank = bank(CAD);
      seed(cadBank);
      var companyCad = open(CAD);
      var companyUsd = open(USD);
      open(GBP);
      open(EUR);
      assertEquals("BANK_CAP_REACHED", openOutcome(JPY).result().code());
      assertEquals(
          4, store.transaction(tx -> CorporateAccounts.list(tx::query, world(), company)).size());

      var personalCad = endpoint(personalOwner, personal, CAD, 0);
      var corporateCad = endpoint(companyOwner, companyCad, CAD, 1);
      var corporateUsd = endpoint(companyOwner, companyUsd, USD, 1);
      var contribution =
          new CorporatePayments.Payment(
              personalCad,
              corporateCad,
              0,
              false,
              new CorporatePayments.Terms(10_000, 0, 10_000, null));
      var contributionIntent = intent(founder, PAY, contribution.fields());
      var committed = pay(contributionIntent, contribution);
      assertEquals("CORPORATE_PAYMENT_SETTLED", committed.result().code());
      assertEquals(10_000, balance(personal, CAD));
      assertEquals(10_000, balance(companyCad, CAD));
      var contributionRecords =
          store.transaction(
              tx ->
                  tx.query(
                      "SELECT record_type FROM journal_records WHERE sequence=(SELECT sequence FROM journal_transactions WHERE transaction_id=?)",
                      List.of(committed.transactionId().value().toString()),
                      64));
      assertEquals(
          2,
          contributionRecords.stream()
              .filter(row -> row.get("record_type").equals("economy:posting"))
              .count());

      appointViewer(personalOwner, companyCad);
      var denied =
          new CorporatePayments.Payment(
              corporateCad, personalCad, 1, false, new CorporatePayments.Terms(100, 0, 100, null));
      assertEquals(
          "CORPORATE_PAYMENT_AUTHORITY",
          pay(intent(manager, PAY, denied.fields()), denied).result().code());
      assertEquals(10_000, balance(companyCad, CAD));

      var fxTerms =
          store.transaction(
              tx ->
                  CorporatePayments.quote(
                          tx::query,
                          founder,
                          corporateCad,
                          corporateUsd,
                          0,
                          5_000,
                          false,
                          NOW.toEpochMilli())
                      .orElseThrow());
      assertEquals(25, fxTerms.fee());
      assertEquals(3_581, fxTerms.credit());
      var fxPayment = new CorporatePayments.Payment(corporateCad, corporateUsd, 0, false, fxTerms);
      assertEquals(
          "CORPORATE_PAYMENT_SETTLED",
          pay(intent(founder, PAY, fxPayment.fields()), fxPayment).result().code());
      assertEquals(4_975, balance(companyCad, CAD));
      assertEquals(3_581, balance(companyUsd, USD));
      var bankCash =
          FinancialAccounts.account(world(), cadBank.id(), FinancialAccounts.Role.CASH, CAD);
      assertEquals(
          25L,
          store
              .<Long>transaction(tx -> Settlement.posted(tx::query, bankCash.id(), CAD))
              .longValue());

      var close =
          new CorporateAccounts.Close(company, 1, 0, cadBank.id(), companyCad, 1, 1, personal, 1L);
      var drain =
          new CorporatePayments.Payment(
              corporateCad,
              personalCad,
              0,
              true,
              new CorporatePayments.Terms(4_975, 0, 4_975, null));
      var closeFields = new HashMap<>(close.fields());
      closeFields.putAll(drain.fields());
      var closed =
          submit(
              intent(founder, CLOSE, closeFields),
              (i, reads) ->
                  CorporateAccounts.prepare(
                      i,
                      reads,
                      close,
                      (operation, view, source, target, revision, full) ->
                          CorporatePayments.prepare(operation, view, drain, NOW.toEpochMilli())));
      assertEquals("CORPORATE_BANK_CLOSED", closed.result().code());
      assertEquals(0, balance(companyCad, CAD));
      assertEquals(14_975, balance(personal, CAD));
      assertEquals(committed, pay(contributionIntent, contribution));
      assertEquals(14_975, balance(personal, CAD));
      assertNotEquals(companyCad, open(CAD));
    } finally {
      store.close();
    }
  }

  @Test
  void initialContributionMustExactlyCoverIssuedSharesAndCardUsesCurrentAccountMembership()
      throws Exception {
    store.initialize(path.toRealPath());
    try {
      seed(bank(CAD));
      store.transaction(
          tx -> {
            tx.execute(
                "UPDATE corporation_registry SET funded=0 WHERE party=?",
                List.of(company.toString()));
            return null;
          });
      assertEquals("CORPORATION_CAPITAL_CURRENCY", openOutcome(USD).result().code());
      var opened = openOutcome(CAD);
      assertEquals("CORPORATE_BANK_OPENED", opened.result().code());
      var token = opened.operationId().value();
      var account = new AccountId(UUID.fromString(opened.result().payload()));
      var card =
          store
              .transaction(
                  tx ->
                      ca.emilybelnavis.mcmods.economy.card.CorporateCards.resolve(
                          tx::query, world(), token, founder))
              .orElseThrow();
      assertEquals(account, card.account());
      assertTrue(
          store
              .transaction(
                  tx ->
                      ca.emilybelnavis.mcmods.economy.card.CorporateCards.resolve(
                          tx::query, world(), token, manager))
              .isEmpty());
      var local = new PartyRef(world(), new PartyRef.Player(founder));
      appointViewer(local, account);
      assertEquals(
          card,
          store
              .transaction(
                  tx ->
                      ca.emilybelnavis.mcmods.economy.card.CorporateCards.resolve(
                          tx::query, world(), token, manager))
              .orElseThrow());
      assertEquals(
          Custody.State.COMMITTED,
          store
              .transaction(tx -> Custody.find(tx::query, founder, token, false))
              .orElseThrow()
              .state());
      var source = endpoint(local, personal, CAD, 0);
      var target =
          endpoint(
              new PartyRef(world(), new PartyRef.Organization(new OrganizationId(company))),
              account,
              CAD,
              1);
      var tooLittle =
          new CorporatePayments.Payment(
              source, target, 0, false, new CorporatePayments.Terms(9_999, 0, 9_999, null));
      assertEquals(
          "CORPORATION_CAPITAL_AMOUNT",
          pay(intent(founder, PAY, tooLittle.fields()), tooLittle).result().code());
      assertEquals(0, balance(account, CAD));
      var exact =
          new CorporatePayments.Payment(
              source, target, 0, false, new CorporatePayments.Terms(10_000, 0, 10_000, null));
      var request = intent(founder, PAY, exact.fields());
      assertEquals(Journal.Status.COMMITTED, pay(request, exact).status());
      assertEquals(Journal.Status.COMMITTED, pay(request, exact).status());
      assertEquals(10_000, balance(account, CAD));
      assertTrue(
          store
              .transaction(tx -> CorporateCapital.profile(tx::query, company))
              .orElseThrow()
              .funded());
    } finally {
      store.close();
    }
  }

  @Test
  void corporateCreditReservesRealFundsAndRepaysAccruedInterestBeforeClosing() throws Exception {
    store.initialize(path.toRealPath());
    try {
      var bank = bank(CAD);
      seed(bank);
      var account = open(CAD);
      var world = world();
      var cash = FinancialAccounts.account(world, bank.id(), FinancialAccounts.Role.CASH, CAD);
      store.transaction(
          tx -> {
            var w = world.value().toString();
            tx.execute(
                "INSERT INTO institution_capital_manifest VALUES(?,1,'ACTIVE','CAD',1,'2026-11-18','CAD','fixture','fixture','fixture',100000,25)",
                List.of(w));
            tx.execute(
                "INSERT INTO institution_capital_allocations VALUES(?,?,0,'CAD',100000,'CAD',100000,'1000','0','0','1',1,'fixture')",
                List.of(w, bank.id().key()));
            FinancialAccounts.register(tx, cash);
            LedgerProjection.write(tx, cash.id(), CAD, 100_000);
            LedgerProjection.write(tx, account.value(), CAD, 1_000);
            return null;
          });
      long now = NOW.toEpochMilli();
      var network = bank.networks().iterator().next();
      var apply =
          new CorporateCredit.Request(
              CorporateCredit.Action.APPLY,
              company,
              1,
              0,
              account,
              1,
              network,
              10_000,
              0,
              CorporateCredit.day(now));
      assertEquals("CORPORATION_UNAVAILABLE", credit(manager, apply, now).result().code());
      var opened = credit(founder, apply, now);
      assertEquals("CORPORATE_CREDIT_APPLY", opened.result().code());
      assertTrue(
          store
              .transaction(
                  tx ->
                      ca.emilybelnavis.mcmods.economy.card.CorporateCards.resolve(
                          tx::query, world, opened.operationId().value(), founder))
              .isPresent());
      var borrow =
          new CorporateCredit.Request(
              CorporateCredit.Action.BORROW,
              company,
              1,
              0,
              account,
              1,
              network,
              5_000,
              1,
              CorporateCredit.day(now));
      assertEquals("CORPORATE_CREDIT_BORROW", credit(founder, borrow, now).result().code());
      assertEquals(6_000, balance(account, CAD));
      var closeAccount =
          new CorporateAccounts.Close(company, 1, 0, bank.id(), account, 1, 1, null, null);
      assertEquals(
          "UNRESOLVED_OBLIGATION",
          submit(
                  intent(founder, CLOSE, closeAccount.fields()),
                  (i, reads) -> CorporateAccounts.prepare(i, reads, closeAccount, null))
              .result()
              .code());
      long later = now + 28 * ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DAY;
      var line =
          store.transaction(tx -> CorporateCredit.find(tx::query, account, later)).orElseThrow();
      assertTrue(line.interest() > 0);
      var close =
          new CorporateCredit.Request(
              CorporateCredit.Action.CLOSE,
              company,
              1,
              0,
              account,
              1,
              network,
              0,
              2,
              CorporateCredit.day(later));
      assertEquals("CORPORATE_CREDIT_OUTSTANDING", credit(founder, close, later).result().code());
      var repay =
          new CorporateCredit.Request(
              CorporateCredit.Action.REPAY,
              company,
              1,
              0,
              account,
              1,
              network,
              line.debt(),
              2,
              CorporateCredit.day(later));
      assertEquals("CORPORATE_CREDIT_REPAY", credit(founder, repay, later).result().code());
      assertEquals(1_000 - line.interest(), balance(account, CAD));
      var release =
          new CorporateCredit.Request(
              CorporateCredit.Action.CLOSE,
              company,
              1,
              0,
              account,
              1,
              network,
              0,
              3,
              CorporateCredit.day(later));
      assertEquals("CORPORATE_CREDIT_CLOSE", credit(founder, release, later).result().code());
      assertEquals(100_000 + line.interest(), balance(new AccountId(cash.id()), CAD));
      assertTrue(
          store
              .transaction(
                  tx ->
                      ca.emilybelnavis.mcmods.economy.card.CorporateCards.resolve(
                          tx::query, world, opened.operationId().value(), founder))
              .isEmpty());
      var catalog = ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog.core();
      store.transaction(
          tx -> {
            for (var record :
                tx.query(
                    "SELECT record_type,record_version,payload FROM journal_records ORDER BY sequence,position",
                    List.of(),
                    256))
              catalog.validate(
                  record.get("record_type"),
                  Integer.parseInt(record.get("record_version")),
                  record.get("payload"));
            return null;
          });
    } finally {
      store.close();
    }
  }

  private Journal.Outcome credit(PlayerId actor, CorporateCredit.Request request, long now) {
    return submit(
        intent(actor, PAY, request.fields()),
        (i, reads) -> CorporateCredit.prepare(i, reads, world(), actor, request, now));
  }

  private void seed(InstitutionCatalog.Entry cadBank) {
    var fxActor = new SystemActorId(UUID.randomUUID());
    var fx = intent(fxActor, FxService.OPEN, Map.of("currency", CAD.code()));
    assertEquals(
        Journal.Status.COMMITTED,
        submit(fx, FxService.opening(Clock.fixed(NOW, ZoneOffset.UTC)).handler()).status());
    store.transaction(
        tx -> {
          for (var currency : List.of(CAD, USD, GBP, EUR, JPY)) {
            var bank = bank(currency);
            tx.execute(
                "INSERT INTO institution_parties VALUES(?,?,?,?,?,?,1)",
                List.of(
                    world().value().toString(),
                    bank.id().key(),
                    new InstitutionCatalog.SystemActor(world(), bank.id())
                        .actorId()
                        .value()
                        .toString(),
                    bank.name(),
                    currency.code(),
                    bank.tier().name()));
          }
          tx.execute(
              "INSERT INTO corporations VALUES(?,?,'Test Workshop','ACTIVE',1)",
              List.of(company.toString(), founder.value().toString()));
          tx.execute(
              "INSERT INTO corporation_registry(identifier,party,funded) VALUES('test-workshop',?,1)",
              List.of(company.toString()));
          tx.execute(
              "INSERT INTO corporation_shares(company,class,quantity,price_minor,currency) VALUES('test-workshop','A',100,100,'CAD')",
              List.of());
          tx.execute(
              "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
              List.of(personal.value().toString(), founder.value().toString()));
          tx.execute(
              "INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",
              List.of(personal.value().toString(), cadBank.id().key()));
          LedgerProjection.write(tx, personal.value(), CAD, 20_000);
          return null;
        });
  }

  private void appointViewer(PartyRef personalOwner, AccountId scope) {
    var change =
        new Managers.Change(
            company,
            1,
            manager.value(),
            0,
            Managers.Mutation.APPOINT,
            Managers.Rights.viewOnly(new Managers.Scope(false, Set.of(scope.value()))),
            false);
    var fields = new HashMap<>(change.request().fields());
    fields.put("economy:owner", Journal.actorKey(founder));
    assertEquals(
        Journal.Status.COMMITTED,
        submit(
                intent(founder, Managers.KIND, fields),
                Managers.mutation(personalOwner, change).handler())
            .status());
  }

  private AccountId open(EconomyCurrency currency) {
    var opened = openOutcome(currency);
    assertEquals("CORPORATE_BANK_OPENED", opened.result().code());
    return new AccountId(UUID.fromString(opened.result().payload()));
  }

  private Journal.Outcome openOutcome(EconomyCurrency currency) {
    var command = new CorporateAccounts.Open(company, 1, 0, bank(currency).id(), currency);
    return submit(
        intent(founder, OPEN, command.fields()),
        (i, reads) -> CorporateAccounts.prepare(i, reads, command));
  }

  private Journal.Outcome pay(Journal.Intent intent, CorporatePayments.Payment payment) {
    return submit(
        intent, (i, reads) -> CorporatePayments.prepare(i, reads, payment, NOW.toEpochMilli()));
  }

  private Journal.Outcome submit(Journal.Intent intent, JournalEngine.Handler handler) {
    return new JournalEngine(
            store,
            (actor, kind) -> actor.equals(intent.actor()),
            Map.of(intent.kind(), new JournalEngine.Registration(1, handler)))
        .submit(intent);
  }

  private Journal.Intent intent(ActorId actor, Journal.Kind kind, Map<String, String> fields) {
    var request = new HashMap<>(fields);
    if (!kind.equals(Managers.KIND)) request.put("world", world().value().toString());
    return new Journal.Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        actor,
        kind,
        new Journal.CanonicalRequest(1, request),
        null,
        null);
  }

  private static InstitutionCatalog.Entry bank(EconomyCurrency currency) {
    return InstitutionCatalog.banks().stream()
        .filter(entry -> entry.baseCurrency() == currency && entry.tier1())
        .findFirst()
        .orElseThrow();
  }

  private static CorporatePayments.Endpoint endpoint(
      PartyRef owner, AccountId account, EconomyCurrency currency, long companyRevision) {
    return new CorporatePayments.Endpoint(
        owner, account, 1, bank(currency).id().key(), 1, companyRevision);
  }

  private long balance(AccountId account, EconomyCurrency currency) {
    return store.transaction(tx -> Settlement.posted(tx::query, account.value(), currency));
  }

  private WorldId world() {
    return store.snapshot().worldId();
  }
}
