package ca.emilybelnavis.mcmods.economy.fx;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.credit.CreditTreasury;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.simulation.activity.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.MacroModel;
import ca.emilybelnavis.mcmods.economy.simulation.plan.ActivationModelPlans;
import ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes;
import ca.emilybelnavis.mcmods.economy.simulation.window.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;

/** Real SQLite/stage-0 child witness. The fixture provider is never registered in production. */
class EffectiveFxTest {
    @TempDir Path path;
    @Test void sameDaySnapshotsKeepOriginalIdentityDailyHistoryAndExactFreshnessAcrossRestart()throws Exception {
        EffectiveFxSnapshot first,second;FxFacts.Publication daily;
        try(var f=new Fixture(path)) {
            long a=f.origin+CurrencyPrimes.DAY_MILLIS;var activation=CurrencyPrimes.activate(f.store,f.activation,a,f.config,f.wall.instant());daily=f.store.transaction(tx->FxService.latestDaily(tx::query));
            f.close(a);f.now=a+1;f.adjust(100_000);long boundary=a+CurrencyPrimes.DAY_MILLIS;f.plan(boundary);f.dispatch(boundary);
            first=f.store.transaction(tx->FxService.snapshot(tx::query));assertEquals(boundary,first.effectiveMillis());assertEquals(EffectiveFxFacts.PROVIDER,first.provider());assertEquals(EffectiveFxSnapshot.Mode.AUTO,first.mode());
            long next=boundary+CurrencyPrimes.DAY_MILLIS;f.plan(next);f.dispatch(next);second=f.store.transaction(tx->FxService.snapshot(tx::query));assertNotEquals(first.id(),second.id());assertEquals(first.publication().day(),second.publication().day());
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
        final SqliteStorage store=new SqliteStorage();final PlayerId actor=new PlayerId(UUID.randomUUID());final AccountId account=new AccountId(UUID.randomUUID());final UUID activation=UUID.randomUUID(),config=UUID.randomUUID();long now=Instant.parse("2026-11-07T12:00:00Z").toEpochMilli();final long origin=now;
        final Clock wall=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}};
        final DeadlineDispatcher dispatcher;
        Fixture(Path path)throws Exception{store.initialize(path.toRealPath());submit(FxService.OPEN,FxService.opening(wall),new Journal.CanonicalRequest(1,Map.of("currency","CAD")));dispatcher=new DeadlineDispatcher(store,wall,List.of(new Provider()),16);dispatcher.prepare();store.transaction(tx->{tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",List.of(account.value().toString(),actor.value().toString()));return null;});var activity=new ActivityRuntime(store,wall);store.commitObserver((i,r,c)->activity.observe(i,r,c,now));for(int n=0;n<100;n++)if(activity.prepare())break;assertTrue(store.<Boolean>transaction(tx->ActivityStore.ready(tx::query)));}
        WorldId world(){return store.snapshot().worldId();}
        UUID window(long time){return WindowFacts.stable("open|"+world().value()+"|"+time);}
        void close(long time){var a=store.transaction(tx->CurrencyPrimes.activation(tx::query,world()).orElseThrow());var open=WindowStore.open(store,time,time,config,a.fxSnapshot());for(var c:EconomyCurrency.all())WindowStore.close(store,open.id(),c);}
        void plan(long time){close(time);var a=store.transaction(tx->CurrencyPrimes.activation(tx::query,world()).orElseThrow());var prior=store.transaction(tx->FxService.at(tx::query,time-1));ActivationModelPlans.prepare(store,activation,window(time),a.reference(),prior.rates(),MacroModel.Config.defaults(config,1));for(var c:EconomyCurrency.all())CurrencyPrimes.publish(store,window(time),c,CurrencyPrimes.Mode.AUTO);CurrencyPrimes.complete(store,window(time));}
        void dispatch(long time){now=time;for(int n=0;n<10;n++){dispatcher.advance();if(dispatcher.progress().ready())return;}fail("Fixture stage-0 event did not complete");}
        void adjust(long amount){var request=new SameCurrency.Adjustment(actor,new SameCurrency.Revision(account,1),new Money(CAD,amount));submit(SameCurrency.CREDIT,SameCurrency.registrations(world(),(i,instruction,r)->true).get(SameCurrency.CREDIT),request.request());}
        void submit(Journal.Kind kind,JournalEngine.Registration registration,Journal.CanonicalRequest request){UUID id=UUID.randomUUID();var result=new JournalEngine(store,(a,k)->true,Map.of(kind,registration)).submit(new Journal.Intent(new OperationId(id),new RequestId(id),actor,kind,request,null,null));assertEquals(Journal.Status.COMMITTED,result.status(),result.result().code());}
        public void close()throws Exception{store.close();}
        private final class Provider implements DeadlineDispatcher.Provider {
            public String id(){return EffectiveFxFacts.PROVIDER;}public Map<Integer,String> versions(){return Map.of(1,"effective-fx-fixture-v1");}
            public DeadlineDispatcher.Event next(EconomicClock.Queries reads,long ignored,DeadlineDispatcher.Event completed){var a=CurrencyPrimes.activation(reads::query,world());if(a.isEmpty())return null;long time=completed==null?a.get().firstEventMillis():completed.effectiveMillis()+CurrencyPrimes.DAY_MILLIS;if(ActivationModelPlans.read(reads::query,window(time))==null)return null;return new DeadlineDispatcher.Event(UUID.nameUUIDFromBytes(("effective-fx-fixture-event|"+world().value()+"|"+time).getBytes(StandardCharsets.UTF_8)),id(),time,0,1);}
            public List<String> targets(DeadlineDispatcher.Event event,String cursor,int limit,EconomicClock.Queries reads){return cursor.isEmpty()?List.of("20-fx"):List.of();}
            public EconomicClock.Output prepare(DeadlineDispatcher.Event event,String target,EconomicClock.Queries reads){UUID operation=UUID.nameUUIDFromBytes(("operation|"+world().value()+"|"+event.id()+"|target|"+target).getBytes(StandardCharsets.UTF_8));var commit=EffectiveFxStore.prepare(reads::query,world(),operation,id(),event.id(),window(event.effectiveMillis()),EffectiveFxSnapshot.Mode.AUTO);return new EconomicClock.Output(commit.result().payload(),commit.projections(),commit.records());}
        }
    }
}
