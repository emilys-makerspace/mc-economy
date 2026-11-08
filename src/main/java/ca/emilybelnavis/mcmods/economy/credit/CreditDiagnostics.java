package ca.emilybelnavis.mcmods.economy.credit;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Worker-owned read snapshots. Callers supply owner or separately audited inspection authority. */
public final class CreditDiagnostics {
    private CreditDiagnostics() {}
    public record BucketView(String kind,long principalMinor,long postedInterestMinor,BigDecimal residualMajor,
                             BigDecimal periodEarnedMajor,BigDecimal apr) {}
    public record StatementView(String id,int version,long closedAt,long dueAt,long balanceMinor,long principalMinor,
                                BigDecimal periodInterestMajor,long newMinimumMinor,long totalRequiredMinor) {}
    public record FacilityView(UUID id,String issuer,String network,EconomyCurrency currency,String status,String access,
            long limitMinor,long availableCreditMinor,long principalMinor,long postedInterestMinor,long currentBalanceMinor,
            BigDecimal exactBalanceMajor,BucketView purchase,BucketView advance,long requiredMinor,long pastDueMinor,
            Long dueAt,StatementView latestStatement,long nextDay,long nextStatement,long revision,boolean scheduleCurrent) {}
    public record FacilityPage(List<FacilityView> entries,UUID nextCursor,long journalFrontier,long asOf) {
        public FacilityPage {entries=List.copyOf(entries);}
    }
    public record EventCursor(long sequence,int position) {
        public EventCursor {if(sequence<0||position< -1||sequence==0&&position!= -1)throw new IllegalArgumentException("Credit diagnostic cursor");}
        public static EventCursor start(){return new EventCursor(0,-1);}
    }
    public record EventView(long sequence,int position,int version,String kind,long effectiveAt,Map<String,String> details) {
        public EventView {details=Map.copyOf(details);}
    }
    public record EventPage(List<EventView> entries,EventCursor nextCursor,long journalFrontier) {
        public EventPage {entries=List.copyOf(entries);}
    }
    public record DailyReceipt(long sequence,int position,int version,CreditAuditFacts.Daily witness) {}
    public record PaymentReceipt(long sequence,int position,int version,CreditAuditFacts.Payment witness) {}
    public record DailyPage(List<DailyReceipt> entries,EventCursor nextCursor,long journalFrontier) {
        public DailyPage {entries=List.copyOf(entries);}
    }
    public record PaymentPage(List<PaymentReceipt> entries,EventCursor nextCursor,long journalFrontier) {
        public PaymentPage {entries=List.copyOf(entries);}
    }
    public record Reconciliation(long ownerCurrencyDebtMinor,long borrowerLiabilityMinor,long issuerCurrencyDebtMinor,
                                 long issuerReceivableMinor,boolean ownerMatches,boolean issuerMatches,
                                 BigInteger ownerJournalMinor,BigInteger issuerJournalMinor,boolean ownerJournalComplete,
                                 boolean issuerJournalComplete,boolean ownerJournalMatches,boolean issuerJournalMatches,
                                 boolean ownerFacilitiesComplete,boolean issuerFacilitiesComplete) {}
    public record ProviderHead(String provider,String implementations,Long completedAt,Long applyingAt,
                               String applyingCursor,long completedEffects) {}
    public record ScheduleView(long nextDay,long nextStatement,long overdueDays,long overdueStatements,boolean current) {}
    public record OperatorView(FacilityView facility,long journalFrontier,long asOf,Reconciliation reconciliation,
                               ScheduleView schedule,List<ProviderHead> providers,EventPage legacyEvents,DailyPage daily,PaymentPage payments) {
        public OperatorView {providers=List.copyOf(providers);}
    }

    public static FacilityPage ownerPage(JournalEngine.ReadView reads,PartyRef owner,UUID after,int limit,long now) {
        bounds(limit);instant(now);requirePlayer(owner);
        var rows=CreditFacilities.history(reads,owner,after,limit);
        var views=rows.stream().map(f->view(reads,f,now)).toList();
        UUID next=null;
        if(rows.size()==limit){var last=rows.getLast().id();
            var more=reads.query("SELECT facility_id FROM credit_facilities WHERE world_id=? AND owner_id=? AND facility_id>? ORDER BY facility_id LIMIT 1",List.of(owner.world().value().toString(),player(owner).value().toString(),last.toString()),1);
            if(!more.isEmpty())next=last;
        }
        return new FacilityPage(views,next,frontier(reads),now);
    }
    public static Optional<FacilityView> facility(JournalEngine.ReadView reads,PartyRef owner,UUID id,long now) {
        instant(now);requirePlayer(owner);
        return CreditFacilities.find(reads,Objects.requireNonNull(id)).filter(f->f.party().equals(owner)).map(f->view(reads,f,now));
    }
    public static CreditFacilities.StatementPage statements(JournalEngine.ReadView reads,PartyRef owner,UUID id,long afterClosedAt,int limit) {
        requirePlayer(owner);bounds(limit);
        return CreditFacilities.statements(reads,owner,id,afterClosedAt,limit);
    }
    public static Optional<PaymentPage> ownerPayments(JournalEngine.ReadView reads,PartyRef owner,UUID id,EventCursor after,int limit) {
        bounds(limit);requirePlayer(owner);Objects.requireNonNull(after);
        var facility=CreditFacilities.find(reads,Objects.requireNonNull(id)).filter(f->f.party().equals(owner)).orElse(null);
        if(facility==null)return Optional.empty();
        var page=payments(reads,facility,after,limit,frontier(reads));
        for(var receipt:page.entries()) {
            var snapshots=reads.query("SELECT record_version,payload FROM journal_records WHERE sequence=? AND record_type=? ORDER BY position LIMIT 64",
                List.of(Long.toString(receipt.sequence()),CreditFacilities.FACT),64);
            boolean bound=false;
            for(var row:snapshots) {
                if(!row.get("record_version").equals("1"))throw new IllegalArgumentException("Unsupported credit snapshot version");
                var source=CreditFacilities.decodeSnapshot(row.get("payload"));
                if(source.id().equals(id)) {
                    if(!source.party().equals(owner)||source.currency()!=facility.currency()||!source.issuer().equals(facility.issuer()))
                        throw new IllegalArgumentException("Credit payment owner binding");
                    bound=true;
                }
            }
            if(!bound)throw new IllegalArgumentException("Credit payment source unavailable");
        }
        return Optional.of(page);
    }
    public static Optional<OperatorView> inspect(JournalEngine.ReadView reads,PartyRef owner,UUID id,long now,EventCursor after,int limit) {
        instant(now);bounds(limit);requirePlayer(owner);Objects.requireNonNull(after);
        var f=CreditFacilities.find(reads,Objects.requireNonNull(id)).filter(v->v.party().equals(owner)).orElse(null);
        if(f==null)return Optional.empty();
        long frontier=frontier(reads);
        return Optional.of(new OperatorView(view(reads,f,now),frontier,now,reconcile(reads,f,frontier),schedule(f,now),providers(reads,f.id()),legacyEvents(reads,f,after,limit,frontier),daily(reads,f,after,limit,frontier),payments(reads,f,after,limit,frontier)));
    }
    public static long frontier(JournalEngine.ReadView reads) {
        var rows=reads.query("SELECT COALESCE(MAX(sequence),0) AS frontier FROM journal_transactions",List.of(),1);
        return rows.isEmpty()?0:Long.parseLong(rows.getFirst().get("frontier"));
    }
    private static FacilityView view(JournalEngine.ReadView reads,CreditFacilities.Facility f,long now) {
        long interest=Math.addExact(f.purchase().interestMinor(),f.advance().interestMinor());
        long pastDue=0;Long due=null;
        for(var r:f.requirements())if(r.unmetMinor()>0){
            if(r.dueAt()<now)pastDue=Math.addExact(pastDue,r.unmetMinor());
            due=due==null?r.dueAt():Math.min(due,r.dueAt());
        }
        long available=f.status()==CreditFacilities.Status.ACTIVE&&f.access()==CreditFacilities.Access.ENABLED?Math.max(0,Math.subtractExact(f.limitMinor(),f.owedMinor())):0;
        var exact=BigDecimal.valueOf(f.owedMinor()).divide(BigDecimal.valueOf(f.currency().minorUnitsPerMajor()))
            .add(f.purchase().residualMajor()).add(f.advance().residualMajor());
        var s=schedule(f,now);
        return new FacilityView(f.id(),f.issuer().key(),f.network().name(),f.currency(),f.status().name(),f.access().name(),f.limitMinor(),available,
            f.principalMinor(),interest,f.owedMinor(),exact,bucket(f.purchase()),bucket(f.advance()),f.requiredMinor(),pastDue,due,latestStatement(reads,f),f.nextDay(),f.nextStatement(),f.revision(),s.current());
    }
    private static BucketView bucket(CardServicing.Bucket b){return new BucketView(b.kind().name(),b.principalMinor(),b.interestMinor(),b.residualMajor(),b.periodEarnedMajor(),b.apr());}
    private static StatementView latestStatement(JournalEngine.ReadView reads,CreditFacilities.Facility f){
        var rows=reads.query("SELECT body FROM credit_statements WHERE world_id=? AND facility_id=? AND owner_id=? ORDER BY closed_at DESC LIMIT 1",List.of(f.world().value().toString(),f.id().toString(),f.owner().value().toString()),1);
        if(rows.isEmpty())return null;
        var body=rows.getFirst().get("body");var fields=unpack(body,11);
        if(!fields.get(0).equals(f.world().value().toString())||!fields.get(1).equals(f.id().toString())||!fields.get(2).equals(f.owner().value().toString()))throw new IllegalArgumentException("Statement owner binding");
        return statement(body);
    }
    public static StatementView statement(CreditFacilities.StatementSnapshot snapshot){
        var s=snapshot.statement();return new StatementView(s.id(),1,s.closedAt(),s.dueAt(),s.debtMinor(),s.principalMinor(),s.periodInterestMajor(),s.newRequirementMinor(),s.totalRequiredMinor());
    }
    private static StatementView statement(String payload){
        CreditFacilities.validateStatementFact(payload);var p=unpack(payload,11);
        return new StatementView(p.get(3),1,number(p.get(4)),number(p.get(5)),number(p.get(6)),number(p.get(7)),new BigDecimal(p.get(8)),number(p.get(9)),number(p.get(10)));
    }
    private static ScheduleView schedule(CreditFacilities.Facility f,long now){
        boolean active=f.status()==CreditFacilities.Status.ACTIVE;
        long days=active&&now>=f.nextDay()?Math.addExact(Math.floorDiv(now-f.nextDay(),CreditFacilities.DAY),1):0;
        long statements=active&&now>=f.nextStatement()?Math.addExact(Math.floorDiv(now-f.nextStatement(),CreditFacilities.MONTH),1):0;
        return new ScheduleView(f.nextDay(),f.nextStatement(),days,statements,days==0&&statements==0);
    }
    private static Reconciliation reconcile(JournalEngine.ReadView reads,CreditFacilities.Facility f,long frontier){
        var ownerFacilities=facilityTotal(reads,"SELECT debt_minor FROM credit_facilities WHERE world_id=? AND owner_id=? AND currency=? AND debt_minor>0 ORDER BY facility_id LIMIT 129",List.of(f.world().value().toString(),f.owner().value().toString(),f.currency().name()));
        var issuerFacilities=facilityTotal(reads,"SELECT debt_minor FROM credit_facilities WHERE world_id=? AND issuer=? AND currency=? AND debt_minor>0 AND NOT EXISTS(SELECT 1 FROM debt_cases c WHERE c.world_id=credit_facilities.world_id AND c.kind='CARD' AND c.facility_id=credit_facilities.facility_id AND c.sale='SOLD') ORDER BY facility_id LIMIT 129",List.of(f.world().value().toString(),f.issuer().key(),f.currency().name()));
        var loanRows=reads.query("SELECT debt_minor FROM loans WHERE world_id=? AND lender=? AND currency=? AND debt_minor>0 AND NOT EXISTS(SELECT 1 FROM debt_cases c WHERE c.world_id=loans.world_id AND c.kind='LOAN' AND c.facility_id=loans.loan_id AND c.sale='SOLD') ORDER BY loan_id LIMIT 129",List.of(f.world().value().toString(),f.issuer().key(),f.currency().name()),129);
        int loanBudget=Math.max(0,128-issuerFacilities.count());
        long loanDebt=0;for(var row:loanRows.stream().limit(loanBudget).toList())loanDebt=Math.addExact(loanDebt,number(row.get("debt_minor")));
        boolean combinedComplete=issuerFacilities.complete()&&loanRows.size()<=loanBudget;
        issuerFacilities=new FacilityTotal(Math.addExact(issuerFacilities.minor(),loanDebt),combinedComplete,Math.min(128,issuerFacilities.count()+loanRows.size()));
        long ownerDebt=ownerFacilities.minor(),issuerDebt=issuerFacilities.minor();
        var borrower=new InstitutionCatalog.InstitutionId("economy:borrower/"+f.owner().value());
        long liability=Math.negateExact(Settlement.posted(reads,FinancialAccounts.id(f.world(),borrower,FinancialAccounts.Role.LIABILITY,f.currency()),f.currency()));
        long receivable=Settlement.posted(reads,FinancialAccounts.id(f.world(),f.issuer(),FinancialAccounts.Role.RECEIVABLE,f.currency()),f.currency());
        var ownerJournal=journalTotal(reads,FinancialAccounts.id(f.world(),borrower,FinancialAccounts.Role.LIABILITY,f.currency()),f.currency(),frontier);
        var issuerJournal=journalTotal(reads,FinancialAccounts.id(f.world(),f.issuer(),FinancialAccounts.Role.RECEIVABLE,f.currency()),f.currency(),frontier);
        var ownerAmount=ownerJournal.minor().negate();var issuerAmount=issuerJournal.minor();
        return new Reconciliation(ownerDebt,liability,issuerDebt,receivable,ownerFacilities.complete()&&ownerDebt==liability,issuerFacilities.complete()&&issuerDebt==receivable,
            ownerAmount,issuerAmount,ownerJournal.complete(),issuerJournal.complete(),
            ownerFacilities.complete()&&ownerJournal.complete()&&ownerAmount.equals(BigInteger.valueOf(ownerDebt))&&ownerAmount.equals(BigInteger.valueOf(liability)),
            issuerFacilities.complete()&&issuerJournal.complete()&&issuerAmount.equals(BigInteger.valueOf(issuerDebt))&&issuerAmount.equals(BigInteger.valueOf(receivable)),ownerFacilities.complete(),issuerFacilities.complete());
    }
    private record JournalTotal(BigInteger minor,boolean complete) {}
    private static JournalTotal journalTotal(JournalEngine.ReadView reads,UUID account,EconomyCurrency currency,long frontier){
        var rows=reads.query("SELECT payload FROM journal_records WHERE record_type='economy:posting' AND record_version IN (1,2,3,4) AND substr(payload,1,36)=? AND sequence<=? ORDER BY sequence,position LIMIT 129",List.of(account.toString(),Long.toString(frontier)),129);
        BigInteger total=BigInteger.ZERO;
        for(var row:rows.stream().limit(128).toList()){
            var p=row.get("payload").split("\\|",-1);if(p.length!=5||!p[0].equals(account.toString())||!p[2].equals(currency.name()))throw new IllegalArgumentException("Credit posting identity");
            total=total.add(BigInteger.valueOf(number(p[3])));
        }
        return new JournalTotal(total,rows.size()<=128);
    }
    private record FacilityTotal(long minor,boolean complete,int count) {}
    private static FacilityTotal facilityTotal(JournalEngine.ReadView reads,String sql,List<String> args){
        var rows=reads.query(sql,args,129);long total=0;
        for(var row:rows.stream().limit(128).toList())total=Math.addExact(total,number(row.get("debt_minor")));
        return new FacilityTotal(total,rows.size()<=128,Math.min(128,rows.size()));
    }
    private static List<ProviderHead> providers(JournalEngine.ReadView reads,UUID facility){
        var result=new ArrayList<ProviderHead>();
        for(String provider:List.of("economy:a6/credit-daily","economy:a6/credit-statements")){
            var rows=reads.query("SELECT implementations,completed_ms FROM financial_deadline_providers WHERE provider=?",List.of(provider),1);
            if(rows.isEmpty()){result.add(new ProviderHead(provider,"NOT_INSTALLED",null,null,"",0));continue;}
            var row=rows.getFirst();var applying=reads.query("SELECT effective_ms,cursor,completed_effects FROM financial_deadline_events WHERE provider=? AND state='APPLYING' LIMIT 1",List.of(provider),1);
            var a=applying.isEmpty()?null:applying.getFirst();
            result.add(new ProviderHead(provider,row.get("implementations"),nullableLong(row.get("completed_ms")),a==null?null:number(a.get("effective_ms")),a==null||a.get("cursor").isEmpty()?"":a.get("cursor").equals(facility.toString())?"THIS_FACILITY":"OTHER_TARGET",a==null?0:number(a.get("completed_effects"))));
        }
        return result;
    }
    private static DailyPage daily(JournalEngine.ReadView reads,CreditFacilities.Facility f,EventCursor after,int limit,long frontier){
        var rows=auditRows(reads,CreditAuditFacts.DAILY,f.id(),after,limit,frontier);
        var entries=rows.stream().limit(limit).map(r->{
            int version=Integer.parseInt(r.get("record_version"));if(version!=1)throw new IllegalArgumentException("Unsupported credit daily witness");
            var witness=CreditAuditFacts.decodeDaily(r.get("payload"));
            if(!witness.facility().equals(f.id())||!witness.world().equals(f.world())||witness.currency()!=f.currency())throw new IllegalArgumentException("Daily witness binding");
            return new DailyReceipt(number(r.get("sequence")),Integer.parseInt(r.get("position")),version,witness);
        }).toList();
        var last=entries.isEmpty()?null:entries.getLast();
        return new DailyPage(entries,rows.size()>limit?new EventCursor(last.sequence(),last.position()):null,frontier);
    }
    private static PaymentPage payments(JournalEngine.ReadView reads,CreditFacilities.Facility f,EventCursor after,int limit,long frontier){
        var rows=auditRows(reads,CreditAuditFacts.PAYMENT,f.id(),after,limit,frontier);
        var entries=rows.stream().limit(limit).map(r->{
            int version=Integer.parseInt(r.get("record_version"));if(version!=1)throw new IllegalArgumentException("Unsupported credit payment witness");
            var witness=CreditAuditFacts.decodePayment(r.get("payload"));
            if(!witness.facility().equals(f.id())||!witness.world().equals(f.world())||witness.currency()!=f.currency())throw new IllegalArgumentException("Payment witness binding");
            return new PaymentReceipt(number(r.get("sequence")),Integer.parseInt(r.get("position")),version,witness);
        }).toList();
        var last=entries.isEmpty()?null:entries.getLast();
        return new PaymentPage(entries,rows.size()>limit?new EventCursor(last.sequence(),last.position()):null,frontier);
    }
    private static List<Map<String,String>> auditRows(JournalEngine.ReadView reads,String type,UUID facility,EventCursor after,int limit,long frontier){
        return reads.query("SELECT sequence,position,record_version,payload FROM journal_records WHERE record_type IN('economy:a6/credit-daily-witness','economy:a6/credit-payment-allocation') AND record_type=? AND substr(payload,1,36)=? AND sequence<=? AND (sequence>? OR (sequence=? AND position>?)) ORDER BY sequence,position LIMIT ?",
            List.of(type,facility.toString(),Long.toString(frontier),Long.toString(after.sequence()),Long.toString(after.sequence()),Integer.toString(after.position()),Integer.toString(limit+1)),limit+1);
    }
    private static EventPage legacyEvents(JournalEngine.ReadView reads,CreditFacilities.Facility f,EventCursor after,int limit,long frontier){
        String key=Base64.getUrlEncoder().withoutPadding().encodeToString(f.id().toString().getBytes(StandardCharsets.UTF_8));
        var rows=reads.query("SELECT sequence,position,record_version,payload FROM journal_records WHERE record_type IN('economy:a6/credit-event','economy:a6/credit-facility') AND record_type='economy:a6/credit-event' AND substr(payload,instr(payload,'|')+1,48)=? AND sequence<=? AND (sequence>? OR (sequence=? AND position>?)) ORDER BY sequence,position LIMIT ?",
            List.of(key,Long.toString(frontier),Long.toString(after.sequence()),Long.toString(after.sequence()),Integer.toString(after.position()),Integer.toString(limit+1)),limit+1);
        var entries=rows.stream().limit(limit).map(CreditDiagnostics::legacyEvent).toList();
        return new EventPage(entries,rows.size()>limit?cursor(entries.getLast()):null,frontier);
    }
    private static EventView legacyEvent(Map<String,String> row){
        int version=Integer.parseInt(row.get("record_version"));if(version!=1)throw new IllegalArgumentException("Unsupported credit event version");
        String body=row.get("payload");CreditFacilities.validateEventFact(body);var p=unpack(body,4);var details=new LinkedHashMap<String,String>();
        var values=p.get(3).split("\\|",-1);
        switch(p.get(0)){
            case "DAILY_INTEREST"->{if(values.length!=4)throw new IllegalArgumentException("Daily receipt fields");details.put("purchaseEarnedMajor",decimal(values[0]));details.put("advanceEarnedMajor",decimal(values[1]));details.put("purchasePostedMinor",numeric(values[2]));details.put("advancePostedMinor",numeric(values[3]));details.put("reproduction","LEGACY_INPUTS_UNAVAILABLE");}
            case "REPAYMENT"->{if(values.length!=4)throw new IllegalArgumentException("Repayment receipt fields");details.put("amountMinor",numeric(values[0]));details.put("principalMinor",numeric(values[1]));details.put("interestMinor",numeric(values[2]));details.put("waivedResidualMajor",decimal(values[3]));details.put("allocations","LEGACY_ALLOCATION_UNAVAILABLE");}
            case "DRAW_PURCHASE","DRAW_ADVANCE"->{if(values.length!=3)throw new IllegalArgumentException("Draw receipt fields");details.put("amountMinor",numeric(values[0]));}
            case "LIMIT_INCREASED"->{if(values.length!=2)throw new IllegalArgumentException("Increase receipt fields");details.put("oldLimitMinor",numeric(values[0]));details.put("newLimitMinor",numeric(values[1]));}
            case "FACILITY_CLOSED"->details.put("releasedReservationMinor",numeric(p.get(3)));
            case "APPLICATION_DENIED","INCREASE_DENIED"->{if(!p.get(3).matches("[A-Z0-9_]{1,96}"))throw new IllegalArgumentException("Credit reason");details.put("reason",p.get(3));}
            default->details.put("detail","UNAVAILABLE");
        }
        return new EventView(number(row.get("sequence")),Integer.parseInt(row.get("position")),version,p.get(0),number(p.get(2)),details);
    }
    private static EventCursor cursor(EventView event){return new EventCursor(event.sequence(),event.position());}
    private static List<String> unpack(String body,int count){var parts=body.split("\\|",-1);if(parts.length!=count)throw new IllegalArgumentException("Credit diagnostic fields");return Arrays.stream(parts).map(p->new String(Base64.getUrlDecoder().decode(p),StandardCharsets.UTF_8)).toList();}
    private static String numeric(String value){return Long.toString(number(value));}
    private static String decimal(String value){var n=new BigDecimal(value);if(n.precision()>64||Math.abs(n.scale())>36)throw new IllegalArgumentException("Credit diagnostic decimal");return n.toPlainString();}
    private static long number(String value){return Long.parseLong(value);}
    private static Long nullableLong(String value){return value==null?null:number(value);}
    private static PlayerId player(PartyRef owner){return ((PartyRef.Player)owner.owner()).id();}
    private static void requirePlayer(PartyRef owner){Objects.requireNonNull(owner);if(!(owner.owner() instanceof PartyRef.Player))throw new IllegalArgumentException("Credit owner");}
    private static void bounds(int limit){if(limit<1||limit>16)throw new IllegalArgumentException("Credit diagnostic page bounds");}
    private static void instant(long now){if(now<0)throw new IllegalArgumentException("Credit diagnostic instant");}
}
