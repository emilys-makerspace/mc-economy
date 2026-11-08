package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.banking.UiText;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.loan.LoanDiagnostics;
import java.util.*;
import java.util.function.Function;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Read-only owner diagnostics and player-backed, durably audited operator inspection. */
public final class LoanCommands {
    private LoanCommands() {}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,Function<MinecraftServer,ServerServices> sessions){
        var detail=Commands.argument("loan",UuidArgument.uuid()).executes(c->owner(c,sessions,"contract",0));
        for(String section:List.of("schedule","installments"))detail.then(Commands.literal(section)
            .executes(c->owner(c,sessions,section,0))
            .then(Commands.argument("after",IntegerArgumentType.integer(0,104)).executes(c->owner(c,sessions,section,IntegerArgumentType.getInteger(c,"after")))));
        detail.then(Commands.literal("payoff").then(Commands.argument("funding",UuidArgument.uuid()).executes(c->owner(c,sessions,"payoff",0))));
        var inspect=Commands.literal("inspect").requires(s->authenticated(s)&&EconomyCommands.privileged(s))
            .then(Commands.argument("owner",UuidArgument.uuid()).then(Commands.argument("loan",UuidArgument.uuid())
                .executes(c->inspect(c,sessions,LoanDiagnostics.EventCursor.start()))
                .then(Commands.literal("after").then(Commands.argument("sequence",LongArgumentType.longArg(0))
                    .then(Commands.argument("position",IntegerArgumentType.integer(-1,63)).executes(c->inspect(c,sessions,
                        new LoanDiagnostics.EventCursor(LongArgumentType.getLong(c,"sequence"),IntegerArgumentType.getInteger(c,"position")))))))));
        var application=Commands.literal("inspect_application").requires(s->authenticated(s)&&EconomyCommands.privileged(s))
            .then(Commands.argument("owner",UuidArgument.uuid()).then(Commands.argument("application",UuidArgument.uuid()).executes(c->decision(c,sessions,true))));
        dispatcher.register(Commands.literal("economy").then(Commands.literal("loan").requires(LoanCommands::authenticated)
            .then(Commands.literal("list").executes(c->list(c,sessions,null))
                .then(Commands.argument("after",UuidArgument.uuid()).executes(c->list(c,sessions,UuidArgument.getUuid(c,"after")))))
            .then(Commands.literal("contract").then(detail))
            .then(Commands.literal("application").then(Commands.argument("application",UuidArgument.uuid()).executes(c->decision(c,sessions,false))))
            .then(inspect).then(application)));
    }
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
    private static int list(CommandContext<CommandSourceStack> c,Function<MinecraftServer,ServerServices> sessions,UUID after){
        var source=c.getSource();var a=access(source,sessions,null,false);if(a==null)return unavailable(source);
        a.services().ledger().loanPage(a.grant(),after,4).whenComplete((found,error)->{
            if(!live(source,sessions,a,false))return;
            if(error!=null||found.isEmpty()){unavailable(source);return;}
            var page=found.orElseThrow();
            for(var v:page.entries())send(source,v.contract().id()+" / "+v.contract().lender().key()+" / "+v.contract().currency().code()+" / "+loanStatus(v.contract().status()));
            if(page.entries().isEmpty())send(source,UiText.t("ui.dialogue.no_loans_on_this_page.69b8ca"));
            if(page.nextCursor()!=null)send(source,UiText.t("ui.dialogue.next_economy_loan_list.3c28b3")+page.nextCursor());
        });return 1;
    }
    private static int owner(CommandContext<CommandSourceStack> c,Function<MinecraftServer,ServerServices> sessions,String section,int after){
        var source=c.getSource();var a=access(source,sessions,null,false);if(a==null)return unavailable(source);
        var ledger=a.services().ledger();var id=UuidArgument.getUuid(c,"loan");
        switch(section){
            case "schedule"->ledger.loanSchedulePage(a.grant(),id,after,4).whenComplete((found,error)->{
                if(!live(source,sessions,a,false))return;
                if(error!=null||found.isEmpty()){unavailable(source);return;}
                LoanReport.schedule(found.orElseThrow()).forEach(line->send(source,line));
            });
            case "installments"->ledger.loanInstallments(a.grant(),id,after,4).whenComplete((found,error)->{
                if(!live(source,sessions,a,false))return;
                if(error!=null||found.isEmpty()){unavailable(source);return;}
                LoanReport.installments(found.orElseThrow()).forEach(line->send(source,line));
            });
            case "payoff"->ledger.quoteLoanRepayment(a.grant(),id,new AccountId(UuidArgument.getUuid(c,"funding")),0,true).whenComplete((found,error)->{
                if(!live(source,sessions,a,false))return;
                if(error!=null||found.isEmpty()){unavailable(source);return;}
                LoanReport.payoff(found.orElseThrow()).forEach(line->send(source,line));
            });
            default->ledger.loanView(a.grant(),id).whenComplete((found,error)->{
                if(!live(source,sessions,a,false))return;
                if(error!=null||found.isEmpty()){unavailable(source);return;}
                LoanReport.owner(found.orElseThrow()).forEach(line->send(source,line));
            });
        }return 1;
    }
    private static int decision(CommandContext<CommandSourceStack> c,Function<MinecraftServer,ServerServices> sessions,boolean inspection){
        var source=c.getSource();var a=access(source,sessions,inspection?UuidArgument.getUuid(c,"owner"):null,inspection);if(a==null)return unavailable(source);
        var id=UuidArgument.getUuid(c,"application");
        var result=inspection?a.services().ledger().inspectLoanApplication(a.grant(),id,()->live(source,sessions,a,true)):a.services().ledger().loanApplicationDecision(a.grant(),id);
        result.whenComplete((found,error)->{
            if(!live(source,sessions,a,inspection))return;
            if(error!=null||found.isEmpty()){unavailable(source);return;}
            LoanReport.application(found.orElseThrow()).forEach(line->send(source,line));
        });return 1;
    }
    private static int inspect(CommandContext<CommandSourceStack> c,Function<MinecraftServer,ServerServices> sessions,LoanDiagnostics.EventCursor cursor){
        var source=c.getSource();var a=access(source,sessions,UuidArgument.getUuid(c,"owner"),true);if(a==null)return unavailable(source);
        a.services().ledger().inspectLoan(a.grant(),UuidArgument.getUuid(c,"loan"),cursor,2,()->live(source,sessions,a,true)).whenComplete((found,error)->{
            if(!live(source,sessions,a,true))return;
            if(error!=null||found.isEmpty()){unavailable(source);return;}
            LoanReport.operator(found.orElseThrow()).forEach(line->send(source,line));
        });return 1;
    }
    private static void send(CommandSourceStack source,String line){source.sendSuccess(()->UiText.component(source, line),false);}
    private static int unavailable(CommandSourceStack source){
        source.sendFailure(UiText.component(source, UiText.t("ui.dialogue.loan_diagnostics_unavailable_for_this_select.1462fc")));return 0;
    }
    private static String loanStatus(ca.emilybelnavis.mcmods.economy.loan.Loans.Status status) { return switch(status) {
        case ACTIVE -> UiText.t("ui.dialogue.loan_status.active");
        case DEFAULTED -> UiText.t("ui.dialogue.loan_status.defaulted");
        case SETTLED -> UiText.t("ui.dialogue.loan_status.settled");
    }; }
}
