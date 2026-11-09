package ca.emilybelnavis.mcmods.economy.autopay;

import ca.emilybelnavis.mcmods.economy.credit.CardServicing;
import java.sql.*;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Version35 adds explicit consent, original due windows and bounded historical statement
 * reconstruction.
 */
public final class AutopaySchema {
  private AutopaySchema() {}

  public static final String FEED =
      "(record_type='economy:a6/credit-statement' OR (record_type='economy:a6/credit-event' AND payload LIKE 'UkVQQVlNRU5U|%'))";

  public static List<String> statements() {
    return List.of(
        """
            CREATE TABLE autopay_settings(world_id TEXT NOT NULL,kind TEXT NOT NULL,facility_id TEXT NOT NULL,
                owner_id TEXT NOT NULL,enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),funding_id TEXT,mode TEXT NOT NULL,
                effective_at INTEGER NOT NULL CHECK(effective_at>=0),revision INTEGER NOT NULL CHECK(revision>0),next_at INTEGER CHECK(next_at IS NULL OR next_at>=0),body TEXT NOT NULL,
                PRIMARY KEY(world_id,kind,facility_id))
            """,
        "CREATE INDEX autopay_next ON autopay_settings(next_at,kind,facility_id) WHERE enabled=1 AND next_at IS NOT NULL",
        "CREATE INDEX autopay_funding ON autopay_settings(funding_id) WHERE enabled=1",
        "CREATE INDEX autopay_owner ON autopay_settings(world_id,owner_id,kind,facility_id)",
        """
            CREATE TABLE autopay_windows(world_id TEXT NOT NULL,kind TEXT NOT NULL,facility_id TEXT NOT NULL,
                due_at INTEGER NOT NULL CHECK(due_at>=0),state TEXT NOT NULL CHECK(state IN ('WAITING','SATISFIED','EXHAUSTED','CANCELLED')),body TEXT NOT NULL,
                PRIMARY KEY(world_id,kind,facility_id,due_at))
            """,
        "CREATE INDEX autopay_waiting ON autopay_windows(world_id,kind,facility_id,due_at) WHERE state='WAITING'",
        """
            CREATE TABLE autopay_statements(world_id TEXT NOT NULL,facility_id TEXT NOT NULL,statement_id TEXT NOT NULL,
                closed_at INTEGER NOT NULL,due_at INTEGER NOT NULL,balance_minor INTEGER NOT NULL CHECK(balance_minor>=0),remaining_minor INTEGER NOT NULL CHECK(remaining_minor>=0 AND remaining_minor<=balance_minor),
                PRIMARY KEY(world_id,facility_id))
            """,
        """
            CREATE TABLE autopay_bootstrap(singleton INTEGER PRIMARY KEY CHECK(singleton=1),frontier INTEGER NOT NULL,
                sequence INTEGER NOT NULL,position INTEGER NOT NULL,ready INTEGER NOT NULL)
            """,
        "INSERT INTO autopay_bootstrap VALUES(1,0,0,-1,0)",
        "CREATE INDEX autopay_card_feed ON journal_records(sequence,position) WHERE " + FEED);
  }

  public static void validate(Connection connection) throws SQLException {
    try {
      validateStructure(connection);
      try (var statement = connection.createStatement()) {
        try (var rows =
            statement.executeQuery(
                "SELECT COUNT(*) FROM autopay_bootstrap WHERE singleton=1 AND ready IN (0,1) AND sequence>=0 AND frontier>=sequence AND position BETWEEN -1 AND 63")) {
          if (!rows.next() || rows.getInt(1) != 1)
            throw new SQLException("Autopay bootstrap state");
        }
      }
      validateSettings(connection);
      validateWindows(connection);
      validateTrackers(connection);
    } catch (RuntimeException malformed) {
      throw new SQLException("Invalid automatic-payment projection", malformed);
    }
  }

  private static void validateStructure(Connection connection) throws SQLException {
    try (var query = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
      for (var declaration : statements()) {
        String sql = declaration.strip();
        if (!sql.startsWith("CREATE ")) continue;
        String name = sql.split("\\s+", 4)[2].split("\\(", 2)[0];
        query.setString(1, name);
        try (var rows = query.executeQuery()) {
          if (!rows.next() || !sql.equals(rows.getString(1)))
            throw new SQLException("Missing or changed automatic-payment schema " + name);
        }
      }
    }
  }

  private static void validateSettings(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery("SELECT *,length(body) AS body_length FROM autopay_settings")) {
      while (rows.next()) {
        if (rows.getLong("body_length") > 16_384)
          throw new SQLException("Autopay settings body bound");
        var body = rows.getString("body");
        var settings = Autopay.decodeSettings(body);
        if (!Autopay.encodeSettings(settings).equals(body)
            || !settings.world().value().toString().equals(rows.getString("world_id"))
            || !settings.kind().name().equals(rows.getString("kind"))
            || !settings.facility().toString().equals(rows.getString("facility_id"))
            || !settings.owner().value().toString().equals(rows.getString("owner_id"))
            || (settings.enabled() ? 1 : 0) != rows.getInt("enabled")
            || !Objects.equals(
                settings.funding() == null ? null : settings.funding().value().toString(),
                rows.getString("funding_id"))
            || !settings.mode().name().equals(rows.getString("mode"))
            || settings.effectiveAt() != rows.getLong("effective_at")
            || settings.revision() != rows.getLong("revision"))
          throw new SQLException("Autopay settings projection does not match its body");
        String next = rows.getString("next_at");
        if (settings.enabled() != (next != null) || next != null && Long.parseLong(next) < 0)
          throw new SQLException("Autopay settings deadline");
      }
    }
  }

  private static void validateWindows(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery("SELECT *,length(body) AS body_length FROM autopay_windows")) {
      while (rows.next()) {
        if (rows.getLong("body_length") > 16_384)
          throw new SQLException("Autopay window body bound");
        var body = rows.getString("body");
        var window = Autopay.decodeWindow(body);
        var consent = window.consent();
        if (!Autopay.encodeWindow(window).equals(body)
            || !consent.world().value().toString().equals(rows.getString("world_id"))
            || !consent.kind().name().equals(rows.getString("kind"))
            || !consent.facility().toString().equals(rows.getString("facility_id"))
            || window.dueAt() != rows.getLong("due_at")
            || !window.state().name().equals(rows.getString("state")))
          throw new SQLException("Autopay window projection does not match its body");
        var next = window.nextAt();
        if (next != null && next < 0) throw new SQLException("Autopay window deadline");
        if (window.state() == Autopay.State.WAITING
            && (next == null
                || next < window.dueAt()
                || next < window.effectiveAt()
                || next > Math.addExact(window.dueAt(), 2 * CardServicing.DAY_MILLIS)))
          throw new SQLException("Autopay waiting window deadline");
      }
    }
  }

  private static void validateTrackers(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT * FROM autopay_statements")) {
      while (rows.next()) {
        canonicalUuid(rows.getString("world_id"));
        canonicalUuid(rows.getString("facility_id"));
        canonicalUuid(rows.getString("statement_id"));
        long closed = rows.getLong("closed_at"), due = rows.getLong("due_at");
        long balance = rows.getLong("balance_minor"), remaining = rows.getLong("remaining_minor");
        if (closed < 0
            || due != Math.addExact(closed, 14 * CardServicing.DAY_MILLIS)
            || balance < 0
            || remaining < 0
            || remaining > balance) throw new SQLException("Autopay statement tracker bounds");
      }
    }
  }

  private static void canonicalUuid(String value) throws SQLException {
    if (!UUID.fromString(value).toString().equals(value))
      throw new SQLException("Autopay canonical identity");
  }
}
