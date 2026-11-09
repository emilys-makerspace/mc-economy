package ca.emilybelnavis.mcmods.economy.recovery;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;

public final class RecoveryFixtures {
  private RecoveryFixtures() {}

  public static final Journal.Kind KIND = new Journal.Kind("fixture:service", "fixture:increment");
  public static final PlayerId ACTOR =
      new PlayerId(UUID.fromString("b97f5d5a-6939-4b8b-b5d0-e040e4cbb808"));

  public static SqliteStorage open(Path world) {
    var store = new SqliteStorage(FixtureRecords.catalog());
    store.initialize(world);
    return store;
  }

  public static void create(Path world, int transactions) throws Exception {
    var store = open(world);
    store.close();
    try (var c =
            DriverManager.getConnection("jdbc:sqlite:" + world.resolve("economy/economy.sqlite"));
        var s = c.createStatement()) {
      s.execute("CREATE TABLE fixture_authority(key TEXT PRIMARY KEY,value INTEGER NOT NULL)");
    }
    store = open(world);
    var journal = journal(store);
    for (int i = 0; i < transactions; i++) journal.submit(intent());
    store.close();
  }

  public static Journal.Intent intent() {
    return new Journal.Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        ACTOR,
        KIND,
        new Journal.CanonicalRequest(1, Map.of("delta", "1")),
        null,
        null);
  }

  public static JournalEngine journal(FoundationStore store) {
    return new JournalEngine(
        store,
        (actor, kind) -> true,
        Map.of(
            KIND,
            new JournalEngine.Registration(
                1,
                (intent, reads) ->
                    new JournalEngine.Commit(
                        List.of(new Journal.Record("fixture:first", 1, "committed decision")),
                        new Journal.Result(1, "OK", ""),
                        tx ->
                            tx.execute(
                                "INSERT INTO fixture_authority VALUES('count',1) ON CONFLICT(key) DO UPDATE SET value=value+1",
                                List.of())))));
  }

  public static ProjectionManager.Definition projection(int version) {
    return projection(
        version,
        (envelope, cells) -> {
          String previous = cells.get("count");
          cells.put(
              "count",
              Long.toString(Math.addExact(previous == null ? 0 : Long.parseLong(previous), 1)));
        });
  }

  public static ProjectionManager.Definition projection(
      int version, ProjectionManager.Reducer reducer) {
    return new ProjectionManager.Definition(
        "fixture:count",
        version,
        Set.of(new RecordCatalog.Key("fixture:first", 1)),
        reducer,
        snapshot -> Long.parseLong(snapshot.values().getOrDefault("count", "0")) >= 0);
  }

  public static void finish(ProjectionManager manager, int batch) {
    for (int i = 0; i < 1000; i++) if (manager.step(batch).ready()) return;
    throw new AssertionError("Projection did not finish");
  }

  public static long authority(FoundationStore store) {
    return store.transaction(
        tx ->
            Long.parseLong(
                tx.query(
                        "SELECT COALESCE(sum(value),0) AS value FROM fixture_authority",
                        List.of(),
                        1)
                    .getFirst()
                    .get("value")));
  }
}
