package ca.emilybelnavis.mcmods.economy.api;

import ca.emilybelnavis.mcmods.economy.foundation.PartyAuthorization;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CompletionStage;

/**
 * Versioned, server-only saved selections. Entries do not assert publication or basket readiness.
 */
public interface EconomicSimulationHistory {
  int VERSION = 1;

  // Integration mods rely on both VERSION and version().
  @SuppressWarnings("java:S1845")
  default int version() {
    return VERSION;
  }

  /**
   * Exclusive day cursor; denied reads return empty, while an authorized empty history has a Page.
   */
  CompletionStage<Optional<Page>> page(PartyAuthorization.Grant grant, long beforeDay, int limit);

  enum Mode {
    AUTO,
    PAUSED,
    MANUAL
  }

  /** Exact captured ratio, without decimal conversion or normalization. */
  record Ratio(BigInteger numerator, BigInteger denominator) {
    public Ratio {
      Objects.requireNonNull(numerator);
      Objects.requireNonNull(denominator);
      if (denominator.signum() <= 0)
        throw new IllegalArgumentException("Positive ratio denominator required");
    }
  }

  record Entry(
      int recordVersion,
      long financialDay,
      long effectiveMillis,
      UUID window,
      Identifiers.WorldId world,
      UUID policyId,
      long policyRevision,
      Mode mode,
      String inputHash,
      String automaticHash,
      Map<EconomyCurrency, BigDecimal> primes,
      Map<EconomyCurrency, BigDecimal> fx,
      Ratio alpha,
      List<String> explanations,
      String hash) {
    public Entry {
      if ((recordVersion != 1 && recordVersion != 2)
          || financialDay < 0
          || effectiveMillis < 0
          || policyRevision < 1) throw new IllegalArgumentException("Saved selection metadata");
      Objects.requireNonNull(window);
      Objects.requireNonNull(world);
      Objects.requireNonNull(policyId);
      Objects.requireNonNull(mode);
      Objects.requireNonNull(inputHash);
      Objects.requireNonNull(automaticHash);
      Objects.requireNonNull(alpha);
      Objects.requireNonNull(hash);
      primes = currencies(primes);
      fx = currencies(fx);
      explanations = List.copyOf(explanations);
      if (explanations.size() > 32) throw new IllegalArgumentException("Explanation bound");
    }

    private static Map<EconomyCurrency, BigDecimal> currencies(
        Map<EconomyCurrency, BigDecimal> values) {
      Objects.requireNonNull(values);
      if (values.size() > 11) throw new IllegalArgumentException("Currency bound");
      var copy = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
      values.forEach(
          (currency, value) ->
              copy.put(Objects.requireNonNull(currency), Objects.requireNonNull(value)));
      return Collections.unmodifiableMap(copy);
    }
  }

  record Page(List<Entry> entries, OptionalLong nextBeforeDay) {
    public Page {
      entries = List.copyOf(entries);
      Objects.requireNonNull(nextBeforeDay);
      if (entries.size() > 16
          || nextBeforeDay.isPresent()
              && (entries.isEmpty()
                  || nextBeforeDay.getAsLong() != entries.getLast().financialDay()))
        throw new IllegalArgumentException("History page bound/cursor");
    }
  }
}
