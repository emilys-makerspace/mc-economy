package ca.emilybelnavis.mcmods.economy.simulation.activity;

import static ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityFacts.*;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** V40 activity projections, immutable source evidence and indexed bounded readers. */
public final class ActivitySchema {
  private ActivitySchema() {}

  public static List<String> statements() {
    return List.of(
        "CREATE TABLE activity_activation(singleton INTEGER PRIMARY KEY CHECK(singleton=1),world_id TEXT NOT NULL,origin_ms INTEGER NOT NULL CHECK(typeof(origin_ms)='integer' AND origin_ms>=0),activated_ms INTEGER NOT NULL CHECK(typeof(activated_ms)='integer' AND activated_ms>=origin_ms),observed_ms INTEGER NOT NULL CHECK(typeof(observed_ms)='integer' AND observed_ms>=activated_ms),sources TEXT NOT NULL,sealed_through_day INTEGER NOT NULL CHECK(typeof(sealed_through_day)='integer' AND sealed_through_day>=-1),capacity_cursor TEXT NOT NULL,capacity_ready INTEGER NOT NULL CHECK(capacity_ready IN (0,1)),manifest_payload TEXT NOT NULL)",
        "CREATE TABLE activity_batches(operation_id TEXT PRIMARY KEY,world_id TEXT NOT NULL,accounting_ms INTEGER NOT NULL CHECK(typeof(accounting_ms)='integer' AND accounting_ms>=0),day_index INTEGER NOT NULL CHECK(typeof(day_index)='integer' AND day_index>=0),seed INTEGER NOT NULL CHECK(seed IN (0,1)),payload TEXT NOT NULL)",
        "CREATE TABLE activity_observations(id TEXT PRIMARY KEY,world_id TEXT NOT NULL,operation_id TEXT NOT NULL REFERENCES activity_batches(operation_id),ordinal INTEGER NOT NULL CHECK(typeof(ordinal)='integer' AND ordinal BETWEEN 0 AND 31),source TEXT NOT NULL,source_version INTEGER NOT NULL CHECK(typeof(source_version)='integer' AND source_version>0),kind TEXT NOT NULL CHECK(kind IN ('GOODS_SALE','GOODS_REFUND','CREDIT_DRAW','CREDIT_REPAYMENT','FX_BUY','FX_SELL','OPERATOR_CREATE','OPERATOR_RETIRE','OPERATOR_CASH_CREATE','NPC_CREATE','NPC_RETIRE')),currency TEXT NOT NULL,minor INTEGER NOT NULL CHECK(typeof(minor)='integer' AND minor>0),subject_id TEXT NOT NULL,effective_ms INTEGER NOT NULL CHECK(typeof(effective_ms)='integer' AND effective_ms>=0),accounting_ms INTEGER NOT NULL CHECK(typeof(accounting_ms)='integer' AND accounting_ms>=0),day_index INTEGER NOT NULL CHECK(typeof(day_index)='integer' AND day_index>=0),reversal_id TEXT,publication_id TEXT,classifier_version INTEGER NOT NULL CHECK(classifier_version=1),UNIQUE(world_id,operation_id,classifier_version,ordinal))",
        "CREATE TABLE activity_day_counters(day_index INTEGER NOT NULL CHECK(typeof(day_index)='integer' AND day_index>=0),currency TEXT NOT NULL,kind TEXT NOT NULL,count INTEGER NOT NULL CHECK(typeof(count)='integer' AND count>0),minor INTEGER NOT NULL CHECK(typeof(minor)='integer' AND minor>0),PRIMARY KEY(day_index,currency,kind))",
        "CREATE TABLE activity_capacity(account_id TEXT PRIMARY KEY,bank TEXT NOT NULL,currency TEXT NOT NULL,free_minor INTEGER NOT NULL CHECK(typeof(free_minor)='integer' AND free_minor>=0),accounting_ms INTEGER NOT NULL CHECK(typeof(accounting_ms)='integer' AND accounting_ms>=0),operation_id TEXT NOT NULL REFERENCES activity_batches(operation_id))",
        "CREATE TABLE activity_capacity_history(operation_id TEXT NOT NULL REFERENCES activity_batches(operation_id),account_id TEXT NOT NULL,bank TEXT NOT NULL,currency TEXT NOT NULL,free_minor INTEGER NOT NULL CHECK(typeof(free_minor)='integer' AND free_minor>=0),accounting_ms INTEGER NOT NULL CHECK(typeof(accounting_ms)='integer' AND accounting_ms>=0),journal_sequence INTEGER NOT NULL REFERENCES journal_transactions(sequence) CHECK(typeof(journal_sequence)='integer' AND journal_sequence>0),PRIMARY KEY(operation_id,account_id))",
        "CREATE TABLE activity_capacity_totals(currency TEXT PRIMARY KEY,free_minor INTEGER NOT NULL CHECK(typeof(free_minor)='integer' AND free_minor>=0),accounting_ms INTEGER NOT NULL CHECK(typeof(accounting_ms)='integer' AND accounting_ms>=0))",
        "CREATE TABLE activity_capacity_totals_history(operation_id TEXT NOT NULL REFERENCES activity_batches(operation_id),currency TEXT NOT NULL,free_minor INTEGER NOT NULL CHECK(typeof(free_minor)='integer' AND free_minor>=0),accounting_ms INTEGER NOT NULL CHECK(typeof(accounting_ms)='integer' AND accounting_ms>=0),journal_sequence INTEGER NOT NULL REFERENCES journal_transactions(sequence) CHECK(typeof(journal_sequence)='integer' AND journal_sequence>0),PRIMARY KEY(operation_id,currency))",
        "CREATE INDEX activity_observation_days ON activity_observations(day_index,currency,kind,id)",
        "CREATE INDEX activity_observation_subject ON activity_observations(subject_id,kind,id)",
        "CREATE INDEX activity_capacity_time ON activity_capacity_totals_history(currency,accounting_ms,journal_sequence)",
        "CREATE INDEX activity_capacity_account_time ON activity_capacity_history(account_id,accounting_ms,journal_sequence)",
        "CREATE INDEX activity_fact_lookup ON journal_records(record_type,record_version,payload) WHERE record_type IN ('economy:a8/activity-manifest','economy:a8/activity-batch')",
        "CREATE INDEX activity_cash_seed ON institution_financial_accounts(world_id,account_id) WHERE role='CASH'",
        "CREATE TRIGGER activity_activation_identity BEFORE UPDATE ON activity_activation WHEN NEW.world_id!=OLD.world_id OR NEW.origin_ms!=OLD.origin_ms OR NEW.activated_ms!=OLD.activated_ms OR NEW.sources!=OLD.sources OR NEW.manifest_payload!=OLD.manifest_payload OR NEW.observed_ms<OLD.observed_ms OR NEW.sealed_through_day<OLD.sealed_through_day OR NEW.capacity_cursor<OLD.capacity_cursor OR NEW.capacity_ready<OLD.capacity_ready BEGIN SELECT RAISE(ABORT,'activity activation identity/high-water'); END",
        "CREATE TRIGGER activity_activation_delete BEFORE DELETE ON activity_activation BEGIN SELECT RAISE(ABORT,'persistent activity activation'); END",
        "CREATE TRIGGER activity_activation_replace BEFORE INSERT ON activity_activation WHEN EXISTS(SELECT 1 FROM activity_activation) BEGIN SELECT RAISE(ABORT,'existing activity activation'); END",
        immutable("activity_batches", "update"),
        immutable("activity_batches", "delete"),
        immutable("activity_observations", "update"),
        immutable("activity_observations", "delete"),
        immutable("activity_capacity_history", "update"),
        immutable("activity_capacity_history", "delete"),
        immutable("activity_capacity_totals_history", "update"),
        immutable("activity_capacity_totals_history", "delete"));
  }

  private static String immutable(String table, String action) {
    return "CREATE TRIGGER "
        + table
        + "_"
        + action
        + " BEFORE "
        + action.toUpperCase(Locale.ROOT)
        + " ON "
        + table
        + " BEGIN SELECT RAISE(ABORT,'immutable activity evidence'); END";
  }

  /**
   * Full evidence validation streams batches, retaining at most the fixed 275-account capacity
   * manifest.
   */
  public static void validate(Connection connection) throws SQLException {
    validateDeclarations(connection);
    try {
      String world =
          scalar(connection, "SELECT world_id FROM foundation_metadata WHERE singleton=1");
      var activation = rows(connection, "SELECT * FROM activity_activation", List.of(), 1);
      long manifestFacts =
          count(
              connection,
              "SELECT count(*) FROM journal_records WHERE record_type='" + MANIFEST + "'");
      if (activation.isEmpty()) {
        if (manifestFacts != 0
            || count(connection, "SELECT count(*) FROM activity_batches") != 0
            || count(connection, "SELECT count(*) FROM activity_observations") != 0
            || count(connection, "SELECT count(*) FROM activity_day_counters") != 0
            || count(connection, "SELECT count(*) FROM activity_capacity") != 0
            || count(connection, "SELECT count(*) FROM activity_capacity_history") != 0
            || count(connection, "SELECT count(*) FROM activity_capacity_totals") != 0
            || count(connection, "SELECT count(*) FROM activity_capacity_totals_history") != 0)
          throw invalid("Activity data without activation");
        return;
      }
      if (manifestFacts != 1) throw invalid("Activity manifest count");
      var a = activation.getFirst();
      var manifest = decodeManifest(a.get("manifest_payload"));
      if (!manifest.world().value().toString().equals(world)
          || !a.get("world_id").equals(world)
          || manifest.originMillis() != number(a, "origin_ms")
          || manifest.activatedMillis() != number(a, "activated_ms")
          || !manifest.sources().equals(a.get("sources")))
        throw invalid("Activity manifest projection mismatch");
      requireFact(connection, world, MANIFEST, a.get("manifest_payload"), null);
      if (!Long.toString(manifest.originMillis())
          .equals(scalar(connection, "SELECT origin_ms FROM financial_calendar WHERE singleton=1")))
        throw invalid("Activity calendar origin mismatch");
      validateBatches(connection, world, a);
      validateCounters(connection);
    } catch (RuntimeException malformed) {
      throw new SQLException("Invalid activity evidence", malformed);
    }
  }

  private static void validateBatches(Connection c, String world, Map<String, String> activation)
      throws SQLException {
    long observations = 0, capacities = 0, totalHistories = 0, batches = 0;
    long observed = number(activation, "activated_ms");
    String cursor = "";
    boolean ready = false;
    var latest = new HashMap<UUID, Capacity>();
    var latestOperation = new HashMap<UUID, String>();
    var latestTime = new HashMap<UUID, Long>();
    var totals = new HashMap<String, Long>();
    var totalTime = new HashMap<String, Long>();
    String sql =
        "SELECT b.*,t.sequence AS source_sequence FROM activity_batches b JOIN journal_transactions t ON t.operation_id=b.operation_id AND t.world_id=b.world_id ORDER BY t.sequence";
    try (var statement = c.createStatement();
        var rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        var b = decodeBatch(rows.getString("payload"));
        batches++;
        if (!b.world().value().toString().equals(world)
            || !rows.getString("world_id").equals(world)
            || !b.operation().toString().equals(rows.getString("operation_id"))
            || b.accountingMillis() != rows.getLong("accounting_ms")
            || b.day() != rows.getLong("day_index")
            || b.seed() != (rows.getInt("seed") == 1)
            || b.accountingMillis() < observed
            || b.day() != (b.accountingMillis() - number(activation, "origin_ms")) / DAY_MILLIS)
          throw invalid("Activity batch projection/time mismatch");
        requireFact(c, world, BATCH, rows.getString("payload"), b.operation().toString());
        if (b.seed()) {
          if (ready
              || b.capacityCursor().compareTo(cursor) < 0
              || (!b.ready() && b.capacityCursor().equals(cursor)))
            throw invalid("Activity seed progress mismatch");
        } else if (!cursor.equals(b.capacityCursor()) || b.ready() != ready)
          throw invalid("Activity source changed seed progress");
        cursor = b.capacityCursor();
        ready = b.ready();
        observed = b.accountingMillis();
        for (var o : b.observations()) {
          validateObservation(c, o);
          observations = Math.addExact(observations, 1);
        }
        var changed = new LinkedHashSet<String>();
        for (var capacity : b.capacities()) {
          var previous = latest.put(capacity.account(), capacity);
          if (previous != null
              && (!previous.bank().equals(capacity.bank())
                  || previous.currency() != capacity.currency()))
            throw invalid("Changed capacity account identity");
          var currency = capacity.currency().name();
          changed.add(currency);
          totals.put(
              currency,
              Math.addExact(
                  totals.getOrDefault(currency, 0L),
                  Math.subtractExact(
                      capacity.freeMinor(), previous == null ? 0 : previous.freeMinor())));
          totalTime.put(currency, b.accountingMillis());
          latestOperation.put(capacity.account(), b.operation().toString());
          latestTime.put(capacity.account(), b.accountingMillis());
          var history =
              rows(
                  c,
                  "SELECT * FROM activity_capacity_history WHERE operation_id=? AND account_id=?",
                  List.of(b.operation().toString(), capacity.account().toString()),
                  1);
          if (history.size() != 1
              || !capacityMatches(
                  history.getFirst(), capacity, b.operation().toString(), b.accountingMillis())
              || number(history.getFirst(), "journal_sequence") != rows.getLong("source_sequence"))
            throw invalid("Capacity history mismatch");
          capacities = Math.addExact(capacities, 1);
        }
        if (latest.size() > MAX_CAPACITIES || totals.size() > 11)
          throw invalid("Capacity manifest bound");
        for (var currency : changed) {
          var history =
              rows(
                  c,
                  "SELECT * FROM activity_capacity_totals_history WHERE operation_id=? AND currency=?",
                  List.of(b.operation().toString(), currency),
                  1);
          if (history.size() != 1
              || number(history.getFirst(), "free_minor") != totals.get(currency)
              || number(history.getFirst(), "accounting_ms") != b.accountingMillis()
              || number(history.getFirst(), "journal_sequence") != rows.getLong("source_sequence"))
            throw invalid("Capacity total history mismatch");
          totalHistories = Math.addExact(totalHistories, 1);
        }
      }
    }
    if (batches != count(c, "SELECT count(*) FROM activity_batches")
        || batches
            != count(c, "SELECT count(*) FROM journal_records WHERE record_type='" + BATCH + "'")
        || observations != count(c, "SELECT count(*) FROM activity_observations")
        || capacities != count(c, "SELECT count(*) FROM activity_capacity_history")
        || totalHistories != count(c, "SELECT count(*) FROM activity_capacity_totals_history")
        || latest.size() != count(c, "SELECT count(*) FROM activity_capacity")
        || totals.size() != count(c, "SELECT count(*) FROM activity_capacity_totals"))
      throw invalid("Missing/orphan activity projections");
    if (observed != number(activation, "observed_ms")
        || !cursor.equals(activation.get("capacity_cursor"))
        || ready != "1".equals(activation.get("capacity_ready")))
      throw invalid("Activity activation progress mismatch");
    for (var entry : latest.entrySet()) {
      var data =
          rows(
              c,
              "SELECT * FROM activity_capacity WHERE account_id=?",
              List.of(entry.getKey().toString()),
              1);
      if (data.size() != 1
          || !capacityMatches(
              data.getFirst(),
              entry.getValue(),
              latestOperation.get(entry.getKey()),
              latestTime.get(entry.getKey()))) throw invalid("Capacity projection mismatch");
    }
    for (var entry : totals.entrySet()) {
      var data =
          rows(
              c,
              "SELECT * FROM activity_capacity_totals WHERE currency=?",
              List.of(entry.getKey()),
              1);
      if (data.size() != 1
          || number(data.getFirst(), "free_minor") != entry.getValue()
          || number(data.getFirst(), "accounting_ms") != totalTime.get(entry.getKey()))
        throw invalid("Capacity total projection mismatch");
    }
  }

  private static boolean capacityMatches(
      Map<String, String> r, Capacity value, String operation, long time) {
    return value.account().toString().equals(r.get("account_id"))
        && value.bank().equals(r.get("bank"))
        && value.currency().name().equals(r.get("currency"))
        && value.freeMinor() == number(r, "free_minor")
        && operation.equals(r.get("operation_id"))
        && time == number(r, "accounting_ms");
  }

  private static void validateObservation(Connection c, Observation o) throws SQLException {
    var data =
        rows(c, "SELECT * FROM activity_observations WHERE id=?", List.of(o.id().toString()), 1);
    if (data.size() != 1) throw invalid("Missing observation");
    var r = data.getFirst();
    var d = o.draft();
    if (!o.world().value().toString().equals(r.get("world_id"))
        || !o.operation().toString().equals(r.get("operation_id"))
        || o.ordinal() != number(r, "ordinal")
        || !d.source().equals(r.get("source"))
        || d.sourceVersion() != number(r, "source_version")
        || !d.kind().name().equals(r.get("kind"))
        || !d.currency().name().equals(r.get("currency"))
        || d.minor() != number(r, "minor")
        || !d.subject().toString().equals(r.get("subject_id"))
        || d.effectiveMillis() != number(r, "effective_ms")
        || o.accountingMillis() != number(r, "accounting_ms")
        || o.day() != number(r, "day_index")
        || number(r, "classifier_version") != 1
        || !Objects.equals(
            d.reversal() == null ? null : d.reversal().toString(), r.get("reversal_id"))
        || !Objects.equals(
            d.publication() == null ? null : d.publication().toString(), r.get("publication_id")))
      throw invalid("Observation projection mismatch");
  }

  private static void validateCounters(Connection c) throws SQLException {
    long groups =
        count(
            c,
            "SELECT count(*) FROM (SELECT day_index,currency,kind FROM activity_observations GROUP BY day_index,currency,kind)");
    if (groups != count(c, "SELECT count(*) FROM activity_day_counters"))
      throw invalid("Missing activity counters");
    try (var s = c.createStatement();
        var r = s.executeQuery("SELECT * FROM activity_day_counters")) {
      while (r.next()) {
        var sums =
            rows(
                    c,
                    "SELECT count(*) AS count,COALESCE(sum(minor),0) AS minor FROM activity_observations WHERE day_index=? AND currency=? AND kind=?",
                    List.of(r.getString("day_index"), r.getString("currency"), r.getString("kind")),
                    1)
                .getFirst();
        if (r.getLong("count") != number(sums, "count")
            || r.getLong("minor") != number(sums, "minor"))
          throw invalid("Activity counter mismatch");
      }
    }
  }

  private static void requireFact(
      Connection c, String world, String type, String payload, String operation)
      throws SQLException {
    String sql =
        "SELECT r.payload FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE t.world_id=? AND r.record_type=? AND r.record_version=1 AND r.payload=?";
    var params = new ArrayList<>(List.of(world, type, payload));
    if (operation != null) {
      sql += " AND t.operation_id=?";
      params.add(operation);
    }
    if (rows(c, sql, params, 1).size() != 1)
      throw invalid("Activity projection lacks immutable fact");
  }

  private static void validateDeclarations(Connection c) throws SQLException {
    var pattern = Pattern.compile("CREATE (?:UNIQUE )?(?:TABLE|INDEX|TRIGGER) ([a-z0-9_]+)");
    try (var q = c.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
      for (var expected : statements()) {
        var matcher = pattern.matcher(expected);
        if (!matcher.find()) throw invalid("Invalid activity schema declaration");
        q.setString(1, matcher.group(1));
        try (var r = q.executeQuery()) {
          if (!r.next()
              || !expected
                  .replaceAll("\\s+", " ")
                  .strip()
                  .equals(r.getString(1).replaceAll("\\s+", " ").strip()))
            throw invalid("Changed activity schema " + matcher.group(1));
        }
      }
    }
  }

  private static List<Map<String, String>> rows(
      Connection c, String sql, List<String> params, int max) throws SQLException {
    try (var q = c.prepareStatement(sql)) {
      for (int i = 0; i < params.size(); i++) q.setString(i + 1, params.get(i));
      try (var r = q.executeQuery()) {
        var result = new ArrayList<Map<String, String>>();
        var metadata = r.getMetaData();
        while (r.next()) {
          if (result.size() == max) throw invalid("Activity validation query bound");
          var row = new HashMap<String, String>();
          for (int i = 1; i <= metadata.getColumnCount(); i++)
            row.put(metadata.getColumnLabel(i), r.getString(i));
          result.add(row);
        }
        return result;
      }
    }
  }

  private static String scalar(Connection c, String sql) throws SQLException {
    try (var s = c.createStatement();
        var r = s.executeQuery(sql)) {
      if (!r.next()) throw invalid("Missing activity validation scalar");
      return r.getString(1);
    }
  }

  private static long count(Connection c, String sql) throws SQLException {
    return Long.parseLong(scalar(c, sql));
  }

  private static long number(Map<String, String> row, String key) {
    return Long.parseLong(row.get(key));
  }

  private static SQLException invalid(String reason) {
    return new SQLException(reason);
  }
}
