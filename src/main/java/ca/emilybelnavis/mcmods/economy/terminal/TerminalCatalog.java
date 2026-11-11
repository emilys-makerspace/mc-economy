package ca.emilybelnavis.mcmods.economy.terminal;

import ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

/**
 * Stable physical identities. Cash and account configuration never travel in crafted block items.
 */
public final class TerminalCatalog {
  private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("economy");
  private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("economy");
  private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "economy");
  private static final DeferredRegister<CreativeModeTab> TABS =
      DeferredRegister.create(Registries.CREATIVE_MODE_TAB, "economy");

  public record Entry(
      String id,
      TerminalPolicy.Kind kind,
      DeferredBlock<? extends Block> block,
      DeferredItem<? extends Item> item) {}

  private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
  public static final DeferredBlock<DecorativeFacingBlock> STOCK_TRADING_COMPUTER =
      BLOCKS.register("stock_trading_computer", () -> new DecorativeFacingBlock(properties()));
  public static final DeferredItem<BlockItem> STOCK_TRADING_COMPUTER_ITEM =
      ITEMS.register(
          "stock_trading_computer",
          () -> new BlockItem(STOCK_TRADING_COMPUTER.get(), new Item.Properties()));

  static {
    for (var kind : List.of(TerminalPolicy.Kind.ATM, TerminalPolicy.Kind.REGISTER)) {
      String base = kind.name().toLowerCase(Locale.ROOT);
      register(base, kind);
      for (var dye : DyeColor.values()) register(dye.getName() + "_" + base, kind);
    }
    register("teller", TerminalPolicy.Kind.TELLER);
    register("branch_kiosk", TerminalPolicy.Kind.KIOSK);
  }

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TerminalBlockEntity>>
      BLOCK_ENTITY =
          ENTITIES.register(
              "terminal",
              () ->
                  BlockEntityType.Builder.of(
                          TerminalBlockEntity::new,
                          ENTRIES.values().stream().map(e -> e.block().get()).toArray(Block[]::new))
                      .build(null));

  static {
    TABS.register(
        "terminals",
        () ->
            CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.economy.terminals"))
                .icon(() -> new ItemStack(ENTRIES.get("atm").item().get()))
                .displayItems(
                    (parameters, output) -> {
                      ENTRIES.values().forEach(e -> output.accept(e.item().get()));
                      output.accept(STOCK_TRADING_COMPUTER_ITEM.get());
                    })
                .build());
  }

  private TerminalCatalog() {}

  private static void register(String id, TerminalPolicy.Kind kind) {
    var block =
        BLOCKS.register(
            id,
            () ->
                kind == TerminalPolicy.Kind.REGISTER
                    ? new RegisterBlock(properties(), kind)
                    : kind == TerminalPolicy.Kind.KIOSK
                        ? new BranchKioskBlock(properties())
                        : new WideTerminalBlock(properties(), kind));
    var item = ITEMS.register(id, () -> new TerminalBlockItem(block.get(), new Item.Properties()));
    ENTRIES.put(id, new Entry(id, kind, block, item));
  }

  private static BlockBehaviour.Properties properties() {
    return BlockBehaviour.Properties.of().strength(3.5F, 6.0F).sound(SoundType.METAL).noOcclusion();
  }

  public static List<Entry> all() {
    return List.copyOf(ENTRIES.values());
  }

  public static Entry get(String id) {
    return Objects.requireNonNull(ENTRIES.get(id), "Unknown terminal");
  }

  public static void initialize(IEventBus bus) {
    BLOCKS.register(bus);
    ITEMS.register(bus);
    ENTITIES.register(bus);
    TABS.register(bus);
  }
}
