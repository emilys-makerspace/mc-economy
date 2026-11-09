package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.cash.CashCatalog;
import ca.emilybelnavis.mcmods.economy.foundation.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.terminal.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;

/** Dedicated online-mode witness: never creates a player or a connection. Excluded from release jars. */
public final class MultiplayerProbe {
    private static final String BANK="economy:bank/crownstone";
    private static final class Actor {
        final String role;final long initial;final BlockPos kiosk,teller;UUID uuid;int setup;boolean reading,funded;
        ServerPlayer original;AccountIdentity account;long lastSerial,lastSentAt,expectedRevision;String expectedSession;BankingNetwork.Action stale;
        long available=-1,reserved=-1,posted=-1;UUID operation,receipt;CompoundTag pending;
        Actor(String role,int x,long initial){this.role=role;this.initial=initial;kiosk=new BlockPos(x,110,520);teller=kiosk.offset(8,0,0);}
    }
    private static final Actor A=new Actor("A",520,10_000),B=new Actor("B",552,20_000);
    private static Path root;private static long started,armed,stageAt,serial,diagnosedAt;private static int stage;
    private static String failure;private static final StringBuilder evidence=new StringBuilder();
    private static CountDownLatch barrier;private static volatile boolean barrierEntered;
    private static boolean checksStarted,checksDone;private static long strangerRevision,strangerOwnRevision;private static ServerPlayer detached;
    private MultiplayerProbe(){}
    public static Path guard(Path path)throws Exception {
        var p=path.toRealPath();if(!p.getParent().equals(Path.of("/private/tmp").toRealPath())||!p.getFileName().toString().startsWith("economy-a10-04-"))throw new IllegalStateException("Guarded disposable A10-04 root required");return p;
    }
    public static boolean tick(MinecraftServer server) {
        try {
            if(root==null){root=guard(Path.of(System.getProperty("economy.multiplayerRoot")));require(server.isDedicatedServer()&&server.usesAuthentication(),"Dedicated online-mode server required");log("Dedicated online-mode socket witness; no fake players or credential files.");}
            if(failure!=null)throw new IllegalStateException(failure);
            if(started!=0&&System.nanoTime()-started>480_000_000_000L)throw new IllegalStateException("Eight-minute fixture deadline stage="+stage);
            if(Files.exists(root.resolve("stop.txt")))throw new IllegalStateException("Fixture stopped before acceptance");
            for(var actor:List.of(A,B)) {
                var online=root.resolve(actor.role+"-online.txt");if(actor.uuid==null&&Files.isRegularFile(online))actor.uuid=UUID.fromString(Files.readString(online).trim());
                if(Files.exists(root.resolve(actor.role+"-client-failed.txt")))throw new IllegalStateException(Files.readString(root.resolve(actor.role+"-client-failed.txt")));
            }
            if(A.uuid==null||B.uuid==null)return false;require(!A.uuid.equals(B.uuid),"Distinct authenticated UUIDs");if(started==0)started=System.nanoTime();
            var services=Economy.serverServices(server);if(services==null||services.state()!=ServerServices.State.READY||(stage==0&&!services.metrics().economy().ready()))return false;
            if(stage>0 && System.nanoTime()-diagnosedAt>5_000_000_000L) {
                diagnosedAt=System.nanoTime();
                for(var actor:List.of(A,B)) { var m=menu(server,actor); log("progress stage="+stage+" role="+actor.role+" menu="+(m==null?"closed":m.page()+" revision="+m.revision()+" busy="+m.busy()+" state="+m.operation().state()+" status="+m.status())); }
            }
            if(stage==0){for(var actor:List.of(A,B))setup(server,actor);if(A.setup==15&&B.setup==15){require(player(server,A)!=null&&player(server,B)!=null,"Both actual players concurrently connected");armed=System.nanoTime();Files.writeString(root.resolve("delay-start.txt"),Long.toString(armed));log("Both authenticated clients funded/ready; 60-second proxy window armed.");next(1);}return false;}
            switch(stage) {
                case 1->{if(!checksStarted){checksStarted=true;var ag=FinanceMenuSupport.grant(player(server,A),services);var bg=FinanceMenuSupport.grant(player(server,B),services);require(ag!=null&&bg!=null,"Current owner grants");services.ledger().ownedBankAccount(ag,B.account.id()).whenComplete((foreign,e)->{if(e!=null||foreign.isPresent())failure="Foreign owned-account lookup was not denied";else checksDone=true;});}if(checksDone){var other=menu(server,B);strangerRevision=other.revision();strangerOwnRevision=menu(server,A).revision();raw(A,new BankingNetwork.Action(other.containerId,other.session(),other.revision(),"history",""));next(31);}}
                case 31->{if(ack(A)&&elapsed()>1_000_000_000L){require(menu(server,B).revision()==strangerRevision&&menu(server,A).revision()==strangerOwnRevision,"Foreign-session history packet cannot publish into either owner menu");log("A sent B menu/session history packet over actual socket; B revision unchanged, foreign grant read denied.");command(server,A,"home","",false);command(server,B,"home","",false);log("Simultaneous offered Home actions issued at server tick="+server.getTickCount());next(2);}}
                case 2->{if(changed(server,A)&&changed(server,B)){command(server,A,"payments","",false);command(server,B,"payments","",false);next(21);}}
                case 21->{if(changed(server,A)&&changed(server,B)){command(server,A,"withdraw","",false);command(server,B,"withdraw","",false);next(3);}}
                case 3->{if(changed(server,A)&&changed(server,B)){command(server,A,"withdraw_preview","20",false);command(server,B,"withdraw_preview","20",false);next(4);}}
                case 4->{if(review(server,A)&&review(server,B)){command(server,A,"withdraw_confirm","",true);command(server,B,"withdraw_confirm","",true);log("Simultaneous explicit confirmations and exact duplicate packets issued tick="+server.getTickCount());next(5);}}
                case 5->{if(completed(server,A)&&completed(server,B)){readFunds(server,A);readFunds(server,B);if(A.available==8_000&&B.available==18_000&&cash(player(server,A))==2_000&&cash(player(server,B))==2_000){A.operation=menu(server,A).operation().operationId();A.receipt=menu(server,A).operation().receiptId();B.operation=menu(server,B).operation().operationId();B.receipt=menu(server,B).operation().receiptId();require(A.operation!=null&&A.receipt!=null&&B.operation!=null&&B.receipt!=null&&!A.operation.equals(B.operation),"Distinct original committed receipts");log("Normal commits A="+A.operation+" receipt="+A.receipt+" B="+B.operation+" receipt="+B.receipt+"; banks8000/18000 cash2000/2000; duplicates caused no second effects.");command(server,A,"withdraw","",false);next(6);}}}
                case 6->{if(changed(server,A)){command(server,A,"withdraw_preview","10",false);next(7);}}
                case 7->{if(review(server,A)){var m=menu(server,A);A.stale=new BankingNetwork.Action(m.containerId,m.session(),m.revision(),"withdraw_confirm","");command(server,A,"home","",false);next(8);}}
                case 8->{if(changed(server,A)){raw(A,A.stale);next(9);}}
                case 9->{if(ack(A)&&elapsed()>1_000_000_000L){readFunds(server,A);if(A.available==8_000&&cash(player(server,A))==2_000){log("Old review confirmation sent after legitimate Home revision; unchanged bank8000/cash2000.");command(server,A,"payments","",false);next(91);}}}
                case 91->{if(changed(server,A)){command(server,A,"withdraw","",false);next(10);}}
                case 10->{if(changed(server,A)){command(server,A,"withdraw_preview","10",false);next(11);}}
                case 11->{if(review(server,A)){barrier=new CountDownLatch(1);services.tradeStorage(store->{barrierEntered=true;try{if(!barrier.await(20,TimeUnit.SECONDS))throw new IllegalStateException("Fixture storage barrier timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return true;});next(12);}}
                case 12->{if(barrierEntered){command(server,A,"withdraw_confirm","",true);next(13);}}
                case 13->{var p=player(server,A);var tag=p.getPersistentData().getCompound("economy_banking_cash_pending");if(tag.hasUUID("operation")){A.pending=tag.copy();detached=p;log("Controlled pending boundary operation="+tag.getUUID("operation")+" service="+tag.getString("service")+" type="+tag.getString("type")+" UTC="+java.time.Instant.now()+"; bounded storage barrier separate from network delay.");simple(A,"close");next(14);}}
                case 14->{if(ack(A)&&player(server,A).containerMenu instanceof net.minecraft.world.inventory.InventoryMenu){BankingController.open(player(server,A),A.teller);next(15);}}
                case 15->{if(player(server,A).containerMenu instanceof BankingMenu reopened){require(reopened.operation().operationId()!=null&&reopened.operation().operationId().equals(A.pending.getUUID("operation")),"Processing close/reopen preserves original pending operation");log("Actual processing menu closed and reopened with original="+reopened.operation().operationId()+" state="+reopened.operation().state());simple(A,"disconnect");next(16);}}
                case 16->{if(player(server,A)==null){log("Actual socket A disconnected; releasing storage barrier. Reconnect same signed-in A to proxy127.0.0.1:25566 now.");barrier.countDown();Files.writeString(root.resolve("reconnect-A.txt"),"same authenticated owner only");next(17);}}
                case 17->{var p=player(server,A);if(p!=null&&p!=detached){require(p.getUUID().equals(detached.getUUID()),"Same authenticated owner returned as new player object");boolean denied=false;try{BankingSaves.player(detached,services.worldId(),UUID.randomUUID());}catch(IllegalStateException expected){denied=expected.getMessage().equals("PLAYER_SESSION_ENDED");}require(denied,"Detached old player cannot save over relogin");p.closeContainer();BankingController.open(p,A.teller);next(18);}}
                case 18->{var m=menu(server,A);if(m!=null&&!m.busy()){require(m.operation().operationId()!=null&&m.operation().operationId().equals(A.pending.getUUID("operation")),"Reopened menu retains original pending identity");command(server,A,"retry","",false);next(19);}}
                case 19->{var m=menu(server,A);if(m!=null&&!m.busy()&&m.operation().state()!=BankingOperation.State.OUTCOME_UNCERTAIN&&m.operation().state()!=BankingOperation.State.PROCESSING){log("Original lookup resolved state="+m.operation().state()+" operation="+m.operation().operationId()+" receipt="+m.operation().receiptId()+"; no automatic new submission.");readFunds(server,A);readFunds(server,B);if(A.available==8_000&&B.available==18_000&&cash(player(server,A))==2_000&&cash(player(server,B))==2_000)next(20);else if(elapsed()>15_000_000_000L)throw new IllegalStateException("Original recovery unresolved funds: A available="+A.available+" reserved="+A.reserved+" posted="+A.posted+" cash="+cash(player(server,A))+" state="+m.operation().state()+" pending="+player(server,A).getPersistentData().getCompound("economy_banking_cash_pending"));}}
                case 20->{if(System.nanoTime()-armed>=60_000_000_000L){log("PASS authenticated A/B owner isolation, simultaneous offered actions, normal original receipts, duplicate/stale refusal, processing close/reopen, controlled disconnect/relogin original lookup; finalbanks8000/18000 cash2000/2000. Loopback only; no external-client claim.");Files.writeString(root.resolve("multiplayer-passed.txt"),evidence.toString());server.halt(false);return true;}}
                default->throw new IllegalStateException("Fixture stage "+stage);
            }
            return false;
        }catch(Throwable e){if(barrier!=null)barrier.countDown();e.printStackTrace();try{if(root!=null)Files.writeString(root.resolve("multiplayer-failed.txt"),e+"\n"+evidence);}catch(Exception ignored){}server.halt(false);return true;}
    }
    private static void setup(MinecraftServer server,Actor a)throws Exception {
        var p=player(server,a);if(p==null)return;var services=Economy.serverServices(server);if(p.containerMenu instanceof BankingMenu w&&w.worldSetup())return;
        switch(a.setup) {
            case 0->{a.original=p;p.setInvulnerable(true);place(p,a.kiosk,"branch_kiosk");a.setup=1;}
            case 1->{if(readyTerminal(p,a.kiosk)){p.closeContainer();if(BankingController.openAdmin(p,a.kiosk))a.setup=2;}}
            case 2->{if(menu(server,a)!=null&&!menu(server,a).busy()){command(server,a,"brand_preview","",false);a.setup=3;}}
            case 3->{if(changed(server,a)){command(server,a,"brand_preview",BANK,false);a.setup=4;}}
            case 4->{if(review(server,a)){command(server,a,"brand_confirm",BANK,false);a.setup=5;}}
            case 5->{if(changed(server,a)){command(server,a,"banking","",false);a.setup=6;}}
            case 6->{if(changed(server,a)){command(server,a,"accounts","",false);a.setup=7;}}
            case 7->{if(changed(server,a)){command(server,a,"manage_accounts","",false);a.setup=8;}}
            case 8->{if(changed(server,a)){command(server,a,"branch_open","",false);a.setup=9;}}
            case 9->{if(a.account!=null){a.setup=10;break;}if(changed(server,a)&&!a.reading){a.reading=true;services.ledger().bankingContext(new PlayerId(a.uuid),menu(server,a).anchor(),null).whenComplete((c,e)->{a.reading=false;if(e!=null){failure="Primary fixture context "+e;return;}if(c.account()==null)return;a.account=c.account();var actor=new PlayerId(a.uuid);var adjustment=new SameCurrency.Adjustment(actor,new SameCurrency.Revision(a.account.id(),a.account.revision()),new Money(a.account.currency(),a.initial));var intent=new Journal.Intent(new OperationId(UUID.randomUUID()),new RequestId(UUID.randomUUID()),actor,SameCurrency.CREDIT,adjustment.request(),null,null);services.ledger().adjustments(SameCurrency.CREDIT,(who,kind)->who.equals(actor),(actual,instruction,reads)->true,null).submit(intent).completion().whenComplete((d,err)->{if(err!=null||d.outcome().status()!=Journal.Status.COMMITTED)failure="Fixture funding "+err;else{a.funded=true;log("Explicit finite fixture funding "+a.role+" minor="+a.initial+" operation="+intent.operationId().value());}});});if(a.account!=null)a.setup=10;}}
            case 10->{if(a.funded){p.closeContainer();place(p,a.teller,"teller");a.setup=11;}}
            case 11->{if(readyTerminal(p,a.teller)){p.closeContainer();if(BankingController.openAdmin(p,a.teller))a.setup=12;}}
            case 12->{if(menu(server,a)!=null&&!menu(server,a).busy()){command(server,a,"brand_preview","",false);a.setup=120;}}
            case 120->{if(changed(server,a)){command(server,a,"brand_preview",BANK,false);a.setup=121;}}
            case 121->{if(review(server,a)){command(server,a,"brand_confirm",BANK,false);a.setup=122;}}
            case 122->{if(changed(server,a)){command(server,a,"configure","BOTH CAD",false);a.setup=13;}}
            case 13->{if(changed(server,a)){command(server,a,"banking","",false);a.setup=14;}}
            case 14->{if(changed(server,a)&&menu(server,a).dashboard()!=null){require(menu(server,a).dashboard().accounts().rows().size()==1&&menu(server,a).dashboard().accounts().rows().getFirst().available().minorUnits()==a.initial,"Own exact funded dashboard only");a.setup=15;log("Ready role="+a.role+" authenticatedUUID="+a.uuid+" account="+a.account.id().value());}}
        }
    }
    private static void place(ServerPlayer p,BlockPos pos,String id){var level=p.serverLevel();level.setChunkForced(pos.getX()>>4,pos.getZ()>>4,true);level.getChunkAt(pos);for(var at:BlockPos.betweenClosed(pos.offset(-3,-1,-3),pos.offset(3,3,3)))level.setBlockAndUpdate(at,at.getY()<pos.getY()?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());p.teleportTo(pos.getX()+2,pos.getY(),pos.getZ()-2);p.setYRot(Direction.SOUTH.toYRot());var previous=p.getMainHandItem();var stack=new ItemStack(TerminalCatalog.get(id).item().get());p.setItemInHand(InteractionHand.MAIN_HAND,stack);try{require(((BlockItem)stack.getItem()).place(new BlockPlaceContext(p,InteractionHand.MAIN_HAND,stack,new BlockHitResult(Vec3.atCenterOf(pos.below()).add(0,.5,0),Direction.UP,pos.below(),false))).consumesAction(),"Place owned "+id);}finally{p.setItemInHand(InteractionHand.MAIN_HAND,previous);}}
    private static boolean readyTerminal(ServerPlayer p,BlockPos pos){return p.serverLevel().getBlockEntity(pos) instanceof TerminalBlockEntity e&&TerminalLifecycle.matches(p.serverLevel(),pos,e.generation());}
    private static ServerPlayer player(MinecraftServer s,Actor a){return a.uuid==null?null:s.getPlayerList().getPlayer(a.uuid);}
    private static BankingMenu menu(MinecraftServer s,Actor a){return player(s,a)!=null&&player(s,a).containerMenu instanceof BankingMenu m?m:null;}
    private static boolean changed(MinecraftServer s,Actor a)throws Exception{var m=menu(s,a);return ack(a)&&m!=null&&!m.busy()&&(!m.session().toString().equals(a.expectedSession)||m.revision()>a.expectedRevision);}
    private static boolean review(MinecraftServer s,Actor a)throws Exception{return changed(s,a)&&menu(s,a).operation().state()==BankingOperation.State.REVIEW;}
    private static boolean completed(MinecraftServer s,Actor a){var m=menu(s,a);return m!=null&&!m.busy()&&m.operation().state()==BankingOperation.State.COMPLETED;}
    private static void command(MinecraftServer s,Actor a,String action,String value,boolean duplicate)throws Exception{var m=menu(s,a);require(m!=null&&!m.busy(),"Live ready menu for command "+action);a.expectedRevision=m.revision();a.expectedSession=m.session().toString();write(a,"action",new BankingNetwork.Action(m.containerId,m.session(),m.revision(),action,value),duplicate);}
    private static void raw(Actor a,BankingNetwork.Action action)throws Exception{write(a,"raw",action,false);}
    private static void simple(Actor a,String kind)throws Exception{write(a,kind,null,false);}
    private static void write(Actor a,String kind,BankingNetwork.Action action,boolean duplicate)throws Exception{var p=new Properties();a.lastSerial=++serial;a.lastSentAt=System.nanoTime();p.setProperty("serial",Long.toString(a.lastSerial));p.setProperty("kind",kind);if(action!=null){p.setProperty("menu",Integer.toString(action.menuId()));p.setProperty("session",action.session().toString());p.setProperty("revision",Long.toString(action.revision()));p.setProperty("action",action.action());p.setProperty("value",action.value());p.setProperty("duplicate",Boolean.toString(duplicate));}var target=root.resolve(a.role+"-command.properties");var temp=root.resolve(a.role+"-command.tmp");try(var out=Files.newOutputStream(temp)){p.store(out,"Fixture coordination only; no credentials");}Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    private static boolean ack(Actor a)throws Exception{var p=root.resolve(a.role+"-ack.txt");return Files.isRegularFile(p)&&Files.readString(p).trim().equals(Long.toString(a.lastSerial));}
    private static void readFunds(MinecraftServer s,Actor a){if(a.reading)return;a.reading=true;Economy.serverServices(s).ledger().primaryFunds(FinanceMenuSupport.grant(player(s,a),Economy.serverServices(s))).whenComplete((f,e)->{a.reading=false;if(e!=null||f.isEmpty())failure="Fixture owner funds read "+e;else {var funds=f.orElseThrow().funds();a.available=funds.availableMinorUnits();a.reserved=funds.reservedMinorUnits();a.posted=funds.posted().minorUnits();}});}
    private static long cash(ServerPlayer p){long total=0;for(int n=0;n<p.getInventory().getContainerSize();n++){var stack=p.getInventory().getItem(n);var cash=CashCatalog.find(stack);if(cash.isPresent()&&cash.orElseThrow().currency()==EconomyCurrency.CAD)total=Math.addExact(total,Math.multiplyExact(cash.orElseThrow().minorUnits(),stack.getCount()));}return total;}
    private static void next(int next){stage=next;stageAt=System.nanoTime();log("stage="+stage);}
    private static long elapsed(){return System.nanoTime()-stageAt;}
    private static void log(String line){evidence.append(java.time.Instant.now()).append(' ').append(line).append('\n');System.out.println("MULTIPLAYER_PROBE "+line);}
    private static void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
}
