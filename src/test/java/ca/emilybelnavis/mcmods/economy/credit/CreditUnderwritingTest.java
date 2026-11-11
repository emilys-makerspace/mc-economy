package ca.emilybelnavis.mcmods.economy.credit;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class CreditUnderwritingTest {
  private static final EconomyCurrency CAD = EconomyCurrency.CAD;

  private static long minor(long major) {
    return Math.multiplyExact(major, CAD.minorUnitsPerMajor());
  }

  private CreditUnderwriting.LoanOffer loan(
      int score,
      boolean tier2,
      long exposure,
      long principal,
      int years,
      CreditUnderwriting.Product product) {
    return CreditUnderwriting.loan(
        new CreditUnderwriting.LoanRequest(
            true,
            score,
            CAD,
            product,
            tier2,
            tier2,
            true,
            false,
            minor(exposure),
            minor(principal),
            years,
            new BigDecimal("0.06")));
  }

  private CreditUnderwriting.RevolvingOffer revolving(
      boolean established,
      boolean starter,
      int score,
      int count,
      long committed,
      long exposure,
      long requested) {
    return CreditUnderwriting.revolving(
        new CreditUnderwriting.RevolvingRequest(
            established,
            starter,
            score,
            CAD,
            false,
            false,
            true,
            false,
            count,
            minor(committed),
            minor(exposure),
            minor(requested)));
  }

  @Test
  void revolvingBandsAndStarterAreOwnerWideRatherThanPerIssuer() {
    int[] scores = {300, 499, 500, 579, 580, 649, 650, 699, 700, 749, 750, 799, 800, 849, 850, 900};
    long[] limits = {
      0, 0, 1000, 1000, 2500, 2500, 5000, 5000, 15000, 15000, 50000, 50000, 150000, 150000, 500000,
      500000
    };
    for (int i = 0; i < scores.length; i++)
      assertEquals(limits[i], CreditUnderwriting.revolvingCeiling(scores[i]));
    var first = revolving(false, false, 300, 0, 0, 0, 2500);
    assertTrue(first.approved());
    assertEquals(650, first.capturedScore());
    assertEquals(minor(2500), first.maximumNewLimit().minorUnits());
    assertFalse(revolving(false, false, 650, 0, 0, 0, 5000).approved());
    assertEquals("STARTER_ALREADY_CONSUMED", revolving(false, true, 650, 0, 0, 0, 2500).reason());
    assertTrue(revolving(true, true, 650, 1, 2500, 2500, 2500).approved());
    assertFalse(revolving(true, true, 650, 2, 5000, 5000, 500).approved());
    assertEquals("REVOLVING_FACILITY_CAP", revolving(true, true, 900, 3, 1500, 1500, 500).reason());
    assertEquals(
        "REVOLVING_HEADROOM_BELOW_MINIMUM", revolving(true, true, 500, 1, 600, 600, 400).reason());
  }

  @Test
  void approvedBankAndSubprimeExamplesUseExactAprAndSharedExposure() {
    var bank = loan(650, false, 0, 100000, 3, CreditUnderwriting.Product.BANK_LOAN);
    assertTrue(bank.approved());
    assertEquals(new BigDecimal("0.12"), bank.apr());
    assertEquals(minor(150000), bank.totalExposureCeiling().minorUnits());
    var tier2 = loan(650, true, 0, 400000, 3, CreditUnderwriting.Product.BANK_LOAN);
    assertTrue(tier2.approved());
    assertEquals(0, new BigDecimal("0.025").compareTo(tier2.apr()));
    assertEquals(minor(600000), tier2.totalExposureCeiling().minorUnits());
    assertEquals(
        "OWNER_EXPOSURE_CEILING",
        loan(650, false, 140000, 20000, 1, CreditUnderwriting.Product.BANK_LOAN).reason());
    var subprime = loan(560, false, 0, 25000, 5, CreditUnderwriting.Product.SUBPRIME_LOAN);
    assertTrue(subprime.approved());
    assertEquals(new BigDecimal("0.2999"), subprime.apr());
    assertFalse(loan(480, false, 0, 1000, 1, CreditUnderwriting.Product.BANK_LOAN).approved());
    assertFalse(loan(480, false, 0, 500, 1, CreditUnderwriting.Product.SUBPRIME_LOAN).approved());
  }

  @Test
  void bankAndSubprimeBoundaryTablesRetainTheirExactTerms() {
    int[] score = {619, 620, 649, 650, 699, 700, 749, 750, 799, 800, 849, 850, 900};
    long[] principal = {
      0, 25000, 25000, 100000, 100000, 500000, 500000, 2000000, 2000000, 10000000, 10000000,
      50000000, 50000000
    };
    int[] years = {0, 2, 2, 3, 3, 5, 5, 5, 5, 5, 5, 5, 5};
    for (int i = 0; i < score.length; i++) {
      var band = CreditUnderwriting.bankBand(score[i]);
      assertEquals(principal[i], band.principalMajor());
      assertEquals(years[i], band.maximumYears());
    }
    assertEquals(3, CreditUnderwriting.subprimeBand(549).maximumYears());
    assertEquals(5, CreditUnderwriting.subprimeBand(550).maximumYears());
    assertEquals(8, CreditUnderwriting.subprimeBand(600).maximumYears());
    assertEquals(new BigDecimal("0.2499"), CreditUnderwriting.subprimeBand(649).premiumApr());
    assertEquals(new BigDecimal("0.1999"), CreditUnderwriting.subprimeBand(650).premiumApr());
    assertEquals(
        "TERM_NOT_PERMITTED",
        loan(620, false, 0, 1000, 3, CreditUnderwriting.Product.BANK_LOAN).reason());
  }

  @Test
  void unestablishedIncompleteBlockedAndIneligibleInputsCannotApprove() {
    var request =
        new CreditUnderwriting.LoanRequest(
            false,
            650,
            CAD,
            CreditUnderwriting.Product.BANK_LOAN,
            false,
            false,
            true,
            false,
            0,
            minor(1000),
            1,
            new BigDecimal("0.06"));
    assertEquals("CREDIT_UNESTABLISHED", CreditUnderwriting.loan(request).reason());
    var unavailable =
        new CreditUnderwriting.LoanRequest(
            true,
            900,
            CAD,
            CreditUnderwriting.Product.BANK_LOAN,
            true,
            true,
            false,
            false,
            0,
            minor(1000),
            1,
            new BigDecimal("0.06"));
    assertFalse(CreditUnderwriting.loan(unavailable).available());
    var blocked =
        new CreditUnderwriting.LoanRequest(
            true,
            900,
            CAD,
            CreditUnderwriting.Product.BANK_LOAN,
            false,
            false,
            true,
            true,
            0,
            minor(1000),
            1,
            new BigDecimal("0.06"));
    assertEquals("ARREARS_DEFAULT_OR_COLLECTION", CreditUnderwriting.loan(blocked).reason());
    var ineligible =
        new CreditUnderwriting.RevolvingRequest(
            true, true, 900, CAD, true, false, true, false, 1, minor(500), minor(500), minor(500));
    assertEquals("TIER2_UNAVAILABLE", CreditUnderwriting.revolving(ineligible).reason());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CreditUnderwriting.loan(
                new CreditUnderwriting.LoanRequest(
                    true,
                    650,
                    CAD,
                    CreditUnderwriting.Product.BANK_LOAN,
                    false,
                    false,
                    true,
                    false,
                    0,
                    minor(1000),
                    1,
                    new BigDecimal("0.0600001"))));
    assertThrows(IllegalArgumentException.class, () -> CreditUnderwriting.revolvingCeiling(901));
  }

  @Test
  void exposureCountsEachClaimOnceAndNeverTreatsUncollectabilityAsExtinguishment() {
    var revolving =
        new CreditUnderwriting.Exposure(
            UUID.randomUUID(),
            CreditUnderwriting.ExposureKind.OPEN_REVOLVING,
            minor(2500),
            minor(1000),
            minor(50),
            true,
            false);
    var loan =
        new CreditUnderwriting.Exposure(
            UUID.randomUUID(),
            CreditUnderwriting.ExposureKind.LOAN,
            0,
            minor(1000),
            minor(50),
            true,
            false);
    var collection =
        new CreditUnderwriting.Exposure(
            UUID.randomUUID(),
            CreditUnderwriting.ExposureKind.COLLECTION,
            0,
            minor(500),
            minor(20),
            false,
            false);
    assertEquals(
        minor(4070), CreditUnderwriting.exposure(List.of(revolving, loan, collection, revolving)));
    var repaid =
        new CreditUnderwriting.Exposure(
            collection.liabilityId(), collection.kind(), 0, minor(500), minor(20), false, true);
    assertEquals(0, CreditUnderwriting.exposure(List.of(repaid)));
    var soldCopy =
        new CreditUnderwriting.Exposure(
            collection.liabilityId(),
            CreditUnderwriting.ExposureKind.LOAN,
            0,
            minor(500),
            minor(20),
            true,
            false);
    assertThrows(
        IllegalArgumentException.class,
        () -> CreditUnderwriting.exposure(List.of(collection, soldCopy)));
    var overLimit =
        new CreditUnderwriting.Exposure(
            UUID.randomUUID(),
            CreditUnderwriting.ExposureKind.OPEN_REVOLVING,
            minor(1000),
            minor(1200),
            minor(50),
            true,
            false);
    assertEquals(minor(1250), CreditUnderwriting.exposure(List.of(overLimit)));
  }

  @Test
  void lowerScoreDoesNotReduceDebtOrContractButBlocksNewRoom() {
    long limit = minor(5000), owed = minor(2000);
    assertTrue(
        CreditUnderwriting.drawAllowed(650, false, false, limit, owed, limit, minor(100), CAD));
    assertFalse(
        CreditUnderwriting.drawAllowed(499, false, false, limit, owed, limit, minor(100), CAD));
    assertEquals(
        "REVOLVING_HEADROOM_BELOW_MINIMUM",
        revolving(true, true, 500, 1, 5000, 5000, 500).reason());
    assertFalse(
        CreditUnderwriting.drawAllowed(
            650, false, false, minor(1000), minor(1200), minor(1200), minor(1), CAD));
    assertFalse(
        CreditUnderwriting.drawAllowed(
            650, false, false, limit, owed, minor(150001), minor(100), CAD));
    assertEquals(minor(5000), limit);
    assertEquals(minor(2000), owed);
  }

  @Test
  void nativeWholeMajorRequestsRemainValidWhenTheirWorldValueHasCents() {
    var fx =
        new CreditUnderwriting.FxSnapshot(
            UUID.randomUUID(),
            0,
            0,
            Map.of(CAD, BigDecimal.ONE, EconomyCurrency.JPY, new BigDecimal("0.0091")),
            true);
    var nativePrincipal =
        new Money(
            EconomyCurrency.JPY,
            Math.multiplyExact(200_001L, EconomyCurrency.JPY.minorUnitsPerMajor()));
    var terms =
        new CreditUnderwriting.LoanRequest(
            true,
            650,
            CAD,
            CreditUnderwriting.Product.BANK_LOAN,
            false,
            false,
            true,
            false,
            0,
            0,
            1,
            new BigDecimal("0.06"));
    var offer = CreditUnderwriting.loanForFacility(terms, nativePrincipal, fx);
    assertTrue(offer.worldOffer().approved());
    assertNotEquals(0, offer.normalizedWorldPrincipal().minorUnits() % CAD.minorUnitsPerMajor());
    var cardTerms =
        new CreditUnderwriting.RevolvingRequest(
            true, true, 650, CAD, false, false, true, false, 0, 0, 0, 0);
    var card = CreditUnderwriting.revolvingForFacility(cardTerms, nativePrincipal, fx);
    assertTrue(card.worldOffer().approved());
    var subprime =
        new CreditUnderwriting.LoanRequest(
            true,
            560,
            CAD,
            CreditUnderwriting.Product.SUBPRIME_LOAN,
            true,
            false,
            true,
            false,
            0,
            minor(500),
            1,
            new BigDecimal("0.06"));
    assertTrue(CreditUnderwriting.loan(subprime).approved());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CreditUnderwriting.FxSnapshot(
                UUID.randomUUID(), 0, 0, Map.of(CAD, new BigDecimal("1e-100000000")), true));
  }

  @Test
  void capturedFxRoundsExposureUpAndFacilityCeilingsDownWithoutCreatingHeadroom() {
    var snapshot =
        new CreditUnderwriting.FxSnapshot(
            UUID.randomUUID(),
            0,
            0,
            Map.of(CAD, BigDecimal.ONE, EconomyCurrency.CHF, new BigDecimal("1.60")),
            true);
    assertEquals(
        2,
        CreditUnderwriting.exposureToWorld(new Money(EconomyCurrency.CHF, 1), CAD, snapshot)
            .minorUnits());
    assertEquals(
        0,
        CreditUnderwriting.ceilingToFacility(new Money(CAD, 1), EconomyCurrency.CHF, snapshot)
            .minorUnits());
    var old =
        new CreditUnderwriting.FxSnapshot(
            snapshot.publication(), 0, 172_800_001L, snapshot.rates(), true);
    assertThrows(
        IllegalArgumentException.class,
        () -> CreditUnderwriting.exposureToWorld(new Money(CAD, 100), CAD, old));
    var missing =
        new CreditUnderwriting.FxSnapshot(
            UUID.randomUUID(), 0, 0, Map.of(CAD, BigDecimal.ONE), true);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CreditUnderwriting.ceilingToFacility(
                new Money(CAD, 100), EconomyCurrency.CHF, missing));
    var overflow =
        new CreditUnderwriting.Exposure(
            UUID.randomUUID(),
            CreditUnderwriting.ExposureKind.LOAN,
            0,
            Long.MAX_VALUE,
            1,
            true,
            false);
    assertThrows(ArithmeticException.class, () -> CreditUnderwriting.exposure(List.of(overflow)));
  }
}
