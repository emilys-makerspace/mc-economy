package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.debt.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import java.util.*;
import java.util.function.Function;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Private owner pages and separately authorized, audited operator pages. */
public final class DebtCommands {
  private DebtCommands() {}

  public static void register(
      CommandDispatcher<CommandSourceStack> dispatcher,
      Function<MinecraftServer, ServerServices> sessions) {
    var root =
        Commands.literal("debt")
            .requires(DebtCommands::authenticated)
            .then(
                Commands.literal("cases")
                    .executes(c -> cases(c, sessions, null))
                    .then(
                        Commands.argument("after", UuidArgument.uuid())
                            .executes(c -> cases(c, sessions, UuidArgument.getUuid(c, "after")))))
            .then(
                Commands.literal("reports")
                    .executes(c -> reports(c, sessions, DebtLifecycle.ReportCursor.start()))
                    .then(
                        Commands.literal("after")
                            .then(
                                Commands.argument("effective", LongArgumentType.longArg(0))
                                    .then(
                                        Commands.argument("report", UuidArgument.uuid())
                                            .then(
                                                Commands.argument(
                                                        "revision", LongArgumentType.longArg(0))
                                                    .executes(
                                                        c ->
                                                            reports(
                                                                c,
                                                                sessions,
                                                                new DebtLifecycle.ReportCursor(
                                                                    LongArgumentType.getLong(
                                                                        c, "effective"),
                                                                    UuidArgument.getUuid(
                                                                            c, "report")
                                                                        .toString(),
                                                                    LongArgumentType.getLong(
                                                                        c, "revision")))))))))
            .then(
                Commands.literal("history")
                    .then(
                        Commands.argument("case", UuidArgument.uuid())
                            .executes(c -> events(c, sessions, DebtDiagnostics.EventCursor.start()))
                            .then(
                                Commands.literal("after")
                                    .then(
                                        Commands.argument("sequence", LongArgumentType.longArg(1))
                                            .then(
                                                Commands.argument(
                                                        "position",
                                                        IntegerArgumentType.integer(-1, 63))
                                                    .executes(
                                                        c ->
                                                            events(
                                                                c,
                                                                sessions,
                                                                new DebtDiagnostics.EventCursor(
                                                                    LongArgumentType.getLong(
                                                                        c, "sequence"),
                                                                    IntegerArgumentType.getInteger(
                                                                        c, "position")))))))));
    var inspect =
        Commands.literal("inspect")
            .requires(s -> authenticated(s) && EconomyCommands.privileged(s));
    for (var kind : DebtRecords.Kind.values())
      inspect.then(
          Commands.literal(kind.name().toLowerCase(Locale.ROOT))
              .then(
                  Commands.argument("owner", UuidArgument.uuid())
                      .then(
                          Commands.argument("facility", UuidArgument.uuid())
                              .executes(c -> inspect(c, sessions, kind, null))
                              .then(
                                  Commands.argument("after", UuidArgument.uuid())
                                      .executes(
                                          c ->
                                              inspect(
                                                  c,
                                                  sessions,
                                                  kind,
                                                  UuidArgument.getUuid(c, "after")))))));
    var payments = Commands.literal("payments");
    for (var kind : DebtRecords.Kind.values())
      payments.then(
          Commands.literal(kind.name().toLowerCase(Locale.ROOT))
              .then(
                  Commands.argument("facility", UuidArgument.uuid())
                      .executes(c -> payments(c, sessions, kind, 0, -1))
                      .then(
                          Commands.literal("after")
                              .then(
                                  Commands.argument("sequence", LongArgumentType.longArg(1))
                                      .then(
                                          Commands.argument(
                                                  "position", IntegerArgumentType.integer(-1, 63))
                                              .executes(
                                                  c ->
                                                      payments(
                                                          c,
                                                          sessions,
                                                          kind,
                                                          LongArgumentType.getLong(c, "sequence"),
                                                          IntegerArgumentType.getInteger(
                                                              c, "position"))))))));
    var inspectReports =
        Commands.literal("inspect_reports")
            .requires(s -> authenticated(s) && EconomyCommands.privileged(s))
            .then(
                Commands.argument("owner", UuidArgument.uuid())
                    .executes(c -> reports(c, sessions, DebtLifecycle.ReportCursor.start(), true))
                    .then(
                        Commands.literal("after")
                            .then(
                                Commands.argument("effective", LongArgumentType.longArg(0))
                                    .then(
                                        Commands.argument("report", UuidArgument.uuid())
                                            .then(
                                                Commands.argument(
                                                        "revision", LongArgumentType.longArg(0))
                                                    .executes(
                                                        c ->
                                                            reports(
                                                                c,
                                                                sessions,
                                                                new DebtLifecycle.ReportCursor(
                                                                    LongArgumentType.getLong(
                                                                        c, "effective"),
                                                                    UuidArgument.getUuid(
                                                                            c, "report")
                                                                        .toString(),
                                                                    LongArgumentType.getLong(
                                                                        c, "revision")),
                                                                true)))))));
    var inspectHistory =
        Commands.literal("inspect_history")
            .requires(s -> authenticated(s) && EconomyCommands.privileged(s))
            .then(
                Commands.argument("owner", UuidArgument.uuid())
                    .then(
                        Commands.argument("case", UuidArgument.uuid())
                            .executes(
                                c -> events(c, sessions, DebtDiagnostics.EventCursor.start(), true))
                            .then(
                                Commands.literal("after")
                                    .then(
                                        Commands.argument("sequence", LongArgumentType.longArg(1))
                                            .then(
                                                Commands.argument(
                                                        "position",
                                                        IntegerArgumentType.integer(-1, 63))
                                                    .executes(
                                                        c ->
                                                            events(
                                                                c,
                                                                sessions,
                                                                new DebtDiagnostics.EventCursor(
                                                                    LongArgumentType.getLong(
                                                                        c, "sequence"),
                                                                    IntegerArgumentType.getInteger(
                                                                        c, "position")),
                                                                true)))))));
    dispatcher.register(
        Commands.literal("economy")
            .then(root.then(inspect).then(payments).then(inspectReports).then(inspectHistory)));
  }

  private static boolean authenticated(CommandSourceStack source) {
    return source.getPlayer() != null && source.source == source.getPlayer();
  }

  private record Access(
      ServerServices services, ServerPlayer player, PartyAuthorization.Grant grant) {}

  private static Access access(
      CommandSourceStack source,
      Function<MinecraftServer, ServerServices> sessions,
      UUID target,
      boolean inspect) {
    if (!authenticated(source)) return null;
    var services = sessions.apply(source.getServer());
    if (services == null || services.partyWorld() == null) return null;
    var player = source.getPlayer();
    var owner =
        new PartyRef(
            services.partyWorld(),
            new PartyRef.Player(new PlayerId(target == null ? player.getUUID() : target)));
    var grant =
        inspect
            ? PartyAuthorization.inspection(source, sessions, owner)
            : PartyAuthorization.player(player, sessions, owner);
    return grant.map(g -> new Access(services, player, g)).orElse(null);
  }

  private static boolean live(
      CommandSourceStack source,
      Function<MinecraftServer, ServerServices> sessions,
      Access a,
      boolean inspect) {
    return sessions.apply(source.getServer()) == a.services()
        && source.getServer().getPlayerList().getPlayer(a.player().getUUID()) == a.player()
        && (!inspect
            || PartyAuthorization.inspection(source, sessions, a.grant().target()).isPresent());
  }

  private static int cases(
      CommandContext<CommandSourceStack> c,
      Function<MinecraftServer, ServerServices> sessions,
      UUID after) {
    var source = c.getSource();
    var a = access(source, sessions, null, false);
    if (a == null) return unavailable(source);
    a.services()
        .ledger()
        .debtCases(a.grant(), after, 4)
        .whenComplete(
            (found, error) -> {
              if (!live(source, sessions, a, false)) return;
              if (error != null || found.isEmpty()) {
                unavailable(source);
                return;
              }
              DebtReport.cases(found.orElseThrow()).forEach(line -> send(source, line));
            });
    return 1;
  }

  private static int reports(
      CommandContext<CommandSourceStack> c,
      Function<MinecraftServer, ServerServices> sessions,
      DebtLifecycle.ReportCursor cursor) {
    return reports(c, sessions, cursor, false);
  }

  private static int reports(
      CommandContext<CommandSourceStack> c,
      Function<MinecraftServer, ServerServices> sessions,
      DebtLifecycle.ReportCursor cursor,
      boolean inspect) {
    var source = c.getSource();
    var a = access(source, sessions, inspect ? UuidArgument.getUuid(c, "owner") : null, inspect);
    if (a == null) return unavailable(source);
    var result =
        inspect
            ? a.services()
                .ledger()
                .inspectDebtReports(a.grant(), cursor, 4, () -> live(source, sessions, a, true))
            : a.services().ledger().debtReportPage(a.grant(), cursor, 4);
    result.whenComplete(
        (found, error) -> {
          if (!live(source, sessions, a, inspect)) return;
          if (error != null || found.isEmpty()) {
            unavailable(source);
            return;
          }
          DebtReport.reports(found.orElseThrow(), inspect ? UuidArgument.getUuid(c, "owner") : null)
              .forEach(line -> send(source, line));
        });
    return 1;
  }

  private static int events(
      CommandContext<CommandSourceStack> c,
      Function<MinecraftServer, ServerServices> sessions,
      DebtDiagnostics.EventCursor cursor) {
    return events(c, sessions, cursor, false);
  }

  private static int events(
      CommandContext<CommandSourceStack> c,
      Function<MinecraftServer, ServerServices> sessions,
      DebtDiagnostics.EventCursor cursor,
      boolean inspect) {
    var source = c.getSource();
    var a = access(source, sessions, inspect ? UuidArgument.getUuid(c, "owner") : null, inspect);
    if (a == null) return unavailable(source);
    var id = UuidArgument.getUuid(c, "case");
    var result =
        inspect
            ? a.services()
                .ledger()
                .inspectDebtEvents(a.grant(), id, cursor, 4, () -> live(source, sessions, a, true))
            : a.services().ledger().debtEvents(a.grant(), id, cursor, 4);
    result.whenComplete(
        (found, error) -> {
          if (!live(source, sessions, a, inspect)) return;
          if (error != null || found.isEmpty()) {
            unavailable(source);
            return;
          }
          DebtReport.events(
                  id, found.orElseThrow(), inspect ? UuidArgument.getUuid(c, "owner") : null)
              .forEach(line -> send(source, line));
        });
    return 1;
  }

  private static int payments(
      CommandContext<CommandSourceStack> c,
      Function<MinecraftServer, ServerServices> sessions,
      DebtRecords.Kind kind,
      long sequence,
      int position) {
    var source = c.getSource();
    var a = access(source, sessions, null, false);
    if (a == null) return unavailable(source);
    var id = UuidArgument.getUuid(c, "facility");
    if (kind == DebtRecords.Kind.CARD) {
      a.services()
          .ledger()
          .cardPaymentHistory(
              a.grant(),
              id,
              new ca.emilybelnavis.mcmods.economy.credit.CreditDiagnostics.EventCursor(
                  sequence, position),
              4)
          .whenComplete(
              (found, error) -> {
                if (!live(source, sessions, a, false)) return;
                if (error != null || found.isEmpty()) {
                  unavailable(source);
                  return;
                }
                DebtReport.cardPayments(id, found.orElseThrow())
                    .forEach(line -> send(source, line));
              });
    } else {
      a.services()
          .ledger()
          .loanPaymentHistory(
              a.grant(),
              id,
              new ca.emilybelnavis.mcmods.economy.loan.LoanDiagnostics.EventCursor(
                  sequence, position),
              4)
          .whenComplete(
              (found, error) -> {
                if (!live(source, sessions, a, false)) return;
                if (error != null || found.isEmpty()) {
                  unavailable(source);
                  return;
                }
                DebtReport.loanPayments(id, found.orElseThrow())
                    .forEach(line -> send(source, line));
              });
    }
    return 1;
  }

  private static int inspect(
      CommandContext<CommandSourceStack> c,
      Function<MinecraftServer, ServerServices> sessions,
      DebtRecords.Kind kind,
      UUID after) {
    var source = c.getSource();
    var a = access(source, sessions, UuidArgument.getUuid(c, "owner"), true);
    if (a == null) return unavailable(source);
    a.services()
        .ledger()
        .inspectDebt(
            a.grant(),
            kind,
            UuidArgument.getUuid(c, "facility"),
            after,
            4,
            () -> live(source, sessions, a, true))
        .whenComplete(
            (found, error) -> {
              if (!live(source, sessions, a, true)) return;
              if (error != null || found.isEmpty()) {
                unavailable(source);
                return;
              }
              DebtReport.operator(found.orElseThrow()).forEach(line -> send(source, line));
            });
    return 1;
  }

  private static void send(CommandSourceStack source, String line) {
    source.sendSuccess(() -> UiText.component(source, line), false);
  }

  private static int unavailable(CommandSourceStack source) {
    source.sendFailure(
        UiText.component(
            source, UiText.t("ui.dialogue.debt_diagnostics_unavailable_for_this_select.dc1fd5")));
    return 0;
  }
}
