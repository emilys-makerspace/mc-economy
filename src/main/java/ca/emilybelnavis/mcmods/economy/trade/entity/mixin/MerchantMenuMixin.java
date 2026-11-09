package ca.emilybelnavis.mcmods.economy.trade.entity.mixin;

import ca.emilybelnavis.mcmods.economy.trade.entity.TradeHooks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(MerchantMenu.class)
public abstract class MerchantMenuMixin {
  @Shadow @Final private Merchant trader;

  @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
  private void economy$beforeGoodsMove(
      Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
    if (TradeHooks.owned(trader)) cir.setReturnValue(ItemStack.EMPTY);
  }

  @Inject(
      method = {"setSelectionHint", "tryMoveItems"},
      at = @At("HEAD"),
      cancellable = true)
  private void economy$ignoreNativeSelection(int index, CallbackInfo ci) {
    if (TradeHooks.owned(trader)) ci.cancel();
  }

  @Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
  private void economy$staleNativeMenu(Player player, CallbackInfoReturnable<Boolean> cir) {
    if (TradeHooks.owned(trader)) cir.setReturnValue(false);
  }
}
