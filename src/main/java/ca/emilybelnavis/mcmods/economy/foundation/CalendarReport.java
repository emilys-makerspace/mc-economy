package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.clock.CalendarDiagnostics;
import ca.emilybelnavis.mcmods.economy.clock.DeadlineDispatcher;
import ca.emilybelnavis.mcmods.economy.clock.FinancialCalendar;
import java.time.Instant;
import java.util.*;

/** Formats cached operator samples only; no storage, scheduling or world effects. */
public final class CalendarReport {
  public static final int PAGE_SIZE = 8;

  private CalendarReport() {}

  public static List<String> summary(
      CalendarDiagnostics sample,
      ServerServices.State state,
      String reason,
      SchedulingMetrics metrics) {
    var lines = new ArrayList<String>();
    lines.add(
        "Calendar "
            + (sample == null ? "sample pending" : "sample " + sample.sampledAt())
            + "; lifecycle "
            + state.name().toLowerCase(Locale.ROOT)
            + (reason == null ? "" : "; inspection reason " + DiagnosticsReport.failure(reason))
            + ".");
    lines.add(
        "Calendar worker stage "
            + DiagnosticsReport.recoveryPhase(metrics.recovery().phase())
            + ", checkpoint "
            + metrics.recovery().completed()
            + ", target "
            + (metrics.recovery().target() < 0 ? "unknown" : metrics.recovery().target())
            + ", economic step in flight "
            + metrics.economicStepInFlight()
            + ".");
    if (sample == null) return List.copyOf(lines);
    if (!sample.active()) {
      lines.add("Calendar origin not activated in this sample.");
      return List.copyOf(lines);
    }
    lines.add(
        "Calendar v" + sample.calendarVersion() + " origin " + utc(sample.originMillis()) + ".");
    var position = sample.position();
    lines.add(
        "Calendar elapsed whole periods at sampled UTC: day "
            + position.completedDays()
            + ", month "
            + position.completedMonths()
            + ", year "
            + position.completedYears()
            + " (zero-based counts).");
    lines.add(
        "Calendar current "
            + utc(sample.currentMillis())
            + ", observed "
            + utc(sample.observedMillis())
            + ", retained target "
            + utc(sample.targetMillis())
            + ".");
    lines.add(
        "Calendar frontier "
            + utc(sample.frontierMillis())
            + ", committed children "
            + sample.completedChildren()
            + "; fixed backlog "
            + sample.fixedBacklog()
            + " intervals; domain pending-event witness "
            + sample.domainPendingWitness()
            + " (not a total obligation count).");
    lines.add(
        "Calendar next world boundaries: day "
            + boundary(position.nextDayMillis())
            + ", month "
            + boundary(position.nextMonthMillis())
            + ", year "
            + boundary(position.nextYearMillis())
            + ".");
    lines.add(
        sample.nextEvent() == null
            ? "Calendar next domain boundary none (domains dormant)."
            : "Calendar next domain boundary " + event(sample.nextEvent()) + ".");
    var fixed =
        sample.fixedSystems().stream()
            .filter(system -> system.nextMillis() != null)
            .min(Comparator.comparingLong(CalendarDiagnostics.FixedSystem::nextMillis));
    if (fixed.isPresent()) {
      long boundary = fixed.get().nextMillis();
      long ties =
          sample.fixedSystems().stream()
              .filter(system -> Objects.equals(system.nextMillis(), boundary))
              .count();
      lines.add(
          "Calendar earliest fixed boundary "
              + utc(boundary)
              + "; "
              + ties
              + " system(s), existing dependency order applies.");
    } else lines.add("Calendar next fixed boundary none.");
    lines.add(
        "Calendar sampled clock state "
            + (sample.waitingForWall()
                ? "WAITING_FOR_UTC_HIGH_WATER"
                : sample.fixedBacklog() > 0 || sample.domainPendingWitness() > 0
                    ? "CATCH_UP_PENDING"
                    : "CURRENT")
            + "; sample does not grant financial readiness.");
    return List.copyOf(lines);
  }

  public static List<String> render(
      CalendarDiagnostics sample,
      ServerServices.State state,
      String reason,
      SchedulingMetrics metrics,
      String after) {
    if (after == null
        || after.length() > 144
        || !after.isEmpty() && !after.matches("[a-z0-9_.:/-]+"))
      return List.of("Calendar page cursor invalid.");
    var lines = new ArrayList<>(summary(sample, state, reason, metrics));
    if (sample == null) return List.copyOf(lines);
    var entries = new TreeMap<String, List<String>>();
    for (var provider : sample.providers()) {
      var detail = new ArrayList<String>();
      detail.add(
          "Calendar provider "
              + safe(provider.id())
              + ": sampled effective policy v"
              + provider.effectiveVersion()
              + ", next policy "
              + (provider.nextPolicy() == null
                  ? "none"
                  : "v"
                      + provider.nextPolicy().version()
                      + " at "
                      + utc(provider.nextPolicy().effectiveMillis()))
              + ".");
      detail.add(
          "Calendar retained policies "
              + safe(provider.id())
              + ": "
              + String.join(
                  ", ",
                  provider.versions().stream()
                      .map(
                          version -> "v" + version.version() + "@" + utc(version.effectiveMillis()))
                      .toList())
              + ".");
      var active = provider.activeEvent();
      if (active != null)
        detail.add(
            "Calendar applying "
                + event(active.event())
                + ", exclusive cursor "
                + safe(active.exclusiveCursor())
                + ", committed event children "
                + active.completedChildren()
                + ".");
      entries.put("domain/" + provider.id(), List.copyOf(detail));
    }
    for (var fixed : sample.fixedSystems()) {
      entries.put(
          "fixed/" + fixed.id(),
          List.of(
              "Calendar fixed system "
                  + safe(fixed.id())
                  + ": next "
                  + boundary(fixed.nextMillis())
                  + ", policy v"
                  + fixed.nextVersion()
                  + ", backlog "
                  + fixed.backlog()
                  + " intervals, frontier "
                  + utc(fixed.frontierMillis())
                  + ", target "
                  + utc(fixed.targetMillis())
                  + (fixed.waitingForWall() ? ", waiting for UTC" : "")
                  + "."));
    }
    var selected = entries.tailMap(after, false).entrySet().stream().limit(PAGE_SIZE + 1).toList();
    for (var entry : selected.stream().limit(PAGE_SIZE).toList()) lines.addAll(entry.getValue());
    if (selected.size() > PAGE_SIZE)
      lines.add(
          "Calendar next page: /economy diagnostics calendar after "
              + selected.get(PAGE_SIZE - 1).getKey());
    else
      lines.add("Calendar registry page complete; each page uses its displayed sample timestamp.");
    return List.copyOf(lines);
  }

  private static String event(DeadlineDispatcher.Event event) {
    return utc(event.effectiveMillis())
        + " stage "
        + FinancialCalendar.Stage.values()[event.stage()].name()
        + " provider "
        + safe(event.provider())
        + " event "
        + event.id()
        + " policy v"
        + event.version();
  }

  private static String safe(String text) {
    return text != null && text.length() <= 144 && text.matches("[A-Za-z0-9_.:/-]*")
        ? text
        : "invalid";
  }

  private static String utc(Long millis) {
    return millis == null ? "unavailable" : Instant.ofEpochMilli(millis).toString();
  }

  private static String boundary(Long millis) {
    return millis == null ? "unrepresentable (UTC overflow)" : utc(millis);
  }
}
