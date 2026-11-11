package ca.emilybelnavis.mcmods.economy.simulation.macro;

import ca.emilybelnavis.mcmods.economy.simulation.prime.*;
import java.sql.*;
import java.util.*;

/** Strict append-only control/source projection. Rates are published by separate event children. */
public final class MacroSchema {
  private MacroSchema() {}

  public static List<String> statements() {
    return List.of(
        "CREATE TABLE macro_control_policies(id TEXT PRIMARY KEY,world_id TEXT NOT NULL,revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision>0),effective_ms INTEGER NOT NULL CHECK(typeof(effective_ms)='integer' AND effective_ms>=0),config_id TEXT NOT NULL,config_revision INTEGER NOT NULL CHECK(typeof(config_revision)='integer' AND config_revision>0),operation_id TEXT NOT NULL UNIQUE,previous_id TEXT NOT NULL,scheduled_ms INTEGER NOT NULL CHECK(typeof(scheduled_ms)='integer' AND scheduled_ms>=-1),scheduling_frontier INTEGER NOT NULL CHECK(typeof(scheduling_frontier)='integer' AND scheduling_frontier>=scheduled_ms),payload TEXT NOT NULL,UNIQUE(world_id,revision),UNIQUE(world_id,effective_ms))",
        "CREATE INDEX macro_control_current ON macro_control_policies(world_id,effective_ms)",
        "CREATE INDEX macro_control_config ON macro_control_policies(config_id,config_revision,revision)",
        "CREATE INDEX macro_control_facts ON journal_records(record_type,record_version,payload) WHERE record_type IN ('economy:a8/macro-policy','economy:a8/macro-schedule')",
        "CREATE TRIGGER macro_control_policies_update BEFORE UPDATE ON macro_control_policies BEGIN SELECT RAISE(ABORT,'immutable macro control policy'); END",
        "CREATE TRIGGER macro_control_policies_delete BEFORE DELETE ON macro_control_policies BEGIN SELECT RAISE(ABORT,'immutable macro control policy'); END",
        "CREATE TRIGGER macro_control_policies_replace BEFORE INSERT ON macro_control_policies WHEN EXISTS(SELECT 1 FROM macro_control_policies WHERE id=NEW.id OR operation_id=NEW.operation_id OR (world_id=NEW.world_id AND (revision=NEW.revision OR effective_ms=NEW.effective_ms))) BEGIN SELECT RAISE(ABORT,'existing macro control policy'); END");
  }

  public static void validate(Connection c) throws SQLException {
    try {
      for (String expected : statements()) {
        String name = expected.split(" ")[2].split("\\(", 2)[0],
            actual = scalar(c, "SELECT sql FROM sqlite_master WHERE name=?", name);
        if (actual == null || !norm(expected).equals(norm(actual)))
          throw invalid("Macro control schema: " + name);
      }
      String world = scalar(c, "SELECT world_id FROM foundation_metadata WHERE singleton=1");
      if (!scalar(c, "SELECT COUNT(*) FROM macro_control_policies")
          .equals(
              scalar(
                  c,
                  "SELECT COUNT(*) FROM journal_records WHERE record_type=?",
                  MacroFacts.POLICY))) throw invalid("Macro policy fact/projection count");
      MacroControls.Policy previous = null;
      long previousSequence = 0, schedules = 0;
      try (var query = c.createStatement();
          var rows = query.executeQuery("SELECT * FROM macro_control_policies ORDER BY revision")) {
        while (rows.next()) {
          var p = MacroFacts.decodePolicy(rows.getString("payload"));
          if (!p.world().value().toString().equals(world)
              || !world.equals(rows.getString("world_id"))
              || !p.id().toString().equals(rows.getString("id"))
              || p.revision() != rows.getLong("revision")
              || p.effectiveMillis() != rows.getLong("effective_ms")
              || !p.config().id().toString().equals(rows.getString("config_id"))
              || p.config().revision() != rows.getInt("config_revision"))
            throw invalid("Macro policy projection identity");
          long sequence =
              fact(
                  c,
                  world,
                  rows.getString("operation_id"),
                  MacroFacts.POLICY,
                  rows.getString("payload"));
          String firstConfig =
              scalar(
                  c,
                  "SELECT payload FROM macro_control_policies WHERE config_id=? AND config_revision=? ORDER BY revision LIMIT 1",
                  p.config().id().toString(),
                  Integer.toString(p.config().revision()));
          if (firstConfig == null
              || !MacroFacts.decodePolicy(firstConfig).config().equals(p.config()))
            throw invalid("Macro immutable configuration changed");
          if (previous == null) {
            if (p.revision() != 1
                || p.mode() == MacroControls.Mode.MANUAL
                || !rows.getString("previous_id").isEmpty()
                || rows.getLong("scheduled_ms") != -1
                || rows.getLong("scheduling_frontier") != -1)
              throw invalid("Initial macro control policy");
            String aPayload =
                scalar(c, "SELECT payload FROM currency_prime_activation WHERE world_id=?", world);
            if (aPayload == null) throw invalid("Macro policy without captured prime activation");
            var a = CurrencyPrimeFacts.decodeActivation(aPayload);
            if (a.effectiveMillis() != p.effectiveMillis()
                || !a.config().equals(p.config().id())
                || !rows.getString("operation_id")
                    .equals(
                        scalar(
                            c,
                            "SELECT operation_id FROM currency_prime_activation WHERE world_id=?",
                            world)))
              throw invalid("Macro initial policy not atomic with activation");
          } else {
            schedules++;
            if (p.revision() != previous.revision() + 1
                || p.effectiveMillis() <= previous.effectiveMillis()
                || !previous.id().toString().equals(rows.getString("previous_id"))
                || sequence <= previousSequence) throw invalid("Macro policy chronological append");
            var schedule =
                new MacroFacts.Schedule(
                    p.world(),
                    p.id(),
                    previous.id(),
                    rows.getLong("scheduled_ms"),
                    rows.getLong("scheduling_frontier"));
            if (p.effectiveMillis() <= schedule.schedulingFrontier()
                || !MacroFacts.stable("schedule|" + world + "|" + p.id())
                    .toString()
                    .equals(rows.getString("operation_id")))
              throw invalid("Macro policy future frontier/original ID");
            fact(
                c,
                world,
                rows.getString("operation_id"),
                MacroFacts.SCHEDULE,
                MacroFacts.encode(schedule));
            var a =
                CurrencyPrimeFacts.decodeActivation(
                    scalar(
                        c,
                        "SELECT payload FROM currency_prime_activation WHERE world_id=?",
                        world));
            if (p.effectiveMillis() < a.firstEventMillis()
                || (p.effectiveMillis() - a.firstEventMillis()) % CurrencyPrimes.DAY_MILLIS != 0)
              throw invalid("Macro policy financial boundary");
            long pending =
                Long.parseLong(
                    scalar(
                        c,
                        "SELECT COUNT(*) FROM macro_control_policies WHERE world_id=? AND effective_ms>? AND revision<?",
                        world,
                        Long.toString(schedule.schedulingFrontier()),
                        Long.toString(p.revision())));
            if (pending >= 16) throw invalid("Macro pending revision bound");
            if (p.mode() == MacroControls.Mode.MANUAL && previous.mode() == MacroControls.Mode.AUTO)
              throw invalid("Manual policy lacks prior pause");
          }
          previous = p;
          previousSequence = sequence;
        }
      }
      if (schedules
          != Long.parseLong(
              scalar(
                  c,
                  "SELECT COUNT(*) FROM journal_records WHERE record_type=?",
                  MacroFacts.SCHEDULE))) throw invalid("Macro schedule fact count");
    } catch (RuntimeException bad) {
      throw new SQLException("Invalid macro control source", bad);
    }
  }

  private static long fact(
      Connection c, String world, String operation, String type, String payload)
      throws SQLException {
    String seq =
        scalar(
            c,
            "SELECT t.sequence FROM journal_transactions t JOIN journal_records r ON r.sequence=t.sequence WHERE t.world_id=? AND t.operation_id=? AND r.record_type=? AND r.record_version=1 AND r.payload=?",
            world,
            operation,
            type,
            payload);
    if (seq == null
        || !"1"
            .equals(
                scalar(
                    c,
                    "SELECT COUNT(*) FROM journal_transactions t JOIN journal_records r ON r.sequence=t.sequence WHERE t.world_id=? AND t.operation_id=? AND r.record_type=? AND r.record_version=1 AND r.payload=?",
                    world,
                    operation,
                    type,
                    payload))) throw invalid("Macro immutable source fact");
    return Long.parseLong(seq);
  }

  private static String scalar(Connection c, String sql, String... params) throws SQLException {
    try (var query = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) query.setString(i + 1, params[i]);
      try (var rows = query.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  private static String norm(String s) {
    return s.replaceAll("\\s+", " ").strip();
  }

  private static SQLException invalid(String s) {
    return new SQLException(s);
  }
}
