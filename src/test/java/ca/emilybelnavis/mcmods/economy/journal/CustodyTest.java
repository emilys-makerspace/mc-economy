package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD;
import static ca.emilybelnavis.mcmods.economy.journal.Custody.*;
import static ca.emilybelnavis.mcmods.economy.journal.Custody.Action.*;
import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Role.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CustodyTest {
  @TempDir Path world;
  private final PlayerId owner = new PlayerId(UUID.randomUUID());
  private final PlayerId operator = new PlayerId(UUID.randomUUID());
  private final UUID source = UUID.randomUUID();
  private final UUID target = UUID.randomUUID();
  private boolean fits = true;
  private boolean proof = true;

  @Test
  void claimsRequireOwnerCapacityAndOneAttemptThenSavedEvidence() throws Exception {
    var store = open();
    try {
      seed(store);
      var original = financialIntent();
      var custody = reserve(store, original);
      assertEquals(
          Status.COMMITTED,
          change(
                  store,
                  RESERVATION_SAVED,
                  owner,
                  custody,
                  null,
                  evidence(EvidenceKind.RESERVATION_SAVED),
                  null)
              .status());
      fits = false;
      assertEquals("CUSTODY_CAPACITY", commit(store, custody, original).result().code());
      assertEquals(10_000L, balance(store, source));
      fits = true;
      var committed = commit(store, custody, original);
      assertEquals(Status.COMMITTED, committed.status());
      assertEquals(9_000L, balance(store, source));
      assertEquals(1_000L, balance(store, target));
      assertEquals(original.operationId().value(), find(store, custody).financialOperation());
      assertEquals(
          Status.COMMITTED,
          change(store, CLAIM, owner, custody, null, evidence(EvidenceKind.UNDELIVERED), null)
              .status());
      var stranger = new PlayerId(UUID.randomUUID());
      assertTrue(
          store.transaction(tx -> Custody.claims(tx::query, stranger, null, 1)).cases().isEmpty());
      assertEquals(
          1, store.transaction(tx -> Custody.claims(tx::query, owner, null, 1)).cases().size());
      var attempt = UUID.randomUUID();
      fits = false;
      assertEquals(
          "CUSTODY_CAPACITY",
          change(store, COLLECT, owner, custody, attempt, Evidence.unknown(), attempt)
              .result()
              .code());
      assertEquals(State.CLAIM, find(store, custody).state());
      fits = true;
      var collection = change(store, COLLECT, owner, custody, attempt, Evidence.unknown(), attempt);
      assertEquals(Status.COMMITTED, collection.status());
      assertEquals(
          collection, change(store, COLLECT, owner, custody, attempt, Evidence.unknown(), attempt));
      assertEquals(
          Status.DURABLY_REJECTED,
          change(store, COLLECT, owner, custody, UUID.randomUUID(), Evidence.unknown(), null)
              .status());
      assertEquals(
          Status.COMMITTED,
          change(store, ACKNOWLEDGE, owner, custody, attempt, Evidence.unknown(), null).status());
      assertEquals(State.ACKNOWLEDGED, find(store, custody).state());
      proof = false;
      assertEquals(
          Status.NOT_ACCEPTED,
          change(
                  store,
                  DELIVERY_SAVED,
                  owner,
                  custody,
                  attempt,
                  evidence(EvidenceKind.DELIVERY_SAVED),
                  null)
              .status());
      proof = true;
      assertEquals(
          Status.COMMITTED,
          change(
                  store,
                  DELIVERY_SAVED,
                  owner,
                  custody,
                  attempt,
                  evidence(EvidenceKind.DELIVERY_SAVED),
                  null)
              .status());
      assertEquals(State.DELIVERED, find(store, custody).state());
      assertEquals(10_000L, balance(store, source) + balance(store, target));
    } finally {
      store.close();
    }
  }

  @Test
  void restartQuarantinesClaimsAndEvidenceControlsOperatorResolution() throws Exception {
    var store = open();
    UUID custody;
    try {
      seed(store);
      var original = financialIntent();
      custody = reserve(store, original);
      change(
          store,
          RESERVATION_SAVED,
          owner,
          custody,
          null,
          evidence(EvidenceKind.RESERVATION_SAVED),
          null);
      commit(store, custody, original);
      change(store, CLAIM, owner, custody, null, evidence(EvidenceKind.UNDELIVERED), null);
    } finally {
      store.close();
    }
    var reopened = open();
    try {
      assertTrue(Custody.reconcileBatch(reopened));
      assertEquals(State.QUARANTINED, find(reopened, custody).state());
      assertTrue(
          reopened.transaction(tx -> Custody.claims(tx::query, owner, null, 63)).cases().isEmpty());
      assertEquals(
          Status.NOT_ACCEPTED,
          change(reopened, REDELIVER, owner, custody, null, evidence(EvidenceKind.LOST), null)
              .status());
      assertEquals(
          Status.NOT_ACCEPTED,
          change(reopened, REDELIVER, operator, custody, null, Evidence.unknown(), null).status());
      assertEquals(State.QUARANTINED, find(reopened, custody).state());
      assertEquals(
          Status.COMMITTED,
          change(reopened, REDELIVER, operator, custody, null, evidence(EvidenceKind.LOST), null)
              .status());
      assertEquals(State.CLAIM, find(reopened, custody).state());
      assertEquals(9_000L, balance(reopened, source));
    } finally {
      reopened.close();
    }
  }

  @Test
  void financialProjectionAndDeliveryIntentRollBackTogether() throws Exception {
    var store = open();
    try {
      seed(store);
      var original = financialIntent();
      var custody = reserve(store, original);
      change(
          store,
          RESERVATION_SAVED,
          owner,
          custody,
          null,
          evidence(EvidenceKind.RESERVATION_SAVED),
          null);
      var wrapped = Custody.financial(original, transfer());
      Custody.Financial failing =
          (intent, current, reads) -> {
            var base = (JournalEngine.Commit) wrapped.prepare(intent, current, reads);
            return new JournalEngine.Commit(
                base.records(),
                base.result(),
                tx -> {
                  base.projections().apply(tx);
                  throw new IllegalStateException("injected custody failure");
                });
          };
      var request = Custody.request(custody, null, Evidence.unknown(), "fixture");
      var intent =
          new Intent(
              original.operationId(),
              new RequestId(UUID.randomUUID()),
              owner,
              COMMIT.kind(),
              request,
              null,
              null);
      assertThrows(
          IllegalStateException.class, () -> engine(store, COMMIT, failing).submit(intent));
      assertEquals(State.WITNESSED, find(store, custody).state());
      assertEquals(10_000L, balance(store, source));
      assertEquals(0L, balance(store, target));
      assertTrue(engine(store, COMMIT, failing).lookup(intent).isEmpty());
      assertEquals(Status.COMMITTED, commit(store, custody, original).status());
    } finally {
      store.close();
    }
  }

  @Test
  void startupGatesSeventeenReservationsThroughBoundedReconciliation() throws Exception {
    var store = open();
    try {
      for (int index = 0; index < 17; index++) reserve(store, financialIntent());
    } finally {
      store.close();
    }
    var reopened = new SqliteStorage();
    var services =
        new ca.emilybelnavis.mcmods.economy.foundation.ServerServices(world.toRealPath(), reopened);
    try {
      var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      boolean gated = false;
      while (services.state()
              == ca.emilybelnavis.mcmods.economy.foundation.ServerServices.State.INITIALIZING
          && System.nanoTime() < deadline) {
        services.tick();
        gated |= services.progress().phase().equals("custody-reconciliation");
        Thread.sleep(1);
      }
      assertEquals(
          ca.emilybelnavis.mcmods.economy.foundation.ServerServices.State.READY, services.state());
      assertTrue(gated);
      var count =
          services
              .storage(
                  () ->
                      reopened.transaction(
                          tx ->
                              tx.query(
                                      "SELECT count(*) AS total FROM custody_cases WHERE state='QUARANTINED'",
                                      List.of(),
                                      1)
                                  .getFirst()
                                  .get("total")))
              .toCompletableFuture();
      while (!count.isDone() && System.nanoTime() < deadline) {
        services.tick();
        Thread.sleep(1);
      }
      assertEquals("17", count.join());
    } finally {
      assertTrue(services.stop(java.time.Duration.ofSeconds(3)));
    }
  }

  @Test
  void compensationRequiresLossProofAndCannotRepeatAfterReconciliation() throws Exception {
    var store = open();
    try {
      seed(store);
      var original = financialIntent();
      var custody = reserve(store, original);
      change(
          store,
          RESERVATION_SAVED,
          owner,
          custody,
          null,
          evidence(EvidenceKind.RESERVATION_SAVED),
          null);
      commit(store, custody, original);
      change(store, QUARANTINE, owner, custody, null, Evidence.unknown(), null);
      Custody.Financial refund =
          (intent, current, reads) ->
              Settlement.prepare(
                  reads,
                  List.of(
                      new PostingBatch.Leg(target, ACCOUNT, CAD, new Money(CAD, -1_000)),
                      new PostingBatch.Leg(source, ACCOUNT, CAD, new Money(CAD, 1_000))),
                  PostingBatch.Authority.TRANSFER,
                  null);
      var request =
          new Intent(
              new OperationId(UUID.randomUUID()),
              new RequestId(UUID.randomUUID()),
              operator,
              COMPENSATE.kind(),
              Custody.request(custody, null, evidence(EvidenceKind.LOST), "confirmed fixture loss"),
              null,
              null);
      proof = false;
      assertEquals(Status.NOT_ACCEPTED, engine(store, COMPENSATE, refund).submit(request).status());
      proof = true;
      var compensated = engine(store, COMPENSATE, refund).submit(request);
      assertEquals(Status.COMMITTED, compensated.status());
      assertEquals(compensated, engine(store, COMPENSATE, refund).submit(request));
      assertEquals(10_000L, balance(store, source));
      Custody.reconcileBatch(store);
      var other =
          new Intent(
              new OperationId(UUID.randomUUID()),
              new RequestId(UUID.randomUUID()),
              operator,
              request.kind(),
              request.request(),
              null,
              null);
      assertEquals(
          "CUSTODY_ALREADY_COMPENSATED",
          engine(store, COMPENSATE, refund).submit(other).result().code());
      assertEquals(
          "CUSTODY_ALREADY_COMPENSATED",
          change(store, REDELIVER, operator, custody, null, evidence(EvidenceKind.LOST), null)
              .result()
              .code());
    } finally {
      store.close();
    }
  }

  @Test
  void diagnosticsScopePagesWitnessLimitsAndRestartAudit() throws Exception {
    var store = open();
    UUID custody;
    UUID savedOperation = UUID.randomUUID();
    CustodyDiagnostics.Audit savedAudit;
    try {
      custody = reserve(store, financialIntent());
      reserve(store, financialIntent());
      var quarantined = reserve(store, financialIntent());
      change(store, QUARANTINE, owner, quarantined, null, Evidence.unknown(), null);
      var first = inspect(store, owner, CustodyDiagnostics.Filter.ALL, null, 1);
      assertTrue(first.pageLimitReached());
      var second = inspect(store, owner, CustodyDiagnostics.Filter.ALL, first.nextCursor(), 2);
      assertEquals(2, second.cases().size());
      assertNull(second.nextCursor());
      assertEquals(
          1,
          inspect(store, operator, CustodyDiagnostics.Filter.QUARANTINED, null, 63).cases().size());
      assertEquals(
          3,
          inspect(store, operator, CustodyDiagnostics.Filter.UNRESOLVED, null, 63).cases().size());
      var stranger = new PlayerId(UUID.randomUUID());
      assertTrue(
          inspect(store, stranger, CustodyDiagnostics.Filter.ALL, null, 63).cases().isEmpty());
      assertThrows(
          IllegalArgumentException.class,
          () -> inspect(store, owner, CustodyDiagnostics.Filter.ALL, null, 64));
      assertEquals(
          CustodyDiagnostics.WitnessStatus.ABSENT,
          witness(store, owner, custody).orElseThrow().status());
      byte[] bytes =
          "persisted fixture generation".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      var expected =
          new CustodySaveWitness.Expected(
              store.snapshot().worldId(),
              custody,
              UUID.randomUUID(),
              "fixture/source",
              CustodySaveWitness.digest(bytes));
      var evidence =
          new Evidence(
              EvidenceKind.RESERVATION_SAVED,
              "diagnostic-fixture",
              "fixture/source",
              expected.token());
      assertEquals(
          Status.COMMITTED,
          change(store, RESERVATION_SAVED, owner, custody, null, evidence, savedOperation)
              .status());
      assertEquals(
          CustodyDiagnostics.WitnessStatus.MISSING,
          witness(store, owner, custody).orElseThrow().status());
      java.nio.file.Files.createDirectories(world.resolve("fixture"));
      java.nio.file.Files.write(
          world.resolve("fixture/source"), new byte[CustodySaveWitness.MAX_BYTES + 1]);
      assertEquals(
          CustodyDiagnostics.WitnessStatus.SCAN_LIMIT,
          witness(store, owner, custody).orElseThrow().status());
      java.nio.file.Files.writeString(world.resolve("fixture/source"), "changed");
      assertEquals(
          CustodyDiagnostics.WitnessStatus.MISMATCH,
          witness(store, owner, custody).orElseThrow().status());
      java.nio.file.Files.write(world.resolve("fixture/source"), bytes);
      assertEquals(
          CustodyDiagnostics.WitnessStatus.MATCH,
          witness(store, owner, custody).orElseThrow().status());
      assertTrue(witness(store, stranger, custody).isEmpty());
      savedAudit = inspectAudit(store, owner, custody, savedOperation).orElseThrow();
      assertEquals(RESERVATION_SAVED, savedAudit.action());
      assertEquals(expected.token(), savedAudit.snapshot().evidence().saveToken());
      assertTrue(inspectAudit(store, stranger, custody, savedOperation).isEmpty());
      assertTrue(inspectAudit(store, owner, quarantined, savedOperation).isEmpty());
      assertEquals(2L, find(store, custody).revision());
    } finally {
      store.close();
    }
    var reopened = open();
    try {
      assertEquals(
          savedAudit, inspectAudit(reopened, operator, custody, savedOperation).orElseThrow());
      assertEquals(
          CustodyDiagnostics.WitnessStatus.MATCH,
          witness(reopened, operator, custody).orElseThrow().status());
    } finally {
      reopened.close();
    }
  }

  private CustodyDiagnostics.Page inspect(
      SqliteStorage store, ActorId actor, CustodyDiagnostics.Filter filter, UUID after, int limit) {
    return store.transaction(
        tx ->
            CustodyDiagnostics.page(
                tx::query, actor, (a, reads) -> a.equals(operator), filter, after, limit));
  }

  private Optional<CustodyDiagnostics.Witness> witness(
      SqliteStorage store, ActorId actor, UUID custody) {
    return store.transaction(
        tx ->
            CustodyDiagnostics.witness(
                tx::query,
                actor,
                (a, reads) -> a.equals(operator),
                custody,
                CustodyDiagnostics.Slot.RESERVATION,
                world,
                store.snapshot().worldId()));
  }

  private Optional<CustodyDiagnostics.Audit> inspectAudit(
      SqliteStorage store, ActorId actor, UUID custody, UUID operation) {
    return store.transaction(
        tx ->
            CustodyDiagnostics.audit(
                tx::query, actor, (a, reads) -> a.equals(operator), custody, operation));
  }

  @Test
  void movedOrUnavailableInventoryCannotAuthorizeAClaimOrRedelivery() throws Exception {
    var store = open();
    try {
      seed(store);
      for (String observation :
          List.of("items moved from recorded location", "chunk unavailable without loading")) {
        var original = financialIntent();
        var custody = reserve(store, original);
        change(
            store,
            RESERVATION_SAVED,
            owner,
            custody,
            null,
            evidence(EvidenceKind.RESERVATION_SAVED),
            null);
        assertEquals(Status.COMMITTED, commit(store, custody, original).status());
        proof = false;
        var request =
            new Intent(
                new OperationId(UUID.randomUUID()),
                new RequestId(UUID.randomUUID()),
                owner,
                CLAIM.kind(),
                Custody.request(custody, null, evidence(EvidenceKind.UNDELIVERED), observation),
                null,
                null);
        assertEquals(
            "CUSTODY_EVIDENCE", engine(store, CLAIM, null).submit(request).result().code());
        assertEquals(State.COMMITTED, find(store, custody).state());
        assertTrue(
            store.transaction(tx -> Custody.claims(tx::query, owner, null, 63)).cases().isEmpty());
        assertEquals(
            Status.COMMITTED,
            change(store, QUARANTINE, owner, custody, null, Evidence.unknown(), null).status());
        assertEquals(
            "CUSTODY_EVIDENCE",
            change(store, REDELIVER, operator, custody, null, evidence(EvidenceKind.LOST), null)
                .result()
                .code());
        assertEquals(State.QUARANTINED, find(store, custody).state());
        proof = true;
      }
      assertEquals(8_000L, balance(store, source));
      assertEquals(2_000L, balance(store, target));
    } finally {
      store.close();
    }
  }

  private SqliteStorage open() throws Exception {
    var store = new SqliteStorage();
    store.initialize(world.toRealPath());
    return store;
  }

  private UUID reserve(SqliteStorage store, Intent financial) {
    var terms =
        new Terms(
            owner,
            "minecraft:stone[count=4,components={}]",
            "fixture/source",
            "fixture/player",
            Custody.financialIntent(financial));
    var intent =
        new Intent(
            new OperationId(UUID.randomUUID()),
            new RequestId(UUID.randomUUID()),
            owner,
            RESERVE.kind(),
            Custody.reserveRequest(terms),
            null,
            null);
    assertEquals(Status.COMMITTED, engine(store, RESERVE, null).submit(intent).status());
    return intent.operationId().value();
  }

  private Outcome commit(SqliteStorage store, UUID custody, Intent original) {
    var intent =
        new Intent(
            original.operationId(),
            new RequestId(UUID.randomUUID()),
            owner,
            COMMIT.kind(),
            Custody.request(custody, null, Evidence.unknown(), "fixture"),
            null,
            null);
    return engine(store, COMMIT, Custody.financial(original, transfer())).submit(intent);
  }

  private Outcome change(
      SqliteStorage store,
      Action action,
      ActorId actor,
      UUID custody,
      UUID attempt,
      Evidence evidence,
      UUID operation) {
    var id = operation == null ? UUID.randomUUID() : operation;
    return engine(store, action, null)
        .submit(
            new Intent(
                new OperationId(id),
                new RequestId(UUID.randomUUID()),
                actor,
                action.kind(),
                Custody.request(custody, attempt, evidence, "fixture"),
                null,
                null));
  }

  private JournalEngine engine(SqliteStorage store, Action action, Custody.Financial financial) {
    return new JournalEngine(
        store,
        (actor, kind) -> kind.equals(action.kind()),
        Map.of(
            action.kind(),
            Custody.registration(
                action,
                (a, intent, current, reads) ->
                    switch (a) {
                      case CLOSE_DELIVERED, REDELIVER, COMPENSATE ->
                          intent.actor().equals(operator);
                      default -> intent.actor().equals(current.terms().owner());
                    },
                (current, evidence, reads) -> proof,
                financial,
                (intent, current, reads) -> fits)));
  }

  private Intent financialIntent() {
    return new Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        owner,
        new Kind("economy:fixture", "economy:transfer"),
        Settlement.request(
            List.of(
                new PostingBatch.Leg(source, ACCOUNT, CAD, new Money(CAD, -1_000)),
                new PostingBatch.Leg(target, ACCOUNT, CAD, new Money(CAD, 1_000)))),
        null,
        null);
  }

  private JournalEngine.Registration transfer() {
    return Settlement.registration((intent, legs, reads) -> PostingBatch.Authority.TRANSFER);
  }

  private Evidence evidence(EvidenceKind kind) {
    return new Evidence(
        kind,
        "fixture-proof",
        kind == EvidenceKind.RESERVATION_SAVED ? "fixture/source" : "fixture/player",
        "generation-1");
  }

  private Custody.Case find(SqliteStorage store, UUID id) {
    return store.transaction(tx -> Custody.find(tx::query, owner, id, false)).orElseThrow();
  }

  private void seed(SqliteStorage store) {
    store.transaction(
        tx -> {
          for (var id : List.of(source, target))
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,0)",
                List.of(id.toString(), UUID.randomUUID().toString()));
          return null;
        });
    var kind = new Kind("economy:fixture", "economy:issue");
    var funding =
        new JournalEngine(
            store,
            (a, k) -> true,
            Map.of(
                kind,
                Settlement.registration((intent, legs, reads) -> PostingBatch.Authority.ISSUE)));
    assertEquals(
        Status.COMMITTED,
        funding
            .submit(
                new Intent(
                    new OperationId(UUID.randomUUID()),
                    new RequestId(UUID.randomUUID()),
                    owner,
                    kind,
                    Settlement.request(
                        List.of(
                            new PostingBatch.Leg(source, ACCOUNT, CAD, new Money(CAD, 10_000)),
                            new PostingBatch.Leg(
                                Settlement.issuanceAccount(CAD),
                                ISSUANCE,
                                CAD,
                                new Money(CAD, -10_000)))),
                    null,
                    null))
            .status());
  }

  private long balance(SqliteStorage store, UUID id) {
    return store.transaction(tx -> Holds.available(tx::query, id, CAD));
  }
}
