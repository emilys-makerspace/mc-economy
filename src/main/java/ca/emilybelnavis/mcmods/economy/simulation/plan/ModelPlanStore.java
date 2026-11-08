package ca.emilybelnavis.mcmods.economy.simulation.plan;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.*;
import ca.emilybelnavis.mcmods.economy.simulation.window.*;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** Internal worker API. Captures one complete candidate plan; it publishes no rates. */
public final class ModelPlanStore {
    private ModelPlanStore() {}
    private static final Journal.Kind KIND = new Journal.Kind("economy:a8", "economy:a8/model-plan");
    public record Plan(MacroModel.Input input, MacroModel.Output output) {}

    public static List<String> statements() {
        return List.of(
                "CREATE TABLE activity_model_plans(open_id TEXT PRIMARY KEY REFERENCES macro_window_opens(id),operation_id TEXT NOT NULL UNIQUE,anchor_id TEXT NOT NULL,config_id TEXT NOT NULL,config_revision INTEGER NOT NULL CHECK(config_revision>0),input_payload TEXT NOT NULL,output_payload TEXT NOT NULL)",
                "CREATE INDEX activity_model_anchor ON activity_model_plans(anchor_id)",
                "CREATE INDEX activity_model_config ON activity_model_plans(config_id,config_revision)",
                "CREATE TRIGGER activity_model_plans_replace BEFORE INSERT ON activity_model_plans WHEN EXISTS(SELECT 1 FROM activity_model_plans WHERE open_id=NEW.open_id OR operation_id=NEW.operation_id) BEGIN SELECT RAISE(ABORT,'existing model plan'); END",
                "CREATE TRIGGER activity_model_plans_update BEFORE UPDATE ON activity_model_plans BEGIN SELECT RAISE(ABORT,'immutable model plan'); END",
                "CREATE TRIGGER activity_model_plans_delete BEFORE DELETE ON activity_model_plans BEGIN SELECT RAISE(ABORT,'immutable model plan'); END",
                "CREATE INDEX activity_model_facts ON journal_records(record_type,record_version,payload) WHERE record_type IN ('economy:a8/model-input','economy:a8/model-output')");
    }

    public static Plan prepare(FoundationStore store, UUID openId, EconomyCurrency reference,
                               Map<EconomyCurrency,BigDecimal> anchors, Map<EconomyCurrency,BigDecimal> priorFx,
                               Map<EconomyCurrency,BigDecimal> priorPrime, MacroModel.Config config) {
        var input = store.transaction(tx -> input(tx::query, openId, reference, anchors, priorFx, priorPrime, config));
        String encodedInput = ModelFacts.encode(input);
        UUID operation = WindowFacts.stable("model-plan|" + openId);
        var actor = new SystemActorId(WindowFacts.stable("model-plan-actor|" + store.snapshot().worldId().value()));
        var request = new Journal.CanonicalRequest(1, Map.of("inputHash", WindowFacts.digest(encodedInput)));
        var registration = new JournalEngine.Registration(1, (intent, reads) -> {
            var checked = input(reads, openId, reference, anchors, priorFx, priorPrime, config);
            if (!checked.equals(input)) throw new IllegalStateException("Model input changed");
            var output = MacroModel.compute(input);
            String encodedOutput = ModelFacts.encode(output);
            return new JournalEngine.Commit(List.of(new Journal.Record(ModelFacts.INPUT,1,encodedInput),
                    new Journal.Record(ModelFacts.OUTPUT,1,encodedOutput)), new Journal.Result(1,"MODEL_PLANNED",openId.toString()),
                    tx -> tx.execute("INSERT INTO activity_model_plans VALUES(?,?,?,?,?,?,?)", List.of(openId.toString(),
                            operation.toString(),WindowStore.readOpen(reads,openId).anchor().toString(),config.id().toString(),Integer.toString(config.revision()),encodedInput,encodedOutput)));
        });
        var engine = new JournalEngine(store,(a,k)->a.equals(actor)&&k.equals(KIND),Map.of(KIND,registration));
        var receipt = engine.submit(new Journal.Intent(new OperationId(operation),new RequestId(operation),actor,KIND,request,null,null));
        if (receipt.status()!=Journal.Status.COMMITTED) throw new IllegalArgumentException("Model plan: "+receipt.result().code());
        return store.transaction(tx -> read(tx::query,openId));
    }

    private static MacroModel.Input input(JournalEngine.ReadView reads, UUID openId, EconomyCurrency reference,
                                          Map<EconomyCurrency,BigDecimal> anchors,Map<EconomyCurrency,BigDecimal> fx,
                                          Map<EconomyCurrency,BigDecimal> prime,MacroModel.Config config) {
        var open = WindowStore.readOpen(reads,openId);
        if (open==null || !open.config().equals(config.id())) throw new IllegalArgumentException("Model configuration identity");
        var priorAnchor = reads.query("SELECT input_payload FROM activity_model_plans WHERE anchor_id=? LIMIT 1",List.of(open.anchor().toString()),1);
        if(!priorAnchor.isEmpty() && !ModelFacts.decodeInput(priorAnchor.getFirst().get("input_payload")).anchors().equals(anchors))
            throw new IllegalArgumentException("Immutable activation anchor changed");
        var priorConfig = reads.query("SELECT input_payload FROM activity_model_plans WHERE config_id=? AND config_revision=? LIMIT 1",List.of(config.id().toString(),Integer.toString(config.revision())),1);
        if(!priorConfig.isEmpty() && !ModelFacts.decodeInput(priorConfig.getFirst().get("input_payload")).config().equals(config))
            throw new IllegalArgumentException("Immutable model configuration changed");
        var windows = new EnumMap<EconomyCurrency,MacroModel.Window>(EconomyCurrency.class);
        var hashes = new StringBuilder(WindowFacts.encode(open));
        for (var currency:open.currencies()) {
            var snapshot = WindowStore.read(reads,openId,currency);
            if (snapshot==null) throw new IllegalArgumentException("Window close unavailable: "+currency);
            windows.put(currency,snapshot.modelWindow()); hashes.append('|').append(snapshot.hash());
        }
        return new MacroModel.Input(openId,WindowFacts.digest(hashes.toString()),reference,windows,anchors,fx,prime,config);
    }

    public static Plan read(JournalEngine.ReadView reads,UUID openId) {
        var rows=reads.query("SELECT input_payload,output_payload FROM activity_model_plans WHERE open_id=?",List.of(openId.toString()),1);
        if(rows.isEmpty())return null;
        var row=rows.getFirst();return new Plan(ModelFacts.decodeInput(row.get("input_payload")),ModelFacts.decodeOutput(row.get("output_payload")));
    }

    /** Stream immutable plans, reusing the frozen v1 model and indexed snapshot reads. */
    public static void validate(Connection connection) throws SQLException {
        for (String expected:statements()) {
            String name=expected.split(" ")[2].split("\\(")[0];
            try(var q=connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
                q.setString(1,name);try(var r=q.executeQuery()) {
                    if(!r.next() || !expected.replaceAll("\\s+"," ").strip().equals(r.getString(1).replaceAll("\\s+"," ").strip()))
                        throw new SQLException("Changed model plan schema: "+name);
                }
            }
        }
        try(var q=connection.createStatement();var rows=q.executeQuery("SELECT * FROM activity_model_plans")) {
            long count=0;
            while(rows.next()) {
                count++;
                var in=ModelFacts.decodeInput(rows.getString("input_payload"));
                var out=ModelFacts.decodeOutput(rows.getString("output_payload"));
                UUID id=UUID.fromString(rows.getString("open_id"));
                JournalEngine.ReadView reads=(sql,params,max)->query(connection,sql,params,max);
                if(!WindowStore.readOpen(reads,id).anchor().toString().equals(rows.getString("anchor_id"))
                        || !in.config().id().toString().equals(rows.getString("config_id")) || in.config().revision()!=rows.getInt("config_revision")
                        || !in.windowId().equals(id) || !WindowFacts.stable("model-plan|"+id).toString().equals(rows.getString("operation_id"))
                        || !input(reads,id,in.reference(),in.anchors(),in.priorFx(),in.priorPrime(),in.config()).equals(in)
                        || !MacroModel.compute(in).equals(out)) throw new SQLException("Model plan provenance/output mismatch");
                requireFact(connection,rows.getString("operation_id"),ModelFacts.INPUT,rows.getString("input_payload"));
                requireFact(connection,rows.getString("operation_id"),ModelFacts.OUTPUT,rows.getString("output_payload"));
            }
            try(var s=connection.createStatement();var r=s.executeQuery("SELECT count(*) FROM journal_records WHERE record_type IN ('economy:a8/model-input','economy:a8/model-output')")) {
                if(!r.next() || r.getLong(1)!=Math.multiplyExact(count,2))throw new SQLException("Orphan model facts");
            }
        }catch(RuntimeException invalid){throw new SQLException("Invalid model plan",invalid);}
    }

    private static void requireFact(Connection c,String operation,String type,String payload)throws SQLException {
        try(var q=c.prepareStatement("SELECT count(*) FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE t.operation_id=? AND r.record_type=? AND r.record_version=1 AND r.payload=?")) {
            q.setString(1,operation);q.setString(2,type);q.setString(3,payload);
            try(var r=q.executeQuery()){if(!r.next()||r.getInt(1)!=1)throw new SQLException("Model plan lacks fact");}
        }
    }
    private static List<Map<String,String>> query(Connection c,String sql,List<String> params,int max) {
        try(var q=c.prepareStatement(sql)) {
            for(int i=0;i<params.size();i++)q.setString(i+1,params.get(i));
            try(var r=q.executeQuery()) {
                var result=new ArrayList<Map<String,String>>();var md=r.getMetaData();
                while(r.next()) {if(result.size()==max)throw new IllegalStateException("Model validation read bound");
                    var row=new HashMap<String,String>();for(int i=1;i<=md.getColumnCount();i++)row.put(md.getColumnLabel(i),r.getString(i));result.add(row);}
                return result;
            }
        }catch(SQLException error){throw new IllegalStateException(error);}
    }
}
