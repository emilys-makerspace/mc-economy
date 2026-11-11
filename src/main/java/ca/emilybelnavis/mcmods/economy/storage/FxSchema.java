package ca.emilybelnavis.mcmods.economy.storage;

import ca.emilybelnavis.mcmods.economy.fx.FxFacts;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** SQLite maintains and can rebuild both FX indexes from the immutable journal rows. */
final class FxSchema {
  private static final Map<String, String> INDEXES =
      Map.of(
          "fx_publication_day",
              "CREATE UNIQUE INDEX fx_publication_day ON journal_records("
                  + FxFacts.DAY_KEY
                  + ") WHERE record_type='"
                  + FxFacts.PUBLICATION
                  + "'",
          "fx_policy_day",
              "CREATE INDEX fx_policy_day ON journal_records("
                  + FxFacts.DAY_KEY
                  + ",sequence) WHERE record_type='"
                  + FxFacts.POLICY
                  + "'");

  private static final String OBSERVATION_TRIGGER =
      "CREATE TRIGGER monotonic_active_observed BEFORE UPDATE ON economic_systems "
          + "WHEN (NEW.active_observed_ms IS NULL)!=(OLD.active_observed_ms IS NULL) "
          + "OR NEW.active_observed_ms<OLD.active_observed_ms "
          + "BEGIN SELECT RAISE(ABORT,'active economic observation cannot reverse'); END";

  private FxSchema() {}

  static List<String> statements() {
    return List.of(
        INDEXES.get("fx_publication_day"),
        INDEXES.get("fx_policy_day"),
        "ALTER TABLE economic_systems ADD COLUMN active_observed_ms INTEGER CHECK(active_observed_ms>=origin_ms)",
        OBSERVATION_TRIGGER);
  }

  static void validate(Connection connection) throws SQLException {
    try (var query =
        connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE type=? AND name=?")) {
      for (var index : INDEXES.entrySet()) {
        requireDefinition(query, "index", index.getKey(), index.getValue());
      }
      requireDefinition(query, "trigger", "monotonic_active_observed", OBSERVATION_TRIGGER);
    }
  }

  private static void requireDefinition(
      java.sql.PreparedStatement query, String type, String name, String expected)
      throws SQLException {
    query.setString(1, type);
    query.setString(2, name);
    try (var row = query.executeQuery()) {
      if (!row.next() || !expected.equals(row.getString(1))) {
        throw new StorageException(
            StorageException.Kind.SCHEMA, "Missing or changed FX schema object " + name);
      }
    }
  }
}
