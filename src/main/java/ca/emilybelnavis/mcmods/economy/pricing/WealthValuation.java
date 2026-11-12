package ca.emilybelnavis.mcmods.economy.pricing;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.institution.BankProviders;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.journal.RebaseSettlement;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/**
 * Complete owner valuation. Installed adapters supply bounded authoritative aggregates, never
 * inventory guesses.
 */
public final class WealthValuation {
  public static final BigDecimal THRESHOLD = new BigDecimal("100000000");
  public static final int POLICY_VERSION = 1;
  public static final MathContext MATH = new MathContext(34, RoundingMode.HALF_EVEN);

  public enum Kind {
    ASSET,
    LIABILITY
  }

  public record Component(String claimId, EconomyCurrency currency, BigDecimal major) {
    public Component {
      if (claimId == null || !claimId.matches("[a-zA-Z0-9_./:-]{1,128}"))
        throw new IllegalArgumentException("Claim identity");
      Objects.requireNonNull(currency);
      Objects.requireNonNull(major);
      if (major.signum() < 0 || major.precision() > 34 || major.scale() > 18 || major.scale() < -18)
        throw new IllegalArgumentException("Valuation amount");
    }
  }

  public record SourceSnapshot(
      boolean available,
      long revision,
      String markId,
      Instant markedAt,
      List<Component> components) {
    public SourceSnapshot {
      if (revision < 0
          || markId == null
          || markId.length() > 128
          || markId.indexOf('|') >= 0
          || markId.indexOf('\n') >= 0) throw new IllegalArgumentException("Source revision/mark");
      Objects.requireNonNull(markedAt);
      components = List.copyOf(components);
      if (components.size() > 16) throw new IllegalArgumentException("Source aggregate bound");
      if (!available && !components.isEmpty())
        throw new IllegalArgumentException("Unavailable source cannot supply assets");
    }

    public static SourceSnapshot unavailable(long revision, Instant now) {
      return new SourceSnapshot(false, revision, "unavailable", now, List.of());
    }
  }

  public interface Source {
    String id();

    Kind kind();

    /** True for installed securities mark adapters; marks expire after one financial day. */
    default boolean requiresFreshMark() {
      return false;
    }

    SourceSnapshot read(JournalEngine.ReadView reads, WorldId world, PlayerId owner, Instant now);
  }

  public record ValuedComponent(
      String claimId,
      Kind kind,
      EconomyCurrency currency,
      BigDecimal major,
      BigDecimal serverMajor) {}

  public record Snapshot(
      boolean available,
      String reason,
      BigDecimal netWorth,
      boolean eligible,
      String fingerprint,
      String inputs,
      UUID publication,
      EconomyCurrency denomination,
      long denominationRevision,
      int policyVersion,
      Instant valuedAt,
      List<ValuedComponent> components) {
    public Snapshot {
      components = List.copyOf(components);
    }
  }

  private final List<Source> sources;

  public WealthValuation(List<Source> sources) {
    if (sources.size() > 8) throw new IllegalArgumentException("Installed valuation source bound");
    var ids = new HashSet<String>();
    for (var source : sources)
      if (source.id() == null
          || !source.id().matches("[a-z0-9_.:/-]{1,96}")
          || !ids.add(source.id()))
        throw new IllegalArgumentException("Duplicate/invalid valuation source");
    this.sources = sources.stream().sorted(Comparator.comparing(Source::id)).toList();
  }

  public WealthValuation() {
    this(List.of());
  }

  /**
   * Every installed source is required; this immutable contract is persisted before finance is
   * ready.
   */
  public List<ValuationSources.Contract> sourceManifest() {
    return sources.stream()
        .map(
            source ->
                new ValuationSources.Contract(
                    source.id(), source.kind(), source.requiresFreshMark()))
        .toList();
  }

  public Set<String> unsupportedCapabilities() {
    return Set.of("securities-without-installed-adapter", "physical-cash", "items", "real-estate");
  }

  public Snapshot snapshot(
      JournalEngine.ReadView reads, WorldId world, PlayerId owner, Instant now) {
    Objects.requireNonNull(reads);
    Objects.requireNonNull(world);
    Objects.requireNonNull(owner);
    Objects.requireNonNull(now);
    var denomination = RebaseSettlement.denomination(reads).orElse(null);
    var fx = FxService.latest(reads);
    if (denomination == null || fx == null) return unavailable("VALUATION_FX_UNAVAILABLE", now);
    var effective = Instant.ofEpochMilli(FxService.effectiveMillis(reads, fx.updateId()));
    if (now.isBefore(effective) || now.isAfter(effective.plus(Duration.ofHours(48))))
      return unavailable("VALUATION_FX_STALE", now);
    if (!fx.rates().containsKey(denomination.currency()))
      return unavailable("VALUATION_RATE_MISSING", now);
    // Compatibility bytes are only fingerprint input, never emitted as a legacy FX fact.
    // The authoritative UUID above resolves the exact effective time for both families.
    var canonical =
        new StringBuilder("1|")
            .append(world.value())
            .append('|')
            .append(owner.value())
            .append('|')
            .append(denomination.currency())
            .append('|')
            .append(denomination.revision())
            .append('|')
            .append(fx.encode());
    var components = new ArrayList<ValuedComponent>();
    var claims = new HashSet<String>();
    Rational net = Rational.ZERO;
    var accounts =
        reads.query(
            "SELECT a.account_id,a.currency,a.revision,a.is_primary,COALESCE(l.minor_units,0) AS minor_units,COALESCE(p.bank_key,'') AS bank_key,COALESCE(p.revision,0) AS provider_revision FROM account_catalog a LEFT JOIN ledger_balances l ON l.account_id=a.account_id AND l.currency=a.currency LEFT JOIN bank_account_providers p ON p.account_id=a.account_id WHERE a.owner_id=? AND a.purpose='BANK' AND a.status='OPEN' ORDER BY a.account_id LIMIT 5",
            List.of(owner.value().toString()),
            5);
    if (accounts.size() > 4) return unavailable("VALUATION_ACCOUNT_BOUND", now);
    try {
      for (var row : accounts) {
        var currency = EconomyCurrency.valueOf(row.get("currency"));
        long minor = Long.parseLong(row.get("minor_units"));
        var amount =
            BigDecimal.valueOf(minor)
                .abs()
                .divide(BigDecimal.valueOf(currency.minorUnitsPerMajor()));
        var component = new Component("ledger:" + row.get("account_id"), currency, amount);
        net =
            net.add(
                value(
                    component,
                    minor < 0 ? Kind.LIABILITY : Kind.ASSET,
                    fx.rates(),
                    denomination.currency(),
                    components,
                    claims));
        canonical
            .append("\naccount|")
            .append(row.get("account_id"))
            .append('|')
            .append(currency)
            .append('|')
            .append(row.get("revision"))
            .append('|')
            .append(row.get("is_primary"))
            .append('|')
            .append(minor)
            .append('|')
            .append(ledgerRevision(reads, UUID.fromString(row.get("account_id"))))
            .append('|')
            .append(row.get("bank_key"))
            .append('|')
            .append(row.get("provider_revision"));
      }
      for (var source : sources) {
        var state = Objects.requireNonNull(source.read(reads, world, owner, now));
        canonical
            .append("\nsource|")
            .append(source.id())
            .append('|')
            .append(source.kind())
            .append('|')
            .append(state.revision())
            .append('|')
            .append(state.markId())
            .append('|')
            .append(state.markedAt())
            .append('|')
            .append(state.available());
        if (!state.available())
          return incomplete(
              "VALUATION_SOURCE_UNAVAILABLE:" + source.id(),
              now,
              canonical,
              fx.updateId(),
              denomination.currency(),
              denomination.revision());
        if (state.markedAt().isAfter(now)
            || source.requiresFreshMark() && state.markedAt().isBefore(now.minusMillis(1_200_000)))
          return incomplete(
              "VALUATION_MARK_STALE:" + source.id(),
              now,
              canonical,
              fx.updateId(),
              denomination.currency(),
              denomination.revision());
        for (var component :
            state.components().stream().sorted(Comparator.comparing(Component::claimId)).toList()) {
          if (components.size() >= 64) return unavailable("VALUATION_COMPONENT_BOUND", now);
          net =
              net.add(
                  value(
                      component,
                      source.kind(),
                      fx.rates(),
                      denomination.currency(),
                      components,
                      claims));
          canonical
              .append("\nclaim|")
              .append(component.claimId())
              .append('|')
              .append(component.currency())
              .append('|')
              .append(component.major().toPlainString());
        }
      }
    } catch (IllegalArgumentException | ArithmeticException failure) {
      return unavailable("VALUATION_INVALID_INPUT", now);
    }
    if (canonical.toString().getBytes(StandardCharsets.UTF_8).length > 10_000)
      return unavailable("VALUATION_INPUT_BOUND", now);
    return new Snapshot(
        true,
        "VALUATION_COMPLETE",
        net.decimal(),
        net.compareTo(Rational.of(THRESHOLD)) >= 0,
        digest(canonical.toString()),
        canonical.toString(),
        fx.updateId(),
        denomination.currency(),
        denomination.revision(),
        POLICY_VERSION,
        now,
        components);
  }

  /** Immutable posting sequence prevents a balance round trip from reviving a stale quote. */
  public static long ledgerRevision(JournalEngine.ReadView reads, UUID account) {
    var rows =
        reads.query(
            "SELECT sequence FROM journal_records WHERE record_type='economy:posting' AND record_version IN (1,2,3,4) AND substr(payload,1,36)=? ORDER BY sequence DESC,position DESC LIMIT 1",
            List.of(account.toString()),
            1);
    return rows.isEmpty() ? 0 : Long.parseLong(rows.getFirst().get("sequence"));
  }

  public BankProviders.TierEligibility tierEligibility(WorldId world, Clock clock) {
    return (reads, owner) -> {
      if (!world.equals(owner.world()) || !(owner.owner() instanceof PartyRef.Player player))
        return new BankProviders.TierSnapshot(false, false, 0);
      var snapshot = snapshot(reads, world, player.id(), clock.instant());
      return new BankProviders.TierSnapshot(
          snapshot.available(), snapshot.eligible(), revision(snapshot));
    };
  }

  /**
   * Stable positive witness for the current complete input fingerprint, not a substitute for
   * recomputation.
   */
  public static long revision(Snapshot snapshot) {
    if (!snapshot.available()) return 0;
    if (snapshot.inputs().contains("\nsource|economy:loan-liabilities|")) {
      var stable = new StringBuilder();
      for (var line : snapshot.inputs().split("\n")) {
        if (line.startsWith("claim|loan-debt:"))
          line = line.substring(0, line.lastIndexOf('|')) + "|time-accrual";
        stable.append(line).append('\n');
      }
      stable.append("eligible|").append(snapshot.eligible());
      return Long.parseUnsignedLong(digest(stable.toString()).substring(0, 15), 16);
    }
    return Long.parseUnsignedLong(snapshot.fingerprint().substring(0, 15), 16);
  }

  private static Rational value(
      Component c,
      Kind kind,
      Map<EconomyCurrency, BigDecimal> rates,
      EconomyCurrency target,
      List<ValuedComponent> values,
      Set<String> claims) {
    if (!claims.add(c.claimId())) throw new IllegalArgumentException("Duplicate wealth claim");
    var rate = rates.get(c.currency());
    if (rate == null) throw new IllegalArgumentException("Missing valuation rate");
    var converted =
        Rational.of(c.major()).multiply(Rational.of(rate)).divide(Rational.of(rates.get(target)));
    values.add(
        new ValuedComponent(c.claimId(), kind, c.currency(), c.major(), converted.decimal()));
    return kind == Kind.ASSET ? converted : converted.negate();
  }

  private static Snapshot incomplete(
      String reason,
      Instant now,
      StringBuilder known,
      UUID publication,
      EconomyCurrency denomination,
      long denominationRevision) {
    String inputs = known + "\nunavailable|" + reason;
    if (inputs.getBytes(StandardCharsets.UTF_8).length > 10_000)
      return unavailable("VALUATION_INPUT_BOUND", now);
    return new Snapshot(
        false,
        reason,
        null,
        false,
        digest(inputs),
        inputs,
        publication,
        denomination,
        denominationRevision,
        POLICY_VERSION,
        now,
        List.of());
  }

  private static Snapshot unavailable(String reason, Instant now) {
    return new Snapshot(
        false, reason, null, false, "", "", null, null, 0, POLICY_VERSION, now, List.of());
  }

  public static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** Exact comparison avoids promoting a just-below-threshold rational by display rounding. */
  private record Rational(BigInteger n, BigInteger d) implements Comparable<Rational> {
    static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);

    Rational {
      if (d.signum() == 0) throw new ArithmeticException("Zero denominator");
      if (d.signum() < 0) {
        n = n.negate();
        d = d.negate();
      }
      var gcd = n.gcd(d);
      n = n.divide(gcd);
      d = d.divide(gcd);
    }

    static Rational of(BigDecimal decimal) {
      return decimal.scale() >= 0
          ? new Rational(decimal.unscaledValue(), BigInteger.TEN.pow(decimal.scale()))
          : new Rational(
              decimal.unscaledValue().multiply(BigInteger.TEN.pow(-decimal.scale())),
              BigInteger.ONE);
    }

    Rational add(Rational other) {
      return new Rational(n.multiply(other.d).add(other.n.multiply(d)), d.multiply(other.d));
    }

    Rational multiply(Rational other) {
      return new Rational(n.multiply(other.n), d.multiply(other.d));
    }

    Rational divide(Rational other) {
      return new Rational(n.multiply(other.d), d.multiply(other.n));
    }

    Rational negate() {
      return new Rational(n.negate(), d);
    }

    BigDecimal decimal() {
      return new BigDecimal(n).divide(new BigDecimal(d), MATH);
    }

    public int compareTo(Rational other) {
      return n.multiply(other.d).compareTo(other.n.multiply(d));
    }
  }
}
