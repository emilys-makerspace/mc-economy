package ca.emilybelnavis.mcmods.economy.simulation.model;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityFacts.Kind;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

class MacroModelTest {
    @Test void emptyCoveredWindowHoldsAndMissingCoverageIsUnavailable() {
        var empty=window(7,7,Map.of(),Map.of()); var input=input(Map.of(CAD,empty,USD,empty));
        var result=MacroModel.compute(input);
        assertTrue(result.available()); assertEquals("EMPTY_WINDOW_HOLD",result.reason());
        assertEquals(input.priorFx(),result.fx()); assertEquals(input.priorPrime(),result.primes());
        var unavailable=new MacroModel.Window(0,0,false,Map.of(),Map.of(),BigInteger.ZERO);
        var missing=MacroModel.compute(input(Map.of(CAD,unavailable,USD,unavailable)));
        assertFalse(missing.available()); assertEquals("MISSING_COVERAGE",missing.reason());
    }
    @Test void opposedFlowsUseExactAnchorSignalsAndPrimeStep() {
        var w=window(1,0,Map.of(Kind.CREDIT_DRAW,BigInteger.valueOf(100_000),Kind.OPERATOR_RETIRE,BigInteger.valueOf(100_000)),Map.of());
        var empty=window(1,0,Map.of(),Map.of()); var output=MacroModel.compute(input(Map.of(CAD,w,USD,empty)));
        assertEquals(Rational.of("0.5"),output.signals().get(CAD).credit());
        assertEquals(Rational.of("-0.5"),output.signals().get(CAD).money());
        assertEquals(Rational.ZERO,output.signals().get(CAD).primePressure());
        assertEquals(new BigDecimal("0.050000"),output.primes().get(CAD));
        var onlyCredit=MacroModel.compute(input(Map.of(CAD,window(1,0,Map.of(Kind.CREDIT_DRAW,BigInteger.valueOf(100_000)),Map.of()),USD,empty)));
        assertEquals(new BigDecimal("0.050167"),onlyCredit.primes().get(CAD));
    }
    @Test void coherentScalingChecksEveryOrderedPairAfterRoundingAndReplaysIdentically() {
        BigInteger huge=new BigInteger("100000000000000000000");
        var positive=window(7,0,Map.of(Kind.FX_BUY,huge,Kind.OPERATOR_RETIRE,huge),Map.of());
        var negative=window(7,0,Map.of(Kind.FX_SELL,huge,Kind.OPERATOR_CREATE,huge),Map.of());
        var empty=window(7,0,Map.of(),Map.of());
        var input=input(Map.of(CAD,positive,USD,negative,EUR,empty)); var result=MacroModel.compute(input);
        assertTrue(result.alpha().compareTo(Rational.ONE)<0); assertTrue(result.alpha().compareTo(Rational.ZERO)>0);
        assertEquals(0,result.fx().get(CAD).compareTo(BigDecimal.ONE)); assertTrue(MacroModel.validRounded(input,result.fx()));
        for(var a:result.fx().keySet())for(var b:result.fx().keySet()) {
            var ratio=Rational.of(result.fx().get(a)).divide(Rational.of(result.fx().get(b)))
                    .divide(Rational.of(input.priorFx().get(a)).divide(Rational.of(input.priorFx().get(b))));
            assertTrue(ratio.compareTo(Rational.of("0.995"))>=0);assertTrue(ratio.compareTo(Rational.of("1.005"))<=0);
        }
        assertEquals(result,MacroModel.compute(ModelFacts.decodeInput(ModelFacts.encode(input))));
        assertEquals(result,ModelFacts.decodeOutput(ModelFacts.encode(result)));
    }
    @Test void baselineMeansAndAnchorCurrencyUnitsAreExactAndCorruptPriorRejects() {
        var cad=window(7,7,Map.of(Kind.GOODS_SALE,BigInteger.valueOf(700_000)),Map.of(Kind.GOODS_SALE,BigInteger.valueOf(350_000)));
        var usd=window(7,7,Map.of(Kind.GOODS_SALE,BigInteger.valueOf(350_000)),Map.of(Kind.GOODS_SALE,BigInteger.valueOf(175_000)));
        var in=input(Map.of(CAD,cad,USD,usd)); var output=MacroModel.compute(in);
        assertEquals(Rational.of(1).divide(Rational.of(5)),output.signals().get(CAD).spending());
        assertEquals(output.signals().get(CAD).spending(),output.signals().get(USD).spending());
        assertThrows(IllegalArgumentException.class,()->new MacroModel.Input(in.windowId(),in.inputHash(),CAD,in.windows(),in.anchors(),
                Map.of(CAD,BigDecimal.ONE,USD,new BigDecimal("5")),in.priorPrime(),in.config()));
    }
    private static MacroModel.Window window(int days,int baseline,Map<Kind,BigInteger> totals,Map<Kind,BigInteger> prior) {
        return new MacroModel.Window(days,baseline,true,totals,prior,BigInteger.ZERO);
    }
    private static MacroModel.Input input(Map<EconomyCurrency,MacroModel.Window> windows) {
        var anchors=new EnumMap<EconomyCurrency,BigDecimal>(EconomyCurrency.class);
        var primes=new EnumMap<EconomyCurrency,BigDecimal>(EconomyCurrency.class);
        windows.keySet().forEach(c->{anchors.put(c,c==USD?new BigDecimal("2"):BigDecimal.ONE);primes.put(c,new BigDecimal("0.05"));});
        return new MacroModel.Input(new UUID(1,2),"0".repeat(64),CAD,windows,anchors,anchors,primes,MacroModel.Config.defaults(new UUID(3,4),1));
    }
}
