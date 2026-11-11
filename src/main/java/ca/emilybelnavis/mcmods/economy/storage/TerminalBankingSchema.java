package ca.emilybelnavis.mcmods.economy.storage;

import java.sql.*;
import java.util.List;

/** V22 adds binding metadata without rewriting legacy terminal settings or custody. */
public final class TerminalBankingSchema {
  private TerminalBankingSchema() {}

  public static List<String> statements() {
    return List.of(
        "CREATE TABLE terminal_bank_bindings(world_id TEXT NOT NULL,dimension TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,generation TEXT NOT NULL,bank_key TEXT NOT NULL,catalog_revision INTEGER NOT NULL CHECK(catalog_revision>=0),native_currency TEXT NOT NULL,migration_state TEXT NOT NULL,legacy_mode TEXT NOT NULL,legacy_currencies TEXT NOT NULL,legacy_denominations TEXT NOT NULL,PRIMARY KEY(world_id,dimension,x,y,z,generation))",
        "INSERT INTO terminal_bank_bindings SELECT world_id,dimension,x,y,z,generation,'',0,'','UNBOUND_BANK',mode,currencies,denominations FROM terminal_settings WHERE terminal_kind IN('ATM','TELLER')");
  }

  public static void validate(Connection connection) throws SQLException {
    try (var query =
            connection.prepareStatement(
                "SELECT sql FROM sqlite_schema WHERE name='terminal_bank_bindings'");
        var row = query.executeQuery()) {
      if (!row.next() || !statements().getFirst().equals(row.getString(1)))
        throw new StorageException(
            StorageException.Kind.SCHEMA, "Missing or changed terminal bank binding schema");
    }
  }
}
