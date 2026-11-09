package ca.emilybelnavis.mcmods.economy.debt;

import static ca.emilybelnavis.mcmods.economy.debt.DebtRecords.*;

import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.util.*;

/**
 * Interest and statements precede stage4 transitions, stage5 reports and the later score stage6.
 */
public final class DebtRuntime {
  private DebtRuntime() {}

  public static List<DeadlineDispatcher.Provider> providers() {
    return List.of(
        provider("default"),
        provider("due"),
        provider("expiry"),
        provider("sale"),
        provider("report"));
  }

  private static DeadlineDispatcher.Provider provider(String mode) {
    return new DeadlineDispatcher.Provider() {
      public String id() {
        return "economy:a6/debt-" + mode;
      }

      public Map<Integer, String> versions() {
        return Map.of(1, "debt-" + mode + "-v1-original-clocks");
      }

      public DeadlineDispatcher.Event next(
          EconomicClock.Queries reads, long origin, DeadlineDispatcher.Event completed) {
        var applying =
            reads.query(
                "SELECT event_id,effective_ms,stage,version FROM financial_deadline_events WHERE provider=? AND state='APPLYING' ORDER BY effective_ms LIMIT 1",
                List.of(id()),
                1);
        if (!applying.isEmpty()) {
          var r = applying.getFirst();
          return new DeadlineDispatcher.Event(
              UUID.fromString(r.get("event_id")),
              id(),
              Long.parseLong(r.get("effective_ms")),
              Integer.parseInt(r.get("stage")),
              Integer.parseInt(r.get("version")));
        }
        var rows = reads.query(headSql(mode), List.of(), 1);
        if (rows.isEmpty()) return null;
        long at = Long.parseLong(rows.getFirst().get("due"));
        if (completed != null && at <= completed.effectiveMillis())
          throw new IllegalStateException("Debt deadline failed to advance");
        return new DeadlineDispatcher.Event(
            DebtLifecycle.stable(id() + "|" + at), id(), at, mode.equals("report") ? 5 : 4, 1);
      }

      public List<String> targets(
          DeadlineDispatcher.Event event, String after, int limit, EconomicClock.Queries reads) {
        String at = Long.toString(event.effectiveMillis());
        var cursor = after.isEmpty() ? new String[] {"", ""} : after.split("/", 2);
        if (mode.equals("report"))
          return reads
              .query(
                  "SELECT kind||'/'||obligation_id AS target FROM debt_obligations WHERE reported=0 AND due_at=? AND (kind,obligation_id)>(?,?) ORDER BY kind,obligation_id LIMIT ?",
                  List.of(
                      Long.toString(event.effectiveMillis() - 2 * DAY),
                      cursor[0],
                      cursor[1],
                      Integer.toString(limit)),
                  limit)
              .stream()
              .map(r -> r.get("target"))
              .toList();
        if (mode.equals("expiry"))
          return reads
              .query(
                  "SELECT case_id AS target FROM debt_cases WHERE collectability='COLLECTIBLE' AND payment<>'SETTLED' AND collection_expires=? AND case_id>? ORDER BY case_id LIMIT ?",
                  List.of(at, after, Integer.toString(limit)),
                  limit)
              .stream()
              .map(r -> r.get("target"))
              .toList();
        String table =
            mode.equals("due")
                ? "debt_due_queue"
                : mode.equals("default") ? "debt_default_heads" : "debt_sale_attempts";
        String key =
            mode.equals("due")
                ? "kind||'/'||obligation_id"
                : mode.equals("default") ? "kind||'/'||facility_id" : "case_id";
        String order =
            mode.equals("due")
                ? "kind,obligation_id"
                : mode.equals("default") ? "kind,facility_id" : "case_id";
        String predicate =
            mode.equals("due")
                ? "(kind,obligation_id)>(?,?)"
                : mode.equals("default") ? "(kind,facility_id)>(?,?)" : "case_id>?";
        var parameters =
            mode.equals("sale")
                ? List.of(at, after, Integer.toString(limit))
                : List.of(at, cursor[0], cursor[1], Integer.toString(limit));
        return reads
            .query(
                "SELECT "
                    + key
                    + " AS target FROM "
                    + table
                    + " WHERE next_at=? AND "
                    + predicate
                    + " ORDER BY "
                    + order
                    + " LIMIT ?",
                parameters,
                limit)
            .stream()
            .map(r -> r.get("target"))
            .toList();
      }

      public EconomicClock.Output prepare(
          DeadlineDispatcher.Event event, String target, EconomicClock.Queries queries) {
        JournalEngine.ReadView reads = queries::query;
        JournalEngine.Decision decision;
        if (mode.equals("sale"))
          decision = DebtLifecycle.sell(reads, UUID.fromString(target), event.effectiveMillis());
        else if (mode.equals("expiry"))
          decision = DebtLifecycle.expire(reads, UUID.fromString(target), event.effectiveMillis());
        else {
          var p = target.split("/", 2);
          var kind = Kind.valueOf(p[0]);
          var id = UUID.fromString(p[1]);
          decision =
              mode.equals("report")
                  ? DebtLifecycle.reportDue(reads, kind, id, event.effectiveMillis())
                  : mode.equals("default")
                      ? DebtLifecycle.defaultDue(reads, kind, id, event.effectiveMillis())
                      : DebtLifecycle.delinquent(reads, kind, id, event.effectiveMillis());
        }
        if (!(decision instanceof JournalEngine.Commit commit))
          throw new IllegalStateException("Debt scheduled transition rejected");
        return new EconomicClock.Output(target, commit.projections(), commit.records());
      }
    };
  }

  private static String headSql(String mode) {
    return switch (mode) {
      case "due" ->
          "SELECT next_at AS due FROM debt_due_queue ORDER BY next_at,kind,obligation_id LIMIT 1";
      case "default" ->
          "SELECT next_at AS due FROM debt_default_heads ORDER BY next_at,kind,facility_id LIMIT 1";
      case "sale" ->
          "SELECT next_at AS due FROM debt_sale_attempts ORDER BY next_at,case_id LIMIT 1";
      case "expiry" ->
          "SELECT collection_expires AS due FROM debt_cases WHERE collectability='COLLECTIBLE' AND payment<>'SETTLED' ORDER BY collection_expires,case_id LIMIT 1";
      default ->
          "SELECT due_at+2400000 AS due FROM debt_obligations WHERE reported=0 ORDER BY due_at,kind,obligation_id LIMIT 1";
    };
  }
}
