package ca.emilybelnavis.mcmods.economy.simulation;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.simulation.macro.*;
import ca.emilybelnavis.mcmods.economy.simulation.plan.ActivationModelPlans;
import ca.emilybelnavis.mcmods.economy.simulation.prime.*;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.util.*;

/** Bounded read-only snapshots. No time observation, scheduler advance or source registration. */
public final class SimulationDiagnostics {
  private SimulationDiagnostics() {}

  public record Snapshot(
      long sampledMillis,
      boolean ready,
      String reason,
      String mode,
      int coverageDays,
      int baselineDays,
      long fxAgeMillis,
      long activationMillis,
      long firstEffectiveMillis,
      long handoverMillis,
      UUID config,
      long policyRevision,
      List<CurrencyPrimes.Policy> primes,
      EffectiveFxSnapshot fx,
      String sources,
      List<String> limits) {
    public Snapshot {
      primes = List.copyOf(primes);
      limits = List.copyOf(limits);
      Objects.requireNonNull(reason);
      Objects.requireNonNull(mode);
      Objects.requireNonNull(sources);
    }
  }

  public static Snapshot read(FoundationStore store, long now, String lifecycle) {
    return store.transaction(
        tx -> {
          var world = store.snapshot().worldId();
          var activation = MacroActivation.read(tx::query, world);
          var fx = FxService.snapshot(tx::query);
          var policy = MacroControls.find(tx::query, world, now);
          var primes = new ArrayList<CurrencyPrimes.Policy>();
          for (var c : EconomyCurrency.all())
            CurrencyPrimes.current(tx::query, world, c, now).ifPresent(primes::add);
          var latest =
              tx.query(
                  "SELECT p.window_id FROM macro_window_opens w JOIN macro_rate_plans p ON p.window_id=w.id WHERE w.boundary_ms<=? ORDER BY w.day_index DESC LIMIT 1",
                  List.of(Long.toString(now)),
                  1);
          int coverage = 0, baseline = 0;
          String mode = policy.map(p -> p.mode().name()).orElse("UNAVAILABLE");
          if (!latest.isEmpty()) {
            var id = UUID.fromString(latest.getFirst().get("window_id"));
            var plan = ActivationModelPlans.read(tx::query, id);
            var selected = MacroPlans.read(tx::query, id);
            if (plan != null) {
              var w = plan.input().windows().get(EconomyCurrency.CAD);
              coverage = w.days();
              baseline = w.baselineDays();
            }
            if (selected != null) mode = selected.mode().name();
          }
          long age = fx == null ? -1 : Math.max(0, now - fx.effectiveMillis());
          String reason =
              activation.isEmpty()
                  ? lifecycle
                  : now < activation.get().firstEffectiveMillis()
                      ? "WARMUP_COMPLETE_DAY_REQUIRED"
                      : !MacroActivation.ready(tx::query, world, now)
                          ? "RATE_EVENT_INCOMPLETE"
                          : fx == null
                              ? "FX_UNAVAILABLE"
                              : now < fx.effectiveMillis()
                                  ? "WAITING_FOR_WALL_CLOCK"
                                  : age >= 172800000 ? "FX_STALE" : "AVAILABLE";
          var a = activation.orElse(null);
          return new Snapshot(
              now,
              reason.equals("AVAILABLE"),
              reason,
              mode,
              coverage,
              baseline,
              age,
              a == null ? -1 : a.activationMillis(),
              a == null ? -1 : a.firstEffectiveMillis(),
              a == null ? -1 : a.handoverMillis(),
              policy.map(p -> p.config().id()).orElse(null),
              policy.map(MacroControls.Policy::revision).orElse(0L),
              primes,
              fx,
              ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityFacts.SOURCE_MANIFEST,
              List.of(
                  "financialDayMillis=1200000",
                  "windowDays<=7; baselineDays<=7",
                  "currencies=11; eventChildren=26+completion",
                  "pendingPolicies<=16; historyPage<=16",
                  "prime=0..25%; step<=0.10 percentage points",
                  "FX ordered-pair step<=0.5%; anchor=50..200%"));
        });
  }

  public static List<MacroPlans.Outcome> history(FoundationStore store, long beforeDay, int limit) {
    if (beforeDay < 0 || limit < 1 || limit > 16)
      throw new IllegalArgumentException("History cursor/limit");
    return store.transaction(
        tx ->
            tx
                .query(
                    "SELECT p.payload FROM macro_window_opens w JOIN macro_rate_plans p ON p.window_id=w.id WHERE w.day_index<? ORDER BY w.day_index DESC LIMIT ?",
                    List.of(Long.toString(beforeDay), Integer.toString(limit)),
                    limit)
                .stream()
                .map(r -> MacroPlans.decode(r.get("payload")))
                .toList());
  }
}
