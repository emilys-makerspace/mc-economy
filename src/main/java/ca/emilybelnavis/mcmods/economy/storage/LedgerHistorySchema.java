package ca.emilybelnavis.mcmods.economy.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** V10 index for bounded account history reads of immutable postings. */
final class LedgerHistorySchema {
  static final String INDEX =
      """
            CREATE INDEX ledger_posting_account ON journal_records(substr(payload,1,36),sequence,position)
            WHERE record_type='economy:posting' AND record_version=1
            """
          .strip();

  static final String V2_INDEX =
      """
            CREATE INDEX ledger_posting_account ON journal_records(substr(payload,1,36),sequence,position)
            WHERE record_type='economy:posting' AND record_version IN (1,2)
            """
          .strip();

  static final String V3_INDEX = V2_INDEX.replace("IN (1,2)", "IN (1,2,3)");

  static final String V4_INDEX = V3_INDEX.replace("IN (1,2,3)", "IN (1,2,3,4)");

  private LedgerHistorySchema() {}

  static void validate(Connection connection) throws SQLException {
    try (var query =
            connection.prepareStatement(
                "SELECT sql FROM sqlite_schema WHERE type='index' AND name='ledger_posting_account'");
        var rows = query.executeQuery()) {
      int schema;
      try (var statement = connection.createStatement();
          var version = statement.executeQuery("PRAGMA user_version")) {
        version.next();
        schema = version.getInt(1);
      }
      if (!rows.next()
          || !(schema >= 44 ? V4_INDEX : schema >= 20 ? V3_INDEX : schema >= 19 ? V2_INDEX : INDEX)
              .equals(rows.getString(1))) {
        throw new StorageException(
            StorageException.Kind.SCHEMA, "Missing or changed ledger history index");
      }
    }
  }
}
