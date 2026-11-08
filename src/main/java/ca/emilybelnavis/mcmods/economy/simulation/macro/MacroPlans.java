package ca.emilybelnavis.mcmods.economy.simulation.macro;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.simulation.model.*;
import ca.emilybelnavis.mcmods.economy.simulation.plan.*;
import ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes;
import ca.emilybelnavis.mcmods.economy.simulation.window.*;
import com.google.gson.*;
import java.math.*;
import java.sql.*;
import java.util.*;
import static ca.emilybelnavis.mcmods.economy.simulation.model.Rational.*;

/** One immutable selected basket per event, consumed by every later prime/FX child. */
public final class MacroPlans {
    private MacroPlans(){}
    public static final String FACT="economy:a8/macro-selected-plan",COMPLETE="economy:a8/macro-basket-complete";
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final Rational MIN_RATE=of("0.000000000001"),MAX_RATE=of(1_000_000);
    public record Outcome(UUID window,WorldId world,UUID policyId,long policyRevision,MacroControls.Mode mode,
                          String inputHash,String automaticHash,Map<EconomyCurrency,BigDecimal> primes,
                          Map<EconomyCurrency,BigDecimal> fx,Rational alpha,List<String> explanations,String hash) {
        public Outcome {
            Objects.requireNonNull(window);Objects.requireNonNull(world);Objects.requireNonNull(policyId);Objects.requireNonNull(mode);Objects.requireNonNull(alpha);
            primes=ordered(primes,6);fx=ordered(fx,12);explanations=List.copyOf(explanations);
            if(policyRevision<1||!digest(inputHash)||!digest(automaticHash)||!digest(hash)||primes.size()!=11||!primes.keySet().equals(fx.keySet())
                    ||!primes.keySet().equals(new HashSet<>(EconomyCurrency.all()))||alpha.compareTo(ZERO)<0||alpha.compareTo(ONE)>0||explanations.size()>32
                    ||!outcomeHash(window,world,policyId,policyRevision,mode,inputHash,automaticHash,primes,fx,alpha,explanations).equals(hash))
                throw new IllegalArgumentException("Selected macro plan identity");
            for(var value:primes.values())if(value.signum()<0||value.compareTo(new BigDecimal("0.25"))>0)throw new IllegalArgumentException("Selected prime bound");
            for(var value:fx.values())if(of(value).compareTo(MIN_RATE)<0||of(value).compareTo(MAX_RATE)>0)throw new IllegalArgumentException("Selected FX bound");
        }
    }
    private record Selection(Map<EconomyCurrency,BigDecimal> primes,Map<EconomyCurrency,BigDecimal> fx,Rational alpha,List<String> explanations){}
    public record Completion(UUID window,WorldId world,UUID policyId,String selectedHash,long effectiveMillis,List<UUID> primes,UUID fx) {
        public Completion{Objects.requireNonNull(window);Objects.requireNonNull(world);Objects.requireNonNull(policyId);Objects.requireNonNull(fx);primes=List.copyOf(primes);if(!digest(selectedHash)||effectiveMillis<0||primes.size()!=11||primes.stream().distinct().count()!=11)throw new IllegalArgumentException("Macro completion");}
    }
    public static List<String> statements(){var sql=new ArrayList<>(List.of(
            "CREATE TABLE macro_rate_plans(window_id TEXT PRIMARY KEY REFERENCES macro_window_opens(id),world_id TEXT NOT NULL,policy_id TEXT NOT NULL,policy_revision INTEGER NOT NULL CHECK(policy_revision>0),operation_id TEXT NOT NULL UNIQUE,payload TEXT NOT NULL)",
            "CREATE TABLE macro_rate_completions(window_id TEXT PRIMARY KEY REFERENCES macro_rate_plans(window_id),world_id TEXT NOT NULL,operation_id TEXT NOT NULL UNIQUE,payload TEXT NOT NULL)",
            "CREATE INDEX macro_selected_facts ON journal_records(record_type,record_version,payload) WHERE record_type IN ('economy:a8/macro-selected-plan','economy:a8/macro-basket-complete')"));
        for(String table:List.of("macro_rate_plans","macro_rate_completions")) {
            for(String action:List.of("UPDATE","DELETE"))sql.add("CREATE TRIGGER "+table+"_"+action.toLowerCase(Locale.ROOT)+" BEFORE "+action+" ON "+table+" BEGIN SELECT RAISE(ABORT,'immutable macro selection'); END");
            sql.add("CREATE TRIGGER "+table+"_replace BEFORE INSERT ON "+table+" WHEN EXISTS(SELECT 1 FROM "+table+" WHERE window_id=NEW.window_id OR operation_id=NEW.operation_id) BEGIN SELECT RAISE(ABORT,'existing macro selection'); END");
        }return List.copyOf(sql);
    }
    public static RecordCatalog register(RecordCatalog catalog){return catalog.with(new RecordCatalog.Key(FACT,1),MacroPlans::decode).with(new RecordCatalog.Key(COMPLETE,1),MacroPlans::decodeCompletion);}
    public static Outcome read(JournalEngine.ReadView reads,UUID window){if(!table(reads,"macro_rate_plans"))return null;var rows=reads.query("SELECT payload FROM macro_rate_plans WHERE window_id=?",List.of(window.toString()),1);return rows.isEmpty()?null:decode(rows.getFirst().get("payload"));}
    public static Completion completion(JournalEngine.ReadView reads,UUID window){if(!table(reads,"macro_rate_completions"))return null;var rows=reads.query("SELECT payload FROM macro_rate_completions WHERE window_id=?",List.of(window.toString()),1);return rows.isEmpty()?null:decodeCompletion(rows.getFirst().get("payload"));}
    public static String encode(Outcome value){return encodeObject(value);}public static String encode(Completion value){return encodeObject(value);}
    public static Outcome decode(String payload){return decodeObject(payload,Outcome.class);}public static Completion decodeCompletion(String payload){return decodeObject(payload,Completion.class);}

    /** Decode the pure prepared automatic facts before their same-child projections are installed. */
    public static ModelPlanStore.Plan automatic(JournalEngine.Commit prepared) {
        MacroModel.Input input=null;MacroModel.Output output=null;
        for(var record:prepared.records()) {
            if(record.type().equals(ActivationModelPlans.INPUT)) {if(input!=null||record.version()!=1)throw new IllegalArgumentException("Automatic input source");input=ModelFacts.decodeInput(record.payload().split("\\|",3)[2]);}
            if(record.type().equals(ActivationModelPlans.OUTPUT)) {if(output!=null||record.version()!=1)throw new IllegalArgumentException("Automatic output source");output=ModelFacts.decodeOutput(record.payload().split("\\|",3)[2]);}
        }
        if(input==null||output==null||!MacroModel.compute(input).equals(output))throw new IllegalArgumentException("Prepared automatic model incomplete");
        return new ModelPlanStore.Plan(input,output);
    }
    public static JournalEngine.Commit prepare(JournalEngine.ReadView reads,WorldId world,UUID childOperation,UUID window,
                                               MacroControls.Policy policy,ModelPlanStore.Plan automatic) {
        if(read(reads,window)!=null)throw new IllegalArgumentException("Macro selection already captured");
        var open=WindowStore.readOpen(reads,window);
        if(open==null||!open.world().equals(world)||!policy.world().equals(world)||!MacroControls.current(reads,world,open.boundaryMillis()).equals(policy)
                ||!open.config().equals(policy.config().id()))throw new IllegalArgumentException("Macro selection source mismatch");
        var outcome=select(world,window,open.boundaryMillis(),policy,automatic);String payload=encode(outcome);
        return new JournalEngine.Commit(List.of(new Journal.Record(FACT,1,payload)),new Journal.Result(1,"MACRO_PLAN_SELECTED",outcome.hash()),tx->
                tx.execute("INSERT INTO macro_rate_plans VALUES(?,?,?,?,?,?)",List.of(window.toString(),world.value().toString(),policy.id().toString(),Long.toString(policy.revision()),childOperation.toString(),payload)));
    }
    private static Outcome select(WorldId world,UUID window,long boundary,MacroControls.Policy policy,ModelPlanStore.Plan automatic) {
        var input=automatic.input();var output=automatic.output();
        if(!input.windowId().equals(window)||!input.config().equals(policy.config())||!output.available()||!MacroModel.compute(input).equals(output))throw new IllegalArgumentException("Complete frozen model required in every mode");
        MacroControls.Mode mode=policy.mode()==MacroControls.Mode.MANUAL&&policy.effectiveMillis()<boundary?MacroControls.Mode.PAUSED:policy.mode();
        Selection selection;
        if(mode==MacroControls.Mode.AUTO)selection=new Selection(output.primes(),output.fx(),output.alpha(),output.explanations());
        else if(mode==MacroControls.Mode.PAUSED)selection=new Selection(input.priorPrime(),input.priorFx(),ZERO,List.of(policy.mode()==MacroControls.Mode.MANUAL?"Manual outcome is one-shot; subsequent event holds":"Paused event holds the frozen prior basket"));
        else {var manual=MacroControls.apply(policy,input.priorPrime(),input.priorFx(),input.anchors(),input.reference());selection=new Selection(manual.primes(),manual.fx(),manual.alpha(),manual.explanations());}
        var primes=ordered(selection.primes(),6);var fx=ordered(selection.fx(),12);
        requireBounds(input,primes,fx);
        String hash=outcomeHash(window,world,policy.id(),policy.revision(),mode,input.inputHash(),output.hash(),primes,fx,selection.alpha(),selection.explanations());
        return new Outcome(window,world,policy.id(),policy.revision(),mode,input.inputHash(),output.hash(),primes,fx,selection.alpha(),selection.explanations(),hash);
    }
    private static void requireBounds(MacroModel.Input input,Map<EconomyCurrency,BigDecimal> primes,Map<EconomyCurrency,BigDecimal> fx) {
        if(!primes.keySet().equals(input.priorPrime().keySet())||!MacroModel.validRounded(input,fx))throw new IllegalArgumentException("Selected coherent FX vector exceeds bounds");
        for(var c:primes.keySet())if(primes.get(c).signum()<0||primes.get(c).compareTo(new BigDecimal("0.25"))>0||primes.get(c).subtract(input.priorPrime().get(c)).abs().compareTo(new BigDecimal("0.001"))>0)throw new IllegalArgumentException("Selected prime exceeds bounds");
    }
    /** Final basket child; actual DeadlineDispatcher completion remains a separate later turn. */
    public static JournalEngine.Commit prepareCompletion(JournalEngine.ReadView reads,WorldId world,UUID childOperation,UUID window) {
        if(completion(reads,window)!=null)throw new IllegalArgumentException("Macro basket already complete");
        var selected=Objects.requireNonNull(read(reads,window),"Selected macro plan missing");
        var open=Objects.requireNonNull(WindowStore.readOpen(reads,window),"Macro source window missing");
        if(!selected.world().equals(world)||!open.world().equals(world))throw new IllegalArgumentException("Macro completion world");
        var primeIds=new ArrayList<UUID>();
        for(var currency:EconomyCurrency.all()) {
            var prime=CurrencyPrimes.current(reads,world,currency,open.boundaryMillis()).orElseThrow();
            if(!prime.window().equals(window)||prime.effectiveMillis()!=open.boundaryMillis()||!prime.mode().name().equals(selected.mode().name())
                    ||prime.primeApr().compareTo(selected.primes().get(currency))!=0)throw new IllegalArgumentException("Prime child differs from selected basket");
            primeIds.add(prime.id());
        }
        var fx=FxService.at(reads,open.boundaryMillis());
        if(fx==null||!fx.world().equals(world)||fx.effectiveMillis()!=open.boundaryMillis()||!fx.window().equals(window)
                ||!fx.mode().name().equals(selected.mode().name())||!ratesEqual(fx.rates(),selected.fx()))throw new IllegalArgumentException("FX child differs from selected basket");
        var complete=new Completion(window,world,selected.policyId(),selected.hash(),open.boundaryMillis(),primeIds,fx.id());String payload=encode(complete);
        return new JournalEngine.Commit(List.of(new Journal.Record(COMPLETE,1,payload)),new Journal.Result(1,"MACRO_BASKET_COMPLETE",selected.hash()),tx->
                tx.execute("INSERT INTO macro_rate_completions VALUES(?,?,?,?)",List.of(window.toString(),world.value().toString(),childOperation.toString(),payload)));
    }
    public static void validate(Connection connection)throws SQLException {
        try {
            for(var expected:statements()){String name=expected.split(" ")[2].split("\\(",2)[0];if(!expected.equals(scalar(connection,"SELECT sql FROM sqlite_schema WHERE name=?",name)))throw new IllegalArgumentException("Changed selected-plan schema");}
            var reads=reads(connection);long plans=0,completions=0;
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT * FROM macro_rate_plans ORDER BY window_id")) {
                while(rows.next()) {
                    plans++;var selected=decode(rows.getString("payload"));var open=WindowStore.readOpen(reads,selected.window());
                    if(open==null||!selected.window().toString().equals(rows.getString("window_id"))||!selected.world().value().toString().equals(rows.getString("world_id"))
                            ||!selected.policyId().toString().equals(rows.getString("policy_id"))||selected.policyRevision()!=rows.getLong("policy_revision"))throw new IllegalArgumentException("Selected-plan projection");
                    var policy=MacroControls.current(reads,selected.world(),open.boundaryMillis());var automatic=ActivationModelPlans.read(reads,selected.window());
                    if(automatic==null||!select(selected.world(),selected.window(),open.boundaryMillis(),policy,automatic).equals(selected))throw new IllegalArgumentException("Selected-plan frozen source mismatch");
                    long sequence=fact(connection,rows.getString("operation_id"),FACT,rows.getString("payload"));
                    MacroActivation.requireChild(connection,rows.getString("operation_id"),"02-plan",open.boundaryMillis());
                    String controlSequence=scalar(connection,"SELECT t.sequence FROM macro_control_policies p JOIN journal_transactions t ON t.operation_id=p.operation_id WHERE p.id=? AND p.world_id=?",policy.id().toString(),selected.world().value().toString());
                    if(controlSequence==null||Long.parseLong(controlSequence)>=sequence)throw new IllegalArgumentException("Macro control must precede selected plan");
                    String automaticSequence=scalar(connection,"SELECT t.sequence FROM activation_model_plans p JOIN journal_transactions t ON t.operation_id=p.operation_id WHERE p.window_id=?",selected.window().toString());
                    if(automaticSequence==null||Long.parseLong(automaticSequence)!=sequence)throw new IllegalArgumentException("Automatic and selected plans must share the plan child");
                }
            }
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT * FROM macro_rate_completions ORDER BY window_id")) {
                while(rows.next()) {
                    completions++;var done=decodeCompletion(rows.getString("payload"));
                    if(!done.window().toString().equals(rows.getString("window_id"))||!done.world().value().toString().equals(rows.getString("world_id")))throw new IllegalArgumentException("Macro completion projection");
                    long sequence=fact(connection,rows.getString("operation_id"),COMPLETE,rows.getString("payload"));
                    MacroActivation.requireChild(connection,rows.getString("operation_id"),"30-complete",done.effectiveMillis());
                    var selected=read(reads,done.window());var open=WindowStore.readOpen(reads,done.window());
                    if(selected==null||open==null||!selected.world().equals(done.world())||!selected.policyId().equals(done.policyId())||!selected.hash().equals(done.selectedHash())||open.boundaryMillis()!=done.effectiveMillis())throw new IllegalArgumentException("Macro completion selected plan");
                    String planned=scalar(connection,"SELECT t.sequence FROM macro_rate_plans p JOIN journal_transactions t ON t.operation_id=p.operation_id WHERE p.window_id=?",done.window().toString());
                    if(planned==null||Long.parseLong(planned)>=sequence)throw new IllegalArgumentException("Macro completion precedes selected plan");
                    var primeCompletion=reads.query("SELECT payload,operation_id FROM currency_prime_completions WHERE world_id=? AND window_id=?",List.of(done.world().value().toString(),done.window().toString()),1);
                    if(primeCompletion.size()!=1||!primeCompletion.getFirst().get("operation_id").equals(rows.getString("operation_id")))throw new IllegalArgumentException("Macro completion lacks same-child prime completion");
                    var primeDone=ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimeFacts.decodeCompletion(primeCompletion.getFirst().get("payload"));
                    if(!primeDone.policies().equals(done.primes())||primeDone.effectiveMillis()!=done.effectiveMillis()||!primeDone.modelHash().equals(selected.automaticHash()))throw new IllegalArgumentException("Macro prime completion differs from selected source");
                    for(int i=0;i<EconomyCurrency.all().size();i++) {
                        var currency=EconomyCurrency.all().get(i);var prime=CurrencyPrimes.byId(reads,done.world(),done.primes().get(i)).orElseThrow();
                        if(prime.currency()!=currency||!prime.window().equals(done.window())||prime.effectiveMillis()!=done.effectiveMillis()||prime.primeApr().compareTo(selected.primes().get(currency))!=0||!prime.mode().name().equals(selected.mode().name()))throw new IllegalArgumentException("Macro completion prime source");
                        String child=scalar(connection,"SELECT t.sequence FROM currency_prime_policies p JOIN journal_transactions t ON t.operation_id=p.operation_id WHERE p.id=?",prime.id().toString());
                        if(child==null||Long.parseLong(child)>=sequence)throw new IllegalArgumentException("Macro completion precedes prime child");
                    }
                    var fx=FxService.source(reads,done.fx(),sequence);if(fx==null||fx.legacy()||!fx.snapshot().world().equals(done.world())||fx.snapshot().effectiveMillis()!=done.effectiveMillis()||!fx.snapshot().window().equals(done.window())
                            ||!ratesEqual(fx.snapshot().rates(),selected.fx())||!fx.snapshot().mode().name().equals(selected.mode().name()))throw new IllegalArgumentException("Macro completion FX source");
                }
            }
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT event_id,effective_ms,state,cursor FROM financial_deadline_events WHERE provider='economy:a8/macro-rates'")) {
                while(rows.next()) {
                    var world=new WorldId(UUID.fromString(scalar(connection,"SELECT world_id FROM foundation_metadata WHERE singleton=1")));long effective=rows.getLong("effective_ms");
                    var event=MacroProvider.event(world,effective);if(!event.id().toString().equals(rows.getString("event_id")))throw new IllegalArgumentException("Macro dispatcher event identity");
                    if(!rows.getString("state").equals("COMPLETE")&&!rows.getString("cursor").equals("30-complete"))continue;
                    UUID window=MacroProvider.windowId(world,effective);if(completion(reads,window)==null)throw new IllegalArgumentException("Dispatcher completed without macro basket");
                    if(rows.getString("state").equals("COMPLETE")) {
                        String basket=scalar(connection,"SELECT t.sequence FROM macro_rate_completions p JOIN journal_transactions t ON t.operation_id=p.operation_id WHERE p.window_id=?",window.toString());
                        String dispatched=scalar(connection,"SELECT sequence FROM journal_records WHERE record_type='economy:a6/deadline-completion' AND substr(payload,3,36)=?",event.id().toString());
                        if(basket==null||dispatched==null||Long.parseLong(basket)>=Long.parseLong(dispatched))throw new IllegalArgumentException("Dispatcher completion must follow basket child");
                    }
                }
            }
            if(!Long.toString(plans).equals(scalar(connection,"SELECT COUNT(*) FROM journal_records WHERE record_type=?",FACT))||!Long.toString(completions).equals(scalar(connection,"SELECT COUNT(*) FROM journal_records WHERE record_type=?",COMPLETE)))throw new IllegalArgumentException("Orphan selected macro facts");
        }catch(RuntimeException invalid){throw new SQLException("Invalid selected macro basket",invalid);}
    }
    private static Map<EconomyCurrency,BigDecimal> ordered(Map<EconomyCurrency,BigDecimal> values,int scale){var result=new TreeMap<EconomyCurrency,BigDecimal>();values.forEach((c,value)->result.put(Objects.requireNonNull(c),Objects.requireNonNull(value).setScale(scale,RoundingMode.UNNECESSARY)));return Collections.unmodifiableMap(result);}
    private static boolean table(JournalEngine.ReadView reads,String name){return !reads.query("SELECT name FROM sqlite_schema WHERE type='table' AND name=?",List.of(name),1).isEmpty();}
    private static boolean ratesEqual(Map<EconomyCurrency,BigDecimal> a,Map<EconomyCurrency,BigDecimal> b){return a.keySet().equals(b.keySet())&&a.keySet().stream().allMatch(c->a.get(c).compareTo(b.get(c))==0);}
    private static String canonical(Map<EconomyCurrency,BigDecimal> values){var text=new StringBuilder();for(var c:values.keySet().stream().sorted().toList())text.append(c).append('=').append(values.get(c).toPlainString()).append(';');return text.toString();}
    private static String outcomeHash(UUID window,WorldId world,UUID policy,long revision,MacroControls.Mode mode,String input,String automatic,
                                      Map<EconomyCurrency,BigDecimal> primes,Map<EconomyCurrency,BigDecimal> fx,Rational alpha,List<String> explanations) {
        return MacroModel.sha256(window+"|"+world.value()+"|"+policy+"|"+revision+"|"+mode+"|"+input+"|"+automatic+"|"+canonical(primes)+"|"+canonical(fx)+"|"+alpha+"|"+JSON.toJson(explanations));
    }
    private static boolean digest(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static String encodeObject(Object value){String text=JSON.toJson(value);if(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("Selected plan fact bound");return text;}
    private static <T>T decodeObject(String text,Class<T> type){if(text==null||text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("Selected plan fact bound");try{T value=JSON.fromJson(text,type);if(value==null||!encodeObject(value).equals(text))throw new IllegalArgumentException("Noncanonical selected plan");return value;}catch(JsonParseException malformed){throw new IllegalArgumentException("Malformed selected plan",malformed);}}
    private static long fact(Connection c,String operation,String type,String payload)throws SQLException {String value=scalar(c,"SELECT t.sequence FROM journal_transactions t JOIN journal_records r ON r.sequence=t.sequence WHERE t.operation_id=? AND r.record_type=? AND r.record_version=1 AND r.payload=?",operation,type,payload);if(value==null||!"1".equals(scalar(c,"SELECT COUNT(*) FROM journal_transactions t JOIN journal_records r ON r.sequence=t.sequence WHERE t.operation_id=? AND r.record_type=? AND r.record_version=1 AND r.payload=?",operation,type,payload)))throw new IllegalArgumentException("Selected macro fact source");return Long.parseLong(value);}
    private static String scalar(Connection c,String sql,String... params)throws SQLException{try(var q=c.prepareStatement(sql)){for(int i=0;i<params.length;i++)q.setString(i+1,params[i]);try(var rows=q.executeQuery()){return rows.next()?rows.getString(1):null;}}}
    private static JournalEngine.ReadView reads(Connection c){return (sql,params,max)->{try(var q=c.prepareStatement(sql)){for(int i=0;i<params.size();i++)q.setString(i+1,params.get(i));var result=new ArrayList<Map<String,String>>();try(var rows=q.executeQuery()){var metadata=rows.getMetaData();while(rows.next()){if(result.size()>=max)throw new IllegalArgumentException("Selected macro query bound");var row=new LinkedHashMap<String,String>();for(int i=1;i<=metadata.getColumnCount();i++)row.put(metadata.getColumnLabel(i),rows.getString(i));result.add(Collections.unmodifiableMap(row));}}return List.copyOf(result);}catch(SQLException invalid){throw new IllegalArgumentException("Selected macro evidence read",invalid);}};}
}
