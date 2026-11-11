package ca.emilybelnavis.mcmods.economy.village;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.foundation.BranchService;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.terminal.*;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans.*;
import ca.emilybelnavis.mcmods.economy.village.generation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Nonblocking generation publication and bounded loaded-only source/receipt/explicit-retry adapter.
 */
public final class GenerationRuntime {
  public record Publication(WorldId world, String dimension, PlanningSnapshotV1 snapshot) {}

  private record ChunkKey(ServerLevel level, ChunkPos position) {}

  private record SourceKey(ServerLevel level, Source source) {}

  private static final class SourceWork {
    final SourceKey key;
    VillagePlanV1 source, registered;
    boolean busy, intact = true;
    long next;
    BranchService.Pending pending;
    Consumer<Journal.Outcome> continuation;

    SourceWork(SourceKey key, VillagePlanV1 source) {
      this.key = key;
      this.source = source;
    }
  }

  private static final class RetryWork {
    final ServerLevel level;
    final UUID branch, operation;
    final ActorId operator;
    final AtomicBoolean permission;
    final VillageSites.Candidate candidate;
    final CompletableFuture<String> result = new CompletableFuture<>();
    final List<BlockState> states;
    final AtomicBoolean physical = new AtomicBoolean(true);
    BranchService.Pending pending;
    Consumer<Journal.Outcome> continuation;
    boolean busy, started;
    int cursor;
    long next, attempt;

    RetryWork(
        ServerLevel level,
        UUID branch,
        ActorId operator,
        AtomicBoolean permission,
        VillageSites.Candidate candidate,
        List<BlockState> states) {
      this.level = level;
      this.branch = branch;
      this.operator = operator;
      this.permission = permission;
      this.candidate = candidate;
      this.states = states;
      operation = UUID.randomUUID();
    }
  }

  private static final class Session {
    final LinkedHashSet<ChunkKey> chunks = new LinkedHashSet<>();
    final LinkedHashMap<SourceKey, SourceWork> sources = new LinkedHashMap<>();
    final LinkedHashMap<UUID, RetryWork> retries = new LinkedHashMap<>();
    boolean publishing;
    long tick, nextPublication, publicationEpoch;
    Object resources;
    boolean resourcesReady;
  }

  private static volatile Map<ChunkGenerator, Publication> published = Map.of();
  private static final Map<MinecraftServer, Session> SESSIONS = new IdentityHashMap<>();
  private static boolean initialized;

  private GenerationRuntime() {}

  public static void initialize() {
    if (initialized) return;
    initialized = true;
    NeoForge.EVENT_BUS.addListener(GenerationRuntime::levelLoaded);
    NeoForge.EVENT_BUS.addListener(GenerationRuntime::chunkLoaded);
  }

  /**
   * Exact generator identity supplies dimension; generation contexts themselves have no dimension.
   */
  public static Publication publication(ChunkGenerator generator) {
    return published.get(generator);
  }

  /** Called before a committed denomination change is acknowledged on the server thread. */
  public static void denominationChanged(MinecraftServer server) {
    var session = SESSIONS.get(server);
    if (session == null) return;
    session.publicationEpoch++;
    session.nextPublication = 0;
    for (var level : server.getAllLevels())
      publish(
          level.getChunkSource().getGenerator(),
          new Publication(
              Economy.serverServices(server).worldId(),
              level.dimension().location().toString(),
              null));
  }

  private static synchronized void publish(ChunkGenerator generator, Publication publication) {
    var copy = new IdentityHashMap<ChunkGenerator, Publication>();
    copy.putAll(published);
    copy.put(generator, publication);
    published = Collections.unmodifiableMap(copy);
  }

  private static void levelLoaded(LevelEvent.Load event) {
    if (event.getLevel() instanceof ServerLevel level) {
      SESSIONS.computeIfAbsent(level.getServer(), s -> new Session());
      publish(
          level.getChunkSource().getGenerator(),
          new Publication(null, level.dimension().location().toString(), null));
    }
  }

  private static void chunkLoaded(ChunkEvent.Load event) {
    if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk)
      observeLoaded(level, event.getChunk().getPos());
  }

  public static void observeLoaded(ServerLevel level, ChunkPos position) {
    var session = SESSIONS.computeIfAbsent(level.getServer(), s -> new Session());
    if (session.chunks.size() < 1024) session.chunks.add(new ChunkKey(level, position));
  }

  /** Refresh detached physical leases before ServerServices publishes worker callbacks. */
  public static void revalidate(MinecraftServer server) {
    var session = SESSIONS.get(server);
    if (session == null) return;
    for (var work : session.retries.values())
      if (!work.permission.get() || !unchanged(work)) work.physical.set(false);
  }

  public static void tick(MinecraftServer server) {
    var session = SESSIONS.computeIfAbsent(server, s -> new Session());
    session.tick++;
    var services = Economy.serverServices(server);
    if (services == null || services.state() != ServerServices.State.READY) return;
    if (session.resources != server.getResourceManager()) {
      session.resources = server.getResourceManager();
      session.resourcesReady = VillageClassifier.resourcesReady(server.getResourceManager());
      session.nextPublication = 0;
    }
    if (!session.publishing && session.tick >= session.nextPublication) {
      session.publishing = true;
      session.nextPublication = session.tick + 100;
      long epoch = session.publicationEpoch;
      services
          .branches()
          .planningSnapshot(VillageClassifier.REVISION, null)
          .whenComplete(
              (snapshot, error) -> {
                session.publishing = false;
                if (SESSIONS.get(server) != session
                    || services.state() != ServerServices.State.READY
                    || session.publicationEpoch != epoch) return;
                for (var level : server.getAllLevels())
                  publish(
                      level.getChunkSource().getGenerator(),
                      new Publication(
                          services.worldId(),
                          level.dimension().location().toString(),
                          error == null
                                  && session.resourcesReady
                                  && level
                                      .dimension()
                                      .equals(net.minecraft.world.level.Level.OVERWORLD)
                              ? snapshot.orElse(null)
                              : null));
              });
    }
    int chunks = 0;
    for (var key : List.copyOf(session.chunks)) {
      if (chunks++ == 4) break;
      session.chunks.remove(key);
      var chunk = key.level().getChunkSource().getChunkNow(key.position().x, key.position().z);
      if (chunk == null) continue;
      for (var start : chunk.getAllStarts().values()) {
        var sources =
            start.getPieces().stream()
                .filter(p -> p instanceof VillageGenerationPiece)
                .map(p -> (VillageGenerationPiece) p)
                .toList();
        for (var source : sources) {
          if (source.branchSlot() != -1) continue;
          if (source.world().isPresent() && !source.world().get().equals(services.worldId()))
            continue;
          var plan = source.plan();
          var sourceKey = new SourceKey(key.level(), plan.source());
          boolean intact = sources.stream().filter(p -> p.branchSlot() == -1).count() == 1;
          for (var branch : plan.branches()) {
            long count =
                sources.stream()
                    .filter(
                        p ->
                            p.branchSlot() == branch.slot()
                                && p.plan().digest().equals(plan.digest())
                                && p.world().equals(source.world()))
                    .count();
            if (branch.state() == State.RESERVED ? count != 1 : count != 0) intact = false;
          }
          if (sources.stream()
              .anyMatch(
                  p ->
                      !p.plan().digest().equals(plan.digest())
                          || !p.world().equals(source.world()))) intact = false;
          if (session.sources.containsKey(sourceKey) || session.sources.size() < 1024) {
            var work = session.sources.computeIfAbsent(sourceKey, k -> new SourceWork(k, plan));
            work.intact &= intact;
          }
        }
      }
    }

    int observations = 0;
    for (var work : List.copyOf(session.sources.values())) {
      if (observations == 4) break;
      if (work.busy || work.next > session.tick) continue;
      if (work.key
              .level()
              .getChunkSource()
              .getChunkNow(work.key.source().startX(), work.key.source().startZ())
          == null) {
        session.sources.remove(work.key);
        continue;
      }
      observations++;
      work.busy = true;
      if (work.pending != null) {
        submitSource(session, work);
        continue;
      }
      if (work.registered != null) {
        observeRegistered(server, session, work, work.registered);
        retrySource(session, work);
        continue;
      }
      services
          .branches()
          .existingPlan(work.source.source())
          .whenComplete(
              (existing, readError) -> {
                if (readError != null) {
                  retrySource(session, work);
                  return;
                }
                if (existing.isPresent()) {
                  var stored = existing.get();
                  if (work.source.captureStatus() == CaptureStatus.CAPTURED
                      ? !work.source.digest().equals(stored.digest())
                      : work.source.buildingCount() != stored.buildingCount()
                          || !work.source.originalBounds().equals(stored.originalBounds())
                          || !work.source.junctions().equals(stored.junctions()))
                    work.intact = false;
                  if (stored.captureStatus() != CaptureStatus.CAPTURE_PENDING) {
                    work.registered = stored;
                    observeRegistered(server, session, work, stored);
                    retrySource(session, work);
                    return;
                  }
                }
                if (!session.resourcesReady) {
                  retrySource(session, work);
                  return;
                }
                services
                    .branches()
                    .reconcilePlan(
                        work.source, VillageClassifier.REVISION, work.key.level().getSeed())
                    .whenComplete(
                        (prepared, error) -> {
                          if (error != null || prepared.isEmpty()) {
                            retrySource(session, work);
                            return;
                          }
                          work.pending = prepared.get();
                          work.continuation =
                              outcome -> {
                                if (outcome.status() != Journal.Status.COMMITTED) {
                                  retrySource(session, work);
                                  return;
                                }
                                work.pending = null;
                                services
                                    .branches()
                                    .existingPlan(work.source.source())
                                    .whenComplete(
                                        (plan, failure) -> {
                                          if (failure == null && plan.isPresent()) {
                                            work.registered = plan.get();
                                            observeRegistered(server, session, work, plan.get());
                                          }
                                          retrySource(session, work);
                                        });
                              };
                          submitSource(session, work);
                        });
              });
    }

    int effects = 0;
    for (var work : List.copyOf(session.retries.values())) {
      if (effects == 4) break;
      if (work.busy || work.next > session.tick) continue;
      effects++;
      if (work.pending != null) {
        submitRetry(session, work);
        continue;
      }
      if (work.started) applyRetry(server, session, work);
    }
  }

  private static void observeRegistered(
      MinecraftServer server, Session session, SourceWork source, VillagePlanV1 plan) {
    var services = Economy.serverServices(server);
    for (var branch : plan.branches()) {
      if (source.intact) {
        observeReceipts(server, session, source, plan, branch);
        continue;
      }
      var id =
          BranchPlans.branchId(BranchPlans.id(services.worldId(), plan.source()), branch.slot());
      services
          .branches()
          .find(id)
          .whenComplete(
              (found, error) -> {
                if (error != null
                    || found.isEmpty()
                    || Set.of("CONFLICT", "REMOVED", "OMITTED").contains(found.get().state()))
                  return;
                var pending =
                    services
                        .branches()
                        .observeBranch(
                            found.get(),
                            State.CONFLICT,
                            "SOURCE_PIECE_MANIFEST_CONFLICT",
                            new AtomicBoolean(true));
                pending
                    .lookup()
                    .whenComplete(
                        (prior, failure) -> {
                          if (failure == null && prior.isEmpty()) pending.submit();
                        });
              });
    }
  }

  private static void observeReceipts(
      MinecraftServer server,
      Session session,
      SourceWork source,
      VillagePlanV1 plan,
      BranchPlanV1 branch) {
    var services = Economy.serverServices(server);
    var id = BranchPlans.branchId(BranchPlans.id(services.worldId(), plan.source()), branch.slot());
    services
        .branches()
        .find(id)
        .whenComplete(
            (found, error) -> {
              if (error != null || found.isEmpty() || session.retries.containsKey(id)) return;
              var view = found.get();
              if (Set.of("PHYSICAL_COMPLETE", "ACTIVATION_PENDING", "ACTIVE")
                  .contains(view.state())) {
                BankLandmarks.record(source.key.level(), plan.source(), view.choice());
                ca.emilybelnavis.mcmods.economy.banker.StaffingRuntime.observe(
                    source.key.level(), view);
              }
              if (!Set.of("RESERVED", "PLACING").contains(view.state())) return;
              services
                  .branches()
                  .placementAttempt(id)
                  .whenComplete(
                      (attempt, failure) -> {
                        if (failure != null) return;
                        if (attempt.isPresent()) {
                          // A lost in-memory operator effect has no restart-safe proof permitting
                          // resubmission.
                          markInterrupted(services, view, attempt.get());
                          return;
                        }
                        var choice = view.choice();
                        if (choice.manifests().isEmpty()) return;
                        boolean complete = true, conflict = false;
                        for (var expected : choice.manifests()) {
                          var pos = new ChunkPos(expected.chunkX(), expected.chunkZ());
                          var chunk = source.key.level().getChunkSource().getChunkNow(pos.x, pos.z);
                          if (chunk == null) return;
                          var receipt = VillageGeneration.receipt(chunk, id, pos, expected);
                          if (!receipt.name().equals("COMPLETE")) {
                            complete = false;
                            conflict = true;
                          }
                        }
                        var state =
                            complete
                                ? (view.state().equals("RESERVED")
                                    ? State.PLACING
                                    : State.PHYSICAL_COMPLETE)
                                : State.CONFLICT;
                        var reason =
                            complete ? "GENERATED_CHUNK_RECEIPTS" : "GENERATION_RECEIPT_CONFLICT";
                        var live = new AtomicBoolean(!conflict || state == State.CONFLICT);
                        var pending = services.branches().observeBranch(view, state, reason, live);
                        pending
                            .lookup()
                            .whenComplete(
                                (prior, lookupError) -> {
                                  if (lookupError == null && prior.isEmpty()) pending.submit();
                                });
                      });
            });
  }

  private static void markInterrupted(
      ServerServices services, Branches.View view, BranchPlacement.Attempt attempt) {
    if (!Set.of("RESERVED", "PLACING").contains(attempt.phase())) return;
    var pending =
        services
            .branches()
            .placement(
                BranchPlacement.Action.FAIL,
                view,
                attempt.number(),
                attempt.operation(),
                null,
                "INTERRUPTED_PLACEMENT",
                BranchPlans.owner(view.id()),
                new AtomicBoolean(true));
    pending
        .lookup()
        .whenComplete(
            (prior, error) -> {
              if (error == null && prior.isEmpty()) pending.submit();
            });
  }

  /**
   * Explicit operator terrain retry. Only AIR writes are allowed; no chunk loading or
   * captured-choice reroll.
   */
  public static CompletionStage<String> retry(
      MinecraftServer server, Branches.View view, ActorId actor, AtomicBoolean permission) {
    var session = SESSIONS.computeIfAbsent(server, s -> new Session());
    var services = Economy.serverServices(server);
    if (services == null
        || services.state() != ServerServices.State.READY
        || !permission.get()
        || !view.state().equals("SITE_PENDING"))
      return CompletableFuture.completedStage("PLACEMENT_RETRY_STATE");
    if (!session.resourcesReady)
      return CompletableFuture.completedStage("PLACEMENT_RESOURCES_UNSUPPORTED");
    if (session.retries.size() >= 64 || session.retries.containsKey(view.id()))
      return CompletableFuture.completedStage("PLACEMENT_BUSY");
    var level =
        server.getLevel(
            net.minecraft.resources.ResourceKey.create(
                Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(view.plan().source().dimension())));
    if (level == null) return CompletableFuture.completedStage("PLACEMENT_DIMENSION_UNAVAILABLE");
    var candidate = VillageSites.searchLoaded(level, view.plan(), view.choice());
    if (candidate.isEmpty()) {
      var pending =
          services
              .branches()
              .placement(
                  BranchPlacement.Action.PENDING,
                  view,
                  0,
                  UUID.randomUUID(),
                  null,
                  "NO_SAFE_LOADED_SITE",
                  actor,
                  permission);
      return pending
          .submit()
          .completion()
          .thenApply(d -> d.outcome().result().code() + " / NO_SAFE_LOADED_SITE");
    }
    var parsed = new ArrayList<BlockState>();
    try {
      for (var cell : candidate.get().cells())
        parsed.add(
            BlockStateParser.parseForBlock(
                    level.holderLookup(Registries.BLOCK), cell.blockState(), true)
                .blockState());
    } catch (Exception malformed) {
      return CompletableFuture.completedStage("PLACEMENT_TEMPLATE_INVALID");
    }
    var work =
        new RetryWork(level, view.id(), actor, permission, candidate.get(), List.copyOf(parsed));
    session.retries.put(view.id(), work);
    if (!clearBefore(work)) {
      endRetry(session, work, "PLACEMENT_CHANGED");
      return work.result;
    }
    services
        .branches()
        .placementAttempt(view.id())
        .whenComplete(
            (prior, error) -> {
              if (error != null) {
                endRetry(session, work, "PLACEMENT_UNAVAILABLE");
                return;
              }
              work.attempt = prior.map(a -> a.number() + 1).orElse(1L);
              work.pending =
                  services
                      .branches()
                      .placement(
                          BranchPlacement.Action.RESERVE,
                          view,
                          work.attempt,
                          work.operation,
                          work.candidate.choice(),
                          "SITE_RESERVED",
                          actor,
                          permission);
              work.continuation =
                  outcome -> {
                    if (outcome.status() != Journal.Status.COMMITTED) {
                      endRetry(session, work, outcome.result().code());
                      return;
                    }
                    transitionRetry(
                        server,
                        session,
                        work,
                        BranchPlacement.Action.START,
                        "PLACEMENT_STARTED",
                        () -> work.started = true);
                  };
              submitRetry(session, work);
            });
    return work.result;
  }

  private static boolean loaded(ServerLevel level, Point point) {
    return level
            .getChunkSource()
            .getChunkNow(Math.floorDiv(point.x(), 16), Math.floorDiv(point.z(), 16))
        != null;
  }

  private static BlockPos pos(Point p) {
    return new BlockPos(p.x(), p.y(), p.z());
  }

  private static boolean supportsIntact(RetryWork work) {
    for (var support : work.candidate.supports())
      if (!loaded(work.level, support)
          || !work.level
              .getBlockState(pos(support))
              .isFaceSturdy(work.level, pos(support), Direction.UP)) return false;
    return true;
  }

  private static boolean clearBefore(RetryWork work) {
    return work.permission.get() && unchanged(work);
  }

  private static boolean unchanged(RetryWork work) {
    if (!supportsIntact(work)) return false;
    for (int i = 0; i < work.candidate.cells().size(); i++) {
      var cell = work.candidate.cells().get(i);
      var point = pos(cell.position());
      if (!loaded(work.level, cell.position())
          || !work.level.isInWorldBounds(point)
          || !work.level.getWorldBorder().isWithinBounds(point)) return false;
      var current = work.level.getBlockState(point);
      if (i < work.cursor) {
        if (!current.equals(work.states.get(i))) return false;
      } else if (!current.isAir()
          || !work.states.get(i).isAir()
              && !work.level.isUnobstructed(
                  work.states.get(i),
                  point,
                  net.minecraft.world.phys.shapes.CollisionContext.empty())) return false;
    }
    return true;
  }

  private static void applyRetry(MinecraftServer server, Session session, RetryWork work) {
    if (!work.permission.get() || !unchanged(work)) {
      conflictRetry(server, session, work, "PLACEMENT_CHANGED");
      return;
    }
    int budget = 384;
    while (work.cursor < work.candidate.cells().size() && budget-- > 0) {
      var cell = work.candidate.cells().get(work.cursor);
      var position = pos(cell.position());
      var state = work.states.get(work.cursor);
      if (!loaded(work.level, cell.position()) || !work.level.getBlockState(position).isAir()) {
        conflictRetry(server, session, work, "PLACEMENT_CHANGED");
        return;
      }
      if (!state.isAir()) {
        if (!work.level.setBlock(
            position, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) {
          conflictRetry(server, session, work, "PLACEMENT_WRITE_FAILED");
          return;
        }
        var blockEntity = work.level.getBlockEntity(position);
        try {
          if (blockEntity != null && cell.blockEntitySnbt() != null)
            blockEntity.loadWithComponents(
                TagParser.parseTag(cell.blockEntitySnbt()), work.level.registryAccess());
          if (blockEntity instanceof TerminalBlockEntity terminal) {
            var anchor =
                work.candidate.choice().services().stream()
                    .filter(a -> a.position().equals(cell.position()))
                    .findFirst()
                    .orElse(null);
            if (anchor != null)
              terminal.generatedPlacement(work.branch, anchor.generation(), work.operation);
          }
          if (blockEntity
              instanceof ca.emilybelnavis.mcmods.economy.banker.desk.DeskBlockEntity desk) {
            var anchor =
                work.candidate.choice().desks().stream()
                    .filter(a -> a.position().equals(cell.position()))
                    .findFirst()
                    .orElseThrow();
            desk.generatedPlacement(work.branch, anchor.generation(), work.operation);
          }
        } catch (Exception malformed) {
          conflictRetry(server, session, work, "PLACEMENT_BLOCK_ENTITY_INVALID");
          return;
        }
      }
      work.cursor++;
    }
    if (work.cursor == work.candidate.cells().size())
      transitionRetry(
          server,
          session,
          work,
          BranchPlacement.Action.FINISH,
          "PLACEMENT_COMPLETE",
          () -> {
            if (SESSIONS.get(server) == session)
              for (var anchor : work.candidate.choice().services())
                if (work.level.getBlockEntity(pos(anchor.position()))
                    instanceof TerminalBlockEntity terminal)
                  BranchBootstrap.loaded(work.level, pos(anchor.position()), terminal);
            endRetry(session, work, "BRANCH_PHYSICAL_COMPLETE");
          });
  }

  private static void conflictRetry(
      MinecraftServer server, Session session, RetryWork work, String reason) {
    transitionRetry(
        server,
        session,
        work,
        BranchPlacement.Action.FAIL,
        reason,
        () -> endRetry(session, work, "BRANCH_CONFLICT / " + reason));
  }

  private static void transitionRetry(
      MinecraftServer server,
      Session session,
      RetryWork work,
      BranchPlacement.Action action,
      String reason,
      Runnable after) {
    var services = Economy.serverServices(server);
    work.busy = true;
    services
        .branches()
        .find(work.branch)
        .whenComplete(
            (found, error) -> {
              if (error != null || found.isEmpty()) {
                work.busy = false;
                work.next = session.tick + 100;
                return;
              }
              work.physical.set(
                  action == BranchPlacement.Action.FAIL
                      || work.permission.get() && unchanged(work));
              work.pending =
                  services
                      .branches()
                      .placement(
                          action,
                          found.get(),
                          work.attempt,
                          work.operation,
                          null,
                          reason,
                          BranchPlans.owner(work.branch),
                          work.physical);
              work.continuation =
                  outcome -> {
                    if (outcome.status() == Journal.Status.COMMITTED) after.run();
                    else endRetry(session, work, outcome.result().code());
                  };
              work.busy = false;
              submitRetry(session, work);
            });
  }

  private static void submitRetry(Session session, RetryWork work) {
    work.busy = true;
    var pending = work.pending;
    pending
        .lookup()
        .whenComplete(
            (prior, error) -> {
              if (error != null) {
                retryWork(session, work);
                return;
              }
              if (prior.isPresent()) {
                resolvedRetry(session, work, prior.get());
                return;
              }
              pending
                  .submit()
                  .completion()
                  .whenComplete(
                      (delivery, failure) -> {
                        if (failure != null) retryWork(session, work);
                        else resolvedRetry(session, work, delivery.outcome());
                      });
            });
  }

  private static void resolvedRetry(Session session, RetryWork work, Journal.Outcome outcome) {
    work.busy = false;
    if (outcome.status() == Journal.Status.UNKNOWN
        || outcome.status() == Journal.Status.NOT_ACCEPTED) {
      retryWork(session, work);
      return;
    }
    var continuation = work.continuation;
    work.pending = null;
    work.continuation = null;
    continuation.accept(outcome);
  }

  private static void retryWork(Session session, RetryWork work) {
    work.busy = false;
    work.next = session.tick + 100;
  }

  private static void endRetry(Session session, RetryWork work, String outcome) {
    session.retries.remove(work.branch);
    work.physical.set(false);
    work.result.complete(outcome);
  }

  private static void submitSource(Session session, SourceWork work) {
    work.pending
        .lookup()
        .whenComplete(
            (prior, error) -> {
              if (error != null) {
                retrySource(session, work);
                return;
              }
              if (prior.isPresent()) {
                work.continuation.accept(prior.get());
                return;
              }
              work.pending
                  .submit()
                  .completion()
                  .whenComplete(
                      (delivery, failure) -> {
                        if (failure != null) retrySource(session, work);
                        else work.continuation.accept(delivery.outcome());
                      });
            });
  }

  private static void retrySource(Session session, SourceWork work) {
    work.busy = false;
    work.next = session.tick + 100;
  }

  public static synchronized void stopped(MinecraftServer server) {
    var session = SESSIONS.remove(server);
    if (session != null)
      for (var retry : session.retries.values()) {
        retry.permission.set(false);
        retry.physical.set(false);
        retry.result.complete("PLACEMENT_SERVER_STOPPED");
      }
    var copy = new IdentityHashMap<ChunkGenerator, Publication>();
    copy.putAll(published);
    for (var level : server.getAllLevels()) copy.remove(level.getChunkSource().getGenerator());
    published = Collections.unmodifiableMap(copy);
  }
}
