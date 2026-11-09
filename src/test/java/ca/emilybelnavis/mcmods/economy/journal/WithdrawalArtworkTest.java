package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import java.util.*;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class WithdrawalArtworkTest {
  private static final WithdrawalPlanner.Denomination VERTICAL = bill("cad_bill_20", CAD, 2_000);
  private static final WithdrawalPlanner.Denomination HORIZONTAL =
      bill("cad_bill_20_horizontal", CAD, 2_000);

  @Test
  void eachTwentyTicketsIncludeOneVerticalAndPreserveTheSelectedMix() {
    var coin = new WithdrawalPlanner.Denomination("economy:cad_coin_25", CAD, 25, false);
    var allowed = List.of(VERTICAL, HORIZONTAL, coin);
    var plan = WithdrawalPlanner.edited(CAD, 80_025, false, allowed, Map.of(VERTICAL.id(), 40));
    var random = new Tickets();
    var selected = WithdrawalArtwork.select(plan, allowed, random);
    assertEquals(Map.of(VERTICAL.id(), 2, HORIZONTAL.id(), 38), selected.bills());
    assertEquals(plan.coins(), selected.coins());
    assertEquals(plan.minorUnits(), selected.minorUnits());
    assertEquals(plan.currency(), selected.currency());
    assertEquals(plan.itemCount(), selected.itemCount());
    assertEquals(40, random.calls);
    assertEquals(
        selected,
        WithdrawalPlanner.edited(CAD, selected.minorUnits(), false, allowed, selected.bills()),
        "the sampled plan can be frozen and revalidated without another random draw");
  }

  @Test
  void horizontalInputAlsoHasTheSamePerNoteVerticalChance() {
    var allowed = List.of(VERTICAL, HORIZONTAL);
    var plan = WithdrawalPlanner.edited(CAD, 40_000, true, allowed, Map.of(HORIZONTAL.id(), 20));
    assertEquals(
        Map.of(VERTICAL.id(), 1, HORIZONTAL.id(), 19),
        WithdrawalArtwork.select(plan, allowed, new Tickets()).bills());
  }

  @Test
  void disabledOrMismatchedVariantsAreNeverIntroduced() {
    var plan = WithdrawalPlanner.plan(CAD, 4_000, true, List.of(VERTICAL));
    var forbiddenRandom =
        new RandomGenerator() {
          @Override
          public long nextLong() {
            throw new AssertionError("Unpaired notes must not draw artwork");
          }
        };
    assertEquals(plan, WithdrawalArtwork.select(plan, List.of(VERTICAL), forbiddenRandom));
    assertEquals(
        plan,
        WithdrawalArtwork.select(
            plan, List.of(VERTICAL, bill("cad_bill_20_horizontal", CAD, 1_000)), forbiddenRandom));
    assertEquals(
        plan,
        WithdrawalArtwork.select(
            plan,
            List.of(VERTICAL, bill("cad_bill_20_horizontal", EconomyCurrency.USD, 2_000)),
            forbiddenRandom));
  }

  private static WithdrawalPlanner.Denomination bill(
      String id, EconomyCurrency currency, long amount) {
    return new WithdrawalPlanner.Denomination("economy:" + id, currency, amount, true);
  }

  private static final class Tickets implements RandomGenerator {
    private int calls;

    @Override
    public int nextInt(int bound) {
      assertEquals(20, bound);
      return calls++ % bound;
    }

    @Override
    public long nextLong() {
      throw new AssertionError("Expected one bounded draw per note");
    }
  }
}
