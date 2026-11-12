package ca.emilybelnavis.mcmods.economy.overdraft;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.FinancialCalendar;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OverdraftTest {
  @TempDir Path path;
  private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
  private static final InstitutionCatalog.InstitutionId BANK =
      new InstitutionCatalog.InstitutionId("economy:bank/crownstone");

  private final class Fixture implements AutoCloseable {
    final SqliteStorage store = new SqliteStorage();
    final PlayerId player = new PlayerId(UUID.randomUUID());
    final AccountId source = new AccountId(UUID.randomUUID());
    final AccountId target = new AccountId(UUID.randomUUID());
    final AtomicLong time = new AtomicLong(NOW.toEpochMilli());
    final PartyRef owner;

    Fixture() throws Exception {
      store.initialize(path.toRealPath());
      owner = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(player));
      submit(
          FxService.OPEN,
          FxService.opening(Clock.fixed(NOW, ZoneOffset.UTC)),
          new Journal.CanonicalRequest(1, Map.of("currency", "CAD")));
      var capital =
          new InstitutionCapital(
              store, new SystemActorId(UUID.randomUUID()), CapitalPosting.poster());
      for (int n = 0; n < 26; n++)
        assertEquals(Journal.Status.COMMITTED, capital.advance(NOW).status());
      store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO financial_calendar(singleton,version,origin_ms,observed_wall_ms,last_effective_ms,completed_effects) VALUES(1,1,?,?,?,0)",
                List.of(
                    Long.toString(time.get()),
                    Long.toString(time.get()),
                    Long.toString(time.get())));
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                List.of(source.value().toString(), player.value().toString()));
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                List.of(target.value().toString(), UUID.randomUUID().toString()));
            tx.execute(
                "INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",
                List.of(source.value().toString(), BANK.key()));
            return null;
          });
    }

    Journal.Intent intent(Journal.Kind kind, Journal.CanonicalRequest request) {
      return new Journal.Intent(
          new OperationId(UUID.randomUUID()),
          new RequestId(UUID.randomUUID()),
          player,
          kind,
          request,
          null,
          null);
    }

    Journal.Outcome submit(
        Journal.Kind kind,
        JournalEngine.Registration registration,
        Journal.CanonicalRequest request) {
      return new JournalEngine(store, (a, k) -> true, Map.of(kind, registration))
          .submit(intent(kind, request));
    }

    Journal.Outcome debit(long minor, Overdrafts.DebitKind kind) {
      var amount = new Money(EconomyCurrency.CAD, minor);
      return submit(
          Overdrafts.DEBIT,
          Overdrafts.debit(owner, source.value(), target.value(), amount, kind, time::get),
          Overdrafts.debitRequest(owner, source.value(), target.value(), amount, kind));
    }

    long balance() {
      return store.transaction(
          tx -> Settlement.posted(tx::query, source.value(), EconomyCurrency.CAD));
    }

    Overdrafts.Protection state() {
      return store.transaction(tx -> Overdrafts.find(tx::query, source.value()).orElseThrow());
    }

    void protect() {
      var fx = store.transaction(tx -> FxService.at(tx::query, time.get()));
      var request =
          new Overdrafts.ApplicationRequest(owner.world(), player, source, 10000, 1, 0, fx.id());
      assertEquals(
          "OVERDRAFT_APPROVED",
          submit(
                  Overdrafts.APPLY,
                  Overdrafts.application(owner, request, time::get),
                  request.request())
              .result()
              .code());
    }

    void day() {
      var state = state();
      long next = Overdrafts.deadline(state);
      time.set(next);
      store.transaction(
          tx -> {
            tx.execute(
                "UPDATE financial_calendar SET observed_wall_ms=?", List.of(Long.toString(next)));
            return null;
          });
      var kind = new Journal.Kind("economy:test-overdraft", "economy:day");
      var result =
          submit(
              kind,
              new JournalEngine.Registration(
                  1, (i, r) -> Overdrafts.daily(r, source.value(), next)),
              new Journal.CanonicalRequest(1, Map.of("at", Long.toString(next))));
      assertEquals(Journal.Status.COMMITTED, result.status(), result.result().code());
    }

    public void close() throws Exception {
      store.close();
    }
  }

  @Test
  void smallUnprotectedDebtIsFreeButFailedPadChargesExactlyOnce() throws Exception {
    try (var f = new Fixture()) {
      assertEquals(Journal.Status.COMMITTED, f.debit(500, Overdrafts.DebitKind.NORMAL).status());
      assertEquals(-500, f.balance());
      assertEquals("INSUFFICIENT_FUNDS", f.debit(1, Overdrafts.DebitKind.NORMAL).result().code());
      assertEquals(-500, f.balance());
      var amount = new Money(EconomyCurrency.CAD, 1);
      var request =
          Overdrafts.debitRequest(
              f.owner,
              f.source.value(),
              f.target.value(),
              amount,
              Overdrafts.DebitKind.PRE_AUTHORIZED);
      var registration =
          Overdrafts.debit(
              f.owner,
              f.source.value(),
              f.target.value(),
              amount,
              Overdrafts.DebitKind.PRE_AUTHORIZED,
              f.time::get);
      var intent = f.intent(Overdrafts.DEBIT, request);
      var engine =
          new JournalEngine(f.store, (a, k) -> true, Map.of(Overdrafts.DEBIT, registration));
      var outcome = engine.submit(intent);
      assertEquals("PAD_DECLINED_FEE_CHARGED", outcome.result().code());
      assertEquals(-5499, f.balance());
      assertEquals(outcome, engine.submit(intent));
      assertEquals(-5499, f.balance());
      assertEquals(
          500L,
          f.store
              .transaction(
                  tx -> Settlement.posted(tx::query, f.target.value(), EconomyCurrency.CAD))
              .longValue());
    }
  }

  @Test
  void approvedAllowanceLimitsOrdinarySpendingButTrustedOverrideAndFeesCanExceedIt()
      throws Exception {
    try (var f = new Fixture()) {
      f.protect();
      assertEquals(Journal.Status.COMMITTED, f.debit(10000, Overdrafts.DebitKind.NORMAL).status());
      assertEquals("INSUFFICIENT_FUNDS", f.debit(1, Overdrafts.DebitKind.NORMAL).result().code());
      assertEquals(
          Journal.Status.COMMITTED,
          f.debit(100, Overdrafts.DebitKind.INTEGRATION_OVERRIDE).status());
      assertEquals(
          Journal.Status.COMMITTED, f.debit(100, Overdrafts.DebitKind.BANK_CHARGE).status());
      long initial = f.balance();
      for (int n = 0; n < 28; n++) f.day();
      assertTrue(
          f.balance() < initial - 500,
          "Monthly fee and daily interest both increase negative debt");
      assertEquals(NOW.toEpochMilli() + 2 * FinancialCalendar.MONTH_MILLIS, f.state().nextMonth());
    }
  }

  @Test
  void interestTracksActualNegativeDurationEvenWhenRepaidBeforeDailyCutoff() throws Exception {
    try (var f = new Fixture()) {
      f.protect();
      f.debit(10000, Overdrafts.DebitKind.NORMAL);
      long halfway = NOW.toEpochMilli() + Overdrafts.DAY / 2;
      f.time.set(halfway);
      f.store.transaction(
          tx -> {
            tx.execute(
                "UPDATE financial_calendar SET observed_wall_ms=?",
                List.of(Long.toString(halfway)));
            return null;
          });
      var legs =
          List.of(
              new PostingBatch.Leg(
                  f.target.value(),
                  PostingBatch.Role.ACCOUNT,
                  EconomyCurrency.CAD,
                  new Money(EconomyCurrency.CAD, -10000)),
              new PostingBatch.Leg(
                  f.source.value(),
                  PostingBatch.Role.ACCOUNT,
                  EconomyCurrency.CAD,
                  new Money(EconomyCurrency.CAD, 10000)));
      var kind = new Journal.Kind("economy:test-overdraft", "economy:return");
      assertEquals(
          Journal.Status.COMMITTED,
          f.submit(
                  kind,
                  Settlement.registrationMinor((i, l, r) -> PostingBatch.Authority.TRANSFER),
                  Settlement.minorRequest(legs))
              .status());
      assertEquals(0, f.balance());
      assertTrue(f.state().accruedMinor().compareTo(new java.math.BigDecimal("2")) > 0);
      f.day();
      assertEquals(
          -2,
          f.balance(),
          "Half a day at16% onCAD100 rounds to2c, even though cutoff balance was zero");
    }
  }

  @Test
  void ninetyDaysClosesAccountAndReclassifiesDebtIntoCollections() throws Exception {
    try (var f = new Fixture()) {
      f.protect();
      f.debit(10000, Overdrafts.DebitKind.NORMAL);
      for (int n = 0; n < 90; n++) f.day();
      assertTrue(f.state().closed());
      assertEquals(0, f.balance());
      assertEquals(
          "CLOSED",
          f.store.transaction(
              tx ->
                  tx.query(
                          "SELECT status FROM account_catalog WHERE account_id=?",
                          List.of(f.source.value().toString()),
                          1)
                      .getFirst()
                      .get("status")));
      assertEquals(
          "1",
          f.store.transaction(
              tx ->
                  tx.query(
                          "SELECT COUNT(*) AS n FROM debt_cases WHERE kind='OVERDRAFT'",
                          List.of(),
                          1)
                      .getFirst()
                      .get("n")));
    }
  }
}
