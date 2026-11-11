package ca.emilybelnavis.mcmods.economy.storage;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.journal.BranchPlacement;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans;
import java.sql.*;
import java.util.*;

/** V38 later-site provenance and progress; no customer or financial tables. */
public final class BranchPlacementSchema {
  private BranchPlacementSchema() {}

  public static List<String> statements() {
    return List.of(
        """
            CREATE TABLE village_placement_attempts(
                world_id TEXT NOT NULL,branch_id TEXT NOT NULL,attempt INTEGER NOT NULL CHECK(typeof(attempt)='integer' AND attempt>0),
                operation_id TEXT NOT NULL,choice TEXT NOT NULL CHECK(length(CAST(choice AS BLOB))<=12288),
                phase TEXT NOT NULL CHECK(phase IN ('RESERVED','PLACING','PHYSICAL_COMPLETE','CONFLICT')),
                reason TEXT NOT NULL CHECK(length(reason)<=128),source_revision INTEGER NOT NULL CHECK(typeof(source_revision)='integer' AND source_revision>0),
                PRIMARY KEY(world_id,branch_id,attempt),UNIQUE(world_id,operation_id),
                FOREIGN KEY(world_id,branch_id) REFERENCES village_branches(world_id,branch_id))
            """,
        "CREATE INDEX village_placement_phase ON village_placement_attempts(world_id,phase,branch_id,attempt)");
  }

  public static Optional<BranchPlans.BranchPlanV1> latestChoice(
      Connection c, String world, UUID branch) throws SQLException {
    // Old supported backups have no V38 table and retain the original capture as authority.
    try (var s = c.createStatement();
        var r =
            s.executeQuery("SELECT 1 FROM sqlite_schema WHERE name='village_placement_attempts'")) {
      if (!r.next()) return Optional.empty();
    }
    try (var s =
        c.prepareStatement(
            "SELECT choice FROM village_placement_attempts WHERE world_id=? AND branch_id=? ORDER BY attempt DESC LIMIT 1")) {
      s.setString(1, world);
      s.setString(2, branch.toString());
      try (var r = s.executeQuery()) {
        return r.next()
            ? Optional.of(BranchPlacement.decodeChoice(r.getString(1)))
            : Optional.empty();
      }
    }
  }

  public static void validate(Connection c) throws SQLException {
    try (var q = c.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
      for (var statement : statements()) {
        var sql = statement.strip();
        var name = sql.split("\\s+", 4)[2].split("\\(", 2)[0];
        q.setString(1, name);
        try (var rows = q.executeQuery()) {
          if (!rows.next() || !sql.equals(rows.getString(1)))
            throw new SQLException("Changed placement schema " + name);
        }
      }
    }
    String world;
    try (var s = c.createStatement();
        var r = s.executeQuery("SELECT world_id FROM foundation_metadata WHERE singleton=1")) {
      if (!r.next()) throw new SQLException("Missing world");
      world = r.getString(1);
    }
    try (var s = c.createStatement();
        var r =
            s.executeQuery(
                """
                SELECT a.*,b.village_id,b.slot,b.revision AS branch_revision,p.plan
                FROM village_placement_attempts a JOIN village_branches b ON b.world_id=a.world_id AND b.branch_id=a.branch_id
                JOIN village_plans p ON p.world_id=b.world_id AND p.village_id=b.village_id
                """)) {
      while (r.next()) {
        try {
          if (!world.equals(r.getString("world_id")))
            throw new IllegalArgumentException("Foreign placement world");
          var branch = UUID.fromString(r.getString("branch_id"));
          var operation = UUID.fromString(r.getString("operation_id"));
          var plan = BranchPlans.decode(r.getString("plan"));
          var original =
              plan.branches().stream()
                  .filter(b -> b.slot() == rUnchecked(r, "slot"))
                  .findFirst()
                  .orElseThrow();
          var choice = BranchPlacement.decodeChoice(r.getString("choice"));
          String institution = original.institution();
          var currency = original.nativeCurrency();
          if (institution == null)
            try (var f =
                c.prepareStatement(
                    "SELECT institution,native_currency FROM village_provider_fills WHERE world_id=? AND branch_id=?")) {
              f.setString(1, world);
              f.setString(2, branch.toString());
              try (var fill = f.executeQuery()) {
                if (fill.next()) {
                  institution = fill.getString(1);
                  currency =
                      ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.valueOf(
                          fill.getString(2));
                }
              }
            }
          if (!BranchPlans.branchId(
                      BranchPlans.id(new WorldId(UUID.fromString(world)), plan.source()),
                      original.slot())
                  .equals(branch)
              || r.getLong("attempt") < 1
              || r.getLong("source_revision") < 1
              || r.getLong("source_revision") >= r.getLong("branch_revision")
              || choice.state() != BranchPlans.State.RESERVED
              || choice.slot() != original.slot()
              || choice.required() != original.required()
              || choice.size() != original.size()
                  && (choice.size() != BranchPlans.Size.STANDARD || original.bounds() != null)
              || choice.optionalRoll() != original.optionalRoll()
              || !Objects.equals(choice.institution(), institution)
              || choice.nativeCurrency() != currency
              || !Set.of("RESERVED", "PLACING", "PHYSICAL_COMPLETE", "CONFLICT")
                  .contains(r.getString("phase")))
            throw new IllegalArgumentException("Placement capture mismatch");
          for (var a : choice.services())
            if (!a.generation().equals(BranchPlans.generation(branch, a.slot())))
              throw new IllegalArgumentException("Placement generation");
          for (var a : choice.desks())
            if (!a.generation()
                .equals(BranchPlans.generation(branch, choice.deskGenerationSlot(a.slot()))))
              throw new IllegalArgumentException("Placement desk generation");
          if (!operation.toString().equals(r.getString("operation_id")))
            throw new IllegalArgumentException("Placement operation");
        } catch (RuntimeException bad) {
          throw new SQLException("Invalid placement attempt", bad);
        }
      }
    }
    try (var s = c.createStatement();
        var r =
            s.executeQuery(
                "SELECT 1 FROM village_placement_attempts a LEFT JOIN village_branches b ON b.world_id=a.world_id AND b.branch_id=a.branch_id WHERE b.branch_id IS NULL LIMIT 1")) {
      if (r.next()) throw new SQLException("Orphan placement attempt");
    }
  }

  private static int rUnchecked(ResultSet r, String name) {
    try {
      return r.getInt(name);
    } catch (SQLException e) {
      throw new IllegalArgumentException(e);
    }
  }
}
