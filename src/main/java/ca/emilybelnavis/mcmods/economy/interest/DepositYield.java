package ca.emilybelnavis.mcmods.economy.interest;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Time-weighted contractual yield. A claim is not cash until its bank funds an actual payout. */
public final class DepositYield {
    public static final long DAY=1_200_000L, MONTH=28*DAY;
    public static final MathContext MC=new MathContext(34,RoundingMode.HALF_EVEN);
    public static final String STATE_BATCH="economy:a6/yield-state-batch", ROUNDING_BATCH="economy:a6/yield-rounding-batch";
    public static final String DUE="economy:a6/yield-due";
    public static final String DEPOSITOR="economy:a6/yield-depositor";
    public static final String ACTIVATION="economy:a6/yield-activation", FACT="economy:a6/deposit-yield", PAYMENT="economy:a6/yield-payment", ROUNDING="economy:a6/yield-rounding";
    public static final Journal.Kind ACTIVATE=new Journal.Kind("economy:a6","economy:a6/yield/activate"), SETTLE=new Journal.Kind("economy:a6","economy:a6/yield/settle");
    @FunctionalInterface public interface Rates { BigDecimal apr(JournalEngine.ReadView reads,PlayerId owner,InstitutionCatalog.InstitutionId bank,long dayStart); }
    public record Claim(AccountId account,PlayerId owner,InstitutionCatalog.InstitutionId bank,EconomyCurrency currency,
            boolean active,long lastMillis,long dayStart,long balanceMinor,BigDecimal weightedMajorMillis,
            BigDecimal accruedMajor,BigDecimal priorCloseMajor,long recognizedMinor,long dueMillis,
            boolean distressed,long revision,BigDecimal paidMajor,BigDecimal apr,long aprStartMillis,long dueMinor,long createdMillis) {}
    private final WorldId world;
    private final Rates rates;
    public DepositYield(WorldId world,Rates rates) {this.world=Objects.requireNonNull(world);this.rates=Objects.requireNonNull(rates);}
    public static RecordCatalog register(RecordCatalog catalog) {
        return catalog.with(new RecordCatalog.Key(ACTIVATION,1),p->{long v=Long.parseLong(p);if(v<0 || !Long.toString(v).equals(p))throw new IllegalArgumentException("Yield activation");})
            .with(new RecordCatalog.Key(STATE_BATCH,1),DepositYield::validateStateBatch)
            .with(new RecordCatalog.Key(ROUNDING_BATCH,1),DepositYield::validateRoundingBatch)
            .with(new RecordCatalog.Key(DUE,1),DepositYield::validateDue)
            .with(new RecordCatalog.Key(DEPOSITOR,1),p->{var v=p.split("\\|",-1);if(v.length!=4)throw new IllegalArgumentException("Yield depositor");var w=new WorldId(UUID.fromString(v[0]));var o=new PlayerId(UUID.fromString(v[1]));if(!new InstitutionCatalog.SystemActor(w,YieldPosting.depositor(o)).actorId().value().toString().equals(v[2]))throw new IllegalArgumentException("Yield depositor actor");EconomyCurrency.valueOf(v[3]);})
            .with(new RecordCatalog.Key(FACT,1),DepositYield::decode)
            .with(new RecordCatalog.Key(PAYMENT,1),DepositYield::validatePayment)
            .with(new RecordCatalog.Key(ROUNDING,1),DepositYield::validateRounding);
    }
    public JournalEngine.Registration activation(Instant effective) {
        long time=effective.toEpochMilli();if(time<0)throw new IllegalArgumentException("Yield activation instant");
        return new JournalEngine.Registration(1,(intent,reads)->{
            if(!intent.request().equals(new Journal.CanonicalRequest(1,Map.of("effective",Long.toString(time)))))throw new IllegalArgumentException("Yield activation request");
            if(activation(reads)!=null)return new JournalEngine.Reject(new Journal.Result(1,"YIELD_ALREADY_ACTIVE",""));
            return new JournalEngine.Commit(List.of(new Journal.Record(ACTIVATION,1,Long.toString(time))),new Journal.Result(1,"YIELD_ACTIVE",""),tx->
                tx.execute("INSERT INTO deposit_yield_activation VALUES(1,?,1)",List.of(Long.toString(time))));
        });
    }
    private static Long activation(JournalEngine.ReadView reads) {
        var rows=reads.query("SELECT effective_ms FROM deposit_yield_activation WHERE singleton=1",List.of(),1);
        return rows.isEmpty()?null:Long.parseLong(rows.getFirst().get("effective_ms"));
    }
    public static List<Money> receivables(JournalEngine.ReadView reads,WorldId world,PlayerId owner) {
        var result=new ArrayList<Money>();
        for(var c:EconomyCurrency.all()) {
            var id=FinancialAccounts.id(world,YieldPosting.depositor(owner),FinancialAccounts.Role.RECEIVABLE,c);
            long amount=Settlement.posted(reads,id,c);
            if(amount<0)throw new IllegalStateException("Negative yield receivable");
            if(amount>0)result.add(new Money(c,amount));
        }
        return List.copyOf(result);
    }
    public static boolean distressed(JournalEngine.ReadView reads,InstitutionCatalog.InstitutionId bank) {
        return !reads.query("SELECT account_id FROM deposit_yield_claims WHERE bank_key=? AND due_ms>0 AND recognized_minor>0 AND due_minor>0 LIMIT 1",List.of(bank.key()),1).isEmpty();
    }
    public static Optional<Claim> find(JournalEngine.ReadView reads,AccountId account,InstitutionCatalog.InstitutionId bank,EconomyCurrency currency) {
        return reads.query("SELECT * FROM deposit_yield_claims WHERE account_id=? AND bank_key=? AND currency=?",List.of(account.value().toString(),bank.key(),currency.name()),1).stream().findFirst().map(DepositYield::row);
    }
    public DeadlineDispatcher.Provider provider() {
        return new DeadlineDispatcher.Provider() {
            public String id(){return "economy:deposit-yield";}
            public Map<Integer,String> versions(){return Map.of(1,"deposit-yield-v1-time-weighted-funded");}
            public DeadlineDispatcher.Event next(EconomicClock.Queries q,long origin,DeadlineDispatcher.Event completed) {
                var applying=applying(q::query,id());if(applying!=null)return applying;
                Long due=nextDue(q::query,origin,completed);if(due==null)return null;
                return new DeadlineDispatcher.Event(stable("day|"+due),id(),due,1,1);
            }
            public List<String> targets(DeadlineDispatcher.Event event,String cursor,int limit,EconomicClock.Queries q) {
                return q.query("""
                    SELECT target FROM (
                      SELECT account_id||'/'||bank_key||'/'||currency AS target FROM deposit_yield_claims
                        WHERE active=1 OR recognized_minor>0 OR accrued_major NOT IN ('0.000000000000000000','0')
                      UNION
                      SELECT a.account_id||'/'||p.bank_key||'/'||a.currency AS target
                        FROM account_catalog a JOIN bank_account_providers p ON p.account_id=a.account_id
                        WHERE a.purpose='BANK' AND a.status='OPEN' AND p.bank_key<>'economy:unassigned-provider'
                    ) WHERE target>? ORDER BY target LIMIT ?
                    """,List.of(cursor,Integer.toString(limit)),limit).stream().map(r->r.get("target")).toList();
            }
            public EconomicClock.Output prepare(DeadlineDispatcher.Event event,String target,EconomicClock.Queries queries) {
                var parsed=parseTarget(target);
                var reads=(JournalEngine.ReadView)queries::query;
                var claim=load(reads,parsed.account(),parsed.bank(),parsed.currency(),event.effectiveMillis());
                var plan=new Plan(reads);
                var next=finishDay(claim,event.effectiveMillis(),reads);
                long recognition=Math.subtractExact(next.recognizedMinor(),claim.recognizedMinor());
                next=plan.recognize(next,recognition);
                long origin=origin(reads);
                boolean month=Math.floorMod(Math.subtractExact(event.effectiveMillis(),origin),MONTH)==0;
                if(month && next.recognizedMinor()>next.dueMinor())next=plan.enqueue(next,event.effectiveMillis());
                plan.save(next);
                var commit=plan.commit(new Journal.Result(1,"YIELD_PROCESSED",next.account().value().toString()));
                return new EconomicClock.Output(next.account().value().toString(),commit.projections(),commit.records());
            }
        };
    }
    /** Runs after accrual at the same stage/instant, paying oldest promises before newer ones. */
    public DeadlineDispatcher.Provider payoutProvider() {
        return new DeadlineDispatcher.Provider() {
            public String id(){return "economy:deposit-yield-payout";}
            public Map<Integer,String> versions(){return Map.of(1,"deposit-yield-payout-v1-oldest-first");}
            public DeadlineDispatcher.Event next(EconomicClock.Queries q,long origin,DeadlineDispatcher.Event completed) {
                var applying=applying(q::query,id());if(applying!=null)return applying;
                Long due=nextDue(q::query,origin,completed);if(due==null)return null;
                return new DeadlineDispatcher.Event(stable("payout-day|"+due),id(),due,1,1);
            }
            public List<String> targets(DeadlineDispatcher.Event event,String cursor,int limit,EconomicClock.Queries reads) {
                return reads.query("SELECT printf('%020d',due_ms)||'/'||account_id||'/'||bank_key||'/'||currency AS target FROM deposit_yield_due WHERE due_ms>0 AND due_ms<=? AND amount_minor>0 AND printf('%020d',due_ms)||'/'||account_id||'/'||bank_key||'/'||currency>? ORDER BY due_ms,account_id,bank_key,currency LIMIT ?",
                    List.of(Long.toString(event.effectiveMillis()),cursor,Integer.toString(limit)),limit).stream().map(r->r.get("target")).toList();
            }
            public EconomicClock.Output prepare(DeadlineDispatcher.Event event,String target,EconomicClock.Queries queries) {
                if(target.length()<22 || target.charAt(20)!='/')throw new IllegalArgumentException("Yield payout target");
                long due=Long.parseLong(target.substring(0,20));
                var parsed=parseTarget(target.substring(21));
                var reads=(JournalEngine.ReadView)queries::query;
                var claim=find(reads,parsed.account(),parsed.bank(),parsed.currency()).orElseThrow();
                var plan=new Plan(reads);
                long amount=Long.parseLong(reads.query("SELECT amount_minor FROM deposit_yield_due WHERE account_id=? AND bank_key=? AND currency=? AND due_ms=?",List.of(parsed.account().value().toString(),parsed.bank().key(),parsed.currency().name(),Long.toString(due)),1).getFirst().get("amount_minor"));
                var next=plan.pay(claim,claim.account(),false,event.effectiveMillis(),amount,due);
                plan.save(next);
                var commit=plan.commit(new Journal.Result(1,next.distressed()?"YIELD_DEFERRED":"YIELD_PAID",next.account().value().toString()));
                return new EconomicClock.Output((next.distressed()?"YIELD_DEFERRED|":"YIELD_PAID|")+next.account().value(),commit.projections(),commit.records());
            }
        };
    }
    private static DeadlineDispatcher.Event applying(JournalEngine.ReadView reads,String provider) {
        var rows=reads.query("SELECT event_id,effective_ms,stage,version FROM financial_deadline_events WHERE provider=? AND state='APPLYING' ORDER BY effective_ms LIMIT 1",List.of(provider),1);
        if(rows.isEmpty())return null;
        var row=rows.getFirst();
        return new DeadlineDispatcher.Event(UUID.fromString(row.get("event_id")),provider,Long.parseLong(row.get("effective_ms")),Integer.parseInt(row.get("stage")),Integer.parseInt(row.get("version")));
    }
    /** Empty product domains do not manufacture daily catch-up work. */
    private Long nextDue(JournalEngine.ReadView reads,long origin,DeadlineDispatcher.Event completed) {
        Long activated=activation(reads);if(activated==null)return null;
        Long earliest=null;
        var active=reads.query("SELECT created_ms FROM deposit_yield_claims WHERE active=1 ORDER BY created_ms LIMIT 1",List.of(),1);
        if(!active.isEmpty())earliest=Long.parseLong(active.getFirst().get("created_ms"));
        var owed=reads.query("SELECT created_ms FROM deposit_yield_claims WHERE recognized_minor>0 OR accrued_major NOT IN ('0.000000000000000000','0') ORDER BY created_ms LIMIT 1",List.of(),1);
        if(!owed.isEmpty()) {long time=Long.parseLong(owed.getFirst().get("created_ms"));earliest=earliest==null?time:Math.min(earliest,time);}
        var untracked=reads.query("SELECT a.account_id FROM bank_account_providers p JOIN account_catalog a ON a.account_id=p.account_id WHERE p.bank_key<>'economy:unassigned-provider' AND a.purpose='BANK' AND a.status='OPEN' AND NOT EXISTS(SELECT 1 FROM deposit_yield_claims y WHERE y.account_id=a.account_id AND y.bank_key=p.bank_key AND y.currency=a.currency AND y.active=1) LIMIT 1",List.of(),1);
        if(!untracked.isEmpty())earliest=earliest==null?activated:Math.min(earliest,activated);
        if(earliest==null)return null;
        long baseline=Math.max(activated,earliest);
        long first=Math.addExact(origin,Math.multiplyExact(Math.addExact(Math.floorDiv(Math.subtractExact(baseline,origin),DAY),1),DAY));
        return completed==null?first:Math.max(first,Math.addExact(completed.effectiveMillis(),DAY));
    }
    /** Call only after A1 replay lookup and before journal persistence. No clock is read from client data. */
    public JournalEngine.Decision observe(Journal.Intent intent,JournalEngine.ReadView reads,JournalEngine.Commit original,long effective) {
        Long start=activation(reads);if(start==null)return original;
        if(effective<start)return original;
        var plan=new Plan(reads);
        plan.base(original.records());
        var saved=new HashSet<StateKey>();
        original.records().stream().filter(r->r.type().equals(FACT)).map(r->decode(r.payload())).forEach(c->saved.add(new StateKey(c.account().value(),c.bank().key(),c.currency())));
        var deltas=postingDeltas(original.records());
        var affected=new TreeSet<UUID>();deltas.keySet().forEach(k->affected.add(k.id));
        var newBanks=new HashMap<UUID,String>();
        var newOwners=new HashMap<UUID,PlayerId>();
        var newCurrencies=new HashMap<UUID,EconomyCurrency>();
        var closes=new HashSet<UUID>();
        for(var record:original.records()) {
            var p=record.payload().split("\\|",-1);
            if(record.type().equals("economy:bank-provider") && Set.of("opened","assigned").contains(p[0])) {var id=UUID.fromString(p[1]);affected.add(id);newBanks.put(id,p[2]);}
            if(record.type().equals("economy:provider-account")) {var id=UUID.fromString(p[1]);affected.add(id);newOwners.put(id,new PlayerId(UUID.fromString(p[2])));newCurrencies.put(id,EconomyCurrency.valueOf(p[3]));}
            if(record.type().equals("economy:bank-lifecycle") && p[0].equals("closed")) {var id=UUID.fromString(p[1]);affected.add(id);closes.add(id);}
            if(record.type().equals(RebaseSettlement.MAPPING)) {
                var id=UUID.fromString(p[1]);affected.add(id);
                var header=original.records().stream().filter(r->r.type().equals(RebaseSettlement.FACT)).findFirst().orElseThrow().payload().split("\\|",-1);
                newCurrencies.put(id,EconomyCurrency.valueOf(header[0]));
                if(!p[3].isEmpty()){var collision=UUID.fromString(p[3]);affected.add(collision);newBanks.put(collision,null);}
            }
        }
        affected.removeIf(id->!newOwners.containsKey(id) && reads.query("SELECT account_id FROM account_catalog WHERE account_id=? AND purpose='BANK'",List.of(id.toString()),1).isEmpty());
        if(affected.size()>16)return new JournalEngine.Retry(new Journal.Result(1,"YIELD_WRITE_BOUND",""));
        for(var id:affected) {
            var accountRows=reads.query("SELECT * FROM account_catalog WHERE account_id=? AND purpose='BANK'",List.of(id.toString()),1);
            Map<String,String> account=accountRows.isEmpty()?null:accountRows.getFirst();
            PlayerId owner=account==null?newOwners.get(id):new PlayerId(UUID.fromString(account.get("owner_id")));
            if(owner==null)continue;
            EconomyCurrency oldCurrency=account==null?newCurrencies.get(id):EconomyCurrency.valueOf(account.get("currency"));
            var binding=reads.query("SELECT bank_key FROM bank_account_providers WHERE account_id=?",List.of(id.toString()),1);
            String oldBank=binding.isEmpty()?null:binding.getFirst().get("bank_key");
            String bank=newBanks.getOrDefault(id,oldBank);
            EconomyCurrency currency=newCurrencies.getOrDefault(id,oldCurrency);
            var key=new AccountId(id);
            if(oldBank!=null && (saved.contains(new StateKey(id,oldBank,oldCurrency)) || plan.current.containsKey(new StateKey(id,oldBank,oldCurrency))))continue;
            if(oldBank!=null && !oldBank.equals(BankProviders.UNASSIGNED_PROVIDER)) {
                var prior=load(reads,key,new InstitutionCatalog.InstitutionId(oldBank),oldCurrency,effective);
                boolean changed=!Objects.equals(oldBank,bank) || oldCurrency!=currency;
                if(closes.contains(id)) {
                    var others=reads.query("SELECT * FROM deposit_yield_claims WHERE account_id=? AND NOT(bank_key=? AND currency=?) AND (recognized_minor>0 OR accrued_major NOT IN ('0','0.000000000000000000')) ORDER BY bank_key,currency LIMIT 254",List.of(id.toString(),oldBank,oldCurrency.name()),254);
                    if(others.size()>252)throw new IllegalStateException("Yield claim catalog bound exceeded");
                    var historical=new ArrayList<Claim>();int whole=0;
                    for(var r:others) {var next=finishPartial(row(r),effective,reads);historical.add(next);if(next.recognizedMinor()>0)whole++;}
                    if(whole>16)return retry("YIELD_SETTLEMENT_REQUIRED");
                    String historicalDestination=intent.request().fields().get("destination");
                    for(int index=0;index<historical.size();index++) {
                        var priorHistorical=row(others.get(index));var nextHistorical=historical.get(index);
                        if(nextHistorical.recognizedMinor()>0) {
                            if(historicalDestination==null || historicalDestination.isEmpty())return retry("YIELD_DESTINATION_REQUIRED");
                            if(!boundedDues(reads,nextHistorical))return retry("YIELD_SETTLEMENT_REQUIRED");
                            nextHistorical=plan.recognize(nextHistorical,Math.subtractExact(nextHistorical.recognizedMinor(),priorHistorical.recognizedMinor()));
                            nextHistorical=plan.pay(nextHistorical,new AccountId(UUID.fromString(historicalDestination)),true,effective);
                            if(nextHistorical.distressed())return retry("YIELD_FUNDING_UNAVAILABLE");
                            nextHistorical=plan.dispose(nextHistorical,effective);
                            plan.save(change(nextHistorical,false,effective,nextHistorical.dayStart(),0,zero(),zero(),zero(),0,0,false,nextHistorical.paidMajor(),nextHistorical.apr()));
                        } else plan.residualClose(priorHistorical,nextHistorical,effective);
                    }
                    var next=finishPartial(prior,effective,reads);
                    if(!boundedDues(reads,next))return retry("YIELD_SETTLEMENT_REQUIRED");
                    next=plan.recognize(next,Math.subtractExact(next.recognizedMinor(),prior.recognizedMinor()));
                    String destination=intent.request().fields().get("destination");
                    if(next.recognizedMinor()>0 && (destination==null || destination.isEmpty()))return retry("YIELD_DESTINATION_REQUIRED");
                    AccountId target=destination==null || destination.isEmpty()?key:new AccountId(UUID.fromString(destination));
                    next=plan.pay(next,target,true,effective);
                    if(next.distressed())return retry("YIELD_FUNDING_UNAVAILABLE");
                    next=plan.dispose(next,effective);
                    plan.save(change(next,false,effective,next.dayStart(),0,zero(),zero(),zero(),0,0,false,next.paidMajor(),next.apr()));
                } else if(changed) {
                    var next=finishPartial(prior,effective,reads);
                    if(!boundedDues(reads,next))return retry("YIELD_SETTLEMENT_REQUIRED");
                    next=plan.recognize(next,Math.subtractExact(next.recognizedMinor(),prior.recognizedMinor()));
                    plan.save(change(next,false,effective,next.dayStart(),0,zero(),next.accruedMajor(),next.priorCloseMajor(),next.recognizedMinor(),next.dueMillis(),next.distressed(),next.paidMajor(),next.apr()));
                } else {
                    var next=segment(prior,effective,reads);
                    long balance=Math.addExact(prior.balanceMinor(),deltas.getOrDefault(new Key(id,currency),0L));
                    if(balance<0)throw new IllegalStateException("Negative yield principal");
                    plan.save(change(next,true,effective,next.dayStart(),balance,next.weightedMajorMillis(),next.accruedMajor(),next.priorCloseMajor(),next.recognizedMinor(),next.dueMillis(),next.distressed(),next.paidMajor(),next.apr()));
                }
            }
            if(!closes.contains(id) && bank!=null && !bank.equals(BankProviders.UNASSIGNED_PROVIDER)
                    && (oldBank==null || !oldBank.equals(bank) || oldCurrency!=currency || oldBank.equals(BankProviders.UNASSIGNED_PROVIDER))) {
                long balance=Math.addExact(Settlement.posted(reads,id,currency),deltas.getOrDefault(new Key(id,currency),0L));
                var targetBank=new InstitutionCatalog.InstitutionId(bank);
                var retained=find(reads,key,targetBank,currency);
                Claim blank;
                if(retained.isEmpty())blank=initial(key,owner,targetBank,currency,effective,balance,reads);
                else {
                    var old=retained.orElseThrow();
                    if(old.lastMillis()<dayStart(reads,effective) && old.recognizedMinor()==0 && old.accruedMajor().signum()==0 && old.balanceMinor()==0 && old.weightedMajorMillis().signum()==0) {
                        var fresh=initial(key,owner,targetBank,currency,effective,balance,reads);
                        blank=new Claim(fresh.account(),fresh.owner(),fresh.bank(),fresh.currency(),true,fresh.lastMillis(),fresh.dayStart(),balance,zero(),zero(),zero(),0,0,false,Math.addExact(old.revision(),1),old.paidMajor(),fresh.apr(),effective,old.dueMinor(),old.createdMillis());
                    } else blank=segment(old,effective,reads);
                }
                plan.save(change(blank,true,effective,blank.dayStart(),balance,blank.weightedMajorMillis(),blank.accruedMajor(),blank.priorCloseMajor(),blank.recognizedMinor(),blank.dueMillis(),blank.distressed(),blank.paidMajor(),blank.apr()));
            }
        }
        if(plan.records.isEmpty())return original;
        plan.flushBatches();
        if(original.records().size()+plan.records.size()>64)return retry("YIELD_WRITE_BOUND");
        var extra=plan.commit(original.result());
        var records=new ArrayList<>(original.records());records.addAll(extra.records());
        return new JournalEngine.Commit(records,original.result(),tx->{original.projections().apply(tx);extra.projections().apply(tx);});
    }
    /** Explicit settlement also handles retained claims after provider/currency changes. */
    public JournalEngine.Registration settlement(PlayerId owner,AccountId account,InstitutionCatalog.InstitutionId bank,
            EconomyCurrency currency,AccountId destination,LongSupplier clock,boolean finalDisposition) {
        var request=new Journal.CanonicalRequest(1,Map.of("account",account.value().toString(),"bank",bank.key(),"currency",currency.name(),"destination",destination.value().toString(),"final",Boolean.toString(finalDisposition)));
        return new JournalEngine.Registration(1,(intent,reads)->{
            if(!owner.equals(intent.actor()) || !request.equals(intent.request()))return new JournalEngine.Reject(new Journal.Result(1,"YIELD_AUTHORITY",""));
            var claim=find(reads,account,bank,currency).orElse(null);
            if(claim==null || !claim.owner().equals(owner))return new JournalEngine.Reject(new Journal.Result(1,"YIELD_UNAVAILABLE",""));
            if(finalDisposition) {
                var source=reads.query("SELECT status FROM account_catalog WHERE account_id=?",List.of(account.value().toString()),1);
                if(source.isEmpty() || !source.getFirst().get("status").equals("CLOSED"))return retry("YIELD_CLOSURE_REQUIRED");
            }
            var sourceStatus=reads.query("SELECT status FROM account_catalog WHERE account_id=?",List.of(account.value().toString()),1);
            boolean closed=!sourceStatus.isEmpty() && sourceStatus.getFirst().get("status").equals("CLOSED");
            if(!closed && claim.dueMinor()==0)return retry("YIELD_NOT_DUE");
            if(closed && !boundedDues(reads,claim))return retry("YIELD_SETTLEMENT_REQUIRED");
            long now=clock.getAsLong();
            var next=finishPartial(claim,now,reads);
            var plan=new Plan(reads);
            next=plan.recognize(next,Math.subtractExact(next.recognizedMinor(),claim.recognizedMinor()));
            if(closed)next=plan.pay(next,destination,true,now);
            else {
                var dated=reads.query("SELECT due_ms,amount_minor FROM deposit_yield_due WHERE account_id=? AND bank_key=? AND currency=? AND amount_minor>0 ORDER BY due_ms LIMIT 1",List.of(account.value().toString(),bank.key(),currency.name()),1);
                if(dated.isEmpty())return retry("YIELD_NOT_DUE");
                next=plan.pay(next,destination,false,now,Long.parseLong(dated.getFirst().get("amount_minor")),Long.parseLong(dated.getFirst().get("due_ms")));
            }
            if(next.distressed())return retry("YIELD_FUNDING_UNAVAILABLE");
            if(finalDisposition)next=plan.dispose(next,now);
            plan.save(next);
            return plan.commit(new Journal.Result(1,"YIELD_SETTLED",account.value().toString()));
        });
    }
    public static Journal.CanonicalRequest settlementRequest(AccountId account,InstitutionCatalog.InstitutionId bank,
            EconomyCurrency currency,AccountId destination,boolean finalDisposition) {
        return new Journal.CanonicalRequest(1,Map.of("account",account.value().toString(),"bank",bank.key(),"currency",currency.name(),"destination",destination.value().toString(),"final",Boolean.toString(finalDisposition)));
    }
    private Claim load(JournalEngine.ReadView reads,AccountId account,InstitutionCatalog.InstitutionId bank,EconomyCurrency currency,long now) {
        var existing=find(reads,account,bank,currency);if(existing.isPresent())return existing.orElseThrow();
        var row=reads.query("SELECT owner_id,status,currency FROM account_catalog WHERE account_id=? AND purpose='BANK'",List.of(account.value().toString()),1);
        if(row.isEmpty())throw new IllegalArgumentException("Yield account missing");
        var a=row.getFirst();var owner=new PlayerId(UUID.fromString(a.get("owner_id")));
        var binding=reads.query("SELECT bank_key FROM bank_account_providers WHERE account_id=?",List.of(account.value().toString()),1);
        boolean active=a.get("status").equals("OPEN") && a.get("currency").equals(currency.name()) && !binding.isEmpty() && binding.getFirst().get("bank_key").equals(bank.key());
        long start=Objects.requireNonNull(activation(reads));
        long balance=active?Settlement.posted(reads,account.value(),currency):0;
        return initial(account,owner,bank,currency,Math.min(start,now),balance,reads);
    }
    private Claim initial(AccountId account,PlayerId owner,InstitutionCatalog.InstitutionId bank,EconomyCurrency currency,
            long time,long balance,JournalEngine.ReadView reads) {
        long day=dayStart(reads,time);var apr=rate(reads,owner,bank,time);
        return new Claim(account,owner,bank,currency,true,time,day,balance,zero(),zero(),zero(),0,0,false,1,zero(),apr,time,0,time);
    }
    private Claim segment(Claim s,long time,JournalEngine.ReadView reads) {
        if(time<s.lastMillis() || time>Math.addExact(s.dayStart(),DAY))throw new IllegalStateException("Yield time requires ordered daily catch-up");
        var principal=BigDecimal.valueOf(s.balanceMinor()).divide(BigDecimal.valueOf(s.currency().minorUnitsPerMajor()),MC);
        var weighted=s.weightedMajorMillis().add(principal.add(s.priorCloseMajor(),MC).max(BigDecimal.ZERO).multiply(BigDecimal.valueOf(time-s.lastMillis()),MC),MC);
        return change(s,s.active(),time,s.dayStart(),s.balanceMinor(),weighted,s.accruedMajor(),s.priorCloseMajor(),s.recognizedMinor(),s.dueMillis(),s.distressed(),s.paidMajor(),s.aprStartMillis()>s.dayStart()?s.apr():rate(reads,s.owner(),s.bank(),s.dayStart()));
    }
    private Claim finishDay(Claim original,long end,JournalEngine.ReadView reads) {
        if(end!=Math.addExact(original.dayStart(),DAY))throw new IllegalStateException("Yield day ordering");
        var s=segment(original,end,reads);var earned=earned(s,end);
        var accrued=scale(s.accruedMajor().add(earned,MC));long recognized=minor(accrued,s.currency());
        return change(s,s.active(),end,end,s.balanceMinor(),zero(),accrued,accrued,recognized,s.dueMillis(),s.distressed(),s.paidMajor(),rate(reads,s.owner(),s.bank(),end));
    }
    private Claim finishPartial(Claim original,long time,JournalEngine.ReadView reads) {
        var s=segment(original,time,reads);var accrued=scale(s.accruedMajor().add(earned(s,time),MC));
        // Restart the day segment after realization; already-realized current-day yield cannot compound today.
        return change(s,s.active(),time,s.dayStart(),s.balanceMinor(),zero(),accrued,s.priorCloseMajor(),minor(accrued,s.currency()),s.dueMillis(),s.distressed(),s.paidMajor(),s.apr());
    }
    private static BigDecimal earned(Claim s,long end) {
        var basis=s.weightedMajorMillis().divide(BigDecimal.valueOf(DAY),MC);
        return scale(basis.multiply(s.apr(),MC).divide(BigDecimal.valueOf(364),MC));
    }
    public static BigDecimal dailyInterest(BigDecimal weightedMajorMillis,BigDecimal priorCloseMajor,BigDecimal apr) {
        return scale(weightedMajorMillis.divide(BigDecimal.valueOf(DAY),MC).add(priorCloseMajor,MC).max(BigDecimal.ZERO).multiply(apr,MC).divide(BigDecimal.valueOf(364),MC));
    }
    private BigDecimal rate(JournalEngine.ReadView reads,PlayerId owner,InstitutionCatalog.InstitutionId bank,long start) {
        var value=Objects.requireNonNull(rates.apr(reads,owner,bank,start));
        if(value.signum()<0 || value.compareTo(BigDecimal.ONE)>0)throw new IllegalArgumentException("Deposit APR");
        return value;
    }
    private final class Plan {
        private final JournalEngine.ReadView reads;
        private final List<Journal.Record> records=new ArrayList<>();
        private final List<JournalEngine.Projection> projections=new ArrayList<>();
        private final Map<Key,Long> deltas=new HashMap<>();
        private final Map<StateKey,Claim> current=new HashMap<>();
        private final List<String> stateBatch=new ArrayList<>(),roundingBatch=new ArrayList<>();
        Plan(JournalEngine.ReadView reads){this.reads=reads;}
        void base(List<Journal.Record> base){postingDeltas(base).forEach((k,v)->deltas.merge(k,v,Math::addExact));}
        JournalEngine.ReadView after(List<Journal.Record> base){
            var all=new HashMap<>(deltas);postingDeltas(base).forEach((k,v)->all.merge(k,v,Math::addExact));return overlay(reads,all);
        }
        Claim recognize(Claim s,long increase) {
            if(increase<0)throw new IllegalStateException("Yield claim recognition decreased without payout");
            if(increase>0) {
                var payment=YieldPosting.prepare(overlay(reads,deltas),world,s.owner(),s.bank(),s.currency(),increase,0,null,null);
                if(payment==null)throw new IllegalStateException("Yield recognition unavailable");
                add(payment);
            }
            return s;
        }
        Claim pay(Claim s,AccountId destination,boolean forced,long now) {return pay(s,destination,forced,now,s.recognizedMinor(),0);}
        Claim pay(Claim s,AccountId destination,boolean forced,long now,long amount,long trancheDue) {
            if(amount==0)return s;
            if(amount<0 || amount>s.recognizedMinor())throw new IllegalStateException("Yield due amount exceeds claim");
            var dest=reads.query("SELECT owner_id,currency,status FROM account_catalog WHERE account_id=? AND purpose='BANK'",List.of(destination.value().toString()),1);
            if(dest.isEmpty() || !dest.getFirst().get("owner_id").equals(s.owner().value().toString()) || !dest.getFirst().get("status").equals("OPEN"))return distress(s,now);
            {
                long orderDue=s.dueMillis()==0?now:s.dueMillis();
                var earlier=reads.query("SELECT account_id FROM deposit_yield_claims WHERE bank_key=? AND due_ms>0 AND recognized_minor>0 AND (due_ms<? OR (due_ms=? AND account_id<?)) LIMIT 1",
                    List.of(s.bank().key(),Long.toString(orderDue),Long.toString(orderDue),s.account().value().toString()),1);
                if(!earlier.isEmpty())return distress(s,now);
            }
            var fx=FxService.latest(reads);
            if(fx!=null) {
                var instant=Instant.ofEpochMilli(FxService.effectiveMillis(reads,fx.updateId()));
                if(Instant.ofEpochMilli(now).isBefore(instant) || Instant.ofEpochMilli(now).isAfter(instant.plus(Duration.ofHours(48))))fx=null;
            }
            var payment=YieldPosting.prepare(overlay(reads,deltas),world,s.owner(),s.bank(),s.currency(),0,amount,destination,fx);
            if(payment==null)return distress(s,now);
            add(payment);
            long remainingDue=s.dueMinor();
            long earliestDue=s.dueMillis();
            if(trancheDue>0) {
                due(s,trancheDue,0);remainingDue=Math.subtractExact(remainingDue,amount);
                var nextDue=reads.query("SELECT due_ms FROM deposit_yield_due WHERE account_id=? AND bank_key=? AND currency=? AND amount_minor>0 AND due_ms<>? ORDER BY due_ms LIMIT 1",List.of(s.account().value().toString(),s.bank().key(),s.currency().name(),Long.toString(trancheDue)),1);
                earliestDue=nextDue.isEmpty()?0:Long.parseLong(nextDue.getFirst().get("due_ms"));
            } else if(remainingDue>0) {
                var dueRows=reads.query("SELECT due_ms FROM deposit_yield_due WHERE account_id=? AND bank_key=? AND currency=? AND amount_minor>0 ORDER BY due_ms LIMIT 17",List.of(s.account().value().toString(),s.bank().key(),s.currency().name()),17);
                if(dueRows.size()>16)throw new IllegalStateException("Settle dated yield tranches before closing");
                for(var r:dueRows)due(s,Long.parseLong(r.get("due_ms")),0);
                remainingDue=0;earliestDue=0;
            }
            var destinationCurrency=EconomyCurrency.valueOf(dest.getFirst().get("currency"));
            var binding=reads.query("SELECT bank_key FROM bank_account_providers WHERE account_id=?",List.of(destination.value().toString()),1);
            if(!binding.isEmpty() && !binding.getFirst().get("bank_key").equals(BankProviders.UNASSIGNED_PROVIDER)) {
                String destinationBank=binding.getFirst().get("bank_key");
                var destinationKey=new StateKey(destination.value(),destinationBank,destinationCurrency);
                var sourceKey=new StateKey(s.account().value(),s.bank().key(),s.currency());
                if(!destinationKey.equals(sourceKey)) {
                    var target=current.get(destinationKey);
                    if(target==null)target=load(reads,destination,new InstitutionCatalog.InstitutionId(destinationBank),destinationCurrency,now);
                    target=segment(target,now,reads);
                    long balance=Settlement.posted(overlay(reads,deltas),destination.value(),destinationCurrency);
                    save(change(target,true,now,target.dayStart(),balance,target.weightedMajorMillis(),target.accruedMajor(),target.priorCloseMajor(),target.recognizedMinor(),target.dueMillis(),target.distressed(),target.paidMajor(),target.apr()));
                }
            }
            var paid=BigDecimal.valueOf(amount).divide(BigDecimal.valueOf(s.currency().minorUnitsPerMajor()));
            var residual=scale(s.accruedMajor().subtract(paid,MC));
            records.add(new Journal.Record(PAYMENT,1,String.join("|",s.account().value().toString(),s.owner().value().toString(),s.bank().key(),s.currency().name(),Long.toString(amount),destination.value().toString(),Long.toString(now),payment.conversion())));
            var next=change(s,s.active(),s.lastMillis(),s.dayStart(),s.active() && destination.equals(s.account()) ? Math.addExact(s.balanceMinor(),amount) : s.balanceMinor(),s.weightedMajorMillis(),residual,s.priorCloseMajor().subtract(paid,MC),Math.subtractExact(s.recognizedMinor(),amount),earliestDue,false,scale(s.paidMajor().add(paid,MC)),s.apr());
            return dueSummary(next,remainingDue,earliestDue);
        }
        void residualClose(Claim before,Claim accrued,long effective) {
            if(accrued.recognizedMinor()!=0 || accrued.dueMinor()!=0)throw new IllegalArgumentException("Whole claim cannot use residual batch");
            var closed=change(accrued,false,effective,accrued.dayStart(),0,zero(),zero(),zero(),0,0,false,accrued.paidMajor(),accrued.apr());
            stateBatch.add(before.revision()+"\t"+encode(closed));
            var roundingAccount=FinancialAccounts.account(world,accrued.bank(),FinancialAccounts.Role.ROUNDING,accrued.currency());
            var rounding=roundingAccount.id();
            projections.add(tx->FinancialAccounts.register(tx,roundingAccount));
            roundingBatch.add(accrued.account().value()+"|"+accrued.bank().key()+"|"+accrued.currency()+"|"+accrued.accruedMajor().toPlainString()+"|"+rounding+"|"+effective+"|"+before.revision()+"|"+closed.revision());
            current.put(new StateKey(closed.account().value(),closed.bank().key(),closed.currency()),closed);
            projections.add(tx->write(tx,closed));
            if(stateBatch.size()==16)flushBatches();
        }
        void flushBatches() {
            if(!stateBatch.isEmpty()) {
                records.add(new Journal.Record(STATE_BATCH,1,String.join("\n",stateBatch)));
                records.add(new Journal.Record(ROUNDING_BATCH,1,String.join("\n",roundingBatch)));
                stateBatch.clear();roundingBatch.clear();
            }
        }
        Claim enqueue(Claim s,long effective) {
            long amount=Math.subtractExact(s.recognizedMinor(),s.dueMinor());
            if(amount<=0)return s;
            due(s,effective,amount);
            return dueSummary(s,Math.addExact(s.dueMinor(),amount),s.dueMillis()==0?effective:s.dueMillis());
        }
        void due(Claim s,long effective,long amount) {
            records.add(new Journal.Record(DUE,1,s.account().value()+"|"+s.bank().key()+"|"+s.currency()+"|"+effective+"|"+amount));
            projections.add(tx->tx.execute("INSERT INTO deposit_yield_due VALUES(?,?,?,?,?) ON CONFLICT(account_id,bank_key,currency,due_ms) DO UPDATE SET amount_minor=excluded.amount_minor",List.of(s.account().value().toString(),s.bank().key(),s.currency().name(),Long.toString(effective),Long.toString(amount))));
        }
        private Claim distress(Claim s,long now) {
            long due=s.dueMillis()==0?now:s.dueMillis();
            return change(s,s.active(),s.lastMillis(),s.dayStart(),s.balanceMinor(),s.weightedMajorMillis(),s.accruedMajor(),s.priorCloseMajor(),s.recognizedMinor(),due,true,s.paidMajor(),s.apr());
        }
        Claim dispose(Claim s,long now) {
            if(s.recognizedMinor()!=0)throw new IllegalStateException("Unfunded yield cannot be waived");
            if(s.accruedMajor().signum()!=0) {
                var rounding=FinancialAccounts.account(world,s.bank(),FinancialAccounts.Role.ROUNDING,s.currency());
                records.add(new Journal.Record(ROUNDING,1,s.account().value()+"|"+s.bank().key()+"|"+s.currency()+"|"+s.accruedMajor().toPlainString()+"|"+rounding.id()+"|"+now));
                if(FinancialAccounts.find(reads,rounding.id()).isEmpty()) {
                    records.add(new Journal.Record("economy:a6/institution-account",1,rounding.id()+"|"+world.value()+"|"+s.bank().key()+"|ROUNDING|"+s.currency()));
                    projections.add(tx->FinancialAccounts.register(tx,rounding));
                }
            }
            return change(s,s.active(),s.lastMillis(),s.dayStart(),s.balanceMinor(),s.weightedMajorMillis(),zero(),s.priorCloseMajor(),0,0,false,s.paidMajor(),s.apr());
        }
        void add(YieldPosting.Payment payment) {
            if(payment.commit()==null)return;
            records.addAll(payment.commit().records());projections.add(payment.commit().projections());
            for(var leg:payment.legs())deltas.merge(new Key(leg.account(),leg.accountCurrency()),leg.amount().minorUnits(),Math::addExact);
        }
        void save(Claim claim) {
            current.put(new StateKey(claim.account().value(),claim.bank().key(),claim.currency()),claim);
            records.add(new Journal.Record(FACT,1,encode(claim)));
            projections.add(tx->write(tx,claim));
        }
        JournalEngine.Commit commit(Journal.Result result) {
            flushBatches();
            return new JournalEngine.Commit(records,result,tx->{for(var projection:projections)projection.apply(tx);});
        }
    }
    private static JournalEngine.ReadView overlay(JournalEngine.ReadView reads,Map<Key,Long> deltas) {
        return (sql,parameters,max)->{
            if(sql.contains("SELECT minor_units FROM ledger_balances WHERE account_id=? AND currency=?") && parameters.size()==2) {
                var key=new Key(UUID.fromString(parameters.get(0)),EconomyCurrency.valueOf(parameters.get(1)));
                long amount=Math.addExact(Settlement.posted(reads,key.id,key.currency),deltas.getOrDefault(key,0L));
                return amount==0?List.of():List.of(Map.of("minor_units",Long.toString(amount)));
            }
            return reads.query(sql,parameters,max);
        };
    }
    private record Key(UUID id,EconomyCurrency currency) {}
    private record StateKey(UUID id,String bank,EconomyCurrency currency) {}
    private static Map<Key,Long> postingDeltas(List<Journal.Record> records) {
        var deltas=new HashMap<Key,Long>();
        for(var record:records)if(record.type().equals("economy:posting")) {
            var p=record.payload().split("\\|",-1);if(p.length!=5)throw new IllegalArgumentException("Yield posting fields");
            deltas.merge(new Key(UUID.fromString(p[0]),EconomyCurrency.valueOf(p[2])),Long.parseLong(p[3]),Math::addExact);
        }
        return deltas;
    }
    /** Dispatcher target alphabet excludes pipes; the UUID prefix and final currency delimit the bank key. */
    public static String target(AccountId account,InstitutionCatalog.InstitutionId bank,EconomyCurrency currency) {
        return account.value()+"/"+bank.key()+"/"+currency;
    }
    private record Target(AccountId account,InstitutionCatalog.InstitutionId bank,EconomyCurrency currency) {}
    private static Target parseTarget(String text) {
        int last=text.lastIndexOf('/');
        if(text.length()<42 || text.charAt(36)!='/' || last<=37)throw new IllegalArgumentException("Yield target");
        var parsed=new Target(new AccountId(UUID.fromString(text.substring(0,36))),new InstitutionCatalog.InstitutionId(text.substring(37,last)),EconomyCurrency.valueOf(text.substring(last+1)));
        if(!target(parsed.account(),parsed.bank(),parsed.currency()).equals(text))throw new IllegalArgumentException("Noncanonical yield target");
        return parsed;
    }
    private UUID stable(String key){return UUID.nameUUIDFromBytes(("economy:yield:v1|"+world.value()+"|"+key).getBytes(StandardCharsets.UTF_8));}
    private static long origin(JournalEngine.ReadView reads){return Long.parseLong(reads.query("SELECT origin_ms FROM financial_calendar WHERE singleton=1",List.of(),1).getFirst().get("origin_ms"));}
    private static long dayStart(JournalEngine.ReadView reads,long time){long origin=origin(reads);return Math.addExact(origin,Math.multiplyExact(Math.floorDiv(time-origin,DAY),DAY));}
    private static BigDecimal zero(){return BigDecimal.ZERO.setScale(18);}
    private static BigDecimal scale(BigDecimal value){return value.setScale(18,RoundingMode.HALF_EVEN);}
    private static long minor(BigDecimal value,EconomyCurrency currency){long rounded=value.multiply(BigDecimal.valueOf(currency.minorUnitsPerMajor()),MC).setScale(0,RoundingMode.HALF_EVEN).longValueExact();if(rounded<0)throw new IllegalStateException("Negative yield claim");return rounded;}
    private static JournalEngine.Retry retry(String code){return new JournalEngine.Retry(new Journal.Result(1,code,""));}
    private static Claim change(Claim s,boolean active,long last,long day,long balance,BigDecimal weighted,BigDecimal accrued,BigDecimal prior,long recognized,long due,boolean distress,BigDecimal paid,BigDecimal apr) {
        return new Claim(s.account(),s.owner(),s.bank(),s.currency(),active,last,day,balance,weighted,accrued,prior,recognized,due,distress,Math.addExact(s.revision(),1),paid,apr,day!=s.dayStart()?day:s.aprStartMillis(),Math.min(s.dueMinor(),recognized),s.createdMillis());
    }
    private static boolean boundedDues(JournalEngine.ReadView reads,Claim claim) {
        return reads.query("SELECT due_ms FROM deposit_yield_due WHERE account_id=? AND bank_key=? AND currency=? AND amount_minor>0 LIMIT 17",List.of(claim.account().value().toString(),claim.bank().key(),claim.currency().name()),17).size()<=16;
    }
    private static void validateStateBatch(String payload) {
        var lines=payload.split("\n",-1);if(lines.length<1 || lines.length>16)throw new IllegalArgumentException("Yield state batch bound");
        var keys=new HashSet<String>();
        for(var line:lines) {
            var p=line.split("\t",2);if(p.length!=2)throw new IllegalArgumentException("Yield state batch fields");
            long before=Long.parseLong(p[0]);var after=decode(p[1]);
            if(before<1 || after.revision()<=before || after.active() || after.accruedMajor().signum()!=0 || after.recognizedMinor()!=0 || after.dueMinor()!=0 || !keys.add(after.account().value()+"|"+after.bank().key()+"|"+after.currency()))throw new IllegalArgumentException("Yield state batch transition");
        }
    }
    private static void validateRoundingBatch(String payload) {
        var lines=payload.split("\n",-1);if(lines.length<1 || lines.length>16)throw new IllegalArgumentException("Yield rounding batch bound");
        for(var line:lines) {
            var p=line.split("\\|",-1);if(p.length!=8)throw new IllegalArgumentException("Yield rounding batch fields");
            validateRounding(String.join("|",Arrays.copyOf(p,6)));
            if(Long.parseLong(p[6])<1 || Long.parseLong(p[7])<=Long.parseLong(p[6]))throw new IllegalArgumentException("Yield rounding batch revisions");
        }
    }
    private static Claim dueSummary(Claim s,long amount,long effective) {
        return new Claim(s.account(),s.owner(),s.bank(),s.currency(),s.active(),s.lastMillis(),s.dayStart(),s.balanceMinor(),s.weightedMajorMillis(),s.accruedMajor(),s.priorCloseMajor(),s.recognizedMinor(),effective,s.distressed(),Math.addExact(s.revision(),1),s.paidMajor(),s.apr(),s.aprStartMillis(),amount,s.createdMillis());
    }
    private static void validateDue(String payload) {
        var p=payload.split("\\|",-1);if(p.length!=5)throw new IllegalArgumentException("Yield due fields");UUID.fromString(p[0]);InstitutionCatalog.require(new InstitutionCatalog.InstitutionId(p[1]));EconomyCurrency.valueOf(p[2]);
        if(Long.parseLong(p[3])<=0 || Long.parseLong(p[4])<0)throw new IllegalArgumentException("Yield due values");
    }
    private static String encode(Claim s) {
        return String.join("|",s.account().value().toString(),s.owner().value().toString(),s.bank().key(),s.currency().name(),s.active()?"1":"0",Long.toString(s.lastMillis()),Long.toString(s.dayStart()),Long.toString(s.balanceMinor()),s.weightedMajorMillis().toPlainString(),scale(s.accruedMajor()).toPlainString(),scale(s.priorCloseMajor()).toPlainString(),Long.toString(s.recognizedMinor()),Long.toString(s.dueMillis()),s.distressed()?"1":"0",Long.toString(s.revision()),scale(s.paidMajor()).toPlainString(),s.apr().toPlainString(),Long.toString(s.aprStartMillis()),Long.toString(s.dueMinor()),Long.toString(s.createdMillis()));
    }
    private static Claim decode(String payload) {
        var p=payload.split("\\|",-1);if(p.length!=20)throw new IllegalArgumentException("Yield fact fields");
        var s=new Claim(new AccountId(UUID.fromString(p[0])),new PlayerId(UUID.fromString(p[1])),new InstitutionCatalog.InstitutionId(p[2]),EconomyCurrency.valueOf(p[3]),p[4].equals("1"),Long.parseLong(p[5]),Long.parseLong(p[6]),Long.parseLong(p[7]),new BigDecimal(p[8]),new BigDecimal(p[9]),new BigDecimal(p[10]),Long.parseLong(p[11]),Long.parseLong(p[12]),p[13].equals("1"),Long.parseLong(p[14]),new BigDecimal(p[15]),new BigDecimal(p[16]),Long.parseLong(p[17]),Long.parseLong(p[18]),Long.parseLong(p[19]));
        if(!Set.of("0","1").contains(p[4]) || !Set.of("0","1").contains(p[13]) || s.lastMillis()<s.dayStart() || s.lastMillis()>Math.addExact(s.dayStart(),DAY) || s.dayStart()<0 || s.createdMillis()<0 || s.createdMillis()>s.lastMillis() || s.aprStartMillis()<s.dayStart() || s.aprStartMillis()>s.lastMillis() || s.balanceMinor()<0 || s.weightedMajorMillis().signum()<0 || s.recognizedMinor()<0 || s.dueMinor()<0 || s.dueMinor()>s.recognizedMinor() || s.dueMillis()<0 || s.revision()<1 || s.paidMajor().signum()<0 || s.apr().signum()<0 || s.apr().compareTo(BigDecimal.ONE)>0 || !encode(s).equals(payload))throw new IllegalArgumentException("Yield fact values");
        return s;
    }
    private static Claim row(Map<String,String> r) {
        return decode(String.join("|",r.get("account_id"),r.get("owner_id"),r.get("bank_key"),r.get("currency"),r.get("active"),r.get("last_ms"),r.get("day_start_ms"),r.get("balance_minor"),r.get("weighted_major_ms"),r.get("accrued_major"),r.get("prior_close_major"),r.get("recognized_minor"),r.get("due_ms"),r.get("distressed"),r.get("revision"),r.get("paid_major"),r.get("apr"),r.get("apr_start_ms"),r.get("due_minor"),r.get("created_ms")));
    }
    private static void write(FoundationStore.Transaction tx,Claim s) {
        var values=List.of(s.account().value().toString(),s.owner().value().toString(),s.bank().key(),s.currency().name(),s.active()?"1":"0",Long.toString(s.lastMillis()),Long.toString(s.dayStart()),Long.toString(s.balanceMinor()),s.weightedMajorMillis().toPlainString(),scale(s.accruedMajor()).toPlainString(),scale(s.priorCloseMajor()).toPlainString(),Long.toString(s.recognizedMinor()),Long.toString(s.dueMillis()),s.distressed()?"1":"0",Long.toString(s.revision()),scale(s.paidMajor()).toPlainString(),s.apr().toPlainString(),Long.toString(s.aprStartMillis()),Long.toString(s.dueMinor()),Long.toString(s.createdMillis()));
        tx.execute("INSERT INTO deposit_yield_claims VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(account_id,bank_key,currency) DO UPDATE SET active=excluded.active,last_ms=excluded.last_ms,day_start_ms=excluded.day_start_ms,balance_minor=excluded.balance_minor,weighted_major_ms=excluded.weighted_major_ms,accrued_major=excluded.accrued_major,prior_close_major=excluded.prior_close_major,recognized_minor=excluded.recognized_minor,due_ms=excluded.due_ms,distressed=excluded.distressed,revision=excluded.revision,paid_major=excluded.paid_major,apr=excluded.apr,apr_start_ms=excluded.apr_start_ms,due_minor=excluded.due_minor,created_ms=excluded.created_ms",values);
    }
    private static void validatePayment(String payload) {
        var p=payload.split("\\|",-1);if(p.length!=8)throw new IllegalArgumentException("Yield payment fields");UUID.fromString(p[0]);UUID.fromString(p[1]);InstitutionCatalog.require(new InstitutionCatalog.InstitutionId(p[2]));EconomyCurrency.valueOf(p[3]);if(Long.parseLong(p[4])<=0 || Long.parseLong(p[6])<0)throw new IllegalArgumentException("Yield payment amount");UUID.fromString(p[5]);
        if(!p[7].isEmpty()) {
            var conversions=p[7].split(";",-1);if(conversions.length>2)throw new IllegalArgumentException("Yield FX conversion count");
            for(var conversion:conversions) {
                var f=conversion.split(",",-1);if(f.length!=8)throw new IllegalArgumentException("Yield FX conversion fields");
                UUID.fromString(f[0]);EconomyCurrency.valueOf(f[1]);EconomyCurrency.valueOf(f[3]);EconomyCurrency.valueOf(f[5]);UUID.fromString(f[7]);
                if(Long.parseLong(f[2])<=0 || Long.parseLong(f[4])<=0 || new BigDecimal(f[6]).signum()<0)throw new IllegalArgumentException("Yield FX conversion values");
            }
        }
    }
    private static void validateRounding(String payload) {
        var p=payload.split("\\|",-1);if(p.length!=6)throw new IllegalArgumentException("Yield rounding fields");UUID.fromString(p[0]);InstitutionCatalog.require(new InstitutionCatalog.InstitutionId(p[1]));var c=EconomyCurrency.valueOf(p[2]);if(new BigDecimal(p[3]).abs().compareTo(BigDecimal.ONE.divide(BigDecimal.valueOf(c.minorUnitsPerMajor())))>=0 || Long.parseLong(p[5])<0)throw new IllegalArgumentException("Yield rounding amount");UUID.fromString(p[4]);
    }
}
