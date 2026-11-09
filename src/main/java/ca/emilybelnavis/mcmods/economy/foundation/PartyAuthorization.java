package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Server admission for A3. Call these factories for each authenticated request. */
public final class PartyAuthorization {
  private PartyAuthorization() {}

  public static Optional<Grant> player(
      ServerPlayer sender, Function<MinecraftServer, ServerServices> sessions, PartyRef target) {
    var server = sender.getServer();
    if (server == null
        || !server.isSameThread()
        || server.getPlayerList().getPlayer(sender.getUUID()) != sender) return Optional.empty();
    var services = sessions.apply(server);
    return services == null
        ? Optional.empty()
        : admit(
            services.partyWorld(),
            services.sessionId(),
            new PlayerId(sender.getUUID()),
            false,
            target,
            false);
  }

  /** Player-backed operator commands have a durable Minecraft UUID for the audit trail. */
  public static Optional<Grant> inspection(
      CommandSourceStack source,
      Function<MinecraftServer, ServerServices> sessions,
      PartyRef target) {
    var server = source.getServer();
    var player = source.getPlayer();
    if (server == null
        || !server.isSameThread()
        || player == null
        || source.source != player
        || !EconomyCommands.privileged(source)
        || server.getPlayerList().getPlayer(player.getUUID()) != player
        || !EconomyCommands.privileged(player.createCommandSourceStack())) return Optional.empty();
    var services = sessions.apply(server);
    return services == null
        ? Optional.empty()
        : admit(
            services.partyWorld(),
            services.sessionId(),
            new PlayerId(player.getUUID()),
            true,
            target,
            true);
  }

  /**
   * Trusted built-in caller only. The actor is chosen by server code, never from request fields.
   */
  static Optional<Grant> internal(ServerServices services, SystemActorId actor, PartyRef target) {
    return admit(services.partyWorld(), services.sessionId(), actor, false, target, false);
  }

  // Detached policy seam used by trusted admission and tests. Never accept these inputs from
  // transport.
  static Optional<Grant> admit(
      WorldId world,
      UUID session,
      ActorId actor,
      boolean operator,
      PartyRef target,
      boolean inspection) {
    if (!target.world().equals(world) || target.owner() instanceof PartyRef.Organization)
      return Optional.empty();
    boolean allowed =
        switch (target.owner()) {
          case PartyRef.Player player ->
              actor instanceof PlayerId && (inspection ? operator : player.id().equals(actor));
          case PartyRef.System system ->
              inspection ? actor instanceof PlayerId && operator : system.id().equals(actor);
          case PartyRef.Organization ignored -> false;
        };
    return allowed ? Optional.of(new Grant(session, actor, target, inspection)) : Optional.empty();
  }

  /** Detached, server-issued capability scoped to one owner and one mode. */
  public static final class Grant {
    private final UUID session;
    private final ActorId actor;
    private final PartyRef target;
    private final boolean inspection;

    private Grant(UUID session, ActorId actor, PartyRef target, boolean inspection) {
      this.session = Objects.requireNonNull(session);
      this.actor = Objects.requireNonNull(actor);
      this.target = target;
      this.inspection = inspection;
    }

    public PartyRef target() {
      return target;
    }

    public ActorId actor() {
      return actor;
    }

    boolean allowsOwnership(WorldId world, UUID session) {
      return !inspection && this.session.equals(session) && target.world().equals(world);
    }

    boolean allows(WorldId world, UUID session, ActorId actor, Journal.Kind kind) {
      return this.session.equals(session)
          && target.world().equals(world)
          && this.actor.equals(actor)
          && inspection == kind.equals(InspectionAudit.KIND);
    }
  }

  static String ownerKey(PartyRef.Owner owner) {
    return switch (owner) {
      case PartyRef.Player player -> Journal.actorKey(player.id());
      case PartyRef.System system -> Journal.actorKey(system.id());
      case PartyRef.Organization organization -> "organization:" + organization.id().value();
    };
  }
}
