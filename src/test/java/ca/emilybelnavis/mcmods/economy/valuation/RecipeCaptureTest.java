package ca.emilybelnavis.mcmods.economy.valuation;

import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.common.crafting.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecipeCaptureTest {
    @BeforeAll static void bootstrap() {
        if (net.neoforged.fml.loading.LoadingModList.get() == null)
            net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static RecipeHolder<?> recipe(String id, Recipe<?> value) {
        return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath("fixture", id), value);
    }

    private static ShapelessRecipe shapeless(ItemStack output, Ingredient... inputs) {
        return new ShapelessRecipe("", CraftingBookCategory.MISC, output, NonNullList.of(Ingredient.EMPTY, inputs));
    }

    private static RecipeGraph.Graph capture(List<RecipeHolder<?>> recipes, Map<String, List<String>> tags) {
        return OrdinaryRecipeCapture.capture(recipes, RegistryAccess.EMPTY, tags, Set.of());
    }

    @Test void nativeRecipeTypesRetainQuantitiesHolesAndDetachedTagMembers() {
        var tag = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("fixture", "logs"));
        var pattern = ShapedRecipePattern.of(Map.of('L', Ingredient.of(tag)), "L L");
        var recipes = List.<RecipeHolder<?>>of(
                recipe("shaped", new ShapedRecipe("", CraftingBookCategory.MISC, pattern, new ItemStack(Items.STICK, 4))),
                recipe("shapeless", shapeless(new ItemStack(Items.OAK_PLANKS, 4), Ingredient.of(Items.OAK_LOG))),
                recipe("stone", new StonecutterRecipe("", Ingredient.of(Items.STONE), new ItemStack(Items.STONE_SLAB, 2))),
                recipe("smelt", new SmeltingRecipe("", CookingBookCategory.MISC, Ingredient.of(Items.RAW_IRON), new ItemStack(Items.IRON_INGOT), 0, 200)),
                recipe("blast", new BlastingRecipe("", CookingBookCategory.MISC, Ingredient.of(Items.RAW_IRON), new ItemStack(Items.IRON_INGOT), 0, 100)),
                recipe("smoke", new SmokingRecipe("", CookingBookCategory.FOOD, Ingredient.of(Items.BEEF), new ItemStack(Items.COOKED_BEEF), 0, 100)),
                recipe("campfire", new CampfireCookingRecipe("", CookingBookCategory.FOOD, Ingredient.of(Items.BEEF), new ItemStack(Items.COOKED_BEEF), 0, 600)));
        var tags = Map.of("fixture:logs", List.of("minecraft:spruce_log", "minecraft:oak_log"));
        var graph = capture(recipes, tags);
        assertTrue(graph.exclusions().isEmpty());
        assertEquals(7, graph.recipes().size());
        assertEquals(RecipeGraph.SERIALIZERS, new HashSet<>(graph.recipes().stream().map(RecipeGraph.Node::serializer).toList()));
        var shaped = graph.recipes().stream().filter(r -> r.id().equals("fixture:shaped")).findFirst().orElseThrow();
        assertEquals(4, shaped.count());
        assertEquals("minecraft:stick", shaped.output());
        assertTrue(shaped.ingredients().get(1).emptySlot());
        assertEquals(List.of("#fixture:logs"), shaped.ingredients().getFirst().selectors());
        assertEquals(List.of("minecraft:oak_log", "minecraft:spruce_log"), shaped.ingredients().getFirst().items());
        var reversed = new ArrayList<>(recipes);
        Collections.reverse(reversed);
        assertEquals(graph.hash(), capture(reversed, tags).hash());
    }

    @Test void reviewedIngredientExpressionsKeepEmptyExpressionsDistinctFromFreeSlots() {
        var both = CompoundIngredient.of(Ingredient.of(Items.OAK_LOG), Ingredient.of(Items.SPRUCE_LOG));
        var difference = DifferenceIngredient.of(both, Ingredient.of(Items.SPRUCE_LOG));
        var intersection = IntersectionIngredient.of(both, Ingredient.of(Items.SPRUCE_LOG));
        var impossible = DifferenceIngredient.of(Ingredient.of(Items.OAK_LOG), Ingredient.of(Items.OAK_LOG));
        var graph = capture(List.of(recipe("expressions", shapeless(new ItemStack(Items.STICK),
                both, difference, intersection, impossible, Ingredient.of(Items.WATER_BUCKET)))), Map.of());
        var node = graph.recipes().getFirst();
        assertEquals(List.of("minecraft:oak_log", "minecraft:spruce_log"), node.ingredients().get(0).items());
        assertEquals(List.of("minecraft:oak_log"), node.ingredients().get(1).items());
        assertEquals(List.of("minecraft:spruce_log"), node.ingredients().get(2).items());
        assertFalse(node.ingredients().get(3).emptySlot());
        assertTrue(node.ingredients().get(3).items().isEmpty());
        assertTrue(node.ingredients().get(4).items().isEmpty());
        assertEquals(List.of("slot=4:minecraft:water_bucket:RETURNED_CONTAINER"), node.exclusions());
    }

    @Test void componentAndCustomRecipeRefusalsCannotExecuteAssemblyOrOverrideCallbacks() {
        var named = new ItemStack(Items.OAK_PLANKS);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("not an ordinary output"));
        var custom = new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.STICK),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.OAK_LOG))) {
            @Override public ItemStack getResultItem(HolderLookup.Provider ignored) {
                throw new AssertionError("Custom result callback must not execute");
            }
        };
        var graph = capture(List.of(
                recipe("output", shapeless(named, Ingredient.of(Items.OAK_LOG))),
                recipe("input", shapeless(new ItemStack(Items.STICK), Ingredient.of(named))),
                recipe("empty", shapeless(new ItemStack(Items.STICK), Ingredient.EMPTY)),
                recipe("custom", custom)), Map.of());
        assertTrue(graph.recipes().isEmpty());
        assertEquals(Map.of("fixture:output", "UNSUPPORTED_OUTPUT_COMPONENTS",
                "fixture:input", "UNSUPPORTED_INGREDIENT_COMPONENTS", "fixture:empty", "NO_CONSUMED_INGREDIENT",
                "fixture:custom", "UNSUPPORTED_RECIPE_CLASS"), graph.exclusions());
    }

    @Test void excessiveTagExpansionAndExpressionDepthStopCapture() {
        var tooMany = Collections.nCopies(RecipeGraph.MAX_ALTERNATIVES + 1, "minecraft:oak_log");
        assertEquals("TAG_ALTERNATIVE_LIMIT", assertThrows(IllegalArgumentException.class,
                () -> capture(List.of(), Map.of("fixture:logs", tooMany))).getMessage());
        Ingredient deep = Ingredient.of(Items.OAK_LOG);
        for (int n = 0; n <= RecipeGraph.MAX_DEPTH; n++)
            deep = DifferenceIngredient.of(deep, Ingredient.of(Items.SPRUCE_LOG));
        var recipe = recipe("deep", shapeless(new ItemStack(Items.STICK), deep));
        assertEquals("INGREDIENT_TREE_LIMIT", assertThrows(IllegalArgumentException.class,
                () -> capture(List.of(recipe), Map.of())).getMessage());
    }
}
