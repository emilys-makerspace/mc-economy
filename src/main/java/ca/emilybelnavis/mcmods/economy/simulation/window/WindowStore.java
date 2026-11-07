package ca.emilybelnavis.mcmods.economy.simulation.window;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.simulation.activity.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.util.*;
import static ca.emilybelnavis.mcmods.economy.simulation.window.WindowFacts.*;

/** Explicit worker turns for future provider use. Nothing schedules or publishes rates here. */
public final class WindowStore {
    private WindowStore() {}
    private static final Journal.Kind OPEN_KIND=new Journal.Kind("economy:a8","economy:a8/window/open");
    private static final Journal.Kind CLOSE_KIND=new Journal.Kind("economy:a8","economy:a8/window/close");

    /** Freeze the source frontier and seal all currencies atomically before any detached currency close. */
    public static Open open(FoundationStore store,long boundaryMillis,long observedMillis,UUID config,UUID anchor) {
        var world=store.snapshot().worldId(); UUID id=stable("open|"+world.value()+"|"+boundaryMillis);
        var retained=store.transaction(tx->readOpen(tx::query,id));
        if(retained!=null) {
            if(!retained.config().equals(config)||!retained.anchor().equals(anchor))throw new IllegalArgumentException("Window identity conflict");
            return retained;
        }
        long frontier=store.lastCommittedSequence();
        Open open=store.transaction(tx->{
            var state=ActivityStore.activation(tx::query);
            if(state==null||!state.ready())throw unavailable("Activity capacity seed is incomplete");
            var seed=tx.query("SELECT MAX(accounting_ms) AS ready_ms FROM activity_batches WHERE seed=1",List.of(),1);
            String ready=seed.getFirst().get("ready_ms");
            if(ready==null)throw unavailable("Activity seed evidence is missing");
            long coverageMillis=Math.max(state.activatedMillis(),Long.parseLong(ready));
            long elapsed=Math.subtractExact(coverageMillis,state.originMillis());
            long first=Math.addExact(elapsed/ActivityFacts.DAY_MILLIS,elapsed%ActivityFacts.DAY_MILLIS==0?0:1);
            var value=new Open(id,world,state.originMillis(),boundaryMillis,first,frontier,config,anchor,stateSources(tx::query),EconomyCurrency.all());
            validateOpen(tx::query,value,observedMillis);
            return value;
        });
        String payload=encode(open);
        commit(store,OPEN_KIND,id,world,payload,(intent,reads)->{
            validateOpen(reads,open,observedMillis);
            long current=Long.parseLong(reads.query("SELECT COALESCE(MAX(sequence),0) AS frontier FROM journal_transactions",List.of(),1).getFirst().get("frontier"));
            if(current!=frontier)throw unavailable("Window source frontier changed");
            return new JournalEngine.Commit(List.of(new Journal.Record(OPEN,1,payload)),new Journal.Result(1,"WINDOW_OPENED",id.toString()),tx->{
                tx.execute("INSERT INTO macro_window_opens VALUES(?,?,?,?,?,?)",List.of(id.toString(),Long.toString(open.day()),Long.toString(boundaryMillis),Long.toString(frontier),id.toString(),payload));
                tx.execute("UPDATE activity_activation SET sealed_through_day=? WHERE singleton=1",List.of(Long.toString(open.day())));
            });
        });
        return store.transaction(tx->readOpen(tx::query,id));
    }
    private static void validateOpen(JournalEngine.ReadView reads,Open open,long observedMillis) {
        if(open.boundaryMillis()>observedMillis)throw unavailable("Cannot close an uncompleted financial day");
        var state=ActivityStore.activation(reads);
        if(state==null||!state.ready()||!state.world().equals(open.world())||state.originMillis()!=open.originMillis())throw unavailable("Activity manifest unavailable");
        var prior=reads.query("SELECT payload FROM macro_window_opens ORDER BY day_index DESC LIMIT 1",List.of(),1);
        long expected=open.firstDay();
        if(!prior.isEmpty()) {
            var previous=decodeOpen(prior.getFirst().get("payload")); expected=Math.addExact(previous.day(),1);
            if(previous.firstDay()!=open.firstDay() || !previous.anchor().equals(open.anchor()))throw unavailable("Window coverage/anchor changed");
            int completed=reads.query("SELECT currency FROM macro_window_closes WHERE open_id=?",List.of(previous.id().toString()),11).size();
            if(completed!=previous.currencies().size())throw unavailable("Previous currency closes are incomplete");
        }
        if(open.day()!=expected||state.sealedThroughDay()>=open.day())throw unavailable("Window close must be sequential");
    }
    public static Snapshot close(FoundationStore store,UUID openId,EconomyCurrency currency) {
        var retained=store.transaction(tx->read(tx::query,openId,currency));if(retained!=null)return retained;
        var snapshot=store.transaction(tx->{
            var open=readOpen(tx::query,openId);if(open==null)throw unavailable("Window open is missing");
            var state=ActivityStore.activation(tx::query);
            if(state==null||state.sealedThroughDay()!=open.day())throw unavailable("Window seal is not current");
            var amounts=new EnumMap<ActivityFacts.Kind,Amount>(ActivityFacts.Kind.class);
            for(var row:tx.query("SELECT kind,count,minor FROM activity_day_counters WHERE day_index=? AND currency=? ORDER BY kind",
                    List.of(Long.toString(open.day()),currency.name()),11)) {
                amounts.put(ActivityFacts.Kind.valueOf(row.get("kind")),new Amount(Long.parseLong(row.get("count")),Long.parseLong(row.get("minor"))));
            }
            var capacity=tx.query("SELECT free_minor FROM activity_capacity_totals_history WHERE currency=? AND accounting_ms<? AND journal_sequence<=? ORDER BY accounting_ms DESC,journal_sequence DESC LIMIT 1",
                    List.of(currency.name(),Long.toString(open.boundaryMillis()),Long.toString(open.frontier())),1);
            // Ready seed proves absence is genuine zero, not an unobserved capacity source.
            long free=capacity.isEmpty()?0:Long.parseLong(capacity.getFirst().get("free_minor"));
            var rows=tx.query("SELECT payload FROM macro_window_closes WHERE currency=? AND day_index>=? AND day_index<? ORDER BY day_index",
                    List.of(currency.name(),Long.toString(Math.max(open.firstDay(),open.day()-13)),Long.toString(open.day())),13);
            var days=new ArrayList<Day>();
            for(var row:rows){var previous=decodeSnapshot(row.get("payload"));days.add(previous.days().getLast());}
            days.add(new Day(open.day(),amounts,free));return new Snapshot(open,currency,days);
        });
        String payload=encode(snapshot);
        commit(store,CLOSE_KIND,snapshot.id(),snapshot.open().world(),payload,(intent,reads)->{
            if(!snapshot.open().equals(readOpen(reads,openId)))throw unavailable("Window open changed");
            return new JournalEngine.Commit(List.of(new Journal.Record(CLOSE,1,payload)),new Journal.Result(1,"WINDOW_CLOSED",snapshot.hash()),tx->
                    tx.execute("INSERT INTO macro_window_closes VALUES(?,?,?,?,?,?)",List.of(currency.name(),Long.toString(snapshot.open().day()),openId.toString(),snapshot.id().toString(),payload,snapshot.hash())));
        });
        return store.transaction(tx->read(tx::query,openId,currency));
    }
    public static Open readOpen(JournalEngine.ReadView reads,UUID id) {
        var rows=reads.query("SELECT payload FROM macro_window_opens WHERE id=?",List.of(id.toString()),1);
        return rows.isEmpty()?null:decodeOpen(rows.getFirst().get("payload"));
    }
    public static Snapshot read(JournalEngine.ReadView reads,UUID openId,EconomyCurrency currency) {
        var rows=reads.query("SELECT payload FROM macro_window_closes WHERE open_id=? AND currency=?",List.of(openId.toString(),currency.name()),1);
        return rows.isEmpty()?null:decodeSnapshot(rows.getFirst().get("payload"));
    }
    private static String stateSources(JournalEngine.ReadView reads) { return reads.query("SELECT sources FROM activity_activation WHERE singleton=1",List.of(),1).getFirst().get("sources"); }
    private static void commit(FoundationStore store,Journal.Kind kind,UUID id,WorldId world,String payload,JournalEngine.Handler handler) {
        var actor=new SystemActorId(stable("actor|"+world.value()));
        var request=new Journal.CanonicalRequest(1,Map.of("payload",payload));
        var registration=new JournalEngine.Registration(1,(intent,reads)->{
            if(!request.equals(intent.request()))throw new IllegalArgumentException("Window request");return handler.prepare(intent,reads);
        });
        var engine=new JournalEngine(store,(a,k)->actor.equals(a)&&kind.equals(k),Map.of(kind,registration));
        var outcome=engine.submit(new Journal.Intent(new OperationId(id),new RequestId(id),actor,kind,request,null,null));
        if(outcome.status()!=Journal.Status.COMMITTED)throw unavailable("Window commit failed: "+outcome.result().code());
    }
    private static StorageException unavailable(String reason) { return new StorageException(StorageException.Kind.ECONOMIC_CATCH_UP,reason); }
}
