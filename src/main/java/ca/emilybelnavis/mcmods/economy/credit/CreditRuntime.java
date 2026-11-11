package ca.emilybelnavis.mcmods.economy.credit;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Two bounded domain providers, independent of the dormant credit reporting/score schedule. */
public final class CreditRuntime {
  private CreditRuntime() {}

  public static List<DeadlineDispatcher.Provider> providers() {
    return List.of(provider(true), provider(false));
  }

  private static DeadlineDispatcher.Provider provider(boolean daily) {
    String column = daily ? "next_day" : "next_statement";
    return new DeadlineDispatcher.Provider() {
      public String id() {
        return daily ? "economy:a6/credit-daily" : "economy:a6/credit-statements";
      }

      public Map<Integer, String> versions() {
        return Map.of(1, daily ? "credit-anchor-daily-v1" : "credit-anchor-statements-v1");
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
                    + column
                    + " AS due FROM credit_facilities WHERE status='ACTIVE' ORDER BY "
                    + column
                    + ",facility_id LIMIT 1",
                List.of(),
                1);
        if (rows.isEmpty()) return null;
        long due = Long.parseLong(rows.getFirst().get("due"));
        if (completed != null && due <= completed.effectiveMillis())
          throw new IllegalStateException("Credit deadline failed to advance");
        var world = world(reads);
        var event =
            UUID.nameUUIDFromBytes(
                ("economy:credit-deadline:v1|" + world.value() + "|" + id() + "|" + due)
                    .getBytes(StandardCharsets.UTF_8));
        return new DeadlineDispatcher.Event(event, id(), due, daily ? 1 : 2, 1);
      }

      public List<String> targets(
          DeadlineDispatcher.Event event, String cursor, int limit, EconomicClock.Queries reads) {
        return reads
            .query(
                "SELECT facility_id FROM credit_facilities WHERE status='ACTIVE' AND "
                    + column
                    + "=? AND facility_id>? ORDER BY facility_id LIMIT ?",
                List.of(Long.toString(event.effectiveMillis()), cursor, Integer.toString(limit)),
                limit)
            .stream()
            .map(r -> r.get("facility_id"))
            .toList();
      }

      public EconomicClock.Output prepare(
          DeadlineDispatcher.Event event, String target, EconomicClock.Queries queries) {
        JournalEngine.ReadView reads = queries::query;
        try {
          var decision =
              daily
                  ? CreditFacilities.daily(reads, UUID.fromString(target), event.effectiveMillis())
                  : CreditFacilities.statement(
                      reads, UUID.fromString(target), event.effectiveMillis());
          if (!(decision instanceof JournalEngine.Commit commit))
            throw new IllegalStateException("Credit scheduled preparation rejected");
          return new EconomicClock.Output(target, commit.projections(), commit.records());
        } catch (RuntimeException failure) {
          throw failure;
        } catch (Exception failure) {
          throw new IllegalStateException("Credit scheduled processing failed", failure);
        }
      }
    };
  }

  private static WorldId world(EconomicClock.Queries reads) {
    return new WorldId(
        UUID.fromString(
            reads
                .query("SELECT world_id FROM foundation_metadata", List.of(), 1)
                .getFirst()
                .get("world_id")));
  }
}
