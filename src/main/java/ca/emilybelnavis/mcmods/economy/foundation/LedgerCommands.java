package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.OperationId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.RequestId;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.SameCurrency;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Commands derive player identity from the authenticated source and use the shared financial
 * service.
 */
public final class LedgerCommands {
  private LedgerCommands() {}

  public static void register(
      CommandDispatcher<CommandSourceStack> dispatcher,
      Function<MinecraftServer, ServerServices> sessions) {
    var amount =
        Commands.argument("coins", LongArgumentType.longArg(1))
            .executes(context -> retryPay(context, sessions));
    var revisions =
        Commands.argument("source", UuidArgument.uuid())
            .then(
                Commands.argument("source_revision", LongArgumentType.longArg(1))
                    .then(
                        Commands.argument("destination", UuidArgument.uuid())
                            .then(
                                Commands.argument(
                                        "destination_revision", LongArgumentType.longArg(1))
                                    .then(
                                        Commands.argument("currency", StringArgumentType.word())
                                            .then(amount)))));
    var pay =
        Commands.literal("pay")
            .requires(LedgerCommands::authenticatedPlayer)
            .then(
                Commands.argument("recipient", UuidArgument.uuid())
                    .then(
                        Commands.argument("coins", LongArgumentType.longArg(1))
                            .executes(context -> planPay(context, sessions))));
    var retryPay =
        Commands.literal("retry-pay")
            .requires(LedgerCommands::authenticatedPlayer)
            .then(
                Commands.argument("operation", UuidArgument.uuid())
                    .then(Commands.argument("recipient", UuidArgument.uuid()).then(revisions)));
    var balance =
        Commands.literal("balance")
            .requires(LedgerCommands::authenticatedPlayer)
            .executes(context -> balance(context, sessions));
    var history =
        Commands.literal("history")
            .requires(LedgerCommands::authenticatedPlayer)
            .then(
                Commands.argument("account", UuidArgument.uuid())
                    .executes(context -> history(context, sessions, null))
                    .then(
                        Commands.argument("cursor", StringArgumentType.string())
                            .executes(
                                context ->
                                    history(
                                        context,
                                        sessions,
                                        StringArgumentType.getString(context, "cursor")))));
    var reconcile =
        Commands.literal("reconcile")
            .requires(source -> authenticatedPlayer(source) && EconomyCommands.privileged(source))
            .then(
                Commands.argument("owner", UuidArgument.uuid())
                    .then(
                        Commands.argument("account", UuidArgument.uuid())
                            .executes(context -> reconcile(context, sessions))));
    dispatcher.register(
        Commands.literal("economy")
            .then(
                Commands.literal("ledger")
                    .then(pay)
                    .then(retryPay)
                    .then(balance)
                    .then(history)
                    .then(reconcile)));
  }

  private record PlayerAccess(
      ServerServices services, ServerPlayer player, PartyAuthorization.Grant grant) {
    boolean live(Function<MinecraftServer, ServerServices> sessions, MinecraftServer server) {
      return sessions.apply(server) == services
          && server.getPlayerList().getPlayer(player.getUUID()) == player;
    }
  }

  private static PlayerAccess access(
      CommandSourceStack source, Function<MinecraftServer, ServerServices> sessions) {
    if (!authenticatedPlayer(source)) return null;
    var services = sessions.apply(source.getServer());
    var world = services == null ? null : services.partyWorld();
    if (world == null) return null;
    var player = source.getPlayer();
    var owner = new PartyRef(world, new PartyRef.Player(new PlayerId(player.getUUID())));
    return PartyAuthorization.player(player, sessions, owner)
        .map(grant -> new PlayerAccess(services, player, grant))
        .orElse(null);
  }

  private static int planPay(
      CommandContext<CommandSourceStack> context,
      Function<MinecraftServer, ServerServices> sessions) {
    var source = context.getSource();
    var access = access(source, sessions);
    if (access == null) return failure(source, "Ledger unavailable");
    var recipient = new PlayerId(UuidArgument.getUuid(context, "recipient"));
    long coins = LongArgumentType.getLong(context, "coins");
    access
        .services()
        .ledger()
        .planPayment(access.grant(), recipient, coins)
        .whenComplete(
            (plan, error) -> {
              if (!access.live(sessions, source.getServer())) return;
              if (error != null || plan.isEmpty()) {
                failure(source, "Payment plan unavailable");
                return;
              }
              submitPayment(
                  source,
                  sessions,
                  access,
                  new OperationId(UUID.randomUUID()),
                  plan.orElseThrow(),
                  coins);
            });
    return 1;
  }

  private static int retryPay(
      CommandContext<CommandSourceStack> context,
      Function<MinecraftServer, ServerServices> sessions) {
    var source = context.getSource();
    var access = access(source, sessions);
    if (access == null) return failure(source, "Ledger unavailable");
    SameCurrency.Payment payment;
    try {
      payment = payment(context);
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      return failure(source, "Invalid payment amount or currency");
    }
    return submitPayment(
        source,
        sessions,
        access,
        new OperationId(UuidArgument.getUuid(context, "operation")),
        payment,
        LongArgumentType.getLong(context, "coins"));
  }

  private static int submitPayment(
      CommandSourceStack source,
      Function<MinecraftServer, ServerServices> sessions,
      PlayerAccess access,
      OperationId operation,
      SameCurrency.Payment payment,
      long coins) {
    var submission =
        access
            .services()
            .ledger()
            .submit(access.grant(), operation, new RequestId(UUID.randomUUID()), payment);
    var retry =
        "/economy ledger retry-pay "
            + operation.value()
            + " "
            + payment.recipient().value()
            + " "
            + payment.debit().id().value()
            + " "
            + payment.debit().revision()
            + " "
            + payment.credit().id().value()
            + " "
            + payment.credit().revision()
            + " "
            + payment.amount().currency().code()
            + " "
            + coins;
    if (submission.receipt() == Journal.Receipt.ACCEPTED_PENDING)
      send(source, "Payment submitted. Retry with " + retry);
    submission
        .completion()
        .whenComplete(
            (delivery, error) -> {
              if (!access.live(sessions, source.getServer())) return;
              if (error != null) {
                failure(source, "Payment result unavailable. Retry with " + retry);
                return;
              }
              var outcome = delivery.outcome();
              String result = outcome.result().code() + " [" + operation.value() + "]";
              if (outcome.status() == Journal.Status.COMMITTED) {
                send(source, result + " transaction " + outcome.transactionId().value());
              } else if (outcome.status() == Journal.Status.UNKNOWN
                  || "ABSENT_RETRY_SAME_ID".equals(outcome.result().code())) {
                failure(source, result + ". Retry with " + retry);
              } else {
                failure(source, result);
              }
            });
    return submission.receipt() == Journal.Receipt.ACCEPTED_PENDING ? 1 : 0;
  }

  private static int balance(
      CommandContext<CommandSourceStack> context,
      Function<MinecraftServer, ServerServices> sessions) {
    var source = context.getSource();
    var access = access(source, sessions);
    if (access == null) return failure(source, "Ledger unavailable");
    access
        .services()
        .ledger()
        .primaryFunds(access.grant())
        .whenComplete(
            (view, error) -> {
              if (!access.live(sessions, source.getServer())) return;
              if (error != null || view.isEmpty()) {
                failure(source, "Primary balance unavailable");
                return;
              }
              var primary = view.orElseThrow();
              var funds = primary.funds();
              send(
                  source,
                  "Primary "
                      + primary.account().id().value()
                      + " revision "
                      + primary.account().revision());
              send(
                  source,
                  "Posted "
                      + amount(funds.posted())
                      + ", available "
                      + amount(new Money(funds.currency(), funds.availableMinorUnits()))
                      + ", reserved "
                      + amount(new Money(funds.currency(), funds.reservedMinorUnits())));
            });
    return 1;
  }

  private static int history(
      CommandContext<CommandSourceStack> context,
      Function<MinecraftServer, ServerServices> sessions,
      String cursor) {
    var source = context.getSource();
    var access = access(source, sessions);
    if (access == null) return failure(source, "Ledger unavailable");
    try {
      access
          .services()
          .ledger()
          .history(access.grant(), account(context, "account"), cursor, 10)
          .whenComplete(
              (view, error) -> {
                if (!access.live(sessions, source.getServer())) return;
                if (error != null || view.isEmpty()) {
                  failure(source, "Account history unavailable");
                  return;
                }
                var page = view.orElseThrow();
                if (page.entries().isEmpty()) send(source, "No postings on this page");
                for (var entry : page.entries()) {
                  send(
                      source,
                      entry.sequence().value()
                          + "."
                          + entry.position()
                          + " "
                          + amount(entry.amount())
                          + " denomination revision "
                          + entry.denominationRevision()
                          + " transaction "
                          + entry.transactionId().value());
                }
                if (page.nextCursor() != null) {
                  send(
                      source,
                      "Next page: /economy ledger history "
                          + account(context, "account").value()
                          + " \""
                          + page.nextCursor()
                          + "\"");
                }
              });
    } catch (IllegalArgumentException invalid) {
      return failure(source, "Invalid history cursor");
    }
    return 1;
  }

  private static int reconcile(
      CommandContext<CommandSourceStack> context,
      Function<MinecraftServer, ServerServices> sessions) {
    var source = context.getSource();
    var access = access(source, sessions);
    if (access == null) return failure(source, "Ledger unavailable");
    var owner =
        new PartyRef(
            access.grant().target().world(),
            new PartyRef.Player(new PlayerId(UuidArgument.getUuid(context, "owner"))));
    if (PartyAuthorization.inspection(source, sessions, owner).isEmpty())
      return failure(source, "Access denied");
    access
        .services()
        .ledger()
        .reconcile(source, sessions, owner, account(context, "account"))
        .whenComplete(
            (view, error) -> {
              if (!access.live(sessions, source.getServer())
                  || PartyAuthorization.inspection(source, sessions, owner).isEmpty()) return;
              if (error != null || view.isEmpty()) {
                failure(source, "Reconciliation unavailable");
                return;
              }
              var result = view.orElseThrow();
              if (!result.complete()) {
                send(source, "Reconciliation exceeded the posting limit");
                return;
              }
              String status = result.matches() ? "Reconciled" : "Mismatch";
              send(
                  source,
                  status
                      + ". Posted "
                      + result.posted()
                      + ", journal "
                      + result.journal()
                      + " minor units ("
                      + result.currency().code()
                      + ")");
            });
    return 1;
  }

  private static String amount(Money money) {
    return money.majorUnits().toPlainString() + " " + money.currency().code();
  }

  private static void send(CommandSourceStack source, String message) {
    source.sendSuccess(() -> Component.literal(message), false);
  }

  private static boolean authenticatedPlayer(CommandSourceStack source) {
    return source.getPlayer() != null && source.source == source.getPlayer();
  }

  private static SameCurrency.Payment payment(CommandContext<CommandSourceStack> context) {
    var currency = EconomyCurrency.fromCode(StringArgumentType.getString(context, "currency"));
    long units =
        Math.multiplyExact(
            LongArgumentType.getLong(context, "coins"), currency.minorUnitsPerMajor());
    return new SameCurrency.Payment(
        new PlayerId(context.getSource().getPlayer().getUUID()),
        revision(context, "source"),
        new PlayerId(UuidArgument.getUuid(context, "recipient")),
        revision(context, "destination"),
        new Money(currency, units));
  }

  private static SameCurrency.Revision revision(
      CommandContext<CommandSourceStack> context, String argument) {
    return new SameCurrency.Revision(
        account(context, argument), LongArgumentType.getLong(context, argument + "_revision"));
  }

  private static AccountId account(CommandContext<CommandSourceStack> context, String argument) {
    return new AccountId(UuidArgument.getUuid(context, argument));
  }

  private static int failure(CommandSourceStack source, String message) {
    source.sendFailure(Component.literal(message));
    return 0;
  }
}
