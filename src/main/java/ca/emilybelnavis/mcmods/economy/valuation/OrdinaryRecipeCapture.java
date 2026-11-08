package ca.emilybelnavis.mcmods.economy.valuation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.common.crafting.CompoundIngredient;
import net.neoforged.neoforge.common.crafting.DifferenceIngredient;
import net.neoforged.neoforge.common.crafting.IntersectionIngredient;
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
        var work = new IngredientWork();
        for (int slot = 0; slot < original.size(); slot++) ingredients.add(ingredient(original.get(slot), slot, tags, exclusions,trustedInputs,work));
        if (ingredients.stream().allMatch(RecipeGraph.Ingredient::emptySlot)) throw unsupported("NO_CONSUMED_INGREDIENT");
        return new RecipeGraph.Node(id, serializerKey.toString(), outputId, output.getCount(), ingredients, exclusions);
    }

    private record DetachedIngredient(SortedSet<String> selectors,SortedSet<String> items) {}
    private static final class IngredientWork {
        int nodes;
        void visit(int depth) {
            if(depth>RecipeGraph.MAX_DEPTH||++nodes>RecipeGraph.MAX_WORK)throw new IllegalArgumentException("INGREDIENT_TREE_LIMIT");
        }
    }

    private static RecipeGraph.Ingredient ingredient(Ingredient ingredient,int slot,
            Map<String,List<String>> tags,List<String> exclusions,Set<String> trustedInputs,IngredientWork work) {
        var detached=detach(ingredient,tags,work,0);
        var alternatives=new TreeSet<String>();
        for(String id:detached.items())addAlternative(id,slot,alternatives,exclusions,trustedInputs);
        // No selectors means a true vanilla shaped hole. A custom expression that yields no
        // items retains its adapter selector, so it remains unsatisfiable rather than free.
        return new RecipeGraph.Ingredient(List.copyOf(detached.selectors()),List.copyOf(alternatives));
    }

    /** Read pinned immutable expression fields only; never call getItems/test/isSimple/type. */
    private static DetachedIngredient detach(Ingredient ingredient,Map<String,List<String>> tags,
                                             IngredientWork work,int depth) {
        work.visit(depth);
        var selectors=new TreeSet<String>();var alternatives=new TreeSet<String>();
        if(ingredient.isCustom()) {
            var custom=ingredient.getCustomIngredient();
            if(custom.getClass()==CompoundIngredient.class) {
                selectors.add("economy:ingredient/compound-v1");
                var children=((CompoundIngredient)custom).children();
                if(children.isEmpty()||children.size()>RecipeGraph.MAX_WORK)throw new IllegalArgumentException("INGREDIENT_CHILD_LIMIT");
                for(var child:children) {
                    var value=detach(child,tags,work,depth+1);selectors.addAll(value.selectors());alternatives.addAll(value.items());
                    ingredientBounds(selectors,alternatives);
                }
            } else if(custom.getClass()==DifferenceIngredient.class) {
                selectors.add("economy:ingredient/difference-v1");
                var difference=(DifferenceIngredient)custom;
                var base=detach(difference.base(),tags,work,depth+1);
                var subtracted=detach(difference.subtracted(),tags,work,depth+1);
                selectors.addAll(base.selectors());selectors.addAll(subtracted.selectors());
                alternatives.addAll(base.items());alternatives.removeAll(subtracted.items());
            } else if(custom.getClass()==IntersectionIngredient.class) {
                selectors.add("economy:ingredient/intersection-v1");
                var children=((IntersectionIngredient)custom).children();
                if(children.isEmpty()||children.size()>RecipeGraph.MAX_WORK)throw new IllegalArgumentException("INGREDIENT_CHILD_LIMIT");
                boolean first=true;
                for(var child:children) {
                    var value=detach(child,tags,work,depth+1);selectors.addAll(value.selectors());
                    if(first){alternatives.addAll(value.items());first=false;}else alternatives.retainAll(value.items());
                    ingredientBounds(selectors,alternatives);
                }
            } else throw unsupported("CUSTOM_INGREDIENT");
        } else {
            Ingredient.Value[] values=ingredient.getValues();
            if(values.length>RecipeGraph.MAX_ALTERNATIVES)throw new IllegalArgumentException("INGREDIENT_ALTERNATIVE_LIMIT");
            for(Ingredient.Value value:values) {
                if(value instanceof Ingredient.ItemValue item) {
                    ItemStack stack=item.item();
                    if(stack.isEmpty()||stack.getCount()!=1||!stack.isComponentsPatchEmpty())throw unsupported("UNSUPPORTED_INGREDIENT_COMPONENTS");
                    String id=itemId(stack);selectors.add(id);alternatives.add(id);
                } else if(value instanceof Ingredient.TagValue tag) {
                    String id=tag.tag().location().toString();selectors.add("#"+id);
                    List<String> members=tags.getOrDefault(id,List.of());
                    if(members.size()>RecipeGraph.MAX_ALTERNATIVES)throw new IllegalArgumentException("INGREDIENT_ALTERNATIVE_LIMIT");
                    alternatives.addAll(members);
                } else throw unsupported("UNKNOWN_INGREDIENT_VALUE");
                ingredientBounds(selectors,alternatives);
            }
        }
        ingredientBounds(selectors,alternatives);
        return new DetachedIngredient(Collections.unmodifiableSortedSet(selectors),Collections.unmodifiableSortedSet(alternatives));
    }
    private static void ingredientBounds(Set<String> selectors,Set<String> alternatives) {
        if(selectors.size()>RecipeGraph.MAX_ALTERNATIVES||alternatives.size()>RecipeGraph.MAX_ALTERNATIVES)
            throw new IllegalArgumentException("INGREDIENT_ALTERNATIVE_LIMIT");
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
