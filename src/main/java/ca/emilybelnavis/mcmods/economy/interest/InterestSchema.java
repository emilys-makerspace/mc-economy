package ca.emilybelnavis.mcmods.economy.interest;

import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Versioned yield projections. All amounts are canonical decimal text or checked minor units. */
public final class InterestSchema {
  private InterestSchema() {}

  public static List<String> statements() {
    return List.of(
        "CREATE TABLE deposit_yield_activation(singleton INTEGER PRIMARY KEY CHECK(singleton=1),effective_ms INTEGER NOT NULL CHECK(effective_ms>=0),policy_version INTEGER NOT NULL CHECK(policy_version=1))",
        "CREATE TABLE deposit_yield_claims(account_id TEXT NOT NULL,owner_id TEXT NOT NULL,bank_key TEXT NOT NULL,currency TEXT NOT NULL,active INTEGER NOT NULL CHECK(active IN (0,1)),last_ms INTEGER NOT NULL,day_start_ms INTEGER NOT NULL,balance_minor INTEGER NOT NULL CHECK(balance_minor>=0),weighted_major_ms TEXT NOT NULL,accrued_major TEXT NOT NULL,prior_close_major TEXT NOT NULL,recognized_minor INTEGER NOT NULL CHECK(recognized_minor>=0),due_ms INTEGER NOT NULL,distressed INTEGER NOT NULL CHECK(distressed IN (0,1)),revision INTEGER NOT NULL CHECK(revision>0),paid_major TEXT NOT NULL,apr TEXT NOT NULL,apr_start_ms INTEGER NOT NULL,due_minor INTEGER NOT NULL CHECK(due_minor>=0 AND due_minor<=recognized_minor),created_ms INTEGER NOT NULL CHECK(created_ms>=0),PRIMARY KEY(account_id,bank_key,currency))",
        "CREATE TABLE deposit_yield_due(account_id TEXT NOT NULL,bank_key TEXT NOT NULL,currency TEXT NOT NULL,due_ms INTEGER NOT NULL CHECK(due_ms>0),amount_minor INTEGER NOT NULL CHECK(amount_minor>=0),PRIMARY KEY(account_id,bank_key,currency,due_ms))",
        "CREATE INDEX deposit_yield_due_order ON deposit_yield_due(due_ms,account_id,bank_key,currency) WHERE amount_minor>0",
        "CREATE INDEX deposit_yield_owner ON deposit_yield_claims(owner_id,currency)",
        "CREATE INDEX deposit_yield_claim_due ON deposit_yield_claims(bank_key,due_ms,account_id) WHERE due_ms>0 AND recognized_minor>0",
        "CREATE INDEX deposit_yield_account ON deposit_yield_claims(account_id,active)",
        "CREATE INDEX deposit_yield_active_time ON deposit_yield_claims(active,created_ms)",
        "CREATE INDEX deposit_yield_owed_time ON deposit_yield_claims(created_ms) WHERE recognized_minor>0 OR accrued_major NOT IN ('0.000000000000000000','0')",
        "CREATE INDEX deposit_yield_assigned ON bank_account_providers(account_id) WHERE bank_key<>'economy:unassigned-provider'",
        "CREATE INDEX deposit_yield_pending ON financial_deadline_events(provider,state,effective_ms)");
  }

  public static void validate(Connection connection) throws SQLException {
    try (var query = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
      for (var sql : statements()) {
        var name = sql.split("[ (]", 4)[2];
        query.setString(1, name);
        try (var row = query.executeQuery()) {
          if (!row.next() || !sql.equals(row.getString(1)))
            throw new StorageException(
                StorageException.Kind.SCHEMA, "Missing or changed yield schema " + name);
        }
      }
    }
  }
}
