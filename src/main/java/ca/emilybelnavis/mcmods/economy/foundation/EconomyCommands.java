package ca.emilybelnavis.mcmods.economy.foundation;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.List;
import java.util.function.BiFunction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.rcon.RconConsoleSource;

/** Commands read detached server-thread state. Output goes only to the requester. */
public final class EconomyCommands {
  private EconomyCommands() {}

  public record View(boolean privileged, boolean detailed, String calendarAfter) {
    public View(boolean privileged, boolean detailed) {
      this(privileged, detailed, null);
    }
  }

  public static void register(
      CommandDispatcher<CommandSourceStack> dispatcher,
      BiFunction<CommandSourceStack, View, List<String>> report) {
    dispatcher.register(
        Commands.literal("economy")
            .then(
                Commands.literal("status")
                    .executes(
                        context ->
                            send(
                                context.getSource(),
                                report,
                                new View(privileged(context.getSource()), false))))
            .then(
                Commands.literal("diagnostics")
                    .requires(EconomyCommands::privileged)
                    .executes(context -> send(context.getSource(), report, new View(true, true)))
                    .then(
                        Commands.literal("calendar")
                            .executes(
                                context ->
                                    send(context.getSource(), report, new View(true, true, "")))
                            .then(
                                Commands.literal("after")
                                    .then(
                                        Commands.argument(
                                                "cursor", StringArgumentType.greedyString())
                                            .executes(
                                                context ->
                                                    send(
                                                        context.getSource(),
                                                        report,
                                                        new View(
                                                            true,
                                                            true,
                                                            StringArgumentType.getString(
                                                                context, "cursor")))))))));
  }

  static boolean privileged(CommandSourceStack source) {
    // /execute as can change the entity but retains the original source.
    return source.hasPermission(2)
        && (source.source == source.getServer()
            || source.source instanceof RconConsoleSource
            || source.getPlayer() != null && source.source == source.getPlayer());
  }

  private static int send(
      CommandSourceStack source,
      BiFunction<CommandSourceStack, View, List<String>> report,
      View view) {
    for (String line : report.apply(source, view)) {
      source.sendSuccess(() -> Component.literal(line), false);
    }
    return 1;
  }
}
