package ca.emilybelnavis.mcmods.economy.trade.entity.mixin;

import ca.emilybelnavis.mcmods.economy.trade.entity.TradeHooks;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Slot.class)
public abstract class SlotMixin {
    @Inject(method="mayPickup",at=@At("HEAD"),cancellable=true)
    private void economy$ownedOutputPickup(Player player,CallbackInfoReturnable<Boolean> cir){if((Object)this instanceof TradeHooks.ResultAccess slot&&slot.economy$ownedResult())cir.setReturnValue(false);}
    @Inject(method="getItem",at=@At("HEAD"),cancellable=true)
    private void economy$ownedCreativeClone(CallbackInfoReturnable<ItemStack> cir){if((Object)this instanceof TradeHooks.ResultAccess slot&&slot.economy$ownedResult())cir.setReturnValue(ItemStack.EMPTY);}
}
