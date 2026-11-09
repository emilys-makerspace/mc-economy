package ca.emilybelnavis.mcmods.economy.journal;

import java.util.*;
import java.util.random.RandomGenerator;

/** Selects collectible note artwork without changing a withdrawal's denomination mix or value. */
public final class WithdrawalArtwork {
  private static final String HORIZONTAL = "_horizontal";

  private WithdrawalArtwork() {}

  /** Each note with two enabled appearances independently has a one-in-twenty vertical chance. */
  public static WithdrawalPlanner.Plan select(
      WithdrawalPlanner.Plan plan,
      Collection<WithdrawalPlanner.Denomination> allowedDenominations,
      RandomGenerator random) {
    Objects.requireNonNull(plan);
    Objects.requireNonNull(random);
    var allowed = new HashMap<String, WithdrawalPlanner.Denomination>();
    for (var denomination : allowedDenominations) allowed.put(denomination.id(), denomination);
    var quantities = new TreeMap<String, Integer>();
    var selected = new HashMap<String, WithdrawalPlanner.Denomination>();
    for (var line : plan.lines()) {
      var denomination = line.denomination();
      String verticalId =
          denomination.id().endsWith(HORIZONTAL)
              ? denomination.id().substring(0, denomination.id().length() - HORIZONTAL.length())
              : denomination.id();
      var vertical = allowed.get(verticalId);
      var horizontal = allowed.get(verticalId + HORIZONTAL);
      if (denomination.bill()
          && sameNote(denomination, vertical)
          && sameNote(denomination, horizontal)) {
        for (int index = 0; index < line.count(); index++) {
          var choice = random.nextInt(20) == 0 ? vertical : horizontal;
          quantities.merge(choice.id(), 1, Math::addExact);
          selected.put(choice.id(), choice);
        }
      } else {
        quantities.merge(denomination.id(), line.count(), Math::addExact);
        selected.put(denomination.id(), denomination);
      }
    }
    var bills = new TreeMap<String, Integer>();
    var coins = new TreeMap<String, Integer>();
    var lines = new ArrayList<WithdrawalPlanner.Line>();
    quantities.forEach(
        (id, count) -> {
          var denomination = selected.get(id);
          (denomination.bill() ? bills : coins).put(id, count);
          lines.add(new WithdrawalPlanner.Line(denomination, count));
        });
    return new WithdrawalPlanner.Plan(
        plan.currency(), plan.minorUnits(), bills, coins, lines, plan.itemCount());
  }

  private static boolean sameNote(
      WithdrawalPlanner.Denomination original, WithdrawalPlanner.Denomination variant) {
    return variant != null
        && variant.bill()
        && variant.currency() == original.currency()
        && variant.minorUnits() == original.minorUnits();
  }
}
