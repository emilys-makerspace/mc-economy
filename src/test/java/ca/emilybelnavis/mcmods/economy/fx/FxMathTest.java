package ca.emilybelnavis.mcmods.economy.fx;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import java.math.BigDecimal;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

class FxMathTest {
  @Test
  void openingRebasesDkkAndJpyAndPublishesAllRatesAtFixedPrecision() {
    var jpyRates = FxMath.opening(JPY);
    var dkkRates = FxMath.opening(DKK);
    assertEquals(11, jpyRates.size());
    assertEquals(new BigDecimal("1.000000000000"), jpyRates.get(JPY));
    assertEquals(new BigDecimal("23.541851514092"), jpyRates.get(DKK));
    assertEquals(new BigDecimal("0.042477542576"), dkkRates.get(JPY));
    assertTrue(dkkRates.values().stream().allMatch(rate -> rate.scale() == 12));
    assertEquals(
        new FxMath.Settings(dkkRates.get(CAD), 3000, 100000, 50000), FxMath.defaults(DKK).get(CAD));
  }

  @Test
  void samplerMatchesGoldenVectorsIncludingARejectedFirstWord() {
    assertEquals(2982, FxMath.drawPpm(0, CAD, 3000));
    assertEquals(88, FxMath.drawPpm(0, JPY, 3000));
    assertEquals(-323, FxMath.drawPpm(-1, DKK, 3000));
    assertEquals(-741074, FxMath.drawPpm(Long.MIN_VALUE, USD, 1000000));
    assertEquals(-624559, FxMath.drawPpm(720, CAD, 1000000));
    assertEquals(0, FxMath.drawPpm(720, CAD, 0));
  }

  @Test
  void advanceCarriesPriorRateIntoEditedBaselineAndKeepsReferencePinned() {
    var previous = new EnumMap<>(FxMath.opening(JPY));
    var policy = new EnumMap<>(FxMath.defaults(JPY));
    previous.put(CAD, new BigDecimal("1.100000000000"));
    policy.put(CAD, new FxMath.Settings(new BigDecimal("2"), 0, 100000, 500000));

    var next = FxMath.advance(JPY, previous, policy, 5);
    assertEquals(new BigDecimal("1.190000000000"), next.get(CAD));
    assertEquals(previous.get(JPY), next.get(JPY));
    assertEquals(next, FxMath.advance(JPY, previous, policy, 5));

    policy.put(CAD, new FxMath.Settings(new BigDecimal("2"), 0, 100000, 50000));
    assertEquals(
        new BigDecimal("1.900000000000"), FxMath.advance(JPY, previous, policy, 5).get(CAD));
  }

  @Test
  void advanceAppliesDrawAgainstBaselineAndRoundsTiesToEven() {
    var previous = new EnumMap<>(FxMath.opening(JPY));
    var policy = new EnumMap<>(FxMath.defaults(JPY));
    previous.put(CAD, new BigDecimal("1.100000000000"));
    policy.put(CAD, new FxMath.Settings(new BigDecimal("2"), 3000, 100000, 500000));
    assertEquals(
        new BigDecimal("1.195964000000"), FxMath.advance(JPY, previous, policy, 0).get(CAD));

    previous.put(CAD, new BigDecimal("1.000000000001"));
    policy.put(CAD, new FxMath.Settings(BigDecimal.ONE, 0, 500000, 50000));
    assertEquals(
        new BigDecimal("1.000000000000"), FxMath.advance(JPY, previous, policy, 0).get(CAD));
  }

  @Test
  void clampsToRepresentableBandAndRejectsInvalidSettingsOrIncompleteInputs() {
    var previous = new EnumMap<>(FxMath.opening(JPY));
    var policy = new EnumMap<>(FxMath.defaults(JPY));
    policy.put(CAD, new FxMath.Settings(new BigDecimal("0.000000000003"), 0, 0, 200000));
    assertEquals(
        new BigDecimal("0.000000000003"), FxMath.advance(JPY, previous, policy, 0).get(CAD));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FxMath.Settings(new BigDecimal("0.000000000001"), 0, 0, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FxMath.Settings(new BigDecimal("1000000"), 0, 0, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FxMath.Settings(new BigDecimal("0.0000000000011"), 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new FxMath.Settings(BigDecimal.ONE, -1, 0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new FxMath.Settings(BigDecimal.ONE, 0, 1000001, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new FxMath.Settings(BigDecimal.ONE, 0, 0, 1000000));

    previous.remove(EconomyCurrency.USD);
    assertThrows(IllegalArgumentException.class, () -> FxMath.advance(JPY, previous, policy, 0));
  }
}
