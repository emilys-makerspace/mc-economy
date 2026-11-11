package ca.emilybelnavis.mcmods.economy.simulation;

import ca.emilybelnavis.mcmods.economy.api.EconomicSimulationHistory;
import ca.emilybelnavis.mcmods.economy.simulation.macro.MacroPlans;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.util.*;

/** Worker-only saved selection reader. Does not observe time, advance work or publish rates. */
public final class SimulationHistory {
  private SimulationHistory() {}

  public static EconomicSimulationHistory.Page page(
      FoundationStore store, long beforeDay, int limit) {
    Objects.requireNonNull(store);
    if (beforeDay < 0 || limit < 1 || limit > 16)
      throw new IllegalArgumentException("History cursor/limit");
    var world = store.snapshot().worldId();
    return store.transaction(
        tx -> {
          var rows =
              tx.query(
                  "SELECT w.day_index,w.boundary_ms,p.payload,r.record_version FROM macro_window_opens w "
                      + "JOIN macro_rate_plans p ON p.window_id=w.id "
                      + "JOIN journal_transactions t ON t.world_id=p.world_id AND t.operation_id=p.operation_id "
                      + "JOIN journal_records r ON r.sequence=t.sequence AND r.record_type=? AND r.payload=p.payload "
                      + "WHERE p.world_id=? AND w.day_index<? ORDER BY w.day_index DESC LIMIT ?",
                  List.of(
                      MacroPlans.FACT,
                      world.value().toString(),
                      Long.toString(beforeDay),
                      Integer.toString(limit + 1)),
                  limit + 1);
          var entries = new ArrayList<EconomicSimulationHistory.Entry>();
          for (var row : rows.subList(0, Math.min(rows.size(), limit))) {
            var saved = MacroPlans.decode(row.get("payload"));
            if (!saved.world().equals(world))
              throw new IllegalArgumentException("Saved selection world mismatch");
            entries.add(
                new EconomicSimulationHistory.Entry(
                    Integer.parseInt(row.get("record_version")),
                    Long.parseLong(row.get("day_index")),
                    Long.parseLong(row.get("boundary_ms")),
                    saved.window(),
                    saved.world(),
                    saved.policyId(),
                    saved.policyRevision(),
                    EconomicSimulationHistory.Mode.valueOf(saved.mode().name()),
                    saved.inputHash(),
                    saved.automaticHash(),
                    saved.primes(),
                    saved.fx(),
                    new EconomicSimulationHistory.Ratio(
                        saved.alpha().numerator(), saved.alpha().denominator()),
                    saved.explanations(),
                    saved.hash()));
          }
          return new EconomicSimulationHistory.Page(
              entries,
              rows.size() > limit
                  ? OptionalLong.of(entries.getLast().financialDay())
                  : OptionalLong.empty());
        });
  }
}
