package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import java.util.*;
import org.junit.jupiter.api.Test;

class WithdrawalPlannerTest {
  private static final WithdrawalPlanner.Denomination TWENTY = bill("twenty", 2_000);
  private static final WithdrawalPlanner.Denomination FIFTY = bill("fifty", 5_000);

  @Test
  void atmTwentyFiftyExamplesUseExactPlans() {
    var denominations = List.of(TWENTY, FIFTY);
    assertEquals(
        Map.of("economy:twenty", 3),
        WithdrawalPlanner.plan(CAD, 6_000, true, denominations).bills());
    assertEquals(
        Map.of("economy:twenty", 1, "economy:fifty", 1),
        WithdrawalPlanner.plan(CAD, 7_000, true, denominations).bills());
    assertThrows(
        IllegalArgumentException.class,
        () -> WithdrawalPlanner.plan(CAD, 3_000, true, denominations));
  }

  @Test
  void tellerMaximizesFeasibleBillValueBeforeMinimizingCount() {
    var denominations = List.of(TWENTY, FIFTY, coin("thirty", 3_000));
    var plan = WithdrawalPlanner.plan(CAD, 7_000, false, denominations);
    assertEquals(Map.of("economy:fifty", 1, "economy:twenty", 1), plan.bills());
    assertTrue(plan.coins().isEmpty());
    var nongreedy =
        WithdrawalPlanner.plan(
            CAD, 8_000, false, List.of(bill("sixty", 6_000), FIFTY, coin("thirty", 3_000)));
    assertEquals(Map.of("economy:fifty", 1), nongreedy.bills());
    assertEquals(Map.of("economy:thirty", 1), nongreedy.coins());
    var minimum =
        WithdrawalPlanner.plan(
            CAD,
            6_000,
            false,
            List.of(bill("ten", 1_000), bill("thirty", 3_000), coin("one", 100)));
    assertEquals(Map.of("economy:thirty", 2), minimum.bills());
  }

  @Test
  void editedBillsDeriveCoinsAndRejectForgedCoinQuantities() {
    var denominations = List.of(TWENTY, FIFTY, coin("one", 100));
    var edited =
        WithdrawalPlanner.edited(CAD, 7_500, false, denominations, Map.of("economy:fifty", 1));
    assertEquals(Map.of("economy:fifty", 1), edited.bills());
    assertEquals(Map.of("economy:one", 25), edited.coins());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WithdrawalPlanner.edited(CAD, 7_500, false, denominations, Map.of("economy:one", 75)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WithdrawalPlanner.edited(
                CAD, 7_500, false, denominations, Map.of("economy:unknown", 1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WithdrawalPlanner.edited(
                CAD, 7_500, false, denominations, Map.of("economy:fifty", -1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WithdrawalPlanner.edited(CAD, 7_500, true, denominations, Map.of("economy:fifty", 1)));
  }

  @Test
  void budgetsAndItemCountRejectBeforeReturningAnApproximation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> WithdrawalPlanner.plan(CAD, 100_001, true, List.of(bill("one", 1))));
    var many = new ArrayList<WithdrawalPlanner.Denomination>();
    for (int i = 1; i <= 118; i++) many.add(bill("bill_" + i, i));
    assertThrows(
        IllegalArgumentException.class, () -> WithdrawalPlanner.plan(CAD, 100_000, true, many));
    assertThrows(
        IllegalArgumentException.class,
        () -> WithdrawalPlanner.plan(CAD, 2_305, true, List.of(bill("one", 1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WithdrawalPlanner.edited(
                CAD,
                Long.MAX_VALUE,
                true,
                List.of(bill("large", Long.MAX_VALUE)),
                Map.of("economy:large", 2)));
  }

  @Test
  void orderingAndEqualValueArtworkHaveStableImmutableResults() {
    var denominations = new ArrayList<>(List.of(bill("z_art", 2_000), bill("a_art", 2_000), FIFTY));
    var first = WithdrawalPlanner.plan(CAD, 6_000, true, denominations);
    Collections.reverse(denominations);
    assertEquals(first, WithdrawalPlanner.plan(CAD, 6_000, true, denominations));
    assertEquals(Map.of("economy:a_art", 3), first.bills());
    assertThrows(UnsupportedOperationException.class, () -> first.bills().put("economy:a_art", 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WithdrawalPlanner.plan(
                CAD,
                100,
                false,
                List.of(
                    new WithdrawalPlanner.Denomination(
                        "economy:foreign", EconomyCurrency.EUR, 100, false))));
  }

  private static WithdrawalPlanner.Denomination bill(String name, long amount) {
    return new WithdrawalPlanner.Denomination("economy:" + name, CAD, amount, true);
  }

  private static WithdrawalPlanner.Denomination coin(String name, long amount) {
    return new WithdrawalPlanner.Denomination("economy:" + name, CAD, amount, false);
  }
}
