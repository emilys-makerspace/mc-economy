package ca.emilybelnavis.mcmods.economy.overdraft;

import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One indexed, resumable account per financial dispatcher child transaction. */
final class OverdraftRuntime implements DeadlineDispatcher.Provider {
  static final String DUE =
      "MIN(next_day,next_month,CASE WHEN limit_minor>0 AND episode_at>=0 THEN CASE WHEN positive_at>=0 THEN positive_at+1200000 ELSE episode_at+108000000 END ELSE 9223372036854775807 END)";

  public String id() {
    return "economy:overdraft/daily";
  }

  public Map<Integer, String> versions() {
    return Map.of(1, "overdraft-daily-v1");
  }

  public DeadlineDispatcher.Event next(
      EconomicClock.Queries reads, long origin, DeadlineDispatcher.Event completed) {
    var active =
        reads.query(
            "SELECT event_id,effective_ms,stage,version FROM financial_deadline_events WHERE provider=? AND state='APPLYING' ORDER BY effective_ms LIMIT 1",
            List.of(id()),
            1);
    if (!active.isEmpty()) {
      var r = active.getFirst();
      return new DeadlineDispatcher.Event(
          UUID.fromString(r.get("event_id")),
          id(),
          Long.parseLong(r.get("effective_ms")),
          Integer.parseInt(r.get("stage")),
          Integer.parseInt(r.get("version")));
    }
    var rows =
        reads.query(
            "SELECT "
                + DUE
                + " AS due FROM overdraft_accounts WHERE closed=0 ORDER BY "
                + DUE
                + ",account_id LIMIT 1",
            List.of(),
            1);
    if (rows.isEmpty()) return null;
    long due = Long.parseLong(rows.getFirst().get("due"));
    if (completed != null && due <= completed.effectiveMillis())
      throw new IllegalStateException("Overdraft deadline did not advance");
    var world =
        reads
            .query("SELECT world_id FROM foundation_metadata", List.of(), 1)
            .getFirst()
            .get("world_id");
    UUID event =
        UUID.nameUUIDFromBytes(
            (id() + "|v1|" + world + "|" + due).getBytes(StandardCharsets.UTF_8));
    return new DeadlineDispatcher.Event(
        event, id(), due, FinancialCalendar.Stage.ACCRUAL.ordinal(), 1);
  }

  public List<String> targets(
      DeadlineDispatcher.Event event, String cursor, int limit, EconomicClock.Queries reads) {
    return reads
        .query(
            "SELECT account_id FROM overdraft_accounts WHERE closed=0 AND "
                + DUE
                + "=? AND account_id>? ORDER BY account_id LIMIT ?",
            List.of(Long.toString(event.effectiveMillis()), cursor, Integer.toString(limit)),
            limit)
        .stream()
        .map(r -> r.get("account_id"))
        .toList();
  }

  public EconomicClock.Output prepare(
      DeadlineDispatcher.Event event, String target, EconomicClock.Queries queries) {
    try {
      var decision =
          Overdrafts.daily(queries::query, UUID.fromString(target), event.effectiveMillis());
      if (!(decision instanceof JournalEngine.Commit commit))
        throw new IllegalStateException("Overdraft servicing rejected: " + decision);
      return new EconomicClock.Output(target, commit.projections(), commit.records());
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException("Overdraft servicing failed", failure);
    }
  }
}
