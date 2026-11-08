package ca.emilybelnavis.mcmods.economy.trade.entity;

import ca.emilybelnavis.mcmods.economy.trade.catalog.TradeCatalog;
import ca.emilybelnavis.mcmods.economy.trade.pricing.Pricing;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.*;
import net.minecraft.world.item.trading.*;

/** Server-only detachment of the already generated catalog. Never calls an ItemListing factory. */
public final class NativeCatalog {
    private NativeCatalog() {}
    public record Result(TradeCatalog.Capture capture,String reason) {
        public Result { Objects.requireNonNull(reason); if((capture!=null)!=reason.equals("AVAILABLE"))throw new IllegalArgumentException("Capture result"); }
        public boolean available(){return capture!=null;}
    }
    public static Result capture(AbstractVillager trader,long revision) {
        thread(trader);
        // Native getOffers initializes once if necessary; it preserves the entity's saved offers.
        return detach(trader.getUUID(),revision,trader.getOffers(),trader.registryAccess());
    }
    public static Result detach(UUID trader,long revision,MerchantOffers nativeOffers,RegistryAccess registries) {
        Objects.requireNonNull(trader);Objects.requireNonNull(nativeOffers);Objects.requireNonNull(registries);
        if(nativeOffers.isEmpty() || nativeOffers.size()>32)return unavailable("CATALOG_SIZE_UNAVAILABLE");
        var detached=new ArrayList<TradeCatalog.Offer>();
        for(int i=0;i<nativeOffers.size();i++) {
            try {detached.add(detachOffer(i,nativeOffers.get(i),registries));}
            catch(RuntimeException unsupported){return unavailable("OFFER_"+i+"_UNAVAILABLE: "+safe(unsupported.getMessage()));}
        }
        return new Result(new TradeCatalog.Capture(trader,revision,detached),"AVAILABLE");
    }
    /** Uses original quantities, never native special price, demand or exhaustion. */
    public static TradeCatalog.Offer detachOffer(int ordinal,MerchantOffer offer,RegistryAccess registries) {
        Objects.requireNonNull(offer);Objects.requireNonNull(registries);
        ItemCost first=offer.getItemCostA();var second=offer.getItemCostB();ItemStack result=offer.getResult();
        if(result.isEmpty())throw new IllegalArgumentException("Empty result");
        if(first.item().value()==Items.EMERALD && !result.is(Items.EMERALD)) {
            if(!first.components().alwaysMatches())throw new IllegalArgumentException("Monetary emerald predicate unsupported");
            var secondary=new ArrayList<TradeCatalog.Ingredient>();
            if(second.isPresent()) {
                if(second.get().item().value()==Items.EMERALD)throw new IllegalArgumentException("Additional monetary emerald cost unsupported");
                secondary.add(new TradeCatalog.Ingredient(required(second.get(),registries),second.get().count()));
            }
            return new TradeCatalog.Offer(ordinal,Pricing.Side.BUY,exact(result,registries),result.getCount(),first.count(),
                    secondary,offer.getMaxUses(),offer.getXp());
        }
        if(result.is(Items.EMERALD) && first.item().value()!=Items.EMERALD) {
            if(second.isPresent())throw new IllegalArgumentException("Seller secondary predicate unsupported");
            if(!result.getComponentsPatch().isEmpty())throw new IllegalArgumentException("Monetary emerald components unsupported");
            return new TradeCatalog.Offer(ordinal,Pricing.Side.SELL,required(first,registries),first.count(),result.getCount(),
                    List.of(),offer.getMaxUses(),offer.getXp());
        }
        throw new IllegalArgumentException("Expected one original emerald monetary leg");
    }
    public static boolean matches(AbstractVillager trader,TradeCatalog.Offer captured) {
        thread(trader);var offers=trader.getOffers();
        if(captured.ordinal()>=offers.size())return false;
        try{return captured.equals(detachOffer(captured.ordinal(),offers.get(captured.ordinal()),trader.registryAccess()));}
        catch(RuntimeException unsupported){return false;}
    }
    private static TradeCatalog.Goods required(ItemCost cost,RegistryAccess registries) {
        var ops=registries.createSerializationContext(JsonOps.INSTANCE);var predicate=cost.components();
        for(var entry:predicate.asPatch().entrySet())if(entry.getKey().isTransient())throw new IllegalArgumentException("Transient required component");
        var encoded=DataComponentPredicate.CODEC.encodeStart(ops,predicate).getOrThrow();
        var restored=DataComponentPredicate.CODEC.parse(ops,encoded).getOrThrow();
        if(!predicate.asPatch().equals(restored.asPatch()))throw new IllegalArgumentException("Lossy required component codec");
        var object=new JsonObject();object.add("required",encoded);
        return new TradeCatalog.Goods(BuiltInRegistries.ITEM.getKey(cost.item().value()).toString(),TradeCatalog.Match.REQUIRED_COMPONENTS,object.toString());
    }
    private static TradeCatalog.Goods exact(ItemStack stack,RegistryAccess registries) {
        var ops=registries.createSerializationContext(JsonOps.INSTANCE);var components=stack.getComponents();var patch=stack.getComponentsPatch();
        for(var component:components)if(component.type().isTransient())throw new IllegalArgumentException("Transient result component");
        for(var entry:patch.entrySet())if(entry.getKey().isTransient())throw new IllegalArgumentException("Transient result patch");
        var full=DataComponentMap.CODEC.encodeStart(ops,components).getOrThrow();
        var fullRestored=DataComponentMap.CODEC.parse(ops,full).getOrThrow();
        if(!DataComponentPredicate.allOf(components).asPatch().equals(DataComponentPredicate.allOf(fullRestored).asPatch()))
            throw new IllegalArgumentException("Lossy exact result codec");
        var patchJson=DataComponentPatch.CODEC.encodeStart(ops,patch).getOrThrow();
        if(!patch.equals(DataComponentPatch.CODEC.parse(ops,patchJson).getOrThrow()))throw new IllegalArgumentException("Lossy result patch codec");
        var object=new JsonObject();object.add("values",full);object.add("patch",patchJson);
        return new TradeCatalog.Goods(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),TradeCatalog.Match.EXACT_COMPONENTS,object.toString());
    }
    private static Result unavailable(String reason){return new Result(null,reason);}
    private static String safe(String message){return message==null?"Unsupported native semantics":message.substring(0,Math.min(240,message.length()));}
    static void thread(AbstractVillager trader) {
        if(trader.level().isClientSide || trader.getServer()==null || !trader.getServer().isSameThread())
            throw new IllegalStateException("Server thread required");
    }
}
