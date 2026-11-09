package ca.emilybelnavis.mcmods.economy.debt;

import ca.emilybelnavis.mcmods.economy.institution.InstitutionSchema;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.sql.*;
import java.util.*;

/** V31 lifecycle projections, constant-size claim witnesses and bounded deadline heads. */
public final class DebtLifecycleSchema {
  private DebtLifecycleSchema() {}

  public static List<String> statements() {
    var sql = new ArrayList<>(InstitutionSchema.allowanceMigration());
    sql.addAll(DebtSchema.statements());
    sql.addAll(DebtClaims.statements());
    sql.addAll(extra());
    return List.copyOf(sql);
  }

  private static List<String> extra() {
    return List.of(
        "CREATE TABLE debt_facility_states(world_id TEXT NOT NULL,kind TEXT NOT NULL,facility_id TEXT NOT NULL,owner_id TEXT NOT NULL,state TEXT NOT NULL,effective_at INTEGER NOT NULL,revision INTEGER NOT NULL,PRIMARY KEY(world_id,kind,facility_id))",
        "CREATE TABLE debt_due_queue(world_id TEXT NOT NULL,kind TEXT NOT NULL,obligation_id TEXT NOT NULL,next_at INTEGER NOT NULL,PRIMARY KEY(world_id,kind,obligation_id))",
        "CREATE INDEX debt_due_head ON debt_due_queue(next_at,kind,obligation_id)",
        "CREATE TABLE debt_default_heads(world_id TEXT NOT NULL,kind TEXT NOT NULL,facility_id TEXT NOT NULL,next_at INTEGER NOT NULL,PRIMARY KEY(world_id,kind,facility_id))",
        "CREATE INDEX debt_default_head ON debt_default_heads(next_at,kind,facility_id)",
        "CREATE TABLE debt_sale_attempts(world_id TEXT NOT NULL,case_id TEXT NOT NULL,next_at INTEGER NOT NULL,PRIMARY KEY(world_id,case_id))",
        "CREATE INDEX debt_sale_head ON debt_sale_attempts(next_at,case_id)",
        "CREATE TABLE debt_bootstrap(singleton INTEGER PRIMARY KEY CHECK(singleton=1),kind TEXT NOT NULL,cursor TEXT NOT NULL,ready INTEGER NOT NULL CHECK(ready IN(0,1)))",
        "CREATE INDEX debt_collectible_expiry ON debt_cases(collection_expires,case_id) WHERE collectability='COLLECTIBLE' AND payment<>'SETTLED'",
        "CREATE INDEX debt_report_effective ON debt_reports(world_id,owner_id,effective_at,report_id,revision)",
        "INSERT INTO debt_bootstrap VALUES(1,'CARD','',0)");
  }

  public static void validate(Connection connection) throws SQLException {
    DebtSchema.validate(connection);
    var sql = new ArrayList<>(DebtClaims.statements());
    sql.addAll(extra());
    for (var expected : sql) {
      if (!expected.startsWith("CREATE ")) continue;
      String name = expected.split(" ", 4)[2].split("\\(", 2)[0];
      try (var query = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
        query.setString(1, name);
        try (var rows = query.executeQuery()) {
          if (!rows.next() || !expected.equals(rows.getString(1)))
            throw new StorageException(StorageException.Kind.SCHEMA, "Changed debt schema " + name);
        }
      }
    }
  }
}
