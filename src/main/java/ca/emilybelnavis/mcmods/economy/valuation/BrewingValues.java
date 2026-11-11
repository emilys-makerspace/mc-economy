package ca.emilybelnavis.mcmods.economy.valuation;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.fx.EffectiveFxSnapshot;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.*;

/** Pure exact brewing costs over a retained vanilla capture, with no recipe or world callbacks. */
public final class BrewingValues {
  private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
  private static final BrewingGraph.State WATER =
      new BrewingGraph.State("minecraft:potion", "minecraft:water");
  private static final Rational THREE = Rational.of(3), SIXTY = Rational.of(60);

  private BrewingValues() {}

  private record Value(Rational exact, RecipeValues.Explanation proof, boolean limited) {}

  private record Attempt(Value value, RecipeValues.Status status, String reason) {
    static Attempt ready(Value value) {
      return new Attempt(value, RecipeValues.Status.READY, "");
    }

    static Attempt failed(RecipeValues.Status status, String reason) {
      return new Attempt(null, status, reason);
    }

    boolean ready() {
      return value != null;
    }
  }

  private static final class Abort extends RuntimeException {
    final RecipeValues.Status status;

    Abort(RecipeValues.Status status, String reason) {
      super(reason);
      this.status = status;
    }
  }

  public static RecipeValues.Result evaluate(
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
    String hash = manifest.hash();
    var exact = material(manifest, item, components, target, fx, new ValuationBudget(), 0, hash);
    if (exact.status() != RecipeValues.Status.READY)
      return unavailable(manifest, hash, fx, exact.status(), exact.reason());
    try {
      long minor = BaseValues.publishedMinor(exact.exact());
      return new RecipeValues.Result(
          RecipeValues.Status.READY,
          "BREWING_REFERENCE_VALUE",
          exact.exact(),
          new Money(target, minor),
          new Money(target, Math.multiplyExact(minor, quantity)),
          fx.id(),
          hash,
          manifest.configHash(),
          null,
          exact.coverage(),
          exact.explanation());
    } catch (ArithmeticException overflow) {
      return unavailable(
          manifest, hash, fx, RecipeValues.Status.OVERFLOW, "EXACT_OR_PUBLISHED_AMOUNT_OVERFLOW");
    }
  }

  static RecipeValues.Material material(
      ValuationData.Manifest manifest,
      String item,
      String components,
      EconomyCurrency currency,
      EffectiveFxSnapshot fx,
      ValuationBudget budget,
      int depth) {
    return material(manifest, item, components, currency, fx, budget, depth, manifest.hash());
  }

  private static RecipeValues.Material material(
      ValuationData.Manifest manifest,
      String item,
      String components,
      EconomyCurrency currency,
      EffectiveFxSnapshot fx,
      ValuationBudget budget,
      int depth,
      String hash) {
    final BrewingGraph.State state;
    try {
      state = BrewingGraph.identity(item, components);
    } catch (IllegalArgumentException invalid) {
      return failed(RecipeValues.Status.UNSUPPORTED_IDENTITY, "UNSUPPORTED_POTION_COMPONENTS");
    }
    if (manifest.variants() != null && !manifest.variants().items().containsKey(item))
      return failed(RecipeValues.Status.UNSUPPORTED_IDENTITY, "UNSUPPORTED_ITEM_PROTOTYPE");
    var graph = manifest.brewing();
    if (graph == null)
      return failed(RecipeValues.Status.UNSUPPORTED_RECIPE, "BREWING_ADAPTER_NOT_INSTALLED");
    if (!manifest.inputs().items().contains(item)
        || !graph.containers().contains(item)
        || !graph.potions().containsKey(state.potion()))
      return failed(RecipeValues.Status.UNSUPPORTED_IDENTITY, "POTION_IDENTITY_NOT_CAPTURED");
    if (!graph.vanillaCompatible())
      return failed(RecipeValues.Status.UNSUPPORTED_RECIPE, "CUSTOM_BREWING_REQUIRES_ADAPTER");
    if (fx == null || !fx.rates().containsKey(currency))
      return failed(RecipeValues.Status.FX_UNAVAILABLE, "CAPTURED_FX_MEMBERSHIP_REQUIRED");
    try {
      var attempt = new Search(manifest, hash, currency, fx, budget).state(state, Set.of(), depth);
      if (!attempt.ready()) return failed(attempt.status(), attempt.reason());
      var value = attempt.value();
      return new RecipeValues.Material(
          RecipeValues.Status.READY,
          "BREWING_REFERENCE_VALUE",
          value.exact(),
          value.proof(),
          value.limited()
              ? RecipeValues.Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS
              : RecipeValues.Coverage.COMPLETE,
          null);
    } catch (ValuationBudget.Limit limit) {
      return failed(RecipeValues.Status.LIMIT_EXCEEDED, limit.getMessage());
    } catch (Abort failure) {
      return failed(failure.status, failure.getMessage());
    } catch (ArithmeticException overflow) {
      return failed(RecipeValues.Status.OVERFLOW, "EXACT_OR_PUBLISHED_AMOUNT_OVERFLOW");
    }
  }

  private static RecipeValues.Material failed(RecipeValues.Status status, String reason) {
    return new RecipeValues.Material(
        status, reason, null, null, RecipeValues.Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS, null);
  }

  private static RecipeValues.Result unavailable(
      ValuationData.Manifest manifest,
      String hash,
      EffectiveFxSnapshot fx,
      RecipeValues.Status status,
      String reason) {
    return new RecipeValues.Result(
        status,
        reason,
        null,
        null,
        null,
        fx == null ? null : fx.id(),
        hash,
        manifest.configHash(),
        null,
        RecipeValues.Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS,
        null);
  }

  private static final class Search {
    final String manifestHash;
    final EconomyCurrency currency;
    final EffectiveFxSnapshot fx;
    final ValuationBudget budget;
    final RecipeValues.Materials materials;
    final Map<BrewingGraph.State, List<BrewingGraph.Mix>> incoming = new HashMap<>();

    Search(
        ValuationData.Manifest manifest,
        String hash,
        EconomyCurrency currency,
        EffectiveFxSnapshot fx,
        ValuationBudget budget) {
      this.budget = budget;
      this.manifestHash = hash;
      this.currency = currency;
      this.fx = fx;
      materials = new RecipeValues.Materials(manifest, hash, currency, fx, budget);
      // Captured graph order fixes ties by mix identity; each recursive path has its own ancestor
      // set.
      for (var mix : manifest.brewing().mixes())
        incoming.computeIfAbsent(mix.to(), ignored -> new ArrayList<>()).add(mix);
    }

    Attempt state(BrewingGraph.State state, Set<BrewingGraph.State> ancestors, int depth) {
      budget.spend();
      if (state.potion().equals("minecraft:empty") || state.potion().equals("minecraft:luck"))
        return Attempt.failed(
            RecipeValues.Status.UNSUPPORTED_RECIPE, "NO_SUPPORTED_SURVIVAL_BREWING_PATH");
      if (state.equals(WATER)) return water(depth);
      if (ancestors.contains(state))
        return Attempt.failed(RecipeValues.Status.CYCLE, "CYCLIC_BREWING_PATH");
      if (depth >= RecipeGraph.MAX_DEPTH)
        throw new Abort(RecipeValues.Status.LIMIT_EXCEEDED, "BREWING_DEPTH_LIMIT");
      var path = new HashSet<>(ancestors);
      path.add(state);
      var candidates = incoming.getOrDefault(state, List.of());
      Attempt failure =
          candidates.isEmpty()
              ? Attempt.failed(RecipeValues.Status.UNSUPPORTED_RECIPE, "NO_SUPPORTED_BREWING_PATH")
              : Attempt.failed(RecipeValues.Status.CYCLE, "CYCLIC_BREWING_PATH");
      Value best = null;
      boolean limited = false;
      for (var mix : candidates) {
        budget.spend();
        var previous = state(mix.from(), path, depth + 1);
        if (!previous.ready()) {
          failure = stronger(failure, previous);
          limited = true;
          continue;
        }
        var ingredient = material(mix.ingredient(), depth + 1);
        if (ingredient.status() != RecipeValues.Status.READY) {
          failure = stronger(failure, Attempt.failed(ingredient.status(), ingredient.reason()));
          limited = true;
          continue;
        }
        var fuel = material("minecraft:blaze_powder", depth + 1);
        if (fuel.status() != RecipeValues.Status.READY) {
          failure = stronger(failure, Attempt.failed(fuel.status(), fuel.reason()));
          limited = true;
          continue;
        }
        Rational exact =
            BaseValues.bounded(
                previous.value().exact().add(BaseValues.bounded(ingredient.exact().divide(THREE))));
        exact = BaseValues.bounded(exact.add(BaseValues.bounded(fuel.exact().divide(SIXTY))));
        boolean excluded =
            previous.value().limited()
                || ingredient.coverage() != RecipeValues.Coverage.COMPLETE
                || fuel.coverage() != RecipeValues.Coverage.COMPLETE;
        var proof =
            proof(
                state.key(),
                "BREW_BATCH3_POWDER60_" + mix.remainder(),
                mix.id(),
                3,
                exact,
                List.of(mix.from().key(), mix.ingredient(), "minecraft:blaze_powder"),
                List.of(previous.value().proof(), ingredient.explanation(), fuel.explanation()));
        Value candidate = new Value(exact, proof, excluded);
        limited |= excluded;
        if (best == null || exact.compareTo(best.exact()) < 0) best = candidate;
      }
      if (best == null) return failure;
      return Attempt.ready(new Value(best.exact(), best.proof(), limited));
    }

    private Attempt water(int depth) {
      var bottle = material("minecraft:glass_bottle", depth + 1);
      if (bottle.status() != RecipeValues.Status.READY)
        return Attempt.failed(bottle.status(), bottle.reason());
      Rational freeWater = BaseValues.inputCost(BaseValues.Input.FREE_WATER);
      var content =
          proof(
              "economy:free_water",
              "TYPED_FREE_WATER",
              "economy:brewing/free_water",
              1,
              freeWater,
              List.of(),
              List.of());
      Rational exact = BaseValues.bounded(bottle.exact().add(freeWater));
      return Attempt.ready(
          new Value(
              exact,
              proof(
                  WATER.key(),
                  "WATER_FREE_CONTENT_BOTTLE_ONCE",
                  "economy:brewing/water",
                  1,
                  exact,
                  List.of("minecraft:glass_bottle", "economy:free_water"),
                  List.of(bottle.explanation(), content)),
              bottle.coverage() != RecipeValues.Coverage.COMPLETE));
    }

    private RecipeValues.Material material(String item, int depth) {
      var value =
          item.equals("minecraft:dragon_breath")
              ? materials.dragonBreath(depth)
              : materials.lookup(item, depth);
      // A potentially cheaper path hitting a resource/numeric limit cannot be silently excluded.
      if (value.status() == RecipeValues.Status.LIMIT_EXCEEDED
          || value.status() == RecipeValues.Status.OVERFLOW)
        throw new Abort(value.status(), value.reason());
      return value;
    }

    private RecipeValues.Explanation proof(
        String item,
        String kind,
        String recipe,
        int count,
        Rational exact,
        List<String> chosen,
        List<RecipeValues.Explanation> children) {
      var hashes = children.stream().map(RecipeValues.Explanation::rootHash).toList();
      var unhashed =
          new RecipeValues.Step(
              "", item, kind, recipe, count, exact, Rational.ZERO, chosen, hashes, null);
      String hash =
          ValuationData.hash(
              JSON.toJson(
                  List.of(
                      "economy:brewing-proof/v1",
                      manifestHash,
                      fx.id().toString(),
                      currency.code(),
                      unhashed)));
      var root =
          new RecipeValues.Step(
              hash, item, kind, recipe, count, exact, Rational.ZERO, chosen, hashes, null);
      var steps = new ArrayList<RecipeValues.Step>();
      steps.add(root);
      boolean truncated = false;
      for (var child : children) {
        truncated |= child.truncated();
        for (var step : child.steps()) {
          if (steps.size() < RecipeGraph.MAX_EXPLANATION) steps.add(step);
          else truncated = true;
        }
      }
      return new RecipeValues.Explanation(hash, steps, truncated);
    }
  }

  private static Attempt stronger(Attempt a, Attempt b) {
    return priority(b.status()) > priority(a.status()) ? b : a;
  }

  private static int priority(RecipeValues.Status status) {
    return switch (status) {
      case FX_UNAVAILABLE -> 7;
      case MOD_UNREGISTERED -> 6;
      case UNPRICED_OPERATOR -> 5;
      case MISSING_INPUT -> 4;
      case UNSUPPORTED_IDENTITY -> 3;
      case UNSUPPORTED_RECIPE -> 2;
      case CYCLE -> 1;
      default -> 0;
    };
  }
}
