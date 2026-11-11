package ca.emilybelnavis.mcmods.economy.collection;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CollectionCatalog {
  private static final DeferredRegister<EntityType<?>> ENTITIES =
      DeferredRegister.create(Registries.ENTITY_TYPE, "economy");
  private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("economy");
  public static final DeferredHolder<EntityType<?>, EntityType<CollectionAgent>> AGENT =
      ENTITIES.register(
          "collection_agent",
          () ->
              EntityType.Builder.of(CollectionAgent::new, MobCategory.MISC)
                  .sized(0.6F, 1.8F)
                  .clientTrackingRange(8)
                  .updateInterval(3)
                  .build("economy:collection_agent"));
  public static final DeferredItem<DeferredSpawnEggItem> SPAWN_EGG =
      ITEMS.register(
          "collection_agent_spawn_egg",
          () -> new DeferredSpawnEggItem(AGENT, 0x283442, 0xB59A64, new Item.Properties()));

  private CollectionCatalog() {}

  public static void initialize(IEventBus bus) {
    ENTITIES.register(bus);
    ITEMS.register(bus);
    bus.addListener(CollectionCatalog::creativeContents);
    bus.addListener(CollectionCatalog::attributes);
    NeoForge.EVENT_BUS.addListener(CollectionCatalog::interact);
  }

  private static void creativeContents(BuildCreativeModeTabContentsEvent event) {
    if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) event.accept(SPAWN_EGG.get());
  }

  private static void interact(PlayerInteractEvent.EntityInteract event) {
    if (!(event.getTarget() instanceof CollectionAgent agent)) return;
    event.setCanceled(true);
    event.setCancellationResult(InteractionResult.CONSUME);
    if (event.getHand() == InteractionHand.MAIN_HAND
        && event.getEntity() instanceof ServerPlayer player
        && CollectionAgents.canInteract(player, agent)) CollectionDialogue.open(player, agent);
  }

  private static void attributes(EntityAttributeCreationEvent event) {
    event.put(AGENT.get(), CollectionAgent.attributes().build());
  }
}
