package ca.emilybelnavis.mcmods.economy.banker;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banker.StaffingRecords.*;
import ca.emilybelnavis.mcmods.economy.banker.desk.*;
import ca.emilybelnavis.mcmods.economy.banker.entity.*;
import ca.emilybelnavis.mcmods.economy.banking.BankingController;
import ca.emilybelnavis.mcmods.economy.foundation.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.terminal.*;
import ca.emilybelnavis.mcmods.economy.village.*;
import ca.emilybelnavis.mcmods.economy.village.generation.VillageSites;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;

/** Loaded-only presentation coordinator. Missing loaded lookups never prove employee vacancy. */
public final class StaffingRuntime {
  public record Context(
      UUID branch,
      String institution,
      EconomyCurrency currency,
      BlockPos desk,
      BlockPos kiosk,
      long branchRevision,
      UUID assignment,
      long assignmentRevision) {}

  private static final class BranchWork {
    final UUID id;
    Branches.View view;
    boolean reading;
    long refresh;
    final Map<Integer, Slot> slots = new LinkedHashMap<>();

    BranchWork(UUID id) {
      this.id = id;
    }
  }

  private static final class Slot {
    final BranchWork branch;
    final int index;
    Row row;
    int accrued;
    boolean busy, spawnAuthorized, attempted;
    BranchService.Pending pending;
    Command command;
    UUID operation;
    AtomicBoolean lease;
    BooleanSupplier proof;
    Runnable committed;

    Slot(BranchWork branch, int index) {
      this.branch = branch;
      this.index = index;
    }
  }

  private static final class Pause {
    final UUID branch;
    final ActorId actor;
    final boolean paused;
    final AtomicBoolean permission;
    final CompletableFuture<String> result = new CompletableFuture<>();
    boolean busy;
    BranchService.Pending pending;
    List<Progress> progress = List.of();

    Pause(UUID branch, ActorId actor, boolean paused, AtomicBoolean permission) {
      this.branch = branch;
      this.actor = actor;
      this.paused = paused;
      this.permission = permission;
    }
  }

  private static final class Session {
    final LinkedHashMap<UUID, BranchWork> branches = new LinkedHashMap<>();
    final LinkedHashMap<UUID, Pause> pauses = new LinkedHashMap<>();
    final Set<UUID> outboxBusy = new HashSet<>();
    final Set<UUID> routing = new HashSet<>();
    long tick;
    int readCursor;
    boolean stopping;
  }

  private static final Map<MinecraftServer, Session> SESSIONS = new IdentityHashMap<>();

  private StaffingRuntime() {}

  private static Session session(MinecraftServer server) {
    return SESSIONS.computeIfAbsent(server, k -> new Session());
  }

  private static ServerServices services(MinecraftServer server) {
    var s = Economy.serverServices(server);
    return s != null && s.state() == ServerServices.State.READY ? s : null;
  }

  public static void observe(ServerLevel level, Branches.View view) {
    var s = session(level.getServer());
    if (s.stopping
        || !Set.of("ACTIVE", "PHYSICAL_COMPLETE", "ACTIVATION_PENDING").contains(view.state()))
      return;
    if (!s.branches.containsKey(view.id())
        && s.branches.size() >= 512
        && !retireUnloaded(level.getServer(), s)) return;
    var b = s.branches.computeIfAbsent(view.id(), BranchWork::new);
    if (b.view != null && b.view.revision() > view.revision()) return;
    b.view = view;
    for (var anchor : view.choice().desks())
      b.slots.computeIfAbsent(anchor.slot(), i -> new Slot(b, i));
  }

  public static void deskLoaded(ServerLevel level, BlockPos position, DeskBlockEntity entity) {
    entity.branchId().ifPresent(id -> watch(level.getServer(), id));
  }

  private static void watch(MinecraftServer server, UUID id) {
    var s = session(server);
    if (!s.stopping
        && (s.branches.containsKey(id) || s.branches.size() < 512 || retireUnloaded(server, s)))
      s.branches.computeIfAbsent(id, BranchWork::new);
  }

  private static boolean retireUnloaded(MinecraftServer server, Session s) {
    for (var b : List.copyOf(s.branches.values())) {
      if (b.view == null
          || b.reading
          || s.pauses.containsKey(b.id)
          || b.slots.values().stream()
              .anyMatch(
                  w ->
                      w.busy
                          || w.pending != null
                          || w.accrued != 0
                          || w.spawnAuthorized && !w.attempted)) continue;
      var l =
          server.getLevel(
              ResourceKey.create(
                  Registries.DIMENSION,
                  ResourceLocation.parse(b.view.plan().source().dimension())));
      if (l != null
          && (b.view.choice().desks().stream()
                  .anyMatch(
                      a ->
                          l.getChunkSource()
                                  .getChunkNow(a.position().x() >> 4, a.position().z() >> 4)
                              != null)
              || b.slots.values().stream()
                  .anyMatch(w -> w.row != null && l.getEntity(w.row.employee()) != null))) continue;
      // Only evict quiescent observations. Durable assignments/timers and uncertain spawn
      // identities remain intact.
      s.branches.remove(b.id);
      return true;
    }
    return false;
  }

  public static void deskRemoved(ServerLevel level, BlockPos position, DeskBlockEntity entity) {
    entity
        .branchId()
        .ifPresent(
            id -> {
              StaffingOutbox.get(level.getServer())
                  .event(
                      "DESK_REMOVED",
                      id,
                      entity.generation(),
                      null,
                      null,
                      -1,
                      level.dimension().location().toString(),
                      position);
              watch(level.getServer(), id);
            });
  }

  public static void died(BankerEntity entity) {
    removal(entity);
  }

  public static void removed(BankerEntity entity) {
    removal(entity);
  }

  private static void removal(BankerEntity entity) {
    if (!(entity.level() instanceof ServerLevel level) || !entity.assigned()) return;
    StaffingOutbox.get(level.getServer())
        .event(
            "DEATH",
            entity.branchId(),
            entity.deskGeneration(),
            entity.assignmentId(),
            entity.getUUID(),
            entity.deskSlot(),
            level.dimension().location().toString(),
            entity.blockPosition());
    watch(level.getServer(), entity.branchId());
  }

  public static void revalidate(MinecraftServer server) {
    var s = SESSIONS.get(server);
    if (s == null) return;
    for (var b : s.branches.values())
      for (var w : b.slots.values())
        if (w.lease != null && !w.proof.getAsBoolean()) w.lease.set(false);
  }

  public static void tick(MinecraftServer server) {
    var svc = services(server);
    if (svc == null) return;
    var s = session(server);
    if (s.stopping) return;
    s.tick++;
    drainOutbox(server, s, svc);
    int reads = 0;
    var branches = List.copyOf(s.branches.values());
    int start = s.readCursor;
    for (int offset = 0; offset < branches.size() && reads < 4; offset++) {
      int index = Math.floorMod(start + offset, branches.size());
      var b = branches.get(index);
      if (!b.reading && s.tick >= b.refresh) {
        reads++;
        s.readCursor = index + 1;
        refresh(server, s, svc, b);
      }
    }
    for (var b : branches) {
      for (var w : b.slots.values()) {
        // Accrue while a small checkpoint is in flight, retaining a hard 200-tick crash-loss bound.
        if (vacancyEligible(server, s, w) && w.row.elapsed() < 24000 && w.accrued < 200)
          w.accrued++;
        if (w.busy) continue;
        if (w.pending != null) {
          submit(server, s, svc, w);
          continue;
        }
        if (w.row == null) {
          attach(server, s, svc, w);
          continue;
        }
        switch (w.row.phase()) {
          case "RESERVED" -> {
            if (spawnEligible(server, s, w))
              transition(
                  server,
                  s,
                  svc,
                  w,
                  Action.BEGIN,
                  0,
                  null,
                  null,
                  () -> spawnEligible(server, s, w),
                  () -> w.spawnAuthorized = true);
          }
          case "SPAWNING" -> {
            var entity = employee(server, w);
            if (entity != null && identity(entity, w.row) && deskValid(server, w) && active(w))
              transition(
                  server,
                  s,
                  svc,
                  w,
                  Action.LIVE,
                  0,
                  null,
                  null,
                  () -> identity(employee(server, w), w.row) && deskValid(server, w) && active(w),
                  () -> {});
            else if (w.spawnAuthorized && !w.attempted) {
              if (spawnEligible(server, s, w)) spawn(server, s, svc, w);
              else
                transition(
                    server,
                    s,
                    svc,
                    w,
                    Action.DEFER,
                    0,
                    null,
                    null,
                    () -> !w.attempted && w.spawnAuthorized,
                    () -> w.spawnAuthorized = false);
            }
          }
          case "UNKNOWN" -> {
            if (identity(employee(server, w), w.row) && deskValid(server, w) && active(w))
              transition(
                  server,
                  s,
                  svc,
                  w,
                  Action.LIVE,
                  0,
                  null,
                  null,
                  () -> identity(employee(server, w), w.row) && deskValid(server, w) && active(w),
                  () -> {});
          }
          case "VACANT" -> {
            if (vacancyEligible(server, s, w)) {
              if (w.row.elapsed() >= 24000)
                transition(
                    server,
                    s,
                    svc,
                    w,
                    Action.REPLACE,
                    0,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    () -> vacancyEligible(server, s, w) && w.row.elapsed() >= 24000,
                    () -> {});
              else if (w.accrued >= 100 || w.row.elapsed() + w.accrued >= 24000)
                checkpoint(server, s, svc, w);
            }
          }
          default -> {}
        }
      }
    }
    for (var pause : List.copyOf(s.pauses.values())) if (!pause.busy) pause(server, s, svc, pause);
  }

  private static void refresh(MinecraftServer server, Session s, ServerServices svc, BranchWork b) {
    b.reading = true;
    b.refresh = s.tick + 100;
    svc.branches()
        .find(b.id)
        .whenComplete(
            (view, error) -> {
              if (SESSIONS.get(server) != s || s.stopping) return;
              if (error != null || view.isEmpty()) {
                b.reading = false;
                return;
              }
              b.view = view.get();
              for (var a : b.view.choice().desks())
                b.slots.computeIfAbsent(a.slot(), i -> new Slot(b, i));
              svc.staffing()
                  .rows(b.id)
                  .whenComplete(
                      (rows, failure) -> {
                        b.reading = false;
                        if (SESSIONS.get(server) != s || s.stopping || failure != null) return;
                        for (var row : rows) {
                          var w = b.slots.computeIfAbsent(row.slot(), i -> new Slot(b, i));
                          if (!w.busy && w.pending == null) accept(w, row);
                        }
                      });
            });
  }

  private static void accept(Slot w, Row row) {
    if (w.row != null && row.revision() < w.row.revision()) return;
    if (w.row != null && !w.row.assignment().equals(row.assignment())) {
      w.accrued = 0;
      w.spawnAuthorized = false;
      w.attempted = false;
    } else if (w.row != null
        && (!w.row.phase().equals(row.phase()) || !w.row.deskState().equals(row.deskState())))
      w.accrued = 0;
    w.row = row;
  }

  private static void attach(MinecraftServer server, Session s, ServerServices svc, Slot w) {
    if (w.branch.view == null
        || !w.branch.view.state().equals("ACTIVE")
        || w.branch.view.paused()
        || s.pauses.containsKey(w.branch.id)
        || !deskValid(server, w)) return;
    transition(
        server,
        s,
        svc,
        w,
        Action.ATTACH,
        0,
        UUID.randomUUID(),
        UUID.randomUUID(),
        () -> active(w) && deskValid(server, w) && !w.branch.view.paused(),
        () -> {});
  }

  private static boolean active(Slot w) {
    return w.branch.view != null && w.branch.view.state().equals("ACTIVE");
  }

  private static ServerLevel level(MinecraftServer server, Slot w) {
    if (w.branch.view == null) return null;
    return server.getLevel(
        ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.parse(w.branch.view.plan().source().dimension())));
  }

  private static BranchPlans.Anchor anchor(Slot w) {
    return w.branch.view == null
        ? null
        : w.branch.view.choice().desks().stream()
            .filter(a -> a.slot() == w.index)
            .findFirst()
            .orElse(null);
  }

  private static BlockPos pos(BranchPlans.Point p) {
    return new BlockPos(p.x(), p.y(), p.z());
  }

  private static boolean deskValid(MinecraftServer server, Slot w) {
    var level = level(server, w);
    var a = anchor(w);
    if (level == null || a == null || w.row != null && !w.row.deskState().equals("ACTIVE"))
      return false;
    var p = pos(a.position());
    return level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) != null
        && level.getBlockEntity(p) instanceof DeskBlockEntity e
        && e.branchId().filter(w.branch.id::equals).isPresent()
        && e.generation().equals(a.generation())
        && DeskGeometry.matches(level, p, a.generation());
  }

  private static BankerEntity employee(MinecraftServer server, Slot w) {
    var l = level(server, w);
    return l != null && w.row != null && l.getEntity(w.row.employee()) instanceof BankerEntity e
        ? e
        : null;
  }

  private static boolean identity(BankerEntity entity, Row row) {
    return entity != null
        && row != null
        && entity.isAlive()
        && entity.assigned()
        && entity.getUUID().equals(row.employee())
        && entity.branchId().equals(row.branch())
        && entity.deskSlot() == row.slot()
        && entity.deskGeneration().equals(row.deskGeneration())
        && entity.assignmentId().equals(row.assignment());
  }

  private static boolean vacancyEligible(MinecraftServer server, Session s, Slot w) {
    return active(w)
        && w.row != null
        && w.row.phase().equals("VACANT")
        && !w.branch.view.paused()
        && !s.pauses.containsKey(w.branch.id)
        && deskValid(server, w);
  }

  private static BlockPos standing(MinecraftServer server, Slot w) {
    var l = level(server, w);
    var a = anchor(w);
    if (l == null || a == null) throw new IllegalStateException("Missing banker desk location");
    var p = pos(a.position());
    return DeskGeometry.standing(p, l.getBlockState(p).getValue(DeskBlock.FACING));
  }

  private static boolean spawnEligible(MinecraftServer server, Session s, Slot w) {
    if (!active(w)
        || w.branch.view.paused()
        || s.pauses.containsKey(w.branch.id)
        || !deskValid(server, w)) return false;
    var l = level(server, w);
    var p = standing(server, w);
    for (int dx = -1; dx <= 1; dx++)
      for (int dz = -1; dz <= 1; dz++)
        if (l.getChunkSource().getChunkNow((p.getX() + dx) >> 4, (p.getZ() + dz) >> 4) == null)
          return false;
    if (!l.getWorldBorder().isWithinBounds(p)
        || !l.getBlockState(p.below()).isFaceSturdy(l, p.below(), Direction.UP)) return false;
    if (!l.getBlockState(p).isAir()
        || !l.getBlockState(p.above()).isAir()
        || !l.getBlockState(p.above(2)).isAir()) return false;
    var box =
        new net.minecraft.world.phys.AABB(
            p.getX() + .2, p.getY(), p.getZ() + .2, p.getX() + .8, p.getY() + 1.95, p.getZ() + .8);
    return l.getEntities((Entity) null, box).isEmpty() && l.noCollision(box);
  }

  private static void spawn(MinecraftServer server, Session s, ServerServices svc, Slot w) {
    w.attempted = true;
    var l = level(server, w);
    var p = standing(server, w);
    var e = BankerCatalog.BANKER.get().create(l);
    if (e == null) {
      transition(server, s, svc, w, Action.UNKNOWN, 0, null, null, () -> true, () -> {});
      return;
    }
    e.setUUID(w.row.employee());
    e.bind(
        w.row.branch(), w.row.slot(), w.row.deskGeneration(), w.row.assignment(), w.row.revision());
    e.moveTo(p.getX() + .5, p.getY(), p.getZ() + .5, 0, 0);
    if (!l.addFreshEntity(e)) {
      transition(server, s, svc, w, Action.UNKNOWN, 0, null, null, () -> true, () -> {});
      return;
    }
    transition(
        server,
        s,
        svc,
        w,
        Action.LIVE,
        0,
        null,
        null,
        () -> identity(e, w.row) && deskValid(server, w) && active(w),
        () -> {});
  }

  private static Command command(Slot w, Action action, int delta, UUID assignment, UUID employee) {
    return new Command(
        action,
        w.branch.id,
        w.branch.view.revision(),
        w.index,
        w.row == null ? 0 : w.row.revision(),
        assignment != null ? assignment : w.row == null ? null : w.row.assignment(),
        employee != null ? employee : w.row == null ? null : w.row.employee(),
        delta,
        false,
        List.of());
  }

  private static void transition(
      MinecraftServer server,
      Session s,
      ServerServices svc,
      Slot w,
      Action action,
      int delta,
      UUID assignment,
      UUID employee,
      BooleanSupplier proof,
      Runnable committed) {
    var cmd = command(w, action, delta, assignment, employee);
    w.command = cmd;
    w.operation = UUID.randomUUID();
    w.lease = new AtomicBoolean(proof.getAsBoolean());
    w.proof = proof;
    w.committed = committed;
    w.pending = svc.staffing().update(cmd, BranchPlans.owner(w.branch.id), w.operation, w.lease);
    submit(server, s, svc, w);
  }

  private static void submit(MinecraftServer server, Session s, ServerServices svc, Slot w) {
    w.busy = true;
    var pending = w.pending;
    pending
        .lookup()
        .whenComplete(
            (prior, error) -> {
              if (SESSIONS.get(server) != s || s.stopping) return;
              if (error != null) {
                w.busy = false;
                return;
              }
              if (prior.isPresent()) {
                resolved(server, s, svc, w, prior.get());
                return;
              }
              pending
                  .submit()
                  .completion()
                  .whenComplete(
                      (delivery, failure) -> {
                        if (SESSIONS.get(server) == s && !s.stopping) {
                          if (failure != null) w.busy = false;
                          else resolved(server, s, svc, w, delivery.outcome());
                        }
                      });
            });
  }

  private static void resolved(
      MinecraftServer server, Session s, ServerServices svc, Slot w, Journal.Outcome outcome) {
    if (outcome.status() == Journal.Status.UNKNOWN
        || outcome.status() == Journal.Status.NOT_ACCEPTED && w.lease.get()) {
      w.busy = false;
      return;
    }
    var cmd = w.command;
    boolean success = outcome.status() == Journal.Status.COMMITTED;
    if (success) {
      w.committed.run();
      if (cmd.action() == Action.CHECKPOINT) w.accrued = Math.max(0, w.accrued - cmd.delta());
      accept(w, BankStaffing.decodeRow(outcome.result().payload()));
    }
    if (cmd.action() == Action.CHECKPOINT)
      StaffingOutbox.get(server).completeCheckpoint(w.operation);
    w.pending = null;
    w.lease = null;
    w.proof = null;
    svc.staffing()
        .find(w.branch.id, w.index)
        .whenComplete(
            (row, error) -> {
              w.busy = false;
              if (SESSIONS.get(server) != s || s.stopping) return;
              if (error == null && row.isPresent()) accept(w, row.get());
              w.branch.refresh = 0;
            });
  }

  private static void checkpoint(MinecraftServer server, Session s, ServerServices svc, Slot w) {
    if (w.accrued == 0 || w.pending != null) return;
    var cmd = command(w, Action.CHECKPOINT, w.accrued, null, null);
    var operation = UUID.randomUUID();
    StaffingOutbox.get(server).checkpoint(operation, cmd);
    w.command = cmd;
    w.operation = operation;
    w.lease = new AtomicBoolean(true);
    w.proof = () -> vacancyEligible(server, s, w);
    w.committed = () -> {};
    w.pending = svc.staffing().update(cmd, BranchPlans.owner(w.branch.id), operation, w.lease);
    submit(server, s, svc, w);
  }

  public static CompletionStage<String> pause(
      MinecraftServer server,
      UUID branch,
      ActorId actor,
      boolean paused,
      AtomicBoolean permission) {
    var svc = services(server);
    if (svc == null || !permission.get())
      return CompletableFuture.completedFuture("STAFFING_UNAVAILABLE");
    var s = session(server);
    if (s.pauses.containsKey(branch)) return CompletableFuture.completedFuture("STAFFING_BUSY");
    watch(server, branch);
    var value = new Pause(branch, actor, paused, permission);
    s.pauses.put(branch, value);
    return value.result;
  }

  private static void pause(MinecraftServer server, Session s, ServerServices svc, Pause pause) {
    if (pause.pending != null) {
      resolvePause(
          server, s, svc, pause, s.branches.get(pause.branch), pause.pending, pause.progress);
      return;
    }
    if (!pause.permission.get()) {
      s.pauses.remove(pause.branch);
      pause.result.complete("STAFFING_PERMISSION_LOST");
      return;
    }
    var b = s.branches.get(pause.branch);
    if (b == null || b.view == null) return;
    if (b.reading || b.slots.values().stream().anyMatch(w -> w.busy || w.pending != null)) return;
    pause.busy = true;
    svc.branches()
        .find(pause.branch)
        .whenComplete(
            (view, error) -> {
              if (SESSIONS.get(server) != s || s.stopping) return;
              if (error != null || view.isEmpty()) {
                pause.busy = false;
                return;
              }
              b.view = view.get();
              var progress =
                  b.slots.values().stream()
                      .filter(
                          w ->
                              w.row != null
                                  && w.row.phase().equals("VACANT")
                                  && w.row.deskState().equals("ACTIVE")
                                  && w.accrued > 0)
                      .map(w -> new Progress(w.index, w.row.revision(), w.accrued))
                      .toList();
              var cmd =
                  new Command(
                      Action.PAUSE,
                      b.id,
                      b.view.revision(),
                      -1,
                      0,
                      null,
                      null,
                      0,
                      pause.paused,
                      progress);
              pause.pending =
                  svc.staffing().update(cmd, pause.actor, UUID.randomUUID(), pause.permission);
              pause.progress = progress;
              resolvePause(server, s, svc, pause, b, pause.pending, progress);
            });
  }

  private static void resolvePause(
      MinecraftServer server,
      Session s,
      ServerServices svc,
      Pause pause,
      BranchWork b,
      BranchService.Pending pending,
      List<Progress> progress) {
    pause.busy = true;
    pending
        .lookup()
        .whenComplete(
            (found, error) -> {
              if (SESSIONS.get(server) != s || s.stopping) return;
              if (error != null) {
                pause.busy = false;
                return;
              }
              if (found.isPresent()) {
                finishPause(server, s, pause, b, found.get(), progress);
                return;
              }
              pending
                  .submit()
                  .completion()
                  .whenComplete(
                      (delivery, failure) -> {
                        if (SESSIONS.get(server) != s || s.stopping) return;
                        if (failure != null
                            || delivery.outcome().status() == Journal.Status.UNKNOWN
                            || delivery.outcome().status() == Journal.Status.NOT_ACCEPTED
                                && pause.permission.get()) {
                          pause.busy = false;
                          return;
                        }
                        finishPause(server, s, pause, b, delivery.outcome(), progress);
                      });
            });
  }

  private static void finishPause(
      MinecraftServer server,
      Session s,
      Pause pause,
      BranchWork b,
      Journal.Outcome outcome,
      List<Progress> progress) {
    if (outcome.status() == Journal.Status.COMMITTED) {
      for (var p : progress) {
        var w = b.slots.get(p.slot());
        if (w != null) w.accrued = Math.max(0, w.accrued - p.delta());
      }
      b.view = null;
      b.refresh = 0;
    }
    s.pauses.remove(pause.branch);
    pause.result.complete(outcome.status() + " / " + outcome.result().code());
  }

  private static void drainOutbox(MinecraftServer server, Session s, ServerServices svc) {
    var out = StaffingOutbox.get(server);
    int admitted = 0;
    for (var checkpoint : out.checkpoints()) {
      if (admitted >= 4) break;
      boolean owned =
          s.branches.values().stream()
              .flatMap(b -> b.slots.values().stream())
              .anyMatch(w -> checkpoint.operation().equals(w.operation) && w.pending != null);
      if (owned || !s.outboxBusy.add(checkpoint.operation())) continue;
      admitted++;
      var pending =
          svc.staffing()
              .update(
                  checkpoint.command(),
                  BranchPlans.owner(checkpoint.command().branch()),
                  checkpoint.operation(),
                  new AtomicBoolean(true));
      resolveOutbox(
          server,
          s,
          pending,
          true,
          () -> {
            out.completeCheckpoint(checkpoint.operation());
            s.outboxBusy.remove(checkpoint.operation());
          },
          () -> s.outboxBusy.remove(checkpoint.operation()));
    }
    for (var event : out.events()) {
      if (admitted >= 4) break;
      if (!s.outboxBusy.add(event.id())) continue;
      admitted++;
      svc.branches()
          .find(event.branch())
          .whenComplete(
              (view, error) -> {
                if (SESSIONS.get(server) != s || s.stopping) return;
                if (error != null || view.isEmpty()) {
                  s.outboxBusy.remove(event.id());
                  return;
                }
                var a =
                    view.get().choice().desks().stream()
                        .filter(
                            d ->
                                d.generation().equals(event.desk())
                                    && (event.action().equals("DEATH")
                                        || pos(d.position()).equals(event.position())
                                            && view.get()
                                                .plan()
                                                .source()
                                                .dimension()
                                                .equals(event.dimension())))
                        .findFirst()
                        .orElse(null);
                if (a == null) {
                  out.completeEvent(event.id());
                  s.outboxBusy.remove(event.id());
                  return;
                }
                svc.staffing()
                    .find(event.branch(), a.slot())
                    .whenComplete(
                        (row, failure) -> {
                          if (SESSIONS.get(server) != s || s.stopping) return;
                          if (failure != null) {
                            s.outboxBusy.remove(event.id());
                            return;
                          }
                          if (event.action().equals("DEATH")
                              && (row.isEmpty()
                                  || !row.get().employee().equals(event.employee())
                                  || !row.get().assignment().equals(event.assignment())
                                  || row.get().phase().equals("VACANT"))) {
                            out.completeEvent(event.id());
                            s.outboxBusy.remove(event.id());
                            return;
                          }
                          if (row.isPresent()
                              && event.action().equals("DESK_REMOVED")
                              && row.get().deskState().equals("REMOVED")) {
                            out.completeEvent(event.id());
                            s.outboxBusy.remove(event.id());
                            return;
                          }
                          var command =
                              new Command(
                                  event.action().equals("DEATH")
                                      ? Action.DEATH
                                      : Action.DESK_REMOVED,
                                  event.branch(),
                                  view.get().revision(),
                                  a.slot(),
                                  row.map(Row::revision).orElse(0L),
                                  row.map(Row::assignment)
                                      .orElseGet(() -> eventIdentity(event.id(), "assignment")),
                                  row.map(Row::employee)
                                      .orElseGet(() -> eventIdentity(event.id(), "employee")),
                                  0,
                                  false,
                                  List.of());
                          // Revision-specific identity makes a corrected stale submission distinct,
                          // while uncertain outcomes keep the exact intent.
                          var operation =
                              UUID.nameUUIDFromBytes(
                                  (event.id()
                                          + "|"
                                          + command.revision()
                                          + "|"
                                          + command.branchRevision())
                                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                          var pending =
                              svc.staffing()
                                  .update(
                                      command,
                                      BranchPlans.owner(event.branch()),
                                      operation,
                                      new AtomicBoolean(true));
                          resolveOutbox(
                              server,
                              s,
                              pending,
                              false,
                              () -> {
                                out.completeEvent(event.id());
                                s.outboxBusy.remove(event.id());
                                watch(server, event.branch());
                                var b = s.branches.get(event.branch());
                                if (b != null) b.refresh = 0;
                              },
                              () -> s.outboxBusy.remove(event.id()));
                        });
              });
    }
  }

  private static UUID eventIdentity(UUID event, String role) {
    return UUID.nameUUIDFromBytes(
        (event + "|" + role).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static void resolveOutbox(
      MinecraftServer server,
      Session s,
      BranchService.Pending pending,
      boolean discardRejected,
      Runnable complete,
      Runnable retry) {
    pending
        .lookup()
        .whenComplete(
            (prior, error) -> {
              if (SESSIONS.get(server) != s || s.stopping) return;
              if (error != null) {
                retry.run();
                return;
              }
              if (prior.isPresent()) {
                if (prior.get().status() == Journal.Status.COMMITTED
                    || discardRejected && prior.get().status() == Journal.Status.DURABLY_REJECTED)
                  complete.run();
                else retry.run();
                return;
              }
              pending
                  .submit()
                  .completion()
                  .whenComplete(
                      (delivery, failure) -> {
                        if (SESSIONS.get(server) != s || s.stopping) return;
                        if (failure == null
                            && (delivery.outcome().status() == Journal.Status.COMMITTED
                                || discardRejected
                                    && delivery.outcome().status()
                                        == Journal.Status.DURABLY_REJECTED)) complete.run();
                        else retry.run();
                      });
            });
  }

  public static void entityTick(BankerEntity entity) {
    if (!(entity.level() instanceof ServerLevel level) || !entity.assigned()) return;
    var server = level.getServer();
    watch(server, entity.branchId());
    var s = SESSIONS.get(server);
    var b = s.branches.get(entity.branchId());
    var w = b == null ? null : b.slots.get(entity.deskSlot());
    if (w == null
        || w.row == null
        || !identity(entity, w.row)
        || !w.row.phase().equals("LIVE")
        || !active(w)
        || !deskValid(server, w)
        || level(server, w) != level) {
      entity.workTarget(null, null, false);
      return;
    }
    if (entity.assignmentRevision() < w.row.revision()) entity.assignmentRevision(w.row.revision());
    var work = standing(server, w);
    var c = b.view.choice();
    var transform = c.transform();
    var rest =
        switch (c.size()) {
          case STANDARD -> VillageSites.bankRest(transform);
          case COMPACT ->
              VillageSites.local(transform.origin(), transform.quarterTurns(), 11, 9, 2, 1, 7);
          case LARGER ->
              VillageSites.local(
                  transform.origin(),
                  transform.quarterTurns(),
                  17,
                  11,
                  w.index == 0 ? 5 : 10,
                  1,
                  9);
        };
    entity.workTarget(work, pos(rest), true);
  }

  public static Optional<Context> context(ServerPlayer player, BankerEntity entity) {
    var server = player.server;
    var svc = services(server);
    var s = SESSIONS.get(server);
    if (svc == null
        || s == null
        || !player.isAlive()
        || !entity.isAlive()
        || server.getPlayerList().getPlayer(player.getUUID()) != player
        || player.serverLevel() != entity.level()
        || player.distanceToSqr(entity) > 36
        || !entity.assigned()) return Optional.empty();
    var b = s.branches.get(entity.branchId());
    var w = b == null ? null : b.slots.get(entity.deskSlot());
    if (w == null
        || w.row == null
        || !w.row.phase().equals("LIVE")
        || !identity(entity, w.row)
        || entity.assignmentRevision() != w.row.revision()
        || !active(w)
        || !deskValid(server, w)
        || level(server, w) != player.serverLevel()) return Optional.empty();
    var kiosk =
        b.view.choice().services().stream()
            .filter(a -> a.serviceKind() == TerminalPolicy.Kind.KIOSK)
            .map(a -> pos(a.position()))
            .filter(
                p ->
                    player.serverLevel().getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4)
                        != null)
            .filter(
                p ->
                    player.serverLevel().getBlockEntity(p) instanceof TerminalBlockEntity e
                        && e.policyReady()
                        && b.id.equals(e.branchId())
                        && TerminalGeometry.matches(player.serverLevel(), p, e.generation()))
            .min(
                Comparator.comparingDouble(
                    p ->
                        player
                            .position()
                            .distanceToSqr(p.getX() + .5, p.getY() + .5, p.getZ() + .5)))
            .orElse(null);
    return Optional.of(
        new Context(
            b.id,
            b.view.choice().institution(),
            b.view.choice().nativeCurrency(),
            pos(anchor(w).position()),
            kiosk,
            b.view.revision(),
            w.row.assignment(),
            w.row.revision()));
  }

  public static boolean openBanking(ServerPlayer player, BankerEntity entity) {
    var context = context(player, entity).orElse(null);
    if (context == null
        || context.kiosk() == null
        || player
                .position()
                .distanceToSqr(
                    context.kiosk().getX() + .5,
                    context.kiosk().getY() + .5,
                    context.kiosk().getZ() + .5)
            > 36) return false;
    // The existing controller creates a fresh physical menu; its worker path issues ordinary
    // KioskAdmission, never NPC authority.
    var s = SESSIONS.get(player.server);
    var svc = services(player.server);
    var menu = player.containerMenu;
    if (!(menu instanceof ca.emilybelnavis.mcmods.economy.banker.dialogue.BankerMenu)
        || !s.routing.add(player.getUUID())) return false;
    svc.staffing()
        .find(context.branch(), entity.deskSlot())
        .whenComplete(
            (row, error) -> {
              if (SESSIONS.get(player.server) != s
                  || s.stopping
                  || services(player.server) != svc
                  || error != null
                  || row.isEmpty()) {
                s.routing.remove(player.getUUID());
                return;
              }
              svc.branches()
                  .find(context.branch())
                  .whenComplete(
                      (branch, failure) -> {
                        s.routing.remove(player.getUUID());
                        if (SESSIONS.get(player.server) != s
                            || services(player.server) != svc
                            || player.containerMenu != menu) return;
                        var fresh = context(player, entity).orElse(null);
                        if (failure != null
                            || branch.isEmpty()
                            || fresh == null
                            || !branch.get().state().equals("ACTIVE")
                            || branch.get().revision() != context.branchRevision()
                            || row.get().revision() != context.assignmentRevision()
                            || !row.get().phase().equals("LIVE")
                            || !identity(entity, row.get())
                            || fresh.kiosk() == null
                            || player
                                    .position()
                                    .distanceToSqr(
                                        fresh.kiosk().getX() + .5,
                                        fresh.kiosk().getY() + .5,
                                        fresh.kiosk().getZ() + .5)
                                > 36) return;
                        BankingController.open(player, fresh.kiosk());
                      });
            });
    return true;
  }

  private static void saveStopCheckpoint(
      MinecraftServer server, ServerServices svc, Command command) {
    var op = UUID.randomUUID();
    StaffingOutbox.get(server).checkpoint(op, command);
    svc.staffing()
        .update(command, BranchPlans.owner(command.branch()), op, new AtomicBoolean(true))
        .submit();
  }

  public static void stopping(MinecraftServer server) {
    var s = SESSIONS.get(server);
    var svc = services(server);
    if (s == null || svc == null) return;
    s.stopping = true;
    for (var b : s.branches.values())
      for (var w : b.slots.values()) {
        boolean checkpoint = w.pending != null && w.command.action() == Action.CHECKPOINT;
        if (w.lease != null && !checkpoint) w.lease.set(false);
        if (checkpoint) {
          // The delta was earned before stop; retain the exact first intent, then drain its
          // buffered tail.
          w.lease.set(true);
          w.pending.submit();
          int remaining = w.accrued - w.command.delta();
          if (remaining > 0) {
            var c = w.command;
            var tail =
                new Command(
                    Action.CHECKPOINT,
                    c.branch(),
                    c.branchRevision(),
                    c.slot(),
                    c.revision() + 1,
                    c.assignment(),
                    c.employee(),
                    remaining,
                    false,
                    List.of());
            saveStopCheckpoint(server, svc, tail);
          }
        } else if (w.row != null
            && w.row.phase().equals("VACANT")
            && w.accrued > 0
            && w.pending == null
            && b.view != null) {
          saveStopCheckpoint(server, svc, command(w, Action.CHECKPOINT, w.accrued, null, null));
        }
      }
    for (var p : s.pauses.values()) p.result.complete("STAFFING_SERVER_STOPPED");
    SESSIONS.remove(server);
  }
}
