package ca.emilybelnavis.mcmods.economy.foundation;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.clock.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class CalendarReportTest {
  private static final SchedulingMetrics METRICS =
      new SchedulingMetrics(
          new SchedulingMetrics.Queue(256, 0, 0),
          new SchedulingMetrics.Queue(64, 0, 0),
          new SchedulingMetrics.Queue(128, 0, 0),
          new StorageLifecycle.Progress("catch-up", 4, 1, false),
          new StorageLifecycle.EconomicProgress(2, 1000001, true, false),
          true,
          0);

  private CalendarDiagnostics sample() {
    var providers = new ArrayList<CalendarDiagnostics.Provider>();
    var policies =
        java.util.stream.IntStream.range(1, 17)
            .mapToObj(v -> new CalendarDiagnostics.PolicyVersion(100L * v, v))
            .toList();
    for (int i = 0; i < 64; i++) {
      String id = String.format(Locale.ROOT, "fixture:bank%02d", i);
      var event = new DeadlineDispatcher.Event(UUID.randomUUID(), id, 500, 3, 1);
      providers.add(
          new CalendarDiagnostics.Provider(
              id,
              policies,
              1,
              policies.get(1),
              new CalendarDiagnostics.ActiveEvent(event, "owner/last-target", 2)));
    }
    return new CalendarDiagnostics(
        Instant.ofEpochMilli(123),
        true,
        1,
        100L,
        123,
        1000000000L,
        1000000000L,
        500L,
        4,
        new CalendarDiagnostics.Position(0, 0, 0, 1200100L, 33600100L, 436800100L),
        providers.getFirst().activeEvent().event(),
        1,
        1000000,
        true,
        providers,
        List.of(
            new CalendarDiagnostics.FixedSystem(
                "fixture:fx", 1000, 0, 2000, 1000000000L, 1000000000L, 3000L, 1, 1000000, true)));
  }

  @Test
  void largeBacklogUsesQualifiedBoundedPagesWithoutAnEtaOrDebtCount() {
    var sample = sample();
    var seen = new HashSet<String>();
    String cursor = "";
    for (int page = 0; page < 20; page++) {
      var lines = CalendarReport.render(sample, ServerServices.State.READY, null, METRICS, cursor);
      assertTrue(lines.size() <= 35);
      assertTrue(lines.stream().allMatch(line -> line.length() < 1200));
      String text = String.join("\n", lines);
      assertTrue(
          text.contains(
              "fixed backlog 1000000 intervals; domain pending-event witness 1 (not a total obligation count)"));
      assertTrue(text.contains("WAITING_FOR_UTC_HIGH_WATER"));
      assertTrue(text.contains("zero-based counts"));
      assertFalse(text.contains("ETA"));
      for (String line : lines)
        if (line.startsWith("Calendar provider ")) assertTrue(seen.add(line));
      var next = lines.stream().filter(line -> line.startsWith("Calendar next page: ")).findFirst();
      if (next.isEmpty()) break;
      cursor =
          next.get().substring("Calendar next page: /economy diagnostics calendar after ".length());
    }
    assertEquals(64, seen.size());
  }

  @Test
  void pendingFailedAndOverflowSamplesStayExplicitAndSanitized() {
    var lines =
        CalendarReport.render(
            null, ServerServices.State.UNAVAILABLE, "/private/failure", METRICS, "");
    assertTrue(lines.getFirst().contains("inspection reason unknown"));
    assertFalse(String.join("\n", lines).contains("private"));
    assertEquals(
        List.of("Calendar page cursor invalid."),
        CalendarReport.render(sample(), ServerServices.State.READY, null, METRICS, "bad\nvalue"));
    var original = sample();
    var overflow =
        new CalendarDiagnostics(
            original.sampledAt(),
            true,
            1,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            0,
            new CalendarDiagnostics.Position(0, 0, 0, null, null, null),
            null,
            0,
            0,
            false,
            List.of(),
            List.of());
    assertTrue(
        String.join(
                "\n", CalendarReport.summary(overflow, ServerServices.State.READY, null, METRICS))
            .contains("unrepresentable (UTC overflow)"));
  }
}
