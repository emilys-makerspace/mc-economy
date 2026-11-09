package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD;
import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.JournalFixtures.ACTOR;
import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Authority.ISSUE;
import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Authority.TRANSFER;
import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Role.ACCOUNT;
import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Role.ISSUANCE;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.OperationId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.RequestId;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.storage.FailureHarness;
import ca.emilybelnavis.mcmods.economy.storage.FixtureRecords;
import ca.emilybelnavis.mcmods.economy.storage.LedgerProjection;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettlementTest {
  private static final Kind KIND = new Kind("economy:settlement", "economy:post");
  private final UUID source = UUID.randomUUID();
  private final UUID target = UUID.randomUUID();
  @TempDir Path world;

  @Test
  void overlappingSubmissionsCommitExactlyOneProjection() throws Exception {
    var store = new SqliteStorage(FixtureRecords.catalog());
    var services = new ServerServices(world.toRealPath(), store);
    try {
      pump(services, () -> services.state() != ServerServices.State.INITIALIZING);
      assertEquals(ServerServices.State.READY, services.state());
      var created =
          services
              .storage(
                  () -> {
                    createAccounts(store);
                    return true;
                  })
              .toCompletableFuture();
      pump(services, created::isDone);
      assertTrue(created.join());
      var journal =
          new JournalService(
              services,
              engine(store),
              new JournalService.Publisher() {
                public void invalidate(Outcome result) {}

                public void publish(Outcome result) {}
              });
      var request = funding(10_000);
      var first = journal.submit(request);
      var duplicate = journal.submit(retry(request));
      assertEquals(Receipt.ACCEPTED_PENDING, first.receipt());
      assertEquals(Receipt.ACCEPTED_PENDING, duplicate.receipt());
      var original = first.completion().toCompletableFuture();
      var replay = duplicate.completion().toCompletableFuture();
      pump(services, () -> original.isDone() && replay.isDone());
      assertEquals(Status.COMMITTED, original.join().outcome().status());
      assertEquals(original.join().outcome(), replay.join().outcome());

      var changed = funding(20_000);
      var conflict =
          journal
              .submit(
                  new Intent(
                      request.operationId(),
                      changed.requestId(),
                      ACTOR,
                      KIND,
                      changed.request(),
                      null,
                      null))
              .completion()
              .toCompletableFuture();
      pump(services, conflict::isDone);
      assertEquals("OPERATION_CONFLICT", conflict.join().outcome().result().code());
      var projected = services.storage(() -> balance(store, source)).toCompletableFuture();
      pump(services, projected::isDone);
      assertEquals(10_000L, projected.join());
    } finally {
      assertTrue(services.stop(Duration.ofSeconds(3)));
    }
  }

  @Test
  void fundingDoesNotChangeOriginalInsufficientFundsOutcome() throws Exception {
    var store = new SqliteStorage(FixtureRecords.catalog());
    try {
      store.initialize(world.toRealPath());
      createAccounts(store);
      var engine = engine(store);
      var transfer = transfer();
      var rejected = engine.submit(transfer);
      assertEquals(Status.DURABLY_REJECTED, rejected.status());
      assertEquals("INSUFFICIENT_FUNDS", rejected.result().code());
      assertTrue(engine.readAfter(0, 10).isEmpty());
      assertEquals(Status.COMMITTED, engine.submit(funding(10_000)).status());
      assertEquals(rejected, engine.submit(retry(transfer)));
      assertEquals(Status.COMMITTED, engine.submit(transfer()).status());
      assertEquals(7_500, balance(store, source));
      assertEquals(2_500, balance(store, target));
      assertEquals(2, engine.readAfter(0, 10).size());
    } finally {
      store.close();
    }
  }

  @Test
  void commitBoundaryRecoveryKeepsPostingFactsAndBalancesTogether() throws Exception {
    var harness = new FailureHarness();
    var store = harness.store;
    try {
      store.initialize(world.toRealPath());
      createAccounts(store);
      var engine = engine(store);
      var request = funding(10_000);
      harness.fault.set(FailureHarness.Fault.BEFORE_COMMIT);
      var absent = engine.submit(request);
      assertEquals("ABSENT_RETRY_SAME_ID", absent.result().code());
      assertEquals(0, balance(store, source));
      assertTrue(engine.lookup(request).isEmpty());
      assertTrue(engine.readAfter(0, 10).isEmpty());

      harness.fault.set(FailureHarness.Fault.AFTER_COMMIT);
      var committed = engine.submit(retry(request));
      assertEquals(Status.COMMITTED, committed.status());
      assertEquals(committed, engine.lookup(request).orElseThrow());
      assertEquals(committed, engine.submit(retry(request)));
      assertEquals(10_000, balance(store, source));
      var envelope = engine.readAfter(0, 10).getFirst();
      assertEquals(committed.transactionId(), envelope.transactionId());
      assertEquals(2, envelope.records().size());
      assertEquals(3, harness.opens.get());
    } finally {
      store.close();
    }
  }

  @Test
  void rebuildingRestoresIdenticalProjectionFromJournalFacts() throws Exception {
    var store = new SqliteStorage(FixtureRecords.catalog());
    try {
      store.initialize(world.toRealPath());
      createAccounts(store);
      var engine = engine(store);
      var issue = engine.submit(funding(10_000));
      assertEquals(Status.COMMITTED, issue.status());
      assertEquals(Status.COMMITTED, engine.submit(transfer()).status());
      var expected = balances(store);
      assertEquals(3, expected.size());
      store.transaction(tx -> tx.execute("DELETE FROM ledger_balances", List.of()));
      assertTrue(balances(store).isEmpty());

      LedgerProjection.rebuild(store);
      assertEquals(expected, balances(store));
      LedgerProjection.rebuild(store);
      assertEquals(expected, balances(store));
      assertEquals(2, engine.readAfter(0, 10).size());
    } finally {
      store.close();
    }
  }

  private static List<Map<String, String>> balances(SqliteStorage store) {
    return store.transaction(
        tx ->
            tx.query(
                "SELECT account_id,currency,minor_units FROM ledger_balances ORDER BY account_id,currency",
                List.of(),
                10));
  }

  private JournalEngine engine(SqliteStorage store) {
    return new JournalEngine(
        store,
        (actor, kind) -> ACTOR.equals(actor) && KIND.equals(kind),
        Map.of(
            KIND,
            Settlement.registration(
                (intent, legs, reads) ->
                    legs.stream().anyMatch(leg -> leg.role() == ISSUANCE) ? ISSUE : TRANSFER)));
  }

  private Intent funding(long units) {
    return intent(
        List.of(
            new PostingBatch.Leg(source, ACCOUNT, CAD, new Money(CAD, units)),
            new PostingBatch.Leg(
                Settlement.issuanceAccount(CAD), ISSUANCE, CAD, new Money(CAD, -units))));
  }

  private Intent transfer() {
    return intent(
        List.of(
            new PostingBatch.Leg(source, ACCOUNT, CAD, new Money(CAD, -2_500)),
            new PostingBatch.Leg(target, ACCOUNT, CAD, new Money(CAD, 2_500))));
  }

  private static Intent intent(List<PostingBatch.Leg> legs) {
    return new Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        ACTOR,
        KIND,
        Settlement.request(legs),
        null,
        null);
  }

  private static Intent retry(Intent intent) {
    return new Intent(
        intent.operationId(),
        new RequestId(UUID.randomUUID()),
        intent.actor(),
        intent.kind(),
        intent.request(),
        null,
        null);
  }

  private void createAccounts(SqliteStorage store) {
    store.transaction(
        tx -> {
          for (UUID account : List.of(source, target)) {
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,0)",
                List.of(account.toString(), UUID.randomUUID().toString()));
          }
          return null;
        });
  }

  private static long balance(SqliteStorage store, UUID account) {
    return store.transaction(
        tx ->
            tx
                .query(
                    "SELECT minor_units FROM ledger_balances WHERE account_id=? AND currency='CAD'",
                    List.of(account.toString()),
                    1)
                .stream()
                .mapToLong(row -> Long.parseLong(row.get("minor_units")))
                .findFirst()
                .orElse(0L));
  }

  private static void pump(ServerServices services, BooleanSupplier finished)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!finished.getAsBoolean() && System.nanoTime() < deadline) {
      services.tick();
      Thread.sleep(1);
    }
    assertTrue(finished.getAsBoolean());
  }
}
