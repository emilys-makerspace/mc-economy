package ca.emilybelnavis.mcmods.economy.clock;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/** Worker-confined arbitrary UTC deadlines. One child or event completion commits per advance. */
public final class DeadlineDispatcher {
  public static final String ACTIVATION = "economy:a6/calendar-activation";
  public static final String PROGRESS_RECORD = "economy:a6/deadline-progress";
  public static final String POLICY = "economy:a6/policy-effective";
  public static final String COMPLETION = "economy:a6/deadline-completion";
  private static final Journal.Kind KIND =
      new Journal.Kind("economy:a6", "economy:a6/calendar/advance");
  private static final Comparator<Event> ORDER =
      Comparator.comparingLong(Event::effectiveMillis)
          .thenComparingInt(Event::stage)
          .thenComparing(Event::provider)
          .thenComparing(e -> e.id().toString());

  public record Event(UUID id, String provider, long effectiveMillis, int stage, int version) {
    public Event {
      Objects.requireNonNull(id);
      identifier(provider);
      if (effectiveMillis < 0 || stage < 0 || stage > 8 || version < 1)
        throw new IllegalArgumentException("Deadline event");
    }
  }

  /**
   * Providers are trusted domain adapters, never packet inputs. Missing domains remain
   * unregistered.
   */
  public interface Provider {
    String id();

    Map<Integer, String> versions();

    /**
     * Earliest event strictly after completed, or null when dormant. Preserve historical meaning.
     */
    Event next(EconomicClock.Queries reads, long originMillis, Event completed);

    /** Stable membership as of this effective instant; exclusive ordered keys, at most limit. */
    List<String> targets(
        Event event, String exclusiveCursor, int limit, EconomicClock.Queries reads);

    /** Pure read preparation; effects and records commit with the cursor through the journal. */
    EconomicClock.Output prepare(Event event, String target, EconomicClock.Queries reads);
  }

  public record Progress(
      long completedEffects, long outstandingEvents, boolean waitingForWall, boolean ready) {}

  private record State(Event event, String cursor, long completed, long globalCount) {}

  private final FoundationStore store;
  private final Clock wall;
  private final SortedMap<String, Provider> providers = new TreeMap<>();
  private final int pageSize;

  public DeadlineDispatcher(
      FoundationStore store, Clock wall, Collection<Provider> providers, int pageSize) {
    this.store = Objects.requireNonNull(store);
    this.wall = Objects.requireNonNull(wall);
    if (pageSize < 1 || pageSize > 16 || providers.size() > 64)
      throw new IllegalArgumentException("Deadline bounds");
    this.pageSize = pageSize;
    for (Provider provider : providers) {
      identifier(provider.id());
      fingerprint(provider);
      if (this.providers.putIfAbsent(provider.id(), provider) != null)
        throw new IllegalArgumentException("Duplicate deadline provider");
    }
  }

  /** Exact persisted child identity shared with domain source writers. */
  public static UUID childOperation(WorldId world, Event event, String target) {
    return stable("operation|" + world.value() + "|" + event.id() + "|target|" + target);
  }

  /** Validate an already proposed next event against this live installed registry. */
  public Successor proposedSuccessor(JournalEngine.ReadView reads, String providerId, Event event) {
    var provider =
        Objects.requireNonNull(
            providers.get(providerId), "Successor implementation is not installed");
    if (!(provider instanceof ca.emilybelnavis.mcmods.economy.simulation.macro.MacroProvider)
        || !event.equals(
            ca.emilybelnavis.mcmods.economy.simulation.macro.MacroProvider.event(
                store.snapshot().worldId(), event.effectiveMillis()))
        || active(providerId, reads::query) != null
        || completed(providerId, reads::query) != null
        || !providerId.equals(event.provider())
        || event.stage() != 0
        || event.version() != scheduledVersion(reads::query, providerId, event.effectiveMillis())
        || !provider.versions().containsKey(event.version()))
      throw new IllegalArgumentException("Prospective successor registry conflict");
    return new Successor(store.snapshot().worldId(), event, fingerprint(provider));
  }

  public record Successor(WorldId world, Event event, String implementations) {}

  /** Live registry proof used only when reserving a prospective fixed-system handover. */
  public Successor successorAt(String providerId, long effectiveMillis) {
    var provider =
        Objects.requireNonNull(
            providers.get(providerId), "Successor implementation is not installed");
    return store.transaction(
        tx -> {
          if (active(providerId, tx::query) != null)
            throw new IllegalArgumentException("Successor has an in-flight event");
          long origin =
              number(
                  tx.query(
                          "SELECT origin_ms FROM financial_calendar WHERE singleton=1",
                          List.of(),
                          1)
                      .getFirst(),
                  "origin_ms");
          var previous = completed(providerId, tx::query);
          var event = provider.next(tx::query, origin, previous);
          if (event == null
              || !providerId.equals(event.provider())
              || event.effectiveMillis() != effectiveMillis
              || event.stage() != 0
              || !provider.versions().containsKey(event.version())
              || event.version() != scheduledVersion(tx::query, providerId, effectiveMillis))
            throw new IllegalArgumentException(
                "Installed successor must next publish at takeover stage zero");
          return new Successor(store.snapshot().worldId(), event, fingerprint(provider));
        });
  }

  public static Map<String, String> implementations(Collection<Provider> providers) {
    var result = new TreeMap<String, String>();
    for (var provider : providers)
      if (result.put(provider.id(), fingerprint(provider)) != null)
        throw new IllegalArgumentException("Duplicate successor");
    return Map.copyOf(result);
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog
        .with(new RecordCatalog.Key(ACTIVATION, 1), DeadlineDispatcher::validateActivation)
        .with(
            new RecordCatalog.Key(PROGRESS_RECORD, 1),
            payload -> validateProgressType(payload, false))
        .with(new RecordCatalog.Key(COMPLETION, 1), payload -> validateProgressType(payload, true))
        .with(new RecordCatalog.Key(POLICY, 1), DeadlineDispatcher::validatePolicy);
  }

  /** Called after migrations; activation is once per world and never rounded to a day boundary. */
  public void prepare() {
    var rows = store.transaction(tx -> tx.query("SELECT * FROM financial_calendar", List.of(), 1));
    if (rows.isEmpty()) {
      long origin = now();
      var intent =
          intent(
              "activation",
              Map.of("origin", Long.toString(origin)),
              "economy:a6/calendar/activate");
      // Retry an uncertain activation with its original canonical origin, not the new wall time.
      var engine =
          engine(
              "economy:a6/calendar/activate",
              (request, reads) -> {
                if (!reads
                    .query("SELECT singleton FROM financial_calendar", List.of(), 1)
                    .isEmpty()) throw failure("Calendar activation conflict");
                long storedOrigin = Long.parseLong(request.request().fields().get("origin"));
                return new JournalEngine.Commit(
                    List.of(new Journal.Record(ACTIVATION, 1, "1|" + storedOrigin)),
                    new Journal.Result(1, "CALENDAR_ACTIVATED", Long.toString(storedOrigin)),
                    tx ->
                        tx.execute(
                            "INSERT INTO financial_calendar(singleton,version,origin_ms,observed_wall_ms,last_effective_ms,completed_effects) VALUES(1,1,?,?,?,0)",
                            List.of(
                                Long.toString(storedOrigin),
                                Long.toString(storedOrigin),
                                Long.toString(storedOrigin))));
              });
      committed(engine.submit(intent));
    }
    store.transaction(
        tx -> {
          var persisted =
              tx.query(
                  "SELECT * FROM financial_deadline_providers ORDER BY provider", List.of(), 64);
          for (var row : persisted) {
            var provider = providers.get(row.get("provider"));
            if (provider == null || !retains(provider, row.get("implementations")))
              throw failure("Missing or changed historical deadline provider");
            tx.execute(
                "UPDATE financial_deadline_providers SET implementations=? WHERE provider=?",
                List.of(fingerprint(provider), provider.id()));
          }
          for (var provider : providers.values()) {
            if (tx.query(
                    "SELECT provider FROM financial_deadline_providers WHERE provider=?",
                    List.of(provider.id()),
                    1)
                .isEmpty()) {
              if (!provider.versions().containsKey(1))
                throw failure("Provider initial version 1 missing");
              tx.execute(
                  "INSERT INTO financial_deadline_providers(provider,implementations) VALUES(?,?)",
                  List.of(provider.id(), fingerprint(provider)));
              String origin =
                  tx.query("SELECT origin_ms FROM financial_calendar", List.of(), 1)
                      .getFirst()
                      .get("origin_ms");
              tx.execute(
                  "INSERT INTO financial_deadline_versions VALUES(?,?,1)",
                  List.of(provider.id(), origin));
            }
            validateSchedule(tx::query, provider);
          }
          validateRetirementSuccessors(tx::query, providers.values());
          var calendar = tx.query("SELECT * FROM financial_calendar", List.of(), 1).getFirst();
          if (!"1".equals(calendar.get("version")))
            throw failure("Unsupported financial calendar version");
          if (tx.query(
                  "SELECT payload FROM journal_records WHERE record_type IN ('economy:a6/calendar-activation','economy:a6/deadline-progress','economy:a6/deadline-completion','economy:a6/policy-effective') AND record_type=? AND record_version=1 AND payload=? LIMIT 1",
                  List.of(ACTIVATION, "1|" + calendar.get("origin_ms")),
                  1)
              .isEmpty()) throw failure("Calendar origin lacks activation fact");
          if (!calendar.get("last_fact").isEmpty()) {
            String payload = calendar.get("last_fact");
            validateProgress(payload);
            String[] fields = payload.split("\\|", -1);
            if (!fields[1].equals(calendar.get("last_event"))
                || !fields[2].equals(calendar.get("last_provider"))
                || !fields[3].equals(calendar.get("last_effective_ms"))
                || !fields[4].equals(calendar.get("last_stage"))
                || !fields[9].equals(calendar.get("completed_effects")))
              throw failure("Calendar global checkpoint mismatch");
            String type = fields[8].equals("1") ? COMPLETION : PROGRESS_RECORD;
            if (tx.query(
                    "SELECT payload FROM journal_records WHERE record_type IN ('economy:a6/calendar-activation','economy:a6/deadline-progress','economy:a6/deadline-completion','economy:a6/policy-effective') AND record_type=? AND record_version=1 AND payload=? LIMIT 1",
                    List.of(type, payload),
                    1)
                .isEmpty()) throw failure("Global calendar checkpoint lacks fact");
          } else if (number(calendar, "completed_effects") != 0
              || !calendar.get("last_effective_ms").equals(calendar.get("origin_ms")))
            throw failure("Invalid initial calendar checkpoint");
          // Check only bounded provider heads; history remains immutable and journal-validated.
          for (var provider : providers.values()) {
            var active = active(provider.id(), tx::query);
            if (active != null) validateHead(active, false, tx::query);
            var head = completed(provider.id(), tx::query);
            if (head != null) {
              var state = state(head, tx::query);
              validateHead(state, true, tx::query);
            }
          }
          return null;
        });
  }

  /**
   * Includes future events, allowing the shared dispatcher to compare fixed and domain instants.
   */
  public Event nextEvent() {
    return store.transaction(tx -> next(tx::query));
  }

  /**
   * Read-only inspection in the caller's consistent transaction; never observes or advances time.
   */
  public Event readNextEvent(EconomicClock.Queries reads) {
    return next(reads);
  }

  public Progress progress() {
    return store.transaction(tx -> progress(tx::query, now()));
  }

  public Progress observeCurrentDebt() {
    long now = now();
    return store.transaction(
        tx -> {
          var progress = progress(tx::query, now);
          if (progress.outstandingEvents() > 0) observe(tx, now);
          return progress;
        });
  }

  public Progress advance() {
    long now = now();
    store.transaction(
        tx -> {
          observe(tx, now);
          return null;
        });
    var event = nextEvent();
    if (event == null || event.effectiveMillis() > now || progress().waitingForWall())
      return progress();
    var before = store.transaction(tx -> state(event, tx::query));
    var selected = store.transaction(tx -> targets(before, tx::query));
    String target = selected.isEmpty() ? "" : selected.getFirst();
    Map<String, String> fields =
        Map.of(
            "event",
            event.id().toString(),
            "provider",
            event.provider(),
            "effective",
            Long.toString(event.effectiveMillis()),
            "stage",
            Integer.toString(event.stage()),
            "version",
            Integer.toString(event.version()),
            "cursor",
            before.cursor(),
            "completed",
            Long.toString(before.completed()),
            "target",
            target);
    var intent =
        intent(
            event.id() + "|" + (target.isEmpty() ? "complete" : "target|" + target),
            fields,
            KIND.operationType());
    var engine =
        engine(
            KIND.operationType(),
            (request, reads) -> {
              var queries = (EconomicClock.Queries) reads::query;
              if (!event.equals(next(queries)) || !before.equals(state(event, queries)))
                throw failure("Deadline changed before commit");
              long commitNow = now();
              if (event.effectiveMillis() > commitNow
                  || progress(queries, commitNow).waitingForWall())
                return new JournalEngine.Decline(new Journal.Result(1, "WAITING_FOR_WALL", ""));
              var currentTargets = targets(before, queries);
              if (!target.equals(currentTargets.isEmpty() ? "" : currentTargets.getFirst()))
                throw failure("Deadline target changed before commit");
              boolean complete = target.isEmpty();
              long count = complete ? before.completed() : Math.addExact(before.completed(), 1);
              String cursor = complete ? before.cursor() : target;
              long globalCount =
                  Math.addExact(
                      number(
                          queries
                              .query(
                                  "SELECT completed_effects FROM financial_calendar", List.of(), 1)
                              .getFirst(),
                          "completed_effects"),
                      complete ? 0 : 1);
              String payload = fact(event, cursor, count, complete, globalCount);
              var output =
                  complete
                      ? new EconomicClock.Output("", tx -> {})
                      : Objects.requireNonNull(
                          providers.get(event.provider()).prepare(event, target, queries));
              var records = new ArrayList<Journal.Record>();
              records.add(new Journal.Record(complete ? COMPLETION : PROGRESS_RECORD, 1, payload));
              records.addAll(output.records());
              return new JournalEngine.Commit(
                  records,
                  new Journal.Result(
                      1, complete ? "DEADLINE_COMPLETE" : "DEADLINE_UPDATED", output.payload()),
                  tx -> {
                    output.effects().apply(tx);
                    tx.execute(
                        "INSERT INTO financial_deadline_events(event_id,provider,effective_ms,stage,version,cursor,completed_effects,state,global_count) VALUES(?,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(event_id) DO UPDATE SET cursor=excluded.cursor,completed_effects=excluded.completed_effects,state=excluded.state,global_count=excluded.global_count",
                        List.of(
                            event.id().toString(),
                            event.provider(),
                            Long.toString(event.effectiveMillis()),
                            Integer.toString(event.stage()),
                            Integer.toString(event.version()),
                            cursor,
                            Long.toString(count),
                            complete ? "COMPLETE" : "APPLYING",
                            Long.toString(globalCount)));
                    tx.execute(
                        "UPDATE financial_calendar SET last_effective_ms=max(last_effective_ms,CAST(? AS INTEGER)),completed_effects=?,last_stage=?,last_provider=?,last_event=?,last_fact=? WHERE singleton=1",
                        List.of(
                            Long.toString(event.effectiveMillis()),
                            Long.toString(globalCount),
                            Integer.toString(event.stage()),
                            event.provider(),
                            event.id().toString(),
                            payload));
                    if (complete)
                      tx.execute(
                          "UPDATE financial_deadline_providers SET completed_event=?,completed_ms=?,completed_stage=?,completed_version=? WHERE provider=?",
                          List.of(
                              event.id().toString(),
                              Long.toString(event.effectiveMillis()),
                              Integer.toString(event.stage()),
                              Integer.toString(event.version()),
                              event.provider()));
                  });
            });
    var outcome = engine.submit(intent);
    if (outcome.status() == Journal.Status.NOT_ACCEPTED
        && outcome.result().code().equals("WAITING_FOR_WALL")) return progress();
    committed(outcome);
    return progress();
  }

  private Event next(EconomicClock.Queries reads) {
    validateRetirementSuccessors(reads, providers.values());
    long origin =
        number(
            reads.query("SELECT origin_ms FROM financial_calendar", List.of(), 1).getFirst(),
            "origin_ms");
    Event earliest = null;
    for (var provider : providers.values()) {
      State active = active(provider.id(), reads);
      Event event;
      if (active != null) event = active.event();
      else {
        Event previous = completed(provider.id(), reads);
        event = provider.next(reads, origin, previous);
        if (event == null) continue;
        if (!provider.id().equals(event.provider())
            || event.effectiveMillis() < origin
            || !provider.versions().containsKey(event.version())
            || event.version() != scheduledVersion(reads, provider.id(), event.effectiveMillis())
            || (previous != null && ORDER.compare(event, previous) <= 0))
          throw failure("Invalid deadline source ordering/version");
        if (!reads
            .query(
                "SELECT event_id FROM financial_deadline_events WHERE event_id=?",
                List.of(event.id().toString()),
                1)
            .isEmpty()) throw failure("Deadline source repeated completed identity");
      }
      var calendar =
          reads
              .query(
                  "SELECT last_effective_ms,last_stage,last_provider,last_event FROM financial_calendar",
                  List.of(),
                  1)
              .getFirst();
      if (!calendar.get("last_event").isEmpty()) {
        var frontier =
            new Event(
                UUID.fromString(calendar.get("last_event")),
                calendar.get("last_provider"),
                number(calendar, "last_effective_ms"),
                Integer.parseInt(calendar.get("last_stage")),
                1);
        if (ORDER.compare(event, frontier) < 0)
          throw failure("Deadline source backdated before completed global frontier");
      }
      if (earliest == null || ORDER.compare(event, earliest) < 0) earliest = event;
    }
    return earliest;
  }

  private State active(String provider, EconomicClock.Queries reads) {
    var rows =
        reads.query(
            "SELECT * FROM financial_deadline_events WHERE provider=? AND state='APPLYING'",
            List.of(provider),
            1);
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    return new State(
        event(row),
        row.get("cursor"),
        number(row, "completed_effects"),
        number(row, "global_count"));
  }

  private State state(Event event, EconomicClock.Queries reads) {
    var rows =
        reads.query(
            "SELECT * FROM financial_deadline_events WHERE event_id=?",
            List.of(event.id().toString()),
            1);
    if (rows.isEmpty()) return new State(event, "", 0, 0);
    var row = rows.getFirst();
    if (!event.equals(event(row))) throw failure("Deadline identity conflict");
    return new State(
        event, row.get("cursor"), number(row, "completed_effects"), number(row, "global_count"));
  }

  private Event completed(String provider, EconomicClock.Queries reads) {
    var row =
        reads
            .query(
                "SELECT * FROM financial_deadline_providers WHERE provider=?", List.of(provider), 1)
            .getFirst();
    if (row.get("completed_event") == null) return null;
    return new Event(
        UUID.fromString(row.get("completed_event")),
        provider,
        number(row, "completed_ms"),
        Integer.parseInt(row.get("completed_stage")),
        Integer.parseInt(row.get("completed_version")));
  }

  private List<String> targets(State state, EconomicClock.Queries reads) {
    var targets =
        List.copyOf(
            providers
                .get(state.event().provider())
                .targets(state.event(), state.cursor(), pageSize, reads));
    if (targets.size() > pageSize) throw failure("Deadline target page exceeds bound");
    String cursor = state.cursor();
    for (String target : targets) {
      target(target);
      if (target.compareTo(cursor) <= 0) throw failure("Deadline target ordering");
      cursor = target;
    }
    return targets;
  }

  private Progress progress(EconomicClock.Queries reads, long now) {
    var row = reads.query("SELECT * FROM financial_calendar", List.of(), 1).getFirst();
    long target = Math.max(now, number(row, "observed_wall_ms"));
    Event next = next(reads);
    long debt = next != null && next.effectiveMillis() <= target ? 1 : 0;
    boolean waiting =
        now < number(row, "last_effective_ms") || (debt > 0 && next.effectiveMillis() > now);
    return new Progress(number(row, "completed_effects"), debt, waiting, debt == 0 && !waiting);
  }

  private void observe(FoundationStore.Transaction tx, long now) {
    tx.execute(
        "UPDATE financial_calendar SET observed_wall_ms=max(observed_wall_ms,CAST(? AS INTEGER)) WHERE singleton=1",
        List.of(Long.toString(now)));
  }

  private void validateHead(State state, boolean complete, EconomicClock.Queries reads) {
    if (!providers.get(state.event().provider()).versions().containsKey(state.event().version()))
      throw failure("Missing historical deadline version");
    if (state.event().version()
        != scheduledVersion(reads, state.event().provider(), state.event().effectiveMillis()))
      throw failure("Deadline checkpoint policy mismatch");
    var row =
        reads
            .query(
                "SELECT state FROM financial_deadline_events WHERE event_id=?",
                List.of(state.event().id().toString()),
                1)
            .getFirst();
    if (!(complete ? "COMPLETE" : "APPLYING").equals(row.get("state")))
      throw failure("Deadline checkpoint state mismatch");
    String payload =
        fact(state.event(), state.cursor(), state.completed(), complete, state.globalCount());
    if (reads
        .query(
            "SELECT payload FROM journal_records WHERE record_type IN ('economy:a6/calendar-activation','economy:a6/deadline-progress','economy:a6/deadline-completion','economy:a6/policy-effective') AND record_type=? AND record_version=1 AND payload=? LIMIT 1",
            List.of(complete ? COMPLETION : PROGRESS_RECORD, payload),
            1)
        .isEmpty()) throw failure("Deadline checkpoint lacks authoritative fact");
  }

  /**
   * Read-only restore/inspection gate; uninstalled historical domains cannot be silently dropped.
   */
  public static void validateProviders(
      EconomicClock.Queries reads, Collection<Provider> installed) {
    var registry = new HashMap<String, Provider>();
    for (var provider : installed)
      if (registry.putIfAbsent(provider.id(), provider) != null)
        throw new IllegalArgumentException("Duplicate provider");
    for (var row :
        reads.query(
            "SELECT * FROM financial_deadline_providers ORDER BY provider", List.of(), 64)) {
      Provider provider = registry.get(row.get("provider"));
      if (provider == null || !retains(provider, row.get("implementations")))
        throw failure("Missing or changed historical deadline provider");
      validateSchedule(reads, provider);
    }
    validateRetirementSuccessors(reads, installed);
  }

  private static void validateRetirementSuccessors(
      EconomicClock.Queries reads, Collection<Provider> installed) {
    if (reads
        .query(
            "SELECT name FROM sqlite_schema WHERE type='table' AND name='fixed_system_retirements'",
            List.of(),
            1)
        .isEmpty()) return;
    var registry = new HashMap<String, Provider>();
    for (var provider : installed) registry.put(provider.id(), provider);
    for (var row :
        reads.query(
            "SELECT payload FROM fixed_system_retirements ORDER BY system_id", List.of(), 64)) {
      var receipt = FixedRetirements.decode(row.get("payload"));
      var provider = registry.get(receipt.successor());
      if (provider == null
          || !FixedRetirements.retains(fingerprint(provider), receipt.successorImplementations()))
        throw failure("Retired system's successor is not installed");
      var persisted =
          reads.query(
              "SELECT event_id,provider,effective_ms,stage,version FROM financial_deadline_events WHERE event_id=?",
              List.of(receipt.successorEvent().toString()),
              1);
      Event expected =
          new Event(
              receipt.successorEvent(),
              receipt.successor(),
              receipt.takeoverMillis(),
              0,
              receipt.successorVersion());
      if (!persisted.isEmpty()) {
        if (!event(persisted.getFirst()).equals(expected))
          throw failure("Retirement successor event changed");
        continue;
      }
      var state =
          reads.query(
              "SELECT * FROM financial_deadline_providers WHERE provider=?",
              List.of(provider.id()),
              1);
      if (state.isEmpty()) throw failure("Retirement successor checkpoint missing");
      var previous = state.getFirst();
      Event completed =
          previous.get("completed_event") == null
              ? null
              : new Event(
                  UUID.fromString(previous.get("completed_event")),
                  provider.id(),
                  number(previous, "completed_ms"),
                  Integer.parseInt(previous.get("completed_stage")),
                  Integer.parseInt(previous.get("completed_version")));
      long origin =
          number(
              reads
                  .query("SELECT origin_ms FROM financial_calendar WHERE singleton=1", List.of(), 1)
                  .getFirst(),
              "origin_ms");
      if (!expected.equals(provider.next(reads, origin, completed)))
        throw failure("Reserved successor event is missing or changed");
    }
  }

  private static void validateSchedule(EconomicClock.Queries reads, Provider provider) {
    var calendar =
        reads.query("SELECT origin_ms FROM financial_calendar WHERE singleton=1", List.of(), 1);
    if (calendar.isEmpty()) throw failure("Deadline provider lacks calendar activation");
    long origin = number(calendar.getFirst(), "origin_ms");
    var versions =
        reads.query(
            "SELECT effective_ms,version FROM financial_deadline_versions WHERE provider=? ORDER BY effective_ms",
            List.of(provider.id()),
            17);
    if (versions.isEmpty()
        || versions.size() > 16
        || number(versions.getFirst(), "effective_ms") != origin
        || number(versions.getFirst(), "version") != 1)
      throw failure("Invalid initial deadline policy schedule");
    for (int i = 0; i < versions.size(); i++) {
      var row = versions.get(i);
      int version = Integer.parseInt(row.get("version"));
      if (!provider.versions().containsKey(version))
        throw failure("Historical deadline policy unavailable");
      if (i > 0) {
        String payload = "1|" + provider.id() + "|" + version + "|" + row.get("effective_ms");
        if (reads
            .query(
                "SELECT payload FROM journal_records WHERE record_type IN ('economy:a6/calendar-activation','economy:a6/deadline-progress','economy:a6/deadline-completion','economy:a6/policy-effective') AND record_type=? AND record_version=1 AND payload=? LIMIT 1",
                List.of(POLICY, payload),
                1)
            .isEmpty()) throw failure("Deadline policy boundary lacks authoritative fact");
      }
    }
  }

  /**
   * Bounded linkage gate for each historical record, including before migrations and offline
   * restore.
   */
  public static void validateStored(String type, String payload, java.sql.Connection connection)
      throws java.sql.SQLException {
    if (type.equals(ACTIVATION)) {
      validateActivation(payload);
      try (var query =
          connection.prepareStatement(
              "SELECT origin_ms FROM financial_calendar WHERE singleton=1")) {
        try (var rows = query.executeQuery()) {
          if (!rows.next() || !payload.equals("1|" + rows.getLong(1)))
            throw failure("Stored calendar activation mismatch");
        }
      }
    } else if (type.equals(POLICY)) {
      validatePolicy(payload);
      String[] fields = payload.split("\\|", -1);
      try (var query =
          connection.prepareStatement(
              "SELECT version FROM financial_deadline_versions WHERE provider=? AND effective_ms=?")) {
        query.setString(1, fields[1]);
        query.setLong(2, Long.parseLong(fields[3]));
        try (var rows = query.executeQuery()) {
          if (!rows.next() || rows.getInt(1) != Integer.parseInt(fields[2]))
            throw failure("Stored calendar policy mismatch");
        }
      }
    } else if (type.equals(PROGRESS_RECORD) || type.equals(COMPLETION)) {
      validateProgress(payload);
      String[] fields = payload.split("\\|", -1);
      if (type.equals(COMPLETION) != fields[8].equals("1"))
        throw failure("Deadline fact type mismatch");
      try (var query =
          connection.prepareStatement("SELECT * FROM financial_deadline_events WHERE event_id=?")) {
        query.setString(1, fields[1]);
        try (var rows = query.executeQuery()) {
          if (!rows.next()
              || !rows.getString("provider").equals(fields[2])
              || rows.getLong("effective_ms") != Long.parseLong(fields[3])
              || rows.getInt("stage") != Integer.parseInt(fields[4])
              || rows.getInt("version") != Integer.parseInt(fields[5])
              || rows.getLong("completed_effects") < Long.parseLong(fields[7])
              || rows.getLong("global_count") < Long.parseLong(fields[9])
              || rows.getString("cursor").compareTo(fields[6]) < 0
              || (rows.getLong("completed_effects") == Long.parseLong(fields[7])
                  && !rows.getString("cursor").equals(fields[6]))
              || (type.equals(COMPLETION) && !rows.getString("state").equals("COMPLETE")))
            throw failure("Stored deadline checkpoint mismatch");
        }
      }
      try (var query =
          connection.prepareStatement(
              "SELECT completed_effects FROM financial_calendar WHERE singleton=1")) {
        try (var rows = query.executeQuery()) {
          if (!rows.next() || rows.getLong(1) < Long.parseLong(fields[9]))
            throw failure("Stored global calendar checkpoint mismatch");
        }
      }
    }
  }

  private JournalEngine engine(String operation, JournalEngine.Handler handler) {
    var kind = new Journal.Kind("economy:a6", operation);
    var actor = actor();
    return new JournalEngine(
        store,
        (candidate, requestKind) -> candidate.equals(actor) && requestKind.equals(kind),
        Map.of(kind, new JournalEngine.Registration(1, handler)));
  }

  private Journal.Intent intent(String key, Map<String, String> fields, String operation) {
    return new Journal.Intent(
        new OperationId(stable("operation|" + world() + "|" + key)),
        new RequestId(UUID.randomUUID()),
        actor(),
        new Journal.Kind("economy:a6", operation),
        new Journal.CanonicalRequest(1, fields),
        null,
        null);
  }

  private SystemActorId actor() {
    return new SystemActorId(stable("calendar-actor|" + world()));
  }

  private UUID world() {
    return store.snapshot().worldId().value();
  }

  private static UUID stable(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private long now() {
    long value = wall.millis();
    if (value < 0) throw new IllegalArgumentException("Negative financial UTC");
    return value;
  }

  private static long number(Map<String, String> row, String name) {
    return Long.parseLong(row.get(name));
  }

  private static Event event(Map<String, String> row) {
    return new Event(
        UUID.fromString(row.get("event_id")),
        row.get("provider"),
        number(row, "effective_ms"),
        Integer.parseInt(row.get("stage")),
        Integer.parseInt(row.get("version")));
  }

  private static void committed(Journal.Outcome outcome) {
    if (outcome.status() != Journal.Status.COMMITTED)
      throw new StorageException(
          StorageException.Kind.UNSAFE_CONNECTION,
          "Unresolved calendar operation; retry original operation " + outcome.operationId());
  }

  private static StorageException failure(String message) {
    return new StorageException(StorageException.Kind.SCHEMA, message);
  }

  private static void identifier(String value) {
    if (value == null || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || value.length() > 128)
      throw new IllegalArgumentException("Deadline provider ID");
  }

  private static void target(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_.:/-]{1,128}"))
      throw new IllegalArgumentException("Deadline target key");
  }

  private static boolean retains(Provider provider, String persisted) {
    for (String pair : persisted.split(";")) {
      String[] fields = pair.split(":", 2);
      if (fields.length != 2
          || !Objects.equals(provider.versions().get(Integer.parseInt(fields[0])), fields[1]))
        return false;
    }
    return true;
  }

  /** Historical policy selected by effective UTC, never by the current wall clock. */
  public static int scheduledVersion(EconomicClock.Queries reads, String provider, long instant) {
    var rows =
        reads.query(
            "SELECT version FROM financial_deadline_versions WHERE provider=? AND effective_ms<=? ORDER BY effective_ms DESC LIMIT 1",
            List.of(provider, Long.toString(instant)),
            1);
    if (rows.isEmpty()) throw failure("Deadline precedes policy activation");
    return Integer.parseInt(rows.getFirst().get("version"));
  }

  /**
   * Schedule an installed historical implementation strictly beyond all observed/processed time.
   */
  public void scheduleVersion(String provider, int version, long effectiveMillis) {
    var implementation = providers.get(provider);
    if (implementation == null || !implementation.versions().containsKey(version))
      throw new IllegalArgumentException("Unknown deadline policy");
    var intent =
        intent(
            "policy|" + provider + "|" + effectiveMillis,
            Map.of(
                "provider",
                provider,
                "version",
                Integer.toString(version),
                "effective",
                Long.toString(effectiveMillis)),
            "economy:a6/calendar/policy");
    var journal =
        engine(
            "economy:a6/calendar/policy",
            (request, reads) -> {
              var row = reads.query("SELECT * FROM financial_calendar", List.of(), 1).getFirst();
              for (var retirement :
                  reads.query(
                      "SELECT payload FROM fixed_system_retirements ORDER BY system_id",
                      List.of(),
                      64)) {
                var captured = FixedRetirements.decode(retirement.get("payload"));
                if (captured.successor().equals(provider)
                    && effectiveMillis <= captured.takeoverMillis())
                  throw new IllegalArgumentException(
                      "Reserved successor policy cannot change before takeover");
              }
              if (effectiveMillis
                  <= Math.max(
                      now(),
                      Math.max(number(row, "observed_wall_ms"), number(row, "last_effective_ms"))))
                throw new IllegalArgumentException("Policy boundary must be strictly future");
              var versions =
                  reads.query(
                      "SELECT effective_ms FROM financial_deadline_versions WHERE provider=? ORDER BY effective_ms",
                      List.of(provider),
                      16);
              if (versions.size() >= 16
                  || versions.stream()
                      .anyMatch(v -> Long.parseLong(v.get("effective_ms")) == effectiveMillis))
                throw new IllegalArgumentException("Policy schedule bound/conflict");
              String payload = "1|" + provider + "|" + version + "|" + effectiveMillis;
              return new JournalEngine.Commit(
                  List.of(new Journal.Record(POLICY, 1, payload)),
                  new Journal.Result(1, "POLICY_SCHEDULED", payload),
                  tx ->
                      tx.execute(
                          "INSERT INTO financial_deadline_versions VALUES(?,?,?)",
                          List.of(
                              provider,
                              Long.toString(effectiveMillis),
                              Integer.toString(version))));
            });
    committed(journal.submit(intent));
  }

  public static void validatePolicy(String payload) {
    String[] fields = payload.split("\\|", -1);
    if (fields.length != 4 || !fields[0].equals("1"))
      throw new IllegalArgumentException("Policy fact");
    identifier(fields[1]);
    int version = Integer.parseInt(fields[2]);
    long effective = Long.parseLong(fields[3]);
    if (version < 1
        || effective < 0
        || !payload.equals("1|" + fields[1] + "|" + version + "|" + effective))
      throw new IllegalArgumentException("Policy fact values");
  }

  private static String fingerprint(Provider provider) {
    var versions = new TreeMap<>(provider.versions());
    if (versions.isEmpty() || versions.size() > 16)
      throw new IllegalArgumentException("Deadline versions");
    var text = new StringBuilder();
    for (var entry : versions.entrySet()) {
      if (entry.getKey() < 1
          || entry.getValue() == null
          || !entry.getValue().matches("[A-Za-z0-9_.:-]{1,128}"))
        throw new IllegalArgumentException("Deadline implementation identity");
      text.append(entry.getKey()).append(':').append(entry.getValue()).append(';');
    }
    return text.toString();
  }

  private static String fact(
      Event event, String cursor, long count, boolean complete, long globalCount) {
    return "1|"
        + event.id()
        + "|"
        + event.provider()
        + "|"
        + event.effectiveMillis()
        + "|"
        + event.stage()
        + "|"
        + event.version()
        + "|"
        + cursor
        + "|"
        + count
        + "|"
        + (complete ? "1" : "0")
        + "|"
        + globalCount;
  }

  public static void validateActivation(String payload) {
    String[] fields = payload.split("\\|", -1);
    if (fields.length != 2 || !fields[0].equals("1") || Long.parseLong(fields[1]) < 0)
      throw new IllegalArgumentException("Calendar activation fact");
    if (!payload.equals("1|" + Long.parseLong(fields[1])))
      throw new IllegalArgumentException("Noncanonical activation fact");
  }

  private static void validateProgressType(String payload, boolean complete) {
    validateProgress(payload);
    if (!payload.split("\\|", -1)[8].equals(complete ? "1" : "0"))
      throw new IllegalArgumentException("Deadline fact type mismatch");
  }

  public static void validateProgress(String payload) {
    String[] fields = payload.split("\\|", -1);
    if (fields.length != 10
        || !fields[0].equals("1")
        || (!fields[8].equals("0") && !fields[8].equals("1")))
      throw new IllegalArgumentException("Deadline progress fact");
    var event =
        new Event(
            UUID.fromString(fields[1]),
            fields[2],
            Long.parseLong(fields[3]),
            Integer.parseInt(fields[4]),
            Integer.parseInt(fields[5]));
    if (!fields[6].isEmpty()) target(fields[6]);
    long count = Long.parseLong(fields[7]);
    if (count < 0
        || (count == 0) != fields[6].isEmpty()
        || Long.parseLong(fields[9]) < count
        || !payload.equals(
            fact(event, fields[6], count, fields[8].equals("1"), Long.parseLong(fields[9]))))
      throw new IllegalArgumentException("Invalid deadline progress fact");
  }
}
