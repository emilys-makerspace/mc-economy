package ca.emilybelnavis.mcmods.economy.recovery;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.foundation.*;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CalendarFairnessTest {
  @TempDir Path temporary;

  private static final class Wall extends Clock {
    final AtomicLong now = new AtomicLong(123);

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return Instant.ofEpochMilli(now.get());
    }

    public long millis() {
      return now.get();
    }
  }

  private void pump(ServerServices services, BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      services.tick();
      assertNotEquals(
          ServerServices.State.UNAVAILABLE,
          services.state(),
          () -> Objects.toString(services.failure()));
      Thread.sleep(1);
    }
    assertTrue(condition.getAsBoolean(), "Fixture did not reach its bounded checkpoint");
  }

  @Test
  void ordinaryReadRunsBetweenRealDomainChildrenWhileFinancialWritesRemainGated() throws Exception {
    var wall = new Wall();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var children = new AtomicInteger();
    var event = new DeadlineDispatcher.Event(UUID.randomUUID(), "fixture:fairness", 124, 1, 1);
    var targets =
        java.util.stream.IntStream.range(0, 65)
            .mapToObj(i -> String.format(Locale.ROOT, "owner%03d", i))
            .toList();
    var provider =
        new DeadlineDispatcher.Provider() {
          public String id() {
            return event.provider();
          }

          public Map<Integer, String> versions() {
            return Map.of(1, "fairness-v1");
          }

          public DeadlineDispatcher.Event next(
              EconomicClock.Queries reads, long origin, DeadlineDispatcher.Event completed) {
            return completed == null ? event : null;
          }

          public List<String> targets(
              DeadlineDispatcher.Event due, String cursor, int limit, EconomicClock.Queries reads) {
            return targets.stream().filter(key -> key.compareTo(cursor) > 0).limit(limit).toList();
          }

          public EconomicClock.Output prepare(
              DeadlineDispatcher.Event due, String target, EconomicClock.Queries reads) {
            return new EconomicClock.Output(
                target,
                tx -> {
                  if (target.equals("owner000")) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS))
                      throw new IllegalStateException("Fixture release missing");
                  }
                  children.incrementAndGet();
                });
          }
        };
    var runtime =
        new FoundationRuntime(
            new FoundationRuntime.Configuration(
                RecordCatalog.core(),
                List.of(),
                List.of(),
                wall,
                Map.of(),
                true,
                List.of(provider),
                new FinancialCalendar.Config(16)));
    var services =
        new ServerServices(
            temporary.toRealPath(), runtime, new SchedulingConfig(1, 4, 1, 5, 1000, 3));
    try {
      pump(services, () -> services.state() == ServerServices.State.READY);
      wall.now.set(124);
      pump(services, () -> entered.getCount() == 0);
      // The child is deliberately held on the storage worker. Tick still returns without joining
      // it.
      assertTimeout(Duration.ofMillis(250), services::tick);
      var read = services.storage(runtime::readCalendarDiagnostics).toCompletableFuture();
      var admission =
          services
              .storage(
                  () -> {
                    try {
                      runtime.requireEconomicCurrent();
                      return false;
                    } catch (StorageException error) {
                      if (error.kind() != StorageException.Kind.ECONOMIC_CATCH_UP) throw error;
                      return true;
                    }
                  })
              .toCompletableFuture();
      assertFalse(read.isDone());
      release.countDown();
      pump(services, () -> read.isDone() && admission.isDone());
      assertEquals(
          1, read.get().completedChildren(), "FIFO read must precede another economic child");
      assertEquals(1, read.get().domainPendingWitness());
      assertTrue(admission.get(), "Incomplete event must continue to gate financial writes");
      assertTrue(children.get() < 65, "Ordinary work must not wait for the whole backlog");
      pump(services, () -> children.get() == 65 && services.metrics().economy().ready());
      var current =
          services
              .storage(
                  () -> {
                    runtime.requireEconomicCurrent();
                    return runtime.readCalendarDiagnostics();
                  })
              .toCompletableFuture();
      pump(services, current::isDone);
      assertEquals(65, current.get().completedChildren());
      assertEquals(0, current.get().domainPendingWitness());
    } finally {
      release.countDown();
      assertTrue(services.stop(Duration.ofSeconds(3)));
    }
  }
}
