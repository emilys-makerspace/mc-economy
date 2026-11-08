package ca.emilybelnavis.mcmods.economy.valuation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Captures reviewed stored recipe data on the server thread, never executing assembly or callbacks. */
final class OrdinaryRecipeCapture {
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> CRAFTING_REMAINDERS = Set.of("minecraft:water_bucket", "minecraft:lava_bucket",
            "minecraft:milk_bucket", "minecraft:dragon_breath", "minecraft:honey_bottle");
    private static final Map<Class<?>, RecipeSerializer<?>> TRUSTED = Map.of(
            ShapedRecipe.class, RecipeSerializer.SHAPED_RECIPE,
            ShapelessRecipe.class, RecipeSerializer.SHAPELESS_RECIPE,
            StonecutterRecipe.class, RecipeSerializer.STONECUTTER,
            SmeltingRecipe.class, RecipeSerializer.SMELTING_RECIPE,
            BlastingRecipe.class, RecipeSerializer.BLASTING_RECIPE,
            SmokingRecipe.class, RecipeSerializer.SMOKING_RECIPE,
            CampfireCookingRecipe.class, RecipeSerializer.CAMPFIRE_COOKING_RECIPE);

    private OrdinaryRecipeCapture() {}

    static RecipeGraph.Graph capture(MinecraftServer server, Map<String, List<String>> expandedTags) {return capture(server,expandedTags,Set.of());}
    static RecipeGraph.Graph capture(MinecraftServer server, Map<String, List<String>> expandedTags,Set<String> trustedInputs) {
        if (!server.isSameThread()) throw new IllegalStateException("Recipe capture requires server thread");
        var installed = server.getRecipeManager().getRecipes();
        if (installed.size() > RecipeGraph.MAX_RECIPES) throw new IllegalArgumentException("RECIPE_COUNT_LIMIT");
        var nodes = new ArrayList<RecipeGraph.Node>();
        var rejected = new TreeMap<String, String>();
        int bytes = tagBytes(expandedTags);
        for (var holder : installed.stream().sorted(Comparator.comparing(h -> h.id().toString())).toList()) {
            String id = holder.id().toString();
            try {
                RecipeGraph.Node node = capture(id, holder.value(), server, expandedTags,trustedInputs);
                bytes = addBytes(bytes, JSON.toJson(node));
                nodes.add(node);
            } catch (UnsupportedRecipe unsupported) {
                bytes = addBytes(bytes, id + unsupported.reason);
                rejected.put(id, unsupported.reason);
            }
        }
        var graph = new RecipeGraph.Graph(nodes, rejected, expandedTags);
        // The incremental guard avoids building unbounded collections; this includes JSON framing too.
        if (RecipeGraph.encode(graph).getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("RECIPE_GRAPH_BYTE_LIMIT");
        return graph;
    }

    private static RecipeGraph.Node capture(String id, Recipe<?> recipe, MinecraftServer server,
                                             Map<String, List<String>> tags,Set<String> trustedInputs) {
        RecipeSerializer<?> serializer = TRUSTED.get(recipe.getClass());
        if (serializer == null) throw unsupported("UNSUPPORTED_RECIPE_CLASS");
        // This method is only called after the exact class gate, so it cannot dispatch to a mod override.
        if (recipe.getSerializer() != serializer) throw unsupported("UNSUPPORTED_RECIPE_SERIALIZER");
        var serializerKey = BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer);
        if (serializerKey == null || !RecipeGraph.SERIALIZERS.contains(serializerKey.toString()))
            throw unsupported("UNREGISTERED_RECIPE_SERIALIZER");
        ItemStack output = recipe.getResultItem(server.registryAccess());
        if (output.isEmpty() || !output.isComponentsPatchEmpty()) throw unsupported("UNSUPPORTED_OUTPUT_COMPONENTS");
        String outputId = itemId(output);
        if (outputId.equals("minecraft:air") || output.getCount() < 1 || output.getCount() > 1_000_000)
            throw unsupported("UNSUPPORTED_OUTPUT_COUNT");
        var original = recipe.getIngredients();
        if (original.isEmpty() || original.size() > 9) throw unsupported("UNSUPPORTED_INGREDIENT_COUNT");
        var ingredients = new ArrayList<RecipeGraph.Ingredient>();
        var exclusions = new ArrayList<String>();
        for (int slot = 0; slot < original.size(); slot++) ingredients.add(ingredient(original.get(slot), slot, tags, exclusions,trustedInputs));
        if (ingredients.stream().allMatch(RecipeGraph.Ingredient::emptySlot)) throw unsupported("NO_CONSUMED_INGREDIENT");
        return new RecipeGraph.Node(id, serializerKey.toString(), outputId, output.getCount(), ingredients, exclusions);
    }

    private static RecipeGraph.Ingredient ingredient(Ingredient ingredient, int slot,
            Map<String, List<String>> tags, List<String> exclusions,Set<String> trustedInputs) {
        if (ingredient.isCustom()) throw unsupported("CUSTOM_INGREDIENT");
        Ingredient.Value[] values = ingredient.getValues();
        if (values.length > RecipeGraph.MAX_ALTERNATIVES) throw new IllegalArgumentException("INGREDIENT_ALTERNATIVE_LIMIT");
        var selectors = new TreeSet<String>();
        var alternatives = new TreeSet<String>();
        for (Ingredient.Value value : values) {
            if (value instanceof Ingredient.ItemValue item) {
                ItemStack stack = item.item();
                if (stack.isEmpty() || stack.getCount() != 1 || !stack.isComponentsPatchEmpty())
                    throw unsupported("UNSUPPORTED_INGREDIENT_COMPONENTS");
                String id = itemId(stack);
                selectors.add(id);
                addAlternative(id, slot, alternatives, exclusions,trustedInputs);
            } else if (value instanceof Ingredient.TagValue tag) {
                String id = tag.tag().location().toString();
                selectors.add("#" + id);
                List<String> members = tags.getOrDefault(id, List.of());
                if (members.size() > RecipeGraph.MAX_ALTERNATIVES) throw new IllegalArgumentException("INGREDIENT_ALTERNATIVE_LIMIT");
                for (String member : members) addAlternative(member, slot, alternatives, exclusions,trustedInputs);
            } else throw unsupported("UNKNOWN_INGREDIENT_VALUE");
        }
        // Empty values are a shaped hole. An empty expansion retains its selectors and is unsatisfiable.
        return new RecipeGraph.Ingredient(List.copyOf(selectors), List.copyOf(alternatives));
    }

    private static void addAlternative(String id, int slot, Set<String> alternatives, List<String> exclusions,Set<String> trustedInputs) {
        if (!ValuationData.catalog().entries().containsKey(id)&&!trustedInputs.contains(id)) {
            exclusions.add("slot=" + slot + ":" + id + ":UNTRUSTED_REMAINDER_SEMANTICS");
        } else if (CRAFTING_REMAINDERS.contains(id)) {
            exclusions.add("slot=" + slot + ":" + id + ":RETURNED_CONTAINER");
        } else alternatives.add(id);
        if (alternatives.size() > RecipeGraph.MAX_ALTERNATIVES) throw new IllegalArgumentException("INGREDIENT_ALTERNATIVE_LIMIT");
        if (exclusions.size() > 40_000) throw new IllegalArgumentException("INGREDIENT_EXCLUSION_LIMIT");
    }
    private static String itemId(ItemStack stack) {
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) throw unsupported("UNREGISTERED_ITEM");
        return id.toString();
    }
    private static int tagBytes(Map<String, List<String>> tags) {
        if (tags.size() > RecipeGraph.MAX_RECIPES) throw new IllegalArgumentException("TAG_COUNT_LIMIT");
        int bytes = 0;
        for (var tag : tags.entrySet()) {
            bytes = addBytes(bytes, tag.getKey());
            if (tag.getValue().size() > RecipeGraph.MAX_ALTERNATIVES) throw new IllegalArgumentException("TAG_ALTERNATIVE_LIMIT");
            for (String item : tag.getValue()) bytes = addBytes(bytes, item);
        }
        return bytes;
    }
    private static int addBytes(int bytes, String text) {
        long total = (long) bytes + text.getBytes(StandardCharsets.UTF_8).length;
        if (total > MAX_BYTES) throw new IllegalArgumentException("RECIPE_GRAPH_BYTE_LIMIT");
        return (int) total;
    }
    private static UnsupportedRecipe unsupported(String reason) { return new UnsupportedRecipe(reason); }
    private static final class UnsupportedRecipe extends RuntimeException {
        final String reason;
        UnsupportedRecipe(String reason) { super(reason); this.reason = reason; }
    }
}
