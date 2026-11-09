package ca.emilybelnavis.mcmods.economy.card;

import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import java.util.*;

/**
 * Pure approved resource identities, usable by worker validation without bootstrapping Minecraft
 * registries.
 */
public final class CardDesigns {
  public record Design(String id, InstitutionCatalog.InstitutionId issuer) {}

  private CardDesigns() {}

  public static String id(InstitutionCatalog.InstitutionId issuer, int tier) {
    var bank = InstitutionCatalog.require(issuer);
    if (tier == 1 ? !bank.tier1() : tier != 2 || !bank.tier2())
      throw new IllegalArgumentException("CARD_PRODUCT");
    return "economy:issued_" + issuer.key().substring("economy:bank/".length()) + "_debit";
  }

  public static List<Design> all() {
    var result = new ArrayList<Design>();
    for (var bank : InstitutionCatalog.banks()) {
      result.add(new Design(id(bank.id(), bank.tier1() ? 1 : 2), bank.id()));
    }
    return List.copyOf(result);
  }

  /** One appearance per issuer, independent of the account's eligibility tier. */
  public static List<Design> collectibles() {
    return all();
  }

  /** Save compatibility only: these names no longer register separate items. */
  public static Map<String, String> legacyAliases() {
    var aliases = new LinkedHashMap<String, String>();
    for (var bank : InstitutionCatalog.banks())
      for (int tier = 1; tier <= 2; tier++) {
        if (tier == 1 ? bank.tier1() : bank.tier2()) {
          String current = id(bank.id(), tier);
          aliases.put(current + "_t" + tier, current);
        }
      }
    return Map.copyOf(aliases);
  }

  public static Optional<Design> byId(String id) {
    return all().stream().filter(design -> design.id().equals(id)).findFirst();
  }
}
