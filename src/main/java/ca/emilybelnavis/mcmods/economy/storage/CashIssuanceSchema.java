package ca.emilybelnavis.mcmods.economy.storage;

import java.util.List;

/** World-local quantity authority for marked cash; physical copies never enlarge a lot. */
public final class CashIssuanceSchema {
  private CashIssuanceSchema() {}

  public static List<String> statements() {
    return List.of(
        """
                CREATE TABLE cash_issuance(
                    lot_id TEXT PRIMARY KEY,world_id TEXT NOT NULL,item_id TEXT NOT NULL,
                    issued INTEGER NOT NULL CHECK(issued BETWEEN 1 AND 4096),
                    remaining INTEGER NOT NULL CHECK(remaining BETWEEN 0 AND issued),
                    state TEXT NOT NULL CHECK(state IN ('ACTIVE','QUARANTINED')),
                    revision INTEGER NOT NULL CHECK(revision>0),evidence TEXT NOT NULL)
                """,
        "CREATE INDEX cash_issuance_reconcile ON cash_issuance(state,remaining,lot_id)");
  }
}
