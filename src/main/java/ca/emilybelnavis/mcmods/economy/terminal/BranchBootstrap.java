package ca.emilybelnavis.mcmods.economy.terminal;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.foundation.BranchService;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Loaded-only generated hardware reconciliation. Never builds blocks or accepts a player-created
 * bootstrap proof.
 */
public final class BranchBootstrap {
  private record Key(UUID branch, TerminalPolicy.Anchor anchor, UUID generation, boolean removal) {}

  private static final class Pending {
    final Key key;
    final AtomicBoolean live = new AtomicBoolean(true);
    BranchService.Pending prepared;
    BranchPlans.Anchor activationAnchor;
    int activationBrand;
    boolean busy;
    long retry;

    Pending(Key key) {
      this.key = key;
    }
  }

  private static final class Session {
    final Map<Key, Pending> queue = new LinkedHashMap<>();
    String cursor = "";
    boolean scanning;
    long tick, nextScan;
  }

  private static final Map<MinecraftServer, Session> SESSIONS = new IdentityHashMap<>();

  private BranchBootstrap() {}

  public static void loaded(ServerLevel level, BlockPos pos, TerminalBlockEntity entity) {
    if (entity.branchId() != null)
      enqueue(
          level.getServer(),
          new Key(entity.branchId(), anchor(level, pos), entity.generation(), false));
  }

  public static void removed(ServerLevel level, BlockPos pos, TerminalBlockEntity entity) {
    if (entity.branchId() == null) return;
    entity.setPolicyReady(false);
    TerminalRemovals.get(level.getServer())
        .addBranch(
            new TerminalRemovals.BranchRemoval(
                entity.branchId(), anchor(level, pos), entity.generation()));
    enqueue(
        level.getServer(),
        new Key(entity.branchId(), anchor(level, pos), entity.generation(), true));
  }

  private static TerminalPolicy.Anchor anchor(ServerLevel level, BlockPos pos) {
    return new TerminalPolicy.Anchor(
        level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ());
  }

  private static void enqueue(MinecraftServer server, Key key) {
    var session = SESSIONS.computeIfAbsent(server, s -> new Session());
    if (key.removal())
      for (var pending : session.queue.values())
        if (pending.key.generation().equals(key.generation()) && !pending.key.removal())
          pending.live.set(false);
    if (session.queue.size() < 256) session.queue.putIfAbsent(key, new Pending(key));
  }

  private static ServerLevel level(MinecraftServer server, TerminalPolicy.Anchor anchor) {
    return server.getLevel(
        net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            net.minecraft.resources.ResourceLocation.parse(anchor.dimension())));
  }

  private static TerminalBlockEntity live(MinecraftServer server, Key key) {
    var level = level(server, key.anchor());
    var pos = new BlockPos(key.anchor().x(), key.anchor().y(), key.anchor().z());
    return level != null
            && level.hasChunkAt(pos)
            && level.getBlockEntity(pos) instanceof TerminalBlockEntity entity
            && (entity.kind() == TerminalPolicy.Kind.KIOSK
                || entity.kind() == TerminalPolicy.Kind.ATM)
            && key.branch().equals(entity.branchId())
            && key.generation().equals(entity.generation())
            && entity.validIdentity()
            && TerminalGeometry.matches(level, pos, key.generation())
        ? entity
        : null;
  }

  private static boolean matchesActivation(Pending pending, TerminalBlockEntity entity) {
    if (entity == null) return false;
    var planned = pending.activationAnchor;
    if (planned == null) return true;
    var state = entity.getBlockState();
    if (entity.kind() != planned.serviceKind()) return false;
    if (state.getBlock() instanceof BranchKioskBlock
        && state.getValue(BranchKioskBlock.WIDE) != planned.geometry().equals("wide")) return false;
    if (pending.activationBrand == 0) return true;
    var level = entity.getLevel();
    return TerminalGeometry.cells(entity.getBlockPos(), state).stream()
        .allMatch(
            p ->
                level.getBlockState(p).getValue(WideTerminalBlock.BANK) == pending.activationBrand);
  }

  public static void revalidate(MinecraftServer server) {
    var session = SESSIONS.get(server);
    if (session != null)
      for (var p : session.queue.values())
        if (!p.key.removal() && !matchesActivation(p, live(server, p.key))) p.live.set(false);
  }

  public static void tick(MinecraftServer server) {
    var session = SESSIONS.computeIfAbsent(server, s -> new Session());
    session.tick++;
    revalidate(server);
    var services = Economy.serverServices(server);
    if (services == null || services.state() != ServerServices.State.READY) return;
    for (var removal : TerminalRemovals.get(server).branchPage(16))
      enqueue(server, new Key(removal.branch(), removal.anchor(), removal.generation(), true));
    if (!session.scanning && session.tick >= session.nextScan) {
      session.scanning = true;
      session.nextScan = session.tick + 100;
      services
          .branches()
          .page(session.cursor)
          .whenComplete(
              (rows, error) -> {
                session.scanning = false;
                if (error != null) return;
                session.cursor = rows.size() == 16 ? rows.getLast().get("branch_id") : "";
                for (var row : rows)
                  services
                      .branches()
                      .find(UUID.fromString(row.get("branch_id")))
                      .whenComplete(
                          (view, failure) -> {
                            if (failure != null || view.isEmpty()) return;
                            for (var a : view.get().anchors()) {
                              var anchor =
                                  new TerminalPolicy.Anchor(
                                      a.get("dimension"),
                                      Integer.parseInt(a.get("x")),
                                      Integer.parseInt(a.get("y")),
                                      Integer.parseInt(a.get("z")));
                              var level = level(server, anchor);
                              var pos = new BlockPos(anchor.x(), anchor.y(), anchor.z());
                              // Unloaded satellites and an in-progress geometry upgrade are not
                              // removal evidence.
                              // Do not persist an outbox entry until the whole footprint is
                              // observable.
                              if (level == null || !TerminalGeometry.fullyLoaded(level, pos))
                                continue;
                              if (level.getBlockEntity(pos) instanceof TerminalBlockEntity pending
                                  && pending.geometryPending()) continue;
                              var key =
                                  new Key(
                                      view.get().id(),
                                      anchor,
                                      UUID.fromString(a.get("generation")),
                                      false);
                              var entity = live(server, key);
                              if (entity != null) {
                                if (Set.of("CONFLICT", "REMOVED").contains(view.get().state()))
                                  entity.setPolicyReady(false);
                                enqueue(server, key);
                              } else if (a.get("state").equals("ACTIVE")) {
                                TerminalRemovals.get(server)
                                    .addBranch(
                                        new TerminalRemovals.BranchRemoval(
                                            key.branch(), anchor, key.generation()));
                                enqueue(
                                    server, new Key(key.branch(), anchor, key.generation(), true));
                              }
                            }
                          });
              });
    }
    int admitted = 0;
    for (var p : List.copyOf(session.queue.values())) {
      if (admitted == 4) break;
      if (p.busy || p.retry > session.tick) continue;
      if (!p.key.removal() && !p.live.get()) {
        session.queue.remove(p.key);
        continue;
      }
      p.busy = true;
      admitted++;
      if (p.prepared != null) {
        submit(server, session, p);
        continue;
      }
      services
          .branches()
          .find(p.key.branch())
          .whenComplete(
              (found, error) -> {
                if (error != null || found.isEmpty()) {
                  retry(session, p);
                  return;
                }
                var view = found.get();
                var row =
                    view.anchors().stream()
                        .filter(
                            a ->
                                a.get("generation").equals(p.key.generation().toString())
                                    && a.get("dimension").equals(p.key.anchor().dimension())
                                    && Integer.parseInt(a.get("x")) == p.key.anchor().x()
                                    && Integer.parseInt(a.get("y")) == p.key.anchor().y()
                                    && Integer.parseInt(a.get("z")) == p.key.anchor().z())
                        .findFirst()
                        .orElse(null);
                if (row == null) {
                  session.queue.remove(p.key);
                  return;
                }
                if (p.key.removal() && row.get("state").equals("REMOVED")) {
                  TerminalRemovals.get(server).completeBranch(p.key.generation());
                  session.queue.remove(p.key);
                  return;
                }
                services
                    .ledger()
                    .terminalSettings(
                        BranchPlans.owner(view.id()), (actor, reads) -> false, p.key.anchor())
                    .whenComplete(
                        (snapshot, failure) -> {
                          if (failure != null) {
                            retry(session, p);
                            return;
                          }
                          var current = snapshot.orElse(null);
                          if (!p.key.removal()) {
                            var entity = live(server, p.key);
                            if (entity == null || !p.live.get()) {
                              session.queue.remove(p.key);
                              return;
                            }
                            var planned =
                                view.choice().services().stream()
                                    .filter(a -> a.slot() == Integer.parseInt(row.get("slot")))
                                    .findFirst()
                                    .orElseThrow();
                            boolean ready =
                                !Set.of("CONFLICT", "REMOVED", "PLACING", "RESERVED")
                                        .contains(view.state())
                                    && row.get("state").equals("ACTIVE")
                                    && entity.kind() == planned.serviceKind()
                                    && current != null
                                    && current.kind() == planned.serviceKind()
                                    && current.active()
                                    && current.generation().equals(p.key.generation())
                                    && current.binding() != null
                                    && current.binding().bound()
                                    && view.choice().institution() != null
                                    && current
                                        .binding()
                                        .institution()
                                        .key()
                                        .equals(view.choice().institution());
                            if (ready) {
                              entity.applyPolicy(current);
                              entity.setPolicyReady(true);
                              session.queue.remove(p.key);
                              return;
                            }
                            entity.setPolicyReady(false);
                            if (!Set.of("PHYSICAL_COMPLETE", "ACTIVATION_PENDING")
                                    .contains(view.state())
                                || !row.get("state").equals("PENDING")) {
                              session.queue.remove(p.key);
                              return;
                            }
                            p.activationAnchor = planned;
                            if (view.choice().size() == BranchPlans.Size.STANDARD) {
                              var bank =
                                  InstitutionCatalog.require(
                                      new InstitutionCatalog.InstitutionId(
                                          view.choice().institution()));
                              p.activationBrand = InstitutionCatalog.banks().indexOf(bank) + 1;
                            }
                            if (!matchesActivation(p, entity)) {
                              session.queue.remove(p.key);
                              return;
                            }
                          }
                          long revision = current == null ? 0 : current.revision();
                          p.prepared =
                              services
                                  .branches()
                                  .hardware(
                                      view,
                                      Integer.parseInt(row.get("slot")),
                                      p.key.generation(),
                                      revision,
                                      p.key.removal(),
                                      p.live);
                          submit(server, session, p);
                        });
              });
    }
  }

  private static void submit(MinecraftServer server, Session session, Pending pending) {
    // Always look up the saved original intent before another submission; an unknown outcome never
    // gets a new ID.
    pending
        .prepared
        .lookup()
        .whenComplete(
            (found, error) -> {
              if (error != null) {
                retry(session, pending);
                return;
              }
              if (found.isPresent()) {
                complete(server, session, pending, found.get());
                return;
              }
              pending
                  .prepared
                  .submit()
                  .completion()
                  .whenComplete(
                      (delivery, failure) -> {
                        if (failure != null) {
                          retry(session, pending);
                          return;
                        }
                        complete(server, session, pending, delivery.outcome());
                      });
            });
  }

  private static void complete(
      MinecraftServer server, Session session, Pending p, Journal.Outcome outcome) {
    if (outcome.status() == Journal.Status.COMMITTED) {
      if (p.key.removal()) TerminalRemovals.get(server).completeBranch(p.key.generation());
      session.queue.remove(p.key);
      // Never enable from historical replay. A separate fresh persisted-state read must succeed.
      var entity = live(server, p.key);
      if (!p.key.removal() && entity != null) enqueue(server, p.key);
    } else if (outcome.status() == Journal.Status.DURABLY_REJECTED) {
      if (outcome.result().code().equals("BRANCH_STALE")
          || outcome.result().code().equals("TERMINAL_STALE")) {
        p.prepared = null;
        retry(session, p);
      } else {
        session.queue.remove(p.key);
      }
    } else retry(session, p);
  }

  private static void retry(Session session, Pending p) {
    p.busy = false;
    p.retry = session.tick + 100;
  }

  public static void stopped(MinecraftServer server) {
    var session = SESSIONS.remove(server);
    if (session != null) session.queue.values().forEach(p -> p.live.set(false));
  }
}
