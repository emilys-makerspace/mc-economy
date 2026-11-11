package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real SQLite evidence for later-site provenance; physical terrain is a separate disposable
 * witness.
 */
class BranchPlacementTest {
  @TempDir Path world;

  @Test
  void explicitSiteAttemptRetainsCaptureAndCannotBecomeARebuildAfterConflict() throws Exception {
    UUID branch, operation = UUID.randomUUID();
    String captured;
    var store = open();
    try {
      var w = store.snapshot().worldId();
      var source = new Source("minecraft:overworld", "minecraft:village_plains", 0, 0);
      var bank = InstitutionCatalog.banks().getFirst();
      var original =
          new BranchPlanV1(
              0,
              true,
              Size.COMPACT,
              bank.id().key(),
              bank.baseCurrency(),
              null,
              null,
              null,
              null,
              List.of(),
              List.of(),
              List.of(),
              State.SITE_PENDING,
              "TERRAIN_EXHAUSTED",
              0,
              -1);
      var plan =
          new VillagePlanV1(
              source,
              "plains",
              5,
              List.of(new Bounds(-20, 60, -20, 20, 70, 20)),
              List.of(new Point(0, 64, 0)),
              new PlanningSnapshotV1(
                  EconomyCurrency.CAD,
                  1,
                  1,
                  1,
                  "pinned-v1",
                  List.of(new Provider(bank.id().key(), bank.baseCurrency(), true)),
                  List.of()),
              CaptureStatus.CAPTURED,
              List.of(original),
              "");
      captured = plan.digest();
      branch = BranchPlans.branchId(BranchPlans.id(w, source), 0);
      var observe =
          new Intent(
              new OperationId(UUID.randomUUID()),
              new RequestId(UUID.randomUUID()),
              BranchPlans.owner(BranchPlans.id(w, source)),
              Branches.Action.OBSERVE.kind(),
              Branches.observeRequest(plan),
              null,
              null);
      var observed =
          new JournalEngine(
                  store,
                  (actor, kind) -> true,
                  Map.of(
                      observe.kind(),
                      Branches.registration(
                          Branches.Action.OBSERVE, w, i -> i.equals(observe), plan)))
              .submit(observe);
      assertEquals(Status.COMMITTED, observed.status());
      var choice =
          new BranchPlanV1(
              0,
              true,
              Size.STANDARD,
              bank.id().key(),
              bank.baseCurrency(),
              "economy:banks/crownstone",
              new Transform(new Point(30, 64, 30), 0),
              new Bounds(30, 58, 30, 46, 79, 46),
              new Point(38, 65, 30),
              List.of(
                  new Anchor(0, new Point(32, 65, 32), BranchPlans.generation(branch, 0), "wide"),
                  new Anchor(1, new Point(34, 65, 32), BranchPlans.generation(branch, 1), "atm"),
                  new Anchor(2, new Point(36, 65, 32), BranchPlans.generation(branch, 2), "atm")),
              List.of(
                  new Anchor(0, new Point(37, 65, 42), BranchPlans.generation(branch, 4), "desk")),
              List.of(),
              State.RESERVED,
              "SITE_RESERVED",
              1,
              -1);
      assertEquals(
          Status.NOT_ACCEPTED,
          submit(store, BranchPlacement.Action.RESERVE, branch, 1, operation, choice, false)
              .status());
      assertEquals("SITE_PENDING", view(store, branch).state());
      var reserved =
          submit(store, BranchPlacement.Action.RESERVE, branch, 1, operation, choice, true);
      assertEquals(Status.COMMITTED, reserved.status());
      assertEquals(captured, view(store, branch).plan().digest());
      assertEquals(
          choice,
          store
              .transaction(tx -> BranchPlacement.latestChoice(tx::query, w, branch))
              .orElseThrow());
      assertEquals(3, view(store, branch).anchors().size());
      assertEquals(
          Status.COMMITTED,
          submit(store, BranchPlacement.Action.START, branch, 2, operation, null, true).status());
      assertEquals("PLACING", view(store, branch).state());
      assertEquals(
          Status.COMMITTED,
          submit(store, BranchPlacement.Action.FAIL, branch, 3, operation, null, true).status());
      assertEquals("CONFLICT", view(store, branch).state());
      assertEquals(
          "PLACEMENT_STATE",
          submit(store, BranchPlacement.Action.RESERVE, branch, 4, UUID.randomUUID(), choice, true)
              .result()
              .code());
      assertTrue(
          store
              .transaction(tx -> tx.query("SELECT * FROM terminal_settings", List.of(), 1))
              .isEmpty());
      assertTrue(
          store
              .transaction(tx -> tx.query("SELECT * FROM account_catalog", List.of(), 1))
              .isEmpty());
    } finally {
      store.close();
    }
    var reopened = open();
    try {
      assertEquals("CONFLICT", view(reopened, branch).state());
      assertEquals(captured, view(reopened, branch).plan().digest());
      var attempt =
          reopened
              .transaction(
                  tx -> BranchPlacement.latest(tx::query, reopened.snapshot().worldId(), branch))
              .orElseThrow();
      assertEquals(operation, attempt.operation());
      assertEquals("CONFLICT", attempt.phase());
    } finally {
      reopened.close();
    }
  }

  private Outcome submit(
      SqliteStorage store,
      BranchPlacement.Action action,
      UUID branch,
      long revision,
      UUID operation,
      BranchPlanV1 choice,
      boolean live) {
    var request =
        BranchPlacement.request(
            branch,
            revision,
            1,
            operation,
            action == BranchPlacement.Action.RESERVE
                ? "SITE_RESERVED"
                : action == BranchPlacement.Action.START
                    ? "PLACEMENT_STARTED"
                    : "PLACEMENT_CHANGED",
            choice);
    var intent =
        new Intent(
            new OperationId(UUID.randomUUID()),
            new RequestId(UUID.randomUUID()),
            BranchPlans.owner(branch),
            action.kind(),
            request,
            null,
            null);
    return new JournalEngine(
            store,
            (actor, kind) -> true,
            Map.of(
                action.kind(),
                BranchPlacement.registration(
                    action, store.snapshot().worldId(), i -> live && i.equals(intent), choice)))
        .submit(intent);
  }

  private Branches.View view(SqliteStorage store, UUID branch) {
    return store
        .transaction(tx -> Branches.find(tx::query, store.snapshot().worldId(), branch))
        .orElseThrow();
  }

  private SqliteStorage open() throws Exception {
    var store = new SqliteStorage();
    store.initialize(world.toRealPath());
    return store;
  }
}
