package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.JournalFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class JournalServiceTest {
  @TempDir Path temporary;
  Path world;

  @BeforeEach
  void setup() throws Exception {
    world = temporary.toRealPath();
    createWorld(world);
  }

  private void pump(ServerServices services, java.util.function.BooleanSupplier finished)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!finished.getAsBoolean() && System.nanoTime() < deadline) {
      services.tick();
      Thread.sleep(1);
    }
    assertTrue(finished.getAsBoolean());
  }

  private void ready(ServerServices services) throws Exception {
    pump(services, () -> services.state() != ServerServices.State.INITIALIZING);
    assertEquals(ServerServices.State.READY, services.state());
  }

  private static JournalService.Publisher publisher() {
    return new JournalService.Publisher() {
      public void invalidate(Outcome result) {}

      public void publish(Outcome result) {}
    };
  }

  @Test
  void overlappingSameKeySubmissionsHaveOneEffectAndOnlyPostcommitPublish() throws Exception {
    var store = new SqliteStorage(ca.emilybelnavis.mcmods.economy.storage.FixtureRecords.catalog());
    var services = new ServerServices(world, store);
    var prepares = new AtomicInteger();
    var engine = engine(store, prepares);
    AtomicInteger publications = new AtomicInteger();
    Thread owner = Thread.currentThread();
    var journal =
        new JournalService(
            services,
            engine,
            new JournalService.Publisher() {
              public void invalidate(Outcome result) {
                assertSame(owner, Thread.currentThread());
              }

              public void publish(Outcome result) {
                assertSame(owner, Thread.currentThread());
                assertEquals(Status.COMMITTED, result.status());
                publications.incrementAndGet();
              }
            });
    var first = intent();
    assertEquals(Receipt.NOT_ACCEPTED, journal.submit(first).receipt());
    ready(services);
    List<CompletableFuture<JournalService.Delivery>> futures = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      var submission = journal.submit(intent(first.operationId()));
      assertEquals(Receipt.ACCEPTED_PENDING, submission.receipt());
      futures.add(submission.completion().toCompletableFuture());
    }
    assertEquals(0, publications.get());
    var refused = journal.submit(intent());
    assertEquals(Receipt.NOT_ACCEPTED, refused.receipt());
    assertEquals(
        "OVERLOADED", refused.completion().toCompletableFuture().join().outcome().result().code());
    var differentActor =
        new Intent(
            first.operationId(),
            first.requestId(),
            new ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId(UUID.randomUUID()),
            first.kind(),
            first.request(),
            first.economicUpdateId(),
            first.correctionOf());
    assertEquals(Receipt.ACCEPTED_PENDING, journal.submit(differentActor).receipt());
    pump(services, () -> futures.stream().allMatch(CompletableFuture::isDone));
    var original = futures.getFirst().join().outcome();
    futures.forEach(f -> assertEquals(original, f.join().outcome()));
    assertEquals(1, prepares.get());
    assertEquals(8, publications.get());
    var counter = services.storage(() -> counter(store)).toCompletableFuture();
    pump(services, counter::isDone);
    assertEquals(1, counter.join());
    assertTrue(services.stop(Duration.ofSeconds(3)));
    assertEquals(Receipt.NOT_ACCEPTED, journal.submit(first).receipt());
  }

  @Test
  void publicationFailureAndCancellationCannotReverseCommit() throws Exception {
    var store = new SqliteStorage(ca.emilybelnavis.mcmods.economy.storage.FixtureRecords.catalog());
    var services = new ServerServices(world, store);
    ready(services);
    var prepares = new AtomicInteger();
    var engine = engine(store, prepares);
    var invalidations = new AtomicInteger();
    var journal =
        new JournalService(
            services,
            engine,
            new JournalService.Publisher() {
              public void invalidate(Outcome result) {
                invalidations.incrementAndGet();
              }

              public void publish(Outcome result) {
                throw new IllegalStateException("fixture cache/network failure");
              }
            });
    Intent request = intent();
    var future = journal.submit(request).completion().toCompletableFuture();
    pump(services, future::isDone);
    assertEquals(Status.COMMITTED, future.join().outcome().status());
    assertEquals(JournalService.DeliveryStatus.FAILED, future.join().publication());
    var duplicate =
        journal.submit(intent(request.operationId())).completion().toCompletableFuture();
    pump(services, duplicate::isDone);
    assertEquals(future.join().outcome(), duplicate.join().outcome());
    assertEquals(1, prepares.get());
    assertTrue(invalidations.get() >= 2);
    Intent disconnected = intent();
    var canceled = journal.submit(disconnected).completion().toCompletableFuture();
    assertTrue(canceled.cancel(false));
    var resolved = journal.lookup(disconnected).toCompletableFuture();
    pump(services, resolved::isDone);
    assertEquals(Status.COMMITTED, resolved.join().orElseThrow().status());
    assertEquals(2, prepares.get());
    assertTrue(services.stop(Duration.ofSeconds(3)));
    var reopened =
        new SqliteStorage(ca.emilybelnavis.mcmods.economy.storage.FixtureRecords.catalog());
    reopened.initialize(world);
    assertEquals(2, counter(reopened));
    assertTrue(engine(reopened, prepares).lookup(disconnected).isPresent());
    reopened.close();
  }

  @Test
  void recoveryFailureReturnsUnknownDisablesWritesAndLaterLookupResolves() throws Exception {
    var harness = new FailureHarness();
    var services = new ServerServices(world, harness.store);
    ready(services);
    var engine = engine(harness.store, new AtomicInteger());
    var journal = new JournalService(services, engine, publisher());
    Intent request = intent();
    harness.fault.set(FailureHarness.Fault.LOST_COMMIT_AND_CLOSE);
    var future = journal.submit(request).completion().toCompletableFuture();
    pump(services, future::isDone);
    assertEquals(Status.UNKNOWN, future.join().outcome().status());
    assertEquals(ServerServices.State.UNAVAILABLE, services.state());
    assertEquals(Receipt.NOT_ACCEPTED, journal.submit(intent()).receipt());
    harness.fault.set(FailureHarness.Fault.NONE);
    assertTrue(services.stop(Duration.ofSeconds(3)));
    var reopened =
        new SqliteStorage(ca.emilybelnavis.mcmods.economy.storage.FixtureRecords.catalog());
    reopened.initialize(world);
    assertEquals(
        Status.COMMITTED,
        engine(reopened, new AtomicInteger()).lookup(request).orElseThrow().status());
    assertEquals(1, counter(reopened));
    reopened.close();
  }

  @Test
  void livePublicationResolvesCurrentMenuAndSharesOriginalLookup() throws Exception {
    var store = new SqliteStorage(ca.emilybelnavis.mcmods.economy.storage.FixtureRecords.catalog());
    var services = new ServerServices(world, store);
    try {
      ready(services);
      var journal = new JournalService(services, engine(store, new AtomicInteger()), publisher());
      var request = intent();
      var menu = UUID.randomUUID();
      var target =
          new JournalService.LiveTarget(
              services.sessionId(),
              (ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId) request.actor(),
              menu);
      var currentMenu = new java.util.concurrent.atomic.AtomicReference<>(menu);
      var resolution = new AtomicInteger();
      var completion =
          journal
              .submit(
                  request,
                  target,
                  live -> {
                    resolution.incrementAndGet();
                    return live.menu().equals(currentMenu.get())
                        ? outcome -> fail("Stale menu received outcome")
                        : null;
                  })
              .completion()
              .toCompletableFuture();
      currentMenu.set(UUID.randomUUID());
      var lookupChecks = new AtomicInteger();
      var reader =
          new JournalService(
              services,
              new JournalEngine(
                  store,
                  (actor, kind) -> {
                    lookupChecks.incrementAndGet();
                    return true;
                  },
                  Map.of()),
              publisher());
      var result = reader.lookup(request).toCompletableFuture();
      var sharedResult = reader.lookup(request).toCompletableFuture();
      pump(services, () -> completion.isDone() && result.isDone() && sharedResult.isDone());
      assertEquals(1, resolution.get());
      assertEquals(1, lookupChecks.get());
      assertEquals(JournalService.DeliveryStatus.SKIPPED, completion.join().publication());
      assertEquals(completion.join().outcome(), result.join().orElseThrow());
      assertEquals(result.join(), sharedResult.join());
    } finally {
      services.stop(Duration.ofSeconds(3));
    }
  }

  @Test
  void operatorCheckpointRefreshesAfterCommitWithoutExposingPlayerDetails() throws Exception {
    var store = new SqliteStorage(ca.emilybelnavis.mcmods.economy.storage.FixtureRecords.catalog());
    var services = new ServerServices(world, store);
    try {
      ready(services);
      assertTrue(
          services.diagnostics(true, true).stream()
              .anyMatch(line -> line.contains("last committed sequence 0")));
      var journal = new JournalService(services, engine(store, new AtomicInteger()), publisher());
      var delivery = journal.submit(intent()).completion().toCompletableFuture();
      pump(services, delivery::isDone);
      long sequence = delivery.join().outcome().sequence().value();
      pump(
          services,
          () ->
              services.diagnostics(true, true).stream()
                  .anyMatch(line -> line.contains("last committed sequence " + sequence + ".")));
      assertEquals(List.of("Economy is available."), services.diagnostics(false, false));
    } finally {
      assertTrue(services.stop(Duration.ofSeconds(3)));
    }
  }

  @Test
  void overloadDoesNotConsumeOperationAndSameIdCanBeSubmittedLater() throws Exception {
    var store = new SqliteStorage(ca.emilybelnavis.mcmods.economy.storage.FixtureRecords.catalog());
    var services = new ServerServices(world, store);
    ready(services);
    var journal = new JournalService(services, engine(store, new AtomicInteger()), publisher());
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      services.storage(
          () -> {
            started.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return 0;
          });
      assertTrue(started.await(3, TimeUnit.SECONDS));
      for (int i = 0; i < ServerServices.STORAGE_CAPACITY; i++) services.storage(() -> 0);
      Intent request = intent();
      var refused = journal.submit(request);
      assertEquals(Receipt.NOT_ACCEPTED, refused.receipt());
      assertEquals(
          Status.NOT_ACCEPTED,
          refused.completion().toCompletableFuture().join().outcome().status());
      release.countDown();
      var drained = services.storage(() -> 0).toCompletableFuture();
      // The worker can still be full immediately after release: drain completions before retrying
      // admission.
      pump(services, () -> services.state() == ServerServices.State.READY && (drained.isDone()));
      CompletableFuture<Optional<Outcome>> lookup;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      do {
        services.tick();
        Thread.sleep(1);
        lookup = journal.lookup(request).toCompletableFuture();
      } while (lookup.isCompletedExceptionally() && System.nanoTime() < deadline);
      var lookupResult = lookup;
      pump(services, lookupResult::isDone);
      assertTrue(lookupResult.join().isEmpty());
      var accepted = journal.submit(request).completion().toCompletableFuture();
      pump(services, accepted::isDone);
      assertEquals(Status.COMMITTED, accepted.join().outcome().status());
    } finally {
      release.countDown();
      services.stop(Duration.ofSeconds(3));
    }
  }
}
