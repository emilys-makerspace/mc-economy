package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.valuation.ValuationRuntime;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Bounded operator reference reads; no listing, pricing-source or monetary mutation. */
public final class ValuationCommands {
    private static final int MAX_PENDING = 16;
    private static final Map<MinecraftServer, Set<UUID>> PENDING = new IdentityHashMap<>();
    private ValuationCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                Function<MinecraftServer, ServerServices> sessions) {
        var value = Commands.literal("value")
                .then(Commands.argument("item", StringArgumentType.word())
                        .executes(c -> value(c.getSource(), sessions, StringArgumentType.getString(c, "item"), EconomyCurrency.CAD))
                        .then(Commands.argument("currency", StringArgumentType.word()).executes(c -> {
                            try {
                                return value(c.getSource(), sessions, StringArgumentType.getString(c, "item"),
                                        EconomyCurrency.fromCode(StringArgumentType.getString(c, "currency")));
                            } catch (IllegalArgumentException invalid) {
                                c.getSource().sendFailure(Component.literal("Use a supported uppercase currency code."));
                                return 0;
                            }
                        })));
        var root = Commands.literal("valuation").requires(EconomyCommands::privileged)
                .then(Commands.literal("status").executes(c -> {
                    if (admit(c.getSource(), sessions) == null) return 0;
                    var status = ValuationRuntime.status(c.getSource().getServer());
                    send(c.getSource(), "Reference valuation: " + (status.ready() ? "READY" : "NOT_READY")
                            + " reason=" + status.reason() + " revision=" + status.revision() + " snapshot=" + status.snapshot());
                    return 1;
                }))
                .then(Commands.literal("coverage").executes(c -> {
                    if (admit(c.getSource(), sessions) == null) return 0;
                    send(c.getSource(), "Vanilla registry: 1,333 classified identities; 349 base, 760 derived, 109 variant, 115 intentionally operator-unpriced.");
                    send(c.getSource(), "Component states require supported typed adapters; registry classification is not a numeric value for every component permutation.");
                    send(c.getSource(), "Mod items require explicit provider registration. Reference reads grant no financial qualification or trade authority.");
                    return 1;
                }))
                .then(value)
                .then(Commands.literal("history")
                        .executes(c -> history(c.getSource(), sessions, 8))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 16))
                                .executes(c -> history(c.getSource(), sessions, IntegerArgumentType.getInteger(c, "limit")))));
        dispatcher.register(Commands.literal("economy").then(root));
    }

    private record Admission(ServerServices services, ServerPlayer player, PartyAuthorization.Grant grant) {}

    private static Admission admit(CommandSourceStack source, Function<MinecraftServer, ServerServices> sessions) {
        var server = source.getServer();
        var player = source.getPlayer();
        var services = sessions.apply(server);
        if (!server.isSameThread() || player == null || source.source != player || !EconomyCommands.privileged(source)
                || server.getPlayerList().getPlayer(player.getUUID()) != player
                || services == null || services.state() != ServerServices.State.READY) {
            source.sendFailure(Component.literal("Use a connected operator player while Economy is ready."));
            return null;
        }
        var target = new PartyRef(services.worldId(), new PartyRef.Player(new PlayerId(player.getUUID())));
        var grant = PartyAuthorization.player(player, sessions, target);
        return grant.map(g -> new Admission(services, player, g)).orElse(null);
    }

    private static int value(CommandSourceStack source, Function<MinecraftServer, ServerServices> sessions,
                             String item, EconomyCurrency currency) {
        var admission = admit(source, sessions);
        if (admission == null) return 0;
        try {
            var request = new ItemValuationIntegration.Request(new ItemValuationIntegration.Identity(item, "{}", 1),
                    1, currency, new ItemValuationIntegration.Current());
            return async(source, sessions, admission,
                    () -> admission.services().itemValuation(source.getServer()).evaluate(admission.grant(), request), result -> {
                        send(source, "Reference " + result.identity().itemId() + ": " + result.status() + " reason=" + result.reasonCode());
                        result.unitMoney().ifPresent(money -> send(source, "Unit=" + MoneyFormatter.format(money, Locale.CANADA)
                                + " coverage=" + result.coverage()));
                        result.snapshot().ifPresent(snapshot -> send(source, "Snapshot=" + snapshot.valuationSnapshot()
                                + " revision=" + snapshot.revision() + " FX=" + snapshot.fxPublication()));
                        result.explanation().ifPresent(explanation -> send(source, "Proof=" + explanation.rootHash()
                                + " nodes=" + explanation.steps().size() + " truncated=" + explanation.truncated()));
                    });
        } catch (IllegalArgumentException invalid) {
            source.sendFailure(Component.literal("Use a valid namespaced item ID and supported currency."));
            return 0;
        }
    }

    private static int history(CommandSourceStack source, Function<MinecraftServer, ServerServices> sessions, int limit) {
        var admission = admit(source, sessions);
        if (admission == null) return 0;
        return async(source, sessions, admission,
                () -> admission.services().itemValuation(source.getServer()).history(admission.grant(), Optional.empty(), limit), rows -> {
                    if (rows.isEmpty()) send(source, "No retained reference snapshots.");
                    for (var row : rows) send(source, "Snapshot=" + row.id() + " revision=" + row.revision()
                            + " published=" + row.publishedAt() + " bytes=" + row.bytes() + " hash=" + row.contentHash());
                });
    }

    private static <T> int async(CommandSourceStack source, Function<MinecraftServer, ServerServices> sessions,
                                 Admission admission, java.util.function.Supplier<CompletionStage<T>> work, Consumer<T> display) {
        var server = source.getServer();
        var pending = PENDING.computeIfAbsent(server, ignored -> new HashSet<>());
        if (pending.size() >= MAX_PENDING) {
            source.sendFailure(Component.literal("Reference diagnostic queue is full."));
            return 0;
        }
        var token = UUID.randomUUID();
        pending.add(token);
        try {
            work.get().whenComplete((result, error) -> server.execute(() -> {
                var current = PENDING.get(server);
                if (current == null || !current.remove(token)) return;
                if (sessions.apply(server) != admission.services() || server.getPlayerList().getPlayer(admission.player().getUUID()) != admission.player()
                        || !EconomyCommands.privileged(admission.player().createCommandSourceStack())) return;
                if (error != null) source.sendFailure(Component.literal("Reference diagnostic unavailable; retry when valuation is ready."));
                else display.accept(result);
            }));
            return 1;
        } catch (RuntimeException rejected) {
            pending.remove(token);
            source.sendFailure(Component.literal("Reference diagnostic unavailable; retry when valuation is ready."));
            return 0;
        }
    }

    public static void stopping(MinecraftServer server) { PENDING.remove(server); }
    private static void send(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }
}
