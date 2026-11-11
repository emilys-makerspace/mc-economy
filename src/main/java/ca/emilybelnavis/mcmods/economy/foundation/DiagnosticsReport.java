package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.fx.FxDiagnostics;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Formats detached diagnostics without accessing storage or exposing arbitrary diagnostic text. */
public final class DiagnosticsReport {
  private static final long STALL_AGE_NANOS = 5_000_000_000L;

  public record StorageDetails(
      UUID worldId, int schemaVersion, long lastCommittedSequence, String health) {}

  private DiagnosticsReport() {}

  public static List<String> render(
      boolean privileged,
      boolean detailed,
      ServerServices.State state,
      SchedulingMetrics metrics,
      StorageDetails storage,
      String failureCode,
      UUID correlation,
      Double intervalsPerSecond) {
    String phase = recoveryPhase(metrics.recovery().phase());
    String availability =
        switch (state) {
          case INITIALIZING -> "Economy is starting (" + phase + ").";
          case READY ->
              metrics.economy().ready()
                  ? "Economy is available."
                  : "Economy is catching up (scheduled work pending).";
          case UNAVAILABLE -> "Economy is unavailable.";
          case STOPPING -> "Economy is stopping.";
          case STOPPED -> "Economy is stopped.";
        };
    if (!privileged) return List.of(availability);

    List<String> lines = new ArrayList<>();
    lines.add(availability + " Lifecycle " + state.name().toLowerCase(Locale.ROOT) + ".");
    if (storage == null) {
      lines.add("Storage details unavailable.");
    } else {
      lines.add(
          "Storage "
              + health(storage.health())
              + ", world "
              + storage.worldId()
              + ", schema "
              + storage.schemaVersion()
              + ", last committed sequence "
              + storage.lastCommittedSequence()
              + ".");
    }
    long target = metrics.recovery().target();
    lines.add(
        "Recovery stage "
            + phase
            + ", checkpoint "
            + metrics.recovery().completed()
            + (isClockRecovery(phase) ? ", backlog " : ", target ")
            + (target < 0 ? "unknown" : Long.toString(target))
            + ".");
    var economy = metrics.economy();
    String rate =
        intervalsPerSecond != null && Double.isFinite(intervalsPerSecond) && intervalsPerSecond >= 0
            ? String.format(Locale.ROOT, "%.2f intervals/s", intervalsPerSecond)
            : "unavailable";
    lines.add(
        "Economic frontier "
            + economy.completedSequence()
            + ", pending work units "
            + economy.outstandingIntervals()
            + ", observed rate "
            + rate
            + (economy.waitingForWall() ? ", waiting for wall clock" : "")
            + ".");
    lines.add(queue("Storage", metrics.storage()));
    lines.add(queue("Calculation", metrics.calculation()));
    lines.add(queue("Publication", metrics.publication()));
    if (detailed) lines.add("Economic step in flight " + metrics.economicStepInFlight() + ".");
    lines.add("Unresolved at shutdown " + metrics.unresolvedAtShutdown() + ".");
    lines.add(
        failureCode == null
            ? "Last failure none recorded."
            : "Last failure "
                + failure(failureCode)
                + (correlation == null ? "" : ", correlation " + correlation)
                + ".");
    return List.copyOf(lines);
  }

  public static List<String> withFx(
      List<String> report, ServerServices.State lifecycle, FxDiagnostics snapshot, String reason) {
    var lines = new ArrayList<>(report);
    if (lifecycle != ServerServices.State.READY) {
      lines.add(
          "FX publication blocked ("
              + (reason == null ? lifecycle.name().toLowerCase(Locale.ROOT) : failure(reason))
              + ").");
    } else if (reason != null) {
      boolean retryable =
          reason.equals("STORAGE_LOCK_TIMEOUT") || reason.equals("STORAGE_OVERLOAD");
      lines.add(
          "FX "
              + (retryable ? "inspection or publication can retry" : "inspection unavailable")
              + " ("
              + failure(reason)
              + ").");
    } else if (snapshot == null) {
      lines.add("FX sample pending.");
    } else if (snapshot.reference() == null) {
      lines.add("FX awaiting world currency selection.");
    } else if (snapshot.waitingForWall()) {
      lines.add("FX waiting for UTC wall clock.");
    } else {
      lines.add(snapshot.backlog() == 0 ? "FX current." : "FX catch-up due.");
    }
    if (snapshot != null && snapshot.reference() == null)
      lines.add(
          "CHF catalog 13 denominations (6 notes, 7 coins); two-decimal ledger; FX unavailable until world currency selection.");
    if (snapshot != null && snapshot.reference() != null) {
      lines.add(
          "FX sample "
              + snapshot.sampledAt()
              + ", reference "
              + snapshot.reference().code()
              + ", last committed UTC day "
              + snapshot.lastDay()
              + ", effective policy "
              + snapshot.effectivePolicy()
              + ".");
      lines.add(
          "FX next due UTC day " + snapshot.nextDay() + ", backlog " + snapshot.backlog() + ".");
      lines.add(
          "CHF catalog 13 denominations (6 notes, 7 coins); two-decimal ledger; FX "
              + (snapshot.chfAvailable()
                  ? "available in committed publication"
                  : "unavailable before activation")
              + (snapshot.chfScheduledDay() == null
                  ? ""
                  : (snapshot.chfAvailable() ? ", effective UTC day " : ", scheduled UTC day ")
                      + snapshot.chfScheduledDay())
              + ".");
      var pending = snapshot.pending();
      lines.add(
          pending == null
              ? "FX pending policy none."
              : "FX pending policy " + pending.id() + ", scheduled UTC day " + pending.day() + ".");
    }
    return List.copyOf(lines);
  }

  private static String queue(String name, SchedulingMetrics.Queue queue) {
    String stall =
        Math.max(queue.oldestAgeNanos(), queue.oldestActiveAgeNanos()) >= STALL_AGE_NANOS
            ? ", possible stall"
            : "";
    return name
        + " queue "
        + queue.depth()
        + "/"
        + queue.capacity()
        + ", oldest wait "
        + queue.oldestAgeNanos() / 1_000_000
        + " ms, oldest active "
        + queue.oldestActiveAgeNanos() / 1_000_000
        + " ms"
        + stall
        + ".";
  }

  private static boolean isClockRecovery(String phase) {
    return phase.equals("catch-up") || phase.equals("waiting-for-wall-clock");
  }

  public static String recoveryPhase(String phase) {
    if (phase == null) return "unknown";
    if (phase.startsWith("projection:")) return "projection rebuild";
    return switch (phase) {
      case "storage",
          "storage-ready",
          "journal-validation",
          "ready",
          "catch-up",
          "waiting-for-wall-clock",
          "projection-final-validation",
          "bank-provider-migration",
          "valuation-source-registration",
          "pricing-activation",
          "custody-reconciliation",
          "cash-reconciliation",
          "deposit-reconciliation",
          "deposit-return-reconciliation",
          "withdrawal-reconciliation",
          "register-reconciliation" ->
          phase;
      default -> "unknown";
    };
  }

  private static String health(String value) {
    return switch (value) {
      case "healthy", "degraded", "unavailable", "initializing", "closed" -> value;
      case null, default -> "unknown";
    };
  }

  static String failure(String value) {
    for (var kind : StorageException.Kind.values()) {
      if (value.equals("STORAGE_" + kind.name())) return value;
    }
    for (var event : OperationDiagnostics.Event.values()) {
      if (value.equals(event.name())) return value;
    }
    return switch (value) {
      case "LIFECYCLE_FAILURE", "CONFIGURATION_INVALID", "DRAIN_TIMEOUT", "POST_SAVE_FAILURE" ->
          value;
      default -> "unknown";
    };
  }
}
