package ca.emilybelnavis.mcmods.economy.api;

import ca.emilybelnavis.mcmods.economy.foundation.PartyAuthorization;
import ca.emilybelnavis.mcmods.economy.simulation.SimulationDiagnostics;
import ca.emilybelnavis.mcmods.economy.simulation.macro.MacroPlans;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** Immutable server-only reads; grants are authenticated by the current ServerServices session. */
public interface EconomicSimulationIntegration {
  int VERSION = 1;

  /** Cached snapshot, possibly unavailable; reading never observes time or authorizes money. */
  Optional<SimulationDiagnostics.Snapshot> snapshot(PartyAuthorization.Grant grant);

  /** Exclusive financial-day cursor, at most16 saved outcomes. No SQL/callback injection. */
  CompletionStage<List<MacroPlans.Outcome>> history(
      PartyAuthorization.Grant grant, long beforeDay, int limit);

  default int version() {
    return VERSION;
  }
}
