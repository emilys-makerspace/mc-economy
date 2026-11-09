package ca.emilybelnavis.mcmods.economy.banker.dialogue;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.*;

public final class BankerMenuCatalog {
  private static final DeferredRegister<MenuType<?>> MENUS =
      DeferredRegister.create(Registries.MENU, "economy");
  public static final DeferredHolder<MenuType<?>, MenuType<BankerMenu>> BANKER =
      MENUS.register("banker_dialogue", () -> IMenuTypeExtension.create(BankerMenu::fromNetwork));

  private BankerMenuCatalog() {}

  public static void initialize(IEventBus bus) {
    MENUS.register(bus);
    BankerNetwork.initialize(bus);
  }
}
