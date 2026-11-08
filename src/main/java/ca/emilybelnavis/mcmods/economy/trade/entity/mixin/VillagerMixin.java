package ca.emilybelnavis.mcmods.economy.trade.entity.mixin;

import ca.emilybelnavis.mcmods.economy.trade.entity.TradeHooks;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(Villager.class)
public abstract class VillagerMixin {
    @Inject(method={"restock","catchUpDemand","updateDemand","resetNumberOfRestocks"},at=@At("HEAD"),cancellable=true)
    private void economy$ownedRestock(CallbackInfo ci){if(TradeHooks.owned(this))ci.cancel();}
    @Inject(method="shouldRestock",at=@At("HEAD"),cancellable=true)
    private void economy$ownedRestockClock(CallbackInfoReturnable<Boolean> cir){if(TradeHooks.owned(this))cir.setReturnValue(false);}
    @Inject(method="startTrading",at=@At("HEAD"),cancellable=true)
    private void economy$noNativeSession(Player player,CallbackInfo ci){if(TradeHooks.owned(this))ci.cancel();}
}
