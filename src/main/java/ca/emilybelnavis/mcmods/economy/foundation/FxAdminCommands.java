package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.OperationId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.RequestId;
import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.journal.JournalService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Server-thread admission for authenticated in-game FX policy edits. */
public final class FxAdminCommands {
  private FxAdminCommands() {}

  private record Pending(
      ServerPlayer player,
      ServerServices services,
      Function<MinecraftServer, ServerServices> sessions,
      AtomicBoolean live) {}

  private static final Map<MinecraftServer, List<Pending>> PENDING = new IdentityHashMap<>();

  /** Stable request identity also permits lookup of an already committed original operation. */
  static JournalEngine.AccessPolicy admission(PlayerId actor) {
    Objects.requireNonNull(actor);
    return (requestActor, kind) -> actor.equals(requestActor) && FxService.EDIT.equals(kind);
  }

  /** JournalEngine invokes handlers only after resolving the original outcome. */
  static JournalEngine.Registration guardedEditing(Clock wall, AtomicBoolean live) {
    var actual = FxService.editing(wall);
    return new JournalEngine.Registration(
        actual.canonicalVersion(),
        (intent, reads) ->
            live.get()
                ? actual.handler().prepare(intent, reads)
                : new JournalEngine.Decline(new Journal.Result(1, "ACCESS_DENIED", "")));
  }

  public static void tick(MinecraftServer server) {
    var list = PENDING.get(server);
    if (list != null)
      for (var pending : list) if (!current(server, pending)) pending.live().set(false);
  }

  public static void stopping(MinecraftServer server) {
    var list = PENDING.remove(server);
    if (list != null) list.forEach(pending -> pending.live().set(false));
  }

  private static boolean current(MinecraftServer server, Pending pending) {
    var player = pending.player();
    return pending.sessions().apply(server) == pending.services()
        && pending.services().state() == ServerServices.State.READY
        && player.isAlive()
        && server.getPlayerList().getPlayer(player.getUUID()) == player
        && authorized(player.createCommandSourceStack());
  }

  public static void register(
      CommandDispatcher<CommandSourceStack> dispatcher,
      Function<MinecraftServer, ServerServices> sessions,
      Clock wall) {
    var policy =
        Commands.literal("policy")
            .requires(FxAdminCommands::authorized)
            .then(
                Commands.argument("operation", UuidArgument.uuid())
                    .then(
                        Commands.argument("currency", StringArgumentType.word())
                            .then(
                                Commands.argument("baseline", StringArgumentType.word())
                                    .then(
                                        Commands.argument(
                                                "variation",
                                                IntegerArgumentType.integer(0, 1_000_000))
                                            .then(
                                                Commands.argument(
                                                        "pull",
                                                        IntegerArgumentType.integer(0, 1_000_000))
                                                    .then(
                                                        Commands.argument(
                                                                "bounds",
                                                                IntegerArgumentType.integer(
                                                                    0, 999_999))
                                                            .executes(
                                                                context ->
                                                                    edit(
                                                                        context, sessions,
                                                                        wall))))))));
    dispatcher.register(Commands.literal("economy").then(Commands.literal("fx").then(policy)));
  }

  private static boolean authorized(CommandSourceStack source) {
    var player = source.getPlayer();
    return player != null
        && source.source == player
        && source.getServer().isSameThread()
        && player.isAlive()
        && source.getServer().getPlayerList().getPlayer(player.getUUID()) == player
        && EconomyCommands.privileged(source)
        && EconomyCommands.privileged(player.createCommandSourceStack());
  }

  private static int edit(
      CommandContext<CommandSourceStack> context,
      Function<MinecraftServer, ServerServices> sessions,
      Clock wall) {
    var source = context.getSource();
    if (!authorized(source)) {
      source.sendFailure(UiText.component(source, UiText.t("ui.fx_admin.connected_operator")));
      return 0;
    }
    var server = source.getServer();
    var services = sessions.apply(server);
    if (services == null || services.state() != ServerServices.State.READY) {
      source.sendFailure(UiText.component(source, UiText.t("ui.fx_admin.unavailable")));
      return 0;
    }

    var player = source.getPlayer();
    var actor = new PlayerId(player.getUUID());
    var operation = new OperationId(UuidArgument.getUuid(context, "operation"));
    var fields =
        Map.of(
            "currency",
            StringArgumentType.getString(context, "currency"),
            "baseline",
            StringArgumentType.getString(context, "baseline"),
            "variation",
            Integer.toString(IntegerArgumentType.getInteger(context, "variation")),
            "pull",
            Integer.toString(IntegerArgumentType.getInteger(context, "pull")),
            "bounds",
            Integer.toString(IntegerArgumentType.getInteger(context, "bounds")));
    if (fields.get("baseline").length() > 20 || fields.get("currency").length() != 3) {
      source.sendFailure(UiText.component(source, UiText.t("ui.fx_admin.invalid_input")));
      return 0;
    }
    var intent =
        new Journal.Intent(
            operation,
            new RequestId(UUID.randomUUID()),
            actor,
            FxService.EDIT,
            new Journal.CanonicalRequest(1, fields),
            null,
            null);
    var list = PENDING.computeIfAbsent(server, ignored -> new ArrayList<>());
    if (list.size() >= 16) {
      source.sendFailure(UiText.component(source, UiText.t("ui.fx_admin.queue_full")));
      return 0;
    }
    var live = new AtomicBoolean(true);
    var pending = new Pending(player, services, sessions, live);
    list.add(pending);
    try {
      var journal =
          services.journal(
              admission(actor),
              Map.of(FxService.EDIT, guardedEditing(wall, live)),
              new JournalService.Publisher() {
                public void invalidate(Journal.Outcome committed) {}

                public void publish(Journal.Outcome terminal) {}
              });
      var submission = journal.submit(intent);
      submission
          .completion()
          .whenComplete(
              (delivery, error) ->
                  server.execute(
                      () -> {
                        list.remove(pending);
                        if (!current(server, pending)) live.set(false);
                        if (!live.get()) return;
                        if (error != null
                            || delivery == null
                            || delivery.outcome().status() == Journal.Status.UNKNOWN
                            || delivery.outcome().status() == Journal.Status.NOT_ACCEPTED) {
                          source.sendFailure(
                              UiText.component(
                                  source, UiText.t("ui.fx_admin.retry", operation.value())));
                          return;
                        }
                        var outcome = delivery.outcome();
                        if (outcome.status() == Journal.Status.COMMITTED) {
                          source.sendSuccess(
                              () ->
                                  UiText.component(
                                      source,
                                      UiText.t(
                                          "ui.fx_admin.saved",
                                          outcome.result().payload(),
                                          operation.value())),
                              false);
                        } else if (outcome.result().code().equals("FX_TAKEOVER_CONFIGURED")) {
                          source.sendFailure(
                              UiText.component(
                                  source, UiText.t("ui.fx_admin.takeover", operation.value())));
                        } else {
                          String reason =
                              UiText.render(
                                  UiText.t(reasonKey(outcome.result().code())),
                                  UiText.language(player));
                          source.sendFailure(
                              UiText.component(
                                  source,
                                  UiText.t("ui.fx_admin.rejected", reason, operation.value())));
                        }
                      }));
      if (submission.receipt() == Journal.Receipt.ACCEPTED_PENDING)
        source.sendSuccess(
            () -> UiText.component(source, UiText.t("ui.fx_admin.submitted", operation.value())),
            false);
      return submission.receipt() == Journal.Receipt.ACCEPTED_PENDING ? 1 : 0;
    } catch (RuntimeException unavailable) {
      list.remove(pending);
      live.set(false);
      source.sendFailure(
          UiText.component(source, UiText.t("ui.fx_admin.retry", operation.value())));
      return 0;
    }
  }

  private static String reasonKey(String code) {
    return "ui.fx_admin."
        + switch (code) {
          case "FX_INVALID_CURRENCY" -> "invalid_currency";
          case "FX_INVALID_POLICY" -> "invalid_policy";
          case "FX_UNINITIALIZED" -> "uninitialized";
          case "FX_BOUNDARY_PASSED" -> "boundary_passed";
          case "FX_FIXED_REFERENCE" -> "fixed_reference";
          case "FX_RATE_UNAVAILABLE" -> "rate_unavailable";
          case "ACCESS_DENIED" -> "access_denied";
          default -> "unavailable_reason";
        };
  }
}
