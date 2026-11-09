package ca.emilybelnavis.mcmods.economy.loan;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import java.math.*;
import java.util.*;

/**
 * Pure fixed-loan version-one accounting. Admission, chronology and postings belong to the worker.
 */
public final class LoanServicing {
  public static final int VERSION = 1, MAX_REQUIREMENTS = 104;
  public static final long DAY = 1_200_000L, MONTH = 28 * DAY;
  public static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(18);

  public record Requirement(
      String id,
      long dueAt,
      long originalMinor,
      long unmetMinor,
      long principalMinor,
      long interestMinor) {
    public Requirement {
      Objects.requireNonNull(id);
      if (id.isBlank()
          || id.length() > 128
          || dueAt < 0
          || originalMinor < 0
          || unmetMinor < 0
          || unmetMinor > originalMinor
          || principalMinor < 0
          || interestMinor < 0
          || Math.addExact(principalMinor, interestMinor) != unmetMinor)
        throw new IllegalArgumentException("Loan requirement");
    }
  }

  public record State(
      EconomyCurrency currency,
      BigDecimal apr,
      long principalMinor,
      long interestMinor,
      BigDecimal residualMajor,
      BigDecimal accruedMajor,
      BigDecimal capitalizedInterestMajor,
      long periodStart,
      long lastAccrued,
      long installmentMinor,
      int completedPeriods,
      int totalPeriods,
      boolean closed,
      List<Requirement> requirements) {
    public State {
      Objects.requireNonNull(currency);
      apr = rate(apr);
      residualMajor = exact(residualMajor);
      accruedMajor = exact(accruedMajor);
      capitalizedInterestMajor = exact(capitalizedInterestMajor);
      requirements = List.copyOf(requirements);
      if (principalMinor < 0
          || interestMinor < 0
          || accruedMajor.signum() < 0
          || periodStart < 0
          || lastAccrued < periodStart
          || lastAccrued > Math.addExact(periodStart, MONTH)
          || installmentMinor < 1
          || completedPeriods < 0
          || totalPeriods < 1
          || totalPeriods > 104
          || requirements.size() > MAX_REQUIREMENTS)
        throw new IllegalArgumentException("Loan state");
      long required = 0, p = 0, i = 0;
      var ids = new HashSet<String>();
      for (var r : requirements) {
        if (!ids.add(r.id())) throw new IllegalArgumentException("Duplicate obligation");
        required = Math.addExact(required, r.unmetMinor());
        p = Math.addExact(p, r.principalMinor());
        i = Math.addExact(i, r.interestMinor());
      }
      if (required > Math.addExact(principalMinor, interestMinor)
          || p > principalMinor
          || i > interestMinor
          || residualMajor.abs().compareTo(halfMinor(currency)) > 0
          || capitalizedInterestMajor.compareTo(halfMinor(currency).negate()) < 0
          || capitalizedInterestMajor.compareTo(
                  major(currency, interestMinor).add(residualMajor).max(BigDecimal.ZERO))
              > 0
          || major(currency, Math.addExact(principalMinor, interestMinor))
                  .add(residualMajor)
                  .add(accruedMajor)
                  .signum()
              < 0) throw new IllegalArgumentException("Loan state reconciliation");
      if (closed
          && (principalMinor != 0
              || interestMinor != 0
              || residualMajor.signum() != 0
              || accruedMajor.signum() != 0
              || capitalizedInterestMajor.signum() != 0
              || required != 0)) throw new IllegalArgumentException("Closed loan debt");
    }

    public long debtMinor() {
      return Math.addExact(principalMinor, interestMinor);
    }

    public BigDecimal exactDebtMajor() {
      return major(currency, debtMinor()).add(residualMajor).add(accruedMajor);
    }

    public long nextDue() {
      return Math.addExact(periodStart, MONTH);
    }

    public long requiredMinor() {
      long n = 0;
      for (var r : requirements) n = Math.addExact(n, r.unmetMinor());
      return n;
    }
  }

  public record Installment(
      int ordinal,
      long principalMinor,
      long interestMinor,
      long paymentMinor,
      long remainingPrincipalMinor,
      BigDecimal residualDisposition) {}

  public record Schedule(
      int version,
      long initialMinor,
      BigDecimal apr,
      int periods,
      long installmentMinor,
      List<Installment> installments) {
    public Schedule {
      installments = List.copyOf(installments);
    }
  }

  public record Close(
      State state, BigDecimal earnedMajor, long postedInterestMinor, Requirement requirement) {}

  public record Allocation(String requirementId, boolean interest, long minor) {}

  public record Payment(
      State state,
      long recognizedInterestMinor,
      long principalPaid,
      long interestPaid,
      List<Allocation> allocations,
      BigDecimal residualDisposition,
      BigDecimal additionalInterestWaived) {
    public Payment {
      allocations = List.copyOf(allocations);
    }
  }

  public record Payoff(
      State source, State quotedState, long capturedAt, long expiresAt, long amountMinor) {}

  private LoanServicing() {}

  public static long installment(
      EconomyCurrency currency, long principal, BigDecimal apr, int periods) {
    Objects.requireNonNull(currency);
    apr = rate(apr);
    if (principal <= 0 || periods < 1 || periods > 104)
      throw new IllegalArgumentException("Loan schedule inputs");
    var r = apr.divide(BigDecimal.valueOf(13), MC);
    var p = major(currency, principal);
    BigDecimal payment;
    if (r.signum() == 0) payment = p.divide(BigDecimal.valueOf(periods), MC);
    else {
      var growth = BigDecimal.ONE.add(r, MC).pow(periods, MC);
      payment =
          p.multiply(r, MC).multiply(growth, MC).divide(growth.subtract(BigDecimal.ONE, MC), MC);
    }
    return minor(currency, payment, RoundingMode.CEILING);
  }

  private static int periods(int years, boolean subprime) {
    if (years < 1 || years > (subprime ? 8 : 5)) throw new IllegalArgumentException("Loan term");
    return Math.multiplyExact(13, years);
  }

  public static State initial(
      EconomyCurrency currency,
      long principal,
      BigDecimal apr,
      int years,
      boolean subprime,
      long anchor) {
    int n = periods(years, subprime);
    return new State(
        currency,
        apr,
        principal,
        0,
        ZERO,
        ZERO,
        ZERO,
        anchor,
        anchor,
        installment(currency, principal, apr, n),
        0,
        n,
        false,
        List.of());
  }

  public static Schedule schedule(
      EconomyCurrency currency, long principal, BigDecimal apr, int years, boolean subprime) {
    var state = initial(currency, principal, apr, years, subprime, 0);
    var result = new ArrayList<Installment>();
    long fixed = state.installmentMinor();
    int n = state.totalPeriods();
    for (int ordinal = 1; ordinal <= n && !state.closed(); ordinal++) {
      var close = monthClose(state, "schedule-" + ordinal, state.nextDue());
      state = close.state();
      long amount = ordinal == n ? state.debtMinor() : Math.min(fixed, state.debtMinor());
      if (amount == 0) break;
      var payment = repay(state, amount, state.lastAccrued());
      state = payment.state();
      result.add(
          new Installment(
              ordinal,
              payment.principalPaid(),
              payment.interestPaid(),
              amount,
              state.principalMinor(),
              payment.residualDisposition()));
    }
    if (!state.closed()) throw new IllegalStateException("Schedule failed to close");
    return new Schedule(VERSION, principal, rate(apr), n, fixed, result);
  }

  /**
   * Current-period earned interest is a simple segmented accumulator, never its own compounding
   * base.
   */
  public static State accrue(State s, long now) {
    Objects.requireNonNull(s);
    if (now < s.lastAccrued()) throw new IllegalArgumentException("Loan time moved backward");
    if (s.closed()) return s;
    if (now > s.nextDue())
      throw new IllegalArgumentException("Loan accrual requires chronological month close");
    if (now == s.lastAccrued()) return s;
    var basis =
        major(s.currency(), s.principalMinor())
            .add(s.capitalizedInterestMajor(), MC)
            .max(BigDecimal.ZERO);
    var earned =
        basis
            .multiply(s.apr(), MC)
            .divide(BigDecimal.valueOf(13), MC)
            .multiply(BigDecimal.valueOf(now - s.lastAccrued()), MC)
            .divide(BigDecimal.valueOf(MONTH), MC);
    return state(
        s,
        s.principalMinor(),
        s.interestMinor(),
        s.residualMajor(),
        scale(s.accruedMajor().add(earned, MC)),
        s.capitalizedInterestMajor(),
        s.periodStart(),
        now,
        s.completedPeriods(),
        false,
        s.requirements());
  }

  private record Realized(State state, long posted) {}

  private static Realized realize(State s) {
    var amount = s.residualMajor().add(s.accruedMajor(), MC);
    long posted = minor(s.currency(), amount, RoundingMode.HALF_EVEN);
    var residual = scale(amount.subtract(major(s.currency(), posted), MC));
    return new Realized(
        state(
            s,
            s.principalMinor(),
            Math.addExact(s.interestMinor(), posted),
            residual,
            ZERO,
            s.capitalizedInterestMajor(),
            s.periodStart(),
            s.lastAccrued(),
            s.completedPeriods(),
            false,
            s.requirements()),
        posted);
  }

  public static Close month(State s, String id, long effective) {
    return monthClose(s, id, effective);
  }

  public static Close monthClose(State original, String id, long effective) {
    if (original.closed() || effective != original.nextDue())
      throw new IllegalArgumentException("Loan month boundary");
    var accrued = accrue(original, effective);
    BigDecimal earned = accrued.accruedMajor();
    var realized = realize(accrued);
    var s = realized.state();
    int ordinal = Math.addExact(s.completedPeriods(), 1);
    long available = Math.subtractExact(s.debtMinor(), s.requiredMinor());
    long fresh =
        ordinal > s.totalPeriods()
            ? 0
            : ordinal == s.totalPeriods() ? available : Math.min(s.installmentMinor(), available);
    var reqs = new ArrayList<>(s.requirements());
    Requirement requirement = null;
    if (fresh > 0) {
      long attributedInterest = 0;
      for (var r : reqs) attributedInterest = Math.addExact(attributedInterest, r.interestMinor());
      long i = Math.min(fresh, Math.subtractExact(s.interestMinor(), attributedInterest));
      requirement = new Requirement(id, effective, fresh, fresh, fresh - i, i);
      reqs.add(requirement);
    }
    var capital = major(s.currency(), s.interestMinor()).add(s.residualMajor());
    var next =
        state(
            s,
            s.principalMinor(),
            s.interestMinor(),
            s.residualMajor(),
            ZERO,
            capital,
            effective,
            effective,
            ordinal,
            false,
            reqs);
    return new Close(next, earned, realized.posted(), requirement);
  }

  public static Payment repay(State original, long amount, long now) {
    if (original.closed() || amount <= 0 || now >= original.nextDue())
      throw new IllegalArgumentException("Loan payment requires current open state");
    var realized = realize(accrue(original, now));
    var s = realized.state();
    if (amount > s.debtMinor())
      throw new IllegalArgumentException("Loan overpayment; fresh payoff required");
    long remaining = amount, p = s.principalMinor(), i = s.interestMinor();
    var allocations = new ArrayList<Allocation>();
    var reqs = new ArrayList<>(s.requirements());
    reqs.sort(Comparator.comparingLong(Requirement::dueAt).thenComparing(Requirement::id));
    for (int k = 0; k < reqs.size(); k++) {
      var r = reqs.get(k);
      long interest = Math.min(remaining, r.interestMinor());
      remaining -= interest;
      i -= interest;
      long principal = Math.min(remaining, r.principalMinor());
      remaining -= principal;
      p -= principal;
      if (interest > 0) allocations.add(new Allocation(r.id(), true, interest));
      if (principal > 0) allocations.add(new Allocation(r.id(), false, principal));
      reqs.set(
          k,
          new Requirement(
              r.id(),
              r.dueAt(),
              r.originalMinor(),
              r.unmetMinor() - interest - principal,
              r.principalMinor() - principal,
              r.interestMinor() - interest));
    }
    long otherInterest = Math.min(remaining, i);
    remaining -= otherInterest;
    i -= otherInterest;
    if (otherInterest > 0) allocations.add(new Allocation("", true, otherInterest));
    long otherPrincipal = Math.min(remaining, p);
    remaining -= otherPrincipal;
    p -= otherPrincipal;
    if (otherPrincipal > 0) allocations.add(new Allocation("", false, otherPrincipal));
    if (remaining != 0) throw new IllegalStateException("Unallocated loan payment");
    long interestPaid = Math.subtractExact(s.interestMinor(), i),
        principalPaid = Math.subtractExact(s.principalMinor(), p);
    long capitalPosted =
        Math.max(0, minor(s.currency(), s.capitalizedInterestMajor(), RoundingMode.HALF_EVEN));
    var capital =
        scale(
            s.capitalizedInterestMajor()
                .subtract(major(s.currency(), Math.min(interestPaid, capitalPosted)))
                .min(major(s.currency(), i).add(s.residualMajor()).max(BigDecimal.ZERO)));
    boolean closed = p == 0 && i == 0;
    var residual = closed ? ZERO : s.residualMajor();
    var disposition = closed ? s.residualMajor() : ZERO;
    var next =
        state(
            s,
            p,
            i,
            residual,
            ZERO,
            closed ? ZERO : capital,
            s.periodStart(),
            now,
            s.completedPeriods(),
            closed,
            reqs.stream().filter(r -> r.unmetMinor() > 0).toList());
    return new Payment(
        next, realized.posted(), principalPaid, interestPaid, allocations, disposition, ZERO);
  }

  public static Payoff payoff(State source, long now) {
    if (source.closed() || now < source.lastAccrued() || now >= source.nextDue())
      throw new IllegalArgumentException("Loan payoff state");
    var quoted = realize(accrue(source, now)).state();
    long expires = Math.min(Math.addExact(now, 30_000), source.nextDue());
    return new Payoff(source, quoted, now, expires, quoted.debtMinor());
  }

  public static Payment settlePayoff(State current, Payoff quote, long now) {
    if (!current.equals(quote.source())
        || now < quote.capturedAt()
        || now >= quote.expiresAt()
        || quote.expiresAt()
            > Math.min(Math.addExact(quote.capturedAt(), 30_000), current.nextDue()))
      throw new IllegalArgumentException("Stale loan payoff quote");
    var atQuote = accrue(current, quote.capturedAt());
    var atSettlement = accrue(current, now);
    var waiver = scale(atSettlement.accruedMajor().subtract(atQuote.accruedMajor()));
    var payment = repay(current, quote.amountMinor(), quote.capturedAt());
    if (!payment.state().closed()) throw new IllegalArgumentException("Incomplete payoff quote");
    var closed = payment.state();
    var settled =
        state(
            closed,
            0,
            0,
            ZERO,
            ZERO,
            ZERO,
            closed.periodStart(),
            now,
            closed.completedPeriods(),
            true,
            List.of());
    return new Payment(
        settled,
        payment.recognizedInterestMinor(),
        payment.principalPaid(),
        payment.interestPaid(),
        payment.allocations(),
        payment.residualDisposition(),
        waiver);
  }

  private static State state(
      State s,
      long p,
      long i,
      BigDecimal residual,
      BigDecimal accrued,
      BigDecimal capital,
      long start,
      long at,
      int completed,
      boolean closed,
      List<Requirement> reqs) {
    return new State(
        s.currency(),
        s.apr(),
        p,
        i,
        residual,
        accrued,
        capital,
        start,
        at,
        s.installmentMinor(),
        completed,
        s.totalPeriods(),
        closed,
        reqs);
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

  private static BigDecimal halfMinor(EconomyCurrency c) {
    return BigDecimal.ONE.divide(BigDecimal.valueOf(c.minorUnitsPerMajor() * 2L), MC);
  }

  private static BigDecimal exact(BigDecimal value) {
    Objects.requireNonNull(value);
    if (value.precision() > 50 || Math.abs(value.scale()) > 100)
      throw new IllegalArgumentException("Loan decimal bound");
    return value.setScale(18, RoundingMode.UNNECESSARY);
  }

  private static BigDecimal scale(BigDecimal value) {
    return value.setScale(18, RoundingMode.HALF_EVEN);
  }

  private static BigDecimal rate(BigDecimal rate) {
    Objects.requireNonNull(rate);
    if (rate.signum() < 0
        || rate.compareTo(BigDecimal.ONE) > 0
        || rate.precision() > 34
        || Math.abs(rate.scale()) > 18) throw new IllegalArgumentException("Loan APR");
    return rate;
  }
}
