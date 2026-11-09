package ca.emilybelnavis.mcmods.economy.banking;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Common-side menu registration; screens are registered only by the client entry point. */
public final class BankingCatalog {
  private static final DeferredRegister<MenuType<?>> MENUS =
      DeferredRegister.create(Registries.MENU, "economy");
  public static final DeferredHolder<MenuType<?>, MenuType<BankingMenu>> BANKING =
      MENUS.register("banking", () -> IMenuTypeExtension.create(BankingMenu::fromNetwork));

  private BankingCatalog() {}

  public static void initialize(IEventBus bus) {
    MENUS.register(bus);
    BankingNetwork.initialize(bus);
  }
}
