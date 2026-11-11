package ca.emilybelnavis.mcmods.economy.card;

import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import java.util.*;

/** Approved network appearances; these lookup references carry no borrowing authority. */
public final class CreditCardDesigns {
  public record Design(String id, InstitutionCatalog.InstitutionId issuer, String network) {}

  private CreditCardDesigns() {}

  public static String id(InstitutionCatalog.InstitutionId issuer, String network, int tier) {
    var bank = InstitutionCatalog.require(issuer);
    var selected =
        bank.networks().stream()
            .filter(n -> n.key().equals(network))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("CREDIT_NETWORK"));
    if (tier == 1 ? !bank.tier1() : tier != 2 || !bank.tier2())
      throw new IllegalArgumentException("CREDIT_TIER");
    return "economy:issued_"
        + issuer.key().substring("economy:bank/".length())
        + "_credit_"
        + selected.name().toLowerCase(Locale.ROOT);
  }

  public static List<Design> all() {
    var result = new ArrayList<Design>();
    for (var bank : InstitutionCatalog.banks())
      for (var network : bank.networks().stream().sorted().toList())
        result.add(
            new Design(
                id(bank.id(), network.key(), bank.tier1() ? 1 : 2), bank.id(), network.key()));
    return List.copyOf(result);
  }

  /** Save compatibility only: both old tiers resolve to the current network appearance. */
  public static Map<String, String> legacyAliases() {
    var aliases = new LinkedHashMap<String, String>();
    for (var bank : InstitutionCatalog.banks())
      for (var network : bank.networks())
        for (int tier = 1; tier <= 2; tier++) {
          if (tier == 1 ? bank.tier1() : bank.tier2()) {
            String current = id(bank.id(), network.key(), tier);
            aliases.put(current + "_t" + tier, current);
          }
        }
    return Map.copyOf(aliases);
  }

  public static Optional<Design> byId(String id) {
    return all().stream().filter(d -> d.id().equals(id)).findFirst();
  }
}
