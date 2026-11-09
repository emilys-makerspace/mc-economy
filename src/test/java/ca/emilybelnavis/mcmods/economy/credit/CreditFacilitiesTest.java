package ca.emilybelnavis.mcmods.economy.credit;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.WealthValuation;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CreditFacilitiesTest {
    @TempDir Path path;
    private static final Instant NOW=Instant.parse("2026-11-05T12:00:00Z");
    private static final InstitutionCatalog.InstitutionId BANK=new InstitutionCatalog.InstitutionId("economy:bank/crownstone");
    private final class Fixture implements AutoCloseable {
        final SqliteStorage store=new SqliteStorage();
        final PlayerId actor=new PlayerId(UUID.randomUUID());final AccountId account=new AccountId(UUID.randomUUID());
        final AtomicLong time=new AtomicLong(NOW.toEpochMilli());final PartyRef owner;
        Fixture()throws Exception {
            store.initialize(path.toRealPath());owner=new PartyRef(store.snapshot().worldId(),new PartyRef.Player(actor));
            var fx=new JournalEngine(store,(a,k)->true,Map.of(FxService.OPEN,FxService.opening(Clock.fixed(NOW,ZoneOffset.UTC))));
            assertEquals(Journal.Status.COMMITTED,fx.submit(intent(FxService.OPEN,new Journal.CanonicalRequest(1,Map.of("currency","CAD")))).status());
            var capital=new InstitutionCapital(store,new SystemActorId(UUID.randomUUID()),CapitalPosting.poster());
            for(int n=0;n<26;n++)assertEquals(Journal.Status.COMMITTED,capital.advance(NOW).status());
            store.transaction(tx->{tx.execute("INSERT INTO financial_calendar(singleton,version,origin_ms,observed_wall_ms,last_effective_ms,completed_effects) VALUES(1,1,?,?,?,0)",List.of(Long.toString(time.get()),Long.toString(time.get()),Long.toString(time.get())));tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",List.of(account.value().toString(),actor.value().toString()));return null;});
        }
        CreditFacilities.Inputs inputs(JournalEngine.ReadView reads,PartyRef owner,long now){var p=FxService.latest(reads);return new CreditFacilities.Inputs(new CreditUnderwriting.FxSnapshot(p.updateId(),p.day().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),now,p.rates(),true),true,false,0);}
        CreditFacilities.IssueRequest request(long amount) {
            long revision=store.transaction(tx->CreditProfiles.find(tx::query,owner).map(CreditProfiles.Profile::revision).orElse(0L));
            var proof=BranchFixtures.proof(new BranchAdmission.Context(owner.world(),UUID.randomUUID(),actor,BranchAdmission.Action.valueOf("CREDIT_ISSUE"),BANK,null,null,0,0,0));
            return new CreditFacilities.IssueRequest(proof,UUID.randomUUID(),InstitutionCatalog.Network.ORBIT,EconomyCurrency.CAD,amount,revision,0,NOW.toEpochMilli());
        }
        Journal.Intent intent(Journal.Kind kind,Journal.CanonicalRequest request){return new Journal.Intent(new OperationId(UUID.randomUUID()),new RequestId(UUID.randomUUID()),actor,kind,request,null,null);}
        Journal.Outcome submit(Journal.Kind kind,JournalEngine.Registration registration,Journal.Intent intent){return new JournalEngine(store,(a,k)->true,Map.of(kind,registration)).submit(intent);}
        CreditFacilities.Facility issue(){var r=request(250000);var outcome=submit(CreditFacilities.ISSUE,CreditFacilities.issuance(owner,r,BankProviders.NO_TIER2,this::inputs,time::get),intent(CreditFacilities.ISSUE,r.request()));assertEquals("CREDIT_CARD_ISSUED",outcome.result().code());return get(r.facility());}
        CreditFacilities.Facility get(UUID id){return store.transaction(tx->CreditFacilities.find(tx::query,id).orElseThrow());}
        CreditFacilities.Token token(UUID id){return store.transaction(tx->CreditFacilities.tokenForFacility(tx::query,owner.world(),id).orElseThrow());}
        long balance(FinancialAccounts.Role role){var id=FinancialAccounts.id(owner.world(),BANK,role,EconomyCurrency.CAD);return store.transaction(tx->Settlement.posted(tx::query,id,EconomyCurrency.CAD));}
        long cash(){return store.transaction(tx->Settlement.posted(tx::query,account.value(),EconomyCurrency.CAD));}
        CreditFacilities.DrawRequest drawRequest(CreditFacilities.Facility f,CreditFacilities.DrawKind kind,long amount){var token=token(f.id());return new CreditFacilities.DrawRequest(owner.world(),actor,f.id(),token.id(),token.network(),kind,account,amount,f.revision(),token.revision(),1,1,UUID.randomUUID(),time.get()+30000);}
        Journal.Outcome draw(CreditFacilities.DrawRequest r,Journal.Intent intent){return submit(CreditFacilities.DRAW,CreditFacilities.draw(owner,r,this::inputs,(i,reads,t,k,a)->true,time::get),intent);}
        TerminalPolicy.Snapshot terminal(InstitutionCatalog.InstitutionId bank, TerminalPolicy.Kind kind) {
            var anchor=new TerminalPolicy.Anchor("minecraft:overworld", 9,64,9); var generation=UUID.randomUUID();
            var denominations=Map.of("economy:cad_bill_5",new TerminalPolicy.Denomination(EconomyCurrency.CAD,true));
            var place=TerminalPolicy.Action.PLACE;
            assertEquals(Journal.Status.COMMITTED,submit(place.kind(),TerminalPolicy.registration(place,owner.world(),denominations,(a,i,c,r)->true,(a,r)->true),
                    intent(place.kind(),TerminalPolicy.placeRequest(anchor,generation,actor,kind,null,0))).status());
            assertEquals(Journal.Status.COMMITTED,submit(TerminalBanking.BIND,TerminalBanking.registration(owner.world(),denominations,(a,i,c,r)->true,(a,r)->true),
                    intent(TerminalBanking.BIND,TerminalBanking.bindRequest(anchor,generation,1,bank,true))).status());
            return store.transaction(tx->TerminalPolicy.find(tx::query,owner.world(),anchor,actor,true).orElseThrow());
        }
        CreditFacilities.DrawRequest atTerminal(CreditFacilities.Facility line,long amount,TerminalPolicy.Snapshot terminal) {
            var r=drawRequest(line,CreditFacilities.DrawKind.ADVANCE,amount);
            return new CreditFacilities.DrawRequest(r.world(),r.owner(),r.facility(),r.token(),r.network(),r.kind(),r.destination(),r.amountMinor(),r.facilityRevision(),r.tokenRevision(),r.destinationRevision(),r.profileRevision(),r.quote(),r.expiresAt(),store.transaction(tx->CreditFacilities.AdvanceFees.capture(tx::query,line,terminal)));
        }
        @Override public void close()throws Exception{store.close();}
    }
    @Test void issuanceReservesFiniteCashAndReplayNeverCreatesAnotherProfileOrLine()throws Exception {
        try(var f=new Fixture()) {
            long before=f.balance(FinancialAccounts.Role.CASH);var request=f.request(250000);var intent=f.intent(CreditFacilities.ISSUE,request.request());
            var registration=CreditFacilities.issuance(f.owner,request,BankProviders.NO_TIER2,f::inputs,f.time::get);
            var first=f.submit(CreditFacilities.ISSUE,registration,intent);assertEquals("CREDIT_CARD_ISSUED",first.result().code());assertEquals(first,f.submit(CreditFacilities.ISSUE,registration,intent));
            assertEquals(before-250000,f.balance(FinancialAccounts.Role.CASH));assertEquals(250000,f.balance(FinancialAccounts.Role.RESERVATION));assertEquals(0,f.cash());
            assertEquals(650,f.store.transaction(tx->CreditProfiles.find(tx::query,f.owner).orElseThrow()).score());
            assertEquals(0,f.store.transaction(tx->CreditProfiles.inquiryBuckets(tx::query,f.owner,NOW.toEpochMilli(),f.time.get())).size());
            var second=f.request(250000);assertEquals("CREDIT_CARD_ISSUED",f.submit(CreditFacilities.ISSUE,CreditFacilities.issuance(f.owner,second,BankProviders.NO_TIER2,f::inputs,f.time::get),f.intent(CreditFacilities.ISSUE,second.request())).result().code());
            var third=f.request(50000);assertEquals("CREDIT_APPLICATION_DENIED",f.submit(CreditFacilities.ISSUE,CreditFacilities.issuance(f.owner,third,BankProviders.NO_TIER2,f::inputs,f.time::get),f.intent(CreditFacilities.ISSUE,third.request())).result().code());
            assertEquals(2,f.store.transaction(tx->CreditFacilities.owned(tx::query,f.owner)).size());
        }
    }
    @Test void committedCreditCardIsCollectibleBeforeRestartAndRecoverableWithoutMaterializing() throws Exception {
        try (var f = new Fixture()) {
            var line = f.issue();
            var token = f.token(line.id());
            assertEquals(token.id(), f.store.transaction(tx -> Custody.claims(tx::query, f.actor, null, 8)).cases().getFirst().id());
            while (!Custody.reconcileBatch(f.store)) { }
            var card = f.store.transaction(tx -> Custody.find(tx::query, f.actor, token.id(), false)).orElseThrow();
            assertEquals(Custody.State.CLAIM, card.state());
            assertNull(card.deliveryAttempt());
            assertEquals(0, f.cash());
        }
    }

    @Test void advancesUseOutstandingTwentyPercentSublimitAndRepaymentRestoresReservation()throws Exception {
        try(var f=new Fixture()) {
            var line=f.issue();var request=f.drawRequest(line,CreditFacilities.DrawKind.ADVANCE,30000);var intent=f.intent(CreditFacilities.DRAW,request.request());
            var first=f.draw(request,intent);assertEquals("CREDIT_DRAWN",first.result().code());assertEquals(first,f.draw(request,intent));assertEquals(30000,f.cash());assertEquals(219700,f.balance(FinancialAccounts.Role.RESERVATION));assertEquals(30300,f.balance(FinancialAccounts.Role.RECEIVABLE));assertEquals(-300,f.balance(FinancialAccounts.Role.INCOME));
            line=f.get(line.id());var excess=f.drawRequest(line,CreditFacilities.DrawKind.ADVANCE,20001);assertEquals("CREDIT_ADVANCE_SUBLIMIT",f.draw(excess,f.intent(CreditFacilities.DRAW,excess.request())).result().code());
            var stale=f.drawRequest(line,CreditFacilities.DrawKind.ADVANCE,100);var t=f.token(line.id());var revoke=f.intent(CreditFacilities.REVOKE,CreditFacilities.revokeRequest(f.owner.world(),f.actor,t.id(),t.revision()));assertEquals("CREDIT_TOKEN_REVOKED",f.submit(CreditFacilities.REVOKE,CreditFacilities.revocation(f.owner,t.id(),t.revision()),revoke).result().code());assertEquals("CREDIT_CARD_STALE",f.draw(stale,f.intent(CreditFacilities.DRAW,stale.request())).result().code());
            long ledger=f.store.transaction(tx->WealthValuation.ledgerRevision(tx::query,f.account.value()));
            var repayment=new CreditFacilities.RepaymentRequest(f.owner.world(),f.actor,line.id(),f.account,30000,line.revision(),1,ledger,UUID.randomUUID(),f.time.get()+30000);
            var pay=f.intent(CreditFacilities.REPAY,repayment.request());assertEquals("CREDIT_REPAID",f.submit(CreditFacilities.REPAY,CreditFacilities.repayment(f.owner,repayment,f.time::get),pay).result().code());assertEquals(0,f.cash());assertEquals(249700,f.balance(FinancialAccounts.Role.RESERVATION));assertEquals(300,f.get(line.id()).owedMinor());
        }
    }
    @Test void differentBankAtmAdvanceFinancesBothFeesAndReplayCannotChargeAgain() throws Exception {
        try(var f=new Fixture()) {
            var line=f.issue();var atmBank=new InstitutionCatalog.InstitutionId("economy:bank/orchard_direct");
            var terminal=f.terminal(atmBank,TerminalPolicy.Kind.ATM);
            var request=f.atTerminal(line,10000,terminal);var intent=f.intent(CreditFacilities.DRAW,request.request());
            long issuerCash=f.balance(FinancialAccounts.Role.CASH);
            var atmCash=FinancialAccounts.id(f.owner.world(),atmBank,FinancialAccounts.Role.CASH,EconomyCurrency.CAD);
            long before=f.store.transaction(tx->Settlement.posted(tx::query,atmCash,EconomyCurrency.CAD));
            var result=f.draw(request,intent);assertEquals("CREDIT_DRAWN",result.result().code());assertEquals(result,f.draw(request,intent));
            assertEquals(10000,f.cash());assertEquals(10550,f.get(line.id()).advance().principalMinor());
            assertEquals(239450,f.balance(FinancialAccounts.Role.RESERVATION));assertEquals(issuerCash+300,f.balance(FinancialAccounts.Role.CASH));
            long after=f.store.transaction(tx->Settlement.posted(tx::query,atmCash,EconomyCurrency.CAD));assertEquals(before+250,after);
            var detail=f.store.transaction(tx->HistoryDetails.read(tx::query,f.owner,result.transactionId().value(),Long.MAX_VALUE)).orElseThrow();
            assertEquals(550,detail.fee().amount().minorUnits());
        }
    }
    @Test void sameBankAdvanceFeesCountTowardTwentyPercentCapAndTamperedQuoteRejects() throws Exception {
        try(var f=new Fixture()) {
            var line=f.issue();var terminal=f.terminal(BANK,TerminalPolicy.Kind.ATM);
            var excess=f.atTerminal(line,50000,terminal);
            assertEquals("CREDIT_ADVANCE_SUBLIMIT",f.draw(excess,f.intent(CreditFacilities.DRAW,excess.request())).result().code());
            assertEquals(0,f.cash());assertEquals(0,f.balance(FinancialAccounts.Role.INCOME));
            var good=f.atTerminal(line,49700,terminal);
            assertEquals(0,good.fees().atmMinor());
            var fee=good.fees();var forged=new CreditFacilities.DrawRequest(good.world(),good.owner(),good.facility(),good.token(),good.network(),good.kind(),good.destination(),good.amountMinor(),good.facilityRevision(),good.tokenRevision(),good.destinationRevision(),good.profileRevision(),good.quote(),good.expiresAt(),new CreditFacilities.AdvanceFees(fee.terminal(),fee.generation(),fee.revision(),fee.bank(),0,0,fee.fxPublication()));
            assertEquals("CREDIT_FEE_STALE",f.draw(forged,f.intent(CreditFacilities.DRAW,forged.request())).result().code());
            assertEquals("CREDIT_DRAWN",f.draw(good,f.intent(CreditFacilities.DRAW,good.request())).result().code());
            assertEquals(50000,f.get(line.id()).advance().principalMinor());assertEquals(49700,f.cash());
        }
    }
    @Test void foreignBankTellerAdvanceHasOnlyIssuerFee() throws Exception {
        try(var f=new Fixture()) {
            var line=f.issue();var terminal=f.terminal(new InstitutionCatalog.InstitutionId("economy:bank/orchard_direct"),TerminalPolicy.Kind.TELLER);
            var request=f.atTerminal(line,10000,terminal);
            assertEquals(0,request.fees().atmMinor());
            assertEquals("CREDIT_DRAWN",f.draw(request,f.intent(CreditFacilities.DRAW,request.request())).result().code());
            assertEquals(10300,f.get(line.id()).owedMinor());assertEquals(10000,f.cash());
        }
    }
    @Test void explicitIncreaseFundsOnlyAdditionalCommitmentAndCannotResetTheStarter()throws Exception {
        try(var f=new Fixture()) {
            var line=f.issue();long initialCash=f.balance(FinancialAccounts.Role.CASH);
            var proof=BranchFixtures.proof(new BranchAdmission.Context(f.owner.world(),UUID.randomUUID(),f.actor,BranchAdmission.Action.valueOf("CREDIT_INCREASE"),BANK,null,null,0,0,0));
            var request=new CreditFacilities.IncreaseRequest(proof,line.id(),500000,line.revision(),1,0,NOW.toEpochMilli());
            var intent=f.intent(CreditFacilities.INCREASE,request.request());var registration=CreditFacilities.increase(f.owner,request,BankProviders.NO_TIER2,f::inputs,f.time::get);
            var result=f.submit(CreditFacilities.INCREASE,registration,intent);assertEquals("CREDIT_LIMIT_INCREASED",result.result().code());assertEquals(result,f.submit(CreditFacilities.INCREASE,registration,intent));
            assertEquals(initialCash-250000,f.balance(FinancialAccounts.Role.CASH));assertEquals(500000,f.balance(FinancialAccounts.Role.RESERVATION));var increased=f.get(line.id());assertEquals(line.anchor(),increased.anchor());assertEquals(500000,increased.limitMinor());
            assertEquals(line.id(),f.store.transaction(tx->CreditProfiles.find(tx::query,f.owner).orElseThrow()).firstFacility());assertEquals(250000,f.store.transaction(tx->CreditProfiles.find(tx::query,f.owner).orElseThrow()).starterMinor());
        }
    }
    @Test void completedDaysStatementsAndCloseKeepImmutableInterestAndOriginalAnchor()throws Exception {
        try(var f=new Fixture()) {
            var line=f.issue();long anchor=line.anchor();var draw=f.drawRequest(line,CreditFacilities.DrawKind.PURCHASE,12300);assertEquals("CREDIT_DRAWN",f.draw(draw,f.intent(CreditFacilities.DRAW,draw.request())).result().code());
            var dayKind=new Journal.Kind("economy:test-credit","economy:day");
            for(int n=1;n<=28;n++){long at=anchor+n*CreditFacilities.DAY;var id=line.id();var intent=f.intent(dayKind,new Journal.CanonicalRequest(1,Map.of("at",Long.toString(at))));assertEquals("CREDIT_INTEREST_POSTED",f.submit(dayKind,new JournalEngine.Registration(1,(i,r)->CreditFacilities.daily(r,id,at)),intent).result().code());}
            line=f.get(line.id());assertTrue(line.purchase().interestMinor()>0);assertEquals(anchor,line.anchor());
            long close=anchor+CreditFacilities.MONTH;var id=line.id();var statementKind=new Journal.Kind("economy:test-credit","economy:statement");var intent=f.intent(statementKind,new Journal.CanonicalRequest(1,Map.of("at",Long.toString(close))));var registration=new JournalEngine.Registration(1,(i,r)->CreditFacilities.statement(r,id,close));var result=f.submit(statementKind,registration,intent);assertEquals("CREDIT_STATEMENT_CREATED",result.result().code());assertEquals(result,f.submit(statementKind,registration,intent));
            line=f.get(id);assertEquals(1,line.requirements().size());assertTrue(line.requiredMinor()>0);assertTrue(line.requiredMinor()<=line.owedMinor());assertEquals(close+14*CreditFacilities.DAY,line.requirements().getFirst().dueAt());assertEquals(0,line.purchase().periodEarnedMajor().signum());assertEquals(line.owedMinor(),f.store.transaction(tx->CreditFacilities.statements(tx::query,f.owner,id,-1,8)).statements().getFirst().statement().debtMinor());
            f.time.set(close+1);long debt=line.owedMinor();var closeIntent=f.intent(CreditFacilities.CLOSE,CreditFacilities.closeRequest(f.owner.world(),f.actor,id,line.revision()));assertEquals("CREDIT_FACILITY_CLOSED",f.submit(CreditFacilities.CLOSE,CreditFacilities.closure(f.owner,id,line.revision(),f.time::get),closeIntent).result().code());
            var closed=f.get(id);assertEquals(debt,closed.owedMinor());assertEquals(CreditFacilities.Access.CLOSED,closed.access());assertEquals(anchor,closed.anchor());assertEquals(0,f.balance(FinancialAccounts.Role.RESERVATION));
        }
    }
    @Test void closingAnUnpaidLineCannotBypassArrearsAdmission()throws Exception {
        try(var f=new Fixture()) {
            var line=f.issue();var draw=f.drawRequest(line,CreditFacilities.DrawKind.PURCHASE,12300);
            assertEquals("CREDIT_DRAWN",f.draw(draw,f.intent(CreditFacilities.DRAW,draw.request())).result().code());
            var id=line.id();long anchor=line.anchor();
            var dayKind=new Journal.Kind("economy:test-credit","economy:day");
            for(int n=1;n<=42;n++){
                long at=anchor+n*CreditFacilities.DAY;
                f.submit(dayKind,new JournalEngine.Registration(1,(i,r)->CreditFacilities.daily(r,id,at)),f.intent(dayKind,new Journal.CanonicalRequest(1,Map.of("at",Long.toString(at)))));
                if(n==28){var kind=new Journal.Kind("economy:test-credit","economy:statement");
                    f.submit(kind,new JournalEngine.Registration(1,(i,r)->CreditFacilities.statement(r,id,at)),f.intent(kind,new Journal.CanonicalRequest(1,Map.of("at",Long.toString(at)))));}
            }
            line=f.get(id);f.time.set(anchor+42*CreditFacilities.DAY+1);
            var close=f.intent(CreditFacilities.CLOSE,CreditFacilities.closeRequest(f.owner.world(),f.actor,id,line.revision()));
            assertEquals("CREDIT_FACILITY_CLOSED",f.submit(CreditFacilities.CLOSE,CreditFacilities.closure(f.owner,id,line.revision(),f.time::get),close).result().code());
            var request=f.request(50000);
            var denied=f.submit(CreditFacilities.ISSUE,CreditFacilities.issuance(f.owner,request,BankProviders.NO_TIER2,f::inputs,f.time::get),f.intent(CreditFacilities.ISSUE,request.request()));
            assertEquals("CREDIT_APPLICATION_DENIED",denied.result().code());
            assertEquals(1,f.store.transaction(tx->CreditFacilities.history(tx::query,f.owner,null,16)).size());
        }
    }

}
