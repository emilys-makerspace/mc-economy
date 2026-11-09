package ca.emilybelnavis.mcmods.economy.pricing;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * World-aligned daily owner evaluation; unavailable inputs retain the last displayed factual tier.
 */
public final class TierReevaluation {
  private TierReevaluation() {}

  public static final String PROVIDER = "economy:a6/tier-reevaluation",
      FACT = "economy:a6/tier-valuation",
      ACTIVATION = "economy:a6/pricing-activation";
  public static final Journal.Kind ACTIVATE =
      new Journal.Kind("economy:a6", "economy:a6/pricing/activate");

  public record Status(
      long revision,
      boolean available,
      boolean eligible,
      String netWorth,
      String denomination,
      long denominationRevision,
      String fingerprint,
      long evaluatedMillis,
      String reason) {}

  public static Optional<Status> status(JournalEngine.ReadView reads, PlayerId owner) {
    return reads
        .query("SELECT * FROM pricing_tiers WHERE owner_id=?", List.of(owner.value().toString()), 1)
        .stream()
        .findFirst()
        .map(
            r ->
                new Status(
                    Long.parseLong(r.get("revision")),
                    r.get("available").equals("1"),
                    r.get("eligible").equals("1"),
                    r.get("net_worth"),
                    r.get("denomination"),
                    Long.parseLong(r.get("denomination_revision")),
                    r.get("fingerprint"),
                    Long.parseLong(r.get("evaluated_ms")),
                    r.get("reason")));
  }

  public static JournalEngine.Registration activation(Clock wall) {
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          if (!ACTIVATE.equals(intent.kind()) || !intent.request().fields().isEmpty())
            throw new IllegalArgumentException("Pricing activation canonical");
          if (!reads.query("SELECT singleton FROM pricing_activation", List.of(), 1).isEmpty())
            return new JournalEngine.Reject(new Journal.Result(1, "PRICING_ALREADY_ACTIVE", ""));
          long now = wall.millis();
          if (now < 0) throw new IllegalArgumentException("Pricing activation time");
          return new JournalEngine.Commit(
              List.of(new Journal.Record(ACTIVATION, 1, Long.toString(now))),
              new Journal.Result(1, "PRICING_ACTIVE", Long.toString(now)),
              tx ->
                  tx.execute(
                      "INSERT INTO pricing_activation VALUES(1,?)", List.of(Long.toString(now))));
        });
  }

  public static DeadlineDispatcher.Provider provider(WorldId world, WealthValuation valuation) {
    return new DeadlineDispatcher.Provider() {
      public String id() {
        return PROVIDER;
      }

      public Map<Integer, String> versions() {
        return Map.of(1, "wealth-tier-daily-v1");
      }

      public DeadlineDispatcher.Event next(
          EconomicClock.Queries reads, long origin, DeadlineDispatcher.Event completed) {
        var rows =
            reads.query("SELECT start_ms FROM pricing_activation WHERE singleton=1", List.of(), 1);
        if (rows.isEmpty()) return null;
        if (reads
            .query(
                "SELECT account_id FROM account_catalog WHERE purpose='BANK' AND status='OPEN' LIMIT 1",
                List.of(),
                1)
            .isEmpty()) return null;
        long start = Long.parseLong(rows.getFirst().get("start_ms"));
        if (completed == null) {
          var first =
              reads.query(
                  "SELECT created_ms FROM deposit_yield_claims WHERE active=1 ORDER BY created_ms LIMIT 1",
                  List.of(),
                  1);
          if (!first.isEmpty())
            start = Math.max(start, Long.parseLong(first.getFirst().get("created_ms")));
        }
        long effective =
            completed == null
                ? Math.addExact(
                    origin,
                    Math.multiplyExact(
                        Math.addExact(
                            Math.floorDiv(Math.subtractExact(start, origin), 1_200_000), 1),
                        1_200_000))
                : Math.addExact(completed.effectiveMillis(), 1_200_000);
        return new DeadlineDispatcher.Event(
            UUID.nameUUIDFromBytes(
                (PROVIDER + "|" + world.value() + "|" + effective)
                    .getBytes(StandardCharsets.UTF_8)),
            PROVIDER,
            effective,
            7,
            1);
      }

      public List<String> targets(
          DeadlineDispatcher.Event event, String cursor, int limit, EconomicClock.Queries reads) {
        if (limit < 1 || limit > 16) throw new IllegalArgumentException("Tier page bound");
        return reads
            .query(
                "SELECT owner_id FROM account_catalog WHERE purpose='BANK' AND status='OPEN' AND owner_id>? GROUP BY owner_id ORDER BY owner_id LIMIT ?",
                List.of(cursor, Integer.toString(limit)),
                limit)
            .stream()
            .map(r -> r.get("owner_id"))
            .toList();
      }

      public EconomicClock.Output prepare(
          DeadlineDispatcher.Event event, String target, EconomicClock.Queries reads) {
        return evaluate(
            reads::query,
            world,
            new PlayerId(UUID.fromString(target)),
            valuation,
            event.effectiveMillis());
      }
    };
  }

  public static EconomicClock.Output evaluate(
      JournalEngine.ReadView reads,
      WorldId world,
      PlayerId owner,
      WealthValuation valuation,
      long effectiveMillis) {
    var snapshot = valuation.snapshot(reads, world, owner, Instant.ofEpochMilli(effectiveMillis));
    var old = status(reads, owner).orElse(null);
    long revision = old == null ? 1 : Math.addExact(old.revision(), 1);
    boolean eligible = snapshot.available() ? snapshot.eligible() : old != null && old.eligible();
    String net =
        snapshot.available()
            ? snapshot.netWorth().toPlainString()
            : old == null ? null : old.netWorth();
    String denomination =
        snapshot.available()
            ? snapshot.denomination().name()
            : old == null ? null : old.denomination();
    long denominationRevision =
        snapshot.available()
            ? snapshot.denominationRevision()
            : old == null ? 0 : old.denominationRevision();
    String inputs =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(snapshot.inputs().getBytes(StandardCharsets.UTF_8));
    String fact =
        String.join(
            "|",
            owner.value().toString(),
            Long.toString(revision),
            Long.toString(effectiveMillis),
            snapshot.available() ? "1" : "0",
            eligible ? "1" : "0",
            net == null ? "" : net,
            denomination == null ? "" : denomination,
            Long.toString(denominationRevision),
            snapshot.fingerprint(),
            snapshot.reason(),
            inputs);
    return new EconomicClock.Output(
        snapshot.reason(),
        tx -> {
          tx.execute(
              "INSERT INTO pricing_tiers(owner_id,revision,available,eligible,net_worth,denomination,denomination_revision,fingerprint,evaluated_ms,reason) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(owner_id) DO UPDATE SET revision=excluded.revision,available=excluded.available,eligible=excluded.eligible,net_worth=excluded.net_worth,denomination=excluded.denomination,denomination_revision=excluded.denomination_revision,fingerprint=excluded.fingerprint,evaluated_ms=excluded.evaluated_ms,reason=excluded.reason",
              Arrays.asList(
                  owner.value().toString(),
                  Long.toString(revision),
                  snapshot.available() ? "1" : "0",
                  eligible ? "1" : "0",
                  net,
                  denomination,
                  Long.toString(denominationRevision),
                  snapshot.fingerprint(),
                  Long.toString(effectiveMillis),
                  snapshot.reason()));
          tx.execute(
              "INSERT INTO pricing_tier_history VALUES(?,?,?,?,?,?)",
              List.of(
                  owner.value().toString(),
                  Long.toString(effectiveMillis),
                  Long.toString(revision),
                  snapshot.available() ? "1" : "0",
                  eligible ? "1" : "0",
                  snapshot.fingerprint()));
        },
        List.of(new Journal.Record(FACT, 1, fact)));
  }

  /** Published boundary status, distinct from synchronous admission. No stale zero-fee benefit. */
  public static boolean tierTwoAt(
      JournalEngine.ReadView reads, PlayerId owner, long effectiveMillis) {
    var rows =
        reads.query(
            "SELECT available,eligible FROM pricing_tier_history WHERE owner_id=? AND effective_ms<=? ORDER BY effective_ms DESC LIMIT 1",
            List.of(owner.value().toString(), Long.toString(effectiveMillis)),
            1);
    return !rows.isEmpty()
        && rows.getFirst().get("available").equals("1")
        && rows.getFirst().get("eligible").equals("1");
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return PricingPolicy.register(catalog)
        .with(
            new RecordCatalog.Key(ACTIVATION, 1),
            p -> {
              if (Long.parseLong(p) < 0 || !Long.toString(Long.parseLong(p)).equals(p))
                throw new IllegalArgumentException("Pricing activation");
            })
        .with(new RecordCatalog.Key(FACT, 1), TierReevaluation::validate);
  }

  private static void validate(String payload) {
    var p = payload.split("\\|", -1);
    if (p.length != 11) throw new IllegalArgumentException("Tier fact fields");
    if (!UUID.fromString(p[0]).toString().equals(p[0])
        || Long.parseLong(p[1]) < 1
        || Long.parseLong(p[2]) < 0
        || Long.parseLong(p[7]) < 0
        || !Set.of("0", "1").contains(p[3])
        || !Set.of("0", "1").contains(p[4])) throw new IllegalArgumentException("Tier fact state");
    if (!p[5].isEmpty()) new java.math.BigDecimal(p[5]);
    if (!p[6].isEmpty()) ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.valueOf(p[6]);
    String inputs = new String(Base64.getUrlDecoder().decode(p[10]), StandardCharsets.UTF_8);
    if (inputs.getBytes(StandardCharsets.UTF_8).length > 10_000
        || p[3].equals("1")
            && (!p[8].matches("[0-9a-f]{64}") || !WealthValuation.digest(inputs).equals(p[8])))
      throw new IllegalArgumentException("Tier input witness");
  }
}
