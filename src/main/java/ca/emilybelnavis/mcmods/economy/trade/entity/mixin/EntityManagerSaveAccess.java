package ca.emilybelnavis.mcmods.economy.trade.entity.mixin;
import java.util.function.Consumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(PersistentEntitySectionManager.class)
public interface EntityManagerSaveAccess {
    @Invoker("storeChunkSections") boolean economy$storeChunk(long chunk,Consumer<Entity> afterSave);
}
