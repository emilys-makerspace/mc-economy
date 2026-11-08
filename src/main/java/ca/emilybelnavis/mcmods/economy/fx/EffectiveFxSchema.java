package ca.emilybelnavis.mcmods.economy.fx;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.clock.DeadlineDispatcher;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.simulation.model.MacroModel;
import ca.emilybelnavis.mcmods.economy.simulation.macro.MacroActivation;
import ca.emilybelnavis.mcmods.economy.simulation.plan.ActivationModelPlans;
import ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes;
import ca.emilybelnavis.mcmods.economy.simulation.window.WindowStore;
import java.sql.*;
import java.util.*;

/** Separate intraday indexes. The unique legacy publication-day index is never repurposed. */
public final class EffectiveFxSchema {
    private EffectiveFxSchema(){}
    public static List<String> statements(){return List.of(
        "CREATE TABLE effective_fx_snapshots(id TEXT PRIMARY KEY,world_id TEXT NOT NULL,effective_ms INTEGER NOT NULL CHECK(typeof(effective_ms)='integer' AND effective_ms>=0),provider TEXT NOT NULL,event_id TEXT NOT NULL UNIQUE,window_id TEXT NOT NULL UNIQUE,operation_id TEXT NOT NULL UNIQUE,source_sequence INTEGER NOT NULL UNIQUE REFERENCES journal_transactions(sequence),payload TEXT NOT NULL,UNIQUE(world_id,effective_ms))",
        "CREATE INDEX effective_fx_time ON effective_fx_snapshots(world_id,effective_ms,id)",
        "CREATE INDEX effective_fx_global_time ON effective_fx_snapshots(effective_ms,id)",
        "CREATE INDEX effective_fx_original_daily ON journal_records(substr(payload,16,36),sequence) WHERE record_type='economy:fx-publication'",
        "CREATE INDEX effective_fx_facts ON journal_records(record_type,record_version,payload) WHERE record_type='economy:a8/effective-fx'",
        "CREATE TRIGGER effective_fx_snapshots_update BEFORE UPDATE ON effective_fx_snapshots BEGIN SELECT RAISE(ABORT,'immutable effective FX'); END",
        "CREATE TRIGGER effective_fx_snapshots_delete BEFORE DELETE ON effective_fx_snapshots BEGIN SELECT RAISE(ABORT,'immutable effective FX'); END",
        "CREATE TRIGGER effective_fx_snapshots_replace BEFORE INSERT ON effective_fx_snapshots WHEN EXISTS(SELECT 1 FROM effective_fx_snapshots WHERE id=NEW.id OR event_id=NEW.event_id OR window_id=NEW.window_id OR operation_id=NEW.operation_id OR source_sequence=NEW.source_sequence OR (world_id=NEW.world_id AND effective_ms=NEW.effective_ms)) BEGIN SELECT RAISE(ABORT,'existing effective FX'); END");}
    public static void validate(Connection c)throws SQLException{try{
        for(var expected:statements()){String name=expected.split(" ")[2].split("\\(",2)[0],actual=scalar(c,"SELECT sql FROM sqlite_master WHERE name=?",name);if(actual==null||!norm(expected).equals(norm(actual)))throw new SQLException("Effective FX schema: "+name);}
        if(!scalar(c,"SELECT COUNT(*) FROM effective_fx_snapshots").equals(scalar(c,"SELECT COUNT(*) FROM journal_records WHERE record_type=?",EffectiveFxFacts.FACT)))throw new SQLException("Effective FX fact/projection count");
        var reads=reads(c);String world=scalar(c,"SELECT world_id FROM foundation_metadata WHERE singleton=1");EffectiveFxSnapshot previous=null;
        try(var query=c.createStatement();var rows=query.executeQuery("SELECT * FROM effective_fx_snapshots ORDER BY effective_ms")){while(rows.next()){
            var snapshot=EffectiveFxFacts.decode(rows.getString("payload"));long sequence=rows.getLong("source_sequence");
            if(!snapshot.world().value().toString().equals(world)||!world.equals(rows.getString("world_id"))||!snapshot.id().toString().equals(rows.getString("id"))||snapshot.effectiveMillis()!=rows.getLong("effective_ms")||!snapshot.provider().equals(rows.getString("provider"))||!snapshot.event().toString().equals(rows.getString("event_id"))||!snapshot.window().toString().equals(rows.getString("window_id")))throw new SQLException("Effective FX identity projection");
            if(!"1".equals(scalar(c,"SELECT COUNT(*) FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE t.sequence=? AND t.world_id=? AND t.operation_id=? AND r.record_type=? AND r.record_version IN (1,2) AND r.payload=?",Long.toString(sequence),world,rows.getString("operation_id"),EffectiveFxFacts.FACT,rows.getString("payload"))))throw new SQLException("Effective FX journal/source sequence");
            var a=CurrencyPrimes.activation(reads,snapshot.world()).orElseThrow();var plan=ActivationModelPlans.read(reads,snapshot.window());var open=WindowStore.readOpen(reads,snapshot.window());
            String planSeq=scalar(c,"SELECT t.sequence FROM activation_model_plans p JOIN journal_transactions t ON t.operation_id=p.operation_id WHERE p.window_id=?",snapshot.window().toString());
            if(!a.id().equals(snapshot.policy())||!a.id().equals(ActivationModelPlans.activation(reads,snapshot.window()))||plan==null||open==null||planSeq==null||Long.parseLong(planSeq)>=sequence||open.boundaryMillis()!=snapshot.effectiveMillis()||!open.config().equals(snapshot.config())||!plan.input().config().id().equals(snapshot.config())||!plan.output().available()||!plan.output().equals(MacroModel.compute(plan.input()))||snapshot.reference()!=a.reference()||!plan.input().anchors().equals(a.anchors()))throw new SQLException("Effective FX frozen activation/model source");
            var prior=FxService.at(reads,snapshot.effectiveMillis()-1,sequence);if(prior==null||!prior.rates().equals(plan.input().priorFx()))throw new SQLException("Effective FX predecessor vector");
            var retirement=ca.emilybelnavis.mcmods.economy.clock.FixedRetirements.read(reads,FxService.SYSTEM);
            if(previous==null&&retirement!=null&&(retirement.takeoverMillis()!=snapshot.effectiveMillis()||!retirement.successor().equals(snapshot.provider())||!retirement.successorEvent().equals(snapshot.event())))throw new SQLException("FX snapshot differs from reserved fixed successor");
            if(previous==null?snapshot.effectiveMillis()!=a.firstEventMillis():snapshot.effectiveMillis()!=previous.effectiveMillis()+CurrencyPrimes.DAY_MILLIS||!snapshot.policy().equals(previous.policy()))throw new SQLException("Effective FX event gap/activation");
            var selected=ca.emilybelnavis.mcmods.economy.simulation.macro.MacroPlans.read(reads,snapshot.window());
            String version=scalar(c,"SELECT record_version FROM journal_records WHERE sequence=? AND record_type=?",Long.toString(sequence),EffectiveFxFacts.FACT);
            if("2".equals(version)){String selectedSeq=scalar(c,"SELECT t.sequence FROM macro_rate_plans p JOIN journal_transactions t ON t.operation_id=p.operation_id WHERE p.window_id=?",snapshot.window().toString());if(selected==null||selectedSeq==null||Long.parseLong(selectedSeq)>=sequence)throw new SQLException("FX v2 requires prior selected plan");MacroActivation.requireChild(c,rows.getString("operation_id"),"20-fx",snapshot.effectiveMillis());}else if(!"1".equals(version)||selected!=null)throw new SQLException("FX v1 selected-plan reinterpretation");
            if(selected!=null&&(!selected.world().equals(snapshot.world())||!selected.mode().name().equals(snapshot.mode().name())||!selected.automaticHash().equals(plan.output().hash())))throw new SQLException("FX selected provenance");var expected=EffectiveFxSnapshot.canonicalRates(selected!=null?selected.fx():snapshot.mode()==EffectiveFxSnapshot.Mode.PAUSED?prior.rates():plan.output().fx());if(selected==null&&snapshot.mode()!=EffectiveFxSnapshot.Mode.AUTO&&snapshot.mode()!=EffectiveFxSnapshot.Mode.PAUSED||!expected.equals(snapshot.rates()))throw new SQLException("Effective FX output differs from frozen plan");
            String progress=scalar(c,"SELECT payload FROM journal_records WHERE sequence=? AND record_type=? AND record_version=1",Long.toString(sequence),DeadlineDispatcher.PROGRESS);if(progress==null)throw new SQLException("Effective FX lacks actual stage-0 provider child");String[] fields=progress.split("\\|",-1);
            if(fields.length!=10||!fields[1].equals(snapshot.event().toString())||!fields[2].equals(snapshot.provider())||Long.parseLong(fields[3])!=snapshot.effectiveMillis()||!fields[4].equals("0")||!fields[6].equals("20-fx")||!fields[8].equals("0"))throw new SQLException("Effective FX provider/event/cursor source");
            previous=snapshot;
        }}
        if(previous!=null){var latestDaily=FxService.latestDaily(reads);long first=Long.parseLong(scalar(c,"SELECT MIN(effective_ms) FROM effective_fx_snapshots"));if(latestDaily!=null&&latestDaily.day().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()>=first)throw new SQLException("Legacy daily FX overlaps the intraday takeover");}
    }catch(RuntimeException bad){throw new SQLException("Invalid effective FX source",bad);}}
    private static JournalEngine.ReadView reads(Connection c){return (sql,params,max)->{try(var query=c.prepareStatement(sql)){for(int i=0;i<params.size();i++)query.setString(i+1,params.get(i));try(var rows=query.executeQuery()){var result=new ArrayList<Map<String,String>>();var meta=rows.getMetaData();while(rows.next()){if(result.size()>=max)throw new IllegalArgumentException("Effective FX validation query bound");var row=new LinkedHashMap<String,String>();for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnLabel(i),rows.getString(i));result.add(Collections.unmodifiableMap(row));}return List.copyOf(result);}}catch(SQLException bad){throw new IllegalStateException(bad);}};}
    private static String scalar(Connection c,String sql,String...params)throws SQLException{try(var query=c.prepareStatement(sql)){for(int i=0;i<params.length;i++)query.setString(i+1,params[i]);try(var rows=query.executeQuery()){return rows.next()?rows.getString(1):null;}}}
    private static String norm(String value){return value.replaceAll("\\s+"," ").strip();}
}
