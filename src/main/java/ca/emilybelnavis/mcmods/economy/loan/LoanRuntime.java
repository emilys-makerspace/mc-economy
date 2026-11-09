package ca.emilybelnavis.mcmods.economy.loan;

import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * One bounded provider for monthly loan closes; autopay/default/reporting remain separate domains.
 */
public final class LoanRuntime {
  public static final String PROVIDER = "economy:a6/loan-monthly";

  private LoanRuntime() {}

  public static DeadlineDispatcher.Provider provider() {
    return new DeadlineDispatcher.Provider() {
      public String id() {
        return PROVIDER;
      }

      public Map<Integer, String> versions() {
        return Map.of(1, "loan-anchor-monthly-v1-fixed-segmented");
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
                "SELECT next_month FROM loans WHERE status='ACTIVE' ORDER BY next_month,loan_id LIMIT 1",
                List.of(),
                1);
        if (rows.isEmpty()) return null;
        long due = Long.parseLong(rows.getFirst().get("next_month"));
        if (completed != null && due <= completed.effectiveMillis())
          throw new IllegalStateException("Loan month failed to advance");
        var world =
            reads
                .query("SELECT world_id FROM foundation_metadata", List.of(), 1)
                .getFirst()
                .get("world_id");
        var id =
            UUID.nameUUIDFromBytes(
                ("economy:loan-month:v1|" + world + "|" + due).getBytes(StandardCharsets.UTF_8));
        return new DeadlineDispatcher.Event(id, id(), due, 1, 1);
      }

      public List<String> targets(
          DeadlineDispatcher.Event event, String after, int limit, EconomicClock.Queries reads) {
        return reads
            .query(
                "SELECT loan_id FROM loans WHERE status='ACTIVE' AND next_month=? AND loan_id>? ORDER BY loan_id LIMIT ?",
                List.of(Long.toString(event.effectiveMillis()), after, Integer.toString(limit)),
                limit)
            .stream()
            .map(r -> r.get("loan_id"))
            .toList();
      }

      public EconomicClock.Output prepare(
          DeadlineDispatcher.Event event, String target, EconomicClock.Queries queries) {
        try {
          var decision =
              Loans.month(queries::query, UUID.fromString(target), event.effectiveMillis());
          if (!(decision instanceof JournalEngine.Commit commit))
            throw new IllegalStateException("Loan month preparation rejected");
          return new EconomicClock.Output(target, commit.projections(), commit.records());
        } catch (RuntimeException failure) {
          throw failure;
        } catch (Exception failure) {
          throw new IllegalStateException("Loan month processing failed", failure);
        }
      }
    };
  }
}
