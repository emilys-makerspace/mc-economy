package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.JournalFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalProcessTest {
  @TempDir Path temporary;

  @Test
  void processDeathBeforeAndAfterCommitResolvesThroughOriginalOperation() throws Exception {
    for (String boundary : new String[] {"before", "after"}) {
      Path world = Files.createDirectory(temporary.toRealPath().resolve(boundary));
      createWorld(world);
      var request = intent();
      Process process =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin/java").toString(),
                  "-cp",
                  System.getProperty("economy.test.classpath"),
                  JournalProcessProbe.class.getName(),
                  world.toString(),
                  boundary,
                  request.operationId().value().toString())
              .redirectErrorStream(true)
              .start();
      try {
        String line =
            CompletableFuture.supplyAsync(
                    () -> {
                      try {
                        return new java.io.BufferedReader(
                                new java.io.InputStreamReader(process.getInputStream()))
                            .readLine();
                      } catch (Exception e) {
                        throw new CompletionException(e);
                      }
                    })
                .get(15, TimeUnit.SECONDS);
        assertEquals("BOUNDARY", line);
        process.destroyForcibly();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        var store =
            new SqliteStorage(ca.emilybelnavis.mcmods.economy.storage.FixtureRecords.catalog());
        store.initialize(world);
        var engine = engine(store, new AtomicInteger());
        var prior = engine.lookup(request);
        if (boundary.equals("before")) {
          assertTrue(prior.isEmpty());
          assertEquals(0, counter(store));
        } else {
          assertEquals(Status.COMMITTED, prior.orElseThrow().status());
          assertEquals(1, counter(store));
        }
        var result = engine.submit(request);
        assertEquals(Status.COMMITTED, result.status());
        assertEquals(1, counter(store));
        if (prior.isPresent()) assertEquals(prior.orElseThrow(), result);
        assertEquals(1, count(store, "journal_transactions"));
        assertEquals(2, count(store, "journal_records"));
        store.close();
      } finally {
        if (process.isAlive()) {
          process.destroyForcibly();
          process.waitFor(5, TimeUnit.SECONDS);
        }
      }
    }
  }
}
