package ca.emilybelnavis.mcmods.economy.trade.pricing;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import java.util.*;
import org.junit.jupiter.api.Test;
import static ca.emilybelnavis.mcmods.economy.simulation.model.Rational.*;
import static org.junit.jupiter.api.Assertions.*;

class PricingTest {
    private static final String ITEM="a".repeat(64);
    private static final UUID MARKET=new UUID(1,2),ACTOR=new UUID(3,4),EPOCH=new UUID(5,6),SOURCE=new UUID(7,8),FX=new UUID(9,10);
    private static final Rational ASK=of(100).divide(of(3)), BID=of(10).divide(of(3));
    @Test void sameInstantSplitAndBulkHaveEqualMoneyAndFinalDemandOnBothSides() {
        for(var side:Pricing.Side.values()) {
            var key=key(side); var start=new Pricing.Demand(ZERO,100);
            var first=Pricing.quote(request(key,1,start,Pricing.Carry.empty(key),ZERO));assertTrue(first.available(),first.reason());
            var second=Pricing.quote(request(key,1,first.quote().nextDemand(),first.quote().nextCarry(),ZERO));assertTrue(second.available(),second.reason());
            var bulk=Pricing.quote(request(key,2,start,Pricing.Carry.empty(key),ZERO));assertTrue(bulk.available(),bulk.reason());
            assertEquals(bulk.quote().minor(),first.quote().minor()+second.quote().minor());
            assertEquals(bulk.quote().exactPrincipal(),first.quote().exactPrincipal().add(second.quote().exactPrincipal()));
            assertEquals(bulk.quote().nextCarry(),second.quote().nextCarry());
            assertEquals(bulk.quote().nextDemand(),second.quote().nextDemand());
        }
    }
    @Test void integrationSaturatesDemandWithoutLosingSplitEquivalence() {
        Rational initial=of("1.99");
        assertEquals(of("5.995"),Pricing.integral(initial,of(3),Pricing.Side.BUY));
        assertEquals(Pricing.integral(initial,of(3),Pricing.Side.BUY),Pricing.integral(initial,ONE,Pricing.Side.BUY)
                .add(Pricing.integral(of(2),of(2),Pricing.Side.BUY)));
        assertEquals(of("1.505"),Pricing.integral(of("0.51"),of(3),Pricing.Side.SELL));
    }
    @Test void preservedRecoveryAnchorHalvesExactlyAndCheckpointsDoNotRestartDecay() {
        var anchor=new Pricing.Demand(ONE,0);
        assertEquals(of("1.875"),Pricing.recover(anchor,6000));
        assertEquals(of("1.75"),Pricing.recover(anchor,12000));
        assertEquals(of("1.5"),Pricing.recover(anchor,24000));
        assertEquals(of("1.375"),Pricing.recover(anchor,36000));
        assertEquals(of("1.25"),Pricing.recover(anchor,48000));
        var wronglyReanchored=new Pricing.Demand(Pricing.recover(anchor,6000).subtract(ONE),6000);
        assertNotEquals(Pricing.recover(anchor,12000),Pricing.recover(wronglyReanchored,12000));
    }
    @Test void reputationRespectsCrossOfferSpreadAndUnavailableComparisonsReject() {
        var key=key(Pricing.Side.SELL);var demand=new Pricing.Demand(ZERO,100);
        var neutral=Pricing.quote(request(key,10,demand,Pricing.Carry.empty(key),ZERO));
        var favorable=Pricing.quote(request(key,10,demand,Pricing.Carry.empty(key),of("0.25")));
        assertTrue(neutral.available());assertTrue(favorable.available());assertTrue(favorable.quote().minor()>=neutral.quote().minor());
        var tight=new Pricing.Comparison(ITEM,of(100),of(80));
        var basis=new TradePriceSource.Basis(SOURCE,1,FX,EconomyCurrency.CAD,of(80));
        var result=Pricing.quote(new Pricing.Request(key,basis,1,10,demand,100,of("0.25"),List.of(tight),Pricing.Carry.empty(key)));
        assertTrue(result.available());assertEquals(ZERO,result.quote().reputationFactor());
        var missing=Pricing.quote(new Pricing.Request(key,basis,1,10,demand,100,ZERO,List.of(),Pricing.Carry.empty(key)));
        assertFalse(missing.available());assertEquals("COMPARISON_UNAVAILABLE",missing.reason());
        var rawBid=new TradePriceSource.Basis(SOURCE,2,FX,EconomyCurrency.CAD,of(90));
        var normalized=Pricing.normalizedBasis(Pricing.Side.SELL,rawBid,tight);
        assertEquals(of(80),normalized.minorPerStandard());
        assertEquals(rawBid.source(),normalized.source());assertEquals(rawBid.fxSnapshot(),normalized.fxSnapshot());
        assertEquals(rawBid.revision(),normalized.revision());assertEquals(rawBid.currency(),normalized.currency());
        var narrowed=Pricing.quote(new Pricing.Request(key,normalized,1,10,demand,100,ZERO,List.of(tight),Pricing.Carry.empty(key)));
        assertTrue(narrowed.available(),narrowed.reason());
    }
    @Test void fractionalZeroPayoutRejectsAndCapturedSourceConvertsMajorUnits() {
        var key=key(Pricing.Side.SELL);var basis=new TradePriceSource.Basis(SOURCE,1,FX,EconomyCurrency.CAD,of("0.1"));
        var request=new Pricing.Request(key,basis,1,1,new Pricing.Demand(ZERO,100),100,ZERO,
                List.of(new Pricing.Comparison(ITEM,of(100),of("0.1"))),Pricing.Carry.empty(key));
        var outcome=Pricing.quote(request);assertFalse(outcome.available());assertEquals("LARGER_POSITIVE_BATCH_REQUIRED",outcome.reason());
        var source=new TradePriceSource.EmeraldV1(SOURCE,1,ONE,FX,EconomyCurrency.USD,of("1.25"));
        assertEquals(of(40),source.basis(2,4,1).minorPerStandard());
        assertThrows(IllegalArgumentException.class,()->new Pricing.Request(key,basis,1,1,new Pricing.Demand(ZERO,100),100,ZERO,
                request.comparisons(),Pricing.Carry.empty(key(Pricing.Side.BUY))));
    }
    private static Pricing.Key key(Pricing.Side side){return new Pricing.Key(MARKET,ITEM,ACTOR,side,EPOCH);}
    private static Pricing.Request request(Pricing.Key key,long goods,Pricing.Demand demand,Pricing.Carry carry,Rational reputation) {
        var basis=new TradePriceSource.Basis(SOURCE,1,FX,EconomyCurrency.CAD,key.side()==Pricing.Side.BUY?ASK:BID);
        return new Pricing.Request(key,basis,1,goods,demand,100,reputation,List.of(new Pricing.Comparison(ITEM,ASK,BID)),carry);
    }
}
