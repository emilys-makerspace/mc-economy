package ca.emilybelnavis.mcmods.economy.interest;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.math.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DepositYieldTest {
  @TempDir Path path;
  private static final InstitutionCatalog.InstitutionId BANK =
      new InstitutionCatalog.InstitutionId("economy:bank/crownstone");
  private static final BigDecimal APR = new BigDecimal("0.005");
  private final PlayerId owner = new PlayerId(UUID.randomUUID());
  private final AccountId account = new AccountId(UUID.randomUUID());

  private final class Fixture implements AutoCloseable {
    final SqliteStorage store = new SqliteStorage();
    final WorldId world;
    final DepositYield yield;
    final FinancialAccounts.Account cash;

    Fixture(long principal, long bankCash) throws Exception {
      this(principal, bankCash, true);
    }

    Fixture(long principal, long bankCash, boolean primary) throws Exception {
      store.initialize(path.toRealPath());
      world = store.snapshot().worldId();
      yield = new DepositYield(world, (r, o, b, t) -> APR);
      new DeadlineDispatcher(store, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), List.of(), 16)
          .prepare();
      cash = FinancialAccounts.account(world, BANK, FinancialAccounts.Role.CASH, CAD);
      store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO institution_parties VALUES(?,?,?,?,?,'HYBRID',1)",
                List.of(
                    world.value().toString(),
                    BANK.key(),
                    new InstitutionCatalog.SystemActor(world, BANK).actorId().value().toString(),
                    "Crownstone",
                    "CAD"));
            FinancialAccounts.register(tx, cash);
            LedgerProjection.write(tx, cash.id(), CAD, bankCash);
            insert(tx, account, owner, principal, primary);
            return null;
          });
      var intent =
          intent(DepositYield.ACTIVATE, new Journal.CanonicalRequest(1, Map.of("effective", "0")));
      assertEquals(
          Journal.Status.COMMITTED,
          new JournalEngine(
                  store,
                  (a, k) -> true,
                  Map.of(DepositYield.ACTIVATE, yield.activation(Instant.EPOCH)))
              .submit(intent)
              .status());
    }

    void insert(FoundationStore.Transaction tx, AccountId id, PlayerId player, long principal) {
      insert(tx, id, player, principal, true);
    }

    void insert(
        FoundationStore.Transaction tx,
        AccountId id,
        PlayerId player,
        long principal,
        boolean primary) {
      tx.execute(
          "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,?)",
          List.of(id.value().toString(), player.value().toString(), primary ? "1" : "0"));
      tx.execute(
          "INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",
          List.of(id.value().toString(), BANK.key()));
      LedgerProjection.write(tx, id.value(), CAD, principal);
    }

    DepositYield.Claim claim(AccountId id) {
      return store.transaction(tx -> DepositYield.find(tx::query, id, BANK, CAD).orElseThrow());
    }

    long balance(AccountId id) {
      return store.transaction(tx -> Settlement.posted(tx::query, id.value(), CAD));
    }

    long ownCash() {
      return store.transaction(tx -> Settlement.posted(tx::query, cash.id(), CAD));
    }

    void day(int day, AccountId id) throws Exception {
      var provider = yield.provider();
      var event =
          new DeadlineDispatcher.Event(
              UUID.randomUUID(), provider.id(), day * DepositYield.DAY, 1, 1);
      var output =
          store.transaction(
              tx -> provider.prepare(event, DepositYield.target(id, BANK, CAD), tx::query));
      commit(output);
    }

    Journal.Outcome commit(EconomicClock.Output output) {
      var kind = new Journal.Kind("fixture:yield", "fixture:effect");
      return new JournalEngine(
              store,
              (a, k) -> true,
              Map.of(
                  kind,
                  new JournalEngine.Registration(
                      1,
                      (i, r) ->
                          new JournalEngine.Commit(
                              output.records(),
                              new Journal.Result(1, "FIXTURE", ""),
                              output.effects()))))
          .submit(intent(kind, new Journal.CanonicalRequest(1, Map.of())));
    }

    Journal.Outcome payout(int day) {
      var provider = yield.payoutProvider();
      var event =
          new DeadlineDispatcher.Event(
              UUID.randomUUID(), provider.id(), day * DepositYield.DAY, 1, 1);
      var targets = store.transaction(tx -> provider.targets(event, "", 16, tx::query));
      assertFalse(targets.isEmpty());
      var output = store.transaction(tx -> provider.prepare(event, targets.getFirst(), tx::query));
      return commit(output);
    }

    void move(AccountId source, AccountId target, long amount, long time) {
      var legs =
          List.of(
              new PostingBatch.Leg(
                  source.value(), PostingBatch.Role.ACCOUNT, CAD, new Money(CAD, -amount)),
              new PostingBatch.Leg(
                  target.value(), PostingBatch.Role.ACCOUNT, CAD, new Money(CAD, amount)));
      var kind = new Journal.Kind("fixture:yield", "fixture:transfer");
      var handler =
          new JournalEngine.Registration(
              1,
              (i, r) -> {
                var decision =
                    Settlement.registrationMinor(
                            (intent, l, reads) -> PostingBatch.Authority.TRANSFER)
                        .handler()
                        .prepare(i, r);
                assertInstanceOf(JournalEngine.Commit.class, decision);
                return yield.observe(i, r, (JournalEngine.Commit) decision, time);
              });
      assertEquals(
          Journal.Status.COMMITTED,
          new JournalEngine(store, (a, k) -> true, Map.of(kind, handler))
              .submit(intent(kind, Settlement.minorRequest(legs)))
              .status());
    }

    @Override
    public void close() throws Exception {
      store.close();
    }
  }

  private Journal.Intent intent(Journal.Kind kind, Journal.CanonicalRequest request) {
    return new Journal.Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        owner,
        kind,
        request,
        null,
        null);
  }

  @Test
  void dailyAccrualHasExactTimeWeightAndPriorCloseCompounding() {
    var basis = new BigDecimal("100000");
    var half =
        DepositYield.dailyInterest(
            basis.multiply(BigDecimal.valueOf(DepositYield.DAY / 2)), BigDecimal.ZERO, APR);
    var full =
        DepositYield.dailyInterest(
            basis.multiply(BigDecimal.valueOf(DepositYield.DAY)), BigDecimal.ZERO, APR);
    assertTrue(
        full.subtract(half.multiply(BigDecimal.valueOf(2)))
                .abs()
                .compareTo(new BigDecimal("0.000000000000000001"))
            <= 0);
    assertTrue(
        DepositYield.dailyInterest(basis.multiply(BigDecimal.valueOf(DepositYield.DAY)), full, APR)
                .compareTo(full)
            > 0);
  }

  @Test
  void transferringAtHalfDayCannotMultiplyEarningDuration() throws Exception {
    try (var f = new Fixture(10_000_000, 100_000_000)) {
      var recipient = new AccountId(UUID.randomUUID());
      var other = new PlayerId(UUID.randomUUID());
      f.store.transaction(
          tx -> {
            f.insert(tx, recipient, other, 0);
            return null;
          });
      f.move(account, recipient, 10_000_000, DepositYield.DAY / 2);
      f.day(1, account);
      f.day(1, recipient);
      var sum = f.claim(account).accruedMajor().add(f.claim(recipient).accruedMajor());
      var expected =
          DepositYield.dailyInterest(
              new BigDecimal("100000").multiply(BigDecimal.valueOf(DepositYield.DAY)),
              BigDecimal.ZERO,
              APR);
      assertTrue(
          sum.subtract(expected).abs().compareTo(new BigDecimal("0.000000000000000001")) <= 0);
      assertEquals(0, f.balance(account));
      assertEquals(10_000_000, f.balance(recipient));
    }
  }

  @Test
  void payoutUsesOwnCashAndRetainsExpenseIncomeAndResidual() throws Exception {
    try (var f = new Fixture(10_000_000, 100_000_000)) {
      for (int day = 1; day <= 28; day++) f.day(day, account);
      var accrued = f.claim(account);
      long payable = accrued.recognizedMinor();
      assertTrue(payable > 0);
      assertEquals(10_000_000, f.balance(account));
      assertEquals(
          List.of(new Money(CAD, payable)),
          f.store.transaction(tx -> DepositYield.receivables(tx::query, f.world, owner)));
      assertEquals(Journal.Status.COMMITTED, f.payout(28).status());
      assertEquals(10_000_000 + payable, f.balance(account));
      assertEquals(100_000_000 - payable, f.ownCash());
      var paid = f.claim(account);
      assertEquals(0, paid.recognizedMinor());
      assertEquals(0, paid.dueMillis());
      assertEquals(f.balance(account), paid.balanceMinor());
      var expense = FinancialAccounts.id(f.world, BANK, FinancialAccounts.Role.EXPENSE, CAD);
      var income =
          FinancialAccounts.id(
              f.world, YieldPosting.depositor(owner), FinancialAccounts.Role.INCOME, CAD);
      assertEquals(
          payable,
          f.store.transaction(tx -> Settlement.posted(tx::query, expense, CAD)).longValue());
      assertEquals(
          -payable,
          f.store.transaction(tx -> Settlement.posted(tx::query, income, CAD)).longValue());
      f.day(29, account);
      assertTrue(f.claim(account).accruedMajor().compareTo(paid.accruedMajor()) > 0);
    }
  }

  @Test
  void emptyBankDefersPaymentWithoutMintingOrLosingTheClaim() throws Exception {
    try (var f = new Fixture(10_000_000, 0)) {
      for (int day = 1; day <= 28; day++) f.day(day, account);
      assertEquals(Journal.Status.COMMITTED, f.payout(28).status());
      var deferred = f.claim(account);
      assertTrue(deferred.distressed());
      assertTrue(deferred.recognizedMinor() > 0);
      assertEquals(10_000_000, f.balance(account));
      assertEquals(0, f.ownCash());
      assertTrue(
          f.store
              .<Boolean>transaction(tx -> DepositYield.distressed(tx::query, BANK))
              .booleanValue());
      long month28 = deferred.dueMinor();
      f.store.transaction(
          tx -> {
            LedgerProjection.write(tx, f.cash.id(), CAD, 1_000_000);
            return null;
          });
      f.day(29, account);
      long whole29 = f.claim(account).recognizedMinor();
      f.payout(29);
      assertFalse(f.claim(account).distressed());
      assertEquals(0, f.claim(account).dueMinor());
      assertEquals(whole29 - month28, f.claim(account).recognizedMinor());
      assertEquals(10_000_000 + month28, f.balance(account));
    }
  }

  @Test
  void capturedAprSurvivesReloadAndNewDailyRateIsSelectedAfterTheBoundary() throws Exception {
    try (var f = new Fixture(10_000_000, 100_000_000)) {
      var tierTwo =
          new DepositYield(
              f.world,
              (r, o, b, t) -> t < DepositYield.DAY ? new BigDecimal("0.01") : BigDecimal.ZERO);
      var provider = tierTwo.provider();
      var first =
          new DeadlineDispatcher.Event(UUID.randomUUID(), provider.id(), DepositYield.DAY, 1, 1);
      f.commit(
          f.store.transaction(
              tx -> provider.prepare(first, DepositYield.target(account, BANK, CAD), tx::query)));
      var firstAmount = f.claim(account).accruedMajor();
      assertEquals(BigDecimal.ZERO, f.claim(account).apr());
      var second =
          new DeadlineDispatcher.Event(
              UUID.randomUUID(), provider.id(), 2 * DepositYield.DAY, 1, 1);
      f.commit(
          f.store.transaction(
              tx -> provider.prepare(second, DepositYield.target(account, BANK, CAD), tx::query)));
      assertEquals(firstAmount, f.claim(account).accruedMajor());
    }
  }

  @Test
  void rebasedClaimRemainsInOriginalCurrencyAndSettlesThroughExplicitFxAfterRestart()
      throws Exception {
    WorldId world;
    BigDecimal earned;
    long nativeCash;
    try (var f = new Fixture(10_000_000, 100_000_000)) {
      world = f.world;
      var fxEngine =
          new JournalEngine(
              f.store,
              (a, k) -> true,
              Map.of(
                  FxService.OPEN, FxService.opening(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))));
      assertEquals(
          Journal.Status.COMMITTED,
          fxEngine
              .submit(
                  intent(
                      FxService.OPEN, new Journal.CanonicalRequest(1, Map.of("currency", "CAD"))))
              .status());
      f.day(1, account);
      var publication = f.store.transaction(tx -> FxService.latest(tx::query));
      long credit =
          BigDecimal.valueOf(10_000_000)
              .multiply(publication.rates().get(CAD))
              .multiply(BigDecimal.valueOf(USD.minorUnitsPerMajor()))
              .divide(
                  publication
                      .rates()
                      .get(USD)
                      .multiply(BigDecimal.valueOf(CAD.minorUnitsPerMajor())),
                  0,
                  RoundingMode.FLOOR)
              .longValueExact();
      var legs =
          List.of(
              new PostingBatch.Leg(
                  account.value(), PostingBatch.Role.ACCOUNT, CAD, new Money(CAD, -10_000_000)),
              new PostingBatch.Leg(
                  Settlement.clearingAccount(CAD),
                  PostingBatch.Role.CLEARING,
                  CAD,
                  new Money(CAD, 10_000_000)),
              new PostingBatch.Leg(
                  Settlement.clearingAccount(USD),
                  PostingBatch.Role.CLEARING,
                  USD,
                  new Money(USD, -credit)),
              new PostingBatch.Leg(
                  account.value(), PostingBatch.Role.ACCOUNT, USD, new Money(USD, credit)));
      var batch = (JournalEngine.Commit) PostingBatch.prepareMinor(legs, PostingBatch.Authority.FX);
      var records = new ArrayList<>(batch.records());
      records.add(new Journal.Record(RebaseSettlement.FACT, 1, "USD|1|" + publication.updateId()));
      records.add(
          new Journal.Record(
              RebaseSettlement.MAPPING, 1, owner.value() + "|" + account.value() + "|1||"));
      var original =
          new JournalEngine.Commit(
              records,
              new Journal.Result(1, "REBASE_FIXTURE", ""),
              tx -> {
                LedgerProjection.write(tx, account.value(), CAD, 0);
                LedgerProjection.write(tx, account.value(), USD, credit);
                tx.execute(
                    "UPDATE account_catalog SET currency='USD',revision=2 WHERE account_id=?",
                    List.of(account.value().toString()));
              });
      var kind = new Journal.Kind("fixture:yield", "fixture:rebase");
      var engine =
          new JournalEngine(
              f.store,
              (a, k) -> true,
              Map.of(
                  kind,
                  new JournalEngine.Registration(
                      1,
                      (i, r) ->
                          f.yield.observe(
                              i, r, original, DepositYield.DAY + DepositYield.DAY / 2))));
      assertEquals(
          Journal.Status.COMMITTED,
          engine.submit(intent(kind, new Journal.CanonicalRequest(1, Map.of()))).status());
      var retained = f.claim(account);
      assertFalse(retained.active());
      assertTrue(retained.recognizedMinor() > 0);
      earned = retained.accruedMajor();
      nativeCash = f.ownCash();
    }
    var reopened = new SqliteStorage();
    reopened.initialize(path.toRealPath());
    try {
      var yield = new DepositYield(world, (r, o, b, t) -> APR);
      var retained =
          reopened.transaction(
              tx -> DepositYield.find(tx::query, account, BANK, CAD).orElseThrow());
      assertEquals(earned, retained.accruedMajor());
      assertFalse(retained.active());
      assertEquals(APR, retained.apr());
      var accrual = yield.provider();
      for (int day = 2; day <= 28; day++) {
        var event =
            new DeadlineDispatcher.Event(
                UUID.randomUUID(), accrual.id(), day * DepositYield.DAY, 1, 1);
        var output =
            reopened.transaction(
                tx -> accrual.prepare(event, DepositYield.target(account, BANK, CAD), tx::query));
        var kind = new Journal.Kind("fixture:yield", "fixture:retained-day");
        new JournalEngine(
                reopened,
                (a, k) -> true,
                Map.of(
                    kind,
                    new JournalEngine.Registration(
                        1,
                        (i, r) ->
                            new JournalEngine.Commit(
                                output.records(),
                                new Journal.Result(1, "DAY", ""),
                                output.effects()))))
            .submit(intent(kind, new Journal.CanonicalRequest(1, Map.of())));
        var currentOutput =
            reopened.transaction(
                tx -> accrual.prepare(event, DepositYield.target(account, BANK, USD), tx::query));
        new JournalEngine(
                reopened,
                (a, k) -> true,
                Map.of(
                    kind,
                    new JournalEngine.Registration(
                        1,
                        (i, r) ->
                            new JournalEngine.Commit(
                                currentOutput.records(),
                                new Journal.Result(1, "DAY", ""),
                                currentOutput.effects()))))
            .submit(intent(kind, new Journal.CanonicalRequest(1, Map.of())));
      }
      long amount =
          reopened.transaction(
              tx -> DepositYield.find(tx::query, account, BANK, CAD).orElseThrow().dueMinor());
      long before = reopened.transaction(tx -> Settlement.posted(tx::query, account.value(), USD));
      var registration =
          yield.settlement(owner, account, BANK, CAD, account, () -> 28 * DepositYield.DAY, false);
      var wrapped =
          new JournalEngine.Registration(
              1,
              (i, r) -> {
                var result = registration.handler().prepare(i, r);
                return result instanceof JournalEngine.Commit c
                    ? yield.observe(i, r, c, 28 * DepositYield.DAY)
                    : result;
              });
      var intent =
          intent(
              DepositYield.SETTLE,
              DepositYield.settlementRequest(account, BANK, CAD, account, false));
      var engine =
          new JournalEngine(reopened, (a, k) -> true, Map.of(DepositYield.SETTLE, wrapped));
      var result = engine.submit(intent);
      assertEquals(Journal.Status.COMMITTED, result.status());
      assertEquals(result, engine.submit(intent));
      assertEquals(
          0,
          reopened
              .transaction(
                  tx ->
                      DepositYield.find(tx::query, account, BANK, CAD)
                          .orElseThrow()
                          .recognizedMinor())
              .longValue());
      assertTrue(
          reopened.transaction(tx -> Settlement.posted(tx::query, account.value(), USD)) > before);
      var cash = FinancialAccounts.id(world, BANK, FinancialAccounts.Role.CASH, CAD);
      assertEquals(
          nativeCash - amount,
          reopened.transaction(tx -> Settlement.posted(tx::query, cash, CAD)).longValue());
    } finally {
      reopened.close();
    }
  }

  @Test
  void decoratedClosureSettlesProrataBeforeCatalogMutationAndCannotMintWhenBankIsEmpty()
      throws Exception {
    try (var f = new Fixture(10_000_000, 0, false)) {
      f.day(1, account);
      var recipient = new AccountId(UUID.randomUUID());
      var other = new PlayerId(UUID.randomUUID());
      f.store.transaction(
          tx -> {
            f.insert(tx, recipient, other, 0);
            return null;
          });
      f.move(account, recipient, 10_000_000, DepositYield.DAY);
      var destination = new AccountId(UUID.randomUUID());
      f.store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','USD','OPEN',1,0)",
                List.of(destination.value().toString(), owner.value().toString()));
            tx.execute(
                "INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",
                List.of(destination.value().toString(), BANK.key()));
            return null;
          });
      var provider = f.yield.provider();
      var destinationDay =
          new DeadlineDispatcher.Event(UUID.randomUUID(), provider.id(), DepositYield.DAY, 1, 1);
      f.commit(
          f.store.transaction(
              tx ->
                  provider.prepare(
                      destinationDay, DepositYield.target(destination, BANK, USD), tx::query)));
      var fx =
          new JournalEngine(
              f.store,
              (a, k) -> true,
              Map.of(
                  FxService.OPEN, FxService.opening(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))));
      fx.submit(intent(FxService.OPEN, new Journal.CanonicalRequest(1, Map.of("currency", "CAD"))));
      var kind = new Journal.Kind("fixture:yield", "fixture:closure");
      var original =
          new JournalEngine.Commit(
              List.of(
                  new Journal.Record(
                      "economy:bank-lifecycle",
                      1,
                      "closed|" + account.value() + "|" + owner.value() + "|CAD|2")),
              new Journal.Result(1, "CLOSED_FIXTURE", ""),
              tx ->
                  tx.execute(
                      "UPDATE account_catalog SET status='CLOSED',revision=2 WHERE account_id=?",
                      List.of(account.value().toString())));
      var engine =
          new JournalEngine(
              f.store,
              (a, k) -> true,
              Map.of(
                  kind,
                  new JournalEngine.Registration(
                      1,
                      (i, r) ->
                          f.yield.observe(
                              i, r, original, DepositYield.DAY + DepositYield.DAY / 2))));
      var request =
          new Journal.CanonicalRequest(1, Map.of("destination", destination.value().toString()));
      var denied = engine.submit(intent(kind, request));
      assertEquals(Journal.Status.NOT_ACCEPTED, denied.status());
      assertEquals("YIELD_FUNDING_UNAVAILABLE", denied.result().code());
      assertEquals(
          "OPEN",
          f.store.transaction(
              tx ->
                  tx.query(
                          "SELECT status FROM account_catalog WHERE account_id=?",
                          List.of(account.value().toString()),
                          1)
                      .getFirst()
                      .get("status")));
      f.store.transaction(
          tx -> {
            LedgerProjection.write(tx, f.cash.id(), CAD, 1_000_000);
            return null;
          });
      var paid = engine.submit(intent(kind, request));
      assertEquals(Journal.Status.COMMITTED, paid.status());
      var claim = f.claim(account);
      assertFalse(claim.active());
      assertEquals(0, claim.accruedMajor().signum());
      assertEquals(0, claim.recognizedMinor());
      assertEquals(
          "CLOSED",
          f.store.transaction(
              tx ->
                  tx.query(
                          "SELECT status FROM account_catalog WHERE account_id=?",
                          List.of(account.value().toString()),
                          1)
                      .getFirst()
                      .get("status")));
      assertTrue(
          f.store.transaction(tx -> Settlement.posted(tx::query, destination.value(), USD)) > 0);
    }
  }

  @Test
  void dueTranchesKeepMonth56SeparateAndDay29YieldCannotJumpOlderPromises() throws Exception {
    try (var f = new Fixture(10_000_000, 0)) {
      var second = new AccountId(UUID.randomUUID());
      var secondOwner = new PlayerId(UUID.randomUUID());
      f.store.transaction(
          tx -> {
            f.insert(tx, second, secondOwner, 10_000_000);
            return null;
          });
      for (int day = 1; day <= 28; day++) {
        f.day(day, account);
        f.day(day, second);
      }
      AccountId first =
          account.value().toString().compareTo(second.value().toString()) < 0 ? account : second;
      AccountId last = first.equals(account) ? second : account;
      long firstMonth = f.claim(first).dueMinor();
      long lastMonth = f.claim(last).dueMinor();
      f.day(29, account);
      f.day(29, second);
      f.store.transaction(
          tx -> {
            LedgerProjection.write(tx, f.cash.id(), CAD, firstMonth);
            return null;
          });
      f.payout(29);
      assertEquals(0, f.claim(first).dueMinor());
      assertTrue(f.claim(first).recognizedMinor() > 0);
      assertEquals(lastMonth, f.claim(last).dueMinor());
      assertEquals(0, f.ownCash());
      f.payout(29);
      assertTrue(f.claim(last).distressed());
      for (int day = 30; day <= 56; day++) {
        f.day(day, account);
        f.day(day, second);
      }
      var rows =
          f.store.transaction(
              tx ->
                  tx.query(
                      "SELECT due_ms,amount_minor FROM deposit_yield_due WHERE account_id=? AND amount_minor>0 ORDER BY due_ms",
                      List.of(last.value().toString()),
                      3));
      assertEquals(2, rows.size());
      assertEquals(Long.toString(28 * DepositYield.DAY), rows.getFirst().get("due_ms"));
      assertEquals(lastMonth, Long.parseLong(rows.getFirst().get("amount_minor")));
      assertEquals(Long.toString(56 * DepositYield.DAY), rows.getLast().get("due_ms"));
    }
  }

  @Test
  void seventeenResidualOnlyHistoricalClaimsDoNotPermanentlyBlockClosure() throws Exception {
    try (var f = new Fixture(0, 0, false)) {
      f.day(1, account);
      f.store.transaction(
          tx -> {
            int count = 0;
            for (var bank : InstitutionCatalog.banks()) {
              if (bank.id().equals(BANK)) continue;
              tx.execute(
                  "INSERT INTO institution_parties VALUES(?,?,?,?,?, ?,1)",
                  List.of(
                      f.world.value().toString(),
                      bank.id().key(),
                      new InstitutionCatalog.SystemActor(f.world, bank.id())
                          .actorId()
                          .value()
                          .toString(),
                      bank.name(),
                      bank.baseCurrency().name(),
                      bank.tier().name()));
              tx.execute(
                  "INSERT INTO deposit_yield_claims VALUES(?,?,?,'CAD',0,?,?,0,'0.000000000000000000','0.000100000000000000','0.000000000000000000',0,0,0,1,'0.000000000000000000','0',?,0,0)",
                  List.of(
                      account.value().toString(),
                      owner.value().toString(),
                      bank.id().key(),
                      Long.toString(DepositYield.DAY),
                      Long.toString(DepositYield.DAY),
                      Long.toString(DepositYield.DAY)));
              if (++count == 17) break;
            }
            return null;
          });
      var kind = new Journal.Kind("fixture:yield", "fixture:residual-close");
      var close =
          new JournalEngine.Commit(
              List.of(
                  new Journal.Record(
                      "economy:bank-lifecycle",
                      1,
                      "closed|" + account.value() + "|" + owner.value() + "|CAD|2")),
              new Journal.Result(1, "CLOSED_FIXTURE", ""),
              tx ->
                  tx.execute(
                      "UPDATE account_catalog SET status='CLOSED',revision=2 WHERE account_id=?",
                      List.of(account.value().toString())));
      var engine =
          new JournalEngine(
              f.store,
              (a, k) -> true,
              Map.of(
                  kind,
                  new JournalEngine.Registration(
                      1, (i, r) -> f.yield.observe(i, r, close, DepositYield.DAY))));
      var result = engine.submit(intent(kind, new Journal.CanonicalRequest(1, Map.of())));
      assertEquals(Journal.Status.COMMITTED, result.status());
      var count =
          f.store
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT account_id FROM deposit_yield_claims WHERE account_id=? AND accrued_major<>'0.000000000000000000'",
                          List.of(account.value().toString()),
                          20))
              .size();
      assertEquals(0, count);
      var batches =
          f.store.transaction(
              tx ->
                  tx.query(
                      "SELECT payload FROM journal_records WHERE record_type=?",
                      List.of(DepositYield.ROUNDING_BATCH),
                      2));
      assertEquals(2, batches.size());
    }
  }

  @Test
  void negativeHalfEvenCarryReducesCompoundBasisWithoutCreatingNegativeYield() {
    var principal = new BigDecimal("100.00");
    var residual = new BigDecimal("-0.004000000000000000");
    var weighted = principal.multiply(BigDecimal.valueOf(DepositYield.DAY));
    var actual = DepositYield.dailyInterest(weighted, residual, APR);
    var expected =
        principal
            .add(residual)
            .multiply(APR, DepositYield.MC)
            .divide(new BigDecimal("364"), DepositYield.MC)
            .setScale(18, RoundingMode.HALF_EVEN);
    assertEquals(expected, actual);
    assertTrue(actual.compareTo(DepositYield.dailyInterest(weighted, BigDecimal.ZERO, APR)) < 0);
    assertEquals(0, DepositYield.dailyInterest(BigDecimal.ZERO, residual, APR).signum());
  }

  @Test
  void emptyWorldStaysDormantAndLateFirstAccountEarnsOnlyItsFundedPartialDay() throws Exception {
    var store = new SqliteStorage();
    store.initialize(path.toRealPath());
    try {
      var world = store.snapshot().worldId();
      var yield = new DepositYield(world, (r, o, b, t) -> APR);
      new DeadlineDispatcher(
              store,
              Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
              List.of(yield.provider(), yield.payoutProvider()),
              16)
          .prepare();
      var activate =
          new JournalEngine(
              store,
              (a, k) -> true,
              Map.of(DepositYield.ACTIVATE, yield.activation(Instant.EPOCH)));
      assertEquals(
          Journal.Status.COMMITTED,
          activate
              .submit(
                  intent(
                      DepositYield.ACTIVATE,
                      new Journal.CanonicalRequest(1, Map.of("effective", "0"))))
              .status());
      assertNull(store.transaction(tx -> yield.provider().next(tx::query, 0, null)));
      assertNull(store.transaction(tx -> yield.payoutProvider().next(tx::query, 0, null)));
      long creation = 100 * DepositYield.DAY + DepositYield.DAY / 2;
      store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO institution_parties VALUES(?,?,?,?,?,'HYBRID',1)",
                List.of(
                    world.value().toString(),
                    BANK.key(),
                    new InstitutionCatalog.SystemActor(world, BANK).actorId().value().toString(),
                    "Crownstone",
                    "CAD"));
            return null;
          });
      var legs =
          List.of(
              new PostingBatch.Leg(
                  account.value(), PostingBatch.Role.ACCOUNT, CAD, new Money(CAD, 10_000_000)),
              new PostingBatch.Leg(
                  Settlement.issuanceAccount(CAD),
                  PostingBatch.Role.ISSUANCE,
                  CAD,
                  new Money(CAD, -10_000_000)));
      var batch =
          (JournalEngine.Commit) PostingBatch.prepareMinor(legs, PostingBatch.Authority.ISSUE);
      var records = new ArrayList<>(batch.records());
      records.add(
          new Journal.Record(
              "economy:bank-provider",
              1,
              "opened|" + account.value() + "|" + BANK.key() + "|1|0|0"));
      records.add(
          new Journal.Record(
              "economy:provider-account",
              1,
              world.value() + "|" + account.value() + "|" + owner.value() + "|CAD|OPEN|1|1"));
      var opening =
          new JournalEngine.Commit(
              records,
              new Journal.Result(1, "OPEN_FIXTURE", ""),
              tx -> {
                tx.execute(
                    "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                    List.of(account.value().toString(), owner.value().toString()));
                tx.execute(
                    "INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",
                    List.of(account.value().toString(), BANK.key()));
                LedgerProjection.write(tx, account.value(), CAD, 10_000_000);
                LedgerProjection.write(tx, Settlement.issuanceAccount(CAD), CAD, -10_000_000);
              });
      var kind = new Journal.Kind("fixture:yield", "fixture:late-open");
      var engine =
          new JournalEngine(
              store,
              (a, k) -> true,
              Map.of(
                  kind,
                  new JournalEngine.Registration(
                      1, (i, r) -> yield.observe(i, r, opening, creation))));
      assertEquals(
          Journal.Status.COMMITTED,
          engine.submit(intent(kind, new Journal.CanonicalRequest(1, Map.of()))).status());
      var next = store.transaction(tx -> yield.provider().next(tx::query, 0, null));
      var payout = store.transaction(tx -> yield.payoutProvider().next(tx::query, 0, null));
      assertEquals(101 * DepositYield.DAY, next.effectiveMillis());
      assertEquals(next.effectiveMillis(), payout.effectiveMillis());
      assertTrue(next.effectiveMillis() > creation);
      var output =
          store.transaction(
              tx ->
                  yield
                      .provider()
                      .prepare(next, DepositYield.target(account, BANK, CAD), tx::query));
      var effect = new Journal.Kind("fixture:yield", "fixture:late-day");
      var committed =
          new JournalEngine(
              store,
              (a, k) -> true,
              Map.of(
                  effect,
                  new JournalEngine.Registration(
                      1,
                      (i, r) ->
                          new JournalEngine.Commit(
                              output.records(),
                              new Journal.Result(1, "DAY_FIXTURE", ""),
                              output.effects()))));
      assertEquals(
          Journal.Status.COMMITTED,
          committed.submit(intent(effect, new Journal.CanonicalRequest(1, Map.of()))).status());
      var claim =
          store.transaction(tx -> DepositYield.find(tx::query, account, BANK, CAD).orElseThrow());
      var expected =
          DepositYield.dailyInterest(
              new BigDecimal("100000").multiply(BigDecimal.valueOf(DepositYield.DAY / 2)),
              BigDecimal.ZERO,
              APR);
      assertEquals(expected, claim.accruedMajor());
    } finally {
      store.close();
    }
  }
}
