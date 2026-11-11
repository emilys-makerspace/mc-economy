package ca.emilybelnavis.mcmods.economy.storage;

import java.util.List;

final class RecoverySchema {
  static void validate(java.sql.Connection connection) throws java.sql.SQLException {
    for (String expected : statements()) {
      if (!expected.startsWith("CREATE TRIGGER")) continue;
      String name = expected.split("\\s+")[2];
      try (var query =
          connection.prepareStatement(
              "SELECT sql FROM sqlite_master WHERE type='trigger' AND name=?")) {
        query.setString(1, name);
        try (var row = query.executeQuery()) {
          if (!row.next()
              || !expected
                  .replaceAll("\\s+", " ")
                  .strip()
                  .equals(row.getString(1).replaceAll("\\s+", " ").strip()))
            throw new StorageException(
                StorageException.Kind.SCHEMA, "Economic protection missing/changed: " + name);
        }
      }
    }
  }

  static List<String> statements() {
    return List.of(
        "CREATE TABLE projection_heads(name TEXT PRIMARY KEY, active_generation TEXT)",
        "CREATE TABLE projection_generations(name TEXT NOT NULL REFERENCES projection_heads(name), generation TEXT NOT NULL, version INTEGER NOT NULL CHECK(version>0), state TEXT NOT NULL CHECK(state IN ('BUILDING','ACTIVE','RETIRED','FAILED')), last_sequence INTEGER NOT NULL CHECK(last_sequence>=0), last_transaction TEXT REFERENCES journal_transactions(transaction_id), target_sequence INTEGER NOT NULL CHECK(target_sequence>=0), checksum TEXT NOT NULL, PRIMARY KEY(name,generation))",
        "CREATE TABLE projection_cells(name TEXT NOT NULL,generation TEXT NOT NULL,key TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(name,generation,key),FOREIGN KEY(name,generation) REFERENCES projection_generations(name,generation))",
        "CREATE TABLE economic_metadata(singleton INTEGER PRIMARY KEY CHECK(singleton=1),seed TEXT NOT NULL,global_sequence INTEGER NOT NULL CHECK(global_sequence>=0 AND typeof(global_sequence)='integer'),observed_wall_ms INTEGER NOT NULL)",
        "INSERT INTO economic_metadata VALUES(1,lower(hex(randomblob(16))),0,0)",
        "CREATE TABLE economic_systems(system_id TEXT PRIMARY KEY,cadence_ms INTEGER NOT NULL CHECK(cadence_ms>0),origin_ms INTEGER NOT NULL,frontier_ms INTEGER NOT NULL,last_sequence INTEGER NOT NULL CHECK(last_sequence>=0))",
        "CREATE TABLE economic_versions(system_id TEXT NOT NULL REFERENCES economic_systems(system_id),version INTEGER NOT NULL CHECK(version>0),implementation_id TEXT NOT NULL,effective_end_ms INTEGER NOT NULL,PRIMARY KEY(system_id,effective_end_ms))",
        "CREATE TABLE economic_updates(update_id TEXT PRIMARY KEY,economic_sequence INTEGER NOT NULL UNIQUE CHECK(economic_sequence>0),system_id TEXT NOT NULL REFERENCES economic_systems(system_id),interval_start_ms INTEGER NOT NULL,interval_end_ms INTEGER NOT NULL,version INTEGER NOT NULL,random_seed TEXT NOT NULL,output TEXT NOT NULL,transaction_id TEXT NOT NULL UNIQUE REFERENCES journal_transactions(transaction_id),journal_sequence INTEGER NOT NULL UNIQUE REFERENCES journal_transactions(sequence),UNIQUE(system_id,interval_end_ms),CHECK(interval_end_ms>interval_start_ms))",
        "CREATE TABLE runtime_state(singleton INTEGER PRIMARY KEY CHECK(singleton=1),clean_shutdown INTEGER NOT NULL CHECK(clean_shutdown IN (0,1)))",
        "INSERT INTO runtime_state VALUES(1,0)",
        "CREATE TRIGGER immutable_economic_metadata_delete BEFORE DELETE ON economic_metadata BEGIN SELECT RAISE(ABORT,'persistent economic identity'); END",
        "CREATE TRIGGER immutable_economic_metadata_replace BEFORE INSERT ON economic_metadata WHEN EXISTS(SELECT 1 FROM economic_metadata WHERE singleton=NEW.singleton) BEGIN SELECT RAISE(ABORT,'existing economic identity'); END",
        "CREATE TRIGGER immutable_economic_system_delete BEFORE DELETE ON economic_systems BEGIN SELECT RAISE(ABORT,'persistent economic system'); END",
        "CREATE TRIGGER immutable_economic_system_replace BEFORE INSERT ON economic_systems WHEN EXISTS(SELECT 1 FROM economic_systems WHERE system_id=NEW.system_id) BEGIN SELECT RAISE(ABORT,'existing economic system'); END",
        "CREATE TRIGGER monotonic_economic_metadata BEFORE UPDATE ON economic_metadata WHEN NEW.seed!=OLD.seed OR NEW.observed_wall_ms<OLD.observed_wall_ms OR NEW.global_sequence<OLD.global_sequence OR NEW.global_sequence>OLD.global_sequence+1 BEGIN SELECT RAISE(ABORT,'economic seed/frontier is immutable or monotonic'); END",
        "CREATE TRIGGER monotonic_economic_system BEFORE UPDATE ON economic_systems WHEN NEW.system_id!=OLD.system_id OR NEW.cadence_ms!=OLD.cadence_ms OR NEW.origin_ms!=OLD.origin_ms OR NEW.frontier_ms<OLD.frontier_ms OR NEW.last_sequence<OLD.last_sequence OR (NEW.frontier_ms!=OLD.frontier_ms AND NEW.frontier_ms!=OLD.frontier_ms+OLD.cadence_ms) BEGIN SELECT RAISE(ABORT,'economic system identity/frontier cannot reverse'); END",
        "CREATE TRIGGER immutable_economic_updates_update BEFORE UPDATE ON economic_updates BEGIN SELECT RAISE(ABORT,'immutable economic completion'); END",
        "CREATE TRIGGER immutable_economic_updates_delete BEFORE DELETE ON economic_updates BEGIN SELECT RAISE(ABORT,'immutable economic completion'); END",
        "CREATE TRIGGER immutable_economic_updates_replace BEFORE INSERT ON economic_updates WHEN EXISTS(SELECT 1 FROM economic_updates WHERE update_id=NEW.update_id OR economic_sequence=NEW.economic_sequence OR (system_id=NEW.system_id AND interval_end_ms=NEW.interval_end_ms)) BEGIN SELECT RAISE(ABORT,'existing economic completion'); END",
        "CREATE TRIGGER immutable_economic_versions_update BEFORE UPDATE ON economic_versions BEGIN SELECT RAISE(ABORT,'immutable simulation version schedule'); END",
        "CREATE TRIGGER immutable_economic_versions_delete BEFORE DELETE ON economic_versions BEGIN SELECT RAISE(ABORT,'immutable simulation version schedule'); END",
        "CREATE TRIGGER immutable_economic_versions_replace BEFORE INSERT ON economic_versions WHEN EXISTS(SELECT 1 FROM economic_versions WHERE system_id=NEW.system_id AND effective_end_ms=NEW.effective_end_ms) BEGIN SELECT RAISE(ABORT,'existing version boundary'); END");
  }
}
