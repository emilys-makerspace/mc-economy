package ca.emilybelnavis.mcmods.economy.simulation.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Objects;

/** Reduced exact arithmetic used by frozen model v1; rounding occurs only at publication. */
public record Rational(BigInteger numerator, BigInteger denominator) implements Comparable<Rational> {
    public static final Rational ZERO = of(0), ONE = of(1);
    public Rational {
        Objects.requireNonNull(numerator); Objects.requireNonNull(denominator);
        if (denominator.signum() == 0) throw new IllegalArgumentException("Zero denominator");
        if (denominator.signum() < 0) { numerator = numerator.negate(); denominator = denominator.negate(); }
        BigInteger gcd = numerator.gcd(denominator);
        numerator = numerator.divide(gcd); denominator = denominator.divide(gcd);
    }
    public static Rational of(long value) { return new Rational(BigInteger.valueOf(value), BigInteger.ONE); }
    public static Rational of(BigInteger value) { return new Rational(value, BigInteger.ONE); }
    public static Rational of(BigDecimal value) {
        return value.scale() >= 0 ? new Rational(value.unscaledValue(), BigInteger.TEN.pow(value.scale()))
                : of(value.unscaledValue().multiply(BigInteger.TEN.pow(-value.scale())));
    }
    public static Rational of(String value) { return of(new BigDecimal(value)); }
    public Rational add(Rational b) { return new Rational(numerator.multiply(b.denominator).add(b.numerator.multiply(denominator)), denominator.multiply(b.denominator)); }
    public Rational subtract(Rational b) { return add(b.negate()); }
    public Rational multiply(Rational b) { return new Rational(numerator.multiply(b.numerator), denominator.multiply(b.denominator)); }
    public Rational divide(Rational b) { return new Rational(numerator.multiply(b.denominator), denominator.multiply(b.numerator)); }
    public Rational negate() { return new Rational(numerator.negate(), denominator); }
    public Rational abs() { return numerator.signum() < 0 ? negate() : this; }
    public Rational min(Rational b) { return compareTo(b) <= 0 ? this : b; }
    public Rational max(Rational b) { return compareTo(b) >= 0 ? this : b; }
    public BigDecimal decimal(int scale) { return new BigDecimal(numerator).divide(new BigDecimal(denominator), scale, RoundingMode.HALF_EVEN); }
    public int compareTo(Rational b) { return numerator.multiply(b.denominator).compareTo(b.numerator.multiply(denominator)); }
    @Override public String toString() { return numerator + "/" + denominator; }
}
