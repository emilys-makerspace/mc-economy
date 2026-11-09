package ca.emilybelnavis.mcmods.economy.fx;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Version-one daily FX arithmetic, over detached rates and effective policy. */
public final class FxMath {
  public static final int SCALE = 12;
  private static final int ONE_PPM_FRACTION = 1_000_000;
  private static final BigDecimal MINIMUM = new BigDecimal("0.000000000001");
  private static final BigDecimal MAXIMUM = new BigDecimal("1000000");
  private static final BigDecimal REFERENCE_RATE = BigDecimal.ONE.setScale(SCALE);
  private static final byte[] DRAW_DOMAIN =
      "economy:fx-draw:v1\0".getBytes(StandardCharsets.US_ASCII);

  private FxMath() {}

  public record Settings(BigDecimal baseline, int variationPpm, int pullPpm, int boundsPpm) {
    public Settings {
      if (baseline == null
          || baseline.compareTo(MINIMUM) < 0
          || baseline.compareTo(MAXIMUM) > 0
          || baseline.stripTrailingZeros().scale() > SCALE) {
        throw new IllegalArgumentException(
            "FX baseline must be representable at publication precision");
      }
      if (variationPpm < 0
          || variationPpm > ONE_PPM_FRACTION
          || pullPpm < 0
          || pullPpm > ONE_PPM_FRACTION
          || boundsPpm < 0
          || boundsPpm >= ONE_PPM_FRACTION) {
        throw new IllegalArgumentException(
            "FX variation, pull or bounds are outside the ppm range");
      }
      baseline = baseline.setScale(SCALE, RoundingMode.UNNECESSARY);
      if (bandEdge(baseline, -boundsPpm).compareTo(MINIMUM) < 0
          || bandEdge(baseline, boundsPpm).compareTo(MAXIMUM) > 0) {
        throw new IllegalArgumentException("FX band is outside the supported publication range");
      }
    }

    private BigDecimal lower() {
      return bandEdge(baseline, -boundsPpm).setScale(SCALE, RoundingMode.CEILING);
    }

    private BigDecimal upper() {
      return bandEdge(baseline, boundsPpm).setScale(SCALE, RoundingMode.FLOOR);
    }
  }

  public static Map<EconomyCurrency, BigDecimal> opening(EconomyCurrency reference) {
    Objects.requireNonNull(reference, "reference");
    var rates = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
    for (EconomyCurrency currency : EconomyCurrency.all()) {
      rates.put(
          currency,
          currency
              .historicalCadRate()
              .divide(reference.historicalCadRate(), SCALE, RoundingMode.HALF_EVEN));
    }
    return Map.copyOf(rates);
  }

  public static Map<EconomyCurrency, Settings> defaults(EconomyCurrency reference) {
    var policy = new EnumMap<EconomyCurrency, Settings>(EconomyCurrency.class);
    opening(reference)
        .forEach(
            (currency, baseline) ->
                policy.put(
                    currency,
                    currency == reference
                        ? new Settings(baseline, 0, 0, 0)
                        : new Settings(baseline, 3_000, 100_000, 50_000)));
    return Map.copyOf(policy);
  }

  public static Map<EconomyCurrency, BigDecimal> advance(
      EconomyCurrency reference,
      Map<EconomyCurrency, BigDecimal> previous,
      Map<EconomyCurrency, Settings> policy,
      long seed) {
    Objects.requireNonNull(reference, "reference");
    var rates = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
    if (!previous.keySet().equals(policy.keySet()) || !previous.containsKey(reference))
      throw new IllegalArgumentException("FX policy and rate membership differ");
    for (EconomyCurrency currency : EconomyCurrency.all()) {
      if (!previous.containsKey(currency)) continue;
      Settings settings = policy.get(currency);
      BigDecimal prior = previous.get(currency);
      if (settings == null
          || prior == null
          || prior.compareTo(MINIMUM) < 0
          || prior.compareTo(MAXIMUM) > 0
          || prior.scale() != SCALE) {
        throw new IllegalArgumentException(
            "Missing policy or invalid previous FX rate for " + currency.code());
      }
      if (currency == reference) {
        if (settings.baseline().compareTo(REFERENCE_RATE) != 0
            || prior.compareTo(REFERENCE_RATE) != 0) {
          throw new IllegalArgumentException("FX reference rate must equal one");
        }
        rates.put(currency, REFERENCE_RATE);
        continue;
      }
      BigDecimal baseline = settings.baseline();
      BigDecimal pull = baseline.subtract(prior).multiply(ppm(settings.pullPpm()));
      BigDecimal variation =
          baseline.multiply(ppm(drawPpm(seed, currency, settings.variationPpm())));
      BigDecimal candidate = prior.add(pull).add(variation);
      BigDecimal bounded = candidate.max(settings.lower()).min(settings.upper());
      rates.put(currency, bounded.setScale(SCALE, RoundingMode.HALF_EVEN));
    }
    return Map.copyOf(rates);
  }

  /** ASCII domain and code, big-endian seed/counter. Reject biased unsigned 32-bit words. */
  static int drawPpm(long seed, EconomyCurrency currency, int variationPpm) {
    if (variationPpm == 0) {
      return 0;
    }
    byte[] code = currency.code().getBytes(StandardCharsets.US_ASCII);
    ByteBuffer input =
        ByteBuffer.allocate(DRAW_DOMAIN.length + Long.BYTES + code.length + Integer.BYTES);
    input.put(DRAW_DOMAIN).putLong(seed).put(code);
    int counterOffset = input.position();
    long span = 2L * variationPpm + 1;
    long limit = (1L << 32) / span * span;
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
    for (int counter = 0; ; counter = Math.incrementExact(counter)) {
      input.putInt(counterOffset, counter);
      long word = Integer.toUnsignedLong(ByteBuffer.wrap(digest.digest(input.array())).getInt());
      if (word < limit) {
        return (int) (word % span) - variationPpm;
      }
    }
  }

  private static BigDecimal bandEdge(BigDecimal baseline, int deltaPpm) {
    return baseline.multiply(ppm(ONE_PPM_FRACTION + deltaPpm));
  }

  private static BigDecimal ppm(int value) {
    return BigDecimal.valueOf(value, 6);
  }
}
