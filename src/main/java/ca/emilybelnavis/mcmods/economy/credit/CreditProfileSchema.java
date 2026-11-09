package ca.emilybelnavis.mcmods.economy.credit;

import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.sql.*;
import java.util.List;

/** V24: no seed rows and no production scoring/reporting job. */
public final class CreditProfileSchema {
  private CreditProfileSchema() {}

  private static final String PROFILES =
      "CREATE TABLE credit_profiles(world_id TEXT NOT NULL,owner_id TEXT NOT NULL,score INTEGER NOT NULL CHECK(score BETWEEN 300 AND 900),first_issued_at INTEGER NOT NULL CHECK(first_issued_at>=0),first_facility TEXT NOT NULL,first_issuer TEXT NOT NULL,starter_currency TEXT NOT NULL,starter_minor INTEGER NOT NULL CHECK(starter_minor>0),policy_version INTEGER NOT NULL CHECK(policy_version>0),revision INTEGER NOT NULL CHECK(revision>0),last_boundary INTEGER NOT NULL CHECK(last_boundary>=-1),reporting_frontier INTEGER NOT NULL CHECK(reporting_frontier>=0),input_fingerprint TEXT NOT NULL,PRIMARY KEY(world_id,owner_id))";
  private static final String HISTORY =
      "CREATE TABLE credit_score_history(history_id INTEGER PRIMARY KEY AUTOINCREMENT,world_id TEXT NOT NULL,owner_id TEXT NOT NULL,boundary INTEGER NOT NULL CHECK(boundary>=0),entry_kind TEXT NOT NULL CHECK(entry_kind IN('INITIAL','SCORE')),policy_version INTEGER NOT NULL CHECK(policy_version>0),profile_revision INTEGER NOT NULL CHECK(profile_revision>0),score INTEGER NOT NULL CHECK(score BETWEEN 300 AND 900),baseline INTEGER NOT NULL,payment INTEGER NOT NULL,utilization INTEGER NOT NULL,age INTEGER NOT NULL,inquiry INTEGER NOT NULL,defaults INTEGER NOT NULL,input_fingerprint TEXT NOT NULL,input_snapshot TEXT NOT NULL,reasons TEXT NOT NULL,period_id TEXT NOT NULL,reporting_frontier INTEGER NOT NULL CHECK(reporting_frontier>=0),UNIQUE(world_id,owner_id,entry_kind,boundary))";
  private static final String HISTORY_INDEX =
      "CREATE INDEX credit_score_history_owner ON credit_score_history(world_id,owner_id,history_id)";
  private static final String INQUIRIES =
      "CREATE TABLE credit_inquiry_groups(world_id TEXT NOT NULL,owner_id TEXT NOT NULL,bucket INTEGER NOT NULL CHECK(bucket>=0),origin_ms INTEGER NOT NULL CHECK(origin_ms>=0),first_accepted_at INTEGER NOT NULL CHECK(first_accepted_at>=origin_ms),first_application TEXT NOT NULL,first_issuer TEXT NOT NULL,PRIMARY KEY(world_id,owner_id,bucket))";
  private static final String APPLICATIONS =
      "CREATE TABLE credit_inquiry_applications(world_id TEXT NOT NULL,owner_id TEXT NOT NULL,application_id TEXT NOT NULL,issuer TEXT NOT NULL,application_kind TEXT NOT NULL,accepted_at INTEGER NOT NULL CHECK(accepted_at>=0),origin_ms INTEGER NOT NULL CHECK(origin_ms>=0),bucket INTEGER NOT NULL CHECK(bucket>=0),operation_id TEXT NOT NULL,journal_sequence INTEGER NOT NULL CHECK(journal_sequence>0),PRIMARY KEY(world_id,owner_id,application_id))";
  private static final String APPLICATION_INDEX =
      "CREATE INDEX credit_inquiry_application_period ON credit_inquiry_applications(world_id,owner_id,bucket,accepted_at)";
  private static final String APPLICATION_FRONTIER =
      "CREATE INDEX credit_inquiry_application_frontier ON credit_inquiry_applications(world_id,owner_id,bucket,journal_sequence,accepted_at)";

  public static List<String> statements() {
    return List.of(
        PROFILES,
        HISTORY,
        HISTORY_INDEX,
        INQUIRIES,
        APPLICATIONS,
        APPLICATION_INDEX,
        APPLICATION_FRONTIER);
  }

  public static void validate(Connection connection) throws SQLException {
    var names =
        List.of(
            "credit_profiles",
            "credit_score_history",
            "credit_score_history_owner",
            "credit_inquiry_groups",
            "credit_inquiry_applications",
            "credit_inquiry_application_period",
            "credit_inquiry_application_frontier");
    for (int i = 0; i < names.size(); i++)
      try (var q = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
        q.setString(1, names.get(i));
        try (var r = q.executeQuery()) {
          if (!r.next() || !statements().get(i).equals(r.getString(1)))
            throw new StorageException(
                StorageException.Kind.SCHEMA, "Missing or changed credit profile schema");
        }
      }
  }
}
