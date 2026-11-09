package ca.emilybelnavis.mcmods.economy.storage;

import java.util.List;

/** Frozen deposit reservations and exact original return intents share the world journal. */
public final class DepositSchema {
  private DepositSchema() {}

  public static List<String> statements() {
    return List.of(
        """
                CREATE TABLE deposits(
                    id TEXT PRIMARY KEY,owner TEXT NOT NULL,state TEXT NOT NULL,preview TEXT NOT NULL,
                    evidence TEXT NOT NULL,reason TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0))
                """,
        """
                CREATE TABLE deposit_cash_reservations(
                    deposit_id TEXT NOT NULL REFERENCES deposits(id),lot_id TEXT NOT NULL,
                    count INTEGER NOT NULL CHECK(count>=0),state TEXT NOT NULL,
                    PRIMARY KEY(deposit_id,lot_id))
                """,
        "CREATE INDEX deposit_cash_locks ON deposit_cash_reservations(lot_id,state)",
        """
                CREATE TABLE deposit_returns(
                    deposit_id TEXT NOT NULL REFERENCES deposits(id),slot INTEGER NOT NULL,
                    world_id TEXT NOT NULL,lot_id TEXT NOT NULL,item_id TEXT NOT NULL,
                    count INTEGER NOT NULL CHECK(count BETWEEN 1 AND 64),components TEXT NOT NULL,
                    state TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),attempt TEXT,
                    PRIMARY KEY(deposit_id,slot))
                """,
        "CREATE INDEX deposit_pending ON deposits(state,id)");
  }
}
