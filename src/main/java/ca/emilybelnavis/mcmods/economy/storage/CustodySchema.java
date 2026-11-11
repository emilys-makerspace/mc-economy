package ca.emilybelnavis.mcmods.economy.storage;

import java.util.List;

/** World-local durable custody state. Historical events remain in the journal. */
public final class CustodySchema {
  private CustodySchema() {}

  public static List<String> statements() {
    return List.of(
        """
                CREATE TABLE custody_cases(
                    id TEXT PRIMARY KEY,owner TEXT NOT NULL,items TEXT NOT NULL,source TEXT NOT NULL,
                    destination TEXT NOT NULL,financial_intent TEXT NOT NULL,state TEXT NOT NULL,
                    reservation_operation TEXT NOT NULL,financial_operation TEXT,delivery_attempt TEXT,
                    reservation_witness TEXT,delivery_witness TEXT,acknowledgment TEXT,
                    evidence TEXT NOT NULL,reason TEXT NOT NULL,revision INTEGER NOT NULL,compensation_operation TEXT)
                """,
        "CREATE INDEX custody_owner_claims ON custody_cases(owner,state,id)",
        "CREATE INDEX custody_reconcile ON custody_cases(state,id)");
  }
}
