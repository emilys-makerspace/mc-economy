package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.banker.*;
import ca.emilybelnavis.mcmods.economy.banker.desk.*;
import ca.emilybelnavis.mcmods.economy.banker.dialogue.*;
import ca.emilybelnavis.mcmods.economy.banker.entity.*;
import ca.emilybelnavis.mcmods.economy.village.*;
import ca.emilybelnavis.mcmods.economy.village.generation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;

/** Opt-in disposable fixture. Ordinary production code never calls this class. */
public final class BankerProbe {
    public static volatile String result,failure;
    public static volatile boolean captureReady,captured;public static volatile BlockPos camera;
    private static int stage;private static long deadline,until;private static UUID branch,employee;private static CompoundTag saved;
    private static BranchPlans.VillagePlanV1 plan;private static BranchPlans.BranchPlanV1 choice;
    private static CompletableFuture<?> pending;private static int elapsed;private static long rowRevision;
    private BankerProbe(){}
    public static boolean tick(MinecraftServer server)throws Exception{
        if(result!=null||failure!=null)return true;
        var svc=Economy.serverServices(server);var level=server.overworld();
        if(server.getPlayerList().getPlayers().isEmpty())return false;var player=server.getPlayerList().getPlayers().getFirst();
        if(stage==0){var root=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();require(root.getFileName().toString().matches("economy-banker-[0-9a-f-]{36}"),"unique disposable world guard");require(level.getSeed()==704L,"seed704");deadline=server.getTickCount()+9000;stage=1;}
        require(server.getTickCount()<deadline,"bounded fixture deadline stage="+stage);
        var publication=GenerationRuntime.publication(level.getChunkSource().getGenerator());if(publication==null||publication.snapshot()==null)return false;
        if(server.getTickCount()%100==0)System.out.println("BANKER_PROBE_STAGE "+stage);
        if(stage==1){
            // Promote the isolated physical fixture to entity-ticking before observing drops.
            for(int x=3;x<=5;x++)for(int z=3;z<=5;z++){level.getChunk(x,z);level.setChunkForced(x,z,true);}
            for(int x=60;x<=69;x++)for(int z=60;z<=69;z++)level.setBlock(new BlockPos(x,239,z),Blocks.STONE.defaultBlockState(),3);
            player.teleportTo(level,67.5,240,67.5,Set.of(),0,0);until=server.getTickCount()+60;stage=100;return false;
        }
        if(server.getTickCount()<until)return false;
        if(stage==100){deskCases(level,player);dropCase(level,player,net.minecraft.world.level.GameType.SURVIVAL);until=server.getTickCount()+5;stage=101;return false;}
        if(stage==101){measureDrops(level,1);dropCase(level,player,net.minecraft.world.level.GameType.CREATIVE);until=server.getTickCount()+5;stage=102;return false;}
        if(stage==102){measureDrops(level,0);stage=103;}
        if(stage==103){
            var source=level.getChunk(1097,-10);
            for(var start:source.getAllStarts().values())for(var piece:start.getPieces())if(piece instanceof VillageGenerationPiece metadata&&metadata.branchSlot()==-1)plan=metadata.plan();
            require(plan!=null,"known natural captured village");choice=plan.branches().stream().filter(b->b.state()==BranchPlans.State.RESERVED).findFirst().orElseThrow();branch=BranchPlans.branchId(BranchPlans.id(svc.worldId(),plan.source()),choice.slot());
            for(var manifest:choice.manifests())for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){level.getChunk(manifest.chunkX()+dx,manifest.chunkZ()+dz);level.setChunkForced(manifest.chunkX()+dx,manifest.chunkZ()+dz,true);}
            var p=choice.entrance();player.teleportTo(level,p.x()+.5,p.y(),p.z()+.5,Set.of(),0,0);until=server.getTickCount()+200;stage=2;return false;
        }
        if(server.getTickCount()<until)return false;
        if(pending!=null&&!pending.isDone())return false;
        if(stage==2){
            pending=svc.branches().find(branch).thenCompose(view->{require(view.isPresent(),"real branch captured");require(!Set.of("CONFLICT","REMOVED","OMITTED").contains(view.get().state()),"real branch activation conflict: "+view.get().state()+" / "+view.get().reason());StaffingRuntime.observe(level,view.get());return svc.staffing().rows(branch);}).toCompletableFuture();stage=3;return false;
        }
        if(stage==3){
            var rows=rows();int capacity=choice.desks().size();require(capacity>0&&capacity<=2,"bounded generated desk capacity");
            if(rows.size()!=capacity||rows.stream().anyMatch(r->!r.phase().equals("LIVE"))){until=server.getTickCount()+40;stage=2;return false;}
            for(var row:rows)require(level.getEntity(row.employee()) instanceof BankerEntity banker&&banker.assignmentId().equals(row.assignment()),"one actual entity per initial assignment");
            var rotation = Rotation.values()[choice.transform().quarterTurns()];
            boolean standard = choice.size() == BranchPlans.Size.STANDARD;
            for (var anchor : choice.services()) {
                var position = new BlockPos(anchor.position().x(), anchor.position().y(), anchor.position().z());
                var expectedFacing = rotation.rotate(standard && anchor.slot() > 0 ? Direction.EAST : Direction.SOUTH);
                require(level.getBlockState(position).getValue(ca.emilybelnavis.mcmods.economy.terminal.WideTerminalBlock.FACING) == expectedFacing,
                        "generated console faces the rotated room direction");
            }
            for (var anchor : choice.desks()) {
                var position = new BlockPos(anchor.position().x(), anchor.position().y(), anchor.position().z());
                require(level.getBlockState(position).getValue(DeskBlock.FACING) == rotation.rotate(standard ? Direction.EAST : Direction.SOUTH),
                        "generated desk faces the rotated room direction");
            }
            var row=rows.getFirst();employee=row.employee();rowRevision=row.revision();var banker=(BankerEntity)level.getEntity(employee);
            boolean compact = choice.size() == BranchPlans.Size.COMPACT;
            var view = standard ? VillageSites.bankRest(choice.transform())
                    : VillageSites.local(choice.transform().origin(), choice.transform().quarterTurns(),
                            compact ? 11 : 17, compact ? 9 : 11, compact ? 5 : 6, 1, compact ? 3 : 6);
            double viewX = view.x() + .5;
            double viewZ = view.z() + .5;
            float viewYaw = (float) Math.toDegrees(Math.atan2(-(banker.getX() - viewX), banker.getZ() - viewZ));
            player.teleportTo(level, viewX, view.y(), viewZ, Set.of(), viewYaw, 10);
            camera = player.blockPosition();
            captureReady = true;
            until = server.getTickCount() + 20;
            stage = 4;
            return false;
        }
        if(stage==4){
            if(!captured)return false;captureReady=false;
            var banker=(BankerEntity)level.getEntity(employee);require(StaffingRuntime.context(player,banker).isPresent(),"live entity/desk context");BankerDialogue.open(player,banker);
            require(player.containerMenu instanceof BankerMenu,"dedicated dialogue opened");var menu=(BankerMenu)player.containerMenu;require(menu.lines().isEmpty(),"opening dialogue has no automatic explanation");
            var ask=new BankerNetwork.Action(menu.containerId,menu.session(),menu.revision(),"ask","institution");require(menu.accept(player,ask),"explicit offered question accepted");BankerDialogue.action(player,menu,"ask","institution");require(!menu.lines().isEmpty(),"answer only after question");
            require(!menu.accept(player,ask),"stale menu revision rejected");player.closeContainer();
            var context=StaffingRuntime.context(player,banker).orElseThrow();require(context.kiosk()!=null,"loaded bound service kiosk");
            var kiosk=Vec3.atCenterOf(context.kiosk());var midpoint=kiosk.add(banker.position()).scale(.5);
            player.teleportTo(level,midpoint.x,midpoint.y,midpoint.z,Set.of(),0,0);
            BankerDialogue.open(player,banker);require(player.containerMenu instanceof BankerMenu,"fresh route dialogue");
            var route=(BankerMenu)player.containerMenu;var action=new BankerNetwork.Action(route.containerId,route.session(),route.revision(),"banking","");
            require(route.accept(player,action),"offered banking route accepted");BankerDialogue.action(player,route,"banking","");until=server.getTickCount()+100;stage=40;return false;
        }
        if(stage==40){
            require(player.containerMenu instanceof ca.emilybelnavis.mcmods.economy.banking.BankingMenu,"dialogue routes to ordinary fresh banking controller menu");player.closeContainer();
            var banker=(BankerEntity)level.getEntity(employee);
            // A positive unload reason is not death. Reload the same saved UUID and complete assignment.
            saved=new CompoundTag();require(banker.save(saved),"ordinary entity NBT saved");banker.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);until=server.getTickCount()+120;stage=5;return false;
        }
        if(stage==5){pending=svc.staffing().rows(branch).toCompletableFuture();stage=6;return false;}
        if(stage==6){var row=rows().stream().filter(r->r.employee().equals(employee)).findFirst().orElseThrow();require(row.phase().equals("LIVE")&&row.elapsed()==0&&row.revision()==rowRevision,"unloaded employee remains LIVE without vacancy/timer");
            var reloaded=EntityType.loadEntityRecursive(saved,level,e->e);require(reloaded instanceof BankerEntity,"registered entity NBT loader");require(level.addFreshEntity(reloaded),"same UUID reload accepted");until=server.getTickCount()+40;stage=7;return false;}
        if(stage==7){var banker=(BankerEntity)level.getEntity(employee);require(banker!=null&&banker.assignmentId().equals(saved.getCompound("economy_banker").getUUID("assignment")),"unload reload retains assignment UUID");
            // Authoritative positive death, unlike a failed lookup, starts vacancy.
            banker.hurt(level.damageSources().genericKill(),Float.MAX_VALUE);until=server.getTickCount()+140;stage=8;return false;}
        if(stage==8){pending=svc.staffing().rows(branch).toCompletableFuture();stage=9;return false;}
        if(stage==9){var row=rows().stream().filter(r->r.employee().equals(employee)).findFirst().orElseThrow();require(row.phase().equals("VACANT"),"positive death produces durable VACANT");pending=StaffingRuntime.pause(server,branch,new PlayerId(player.getUUID()),true,new AtomicBoolean(true)).toCompletableFuture();stage=10;return false;}
        if(stage==10){require(pending.join().toString().contains("COMMITTED")&&pending.join().toString().contains("STAFF_PAUSED"),"pause committed: "+pending.join());pending=svc.staffing().rows(branch).toCompletableFuture();stage=11;return false;}
        if(stage==11){elapsed=vacant().elapsed();require(elapsed>0,"pause flushes eligible elapsed ticks");until=server.getTickCount()+250;stage=12;return false;}
        if(stage==12){pending=svc.staffing().rows(branch).toCompletableFuture();stage=13;return false;}
        if(stage==13){require(vacant().elapsed()==elapsed,"paused vacancy does not accrue");pending=StaffingRuntime.pause(server,branch,new PlayerId(player.getUUID()),false,new AtomicBoolean(true)).toCompletableFuture();stage=14;return false;}
        if(stage==14){require(pending.join().toString().contains("COMMITTED")&&pending.join().toString().contains("STAFF_RESUMED"),"resume committed: "+pending.join());until=server.getTickCount()+250;stage=15;return false;}
        if(stage==15){pending=svc.staffing().rows(branch).toCompletableFuture();stage=16;return false;}
        if(stage==16){require(vacant().elapsed()>elapsed,"resumed vacancy accrues eligible ticks");result="PASS disposable real bank: desk item four rotations, blocked-satellite rejection preserves gold/air, satellite teardown with survival one/creative zero desk drops; actual initial banker capacity; explicit-only dialogue and stale revision rejection; entity NBT unload/reload remains LIVE without timer; authoritative death VACANT; pause flush/no accrual/resume. Explicit banking route opens ordinary controller menu with fresh entity/desk/kiosk checks.";System.out.println("BANKER_PROBE_PASS "+result);return true;}
        return false;
    }
    @SuppressWarnings("unchecked") private static List<StaffingRecords.Row> rows(){return (List<StaffingRecords.Row>)pending.join();}
    private static StaffingRecords.Row vacant(){return rows().stream().filter(r->r.employee().equals(employee)).findFirst().orElseThrow();}
    private static void deskCases(ServerLevel level,ServerPlayer player){
        var anchor=new BlockPos(64,240,64);for(int x=60;x<=69;x++)for(int z=60;z<=69;z++)level.setBlock(new BlockPos(x,239,z),Blocks.STONE.defaultBlockState(),3);
        for(var facing:Direction.Plane.HORIZONTAL){
            player.setYRot(facing.getOpposite().toYRot());var stack=new ItemStack(DeskCatalog.ITEM.get());var hit=new BlockHitResult(Vec3.atCenterOf(anchor.below()),Direction.UP,anchor.below(),false);
            var context=new BlockPlaceContext(player,InteractionHand.MAIN_HAND,stack,hit);
            require(DeskCatalog.ITEM.get().place(context).consumesAction(),"actual desk item placement "+facing);
            var state=level.getBlockState(anchor);require(state.getValue(DeskBlock.FACING)==facing,"desk rotation "+facing);
            var entity=(DeskBlockEntity)level.getBlockEntity(anchor);require(entity!=null&&DeskGeometry.matches(level,anchor,entity.generation()),"four-cell generation identity");
            var cells=DeskGeometry.cells(anchor,state);level.destroyBlock(cells.get(3),false);require(cells.stream().allMatch(p->level.getBlockState(p).isAir()),"satellite teardown");
            var blocked=DeskGeometry.cell(anchor,facing,3);level.setBlock(blocked,Blocks.GOLD_BLOCK.defaultBlockState(),3);
            require(!DeskCatalog.ITEM.get().place(new BlockPlaceContext(player,InteractionHand.MAIN_HAND,new ItemStack(DeskCatalog.ITEM.get()),hit)).consumesAction(),"blocked satellite rejects item placement");
            require(level.getBlockState(blocked).is(Blocks.GOLD_BLOCK)&&level.getBlockState(anchor).isAir(),"rejected placement preserves gold and original air");level.setBlock(blocked,Blocks.AIR.defaultBlockState(),3);
        }
    }
    private static void dropCase(ServerLevel level,ServerPlayer player,net.minecraft.world.level.GameType mode){
        var anchor=new BlockPos(64,240,64);var area=new AABB(anchor).inflate(8);
        for(var item:level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,area))item.discard();
        player.setGameMode(mode);player.setYRot(Direction.SOUTH.toYRot());
        var hit=new BlockHitResult(Vec3.atCenterOf(anchor.below()),Direction.UP,anchor.below(),false);
        require(DeskCatalog.ITEM.get().place(new BlockPlaceContext(player,InteractionHand.MAIN_HAND,new ItemStack(DeskCatalog.ITEM.get()),hit)).consumesAction(),"drop fixture real item placement");
        var cells=DeskGeometry.cells(anchor,level.getBlockState(anchor));
        require(player.gameMode.destroyBlock(cells.get(3)),"actual player destruction succeeds "+mode);
        require(cells.stream().allMatch(p->level.getBlockState(p).isAir()),"player satellite teardown "+mode);
    }
    private static void measureDrops(ServerLevel level,int expected){
        var area=new AABB(new BlockPos(64,240,64)).inflate(8);
        int count=level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,area).stream().filter(e->e.getItem().is(DeskCatalog.ITEM.get())).mapToInt(e->e.getItem().getCount()).sum();
        require(count==expected,"entity-ticking fixture desk drops expected="+expected+" actual="+count);
        for(var item:level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,area))item.discard();
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
