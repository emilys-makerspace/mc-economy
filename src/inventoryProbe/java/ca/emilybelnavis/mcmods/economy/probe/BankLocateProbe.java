package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.terminal.*;
import ca.emilybelnavis.mcmods.economy.village.BankPoints;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Actual native /locate parsing, registry mapping and POI lifecycle in a fresh disposable world.
 */
public final class BankLocateProbe {
  public static volatile String result, failure;
  private static int stage, next;
  private static BlockPos anchor;

  public static boolean tick(MinecraftServer server) throws Exception {
    if (server.getPlayerList().getPlayers().isEmpty()) return false;
    var level = server.overworld();
    var player = server.getPlayerList().getPlayers().getFirst();
    if (stage == 0) {
      require(
          server
              .getWorldPath(LevelResource.ROOT)
              .toAbsolutePath()
              .normalize()
              .getFileName()
              .toString()
              .startsWith("economy-bank-locate-"),
          "disposable world");
      var block = (BranchKioskBlock) TerminalCatalog.get("branch_kiosk").block().get();
      for (var state : block.getStateDefinition().getPossibleStates()) {
        boolean expected =
            state.getValue(BranchKioskBlock.CELL) == 0
                && state.getValue(WideTerminalBlock.BANK) > 0;
        require(
            PoiTypes.forState(state).map(p -> p.is(BankPoints.BANK.getKey())).orElse(false)
                == expected,
            "all brands/facings/legacy/wide mapping; no satellites or neutral anchors");
      }
      anchor = new BlockPos(player.getBlockX() + 8, 250, player.getBlockZ() + 8);
      var state =
          block
              .defaultBlockState()
              .setValue(BranchKioskBlock.WIDE, true)
              .setValue(WideTerminalBlock.BANK, 1);
      for (var p : TerminalGeometry.cells(anchor, state)) {
        level.setBlock(
            p.below(),
            Blocks.STONE.defaultBlockState(),
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
      }
      for (int cell = 0; cell < 4; cell++)
        level.setBlock(
            block.cellPosition(anchor, state, cell),
            state.setValue(BranchKioskBlock.CELL, cell),
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
      next = server.getTickCount() + 4;
      stage = 1;
      return false;
    }
    if (server.getTickCount() < next) return false;
    var source =
        server
            .createCommandSourceStack()
            .withLevel(level)
            .withPosition(Vec3iToVec(anchor.offset(32, 0, 0)));
    if (stage == 1) {
      require(
          level
              .getPoiManager()
              .getType(anchor)
              .map(p -> p.is(BankPoints.BANK.getKey()))
              .orElse(false),
          "block placement automatically indexed");
      require(
          server.getCommands().getDispatcher().execute("locate poi economy:bank", source) == 32,
          "native ID locate");
      require(
          server.getCommands().getDispatcher().execute("locate poi #economy:banks", source) == 32,
          "native tag locate");
      try {
        server
            .getCommands()
            .getDispatcher()
            .execute(
                "locate poi #economy:banks",
                source.withPosition(Vec3iToVec(anchor.offset(257, 0, 0))));
        throw new IllegalStateException("outside POI radius located");
      } catch (CommandSyntaxException expected) {
      }
      var state = level.getBlockState(anchor);
      for (var p : TerminalGeometry.cells(anchor, state))
        if (!p.equals(anchor))
          require(level.getPoiManager().getType(p).isEmpty(), "satellite not indexed");
      level.getPoiManager().remove(anchor);
      require(level.getPoiManager().getType(anchor).isEmpty(), "old missing index fixture");
      ((TerminalBlockEntity) level.getBlockEntity(anchor)).onLoad();
      require(
          level
              .getPoiManager()
              .getType(anchor)
              .map(p -> p.is(BankPoints.BANK.getKey()))
              .orElse(false),
          "existing anchor onLoad backfill");
      for (var p : TerminalGeometry.cells(anchor, state))
        level.setBlock(
            p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
      next = server.getTickCount() + 4;
      stage = 2;
      return false;
    }
    require(level.getPoiManager().getType(anchor).isEmpty(), "removed anchor deindexed");
    try {
      server.getCommands().getDispatcher().execute("locate poi #economy:banks", source);
      throw new IllegalStateException("removed bank located");
    } catch (CommandSyntaxException expected) {
    }
    result =
        "PASS native ID/tag locate; 256-block bound; all brand/facing/geometry mappings; neutral/satellite exclusion; old anchor backfill; removal.";
    System.out.println("BANK_LOCATE_" + result);
    return true;
  }

  private static net.minecraft.world.phys.Vec3 Vec3iToVec(BlockPos p) {
    return new net.minecraft.world.phys.Vec3(p.getX(), p.getY(), p.getZ());
  }

  private static void require(boolean condition, String reason) {
    if (!condition) throw new IllegalStateException(reason);
  }
}
