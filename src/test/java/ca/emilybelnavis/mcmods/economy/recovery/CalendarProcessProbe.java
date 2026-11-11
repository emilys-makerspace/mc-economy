package ca.emilybelnavis.mcmods.economy.recovery;

import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/**
 * Standalone disposable JVM only. Deliberately waits for its parent at exact transaction
 * boundaries.
 */
public final class CalendarProcessProbe {
  public static final long ORIGIN = 123;
  public static final UUID FACILITY = UUID.fromString("00000000-0000-0000-0000-000000000603");

  private CalendarProcessProbe() {}

  public static DeadlineDispatcher.Event event(UUID world) {
    var boundary =
        FinancialCalendar.boundary(world, FACILITY, ORIGIN, FinancialCalendar.Period.MONTH, 1);
    return new DeadlineDispatcher.Event(
        boundary.id(), "fixture:calendar-process", boundary.instantMillis(), 2, 1);
  }

  public static UUID operation(UUID world, UUID event, String target) {
    String key = event + "|" + (target.isEmpty() ? "complete" : "target|" + target);
    return UUID.nameUUIDFromBytes(
        ("operation|" + world + "|" + key).getBytes(StandardCharsets.UTF_8));
  }

  public static DeadlineDispatcher.Provider provider(UUID world, boolean pauseBeforeChild) {
    return new DeadlineDispatcher.Provider() {
      public String id() {
        return "fixture:calendar-process";
      }

      public Map<Integer, String> versions() {
        return Map.of(1, "calendar-process-v1");
      }

      public DeadlineDispatcher.Event next(
          EconomicClock.Queries reads, long origin, DeadlineDispatcher.Event completed) {
        if (origin != ORIGIN) throw new IllegalStateException("Fixture origin changed");
        return completed == null ? event(world) : null;
      }

      public List<String> targets(
          DeadlineDispatcher.Event event, String cursor, int limit, EconomicClock.Queries reads) {
        return List.of("a", "b", "c").stream()
            .filter(key -> key.compareTo(cursor) > 0)
            .limit(limit)
            .toList();
      }

      public EconomicClock.Output prepare(
          DeadlineDispatcher.Event event, String target, EconomicClock.Queries reads) {
        return new EconomicClock.Output(
            target,
            tx -> {
              tx.execute(
                  "INSERT INTO fixture_calendar_effects VALUES(?,?)",
                  List.of(event.id().toString(), target));
              if (pauseBeforeChild) boundary(world, event.id(), target);
            });
      }
    };
  }

  private static void boundary(UUID world, UUID event, String target) throws java.io.IOException {
    System.out.println("BOUNDARY|" + event + "|" + operation(world, event, target));
    System.out.flush();
    if (System.in.read() < 0) throw new IllegalStateException("Parent closed fixture input");
  }

  public static void main(String[] args) throws Exception {
    var actual = new SqliteStorage();
    actual.initialize(Path.of(args[0]));
    String mode = args[1];
    UUID world = actual.snapshot().worldId().value();
    boolean[] armed = {false};
    FoundationStore observed =
        new FoundationStore() {
          public void initialize(Path path) {
            throw new UnsupportedOperationException();
          }

          public Snapshot snapshot() {
            return actual.snapshot();
          }

          public long lastCommittedSequence() {
            return actual.lastCommittedSequence();
          }

          public RecordCatalog recordCatalog() {
            return actual.recordCatalog();
          }

          public void recover() {
            actual.recover();
          }

          public void close() throws Exception {
            actual.close();
          }

          public <T> T transaction(Work<T> work) {
            T result = actual.transaction(work);
            if (armed[0]) {
              var rows =
                  actual.transaction(
                      tx ->
                          tx.query(
                              "SELECT state,completed_effects FROM financial_deadline_events WHERE event_id=?",
                              List.of(event(world).id().toString()),
                              1));
              if (!rows.isEmpty()
                  && (mode.equals("after-child")
                          && rows.getFirst().get("completed_effects").equals("1")
                      || mode.equals("after-completion")
                          && rows.getFirst().get("state").equals("COMPLETE"))) {
                armed[0] = false;
                try {
                  boundary(world, event(world).id(), mode.equals("after-child") ? "a" : "");
                } catch (java.io.IOException error) {
                  throw new IllegalStateException(error);
                }
              }
            }
            return result;
          }
        };
    var wall = new EconomicClockTest.MutableClock(ORIGIN);
    var dispatcher =
        new DeadlineDispatcher(
            observed, wall, List.of(provider(world, mode.equals("before-child"))), 2);
    dispatcher.prepare();
    wall.now = event(world).effectiveMillis();
    armed[0] = true;
    for (int turn = 0; turn < 5; turn++) dispatcher.advance();
    throw new IllegalStateException("Fixture did not reach requested boundary");
  }
}
