package ca.emilybelnavis.mcmods.economy.debt;

import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.sql.*;
import java.util.List;

/** Durable bounded replay cursor for safe pre-lifecycle obligation histories. */
public final class DebtLegacySchema {
  private DebtLegacySchema() {}

  private static final String TABLE =
      "CREATE TABLE debt_legacy_reconciliation(singleton INTEGER PRIMARY KEY CHECK(singleton=1),kind TEXT NOT NULL,cursor TEXT NOT NULL,facility TEXT NOT NULL,sequence INTEGER NOT NULL,position INTEGER NOT NULL,frontier INTEGER NOT NULL,target_at INTEGER NOT NULL,source_at INTEGER NOT NULL,source_body TEXT NOT NULL,ready INTEGER NOT NULL CHECK(ready IN(0,1)),revision INTEGER NOT NULL CHECK(revision>0),tracked INTEGER NOT NULL CHECK(tracked IN(0,1)))";
  private static final String INDEX =
      "CREATE INDEX debt_legacy_pending_reports ON debt_obligations(world_id,kind,facility_id,reported,due_at,obligation_id)";

  public static List<String> statements() {
    return List.of(
        TABLE,
        INDEX,
        "INSERT INTO debt_legacy_reconciliation VALUES(1,'CARD','','',0,-1,0,0,0,'',0,1,0)");
  }

  public static void validate(Connection connection) throws SQLException {
    try (var query =
            connection.prepareStatement(
                "SELECT sql FROM sqlite_schema WHERE name='debt_legacy_reconciliation'");
        var rows = query.executeQuery()) {
      if (!rows.next() || !TABLE.equals(rows.getString(1)))
        throw new StorageException(
            StorageException.Kind.SCHEMA, "Missing or changed debt legacy reconciliation cursor");
    }
    try (var query =
            connection.prepareStatement(
                "SELECT sql FROM sqlite_schema WHERE name='debt_legacy_pending_reports'");
        var rows = query.executeQuery()) {
      if (!rows.next() || !INDEX.equals(rows.getString(1)))
        throw new StorageException(
            StorageException.Kind.SCHEMA, "Missing or changed debt legacy report index");
    }
  }
}
