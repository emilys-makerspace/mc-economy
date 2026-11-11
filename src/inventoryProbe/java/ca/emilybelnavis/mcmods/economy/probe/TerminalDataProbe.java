package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.terminal.TerminalCatalog;
import java.util.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;

/** Recipes and registry tags are checked after Minecraft's actual data reload. */
public final class TerminalDataProbe {
  private TerminalDataProbe() {}

  public static void run(ServerLevel level) {
    require(TerminalCatalog.all().size() == 35, "35 physical identities");
    for (var family : List.of("atm", "register")) {
      var tag =
          TagKey.create(
              Registries.ITEM,
              ResourceLocation.fromNamespaceAndPath(
                  "economy", family.equals("atm") ? "atms" : "registers"));
      var sources =
          TerminalCatalog.all().stream()
              .filter(e -> e.id().equals(family) || e.id().endsWith("_" + family))
              .toList();
      require(sources.size() == 17, "17 variants per family");
      for (var source : sources)
        require(new ItemStack(source.item().get()).is(tag), "loaded family tag");
      for (var color : DyeColor.values()) {
        var target = TerminalCatalog.get(color.getName() + "_" + family);
        var dye =
            BuiltInRegistries.ITEM.get(
                ResourceLocation.withDefaultNamespace(color.getName() + "_dye"));
        var recipe = recipe(level, target.id());
        for (var source : sources) {
          var input =
              CraftingInput.of(
                  2, 1, List.of(new ItemStack(source.item().get()), new ItemStack(dye)));
          require(recipe.matches(input, level), "recolor matches every family source");
          var output = recipe.assemble(input, level.registryAccess());
          require(output.is(target.item().get()) && output.getCount() == 1, "one recolored result");
        }
      }
    }
    for (var base : List.of("atm", "register", "teller")) {
      var iron = Items.IRON_INGOT;
      var glass = Items.GLASS_PANE;
      var redstone = Items.REDSTONE;
      var chest = Items.CHEST;
      var ingredients =
          base.equals("register")
              ? List.of(
                  ItemStack.EMPTY,
                  new ItemStack(glass),
                  ItemStack.EMPTY,
                  new ItemStack(iron),
                  new ItemStack(redstone),
                  new ItemStack(iron),
                  ItemStack.EMPTY,
                  new ItemStack(chest),
                  ItemStack.EMPTY)
              : List.of(
                  new ItemStack(iron),
                  new ItemStack(glass),
                  new ItemStack(iron),
                  new ItemStack(redstone),
                  new ItemStack(chest),
                  new ItemStack(redstone),
                  new ItemStack(iron),
                  new ItemStack(base.equals("teller") ? Items.HOPPER : iron),
                  new ItemStack(iron));
      var input = CraftingInput.of(3, 3, ingredients);
      var recipe = recipe(level, base);
      require(recipe.matches(input, level), "base shaped recipe");
      var result = recipe.assemble(input, level.registryAccess());
      require(
          result.is(TerminalCatalog.get(base).item().get()) && result.getCount() == 1,
          "base result");
    }
    System.out.println("TERMINAL_DATA_PASS 35 recipes; 544 recolor source combinations; tags");
  }

  private static CraftingRecipe recipe(ServerLevel level, String id) {
    return (CraftingRecipe)
        level
            .getRecipeManager()
            .byKey(ResourceLocation.fromNamespaceAndPath("economy", id))
            .orElseThrow()
            .value();
  }

  private static void require(boolean value, String label) {
    if (!value) throw new IllegalStateException("Terminal data: " + label);
  }
}
