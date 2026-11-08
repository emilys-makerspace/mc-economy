package ca.emilybelnavis.mcmods.economy.trade.menu;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.cash.CashCatalog;
import ca.emilybelnavis.mcmods.economy.trade.catalog.TradeCatalog;
import ca.emilybelnavis.mcmods.economy.trade.pricing.Pricing;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Server-thread detachment of exact native goods and explicit selected-currency cash. */
final class TraderItems {
    private TraderItems() {}
    private record Required(TradeCatalog.Goods goods,int count) {}
    static Map<Integer,Integer> select(ServerPlayer player,TradeCatalog.Offer offer,int executions,EconomyCurrency cash) {
        var selected=new TreeMap<Integer,Integer>();
        var requirements=new ArrayList<Required>();
        if(offer.side()==Pricing.Side.SELL)requirements.add(new Required(offer.goods(),Math.multiplyExact(offer.goodsCount(),executions)));
        for(var ingredient:offer.secondary())requirements.add(new Required(ingredient.goods(),Math.multiplyExact(ingredient.count(),executions)));
        requirements.sort(Comparator.comparing(i->i.goods().match()));
        for(var requirement:requirements) {
            int needed=requirement.count();
            for(int slot=0;slot<36&&needed>0;slot++) {
                var stack=player.getInventory().getItem(slot);
                if(CashCatalog.find(stack).isPresent()||!matches(player,stack,requirement.goods()))continue;
                int available=stack.getCount()-selected.getOrDefault(slot,0),take=Math.min(available,needed);
                if(take>0){selected.merge(slot,take,Integer::sum);needed-=take;}
            }
            if(needed!=0)throw new IllegalArgumentException("Required goods or secondary items are missing");
        }
        if(cash!=null&&offer.side()==Pricing.Side.BUY)for(int slot=0;slot<36;slot++) {
            var stack=player.getInventory().getItem(slot);var denomination=CashCatalog.find(stack);
            if(denomination.isPresent()&&denomination.get().currency()==cash)selected.put(slot,stack.getCount());
        }
        if(selected.size()>24)throw new IllegalArgumentException("Select at most 24 input stacks");
        return Map.copyOf(selected);
    }
    static boolean matches(ServerPlayer player,ItemStack stack,TradeCatalog.Goods goods) {
        if(stack.isEmpty()||!BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(goods.item()))return false;
        var ops=player.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        var json=JsonParser.parseString(goods.components()).getAsJsonObject();
        if(goods.match()==TradeCatalog.Match.REQUIRED_COMPONENTS)return DataComponentPredicate.CODEC.parse(ops,json.get("required")).getOrThrow().test(stack);
        for(var component:stack.getComponents())if(component.type().isTransient())return false;
        for(var component:stack.getComponentsPatch().entrySet())if(component.getKey().isTransient())return false;
        return DataComponentMap.CODEC.encodeStart(ops,stack.getComponents()).getOrThrow().equals(json.get("values"))
                &&DataComponentPatch.CODEC.encodeStart(ops,stack.getComponentsPatch()).getOrThrow().equals(json.get("patch"));
    }
    static List<ItemStack> goods(ServerPlayer player,TradeCatalog.Offer offer,int executions) {
        if(offer.side()!=Pricing.Side.BUY)return List.of();
        if(offer.goods().match()!=TradeCatalog.Match.EXACT_COMPONENTS)throw new IllegalArgumentException("Exact result unavailable");
        var item=BuiltInRegistries.ITEM.get(ResourceLocation.parse(offer.goods().item()));
        var json=JsonParser.parseString(offer.goods().components()).getAsJsonObject();
        var patch=DataComponentPatch.CODEC.parse(player.registryAccess().createSerializationContext(JsonOps.INSTANCE),json.get("patch")).getOrThrow();
        var prototype=new ItemStack(item);prototype.applyComponents(patch);
        if(!matches(player,prototype,offer.goods()))throw new IllegalArgumentException("Exact result changed");
        long left=Math.multiplyExact((long)offer.goodsCount(),executions);var result=new ArrayList<ItemStack>();
        while(left>0){int count=(int)Math.min(left,Math.min(64,prototype.getMaxStackSize()));result.add(prototype.copyWithCount(count));left-=count;if(result.size()>36)throw new IllegalArgumentException("Output stack bound");}
        return List.copyOf(result);
    }
}
