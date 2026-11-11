package ca.emilybelnavis.mcmods.economy.loan;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoanRepaymentFxTest {
  @TempDir Path path;
  private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
  private static final InstitutionCatalog.InstitutionId
      BANK = new InstitutionCatalog.InstitutionId("economy:bank/crownstone"),
      LENDER = new InstitutionCatalog.InstitutionId("economy:bank/crosswind");

  private final class Fixture implements AutoCloseable {
    final SqliteStorage store = new SqliteStorage();
    final WorldId world;
    final PlayerId player = new PlayerId(UUID.randomUUID());
    final PartyRef owner;
    final AccountIdentity funding;
    final UUID loanId = UUID.randomUUID();
    final FxQuotes book;
    final ServicePricing pricing = new ServicePricing(new WealthValuation());
    long now = NOW.toEpochMilli() + LoanServicing.MONTH / 2;

    Fixture() throws Exception {
      store.initialize(path.toRealPath());
      world = store.snapshot().worldId();
      owner = new PartyRef(world, new PartyRef.Player(player));
      book = new FxQuotes(world);
      var open =
          new JournalEngine(
              store,
              (a, k) -> true,
              Map.of(FxService.OPEN, FxService.opening(Clock.fixed(NOW, ZoneOffset.UTC))));
      assertEquals(
          Journal.Status.COMMITTED,
          open.submit(
                  new Journal.Intent(
                      new OperationId(UUID.randomUUID()),
                      new RequestId(UUID.randomUUID()),
                      new SystemActorId(UUID.randomUUID()),
                      FxService.OPEN,
                      new Journal.CanonicalRequest(1, Map.of("currency", "CAD")),
                      null,
                      null))
              .status());
      funding =
          new AccountIdentity(
              new AccountId(UUID.randomUUID()),
              owner,
              AccountIdentity.Purpose.BANK,
              CAD,
              AccountIdentity.Status.OPEN,
              1,
              true);
      var state =
          LoanServicing.initial(USD, 100000, new BigDecimal("0.12"), 1, false, NOW.toEpochMilli());
      var schedule = LoanServicing.schedule(USD, 100000, new BigDecimal("0.12"), 1, false);
      var text =
          new StringBuilder("loan-schedule-v1|")
              .append(schedule.initialMinor())
              .append('|')
              .append(schedule.apr())
              .append('|')
              .append(schedule.periods())
              .append('|')
              .append(schedule.installmentMinor());
      for (var row : schedule.installments())
        text.append('|')
            .append(row.ordinal())
            .append(',')
            .append(row.principalMinor())
            .append(',')
            .append(row.interestMinor())
            .append(',')
            .append(row.paymentMinor())
            .append(',')
            .append(row.remainingPrincipalMinor())
            .append(',')
            .append(row.residualDisposition());
      store.transaction(
          tx -> {
            party(tx, BANK);
            party(tx, LENDER);
            var borrower = Loans.borrower(player);
            party(tx, borrower);
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                List.of(funding.id().value().toString(), player.value().toString()));
            tx.execute(
                "INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",
                List.of(funding.id().value().toString(), BANK.key()));
            LedgerProjection.write(tx, funding.id().value(), CAD, 1_000_000);
            var receivable =
                FinancialAccounts.account(world, LENDER, FinancialAccounts.Role.RECEIVABLE, USD);
            var liability =
                FinancialAccounts.account(world, borrower, FinancialAccounts.Role.LIABILITY, USD);
            for (var a : List.of(receivable, liability)) FinancialAccounts.register(tx, a);
            LedgerProjection.write(tx, receivable.id(), USD, 100000);
            LedgerProjection.write(tx, liability.id(), USD, -100000);
            var fx = FxService.latest(tx::query);
            String math =
                pack(
                    List.of(
                        "USD",
                        "0.12",
                        "100000",
                        "0",
                        "0.000000000000000000",
                        "0.000000000000000000",
                        "0.000000000000000000",
                        Long.toString(state.periodStart()),
                        Long.toString(state.lastAccrued()),
                        Long.toString(state.installmentMinor()),
                        "0",
                        "13",
                        "false",
                        ""));
            String body =
                pack(
                    List.of(
                        world.value().toString(),
                        loanId.toString(),
                        player.value().toString(),
                        LENDER.key(),
                        "BANK_LOAN",
                        "USD",
                        "100000",
                        "1",
                        "0.12",
                        "0.06",
                        "1",
                        "650",
                        "1",
                        "1",
                        "1",
                        "0",
                        fx.updateId().toString(),
                        "CAD",
                        Long.toString(NOW.toEpochMilli()),
                        WealthValuation.digest(text.toString()),
                        "1",
                        "ACTIVE",
                        math));
            Loans.validateFact(body);
            tx.execute(
                "INSERT INTO loans VALUES(?,?,?,?,?,?,'ACTIVE',?,?,1,?)",
                List.of(
                    world.value().toString(),
                    loanId.toString(),
                    player.value().toString(),
                    LENDER.key(),
                    "BANK_LOAN",
                    "USD",
                    "100000",
                    Long.toString(state.nextDue()),
                    body));
            return null;
          });
    }

    void party(FoundationStore.Transaction tx, InstitutionCatalog.InstitutionId id) {
      tx.execute(
          "INSERT INTO institution_parties VALUES(?,?,?,?,?,'HYBRID',1)",
          List.of(
              world.value().toString(),
              id.key(),
              new InstitutionCatalog.SystemActor(world, id).actorId().value().toString(),
              "Fixture",
              "CAD"));
    }

    LoanRepaymentFx.Quote quote(long amount, boolean full) {
      return store.transaction(
          tx -> {
            var payment = Loans.quote(tx::query, owner, loanId, funding.id(), amount, full, now);
            var fx =
                book.issue(
                    player,
                    new FxQuotes.Pair(CAD, USD),
                    FxQuotes.Side.EXACT_CREDIT,
                    new Money(USD, payment.request().amountMinor()),
                    FxService.latest(tx::query),
                    Instant.ofEpochMilli(now),
                    Duration.ofSeconds(60));
            var price =
                pricing.quote(
                    tx::query,
                    world,
                    player,
                    BANK,
                    ServicePricing.Service.FX,
                    fx.debit(),
                    Instant.ofEpochMilli(now),
                    fx.expires());
            fx = book.price(fx, price);
            return LoanRepaymentFx.capture(tx::query, owner, payment, fx, now);
          });
    }

    FxQuotes.Quote fresh(UUID id, PlayerId actor, WorldId w, JournalEngine.ReadView reads) {
      var fx =
          book.requireFresh(id, actor, w, FxService.latest(reads), 1, Instant.ofEpochMilli(now));
      pricing.requireFresh(fx.pricing(), reads, w, actor, Instant.ofEpochMilli(now));
      return fx;
    }

    Journal.Intent intent(LoanRepaymentFx.Quote q) {
      return new Journal.Intent(
          new OperationId(UUID.randomUUID()),
          new RequestId(UUID.randomUUID()),
          player,
          Loans.REPAY,
          q.canonical(),
          null,
          null);
    }

    Journal.Outcome submit(LoanRepaymentFx.Quote q, Journal.Intent intent) {
      return new JournalEngine(
              store,
              (a, k) -> true,
              Map.of(Loans.REPAY, LoanRepaymentFx.registration(owner, q, this::fresh, () -> now)))
          .submit(intent);
    }

    Loans.Contract loan() {
      return store.transaction(tx -> Loans.find(tx::query, loanId).orElseThrow());
    }

    long balance(UUID id, EconomyCurrency c) {
      return store.transaction(tx -> Settlement.posted(tx::query, id, c));
    }

    @Override
    public void close() throws Exception {
      store.close();
    }
  }

  private static String pack(List<String> fields) {
    return String.join(
        "|",
        fields.stream()
            .map(
                s ->
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(s.getBytes(StandardCharsets.UTF_8)))
            .toList());
  }

  @Test
  void nearestMinorFundingRetainsSignedResidualWithVersionedHistory() throws Exception {
    try (var f = new Fixture()) {
      var quote = f.quote(102, false);
      assertEquals(142, quote.fx().debit().minorUnits());
      assertTrue(quote.referenceResidual().signum() < 0);
      var intent = f.intent(quote);
      var outcome = f.submit(quote, intent);
      assertEquals(Journal.Status.COMMITTED, outcome.status());
      assertEquals(outcome, f.submit(quote, intent));
      var fact =
          f.store.transaction(
              tx ->
                  tx.query(
                          "SELECT record_version,payload FROM journal_records WHERE record_type=?",
                          List.of(LoanRepaymentFx.FACT),
                          1)
                      .getFirst());
      assertEquals("2", fact.get("record_version"));
      var payload = fact.get("payload");
      var fields = payload.split("\\|", -1);
      assertEquals(quote.referenceResidual(), new BigDecimal(fields[13]));
      var catalog = RecordCatalog.core();
      assertDoesNotThrow(() -> catalog.validate(LoanRepaymentFx.FACT, 2, payload));
      assertThrows(
          StorageException.class, () -> catalog.validate(LoanRepaymentFx.FACT, 1, payload));
      fields[13] = quote.referenceResidual().abs().toPlainString();
      String legacy = String.join("|", fields);
      assertDoesNotThrow(() -> catalog.validate(LoanRepaymentFx.FACT, 1, legacy));
    }
  }

  @Test
  void partialFxPaymentChargesSeparateFeeAndRecognizesActualSegmentWithoutWaiver()
      throws Exception {
    try (var f = new Fixture()) {
      var q = f.quote(10000, false);
      var intent = f.intent(q);
      f.now += 1000;
      var first = f.submit(q, intent);
      assertEquals(Journal.Status.COMMITTED, first.status());
      assertEquals(
          1_000_000 - q.fx().debit().minorUnits() - q.fx().fee().minorUnits(),
          f.balance(f.funding.id().value(), CAD));
      assertFalse(f.loan().servicing().closed());
      assertEquals(f.now, f.loan().servicing().lastAccrued());
      assertEquals(q.fx().debit().minorUnits(), f.balance(Settlement.clearingAccount(CAD), CAD));
      assertEquals(-10000, f.balance(Settlement.clearingAccount(USD), USD));
      assertEquals(
          q.fx().fee().minorUnits(),
          f.balance(FinancialAccounts.id(f.world, BANK, FinancialAccounts.Role.CASH, CAD), CAD));
      long frontier = f.store.lastCommittedSequence();
      assertEquals(first, f.submit(q, intent));
      assertEquals(frontier, f.store.lastCommittedSequence());
      var receipt =
          f.store.transaction(
              tx ->
                  tx.query(
                          "SELECT payload FROM journal_records WHERE record_type=?",
                          List.of(LoanRepaymentFx.FACT),
                          1)
                      .getFirst()
                      .get("payload"));
      assertTrue(receipt.endsWith("|false"));
      var paymentEvent =
          f.store.transaction(
              tx ->
                  tx.query(
                          "SELECT payload FROM journal_records WHERE record_type=?",
                          List.of(Loans.EVENT_FACT),
                          1)
                      .getFirst()
                      .get("payload"));
      var eventFields =
          Arrays.stream(paymentEvent.split("\\|", -1))
              .map(s -> new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8))
              .toList();
      assertEquals(
          0,
          new BigDecimal(eventFields.get(3).split("\\|", -1)[5]).signum(),
          "Partial payment cannot receive quote-to-commit interest waiver");
    }
  }

  @Test
  void frozenFullPayoffRecordsOnlyAdditionalQuoteToCommitInterestWaiverAndClosesExactly()
      throws Exception {
    try (var f = new Fixture()) {
      var q = f.quote(0, true);
      var math =
          LoanServicing.settlePayoff(f.loan().servicing(), q.payment().payoff(), f.now + 1000);
      assertTrue(math.additionalInterestWaived().signum() > 0);
      f.now += 1000;
      assertEquals("LOAN_SETTLED", f.submit(q, f.intent(q)).result().code());
      assertTrue(f.loan().servicing().closed());
      assertEquals(0, f.loan().servicing().exactDebtMajor().signum());
      var event =
          f.store.transaction(
              tx ->
                  tx.query(
                          "SELECT payload FROM journal_records WHERE record_type=?",
                          List.of(Loans.EVENT_FACT),
                          1)
                      .getFirst()
                      .get("payload"));
      var fields =
          Arrays.stream(event.split("\\|", -1))
              .map(s -> new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8))
              .toList();
      assertTrue(fields.get(3).contains(math.additionalInterestWaived().toPlainString()));
      assertEquals(
          0,
          f.balance(
              FinancialAccounts.id(
                  f.world, Loans.borrower(f.player), FinancialAccounts.Role.LIABILITY, USD),
              USD));
    }
  }

  @Test
  void feeFailureAndExpiredOrForeignQuoteCannotPartiallySettleOrConsumeFx() throws Exception {
    try (var f = new Fixture()) {
      var first = f.quote(10000, false);
      f.store.transaction(
          tx -> {
            LedgerProjection.write(
                tx, f.funding.id().value(), CAD, first.fx().debit().minorUnits());
            return null;
          });
      var q = f.quote(10000, false);
      var result = f.submit(q, f.intent(q));
      assertNotEquals(Journal.Status.COMMITTED, result.status());
      assertEquals(100000, f.loan().owedMinor());
      assertEquals(
          0,
          f.store
              .transaction(
                  tx ->
                      tx.query(
                              "SELECT quote_id FROM ledger_fx_quotes WHERE quote_id=?",
                              List.of(q.fx().id().toString()),
                              1)
                          .size())
              .intValue());
      f.now = q.payment().request().expiresAt();
      assertEquals("LOAN_PAYMENT_STALE", f.submit(q, f.intent(q)).result().code());
      var foreign = new PartyRef(f.world, new PartyRef.Player(new PlayerId(UUID.randomUUID())));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              f.store.transaction(
                  tx ->
                      LoanRepaymentFx.capture(
                          tx::query,
                          foreign,
                          q.payment(),
                          q.fx(),
                          q.payment().request().capturedAt())));
    }
  }
}
