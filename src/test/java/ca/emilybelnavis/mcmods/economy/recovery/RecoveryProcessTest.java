package ca.emilybelnavis.mcmods.economy.recovery;

import static ca.emilybelnavis.mcmods.economy.recovery.RecoveryFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.clock.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryProcessTest {
  @TempDir Path temporary;

  private String killAtBoundary(Path world, String mode) throws Exception {
    Process child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp",
                System.getProperty("economy.test.classpath"),
                RecoveryProcessProbe.class.getName(),
                world.toString(),
                mode)
            .redirectErrorStream(true)
            .start();
    try {
      String line =
          CompletableFuture.supplyAsync(
                  () -> {
                    try {
                      return new java.io.BufferedReader(
                              new java.io.InputStreamReader(child.getInputStream()))
                          .readLine();
                    } catch (Exception e) {
                      throw new CompletionException(e);
                    }
                  })
              .get(15, TimeUnit.SECONDS);
      assertTrue(line.startsWith("BOUNDARY"), line);
      child.destroyForcibly();
      assertTrue(child.waitFor(10, TimeUnit.SECONDS));
      return line;
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        child.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void interruptedProjectionBatchResumesFromCommittedGenerationCheckpoint() throws Exception {
    Path world = temporary.toRealPath();
    create(world, 5);
    var store = open(world);
    var manager = new ProjectionManager(store, List.of(projection(1)));
    manager.step(1);
    String generation =
        store
            .transaction(
                tx ->
                    tx.query(
                        "SELECT generation FROM projection_generations WHERE state='BUILDING'",
                        List.of(),
                        1))
            .getFirst()
            .get("generation");
    store.close();
    killAtBoundary(world, "projection");
    store = open(world);
    manager = new ProjectionManager(store, List.of(projection(1)));
    finish(manager, 1);
    assertEquals(generation, manager.snapshot("fixture:count").generation());
    assertEquals("5", manager.snapshot("fixture:count").values().get("count"));
    assertEquals(5, authority(store));
    store.close();
  }

  @Test
  void economicDecisionSurvivesProcessBoundariesWithoutReroll() throws Exception {
    Path root = temporary.toRealPath();
    for (String mode : List.of("clock-before", "clock-after")) {
      Path world = Files.createDirectory(root.resolve(mode));
      create(world, 0);
      String boundary = killAtBoundary(world, mode);
      var store = open(world);
      int before = EconomicClockTest.updates(store).size();
      assertEquals(mode.equals("clock-after") ? 1 : 0, before);
      var clock =
          new EconomicClock(
              store,
              new EconomicClockTest.MutableClock(1000),
              List.of(RecoveryProcessProbe.system(false)));
      clock.prepare();
      assertTrue(clock.runBatch(16).ready());
      var rows = EconomicClockTest.updates(store);
      assertEquals(1, rows.size());
      if (mode.equals("clock-before")) {
        String[] values = boundary.split("\\|");
        assertEquals(values[1], rows.getFirst().get("update_id"));
        assertEquals(values[2], rows.getFirst().get("random_seed"));
      }
      clock.runBatch(16);
      assertEquals(rows, EconomicClockTest.updates(store));
      store.close();
    }
  }
}
