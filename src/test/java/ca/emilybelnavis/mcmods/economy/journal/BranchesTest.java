package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans.*;
import ca.emilybelnavis.mcmods.economy.village.BranchSelection;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real journal/SQLite witnesses; no generator, physical placement or NPC mocks. */
class BranchesTest {
  @TempDir Path world;
  private static final String LOCAL = InstitutionCatalog.banks().getFirst().id().key();
  private static final Map<String, TerminalPolicy.Denomination> CASH =
      Map.of(
          "economy:cad_bill_10", new TerminalPolicy.Denomination(EconomyCurrency.CAD, true),
          "economy:chf_bill_10", new TerminalPolicy.Denomination(EconomyCurrency.CHF, true),
          "economy:cad_coin_5", new TerminalPolicy.Denomination(EconomyCurrency.CAD, false),
          "economy:chf_coin_5", new TerminalPolicy.Denomination(EconomyCurrency.CHF, false));

  @Test
  void activationIsNativeAtomicReplayableAndRemovalNeverRevivesIt() throws Exception {
    Intent activation;
    Outcome activated;
    UUID branch;
    UUID generation;
    TerminalPolicy.Anchor anchor;
    Map<String, List<Map<String, String>>> financialBefore;
    try (var fixture = new Fixture(open())) {
      var store = fixture.store();
      var worldId = store.snapshot().worldId();
      var plan = reserved(worldId);
      var village = BranchPlans.id(worldId, plan.source());
      branch = BranchPlans.branchId(village, 0);
      generation = plan.branches().getFirst().services().getFirst().generation();
      var position = plan.branches().getFirst().services().getFirst().position();
      anchor =
          new TerminalPolicy.Anchor(
              plan.source().dimension(), position.x(), position.y(), position.z());
      financialBefore = financial(store);
      var observe =
          intent(
              Branches.Action.OBSERVE, BranchPlans.owner(village), Branches.observeRequest(plan));
      var captured =
          engine(store, Branches.Action.OBSERVE, observe, plan, () -> true).submit(observe);
      assertEquals(Status.COMMITTED, captured.status());
      assertEquals(
          captured,
          engine(store, Branches.Action.OBSERVE, observe, plan, () -> false).submit(observe));
      assertEquals(plan.digest(), view(store, branch).plan().digest());
      assertEquals("RESERVED", view(store, branch).state());
      progress(store, branch, 1, "PLACING", "INITIAL_PLACEMENT");
      progress(store, branch, 2, "PHYSICAL_COMPLETE", "CHUNK_RECEIPTS_MATCH");

      var wrongActor =
          intent(
              Branches.Action.ACTIVATE,
              new PlayerId(UUID.randomUUID()),
              hardware(branch, 3, generation, 0));
      assertEquals(
          Status.NOT_ACCEPTED,
          engine(store, Branches.Action.ACTIVATE, wrongActor, null, () -> true)
              .submit(wrongActor)
              .status());
      var wrongGeneration =
          intent(
              Branches.Action.ACTIVATE,
              BranchPlans.owner(branch),
              hardware(branch, 3, UUID.randomUUID(), 0));
      assertEquals(
          "BRANCH_GENERATION",
          engine(store, Branches.Action.ACTIVATE, wrongGeneration, null, () -> true)
              .submit(wrongGeneration)
              .result()
              .code());
      var stale =
          intent(
              Branches.Action.ACTIVATE,
              BranchPlans.owner(branch),
              hardware(branch, 2, generation, 0));
      assertEquals(
          "BRANCH_STALE",
          engine(store, Branches.Action.ACTIVATE, stale, null, () -> true)
              .submit(stale)
              .result()
              .code());
      assertEquals(3, view(store, branch).revision());
      assertTrue(
          store
              .transaction(
                  tx ->
                      TerminalPolicy.find(
                          tx::query, worldId, anchor, BranchPlans.owner(branch), false))
              .isEmpty());

      activation =
          intent(
              Branches.Action.ACTIVATE,
              BranchPlans.owner(branch),
              hardware(branch, 3, generation, 0));
      var live = new AtomicBoolean(false);
      var activationEngine = engine(store, Branches.Action.ACTIVATE, activation, null, live::get);
      assertEquals(
          Status.NOT_ACCEPTED,
          activationEngine.submit(activation).status(),
          "An expired detached lease cannot authorize hardware");
      live.set(true);
      activated = activationEngine.submit(activation);
      assertEquals(Status.COMMITTED, activated.status());
      var terminal = terminal(store, anchor, BranchPlans.owner(branch));
      assertEquals(BranchPlans.owner(branch), terminal.owner());
      assertInstanceOf(SystemActorId.class, terminal.owner());
      assertEquals(TerminalPolicy.Kind.KIOSK, terminal.kind());
      assertEquals(2, terminal.revision());
      assertTrue(terminal.active());
      assertEquals(generation, terminal.generation());
      assertEquals(LOCAL, terminal.binding().institution().key());
      assertEquals(EconomyCurrency.CAD, terminal.binding().nativeCurrency());
      assertEquals(TerminalPolicy.Settings.disabled(), terminal.settings());
      assertEquals("ACTIVE", view(store, branch).state());
      assertEquals(4, view(store, branch).revision());
      live.set(false);
      assertEquals(
          activated,
          activationEngine.submit(activation),
          "Original replay resolves without executing an expired effect again");
      assertEquals(financialBefore, financial(store));
    }
    // Reopen runs production schema/projection validation over the persisted plan and native
    // binding.
    try (var fixture = new Fixture(open())) {
      var store = fixture.store();
      assertEquals("ACTIVE", view(store, branch).state());
      assertEquals(
          activated,
          engine(store, Branches.Action.ACTIVATE, activation, null, () -> false)
              .submit(activation));
      var remove =
          intent(
              Branches.Action.REMOVE,
              BranchPlans.owner(branch),
              hardware(branch, 4, generation, 2));
      var removed = engine(store, Branches.Action.REMOVE, remove, null, () -> true).submit(remove);
      assertEquals(Status.COMMITTED, removed.status());
      assertEquals("REMOVED", view(store, branch).state());
      assertEquals(5, view(store, branch).revision());
      assertEquals("REMOVED", view(store, branch).anchors().getFirst().get("state"));
      assertFalse(terminal(store, anchor, BranchPlans.owner(branch)).active());
      assertEquals(3, terminal(store, anchor, BranchPlans.owner(branch)).revision());
      assertEquals(
          activated,
          engine(store, Branches.Action.ACTIVATE, activation, null, () -> true).submit(activation));
      assertEquals(
          "REMOVED",
          view(store, branch).state(),
          "Replay is an outcome lookup, never a reconstruction instruction");
      var revive =
          intent(
              Branches.Action.ACTIVATE,
              BranchPlans.owner(branch),
              hardware(branch, 5, generation, 3));
      assertEquals(
          "BRANCH_ACTIVATION_STATE",
          engine(store, Branches.Action.ACTIVATE, revive, null, () -> true)
              .submit(revive)
              .result()
              .code());
      assertFalse(terminal(store, anchor, BranchPlans.owner(branch)).active());
      assertEquals(financialBefore, financial(store));
    }
    try (var fixture = new Fixture(open())) {
      var store = fixture.store();
      assertEquals("REMOVED", view(store, branch).state());
      assertFalse(terminal(store, anchor, BranchPlans.owner(branch)).active());
      assertEquals(financialBefore, financial(store));
    }
  }

  @Test
  void providerAndTerrainPendingRemainDistinctFromPersistedOptionalOmission() throws Exception {
    UUID terrainBranch;
    UUID optionalBranch;
    UUID providerBranch;
    String capturedDigest;
    try (var fixture = new Fixture(open())) {
      var store = fixture.store();
      var w = store.snapshot().worldId();
      var snapshot = snapshot(List.of(new Provider(LOCAL, EconomyCurrency.CAD, true)));
      var terrain =
          pending(
              0,
              true,
              Size.LARGER,
              LOCAL,
              EconomyCurrency.CAD,
              State.SITE_PENDING,
              "TERRAIN_EXHAUSTED",
              -1);
      var omitted =
          pending(1, false, Size.COMPACT, null, null, State.OMITTED, "NO_DISTINCT_PROVIDER", 0);
      var plan =
          plan(
              new Source("minecraft:overworld", "minecraft:village_plains", 2, 2),
              10,
              snapshot,
              List.of(terrain, omitted));
      capturedDigest = plan.digest();
      observe(store, plan);
      var village = BranchPlans.id(w, plan.source());
      terrainBranch = BranchPlans.branchId(village, 0);
      optionalBranch = BranchPlans.branchId(village, 1);
      assertEquals("SITE_PENDING", view(store, terrainBranch).state());
      assertEquals("OMITTED", view(store, optionalBranch).state());
      assertEquals("NO_DISTINCT_PROVIDER", view(store, optionalBranch).reason());
      var retry =
          intent(
              Branches.Action.RETRY,
              new SystemActorId(UUID.randomUUID()),
              Branches.request(terrainBranch, 1, Map.of()));
      assertEquals(
          Status.COMMITTED,
          engine(store, Branches.Action.RETRY, retry, null, () -> true).submit(retry).status());
      assertEquals("SITE_PENDING", view(store, terrainBranch).state());
      assertEquals("PLACEMENT_ADAPTER_UNAVAILABLE", view(store, terrainBranch).reason());
      assertEquals(
          capturedDigest,
          view(store, terrainBranch).plan().digest(),
          "Retry cannot reroll captured choices");
      var invalidRetry =
          intent(
              Branches.Action.RETRY,
              new SystemActorId(UUID.randomUUID()),
              Branches.request(optionalBranch, 1, Map.of()));
      assertEquals(
          "BRANCH_RETRY_STATE",
          engine(store, Branches.Action.RETRY, invalidRetry, null, () -> true)
              .submit(invalidRetry)
              .result()
              .code());

      var noProvider =
          plan(
              new Source("minecraft:overworld", "minecraft:village_plains", 3, 3),
              5,
              snapshot(List.of()),
              List.of(
                  pending(
                      0,
                      true,
                      Size.COMPACT,
                      null,
                      null,
                      State.PROVIDER_PENDING,
                      "NO_LOCAL_PROVIDER",
                      -1)));
      observe(store, noProvider);
      providerBranch = BranchPlans.branchId(BranchPlans.id(w, noProvider.source()), 0);
      assertEquals("PROVIDER_PENDING", view(store, providerBranch).state());
      var pause =
          intent(
              Branches.Action.PAUSE,
              new SystemActorId(UUID.randomUUID()),
              Branches.request(terrainBranch, 2, Map.of("paused", "true")));
      assertEquals(
          Status.COMMITTED,
          engine(store, Branches.Action.PAUSE, pause, null, () -> true).submit(pause).status());
      assertTrue(view(store, terrainBranch).paused());
      assertTrue(
          store
              .transaction(tx -> tx.query("SELECT * FROM terminal_settings", List.of(), 1))
              .isEmpty());
    }
    try (var fixture = new Fixture(open())) {
      var store = fixture.store();
      assertEquals("SITE_PENDING", view(store, terrainBranch).state());
      assertTrue(view(store, terrainBranch).paused());
      assertEquals(capturedDigest, view(store, terrainBranch).plan().digest());
      assertEquals("OMITTED", view(store, optionalBranch).state());
      assertEquals("PROVIDER_PENDING", view(store, providerBranch).state());
    }
  }

  @Test
  void standardBranchesCommissionBothAtmsForTheirOwnBankCurrency() throws Exception {
    var source = new Source("minecraft:overworld", "minecraft:village_plains", 0, 0);
    var local = InstitutionCatalog.banks().getFirst();
    var foreign =
        InstitutionCatalog.banks().stream()
            .filter(b -> b.baseCurrency() == EconomyCurrency.CHF)
            .findFirst()
            .orElseThrow();
    UUID village;
    try (var fixture = new Fixture(open())) {
      var store = fixture.store();
      var worldId = store.snapshot().worldId();
      village = BranchPlans.id(worldId, source);
      var captured =
          plan(
              source,
              10,
              snapshot(
                  List.of(
                      new Provider(local.id().key(), local.baseCurrency(), true),
                      new Provider(foreign.id().key(), foreign.baseCurrency(), true))),
              List.of(
                  standardBranch(village, 0, local),
                  standardBranch(village, 1, foreign),
                  pending(2, false, Size.STANDARD, null, null, State.OMITTED, "NOT_SELECTED", 99)));
      observe(store, captured);
      for (var choice : captured.branches().subList(0, 2)) {
        var branch = BranchPlans.branchId(village, choice.slot());
        assertEquals(3, view(store, branch).anchors().size());
        progress(store, branch, 1, "PLACING", "INITIAL_PLACEMENT");
        progress(store, branch, 2, "PHYSICAL_COMPLETE", "CHUNK_RECEIPTS_MATCH");
        for (var service : choice.services()) {
          var activation =
              intent(
                  Branches.Action.ACTIVATE,
                  BranchPlans.owner(branch),
                  Branches.request(
                      branch,
                      3 + service.slot(),
                      Map.of(
                          "slot",
                          Integer.toString(service.slot()),
                          "generation",
                          service.generation().toString(),
                          "terminal_revision",
                          "0")));
          assertEquals(
              Status.COMMITTED,
              engine(store, Branches.Action.ACTIVATE, activation, null, () -> true)
                  .submit(activation)
                  .status());
        }
        assertEquals("ACTIVE", view(store, branch).state());
      }
    }
    try (var fixture = new Fixture(open())) {
      var store = fixture.store();
      for (int slot = 0; slot < 2; slot++) {
        var branch = BranchPlans.branchId(village, slot);
        var choice = view(store, branch).choice();
        for (var service : choice.services()) {
          var point = service.position();
          var at = new TerminalPolicy.Anchor(source.dimension(), point.x(), point.y(), point.z());
          var terminal = terminal(store, at, BranchPlans.owner(branch));
          assertEquals(choice.institution(), terminal.binding().institution().key());
          assertEquals(choice.nativeCurrency(), terminal.binding().nativeCurrency());
          if (service.slot() == 0) {
            assertEquals(TerminalPolicy.Kind.KIOSK, terminal.kind());
            assertEquals(TerminalPolicy.Settings.disabled(), terminal.settings());
          } else {
            assertEquals(TerminalPolicy.Kind.ATM, terminal.kind());
            assertEquals(TerminalPolicy.Mode.BOTH, terminal.settings().mode());
            assertEquals(Set.of(choice.nativeCurrency()), terminal.settings().currencies());
            assertEquals(
                Set.of(
                    choice.nativeCurrency() == EconomyCurrency.CAD
                        ? "economy:cad_bill_10"
                        : "economy:chf_bill_10"),
                terminal.settings().denominations());
          }
        }
      }
    }
  }

  private static BranchPlanV1 standardBranch(
      UUID village, int slot, InstitutionCatalog.Entry bank) {
    var branch = BranchPlans.branchId(village, slot);
    int x = 24 + 18 * slot;
    return new BranchPlanV1(
        slot,
        true,
        Size.STANDARD,
        bank.id().key(),
        bank.baseCurrency(),
        "economy:banks/" + bank.id().key().substring(bank.id().key().lastIndexOf('/') + 1),
        new Transform(new Point(x, 63, 24), 0),
        new Bounds(x, 57, 24, x + 16, 78, 40),
        new Point(x + 8, 64, 24),
        List.of(
            new Anchor(0, new Point(x + 2, 64, 27), BranchPlans.generation(branch, 0), "wide"),
            new Anchor(1, new Point(x + 4, 64, 27), BranchPlans.generation(branch, 1), "atm"),
            new Anchor(2, new Point(x + 6, 64, 27), BranchPlans.generation(branch, 2), "atm")),
        List.of(new Anchor(0, new Point(x + 8, 64, 37), BranchPlans.generation(branch, 4), "desk")),
        List.of(),
        State.RESERVED,
        "SITE_RESERVED",
        1,
        -1);
  }

  @Test
  void firstReadyCaptureAndRequiredProviderFillPreserveOriginalChoicesAndConflictSafety()
      throws Exception {
    UUID filledBranch;
    UUID omittedBranch;
    String missingProviderDigest;
    try (var fixture = new Fixture(open())) {
      var store = fixture.store();
      var worldId = store.snapshot().worldId();
      var source = new Source("minecraft:overworld", "minecraft:village_plains", 4, 4);
      var pending =
          new VillagePlanV1(
              source,
              "plains",
              5,
              List.of(new Bounds(-20, 60, -20, 20, 70, 20)),
              List.of(new Point(0, 64, 0)),
              null,
              CaptureStatus.CAPTURE_PENDING,
              List.of(),
              "AWAITING_FIRST_READY_SNAPSHOT");
      observe(store, pending);
      var ready = snapshot(List.of(new Provider(LOCAL, EconomyCurrency.CAD, true)));
      var captured = BranchSelection.capture(pending, ready, 42);
      observe(store, captured);
      var village = BranchPlans.id(worldId, source);
      var capturedBranch = BranchPlans.branchId(village, 0);
      assertEquals(captured.digest(), view(store, capturedBranch).plan().digest());
      assertEquals(
          EconomyCurrency.CAD, view(store, capturedBranch).plan().snapshot().denomination());
      assertEquals(
          "2",
          store
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT revision FROM village_plans WHERE village_id=?",
                          List.of(village.toString()),
                          1))
              .getFirst()
              .get("revision"));
      observe(store, captured);
      var euro =
          InstitutionCatalog.banks().stream()
              .filter(bank -> bank.baseCurrency() == EconomyCurrency.EUR && bank.tier1())
              .findFirst()
              .orElseThrow();
      var changedSnapshot =
          new PlanningSnapshotV1(
              EconomyCurrency.EUR,
              2,
              1,
              1,
              "pinned-resource-v1",
              List.of(new Provider(euro.id().key(), EconomyCurrency.EUR, true)),
              List.of());
      var recaptured = BranchSelection.capture(pending, changedSnapshot, 42);
      var recapture =
          intent(
              Branches.Action.OBSERVE,
              BranchPlans.owner(village),
              Branches.observeRequest(recaptured));
      assertEquals(
          "BRANCH_PLAN_CONFLICT",
          engine(store, Branches.Action.OBSERVE, recapture, recaptured, () -> true)
              .submit(recapture)
              .result()
              .code());
      assertEquals(captured.digest(), view(store, capturedBranch).plan().digest());

      var missing =
          plan(
              new Source("minecraft:overworld", "minecraft:village_plains", 5, 5),
              10,
              snapshot(List.of()),
              List.of(
                  pending(
                      0,
                      true,
                      Size.LARGER,
                      null,
                      null,
                      State.PROVIDER_PENDING,
                      "NO_LOCAL_PROVIDER",
                      -1),
                  pending(1, false, Size.COMPACT, null, null, State.OMITTED, "NOT_SELECTED", 99)));
      missingProviderDigest = missing.digest();
      observe(store, missing);
      var missingVillage = BranchPlans.id(worldId, missing.source());
      filledBranch = BranchPlans.branchId(missingVillage, 0);
      omittedBranch = BranchPlans.branchId(missingVillage, 1);
      var fill =
          intent(
              Branches.Action.FILL,
              new SystemActorId(UUID.randomUUID()),
              Branches.request(
                  filledBranch,
                  1,
                  Map.of("snapshot", BranchSelection.encodeSnapshot(ready), "institution", LOCAL)));
      var filled = engine(store, Branches.Action.FILL, fill, null, () -> true).submit(fill);
      assertEquals(Status.COMMITTED, filled.status());
      assertEquals("SITE_PENDING", view(store, filledBranch).state());
      assertEquals(LOCAL, view(store, filledBranch).choice().institution());
      assertNull(view(store, filledBranch).plan().branches().getFirst().institution());
      assertTrue(view(store, filledBranch).plan().snapshot().providers().isEmpty());
      assertEquals(missingProviderDigest, view(store, filledBranch).plan().digest());
      assertEquals("OMITTED", view(store, omittedBranch).state());
      assertEquals("NOT_SELECTED", view(store, omittedBranch).reason());
      assertEquals(1, view(store, omittedBranch).revision());

      var physical = reserved(worldId);
      observe(store, physical);
      var physicalBranch = BranchPlans.branchId(BranchPlans.id(worldId, physical.source()), 0);
      progress(store, physicalBranch, 1, "PLACING", "INITIAL_PLACEMENT");
      progress(store, physicalBranch, 2, "PHYSICAL_COMPLETE", "CHUNK_RECEIPTS_MATCH");
      var service = physical.branches().getFirst().services().getFirst();
      var activate =
          intent(
              Branches.Action.ACTIVATE,
              BranchPlans.owner(physicalBranch),
              hardware(physicalBranch, 3, service.generation(), 0));
      assertEquals(
          Status.COMMITTED,
          engine(store, Branches.Action.ACTIVATE, activate, null, () -> true)
              .submit(activate)
              .status());
      var at =
          new TerminalPolicy.Anchor(
              physical.source().dimension(),
              service.position().x(),
              service.position().y(),
              service.position().z());
      assertTrue(
          store.<Boolean>transaction(
              tx -> TerminalPolicy.matches(tx::query, worldId, at, service.generation(), 2)));
      progress(store, physicalBranch, 4, "CONFLICT", "PHYSICAL_PROVENANCE_CONFLICT");
      assertFalse(
          store.<Boolean>transaction(
              tx -> TerminalPolicy.matches(tx::query, worldId, at, service.generation(), 2)),
          "An intact anchor cannot admit banking while its branch is conflicted");
      var remove =
          intent(
              Branches.Action.REMOVE,
              BranchPlans.owner(physicalBranch),
              hardware(physicalBranch, 5, service.generation(), 2));
      assertEquals(
          Status.COMMITTED,
          engine(store, Branches.Action.REMOVE, remove, null, () -> true).submit(remove).status());
      assertEquals(
          "CONFLICT",
          view(store, physicalBranch).state(),
          "Removal cannot erase an unresolved placement conflict");
      assertFalse(terminal(store, at, BranchPlans.owner(physicalBranch)).active());
    }
    try (var fixture = new Fixture(open())) {
      var store = fixture.store();
      assertEquals("SITE_PENDING", view(store, filledBranch).state());
      assertEquals(LOCAL, view(store, filledBranch).choice().institution());
      assertTrue(view(store, filledBranch).plan().snapshot().providers().isEmpty());
      assertNull(view(store, filledBranch).plan().branches().getFirst().institution());
      assertEquals(missingProviderDigest, view(store, filledBranch).plan().digest());
      assertEquals("OMITTED", view(store, omittedBranch).state());
      assertEquals(1, view(store, omittedBranch).revision());
    }
  }

  private VillagePlanV1 reserved(WorldId worldId) {
    var source = new Source("minecraft:overworld", "minecraft:village_plains", 0, 0);
    var branch = BranchPlans.branchId(BranchPlans.id(worldId, source), 0);
    var choice =
        new BranchPlanV1(
            0,
            true,
            Size.COMPACT,
            LOCAL,
            EconomyCurrency.CAD,
            "a7-compact-v1",
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
    return plan(
        source,
        5,
        snapshot(List.of(new Provider(LOCAL, EconomyCurrency.CAD, true))),
        List.of(choice));
  }

  private static VillagePlanV1 plan(
      Source source, int count, PlanningSnapshotV1 snapshot, List<BranchPlanV1> choices) {
    return new VillagePlanV1(
        source,
        "plains",
        count,
        List.of(new Bounds(-20, 60, -20, 20, 70, 20)),
        List.of(new Point(0, 64, 0)),
        snapshot,
        CaptureStatus.CAPTURED,
        choices,
        "");
  }

  private static PlanningSnapshotV1 snapshot(List<Provider> providers) {
    return new PlanningSnapshotV1(
        EconomyCurrency.CAD, 1, 1, 1, "pinned-resource-v1", providers, List.of());
  }

  private static BranchPlanV1 pending(
      int slot,
      boolean required,
      Size size,
      String bank,
      EconomyCurrency currency,
      State state,
      String reason,
      int roll) {
    return new BranchPlanV1(
        slot, required, size, bank, currency, null, null, null, null, List.of(), List.of(),
        List.of(), state, reason, 0, roll);
  }

  private void observe(SqliteStorage store, VillagePlanV1 plan) {
    var village = BranchPlans.id(store.snapshot().worldId(), plan.source());
    var intent =
        intent(Branches.Action.OBSERVE, BranchPlans.owner(village), Branches.observeRequest(plan));
    assertEquals(
        Status.COMMITTED,
        engine(store, Branches.Action.OBSERVE, intent, plan, () -> true).submit(intent).status());
  }

  private void progress(
      SqliteStorage store, UUID branch, long revision, String state, String reason) {
    var intent =
        intent(
            Branches.Action.PROGRESS,
            BranchPlans.owner(branch),
            Branches.request(branch, revision, Map.of("state", state, "reason", reason)));
    assertEquals(
        Status.COMMITTED,
        engine(store, Branches.Action.PROGRESS, intent, null, () -> true).submit(intent).status());
  }

  private static CanonicalRequest hardware(
      UUID branch, long revision, UUID generation, long terminalRevision) {
    return Branches.request(
        branch,
        revision,
        Map.of(
            "slot",
            "0",
            "generation",
            generation.toString(),
            "terminal_revision",
            Long.toString(terminalRevision)));
  }

  private static Intent intent(Branches.Action action, ActorId actor, CanonicalRequest request) {
    return new Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        actor,
        action.kind(),
        request,
        null,
        null);
  }

  private static JournalEngine engine(
      SqliteStorage store,
      Branches.Action action,
      Intent expected,
      VillagePlanV1 plan,
      java.util.function.BooleanSupplier live) {
    return new JournalEngine(
        store,
        (actor, kind) -> true,
        Map.of(
            action.kind(),
            Branches.registration(
                action,
                store.snapshot().worldId(),
                candidate -> candidate.equals(expected) && live.getAsBoolean(),
                plan,
                CASH)));
  }

  private static Branches.View view(SqliteStorage store, UUID branch) {
    return store
        .transaction(tx -> Branches.find(tx::query, store.snapshot().worldId(), branch))
        .orElseThrow();
  }

  private static TerminalPolicy.Snapshot terminal(
      SqliteStorage store, TerminalPolicy.Anchor anchor, ActorId actor) {
    return store
        .transaction(
            tx -> TerminalPolicy.find(tx::query, store.snapshot().worldId(), anchor, actor, false))
        .orElseThrow();
  }

  private static Map<String, List<Map<String, String>>> financial(SqliteStorage store) {
    var result = new TreeMap<String, List<Map<String, String>>>();
    for (var table :
        List.of(
            "account_catalog",
            "institution_parties",
            "institution_financial_accounts",
            "institution_capital_manifest",
            "institution_capital_allocations",
            "ledger_balances"))
      result.put(
          table, store.transaction(tx -> tx.query("SELECT * FROM " + table, List.of(), 1024)));
    return result;
  }

  private record Fixture(SqliteStorage store) implements AutoCloseable {
    @Override
    public void close() throws Exception {
      store.close();
    }
  }

  private SqliteStorage open() throws Exception {
    var store = new SqliteStorage();
    store.initialize(world.toRealPath());
    return store;
  }
}
