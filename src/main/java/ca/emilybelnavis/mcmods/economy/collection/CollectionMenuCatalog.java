package ca.emilybelnavis.mcmods.economy.collection;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.*;

public final class CollectionMenuCatalog {
  private static final DeferredRegister<MenuType<?>> MENUS =
      DeferredRegister.create(Registries.MENU, "economy");
  public static final DeferredHolder<MenuType<?>, MenuType<CollectionMenu>> COLLECTION =
      MENUS.register("collection", () -> IMenuTypeExtension.create(CollectionMenu::fromNetwork));

  private CollectionMenuCatalog() {}

  public static void initialize(IEventBus bus) {
    MENUS.register(bus);
    CollectionNetwork.initialize(bus);
  }
}
