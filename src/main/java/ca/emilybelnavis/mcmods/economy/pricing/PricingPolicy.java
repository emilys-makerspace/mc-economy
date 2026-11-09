package ca.emilybelnavis.mcmods.economy.pricing;

import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import java.math.*;
import java.time.Clock;
import java.util.*;

/**
 * Approved fixed service rates and a separately versioned configured prime. No real-world rate
 * source.
 */
public final class PricingPolicy {
  private PricingPolicy() {}

  public static final String FACT = "economy:a6/pricing-policy";
  public static final Journal.Kind CHANGE =
      new Journal.Kind("economy:a6", "economy:a6/pricing/change-prime");
  public static final BigDecimal FX_RATE = new BigDecimal("0.005"),
      TRADING_RATE = new BigDecimal("0.0025"),
      TIER1_DEPOSIT_APR = new BigDecimal("0.005"),
      TIER2_DEPOSIT_APR = new BigDecimal("0.01");

  public record Policy(int version, long effectiveMillis, BigDecimal primeApr) {
    public Policy {
      if (version < 1
          || effectiveMillis < 0
          || primeApr.signum() < 0
          || primeApr.compareTo(new BigDecimal("0.5")) > 0
          || primeApr.scale() > 6) throw new IllegalArgumentException("Pricing policy");
    }
  }

  public record Change(long effectiveMillis, BigDecimal primePercentagePoints) {
    public Change {
      Objects.requireNonNull(primePercentagePoints);
      if (effectiveMillis < 0
          || primePercentagePoints.signum() < 0
          || primePercentagePoints.compareTo(new BigDecimal("50")) > 0
          || primePercentagePoints.scale() > 4)
        throw new IllegalArgumentException(
            "Prime must be0..50 percentage points with at most4 decimal places");
    }

    public Journal.CanonicalRequest request() {
      return new Journal.CanonicalRequest(
          1,
          Map.of(
              "effective_ms",
              Long.toString(effectiveMillis),
              "prime_pp",
              primePercentagePoints.toPlainString()));
    }
  }

  public static Policy current(JournalEngine.ReadView reads, long effectiveMillis) {
    var rows =
        reads.query(
            "SELECT version,effective_ms,prime_apr FROM pricing_policies WHERE effective_ms<=? ORDER BY effective_ms DESC LIMIT 1",
            List.of(Long.toString(effectiveMillis)),
            1);
    if (rows.isEmpty()) throw new IllegalStateException("Pricing policy unavailable");
    var r = rows.getFirst();
    return new Policy(
        Integer.parseInt(r.get("version")),
        Long.parseLong(r.get("effective_ms")),
        new BigDecimal(r.get("prime_apr")));
  }

  /**
   * Caller owns operator authorization; this registration never accepts client timestamps as
   * current time.
   */
  public static JournalEngine.Registration change(Change change, Clock clock) {
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          if (!change.request().equals(intent.request()) || !CHANGE.equals(intent.kind()))
            throw new IllegalArgumentException("Pricing canonical request");
          var worldRows =
              reads.query(
                  "SELECT world_id FROM foundation_metadata WHERE singleton=1", List.of(), 1);
          if (!worldRows.isEmpty()
              && ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes.activation(
                      reads,
                      new ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId(
                          UUID.fromString(worldRows.getFirst().get("world_id"))))
                  .isPresent()) return reject("PRICING_CURRENCY_TAKEOVER_RESERVED");
          var rows =
              reads.query(
                  "SELECT origin_ms,observed_wall_ms,last_effective_ms FROM financial_calendar WHERE singleton=1",
                  List.of(),
                  1);
          if (rows.isEmpty()) return reject("PRICING_CALENDAR_UNAVAILABLE");
          var row = rows.getFirst();
          long origin = Long.parseLong(row.get("origin_ms"));
          long frontier =
              Math.max(
                  clock.millis(),
                  Math.max(
                      Long.parseLong(row.get("observed_wall_ms")),
                      Long.parseLong(row.get("last_effective_ms"))));
          if (change.effectiveMillis() <= frontier
              || change.effectiveMillis() < origin
              || (change.effectiveMillis() - origin) % 1_200_000 != 0)
            return reject("PRICING_FUTURE_DAY_REQUIRED");
          if (!reads
              .query(
                  "SELECT version FROM pricing_policies WHERE effective_ms=?",
                  List.of(Long.toString(change.effectiveMillis())),
                  1)
              .isEmpty()) return reject("PRICING_BOUNDARY_EXISTS");
          if (reads
                  .query(
                      "SELECT version FROM pricing_policies WHERE effective_ms>? LIMIT 16",
                      List.of(Long.toString(frontier)),
                      16)
                  .size()
              >= 16) return reject("PRICING_SCHEDULE_BOUND");
          int version =
              Math.addExact(
                  Integer.parseInt(
                      reads
                          .query(
                              "SELECT max(version) AS version FROM pricing_policies", List.of(), 1)
                          .getFirst()
                          .get("version")),
                  1);
          var policy =
              new Policy(
                  version,
                  change.effectiveMillis(),
                  change.primePercentagePoints().movePointLeft(2));
          return new JournalEngine.Commit(
              List.of(new Journal.Record(FACT, 1, encode(policy))),
              new Journal.Result(1, "PRICING_SCHEDULED", encode(policy)),
              tx ->
                  tx.execute(
                      "INSERT INTO pricing_policies(version,effective_ms,prime_apr) VALUES(?,?,?)",
                      List.of(
                          Integer.toString(version),
                          Long.toString(policy.effectiveMillis()),
                          policy.primeApr().toPlainString())));
        });
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog.with(new RecordCatalog.Key(FACT, 1), PricingPolicy::validate);
  }

  private static String encode(Policy p) {
    return p.version() + "|" + p.effectiveMillis() + "|" + p.primeApr().toPlainString();
  }

  private static void validate(String payload) {
    var p = payload.split("\\|", -1);
    if (p.length != 3) throw new IllegalArgumentException("Pricing policy fields");
    var policy = new Policy(Integer.parseInt(p[0]), Long.parseLong(p[1]), new BigDecimal(p[2]));
    if (!encode(policy).equals(payload))
      throw new IllegalArgumentException("Pricing policy encoding");
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }
}
