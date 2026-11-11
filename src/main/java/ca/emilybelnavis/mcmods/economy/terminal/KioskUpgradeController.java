package ca.emilybelnavis.mcmods.economy.terminal;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * One guarded additive world effect between durable preparation and fresh completion. Recovery
 * never installs cells.
 */
public final class KioskUpgradeController {
  private record Key(ServerLevel level, BlockPos anchor, UUID generation) {}

  private static final class Work {
    final Key key;
    final Consumer<String> reply;
    final ServerPlayer player;
    final AtomicBoolean live = new AtomicBoolean(true), operator = new AtomicBoolean();
    JournalService journal;
    Journal.Intent intent;
    UUID operation;
    long originalRevision;
    KioskUpgrades.Action action;
    boolean busy, prepared, effectAttempted, reportedPartial;
    long retry;

    Work(Key key, ServerPlayer player, Consumer<String> reply) {
      this.key = key;
      this.player = player;
      this.reply = reply;
    }
  }

  private static final Map<MinecraftServer, LinkedHashMap<UUID, Work>> QUEUES =
      new IdentityHashMap<>();

  private KioskUpgradeController() {}

  public static void loaded(ServerLevel level, BlockPos pos, TerminalBlockEntity entity) {
    var q = QUEUES.computeIfAbsent(level.getServer(), s -> new LinkedHashMap<>());
    if (q.size() < 256)
      q.putIfAbsent(
          entity.generation(),
          new Work(new Key(level, pos.immutable(), entity.generation()), null, message -> {}));
  }

  private static TerminalBlockEntity entity(Key k) {
    return k.level().hasChunkAt(k.anchor())
            && k.level().getBlockEntity(k.anchor()) instanceof TerminalBlockEntity e
            && e.generation().equals(k.generation())
            && e.validIdentity()
        ? e
        : null;
  }

  public static boolean exactGeometry(ServerLevel level, BlockPos anchor, boolean wide) {
    var state = level.getBlockState(anchor);
    if (!(state.getBlock() instanceof BranchKioskBlock block)
        || state.getValue(BranchKioskBlock.WIDE) != wide
        || state.getValue(BranchKioskBlock.CELL) != 0) return false;
    for (int i = 0; i < block.cellCount(state); i++) {
      var pos = block.cellPosition(anchor, state, i);
      if (!level.hasChunkAt(pos)
          || !level.getBlockState(pos).equals(state.setValue(BranchKioskBlock.CELL, i)))
        return false;
    }
    return block.hasSupport(level, anchor, state);
  }

  private static boolean untouchedNarrow(Key key) {
    if (!exactGeometry(key.level(), key.anchor(), false)) return false;
    var state = key.level().getBlockState(key.anchor());
    for (int i : new int[] {1, 3}) {
      var pos = TerminalGeometry.cell(key.anchor(), state.getValue(WideTerminalBlock.FACING), i);
      if (!key.level().hasChunkAt(pos)) return false;
      var part = key.level().getBlockState(pos);
      if (part.getBlock() instanceof BranchKioskBlock
          && part.getValue(BranchKioskBlock.WIDE)
          && TerminalGeometry.anchor(pos, part).equals(key.anchor())) return false;
    }
    return true;
  }

  public static boolean canUpgrade(ServerPlayer player, BlockPos anchor) {
    var level = player.serverLevel();
    var state = level.getBlockState(anchor);
    if (!exactGeometry(level, anchor, false)
        || !(level.getBlockEntity(anchor) instanceof TerminalBlockEntity entity)
        || entity.geometryPending()) return false;
    if (!player.hasPermissions(2) && !player.getUUID().equals(entity.owner())) return false;
    var block = (BranchKioskBlock) state.getBlock();
    var wide = BranchKioskBlock.wideState(state, 0);
    if (!block.hasSupport(level, anchor, wide)) return false;
    for (int i = 0; i < 4; i++) {
      var pos = block.cellPosition(anchor, wide, i);
      if (!level.hasChunkAt(pos)
          || !level.isInWorldBounds(pos)
          || !level.getWorldBorder().isWithinBounds(pos)
          || !level.mayInteract(player, pos)
          || !player.mayUseItemAt(pos, Direction.UP, ItemStack.EMPTY)) return false;
      if ((i == 1 || i == 3) && !level.getBlockState(pos).isAir()) return false;
      if (!level.isUnobstructed(
          wide.setValue(BranchKioskBlock.CELL, i), pos, CollisionContext.of(player))) return false;
    }
    return true;
  }

  public static void request(ServerPlayer player, BlockPos anchor, Consumer<String> reply) {
    var level = player.serverLevel();
    var services = Economy.serverServices(player.server);
    if (services == null
        || services.state() != ServerServices.State.READY
        || !canUpgrade(player, anchor)) {
      reply.accept(
          "Upgrade blocked: the narrow kiosk needs loaded clear space, sturdy support and owner/operator access.");
      return;
    }
    var entity = (TerminalBlockEntity) level.getBlockEntity(anchor);
    var key = new Key(level, anchor.immutable(), entity.generation());
    var queue = QUEUES.computeIfAbsent(player.server, s -> new LinkedHashMap<>());
    if (queue.containsKey(key.generation()) || queue.size() >= 256) {
      reply.accept("Kiosk reconciliation is busy. Try again shortly.");
      return;
    }
    var w = new Work(key, player, reply);
    queue.put(key.generation(), w);
    w.busy = true;
    var actor = new PlayerId(player.getUUID());
    services
        .ledger()
        .terminalSettings(actor, (who, reads) -> w.operator.get(), policy(key))
        .whenComplete(
            (found, error) -> {
              w.busy = false;
              if (error != null || found.isEmpty()) {
                finish(w, "Owner/operator settings are unavailable.");
                return;
              }
              var snapshot = found.get();
              if (entity(key) == null
                  || !eligibleInitiator(w)
                  || snapshot.revision() != entity.policyRevision()) {
                finish(w, "The kiosk changed. Reopen its settings.");
                return;
              }
              w.operation = UUID.randomUUID();
              w.originalRevision = snapshot.revision();
              w.action = KioskUpgrades.Action.BEGIN;
              prepare(services, w, actor, w.originalRevision);
              submit(w);
            });
    w.operator.set(player.hasPermissions(2));
  }

  private static boolean eligibleInitiator(Work w) {
    var p = w.player;
    return p != null
        && p.server.getPlayerList().getPlayer(p.getUUID()) == p
        && p.serverLevel() == w.key.level()
        && p.distanceToSqr(
                w.key.anchor().getX() + .5, w.key.anchor().getY() + .5, w.key.anchor().getZ() + .5)
            <= 36
        && canUpgrade(p, w.key.anchor());
  }

  private static TerminalPolicy.Anchor policy(Key k) {
    return new TerminalPolicy.Anchor(
        k.level().dimension().location().toString(),
        k.anchor().getX(),
        k.anchor().getY(),
        k.anchor().getZ());
  }

  private static void prepare(ServerServices services, Work w, ActorId actor, long revision) {
    var op =
        w.action == KioskUpgrades.Action.BEGIN
            ? w.operation
            : UUID.nameUUIDFromBytes(
                ("kiosk-upgrade|" + w.operation + "|" + w.action).getBytes(StandardCharsets.UTF_8));
    w.intent =
        new Journal.Intent(
            new OperationId(op),
            new RequestId(op),
            actor,
            w.action.kind(),
            KioskUpgrades.request(policy(w.key), w.key.generation(), revision, w.operation),
            null,
            null);
    w.journal =
        services
            .ledger()
            .kioskUpgrade(
                w.action,
                (who, kind) -> who.equals(actor),
                (a, i, current, reads) -> w.live.get() && i.equals(w.intent),
                (who, reads) -> w.operator.get());
  }

  public static void revalidate(MinecraftServer server) {
    var queue = QUEUES.get(server);
    if (queue == null) return;
    for (var w : queue.values()) {
      boolean live = entity(w.key) != null;
      if (w.action == KioskUpgrades.Action.BEGIN) {
        live =
            live
                && w.player != null
                && server.getPlayerList().getPlayer(w.player.getUUID()) == w.player
                && w.player.serverLevel() == w.key.level()
                && w.player.distanceToSqr(
                        w.key.anchor().getX() + .5,
                        w.key.anchor().getY() + .5,
                        w.key.anchor().getZ() + .5)
                    <= 36
                && canUpgrade(w.player, w.key.anchor());
        w.operator.set(w.player != null && w.player.hasPermissions(2));
      } else if (w.action == KioskUpgrades.Action.COMPLETE)
        live = live && exactGeometry(w.key.level(), w.key.anchor(), true);
      else if (w.action == KioskUpgrades.Action.ABORT) live = live && untouchedNarrow(w.key);
      if (!live) w.live.set(false);
    }
  }

  public static void tick(MinecraftServer server) {
    var services = Economy.serverServices(server);
    if (services == null || services.state() != ServerServices.State.READY) return;
    var queue = QUEUES.get(server);
    if (queue == null) return;
    int admitted = 0;
    for (var w : List.copyOf(queue.values())) {
      if (w.busy || w.retry > server.getTickCount() || admitted == 4) continue;
      if (entity(w.key) == null) {
        queue.remove(w.key.generation());
        continue;
      }
      admitted++;
      if (w.intent != null) {
        submit(w);
        continue;
      }
      w.busy = true;
      services
          .ledger()
          .kioskUpgrade(w.key.generation())
          .whenComplete(
              (found, error) -> {
                w.busy = false;
                if (error != null) {
                  w.retry = server.getTickCount() + 100;
                  return;
                }
                var entity = entity(w.key);
                if (entity == null) {
                  queue.remove(w.key.generation());
                  return;
                }
                if (found.isEmpty()) {
                  entity.setGeometryPending(false);
                  queue.remove(w.key.generation());
                  TerminalLifecycle.loaded(w.key.level(), w.key.anchor(), entity);
                  return;
                }
                var upgrade = found.get();
                w.operation = upgrade.operation();
                w.originalRevision = upgrade.expectedRevision();
                if (!upgrade.phase().equals("PREPARED")) {
                  boolean matches =
                      exactGeometry(
                          w.key.level(), w.key.anchor(), upgrade.phase().equals("COMPLETE"));
                  entity.setGeometryPending(!matches);
                  queue.remove(w.key.generation());
                  if (matches) TerminalLifecycle.loaded(w.key.level(), w.key.anchor(), entity);
                  return;
                }
                w.prepared = true;
                w.effectAttempted = true;
                entity.setGeometryPending(true);
                recover(services, w);
              });
    }
  }

  private static void recover(ServerServices services, Work w) {
    if (exactGeometry(w.key.level(), w.key.anchor(), true))
      w.action = KioskUpgrades.Action.COMPLETE;
    else if (untouchedNarrow(w.key)) w.action = KioskUpgrades.Action.ABORT;
    else {
      if (!w.reportedPartial) {
        w.reportedPartial = true;
        w.reply.accept(
            "Upgrade interrupted: restore the complete original or wide geometry before service can resume. No cells are rebuilt automatically.");
      }
      w.intent = null;
      w.retry = w.key.level().getServer().getTickCount() + 100;
      return;
    }
    w.live.set(true);
    prepare(services, w, new SystemActorId(w.key.generation()), w.originalRevision + 1);
    submit(w);
  }

  private static void submit(Work w) {
    w.busy = true;
    w.journal
        .lookup(w.intent)
        .whenComplete(
            (found, error) -> {
              if (error != null) {
                retry(w);
                return;
              }
              if (found.isPresent()) {
                resolved(w, found.get());
                return;
              }
              w.journal
                  .submit(w.intent)
                  .completion()
                  .whenComplete(
                      (delivery, failure) -> {
                        if (failure != null) retry(w);
                        else resolved(w, delivery.outcome());
                      });
            });
  }

  private static void resolved(Work w, Journal.Outcome outcome) {
    w.busy = false;
    if (outcome.status() != Journal.Status.COMMITTED) {
      if (outcome.status() == Journal.Status.DURABLY_REJECTED || !w.live.get())
        finish(w, "Upgrade unavailable: " + outcome.result().code());
      else retry(w);
      return;
    }
    var entity = entity(w.key);
    if (entity == null) {
      finish(w, "Kiosk removed; upgrade cannot restore it.");
      return;
    }
    var services = Economy.serverServices(w.key.level().getServer());
    if (services == null || services.state() != ServerServices.State.READY) {
      finish(w, "Upgrade saved for startup reconciliation.");
      return;
    }
    if (w.action == KioskUpgrades.Action.BEGIN) {
      w.prepared = true;
      entity.setGeometryPending(true);
      // Exactly one synchronous attempt in the live initiating session. Startup recovery never
      // comes here.
      if (!w.effectAttempted && w.live.get() && w.player != null) {
        w.effectAttempted = true;
        entity.setGeometryPending(false);
        boolean allowed = eligibleInitiator(w);
        entity.setGeometryPending(true);
        if (allowed) install(w);
      }
      w.intent = null;
      recover(services, w);
    } else {
      // A committed historical result cannot override a later removal/mismatch.
      boolean exact =
          exactGeometry(w.key.level(), w.key.anchor(), w.action == KioskUpgrades.Action.COMPLETE);
      entity.setGeometryPending(!exact);
      if (exact) {
        entity.setPolicyRevision(w.originalRevision + 2);
        TerminalLifecycle.loaded(w.key.level(), w.key.anchor(), entity);
      }
      finish(
          w,
          w.action == KioskUpgrades.Action.COMPLETE && exact
              ? "Kiosk upgraded. Bank, currency and identity preserved."
              : "Upgrade cancelled; the original kiosk is retained.");
    }
  }

  private static void install(Work w) {
    var level = w.key.level();
    var anchor = w.key.anchor();
    var original = level.getBlockState(anchor);
    var wide = BranchKioskBlock.wideState(original, 0);
    var block = (BranchKioskBlock) wide.getBlock();
    var positions = TerminalGeometry.cells(anchor, wide);
    var before = positions.stream().map(level::getBlockState).toList();
    var written = new ArrayList<Integer>();
    WideTerminalBlock.beginPlacement(level, anchor);
    try {
      for (int i : new int[] {1, 3, 2, 0}) {
        var expected = wide.setValue(BranchKioskBlock.CELL, i);
        if (!level.getBlockState(positions.get(i)).equals(before.get(i))
            || !level.setBlock(
                positions.get(i), expected, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) {
          for (int j = written.size() - 1; j >= 0; j--) {
            int cell = written.get(j);
            if (level
                .getBlockState(positions.get(cell))
                .equals(wide.setValue(BranchKioskBlock.CELL, cell)))
              level.setBlock(
                  positions.get(cell),
                  before.get(cell),
                  Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
          }
          return;
        }
        written.add(i);
      }
    } finally {
      WideTerminalBlock.endPlacement(level, anchor);
    }
  }

  private static void retry(Work w) {
    w.busy = false;
    w.retry = w.key.level().getServer().getTickCount() + 100;
  }

  private static void finish(Work w, String message) {
    var q = QUEUES.get(w.key.level().getServer());
    if (q != null) q.remove(w.key.generation());
    w.live.set(false);
    w.reply.accept(message);
  }

  public static void stopped(MinecraftServer server) {
    var queue = QUEUES.remove(server);
    if (queue != null) queue.values().forEach(w -> w.live.set(false));
  }
}
