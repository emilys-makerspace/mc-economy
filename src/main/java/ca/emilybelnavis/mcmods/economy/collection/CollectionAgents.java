package ca.emilybelnavis.mcmods.economy.collection;

import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle;
import ca.emilybelnavis.mcmods.economy.debt.DebtRecords;
import ca.emilybelnavis.mcmods.economy.foundation.PartyAuthorization;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Server-thread presentation manager. Its immutable snapshots arrive from the owned storage worker. */
public final class CollectionAgents {
    private static final long SNAPSHOT_MILLIS=1000;
    private static final Map<MinecraftServer,Runtime> RUNTIMES=new IdentityHashMap<>();
    private CollectionAgents() {}

    public static void tick(MinecraftServer server) {
        var services=Economy.serverServices(server);
        if(services==null||services.state()!=ServerServices.State.READY) {
            var runtime=RUNTIMES.get(server);
            if(runtime!=null&&services!=null&&services.state()==ServerServices.State.UNAVAILABLE)stop(server);
            return;
        }
        var runtime=RUNTIMES.computeIfAbsent(server,s->new Runtime(s,services));
        if(runtime.services!=services){stop(server);return;}
        runtime.tick();
    }
    public static void stop(MinecraftServer server) {
        var runtime=RUNTIMES.remove(server);if(runtime==null)return;runtime.stopped=true;
        for(var entry:List.copyOf(runtime.entries.values()))runtime.remove(entry,true);
        runtime.entries.clear();runtime.order.clear();runtime.queued.clear();
    }
    public static boolean canInteract(ServerPlayer player,CollectionAgent agent) {
        if (!agent.managed()) {
            var services = Economy.serverServices(player.server);
            return services != null && services.state() == ServerServices.State.READY
                && services.metrics().economy().ready() && player.isAlive() && !player.isSpectator()
                && !player.isChangingDimension() && agent.isAlive() && !agent.isRemoved()
                && agent.level() == player.level() && player.distanceToSqr(agent) <= 36
                && CollectionProtection.allows(player, agent.position(), CollectionProtection.Action.INTERACT);
        }
        var runtime=RUNTIMES.get(player.server);var entry=runtime==null?null:runtime.entries.get(player.getUUID());
        return entry!=null&&entry.player==player&&entry.agent==agent&&runtime.valid(entry,agent,runtime.services.financialMillis())
            &&player.distanceToSqr(agent)<=36&&CollectionProtection.allows(player,agent.position(),CollectionProtection.Action.INTERACT);
    }
    public static void refresh(ServerPlayer player) {
        var runtime=RUNTIMES.get(player.server);if(runtime==null)return;
        var entry=runtime.entries.get(player.getUUID());if(entry!=null){entry.snapshot=null;entry.nextRead=0;}
    }
    static void entityTick(CollectionAgent agent) {
        if (!agent.managed()) return;
        if(!(agent.level() instanceof ServerLevel level))return;
        var runtime=RUNTIMES.get(level.getServer());
        var entry=runtime==null||agent.debtor()==null?null:runtime.entries.get(agent.debtor());
        if(entry==null||entry.agent!=agent){agent.discard();return;}
        runtime.follow(entry,agent);
    }
    static void died(CollectionAgent agent) {
        var entry=entry(agent);if(entry==null)return;var runtime=RUNTIMES.get(((ServerLevel)agent.level()).getServer());
        entry.death=true;entry.snapshot=null;entry.search=false;agent.getNavigation().stop();
        runtime.transition(entry,CollectionPresence.Action.DEATH,entry.lease,runtime.services.financialMillis(),false);
    }
    static void removed(CollectionAgent agent) {
        var entry=entry(agent);if(entry==null)return;
        var runtime=RUNTIMES.get(((ServerLevel)agent.level()).getServer());
        entry.agent=null;
        if(!entry.death)runtime.release(entry);
    }
    private static Entry entry(CollectionAgent agent) {
        if(!(agent.level() instanceof ServerLevel level)||agent.debtor()==null)return null;
        var runtime=RUNTIMES.get(level.getServer());var entry=runtime==null?null:runtime.entries.get(agent.debtor());
        return entry!=null&&entry.agent==agent&&Objects.equals(entry.lease,agent.presentation())?entry:null;
    }

    private static final class Entry {
        final ServerPlayer player;final PartyAuthorization.Grant grant;
        CollectionPresence.Snapshot snapshot;CollectionAgent agent;UUID lease;
        boolean reading,transitioning,releasing,death,search,relocation,greetingCleared;
        long nextRead,nextSearch,attempt,lastReminder,greetingUntil;int candidate,blockedTicks;
        double previousDistance=Double.POSITIVE_INFINITY;
        Entry(ServerPlayer player,PartyAuthorization.Grant grant){this.player=player;this.grant=grant;}
    }
    private static final class Runtime {
        final MinecraftServer server;final ServerServices services;final UUID session;
        final Map<UUID,Entry> entries=new HashMap<>();final ArrayDeque<UUID> order=new ArrayDeque<>();final Set<UUID> queued=new HashSet<>();
        long ticks;int playerCursor;boolean stopped;
        Runtime(MinecraftServer server,ServerServices services){this.server=server;this.services=services;this.session=services.sessionId();}
        boolean usable(){return Economy.serverServices(server)==services&&services.sessionId().equals(session)&&services.state()==ServerServices.State.READY;}
        boolean current(){return !stopped&&usable()&&services.metrics().economy().ready();}
        boolean online(Entry entry){return server.getPlayerList().getPlayer(entry.player.getUUID())==entry.player;}
        boolean eligible(Entry entry,long now) {
            return current()&&online(entry)&&entry.snapshot!=null&&now>=entry.snapshot.asOf()
                &&now-entry.snapshot.asOf()<=SNAPSHOT_MILLIS+50L*server.getPlayerList().getPlayers().size()&&entry.snapshot.cases().stream().anyMatch(c->DebtLifecycle.demands(c,now));
        }
        boolean valid(Entry entry,CollectionAgent agent,long now) {
            return eligible(entry,now)&&!entry.death&&!entry.releasing&&agent.isAlive()&&!agent.isRemoved()
                &&entry.player.isAlive()&&!entry.player.isSpectator()&&!entry.player.isChangingDimension()
                &&agent.level()==entry.player.level()&&Objects.equals(entry.lease,agent.presentation())
                &&entry.snapshot.state().phase()==CollectionPresence.Phase.LIVE&&entry.lease.equals(entry.snapshot.state().lease());
        }
        void tick() {
            ticks++;
            var players=server.getPlayerList().getPlayers();
            if(!players.isEmpty()) {
                var player=players.get(Math.floorMod(playerCursor++,players.size()));
                var entry=entries.get(player.getUUID());
                if(entry!=null&&entry.player!=player){remove(entry,true);entries.remove(player.getUUID());entry=null;}
                if(entry==null&&services.worldId()!=null) {
                    var owner=new PartyRef(services.worldId(),new PartyRef.Player(new PlayerId(player.getUUID())));
                    var admitted=PartyAuthorization.player(player,Economy::serverServices,owner);
                    if(admitted.isPresent()){entry=new Entry(player,admitted.orElseThrow());entries.put(player.getUUID(),entry);enqueue(player.getUUID());}
                }
                if(entry!=null)read(entry);
            }
            for(int budget=0;budget<CollectionPlacement.GLOBAL_BUDGET&&!order.isEmpty();budget++) {
                UUID id=order.removeFirst();queued.remove(id);var entry=entries.get(id);if(entry==null)continue;
                enqueue(id);
                if(!online(entry)) {
                    remove(entry,true);entries.remove(id);continue;
                }
                service(entry);
            }
        }
        void enqueue(UUID owner) {if(queued.add(owner))order.addLast(owner);}
        void read(Entry entry) {
            if(entry.reading||ticks<entry.nextRead||!current())return;
            entry.reading=true;entry.nextRead=ticks+20;
            services.ledger().collectionAgentState(entry.grant,null,16).whenComplete((found,error)->server.execute(()->{
                entry.reading=false;
                if(!current()||entries.get(entry.player.getUUID())!=entry||!online(entry))return;
                if(error!=null||found==null||found.isEmpty()){entry.snapshot=null;return;}
                entry.snapshot=found.orElseThrow();
                if(entry.agent!=null&&(entry.snapshot.state().phase()!=CollectionPresence.Phase.LIVE
                        ||!Objects.equals(entry.lease,entry.snapshot.state().lease()))) {
                    remove(entry,false);entry.lease=null;
                }
                if(!entry.transitioning&&entry.agent==null) {
                    var state=entry.snapshot.state();
                    if(state.phase()==CollectionPresence.Phase.LIVE) {
                        if(entry.lease!=null&&entry.lease.equals(state.lease())&&!entry.death)release(entry);
                        else {entry.lease=state.lease();transition(entry,CollectionPresence.Action.RECONCILE,entry.lease,0,false);}
                    } else {entry.lease=null;entry.death=false;entry.releasing=false;}
                }
            }));
        }
        void service(Entry entry) {
            long now=services.financialMillis();
            if(entry.agent!=null) {
                if(entry.agent.isRemoved()){entry.agent=null;if(!entry.death)release(entry);}
                else if(entry.agent.level()!=entry.player.level()||!entry.player.isAlive()||entry.player.isSpectator()||entry.player.isChangingDimension())remove(entry,true);
            }
            if(entry.agent!=null&&entry.snapshot!=null&&entry.snapshot.cases().stream().noneMatch(c->DebtLifecycle.demands(c,now))) {
                remove(entry,true);return;
            }
            if(entry.transitioning||entry.releasing||!eligible(entry,now))return;
            var state=entry.snapshot.state();
            if(entry.agent==null&&entry.lease==null&&state.phase()==CollectionPresence.Phase.LIVE) {
                entry.lease=state.lease();transition(entry,CollectionPresence.Action.RECONCILE,entry.lease,0,false);return;
            }
            if(entry.agent==null&&(state.phase()==CollectionPresence.Phase.LIVE||state.cooldownUntil()>now||entry.death))return;
            if(!entry.search&&entry.agent==null&&ticks>=entry.nextSearch&&entry.player.isAlive()&&!entry.player.isSpectator()&&!entry.player.isChangingDimension())startSearch(entry,false);
            if(entry.search)candidate(entry);
        }
        void startSearch(Entry entry,boolean relocation) {
            if(ticks<entry.nextSearch||entry.transitioning||entry.releasing)return;
            entry.search=true;entry.relocation=relocation;entry.candidate=0;entry.attempt++;
        }
        void candidate(Entry entry) {
            if(!entry.search||entry.transitioning||!eligible(entry,services.financialMillis()))return;
            var first=entry.snapshot.cases().stream().filter(c->DebtLifecycle.demands(c,services.financialMillis())).findFirst().orElse(null);
            if(first==null){entry.search=false;return;}
            var offset=CollectionPlacement.candidate(first.world().value(),entry.player.getUUID(),first.id(),entry.attempt,entry.candidate++);
            BlockPos position=entry.player.blockPosition().offset(offset.x(),offset.y(),offset.z());
            if(safe(entry.player,position)) {
                entry.search=false;entry.nextSearch=ticks+CollectionPlacement.RETRY_TICKS;
                if(entry.relocation&&entry.agent!=null&&valid(entry,entry.agent,services.financialMillis())) {
                    entry.agent.getNavigation().stop();entry.agent.moveTo(position.getX()+0.5,position.getY(),position.getZ()+0.5,entry.player.getYRot(),0);
                    entry.blockedTicks=0;entry.previousDistance=Double.POSITIVE_INFINITY;
                } else if(entry.agent==null)reserve(entry,position);
            } else if(entry.candidate>=CollectionPlacement.CANDIDATES) {
                entry.search=false;entry.nextSearch=ticks+CollectionPlacement.RETRY_TICKS;
            }
        }
        void reserve(Entry entry,BlockPos position) {
            entry.lease=UUID.randomUUID();entry.death=false;entry.transitioning=true;UUID lease=entry.lease;
            services.ledger().collectionAgentTransition(entry.grant,CollectionPresence.Action.RESERVE,lease,0).whenComplete((outcome,error)->server.execute(()->{
                entry.transitioning=false;
                if(error!=null||outcome==null||outcome.status()!=Journal.Status.COMMITTED||!outcome.result().code().equals("COLLECTION_AGENT_RESERVED")) {
                    entry.snapshot=null;entry.lease=null;entry.nextRead=0;return;
                }
                if(!current()||entries.get(entry.player.getUUID())!=entry||!online(entry)||entry.releasing||entry.player.isChangingDimension()
                        ||!eligible(entry,services.financialMillis())||!safe(entry.player,position)) {release(entry);return;}
                var agent=CollectionCatalog.AGENT.get().create(entry.player.serverLevel());
                if(agent==null){release(entry);return;}
                agent.bind(entry.player.getUUID(),lease);agent.setCustomName(Component.translatable("ui.dialogue.collection_agent.5fc2c1"));
                agent.moveTo(position.getX()+0.5,position.getY(),position.getZ()+0.5,entry.player.getYRot(),0);
                entry.agent=agent;
                if(!entry.player.serverLevel().addFreshEntity(agent)){entry.agent=null;release(entry);return;}
                entry.greetingUntil=System.currentTimeMillis()+8000;entry.greetingCleared=false;entry.lastReminder=services.financialMillis();entry.snapshot=null;entry.nextRead=0;
            }));
        }
        void transition(Entry entry,CollectionPresence.Action action,UUID lease,long observed,boolean release) {
            if(lease==null||!usable())return;entry.transitioning=true;
            services.ledger().collectionAgentTransition(entry.grant,action,lease,observed).whenComplete((outcome,error)->server.execute(()->{
                entry.transitioning=false;
                if(release)entry.releasing=false;
                if(error==null&&outcome!=null&&outcome.status()==Journal.Status.COMMITTED) {
                    if(entry.agent==null||action!=CollectionPresence.Action.RESERVE)entry.lease=null;
                    if(action!=CollectionPresence.Action.DEATH)entry.death=false;
                }
                entry.snapshot=null;entry.nextRead=0;
            }));
        }
        void release(Entry entry) {
            if(entry.lease==null||entry.releasing||entry.death)return;
            entry.releasing=true;
            transition(entry,CollectionPresence.Action.RELEASE,entry.lease,0,true);
        }
        void remove(Entry entry,boolean release) {
            entry.search=false;
            var agent=entry.agent;entry.agent=null;
            if(release)release(entry);
            if(agent!=null&&!agent.isRemoved())agent.discard();
        }
        void follow(Entry entry,CollectionAgent agent) {
            long now=services.financialMillis();
            if(!valid(entry,agent,now)){agent.getNavigation().stop();return;}
            if(!CollectionProtection.allows(entry.player,agent.position(),CollectionProtection.Action.SPAWN)){remove(entry,true);return;}
            double distance=entry.player.distanceToSqr(agent);
            if(distance<=64) {
                if(System.currentTimeMillis()<entry.greetingUntil&&agent.tickCount%20==0)entry.player.displayClientMessage(UiText.component(entry.player, UiText.t("ui.dialogue.a_collection_representative_is_available_rig.7b4116")),true);
                else if(System.currentTimeMillis()>=entry.greetingUntil&&!entry.greetingCleared) {
                    entry.player.displayClientMessage(Component.empty(),true);entry.greetingCleared=true;
                } else if(now-entry.lastReminder>=DebtRecords.DAY) {
                    entry.player.displayClientMessage(UiText.component(entry.player, UiText.t("ui.dialogue.you_can_review_collectible_cases_with_your_c.3a8b4b")),false);entry.lastReminder=now;
                }
            }
            if(distance<=9){agent.getNavigation().stop();entry.blockedTicks=0;return;}
            if(agent.tickCount%20!=0)return;
            if(distance>=entry.previousDistance-0.25)entry.blockedTicks+=20;else entry.blockedTicks=0;
            entry.previousDistance=distance;
            if(distance>1024||entry.blockedTicks>=200) {agent.getNavigation().stop();startSearch(entry,true);return;}
            if(loadedNavigationArea(agent))agent.getNavigation().moveTo(entry.player.getX(),entry.player.getY(),entry.player.getZ(),1.0);
            else agent.getNavigation().stop();
        }
    }
    private static boolean loadedNavigationArea(CollectionAgent agent) {
        var center=agent.blockPosition();var level=agent.level();
        for(int x=(center.getX()-40)>>4;x<=(center.getX()+40)>>4;x++)
            for(int z=(center.getZ()-40)>>4;z<=(center.getZ()+40)>>4;z++)if(!level.hasChunk(x,z))return false;
        return true;
    }
    private static boolean safe(ServerPlayer player,BlockPos position) {
        var level=player.serverLevel();
        if(!player.isAlive()||player.isSpectator()||player.isChangingDimension()||!level.hasChunkAt(position)
                ||position.getY()<level.getMinBuildHeight()+1||position.getY()+2>=level.getMaxBuildHeight())return false;
        var center=Vec3.atBottomCenterOf(position);double dx=center.x-player.getX(),dz=center.z-player.getZ();
        if(dx*dx+dz*dz<64||dx*dx+dz*dz>144||Math.abs(position.getY()-player.getY())>3)return false;
        if(!CollectionProtection.allows(player,center,CollectionProtection.Action.SPAWN))return false;
        var below=position.below();var support=level.getBlockState(below);
        if(!support.isFaceSturdy(level,below,Direction.UP)||hazard(support)||!support.getFluidState().isEmpty())return false;
        if(!level.getBlockState(position).isAir()||!level.getBlockState(position.above()).isAir())return false;
        var box=CollectionCatalog.AGENT.get().getDimensions().makeBoundingBox(center);
        return level.getWorldBorder().isWithinBounds(box)&&level.noCollision(box);
    }
    private static boolean hazard(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.FIRE)||state.is(Blocks.SOUL_FIRE)||state.is(Blocks.CACTUS)||state.is(Blocks.MAGMA_BLOCK)
            ||state.is(Blocks.NETHER_PORTAL)||state.is(Blocks.END_PORTAL)||state.is(Blocks.END_GATEWAY);
    }
}
