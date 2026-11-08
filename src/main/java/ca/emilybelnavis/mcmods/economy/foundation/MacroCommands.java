package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.simulation.MacroPolicyRequests;
import ca.emilybelnavis.mcmods.economy.simulation.macro.MacroControls;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** Player-backed operator controls. Public cached reads cannot schedule or authorize a trade. */
public final class MacroCommands {
    private MacroCommands(){}
    private record Pending(net.minecraft.server.level.ServerPlayer player,AtomicBoolean live){}
    private static final Map<MinecraftServer,List<Pending>> PENDING=new IdentityHashMap<>();
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,Function<MinecraftServer,ServerServices> sessions){
        var root=Commands.literal("macro").requires(EconomyCommands::privileged)
            .then(Commands.literal("status").executes(c->{var svc=sessions.apply(c.getSource().getServer());if(svc==null)return 0;for(var line:svc.macroDiagnostics())c.getSource().sendSuccess(()->Component.literal(line),false);return 1;}))
            .then(Commands.literal("pause").then(Commands.argument("at",LongArgumentType.longArg(0)).executes(c->schedule(c.getSource(),sessions,MacroPolicyRequests.mode(LongArgumentType.getLong(c,"at"),MacroControls.Mode.PAUSED)))))
            .then(Commands.literal("resume").then(Commands.argument("at",LongArgumentType.longArg(0)).executes(c->schedule(c.getSource(),sessions,MacroPolicyRequests.mode(LongArgumentType.getLong(c,"at"),MacroControls.Mode.AUTO)))))
            .then(Commands.literal("manual").then(Commands.argument("at",LongArgumentType.longArg(0)).then(Commands.argument("vectors",StringArgumentType.greedyString()).executes(c->{try{return schedule(c.getSource(),sessions,MacroPolicyRequests.manual(LongArgumentType.getLong(c,"at"),StringArgumentType.getString(c,"vectors")));}catch(RuntimeException e){c.getSource().sendFailure(Component.literal("Invalid manual rates: "+safe(e.getMessage())));return 0;}}))))
            .then(Commands.literal("config").then(Commands.argument("at",LongArgumentType.longArg(0)).then(Commands.argument("settings",StringArgumentType.greedyString()).executes(c->{try{return schedule(c.getSource(),sessions,MacroPolicyRequests.configuration(LongArgumentType.getLong(c,"at"),StringArgumentType.getString(c,"settings")));}catch(RuntimeException e){c.getSource().sendFailure(Component.literal("Invalid future configuration: "+safe(e.getMessage())));return 0;}}))))
            .then(Commands.literal("history").then(Commands.argument("beforeDay",LongArgumentType.longArg(0)).executes(c->{var source=c.getSource();var svc=sessions.apply(source.getServer());var player=source.getPlayer();if(svc==null||svc.state()!=ServerServices.State.READY||player==null)return 0;var grant=PartyAuthorization.player(player,sessions,new PartyRef(svc.worldId(),new PartyRef.Player(new PlayerId(player.getUUID()))));if(grant.isEmpty())return 0;svc.simulation().history(grant.get(),LongArgumentType.getLong(c,"beforeDay"),16).whenComplete((rows,error)->{if(!player.hasPermissions(2)||player.server.getPlayerList().getPlayer(player.getUUID())!=player)return;if(error!=null){source.sendFailure(Component.literal("Rate history unavailable."));return;}for(var row:rows)source.sendSuccess(()->Component.literal("Window="+row.window()+" mode="+row.mode()+" revision="+row.policyRevision()+" hash="+row.hash()),false);});return 1;})));
        dispatcher.register(Commands.literal("economy").then(root));
    }
    private static int schedule(CommandSourceStack source,Function<MinecraftServer,ServerServices> sessions,MacroPolicyRequests.Request request){var svc=sessions.apply(source.getServer());var player=source.getPlayer();if(svc==null||svc.state()!=ServerServices.State.READY||player==null){source.sendFailure(Component.literal("Use a connected operator player to schedule rates."));return 0;}var grant=PartyAuthorization.inspection(source,sessions,new PartyRef(svc.worldId(),new PartyRef.Player(new PlayerId(player.getUUID()))));if(grant.isEmpty())return 0;
        var list=PENDING.computeIfAbsent(source.getServer(),k->new ArrayList<>());if(list.size()>=16){source.sendFailure(Component.literal("Rate control queue is full."));return 0;}var live=new AtomicBoolean(true);var pending=new Pending(player,live);list.add(pending);
        svc.scheduleMacro(grant.get(),live,request).whenComplete((policy,error)->{list.remove(pending);if(!live.get()||!player.hasPermissions(2)||player.server.getPlayerList().getPlayer(player.getUUID())!=player)return;if(error!=null){Throwable actual=error;while(actual.getCause()!=null)actual=actual.getCause();source.sendFailure(Component.literal("Rate schedule rejected: "+safe(actual.getMessage())));return;}source.sendSuccess(()->Component.literal("Scheduled "+policy.mode()+" at "+policy.effectiveMillis()+" revision "+policy.revision()+" ("+policy.id()+")."),true);});return 1;
    }
    public static void tick(MinecraftServer server){var list=PENDING.get(server);if(list!=null)for(var p:list)if(!p.player().hasPermissions(2)||!p.player().isAlive()||server.getPlayerList().getPlayer(p.player().getUUID())!=p.player())p.live().set(false);}
    public static void stopping(MinecraftServer server){var list=PENDING.remove(server);if(list!=null)list.forEach(p->p.live().set(false));}
    private static String safe(String text){if(text==null)return "unavailable";return text.replaceAll("[\\r\\n]"," ").substring(0,Math.min(200,text.length()));}
}
