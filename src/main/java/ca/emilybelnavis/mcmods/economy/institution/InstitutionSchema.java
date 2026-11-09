package ca.emilybelnavis.mcmods.economy.institution;

import java.util.List;

/** V20 institution projections; immutable journal facts remain the financial evidence. */
public final class InstitutionSchema {
  private InstitutionSchema() {}

  public static void validate(java.sql.Connection connection) throws java.sql.SQLException {
    var statements = new java.util.ArrayList<>(statements());
    try (var query = connection.createStatement();
        var row = query.executeQuery("PRAGMA user_version")) {
      if (row.next() && row.getInt(1) >= 31) statements.set(1, allowanceAccounts());
    }
    statements.addAll(ProviderSchema.statements());
    try (var query = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
      for (var sql : statements) {
        if (!sql.startsWith("CREATE TABLE ")) continue;
        String name = sql.substring(13).split("[ (]", 2)[0];
        query.setString(1, name);
        try (var row = query.executeQuery()) {
          if (!row.next() || !sql.equals(row.getString(1)))
            throw new ca.emilybelnavis.mcmods.economy.storage.StorageException(
                ca.emilybelnavis.mcmods.economy.storage.StorageException.Kind.SCHEMA,
                "Missing or changed institution schema " + name);
        }
      }
    }
  }

  public static String allowanceAccounts() {
    return statements().get(1).replace("'ROUNDING'", "'ROUNDING','ALLOWANCE'");
  }

  public static List<String> allowanceMigration() {
    return List.of(
        "ALTER TABLE institution_financial_accounts RENAME TO institution_accounts_v20",
        allowanceAccounts(),
        "INSERT INTO institution_financial_accounts SELECT * FROM institution_accounts_v20",
        "DROP TABLE institution_accounts_v20");
  }

  public static List<String> statements() {
    return List.of(
        "CREATE TABLE institution_parties(world_id TEXT NOT NULL,institution_key TEXT NOT NULL,actor_id TEXT NOT NULL,display_name TEXT NOT NULL,base_currency TEXT NOT NULL,tier TEXT NOT NULL,enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),PRIMARY KEY(world_id,institution_key),UNIQUE(world_id,actor_id))",
        "CREATE TABLE institution_financial_accounts(account_id TEXT PRIMARY KEY,world_id TEXT NOT NULL,institution_key TEXT NOT NULL,role TEXT NOT NULL CHECK(role IN ('CASH','RESERVATION','RECEIVABLE','LIABILITY','PAYABLE','INCOME','EXPENSE','EQUITY','ROUNDING')),currency TEXT NOT NULL,UNIQUE(world_id,institution_key,role,currency),FOREIGN KEY(world_id,institution_key) REFERENCES institution_parties(world_id,institution_key))",
        "CREATE TABLE institution_capital_manifest(world_id TEXT PRIMARY KEY,version INTEGER NOT NULL CHECK(version=1),state TEXT NOT NULL CHECK(state IN ('ALLOCATING','ACTIVE')),server_currency TEXT NOT NULL,denomination_revision INTEGER NOT NULL,publication_day TEXT NOT NULL,publication_reference TEXT NOT NULL,publication_id TEXT NOT NULL,publication_policy TEXT NOT NULL,rates TEXT NOT NULL,total_server_minor INTEGER NOT NULL CHECK(total_server_minor>0),next_allocation INTEGER NOT NULL CHECK(next_allocation BETWEEN 0 AND 25))",
        "CREATE TABLE institution_capital_allocations(world_id TEXT NOT NULL,institution_key TEXT NOT NULL,ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 24),server_currency TEXT NOT NULL,server_minor INTEGER NOT NULL CHECK(server_minor>0),base_currency TEXT NOT NULL,base_minor INTEGER NOT NULL CHECK(base_minor>0),exact_base_major TEXT NOT NULL,residual_major TEXT NOT NULL,residual_numerator TEXT NOT NULL,residual_denominator TEXT NOT NULL,operating_floor_minor INTEGER NOT NULL CHECK(operating_floor_minor>0),publication_id TEXT NOT NULL,PRIMARY KEY(world_id,institution_key),UNIQUE(world_id,ordinal),FOREIGN KEY(world_id) REFERENCES institution_capital_manifest(world_id))");
  }
}
