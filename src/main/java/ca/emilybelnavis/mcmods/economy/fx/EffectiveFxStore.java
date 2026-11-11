package ca.emilybelnavis.mcmods.economy.fx;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.MacroModel;
import ca.emilybelnavis.mcmods.economy.simulation.plan.ActivationModelPlans;
import ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes;
import ca.emilybelnavis.mcmods.economy.simulation.window.WindowStore;
import java.util.*;

/** Detached stage-0 child preparation only. No provider is registered or activated here. */
public final class EffectiveFxStore {
  private EffectiveFxStore() {}

  public static JournalEngine.Commit prepare(
      JournalEngine.ReadView reads,
      WorldId world,
      UUID actualChildOperation,
      String provider,
      UUID event,
      UUID window,
      EffectiveFxSnapshot.Mode mode) {
    var selected = ca.emilybelnavis.mcmods.economy.simulation.macro.MacroPlans.read(reads, window);
    if (!provider.equals(EffectiveFxFacts.PROVIDER)
        || selected == null
            && mode != EffectiveFxSnapshot.Mode.AUTO
            && mode != EffectiveFxSnapshot.Mode.PAUSED)
      throw new IllegalArgumentException("Installed automatic/paused FX model required");
    var a =
        CurrencyPrimes.activation(reads, world)
            .orElseThrow(() -> new IllegalArgumentException("FX activation source missing"));
    var open = WindowStore.readOpen(reads, window);
    var plan = ActivationModelPlans.read(reads, window);
    if (open == null
        || plan == null
        || !a.id().equals(ActivationModelPlans.activation(reads, window))
        || !open.world().equals(world)
        || open.boundaryMillis() < a.firstEventMillis()
        || (open.boundaryMillis() - a.firstEventMillis()) % CurrencyPrimes.DAY_MILLIS != 0
        || !open.config().equals(plan.input().config().id())
        || !plan.input().anchors().equals(a.anchors())
        || !plan.output().available()
        || !plan.output().equals(MacroModel.compute(plan.input())))
      throw new IllegalArgumentException("FX frozen event source unavailable");
    long effective = open.boundaryMillis();
    var retirement =
        ca.emilybelnavis.mcmods.economy.clock.FixedRetirements.read(reads, FxService.SYSTEM);
    if (retirement != null
        && effective == a.firstEventMillis()
        && (retirement.takeoverMillis() != effective
            || !retirement.successor().equals(provider)
            || !retirement.successorEvent().equals(event)))
      throw new IllegalArgumentException("FX publication differs from reserved successor");
    var prior = FxService.at(reads, effective - 1);
    if (prior == null
        || !prior.world().equals(world)
        || prior.reference() != a.reference()
        || !prior.rates().equals(plan.input().priorFx()))
      throw new IllegalArgumentException("FX prior effective vector differs from frozen model");
    if (prior.mode() == EffectiveFxSnapshot.Mode.LEGACY_DAILY
        ? effective != a.firstEventMillis()
        : prior.effectiveMillis() != effective - CurrencyPrimes.DAY_MILLIS
            || !prior.policy().equals(a.id()))
      throw new IllegalArgumentException("FX event sequence gap");
    var existing =
        reads.query(
            "SELECT id FROM effective_fx_snapshots WHERE effective_ms>=? ORDER BY effective_ms LIMIT 1",
            List.of(Long.toString(effective)),
            1);
    if (!existing.isEmpty())
      throw new IllegalArgumentException("FX publication boundary already occupied/passed");
    if (selected != null
        && (!selected.world().equals(world)
            || !selected.mode().name().equals(mode.name())
            || !selected.automaticHash().equals(plan.output().hash())))
      throw new IllegalArgumentException("FX selected outcome mismatch");
    var snapshot =
        new EffectiveFxSnapshot(
            EffectiveFxFacts.id(world, provider, event, a.id(), window),
            world,
            effective,
            provider,
            event,
            a.id(),
            plan.input().config().id(),
            window,
            2,
            1,
            mode,
            a.reference(),
            selected != null
                ? selected.fx()
                : mode == EffectiveFxSnapshot.Mode.PAUSED ? prior.rates() : plan.output().fx());
    if (FxService.snapshot(reads, snapshot.id()) != null)
      throw new IllegalArgumentException("FX snapshot identity already exists");
    String payload = EffectiveFxFacts.encode(snapshot);
    return new JournalEngine.Commit(
        List.of(new Journal.Record(EffectiveFxFacts.FACT, selected == null ? 1 : 2, payload)),
        new Journal.Result(1, "EFFECTIVE_FX_PUBLISHED", snapshot.id().toString()),
        tx -> {
          var seq =
              tx.query(
                  "SELECT sequence FROM journal_transactions WHERE world_id=? AND operation_id=?",
                  List.of(world.value().toString(), actualChildOperation.toString()),
                  1);
          if (seq.size() != 1)
            throw new IllegalStateException("FX child source transaction missing");
          tx.execute(
              "INSERT INTO effective_fx_snapshots VALUES(?,?,?,?,?,?,?,?,?)",
              List.of(
                  snapshot.id().toString(),
                  world.value().toString(),
                  Long.toString(effective),
                  provider,
                  event.toString(),
                  window.toString(),
                  actualChildOperation.toString(),
                  seq.getFirst().get("sequence"),
                  payload));
        });
  }
}
