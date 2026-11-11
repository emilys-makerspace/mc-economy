package ca.emilybelnavis.mcmods.economy.card;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.credit.CreditFacilities;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import java.util.*;

/** Public presentation identity only, resolved after authoritative owner/token validation. */
public record CardAccess(UUID id, String design) {
  public static Optional<CardAccess> usable(
      JournalEngine.ReadView reads, WorldId world, PlayerId actor, UUID token, long now) {
    var debit = IssuedCards.usable(reads, world, actor, token, now);
    if (debit.isPresent()) return debit.map(t -> new CardAccess(t.id(), IssuedCards.itemId(t)));
    return CreditFacilities.usableToken(reads, world, actor, token, now)
        .map(t -> new CardAccess(t.id(), CreditFacilities.itemId(t)));
  }
}
