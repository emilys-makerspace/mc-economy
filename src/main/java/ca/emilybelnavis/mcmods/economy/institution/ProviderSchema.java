package ca.emilybelnavis.mcmods.economy.institution;

import java.util.List;

/** V20 provider projections; existing account identities and ledger rows are preserved. */
public final class ProviderSchema {
  private ProviderSchema() {}

  public static List<String> statements() {
    return List.of(
        "CREATE TABLE bank_account_providers(account_id TEXT PRIMARY KEY REFERENCES account_catalog(account_id),bank_key TEXT NOT NULL,legacy INTEGER NOT NULL CHECK(legacy IN (0,1)),revision INTEGER NOT NULL CHECK(revision>0),debit_epoch INTEGER NOT NULL CHECK(debit_epoch>=0))",
        "CREATE TABLE bank_provider_leases(lease_id TEXT PRIMARY KEY,operation_id TEXT NOT NULL UNIQUE,intent_fingerprint TEXT NOT NULL)",
        "CREATE TABLE bank_provider_switches(account_id TEXT PRIMARY KEY REFERENCES account_catalog(account_id),target_key TEXT NOT NULL,source_key TEXT NOT NULL,account_revision INTEGER NOT NULL,provider_revision INTEGER NOT NULL,denomination_revision INTEGER NOT NULL,expires_at INTEGER NOT NULL)",
        "CREATE TABLE bank_provider_migration(singleton INTEGER PRIMARY KEY CHECK(singleton=1),cursor TEXT NOT NULL)",
        "INSERT INTO bank_provider_migration(singleton,cursor) VALUES(1,'')");
  }
}
