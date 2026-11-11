package ca.emilybelnavis.mcmods.economy.terminal;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TerminalGeometryLoadingTest {
  private static BranchKioskBlock block;

  @BeforeAll
  static void bootstrap() {
    if (net.neoforged.fml.loading.LoadingModList.get() == null)
      net.neoforged.fml.loading.LoadingModList.of(
          List.of(), List.of(), List.of(), List.of(), Map.of());
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    // Standalone fixture has no mod registration events. Register one real kiosk for these checks.
    var registry = (MappedRegistry<net.minecraft.world.level.block.Block>) BuiltInRegistries.BLOCK;
    registry.unfreeze();
    try {
      block =
          Registry.register(
              registry,
              ResourceLocation.fromNamespaceAndPath("economy_test", "loading_kiosk"),
              new BranchKioskBlock(BlockBehaviour.Properties.of()));
    } finally {
      registry.freeze();
    }
  }

  @Test
  void boundaryKioskDefersObservationUntilBothChunksLoadInEveryFacing() {
    for (var facing : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
      // Local right lies across the x=0 or z=0 chunk boundary in each orientation.
      var right = facing.getCounterClockWise();
      var anchor = new BlockPos(right.getStepX() > 0 ? -1 : 0, 64, right.getStepZ() > 0 ? -1 : 0);
      var state =
          block
              .defaultBlockState()
              .setValue(BranchKioskBlock.WIDE, true)
              .setValue(WideTerminalBlock.FACING, facing);
      var fixture = new LoadedBlocks();
      for (int cell = 0; cell < 2; cell++) {
        var position = block.cellPosition(anchor, state, cell);
        fixture.blocks.put(position.below(), Blocks.STONE.defaultBlockState());
      }
      for (int cell = 0; cell < block.cellCount(state); cell++)
        fixture.blocks.put(
            block.cellPosition(anchor, state, cell), state.setValue(BranchKioskBlock.CELL, cell));
      var anchorChunk = new ChunkPos(anchor);
      var neighborChunk = new ChunkPos(block.cellPosition(anchor, state, 1));
      assertNotEquals(anchorChunk, neighborChunk, facing.toString());
      fixture.loaded.add(anchorChunk);
      assertFalse(TerminalGeometry.fullyLoaded(fixture.level, anchor), facing.toString());
      fixture.loaded.add(neighborChunk);
      assertTrue(TerminalGeometry.fullyLoaded(fixture.level, anchor), facing.toString());
      // Genuine loaded damage remains observable; it must not be mistaken for unavailability.
      fixture.blocks.remove(block.cellPosition(anchor, state, 1));
      assertTrue(TerminalGeometry.fullyLoaded(fixture.level, anchor), facing.toString());
      fixture.loaded.remove(anchorChunk);
      assertFalse(TerminalGeometry.fullyLoaded(fixture.level, anchor), facing.toString());
    }
  }

  @Test
  void legacyNarrowKioskNeedsOnlyItsOwnChunk() {
    var anchor = new BlockPos(0, 64, 0);
    var fixture = new LoadedBlocks();
    fixture.blocks.put(anchor, block.defaultBlockState());
    fixture.loaded.add(new ChunkPos(anchor));
    assertTrue(TerminalGeometry.fullyLoaded(fixture.level, anchor));
    // A missing anchor is confirmed absence when its own chunk is available.
    fixture.blocks.remove(anchor);
    assertTrue(TerminalGeometry.fullyLoaded(fixture.level, anchor));
  }

  /** Minimal loaded-world view: any accidental read from an unavailable chunk fails the check. */
  private static final class LoadedBlocks {
    final Set<ChunkPos> loaded = new HashSet<>();
    final Map<BlockPos, BlockState> blocks = new HashMap<>();
    final LevelReader level =
        (LevelReader)
            Proxy.newProxyInstance(
                LevelReader.class.getClassLoader(),
                new Class<?>[] {LevelReader.class},
                (proxy, method, args) -> {
                  var position = (BlockPos) args[0];
                  return switch (method.getName()) {
                    case "hasChunkAt" -> loaded.contains(new ChunkPos(position));
                    case "getBlockState" -> {
                      assertTrue(
                          loaded.contains(new ChunkPos(position)), "Read from an unloaded chunk");
                      yield blocks.getOrDefault(position, Blocks.AIR.defaultBlockState());
                    }
                    default ->
                        throw new AssertionError("Unexpected world read: " + method.getName());
                  };
                });
  }
}
