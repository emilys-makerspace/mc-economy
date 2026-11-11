package ca.emilybelnavis.mcmods.economy.simulation.activity;

import static ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityFacts.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.time.Clock;
import java.util.*;

/** Worker-confined prospective observations; all effects compose with the source journal commit. */
public final class ActivityStore {
  private ActivityStore() {}

  private static final Journal.Kind INITIALIZE =
      new Journal.Kind("economy:a8", "economy:a8/activity/initialize");
  private static final Journal.Kind SEED =
      new Journal.Kind("economy:a8", "economy:a8/activity/seed-capacity");

  public record Activation(
      WorldId world,
      long originMillis,
      long activatedMillis,
      long observedMillis,
      long sealedThroughDay,
      String capacityCursor,
      boolean ready) {}

  /** Creates only the immutable activation. Caller supplies bounded capacity pages with seed(). */
  public static boolean bootstrap(FoundationStore store, Clock wall) {
    var existing = store.transaction(tx -> activation(tx::query));
    if (existing != null) return existing.ready();
    var origin =
        store.transaction(
            tx ->
                tx.query(
                    "SELECT origin_ms,observed_wall_ms FROM financial_calendar WHERE singleton=1",
                    List.of(),
                    1));
    if (origin.isEmpty()) return false;
    var world = store.snapshot().worldId();
    long originMillis = Long.parseLong(origin.getFirst().get("origin_ms"));
    long activated =
        Math.max(
            Math.max(0, wall.millis()),
            Math.max(originMillis, Long.parseLong(origin.getFirst().get("observed_wall_ms"))));
    var manifest = new Manifest(world, originMillis, activated, SOURCE_MANIFEST);
    String payload = ActivityFacts.encode(manifest);
    var operation = stable("initialize|" + world.value());
    var actor = new SystemActorId(stable("actor|" + world.value()));
    var request = new Journal.CanonicalRequest(1, Map.of("manifest", payload));
    var registration =
        new JournalEngine.Registration(
            1,
            (intent, reads) -> {
              if (!request.equals(intent.request()) || initialized(reads))
                return reject("ACTIVITY_ACTIVATION_CONFLICT");
              if (!reads
                  .query(
                      "SELECT payload FROM journal_records WHERE record_type=? LIMIT 1",
                      List.of(MANIFEST),
                      1)
                  .isEmpty()) throw failure("Activity activation projection is missing");
              return new JournalEngine.Commit(
                  List.of(new Journal.Record(MANIFEST, 1, payload)),
                  new Journal.Result(1, "ACTIVITY_INITIALIZED", ""),
                  tx ->
                      tx.execute(
                          "INSERT INTO activity_activation VALUES(1,?,?,?,?,?,-1,'',0,?)",
                          List.of(
                              world.value().toString(),
                              Long.toString(originMillis),
                              Long.toString(activated),
                              Long.toString(activated),
                              SOURCE_MANIFEST,
                              payload)));
            });
    var engine =
        new JournalEngine(
            store,
            (candidate, kind) -> candidate.equals(actor) && kind.equals(INITIALIZE),
            Map.of(INITIALIZE, registration));
    committed(
        engine.submit(
            new Journal.Intent(
                new OperationId(operation),
                new RequestId(operation),
                actor,
                INITIALIZE,
                request,
                null,
                null)));
    var retained = store.transaction(tx -> activation(tx::query));
    if (retained == null) throw failure("Activity activation projection is missing");
    return retained.ready();
  }

  public static boolean initialized(JournalEngine.ReadView reads) {
    return activation(reads) != null;
  }

  public static boolean ready(JournalEngine.ReadView reads) {
    var state = activation(reads);
    return state != null && state.ready();
  }

  public static Activation activation(JournalEngine.ReadView reads) {
    var rows = reads.query("SELECT * FROM activity_activation WHERE singleton=1", List.of(), 1);
    if (rows.isEmpty()) return null;
    var r = rows.getFirst();
    return new Activation(
        new WorldId(canonicalUuid(r.get("world_id"))),
        number(r, "origin_ms"),
        number(r, "activated_ms"),
        number(r, "observed_ms"),
        number(r, "sealed_through_day"),
        r.get("capacity_cursor"),
        "1".equals(r.get("capacity_ready")));
  }

  /** One journal turn for a <=16-row raw-account keyset page; empty final pages are valid. */
  public static boolean seed(
      FoundationStore store,
      List<Capacity> capacities,
      String nextCursor,
      boolean complete,
      long accountingMillis) {
    var state = store.transaction(tx -> activation(tx::query));
    if (state == null) throw failure("Activity manifest is not initialized");
    if (state.ready()) return true;
    if (capacities.size() > 16
        || nextCursor.compareTo(state.capacityCursor()) < 0
        || (!complete && nextCursor.equals(state.capacityCursor())))
      throw new IllegalArgumentException("Activity capacity seed cursor");
    var operation =
        stable(
            "seed|"
                + state.world().value()
                + "|"
                + state.capacityCursor()
                + "|"
                + nextCursor
                + "|"
                + complete);
    var actor = new SystemActorId(stable("actor|" + state.world().value()));
    long accounting = accountingTime(state, accountingMillis);
    var changed = store.transaction(tx -> changedCapacities(tx::query, capacities));
    var batch =
        new Batch(
            state.world(),
            operation,
            accounting,
            day(state, accounting),
            List.of(),
            changed,
            nextCursor,
            true,
            complete);
    String payload = ActivityFacts.encode(batch);
    var request = new Journal.CanonicalRequest(1, Map.of("batch", payload));
    var registration =
        new JournalEngine.Registration(
            1,
            (intent, reads) -> {
              var current = activation(reads);
              if (!request.equals(intent.request())
                  || current == null
                  || current.ready()
                  || !state.capacityCursor().equals(current.capacityCursor()))
                return reject("ACTIVITY_SEED_CONFLICT");
              validateCapacities(reads, changed);
              capacityTotals(reads, changed);
              return new JournalEngine.Commit(
                  List.of(new Journal.Record(BATCH, 1, payload)),
                  new Journal.Result(1, "ACTIVITY_CAPACITY_SEEDED", nextCursor),
                  tx -> apply(tx, batch, payload));
            });
    var engine =
        new JournalEngine(
            store,
            (candidate, kind) -> candidate.equals(actor) && kind.equals(SEED),
            Map.of(SEED, registration));
    committed(
        engine.submit(
            new Journal.Intent(
                new OperationId(operation),
                new RequestId(operation),
                actor,
                SEED,
                request,
                null,
                null)));
    return store.transaction(tx -> ready(tx::query));
  }

  public static JournalEngine.Decision decorate(
      Journal.Intent intent,
      JournalEngine.ReadView reads,
      JournalEngine.Commit source,
      List<Draft> drafts,
      List<Capacity> capacities,
      long accountingMillis) {
    var state = activation(reads);
    if (state == null
        || source.records().stream()
            .anyMatch(r -> r.type().equals(MANIFEST) || r.type().equals(BATCH))) return source;
    if (drafts.size() > MAX_OBSERVATIONS || capacities.size() > MAX_CAPACITIES)
      return reject("ACTIVITY_BATCH_BOUND");
    capacities = changedCapacities(reads, capacities);
    if (drafts.isEmpty() && capacities.isEmpty()) return source;
    try {
      if (drafts.size() > MAX_OBSERVATIONS || capacities.size() > MAX_CAPACITIES)
        return reject("ACTIVITY_BATCH_BOUND");
      long accounting = accountingTime(state, accountingMillis), day = day(state, accounting);
      var observations = new ArrayList<Observation>();
      for (int i = 0; i < drafts.size(); i++)
        observations.add(
            observation(
                state.world(), intent.operationId().value(), i, drafts.get(i), accounting, day));
      var batch =
          new Batch(
              state.world(),
              intent.operationId().value(),
              accounting,
              day,
              observations,
              capacities,
              state.capacityCursor(),
              false,
              state.ready());
      validateCapacities(reads, capacities);
      capacityTotals(reads, capacities);
      // Read/check all resulting sums before source projections or effects run.
      for (var total : totals(observations).entrySet()) {
        var key = total.getKey();
        var rows =
            reads.query(
                "SELECT count,minor FROM activity_day_counters WHERE day_index=? AND currency=? AND kind=?",
                List.of(Long.toString(day), key.currency(), key.kind()),
                1);
        if (!rows.isEmpty()) {
          Math.addExact(number(rows.getFirst(), "count"), total.getValue()[0]);
          Math.addExact(number(rows.getFirst(), "minor"), total.getValue()[1]);
        }
      }
      String payload = ActivityFacts.encode(batch);
      var records = new ArrayList<>(source.records());
      records.add(new Journal.Record(BATCH, 1, payload));
      return new JournalEngine.Commit(
          records,
          source.result(),
          tx -> {
            source.projections().apply(tx);
            apply(tx, batch, payload);
          });
    } catch (ArithmeticException overflow) {
      return reject("ACTIVITY_COUNTER_OVERFLOW");
    } catch (IllegalArgumentException bound) {
      return reject("ACTIVITY_BATCH_BOUND");
    }
  }

  private static void validateCapacities(JournalEngine.ReadView reads, List<Capacity> capacities) {
    long currentCount =
        Long.parseLong(
            reads
                .query("SELECT count(*) AS count FROM activity_capacity", List.of(), 1)
                .getFirst()
                .get("count"));
    long added = 0;
    for (var c : capacities) {
      var rows =
          reads.query(
              "SELECT bank,currency FROM activity_capacity WHERE account_id=?",
              List.of(c.account().toString()),
              1);
      if (rows.isEmpty()) added++;
      if (!rows.isEmpty()
          && (!c.bank().equals(rows.getFirst().get("bank"))
              || !c.currency().name().equals(rows.getFirst().get("currency"))))
        throw new IllegalArgumentException("Changed activity capacity identity");
    }
    if (Math.addExact(currentCount, added) > MAX_CAPACITIES)
      throw new IllegalArgumentException("Activity capacity manifest bound");
  }

  private static List<Capacity> changedCapacities(
      JournalEngine.ReadView reads, List<Capacity> capacities) {
    var changed = new ArrayList<Capacity>();
    for (var c : capacities) {
      var rows =
          reads.query(
              "SELECT bank,currency,free_minor FROM activity_capacity WHERE account_id=?",
              List.of(c.account().toString()),
              1);
      if (rows.isEmpty()
          || !c.bank().equals(rows.getFirst().get("bank"))
          || !c.currency().name().equals(rows.getFirst().get("currency"))
          || c.freeMinor() != number(rows.getFirst(), "free_minor")) changed.add(c);
    }
    return List.copyOf(changed);
  }

  private static Map<String, Long> capacityTotals(
      JournalEngine.ReadView reads, List<Capacity> capacities) {
    var deltas = new LinkedHashMap<String, Long>();
    for (var c : capacities) {
      var prior =
          reads.query(
              "SELECT free_minor FROM activity_capacity WHERE account_id=?",
              List.of(c.account().toString()),
              1);
      long old = prior.isEmpty() ? 0 : number(prior.getFirst(), "free_minor");
      deltas.merge(c.currency().name(), Math.subtractExact(c.freeMinor(), old), Math::addExact);
    }
    var result = new LinkedHashMap<String, Long>();
    for (var entry : deltas.entrySet()) {
      var prior =
          reads.query(
              "SELECT free_minor FROM activity_capacity_totals WHERE currency=?",
              List.of(entry.getKey()),
              1);
      long total =
          Math.addExact(
              prior.isEmpty() ? 0 : number(prior.getFirst(), "free_minor"), entry.getValue());
      if (total < 0) throw new IllegalArgumentException("Negative aggregate capacity");
      result.put(entry.getKey(), total);
    }
    return result;
  }

  private static void apply(FoundationStore.Transaction tx, Batch batch, String payload) {
    String operation = batch.operation().toString();
    var source =
        tx.query(
            "SELECT sequence FROM journal_transactions WHERE world_id=? AND operation_id=?",
            List.of(batch.world().value().toString(), operation),
            1);
    if (source.size() != 1) throw failure("Activity capacity history lacks its source transaction");
    String sequence = source.getFirst().get("sequence");
    var totals = capacityTotals(tx::query, batch.capacities());
    tx.execute(
        "INSERT INTO activity_batches VALUES(?,?,?,?,?,?)",
        List.of(
            operation,
            batch.world().value().toString(),
            Long.toString(batch.accountingMillis()),
            Long.toString(batch.day()),
            batch.seed() ? "1" : "0",
            payload));
    for (var o : batch.observations()) {
      var d = o.draft();
      tx.execute(
          "INSERT INTO activity_observations VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          Arrays.asList(
              o.id().toString(),
              o.world().value().toString(),
              operation,
              Integer.toString(o.ordinal()),
              d.source(),
              Integer.toString(d.sourceVersion()),
              d.kind().name(),
              d.currency().name(),
              Long.toString(d.minor()),
              d.subject().toString(),
              Long.toString(d.effectiveMillis()),
              Long.toString(o.accountingMillis()),
              Long.toString(o.day()),
              d.reversal() == null ? null : d.reversal().toString(),
              d.publication() == null ? null : d.publication().toString(),
              "1"));
    }
    for (var total : totals(batch.observations()).entrySet()) {
      var key = total.getKey();
      tx.execute(
          "INSERT INTO activity_day_counters VALUES(?,?,?,?,?) ON CONFLICT(day_index,currency,kind) DO UPDATE SET count=count+excluded.count,minor=minor+excluded.minor",
          List.of(
              Long.toString(batch.day()),
              key.currency(),
              key.kind(),
              Long.toString(total.getValue()[0]),
              Long.toString(total.getValue()[1])));
    }
    for (var c : batch.capacities()) {
      tx.execute(
          "INSERT INTO activity_capacity_history VALUES(?,?,?,?,?,?,?)",
          List.of(
              operation,
              c.account().toString(),
              c.bank(),
              c.currency().name(),
              Long.toString(c.freeMinor()),
              Long.toString(batch.accountingMillis()),
              sequence));
      tx.execute(
          "INSERT INTO activity_capacity VALUES(?,?,?,?,?,?) ON CONFLICT(account_id) DO UPDATE SET free_minor=excluded.free_minor,accounting_ms=excluded.accounting_ms,operation_id=excluded.operation_id",
          List.of(
              c.account().toString(),
              c.bank(),
              c.currency().name(),
              Long.toString(c.freeMinor()),
              Long.toString(batch.accountingMillis()),
              operation));
    }
    for (var total : totals.entrySet()) {
      tx.execute(
          "INSERT INTO activity_capacity_totals VALUES(?,?,?) ON CONFLICT(currency) DO UPDATE SET free_minor=excluded.free_minor,accounting_ms=excluded.accounting_ms",
          List.of(
              total.getKey(),
              Long.toString(total.getValue()),
              Long.toString(batch.accountingMillis())));
      tx.execute(
          "INSERT INTO activity_capacity_totals_history VALUES(?,?,?,?,?)",
          List.of(
              operation,
              total.getKey(),
              Long.toString(total.getValue()),
              Long.toString(batch.accountingMillis()),
              sequence));
    }
    tx.execute(
        "UPDATE activity_activation SET observed_ms=?,capacity_cursor=?,capacity_ready=? WHERE singleton=1",
        List.of(
            Long.toString(batch.accountingMillis()),
            batch.capacityCursor(),
            batch.ready() ? "1" : "0"));
  }

  record CounterKey(String currency, String kind) {}

  private static Map<CounterKey, long[]> totals(List<Observation> observations) {
    var totals = new LinkedHashMap<CounterKey, long[]>();
    for (var o : observations) {
      var key = new CounterKey(o.draft().currency().name(), o.draft().kind().name());
      var total = totals.computeIfAbsent(key, ignored -> new long[2]);
      total[0] = Math.addExact(total[0], 1);
      total[1] = Math.addExact(total[1], o.draft().minor());
    }
    return totals;
  }

  private static long accountingTime(Activation state, long candidate) {
    long value = Math.max(Math.max(candidate, state.activatedMillis()), state.observedMillis());
    long firstOpen =
        Math.addExact(
            state.originMillis(),
            Math.multiplyExact(Math.addExact(state.sealedThroughDay(), 1), DAY_MILLIS));
    return Math.max(value, firstOpen);
  }

  private static long day(Activation state, long accounting) {
    return Math.subtractExact(accounting, state.originMillis()) / DAY_MILLIS;
  }

  private static long number(Map<String, String> row, String key) {
    return Long.parseLong(row.get(key));
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }

  private static StorageException failure(String message) {
    return new StorageException(StorageException.Kind.SCHEMA, message);
  }

  private static void committed(Journal.Outcome outcome) {
    if (outcome.status() != Journal.Status.COMMITTED)
      throw failure("Activity bootstrap did not commit: " + outcome.result().code());
  }
}
