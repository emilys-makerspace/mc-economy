package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ServerServicesTest {
    @TempDir Path world;
    private void ready(ServerServices services) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (services.state() == ServerServices.State.INITIALIZING && System.nanoTime() < deadline) {
            services.tick(); Thread.sleep(1);
        }
        assertEquals(ServerServices.State.READY, services.state());
    }
    @Test void fxDiagnosticsUsesDetachedSampleWhenStorageAdmissionIsFull() throws Exception {
        var runtime = new ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime(
                ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime.Configuration.production(java.time.Clock.systemUTC()));
        var services = new ServerServices(world.toRealPath(), runtime, new SchedulingConfig(1, 2, 1, 5, 1000, 3));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            ready(services);
            assertTrue(services.diagnostics(true, true).contains("FX awaiting world currency selection."));
            assertTrue(services.calendarDiagnostics("").stream().anyMatch(line -> line.contains("Calendar v1 origin")));
            services.storage(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            services.storage(() -> null);
            services.storage(() -> null);
            assertTrue(services.storage(() -> null).toCompletableFuture().isCompletedExceptionally());
            assertTrue(services.diagnostics(true, true).stream().anyMatch(line -> line.contains("STORAGE_OVERLOAD")));
            assertTrue(services.calendarDiagnostics("").stream().anyMatch(line -> line.contains("STORAGE_OVERLOAD")));
            assertTrue(services.calendarDiagnostics("").stream().anyMatch(line -> line.contains("Calendar v1 origin")));
            assertFalse(services.diagnostics(false, true).stream().anyMatch(line -> line.contains("Calendar")));
            assertFalse(services.diagnostics(true, false).stream().anyMatch(line -> line.contains("Calendar")));
            assertFalse(services.diagnostics(false, true).stream().anyMatch(line -> line.contains("FX")));
            assertFalse(services.diagnostics(true, false).stream().anyMatch(line -> line.contains("FX")));
        } finally {
            release.countDown();
            assertTrue(services.stop(Duration.ofSeconds(3)));
        }
    }

    @Test void lifecycleIsolationAndDisposal() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        StorageLifecycle adapter = new StorageLifecycle() {
            Thread owner;
            public void initialize(Path path) { owner = Thread.currentThread(); }
            public void close() { assertSame(owner, Thread.currentThread()); closes.incrementAndGet(); }
        };
        var first = new ServerServices(world.resolve("one"), adapter);
        assertThrows(IllegalStateException.class, first::requireReady);
        ready(first);
        assertTrue(first.stop(Duration.ofSeconds(3)));
        assertTrue(first.stop(Duration.ofSeconds(3)));
        assertEquals(1, closes.get());
        assertThrows(IllegalStateException.class, first::requireReady);
        var second = new ServerServices(world.resolve("two"), StorageLifecycle.foundationOnly());
        ready(second);
        assertNotEquals(first.sessionId(), second.sessionId());
        assertEquals(ServerServices.State.STOPPED, first.state());
        assertTrue(second.stop(Duration.ofSeconds(3)));
    }
    @Test void failedInitializationStaysUnavailable() throws Exception {
        var services = new ServerServices(world, new StorageLifecycle() {
            public void initialize(Path path) { throw new IllegalStateException("fixture failure"); }
            public void close() {}
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (services.state() == ServerServices.State.INITIALIZING && System.nanoTime() < deadline) {
            services.tick(); Thread.sleep(1);
        }
        assertEquals(ServerServices.State.UNAVAILABLE, services.state());
        assertThrows(IllegalStateException.class, services::requireReady);
        assertThrows(CompletionException.class, () -> services.readiness().toCompletableFuture().join());
        services.stop(Duration.ofSeconds(3));
    }
    @Test void boundedWorkAndOwnerThread() throws Exception {
        var services = new ServerServices(world, StorageLifecycle.foundationOnly());
        ready(services);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 2; i++) services.calculate(() -> {
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return 1;
            });
            for (int i = 0; i < ServerServices.CALCULATION_CAPACITY; i++) services.calculate(() -> 2);
            var rejected = services.calculate(() -> 3).toCompletableFuture();
            assertTrue(rejected.isCompletedExceptionally());
            CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class, services::requireReady)).join();
        } finally { release.countDown(); services.stop(Duration.ofSeconds(3)); }
    }
    @Test void callbackCompletesOnlyOnTick() throws Exception {
        var services = new ServerServices(world, StorageLifecycle.foundationOnly());
        ready(services);
        CountDownLatch ran = new CountDownLatch(1);
        var result = services.calculate(() -> { ran.countDown(); return 42; }).toCompletableFuture();
        assertTrue(ran.await(3, TimeUnit.SECONDS));
        assertFalse(result.isDone());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!result.isDone() && System.nanoTime() < deadline) { services.tick(); Thread.sleep(1); }
        assertEquals(42, result.join());
        assertTrue(services.stop(Duration.ofSeconds(3)));
    }
    @Test void disposalFailureIsReportedOnRepeatedStop() throws Exception {
        var services = new ServerServices(world, new StorageLifecycle() {
            public void initialize(Path path) {}
            public void close() { throw new IllegalStateException("close failed"); }
        });
        ready(services);
        assertFalse(services.stop(Duration.ofSeconds(3)));
        assertFalse(services.stop(Duration.ofSeconds(3)));
        assertNotNull(services.failure());
    }
    @Test void timeoutRejectsOutstandingRequests() throws Exception {
        var services = new ServerServices(world, StorageLifecycle.foundationOnly());
        ready(services);
        CountDownLatch started = new CountDownLatch(1);
        var result = services.calculate(() -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return 1;
        }).toCompletableFuture();
        assertTrue(started.await(3, TimeUnit.SECONDS));
        assertFalse(services.stop(Duration.ZERO));
        assertTrue(result.isCompletedExceptionally());
        assertFalse(services.stop(Duration.ZERO));
    }
    @Test void stalledEconomicCalculationDoesNotBlockStorageOrCloseFromTickThread() throws Exception {
        var calculating = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var storageOwner = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var commits = new AtomicInteger();
        var adapter = new StorageLifecycle() {
            public void initialize(Path path) { storageOwner.set(Thread.currentThread()); }
            public void close() { assertSame(storageOwner.get(), Thread.currentThread()); }
            public boolean schedulesEconomy() { return true; }
            public EconomicStep<Integer> prepareEconomicStep() {
                assertSame(storageOwner.get(), Thread.currentThread());
                return new EconomicStep<>(new EconomicProgress(0, 1, false, false), () -> {
                    assertNotSame(storageOwner.get(), Thread.currentThread());
                    calculating.countDown();
                    while (release.getCount() > 0) {
                        try { release.await(); } catch (InterruptedException ignored) { /* Simulate stalled code. */ }
                    }
                    return 1;
                }, result -> {
                    commits.incrementAndGet();
                    return new EconomicProgress(1, 0, false, true);
                });
            }
        };
        var services = new ServerServices(world, adapter, new SchedulingConfig(1, 2, 2, 1, 5000, 1));
        try {
            ready(services);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (calculating.getCount() > 0 && System.nanoTime() < deadline) {
                services.tick(); Thread.sleep(1);
            }
            assertEquals(0, calculating.getCount());
            assertTrue(services.metrics().economicStepInFlight());
            assertTrue(services.metrics().calculation().oldestActiveAgeNanos() > 0);
            assertEquals(1, services.metrics().economy().outstandingIntervals());
            var storageRead = services.storage(() -> 42).toCompletableFuture();
            while (!storageRead.isDone() && System.nanoTime() < deadline) {
                services.tick(); Thread.sleep(1);
            }
            assertTrue(storageRead.isDone());
            assertEquals(42, storageRead.join());
            assertFalse(services.stop(Duration.ofMillis(20)));
            assertEquals(1, services.metrics().unresolvedAtShutdown());
            assertEquals(0, commits.get());
        } finally {
            release.countDown();
            services.stop(Duration.ofSeconds(3));
        }
    }

    @Test void payloadBounds() {
        ca.emilybelnavis.mcmods.economy.api.RequestContext.validatePayloadSize(16_384);
        assertThrows(IllegalArgumentException.class,
            () -> ca.emilybelnavis.mcmods.economy.api.RequestContext.validatePayloadSize(16_385));
        assertThrows(IllegalArgumentException.class,
            () -> ca.emilybelnavis.mcmods.economy.api.RequestContext.validatePayloadSize(-1));
    }
    @Test void realStorageFailurePropagatesUnavailable() throws Exception {
        java.nio.file.Files.writeString(world.resolve("economy"), "not a directory");
        var services = new ServerServices(world, new ca.emilybelnavis.mcmods.economy.storage.SqliteStorage());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (services.state() == ServerServices.State.INITIALIZING && System.nanoTime() < deadline) {
            services.tick(); Thread.sleep(1);
        }
        assertEquals(ServerServices.State.UNAVAILABLE, services.state());
        assertInstanceOf(ca.emilybelnavis.mcmods.economy.storage.StorageException.class, services.failure());
        assertThrows(IllegalStateException.class, services::requireReady);
        assertTrue(services.stop(Duration.ofSeconds(3)));
    }
    @Test void storageQueueOverloadIsTyped() throws Exception {
        var services = new ServerServices(world, StorageLifecycle.foundationOnly());
        ready(services);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            services.storage(() -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return 1;
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < ServerServices.STORAGE_CAPACITY; i++) services.storage(() -> 2);
            var rejected = services.storage(() -> 3).toCompletableFuture();
            var error = assertThrows(CompletionException.class, rejected::join);
            assertEquals(ca.emilybelnavis.mcmods.economy.storage.StorageException.Kind.OVERLOAD,
                    assertInstanceOf(ca.emilybelnavis.mcmods.economy.storage.StorageException.class, error.getCause()).kind());
        } finally { release.countDown(); services.stop(Duration.ofSeconds(3)); }
    }

    @Test void cleanShutdownDrainsAcceptedDatabaseWritesWithoutPublishingLateCallbacks() throws Exception {
        var store = new SqliteStorage();
        var services = new ServerServices(world.toRealPath(), store);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var owner = UUID.randomUUID().toString();
        var firstAccount = UUID.randomUUID().toString();
        var secondAccount = UUID.randomUUID().toString();
        try {
            ready(services);
            var first = services.storage(() -> {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return store.transaction(tx -> tx.execute(
                        "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,0)", List.of(firstAccount, owner)));
            }).toCompletableFuture();
            assertTrue(started.await(3, TimeUnit.SECONDS));
            var second = services.storage(() -> store.transaction(tx -> tx.execute(
                    "INSERT INTO account_catalog VALUES(?,?,'BANK','USD','OPEN',1,0)", List.of(secondAccount, owner))))
                    .toCompletableFuture();
            var published = new AtomicInteger();
            first.thenRun(published::incrementAndGet);
            second.thenRun(published::incrementAndGet);
            release.countDown();

            assertTrue(services.stop(Duration.ofSeconds(3)));
            assertTrue(first.isCompletedExceptionally());
            assertTrue(second.isCompletedExceptionally());
            assertEquals(0, published.get(), "Stopped menus must not receive successful callbacks");
            assertThrows(IllegalStateException.class, () -> services.storage(() -> 1));
        } finally {
            release.countDown();
            services.stop(Duration.ofSeconds(3));
        }
        var reopened = new SqliteStorage();
        try {
            reopened.initialize(world.toRealPath());
            var accounts = reopened.transaction(tx -> tx.query(
                    "SELECT account_id FROM account_catalog WHERE owner_id=? ORDER BY account_id", List.of(owner), 2));
            assertEquals(java.util.Set.of(firstAccount, secondAccount), accounts.stream()
                    .map(row -> row.get("account_id")).collect(java.util.stream.Collectors.toSet()));
        } finally { reopened.close(); }
    }

    @Test void oneActorsReadBacklogCannotConsumeAnotherActorsAdmissionAndCapacityRecovers() throws Exception {
        var store = new SqliteStorage();
        var services = new ServerServices(world.toRealPath(), store, new SchedulingConfig(1, 4, 1, 5, 1000, 3));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstActor = new PlayerId(UUID.randomUUID());
        var otherActor = new PlayerId(UUID.randomUUID());
        try {
            ready(services);
            var blocked = services.storage(firstActor, () -> {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return 1;
            }).toCompletableFuture();
            assertTrue(started.await(3, TimeUnit.SECONDS));
            var queued = services.storage(firstActor, () -> store.transaction(tx -> tx.query(
                    "SELECT world_id FROM foundation_metadata WHERE singleton=1", List.of(), 1))).toCompletableFuture();
            var rejected = services.storage(firstActor, () -> 3).toCompletableFuture();
            var error = assertThrows(CompletionException.class, rejected::join);
            assertEquals(StorageException.Kind.OVERLOAD, assertInstanceOf(StorageException.class, error.getCause()).kind());
            var other = services.storage(otherActor, () -> store.transaction(tx -> tx.query(
                    "SELECT world_id FROM foundation_metadata WHERE singleton=1", List.of(), 1))).toCompletableFuture();
            assertFalse(other.isCompletedExceptionally());
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((!blocked.isDone() || !queued.isDone() || !other.isDone()) && System.nanoTime() < deadline) {
                services.tick(); Thread.sleep(1);
            }
            assertEquals(1, blocked.get(1, TimeUnit.SECONDS));
            assertEquals(queued.get(1, TimeUnit.SECONDS), other.get(1, TimeUnit.SECONDS));
            var recovered = services.storage(firstActor, () -> 4).toCompletableFuture();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!recovered.isDone() && System.nanoTime() < deadline) { services.tick(); Thread.sleep(1); }
            assertEquals(4, recovered.get(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            assertTrue(services.stop(Duration.ofSeconds(3)));
        }
    }

}
