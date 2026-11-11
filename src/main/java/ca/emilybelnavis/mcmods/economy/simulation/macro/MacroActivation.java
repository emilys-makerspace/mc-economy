package ca.emilybelnavis.mcmods.economy.simulation.macro;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.simulation.activity.*;
import ca.emilybelnavis.mcmods.economy.simulation.prime.*;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import com.google.gson.Gson;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Trusted prospective API only. Production lifecycle activation is intentionally a later package.
 */
public final class MacroActivation {
  private MacroActivation() {}

  public static final String FACT = "economy:a8/macro-activation";
  private static final Gson JSON = new Gson();
  private static final Journal.Kind KIND =
      new Journal.Kind("economy:a8", "economy:a8/macro/activate");

  public record Receipt(
      UUID id,
      WorldId world,
      long activationMillis,
      long firstEffectiveMillis,
      long handoverMillis,
      UUID config,
      UUID initialPolicy,
      UUID successorEvent) {
    public Receipt {
      Objects.requireNonNull(id);
      Objects.requireNonNull(world);
      Objects.requireNonNull(config);
      Objects.requireNonNull(initialPolicy);
      Objects.requireNonNull(successorEvent);
      if (activationMillis < 0
          || firstEffectiveMillis != Math.addExact(activationMillis, ActivityFacts.DAY_MILLIS)
          || handoverMillis < firstEffectiveMillis
          || handoverMillis - firstEffectiveMillis >= 86_400_000L)
        throw new IllegalArgumentException("Macro activation boundaries");
    }
  }

  public static Optional<Receipt> read(JournalEngine.ReadView reads, WorldId world) {
    if (reads
        .query(
            "SELECT name FROM sqlite_schema WHERE type='table' AND name='macro_activations'",
            List.of(),
            1)
        .isEmpty()) return Optional.empty();
    var rows =
        reads.query(
            "SELECT payload FROM macro_activations WHERE world_id=?",
            List.of(world.value().toString()),
            1);
    return rows.isEmpty() ? Optional.empty() : Optional.of(decode(rows.getFirst().get("payload")));
  }

  public static Receipt activate(
      FoundationStore store,
      EconomicClock clock,
      DeadlineDispatcher dispatcher,
      UUID id,
      long activationMillis,
      MacroControls.Policy initial,
      Instant now) {
    var world = store.snapshot().worldId();
    UUID operation = MacroFacts.stable("activation|" + world.value() + "|" + id);
    var existing = store.transaction(tx -> read(tx::query, world));
    if (existing.isPresent()) {
      var original = existing.get();
      if (!original.id().equals(id)
          || original.activationMillis() != activationMillis
          || !original.initialPolicy().equals(initial.id())
          || !original.config().equals(initial.config().id()))
        throw new IllegalArgumentException("Original macro activation conflict");
      var policy =
          store.transaction(tx -> MacroControls.current(tx::query, world, activationMillis));
      if (!policy.equals(initial))
        throw new IllegalArgumentException("Original initial policy conflict");
      return original;
    }
    var request =
        new Journal.CanonicalRequest(
            1,
            Map.of(
                "activation",
                id.toString(),
                "A",
                Long.toString(activationMillis),
                "policy",
                MacroFacts.digest(MacroFacts.encode(initial))));
    var actor = new SystemActorId(MacroFacts.stable("activation-actor|" + world.value()));
    var registration =
        new JournalEngine.Registration(
            1,
            (intent, reads) -> {
              if (!request.equals(intent.request())
                  || read(reads, world).isPresent()
                  || initial.effectiveMillis() != activationMillis
                  || initial.revision() != 1
                  || !initial.world().equals(world))
                throw new IllegalArgumentException("Macro activation request conflict");
              long frontier = MacroControls.frontier(reads, now.toEpochMilli());
              if (activationMillis <= frontier)
                throw new IllegalArgumentException(
                    "Macro activation must exceed every observed frontier");
              var source = ActivityStore.activation(reads);
              var seeded =
                  reads
                      .query(
                          "SELECT MAX(accounting_ms) AS ready FROM activity_batches WHERE seed=1",
                          List.of(),
                          1)
                      .getFirst()
                      .get("ready");
              if (source == null
                  || !source.ready()
                  || seeded == null
                  || Math.max(source.activatedMillis(), Long.parseLong(seeded)) > activationMillis)
                throw new IllegalArgumentException(
                    "Complete activation observation coverage required");
              var prior =
                  reads.query(
                      "SELECT id,day_index FROM macro_window_opens ORDER BY day_index DESC LIMIT 1",
                      List.of(),
                      1);
              long first = (activationMillis - source.originMillis()) / ActivityFacts.DAY_MILLIS;
              if (!prior.isEmpty()
                  && (Long.parseLong(prior.getFirst().get("day_index")) >= first
                      || reads
                              .query(
                                  "SELECT currency FROM macro_window_closes WHERE open_id=?",
                                  List.of(prior.getFirst().get("id")),
                                  11)
                              .size()
                          != 11))
                throw new IllegalArgumentException(
                    "Existing source window is incomplete or overlaps activation");
              var prime =
                  CurrencyPrimes.prepareActivation(
                      reads, world, operation, id, activationMillis, initial.config().id(), now);
              long effective = Math.addExact(activationMillis, ActivityFacts.DAY_MILLIS);
              var event = MacroProvider.event(world, effective);
              var successor = dispatcher.proposedSuccessor(reads, MacroProvider.PROVIDER, event);
              if (CurrencyPrimeFacts.decodeActivation(prime.records().getFirst().payload())
                      .originMillis()
                  != source.originMillis())
                throw new IllegalArgumentException(
                    "Activity and financial calendar origins differ");
              var retirement =
                  clock.prepareRetirement(reads, operation, FxService.SYSTEM, effective, successor);
              var retired = FixedRetirements.decode(retirement.records().getFirst().payload());
              var receipt =
                  new Receipt(
                      id,
                      world,
                      activationMillis,
                      effective,
                      retired.firstExcludedMillis(),
                      initial.config().id(),
                      initial.id(),
                      event.id());
              String payload = encode(receipt);
              var control = MacroControls.prepareInitial(reads, world, operation, initial);
              var records = new ArrayList<Journal.Record>();
              records.add(new Journal.Record(FACT, 1, payload));
              records.addAll(prime.records());
              records.addAll(retirement.records());
              records.addAll(control.records());
              return new JournalEngine.Commit(
                  records,
                  new Journal.Result(1, "MACRO_ACTIVATED", id.toString()),
                  tx -> {
                    prime.projections().apply(tx);
                    retirement.projections().apply(tx);
                    control.projections().apply(tx);
                    tx.execute(
                        "INSERT INTO macro_activations VALUES(?,?,?,?)",
                        List.of(
                            world.value().toString(),
                            id.toString(),
                            operation.toString(),
                            payload));
                  });
            });
    var result =
        new JournalEngine(
                store, (a, k) -> a.equals(actor) && k.equals(KIND), Map.of(KIND, registration))
            .submit(
                new Journal.Intent(
                    new OperationId(operation),
                    new RequestId(operation),
                    actor,
                    KIND,
                    request,
                    null,
                    null));
    if (result.status() != Journal.Status.COMMITTED)
      throw new IllegalArgumentException("Macro activation: " + result.result().code());
    return store.transaction(tx -> read(tx::query, world).orElseThrow());
  }

  /** Readiness includes the dispatcher's separate final completion turn. */
  public static boolean ready(JournalEngine.ReadView reads, WorldId world, long at) {
    var a = read(reads, world);
    if (a.isEmpty() || at < a.get().firstEffectiveMillis()) return true;
    long due =
        a.get().firstEffectiveMillis()
            + ((at - a.get().firstEffectiveMillis()) / ActivityFacts.DAY_MILLIS)
                * ActivityFacts.DAY_MILLIS;
    return !reads
        .query(
            "SELECT event_id FROM financial_deadline_events WHERE provider=? AND effective_ms=? AND state='COMPLETE'",
            List.of(MacroProvider.PROVIDER, Long.toString(due)),
            1)
        .isEmpty();
  }

  public static List<String> statements() {
    return List.of(
        "CREATE TABLE macro_activations(world_id TEXT PRIMARY KEY,id TEXT NOT NULL UNIQUE,operation_id TEXT NOT NULL UNIQUE,payload TEXT NOT NULL)",
        "CREATE TRIGGER macro_activations_update BEFORE UPDATE ON macro_activations BEGIN SELECT RAISE(ABORT,'immutable macro activation'); END",
        "CREATE TRIGGER macro_activations_delete BEFORE DELETE ON macro_activations BEGIN SELECT RAISE(ABORT,'immutable macro activation'); END",
        "CREATE TRIGGER macro_activations_replace BEFORE INSERT ON macro_activations WHEN EXISTS(SELECT 1 FROM macro_activations WHERE world_id=NEW.world_id OR id=NEW.id OR operation_id=NEW.operation_id) BEGIN SELECT RAISE(ABORT,'existing macro activation'); END");
  }

  public static RecordCatalog register(RecordCatalog c) {
    return c.with(new RecordCatalog.Key(FACT, 1), MacroActivation::decode);
  }

  public static String encode(Receipt r) {
    return JSON.toJson(r);
  }

  public static Receipt decode(String text) {
    if (text == null || text.length() > 4096)
      throw new IllegalArgumentException("Macro activation bound");
    var r = JSON.fromJson(text, Receipt.class);
    if (r == null || !encode(r).equals(text))
      throw new IllegalArgumentException("Macro activation canonical encoding");
    return r;
  }

  public static boolean owns(Connection c, String operation) throws SQLException {
    return table(c)
        && "1"
            .equals(
                scalar(
                    c, "SELECT COUNT(*) FROM macro_activations WHERE operation_id=?", operation));
  }

  private static boolean table(Connection c) throws SQLException {
    return "1"
        .equals(
            scalar(
                c,
                "SELECT COUNT(*) FROM sqlite_schema WHERE type='table' AND name='macro_activations'"));
  }

  public static void requireChild(Connection c, String operation, String target, long effective)
      throws SQLException {
    String progress =
        scalar(
            c,
            "SELECT r.payload FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE t.operation_id=? AND r.record_type=? AND r.record_version=1",
            operation,
            DeadlineDispatcher.PROGRESS_RECORD);
    if (progress == null) throw new SQLException("Macro child lacks dispatcher progress");
    String[] f = progress.split("\\|", -1);
    String world = scalar(c, "SELECT world_id FROM foundation_metadata WHERE singleton=1");
    String receipt = scalar(c, "SELECT payload FROM macro_activations WHERE world_id=?", world);
    if (receipt == null) throw new SQLException("Macro child lacks atomic activation");
    var a = decode(receipt);
    String activationSequence =
        scalar(
            c,
            "SELECT t.sequence FROM macro_activations a JOIN journal_transactions t ON t.operation_id=a.operation_id WHERE a.world_id=?",
            world);
    String sequence =
        scalar(c, "SELECT sequence FROM journal_transactions WHERE operation_id=?", operation);
    if (effective < a.firstEffectiveMillis()
        || (effective - a.firstEffectiveMillis()) % ActivityFacts.DAY_MILLIS != 0
        || activationSequence == null
        || sequence == null
        || Long.parseLong(activationSequence) >= Long.parseLong(sequence))
      throw new SQLException("Macro child activation chronology");
    var event = MacroProvider.event(new WorldId(UUID.fromString(world)), effective);
    if (f.length != 10
        || !f[1].equals(event.id().toString())
        || !f[2].equals(MacroProvider.PROVIDER)
        || !f[3].equals(Long.toString(effective))
        || !f[4].equals("0")
        || !f[6].equals(target)
        || !f[8].equals("0")
        || !DeadlineDispatcher.childOperation(new WorldId(UUID.fromString(world)), event, target)
            .toString()
            .equals(operation)) throw new SQLException("Macro actual child linkage");
  }

  public static void validate(Connection c) throws SQLException {
    try {
      for (var sql : statements()) {
        String name = sql.split(" ")[2].split("\\(", 2)[0];
        if (!sql.equals(scalar(c, "SELECT sql FROM sqlite_schema WHERE name=?", name)))
          throw new SQLException("Macro activation schema");
      }
      long count = 0;
      try (var q = c.createStatement();
          var rows = q.executeQuery("SELECT * FROM macro_activations")) {
        while (rows.next()) {
          count++;
          var a = decode(rows.getString("payload"));
          String op = rows.getString("operation_id");
          if (!a.id().toString().equals(rows.getString("id"))
              || !a.world().value().toString().equals(rows.getString("world_id"))
              || !op.equals(
                  MacroFacts.stable("activation|" + a.world().value() + "|" + a.id()).toString()))
            throw new SQLException("Macro activation projection");
          String
              prime =
                  scalar(
                      c, "SELECT payload FROM currency_prime_activation WHERE operation_id=?", op),
              retired =
                  scalar(
                      c, "SELECT payload FROM fixed_system_retirements WHERE operation_id=?", op),
              policy =
                  scalar(c, "SELECT payload FROM macro_control_policies WHERE operation_id=?", op);
          if (prime == null || retired == null || policy == null)
            throw new SQLException("Partial macro activation");
          var p = CurrencyPrimeFacts.decodeActivation(prime);
          var r = FixedRetirements.decode(retired);
          var mode = MacroFacts.decodePolicy(policy);
          if (!p.id().equals(a.id())
              || !p.world().equals(a.world())
              || p.effectiveMillis() != a.activationMillis()
              || p.firstEventMillis() != a.firstEffectiveMillis()
              || !p.config().equals(a.config())
              || !mode.id().equals(a.initialPolicy())
              || mode.revision() != 1
              || mode.effectiveMillis() != a.activationMillis()
              || !mode.world().equals(a.world())
              || !mode.config().id().equals(a.config())
              || r.takeoverMillis() != a.firstEffectiveMillis()
              || r.firstExcludedMillis() != a.handoverMillis()
              || !r.system().equals(FxService.SYSTEM)
              || !r.successor().equals(MacroProvider.PROVIDER)
              || !r.successorEvent().equals(a.successorEvent())
              || !a.successorEvent()
                  .equals(MacroProvider.event(a.world(), a.firstEffectiveMillis()).id()))
            throw new SQLException("Macro atomic boundary/source linkage");
          if (!"1"
              .equals(
                  scalar(
                      c,
                      "SELECT COUNT(*) FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE t.operation_id=? AND r.record_type=? AND r.record_version=1 AND r.payload=?",
                      op,
                      FACT,
                      rows.getString("payload")))) throw new SQLException("Macro activation fact");
        }
      }
      if (!Long.toString(count)
          .equals(scalar(c, "SELECT COUNT(*) FROM journal_records WHERE record_type=?", FACT)))
        throw new SQLException("Orphan macro activation fact");
    } catch (RuntimeException malformed) {
      throw new SQLException("Invalid macro activation", malformed);
    }
  }

  private static String scalar(Connection c, String sql, String... p) throws SQLException {
    try (var q = c.prepareStatement(sql)) {
      for (int i = 0; i < p.length; i++) q.setString(i + 1, p[i]);
      try (var r = q.executeQuery()) {
        return r.next() ? r.getString(1) : null;
      }
    }
  }
}
