package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.storage.DatabaseUpgrade;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** Connected operators approve exact database reviews; conversion remains an offline operation. */
public final class DatabaseUpgradeCommands {
  private static final long REVIEW_NANOS = TimeUnit.MINUTES.toNanos(2);
  private static final int SESSION_LIMIT = 8;
  private static final Map<MinecraftServer, State> STATES = new IdentityHashMap<>();

  private DatabaseUpgradeCommands() {}

  private static final class State {
    final Map<UUID, Session> sessions = new LinkedHashMap<>();
    final ThreadPoolExecutor worker =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(SESSION_LIMIT),
            task -> {
              var thread = new Thread(task, "economy-upgrade-review");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  private static final class Session {
    final ServerPlayer player;
    final ServerServices services;
    final Function<MinecraftServer, ServerServices> lookup;
    final AtomicBoolean live = new AtomicBoolean(true);
    final long expires = System.nanoTime() + REVIEW_NANOS;
    DatabaseUpgrade.Review review;
    boolean busy;

    Session(
        ServerPlayer player,
        ServerServices services,
        Function<MinecraftServer, ServerServices> lookup) {
      this.player = player;
      this.services = services;
      this.lookup = lookup;
    }
  }

  public static void register(
      CommandDispatcher<CommandSourceStack> dispatcher,
      Function<MinecraftServer, ServerServices> sessions) {
    dispatcher.register(
        Commands.literal("economy")
            .then(
                Commands.literal("upgrade")
                    .requires(DatabaseUpgradeCommands::connectedOperator)
                    .then(Commands.literal("review").executes(c -> review(c.getSource(), sessions)))
                    .then(
                        Commands.literal("confirm")
                            .then(
                                Commands.argument("token", StringArgumentType.word())
                                    .executes(
                                        c ->
                                            confirm(
                                                c.getSource(),
                                                sessions,
                                                StringArgumentType.getString(c, "token")))))));
  }

  private static boolean connectedOperator(CommandSourceStack source) {
    var player = source.getPlayer();
    return source.hasPermission(2)
        && player != null
        && player.hasPermissions(2)
        && source.source == player
        && source.getServer().getPlayerList().getPlayer(player.getUUID()) == player;
  }

  private static ServerServices requireUnavailable(
      CommandSourceStack source, Function<MinecraftServer, ServerServices> lookup) {
    if (!connectedOperator(source))
      throw new IllegalStateException("A directly connected operator must review and confirm.");
    var services = lookup.apply(source.getServer());
    if (services == null || services.state() != ServerServices.State.UNAVAILABLE)
      throw new IllegalStateException(
          "Wait for startup to finish. Upgrade review requires Economy to be unavailable.");
    return services;
  }

  private static int review(
      CommandSourceStack source, Function<MinecraftServer, ServerServices> lookup) {
    try {
      var services = requireUnavailable(source, lookup);
      var server = source.getServer();
      var state = STATES.computeIfAbsent(server, ignored -> new State());
      tick(server);
      var player = source.getPlayer();
      var previous = state.sessions.get(player.getUUID());
      if (previous != null && previous.busy)
        throw new IllegalStateException("Your previous upgrade request is still pending.");
      if (previous == null && state.sessions.size() >= SESSION_LIMIT)
        throw new IllegalStateException("Upgrade review queue is full; try again shortly.");
      if (previous != null) previous.live.set(false);
      var session = new Session(player, services, lookup);
      session.busy = true;
      state.sessions.put(player.getUUID(), session);
      Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
      try {
        state.worker.execute(
            () -> {
              DatabaseUpgrade.Review result = null;
              Exception failure = null;
              try {
                if (!session.live.get()) return;
                result = DatabaseUpgrade.review(world);
              } catch (Exception error) {
                failure = error;
              }
              var reviewed = result;
              var error = failure;
              server.execute(
                  () -> {
                    if (!current(server, state, session)) return;
                    session.busy = false;
                    if (error != null) {
                      discard(state, session);
                      source.sendFailure(
                          Component.literal("Upgrade review refused: " + safe(error)));
                      return;
                    }
                    session.review = reviewed;
                    send(
                        source,
                        "Database "
                            + reviewed.sourceFormat()
                            + ", schema "
                            + reviewed.sourceSchema()
                            + " -> "
                            + reviewed.targetSchema()
                            + ", accounts "
                            + reviewed.accountCount()
                            + ".");
                    send(source, reviewed.summary());
                    send(
                        source,
                        "Confirm this review within two minutes: /economy upgrade confirm "
                            + reviewed.token());
                    send(
                        source,
                        "After confirmation, stop the server normally, run ./gradlew upgradeEconomyDatabase -PeconomyWorld=/absolute/world, then restart.");
                  });
            });
      } catch (RejectedExecutionException error) {
        discard(state, session);
        throw new IllegalStateException("Upgrade review queue is full; try again shortly.");
      }
      send(source, "Inspecting the database for an upgrade review...");
      return 1;
    } catch (RuntimeException error) {
      source.sendFailure(Component.literal("Upgrade review refused: " + safe(error)));
      return 0;
    }
  }

  private static int confirm(
      CommandSourceStack source, Function<MinecraftServer, ServerServices> lookup, String token) {
    try {
      requireUnavailable(source, lookup);
      var server = source.getServer();
      var state = STATES.get(server);
      var session = state == null ? null : state.sessions.get(source.getPlayer().getUUID());
      if (session == null
          || !current(server, state, session)
          || session.player != source.getPlayer()
          || session.busy
          || session.review == null
          || !session.review.token().equals(token))
        throw new IllegalStateException("Run a fresh upgrade review from this login first.");
      session.busy = true;
      Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
      UUID administrator = session.player.getUUID();
      var reviewed = session.review;
      try {
        state.worker.execute(
            () -> {
              Exception failure = null;
              try {
                if (!session.live.get()) return;
                DatabaseUpgrade.approve(world, reviewed, administrator, session.live::get);
              } catch (Exception error) {
                failure = error;
              }
              var error = failure;
              server.execute(
                  () -> {
                    if (!current(server, state, session)) return;
                    discard(state, session);
                    if (error != null) {
                      source.sendFailure(
                          Component.literal("Upgrade approval refused: " + safe(error)));
                      return;
                    }
                    send(
                        source,
                        "Database upgrade approved for the exact reviewed source and target schema.");
                    send(
                        source,
                        "Stop the server normally, run ./gradlew upgradeEconomyDatabase -PeconomyWorld=/absolute/world, then restart. The tool backs up the world before conversion.");
                  });
            });
      } catch (RejectedExecutionException error) {
        session.busy = false;
        throw new IllegalStateException("Upgrade approval queue is full; try again shortly.");
      }
      send(source, "Saving your database upgrade approval...");
      return 1;
    } catch (RuntimeException error) {
      source.sendFailure(Component.literal("Upgrade approval refused: " + safe(error)));
      return 0;
    }
  }

  /** Server-thread observation revokes detached worker leases permanently. */
  public static void tick(MinecraftServer server) {
    var state = STATES.get(server);
    if (state == null) return;
    var iterator = state.sessions.values().iterator();
    while (iterator.hasNext()) {
      var session = iterator.next();
      if (!valid(server, session)) {
        session.live.set(false);
        iterator.remove();
      }
    }
  }

  public static void stopped(MinecraftServer server) {
    var state = STATES.remove(server);
    if (state == null) return;
    state.sessions.values().forEach(session -> session.live.set(false));
    state.sessions.clear();
    state.worker.shutdownNow();
  }

  private static boolean current(MinecraftServer server, State state, Session session) {
    if (STATES.get(server) != state
        || state.sessions.get(session.player.getUUID()) != session
        || !valid(server, session)) {
      session.live.set(false);
      return false;
    }
    return true;
  }

  private static boolean valid(MinecraftServer server, Session session) {
    return session.live.get()
        && System.nanoTime() - session.expires < 0
        && server.getPlayerList().getPlayer(session.player.getUUID()) == session.player
        && session.player.hasPermissions(2)
        && session.lookup.apply(server) == session.services
        && session.services.state() == ServerServices.State.UNAVAILABLE;
  }

  private static void discard(State state, Session session) {
    session.live.set(false);
    state.sessions.remove(session.player.getUUID(), session);
  }

  private static void send(CommandSourceStack source, String text) {
    source.sendSuccess(() -> Component.literal(text), false);
  }

  private static String safe(Exception error) {
    String text = error.getMessage();
    if (text == null || text.isBlank()) return "Database inspection or approval failed.";
    text = text.replaceAll("[\\r\\n\\p{Cntrl}]", " ");
    return text.substring(0, Math.min(240, text.length()));
  }
}
