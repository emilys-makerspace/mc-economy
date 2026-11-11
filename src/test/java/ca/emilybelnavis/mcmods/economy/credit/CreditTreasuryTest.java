package ca.emilybelnavis.mcmods.economy.credit;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
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

class CreditTreasuryTest {
  @TempDir Path path;
  private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
  private static final SystemActorId ACTOR = new SystemActorId(new UUID(0, 881));
  private static final InstitutionCatalog.InstitutionId BANK =
      new InstitutionCatalog.InstitutionId("economy:bank/crownstone");
  private static final Journal.Kind EFFECT =
      new Journal.Kind("fixture:treasury", "fixture:reserve");

  private final class Fixture implements AutoCloseable {
    final SqliteStorage store = new SqliteStorage();
    final WorldId world;

    Fixture() throws Exception {
      store.initialize(path.toRealPath());
      world = store.snapshot().worldId();
      var opening =
          new JournalEngine(
              store,
              (a, k) -> true,
              Map.of(
                  FxService.OPEN,
                  FxService.opening(Clock.fixed(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC))));
      assertEquals(
          Journal.Status.COMMITTED,
          opening
              .submit(
                  intent(
                      FxService.OPEN, new Journal.CanonicalRequest(1, Map.of("currency", "CAD"))))
              .status());
      var rates = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
      for (var c : EconomyCurrency.all()) rates.put(c, new BigDecimal("1.000000000000"));
      rates.put(USD, new BigDecimal("1.333333333333"));
      var fx =
          new FxFacts.Publication(
              LocalDate.of(2026, 11, 5), CAD, UUID.randomUUID(), UUID.randomUUID(), rates);
      var kind = new Journal.Kind("fixture:treasury", "fixture:fx");
      var registration =
          new JournalEngine.Registration(
              1,
              (i, r) ->
                  new JournalEngine.Commit(
                      List.of(new Journal.Record(FxFacts.PUBLICATION, fx.version(), fx.encode())),
                      new Journal.Result(1, "FIXTURE", ""),
                      tx -> {}));
      assertEquals(
          Journal.Status.COMMITTED,
          new JournalEngine(store, (a, k) -> true, Map.of(kind, registration))
              .submit(intent(kind, new Journal.CanonicalRequest(1, Map.of())))
              .status());
      var capital = new InstitutionCapital(store, ACTOR, CapitalPosting.poster());
      for (int n = 0; n < 26; n++)
        assertEquals(Journal.Status.COMMITTED, capital.advance(NOW).status());
    }

    FinancialAccounts.Account account(FinancialAccounts.Role role, EconomyCurrency c) {
      return FinancialAccounts.account(world, BANK, role, c);
    }

    long balance(FinancialAccounts.Role role, EconomyCurrency c) {
      return store.transaction(tx -> Settlement.posted(tx::query, account(role, c).id(), c));
    }

    void cash(EconomyCurrency c, long amount) {
      store.transaction(
          tx -> {
            var a = account(FinancialAccounts.Role.CASH, c);
            FinancialAccounts.register(tx, a);
            LedgerProjection.write(tx, a.id(), c, amount);
            return null;
          });
    }

    long floor() {
      return store.transaction(
          tx ->
              Long.parseLong(
                  tx.query(
                          "SELECT operating_floor_minor FROM institution_capital_allocations WHERE institution_key=?",
                          List.of(BANK.key()),
                          1)
                      .getFirst()
                      .get("operating_floor_minor")));
    }

    JournalEngine.Decision prepare(EconomyCurrency c, long amount, long now) {
      return store.transaction(
          tx -> CreditTreasury.reserve(tx::query, world, BANK, c, amount, now));
    }

    Journal.Outcome submit(EconomyCurrency c, long amount, Journal.Intent intent) {
      return new JournalEngine(
              store,
              (a, k) -> true,
              Map.of(
                  EFFECT,
                  new JournalEngine.Registration(
                      1,
                      (i, r) ->
                          CreditTreasury.reserve(r, world, BANK, c, amount, NOW.toEpochMilli()))))
          .submit(intent);
    }

    public void close() throws Exception {
      store.close();
    }
  }

  private static Journal.Intent intent(Journal.Kind kind, Journal.CanonicalRequest request) {
    return new Journal.Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        ACTOR,
        kind,
        request,
        null,
        null);
  }

  private static String rejection(JournalEngine.Decision d) {
    return assertInstanceOf(JournalEngine.Reject.class, d).result().code();
  }

  private static void balanced(JournalEngine.Commit commit) {
    var totals = new EnumMap<EconomyCurrency, Long>(EconomyCurrency.class);
    for (var fact : commit.records())
      if (fact.type().equals("economy:posting")) {
        var p = fact.payload().split("\\|", -1);
        totals.merge(EconomyCurrency.valueOf(p[2]), Long.parseLong(p[3]), Math::addExact);
      }
    assertFalse(totals.isEmpty());
    assertTrue(totals.values().stream().allMatch(v -> v == 0), totals.toString());
  }

  @Test
  void sameCurrencySegregatesCashAtFloorAndOriginalOperationReplaysOnce() throws Exception {
    try (var f = new Fixture()) {
      long floor = f.floor();
      f.cash(CAD, floor + 1000);
      var decision =
          assertInstanceOf(JournalEngine.Commit.class, f.prepare(CAD, 1000, NOW.toEpochMilli()));
      balanced(decision);
      assertEquals("CREDIT_OPERATING_FLOOR", rejection(f.prepare(CAD, 1001, NOW.toEpochMilli())));
      var intent = intent(EFFECT, new Journal.CanonicalRequest(1, Map.of("amount", "1000")));
      assertEquals(Journal.Status.COMMITTED, f.submit(CAD, 1000, intent).status());
      assertEquals(floor, f.balance(FinancialAccounts.Role.CASH, CAD));
      assertEquals(1000, f.balance(FinancialAccounts.Role.RESERVATION, CAD));
      long frontier = f.store.lastCommittedSequence();
      assertEquals(Journal.Status.COMMITTED, f.submit(CAD, 1000, intent).status());
      assertEquals(frontier, f.store.lastCommittedSequence());
      assertEquals(1000, f.balance(FinancialAccounts.Role.RESERVATION, CAD));
      assertEquals("CREDIT_OPERATING_FLOOR", rejection(f.prepare(CAD, 1, NOW.toEpochMilli())));
    }
  }

  @Test
  void foreignLineUsesActualBalancedFxAndConservativeBaseDebitWithResidual() throws Exception {
    try (var f = new Fixture()) {
      long floor = f.floor();
      f.cash(CAD, floor + 1000);
      f.cash(USD, 0);
      var decision =
          assertInstanceOf(JournalEngine.Commit.class, f.prepare(USD, 101, NOW.toEpochMilli()));
      balanced(decision);
      var fact =
          decision.records().stream()
              .filter(r -> r.type().equals(CreditTreasury.FACT))
              .findFirst()
              .orElseThrow();
      var fields = fact.payload().split("\\|", -1);
      var fx = fields[6].split(",", -1);
      assertEquals("CAD", fx[1]);
      assertEquals("135", fx[2]);
      assertEquals("USD", fx[3]);
      assertEquals("101", fx[4]);
      assertEquals(0, new BigDecimal("0.00333333333367").compareTo(new BigDecimal(fx[6])));
      assertEquals(
          Journal.Status.COMMITTED,
          f.submit(
                  USD,
                  101,
                  intent(EFFECT, new Journal.CanonicalRequest(1, Map.of("amount", "101"))))
              .status());
      assertEquals(floor + 865, f.balance(FinancialAccounts.Role.CASH, CAD));
      assertEquals(0, f.balance(FinancialAccounts.Role.CASH, USD));
      assertEquals(101, f.balance(FinancialAccounts.Role.RESERVATION, USD));
      assertEquals(
          135L,
          f.store.<Long>transaction(
              tx -> Settlement.posted(tx::query, Settlement.clearingAccount(CAD), CAD)));
      assertEquals(
          -101L,
          f.store.<Long>transaction(
              tx -> Settlement.posted(tx::query, Settlement.clearingAccount(USD), USD)));
    }
  }

  @Test
  void existingForeignCashNeedsNoNewConversionButBaseFloorStillProtectsOrigination()
      throws Exception {
    try (var f = new Fixture()) {
      long floor = f.floor();
      f.cash(CAD, floor);
      f.cash(USD, 200);
      var commit =
          assertInstanceOf(
              JournalEngine.Commit.class,
              f.prepare(USD, 150, NOW.plus(Duration.ofDays(3)).toEpochMilli()));
      balanced(commit);
      assertTrue(
          commit.records().stream()
              .filter(r -> r.type().equals(CreditTreasury.FACT))
              .findFirst()
              .orElseThrow()
              .payload()
              .endsWith("|NONE"));
      f.cash(CAD, floor - 1);
      assertEquals("CREDIT_OPERATING_FLOOR", rejection(f.prepare(USD, 150, NOW.toEpochMilli())));
    }
  }

  @Test
  void unavailableFxAndUnpaidYieldDenyWithoutMovingCash() throws Exception {
    try (var f = new Fixture()) {
      long before = f.balance(FinancialAccounts.Role.CASH, CAD);
      assertEquals(
          "CREDIT_FX_UNAVAILABLE",
          rejection(f.prepare(USD, 100, NOW.plus(Duration.ofDays(3)).toEpochMilli())));
      assertEquals("CREDIT_FUNDING_AMOUNT", rejection(f.prepare(CAD, 0, NOW.toEpochMilli())));
      var distressed =
          f.store.transaction(
              tx ->
                  CreditTreasury.reserve(
                      (sql, args, limit) ->
                          sql.startsWith("SELECT account_id FROM deposit_yield_claims")
                              ? List.of(Map.of("account_id", UUID.randomUUID().toString()))
                              : tx.query(sql, args, limit),
                      f.world,
                      BANK,
                      CAD,
                      100,
                      NOW.toEpochMilli()));
      assertEquals("CREDIT_CAPITAL_UNAVAILABLE", rejection(distressed));
      assertEquals(before, f.balance(FinancialAccounts.Role.CASH, CAD));
      assertEquals(0, f.balance(FinancialAccounts.Role.RESERVATION, CAD));
    }
  }
}
