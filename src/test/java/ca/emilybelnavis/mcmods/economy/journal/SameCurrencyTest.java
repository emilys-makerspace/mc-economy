package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD;
import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.JournalFixtures.ACTOR;
import static ca.emilybelnavis.mcmods.economy.journal.SameCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.OperationId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.RequestId;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SameCurrencyTest {
  private final AccountId source = new AccountId(UUID.randomUUID());
  private final AccountId target = new AccountId(UUID.randomUUID());
  private final PlayerId recipient = new PlayerId(UUID.randomUUID());
  @TempDir Path world;

  @Test
  void competingPaymentsCommitOnePairedTransferEvenAfterDisconnect() throws Exception {
    var store = new SqliteStorage();
    var services = new ServerServices(world.toRealPath(), store);
    try {
      pump(services, () -> services.state() != ServerServices.State.INITIALIZING);
      assertEquals(ServerServices.State.READY, services.state());
      var seeded =
          services
              .storage(
                  () -> {
                    seed(store);
                    var engine = engine(store, new AtomicBoolean(true));
                    assertEquals(Status.COMMITTED, engine.submit(fund()).status());
                    return engine;
                  })
              .toCompletableFuture();
      pump(services, seeded::isDone);
      var engine = seeded.join();
      var journal =
          new JournalService(
              services,
              engine,
              new JournalService.Publisher() {
                public void invalidate(Outcome outcome) {}

                public void publish(Outcome outcome) {}
              });
      var connected = new AtomicBoolean(true);
      var delivered = new AtomicBoolean(false);
      var target = new JournalService.LiveTarget(services.sessionId(), ACTOR, null);
      var paid =
          journal
              .submit(
                  payment(recipient, 1),
                  target,
                  ignored -> connected.get() ? outcome -> delivered.set(true) : null)
              .completion()
              .toCompletableFuture();
      connected.set(false);
      var overspend = journal.submit(payment(recipient, 1)).completion().toCompletableFuture();
      pump(services, () -> paid.isDone() && overspend.isDone());
      assertEquals(Status.COMMITTED, paid.join().outcome().status());
      assertEquals(JournalService.DeliveryStatus.SKIPPED, paid.join().publication());
      assertFalse(delivered.get());
      assertEquals(Status.DURABLY_REJECTED, overspend.join().outcome().status());
      assertEquals("INSUFFICIENT_FUNDS", overspend.join().outcome().result().code());

      var history = services.storage(() -> engine.readAfter(0, 10)).toCompletableFuture();
      pump(services, history::isDone);
      assertEquals(2, history.join().size());
      var payment = history.join().getLast();
      assertEquals(paid.join().outcome().transactionId(), payment.transactionId());
      assertEquals(
          List.of(
              source.value() + "|ACCOUNT|CAD|-7000|TRANSFER",
              this.target.value() + "|ACCOUNT|CAD|7000|TRANSFER"),
          payment.records().stream().map(Entry::payload).toList());
    } finally {
      assertTrue(services.stop(Duration.ofSeconds(3)));
    }
  }

  @Test
  void livePrimaryRevisionAndAuthorityAreCheckedAtSettlement() throws Exception {
    var store = new SqliteStorage();
    try {
      store.initialize(world.toRealPath());
      seed(store);
      var authorized = new AtomicBoolean(true);
      var engine = engine(store, authorized);
      assertEquals(Status.COMMITTED, engine.submit(fund()).status());
      assertRejected("MISSING_PRIMARY", engine.submit(payment(new PlayerId(UUID.randomUUID()), 1)));

      var beforeChange = payment(recipient, 1);
      store.transaction(
          tx ->
              tx.execute(
                  "UPDATE account_catalog SET revision=2 WHERE account_id=?",
                  List.of(target.value().toString())));
      assertRejected("STALE_ACCOUNT", engine.submit(beforeChange));

      var beforeRevocation = payment(recipient, 2);
      authorized.set(false);
      assertRejected("AUTHORITY_REVOKED", engine.submit(beforeRevocation));
      assertEquals(1, engine.readAfter(0, 10).size());
    } finally {
      store.close();
    }
  }

  private void seed(SqliteStorage store) {
    store.transaction(
        tx -> {
          tx.execute(
              "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
              List.of(source.value().toString(), ACTOR.value().toString()));
          tx.execute(
              "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
              List.of(target.value().toString(), recipient.value().toString()));
          return null;
        });
  }

  private static JournalEngine engine(SqliteStorage store, AtomicBoolean authorized) {
    return new JournalEngine(
        store,
        (actor, kind) -> ACTOR.equals(actor),
        registrations(
            store.snapshot().worldId(), (intent, instruction, reads) -> authorized.get()));
  }

  private Intent fund() {
    return intent(
        CREDIT, new Adjustment(ACTOR, new Revision(source, 1), new Money(CAD, 10_000)).request());
  }

  private Intent payment(PlayerId to, long revision) {
    return intent(
        PAY,
        new Payment(
                ACTOR,
                new Revision(source, 1),
                to,
                new Revision(target, revision),
                new Money(CAD, 7_000))
            .request());
  }

  private static Intent intent(Kind kind, CanonicalRequest request) {
    return new Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        ACTOR,
        kind,
        request,
        null,
        null);
  }

  private static void assertRejected(String code, Outcome outcome) {
    assertEquals(Status.DURABLY_REJECTED, outcome.status());
    assertEquals(code, outcome.result().code());
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
