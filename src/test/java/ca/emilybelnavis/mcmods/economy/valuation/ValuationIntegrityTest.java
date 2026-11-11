package ca.emilybelnavis.mcmods.economy.valuation;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValuationIntegrityTest {
  @TempDir Path world;

  @Test
  void retainedSnapshotsRefuseDamagedProjectionContentAndJournalProvenance() throws Exception {
    var store = new SqliteStorage();
    store.initialize(world.toRealPath());
    ValuationStore.Snapshot saved;
    try {
      var inputs =
          new ValuationData.Inputs(
              ValuationData.catalog().entries().keySet(),
              ValuationData.hash("recipes"),
              ValuationData.hash("tags"),
              ValuationData.hash("features"));
      var manifest = ValuationData.prepare(inputs, ValuationConfig.Config.empty(), List.of());
      saved =
          ValuationStore.publish(
              store, ValuationData.encode(manifest), 1, new ValuationStore.Gate(), 0);
      assertNull(ValuationStore.load(store, java.util.UUID.randomUUID()));
      assertThrows(
          IllegalArgumentException.class,
          () -> ValuationStore.history(store, java.util.UUID.randomUUID(), 8));
    } finally {
      store.close();
    }
    try (var connection =
        DriverManager.getConnection("jdbc:sqlite:" + world.resolve("economy/economy.sqlite"))) {
      ValuationStore.validate(connection);
      connection.setAutoCommit(false);
      // Restore the exact schema after each deliberately bypassed immutable trigger. Validation
      // must inspect source linkage/content, rather than merely notice the missing trigger.
      for (String mutation :
          List.of(
              "UPDATE valuation_chunks SET world_id='00000000-0000-0000-0000-000000000000' WHERE ordinal=0",
              "UPDATE valuation_chunks SET payload=replace(payload, '\"ordinal\":0', '\"ordinal\":1') WHERE ordinal=0",
              "UPDATE valuation_manifests SET operation_id='00000000-0000-0000-0000-000000000000'",
              "UPDATE valuation_active SET manifest_id='00000000-0000-0000-0000-000000000000'")) {
        String table = mutation.split(" ")[1];
        String trigger = table + "_update";
        String declaration = null;
        if (!table.equals("valuation_active")) {
          try (var query =
              connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
            query.setString(1, trigger);
            try (var rows = query.executeQuery()) {
              assertTrue(rows.next());
              declaration = rows.getString(1);
            }
          }
        }
        try (var sql = connection.createStatement()) {
          if (declaration != null) sql.execute("DROP TRIGGER " + trigger);
          assertTrue(sql.executeUpdate(mutation) > 0);
          if (declaration != null) sql.execute(declaration);
        }
        assertThrows(SQLException.class, () -> ValuationStore.validate(connection), mutation);
        connection.rollback();
        ValuationStore.validate(connection);
      }
    }
    var reopened = new SqliteStorage();
    reopened.initialize(world.toRealPath());
    try {
      assertEquals(saved, ValuationStore.load(reopened, saved.id()));
    } finally {
      reopened.close();
    }
  }
}
