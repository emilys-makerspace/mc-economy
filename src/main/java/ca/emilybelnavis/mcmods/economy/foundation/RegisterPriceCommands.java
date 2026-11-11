package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.cash.RegisterInventory;
import ca.emilybelnavis.mcmods.economy.journal.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.BlockHitResult;

/** The price command and future menu submit the same revisioned owner policy request. */
public final class RegisterPriceCommands {
  private RegisterPriceCommands() {}

  public static void register(
      CommandDispatcher<CommandSourceStack> dispatcher,
      Function<MinecraftServer, ServerServices> sessions) {
    dispatcher.register(
        Commands.literal("economy")
            .then(
                Commands.literal("shop")
                    .then(
                        Commands.literal("price")
                            .requires(
                                source ->
                                    source.getPlayer() != null
                                        && source.source == source.getPlayer())
                            .then(
                                Commands.argument("units", LongArgumentType.longArg(1))
                                    .executes(
                                        context ->
                                            configure(
                                                context.getSource(),
                                                LongArgumentType.getLong(context, "units"),
                                                sessions))))));
  }

  private static int configure(
      CommandSourceStack source, long units, Function<MinecraftServer, ServerServices> sessions) {
    var player = source.getPlayer();
    var services = sessions.apply(source.getServer());
    if (player == null || services == null || services.state() != ServerServices.State.READY)
      return 0;
    if (!(player.pick(5, 0, false) instanceof BlockHitResult hit)
        || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
        || player.getMainHandItem().isEmpty()) {
      source.sendFailure(
          Component.literal("Look at your register while holding the product batch."));
      return 0;
    }
    var position = hit.getBlockPos().immutable();
    var dimension = player.serverLevel().dimension().location().toString();
    var anchor =
        new TerminalPolicy.Anchor(dimension, position.getX(), position.getY(), position.getZ());
    if (!(player.serverLevel().getBlockState(position).getBlock()
            instanceof ca.emilybelnavis.mcmods.economy.terminal.RegisterBlock)
        || !(player.serverLevel().getBlockEntity(position)
            instanceof ca.emilybelnavis.mcmods.economy.terminal.TerminalBlockEntity physical)
        || !physical.policyReady()) {
      source.sendFailure(Component.literal("A ready register is required."));
      return 0;
    }
    var physicalGeneration = physical.generation();
    var actor = new PlayerId(player.getUUID());
    var product = player.getMainHandItem().copy();
    boolean operator = source.hasPermission(2);
    services
        .ledger()
        .registerPriceContext(actor, anchor, (who, reads) -> operator)
        .whenComplete(
            (context, error) -> {
              if (source.getServer().getPlayerList().getPlayer(player.getUUID()) != player) return;
              if (error != null || context.isEmpty()) {
                source.sendFailure(
                    Component.literal(
                        "An owned register and an open seller primary account are required."));
                return;
              }
              if (!player.serverLevel().dimension().location().toString().equals(dimension)
                  || player
                          .position()
                          .distanceToSqr(
                              position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5)
                      > 36) {
                source.sendFailure(Component.literal("Register interaction expired."));
                return;
              }
              if (!ca.emilybelnavis.mcmods.economy.terminal.TerminalLifecycle.matches(
                      player.serverLevel(), position, physicalGeneration)
                  || !context.orElseThrow().terminal().generation().equals(physicalGeneration)) {
                source.sendFailure(Component.literal("Register placement changed."));
                return;
              }
              try {
                RegisterInventory.initializeSource(source.getServer(), dimension, position);
                var stock =
                    RegisterInventory.snapshot(
                        source.getServer(), dimension, position, product, product.getCount());
                var terminal = context.orElseThrow().terminal();
                var price = context.orElseThrow().price();
                long revision =
                    price != null && price.generation().equals(terminal.generation())
                        ? price.revision()
                        : 0;
                var amount =
                    new Money(
                        context.orElseThrow().seller().currency(),
                        Math.multiplyExact(
                            units, context.orElseThrow().seller().currency().minorUnitsPerMajor()));
                boolean currentOperator = source.hasPermission(2);
                var action = RegisterSales.Action.CONFIGURE;
                var operation = new OperationId(UUID.randomUUID());
                var request =
                    RegisterSales.configureRequest(
                        anchor,
                        terminal.generation(),
                        terminal.revision(),
                        revision,
                        stock.template(),
                        stock.batch(),
                        amount);
                var journal =
                    services
                        .ledger()
                        .registerSales(
                            action,
                            (who, kind) -> who.equals(actor),
                            (a, intent, current, reads) -> intent.actor().equals(actor),
                            (who, reads) -> currentOperator,
                            (a, intent, current, reads) -> false,
                            (below, template, reads) -> null,
                            (a, intent, current, evidence, reads) -> false,
                            null);
                journal
                    .submit(
                        new Journal.Intent(
                            operation,
                            new RequestId(UUID.randomUUID()),
                            actor,
                            action.kind(),
                            request,
                            null,
                            null))
                    .completion()
                    .thenAccept(
                        result -> {
                          if (source.getServer().getPlayerList().getPlayer(player.getUUID())
                              == player)
                            source.sendSuccess(
                                () ->
                                    Component.literal(
                                        "Shop price "
                                            + result.outcome().result().code()
                                            + ". "
                                            + result.outcome().result().payload()
                                            + " Operation "
                                            + operation.value()),
                                false);
                        });
              } catch (IllegalArgumentException
                  | IllegalStateException
                  | ArithmeticException invalid) {
                source.sendFailure(
                    Component.literal("Register product, source or price is invalid."));
              }
            });
    return 1;
  }
}
