package ca.emilybelnavis.mcmods.economy.storage;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans;
import ca.emilybelnavis.mcmods.economy.village.BranchSelection;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** V36 stores presentation plans and bounded branch servicing projections, never customer funds. */
public final class VillageBranchSchema {
  private VillageBranchSchema() {}

  private static final String STATES =
      "'CAPTURE_PENDING','PROVIDER_PENDING','SITE_PENDING','RESERVED','PLACING',"
          + "'PHYSICAL_COMPLETE','ACTIVATION_PENDING','ACTIVE','REMOVED','CONFLICT','OMITTED'";
  private static final Set<String> BRANCH_STATES =
      Set.of(
          "CAPTURE_PENDING",
          "PROVIDER_PENDING",
          "SITE_PENDING",
          "RESERVED",
          "PLACING",
          "PHYSICAL_COMPLETE",
          "ACTIVATION_PENDING",
          "ACTIVE",
          "REMOVED",
          "CONFLICT",
          "OMITTED");
  private static final Set<String> ANCHOR_STATES =
      Set.of("PENDING", "ACTIVE", "REMOVED", "CONFLICT");
  private static final String ANCHOR_INDEX =
      "CREATE INDEX village_anchor_location ON village_branch_anchors(world_id,dimension,x,y,z)";

  public static List<String> statements() {
    return statements(1);
  }

  private static String anchorTable(int maxSlot) {
    return """
                CREATE TABLE village_branch_anchors(
                    world_id TEXT NOT NULL,branch_id TEXT NOT NULL,slot INTEGER NOT NULL CHECK(typeof(slot)='integer' AND slot BETWEEN 0 AND %d),
                    dimension TEXT NOT NULL,x INTEGER NOT NULL CHECK(typeof(x)='integer' AND x BETWEEN -30000000 AND 30000000),
                    y INTEGER NOT NULL CHECK(typeof(y)='integer' AND y BETWEEN -2048 AND 2047),z INTEGER NOT NULL CHECK(typeof(z)='integer' AND z BETWEEN -30000000 AND 30000000),
                    generation TEXT NOT NULL,terminal_revision INTEGER NOT NULL CHECK(typeof(terminal_revision)='integer' AND terminal_revision>=0),
                    state TEXT NOT NULL CHECK(state IN ('PENDING','ACTIVE','REMOVED','CONFLICT')),
                    PRIMARY KEY(world_id,branch_id,slot),UNIQUE(world_id,generation),
                    FOREIGN KEY(world_id,branch_id) REFERENCES village_branches(world_id,branch_id))
                """
        .formatted(maxSlot);
  }

  static List<String> threeAnchorMigration() {
    return List.of(
        "ALTER TABLE village_branch_anchors RENAME TO village_branch_anchors_v53",
        anchorTable(2),
        "INSERT INTO village_branch_anchors SELECT * FROM village_branch_anchors_v53",
        "DROP TABLE village_branch_anchors_v53",
        ANCHOR_INDEX);
  }

  private static List<String> statements(int maxAnchorSlot) {
    return List.of(
        """
                CREATE TABLE village_plans(
                    world_id TEXT NOT NULL,village_id TEXT NOT NULL,source_key TEXT NOT NULL CHECK(length(source_key)<=512),
                    plan_digest TEXT NOT NULL CHECK(length(plan_digest)=64),plan TEXT NOT NULL CHECK(length(CAST(plan AS BLOB))<=262144),
                    revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision>0),
                    PRIMARY KEY(world_id,village_id),UNIQUE(world_id,source_key))
                """,
        """
                CREATE TABLE village_branches(
                    world_id TEXT NOT NULL,branch_id TEXT NOT NULL,village_id TEXT NOT NULL,slot INTEGER NOT NULL CHECK(typeof(slot)='integer' AND slot BETWEEN 0 AND 2),
                    state TEXT NOT NULL CHECK(state IN (%s)),reason TEXT NOT NULL CHECK(length(reason)<=256),
                    revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision>0),staffing_paused INTEGER NOT NULL CHECK(typeof(staffing_paused)='integer' AND staffing_paused IN (0,1)),
                    PRIMARY KEY(world_id,branch_id),UNIQUE(world_id,village_id,slot),
                    FOREIGN KEY(world_id,village_id) REFERENCES village_plans(world_id,village_id))
                """
            .formatted(STATES),
        anchorTable(maxAnchorSlot),
        """
                CREATE TABLE village_branch_events(
                    world_id TEXT NOT NULL,branch_id TEXT NOT NULL,revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision>0),
                    state TEXT NOT NULL CHECK(state IN (%s)),reason TEXT NOT NULL CHECK(length(reason)<=256),operation_id TEXT NOT NULL,
                    PRIMARY KEY(world_id,branch_id,revision),
                    FOREIGN KEY(world_id,branch_id) REFERENCES village_branches(world_id,branch_id))
                """
            .formatted(STATES),
        """
                CREATE TABLE village_provider_fills(
                    world_id TEXT NOT NULL,branch_id TEXT NOT NULL,institution TEXT NOT NULL,native_currency TEXT NOT NULL,
                    snapshot TEXT NOT NULL CHECK(length(CAST(snapshot AS BLOB))<=16384),operation_id TEXT NOT NULL,
                    PRIMARY KEY(world_id,branch_id),
                    FOREIGN KEY(world_id,branch_id) REFERENCES village_branches(world_id,branch_id))
                """,
        "CREATE INDEX village_branch_state ON village_branches(world_id,state,branch_id)",
        "CREATE INDEX village_branch_village ON village_branches(world_id,village_id,slot)",
        ANCHOR_INDEX,
        "CREATE INDEX village_branch_event_operation ON village_branch_events(world_id,operation_id,branch_id)");
  }

  /**
   * Exact schema, canonical immutable capture and relational history checks for online/offline
   * opening.
   */
  public static void validate(Connection connection) throws SQLException {
    try {
      String world;
      int maxAnchorSlot;
      try (var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT world_id,schema_version FROM foundation_metadata WHERE singleton=1")) {
        if (!rows.next()) throw new SQLException("Missing village world identity");
        world = canonicalUuid(rows.getString(1)).toString();
        maxAnchorSlot = rows.getInt(2) >= 54 ? 2 : 1;
      }
      validateStructure(connection, maxAnchorSlot);
      validatePlans(connection, world);
      validateBranches(connection, world);
      validateAnchors(connection, world, maxAnchorSlot);
      validateEvents(connection, world);
      validateProviderFills(connection, world);
      rejectRows(
          connection,
          "SELECT 1 FROM village_branches b LEFT JOIN village_plans p ON p.world_id=b.world_id AND p.village_id=b.village_id WHERE p.village_id IS NULL LIMIT 1",
          "Orphan village branch");
      rejectRows(
          connection,
          "SELECT 1 FROM village_branch_anchors a LEFT JOIN village_branches b ON b.world_id=a.world_id AND b.branch_id=a.branch_id WHERE b.branch_id IS NULL LIMIT 1",
          "Orphan branch anchor");
      rejectRows(
          connection,
          "SELECT 1 FROM village_provider_fills f LEFT JOIN village_branches b ON b.world_id=f.world_id AND b.branch_id=f.branch_id WHERE b.branch_id IS NULL LIMIT 1",
          "Orphan provider fill");
      rejectRows(
          connection,
          "SELECT 1 FROM village_branch_events e LEFT JOIN village_branches b ON b.world_id=e.world_id AND b.branch_id=e.branch_id WHERE b.branch_id IS NULL LIMIT 1",
          "Orphan branch event");
      rejectRows(
          connection,
          """
                    SELECT 1 FROM village_branches b LEFT JOIN village_branch_events e
                    ON e.world_id=b.world_id AND e.branch_id=b.branch_id AND e.revision=b.revision
                    WHERE e.revision IS NULL OR e.state<>b.state OR e.reason<>b.reason LIMIT 1
                    """,
          "Branch latest history mismatch");
      rejectRows(
          connection,
          """
                    SELECT 1 FROM village_branch_events GROUP BY world_id,branch_id
                    HAVING min(revision)<>1 OR count(*)<>max(revision) LIMIT 1
                    """,
          "Branch history gap");
      rejectRows(
          connection,
          """
                    SELECT 1 FROM village_branch_events e JOIN village_branches b
                    ON b.world_id=e.world_id AND b.branch_id=e.branch_id WHERE e.revision>b.revision LIMIT 1
                    """,
          "Branch history exceeds projection");
    } catch (RuntimeException malformed) {
      throw new SQLException("Invalid village branch projection", malformed);
    }
  }

  private static void validateStructure(Connection connection, int maxAnchorSlot)
      throws SQLException {
    try (var query = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
      for (var declaration : statements(maxAnchorSlot)) {
        String sql = declaration.strip();
        String name = sql.split("\\s+", 4)[2].split("\\(", 2)[0];
        query.setString(1, name);
        try (var rows = query.executeQuery()) {
          if (!rows.next() || !sql.equals(rows.getString(1)))
            throw new SQLException("Missing or changed village branch schema " + name);
        }
      }
    }
  }

  private static void validatePlans(Connection connection, String world) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT * FROM village_plans")) {
      while (rows.next()) {
        requireWorld(world, rows.getString("world_id"));
        var village = canonicalUuid(rows.getString("village_id"));
        String body = rows.getString("plan");
        var plan = BranchPlans.decode(body);
        var source = plan.source();
        String sourceKey =
            source.dimension()
                + "|"
                + source.structure()
                + "|"
                + source.startX()
                + "|"
                + source.startZ();
        if (!BranchPlans.encode(plan).equals(body)
            || !plan.digest().equals(rows.getString("plan_digest"))
            || !BranchPlans.id(new WorldId(UUID.fromString(world)), source).equals(village)
            || !sourceKey.equals(rows.getString("source_key"))
            || rows.getLong("revision") < 1)
          throw new SQLException("Village capture projection mismatch");
        validateCapturedChildren(connection, world, village, plan);
      }
    }
  }

  private static void validateCapturedChildren(
      Connection connection, String world, UUID village, BranchPlans.VillagePlanV1 plan)
      throws SQLException {
    try (var query =
        connection.prepareStatement(
            "SELECT slot,branch_id FROM village_branches WHERE world_id=? AND village_id=? ORDER BY slot")) {
      query.setString(1, world);
      query.setString(2, village.toString());
      try (var rows = query.executeQuery()) {
        for (var branch : plan.branches()) {
          var branchId = BranchPlans.branchId(village, branch.slot());
          if (!rows.next()
              || rows.getInt("slot") != branch.slot()
              || !rows.getString("branch_id").equals(branchId.toString()))
            throw new SQLException("Missing captured branch slot");
          var currentChoice =
              BranchPlacementSchema.latestChoice(connection, world, branchId).orElse(branch);
          validateCapturedAnchors(
              connection, world, branchId, plan.source().dimension(), currentChoice.services());
        }
        if (rows.next()) throw new SQLException("Unexpected captured branch slot");
      }
    }
  }

  private static void validateCapturedAnchors(
      Connection connection,
      String world,
      UUID branch,
      String dimension,
      List<BranchPlans.Anchor> anchors)
      throws SQLException {
    try (var query =
        connection.prepareStatement(
            "SELECT * FROM village_branch_anchors WHERE world_id=? AND branch_id=? ORDER BY slot")) {
      query.setString(1, world);
      query.setString(2, branch.toString());
      try (var rows = query.executeQuery()) {
        for (var anchor : anchors) {
          if (!rows.next()
              || rows.getInt("slot") != anchor.slot()
              || !rows.getString("dimension").equals(dimension)
              || rows.getInt("x") != anchor.position().x()
              || rows.getInt("y") != anchor.position().y()
              || rows.getInt("z") != anchor.position().z()
              || !rows.getString("generation").equals(anchor.generation().toString()))
            throw new SQLException("Captured service anchor mismatch");
        }
        if (rows.next()) throw new SQLException("Unexpected captured service anchor");
      }
    }
  }

  private static void validateBranches(Connection connection, String world) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT * FROM village_branches")) {
      while (rows.next()) {
        requireWorld(world, rows.getString("world_id"));
        var village = canonicalUuid(rows.getString("village_id"));
        var branch = canonicalUuid(rows.getString("branch_id"));
        int slot = rows.getInt("slot");
        if (slot < 0
            || slot > 2
            || !BranchPlans.branchId(village, slot).equals(branch)
            || !BRANCH_STATES.contains(rows.getString("state"))
            || rows.getString("reason").length() > 256
            || rows.getLong("revision") < 1
            || rows.getInt("staffing_paused") < 0
            || rows.getInt("staffing_paused") > 1)
          throw new SQLException("Village branch projection bounds");
      }
    }
  }

  private static void validateAnchors(Connection connection, String world, int maxAnchorSlot)
      throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT * FROM village_branch_anchors")) {
      while (rows.next()) {
        requireWorld(world, rows.getString("world_id"));
        canonicalUuid(rows.getString("branch_id"));
        canonicalUuid(rows.getString("generation"));
        if (!rows.getString("dimension").matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
            || rows.getString("dimension").length() > 128
            || rows.getInt("slot") < 0
            || rows.getInt("slot") > maxAnchorSlot
            || Math.abs(rows.getLong("x")) > 30_000_000
            || Math.abs(rows.getLong("z")) > 30_000_000
            || rows.getInt("y") < -2048
            || rows.getInt("y") > 2047
            || rows.getLong("terminal_revision") < 0
            || !ANCHOR_STATES.contains(rows.getString("state")))
          throw new SQLException("Village anchor projection bounds");
      }
    }
  }

  private static void validateEvents(Connection connection, String world) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT * FROM village_branch_events")) {
      while (rows.next()) {
        requireWorld(world, rows.getString("world_id"));
        canonicalUuid(rows.getString("branch_id"));
        canonicalUuid(rows.getString("operation_id"));
        if (rows.getLong("revision") < 1
            || !BRANCH_STATES.contains(rows.getString("state"))
            || rows.getString("reason").length() > 256)
          throw new SQLException("Village event projection bounds");
      }
    }
  }

  private static void validateProviderFills(Connection connection, String world)
      throws SQLException {
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                """
                SELECT f.*,b.village_id,b.slot,p.plan FROM village_provider_fills f
                JOIN village_branches b ON b.world_id=f.world_id AND b.branch_id=f.branch_id
                JOIN village_plans p ON p.world_id=b.world_id AND p.village_id=b.village_id
                """)) {
      while (rows.next()) {
        requireWorld(world, rows.getString("world_id"));
        var branchId = canonicalUuid(rows.getString("branch_id"));
        canonicalUuid(rows.getString("operation_id"));
        var plan = BranchPlans.decode(rows.getString("plan"));
        int slot = rows.getInt("slot");
        var branch =
            plan.branches().stream()
                .filter(candidate -> candidate.slot() == slot)
                .findFirst()
                .orElseThrow();
        var snapshot = BranchSelection.decodeSnapshot(rows.getString("snapshot"));
        var bank =
            InstitutionCatalog.require(
                new InstitutionCatalog.InstitutionId(rows.getString("institution")));
        var currency = EconomyCurrency.valueOf(rows.getString("native_currency"));
        var provider =
            snapshot.providers().stream()
                .filter(candidate -> candidate.institution().equals(bank.id().key()))
                .findFirst()
                .orElseThrow();
        if (!branch.required()
            || branch.state() != BranchPlans.State.PROVIDER_PENDING
            || branch.institution() != null
            || plan.snapshot() == null
            || snapshot.denomination() != plan.snapshot().denomination()
            || bank.baseCurrency() != currency
            || provider.nativeCurrency() != currency
            || slot == 0 && (!provider.primaryEligible() || currency != snapshot.denomination())
            || plan.branches().stream()
                .anyMatch(candidate -> bank.id().key().equals(candidate.institution())))
          throw new SQLException("Invalid required provider fill");
        try (var other =
            connection.prepareStatement(
                """
                        SELECT f.branch_id FROM village_provider_fills f JOIN village_branches b
                        ON b.world_id=f.world_id AND b.branch_id=f.branch_id
                        WHERE f.world_id=? AND b.village_id=? AND f.institution=? AND f.branch_id<>? LIMIT 1
                        """)) {
          other.setString(1, world);
          other.setString(2, rows.getString("village_id"));
          other.setString(3, bank.id().key());
          other.setString(4, branchId.toString());
          try (var duplicate = other.executeQuery()) {
            if (duplicate.next()) throw new SQLException("Duplicate filled village provider");
          }
        }
      }
    }
  }

  private static void requireWorld(String expected, String actual) throws SQLException {
    if (!expected.equals(actual)) throw new SQLException("Foreign village projection world");
  }

  private static UUID canonicalUuid(String value) throws SQLException {
    var uuid = UUID.fromString(value);
    if (!uuid.toString().equals(value)) throw new SQLException("Noncanonical village identity");
    return uuid;
  }

  private static void rejectRows(Connection connection, String query, String message)
      throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(query)) {
      if (rows.next()) throw new SQLException(message);
    }
  }
}
