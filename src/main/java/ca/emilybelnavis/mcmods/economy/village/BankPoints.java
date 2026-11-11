package ca.emilybelnavis.mcmods.economy.village;

import ca.emilybelnavis.mcmods.economy.terminal.BranchKioskBlock;
import ca.emilybelnavis.mcmods.economy.terminal.TerminalCatalog;
import ca.emilybelnavis.mcmods.economy.terminal.WideTerminalBlock;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Physical discovery only: a branded kiosk is neither a job site nor proof of banking authority.
 */
public final class BankPoints {
  private static final DeferredRegister<PoiType> TYPES =
      DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, "economy");
  public static final DeferredHolder<PoiType, PoiType> BANK =
      TYPES.register(
          "bank",
          () ->
              new PoiType(
                  TerminalCatalog.get("branch_kiosk")
                      .block()
                      .get()
                      .getStateDefinition()
                      .getPossibleStates()
                      .stream()
                      .filter(BankPoints::isBankAnchor)
                      .collect(Collectors.toUnmodifiableSet()),
                  0,
                  1));

  private BankPoints() {}

  public static void initialize(IEventBus bus) {
    TYPES.register(bus);
  }

  private static boolean isBankAnchor(BlockState state) {
    return state.getBlock() instanceof BranchKioskBlock
        && state.getValue(BranchKioskBlock.CELL) == 0
        && state.getValue(WideTerminalBlock.BANK) > 0;
  }

  /**
   * Backfill pre-existing kiosk anchors when their chunks load, including valid older POI sections.
   */
  public static void loaded(ServerLevel level, BlockPos position, BlockState state) {
    if (isBankAnchor(state) && level.getPoiManager().getType(position).isEmpty())
      level.getPoiManager().add(position, BANK);
  }
}
