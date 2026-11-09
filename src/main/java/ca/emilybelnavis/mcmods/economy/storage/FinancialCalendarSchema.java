package ca.emilybelnavis.mcmods.economy.storage;

import java.util.List;

/** Additive calendar metadata and bounded resumable domain deadline checkpoints. */
public final class FinancialCalendarSchema {
  private FinancialCalendarSchema() {}

  public static void validate(java.sql.Connection connection) throws java.sql.SQLException {
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
                StorageException.Kind.SCHEMA,
                "Financial calendar protection missing/changed: " + name);
        }
      }
    }
  }

  public static List<String> statements() {
    return List.of(
        "CREATE TABLE financial_calendar(singleton INTEGER PRIMARY KEY CHECK(singleton=1), version INTEGER NOT NULL CHECK(version=1), origin_ms INTEGER NOT NULL CHECK(origin_ms>=0), observed_wall_ms INTEGER NOT NULL CHECK(observed_wall_ms>=origin_ms), last_effective_ms INTEGER NOT NULL CHECK(last_effective_ms>=origin_ms), completed_effects INTEGER NOT NULL CHECK(completed_effects>=0), last_stage INTEGER NOT NULL DEFAULT -1, last_provider TEXT NOT NULL DEFAULT '', last_event TEXT NOT NULL DEFAULT '', last_fact TEXT NOT NULL DEFAULT '')",
        "CREATE TABLE financial_deadline_providers(provider TEXT PRIMARY KEY, implementations TEXT NOT NULL, completed_event TEXT, completed_ms INTEGER, completed_stage INTEGER, completed_version INTEGER)",
        "CREATE TABLE financial_deadline_versions(provider TEXT NOT NULL REFERENCES financial_deadline_providers(provider), effective_ms INTEGER NOT NULL CHECK(effective_ms>=0), version INTEGER NOT NULL CHECK(version>=1), PRIMARY KEY(provider,effective_ms))",
        "CREATE TABLE financial_deadline_events(event_id TEXT PRIMARY KEY, provider TEXT NOT NULL REFERENCES financial_deadline_providers(provider), effective_ms INTEGER NOT NULL CHECK(effective_ms>=0), stage INTEGER NOT NULL CHECK(stage BETWEEN 0 AND 8), version INTEGER NOT NULL CHECK(version>=1), cursor TEXT NOT NULL, completed_effects INTEGER NOT NULL CHECK(completed_effects>=0), state TEXT NOT NULL CHECK(state IN ('APPLYING','COMPLETE')), global_count INTEGER NOT NULL CHECK(global_count>=0))",
        "CREATE INDEX financial_calendar_fact_lookup ON journal_records(record_type,record_version,payload) WHERE record_type IN ('economy:a6/calendar-activation','economy:a6/deadline-progress','economy:a6/deadline-completion','economy:a6/policy-effective')",
        "CREATE INDEX financial_deadline_due ON financial_deadline_events(state,effective_ms,stage,provider,event_id)",
        "CREATE UNIQUE INDEX financial_deadline_active ON financial_deadline_events(provider) WHERE state='APPLYING'",
        "CREATE TRIGGER financial_calendar_identity BEFORE UPDATE ON financial_calendar WHEN NEW.version!=OLD.version OR NEW.origin_ms!=OLD.origin_ms OR NEW.observed_wall_ms<OLD.observed_wall_ms OR NEW.last_effective_ms<OLD.last_effective_ms OR NEW.completed_effects<OLD.completed_effects OR NEW.completed_effects>OLD.completed_effects+1 BEGIN SELECT RAISE(ABORT,'financial calendar identity/high-water cannot reverse'); END",
        "CREATE TRIGGER financial_calendar_delete BEFORE DELETE ON financial_calendar BEGIN SELECT RAISE(ABORT,'persistent financial calendar'); END",
        "CREATE TRIGGER financial_calendar_replace BEFORE INSERT ON financial_calendar WHEN EXISTS(SELECT 1 FROM financial_calendar) BEGIN SELECT RAISE(ABORT,'existing financial calendar'); END",
        "CREATE TRIGGER financial_deadline_identity BEFORE UPDATE ON financial_deadline_events WHEN NEW.event_id!=OLD.event_id OR NEW.provider!=OLD.provider OR NEW.effective_ms!=OLD.effective_ms OR NEW.stage!=OLD.stage OR NEW.version!=OLD.version OR OLD.state='COMPLETE' OR NEW.cursor<OLD.cursor OR NEW.completed_effects<OLD.completed_effects OR NEW.completed_effects>OLD.completed_effects+1 OR NEW.global_count<OLD.global_count BEGIN SELECT RAISE(ABORT,'deadline identity/progress cannot reverse'); END");
  }
}
