package ca.emilybelnavis.mcmods.economy.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** V6 account catalog constraints and owner lookup indexes. */
final class AccountSchema {
  private AccountSchema() {}

  static List<String> statements() {
    return List.of(
        """
                CREATE TABLE account_catalog (
                  account_id TEXT PRIMARY KEY NOT NULL,
                  owner_id TEXT NOT NULL,
                  purpose TEXT NOT NULL CHECK(purpose IN ('BANK','TRADING')),
                  currency TEXT,
                  status TEXT NOT NULL CHECK(status IN ('OPEN','CLOSED')),
                  revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision>=1),
                  is_primary INTEGER NOT NULL CHECK(is_primary IN (0,1)),
                  CHECK((purpose='BANK' AND currency IS NOT NULL AND length(currency) BETWEEN 3 AND 12
                    AND currency GLOB '[A-Z]*' AND currency NOT GLOB '*[^A-Z0-9]*')
                    OR (purpose='TRADING' AND currency IS NULL AND status='OPEN')),
                  CHECK(is_primary=0 OR (purpose='BANK' AND status='OPEN'))
                )
                """
            .strip(),
        "CREATE INDEX account_owner_history ON account_catalog(owner_id,account_id)",
        "CREATE INDEX account_owner_open ON account_catalog(owner_id,account_id) WHERE status='OPEN'",
        "CREATE UNIQUE INDEX account_primary ON account_catalog(owner_id) WHERE is_primary=1",
        "CREATE UNIQUE INDEX account_trading ON account_catalog(owner_id) WHERE purpose='TRADING'",
        "CREATE UNIQUE INDEX account_open_bank_currency ON account_catalog(owner_id,currency) WHERE purpose='BANK' AND status='OPEN'",
        """
                CREATE TRIGGER account_insert_guard BEFORE INSERT ON account_catalog
                WHEN EXISTS(SELECT 1 FROM account_catalog WHERE account_id=NEW.account_id)
                  OR (NEW.is_primary=1 AND EXISTS(SELECT 1 FROM account_catalog WHERE owner_id=NEW.owner_id AND is_primary=1))
                  OR (NEW.purpose='TRADING' AND EXISTS(SELECT 1 FROM account_catalog WHERE owner_id=NEW.owner_id AND purpose='TRADING'))
                  OR (NEW.purpose='BANK' AND NEW.status='OPEN' AND (
                    EXISTS(SELECT 1 FROM account_catalog WHERE owner_id=NEW.owner_id AND purpose='BANK'
                      AND status='OPEN' AND currency=NEW.currency)
                    OR EXISTS(SELECT 1 FROM account_catalog WHERE owner_id=NEW.owner_id AND purpose='BANK'
                      AND status='OPEN' LIMIT 1 OFFSET 2)))
                BEGIN SELECT RAISE(ABORT,'conflicting account identity, currency or cap'); END
                """
            .strip(),
        """
                CREATE TRIGGER account_update_guard BEFORE UPDATE ON account_catalog
                WHEN NEW.account_id IS NOT OLD.account_id OR NEW.owner_id IS NOT OLD.owner_id
                  OR NEW.purpose IS NOT OLD.purpose OR NEW.is_primary IS NOT OLD.is_primary
                  OR OLD.status='CLOSED' OR NEW.revision<=OLD.revision
                  OR (NEW.purpose='BANK' AND NEW.status='OPEN' AND EXISTS(
                    SELECT 1 FROM account_catalog WHERE owner_id=NEW.owner_id AND purpose='BANK'
                      AND status='OPEN' AND currency=NEW.currency AND account_id<>OLD.account_id))
                BEGIN SELECT RAISE(ABORT,'invalid account transition'); END
                """
            .strip(),
        """
                CREATE TRIGGER account_delete_guard BEFORE DELETE ON account_catalog
                BEGIN SELECT RAISE(ABORT,'account identities are retained'); END
                """
            .strip());
  }

  static String fourAccountGuard() {
    return statements().get(6).replace("OFFSET 2", "OFFSET 3");
  }

  static void validate(Connection connection) throws SQLException {
    int schema;
    try (var stmt = connection.createStatement();
        var rows = stmt.executeQuery("PRAGMA user_version")) {
      rows.next();
      schema = rows.getInt(1);
    }
    try (var query = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
      for (String original : statements()) {
        String sql =
            schema >= 20 && original.startsWith("CREATE TRIGGER account_insert_guard")
                ? fourAccountGuard()
                : original;
        // CREATE [UNIQUE] <object kind> <object name> ...
        String[] tokens = sql.split("[\\s(]+", 5);
        String name = tokens[tokens[1].equals("UNIQUE") ? 3 : 2];
        query.setString(1, name);
        try (var row = query.executeQuery()) {
          if (!row.next() || !sql.equals(row.getString(1))) {
            throw new StorageException(
                StorageException.Kind.SCHEMA, "Missing or changed account schema object " + name);
          }
        }
      }
    }
  }
}
