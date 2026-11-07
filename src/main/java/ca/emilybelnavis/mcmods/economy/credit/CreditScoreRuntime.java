package ca.emilybelnavis.mcmods.economy.credit;

import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.debt.DebtRecords;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.math.*;
import java.time.*;
import java.util.*;

/** Durable one-owner, one-input score turns. Daily areas and summaries survive restart. */
public final class CreditScoreRuntime {
    public static final String PROVIDER="economy:a6/credit-score";
    public static final String TASK="economy:a6/score-fold";
    public static final String DAY="economy:a6/score-utilization-day";
    private static final long YEAR=364*ScorePolicy.DAY_MILLIS;
    private CreditScoreRuntime() {}

    public static List<DeadlineDispatcher.Provider> providers() {
        return List.of(new DeadlineDispatcher.Provider() {
            public String id(){return PROVIDER;}
            public Map<Integer,String> versions(){return Map.of(1,"score-reported-daily-area-v1");}
            public DeadlineDispatcher.Event next(EconomicClock.Queries queries,long origin,DeadlineDispatcher.Event completed) {
                var applying=queries.query("SELECT event_id,effective_ms,stage,version FROM financial_deadline_events WHERE provider=? AND state='APPLYING' LIMIT 1",List.of(PROVIDER),1);
                if(!applying.isEmpty()){var r=applying.getFirst();return new DeadlineDispatcher.Event(UUID.fromString(r.get("event_id")),PROVIDER,number(r,"effective_ms"),6,Integer.parseInt(r.get("version")));}
                var head=queries.query("SELECT next_at FROM credit_score_heads ORDER BY next_at,owner_id LIMIT 1",List.of(),1);
                if(head.isEmpty())return null;long boundary=number(head.getFirst(),"next_at");
                if(completed!=null&&boundary<=completed.effectiveMillis())throw new IllegalStateException("Score deadline did not advance");
                return new DeadlineDispatcher.Event(CreditReporting.stable(PROVIDER+"|"+boundary),PROVIDER,boundary,6,1);
            }
            public List<String> targets(DeadlineDispatcher.Event event,String after,int limit,EconomicClock.Queries queries) {
                var parts=after.isEmpty()?new String[]{"",""}:after.split("/",2);
                var rows=queries.query("SELECT h.owner_id,COALESCE(t.step,0) AS step FROM credit_score_heads h LEFT JOIN credit_score_tasks t ON t.world_id=h.world_id AND t.owner_id=h.owner_id AND t.boundary=h.next_at WHERE h.next_at=? AND h.owner_id>=? ORDER BY h.owner_id LIMIT ?",List.of(Long.toString(event.effectiveMillis()),parts[0],Integer.toString(limit+1)),limit+1);
                return rows.stream().map(r->r.get("owner_id")+"/"+String.format(Locale.ROOT,"%020d",number(r,"step"))).filter(s->s.compareTo(after)>0).limit(limit).toList();
            }
            public EconomicClock.Output prepare(DeadlineDispatcher.Event event,String target,EconomicClock.Queries queries) {
                var owner=UUID.fromString(target.split("/",2)[0]);
                var commit=step(queries::query,owner,event.effectiveMillis());
                return new EconomicClock.Output(target,commit.projections(),commit.records());
            }
        });
    }

    /** Historical new-domain work commits independently without rewinding the shared calendar. */
    public static boolean bootstrap(FoundationStore store) {
        var head=store.transaction(tx->tx.query("SELECT h.owner_id,h.next_at,COALESCE(t.step,0) AS step FROM credit_score_heads h LEFT JOIN credit_score_tasks t ON t.world_id=h.world_id AND t.owner_id=h.owner_id AND t.boundary=h.next_at WHERE h.next_at<=(SELECT last_effective_ms FROM financial_calendar WHERE singleton=1) ORDER BY h.next_at,h.owner_id LIMIT 1",List.of(),1));
        if(head.isEmpty())return true;
        var row=head.getFirst();var owner=UUID.fromString(row.get("owner_id"));long boundary=number(row,"next_at");
        var kind=new Journal.Kind("economy:credit-reporting","economy:score-bootstrap");
        var operation=CreditReporting.stable("score-bootstrap|"+store.snapshot().worldId().value()+"|"+owner+"|"+boundary+"|"+row.get("step"));
        var engine=new JournalEngine(store,(a,k)->true,Map.of(kind,new JournalEngine.Registration(1,(intent,reads)->step(reads,owner,boundary))));
        var actor=new SystemActorId(CreditReporting.stable("score-bootstrap-actor|"+store.snapshot().worldId().value()));
        var outcome=engine.submit(new Journal.Intent(new OperationId(operation),new RequestId(operation),actor,kind,new Journal.CanonicalRequest(1,Map.of("owner",owner.toString(),"boundary",Long.toString(boundary),"step",row.get("step"))),null,null));
        if(outcome.status()!=Journal.Status.COMMITTED)throw new IllegalStateException("Historical score fold unavailable");
        return false;
    }

    static JournalEngine.Commit step(JournalEngine.ReadView reads,UUID ownerId,long boundary) {
        var world=new WorldId(UUID.fromString(reads.query("SELECT world_id FROM foundation_metadata",List.of(),1).getFirst().get("world_id")));
        var owner=new PartyRef(world,new PartyRef.Player(new PlayerId(ownerId)));
        var profile=CreditProfiles.find(reads,owner).orElseThrow();long origin=CreditProfiles.origin(reads).orElseThrow();
        if(boundary!=CreditReporting.nextBoundary(profile,origin))throw new IllegalArgumentException("Score fold boundary changed");
        var firstSource=reads.query("SELECT body FROM credit_reporting_sources WHERE world_id=? AND facility_id=? AND revision=1",List.of(world.value().toString(),profile.firstFacility().toString()),1);
        if(firstSource.isEmpty())throw new IllegalStateException("CREDIT_UTILIZATION_FIRST_ISSUANCE_SOURCE_UNAVAILABLE");
        var issued=CreditReporting.decode(firstSource.getFirst().get("body"));
        if(!issued.world().equals(world)||!issued.facility().equals(profile.firstFacility())||issued.revision()!=1||!issued.owner().equals(profile.owner())||issued.effectiveAt()!=profile.firstIssuedAt())throw new IllegalArgumentException("Score first issuance source binding");
        var saved=reads.query("SELECT boundary,step,body FROM credit_score_tasks WHERE world_id=? AND owner_id=?",List.of(world.value().toString(),ownerId.toString()),1);
        var state=saved.isEmpty()?initial(world,ownerId,boundary,origin):decodeState(saved.getFirst().get("body"));
        if(!state.s("world").equals(world.value().toString())||!state.s("owner").equals(ownerId.toString())
                ||!saved.isEmpty()&&(state.n("boundary")!=number(saved.getFirst(),"boundary")||state.n("step")!=number(saved.getFirst(),"step")))
            throw new IllegalArgumentException("Score fold projection binding");
        if(state.n("boundary")!=boundary) {
            if(!state.s("stage").equals("DONE")||state.n("boundary")+ScorePolicy.CYCLE_MILLIS!=boundary)throw new IllegalStateException("Prior score fold incomplete");
            var prior=state;state=initial(world,ownerId,boundary,origin);state.put("time",boundary-ScorePolicy.CYCLE_MILLIS);
            for(var key:prior.values.keySet())if(key.startsWith("p.")||Set.of("sourceTime","sourceFacility","sourceRevision").contains(key))state.put(key,prior.s(key));
        }
        var facts=new ArrayList<Journal.Record>();JournalEngine.Commit score=null;
        switch(state.s("stage")) {
            case "UTIL" -> utilization(reads,state,origin,facts);
            case "REPORT" -> reports(reads,state);
            case "DEFAULT" -> defaults(reads,state);
            case "INQUIRY" -> inquiries(reads,state,origin);
            case "COMMIT" -> score=score(reads,state,profile,origin);
            default -> throw new IllegalStateException("Completed score fold repeated");
        }
        state.put("step",Math.addExact(state.n("step"),1));
        var result=state;var scoreCommit=score;
        if(score!=null)facts.addAll(score.records());
        facts.add(new Journal.Record(TASK,1,encodeState(state)));
        return new JournalEngine.Commit(facts,new Journal.Result(1,score==null?"CREDIT_SCORE_FOLD_ADVANCED":"CREDIT_SCORE_RECALCULATED",ownerId.toString()),tx->{
            if(scoreCommit!=null)scoreCommit.projections().apply(tx);
            tx.execute("INSERT INTO credit_score_tasks VALUES(?,?,?,?,?) ON CONFLICT(world_id,owner_id) DO UPDATE SET boundary=excluded.boundary,step=excluded.step,body=excluded.body",List.of(world.value().toString(),ownerId.toString(),Long.toString(boundary),Long.toString(result.n("step")),encodeState(result)));
            for(var fact:facts)if(fact.type().equals(DAY)) {
                var area=decodeDay(fact.payload());
                tx.execute("INSERT INTO credit_utilization_days VALUES(?,?,?,?,?)",List.of(world.value().toString(),ownerId.toString(),Long.toString(boundary),Long.toString(area.dayStart()),fact.payload()));
            }
            if(scoreCommit!=null)tx.execute("UPDATE credit_score_heads SET next_at=? WHERE world_id=? AND owner_id=?",List.of(Long.toString(Math.addExact(boundary,ScorePolicy.CYCLE_MILLIS)),world.value().toString(),ownerId.toString()));
        });
    }

    private static State initial(WorldId world,UUID owner,long boundary,long origin) {
        var state=new State();
        state.put("world",world.value().toString());state.put("owner",owner.toString());state.put("boundary",boundary);state.put("step",0);state.put("stage","UTIL");
        state.put("time",Math.max(origin,boundary-ScorePolicy.CYCLE_MILLIS));state.put("sourceTime",-1);state.put("sourceFacility","");state.put("sourceRevision",0);
        state.put("areaN",0);state.put("areaD",1);state.put("active",0);state.put("dayN",0);state.put("dayD",1);state.put("dayActive",0);
        state.put("digest",CreditProfiles.hash("score-fold-v1|"+world.value()+"|"+owner+"|"+boundary));state.put("dayDigest",CreditProfiles.hash("day-start"));
        state.put("reportCursor","");state.put("reportRevision",0);state.put("reportTime",-1);state.put("pendingReport","");state.put("caseCursor","");state.put("inquiryCursor",-1);state.put("inquiryTime",-1);state.put("inquiryApplication","");state.put("buckets","");
        for(String key:List.of("met","missed","suppressed","young","middle","old"))state.put(key,0);
        return state;
    }

    private static void utilization(JournalEngine.ReadView reads,State state,long origin,List<Journal.Record> facts) {
        long boundary=state.n("boundary"),time=state.n("time");
        var next=reads.query("SELECT * FROM credit_reporting_sources WHERE world_id=? AND owner_id=? AND effective_at<=? AND (effective_at,facility_id,revision)>(?,?,?) ORDER BY effective_at,facility_id,revision LIMIT 1",List.of(state.s("world"),state.s("owner"),Long.toString(boundary),state.s("sourceTime"),state.s("sourceFacility"),state.s("sourceRevision")),1);
        var source=next.isEmpty()?null:CreditReporting.decode(next.getFirst().get("body"));
        if(source!=null) {
            var row=next.getFirst();
            if(!source.world().value().toString().equals(state.s("world"))||!source.owner().value().toString().equals(state.s("owner"))
                    ||!source.facility().toString().equals(row.get("facility_id"))||source.revision()!=number(row,"revision")
                    ||source.effectiveAt()!=number(row,"effective_at")||source.sourceSequence()!=number(row,"source_sequence"))
                throw new IllegalArgumentException("Utilization source projection binding");
        }
        if(source!=null&&source.effectiveAt()<=time) {
            String key="p."+source.facility();
            var previous=state.values.containsKey(key)?CreditReporting.decode(state.s(key)):null;
            if(previous!=null&&source.revision()<=previous.revision())throw new IllegalArgumentException("Revolving source revision regressed");
            if(source.active())state.put(key,CreditReporting.encode(source));else state.values.remove(key);
            state.put("sourceTime",source.effectiveAt());state.put("sourceFacility",source.facility().toString());state.put("sourceRevision",source.revision());
            digest(state,"source|"+CreditReporting.encode(source));return;
        }
        if(time==boundary) {
            if(source!=null)throw new IllegalArgumentException("Unprocessed boundary source");
            state.put("stage","REPORT");return;
        }
        long worldDay=origin+Math.floorDiv(time-origin,ScorePolicy.DAY_MILLIS)*ScorePolicy.DAY_MILLIS;
        long utcEnd=Math.multiplyExact(Math.floorDiv(time,86_400_000L)+1,86_400_000L);
        long end=Math.min(boundary,Math.min(worldDay+ScorePolicy.DAY_MILLIS,utcEnd));
        if(source!=null)end=Math.min(end,source.effectiveAt());
        var pool=state.values.entrySet().stream().filter(e->e.getKey().startsWith("p.")).map(e->CreditReporting.decode(e.getValue())).toList();
        if(pool.size()>3)throw new IllegalStateException("Revolving active portfolio bound");
        if(pool.stream().map(CreditReporting.Source::currency).distinct().count()>1) {
            Long nextFx=FxService.nextEffectiveMillis(reads,time,end);
            if(nextFx!=null)end=Math.min(end,nextFx);
        }
        if(end<=time)throw new IllegalStateException("Utilization fold did not advance");
        var area=integral(reads,pool,time,end);
        var total=new Fraction(new BigInteger(state.s("areaN")),new BigInteger(state.s("areaD"))).add(area.ratio());
        var daily=new Fraction(new BigInteger(state.s("dayN")),new BigInteger(state.s("dayD"))).add(area.ratio());
        state.put("areaN",total.n.toString());state.put("areaD",total.d.toString());state.put("dayN",daily.n.toString());state.put("dayD",daily.d.toString());
        long active=pool.isEmpty()?0:end-time;state.put("active",Math.addExact(state.n("active"),active));state.put("dayActive",Math.addExact(state.n("dayActive"),active));
        state.put("dayDigest",CreditProfiles.hash(state.s("dayDigest")+"|"+time+"|"+end+"|"+area.source()));digest(state,"interval|"+time+"|"+end+"|"+area.source());state.put("time",end);
        if(end==worldDay+ScorePolicy.DAY_MILLIS||end==boundary) {
            var day=new ScorePolicy.DailyArea(worldDay,Math.max(worldDay,boundary-ScorePolicy.CYCLE_MILLIS),end,daily.n,daily.d,state.n("dayActive"),end-Math.max(worldDay,boundary-ScorePolicy.CYCLE_MILLIS),1,true,state.s("dayDigest"));
            facts.add(new Journal.Record(DAY,1,encodeDay(state,day)));
            state.put("dayN",0);state.put("dayD",1);state.put("dayActive",0);state.put("dayDigest",CreditProfiles.hash("day-start|"+end));
        }
    }

    record Integral(Fraction ratio,String source) {}
    static Integral integral(JournalEngine.ReadView reads,List<CreditReporting.Source> pool,long start,long end) {
        if(pool.isEmpty())return new Integral(Fraction.ZERO,"NO_ACTIVE_REVOLVING_FACILITIES");
        boolean nativeUnit=pool.stream().map(CreditReporting.Source::currency).distinct().count()==1;
        var snapshot=nativeUnit?null:FxService.at(reads,start);
        if(!nativeUnit&&(snapshot==null||start<snapshot.effectiveMillis()||start-snapshot.effectiveMillis()>=86_400_000L))
            throw new IllegalStateException("CREDIT_UTILIZATION_HISTORICAL_FX_UNAVAILABLE");
        if(!nativeUnit&&FxService.nextEffectiveMillis(reads,start,end)!=null)throw new IllegalStateException("CREDIT_UTILIZATION_FX_BOUNDARY_REQUIRED");
        var publication=snapshot==null?null:snapshot.publication();
        Fraction owedStart=Fraction.ZERO,owedEnd=Fraction.ZERO,limit=Fraction.ZERO;
        for(var source:pool) {
            if(start<source.lastDay()||end>source.lastDay()+ScorePolicy.DAY_MILLIS)throw new IllegalStateException("CREDIT_UTILIZATION_DAILY_SOURCE_GAP");
            var rate=nativeUnit?BigDecimal.ONE:publication.rates().get(source.currency());
            if(rate==null)throw new IllegalStateException("CREDIT_UTILIZATION_CURRENCY_RATE_UNAVAILABLE");
            var fx=Fraction.decimal(rate);
            owedStart=owedStart.add(owed(source,start).multiply(fx));owedEnd=owedEnd.add(owed(source,end).multiply(fx));
            limit=limit.add(new Fraction(BigInteger.valueOf(source.limitMinor()),BigInteger.valueOf(source.currency().minorUnitsPerMajor())).multiply(fx));
        }
        var area=owedStart.add(owedEnd).multiply(new Fraction(BigInteger.valueOf(end-start),BigInteger.TWO)).divide(limit);
        return new Integral(area,nativeUnit?"NATIVE_"+pool.getFirst().currency():"FX_"+publication.updateId());
    }
    private static Fraction owed(CreditReporting.Source source,long time) {
        var unit=new Fraction(BigInteger.ONE,BigInteger.valueOf(source.currency().minorUnitsPerMajor()));
        var purchase=new Fraction(BigInteger.valueOf(source.purchaseMinor()),BigInteger.ONE).multiply(unit).add(Fraction.decimal(source.purchaseResidual()));
        var advance=new Fraction(BigInteger.valueOf(source.advanceMinor()),BigInteger.ONE).multiply(unit).add(Fraction.decimal(source.advanceResidual()));
        var duration=new Fraction(BigInteger.valueOf(time-source.lastDay()),BigInteger.valueOf(364L*ScorePolicy.DAY_MILLIS));
        return purchase.add(advance).add(purchase.multiply(Fraction.decimal(source.purchaseApr())).add(advance.multiply(Fraction.decimal(source.advanceApr()))).multiply(duration));
    }

    private static void reports(JournalEngine.ReadView reads,State state) {
        var rows=reads.query("SELECT report_id,revision,report_at,body FROM debt_reports WHERE world_id=? AND owner_id=? AND effective_at<=? AND report_at>? AND report_at<=? AND (report_at,report_id,revision)>(?,?,?) ORDER BY report_at,report_id,revision LIMIT 16",List.of(state.s("world"),state.s("owner"),state.s("boundary"),Long.toString(state.n("boundary")-YEAR),state.s("boundary"),state.s("reportTime"),state.s("reportCursor"),state.s("reportRevision")),16);
        for(var row:rows) {
            var report=DebtRecords.decodeReport(row.get("body"));
            if(!report.world().value().toString().equals(state.s("world"))||!report.owner().value().toString().equals(state.s("owner"))||!report.id().toString().equals(row.get("report_id"))||report.revision()!=number(row,"revision")||report.reportAt()!=number(row,"report_at"))throw new IllegalArgumentException("Score report owner binding");
            if(!state.s("reportCursor").equals(report.id().toString())){finishReport(reads,state);state.put("pendingReport","");}
            if(!report.cured())state.put("pendingReport",row.get("body"));
            state.put("reportCursor",report.id().toString());state.put("reportRevision",report.revision());state.put("reportTime",report.reportAt());digest(state,"report|"+row.get("body"));
        }
        if(rows.size()<16){finishReport(reads,state);state.put("pendingReport","");state.put("stage","DEFAULT");}
    }
    private static void finishReport(JournalEngine.ReadView reads,State state) {
        if(state.s("pendingReport").isEmpty())return;
        var report=DebtRecords.decodeReport(state.s("pendingReport"));
        var defaults=reads.query("SELECT body FROM debt_cases WHERE world_id=? AND kind=? AND facility_id=?",List.of(state.s("world"),report.kind().name(),report.facility().toString()),1);
        boolean suppressed=false;
        if(!defaults.isEmpty()) {
            var debt=DebtRecords.decodeCase(defaults.getFirst().get("body"));
            if(!debt.owner().value().toString().equals(state.s("owner"))||!debt.world().value().toString().equals(state.s("world"))||debt.kind()!=report.kind()||!debt.facility().equals(report.facility()))throw new IllegalArgumentException("Score suppression owner binding");
            suppressed=state.n("boundary")>=debt.defaultAt()&&state.n("boundary")<debt.reportExpires();
        }
        String key=suppressed?"suppressed":report.metAtSnapshot()?"met":"missed";state.put(key,Math.addExact(state.n(key),1));
    }
    private static void defaults(JournalEngine.ReadView reads,State state) {
        var rows=reads.query("SELECT case_id,body FROM debt_cases WHERE world_id=? AND owner_id=? AND case_id>? ORDER BY case_id LIMIT 16",List.of(state.s("world"),state.s("owner"),state.s("caseCursor")),16);
        for(var row:rows) {
            var debt=DebtRecords.decodeCase(row.get("body"));
            if(!debt.owner().value().toString().equals(state.s("owner"))||!debt.world().value().toString().equals(state.s("world"))||!debt.id().toString().equals(row.get("case_id")))throw new IllegalArgumentException("Score default owner binding");
            long age=state.n("boundary")-debt.defaultAt();
            if(age>=0&&state.n("boundary")<debt.reportExpires()) {
                String key=age<YEAR?"young":age<2*YEAR?"middle":"old";state.put(key,Math.addExact(state.n(key),1));
                digest(state,"default|"+debt.id()+"|"+debt.kind()+"|"+debt.facility()+"|"+debt.defaultAt()+"|"+debt.reportExpires());
            }
            state.put("caseCursor",row.get("case_id"));
        }
        if(rows.size()<16)state.put("stage","INQUIRY");
    }
    private static void inquiries(JournalEngine.ReadView reads,State state,long origin) {
        long first=state.n("boundary")-84*ScorePolicy.DAY_MILLIS;
        long firstBucket=Math.max(0,Math.floorDiv(first-origin,7*ScorePolicy.DAY_MILLIS));
        long cursorBucket=state.n("inquiryCursor");long cursorTime=state.n("inquiryTime");
        if(cursorBucket<firstBucket){cursorBucket=firstBucket;cursorTime=first;}
        var rows=reads.query("SELECT bucket,accepted_at,application_id FROM credit_inquiry_applications WHERE world_id=? AND owner_id=? AND accepted_at>? AND accepted_at<=? AND (bucket,accepted_at,application_id)>(?,?,?) ORDER BY bucket,accepted_at,application_id LIMIT 16",List.of(state.s("world"),state.s("owner"),Long.toString(first),state.s("boundary"),Long.toString(cursorBucket),Long.toString(cursorTime),state.s("inquiryApplication")),16);
        var buckets=new TreeSet<Long>();
        if(!state.s("buckets").isEmpty())for(String value:state.s("buckets").split(","))buckets.add(Long.parseLong(value));
        for(var row:rows) {
            buckets.add(number(row,"bucket"));state.put("inquiryCursor",row.get("bucket"));state.put("inquiryTime",row.get("accepted_at"));state.put("inquiryApplication",row.get("application_id"));
            digest(state,"inquiry|"+row.get("bucket")+"|"+row.get("accepted_at")+"|"+row.get("application_id"));
        }
        if(buckets.size()>13)throw new IllegalArgumentException("Score inquiry bucket bound");
        state.put("buckets",String.join(",",buckets.stream().map(Object::toString).toList()));
        if(rows.size()<16)state.put("stage","COMMIT");
    }
    private static JournalEngine.Commit score(JournalEngine.ReadView reads,State state,CreditProfiles.Profile profile,long origin) {
        if(!reads.query("SELECT obligation_id FROM debt_obligations WHERE world_id=? AND owner_id=? AND reported=0 AND due_at<=? LIMIT 1",List.of(state.s("world"),state.s("owner"),Long.toString(state.n("boundary")-2*ScorePolicy.DAY_MILLIS)),1).isEmpty())
            throw new IllegalStateException("CREDIT_SCORE_REPORT_FRONTIER_INCOMPLETE");
        var area=new ScorePolicy.Utilization(state.n("boundary")-ScorePolicy.CYCLE_MILLIS,state.n("boundary"),new BigInteger(state.s("areaN")),new BigInteger(state.s("areaD")),state.n("active"),true,state.s("digest"));
        var buckets=state.s("buckets").isEmpty()?List.<Long>of():Arrays.stream(state.s("buckets").split(",")).map(Long::parseLong).toList();
        var input=new ScorePolicy.SummaryInput(origin,profile.firstIssuedAt(),state.n("boundary"),state.n("met"),state.n("missed"),state.n("suppressed"),state.n("young"),state.n("middle"),state.n("old"),buckets,area,true,state.s("digest"));
        var result=ScorePolicy.calculate(input);
        if(!result.available())throw new IllegalStateException("Credit score source unavailable "+result.reason());
        var request=new CreditScoring.Request(profile.revision(),origin,state.n("boundary"),ScorePolicy.VERSION,state.n("boundary"),state.n("step"),result.fingerprint());
        var owner=new PartyRef(profile.world(),new PartyRef.Player(profile.owner()));
        var registration=CreditScoring.futureRegistration(owner,request,(r,p,o,b)->Optional.of(new CreditScoring.ReportedScore(true,FinancialCalendar.Stage.REPORTING,b,state.n("step"),profile.firstIssuedAt(),result)),(actor,p,r)->actor.equals(profile.owner()));
        var operation=CreditReporting.stable("score-result|"+profile.world().value()+"|"+profile.owner().value()+"|"+state.n("boundary"));
        try {
            var decision=registration.handler().prepare(new Journal.Intent(new OperationId(operation),new RequestId(operation),profile.owner(),CreditScoring.RECALCULATE,request.request(owner),null,null),reads);
            if(!(decision instanceof JournalEngine.Commit commit))throw new IllegalStateException("Score result rejected");
            state.put("stage","DONE");return commit;
        }catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("Score result preparation failed",failure);}
    }

    private static void digest(State state,String addition){state.put("digest",CreditProfiles.hash(state.s("digest")+"|"+addition));}
    private static String encodeDay(State state,ScorePolicy.DailyArea area) {
        return CreditReporting.pack(List.of(state.s("world"),state.s("owner"),state.s("boundary"),Long.toString(area.dayStart()),Long.toString(area.start()),Long.toString(area.end()),area.areaNumerator().toString(),area.areaDenominator().toString(),Long.toString(area.activeMillis()),area.sourceFingerprint()));
    }
    public static ScorePolicy.DailyArea decodeDay(String body) {
        var f=CreditReporting.unpack(body,10);UUID.fromString(f.get(0));UUID.fromString(f.get(1));long boundary=Long.parseLong(f.get(2)),start=Long.parseLong(f.get(4)),end=Long.parseLong(f.get(5));
        if(end>boundary||start<boundary-ScorePolicy.CYCLE_MILLIS)throw new IllegalArgumentException("Daily score source boundary");
        return new ScorePolicy.DailyArea(Long.parseLong(f.get(3)),start,end,new BigInteger(f.get(6)),new BigInteger(f.get(7)),Long.parseLong(f.get(8)),end-start,1,true,f.get(9));
    }
    public static RecordCatalog register(RecordCatalog catalog) {
        return catalog.with(new RecordCatalog.Key(TASK,1),CreditScoreRuntime::decodeState).with(new RecordCatalog.Key(DAY,1),CreditScoreRuntime::decodeDay);
    }
    private static final class State {
        private final TreeMap<String,String> values=new TreeMap<>();
        String s(String key){return Objects.requireNonNull(values.get(key),"Missing score fold field "+key);}
        long n(String key){long value=Long.parseLong(s(key));if(!Long.toString(value).equals(s(key)))throw new IllegalArgumentException("Score fold number");return value;}
        void put(String key,long value){values.put(key,Long.toString(value));}
        void put(String key,String value){values.put(key,value);}
    }
    private static String encodeState(State state) {
        var fields=new ArrayList<String>();state.values.forEach((key,value)->{fields.add(key);fields.add(value);});
        return CreditReporting.pack(fields);
    }
    private static State decodeState(String body) {
        var fields=CreditReporting.unpack(body,body.split("\\|",-1).length);if(fields.size()%2!=0||fields.size()>96)throw new IllegalArgumentException("Score fold field bound");
        var state=new State();
        for(int at=0;at<fields.size();at+=2)if(state.values.put(fields.get(at),fields.get(at+1))!=null)throw new IllegalArgumentException("Duplicate score fold field");
        var required=Set.of("world","owner","boundary","step","stage","time","sourceTime","sourceFacility","sourceRevision",
            "areaN","areaD","active","dayN","dayD","dayActive","digest","dayDigest","reportCursor","reportRevision","reportTime",
            "pendingReport","caseCursor","inquiryCursor","inquiryTime","inquiryApplication","buckets","met","missed","suppressed","young","middle","old");
        if(!state.values.keySet().containsAll(required)||state.values.keySet().stream().anyMatch(k->!required.contains(k)&&!k.startsWith("p.")))
            throw new IllegalArgumentException("Score fold fields");
        uuid(state.s("world"),false);uuid(state.s("owner"),false);
        long boundary=state.n("boundary"),time=state.n("time");
        if(!Set.of("UTIL","REPORT","DEFAULT","INQUIRY","COMMIT","DONE").contains(state.s("stage"))||boundary<ScorePolicy.CYCLE_MILLIS
                ||state.n("step")<0||time<boundary-ScorePolicy.CYCLE_MILLIS||time>boundary
                ||state.n("active")<0||state.n("active")>ScorePolicy.CYCLE_MILLIS||state.n("dayActive")<0
                ||state.n("dayActive")>ScorePolicy.DAY_MILLIS||state.n("dayActive")>state.n("active"))throw new IllegalArgumentException("Score fold state");
        for(String key:List.of("met","missed","suppressed","young","middle","old"))if(state.n(key)<0)throw new IllegalArgumentException("Score fold count");
        for(String key:List.of("areaN","areaD","dayN","dayD")) {
            var n=new BigInteger(state.s(key));
            if(!n.toString().equals(state.s(key))||n.bitLength()>8192||n.signum()<0||key.endsWith("D")&&n.signum()==0)
                throw new IllegalArgumentException("Score fold area");
        }
        CreditProfiles.fingerprint(state.s("digest"));CreditProfiles.fingerprint(state.s("dayDigest"));
        uuid(state.s("sourceFacility"),true);uuid(state.s("reportCursor"),true);uuid(state.s("caseCursor"),true);uuid(state.s("inquiryApplication"),true);
        if(state.n("sourceTime")< -1||state.n("sourceRevision")<0||state.s("sourceFacility").isEmpty()!=(state.n("sourceRevision")==0)
                ||state.s("sourceFacility").isEmpty()!=(state.n("sourceTime")== -1)||state.n("sourceTime")>time
                ||state.n("reportTime")< -1||state.n("reportRevision")<0||state.s("reportCursor").isEmpty()!=(state.n("reportRevision")==0)
                ||state.s("reportCursor").isEmpty()!=(state.n("reportTime")== -1)||state.n("reportTime")>boundary
                ||state.n("inquiryCursor")< -1||state.n("inquiryTime")< -1||state.n("inquiryTime")>boundary
                ||state.s("inquiryApplication").isEmpty()!=(state.n("inquiryCursor")== -1)
                ||state.s("inquiryApplication").isEmpty()!=(state.n("inquiryTime")== -1))throw new IllegalArgumentException("Score fold cursor");
        if(!state.s("pendingReport").isEmpty()) {
            var report=DebtRecords.decodeReport(state.s("pendingReport"));
            if(!report.id().toString().equals(state.s("reportCursor"))||!report.owner().value().toString().equals(state.s("owner"))
                    ||!report.world().value().toString().equals(state.s("world"))||report.effectiveAt()>boundary||report.cured())
                throw new IllegalArgumentException("Pending score report binding");
        }
        long poolSize=state.values.keySet().stream().filter(k->k.startsWith("p.")).count();
        // Same-instant facility ordering can retain three old, three new and one transient line.
        if(poolSize>7)throw new IllegalArgumentException("Score portfolio state bound");
        for(var e:state.values.entrySet())if(e.getKey().startsWith("p.")) {
            var source=CreditReporting.decode(e.getValue());
            if(!e.getKey().equals("p."+source.facility())||!source.world().value().toString().equals(state.s("world"))
                    ||!source.owner().value().toString().equals(state.s("owner"))||!source.active()||source.effectiveAt()>time)
                throw new IllegalArgumentException("Score portfolio binding");
        }
        if(!state.s("buckets").isEmpty()) {
            var buckets=Arrays.stream(state.s("buckets").split(",")).map(Long::parseLong).toList();
            if(buckets.size()>13||buckets.stream().anyMatch(n->n<0)||new TreeSet<>(buckets).size()!=buckets.size()
                    ||!new ArrayList<>(new TreeSet<>(buckets)).equals(buckets))throw new IllegalArgumentException("Score bucket state");
        }
        if(!encodeState(state).equals(body))throw new IllegalArgumentException("Noncanonical score fold state");return state;
    }
    private static void uuid(String value,boolean emptyAllowed) {
        if(emptyAllowed&&value.isEmpty())return;
        if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException("Score fold UUID");
    }
    record Fraction(BigInteger n,BigInteger d) {
        static final Fraction ZERO=new Fraction(BigInteger.ZERO,BigInteger.ONE);
        Fraction {if(d.signum()<=0)throw new IllegalArgumentException("Nonnegative utilization fraction");var gcd=n.gcd(d);n=n.divide(gcd);d=d.divide(gcd);if(n.bitLength()>8192||d.bitLength()>8192)throw new IllegalArgumentException("Utilization precision budget");}
        Fraction add(Fraction b){return new Fraction(n.multiply(b.d).add(b.n.multiply(d)),d.multiply(b.d));}
        Fraction multiply(Fraction b){return new Fraction(n.multiply(b.n),d.multiply(b.d));}
        Fraction divide(Fraction b){return new Fraction(n.multiply(b.d),d.multiply(b.n));}
        static Fraction decimal(BigDecimal value){return value.scale()<0?new Fraction(value.unscaledValue().multiply(BigInteger.TEN.pow(-value.scale())),BigInteger.ONE):new Fraction(value.unscaledValue(),BigInteger.TEN.pow(value.scale()));}
    }
    private static long number(Map<String,String> row,String key){return Long.parseLong(row.get(key));}
}
