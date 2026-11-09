package ca.emilybelnavis.mcmods.economy.foundation;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.account.AccountLifecycle;
import ca.emilybelnavis.mcmods.economy.account.AccountLifecycle.Close;
import ca.emilybelnavis.mcmods.economy.account.AccountLifecycle.ClosureReader;
import ca.emilybelnavis.mcmods.economy.account.AccountLifecycle.ClosureState;
import ca.emilybelnavis.mcmods.economy.account.AccountLifecycle.Open;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity.Status;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.journal.JournalService;
import ca.emilybelnavis.mcmods.economy.storage.FailureHarness;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountLifecycleTest {
  @TempDir Path world;
  private final PlayerId player = new PlayerId(UUID.randomUUID());
  private final UUID session = UUID.randomUUID();

  private static final JournalService.Publisher PUBLISHER =
      new JournalService.Publisher() {
        @Override
        public void invalidate(Journal.Outcome committed) {}

        @Override
        public void publish(Journal.Outcome terminal) {}
      };

  @Test
  void rawOpeningAndClosingRequireBranchEvenWithTrueAuthorityPredicate() throws Exception {
    var owner = owner(new WorldId(UUID.randomUUID()), player);
    var open = new Open(EUR, 1);
    var request =
        new Journal.CanonicalRequest(
            1,
            Map.of(
                "currency",
                "EUR",
                "primary_revision",
                "1",
                "economy:owner",
                Journal.actorKey(player)));
    var intent =
        new Journal.Intent(
            operationId(), requestId(), player, AccountLifecycle.OPEN, request, null, null);
    var reads =
        (JournalEngine.ReadView)
            (sql, p, max) -> {
              throw new AssertionError("Raw lifecycle must deny before querying");
            };
    assertEquals(
        "BRANCH_REQUIRED",
        ((JournalEngine.Reject)
                AccountLifecycle.opening(owner, open).handler().prepare(intent, reads))
            .result()
            .code());
    var close = new Close(accountId(), 1, null, null);
    var fields = new java.util.HashMap<>(close.request().fields());
    fields.put("economy:owner", Journal.actorKey(player));
    var closeIntent =
        new Journal.Intent(
            operationId(),
            requestId(),
            player,
            AccountLifecycle.CLOSE,
            new Journal.CanonicalRequest(1, fields),
            null,
            null);
    var registration =
        AccountLifecycle.closing(
            owner,
            close,
            state(0, false, false, false),
            close.request(),
            (i, r) -> true,
            (i, r) -> fail("No settlement"));
    assertEquals(
        "BRANCH_REQUIRED",
        ((JournalEngine.Reject) registration.handler().prepare(closeIntent, reads))
            .result()
            .code());
  }

  @Test
  void closureChecksProtectionClaimsAndDestinationWithoutChangingAccounts() throws Exception {
    var store = new SqliteStorage();
    store.initialize(world.toRealPath());
    try {
      select(store);
      var owner = owner(store.snapshot().worldId(), player);
      var primary = accountId();
      var denominationBank = accountId();
      var source = accountId();
      var foreign = accountId();
      store.transaction(
          tx -> {
            insertBank(tx, primary, player, EUR, true);
            insertBank(tx, denominationBank, player, CAD, false);
            insertBank(tx, source, player, JPY, false);
            insertBank(tx, foreign, new PlayerId(UUID.randomUUID()), USD, false);
            return null;
          });

      ClosureReader untouched =
          (reads, account) -> fail("Ledger must remain private for invalid sources");
      assertEquals(
          "ACCOUNT_PROTECTED", close(store, owner, new Close(primary, 1, null, null), untouched));
      assertEquals(
          "ACCOUNT_PROTECTED",
          close(store, owner, new Close(denominationBank, 1, null, null), untouched));
      assertEquals(
          "ACCOUNT_STALE", close(store, owner, new Close(source, 2, null, null), untouched));
      assertEquals(
          "ACTIVE_HOLD",
          close(store, owner, new Close(source, 1, null, null), state(0, true, false, false)));
      assertEquals(
          "RESTRICTED_FUNDS",
          close(store, owner, new Close(source, 1, null, null), state(0, false, true, false)));
      assertEquals(
          "UNRESOLVED_OBLIGATION",
          close(store, owner, new Close(source, 1, null, null), state(0, false, false, true)));
      assertEquals(
          "CLOSURE_STATE_UNAVAILABLE",
          close(
              store,
              owner,
              new Close(source, 1, null, null),
              (reads, account) -> Optional.empty()));

      var funded = state(100, false, false, false);
      assertEquals(
          "DESTINATION_UNAVAILABLE",
          close(store, owner, new Close(source, 1, foreign, 1L), funded));
      assertEquals(
          "DESTINATION_STALE", close(store, owner, new Close(source, 1, primary, 2L), funded));
      assertEquals(
          "FUNDED_SETTLEMENT_UNAVAILABLE",
          close(store, owner, new Close(source, 1, primary, 1L), funded));
      var unchanged =
          store.transaction(tx -> AccountCatalog.find(tx::query, owner, source).orElseThrow());
      assertEquals(Status.OPEN, unchanged.status());
      assertEquals(1, unchanged.revision());
    } finally {
      store.close();
    }
  }

  @Test
  void interruptedClosureRollsBackAndRetainsClosedIdentityAfterRestart() throws Exception {
    var harness = new FailureHarness();
    var store = harness.store;
    store.initialize(world.toRealPath());
    var source = accountId();
    var operationId = operationId();
    var request = new Close(source, 1, null, null);
    PartyRef owner;
    Journal.Outcome original;
    try {
      select(store);
      owner = owner(store.snapshot().worldId(), player);
      store.transaction(
          tx -> {
            insertBank(tx, accountId(), player, CAD, true);
            insertBank(tx, source, player, JPY, false);
            return null;
          });
      var attempt = closure(owner, session, operationId, request, state(0, false, false, false));
      var engine = attempt.engine(store, session);
      harness.fault.set(FailureHarness.Fault.BEFORE_COMMIT);
      assertEquals("ABSENT_RETRY_SAME_ID", engine.submit(attempt.intent()).result().code());
      assertEquals(
          Status.OPEN,
          store.transaction(
              tx -> AccountCatalog.find(tx::query, owner, source).orElseThrow().status()));
      assertTrue(engine.lookup(attempt.intent()).isEmpty());

      original = engine.submit(attempt.intent());
      assertEquals(Journal.Status.COMMITTED, original.status());
      assertEquals("BANK_CLOSED", original.result().code());
      assertEquals(original, engine.submit(attempt.intent()));
    } finally {
      store.close();
    }

    var reopened = new SqliteStorage();
    reopened.initialize(world.toRealPath());
    try {
      var newSession = UUID.randomUUID();
      var retry =
          closure(
              owner,
              newSession,
              operationId,
              request,
              (reads, account) -> fail("Replay must bypass ledger"));
      var engine = retry.engine(reopened, newSession);
      assertEquals(original, engine.lookup(retry.intent()).orElseThrow());
      assertEquals(original, engine.submit(retry.intent()));
      var closed =
          reopened.transaction(tx -> AccountCatalog.find(tx::query, owner, source).orElseThrow());
      assertEquals(Status.CLOSED, closed.status());
      assertEquals(2, closed.revision());
      assertEquals(JPY, closed.currency());
      var fact = engine.readAfter(original.sequence().value() - 1, 2).getFirst();
      assertEquals(player, fact.actor());
      assertEquals(owner.world(), fact.worldId());
      assertEquals(original.operationId(), fact.operationId());
      assertTrue(
          fact.records().stream()
              .anyMatch(record -> record.payload().contains(source.value().toString())));
    } finally {
      reopened.close();
    }
  }

  private List<String> compete(
      ServerServices services, PartyAuthorization.Grant grant, Open first, Open second)
      throws Exception {
    var blocked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    services.storage(
        () -> {
          blocked.countDown();
          try {
            assertTrue(release.await(5, TimeUnit.SECONDS));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
          }
          return null;
        });
    JournalService.Submission one;
    JournalService.Submission two;
    try {
      assertTrue(blocked.await(5, TimeUnit.SECONDS));
      one = open(services, grant, first);
      two = open(services, grant, second);
      assertEquals(Journal.Receipt.ACCEPTED_PENDING, one.receipt());
      assertEquals(Journal.Receipt.ACCEPTED_PENDING, two.receipt());
    } finally {
      release.countDown();
    }
    return List.of(
        complete(services, one).result().code(), complete(services, two).result().code());
  }

  private JournalService.Submission open(
      ServerServices services, PartyAuthorization.Grant grant, Open request) {
    return services.submitOwned(
        grant,
        AccountLifecycle.OPEN,
        operationId(),
        requestId(),
        request.request(),
        AccountLifecycle.opening(grant.target(), request),
        PUBLISHER);
  }

  private String close(SqliteStorage store, PartyRef owner, Close request, ClosureReader reader) {
    var attempt = closure(owner, session, operationId(), request, reader);
    return attempt.engine(store, session).submit(attempt.intent()).result().code();
  }

  private final Map<OperationId, BranchAdmission.Proof> proofs = new java.util.HashMap<>();

  private PartyOperation closure(
      PartyRef owner, UUID scope, OperationId id, Close request, ClosureReader reader) {
    var proof =
        proofs.computeIfAbsent(
            id,
            ignored -> {
              var context =
                  new BranchAdmission.Context(
                      owner.world(),
                      scope,
                      player,
                      BranchAdmission.Action.CLOSE,
                      new InstitutionCatalog.InstitutionId("economy:bank/cedar_parcel"),
                      null,
                      request.source(),
                      request.expectedRevision(),
                      1,
                      0);
              return BranchFixtures.proof(context);
            });
    // Override the source kiosk for protection fixtures; only JPY can reach the closure reader.
    var full = new BankProviders.Request(proof, 0, request.request().fields()).request();
    var fields = new java.util.HashMap<>(full.fields());
    fields.remove("economy:owner");
    var canonical = new Journal.CanonicalRequest(full.version(), fields);
    return PartyOperation.owned(
        grant(owner, scope),
        BankProviders.CLOSE,
        id,
        requestId(),
        canonical,
        AccountLifecycle.closingAtBranch(owner, request, reader, proof));
  }

  private PartyAuthorization.Grant grant(PartyRef owner, UUID scope) {
    return PartyAuthorization.admit(owner.world(), scope, player, false, owner, false)
        .orElseThrow();
  }

  private static PartyRef owner(WorldId world, PlayerId player) {
    return new PartyRef(world, new PartyRef.Player(player));
  }

  private static ClosureReader state(
      long minorUnits, boolean hold, boolean restricted, boolean obligation) {
    return (reads, account) ->
        Optional.of(new ClosureState(minorUnits, hold, restricted, obligation));
  }

  private static void insertBank(
      FoundationStore.Transaction tx,
      AccountId account,
      PlayerId owner,
      EconomyCurrency currency,
      boolean primary) {
    tx.execute(
        """
                INSERT INTO account_catalog(account_id,owner_id,purpose,currency,status,revision,is_primary)
                VALUES(?,?,'BANK',?,'OPEN',1,?)
                """,
        List.of(
            account.value().toString(),
            owner.value().toString(),
            currency.code(),
            primary ? "1" : "0"));
    tx.execute(
        "INSERT INTO bank_account_providers VALUES(?,?,1,1,0)",
        List.of(account.value().toString(), "economy:bank/cedar_parcel"));
  }

  private void select(SqliteStorage store) {
    var engine =
        new JournalEngine(
            store,
            (actor, kind) -> actor.equals(player) && kind.equals(FxService.OPEN),
            Map.of(FxService.OPEN, FxService.opening(Clock.systemUTC())));
    assertEquals(Journal.Status.COMMITTED, engine.submit(selection()).status());
  }

  private Journal.Intent selection() {
    return new Journal.Intent(
        operationId(),
        requestId(),
        player,
        FxService.OPEN,
        new Journal.CanonicalRequest(1, Map.of("currency", "CAD")),
        null,
        null);
  }

  private static AccountId accountId() {
    return new AccountId(UUID.randomUUID());
  }

  private static RequestId requestId() {
    return new RequestId(UUID.randomUUID());
  }

  private static OperationId operationId() {
    return new OperationId(UUID.randomUUID());
  }

  private static Journal.Outcome complete(
      ServerServices services, JournalService.Submission submission) throws Exception {
    return await(services, submission.completion()).outcome();
  }

  private static <T> T await(ServerServices services, CompletionStage<T> stage) throws Exception {
    var future = stage.toCompletableFuture();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!future.isDone() && System.nanoTime() < deadline) {
      services.tick();
      Thread.sleep(1);
    }
    return future.get(1, TimeUnit.SECONDS);
  }
}
