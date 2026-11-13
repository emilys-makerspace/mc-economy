package ca.emilybelnavis.mcmods.economy.storage;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Recognizes the original minor-unit database without interpreting its historical archives. */
final class LegacyDatabase {
  private static final int ACCOUNT_LIMIT = 100_000;
  private static final Map<String, List<String>> CORE = coreColumns();
  private static final Map<String, List<String>> OPTIONAL =
      Map.of(
          "daily_stock_prices",
          List.of("day", "stock", "price", "currency", "display_price", "display_currency"),
          "ledger_display_values",
          List.of("sequence", "currency", "delta", "balance"),
          "ledger_legacy_currencies",
          List.of("account", "currency"));
  private static final Map<String, List<String>> TRAILING =
      Map.of(
          "ledger", List.of("transaction_id", "currency"),
          "stock_price_history", List.of("currency"),
          "orders", List.of("currency"),
          "trades", List.of("currency"));

  record Account(
      UUID id, UUID owner, EconomyCurrency currency, String kind, boolean primary, long balance) {}

  record Snapshot(List<Account> accounts, EconomyCurrency denomination, List<String> tables) {
    Snapshot {
      accounts = List.copyOf(accounts);
      tables = List.copyOf(tables);
    }
  }

  private LegacyDatabase() {}

  static Snapshot inspect(Connection connection) throws SQLException, IOException {
    if (pragma(connection, "application_id") != 0 || pragma(connection, "user_version") != 0)
      throw invalid("Not a recognized original Economy database");
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT 1 FROM sqlite_master WHERE type IN ('trigger','view') LIMIT 1")) {
      if (rows.next()) throw invalid("Legacy database contains unsupported triggers or views");
    }
    var tables = new ArrayList<String>();
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
      while (rows.next()) {
        String table = rows.getString(1);
        if (table.equals("sqlite_sequence")) continue;
        if (!CORE.containsKey(table) && !OPTIONAL.containsKey(table))
          throw invalid("Unrecognized legacy table");
        tables.add(table);
      }
    }
    if (!tables.containsAll(CORE.keySet())) throw invalid("Incomplete legacy table layout");
    for (String table : tables) validateColumns(connection, table);
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA quick_check")) {
      if (!rows.next() || !"ok".equals(rows.getString(1)) || rows.next())
        throw invalid("Legacy SQLite integrity check failed");
    }
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
      if (rows.next()) throw invalid("Legacy database has broken foreign keys");
    }
    validateMetadata(connection);
    String denomination = metadata(connection, "world_currency");
    if (denomination == null) denomination = metadata(connection, "market_currency");
    if (denomination == null) throw invalid("Legacy world currency is missing");
    EconomyCurrency currency = currency(denomination);
    return new Snapshot(accounts(connection), currency, tables);
  }

  /** Only the staged copy is passed here; original tables remain intact in the source backup. */
  static void archive(Connection connection, Snapshot snapshot) throws SQLException {
    var seen = new HashSet<String>();
    for (String table : snapshot.tables()) {
      if ((!CORE.containsKey(table) && !OPTIONAL.containsKey(table)) || !seen.add(table))
        throw new SQLException("Invalid legacy archive table");
    }
    try (var statement = connection.createStatement()) {
      statement.execute("PRAGMA legacy_alter_table=OFF");
      for (String table : snapshot.tables())
        statement.execute("ALTER TABLE \"" + table + "\" RENAME TO \"legacy_v0_" + table + "\"");
      for (String table : snapshot.tables()) {
        String archive = "legacy_v0_" + table;
        for (String action : List.of("INSERT", "UPDATE", "DELETE"))
          statement.execute(
              "CREATE TRIGGER \""
                  + archive
                  + "_no_"
                  + action.toLowerCase(java.util.Locale.ROOT)
                  + "\" BEFORE "
                  + action
                  + " ON \""
                  + archive
                  + "\" BEGIN SELECT RAISE(ABORT, 'Legacy import archive is read-only'); END");
      }
    }
  }

  private static List<Account> accounts(Connection connection) throws SQLException, IOException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT count(*) FROM accounts")) {
      if (!rows.next() || rows.getLong(1) > ACCOUNT_LIMIT)
        throw invalid("Legacy account count exceeds the import limit");
    }
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT 1 FROM accounts WHERE typeof(id)!='text' OR length(id)!=36"
                    + " OR typeof(owner)!='text' OR length(owner)!=36"
                    + " OR typeof(currency)!='text' OR length(currency)!=3"
                    + " OR typeof(kind)!='text' OR length(kind)>16 LIMIT 1")) {
      if (rows.next()) throw invalid("Malformed or oversized legacy account identity");
    }
    var result = new ArrayList<Account>();
    Set<UUID> ids = new HashSet<>();
    Set<UUID> primaries = new HashSet<>();
    Set<UUID> trading = new HashSet<>();
    Map<UUID, Set<EconomyCurrency>> banks = new HashMap<>();
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT id,owner,currency,kind,is_primary,balance,"
                    + "typeof(is_primary) AS primary_type,typeof(balance) AS balance_type"
                    + " FROM accounts ORDER BY id LIMIT "
                    + (ACCOUNT_LIMIT + 1))) {
      while (rows.next()) {
        if (result.size() >= ACCOUNT_LIMIT) throw invalid("Legacy account import limit exceeded");
        UUID id = uuid(text(rows, "id", 36));
        UUID owner = uuid(text(rows, "owner", 36));
        EconomyCurrency currency = currency(text(rows, "currency", 3));
        String kind = text(rows, "kind", 16);
        long primary = rows.getLong("is_primary");
        long balance = rows.getLong("balance");
        if (!"integer".equals(rows.getString("primary_type"))
            || !"integer".equals(rows.getString("balance_type"))
            || primary < 0
            || primary > 1
            || balance < 0) throw invalid("Legacy account has invalid minor units or primary flag");
        if (!ids.add(id)) throw invalid("Duplicate legacy account identity");
        if (kind.equals("BANK")) {
          var currencies = banks.computeIfAbsent(owner, ignored -> new HashSet<>());
          if (!currencies.add(currency) || currencies.size() > 4)
            throw invalid("Legacy bank accounts exceed supported per-owner limits");
          if (primary == 1 && !primaries.add(owner))
            throw invalid("Legacy owner has multiple primary accounts");
        } else if (kind.equals("TRADING")) {
          if (primary != 0 || !trading.add(owner))
            throw invalid("Legacy trading accounts exceed supported per-owner limits");
        } else throw invalid("Unsupported legacy account kind");
        result.add(new Account(id, owner, currency, kind, primary == 1, balance));
      }
    }
    return result;
  }

  private static void validateColumns(Connection connection, String table)
      throws SQLException, IOException {
    var names = new ArrayList<String>();
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA table_xinfo(\"" + table + "\")")) {
      while (rows.next()) {
        if (rows.getInt("hidden") != 0) throw invalid("Unsupported legacy generated column");
        names.add(rows.getString("name"));
      }
    }
    List<String> expected = CORE.containsKey(table) ? CORE.get(table) : OPTIONAL.get(table);
    if (names.equals(expected)) return;
    List<String> trailing = TRAILING.getOrDefault(table, List.of());
    if (names.size() < expected.size()
        || !names.subList(0, expected.size()).equals(expected)
        || names.size() > expected.size() + trailing.size())
      throw invalid("Unsupported legacy column layout: " + table);
    var extras = names.subList(expected.size(), names.size());
    if (extras.isEmpty()
        || new HashSet<>(extras).size() != extras.size()
        || !trailing.containsAll(extras)
        || !extras.equals(trailing.stream().filter(extras::contains).toList()))
      throw invalid("Unsupported legacy column layout: " + table);
  }

  private static void validateMetadata(Connection connection) throws SQLException, IOException {
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT 1 FROM metadata WHERE typeof(key)!='text' OR typeof(value)!='text'"
                    + " OR length(key)>128 OR length(value)>4096 LIMIT 1")) {
      if (rows.next()) throw invalid("Malformed legacy metadata");
    }
  }

  private static String metadata(Connection connection, String key)
      throws SQLException, IOException {
    try (var statement =
        connection.prepareStatement("SELECT value FROM metadata WHERE key=? LIMIT 2")) {
      statement.setString(1, key);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) return null;
        String value = rows.getString(1);
        if (rows.next()) throw invalid("Duplicate legacy world currency metadata");
        return value;
      }
    }
  }

  private static String text(ResultSet rows, String column, int limit)
      throws SQLException, IOException {
    Object value = rows.getObject(column);
    if (!(value instanceof String text) || text.isEmpty() || text.length() > limit)
      throw invalid("Malformed legacy account field: " + column);
    return text;
  }

  private static UUID uuid(String text) throws IOException {
    try {
      UUID value = UUID.fromString(text);
      if (!value.toString().equals(text)) throw invalid("Noncanonical legacy UUID");
      return value;
    } catch (IllegalArgumentException error) {
      throw invalid("Invalid legacy UUID");
    }
  }

  private static EconomyCurrency currency(String text) throws IOException {
    try {
      return EconomyCurrency.fromCode(text);
    } catch (IllegalArgumentException error) {
      throw invalid("Unsupported legacy currency");
    }
  }

  private static int pragma(Connection connection, String name) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA " + name)) {
      if (!rows.next()) throw new SQLException("Missing SQLite version marker");
      return rows.getInt(1);
    }
  }

  private static IOException invalid(String reason) {
    return new IOException(reason);
  }

  private static Map<String, List<String>> coreColumns() {
    var columns = new LinkedHashMap<String, List<String>>();
    columns.put("metadata", List.of("key", "value"));
    columns.put("accounts", List.of("id", "owner", "currency", "kind", "is_primary", "balance"));
    columns.put(
        "ledger",
        List.of("sequence", "operation", "account", "timestamp", "delta", "balance", "memo"));
    columns.put("exchange_rates", List.of("currency", "rate"));
    columns.put("historical_rates", List.of("date", "currency", "rate"));
    columns.put("stocks", List.of("id", "name"));
    columns.put("stock_prices", List.of("stock", "price"));
    columns.put("stock_price_history", List.of("sequence", "stock", "price", "timestamp"));
    columns.put("holdings", List.of("owner", "stock", "quantity"));
    columns.put("orders", List.of("id", "owner", "stock", "side", "quantity", "price", "status"));
    columns.put("trades", List.of("id", "order_id", "account", "quantity", "price", "timestamp"));
    return Map.copyOf(columns);
  }
}
