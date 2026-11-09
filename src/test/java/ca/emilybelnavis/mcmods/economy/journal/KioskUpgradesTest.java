package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KioskUpgradesTest {
  @TempDir Path world;
  final PlayerId owner = new PlayerId(UUID.randomUUID());
  final TerminalPolicy.Anchor anchor = new TerminalPolicy.Anchor("minecraft:overworld", 10, 64, 10);
  final UUID generation = UUID.randomUUID(), operation = UUID.randomUUID();

  @Test
  void preparationGatesFinanceAndCompletionPreservesBindingAcrossReplayReplacementAndReopen()
      throws Exception {
    var store = open();
    try {
      setup(store);
      var original = snapshot(store);
      var begin = intent(KioskUpgrades.Action.BEGIN, owner, 2);
      assertEquals(
          Status.COMMITTED, engine(store, KioskUpgrades.Action.BEGIN, true).submit(begin).status());
      assertFalse(
          store.<Boolean>transaction(
              tx ->
                  TerminalPolicy.matches(
                      tx::query, store.snapshot().worldId(), anchor, generation, 3)));
      assertEquals(original.binding(), snapshot(store).binding());
      assertEquals(original.owner(), snapshot(store).owner());
      var bind =
          new Intent(
              new OperationId(UUID.randomUUID()),
              new RequestId(UUID.randomUUID()),
              owner,
              TerminalBanking.BIND,
              TerminalBanking.bindRequest(
                  anchor, generation, 3, original.binding().institution(), true),
              null,
              null);
      var binding =
          new JournalEngine(
              store,
              (a, k) -> true,
              Map.of(
                  TerminalBanking.BIND,
                  TerminalBanking.registration(
                      store.snapshot().worldId(),
                      Map.of(),
                      (a, i, c, r) -> true,
                      (a, r) -> false)));
      assertEquals("KIOSK_UPGRADE_PENDING", binding.submit(bind).result().code());
      var finish = intent(KioskUpgrades.Action.COMPLETE, new SystemActorId(generation), 3);
      assertEquals(
          Status.NOT_ACCEPTED,
          engine(store, KioskUpgrades.Action.COMPLETE, false).submit(finish).status());
      var completed = engine(store, KioskUpgrades.Action.COMPLETE, true).submit(finish);
      assertEquals(Status.COMMITTED, completed.status());
      assertEquals(4, snapshot(store).revision());
      assertEquals(original.binding(), snapshot(store).binding());
      assertTrue(
          store.<Boolean>transaction(
              tx ->
                  TerminalPolicy.matches(
                      tx::query, store.snapshot().worldId(), anchor, generation, 4)));
      assertEquals(completed, engine(store, KioskUpgrades.Action.COMPLETE, false).submit(finish));
      assertEquals(
          Status.COMMITTED,
          engine(store, KioskUpgrades.Action.BEGIN, false).submit(begin).status());
      assertEquals(4, snapshot(store).revision());
      policy(
          store, TerminalPolicy.Action.REMOVE, TerminalPolicy.removeRequest(anchor, generation, 4));
      policy(
          store,
          TerminalPolicy.Action.PLACE,
          TerminalPolicy.placeRequest(
              anchor, UUID.randomUUID(), owner, TerminalPolicy.Kind.KIOSK, generation, 5));
      assertTrue(
          store
              .transaction(tx -> tx.query("SELECT * FROM account_catalog", List.of(), 1))
              .isEmpty());
    } finally {
      store.close();
    }
    var reopened = open();
    try {
      assertEquals(
          "COMPLETE",
          reopened
              .transaction(
                  tx -> KioskUpgrades.find(tx::query, reopened.snapshot().worldId(), generation))
              .orElseThrow()
              .phase());
    } finally {
      reopened.close();
    }
  }

  @Test
  void rejectedAuthorityAndCleanAbortKeepTheOriginalNativeService() throws Exception {
    var store = open();
    try {
      setup(store);
      var original = snapshot(store);
      var unauthorized = intent(KioskUpgrades.Action.BEGIN, new PlayerId(UUID.randomUUID()), 2);
      assertEquals(
          Status.NOT_ACCEPTED,
          engine(store, KioskUpgrades.Action.BEGIN, true).submit(unauthorized).status());
      assertEquals(2, snapshot(store).revision());
      assertEquals(
          Status.COMMITTED,
          engine(store, KioskUpgrades.Action.BEGIN, true)
              .submit(intent(KioskUpgrades.Action.BEGIN, owner, 2))
              .status());
      assertEquals(
          Status.COMMITTED,
          engine(store, KioskUpgrades.Action.ABORT, true)
              .submit(intent(KioskUpgrades.Action.ABORT, new SystemActorId(generation), 3))
              .status());
      assertEquals(original.binding(), snapshot(store).binding());
      assertEquals(4, snapshot(store).revision());
      assertTrue(
          store.<Boolean>transaction(
              tx -> KioskUpgrades.usable(tx::query, store.snapshot().worldId(), generation)));
    } finally {
      store.close();
    }
  }

  private void setup(SqliteStorage store) {
    policy(
        store,
        TerminalPolicy.Action.PLACE,
        TerminalPolicy.placeRequest(anchor, generation, owner, TerminalPolicy.Kind.KIOSK, null, 0));
    var request =
        TerminalBanking.bindRequest(
            anchor, generation, 1, InstitutionCatalog.banks().getFirst().id(), true);
    var i =
        new Intent(
            new OperationId(UUID.randomUUID()),
            new RequestId(UUID.randomUUID()),
            owner,
            TerminalBanking.BIND,
            request,
            null,
            null);
    assertEquals(
        Status.COMMITTED,
        new JournalEngine(
                store,
                (a, k) -> true,
                Map.of(
                    TerminalBanking.BIND,
                    TerminalBanking.registration(
                        store.snapshot().worldId(),
                        Map.of(),
                        (a, j, c, r) -> true,
                        (a, r) -> false)))
            .submit(i)
            .status());
  }

  private void policy(SqliteStorage store, TerminalPolicy.Action action, CanonicalRequest request) {
    var i =
        new Intent(
            new OperationId(UUID.randomUUID()),
            new RequestId(UUID.randomUUID()),
            owner,
            action.kind(),
            request,
            null,
            null);
    assertEquals(
        Status.COMMITTED,
        new JournalEngine(
                store,
                (a, k) -> true,
                Map.of(
                    action.kind(),
                    TerminalPolicy.registration(
                        action,
                        store.snapshot().worldId(),
                        Map.of(),
                        (a, j, c, r) -> true,
                        (a, r) -> false)))
            .submit(i)
            .status());
  }

  private Intent intent(KioskUpgrades.Action action, ActorId actor, long revision) {
    return new Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        actor,
        action.kind(),
        KioskUpgrades.request(anchor, generation, revision, operation),
        null,
        null);
  }

  private JournalEngine engine(SqliteStorage store, KioskUpgrades.Action action, boolean live) {
    return new JournalEngine(
        store,
        (a, k) -> true,
        Map.of(
            action.kind(),
            KioskUpgrades.registration(
                action, store.snapshot().worldId(), (a, i, c, r) -> live, (a, r) -> false)));
  }

  private TerminalPolicy.Snapshot snapshot(SqliteStorage store) {
    return store
        .transaction(
            tx -> TerminalPolicy.find(tx::query, store.snapshot().worldId(), anchor, owner, false))
        .orElseThrow();
  }

  private SqliteStorage open() throws Exception {
    var s = new SqliteStorage();
    s.initialize(world.toRealPath());
    return s;
  }
}
