package ca.emilybelnavis.mcmods.economy.trade.entity.mixin;

import ca.emilybelnavis.mcmods.economy.trade.entity.TradeHooks;
import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(MerchantContainer.class)
public abstract class MerchantContainerMixin {
  @Shadow @Final private Merchant merchant;
  @Shadow @Final private NonNullList<ItemStack> itemStacks;
  @Shadow private MerchantOffer activeOffer;
  @Shadow private int futureXp;

  @Inject(method = "updateSellItem", at = @At("HEAD"), cancellable = true)
  private void economy$noNativeResult(CallbackInfo ci) {
    if (TradeHooks.owned(merchant)) {
      itemStacks.set(2, ItemStack.EMPTY);
      activeOffer = null;
      futureXp = 0;
      ci.cancel();
    }
  }

  @Inject(method = "getItem", at = @At("HEAD"), cancellable = true)
  private void economy$hideStaleOutput(int index, CallbackInfoReturnable<ItemStack> cir) {
    if (index == 2 && TradeHooks.owned(merchant)) cir.setReturnValue(ItemStack.EMPTY);
  }

  @Inject(method = "removeItem", at = @At("HEAD"), cancellable = true)
  private void economy$noDirectOutput(int index, int count, CallbackInfoReturnable<ItemStack> cir) {
    if (index == 2 && TradeHooks.owned(merchant)) cir.setReturnValue(ItemStack.EMPTY);
  }

  @Inject(method = "removeItemNoUpdate", at = @At("HEAD"), cancellable = true)
  private void economy$noUntrackedOutput(int index, CallbackInfoReturnable<ItemStack> cir) {
    if (index == 2 && TradeHooks.owned(merchant)) cir.setReturnValue(ItemStack.EMPTY);
  }

  @Inject(method = "setItem", at = @At("HEAD"), cancellable = true)
  private void economy$noInjectedOutput(int index, ItemStack stack, CallbackInfo ci) {
    if (index == 2 && TradeHooks.owned(merchant)) {
      itemStacks.set(2, ItemStack.EMPTY);
      ci.cancel();
    }
  }
}
