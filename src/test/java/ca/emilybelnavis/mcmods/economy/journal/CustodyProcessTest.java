package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD;
import static ca.emilybelnavis.mcmods.economy.journal.Custody.Action.*;
import static ca.emilybelnavis.mcmods.economy.journal.CustodyProcessProbe.*;
import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.RequestId;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CustodyProcessTest {
  @TempDir Path temporary;

  @Test
  void forcedProcessDeathAtCustodyBoundariesPreservesIdentityAndQuarantinesUncertainty()
      throws Exception {
    var boundaries =
        List.of(
            "reservation-save-before",
            "reservation-save-after",
            "financial-before",
            "financial-after",
            "physical-before",
            "physical-after",
            "ack-before",
            "ack-after",
            "inventory-save-before",
            "inventory-save-after");
    for (int index = 0; index < boundaries.size(); index++) {
      var boundary = boundaries.get(index);
      var world = Files.createDirectory(temporary.resolve(boundary)).toRealPath();
      var process =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin/java").toString(),
                  "-cp",
                  System.getProperty("economy.test.classpath"),
                  CustodyProcessProbe.class.getName(),
                  world.toString(),
                  boundary)
              .redirectErrorStream(true)
              .start();
      try {
        var line =
            CompletableFuture.supplyAsync(() -> awaitBoundary(process)).get(15, TimeUnit.SECONDS);
        assertEquals("BOUNDARY " + boundary, line);
        process.destroyForcibly();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        verifyRecovered(world, index, boundary);
      } finally {
        if (process.isAlive()) {
          process.destroyForcibly();
          assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
      }
    }
  }

  private static String awaitBoundary(Process process) {
    try {
      var input = new BufferedReader(new InputStreamReader(process.getInputStream()));
      String line;
      var output = new StringBuilder();
      while ((line = input.readLine()) != null) {
        if (line.startsWith("BOUNDARY ")) return line;
        if (output.length() < 16_384) output.append(line).append('\n');
      }
      throw new IllegalStateException(
          "Child exited without boundary, exit " + process.waitFor() + "\n" + output);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new CompletionException(error);
    }
  }

  private static void verifyRecovered(Path world, int index, String boundary) throws Exception {
    var store = new SqliteStorage();
    store.initialize(world);
    try {
      var current =
          store.transaction(tx -> Custody.find(tx::query, OWNER, CASE, false)).orElseThrow();
      assertEquals(expectedState(index), current.state(), boundary);
      boolean funded = index >= 3;
      assertEquals(funded ? 9_000L : 10_000L, available(store, SOURCE), boundary);
      assertEquals(funded ? 1_000L : 0L, available(store, TARGET), boundary);
      assertEquals(index >= 1, Files.exists(world.resolve(SOURCE_FILE)), boundary);
      assertEquals(index >= 5, Files.exists(world.resolve("physical-delivery.marker")), boundary);
      assertEquals(index == 9, Files.exists(world.resolve(DESTINATION_FILE)), boundary);
      assertEquals(index >= 7, current.acknowledgment() != null, boundary);
      assertEquals(index == 9, current.deliveryWitness() != null, boundary);

      var commitEngine = engine(store, world, COMMIT);
      var original = commit();
      var prior = commitEngine.lookup(original);
      assertEquals(funded, prior.isPresent(), boundary);
      // Finish only the independently proven reservation. Do not infer delivery from a missing
      // marker.
      if (current.state() == Custody.State.RESERVED) {
        if (!Files.exists(world.resolve(SOURCE_FILE)))
          Files.write(world.resolve(SOURCE_FILE), RESERVED_BYTES);
        assertEquals(
            Status.COMMITTED,
            engine(store, world, RESERVATION_SAVED).submit(reservationSaved(store)).status());
      }
      var retry =
          new Intent(
              original.operationId(),
              new RequestId(UUID.randomUUID()),
              original.actor(),
              original.kind(),
              original.request(),
              null,
              null);
      var result = commitEngine.submit(retry);
      assertEquals(Status.COMMITTED, result.status(), boundary);
      if (prior.isPresent()) assertEquals(prior.orElseThrow(), result, boundary);
      assertEquals(result, commitEngine.submit(retry), boundary);
      assertEquals(9_000L, available(store, SOURCE), boundary);
      assertEquals(1_000L, available(store, TARGET), boundary);
      assertEquals(
          "1",
          store.transaction(
              tx ->
                  tx.query(
                          "SELECT count(*) AS total FROM operation_outcomes WHERE operation_id=? AND status='COMMITTED'",
                          List.of(original.operationId().value().toString()),
                          1)
                      .getFirst()
                      .get("total")),
          boundary);
      assertEquals(index >= 5, Files.exists(world.resolve("physical-delivery.marker")), boundary);
      assertEquals(index == 9, Files.exists(world.resolve(DESTINATION_FILE)), boundary);

      assertTrue(Custody.reconcileBatch(store));
      var reconciled =
          store.transaction(tx -> Custody.find(tx::query, OWNER, CASE, false)).orElseThrow();
      assertEquals(Custody.State.QUARANTINED, reconciled.state(), boundary);
      assertTrue(
          store.transaction(tx -> Custody.claims(tx::query, OWNER, null, 63)).cases().isEmpty(),
          boundary);
      assertEquals(
          Status.DURABLY_REJECTED,
          engine(store, world, CLAIM)
              .submit(transition(CLAIM, ATTEMPT, Custody.Evidence.unknown(), id("unproven-claim")))
              .status(),
          boundary);
      assertEquals(9_000L, available(store, SOURCE), boundary);
      assertEquals(1_000L, available(store, TARGET), boundary);
    } finally {
      store.close();
    }
  }

  private static Custody.State expectedState(int index) {
    if (index < 2) return Custody.State.RESERVED;
    if (index == 2) return Custody.State.WITNESSED;
    if (index == 3) return Custody.State.COMMITTED;
    if (index < 7) return Custody.State.DELIVERING;
    if (index < 9) return Custody.State.ACKNOWLEDGED;
    return Custody.State.DELIVERED;
  }

  private static long available(SqliteStorage store, UUID account) {
    return store.transaction(tx -> Holds.available(tx::query, account, CAD));
  }
}
