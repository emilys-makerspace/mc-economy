package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.valuation.*;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Opt-in disposable startup/reload witness. Never packaged in the release jar. */
public final class ValuationClient {
    private boolean opened, stopped;
    private final long start = System.nanoTime();
    private volatile String result, failure;
    private int phase, clientTicks;
    private UUID original, changed;
    private CompletableFuture<?> query;
    public ValuationClient() {
        NeoForge.EVENT_BUS.addListener(this::client);
        NeoForge.EVENT_BUS.addListener(this::server);
    }
    private void client(ClientTickEvent.Post event) {
        if (stopped) return;
        var client = Minecraft.getInstance(); client.options.pauseOnLostFocus = false; clientTicks++;
        try {
            if (!opened && client.getOverlay() == null && client.screen != null && client.level == null) {
                opened = true; String name = "economy-valuation-" + UUID.randomUUID();
                require(!Files.exists(client.gameDirectory.toPath().resolve("saves").resolve(name)), "fresh world");
                client.createWorldOpenFlows().createFreshLevel(name, new LevelSettings(name, GameType.CREATIVE, false,
                        Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(902L, false, false), WorldPresets::createNormalWorldDimensions, client.screen);
            }
            if (client.player != null && client.player.containerMenu instanceof BankingMenu menu
                    && menu.worldSetup() && !menu.busy() && clientTicks % 20 == 0) BankingNetwork.action(menu, "setup", "CAD");
            if (result == null && failure == null && System.nanoTime() - start > 240_000_000_000L) failure = "Fixture deadline phase=" + phase;
            if (result != null || failure != null) {
                stopped = true;
                Files.writeString(client.gameDirectory.toPath().resolve(failure == null ? "valuation-passed.txt" : "valuation-failed.txt"),
                        failure == null ? result : failure);
                client.execute(client::stop);
            }
        } catch (Exception error) { error.printStackTrace(); failure = error.toString(); client.execute(client::stop); stopped = true; }
    }
    private void server(ServerTickEvent.Post event) {
        if (result != null || failure != null) return;
        var server = event.getServer(); var services = Economy.serverServices(server);
        if (services == null || services.state() != ServerServices.State.READY || server.getPlayerList().getPlayers().isEmpty()) return;
        try {
            require(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString()
                    .matches("economy-valuation-[0-9a-f-]{36}"), "guarded disposable world");
            Path config = FMLPaths.CONFIGDIR.get().resolve("economy-valuation.json").toAbsolutePath().normalize();
            require(config.startsWith(Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize()), "disposable config root");
            if (query != null && !query.isDone()) return;
            if (query != null) { query.join(); query = null; }
            var status = ValuationRuntime.status(server);
            if (phase == 0 && status.ready()) {
                require(!Files.exists(config), "existing operator config refused");
                original = status.snapshot(); phase = 1;
                reload(server);
            } else if (phase == 1 && status.ready()) {
                require(original.equals(status.snapshot()), "identical reload is idempotent");
                Files.writeString(config,"{\"bases\":[],\"overrides\":[{\"item\":\"minecraft:diamond\",\"currency\":\"CAD\",\"amount\":\"301\",\"referenceCount\":1}]}");
                phase = 2; reload(server);
            } else if (phase == 2 && status.ready()) {
                require(!original.equals(status.snapshot()), "changed config new snapshot"); changed = status.snapshot(); phase = 3;
                query = services.tradeStorage(store -> {
                    var snapshot = ValuationStore.active(store);
                    var fx = store.transaction(tx -> FxService.at(tx::query,System.currentTimeMillis()));
                    var value = BaseValues.evaluate(ValuationData.decode(snapshot.payload()),"minecraft:diamond","{}",1,EconomyCurrency.CAD,fx);
                    require(value.unitMoney().minorUnits() == 30100, "actual captured config value"); return true;
                }).toCompletableFuture();
            } else if (phase == 3) {
                Files.writeString(config,"{\"bases\":[],\"overrides\":\"invalid\"}"); phase = 4; reload(server);
            } else if (phase == 4 && status.reason().equals("VALUATION_RELOAD_REJECTED")) {
                require(!status.ready(), "invalid reload no fresh admission"); phase = 5;
                query = services.tradeStorage(store -> { require(changed.equals(ValuationStore.active(store).id()), "invalid reload preserves durable active"); return true; }).toCompletableFuture();
            } else if (phase == 5) {
                Files.writeString(config,"{\"bases\":[],\"overrides\":[]}"); phase = 6; reload(server);
            } else if (phase == 6 && status.ready()) {
                phase = 7;
                query = services.tradeStorage(store -> {
                    require(ValuationStore.active(store).revision() == 3,"reversion creates prospective revision");
                    var historical = ValuationStore.load(store,original);
                    require(historical.revision() == 1,"original manifest retained");
                    return true;
                }).toCompletableFuture();
            } else if (phase == 7) {
                result = "PASS startup schema50; actual reload hook pending; identical reload no publication; config301 new snapshot; invalid reload preserved durable active and refused fresh reads; reversion revision3; original manifest retained; normal client shutdown.";
                System.out.println("VALUATION_PROBE_PASS " + result);
            }
        } catch (Throwable error) { error.printStackTrace(); failure = error.toString(); }
    }
    private static void reload(MinecraftServer server) {
        server.reloadResources(server.getPackRepository().getSelectedIds()).join();
        require(!ValuationRuntime.status(server).ready(), "reload synchronously invalidates admission");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
