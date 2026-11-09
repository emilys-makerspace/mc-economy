package ca.emilybelnavis.mcmods.economy.examples;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.EconomicSimulationHistory;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.foundation.PartyAuthorization;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;

/** Copyable authenticated consumer, compiled with the tests; installs no command or event handler. */
public final class SimulationHistoryExample {
    private SimulationHistoryExample() {}

    /**
     * Call from an authenticated server action. Use Long.MAX_VALUE for the first page, then the
     * returned nextBeforeDay. stillViewing runs on the server thread and can check menu/revision.
     * The returned action abandons display only; it does not cancel admitted server work.
     */
    public static Runnable page(ServerPlayer player, long beforeDay, BooleanSupplier stillViewing,
                                Consumer<Optional<EconomicSimulationHistory.Page>> display,
                                Consumer<Throwable> failure) {
        var server = player.getServer();
        if (server == null || !server.isSameThread())
            throw new IllegalStateException("Call on the authenticated server thread");
        var interested = new AtomicBoolean(true);
        Runnable abandonDisplay = () -> interested.set(false);
        try {
            var services = Economy.serverServices(server);
            if (services == null || services.state() != ServerServices.State.READY)
                throw new IllegalStateException("Economy is not ready");
            var api = services.simulationHistory();
            if (api.version() != EconomicSimulationHistory.VERSION)
                throw new IllegalStateException("Unsupported history API version");
            var world = services.worldId();
            var session = services.sessionId();
            var owner = new PartyRef(world, new PartyRef.Player(new PlayerId(player.getUUID())));
            var grant = PartyAuthorization.player(player, Economy::serverServices, owner).orElseThrow(
                    () -> new SecurityException("Current player ownership required"));
            api.page(grant, beforeDay, 8).whenComplete((result, error) -> server.execute(() -> {
                // A read capability is not a continuing live player/menu audience.
                if (!interested.get() || server.getPlayerList().getPlayer(player.getUUID()) != player
                        || Economy.serverServices(server) != services || services.state() != ServerServices.State.READY
                        || !services.sessionId().equals(session) || !services.worldId().equals(world)
                        || !stillViewing.getAsBoolean()) return;
                if (error != null) failure.accept(error);
                else display.accept(result); // empty Optional means denied, not an empty history page
            }));
        } catch (RuntimeException rejected) {
            failure.accept(rejected);
        }
        return abandonDisplay;
    }
}
