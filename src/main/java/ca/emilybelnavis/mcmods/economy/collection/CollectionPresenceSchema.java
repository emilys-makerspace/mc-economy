package ca.emilybelnavis.mcmods.economy.collection;

import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.sql.*;
import java.util.List;

public final class CollectionPresenceSchema {
  private CollectionPresenceSchema() {}

  public static List<String> statements() {
    return List.of(
        "CREATE TABLE collection_agent_state(world_id TEXT NOT NULL,owner_id TEXT NOT NULL,lease_id TEXT NOT NULL,phase TEXT NOT NULL,observed_at INTEGER NOT NULL,cooldown_until INTEGER NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),body TEXT NOT NULL,PRIMARY KEY(world_id,owner_id))",
        "CREATE INDEX collection_agent_eligible ON debt_cases(world_id,owner_id,case_id) WHERE collectability='COLLECTIBLE' AND payment<>'SETTLED'");
  }

  public static void validate(Connection connection) throws SQLException {
    for (String sql : statements()) {
      String name = sql.split(" ")[2].split("\\(")[0];
      try (var query = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
        query.setString(1, name);
        try (var rows = query.executeQuery()) {
          if (!rows.next() || !sql.equals(rows.getString(1)))
            throw new StorageException(
                StorageException.Kind.SCHEMA, "Missing or changed collection presentation schema");
        }
      }
    }
  }
}
