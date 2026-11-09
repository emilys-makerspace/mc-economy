package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.JournalFixtures.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Disposable process-boundary fixture. Never included in the mod jar. */
public final class JournalProcessProbe {
  public static void main(String[] args) throws Exception {
    var store = new SqliteStorage(ca.emilybelnavis.mcmods.economy.storage.FixtureRecords.catalog());
    store.initialize(Path.of(args[0]));
    var request = intent(new OperationId(UUID.fromString(args[2])));
    if (args[1].equals("before")) {
      var engine =
          new JournalEngine(
              store,
              (actor, kind) -> true,
              Map.of(
                  KIND,
                  new JournalEngine.Registration(
                      1,
                      (intent, reads) ->
                          commit(
                              tx -> {
                                tx.execute(
                                    "INSERT INTO fixture_projection VALUES('counter',1)",
                                    List.of());
                                System.out.println("BOUNDARY");
                                System.out.flush();
                                System.in.read();
                              }))));
      engine.submit(request);
    } else {
      engine(store, new AtomicInteger()).submit(request);
      System.out.println("BOUNDARY");
      System.out.flush();
      System.in.read();
    }
    store.close();
  }
}
