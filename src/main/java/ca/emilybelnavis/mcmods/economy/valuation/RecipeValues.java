package ca.emilybelnavis.mcmods.economy.valuation;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.fx.EffectiveFxSnapshot;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.*;

/** Pure bounded ordinary-recipe evaluation over one retained manifest and captured FX publication. */
public final class RecipeValues {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Rational PROCESSING_FACTOR = Rational.of(4).divide(Rational.of(3));
    private RecipeValues() {}

    public enum Status { READY, UNPRICED_OPERATOR, MOD_UNREGISTERED, MISSING_INPUT, UNSUPPORTED_IDENTITY,
        UNSUPPORTED_RECIPE, CYCLE, LIMIT_EXCEEDED, OVERFLOW, FX_UNAVAILABLE }
    public enum Coverage { COMPLETE, SUPPORTED_PATH_WITH_EXCLUSIONS }
    public record Step(String hash, String item, String kind, String recipe, int outputCount,
                       Rational exactUnitMinor, Rational processingValue, List<String> chosenItems,
                       List<String> inputHashes, ValuationData.Source source) {
        public Step {
            Objects.requireNonNull(hash); Objects.requireNonNull(item); Objects.requireNonNull(kind);
            Objects.requireNonNull(recipe); Objects.requireNonNull(exactUnitMinor); Objects.requireNonNull(processingValue);
            chosenItems = List.copyOf(chosenItems); inputHashes = List.copyOf(inputHashes);
        }
    }
    public record Explanation(String rootHash, List<Step> steps, boolean truncated) {
        public Explanation {
            Objects.requireNonNull(rootHash); steps = List.copyOf(steps);
            if (steps.size() > RecipeGraph.MAX_EXPLANATION) throw new IllegalArgumentException("Explanation node bound");
        }
    }
    public record Result(Status status, String reason, Rational exactUnitMinor, Money unitMoney, Money totalMoney,
                         UUID fxPublication, String manifestHash, String configHash, ValuationData.Source source,
                         Coverage coverage, Explanation explanation) {
        public Result {
            Objects.requireNonNull(status); Objects.requireNonNull(reason); Objects.requireNonNull(manifestHash);
            Objects.requireNonNull(configHash); Objects.requireNonNull(coverage);
            if (status == Status.READY) {
                Objects.requireNonNull(exactUnitMinor); Objects.requireNonNull(unitMoney); Objects.requireNonNull(totalMoney);
                Objects.requireNonNull(fxPublication); Objects.requireNonNull(explanation);
            } else if (exactUnitMinor != null || unitMoney != null || totalMoney != null || explanation != null)
                throw new IllegalArgumentException("Unavailable recipe result cannot expose a partial value");
        }
    }
    private record Trace(Step step, List<Trace> children) {
        Trace { children = List.copyOf(children); }
    }
    private record Value(Rational exact, Trace trace, ValuationData.Source source, boolean limited) {}
    private record Attempt(Value value, Status status, String reason) {
        static Attempt ready(Value value) { return new Attempt(value, Status.READY, ""); }
        static Attempt failed(Status status, String reason) { return new Attempt(null, status, reason); }
        boolean ready() { return value != null; }
    }
    private static final class Abort extends RuntimeException {
        final Status status;
        Abort(Status status, String reason) { super(reason); this.status = status; }
    }

    public static Result evaluate(ValuationData.Manifest manifest, String item, String components, long quantity,
                                  EconomyCurrency target, EffectiveFxSnapshot fx) {
        Objects.requireNonNull(manifest); Objects.requireNonNull(target); ValuationData.itemId(item);
        if (quantity < 1 || quantity > 1_000_000) throw new IllegalArgumentException("Quantity limit");
        if (components == null || ValuationConfig.bytes(components) > 65_536)
            throw new IllegalArgumentException("Component identity limit");
        if (manifest.variants() != null) return VariantValues.evaluate(manifest,item,components,quantity,target,fx);
        if (manifest.brewing() != null && BrewingGraph.FORMS.contains(item))
            return BrewingValues.evaluate(manifest, item, components, quantity, target, fx);
        // Historical base-only manifests keep their original interpretation, including missing derived values.
        if (manifest.recipes() == null) return historical(BaseValues.evaluate(manifest, item, components, quantity, target, fx), item, target);
        String manifestHash = manifest.hash();
        if (!components.equals("{}"))
            return unavailable(Status.UNSUPPORTED_IDENTITY, "COMPONENT_ADAPTER_NOT_INSTALLED", manifest, manifestHash, item, fx);
        var context = new Search(manifest, manifestHash, target, fx);
        try {
            var attempt = context.item(item, Set.of(), 0);
            if (!attempt.ready()) return unavailable(attempt.status(), attempt.reason(), manifest, manifestHash, item, fx);
            Value value = attempt.value();
            long unitMinor = BaseValues.publishedMinor(value.exact());
            var unit = new Money(target, unitMinor);
            var total = new Money(target, Math.multiplyExact(unitMinor, quantity));
            return new Result(Status.READY, value.source() == null ? "DERIVED_REFERENCE_VALUE" : "EXPLICIT_REFERENCE_VALUE",
                    value.exact(), unit, total, fx.id(), manifestHash, manifest.configHash(), value.source(),
                    value.limited() ? Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS : Coverage.COMPLETE, explanation(value.trace()));
        } catch (ValuationBudget.Limit limit) {
            return unavailable(Status.LIMIT_EXCEEDED, limit.getMessage(), manifest, manifestHash, item, fx);
        } catch (Abort limit) {
            return unavailable(limit.status, limit.getMessage(), manifest, manifestHash, item, fx);
        } catch (ArithmeticException overflow) {
            return unavailable(Status.OVERFLOW, "EXACT_OR_PUBLISHED_AMOUNT_OVERFLOW", manifest, manifestHash, item, fx);
        }
    }

    private static Result historical(BaseValues.Result base, String item, EconomyCurrency target) {
        Explanation explanation = null;
        if (base.status() == BaseValues.Status.READY) {
            Trace trace = trace(base.manifestHash(), base.fxPublication(), target, item, "ANCHOR", "", 1,
                    base.exactUnitMinor(), Rational.ZERO, List.of(), List.of(), base.source());
            explanation = explanation(trace);
        }
        return new Result(Status.valueOf(base.status().name()), base.reason(), base.exactUnitMinor(), base.unitMoney(),
                base.totalMoney(), base.fxPublication(), base.manifestHash(), base.configHash(), base.source(),
                Coverage.COMPLETE, explanation);
    }
    private static Result unavailable(Status status, String reason, ValuationData.Manifest manifest, String manifestHash,
                                      String item, EffectiveFxSnapshot fx) {
        return new Result(status, reason, null, null, null, fx == null ? null : fx.id(), manifestHash,
                manifest.configHash(), manifest.sources().get(item), Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS, null);
    }

    /** Exact material seam for brewing; no published-unit rounding and no independent budget reset. */
    record Material(Status status, String reason, Rational exact, Explanation explanation, Coverage coverage,
                    ValuationData.Source source) {}
    static final class Materials {
        private final Search search;
        Materials(ValuationData.Manifest manifest, String hash, EconomyCurrency currency, EffectiveFxSnapshot fx,
                  ValuationBudget budget) { search = new Search(manifest, hash, currency, fx, budget); }
        Material content(String id,String cad,int depth) {
            try {
                search.spend();
                var source=new ValuationData.Source(new ValuationConfig.Entry(id,EconomyCurrency.CAD,cad,1),
                        "BUILTIN_BASE","economy:variant-content",VariantGraph.RULES_HASH);
                var attempt=search.anchor(id,source);
                if(!attempt.ready())return new Material(attempt.status(),attempt.reason(),null,null,Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS,null);
                var value=attempt.value();return new Material(Status.READY,"CONTENT_VALUE",value.exact(),explanation(value.trace()),Coverage.COMPLETE,source);
            }catch(ArithmeticException error){return new Material(Status.OVERFLOW,"EXACT_AMOUNT_OVERFLOW",null,null,Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS,null);}
        }
        /** Brewing-only typed acquisition rule, not generic variant/container recursion. */
        Material dragonBreath(int depth) {
            try {
                search.spend();
                if(search.manifest.variants()!=null&&!search.manifest.variants().items().containsKey("minecraft:dragon_breath"))
                    return new Material(Status.UNSUPPORTED_IDENTITY,"UNSUPPORTED_ITEM_PROTOTYPE",null,null,Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS,null);
                var override = search.manifest.sources().get("minecraft:dragon_breath");
                Attempt attempt;
                if (override != null) attempt = search.anchor("minecraft:dragon_breath", override);
                else {
                    if (depth >= RecipeGraph.MAX_DEPTH) throw new Abort(Status.LIMIT_EXCEEDED, "RECIPE_DEPTH_LIMIT");
                    var bottle = search.item("minecraft:glass_bottle", Set.of(), depth + 1);
                    if (!bottle.ready()) return new Material(bottle.status(), bottle.reason(), null, null,
                            Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS, null);
                    search.spend();
                    var contentSource = new ValuationData.Source(new ValuationConfig.Entry("economy:dragon_breath_content",
                            EconomyCurrency.CAD, "25", 1), "BUILTIN_BASE", "economy:brewing-acquisition", BrewingGraph.RULES_HASH);
                    var content = search.anchor("economy:dragon_breath_content", contentSource);
                    if (!content.ready()) return new Material(content.status(), content.reason(), null, null,
                            Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS, null);
                    Rational exact = BaseValues.bounded(bottle.value().exact().add(content.value().exact()));
                    var proof = trace(search.manifestHash, search.fx.id(), search.target, "minecraft:dragon_breath",
                            "DRAGON_BREATH_BOTTLE_CONTENT", "economy:brewing/dragon_breath_acquisition", 1, exact,
                            Rational.ZERO, List.of("minecraft:glass_bottle", "economy:dragon_breath_content"),
                            List.of(bottle.value().trace(), content.value().trace()), null);
                    attempt = Attempt.ready(new Value(exact, proof, null, bottle.value().limited()));
                }
                if (!attempt.ready()) return new Material(attempt.status(), attempt.reason(), null, null,
                        Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS, null);
                var value = attempt.value();
                return new Material(Status.READY, "DRAGON_BREATH_ACQUISITION", value.exact(), explanation(value.trace()),
                        value.limited() ? Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS : Coverage.COMPLETE, value.source());
            } catch (Abort failure) {
                return new Material(failure.status, failure.getMessage(), null, null, Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS, null);
            } catch (ArithmeticException failure) {
                return new Material(Status.OVERFLOW, "EXACT_AMOUNT_OVERFLOW", null, null, Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS, null);
            }
        }
        Material lookup(String item, int depth) {
            try {
                var attempt = search.item(item, Set.of(), depth);
                if (!attempt.ready()) return new Material(attempt.status(), attempt.reason(), null, null,
                        Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS, null);
                var value = attempt.value();
                return new Material(Status.READY, "EXACT_MATERIAL", value.exact(), explanation(value.trace()),
                        value.limited() ? Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS : Coverage.COMPLETE, value.source());
            } catch (Abort failure) {
                return new Material(failure.status, failure.getMessage(), null, null, Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS, null);
            } catch (ArithmeticException failure) {
                return new Material(Status.OVERFLOW, "EXACT_AMOUNT_OVERFLOW", null, null, Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS, null);
            }
        }
    }

    private static final class Search {
        final ValuationData.Manifest manifest;
        final String manifestHash;
        final EconomyCurrency target;
        final EffectiveFxSnapshot fx;
        final Map<String, List<RecipeGraph.Node>> byOutput = new HashMap<>();
        final ValuationBudget budget;
        Search(ValuationData.Manifest manifest, String manifestHash, EconomyCurrency target, EffectiveFxSnapshot fx) {
            this(manifest, manifestHash, target, fx, new ValuationBudget());
        }
        Search(ValuationData.Manifest manifest, String manifestHash, EconomyCurrency target, EffectiveFxSnapshot fx, ValuationBudget budget) {
            this.budget = budget;
            this.manifest = manifest; this.manifestHash = manifestHash; this.target = target; this.fx = fx;
            // Graph construction freezes recipe-ID ordering; indexing does not execute any recipe callbacks.
            for (var node : manifest.recipes().recipes()) byOutput.computeIfAbsent(node.output(), ignored -> new ArrayList<>()).add(node);
        }
        /** Count every dependency visit, recipe candidate and ingredient alternative, including anchors. */
        void spend() {
            budget.spend();
        }
        Attempt item(String item, Set<String> ancestors, int depth) {
            spend();
            if (!manifest.inputs().items().contains(item)) return Attempt.failed(Status.UNSUPPORTED_IDENTITY, "ITEM_NOT_IN_CAPTURED_REGISTRY");
            if(manifest.variants()!=null&&!manifest.variants().items().containsKey(item))
                return Attempt.failed(manifest.catalog().entries().containsKey(item)?Status.UNSUPPORTED_IDENTITY:Status.MOD_UNREGISTERED,"UNSUPPORTED_ITEM_PROTOTYPE");
            var catalog = manifest.catalog().entries().get(item);
            if (catalog != null && catalog.classification() == ValuationData.Classification.UNPRICED_OPERATOR)
                return Attempt.failed(Status.UNPRICED_OPERATOR, "OPERATOR_ONLY_ITEM");
            boolean stick = item.equals("minecraft:stick");
            var variantRule=manifest.variants()==null?null:manifest.variants().rules().get(item);
            if (catalog != null && catalog.classification() == ValuationData.Classification.VARIANT && !stick
                    && (variantRule==null||variantRule.kind().equals("TYPED")))
                return Attempt.failed(Status.UNSUPPORTED_IDENTITY, "VARIANT_ADAPTER_NOT_INSTALLED");
            var source = manifest.sources().get(item);
            if (source != null) return anchor(item, source);
            if (catalog == null) {
                boolean claimed = manifest.contributions().stream().anyMatch(c -> c.claimedItems().contains(item));
                return Attempt.failed(claimed ? Status.MISSING_INPUT : Status.MOD_UNREGISTERED,
                        claimed ? "MOD_DERIVATION_NOT_OPTED_IN" : "MOD_ITEM_NOT_REGISTERED");
            }
            if (ancestors.contains(item)) return Attempt.failed(Status.CYCLE, "CYCLIC_RECIPE_PATH");
            if (depth >= RecipeGraph.MAX_DEPTH) throw new Abort(Status.LIMIT_EXCEEDED, "RECIPE_DEPTH_LIMIT");
            var path = new HashSet<>(ancestors); path.add(item);
            if (stick) {
                spend();
                var planks = item("minecraft:oak_planks", path, depth + 1);
                if (!planks.ready()) return planks;
                Rational exact = BaseValues.bounded(planks.value().exact().multiply(Rational.of(2)).divide(Rational.of(4)));
                Trace trace = trace(manifestHash, fx.id(), target, item, "CANONICAL_MATERIAL", "economy:canonical_oak_sticks", 4,
                        exact, Rational.ZERO, List.of("minecraft:oak_planks", "minecraft:oak_planks"),
                        List.of(planks.value().trace(), planks.value().trace()), null);
                return Attempt.ready(new Value(exact, trace, null, planks.value().limited()));
            }
            if(variantRule!=null&&variantRule.kind().equals("SUM"))return variant(item,variantRule,path,depth);
            // These approved calibrated counterparts are canonical sources, just like oak-based sticks.
            // Otherwise cheaper intact ore (notably Nether gold ore) would erase the approved ratio.
            if (RecipeGraph.PROCESSING_INPUTS.containsKey(item)) return processing(item, path, depth);
            List<RecipeGraph.Node> recipes = byOutput.getOrDefault(item, List.of());
            Value best = null;
            Attempt failure = recipes.isEmpty() ? Attempt.failed(Status.UNSUPPORTED_RECIPE, "NO_SUPPORTED_RECIPE")
                    : Attempt.failed(Status.CYCLE, "CYCLIC_RECIPE_PATH");
            boolean limited = !manifest.recipes().exclusions().isEmpty();
            for (var recipe : recipes) {
                spend();
                var candidate = recipe(recipe, path, depth);
                if (!candidate.ready()) { failure = stronger(failure, candidate); limited = true; continue; }
                limited |= candidate.value().limited();
                // ID order is canonical. Equal cost therefore keeps the lexically first recipe.
                if (best == null || candidate.value().exact().compareTo(best.exact()) < 0) best = candidate.value();
            }
            if (best == null) return failure;
            return Attempt.ready(new Value(best.exact(), best.trace(), best.source(), limited));
        }
        Attempt variant(String item,VariantGraph.Rule rule,Set<String> ancestors,int depth) {
            Rational total=Rational.ZERO;var children=new ArrayList<Trace>();var chosen=new ArrayList<String>();boolean limited=false;
            for(var term:rule.materials()) {
                spend();var material=item(term.item(),ancestors,depth+1);if(!material.ready())return material;
                total=BaseValues.bounded(total.add(BaseValues.bounded(material.value().exact().multiply(Rational.of(term.numerator())).divide(Rational.of(term.denominator())))));
                chosen.add(term.item()+"*"+term.numerator()+"/"+term.denominator());children.add(material.value().trace());limited|=material.value().limited();
            }
            if(rule.contentCad()!=null) {
                spend();var source=new ValuationData.Source(new ValuationConfig.Entry("economy:variant_content",EconomyCurrency.CAD,rule.contentCad(),1),
                        "BUILTIN_BASE","economy:variant-content",VariantGraph.RULES_HASH);
                var content=anchor("economy:variant_content",source);if(!content.ready())return content;
                total=BaseValues.bounded(total.add(content.value().exact()));children.add(content.value().trace());chosen.add("economy:variant_content");
            }
            return Attempt.ready(new Value(total,trace(manifestHash,fx.id(),target,item,"VARIANT_MATERIAL_RULE",
                    "economy:variant/"+item.replace(':','/'),1,total,Rational.ZERO,chosen,children,null),null,limited));
        }
        Attempt processing(String item, Set<String> ancestors, int depth) {
            String raw = RecipeGraph.PROCESSING_INPUTS.get(item);
            RecipeGraph.Node selected = null;
            for (var node : byOutput.getOrDefault(item, List.of())) {
                spend();
                var consumed = node.ingredients().stream().filter(i -> !i.emptySlot()).toList();
                if (node.cooking() && node.count() == 1 && consumed.size() == 1 && consumed.getFirst().items().contains(raw)
                        && selected == null) selected = node;
            }
            if (selected == null) return Attempt.failed(Status.UNSUPPORTED_RECIPE, "PROCESSING_WITNESS_REQUIRED");
            spend();
            var input = item(raw, ancestors, depth + 1);
            if (!input.ready()) return input;
            Rational exact = BaseValues.bounded(input.value().exact().multiply(PROCESSING_FACTOR));
            Rational delta = BaseValues.bounded(exact.subtract(input.value().exact()));
            Trace proof = trace(manifestHash, fx.id(), target, item, "PROCESSING_VALUE", selected.id(), 1,
                    exact, delta, List.of(raw), List.of(input.value().trace()), null);
            return Attempt.ready(new Value(exact, proof, null, input.value().limited() || !selected.exclusions().isEmpty()));
        }
        Attempt anchor(String item, ValuationData.Source source) {
            var entry = source.entry();
            if (fx == null || !fx.rates().containsKey(entry.currency()) || !fx.rates().containsKey(target))
                return Attempt.failed(Status.FX_UNAVAILABLE, "CAPTURED_FX_MEMBERSHIP_REQUIRED");
            Rational exact = BaseValues.bounded(entry.exactMinor());
            exact = BaseValues.bounded(exact.multiply(Rational.of(fx.rates().get(entry.currency()))));
            exact = BaseValues.bounded(exact.divide(Rational.of(fx.rates().get(target))));
            exact = BaseValues.bounded(exact.multiply(Rational.of(target.minorUnitsPerMajor())));
            exact = BaseValues.bounded(exact.divide(Rational.of(entry.currency().minorUnitsPerMajor())));
            Trace trace = trace(manifestHash, fx.id(), target, item, "ANCHOR", "", 1, exact, Rational.ZERO, List.of(), List.of(), source);
            return Attempt.ready(new Value(exact, trace, source, false));
        }
        Attempt recipe(RecipeGraph.Node node, Set<String> ancestors, int depth) {
            Rational total = Rational.ZERO;
            var chosen = new ArrayList<String>();
            var children = new ArrayList<Trace>();
            boolean valid = true, limited = !node.exclusions().isEmpty();
            Attempt failure = Attempt.failed(Status.CYCLE, "CYCLIC_RECIPE_PATH");
            for (var ingredient : node.ingredients()) {
                if (ingredient.emptySlot()) continue;
                Value best = null;
                String chosenItem = null;
                Attempt slotFailure = Attempt.failed(Status.CYCLE, "CYCLIC_RECIPE_PATH");
                if (ingredient.items().isEmpty()) slotFailure = Attempt.failed(Status.MISSING_INPUT, "EMPTY_CAPTURED_INGREDIENT");
                for (String alternative : ingredient.items()) {
                    spend();
                    var value = item(alternative, ancestors, depth + 1);
                    if (!value.ready()) { limited = true; slotFailure = stronger(slotFailure, value); continue; }
                    limited |= value.value().limited();
                    if (best == null || value.value().exact().compareTo(best.exact()) < 0) {
                        best = value.value(); chosenItem = alternative;
                    }
                }
                if (best == null) { valid = false; failure = stronger(failure, slotFailure); continue; }
                total = BaseValues.bounded(total.add(best.exact()));
                chosen.add(chosenItem); children.add(best.trace());
            }
            if (!valid) return failure;
            Rational exact = BaseValues.bounded(total.divide(Rational.of(node.count())));
            Trace trace = trace(manifestHash, fx.id(), target, node.output(), "RECIPE",
                    node.id(), node.count(), exact, Rational.ZERO, chosen, children, null);
            return Attempt.ready(new Value(exact, trace, null, limited));
        }
    }

    /** Failures are merged canonically; a mixed missing/cyclic family is missing, not globally cyclic. */
    private static Attempt stronger(Attempt a, Attempt b) { return priority(b.status()) > priority(a.status()) ? b : a; }
    private static int priority(Status status) {
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
    private static Trace trace(String manifest, UUID fx, EconomyCurrency target, String item, String kind, String recipe,
                               int count, Rational exact, Rational processing, List<String> chosen, List<Trace> children,
                               ValuationData.Source source) {
        List<String> hashes = children.stream().map(child -> child.step().hash()).toList();
        var unhashed = new Step("", item, kind, recipe, count, exact, processing, chosen, hashes, source);
        String hash = ValuationData.hash(JSON.toJson(List.of("economy:recipe-proof/v1", manifest, fx.toString(), target.code(), unhashed)));
        return new Trace(new Step(hash, item, kind, recipe, count, exact, processing, chosen, hashes, source), children);
    }
    private static Explanation explanation(Trace root) {
        var pending = new ArrayDeque<Trace>(); pending.push(root);
        var steps = new ArrayList<Step>();
        while (!pending.isEmpty() && steps.size() < RecipeGraph.MAX_EXPLANATION) {
            var current = pending.pop(); steps.add(current.step());
            for (int i = current.children().size() - 1; i >= 0; i--) pending.push(current.children().get(i));
        }
        return new Explanation(root.step().hash(), steps, !pending.isEmpty());
    }
}
