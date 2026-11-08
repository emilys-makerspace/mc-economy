package ca.emilybelnavis.mcmods.economy.trade.catalog;

import ca.emilybelnavis.mcmods.economy.trade.pricing.Pricing;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Detached, exact offer semantics captured by a trusted server adapter in A8-03-02. */
public final class TradeCatalog {
    private TradeCatalog() {}
    public enum Match { EXACT_COMPONENTS, REQUIRED_COMPONENTS }
    public record Goods(String item,Match match,String components) {
        public Goods {
            key(item);Objects.requireNonNull(match);components=canonicalComponents(components);
            if(item.equals("minecraft:emerald"))throw new IllegalArgumentException("Emerald is a monetary leg, not merchandise");
        }
        /** Quantity-independent component/predicate identity for shared demand. */
        public String priceKey(){return digest(item+"|"+match+"|"+components);}
    }
    public record Ingredient(Goods goods,int count) {
        public Ingredient {Objects.requireNonNull(goods);if(count<1||count>64)throw new IllegalArgumentException("Ingredient count");}
    }
    public record Offer(int ordinal,Pricing.Side side,Goods goods,int goodsCount,int emeraldCount,
                        List<Ingredient> secondary,int originalMaxUses,int villagerXp) {
        public Offer {
            Objects.requireNonNull(side);Objects.requireNonNull(goods);secondary=List.copyOf(secondary);
            if(ordinal<0||ordinal>=32||goodsCount<1||goodsCount>64||emeraldCount<1||emeraldCount>64
                    ||secondary.size()>1||originalMaxUses<1||originalMaxUses>1024||villagerXp<0||villagerXp>1024)
                throw new IllegalArgumentException("Detached offer domain");
            StandardQuantities.require(goods.item());
            if(side==Pricing.Side.SELL&&!secondary.isEmpty())throw new IllegalArgumentException("Unsupported seller secondary predicate");
        }
        public int standardQuantity(){return StandardQuantities.require(goods.item());}
        /** Preserves secondary predicates/counts and original catalog quantities and progression. */
        public String identity(){return digest(CatalogFacts.encode(this));}
        public String comparisonKey(){
            var text=new StringBuilder(goods.priceKey());
            for(var ingredient:secondary)text.append('|').append(ingredient.goods().priceKey()).append('|').append(ingredient.count());
            return digest(text.toString());
        }
    }
    public record Capture(UUID trader,long revision,List<Offer> offers) {
        public Capture {
            Objects.requireNonNull(trader);offers=List.copyOf(offers);
            if(revision<1||offers.isEmpty()||offers.size()>32)throw new IllegalArgumentException("Catalog capture domain");
            for(int i=0;i<offers.size();i++)if(offers.get(i).ordinal()!=i)throw new IllegalArgumentException("Catalog ordinal");
        }
    }
    /** Resolves only exactly comparable captured inputs; unresolved secondary value is unavailable. */
    public static boolean equivalent(Offer a,Offer b) {
        if(!a.goods().equals(b.goods())||a.secondary().size()!=b.secondary().size())return false;
        for(int i=0;i<a.secondary().size();i++) {
            var x=a.secondary().get(i);var y=b.secondary().get(i);
            if(!x.goods().equals(y.goods())||(long)x.count()*b.goodsCount()!=(long)y.count()*a.goodsCount())return false;
        }
        return true;
    }
    public static String digest(String text){return StandardQuantities.sha256(text.getBytes(StandardCharsets.UTF_8));}
    private static void key(String value){if(value==null||!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||value.length()>256)throw new IllegalArgumentException("Item identity");}
    private static String canonicalComponents(String value) {
        if(value==null||value.length()>4096)throw new IllegalArgumentException("Component predicate size");
        var parsed=JsonParser.parseString(value);
        if(!parsed.isJsonObject())throw new IllegalArgumentException("Exact component object required");
        return sorted(parsed,0).toString();
    }
    private static JsonElement sorted(JsonElement value,int depth) {
        if(depth>32)throw new IllegalArgumentException("Component predicate depth");
        if(value.isJsonObject()) {
            var result=new JsonObject();var map=new TreeMap<String,JsonElement>();value.getAsJsonObject().entrySet().forEach(e->map.put(e.getKey(),e.getValue()));
            map.forEach((k,v)->result.add(k,sorted(v,depth+1)));return result;
        }
        if(value.isJsonArray()){var result=new JsonArray();value.getAsJsonArray().forEach(v->result.add(sorted(v,depth+1)));return result;}
        return value.deepCopy();
    }
}
