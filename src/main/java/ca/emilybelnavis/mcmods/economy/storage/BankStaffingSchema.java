package ca.emilybelnavis.mcmods.economy.storage;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.banker.StaffingRecords.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.sql.*;
import java.util.*;

/** V39 detached employee capacity, immutable identities and revisioned staffing events. */
public final class BankStaffingSchema {
  private BankStaffingSchema() {}

  public static List<String> statements() {
    return List.of(
        """
        CREATE TABLE banker_slots(
            world_id TEXT NOT NULL,branch_id TEXT NOT NULL,slot INTEGER NOT NULL CHECK(typeof(slot)='integer' AND slot BETWEEN 0 AND 1),
            desk_generation TEXT NOT NULL,dimension TEXT NOT NULL CHECK(length(dimension)<=128),
            x INTEGER NOT NULL CHECK(typeof(x)='integer' AND x BETWEEN -30000000 AND 30000000),
            y INTEGER NOT NULL CHECK(typeof(y)='integer' AND y BETWEEN -2048 AND 2047),
            z INTEGER NOT NULL CHECK(typeof(z)='integer' AND z BETWEEN -30000000 AND 30000000),
            desk_state TEXT NOT NULL CHECK(desk_state IN ('ACTIVE','REMOVED')),assignment_id TEXT NOT NULL,employee_id TEXT NOT NULL,
            phase TEXT NOT NULL CHECK(phase IN ('RESERVED','SPAWNING','LIVE','VACANT','UNKNOWN')),
            elapsed INTEGER NOT NULL CHECK(typeof(elapsed)='integer' AND elapsed BETWEEN 0 AND 24000 AND (phase='VACANT' OR elapsed=0)),
            revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision>0),operation_id TEXT NOT NULL,
            PRIMARY KEY(world_id,branch_id,slot),UNIQUE(world_id,desk_generation),UNIQUE(world_id,assignment_id),UNIQUE(world_id,employee_id),
            FOREIGN KEY(world_id,branch_id) REFERENCES village_branches(world_id,branch_id))
        """,
        """
        CREATE TABLE banker_employee_history(
            world_id TEXT NOT NULL,employee_id TEXT NOT NULL,assignment_id TEXT NOT NULL,branch_id TEXT NOT NULL,
            slot INTEGER NOT NULL CHECK(typeof(slot)='integer' AND slot BETWEEN 0 AND 1),desk_generation TEXT NOT NULL,
            phase TEXT NOT NULL CHECK(phase IN ('RESERVED','SPAWNING','LIVE','UNKNOWN','DEAD')),
            PRIMARY KEY(world_id,employee_id),UNIQUE(world_id,assignment_id),
            FOREIGN KEY(world_id,branch_id,slot) REFERENCES banker_slots(world_id,branch_id,slot))
        """,
        """
        CREATE TABLE banker_slot_events(
            world_id TEXT NOT NULL,branch_id TEXT NOT NULL,slot INTEGER NOT NULL CHECK(typeof(slot)='integer' AND slot BETWEEN 0 AND 1),
            revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision>0),
            action TEXT NOT NULL CHECK(action IN ('ATTACH','BEGIN','LIVE','DEATH','DESK_REMOVED','CHECKPOINT','REPLACE','UNKNOWN','DEFER','PAUSE')),
            canonical_row TEXT NOT NULL CHECK(length(CAST(canonical_row AS BLOB))<=2048),operation_id TEXT NOT NULL,
            PRIMARY KEY(world_id,branch_id,slot,revision),
            FOREIGN KEY(world_id,branch_id,slot) REFERENCES banker_slots(world_id,branch_id,slot))
        """);
  }

  public static void validate(Connection connection) throws SQLException {
    try (var query = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
      for (var declaration : statements()) {
        var sql = declaration.strip();
        var name = sql.split("\\s+", 4)[2].split("\\(", 2)[0];
        query.setString(1, name);
        try (var r = query.executeQuery()) {
          if (!r.next() || !sql.equals(r.getString(1)))
            throw new SQLException("Changed banker schema " + name);
        }
      }
    }
    String world;
    try (var s = connection.createStatement();
        var r = s.executeQuery("SELECT world_id FROM foundation_metadata WHERE singleton=1")) {
      if (!r.next()) throw new SQLException("Missing staffing world");
      world = r.getString(1);
    }
    var id = new WorldId(canonicalUuid(world));
    JournalEngine.ReadView reads = (sql, params, max) -> query(connection, sql, params, max);
    var latest = new HashMap<String, Row>();
    var history = new HashMap<UUID, Row>();
    for (var event :
        query(
            connection,
            "SELECT * FROM banker_slot_events ORDER BY world_id,branch_id,slot,revision",
            List.of(),
            Integer.MAX_VALUE)) {
      try {
        if (!world.equals(event.get("world_id")))
          throw new IllegalArgumentException("Foreign staffing event");
        var row = BankStaffing.decodeRow(event.get("canonical_row"));
        Action.valueOf(event.get("action"));
        if (!row.branch().toString().equals(event.get("branch_id"))
            || !Integer.toString(row.slot()).equals(event.get("slot"))
            || !Long.toString(row.revision()).equals(event.get("revision"))
            || !row.operation().toString().equals(event.get("operation_id")))
          throw new IllegalArgumentException("Staffing event identity");
        String key = row.branch() + "/" + row.slot();
        var prior = latest.put(key, row);
        if (row.revision() != (prior == null ? 1 : prior.revision() + 1))
          throw new IllegalArgumentException("Staffing event sequence");
        validateTransition(Action.valueOf(event.get("action")), prior, row);
        String fact = world + "|" + event.get("action") + "|" + event.get("canonical_row");
        if (query(
                    connection,
                    "SELECT r.payload FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE t.world_id=? AND t.operation_id=? AND r.record_type=? AND r.record_version=1 AND r.payload=?",
                    List.of(world, row.operation().toString(), BankStaffing.FACT, fact),
                    1)
                .size()
            != 1) throw new IllegalArgumentException("Staffing event journal mismatch");
        var oldEmployee = history.get(row.employee());
        if (oldEmployee != null
            && (!oldEmployee.assignment().equals(row.assignment())
                || !oldEmployee.branch().equals(row.branch())
                || oldEmployee.slot() != row.slot()
                || !oldEmployee.deskGeneration().equals(row.deskGeneration())))
          throw new IllegalArgumentException("Staff employee reused");
        history.put(row.employee(), row);
      } catch (RuntimeException bad) {
        throw new SQLException("Invalid banker event", bad);
      }
    }
    for (var data : query(connection, "SELECT * FROM banker_slots", List.of(), Integer.MAX_VALUE)) {
      try {
        canonicalUuid(data.get("world_id"));
        if (!world.equals(data.get("world_id")))
          throw new IllegalArgumentException("Foreign banker world");
        for (String key :
            List.of("branch_id", "desk_generation", "assignment_id", "employee_id", "operation_id"))
          canonicalUuid(data.get(key));
        var row = BankStaffing.row(data);
        var branch = Branches.find(reads, id, row.branch()).orElseThrow();
        var anchor =
            branch.choice().desks().stream()
                .filter(a -> a.slot() == row.slot())
                .findFirst()
                .orElseThrow();
        if (!anchor.generation().equals(row.deskGeneration())
            || !branch.plan().source().dimension().equals(row.dimension())
            || anchor.position().x() != row.x()
            || anchor.position().y() != row.y()
            || anchor.position().z() != row.z())
          throw new IllegalArgumentException("Uncaptured banker capacity");
        if (!row.equals(latest.remove(row.branch() + "/" + row.slot())))
          throw new IllegalArgumentException("Staff latest event mismatch");
      } catch (RuntimeException bad) {
        throw new SQLException("Invalid banker slot", bad);
      }
    }
    if (!latest.isEmpty()) throw new SQLException("Orphan banker events");
    for (var data :
        query(connection, "SELECT * FROM banker_employee_history", List.of(), Integer.MAX_VALUE)) {
      try {
        if (!world.equals(data.get("world_id")))
          throw new IllegalArgumentException("Foreign employee world");
        for (String key : List.of("employee_id", "assignment_id", "branch_id", "desk_generation"))
          canonicalUuid(data.get(key));
        var row = history.remove(UUID.fromString(data.get("employee_id")));
        if (row == null
            || !row.assignment().toString().equals(data.get("assignment_id"))
            || !row.branch().toString().equals(data.get("branch_id"))
            || !Integer.toString(row.slot()).equals(data.get("slot"))
            || !row.deskGeneration().toString().equals(data.get("desk_generation"))
            || !(row.phase().equals("VACANT") ? "DEAD" : row.phase()).equals(data.get("phase")))
          throw new IllegalArgumentException("Employee history mismatch");
      } catch (RuntimeException bad) {
        throw new SQLException("Invalid banker employee history", bad);
      }
    }
    if (!history.isEmpty()) throw new SQLException("Missing banker history");
  }

  private static void validateTransition(Action action, Row prior, Row row) {
    if (prior == null) {
      if (action == Action.ATTACH
          && row.phase().equals("RESERVED")
          && row.deskState().equals("ACTIVE")
          && row.elapsed() == 0) return;
      if (action == Action.DESK_REMOVED
          && row.phase().equals("UNKNOWN")
          && row.deskState().equals("REMOVED")
          && row.elapsed() == 0) return;
      throw new IllegalArgumentException("Invalid initial staff event");
    }
    if (!prior.branch().equals(row.branch())
        || prior.slot() != row.slot()
        || !prior.deskGeneration().equals(row.deskGeneration())
        || !prior.dimension().equals(row.dimension())
        || prior.x() != row.x()
        || prior.y() != row.y()
        || prior.z() != row.z()) throw new IllegalArgumentException("Changed staff anchor");
    boolean same =
        prior.assignment().equals(row.assignment()) && prior.employee().equals(row.employee());
    if (action != Action.REPLACE && !same)
      throw new IllegalArgumentException("Changed staff identity");
    boolean valid =
        switch (action) {
          case BEGIN ->
              prior.phase().equals("RESERVED")
                  && row.phase().equals("SPAWNING")
                  && row.deskState().equals("ACTIVE");
          case LIVE ->
              Set.of("SPAWNING", "UNKNOWN").contains(prior.phase())
                  && row.phase().equals("LIVE")
                  && row.deskState().equals("ACTIVE");
          case DEATH ->
              Set.of("RESERVED", "SPAWNING", "LIVE", "UNKNOWN").contains(prior.phase())
                  && row.phase().equals("VACANT")
                  && row.elapsed() == 0;
          case DESK_REMOVED ->
              prior.deskState().equals("ACTIVE")
                  && row.deskState().equals("REMOVED")
                  && prior.phase().equals(row.phase())
                  && prior.elapsed() == row.elapsed();
          case CHECKPOINT, PAUSE ->
              prior.phase().equals("VACANT")
                  && row.phase().equals("VACANT")
                  && row.deskState().equals("ACTIVE")
                  && row.elapsed() >= prior.elapsed()
                  && row.elapsed() - prior.elapsed() <= 200;
          case REPLACE ->
              prior.phase().equals("VACANT")
                  && prior.elapsed() == 24000
                  && row.phase().equals("RESERVED")
                  && row.elapsed() == 0
                  && !prior.assignment().equals(row.assignment())
                  && !prior.employee().equals(row.employee())
                  && row.deskState().equals("ACTIVE");
          case UNKNOWN -> prior.phase().equals("SPAWNING") && row.phase().equals("UNKNOWN");
          case DEFER -> prior.phase().equals("SPAWNING") && row.phase().equals("RESERVED");
          default -> false;
        };
    if (action != Action.DESK_REMOVED && !prior.deskState().equals(row.deskState())) valid = false;
    if (!valid) throw new IllegalArgumentException("Invalid staff event transition");
  }

  private static UUID canonicalUuid(String value) {
    var id = UUID.fromString(value);
    if (!id.toString().equals(value))
      throw new IllegalArgumentException("Noncanonical staffing UUID");
    return id;
  }

  private static List<Map<String, String>> query(
      Connection connection, String sql, List<String> params, int max) {
    try (var statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.size(); i++) statement.setString(i + 1, params.get(i));
      var result = new ArrayList<Map<String, String>>();
      try (var rows = statement.executeQuery()) {
        var meta = rows.getMetaData();
        while (rows.next()) {
          if (result.size() >= max) throw new IllegalArgumentException("Staff query bounds");
          var row = new HashMap<String, String>();
          for (int i = 1; i <= meta.getColumnCount(); i++)
            row.put(meta.getColumnLabel(i), rows.getString(i));
          result.add(row);
        }
      }
      return result;
    } catch (SQLException bad) {
      throw new IllegalArgumentException("Staff validation query", bad);
    }
  }
}
