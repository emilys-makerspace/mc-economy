package ca.emilybelnavis.mcmods.economy.trade.entity.mixin;

import ca.emilybelnavis.mcmods.economy.trade.entity.TradeHooks;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.trading.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractVillager.class)
public abstract class AbstractVillagerMixin implements Merchant {
  /** Override the inherited default to guard direct opens as well as interactions. */
  @Override
  public void openTradingScreen(Player player, Component title, int level) {
    if (!TradeHooks.owned(this)) Merchant.super.openTradingScreen(player, title, level);
  }

  @Inject(method = "notifyTrade", at = @At("HEAD"), cancellable = true)
  private void economy$receiptOnly(MerchantOffer offer, CallbackInfo ci) {
    if (TradeHooks.blocksProgression(this)) ci.cancel();
  }
}
