package ca.emilybelnavis.mcmods.economy.foundation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DiagnosticsReportTest {
  private static final UUID WORLD = UUID.randomUUID();
  private static final UUID CORRELATION = UUID.randomUUID();
  private static final SchedulingMetrics METRICS =
      new SchedulingMetrics(
          new SchedulingMetrics.Queue(256, 0, 0, 6_000_000_000L),
          new SchedulingMetrics.Queue(64, 1, 1_000_000L),
          new SchedulingMetrics.Queue(128, 0, 0),
          new StorageLifecycle.Progress("projection:private-name", 3, 9, false),
          new StorageLifecycle.EconomicProgress(5, 2, false, false),
          true,
          4);

  @Test
  void playersSeeOnlySafeAvailabilityAndProgressForBothCommands() {
    for (boolean detailed : new boolean[] {false, true}) {
      assertEquals(
          List.of("Economy is starting (projection rebuild)."),
          DiagnosticsReport.render(
              false,
              detailed,
              ServerServices.State.INITIALIZING,
              METRICS,
              new DiagnosticsReport.StorageDetails(WORLD, 1, 12, "healthy"),
              "STORAGE_IDENTITY",
              CORRELATION,
              2.5));
    }
  }

  @Test
  void operatorsSeeDetachedMetricsAndOnlyControlledText() {
    String report =
        String.join(
            "\n",
            DiagnosticsReport.render(
                true,
                true,
                ServerServices.State.STOPPED,
                METRICS,
                new DiagnosticsReport.StorageDetails(WORLD, 1, 12, "/private-health"),
                "/private-failure",
                CORRELATION,
                null));
    assertTrue(
        report.contains(
            "Storage unknown, world " + WORLD + ", schema 1, last committed sequence 12"));
    assertTrue(report.contains("projection rebuild, checkpoint 3, target 9"));
    assertTrue(report.contains("frontier 5, pending work units 2, observed rate unavailable"));
    assertTrue(report.contains("oldest active 6000 ms, possible stall"));
    assertTrue(report.contains("Unresolved at shutdown 4"));
    assertTrue(report.contains("Last failure unknown, correlation " + CORRELATION));
    assertFalse(report.contains("private"));
    assertFalse(report.contains("ETA"));
  }

  @Test
  void fxViewsDistinguishWaitingBlockedAndRetryableReasons() {
    var day = java.time.LocalDate.parse("2026-11-03");
    var now = java.time.Instant.parse("2026-11-03T19:00:00Z");
    var pending =
        new ca.emilybelnavis.mcmods.economy.fx.FxDiagnostics.PendingPolicy(
            day.plusDays(1), CORRELATION);
    var awaiting =
        new ca.emilybelnavis.mcmods.economy.fx.FxDiagnostics(
            now, null, null, null, null, 0, false, null);
    var healthy =
        new ca.emilybelnavis.mcmods.economy.fx.FxDiagnostics(
            now,
            ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD,
            day,
            WORLD,
            day.plusDays(1),
            0,
            false,
            pending);
    var overdue =
        new ca.emilybelnavis.mcmods.economy.fx.FxDiagnostics(
            now, healthy.reference(), day, WORLD, day.plusDays(1), 2, false, pending);
    var waiting =
        new ca.emilybelnavis.mcmods.economy.fx.FxDiagnostics(
            now, healthy.reference(), day, WORLD, day.plusDays(1), 2, true, pending);
    assertEquals("FX awaiting world currency selection.", fx(awaiting, null).getFirst());
    var current = fx(healthy, null);
    assertEquals("FX current.", current.getFirst());
    assertTrue(current.get(1).contains("effective policy " + WORLD));
    assertTrue(current.get(4).contains("pending policy " + CORRELATION));
    assertEquals("FX catch-up due.", fx(overdue, null).getFirst());
    assertTrue(fx(overdue, null).get(2).contains("backlog 2"));
    assertEquals("FX waiting for UTC wall clock.", fx(waiting, null).getFirst());
    assertTrue(fx(healthy, "STORAGE_OVERLOAD").getFirst().contains("can retry (STORAGE_OVERLOAD)"));
    assertTrue(
        fx(healthy, "STORAGE_LOCK_TIMEOUT")
            .getFirst()
            .contains("can retry (STORAGE_LOCK_TIMEOUT)"));
    for (String reason : List.of("CONFIGURATION_INVALID", "STORAGE_UNSAFE_CONNECTION")) {
      assertEquals(
          "FX publication blocked (" + reason + ").",
          DiagnosticsReport.withFx(List.of(), ServerServices.State.UNAVAILABLE, null, reason)
              .getFirst());
    }
  }

  private static List<String> fx(
      ca.emilybelnavis.mcmods.economy.fx.FxDiagnostics snapshot, String reason) {
    return DiagnosticsReport.withFx(List.of(), ServerServices.State.READY, snapshot, reason);
  }

  @Test
  void operatorStatusShowsMeasuredRateAndControlledFailure() {
    String status =
        String.join(
            "\n",
            DiagnosticsReport.render(
                true,
                false,
                ServerServices.State.READY,
                METRICS,
                null,
                "STORAGE_IDENTITY",
                CORRELATION,
                2.5));
    assertTrue(status.contains("observed rate 2.50 intervals/s"));
    assertTrue(status.contains("Last failure STORAGE_IDENTITY"));
  }
}
