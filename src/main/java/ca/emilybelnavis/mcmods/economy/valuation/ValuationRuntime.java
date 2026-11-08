package ca.emilybelnavis.mcmods.economy.valuation;

import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import net.minecraft.server.MinecraftServer;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/** Internal lifecycle only. Public grant-authenticated valuation API arrives in A9-04. */
public final class ValuationRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ConcurrentHashMap<MinecraftServer, Session> SESSIONS = new ConcurrentHashMap<>();
    public record Status(boolean ready, String reason, UUID snapshot, long revision, String contentHash) {}
    public record Reload(Session session, long generation, Object oldResources, Status oldStatus) {}
    private record Candidate(ValuationData.Manifest manifest, String payload) {}
    private ValuationRuntime() {}

    public static void start(MinecraftServer server, ServerServices services, Path config) {
        SESSIONS.put(server, new Session(services, config));
    }
    public static Status status(MinecraftServer server) {
        var session = SESSIONS.get(server);
        return session == null ? new Status(false, "SESSION_UNAVAILABLE", null, 0, null) : session.status;
    }
    /** May be called off the server thread: invalidate synchronously before vanilla reload starts. */
    public static Reload beginReload(MinecraftServer server) {
        var session = SESSIONS.get(server);
        if (session == null) return null;
        synchronized (session.gate) {
            var prior = session.status;
            long generation = session.gate.invalidate();
            session.reloading = true;
            session.reloadCount++;
            session.requested = false;
            session.status = new Status(false, "RELOAD_PENDING", prior.snapshot(), prior.revision(), prior.contentHash());
            return new Reload(session, generation, server.getServerResources(), prior);
        }
    }
    public static void finishReload(MinecraftServer server, Reload reload, Throwable failure) {
        if (reload == null) return;
        server.execute(() -> {
            var session = SESSIONS.get(server);
            if (session != reload.session()) return;
            synchronized (session.gate) {
                // Every completion matters: an older overlapping reload may install last.
                session.reloadCount--;
                session.reloading = session.reloadCount > 0;
                if (session.reloading) return;
                if (failure != null) {
                    boolean unchanged = server.getServerResources() == reload.oldResources();
                    if (session.readyStatus != null && server.getServerResources() == session.readyResources)
                        session.status = session.readyStatus;
                    else session.status = new Status(false, "RELOAD_INSTALL_FAILED", null, 0, null);
                    session.requested = unchanged && !session.status.ready();
                } else session.requested = true;
            }
        });
    }
    public static void tick(MinecraftServer server) {
        var session = SESSIONS.get(server);
        if (session != null && session.status.ready() && session.readyResources != server.getServerResources()) {
            synchronized (session.gate) {
                session.gate.invalidate();
                session.status = new Status(false, "RESOURCE_EPOCH_CHANGED", session.status.snapshot(),
                        session.status.revision(), session.status.contentHash());
                session.requested = true;
            }
        }
        if (session == null || session.reloading || !session.requested || session.busy
                || session.services.state() != ServerServices.State.READY) return;
        session.requested = false;
        long generation = session.gate.generation();
        final ValuationCapture.Captured inputs;
        try { inputs = ValuationCapture.capture(server); }
        catch (Exception failure) { fail(session, generation, failure); return; }
        Object capturedResources = server.getServerResources();
        session.busy = true;
        // Path and detached input data only; no Minecraft object is used by either worker.
        Path config = session.config;
        try {
            session.services.calculate(() -> prepare(inputs, config)).whenComplete((candidate, error) -> {
                if (error != null) {
                    session.busy = false;
                    fail(session, generation, error);
                } else if (!session.gate.matches(generation)) session.busy = false;
                else publish(session, candidate, generation, capturedResources);
            });
        } catch (RuntimeException rejected) {
            session.busy = false;
            fail(session, generation, rejected);
        }
    }
    private static Candidate prepare(ValuationCapture.Captured inputs, Path config) {
        try {
            var manifest = ValuationData.prepare(inputs.inputs(), ValuationConfig.load(config), List.of(), inputs.recipes(), inputs.brewing(), inputs.variants());
            return new Candidate(manifest, ValuationData.encode(manifest));
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    private static void publish(Session session, Candidate candidate, long generation, Object capturedResources) {
        ValuationStore.Gate gate = session.gate;
        long publishedAt = System.currentTimeMillis();
        try {
            session.services.tradeStorage(store -> ValuationStore.publish(store, candidate.payload(), publishedAt,
                    gate, generation)).whenComplete((snapshot, error) -> {
                session.busy = false;
                if (error != null) { fail(session, generation, error); return; }
                synchronized (gate) {
                    if (snapshot == null || !gate.matches(generation)) return;
                    session.cache.activate(snapshot, candidate.manifest());
                    session.snapshot = snapshot;
                    session.status = new Status(true, "VARIANT_SNAPSHOT_READY", snapshot.id(), snapshot.revision(), snapshot.hash());
                    session.readyStatus = session.status;
                    session.readyResources = capturedResources;
                }
                LOGGER.info("event=VALUATION_SNAPSHOT_READY revision={} snapshot={}", snapshot.revision(), snapshot.id());
            });
        } catch (RuntimeException rejected) {
            session.busy = false;
            fail(session, generation, rejected);
        }
    }
    /** Detached internal read seam; grant-authenticated public routing remains A9-04 work. */
    record Read(UUID snapshot, long capturedAt, long effectiveFrom, RecipeValues.Result value) {}
    static RecipeValues.Result cached(MinecraftServer server, String item, String components, long quantity,
                                        ca.emilybelnavis.mcmods.economy.api.EconomyCurrency currency, UUID fx) {
        if (!server.isSameThread()) throw new IllegalStateException("Valuation read requires session owner");
        var session = SESSIONS.get(server);
        if (session == null) return null;
        synchronized (session.gate) {
            if (!session.status.ready() || session.readyResources != server.getServerResources()) return null;
            return session.cache.cached(session.status.snapshot(), item, components, quantity, currency, fx);
        }
    }
    static java.util.concurrent.CompletionStage<Read> evaluate(MinecraftServer server, UUID retained,
            UUID retainedFx, String item, String components, long quantity,
            ca.emilybelnavis.mcmods.economy.api.EconomyCurrency currency) {
        if (!server.isSameThread()) throw new IllegalStateException("Valuation read requires session owner");
        var session = SESSIONS.get(server);
        if (session == null)
            return java.util.concurrent.CompletableFuture.failedStage(new IllegalStateException("VALUATION_NOT_READY"));
        if (retained != null && retainedFx == null) throw new IllegalArgumentException("Historical FX identity required");
        var gate = session.gate;
        var cache = session.cache;
        final ValuationStore.Snapshot current;
        final long generation;
        synchronized (gate) {
            if (retained == null && (!session.status.ready() || session.readyResources != server.getServerResources()))
                return java.util.concurrent.CompletableFuture.failedStage(new IllegalStateException("VALUATION_NOT_READY"));
            current = session.snapshot;
            generation = gate.generation();
        }
        long capturedAt = System.currentTimeMillis();
        return session.services.tradeStorage(store -> {
            var snapshot = retained == null ? current : ValuationStore.load(store, retained);
            if (snapshot == null) throw new IllegalArgumentException("SNAPSHOT_UNAVAILABLE");
            if (retained == null && !gate.matches(generation)) throw new IllegalStateException("VALUATION_NOT_READY");
            var fx = store.transaction(tx -> retained == null
                    ? ca.emilybelnavis.mcmods.economy.fx.FxService.at(tx::query, capturedAt)
                    : ca.emilybelnavis.mcmods.economy.fx.FxService.snapshot(tx::query, retainedFx));
            return new Read(snapshot.id(), capturedAt, snapshot.publishedMillis(),
                    cache.evaluate(snapshot, item, components, quantity, currency, fx));
        });
    }
    private static void fail(Session session, long generation, Throwable error) {
        synchronized (session.gate) {
            if (!session.gate.matches(generation)) return;
            var old = session.status;
            session.status = new Status(false, "VALUATION_RELOAD_REJECTED", old.snapshot(), old.revision(), old.contentHash());
        }
        LOGGER.warn("event=VALUATION_RELOAD_REJECTED detail={}", error.toString());
    }
    public static void stop(MinecraftServer server) {
        var session = SESSIONS.remove(server);
        if (session != null) session.gate.invalidate();
    }
    /** Session identity is opaque outside the lifecycle/mixin bridge. */
    public static final class Session {
        final ServerServices services;
        final Path config;
        final ValuationStore.Gate gate = new ValuationStore.Gate();
        final ValuationCache cache = new ValuationCache();
        volatile ValuationStore.Snapshot snapshot;
        volatile Status status = new Status(false, "STARTUP_PENDING", null, 0, null);
        volatile Object readyResources;
        Status readyStatus;
        int reloadCount;
        volatile boolean reloading;
        volatile boolean requested = true;
        boolean busy;
        Session(ServerServices services, Path config) { this.services = services; this.config = config; }
    }
}
