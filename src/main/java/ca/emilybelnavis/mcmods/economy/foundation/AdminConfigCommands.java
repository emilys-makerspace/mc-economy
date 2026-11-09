package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.banking.BankingController;
import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Operator shortcut to the terminal settings screen; owners also have sneak/empty-hand access. */
public final class AdminConfigCommands {
  private AdminConfigCommands() {}

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
        Commands.literal("economy")
            .then(
                Commands.literal("admin")
                    .requires(EconomyCommands::privileged)
                    .then(Commands.literal("config").executes(context -> open(context.getSource())))
                    .then(
                        Commands.literal("binding")
                            .executes(context -> inspect(context.getSource())))));
  }

  private static int inspect(CommandSourceStack source) {
    var player = source.getPlayer();
    if (player == null
        || source.source != player
        || !(player.pick(6, 0, false) instanceof BlockHitResult hit)
        || hit.getType() != HitResult.Type.BLOCK) {
      source.sendFailure(UiText.component(source, UiText.t("ui.admin.look_at_terminal", 6)));
      return 0;
    }
    var level = player.serverLevel();
    var anchor =
        ca.emilybelnavis.mcmods.economy.terminal.TerminalGeometry.resolveAnchor(
            level, hit.getBlockPos());
    if (!level.hasChunkAt(anchor)
        || !(level.getBlockEntity(anchor)
            instanceof ca.emilybelnavis.mcmods.economy.terminal.TerminalBlockEntity entity)) {
      source.sendFailure(UiText.component(source, UiText.t("ui.admin.look_at_terminal", 6)));
      return 0;
    }
    var services = ca.emilybelnavis.mcmods.economy.Economy.serverServices(player.server);
    if (services == null || services.state() != ServerServices.State.READY) {
      source.sendFailure(UiText.component(source, UiText.t("ui.admin.inspect.unavailable")));
      return 0;
    }
    var generation = entity.generation();
    var key =
        new ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy.Anchor(
            level.dimension().location().toString(), anchor.getX(), anchor.getY(), anchor.getZ());
    services
        .ledger()
        .terminalInspection(
            new ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId(player.getUUID()),
            (actor, reads) -> true,
            key)
        .whenComplete(
            (found, error) -> {
              if (player.server.getPlayerList().getPlayer(player.getUUID()) != player
                  || !player.hasPermissions(2)
                  || ca.emilybelnavis.mcmods.economy.Economy.serverServices(player.server)
                      != services) return;
              if (error != null || found.isEmpty()) {
                source.sendFailure(
                    UiText.component(source, UiText.t("ui.admin.inspect.unavailable")));
                return;
              }
              var report = found.orElseThrow();
              var terminal = report.terminal();
              var binding = terminal.binding();
              boolean geometry =
                  ca.emilybelnavis.mcmods.economy.terminal.TerminalGeometry.matches(
                      level, anchor, generation);
              for (var line : TerminalSettingsReview.scope(terminal))
                source.sendSuccess(() -> UiText.component(source, line), false);
              source.sendSuccess(
                  () ->
                      UiText.component(
                          source,
                          UiText.t(
                              "ui.admin.inspect.geometry",
                              UiText.t(
                                  geometry
                                      ? "ui.admin.inspect.complete"
                                      : "ui.admin.inspect.incomplete"))),
                  false);
              source.sendSuccess(
                  () ->
                      UiText.component(
                          source, UiText.t("ui.admin.terminal.placement", terminal.generation())),
                  false);
              boolean bound = binding != null && binding.bound();
              source.sendSuccess(
                  () ->
                      UiText.component(
                          source,
                          UiText.t(
                              "ui.admin.inspect.binding",
                              bound
                                  ? InstitutionCatalog.require(binding.institution()).name()
                                  : UiText.t("ui.admin.binding.unbound"),
                              bound ? binding.nativeCurrency().code() : "—",
                              BankingAccounts.deliveryLabel(terminal.settings().mode()),
                              UiText.t(
                                  "ui.admin.binding."
                                      + (bound
                                          ? binding
                                              .migrationState()
                                              .name()
                                              .toLowerCase(java.util.Locale.ROOT)
                                          : "unbound")),
                              binding == null ? 0 : binding.catalogRevision())),
                  false);
              source.sendSuccess(
                  () ->
                      UiText.component(
                          source,
                          UiText.t(
                              report.pendingCash()
                                  ? "ui.admin.inspect.pending"
                                  : "ui.admin.inspect.clear")),
                  false);
            });
    return 1;
  }

  private static int open(CommandSourceStack source) {
    var player = source.getPlayer();
    if (player == null
        || source.source != player
        || !(player.pick(5, 0, false) instanceof BlockHitResult hit)
        || hit.getType() != HitResult.Type.BLOCK
        || !BankingController.openAdmin(player, hit.getBlockPos())) {
      source.sendFailure(UiText.component(source, UiText.t("ui.admin.look_at_terminal", 5)));
      return 0;
    }
    return 1;
  }
}
