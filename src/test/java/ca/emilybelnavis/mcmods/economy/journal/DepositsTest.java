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

class DepositsTest {
  @TempDir Path world;
  private final PlayerId owner = new PlayerId(UUID.randomUUID());
  private final UUID account = UUID.randomUUID();
  private final UUID generation = UUID.randomUUID();
  private final TerminalPolicy.Anchor anchor =
      new TerminalPolicy.Anchor("minecraft:overworld", 1, 64, 2);
  private final UUID cad = UUID.randomUUID();
  private final UUID eur = UUID.randomUUID();
  private long accountRevision = 1;
  private String fxRevision = "fx-1";
  private boolean proof = true;
  private boolean live = true;
  private final Map<String, Deposits.Denomination> catalog =
      Map.of(
          "economy:cad_coin_25", new Deposits.Denomination(CAD, 25, false),
          "economy:eur_coin_1", new Deposits.Denomination(EUR, 1, false),
          "economy:cad_bill_5", new Deposits.Denomination(CAD, 500, true),
          "economy:cad_bill_20", new Deposits.Denomination(CAD, 2000, true),
          "economy:cad_bill_100_horizontal", new Deposits.Denomination(CAD, 10000, true));

  @Test
  void twentyFourSlotsRoundByCurrencyAndReplayOnlyOneSettlement() throws Exception {
    var store = open();
    try {
      seed(store);
      var oversized = new ArrayList<Deposits.Slot>();
      for (int i = 0; i < 16; i++)
        oversized.add(new Deposits.Slot(i, slot(store, i, cad, 1).claim(), "z".repeat(2048)));
      assertThrows(IllegalArgumentException.class, () -> preview(store, oversized));
      var slots = new ArrayList<Deposits.Slot>();
      for (int i = 0; i < 24; i++) slots.add(slot(store, i, cad, 1));
      var preview = preview(store, slots);
      assertEquals(24, preview.groups().getFirst().count());
      assertEquals(600L, preview.credit().minorUnits());
      var id = reserve(store, preview);
      assertEquals(
          Status.COMMITTED, change(store, Deposits.Action.SAVED, id, "saved", "confirm").status());
      var commit = intent(Deposits.Action.COMMIT, Deposits.request(id, "saved", "confirm"));
      var outcome = engine(store, Deposits.Action.COMMIT).submit(commit);
      assertEquals(Status.COMMITTED, outcome.status());
      assertEquals(outcome, engine(store, Deposits.Action.COMMIT).submit(commit));
      assertEquals(600L, balance(store));
      assertEquals(40, lot(store, cad).remaining());
      assertTrue(store.transaction(tx -> DepositReturns.list(tx::query, owner, id)).isEmpty());
    } finally {
      store.close();
    }
  }

  @Test
  void allSkippedAndMixedGroupsPreserveExactOriginalReturns() throws Exception {
    var store = open();
    try {
      seed(store);
      var skipped = preview(store, List.of(slot(store, 0, eur, 1)));
      assertEquals(0L, skipped.credit().minorUnits());
      var skippedId = reserve(store, skipped);
      change(store, Deposits.Action.SAVED, skippedId, "saved", "confirm");
      assertEquals(
          Status.COMMITTED,
          change(store, Deposits.Action.COMMIT, skippedId, "saved", "confirm").status());
      assertEquals(64, lot(store, eur).remaining());
      assertEquals(
          1, store.transaction(tx -> DepositReturns.list(tx::query, owner, skippedId)).size());
      assertThrows(
          IllegalArgumentException.class,
          () -> preview(store, List.of(slot(store, 0, cad, 4), slot(store, 1, eur, 1))));
      var mixed = preview(store, List.of(slot(store, 0, cad, 4)));
      assertEquals(100L, mixed.credit().minorUnits());
      assertEquals(1, skipped.returned().size());
      var id = reserve(store, mixed);
      change(store, Deposits.Action.SAVED, id, "saved", "confirm");
      assertEquals(
          Status.COMMITTED, change(store, Deposits.Action.COMMIT, id, "saved", "confirm").status());
      var returns = store.transaction(tx -> DepositReturns.list(tx::query, owner, skippedId));
      assertEquals("original-components-" + eur, returns.getFirst().components());
      assertEquals(1, returns.getFirst().count());
      assertEquals(60, lot(store, cad).remaining());
      assertEquals(64, lot(store, eur).remaining());
      assertEquals(100L, balance(store));
    } finally {
      store.close();
    }
  }

  @Test
  void staleFxAndLiveAuthorityDenyCommitButOriginalsCanBeCancelled() throws Exception {
    var store = open();
    try {
      seed(store);
      var id = reserve(store, preview(store, List.of(slot(store, 0, cad, 4))));
      change(store, Deposits.Action.SAVED, id, "saved", "confirm");
      live = false;
      assertEquals(
          Status.NOT_ACCEPTED,
          change(store, Deposits.Action.COMMIT, id, "saved", "confirm").status());
      live = true;
      fxRevision = "fx-2";
      assertEquals(
          Status.DURABLY_REJECTED,
          change(store, Deposits.Action.COMMIT, id, "saved", "confirm").status());
      assertEquals(
          Status.COMMITTED,
          change(store, Deposits.Action.CANCEL, id, "held-originals", "page-change").status());
      assertEquals(0L, balance(store));
      assertEquals(64, lot(store, cad).remaining());
      assertEquals(
          4, store.transaction(tx -> DepositReturns.list(tx::query, owner, id)).getFirst().count());
    } finally {
      store.close();
    }
  }

  @Test
  void partialCapacityReturnsOnceAndClaimsOnlyProvenUndeliveredRemainder() throws Exception {
    var store = open();
    try {
      seed(store);
      var id = reserve(store, preview(store, List.of(slot(store, 0, cad, 4))));
      change(store, Deposits.Action.CANCEL, id, "held-originals", "close");
      var attempt = UUID.randomUUID();
      var begin =
          returnIntent(
              store,
              DepositReturns.Action.BEGIN,
              id,
              1,
              attempt,
              3,
              new DepositReturns.Evidence(
                  DepositReturns.Proof.UNDELIVERED, "exact reserved originals"),
              attempt);
      assertEquals(
          Status.COMMITTED,
          returnEngine(store, DepositReturns.Action.BEGIN).submit(begin).status());
      var complete =
          returnIntent(
              store,
              DepositReturns.Action.COMPLETE,
              id,
              2,
              attempt,
              2,
              new DepositReturns.Evidence(
                  DepositReturns.Proof.SAVED_AND_UNDELIVERED, "two saved, two held"),
              null);
      var result = returnEngine(store, DepositReturns.Action.COMPLETE).submit(complete);
      assertEquals(Status.COMMITTED, result.status());
      assertEquals(result, returnEngine(store, DepositReturns.Action.COMPLETE).submit(complete));
      var returned =
          store.transaction(tx -> DepositReturns.find(tx::query, owner, id, 0)).orElseThrow();
      assertEquals(2, returned.delivered());
      assertEquals(DepositReturns.State.CLAIMED, returned.state());
      var claims = store.transaction(tx -> Custody.claims(tx::query, owner, null, 63));
      assertEquals(1, claims.cases().size());
      assertEquals(
          DepositReturns.claimItems(returned, 2), claims.cases().getFirst().terms().items());
      long reserved = store.transaction(tx -> CashIssuance.reserved(tx::query, cad, null));
      assertEquals(2L, reserved);
      assertEquals(64, lot(store, cad).remaining());
      assertEquals(0L, balance(store));
    } finally {
      store.close();
    }
  }

  @Test
  void restartQuarantinesDisconnectAndPreservesLocksWithoutClaims() throws Exception {
    var store = open();
    UUID id;
    try {
      seed(store);
      id = reserve(store, preview(store, List.of(slot(store, 0, cad, 4))));
      proof = false;
      assertEquals(
          Status.NOT_ACCEPTED,
          change(store, Deposits.Action.CANCEL, id, "unknown", "disconnect").status());
    } finally {
      store.close();
    }
    var reopened = open();
    try {
      assertTrue(Deposits.reconcileBatch(reopened));
      assertTrue(DepositReturns.reconcileBatch(reopened));
      assertEquals(
          Deposits.State.QUARANTINED,
          reopened.transaction(tx -> Deposits.find(tx::query, owner, id)).orElseThrow().state());
      var returned =
          reopened.transaction(tx -> DepositReturns.find(tx::query, owner, id, 0)).orElseThrow();
      assertEquals(DepositReturns.State.QUARANTINED, returned.state());
      assertTrue(
          reopened.transaction(tx -> Custody.claims(tx::query, owner, null, 63)).cases().isEmpty());
      assertEquals(
          Custody.State.QUARANTINED,
          reopened
              .transaction(tx -> Custody.find(tx::query, owner, returned.claimId(), false))
              .orElseThrow()
              .state());
      long reserved = reopened.transaction(tx -> CashIssuance.reserved(tx::query, cad, null));
      assertEquals(4L, reserved);
      assertEquals(0L, balance(reopened));
    } finally {
      reopened.close();
    }
  }

  @Test
  void accountTerminalAndAtmAdmissionRejectStaleOrCoinInputs() throws Exception {
    var store = open();
    try {
      seed(store);
      var id = reserve(store, preview(store, List.of(slot(store, 0, cad, 4))));
      change(store, Deposits.Action.SAVED, id, "saved", "confirm");
      store.transaction(
          tx ->
              tx.execute(
                  "UPDATE account_catalog SET revision=2 WHERE account_id=?",
                  List.of(account.toString())));
      assertEquals(
          Status.DURABLY_REJECTED,
          change(store, Deposits.Action.COMMIT, id, "saved", "confirm").status());
      assertEquals(
          Status.COMMITTED, change(store, Deposits.Action.CANCEL, id, "held", "close").status());
      accountRevision = 2;
      var second = reserve(store, preview(store, List.of(slot(store, 0, cad, 4))));
      change(store, Deposits.Action.SAVED, second, "saved", "confirm");
      store.transaction(tx -> tx.execute("UPDATE terminal_settings SET revision=4", List.of()));
      assertEquals(
          Status.DURABLY_REJECTED,
          change(store, Deposits.Action.COMMIT, second, "saved", "confirm").status());
      assertEquals(
          Status.COMMITTED,
          change(store, Deposits.Action.CANCEL, second, "held", "page-change").status());
      store.transaction(
          tx ->
              tx.execute(
                  """
                    UPDATE terminal_settings SET revision=3,terminal_kind='ATM',currencies='CAD',
                    denominations='economy:cad_bill_5'
                    """,
                  List.of()));
      assertThrows(
          IllegalArgumentException.class, () -> preview(store, List.of(slot(store, 0, cad, 4))));
      assertEquals(0L, balance(store));
      assertEquals(64, lot(store, cad).remaining());
    } finally {
      store.close();
    }
  }

  @Test
  void foreignWorldCashCannotBeReclassifiedAsNewCreativeCash() throws Exception {
    var store = open();
    try {
      seed(store);
      var foreign =
          new Deposits.Slot(
              0,
              new CashIssuance.Claim(new WorldId(UUID.randomUUID()), cad, "economy:cad_coin_25", 4),
              "foreign-marked-stack");
      assertThrows(IllegalArgumentException.class, () -> preview(store, List.of(foreign)));
      assertEquals(0L, balance(store));
      assertEquals(64, lot(store, cad).remaining());
    } finally {
      store.close();
    }
  }

  @Test
  void creativeCashCreditsOnceWithoutAnExistingIssuedLot() throws Exception {
    var store = open();
    try {
      seed(store);
      var unmarked =
          new Deposits.Slot(
              0,
              new CashIssuance.Claim(
                  store.snapshot().worldId(), UUID.randomUUID(), "economy:cad_coin_25", 4),
              "unmarked-creative-stack",
              true);
      var preview = preview(store, List.of(unmarked));
      assertEquals(100L, preview.credit().minorUnits());
      var id = reserve(store, preview);
      assertEquals(
          Status.COMMITTED, change(store, Deposits.Action.SAVED, id, "saved", "confirm").status());
      var commit = intent(Deposits.Action.COMMIT, Deposits.request(id, "saved", "confirm"));
      var outcome = engine(store, Deposits.Action.COMMIT).submit(commit);
      assertEquals(Status.COMMITTED, outcome.status());
      assertEquals(outcome, engine(store, Deposits.Action.COMMIT).submit(commit));
      assertEquals(100L, balance(store));
      assertEquals(64, lot(store, cad).remaining());
      assertTrue(
          store
              .transaction(
                  tx ->
                      CashIssuance.find(
                          tx::query, store.snapshot().worldId(), unmarked.claim().lot()))
              .isEmpty());
      assertTrue(store.transaction(tx -> DepositReturns.list(tx::query, owner, id)).isEmpty());
    } finally {
      store.close();
    }
  }

  @Test
  void freshCreativeTwentyDollarBillPreviewsAndCreditsWithoutIssuanceRecord() throws Exception {
    var store = open();
    try {
      seed(store);
      var creative =
          new Deposits.Slot(
              0,
              new CashIssuance.Claim(
                  store.snapshot().worldId(), UUID.randomUUID(), "economy:cad_bill_20", 1),
              "fresh-creative-cad20",
              true);
      var preview = preview(store, List.of(creative));
      assertEquals(new Money(CAD, 2000), preview.credit());
      assertEquals(List.of(creative), preview.payable());
      assertTrue(preview.returned().isEmpty());
      var id = reserve(store, preview);
      assertEquals(
          Status.COMMITTED, change(store, Deposits.Action.SAVED, id, "saved", "confirm").status());
      var commit = intent(Deposits.Action.COMMIT, Deposits.request(id, "saved", "confirm"));
      var outcome = engine(store, Deposits.Action.COMMIT).submit(commit);
      assertEquals(Status.COMMITTED, outcome.status());
      assertEquals(outcome, engine(store, Deposits.Action.COMMIT).submit(commit));
      assertEquals(2000, balance(store));
      assertEquals(64, lot(store, cad).remaining());
      assertTrue(
          store
              .transaction(
                  tx ->
                      CashIssuance.find(
                          tx::query, store.snapshot().worldId(), creative.claim().lot()))
              .isEmpty());
    } finally {
      store.close();
    }
  }

  @Test
  void creativeHorizontalBillReviewDistinguishesDisabledUnacceptedAndEnabledTerminal()
      throws Exception {
    var store = open();
    try {
      seed(store);
      var creative =
          new Deposits.Slot(
              0,
              new CashIssuance.Claim(
                  store.snapshot().worldId(),
                  UUID.randomUUID(),
                  "economy:cad_bill_100_horizontal",
                  64),
              "fresh-creative-cad100-horizontal",
              true);
      store.transaction(
          tx ->
              tx.execute(
                  "UPDATE terminal_settings SET terminal_kind='ATM',mode='DISABLED',currencies='',denominations=''",
                  List.of()));
      var disabled =
          assertThrows(IllegalArgumentException.class, () -> preview(store, List.of(creative)));
      assertEquals("DEPOSIT_MODE", disabled.getMessage());
      store.transaction(
          tx ->
              tx.execute(
                  "UPDATE terminal_settings SET mode='BOTH',currencies='CAD',denominations='economy:cad_bill_20'",
                  List.of()));
      var unaccepted =
          assertThrows(IllegalArgumentException.class, () -> preview(store, List.of(creative)));
      assertEquals("DEPOSIT_DENOMINATION", unaccepted.getMessage());
      store.transaction(
          tx ->
              tx.execute(
                  "UPDATE terminal_settings SET denominations='economy:cad_bill_100_horizontal'",
                  List.of()));
      var preview = preview(store, List.of(creative));
      assertEquals(new Money(CAD, 640000), preview.credit());
      assertEquals(List.of(creative), preview.payable());
      var id = reserve(store, preview);
      assertEquals(
          Status.COMMITTED, change(store, Deposits.Action.SAVED, id, "saved", "confirm").status());
      assertEquals(
          Status.COMMITTED, change(store, Deposits.Action.COMMIT, id, "saved", "confirm").status());
      assertEquals(640000, balance(store));
      assertEquals(64, lot(store, cad).remaining());
    } finally {
      store.close();
    }
  }

  @Test
  void legacyDisabledTellerAcceptsCreativeBillsAndCoinsWithoutChangingItsSavedPolicy()
      throws Exception {
    var store = open();
    try {
      seed(store);
      store.transaction(
          tx ->
              tx.execute(
                  "UPDATE terminal_settings SET mode='DISABLED',currencies='',denominations=''",
                  List.of()));
      var bill =
          new Deposits.Slot(
              0,
              new CashIssuance.Claim(
                  store.snapshot().worldId(), UUID.randomUUID(), "economy:cad_bill_20", 1),
              "creative-bill",
              true);
      var coin =
          new Deposits.Slot(
              1,
              new CashIssuance.Claim(
                  store.snapshot().worldId(), UUID.randomUUID(), "economy:cad_coin_25", 4),
              "creative-coins",
              true);
      assertEquals(
          "DEPOSIT_MODE",
          assertThrows(IllegalArgumentException.class, () -> preview(store, List.of(bill, coin)))
              .getMessage());
      var preview =
          new Deposits.Preview(
              new Deposits.Binding(
                  account, accountRevision, CAD, anchor, generation, 3, fxRevision),
              List.of(bill, coin),
              List.of(
                  new Deposits.Group(CAD, 5, new Money(CAD, 2100), new Money(CAD, 2100), false)),
              new Money(CAD, 2100),
              List.of(bill, coin),
              List.of());
      assertEquals(new Money(CAD, 2100), preview.credit());
      var id = historicalReserve(store, preview);
      assertEquals(
          Status.COMMITTED, change(store, Deposits.Action.SAVED, id, "saved", "confirm").status());
      assertEquals(
          Status.COMMITTED, change(store, Deposits.Action.COMMIT, id, "saved", "confirm").status());
      assertEquals(2100, balance(store));
      assertEquals(
          "DISABLED",
          store
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT mode FROM terminal_settings WHERE dimension=? AND x=? AND y=? AND z=?",
                          List.of(
                              anchor.dimension(),
                              Integer.toString(anchor.x()),
                              Integer.toString(anchor.y()),
                              Integer.toString(anchor.z())),
                          1))
              .getFirst()
              .get("mode"));
    } finally {
      store.close();
    }
  }

  @Test
  void mixedCreativeAndIssuedCashCreditsBothButRedeemsOnlyIssuedQuantity() throws Exception {
    var store = open();
    try {
      seed(store);
      var marked = slot(store, 0, cad, 4);
      var unmarked =
          new Deposits.Slot(
              1,
              new CashIssuance.Claim(
                  store.snapshot().worldId(), UUID.randomUUID(), "economy:cad_coin_25", 4),
              "unmarked-command-stack",
              true);
      var preview = preview(store, List.of(marked, unmarked));
      assertEquals(200L, preview.credit().minorUnits());
      var id = reserve(store, preview);
      change(store, Deposits.Action.SAVED, id, "saved", "confirm");
      var commit = intent(Deposits.Action.COMMIT, Deposits.request(id, "saved", "confirm"));
      var outcome = engine(store, Deposits.Action.COMMIT).submit(commit);
      assertEquals(Status.COMMITTED, outcome.status());
      assertEquals(outcome, engine(store, Deposits.Action.COMMIT).submit(commit));
      assertEquals(200L, balance(store));
      assertEquals(60, lot(store, cad).remaining());
      assertTrue(
          store
              .transaction(
                  tx ->
                      CashIssuance.find(
                          tx::query, store.snapshot().worldId(), unmarked.claim().lot()))
              .isEmpty());
    } finally {
      store.close();
    }
  }

  @Test
  void returnProjectionFailureRollsBackCreditAndRedemption() throws Exception {
    var store = open();
    try {
      seed(store);
      var cadSlot = slot(store, 0, cad, 4);
      var eurSlot = slot(store, 1, eur, 1);
      var legacy =
          new Deposits.Preview(
              new Deposits.Binding(
                  account, accountRevision, CAD, anchor, generation, 3, fxRevision),
              List.of(cadSlot, eurSlot),
              List.of(
                  new Deposits.Group(CAD, 4, new Money(CAD, 100), new Money(CAD, 100), false),
                  new Deposits.Group(EUR, 1, new Money(EUR, 1), new Money(CAD, 0), true)),
              new Money(CAD, 100),
              List.of(cadSlot),
              List.of(eurSlot));
      var id = historicalReserve(store, legacy);
      change(store, Deposits.Action.SAVED, id, "saved", "confirm");
      store.transaction(
          tx ->
              tx.execute(
                  """
                    INSERT INTO deposit_returns(deposit_id,slot,world_id,lot_id,item_id,count,components,state,revision)
                    VALUES(?,1,?,?,'economy:eur_coin_1',1,'injected','PENDING',1)
                    """,
                  List.of(
                      id.toString(),
                      store.snapshot().worldId().value().toString(),
                      eur.toString())));
      var commit = intent(Deposits.Action.COMMIT, Deposits.request(id, "saved", "confirm"));
      assertThrows(
          RuntimeException.class, () -> engine(store, Deposits.Action.COMMIT).submit(commit));
      store.recover();
      assertEquals(0L, balance(store));
      assertEquals(64, lot(store, cad).remaining());
      assertEquals(
          Deposits.State.SAVED,
          store.transaction(tx -> Deposits.find(tx::query, owner, id)).orElseThrow().state());
      assertTrue(engine(store, Deposits.Action.COMMIT).lookup(commit).isEmpty());
      store.transaction(
          tx ->
              tx.execute("DELETE FROM deposit_returns WHERE deposit_id=?", List.of(id.toString())));
      assertEquals(Status.COMMITTED, engine(store, Deposits.Action.COMMIT).submit(commit).status());
      assertEquals(100L, balance(store));
    } finally {
      store.close();
    }
  }

  @Test
  void compensationRetiresTheProtectedOriginalCashQuantity() throws Exception {
    var store = open();
    try {
      seed(store);
      var id = reserve(store, preview(store, List.of(slot(store, 0, cad, 4))));
      change(store, Deposits.Action.CANCEL, id, "held", "close");
      var claim =
          returnIntent(
              store,
              DepositReturns.Action.CLAIM,
              id,
              1,
              null,
              0,
              new DepositReturns.Evidence(DepositReturns.Proof.UNDELIVERED, "held"),
              null);
      assertEquals(
          Status.COMMITTED,
          returnEngine(store, DepositReturns.Action.CLAIM).submit(claim).status());
      var returned =
          store.transaction(tx -> DepositReturns.find(tx::query, owner, id, 0)).orElseThrow();
      var custody =
          store
              .transaction(tx -> Custody.find(tx::query, owner, returned.claimId(), false))
              .orElseThrow();
      for (var action : List.of(Custody.Action.QUARANTINE, Custody.Action.COMPENSATE)) {
        var evidence =
            action == Custody.Action.QUARANTINE
                ? Custody.Evidence.unknown()
                : new Custody.Evidence(
                    Custody.EvidenceKind.LOST,
                    "confirmed loss",
                    custody.terms().destination(),
                    "loss proof");
        Custody.Financial financial =
            (intent, current, reads) ->
                Settlement.prepare(
                    reads,
                    List.of(
                        new PostingBatch.Leg(
                            Settlement.issuanceAccount(CAD),
                            PostingBatch.Role.ISSUANCE,
                            CAD,
                            new Money(CAD, -100)),
                        new PostingBatch.Leg(
                            account, PostingBatch.Role.ACCOUNT, CAD, new Money(CAD, 100))),
                    PostingBatch.Authority.ISSUE,
                    null);
        var registration =
            Custody.registration(
                action,
                (a, intent, current, reads) -> true,
                (current, proof, reads) -> true,
                financial);
        var engine =
            new JournalEngine(store, (actor, kind) -> true, Map.of(action.kind(), registration));
        var request =
            new Intent(
                new OperationId(UUID.randomUUID()),
                new RequestId(UUID.randomUUID()),
                owner,
                action.kind(),
                Custody.request(custody.id(), null, evidence, "confirmed original loss"),
                null,
                null);
        assertEquals(Status.COMMITTED, engine.submit(request).status());
        assertEquals(engine.lookup(request).orElseThrow(), engine.submit(request));
      }
      assertEquals(60, lot(store, cad).remaining());
      long reserved = store.transaction(tx -> CashIssuance.reserved(tx::query, cad, null));
      assertEquals(0L, reserved);
      assertEquals(100L, balance(store));
    } finally {
      store.close();
    }
  }

  private TerminalPolicy.Anchor terminal(EconomyCurrency currency) {
    return currency == CAD
        ? anchor
        : new TerminalPolicy.Anchor(anchor.dimension(), anchor.x() + 8, anchor.y(), anchor.z());
  }

  private UUID physicalGeneration(EconomyCurrency currency) {
    return currency == CAD
        ? generation
        : UUID.nameUUIDFromBytes(
            (generation + "EUR").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /**
   * Frozen pre-A6 pending row: new mixed/disabled reservations are denied, but original cash must
   * reconcile.
   */
  private UUID historicalReserve(SqliteStorage store, Deposits.Preview preview) {
    var fields = Deposits.reserveRequest(preview).fields();
    var slots =
        fields.entrySet().stream()
            .filter(e -> e.getKey().startsWith("slot_"))
            .sorted(
                java.util.Comparator.comparingInt(e -> Integer.parseInt(e.getKey().substring(5))))
            .map(Map.Entry::getValue)
            .collect(java.util.stream.Collectors.joining("\n"));
    var payable =
        preview.payable().stream()
            .map(s -> Integer.toString(s.index()))
            .collect(java.util.stream.Collectors.joining(","));
    var encoded =
        pack(fields.get("binding"))
            + ";"
            + fields.get("credit")
            + ";"
            + pack(fields.get("groups"))
            + ";"
            + pack(slots)
            + ";"
            + payable;
    if (fields.containsKey("settlement")) encoded += ";" + fields.get("settlement");
    var id = UUID.randomUUID();
    var frozen = encoded;
    store.transaction(
        tx -> {
          tx.execute(
              "UPDATE terminal_bank_bindings SET bank_key='',catalog_revision=0,native_currency='',migration_state='UNBOUND_BANK'",
              List.of());
          tx.execute(
              "INSERT INTO deposits VALUES(?,?,'RESERVED',?,'pending','legacy fixture',1)",
              List.of(id.toString(), Journal.actorKey(owner), frozen));
          var counts = new HashMap<UUID, Integer>();
          for (var slot : preview.slots())
            if (!slot.unmarked())
              counts.merge(slot.claim().lot(), slot.claim().count(), Integer::sum);
          counts.forEach(
              (lot, count) ->
                  tx.execute(
                      "INSERT INTO deposit_cash_reservations VALUES(?,?,?,'ACTIVE')",
                      List.of(id.toString(), lot.toString(), Integer.toString(count))));
          return null;
        });
    return id;
  }

  private static String pack(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private SqliteStorage open() throws Exception {
    var store = new SqliteStorage();
    store.initialize(world.toRealPath());
    return store;
  }

  private Deposits.FxSnapshot fx(JournalEngine.ReadView reads) {
    return new Deposits.FxSnapshot(
        fxRevision,
        new CurrencyConversion.Rates(CAD, Map.of(CAD, BigDecimal.ONE, EUR, new BigDecimal("2"))));
  }

  private Deposits.Slot slot(SqliteStorage store, int index, UUID lot, int count) {
    return new Deposits.Slot(
        index,
        new CashIssuance.Claim(
            store.snapshot().worldId(),
            lot,
            lot.equals(cad) ? "economy:cad_coin_25" : "economy:eur_coin_1",
            count),
        "original-components-" + lot);
  }

  private Deposits.Preview preview(SqliteStorage store, List<Deposits.Slot> slots) {
    var currency = catalog.get(slots.getFirst().claim().item()).currency();
    var binding =
        new Deposits.Binding(
            account,
            accountRevision,
            CAD,
            terminal(currency),
            physicalGeneration(currency),
            3,
            fxRevision);
    return store.transaction(
        tx ->
            Deposits.preview(
                tx::query, store.snapshot().worldId(), owner, binding, slots, catalog, this::fx));
  }

  private UUID reserve(SqliteStorage store, Deposits.Preview preview) {
    var request = intent(Deposits.Action.RESERVE, Deposits.reserveRequest(preview));
    assertEquals(Status.COMMITTED, engine(store, Deposits.Action.RESERVE).submit(request).status());
    return request.operationId().value();
  }

  private JournalEngine engine(SqliteStorage store, Deposits.Action action) {
    return new JournalEngine(
        store,
        (actor, kind) -> actor.equals(owner),
        Map.of(
            action.kind(),
            Deposits.registration(
                action,
                store.snapshot().worldId(),
                catalog,
                this::fx,
                (a, intent, current, reads) -> live,
                (a, intent, current, evidence, reads) -> proof)));
  }

  private Outcome change(
      SqliteStorage store, Deposits.Action action, UUID deposit, String evidence, String reason) {
    return engine(store, action)
        .submit(intent(action, Deposits.request(deposit, evidence, reason)));
  }

  private Intent intent(Deposits.Action action, CanonicalRequest request) {
    return new Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        owner,
        action.kind(),
        request,
        null,
        null);
  }

  private JournalEngine returnEngine(SqliteStorage store, DepositReturns.Action action) {
    return new JournalEngine(
        store,
        (actor, kind) -> actor.equals(owner),
        Map.of(
            action.kind(),
            DepositReturns.registration(
                action,
                store.snapshot().worldId(),
                (a, intent, current, reads) -> live,
                (a, current, delivered, evidence, reads) -> proof)));
  }

  private Intent returnIntent(
      SqliteStorage store,
      DepositReturns.Action action,
      UUID deposit,
      long revision,
      UUID attempt,
      int count,
      DepositReturns.Evidence evidence,
      UUID operation) {
    return new Intent(
        new OperationId(operation == null ? UUID.randomUUID() : operation),
        new RequestId(UUID.randomUUID()),
        owner,
        action.kind(),
        DepositReturns.request(deposit, 0, revision, attempt, count, evidence, "return"),
        null,
        null);
  }

  private CashIssuance.Lot lot(SqliteStorage store, UUID id) {
    return store
        .transaction(tx -> CashIssuance.find(tx::query, store.snapshot().worldId(), id))
        .orElseThrow();
  }

  private long balance(SqliteStorage store) {
    return store.transaction(tx -> Holds.available(tx::query, account, CAD));
  }

  private void seed(SqliteStorage store) {
    store.transaction(
        tx -> {
          tx.execute(
              "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,0)",
              List.of(account.toString(), owner.value().toString()));
          for (var id : List.of(cad, eur))
            tx.execute(
                "INSERT INTO cash_issuance VALUES(?,?,?,?,?,'ACTIVE',1,'fixture')",
                List.of(
                    id.toString(),
                    store.snapshot().worldId().value().toString(),
                    id.equals(cad) ? "economy:cad_coin_25" : "economy:eur_coin_1",
                    "64",
                    "64"));
          return null;
        });
    var terminalCatalog = new HashMap<String, TerminalPolicy.Denomination>();
    catalog.forEach(
        (id, d) ->
            terminalCatalog.put(id, new TerminalPolicy.Denomination(d.currency(), d.bill())));
    for (var currency : List.of(CAD, EUR)) {
      var at = terminal(currency);
      var physicalGeneration = physicalGeneration(currency);
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
              "economy:bank/" + (currency == CAD ? "crownstone" : "meridian_grove"));
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
                      TerminalPolicy.configureRequest(
                          at,
                          physicalGeneration,
                          2,
                          TerminalBanking.settings(
                              current, TerminalPolicy.Mode.BOTH, terminalCatalog)),
                      null,
                      null))
              .status());
    }
  }
}
