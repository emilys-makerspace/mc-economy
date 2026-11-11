package ca.emilybelnavis.mcmods.economy.storage;

import java.util.List;

/** Durable return attempts and protection transferred from a deposit to an owner claim. */
public final class DepositReturnSchema {
  private DepositReturnSchema() {}

  public static List<String> statements() {
    return List.of(
        "ALTER TABLE deposit_returns ADD COLUMN admitted INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE deposit_returns ADD COLUMN delivered INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE deposit_returns ADD COLUMN evidence TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE deposit_returns ADD COLUMN reason TEXT NOT NULL DEFAULT ''",
        "CREATE INDEX deposit_return_pending ON deposit_returns(state,deposit_id,slot)",
        """
                CREATE TABLE custody_cash_reservations(
                    custody_id TEXT NOT NULL,lot_id TEXT NOT NULL,count INTEGER NOT NULL CHECK(count>0),
                    state TEXT NOT NULL CHECK(state IN ('ACTIVE','RELEASED')),
                    PRIMARY KEY(custody_id,lot_id))
                """,
        "CREATE INDEX custody_cash_reserved ON custody_cash_reservations(lot_id,state)");
  }
}
