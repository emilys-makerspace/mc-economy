package ca.emilybelnavis.mcmods.economy.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** V8 hold state and worker lookup indexes. */
final class HoldSchema {
  private static final String TABLE =
      """
            CREATE TABLE ledger_holds (
              hold_id TEXT PRIMARY KEY NOT NULL,
              account_id TEXT NOT NULL REFERENCES account_catalog(account_id),
              currency TEXT NOT NULL,
              minor_units INTEGER NOT NULL CHECK(typeof(minor_units)='integer' AND minor_units>0),
              actor TEXT NOT NULL,
              state TEXT NOT NULL CHECK(state IN ('ACTIVE','CAPTURED','RELEASED'))
            )
            """
          .strip();
  private static final String ACTIVE_ACCOUNT =
      "CREATE INDEX ledger_holds_active_account ON ledger_holds(account_id,currency) WHERE state='ACTIVE'";
  private static final String ACTOR_STATE =
      "CREATE INDEX ledger_holds_actor_state ON ledger_holds(actor,state,hold_id)";

  private HoldSchema() {}

  static List<String> statements() {
    return List.of(TABLE, ACTIVE_ACCOUNT, ACTOR_STATE);
  }

  static void validate(Connection connection) throws SQLException {
    try (var query =
        connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE type=? AND name=?")) {
      validate(query, "table", "ledger_holds", TABLE);
      validate(query, "index", "ledger_holds_active_account", ACTIVE_ACCOUNT);
      validate(query, "index", "ledger_holds_actor_state", ACTOR_STATE);
    }
  }

  private static void validate(
      java.sql.PreparedStatement query, String type, String name, String sql) throws SQLException {
    query.setString(1, type);
    query.setString(2, name);
    try (var row = query.executeQuery()) {
      if (!row.next() || !sql.equals(row.getString(1))) {
        throw new StorageException(
            StorageException.Kind.SCHEMA, "Missing or changed hold schema: " + name);
      }
    }
  }
}
