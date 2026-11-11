package ca.emilybelnavis.mcmods.economy.debt;

import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.sql.*;
import java.util.List;

/** Indexed keyset reads over saved obligations and immutable accounting receipts. */
public final class DebtDiagnosticSchema {
  private DebtDiagnosticSchema() {}

  public static List<String> statements() {
    return List.of(
        "CREATE INDEX debt_diagnostic_obligations ON debt_obligations(world_id,kind,facility_id,obligation_id)",
        "CREATE INDEX debt_sale_diagnostic_history ON journal_records(substr(payload,1,36),sequence,position) WHERE record_type='economy:a6/debt-sale-attempt'",
        "CREATE INDEX debt_accounting_history ON journal_records(substr(payload,38,36),sequence,position) WHERE record_type='economy:a6/debt-accounting'");
  }

  public static void validate(Connection connection) throws SQLException {
    for (String sql : statements()) {
      String name = sql.split(" ")[2];
      try (var query =
          connection.prepareStatement(
              "SELECT sql FROM sqlite_schema WHERE type='index' AND name=?")) {
        query.setString(1, name);
        try (var rows = query.executeQuery()) {
          if (!rows.next() || !sql.equals(rows.getString(1)))
            throw new StorageException(
                StorageException.Kind.SCHEMA, "Missing or changed debt diagnostic index");
        }
      }
    }
  }
}
