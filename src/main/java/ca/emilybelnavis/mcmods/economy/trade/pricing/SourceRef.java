package ca.emilybelnavis.mcmods.economy.trade.pricing;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.trade.market.MarketFacts;
import java.util.*;

/** Immutable prospective source identity for one world's exact market/item scope. */
public record SourceRef(
    WorldId world,
    UUID market,
    String item,
    UUID source,
    int revision,
    UUID epoch,
    UUID valuationManifest,
    String configHash,
    String recipeHash,
    String providerHash,
    String implementation,
    String rounding,
    UUID activation) {
  public SourceRef {
    Objects.requireNonNull(world);
    Objects.requireNonNull(market);
    MarketFacts.key(item);
    Objects.requireNonNull(source);
    Objects.requireNonNull(epoch);
    Objects.requireNonNull(valuationManifest);
    Objects.requireNonNull(activation);
    if (revision < 1
        || !Set.of(ReferencePricing.VERSION, ReferencePricing.V2).contains(implementation)
        || !ReferencePricing.ROUNDING.equals(rounding))
      throw new IllegalArgumentException("Reference source version");
    for (String hash : List.of(configHash, recipeHash, providerHash))
      if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Source input hash");
  }
}
