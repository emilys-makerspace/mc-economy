package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.trade.entity.*;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.trading.*;
import net.minecraft.world.level.storage.LevelResource;

/** Actual mapped Minecraft calls; a missing/corrupt injection makes this fixture fail. */
public final class TraderHookProbe {
    private TraderHookProbe() {}
    public static volatile String result,failure;
    public static boolean tick(MinecraftServer server) {
        if(result!=null||failure!=null)return true;
        if(server.getPlayerList().getPlayers().isEmpty())return false;
        var root=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        require(root.getFileName().toString().matches("economy-trader-hooks-[0-9a-f-]{36}"),"unique disposable world");
        var level=server.overworld();var player=server.getPlayerList().getPlayers().getFirst();
        // Production claims untagged native traders too. Exercise the vanilla control with
        // monetary trading explicitly disabled, then restore the original mode in this tick.
        boolean monetaryTrading=TradeRuntime.enabled(server);
        Villager villager;
        MerchantOffer offer;
        MerchantMenu nativeMenu;
        TradeRuntime.configure(server,false);
        try {
            villager=new Villager(EntityType.VILLAGER,level);villager.setNoAi(true);villager.setPos(player.position());
            offer=new MerchantOffer(new ItemCost(Items.EMERALD,1),new ItemStack(Items.BREAD,6),12,2,0);
            var offers=villager.getOffers();offers.clear();offers.add(offer);villager.setTradingPlayer(player);
            nativeMenu=new MerchantMenu(77,player.getInventory(),villager);
            nativeMenu.getSlot(0).set(new ItemStack(Items.EMERALD));
            require(!TradeHooks.owned(villager),"disabled monetary trading leaves the untagged vanilla control unowned");
            require(ItemStack.matches(new ItemStack(Items.BREAD,6),nativeMenu.getSlot(2).getItem()),"unowned native catalog still works");
            var capture=NativeCatalog.capture(villager,1);require(capture.available(),"actual native capture: "+capture.reason());
            require(capture.capture().offers().getFirst().goodsCount()==6&&capture.capture().offers().getFirst().originalMaxUses()==12,"original native count/stock");
        } finally {
            TradeRuntime.configure(server,monetaryTrading);
        }
        if(monetaryTrading) {
            require(!villager.getPersistentData().getBoolean(TradeHooks.OWNED)&&TradeHooks.owned(villager),"enabled monetary trading claims an untagged native trader");
            require(nativeMenu.getSlot(2).getItem().isEmpty(),"production ownership hides a previously prepared native result");
        }
        villager.getPersistentData().putBoolean(TradeHooks.OWNED,true);
        int before=player.getInventory().countItem(Items.BREAD),uses=offer.getUses(),xp=villager.getVillagerXp();
        require(!nativeMenu.getSlot(2).mayPickup(player),"owned inherited pickup blocked");
        require(nativeMenu.quickMoveStack(player,2).isEmpty(),"shift-click blocked before movement");
        require(nativeMenu.getSlot(2).getItem().isEmpty()&&nativeMenu.getSlot(2).remove(64).isEmpty(),"owned result/creative clone hidden");
        nativeMenu.getSlot(2).onTake(player,new ItemStack(Items.BREAD,6));
        require(player.getInventory().countItem(Items.BREAD)==before&&offer.getUses()==uses&&villager.getVillagerXp()==xp,"no native onTake progression/effects");
        var container=new MerchantContainer(villager);container.setItem(0,new ItemStack(Items.EMERALD));container.setItem(2,new ItemStack(Items.BREAD,6));
        require(container.getItem(2).isEmpty()&&container.removeItem(2,64).isEmpty()&&container.removeItemNoUpdate(2).isEmpty(),"direct native extraction paths blocked");
        offer.increaseUses();int exhausted=offer.getUses();villager.restock();
        require(offer.getUses()==exhausted&&!villager.shouldRestock(),"native restock/demand clock suppressed");
        villager.notifyTrade(offer);require(offer.getUses()==exhausted&&villager.getVillagerXp()==xp,"unscoped notifyTrade suppressed");
        var previous=player.containerMenu;villager.openTradingScreen(player,Component.literal("fixture"),1);
        require(player.containerMenu==previous,"direct inherited native menu open blocked");
        TradeHooks.withProgression(villager,()->villager.notifyTrade(offer));
        require(offer.getUses()==exhausted+1&&villager.getVillagerXp()==xp+offer.getXp(),"single explicit native progression path");
        var wandering=new WanderingTrader(EntityType.WANDERING_TRADER,level);wandering.getPersistentData().putBoolean(TradeHooks.OWNED,true);
        require(TradeHooks.owned(wandering),"wandering ownership uses same gate");
        result="PASS actual owned native result, quickmove, direct extraction, menu, restock and progression gates; explicit disabled-mode native control; restored production ownership; detached catalog counts";
        return true;
    }
    private static void require(boolean condition,String detail){if(!condition)throw new IllegalStateException(detail);}
}
