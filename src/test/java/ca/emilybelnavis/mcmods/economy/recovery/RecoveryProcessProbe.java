package ca.emilybelnavis.mcmods.economy.recovery;

import static ca.emilybelnavis.mcmods.economy.recovery.RecoveryFixtures.*;

import ca.emilybelnavis.mcmods.economy.clock.*;
import java.nio.file.Path;
import java.util.*;

/** Test-only process checkpoints. No runtime/game processes are targeted. */
public final class RecoveryProcessProbe {
  public static EconomicClock.SystemDefinition system(boolean pauseBeforeCommit) {
    var version =
        new EconomicClock.Version(
            1,
            "fixture-process-v1",
            update ->
                new EconomicClock.Output(
                    Long.toString(new SplittableRandom(update.randomSeed()).nextLong()),
                    tx -> {
                      if (pauseBeforeCommit) {
                        System.out.println("BOUNDARY|" + update.id() + "|" + update.randomSeed());
                        System.out.flush();
                        System.in.read();
                      }
                    }));
    return new EconomicClock.SystemDefinition(
        "fixture:process", 1000, 0, 1, Map.of(1, version), Set.of());
  }

  public static void main(String[] args) throws Exception {
    var store = open(Path.of(args[0]));
    if (args[1].equals("projection")) {
      var definition =
          projection(
              1,
              (envelope, cells) -> {
                String old = cells.get("count");
                cells.put("count", Long.toString((old == null ? 0 : Long.parseLong(old)) + 1));
                System.out.println("BOUNDARY");
                System.out.flush();
                try {
                  System.in.read();
                } catch (java.io.IOException e) {
                  throw new RuntimeException(e);
                }
              });
      new ProjectionManager(store, List.of(definition)).step(1);
    } else {
      var clock =
          new EconomicClock(
              store,
              new EconomicClockTest.MutableClock(1000),
              List.of(system(args[1].equals("clock-before"))));
      clock.prepare();
      clock.runBatch(1);
      System.out.println("BOUNDARY");
      System.out.flush();
      System.in.read();
    }
    store.close();
  }
}
