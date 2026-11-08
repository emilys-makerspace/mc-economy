package ca.emilybelnavis.mcmods.economy.fx;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.credit.CreditTreasury;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.simulation.activity.*;
import ca.emilybelnavis.mcmods.economy.simulation.macro.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.MacroModel;
import ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;

/** Effective FX history published by the real stage-0 provider against SQLite. */
class EffectiveFxTest {
    @TempDir Path path;
    @Test void sameDaySnapshotsKeepOriginalIdentityDailyHistoryAndExactFreshnessAcrossRestart()throws Exception {
        EffectiveFxSnapshot first,second;FxFacts.Publication daily;
        try(var f=new Fixture(path)) {
            long a=f.origin+CurrencyPrimes.DAY_MILLIS;
            var initial=new MacroControls.Policy(UUID.randomUUID(),f.world(),1,a,MacroControls.Mode.AUTO,f.config,Map.of(),Map.of());
            var macro=MacroActivation.activate(f.store,f.clock,f.dispatcher,UUID.randomUUID(),a,initial,f.wall.instant());
            var activation=f.store.transaction(tx->CurrencyPrimes.activation(tx::query,f.world()).orElseThrow());
            daily=f.store.transaction(tx->FxService.latestDaily(tx::query));
            f.now=a+1;f.adjust(100_000);long boundary=macro.firstEffectiveMillis();f.dispatch(boundary);
            first=f.store.transaction(tx->FxService.snapshot(tx::query));assertEquals(boundary,first.effectiveMillis());assertEquals(EffectiveFxFacts.PROVIDER,first.provider());assertEquals(EffectiveFxSnapshot.Mode.AUTO,first.mode());
            long next=boundary+CurrencyPrimes.DAY_MILLIS;f.dispatch(next);second=f.store.transaction(tx->FxService.snapshot(tx::query));assertNotEquals(first.id(),second.id());assertEquals(first.publication().day(),second.publication().day());
            assertEquals(first,f.store.transaction(tx->FxService.snapshot(tx::query,first.id())));assertEquals(first,f.store.transaction(tx->FxService.at(tx::query,next-1)));assertEquals(second,f.store.transaction(tx->FxService.at(tx::query,next)));
            assertEquals(next,f.store.transaction(tx->FxService.nextEffectiveMillis(tx::query,boundary,next+1)).longValue());
            assertEquals(daily,f.store.transaction(tx->FxService.latestDaily(tx::query)));assertEquals(daily,f.store.transaction(tx->FxService.publication(tx::query,daily.day())));assertEquals(second.id(),f.store.transaction(tx->FxService.latest(tx::query)).updateId());
            var pair=new FxQuotes.Pair(CAD,USD);var history=f.store.transaction(tx->FxPairHistory.intradayPage(tx::query,f.world(),pair,null,1));assertEquals(second.id(),history.entries().getFirst().id());assertNotNull(history.next());var older=f.store.transaction(tx->FxPairHistory.intradayPage(tx::query,f.world(),pair,history.next(),1));assertEquals(first.id(),older.entries().getFirst().id());assertEquals(1,f.store.transaction(tx->FxPairHistory.page(tx::query,f.world(),pair,null,16)).days().size());
            long legacyExpired=daily.day().atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(48)).toEpochMilli()+1;
            assertEquals(second.id(),f.store.transaction(tx->CreditTreasury.publication(tx::query,legacyExpired)).updateId(),"Live treasury age uses the intraday instant, not display midnight");
            assertNotNull(f.store.transaction(tx->CreditTreasury.publication(tx::query,second.effectiveMillis()+172_800_000L)));assertNull(f.store.transaction(tx->CreditTreasury.publication(tx::query,second.effectiveMillis()+172_800_001L)));
            var book=new FxQuotes(f.world());var issued=book.issue(f.actor,pair,FxQuotes.Side.EXACT_DEBIT,new Money(CAD,100),second,Instant.ofEpochMilli(legacyExpired),Duration.ofSeconds(60));assertEquals(issued,book.requireFresh(issued.id(),f.actor,f.world(),second,1,Instant.ofEpochMilli(legacyExpired+1)));
            assertThrows(IllegalArgumentException.class,()->book.issue(f.actor,pair,FxQuotes.Side.EXACT_DEBIT,new Money(CAD,100),second,Instant.ofEpochMilli(second.effectiveMillis()+172_800_000L),Duration.ofSeconds(60)));
            assertEquals(activation.fxSnapshot(),daily.updateId());
        }
        var reopened=new SqliteStorage();reopened.initialize(path.toRealPath());try{assertEquals(first,reopened.transaction(tx->FxService.snapshot(tx::query,first.id())));assertEquals(second,reopened.transaction(tx->FxService.snapshot(tx::query)));assertEquals(daily.encode(),reopened.transaction(tx->FxService.latestDaily(tx::query)).encode());}finally{reopened.close();}
    }
    private static final class Fixture implements AutoCloseable {
        final SqliteStorage store=new SqliteStorage();final PlayerId actor=new PlayerId(UUID.randomUUID());final AccountId account=new AccountId(UUID.randomUUID());
        final MacroModel.Config config=MacroModel.Config.defaults(UUID.randomUUID(),1);
        long now=Instant.parse("2026-11-07T12:00:00Z").toEpochMilli();final long origin=now;
        final Clock wall=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}};
        final DeadlineDispatcher dispatcher;
        final EconomicClock clock;
        Fixture(Path path)throws Exception{
            store.initialize(path.toRealPath());
            var providers=List.<DeadlineDispatcher.Provider>of(new MacroProvider());
            dispatcher=new DeadlineDispatcher(store,wall,providers,16);
            dispatcher.prepare();
            submit(FxService.OPEN,FxService.opening(wall),new Journal.CanonicalRequest(1,Map.of("currency","CAD")));
            clock=new EconomicClock(store,wall,List.of(FxService.definition()),Map.of(FxService.SYSTEM,FxService.deferred()),DeadlineDispatcher.implementations(providers));
            clock.prepare();
            store.transaction(tx->tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",List.of(account.value().toString(),actor.value().toString())));
            var activity=new ActivityRuntime(store,wall);
            store.commitObserver((i,r,c)->activity.observe(i,r,c,now));
            for(int n=0;n<100;n++)if(activity.prepare())break;
            assertTrue(store.<Boolean>transaction(tx->ActivityStore.ready(tx::query)));
        }
        WorldId world(){return store.snapshot().worldId();}
        void dispatch(long time){
            now=time;
            assertTrue(clock.runBatch(16).ready());
            for(int n=0;n<40;n++){dispatcher.advance();if(dispatcher.progress().ready())return;}
            fail("Macro event did not complete");
        }
        void adjust(long amount){var request=new SameCurrency.Adjustment(actor,new SameCurrency.Revision(account,1),new Money(CAD,amount));submit(SameCurrency.CREDIT,SameCurrency.registrations(world(),(i,instruction,r)->true).get(SameCurrency.CREDIT),request.request());}
        void submit(Journal.Kind kind,JournalEngine.Registration registration,Journal.CanonicalRequest request){UUID id=UUID.randomUUID();var result=new JournalEngine(store,(a,k)->true,Map.of(kind,registration)).submit(new Journal.Intent(new OperationId(id),new RequestId(id),actor,kind,request,null,null));assertEquals(Journal.Status.COMMITTED,result.status(),result.result().code());}
        public void close()throws Exception{store.close();}
    }
}
