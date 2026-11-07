package ca.emilybelnavis.mcmods.economy;

import com.mojang.logging.LogUtils;
import ca.emilybelnavis.mcmods.economy.foundation.DiagnosticsReport;
import ca.emilybelnavis.mcmods.economy.foundation.EconomyCommands;
import ca.emilybelnavis.mcmods.economy.foundation.SchedulingMetrics;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.foundation.StorageLifecycle;
import ca.emilybelnavis.mcmods.economy.foundation.SchedulingConfig;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime;
import ca.emilybelnavis.mcmods.economy.foundation.FxAdminCommands;
import ca.emilybelnavis.mcmods.economy.foundation.LedgerCommands;
import ca.emilybelnavis.mcmods.economy.foundation.DenominationChooser;
import java.time.Clock;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.Set;

@Mod(Economy.MOD_ID)
public final class Economy {
    public static final String MOD_ID = "economy";
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<MinecraftServer, DenominationChooser> choosers = new IdentityHashMap<>();
    private static final Map<MinecraftServer, ServerServices> servers = new IdentityHashMap<>();
    private final Set<MinecraftServer> invalidConfiguration = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final SchedulingMetrics.Queue NO_QUEUE = new SchedulingMetrics.Queue(0, 0, 0);
    private static final SchedulingMetrics NO_METRICS = new SchedulingMetrics(NO_QUEUE, NO_QUEUE, NO_QUEUE,
            new StorageLifecycle.Progress("storage", 0, -1, false),
            new StorageLifecycle.EconomicProgress(0, 0, false, false), false, 0);

    private final Map<MinecraftServer,FoundationRuntime> runtimes = new IdentityHashMap<>();
    private final Map<MinecraftServer,ca.emilybelnavis.mcmods.economy.recovery.ShutdownErrors> shutdownErrors = new IdentityHashMap<>();
    private final Map<MinecraftServer,Boolean> drained = new IdentityHashMap<>();
    public Economy(net.neoforged.bus.api.IEventBus modBus) {
        ca.emilybelnavis.mcmods.economy.cash.CashCatalog.initialize(modBus);
        ca.emilybelnavis.mcmods.economy.card.CardCatalog.initialize(modBus);
        ca.emilybelnavis.mcmods.economy.terminal.TerminalCatalog.initialize(modBus);
        ca.emilybelnavis.mcmods.economy.village.BankPoints.initialize(modBus);
        ca.emilybelnavis.mcmods.economy.village.generation.VillageGeneration.initialize(modBus);
        ca.emilybelnavis.mcmods.economy.village.GenerationRuntime.initialize();
        ca.emilybelnavis.mcmods.economy.banker.desk.DeskCatalog.initialize(modBus);
        ca.emilybelnavis.mcmods.economy.banker.entity.BankerCatalog.initialize(modBus);
        ca.emilybelnavis.mcmods.economy.banker.dialogue.BankerMenuCatalog.initialize(modBus);
        ca.emilybelnavis.mcmods.economy.banking.BankingCatalog.initialize(modBus);
        ca.emilybelnavis.mcmods.economy.collection.CollectionCatalog.initialize(modBus);
        ca.emilybelnavis.mcmods.economy.collection.CollectionMenuCatalog.initialize(modBus);
        ca.emilybelnavis.mcmods.economy.trade.entity.TradeHooks.initialize();
        ca.emilybelnavis.mcmods.economy.trade.entity.TradeRuntime.initialize();
        NeoForge.EVENT_BUS.addListener(this::card);
        ca.emilybelnavis.mcmods.economy.cash.CashStackIdentity.initialize(modBus);
        NeoForge.EVENT_BUS.addListener(this::commands);
        NeoForge.EVENT_BUS.addListener(this::starting);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::stopping);
        NeoForge.EVENT_BUS.addListener(this::stopped);
        LOGGER.info("Economy foundation loaded");
        try { LOGGER.info("SQLite JDBC driver loaded: {}", SqliteStorage.verifyDriver()); }
        catch (StorageException e) { LOGGER.error("event={} component=sqlite_driver", ServerServices.failureCode(e)); }
    }
    /** Trusted integrations can resolve the current service and issue fresh server-side grants. */
    public static ServerServices serverServices(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Requires server thread");
        return servers.get(server);
    }

    public static boolean canChooseWorld(net.minecraft.server.level.ServerPlayer player, java.util.UUID lease) {
        var chooser = activeChoosers.get(player.server); return chooser != null && chooser.canChoose(player, lease);
    }
    public static boolean chooseWorld(net.minecraft.server.level.ServerPlayer player, java.util.UUID lease, String code) {
        var chooser = activeChoosers.get(player.server); return chooser != null && chooser.choose(player, lease, code);
    }
    private static final Map<MinecraftServer, DenominationChooser> activeChoosers = new IdentityHashMap<>();
    private void card(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                && ca.emilybelnavis.mcmods.economy.card.CardCatalog.find(event.getItemStack()).isPresent()) {
            ca.emilybelnavis.mcmods.economy.banking.BankingController.openCard(player);
            event.setCancellationResult(net.minecraft.world.InteractionResult.CONSUME); event.setCanceled(true);
        }
    }
    private void commands(RegisterCommandsEvent event) {
        DenominationChooser.register(event.getDispatcher(), choosers::get);
        FxAdminCommands.register(event.getDispatcher(), servers::get, Clock.systemUTC());
        LedgerCommands.register(event.getDispatcher(), servers::get);
        ca.emilybelnavis.mcmods.economy.foundation.BranchCommands.register(event.getDispatcher(), servers::get);
        ca.emilybelnavis.mcmods.economy.foundation.CreditCommands.register(event.getDispatcher(), servers::get);
        ca.emilybelnavis.mcmods.economy.foundation.LoanCommands.register(event.getDispatcher(), servers::get);
        ca.emilybelnavis.mcmods.economy.foundation.DebtCommands.register(event.getDispatcher(), servers::get);
        ca.emilybelnavis.mcmods.economy.foundation.RegisterPriceCommands.register(event.getDispatcher(), servers::get);
        ca.emilybelnavis.mcmods.economy.foundation.AdminConfigCommands.register(event.getDispatcher());
        EconomyCommands.register(event.getDispatcher(), (source, view) -> {
            var server = source.getServer();
            var services = servers.get(server);
            if (services != null) return view.calendarAfter() == null
                    ? services.diagnostics(view.privileged(), view.detailed())
                    : services.calendarDiagnostics(view.calendarAfter());
            String reason = invalidConfiguration.contains(server) ? "CONFIGURATION_INVALID" : null;
            if (view.calendarAfter() != null) return ca.emilybelnavis.mcmods.economy.foundation.CalendarReport.render(
                    null, ServerServices.State.UNAVAILABLE, reason, NO_METRICS, view.calendarAfter());
            var report = DiagnosticsReport.render(view.privileged(), view.detailed(), ServerServices.State.UNAVAILABLE,
                    NO_METRICS, null, reason, null, null);
            if (!view.privileged() || !view.detailed()) return report;
            var detailed = new java.util.ArrayList<>(DiagnosticsReport.withFx(report,
                    ServerServices.State.UNAVAILABLE, null, reason));
            detailed.addAll(ca.emilybelnavis.mcmods.economy.foundation.CalendarReport.summary(null,
                    ServerServices.State.UNAVAILABLE, reason, NO_METRICS));
            return java.util.List.copyOf(detailed);
        });
    }

    private void starting(ServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        if (servers.containsKey(server)) throw new IllegalStateException("Duplicate server lifecycle");
        SchedulingConfig scheduling;
        ca.emilybelnavis.mcmods.economy.clock.FinancialCalendar.Config calendar;
        try {
            scheduling = SchedulingConfig.load(FMLPaths.CONFIGDIR.get().resolve("economy-scheduling.properties"));
            calendar = ca.emilybelnavis.mcmods.economy.clock.FinancialCalendar.Config.load(FMLPaths.CONFIGDIR.get().resolve("economy-calendar.properties"));
        } catch (SchedulingConfig.InvalidConfiguration e) {
            invalidConfiguration.add(server);
            LOGGER.error("event=CONFIGURATION_INVALID reason={}", e.safeReason());
            return;
        }
        catch (IllegalArgumentException e) {
            invalidConfiguration.add(server);
            LOGGER.error("event=CONFIGURATION_INVALID reason=financial_calendar_settings");
            return;
        }
        var runtime = new FoundationRuntime(FoundationRuntime.Configuration.production(Clock.systemUTC(), calendar), scheduling.sqliteLockTimeoutMillis());
        servers.put(server, new ServerServices(server.getWorldPath(LevelResource.ROOT), runtime, scheduling));
        servers.get(server).onDenominationChange(()->ca.emilybelnavis.mcmods.economy.village.GenerationRuntime.denominationChanged(server));
        runtimes.put(server, runtime);
        choosers.put(server, new DenominationChooser(servers.get(server), runtime, Clock.systemUTC()));
        activeChoosers.put(server, choosers.get(server));
        LOGGER.info("Economy foundation initializing");
    }
    private void tick(ServerTickEvent.Post event) {
        ServerServices services = servers.get(event.getServer());
        if (services != null) {
            var previous = services.state();
            var previousPhase = services.progress().phase();
            ca.emilybelnavis.mcmods.economy.institution.KioskAdmission.tick(event.getServer());
            ca.emilybelnavis.mcmods.economy.foundation.BranchCommands.tick(event.getServer());
            ca.emilybelnavis.mcmods.economy.terminal.BranchBootstrap.revalidate(event.getServer());
            ca.emilybelnavis.mcmods.economy.terminal.KioskUpgradeController.revalidate(event.getServer());
            ca.emilybelnavis.mcmods.economy.village.GenerationRuntime.revalidate(event.getServer());
            ca.emilybelnavis.mcmods.economy.banker.StaffingRuntime.revalidate(event.getServer());
            services.tick();
            ca.emilybelnavis.mcmods.economy.trade.entity.TradeRuntime.tick(event.getServer());
            ca.emilybelnavis.mcmods.economy.banker.StaffingRuntime.tick(event.getServer());
            ca.emilybelnavis.mcmods.economy.village.GenerationRuntime.tick(event.getServer());
            ca.emilybelnavis.mcmods.economy.collection.CollectionAgents.tick(event.getServer());
            ca.emilybelnavis.mcmods.economy.collection.CollectionDialogue.tick(event.getServer());
            ca.emilybelnavis.mcmods.economy.terminal.KioskUpgradeController.tick(event.getServer());
            ca.emilybelnavis.mcmods.economy.terminal.TerminalLifecycle.tick(event.getServer());
            ca.emilybelnavis.mcmods.economy.terminal.BranchBootstrap.tick(event.getServer());
            choosers.get(event.getServer()).tick(event.getServer());
            if (!services.progress().phase().equals(previousPhase)) LOGGER.info("Economy startup progress: {} ({}/{})", DiagnosticsReport.recoveryPhase(services.progress().phase()), services.progress().completed(), services.progress().target());
            if (services.state() != previous) {
                LOGGER.info("Economy foundation state: {}", services.state());
                if (services.failure() != null)
                    LOGGER.error("event={} lifecycle=unavailable", ServerServices.failureCode(services.failure()));
            }
        }
    }
    private void stopping(ServerStoppingEvent event) {
        ca.emilybelnavis.mcmods.economy.trade.entity.TradeRuntime.stopping(event.getServer());
        ca.emilybelnavis.mcmods.economy.banker.StaffingRuntime.stopping(event.getServer());
        ca.emilybelnavis.mcmods.economy.collection.CollectionDialogue.stop(event.getServer());
        ca.emilybelnavis.mcmods.economy.collection.CollectionAgents.stop(event.getServer());
        shutdownErrors.put(event.getServer(),new ca.emilybelnavis.mcmods.economy.recovery.ShutdownErrors());
        ca.emilybelnavis.mcmods.economy.foundation.BranchCommands.stopped(event.getServer());
        ca.emilybelnavis.mcmods.economy.terminal.BranchBootstrap.stopped(event.getServer());
        ca.emilybelnavis.mcmods.economy.terminal.KioskUpgradeController.stopped(event.getServer());
        ca.emilybelnavis.mcmods.economy.village.GenerationRuntime.stopped(event.getServer());
        ServerServices services = servers.remove(event.getServer());
        choosers.remove(event.getServer());
        activeChoosers.remove(event.getServer());
        if (services != null) {
            boolean wasReady = services.state() == ServerServices.State.READY;
            boolean clean = services.stop();
            drained.put(event.getServer(), wasReady && clean);
            LOGGER.info("Economy foundation stopped; drained={}", clean);
            if (!clean) LOGGER.error("event={} lifecycle=shutdown unresolved={}",
                    ServerServices.failureCode(services.failure()), services.metrics().unresolvedAtShutdown());
        }
    }
    private void stopped(ServerStoppedEvent event) {
        invalidConfiguration.remove(event.getServer());
        ca.emilybelnavis.mcmods.economy.institution.KioskAdmission.stopped(event.getServer());
        ca.emilybelnavis.mcmods.economy.terminal.TerminalLifecycle.stopped(event.getServer());
        ca.emilybelnavis.mcmods.economy.terminal.BranchBootstrap.stopped(event.getServer());
        FoundationRuntime runtime=runtimes.remove(event.getServer());
        ServerServices orphan=servers.remove(event.getServer());
        choosers.remove(event.getServer());
        activeChoosers.remove(event.getServer());
        if(orphan!=null) {orphan.stop();drained.remove(event.getServer());}
        var errors=shutdownErrors.remove(event.getServer());
        boolean saveFailed=errors==null || errors.failed() || event.getServer().isCurrentlySaving();
        if(errors!=null) errors.close();
        boolean clean=Boolean.TRUE.equals(drained.remove(event.getServer()));
        if(runtime==null || !clean || saveFailed) {LOGGER.warn("Complete-world clean shutdown not certified");return;}
        // The JDBC worker has terminated. A single bounded post-save I/O job writes the clean witness.
        var done=new java.util.concurrent.CompletableFuture<Void>();
        Thread worker=new Thread(() -> {
            try {runtime.afterMinecraftStopped();done.complete(null);}
            catch(Exception e){done.completeExceptionally(e);}
        },"economy-post-save");
        worker.setDaemon(true);worker.start();
        try {done.get(30,java.util.concurrent.TimeUnit.SECONDS);LOGGER.info("Economy complete-world clean shutdown recorded");}
        catch(Exception e){worker.interrupt();LOGGER.error("event=POST_SAVE_FAILURE lifecycle=shutdown");}
    }
}
