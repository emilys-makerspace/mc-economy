package ca.emilybelnavis.mcmods.economy.simulation;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.simulation.macro.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.MacroModel;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import com.google.gson.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Immutable operator request; only existing prospective policy admission may accept it. */
public final class MacroPolicyRequests {
  private MacroPolicyRequests() {}

  public record Request(
      UUID id,
      long effectiveMillis,
      MacroControls.Mode mode,
      Map<EconomyCurrency, BigDecimal> primeDeltas,
      Map<EconomyCurrency, BigDecimal> fxMultipliers,
      MacroModel.Config config) {
    public Request {
      Objects.requireNonNull(id);
      Objects.requireNonNull(mode);
      primeDeltas = Map.copyOf(primeDeltas);
      fxMultipliers = Map.copyOf(fxMultipliers);
      if (effectiveMillis < 0) throw new IllegalArgumentException("Future timestamp required");
    }
  }

  public static Request mode(long time, MacroControls.Mode mode) {
    if (mode == MacroControls.Mode.MANUAL)
      throw new IllegalArgumentException("Manual vectors required");
    return new Request(UUID.randomUUID(), time, mode, Map.of(), Map.of(), null);
  }

  public static Request manual(long time, String json) {
    if (json.length() > 4096) throw new IllegalArgumentException("Manual vector limit4096");
    var value = JsonParser.parseString(json).getAsJsonObject();
    if (!value.keySet().equals(Set.of("primeDeltas", "fxMultipliers")))
      throw new IllegalArgumentException(
          "Expected primeDeltas and fxMultipliers full currency maps");
    return new Request(
        UUID.randomUUID(),
        time,
        MacroControls.Mode.MANUAL,
        vector(value.getAsJsonObject("primeDeltas")),
        vector(value.getAsJsonObject("fxMultipliers")),
        null);
  }

  /**
   * A configuration edit explicitly schedules a pause; resume remains an explicit future policy.
   */
  public static Request configuration(long time, String json) {
    if (json.length() > 2048) throw new IllegalArgumentException("Configuration limit2048");
    var value = JsonParser.parseString(json).getAsJsonObject();
    if (!Set.of("dailyFloor", "capacityFactor", "primeWeights", "fxWeights").equals(value.keySet()))
      throw new IllegalArgumentException("Unknown model setting");
    var properties = new Properties();
    for (var e : value.entrySet()) properties.setProperty(e.getKey(), e.getValue().getAsString());
    return new Request(
        UUID.randomUUID(),
        time,
        MacroControls.Mode.PAUSED,
        Map.of(),
        Map.of(),
        SimulationConfig.parse(properties).model());
  }

  private static Map<EconomyCurrency, BigDecimal> vector(JsonObject object) {
    if (object.size() != 11) throw new IllegalArgumentException("Exactly11 currencies required");
    var result = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
    for (var entry : object.entrySet()) {
      String value = entry.getValue().getAsString();
      SimulationConfig.number(value);
      result.put(EconomyCurrency.fromCode(entry.getKey()), new BigDecimal(value));
    }
    if (result.size() != 11) throw new IllegalArgumentException("Currency manifest");
    return Map.copyOf(result);
  }

  public static MacroControls.Policy schedule(
      FoundationStore store,
      Request request,
      Instant now,
      ca.emilybelnavis.mcmods.economy.api.Identifiers.ActorId actor) {
    var world = store.snapshot().worldId();
    var existing =
        store.transaction(
            tx ->
                tx.query(
                    "SELECT payload FROM macro_control_policies WHERE id=? AND world_id=?",
                    List.of(request.id().toString(), world.value().toString()),
                    1));
    if (!existing.isEmpty()) {
      var captured = MacroFacts.decodePolicy(existing.getFirst().get("payload"));
      if (captured.effectiveMillis() != request.effectiveMillis()
          || captured.mode() != request.mode()
          || !captured.primeDeltas().equals(request.primeDeltas())
          || !captured.fxMultipliers().equals(request.fxMultipliers())
          || request.config() != null && !request.config().equals(captured.config()))
        throw new IllegalArgumentException("Original macro request differs");
      return captured;
    }
    var prior =
        store.transaction(
            tx -> {
              var rows =
                  tx.query(
                      "SELECT payload FROM macro_control_policies WHERE world_id=? ORDER BY revision DESC LIMIT 1",
                      List.of(world.value().toString()),
                      1);
              if (rows.isEmpty())
                throw new IllegalArgumentException("Rate activation is not installed");
              return MacroFacts.decodePolicy(rows.getFirst().get("payload"));
            });
    var policy =
        new MacroControls.Policy(
            request.id(),
            world,
            Math.addExact(prior.revision(), 1),
            request.effectiveMillis(),
            request.mode(),
            request.config() == null ? prior.config() : request.config(),
            request.primeDeltas(),
            request.fxMultipliers());
    return MacroControls.schedule(store, policy, now, actor);
  }
}
