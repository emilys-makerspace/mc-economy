package ca.emilybelnavis.mcmods.economy.client;

import ca.emilybelnavis.mcmods.economy.Economy;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/** Distribution boundary for banking screens and original visual resources. */
@Mod(value = Economy.MOD_ID, dist = Dist.CLIENT)
public final class EconomyClient {
  public EconomyClient(net.neoforged.bus.api.IEventBus modBus) {
    BankingScreen.initialize(modBus);
    BankingArtModels.initialize(modBus);
    CollectionAgentRenderer.initialize(modBus);
    CollectionScreen.initialize(modBus);
    BankerRenderer.initialize(modBus);
    BankerScreen.initialize(modBus);
    TraderScreen.initialize(modBus);
    LogUtils.getLogger().info("Economy client entry point loaded");
  }
}
