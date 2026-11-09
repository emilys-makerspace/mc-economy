package ca.emilybelnavis.mcmods.economy.storage;

import java.util.List;

/** World-local terminal policy with permanent placement-generation history. */
public final class TerminalPolicySchema {
  private TerminalPolicySchema() {}

  public static List<String> statements() {
    return List.of(
        """
                CREATE TABLE terminal_settings(
                    world_id TEXT NOT NULL,dimension TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,
                    generation TEXT NOT NULL,owner TEXT NOT NULL,terminal_kind TEXT NOT NULL,
                    revision INTEGER NOT NULL CHECK(revision>0),active INTEGER NOT NULL CHECK(active IN (0,1)),
                    mode TEXT NOT NULL,currencies TEXT NOT NULL,denominations TEXT NOT NULL,
                    PRIMARY KEY(world_id,dimension,x,y,z))
                """,
        """
                CREATE TABLE terminal_generations(
                    world_id TEXT NOT NULL,generation TEXT NOT NULL,dimension TEXT NOT NULL,
                    x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,
                    PRIMARY KEY(world_id,generation))
                """);
  }
}
