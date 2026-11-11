package ca.emilybelnavis.mcmods.economy.credit;

import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.sql.*;
import java.util.List;

/** Additive V25 revolving contracts. No seed facility or token is installed. */
public final class CreditFacilitySchema {
  private CreditFacilitySchema() {}

  private static final List<String> NAMES =
      List.of(
          "credit_facilities",
          "credit_facility_owner",
          "credit_facility_day",
          "credit_facility_month",
          "credit_facility_tokens",
          "credit_facility_active_token",
          "credit_statements",
          "credit_statement_owner");

  public static List<String> statements() {
    return List.of(
        "CREATE TABLE credit_facilities(world_id TEXT NOT NULL,facility_id TEXT NOT NULL,owner_id TEXT NOT NULL,issuer TEXT NOT NULL,currency TEXT NOT NULL,status TEXT NOT NULL,debt_minor INTEGER NOT NULL CHECK(debt_minor>=0),limit_minor INTEGER NOT NULL CHECK(limit_minor>0),access TEXT NOT NULL,next_day INTEGER NOT NULL,next_statement INTEGER NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),body TEXT NOT NULL,PRIMARY KEY(world_id,facility_id))",
        "CREATE INDEX credit_facility_owner ON credit_facilities(world_id,owner_id,facility_id)",
        "CREATE INDEX credit_facility_day ON credit_facilities(status,next_day,facility_id)",
        "CREATE INDEX credit_facility_month ON credit_facilities(status,next_statement,facility_id)",
        "CREATE TABLE credit_facility_tokens(world_id TEXT NOT NULL,token_id TEXT NOT NULL,facility_id TEXT NOT NULL,owner_id TEXT NOT NULL,status TEXT NOT NULL,body TEXT NOT NULL,PRIMARY KEY(world_id,token_id))",
        "CREATE UNIQUE INDEX credit_facility_active_token ON credit_facility_tokens(world_id,facility_id) WHERE status='ACTIVE'",
        "CREATE TABLE credit_statements(world_id TEXT NOT NULL,facility_id TEXT NOT NULL,statement_id TEXT NOT NULL,owner_id TEXT NOT NULL,closed_at INTEGER NOT NULL,due_at INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(world_id,statement_id),UNIQUE(world_id,facility_id,closed_at))",
        "CREATE INDEX credit_statement_owner ON credit_statements(world_id,owner_id,closed_at,statement_id)");
  }

  public static void validate(Connection connection) throws SQLException {
    for (int i = 0; i < NAMES.size(); i++)
      try (var q = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
        q.setString(1, NAMES.get(i));
        try (var r = q.executeQuery()) {
          if (!r.next() || !statements().get(i).equals(r.getString(1)))
            throw new StorageException(
                StorageException.Kind.SCHEMA, "Missing or changed credit facility schema");
        }
      }
  }
}
