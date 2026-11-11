package ca.emilybelnavis.mcmods.economy.terminal;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.journal.*;
import com.mojang.logging.LogUtils;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Bounded server-thread physical identity admission; storage callbacks receive immutable
 * observations only.
 */
public final class TerminalLifecycle {
  private static final int LIMIT = 256;

  private record Key(String dimension, BlockPos anchor, UUID generation, boolean removal) {}

  private static final class Pending {
    final Key key;
    final UUID owner;
    final TerminalPolicy.Kind kind;
    Journal.Intent intent;
    long retry;
    boolean busy;

    Pending(Key key, UUID owner, TerminalPolicy.Kind kind) {
      this.key = key;
      this.owner = owner;
      this.kind = kind;
    }
  }

  private static final Map<MinecraftServer, LinkedHashMap<Key, Pending>> QUEUES =
      new IdentityHashMap<>();

  private TerminalLifecycle() {}

  public static boolean canPlace(ServerLevel level) {
    var services = Economy.serverServices(level.getServer());
    return services != null
        && services.state() == ServerServices.State.READY
        && QUEUES.getOrDefault(level.getServer(), new LinkedHashMap<>()).size() < LIMIT - 8;
  }

  public static void loaded(ServerLevel level, BlockPos anchor, TerminalBlockEntity entity) {
    placed(level, anchor, entity);
  }

  public static void placed(ServerLevel level, BlockPos anchor, TerminalBlockEntity entity) {
    if (entity.branchId() != null) {
      BranchBootstrap.loaded(level, anchor, entity);
      return;
    }
    enqueue(level, anchor, entity, false);
  }

  public static void removed(ServerLevel level, BlockPos anchor, TerminalBlockEntity entity) {
    ca.emilybelnavis.mcmods.economy.institution.KioskAdmission.removed(
        level.getServer(), level.dimension().location().toString(), anchor);
    entity.setPolicyReady(false);
    if (entity.branchId() != null) {
      BranchBootstrap.removed(level, anchor, entity);
      return;
    }
    if (entity.owner() != null) {
      var kind = kind(entity.getBlockState().getBlock());
      if (kind != null)
        TerminalRemovals.get(level.getServer())
            .add(
                new TerminalRemovals.Removal(
                    level.dimension().location().toString(),
                    anchor.immutable(),
                    entity.generation(),
                    entity.owner(),
                    kind));
    }
    enqueue(level, anchor, entity, true);
  }

  private static void enqueue(
      ServerLevel level, BlockPos anchor, TerminalBlockEntity entity, boolean removal) {
    if (entity.owner() == null) return;
    TerminalPolicy.Kind kind = kind(entity.getBlockState().getBlock());
    if (kind == null) return;
    var queue = QUEUES.computeIfAbsent(level.getServer(), ignored -> new LinkedHashMap<>());
    var key =
        new Key(
            level.dimension().location().toString(),
            anchor.immutable(),
            entity.generation(),
            removal);
    if (queue.containsKey(key)) return;
    if (queue.size() >= LIMIT) {
      // Removals remain in the saved outbox; loaded placements remain fail-closed until
      // interaction.
      if (!removal)
        LogUtils.getLogger().warn("Physical terminal admission queue full at {}", anchor);
      return;
    }
    queue.put(key, new Pending(key, entity.owner(), kind));
  }

  public static TerminalPolicy.Kind kind(net.minecraft.world.level.block.Block block) {
    if (block instanceof WideTerminalBlock wide) return wide.kind();
    if (block instanceof RegisterBlock register) return register.kind();
    return null;
  }

  public static boolean matches(ServerLevel level, BlockPos anchor, UUID generation) {
    return level.hasChunkAt(anchor)
        && level.getBlockEntity(anchor) instanceof TerminalBlockEntity entity
        && entity.generation().equals(generation)
        && entity.policyReady()
        && TerminalGeometry.matches(level, anchor, generation);
  }

  private static TerminalBlockEntity live(MinecraftServer server, Pending pending) {
    var level =
        server.getLevel(
            net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(pending.key.dimension())));
    if (level == null || !level.hasChunkAt(pending.key.anchor())) return null;
    return level.getBlockEntity(pending.key.anchor()) instanceof TerminalBlockEntity entity
            && entity.generation().equals(pending.key.generation())
            && entity.validIdentity()
            && TerminalGeometry.matches(level, pending.key.anchor(), pending.key.generation())
        ? entity
        : null;
  }

  public static void tick(MinecraftServer server) {
    var services = Economy.serverServices(server);
    if (services == null || services.state() != ServerServices.State.READY) return;
    var queue = QUEUES.computeIfAbsent(server, ignored -> new LinkedHashMap<>());
    for (var removal : TerminalRemovals.get(server).page(16)) {
      var key = new Key(removal.dimension(), removal.anchor(), removal.generation(), true);
      if (queue.containsKey(key)) continue;
      if (queue.size() >= LIMIT) break;
      queue.put(key, new Pending(key, removal.owner(), removal.kind()));
    }
    int admitted = 0;
    for (var pending : List.copyOf(queue.values())) {
      if (admitted >= 4) break;
      if (pending.busy || pending.retry > server.getTickCount()) continue;
      if (!pending.key.removal() && live(server, pending) == null) {
        queue.remove(pending.key);
        continue;
      }
      pending.busy = true;
      admitted++;
      if (pending.intent != null) {
        submit(server, services, queue, pending);
        continue;
      }
      var anchor = anchor(pending.key);
      var actor = new PlayerId(pending.owner);
      services
          .ledger()
          .terminalSettings(actor, (who, reads) -> true, anchor)
          .whenComplete(
              (found, error) -> {
                if (error != null) {
                  retry(server, pending);
                  return;
                }
                var current = found.orElse(null);
                if (pending.key.removal()) {
                  if (current == null
                      || !current.active()
                      || !current.generation().equals(pending.key.generation())) {
                    TerminalRemovals.get(server).complete(pending.key.generation());
                    queue.remove(pending.key);
                    return;
                  }
                } else {
                  var entity = live(server, pending);
                  if (entity == null) {
                    queue.remove(pending.key);
                    return;
                  }
                  if (current != null
                      && current.active()
                      && current.generation().equals(pending.key.generation())) {
                    entity.applyPolicy(current);
                    entity.setPolicyReady(true);
                    queue.remove(pending.key);
                    return;
                  }
                }
                var action =
                    pending.key.removal()
                        ? TerminalPolicy.Action.REMOVE
                        : TerminalPolicy.Action.PLACE;
                var request =
                    pending.key.removal()
                        ? TerminalPolicy.removeRequest(
                            anchor, current.generation(), current.revision())
                        : TerminalPolicy.placeRequest(
                            anchor,
                            pending.key.generation(),
                            actor,
                            pending.kind,
                            current == null ? null : current.generation(),
                            current == null ? 0 : current.revision());
                var operation =
                    UUID.nameUUIDFromBytes(
                        ("physical-terminal|" + pending.key + "|" + request.fields())
                            .getBytes(StandardCharsets.UTF_8));
                pending.intent =
                    new Journal.Intent(
                        new OperationId(operation),
                        new RequestId(operation),
                        actor,
                        action.kind(),
                        request,
                        null,
                        null);
                submit(server, services, queue, pending);
              });
    }
  }

  private static void submit(
      MinecraftServer server, ServerServices services, Map<Key, Pending> queue, Pending pending) {
    var intent = pending.intent;
    var action = pending.key.removal() ? TerminalPolicy.Action.REMOVE : TerminalPolicy.Action.PLACE;
    // This is a trusted physical-world grant, detached before crossing to the worker. It is not a
    // player operator grant.
    var journal =
        services
            .ledger()
            .terminalPolicy(
                action,
                (actor, kind) -> actor.equals(intent.actor()),
                (a, candidate, current, reads) -> candidate.equals(intent),
                (actor, reads) -> true,
                null);
    journal
        .submit(intent)
        .completion()
        .whenComplete(
            (delivery, error) -> {
              if (error != null) {
                retry(server, pending);
                return;
              }
              var outcome = delivery.outcome();
              if (outcome.status() == Journal.Status.COMMITTED) {
                var entity = live(server, pending);
                if (!pending.key.removal() && entity != null) {
                  entity.setPolicyRevision(
                      Long.parseLong(intent.request().fields().get("expected_revision")) + 1);
                  entity.setPolicyReady(true);
                }
                if (pending.key.removal())
                  TerminalRemovals.get(server).complete(pending.key.generation());
                queue.remove(pending.key);
              } else if (outcome.status() == Journal.Status.DURABLY_REJECTED) {
                if (outcome.result().code().equals("TERMINAL_STALE")) {
                  pending.intent = null;
                  retry(server, pending);
                } else {
                  LogUtils.getLogger()
                      .warn(
                          "Physical terminal admission rejected: {} at {}",
                          outcome.result().code(),
                          pending.key.anchor());
                  queue.remove(pending.key);
                }
              } else
                retry(server, pending); // Canonical intent and original ID survive unknown/rejected
              // admission.
            });
  }

  private static void retry(MinecraftServer server, Pending pending) {
    pending.busy = false;
    pending.retry = server.getTickCount() + 100L;
  }

  private static TerminalPolicy.Anchor anchor(Key key) {
    return new TerminalPolicy.Anchor(
        key.dimension(), key.anchor().getX(), key.anchor().getY(), key.anchor().getZ());
  }

  public static void stopped(MinecraftServer server) {
    QUEUES.remove(server);
  }
}
