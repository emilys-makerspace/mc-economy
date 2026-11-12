package ca.emilybelnavis.mcmods.economy.loan;

import static ca.emilybelnavis.mcmods.economy.debt.DebtRecords.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.debt.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.overdraft.Overdrafts;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebtLifecycleTest {
  @TempDir Path path;

  private DeadlineDispatcher scheduler(LoanTestFixture f) {
    var activation = new Journal.Kind("fixture:debt", "fixture:activation");
    f.submit(
        activation,
        new JournalEngine.Registration(
            1,
            (i, r) ->
                new JournalEngine.Commit(
                    List.of(
                        new Journal.Record(DeadlineDispatcher.ACTIVATION, 1, "1|" + f.clock.get())),
                    new Journal.Result(1, "FIXTURE", ""),
                    tx -> {})),
        f.intent(activation, new Journal.CanonicalRequest(1, Map.of())));
    var providers = new ArrayList<>(DebtRuntime.providers());
    providers.add(LoanRuntime.provider());
    var wall =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId z) {
            return this;
          }

          public Instant instant() {
            return Instant.ofEpochMilli(f.clock.get());
          }
        };
    var dispatcher = new DeadlineDispatcher(f.store, wall, providers, 1);
    dispatcher.prepare();
    return dispatcher;
  }

  private void catchup(LoanTestFixture f, DeadlineDispatcher scheduler, long at) {
    f.clock.set(at);
    int turns = 0;
    while (!scheduler.progress().ready() && turns++ < 1024) {
      long before = f.store.lastCommittedSequence();
      scheduler.advance();
      assertTrue(f.store.lastCommittedSequence() - before <= 1);
    }
    assertTrue(
        scheduler.progress().ready(), "Debt catch-up must finish within the fixture turn budget");
  }

  private Journal.Outcome payLoan(LoanTestFixture f, UUID id, long amount) {
    var quote =
        f.store.transaction(
            tx -> Loans.quote(tx::query, f.owner, id, f.account, amount, false, f.clock.get()));
    return f.submit(
        Loans.REPAY,
        Loans.repayment(f.owner, quote, f.clock::get),
        f.intent(Loans.REPAY, quote.request().request()));
  }

  @Test
  void shortPaymentReportsMissedAndLaterCurePreservesOriginalSnapshotAndDue() throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var loan = f.originate();
      var scheduler = scheduler(f);
      long due = loan.nextMonth();
      catchup(f, scheduler, due + DAY);
      var original = f.get(loan.id()).servicing().requirements().getFirst();
      assertEquals("LOAN_REPAID", payLoan(f, loan.id(), 1).result().code());
      catchup(f, scheduler, due + 2 * DAY);
      var reports =
          f.store.transaction(
              tx ->
                  DebtLifecycle.reports(
                      tx::query, f.owner, DebtLifecycle.ReportCursor.start(), 16));
      assertEquals(1, reports.entries().size());
      var missed = reports.entries().getFirst();
      assertFalse(missed.metAtSnapshot());
      assertEquals(original.originalMinor() - 1, missed.unmetMinor());
      assertEquals(due, missed.dueAt());
      f.clock.incrementAndGet();
      assertEquals("LOAN_REPAID", payLoan(f, loan.id(), original.unmetMinor() - 1).result().code());
      var corrected =
          f.store
              .transaction(
                  tx ->
                      DebtLifecycle.reports(
                          tx::query, f.owner, DebtLifecycle.ReportCursor.start(), 16))
              .entries();
      assertEquals(2, corrected.size());
      assertFalse(corrected.getLast().metAtSnapshot());
      assertTrue(corrected.getLast().cured());
      assertEquals(missed.reportAt(), corrected.getLast().reportAt());
      assertEquals(0, corrected.getLast().unmetMinor());
      assertEquals(due, corrected.getLast().dueAt());
      catchup(f, scheduler, loan.anchor() + 4 * Loans.MONTH);
      assertTrue(
          f.store
              .transaction(tx -> DebtLifecycle.facilityCase(tx::query, Kind.LOAN, loan.id()))
              .isEmpty());
    }
  }

  @Test
  void defaultSalePartialAcknowledgementAndExpiryKeepIndependentClocksAndLiability()
      throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var loan = f.originate();
      var scheduler = scheduler(f);
      long at = loan.nextMonth() + DEFAULT_DELAY;
      catchup(f, scheduler, at);
      var c =
          f.store.transaction(
              tx -> DebtLifecycle.facilityCase(tx::query, Kind.LOAN, loan.id()).orElseThrow());
      assertEquals(Sale.SOLD, c.sale());
      assertEquals(InstitutionCatalog.WAYSTONE, c.creditor());
      assertEquals(at, c.defaultAt());
      assertEquals(Loans.Status.DEFAULTED, f.get(loan.id()).status());
      long frozen = f.get(loan.id()).owedMinor();
      assertEquals(0, f.amount(loan.lender(), FinancialAccounts.Role.RECEIVABLE));
      catchup(f, scheduler, at + DAY);
      var quote =
          f.store.transaction(
              tx -> DebtPayments.quote(tx::query, f.owner, c.id(), f.account, 100, f.clock.get()));
      var handler = DebtPayments.registration(f.owner, quote, f.clock::get);
      var intent = f.intent(DebtPayments.PAY, quote.request());
      var payment = f.submit(DebtPayments.PAY, handler, intent);
      assertEquals("COLLECTION_REPAID", payment.result().code());
      assertEquals(payment, f.submit(DebtPayments.PAY, handler, intent));
      var partial =
          f.store.transaction(tx -> DebtLifecycle.findCase(tx::query, c.id()).orElseThrow());
      assertEquals(at, partial.defaultAt());
      assertEquals(at + DAY + COLLECTION_WINDOW, partial.collectionExpires());
      assertEquals(c.reportExpires(), partial.reportExpires());
      assertEquals(frozen - 100, partial.debtMinor());
      long beforeRead = f.store.lastCommittedSequence();
      assertTrue(DebtLifecycle.demands(partial, f.clock.get()));
      assertTrue(DebtLifecycle.reportVisible(partial, f.clock.get()));
      assertEquals(beforeRead, f.store.lastCommittedSequence());
      catchup(f, scheduler, partial.collectionExpires());
      var expired =
          f.store.transaction(tx -> DebtLifecycle.findCase(tx::query, c.id()).orElseThrow());
      assertEquals(Collectability.UNCOLLECTABLE, expired.collectability());
      assertEquals(
          "UNCOLLECTABLE",
          f.store
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT state FROM debt_facility_states WHERE kind='LOAN' AND facility_id=?",
                          List.of(loan.id().toString()),
                          1))
              .getFirst()
              .get("state"));
      assertFalse(DebtLifecycle.demands(expired, f.clock.get()));
      assertEquals(partial.debtMinor(), f.get(loan.id()).owedMinor());
      assertEquals(0, f.get(loan.id()).servicing().requirements().size());
      var borrower =
          FinancialAccounts.id(
              c.world(), Loans.borrower(c.owner()), FinancialAccounts.Role.LIABILITY, c.currency());
      long liability =
          f.store.transaction(tx -> Settlement.posted(tx::query, borrower, c.currency()));
      assertEquals(-partial.debtMinor(), liability);
      var allowance =
          FinancialAccounts.id(
              c.world(), c.creditor(), FinancialAccounts.Role.ALLOWANCE, c.currency());
      long writtenOff =
          f.store.transaction(tx -> Settlement.posted(tx::query, allowance, c.currency()));
      assertEquals(-partial.debtMinor(), writtenOff);
      assertTrue(DebtLifecycle.reportVisible(expired, f.clock.get()));
      assertFalse(DebtLifecycle.reportVisible(expired, c.reportExpires()));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              f.store.transaction(
                  tx ->
                      DebtPayments.quote(tx::query, f.owner, c.id(), f.account, 1, f.clock.get())));
    }
  }

  @Test
  void unfundedSaleDefersWithoutPostponingDefaultAndRepaymentStillPaysOriginalCreditor()
      throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var loan = f.originate();
      var cash =
          FinancialAccounts.id(
              f.owner.world(),
              InstitutionCatalog.WAYSTONE,
              FinancialAccounts.Role.CASH,
              EconomyCurrency.CAD);
      f.store.transaction(
          tx -> {
            ca.emilybelnavis.mcmods.economy.storage.LedgerProjection.write(
                tx, cash, EconomyCurrency.CAD, 4000000000L);
            return null;
          });
      var scheduler = scheduler(f);
      long at = loan.nextMonth() + DEFAULT_DELAY;
      catchup(f, scheduler, at);
      var c =
          f.store.transaction(
              tx -> DebtLifecycle.facilityCase(tx::query, Kind.LOAN, loan.id()).orElseThrow());
      assertEquals(Sale.PENDING, c.sale());
      assertEquals(loan.lender(), c.creditor());
      assertEquals(at, c.defaultAt());
      var quote =
          f.store.transaction(
              tx -> DebtPayments.quote(tx::query, f.owner, c.id(), f.account, 100, at));
      assertEquals(
          "COLLECTION_REPAID",
          f.submit(
                  DebtPayments.PAY,
                  DebtPayments.registration(f.owner, quote, () -> at),
                  f.intent(DebtPayments.PAY, quote.request()))
              .result()
              .code());
      catchup(f, scheduler, at + DAY);
      assertTrue(scheduler.progress().ready());
      assertEquals(
          Sale.PENDING,
          f.store
              .transaction(tx -> DebtLifecycle.findCase(tx::query, c.id()).orElseThrow())
              .sale());
    }
  }

  @Test
  void scheduledPaymentPrecedesReportAndScoreProbeAtTheSameInstant() throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var loan = f.originate();
      long reportAt = loan.nextMonth() + 2 * DAY;
      var activation = new Journal.Kind("fixture:debt", "fixture:activation");
      f.submit(
          activation,
          new JournalEngine.Registration(
              1,
              (i, r) ->
                  new JournalEngine.Commit(
                      List.of(
                          new Journal.Record(
                              DeadlineDispatcher.ACTIVATION, 1, "1|" + loan.anchor())),
                      new Journal.Result(1, "FIXTURE", ""),
                      tx -> {})),
          f.intent(activation, new Journal.CanonicalRequest(1, Map.of())));
      var providers = new ArrayList<>(DebtRuntime.providers());
      providers.add(LoanRuntime.provider());
      for (int stage : new int[] {3, 6})
        providers.add(
            new DeadlineDispatcher.Provider() {
              public String id() {
                return "fixture:debt-stage-" + stage;
              }

              public Map<Integer, String> versions() {
                return Map.of(1, "fixture-v1");
              }

              public DeadlineDispatcher.Event next(
                  EconomicClock.Queries r, long origin, DeadlineDispatcher.Event completed) {
                return completed == null
                    ? new DeadlineDispatcher.Event(
                        DebtLifecycle.stable(id()), id(), reportAt, stage, 1)
                    : null;
              }

              public List<String> targets(
                  DeadlineDispatcher.Event e, String after, int limit, EconomicClock.Queries r) {
                return after.isEmpty() ? List.of(loan.id().toString()) : List.of();
              }

              public EconomicClock.Output prepare(
                  DeadlineDispatcher.Event e, String target, EconomicClock.Queries queries) {
                JournalEngine.ReadView reads = queries::query;
                if (stage == 6) {
                  var page =
                      DebtLifecycle.reports(reads, f.owner, DebtLifecycle.ReportCursor.start(), 16);
                  assertEquals(1, page.entries().size());
                  assertTrue(page.entries().getFirst().metAtSnapshot());
                  return new EconomicClock.Output(
                      target,
                      tx -> {},
                      List.of(
                          new Journal.Record(
                              DebtLifecycle.ATTEMPT,
                              1,
                              loan.id() + "|" + reportAt + "|SCORE_ORDER_PROBE")));
                }
                var current = Loans.find(reads, loan.id()).orElseThrow();
                long amount = current.servicing().requiredMinor();
                var quote =
                    Loans.quote(reads, f.owner, loan.id(), f.account, amount, false, reportAt);
                try {
                  var decision =
                      Loans.planRepayment(
                          reads,
                          current,
                          quote,
                          reportAt,
                          List.of(
                              new PostingBatch.Leg(
                                  f.account.value(),
                                  PostingBatch.Role.ACCOUNT,
                                  EconomyCurrency.CAD,
                                  new Money(EconomyCurrency.CAD, -amount))),
                          List.of());
                  var commit = (JournalEngine.Commit) decision;
                  return new EconomicClock.Output(target, commit.projections(), commit.records());
                } catch (Exception failure) {
                  throw new IllegalStateException(failure);
                }
              }
            });
      var dispatcher =
          new DeadlineDispatcher(
              f.store, Clock.fixed(Instant.ofEpochMilli(reportAt), ZoneOffset.UTC), providers, 1);
      dispatcher.prepare();
      catchup(f, dispatcher, reportAt);
      var reports =
          f.store.transaction(
              tx ->
                  DebtLifecycle.reports(
                      tx::query, f.owner, DebtLifecycle.ReportCursor.start(), 16));
      assertTrue(reports.entries().getFirst().metAtSnapshot());
    }
  }

  @Test
  void cardDefaultClosesSpendingWhileCollectionPaymentSettlesFrozenDebt() throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var proof =
          ca.emilybelnavis.mcmods.economy.institution.BranchFixtures.proof(
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
      var request =
          new ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.IssueRequest(
              proof,
              UUID.randomUUID(),
              InstitutionCatalog.Network.ORBIT,
              EconomyCurrency.CAD,
              250000,
              1,
              0,
              f.clock.get());
      var handler =
          ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.issuance(
              f.owner,
              request,
              BankProviders.NO_TIER2,
              (r, o, n) ->
                  new ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.Inputs(
                      f.fx(r, n), true, false, 0),
              f.clock::get);
      assertEquals(
          "CREDIT_CARD_ISSUED",
          f.submit(
                  ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.ISSUE,
                  handler,
                  f.intent(
                      ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.ISSUE,
                      request.request()))
              .result()
              .code());
      var token =
          f.store.transaction(
              tx ->
                  ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.tokenForFacility(
                          tx::query, f.owner.world(), request.facility())
                      .orElseThrow());
      var draw =
          new ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DrawRequest(
              f.owner.world(),
              f.actor,
              request.facility(),
              token.id(),
              token.network(),
              ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DrawKind.PURCHASE,
              f.account,
              100000,
              1,
              1,
              1,
              1,
              UUID.randomUUID(),
              f.clock.get() + 30000);
      assertEquals(
          "CREDIT_DRAWN",
          f.submit(
                  ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DRAW,
                  ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.draw(
                      f.owner,
                      draw,
                      (r, o, n) ->
                          new ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.Inputs(
                              f.fx(r, n), true, false, 0),
                      (i, r, t, k, a) -> true,
                      f.clock::get),
                  f.intent(
                      ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DRAW, draw.request()))
              .result()
              .code());
      var activation = new Journal.Kind("fixture:debt", "fixture:activation");
      f.submit(
          activation,
          new JournalEngine.Registration(
              1,
              (i, r) ->
                  new JournalEngine.Commit(
                      List.of(
                          new Journal.Record(
                              DeadlineDispatcher.ACTIVATION, 1, "1|" + f.clock.get())),
                      new Journal.Result(1, "FIXTURE", ""),
                      tx -> {})),
          f.intent(activation, new Journal.CanonicalRequest(1, Map.of())));
      var providers = new ArrayList<>(DebtRuntime.providers());
      providers.addAll(ca.emilybelnavis.mcmods.economy.credit.CreditRuntime.providers());
      long defaultAt = f.clock.get() + 42 * DAY + DEFAULT_DELAY;
      var scheduler =
          new DeadlineDispatcher(
              f.store, Clock.fixed(Instant.ofEpochMilli(defaultAt), ZoneOffset.UTC), providers, 1);
      scheduler.prepare();
      catchup(f, scheduler, defaultAt);
      var c =
          f.store.transaction(
              tx ->
                  DebtLifecycle.facilityCase(tx::query, Kind.CARD, request.facility())
                      .orElseThrow());
      var saved =
          f.store.transaction(
              tx ->
                  ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.find(
                          tx::query, request.facility())
                      .orElseThrow());
      assertEquals(
          ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.Access.CLOSED, saved.access());
      assertTrue(
          f.store
              .transaction(
                  tx ->
                      ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.usableToken(
                          tx::query, f.owner.world(), f.actor, token.id(), defaultAt))
              .isEmpty());
      var quote =
          f.store.transaction(
              tx ->
                  DebtPayments.quote(
                      tx::query, f.owner, c.id(), f.account, c.debtMinor(), defaultAt + DAY));
      assertEquals(
          "COLLECTION_SETTLED",
          f.submit(
                  DebtPayments.PAY,
                  DebtPayments.registration(f.owner, quote, () -> defaultAt + DAY),
                  f.intent(DebtPayments.PAY, quote.request()))
              .result()
              .code());
      var paid = f.store.transaction(tx -> DebtLifecycle.findCase(tx::query, c.id()).orElseThrow());
      assertEquals(Payment.SETTLED, paid.payment());
      var changes =
          f.store.transaction(
              tx ->
                  DebtLifecycle.reports(
                      tx::query, f.owner, DebtLifecycle.ReportCursor.start(), 16));
      assertTrue(
          changes.entries().stream()
              .filter(DebtRecords.Report::cured)
              .allMatch(r -> r.effectiveAt() == defaultAt + DAY));
      assertEquals(c.defaultAt(), paid.defaultAt());
      assertEquals(c.reportExpires(), paid.reportExpires());
      assertTrue(DebtLifecycle.reportVisible(paid, defaultAt + DAY));
    }
  }

  @Test
  void foreignCollectionRepaymentChargesSeparateFeeConsumesOnceAndRejectsUnderfundedPrincipal()
      throws Exception {
    try (var f = new LoanTestFixture(path)) {
      var loan = f.originate();
      var scheduler = scheduler(f);
      long now = loan.nextMonth() + DEFAULT_DELAY;
      catchup(f, scheduler, now);
      var debt =
          f.store.transaction(
              tx -> DebtLifecycle.facilityCase(tx::query, Kind.LOAN, loan.id()).orElseThrow());
      var account =
          new ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId(UUID.randomUUID());
      var bank = new InstitutionCatalog.InstitutionId("economy:bank/crosswind");
      f.store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','USD','OPEN',1,0)",
                List.of(account.value().toString(), f.actor.value().toString()));
            tx.execute(
                "INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",
                List.of(account.value().toString(), bank.key()));
            ca.emilybelnavis.mcmods.economy.storage.LedgerProjection.write(
                tx, account.value(), EconomyCurrency.USD, 1_000_000);
            return null;
          });
      var fxClock =
          new EconomicClock(
              f.store,
              Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
              List.of(ca.emilybelnavis.mcmods.economy.fx.FxService.definition()),
              Map.of(
                  ca.emilybelnavis.mcmods.economy.fx.FxService.SYSTEM,
                  ca.emilybelnavis.mcmods.economy.fx.FxService.deferred()));
      fxClock.prepare();
      for (int turn = 0; turn < 4; turn++) {
        var step = fxClock.prepareStep();
        if (step.work() == null) break;
        fxClock.commit(step.work(), step.work().calculate());
      }
      var book = new ca.emilybelnavis.mcmods.economy.fx.FxQuotes(f.owner.world());
      var pricing =
          new ca.emilybelnavis.mcmods.economy.pricing.ServicePricing(
              new ca.emilybelnavis.mcmods.economy.pricing.WealthValuation(
                  List.of(
                      new LoanLiabilities(),
                      new ca.emilybelnavis.mcmods.economy.credit.CreditLiabilities())));
      java.util.function.Supplier<DebtPayments.FxQuote> capture =
          () ->
              f.store.transaction(
                  tx -> {
                    var payment =
                        DebtPayments.quote(
                            tx::query, f.owner, debt.id(), account, debt.debtMinor(), now);
                    var fx =
                        book.issue(
                            f.actor,
                            new ca.emilybelnavis.mcmods.economy.fx.FxQuotes.Pair(
                                EconomyCurrency.USD, EconomyCurrency.CAD),
                            ca.emilybelnavis.mcmods.economy.fx.FxQuotes.Side.EXACT_CREDIT,
                            new Money(EconomyCurrency.CAD, debt.debtMinor()),
                            ca.emilybelnavis.mcmods.economy.fx.FxService.latest(tx::query),
                            Instant.ofEpochMilli(now),
                            Duration.ofSeconds(60));
                    var fee =
                        pricing.quote(
                            tx::query,
                            f.owner.world(),
                            f.actor,
                            bank,
                            ca.emilybelnavis.mcmods.economy.pricing.ServicePricing.Service
                                .PROTECTED_WITHDRAWAL_FX,
                            fx.debit(),
                            Instant.ofEpochMilli(now),
                            fx.expires());
                    return DebtPayments.capture(
                        tx::query, f.owner, payment, book.price(fx, fee), now);
                  });
      var first = capture.get();
      long funded =
          f.store.transaction(
              tx ->
                  first.fx().debit().minorUnits()
                      - Overdrafts.tolerance(
                          tx::query, EconomyCurrency.USD, Overdrafts.now(tx::query))
                      - 1);
      f.store.transaction(
          tx -> {
            ca.emilybelnavis.mcmods.economy.storage.LedgerProjection.write(
                tx, account.value(), EconomyCurrency.USD, funded);
            return null;
          });
      var constrained = capture.get();
      FxSettlement.QuoteReader fresh =
          (id, actor, world, reads) -> {
            var fx =
                book.requireFresh(
                    id,
                    actor,
                    world,
                    ca.emilybelnavis.mcmods.economy.fx.FxService.latest(reads),
                    1,
                    Instant.ofEpochMilli(now));
            pricing.requireFresh(fx.pricing(), reads, world, actor, Instant.ofEpochMilli(now));
            return fx;
          };
      var failed =
          f.submit(
              DebtPayments.PAY,
              DebtPayments.registrationFx(f.owner, constrained, fresh, () -> now),
              f.intent(DebtPayments.PAY, constrained.canonical()));
      assertNotEquals(Journal.Status.COMMITTED, failed.status());
      assertEquals(
          debt,
          f.store.transaction(tx -> DebtLifecycle.findCase(tx::query, debt.id()).orElseThrow()));
      assertTrue(
          f.store
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT quote_id FROM ledger_fx_quotes WHERE quote_id=?",
                          List.of(constrained.fx().id().toString()),
                          1))
              .isEmpty());
      f.store.transaction(
          tx -> {
            ca.emilybelnavis.mcmods.economy.storage.LedgerProjection.write(
                tx, account.value(), EconomyCurrency.USD, 1_000_000);
            return null;
          });
      var quote = capture.get();
      var intent = f.intent(DebtPayments.PAY, quote.canonical());
      var registration = DebtPayments.registrationFx(f.owner, quote, fresh, () -> now);
      var result = f.submit(DebtPayments.PAY, registration, intent);
      assertEquals("COLLECTION_SETTLED", result.result().code());
      var receipt =
          f.store.transaction(
              tx ->
                  tx.query(
                          "SELECT record_version,payload FROM journal_records WHERE record_type=?",
                          List.of(DebtPayments.FACT),
                          1)
                      .getFirst());
      assertEquals("2", receipt.get("record_version"));
      var catalog = ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog.core();
      assertDoesNotThrow(() -> catalog.validate(DebtPayments.FACT, 2, receipt.get("payload")));
      var fields = receipt.get("payload").split("\\|", -1);
      assertEquals(quote.referenceResidual(), new java.math.BigDecimal(fields[13]));
      // Scheduled publications vary with the world seed; both residual signs are valid in v2.
      fields[13] = "-0.001";
      var negativeResidual = String.join("|", fields);
      assertDoesNotThrow(() -> catalog.validate(DebtPayments.FACT, 2, negativeResidual));
      assertThrows(
          ca.emilybelnavis.mcmods.economy.storage.StorageException.class,
          () -> catalog.validate(DebtPayments.FACT, 1, negativeResidual));

      assertEquals(result, f.submit(DebtPayments.PAY, registration, intent));
      long balance =
          f.store.transaction(
              tx -> Settlement.posted(tx::query, account.value(), EconomyCurrency.USD));
      assertEquals(
          1_000_000 - quote.fx().debit().minorUnits() - quote.fx().fee().minorUnits(), balance);
      assertEquals(
          1,
          f.store
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT quote_id FROM ledger_fx_quotes WHERE quote_id=?",
                          List.of(quote.fx().id().toString()),
                          1))
              .size());
      var paid =
          f.store.transaction(tx -> DebtLifecycle.findCase(tx::query, debt.id()).orElseThrow());
      assertEquals(Payment.SETTLED, paid.payment());
      assertEquals(debt.reportExpires(), paid.reportExpires());
    }
  }

  @Test
  void insufficientNativeFundsRejectWithoutAcknowledgementOrStorageFailure() throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      var loan = fixture.originate();
      var dispatcher = scheduler(fixture);
      catchup(fixture, dispatcher, loan.nextMonth() + DEFAULT_DELAY);
      var debt =
          fixture.store.transaction(
              tx -> DebtLifecycle.facilityCase(tx::query, Kind.LOAN, loan.id()).orElseThrow());
      fixture.store.transaction(
          tx -> {
            ca.emilybelnavis.mcmods.economy.storage.LedgerProjection.write(
                tx, fixture.account.value(), EconomyCurrency.CAD, 0);
            return null;
          });
      var quote =
          fixture.store.transaction(
              tx ->
                  DebtPayments.quote(
                      tx::query,
                      fixture.owner,
                      debt.id(),
                      fixture.account,
                      501,
                      fixture.clock.get()));
      long frontier = fixture.store.lastCommittedSequence();
      var result =
          fixture.submit(
              DebtPayments.PAY,
              DebtPayments.registration(fixture.owner, quote, fixture.clock::get),
              fixture.intent(DebtPayments.PAY, quote.request()));
      assertEquals("INSUFFICIENT_FUNDS", result.result().code());
      assertNotEquals(Journal.Status.COMMITTED, result.status());
      assertEquals(frontier, fixture.store.lastCommittedSequence());
      assertEquals(
          debt,
          fixture.store.transaction(
              tx -> DebtLifecycle.findCase(tx::query, debt.id()).orElseThrow()));
      assertEquals(loan.id(), fixture.get(loan.id()).id());
    }
  }
}
