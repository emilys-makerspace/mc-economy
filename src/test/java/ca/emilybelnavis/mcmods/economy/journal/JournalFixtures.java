package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

final class JournalFixtures {
  static final Kind KIND = new Kind("fixture:service", "fixture:increment");
  static final PlayerId ACTOR =
      new PlayerId(UUID.fromString("b97f5d5a-6939-4b8b-b5d0-e040e4cbb808"));

  static void createWorld(Path world) throws Exception {
    var store = new SqliteStorage(ca.emilybelnavis.mcmods.economy.storage.FixtureRecords.catalog());
    store.initialize(world);
    store.close();
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + world.resolve("economy/economy.sqlite"));
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE fixture_projection(key TEXT PRIMARY KEY,value INTEGER NOT NULL)");
    }
  }

  static Intent intent(OperationId id) {
    return new Intent(
        id,
        new RequestId(UUID.randomUUID()),
        ACTOR,
        KIND,
        new CanonicalRequest(1, Map.of("delta", "1")),
        null,
        null);
  }

  static Intent intent() {
    return intent(new OperationId(UUID.randomUUID()));
  }

  static JournalEngine engine(SqliteStorage store, AtomicInteger prepares) {
    return new JournalEngine(
        store,
        (actor, kind) -> true,
        Map.of(
            KIND,
            new JournalEngine.Registration(
                1,
                (intent, reads) -> {
                  prepares.incrementAndGet();
                  return commit(
                      tx ->
                          tx.execute(
                              "INSERT INTO fixture_projection(key,value) VALUES('counter',1) ON CONFLICT(key) DO UPDATE SET value=value+1",
                              List.of()));
                })));
  }

  static JournalEngine.Commit commit(JournalEngine.Projection projection) {
    return new JournalEngine.Commit(
        List.of(
            new Journal.Record("fixture:first", 1, "first"),
            new Journal.Record("fixture:second", 2, "second")),
        new Result(1, "OK", "fixture-result"),
        projection);
  }

  static long counter(SqliteStorage store) {
    return store.transaction(
        tx ->
            Long.parseLong(
                tx.query(
                        "SELECT COALESCE(sum(value),0) AS value FROM fixture_projection",
                        List.of(),
                        1)
                    .getFirst()
                    .get("value")));
  }

  static long count(SqliteStorage store, String table) {
    return store.transaction(
        tx ->
            Long.parseLong(
                tx.query("SELECT count(*) AS value FROM " + table, List.of(), 1)
                    .getFirst()
                    .get("value")));
  }
}
