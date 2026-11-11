package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy.*;
import static ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy.Action.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerminalPolicyTest {
  @TempDir Path world;
  private final PlayerId owner = new PlayerId(UUID.randomUUID());
  private final PlayerId operator = new PlayerId(UUID.randomUUID());
  private final PlayerId stranger = new PlayerId(UUID.randomUUID());
  private final Anchor anchor = new Anchor("minecraft:overworld", 10, 64, 20);
  private final Map<String, Denomination> catalog =
      Map.of(
          "economy:cad_bill_5", new Denomination(EconomyCurrency.CAD, true),
          "economy:cad_coin_25", new Denomination(EconomyCurrency.CAD, false),
          "economy:eur_bill_5", new Denomination(EconomyCurrency.EUR, true));
  private boolean live = true;

  @Test
  void boundKioskEnablesNativeCashServicesWithoutChangingSavedSettings() {
    var worldId = new WorldId(UUID.randomUUID());
    var generation = UUID.randomUUID();
    var binding =
        new TerminalBanking.Binding(
            worldId,
            anchor,
            generation,
            ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.banks().getFirst().id(),
            TerminalBanking.CATALOG_REVISION,
            EconomyCurrency.CAD,
            TerminalBanking.MigrationState.BOUND,
            Settings.disabled());
    var saved =
        new Snapshot(
            worldId,
            anchor,
            generation,
            owner,
            TerminalPolicy.Kind.KIOSK,
            1,
            true,
            Settings.disabled(),
            binding);
    var live = effective(saved, catalog);
    assertEquals(Mode.BOTH, live.settings().mode());
    assertEquals(Set.of(EconomyCurrency.CAD), live.settings().currencies());
    assertEquals(
        Set.of("economy:cad_bill_5", "economy:cad_coin_25"), live.settings().denominations());
    assertEquals(Settings.disabled(), saved.settings());
    var unbound =
        new Snapshot(
            worldId,
            anchor,
            UUID.randomUUID(),
            owner,
            TerminalPolicy.Kind.KIOSK,
            1,
            true,
            Settings.disabled());
    assertEquals(Settings.disabled(), effective(unbound, catalog).settings());
  }

  @Test
  void ownerOperatorLiveAuthorityAndOptimisticRevisionAreRequired() throws Exception {
    var store = open();
    try {
      var generation = UUID.randomUUID();
      var place =
          intent(
              PLACE,
              owner,
              placeRequest(anchor, generation, owner, TerminalPolicy.Kind.ATM, null, 0));
      assertEquals(Status.COMMITTED, engine(store, PLACE).submit(place).status());
      var initial = snapshot(store, owner);
      assertEquals(Settings.disabled(), initial.settings());
      bind(store, anchor, generation, owner, 1);
      var settings =
          new Settings(Mode.BOTH, Set.of(EconomyCurrency.CAD), Set.of("economy:cad_bill_5"));
      assertEquals(
          Status.NOT_ACCEPTED,
          submit(store, CONFIGURE, stranger, configureRequest(anchor, generation, 2, settings))
              .status());
      assertTrue(
          store
              .transaction(
                  tx ->
                      TerminalPolicy.find(
                          tx::query, store.snapshot().worldId(), anchor, stranger, false))
              .isEmpty());
      live = false;
      var edit = intent(CONFIGURE, owner, configureRequest(anchor, generation, 2, settings));
      assertEquals(Status.NOT_ACCEPTED, engine(store, CONFIGURE).submit(edit).status());
      live = true;
      var configured = engine(store, CONFIGURE).submit(edit);
      assertEquals(Status.COMMITTED, configured.status());
      assertEquals(configured, engine(store, CONFIGURE).submit(edit));
      var conflict =
          new Intent(
              edit.operationId(),
              new RequestId(UUID.randomUUID()),
              owner,
              edit.kind(),
              configureRequest(anchor, generation, 2, Settings.disabled()),
              null,
              null);
      assertEquals("OPERATION_CONFLICT", engine(store, CONFIGURE).submit(conflict).result().code());
      assertEquals(
          Status.DURABLY_REJECTED,
          submit(store, CONFIGURE, owner, configureRequest(anchor, generation, 2, settings))
              .status());
      assertEquals(
          Status.COMMITTED,
          submit(
                  store,
                  CONFIGURE,
                  operator,
                  configureRequest(anchor, generation, 3, Settings.disabled()))
              .status());
      assertEquals(4L, snapshot(store, owner).revision());
    } finally {
      store.close();
    }
  }

  @Test
  void replacementRemovalAndReloadRejectStaleOrReusedGenerations() throws Exception {
    var store = open();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    Intent placement;
    try {
      placement =
          intent(
              PLACE,
              owner,
              placeRequest(anchor, first, owner, TerminalPolicy.Kind.TELLER, null, 0));
      assertEquals(Status.COMMITTED, engine(store, PLACE).submit(placement).status());
      assertEquals(
          Status.COMMITTED, submit(store, REMOVE, owner, removeRequest(anchor, first, 1)).status());
      assertEquals(
          Status.DURABLY_REJECTED,
          submit(store, CONFIGURE, owner, configureRequest(anchor, first, 2, Settings.disabled()))
              .status());
      assertEquals(
          Status.COMMITTED,
          submit(
                  store,
                  PLACE,
                  stranger,
                  placeRequest(anchor, second, stranger, TerminalPolicy.Kind.ATM, first, 2))
              .status());
      assertFalse(
          store.<Boolean>transaction(
              tx ->
                  TerminalPolicy.matches(tx::query, store.snapshot().worldId(), anchor, first, 1)));
      assertEquals(
          Status.DURABLY_REJECTED,
          submit(store, REMOVE, stranger, removeRequest(anchor, first, 1)).status());
      assertEquals(Status.COMMITTED, engine(store, PLACE).submit(placement).status());
      assertEquals(second, snapshot(store, stranger).generation());
    } finally {
      store.close();
    }
    var reopened = open();
    try {
      assertEquals(second, snapshot(reopened, stranger).generation());
      assertTrue(
          reopened.<Boolean>transaction(
              tx ->
                  TerminalPolicy.matches(
                      tx::query, reopened.snapshot().worldId(), anchor, second, 3)));
      assertEquals(
          Status.DURABLY_REJECTED,
          submit(
                  reopened,
                  PLACE,
                  stranger,
                  placeRequest(anchor, first, stranger, TerminalPolicy.Kind.TELLER, second, 3))
              .status());
      assertEquals(second, snapshot(reopened, stranger).generation());
    } finally {
      reopened.close();
    }
  }

  @Test
  void whitelistAndModeValidationFollowTerminalCapabilities() throws Exception {
    var store = open();
    try {
      var generation = UUID.randomUUID();
      submit(
          store,
          PLACE,
          owner,
          placeRequest(anchor, generation, owner, TerminalPolicy.Kind.ATM, null, 0));
      bind(store, anchor, generation, owner, 1);
      for (var invalid : Set.of("economy:cad_coin_25", "economy:eur_bill_5", "economy:unknown")) {
        assertEquals(
            Status.DURABLY_REJECTED,
            submit(
                    store,
                    CONFIGURE,
                    owner,
                    configureRequest(
                        anchor,
                        generation,
                        2,
                        new Settings(Mode.BOTH, Set.of(EconomyCurrency.CAD), Set.of(invalid))))
                .status());
      }
      assertEquals(2L, snapshot(store, owner).revision());
      var teller = UUID.randomUUID();
      assertEquals(
          Status.COMMITTED,
          submit(
                  store,
                  PLACE,
                  owner,
                  placeRequest(anchor, teller, owner, TerminalPolicy.Kind.TELLER, generation, 2))
              .status());
      bind(store, anchor, teller, owner, 3);
      var coins =
          new Settings(Mode.BOTH, Set.of(EconomyCurrency.CAD), Set.of("economy:cad_coin_25"));
      assertEquals(
          Status.COMMITTED,
          submit(store, CONFIGURE, owner, configureRequest(anchor, teller, 4, coins)).status());
      assertEquals(coins, effective(snapshot(store, owner), catalog).settings());
      var otherDimension = new Anchor("minecraft:the_nether", anchor.x(), anchor.y(), anchor.z());
      assertTrue(
          store
              .transaction(
                  tx ->
                      TerminalPolicy.find(
                          tx::query, store.snapshot().worldId(), otherDimension, owner, false))
              .isEmpty());
      var register = UUID.randomUUID();
      submit(
          store,
          PLACE,
          owner,
          placeRequest(otherDimension, register, owner, TerminalPolicy.Kind.REGISTER, null, 0));
      assertEquals(
          Status.DURABLY_REJECTED,
          submit(
                  store,
                  CONFIGURE,
                  owner,
                  configureRequest(
                      otherDimension,
                      register,
                      1,
                      new Settings(
                          Mode.BOTH, Set.of(EconomyCurrency.CAD), Set.of("economy:cad_bill_5"))))
              .status());
      var oversized =
          java.util.stream.IntStream.range(0, 132)
              .mapToObj(i -> "economy:denomination_" + i)
              .collect(java.util.stream.Collectors.toSet());
      assertThrows(
          IllegalArgumentException.class,
          () -> new Settings(Mode.BOTH, Set.of(EconomyCurrency.CAD), oversized));
      assertThrows(IllegalArgumentException.class, () -> new Anchor("invalid", 0, 64, 0));
      assertThrows(
          IllegalArgumentException.class,
          () -> new Anchor("minecraft:overworld", 30_000_001, 64, 0));
    } finally {
      store.close();
    }
  }

  @Test
  void defaultsAndLegacyTellerServicesAreCompleteWithoutRewritingStoredSettings() throws Exception {
    var store = open();
    try {
      var teller = UUID.randomUUID();
      assertEquals(
          Status.COMMITTED,
          submit(
                  store,
                  PLACE,
                  owner,
                  placeRequest(anchor, teller, owner, TerminalPolicy.Kind.TELLER, null, 0))
              .status());
      var full = defaultSettings(TerminalPolicy.Kind.TELLER, catalog);
      assertEquals(Mode.BOTH, full.mode());
      assertEquals(EnumSet.allOf(EconomyCurrency.class), full.currencies());
      assertEquals(catalog.keySet(), full.denominations());
      assertEquals(Settings.disabled(), snapshot(store, owner).settings());
      assertEquals(
          Status.DURABLY_REJECTED,
          submit(store, CONFIGURE, owner, configureRequest(anchor, teller, 1, Settings.disabled()))
              .status());
      assertEquals(
          Status.DURABLY_REJECTED,
          submit(
                  store,
                  CONFIGURE,
                  operator,
                  configureRequest(
                      anchor,
                      teller,
                      1,
                      new Settings(
                          Mode.DEPOSIT,
                          Set.of(EconomyCurrency.CAD),
                          Set.of("economy:cad_coin_25"))))
              .status());
      // Simulate a previously saved teller, retaining its original revision and historical
      // projection.
      store.transaction(
          tx ->
              tx.execute(
                  "UPDATE terminal_settings SET mode='DISABLED',currencies='',denominations=''",
                  List.of()));
      var historical = snapshot(store, owner);
      var usable = effective(historical, catalog);
      assertEquals(full, usable.settings());
      assertEquals(historical.revision(), usable.revision());
      assertEquals(historical.generation(), usable.generation());
      assertEquals(Mode.DISABLED, snapshot(store, owner).settings().mode());
      assertEquals(
          Status.COMMITTED,
          submit(store, REMOVE, owner, removeRequest(anchor, teller, 1)).status());
      assertFalse(effective(snapshot(store, owner), catalog).active());
      assertEquals(Settings.disabled(), defaultSettings(TerminalPolicy.Kind.REGISTER, catalog));
    } finally {
      store.close();
    }
  }

  @Test
  void twoAtmSettingsRemainIndependentAndPersistAcrossReload() throws Exception {
    var store = open();
    var other = new Anchor(anchor.dimension(), anchor.x() + 4, anchor.y(), anchor.z());
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();
    var restricted =
        new Settings(Mode.WITHDRAW, Set.of(EconomyCurrency.CAD), Set.of("economy:cad_bill_5"));
    try {
      assertEquals(
          Status.COMMITTED,
          submit(
                  store,
                  PLACE,
                  owner,
                  placeRequest(anchor, first, owner, TerminalPolicy.Kind.ATM, null, 0))
              .status());
      assertEquals(
          Status.COMMITTED,
          submit(
                  store,
                  PLACE,
                  stranger,
                  placeRequest(other, second, stranger, TerminalPolicy.Kind.ATM, null, 0))
              .status());
      bind(store, anchor, first, owner, 1);
      var defaults = defaultSettings(TerminalPolicy.Kind.ATM, catalog);
      assertEquals(Mode.BOTH, defaults.mode());
      assertEquals(EnumSet.allOf(EconomyCurrency.class), defaults.currencies());
      assertEquals(Set.of("economy:cad_bill_5", "economy:eur_bill_5"), defaults.denominations());
      assertEquals(
          Status.NOT_ACCEPTED,
          submit(store, CONFIGURE, stranger, configureRequest(anchor, first, 2, restricted))
              .status());
      assertEquals(
          Status.COMMITTED,
          submit(store, CONFIGURE, owner, configureRequest(anchor, first, 2, restricted)).status());
      assertEquals(
          Settings.disabled(),
          store
              .transaction(
                  tx -> find(tx::query, store.snapshot().worldId(), other, stranger, false))
              .orElseThrow()
              .settings());
    } finally {
      store.close();
    }
    var reopened = open();
    try {
      assertEquals(restricted, effective(snapshot(reopened, owner), catalog).settings());
      assertEquals(
          Settings.disabled(),
          reopened
              .transaction(
                  tx -> find(tx::query, reopened.snapshot().worldId(), other, stranger, false))
              .orElseThrow()
              .settings());
      assertEquals(
          Status.COMMITTED,
          submit(reopened, REMOVE, owner, removeRequest(anchor, first, 3)).status());
      assertEquals(
          Status.COMMITTED,
          submit(
                  reopened,
                  PLACE,
                  owner,
                  placeRequest(anchor, UUID.randomUUID(), owner, TerminalPolicy.Kind.ATM, first, 4))
              .status());
      assertEquals(Settings.disabled(), snapshot(reopened, owner).settings());
    } finally {
      reopened.close();
    }
  }

  private void bind(SqliteStorage store, Anchor at, UUID generation, ActorId actor, long revision) {
    var bank =
        new ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.InstitutionId(
            "economy:bank/crownstone");
    var request = TerminalBanking.bindRequest(at, generation, revision, bank, true);
    var intent =
        new Intent(
            new OperationId(UUID.randomUUID()),
            new RequestId(UUID.randomUUID()),
            actor,
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
                        catalog,
                        (a, i, c, r) -> live,
                        (a, r) -> a.equals(operator))))
            .submit(intent)
            .status());
  }

  private SqliteStorage open() throws Exception {
    var store = new SqliteStorage();
    store.initialize(world.toRealPath());
    return store;
  }

  private Snapshot snapshot(SqliteStorage store, ActorId actor) {
    return store
        .transaction(
            tx ->
                TerminalPolicy.find(
                    tx::query, store.snapshot().worldId(), anchor, actor, actor.equals(operator)))
        .orElseThrow();
  }

  private JournalEngine engine(SqliteStorage store, Action action) {
    return new JournalEngine(
        store,
        (actor, kind) -> kind.equals(action.kind()),
        Map.of(
            action.kind(),
            TerminalPolicy.registration(
                action,
                store.snapshot().worldId(),
                catalog,
                (a, intent, current, reads) -> live,
                (actor, reads) -> actor.equals(operator))));
  }

  private Outcome submit(
      SqliteStorage store, Action action, ActorId actor, CanonicalRequest request) {
    return engine(store, action).submit(intent(action, actor, request));
  }

  private static Intent intent(Action action, ActorId actor, CanonicalRequest request) {
    return new Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        actor,
        action.kind(),
        request,
        null,
        null);
  }
}
