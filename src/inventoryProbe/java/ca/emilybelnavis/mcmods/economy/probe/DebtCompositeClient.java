package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.cash.CashCatalog;
import ca.emilybelnavis.mcmods.economy.client.BankingScreen;
import ca.emilybelnavis.mcmods.economy.foundation.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.terminal.*;
import java.nio.file.*;
import java.time.*;
import ca.emilybelnavis.mcmods.economy.collection.*;
import ca.emilybelnavis.mcmods.economy.loan.*;
import ca.emilybelnavis.mcmods.economy.debt.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Opt-in native linked debt witness. Every financial action uses the real client protocol. */
public final class DebtCompositeClient {
    private static final BlockPos KIOSK = new BlockPos(520,110,520);
    private static final String BANK = "economy:bank/crownstone";
    private final FixtureClock clock = new FixtureClock();
    private final long started = System.nanoTime();
    private final ArrayDeque<String> captures = new ArrayDeque<>();
    private final StringBuilder evidence = new StringBuilder();
    private Path fixtureRoot;
    private String worldName;
    private boolean opened, stopped, setupRequested, adminRequested, seedRequested;
    private volatile boolean queryPending;
    private volatile boolean prepared, funded;
    private volatile String failure;
    private int ticks, screenshots;
    private volatile int step;
    private long sentRevision=-1;
    private UUID sentSession, fundingOperation, loanOperation, loanReceipt;
    private UUID accountId, loanId, caseId;
    private volatile UUID fxAccountId;
    private volatile Loans.Contract loan;
    private volatile DebtRecords.Case debt;
    private volatile CalendarDiagnostics calendar;
    private volatile long observedTurns;
    private long due, target;
    private long initialDebt, initialFunding, initialCollectionExpiry, originalReportExpiry;
    private volatile int paymentCount;
    private int boundary;

    public DebtCompositeClient() {
        if (!Boolean.getBoolean("economy.debtCompositeProbe")) throw new IllegalStateException("Explicit debt fixture required");
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::installClock);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::captureFrame);
    }
    private void installClock(ServerAboutToStartEvent event) {
        try {
        var client=Minecraft.getInstance();
        var root=DisposableProbeWorld.requireClientDirectory(client.gameDirectory.toPath(),"economy-a10-05-");
        if(worldName==null || !event.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize()
                .equals(root.resolve("saves").resolve(worldName).toAbsolutePath().normalize()))
            throw new IllegalStateException("Debt clock requires the newly created fixture world");
        Economy.installFixtureClock(event.getServer(),clock);
        }catch(java.io.IOException invalid){throw new IllegalStateException("Disposable clock guard",invalid);}
    }
    private void tick(ClientTickEvent.Post event) {
        if(stopped)return;
        var client=Minecraft.getInstance();client.options.pauseOnLostFocus=false;
        try {
            if(fixtureRoot!=null&&Files.exists(fixtureRoot.resolve("debt-stop.txt")))failure="Fixture stopped by operator";
            if(!opened && client.getOverlay()==null && client.screen!=null && client.level==null) {
                opened=true;fixtureRoot=DisposableProbeWorld.requireClientDirectory(client.gameDirectory.toPath(),"economy-a10-05-");
                worldName="economy-debt-composite-"+UUID.randomUUID();DisposableProbeWorld.requireFreshWorld(fixtureRoot,worldName);
                client.options.guiScale().set(2);client.resizeDisplay();
                var settings=new LevelSettings(worldName,GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT);
                client.createWorldOpenFlows().createFreshLevel(worldName,settings,new WorldOptions(100500L,false,false),WorldPresets::createNormalWorldDimensions,client.screen);
            }
            if(failure!=null){finish(client,false);return;}
            if(System.nanoTime()-started>600_000_000_000L)throw new IllegalStateException("Ten-minute debt watchdog step="+step);
            if(observedTurns>10_000)throw new IllegalStateException("Dispatcher turn bound "+observedTurns);
            if(client.player==null || client.getSingleplayerServer()==null)return;
            ticks++;var server=client.getSingleplayerServer();
            if(client.player.containerMenu instanceof BankingMenu setup && setup.worldSetup() && !setup.busy()) {
                if(ticks%20==0)BankingNetwork.action(setup,"setup","CAD");return;
            }
            if(!setupRequested && ticks>30){setupRequested=true;UUID id=client.player.getUUID();onServer(server,()->prepare(server,id));}
            if(prepared && !adminRequested && step==0 && ticks%10==0){UUID id=client.player.getUUID();onServer(server,()->{var player=server.getPlayerList().getPlayer(id);if(player!=null && BankingController.openAdmin(player,KIOSK))adminRequested=true;});}
            if(ticks%100==0)System.out.println("DEBT_CLIENT_WAIT step="+step+" clock="+clock.millis()+" turns="+observedTurns+" menu="+client.player.containerMenu.getClass().getSimpleName()
                +(client.player.containerMenu instanceof BankingMenu bank ? " access="+bank.access()+" page="+bank.page()+" status="+bank.status()+" operation="+bank.operation()+" choices="+bank.choices():""));
            if(ticks%10!=0)return;
            if(step>=100){fxSetup(client,server);return;}
            if(step>=40){collection(client,server);return;}
            if(step>=30){advance(client,server);return;}
            if(!(client.player.containerMenu instanceof BankingMenu menu)||menu.busy()||menu.worldSetup())return;
            switch(step) {
                case 0 -> action(menu,"brand_preview","",1);
                case 1 -> {if(fresh(menu))action(menu,"brand_preview",BANK,2);}
                case 2 -> {if(fresh(menu))action(menu,"brand_confirm",BANK,3);}
                case 3 -> {if(fresh(menu)&&menu.page().equals("settings")){UUID id=client.player.getUUID();onServer(server,()->{var p=server.getPlayerList().getPlayer(id);p.closeContainer();BankingController.open(p,KIOSK);});step=4;}}
                case 4 -> {if(menu.access()==BankingMenu.Access.KIOSK && menu.dashboard()!=null)action(menu,"accounts","",5);}
                case 5 -> {if(fresh(menu)){if(offered(menu,"branch_open",""))action(menu,"branch_open","",6);else if(offered(menu,"manage_accounts",""))send(menu,"manage_accounts","");}}
                case 6 -> {if(fresh(menu)&&!seedRequested){seedRequested=true;UUID id=client.player.getUUID();var anchor=menu.anchor();onServer(server,()->fund(server,id,anchor));step=7;}}
                case 7 -> {if(funded){send(menu,"manage_accounts","");step=8;}}
                case 8 -> {if(fresh(menu))action(menu,"branch_products","",9);}
                case 9 -> {if(fresh(menu))action(menu,"finance_credit_apply","",10);}
                case 10 -> {if(fresh(menu))first(menu,"finance_credit_network",11);}
                case 11 -> {if(fresh(menu)&&offered(menu,"finance_credit_preview","")){send(menu,"finance_credit_preview","2500");step=12;}}
                case 12 -> {if(fresh(menu))action(menu,"finance_credit_confirm","",13);}
                case 13 -> {if(fresh(menu)&&menu.operation().state()==BankingOperation.State.COMPLETED){note("starter credit original "+menu.operation());send(menu,"manage_accounts","");step=14;}}
                case 14 -> {if(fresh(menu))action(menu,"branch_products","",15);}
                case 15 -> {if(fresh(menu))action(menu,"finance_loan_apply","",16);}
                case 16 -> {if(fresh(menu))action(menu,"finance_loan_destination",accountId.toString(),17);}
                case 17 -> {if(fresh(menu)&&offered(menu,"finance_loan_preview","")){send(menu,"finance_loan_preview","1000 1");step=18;}}
                case 18 -> {if(fresh(menu)&&offered(menu,"finance_loan_confirm","")){note("primary fixed loan review "+menu.lines());captureReview(client,"debt-primary-loan-review.png");step=19;}}
                case 19 -> {if(captures.isEmpty()&&screenshots>=1)action(menu,"finance_loan_confirm","",20);}
                case 20 -> {if(fresh(menu)&&menu.operation().state()==BankingOperation.State.COMPLETED){loanOperation=menu.operation().operationId();loanReceipt=menu.operation().receiptId();note("primary original operation="+loanOperation+" receipt="+loanReceipt);UUID id=client.player.getUUID();onServer(server,()->queryLoan(server,id));step=21;}}
                case 21 -> {if(loan!=null){loanId=loan.id();due=loan.nextMonth();note("primary captured "+loan);send(menu,"manage_accounts","");step=22;}}
                case 22 -> {if(fresh(menu))action(menu,"branch_products","",23);}
                case 23 -> {if(fresh(menu))action(menu,"finance_loan_apply","",24);}
                case 24 -> {if(fresh(menu))action(menu,"finance_loan_product","SUBPRIME_LOAN",25);}
                case 25 -> {if(fresh(menu))action(menu,"finance_loan_destination",accountId.toString(),26);}
                case 26 -> {if(fresh(menu)&&offered(menu,"finance_loan_preview","")){send(menu,"finance_loan_preview","500 1");step=27;}}
                case 27 -> {if(fresh(menu)&&offered(menu,"finance_loan_confirm","")){note("separate subprime loan review "+menu.lines());captureReview(client,"debt-subprime-loan-review.png");step=28;}}
                case 28 -> {if(captures.isEmpty())action(menu,"finance_loan_confirm","",29);}
                case 29 -> {if(fresh(menu)&&menu.operation().state()==BankingOperation.State.COMPLETED){note("separate subprime original "+menu.operation());openAdmin(server,client.player.getUUID());step=100;}}
                default -> throw new IllegalStateException("Unknown debt step "+step);
            }
        }catch(Throwable invalid){invalid.printStackTrace();failure=invalid.toString();finish(client,false);}
    }

    private void onServer(MinecraftServer server,Runnable work){server.execute(()->{try{work.run();}catch(Throwable invalid){failure="Server fixture step="+step+" "+invalid;invalid.printStackTrace();}});}
    private void openAdmin(MinecraftServer server,UUID id){onServer(server,()->{var player=server.getPlayerList().getPlayer(id);player.closeContainer();require(BankingController.openAdmin(player,KIOSK),"Placed kiosk administration");});}
    private void openBranch(MinecraftServer server,UUID id){onServer(server,()->{var player=server.getPlayerList().getPlayer(id);player.closeContainer();BankingController.open(player,KIOSK);});}
    private void fxSetup(Minecraft client,MinecraftServer server){
        if(!(client.player.containerMenu instanceof BankingMenu menu)||menu.busy())return;
        switch(step){
            case 100 -> action(menu,"brand_preview","",101);
            case 101 -> {if(fresh(menu))action(menu,"brand_preview","economy:bank/crosswind",102);}
            case 102 -> {if(fresh(menu))action(menu,"brand_confirm","economy:bank/crosswind",103);}
            case 103 -> {if(fresh(menu)&&menu.page().equals("settings")){openBranch(server,client.player.getUUID());step=104;}}
            case 104 -> {if(menu.access()==BankingMenu.Access.KIOSK&&menu.dashboard()!=null)action(menu,"accounts","",105);}
            case 105 -> {if(fresh(menu)){if(offered(menu,"branch_open",""))action(menu,"branch_open","",106);else if(offered(menu,"manage_accounts",""))send(menu,"manage_accounts","");}}
            case 106 -> {if(fresh(menu)&&menu.page().equals("branch")&&!queryPending){queryPending=true;UUID id=client.player.getUUID();onServer(server,()->{
                var services=Economy.serverServices(server);var grant=FinanceMenuSupport.grant(server.getPlayerList().getPlayer(id),services);
                services.listOwnedAccounts(grant,null,4).whenComplete((found,error)->onServer(server,()->{queryPending=false;if(error!=null){failure="USD account query "+error;return;}
                    fxAccountId=found.orElseThrow().accounts().stream().filter(a->a.currency()==EconomyCurrency.USD).findFirst().orElseThrow().id().value();openAdmin(server,id);step=110;}));
            });}}
            case 110 -> action(menu,"brand_preview","",111);
            case 111 -> {if(fresh(menu))action(menu,"brand_preview",BANK,112);}
            case 112 -> {if(fresh(menu))action(menu,"brand_confirm",BANK,113);}
            case 113 -> {if(fresh(menu)&&menu.page().equals("settings")){openBranch(server,client.player.getUUID());step=114;}}
            case 114 -> {if(offered(menu,"branch_products",""))action(menu,"branch_products","",115);else if(offered(menu,"accounts",""))send(menu,"accounts","");else if(offered(menu,"manage_accounts",""))send(menu,"manage_accounts","");}
            case 115 -> {if(fresh(menu))action(menu,"finance_loan_apply","",116);}
            case 116 -> {if(fresh(menu))action(menu,"finance_loan_product","BANK_LOAN",117);}
            case 117 -> {if(fresh(menu))action(menu,"finance_loan_destination",fxAccountId.toString(),118);}
            case 118 -> {if(fresh(menu)&&offered(menu,"finance_loan_preview","")){send(menu,"finance_loan_preview","1000 1");step=119;}}
            case 119 -> {if(fresh(menu)&&offered(menu,"finance_loan_confirm","")){note("separate fixed CAD loan explicit USD disbursement FX review "+menu.lines());captureReview(client,"debt-fixed-fx-review.png");step=120;}}
            case 120 -> {if(captures.isEmpty())action(menu,"finance_loan_confirm","",121);}
            case 121 -> {if(fresh(menu)&&menu.operation().state()==BankingOperation.State.COMPLETED){note("separate fixed FX original "+menu.operation());target=due;step=30;}}
            default -> throw new IllegalStateException("Unknown native FX step "+step);
        }
    }
    private void advance(Minecraft client,MinecraftServer server) {
        if(queryPending)return;
        queryPending=true;
        UUID playerId=client.player.getUUID();
        onServer(server,()->{
        var services=Economy.serverServices(server);
        if(services==null){queryPending=false;return;}
        if(services.state()==ServerServices.State.UNAVAILABLE){
            var cause=services.failure();if(cause!=null)cause.printStackTrace();
            failure="Economy unavailable: "+cause;queryPending=false;return;
        }
        observedTurns=services.observedDeadlineTurns();
        if(observedTurns>10_000){failure="Observed dispatcher turns exceeded10000: "+observedTurns;queryPending=false;return;}
        if(services.state()!=ServerServices.State.READY||(!services.metrics().economy().ready()||services.metrics().economicStepInFlight())){queryPending=false;return;}
        services.tradeStorage(store->{
            if(!(store instanceof FoundationRuntime runtime))throw new IllegalStateException("Foundation runtime diagnostics required");
            var diagnostics=runtime.readCalendarDiagnostics();
            var fixed=store.transaction(tx->tx.query("SELECT e.system_id,e.frontier_ms,e.cadence_ms,r.first_excluded_ms FROM economic_systems e LEFT JOIN fixed_system_retirements r ON r.system_id=e.system_id ORDER BY e.system_id",List.of(),64));
            var obligations=store.transaction(tx->tx.query("SELECT body FROM debt_obligations WHERE kind='LOAN' AND facility_id=? AND due_at=?",List.of(loanId.toString(),Long.toString(due)),2));
            var reports=store.transaction(tx->tx.query("SELECT body FROM debt_reports WHERE kind='LOAN' AND facility_id=? AND obligation_id IN (SELECT obligation_id FROM debt_obligations WHERE kind='LOAN' AND facility_id=? AND due_at=?) ORDER BY revision",List.of(loanId.toString(),loanId.toString(),Long.toString(due)),16));
            var enabled=store.transaction(tx->tx.query("SELECT facility_id FROM autopay_settings WHERE kind='LOAN' AND facility_id=? AND enabled=1",List.of(loanId.toString()),1));
            if(!enabled.isEmpty())throw new IllegalStateException("Fixture loan autopay must remain OFF");
            return new CalendarSample(diagnostics,fixed,obligations,reports);
        }).whenComplete((snapshot,error)->onServer(server,()->{
            var sample=snapshot==null?null:snapshot.diagnostics();
            queryPending=false;
            if(error!=null){failure="Calendar inspection "+error;return;}
            calendar=sample;
            if(sample==null||sample.domainPendingWitness()!=0)return;
            for(var system:snapshot.fixed()) {
                long next=Math.addExact(Long.parseLong(system.get("frontier_ms")),Long.parseLong(system.get("cadence_ms")));
                String retired=system.get("first_excluded_ms");
                if((retired==null||next<Long.parseLong(retired))&&next<=clock.millis())return;
            }
            if(clock.millis()<target) {
                long next=target;
                if(sample.nextEvent()!=null && sample.nextEvent().effectiveMillis()>clock.millis())next=Math.min(next,sample.nextEvent().effectiveMillis());
                for(var system:snapshot.fixed()) {
                    long fixedNext=Math.addExact(Long.parseLong(system.get("frontier_ms")),Long.parseLong(system.get("cadence_ms")));
                    String retired=system.get("first_excluded_ms");
                    if((retired==null||fixedNext<Long.parseLong(retired)) && fixedNext>clock.millis())next=Math.min(next,fixedNext);
                }
                note("advance from="+clock.millis()+" to="+next+" next="+sample.nextEvent()+" frontier="+sample.frontierMillis());clock.advanceTo(next);return;
            }
            if(boundary==0){
                require(snapshot.obligations().size()==1,"Exactly one original first-due obligation");
                var original=DebtRecords.decodeObligation(snapshot.obligations().getFirst().get("body"));
                require(original.dueAt()==due&&original.unmetMinor()==original.originalMinor()&&original.unmetMinor()>0&&!original.reported(),"Original unpaid obligation before report");
                note("original first due obligation "+original);boundary=1;note("original first due "+sample);capture("debt-original-due.png");queryLoan(server,playerId);target=due+2*Loans.DAY;}
            else if(boundary==1){
                require(snapshot.obligations().size()==1&&!snapshot.reports().isEmpty(),"Original obligation reported at due+2");
                var original=DebtRecords.decodeObligation(snapshot.obligations().getFirst().get("body"));
                require(original.reported()&&!original.metAtReport(),"Original missed obligation report");
                for(var row:snapshot.reports()){var report=DebtRecords.decodeReport(row.get("body"));require(report.facility().equals(loanId)&&report.obligation().equals(original.id())&&report.reportAt()==due+2*Loans.DAY,"Same original loan report identity");note("original report "+report);}
                boundary=2;note("due plus two financial days "+sample);capture("debt-report-boundary.png");target=due+84*Loans.DAY;}
            else {
                var player=server.getPlayerList().getPlayer(playerId);var grant=FinanceMenuSupport.grant(player,services);
                services.ledger().collectionCases(grant,null,16).whenComplete((found,caseError)->onServer(server,()->{
                    if(caseError!=null){failure="Case query "+caseError;return;}
                    debt=found.orElseThrow().stream().filter(c->c.facility().equals(loanId)).findFirst().orElse(null);
                    if(debt==null){failure="Original loan did not produce a linked case at oldest due plus84days";return;}
                    require(debt.defaultAt()==due+84*Loans.DAY,"Default anchored to original first unmet due");
                    if(boundary==2){
                        caseId=debt.id();initialCollectionExpiry=debt.collectionExpires();originalReportExpiry=debt.reportExpires();
                        note("linked default case "+debt);boundary=3;target=due+85*Loans.DAY;return;
                    }
                    require(debt.id().equals(caseId),"Original case retained before native payment");
                    initialDebt=debt.debtMinor();paymentWitness(server,playerId,0);player.closeContainer();step=40;
                }));
            }
        })); });
    }
    private record CalendarSample(CalendarDiagnostics diagnostics,List<Map<String,String>> fixed,List<Map<String,String>> obligations,List<Map<String,String>> reports) {}
    private void collection(Minecraft client,MinecraftServer server) {
        if(step==54){if(captures.isEmpty())finish(client,true);return;}
        if(step==53){
            if(paymentCount<2){if(!queryPending)paymentWitness(server,client.player.getUUID(),2);return;}
            if(queryPending)return;queryPending=true;UUID id=client.player.getUUID();
            onServer(server,()->{var services=Economy.serverServices(server);var grant=FinanceMenuSupport.grant(server.getPlayerList().getPlayer(id),services);
                services.ledger().collectionCase(grant,caseId).whenComplete((found,error)->{
                    queryPending=false;if(error!=null){failure="Settlement verification "+error;return;}
                    debt=found.orElseThrow();if(debt.payment()==DebtRecords.Payment.SETTLED&&debt.debtMinor()==0){note("verified full settlement "+debt);capture("debt-agent-settled.png");step=54;}
                });});return;
        }
        if(step==40 || step==47 && !(client.player.containerMenu instanceof CollectionMenu)) {
            if(step==47 && paymentCount<1){if(!queryPending)paymentWitness(server,client.player.getUUID(),1);return;}
            // Use the actual client interaction packet against an automatically managed presentation.
            var agents=client.level.getEntitiesOfClass(CollectionAgent.class,client.player.getBoundingBox().inflate(48));
            if(agents.isEmpty())return;
            var agent=agents.getFirst();
            note("native agent entity="+agent.getUUID()+" presentation="+agent.presentation()+" position="+agent.position());
            if(client.player.distanceTo(agent)>4){UUID id=client.player.getUUID();double x=agent.getX(),y=agent.getY(),z=agent.getZ();onServer(server,()->server.getPlayerList().getPlayer(id).teleportTo(x+1,y,z));return;}
            client.gameMode.interact(client.player,agent,InteractionHand.MAIN_HAND);if(step==40)step=41;sentRevision=-1;sentSession=null;return;
        }
        if(!(client.player.containerMenu instanceof CollectionMenu menu)||menu.busy())return;
        boolean fresh=sentSession==null||!sentSession.equals(menu.session())||menu.revision()>sentRevision;
        if(!fresh)return;
        switch(step) {
            case 41 -> {note("actual native menu agent="+menu.entityId()+" server presentation lease="+menu.presentation());collectionAction(menu,"select_case",caseId.toString(),42);}
            case 42 -> collectionAction(menu,"funding","",43);
            case 43 -> collectionAction(menu,"select_funding",accountId.toString(),44);
            case 44 -> collectionAction(menu,"preview_partial","1.00",45);
            case 45 -> {if(collectionOffered(menu,"confirm","")){note("positive partial review "+menu.lines());captureReview(client,"debt-agent-partial-review.png");step=46;}}
            case 46 -> {if(captures.isEmpty())collectionAction(menu,"confirm","",47);}
            case 47 -> {if(collectionOffered(menu,"select_case",caseId.toString())){if(paymentCount<1){if(!queryPending)paymentWitness(server,client.player.getUUID(),1);return;}note("partial outcome "+menu.lines());capture("debt-agent-partial-outcome.png");collectionAction(menu,"select_case",caseId.toString(),48);}}
            case 48 -> collectionAction(menu,"funding","",49);
            case 49 -> collectionAction(menu,"select_funding",accountId.toString(),50);
            case 50 -> collectionAction(menu,"preview_full","",51);
            case 51 -> {if(collectionOffered(menu,"confirm","")){note("full settlement review "+menu.lines());captureReview(client,"debt-agent-full-review.png");step=52;}}
            case 52 -> {if(captures.isEmpty())collectionAction(menu,"confirm","",53);}
            case 53,54 -> { }
            default -> throw new IllegalStateException("Unknown collection step "+step);
        }
    }
    private void paymentWitness(MinecraftServer server,UUID id,int expected){
        queryPending=true;onServer(server,()->{var services=Economy.serverServices(server);
            services.tradeStorage(store->store.transaction(tx->{
                var balances=tx.query("SELECT minor_units FROM ledger_balances WHERE account_id=? AND currency='CAD'",List.of(accountId.toString()),1);
                long balance=balances.isEmpty()?0:Long.parseLong(balances.getFirst().get("minor_units"));
                var cases=tx.query("SELECT body FROM debt_cases WHERE case_id=?",List.of(caseId.toString()),1);
                var current=DebtRecords.decodeCase(cases.getFirst().get("body"));
                var outcomes=tx.query("SELECT operation_id,transaction_id,sequence,result_code,result_payload FROM operation_outcomes WHERE service='economy:debt' AND operation_type='economy:repay' AND status='COMMITTED' ORDER BY sequence",List.of(),8);
                require(outcomes.size()==expected,"Exactly one commit per reviewed native payment, expected="+expected+" actual="+outcomes.size());
                if(expected==0){initialFunding=balance;require(current.debtMinor()==initialDebt,"Default initial debt witness");}
                else if(expected==1){require(current.debtMinor()==initialDebt-100&&balance==initialFunding-100&&current.payment()==DebtRecords.Payment.PARTIAL,"Positive CAD1 partial payment exact debt/funding reduction");
                    require(current.collectionExpires()==clock.millis()+DebtRecords.COLLECTION_WINDOW&&current.collectionExpires()>initialCollectionExpiry&&current.reportExpires()==originalReportExpiry,"Positive partial renews collection window and preserves original report expiry");}
                else{require(current.debtMinor()==0&&balance==initialFunding-initialDebt&&current.payment()==DebtRecords.Payment.SETTLED,"Full settlement exact remaining debt/funding reduction");}
                note("payment witness count="+expected+" fundingMinor="+balance+" case="+current+" immutable original outcomes="+outcomes);return expected;
            })).whenComplete((count,error)->{queryPending=false;if(error!=null)failure="Payment witness "+error;else paymentCount=count;});
        });
    }
    private void queryLoan(MinecraftServer server,UUID id) {
        var services=Economy.serverServices(server);var grant=FinanceMenuSupport.grant(server.getPlayerList().getPlayer(id),services);
        services.ledger().loans(grant,null,16).whenComplete((found,error)->{if(error!=null)failure="Loan query "+error;else loan=found.orElseThrow().stream().filter(l->loanId==null||l.id().equals(loanId)).findFirst().orElse(null);});
    }
    private void prepare(MinecraftServer server, UUID uuid) {
        try {
            var player = server.getPlayerList().getPlayer(uuid); var services = Economy.serverServices(server);
            if (player == null || services == null || services.state() != ServerServices.State.READY || !services.metrics().economy().ready()) { setupRequested = false; return; }
            player.closeContainer(); player.setInvulnerable(true);
            var level = server.overworld(); level.setChunkForced(KIOSK.getX() >> 4, KIOSK.getZ() >> 4, true); level.getChunkAt(KIOSK);
            for(int x=30;x<=34;x++)for(int z=30;z<=34;z++)level.setChunkForced(x,z,true);
            for (var pos : BlockPos.betweenClosed(KIOSK.offset(-20, -1, -20), KIOSK.offset(20, 3, 20)))
                level.setBlockAndUpdate(pos, pos.getY() < KIOSK.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            player.teleportTo(KIOSK.getX() + 2, KIOSK.getY(), KIOSK.getZ() - 2); player.setYRot(Direction.SOUTH.toYRot());
            var stack = new ItemStack(TerminalCatalog.get("branch_kiosk").item().get()); player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            var hit = new BlockHitResult(Vec3.atCenterOf(KIOSK.below()).add(0, .5, 0), Direction.UP, KIOSK.below(), false);
            require(((BlockItem) stack.getItem()).place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit)).consumesAction(), "Place fixture branch kiosk");
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); prepared = true;
        } catch (Throwable invalid) { failure = "Debt setup " + invalid; }
    }

    private void fund(MinecraftServer server, UUID uuid, TerminalPolicy.Anchor anchor) {
        var player = server.getPlayerList().getPlayer(uuid); var services = Economy.serverServices(server); var actor = new PlayerId(uuid);
        services.ledger().bankingContext(actor, anchor, null).whenComplete((context, error) -> {
            if (error != null || context.account() == null) { failure = "Native primary account missing before explicitly seeded funding: " + error; return; }
            var account = context.account(); accountId=account.id().value(); fundingOperation = UUID.randomUUID();
            var adjustment = new SameCurrency.Adjustment(actor, new SameCurrency.Revision(account.id(), account.revision()), new Money(account.currency(), 1_000_000));
            var intent = new Journal.Intent(new OperationId(fundingOperation), new RequestId(UUID.randomUUID()), actor, SameCurrency.CREDIT, adjustment.request(), null, null);
            services.ledger().adjustments(SameCurrency.CREDIT, (who, kind) -> who.equals(actor), (actual, instruction, reads) -> true, null)
                    .submit(intent).completion().whenComplete((delivery, seedError) -> {
                        if (seedError != null || delivery.outcome().status() != Journal.Status.COMMITTED) failure = "Explicit fixture CAD10000 journal funding failed: " + seedError;
                        else { funded = true; System.out.println("DEBT_FIXTURE_FUNDING " + fundingOperation); }
                    });
        });
    }


    private void action(BankingMenu menu,String action,String value,int next){if(offered(menu,action,value)){send(menu,action,value);step=next;}}
    private void first(BankingMenu menu,String action,int next){menu.choices().stream().filter(c->c.action().equals(action)).findFirst().ifPresent(c->{send(menu,action,c.value());step=next;});}
    private static boolean offered(BankingMenu menu,String action,String value){return menu.choices().stream().anyMatch(c->c.action().equals(action)&&c.value().equals(value));}
    private void send(BankingMenu menu,String action,String value){sentSession=menu.session();sentRevision=menu.revision();BankingNetwork.action(menu,action,value);}
    private boolean fresh(BankingMenu menu){return sentSession==null||!sentSession.equals(menu.session())||menu.revision()>sentRevision;}
    private static boolean collectionOffered(CollectionMenu menu,String action,String value){return CollectionMenu.offered(menu.choices(),action,value);}
    private void collectionAction(CollectionMenu menu,String action,String value,int next){if(collectionOffered(menu,action,value)){sentSession=menu.session();sentRevision=menu.revision();CollectionNetwork.action(menu,action,value);step=next;}}
    private synchronized void note(String value){evidence.append(value).append('\n');System.out.println("DEBT_EVIDENCE "+value);}
    private synchronized void capture(String name){captures.addLast(name);}
    private void captureReview(Minecraft client,String name){
        require(client.screen!=null,"Native review screen present");
        for(int i=0;i<32;i++)client.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN,0,0);
        capture(name);
    }
    private void captureFrame(RenderFrameEvent.Post event){
        String name; synchronized(this){if(captures.isEmpty())return;name=captures.removeFirst();}
        var client=Minecraft.getInstance();Screenshot.grab(client.gameDirectory,name,client.getMainRenderTarget(),message->{screenshots++;System.out.println("DEBT_SCREEN "+name+" "+message.getString());});
    }
    private void finish(Minecraft client,boolean success){
        if(stopped)return;stopped=true;
        note("fixture finished success="+success+" elapsedMillis="+(System.nanoTime()-started)/1_000_000
                +" lastObservedDispatcherTurns="+observedTurns+" financialMillis="+clock.millis());
        try{if(fixtureRoot!=null)Files.writeString(fixtureRoot.resolve(success?"debt-composite-passed.txt":"debt-composite-failed.txt"),
                (success?"PASS native linked primary debt chain\n":"FAILED "+failure+"\n")+evidence);}
        catch(Exception error){System.out.println("DEBT_MARKER_FAILED "+error);}
        System.out.println(success?"DEBT_CLIENT_PASS":"DEBT_CLIENT_FAILED "+failure);client.execute(client::stop);
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private static final class FixtureClock extends Clock {
        private long millis=System.currentTimeMillis();
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){if(!ZoneOffset.UTC.equals(zone))throw new IllegalArgumentException("UTC fixture");return this;}
        public synchronized Instant instant(){return Instant.ofEpochMilli(millis);}
        public synchronized long millis(){return millis;}
        synchronized void advanceTo(long next){if(next<millis)throw new IllegalArgumentException("Financial clock cannot retreat");millis=next;}
    }
}
