package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.cash.CashCatalog;
import ca.emilybelnavis.mcmods.economy.foundation.*;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.trade.cash.*;
import ca.emilybelnavis.mcmods.economy.trade.entity.*;
import ca.emilybelnavis.mcmods.economy.trade.menu.TraderMenu;
import ca.emilybelnavis.mcmods.economy.trade.pricing.*;
import ca.emilybelnavis.mcmods.economy.trade.stock.*;
import ca.emilybelnavis.mcmods.economy.valuation.ValuationRuntime;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.trading.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Uses only a new guarded world; never activates a source in a user's saved world. */
public final class ReferenceTraderProbe {
    private ReferenceTraderProbe(){}
    static volatile String result,failure;static volatile List<String> quoteLines=List.of();static int phase;
    private static Villager trader;private static int ticks;private static CompletableFuture<?> work;
    private static ReferenceTrading.Review review;private static ValuationRuntime.Lease lease;private static boolean checking;
    private static final AtomicBoolean LIVE=new AtomicBoolean(true);
    private static boolean denominationRequested,denominationReady;
    private static CompletableFuture<Boolean> denomination;
    public static boolean tick(MinecraftServer server){
        if(result!=null||failure!=null)return true;
        if(server.getPlayerList().getPlayers().isEmpty())return false;
        require(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString().matches("economy-reference-trader-[0-9a-f-]{36}"),"Fresh guarded reference fixture world required");
        var services=Economy.serverServices(server);
        if(services!=null&&services.state()==ServerServices.State.UNAVAILABLE)throw new IllegalStateException("Economy lifecycle failed",services.failure());
        if(services==null||services.state()!=ServerServices.State.READY||++ticks%10!=0)return false;
        var player=server.getPlayerList().getPlayers().getFirst();
        if(!denominationRequested){
            // This fixture owns a fresh world. Select its currency explicitly once; display
            // diagnostics are not an authoritative denomination or activation predicate.
            server.getPlayerList().op(player.getGameProfile());
            server.getCommands().performPrefixedCommand(player.createCommandSourceStack(),"economy choose CAD");
            denominationRequested=true;System.out.println("REFERENCE_FIXTURE_CURRENCY_REQUESTED CAD");return false;
        }
        if(!denominationReady){
            if(denomination==null){denomination=services.tradeStorage(store->store.transaction(tx->ca.emilybelnavis.mcmods.economy.journal.RebaseSettlement.denomination(tx::query).map(value->value.currency()==EconomyCurrency.CAD).orElse(false))).toCompletableFuture();return false;}
            if(!denomination.isDone())return false;
            denominationReady=denomination.join();denomination=null;if(!denominationReady)return false;
            System.out.println("REFERENCE_FIXTURE_CURRENCY_READY CAD");
        }
        if(!TradeRuntime.enabled(server))return false;
        if(work!=null&&!work.isDone())return false;
        switch(phase){
            case 0->{
                if(!ValuationRuntime.status(server).ready())return false;
                server.getPlayerList().op(player.getGameProfile());
                for(int i=0;i<36;i++)player.getInventory().setItem(i,ItemStack.EMPTY);
                player.getInventory().setItem(0,new ItemStack(CashCatalog.byId("economy:cad_bill_100").orElseThrow().item().get()));
                trader=new Villager(EntityType.VILLAGER,player.serverLevel());trader.setNoAi(true);trader.setNoGravity(true);trader.setInvulnerable(true);trader.setPos(player.getX()+1,player.getY(),player.getZ());
                var offers=trader.getOffers();offers.clear();offers.add(new MerchantOffer(new ItemCost(Items.EMERALD,7),new ItemStack(Items.BREAD,6),12,2,0));
                var unsupported=new ItemStack(Items.BOOK);var custom=new CompoundTag();custom.putString("reference_probe","unsupported");unsupported.set(DataComponents.CUSTOM_DATA,CustomData.of(custom));
                offers.add(new MerchantOffer(new ItemCost(Items.EMERALD,3),unsupported,12,2,0));
                player.serverLevel().addFreshEntity(trader);phase=1;
            }
            case 1->{
                // Native interaction owns initial catalog/market/stock installation. A flush alone
                // only checkpoints an already installed trader and must not stand in for bootstrap.
                if(!(player.containerMenu instanceof TraderMenu)){
                    var event=new PlayerInteractEvent.EntityInteract(player,InteractionHand.MAIN_HAND,trader);NeoForge.EVENT_BUS.post(event);
                    require(event.isCanceled(),"Production native bootstrap interception");return false;
                }
                work=TradeRuntime.flush(trader).toCompletableFuture();phase=2;
            }
            case 2->{
                work.join();ReferenceTrading.capture(server,trader);lease=ValuationRuntime.lease(server);if(lease==null)return false;
                var target=new PartyRef(services.worldId(),new PartyRef.Player(new PlayerId(player.getUUID())));
                var grant=PartyAuthorization.inspection(player.createCommandSourceStack(),Economy::serverServices,target).orElseThrow();
                work=services.referenceReview(grant,trader.getUUID(),lease,LIVE).toCompletableFuture();phase=3;
            }
            case 3->{
                review=(ReferenceTrading.Review)work.join();System.out.println("REFERENCE_REGISTRY_COVERAGE "+review.registry().summary());
                require(review.registry().ordinaryComplete(),"Ordinary numeric coverage gaps before activation: "+review.registry().unavailable());
                require(review.offers().stream().anyMatch(row->row.ordinal()==0&&row.available()),"Plain bread reference offer available: "+review.offers());
                require(review.offers().stream().anyMatch(row->row.ordinal()==1&&!row.available()),"Unsupported exact state is retained only in operator review");
                var target=new PartyRef(services.worldId(),new PartyRef.Player(new PlayerId(player.getUUID())));
                var grant=PartyAuthorization.inspection(player.createCommandSourceStack(),Economy::serverServices,target).orElseThrow();
                work=services.activateReference(grant,trader.getUUID(),UUID.randomUUID(),review,lease,LIVE).toCompletableFuture();phase=4;
            }
            case 4->{
                var outcome=(Journal.Outcome)work.join();require(outcome.status()==Journal.Status.COMMITTED,"Authenticated activation committed: "+outcome.result().code());
                var event=new PlayerInteractEvent.EntityInteract(player,InteractionHand.MAIN_HAND,trader);NeoForge.EVENT_BUS.post(event);require(event.isCanceled(),"Production native checkout interception");phase=5;
            }
            case 5->{
                if(player.getInventory().countItem(Items.BREAD)!=6||trader.getOffers().getFirst().getUses()!=1||checking)return false;
                require(player.containerMenu instanceof TraderMenu,"Production trader menu remained open");checking=true;
                work=services.tradeStorage(store->store.transaction(tx->{
                    var rows=tx.query("SELECT payload FROM reference_cash_receipts ORDER BY operation_id LIMIT 2",List.of(),2);require(rows.size()==1,"Exactly one reference receipt");
                    var receipt=ReferenceCashFacts.decodeReceipt(rows.getFirst().get("payload"));var quote=ReferenceCashTrades.read(tx::query,receipt.quote());
                    require(quote!=null&&receipt.goodsPrincipal()==quote.pricing().price().minor(),"Quoted and committed principal agree");
                    require(receipt.goodsPrincipal()==Math.multiplyExact(quote.pricing().price().unitMinor(),quote.pricing().request().goods()),"Published goods unit times quantity");
                    require(quote.pricing().request().basis().minorPerStandard().equals(Rational.of(quote.terms().source().unitMinor()).multiply(Rational.of(quote.pricing().request().standardQuantity()))),"New reference principal is not multiplied by old emerald7 price");
                    require(quoteLines.contains("Goods price: "+money(quote.pricing().request().basis().currency(),receipt.goodsPrincipal()))&&quoteLines.contains("Unit price: "+money(quote.pricing().request().basis().currency(),quote.pricing().price().unitMinor())),"Actual offered review shows quoted unit and total");
                    var progression=StockStore.progression(tx::query,receipt.operation());if(progression.stage()!=StockFacts.Stage.ACKNOWLEDGED)return false;
                    require(tx.query("SELECT operation_id FROM npc_cash_receipts",List.of(),2).isEmpty(),"No EmeraldV1 fallback receipt");
                    require(tx.query("SELECT payload FROM trade_source_active",List.of(),4).size()==2,"Activation covers supported and unsupported original scopes");
                    require(tx.query("SELECT minor FROM activity_observations WHERE operation_id=? AND kind='NPC_RETIRE'",List.of(receipt.operation().toString()),2).stream().anyMatch(row->Long.parseLong(row.get("minor"))==receipt.goodsPrincipal()),"Actual rounded committed principal observed");
                    return true;
                })).toCompletableFuture();phase=6;
            }
            case 6->{
                checking=false;if(!(Boolean)work.join()){phase=5;return false;}
                require(trader.getVillagerXp()==2&&trader.getOffers().getFirst().getUses()==1&&player.getInventory().countItem(Items.EMERALD)==0,"Native progression exactly once without emerald consumption");
                result="PASS authenticated reviewed prospective reference activation; pinned vanilla/actual catalog coverage report; unsupported exact listing quietly omitted; actual offered cash/CAD/quantity/quote/confirm network flow; displayed unit and quoted total equal one committed reference receipt; original emerald7 not used as monetary multiplier; six bread and exact cash/change; saved native progression once; rounded actual activity principal; normal shutdown.";
                System.out.println("REFERENCE_TRADER_PROBE_PASS "+result);
            }
            default->throw new IllegalStateException("Unknown reference probe phase");
        }
        return result!=null||failure!=null;
    }
    private static String money(EconomyCurrency currency,long minor){return currency+" "+BigDecimal.valueOf(minor,currency.fractionDigits()).toPlainString();}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
