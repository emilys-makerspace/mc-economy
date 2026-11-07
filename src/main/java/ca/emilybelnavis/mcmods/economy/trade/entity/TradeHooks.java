package ca.emilybelnavis.mcmods.economy.trade.entity;

import ca.emilybelnavis.mcmods.economy.banker.entity.BankerEntity;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.trading.Merchant;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Narrow ownership gate. No vanilla trader is claimed automatically before A8-07. */
public final class TradeHooks {
    private TradeHooks() {}
    public static final String OWNED="economy_a8_owned_v1";
    private static final ThreadLocal<AbstractVillager> PROGRESSION=new ThreadLocal<>();
    private record Click(UUID trader,int tick) {}
    private static final Map<ServerPlayer,Click> CLICKS=new WeakHashMap<>();
    public static boolean owned(Object target) {
        return !(target instanceof BankerEntity) && (target instanceof Villager||target instanceof WanderingTrader)
                && ((AbstractVillager)target).getPersistentData().getBoolean(OWNED);
    }
    public static boolean blocksProgression(Object target){return owned(target)&&PROGRESSION.get()!=target;}
    /** Only the original-receipt adapter may enter this scope; it grants no money authority. */
    public static void withProgression(AbstractVillager trader,Runnable action) {
        if(trader.level().isClientSide||!trader.getServer().isSameThread()||!owned(trader)||PROGRESSION.get()!=null)
            throw new IllegalStateException("Original progression requires an owned server trader");
        PROGRESSION.set(trader);try{action.run();}finally{PROGRESSION.remove();}
    }
    public static void initialize() {
        NeoForge.EVENT_BUS.addListener(TradeHooks::interact);
        NeoForge.EVENT_BUS.addListener(TradeHooks::interactSpecific);
    }
    private static void interact(PlayerInteractEvent.EntityInteract event){
        if(!owned(event.getTarget()))return;event.setCanceled(true);event.setCancellationResult(InteractionResult.CONSUME);route(event,event.getTarget());
    }
    private static void interactSpecific(PlayerInteractEvent.EntityInteractSpecific event){
        if(!owned(event.getTarget()))return;event.setCanceled(true);event.setCancellationResult(InteractionResult.CONSUME);route(event,event.getTarget());
    }
    private static void route(PlayerInteractEvent event,net.minecraft.world.entity.Entity target) {
        if(!owned(target))return;
        if(!(event.getEntity() instanceof ServerPlayer player)||event.getHand()!=InteractionHand.MAIN_HAND)return;
        var before=CLICKS.get(player);int tick=player.server.getTickCount();
        if(before!=null&&before.tick()==tick&&before.trader().equals(target.getUUID()))return;
        CLICKS.put(player,new Click(target.getUUID(),tick));
        TradeRuntime.interact(player,(AbstractVillager)target);
    }
    public interface ResultAccess { boolean economy$ownedResult(); }
}
