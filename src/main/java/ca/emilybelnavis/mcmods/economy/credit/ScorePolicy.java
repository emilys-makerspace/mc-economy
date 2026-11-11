package ca.emilybelnavis.mcmods.economy.credit;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Versioned pure score computation. Production reports and recurring dispatch belong to A6-10. */
public final class ScorePolicy {
  public static final int VERSION = 1, MAX_INPUTS = 4096;
  public static final long DAY_MILLIS = 1_200_000L, CYCLE_MILLIS = 28L * DAY_MILLIS;
  private static final long YEAR = 364L * DAY_MILLIS, INQUIRY_WINDOW = 84L * DAY_MILLIS;

  private ScorePolicy() {}

  /**
   * Later cures retain their reporting snapshot; only a data correction may replace that snapshot.
   */
  public record PaymentReport(
      UUID obligationId,
      UUID facilityId,
      long reportAt,
      long effectiveAt,
      long revision,
      boolean metAtSnapshot,
      boolean laterCure) {
    public PaymentReport {
      Objects.requireNonNull(obligationId);
      Objects.requireNonNull(facilityId);
      times(reportAt, effectiveAt, revision);
    }
  }

  /** Original default date is immutable across corrections, payment and debt sale. */
  public record DefaultReport(
      UUID defaultId,
      UUID facilityId,
      long defaultAt,
      long reportedAt,
      long effectiveAt,
      long revision,
      boolean valid) {
    public DefaultReport {
      Objects.requireNonNull(defaultId);
      Objects.requireNonNull(facilityId);
      times(reportedAt, effectiveAt, revision);
      if (defaultAt < 0 || defaultAt > reportedAt)
        throw new IllegalArgumentException("Default date");
    }
  }

  public record Inquiry(
      UUID applicationId, long acceptedAt, boolean acceptedForUnderwriting, boolean firstIssuance) {
    public Inquiry {
      Objects.requireNonNull(applicationId);
      if (acceptedAt < 0) throw new IllegalArgumentException("Inquiry instant");
    }
  }

  /**
   * Amounts share one captured valuation unit; never sum raw currencies. Closed/defaulted
   * facilities are excluded upstream.
   */
  public record Segment(
      long start,
      long end,
      long owed,
      long limit,
      boolean active,
      boolean complete,
      String sourceFingerprint) {
    public Segment(long start, long end, long owed, long limit, boolean active, boolean complete) {
      this(start, end, owed, limit, active, complete, "detached-common-unit");
    }

    public Segment {
      if (start < 0
          || end <= start
          || owed < 0
          || limit < 0
          || active && limit == 0
          || !active && (owed != 0 || limit != 0))
        throw new IllegalArgumentException("Utilization segment");
      Objects.requireNonNull(sourceFingerprint);
      if (sourceFingerprint.isBlank() || sourceFingerprint.length() > 256)
        throw new IllegalArgumentException("Utilization source");
    }
  }

  /**
   * Exact integral of portfolio owed/limit over active milliseconds, not a cutoff balance ratio.
   */
  public record Utilization(
      long start,
      long end,
      BigInteger areaNumerator,
      BigInteger areaDenominator,
      long activeMillis,
      boolean complete,
      String sourceFingerprint) {
    public Utilization {
      Objects.requireNonNull(areaNumerator);
      Objects.requireNonNull(areaDenominator);
      Objects.requireNonNull(sourceFingerprint);
      if (start < 0
          || end <= start
          || end - start > CYCLE_MILLIS
          || areaNumerator.signum() < 0
          || areaDenominator.signum() <= 0
          || activeMillis < 0
          || activeMillis > end - start
          || activeMillis == 0 && areaNumerator.signum() != 0
          || areaNumerator.bitLength() > 8192
          || areaDenominator.bitLength() > 8192
          || sourceFingerprint.isBlank()
          || sourceFingerprint.length() > 256)
        throw new IllegalArgumentException("Utilization area");
      var gcd = areaNumerator.gcd(areaDenominator);
      areaNumerator = areaNumerator.divide(gcd);
      areaDenominator = areaDenominator.divide(gcd);
    }
  }

  public record DailyArea(
      long dayStart,
      long start,
      long end,
      BigInteger areaNumerator,
      BigInteger areaDenominator,
      long activeMillis,
      long coverageMillis,
      int policyVersion,
      boolean complete,
      String sourceFingerprint) {
    public DailyArea {
      if (dayStart < 0
          || start < dayStart
          || end - dayStart > DAY_MILLIS
          || coverageMillis != end - start
          || policyVersion < 1) throw new IllegalArgumentException("Daily utilization interval");
      var checked =
          new Utilization(
              start,
              end,
              areaNumerator,
              areaDenominator,
              activeMillis,
              complete,
              sourceFingerprint);
      areaNumerator = checked.areaNumerator();
      areaDenominator = checked.areaDenominator();
    }
  }

  /**
   * Future facility owners persist these day-sized exact areas, including first/last partial days.
   */
  public static List<DailyArea> dailyAreas(
      long origin, long start, long end, List<Segment> segments, boolean complete) {
    accumulate(
        origin, start, end, segments,
        complete); // Validate complete coverage and budgets before splitting.
    var result = new ArrayList<DailyArea>();
    long position = start;
    while (position < end) {
      long dayStart = position - (position - origin) % DAY_MILLIS;
      long next =
          position + Math.min(end - position, DAY_MILLIS - (position - origin) % DAY_MILLIS);
      var clipped = new ArrayList<Segment>();
      for (var segment : segments)
        if (segment.end() > position && segment.start() < next)
          clipped.add(
              new Segment(
                  Math.max(position, segment.start()),
                  Math.min(next, segment.end()),
                  segment.owed(),
                  segment.limit(),
                  segment.active(),
                  segment.complete(),
                  segment.sourceFingerprint()));
      var area = accumulate(origin, position, next, clipped, complete);
      result.add(
          new DailyArea(
              dayStart,
              position,
              next,
              area.areaNumerator(),
              area.areaDenominator(),
              area.activeMillis(),
              next - position,
              VERSION,
              area.complete(),
              area.sourceFingerprint()));
      position = next;
    }
    return List.copyOf(result);
  }

  public static Utilization combineDaily(
      long start, long end, List<DailyArea> supplied, boolean complete) {
    if (supplied.size() > 29) throw new IllegalArgumentException("Daily utilization bound");
    var areas = new ArrayList<>(supplied);
    areas.sort(Comparator.comparingLong(DailyArea::start));
    long cursor = start, active = 0;
    BigInteger numerator = BigInteger.ZERO, denominator = BigInteger.ONE;
    var sources = new StringBuilder();
    for (var area : areas) {
      if (area.start() != cursor || area.end() > end || area.policyVersion() != VERSION)
        throw new IllegalArgumentException("Daily utilization gap/version");
      numerator =
          numerator
              .multiply(area.areaDenominator())
              .add(area.areaNumerator().multiply(denominator));
      denominator = denominator.multiply(area.areaDenominator());
      var gcd = numerator.gcd(denominator);
      numerator = numerator.divide(gcd);
      denominator = denominator.divide(gcd);
      if (numerator.bitLength() > 8192 || denominator.bitLength() > 8192)
        throw new IllegalArgumentException("Utilization precision budget");
      active = Math.addExact(active, area.activeMillis());
      complete &= area.complete();
      sources.append(area).append('\n');
      cursor = area.end();
    }
    if (cursor != end) throw new IllegalArgumentException("Daily utilization missing interval");
    return new Utilization(
        start, end, numerator, denominator, active, complete, hash(sources.toString()));
  }

  public record Input(
      long worldOrigin,
      long firstCreditAt,
      long boundary,
      List<PaymentReport> payments,
      List<DefaultReport> defaults,
      List<Inquiry> inquiries,
      Utilization utilization,
      boolean reportsComplete) {
    public Input {
      payments = List.copyOf(payments);
      defaults = List.copyOf(defaults);
      inquiries = List.copyOf(inquiries);
    }
  }

  /**
   * A6-10's bounded report fold supplies deduplicated/corrected counts and a digest of its
   * immutable source frontier.
   */
  public record SummaryInput(
      long worldOrigin,
      long firstCreditAt,
      long boundary,
      long met,
      long missed,
      long suppressed,
      long youngDefaults,
      long middleDefaults,
      long oldDefaults,
      List<Long> inquiryBuckets,
      Utilization utilization,
      boolean reportsComplete,
      String sourceDigest) {
    public SummaryInput {
      inquiryBuckets = List.copyOf(inquiryBuckets);
    }
  }

  public record Components(
      int baseline, int payment, int utilization, int age, int inquiry, int defaults) {}

  public record Result(
      boolean available,
      String reason,
      int policyVersion,
      long boundary,
      Components components,
      int score,
      String fingerprint,
      String snapshot,
      List<String> reasons) {
    public Result {
      reasons = List.copyOf(reasons);
    }
  }

  /**
   * Split every segment at world-day boundaries before summing; gaps and overlaps are unavailable.
   */
  public static Utilization accumulate(
      long origin, long start, long end, List<Segment> supplied, boolean complete) {
    if (origin < 0
        || start < origin
        || end <= start
        || end - start > CYCLE_MILLIS
        || supplied.size() > MAX_INPUTS) throw new IllegalArgumentException("Utilization interval");
    var segments = new ArrayList<>(supplied);
    segments.sort(Comparator.comparingLong(Segment::start).thenComparingLong(Segment::end));
    BigInteger numerator = BigInteger.ZERO, denominator = BigInteger.ONE;
    long active = 0, cursor = start;
    var source = new StringBuilder();
    for (var segment : segments) {
      if (segment.start() != cursor || segment.end() > end)
        throw new IllegalArgumentException("Utilization gap or overlap");
      complete &= segment.complete();
      source.append(segment).append('\n');
      long position = segment.start();
      while (position < segment.end()) {
        long offset = (position - origin) % DAY_MILLIS;
        long duration = Math.min(segment.end() - position, DAY_MILLIS - offset);
        if (segment.active()) {
          var add = BigInteger.valueOf(segment.owed()).multiply(BigInteger.valueOf(duration));
          var divisor = BigInteger.valueOf(segment.limit());
          numerator = numerator.multiply(divisor).add(add.multiply(denominator));
          denominator = denominator.multiply(divisor);
          var gcd = numerator.gcd(denominator);
          numerator = numerator.divide(gcd);
          denominator = denominator.divide(gcd);
          if (numerator.bitLength() > 8192 || denominator.bitLength() > 8192)
            throw new IllegalArgumentException("Utilization precision budget");
          active = Math.addExact(active, duration);
        }
        position += duration;
      }
      cursor = segment.end();
    }
    if (cursor != end) throw new IllegalArgumentException("Utilization missing interval");
    return new Utilization(
        start, end, numerator, denominator, active, complete, hash(source.toString()));
  }

  public static Result calculate(int version, Input input) {
    if (version != VERSION) return unavailable(input.boundary(), "SCORE_POLICY_UNAVAILABLE");
    return calculate(input);
  }

  public static Result calculate(Input input) {
    Objects.requireNonNull(input);
    String unavailable =
        readiness(
            input.worldOrigin(),
            input.firstCreditAt(),
            input.boundary(),
            input.utilization(),
            input.reportsComplete());
    if (unavailable != null) return unavailable(input.boundary(), unavailable);
    if (input.payments().size() > MAX_INPUTS
        || input.defaults().size() > MAX_INPUTS
        || input.inquiries().size() > MAX_INPUTS)
      return unavailable(input.boundary(), "SCORE_INPUT_BOUND");
    try {
      return compute(input);
    } catch (IllegalArgumentException malformed) {
      return unavailable(input.boundary(), "SCORE_INPUT_CONFLICT");
    }
  }

  public static Result calculate(int version, SummaryInput input) {
    if (version != VERSION) return unavailable(input.boundary(), "SCORE_POLICY_UNAVAILABLE");
    return calculate(input);
  }

  public static Result calculate(SummaryInput input) {
    Objects.requireNonNull(input);
    String unavailable =
        readiness(
            input.worldOrigin(),
            input.firstCreditAt(),
            input.boundary(),
            input.utilization(),
            input.reportsComplete());
    if (unavailable != null) return unavailable(input.boundary(), unavailable);
    if (input.sourceDigest() == null
        || input.sourceDigest().length() != 64
        || !input.sourceDigest().matches("[0-9a-f]{64}"))
      return unavailable(input.boundary(), "REPORT_SOURCE_UNAVAILABLE");
    if (input.met() < 0
        || input.missed() < 0
        || input.suppressed() < 0
        || input.youngDefaults() < 0
        || input.middleDefaults() < 0
        || input.oldDefaults() < 0
        || input.inquiryBuckets().size() > 13)
      return unavailable(input.boundary(), "SCORE_SUMMARY_CONFLICT");
    var buckets = new TreeSet<>(input.inquiryBuckets());
    long last = (input.boundary() - input.worldOrigin()) / (7L * DAY_MILLIS);
    long first =
        Math.max(0, (input.boundary() - input.worldOrigin() - INQUIRY_WINDOW) / (7L * DAY_MILLIS));
    if (buckets.size() != input.inquiryBuckets().size()
        || buckets.stream().anyMatch(bucket -> bucket < first || bucket > last))
      return unavailable(input.boundary(), "SCORE_SUMMARY_CONFLICT");
    return finish(
        input.worldOrigin(),
        input.firstCreditAt(),
        input.boundary(),
        input.met(),
        input.missed(),
        input.suppressed(),
        input.youngDefaults(),
        input.middleDefaults(),
        input.oldDefaults(),
        buckets,
        input.utilization(),
        input.sourceDigest());
  }

  private static String readiness(
      long origin, long first, long boundary, Utilization utilization, boolean complete) {
    if (origin < 0
        || first < origin
        || boundary < first
        || boundary - origin < CYCLE_MILLIS
        || (boundary - origin) % CYCLE_MILLIS != 0) return "SCORE_BOUNDARY";
    if (!complete) return "REPORTS_UNAVAILABLE";
    if (utilization == null
        || !utilization.complete()
        || utilization.start() != boundary - CYCLE_MILLIS
        || utilization.end() != boundary
        || utilization.activeMillis() > boundary - Math.max(first, utilization.start()))
      return "UTILIZATION_UNAVAILABLE";
    return null;
  }

  private static Result compute(Input input) {
    long boundary = input.boundary();
    var paymentVersions = new TreeMap<String, PaymentReport>();
    var defaultVersions = new TreeMap<String, DefaultReport>();
    var identities = new HashMap<UUID, String>();
    var source = new TreeSet<String>();
    for (var report : input.payments())
      if (report.effectiveAt() <= boundary && report.reportAt() <= boundary) {
        String identity = report.facilityId() + "|" + report.reportAt();
        if (!identity.equals(
            identities.computeIfAbsent(report.obligationId(), ignored -> identity)))
          throw new IllegalArgumentException("Payment identity changed");
        var prior =
            paymentVersions.putIfAbsent(report.obligationId() + "|" + report.revision(), report);
        if (prior != null && !prior.equals(report))
          throw new IllegalArgumentException("Payment correction conflict");
        source.add("P|" + report);
      }
    var payments = new HashMap<UUID, PaymentReport>();
    for (var report : paymentVersions.values())
      if (!report.laterCure())
        payments.merge(
            report.obligationId(), report, (a, b) -> a.revision() > b.revision() ? a : b);
    for (var report : paymentVersions.values())
      if (!payments.containsKey(report.obligationId()))
        throw new IllegalArgumentException("Missing reporting snapshot");
    identities.clear();
    for (var report : input.defaults())
      if (report.effectiveAt() <= boundary && report.reportedAt() <= boundary) {
        String identity =
            report.facilityId() + "|" + report.defaultAt() + "|" + report.reportedAt();
        if (!identity.equals(identities.computeIfAbsent(report.defaultId(), ignored -> identity)))
          throw new IllegalArgumentException("Default date changed");
        var prior =
            defaultVersions.putIfAbsent(report.defaultId() + "|" + report.revision(), report);
        if (prior != null && !prior.equals(report))
          throw new IllegalArgumentException("Default correction conflict");
        source.add("D|" + report);
      }
    var defaults = new HashMap<UUID, DefaultReport>();
    for (var report : defaultVersions.values())
      defaults.merge(report.defaultId(), report, (a, b) -> a.revision() > b.revision() ? a : b);
    var suppressed = new HashSet<UUID>();
    int young = 0, middle = 0, old = 0;
    for (var report : defaults.values())
      if (report.valid()) {
        long age = boundary - report.defaultAt();
        if (age >= 2548L * DAY_MILLIS) continue;
        if (!suppressed.add(report.facilityId()))
          throw new IllegalArgumentException("Duplicate facility default");
        if (age < YEAR) young++;
        else if (age < 2 * YEAR) middle++;
        else old++;
      }
    int met = 0, missed = 0, omitted = 0;
    for (var report : payments.values())
      if (report.reportAt() > boundary - YEAR && report.reportAt() <= boundary) {
        if (suppressed.contains(report.facilityId())) omitted++;
        else if (report.metAtSnapshot()) met++;
        else missed++;
      }
    var applications = new HashMap<UUID, Inquiry>();
    var buckets = new TreeSet<Long>();
    for (var inquiry : input.inquiries())
      if (inquiry.acceptedAt() <= boundary) {
        var prior = applications.putIfAbsent(inquiry.applicationId(), inquiry);
        if (prior != null && !prior.equals(inquiry))
          throw new IllegalArgumentException("Inquiry conflict");
        source.add("I|" + inquiry);
        if (inquiry.acceptedAt() < input.worldOrigin())
          throw new IllegalArgumentException("Inquiry before origin");
        if (inquiry.acceptedForUnderwriting()
            && !inquiry.firstIssuance()
            && inquiry.acceptedAt() > boundary - INQUIRY_WINDOW)
          buckets.add((inquiry.acceptedAt() - input.worldOrigin()) / (7L * DAY_MILLIS));
      }
    return finish(
        input.worldOrigin(),
        input.firstCreditAt(),
        boundary,
        met,
        missed,
        omitted,
        young,
        middle,
        old,
        buckets,
        input.utilization(),
        hash(String.join("\n", source)));
  }

  private static Result finish(
      long origin,
      long first,
      long boundary,
      long met,
      long missed,
      long omitted,
      long young,
      long middle,
      long old,
      SortedSet<Long> buckets,
      Utilization utilization,
      String sourceDigest) {
    int utilizationPoints = utilizationPoints(utilization);
    long cycles = (boundary - first) / CYCLE_MILLIS;
    int age = (int) Math.min(90, Math.multiplyExact(2, Math.min(45, cycles)));
    var payment =
        BigInteger.valueOf(met)
            .multiply(BigInteger.valueOf(4))
            .subtract(BigInteger.valueOf(missed).multiply(BigInteger.valueOf(15)));
    var defaults =
        BigInteger.valueOf(young)
            .multiply(BigInteger.valueOf(120))
            .add(BigInteger.valueOf(middle).multiply(BigInteger.valueOf(90)))
            .add(BigInteger.valueOf(old).multiply(BigInteger.valueOf(60)));
    var components =
        new Components(
            650,
            clamp(-90, 100, payment),
            utilizationPoints,
            age,
            Math.min(30, buckets.size() * 5),
            clamp(0, 360, defaults));
    int score =
        clamp(
            300,
            900,
            650
                + components.payment()
                + components.utilization()
                + components.age()
                - components.inquiry()
                - components.defaults());
    String snapshot =
        String.join(
            "|",
            "score-policy",
            Integer.toString(VERSION),
            Long.toString(origin),
            Long.toString(first),
            Long.toString(boundary),
            Long.toString(met),
            Long.toString(missed),
            Long.toString(omitted),
            Long.toString(young),
            Long.toString(middle),
            Long.toString(old),
            buckets.toString(),
            Long.toString(cycles),
            utilization.areaNumerator().toString(),
            utilization.areaDenominator().toString(),
            Long.toString(utilization.activeMillis()),
            utilization.sourceFingerprint(),
            sourceDigest);
    var reasons = new ArrayList<String>();
    reasons.add("BASELINE_650");
    reasons.add("PAYMENTS_" + components.payment());
    reasons.add("UTILIZATION_" + utilizationPoints);
    reasons.add("AGE_" + age);
    reasons.add("INQUIRIES_" + components.inquiry());
    reasons.add("DEFAULTS_" + components.defaults());
    if (omitted > 0) reasons.add("DEFAULT_PAYMENT_SUPPRESSION");
    return new Result(
        true,
        "SCORE_READY",
        VERSION,
        boundary,
        components,
        score,
        hash(snapshot),
        snapshot,
        reasons);
  }

  public static int utilizationPoints(Utilization area) {
    if (area.activeMillis() == 0) return 0;
    if (area.areaNumerator().signum() == 0) return 30;
    var numerator = area.areaNumerator();
    var denominator = area.areaDenominator().multiply(BigInteger.valueOf(area.activeMillis()));
    if (numerator.multiply(BigInteger.TEN).compareTo(denominator) <= 0) return 60;
    if (numerator.multiply(BigInteger.TEN).compareTo(denominator.multiply(BigInteger.valueOf(3)))
        <= 0) return 30;
    if (numerator.multiply(BigInteger.TWO).compareTo(denominator) <= 0) return 0;
    if (numerator
            .multiply(BigInteger.valueOf(4))
            .compareTo(denominator.multiply(BigInteger.valueOf(3)))
        <= 0) return -30;
    if (numerator.compareTo(denominator) <= 0) return -60;
    return -90;
  }

  private static Result unavailable(long boundary, String reason) {
    return new Result(
        false,
        reason,
        VERSION,
        boundary,
        new Components(650, 0, 0, 0, 0, 0),
        650,
        "",
        "",
        List.of(reason));
  }

  private static int clamp(int min, int max, int value) {
    return Math.max(min, Math.min(max, value));
  }

  private static int clamp(int min, int max, BigInteger value) {
    return value.max(BigInteger.valueOf(min)).min(BigInteger.valueOf(max)).intValueExact();
  }

  private static void times(long report, long effective, long revision) {
    if (report < 0 || effective < report || revision < 1)
      throw new IllegalArgumentException("Report revision/instant");
  }

  public static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
