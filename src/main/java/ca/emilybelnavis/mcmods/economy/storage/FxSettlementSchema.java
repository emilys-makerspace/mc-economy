package ca.emilybelnavis.mcmods.economy.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

final class FxSettlementSchema {
  private static final String QUOTES =
      "CREATE TABLE ledger_fx_quotes(quote_id TEXT PRIMARY KEY NOT NULL)";
  private static final String DENOMINATION =
      "CREATE INDEX ledger_rebase_latest ON journal_records(sequence DESC) WHERE record_type='economy:rebase'";

  private FxSettlementSchema() {}

  static List<String> statements() {
    return List.of(QUOTES, DENOMINATION);
  }

  static void validate(Connection connection) throws SQLException {
    try (var query =
        connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE type=? AND name=?")) {
      validate(query, "table", "ledger_fx_quotes", QUOTES);
      validate(query, "index", "ledger_rebase_latest", DENOMINATION);
    }
  }

  private static void validate(
      java.sql.PreparedStatement query, String type, String name, String sql) throws SQLException {
    query.setString(1, type);
    query.setString(2, name);
    try (var rows = query.executeQuery()) {
      if (!rows.next() || !sql.equals(rows.getString(1))) {
        throw new StorageException(
            StorageException.Kind.SCHEMA, "Missing or changed FX settlement schema: " + name);
      }
    }
  }
}
