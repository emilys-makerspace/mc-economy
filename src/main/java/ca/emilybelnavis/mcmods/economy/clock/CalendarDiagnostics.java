package ca.emilybelnavis.mcmods.economy.clock;

import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Detached, read-only calendar state captured in one storage-worker transaction. */
public record CalendarDiagnostics(
    Instant sampledAt,
    boolean active,
    int calendarVersion,
    Long originMillis,
    long currentMillis,
    Long observedMillis,
    Long targetMillis,
    Long frontierMillis,
    long completedChildren,
    Position position,
    DeadlineDispatcher.Event nextEvent,
    long domainPendingWitness,
    long fixedBacklog,
    boolean waitingForWall,
    List<Provider> providers,
    List<FixedSystem> fixedSystems) {
  public record Position(
      long completedDays,
      long completedMonths,
      long completedYears,
      Long nextDayMillis,
      Long nextMonthMillis,
      Long nextYearMillis) {}

  public record PolicyVersion(long effectiveMillis, int version) {}

  public record ActiveEvent(
      DeadlineDispatcher.Event event, String exclusiveCursor, long completedChildren) {}

  public record Provider(
      String id,
      List<PolicyVersion> versions,
      Integer effectiveVersion,
      PolicyVersion nextPolicy,
      ActiveEvent activeEvent) {
    public Provider {
      versions = List.copyOf(versions);
    }
  }

  public record FixedSystem(
      String id,
      long cadenceMillis,
      long originMillis,
      long frontierMillis,
      long observedMillis,
      long targetMillis,
      Long nextMillis,
      Integer nextVersion,
      long backlog,
      boolean waitingForWall) {}

  public CalendarDiagnostics {
    Objects.requireNonNull(sampledAt);
    providers = List.copyOf(providers);
    fixedSystems = List.copyOf(fixedSystems);
  }

  /** Does not observe time, activate a calendar, advance work or change readiness. */
  public static CalendarDiagnostics read(
      FoundationStore.Transaction tx,
      Instant now,
      Function<EconomicClock.Queries, DeadlineDispatcher.Event> nextEvent) {
    Objects.requireNonNull(tx);
    Objects.requireNonNull(now);
    long current = now.toEpochMilli();
    if (current < 0) throw new IllegalArgumentException("Negative diagnostic UTC");
    EconomicClock.Queries reads =
        (sql, parameters, maximum) -> {
          if (!sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT "))
            throw new IllegalArgumentException("Calendar inspection is read-only");
          return tx.query(sql, parameters, maximum);
        };
    var fixed = fixedSystems(reads, current);
    long fixedBacklog = 0;
    boolean fixedWaiting = false;
    for (var system : fixed) {
      fixedBacklog = Math.addExact(fixedBacklog, system.backlog());
      fixedWaiting |= system.waitingForWall();
    }
    var calendars = bounded(reads.query("SELECT * FROM financial_calendar", List.of(), 1), 1);
    if (calendars.isEmpty())
      return new CalendarDiagnostics(
          now,
          false,
          0,
          null,
          current,
          null,
          null,
          null,
          0,
          null,
          null,
          0,
          fixedBacklog,
          fixedWaiting,
          List.of(),
          fixed);
    var calendar = calendars.getFirst();
    int version = Integer.parseInt(calendar.get("version"));
    if (version != FinancialCalendar.VERSION)
      throw invalid("Unsupported diagnostic calendar version");
    long origin = nonnegative(calendar, "origin_ms");
    long observed = nonnegative(calendar, "observed_wall_ms");
    long frontier = nonnegative(calendar, "last_effective_ms");
    long target = Math.max(current, observed);
    var event = nextEvent == null ? null : nextEvent.apply(reads);
    long witness = event != null && event.effectiveMillis() <= target ? 1 : 0;
    boolean domainWaiting =
        current < frontier || (witness > 0 && event.effectiveMillis() > current);
    long days = FinancialCalendar.completedPeriods(origin, current, FinancialCalendar.Period.DAY);
    long months =
        FinancialCalendar.completedPeriods(origin, current, FinancialCalendar.Period.MONTH);
    long years = FinancialCalendar.completedPeriods(origin, current, FinancialCalendar.Period.YEAR);
    var position =
        new Position(
            days,
            months,
            years,
            boundary(origin, FinancialCalendar.Period.DAY, days),
            boundary(origin, FinancialCalendar.Period.MONTH, months),
            boundary(origin, FinancialCalendar.Period.YEAR, years));
    var providers = new ArrayList<Provider>();
    for (var row :
        bounded(
            reads.query(
                "SELECT provider FROM financial_deadline_providers ORDER BY provider",
                List.of(),
                64),
            64)) {
      String id = row.get("provider");
      var versions = policyVersions(reads, id);
      Integer effective = null;
      PolicyVersion pending = null;
      for (var policy : versions) {
        if (policy.effectiveMillis() <= current) effective = policy.version();
        else if (pending == null) pending = policy;
      }
      ActiveEvent active = null;
      var activeRows =
          bounded(
              reads.query(
                  "SELECT * FROM financial_deadline_events WHERE provider=? AND state='APPLYING'",
                  List.of(id),
                  1),
              1);
      if (!activeRows.isEmpty()) {
        var child = activeRows.getFirst();
        active =
            new ActiveEvent(
                new DeadlineDispatcher.Event(
                    UUID.fromString(child.get("event_id")),
                    id,
                    nonnegative(child, "effective_ms"),
                    Integer.parseInt(child.get("stage")),
                    Integer.parseInt(child.get("version"))),
                child.get("cursor"),
                nonnegative(child, "completed_effects"));
      }
      providers.add(new Provider(id, versions, effective, pending, active));
    }
    return new CalendarDiagnostics(
        now,
        true,
        version,
        origin,
        current,
        observed,
        target,
        frontier,
        nonnegative(calendar, "completed_effects"),
        position,
        event,
        witness,
        fixedBacklog,
        fixedWaiting || domainWaiting,
        providers,
        fixed);
  }

  private static List<FixedSystem> fixedSystems(EconomicClock.Queries reads, long current) {
    var metadata =
        bounded(reads.query("SELECT observed_wall_ms FROM economic_metadata", List.of(), 1), 1);
    long globalObserved =
        metadata.isEmpty() ? 0 : nonnegative(metadata.getFirst(), "observed_wall_ms");
    var systems = new ArrayList<FixedSystem>();
    for (var row :
        bounded(
            reads.query("SELECT * FROM economic_systems ORDER BY system_id", List.of(), 64), 64)) {
      String id = row.get("system_id");
      long cadence = nonnegative(row, "cadence_ms");
      if (cadence == 0) throw invalid("Invalid diagnostic fixed cadence");
      long origin = nonnegative(row, "origin_ms");
      long frontier = nonnegative(row, "frontier_ms");
      long observed =
          row.get("active_observed_ms") == null
              ? globalObserved
              : nonnegative(row, "active_observed_ms");
      long target = Math.max(current, observed);
      long debt = target <= frontier ? 0 : (target - frontier) / cadence;
      boolean waiting =
          (nonnegative(row, "last_sequence") > 0 && current < frontier)
              || (target > current && target - frontier >= cadence);
      Long next;
      try {
        next = Math.addExact(frontier, cadence);
      } catch (ArithmeticException overflow) {
        next = null;
      }
      Integer nextVersion = null;
      var versions =
          bounded(
              reads.query(
                  "SELECT version,effective_end_ms FROM economic_versions WHERE system_id=? ORDER BY effective_end_ms",
                  List.of(id),
                  16),
              16);
      for (var version : versions) {
        if (next != null && nonnegative(version, "effective_end_ms") <= next)
          nextVersion = Integer.parseInt(version.get("version"));
      }
      systems.add(
          new FixedSystem(
              id, cadence, origin, frontier, observed, target, next, nextVersion, debt, waiting));
    }
    return List.copyOf(systems);
  }

  private static List<PolicyVersion> policyVersions(EconomicClock.Queries reads, String provider) {
    var result = new ArrayList<PolicyVersion>();
    for (var row :
        bounded(
            reads.query(
                "SELECT effective_ms,version FROM financial_deadline_versions WHERE provider=? ORDER BY effective_ms",
                List.of(provider),
                16),
            16)) {
      int version = Integer.parseInt(row.get("version"));
      if (version < 1) throw invalid("Invalid diagnostic policy version");
      result.add(new PolicyVersion(nonnegative(row, "effective_ms"), version));
    }
    return List.copyOf(result);
  }

  private static Long boundary(long origin, FinancialCalendar.Period period, long completed) {
    try {
      return FinancialCalendar.at(origin, period, Math.addExact(completed, 1));
    } catch (ArithmeticException overflow) {
      return null;
    }
  }

  private static long nonnegative(Map<String, String> row, String key) {
    long number = Long.parseLong(row.get(key));
    if (number < 0) throw invalid("Negative diagnostic checkpoint");
    return number;
  }

  private static <T> List<T> bounded(List<T> values, int maximum) {
    if (values.size() > maximum) throw invalid("Calendar diagnostic registry bound");
    return values;
  }

  private static StorageException invalid(String message) {
    return new StorageException(StorageException.Kind.SCHEMA, message);
  }
}
