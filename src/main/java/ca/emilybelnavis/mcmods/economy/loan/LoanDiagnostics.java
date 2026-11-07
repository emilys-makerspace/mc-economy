package ca.emilybelnavis.mcmods.economy.loan;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Worker-owned immutable read snapshots. Operator callers must obtain a separately audited grant. */
public final class LoanDiagnostics {
    private LoanDiagnostics() {}
    public record ContractView(Loans.Contract contract,BigDecimal exactDebtMajor,boolean current,String availability,
            long overdueMonths,long requiredMinor,long pastDueMinor,Long oldestDueAt) {}
    public record ContractPage(List<ContractView> entries,UUID nextCursor,long journalFrontier,long asOf) {
        public ContractPage {entries=List.copyOf(entries);}
    }
    public record SchedulePage(List<Loans.ScheduleRow> entries,Integer nextOrdinal,String scheduleHash,int version,EconomyCurrency currency) {
        public SchedulePage {entries=List.copyOf(entries);}
    }
    public record EventCursor(long sequence,int position) {
        public EventCursor {if(sequence<0||position< -1||position>63||sequence==0&&position!= -1)throw new IllegalArgumentException("Loan event cursor");}
        public static EventCursor start(){return new EventCursor(0,-1);}
    }
    public record PaymentView(long amountMinor,long principalMinor,long interestMinor,long recognizedInterestMinor,
            BigDecimal residualDispositionMajor,BigDecimal additionalInterestWaivedMajor,
            List<LoanServicing.Allocation> allocations,boolean journalMatches) {
        public PaymentView {allocations=List.copyOf(allocations);}
    }
    public record EventView(long sequence,int position,int version,String kind,long effectiveAt,
            Map<String,String> details,PaymentView payment) {
        public EventView {details=Map.copyOf(details);}
    }
    public record EventPage(List<EventView> entries,EventCursor nextCursor,long journalFrontier) {
        public EventPage {entries=List.copyOf(entries);}
    }
    public record FundingView(long sequence,int version,long amountMinor,long operatingFloorMinor,long effectiveAt,String conversion) {}
    public record Reconciliation(long ownerCurrencyDebtMinor,long borrowerLiabilityMinor,long issuerCurrencyDebtMinor,
            long issuerReceivableMinor,boolean ownerMatches,boolean issuerMatches,BigInteger ownerJournalMinor,
            BigInteger issuerJournalMinor,boolean ownerJournalComplete,boolean issuerJournalComplete,
            boolean ownerJournalMatches,boolean issuerJournalMatches,boolean ownerFacilitiesComplete,boolean issuerFacilitiesComplete) {}
    public record ProviderHead(String provider,String implementations,Long completedAt,Long applyingAt,String applyingCursor,long completedEffects) {}
    public record OperatorView(ContractView contract,long journalFrontier,long asOf,Reconciliation reconciliation,
            ProviderHead provider,Optional<Loans.ApplicationDecision> application,Optional<FundingView> funding,EventPage events,String currentPrimeStatus) {}

    public static ContractPage ownerPage(JournalEngine.ReadView reads,PartyRef owner,UUID after,int limit,long now){
        bounds(limit);instant(now);player(owner);
        var rows=Loans.history(reads,owner,after,limit);UUID next=null;
        if(rows.size()==limit){var last=rows.getLast().id();
            if(!reads.query("SELECT loan_id FROM loans WHERE world_id=? AND owner_id=? AND loan_id>? ORDER BY loan_id LIMIT 1",
                    List.of(owner.world().value().toString(),player(owner).value().toString(),last.toString()),1).isEmpty())next=last;
        }
        return new ContractPage(rows.stream().map(l->view(l,now)).toList(),next,frontier(reads),now);
    }
    public static Optional<ContractView> contract(JournalEngine.ReadView reads,PartyRef owner,UUID id,long now){
        instant(now);player(owner);return selected(reads,owner,id).map(l->view(l,now));
    }
    public static SchedulePage schedule(JournalEngine.ReadView reads,PartyRef owner,UUID id,int after,int limit){
        bounds(limit);player(owner);if(after<0||after>104)throw new IllegalArgumentException("Loan schedule cursor");
        var loan=selected(reads,owner,id).orElse(null);if(loan==null)throw new IllegalArgumentException("Loan selection unavailable");
        var rows=Loans.schedule(reads,owner,id,after,limit);
        var original=LoanServicing.schedule(loan.currency(),loan.originalPrincipalMinor(),loan.apr(),loan.years(),loan.product()==ca.emilybelnavis.mcmods.economy.credit.CreditUnderwriting.Product.SUBPRIME_LOAN);
        int expectedOrdinal=after+1;
        for(var row:rows){
            if(row.ordinal()!=expectedOrdinal++||row.ordinal()>original.installments().size())throw new IllegalArgumentException("Loan schedule identity");
            var expected=original.installments().get(row.ordinal()-1);
            if(row.dueAt()!=Math.addExact(loan.anchor(),Math.multiplyExact((long)row.ordinal(),Loans.MONTH))||row.paymentMinor()!=expected.paymentMinor()
                    ||row.principalMinor()!=expected.principalMinor()||row.interestMinor()!=expected.interestMinor()||row.remainingPrincipalMinor()!=expected.remainingPrincipalMinor())throw new IllegalArgumentException("Loan original schedule changed");
        }
        int expectedCount=Math.min(limit,Math.max(0,original.installments().size()-after));
        if(rows.size()!=expectedCount)throw new IllegalArgumentException("Loan original schedule incomplete");
        Integer next=after+rows.size()<original.installments().size()?rows.getLast().ordinal():null;
        return new SchedulePage(rows,next,loan.scheduleHash(),1,loan.currency());
    }
    public static Optional<EventPage> ownerPayments(JournalEngine.ReadView reads,PartyRef owner,UUID id,EventCursor after,int limit) {
        bounds(limit);player(owner);Objects.requireNonNull(after);
        var loan=selected(reads,owner,id).orElse(null);if(loan==null)return Optional.empty();
        long frontier=frontier(reads);var origin=originated(reads,loan,frontier);
        if(origin.isEmpty())return Optional.of(new EventPage(List.of(),null,frontier));
        var page=events(reads,loan,after,limit,frontier,origin.orElseThrow().sequence());
        return Optional.of(new EventPage(page.entries().stream().filter(e->e.payment()!=null).toList(),page.nextCursor(),frontier));
    }
    public static Optional<OperatorView> inspect(JournalEngine.ReadView reads,PartyRef owner,UUID id,long now,EventCursor after,int limit){
        bounds(limit);instant(now);player(owner);Objects.requireNonNull(after);
        var loan=selected(reads,owner,id).orElse(null);if(loan==null)return Optional.empty();long frontier=frontier(reads);
        var originated=originated(reads,loan,frontier);Optional<Loans.ApplicationDecision> application=Optional.empty();Optional<FundingView> funding=Optional.empty();
        if(originated.isPresent()){
            var event=originated.orElseThrow();var applicationId=UUID.fromString(event.details().get("application"));
            application=Loans.applicationDecision(reads,owner,applicationId);
            if(application.isPresent()&&(!application.orElseThrow().loan().equals(id)||!application.orElseThrow().approved()))throw new IllegalArgumentException("Loan application receipt binding");
            funding=funding(reads,loan,event.sequence());
        }
        return Optional.of(new OperatorView(view(loan,now),frontier,now,reconcile(reads,loan,frontier),provider(reads,id),application,funding,originated.isPresent()?events(reads,loan,after,limit,frontier,originated.orElseThrow().sequence()):new EventPage(List.of(),null,frontier),primeStatus(reads,loan,now)));
    }
    private static String primeStatus(JournalEngine.ReadView reads,Loans.Contract loan,long now){
        if(loan.product()==ca.emilybelnavis.mcmods.economy.credit.CreditUnderwriting.Product.SUBPRIME_LOAN)return "NOT_APPLICABLE_FIXED_SUBPRIME";
        try{var current=ca.emilybelnavis.mcmods.economy.pricing.PricingPolicy.current(reads,now);return "CURRENT_PRIME_VERSION_"+current.version()+"_EXISTING_FIXED_RATE_UNCHANGED";}
        catch(IllegalStateException unavailable){return "CURRENT_PRIME_UNAVAILABLE_EXISTING_FIXED_RATE_UNCHANGED";}
    }
    private static Optional<Loans.Contract> selected(JournalEngine.ReadView reads,PartyRef owner,UUID id){return Loans.find(reads,Objects.requireNonNull(id)).filter(l->l.party().equals(owner));}
    private static ContractView view(Loans.Contract loan,long now){
        var s=loan.servicing();long overdue=loan.status()==Loans.Status.ACTIVE&&now>=loan.nextMonth()?Math.addExact(Math.floorDiv(now-loan.nextMonth(),Loans.MONTH),1):0;
        String availability=overdue>0?"MONTHLY_CATCHUP_REQUIRED":now<s.lastAccrued()?"TIME_BEFORE_SNAPSHOT":"CURRENT";
        BigDecimal exact=null;
        if(availability.equals("CURRENT"))exact=(loan.status()==Loans.Status.ACTIVE?LoanServicing.accrue(s,now):s).exactDebtMajor();
        long past=0;Long due=null;
        for(var r:s.requirements())if(r.unmetMinor()>0){if(r.dueAt()<now)past=Math.addExact(past,r.unmetMinor());due=due==null?r.dueAt():Math.min(due,r.dueAt());}
        return new ContractView(loan,exact,availability.equals("CURRENT"),availability,overdue,s.requiredMinor(),past,due);
    }
    public static long frontier(JournalEngine.ReadView reads){return number(reads.query("SELECT COALESCE(MAX(sequence),0) AS frontier FROM journal_transactions",List.of(),1).getFirst().get("frontier"));}
    private static EventPage events(JournalEngine.ReadView reads,Loans.Contract loan,EventCursor after,int limit,long frontier,long originSequence){
        var rows=reads.query("SELECT sequence,position,record_version,payload FROM journal_records WHERE record_type IN('economy:a6/loan-event','economy:a6/loan') AND record_type='economy:a6/loan-event' AND substr(payload,instr(payload,'|')+1,48)=? AND sequence>=? AND sequence<=? AND (sequence>? OR (sequence=? AND position>?)) ORDER BY sequence,position LIMIT ?",
                List.of(key(loan.id().toString()),Long.toString(originSequence),Long.toString(frontier),Long.toString(after.sequence()),Long.toString(after.sequence()),Integer.toString(after.position()),Integer.toString(limit+1)),limit+1);
        var examined=rows.stream().limit(limit).toList();var entries=new ArrayList<EventView>();
        for(var row:examined){
            var fields=unpack(row.get("payload"),4);
            // Denied applicants may choose another person's existing UUID. Their owner-bound decision is a separate lookup.
            if(fields.get(0).equals("APPLICATION_DENIED"))continue;
            requireSnapshot(reads,loan,number(row.get("sequence")));
            entries.add(event(reads,loan,row));
        }
        var last=examined.isEmpty()?null:examined.getLast();
        return new EventPage(entries,rows.size()>limit?new EventCursor(number(last.get("sequence")),Integer.parseInt(last.get("position"))):null,frontier);
    }
    private static Optional<EventView> originated(JournalEngine.ReadView reads,Loans.Contract loan,long frontier){
        var facts=reads.query("SELECT sequence,record_version,payload FROM journal_records WHERE record_type IN('economy:a6/loan-event','economy:a6/loan') AND record_type='economy:a6/loan' AND substr(payload,instr(payload,'|')+1,48)=? AND sequence<=? ORDER BY sequence,position LIMIT 1",
                List.of(key(loan.id().toString()),Long.toString(frontier)),1);
        if(facts.isEmpty())return Optional.empty();var fact=facts.getFirst();requireSnapshot(loan,fact);
        var original=Loans.decode(fact.get("payload"));if(original.revision()!=1||original.anchor()!=loan.anchor())throw new IllegalArgumentException("Loan original snapshot");
        var rows=reads.query("SELECT sequence,position,record_version,payload FROM journal_records WHERE sequence=? AND record_type=? ORDER BY position LIMIT 1",List.of(fact.get("sequence"),Loans.EVENT_FACT),1);
        if(rows.isEmpty())return Optional.empty();var result=event(reads,loan,rows.getFirst());
        return result.kind().equals("ORIGINATED")?Optional.of(result):Optional.empty();
    }
    private static void requireSnapshot(JournalEngine.ReadView reads,Loans.Contract loan,long sequence){
        var rows=reads.query("SELECT record_version,payload FROM journal_records WHERE sequence=? AND record_type=? LIMIT 1",List.of(Long.toString(sequence),Loans.FACT),1);
        if(rows.isEmpty())throw new IllegalArgumentException("Loan event source unavailable");requireSnapshot(loan,rows.getFirst());
    }
    private static void requireSnapshot(Loans.Contract loan,Map<String,String> row){
        int version=Integer.parseInt(row.get("record_version"));if(version!=1&&version!=2)throw new IllegalArgumentException("Unsupported loan snapshot version");var source=Loans.decode(row.get("payload"),version);
        if(!source.id().equals(loan.id())||!source.party().equals(loan.party())||!source.lender().equals(loan.lender())||source.currency()!=loan.currency()||!source.scheduleHash().equals(loan.scheduleHash())||!Objects.equals(source.primePolicyId(),loan.primePolicyId())||source.primePolicyRevision()!=loan.primePolicyRevision())throw new IllegalArgumentException("Loan event owner binding");
    }
    private static EventView event(JournalEngine.ReadView reads,Loans.Contract loan,Map<String,String> row){
        int version=Integer.parseInt(row.get("record_version"));if(version!=1)throw new IllegalArgumentException("Unsupported loan event version");
        var p=unpack(row.get("payload"),4);if(!p.get(1).equals(loan.id().toString())||number(p.get(2))<loan.anchor())throw new IllegalArgumentException("Loan event binding");
        long sequence=number(row.get("sequence"));var values=p.get(3).split("\\|",-1);var details=new LinkedHashMap<String,String>();PaymentView payment=null;
        switch(p.get(0)){
            case "ORIGINATED"->{if(values.length!=3)throw new IllegalArgumentException("Loan origination fields");UUID.fromString(values[0]);if(number(values[1])!=loan.originalPrincipalMinor()||number(values[2])!=loan.servicing().installmentMinor())throw new IllegalArgumentException("Loan origination amounts");details.put("application",values[0]);details.put("principalMinor",values[1]);details.put("installmentMinor",values[2]);}
            case "MONTH_CLOSED"->{if(values.length!=2)throw new IllegalArgumentException("Loan month fields");details.put("recognizedInterestMinor",Long.toString(number(values[0])));details.put("earnedMajor",decimal(values[1]).toPlainString());}
            case "PAYMENT"->{
                if(values.length!=7)throw new IllegalArgumentException("Loan payment fields");long amount=number(values[0]),principal=number(values[1]),interest=number(values[2]),recognized=number(values[3]);
                if(amount<=0||principal<0||interest<0||Math.addExact(principal,interest)!=amount)throw new IllegalArgumentException("Loan payment totals");
                var residual=decimal(values[4]);var waived=decimal(values[5]);if(waived.signum()<0)throw new IllegalArgumentException("Loan waiver");var allocations=allocations(values[6]);long pTotal=0,iTotal=0;
                for(var a:allocations)if(a.interest())iTotal=Math.addExact(iTotal,a.minor());else pTotal=Math.addExact(pTotal,a.minor());
                if(pTotal!=principal||iTotal!=interest)throw new IllegalArgumentException("Loan payment allocation totals");
                payment=new PaymentView(amount,principal,interest,recognized,residual,waived,allocations,paymentJournalMatches(reads,loan,sequence,amount,recognized));
            }
            default->throw new IllegalArgumentException("Unsupported loan event kind");
        }
        return new EventView(sequence,Integer.parseInt(row.get("position")),version,p.get(0),number(p.get(2)),details,payment);
    }
    private static List<LoanServicing.Allocation> allocations(String text){
        if(text.isEmpty())throw new IllegalArgumentException("Missing loan allocations");var rows=text.split("\n",-1);if(rows.length>34)throw new IllegalArgumentException("Loan allocation bound");var result=new ArrayList<LoanServicing.Allocation>();
        for(String row:rows){var p=row.split(",",-1);if(p.length!=3||!Set.of("true","false").contains(p[1])||number(p[2])<=0)throw new IllegalArgumentException("Loan allocation");if(!p[0].isEmpty())UUID.fromString(p[0]);result.add(new LoanServicing.Allocation(p[0],Boolean.parseBoolean(p[1]),number(p[2])));}
        return List.copyOf(result);
    }
    private static boolean paymentJournalMatches(JournalEngine.ReadView reads,Loans.Contract loan,long sequence,long amount,long recognized){
        var rows=reads.query("SELECT payload FROM journal_records WHERE sequence=? AND record_type='economy:posting' AND record_version IN(1,2,3) ORDER BY position LIMIT 65",List.of(Long.toString(sequence)),65);
        if(rows.size()>64)return false;BigInteger receivable=BigInteger.ZERO,liability=BigInteger.ZERO;
        var receive=FinancialAccounts.id(loan.world(),loan.lender(),FinancialAccounts.Role.RECEIVABLE,loan.currency()).toString();
        var owe=FinancialAccounts.id(loan.world(),Loans.borrower(loan.owner()),FinancialAccounts.Role.LIABILITY,loan.currency()).toString();
        for(var row:rows){var p=row.get("payload").split("\\|",-1);if(p.length!=5)throw new IllegalArgumentException("Loan posting fields");
            if(p[0].equals(receive)||p[0].equals(owe)){if(!p[2].equals(loan.currency().name()))throw new IllegalArgumentException("Loan posting currency");if(p[0].equals(receive))receivable=receivable.add(BigInteger.valueOf(number(p[3])));else liability=liability.add(BigInteger.valueOf(number(p[3])));}}
        var expected=BigInteger.valueOf(recognized).subtract(BigInteger.valueOf(amount));return receivable.equals(expected)&&liability.equals(expected.negate());
    }
    private static Optional<FundingView> funding(JournalEngine.ReadView reads,Loans.Contract loan,long sequence){
        var rows=reads.query("SELECT record_version,payload FROM journal_records WHERE sequence=? AND record_type=? LIMIT 1",List.of(Long.toString(sequence),LoanTreasury.FACT),1);
        if(rows.isEmpty())return Optional.empty();var row=rows.getFirst();if(!row.get("record_version").equals("1"))throw new IllegalArgumentException("Unsupported loan funding version");
        ca.emilybelnavis.mcmods.economy.credit.CreditTreasury.validateLoanFunding(row.get("payload"));var p=row.get("payload").split("\\|",-1);
        if(!p[0].equals(loan.world().value().toString())||!p[1].equals(loan.lender().key())||!p[2].equals(loan.currency().name())||number(p[3])!=loan.originalPrincipalMinor())throw new IllegalArgumentException("Loan funding binding");
        return Optional.of(new FundingView(sequence,1,number(p[3]),number(p[4]),number(p[5]),p[6]));
    }
    private record Total(long amount,boolean complete,int count) {}
    private static Total total(JournalEngine.ReadView reads,String sql,List<String> args,int budget){
        var rows=reads.query(sql,args,129);long n=0;for(var row:rows.stream().limit(budget).toList())n=Math.addExact(n,number(row.get("debt_minor")));
        return new Total(n,rows.size()<=budget,Math.min(budget,rows.size()));
    }
    private record JournalTotal(BigInteger amount,boolean complete) {}
    private static JournalTotal journalTotal(JournalEngine.ReadView reads,UUID account,EconomyCurrency currency,long frontier){
        var rows=reads.query("SELECT payload FROM journal_records WHERE record_type='economy:posting' AND record_version IN (1,2,3,4) AND substr(payload,1,36)=? AND sequence<=? ORDER BY sequence,position LIMIT 129",List.of(account.toString(),Long.toString(frontier)),129);
        BigInteger amount=BigInteger.ZERO;for(var row:rows.stream().limit(128).toList()){var p=row.get("payload").split("\\|",-1);if(p.length!=5||!p[0].equals(account.toString())||!p[2].equals(currency.name()))throw new IllegalArgumentException("Loan journal binding");amount=amount.add(BigInteger.valueOf(number(p[3])));}
        return new JournalTotal(amount,rows.size()<=128);
    }
    private static Reconciliation reconcile(JournalEngine.ReadView reads,Loans.Contract loan,long frontier){
        var args=List.of(loan.world().value().toString(),loan.owner().value().toString(),loan.currency().name());
        var owner=total(reads,"SELECT debt_minor FROM loans WHERE world_id=? AND owner_id=? AND currency=? AND status<>'SETTLED' ORDER BY loan_id LIMIT 129",args,128);
        args=List.of(loan.world().value().toString(),loan.lender().key(),loan.currency().name());
        var issuerLoans=total(reads,"SELECT debt_minor FROM loans WHERE world_id=? AND lender=? AND currency=? AND debt_minor>0 AND NOT EXISTS(SELECT 1 FROM debt_cases c WHERE c.world_id=loans.world_id AND c.kind='LOAN' AND c.facility_id=loans.loan_id AND c.sale='SOLD') ORDER BY loan_id LIMIT 129",args,128);
        var issuerCards=total(reads,"SELECT debt_minor FROM credit_facilities WHERE world_id=? AND issuer=? AND currency=? AND debt_minor>0 AND NOT EXISTS(SELECT 1 FROM debt_cases c WHERE c.world_id=credit_facilities.world_id AND c.kind='CARD' AND c.facility_id=credit_facilities.facility_id AND c.sale='SOLD') ORDER BY facility_id LIMIT 129",args,128-issuerLoans.count());
        long issuerDebt=Math.addExact(issuerLoans.amount(),issuerCards.amount());boolean issuerComplete=issuerLoans.complete()&&issuerCards.complete();
        var liabilityId=FinancialAccounts.id(loan.world(),Loans.borrower(loan.owner()),FinancialAccounts.Role.LIABILITY,loan.currency());
        var receivableId=FinancialAccounts.id(loan.world(),loan.lender(),FinancialAccounts.Role.RECEIVABLE,loan.currency());
        long liability=Math.negateExact(Settlement.posted(reads,liabilityId,loan.currency())),receivable=Settlement.posted(reads,receivableId,loan.currency());
        var ownerJournal=journalTotal(reads,liabilityId,loan.currency(),frontier);var issuerJournal=journalTotal(reads,receivableId,loan.currency(),frontier);var o=ownerJournal.amount().negate();var i=issuerJournal.amount();
        return new Reconciliation(owner.amount(),liability,issuerDebt,receivable,owner.complete()&&owner.amount()==liability,issuerComplete&&issuerDebt==receivable,o,i,ownerJournal.complete(),issuerJournal.complete(),
                owner.complete()&&ownerJournal.complete()&&o.equals(BigInteger.valueOf(owner.amount()))&&o.equals(BigInteger.valueOf(liability)),issuerComplete&&issuerJournal.complete()&&i.equals(BigInteger.valueOf(issuerDebt))&&i.equals(BigInteger.valueOf(receivable)),owner.complete(),issuerComplete);
    }
    private static ProviderHead provider(JournalEngine.ReadView reads,UUID loan){
        String id="economy:a6/loan-monthly";var rows=reads.query("SELECT implementations,completed_ms FROM financial_deadline_providers WHERE provider=?",List.of(id),1);
        if(rows.isEmpty())return new ProviderHead(id,"NOT_INSTALLED",null,null,"",0);
        var row=rows.getFirst();var applying=reads.query("SELECT effective_ms,cursor,completed_effects FROM financial_deadline_events WHERE provider=? AND state='APPLYING' LIMIT 1",List.of(id),1);var a=applying.isEmpty()?null:applying.getFirst();
        return new ProviderHead(id,row.get("implementations"),nullable(row.get("completed_ms")),a==null?null:number(a.get("effective_ms")),a==null||a.get("cursor").isEmpty()?"":a.get("cursor").equals(loan.toString())?"THIS_LOAN":"OTHER_TARGET",a==null?0:number(a.get("completed_effects")));
    }
    private static PlayerId player(PartyRef owner){if(!(owner.owner() instanceof PartyRef.Player p))throw new IllegalArgumentException("Loan player owner required");return p.id();}
    private static void bounds(int limit){if(limit<1||limit>16)throw new IllegalArgumentException("Loan diagnostic page bounds");}
    private static void instant(long now){if(now<0)throw new IllegalArgumentException("Loan diagnostic time");}
    private static Long nullable(String value){return value==null?null:number(value);}
    private static long number(String value){long n=Long.parseLong(value);if(!Long.toString(n).equals(value))throw new IllegalArgumentException("Loan diagnostic number");return n;}
    private static BigDecimal decimal(String value){var n=new BigDecimal(value);if(n.precision()>50||Math.abs(n.scale())>18||!n.toPlainString().equals(value))throw new IllegalArgumentException("Loan diagnostic decimal");return n;}
    private static String key(String value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
    private static List<String> unpack(String body,int count){if(body.length()>16384)throw new IllegalArgumentException("Loan event size");var p=body.split("\\|",-1);if(p.length!=count)throw new IllegalArgumentException("Loan event fields");var result=Arrays.stream(p).map(v->new String(Base64.getUrlDecoder().decode(v),StandardCharsets.UTF_8)).toList();for(int n=0;n<count;n++)if(!key(result.get(n)).equals(p[n]))throw new IllegalArgumentException("Loan event encoding");return result;}
}
