package ca.emilybelnavis.mcmods.economy.credit;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreditEventsTest {
  @TempDir Path path;
  private static final PlayerId OWNER = new PlayerId(UUID.randomUUID());
  private static final Journal.Kind KIND =
      new Journal.Kind("fixture:credit-events", "fixture:append");

  private static Journal.Intent intent() {
    return new Journal.Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        OWNER,
        KIND,
        new Journal.CanonicalRequest(1, Map.of()),
        null,
        null);
  }

  private static String pack(List<String> fields) {
    return String.join(
        "|",
        fields.stream()
            .map(
                v ->
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(v.getBytes(StandardCharsets.UTF_8)))
            .toList());
  }

  private static Journal.Record state(WorldId world, UUID facility) {
    return new Journal.Record(
        CreditFacilities.FACT,
        1,
        pack(
            List.of(
                world.value().toString(),
                facility.toString(),
                OWNER.value().toString(),
                "economy:bank/crownstone",
                "ORBIT",
                "CAD",
                "250000",
                "1",
                "1000",
                "1000",
                "1000",
                "1",
                "ACTIVE",
                "ENABLED",
                "PURCHASE,0,0,0.000000000000000000,0.000000000000000000,0.1999",
                "ADVANCE,0,0,0.000000000000000000,0.000000000000000000,0.2199",
                "",
                "")));
  }

  private static JournalEngine engine(SqliteStorage store, List<Journal.Record> records) {
    return new JournalEngine(
        store,
        (a, k) -> true,
        Map.of(
            KIND,
            new JournalEngine.Registration(
                1,
                (i, r) ->
                    new JournalEngine.Commit(
                        records, new Journal.Result(1, "FIXTURE", ""), tx -> {}))));
  }

  @Test
  void fixedFrontierAndExclusiveRecordCursorPublishExistingSourcesWithoutTokenFacts()
      throws Exception {
    var store = new SqliteStorage();
    try {
      store.initialize(path.toRealPath());
      var world = store.snapshot().worldId();
      var facility = UUID.randomUUID();
      var initial = state(world, facility);
      var token = UUID.randomUUID();
      var tokenFact =
          new Journal.Record(
              CreditFacilities.TOKEN_FACT,
              1,
              pack(
                  List.of(
                      world.value().toString(),
                      token.toString(),
                      facility.toString(),
                      OWNER.value().toString(),
                      "economy:bank/crownstone",
                      "ORBIT",
                      "1",
                      "ACTIVE",
                      "1000",
                      Long.toString(
                          1000 + ca.emilybelnavis.mcmods.economy.card.IssuedCards.LIFETIME),
                      "1",
                      token.toString())));
      assertEquals(
          Journal.Status.COMMITTED,
          engine(store, List.of(initial, tokenFact, initial)).submit(intent()).status());
      var cursor = store.transaction(tx -> CreditEvents.open(tx::query));
      var page = store.transaction(tx -> CreditEvents.read(tx::query, cursor, 1));
      assertEquals(1, page.events().size());
      assertNotNull(page.nextCursor());
      var envelope = page.events().getFirst().envelope();
      assertEquals(0, envelope.position());
      assertNotNull(envelope.operation());
      assertNotNull(envelope.transaction());
      assertInstanceOf(CreditEvents.FacilityChanged.class, page.events().getFirst().payload());
      assertFalse(page.toString().contains(token.toString()));
      engine(store, List.of(initial))
          .submit(intent()); // Appended later; the open frontier must not move.
      var second = store.transaction(tx -> CreditEvents.read(tx::query, page.nextCursor(), 1));
      assertEquals(2, second.events().getFirst().envelope().position());
      assertNull(second.nextCursor());
      assertEquals(cursor.frontier(), second.checkpoint().frontier());
      var renewed = store.transaction(tx -> CreditEvents.open(tx::query, second.checkpoint()));
      var third = store.transaction(tx -> CreditEvents.read(tx::query, renewed, 16));
      assertEquals(1, third.events().size());
      assertTrue(third.events().getFirst().envelope().sequence() > envelope.sequence());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              store.transaction(
                  tx ->
                      CreditEvents.read(
                          tx::query,
                          new CreditEvents.Cursor(
                              new WorldId(UUID.randomUUID()), 0, -1, cursor.frontier()),
                          1)));
      assertThrows(
          IllegalArgumentException.class,
          () -> store.transaction(tx -> CreditEvents.read(tx::query, cursor, 17)));
      try (var c =
              DriverManager.getConnection(
                  "jdbc:sqlite:file:" + path.resolve("economy/economy.sqlite") + "?mode=ro");
          var q = c.prepareStatement("EXPLAIN QUERY PLAN " + CreditEvents.QUERY)) {
        q.setLong(1, cursor.frontier());
        q.setLong(2, 0);
        q.setInt(3, -1);
        q.setInt(4, 17);
        var plan = new StringBuilder();
        try (var r = q.executeQuery()) {
          while (r.next()) plan.append(r.getString("detail")).append('\n');
        }
        assertTrue(plan.toString().contains("credit_event_feed"), plan.toString());
        assertFalse(plan.toString().contains("TEMP B-TREE"), plan.toString());
      }
    } finally {
      store.close();
    }
  }

  @Test
  void productionGateHasNoBypassAndOriginalCommittedOutcomesReplayBeforeIt() throws Exception {
    var store = new SqliteStorage();
    try {
      store.initialize(path.toRealPath());
      var calls = new AtomicInteger();
      var registration =
          new JournalEngine.Registration(
              1,
              (i, r) -> {
                calls.incrementAndGet();
                return new JournalEngine.Commit(
                    List.of(state(store.snapshot().worldId(), UUID.randomUUID())),
                    new Journal.Result(1, "ORIGINAL", ""),
                    tx -> {});
              });
      var original = intent();
      var ungated = new JournalEngine(store, (a, k) -> true, Map.of(KIND, registration));
      var committed = ungated.submit(original);
      assertEquals(Journal.Status.COMMITTED, committed.status());
      var gated =
          new JournalEngine(
              store, (a, k) -> true, Map.of(KIND, CreditSafety.production(registration)));
      assertEquals(committed, gated.submit(original));
      assertEquals(1, calls.get());
      long frontier = store.lastCommittedSequence();
      var rejected = gated.submit(intent());
      assertEquals(CreditSafety.STATUS, rejected.result().code());
      assertEquals(1, calls.get());
      assertEquals(frontier, store.lastCommittedSequence());
    } finally {
      store.close();
    }
  }
}
