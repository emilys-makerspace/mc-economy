package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.JournalFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class JournalEngineTest {
  @TempDir Path temporary;
  Path world;

  @BeforeEach
  void setup() throws Exception {
    world = temporary.toRealPath();
    createWorld(world);
  }

  private SqliteStorage open() {
    var store = new SqliteStorage(ca.emilybelnavis.mcmods.economy.storage.FixtureRecords.catalog());
    store.initialize(world);
    return store;
  }

  @Test
  void canonicalRetryEnvelopeAndRestart() throws Exception {
    var store = open();
    var prepares = new AtomicInteger();
    var engine = engine(store, prepares);
    Intent first =
        new Intent(
            new OperationId(UUID.randomUUID()),
            new RequestId(UUID.randomUUID()),
            ACTOR,
            KIND,
            new CanonicalRequest(1, Map.of("z", "last", "a", "first")),
            UUID.randomUUID(),
            null);
    Outcome original = engine.submit(first);
    assertEquals(Status.COMMITTED, original.status());
    assertEquals(1, counter(store));
    var retry =
        new Intent(
            first.operationId(),
            new RequestId(UUID.randomUUID()),
            ACTOR,
            KIND,
            new CanonicalRequest(1, new LinkedHashMap<>(Map.of("a", "first", "z", "last"))),
            first.economicUpdateId(),
            null);
    assertEquals(first.canonical(), retry.canonical());
    assertEquals(original, engine.submit(retry));
    assertEquals(1, prepares.get());
    assertEquals(1, count(store, "operation_outcomes"));
    var envelope = engine.readAfter(0, 10).getFirst();
    assertEquals(original.transactionId(), envelope.transactionId());
    assertEquals(first.requestId(), envelope.originalRequestId());
    assertEquals(first.actor(), envelope.actor());
    assertEquals(first.kind(), envelope.kind());
    assertEquals(
        List.of("first", "second"),
        envelope.records().stream().map(Journal.Entry::payload).toList());
    assertEquals(List.of(0, 1), envelope.records().stream().map(Journal.Entry::position).toList());
    assertEquals(first.economicUpdateId(), envelope.economicUpdateId());
    assertNotNull(envelope.auditInstant());
    assertThrows(UnsupportedOperationException.class, () -> envelope.records().clear());
    store.close();
    var reopened = open();
    var afterRestart = engine(reopened, prepares);
    assertEquals(original, afterRestart.submit(retry));
    assertEquals(original, afterRestart.lookup(retry).orElseThrow());
    assertEquals(1, counter(reopened));
    reopened.close();
  }

  @Test
  void sequentialWorldsKeepSameOperationIdAndEffectsIsolated() throws Exception {
    Path secondWorld = Files.createDirectory(temporary.resolve("other-world")).toRealPath();
    createWorld(secondWorld);
    var request = intent();
    var first = open();
    var firstWorldId = first.snapshot().worldId();
    Outcome original;
    try {
      original = engine(first, new AtomicInteger()).submit(request);
      assertEquals(Status.COMMITTED, original.status());
      assertEquals(1, counter(first));
    } finally {
      first.close();
    }

    var second = new SqliteStorage(FixtureRecords.catalog());
    second.initialize(secondWorld);
    try {
      var journal = engine(second, new AtomicInteger());
      assertNotEquals(firstWorldId, second.snapshot().worldId());
      assertTrue(journal.lookup(request).isEmpty());
      assertEquals(0, counter(second));
      var independent = journal.submit(request);
      assertEquals(Status.COMMITTED, independent.status());
      assertNotEquals(original.transactionId(), independent.transactionId());
      assertEquals(1, counter(second));
    } finally {
      second.close();
    }

    var reopened = open();
    try {
      assertEquals(firstWorldId, reopened.snapshot().worldId());
      assertEquals(original, engine(reopened, new AtomicInteger()).lookup(request).orElseThrow());
      assertEquals(1, counter(reopened));
    } finally {
      reopened.close();
    }
  }

  @Test
  void conflictAndAuthorizationNeverRevealAnotherActorsResult() throws Exception {
    var store = open();
    var engine = engine(store, new AtomicInteger());
    var first = intent();
    engine.submit(first);
    var changed =
        new Intent(
            first.operationId(),
            new RequestId(UUID.randomUUID()),
            ACTOR,
            KIND,
            new CanonicalRequest(1, Map.of("delta", "2")),
            null,
            null);
    assertEquals("OPERATION_CONFLICT", engine.submit(changed).result().code());
    var other =
        new Intent(
            first.operationId(),
            new RequestId(UUID.randomUUID()),
            new PlayerId(UUID.randomUUID()),
            KIND,
            first.request(),
            null,
            null);
    assertEquals("ACCESS_DENIED", engine.submit(other).result().code());
    assertTrue(engine.lookup(other).isEmpty());
    var denied = new JournalEngine(store, (actor, kind) -> false, Map.of());
    assertTrue(denied.lookup(first).isEmpty());
    assertEquals(Status.NOT_ACCEPTED, denied.submit(first).status());
    var otherType =
        new Intent(
            first.operationId(),
            first.requestId(),
            ACTOR,
            new Kind("fixture:service", "fixture:other"),
            first.request(),
            null,
            null);
    assertEquals("OPERATION_CONFLICT", engine.submit(otherType).result().code());
    assertEquals(1, counter(store));
    store.close();
  }

  @Test
  void multiRecordProjectionFailureRollsEverythingBack() throws Exception {
    var store = open();
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
                                  "INSERT INTO fixture_projection VALUES('counter',1)", List.of());
                              throw new IllegalStateException(
                                  "fixture abort after projection write");
                            }))));
    Intent request = intent();
    assertThrows(IllegalStateException.class, () -> engine.submit(request));
    for (String table :
        List.of(
            "operation_outcomes", "journal_transactions", "journal_records", "fixture_projection"))
      assertEquals(0, count(store, table));
    assertTrue(engine.lookup(request).isEmpty());
    assertEquals(Status.COMMITTED, engine(store, new AtomicInteger()).submit(request).status());
    store.close();
  }

  @Test
  void terminalRejectionIsRetainedAndNeverRunsProjections() throws Exception {
    var store = open();
    var prepares = new AtomicInteger();
    var engine =
        new JournalEngine(
            store,
            (actor, kind) -> true,
            Map.of(
                KIND,
                new JournalEngine.Registration(
                    1,
                    (intent, reads) -> {
                      prepares.incrementAndGet();
                      return new JournalEngine.Reject(
                          new Result(1, "FIXTURE_DENIED", "stable reason"));
                    })));
    Intent request = intent();
    Outcome rejected = engine.submit(request);
    assertEquals(Status.DURABLY_REJECTED, rejected.status());
    assertNull(rejected.sequence());
    assertEquals(rejected, engine.submit(intent(request.operationId())));
    assertEquals(1, prepares.get());
    assertEquals(0, counter(store));
    assertEquals(0, count(store, "journal_transactions"));
    store.close();
    var reopened = open();
    var nowWouldAccept = engine(reopened, prepares);
    assertEquals(rejected, nowWouldAccept.submit(intent(request.operationId())));
    assertEquals(0, counter(reopened));
    reopened.close();
  }

  @Test
  void appendOnlyIncludesReplaceSealedRecordsAndCorrections() throws Exception {
    var store = open();
    var engine = engine(store, new AtomicInteger());
    var first = engine.submit(intent());
    Intent correction =
        new Intent(
            new OperationId(UUID.randomUUID()),
            new RequestId(UUID.randomUUID()),
            ACTOR,
            KIND,
            new CanonicalRequest(1, Map.of("delta", "1")),
            null,
            first.transactionId());
    Outcome second = engine.submit(correction);
    assertTrue(second.sequence().value() > first.sequence().value());
    assertEquals(
        first.transactionId(),
        engine.readAfter(first.sequence().value(), 1).getFirst().correctionOf());
    store.close();
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + world.resolve("economy/economy.sqlite"));
        var sql = connection.createStatement()) {
      sql.execute("PRAGMA foreign_keys=ON");
      for (String table :
          List.of("operation_outcomes", "journal_transactions", "journal_records")) {
        assertThrows(SQLException.class, () -> sql.executeUpdate("DELETE FROM " + table));
        String column = table.equals("journal_records") ? "payload" : "world_id";
        assertThrows(
            SQLException.class,
            () -> sql.executeUpdate("UPDATE " + table + " SET " + column + "=" + column));
        assertThrows(
            SQLException.class,
            () -> sql.executeUpdate("INSERT OR REPLACE INTO " + table + " SELECT * FROM " + table));
      }
      assertThrows(
          SQLException.class,
          () ->
              sql.executeUpdate(
                  "INSERT INTO journal_records VALUES("
                      + first.sequence().value()
                      + ",2,'fixture:late',1,'late')"));
    }
    var reopened = open();
    assertEquals(2, counter(reopened));
    assertEquals(2, engine(reopened, new AtomicInteger()).readAfter(0, 10).size());
    reopened.close();
  }

  @Test
  void uncertainCommitAndCleanupResolveOriginalOutcome() throws Exception {
    for (var fault : List.of(FailureHarness.Fault.AFTER_COMMIT, FailureHarness.Fault.RESET)) {
      var harness = new FailureHarness();
      harness.store.initialize(world);
      var prepares = new AtomicInteger();
      var engine = engine(harness.store, prepares);
      Intent request = intent();
      harness.fault.set(fault);
      Outcome result = engine.submit(request);
      assertEquals(Status.COMMITTED, result.status());
      assertEquals(2, harness.opens.get());
      assertEquals(result, engine.submit(intent(request.operationId())));
      assertEquals(1, prepares.get());
      harness.store.close();
    }
    var store = open();
    assertEquals(2, counter(store));
    store.close();
  }

  @Test
  void definitivelyAbsentCommitRequiresRetryWithSameKey() throws Exception {
    var harness = new FailureHarness();
    harness.store.initialize(world);
    var engine = engine(harness.store, new AtomicInteger());
    Intent request = intent();
    harness.fault.set(FailureHarness.Fault.BEFORE_COMMIT);
    var absent = engine.submit(request);
    assertEquals(Status.NOT_ACCEPTED, absent.status());
    assertEquals("ABSENT_RETRY_SAME_ID", absent.result().code());
    assertEquals(0, counter(harness.store));
    assertTrue(engine.lookup(request).isEmpty());
    assertEquals(Status.COMMITTED, engine.submit(intent(request.operationId())).status());
    assertEquals(1, counter(harness.store));
    harness.store.close();
  }

  @Test
  void rollbackFailureRecoversWithoutPartialEffectsAndFailedCloseHoldsOwnership() throws Exception {
    var harness = new FailureHarness();
    harness.store.initialize(world);
    Intent request = intent();
    var failing =
        new JournalEngine(
            harness.store,
            (actor, kind) -> true,
            Map.of(
                KIND,
                new JournalEngine.Registration(
                    1,
                    (intent, reads) ->
                        commit(
                            tx -> {
                              tx.execute(
                                  "INSERT INTO fixture_projection VALUES('counter',1)", List.of());
                              throw new IllegalStateException("abort");
                            }))));
    harness.fault.set(FailureHarness.Fault.ROLLBACK_AND_CLOSE);
    assertEquals(
        StorageException.Kind.UNSAFE_CONNECTION,
        assertThrows(StorageException.class, () -> failing.submit(request)).kind());
    assertEquals(1, harness.opens.get());
    assertThrows(StorageException.class, () -> harness.store.transaction(tx -> 1));
    assertEquals(
        StorageException.Kind.LOCK_TIMEOUT,
        assertThrows(StorageException.class, this::open).kind());
    harness.fault.set(FailureHarness.Fault.NONE);
    harness.store.recover();
    assertTrue(engine(harness.store, new AtomicInteger()).lookup(request).isEmpty());
    assertEquals(0, counter(harness.store));
    harness.store.close();
  }

  @Test
  void earlyValidationAndCanonicalVersionConsumeNoIdentity() throws Exception {
    assertThrows(
        IllegalArgumentException.class, () -> new CanonicalRequest(1, Map.of("bad", "\uD800")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Intent(
                new OperationId(UUID.randomUUID()),
                new RequestId(UUID.randomUUID()),
                ACTOR,
                KIND,
                new CanonicalRequest(1, Map.of("large", "x".repeat(16384))),
                null,
                null));
    var store = open();
    var engine = engine(store, new AtomicInteger());
    var first = intent();
    var unsupported =
        new Intent(
            first.operationId(),
            first.requestId(),
            ACTOR,
            KIND,
            new CanonicalRequest(2, Map.of("delta", "1")),
            null,
            null);
    assertEquals(Status.NOT_ACCEPTED, engine.submit(unsupported).status());
    assertEquals(0, count(store, "operation_outcomes"));
    assertEquals(Status.COMMITTED, engine.submit(first).status());
    store.close();
  }

  @Test
  void largeEnvelopesPageWithoutSplittingTransactions() throws Exception {
    var store = open();
    var records =
        java.util.stream.IntStream.range(0, 8)
            .mapToObj(i -> new Journal.Record("fixture:large", 1, "x".repeat(16384)))
            .toList();
    var engine =
        new JournalEngine(
            store,
            (actor, kind) -> true,
            Map.of(
                KIND,
                new JournalEngine.Registration(
                    1,
                    (intent, reads) ->
                        new JournalEngine.Commit(
                            records, new Result(1, "OK", "r".repeat(16384)), tx -> {}))));
    for (int i = 0; i < 10; i++) assertEquals(Status.COMMITTED, engine.submit(intent()).status());
    var first = engine.readAfter(0, 64);
    assertTrue(first.size() > 0 && first.size() < 10);
    assertTrue(first.stream().allMatch(envelope -> envelope.records().size() == 8));
    var second = engine.readAfter(first.getLast().sequence().value(), 64);
    assertEquals(10, first.size() + second.size());
    store.close();
  }
}
