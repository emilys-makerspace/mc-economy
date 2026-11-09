package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Bounded operator inspection and explicit, revisioned branch controls. No physical placement
 * implementation.
 */
public final class BranchCommands {
  private record Lease(CommandSourceStack source, ServerServices services, AtomicBoolean live) {}

  private static final Map<MinecraftServer, List<Lease>> LEASES = new IdentityHashMap<>();

  private BranchCommands() {}

  public static void register(
      CommandDispatcher<CommandSourceStack> dispatcher,
      Function<MinecraftServer, ServerServices> sessions) {
    var status =
        Commands.literal("status")
            .then(
                Commands.argument("id", UuidArgument.uuid())
                    .executes(
                        c ->
                            read(
                                c.getSource(),
                                sessions,
                                UuidArgument.getUuid(c, "id"),
                                "",
                                false)));
    var list =
        Commands.literal("list")
            .executes(c -> read(c.getSource(), sessions, null, "", false))
            .then(
                Commands.literal("after")
                    .then(
                        Commands.argument("cursor", UuidArgument.uuid())
                            .executes(
                                c ->
                                    read(
                                        c.getSource(),
                                        sessions,
                                        null,
                                        UuidArgument.getUuid(c, "cursor").toString(),
                                        false))));
    var plans =
        Commands.literal("plans")
            .executes(c -> read(c.getSource(), sessions, null, "", true))
            .then(
                Commands.literal("after")
                    .then(
                        Commands.argument("cursor", UuidArgument.uuid())
                            .executes(
                                c ->
                                    read(
                                        c.getSource(),
                                        sessions,
                                        null,
                                        UuidArgument.getUuid(c, "cursor").toString(),
                                        true))));
    var staffing =
        Commands.literal("staffing")
            .then(
                Commands.argument("id", UuidArgument.uuid())
                    .then(
                        Commands.literal("pause")
                            .executes(
                                c ->
                                    change(
                                        c.getSource(),
                                        sessions,
                                        UuidArgument.getUuid(c, "id"),
                                        true,
                                        -1)))
                    .then(
                        Commands.literal("resume")
                            .executes(
                                c ->
                                    change(
                                        c.getSource(),
                                        sessions,
                                        UuidArgument.getUuid(c, "id"),
                                        false,
                                        -1))));
    var retry =
        Commands.literal("retry")
            .then(
                Commands.argument("id", UuidArgument.uuid())
                    .then(
                        Commands.argument("revision", LongArgumentType.longArg(1))
                            .executes(
                                c ->
                                    change(
                                        c.getSource(),
                                        sessions,
                                        UuidArgument.getUuid(c, "id"),
                                        null,
                                        LongArgumentType.getLong(c, "revision")))));
    dispatcher.register(
        Commands.literal("economy")
            .then(
                Commands.literal("branch")
                    .requires(EconomyCommands::privileged)
                    .then(status)
                    .then(list)
                    .then(plans)
                    .then(staffing)
                    .then(retry)));
  }

  private static boolean valid(CommandSourceStack source, ServerServices services) {
    if (!EconomyCommands.privileged(source) || services.state() != ServerServices.State.READY)
      return false;
    var player = source.getPlayer();
    return player == null
        || source.getServer().getPlayerList().getPlayer(player.getUUID()) == player;
  }

  private static int read(
      CommandSourceStack source,
      Function<MinecraftServer, ServerServices> sessions,
      UUID id,
      String after,
      boolean plans) {
    var services = sessions.apply(source.getServer());
    if (services == null || !valid(source, services)) return unavailable(source);
    if (id == null) {
      var work = plans ? services.branches().plans(after) : services.branches().page(after);
      work.whenComplete(
          (rows, error) -> {
            if (sessions.apply(source.getServer()) != services || !valid(source, services)) return;
            if (error != null) {
              unavailable(source);
              return;
            }
            for (var row : rows) send(source, row.toString());
            if (rows.isEmpty())
              send(source, "No " + (plans ? "village plans" : "branches") + " on this page.");
            if (rows.size() == 16)
              send(
                  source,
                  "Next: /economy branch "
                      + (plans ? "plans" : "list")
                      + " after "
                      + rows.getLast().get(plans ? "village_id" : "branch_id"));
          });
    } else
      services
          .branches()
          .find(id)
          .whenComplete(
              (found, error) -> {
                if (sessions.apply(source.getServer()) != services || !valid(source, services))
                  return;
                if (error != null || found.isEmpty()) {
                  unavailable(source);
                  return;
                }
                var view = found.get();
                var plan = view.plan();
                var choice = view.choice();
                send(
                    source,
                    view.id()
                        + " / "
                        + view.state()
                        + " / "
                        + view.reason()
                        + " / revision "
                        + view.revision());
                send(
                    source,
                    "Village "
                        + view.village()
                        + " / "
                        + plan.style()
                        + " / buildings "
                        + plan.buildingCount()
                        + " / slot "
                        + view.slot()
                        + " / "
                        + choice.size()
                        + " / "
                        + (choice.required() ? "required" : "optional"));
                send(
                    source,
                    "Bank "
                        + choice.institution()
                        + " / native "
                        + choice.nativeCurrency()
                        + " / captured denomination "
                        + plan.snapshot().denomination()
                        + "@"
                        + plan.snapshot().denominationRevision());
                send(
                    source,
                    "Catalog "
                        + plan.snapshot().catalogRevision()
                        + " / policy "
                        + plan.snapshot().policyRevision()
                        + " / resources "
                        + plan.snapshot().resourceRevision()
                        + " / digest "
                        + plan.digest());
                send(
                    source,
                    "Staffing paused="
                        + view.paused()
                        + "; replacement delay 24,000 eligible server ticks; terrain retry uses loaded safe sites only.");
                services
                    .staffing()
                    .rows(id)
                    .whenComplete(
                        (rows, problem) -> {
                          if (problem != null
                              || sessions.apply(source.getServer()) != services
                              || !valid(source, services)) return;
                          var level =
                              source
                                  .getServer()
                                  .getLevel(
                                      net.minecraft.resources.ResourceKey.create(
                                          net.minecraft.core.registries.Registries.DIMENSION,
                                          net.minecraft.resources.ResourceLocation.parse(
                                              plan.source().dimension())));
                          for (var anchor : choice.desks()) {
                            var p =
                                new net.minecraft.core.BlockPos(
                                    anchor.position().x(),
                                    anchor.position().y(),
                                    anchor.position().z());
                            var row =
                                rows.stream()
                                    .filter(r -> r.slot() == anchor.slot())
                                    .findFirst()
                                    .orElse(null);
                            boolean loaded =
                                level != null
                                    && level
                                            .getChunkSource()
                                            .getChunkNow(p.getX() >> 4, p.getZ() >> 4)
                                        != null;
                            String observed =
                                !loaded
                                    ? "UNKNOWN_UNLOADED"
                                    : level.getBlockEntity(p)
                                                instanceof
                                                ca.emilybelnavis.mcmods.economy.banker.desk
                                                        .DeskBlockEntity
                                                    e
                                            && e.branchId().filter(id::equals).isPresent()
                                            && ca.emilybelnavis.mcmods.economy.banker.desk
                                                .DeskGeometry.matches(level, p, anchor.generation())
                                        ? "VALID"
                                        : row == null
                                            ? "LEGACY_DESK_UNBOUND"
                                            : "ABSENT_OR_REPLACED";
                            String employee =
                                row == null
                                    ? "UNASSIGNED"
                                    : !loaded || level.getEntity(row.employee()) == null
                                        ? "UNKNOWN_UNLOADED_OR_ABSENT"
                                        : "LOADED";
                            send(
                                source,
                                "Desk "
                                    + anchor.slot()
                                    + " / "
                                    + p.toShortString()
                                    + " / "
                                    + observed
                                    + " / "
                                    + (row == null
                                        ? "no staffing assignment"
                                        : row.deskState()
                                            + " / "
                                            + row.phase()
                                            + " / elapsed "
                                            + row.elapsed()
                                            + "/24000 / employee "
                                            + row.employee()
                                            + " / assignment revision "
                                            + row.revision())
                                    + " / "
                                    + employee);
                          }
                        });
                services
                    .branches()
                    .placementAttempt(id)
                    .whenComplete(
                        (attempt, problem) -> {
                          if (problem == null
                              && attempt.isPresent()
                              && sessions.apply(source.getServer()) == services
                              && valid(source, services)) {
                            var value = attempt.get();
                            send(
                                source,
                                "Placement attempt "
                                    + value.number()
                                    + " / "
                                    + value.phase()
                                    + " / "
                                    + value.reason()
                                    + " / original operation "
                                    + value.operation());
                          }
                        });
                for (var a : view.anchors()) {
                  var level =
                      source
                          .getServer()
                          .getLevel(
                              net.minecraft.resources.ResourceKey.create(
                                  net.minecraft.core.registries.Registries.DIMENSION,
                                  net.minecraft.resources.ResourceLocation.parse(
                                      a.get("dimension"))));
                  var pos =
                      new net.minecraft.core.BlockPos(
                          Integer.parseInt(a.get("x")),
                          Integer.parseInt(a.get("y")),
                          Integer.parseInt(a.get("z")));
                  String observed =
                      level == null || !level.hasChunkAt(pos)
                          ? "UNKNOWN_UNLOADED"
                          : level.getBlockEntity(pos)
                                      instanceof
                                      ca.emilybelnavis.mcmods.economy.terminal.TerminalBlockEntity
                                          entity
                                  && entity.generation().toString().equals(a.get("generation"))
                              ? "LOADED"
                              : "ABSENT_OR_REPLACED";
                  send(
                      source,
                      "Service "
                          + a.get("slot")
                          + " / "
                          + a.get("dimension")
                          + " "
                          + pos.toShortString()
                          + " / "
                          + a.get("generation")
                          + " / "
                          + a.get("state")
                          + " / terminal revision "
                          + a.get("terminal_revision")
                          + " / "
                          + observed);
                }
                services
                    .branches()
                    .history(id, Math.max(0, view.revision() - 16))
                    .whenComplete(
                        (rows, failure) -> {
                          if (failure == null
                              && sessions.apply(source.getServer()) == services
                              && valid(source, services))
                            rows.forEach(row -> send(source, "History " + row));
                        });
              });
    return 1;
  }

  private static int change(
      CommandSourceStack source,
      Function<MinecraftServer, ServerServices> sessions,
      UUID id,
      Boolean pause,
      long expected) {
    var services = sessions.apply(source.getServer());
    if (services == null || !valid(source, services)) return unavailable(source);
    var list = LEASES.computeIfAbsent(source.getServer(), s -> new ArrayList<>());
    if (list.size() >= 64) {
      send(source, "Branch command queue is full.");
      return 0;
    }
    var lease = new Lease(source, services, new AtomicBoolean(true));
    list.add(lease);
    services
        .branches()
        .find(id)
        .whenComplete(
            (found, error) -> {
              if (error != null
                  || found.isEmpty()
                  || !lease.live().get()
                  || !valid(source, services)) {
                finish(lease);
                unavailable(source);
                return;
              }
              var view = found.get();
              if (expected != -1 && view.revision() != expected) {
                finish(lease);
                send(source, "BRANCH_STALE; inspect current revision.");
                return;
              }
              ActorId actor =
                  source.getPlayer() == null
                      ? new SystemActorId(new UUID(0, 0))
                      : new PlayerId(source.getPlayer().getUUID());
              if (pause == null && view.state().equals("SITE_PENDING")) {
                ca.emilybelnavis.mcmods.economy.village.GenerationRuntime.retry(
                        source.getServer(), view, actor, lease.live())
                    .whenComplete(
                        (outcome, problem) -> {
                          if (lease.live().get() && valid(source, services))
                            send(
                                source,
                                problem == null
                                    ? outcome
                                    : "Terrain retry unavailable; inspect original operation history.");
                          finish(lease);
                        });
                return;
              }
              if (pause != null) {
                ca.emilybelnavis.mcmods.economy.banker.StaffingRuntime.pause(
                        source.getServer(), id, actor, pause, lease.live())
                    .whenComplete(
                        (outcome, problem) -> {
                          if (lease.live().get() && valid(source, services))
                            send(
                                source,
                                problem == null
                                    ? outcome
                                    : "Staffing control unavailable; inspect branch status.");
                          finish(lease);
                        });
                return;
              }
              var preparation =
                  services
                      .branches()
                      .retryProvider(
                          view, actor, source.getServer().overworld().getSeed(), lease.live());
              preparation.whenComplete(
                  (prepared, failure) -> {
                    if (failure != null
                        || prepared.isEmpty()
                        || !lease.live().get()
                        || !valid(source, services)) {
                      finish(lease);
                      unavailable(source);
                      return;
                    }
                    var pending = prepared.get();
                    send(source, "Branch operation " + pending.intent().operationId().value());
                    pending
                        .submit()
                        .completion()
                        .whenComplete(
                            (delivery, problem) -> {
                              if (lease.live().get() && valid(source, services))
                                send(
                                    source,
                                    problem == null
                                        ? delivery.outcome().status()
                                            + " / "
                                            + delivery.outcome().result().code()
                                        : "Outcome unavailable; retain operation ID.");
                              finish(lease);
                            });
                  });
            });
    return 1;
  }

  private static void finish(Lease lease) {
    lease.live().set(false);
    var list = LEASES.get(lease.source().getServer());
    if (list != null) list.remove(lease);
  }

  public static void tick(MinecraftServer server) {
    var list = LEASES.get(server);
    if (list != null)
      for (var lease : List.copyOf(list))
        if (!valid(lease.source(), lease.services())) finish(lease);
  }

  public static void stopped(MinecraftServer server) {
    var list = LEASES.remove(server);
    if (list != null) list.forEach(l -> l.live().set(false));
  }

  private static int unavailable(CommandSourceStack source) {
    source.sendFailure(Component.literal("Branch information or controls unavailable."));
    return 0;
  }

  private static void send(CommandSourceStack source, String message) {
    source.sendSuccess(() -> Component.literal(message), false);
  }
}
