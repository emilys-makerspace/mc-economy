package ca.emilybelnavis.mcmods.economy.example;

import ca.emilybelnavis.mcmods.economy.api.CorporationIntegration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModContainer;

/** Minimal consumer. Construct and call it on the owning server thread. */
final class CorporationIntegrationConsumer {
  private final CorporationIntegration integration;

  CorporationIntegrationConsumer(MinecraftServer server, ModContainer mod) {
    integration =
        CorporationIntegration.register(
            server,
            mod,
            mod.getModInfo().getModId() + ":company_directory",
            Set.of(
                CorporationIntegration.Capability.FIND,
                CorporationIntegration.Capability.ACCOUNTS));
  }

  CompletionStage<CorporationIntegration.Result<CorporationIntegration.Company>> find(String key) {
    return integration.find(key);
  }

  CompletionStage<CorporationIntegration.Result<List<CorporationIntegration.Account>>> accounts(
      String key, long companyRevision, long mandateRevision) {
    return integration.accounts(key, companyRevision, mandateRevision);
  }

  CompletionStage<Void> disable() {
    return integration.disable();
  }
}
