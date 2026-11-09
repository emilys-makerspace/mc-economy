package ca.emilybelnavis.mcmods.economy.storage;

import java.util.List;

/** Durable pre-debit delivery intent. Issued cash and custody use their existing world tables. */
public final class WithdrawalSchema {
  private WithdrawalSchema() {}

  public static List<String> statements() {
    return List.of(
        """
                CREATE TABLE withdrawals(
                    id TEXT PRIMARY KEY,owner TEXT NOT NULL,state TEXT NOT NULL,preview TEXT NOT NULL,
                    evidence TEXT NOT NULL,reason TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0))
                """,
        "CREATE INDEX withdrawal_pending ON withdrawals(state,id)");
  }
}
