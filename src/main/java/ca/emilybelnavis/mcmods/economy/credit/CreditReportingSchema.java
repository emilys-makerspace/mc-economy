package ca.emilybelnavis.mcmods.economy.credit;

import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.sql.*;
import java.util.List;

/** Durable, bounded score-source replay and per-owner cycle accumulation. */
public final class CreditReportingSchema {
  private CreditReportingSchema() {}

  public static List<String> statements() {
    return List.of(
        "CREATE TABLE credit_reporting_sources(world_id TEXT NOT NULL,owner_id TEXT NOT NULL,facility_id TEXT NOT NULL,revision INTEGER NOT NULL,effective_at INTEGER NOT NULL,source_sequence INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(world_id,facility_id,revision))",
        "CREATE INDEX credit_reporting_owner_time ON credit_reporting_sources(world_id,owner_id,effective_at,facility_id,revision)",
        "CREATE TABLE credit_reporting_bootstrap(singleton INTEGER PRIMARY KEY CHECK(singleton=1),phase TEXT NOT NULL,cursor_sequence INTEGER NOT NULL,cursor_position INTEGER NOT NULL,owner_cursor TEXT NOT NULL,ready INTEGER NOT NULL CHECK(ready IN(0,1)))",
        "INSERT INTO credit_reporting_bootstrap VALUES(1,'SOURCES',0,-1,'',0)",
        "CREATE TABLE credit_score_heads(world_id TEXT NOT NULL,owner_id TEXT NOT NULL,next_at INTEGER NOT NULL,PRIMARY KEY(world_id,owner_id))",
        "CREATE INDEX credit_score_head ON credit_score_heads(next_at,owner_id)",
        "CREATE TABLE credit_score_tasks(world_id TEXT NOT NULL,owner_id TEXT NOT NULL,boundary INTEGER NOT NULL,step INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(world_id,owner_id))",
        "CREATE TABLE credit_utilization_days(world_id TEXT NOT NULL,owner_id TEXT NOT NULL,boundary INTEGER NOT NULL,day_start INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(world_id,owner_id,boundary,day_start))",
        "CREATE INDEX credit_reporting_historical_sources ON journal_records(sequence,position) WHERE record_type='economy:a6/credit-facility'",
        "CREATE INDEX credit_score_inquiry_fold ON credit_inquiry_applications(world_id,owner_id,bucket,accepted_at,application_id)");
  }

  public static void validate(Connection connection) throws SQLException {
    for (String sql : statements()) {
      if (sql.startsWith("INSERT")) continue;
      String name = sql.split(" ")[2].split("\\(")[0];
      try (var query = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
        query.setString(1, name);
        try (var rows = query.executeQuery()) {
          if (!rows.next() || !sql.equals(rows.getString(1)))
            throw new StorageException(
                StorageException.Kind.SCHEMA, "Missing or changed credit reporting schema");
        }
      }
    }
  }
}
