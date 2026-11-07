package ca.emilybelnavis.mcmods.economy.trade.cash;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.DeadlineDispatcher;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.*;
import ca.emilybelnavis.mcmods.economy.simulation.activity.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import ca.emilybelnavis.mcmods.economy.trade.catalog.*;
import ca.emilybelnavis.mcmods.economy.trade.market.*;
import ca.emilybelnavis.mcmods.economy.trade.pricing.*;
import ca.emilybelnavis.mcmods.economy.trade.stock.StockStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static ca.emilybelnavis.mcmods.economy.trade.cash.NpcCashFacts.*;
import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;

/** Real SQLite quantity/custody tests; detached fixture witnesses do not claim a Minecraft save. */
class NpcCashTradesTest {
    @TempDir Path path;
    @Test void markedCashProducesExactProtectedChangeAndOriginalReplayNeverConsumesTwice()throws Exception {
        UUID original,lot;long goods;
        try(var f=new Fixture(path)) {
            var terms=f.terms(false);var q=f.trades.quote(terms);assertTrue(q.cashOutputMinor()>0);assertEquals(500,q.inputCashMinor());assertEquals(500-q.pricing().price().minor(),q.cashOutputMinor());
            assertEquals(1,f.store.transaction(tx->tx.query("SELECT lot_id FROM custody_cash_reservations WHERE custody_id=? AND state='ACTIVE'",List.of(terms.inputCustody().toString()),24)).size());
            f.saved(terms);var receipt=f.trades.settle(NpcCashTrades.intent(f.world(),terms.operation(),f.actor));assertEquals(Journal.Status.COMMITTED,receipt.status(),receipt.result().code());original=terms.operation();lot=f.lot;goods=q.pricing().price().minor();
            assertEquals(1,f.store.transaction(tx->CashIssuance.find(tx::query,f.world(),f.lot).orElseThrow()).remaining());
            long value=0;for(var output:q.outputLots()){value+=Math.multiplyExact(CashDenominations.require(output.item()).minorUnits(),output.issued());assertEquals(output,f.store.transaction(tx->CashIssuance.find(tx::query,f.world(),output.id()).orElseThrow()));}
            assertEquals(q.cashOutputMinor(),value);assertEquals(0,f.store.transaction(tx->Settlement.posted(tx::query,NpcSettlement.bridge(f.world(),USD),USD)).longValue());
            assertEquals(goods,f.total(ActivityFacts.Kind.NPC_RETIRE));assertEquals(0,f.total(ActivityFacts.Kind.OPERATOR_CASH_CREATE));
            f.now+=31_000;f.allowed=false;assertEquals(receipt,f.trades.settle(NpcCashTrades.intent(f.world(),original,f.actor)));assertEquals(1,f.store.transaction(tx->CashIssuance.find(tx::query,f.world(),f.lot).orElseThrow()).remaining());
        }
        var reopened=new SqliteStorage();reopened.initialize(path.toRealPath());try{var world=reopened.snapshot().worldId();assertEquals(1,reopened.transaction(tx->CashIssuance.find(tx::query,world,lot).orElseThrow()).remaining());assertEquals(goods,reopened.transaction(tx->NpcCashTrades.receipt(tx::query,original)).goodsPrincipal());}finally{reopened.close();}
    }
    @Test void capacityFailureLeavesOriginalsAndExpiredHeldInputsRequireAnOriginalProtectedReturn()throws Exception {
        try(var f=new Fixture(path)) {
            var denied=f.terms(false);f.allowed=false;assertThrows(IllegalArgumentException.class,()->f.trades.quote(denied));assertEquals(2,f.store.transaction(tx->CashIssuance.find(tx::query,f.world(),f.lot).orElseThrow()).remaining());assertTrue(f.store.transaction(tx->tx.query("SELECT quote_id FROM npc_cash_receipts",List.of(),16)).isEmpty());
            f.allowed=true;var terms=f.terms(false);var q=f.trades.quote(terms);f.saved(terms);f.now+=31_000;
            var proof=new Custody.Evidence(Custody.EvidenceKind.UNDELIVERED,"independent-saved-HELD",NpcCashTrades.inputSource(terms),"saved-held-token");
            var claim=f.trades.returnInputs(q.id(),true,proof);assertEquals(Custody.State.CLAIM,claim.state());assertEquals(terms.inputCustody(),claim.reservationOperation());assertNotEquals(terms.operation(),claim.financialOperation());
            assertEquals(Custody.State.QUARANTINED,f.store.transaction(tx->Custody.find(tx::query,f.actor,terms.inputCustody(),false).orElseThrow()).state());assertEquals(terms.exactInputItems(),claim.terms().items());
            assertEquals("ACTIVE",f.store.transaction(tx->tx.query("SELECT state FROM custody_cash_reservations WHERE custody_id=? AND lot_id=?",List.of(claim.id().toString(),f.lot.toString()),1)).getFirst().get("state"));
            assertEquals(2,f.store.transaction(tx->CashIssuance.find(tx::query,f.world(),f.lot).orElseThrow()).remaining());assertEquals(Journal.Status.DURABLY_REJECTED,f.trades.settle(NpcCashTrades.intent(f.world(),terms.operation(),f.actor)).status());assertEquals(0,f.total(ActivityFacts.Kind.GOODS_SALE));
        }
        var reopened=new SqliteStorage();reopened.initialize(path.toRealPath());try{assertEquals(1,reopened.transaction(tx->tx.query("SELECT operation_id FROM npc_cash_returns",List.of(),16)).size());}finally{reopened.close();}
    }
    @Test void unbankedForeignUnmarkedCashCapturesTierOneFeeAndOperatorOriginOnce()throws Exception {
        UUID original;long goods,principal,fee;
        try(var f=new Fixture(path)) {
            var terms=f.terms(true,EUR);var q=f.trades.quote(terms);assertNotNull(q.fx());assertEquals(ServicePricing.Tier.TIER1,q.fx().pricing().tier());assertTrue(q.fx().pricing().fee().minorUnits()>0);assertEquals(500,q.operatorCashMinor());
            assertTrue(f.store.transaction(tx->tx.query("SELECT account_id FROM account_catalog WHERE owner_id=?",List.of(f.actor.value().toString()),4)).isEmpty(),"Cash rail does not require a bank account");
            f.saved(terms);var outcome=f.trades.settle(NpcCashTrades.intent(f.world(),terms.operation(),f.actor));assertEquals(Journal.Status.COMMITTED,outcome.status(),outcome.result().code());original=terms.operation();goods=q.pricing().price().minor();principal=q.fx().conversion().debit().minorUnits();fee=q.fx().pricing().fee().minorUnits();
            assertEquals(500-principal-fee,q.cashOutputMinor());assertEquals(500,f.total(ActivityFacts.Kind.OPERATOR_CASH_CREATE));assertEquals(goods,f.total(ActivityFacts.Kind.NPC_RETIRE));assertEquals(principal,f.total(ActivityFacts.Kind.FX_SELL));assertEquals(goods,f.total(ActivityFacts.Kind.FX_BUY));assertEquals(goods,f.total(ActivityFacts.Kind.GOODS_SALE));
            for(var c:List.of(EUR,USD))assertEquals(0,f.store.transaction(tx->Settlement.posted(tx::query,NpcSettlement.bridge(f.world(),c),c)).longValue());
            assertEquals(outcome,f.trades.settle(NpcCashTrades.intent(f.world(),terms.operation(),f.actor)));assertEquals(500,f.total(ActivityFacts.Kind.OPERATOR_CASH_CREATE));
        }
        var reopened=new SqliteStorage();reopened.initialize(path.toRealPath());try{var receipt=reopened.transaction(tx->NpcCashTrades.receipt(tx::query,original));assertEquals(principal,receipt.fxSourcePrincipal());assertEquals(goods,receipt.goodsPrincipal());assertEquals(fee,receipt.feeMinor());assertEquals(500,receipt.operatorCashMinor());}finally{reopened.close();}
    }
    private static final class Fixture implements AutoCloseable {
        final SqliteStorage store=new SqliteStorage();final PlayerId actor=new PlayerId(UUID.randomUUID());final UUID trader=UUID.randomUUID(),lot=UUID.randomUUID(),source=UUID.randomUUID(),epoch=UUID.randomUUID();long now=Instant.parse("2026-11-07T12:00:00Z").toEpochMilli();boolean allowed=true;
        final Clock wall=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}};
        final TradeCatalog.Offer offer=new TradeCatalog.Offer(0,Pricing.Side.BUY,new TradeCatalog.Goods("minecraft:bread",TradeCatalog.Match.EXACT_COMPONENTS,"{}"),1,1,List.of(),16,2);final NpcCashTrades trades;
        Fixture(Path path)throws Exception {store.initialize(path.toRealPath());var opening=FxService.opening(wall);var registration=new JournalEngine.Registration(1,(i,r)->{var decision=opening.handler().prepare(i,r);if(!(decision instanceof JournalEngine.Commit base))return decision;return CashIssuance.composeIssuance(world(),List.of(new CashIssuance.Lot(world(),lot,"economy:usd_bill_5",2,2,CashIssuance.State.ACTIVE,1,"fixture-original-cash")),base,r);});submit(FxService.OPEN,registration,new Journal.CanonicalRequest(1,Map.of("currency","CAD")));
            var capital=new InstitutionCapital(store,new SystemActorId(UUID.randomUUID()),CapitalPosting.poster());for(int n=0;n<26;n++)assertEquals(Journal.Status.COMMITTED,capital.advance(wall.instant()).status());
            new DeadlineDispatcher(store,wall,List.of(),16).prepare();var activity=new ActivityRuntime(store,wall);store.commitObserver((i,r,c)->activity.observe(i,r,c,now));for(int n=0;n<100;n++)if(activity.prepare())break;assertTrue(store.<Boolean>transaction(tx->ActivityStore.ready(tx::query)));
            var position=new MarketFacts.Position("minecraft:overworld",0,64,0);for(int n=0;n<100;n++)if(!MarketStore.resolve(store,trader,position,position,USD,true).pending())break;CatalogStore.capture(store,new TradeCatalog.Capture(trader,1,List.of(offer)));StockStore.install(store,trader,1,false);
            trades=new NpcCashTrades(store,new NpcCashFx(new FxQuotes(world()),new ServicePricing(new WealthValuation())),wall,(phase,terms,quote,reads)->allowed);
        }
        WorldId world(){return store.snapshot().worldId();}
        Terms terms(boolean unmarked){return terms(unmarked,USD);}
        Terms terms(boolean unmarked,EconomyCurrency selected){var publication=store.transaction(tx->FxService.latest(tx::query));var marketCad=Rational.of(publication.rates().get(USD)).divide(Rational.of(publication.rates().get(CAD)));var priceSource=new TradePriceSource.EmeraldV1(source,1,marketCad,publication.updateId(),USD,marketCad);var compared=CatalogPricing.compare(offer,List.of(offer),true,priceSource);UUID original=UUID.randomUUID(),input=UUID.randomUUID();var slot=new Deposits.Slot(0,new CashIssuance.Claim(world(),unmarked?UUID.randomUUID():lot,selected==EUR?"economy:eur_bill_5":"economy:usd_bill_5",1),"exact-saved-full-components",unmarked);
            var terms=new Terms(original,actor,UUID.randomUUID(),trader,1,0,1,selected,List.of(slot),epoch,priceSource,Rational.ZERO,1,"authoritative-reputation",compared.comparisons(),input,"fixture-exact-input:"+original,UUID.randomUUID(),UUID.randomUUID(),"combined-capacity:"+original,1);
            var custodyTerms=new Custody.Terms(actor,terms.exactInputItems(),NpcCashTrades.inputSource(terms),NpcCashTrades.inputDestination(terms),NpcCashTrades.financialIntent(world(),original,actor));var intent=new Journal.Intent(new OperationId(input),new RequestId(input),actor,Custody.Action.RESERVE.kind(),Custody.reserveRequest(custodyTerms),null,null);submit(Custody.Action.RESERVE.kind(),Custody.registration(Custody.Action.RESERVE,(a,i,c,r)->true,(c,e,r)->true,null),intent);return terms;
        }
        void saved(Terms terms){var evidence=new Custody.Evidence(Custody.EvidenceKind.RESERVATION_SAVED,"independent-fixture-save",NpcCashTrades.inputSource(terms),"save-token");submit(Custody.Action.RESERVATION_SAVED.kind(),Custody.registration(Custody.Action.RESERVATION_SAVED,(a,i,c,r)->true,(c,e,r)->true,null),Custody.request(terms.inputCustody(),null,evidence,"fixture saved originals"));}
        void submit(Journal.Kind kind,JournalEngine.Registration reg,Journal.CanonicalRequest request){submit(kind,reg,new Journal.Intent(new OperationId(UUID.randomUUID()),new RequestId(UUID.randomUUID()),actor,kind,request,null,null));}
        void submit(Journal.Kind kind,JournalEngine.Registration reg,Journal.Intent intent){var outcome=new JournalEngine(store,(a,k)->true,Map.of(kind,reg)).submit(intent);assertEquals(Journal.Status.COMMITTED,outcome.status(),outcome.result().code());}
        long total(ActivityFacts.Kind kind){return store.transaction(tx->Long.parseLong(tx.query("SELECT COALESCE(SUM(minor),0) AS total FROM activity_day_counters WHERE kind=?",List.of(kind.name()),1).getFirst().get("total")));}
        public void close()throws Exception{store.close();}
    }
}
