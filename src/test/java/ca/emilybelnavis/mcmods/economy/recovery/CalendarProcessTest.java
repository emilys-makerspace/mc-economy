package ca.emilybelnavis.mcmods.economy.recovery;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CalendarProcessTest {
  @TempDir Path temporary;

  private void seed(Path world) throws Exception {
    var store = new SqliteStorage();
    store.initialize(world);
    store.close();
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + world.resolve("economy/economy.sqlite"));
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE fixture_calendar_effects(event TEXT NOT NULL,target TEXT NOT NULL,PRIMARY KEY(event,target))");
    }
  }

  private String killOnlyFixture(Path world, String mode) throws Exception {
    var child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp",
                System.getProperty("economy.test.classpath"),
                CalendarProcessProbe.class.getName(),
                world.toString(),
                mode)
            .redirectErrorStream(true)
            .start();
    try {
      String boundary =
          CompletableFuture.supplyAsync(
                  () -> {
                    var output = new StringBuilder();
                    try {
                      var reader =
                          new java.io.BufferedReader(
                              new java.io.InputStreamReader(child.getInputStream()));
                      for (int i = 0; i < 32; i++) {
                        String line = reader.readLine();
                        if (line == null) break;
                        if (line.startsWith("BOUNDARY|")) return line;
                        output.append(line).append('\n');
                      }
                      throw new IllegalStateException("No fixture boundary: " + output);
                    } catch (java.io.IOException error) {
                      throw new CompletionException(error);
                    }
                  })
              .get(15, TimeUnit.SECONDS);
      child.destroyForcibly();
      assertTrue(child.waitFor(10, TimeUnit.SECONDS));
      assertNotEquals(0, child.exitValue());
      return boundary;
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        assertTrue(child.waitFor(5, TimeUnit.SECONDS));
      }
    }
  }

  private List<Map<String, String>> history(SqliteStorage store) {
    return store.transaction(
        tx ->
            tx.query(
                "SELECT operation_id,transaction_id,sequence,canonical_request,result_code,result_payload "
                    + "FROM operation_outcomes WHERE operation_type='economy:a6/calendar/advance' ORDER BY sequence",
                List.of(),
                8));
  }

  @Test
  void actualProcessDeathAtMonthBoundaryPreservesOnlyCommittedChildrenAndCompletionIdentities()
      throws Exception {
    for (String mode : List.of("before-child", "after-child", "after-completion")) {
      Path world = Files.createDirectory(temporary.toRealPath().resolve(mode));
      seed(world);
      String[] boundary = killOnlyFixture(world, mode).split("\\|");
      var store = new SqliteStorage();
      store.initialize(world);
      try {
        UUID worldId = store.snapshot().worldId().value();
        var event = CalendarProcessProbe.event(worldId);
        assertEquals(event.id().toString(), boundary[1]);
        assertEquals(
            CalendarProcessProbe.operation(
                    worldId, event.id(), mode.equals("after-completion") ? "" : "a")
                .toString(),
            boundary[2]);
        var prefix = history(store);
        assertEquals(
            mode.equals("before-child") ? 0 : mode.equals("after-child") ? 1 : 4, prefix.size());
        int expectedEffects = mode.equals("before-child") ? 0 : mode.equals("after-child") ? 1 : 3;
        assertEquals(
            expectedEffects,
            store
                .transaction(
                    tx -> tx.query("SELECT target FROM fixture_calendar_effects", List.of(), 4))
                .size());
        var wall = new EconomicClockTest.MutableClock(event.effectiveMillis());
        var dispatcher =
            new DeadlineDispatcher(
                store, wall, List.of(CalendarProcessProbe.provider(worldId, false)), 2);
        dispatcher.prepare();
        for (int turn = 0; !dispatcher.progress().ready(); turn++) {
          assertTrue(turn < 6);
          dispatcher.advance();
        }
        var completed = history(store);
        assertEquals(4, completed.size());
        assertEquals(prefix, completed.subList(0, prefix.size()));
        assertEquals(
            List.of("a", "b", "c"),
            store
                .transaction(
                    tx ->
                        tx.query(
                            "SELECT target FROM fixture_calendar_effects ORDER BY target",
                            List.of(),
                            4))
                .stream()
                .map(row -> row.get("target"))
                .toList());
        assertEquals(
            List.of("a", "b", "c", "").stream()
                .map(
                    target ->
                        CalendarProcessProbe.operation(worldId, event.id(), target).toString())
                .toList(),
            completed.stream().map(row -> row.get("operation_id")).toList());
        assertEquals(
            "COMPLETE",
            store
                .transaction(
                    tx ->
                        tx.query(
                            "SELECT state FROM financial_deadline_events WHERE event_id=?",
                            List.of(event.id().toString()),
                            1))
                .getFirst()
                .get("state"));
        // Actual A1 reconnect/compatibility validation, then replay only stored outcomes.
        store.recover();
        dispatcher.prepare();
        dispatcher.advance();
        assertEquals(completed, history(store));
        assertEquals(3, dispatcher.progress().completedEffects());
      } finally {
        store.close();
      }
    }
  }
}
