package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.banking.UiText;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.credit.CreditDiagnostics;
import java.util.*;
import java.util.function.Function;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Read-only owner views and player-backed, durably audited operator inspection. */
public final class CreditCommands {
    private CreditCommands() {}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,Function<MinecraftServer,ServerServices> sessions){
        var position=Commands.argument("position",IntegerArgumentType.integer(-1,63))
            .executes(c->inspect(c,sessions,StringArgumentType.getString(c,"section"),cursor(c),0))
            .then(Commands.argument("allocation_offset",IntegerArgumentType.integer(0,260))
                .executes(c->inspect(c,sessions,StringArgumentType.getString(c,"section"),cursor(c),IntegerArgumentType.getInteger(c,"allocation_offset"))));
        var section=Commands.argument("section",StringArgumentType.word())
            .suggests((c,b)->{for(var value:List.of("daily","payments","events"))b.suggest(value);return b.buildFuture();})
            .executes(c->inspect(c,sessions,StringArgumentType.getString(c,"section"),CreditDiagnostics.EventCursor.start(),0))
            .then(Commands.literal("after").then(Commands.argument("sequence",LongArgumentType.longArg(0)).then(position)));
        var facility=Commands.argument("facility",UuidArgument.uuid())
            .executes(c->inspect(c,sessions,"daily",CreditDiagnostics.EventCursor.start(),0)).then(section);
        var inspect=Commands.literal("inspect").requires(s->authenticated(s)&&EconomyCommands.privileged(s))
            .then(Commands.argument("owner",UuidArgument.uuid()).then(facility));
        dispatcher.register(Commands.literal("economy").then(Commands.literal("credit")
            .requires(CreditCommands::authenticated)
            .then(Commands.literal("facility").then(Commands.argument("facility",UuidArgument.uuid()).executes(c->owner(c,sessions))))
            .then(inspect)));
    }
    private static CreditDiagnostics.EventCursor cursor(CommandContext<CommandSourceStack> c){return new CreditDiagnostics.EventCursor(LongArgumentType.getLong(c,"sequence"),IntegerArgumentType.getInteger(c,"position"));}
    private static boolean authenticated(CommandSourceStack source){return source.getPlayer()!=null&&source.source==source.getPlayer();}
    private record Access(ServerServices services,ServerPlayer player,PartyAuthorization.Grant grant) {}
    private static Access access(CommandSourceStack source,Function<MinecraftServer,ServerServices> sessions,UUID target,boolean inspection){
        if(!authenticated(source))return null;
        var services=sessions.apply(source.getServer());if(services==null||services.partyWorld()==null)return null;
        var player=source.getPlayer();var owner=new PartyRef(services.partyWorld(),new PartyRef.Player(new PlayerId(target==null?player.getUUID():target)));
        var grant=inspection?PartyAuthorization.inspection(source,sessions,owner):PartyAuthorization.player(player,sessions,owner);
        return grant.map(g->new Access(services,player,g)).orElse(null);
    }
    private static boolean live(CommandSourceStack source,Function<MinecraftServer,ServerServices> sessions,Access access,boolean inspection){
        return sessions.apply(source.getServer())==access.services()&&source.getServer().getPlayerList().getPlayer(access.player().getUUID())==access.player()
            &&(!inspection||PartyAuthorization.inspection(source,sessions,access.grant().target()).isPresent());
    }
    private static int owner(CommandContext<CommandSourceStack> context,Function<MinecraftServer,ServerServices> sessions){
        var source=context.getSource();var access=access(source,sessions,null,false);if(access==null)return unavailable(source);
        access.services().ledger().creditFacilityView(access.grant(),UuidArgument.getUuid(context,"facility")).whenComplete((found,error)->{
            if(!live(source,sessions,access,false))return;
            if(error!=null||found.isEmpty()){unavailable(source);return;}
            CreditReport.owner(found.orElseThrow()).forEach(line->send(source,line));
        });return 1;
    }
    private static int inspect(CommandContext<CommandSourceStack> context,Function<MinecraftServer,ServerServices> sessions,String section,CreditDiagnostics.EventCursor cursor,int offset){
        var source=context.getSource();if(!Set.of("daily","payments","events").contains(section)||(offset!=0&&!section.equals("payments")))return unavailable(source);
        var access=access(source,sessions,UuidArgument.getUuid(context,"owner"),true);if(access==null)return unavailable(source);
        access.services().ledger().inspectCreditFacility(access.grant(),UuidArgument.getUuid(context,"facility"),cursor,section.equals("payments")?1:4,
                ()->live(source,sessions,access,true)).whenComplete((found,error)->{
            if(!live(source,sessions,access,true))return;
            if(error!=null||found.isEmpty()){unavailable(source);return;}
            try{CreditReport.operator(found.orElseThrow(),section,offset).forEach(line->send(source,line));}
            catch(IllegalArgumentException invalid){unavailable(source);}
        });return 1;
    }
    private static void send(CommandSourceStack source,String line){source.sendSuccess(()->UiText.component(source, line),false);}
    private static int unavailable(CommandSourceStack source){source.sendFailure(UiText.component(source, UiText.t("ui.dialogue.credit_diagnostics_unavailable_for_this_sele.a0c34a")));return 0;}
}
