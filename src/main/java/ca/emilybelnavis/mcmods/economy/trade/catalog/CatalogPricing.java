package ca.emilybelnavis.mcmods.economy.trade.catalog;

import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.trade.pricing.*;
import java.util.*;

/** Normalizes a complete detached equivalent-offer cohort, without adding native offers. */
public final class CatalogPricing {
    private CatalogPricing() {}
    public record Compared(TradePriceSource.Basis basis,List<Pricing.Comparison> comparisons) {
        public Compared {comparisons=List.copyOf(comparisons);}
    }
    /** The future adapter must prove completeness across reachable market/reputation witnesses. */
    public static Compared compare(TradeCatalog.Offer selected,List<TradeCatalog.Offer> completeCohort,
                                   boolean complete,TradePriceSource source) {
        if(!complete||completeCohort.isEmpty()||completeCohort.size()>32||!completeCohort.contains(selected))
            throw new IllegalArgumentException("Complete bounded equivalent-offer cohort required");
        Rational ask=null,bid=null;
        for(var offer:completeCohort) {
            if(!TradeCatalog.equivalent(selected,offer))throw new IllegalArgumentException("Unresolved component/secondary equivalence");
            var value=source.basis(offer.emeraldCount(),offer.goodsCount(),offer.standardQuantity()).minorPerStandard();
            if(offer.side()==Pricing.Side.BUY)ask=ask==null?value:ask.min(value);
            else bid=bid==null?value:bid.max(value);
        }
        var original=source.basis(selected.emeraldCount(),selected.goodsCount(),selected.standardQuantity());
        // A hypothetical comparison only constrains a one-sided entry; it creates no offer.
        if(ask==null)ask=bid.divide(Rational.of("0.8"));
        if(bid==null)bid=ask.multiply(Rational.of("0.8"));
        var comparison=Pricing.Comparison.neutral(selected.goods().priceKey(),ask,bid);
        // Individual asks retain their larger gaps; all bids obey the lowest reachable ask.
        var local=new Pricing.Comparison(selected.goods().priceKey(),
                selected.side()==Pricing.Side.BUY?original.minorPerStandard():ask,
                selected.side()==Pricing.Side.SELL?original.minorPerStandard().min(comparison.neutralBid()):comparison.neutralBid());
        var normalized=Pricing.normalizedBasis(selected.side(),original,local);
        return new Compared(normalized,List.of(comparison,local));
    }
}
