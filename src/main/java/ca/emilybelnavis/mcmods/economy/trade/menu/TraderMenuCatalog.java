package ca.emilybelnavis.mcmods.economy.trade.menu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.*;

public final class TraderMenuCatalog {
  private static final DeferredRegister<MenuType<?>> MENUS =
      DeferredRegister.create(Registries.MENU, "economy");
  public static final DeferredHolder<MenuType<?>, MenuType<TraderMenu>> TRADER =
      MENUS.register("trader", () -> IMenuTypeExtension.create(TraderMenu::fromNetwork));

  private TraderMenuCatalog() {}

  public static void initialize(IEventBus bus) {
    MENUS.register(bus);
    TraderNetwork.initialize(bus);
  }
}
