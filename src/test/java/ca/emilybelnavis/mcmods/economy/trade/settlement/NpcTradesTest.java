package ca.emilybelnavis.mcmods.economy.trade.settlement;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.DeadlineDispatcher;
import ca.emilybelnavis.mcmods.economy.clock.EconomicClock;
import ca.emilybelnavis.mcmods.economy.simulation.macro.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.MacroModel;
import ca.emilybelnavis.mcmods.economy.simulation.plan.ActivationModelPlans;
import ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes;
import ca.emilybelnavis.mcmods.economy.simulation.window.WindowStore;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.*;
import ca.emilybelnavis.mcmods.economy.simulation.activity.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.storage.*;
import ca.emilybelnavis.mcmods.economy.trade.catalog.*;
import ca.emilybelnavis.mcmods.economy.trade.market.*;
import ca.emilybelnavis.mcmods.economy.trade.pricing.*;
import ca.emilybelnavis.mcmods.economy.trade.stock.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static ca.emilybelnavis.mcmods.economy.trade.settlement.NpcFacts.*;

/** Real services/SQLite witnesses; detached admission does not claim physical Minecraft save evidence. */
class NpcTradesTest {
    @TempDir Path path;
    @Test void sameCurrencyIssueRetireBridgeAndOriginalReplayRemainAtomicAcrossRestart()throws Exception {
        UUID original;Journal.Outcome receipt;long expected;
        try(var f=new Fixture(path)) {
            var buy=f.quote(0,f.cad);long before=f.balance(f.cad);original=buy.terms().operation();
            receipt=f.trades.settle(NpcTrades.intent(f.world(),original,f.actor));assertEquals(Journal.Status.COMMITTED,receipt.status(),receipt.result().code());
            expected=before-buy.price().minor();assertEquals(expected,f.balance(f.cad));assertEquals(0,f.bridge(CAD));
            assertEquals(buy.price().minor(),f.total(ActivityFacts.Kind.NPC_RETIRE));assertEquals(buy.price().minor(),f.total(ActivityFacts.Kind.GOODS_SALE));
            f.now+=31_000;f.allowed=false;
            assertEquals(receipt,f.trades.settle(NpcTrades.intent(f.world(),original,f.actor)),"Original outcome precedes expiry/admission callbacks");
            f.allowed=true;
            var sell=f.quote(1,f.cad);var payout=f.trades.settle(NpcTrades.intent(f.world(),sell.terms().operation(),f.actor));assertEquals(Journal.Status.COMMITTED,payout.status(),payout.result().code());
            expected+=sell.price().minor();assertEquals(expected,f.balance(f.cad));assertEquals(0,f.bridge(CAD));assertEquals(sell.price().minor(),f.total(ActivityFacts.Kind.NPC_CREATE));
            assertEquals(2,f.store.transaction(tx->tx.query("SELECT operation_id FROM npc_trade_receipts",List.of(),16)).size());
            LedgerProjection.rebuild(f.store);
            assertEquals(expected,f.balance(f.cad));assertEquals(0,f.bridge(CAD),"Posting-v4 replay cannot retain NPC float");
            for(int version=1;version<=3;version++){
                var legacy=new Journal.Record("economy:posting",version,NpcSettlement.bridge(f.world(),CAD)+"|NPC_BRIDGE|CAD|100|TRANSFER");
                assertThrows(IllegalArgumentException.class,()->{switch(legacy.version()){case 1->LedgerProjection.validatePosting(legacy.payload());case 2->LedgerProjection.validatePostingV2(legacy.payload());case 3->LedgerProjection.validatePostingV3(legacy.payload());default->throw new AssertionError();}});
            }
        }
        var store=new SqliteStorage();store.initialize(path.toRealPath());
        try {assertEquals(expected,store.transaction(tx->Settlement.posted(tx::query,UUID.nameUUIDFromBytes("npc-test-cad".getBytes(java.nio.charset.StandardCharsets.UTF_8)),CAD)).longValue());assertEquals(original,store.transaction(tx->NpcTrades.receipt(tx::query,original)).operation());}
        finally{store.close();}
    }
    @Test void foreignFeePrincipalAndStaleClockRejectionDoNotLeakMoneyOrObservations()throws Exception {
        try(var f=new Fixture(path)) {
            var stale=f.quote(0,f.usd);long before=f.balance(f.usd),goods=f.total(ActivityFacts.Kind.GOODS_SALE);
            StockStore.checkpointMarket(f.store,stale.market(),new StockFacts.TickSample(UUID.randomUUID(),1,1,1));
            var rejected=f.trades.settle(NpcTrades.intent(f.world(),stale.terms().operation(),f.actor));assertNotEquals(Journal.Status.COMMITTED,rejected.status());
            assertEquals(before,f.balance(f.usd));assertEquals(goods,f.total(ActivityFacts.Kind.GOODS_SALE));assertEquals(0,f.bridge(CAD));
            var bank=new InstitutionCatalog.InstitutionId("economy:bank/crosswind");var cash=FinancialAccounts.id(f.world(),bank,FinancialAccounts.Role.CASH,USD);long cashBefore=f.store.transaction(tx->Settlement.posted(tx::query,cash,USD));
            var buy=f.quote(0,f.usd);var outcome=f.trades.settle(NpcTrades.intent(f.world(),buy.terms().operation(),f.actor));assertEquals(Journal.Status.COMMITTED,outcome.status(),outcome.result().code());
            long fee=buy.fx().pricing().fee().minorUnits(),principal=buy.fx().conversion().debit().minorUnits();assertTrue(fee>0);
            assertEquals(before-principal-fee,f.balance(f.usd));assertEquals(principal,f.total(ActivityFacts.Kind.FX_SELL));assertEquals(buy.price().minor(),f.total(ActivityFacts.Kind.FX_BUY));assertEquals(buy.price().minor(),f.total(ActivityFacts.Kind.NPC_RETIRE));assertEquals(0,f.bridge(CAD));assertEquals(0,f.bridge(USD));
            assertEquals(cashBefore+fee,f.store.transaction(tx->Settlement.posted(tx::query,cash,USD)).longValue());
            var sell=f.quote(1,f.usd);long previous=f.balance(f.usd);var sold=f.trades.settle(NpcTrades.intent(f.world(),sell.terms().operation(),f.actor));assertEquals(Journal.Status.COMMITTED,sold.status(),sold.result().code());
            assertEquals(previous+sell.fx().conversion().credit().minorUnits(),f.balance(f.usd));assertEquals(sell.price().minor(),f.total(ActivityFacts.Kind.NPC_CREATE));assertEquals(0,f.bridge(CAD));
        }
        var reopened=new SqliteStorage();reopened.initialize(path.toRealPath());try{assertEquals(2,reopened.transaction(tx->tx.query("SELECT operation_id FROM npc_trade_receipts",List.of(),16)).size());}finally{reopened.close();}
    }
    /** A8-08's missing composite: completed merchant source through the real stage-0 basket. */
    @Test void completedMerchantActivityDrivesFrozenPrimeFxAndReplayDoesNotDuplicateIt()throws Exception {
        UUID original,window;Journal.Outcome receipt;MacroPlans.Outcome selected;long finalBalance,principal,sequence;
        try(var f=new Fixture(path)) {
            var provider=new MacroProvider();var dispatcher=new DeadlineDispatcher(f.store,f.wall,List.of(provider),16);dispatcher.prepare();
            var clock=new EconomicClock(f.store,f.wall,List.of(FxService.definition()),Map.of(FxService.SYSTEM,FxService.deferred()),DeadlineDispatcher.implementations(List.of(provider)));clock.prepare();
            var config=MacroModel.Config.defaults(UUID.randomUUID(),1);long a=f.now+ActivityFacts.DAY_MILLIS,first=a+ActivityFacts.DAY_MILLIS;
            var initial=new MacroControls.Policy(UUID.randomUUID(),f.world(),1,a,MacroControls.Mode.AUTO,config,Map.of(),Map.of());
            var activation=MacroActivation.activate(f.store,clock,dispatcher,UUID.randomUUID(),a,initial,f.wall.instant());
            var inherited=f.store.transaction(tx->CurrencyPrimes.current(tx::query,f.world(),CAD,a).orElseThrow());
            var priorFx=f.store.transaction(tx->FxService.snapshot(tx::query));
            f.now=a+1;var sale=f.quote(1,f.cad,64);original=sale.terms().operation();principal=sale.price().minor();long before=f.balance(f.cad);
            receipt=f.trades.settle(NpcTrades.intent(f.world(),original,f.actor));assertEquals(Journal.Status.COMMITTED,receipt.status(),receipt.result().code());
            finalBalance=before+principal;assertEquals(finalBalance,f.balance(f.cad));assertEquals(0,f.bridge(CAD));
            var observations=f.store.transaction(tx->tx.query("SELECT kind,minor,day_index FROM activity_observations WHERE operation_id=? ORDER BY ordinal",List.of(original.toString()),16));
            assertEquals(Set.of("GOODS_SALE","NPC_CREATE"),observations.stream().map(r->r.get("kind")).collect(java.util.stream.Collectors.toSet()));
            assertEquals(2,observations.size());for(var observation:observations){assertEquals(principal,Long.parseLong(observation.get("minor")));assertEquals(1,Long.parseLong(observation.get("day_index")));}
            long afterTrade=f.store.lastCommittedSequence();assertEquals(receipt,f.trades.settle(NpcTrades.intent(f.world(),original,f.actor)));assertEquals(afterTrade,f.store.lastCommittedSequence());
            f.now=first;assertTrue(clock.runBatch(16).ready());for(int n=0;n<27;n++)dispatcher.advance();assertTrue(dispatcher.progress().ready());
            window=MacroProvider.windowId(f.world(),first);var snapshot=f.store.transaction(tx->WindowStore.read(tx::query,window,CAD));assertEquals(1,snapshot.days().size());
            var day=snapshot.days().getFirst();assertEquals(principal,day.amounts().get(ActivityFacts.Kind.GOODS_SALE).minor());assertEquals(1,day.amounts().get(ActivityFacts.Kind.GOODS_SALE).count());assertEquals(principal,day.amounts().get(ActivityFacts.Kind.NPC_CREATE).minor());
            var plan=f.store.transaction(tx->ActivationModelPlans.read(tx::query,window));selected=f.store.transaction(tx->MacroPlans.read(tx::query,window));
            assertEquals(java.math.BigInteger.valueOf(principal),plan.input().windows().get(CAD).totals().get(ActivityFacts.Kind.GOODS_SALE));
            assertEquals(plan.input().inputHash(),selected.inputHash());assertEquals(plan.output().hash(),selected.automaticHash());
            assertTrue(selected.primes().get(CAD).compareTo(inherited.primeApr())>0,"Completed merchant payout raises observed CAD money pressure");
            assertTrue(selected.fx().get(USD).compareTo(priorFx.rates().get(USD))>0,"Reference-currency activity changes the coherent relative vector");
            assertTrue(MacroModel.validRounded(plan.input(),selected.fx()));
            var publishedFx=f.store.transaction(tx->FxService.snapshot(tx::query));assertEquals(first,publishedFx.effectiveMillis());assertEquals(window,publishedFx.window());assertEquals(selected.fx(),publishedFx.rates());
            for(var currency:EconomyCurrency.all()){var prime=f.store.transaction(tx->CurrencyPrimes.current(tx::query,f.world(),currency,first).orElseThrow());assertEquals(first,prime.effectiveMillis());assertEquals(window,prime.window());assertEquals(0,selected.primes().get(currency).compareTo(prime.primeApr()));}
            assertTrue(f.store.<Boolean>transaction(tx->CurrencyPrimes.ready(tx::query,f.world(),first)));assertEquals(selected.hash(),f.store.transaction(tx->MacroPlans.completion(tx::query,window)).selectedHash());
            assertEquals(activation.successorEvent(),publishedFx.event());assertEquals(finalBalance,f.balance(f.cad),"Rate publication does not move customer money");
            f.allowed=false;assertEquals(receipt,f.trades.settle(NpcTrades.intent(f.world(),original,f.actor)),"Original lookup precedes expired quote/current FX/admission");sequence=f.store.lastCommittedSequence();
        }
        var reopened=new SqliteStorage();reopened.initialize(path.toRealPath());try{
            var world=reopened.snapshot().worldId();var saved=reopened.transaction(tx->NpcTrades.receipt(tx::query,original));
            var replay=new NpcTrades(reopened,null,Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"),ZoneOffset.UTC),(phase,terms,quote,reads)->false);
            assertEquals(receipt,replay.settle(NpcTrades.intent(world,original,saved.actor())));assertEquals(sequence,reopened.lastCommittedSequence());
            assertEquals(selected,reopened.transaction(tx->MacroPlans.read(tx::query,window)));assertEquals(principal,reopened.transaction(tx->WindowStore.read(tx::query,window,CAD)).days().getFirst().amounts().get(ActivityFacts.Kind.GOODS_SALE).minor());
            assertEquals(2,reopened.transaction(tx->tx.query("SELECT id FROM activity_observations WHERE operation_id=?",List.of(original.toString()),16)).size());
            assertEquals(finalBalance,reopened.transaction(tx->Settlement.posted(tx::query,UUID.nameUUIDFromBytes("npc-test-cad".getBytes(java.nio.charset.StandardCharsets.UTF_8)),CAD)).longValue());
        }finally{reopened.close();}
    }
    private static final class Fixture implements AutoCloseable {
        final SqliteStorage store=new SqliteStorage();final PlayerId actor=new PlayerId(UUID.randomUUID());final UUID trader=UUID.randomUUID(),source=UUID.randomUUID(),epoch=UUID.randomUUID();
        final AccountId cad=new AccountId(UUID.nameUUIDFromBytes("npc-test-cad".getBytes(java.nio.charset.StandardCharsets.UTF_8))),usd=new AccountId(UUID.randomUUID());long now=Instant.parse("2026-11-07T12:00:00Z").toEpochMilli();boolean allowed=true;
        final Clock wall=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}};
        final List<TradeCatalog.Offer> offers=List.of(new TradeCatalog.Offer(0,Pricing.Side.BUY,new TradeCatalog.Goods("minecraft:bread",TradeCatalog.Match.EXACT_COMPONENTS,"{}"),1,1,List.of(),16,2),new TradeCatalog.Offer(1,Pricing.Side.SELL,new TradeCatalog.Goods("minecraft:wheat",TradeCatalog.Match.REQUIRED_COMPONENTS,"{}"),20,1,List.of(),16,2));
        final NpcTrades trades;
        Fixture(Path path)throws Exception {
            store.initialize(path.toRealPath());submit(FxService.OPEN,FxService.opening(wall),request("currency","CAD"));
            var capital=new InstitutionCapital(store,new SystemActorId(UUID.randomUUID()),CapitalPosting.poster());for(int n=0;n<26;n++)assertEquals(Journal.Status.COMMITTED,capital.advance(wall.instant()).status());
            store.transaction(tx->{for(var a:List.of(cad,usd)){tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK',?,'OPEN',1,?)",List.of(a.value().toString(),actor.value().toString(),a.equals(cad)?"CAD":"USD",a.equals(cad)?"1":"0"));tx.execute("INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",List.of(a.value().toString(),a.equals(cad)?"economy:bank/crownstone":"economy:bank/crosswind"));}return null;});
            for(var a:List.of(cad,usd)){EconomyCurrency c=a.equals(cad)?CAD:USD;var legs=List.of(new PostingBatch.Leg(Settlement.issuanceAccount(c),PostingBatch.Role.ISSUANCE,c,new Money(c,-1_000_000)),new PostingBatch.Leg(a.value(),PostingBatch.Role.ACCOUNT,c,new Money(c,1_000_000)));var kind=new Journal.Kind("fixture:npc","fixture:npc-fund");submit(kind,Settlement.registrationMinor((i,l,r)->PostingBatch.Authority.ISSUE),Settlement.minorRequest(legs));}
            new DeadlineDispatcher(store,wall,List.of(),16).prepare();var activity=new ActivityRuntime(store,wall);store.commitObserver((i,r,c)->activity.observe(i,r,c,now));for(int turn=0;turn<100;turn++)if(activity.prepare())break;
            assertTrue(store.<Boolean>transaction(tx->ActivityStore.ready(tx::query)));
            var position=new MarketFacts.Position("minecraft:overworld",0,64,0);for(int turn=0;turn<100;turn++)if(!MarketStore.resolve(store,trader,position,position,CAD,true).pending())break;
            CatalogStore.capture(store,new TradeCatalog.Capture(trader,1,offers));StockStore.install(store,trader,1,false);
            trades=new NpcTrades(store,new NpcAccountFx(new FxQuotes(world()),new ServicePricing(new WealthValuation())),wall,(phase,terms,quote,reads)->allowed);
        }
        WorldId world(){return store.snapshot().worldId();}
        Quote quote(int ordinal,AccountId account){return quote(ordinal,account,1);}
        Quote quote(int ordinal,AccountId account,int executions){var offer=offers.get(ordinal);UUID operation=UUID.randomUUID(),input=offer.side()==Pricing.Side.SELL?UUID.randomUUID():null,output=offer.side()==Pricing.Side.BUY?UUID.randomUUID():null;
            var publication=store.transaction(tx->FxService.latest(tx::query));var priceSource=new TradePriceSource.EmeraldV1(source,1,Rational.ONE,publication.updateId(),CAD,Rational.ONE);var compared=CatalogPricing.compare(offer,List.of(offer),true,priceSource);
            var terms=new Terms(operation,actor,UUID.randomUUID(),trader,1,ordinal,executions,account,epoch,priceSource,Rational.ZERO,1,"trusted-reputation",compared.comparisons(),input,input==null?"":NpcTrades.inputItems(new TradeCatalog.Offer(offer.ordinal(),offer.side(),new TradeCatalog.Goods(offer.goods().item(),TradeCatalog.Match.EXACT_COMPONENTS,offer.goods().components()),offer.goodsCount(),offer.emeraldCount(),offer.secondary(),offer.originalMaxUses(),offer.villagerXp()),executions),output,"capacity-reservation:"+operation,1);
            if(input!=null){var custodyTerms=new Custody.Terms(actor,terms.exactInputItems(),NpcTrades.inputSource(terms),NpcTrades.inputDestination(terms),NpcTrades.financialIntent(world(),operation,actor));var reserve=new Journal.Intent(new OperationId(input),new RequestId(input),actor,Custody.Action.RESERVE.kind(),Custody.reserveRequest(custodyTerms),null,null);submit(Custody.Action.RESERVE.kind(),Custody.registration(Custody.Action.RESERVE,(a,i,c,r)->true,(c,e,r)->true,null),reserve);
                var evidence=new Custody.Evidence(Custody.EvidenceKind.RESERVATION_SAVED,"independent-fixture-save",NpcTrades.inputSource(terms),"saved-token");submit(Custody.Action.RESERVATION_SAVED.kind(),Custody.registration(Custody.Action.RESERVATION_SAVED,(a,i,c,r)->true,(c,e,r)->true,null),Custody.request(input,null,evidence,"fixture witnessed goods"));}
            return trades.quote(terms);
        }
        long balance(AccountId id){return store.transaction(tx->Settlement.posted(tx::query,id.value(),id.equals(cad)?CAD:USD));}
        long bridge(EconomyCurrency c){return store.transaction(tx->Settlement.posted(tx::query,NpcSettlement.bridge(world(),c),c));}
        long total(ActivityFacts.Kind kind){return store.transaction(tx->Long.parseLong(tx.query("SELECT COALESCE(SUM(minor),0) AS total FROM activity_day_counters WHERE kind=?",List.of(kind.name()),1).getFirst().get("total")));}
        Journal.CanonicalRequest request(String key,String value){return new Journal.CanonicalRequest(1,Map.of(key,value));}
        void submit(Journal.Kind kind,JournalEngine.Registration reg,Journal.CanonicalRequest request){submit(kind,reg,new Journal.Intent(new OperationId(UUID.randomUUID()),new RequestId(UUID.randomUUID()),actor,kind,request,null,null));}
        void submit(Journal.Kind kind,JournalEngine.Registration reg,Journal.Intent intent){var outcome=new JournalEngine(store,(a,k)->true,Map.of(kind,reg)).submit(intent);assertEquals(Journal.Status.COMMITTED,outcome.status(),outcome.result().code());}
        public void close()throws Exception{store.close();}
    }
}
