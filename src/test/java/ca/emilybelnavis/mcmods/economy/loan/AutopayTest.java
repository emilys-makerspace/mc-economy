package ca.emilybelnavis.mcmods.economy.loan;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.autopay.Autopay;
import ca.emilybelnavis.mcmods.economy.autopay.AutopayRuntime;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.debt.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.WealthValuation;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite and chronological providers exercise opt-in consent and one-time due payments. */
class AutopayTest {
  @TempDir Path path;

  @Test
  void defaultsOffAndOriginalConfigurationReplayPreservesOneConsentRevision() throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var loan = f.originate();
      assertFalse(
          settings(f, DebtRecords.Kind.LOAN, loan.id())
              .map(Autopay.Settings::enabled)
              .orElse(false));
      long originalRevision =
          settings(f, DebtRecords.Kind.LOAN, loan.id()).map(Autopay.Settings::revision).orElse(0L);
      var request =
          configureRequest(
              f, DebtRecords.Kind.LOAN, loan.id(), loan.revision(), true, Autopay.Mode.LOAN_DUE);
      var intent = f.intent(Autopay.CONFIGURE, request.request());
      var handler = Autopay.configuration(f.owner, request, f.clock::get);
      var first = f.submit(Autopay.CONFIGURE, handler, intent);
      assertEquals(Journal.Status.COMMITTED, first.status());
      assertEquals(first, f.submit(Autopay.CONFIGURE, handler, intent));
      var saved = settings(f, DebtRecords.Kind.LOAN, loan.id()).orElseThrow();
      assertTrue(saved.enabled());
      assertEquals(f.account, saved.funding());
      assertEquals(originalRevision + 1, saved.revision());
      var foreign =
          new PartyRef(f.owner.world(), new PartyRef.Player(new PlayerId(UUID.randomUUID())));
      assertNotEquals(
          Journal.Status.COMMITTED,
          f.submit(
                  Autopay.CONFIGURE,
                  Autopay.configuration(foreign, request, f.clock::get),
                  f.intent(Autopay.CONFIGURE, request.request()))
              .status());
    }
  }

  @Test
  void loanDueDebitIsExactAndCannotRepeatAfterRestartOrReport() throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var loan = f.originate();
      enable(f, DebtRecords.Kind.LOAN, loan.id(), Autopay.Mode.LOAN_DUE);
      long before = cash(f),
          installment = loan.servicing().installmentMinor(),
          due = loan.nextMonth();
      var scheduler = scheduler(f);
      catchup(f, scheduler, due);
      assertEquals(before - installment, cash(f));
      assertEquals(0, f.get(loan.id()).servicing().requiredMinor());
      assertTrue(f.get(loan.id()).servicing().principalMinor() < loan.originalPrincipalMinor());
      long paidBalance = cash(f);
      f.reopen();
      scheduler = scheduler(f);
      catchup(f, scheduler, due + 2 * DebtRecords.DAY);
      assertEquals(paidBalance, cash(f));
      var report = reports(f, loan.id());
      assertEquals(1, report.size());
      assertTrue(report.getFirst().metAtSnapshot());
      long frontier = f.store.lastCommittedSequence();
      scheduler.advance();
      assertEquals(frontier, f.store.lastCommittedSequence());
    }
  }

  @Test
  void insufficientFundsAreAllOrNothingAndFinalRetryPrecedesDuePlusTwoReport() throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var loan = f.originate();
      enable(f, DebtRecords.Kind.LOAN, loan.id(), Autopay.Mode.LOAN_DUE);
      setCash(f, 0);
      long due = loan.nextMonth();
      var scheduler = scheduler(f);
      catchup(f, scheduler, due);
      long required = f.get(loan.id()).servicing().requiredMinor();
      assertTrue(required > 0);
      assertEquals(0, cash(f));
      f.reopen();
      scheduler = scheduler(f);
      catchup(f, scheduler, due + DebtRecords.DAY);
      assertEquals(required, f.get(loan.id()).servicing().requiredMinor());
      assertEquals(0, cash(f));
      f.clock.set(due + 2 * DebtRecords.DAY - 1);
      setCash(f, required);
      catchup(f, scheduler, due + 2 * DebtRecords.DAY);
      assertEquals(0, cash(f));
      assertEquals(0, f.get(loan.id()).servicing().requiredMinor());
      assertTrue(reports(f, loan.id()).getFirst().metAtSnapshot());
      catchup(f, scheduler, due + 3 * DebtRecords.DAY);
      assertEquals(0, cash(f));
    }
  }

  @Test
  void optOutAndClosedNamedSourceNeverSubstituteAnotherAccount() throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var disabled = f.originate();
      var namedSource = new AccountId(UUID.randomUUID());
      f.store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CHF','OPEN',1,0)",
                List.of(namedSource.value().toString(), f.actor.value().toString()));
            return null;
          });
      var bank = new InstitutionCatalog.InstitutionId("economy:bank/lakeward_cantonal");
      var proof =
          BranchFixtures.proof(
              new BranchAdmission.Context(
                  f.owner.world(),
                  UUID.randomUUID(),
                  f.actor,
                  BranchAdmission.Action.LOAN_APPLY,
                  bank,
                  null,
                  null,
                  0,
                  0,
                  0));
      long profileRevision =
          f.store.transaction(
              tx -> CreditProfiles.find(tx::query, f.owner).orElseThrow().revision());
      var publication =
          f.store
              .transaction(tx -> ca.emilybelnavis.mcmods.economy.fx.FxService.latest(tx::query))
              .updateId();
      var application =
          new Loans.Application(
              proof,
              UUID.randomUUID(),
              UUID.randomUUID(),
              CreditUnderwriting.Product.BANK_LOAN,
              EconomyCurrency.CHF,
              100_000,
              1,
              namedSource,
              1,
              profileRevision,
              0,
              1,
              publication,
              LoanTestFixture.NOW.toEpochMilli(),
              Loans.RateMode.FIXED,
              false);
      assertEquals(
          "LOAN_ORIGINATED",
          f.apply(application, f.intent(Loans.APPLY, application.request())).result().code());
      var closedSource = f.get(application.loan());
      enable(f, DebtRecords.Kind.LOAN, disabled.id(), Autopay.Mode.LOAN_DUE);
      var consent =
          new Autopay.Configure(
              f.owner.world(),
              f.actor,
              DebtRecords.Kind.LOAN,
              closedSource.id(),
              0,
              closedSource.revision(),
              true,
              namedSource,
              1,
              Autopay.Mode.LOAN_DUE);
      assertEquals(
          Journal.Status.COMMITTED,
          f.submit(
                  Autopay.CONFIGURE,
                  Autopay.configuration(f.owner, consent, f.clock::get),
                  f.intent(Autopay.CONFIGURE, consent.request()))
              .status());
      setCash(f, 0);
      setCash(f, namedSource, EconomyCurrency.CHF, 0);
      long due = disabled.nextMonth();
      var scheduler = scheduler(f);
      catchup(f, scheduler, due);
      f.clock.set(due + 1);
      var off =
          configureRequest(
              f,
              DebtRecords.Kind.LOAN,
              disabled.id(),
              f.get(disabled.id()).revision(),
              false,
              Autopay.Mode.LOAN_DUE);
      assertEquals(
          Journal.Status.COMMITTED,
          f.submit(
                  Autopay.CONFIGURE,
                  Autopay.configuration(f.owner, off, f.clock::get),
                  f.intent(Autopay.CONFIGURE, off.request()))
              .status());
      // An empty secondary identity can close. Its history cannot be reopened or replaced by
      // another source.
      f.store.transaction(
          tx -> {
            tx.execute(
                "UPDATE account_catalog SET status='CLOSED',revision=revision+1 WHERE account_id=?",
                List.of(namedSource.value().toString()));
            return null;
          });
      catchup(f, scheduler, due + 2 * DebtRecords.DAY);
      setCash(f, 50_000);
      catchup(f, scheduler, due + 3 * DebtRecords.DAY);
      assertEquals(50_000, cash(f));
      assertFalse(settings(f, DebtRecords.Kind.LOAN, disabled.id()).orElseThrow().enabled());
      assertTrue(f.get(disabled.id()).servicing().requiredMinor() > 0);
      assertTrue(f.get(closedSource.id()).servicing().requiredMinor() > 0);
      assertEquals(
          0L,
          f.store
              .transaction(
                  tx -> Settlement.posted(tx::query, namedSource.value(), EconomyCurrency.CHF))
              .longValue());
    }
  }

  @Test
  void exhaustedRetriesAndDefaultNeverReviveOldPaymentWindows() throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var loan = f.originate();
      enable(f, DebtRecords.Kind.LOAN, loan.id(), Autopay.Mode.LOAN_DUE);
      setCash(f, 0);
      long due = loan.nextMonth();
      var scheduler = scheduler(f);
      catchup(f, scheduler, due + 2 * DebtRecords.DAY);
      f.clock.set(due + 3 * DebtRecords.DAY);
      setCash(f, 50_000);
      catchup(f, scheduler, due + 3 * DebtRecords.DAY);
      assertEquals(50_000, cash(f));
      assertTrue(f.get(loan.id()).servicing().requiredMinor() > 0);
      setCash(f, 0);
      long defaultAt = due + DebtRecords.DEFAULT_DELAY;
      catchup(f, scheduler, defaultAt);
      assertEquals(Loans.Status.DEFAULTED, f.get(loan.id()).status());
      long frozen = f.get(loan.id()).owedMinor();
      setCash(f, 100_000);
      f.reopen();
      scheduler = scheduler(f);
      catchup(f, scheduler, defaultAt + DebtRecords.DAY);
      assertEquals(100_000, cash(f));
      assertEquals(frozen, f.get(loan.id()).owedMinor());
    }
  }

  @Test
  void cardMinimumPaysOnlyTheRequiredAmountAndStopsRetries() throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var card = issueCard(f);
      draw(f, card.id(), 20_000);
      enable(f, DebtRecords.Kind.CARD, card.id(), Autopay.Mode.MINIMUM);
      var scheduler = scheduler(f);
      long due = card.nextStatement() + 14 * DebtRecords.DAY;
      catchup(f, scheduler, due - 1);
      long required = card(f, card.id()).requiredMinor(), before = cash(f);
      assertTrue(required > 0);
      catchup(f, scheduler, due);
      assertEquals(before - required, cash(f));
      assertEquals(0, card(f, card.id()).requiredMinor());
      assertTrue(card(f, card.id()).owedMinor() > 0);
      catchup(f, scheduler, due + 2 * DebtRecords.DAY);
      assertEquals(before - required, cash(f));
    }
  }

  @Test
  void statementTargetSubtractsManualPaymentsAndIgnoresLaterPurchases() throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var card = issueCard(f);
      draw(f, card.id(), 20_000);
      enable(f, DebtRecords.Kind.CARD, card.id(), Autopay.Mode.STATEMENT);
      var scheduler = scheduler(f);
      long close = card.nextStatement();
      catchup(f, scheduler, close);
      var statement =
          f.store
              .transaction(tx -> CreditFacilities.statements(tx::query, f.owner, card.id(), -1, 1))
              .statements()
              .getFirst()
              .statement();
      f.clock.set(close + 1);
      payCard(f, card.id(), 1_000);
      draw(f, card.id(), 2_000);
      assertEquals(
          0,
          card(f, card.id()).requiredMinor(),
          "Manual amount already satisfies this small minimum");
      setCash(f, 0);
      catchup(f, scheduler, statement.dueAt());
      long target = statement.debtMinor() - 1_000;
      f.clock.set(statement.dueAt() + DebtRecords.DAY - 1);
      setCash(f, target);
      catchup(f, scheduler, statement.dueAt() + DebtRecords.DAY);
      assertEquals(0, cash(f), "The remaining immutable statement amount was paid exactly");
      assertTrue(
          card(f, card.id()).owedMinor() >= 2_000,
          "Later spending stays outside statement consent");
    }
  }

  @Test
  void historicalStatementBootstrapSubtractsPaymentsBeforeOptIn() throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var card = issueCard(f);
      draw(f, card.id(), 20_000);
      var scheduler = scheduler(f);
      long close = card.nextStatement();
      catchup(f, scheduler, close);
      var statement =
          f.store
              .transaction(tx -> CreditFacilities.statements(tx::query, f.owner, card.id(), -1, 1))
              .statements()
              .getFirst()
              .statement();
      f.clock.set(close + 1);
      payCard(f, card.id(), 1_000);
      assertFalse(
          f.store.<Boolean>transaction(tx -> Autopay.ready(tx::query)),
          "This history predates autopay source bootstrap");
      f.reopen();
      enable(f, DebtRecords.Kind.CARD, card.id(), Autopay.Mode.STATEMENT);
      long remainingStatement = statement.debtMinor() - 1_000;
      setCash(f, remainingStatement);
      scheduler = scheduler(f);
      catchup(f, scheduler, statement.dueAt());
      assertEquals(0, cash(f), "Historical payment reduces the opted-in statement target exactly");
      assertTrue(
          card(f, card.id()).owedMinor() > 0,
          "Post-statement earned interest was outside the original statement target");
    }
  }

  @Test
  void fullPayoffRetryContinuesAfterMinimumAndStopsBeforeNewSpending() throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var card = issueCard(f);
      draw(f, card.id(), 20_000);
      enable(f, DebtRecords.Kind.CARD, card.id(), Autopay.Mode.FULL_PAYOFF);
      var scheduler = scheduler(f);
      long due = card.nextStatement() + 14 * DebtRecords.DAY;
      setCash(f, 0);
      catchup(f, scheduler, due);
      long minimum = card(f, card.id()).requiredMinor();
      assertTrue(minimum > 0);
      f.clock.set(due + 1);
      setCash(f, minimum);
      payCard(f, card.id(), minimum);
      assertEquals(0, card(f, card.id()).requiredMinor());
      draw(f, card.id(), 1_000);
      setCash(f, 100_000);
      catchup(f, scheduler, due + DebtRecords.DAY);
      assertEquals(0, card(f, card.id()).owedMinor(), "Full target survives minimum satisfaction");
      f.clock.set(due + DebtRecords.DAY + 1);
      draw(f, card.id(), 500);
      long afterDraw = cash(f);
      catchup(f, scheduler, due + 2 * DebtRecords.DAY);
      assertEquals(afterDraw, cash(f), "Completed target cannot sweep later spending");
      assertTrue(card(f, card.id()).owedMinor() > 0);
    }
  }

  private static Optional<Autopay.Settings> settings(
      LoanTestFixture f, DebtRecords.Kind kind, UUID id) {
    return f.store.transaction(tx -> Autopay.find(tx::query, f.owner, kind, id));
  }

  private static Autopay.Configure configureRequest(
      LoanTestFixture f,
      DebtRecords.Kind kind,
      UUID id,
      long facilityRevision,
      boolean enabled,
      Autopay.Mode mode) {
    for (int turn = 0; !Autopay.bootstrap(f.store); turn++)
      assertTrue(turn < 128, "Bounded autopay bootstrap");
    long revision = settings(f, kind, id).map(Autopay.Settings::revision).orElse(0L);
    long fundingRevision =
        f.store.transaction(
            tx ->
                ca.emilybelnavis.mcmods.economy.account.AccountCatalog.find(
                        tx::query, f.owner, f.account)
                    .orElseThrow()
                    .revision());
    return new Autopay.Configure(
        f.owner.world(),
        f.actor,
        kind,
        id,
        revision,
        facilityRevision,
        enabled,
        enabled ? f.account : null,
        enabled ? fundingRevision : 0,
        mode);
  }

  private static void enable(LoanTestFixture f, DebtRecords.Kind kind, UUID id, Autopay.Mode mode) {
    long revision = kind == DebtRecords.Kind.CARD ? card(f, id).revision() : f.get(id).revision();
    var request = configureRequest(f, kind, id, revision, true, mode);
    assertEquals(
        Journal.Status.COMMITTED,
        f.submit(
                Autopay.CONFIGURE,
                Autopay.configuration(f.owner, request, f.clock::get),
                f.intent(Autopay.CONFIGURE, request.request()))
            .status());
  }

  private static DeadlineDispatcher scheduler(LoanTestFixture f) {
    if (f.store
        .transaction(
            tx ->
                tx.query(
                    "SELECT payload FROM journal_records WHERE record_type=? LIMIT 1",
                    List.of(DeadlineDispatcher.ACTIVATION),
                    1))
        .isEmpty()) DebtAcceptanceTest.activate(f);
    var providers = new ArrayList<DeadlineDispatcher.Provider>(CreditRuntime.providers());
    providers.add(LoanRuntime.provider());
    providers.addAll(DebtRuntime.providers());
    providers.add(AutopayRuntime.provider());
    var wall =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId zone) {
            return this;
          }

          public Instant instant() {
            return Instant.ofEpochMilli(f.clock.get());
          }
        };
    var result = new DeadlineDispatcher(f.store, wall, providers, 1);
    result.prepare();
    return result;
  }

  private static void catchup(LoanTestFixture f, DeadlineDispatcher scheduler, long at) {
    f.clock.set(at);
    int turns = 0;
    while (!scheduler.progress().ready()) {
      assertTrue(++turns < 8192, "Autopay/debt catch-up is bounded");
      long before = f.store.lastCommittedSequence();
      scheduler.advance();
      assertTrue(f.store.lastCommittedSequence() - before <= 1, "One child mutation per turn");
    }
  }

  private static long cash(LoanTestFixture f) {
    return f.store.transaction(
        tx -> Settlement.posted(tx::query, f.account.value(), EconomyCurrency.CAD));
  }

  private static void setCash(LoanTestFixture f, long amount) {
    setCash(f, f.account, EconomyCurrency.CAD, amount);
  }

  private static void setCash(
      LoanTestFixture f, AccountId account, EconomyCurrency currency, long amount) {
    long old = f.store.transaction(tx -> Settlement.posted(tx::query, account.value(), currency));
    long delta = Math.subtractExact(amount, old);
    if (delta == 0) return;
    var kind = new Journal.Kind("fixture:autopay", "fixture:fund");
    var legs =
        List.of(
            new PostingBatch.Leg(
                Settlement.issuanceAccount(currency),
                PostingBatch.Role.ISSUANCE,
                currency,
                new Money(currency, -delta)),
            new PostingBatch.Leg(
                account.value(), PostingBatch.Role.ACCOUNT, currency, new Money(currency, delta)));
    var authority = delta > 0 ? PostingBatch.Authority.ISSUE : PostingBatch.Authority.REDEEM;
    assertEquals(
        Journal.Status.COMMITTED,
        f.submit(
                kind,
                Settlement.registrationMinor((intent, entries, reads) -> authority),
                f.intent(kind, Settlement.minorRequest(legs)))
            .status());
  }

  private static List<DebtRecords.Report> reports(LoanTestFixture f, UUID loan) {
    return f
        .store
        .transaction(
            tx -> DebtLifecycle.reports(tx::query, f.owner, DebtLifecycle.ReportCursor.start(), 16))
        .entries()
        .stream()
        .filter(report -> report.facility().equals(loan))
        .toList();
  }

  private static CreditFacilities.Facility card(LoanTestFixture f, UUID id) {
    return f.store.transaction(tx -> CreditFacilities.find(tx::query, id).orElseThrow());
  }

  private static CreditFacilities.Facility issueCard(LoanTestFixture f) {
    var proof =
        BranchFixtures.proof(
            new BranchAdmission.Context(
                f.owner.world(),
                UUID.randomUUID(),
                f.actor,
                BranchAdmission.Action.CREDIT_ISSUE,
                LoanTestFixture.BANK,
                null,
                null,
                0,
                0,
                0));
    long profileRevision =
        f.store.transaction(tx -> CreditProfiles.find(tx::query, f.owner).orElseThrow().revision());
    var request =
        new CreditFacilities.IssueRequest(
            proof,
            UUID.randomUUID(),
            InstitutionCatalog.Network.ORBIT,
            EconomyCurrency.CAD,
            100_000,
            profileRevision,
            0,
            LoanTestFixture.NOW.toEpochMilli());
    assertEquals(
        "CREDIT_CARD_ISSUED",
        f.submit(
                CreditFacilities.ISSUE,
                CreditFacilities.issuance(
                    f.owner,
                    request,
                    BankProviders.NO_TIER2,
                    (reads, owner, now) ->
                        new CreditFacilities.Inputs(f.fx(reads, now), true, false, 0),
                    f.clock::get),
                f.intent(CreditFacilities.ISSUE, request.request()))
            .result()
            .code());
    return card(f, request.facility());
  }

  private static void draw(LoanTestFixture f, UUID id, long amount) {
    var card = card(f, id);
    var token =
        f.store.transaction(
            tx -> CreditFacilities.tokenForFacility(tx::query, f.owner.world(), id).orElseThrow());
    long profileRevision =
        f.store.transaction(tx -> CreditProfiles.find(tx::query, f.owner).orElseThrow().revision());
    var request =
        new CreditFacilities.DrawRequest(
            f.owner.world(),
            f.actor,
            id,
            token.id(),
            token.network(),
            CreditFacilities.DrawKind.PURCHASE,
            f.account,
            amount,
            card.revision(),
            token.revision(),
            1,
            profileRevision,
            UUID.randomUUID(),
            f.clock.get() + 30_000);
    assertEquals(
        "CREDIT_DRAWN",
        f.submit(
                CreditFacilities.DRAW,
                CreditFacilities.draw(
                    f.owner,
                    request,
                    (reads, owner, now) ->
                        new CreditFacilities.Inputs(f.fx(reads, now), true, false, 0),
                    (intent, reads, found, kind, destination) -> true,
                    f.clock::get),
                f.intent(CreditFacilities.DRAW, request.request()))
            .result()
            .code());
  }

  private static void payCard(LoanTestFixture f, UUID id, long amount) {
    var card = card(f, id);
    long ledger =
        f.store.transaction(tx -> WealthValuation.ledgerRevision(tx::query, f.account.value()));
    var request =
        new CreditFacilities.RepaymentRequest(
            f.owner.world(),
            f.actor,
            id,
            f.account,
            amount,
            card.revision(),
            1,
            ledger,
            UUID.randomUUID(),
            Math.min(f.clock.get() + 30_000, Math.min(card.nextDay(), card.nextStatement())));
    assertEquals(
        "CREDIT_REPAID",
        f.submit(
                CreditFacilities.REPAY,
                CreditFacilities.repayment(f.owner, request, f.clock::get),
                f.intent(CreditFacilities.REPAY, request.request()))
            .result()
            .code());
  }
}
