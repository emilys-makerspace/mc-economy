package ca.emilybelnavis.mcmods.economy.loan;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.storage.LedgerProjection;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LoanDiagnosticsTest {
    @TempDir Path path;
    private LoanDiagnostics.OperatorView inspect(LoanTestFixture f,UUID id,int limit){return f.store.transaction(tx->LoanDiagnostics.inspect(tx::query,f.owner,id,f.clock.get(),LoanDiagnostics.EventCursor.start(),limit).orElseThrow());}

    @Test void ownerIsolationExclusivePagesAndOriginalScheduleSurvivePayment()throws Exception{
        try(var f=new LoanTestFixture(path)){
            var first=f.originate();var second=f.originate();long frontier=f.store.lastCommittedSequence();
            var page=f.store.transaction(tx->LoanDiagnostics.ownerPage(tx::query,f.owner,null,1,f.clock.get()));
            assertEquals(1,page.entries().size());assertNotNull(page.nextCursor());
            var next=f.store.transaction(tx->LoanDiagnostics.ownerPage(tx::query,f.owner,page.nextCursor(),1,f.clock.get()));
            assertEquals(1,next.entries().size());assertNull(next.nextCursor());assertNotEquals(page.entries().getFirst().contract().id(),next.entries().getFirst().contract().id());
            var stranger=new PartyRef(f.owner.world(),new PartyRef.Player(new PlayerId(UUID.randomUUID())));
            assertTrue(f.store.transaction(tx->LoanDiagnostics.contract(tx::query,stranger,first.id(),f.clock.get())).isEmpty());
            assertTrue(f.store.transaction(tx->LoanDiagnostics.inspect(tx::query,stranger,first.id(),f.clock.get(),LoanDiagnostics.EventCursor.start(),16)).isEmpty());
            assertThrows(IllegalArgumentException.class,()->f.store.transaction(tx->LoanDiagnostics.schedule(tx::query,stranger,first.id(),0,16)));
            var original=f.store.transaction(tx->LoanDiagnostics.schedule(tx::query,f.owner,first.id(),0,4));
            assertEquals(4,original.entries().size());assertEquals(4,original.nextOrdinal());
            f.clock.addAndGet(Loans.MONTH/3);
            var quote=f.store.transaction(tx->Loans.quote(tx::query,f.owner,first.id(),f.account,10000,false,f.clock.get()));
            assertEquals("LOAN_REPAID",f.submit(Loans.REPAY,Loans.repayment(f.owner,quote,f.clock::get),f.intent(Loans.REPAY,quote.request().request())).result().code());
            assertEquals(original,f.store.transaction(tx->LoanDiagnostics.schedule(tx::query,f.owner,first.id(),0,4)));
            assertEquals(5,f.store.transaction(tx->LoanDiagnostics.schedule(tx::query,f.owner,first.id(),4,4)).entries().getFirst().ordinal());
            assertTrue(f.store.lastCommittedSequence()>frontier);
            assertThrows(IllegalArgumentException.class,()->f.store.transaction(tx->LoanDiagnostics.ownerPage(tx::query,f.owner,null,17,f.clock.get())));
            assertThrows(IllegalArgumentException.class,()->new LoanDiagnostics.EventCursor(0,0));
            f.store.transaction(tx->{tx.execute("UPDATE loan_schedule SET payment_minor=payment_minor+1 WHERE loan_id=? AND ordinal=1",List.of(first.id().toString()));return null;});
            assertThrows(IllegalArgumentException.class,()->f.store.transaction(tx->LoanDiagnostics.schedule(tx::query,f.owner,first.id(),0,4)));
        }
    }
    @Test void frozenFundingAndPaymentReceiptsReconcileToImmutablePostings()throws Exception{
        try(var f=new LoanTestFixture(path)){
            var loan=f.originate();var original=inspect(f,loan.id(),1);
            assertEquals(loan.originalPrincipalMinor(),original.funding().orElseThrow().amountMinor());
            assertEquals(loan.apr(),original.application().orElseThrow().apr());
            assertTrue(original.reconciliation().ownerMatches());assertTrue(original.reconciliation().issuerMatches());
            assertTrue(original.reconciliation().ownerJournalMatches());assertTrue(original.reconciliation().issuerJournalMatches());
            f.clock.addAndGet(Loans.MONTH/2);
            var quote=f.store.transaction(tx->Loans.quote(tx::query,f.owner,loan.id(),f.account,10000,false,f.clock.get()));
            var outcome=f.submit(Loans.REPAY,Loans.repayment(f.owner,quote,f.clock::get),f.intent(Loans.REPAY,quote.request().request()));assertEquals("LOAN_REPAID",outcome.result().code());
            var ownerFirst=f.store.transaction(tx->LoanDiagnostics.ownerPayments(tx::query,f.owner,loan.id(),LoanDiagnostics.EventCursor.start(),1).orElseThrow());
            assertTrue(ownerFirst.entries().isEmpty());assertNotNull(ownerFirst.nextCursor());
            var ownerNext=f.store.transaction(tx->LoanDiagnostics.ownerPayments(tx::query,f.owner,loan.id(),ownerFirst.nextCursor(),1).orElseThrow());
            assertEquals(10000,ownerNext.entries().getFirst().payment().amountMinor());
            var other=new PartyRef(f.owner.world(),new PartyRef.Player(new PlayerId(UUID.randomUUID())));
            assertTrue(f.store.transaction(tx->LoanDiagnostics.ownerPayments(tx::query,other,loan.id(),LoanDiagnostics.EventCursor.start(),1)).isEmpty());
            var page=inspect(f,loan.id(),1);assertNotNull(page.events().nextCursor());
            var next=f.store.transaction(tx->LoanDiagnostics.inspect(tx::query,f.owner,loan.id(),f.clock.get(),page.events().nextCursor(),1).orElseThrow());
            var payment=next.events().entries().getFirst().payment();assertEquals(10000,payment.amountMinor());assertTrue(payment.journalMatches());assertEquals(payment.amountMinor(),payment.principalMinor()+payment.interestMinor());
            assertEquals(payment.amountMinor(),payment.allocations().stream().mapToLong(LoanServicing.Allocation::minor).sum());
            assertTrue(next.reconciliation().ownerJournalMatches());assertTrue(next.reconciliation().issuerJournalMatches());
            assertEquals(original.funding(),next.funding());assertEquals(original.application(),next.application());assertNull(next.events().nextCursor());
            var liability=FinancialAccounts.id(loan.world(),Loans.borrower(loan.owner()),FinancialAccounts.Role.LIABILITY,loan.currency());
            f.store.transaction(tx->{LedgerProjection.write(tx,liability,loan.currency(),-1);return null;});
            var mismatch=inspect(f,loan.id(),16);assertFalse(mismatch.reconciliation().ownerMatches());assertFalse(mismatch.reconciliation().ownerJournalMatches());assertTrue(mismatch.reconciliation().issuerMatches());
        }
    }
    @Test void missingCurrentPrimeAndMonthlyBacklogDoNotRewriteCapturedFixedTerms()throws Exception{
        try(var f=new LoanTestFixture(path)){
            var loan=f.originate();f.store.transaction(tx->{tx.execute("DELETE FROM pricing_policies",List.of());return null;});
            var current=inspect(f,loan.id(),16);assertEquals("CURRENT_PRIME_UNAVAILABLE_EXISTING_FIXED_RATE_UNCHANGED",current.currentPrimeStatus());assertEquals(loan.apr(),current.contract().contract().apr());assertTrue(current.contract().current());
            f.clock.set(loan.nextMonth());long frontier=f.store.lastCommittedSequence();
            var overdue=inspect(f,loan.id(),16);assertEquals("MONTHLY_CATCHUP_REQUIRED",overdue.contract().availability());assertFalse(overdue.contract().current());assertNull(overdue.contract().exactDebtMajor());assertEquals(1,overdue.contract().overdueMonths());assertEquals(loan,overdue.contract().contract());assertEquals(frontier,f.store.lastCommittedSequence());
            f.store.transaction(tx->{tx.execute("INSERT INTO financial_deadline_providers(provider,implementations) VALUES('economy:a6/loan-monthly','v1')",List.of());tx.execute("INSERT INTO financial_deadline_events VALUES('fixture:loan','economy:a6/loan-monthly',?,1,1,?,0,'APPLYING',1)",List.of(Long.toString(loan.nextMonth()),UUID.randomUUID().toString()));return null;});
            assertEquals("OTHER_TARGET",inspect(f,loan.id(),16).provider().applyingCursor());
        }
    }
    @Test void boundedReconciliationNeverClaimsPartialPortfolioIsCompleteAndQueriesUseIndexes()throws Exception{
        try(var f=new LoanTestFixture(path)){
            var loan=f.originate();var limits=new ArrayList<Integer>();
            var partial=f.store.transaction(tx->{
                JournalEngine.ReadView reads=(sql,args,limit)->{
                    limits.add(limit);
                    if(sql.startsWith("SELECT debt_minor FROM loans WHERE world_id=? AND owner_id=?"))return Collections.nCopies(129,Map.of("debt_minor","100000"));
                    return tx.query(sql,args,limit);
                };
                return LoanDiagnostics.inspect(reads,f.owner,loan.id(),f.clock.get(),LoanDiagnostics.EventCursor.start(),16).orElseThrow();
            });
            assertFalse(partial.reconciliation().ownerFacilitiesComplete());assertFalse(partial.reconciliation().ownerMatches());assertFalse(partial.reconciliation().ownerJournalMatches());assertEquals(12_800_000,partial.reconciliation().ownerCurrencyDebtMinor());assertTrue(limits.stream().allMatch(n->n<=129));
            String key=Base64.getUrlEncoder().withoutPadding().encodeToString(loan.id().toString().getBytes(StandardCharsets.UTF_8));
            String eventSql="SELECT sequence,position FROM journal_records WHERE record_type IN('economy:a6/loan-event','economy:a6/loan') AND record_type='economy:a6/loan-event' AND substr(payload,instr(payload,'|')+1,48)=? AND sequence<=? AND (sequence>? OR (sequence=? AND position>?)) ORDER BY sequence,position LIMIT ?";
            assertPlan(f,eventSql,List.of(key,Long.toString(f.store.lastCommittedSequence()),"0","0","-1","17"),"loan_diagnostic_records");
            assertPlan(f,"SELECT debt_minor FROM loans WHERE world_id=? AND owner_id=? AND currency=? AND status<>'SETTLED' ORDER BY loan_id LIMIT 129",List.of(loan.world().value().toString(),loan.owner().value().toString(),"CAD"),"loan_outstanding_owner");
            assertPlan(f,"SELECT debt_minor FROM loans WHERE world_id=? AND lender=? AND currency=? AND debt_minor>0 ORDER BY loan_id LIMIT 129",List.of(loan.world().value().toString(),loan.lender().key(),"CAD"),"loan_outstanding_lender");
            var account=FinancialAccounts.id(loan.world(),Loans.borrower(loan.owner()),FinancialAccounts.Role.LIABILITY,loan.currency());
            assertPlan(f,LoanDiagnostics.JOURNAL_TOTAL_QUERY,List.of(account.toString(),Long.toString(f.store.lastCommittedSequence())),"ledger_posting_account");
        }
    }
    @Test void guessedLoanIdsNeverExposeAnotherApplicantsDenialAndFilteredPagesStillAdvance()throws Exception{
        try(var f=new LoanTestFixture(path)){
            var application=f.application(ca.emilybelnavis.mcmods.economy.credit.CreditUnderwriting.Product.BANK_LOAN,100000,1);
            var stranger=new PlayerId(UUID.randomUUID());var strangerAccount=new AccountId(UUID.randomUUID());var strangerOwner=new PartyRef(f.owner.world(),new PartyRef.Player(stranger));
            f.store.transaction(tx->{tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",List.of(strangerAccount.value().toString(),stranger.value().toString()));return null;});
            assertEquals("LOAN_APPLICATION_DENIED",denyGuessed(f,application,strangerOwner,strangerAccount));
            assertEquals("LOAN_ORIGINATED",f.apply(application,f.intent(Loans.APPLY,application.request())).result().code());
            assertEquals("LOAN_EXISTS",denyGuessed(f,application,strangerOwner,strangerAccount));
            // Explicit historical fault fixture: ensure filtered rows advance a cursor without disclosure.
            deniedEventFixture(f,application.loan());deniedEventFixture(f,application.loan());
            var first=inspect(f,application.loan(),1);assertEquals("ORIGINATED",first.events().entries().getFirst().kind());assertNotNull(first.events().nextCursor());
            assertEquals(f.actor,first.application().orElseThrow().owner());
            var second=f.store.transaction(tx->LoanDiagnostics.inspect(tx::query,f.owner,application.loan(),f.clock.get(),first.events().nextCursor(),1).orElseThrow());
            assertTrue(second.events().entries().isEmpty());assertNotNull(second.events().nextCursor());
            var third=f.store.transaction(tx->LoanDiagnostics.inspect(tx::query,f.owner,application.loan(),f.clock.get(),second.events().nextCursor(),1).orElseThrow());
            assertTrue(third.events().entries().isEmpty());assertNull(third.events().nextCursor());
        }
    }
    private static String denyGuessed(LoanTestFixture f,Loans.Application target,PartyRef owner,AccountId account){
        var player=((PartyRef.Player)owner.owner()).id();
        var proof=BranchFixtures.proof(new BranchAdmission.Context(owner.world(),UUID.randomUUID(),player,BranchAdmission.Action.LOAN_APPLY,LoanTestFixture.BANK,null,null,0,0,0));
        var request=new Loans.Application(proof,UUID.randomUUID(),target.loan(),target.product(),target.currency(),target.principalMinor(),target.years(),account,1,0,0,1,target.fxPublication(),target.calendarOrigin(),Loans.RateMode.FIXED,false);
        var intent=new Journal.Intent(new OperationId(UUID.randomUUID()),new RequestId(UUID.randomUUID()),player,Loans.APPLY,request.request(),null,null);
        var outcome=f.submit(Loans.APPLY,Loans.application(owner,request,BankProviders.NO_TIER2,f::inputs,f.clock::get),intent);
        return outcome.result().code();
    }
    private static void deniedEventFixture(LoanTestFixture f,UUID loan){
        var fields=List.of("APPLICATION_DENIED",loan.toString(),Long.toString(f.clock.get()),"PRIVATE_OTHER_APPLICANT_REASON");
        var payload=String.join("|",fields.stream().map(v->Base64.getUrlEncoder().withoutPadding().encodeToString(v.getBytes(StandardCharsets.UTF_8))).toList());
        var kind=new Journal.Kind("fixture:loan-diagnostics","fixture:denied");
        var registration=new JournalEngine.Registration(1,(i,r)->new JournalEngine.Commit(List.of(new Journal.Record(Loans.EVENT_FACT,1,payload)),new Journal.Result(1,"FIXTURE_DENIED",""),tx->{}));
        assertEquals(Journal.Status.COMMITTED,f.submit(kind,registration,f.intent(kind,new Journal.CanonicalRequest(1,Map.of()))).status());
    }
    private static void assertPlan(LoanTestFixture f,String sql,List<String> args,String index)throws Exception{
        var plan=new StringBuilder();
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:file:"+f.path.resolve("economy/economy.sqlite")+"?mode=ro");var statement=connection.prepareStatement("EXPLAIN QUERY PLAN "+sql)){
            for(int n=0;n<args.size();n++)statement.setString(n+1,args.get(n));
            try(var rows=statement.executeQuery()){while(rows.next())plan.append(rows.getString(4)).append('\n');}
        }
        assertTrue(plan.toString().contains(index),plan.toString());assertFalse(plan.toString().contains("USE TEMP B-TREE"),plan.toString());
    }
}
