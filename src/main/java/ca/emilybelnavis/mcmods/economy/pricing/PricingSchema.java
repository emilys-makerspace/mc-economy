package ca.emilybelnavis.mcmods.economy.pricing;

import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.sql.*;
import java.util.List;

/**
 * Additive A6-04 pricing/eligibility projections. Immutable facts retain each valuation decision.
 */
public final class PricingSchema {
  private PricingSchema() {}

  public static List<String> statements() {
    return List.of(
        "CREATE TABLE pricing_valuation_sources(source_id TEXT PRIMARY KEY,kind TEXT NOT NULL CHECK(kind IN('ASSET','LIABILITY')),fresh_mark INTEGER NOT NULL CHECK(fresh_mark IN(0,1)))",
        "CREATE INDEX pricing_valuation_registration_facts ON journal_records(record_type,record_version,sequence) WHERE record_type='economy:a6/valuation-source-registration' AND record_version=1",
        "CREATE TABLE pricing_activation(singleton INTEGER PRIMARY KEY CHECK(singleton=1),start_ms INTEGER NOT NULL CHECK(start_ms>=0))",
        "CREATE TABLE pricing_policies(version INTEGER PRIMARY KEY CHECK(version>0),effective_ms INTEGER NOT NULL UNIQUE CHECK(effective_ms>=0),prime_apr TEXT NOT NULL)",
        "INSERT INTO pricing_policies VALUES(1,0,'0.06')",
        "CREATE TABLE pricing_tiers(owner_id TEXT PRIMARY KEY,revision INTEGER NOT NULL CHECK(revision>0),available INTEGER NOT NULL CHECK(available IN(0,1)),eligible INTEGER NOT NULL CHECK(eligible IN(0,1)),net_worth TEXT,denomination TEXT,denomination_revision INTEGER NOT NULL,fingerprint TEXT NOT NULL,evaluated_ms INTEGER NOT NULL,reason TEXT NOT NULL)",
        "CREATE TABLE pricing_tier_history(owner_id TEXT NOT NULL,effective_ms INTEGER NOT NULL,revision INTEGER NOT NULL,available INTEGER NOT NULL CHECK(available IN(0,1)),eligible INTEGER NOT NULL CHECK(eligible IN(0,1)),fingerprint TEXT NOT NULL,PRIMARY KEY(owner_id,effective_ms))",
        "CREATE INDEX pricing_tier_history_effective ON pricing_tier_history(owner_id,effective_ms DESC)");
  }

  public static void validate(Connection connection) throws SQLException {
    try (var query = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
      for (var sql : statements()) {
        if (!sql.startsWith("CREATE ")) continue;
        String name = sql.split("[ (]", 4)[2];
        query.setString(1, name);
        try (var row = query.executeQuery()) {
          if (!row.next() || !sql.equals(row.getString(1)))
            throw new StorageException(
                StorageException.Kind.SCHEMA, "Missing or changed pricing schema " + name);
        }
      }
    }
  }
}
