package ca.emilybelnavis.mcmods.economy.fx;

import ca.emilybelnavis.mcmods.economy.api.CurrencyConversion;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Frozen legacy v1 and CHF v2 journal payloads; the first ten characters remain the indexed UTC
 * date.
 */
public final class FxFacts {
  public static final String PUBLICATION = "economy:fx-publication";
  public static final String POLICY = "economy:fx-policy";
  public static final String DAY_KEY = "substr(payload,1,10)";

  private static final List<EconomyCurrency> LEGACY_CURRENCIES =
      List.of(
          EconomyCurrency.CAD,
          EconomyCurrency.EUR,
          EconomyCurrency.GBP,
          EconomyCurrency.AUD,
          EconomyCurrency.NZD,
          EconomyCurrency.USD,
          EconomyCurrency.HKD,
          EconomyCurrency.JPY,
          EconomyCurrency.CNY,
          EconomyCurrency.DKK);
  private static final List<EconomyCurrency> CHF_CURRENCIES =
      List.of(
          EconomyCurrency.CAD,
          EconomyCurrency.EUR,
          EconomyCurrency.GBP,
          EconomyCurrency.AUD,
          EconomyCurrency.NZD,
          EconomyCurrency.USD,
          EconomyCurrency.HKD,
          EconomyCurrency.JPY,
          EconomyCurrency.CNY,
          EconomyCurrency.DKK,
          EconomyCurrency.CHF);

  private FxFacts() {}

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog
        .with(new RecordCatalog.Key(PUBLICATION, 1), payload -> Publication.decode(payload, 1))
        .with(new RecordCatalog.Key(POLICY, 1), payload -> Policy.decode(payload, 1))
        .with(new RecordCatalog.Key(PUBLICATION, 2), payload -> Publication.decode(payload, 2))
        .with(new RecordCatalog.Key(POLICY, 2), payload -> Policy.decode(payload, 2))
        .with(
            new RecordCatalog.Key(PUBLICATION, 3),
            payload -> {
              var publication = Publication.decode(payload, 2);
              if (publication.rates().values().stream()
                  .anyMatch(rate -> rate.stripTrailingZeros().scale() > 8))
                throw new IllegalArgumentException("Eight-decimal FX publication precision");
            });
  }

  public record Publication(
      LocalDate day,
      EconomyCurrency reference,
      UUID updateId,
      UUID policyId,
      Map<EconomyCurrency, BigDecimal> rates) {
    public Publication {
      dayKey(day);
      Objects.requireNonNull(updateId);
      Objects.requireNonNull(policyId);
      rates = new CurrencyConversion.Rates(reference, rates).perMajorUnit();
      currencies(rates.keySet());
      if (!rates.containsKey(reference)
          || rates.values().stream()
              .anyMatch(
                  rate ->
                      rate.scale() != 12 || rate.compareTo(BigDecimal.valueOf(1_000_000)) > 0)) {
        throw new IllegalArgumentException("Incomplete or unrepresentable FX publication");
      }
    }

    public CurrencyConversion.Rates conversionRates() {
      return new CurrencyConversion.Rates(reference, rates);
    }

    public String encode() {
      StringBuilder value =
          new StringBuilder(dayKey(day))
              .append('|')
              .append(reference.code())
              .append('|')
              .append(updateId)
              .append('|')
              .append(policyId);
      for (var currency : currencies(rates.keySet()))
        value.append('|').append(rates.get(currency).toPlainString());
      return value.toString();
    }

    public int version() {
      return rates.containsKey(EconomyCurrency.CHF) ? 2 : 1;
    }

    Journal.Record record() {
      return new Journal.Record(PUBLICATION, version(), encode());
    }

    public static Publication decode(String payload) {
      return decode(payload, payload.split("\\|", -1).length == 14 ? 1 : 2);
    }

    public static Publication decode(String payload, int version) {
      String[] parts = payload.split("\\|", -1);
      var currencies = currencies(version == 3 ? 2 : version);
      if (parts.length != 4 + currencies.size())
        throw new IllegalArgumentException("FX publication fields");
      var rates = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
      int index = 4;
      for (var currency : currencies) rates.put(currency, new BigDecimal(parts[index++]));
      var publication =
          new Publication(
              LocalDate.parse(parts[0]),
              EconomyCurrency.fromCode(parts[1]),
              UUID.fromString(parts[2]),
              UUID.fromString(parts[3]),
              rates);
      if (version == 3
          && publication.rates().values().stream()
              .anyMatch(rate -> rate.stripTrailingZeros().scale() > 8))
        throw new IllegalArgumentException("Eight-decimal FX publication precision");
      if (!publication.encode().equals(payload))
        throw new IllegalArgumentException("Noncanonical FX publication");
      return publication;
    }
  }

  public record Policy(
      LocalDate effectiveDay,
      UUID id,
      EconomyCurrency reference,
      Map<EconomyCurrency, FxMath.Settings> settings) {
    public Policy {
      dayKey(effectiveDay);
      Objects.requireNonNull(id);
      Objects.requireNonNull(reference);
      settings = Map.copyOf(settings);
      currencies(settings.keySet());
      if (!settings.containsKey(reference)
          || settings.get(reference).baseline().compareTo(BigDecimal.ONE) != 0) {
        throw new IllegalArgumentException("Incomplete FX policy or invalid reference");
      }
    }

    String encode() {
      StringBuilder value =
          new StringBuilder(dayKey(effectiveDay))
              .append('|')
              .append(id)
              .append('|')
              .append(reference.code());
      for (var currency : currencies(settings.keySet())) {
        var setting = settings.get(currency);
        value
            .append('|')
            .append(setting.baseline().toPlainString())
            .append(',')
            .append(setting.variationPpm())
            .append(',')
            .append(setting.pullPpm())
            .append(',')
            .append(setting.boundsPpm());
      }
      return value.toString();
    }

    public int version() {
      return settings.containsKey(EconomyCurrency.CHF) ? 2 : 1;
    }

    Journal.Record record() {
      return new Journal.Record(POLICY, version(), encode());
    }

    public static Policy decode(String payload) {
      return decode(payload, payload.split("\\|", -1).length == 13 ? 1 : 2);
    }

    public static Policy decode(String payload, int version) {
      String[] parts = payload.split("\\|", -1);
      var currencies = currencies(version);
      if (parts.length != 3 + currencies.size())
        throw new IllegalArgumentException("FX policy fields");
      var settings = new EnumMap<EconomyCurrency, FxMath.Settings>(EconomyCurrency.class);
      int index = 3;
      for (var currency : currencies) {
        String[] fields = parts[index++].split(",", -1);
        if (fields.length != 4) throw new IllegalArgumentException("FX settings fields");
        settings.put(
            currency,
            new FxMath.Settings(
                new BigDecimal(fields[0]),
                Integer.parseInt(fields[1]),
                Integer.parseInt(fields[2]),
                Integer.parseInt(fields[3])));
      }
      var policy =
          new Policy(
              LocalDate.parse(parts[0]),
              UUID.fromString(parts[1]),
              EconomyCurrency.fromCode(parts[2]),
              settings);
      if (!policy.encode().equals(payload))
        throw new IllegalArgumentException("Noncanonical FX policy");
      return policy;
    }
  }

  /** Exact historical membership. Adding a registry currency never changes a v1 payload. */
  public static List<EconomyCurrency> currencies(int version) {
    if (version == 1) return LEGACY_CURRENCIES;
    if (version == 2) return CHF_CURRENCIES;
    throw new IllegalArgumentException("Unsupported FX fact version");
  }

  private static List<EconomyCurrency> currencies(Set<EconomyCurrency> keys) {
    for (int version : new int[] {1, 2}) {
      var currencies = currencies(version);
      if (keys.equals(Set.copyOf(currencies))) return currencies;
    }
    throw new IllegalArgumentException("Incomplete FX currency membership");
  }

  static String dayKey(LocalDate day) {
    String key = day.toString();
    if (day.getYear() < 1970 || key.length() != 10)
      throw new IllegalArgumentException("Unsupported FX UTC day");
    return key;
  }
}
