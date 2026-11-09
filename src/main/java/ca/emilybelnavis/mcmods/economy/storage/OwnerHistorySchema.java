package ca.emilybelnavis.mcmods.economy.storage;

import ca.emilybelnavis.mcmods.economy.journal.OwnerHistory;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Additive, rebuildable original-posting projection. Never updates source journal facts. */
public final class OwnerHistorySchema {
  private OwnerHistorySchema() {}

  private static final String COLUMNS =
      "world_id,sequence,position,transaction_id,operation_id,account_id,owner_id,recorded_ms,currency,minor,denomination_revision,posting_version,service,operation_type,category,internal_movement,decoder_version";

  public static List<String> statements() {
    var sql = new ArrayList<String>();
    sql.add(
        "CREATE TABLE owner_history (world_id TEXT NOT NULL,sequence INTEGER NOT NULL,position INTEGER NOT NULL,transaction_id TEXT NOT NULL,operation_id TEXT NOT NULL,account_id TEXT NOT NULL REFERENCES account_catalog(account_id),owner_id TEXT NOT NULL,recorded_ms INTEGER NOT NULL CHECK(recorded_ms>=0),currency TEXT NOT NULL,minor INTEGER NOT NULL,denomination_revision INTEGER NOT NULL CHECK(denomination_revision>=0),posting_version INTEGER NOT NULL CHECK(posting_version BETWEEN 1 AND 4),service TEXT NOT NULL,operation_type TEXT NOT NULL,category TEXT NOT NULL,internal_movement INTEGER NOT NULL CHECK(internal_movement IN (0,1)),decoder_version INTEGER NOT NULL CHECK(decoder_version=1),PRIMARY KEY(world_id,sequence,position),FOREIGN KEY(sequence,position) REFERENCES journal_records(sequence,position))");
    sql.add(
        "CREATE TABLE owner_history_state (singleton INTEGER PRIMARY KEY CHECK(singleton=1),frontier INTEGER NOT NULL CHECK(frontier>=0),target INTEGER NOT NULL CHECK(target>=frontier),decoder_version INTEGER NOT NULL CHECK(decoder_version=1),ready INTEGER NOT NULL CHECK(ready IN (0,1)))");
    sql.add(
        "INSERT INTO owner_history_state SELECT 1,0,COALESCE(MAX(sequence),0),1,CASE WHEN COALESCE(MAX(sequence),0)=0 THEN 1 ELSE 0 END FROM journal_transactions");
    for (boolean account : List.of(false, true))
      for (boolean currency : List.of(false, true))
        for (boolean type : List.of(false, true)) {
          var q =
              new OwnerHistory.Query(
                  account ? new UUID(0, 1) : null,
                  currency ? ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD : null,
                  null,
                  null,
                  type ? OwnerHistory.Type.FX : OwnerHistory.Type.ALL,
                  8);
          sql.add(
              "CREATE INDEX "
                  + OwnerHistory.index(q)
                  + " ON owner_history(world_id,owner_id,"
                  + (account ? "account_id," : "")
                  + (currency ? "currency," : "")
                  + (type ? "category," : "")
                  + "sequence DESC,position DESC)");
        }
    return List.copyOf(sql);
  }

  public static void validate(Connection c) throws SQLException {
    for (String sql : statements()) {
      if (!sql.startsWith("CREATE ")) continue;
      String[] words = sql.split(" ");
      String name = words[2];
      try (var statement = c.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
        statement.setString(1, name);
        try (var row = statement.executeQuery()) {
          if (!row.next() || !normalize(sql).equals(normalize(row.getString(1))))
            throw failure("History projection schema/index changed: " + name);
        }
      }
    }
    var rows = query(c, "SELECT * FROM owner_history_state", List.of(), 2);
    if (rows.size() != 1
        || !rows.getFirst().get("singleton").equals("1")
        || !rows.getFirst().get("decoder_version").equals("1"))
      throw failure("History projection metadata missing");
  }

  private static String normalize(String sql) {
    return sql.replaceAll("\\s+", " ").strip();
  }

  /**
   * Called once, in the same transaction after original records/outcome and domain effects are
   * sealed.
   */
  public static void committed(FoundationStore.Transaction tx, long sequence) {
    var state =
        tx.query("SELECT frontier,ready FROM owner_history_state WHERE singleton=1", List.of(), 1)
            .getFirst();
    long frontier = Long.parseLong(state.get("frontier"));
    if (!state.get("ready").equals("1")) return;
    var pending =
        tx.query(
            "SELECT sequence FROM journal_transactions WHERE sequence>? ORDER BY sequence LIMIT 1",
            List.of(Long.toString(frontier)),
            1);
    if (pending.isEmpty() || Long.parseLong(pending.getFirst().get("sequence")) != sequence) {
      tx.execute(
          "UPDATE owner_history_state SET ready=0,target=(SELECT COALESCE(MAX(sequence),0) FROM journal_transactions) WHERE singleton=1",
          List.of());
      return;
    }
    for (var row : source(tx::query, sequence)) insert(tx, row);
    tx.execute(
        "UPDATE owner_history_state SET frontier=?,target=?,ready=1 WHERE singleton=1",
        List.of(Long.toString(sequence), Long.toString(sequence)));
  }

  private static void insert(FoundationStore.Transaction tx, Map<String, String> row) {
    tx.execute(
        "INSERT INTO owner_history("
            + COLUMNS
            + ") VALUES("
            + String.join(",", Collections.nCopies(17, "?"))
            + ")",
        Arrays.stream(COLUMNS.split(",")).map(row::get).toList());
  }

  private interface Reads {
    List<Map<String, String>> query(String sql, List<String> parameters, int maximum);
  }

  private static List<Map<String, String>> source(Reads reads, long sequence) {
    var envelope =
        reads.query(
            "SELECT t.*,o.service,o.operation_type FROM journal_transactions t JOIN operation_outcomes o ON o.world_id=t.world_id AND o.operation_id=t.operation_id WHERE t.sequence=?",
            List.of(Long.toString(sequence)),
            1);
    if (envelope.size() != 1) throw failure("History source envelope missing");
    var fact = envelope.getFirst();
    long recorded = Instant.parse(fact.get("audit_utc")).toEpochMilli();
    var original =
        reads.query(
            "SELECT position,record_type,record_version,payload FROM journal_records WHERE sequence=? ORDER BY position",
            List.of(Long.toString(sequence)),
            64);
    if (original.size() != Integer.parseInt(fact.get("record_count")))
      throw failure("History source record count changed");
    var projected = new ArrayList<Map<String, String>>();
    long denomination = 0;
    var rebases =
        reads.query(
            "SELECT payload FROM journal_records INDEXED BY ledger_rebase_latest WHERE record_type='economy:rebase' AND record_version=1 AND sequence<=? ORDER BY sequence DESC LIMIT 1",
            List.of(Long.toString(sequence)),
            1);
    if (!rebases.isEmpty())
      denomination = Long.parseLong(rebases.getFirst().get("payload").split("\\|", -1)[1]);
    for (var record : original) {
      if (!record.get("record_type").equals("economy:posting")) continue;
      int version = Integer.parseInt(record.get("record_version"));
      if (version < 1 || version > 4) throw failure("History posting decoder unavailable");
      var fields = record.get("payload").split("\\|", -1);
      String account = UUID.fromString(fields[0]).toString();
      String minor = Long.toString(Long.parseLong(fields[3]));
      String currency =
          ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.valueOf(fields[2]).code();
      var catalog =
          reads.query(
              "SELECT account_id,owner_id FROM account_catalog WHERE account_id=?",
              List.of(account),
              1);
      if (catalog.isEmpty()) continue; // Issuance/institution/system legs are never owner accounts.
      var row = new LinkedHashMap<String, String>();
      row.put("world_id", fact.get("world_id"));
      row.put("sequence", Long.toString(sequence));
      row.put("position", record.get("position"));
      row.put("transaction_id", fact.get("transaction_id"));
      row.put("operation_id", fact.get("operation_id"));
      row.put("account_id", account);
      row.put("owner_id", catalog.getFirst().get("owner_id"));
      row.put("recorded_ms", Long.toString(recorded));
      row.put("currency", currency);
      row.put("minor", minor);
      row.put("denomination_revision", Long.toString(denomination));
      row.put("posting_version", Integer.toString(version));
      row.put("service", fact.get("service"));
      row.put("operation_type", fact.get("operation_type"));
      row.put(
          "category",
          OwnerHistory.classify(fact.get("service"), fact.get("operation_type")).name());
      row.put("internal_movement", "0");
      row.put("decoder_version", "1");
      projected.add(row);
    }
    for (var row : projected) {
      boolean opposite =
          projected.stream()
              .anyMatch(
                  other ->
                      other != row
                          && other.get("owner_id").equals(row.get("owner_id"))
                          && !other.get("account_id").equals(row.get("account_id"))
                          && Long.signum(Long.parseLong(other.get("minor")))
                              == -Long.signum(Long.parseLong(row.get("minor")))
                          && Long.parseLong(row.get("minor")) != 0);
      if (opposite) row.put("internal_movement", "1");
    }
    return List.copyOf(projected);
  }

  private static void compare(Reads reads, long sequence, List<Map<String, String>> expected) {
    var actual =
        reads.query(
            "SELECT " + COLUMNS + " FROM owner_history WHERE sequence=? ORDER BY position",
            List.of(Long.toString(sequence)),
            64);
    if (!actual.equals(expected))
      throw failure("History projection does not match original source at sequence " + sequence);
  }

  /** Each startup call validates/rebuilds at most sixteen original transactions, then yields. */
  public static final class Initializer {
    private final Connection connection;
    private long cursor;
    private final long target;

    public Initializer(Connection c) throws SQLException {
      connection = c;
      validate(c);
      target =
          Long.parseLong(
              query(
                      c,
                      "SELECT COALESCE(MAX(sequence),0) AS n FROM journal_transactions",
                      List.of(),
                      1)
                  .getFirst()
                  .get("n"));
    }

    public long cursor() {
      return cursor;
    }

    public long target() {
      return target;
    }

    public boolean step() throws SQLException {
      interrupted();
      connection.setAutoCommit(false);
      try {
        var tx = transaction(connection);
        var state =
            tx.query("SELECT frontier FROM owner_history_state WHERE singleton=1", List.of(), 1)
                .getFirst();
        long frontier = Long.parseLong(state.get("frontier"));
        if (frontier > target) throw failure("History frontier exceeds original journal");
        var sources =
            tx.query(
                "SELECT sequence FROM journal_transactions WHERE sequence>? AND sequence<=? ORDER BY sequence LIMIT 16",
                List.of(Long.toString(cursor), Long.toString(target)),
                16);
        for (var selected : sources) {
          long sequence = Long.parseLong(selected.get("sequence"));
          var expected = source(tx::query, sequence);
          if (sequence <= frontier) compare(tx::query, sequence, expected);
          else {
            for (var row : expected) insert(tx, row);
            frontier = sequence;
          }
          cursor = sequence;
        }
        boolean done = sources.size() < 16 || cursor == target;
        tx.execute(
            "UPDATE owner_history_state SET frontier=?,target=?,ready=? WHERE singleton=1",
            List.of(Long.toString(frontier), Long.toString(target), done ? "1" : "0"));
        connection.commit();
        return done;
      } catch (Throwable error) {
        connection.rollback();
        if (error instanceof SQLException sql) throw sql;
        if (error instanceof RuntimeException runtime) throw runtime;
        throw new IllegalStateException(error);
      } finally {
        connection.setAutoCommit(true);
      }
    }
  }

  /**
   * Offline complete-world validation uses bounded source pages without changing any fact or
   * projection.
   */
  public static void validateFacts(Connection c) throws SQLException {
    validate(c);
    var state =
        query(
                c,
                "SELECT frontier,target,ready FROM owner_history_state WHERE singleton=1",
                List.of(),
                1)
            .getFirst();
    long high =
        Long.parseLong(
            query(
                    c,
                    "SELECT COALESCE(MAX(sequence),0) AS n FROM journal_transactions",
                    List.of(),
                    1)
                .getFirst()
                .get("n"));
    if (!state.get("ready").equals("1")
        || Long.parseLong(state.get("frontier")) != high
        || Long.parseLong(state.get("target")) != high)
      throw failure("History projection coverage incomplete");
    long cursor = 0;
    while (true) {
      interrupted();
      var rows =
          query(
              c,
              "SELECT sequence FROM journal_transactions WHERE sequence>? ORDER BY sequence LIMIT 16",
              List.of(Long.toString(cursor)),
              16);
      for (var row : rows) {
        cursor = Long.parseLong(row.get("sequence"));
        compare(
            (s, p, n) -> query(c, s, p, n), cursor, source((s, p, n) -> query(c, s, p, n), cursor));
      }
      if (rows.size() < 16) return;
    }
  }

  private static FoundationStore.Transaction transaction(Connection c) {
    return new FoundationStore.Transaction() {
      public int execute(String sql, List<String> parameters) {
        try (var statement = c.prepareStatement(sql)) {
          for (int i = 0; i < parameters.size(); i++) statement.setString(i + 1, parameters.get(i));
          return statement.executeUpdate();
        } catch (SQLException e) {
          throw StorageException.sql(e);
        }
      }

      public List<Map<String, String>> query(String sql, List<String> parameters, int maximum) {
        return OwnerHistorySchema.query(c, sql, parameters, maximum);
      }
    };
  }

  private static List<Map<String, String>> query(
      Connection c, String sql, List<String> parameters, int maximum) {
    try (var statement = c.prepareStatement(sql)) {
      for (int i = 0; i < parameters.size(); i++) statement.setString(i + 1, parameters.get(i));
      try (var result = statement.executeQuery()) {
        var rows = new ArrayList<Map<String, String>>();
        int bytes = 0;
        while (result.next()) {
          if (rows.size() == maximum) throw failure("History validation row bound");
          var row = new LinkedHashMap<String, String>();
          var metadata = result.getMetaData();
          for (int i = 1; i <= metadata.getColumnCount(); i++) {
            String value = result.getString(i);
            if (value != null)
              bytes =
                  Math.addExact(
                      bytes, value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            if (bytes > 1_048_576) throw failure("History validation byte bound");
            row.put(metadata.getColumnLabel(i), value);
          }
          rows.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(rows);
      }
    } catch (SQLException e) {
      throw StorageException.sql(e);
    }
  }

  private static void interrupted() {
    if (Thread.currentThread().isInterrupted()) throw failure("History validation interrupted");
  }

  private static StorageException failure(String message) {
    return new StorageException(StorageException.Kind.SCHEMA, message);
  }
}
