package ca.emilybelnavis.mcmods.economy.credit;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import java.math.*;
import java.util.*;

/**
 * Pure version-one card servicing. UTC admission, journal replay and postings belong to the caller.
 */
public final class CardServicing {
  public static final int VERSION = 1, MAX_ROWS = 128;
  public static final long DAY_MILLIS = 1_200_000L, MONTH_MILLIS = 28 * DAY_MILLIS;
  public static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(18);

  public enum Kind {
    PURCHASE,
    ADVANCE
  }

  public record Bucket(
      Kind kind,
      long principalMinor,
      long interestMinor,
      BigDecimal residualMajor,
      BigDecimal periodEarnedMajor,
      BigDecimal apr) {
    public Bucket {
      Objects.requireNonNull(kind);
      Objects.requireNonNull(apr);
      residualMajor = exact(residualMajor);
      periodEarnedMajor = exact(periodEarnedMajor);
      if (principalMinor < 0
          || interestMinor < 0
          || periodEarnedMajor.signum() < 0
          || apr.signum() < 0
          || apr.compareTo(BigDecimal.ONE) > 0
          || apr.precision() > 34
          || Math.abs(apr.scale()) > 18) throw new IllegalArgumentException("Card bucket");
      Math.addExact(principalMinor, interestMinor);
    }

    public long debtMinor() {
      return Math.addExact(principalMinor, interestMinor);
    }
  }

  public record Accrual(Bucket bucket, BigDecimal earnedMajor, long postedMinor) {}

  public record Requirement(
      String id, long statementAt, long dueAt, long originalMinor, long unmetMinor) {
    public Requirement {
      id = identity(id);
      if (statementAt < 0
          || dueAt != Math.addExact(statementAt, 14 * DAY_MILLIS)
          || originalMinor < 0
          || unmetMinor < 0
          || unmetMinor > originalMinor) throw new IllegalArgumentException("Card requirement");
    }
  }

  public record Statement(
      String id,
      long closedAt,
      long dueAt,
      long debtMinor,
      long principalMinor,
      BigDecimal periodInterestMajor,
      long newRequirementMinor,
      long totalRequiredMinor) {}

  /**
   * Disjoint outstanding posted debt attributed to an earlier statement, not additional liability.
   */
  public record DebtSlice(
      String id, long statementAt, long dueAt, Kind kind, long principalMinor, long interestMinor) {
    public DebtSlice {
      id = identity(id);
      Objects.requireNonNull(kind);
      if (statementAt < 0
          || dueAt != Math.addExact(statementAt, 14 * DAY_MILLIS)
          || principalMinor < 0
          || interestMinor < 0) throw new IllegalArgumentException("Card debt slice");
      Math.addExact(principalMinor, interestMinor);
    }
  }

  public record Allocation(String statementId, Kind kind, boolean interest, long minor) {}

  public record Payment(
      List<Bucket> buckets,
      List<Requirement> requirements,
      List<DebtSlice> slices,
      List<Allocation> allocations,
      BigDecimal waivedResidualMajor) {
    public Payment {
      buckets = List.copyOf(buckets);
      requirements = List.copyOf(requirements);
      slices = List.copyOf(slices);
      allocations = List.copyOf(allocations);
    }
  }

  private CardServicing() {}

  public static Bucket empty(Kind kind, BigDecimal apr) {
    return new Bucket(kind, 0, 0, ZERO, ZERO, apr);
  }

  public static BigDecimal purchaseApr(String network) {
    return networkApr(network, false);
  }

  public static BigDecimal advanceApr(String network) {
    return networkApr(network, true);
  }

  private static BigDecimal networkApr(String network, boolean advance) {
    if (!Set.of("economy:keystone", "economy:orbit", "economy:wayline", "economy:ember")
        .contains(network)) throw new IllegalArgumentException("Card network");
    return new BigDecimal(
        network.equals("economy:keystone")
            ? (advance ? "0.2399" : "0.2199")
            : (advance ? "0.2199" : "0.1999"));
  }

  /** One completed anchor day; current-day earned interest does not enter its own basis. */
  public static Accrual day(EconomyCurrency currency, Bucket bucket) {
    validate(currency, bucket);
    BigDecimal basis = major(currency, bucket.debtMinor()).add(bucket.residualMajor(), MC);
    if (basis.signum() < 0) throw new IllegalArgumentException("Negative exact card debt");
    BigDecimal earned =
        basis
            .multiply(bucket.apr(), MC)
            .divide(BigDecimal.valueOf(364), MC)
            .setScale(18, RoundingMode.HALF_EVEN);
    BigDecimal unsettled = bucket.residualMajor().add(earned, MC);
    long posted = minor(currency, unsettled, RoundingMode.HALF_EVEN);
    long interest = Math.addExact(bucket.interestMinor(), posted);
    if (interest < 0) throw new IllegalArgumentException("Negative posted interest");
    BigDecimal residual =
        unsettled.subtract(major(currency, posted), MC).setScale(18, RoundingMode.HALF_EVEN);
    Bucket result =
        new Bucket(
            bucket.kind(),
            bucket.principalMinor(),
            interest,
            residual,
            bucket.periodEarnedMajor().add(earned, MC).setScale(18, RoundingMode.HALF_EVEN),
            bucket.apr());
    validate(currency, result);
    return new Accrual(result, earned, posted);
  }

  public static Bucket draw(Bucket bucket, long minor) {
    if (minor <= 0) throw new IllegalArgumentException("Positive draw required");
    return new Bucket(
        bucket.kind(),
        Math.addExact(bucket.principalMinor(), minor),
        bucket.interestMinor(),
        bucket.residualMajor(),
        bucket.periodEarnedMajor(),
        bucket.apr());
  }

  public static Bucket nextPeriod(Bucket bucket) {
    return new Bucket(
        bucket.kind(),
        bucket.principalMinor(),
        bucket.interestMinor(),
        bucket.residualMajor(),
        ZERO,
        bucket.apr());
  }

  public static Statement statement(
      EconomyCurrency currency,
      String id,
      long closeAt,
      List<Bucket> buckets,
      List<Requirement> older) {
    id = identity(id);
    var values = buckets(currency, buckets);
    requirements(older);
    if (closeAt < 0) throw new IllegalArgumentException("Statement time");
    long debt = 0, principal = 0, arrears = 0;
    BigDecimal earned = ZERO;
    for (var b : values) {
      debt = Math.addExact(debt, b.debtMinor());
      principal = Math.addExact(principal, b.principalMinor());
      earned = earned.add(b.periodEarnedMajor(), MC);
    }
    for (var r : older) {
      if (r.statementAt() >= closeAt) throw new IllegalArgumentException("Statement order");
      arrears = Math.addExact(arrears, r.unmetMinor());
    }
    if (arrears > debt) throw new IllegalArgumentException("Requirements exceed debt");
    BigDecimal wanted =
        major(currency, principal).multiply(new BigDecimal("0.02"), MC).add(earned, MC);
    long candidate = Math.min(debt, minor(currency, wanted, RoundingMode.CEILING));
    long fresh = Math.min(candidate, Math.subtractExact(debt, arrears));
    return new Statement(
        id,
        closeAt,
        Math.addExact(closeAt, 14 * DAY_MILLIS),
        debt,
        principal,
        earned,
        fresh,
        Math.addExact(arrears, fresh));
  }

  /** Financial allocation and minimum satisfaction are separate uses of the same payment. */
  public static Payment repay(
      EconomyCurrency currency,
      List<Bucket> input,
      List<Requirement> requirements,
      List<DebtSlice> slices,
      long amount,
      long now) {
    var values = buckets(currency, input);
    requirements(requirements);
    bounded(slices);
    if (amount <= 0 || now < 0) throw new IllegalArgumentException("Positive payment required");
    long debt = 0, required = 0;
    for (var b : values) debt = Math.addExact(debt, b.debtMinor());
    for (var r : requirements) required = Math.addExact(required, r.unmetMinor());
    if (amount > debt || required > debt)
      throw new IllegalArgumentException("Payment exceeds debt or invalid requirements");
    Map<Kind, Bucket> state = new EnumMap<>(Kind.class);
    for (var b : values) state.put(b.kind(), b);
    var working = new ArrayList<DebtSlice>(slices);
    var seen = new HashSet<String>();
    for (var s : working)
      if (!seen.add(s.id() + "/" + s.kind()))
        throw new IllegalArgumentException("Duplicate debt slice");
    for (var kind : Kind.values()) {
      long p = 0, i = 0;
      for (var s : working)
        if (s.kind() == kind) {
          p = Math.addExact(p, s.principalMinor());
          i = Math.addExact(i, s.interestMinor());
        }
      var b = state.get(kind);
      if (p > b.principalMinor() || i > b.interestMinor())
        throw new IllegalArgumentException("Debt attribution exceeds bucket");
    }
    var allocations = new ArrayList<Allocation>();
    long remaining = amount;
    var overdue = new TreeSet<Long>();
    for (var s : working) if (s.dueAt() <= now) overdue.add(s.statementAt());
    for (long at : overdue) {
      for (boolean interest : new boolean[] {true, false}) {
        var order = new ArrayList<Integer>();
        for (int n = 0; n < working.size(); n++)
          if (working.get(n).statementAt() == at && working.get(n).dueAt() <= now) order.add(n);
        order.sort(
            (a, b) ->
                compare(
                    working.get(a).kind(),
                    working.get(b).kind(),
                    state,
                    working.get(a).id(),
                    working.get(b).id()));
        for (int n : order) {
          var s = working.get(n);
          long available = interest ? s.interestMinor() : s.principalMinor();
          long paid = Math.min(remaining, available);
          if (paid == 0) continue;
          state.put(s.kind(), subtract(state.get(s.kind()), interest, paid));
          working.set(
              n,
              new DebtSlice(
                  s.id(),
                  s.statementAt(),
                  s.dueAt(),
                  s.kind(),
                  s.principalMinor() - (interest ? 0 : paid),
                  s.interestMinor() - (interest ? paid : 0)));
          allocations.add(new Allocation(s.id(), s.kind(), interest, paid));
          remaining -= paid;
        }
      }
    }
    // Non-overdue/unbilled debt: all interest before highest-APR principal.
    var kinds = new ArrayList<>(List.of(Kind.values()));
    kinds.sort((a, b) -> compare(a, b, state, "", ""));
    for (boolean interest : new boolean[] {true, false})
      for (var kind : kinds) {
        var b = state.get(kind);
        long paid = Math.min(remaining, interest ? b.interestMinor() : b.principalMinor());
        if (paid == 0) continue;
        state.put(kind, subtract(b, interest, paid));
        remaining -= paid;
        long attributionPayment = paid;
        var order = new ArrayList<Integer>();
        for (int n = 0; n < working.size(); n++) if (working.get(n).kind() == kind) order.add(n);
        order.sort(
            Comparator.comparingLong((Integer n) -> working.get(n).statementAt())
                .thenComparing(n -> working.get(n).id()));
        // At the same APR/kind, older statement debt precedes subsequent unbilled spending.
        for (int n : order) {
          var s = working.get(n);
          long part =
              Math.min(attributionPayment, interest ? s.interestMinor() : s.principalMinor());
          working.set(
              n,
              new DebtSlice(
                  s.id(),
                  s.statementAt(),
                  s.dueAt(),
                  s.kind(),
                  s.principalMinor() - (interest ? 0 : part),
                  s.interestMinor() - (interest ? part : 0)));
          if (part > 0) allocations.add(new Allocation(s.id(), kind, interest, part));
          attributionPayment -= part;
        }
        if (attributionPayment > 0)
          allocations.add(new Allocation("", kind, interest, attributionPayment));
      }
    if (remaining != 0) throw new IllegalStateException("Unallocated card payment");
    var updated = new ArrayList<Requirement>(requirements);
    updated.sort(Comparator.comparingLong(Requirement::dueAt).thenComparing(Requirement::id));
    long satisfy = amount;
    for (int n = 0; n < updated.size(); n++) {
      var r = updated.get(n);
      long paid = Math.min(satisfy, r.unmetMinor());
      updated.set(
          n,
          new Requirement(
              r.id(), r.statementAt(), r.dueAt(), r.originalMinor(), r.unmetMinor() - paid));
      satisfy -= paid;
    }
    BigDecimal waived = ZERO;
    for (var kind : Kind.values()) {
      var b = state.get(kind);
      if (b.debtMinor() == 0) {
        waived = waived.add(b.residualMajor(), MC);
        state.put(kind, new Bucket(kind, 0, 0, ZERO, b.periodEarnedMajor(), b.apr()));
      }
    }
    return new Payment(
        Arrays.stream(Kind.values()).map(state::get).toList(),
        updated,
        working,
        allocations,
        waived.setScale(18, RoundingMode.HALF_EVEN));
  }

  private static Bucket subtract(Bucket b, boolean interest, long paid) {
    return new Bucket(
        b.kind(),
        b.principalMinor() - (interest ? 0 : paid),
        b.interestMinor() - (interest ? paid : 0),
        b.residualMajor(),
        b.periodEarnedMajor(),
        b.apr());
  }

  private static int compare(Kind a, Kind b, Map<Kind, Bucket> state, String aid, String bid) {
    int c = state.get(b).apr().compareTo(state.get(a).apr());
    if (c == 0) c = Integer.compare(b.ordinal(), a.ordinal());
    return c == 0 ? aid.compareTo(bid) : c;
  }

  private static List<Bucket> buckets(EconomyCurrency c, List<Bucket> values) {
    Objects.requireNonNull(c);
    if (values.size() != 2) throw new IllegalArgumentException("Both buckets required");
    var kinds = EnumSet.noneOf(Kind.class);
    for (var b : values) {
      validate(c, b);
      if (!kinds.add(b.kind())) throw new IllegalArgumentException("Duplicate bucket");
    }
    return List.copyOf(values);
  }

  private static void validate(EconomyCurrency c, Bucket b) {
    Objects.requireNonNull(c);
    Objects.requireNonNull(b);
    BigDecimal half = BigDecimal.ONE.divide(BigDecimal.valueOf(c.minorUnitsPerMajor() * 2L), MC);
    if (b.residualMajor().abs().compareTo(half) > 0)
      throw new IllegalArgumentException("Residual exceeds half minor unit");
  }

  private static void requirements(List<Requirement> values) {
    bounded(values);
    var ids = new HashSet<String>();
    for (var r : values)
      if (!ids.add(r.id())) throw new IllegalArgumentException("Duplicate requirement");
  }

  private static void bounded(List<?> values) {
    Objects.requireNonNull(values);
    if (values.size() > MAX_ROWS) throw new IllegalArgumentException("Card page too large");
    values.forEach(Objects::requireNonNull);
  }

  private static String identity(String id) {
    Objects.requireNonNull(id);
    if (id.isBlank() || id.length() > 128) throw new IllegalArgumentException("Statement identity");
    return id;
  }

  private static BigDecimal exact(BigDecimal n) {
    Objects.requireNonNull(n);
    if (n.precision() > 50 || Math.abs(n.scale()) > 100)
      throw new IllegalArgumentException("Decimal bound");
    return n.setScale(18, RoundingMode.UNNECESSARY);
  }

  private static BigDecimal major(EconomyCurrency c, long minor) {
    return BigDecimal.valueOf(minor).divide(BigDecimal.valueOf(c.minorUnitsPerMajor()), MC);
  }

  private static long minor(EconomyCurrency c, BigDecimal major, RoundingMode mode) {
    return major
        .multiply(BigDecimal.valueOf(c.minorUnitsPerMajor()), MC)
        .setScale(0, mode)
        .longValueExact();
  }
}
