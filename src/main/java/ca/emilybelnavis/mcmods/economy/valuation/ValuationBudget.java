package ca.emilybelnavis.mcmods.economy.valuation;

/** One deterministic work budget across brewing and its ordinary material dependencies. */
final class ValuationBudget {
  private int work;

  void spend() {
    if (++work > RecipeGraph.MAX_WORK) throw new Limit("RECIPE_WORK_LIMIT");
  }

  static final class Limit extends RuntimeException {
    Limit(String reason) {
      super(reason);
    }
  }
}
