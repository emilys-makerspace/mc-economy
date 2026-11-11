package ca.emilybelnavis.mcmods.economy.valuation;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.fx.EffectiveFxSnapshot;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import java.math.BigInteger;
import java.util.*;

/**
 * Base-only internal evaluation; reference results never grant settlement or issuance authority.
 */
public final class BaseValues {
  private BaseValues() {}

  public enum Input {
    FREE_WATER
  }

  public enum Status {
    READY,
    UNPRICED_OPERATOR,
    MOD_UNREGISTERED,
    MISSING_INPUT,
    UNSUPPORTED_IDENTITY,
    OVERFLOW,
    FX_UNAVAILABLE
  }

  public record Result(
      Status status,
      String reason,
      Rational exactUnitMinor,
      Money unitMoney,
      Money totalMoney,
      UUID fxPublication,
      String manifestHash,
      String configHash,
      ValuationData.Source source) {
    public Result {
      Objects.requireNonNull(status);
      Objects.requireNonNull(reason);
      Objects.requireNonNull(manifestHash);
      Objects.requireNonNull(configHash);
      if (status == Status.READY) {
        Objects.requireNonNull(exactUnitMinor);
        Objects.requireNonNull(unitMoney);
        Objects.requireNonNull(totalMoney);
        Objects.requireNonNull(fxPublication);
        Objects.requireNonNull(source);
      } else if (exactUnitMinor != null || unitMoney != null || totalMoney != null)
        throw new IllegalArgumentException("Unavailable result cannot contain partial money");
    }
  }

  /** A zero-cost ingredient is explicitly typed; it is never a generally priced zero-value item. */
  public static Rational inputCost(Input input) {
    Objects.requireNonNull(input);
    return Rational.ZERO;
  }

  public static Result evaluate(
      ValuationData.Manifest manifest,
      String item,
      String components,
      long quantity,
      EconomyCurrency target,
      EffectiveFxSnapshot fx) {
    Objects.requireNonNull(manifest);
    Objects.requireNonNull(target);
    ValuationData.itemId(item);
    if (quantity < 1 || quantity > 1_000_000) throw new IllegalArgumentException("Quantity limit");
    if (components == null || ValuationConfig.bytes(components) > 65_536)
      throw new IllegalArgumentException("Component identity limit");
    String manifestHash = manifest.hash();
    var source = manifest.sources().get(item);
    if (!manifest.inputs().items().contains(item))
      return unavailable(
          Status.UNSUPPORTED_IDENTITY,
          "ITEM_NOT_IN_CAPTURED_REGISTRY",
          manifest,
          manifestHash,
          fx,
          source);
    if (!components.equals("{}"))
      return unavailable(
          Status.UNSUPPORTED_IDENTITY,
          "COMPONENT_ADAPTER_NOT_INSTALLED",
          manifest,
          manifestHash,
          fx,
          source);
    var catalogEntry = manifest.catalog().entries().get(item);
    if (catalogEntry != null
        && catalogEntry.classification() == ValuationData.Classification.UNPRICED_OPERATOR)
      return unavailable(
          Status.UNPRICED_OPERATOR, "OPERATOR_ONLY_ITEM", manifest, manifestHash, fx, source);
    if (catalogEntry != null
        && catalogEntry.classification() == ValuationData.Classification.VARIANT)
      return unavailable(
          Status.UNSUPPORTED_IDENTITY,
          "VARIANT_ADAPTER_NOT_INSTALLED",
          manifest,
          manifestHash,
          fx,
          source);
    if (catalogEntry == null
        && manifest.contributions().stream().noneMatch(c -> c.claimedItems().contains(item)))
      return unavailable(
          Status.MOD_UNREGISTERED, "MOD_ITEM_NOT_REGISTERED", manifest, manifestHash, fx, source);
    if (source == null)
      return unavailable(
          Status.MISSING_INPUT, "NO_EXPLICIT_BASE_OR_OVERRIDE", manifest, manifestHash, fx, null);
    var entry = source.entry();
    if (fx == null || !fx.rates().containsKey(entry.currency()) || !fx.rates().containsKey(target))
      return unavailable(
          Status.FX_UNAVAILABLE,
          "CAPTURED_FX_MEMBERSHIP_REQUIRED",
          manifest,
          manifestHash,
          fx,
          source);
    try {
      Rational exact = bounded(entry.exactMinor());
      exact = bounded(exact.multiply(Rational.of(fx.rates().get(entry.currency()))));
      exact = bounded(exact.divide(Rational.of(fx.rates().get(target))));
      exact = bounded(exact.multiply(Rational.of(target.minorUnitsPerMajor())));
      exact = bounded(exact.divide(Rational.of(entry.currency().minorUnitsPerMajor())));
      long unitMinor = publishedMinor(exact);
      Money unit = new Money(target, unitMinor);
      Money total = new Money(target, Math.multiplyExact(unitMinor, quantity));
      return new Result(
          Status.READY,
          "EXPLICIT_REFERENCE_VALUE",
          exact,
          unit,
          total,
          fx.id(),
          manifestHash,
          manifest.configHash(),
          source);
    } catch (ArithmeticException e) {
      return unavailable(
          Status.OVERFLOW,
          "EXACT_OR_PUBLISHED_AMOUNT_OVERFLOW",
          manifest,
          manifestHash,
          fx,
          source);
    }
  }

  private static Result unavailable(
      Status status,
      String reason,
      ValuationData.Manifest manifest,
      String manifestHash,
      EffectiveFxSnapshot fx,
      ValuationData.Source source) {
    return new Result(
        status,
        reason,
        null,
        null,
        null,
        fx == null ? null : fx.id(),
        manifestHash,
        manifest.configHash(),
        source);
  }

  public static Rational bounded(Rational value) {
    if (value.numerator().bitLength() > 4_096 || value.denominator().bitLength() > 4_096)
      throw new ArithmeticException("Exact valuation exceeds 4096 bits");
    return value;
  }

  /** Positive exact unit → HALF_UP minor unit with the approved one-unit floor. */
  public static long publishedMinor(Rational exact) {
    bounded(exact);
    if (exact.compareTo(Rational.ZERO) <= 0)
      throw new ArithmeticException("Priced item requires a positive exact cost");
    BigInteger[] parts = exact.numerator().divideAndRemainder(exact.denominator());
    BigInteger rounded =
        parts[1].shiftLeft(1).compareTo(exact.denominator()) >= 0
            ? parts[0].add(BigInteger.ONE)
            : parts[0];
    return rounded.max(BigInteger.ONE).longValueExact();
  }
}
