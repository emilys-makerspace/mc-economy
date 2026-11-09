package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans.*;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** Internal server integration. No packet or NPC can construct this service or mint a live bootstrap lease. */
public final class BranchService {
    private final ServerServices services;
    private final FoundationStore store;
    BranchService(ServerServices services,FoundationStore store){this.services=services;this.store=store;}
    public record Pending(JournalService journal,Journal.Intent intent){
        public CompletionStage<Optional<Journal.Outcome>> lookup(){return journal.lookup(intent);}
        public JournalService.Submission submit(){return journal.submit(intent);}
    }
    public CompletionStage<Optional<Branches.View>> find(UUID branch){
        var world=services.worldId();return services.storage(()->store.transaction(tx->Branches.find(tx::query,world,branch)));
    }
    public CompletionStage<List<Map<String,String>>> page(String after){
        if(!after.isEmpty()&&!UUID.fromString(after).toString().equals(after))throw new IllegalArgumentException("Branch cursor");
        var world=services.worldId();return services.storage(()->store.transaction(tx->Branches.page(tx::query,world,after)));
    }
    public CompletionStage<List<Map<String,String>>> plans(String after){
        if(!after.isEmpty())UUID.fromString(after);var world=services.worldId();
        return services.storage(()->store.transaction(tx->tx.query("SELECT village_id,source_key,plan_digest,revision FROM village_plans WHERE world_id=? AND village_id>? ORDER BY village_id LIMIT 16",List.of(world.value().toString(),after),16)));
    }
    public CompletionStage<List<Map<String,String>>> history(UUID branch,long after){
        if(after<0)throw new IllegalArgumentException("Branch history cursor");var world=services.worldId();
        return services.storage(()->store.transaction(tx->tx.query("SELECT revision,state,reason,operation_id FROM village_branch_events WHERE world_id=? AND branch_id=? AND revision>? ORDER BY revision LIMIT 16",List.of(world.value().toString(),branch.toString(),Long.toString(after)),16)));
    }
    public CompletionStage<Optional<VillagePlanV1>> existingPlan(Source source){
        var world=services.worldId();return services.storage(()->store.transaction(tx->tx.query(
                "SELECT plan FROM village_plans WHERE world_id=? AND source_key=?",List.of(world.value().toString(),source.key()),1)
                .stream().map(row->BranchPlans.decode(row.get("plan"))).findFirst()));
    }
    public CompletionStage<Optional<BranchPlacement.Attempt>> placementAttempt(UUID branch){
        var world=services.worldId();return services.storage(()->store.transaction(tx->BranchPlacement.latest(tx::query,world,branch)));
    }
    /** Later-site effects remain detached and revision-scoped; the server adapter owns physical evidence. */
    public Pending placement(BranchPlacement.Action action,Branches.View view,long attempt,UUID operation,
            BranchPlanV1 choice,String reason,ActorId actor,AtomicBoolean live){
        var request=BranchPlacement.request(view.id(),view.revision(),attempt,operation,reason,choice);
        var id=UUID.nameUUIDFromBytes(("economy:branch-placement:v1|"+operation+"|"+action+"|"+view.revision()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var intent=new Journal.Intent(new OperationId(id),new RequestId(id),actor,action.kind(),request,null,null);
        var journal=services.journal((who,kind)->who.equals(actor)&&kind.equals(action.kind()),Map.of(action.kind(),
                BranchPlacement.registration(action,services.worldId(),candidate->live.get()&&candidate.equals(intent),choice)),null);
        return new Pending(journal,intent);
    }
    /** First ready observation captures denomination once; the worker CAS refuses later recapture. */
    public CompletionStage<Optional<Pending>> reconcilePlan(VillagePlanV1 source,String resourceRevision,long worldSeed){
        if(source.captureStatus()!=CaptureStatus.CAPTURE_PENDING)return java.util.concurrent.CompletableFuture.completedStage(Optional.of(observePlan(source)));
        return existingPlan(source.source()).thenCompose(prior->{
            if(prior.isPresent()&&prior.get().captureStatus()==CaptureStatus.CAPTURED)
                return java.util.concurrent.CompletableFuture.completedStage(Optional.of(observePlan(prior.get())));
            return planningSnapshot(resourceRevision,null).thenApply(snapshot->Optional.of(snapshot.isPresent()?observePlan(
                    ca.emilybelnavis.mcmods.economy.village.BranchSelection.capture(source,snapshot.get(),worldSeed)):observePlan(prior.orElse(source))));
        });
    }
    public CompletionStage<Optional<PlanningSnapshotV1>> planningSnapshot(String resourceRevision,ca.emilybelnavis.mcmods.economy.api.EconomyCurrency capturedDenomination){
        return services.storage(()->store.transaction(tx->{
            var denomination=RebaseSettlement.denomination(tx::query);
            if(denomination.isEmpty())return Optional.empty();
            var currency=capturedDenomination==null?denomination.get().currency():capturedDenomination;
            var providers=ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.banks().stream()
                    .filter(b->b.enabled()&&b.customerBank()&&b.capabilities().contains(ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.Capability.BANK_ACCOUNT))
                    .map(b->new Provider(b.id().key(),b.baseCurrency(),b.tier1())).toList();
            var reasons=providers.stream().anyMatch(b->b.primaryEligible()&&b.nativeCurrency()==currency)?List.<String>of():List.of("NO_LOCAL_PROVIDER");
            return Optional.of(new PlanningSnapshotV1(currency,denomination.get().revision(),TerminalBanking.CATALOG_REVISION,1,resourceRevision,providers,reasons));
        }));
    }
    CompletionStage<Optional<Pending>> retryProvider(Branches.View view,ActorId actor,long seed,AtomicBoolean live){
        if(!view.state().equals("PROVIDER_PENDING"))return java.util.concurrent.CompletableFuture.completedStage(Optional.of(operator(view,actor,null,live)));
        return planningSnapshot(view.plan().snapshot().resourceRevision(),view.plan().snapshot().denomination()).thenCompose(snapshot->{
            if(snapshot.isEmpty())return java.util.concurrent.CompletableFuture.completedStage(Optional.empty());
            var world=services.worldId();
            return services.storage(()->store.transaction(tx->{
                var used=new HashSet<String>();
                for(var b:view.plan().branches())if(b.institution()!=null)used.add(b.institution());
                for(var f:tx.query("SELECT f.institution FROM village_provider_fills f JOIN village_branches b ON b.world_id=f.world_id AND b.branch_id=f.branch_id WHERE b.world_id=? AND b.village_id=?",List.of(world.value().toString(),view.village().toString()),3))used.add(f.get("institution"));
                return ca.emilybelnavis.mcmods.economy.village.BranchSelection.selectProvider(snapshot.get(),view.slot(),used,seed,view.plan().source());
            })).thenApply(selected->{
                if(selected.isEmpty())return Optional.of(operator(view,actor,null,live));
                return Optional.of(prepare(Branches.Action.FILL,actor,UUID.randomUUID(),Branches.request(view.id(),view.revision(),Map.of("snapshot",ca.emilybelnavis.mcmods.economy.village.BranchSelection.encodeSnapshot(snapshot.get()),"institution",selected.get().institution())),live,null));
            });
        });
    }
    /** Detached loaded source plan; durable admission never changes its captured choices. */
    public Pending observePlan(VillagePlanV1 plan){
        var id=BranchPlans.id(services.worldId(),plan.source());
        return prepare(Branches.Action.OBSERVE,BranchPlans.owner(id),BranchPlans.operation(id,0,"observe/"+plan.digest(),1),Branches.observeRequest(plan),new AtomicBoolean(true),plan);
    }
    /** Future generation adapter supplies a revocable detached receipt check, never live Minecraft objects on worker. */
    public Pending observeBranch(Branches.View view,State state,String reason,AtomicBoolean receiptStillValid){
        return prepare(Branches.Action.PROGRESS,BranchPlans.owner(view.id()),operation(view,"progress/"+state),
                Branches.request(view.id(),view.revision(),Map.of("state",state.name(),"reason",reason)),receiptStillValid,null);
    }
    public Pending hardware(Branches.View view,int slot,UUID generation,long terminalRevision,boolean removal,AtomicBoolean live){
        var action=removal?Branches.Action.REMOVE:Branches.Action.ACTIVATE;
        var request=Branches.request(view.id(),view.revision(),Map.of("slot",Integer.toString(slot),"generation",generation.toString(),"terminal_revision",Long.toString(terminalRevision)));
        return prepare(action,BranchPlans.owner(view.id()),BranchPlans.operation(view.id(),slot,action.name()+"/"+view.revision()+"/"+terminalRevision+"/"+generation,1),request,live,null);
    }
    /** Caller must retain and recheck authenticated operator permission through acknowledgement. */
    Pending operator(Branches.View view,ActorId actor,Boolean paused,AtomicBoolean live){
        var action=paused==null?Branches.Action.RETRY:Branches.Action.PAUSE;
        return prepare(action,actor,UUID.randomUUID(),Branches.request(view.id(),view.revision(),paused==null?Map.of():Map.of("paused",paused.toString())),live,null);
    }
    private UUID operation(Branches.View view,String action){return BranchPlans.operation(view.id(),0,action+"/"+view.revision(),1);}
    private Pending prepare(Branches.Action action,ActorId actor,UUID operation,Journal.CanonicalRequest request,AtomicBoolean live,VillagePlanV1 plan){
        var intent=new Journal.Intent(new OperationId(operation),new RequestId(operation),actor,action.kind(),request,null,null);
        var catalog=action==Branches.Action.ACTIVATE?LedgerService.terminalCatalog():Map.<String,TerminalPolicy.Denomination>of();
        var journal=services.journal((who,kind)->who.equals(actor)&&kind.equals(action.kind()),Map.of(action.kind(),
                Branches.registration(action,services.worldId(),candidate->live.get()&&candidate.equals(intent),plan,catalog)),null);
        return new Pending(journal,intent);
    }
}
