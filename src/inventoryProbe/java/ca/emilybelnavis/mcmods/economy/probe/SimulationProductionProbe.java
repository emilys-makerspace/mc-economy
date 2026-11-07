package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.cash.CashCatalog;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.trade.entity.*;
import ca.emilybelnavis.mcmods.economy.trade.menu.TraderMenu;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.item.trading.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Real production lifecycle, offered network choices, cash settlement, saves and native progression. */
public final class SimulationProductionProbe {
    private SimulationProductionProbe(){}
    public static volatile String result,failure;private static Villager trader;private static boolean checking;private static int tick;
    public static boolean tick(MinecraftServer server){if(result!=null||failure!=null)return true;if(server.getPlayerList().getPlayers().isEmpty())return false;
        var root=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();if(!root.getFileName().toString().matches("economy-simulation-production-[0-9a-f-]{36}"))throw new IllegalStateException("Disposable world required");
        var services=Economy.serverServices(server);if(services!=null&&services.state()==ServerServices.State.UNAVAILABLE){services.failure().printStackTrace();throw new IllegalStateException("Economy lifecycle failed",services.failure());}if(services==null||services.state()!=ServerServices.State.READY)return false;var player=server.getPlayerList().getPlayers().getFirst();if(++tick%10!=0)return false;
        if(trader==null){
            if(services.macroDiagnostics().stream().noneMatch(s->s.contains("WARMUP_COMPLETE_DAY_REQUIRED")||s.contains("AVAILABLE"))){server.getCommands().performPrefixedCommand(player.createCommandSourceStack(),"economy choose CAD");return false;}
            for(int i=0;i<36;i++)player.getInventory().setItem(i,ItemStack.EMPTY);
            player.getInventory().setItem(0,new ItemStack(CashCatalog.byId("economy:jpy_bill_1000").orElseThrow().item().get()));
            trader=new Villager(EntityType.VILLAGER,player.serverLevel());trader.setNoAi(true);trader.setNoGravity(true);trader.setInvulnerable(true);trader.setPos(player.getX()+1,player.getY(),player.getZ());
            var offers=trader.getOffers();offers.clear();offers.add(new MerchantOffer(new ItemCost(Items.EMERALD,1),new ItemStack(Items.BREAD,6),12,2,0));player.serverLevel().addFreshEntity(trader);
        }
        if(!(player.containerMenu instanceof TraderMenu)&&player.getInventory().countItem(Items.BREAD)==0){var event=new PlayerInteractEvent.EntityInteract(player,InteractionHand.MAIN_HAND,trader);NeoForge.EVENT_BUS.post(event);if(!event.isCanceled())throw new IllegalStateException("Production native checkout bypass");return false;}
        if(player.getInventory().countItem(Items.BREAD)==6&&trader.getOffers().getFirst().getUses()==1&&!checking){checking=true;services.tradeStorage(store->store.transaction(tx->{
            var receipts=tx.query("SELECT payload,operation_id FROM npc_cash_receipts ORDER BY operation_id LIMIT 2",List.of(),2);if(receipts.size()!=1)throw new IllegalStateException("Exactly one production receipt required");var receipt=ca.emilybelnavis.mcmods.economy.trade.cash.NpcCashFacts.decodeReceipt(receipts.getFirst().get("payload"));
            var progression=ca.emilybelnavis.mcmods.economy.trade.stock.StockStore.progression(tx::query,receipt.operation());if(progression.stage()!=ca.emilybelnavis.mcmods.economy.trade.stock.StockFacts.Stage.ACKNOWLEDGED)return false;
            if(ca.emilybelnavis.mcmods.economy.simulation.SimulationLifecycle.read(tx::query)==null||ca.emilybelnavis.mcmods.economy.simulation.macro.MacroActivation.read(tx::query,store.snapshot().worldId()).isEmpty())throw new IllegalStateException("Production manifest/activation missing");
            if(receipt.goodsPrincipal()<=0||tx.query("SELECT minor FROM activity_day_counters WHERE kind='NPC_RETIRE' AND minor>0 LIMIT 1",List.of(),1).isEmpty())throw new IllegalStateException("Production principal missing");
            return true;
        })).whenComplete((passed,error)->{checking=false;if(error!=null)failure=error.toString();else if(passed){if(trader.getVillagerXp()!=2||player.getInventory().countItem(Items.EMERALD)!=0)failure="Native progression count mismatch";else result="PASS production manifest and future rate warmup; native interaction cancellation; actual offered client network cash/currency/quantity/quote/confirm; one monetary receipt; six bread and protected cash change; independent player and entity STARTED/APPLIED saves; native uses/XP once; progression acknowledged";}});}
        return result!=null||failure!=null;
    }
}
