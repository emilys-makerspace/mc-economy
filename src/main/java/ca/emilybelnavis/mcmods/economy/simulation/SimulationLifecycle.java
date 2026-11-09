package ca.emilybelnavis.mcmods.economy.simulation;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityStore;
import ca.emilybelnavis.mcmods.economy.simulation.macro.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import com.google.gson.Gson;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Bounded worker lifecycle manifest and prospective first activation. No independent clock. */
public final class SimulationLifecycle {
  private SimulationLifecycle() {}

  public static final String FACT = "economy:a8/production-manifest";
  private static final Gson JSON = new Gson();
  private static final Journal.Kind KIND =
      new Journal.Kind("economy:a8", "economy:a8/production/activate");

  public record Manifest(
      WorldId world,
      SimulationConfig config,
      String activity,
      String catalog,
      String cash,
      String provider) {
    public Manifest {
      Objects.requireNonNull(world);
      Objects.requireNonNull(config);
      if (!ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityFacts.SOURCE_MANIFEST.equals(
              activity)
          || !ca.emilybelnavis.mcmods.economy.trade.catalog.StandardQuantities.MANIFEST_SHA256
              .equals(catalog)
          || !ca.emilybelnavis.mcmods.economy.trade.cash.CashDenominations.SHA256.equals(cash)
          || !provider.equals(MacroProvider.PROVIDER))
        throw new IllegalArgumentException("Simulation source manifest");
    }
  }

  public record Status(boolean installed, String reason) {}

  public static Manifest read(JournalEngine.ReadView reads) {
    var rows =
        reads.query("SELECT payload FROM simulation_production WHERE singleton=1", List.of(), 1);
    return rows.isEmpty() ? null : decode(rows.getFirst().get("payload"));
  }

  public static Status prepare(
      FoundationStore store,
      EconomicClock clock,
      DeadlineDispatcher dispatcher,
      SimulationConfig config,
      Instant now) {
    if (!store.transaction(tx -> ActivityStore.ready(tx::query)))
      return new Status(false, "ACTIVITY_SEED_PENDING");
    var denomination =
        store.transaction(
            tx -> ca.emilybelnavis.mcmods.economy.journal.RebaseSettlement.denomination(tx::query));
    if (denomination.isEmpty()) return new Status(false, "WORLD_CURRENCY_REQUIRED");
    var manifest =
        new Manifest(
            store.snapshot().worldId(),
            config,
            ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityFacts.SOURCE_MANIFEST,
            ca.emilybelnavis.mcmods.economy.trade.catalog.StandardQuantities.MANIFEST_SHA256,
            ca.emilybelnavis.mcmods.economy.trade.cash.CashDenominations.SHA256,
            MacroProvider.PROVIDER);
    var prior = store.transaction(tx -> read(tx::query));
    if (prior != null && !prior.equals(manifest))
      throw new IllegalArgumentException(
          "Persisted simulation configuration differs: use future policy controls; initial lifecycle settings are immutable");
    if (prior == null) {
      UUID op = MacroFacts.stable("production|" + manifest.world().value());
      String payload = encode(manifest);
      var actor =
          new SystemActorId(MacroFacts.stable("production-actor|" + manifest.world().value()));
      var request = new Journal.CanonicalRequest(1, Map.of("manifest", MacroFacts.digest(payload)));
      var reg =
          new JournalEngine.Registration(
              1,
              (i, r) -> {
                if (!request.equals(i.request()) || read(r) != null)
                  throw new IllegalArgumentException("Production manifest conflict");
                return new JournalEngine.Commit(
                    List.of(new Journal.Record(FACT, 1, payload)),
                    new Journal.Result(
                        1, "SIMULATION_INSTALLED", manifest.world().value().toString()),
                    tx ->
                        tx.execute(
                            "INSERT INTO simulation_production VALUES(1,?,?,?)",
                            List.of(manifest.world().value().toString(), op.toString(), payload)));
              });
      var result =
          new JournalEngine(store, (a, k) -> a.equals(actor) && k.equals(KIND), Map.of(KIND, reg))
              .submit(
                  new Journal.Intent(
                      new OperationId(op), new RequestId(op), actor, KIND, request, null, null));
      if (result.status() != Journal.Status.COMMITTED)
        throw new IllegalArgumentException("Production manifest: " + result.result().code());
      return new Status(false, "RATE_ACTIVATION_PENDING");
    }
    if (config.rates()
        && store.transaction(tx -> MacroActivation.read(tx::query, manifest.world())).isEmpty()) {
      long frontier = now.toEpochMilli();
      for (String sql :
          List.of(
              "SELECT observed_wall_ms AS value FROM economic_metadata WHERE singleton=1",
              "SELECT MAX(frontier_ms) AS value FROM economic_systems",
              "SELECT MAX(active_observed_ms) AS value FROM economic_systems",
              "SELECT observed_wall_ms AS value FROM financial_calendar WHERE singleton=1",
              "SELECT last_effective_ms AS value FROM financial_calendar WHERE singleton=1",
              "SELECT MAX(effective_ms) AS value FROM financial_deadline_events",
              "SELECT observed_ms AS value FROM activity_activation WHERE singleton=1")) {
        var rows = store.transaction(tx -> tx.query(sql, List.of(), 1));
        if (!rows.isEmpty() && rows.getFirst().get("value") != null)
          frontier = Math.max(frontier, Long.parseLong(rows.getFirst().get("value")));
      }
      long
          origin =
              store.transaction(
                  tx ->
                      Long.parseLong(
                          tx.query(
                                  "SELECT origin_ms FROM financial_calendar WHERE singleton=1",
                                  List.of(),
                                  1)
                              .getFirst()
                              .get("origin_ms"))),
          day = ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityFacts.DAY_MILLIS;
      long a =
          Math.addExact(
              origin,
              Math.multiplyExact(
                  Math.addExact(Math.subtractExact(frontier, origin) / day, 1), day));
      UUID id = MacroFacts.stable("production-rates|" + manifest.world().value());
      var initial =
          new MacroControls.Policy(
              MacroFacts.stable("production-policy|" + id),
              manifest.world(),
              1,
              a,
              MacroControls.Mode.AUTO,
              config.model(),
              Map.of(),
              Map.of());
      try {
        MacroActivation.activate(store, clock, dispatcher, id, a, initial, now);
      } catch (IllegalArgumentException unavailable) {
        return new Status(false, "RATE_ACTIVATION_BLOCKED: " + unavailable.getMessage());
      }
      return new Status(false, "RATE_WARMUP_SCHEDULED");
    }
    if (!ca.emilybelnavis.mcmods.economy.trade.catalog.VillageMarketSource.step(
        store, denomination.get().currency()))
      return new Status(false, "VILLAGE_MARKET_SOURCE_PENDING");
    return new Status(true, config.rates() ? "ACTIVE" : "RATES_DISABLED");
  }

  public static List<String> statements() {
    return List.of(
        "CREATE TABLE simulation_production(singleton INTEGER PRIMARY KEY CHECK(singleton=1),world_id TEXT NOT NULL,operation_id TEXT NOT NULL UNIQUE,payload TEXT NOT NULL)",
        "CREATE TRIGGER simulation_production_update BEFORE UPDATE ON simulation_production BEGIN SELECT RAISE(ABORT,'immutable simulation manifest'); END",
        "CREATE TRIGGER simulation_production_delete BEFORE DELETE ON simulation_production BEGIN SELECT RAISE(ABORT,'immutable simulation manifest'); END",
        "CREATE TRIGGER simulation_production_replace BEFORE INSERT ON simulation_production WHEN EXISTS(SELECT 1 FROM simulation_production) BEGIN SELECT RAISE(ABORT,'existing simulation manifest'); END");
  }

  public static RecordCatalog register(RecordCatalog c) {
    return c.with(new RecordCatalog.Key(FACT, 1), SimulationLifecycle::decode);
  }

  public static String encode(Manifest m) {
    return JSON.toJson(m);
  }

  public static Manifest decode(String p) {
    if (p == null || p.length() > 16384) throw new IllegalArgumentException("Manifest bound");
    var m = JSON.fromJson(p, Manifest.class);
    if (m == null || !encode(m).equals(p))
      throw new IllegalArgumentException("Manifest canonical encoding");
    return m;
  }

  public static void validate(Connection c) throws SQLException {
    try {
      ca.emilybelnavis.mcmods.economy.trade.settlement.NpcInputReturns.validate(c);
      for (var sql : statements()) {
        String name = sql.split(" ")[2].split("\\(", 2)[0];
        if (!sql.equals(scalar(c, "SELECT sql FROM sqlite_schema WHERE name=?", name)))
          throw new SQLException("Simulation manifest schema");
      }
      String p = scalar(c, "SELECT payload FROM simulation_production WHERE singleton=1");
      if (p == null) {
        if (!"0"
            .equals(scalar(c, "SELECT COUNT(*) FROM journal_records WHERE record_type=?", FACT)))
          throw new SQLException("Orphan simulation manifest");
        return;
      }
      var m = decode(p);
      String world = m.world().value().toString(),
          op = MacroFacts.stable("production|" + world).toString();
      if (!world.equals(scalar(c, "SELECT world_id FROM foundation_metadata WHERE singleton=1"))
          || !world.equals(scalar(c, "SELECT world_id FROM simulation_production"))
          || !op.equals(scalar(c, "SELECT operation_id FROM simulation_production"))
          || !"1"
              .equals(
                  scalar(
                      c,
                      "SELECT COUNT(*) FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE r.record_type=? AND r.record_version=1 AND t.operation_id=? AND r.payload=?",
                      FACT,
                      op,
                      p))
          || !"1"
              .equals(scalar(c, "SELECT COUNT(*) FROM journal_records WHERE record_type=?", FACT)))
        throw new SQLException("Simulation manifest source");
    } catch (RuntimeException e) {
      throw new SQLException("Invalid simulation manifest", e);
    }
  }

  private static String scalar(Connection c, String sql, String... args) throws SQLException {
    try (var q = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) q.setString(i + 1, args[i]);
      try (var r = q.executeQuery()) {
        return r.next() ? r.getString(1) : null;
      }
    }
  }
}
