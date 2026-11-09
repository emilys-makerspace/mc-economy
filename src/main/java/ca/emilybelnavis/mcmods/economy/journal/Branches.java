package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;

/** Worker-owned branch projection. All authority is supplied by internal server adapters, never packets. */
public final class Branches {
    public static final String FACT="economy:village_branch", PLAN_PART="economy:village_plan_part";
    public enum Action { OBSERVE, PROGRESS, ACTIVATE, REMOVE, PAUSE, RETRY, FILL;
        public Journal.Kind kind(){return new Journal.Kind("economy:branches","economy:"+name().toLowerCase(Locale.ROOT));}
    }
    @FunctionalInterface public interface Authority { boolean allows(Journal.Intent intent); }
    public record View(UUID id,UUID village,int slot,String state,String reason,long revision,boolean paused,
                       VillagePlanV1 plan,List<Map<String,String>> anchors,BranchPlanV1 providerChoice) {
        public View { anchors=List.copyOf(anchors); }
        public BranchPlanV1 choice(){if(providerChoice!=null)return providerChoice;return plan.branches().stream().filter(b->b.slot()==slot).findFirst().orElseThrow();}
    }
    private Branches(){}
    public static Journal.CanonicalRequest observeRequest(VillagePlanV1 plan){
        return new Journal.CanonicalRequest(1,Map.of("source",plan.source().key(),"digest",plan.digest()));
    }
    public static Journal.CanonicalRequest request(UUID branch,long revision,Map<String,String> extra){
        if(revision<1)throw new IllegalArgumentException("Branch revision");
        var f=new TreeMap<>(extra);f.put("branch",branch.toString());f.put("revision",Long.toString(revision));
        return new Journal.CanonicalRequest(1,f);
    }
    public static Registration registration(Action action,WorldId world,Authority authority,VillagePlanV1 observed){
        return registration(action,world,authority,observed,Map.of());
    }
    public static Registration registration(Action action,WorldId world,Authority authority,VillagePlanV1 observed,
                                            Map<String,TerminalPolicy.Denomination> catalog){
        return new Registration(1,(intent,reads)->{
            if(!intent.kind().equals(action.kind())||!authority.allows(intent))return decline("BRANCH_ACCESS");
            if(action==Action.OBSERVE)return observe(world,intent,reads,Objects.requireNonNull(observed));
            var f=intent.request().fields();View view;
            try {view=find(reads,world,UUID.fromString(f.get("branch"))).orElse(null);
                if(view==null)return reject("BRANCH_ABSENT");
                if(view.revision()!=Long.parseLong(f.get("revision")))return reject("BRANCH_STALE");
                var extra=new TreeMap<>(f);extra.remove("branch");extra.remove("revision");
                if(!request(view.id(),view.revision(),extra).equals(intent.request()))return reject("BRANCH_REQUEST");
            }catch(IllegalArgumentException|NullPointerException bad){return reject("BRANCH_REQUEST");}
            if(action==Action.FILL)return fill(world,intent,reads,view);
            if(action==Action.ACTIVATE||action==Action.REMOVE)
                return hardware(action,world,intent,reads,view,catalog);
            if(action==Action.PAUSE){
                if(!f.keySet().equals(Set.of("branch","revision","paused"))||!Set.of("true","false").contains(f.get("paused")))return reject("BRANCH_REQUEST");
                boolean paused=Boolean.parseBoolean(f.get("paused"));
                return paused(world,intent,view,paused);
            }
            if(action==Action.RETRY){
                if(!f.keySet().equals(Set.of("branch","revision")))return reject("BRANCH_REQUEST");
                // A7-04 owns terrain and provider search adapters. Retain the original captured plan unchanged.
                if(!Set.of("SITE_PENDING","PROVIDER_PENDING").contains(view.state()))return reject("BRANCH_RETRY_STATE");
                return transition(world,intent,view,view.state(),view.state().equals("SITE_PENDING")?
                        "PLACEMENT_ADAPTER_UNAVAILABLE":"PROVIDER_RETRY_ADAPTER_UNAVAILABLE",view.paused(),tx->{});
            }
            if(!f.keySet().equals(Set.of("branch","revision","state","reason")))return reject("BRANCH_REQUEST");
            String state=f.get("state"),reason=f.get("reason");
            if(state.equals("REMOVED")&&view.anchors().stream().anyMatch(a->a.get("state").equals("ACTIVE")))return reject("BRANCH_SERVICE_STILL_ACTIVE");
            if(!allowed(view.state(),state))return reject("BRANCH_TRANSITION");
            if(reason.length()>256||!reason.matches("[A-Z][A-Z0-9_]{0,255}"))return reject("BRANCH_REASON");
            return transition(world,intent,view,state,reason,view.paused(),tx->{});
        });
    }
    private static Decision observe(WorldId world,Journal.Intent intent,ReadView reads,VillagePlanV1 plan){
        if(!observeRequest(plan).equals(intent.request()))return reject("BRANCH_PLAN_REQUEST");
        var id=BranchPlans.id(world,plan.source());
        if(!intent.actor().equals(BranchPlans.owner(id)))return decline("BRANCH_ACCESS");
        var prior=reads.query("SELECT plan_digest,plan,revision FROM village_plans WHERE world_id=? AND source_key=?",
                List.of(world.value().toString(),plan.source().key()),1);
        boolean capture=false;
        if(!prior.isEmpty()){
            if(prior.getFirst().get("plan_digest").equals(plan.digest()))return new Commit(List.of(new Journal.Record(PLAN_PART,1,id+"|0|1|")),
                    new Journal.Result(1,"BRANCH_PLAN_KNOWN",id.toString()),tx->{});
            var old=BranchPlans.decode(prior.getFirst().get("plan"));
            capture=old.captureStatus()==CaptureStatus.CAPTURE_PENDING&&plan.captureStatus()==CaptureStatus.CAPTURED
                    && old.source().equals(plan.source())&&old.buildingCount()==plan.buildingCount()&&old.style().equals(plan.style())
                    &&old.originalBounds().equals(plan.originalBounds())&&old.junctions().equals(plan.junctions())&&old.abandoned()==plan.abandoned();
            if(!capture)return reject("BRANCH_PLAN_CONFLICT");
        }
        byte[] encoded=BranchPlans.encode(plan).getBytes(StandardCharsets.UTF_8);
        if(encoded.length>65536)return reject("BRANCH_PLAN_JOURNAL_LIMIT");
        if(plan.branches().stream().anyMatch(b->Set.of("ACTIVE","REMOVED").contains(b.state().name())))return reject("BRANCH_INITIAL_STATE");
        for(var b:plan.branches()){
            var bid=BranchPlans.branchId(id,b.slot());
            for(var a:b.services())if(!a.generation().equals(BranchPlans.generation(bid,a.slot())))return reject("BRANCH_ANCHOR_IDENTITY");
            for(var a:b.desks())if(!a.generation().equals(BranchPlans.generation(bid,b.deskGenerationSlot(a.slot()))))return reject("BRANCH_ANCHOR_IDENTITY");
        }
        var records=new ArrayList<Journal.Record>();
        int total=Math.max(1,(encoded.length+8191)/8192);
        for(int i=0;i<total;i++)records.add(new Journal.Record(PLAN_PART,1,id+"|"+i+"|"+total+"|"+
                Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOfRange(encoded,i*8192,Math.min(encoded.length,(i+1)*8192)))));
        for(var branch:plan.branches())records.add(event(world,BranchPlans.branchId(id,branch.slot()),1,branch.state().name(),branch.reason(),intent.operationId()));
        boolean completing=capture;
        return new Commit(records,new Journal.Result(1,"BRANCH_PLAN_CAPTURED",id.toString()),tx->{
            if(completing){
                int n=tx.execute("UPDATE village_plans SET plan_digest=?,plan=?,revision=revision+1 WHERE world_id=? AND village_id=? AND plan_digest=? AND revision=?",
                        List.of(plan.digest(),BranchPlans.encode(plan),world.value().toString(),id.toString(),prior.getFirst().get("plan_digest"),prior.getFirst().get("revision")));
                if(n!=1)throw new IllegalStateException("Village capture changed");
            }else tx.execute("INSERT INTO village_plans VALUES(?,?,?,?,?,?)",List.of(world.value().toString(),id.toString(),plan.source().key(),plan.digest(),BranchPlans.encode(plan),"1"));
            for(var b:plan.branches()){
                var bid=BranchPlans.branchId(id,b.slot());
                tx.execute("INSERT INTO village_branches VALUES(?,?,?,?,?,?,?,?)",List.of(world.value().toString(),bid.toString(),id.toString(),Integer.toString(b.slot()),b.state().name(),b.reason(),"1","0"));
                insertEvent(tx,world,bid,1,b.state().name(),b.reason(),intent.operationId());
                for(var a:b.services())tx.execute("INSERT INTO village_branch_anchors VALUES(?,?,?,?,?,?,?,?,?,?)",List.of(world.value().toString(),bid.toString(),Integer.toString(a.slot()),plan.source().dimension(),Integer.toString(a.position().x()),Integer.toString(a.position().y()),Integer.toString(a.position().z()),a.generation().toString(),"0","PENDING"));
            }
        });
    }
    private static Decision hardware(Action action,WorldId world,Journal.Intent intent,ReadView reads,View v,
                                     Map<String,TerminalPolicy.Denomination> catalog) throws Exception {
        var f=intent.request().fields();
        if(!intent.actor().equals(BranchPlans.owner(v.id()))||!f.keySet().equals(Set.of("branch","revision","slot","generation","terminal_revision")))return decline("BRANCH_ACCESS");
        Map<String,String> row;UUID generation;long expected;
        try{int slot=Integer.parseInt(f.get("slot"));generation=UUID.fromString(f.get("generation"));expected=Long.parseLong(f.get("terminal_revision"));
            row=v.anchors().stream().filter(a->Integer.parseInt(a.get("slot"))==slot).findFirst().orElseThrow();
            if(!generation.toString().equals(row.get("generation")))return reject("BRANCH_GENERATION");
        }catch(IllegalArgumentException|NullPointerException|NoSuchElementException bad){return reject("BRANCH_REQUEST");}
        var anchor=new TerminalPolicy.Anchor(row.get("dimension"),Integer.parseInt(row.get("x")),Integer.parseInt(row.get("y")),Integer.parseInt(row.get("z")));
        var current=TerminalPolicy.raw(reads,world,anchor).orElse(null);
        List<Journal.Record> facts=new ArrayList<>();var effects=new ArrayList<Projection>();String anchorState;
        if(action==Action.REMOVE){
            if(row.get("state").equals("REMOVED"))return reject("BRANCH_ANCHOR_REMOVED");
            if(current!=null&&current.active()&&current.generation().equals(generation)){
                if(current.revision()!=expected||!current.owner().equals(intent.actor()))return reject("TERMINAL_STALE");
                var inner=inner(intent,TerminalPolicy.Action.REMOVE.kind(),TerminalPolicy.removeRequest(anchor,generation,expected));
                var d=TerminalPolicy.registration(TerminalPolicy.Action.REMOVE,world,Map.of(),(a,i,c,r)->i.equals(inner),(a,r)->false).handler().prepare(inner,reads);
                if(!(d instanceof Commit c))return d;facts.addAll(c.records());effects.add(c.projections());
            } else if(current!=null&&current.generation().equals(generation)&&current.revision()!=expected)return reject("TERMINAL_STALE");
            anchorState="REMOVED";
        }else{
            if(!Set.of("PHYSICAL_COMPLETE","ACTIVATION_PENDING").contains(v.state())||!row.get("state").equals("PENDING"))return reject("BRANCH_ACTIVATION_STATE");
            if(v.choice().institution()==null)return reject("BRANCH_PROVIDER_PENDING");
            int slot=Integer.parseInt(row.get("slot"));
            var kind=v.choice().services().stream().filter(a->a.slot()==slot).findFirst().orElseThrow().serviceKind();
            if(current==null){
                if(expected!=0)return reject("TERMINAL_STALE");
                var inner=inner(intent,TerminalPolicy.Action.PLACE.kind(),TerminalPolicy.placeRequest(anchor,generation,intent.actor(),kind,null,0));
                var d=TerminalPolicy.registration(TerminalPolicy.Action.PLACE,world,Map.of(),(a,i,c,r)->i.equals(inner),(a,r)->false).handler().prepare(inner,reads);
                if(!(d instanceof Commit c))return d;facts.addAll(c.records());effects.add(c.projections());
                current=new TerminalPolicy.Snapshot(world,anchor,generation,intent.actor(),kind,1,true,TerminalPolicy.Settings.disabled());
            }else if(current.kind()!=kind||!current.active()||!current.owner().equals(intent.actor())||!current.generation().equals(generation)||current.revision()!=expected||current.binding()!=null&&current.binding().bound())return reject("BRANCH_TERMINAL_CONFLICT");
            var bank=ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.require(new ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.InstitutionId(v.choice().institution()));
            var d=TerminalBanking.prepareGeneratedBinding(world,bank,current,reads,catalog);
            if(!(d instanceof Commit c))return d;facts.addAll(c.records());effects.add(c.projections());anchorState="ACTIVE";
        }
        String changed;
        if(action==Action.REMOVE){
            if(Set.of("CONFLICT","PLACING","RESERVED","CAPTURE_PENDING","PROVIDER_PENDING","SITE_PENDING").contains(v.state()))changed=v.state();
            else if(v.anchors().stream().allMatch(a->a==row||a.get("state").equals("REMOVED")))changed="REMOVED";
            else changed=v.anchors().stream().anyMatch(a->a!=row&&a.get("state").equals("PENDING"))?"ACTIVATION_PENDING":"ACTIVE";
        }else changed=v.anchors().stream().anyMatch(a->a!=row&&a.get("state").equals("PENDING"))?"ACTIVATION_PENDING":"ACTIVE";
        long terminalRevision=current==null||!current.generation().equals(generation)?Long.parseLong(row.get("terminal_revision")):
                current.revision()+(action==Action.ACTIVATE||current.active()?1:0);
        var decision=(Commit)transition(world,intent,v,changed,action==Action.REMOVE?"SERVICE_REMOVED":"HARDWARE_ACTIVATED",v.paused(),tx->{
            for(var effect:effects)effect.apply(tx);
            tx.execute("UPDATE village_branch_anchors SET terminal_revision=?,state=? WHERE world_id=? AND branch_id=? AND slot=?",
                    List.of(Long.toString(terminalRevision),anchorState,world.value().toString(),v.id().toString(),row.get("slot")));
        });
        facts.addAll(decision.records());
        return new Commit(facts,decision.result(),decision.projections());
    }
    private static Decision fill(WorldId world,Journal.Intent intent,ReadView reads,View view){
        var f=intent.request().fields();
        if(!f.keySet().equals(Set.of("branch","revision","snapshot","institution")))return reject("BRANCH_REQUEST");
        if(!view.state().equals("PROVIDER_PENDING")||view.choice().institution()!=null||!view.choice().required())return reject("BRANCH_PROVIDER_STATE");
        PlanningSnapshotV1 snapshot;
        try{snapshot=ca.emilybelnavis.mcmods.economy.village.BranchSelection.decodeSnapshot(f.get("snapshot"));}
        catch(IllegalArgumentException e){return reject("BRANCH_REQUEST");}
        if(snapshot.denomination()!=view.plan().snapshot().denomination())return reject("BRANCH_CAPTURED_DENOMINATION");
        var used=new HashSet<String>();
        for(var branch:view.plan().branches())if(branch.institution()!=null)used.add(branch.institution());
        for(var fill:reads.query("SELECT f.institution FROM village_provider_fills f JOIN village_branches b ON b.world_id=f.world_id AND b.branch_id=f.branch_id WHERE b.world_id=? AND b.village_id=?",List.of(world.value().toString(),view.village().toString()),3))used.add(fill.get("institution"));
        var candidates=snapshot.providers().stream().filter(p->!used.contains(p.institution())).filter(p->view.slot()!=0||p.primaryEligible()&&p.nativeCurrency()==snapshot.denomination()).toList();
        var selected=candidates.stream().filter(p->p.institution().equals(f.get("institution"))).findFirst().orElse(null);
        if(selected==null)return reject("BRANCH_PROVIDER_UNAVAILABLE");
        if(view.slot()!=0&&selected.nativeCurrency()==snapshot.denomination()&&candidates.stream().anyMatch(p->p.nativeCurrency()!=snapshot.denomination()))return reject("BRANCH_FOREIGN_PREFERENCE");
        return transition(world,intent,view,"SITE_PENDING","PROVIDER_FILLED",view.paused(),tx->tx.execute("INSERT INTO village_provider_fills VALUES(?,?,?,?,?,?)",List.of(world.value().toString(),view.id().toString(),selected.institution(),selected.nativeCurrency().name(),f.get("snapshot"),intent.operationId().value().toString())));
    }
    private static Journal.Intent inner(Journal.Intent i,Journal.Kind kind,Journal.CanonicalRequest request){return new Journal.Intent(i.operationId(),i.requestId(),i.actor(),kind,request,null,null);}
    private static boolean allowed(String from,String to){
        if(Set.of("REMOVED","OMITTED","CONFLICT").contains(from))return false;
        if(to.equals("CONFLICT")||to.equals("REMOVED"))return true;
        return switch(from){
            case "CAPTURE_PENDING"->Set.of("PROVIDER_PENDING","SITE_PENDING","RESERVED").contains(to);
            case "PROVIDER_PENDING"->false; // Provider choices need a separately versioned capture adapter.
            case "SITE_PENDING"->false; // Physical retry adapter is deliberately not installed in A7-02.
            case "RESERVED"->Set.of("PLACING","SITE_PENDING").contains(to);
            case "PLACING"->to.equals("PHYSICAL_COMPLETE");
            case "PHYSICAL_COMPLETE"->to.equals("ACTIVATION_PENDING");
            default->false;
        };
    }
    static Decision paused(WorldId world,Journal.Intent intent,View view,boolean paused){
        return staffingPause(world,intent,view,paused,tx->{});
    }
    static Decision staffingPause(WorldId world,Journal.Intent intent,View view,boolean paused,Projection effect){
        return transition(world,intent,view,view.state(),paused?"STAFFING_PAUSED":"STAFFING_RESUMED",paused,effect);
    }
    private static Decision transition(WorldId world,Journal.Intent intent,View view,String state,String reason,boolean paused,Projection effect){
        if(view.revision()==Long.MAX_VALUE)return reject("BRANCH_REVISION_LIMIT");
        long revision=view.revision()+1;
        return new Commit(List.of(event(world,view.id(),revision,state,reason,intent.operationId())),new Journal.Result(1,"BRANCH_"+state,view.id().toString()),tx->{
            effect.apply(tx);
            int n=tx.execute("UPDATE village_branches SET state=?,reason=?,revision=?,staffing_paused=? WHERE world_id=? AND branch_id=? AND revision=?",
                    List.of(state,reason,Long.toString(revision),paused?"1":"0",world.value().toString(),view.id().toString(),Long.toString(view.revision())));
            if(n!=1)throw new IllegalStateException("Branch revision changed");
            insertEvent(tx,world,view.id(),revision,state,reason,intent.operationId());
        });
    }
    private static void insertEvent(FoundationStore.Transaction tx,WorldId w,UUID branch,long revision,String state,String reason,OperationId op){
        tx.execute("INSERT INTO village_branch_events VALUES(?,?,?,?,?,?)",List.of(w.value().toString(),branch.toString(),Long.toString(revision),state,reason,op.value().toString()));
    }
    private static Journal.Record event(WorldId w,UUID branch,long revision,String state,String reason,OperationId op){
        return new Journal.Record(FACT,1,String.join("|",w.value().toString(),branch.toString(),Long.toString(revision),state,
                Base64.getUrlEncoder().withoutPadding().encodeToString(reason.getBytes(StandardCharsets.UTF_8)),op.value().toString()));
    }
    public static Optional<View> find(ReadView reads,WorldId world,UUID id){
        var rows=reads.query("SELECT b.*,v.plan FROM village_branches b JOIN village_plans v ON v.world_id=b.world_id AND v.village_id=b.village_id WHERE b.world_id=? AND b.branch_id=?",List.of(world.value().toString(),id.toString()),1);
        if(rows.isEmpty())return Optional.empty();
        var row=rows.getFirst();var plan=BranchPlans.decode(row.get("plan"));int slot=Integer.parseInt(row.get("slot"));
        var original=plan.branches().stream().filter(b->b.slot()==slot).findFirst().orElseThrow();
        var fills=reads.query("SELECT institution,native_currency FROM village_provider_fills WHERE world_id=? AND branch_id=?",List.of(world.value().toString(),id.toString()),1);
        BranchPlanV1 choice=null;
        if(!fills.isEmpty())choice=new BranchPlanV1(original.slot(),original.required(),original.size(),fills.getFirst().get("institution"),
                ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.valueOf(fills.getFirst().get("native_currency")),original.templateRevision(),original.transform(),original.bounds(),original.entrance(),original.services(),original.desks(),original.manifests(),State.SITE_PENDING,"PROVIDER_FILLED",original.placementRevision(),original.optionalRoll());
        var placement=BranchPlacement.latestChoice(reads,world,id);
        if(placement.isPresent())choice=placement.get();
        return Optional.of(new View(id,UUID.fromString(row.get("village_id")),slot,row.get("state"),row.get("reason"),Long.parseLong(row.get("revision")),row.get("staffing_paused").equals("1"),plan,
                reads.query("SELECT * FROM village_branch_anchors WHERE world_id=? AND branch_id=? ORDER BY slot",List.of(world.value().toString(),id.toString()),3),choice));
    }

    public static List<Map<String,String>> page(ReadView reads,WorldId world,String after){
        return reads.query("SELECT branch_id,state,reason,revision,staffing_paused FROM village_branches WHERE world_id=? AND branch_id>? ORDER BY branch_id LIMIT 16",List.of(world.value().toString(),after),16);
    }
    /** Generated binding remains tied to its captured provider, including explicit later required-provider fills. */
    public static boolean providerMatches(ReadView reads,WorldId world,UUID generation,String institution){
        var rows=reads.query("SELECT branch_id FROM village_branch_anchors WHERE world_id=? AND generation=?",List.of(world.value().toString(),generation.toString()),1);
        return rows.isEmpty()||find(reads,world,UUID.fromString(rows.getFirst().get("branch_id"))).map(v->institution.equals(v.choice().institution())).orElse(false);
    }
    /** Shared fresh financial admission gate for generated hardware, independent of cached block readiness. */
    public static boolean serviceUsable(ReadView reads,WorldId world,UUID generation){
        var rows=reads.query("SELECT a.state AS anchor_state,b.state AS branch_state FROM village_branch_anchors a JOIN village_branches b ON b.world_id=a.world_id AND b.branch_id=a.branch_id WHERE a.world_id=? AND a.generation=?",List.of(world.value().toString(),generation.toString()),1);
        return rows.isEmpty()||rows.getFirst().get("anchor_state").equals("ACTIVE")&&Set.of("ACTIVE","ACTIVATION_PENDING").contains(rows.getFirst().get("branch_state"));
    }
    public static void validateEvent(String payload){
        var f=payload.split("\\|",-1);if(f.length!=6)throw new IllegalArgumentException("Branch fact");
        UUID.fromString(f[0]);UUID.fromString(f[1]);UUID.fromString(f[5]);if(Long.parseLong(f[2])<1)throw new IllegalArgumentException("Branch revision");
        State.valueOf(f[3]);var reason=new String(Base64.getUrlDecoder().decode(f[4]),StandardCharsets.UTF_8);
        if(reason.length()>256)throw new IllegalArgumentException("Branch reason");
        if(!event(new WorldId(UUID.fromString(f[0])),UUID.fromString(f[1]),Long.parseLong(f[2]),f[3],reason,new OperationId(UUID.fromString(f[5]))).payload().equals(payload))throw new IllegalArgumentException("Branch canonical fact");
    }
    public static void validatePart(String payload){
        var f=payload.split("\\|",-1);if(f.length!=4)throw new IllegalArgumentException("Plan part");
        UUID id=UUID.fromString(f[0]);int part=Integer.parseInt(f[1]),total=Integer.parseInt(f[2]);byte[] bytes=Base64.getUrlDecoder().decode(f[3]);
        if(total<1||total>8||part<0||part>=total||bytes.length>8192||!payload.equals(id+"|"+part+"|"+total+"|"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)))throw new IllegalArgumentException("Plan part bounds/canonical");
    }
    private static Reject reject(String code){return new Reject(new Journal.Result(1,code,""));}
    private static Decline decline(String code){return new Decline(new Journal.Result(1,code,""));}
}
