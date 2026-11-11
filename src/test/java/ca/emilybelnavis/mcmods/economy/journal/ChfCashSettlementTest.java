package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CHF cents are a new captured settlement contract; historical whole-major cash behavior stays
 * intact.
 */
class ChfCashSettlementTest {
  @TempDir Path world;
  private final PlayerId owner = new PlayerId(UUID.randomUUID());
  private final UUID cadAccount = UUID.randomUUID();
  private final UUID chfAccount = UUID.randomUUID();
  private final UUID generation = UUID.randomUUID();
  private final UUID marked = UUID.randomUUID();
  private final TerminalPolicy.Anchor anchor =
      new TerminalPolicy.Anchor("minecraft:overworld", 7, 64, 9);
  private String fxRevision = "fx-1";
  private CurrencyConversion.Rates rates =
      new CurrencyConversion.Rates(CAD, Map.of(CAD, BigDecimal.ONE, CHF, new BigDecimal("1.6")));
  private final Map<String, Deposits.Denomination> depositCatalog =
      Map.of(
          "economy:chf_coin_5", new Deposits.Denomination(CHF, 5, false),
          "economy:chf_coin_100", new Deposits.Denomination(CHF, 100, false),
          "economy:chf_bill_10", new Deposits.Denomination(CHF, 1000, true),
          "economy:cad_coin_25", new Deposits.Denomination(CAD, 25, false),
          "economy:cad_bill_5", new Deposits.Denomination(CAD, 500, true));

  private SqliteStorage open() throws Exception {
    var store = new SqliteStorage();
    store.initialize(world.toRealPath());
    return store;
  }

  private TerminalPolicy.Anchor terminal(EconomyCurrency currency) {
    return currency == CHF
        ? anchor
        : new TerminalPolicy.Anchor(anchor.dimension(), anchor.x() + 8, anchor.y(), anchor.z());
  }

  private UUID physicalGeneration(EconomyCurrency currency) {
    return currency == CHF
        ? generation
        : UUID.nameUUIDFromBytes(
            (generation + "CAD").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private UUID account(EconomyCurrency currency) {
    return currency == CHF ? chfAccount : cadAccount;
  }

  private Map<String, WithdrawalPlanner.Denomination> withdrawalCatalog() {
    var values = new HashMap<String, WithdrawalPlanner.Denomination>();
    depositCatalog.forEach(
        (id, value) ->
            values.put(
                id,
                new WithdrawalPlanner.Denomination(
                    id, value.currency(), value.minorUnits(), value.bill())));
    return Map.copyOf(values);
  }

  private void seed(SqliteStorage store, boolean fund) {
    store.transaction(
        tx -> {
          for (var currency : List.of(CAD, CHF))
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK',?,'OPEN',1,0)",
                List.of(account(currency).toString(), owner.value().toString(), currency.name()));
          tx.execute(
              "INSERT INTO cash_issuance VALUES(?,?,?,'2','2','ACTIVE',1,'fixture')",
              List.of(
                  marked.toString(),
                  store.snapshot().worldId().value().toString(),
                  "economy:chf_coin_5"));
          return null;
        });
    var terminalCatalog = new HashMap<String, TerminalPolicy.Denomination>();
    depositCatalog.forEach(
        (id, value) ->
            terminalCatalog.put(
                id, new TerminalPolicy.Denomination(value.currency(), value.bill())));
    for (var cashCurrency : List.of(CHF, CAD)) {
      var at = terminal(cashCurrency);
      var physicalGeneration = physicalGeneration(cashCurrency);
      var place = TerminalPolicy.Action.PLACE;
      assertEquals(
          Status.COMMITTED,
          new JournalEngine(
                  store,
                  (a, k) -> true,
                  Map.of(
                      place.kind(),
                      TerminalPolicy.registration(
                          place,
                          store.snapshot().worldId(),
                          terminalCatalog,
                          (a, i, c, r) -> true,
                          (a, r) -> false)))
              .submit(
                  new Intent(
                      new OperationId(UUID.randomUUID()),
                      new RequestId(UUID.randomUUID()),
                      owner,
                      place.kind(),
                      TerminalPolicy.placeRequest(
                          at, physicalGeneration, owner, TerminalPolicy.Kind.TELLER, null, 0),
                      null,
                      null))
              .status());
      var bank =
          new ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.InstitutionId(
              "economy:bank/" + (cashCurrency == CHF ? "lakeward_cantonal" : "crownstone") + "");
      assertEquals(
          Status.COMMITTED,
          new JournalEngine(
                  store,
                  (a, k) -> true,
                  Map.of(
                      TerminalBanking.BIND,
                      TerminalBanking.registration(
                          store.snapshot().worldId(),
                          terminalCatalog,
                          (a, i, c, r) -> true,
                          (a, r) -> false)))
              .submit(
                  new Intent(
                      new OperationId(UUID.randomUUID()),
                      new RequestId(UUID.randomUUID()),
                      owner,
                      TerminalBanking.BIND,
                      TerminalBanking.bindRequest(at, physicalGeneration, 1, bank, true),
                      null,
                      null))
              .status());
      var current =
          store
              .transaction(
                  tx ->
                      TerminalPolicy.find(tx::query, store.snapshot().worldId(), at, owner, false))
              .orElseThrow();
      var configure = TerminalPolicy.Action.CONFIGURE;
      var nativeSettings =
          TerminalBanking.settings(current, TerminalPolicy.Mode.BOTH, terminalCatalog);
      assertEquals(
          Status.COMMITTED,
          new JournalEngine(
                  store,
                  (a, k) -> true,
                  Map.of(
                      configure.kind(),
                      TerminalPolicy.registration(
                          configure,
                          store.snapshot().worldId(),
                          terminalCatalog,
                          (a, i, c, r) -> true,
                          (a, r) -> false)))
              .submit(
                  new Intent(
                      new OperationId(UUID.randomUUID()),
                      new RequestId(UUID.randomUUID()),
                      owner,
                      configure.kind(),
                      TerminalPolicy.configureRequest(at, physicalGeneration, 2, nativeSettings),
                      null,
                      null))
              .status());
    }
    if (fund)
      for (var currency : List.of(CAD, CHF)) {
        var kind = new Kind("economy:chf_fixture", "economy:fund");
        var legs =
            List.of(
                new PostingBatch.Leg(
                    account(currency),
                    PostingBatch.Role.ACCOUNT,
                    currency,
                    new Money(currency, 1000)),
                new PostingBatch.Leg(
                    Settlement.issuanceAccount(currency),
                    PostingBatch.Role.ISSUANCE,
                    currency,
                    new Money(currency, -1000)));
        var registration =
            currency == CHF
                ? Settlement.registrationMinor((i, l, r) -> PostingBatch.Authority.ISSUE)
                : Settlement.registration((i, l, r) -> PostingBatch.Authority.ISSUE);
        var request = currency == CHF ? Settlement.minorRequest(legs) : Settlement.request(legs);
        var engine = new JournalEngine(store, (a, k) -> true, Map.of(kind, registration));
        assertEquals(Status.COMMITTED, engine.submit(intent(kind, request)).status());
      }
  }

  private Intent intent(Kind kind, CanonicalRequest request) {
    return new Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        owner,
        kind,
        request,
        null,
        null);
  }

  private Deposits.Slot slot(SqliteStorage store, int index, String item, boolean unmarked) {
    return new Deposits.Slot(
        index,
        new CashIssuance.Claim(
            store.snapshot().worldId(), unmarked ? UUID.randomUUID() : marked, item, 1),
        "fixture-components-" + index,
        unmarked);
  }

  private Deposits.Preview depositPreview(
      SqliteStorage store, EconomyCurrency currency, List<Deposits.Slot> slots) {
    return store.transaction(
        tx ->
            Deposits.preview(
                tx::query,
                store.snapshot().worldId(),
                owner,
                new Deposits.Binding(
                    account(currency),
                    1,
                    currency,
                    terminal(depositCatalog.get(slots.getFirst().claim().item()).currency()),
                    physicalGeneration(
                        depositCatalog.get(slots.getFirst().claim().item()).currency()),
                    3,
                    "fx-1"),
                slots,
                depositCatalog,
                reads -> new Deposits.FxSnapshot(fxRevision, rates)));
  }

  private JournalEngine depositEngine(SqliteStorage store, Deposits.Action action) {
    return new JournalEngine(
        store,
        (a, k) -> a.equals(owner),
        Map.of(
            action.kind(),
            Deposits.registration(
                action,
                store.snapshot().worldId(),
                depositCatalog,
                reads -> new Deposits.FxSnapshot(fxRevision, rates),
                (a, i, current, reads) -> true,
                (a, i, current, evidence, reads) -> true)));
  }

  private Intent commitDeposit(SqliteStorage store, Deposits.Preview preview) {
    var reserve = intent(Deposits.Action.RESERVE.kind(), Deposits.reserveRequest(preview));
    assertEquals(
        Status.COMMITTED, depositEngine(store, Deposits.Action.RESERVE).submit(reserve).status());
    var saved =
        intent(
            Deposits.Action.SAVED.kind(),
            Deposits.request(reserve.operationId().value(), "saved", "fixture"));
    assertEquals(
        Status.COMMITTED, depositEngine(store, Deposits.Action.SAVED).submit(saved).status());
    var commit =
        intent(
            Deposits.Action.COMMIT.kind(),
            Deposits.request(reserve.operationId().value(), "saved", "fixture"));
    var outcome = depositEngine(store, Deposits.Action.COMMIT).submit(commit);
    assertEquals(Status.COMMITTED, outcome.status(), () -> outcome.result().code());
    return commit;
  }

  private long balance(SqliteStorage store, EconomyCurrency currency) {
    return store.transaction(tx -> Holds.available(tx::query, account(currency), currency));
  }

  private Withdrawals.Preview withdrawalPreview(
      SqliteStorage store,
      EconomyCurrency accountCurrency,
      Money cash,
      Map<String, Integer> edits) {
    return store.transaction(
        tx ->
            Withdrawals.preview(
                tx::query,
                store.snapshot().worldId(),
                owner,
                new Withdrawals.Binding(
                    account(accountCurrency),
                    1,
                    accountCurrency,
                    terminal(cash.currency()),
                    physicalGeneration(cash.currency()),
                    3,
                    "fx-1"),
                cash,
                edits,
                withdrawalCatalog(),
                reads -> new Withdrawals.FxSnapshot(fxRevision, rates)));
  }

  private JournalEngine withdrawalEngine(SqliteStorage store, Withdrawals.Action action) {
    return new JournalEngine(
        store,
        (a, k) -> a.equals(owner),
        Map.of(
            action.kind(),
            Withdrawals.registration(
                action,
                store.snapshot().worldId(),
                withdrawalCatalog(),
                reads -> new Withdrawals.FxSnapshot(fxRevision, rates),
                (a, i, current, reads) -> true,
                (a, i, current, reads) -> true,
                WithdrawalDelivery::prepare)));
  }

  @Test
  void markedUnmarkedAndMixedChfDepositsPreserveCentsAndOriginalReplay() throws Exception {
    var store = open();
    Intent commit;
    Outcome original;
    try {
      seed(store, false);
      var unmarked = slot(store, 0, "economy:chf_coin_5", true);
      var nominal = depositPreview(store, CHF, List.of(unmarked));
      assertEquals(new Money(CHF, 5), nominal.credit());
      assertEquals("CHF_MINOR_V2", Deposits.reserveRequest(nominal).fields().get("settlement"));
      commitDeposit(store, nominal);
      assertEquals(5, balance(store, CHF));
      assertTrue(
          store
              .transaction(
                  tx ->
                      CashIssuance.find(
                          tx::query, store.snapshot().worldId(), unmarked.claim().lot()))
              .isEmpty());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              depositPreview(
                  store,
                  CAD,
                  List.of(
                      slot(store, 0, "economy:chf_coin_5", false),
                      slot(store, 1, "economy:cad_coin_25", true))));
      var mixed =
          depositPreview(
              store,
              CAD,
              List.of(
                  slot(store, 0, "economy:chf_coin_5", false),
                  slot(store, 1, "economy:chf_coin_5", true)));
      assertEquals(new Money(CAD, 16), mixed.credit());
      assertEquals(
          1,
          depositPreview(store, CAD, List.of(slot(store, 0, "economy:cad_coin_25", true)))
              .returned()
              .size(),
          "Legacy CAD cash retains whole-major rounding at its own native station");
      assertEquals(
          new Money(CAD, 8),
          depositPreview(store, CAD, List.of(slot(store, 0, "economy:chf_coin_5", true))).credit());
      commit = commitDeposit(store, mixed);
      original = depositEngine(store, Deposits.Action.COMMIT).submit(commit);
      assertEquals(16, balance(store, CAD));
      assertEquals(
          1,
          store
              .transaction(tx -> CashIssuance.find(tx::query, store.snapshot().worldId(), marked))
              .orElseThrow()
              .remaining());
      var facts =
          store.transaction(
              tx ->
                  tx.query(
                      "SELECT record_version,payload FROM journal_records WHERE record_type=?",
                      List.of(Deposits.FACT),
                      16));
      assertFalse(facts.isEmpty());
      for (var fact : facts) {
        assertEquals("2", fact.get("record_version"));
        assertDoesNotThrow(() -> Deposits.validateFactV2(fact.get("payload")));
        assertThrows(
            IllegalArgumentException.class, () -> Deposits.validateFact(fact.get("payload")));
      }
    } finally {
      store.close();
    }
    var reopened = open();
    try {
      assertEquals(original, depositEngine(reopened, Deposits.Action.COMMIT).submit(commit));
      assertEquals(16, balance(reopened, CAD));
      assertEquals(5, balance(reopened, CHF));
      ca.emilybelnavis.mcmods.economy.storage.LedgerProjection.rebuild(reopened);
      assertEquals(16, balance(reopened, CAD));
      assertEquals(5, balance(reopened, CHF));
      var party = new PartyRef(reopened.snapshot().worldId(), new PartyRef.Player(owner));
      assertEquals(
          List.of(new Money(CAD, 16)),
          reopened
              .transaction(
                  tx -> LedgerHistory.page(tx::query, party, new AccountId(cadAccount), null, 2))
              .orElseThrow()
              .entries()
              .stream()
              .map(LedgerHistory.Entry::amount)
              .toList());
      assertEquals(
          List.of(new Money(CHF, 5)),
          reopened
              .transaction(
                  tx -> LedgerHistory.page(tx::query, party, new AccountId(chfAccount), null, 2))
              .orElseThrow()
              .entries()
              .stream()
              .map(LedgerHistory.Entry::amount)
              .toList());
    } finally {
      reopened.close();
    }
  }

  @Test
  void chfWithdrawalIsExactAndIssuanceReplaysOnceAcrossRestart() throws Exception {
    var store = open();
    Intent commit;
    Outcome original;
    try {
      seed(store, true);
      var preview = withdrawalPreview(store, CHF, new Money(CHF, 105), null);
      assertEquals(new Money(CHF, 105), preview.debit());
      assertEquals(
          Map.of("economy:chf_coin_100", 1, "economy:chf_coin_5", 1), preview.plan().coins());
      assertThrows(
          IllegalArgumentException.class,
          () -> withdrawalPreview(store, CHF, new Money(CHF, 102), null));
      assertEquals(
          new Money(CAD, 8), withdrawalPreview(store, CAD, new Money(CHF, 5), null).debit());
      assertEquals(
          new Money(CHF, 16), withdrawalPreview(store, CHF, new Money(CAD, 25), null).debit());
      var reserve = intent(Withdrawals.Action.RESERVE.kind(), Withdrawals.reserveRequest(preview));
      assertEquals("CHF_MINOR_V2", reserve.request().fields().get("settlement"));
      assertEquals(
          Status.COMMITTED,
          withdrawalEngine(store, Withdrawals.Action.RESERVE).submit(reserve).status());
      commit =
          intent(
              Withdrawals.Action.COMMIT.kind(),
              Withdrawals.request(reserve.operationId().value(), "full capacity", "fixture"));
      original = withdrawalEngine(store, Withdrawals.Action.COMMIT).submit(commit);
      assertEquals(Status.COMMITTED, original.status(), () -> original.result().code());
      assertEquals(original, withdrawalEngine(store, Withdrawals.Action.COMMIT).submit(commit));
      assertEquals(895, balance(store, CHF));
      assertEquals(
          3,
          store
              .transaction(tx -> tx.query("SELECT lot_id FROM cash_issuance", List.of(), 8))
              .size(),
          "One seeded lot and two exact output lots");
      var facts =
          store.transaction(
              tx ->
                  tx.query(
                      "SELECT record_version,payload FROM journal_records WHERE record_type=?",
                      List.of(Withdrawals.FACT),
                      8));
      for (var fact : facts) {
        assertEquals("2", fact.get("record_version"));
        assertDoesNotThrow(() -> Withdrawals.validateFactV2(fact.get("payload")));
        assertThrows(
            IllegalArgumentException.class, () -> Withdrawals.validateFact(fact.get("payload")));
      }
      store.transaction(
          tx -> tx.execute("UPDATE terminal_settings SET terminal_kind='ATM'", List.of()));
      assertThrows(
          IllegalArgumentException.class,
          () -> withdrawalPreview(store, CHF, new Money(CHF, 5), Map.of("economy:chf_coin_5", 1)));
    } finally {
      store.close();
    }
    var reopened = open();
    try {
      assertEquals(original, withdrawalEngine(reopened, Withdrawals.Action.COMMIT).submit(commit));
      assertEquals(895, balance(reopened, CHF));
      assertEquals(
          3,
          reopened
              .transaction(tx -> tx.query("SELECT lot_id FROM cash_issuance", List.of(), 8))
              .size());
    } finally {
      reopened.close();
    }
  }

  @Test
  void explicitMarkerCannotBeRemovedOrAddedToLegacySettlement() throws Exception {
    var store = open();
    try {
      seed(store, true);
      var chf = depositPreview(store, CHF, List.of(slot(store, 0, "economy:chf_coin_5", true)));
      var missing = new TreeMap<>(Deposits.reserveRequest(chf).fields());
      missing.remove("settlement");
      assertEquals(
          Status.DURABLY_REJECTED,
          depositEngine(store, Deposits.Action.RESERVE)
              .submit(intent(Deposits.Action.RESERVE.kind(), new CanonicalRequest(1, missing)))
              .status());
      var legacy =
          depositPreview(
              store,
              CAD,
              List.of(
                  new Deposits.Slot(
                      0,
                      new CashIssuance.Claim(
                          store.snapshot().worldId(), UUID.randomUUID(), "economy:cad_coin_25", 4),
                      "legacy",
                      true)));
      assertFalse(Deposits.reserveRequest(legacy).fields().containsKey("settlement"));
      var injected = new TreeMap<>(Deposits.reserveRequest(legacy).fields());
      injected.put("settlement", "CHF_MINOR_V2");
      assertEquals(
          Status.DURABLY_REJECTED,
          depositEngine(store, Deposits.Action.RESERVE)
              .submit(intent(Deposits.Action.RESERVE.kind(), new CanonicalRequest(1, injected)))
              .status());
      var oldWithdrawal = withdrawalPreview(store, CAD, new Money(CAD, 25), null);
      assertEquals(new Money(CAD, 100), oldWithdrawal.debit());
      assertFalse(Withdrawals.reserveRequest(oldWithdrawal).fields().containsKey("settlement"));
      var reserve =
          intent(Withdrawals.Action.RESERVE.kind(), Withdrawals.reserveRequest(oldWithdrawal));
      assertEquals(
          Status.COMMITTED,
          withdrawalEngine(store, Withdrawals.Action.RESERVE).submit(reserve).status());
      var legacyFact =
          store
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT payload FROM journal_records WHERE record_type=?",
                          List.of(Withdrawals.FACT),
                          1))
              .getFirst()
              .get("payload");
      assertDoesNotThrow(() -> Withdrawals.validateFact(legacyFact));
      assertThrows(IllegalArgumentException.class, () -> Withdrawals.validateFactV2(legacyFact));
    } finally {
      store.close();
    }
  }

  @Test
  void missingStaleForeignCounterfeitAndExhaustedCashRejectBeforeReservation() throws Exception {
    var store = open();
    try {
      seed(store, false);
      var cash = slot(store, 0, "economy:chf_coin_5", true);
      rates = new CurrencyConversion.Rates(CAD, Map.of(CAD, BigDecimal.ONE));
      var missing =
          assertThrows(
              CurrencyConversion.Rejected.class, () -> depositPreview(store, CAD, List.of(cash)));
      assertEquals(CurrencyConversion.Failure.MISSING_RATE, missing.failure());
      rates =
          new CurrencyConversion.Rates(
              CAD, Map.of(CAD, BigDecimal.ONE, CHF, new BigDecimal("1.6")));
      fxRevision = "fx-2";
      assertEquals(
          "DEPOSIT_FX_STALE",
          assertThrows(
                  IllegalArgumentException.class, () -> depositPreview(store, CAD, List.of(cash)))
              .getMessage());
      fxRevision = "fx-1";
      var foreign =
          new Deposits.Slot(
              0,
              new CashIssuance.Claim(
                  new WorldId(UUID.randomUUID()), marked, "economy:chf_coin_5", 1),
              "foreign");
      assertEquals(
          "DEPOSIT_WORLD",
          assertThrows(
                  IllegalArgumentException.class,
                  () -> depositPreview(store, CHF, List.of(foreign)))
              .getMessage());
      var counterfeit =
          new Deposits.Slot(
              0,
              new CashIssuance.Claim(
                  store.snapshot().worldId(), UUID.randomUUID(), "economy:chf_coin_5", 1),
              "unknown-lot");
      assertEquals(
          "DEPOSIT_UNRECOGNIZED",
          assertThrows(
                  IllegalArgumentException.class,
                  () -> depositPreview(store, CHF, List.of(counterfeit)))
              .getMessage());
      store.transaction(
          tx ->
              tx.execute(
                  "UPDATE cash_issuance SET remaining=0 WHERE lot_id=?",
                  List.of(marked.toString())));
      assertEquals(
          "DEPOSIT_CASH_RESERVED",
          assertThrows(
                  IllegalArgumentException.class,
                  () ->
                      depositPreview(
                          store, CHF, List.of(slot(store, 0, "economy:chf_coin_5", false))))
              .getMessage());
      assertEquals(0, balance(store, CAD));
      assertEquals(0, balance(store, CHF));
      assertTrue(
          store.transaction(tx -> tx.query("SELECT id FROM deposits", List.of(), 1)).isEmpty());
    } finally {
      store.close();
    }
  }
}
