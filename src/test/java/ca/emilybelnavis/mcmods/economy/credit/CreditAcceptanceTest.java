package ca.emilybelnavis.mcmods.economy.credit;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.DeadlineDispatcher;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.WealthValuation;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.math.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Detached acceptance of the core. Production exposure stays gated by downstream safety. */
class CreditAcceptanceTest {
    @TempDir Path path;
    private static final Instant NOW=Instant.parse("2026-11-05T12:00:00Z");
    private static final InstitutionCatalog.InstitutionId BANK=new InstitutionCatalog.InstitutionId("economy:bank/crownstone");
    private final class Fixture implements AutoCloseable {
        SqliteStorage store=new SqliteStorage();
        final EconomyCurrency currency;
        final PlayerId actor=new PlayerId(UUID.randomUUID());
        final AccountId funding=new AccountId(UUID.randomUUID()),merchant=new AccountId(UUID.randomUUID());
        final AtomicLong time=new AtomicLong(NOW.toEpochMilli());
        final PartyRef owner;
        final Clock wall=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(time.get());}};
        DeadlineDispatcher dispatcher;
        Fixture(EconomyCurrency currency)throws Exception {
            this.currency=currency;store.initialize(path.toRealPath());owner=new PartyRef(store.snapshot().worldId(),new PartyRef.Player(actor));
            assertEquals(Journal.Status.COMMITTED,submit(FxService.OPEN,FxService.opening(wall),intent(FxService.OPEN,new Journal.CanonicalRequest(1,Map.of("currency",currency.name())))).status());
            var capital=new InstitutionCapital(store,new SystemActorId(UUID.randomUUID()),CapitalPosting.poster());
            for(int n=0;n<26;n++)assertEquals(Journal.Status.COMMITTED,capital.advance(NOW).status());
            store.transaction(tx->{
                tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK',?,'OPEN',1,1)",List.of(funding.value().toString(),actor.value().toString(),currency.name()));
                tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK',?,'OPEN',1,1)",List.of(merchant.value().toString(),UUID.randomUUID().toString(),currency.name()));return null;
            });
            var legs=List.of(new PostingBatch.Leg(Settlement.issuanceAccount(currency),PostingBatch.Role.ISSUANCE,currency,new Money(currency,-1_000_000)),new PostingBatch.Leg(funding.value(),PostingBatch.Role.ACCOUNT,currency,new Money(currency,1_000_000)));
            var seed=new Journal.Kind("fixture:credit-acceptance","fixture:fund");
            assertEquals(Journal.Status.COMMITTED,submit(seed,Settlement.registrationMinor((i,l,r)->PostingBatch.Authority.ISSUE),intent(seed,Settlement.minorRequest(legs))).status());
            dispatcher=new DeadlineDispatcher(store,wall,CreditRuntime.providers(),1);dispatcher.prepare();
        }
        Journal.Intent intent(Journal.Kind kind,Journal.CanonicalRequest request){return new Journal.Intent(new OperationId(UUID.randomUUID()),new RequestId(UUID.randomUUID()),actor,kind,request,null,null);}
        Journal.Outcome submit(Journal.Kind kind,JournalEngine.Registration handler,Journal.Intent intent){return new JournalEngine(store,(a,k)->true,Map.of(kind,handler)).submit(intent);}
        CreditFacilities.Inputs inputs(JournalEngine.ReadView reads,PartyRef owner,long now){var fx=FxService.latest(reads);return new CreditFacilities.Inputs(new CreditUnderwriting.FxSnapshot(fx.updateId(),fx.day().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),now,fx.rates(),true),true,false,0);}
        CreditFacilities.Facility issue(){
            long revision=store.transaction(tx->CreditProfiles.find(tx::query,owner).map(CreditProfiles.Profile::revision).orElse(0L));
            var proof=BranchFixtures.proof(new BranchAdmission.Context(owner.world(),UUID.randomUUID(),actor,BranchAdmission.Action.CREDIT_ISSUE,BANK,null,null,0,0,0));
            var request=new CreditFacilities.IssueRequest(proof,UUID.randomUUID(),InstitutionCatalog.Network.ORBIT,currency,2500L*currency.minorUnitsPerMajor(),revision,0,NOW.toEpochMilli());
            assertEquals("CREDIT_CARD_ISSUED",submit(CreditFacilities.ISSUE,CreditFacilities.issuance(owner,request,BankProviders.NO_TIER2,this::inputs,time::get),intent(CreditFacilities.ISSUE,request.request())).result().code());return get(request.facility());
        }
        CreditFacilities.Facility get(UUID id){return store.transaction(tx->CreditFacilities.find(tx::query,id).orElseThrow());}
        CreditFacilities.Token token(UUID facility){return store.transaction(tx->CreditFacilities.tokenForFacility(tx::query,owner.world(),facility).orElseThrow());}
        CreditFacilities.DrawRequest drawRequest(UUID facility,CreditFacilities.DrawKind kind,long amount){var f=get(facility);var t=token(facility);return new CreditFacilities.DrawRequest(owner.world(),actor,facility,t.id(),t.network(),kind,kind==CreditFacilities.DrawKind.PURCHASE?merchant:funding,amount,f.revision(),t.revision(),1,1,UUID.randomUUID(),time.get()+30_000);}
        Journal.Outcome draw(UUID facility,CreditFacilities.DrawKind kind,long amount){var r=drawRequest(facility,kind,amount);return submit(CreditFacilities.DRAW,CreditFacilities.draw(owner,r,this::inputs,(i,q,t,k,a)->true,time::get),intent(CreditFacilities.DRAW,r.request()));}
        Journal.Outcome pay(UUID facility,long amount){var f=get(facility);long ledger=store.transaction(tx->WealthValuation.ledgerRevision(tx::query,funding.value()));var r=new CreditFacilities.RepaymentRequest(owner.world(),actor,facility,funding,amount,f.revision(),1,ledger,UUID.randomUUID(),Math.min(time.get()+30_000,Math.min(f.nextDay(),f.nextStatement())));return submit(CreditFacilities.REPAY,CreditFacilities.repayment(owner,r,time::get),intent(CreditFacilities.REPAY,r.request()));}
        int catchup(long at){time.set(at);int turns=0;while(!dispatcher.progress().ready()&&turns<1000){long before=store.lastCommittedSequence();dispatcher.advance();assertTrue(store.lastCommittedSequence()-before<=1,"one child/completion per turn");turns++;}assertTrue(dispatcher.progress().ready());return turns;}
        void reopen()throws Exception {store.close();store=new SqliteStorage();store.initialize(path.toRealPath());dispatcher=new DeadlineDispatcher(store,wall,CreditRuntime.providers(),1);dispatcher.prepare();}
        long balance(FinancialAccounts.Role role){return store.transaction(tx->Settlement.posted(tx::query,FinancialAccounts.id(owner.world(),BANK,role,currency),currency));}
        long count(String type){return store.transaction(tx->Long.parseLong(tx.query("SELECT count(*) AS n FROM journal_records WHERE record_type=?",List.of(type),1).getFirst().get("n")));}
        void reconcile(UUID facility){var f=get(facility);assertEquals(f.owedMinor(),balance(FinancialAccounts.Role.RECEIVABLE));var borrower=new InstitutionCatalog.InstitutionId("economy:borrower/"+actor.value());long liability=store.transaction(tx->Settlement.posted(tx::query,FinancialAccounts.id(owner.world(),borrower,FinancialAccounts.Role.LIABILITY,currency),currency));assertEquals(-f.owedMinor(),liability);
            var totals=new EnumMap<EconomyCurrency,BigInteger>(EconomyCurrency.class);for(var row:store.transaction(tx->tx.query("SELECT payload FROM journal_records WHERE record_type='economy:posting' ORDER BY sequence,position LIMIT 1024",List.of(),1024))){var p=row.get("payload").split("\\|",-1);totals.merge(EconomyCurrency.valueOf(p[2]),new BigInteger(p[3]),BigInteger::add);}assertTrue(totals.values().stream().allMatch(BigInteger.ZERO::equals),totals.toString());}
        @Override public void close()throws Exception {store.close();}
    }
    private static BigDecimal earned(EconomyCurrency currency,CardServicing.Bucket bucket){return BigDecimal.valueOf(bucket.debtMinor()).divide(BigDecimal.valueOf(currency.minorUnitsPerMajor()),CardServicing.MC).add(bucket.residualMajor(),CardServicing.MC).multiply(bucket.apr(),CardServicing.MC).divide(BigDecimal.valueOf(364),CardServicing.MC).setScale(18,RoundingMode.HALF_EVEN);}

    @Test void mixedBalancesPartialAndFullPayoffPreserveEarnedHistoryAndReservation()throws Exception {
        try(var f=new Fixture(EconomyCurrency.CAD)){
            var line=f.issue();var id=line.id();long anchor=line.anchor();
            assertEquals("CREDIT_DRAWN",f.draw(id,CreditFacilities.DrawKind.PURCHASE,12300).result().code());assertEquals("CREDIT_DRAWN",f.draw(id,CreditFacilities.DrawKind.ADVANCE,10000).result().code());
            assertEquals(-300,f.balance(FinancialAccounts.Role.INCOME),"Issuer earns the advance fee before daily interest");
            f.catchup(anchor+CreditFacilities.DAY);line=f.get(id);
            // The CAD 3.00 advance fee is financed and earns interest with the CAD 100.00 proceeds.
            assertEquals(10300,line.advance().principalMinor());
            assertEquals(new BigDecimal("0.067548626373626374"),line.purchase().periodEarnedMajor());assertEquals(new BigDecimal("0.062224450549450549"),line.advance().periodEarnedMajor());
            assertEquals("CREDIT_REPAID",f.pay(id,5000).result().code());line=f.get(id);assertEquals(12300,line.purchase().principalMinor());assertEquals(5313,line.advance().principalMinor());assertEquals(0,line.purchase().interestMinor());assertEquals(0,line.advance().interestMinor());
            assertEquals("CREDIT_DRAWN",f.draw(id,CreditFacilities.DrawKind.PURCHASE,5000).result().code());line=f.get(id);var expectedP=earned(f.currency,line.purchase());var expectedA=earned(f.currency,line.advance());
            f.catchup(anchor+2*CreditFacilities.DAY);var changed=f.get(id);assertEquals(0,changed.purchase().periodEarnedMajor().subtract(line.purchase().periodEarnedMajor()).compareTo(expectedP));assertEquals(0,changed.advance().periodEarnedMajor().subtract(line.advance().periodEarnedMajor()).compareTo(expectedA));
            long debt=changed.owedMinor(),before=f.store.lastCommittedSequence();assertEquals("CREDIT_FRESH_PAYOFF_REQUIRED",f.pay(id,debt+1).result().code());assertEquals(debt,f.get(id).owedMinor());assertEquals(before,f.store.lastCommittedSequence(),"A durable rejection must not add a financial journal transaction");
            assertEquals("CREDIT_REPAID",f.pay(id,debt).result().code());var paid=f.get(id);assertEquals(0,paid.owedMinor());assertEquals(0,paid.purchase().residualMajor().signum());assertEquals(250000,f.balance(FinancialAccounts.Role.RESERVATION));assertTrue(paid.purchase().periodEarnedMajor().signum()>0);
            f.catchup(anchor+3*CreditFacilities.DAY);assertEquals(0,f.get(id).owedMinor());assertEquals(2,f.count(CreditAuditFacts.PAYMENT));f.reconcile(id);
        }
    }

    @Test void dueDateAndAnotherCardsStatementOverlapWithoutDoubleSatisfyingRequirements()throws Exception {
        try(var f=new Fixture(EconomyCurrency.CAD)){
            var first=f.issue();assertEquals("CREDIT_DRAWN",f.draw(first.id(),CreditFacilities.DrawKind.PURCHASE,10000).result().code());
            f.catchup(first.anchor()+14*CreditFacilities.DAY);var second=f.issue();assertEquals("CREDIT_DRAWN",f.draw(second.id(),CreditFacilities.DrawKind.ADVANCE,1000).result().code());
            f.catchup(first.anchor()+42*CreditFacilities.DAY);var a=f.get(first.id());var b=f.get(second.id());assertEquals(first.anchor()+42*CreditFacilities.DAY,a.requirements().getFirst().dueAt());assertEquals(a.requirements().getFirst().dueAt(),b.lastStatement());
            long minimum=a.requiredMinor();assertEquals("CREDIT_REPAID",f.pay(a.id(),minimum-1).result().code());assertEquals(1,f.get(a.id()).requiredMinor());assertEquals(b.requiredMinor(),f.get(b.id()).requiredMinor());assertEquals("CREDIT_REPAID",f.pay(a.id(),1).result().code());assertEquals(0,f.get(a.id()).requiredMinor());assertEquals(b.requiredMinor(),f.get(b.id()).requiredMinor());
            var snapshot=f.store.transaction(tx->CreditFacilities.statements(tx::query,f.owner,a.id(),-1,16)).statements().getFirst().statement();assertEquals(minimum,snapshot.newRequirementMinor());assertTrue(snapshot.debtMinor()>f.get(a.id()).owedMinor());
        }
    }

    @Test void threeOfflinePeriodsAndRestartKeepOneReceiptPerAnchorBoundary()throws Exception {
        try(var f=new Fixture(EconomyCurrency.CAD)){
            var line=f.issue();var id=line.id();assertEquals("CREDIT_DRAWN",f.draw(id,CreditFacilities.DrawKind.PURCHASE,12300).result().code());assertEquals("CREDIT_DRAWN",f.draw(id,CreditFacilities.DrawKind.ADVANCE,1000).result().code());
            int firstTurns=f.catchup(line.anchor()+56*CreditFacilities.DAY);assertTrue(firstTurns<130);assertEquals(56,f.count(CreditAuditFacts.DAILY));assertEquals(2,f.count(CreditFacilities.STATEMENT_FACT));
            long sequence=f.store.lastCommittedSequence();f.reopen();assertEquals(sequence,f.store.lastCommittedSequence());assertTrue(f.dispatcher.progress().ready());
            int more=f.catchup(line.anchor()+84*CreditFacilities.DAY);assertTrue(more<65);assertEquals(84,f.count(CreditAuditFacts.DAILY));assertEquals(3,f.count(CreditFacilities.STATEMENT_FACT));
            var result=f.get(id);assertEquals(line.anchor()+84*CreditFacilities.DAY,result.lastDay());assertEquals(result.lastDay(),result.lastStatement());assertEquals(3,result.requirements().size());assertTrue(result.requiredMinor()<=result.owedMinor());
            long after=f.store.lastCommittedSequence();f.dispatcher.advance();assertEquals(after,f.store.lastCommittedSequence());f.reconcile(id);
        }
    }

    @Test void smallestUnitPayoffOverflowAndUnissuedTokensFailWithoutBorrowingAuthority()throws Exception {
        try(var f=new Fixture(EconomyCurrency.JPY)){
            var line=f.issue();var id=line.id();assertEquals(2500,line.limitMinor());assertEquals("CREDIT_DRAWN",f.draw(id,CreditFacilities.DrawKind.PURCHASE,1).result().code());
            f.catchup(line.anchor()+CreditFacilities.DAY);line=f.get(id);assertEquals(0,line.purchase().interestMinor());assertTrue(line.purchase().residualMajor().signum()>0);
            assertEquals("CREDIT_REPAID",f.pay(id,1).result().code());assertEquals(0,f.get(id).owedMinor());assertEquals(0,f.get(id).purchase().residualMajor().signum());
            assertEquals("CREDIT_DRAW_LIMIT",f.draw(id,CreditFacilities.DrawKind.PURCHASE,Long.MAX_VALUE).result().code());assertEquals(0,f.get(id).owedMinor());
            var request=f.drawRequest(id,CreditFacilities.DrawKind.PURCHASE,1);var forged=new CreditFacilities.DrawRequest(request.world(),request.owner(),request.facility(),UUID.randomUUID(),request.network(),request.kind(),request.destination(),request.amountMinor(),request.facilityRevision(),request.tokenRevision(),request.destinationRevision(),request.profileRevision(),request.quote(),request.expiresAt());
            assertEquals("CREDIT_CARD_STALE",f.submit(CreditFacilities.DRAW,CreditFacilities.draw(f.owner,forged,f::inputs,(i,r,t,k,a)->true,f.time::get),f.intent(CreditFacilities.DRAW,forged.request())).result().code());assertEquals(0,f.get(id).owedMinor());
            assertThrows(ArithmeticException.class,()->new CardServicing.Bucket(CardServicing.Kind.PURCHASE,Long.MAX_VALUE,1,BigDecimal.ZERO.setScale(18),BigDecimal.ZERO.setScale(18),new BigDecimal("0.1999")));f.reconcile(id);
        }
    }
}
