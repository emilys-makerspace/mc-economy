package ca.emilybelnavis.mcmods.economy.loan;

import static ca.emilybelnavis.mcmods.economy.collection.CollectionPresence.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.collection.CollectionPresence;
import ca.emilybelnavis.mcmods.economy.debt.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollectionPresenceTest {
  @TempDir Path path;

  private DebtRecords.Case seed(LoanTestFixture fixture) {
    var loan = fixture.originate();
    DebtAcceptanceTest.activate(fixture);
    var scheduler = DebtAcceptanceTest.scheduler(fixture);
    DebtAcceptanceTest.catchup(fixture, scheduler, loan.nextMonth() + DebtRecords.DEFAULT_DELAY);
    return fixture.store.transaction(
        tx ->
            DebtLifecycle.facilityCase(tx::query, DebtRecords.Kind.LOAN, loan.id()).orElseThrow());
  }

  private Journal.Outcome change(
      LoanTestFixture fixture, Action action, UUID lease, long observed) {
    var request = CollectionPresence.request(fixture.owner, action, lease, observed);
    var operation = CollectionPresence.operation(fixture.owner, action, lease);
    var intent =
        new Journal.Intent(
            new OperationId(operation),
            new RequestId(UUID.randomUUID()),
            fixture.actor,
            KIND,
            request,
            null,
            null);
    return fixture.submit(
        KIND,
        CollectionPresence.registration(fixture.owner, action, lease, observed, fixture.clock::get),
        intent);
  }

  @Test
  void oneLeaseExitAndDeathHaveSeparateDurableEffectsWithoutChangingDebt() throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      var debt = seed(fixture);
      var lease = UUID.randomUUID();
      long ledger = fixture.store.lastCommittedSequence();
      var reserve = change(fixture, Action.RESERVE, lease, 0);
      assertEquals("COLLECTION_AGENT_RESERVED", reserve.result().code());
      assertEquals(reserve, change(fixture, Action.RESERVE, lease, 0));
      assertEquals(
          "COLLECTION_AGENT_BLOCKED",
          change(fixture, Action.RESERVE, UUID.randomUUID(), 0).result().code());
      assertEquals(
          "COLLECTION_AGENT_STALE",
          change(fixture, Action.RELEASE, UUID.randomUUID(), 0).result().code());
      assertEquals(
          "COLLECTION_AGENT_RELEASED", change(fixture, Action.RELEASE, lease, 0).result().code());
      var fresh = UUID.randomUUID();
      assertEquals(
          "COLLECTION_AGENT_RESERVED", change(fixture, Action.RESERVE, fresh, 0).result().code());
      long observed = fixture.clock.incrementAndGet();
      assertEquals(
          "COLLECTION_AGENT_KILLED",
          change(fixture, Action.DEATH, fresh, observed).result().code());
      var state =
          fixture.store.transaction(tx -> CollectionPresence.state(tx::query, fixture.owner));
      assertEquals(Phase.KILLED, state.phase());
      assertEquals(observed + COOLDOWN, state.cooldownUntil());
      assertEquals(
          "COLLECTION_AGENT_STALE", change(fixture, Action.RELEASE, fresh, 0).result().code());
      fixture.clock.set(state.cooldownUntil() - 1);
      assertEquals(
          "COLLECTION_AGENT_BLOCKED",
          change(fixture, Action.RESERVE, UUID.randomUUID(), 0).result().code());
      fixture.clock.incrementAndGet();
      assertEquals(
          "COLLECTION_AGENT_RESERVED",
          change(fixture, Action.RESERVE, UUID.randomUUID(), 0).result().code());
      assertEquals(
          debt,
          fixture.store.transaction(
              tx -> DebtLifecycle.findCase(tx::query, debt.id()).orElseThrow()));
      var postings =
          fixture.store.transaction(
              tx ->
                  tx.query(
                      "SELECT sequence FROM journal_records WHERE record_type='economy:posting' AND sequence>? LIMIT 1",
                      List.of(Long.toString(ledger)),
                      1));
      assertTrue(postings.isEmpty());
    }
  }

  @Test
  void orphanedLiveLeaseCreatesRecoveryAnchoredProtectionEvenAfterAnOldSpawn() throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      var debt = seed(fixture);
      var lease = UUID.randomUUID();
      change(fixture, Action.RESERVE, lease, 0);
      fixture.clock.addAndGet(10 * DebtRecords.DAY);
      fixture.reopen();
      assertEquals(
          "COLLECTION_AGENT_BLOCKED",
          change(fixture, Action.RESERVE, UUID.randomUUID(), 0).result().code());
      assertEquals(
          "COLLECTION_AGENT_UNCERTAIN",
          change(fixture, Action.RECONCILE, lease, 0).result().code());
      var state =
          fixture.store.transaction(tx -> CollectionPresence.state(tx::query, fixture.owner));
      assertEquals(fixture.clock.get() + COOLDOWN, state.cooldownUntil());
      assertEquals(Phase.UNCERTAIN, state.phase());
      fixture.reopen();
      assertEquals(
          state,
          fixture.store.transaction(tx -> CollectionPresence.state(tx::query, fixture.owner)));
      assertEquals(
          debt,
          fixture.store.transaction(
              tx -> DebtLifecycle.findCase(tx::query, debt.id()).orElseThrow()));
      var stranger =
          new PartyRef(fixture.owner.world(), new PartyRef.Player(new PlayerId(UUID.randomUUID())));
      assertTrue(
          fixture
              .store
              .transaction(
                  tx ->
                      CollectionPresence.snapshot(
                          tx::query, stranger, null, 16, fixture.clock.get()))
              .cases()
              .isEmpty());
    }
  }

  @Test
  void settledAndExpiredCasesCannotAuthorizePresentationAndCodecsRejectMalformedWindows()
      throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      var debt = seed(fixture);
      fixture.clock.set(debt.collectionExpires());
      assertTrue(
          fixture
              .store
              .transaction(
                  tx ->
                      CollectionPresence.snapshot(
                          tx::query, fixture.owner, null, 16, fixture.clock.get()))
              .cases()
              .isEmpty());
      assertEquals(
          "COLLECTION_AGENT_BLOCKED",
          change(fixture, Action.RESERVE, UUID.randomUUID(), 0).result().code());
      assertThrows(
          IllegalArgumentException.class,
          () -> CollectionPresence.request(fixture.owner, Action.RELEASE, UUID.randomUUID(), 1));
      assertThrows(IllegalArgumentException.class, () -> CollectionPresence.decode("bad"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new State(
                  fixture.owner.world(),
                  fixture.actor,
                  UUID.randomUUID(),
                  Phase.KILLED,
                  100,
                  100 + COOLDOWN - 1,
                  1));
    }
  }
}
