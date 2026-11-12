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

class HoldTest {
  private static final Kind POST = kind("post");
  private static final Kind RESERVE = kind("reserve");
  private static final Kind RELEASE = kind("release");
  private static final Kind CAPTURE = kind("capture");
  private final UUID source = UUID.randomUUID();
  private final UUID target = UUID.randomUUID();
  @TempDir Path world;

  @Test
  void activeHoldAndReserveOutcomeSurviveRestart() throws Exception {
    var reserve = reserve();
    var holdId = reserve.operationId().value();
    var store = new SqliteStorage();
    Outcome original;
    try {
      store.initialize(world.toRealPath());
      seed(store);
      original = engine(store).submit(reserve);
      assertEquals(Status.COMMITTED, original.status());
      assertEquals(7_000, available(store));
    } finally {
      store.close();
    }

    var reopened = new SqliteStorage();
    try {
      reopened.initialize(world.toRealPath());
      var engine = engine(reopened);
      assertEquals(original, engine.submit(retry(reserve)));
      assertEquals(7_000, available(reopened));
      assertEquals(1, reopened.transaction(tx -> Holds.active(tx::query, ACTOR, null, 10)).size());
      var release = intent(RELEASE, Holds.releaseRequest(holdId));
      var released = engine.submit(release);
      assertEquals(Status.COMMITTED, released.status());
      assertEquals(released, engine.submit(retry(release)));
      assertEquals(10_000, available(reopened));
      assertTrue(reopened.transaction(tx -> Holds.active(tx::query, ACTOR, null, 10)).isEmpty());
    } finally {
      reopened.close();
    }
  }

  @Test
  void queuedSpendCaptureAndReleaseObserveOneHoldTransition() throws Exception {
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
                    return true;
                  })
              .toCompletableFuture();
      pump(services, seeded::isDone);
      assertTrue(seeded.join());

      var journal =
          new JournalService(
              services,
              engine(store),
              new JournalService.Publisher() {
                public void invalidate(Outcome result) {}

                public void publish(Outcome result) {}
              });
      var reserve = reserve();
      var reserved = journal.submit(reserve).completion().toCompletableFuture();
      pump(services, reserved::isDone);
      assertEquals(Status.COMMITTED, reserved.join().outcome().status());

      var holdId = reserve.operationId().value();
      var capture = intent(CAPTURE, Holds.captureRequest(holdId, target));
      var blocked = journal.submit(spend(7_600)).completion().toCompletableFuture();
      var captured = journal.submit(capture).completion().toCompletableFuture();
      var released =
          journal
              .submit(intent(RELEASE, Holds.releaseRequest(holdId)))
              .completion()
              .toCompletableFuture();
      var otherSpend = journal.submit(spend(7_000)).completion().toCompletableFuture();
      var replay = journal.submit(retry(capture)).completion().toCompletableFuture();
      pump(
          services,
          () ->
              blocked.isDone()
                  && captured.isDone()
                  && released.isDone()
                  && otherSpend.isDone()
                  && replay.isDone());
      assertEquals("INSUFFICIENT_FUNDS", blocked.join().outcome().result().code());
      assertEquals(Status.COMMITTED, captured.join().outcome().status());
      assertEquals(Status.DURABLY_REJECTED, released.join().outcome().status());
      assertEquals(Status.COMMITTED, otherSpend.join().outcome().status());
      assertEquals(captured.join().outcome(), replay.join().outcome());
      var balances =
          services
              .storage(() -> List.of(available(store), posted(store, target)))
              .toCompletableFuture();
      pump(services, balances::isDone);
      assertEquals(List.of(0L, 10_000L), balances.join());
    } finally {
      assertTrue(services.stop(Duration.ofSeconds(3)));
    }
  }

  private void seed(SqliteStorage store) {
    store.transaction(
        tx -> {
          for (UUID account : List.of(source, target)) {
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,0)",
                List.of(account.toString(), UUID.randomUUID().toString()));
          }
          return null;
        });
    var funding =
        intent(
            POST,
            Settlement.request(
                List.of(
                    new PostingBatch.Leg(source, ACCOUNT, CAD, new Money(CAD, 10_000)),
                    new PostingBatch.Leg(
                        Settlement.issuanceAccount(CAD), ISSUANCE, CAD, new Money(CAD, -10_000)))));
    assertEquals(Status.COMMITTED, engine(store).submit(funding).status());
  }

  private static JournalEngine engine(SqliteStorage store) {
    Holds.Authority authority = (intent, source, target, reads) -> ACTOR.equals(intent.actor());
    return new JournalEngine(
        store,
        (actor, kind) -> ACTOR.equals(actor),
        Map.of(
            POST,
            Settlement.registration(
                (intent, legs, reads) ->
                    legs.stream().anyMatch(leg -> leg.role() == ISSUANCE) ? ISSUE : TRANSFER),
            RESERVE,
            Holds.reserve(authority),
            RELEASE,
            Holds.release(authority),
            CAPTURE,
            Holds.capture(authority)));
  }

  private Intent reserve() {
    return intent(RESERVE, Holds.reserveRequest(source, new Money(CAD, 3_000)));
  }

  private Intent spend(long units) {
    return intent(
        POST,
        Settlement.request(
            List.of(
                new PostingBatch.Leg(source, ACCOUNT, CAD, new Money(CAD, -units)),
                new PostingBatch.Leg(target, ACCOUNT, CAD, new Money(CAD, units)))));
  }

  private long available(SqliteStorage store) {
    return store.transaction(tx -> Holds.available(tx::query, source, CAD));
  }

  private static long posted(SqliteStorage store, UUID account) {
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

  private static Kind kind(String operation) {
    return new Kind("economy:hold-test", "economy:" + operation);
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
