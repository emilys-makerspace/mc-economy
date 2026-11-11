package ca.emilybelnavis.mcmods.economy.banker.entity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.*;

public final class BankerCatalog {
  private static final DeferredRegister<EntityType<?>> TYPES =
      DeferredRegister.create(Registries.ENTITY_TYPE, "economy");
  private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("economy");
  public static final DeferredHolder<EntityType<?>, EntityType<BankerEntity>> BANKER =
      TYPES.register(
          "banker",
          () ->
              EntityType.Builder.of(BankerEntity::new, MobCategory.MISC)
                  .sized(0.6F, 1.95F)
                  .clientTrackingRange(8)
                  .updateInterval(3)
                  .build("economy:banker"));
  public static final DeferredItem<DeferredSpawnEggItem> SPAWN_EGG =
      ITEMS.register(
          "banker_spawn_egg",
          () -> new DeferredSpawnEggItem(BANKER, 0x283442, 0xD4B66A, new Item.Properties()));

  private BankerCatalog() {}

  public static void initialize(IEventBus bus) {
    TYPES.register(bus);
    ITEMS.register(bus);
    bus.addListener(
        (EntityAttributeCreationEvent event) ->
            event.put(BANKER.get(), BankerEntity.attributes().build()));
    bus.addListener(BankerCatalog::creativeContents);
  }

  private static void creativeContents(BuildCreativeModeTabContentsEvent event) {
    if (event.getTabKey().equals(CreativeModeTabs.SPAWN_EGGS)) event.accept(SPAWN_EGG.get());
  }
}
