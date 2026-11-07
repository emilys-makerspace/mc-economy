package ca.emilybelnavis.mcmods.economy.simulation.macro;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityFacts;
import ca.emilybelnavis.mcmods.economy.simulation.plan.ActivationModelPlans;
import ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes;
import ca.emilybelnavis.mcmods.economy.simulation.window.*;
import java.util.*;

/** One dormant-until-explicit-activation stage-zero provider; no independent scheduler. */
public final class MacroProvider implements DeadlineDispatcher.Provider {
    public static final String PROVIDER="economy:a8/macro-rates";
    private static final List<String> TARGETS=targets();
    @Override public String id(){return PROVIDER;}
    @Override public Map<Integer,String> versions(){return Map.of(1,"macro-rates-v1");}
    public static DeadlineDispatcher.Event event(WorldId world,long boundary){return new DeadlineDispatcher.Event(WindowFacts.stable("macro-event|"+world.value()+"|"+boundary),PROVIDER,boundary,0,1);}
    public static UUID windowId(WorldId world,long boundary){return WindowFacts.stable("open|"+world.value()+"|"+boundary);}
    @Override public DeadlineDispatcher.Event next(EconomicClock.Queries reads,long originMillis,DeadlineDispatcher.Event completed) {
        var world=world(reads);var activation=MacroActivation.read(reads::query,world);if(activation.isEmpty())return null;
        var a=activation.get();var prime=CurrencyPrimes.activation(reads::query,world).orElseThrow(()->new IllegalStateException("Macro prime activation missing"));
        if(prime.originMillis()!=originMillis||!prime.id().equals(a.id())||prime.firstEventMillis()!=a.firstEffectiveMillis())throw new IllegalStateException("Macro activation calendar mismatch");
        long next=a.firstEffectiveMillis();
        if(completed!=null) {
            if(!completed.equals(event(world,completed.effectiveMillis()))||completed.effectiveMillis()<next||(completed.effectiveMillis()-next)%ActivityFacts.DAY_MILLIS!=0)throw new IllegalStateException("Macro completed event identity");
            next=Math.addExact(completed.effectiveMillis(),ActivityFacts.DAY_MILLIS);
        }
        return event(world,next);
    }
    @Override public List<String> targets(DeadlineDispatcher.Event event,String exclusiveCursor,int limit,EconomicClock.Queries reads) {
        check(event,reads);if(limit<1||limit>16)throw new IllegalArgumentException("Macro target page bound");String after=exclusiveCursor==null?"":exclusiveCursor;
        return TARGETS.stream().filter(target->target.compareTo(after)>0).limit(limit).toList();
    }
    @Override public EconomicClock.Output prepare(DeadlineDispatcher.Event event,String target,EconomicClock.Queries queries) {
        check(event,queries);if(!TARGETS.contains(target))throw new IllegalArgumentException("Unknown macro target");
        JournalEngine.ReadView reads=queries::query;var world=world(queries);var a=MacroActivation.read(reads,world).orElseThrow();
        var prime=CurrencyPrimes.activation(reads,world).orElseThrow();UUID child=DeadlineDispatcher.childOperation(world,event,target),window=windowId(world,event.effectiveMillis());
        JournalEngine.Commit prepared;
        if(target.equals("00-open")) {
            var policy=MacroControls.current(reads,world,event.effectiveMillis());
            long observed=Long.parseLong(reads.query("SELECT observed_wall_ms FROM financial_calendar WHERE singleton=1",List.of(),1).getFirst().get("observed_wall_ms"));
            prepared=WindowStore.prepareOpen(reads,world,child,event.effectiveMillis(),observed,policy.config().id(),prime.fxSnapshot());
        }else if(target.startsWith("01-close/"))prepared=WindowStore.prepareClose(reads,child,window,EconomyCurrency.fromCode(target.substring("01-close/".length())));
        else if(target.equals("02-plan")) {
            var policy=MacroControls.current(reads,world,event.effectiveMillis());var prior=FxService.at(reads,event.effectiveMillis()-1);
            if(prior==null||!prior.world().equals(world)||prior.reference()!=prime.reference())throw new IllegalStateException("Macro prior FX unavailable");
            var automatic=ActivationModelPlans.prepare(reads,world,child,a.id(),window,prime.reference(),prior.rates(),policy.config());
            var selected=MacroPlans.prepare(reads,world,child,window,policy,MacroPlans.automatic(automatic));prepared=compose(automatic,selected);
        }else if(target.startsWith("10-prime/")) {
            var selected=Objects.requireNonNull(MacroPlans.read(reads,window),"Frozen macro plan missing");
            prepared=CurrencyPrimes.prepare(reads,world,child,window,EconomyCurrency.fromCode(target.substring("10-prime/".length())),CurrencyPrimes.Mode.valueOf(selected.mode().name()));
        }else if(target.equals("20-fx")) {
            var selected=Objects.requireNonNull(MacroPlans.read(reads,window),"Frozen macro plan missing");
            prepared=EffectiveFxStore.prepare(reads,world,child,PROVIDER,event.id(),window,EffectiveFxSnapshot.Mode.valueOf(selected.mode().name()));
        }else prepared=compose(CurrencyPrimes.prepareCompletion(reads,world,child,window),MacroPlans.prepareCompletion(reads,world,child,window));
        return new EconomicClock.Output(target+"|"+window,prepared.projections(),prepared.records());
    }
    private static JournalEngine.Commit compose(JournalEngine.Commit first,JournalEngine.Commit second){var records=new ArrayList<>(first.records());records.addAll(second.records());return new JournalEngine.Commit(records,second.result(),tx->{first.projections().apply(tx);second.projections().apply(tx);});}
    private static void check(DeadlineDispatcher.Event event,EconomicClock.Queries reads) {
        var world=world(reads);var activation=MacroActivation.read(reads::query,world).orElseThrow(()->new IllegalStateException("Macro provider is dormant"));
        if(!event.equals(event(world,event.effectiveMillis()))||event.effectiveMillis()<activation.firstEffectiveMillis()||(event.effectiveMillis()-activation.firstEffectiveMillis())%ActivityFacts.DAY_MILLIS!=0)throw new IllegalArgumentException("Macro event identity");
    }
    private static WorldId world(EconomicClock.Queries reads){return new WorldId(UUID.fromString(reads.query("SELECT world_id FROM foundation_metadata WHERE singleton=1",List.of(),1).getFirst().get("world_id")));}
    private static List<String> targets(){var targets=new ArrayList<String>();targets.add("00-open");for(var c:EconomyCurrency.all())targets.add("01-close/"+c.code());targets.add("02-plan");for(var c:EconomyCurrency.all())targets.add("10-prime/"+c.code());targets.add("20-fx");targets.add("30-complete");targets.sort(String::compareTo);if(targets.size()!=26)throw new IllegalStateException("Frozen macro target manifest");return List.copyOf(targets);}
}
