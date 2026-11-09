package ca.emilybelnavis.mcmods.economy.trade.entity.mixin;

import ca.emilybelnavis.mcmods.economy.trade.entity.TradeHooks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(MerchantResultSlot.class)
public abstract class MerchantResultSlotMixin implements TradeHooks.ResultAccess {
  @Shadow @Final private Merchant merchant;

  public boolean economy$ownedResult() {
    return TradeHooks.owned(merchant);
  }

  @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
  private void economy$beforeRemoval(int count, CallbackInfoReturnable<ItemStack> cir) {
    if (economy$ownedResult()) cir.setReturnValue(ItemStack.EMPTY);
  }

  @Inject(method = "onTake", at = @At("HEAD"), cancellable = true)
  private void economy$beforeNativeEffects(Player player, ItemStack stack, CallbackInfo ci) {
    if (economy$ownedResult()) ci.cancel();
  }

  @Inject(method = "checkTakeAchievements", at = @At("HEAD"), cancellable = true)
  private void economy$noCraftEffects(ItemStack stack, CallbackInfo ci) {
    if (economy$ownedResult()) ci.cancel();
  }

  @Inject(method = "onQuickCraft", at = @At("HEAD"), cancellable = true)
  private void economy$noQuickCraft(ItemStack stack, int count, CallbackInfo ci) {
    if (economy$ownedResult()) ci.cancel();
  }
}
