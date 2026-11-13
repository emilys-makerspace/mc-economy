package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy;
import ca.emilybelnavis.mcmods.economy.terminal.*;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/** Real BlockItem/player-break/loot paths, all variants, cells and orientations. */
public final class TerminalGeometryProbe {
  private static final BlockPos ANCHOR = new BlockPos(96, 110, 96);
  private static final List<Direction> FACINGS =
      List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
  private static final List<Runnable> CASES = new ArrayList<>();
  private static ServerLevel level;
  private static ServerPlayer player;
  private static int completed;
  private static int stage;
  private static UUID generation;
  private static boolean awaiting;
  private static boolean policyChecked;
  private static long deadline;

  private TerminalGeometryProbe() {}

  public static boolean tick(MinecraftServer server) throws Exception {
    if (Boolean.getBoolean("economy.atmPlacementProbe")) return tickAtmPlacement(server);
    if (stage == 0) {
      level = server.overworld();
      TerminalDataProbe.run(level);
      player = FakePlayerFactory.getMinecraft(level);
      player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
      level.getChunkAt(ANCHOR);
      level.getChunkAt(ANCHOR.offset(-4, 0, -4));
      for (var entry : TerminalCatalog.all()) {
        if (entry.kind() == TerminalPolicy.Kind.REGISTER) {
          for (var facing : FACINGS) CASES.add(() -> register(entry, facing));
        } else
          for (var facing : FACINGS)
            for (int cell = 0; cell < 4; cell++) {
              int selected = cell;
              CASES.add(() -> wideBreak(entry, facing, selected, false));
            }
      }
      for (var facing : FACINGS) {
        CASES.add(() -> forgedItem(facing));
        CASES.add(() -> missingSupport(facing));
        for (int cell = 0; cell < 4; cell++) {
          int selected = cell;
          CASES.add(() -> wideBreak(TerminalCatalog.get("atm"), facing, selected, true));
          CASES.add(() -> blocked(facing, selected));
          CASES.add(() -> replacement(facing, selected));
        }
        for (int floor = 0; floor < 2; floor++) {
          int selected = floor;
          CASES.add(() -> support(facing, selected));
        }
      }
      stage = 1;
    }
    if (stage == 1 && completed < CASES.size()) {
      if (!TerminalLifecycle.canPlace(level)) return false;
      CASES.get(completed++).run();
      return false;
    }
    if (stage == 1) {
      clean();
      place(TerminalCatalog.get("register"), Direction.NORTH);
      var entity = entity();
      generation = entity.generation();
      var tag = entity.saveWithFullMetadata(server.registryAccess());
      var restored = new TerminalBlockEntity(ANCHOR, entity.getBlockState());
      restored.loadWithComponents(tag, server.registryAccess());
      require(
          restored.generation().equals(generation) && restored.owner().equals(player.getUUID()),
          "saved identity roundtrip");
      level.save(null, true, false);
      stage = 2;
      deadline = server.getTickCount() + 600;
    }
    if (server.getTickCount() > deadline)
      throw new IllegalStateException("Terminal policy fixture timed out stage " + stage);
    if (stage == 2 && entity().policyReady() && !awaiting) {
      awaiting = true;
      var services = Economy.serverServices(server);
      services
          .ledger()
          .terminalSettings(new PlayerId(player.getUUID()), (who, reads) -> true, policyAnchor())
          .whenComplete(
              (found, error) -> {
                require(error == null && found.isPresent(), "placement journal snapshot");
                var snapshot = found.orElseThrow();
                require(
                    snapshot.generation().equals(generation) && snapshot.active(),
                    "physical generation journal binding");
                require(
                    snapshot.kind() == TerminalPolicy.Kind.REGISTER
                        && snapshot.settings().equals(TerminalPolicy.Settings.disabled()),
                    "register placement keeps cash services disabled");
                var old = generation;
                player.gameMode.destroyBlock(ANCHOR);
                place(TerminalCatalog.get("register"), Direction.NORTH);
                generation = entity().generation();
                require(
                    !generation.equals(old) && !TerminalLifecycle.matches(level, ANCHOR, old),
                    "replacement rejects stale generation");
                stage = 3;
                awaiting = false;
              });
    }
    if (stage == 3 && entity().policyReady() && !awaiting) {
      awaiting = true;
      Economy.serverServices(server)
          .ledger()
          .terminalSettings(new PlayerId(player.getUUID()), (who, reads) -> true, policyAnchor())
          .whenComplete(
              (found, error) -> {
                require(
                    error == null
                        && found.isPresent()
                        && found.orElseThrow().generation().equals(generation),
                    "replacement journal binding");
                player.gameMode.destroyBlock(ANCHOR);
                stage = 4;
                awaiting = false;
              });
    }
    if (stage == 4 && !awaiting) {
      awaiting = true;
      Economy.serverServices(server)
          .ledger()
          .terminalSettings(new PlayerId(player.getUUID()), (who, reads) -> true, policyAnchor())
          .whenComplete(
              (found, error) -> {
                require(error == null && found.isPresent(), "removal snapshot");
                policyChecked = !found.orElseThrow().active();
                awaiting = false;
              });
      if (policyChecked && TerminalRemovals.get(server).page(16).isEmpty()) {
        var removals = TerminalRemovals.get(server);
        for (int index = 0; index < 300; index++)
          removals.add(
              new TerminalRemovals.Removal(
                  "minecraft:overworld",
                  ANCHOR.offset(index, 0, 8),
                  UUID.randomUUID(),
                  player.getUUID(),
                  TerminalPolicy.Kind.ATM));
        level.save(null, true, false);
        var path =
            server.getWorldPath(LevelResource.ROOT).resolve("data/economy_terminal_removals.dat");
        var saved =
            net.minecraft.nbt.NbtIo.readCompressed(
                path, net.minecraft.nbt.NbtAccounter.create(1024 * 1024));
        var reloaded = TerminalRemovals.load(saved.getCompound("data"), server.registryAccess());
        int count = 0;
        while (!reloaded.page(16).isEmpty())
          for (var removal : reloaded.page(16)) {
            reloaded.complete(removal.generation());
            count++;
          }
        require(count == 300, "actual saved removal outbox exceeds live queue without loss");
        stage = 5;
        deadline = server.getTickCount() + 1200;
      }
    }
    if (stage == 5 && TerminalRemovals.get(server).page(16).isEmpty()) {
      Files.writeString(
          server.getWorldPath(LevelResource.ROOT).resolve("terminal-probe-passed.txt"),
          "PASS "
              + completed
              + " placement/teardown cases; policy placement replacement removal; saved identity; 300 saved removal outbox entries drained\n");
      System.out.println(
          "TERMINAL_GEOMETRY_PASS " + completed + " cases; saved 300-entry removal outbox drained");
      return true;
    }
    return false;
  }

  /** Focused regression: real item placement, delayed integrity and genuine support loss. */
  private static boolean tickAtmPlacement(MinecraftServer server) throws Exception {
    if (stage == 0) {
      level = server.overworld();
      player = FakePlayerFactory.getMinecraft(level);
      level.getChunkAt(ANCHOR);
      level.getChunkAt(ANCHOR.offset(-4, 0, -4));
      for (int x = 5; x <= 6; x++) for (int z = 5; z <= 6; z++) level.setChunkForced(x, z, true);
      for (var entry : TerminalCatalog.all())
        if (entry.kind() == TerminalPolicy.Kind.ATM)
          for (var facing : FACINGS)
            CASES.add(
                () -> {
                  clean();
                  place(entry, facing);
                  require(drops().isEmpty(), "placement must not drop the ATM");
                });
      stage = 1;
    }
    if (stage == 1 && completed < CASES.size()) {
      if (!TerminalLifecycle.canPlace(level)) return false;
      CASES.get(completed++).run();
      return false;
    }
    if (stage == 1) {
      clean();
      place(TerminalCatalog.get("atm"), Direction.NORTH);
      generation = entity().generation();
      deadline = server.getTickCount() + 40;
      stage = 2;
    }
    if (stage == 2) {
      require(
          TerminalGeometry.matches(level, ANCHOR, generation),
          "ATM survives placement and scheduled integrity ticks");
      require(drops().isEmpty(), "standing ATM has no item drop");
      if (server.getTickCount() > deadline + 600)
        throw new IllegalStateException("ATM policy admission timed out");
      if (server.getTickCount() < deadline || !entity().policyReady()) return false;
      level.destroyBlock(ANCHOR.below(), false);
      for (var position : TerminalGeometry.cells(ANCHOR, Direction.NORTH))
        require(level.isEmptyBlock(position), "support loss removes every ATM cell");
      require(drops().size() == 1, "genuine support loss drops exactly one ATM");
      Files.writeString(
          server.getWorldPath(LevelResource.ROOT).resolve("terminal-probe-passed.txt"),
          "PASS "
              + completed
              + " ATM variant/facing placements; 40-tick persistence; support-loss single drop\n");
      System.out.println(
          "ATM_PLACEMENT_PASS " + completed + " placements; delayed integrity; support loss");
      return true;
    }
    return false;
  }

  private static TerminalPolicy.Anchor policyAnchor() {
    return new TerminalPolicy.Anchor(
        level.dimension().location().toString(), ANCHOR.getX(), ANCHOR.getY(), ANCHOR.getZ());
  }

  private static TerminalBlockEntity entity() {
    return (TerminalBlockEntity) level.getBlockEntity(ANCHOR);
  }

  private static void clean() {
    player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
    for (var position : BlockPos.betweenClosed(ANCHOR.offset(-3, 0, -3), ANCHOR.offset(3, 3, 3)))
      level.destroyBlock(position, false);
    for (var item : drops()) item.discard();
    for (var position : BlockPos.betweenClosed(ANCHOR.offset(-3, -1, -3), ANCHOR.offset(3, -1, 3)))
      level.setBlockAndUpdate(position, Blocks.STONE.defaultBlockState());
    player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
    player.setPos(ANCHOR.getX() + 5, ANCHOR.getY(), ANCHOR.getZ() + 5);
  }

  private static List<ItemEntity> drops() {
    return level.getEntitiesOfClass(ItemEntity.class, new AABB(ANCHOR).inflate(5));
  }

  private static boolean attempt(TerminalCatalog.Entry entry, Direction facing, ItemStack stack) {
    player.setYRot(facing.getOpposite().toYRot());
    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
    var hit =
        new BlockHitResult(
            Vec3.atCenterOf(ANCHOR.below()).add(0, .5, 0), Direction.UP, ANCHOR.below(), false);
    if (Boolean.getBoolean("economy.atmPlacementProbe"))
      return player
          .gameMode
          .useItemOn(player, level, stack, InteractionHand.MAIN_HAND, hit)
          .consumesAction();
    return ((BlockItem) entry.item().get())
        .place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit))
        .consumesAction();
  }

  private static void place(TerminalCatalog.Entry entry, Direction facing) {
    var stack = new ItemStack(entry.item().get(), 2);
    require(attempt(entry, facing, stack), "placement " + entry.id() + " " + facing);
    require(stack.getCount() == 1, "one placement item consumed");
    require(entity() != null && entity().owner().equals(player.getUUID()), "anchor owner");
    require(
        TerminalGeometry.matches(level, ANCHOR, entity().generation()),
        "complete physical identity");
  }

  private static void wideBreak(
      TerminalCatalog.Entry entry, Direction facing, int cell, boolean creative) {
    clean();
    place(entry, facing);
    var positions = TerminalGeometry.cells(ANCHOR, facing);
    for (int index = 0; index < 4; index++) {
      var state = level.getBlockState(positions.get(index));
      require(
          state.getValue(WideTerminalBlock.CELL) == index
              && state.getValue(WideTerminalBlock.FACING) == facing,
          "cell orientation");
      require(
          TerminalGeometry.resolveAnchor(level, positions.get(index)).equals(ANCHOR),
          "anchor resolution");
    }
    if (creative) player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
    require(player.gameMode.destroyBlock(positions.get(cell)), "real player break");
    for (var position : positions) require(level.isEmptyBlock(position), "all four cells removed");
    require(level.getBlockEntity(ANCHOR) == null, "anchor entity cleaned");
    require(
        drops().stream().mapToInt(item -> item.getItem().getCount()).sum() == (creative ? 0 : 1),
        "exact single teardown drop");
    if (!creative)
      require(drops().getFirst().getItem().is(entry.item().get()), "same variant drop");
  }

  private static void register(TerminalCatalog.Entry entry, Direction facing) {
    clean();
    level.setBlockAndUpdate(ANCHOR.below(), Blocks.CHEST.defaultBlockState());
    place(entry, facing);
    require(
        level.getBlockState(ANCHOR).getValue(RegisterBlock.FACING) == facing,
        "register orientation");
    require(player.gameMode.destroyBlock(ANCHOR), "register real break");
    require(
        drops().stream()
                .filter(item -> item.getItem().is(entry.item().get()))
                .mapToInt(item -> item.getItem().getCount())
                .sum()
            == 1,
        "register loot");
    require(
        level.getBlockState(ANCHOR.below()).is(Blocks.CHEST), "register preserves stock container");
  }

  private static void blocked(Direction facing, int cell) {
    clean();
    var obstacle = TerminalGeometry.cell(ANCHOR, facing, cell);
    level.setBlockAndUpdate(obstacle, Blocks.STONE.defaultBlockState());
    var stack = new ItemStack(TerminalCatalog.get("atm").item().get());
    require(!attempt(TerminalCatalog.get("atm"), facing, stack), "occupied footprint rejects");
    require(
        stack.getCount() == 1 && level.getBlockState(obstacle).is(Blocks.STONE),
        "failed placement preserves item and obstruction");
    for (var position : TerminalGeometry.cells(ANCHOR, facing))
      if (!position.equals(obstacle)) require(level.isEmptyBlock(position), "no partial footprint");
  }

  private static void replacement(Direction facing, int cell) {
    clean();
    place(TerminalCatalog.get("atm"), facing);
    var target = TerminalGeometry.cell(ANCHOR, facing, cell);
    level.setBlockAndUpdate(target, Blocks.GOLD_BLOCK.defaultBlockState());
    require(level.getBlockState(target).is(Blocks.GOLD_BLOCK), "external replacement preserved");
    for (var position : TerminalGeometry.cells(ANCHOR, facing))
      if (!position.equals(target))
        require(level.isEmptyBlock(position), "replacement cleans peers");
    require(drops().size() == 1, "replacement single item");
  }

  private static void support(Direction facing, int floor) {
    clean();
    place(TerminalCatalog.get("atm"), facing);
    level.removeBlock(TerminalGeometry.cell(ANCHOR, facing, floor).below(), false);
    for (var position : TerminalGeometry.cells(ANCHOR, facing))
      require(level.isEmptyBlock(position), "support loss cleans all");
    require(drops().size() == 1, "support loss one item");
  }

  private static void forgedItem(Direction facing) {
    clean();
    var entry = TerminalCatalog.get("atm");
    var stack = new ItemStack(entry.item().get());
    var forged = new net.minecraft.nbt.CompoundTag();
    var owner = UUID.randomUUID();
    var generation = UUID.randomUUID();
    forged.putUUID("owner", owner);
    forged.putUUID("generation", generation);
    stack.set(
        net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA,
        net.minecraft.world.item.component.CustomData.of(forged));
    stack.set(
        net.minecraft.core.component.DataComponents.BLOCK_STATE,
        new net.minecraft.world.item.component.BlockItemStateProperties(
            Map.of("cell", "3", "facing", "south")));
    require(attempt(entry, facing, stack), "sanitized item placement");
    require(
        entity().owner().equals(player.getUUID()) && !entity().generation().equals(generation),
        "item cannot inject anchor identity");
    require(
        level.getBlockState(ANCHOR).getValue(WideTerminalBlock.CELL) == 0
            && level.getBlockState(ANCHOR).getValue(WideTerminalBlock.FACING) == facing,
        "item cannot inject satellite state");
  }

  private static void missingSupport(Direction facing) {
    clean();
    var floor = TerminalGeometry.cell(ANCHOR, facing, 1).below();
    level.removeBlock(floor, false);
    var stack = new ItemStack(TerminalCatalog.get("atm").item().get());
    require(
        !attempt(TerminalCatalog.get("atm"), facing, stack) && stack.getCount() == 1,
        "both supports required before placement");
    for (var position : TerminalGeometry.cells(ANCHOR, facing))
      require(level.isEmptyBlock(position), "unsupported placement leaves no partial machine");
  }

  private static void require(boolean condition, String label) {
    if (!condition) throw new IllegalStateException("Terminal fixture: " + label);
  }
}
