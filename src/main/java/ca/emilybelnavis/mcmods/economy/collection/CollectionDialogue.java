package ca.emilybelnavis.mcmods.economy.collection;

import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.debt.*;
import ca.emilybelnavis.mcmods.economy.foundation.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.banking.BankingSaves;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import net.minecraft.nbt.CompoundTag;
import java.math.BigDecimal;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

/** Server-owned case/funding choices and original payment intents for a live debtor dialogue. */
public final class CollectionDialogue {
    private static final String RECOVERY="economy_collection_payment";
    private static final Map<CollectionMenu,State> STATES=new WeakHashMap<>();
    private static final Map<MinecraftServer,Map<UUID,Pending>> PENDING=new WeakHashMap<>();
    private CollectionDialogue() {}
    private static final class State {
        final ServerServices services;
        final UUID serverSession;
        final Map<UUID,DebtRecords.Case> offeredCases=new LinkedHashMap<>();
        final Map<UUID,AccountIdentity> offeredFunding=new LinkedHashMap<>();
        UUID nextCase;
        String nextFunding;
        DebtRecords.Case selected;
        AccountIdentity funding;
        DebtPayments.Quote nativeQuote;
        DebtPayments.FxQuote fxQuote;
        State(ServerServices services){this.services=services;serverSession=services.sessionId();}
        void clearQuote(){nativeQuote=null;fxQuote=null;}
    }
    private static final class Pending {
        final UUID serverSession;
        final OperationId operation=new OperationId(UUID.randomUUID());
        final DebtPayments.Quote nativeQuote;
        final DebtPayments.FxQuote fxQuote;
        final EconomyCurrency currency;
        boolean inFlight,markerSaved;
        Pending(State state){serverSession=state.serverSession;nativeQuote=state.nativeQuote;fxQuote=state.fxQuote;currency=state.selected.currency();}
        long amount(){return nativeQuote!=null?nativeQuote.amountMinor():fxQuote.payment().amountMinor();}
    }
    private static final Map<MinecraftServer,Integer> RECOVERY_CURSORS=new IdentityHashMap<>();
    private static final Map<MinecraftServer,Set<UUID>> RECOVERY_READS=new IdentityHashMap<>();
    /** Resolve saved receipts even when full settlement removed the last representative. */
    public static void tick(MinecraftServer server) {
        var services=Economy.serverServices(server);var players=server.getPlayerList().getPlayers();
        if(services==null||services.state()!=ServerServices.State.READY||players.isEmpty())return;
        int cursor=RECOVERY_CURSORS.getOrDefault(server,0);RECOVERY_CURSORS.put(server,cursor+1);
        var player=players.get(Math.floorMod(cursor,players.size()));var pending=pending(player);
        if(player.containerMenu instanceof CollectionMenu||pending!=null&&pending.inFlight||!player.getPersistentData().contains(RECOVERY))return;
        var marker=player.getPersistentData().getCompound(RECOVERY);
        if(!marker.hasUUID("world")||!marker.hasUUID("operation")||!marker.getUUID("world").equals(services.worldId().value()))return;
        var reads=RECOVERY_READS.computeIfAbsent(server,s->new HashSet<>());if(!reads.add(player.getUUID()))return;
        var owner=new PartyRef(services.worldId(),new PartyRef.Player(new PlayerId(player.getUUID())));
        var granted=PartyAuthorization.player(player,Economy::serverServices,owner);
        if(granted.isEmpty()){reads.remove(player.getUUID());return;}
        var operation=new OperationId(marker.getUUID("operation"));
        services.ledger().lookupCollectionPaymentReceipt(granted.orElseThrow(),operation).whenComplete((found,error)->server.execute(()->{
            reads.remove(player.getUUID());
            if(error!=null||Economy.serverServices(server)!=services||server.getPlayerList().getPlayer(player.getUUID())!=player
                    ||found.isPresent()&&found.orElseThrow().status()==Journal.Status.UNKNOWN)return;
            var current=player.getPersistentData().getCompound(RECOVERY);
            if(!current.hasUUID("operation")||!current.getUUID("operation").equals(operation.value()))return;
            var book=PENDING.get(server);if(book!=null)book.remove(player.getUUID(),pending);
            clearMarker(player,operation);
            player.displayClientMessage(UiText.component(player, found.isPresent()&&found.orElseThrow().status()==Journal.Status.COMMITTED
                ?UiText.t("ui.dialogue.saved_collection_payment_receipt_recovered.ef5286"):UiText.t("ui.dialogue.saved_payment_resolved_review_current_balanc.1b5668")),false);
        }));
    }
    public static void open(ServerPlayer player,CollectionAgent entity){
        if(!CollectionAgents.canInteract(player,entity))return;
        var services=Economy.serverServices(player.server);if(services==null||services.state()!=ServerServices.State.READY)return;
        var holder=new CollectionMenu[1];UUID session=UUID.randomUUID();
        player.openMenu(new SimpleMenuProvider((id,inventory,p)->holder[0]=new CollectionMenu(id,inventory,entity.getUUID(),entity.presentation(),session),UiText.component(player, UiText.t("ui.dialogue.waystone_collections.2b442b"))),b->holder[0].writeOpening(b));
        if(holder[0]==null)return;
        var state=new State(services);STATES.put(holder[0],state);
        var pending=pending(player);
        if(pending!=null&&pending.serverSession.equals(state.serverSession))pendingPage(holder[0],pending,UiText.t("ui.dialogue.recover_the_original_payment_before_starting.5e108c"));
        else if(player.getPersistentData().contains(RECOVERY))recoverMarker(player,holder[0],state);
        else cases(player,holder[0],state,null);
    }
    public static void closed(CollectionMenu menu){STATES.remove(menu);}
    public static void stop(MinecraftServer server){RECOVERY_CURSORS.remove(server);RECOVERY_READS.remove(server);
        PENDING.remove(server);
        STATES.entrySet().removeIf(entry->entry.getKey().player() instanceof ServerPlayer player&&player.server==server);
    }
    private static Pending pending(ServerPlayer player){return PENDING.computeIfAbsent(player.server,server->new HashMap<>()).get(player.getUUID());}
    private static PartyAuthorization.Grant grant(ServerPlayer player,State state){
        if(Economy.serverServices(player.server)!=state.services||!state.services.sessionId().equals(state.serverSession))throw new IllegalArgumentException("Collection session changed");
        var world=state.services.worldId();if(world==null)throw new IllegalArgumentException("Collection world unavailable");
        return PartyAuthorization.player(player,Economy::serverServices,new PartyRef(world,new PartyRef.Player(new PlayerId(player.getUUID())))).orElseThrow();
    }
    private static boolean live(ServerPlayer player,CollectionMenu menu,State state){return STATES.get(menu)==state&&Economy.serverServices(player.server)==state.services&&state.services.sessionId().equals(state.serverSession)&&menu.valid(player);}
    public static void action(ServerPlayer player,CollectionMenu menu,String action,String value){
        var state=STATES.get(menu);if(state==null||!live(player,menu,state))return;
        if(action.equals("exit")){player.closeContainer();return;}
        var pending=pending(player);
        if(pending!=null){if(action.equals("retry"))submit(player,menu,state,pending);return;}
        try {
            switch(action){
                case "retry"->recoverMarker(player,menu,state);
                case "cases"->cases(player,menu,state,null);
                case "cases_next"->cases(player,menu,state,state.nextCase);
                case "funding"->funding(player,menu,state,null);
                case "funding_next"->funding(player,menu,state,state.nextFunding);
                case "select_case"->{state.selected=state.offeredCases.get(UUID.fromString(value));state.clearQuote();payment(menu,state,UiText.t("ui.dialogue.choose_payment_amount_or_full_balance.b95684"));}
                case "select_funding"->{state.funding=state.offeredFunding.get(UUID.fromString(value));state.clearQuote();payment(menu,state,UiText.t("ui.dialogue.funding_selected_preview_before_confirming.1866b9"));}
                case "preview_partial"->preview(player,menu,state,parseAmount(value,state.selected.currency()));
                case "preview_full"->preview(player,menu,state,state.selected.debtMinor());
                case "confirm"->confirm(player,menu,state);
                default->{}
            }
        }catch(RuntimeException unavailable){failure(menu,UiText.t("ui.dialogue.selection_unavailable_refresh_cases_and_fund.d9fe71"));}
    }
    private static void cases(ServerPlayer player,CollectionMenu menu,State state,UUID after){
        state.clearQuote();menu.publish("cases",List.of(),UiText.t("ui.dialogue.loading_eligible_cases.756d2d"),true,List.of());
        state.services.ledger().collectionAgentState(grant(player,state),after,4).whenComplete((found,error)->player.server.execute(()->{
            if(!live(player,menu,state))return;
            if(error!=null||found.isEmpty()){failure(menu,UiText.t("ui.dialogue.cases_unavailable_while_financial_work_is_pe.7067cd"));return;}
            if(menu.managed()&&(found.orElseThrow().state().phase()!=CollectionPresence.Phase.LIVE||!found.orElseThrow().state().lease().equals(menu.presentation()))){player.closeContainer();return;}
            state.nextCase=found.orElseThrow().nextCursor();state.offeredCases.clear();
            var lines=new ArrayList<String>();var choices=new ArrayList<CollectionNetwork.Choice>();long now=state.services.financialMillis();
            for(var entry:found.orElseThrow().cases()){
                var debt=entry;if(!DebtLifecycle.demands(debt,now))continue;state.offeredCases.put(debt.id(),debt);
                lines.add(kind(debt.kind())+" / "+creditor(debt));lines.add(UiText.t("ui.dialogue.remaining.cd8e27")+money(debt.currency(),debt.debtMinor()));
                choices.add(choice("select_case",debt.id().toString(),kind(debt.kind())+" · "+money(debt.currency(),debt.debtMinor()),state.selected!=null&&state.selected.id().equals(debt.id())));
            }
            if(lines.isEmpty())lines.add(UiText.t("ui.dialogue.no_eligible_cases_on_this_page.29dec2"));
            if(state.nextCase!=null)choices.add(choice("cases_next","",UiText.t("ui.dialogue.next_cases.0d3676"),false));
            choices.add(choice("funding","",UiText.t("ui.dialogue.funding_accounts.3ed457"),false));
            menu.publish("cases",lines,UiText.t("ui.dialogue.select_the_balance_to_discuss.88a401"),false,choices);
        }));
    }
    private static void funding(ServerPlayer player,CollectionMenu menu,State state,String cursor){
        state.clearQuote();menu.publish("funding",List.of(),UiText.t("ui.dialogue.loading_owned_bank_funding.48bbc5"),true,List.of());
        state.services.listOwnedAccounts(grant(player,state),cursor,4).whenComplete((found,error)->player.server.execute(()->{
            if(!live(player,menu,state))return;
            if(error!=null||found.isEmpty()){failure(menu,UiText.t("ui.dialogue.funding_unavailable_while_financial_work_is_.170025"));return;}
            state.nextFunding=found.orElseThrow().nextCursor();state.offeredFunding.clear();var choices=new ArrayList<CollectionNetwork.Choice>();
            for(var account:found.orElseThrow().accounts())if(account.status()==AccountIdentity.Status.OPEN&&account.purpose()==AccountIdentity.Purpose.BANK){
                state.offeredFunding.put(account.id().value(),account);
                choices.add(choice("select_funding",account.id().value().toString(),account.currency().code()+" · "+(account.primary()?UiText.t("ui.dialogue.primary.a9a96e"):UiText.t("ui.dialogue.bank.00f57e"))+" · "+account.id().value().toString().substring(0,8),state.funding!=null&&state.funding.id().equals(account.id())));
            }
            if(state.nextFunding!=null)choices.add(choice("funding_next","",UiText.t("ui.dialogue.next_funding_accounts.326a4f"),false));
            choices.add(choice("cases","",UiText.t("ui.dialogue.collection_cases.6d2afa"),false));
            menu.publish("funding",List.of(UiText.t("ui.dialogue.choose_an_owned_open_bank_account.6117af"),UiText.t("ui.dialogue.a_different_currency_requires_an_explicit_fx.3d5d1f")),UiText.t("ui.dialogue.no_debit_occurs_when_choosing_funding.425b9d"),false,choices);
        }));
    }
    private static void payment(CollectionMenu menu,State state,String status){
        var lines=new ArrayList<String>();lines.add(UiText.t("ui.dialogue.amount_input_help"));var choices=new ArrayList<CollectionNetwork.Choice>();
        if(state.selected!=null){lines.add(kind(state.selected.kind())+" / "+creditor(state.selected));lines.add(UiText.t("ui.dialogue.debt.0e6f7a")+money(state.selected.currency(),state.selected.debtMinor()));}
        if(state.funding!=null)lines.add(UiText.t("ui.dialogue.funding.a2a66d")+state.funding.currency().code()+" / "+state.funding.id().value().toString().substring(0,8));
        choices.add(choice("cases","",UiText.t("ui.dialogue.choose_case.f81e0b"),false));choices.add(choice("funding","",UiText.t("ui.dialogue.choose_funding.1eb047"),false));
        if(state.selected!=null&&state.funding!=null){choices.add(choice("preview_partial","",UiText.t("ui.dialogue.preview_partial_payment.dd83b4"),false));choices.add(choice("preview_full","",UiText.t("ui.dialogue.preview_full_balance.533159"),false));}
        menu.publish("payment",lines,status,false,choices);
    }
    public static long parseAmount(String text,EconomyCurrency currency){
        if(text==null||!text.matches("(?:0|[1-9][0-9]{0,18})(?:\\.[0-9]{1,2})?"))throw new IllegalArgumentException("Positive decimal amount required");
        long minor=new BigDecimal(text).multiply(BigDecimal.valueOf(currency.minorUnitsPerMajor())).longValueExact();
        if(minor<=0)throw new IllegalArgumentException("Positive representable amount required");return minor;
    }
    private static void preview(ServerPlayer player,CollectionMenu menu,State state,long amount){
        if(state.selected==null||state.funding==null||amount>state.selected.debtMinor()||!DebtLifecycle.demands(state.selected,state.services.financialMillis()))throw new IllegalArgumentException("Collection selection stale");
        state.clearQuote();menu.publish("payment",List.of(),UiText.t("ui.dialogue.preparing_payment_review.3c259b"),true,List.of());
        if(state.funding.currency()==state.selected.currency()) {
            state.services.ledger().quoteCollectionPayment(grant(player,state),state.selected.id(),state.funding.id(),amount).whenComplete((found,error)->player.server.execute(()->{
                if(!live(player,menu,state))return;
                if(error!=null||found.isEmpty()){payment(menu,state,UiText.t("ui.dialogue.quote_unavailable_refresh_case_and_funding.17d381"));return;}
                var quote=found.orElseThrow();
                if(quote.caseRevision()!=state.selected.revision()||quote.fundingRevision()!=state.funding.revision()){
                    state.selected=null;state.funding=null;cases(player,menu,state,null);return;
                }
                state.nativeQuote=quote;review(menu,state);
            }));
        }else {
            state.services.ledger().quoteCollectionPaymentFx(grant(player,state),state.selected.id(),state.funding.id(),amount).whenComplete((found,error)->player.server.execute(()->{
                if(!live(player,menu,state))return;
                if(error!=null){payment(menu,state,UiText.t("ui.dialogue.fx_quote_unavailable_refresh_case_and_fundin.0f8123"));return;}
                if(found.payment().caseRevision()!=state.selected.revision()||found.payment().fundingRevision()!=state.funding.revision()){
                    state.selected=null;state.funding=null;cases(player,menu,state,null);return;
                }
                state.fxQuote=found;review(menu,state);
            }));
        }
    }
    private static long expires(State state){return state.nativeQuote!=null?state.nativeQuote.expiresAt():Math.min(state.fxQuote.payment().expiresAt(),state.fxQuote.fx().expires().toEpochMilli());}
    private static void review(CollectionMenu menu,State state){
        var lines=new ArrayList<String>();lines.add(UiText.t("ui.dialogue.current_creditor.f20a66")+creditor(state.selected));
        if(state.nativeQuote!=null){lines.add(UiText.t("ui.dialogue.pay.f5b9f7")+money(state.selected.currency(),state.nativeQuote.amountMinor()));lines.add(UiText.t("ui.dialogue.fee_0.78779a")+state.selected.currency().code());}
        else {
            var fx=state.fxQuote.fx();lines.add(UiText.t("ui.dialogue.debt_receives.6cc82c")+money(state.selected.currency(),state.fxQuote.payment().amountMinor()));
            lines.add(UiText.t("ui.dialogue.converted_debit.d89cfc")+money(fx.debit().currency(),fx.debit().minorUnits()));lines.add(UiText.t("ui.dialogue.separate_fx_fee.cc4240")+money(fx.fee().currency(),fx.fee().minorUnits()));
            lines.add(UiText.t("ui.dialogue.total_funding_debit.110f3a")+money(fx.debit().currency(),Math.addExact(fx.debit().minorUnits(),fx.fee().minorUnits())));
        }
        lines.add(UiText.t("ui.dialogue.quote_expires.9a6460")+java.time.Instant.ofEpochMilli(expires(state)));lines.add(UiText.t("ui.dialogue.positive_partial_payment_renews_the_collecti.1b71be"));
        lines.add(UiText.t("ui.dialogue.full_payment_settles_the_selected_case.bde6e0"));
        menu.publish("review",lines,UiText.t("ui.dialogue.review_the_exact_debit_then_confirm.69f8f6"),false,List.of(choice("confirm","",UiText.t("ui.dialogue.confirm_payment.5febc1"),false),choice("cases","",UiText.t("ui.dialogue.choose_case.f81e0b"),false),choice("funding","",UiText.t("ui.dialogue.choose_funding.1eb047"),false)));
    }
    private static void confirm(ServerPlayer player,CollectionMenu menu,State state){
        if(state.nativeQuote==null&&state.fxQuote==null)throw new IllegalArgumentException("No reviewed quote");
        if(state.services.financialMillis()>=expires(state)){state.clearQuote();payment(menu,state,UiText.t("ui.dialogue.quote_expired_preview_again.86dab6"));return;}
        var pendingByPlayer=PENDING.computeIfAbsent(player.server,server->new HashMap<>());
        if(pendingByPlayer.size()>=256&&!pendingByPlayer.containsKey(player.getUUID())){failure(menu,UiText.t("ui.dialogue.payment_recovery_is_busy_try_again_later.9f9496"));return;}
        var pending=new Pending(state);pendingByPlayer.put(player.getUUID(),pending);state.clearQuote();submit(player,menu,state,pending);
    }
    private static void pendingPage(CollectionMenu menu,Pending pending,String status){
        menu.publish("pending",List.of(UiText.t("ui.dialogue.original_payment.213584")+money(pending.currency,pending.amount()),UiText.t("ui.dialogue.closing_this_dialogue_does_not_cancel_a_comm.c11773")),status,pending.inFlight,List.of(choice("retry","",UiText.t("ui.dialogue.recover_original_payment.dd5898"),false)));
    }
    private static void submit(ServerPlayer player,CollectionMenu menu,State state,Pending pending){
        if(!pending.serverSession.equals(state.serverSession)){failure(menu,UiText.t("ui.dialogue.payment_belongs_to_an_earlier_server_session.8ba582"));return;}
        if(pending.inFlight){pendingPage(menu,pending,UiText.t("ui.dialogue.original_payment_is_still_processing.5c2c49"));return;}
        pending.inFlight=true;pendingPage(menu,pending,UiText.t("ui.dialogue.submitting_the_original_payment.64c9a8"));
        try {
            var grant=grant(player,state);
            if(!pending.markerSaved) {
                var marker=new CompoundTag();marker.putUUID("world",grant.target().world().value());marker.putUUID("operation",pending.operation.value());
                player.getPersistentData().put(RECOVERY,marker);
                BankingSaves.player(player,grant.target().world(),pending.operation.value());pending.markerSaved=true;
            }
            if(!live(player,menu,state)){pending.inFlight=false;return;}
            var attempt=new RequestId(UUID.randomUUID());
            var submission=pending.nativeQuote!=null?state.services.ledger().repayCollection(grant,pending.operation,attempt,pending.nativeQuote)
                :state.services.ledger().repayCollectionFx(grant,pending.operation,attempt,pending.fxQuote);
            submission.completion().whenComplete((delivery,error)->player.server.execute(()->{
                pending.inFlight=false;
                if(error!=null||delivery.outcome().status()==Journal.Status.UNKNOWN||delivery.outcome().status()==Journal.Status.NOT_ACCEPTED){
                    if(live(player,menu,state))pendingPage(menu,pending,UiText.t("ui.dialogue.outcome_pending_recover_this_original_paymen.84869f"));return;
                }
                var book=PENDING.get(player.server);if(book!=null)book.remove(player.getUUID(),pending);
                clearMarker(player,pending.operation);
                if(player.server.getPlayerList().getPlayer(player.getUUID())==player&&Economy.serverServices(player.server)==state.services)
                    player.displayClientMessage(UiText.component(player, delivery.outcome().status()==Journal.Status.COMMITTED
                        ?UiText.t("ui.dialogue.collection_payment_recorded.8bca54")+money(pending.currency,pending.amount())
                        :UiText.t("ui.dialogue.collection_payment_rejected_refresh_the_bala.7d4e96")),false);
                CollectionAgents.refresh(player);
                if(!live(player,menu,state))return;
                if(delivery.outcome().status()==Journal.Status.COMMITTED)cases(player,menu,state,null);
                else payment(menu,state,UiText.t("ui.dialogue.payment_rejected_refresh_balances_and_obtain.a86e2d"));
            }));
        }catch(RuntimeException unavailable){pending.inFlight=false;pendingPage(menu,pending,UiText.t("ui.dialogue.payment_unavailable_recover_the_original_req.5e4abc"));}
    }
    private static void recoverMarker(ServerPlayer player,CollectionMenu menu,State state) {
        var marker=player.getPersistentData().getCompound(RECOVERY);
        if(!marker.hasUUID("world")||!marker.hasUUID("operation")||!marker.getUUID("world").equals(state.services.worldId().value())) {
            menu.publish("pending",List.of(UiText.t("ui.dialogue.saved_payment_evidence_needs_review.fb470a")),UiText.t("ui.dialogue.payment_recovery_unavailable_for_this_world.91d3fb"),false,List.of());return;
        }
        var operation=new OperationId(marker.getUUID("operation"));
        menu.publish("pending",List.of(UiText.t("ui.dialogue.checking_the_original_payment_receipt.5bc3fe")),UiText.t("ui.dialogue.waiting_for_authoritative_recovery.f28c81"),true,List.of());
        state.services.ledger().lookupCollectionPaymentReceipt(grant(player,state),operation).whenComplete((found,error)->player.server.execute(()->{
            if(!live(player,menu,state))return;
            if(error!=null){menu.publish("pending",List.of(UiText.t("ui.dialogue.the_saved_original_request_remains_protected.66f8d7")),UiText.t("ui.dialogue.receipt_unavailable_retry_after_financial_ca.d859ff"),false,List.of(choice("retry","",UiText.t("ui.dialogue.recover_original_payment.dd5898"),false)));return;}
            if(found.isPresent()&&found.orElseThrow().status()==Journal.Status.UNKNOWN){menu.publish("pending",List.of(UiText.t("ui.dialogue.the_original_outcome_is_still_uncertain.4cec1c")),UiText.t("ui.dialogue.recover_the_original_payment_before_another.f9ffd0"),false,List.of(choice("retry","",UiText.t("ui.dialogue.recover_original_payment.dd5898"),false)));return;}
            clearMarker(player,operation);
            if(found.isPresent())player.displayClientMessage(UiText.component(player, found.orElseThrow().status()==Journal.Status.COMMITTED?UiText.t("ui.dialogue.previous_collection_payment_recorded.ddfa41"):UiText.t("ui.dialogue.previous_collection_payment_rejected.361350")),false);
            else player.displayClientMessage(UiText.component(player, UiText.t("ui.dialogue.no_committed_original_payment_was_found_revi.01aaef")),false);
            cases(player,menu,state,null);
        }));
    }
    private static void clearMarker(ServerPlayer player,OperationId operation) {
        var marker=player.getPersistentData().getCompound(RECOVERY);
        if(!marker.hasUUID("operation")||!marker.getUUID("operation").equals(operation.value()))return;
        player.getPersistentData().remove(RECOVERY);
        if(player.server.getPlayerList().getPlayer(player.getUUID())==player)player.server.getPlayerList().save(player);
    }
    private static String creditor(DebtRecords.Case debt){return InstitutionCatalog.require(debt.creditor(),debt.currency()).name();}
    private static CollectionNetwork.Choice choice(String action,String value,String label,boolean selected){return new CollectionNetwork.Choice(action,value,label,selected);}
    private static String money(EconomyCurrency currency,long amount){return UiText.money(new Money(currency,amount));}
    private static void failure(CollectionMenu menu,String reason){menu.publish("payment",List.of(),reason,false,List.of(choice("cases","",UiText.t("ui.dialogue.refresh_cases.9cbe98"),false),choice("funding","",UiText.t("ui.dialogue.funding_accounts.3ed457"),false)));}
    private static String kind(DebtRecords.Kind value) { return value == DebtRecords.Kind.CARD ? UiText.t("ui.dialogue.kind.card") : UiText.t("ui.dialogue.kind.loan"); }
}
