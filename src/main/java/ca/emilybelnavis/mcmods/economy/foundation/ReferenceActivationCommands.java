package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.trade.pricing.ReferenceTrading;
import ca.emilybelnavis.mcmods.economy.valuation.ValuationRuntime;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.AbstractVillager;

/** Two-step, player-backed operator publication of an exact reviewed trader scope. */
public final class ReferenceActivationCommands {
    private ReferenceActivationCommands(){}
    private record Pending(ServerPlayer player,AtomicBoolean live){}
    private record Reviewed(UUID player,ReferenceTrading.Review review,ValuationRuntime.Lease lease){}
    private static final Map<MinecraftServer,List<Pending>> PENDING=new IdentityHashMap<>();
    private static final Map<MinecraftServer,Map<UUID,Reviewed>> REVIEWS=new IdentityHashMap<>();
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,Function<MinecraftServer,ServerServices> sessions){
        var trader=Commands.argument("trader",StringArgumentType.word()).executes(c->review(c.getSource(),sessions,StringArgumentType.getString(c,"trader")));
        var activate=Commands.argument("trader",StringArgumentType.word()).then(Commands.argument("review",StringArgumentType.word()).executes(c->activate(c.getSource(),sessions,StringArgumentType.getString(c,"trader"),StringArgumentType.getString(c,"review"))));
        dispatcher.register(Commands.literal("economy").then(Commands.literal("valuation").requires(EconomyCommands::privileged)
            .then(Commands.literal("review").then(trader)).then(Commands.literal("activate").then(activate))));
    }
    private record Admission(ServerServices services,ServerPlayer player,PartyAuthorization.Grant grant,Pending pending){}
    private static Admission admit(CommandSourceStack source,Function<MinecraftServer,ServerServices> sessions){
        var server=source.getServer();var player=source.getPlayer();var services=sessions.apply(server);
        if(player==null||source.source!=player||services==null||services.state()!=ServerServices.State.READY||!EconomyCommands.privileged(source)||server.getPlayerList().getPlayer(player.getUUID())!=player)throw new SecurityException("Connected ready operator required");
        var list=PENDING.computeIfAbsent(server,k->new ArrayList<>());if(list.size()>=4)throw new IllegalStateException("Reference operator queue full");
        var grant=PartyAuthorization.inspection(source,sessions,new PartyRef(services.worldId(),new PartyRef.Player(new PlayerId(player.getUUID())))).orElseThrow();
        var pending=new Pending(player,new AtomicBoolean(true));list.add(pending);return new Admission(services,player,grant,pending);
    }
    private static int review(CommandSourceStack source,Function<MinecraftServer,ServerServices> sessions,String text){
        Admission admission=null;
        try{
            UUID trader=UUID.fromString(text);admission=admit(source,sessions);var server=source.getServer();
            AbstractVillager found=null;for(var level:server.getAllLevels())if(level.getEntity(trader) instanceof AbstractVillager entity){found=entity;break;}
            if(found==null)throw new IllegalArgumentException("Load the captured trader before reviewing");
            ReferenceTrading.capture(server,found);var lease=ValuationRuntime.lease(server);if(lease==null)throw new IllegalStateException("Reference snapshot not ready");
            var a=admission;a.services().referenceReview(a.grant(),trader,lease,a.pending().live()).whenComplete((review,error)->{
                done(server,a.pending());if(!valid(server,a))return;
                if(error!=null){source.sendFailure(Component.literal("Reference coverage unavailable; inspect valuation readiness."));return;}
                var saved=REVIEWS.computeIfAbsent(server,k->new LinkedHashMap<>());saved.put(trader,new Reviewed(a.player().getUUID(),review,lease));while(saved.size()>16)saved.remove(saved.keySet().iterator().next());
                send(source,review.registry().summary());for(var offer:review.offers())send(source,"Offer "+offer.ordinal()+" "+offer.item()+" "+offer.reason());
                review.registry().unavailable().entrySet().stream().limit(16).forEach(e->send(source,"Numeric gap "+e.getKey()+" "+e.getValue()));
                if(review.registry().ordinaryComplete())send(source,"Reviewed snapshot="+review.snapshot()+". Publish with /economy valuation activate "+trader+" "+review.hash());
                else source.sendFailure(Component.literal("Resolve numeric coverage gaps before activation."));
            });return 1;
        }catch(RuntimeException error){if(admission!=null)done(source.getServer(),admission.pending());source.sendFailure(Component.literal("Reference review refused: "+safe(error.getMessage())));return 0;}
    }
    private static int activate(CommandSourceStack source,Function<MinecraftServer,ServerServices> sessions,String text,String hash){
        Admission admission=null;
        try{
            UUID trader=UUID.fromString(text);var server=source.getServer();admission=admit(source,sessions);
            var reviewed=REVIEWS.getOrDefault(server,Map.of()).get(trader);
            if(reviewed==null||!reviewed.player().equals(admission.player().getUUID())||!reviewed.review().hash().equals(hash)||!ValuationRuntime.matches(server,reviewed.lease()))throw new IllegalArgumentException("Run a fresh coverage review first");
            var a=admission;a.services().activateReference(a.grant(),trader,UUID.randomUUID(),reviewed.review(),reviewed.lease(),a.pending().live()).whenComplete((result,error)->{
                done(server,a.pending());if(!valid(server,a))return;
                if(error!=null||result.status()!=ca.emilybelnavis.mcmods.economy.journal.Journal.Status.COMMITTED){source.sendFailure(Component.literal("Reference activation refused; review current coverage and readiness."));return;}
                REVIEWS.getOrDefault(server,Map.of()).remove(trader);send(source,"Reference pricing activated prospectively for the reviewed trader scopes. Reopen trading.");
            });return 1;
        }catch(RuntimeException error){if(admission!=null)done(source.getServer(),admission.pending());source.sendFailure(Component.literal("Reference activation refused: "+safe(error.getMessage())));return 0;}
    }
    private static boolean valid(MinecraftServer server,Admission a){return a.pending().live().get()&&server.getPlayerList().getPlayer(a.player().getUUID())==a.player()&&EconomyCommands.privileged(a.player().createCommandSourceStack());}
    private static void done(MinecraftServer server,Pending pending){var list=PENDING.get(server);if(list!=null)list.remove(pending);}
    public static void tick(MinecraftServer server){var list=PENDING.get(server);if(list!=null)for(var p:list)if(server.getPlayerList().getPlayer(p.player().getUUID())!=p.player()||!EconomyCommands.privileged(p.player().createCommandSourceStack()))p.live().set(false);}
    public static void stopping(MinecraftServer server){var list=PENDING.remove(server);if(list!=null)list.forEach(p->p.live().set(false));REVIEWS.remove(server);}
    private static void send(CommandSourceStack source,String text){source.sendSuccess(()->Component.literal(text),false);}
    private static String safe(String text){if(text==null)return "not ready";return text.replaceAll("[\\r\\n]"," ").substring(0,Math.min(160,text.length()));}
}
