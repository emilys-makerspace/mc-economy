package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banker.StaffingRecords.*;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Production journal and SQLite witnesses; no physical-entity absence is treated as death. */
class BankStaffingTest {
  @TempDir Path directory;

  @Test
  void positiveDeathEarnsDurableLoadedProgressAndReplacementNeverReusesIdentity() throws Exception {
    UUID branch, firstEmployee, newEmployee;
    Intent replacement;
    Outcome replaced;
    try (var fixture = open()) {
      var store = fixture.store();
      branch = capture(store, true);
      var baseline = financial(store);
      var attach =
          command(
              Action.ATTACH,
              branch,
              4,
              0,
              UUID.randomUUID(),
              UUID.randomUUID(),
              0,
              false,
              List.of());
      firstEmployee = attach.employee();
      assertCommitted(submit(store, attach, true));
      var row = row(store, branch);
      assertEquals("RESERVED", row.phase());
      assertCommitted(submit(store, from(Action.BEGIN, store, row, 0), true));
      row = row(store, branch);
      assertCommitted(submit(store, from(Action.UNKNOWN, store, row, 0), true));
      row = row(store, branch);
      var confirmation = from(Action.LIVE, store, row, 0);
      var confirmedIntent = intent(confirmation);
      var loadedProof = new AtomicBoolean(false);
      var confirmationEngine = engine(store, confirmation, confirmedIntent, loadedProof);
      assertEquals(Status.NOT_ACCEPTED, confirmationEngine.submit(confirmedIntent).status());
      assertEquals("UNKNOWN", row(store, branch).phase());
      loadedProof.set(true);
      assertCommitted(confirmationEngine.submit(confirmedIntent));
      row = row(store, branch);
      assertEquals("LIVE", row.phase());
      assertEquals(attach.assignment(), row.assignment());
      assertEquals(firstEmployee, row.employee());
      var death = from(Action.DEATH, store, row, 0);
      var live = new AtomicBoolean(false);
      var deathIntent = intent(death);
      var engine = engine(store, death, deathIntent, live);
      assertEquals(Status.NOT_ACCEPTED, engine.submit(deathIntent).status());
      assertEquals("LIVE", row(store, branch).phase());
      live.set(true);
      assertCommitted(engine.submit(deathIntent));
      row = row(store, branch);
      assertEquals("VACANT", row.phase());
      assertCommitted(submit(store, from(Action.CHECKPOINT, store, row, 199), true));
      row = row(store, branch);
      var pause =
          command(
              Action.PAUSE,
              branch,
              view(store, branch).revision(),
              0,
              row.assignment(),
              row.employee(),
              0,
              true,
              List.of(new Progress(0, row.revision(), 1)));
      assertCommitted(submit(store, pause, true));
      assertTrue(view(store, branch).paused());
      assertEquals(200, row(store, branch).elapsed());
      row = row(store, branch);
      assertEquals(
          "STAFF_PROGRESS_STATE",
          submit(store, from(Action.CHECKPOINT, store, row, 1), true).result().code());
      var resume =
          command(
              Action.PAUSE,
              branch,
              view(store, branch).revision(),
              0,
              row.assignment(),
              row.employee(),
              0,
              false,
              List.of());
      assertCommitted(submit(store, resume, true));
      row = row(store, branch);
      assertEquals(200, row.elapsed());
      var early =
          command(
              Action.REPLACE,
              branch,
              view(store, branch).revision(),
              row.revision(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              0,
              false,
              List.of());
      assertEquals("STAFF_REPLACEMENT_NOT_DUE", submit(store, early, true).result().code());
      for (int i = 0; i < 119; i++) {
        row = row(store, branch);
        assertCommitted(submit(store, from(Action.CHECKPOINT, store, row, 200), true));
      }
      row = row(store, branch);
      assertEquals(24000, row.elapsed());
      assertCommitted(submit(store, from(Action.CHECKPOINT, store, row, 200), true));
      assertEquals(24000, row(store, branch).elapsed());
      row = row(store, branch);
      var reuse =
          command(
              Action.REPLACE,
              branch,
              view(store, branch).revision(),
              row.revision(),
              attach.assignment(),
              firstEmployee,
              0,
              false,
              List.of());
      assertEquals("STAFF_EMPLOYEE_REUSED", submit(store, reuse, true).result().code());
      var next =
          command(
              Action.REPLACE,
              branch,
              view(store, branch).revision(),
              row.revision(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              0,
              false,
              List.of());
      newEmployee = next.employee();
      replacement = intent(next);
      replaced = engine(store, next, replacement, new AtomicBoolean(true)).submit(replacement);
      assertCommitted(replaced);
      assertEquals(
          replaced, engine(store, next, replacement, new AtomicBoolean(false)).submit(replacement));
      assertEquals("RESERVED", row(store, branch).phase());
      assertEquals(0, row(store, branch).elapsed());
      assertEquals(baseline, financial(store));
    }
    try (var fixture = open()) {
      var store = fixture.store();
      assertEquals(newEmployee, row(store, branch).employee());
      var history =
          store.transaction(
              tx ->
                  tx.query(
                      "SELECT employee_id,phase FROM banker_employee_history ORDER BY employee_id",
                      List.of(),
                      3));
      assertEquals(2, history.size());
      assertTrue(
          history.stream()
              .anyMatch(
                  r ->
                      r.get("employee_id").equals(firstEmployee.toString())
                          && r.get("phase").equals("DEAD")));
      assertEquals(
          replaced,
          engine(
                  store,
                  BankStaffing.decodeCommand(replacement.request().fields().get("command")),
                  replacement,
                  new AtomicBoolean(false))
              .submit(replacement));
    }
  }

  @Test
  void removedBeforeFirstAttachPersistsCapacityTombstoneWithoutCreatingAccounts() throws Exception {
    UUID branch;
    try (var fixture = open()) {
      var store = fixture.store();
      branch = capture(store, false);
      var before = financial(store);
      var removal =
          command(
              Action.DESK_REMOVED,
              branch,
              1,
              0,
              UUID.randomUUID(),
              UUID.randomUUID(),
              0,
              false,
              List.of());
      assertCommitted(submit(store, removal, true));
      var row = row(store, branch);
      assertEquals("REMOVED", row.deskState());
      assertEquals("UNKNOWN", row.phase());
      var attach =
          command(
              Action.ATTACH,
              branch,
              1,
              0,
              UUID.randomUUID(),
              UUID.randomUUID(),
              0,
              false,
              List.of());
      assertNotEquals(Status.COMMITTED, submit(store, attach, true).status());
      assertEquals(
          "STAFF_BEGIN_STATE",
          submit(store, from(Action.BEGIN, store, row, 0), true).result().code());
      assertEquals(before, financial(store));
    }
    try (var fixture = open()) {
      var store = fixture.store();
      assertEquals("REMOVED", row(store, branch).deskState());
      assertEquals("UNKNOWN", row(store, branch).phase());
      assertEquals(
          1,
          store
              .transaction(tx -> tx.query("SELECT * FROM banker_employee_history", List.of(), 2))
              .size());
    }
  }

  private record Fixture(SqliteStorage store) implements AutoCloseable {
    public void close() throws Exception {
      store.close();
    }
  }

  private Fixture open() throws Exception {
    var store = new SqliteStorage();
    store.initialize(directory.toRealPath());
    return new Fixture(store);
  }

  private static Command command(
      Action action,
      UUID branch,
      long branchRevision,
      long revision,
      UUID assignment,
      UUID employee,
      int delta,
      boolean paused,
      List<Progress> progress) {
    return new Command(
        action,
        branch,
        branchRevision,
        action == Action.PAUSE ? -1 : 0,
        revision,
        assignment,
        employee,
        delta,
        paused,
        progress);
  }

  private static Command from(Action action, SqliteStorage store, Row row, int delta) {
    return command(
        action,
        row.branch(),
        view(store, row.branch()).revision(),
        row.revision(),
        row.assignment(),
        row.employee(),
        delta,
        false,
        List.of());
  }

  private static Intent intent(Command command) {
    var id = UUID.randomUUID();
    return new Intent(
        new OperationId(id),
        new RequestId(id),
        BranchPlans.owner(command.branch()),
        command.action().kind(),
        BankStaffing.request(command),
        null,
        null);
  }

  private static JournalEngine engine(
      SqliteStorage store, Command command, Intent intent, AtomicBoolean live) {
    return new JournalEngine(
        store,
        (a, k) -> true,
        Map.of(
            command.action().kind(),
            BankStaffing.registration(
                store.snapshot().worldId(), i -> live.get() && i.equals(intent), command)));
  }

  private static Outcome submit(SqliteStorage store, Command command, boolean live) {
    var intent = intent(command);
    return engine(store, command, intent, new AtomicBoolean(live)).submit(intent);
  }

  private static void assertCommitted(Outcome outcome) {
    assertEquals(Status.COMMITTED, outcome.status(), outcome.result().code());
  }

  private static Row row(SqliteStorage store, UUID branch) {
    return store
        .transaction(tx -> BankStaffing.find(tx::query, store.snapshot().worldId(), branch, 0))
        .orElseThrow();
  }

  private static Branches.View view(SqliteStorage store, UUID branch) {
    return store
        .transaction(tx -> Branches.find(tx::query, store.snapshot().worldId(), branch))
        .orElseThrow();
  }

  private static UUID capture(SqliteStorage store, boolean active) {
    var world = store.snapshot().worldId();
    var source = new Source("minecraft:overworld", "minecraft:village_plains", 0, 0);
    var village = BranchPlans.id(world, source);
    var branch = BranchPlans.branchId(village, 0);
    var bank = InstitutionCatalog.banks().getFirst().id().key();
    var choice =
        new BranchPlanV1(
            0,
            true,
            Size.COMPACT,
            bank,
            EconomyCurrency.CAD,
            "a7-desk-test",
            new Transform(new Point(8, 63, 18), 0),
            new Bounds(8, 63, 18, 20, 71, 30),
            new Point(13, 64, 18),
            List.of(
                new Anchor(0, new Point(10, 64, 20), BranchPlans.generation(branch, 0), "narrow")),
            List.of(
                new Anchor(0, new Point(16, 64, 25), BranchPlans.generation(branch, 2), "desk")),
            List.of(),
            State.RESERVED,
            "SITE_RESERVED",
            0,
            -1);
    var snapshot =
        new PlanningSnapshotV1(
            EconomyCurrency.CAD,
            1,
            1,
            1,
            "staff-test",
            List.of(new Provider(bank, EconomyCurrency.CAD, true)),
            List.of());
    var plan =
        new VillagePlanV1(
            source,
            "plains",
            5,
            List.of(new Bounds(-20, 60, -20, 20, 70, 20)),
            List.of(new Point(0, 64, 0)),
            snapshot,
            CaptureStatus.CAPTURED,
            List.of(choice),
            "");
    branchOperation(
        store,
        Branches.Action.OBSERVE,
        BranchPlans.owner(village),
        Branches.observeRequest(plan),
        plan);
    if (active) {
      branchOperation(
          store,
          Branches.Action.PROGRESS,
          BranchPlans.owner(branch),
          Branches.request(branch, 1, Map.of("state", "PLACING", "reason", "INITIAL_PLACEMENT")),
          null);
      branchOperation(
          store,
          Branches.Action.PROGRESS,
          BranchPlans.owner(branch),
          Branches.request(
              branch, 2, Map.of("state", "PHYSICAL_COMPLETE", "reason", "CHUNK_RECEIPTS_MATCH")),
          null);
      branchOperation(
          store,
          Branches.Action.ACTIVATE,
          BranchPlans.owner(branch),
          Branches.request(
              branch,
              3,
              Map.of(
                  "slot",
                  "0",
                  "generation",
                  BranchPlans.generation(branch, 0).toString(),
                  "terminal_revision",
                  "0")),
          null);
    }
    return branch;
  }

  private static void branchOperation(
      SqliteStorage store,
      Branches.Action action,
      ActorId actor,
      CanonicalRequest request,
      VillagePlanV1 plan) {
    var id = UUID.randomUUID();
    var intent =
        new Intent(
            new OperationId(id), new RequestId(id), actor, action.kind(), request, null, null);
    assertCommitted(
        new JournalEngine(
                store,
                (a, k) -> true,
                Map.of(
                    action.kind(),
                    Branches.registration(action, store.snapshot().worldId(), i -> true, plan)))
            .submit(intent));
  }

  private static Map<String, List<Map<String, String>>> financial(SqliteStorage store) {
    var result = new TreeMap<String, List<Map<String, String>>>();
    for (String table :
        List.of(
            "account_catalog",
            "institution_parties",
            "institution_financial_accounts",
            "ledger_balances"))
      result.put(
          table, store.transaction(tx -> tx.query("SELECT * FROM " + table, List.of(), 1024)));
    return result;
  }
}
