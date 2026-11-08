package ca.emilybelnavis.mcmods.economy.valuation;

import com.google.gson.GsonBuilder;
import java.util.*;

/** Detached pinned vanilla brewing identities. No live stacks, holders or callbacks are retained. */
public final class BrewingGraph {
    private BrewingGraph() {}
    public static final Set<String> FORMS = Set.of("minecraft:potion", "minecraft:splash_potion", "minecraft:lingering_potion");
    public static final String ADAPTER = "economy:vanilla-brewing/v1";
    public static final String RULES_HASH = ValuationData.hash(ADAPTER + "|pinned-list-access-v1|batch=3|fuel=20|powder/60|"
            + "water=free-content|bottle-once|vanilla-effect-cures-v1|dragon-breath=CAD25-content+bottle;remainder-no-salvage|ordinary-shared-budget|acyclic-cheapest");
    public record State(String form, String potion) {
        public State {
            if (!FORMS.contains(form)) throw new IllegalArgumentException("Unsupported potion form");
            ValuationData.itemId(potion);
        }
        public String key() { return form + "/" + potion; }
    }
    public record Effect(String id, int amplifier, int duration, boolean ambient, boolean visible, boolean showIcon, List<String> cures) {
        public Effect {
            ValuationData.itemId(id);
            if (amplifier < 0 || amplifier > 255 || duration < -1) throw new IllegalArgumentException("Potion effect domain");
            cures = List.copyOf(new TreeSet<>(cures));
            if (!Set.of("milk", "honey", "protected_by_totem").containsAll(cures)) throw new IllegalArgumentException("Unknown potion cure semantics");
        }
        public Effect(String id, int amplifier, int duration, boolean ambient, boolean visible, boolean showIcon) {
            this(id, amplifier, duration, ambient, visible, showIcon, List.of());
        }
    }
    public record Potion(String id, List<Effect> effects) {
        public Potion {
            ValuationData.itemId(id); effects = List.copyOf(effects);
            if (!id.startsWith("minecraft:") || effects.size() > 32) throw new IllegalArgumentException("Vanilla potion definition");
        }
    }
    public record Mix(String id, State from, State to, String ingredient, String remainder) {
        public Mix {
            ValuationData.itemId(id); Objects.requireNonNull(from); Objects.requireNonNull(to); ValuationData.itemId(ingredient);
            if (!Set.of("NONE", "GLASS_BOTTLE_NO_SALVAGE").contains(remainder)) throw new IllegalArgumentException("Brewing remainder rule");
            if (ingredient.equals("minecraft:dragon_breath") != remainder.equals("GLASS_BOTTLE_NO_SALVAGE"))
                throw new IllegalArgumentException("Breathing ingredient remainder identity");
        }
    }
    public record Graph(int version, String adapter, String rulesHash, boolean vanillaCompatible,
                        Map<String, Potion> potions, List<String> containers, List<Mix> mixes,
                        Map<String, String> exclusions, String capturedListsHash) {
        public Graph {
            if (version != 1 || !ADAPTER.equals(adapter) || !RULES_HASH.equals(rulesHash))
                throw new IllegalArgumentException("Brewing adapter version");
            ValuationData.requireHash(capturedListsHash);
            var definitions = new TreeMap<String, Potion>();
            for (var pair : potions.entrySet()) {
                if (!pair.getKey().equals(pair.getValue().id())) throw new IllegalArgumentException("Potion definition identity");
                definitions.put(pair.getKey(), pair.getValue());
            }
            if (definitions.size() > 4_096) throw new IllegalArgumentException("Potion definition limit");
            potions = Collections.unmodifiableSortedMap(definitions);
            containers = List.copyOf(new TreeSet<>(containers));
            if (!FORMS.containsAll(containers)) throw new IllegalArgumentException("Brewing container identity");
            var ordered = new TreeMap<String, Mix>();
            for (Mix mix : mixes) {
                if (!potions.containsKey(mix.from().potion()) || !potions.containsKey(mix.to().potion())
                        || !containers.contains(mix.from().form()) || !containers.contains(mix.to().form())
                        || ordered.putIfAbsent(mix.id(), mix) != null) throw new IllegalArgumentException("Brewing mix identity");
            }
            if (ordered.size() > RecipeGraph.MAX_RECIPES) throw new IllegalArgumentException("Brewing mix limit");
            mixes = List.copyOf(ordered.values());
            var rejected = new TreeMap<String, String>();
            for (var pair : exclusions.entrySet()) {
                if (pair.getKey().length() > 512 || pair.getValue().length() > 512) throw new IllegalArgumentException("Brewing exclusion bound");
                rejected.put(pair.getKey(), pair.getValue());
            }
            if (rejected.size() > 4_096) throw new IllegalArgumentException("Brewing exclusion limit");
            exclusions = Collections.unmodifiableSortedMap(rejected);
            if (vanillaCompatible && (!exclusions.isEmpty() || containers.size() != 3))
                throw new IllegalArgumentException("Incomplete vanilla brewing capture");
        }
        public String hash() { return ValuationData.hash(encode(this)); }
    }
    public static String encode(Graph graph) { return new GsonBuilder().disableHtmlEscaping().create().toJson(graph); }
    public static String components(String potion) {
        ValuationData.itemId(potion);
        return "{\"minecraft:potion_contents\":{\"potion\":\"" + potion + "\"}}";
    }
    public static State identity(String form, String components) {
        if (components == null || ValuationConfig.bytes(components) > 65_536) throw new IllegalArgumentException("Potion component bound");
        var outer = ValuationConfig.object(ValuationConfig.parse(components, 65_536));
        ValuationConfig.keys(outer, Set.of("minecraft:potion_contents"));
        var contents = ValuationConfig.object(outer.get("minecraft:potion_contents"));
        ValuationConfig.keys(contents, Set.of("potion"));
        String potion = ValuationConfig.string(contents, "potion");
        if (!components.equals(components(potion))) throw new IllegalArgumentException("Canonical potion components required");
        return new State(form, potion);
    }
    public static void validate(Graph graph, ValuationData.Inputs inputs) {
        if (!inputs.items().containsAll(graph.containers())) throw new IllegalArgumentException("Unknown brewing container");
        for (Mix mix : graph.mixes()) if (!inputs.items().contains(mix.ingredient())) throw new IllegalArgumentException("Unknown brewing ingredient");
    }
}
