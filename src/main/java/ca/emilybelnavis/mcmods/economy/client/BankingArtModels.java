package ca.emilybelnavis.mcmods.economy.client;

import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ModelEvent;

/** Resource-only models: registration here creates no item, terminal, credential or facility. */
public final class BankingArtModels {
  private BankingArtModels() {}

  public static void initialize(IEventBus bus) {
    bus.addListener(BankingArtModels::register);
  }

  private static void register(ModelEvent.RegisterAdditional event) {
    forEachModel(
        path ->
            event.register(
                ModelResourceLocation.standalone(
                    ResourceLocation.fromNamespaceAndPath("economy", path))));
  }

  /** Derive visual variants from the authoritative catalog, never a second product roster. */
  public static void forEachModel(Consumer<String> consumer) {
    for (var bank : InstitutionCatalog.banks()) {
      String key = bank.id().key().substring("economy:bank/".length());
      consumer.accept("item/banking/" + key + "_debit");
      for (var network : bank.networks()) {
        consumer.accept(
            "item/banking/" + key + "_credit_" + network.name().toLowerCase(Locale.ROOT));
      }
      for (String kind : new String[] {"atm", "teller", "kiosk"}) {
        consumer.accept("item/banking/" + key + "_" + kind);
        int cells = kind.equals("kiosk") ? 2 : 4;
        for (int cell = 0; cell < cells; cell++) {
          consumer.accept("block/banking/" + key + "_" + kind + "_" + cell);
        }
      }
    }
  }
}
