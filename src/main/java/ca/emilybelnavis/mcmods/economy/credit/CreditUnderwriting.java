package ca.emilybelnavis.mcmods.economy.credit;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Money;
import java.math.*;
import java.util.*;

/**
 * Pure version-one offer ceilings. It neither originates a facility nor changes a contractual limit
 * or debt.
 */
public final class CreditUnderwriting {
  public static final int VERSION = 1, MAX_EXPOSURES = 4096;

  public enum Product {
    BANK_LOAN,
    SUBPRIME_LOAN
  }

  public enum ExposureKind {
    OPEN_REVOLVING,
    LOAN,
    CLOSED_REVOLVING,
    COLLECTION
  }

  public record Exposure(
      UUID liabilityId,
      ExposureKind kind,
      long contractualLimit,
      long principal,
      long unpaidInterest,
      boolean collectable,
      boolean extinguished) {
    public Exposure {
      Objects.requireNonNull(liabilityId);
      Objects.requireNonNull(kind);
      if (contractualLimit < 0 || principal < 0 || unpaidInterest < 0)
        throw new IllegalArgumentException("Exposure amount");
    }
  }

  public record Band(
      long principalMajor, long exposureMajor, BigDecimal premiumApr, int maximumYears) {}

  public record LoanRequest(
      boolean established,
      int score,
      EconomyCurrency worldCurrency,
      Product product,
      boolean tierTwo,
      boolean tierEligible,
      boolean inputsComplete,
      boolean creditBlocked,
      long currentExposureMinor,
      long requestedMinor,
      int years,
      BigDecimal primeApr) {
    public LoanRequest {
      checkScore(score);
      Objects.requireNonNull(worldCurrency);
      Objects.requireNonNull(product);
      Objects.requireNonNull(primeApr);
      if (currentExposureMinor < 0) throw new IllegalArgumentException("Exposure");
    }
  }

  public record LoanOffer(
      boolean available,
      boolean approved,
      String reason,
      int policyVersion,
      Money maximumPrincipal,
      Money totalExposureCeiling,
      BigDecimal apr,
      int maximumYears) {}

  public record RevolvingRequest(
      boolean established,
      boolean starterConsumed,
      int score,
      EconomyCurrency worldCurrency,
      boolean tierTwo,
      boolean tierEligible,
      boolean inputsComplete,
      boolean creditBlocked,
      int openFacilities,
      long committedLimitMinor,
      long currentExposureMinor,
      long requestedMinor) {
    public RevolvingRequest {
      checkScore(score);
      Objects.requireNonNull(worldCurrency);
      if (openFacilities < 0 || committedLimitMinor < 0 || currentExposureMinor < 0)
        throw new IllegalArgumentException("Revolving exposure");
    }
  }

  public record RevolvingOffer(
      boolean available,
      boolean approved,
      String reason,
      int policyVersion,
      int capturedScore,
      Money combinedLimitCeiling,
      Money maximumNewLimit,
      Money totalExposureCeiling) {}

  private CreditUnderwriting() {}

  public static long revolvingCeiling(int score) {
    checkScore(score);
    if (score < 500) return 0;
    if (score < 580) return 1_000;
    if (score < 650) return 2_500;
    if (score < 700) return 5_000;
    if (score < 750) return 15_000;
    if (score < 800) return 50_000;
    if (score < 850) return 150_000;
    return 500_000;
  }

  public static Band bankBand(int score) {
    checkScore(score);
    if (score < 620) return new Band(0, 0, BigDecimal.ZERO, 0);
    if (score < 650) return new Band(25_000, 50_000, new BigDecimal("0.06"), 2);
    if (score < 700) return new Band(100_000, 150_000, new BigDecimal("0.04"), 3);
    if (score < 750) return new Band(500_000, 750_000, new BigDecimal("0.02"), 5);
    if (score < 800) return new Band(2_000_000, 3_000_000, new BigDecimal("0.01"), 5);
    if (score < 850) return new Band(10_000_000, 15_000_000, new BigDecimal("0.005"), 5);
    return new Band(50_000_000, 75_000_000, BigDecimal.ZERO, 5);
  }

  public static Band subprimeBand(int score) {
    checkScore(score);
    if (score < 500) return new Band(0, 0, BigDecimal.ZERO, 0);
    if (score < 550) return new Band(10_000, 15_000, new BigDecimal("0.3499"), 3);
    if (score < 600) return new Band(25_000, 40_000, new BigDecimal("0.2999"), 5);
    if (score < 650) return new Band(50_000, 75_000, new BigDecimal("0.2499"), 8);
    return new Band(100_000, 150_000, new BigDecimal("0.1999"), 8);
  }

  public static LoanOffer loan(LoanRequest request) {
    return loan(request, false);
  }

  private static LoanOffer loan(LoanRequest request, boolean nativeWholeMajorValidated) {
    var currency = request.worldCurrency();
    var band =
        request.product() == Product.BANK_LOAN
            ? bankBand(request.score())
            : subprimeBand(request.score());
    prime(request.primeApr());
    long multiplier = request.product() == Product.BANK_LOAN && request.tierTwo() ? 4 : 1;
    var maximum =
        new Money(currency, minor(Math.multiplyExact(band.principalMajor(), multiplier), currency));
    var ceiling =
        new Money(currency, minor(Math.multiplyExact(band.exposureMajor(), multiplier), currency));
    var apr =
        request.product() == Product.SUBPRIME_LOAN
            ? band.premiumApr()
            : request.tierTwo()
                ? request
                    .primeApr()
                    .divide(BigDecimal.valueOf(4))
                    .add(band.premiumApr().divide(BigDecimal.valueOf(4)))
                : request.primeApr().add(new BigDecimal("0.02")).add(band.premiumApr());
    String reason = "APPROVED";
    boolean available = request.inputsComplete();
    if (!available) reason = "UNDERWRITING_INPUTS_UNAVAILABLE";
    else if (!request.established()) reason = "CREDIT_UNESTABLISHED";
    else if (request.product() == Product.BANK_LOAN && request.tierTwo() && !request.tierEligible())
      reason = "TIER2_UNAVAILABLE";
    else if (request.creditBlocked()) reason = "ARREARS_DEFAULT_OR_COLLECTION";
    else if (band.principalMajor() == 0) reason = "SCORE_BELOW_PRODUCT_FLOOR";
    else if (request.years() < 1 || request.years() > band.maximumYears())
      reason = "TERM_NOT_PERMITTED";
    else if (!nativeWholeMajorValidated
        && request.requestedMinor() % currency.minorUnitsPerMajor() != 0)
      reason = "WHOLE_MAJOR_PRINCIPAL_REQUIRED";
    else if (request.requestedMinor()
        < minor(request.product() == Product.BANK_LOAN ? 1_000 : 500, currency))
      reason = "PRINCIPAL_BELOW_MINIMUM";
    else if (request.requestedMinor() > maximum.minorUnits()) reason = "PRODUCT_PRINCIPAL_CEILING";
    else if (exceeds(
        request.currentExposureMinor(), request.requestedMinor(), ceiling.minorUnits()))
      reason = "OWNER_EXPOSURE_CEILING";
    return new LoanOffer(
        available,
        reason.equals("APPROVED"),
        reason,
        VERSION,
        maximum,
        ceiling,
        apr,
        band.maximumYears());
  }

  public static RevolvingOffer revolving(RevolvingRequest request) {
    return revolving(request, false);
  }

  private static RevolvingOffer revolving(
      RevolvingRequest request, boolean nativeWholeMajorValidated) {
    var currency = request.worldCurrency();
    int captured = request.established() ? request.score() : 650;
    long combined = minor(revolvingCeiling(captured), currency),
        exposure = minor(exposureCeiling(captured, request.tierTwo()), currency);
    long limitRoom = Math.max(0, combined - request.committedLimitMinor());
    long exposureRoom = Math.max(0, exposure - request.currentExposureMinor());
    long maximum = Math.min(limitRoom, exposureRoom);
    if (!request.established()) maximum = Math.min(maximum, minor(2_500, currency));
    String reason = "APPROVED";
    boolean available = request.inputsComplete();
    if (!available) reason = "UNDERWRITING_INPUTS_UNAVAILABLE";
    else if (request.tierTwo() && !request.tierEligible()) reason = "TIER2_UNAVAILABLE";
    else if (request.creditBlocked()) reason = "ARREARS_DEFAULT_OR_COLLECTION";
    else if (!request.established()
        && (request.starterConsumed()
            || request.openFacilities() != 0
            || request.committedLimitMinor() != 0)) reason = "STARTER_ALREADY_CONSUMED";
    else if (request.openFacilities() >= 3) reason = "REVOLVING_FACILITY_CAP";
    else if (captured < 500) reason = "SCORE_BELOW_PRODUCT_FLOOR";
    else if (maximum < minor(500, currency)) reason = "REVOLVING_HEADROOM_BELOW_MINIMUM";
    else if (!request.established() && request.requestedMinor() != minor(2_500, currency))
      reason = "STARTER_EXACT_2500";
    else if (!nativeWholeMajorValidated
        && request.requestedMinor() % currency.minorUnitsPerMajor() != 0)
      reason = "WHOLE_MAJOR_LIMIT_REQUIRED";
    else if (request.requestedMinor() < minor(500, currency)) reason = "LIMIT_BELOW_MINIMUM";
    else if (request.requestedMinor() > maximum) reason = "COMBINED_REVOLVING_CEILING";
    return new RevolvingOffer(
        available,
        reason.equals("APPROVED"),
        reason,
        VERSION,
        captured,
        new Money(currency, combined),
        new Money(currency, maximum),
        new Money(currency, exposure));
  }

  public record NativeLoanOffer(
      LoanOffer worldOffer,
      Money requestedNative,
      Money normalizedWorldPrincipal,
      Money maximumNativePrincipal) {}

  public record NativeRevolvingOffer(
      RevolvingOffer worldOffer,
      Money requestedNative,
      Money normalizedWorldLimit,
      Money maximumNativeLimit) {}

  /**
   * The original amount is whole-major in the contract currency; its conservative world value may
   * contain world cents.
   */
  public static NativeLoanOffer loanForFacility(
      LoanRequest terms, Money requestedNative, FxSnapshot fx) {
    if (requestedNative.minorUnits() <= 0
        || requestedNative.minorUnits() % requestedNative.currency().minorUnitsPerMajor() != 0)
      throw new IllegalArgumentException("WHOLE_MAJOR_PRINCIPAL_REQUIRED");
    var normalized = exposureToWorld(requestedNative, terms.worldCurrency(), fx);
    var request =
        new LoanRequest(
            terms.established(),
            terms.score(),
            terms.worldCurrency(),
            terms.product(),
            terms.tierTwo(),
            terms.tierEligible(),
            terms.inputsComplete(),
            terms.creditBlocked(),
            terms.currentExposureMinor(),
            normalized.minorUnits(),
            terms.years(),
            terms.primeApr());
    var offer = loan(request, true);
    long room =
        Math.max(0, offer.totalExposureCeiling().minorUnits() - terms.currentExposureMinor());
    var ceiling =
        new Money(terms.worldCurrency(), Math.min(offer.maximumPrincipal().minorUnits(), room));
    return new NativeLoanOffer(
        offer,
        requestedNative,
        normalized,
        ceilingToFacility(ceiling, requestedNative.currency(), fx));
  }

  public static NativeRevolvingOffer revolvingForFacility(
      RevolvingRequest terms, Money requestedNative, FxSnapshot fx) {
    if (requestedNative.minorUnits() <= 0
        || requestedNative.minorUnits() % requestedNative.currency().minorUnitsPerMajor() != 0)
      throw new IllegalArgumentException("WHOLE_MAJOR_LIMIT_REQUIRED");
    if (!terms.established() && requestedNative.currency() != terms.worldCurrency())
      throw new IllegalArgumentException("STARTER_WORLD_CURRENCY");
    var normalized = exposureToWorld(requestedNative, terms.worldCurrency(), fx);
    var request =
        new RevolvingRequest(
            terms.established(),
            terms.starterConsumed(),
            terms.score(),
            terms.worldCurrency(),
            terms.tierTwo(),
            terms.tierEligible(),
            terms.inputsComplete(),
            terms.creditBlocked(),
            terms.openFacilities(),
            terms.committedLimitMinor(),
            terms.currentExposureMinor(),
            normalized.minorUnits());
    var offer = revolving(request, true);
    return new NativeRevolvingOffer(
        offer,
        requestedNative,
        normalized,
        ceilingToFacility(offer.maximumNewLimit(), requestedNative.currency(), fx));
  }

  /** Combined debt count, not principal plus the same committed line or a second sold-debt copy. */
  public static long exposure(List<Exposure> inputs) {
    if (inputs.size() > MAX_EXPOSURES) throw new IllegalArgumentException("Exposure bound");
    var seen = new HashMap<UUID, Exposure>();
    long total = 0;
    for (var item : inputs) {
      var prior = seen.putIfAbsent(item.liabilityId(), item);
      if (prior != null) {
        if (!prior.equals(item))
          throw new IllegalArgumentException("Conflicting liability representations");
        continue;
      }
      if (item.extinguished()) continue;
      long owed = Math.addExact(item.principal(), item.unpaidInterest());
      total =
          Math.addExact(
              total,
              item.kind() == ExposureKind.OPEN_REVOLVING
                  ? Math.max(item.contractualLimit(), owed)
                  : owed);
    }
    return total;
  }

  /**
   * Contractual limit/owed/draw use one facility minor unit. currentExposure uses world minor
   * units; it already counts max(limit, owed) once.
   */
  public static boolean drawAllowed(
      int score,
      boolean tierTwo,
      boolean creditBlocked,
      long contractualLimit,
      long owed,
      long currentExposure,
      long draw,
      EconomyCurrency worldCurrency) {
    return drawAllowed(
        score,
        tierTwo,
        creditBlocked,
        contractualLimit,
        owed,
        currentExposure,
        draw,
        worldCurrency,
        false);
  }

  /** The trusted integration flag bypasses headroom only, never credit risk restrictions. */
  public static boolean drawAllowed(
      int score,
      boolean tierTwo,
      boolean creditBlocked,
      long contractualLimit,
      long owed,
      long currentExposure,
      long draw,
      EconomyCurrency worldCurrency,
      boolean allowOverLimit) {
    checkScore(score);
    if (contractualLimit < 0 || owed < 0 || currentExposure < 0 || draw <= 0)
      throw new IllegalArgumentException("Draw input");
    if (score < 500
        || creditBlocked
        || !allowOverLimit && (owed > contractualLimit || exceeds(owed, draw, contractualLimit)))
      return false;
    return currentExposure <= minor(exposureCeiling(score, tierTwo), worldCurrency);
  }

  public static long exposureCeiling(int score, boolean tierTwo) {
    checkScore(score);
    if (score < 500) return 0;
    long product =
        score < 620
            ? subprimeBand(score).exposureMajor()
            : Math.multiplyExact(bankBand(score).exposureMajor(), tierTwo ? 4 : 1);
    return Math.max(revolvingCeiling(score), product);
  }

  /** One immutable fresh A2 publication supplies all normalized exposure and ceiling inputs. */
  public record FxSnapshot(
      UUID publication,
      long effectiveAt,
      long capturedAt,
      Map<EconomyCurrency, BigDecimal> rates,
      boolean complete) {
    public FxSnapshot {
      Objects.requireNonNull(publication);
      rates = Map.copyOf(rates);
      if (effectiveAt < 0 || capturedAt < 0) throw new IllegalArgumentException("FX instant");
      for (var rate : rates.values())
        if (rate.scale() > 12
            || rate.scale() < -12
            || rate.precision() > 19
            || rate.signum() <= 0
            || rate.compareTo(new BigDecimal("1000000")) > 0)
          throw new IllegalArgumentException("FX rate precision/range");
    }

    public boolean available() {
      return complete && effectiveAt <= capturedAt && capturedAt - effectiveAt <= 172_800_000L;
    }
  }

  public static Money exposureToWorld(Money amount, EconomyCurrency world, FxSnapshot snapshot) {
    return convert(amount, world, snapshot, RoundingMode.CEILING);
  }

  public static Money ceilingToFacility(
      Money ceiling, EconomyCurrency facility, FxSnapshot snapshot) {
    return convert(ceiling, facility, snapshot, RoundingMode.FLOOR);
  }

  private static Money convert(
      Money amount, EconomyCurrency target, FxSnapshot snapshot, RoundingMode rounding) {
    Objects.requireNonNull(amount);
    Objects.requireNonNull(target);
    Objects.requireNonNull(snapshot);
    if (amount.minorUnits() < 0 || !snapshot.available())
      throw new IllegalArgumentException("FX_UNAVAILABLE");
    var sourceRate = snapshot.rates().get(amount.currency());
    var targetRate = snapshot.rates().get(target);
    if (sourceRate == null
        || targetRate == null
        || sourceRate.signum() <= 0
        || targetRate.signum() <= 0) throw new IllegalArgumentException("FX_UNAVAILABLE");
    var numerator =
        BigDecimal.valueOf(amount.minorUnits())
            .multiply(sourceRate)
            .multiply(BigDecimal.valueOf(target.minorUnitsPerMajor()));
    var denominator =
        targetRate.multiply(BigDecimal.valueOf(amount.currency().minorUnitsPerMajor()));
    return new Money(target, numerator.divide(denominator, 0, rounding).longValueExact());
  }

  private static boolean exceeds(long exposure, long added, long ceiling) {
    try {
      return Math.addExact(exposure, added) > ceiling;
    } catch (ArithmeticException overflow) {
      return true;
    }
  }

  private static long minor(long major, EconomyCurrency currency) {
    return Math.multiplyExact(major, currency.minorUnitsPerMajor());
  }

  private static void checkScore(int score) {
    if (score < 300 || score > 900)
      throw new IllegalArgumentException("Score outside policy range");
  }

  private static void prime(BigDecimal apr) {
    if (apr.signum() < 0
        || apr.compareTo(new BigDecimal("0.50")) > 0
        || apr.stripTrailingZeros().scale() > 6)
      throw new IllegalArgumentException("Prime precision/range");
  }
}
