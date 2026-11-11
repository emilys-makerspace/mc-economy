package ca.emilybelnavis.mcmods.economy.recovery;

import static ca.emilybelnavis.mcmods.economy.recovery.RecoveryFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.storage.FixtureRecords;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeStartupTest {
  @TempDir Path temporary;

  @Test
  void readinessWaitsForJournalProjectionAndCatchUp() throws Exception {
    Path world = temporary.toRealPath();
    create(world, 5);
    var calls = new AtomicInteger();
    var config =
        new FoundationRuntime.Configuration(
            FixtureRecords.catalog(),
            List.of(projection(1)),
            List.of(EconomicClockTest.definition("fixture:clock", Set.of(), calls)),
            new EconomicClockTest.MutableClock(5000));
    var runtime = new FoundationRuntime(config);
    var services = new ServerServices(world, runtime);
    Set<String> phases = new HashSet<>();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (services.state() == ServerServices.State.INITIALIZING && System.nanoTime() < deadline) {
      assertThrows(IllegalStateException.class, services::requireReady);
      services.tick();
      phases.add(services.progress().phase());
      Thread.sleep(1);
    }
    assertEquals(
        ServerServices.State.READY, services.state(), () -> Objects.toString(services.failure()));
    assertEquals(5, calls.get());
    assertTrue(phases.stream().anyMatch(p -> p.startsWith("projection:")));
    assertTrue(phases.contains("projection-final-validation"));
    var result =
        services
            .storage(() -> runtime.projections().snapshot("fixture:count"))
            .toCompletableFuture();
    while (!result.isDone() && System.nanoTime() < deadline) {
      services.tick();
      Thread.sleep(1);
    }
    assertEquals("5", result.join().values().get("count"));
    assertEquals(10, result.join().sequence());
    assertTrue(services.stop(Duration.ofSeconds(3)));
    runtime.afterMinecraftStopped();
    assertTrue(Files.exists(world.resolve("economy/world-clean.properties")));
  }
}
