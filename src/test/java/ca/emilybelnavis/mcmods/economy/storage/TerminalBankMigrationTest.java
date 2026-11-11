package ca.emilybelnavis.mcmods.economy.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TerminalBankMigrationTest {
  @Test
  void version21MigrationRetainsOwnerGenerationAndSettings() throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
      var world = UUID.randomUUID();
      var generation = UUID.randomUUID();
      var owner = "player:" + UUID.randomUUID();
      SchemaMigrations.apply(connection, world, 0, 21, SchemaMigrations.REGISTRY.subList(0, 21));
      try (var insert =
          connection.prepareStatement(
              "INSERT INTO terminal_settings VALUES(?, 'minecraft:overworld',1,70,2,?,?,'ATM',7,1,'BOTH','CAD,EUR','economy:cad_bill_5')")) {
        insert.setString(1, world.toString());
        insert.setString(2, generation.toString());
        insert.setString(3, owner);
        insert.executeUpdate();
      }
      String before;
      try (var sql = connection.createStatement();
          var rows =
              sql.executeQuery(
                  "SELECT generation||'|'||owner||'|'||revision||'|'||mode||'|'||currencies||'|'||denominations FROM terminal_settings")) {
        assertTrue(rows.next());
        before = rows.getString(1);
      }
      SchemaMigrations.apply(connection, world, 21, 22, SchemaMigrations.REGISTRY.subList(0, 22));
      TerminalBankingSchema.validate(connection);
      try (var sql = connection.createStatement();
          var rows =
              sql.executeQuery(
                  "SELECT generation||'|'||owner||'|'||revision||'|'||mode||'|'||currencies||'|'||denominations FROM terminal_settings")) {
        assertTrue(rows.next());
        assertEquals(before, rows.getString(1));
      }
      try (var sql = connection.createStatement();
          var rows = sql.executeQuery("SELECT * FROM terminal_bank_bindings")) {
        assertTrue(rows.next());
        assertEquals(generation.toString(), rows.getString("generation"));
        assertEquals("UNBOUND_BANK", rows.getString("migration_state"));
        assertEquals("", rows.getString("bank_key"));
        assertEquals("BOTH", rows.getString("legacy_mode"));
        assertEquals("CAD,EUR", rows.getString("legacy_currencies"));
        assertFalse(rows.next());
      }
    }
  }
}
