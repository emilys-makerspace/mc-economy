package ca.emilybelnavis.mcmods.economy.banker.desk;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

public final class DeskCatalog {
  private static final DeferredRegister<Block> BLOCKS =
      DeferredRegister.create(Registries.BLOCK, "economy");
  private static final DeferredRegister<Item> ITEMS =
      DeferredRegister.create(Registries.ITEM, "economy");
  private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "economy");
  public static final DeferredHolder<Block, DeskBlock> DESK =
      BLOCKS.register(
          "banker_desk",
          () ->
              new DeskBlock(
                  BlockBehaviour.Properties.of()
                      .strength(2.5F)
                      .sound(SoundType.WOOD)
                      .noOcclusion()));
  public static final DeferredHolder<Item, DeskBlockItem> ITEM =
      ITEMS.register("banker_desk", () -> new DeskBlockItem(DESK.get(), new Item.Properties()));
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DeskBlockEntity>>
      BLOCK_ENTITY =
          ENTITIES.register(
              "banker_desk",
              () -> BlockEntityType.Builder.of(DeskBlockEntity::new, DESK.get()).build(null));

  private DeskCatalog() {}

  public static void initialize(IEventBus bus) {
    BLOCKS.register(bus);
    ITEMS.register(bus);
    ENTITIES.register(bus);
    bus.addListener(DeskCatalog::creative);
  }

  private static void creative(
      net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) {
    if (event.getTabKey().equals(CreativeModeTabs.FUNCTIONAL_BLOCKS)) event.accept(ITEM.get());
  }

  public static BlockState stateFor(String style, Direction facing, int cell) {
    return DESK.get()
        .defaultBlockState()
        .setValue(DeskBlock.FACING, facing)
        .setValue(DeskBlock.CELL, cell)
        .setValue(
            DeskBlock.WOOD,
            switch (style) {
              case "desert" -> DeskBlock.Wood.JUNGLE;
              case "savanna" -> DeskBlock.Wood.ACACIA;
              case "snowy", "taiga" -> DeskBlock.Wood.SPRUCE;
              default -> DeskBlock.Wood.OAK;
            });
  }
}
