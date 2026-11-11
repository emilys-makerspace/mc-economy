package ca.emilybelnavis.mcmods.economy.storage;

import java.util.ArrayList;
import java.util.List;

/** Schema only. Future migrations must retain existing journal facts and these protections. */
final class JournalSchema {
  private JournalSchema() {}

  static void validate(java.sql.Connection connection) throws java.sql.SQLException {
    for (String sql : statements()) {
      if (!sql.stripLeading().startsWith("CREATE TRIGGER")) continue;
      String name = sql.strip().split("\\s+")[2];
      try (var statement =
          connection.prepareStatement(
              "SELECT sql FROM sqlite_master WHERE type='trigger' AND name=?")) {
        statement.setString(1, name);
        try (var rows = statement.executeQuery()) {
          if (!rows.next() || !normalize(sql).equals(normalize(rows.getString(1))))
            throw new StorageException(
                StorageException.Kind.SCHEMA, "Journal protection missing or altered: " + name);
        }
      }
    }
  }

  private static String normalize(String sql) {
    return sql.replaceAll("\\s+", " ").strip();
  }

  static List<String> statements() {
    var sql = new ArrayList<String>();
    sql.add("CREATE UNIQUE INDEX foundation_world_identity ON foundation_metadata(world_id)");
    sql.add(
        """
            CREATE TABLE operation_outcomes (
              world_id TEXT NOT NULL REFERENCES foundation_metadata(world_id), operation_id TEXT NOT NULL,
              actor TEXT NOT NULL, service TEXT NOT NULL, operation_type TEXT NOT NULL,
              canonical_version INTEGER NOT NULL CHECK(canonical_version>0), canonical_request TEXT NOT NULL,
              request_id TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN ('COMMITTED','DURABLY_REJECTED')),
              result_version INTEGER NOT NULL CHECK(result_version>0), result_code TEXT NOT NULL, result_payload TEXT NOT NULL,
              transaction_id TEXT UNIQUE REFERENCES journal_transactions(transaction_id) DEFERRABLE INITIALLY DEFERRED,
              sequence INTEGER UNIQUE REFERENCES journal_transactions(sequence) DEFERRABLE INITIALLY DEFERRED,
              audit_utc TEXT NOT NULL, PRIMARY KEY(world_id,operation_id),
              CHECK((status='COMMITTED' AND transaction_id IS NOT NULL AND sequence IS NOT NULL) OR
                    (status='DURABLY_REJECTED' AND transaction_id IS NULL AND sequence IS NULL)))
            """);
    sql.add(
        """
            CREATE TABLE journal_transactions (
              sequence INTEGER PRIMARY KEY AUTOINCREMENT CHECK(sequence>0), transaction_id TEXT NOT NULL UNIQUE,
              world_id TEXT NOT NULL, operation_id TEXT NOT NULL, envelope_version INTEGER NOT NULL CHECK(envelope_version=1),
              record_count INTEGER NOT NULL CHECK(record_count BETWEEN 1 AND 64), audit_utc TEXT NOT NULL,
              economic_update_id TEXT, correction_of TEXT REFERENCES journal_transactions(transaction_id),
              UNIQUE(world_id,operation_id),
              FOREIGN KEY(world_id,operation_id) REFERENCES operation_outcomes(world_id,operation_id) DEFERRABLE INITIALLY DEFERRED)
            """);
    sql.add(
        """
            CREATE TABLE journal_records (
              sequence INTEGER NOT NULL REFERENCES journal_transactions(sequence), position INTEGER NOT NULL CHECK(position>=0 AND typeof(position)='integer'),
              record_type TEXT NOT NULL, record_version INTEGER NOT NULL CHECK(record_version>0), payload TEXT NOT NULL,
              PRIMARY KEY(sequence,position))
            """);
    for (String table : List.of("operation_outcomes", "journal_transactions", "journal_records")) {
      for (String action : List.of("UPDATE", "DELETE")) {
        sql.add(
            "CREATE TRIGGER immutable_"
                + table
                + "_"
                + action.toLowerCase()
                + " BEFORE "
                + action
                + " ON "
                + table
                + " BEGIN SELECT RAISE(ABORT,'immutable journal fact'); END");
      }
      // REPLACE can silently delete a conflicting row without firing delete triggers.
      String key =
          switch (table) {
            case "operation_outcomes" -> "world_id=NEW.world_id AND operation_id=NEW.operation_id";
            case "journal_transactions" ->
                "transaction_id=NEW.transaction_id OR (world_id=NEW.world_id AND operation_id=NEW.operation_id) OR sequence=NEW.sequence";
            default -> "sequence=NEW.sequence AND position=NEW.position";
          };
      sql.add(
          "CREATE TRIGGER immutable_"
              + table
              + "_replace BEFORE INSERT ON "
              + table
              + " WHEN EXISTS(SELECT 1 FROM "
              + table
              + " WHERE "
              + key
              + ") BEGIN SELECT RAISE(ABORT,'existing journal fact'); END");
    }
    sql.add(
        """
            CREATE TRIGGER seal_journal_records BEFORE INSERT ON journal_records
            WHEN EXISTS(SELECT 1 FROM operation_outcomes WHERE sequence=NEW.sequence)
              OR NOT EXISTS(SELECT 1 FROM journal_transactions WHERE sequence=NEW.sequence AND NEW.position<record_count)
            BEGIN SELECT RAISE(ABORT,'sealed or invalid journal record'); END
            """);
    sql.add(
        """
            CREATE TRIGGER validate_operation_seal BEFORE INSERT ON operation_outcomes
            WHEN NEW.status='COMMITTED' AND NOT EXISTS(
              SELECT 1 FROM journal_transactions t WHERE t.sequence=NEW.sequence AND t.transaction_id=NEW.transaction_id
              AND t.world_id=NEW.world_id AND t.operation_id=NEW.operation_id AND t.audit_utc=NEW.audit_utc
              AND t.record_count=(SELECT count(*) FROM journal_records r WHERE r.sequence=t.sequence))
            BEGIN SELECT RAISE(ABORT,'incomplete journal transaction'); END
            """);
    sql.add(
        """
            CREATE TRIGGER seal_operation_transactions BEFORE INSERT ON journal_transactions
            WHEN EXISTS(SELECT 1 FROM operation_outcomes WHERE world_id=NEW.world_id AND operation_id=NEW.operation_id)
            BEGIN SELECT RAISE(ABORT,'sealed operation'); END
            """);
    sql.add(
        """
            CREATE TRIGGER validate_rejection_seal BEFORE INSERT ON operation_outcomes
            WHEN NEW.status='DURABLY_REJECTED' AND EXISTS(
              SELECT 1 FROM journal_transactions WHERE world_id=NEW.world_id AND operation_id=NEW.operation_id)
            BEGIN SELECT RAISE(ABORT,'rejection cannot have journal effects'); END
            """);
    return List.copyOf(sql);
  }
}
