package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.OperationId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.RequestId;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.corporation.IntegrationBindings;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * One reviewed integration request per connected player, admitted by an explicit player command.
 */
public final class CorporationConsent {
  private static final long REVIEW_LIFETIME = Duration.ofMinutes(2).toNanos();
  private static final long LOOKUP_LIFETIME = Duration.ofMinutes(10).toNanos();
  private static final int MAX_PER_SERVER = 128;
  private static final Map<MinecraftServer, Map<UUID, Review>> REVIEWS =
      Collections.synchronizedMap(new IdentityHashMap<>());

  private CorporationConsent() {}

  private record Review(
      UUID token,
      ServerPlayer player,
      ServerGamePacketListenerImpl connection,
      ServerServices services,
      OperationId operation,
      IntegrationBindings.Command command,
      BooleanSupplier providerCurrent,
      long deadline,
      boolean submitted) {
    boolean live() {
      return player.connection == connection
          && player.server.getPlayerList().getPlayer(player.getUUID()) == player
          && Economy.serverServices(player.server) == services
          && services.state() == ServerServices.State.READY;
    }

    Review submit() {
      return new Review(
          token,
          player,
          connection,
          services,
          operation,
          command,
          providerCurrent,
          System.nanoTime() + LOOKUP_LIFETIME,
          true);
    }
  }

  public static boolean offer(
      ServerPlayer founder,
      ServerServices services,
      UUID operation,
      IntegrationBindings.Command command,
      BooleanSupplier providerCurrent) {
    Objects.requireNonNull(operation);
    Objects.requireNonNull(command);
    Objects.requireNonNull(providerCurrent);
    if (command instanceof IntegrationBindings.Revoke
        || grant(founder, services) == null
        || !providerCurrent.getAsBoolean()) return false;
    var reviews = reviews(founder.server);
    var prior = reviews.get(founder.getUUID());
    if ((prior != null && !prior.submitted())
        || (prior == null && reviews.size() >= MAX_PER_SERVER)) return false;

    var review =
        new Review(
            UUID.randomUUID(),
            founder,
            founder.connection,
            services,
            new OperationId(operation),
            command,
            providerCurrent,
            System.nanoTime() + REVIEW_LIFETIME,
            false);
    reviews.put(founder.getUUID(), review);
    var key = command.key();
    message(founder, "Integration " + key.provider() + " · External key " + key.externalKey());
    switch (command) {
      case IntegrationBindings.Create create -> {
        var registration = create.registration();
        var fee = registration.fee();
        var exactDebit = new Money(fee.currency(), fee.minor()).majorUnits().toPlainString();
        message(founder, "Create company “" + registration.name() + "” with you as founder.");
        message(
            founder,
            "Debit "
                + fee.currency().code()
                + " "
                + exactDebit
                + " from account "
                + fee.account().value()
                + " at revision "
                + fee.revision()
                + ".");
        if (fee.publication() != null)
          message(founder, "CAD 500.00 equivalent using FX publication " + fee.publication() + ".");
      }
      case IntegrationBindings.Bind bind ->
          message(
              founder,
              "Bind this key to company "
                  + bind.company()
                  + " at revision "
                  + bind.companyRevision()
                  + ".");
      case IntegrationBindings.Approve approve -> {
        message(
            founder,
            "Approve "
                + approve.fields().get("capabilities")
                + " for company "
                + approve.company()
                + " at revision "
                + approve.companyRevision()
                + ", replacing mandate revision "
                + approve.mandateRevision()
                + ".");
        if (!approve.accounts().isEmpty())
          message(founder, "Selected accounts " + approve.fields().get("accounts") + ".");
      }
      case IntegrationBindings.Revoke ignored ->
          throw new AssertionError("Unexpected revoke review");
    }
    message(
        founder,
        "To approve within two minutes, enter /economy corporation confirm " + review.token());
    return true;
  }

  public static void stop(MinecraftServer server) {
    REVIEWS.remove(server);
  }

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    var revoke =
        Commands.literal("revoke")
            .then(
                Commands.argument("provider", ResourceLocationArgument.id())
                    .then(
                        Commands.argument("external-key", StringArgumentType.string())
                            .then(
                                Commands.argument("company", UuidArgument.uuid())
                                    .then(
                                        Commands.argument(
                                                "company-revision", LongArgumentType.longArg(1))
                                            .then(
                                                Commands.argument(
                                                        "mandate-revision",
                                                        LongArgumentType.longArg(1))
                                                    .executes(CorporationConsent::revoke))))));
    dispatcher.register(
        Commands.literal("economy")
            .then(
                Commands.literal("corporation")
                    .requires(CorporationConsent::direct)
                    .then(
                        Commands.literal("confirm")
                            .then(
                                Commands.argument("token", UuidArgument.uuid())
                                    .executes(CorporationConsent::confirm)))
                    .then(
                        Commands.literal("lookup")
                            .then(
                                Commands.argument("token", UuidArgument.uuid())
                                    .executes(CorporationConsent::lookup)))
                    .then(revoke)));
  }

  private static boolean direct(CommandSourceStack source) {
    return source.getPlayer() != null && source.source == source.getPlayer();
  }

  private static int confirm(CommandContext<CommandSourceStack> context) {
    var review = review(context);
    if (review == null || review.submitted() || !review.providerCurrent().getAsBoolean())
      return unavailable(context.getSource());
    return submit(context.getSource(), review);
  }

  private static int revoke(CommandContext<CommandSourceStack> context) {
    var source = context.getSource();
    var player = source.getPlayer();
    var services = Economy.serverServices(source.getServer());
    if (!direct(source) || grant(player, services) == null) return unavailable(source);
    try {
      var key =
          new IntegrationBindings.Key(
              ResourceLocationArgument.getId(context, "provider").toString(),
              StringArgumentType.getString(context, "external-key"));
      var command =
          new IntegrationBindings.Revoke(
              key,
              UuidArgument.getUuid(context, "company"),
              LongArgumentType.getLong(context, "company-revision"),
              LongArgumentType.getLong(context, "mandate-revision"));
      var reviews = reviews(player.server);
      if (!reviews.containsKey(player.getUUID()) && reviews.size() >= MAX_PER_SERVER)
        return unavailable(source);
      return submit(
          source,
          new Review(
              UUID.randomUUID(),
              player,
              player.connection,
              services,
              new OperationId(UUID.randomUUID()),
              command,
              () -> true,
              System.nanoTime() + REVIEW_LIFETIME,
              false));
    } catch (IllegalArgumentException invalid) {
      return unavailable(source);
    }
  }

  private static int submit(CommandSourceStack source, Review review) {
    var grant = grant(review.player(), review.services());
    if (grant == null || !review.live()) return unavailable(source);
    var submitted = review.submit();
    var services = review.services();
    var command = review.command();
    CorporateBanking.authorityChanging(services);
    var submission =
        services.submitOwned(
            grant,
            IntegrationBindings.kind(command.key()),
            review.operation(),
            attempt(),
            request(command),
            handler(review, grant.target()),
            null);
    reviews(review.player().server).put(review.player().getUUID(), submitted);
    message(
        review.player(),
        "Operation "
            + review.operation().value()
            + " · Original outcome lookup /economy corporation lookup "
            + review.token());
    submission
        .completion()
        .whenCompleteAsync(
            (delivery, failure) -> {
              if (submitted.live())
                outcome(submitted.player(), failure == null ? delivery.outcome() : null);
            },
            review.player().server);
    return 1;
  }

  private static int lookup(CommandContext<CommandSourceStack> context) {
    var review = review(context);
    if (review == null || !review.submitted()) return unavailable(context.getSource());
    var grant = grant(review.player(), review.services());
    if (grant == null) return unavailable(context.getSource());
    var command = review.command();
    review
        .services()
        .lookupOwned(
            grant,
            IntegrationBindings.kind(command.key()),
            review.operation(),
            attempt(),
            request(command),
            handler(review, grant.target()))
        .whenCompleteAsync(
            (found, failure) -> {
              if (!review.live()) return;
              if (failure != null) outcome(review.player(), null);
              else if (found.isEmpty())
                message(review.player(), "No durable outcome for this operation.");
              else outcome(review.player(), found.orElseThrow());
            },
            review.player().server);
    return 1;
  }

  private static JournalEngine.Registration handler(Review review, PartyRef owner) {
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          if (!review.providerCurrent().getAsBoolean())
            return new JournalEngine.Reject(
                new Journal.Result(1, "INTEGRATION_PROVIDER_DISABLED", ""));
          var decision =
              IntegrationBindings.prepare(
                  intent, reads, owner, review.command(), review.services()::financialMillis);
          if (!(decision instanceof JournalEngine.Commit commit)) return decision;
          return new JournalEngine.Commit(
              commit.records(),
              commit.result(),
              tx -> {
                if (!review.providerCurrent().getAsBoolean())
                  throw new IllegalStateException(
                      "Integration provider disabled during consent transaction");
                commit.projections().apply(tx);
              });
        });
  }

  private static PartyAuthorization.Grant grant(ServerPlayer player, ServerServices services) {
    if (player == null || services == null || !player.server.isSameThread()) return null;
    var world = services.worldId();
    if (world == null) return null;
    var owner = new PartyRef(world, new PartyRef.Player(new PlayerId(player.getUUID())));
    return PartyAuthorization.player(
            player, server -> Economy.serverServices(server) == services ? services : null, owner)
        .orElse(null);
  }

  private static Map<UUID, Review> reviews(MinecraftServer server) {
    var reviews = REVIEWS.computeIfAbsent(server, ignored -> new HashMap<>());
    long now = System.nanoTime();
    reviews.values().removeIf(entry -> now - entry.deadline() >= 0 || !entry.live());
    return reviews;
  }

  private static Review review(CommandContext<CommandSourceStack> context) {
    var source = context.getSource();
    if (!direct(source)) return null;
    var player = source.getPlayer();
    var review = reviews(player.server).get(player.getUUID());
    return review != null
            && review.player() == player
            && review.live()
            && review.token().equals(UuidArgument.getUuid(context, "token"))
        ? review
        : null;
  }

  private static Journal.CanonicalRequest request(IntegrationBindings.Command command) {
    return new Journal.CanonicalRequest(1, command.fields());
  }

  private static RequestId attempt() {
    return new RequestId(UUID.randomUUID());
  }

  private static int unavailable(CommandSourceStack source) {
    source.sendFailure(Component.literal("Corporation integration request unavailable."));
    return 0;
  }

  private static void message(ServerPlayer player, String text) {
    player.sendSystemMessage(Component.literal(text));
  }

  private static void outcome(ServerPlayer player, Journal.Outcome outcome) {
    if (outcome == null) {
      message(player, "Outcome unavailable. Use the original operation lookup.");
      return;
    }
    var result = outcome.result();
    message(
        player,
        "Corporation integration "
            + outcome.status().name()
            + " · "
            + result.code()
            + (result.payload().isEmpty() ? "" : " · " + result.payload()));
  }
}
