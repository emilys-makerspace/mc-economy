package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD;
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

class WithdrawalsTest {
  @TempDir Path world;
  private final PlayerId owner = new PlayerId(UUID.randomUUID());
  private final UUID account = UUID.randomUUID();
  private final UUID generation = UUID.randomUUID();
  private final TerminalPolicy.Anchor anchor =
      new TerminalPolicy.Anchor("minecraft:overworld", 3, 64, 4);
  private boolean fits = true;
  private boolean live = true;
  private String fxRevision = "fx-1";
  private final Map<String, WithdrawalPlanner.Denomination> catalog =
      Map.of(
          "economy:cad_bill_20",
              new WithdrawalPlanner.Denomination("economy:cad_bill_20", CAD, 2000, true),
          "economy:cad_bill_50",
              new WithdrawalPlanner.Denomination("economy:cad_bill_50", CAD, 5000, true),
          "economy:cad_coin_25",
              new WithdrawalPlanner.Denomination("economy:cad_coin_25", CAD, 25, false));

  @Test
  void fullFitRequiredBeforeIntentAndBeforeDebitThenReplaySettlesOnce() throws Exception {
    var store = open();
    try {
      seed(store, TerminalPolicy.Kind.ATM);
      var preview = preview(store, 6000, null);
      var reserve = intent(Withdrawals.Action.RESERVE, Withdrawals.reserveRequest(preview));
      fits = false;
      assertEquals(
          Status.NOT_ACCEPTED,
          engine(store, Withdrawals.Action.RESERVE, WithdrawalDelivery::prepare)
              .submit(reserve)
              .status());
      assertEquals(10000L, balance(store));
      assertEquals("0", count(store, "cash_issuance"));
      fits = true;
      assertEquals(
          Status.COMMITTED,
          engine(store, Withdrawals.Action.RESERVE, WithdrawalDelivery::prepare)
              .submit(reserve)
              .status());
      var commit = commit(reserve.operationId().value());
      fits = false;
      assertEquals(
          "WITHDRAWAL_CAPACITY",
          engine(store, Withdrawals.Action.COMMIT, WithdrawalDelivery::prepare)
              .submit(commit)
              .result()
              .code());
      assertEquals(10000L, balance(store));
      assertEquals("0", count(store, "cash_issuance"));
      fits = true;
      var original =
          engine(store, Withdrawals.Action.COMMIT, WithdrawalDelivery::prepare).submit(commit);
      assertEquals(Status.COMMITTED, original.status());
      assertEquals(
          original,
          engine(store, Withdrawals.Action.COMMIT, WithdrawalDelivery::prepare).submit(commit));
      assertEquals(4000L, balance(store));
      var delivery =
          store
              .transaction(
                  tx -> Custody.find(tx::query, owner, reserve.operationId().value(), false))
              .orElseThrow();
      assertEquals(Custody.State.COMMITTED, delivery.state());
      var claims = WithdrawalDelivery.claims(delivery);
      assertEquals(3, claims.getFirst().count());
      long protectedCount =
          store.transaction(tx -> CashIssuance.reserved(tx::query, claims.getFirst().lot(), null));
      assertEquals(3L, protectedCount);
      var claimAction = Custody.Action.CLAIM;
      var claimHandler =
          Custody.registration(
              claimAction,
              (a, i, current, reads) -> true,
              (current, evidence, reads) -> evidence.kind() == Custody.EvidenceKind.UNDELIVERED,
              null);
      var claimIntent =
          new Intent(
              new OperationId(UUID.randomUUID()),
              new RequestId(UUID.randomUUID()),
              owner,
              claimAction.kind(),
              Custody.request(
                  delivery.id(),
                  null,
                  new Custody.Evidence(
                      Custody.EvidenceKind.UNDELIVERED,
                      "capacity lost after commit",
                      delivery.terms().destination(),
                      "verified no physical insertion"),
                  "whole batch undelivered"),
              null,
              null);
      var claimEngine =
          new JournalEngine(store, (actor, kind) -> true, Map.of(claimAction.kind(), claimHandler));
      var claimOutcome = claimEngine.submit(claimIntent);
      assertEquals(Status.COMMITTED, claimOutcome.status());
      assertEquals(claimOutcome, claimEngine.submit(claimIntent));
      assertEquals(
          1, store.transaction(tx -> Custody.claims(tx::query, owner, null, 63)).cases().size());
      assertEquals(4000L, balance(store));
      assertEquals("1", count(store, "cash_issuance"));
      assertThrows(IllegalArgumentException.class, () -> preview(store, 3000, null));
      assertThrows(
          IllegalArgumentException.class,
          () -> preview(store, 6000, Map.of("economy:cad_coin_25", 240)));
    } finally {
      store.close();
    }
  }

  @Test
  void tellerCoinsAreAutomaticAndDebitUsesCeilingBankQuantum() throws Exception {
    var store = open();
    try {
      seed(store, TerminalPolicy.Kind.TELLER);
      var preview = preview(store, 75, Map.of());
      assertEquals(Map.of("economy:cad_coin_25", 3), preview.plan().coins());
      assertEquals(100L, preview.debit().minorUnits());
      var reserve = intent(Withdrawals.Action.RESERVE, Withdrawals.reserveRequest(preview));
      engine(store, Withdrawals.Action.RESERVE, WithdrawalDelivery::prepare).submit(reserve);
      assertEquals(
          Status.COMMITTED,
          engine(store, Withdrawals.Action.COMMIT, WithdrawalDelivery::prepare)
              .submit(commit(reserve.operationId().value()))
              .status());
      assertEquals(9900L, balance(store));
    } finally {
      store.close();
    }
  }

  @Test
  void legacyDisabledTellerDispensesBillsAndCoinsWithoutRewritingItsSavedPolicy() throws Exception {
    var store = open();
    try {
      seed(store, TerminalPolicy.Kind.TELLER);
      var preview = preview(store, 2075, Map.of("economy:cad_bill_20", 1));
      assertEquals(Map.of("economy:cad_bill_20", 1), preview.plan().bills());
      assertEquals(Map.of("economy:cad_coin_25", 3), preview.plan().coins());
      var reserve = intent(Withdrawals.Action.RESERVE, Withdrawals.reserveRequest(preview));
      assertEquals(
          Status.COMMITTED,
          engine(store, Withdrawals.Action.RESERVE, WithdrawalDelivery::prepare)
              .submit(reserve)
              .status());
      // Frozen pre-A6 pending reservation: an unbound disabled legacy teller keeps old commit
      // semantics.
      store.transaction(
          tx -> {
            tx.execute(
                "UPDATE terminal_settings SET mode='DISABLED',currencies='',denominations=''",
                List.of());
            tx.execute(
                "UPDATE terminal_bank_bindings SET bank_key='',catalog_revision=0,native_currency='',migration_state='UNBOUND_BANK'",
                List.of());
            return null;
          });
      assertEquals(
          "UNBOUND_BANK",
          assertThrows(
                  IllegalArgumentException.class,
                  () -> preview(store, 2075, Map.of("economy:cad_bill_20", 1)))
              .getMessage());
      assertEquals(
          Status.COMMITTED,
          engine(store, Withdrawals.Action.COMMIT, WithdrawalDelivery::prepare)
              .submit(commit(reserve.operationId().value()))
              .status());
      assertEquals(7900, balance(store));
      assertEquals(
          "DISABLED",
          store
              .transaction(tx -> tx.query("SELECT mode FROM terminal_settings", List.of(), 1))
              .getFirst()
              .get("mode"));
    } finally {
      store.close();
    }
  }

  @Test
  void stalePublicationAndAuthorityRejectBeforeIssuance() throws Exception {
    var store = open();
    try {
      seed(store, TerminalPolicy.Kind.ATM);
      var reserve =
          intent(
              Withdrawals.Action.RESERVE, Withdrawals.reserveRequest(preview(store, 7000, null)));
      engine(store, Withdrawals.Action.RESERVE, WithdrawalDelivery::prepare).submit(reserve);
      live = false;
      assertEquals(
          Status.NOT_ACCEPTED,
          engine(store, Withdrawals.Action.COMMIT, WithdrawalDelivery::prepare)
              .submit(commit(reserve.operationId().value()))
              .status());
      live = true;
      fxRevision = "fx-2";
      assertEquals(
          Status.DURABLY_REJECTED,
          engine(store, Withdrawals.Action.COMMIT, WithdrawalDelivery::prepare)
              .submit(commit(reserve.operationId().value()))
              .status());
      assertEquals(10000L, balance(store));
      assertEquals("0", count(store, "cash_issuance"));
    } finally {
      store.close();
    }
  }

  @Test
  void deliveryFailureRollsBackDebitIssuanceAndCustodyTogether() throws Exception {
    var store = open();
    try {
      seed(store, TerminalPolicy.Kind.ATM);
      var reserve =
          intent(
              Withdrawals.Action.RESERVE, Withdrawals.reserveRequest(preview(store, 6000, null)));
      engine(store, Withdrawals.Action.RESERVE, WithdrawalDelivery::prepare).submit(reserve);
      var commit = commit(reserve.operationId().value());
      Withdrawals.Delivery failure =
          (intent, current, lots, reads) -> {
            var base = WithdrawalDelivery.prepare(intent, current, lots, reads);
            return new JournalEngine.Commit(
                base.records(),
                base.result(),
                tx -> {
                  base.projections().apply(tx);
                  throw new IllegalStateException("injected delivery-intent failure");
                });
          };
      assertThrows(
          IllegalStateException.class,
          () -> engine(store, Withdrawals.Action.COMMIT, failure).submit(commit));
      assertEquals(10000L, balance(store));
      assertEquals("0", count(store, "cash_issuance"));
      assertEquals("0", count(store, "custody_cases"));
      assertTrue(engine(store, Withdrawals.Action.COMMIT, failure).lookup(commit).isEmpty());
      assertEquals(
          Status.COMMITTED,
          engine(store, Withdrawals.Action.COMMIT, WithdrawalDelivery::prepare)
              .submit(commit)
              .status());
    } finally {
      store.close();
    }
  }

  @Test
  void committedOutcomeSurvivesRestartWithoutSpeculativeRedelivery() throws Exception {
    var store = open();
    Intent commit;
    Outcome original;
    UUID reservation;
    try {
      seed(store, TerminalPolicy.Kind.ATM);
      var reserve =
          intent(
              Withdrawals.Action.RESERVE, Withdrawals.reserveRequest(preview(store, 6000, null)));
      reservation = reserve.operationId().value();
      engine(store, Withdrawals.Action.RESERVE, WithdrawalDelivery::prepare).submit(reserve);
      commit = commit(reservation);
      original =
          engine(store, Withdrawals.Action.COMMIT, WithdrawalDelivery::prepare).submit(commit);
    } finally {
      store.close();
    }
    var reopened = open();
    try {
      assertTrue(Withdrawals.reconcileBatch(reopened));
      assertTrue(Custody.reconcileBatch(reopened));
      assertTrue(CashIssuance.reconcileBatch(reopened));
      assertEquals(
          original,
          engine(reopened, Withdrawals.Action.COMMIT, WithdrawalDelivery::prepare).submit(commit));
      assertEquals(4000L, balance(reopened));
      var delivery =
          reopened
              .transaction(tx -> Custody.find(tx::query, owner, reservation, false))
              .orElseThrow();
      assertEquals(Custody.State.QUARANTINED, delivery.state());
      assertTrue(
          reopened.transaction(tx -> Custody.claims(tx::query, owner, null, 63)).cases().isEmpty());
      assertEquals("1", count(reopened, "cash_issuance"));
    } finally {
      reopened.close();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"account", "terminal"})
  void changedAccountOrTerminalRejectsFrozenPlan(String changed) throws Exception {
    var store = open();
    try {
      seed(store, TerminalPolicy.Kind.ATM);
      var reserve =
          intent(
              Withdrawals.Action.RESERVE, Withdrawals.reserveRequest(preview(store, 6000, null)));
      engine(store, Withdrawals.Action.RESERVE, WithdrawalDelivery::prepare).submit(reserve);
      if (changed.equals("account"))
        store.transaction(
            tx ->
                tx.execute(
                    "UPDATE account_catalog SET revision=2 WHERE account_id=?",
                    List.of(account.toString())));
      else
        store.transaction(tx -> tx.execute("UPDATE terminal_settings SET revision=4", List.of()));
      assertEquals(
          Status.DURABLY_REJECTED,
          engine(store, Withdrawals.Action.COMMIT, WithdrawalDelivery::prepare)
              .submit(commit(reserve.operationId().value()))
              .status());
      assertEquals(10000L, balance(store));
      assertEquals("0", count(store, "cash_issuance"));
    } finally {
      store.close();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"economy:bank/orchard_direct", "economy:unassigned"})
  void foreignOrUnassignedAtmWithdrawalChargesOperatorOnce(String issuer) throws Exception {
    var store = open();
    try {
      seed(store, TerminalPolicy.Kind.ATM);
      // Include a legacy account with no financial institution/card affiliation.
      store.transaction(
          tx ->
              tx.execute(
                  "UPDATE bank_account_providers SET bank_key=?,revision=2 WHERE account_id=?",
                  List.of(
                      issuer.equals("economy:unassigned")
                          ? ca.emilybelnavis.mcmods.economy.institution.BankProviders
                              .UNASSIGNED_PROVIDER
                          : issuer,
                      account.toString())));
      var preview = preview(store, 6000, null);
      assertEquals(6250, preview.debit().minorUnits());
      assertEquals(250, Withdrawals.atmFee(preview).minorUnits());
      var reserve = intent(Withdrawals.Action.RESERVE, Withdrawals.reserveRequest(preview));
      assertEquals(
          Status.COMMITTED,
          engine(store, Withdrawals.Action.RESERVE, WithdrawalDelivery::prepare)
              .submit(reserve)
              .status());
      var commit = commit(reserve.operationId().value());
      var outcome =
          engine(store, Withdrawals.Action.COMMIT, WithdrawalDelivery::prepare).submit(commit);
      assertEquals(Status.COMMITTED, outcome.status());
      assertEquals(
          outcome,
          engine(store, Withdrawals.Action.COMMIT, WithdrawalDelivery::prepare).submit(commit));
      assertEquals(3750, balance(store));
      var bank =
          new ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.InstitutionId(
              "economy:bank/crownstone");
      var cash =
          ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts.account(
              store.snapshot().worldId(),
              bank,
              ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts.Role.CASH,
              CAD);
      assertEquals(
          250, store.transaction(tx -> Settlement.posted(tx::query, cash.id(), CAD)).longValue());
      assertEquals("1", count(store, "cash_issuance"));
    } finally {
      store.close();
    }
  }

  @Test
  void atmFeeCanOverdrawAndProviderChangeInvalidatesReview() throws Exception {
    var store = open();
    try {
      seed(store, TerminalPolicy.Kind.ATM);
      store.transaction(
          tx ->
              tx.execute(
                  "DELETE FROM bank_account_providers WHERE account_id=?",
                  List.of(account.toString())));
      // The withdrawal principal exhausts cash plus the CAD 5 overdraft tolerance.
      store.transaction(
          tx -> {
            ca.emilybelnavis.mcmods.economy.storage.LedgerProjection.write(tx, account, CAD, 9500);
            return null;
          });
      var reserve =
          intent(
              Withdrawals.Action.RESERVE, Withdrawals.reserveRequest(preview(store, 10000, null)));
      assertEquals(
          Status.COMMITTED,
          engine(store, Withdrawals.Action.RESERVE, WithdrawalDelivery::prepare)
              .submit(reserve)
              .status());
      assertEquals(
          "WITHDRAWAL_COMMIT",
          engine(store, Withdrawals.Action.COMMIT, WithdrawalDelivery::prepare)
              .submit(commit(reserve.operationId().value()))
              .result()
              .code());
      assertEquals(-750, balance(store));
      assertEquals("1", count(store, "cash_issuance"));
      store.transaction(
          tx -> {
            ca.emilybelnavis.mcmods.economy.storage.LedgerProjection.write(tx, account, CAD, 10000);
            return null;
          });
      var secondReserve =
          intent(
              Withdrawals.Action.RESERVE, Withdrawals.reserveRequest(preview(store, 10000, null)));
      assertEquals(
          Status.COMMITTED,
          engine(store, Withdrawals.Action.RESERVE, WithdrawalDelivery::prepare)
              .submit(secondReserve)
              .status());
      store.transaction(
          tx ->
              tx.execute(
                  "INSERT INTO bank_account_providers VALUES(?, 'economy:bank/crownstone',0,1,0)",
                  List.of(account.toString())));
      assertEquals(
          "WITHDRAWAL_STALE",
          engine(store, Withdrawals.Action.COMMIT, WithdrawalDelivery::prepare)
              .submit(commit(secondReserve.operationId().value()))
              .result()
              .code());
      assertEquals(10000, balance(store));
    } finally {
      store.close();
    }
  }

  @Test
  void foreignAccountFeeUsesPublishedCadRatioAndFreezesQuarterUnitRounding() throws Exception {
    var store = open();
    try {
      seed(store, TerminalPolicy.Kind.ATM);
      // A separately created legacy/non-bank EUR account can review foreign cash without an
      // institution FX quote.
      // Preview and reservation do not debit funds; account currency remains immutable.
      var euroAccount = UUID.randomUUID();
      store.transaction(
          tx ->
              tx.execute(
                  "INSERT INTO account_catalog VALUES(?,?,'BANK','EUR','OPEN',1,0)",
                  List.of(euroAccount.toString(), owner.value().toString())));
      var rates =
          new CurrencyConversion.Rates(
              EconomyCurrency.USD,
              Map.of(
                  EconomyCurrency.USD,
                  BigDecimal.ONE,
                  CAD,
                  new BigDecimal("0.75"),
                  EconomyCurrency.EUR,
                  new BigDecimal("1.125")));
      var binding =
          new Withdrawals.Binding(
              euroAccount, 1, EconomyCurrency.EUR, anchor, generation, 3, fxRevision);
      Withdrawals.FxReader publication = reads -> new Withdrawals.FxSnapshot(fxRevision, rates);
      var preview =
          store.transaction(
              tx ->
                  Withdrawals.preview(
                      tx::query,
                      store.snapshot().worldId(),
                      owner,
                      binding,
                      new Money(CAD, 6000),
                      null,
                      catalog,
                      publication));
      // CAD2.50 / 1.5 EUR rounds from EUR1.666... to the nearest quarter: EUR1.75.
      assertEquals(175, Withdrawals.atmFee(preview).minorUnits());
      assertEquals(4175, preview.debit().minorUnits());
      assertTrue(preview.atmFee().endsWith("|175|CAD_FEE_V3|0.75|1.125"));
      assertEquals(
          "ATM_FEE_MINOR_V5", Withdrawals.reserveRequest(preview).fields().get("settlement"));
      var reserve = intent(Withdrawals.Action.RESERVE, Withdrawals.reserveRequest(preview));
      var action = Withdrawals.Action.RESERVE;
      var engine =
          new JournalEngine(
              store,
              (a, k) -> true,
              Map.of(
                  action.kind(),
                  Withdrawals.registration(
                      action,
                      store.snapshot().worldId(),
                      catalog,
                      publication,
                      (a, i, c, r) -> true,
                      (a, i, c, r) -> true,
                      WithdrawalDelivery::prepare)));
      assertEquals(Status.COMMITTED, engine.submit(reserve).status());
      var frozen =
          store
              .transaction(tx -> Withdrawals.find(tx::query, owner, reserve.operationId().value()))
              .orElseThrow()
              .preview();
      assertEquals(preview.atmFee(), frozen.atmFee());
      // Old V4 local-currency fee payloads remain readable when reloading retained facts.
      var legacy =
          new Withdrawals.Preview(
              binding,
              preview.cash(),
              preview.edited(),
              preview.billEdits(),
              preview.plan(),
              new Money(EconomyCurrency.EUR, 4250),
              null,
              "economy:bank/crownstone||0|250");
      assertEquals(
          "ATM_FEE_MINOR_V4", Withdrawals.reserveRequest(legacy).fields().get("settlement"));
    } finally {
      store.close();
    }
  }

  @Test
  void ownBankAtmAndForeignBankTellerDoNotChargeAtmFees() throws Exception {
    var store = open();
    try {
      seed(store, TerminalPolicy.Kind.ATM);
      assertNull(preview(store, 6000, null).atmFee());
      assertEquals(6000, preview(store, 6000, null).debit().minorUnits());
      store.transaction(
          tx -> {
            tx.execute("UPDATE terminal_settings SET terminal_kind='TELLER'", List.of());
            tx.execute(
                "UPDATE bank_account_providers SET bank_key='economy:bank/orchard_direct',revision=2 WHERE account_id=?",
                List.of(account.toString()));
            return null;
          });
      assertNull(preview(store, 6000, null).atmFee());
      assertEquals(6000, preview(store, 6000, null).debit().minorUnits());
    } finally {
      store.close();
    }
  }

  private SqliteStorage open() throws Exception {
    var store = new SqliteStorage();
    store.initialize(world.toRealPath());
    return store;
  }

  private Withdrawals.Preview preview(
      SqliteStorage store, long amount, Map<String, Integer> edits) {
    var binding = new Withdrawals.Binding(account, 1, CAD, anchor, generation, 3, fxRevision);
    return store.transaction(
        tx ->
            Withdrawals.preview(
                tx::query,
                store.snapshot().worldId(),
                owner,
                binding,
                new Money(CAD, amount),
                edits,
                catalog,
                this::fx));
  }

  private Withdrawals.FxSnapshot fx(JournalEngine.ReadView reads) {
    return new Withdrawals.FxSnapshot(
        fxRevision, new CurrencyConversion.Rates(CAD, Map.of(CAD, BigDecimal.ONE)));
  }

  private JournalEngine engine(
      SqliteStorage store, Withdrawals.Action action, Withdrawals.Delivery delivery) {
    return new JournalEngine(
        store,
        (actor, kind) -> actor.equals(owner),
        Map.of(
            action.kind(),
            Withdrawals.registration(
                action,
                store.snapshot().worldId(),
                catalog,
                this::fx,
                (a, intent, current, reads) -> live,
                (a, intent, current, reads) -> fits,
                delivery)));
  }

  private Intent intent(Withdrawals.Action action, CanonicalRequest request) {
    return new Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        owner,
        action.kind(),
        request,
        null,
        null);
  }

  private Intent commit(UUID id) {
    return intent(
        Withdrawals.Action.COMMIT, Withdrawals.request(id, "admitted full fit", "withdraw"));
  }

  private long balance(SqliteStorage store) {
    return store.transaction(tx -> Holds.available(tx::query, account, CAD));
  }

  private String count(SqliteStorage store, String table) {
    return store.transaction(
        tx ->
            tx.query("SELECT count(*) AS total FROM " + table, List.of(), 1)
                .getFirst()
                .get("total"));
  }

  private void seed(SqliteStorage store, TerminalPolicy.Kind kind) {
    store.transaction(
        tx ->
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,0)",
                List.of(account.toString(), owner.value().toString())));
    store.transaction(
        tx -> {
          tx.execute(
              "INSERT INTO bank_account_providers VALUES(?, 'economy:bank/crownstone',0,1,0)",
              List.of(account.toString()));
          var bank =
              new ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.InstitutionId(
                  "economy:bank/crownstone");
          tx.execute(
              "INSERT INTO institution_parties VALUES(?,?,?,?,?,'HYBRID',1)",
              List.of(
                  store.snapshot().worldId().value().toString(),
                  bank.key(),
                  new ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.SystemActor(
                          store.snapshot().worldId(), bank)
                      .actorId()
                      .value()
                      .toString(),
                  "Crownstone Bank",
                  "CAD"));
          return null;
        });
    var funding = new Journal.Kind("economy:withdrawal_fixture", "economy:fund");
    var engine =
        new JournalEngine(
            store,
            (a, k) -> true,
            Map.of(
                funding,
                Settlement.registration((intent, legs, reads) -> PostingBatch.Authority.ISSUE)));
    assertEquals(
        Status.COMMITTED,
        engine
            .submit(
                new Intent(
                    new OperationId(UUID.randomUUID()),
                    new RequestId(UUID.randomUUID()),
                    owner,
                    funding,
                    Settlement.request(
                        List.of(
                            new PostingBatch.Leg(
                                account, PostingBatch.Role.ACCOUNT, CAD, new Money(CAD, 10000)),
                            new PostingBatch.Leg(
                                Settlement.issuanceAccount(CAD),
                                PostingBatch.Role.ISSUANCE,
                                CAD,
                                new Money(CAD, -10000)))),
                    null,
                    null))
            .status());
    var terminalCatalog = new HashMap<String, TerminalPolicy.Denomination>();
    catalog.values().stream()
        .filter(value -> kind != TerminalPolicy.Kind.ATM || value.bill())
        .forEach(
            value ->
                terminalCatalog.put(
                    value.id(), new TerminalPolicy.Denomination(value.currency(), value.bill())));
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
                    TerminalPolicy.placeRequest(anchor, generation, owner, kind, null, 0),
                    null,
                    null))
            .status());
    var bank =
        new ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.InstitutionId(
            "economy:bank/crownstone");
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
                    TerminalBanking.bindRequest(anchor, generation, 1, bank, true),
                    null,
                    null))
            .status());
    var current =
        store
            .transaction(
                tx ->
                    TerminalPolicy.find(
                        tx::query, store.snapshot().worldId(), anchor, owner, false))
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
                    TerminalPolicy.configureRequest(anchor, generation, 2, nativeSettings),
                    null,
                    null))
            .status());
  }
}
