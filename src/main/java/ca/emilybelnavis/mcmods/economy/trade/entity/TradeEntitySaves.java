package ca.emilybelnavis.mcmods.economy.trade.entity;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.trade.entity.mixin.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

/** Native whole-chunk entity save followed by independent region read; never a global blocking save. */
public final class TradeEntitySaves {
    private TradeEntitySaves(){}
    private static final int LIMIT=1_048_576;
    private static final class Capture {final ChunkPos chunk;CompletableFuture<Void> future;Capture(ChunkPos chunk){this.chunk=chunk;}}
    private static final ThreadLocal<Capture> ACTIVE=new ThreadLocal<>();
    /** Narrow mixin observer; outside a scoped request it changes no native behavior. */
    public static void observe(ChunkPos chunk,CompletableFuture<Void> future){var capture=ACTIVE.get();if(capture!=null&&capture.chunk.equals(chunk)){if(capture.future!=null)throw new IllegalStateException("Duplicate entity save");capture.future=future;}}
    public static CompletionStage<TradeProgression.SaveProof> witness(ServerPlayer player,AbstractVillager trader,TradeProgression.Attempt attempt){
        var server=player.server;if(!server.isSameThread()||trader.getServer()!=server||!trader.isAlive()||ACTIVE.get()!=null)return CompletableFuture.failedStage(new IllegalStateException("Entity save session unavailable"));
        var level=(net.minecraft.server.level.ServerLevel)trader.level();var position=trader.chunkPosition();if(!level.areEntitiesLoaded(position.toLong()))return CompletableFuture.failedStage(new IllegalStateException("Trader entity chunk is not fully loaded"));
        var svc=Economy.serverServices(server);if(svc==null)return CompletableFuture.failedStage(new IllegalStateException("Economy unavailable"));
        var dimension=level.dimension();var root=server.getWorldPath(LevelResource.ROOT);var folder=DimensionType.getStorageFolder(dimension,root).resolve("entities");UUID id=trader.getUUID();
        var capture=new Capture(position);try{ACTIVE.set(capture);var manager=((ServerLevelEntityAccess)level).economy$entityManager();if(!((EntityManagerSaveAccess)manager).economy$storeChunk(position.toLong(),entity->{}))throw new IllegalStateException("Trader entity save is pending");}finally{ACTIVE.remove();}
        if(capture.future==null)return CompletableFuture.failedStage(new IllegalStateException("Trader native save was not observed"));
        var result=new CompletableFuture<TradeProgression.SaveProof>();capture.future.whenComplete((ignored,error)->server.execute(()->{
            if(error!=null){result.completeExceptionally(new IllegalStateException("Trader save failed",error));return;}
            svc.storage(new PlayerId(player.getUUID()),()->{
                var region=folder.resolve("r."+position.getRegionX()+"."+position.getRegionZ()+".mca");
                Path safe=root;for(var part:root.relativize(region)){safe=safe.resolve(part);if(Files.isSymbolicLink(safe))throw new IllegalStateException("Unsafe entity save path");}
                if(!Files.isRegularFile(region))throw new IllegalStateException("Trader saved region is missing");
                try(var file=new RegionFile(new RegionStorageInfo("economy-trader",dimension,"entities"),region,folder,false);var input=file.getChunkDataInputStream(position)){
                    if(input==null)throw new IllegalStateException("Trader save is missing");var actual=NbtIo.read(input,NbtAccounter.create(LIMIT));var roots=actual.getList("Entities",Tag.TAG_COMPOUND);int[] remaining={1024};for(int n=0;n<roots.size();n++){var found=find(roots.getCompound(n),id,remaining,0);if(found!=null)return found;}throw new IllegalStateException("Trader absent from saved chunk");
                }catch(java.io.IOException failure){throw new IllegalStateException("Trader save unreadable",failure);}
            }).whenComplete((actual,failure)->{if(failure!=null)result.completeExceptionally(failure);else try{result.complete(TradeProgression.witness(attempt,actual,"entity-region:"+position.x+":"+position.z));}catch(RuntimeException mismatch){result.completeExceptionally(mismatch);}});
        }));return result;
    }
    private static CompoundTag find(CompoundTag tag,UUID id,int[] remaining,int depth){if(--remaining[0]<0||depth>16)throw new IllegalStateException("Entity witness traversal limit");if(tag.hasUUID("UUID")&&tag.getUUID("UUID").equals(id))return tag.copy();var passengers=tag.getList("Passengers",Tag.TAG_COMPOUND);for(int i=0;i<passengers.size();i++){var found=find(passengers.getCompound(i),id,remaining,depth+1);if(found!=null)return found;}return null;}
}
