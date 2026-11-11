package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerminalBankingTest {
  @TempDir Path world;
  private final PlayerId owner = new PlayerId(UUID.randomUUID()),
      stranger = new PlayerId(UUID.randomUUID());
  private final Anchor anchor = new Anchor("minecraft:overworld", 10, 64, 20);
  private final Map<String, Denomination> catalog =
      Map.of(
          "economy:cad_bill_5",
          new Denomination(EconomyCurrency.CAD, true),
          "economy:cad_coin_25",
          new Denomination(EconomyCurrency.CAD, false),
          "economy:eur_bill_5",
          new Denomination(EconomyCurrency.EUR, true));
  private boolean live = true;

  @Test
  void bindingIsExplicitNativeOnlyRevisionedAndSurvivesReload() throws Exception {
    UUID generation = UUID.randomUUID();
    var bank = InstitutionCatalog.banks().getFirst();
    {
      var store = open();
      try {
        assertEquals(
            Status.COMMITTED,
            policy(
                    store,
                    Action.PLACE,
                    owner,
                    placeRequest(anchor, generation, owner, TerminalPolicy.Kind.ATM, null, 0))
                .status());
        assertEquals(Settings.disabled(), snapshot(store).settings());
        assertEquals(
            TerminalBanking.MigrationState.UNBOUND_BANK,
            snapshot(store).binding().migrationState());
        var nativeSettings =
            new Settings(Mode.BOTH, Set.of(EconomyCurrency.CAD), Set.of("economy:cad_bill_5"));
        assertEquals(
            "TERMINAL_POLICY",
            policy(
                    store,
                    Action.CONFIGURE,
                    owner,
                    configureRequest(anchor, generation, 1, nativeSettings))
                .result()
                .code());
        assertEquals(
            Status.NOT_ACCEPTED, bind(store, stranger, generation, 1, bank.id(), true).status());
        assertEquals(
            "TERMINAL_BANK_CONFIRM",
            bind(store, owner, generation, 1, bank.id(), false).result().code());
        var intent =
            intent(
                TerminalBanking.BIND,
                owner,
                TerminalBanking.bindRequest(anchor, generation, 1, bank.id(), true));
        var engine = bindingEngine(store);
        var outcome = engine.submit(intent);
        assertEquals(Status.COMMITTED, outcome.status());
        assertEquals(outcome, engine.submit(intent));
        assertEquals(2, snapshot(store).revision());
        assertEquals(Settings.disabled(), snapshot(store).settings());
        var badCurrency =
            new Settings(Mode.BOTH, Set.of(EconomyCurrency.EUR), Set.of("economy:eur_bill_5"));
        assertEquals(
            "TERMINAL_POLICY",
            policy(
                    store,
                    Action.CONFIGURE,
                    owner,
                    configureRequest(anchor, generation, 2, badCurrency))
                .result()
                .code());
        var coin =
            new Settings(Mode.BOTH, Set.of(EconomyCurrency.CAD), Set.of("economy:cad_coin_25"));
        assertEquals(
            "TERMINAL_POLICY",
            policy(store, Action.CONFIGURE, owner, configureRequest(anchor, generation, 2, coin))
                .result()
                .code());
        assertEquals(
            Status.COMMITTED,
            policy(
                    store,
                    Action.CONFIGURE,
                    owner,
                    configureRequest(anchor, generation, 2, nativeSettings))
                .status());
        assertFalse(
            store.<Boolean>transaction(
                tx ->
                    TerminalBanking.matches(
                        tx::query, store.snapshot().worldId(), anchor, generation, 2, bank.id())));
        assertTrue(
            store.<Boolean>transaction(
                tx ->
                    TerminalBanking.matches(
                        tx::query, store.snapshot().worldId(), anchor, generation, 3, bank.id())));
      } finally {
        store.close();
      }
    }
    {
      var store = open();
      try {
        assertEquals(bank.id(), snapshot(store).binding().institution());
        assertEquals(EconomyCurrency.CAD, snapshot(store).binding().nativeCurrency());
        live = false;
        assertEquals(
            Status.NOT_ACCEPTED, bind(store, owner, generation, 3, bank.id(), true).status());
        live = true;
        assertEquals(
            "TERMINAL_BANK_LOCKED",
            bind(store, owner, generation, 3, InstitutionCatalog.banks().get(1).id(), true)
                .result()
                .code());
        assertEquals(3, snapshot(store).revision());
      } finally {
        store.close();
      }
    }
  }

  @Test
  void everyBankHasExplicitCurrencyAndTellerCannotGainOtherCurrency() throws Exception {
    {
      var store = open();
      try {
        int offset = 0;
        for (var bank : InstitutionCatalog.banks()) {
          var at = new Anchor(anchor.dimension(), anchor.x() + offset++, anchor.y(), anchor.z());
          var generation = UUID.randomUUID();
          policy(
              store,
              Action.PLACE,
              owner,
              placeRequest(at, generation, owner, TerminalPolicy.Kind.TELLER, null, 0));
          var binding =
              intent(
                  TerminalBanking.BIND,
                  owner,
                  TerminalBanking.bindRequest(at, generation, 1, bank.id(), true));
          assertEquals(Status.COMMITTED, bindingEngine(store).submit(binding).status());
          var found =
              store
                  .transaction(
                      tx ->
                          TerminalPolicy.find(
                              tx::query, store.snapshot().worldId(), at, owner, false))
                  .orElseThrow();
          assertEquals(bank.baseCurrency(), found.binding().nativeCurrency());
          assertEquals(Settings.disabled(), TerminalPolicy.effective(found, catalog).settings());
        }
      } finally {
        store.close();
      }
    }
  }

  @Test
  void replacementGenerationCannotInheritTheOldBrand() throws Exception {
    {
      var store = open();
      try {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var bank = InstitutionCatalog.banks().getFirst();
        policy(
            store,
            Action.PLACE,
            owner,
            placeRequest(anchor, first, owner, TerminalPolicy.Kind.KIOSK, null, 0));
        assertEquals(Status.COMMITTED, bind(store, owner, first, 1, bank.id(), true).status());
        policy(store, Action.REMOVE, owner, removeRequest(anchor, first, 2));
        policy(
            store,
            Action.PLACE,
            owner,
            placeRequest(anchor, second, owner, TerminalPolicy.Kind.KIOSK, first, 3));
        assertFalse(snapshot(store).binding().bound());
        assertFalse(
            store.<Boolean>transaction(
                tx ->
                    TerminalBanking.matches(
                        tx::query, store.snapshot().worldId(), anchor, first, 2, bank.id())));
      } finally {
        store.close();
      }
    }
  }

  private SqliteStorage open() throws Exception {
    var store = new SqliteStorage();
    store.initialize(world.toRealPath());
    return store;
  }

  private Snapshot snapshot(SqliteStorage store) {
    return store
        .transaction(
            tx -> TerminalPolicy.find(tx::query, store.snapshot().worldId(), anchor, owner, false))
        .orElseThrow();
  }

  private Outcome policy(
      SqliteStorage store, Action action, ActorId actor, CanonicalRequest request) {
    return new JournalEngine(
            store,
            (a, k) -> true,
            Map.of(
                action.kind(),
                TerminalPolicy.registration(
                    action,
                    store.snapshot().worldId(),
                    catalog,
                    (a, i, c, r) -> live,
                    (a, r) -> false)))
        .submit(intent(action.kind(), actor, request));
  }

  private JournalEngine bindingEngine(SqliteStorage store) {
    return new JournalEngine(
        store,
        (a, k) -> true,
        Map.of(
            TerminalBanking.BIND,
            TerminalBanking.registration(
                store.snapshot().worldId(), catalog, (a, i, c, r) -> live, (a, r) -> false)));
  }

  private Outcome bind(
      SqliteStorage store,
      ActorId actor,
      UUID generation,
      long revision,
      InstitutionCatalog.InstitutionId bank,
      boolean confirm) {
    return bindingEngine(store)
        .submit(
            intent(
                TerminalBanking.BIND,
                actor,
                TerminalBanking.bindRequest(anchor, generation, revision, bank, confirm)));
  }

  private static Intent intent(Journal.Kind kind, ActorId actor, CanonicalRequest request) {
    return new Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        actor,
        kind,
        request,
        null,
        null);
  }
}
