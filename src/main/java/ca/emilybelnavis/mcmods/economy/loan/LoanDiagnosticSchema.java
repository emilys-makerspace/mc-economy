package ca.emilybelnavis.mcmods.economy.loan;

import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.sql.Connection;
import java.sql.SQLException;

/** V29 only adds a keyset lookup over immutable, historically encoded loan identities. */
public final class LoanDiagnosticSchema {
  private LoanDiagnosticSchema() {}

  public static final String INDEX =
      "CREATE INDEX loan_diagnostic_records ON journal_records(substr(payload,instr(payload,'|')+1,48),record_type,sequence,position) WHERE record_type IN('economy:a6/loan-event','economy:a6/loan')";

  public static void validate(Connection connection) throws SQLException {
    try (var query =
            connection.prepareStatement(
                "SELECT sql FROM sqlite_schema WHERE type='index' AND name='loan_diagnostic_records'");
        var rows = query.executeQuery()) {
      if (!rows.next() || !INDEX.equals(rows.getString(1)))
        throw new StorageException(
            StorageException.Kind.SCHEMA, "Missing or changed loan diagnostic index");
    }
  }
}
