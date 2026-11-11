package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.terminal.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

/**
 * Disposable connected-host witness for only A7-03's changed physical upgrade/recovery behavior.
 */
public final class KioskUpgradeProbe {
  private static final BlockPos ANCHOR = new BlockPos(96, 110, 96);
  private static final List<Direction> FACINGS =
      List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
  private static final InstitutionCatalog.InstitutionId BANK =
      InstitutionCatalog.banks().getFirst().id();
  private static ServerPlayer player;
  private static int stage, facingIndex;
  private static long deadline, waitUntil, revision;
  private static UUID generation;
  private static boolean busy;
  private static volatile String failure, result;
  private static volatile Path root;
  private static volatile boolean captureReady, captured;
  private static String blockedReply, lastBindingProblem;
  private static Journal.Intent bindingIntent;
  private static JournalService bindingJournal;
  private static long bindingRetry;

  private KioskUpgradeProbe() {}

  public static String failure() {
    return failure;
  }

  public static String result() {
    return result;
  }

  public static Path root() {
    return root;
  }

  public static boolean captureReady() {
    return captureReady;
  }

  public static void captured() {
    captured = true;
  }

  public static void fail(Throwable problem) {
    var trace = new java.io.StringWriter();
    problem.printStackTrace(new java.io.PrintWriter(trace));
    failure = trace.toString();
    System.err.println("KIOSK_UPGRADE_PROBE_FAILED " + failure);
    try {
      if (root != null) Files.writeString(root.resolve("kiosk-upgrade-failed.txt"), failure);
    } catch (Exception ignored) {
    }
  }

  public static boolean tick(MinecraftServer server) throws Exception {
    if (failure != null || result != null) return true;
    if (server.getPlayerList().getPlayers().isEmpty()) return false;
    if (stage == 0) {
      root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
      require(
          root.getFileName().toString().startsWith("economy-a7-03-"),
          "explicitly disposable world name");
      player = server.getPlayerList().getPlayers().getFirst();
      player.setInvulnerable(true);
      player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
      for (int dx = -1; dx <= 1; dx++)
        for (int dz = -1; dz <= 1; dz++) {
          server.overworld().getChunk(ANCHOR.getX() / 16 + dx, ANCHOR.getZ() / 16 + dz);
          server.overworld().setChunkForced(ANCHOR.getX() / 16 + dx, ANCHOR.getZ() / 16 + dz, true);
        }
      next(server, 1);
    }
    require(
        server.getTickCount() < deadline,
        "deadline at stage " + stage + " facing " + facingIndex + " binding=" + lastBindingProblem);
    if (server.getTickCount() % 100 == 0)
      System.out.println(
          "KIOSK_UPGRADE_WAIT stage=" + stage + " facing=" + facingIndex + " busy=" + busy);
    if (busy) return false;
    switch (stage) {
      case 1 -> {
        clean();
        createLegacy();
        next(server, 2);
      }
      case 2 -> {
        if (!entity().policyReady()
            || server.getTickCount() < bindingRetry
            || !Economy.serverServices(server).metrics().economy().ready()) break;
        if (bindingIntent != null) {
          submitBinding(server);
          break;
        }
        busy = true;
        settings(
            snapshot -> {
              require(
                  snapshot.active() && snapshot.generation().equals(entity().generation()),
                  "legacy policy identity");
              generation = snapshot.generation();
              var operation = UUID.randomUUID();
              var actor = new PlayerId(player.getUUID());
              var request =
                  TerminalBanking.bindRequest(
                      anchor(), generation, snapshot.revision(), BANK, true);
              bindingIntent =
                  new Journal.Intent(
                      new OperationId(operation),
                      new RequestId(operation),
                      actor,
                      TerminalBanking.BIND,
                      request,
                      null,
                      null);
              var expectedBinding = bindingIntent;
              bindingJournal =
                  Economy.serverServices(server)
                      .ledger()
                      .terminalBanking(
                          (who, kind) -> who.equals(actor),
                          (action, candidate, current, reads) -> candidate.equals(expectedBinding),
                          (who, reads) -> false);
              busy = false;
              submitBinding(server);
            });
      }
      case 3 -> {
        busy = true;
        settings(
            snapshot -> {
              entity().applyPolicy(snapshot);
              entity().setPolicyReady(true);
              revision = snapshot.revision();
              require(
                  snapshot.binding().institution().equals(BANK)
                      && snapshot.binding().nativeCurrency() == EconomyCurrency.CAD,
                  "native binding before upgrade");
              var obstacle = extraCell(1);
              player
                  .serverLevel()
                  .setBlockAndUpdate(obstacle, Blocks.GOLD_BLOCK.defaultBlockState());
              var saved = entity().saveWithFullMetadata(server.registryAccess());
              var restored =
                  new TerminalBlockEntity(ANCHOR, player.serverLevel().getBlockState(ANCHOR));
              restored.loadWithComponents(saved, server.registryAccess());
              player.serverLevel().removeBlockEntity(ANCHOR);
              player.serverLevel().setBlockEntity(restored);
              restored.onLoad();
              require(
                  restored.generation().equals(generation)
                      && restored.owner().equals(player.getUUID()),
                  "legacy saved NBT identity reload");
              require(
                  player.serverLevel().getBlockState(obstacle).is(Blocks.GOLD_BLOCK),
                  "legacy reload preserves adjacent construction");
              busy = false;
              next(server, 4);
              waitUntil = server.getTickCount() + 10;
            });
      }
      case 4 -> {
        if (!entity().policyReady() || server.getTickCount() < waitUntil) break;
        require(
            KioskUpgradeController.exactGeometry(player.serverLevel(), ANCHOR, false),
            "reloaded legacy stays narrow");
        require(
            !KioskUpgradeController.canUpgrade(player, ANCHOR), "blocked extra space preflight");
        busy = true;
        KioskUpgradeController.request(
            player,
            ANCHOR,
            message ->
                safe(
                    () -> {
                      blockedReply = message;
                      require(
                          !message.contains("upgraded"), "blocked request has no success reply");
                      require(
                          entity().policyReady() && !entity().geometryPending(),
                          "blocked request keeps old service ready");
                      require(
                          KioskUpgradeController.exactGeometry(player.serverLevel(), ANCHOR, false),
                          "blocked request keeps narrow geometry");
                      busy = false;
                      next(server, 5);
                    }));
      }
      case 5 -> {
        busy = true;
        settings(
            snapshot -> {
              require(
                  snapshot.revision() == revision && snapshot.generation().equals(generation),
                  "blocked upgrade preserves revision and generation");
              require(
                  snapshot.binding().institution().equals(BANK)
                      && snapshot.binding().nativeCurrency() == EconomyCurrency.CAD,
                  "blocked upgrade preserves binding");
              require(
                  player.serverLevel().getBlockState(extraCell(1)).is(Blocks.GOLD_BLOCK),
                  "blocked upgrade preserves obstacle");
              player.serverLevel().setBlockAndUpdate(extraCell(1), Blocks.AIR.defaultBlockState());
              require(
                  KioskUpgradeController.canUpgrade(player, ANCHOR),
                  "clear supported upgrade preflight");
              KioskUpgradeController.request(
                  player,
                  ANCHOR,
                  message ->
                      safe(
                          () -> {
                            require(
                                message.contains("upgraded"),
                                "successful upgrade reply: "
                                    + message
                                    + "; previous blocked="
                                    + blockedReply);
                            busy = false;
                            next(server, 6);
                            waitUntil = server.getTickCount() + 5;
                          }));
            });
      }
      case 6 -> {
        if (!entity().policyReady()
            || entity().geometryPending()
            || server.getTickCount() < waitUntil) break;
        busy = true;
        settings(
            snapshot -> {
              require(
                  KioskUpgradeController.exactGeometry(player.serverLevel(), ANCHOR, true),
                  "full wide geometry " + facing());
              require(
                  TerminalGeometry.matches(player.serverLevel(), ANCHOR, generation),
                  "complete supported loaded wide identity");
              require(
                  snapshot.generation().equals(generation) && snapshot.revision() > revision,
                  "success retains generation and advances revision");
              require(
                  snapshot.binding().institution().equals(BANK)
                      && snapshot.binding().nativeCurrency() == EconomyCurrency.CAD,
                  "success retains bank/native currency");
              var positions =
                  TerminalGeometry.cells(ANCHOR, player.serverLevel().getBlockState(ANCHOR));
              for (int i = 0; i < 4; i++) {
                var state = player.serverLevel().getBlockState(positions.get(i));
                require(
                    state.getValue(BranchKioskBlock.CELL) == i
                        && state.getValue(WideTerminalBlock.FACING) == facing(),
                    "wide orientation " + facing() + " cell " + i);
                require(
                    TerminalGeometry.resolveAnchor(player.serverLevel(), positions.get(i))
                        .equals(ANCHOR),
                    "wide anchor resolution");
              }
              busy = false;
              if (facingIndex == 0) {
                captureReady = true;
                next(server, 7);
                waitUntil = server.getTickCount() + 40;
              } else next(server, 8);
            });
      }
      case 7 -> {
        if (!captured && server.getTickCount() < waitUntil + 100) break;
        next(server, 8);
      }
      case 8 -> {
        var positions = TerminalGeometry.cells(ANCHOR, player.serverLevel().getBlockState(ANCHOR));
        require(
            player.gameMode.destroyBlock(positions.get(facingIndex)),
            "real survival break facing " + facing());
        require(
            positions.stream().allMatch(player.serverLevel()::isEmptyBlock),
            "all wide cells removed");
        require(player.serverLevel().getBlockEntity(ANCHOR) == null, "wide anchor removed");
        require(
            drops().stream().mapToInt(e -> e.getItem().getCount()).sum() == 1,
            "exactly one survival drop");
        if (++facingIndex < FACINGS.size()) next(server, 1);
        else {
          facingIndex = 0;
          next(server, 20);
        }
      }
      case 20 -> {
        clean();
        createLegacy();
        next(server, 21);
      }
      case 21 -> {
        if (!entity().policyReady()) break;
        busy = true;
        settings(
            snapshot -> {
              generation = snapshot.generation();
              revision = snapshot.revision();
              var operation = UUID.randomUUID();
              var actor = new PlayerId(player.getUUID());
              var request = KioskUpgrades.request(anchor(), generation, revision, operation);
              var intent =
                  new Journal.Intent(
                      new OperationId(operation),
                      new RequestId(operation),
                      actor,
                      KioskUpgrades.Action.BEGIN.kind(),
                      request,
                      null,
                      null);
              Economy.serverServices(server)
                  .ledger()
                  .kioskUpgrade(
                      KioskUpgrades.Action.BEGIN,
                      (who, kind) -> who.equals(actor),
                      (action, candidate, current, reads) -> candidate.equals(intent),
                      (who, reads) -> false)
                  .submit(intent)
                  .completion()
                  .whenComplete(
                      (delivery, error) ->
                          safe(
                              () -> {
                                require(
                                    error == null
                                        && delivery.outcome().status() == Journal.Status.COMMITTED,
                                    "durable interrupted preparation");
                                var original = entity().getBlockState();
                                entity().setGeometryPending(true);
                                entity().setPolicyReady(false);
                                player
                                    .serverLevel()
                                    .setBlock(
                                        extraCell(1),
                                        BranchKioskBlock.wideState(original, 1),
                                        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                                player.serverLevel().save(null, true, false);
                                KioskUpgradeController.loaded(
                                    player.serverLevel(), ANCHOR, entity());
                                busy = false;
                                next(server, 22);
                                waitUntil = server.getTickCount() + 250;
                              }));
            });
      }
      case 22 -> {
        if (server.getTickCount() < waitUntil) break;
        require(
            entity().geometryPending() && !entity().policyReady(),
            "interrupted partial geometry stays unavailable");
        require(
            !TerminalGeometry.matches(player.serverLevel(), ANCHOR, generation),
            "partial geometry cannot authorize service");
        require(
            player.serverLevel().getBlockState(extraCell(1)).getValue(BranchKioskBlock.WIDE),
            "existing partial cell retained for inspection");
        require(
            player.serverLevel().isEmptyBlock(extraCell(3)),
            "recovery never fills absent satellite");
        require(drops().isEmpty(), "interrupted recovery creates no duplicate drops");
        busy = true;
        Economy.serverServices(server)
            .ledger()
            .kioskUpgrade(generation)
            .whenComplete(
                (found, error) ->
                    safe(
                        () -> {
                          require(
                              error == null
                                  && found.isPresent()
                                  && found.get().phase().equals("PREPARED"),
                              "partial prepared upgrade remains durably unresolved");
                          result =
                              "PASS four facings: legacy NBT reload preserves adjacent construction; blocked upgrade retains service/revision; explicit free upgrade preserves generation/native CAD bank and advances revision; all four cells resolve anchor; one survival drop per facing. Durable prepared partial geometry saved and observed across250 running ticks remains fail-closed without filling or drops. No process-restart claim.\n";
                          Files.writeString(root.resolve("kiosk-upgrade-passed.txt"), result);
                          busy = false;
                          System.out.println("KIOSK_UPGRADE_PROBE_PASS " + result);
                        }));
      }
      default -> throw new IllegalStateException("Unknown kiosk fixture stage " + stage);
    }
    return result != null || failure != null;
  }

  private static void submitBinding(MinecraftServer server) {
    busy = true;
    // A catch-up/queue admission failure consumes no new identity. Resolve and retry the saved
    // exact intent.
    bindingJournal
        .lookup(bindingIntent)
        .whenComplete(
            (found, lookupError) ->
                safe(
                    () -> {
                      if (lookupError != null)
                        throw new IllegalStateException(
                            "Native binding original lookup failed", lookupError);
                      if (found.isPresent()) {
                        bindingOutcome(server, found.get());
                        return;
                      }
                      bindingJournal
                          .submit(bindingIntent)
                          .completion()
                          .whenComplete(
                              (delivery, error) ->
                                  safe(
                                      () -> {
                                        if (error != null)
                                          throw new IllegalStateException(
                                              "Native binding callback failed", error);
                                        bindingOutcome(server, delivery.outcome());
                                      }));
                    }));
  }

  private static void bindingOutcome(MinecraftServer server, Journal.Outcome outcome) {
    lastBindingProblem =
        outcome.status() + "/" + outcome.result().code() + "/" + outcome.result().payload();
    System.out.println(
        "KIOSK_BINDING_OUTCOME "
            + lastBindingProblem
            + " original="
            + bindingIntent.operationId().value());
    if (outcome.status() == Journal.Status.COMMITTED) {
      busy = false;
      next(server, 3);
      return;
    }
    if (outcome.status() == Journal.Status.UNKNOWN
        || outcome.status() == Journal.Status.NOT_ACCEPTED
            && Set.of("ECONOMIC_CATCH_UP", "OVERLOADED", "ABSENT_RETRY_SAME_ID")
                .contains(outcome.result().code())) {
      busy = false;
      bindingRetry = server.getTickCount() + 10;
      return;
    }
    throw new IllegalStateException("Native binding failed: " + lastBindingProblem);
  }

  private static void createLegacy() {
    player.closeContainer();
    var level = player.serverLevel();
    var state =
        TerminalCatalog.get("branch_kiosk")
            .block()
            .get()
            .defaultBlockState()
            .setValue(WideTerminalBlock.FACING, facing())
            .setValue(BranchKioskBlock.WIDE, false);
    level.setBlock(ANCHOR, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    level.setBlock(
        ANCHOR.above(),
        state.setValue(BranchKioskBlock.CELL, 1),
        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    var legacyTag = net.minecraft.nbt.NbtUtils.writeBlockState(state);
    legacyTag.getCompound("Properties").remove("wide");
    var legacyDecoded =
        net.minecraft.nbt.NbtUtils.readBlockState(
            level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), legacyTag);
    require(
        !legacyDecoded.getValue(BranchKioskBlock.WIDE)
            && legacyDecoded.getValue(BranchKioskBlock.CELL) == 0,
        "old palette missing wide remains narrow");
    var upperTag =
        net.minecraft.nbt.NbtUtils.writeBlockState(state.setValue(BranchKioskBlock.CELL, 1));
    upperTag.getCompound("Properties").remove("wide");
    var upperDecoded =
        net.minecraft.nbt.NbtUtils.readBlockState(
            level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), upperTag);
    require(
        !upperDecoded.getValue(BranchKioskBlock.WIDE)
            && upperDecoded.getValue(BranchKioskBlock.CELL) == 1,
        "old upper palette keeps vertical cell1");
    entity().newPlacement(player.getUUID());
    TerminalLifecycle.placed(level, ANCHOR, entity());
    double x =
        ANCHOR.getX()
            + .5
            + facing().getCounterClockWise().getStepX() * .5
            + facing().getStepX() * 4;
    double z =
        ANCHOR.getZ()
            + .5
            + facing().getCounterClockWise().getStepZ() * .5
            + facing().getStepZ() * 4;
    player.connection.teleport(x, ANCHOR.getY(), z, facing().getOpposite().toYRot(), 0);
  }

  private static void clean() {
    bindingIntent = null;
    bindingJournal = null;
    bindingRetry = 0;
    lastBindingProblem = null;
    player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
    for (var pos : BlockPos.betweenClosed(ANCHOR.offset(-6, 0, -6), ANCHOR.offset(6, 3, 6)))
      player.serverLevel().destroyBlock(pos, false);
    drops().forEach(ItemEntity::discard);
    for (var pos : BlockPos.betweenClosed(ANCHOR.offset(-6, -1, -6), ANCHOR.offset(6, -1, 6)))
      player.serverLevel().setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
    player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
  }

  private static Direction facing() {
    return FACINGS.get(facingIndex);
  }

  private static BlockPos extraCell(int cell) {
    return TerminalGeometry.cell(ANCHOR, facing(), cell);
  }

  private static TerminalBlockEntity entity() {
    return (TerminalBlockEntity) player.serverLevel().getBlockEntity(ANCHOR);
  }

  private static TerminalPolicy.Anchor anchor() {
    return new TerminalPolicy.Anchor(
        player.serverLevel().dimension().location().toString(),
        ANCHOR.getX(),
        ANCHOR.getY(),
        ANCHOR.getZ());
  }

  private static List<ItemEntity> drops() {
    return player.serverLevel().getEntitiesOfClass(ItemEntity.class, new AABB(ANCHOR).inflate(5));
  }

  private static void settings(Consumer<TerminalPolicy.Snapshot> receive) {
    Economy.serverServices(player.server)
        .ledger()
        .terminalSettings(new PlayerId(player.getUUID()), (who, reads) -> false, anchor())
        .whenComplete(
            (found, error) ->
                safe(
                    () -> {
                      require(error == null && found.isPresent(), "current terminal snapshot");
                      receive.accept(found.orElseThrow());
                    }));
  }

  private static void next(MinecraftServer server, int value) {
    stage = value;
    deadline = server.getTickCount() + 1800;
  }

  @FunctionalInterface
  private interface Checked {
    void run() throws Exception;
  }

  private static void safe(Checked callback) {
    try {
      callback.run();
    } catch (Throwable problem) {
      busy = false;
      fail(problem);
    }
  }

  private static void require(boolean condition, String label) {
    if (!condition) throw new IllegalStateException("Kiosk fixture: " + label);
  }
}
