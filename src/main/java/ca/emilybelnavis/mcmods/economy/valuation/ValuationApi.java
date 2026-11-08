package ca.emilybelnavis.mcmods.economy.valuation;

import ca.emilybelnavis.mcmods.economy.api.ItemValuationIntegration;
import ca.emilybelnavis.mcmods.economy.foundation.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

/** Authenticated admission bridge. Only detached data enters bounded workers. */
public final class ValuationApi implements ItemValuationIntegration {
    private final MinecraftServer server;
    private final ServerServices services;
    private final ValuationRuntime.Session session;
    private record Sample(Result value,long epoch,long validUntil) {}
    private final Map<Request,Sample> sampled=new LinkedHashMap<>(16,.75f,true);
    private final Set<Request> pending=new HashSet<>();
    private ValuationApi(MinecraftServer server,ServerServices services,ValuationRuntime.Session session){this.server=server;this.services=services;this.session=session;}
    public static ItemValuationIntegration integration(MinecraftServer server,ServerServices services){
        var session=ValuationRuntime.require(server);
        if(session.services!=services)throw new SecurityException("Wrong valuation session");
        if(session.api==null)session.api=new ValuationApi(server,services,session);
        return session.api;
    }
    private void authenticate(PartyAuthorization.Grant grant){
        if(ValuationRuntime.require(server)!=session)throw new SecurityException("Expired valuation session");
        services.authenticateValuationRead(grant);
    }
    @Override public Result currentCached(PartyAuthorization.Grant grant,Request request){
        authenticate(grant);Objects.requireNonNull(request);
        if(!(request.selector() instanceof Current))throw new IllegalArgumentException("Cached reads require Current");
        var sample=sampled.get(request);
        var result=sample==null?null:sample.value();
        long now=System.currentTimeMillis();
        var status=ValuationRuntime.status(server);
        if(result!=null&&(!status.ready()||result.snapshot().isEmpty()||!result.snapshot().get().valuationSnapshot().equals(status.snapshot())
                ||sample.epoch()!=services.valuationStorageEpoch()||now>=sample.validUntil()||now<result.capturedAt().toEpochMilli()))result=null;
        if(result==null&&!pending.contains(request)&&pending.size()<32){
            pending.add(request);
            try{evaluate(grant,request).whenComplete((value,error)->pending.remove(request));}
            catch(RuntimeException rejected){pending.remove(request);}
        }
        return result==null?unavailable(request,Status.NOT_READY,"PENDING",Instant.now()):new Result(result.status(),result.reasonCode(),result.coverage(),result.identity(),result.quantity(),
            result.exactUnitMinor(),result.unitMoney(),result.totalMoney(),Instant.ofEpochMilli(now),result.effectiveFrom(),result.snapshot(),result.explanation());
    }
    @Override public CompletionStage<Result> evaluate(PartyAuthorization.Grant grant,Request request){
        authenticate(grant);Objects.requireNonNull(request);
        if(session.reads>=32)return CompletableFuture.completedStage(unavailable(request,Status.NOT_READY,"BACKPRESSURE",Instant.now()));
        UUID retained=null,fx=null;
        if(request.selector() instanceof Retained selected){retained=selected.valuationSnapshot();fx=selected.fxPublication();}
        Instant admitted=Instant.now();session.reads++;
        try{return ValuationRuntime.evaluate(server,retained,fx,request.identity().itemId(),request.identity().canonicalComponents(),request.quantity(),request.currency(),grant.actor())
            .handle((read,error)->{session.reads--;if(error!=null)return failure(request,error,admitted);
                var result=result(request,read);
                if(request.selector() instanceof Current){sampled.put(request,new Sample(result,read.storageEpoch(),read.validUntil()));
                    while(sampled.size()>64)sampled.remove(sampled.keySet().iterator().next());}
                return result;});
        }catch(RuntimeException error){session.reads--;return CompletableFuture.completedStage(failure(request,error,admitted));}
    }
    @Override public CompletionStage<List<SnapshotMetadata>> history(PartyAuthorization.Grant grant,Optional<UUID> before,int limit){
        authenticate(grant);Objects.requireNonNull(before);if(limit<1||limit>16)throw new IllegalArgumentException("History bound");
        if(session.reads>=32)return CompletableFuture.failedStage(new RejectedExecutionException("BACKPRESSURE"));
        session.reads++;
        try{return services.tradeStorage(grant.actor(),store->ValuationStore.history(store,before.orElse(null),limit)).thenApply(rows->{
            if(ValuationRuntime.require(server)!=session)throw new SecurityException("Expired valuation session");
            return rows.stream().map(row->new SnapshotMetadata(row.id(),row.revision(),Instant.ofEpochMilli(row.publishedMillis()),row.hash(),row.bytes())).toList();
        }).whenComplete((result,error)->session.reads--);
        }catch(RuntimeException error){session.reads--;return CompletableFuture.failedStage(error);}
    }
    static Result result(Request request,ValuationRuntime.Read read){
        var value=read.value();var manifest=read.manifest();var saved=read.snapshot();
        var ref=new SnapshotRef(saved.id(),saved.revision(),saved.hash(),manifest.configHash(),manifest.inputs().recipeHash(),manifest.providerHash(),manifest.version(),manifest.algorithm(),request.identity().adapterVersion(),
            read.fx()==null?null:read.fx().id(),read.fx()==null?null:Instant.ofEpochMilli(read.fx().effectiveMillis()));
        Optional<Explanation> explanation=Optional.empty();
        if(value.explanation()!=null)explanation=Optional.of(new Explanation(value.explanation().rootHash(),value.explanation().steps().stream().map(step->new Step(
            step.hash(),step.item(),step.kind(),step.recipe(),step.outputCount(),exact(step.exactUnitMinor()),exact(step.processingValue()),step.chosenItems(),step.inputHashes(),
            step.source()==null?"":step.source().kind(),step.source()==null?"":step.source().provider(),step.source()==null?"":step.source().revision())).toList(),value.explanation().truncated()));
        return new Result(Status.valueOf(value.status().name()),value.reason(),Coverage.valueOf(value.coverage().name()),request.identity(),request.quantity(),
            Optional.ofNullable(value.exactUnitMinor()).map(ValuationApi::exact),Optional.ofNullable(value.unitMoney()),Optional.ofNullable(value.totalMoney()),
            Instant.ofEpochMilli(read.capturedAt()),Optional.of(Instant.ofEpochMilli(saved.publishedMillis())),Optional.of(ref),explanation);
    }
    private static Exact exact(Rational value){return new Exact(value.numerator(),value.denominator());}
    private static Result failure(Request request,Throwable error,Instant admitted){
        while(error.getCause()!=null)error=error.getCause();
        String reason=error.getMessage();
        if("SNAPSHOT_UNAVAILABLE".equals(reason))return unavailable(request,Status.SNAPSHOT_UNAVAILABLE,reason,admitted);
        return unavailable(request,Status.NOT_READY,error instanceof RejectedExecutionException||error instanceof ca.emilybelnavis.mcmods.economy.storage.StorageException storage&&storage.kind()==ca.emilybelnavis.mcmods.economy.storage.StorageException.Kind.OVERLOAD?"BACKPRESSURE":"VALUATION_NOT_READY",admitted);
    }
    private static Result unavailable(Request request,Status status,String reason,Instant admitted){
        return new Result(status,reason,Coverage.SUPPORTED_PATH_WITH_EXCLUSIONS,request.identity(),request.quantity(),Optional.empty(),Optional.empty(),Optional.empty(),admitted,Optional.empty(),Optional.empty(),Optional.empty());
    }
    /** Capture the actual external caller here as well as through the service entry point. */
    public static Provider provider(MinecraftServer server,ServerServices services){
        Class<?> caller=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames->frames
            .map(StackWalker.StackFrame::getDeclaringClass).filter(type->type!=ValuationApi.class&&type!=ServerServices.class).findFirst().orElseThrow());
        var session=ValuationRuntime.require(server);if(session.services!=services)throw new SecurityException("Wrong provider session");
        var module=caller.getModule();
        if(!module.isNamed())throw new SecurityException("Provider requires a loaded named mod module");
        var namespaces=new TreeSet<String>();
        for(var info:ModList.get().getMods())if(info.getOwningFile().moduleName().equals(module.getName())
            &&net.neoforged.fml.loading.FMLLoader.getGameLayer().findModule(module.getName()).orElse(null)==module
            &&ModList.get().getModContainerById(info.getModId()).isPresent())namespaces.add(info.getModId());
        if(namespaces.isEmpty())throw new SecurityException("Provider is not a loaded mod");
        return new Provider(){
            private void owner(String id){ValuationData.itemId(id);if(!namespaces.contains(id.substring(0,id.indexOf(':'))))throw new SecurityException("Provider namespace mismatch");}
            public CompletionStage<SnapshotMetadata> register(Contribution contribution){Objects.requireNonNull(contribution);owner(contribution.providerId());return ValuationRuntime.contribute(server,session,convert(contribution),null);}
            public CompletionStage<SnapshotMetadata> remove(String id){owner(id);return ValuationRuntime.contribute(server,session,null,id);}
        };
    }
    static ValuationContributions.Provider convert(Contribution p){
        var rules=new TreeMap<String,VariantGraph.Rule>();p.variantRules().forEach((id,rule)->rules.put(id,new VariantGraph.Rule("SUM",rule.materials().stream().map(m->new VariantGraph.Term(m.item(),m.numerator(),m.denominator())).toList(),rule.contentCad())));
        return new ValuationContributions.Provider(p.providerId(),p.revision(),p.ownedItemIds(),p.bases().stream().map(ValuationApi::entry).toList(),
            p.exactSelectors().stream().map(s->new ValuationContributions.Selector(s.identity().itemId(),s.identity().canonicalComponents(),entry(s.value()))).toList(),p.derivationOptIns(),rules,p.trustedAdapterVersions());
    }
    private static ValuationConfig.Entry entry(Base e){return new ValuationConfig.Entry(e.item(),e.currency(),e.amount(),e.referenceCount());}
}
