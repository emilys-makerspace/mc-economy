package ca.emilybelnavis.mcmods.economy.card;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CardCustodyRecoveryTest {
  @TempDir Path world;

  private record Issuance(PlayerId owner, UUID token) {}

  private SqliteStorage open() throws Exception {
    var store = new SqliteStorage();
    store.initialize(world.toRealPath());
    return store;
  }

  private void publication(SqliteStorage store) {
    var actor = new PlayerId(UUID.randomUUID());
    var handler =
        FxService.opening(Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC));
    var request = new Journal.CanonicalRequest(1, Map.of("currency", "CAD"));
    var intent =
        new Journal.Intent(
            new OperationId(UUID.randomUUID()),
            new RequestId(UUID.randomUUID()),
            actor,
            FxService.OPEN,
            request,
            null,
            null);
    assertEquals(
        Journal.Status.COMMITTED,
        new JournalEngine(store, (a, k) -> true, Map.of(FxService.OPEN, handler))
            .submit(intent)
            .status());
  }

  private Issuance issue(SqliteStorage store) {
    var actor = new PlayerId(UUID.randomUUID());
    var account = new AccountId(UUID.randomUUID());
    var owner = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(actor));
    store.transaction(
        tx -> {
          tx.execute(
              "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
              List.of(account.value().toString(), actor.value().toString()));
          tx.execute(
              "INSERT INTO bank_account_providers VALUES(?,'economy:bank/crownstone',0,1,0)",
              List.of(account.value().toString()));
          return null;
        });
    var proof =
        BranchFixtures.proof(
            new BranchAdmission.Context(
                owner.world(),
                UUID.randomUUID(),
                actor,
                BranchAdmission.Action.CARD_ISSUE,
                new InstitutionCatalog.InstitutionId("economy:bank/crownstone"),
                null,
                account,
                1,
                1,
                0));
    var request = new IssuedCards.Request(proof, 0, null, null);
    var intent =
        new Journal.Intent(
            new OperationId(UUID.randomUUID()),
            new RequestId(UUID.randomUUID()),
            actor,
            IssuedCards.ISSUE,
            request.request(),
            null,
            null);
    var handler =
        IssuedCards.registration(owner, request, BankProviders.NO_TIER2, () -> 1000, null);
    assertEquals(
        Journal.Status.COMMITTED,
        new JournalEngine(store, (a, k) -> true, Map.of(IssuedCards.ISSUE, handler))
            .submit(intent)
            .status());
    return new Issuance(actor, intent.operationId().value());
  }

  private Custody.Case current(SqliteStorage store, Issuance card) {
    return store.transaction(
        tx -> Custody.find(tx::query, card.owner(), card.token(), false).orElseThrow());
  }

  private void change(
      SqliteStorage store,
      Issuance card,
      Custody.Action action,
      UUID attempt,
      Custody.Evidence evidence) {
    var operation =
        action == Custody.Action.BEGIN_DELIVERY || action == Custody.Action.COLLECT
            ? attempt
            : UUID.randomUUID();
    var request =
        Custody.request(card.token(), attempt, evidence, "trusted test custody observation");
    var intent =
        new Journal.Intent(
            new OperationId(operation),
            new RequestId(UUID.randomUUID()),
            card.owner(),
            action.kind(),
            request,
            null,
            null);
    var handler =
        Custody.registration(
            action,
            (a, i, c, r) -> i.actor().equals(card.owner()),
            (c, e, r) -> true,
            null,
            (i, c, r) -> true);
    assertEquals(
        Journal.Status.COMMITTED,
        new JournalEngine(store, (a, k) -> true, Map.of(action.kind(), handler))
            .submit(intent)
            .status());
  }

  private long outcomes(SqliteStorage store) {
    return store.transaction(
        tx ->
            Long.parseLong(
                tx.query("SELECT count(*) AS n FROM operation_outcomes", List.of(), 1)
                    .getFirst()
                    .get("n")));
  }

  @Test
  void commitBeforeDeliveryBecomesOneRetainedOwnerClaimAcrossRestarts() throws Exception {
    Issuance card;
    var store = open();
    try {
      publication(store);
      card = issue(store);
      assertEquals(Custody.State.COMMITTED, current(store, card).state());
    } finally {
      store.close();
    }
    store = open();
    try {
      assertTrue(Custody.reconcileBatch(store));
      var claim = current(store, card);
      assertEquals(Custody.State.CLAIM, claim.state());
      assertEquals(Custody.EvidenceKind.UNDELIVERED, claim.evidence().kind());
      assertNull(claim.deliveryAttempt());
      var count = outcomes(store);
      assertTrue(Custody.reconcileBatch(store));
      assertEquals(count, outcomes(store));
      assertEquals(claim.revision(), current(store, card).revision());
      var reopened = store;
      assertEquals(
          1,
          reopened
              .<Integer>transaction(
                  tx -> tx.query("SELECT token_id FROM issued_cards", List.of(), 2).size())
              .intValue());
    } finally {
      store.close();
    }
    store = open();
    try {
      var count = outcomes(store);
      assertTrue(Custody.reconcileBatch(store));
      assertEquals(count, outcomes(store));
      assertEquals(Custody.State.CLAIM, current(store, card).state());
      assertEquals(
          1,
          store.transaction(tx -> Custody.claims(tx::query, card.owner(), null, 8)).cases().size());
    } finally {
      store.close();
    }
  }

  @Test
  void ambiguousAttemptsQuarantineWhileVerifiedClaimsAndDeliveredCasesRemainTerminal()
      throws Exception {
    var store = open();
    try {
      publication(store);
      var delivering = issue(store);
      var acknowledged = issue(store);
      var delivered = issue(store);
      var claim = issue(store);
      var forged = issue(store);
      var first = UUID.randomUUID();
      change(store, delivering, Custody.Action.BEGIN_DELIVERY, first, Custody.Evidence.unknown());
      var second = UUID.randomUUID();
      change(
          store, acknowledged, Custody.Action.BEGIN_DELIVERY, second, Custody.Evidence.unknown());
      change(store, acknowledged, Custody.Action.ACKNOWLEDGE, second, Custody.Evidence.unknown());
      var third = UUID.randomUUID();
      change(store, delivered, Custody.Action.BEGIN_DELIVERY, third, Custody.Evidence.unknown());
      change(
          store,
          delivered,
          Custody.Action.DELIVERY_SAVED,
          third,
          new Custody.Evidence(
              Custody.EvidenceKind.DELIVERY_SAVED,
              "save",
              Journal.actorKey(delivered.owner()),
              "saved"));
      change(
          store,
          claim,
          Custody.Action.CLAIM,
          null,
          new Custody.Evidence(
              Custody.EvidenceKind.UNDELIVERED,
              "inventory-full",
              Journal.actorKey(claim.owner()),
              "no-insertion"));
      store.transaction(
          tx ->
              tx.execute(
                  "UPDATE custody_cases SET destination=? WHERE id=?",
                  List.of("player:" + UUID.randomUUID(), forged.token().toString())));
      assertTrue(Custody.reconcileBatch(store));
      assertEquals(Custody.State.QUARANTINED, current(store, delivering).state());
      assertEquals(Custody.State.QUARANTINED, current(store, acknowledged).state());
      assertEquals(Custody.State.DELIVERED, current(store, delivered).state());
      assertEquals(Custody.State.CLAIM, current(store, claim).state());
      assertEquals(Custody.State.QUARANTINED, current(store, forged).state());
      var count = outcomes(store);
      assertTrue(Custody.reconcileBatch(store));
      assertEquals(count, outcomes(store));
    } finally {
      store.close();
    }
  }

  @Test
  void retainedClaimsDoNotTrapTheBoundedStartupCursor() throws Exception {
    var store = open();
    try {
      publication(store);
      var cards = new ArrayList<Issuance>();
      for (int i = 0; i < 17; i++) cards.add(issue(store));
      assertFalse(Custody.reconcileBatch(store));
      assertTrue(Custody.reconcileBatch(store));
      for (var card : cards) assertEquals(Custody.State.CLAIM, current(store, card).state());
      var count = outcomes(store);
      assertFalse(Custody.reconcileBatch(store));
      assertTrue(Custody.reconcileBatch(store));
      assertEquals(count, outcomes(store));
    } finally {
      store.close();
    }
  }
}
