package ca.emilybelnavis.mcmods.economy.simulation.plan;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityFacts;
import ca.emilybelnavis.mcmods.economy.simulation.model.*;
import ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes;
import ca.emilybelnavis.mcmods.economy.simulation.window.*;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.math.*;
import java.sql.*;
import java.util.*;

/** Prospective model horizon over immutable source days. Historical v1 windows/plans are untouched. */
public final class ActivationModelPlans {
    private ActivationModelPlans() {}
    public static final String INPUT="economy:a8/activation-model-input", OUTPUT="economy:a8/activation-model-output";
    private static final Journal.Kind KIND=new Journal.Kind("economy:a8","economy:a8/activation-model-plan");

    public static List<String> statements() {
        return List.of(
            "CREATE TABLE activation_model_plans(window_id TEXT PRIMARY KEY REFERENCES macro_window_opens(id),activation_id TEXT NOT NULL,first_day INTEGER NOT NULL CHECK(first_day>=0),operation_id TEXT NOT NULL UNIQUE,config_id TEXT NOT NULL,config_revision INTEGER NOT NULL CHECK(config_revision>0),input_payload TEXT NOT NULL,output_payload TEXT NOT NULL)",
            "CREATE INDEX activation_model_config ON activation_model_plans(config_id,config_revision)",
            "CREATE TRIGGER activation_model_plans_update BEFORE UPDATE ON activation_model_plans BEGIN SELECT RAISE(ABORT,'immutable activation model plan'); END",
            "CREATE TRIGGER activation_model_plans_delete BEFORE DELETE ON activation_model_plans BEGIN SELECT RAISE(ABORT,'immutable activation model plan'); END",
            "CREATE TRIGGER activation_model_plans_replace BEFORE INSERT ON activation_model_plans WHEN EXISTS(SELECT 1 FROM activation_model_plans WHERE window_id=NEW.window_id OR operation_id=NEW.operation_id) BEGIN SELECT RAISE(ABORT,'existing activation model plan'); END"
        );
    }
    public static RecordCatalog register(RecordCatalog catalog) {
        return catalog.with(new RecordCatalog.Key(INPUT,1),p->{var parts=parts(p);UUID.fromString(parts[0]);if(Long.parseLong(parts[1])<0)throw new IllegalArgumentException("Model horizon");ModelFacts.decodeInput(parts[2]);})
                .with(new RecordCatalog.Key(OUTPUT,1),p->{var parts=parts(p);UUID.fromString(parts[0]);UUID.fromString(parts[1]);ModelFacts.decodeOutput(parts[2]);});
    }
    public static UUID activation(JournalEngine.ReadView reads,UUID window) {
        var rows=reads.query("SELECT activation_id FROM activation_model_plans WHERE window_id=?",List.of(window.toString()),1);
        return rows.isEmpty()?null:UUID.fromString(rows.getFirst().get("activation_id"));
    }
    public static ModelPlanStore.Plan read(JournalEngine.ReadView reads,UUID window) {
        var rows=reads.query("SELECT input_payload,output_payload FROM activation_model_plans WHERE window_id=?",List.of(window.toString()),1);
        return rows.isEmpty()?null:new ModelPlanStore.Plan(ModelFacts.decodeInput(rows.getFirst().get("input_payload")),ModelFacts.decodeOutput(rows.getFirst().get("output_payload")));
    }
    public static ModelPlanStore.Plan prepare(FoundationStore store,UUID activation,UUID window,EconomyCurrency reference,
                                               Map<EconomyCurrency,BigDecimal> priorFx,MacroModel.Config config) {
        var world=store.snapshot().worldId();UUID operation=WindowFacts.stable("activation-plan|"+activation+"|"+window);
        var input=store.transaction(tx->input(tx::query,world,activation,window,reference,priorFx,config));
        var retained=store.transaction(tx->read(tx::query,window));
        if(retained!=null){if(!retained.input().equals(input)||!activation.equals(store.transaction(tx->activation(tx::query,window))))throw new IllegalArgumentException("Captured activation model conflict");return retained;}
        var request=new Journal.CanonicalRequest(1,Map.of("input",WindowFacts.digest(ModelFacts.encode(input))));
        var actor=new SystemActorId(WindowFacts.stable("activation-plan-actor|"+world.value()));
        var registration=new JournalEngine.Registration(1,(i,r)->{
            if(!i.request().equals(request))throw new IllegalArgumentException("Model canonical request");
            return prepare(r,world,operation,activation,window,reference,priorFx,config);
        });
        var result=new JournalEngine(store,(a,k)->a.equals(actor)&&k.equals(KIND),Map.of(KIND,registration))
                .submit(new Journal.Intent(new OperationId(operation),new RequestId(operation),actor,KIND,request,null,null));
        if(result.status()!=Journal.Status.COMMITTED)throw new IllegalArgumentException("Activation model unavailable: "+result.result().code());
        return store.transaction(tx->read(tx::query,window));
    }
    /** One bounded future provider target; operation is the actual dispatcher child identity. */
    public static JournalEngine.Commit prepare(JournalEngine.ReadView reads,WorldId world,UUID operation,UUID activation,UUID window,
                                                EconomyCurrency reference,Map<EconomyCurrency,BigDecimal> priorFx,MacroModel.Config config) {
        var input=input(reads,world,activation,window,reference,priorFx,config);
        if(read(reads,window)!=null)throw new IllegalArgumentException("Activation model already captured");
        var output=MacroModel.compute(input);String in=ModelFacts.encode(input),out=ModelFacts.encode(output);
        var a=CurrencyPrimes.activation(reads,world).orElseThrow();long first=(a.effectiveMillis()-a.originMillis())/ActivityFacts.DAY_MILLIS;
        return new JournalEngine.Commit(List.of(new Journal.Record(INPUT,1,activation+"|"+first+"|"+in),new Journal.Record(OUTPUT,1,activation+"|"+window+"|"+out)),
                new Journal.Result(1,"ACTIVATION_MODEL_PLANNED",window.toString()),tx->tx.execute("INSERT INTO activation_model_plans VALUES(?,?,?,?,?,?,?,?)",
                    List.of(window.toString(),activation.toString(),Long.toString(first),operation.toString(),config.id().toString(),Integer.toString(config.revision()),in,out)));
    }
    private static MacroModel.Input input(JournalEngine.ReadView reads,WorldId world,UUID activation,UUID window,EconomyCurrency reference,
                                          Map<EconomyCurrency,BigDecimal> fx,MacroModel.Config config) {
        var a=CurrencyPrimes.activation(reads,world).orElseThrow(()->new IllegalArgumentException("Activation unavailable"));
        var open=WindowStore.readOpen(reads,window);
        if(!a.id().equals(activation)||(!a.config().equals(config.id())&&(ca.emilybelnavis.mcmods.economy.simulation.macro.MacroActivation.read(reads,world).isEmpty()||open==null||!ca.emilybelnavis.mcmods.economy.simulation.macro.MacroControls.current(reads,world,open.boundaryMillis()).config().equals(config)))||reference!=a.reference()||open==null||!open.world().equals(world)
                ||open.originMillis()!=a.originMillis()||open.boundaryMillis()<a.firstEventMillis()||!open.currencies().equals(a.currencies())||!open.config().equals(config.id()))throw new IllegalArgumentException("Activation model source mismatch");
        long first=(a.effectiveMillis()-a.originMillis())/ActivityFacts.DAY_MILLIS;
        if(open.firstDay()>first)throw new IllegalArgumentException("Activation begins before source coverage");
        for(String table:List.of("activation_model_plans","activity_model_plans")) {
            var rows=reads.query("SELECT input_payload FROM "+table+" WHERE config_id=? AND config_revision=? LIMIT 1",List.of(config.id().toString(),Integer.toString(config.revision())),1);
            if(!rows.isEmpty()&&!ModelFacts.decodeInput(rows.getFirst().get("input_payload")).config().equals(config))throw new IllegalArgumentException("Immutable model config changed");
        }
        var windows=new EnumMap<EconomyCurrency,MacroModel.Window>(EconomyCurrency.class);
        var primes=new EnumMap<EconomyCurrency,BigDecimal>(EconomyCurrency.class);
        var hash=new StringBuilder(activation+"|"+first+"|"+WindowFacts.encode(open));
        for(var currency:a.currencies()) {
            var snapshot=WindowStore.read(reads,window,currency);
            if(snapshot==null)throw new IllegalArgumentException("Currency source close missing");
            var days=snapshot.days().stream().filter(d->d.index()>=first).toList();
            int expected=(int)Math.min(14,open.day()-first+1);
            if(expected<1||days.size()!=expected)throw new IllegalArgumentException("Activation day coverage missing");
            for(int i=0;i<days.size();i++)if(days.get(i).index()!=open.day()-expected+1+i)throw new IllegalArgumentException("Activation day chronology");
            int n=Math.min(7,days.size()),baseline=days.size()-n;
            var totals=new EnumMap<ActivityFacts.Kind,BigInteger>(ActivityFacts.Kind.class);
            var old=new EnumMap<ActivityFacts.Kind,BigInteger>(ActivityFacts.Kind.class);BigInteger capacity=BigInteger.ZERO;
            for(int i=0;i<days.size();i++) {
                var target=i<baseline?old:totals;
                for(var amount:days.get(i).amounts().entrySet())target.merge(amount.getKey(),BigInteger.valueOf(amount.getValue().minor()),BigInteger::add);
                if(i>=baseline)capacity=capacity.add(BigInteger.valueOf(days.get(i).capacityMinor()));
            }
            windows.put(currency,new MacroModel.Window(n,baseline,true,totals,old,capacity));
            var prior=CurrencyPrimes.current(reads,world,currency,open.boundaryMillis()-1).orElseThrow(()->new IllegalArgumentException("Prior prime unavailable"));
            if(prior.effectiveMillis()!=open.boundaryMillis()-ActivityFacts.DAY_MILLIS)throw new IllegalArgumentException("Prior prime chronology");
            primes.put(currency,prior.primeApr());hash.append('|').append(snapshot.hash());
        }
        return new MacroModel.Input(window,WindowFacts.digest(hash.toString()),reference,windows,a.anchors(),fx,primes,config);
    }
    public static void validate(Connection c)throws SQLException {
        try {
            for(var sql:statements()){String name=sql.split(" ")[2].split("\\(",2)[0];if(!sql.equals(scalar(c,"SELECT sql FROM sqlite_schema WHERE name=?",name)))throw new SQLException("Activation model schema");}
            JournalEngine.ReadView reads=(sql,params,max)->query(c,sql,params,max);long count=0;
            try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT * FROM activation_model_plans")) {
                while(rows.next()) {
                    count++;var in=ModelFacts.decodeInput(rows.getString("input_payload"));var out=ModelFacts.decodeOutput(rows.getString("output_payload"));
                    UUID activation=UUID.fromString(rows.getString("activation_id")),window=UUID.fromString(rows.getString("window_id"));
                    var a=CurrencyPrimes.activation(reads,WindowStore.readOpen(reads,window).world()).orElseThrow();long first=(a.effectiveMillis()-a.originMillis())/ActivityFacts.DAY_MILLIS;
                    if(rows.getLong("first_day")!=first||!rows.getString("config_id").equals(in.config().id().toString())||rows.getInt("config_revision")!=in.config().revision()
                            ||!input(reads,a.world(),activation,window,in.reference(),in.priorFx(),in.config()).equals(in)||!MacroModel.compute(in).equals(out))throw new SQLException("Activation-scoped model provenance");
                    long sequence=fact(c,rows.getString("operation_id"),INPUT,activation+"|"+first+"|"+rows.getString("input_payload"));
                    if(fact(c,rows.getString("operation_id"),OUTPUT,activation+"|"+window+"|"+rows.getString("output_payload"))!=sequence)throw new SQLException("Activation model input/output transaction");
                    var open=WindowStore.readOpen(reads,window);
                    if(fact(c,scalar(c,"SELECT operation_id FROM macro_window_opens WHERE id=?",open.id().toString()),WindowFacts.OPEN,WindowFacts.encode(open))>=sequence)throw new SQLException("Activation model precedes open");
                    for(var currency:a.currencies()) {
                        var snapshot=WindowStore.read(reads,window,currency);
                        if(fact(c,scalar(c,"SELECT operation_id FROM macro_window_closes WHERE open_id=? AND currency=?",window.toString(),currency.name()),WindowFacts.CLOSE,WindowFacts.encode(snapshot))>=sequence)throw new SQLException("Activation model precedes close");
                    }
                    String activationSequence=scalar(c,"SELECT t.sequence FROM currency_prime_activation a JOIN journal_transactions t ON t.operation_id=a.operation_id WHERE a.world_id=?",a.world().value().toString());
                    if(activationSequence==null||Long.parseLong(activationSequence)>=sequence)throw new SQLException("Activation model precedes activation");
                }
            }
            if(!Long.toString(count*2).equals(scalar(c,"SELECT COUNT(*) FROM journal_records WHERE record_type IN (?,?)",INPUT,OUTPUT)))throw new SQLException("Orphan activation model facts");
        }catch(RuntimeException bad){throw new SQLException("Invalid activation model source",bad);}
    }
    private static String[] parts(String payload){if(payload==null||payload.length()>16384)throw new IllegalArgumentException("Activation model bound");var parts=payload.split("\\|",3);if(parts.length!=3)throw new IllegalArgumentException("Activation model fields");return parts;}
    private static long fact(Connection c,String operation,String type,String payload)throws SQLException {
        if(!"1".equals(scalar(c,"SELECT COUNT(*) FROM journal_transactions t JOIN journal_records r ON r.sequence=t.sequence WHERE t.operation_id=? AND r.record_type=? AND r.record_version IN (1,2) AND r.payload=?",operation,type,payload)))throw new SQLException("Activation model fact missing");
        return Long.parseLong(scalar(c,"SELECT t.sequence FROM journal_transactions t JOIN journal_records r ON r.sequence=t.sequence WHERE t.operation_id=? AND r.record_type=? AND r.record_version IN (1,2) AND r.payload=?",operation,type,payload));
    }
    private static String scalar(Connection c,String sql,String... params)throws SQLException{try(var q=c.prepareStatement(sql)){for(int i=0;i<params.length;i++)q.setString(i+1,params[i]);try(var r=q.executeQuery()){return r.next()?r.getString(1):null;}}}
    private static List<Map<String,String>> query(Connection c,String sql,List<String> params,int max) {
        try(var q=c.prepareStatement(sql)){for(int i=0;i<params.size();i++)q.setString(i+1,params.get(i));var values=new ArrayList<Map<String,String>>();try(var r=q.executeQuery()){var metadata=r.getMetaData();while(r.next()){if(values.size()>=max)throw new IllegalArgumentException("Model bounded read");var row=new HashMap<String,String>();for(int i=1;i<=metadata.getColumnCount();i++)row.put(metadata.getColumnLabel(i),r.getString(i));values.add(row);}}return values;}
        catch(SQLException failure){throw new IllegalArgumentException("Activation model read",failure);}
    }
}
