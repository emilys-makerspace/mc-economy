package ca.emilybelnavis.mcmods.economy.trade.entity.mixin;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import ca.emilybelnavis.mcmods.economy.trade.entity.TradeEntitySaves;
@Mixin(EntityStorage.class)
public abstract class EntityStorageSaveMixin {
    @Redirect(method="storeEntities",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/chunk/storage/SimpleRegionStorage;write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> economy$observeWrite(SimpleRegionStorage storage,ChunkPos position,CompoundTag payload){var future=storage.write(position,payload);TradeEntitySaves.observe(position,future);return future;}
}
