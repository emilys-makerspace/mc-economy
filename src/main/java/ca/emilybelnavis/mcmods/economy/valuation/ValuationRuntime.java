package ca.emilybelnavis.mcmods.economy.valuation;

import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import net.minecraft.server.MinecraftServer;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/** Session-owned publication and detached read lifecycle. */
public final class ValuationRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ConcurrentHashMap<MinecraftServer, Session> SESSIONS = new ConcurrentHashMap<>();
    public record Status(boolean ready, String reason, UUID snapshot, long revision, String contentHash) {}
    public record Reload(Session session, long generation, Object oldResources, Status oldStatus) {}
    private record Candidate(ValuationData.Manifest manifest, String payload) {}
    private ValuationRuntime() {}

    public static void start(MinecraftServer server, ServerServices services, Path config) {
        SESSIONS.put(server, new Session(server,services, config));
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
            if(session.mutation!=null){var cancelled=session.mutation;session.mutation=null;session.proposed=null;
                server.execute(()->cancelled.completeExceptionally(new IllegalStateException("RELOAD_SUPERSEDED_PROVIDER_CHANGE")));}
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
                    if (reload.oldStatus().ready() && server.getServerResources() == reload.oldResources())
                        session.status = reload.oldStatus();
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
        try { inputs = ValuationCapture.capture(server, session.proposed == null ? session.providers : session.proposed); }
        catch (Exception failure) { fail(session, generation, failure); return; }
        Object capturedResources = server.getServerResources();
        final var candidateProviders = session.proposed == null ? session.providers : session.proposed;
        session.busy = true;
        // Path and detached input data only; no Minecraft object is used by either worker.
        Path config = session.config;
        try {
            session.services.calculate(() -> prepare(inputs, config, candidateProviders)).whenComplete((candidate, error) -> {
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
    private static Candidate prepare(ValuationCapture.Captured inputs, Path config, List<ValuationContributions.Provider> providers) {
        try {
            var manifest = ValuationData.prepareWithProviders(inputs.inputs(), ValuationConfig.load(config), List.of(), inputs.recipes(), inputs.brewing(), inputs.variants(), providers);
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
                java.util.concurrent.CompletableFuture<ca.emilybelnavis.mcmods.economy.api.ItemValuationIntegration.SnapshotMetadata> completed;
                synchronized (gate) {
                    if (snapshot == null || !gate.matches(generation)) return;
                    session.cache.activate(snapshot, candidate.manifest());
                    session.snapshot = snapshot;
                    session.status = new Status(true, "VARIANT_SNAPSHOT_READY", snapshot.id(), snapshot.revision(), snapshot.hash());
                    session.readyStatus = session.status;
                    session.readyResources = capturedResources;
                    session.providers = candidate.manifest().providers();
                    session.proposed = null;
                    completed=session.mutation;session.mutation=null;
                }
                if(completed!=null)completed.complete(metadata(snapshot));
                LOGGER.info("event=VALUATION_SNAPSHOT_READY revision={} snapshot={}", snapshot.revision(), snapshot.id());
            });
        } catch (RuntimeException rejected) {
            session.busy = false;
            fail(session, generation, rejected);
        }
    }
    record Read(ValuationStore.Snapshot snapshot, ValuationData.Manifest manifest, long capturedAt,
                ca.emilybelnavis.mcmods.economy.fx.EffectiveFxSnapshot fx, RecipeValues.Result value,long storageEpoch,long validUntil) {}
    static java.util.concurrent.CompletionStage<Read> evaluate(MinecraftServer server, UUID retained,
            UUID retainedFx, String item, String components, long quantity,
            ca.emilybelnavis.mcmods.economy.api.EconomyCurrency currency,
            ca.emilybelnavis.mcmods.economy.api.Identifiers.ActorId actor) {
        var session=require(server);
        final ValuationStore.Snapshot current;
        final long generation;
        synchronized(session.gate){
            if(retained==null&&(!session.status.ready()||session.readyResources!=server.getServerResources()))
                return java.util.concurrent.CompletableFuture.failedStage(new IllegalStateException("VALUATION_NOT_READY"));
            current=session.snapshot;generation=session.gate.generation();
        }
        long capturedAt=System.currentTimeMillis();
        record Admission(ValuationStore.Snapshot snapshot,ca.emilybelnavis.mcmods.economy.fx.EffectiveFxSnapshot fx,long storageEpoch,long validUntil){}
        return session.services.tradeStorage(actor,store->{
            var snapshot=retained==null?current:ValuationStore.load(store,retained);
            if(snapshot==null)throw new IllegalArgumentException("SNAPSHOT_UNAVAILABLE");
            if(retained==null&&!session.gate.matches(generation))throw new IllegalStateException("VALUATION_NOT_READY");
            var fx=store.transaction(tx->retained==null
                ?ca.emilybelnavis.mcmods.economy.fx.FxService.at(tx::query,capturedAt)
                :ca.emilybelnavis.mcmods.economy.fx.FxService.snapshot(tx::query,retainedFx));
            Long next=retained==null?store.transaction(tx->ca.emilybelnavis.mcmods.economy.fx.FxService.nextEffectiveMillis(tx::query,capturedAt,Long.MAX_VALUE)):null;
            return new Admission(snapshot,fx,session.services.valuationStorageEpoch(),next==null?Long.MAX_VALUE:next);
        }).thenCompose(admission->session.services.calculate(actor,()->{
            var manifest=session.cache.retain(admission.snapshot());
            return new Read(admission.snapshot(),manifest,capturedAt,admission.fx(),
                session.cache.evaluate(admission.snapshot(),item,components,quantity,currency,admission.fx()),admission.storageEpoch(),admission.validUntil());
        })).thenApply(read->{
            if(SESSIONS.get(server)!=session||retained==null&&!session.gate.matches(generation))throw new IllegalStateException("VALUATION_NOT_READY");
            return read;
        });
    }
    static Session require(MinecraftServer server){
        if(!server.isSameThread())throw new IllegalStateException("Valuation admission requires server thread");
        var session=SESSIONS.get(server);if(session==null)throw new IllegalStateException("SESSION_UNAVAILABLE");return session;
    }
    static ca.emilybelnavis.mcmods.economy.api.ItemValuationIntegration.SnapshotMetadata metadata(ValuationStore.Snapshot snapshot){
        return new ca.emilybelnavis.mcmods.economy.api.ItemValuationIntegration.SnapshotMetadata(snapshot.id(),snapshot.revision(),java.time.Instant.ofEpochMilli(snapshot.publishedMillis()),snapshot.hash(),ValuationConfig.bytes(snapshot.payload()));
    }
    static java.util.concurrent.CompletionStage<ca.emilybelnavis.mcmods.economy.api.ItemValuationIntegration.SnapshotMetadata> contribute(
            MinecraftServer server,Session expected,ValuationContributions.Provider provider,String remove){
        var session=require(server);if(session!=expected)throw new SecurityException("Expired provider session");
        synchronized(session.gate){
            if(session.mutation!=null||session.busy||session.reloading)throw new java.util.concurrent.RejectedExecutionException("VALUATION_BUSY");
            var next=new java.util.TreeMap<String,ValuationContributions.Provider>();for(var old:session.providers)next.put(old.providerId(),old);
            if(provider!=null){
                var prior=next.get(provider.providerId());
                if(prior!=null&&prior.revision().equals(provider.revision())&&!prior.equals(provider))throw new IllegalArgumentException("Provider revision reused with changed data");
                next.put(provider.providerId(),provider);
            }else if(next.remove(remove)==null)throw new IllegalArgumentException("Provider not registered");
            session.mutationStatus=session.status;session.mutationResources=server.getServerResources();
            session.proposed=List.copyOf(next.values());session.mutation=new java.util.concurrent.CompletableFuture<>();
            session.gate.invalidate();session.status=new Status(false,"PROVIDER_PUBLICATION_PENDING",session.status.snapshot(),session.status.revision(),session.status.contentHash());
            session.requested=true;return session.mutation;
        }
    }
    private static void fail(Session session, long generation, Throwable error) {
        java.util.concurrent.CompletableFuture<ca.emilybelnavis.mcmods.economy.api.ItemValuationIntegration.SnapshotMetadata> pending;
        synchronized(session.gate){
            if(!session.gate.matches(generation))return;
            var old=session.status;
            session.status=new Status(false,"VALUATION_RELOAD_REJECTED",old.snapshot(),old.revision(),old.contentHash());
            pending=session.mutation;session.mutation=null;session.proposed=null;
            if(pending!=null&&session.mutationStatus!=null&&session.server.getServerResources()==session.mutationResources)
                session.status=session.mutationStatus;
        }
        if(pending!=null)pending.completeExceptionally(error);
        LOGGER.warn("event=VALUATION_RELOAD_REJECTED detail={}",error.toString());
    }
    public static void stop(MinecraftServer server) {
        var session = SESSIONS.remove(server);
        if (session != null) {session.gate.invalidate();if(session.mutation!=null)session.mutation.completeExceptionally(new IllegalStateException("SESSION_ENDED"));}
    }
    /** Session identity is opaque outside the lifecycle/mixin bridge. */
    public static final class Session {
        final ServerServices services;
        final MinecraftServer server;
        final Path config;
        ValuationApi api;
        int reads;
        List<ValuationContributions.Provider> providers=List.of(),proposed;
        Status mutationStatus;
        Object mutationResources;
        java.util.concurrent.CompletableFuture<ca.emilybelnavis.mcmods.economy.api.ItemValuationIntegration.SnapshotMetadata> mutation;
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
        Session(MinecraftServer server,ServerServices services, Path config) { this.server=server;this.services = services; this.config = config; }
    }
}
