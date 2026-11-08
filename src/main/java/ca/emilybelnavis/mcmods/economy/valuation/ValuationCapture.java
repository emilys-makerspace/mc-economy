package ca.emilybelnavis.mcmods.economy.valuation;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import java.io.IOException;

import java.util.TreeMap;
import java.util.TreeSet;

/** Server-thread capture only; no live registry/resource objects cross into calculation work. */
final class ValuationCapture {
    private static final int MAX_SOURCE_BYTES = 32 * 1024 * 1024;
    private ValuationCapture() {}

    record Captured(ValuationData.Inputs inputs, RecipeGraph.Graph recipes, BrewingGraph.Graph brewing, VariantGraph.Graph variants) {}

    static Captured capture(MinecraftServer server) throws IOException {return capture(server,java.util.List.of());}
    static Captured capture(MinecraftServer server,java.util.List<ValuationContributions.Provider> providers) throws IOException {
        if (!server.isSameThread()) throw new IllegalStateException("Valuation capture requires server thread");
        if (server.getRecipeManager().hadErrorsLoading()) throw new IllegalArgumentException("RECIPE_LOAD_ERRORS");
        var items = new TreeSet<String>();
        BuiltInRegistries.ITEM.keySet().forEach(id -> items.add(id.toString()));
        if (items.size() > 100_000) throw new IllegalArgumentException("REGISTRY_CAPTURE_LIMIT");
        var tags = new TreeMap<String, java.util.List<String>>();
        int tagBytes = 0;
        try (var stream = BuiltInRegistries.ITEM.getTags()) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                var pair = iterator.next();
                String id = pair.getFirst().location().toString();
                if (pair.getSecond().size() > 4_096 || tags.size() >= 100_000)
                    throw new IllegalArgumentException("TAG_CAPTURE_LIMIT");
                var members = pair.getSecond().stream().map(h -> BuiltInRegistries.ITEM.getKey(h.value()).toString()).sorted().toList();
                tagBytes = Math.addExact(tagBytes, id.length() + members.stream().mapToInt(String::length).sum());
                if (tagBytes > MAX_SOURCE_BYTES) throw new IllegalArgumentException("TAG_CAPTURE_LIMIT");
                tags.put(id, members);
            }
        }
        // Raw selected resource content captures quantities/components without invoking mod serializers.
        // Detached trusted recipe semantics below are hashed alongside selected raw source content.
        var resources = new TreeMap<String, String>();
        int bytes = 0;
        for (var entry : server.getResourceManager().listResources("recipe", id -> id.getPath().endsWith(".json")).entrySet()) {
            byte[] content;
            try (var stream = entry.getValue().open()) { content = stream.readNBytes(MAX_SOURCE_BYTES + 1); }
            bytes = Math.addExact(bytes, content.length);
            if (bytes > MAX_SOURCE_BYTES) throw new IllegalArgumentException("RECIPE_CAPTURE_LIMIT");
            if (resources.size() >= 100_000) throw new IllegalArgumentException("RECIPE_COUNT_LIMIT");
            resources.put(entry.getKey().toString(), ValuationData.hash(ValuationConfig.utf8(content)));
        }
        if (server.getRecipeManager().getRecipes().size() > 100_000) throw new IllegalArgumentException("RECIPE_COUNT_LIMIT");
        var installed = server.getRecipeManager().getRecipes().stream()
                .map(h -> h.id() + "=" + h.value().getClass().getName()).sorted().toList();
        var trusted=new TreeSet<String>();
        for(var provider:providers)if(provider.trustedAdapterVersions().contains(ValuationContributions.ORDINARY))trusted.addAll(provider.claimedItems());
        var graph = OrdinaryRecipeCapture.capture(server, tags, trusted);
        var brewing = VanillaBrewingCapture.capture(server);
        var variants = VariantCapture.capture(server);
        String recipeHash = ValuationData.hash(resources + "\n" + installed + "\n" + graph.hash() + "\n" + brewing.hash() + "\n" + variants.hash());
        String tagsHash = ValuationData.hash(tags.toString());
        String featuresHash = ValuationData.hash(new TreeSet<>(net.minecraft.world.flag.FeatureFlags.REGISTRY
                .toNames(server.getWorldData().enabledFeatures())).toString());
        return new Captured(new ValuationData.Inputs(items, recipeHash, tagsHash, featuresHash), graph, brewing, variants);
    }
}
