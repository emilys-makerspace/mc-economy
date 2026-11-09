package ca.emilybelnavis.mcmods.economy.storage;

import java.util.List;

/** Owner pricing, frozen register sales and stock-reservation high-water marks. */
public final class RegisterSalesSchema {
  private RegisterSalesSchema() {}

  public static List<String> statements() {
    return List.of(
        "CREATE TABLE register_merchants(owner TEXT PRIMARY KEY)",
        """
                CREATE TABLE register_prices(
                    world_id TEXT NOT NULL,dimension TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,
                    generation TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),price TEXT NOT NULL,
                    PRIMARY KEY(world_id,dimension,x,y,z))
                """,
        """
                CREATE TABLE register_sales(
                    id TEXT PRIMARY KEY,owner TEXT NOT NULL,state TEXT NOT NULL,quote TEXT NOT NULL,
                    saved_source TEXT,evidence TEXT NOT NULL,reason TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0))
                """,
        """
                CREATE TABLE register_stock_locks(
                    sale_id TEXT PRIMARY KEY REFERENCES register_sales(id),world_id TEXT NOT NULL,
                    source_generation TEXT NOT NULL,count INTEGER NOT NULL CHECK(count BETWEEN 1 AND 64),
                    state TEXT NOT NULL,return_custody_id TEXT)
                """,
        """
                CREATE TABLE register_stock_sources(
                    world_id TEXT NOT NULL,generation TEXT NOT NULL,dimension TEXT NOT NULL,
                    x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,
                    revision INTEGER NOT NULL CHECK(revision>0),quantity INTEGER NOT NULL CHECK(quantity BETWEEN 0 AND 4096),
                    template TEXT NOT NULL,fingerprint TEXT NOT NULL,PRIMARY KEY(world_id,generation))
                """,
        "CREATE INDEX register_return_case ON register_stock_locks(return_custody_id)",
        "CREATE INDEX register_pending ON register_sales(state,id)",
        "CREATE INDEX register_stock_active ON register_stock_locks(world_id,source_generation,state)");
  }
}
