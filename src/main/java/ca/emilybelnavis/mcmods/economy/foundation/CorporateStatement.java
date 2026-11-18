package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import java.time.Instant;
import java.util.*;

/** Latest eight original postings for one already-authorized corporate bank account. */
final class CorporateStatement {
  private CorporateStatement() {}

  static List<String> recent(
      JournalEngine.ReadView reads, WorldId world, UUID company, AccountId account) {
    if (reads
        .query("SELECT 1 FROM owner_history_state WHERE singleton=1 AND ready=1", List.of(), 1)
        .isEmpty()) throw new IllegalStateException("HISTORY_UNAVAILABLE");
    var rows =
        reads.query(
            """
        SELECT recorded_ms,currency,minor FROM owner_history
        WHERE world_id=? AND owner_id=? AND account_id=?
        ORDER BY sequence DESC,position DESC LIMIT 8
        """,
            List.of(
                world.value().toString(), "organization:" + company, account.value().toString()),
            8);
    if (rows.isEmpty()) return List.of(UiText.t("ui.bank.corporate.no_postings"));
    return rows.stream()
        .map(
            row ->
                UiText.t(
                    "ui.bank.corporate.posting",
                    Instant.ofEpochMilli(Long.parseLong(row.get("recorded_ms"))).toString(),
                    new Money(
                        EconomyCurrency.valueOf(row.get("currency")),
                        Long.parseLong(row.get("minor")))))
        .toList();
  }
}
