package ca.emilybelnavis.mcmods.economy.valuation.mixin;

import ca.emilybelnavis.mcmods.economy.valuation.ValuationRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;

/** Covers synchronous exceptions as well as asynchronous installation failures. */
@Mixin(MinecraftServer.class)
public abstract class ServerReloadMixin {
  @WrapMethod(method = "reloadResources")
  private CompletableFuture<Void> economy$valuationReload(
      Collection<String> packs, Operation<CompletableFuture<Void>> original) {
    var server = (MinecraftServer) (Object) this;
    var reload = ValuationRuntime.beginReload(server);
    try {
      var result = original.call(packs);
      result.whenComplete(
          (ignored, failure) -> ValuationRuntime.finishReload(server, reload, failure));
      return result;
    } catch (RuntimeException | Error failure) {
      ValuationRuntime.finishReload(server, reload, failure);
      throw failure;
    }
  }
}
