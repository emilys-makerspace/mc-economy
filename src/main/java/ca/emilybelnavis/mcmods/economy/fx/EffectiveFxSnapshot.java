package ca.emilybelnavis.mcmods.economy.fx;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import java.math.*;
import java.time.*;
import java.util.*;

/**
 * Exact effective-time identity. publication() is a mathematical/display view, never a legacy fact.
 */
public record EffectiveFxSnapshot(
    UUID id,
    WorldId world,
    long effectiveMillis,
    String provider,
    UUID event,
    UUID policy,
    UUID config,
    UUID window,
    int membershipVersion,
    int modelVersion,
    Mode mode,
    EconomyCurrency reference,
    Map<EconomyCurrency, BigDecimal> rates) {
  public enum Mode {
    LEGACY_DAILY,
    AUTO,
    PAUSED,
    MANUAL
  }

  public EffectiveFxSnapshot {
    Objects.requireNonNull(id);
    Objects.requireNonNull(world);
    Objects.requireNonNull(event);
    Objects.requireNonNull(policy);
    Objects.requireNonNull(config);
    Objects.requireNonNull(window);
    Objects.requireNonNull(mode);
    Objects.requireNonNull(reference);
    rates = canonicalRates(rates);
    if (effectiveMillis < 0
        || provider == null
        || !provider.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
        || membershipVersion < 1
        || membershipVersion > 2
        || modelVersion < 0
        || !rates.containsKey(reference)
        || rates.get(reference).compareTo(BigDecimal.ONE) != 0
        || rates.values().stream()
            .anyMatch(
                v ->
                    v == null
                        || v.signum() <= 0
                        || v.scale() != 12
                        || v.compareTo(new BigDecimal("1000000")) > 0))
      throw new IllegalArgumentException("Effective FX snapshot");
    var expected =
        membershipVersion == 2
            ? new HashSet<>(EconomyCurrency.all())
            : EnumSet.complementOf(EnumSet.of(EconomyCurrency.CHF));
    if (!rates.keySet().equals(expected))
      throw new IllegalArgumentException("Effective FX membership");
    if (mode == Mode.LEGACY_DAILY) {
      if (!provider.equals(FxService.SYSTEM)
          || modelVersion != 0
          || effectiveMillis % 86_400_000L != 0
          || !event.equals(id)
          || !window.equals(id)
          || !config.equals(policy)) throw new IllegalArgumentException("Legacy FX adaptation");
    } else if (!provider.equals(EffectiveFxFacts.PROVIDER)
        || membershipVersion != 2
        || modelVersion != 1
        || !EffectiveFxFacts.id(world, provider, event, policy, window).equals(id))
      throw new IllegalArgumentException("Intraday FX identity");
  }

  /** Representation only: no numerical rounding or change to frozen model output. */
  public static Map<EconomyCurrency, BigDecimal> canonicalRates(
      Map<EconomyCurrency, BigDecimal> rates) {
    var result = new TreeMap<EconomyCurrency, BigDecimal>();
    for (var entry : rates.entrySet())
      result.put(
          entry.getKey(),
          Objects.requireNonNull(entry.getValue()).setScale(12, RoundingMode.UNNECESSARY));
    return Collections.unmodifiableMap(result);
  }

  public FxFacts.Publication publication() {
    return new FxFacts.Publication(
        LocalDate.ofInstant(Instant.ofEpochMilli(effectiveMillis), ZoneOffset.UTC),
        reference,
        id,
        policy,
        rates);
  }

  public static EffectiveFxSnapshot legacy(WorldId world, FxFacts.Publication value) {
    return new EffectiveFxSnapshot(
        value.updateId(),
        world,
        value.day().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        FxService.SYSTEM,
        value.updateId(),
        value.policyId(),
        value.policyId(),
        value.updateId(),
        value.version(),
        0,
        Mode.LEGACY_DAILY,
        value.reference(),
        value.rates());
  }
}
