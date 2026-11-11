package ca.emilybelnavis.mcmods.economy.simulation.prime;

import static ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimeFacts.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.simulation.model.MacroModel;
import ca.emilybelnavis.mcmods.economy.simulation.plan.ActivationModelPlans;
import ca.emilybelnavis.mcmods.economy.simulation.window.WindowStore;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** Additive immutable policies. Validation never edits legacy global policies or source windows. */
public final class CurrencyPrimeSchema {
  private CurrencyPrimeSchema() {}

  public static List<String> statements() {
    var sql =
        new ArrayList<>(
            List.of(
                "CREATE TABLE currency_prime_activation(world_id TEXT PRIMARY KEY,id TEXT NOT NULL UNIQUE,effective_ms INTEGER NOT NULL CHECK(typeof(effective_ms)='integer' AND effective_ms>=0),operation_id TEXT NOT NULL UNIQUE,payload TEXT NOT NULL)",
                "CREATE TABLE currency_prime_policies(id TEXT PRIMARY KEY,world_id TEXT NOT NULL,currency TEXT NOT NULL,revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision>0),effective_ms INTEGER NOT NULL CHECK(typeof(effective_ms)='integer' AND effective_ms>=0),activation_id TEXT NOT NULL REFERENCES currency_prime_activation(id),window_id TEXT NOT NULL,operation_id TEXT NOT NULL,payload TEXT NOT NULL,UNIQUE(world_id,currency,revision),UNIQUE(world_id,currency,effective_ms))",
                "CREATE TABLE currency_prime_completions(id TEXT PRIMARY KEY,world_id TEXT NOT NULL,window_id TEXT NOT NULL UNIQUE,effective_ms INTEGER NOT NULL CHECK(typeof(effective_ms)='integer' AND effective_ms>=0),operation_id TEXT NOT NULL UNIQUE,payload TEXT NOT NULL,UNIQUE(world_id,effective_ms))",
                "CREATE INDEX currency_prime_current ON currency_prime_policies(world_id,currency,effective_ms)",
                "CREATE INDEX currency_prime_window ON currency_prime_policies(world_id,window_id,currency)",
                "CREATE INDEX currency_prime_source_facts ON journal_records(record_type,record_version,payload) WHERE record_type IN ('economy:a8/currency-prime-activation','economy:a8/currency-prime','economy:a8/currency-prime-complete')"));
    for (String table :
        List.of(
            "currency_prime_activation", "currency_prime_policies", "currency_prime_completions")) {
      for (String action : List.of("UPDATE", "DELETE"))
        sql.add(
            "CREATE TRIGGER "
                + table
                + "_"
                + action.toLowerCase(Locale.ROOT)
                + " BEFORE "
                + action
                + " ON "
                + table
                + " BEGIN SELECT RAISE(ABORT,'immutable currency prime'); END");
      String keys =
          table.equals("currency_prime_activation")
              ? "world_id=NEW.world_id OR id=NEW.id OR operation_id=NEW.operation_id"
              : table.equals("currency_prime_policies")
                  ? "id=NEW.id OR (world_id=NEW.world_id AND currency=NEW.currency AND (revision=NEW.revision OR effective_ms=NEW.effective_ms))"
                  : "id=NEW.id OR window_id=NEW.window_id OR operation_id=NEW.operation_id OR (world_id=NEW.world_id AND effective_ms=NEW.effective_ms)";
      sql.add(
          "CREATE TRIGGER "
              + table
              + "_replace BEFORE INSERT ON "
              + table
              + " WHEN EXISTS(SELECT 1 FROM "
              + table
              + " WHERE "
              + keys
              + ") BEGIN SELECT RAISE(ABORT,'existing currency prime'); END");
    }
    return List.copyOf(sql);
  }

  public static void validate(Connection c) throws SQLException {
    try {
      for (String expected : statements()) {
        String name = expected.split(" ")[2].split("\\(", 2)[0],
            actual = scalar(c, "SELECT sql FROM sqlite_master WHERE name=?", name);
        if (actual == null || !norm(expected).equals(norm(actual)))
          throw invalid("Currency prime schema: " + name);
      }
      String world = scalar(c, "SELECT world_id FROM foundation_metadata WHERE singleton=1");
      counts(c, "currency_prime_activation", ACTIVATION);
      counts(c, "currency_prime_policies", POLICY);
      counts(c, "currency_prime_completions", COMPLETE);
      var reads = reads(c);
      var activationRows = reads.query("SELECT * FROM currency_prime_activation", List.of(), 1);
      if (activationRows.isEmpty()) {
        if (!"0".equals(scalar(c, "SELECT COUNT(*) FROM currency_prime_policies"))
            || !"0".equals(scalar(c, "SELECT COUNT(*) FROM currency_prime_completions")))
          throw invalid("Prime source without activation");
        return;
      }
      var row = activationRows.getFirst();
      var a = decodeActivation(row.get("payload"));
      if (!a.world().value().toString().equals(world)
          || !world.equals(row.get("world_id"))
          || !a.id().toString().equals(row.get("id"))
          || a.effectiveMillis() != Long.parseLong(row.get("effective_ms")))
        throw invalid("Prime activation projection");
      long activationSequence = fact(c, row.get("operation_id"), ACTIVATION, row.get("payload"));
      String av = version(c, row.get("operation_id"), ACTIVATION);
      if ("1".equals(av)
          ? !stable("activate|" + world + "|" + a.id()).toString().equals(row.get("operation_id"))
          : !"2".equals(av)
              || !ca.emilybelnavis.mcmods.economy.simulation.macro.MacroActivation.owns(
                  c, row.get("operation_id"))) throw invalid("Prime activation version/operation");
      if (a.originMillis()
          != Long.parseLong(
              scalar(c, "SELECT origin_ms FROM financial_calendar WHERE singleton=1")))
        throw invalid("Prime activation calendar origin");
      String legacy =
          scalar(
              c,
              "SELECT prime_apr FROM pricing_policies WHERE version=?",
              Integer.toString(a.legacyVersion()));
      String legacyTime =
          scalar(
              c,
              "SELECT effective_ms FROM pricing_policies WHERE version=?",
              Integer.toString(a.legacyVersion()));
      if (legacy == null
          || new BigDecimal(legacy).compareTo(a.inheritedApr()) != 0
          || Long.parseLong(legacyTime) > a.schedulingFrontier()
          || !Integer.toString(a.legacyVersion())
              .equals(
                  scalar(
                      c,
                      "SELECT version FROM pricing_policies WHERE effective_ms<=? ORDER BY effective_ms DESC LIMIT 1",
                      Long.toString(a.schedulingFrontier()))))
        throw invalid("Prime inherited configured policy");
      if (!"0"
          .equals(
              scalar(
                  c,
                  "SELECT COUNT(*) FROM pricing_policies WHERE effective_ms>?",
                  Long.toString(a.schedulingFrontier()))))
        throw invalid("Unreconciled future legacy prime edit");
      var anchors = FxService.snapshot(reads, activationSequence);
      if (anchors == null
          || !anchors.id().equals(a.fxSnapshot())
          || !anchors.world().equals(a.world())
          || anchors.reference() != a.reference()
          || !anchors.rates().equals(a.anchors()))
        throw invalid("Prime activation FX anchor source");
      long anchorMillis = anchors.effectiveMillis();
      if (a.scheduledMillis() < anchorMillis
          || a.scheduledMillis() >= Math.addExact(anchorMillis, 172_800_000L))
        throw invalid("Prime activation FX freshness");
      long bootstrap = 0;
      String previousCurrency = "";
      CurrencyPrimes.Policy previous = null;
      try (var q = c.createStatement();
          var rows =
              q.executeQuery("SELECT * FROM currency_prime_policies ORDER BY currency,revision")) {
        while (rows.next()) {
          var p = decodePolicy(rows.getString("payload"));
          if (!p.world().equals(a.world())
              || !p.id().toString().equals(rows.getString("id"))
              || !world.equals(rows.getString("world_id"))
              || !p.currency().name().equals(rows.getString("currency"))
              || p.revision() != rows.getLong("revision")
              || p.effectiveMillis() != rows.getLong("effective_ms")
              || !p.activation().equals(a.id())
              || !p.activation().toString().equals(rows.getString("activation_id"))
              || !p.window().toString().equals(rows.getString("window_id")))
            throw invalid("Currency prime projection");
          long sequence =
              fact(c, rows.getString("operation_id"), POLICY, rows.getString("payload"));
          if (!previousCurrency.equals(p.currency().name())) previous = null;
          if (previous == null) {
            bootstrap++;
            if (p.mode() != CurrencyPrimes.Mode.BOOTSTRAP
                || p.revision() != 1
                || p.effectiveMillis() != a.effectiveMillis()
                || !p.config().equals(a.config())
                || !p.window().equals(a.id())
                || p.primeApr().compareTo(a.inheritedApr()) != 0
                || sequence != activationSequence) throw invalid("Currency prime bootstrap");
          } else {
            var selected =
                ca.emilybelnavis.mcmods.economy.simulation.macro.MacroPlans.read(reads, p.window());
            String pv = version(c, rows.getString("operation_id"), POLICY);
            if ("2".equals(pv)) {
              String selectedSeq =
                  scalar(
                      c,
                      "SELECT t.sequence FROM macro_rate_plans p JOIN journal_transactions t ON t.operation_id=p.operation_id WHERE p.window_id=?",
                      p.window().toString());
              if (selected == null
                  || selectedSeq == null
                  || Long.parseLong(selectedSeq) >= sequence)
                throw invalid("Prime v2 requires prior selected plan");
            } else if (!"1".equals(pv) || selected != null)
              throw invalid("Prime v1 selected-plan reinterpretation");
            if (selected == null
                    && p.mode() != CurrencyPrimes.Mode.AUTO
                    && p.mode() != CurrencyPrimes.Mode.PAUSED
                || p.revision() != previous.revision() + 1
                || p.effectiveMillis() != previous.effectiveMillis() + CurrencyPrimes.DAY_MILLIS
                || p.primeApr()
                        .subtract(previous.primeApr())
                        .abs()
                        .compareTo(new BigDecimal("0.001"))
                    > 0) throw invalid("Currency prime continuity/bounds");
            String planSequence =
                scalar(
                    c,
                    "SELECT t.sequence FROM activation_model_plans m JOIN journal_transactions t ON t.operation_id=m.operation_id WHERE m.window_id=?",
                    p.window().toString());
            if (planSequence == null || Long.parseLong(planSequence) >= sequence)
              throw invalid("Prime source plan must precede currency child");
            var plan = ActivationModelPlans.read(reads, p.window());
            var open = WindowStore.readOpen(reads, p.window());
            if (plan == null
                || open == null
                || !a.id().equals(ActivationModelPlans.activation(reads, p.window()))
                || open.boundaryMillis() != p.effectiveMillis()
                || !plan.output().available()
                || !plan.output().equals(MacroModel.compute(plan.input()))
                || !plan.input().config().id().equals(p.config())
                || !plan.input().anchors().equals(a.anchors()))
              throw invalid("Currency prime frozen model source");
            if (selected != null) {
              if (!selected.world().equals(a.world())
                  || !selected.mode().name().equals(p.mode().name())
                  || !selected.automaticHash().equals(plan.output().hash()))
                throw invalid("Prime selected provenance");
              ca.emilybelnavis.mcmods.economy.simulation.macro.MacroActivation.requireChild(
                  c,
                  rows.getString("operation_id"),
                  "10-prime/" + p.currency().name(),
                  p.effectiveMillis());
            }
            var expected =
                selected != null
                    ? selected.primes().get(p.currency())
                    : p.mode() == CurrencyPrimes.Mode.PAUSED
                        ? previous.primeApr()
                        : plan.output().primes().get(p.currency());
            if (expected.compareTo(p.primeApr()) != 0
                || plan.input().priorPrime().get(p.currency()).compareTo(previous.primeApr()) != 0)
              throw invalid("Currency prime candidate differs from model");
            long observedDays =
                (p.effectiveMillis() - a.effectiveMillis()) / CurrencyPrimes.DAY_MILLIS;
            for (var w : plan.input().windows().values())
              if (w.days() != Math.min(7, observedDays)
                  || w.baselineDays() != Math.min(7, Math.max(0, observedDays - 7)))
                throw invalid("Currency prime activation coverage");
            String mixed =
                scalar(
                    c,
                    "SELECT payload FROM currency_prime_policies WHERE world_id=? AND window_id=? AND currency<>? LIMIT 1",
                    world,
                    p.window().toString(),
                    p.currency().name());
            if (mixed != null && decodePolicy(mixed).mode() != p.mode())
              throw invalid("Mixed currency prime event mode");
            if (previous.mode() != CurrencyPrimes.Mode.BOOTSTRAP) {
              String completion =
                  scalar(
                      c,
                      "SELECT t.sequence FROM currency_prime_completions pc JOIN journal_transactions t ON t.operation_id=pc.operation_id WHERE pc.world_id=? AND pc.effective_ms=?",
                      world,
                      Long.toString(previous.effectiveMillis()));
              if (completion == null || Long.parseLong(completion) >= sequence)
                throw invalid("Prior prime basket was incomplete at publication");
            }
          }
          previous = p;
          previousCurrency = p.currency().name();
        }
      }
      if (bootstrap != a.currencies().size())
        throw invalid("Incomplete bootstrap currency manifest");
      try (var q = c.createStatement();
          var rows =
              q.executeQuery("SELECT * FROM currency_prime_completions ORDER BY effective_ms")) {
        long expected = a.firstEventMillis();
        while (rows.next()) {
          var done = decodeCompletion(rows.getString("payload"));
          long sequence =
              fact(c, rows.getString("operation_id"), COMPLETE, rows.getString("payload"));
          if (!done.world().equals(a.world())
              || !done.activation().equals(a.id())
              || done.effectiveMillis() != expected
              || done.effectiveMillis() != rows.getLong("effective_ms")
              || !done.id().toString().equals(rows.getString("id"))
              || !world.equals(rows.getString("world_id"))
              || !done.window().toString().equals(rows.getString("window_id"))
              || !stable("basket|" + world + "|" + done.window()).equals(done.id()))
            throw invalid("Prime basket chronology/projection");
          var plan = ActivationModelPlans.read(reads, done.window());
          if (plan == null || !plan.output().hash().equals(done.modelHash()))
            throw invalid("Prime completion frozen plan");
          for (int i = 0; i < a.currencies().size(); i++) {
            var p = CurrencyPrimes.byId(reads, a.world(), done.policies().get(i)).orElseThrow();
            if (p.currency() != a.currencies().get(i)
                || p.effectiveMillis() != done.effectiveMillis()
                || !p.window().equals(done.window())) throw invalid("Prime basket member identity");
            String source =
                scalar(
                    c,
                    "SELECT t.sequence FROM currency_prime_policies p JOIN journal_transactions t ON t.operation_id=p.operation_id WHERE p.id=?",
                    p.id().toString());
            if (source == null || Long.parseLong(source) >= sequence)
              throw invalid("Prime basket completes before child");
          }
          expected = Math.addExact(expected, CurrencyPrimes.DAY_MILLIS);
        }
      }
    } catch (RuntimeException bad) {
      throw new SQLException("Invalid currency-prime source", bad);
    }
  }

  private static JournalEngine.ReadView reads(Connection c) {
    return (sql, params, max) -> {
      try (var q = c.prepareStatement(sql)) {
        for (int i = 0; i < params.size(); i++) q.setString(i + 1, params.get(i));
        try (var rows = q.executeQuery()) {
          var result = new ArrayList<Map<String, String>>();
          var meta = rows.getMetaData();
          while (rows.next()) {
            if (result.size() >= max)
              throw new IllegalArgumentException("Prime validation query bound");
            var row = new LinkedHashMap<String, String>();
            for (int i = 1; i <= meta.getColumnCount(); i++)
              row.put(meta.getColumnLabel(i), rows.getString(i));
            result.add(Collections.unmodifiableMap(row));
          }
          return List.copyOf(result);
        }
      } catch (SQLException bad) {
        throw new IllegalStateException(bad);
      }
    };
  }

  private static void counts(Connection c, String table, String type) throws SQLException {
    if (!scalar(c, "SELECT COUNT(*) FROM " + table)
        .equals(scalar(c, "SELECT COUNT(*) FROM journal_records WHERE record_type=?", type)))
      throw invalid("Prime fact/projection count: " + table);
  }

  private static String version(Connection c, String operation, String type) throws SQLException {
    return scalar(
        c,
        "SELECT r.record_version FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE t.operation_id=? AND r.record_type=?",
        operation,
        type);
  }

  private static long fact(Connection c, String operation, String type, String payload)
      throws SQLException {
    String seq =
        scalar(
            c,
            "SELECT t.sequence FROM journal_transactions t JOIN journal_records r ON r.sequence=t.sequence WHERE t.operation_id=? AND r.record_type=? AND r.record_version IN (1,2) AND r.payload=?",
            operation,
            type,
            payload);
    if (seq == null
        || !"1"
            .equals(
                scalar(
                    c,
                    "SELECT COUNT(*) FROM journal_transactions t JOIN journal_records r ON r.sequence=t.sequence WHERE t.operation_id=? AND r.record_type=? AND r.record_version IN (1,2) AND r.payload=?",
                    operation,
                    type,
                    payload))) throw invalid("Prime immutable fact linkage");
    return Long.parseLong(seq);
  }

  private static String scalar(Connection c, String sql, String... params) throws SQLException {
    try (var q = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) q.setString(i + 1, params[i]);
      try (var rows = q.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  private static String norm(String s) {
    return s.replaceAll("\\s+", " ").trim();
  }

  private static SQLException invalid(String s) {
    return new SQLException(s);
  }
}
