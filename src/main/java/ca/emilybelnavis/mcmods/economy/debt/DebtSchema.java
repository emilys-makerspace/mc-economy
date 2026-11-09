package ca.emilybelnavis.mcmods.economy.debt;

import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Additive lifecycle projections and append-only report revision lookup. */
public final class DebtSchema {
  private DebtSchema() {}

  private static final List<String> NAMES =
      List.of(
          "debt_obligations",
          "debt_pending_reports",
          "debt_unmet_due",
          "debt_obligation_owner",
          "debt_cases",
          "debt_case_expiry",
          "debt_pending_sale",
          "debt_case_owner",
          "debt_reports",
          "debt_report_owner");

  public static List<String> statements() {
    return List.of(
        "CREATE TABLE debt_obligations(world_id TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN('CARD','LOAN')),obligation_id TEXT NOT NULL,facility_id TEXT NOT NULL,owner_id TEXT NOT NULL,currency TEXT NOT NULL,due_at INTEGER NOT NULL CHECK(due_at>=0),original_minor INTEGER NOT NULL CHECK(original_minor>=0),unmet_minor INTEGER NOT NULL CHECK(unmet_minor>=0 AND unmet_minor<=original_minor),reported INTEGER NOT NULL CHECK(reported IN(0,1)),met_at_report INTEGER NOT NULL CHECK(met_at_report IN(0,1) AND (met_at_report=0 OR reported=1)),revision INTEGER NOT NULL CHECK(revision>0),body TEXT NOT NULL,PRIMARY KEY(world_id,kind,obligation_id))",
        "CREATE INDEX debt_pending_reports ON debt_obligations(reported,due_at,kind,obligation_id)",
        "CREATE INDEX debt_unmet_due ON debt_obligations(kind,facility_id,due_at,obligation_id) WHERE unmet_minor>0",
        "CREATE INDEX debt_obligation_owner ON debt_obligations(world_id,owner_id,kind,obligation_id)",
        "CREATE TABLE debt_cases(world_id TEXT NOT NULL,case_id TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN('CARD','LOAN')),facility_id TEXT NOT NULL,owner_id TEXT NOT NULL,creditor TEXT NOT NULL,currency TEXT NOT NULL,principal_minor INTEGER NOT NULL CHECK(principal_minor>=0),interest_minor INTEGER NOT NULL CHECK(interest_minor>=0),default_at INTEGER NOT NULL CHECK(default_at>=0),acknowledged_at INTEGER NOT NULL CHECK(acknowledged_at>=default_at),collection_expires INTEGER NOT NULL CHECK(collection_expires=acknowledged_at+873600000),report_expires INTEGER NOT NULL CHECK(report_expires=default_at+3057600000),sale TEXT NOT NULL CHECK(sale IN('PENDING','SOLD')),collectability TEXT NOT NULL CHECK(collectability IN('COLLECTIBLE','UNCOLLECTABLE')),payment TEXT NOT NULL CHECK(payment IN('UNPAID','PARTIAL','SETTLED') AND ((payment='SETTLED')=(principal_minor+interest_minor=0))),discount_minor INTEGER NOT NULL CHECK(discount_minor>=0 AND discount_minor<=principal_minor+interest_minor),revision INTEGER NOT NULL CHECK(revision>0),body TEXT NOT NULL,PRIMARY KEY(world_id,case_id),UNIQUE(world_id,kind,facility_id))",
        "CREATE INDEX debt_case_expiry ON debt_cases(collectability,payment,collection_expires,case_id)",
        "CREATE INDEX debt_pending_sale ON debt_cases(sale,collectability,payment,acknowledged_at,case_id)",
        "CREATE INDEX debt_case_owner ON debt_cases(world_id,owner_id,case_id)",
        "CREATE TABLE debt_reports(world_id TEXT NOT NULL,report_id TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),owner_id TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN('CARD','LOAN')),facility_id TEXT NOT NULL,obligation_id TEXT NOT NULL,report_at INTEGER NOT NULL CHECK(report_at>=0),effective_at INTEGER NOT NULL CHECK(effective_at>=report_at),body TEXT NOT NULL,PRIMARY KEY(world_id,report_id,revision))",
        "CREATE INDEX debt_report_owner ON debt_reports(world_id,owner_id,report_at,report_id,revision)");
  }

  public static void validate(Connection connection) throws SQLException {
    var expected = statements();
    for (int index = 0; index < NAMES.size(); index++) {
      try (var query = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
        query.setString(1, NAMES.get(index));
        try (var rows = query.executeQuery()) {
          if (!rows.next() || !expected.get(index).equals(rows.getString(1))) {
            throw new StorageException(
                StorageException.Kind.SCHEMA, "Missing or changed debt lifecycle schema");
          }
        }
      }
    }
  }
}
