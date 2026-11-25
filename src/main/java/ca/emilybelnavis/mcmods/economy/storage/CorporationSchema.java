package ca.emilybelnavis.mcmods.economy.storage;

import java.sql.*;
import java.util.List;

/** V56. The database is world-local. Aliases remain reserved after dissolution. */
final class CorporationSchema {
  private CorporationSchema() {}

  static List<String> statements() {
    return List.of(
        "CREATE TABLE corporations(id TEXT PRIMARY KEY NOT NULL, founder TEXT NOT NULL, name TEXT"
            + " NOT NULL, state TEXT NOT NULL CHECK(state IN"
            + " ('ACTIVE','WINDING_DOWN','DISSOLVED')), revision INTEGER NOT NULL"
            + " CHECK(typeof(revision)='integer' AND revision>=1))",
        "CREATE TABLE corporation_claims(company TEXT NOT NULL REFERENCES corporations(id), domain"
            + " TEXT NOT NULL, claim_id TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN"
            + " ('FUNDS','ACCOUNT','HOLD','OBLIGATION','UNCERTAIN','UNAVAILABLE')), PRIMARY"
            + " KEY(company,domain,claim_id))",
        "CREATE INDEX corporation_founder ON corporations(founder,id)",
        "CREATE TABLE corporation_names(name_key TEXT PRIMARY KEY NOT NULL, company TEXT NOT NULL"
            + " REFERENCES corporations(id))",
        "CREATE TRIGGER corporation_identity BEFORE UPDATE ON corporations WHEN NEW.id<>OLD.id OR"
            + " NEW.founder<>OLD.founder OR OLD.state='DISSOLVED' OR NEW.revision<>OLD.revision+1"
            + " BEGIN SELECT RAISE(ABORT,'immutable corporation identity'); END",
        "CREATE TRIGGER corporation_history BEFORE DELETE ON corporations BEGIN SELECT"
            + " RAISE(ABORT,'retained corporation'); END",
        "CREATE TRIGGER corporation_alias_owner BEFORE UPDATE ON corporation_names BEGIN SELECT"
            + " RAISE(ABORT,'retained corporate name'); END",
        "CREATE TRIGGER corporation_alias_history BEFORE DELETE ON corporation_names BEGIN SELECT"
            + " RAISE(ABORT,'retained corporate alias'); END");
  }

  static List<String> migration() {
    var sql = new java.util.ArrayList<>(statements());
    sql.addAll(
        ca.emilybelnavis.mcmods.economy.simulation.activity.ActivitySchema.registrationMigration());
    return List.copyOf(sql);
  }

  static List<String> managerStatements() {
    return List.of(
        "CREATE TABLE corporation_managers(company TEXT NOT NULL REFERENCES"
            + " corporations(id),manager TEXT NOT NULL,revision INTEGER NOT NULL"
            + " CHECK(typeof(revision)='integer' AND revision>=1),active INTEGER NOT NULL"
            + " CHECK(active IN (0,1)),capabilities TEXT NOT NULL,scope TEXT NOT NULL,PRIMARY"
            + " KEY(company,manager))",
        "CREATE TRIGGER corporation_manager_revision BEFORE UPDATE ON corporation_managers WHEN"
            + " NEW.company<>OLD.company OR NEW.manager<>OLD.manager OR"
            + " NEW.revision<>OLD.revision+1 BEGIN SELECT RAISE(ABORT,'invalid membership"
            + " revision'); END",
        "CREATE TRIGGER corporation_manager_history BEFORE DELETE ON corporation_managers BEGIN"
            + " SELECT RAISE(ABORT,'retained membership'); END");
  }

  static List<String> bankStatements() {
    return List.of(
        "CREATE INDEX corporation_manager_selection ON corporation_managers(manager,company) WHERE active=1");
  }

  static List<String> integrationStatements() {
    return List.of(
        "CREATE TABLE corporation_integration_bindings(provider TEXT NOT NULL, external_key TEXT"
            + " NOT NULL, company TEXT NOT NULL REFERENCES corporations(id), registration_request"
            + " TEXT NOT NULL, PRIMARY KEY(provider,external_key))",
        "CREATE TRIGGER corporation_integration_binding_update BEFORE UPDATE ON"
            + " corporation_integration_bindings BEGIN SELECT RAISE(ABORT,'immutable integration"
            + " binding'); END",
        "CREATE TRIGGER corporation_integration_binding_delete BEFORE DELETE ON"
            + " corporation_integration_bindings BEGIN SELECT RAISE(ABORT,'retained integration"
            + " binding'); END",
        "CREATE TABLE corporation_integration_epochs(company TEXT PRIMARY KEY NOT NULL"
            + " REFERENCES corporations(id), epoch INTEGER NOT NULL CHECK(typeof(epoch)='integer'"
            + " AND epoch>=1))",
        "CREATE TRIGGER corporation_integration_wind_down AFTER UPDATE ON corporations WHEN"
            + " OLD.state='ACTIVE' AND NEW.state='WINDING_DOWN' BEGIN INSERT INTO"
            + " corporation_integration_epochs VALUES(NEW.id,NEW.revision) ON CONFLICT(company)"
            + " DO UPDATE SET epoch=excluded.epoch; END",
        "CREATE TABLE corporation_integration_mandates(provider TEXT NOT NULL, external_key TEXT"
            + " NOT NULL, revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND"
            + " revision>=1), epoch INTEGER NOT NULL CHECK(typeof(epoch)='integer' AND epoch>=0),"
            + " active INTEGER NOT NULL CHECK(active IN (0,1)), capabilities TEXT NOT NULL,"
            + " accounts TEXT NOT NULL, PRIMARY KEY(provider,external_key),"
            + " FOREIGN KEY(provider,external_key) REFERENCES"
            + " corporation_integration_bindings(provider,external_key))",
        "CREATE TRIGGER corporation_integration_mandate_revision BEFORE UPDATE ON"
            + " corporation_integration_mandates WHEN NEW.provider<>OLD.provider OR"
            + " NEW.external_key<>OLD.external_key OR NEW.epoch<OLD.epoch OR"
            + " NEW.revision<>OLD.revision+1 BEGIN SELECT RAISE(ABORT,'invalid integration"
            + " mandate revision'); END",
        "CREATE TRIGGER corporation_integration_mandate_history BEFORE DELETE ON"
            + " corporation_integration_mandates BEGIN SELECT RAISE(ABORT,'retained integration"
            + " mandate revision'); END");
  }

  static List<String> capitalStatements() {
    var sql =
        new java.util.ArrayList<>(
            List.of(
                "CREATE TABLE corporation_registry(identifier TEXT PRIMARY KEY NOT NULL, party TEXT NOT NULL UNIQUE REFERENCES corporations(id), funded INTEGER NOT NULL DEFAULT 0 CHECK(funded IN (0,1)))",
                "CREATE TABLE corporation_shares(company TEXT NOT NULL REFERENCES corporation_registry(identifier) ON UPDATE CASCADE,class TEXT NOT NULL,quantity INTEGER NOT NULL CHECK(typeof(quantity)='integer' AND quantity>0),price_minor INTEGER NOT NULL CHECK(typeof(price_minor)='integer' AND price_minor>0),currency TEXT NOT NULL,PRIMARY KEY(company,class))"));
    sql.add(
        "CREATE TABLE corporate_credit_lines(account_id TEXT PRIMARY KEY NOT NULL REFERENCES account_catalog(account_id),company TEXT NOT NULL REFERENCES corporation_registry(identifier) ON UPDATE CASCADE,network TEXT NOT NULL,limit_minor INTEGER NOT NULL CHECK(limit_minor>0),principal INTEGER NOT NULL CHECK(principal>=0 AND principal<=limit_minor),interest INTEGER NOT NULL CHECK(interest>=0),carry INTEGER NOT NULL CHECK(carry>=0 AND carry<3640000),last_day INTEGER NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),active INTEGER NOT NULL CHECK(active IN(0,1)),opened_operation TEXT NOT NULL)");
    sql.addAll(ca.emilybelnavis.mcmods.economy.card.CorporateCards.schema());
    return List.copyOf(sql);
  }

  // Legacy names could contain both spaces and dashes. Disambiguate historical collisions in
  // ID order, using a suffix character that a new name cannot contain.
  static void importIdentifiers(Connection connection) throws SQLException {
    try (var query = connection.createStatement();
        var insert =
            connection.prepareStatement(
                "INSERT INTO corporation_registry(identifier,party) VALUES(?,?) ON CONFLICT(identifier) DO NOTHING");
        var rows = query.executeQuery("SELECT id,name FROM corporations ORDER BY id")) {
      while (rows.next()) {
        String base =
            ca.emilybelnavis.mcmods.economy.corporation.Corporations.identifier(rows.getString(2));
        insert.setString(2, rows.getString(1));
        int duplicate = 0;
        do {
          insert.setString(1, duplicate == 0 ? base : base + "~" + duplicate);
          duplicate++;
        } while (insert.executeUpdate() == 0);
      }
    }
  }

  static void validateCapital(Connection c) throws SQLException {
    validate(c, capitalStatements());
  }

  static void validateIntegrations(Connection c) throws SQLException {
    validate(c, integrationStatements());
  }

  static void validateBanking(Connection c) throws SQLException {
    validate(c, bankStatements());
  }

  static void validateManagers(Connection c) throws SQLException {
    validate(c, managerStatements());
  }

  static void validate(Connection c) throws SQLException {
    validate(c, statements());
  }

  private static void validate(Connection c, List<String> declarations) throws SQLException {
    try (var query = c.prepareStatement("SELECT 1 FROM sqlite_schema WHERE sql=?")) {
      for (String sql : declarations) {
        query.setString(1, sql);
        try (var rows = query.executeQuery()) {
          if (!rows.next()) throw new SQLException("Missing or changed corporation schema");
        }
      }
    }
  }
}
