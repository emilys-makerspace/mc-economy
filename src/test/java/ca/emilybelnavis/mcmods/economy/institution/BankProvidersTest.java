package ca.emilybelnavis.mcmods.economy.institution;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BankProvidersTest {
  @TempDir Path world;

  private static InstitutionCatalog.InstitutionId bank(String key) {
    return new InstitutionCatalog.InstitutionId("economy:bank/" + key);
  }

  private final class Fixture implements AutoCloseable {
    final SqliteStorage store = new SqliteStorage();
    final PlayerId actor = new PlayerId(UUID.randomUUID());
    final AtomicLong time = new AtomicLong();
    final PartyRef owner;
    final BranchAdmission.Issuer issuer = new BranchAdmission.Issuer(time::get, (c, r) -> true);

    Fixture() throws Exception {
      store.initialize(world.toRealPath());
      owner = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(actor));
      var engine =
          new JournalEngine(
              store,
              (a, k) -> true,
              Map.of(
                  FxService.OPEN,
                  FxService.opening(
                      Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC))));
      assertEquals(
          Journal.Status.COMMITTED,
          engine
              .submit(
                  new Journal.Intent(
                      new OperationId(UUID.randomUUID()),
                      new RequestId(UUID.randomUUID()),
                      actor,
                      FxService.OPEN,
                      new Journal.CanonicalRequest(1, Map.of("currency", "CAD")),
                      null,
                      null))
              .status());
    }

    BankProviders.Request request(
        BranchAdmission.Action action,
        String bank,
        String target,
        AccountId account,
        long ar,
        long pr,
        long dr) {
      return new BankProviders.Request(
          issuer.issue(
              new BranchAdmission.Context(
                  owner.world(),
                  UUID.randomUUID(),
                  actor,
                  action,
                  bank(bank),
                  target == null ? null : bank(target),
                  account,
                  ar,
                  pr,
                  dr),
              30_000),
          0);
    }

    Journal.Outcome submit(BankProviders.Request request) {
      return submit(request, BankProviders.NO_TIER2);
    }

    Journal.Outcome submit(
        BankProviders.Request request, BankProviders.TierEligibility eligibility) {
      var kind =
          switch (request.proof().context().action()) {
            case PRIMARY -> BankProviders.PRIMARY;
            case SECONDARY -> BankProviders.SECONDARY;
            case ASSIGN -> BankProviders.ASSIGN;
            case SWITCH_SOURCE -> BankProviders.SWITCH_SOURCE;
            case SWITCH_TARGET -> BankProviders.SWITCH_TARGET;
            case CLOSE -> BankProviders.CLOSE;
            case CARD_ISSUE,
                CARD_REPLACE,
                CREDIT_ISSUE,
                CREDIT_INCREASE,
                CREDIT_REPLACE,
                LOAN_APPLY ->
                throw new IllegalArgumentException("Cards use own service");
          };
      return new JournalEngine(
              store,
              (a, k) -> true,
              Map.of(kind, BankProviders.lifecycle(owner, request, eligibility, time::get)))
          .submit(
              new Journal.Intent(
                  new OperationId(UUID.randomUUID()),
                  new RequestId(UUID.randomUUID()),
                  actor,
                  kind,
                  request.request(),
                  null,
                  null));
    }

    AccountId primary() {
      return new AccountId(
          UUID.fromString(
              submit(request(BranchAdmission.Action.PRIMARY, "crownstone", null, null, 0, 0, 0))
                  .result()
                  .payload()));
    }

    BankProviders.Binding binding(AccountId id) {
      return store.transaction(tx -> BankProviders.find(tx::query, id).orElseThrow());
    }

    @Override
    public void close() throws Exception {
      store.close();
    }
  }

  @Test
  void globalFourthSlotAndUniqueCurrencyAreSerializedAcrossBanks() throws Exception {
    try (var f = new Fixture()) {
      var primary = f.primary();
      assertEquals(
          "PROVIDER_OPENED",
          f.submit(f.request(BranchAdmission.Action.SECONDARY, "crosswind", null, null, 1, 0, 0))
              .result()
              .code());
      assertEquals(
          "BANK_CURRENCY_EXISTS",
          f.submit(
                  f.request(BranchAdmission.Action.SECONDARY, "lantern_union", null, null, 1, 0, 0))
              .result()
              .code());
      assertEquals(
          "PROVIDER_OPENED",
          f.submit(f.request(BranchAdmission.Action.SECONDARY, "asterbridge", null, null, 1, 0, 0))
              .result()
              .code());
      // Both requests capture the same last-slot snapshot; worker serialization admits exactly one.
      var fourth =
          f.request(BranchAdmission.Action.SECONDARY, "meridian_grove", null, null, 1, 0, 0);
      var fifth = f.request(BranchAdmission.Action.SECONDARY, "cedar_parcel", null, null, 1, 0, 0);
      assertEquals("PROVIDER_OPENED", f.submit(fourth).result().code());
      assertEquals("BANK_CAP_REACHED", f.submit(fifth).result().code());
      assertEquals(
          4, f.store.transaction(tx -> AccountCatalog.history(tx::query, f.owner, null, 8)).size());
      assertEquals(
          primary,
          f.store.transaction(tx -> AccountCatalog.primary(tx::query, f.owner).orElseThrow().id()));
    }
  }

  @Test
  void simultaneousLastSlotRequestsCommitOnlyOneThroughTheSoleWorker() throws Exception {
    try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var fixture = worker.submit(() -> new Fixture()).get();
      try {
        var requests =
            worker
                .submit(
                    () -> {
                      fixture.primary();
                      fixture.submit(
                          fixture.request(
                              BranchAdmission.Action.SECONDARY, "crosswind", null, null, 1, 0, 0));
                      fixture.submit(
                          fixture.request(
                              BranchAdmission.Action.SECONDARY,
                              "asterbridge",
                              null,
                              null,
                              1,
                              0,
                              0));
                      return List.of(
                          fixture.request(
                              BranchAdmission.Action.SECONDARY,
                              "meridian_grove",
                              null,
                              null,
                              1,
                              0,
                              0),
                          fixture.request(
                              BranchAdmission.Action.SECONDARY,
                              "cedar_parcel",
                              null,
                              null,
                              1,
                              0,
                              0));
                    })
                .get();
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var results = new ArrayList<java.util.concurrent.Future<Journal.Outcome>>();
        for (var request : requests)
          results.add(
              callers.submit(
                  () -> {
                    ready.countDown();
                    start.await();
                    return worker.submit(() -> fixture.submit(request)).get();
                  }));
        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
        start.countDown();
        var codes = new HashSet<String>();
        for (var result : results) codes.add(result.get().result().code());
        assertEquals(Set.of("PROVIDER_OPENED", "BANK_CAP_REACHED"), codes);
        assertEquals(
            4,
            worker
                .submit(
                    () ->
                        fixture
                            .store
                            .transaction(
                                tx -> AccountCatalog.history(tx::query, fixture.owner, null, 8))
                            .size())
                .get());
      } finally {
        worker
            .submit(
                () -> {
                  fixture.close();
                  return null;
                })
            .get();
      }
    }
  }

  @Test
  void primaryRequiresMatchingNativeCurrencyAndLiveBankIssuer() throws Exception {
    try (var f = new Fixture()) {
      assertEquals(
          "PRIMARY_BANK_CURRENCY",
          f.submit(f.request(BranchAdmission.Action.PRIMARY, "crosswind", null, null, 0, 0, 0))
              .result()
              .code());
      assertEquals(
          "BANK_UNAVAILABLE",
          f.submit(f.request(BranchAdmission.Action.PRIMARY, "missing", null, null, 0, 0, 0))
              .result()
              .code());
      var request = f.request(BranchAdmission.Action.PRIMARY, "crownstone", null, null, 0, 0, 0);
      f.issuer.disable();
      assertEquals("BRANCH_UNAVAILABLE", f.submit(request).result().code());
    }
  }

  @Test
  void targetSwitchAtomicallyRevokesTheIssuedDebitToken() throws Exception {
    try (var f = new Fixture()) {
      var account = f.primary();
      var proof =
          f.issuer.issue(
              new BranchAdmission.Context(
                  f.owner.world(),
                  UUID.randomUUID(),
                  f.actor,
                  BranchAdmission.Action.CARD_ISSUE,
                  bank("crownstone"),
                  null,
                  account,
                  1,
                  1,
                  0),
              30_000);
      var request =
          new ca.emilybelnavis.mcmods.economy.card.IssuedCards.Request(proof, 0, null, null);
      var operation = new OperationId(UUID.randomUUID());
      var issue =
          new Journal.Intent(
              operation,
              new RequestId(UUID.randomUUID()),
              f.actor,
              ca.emilybelnavis.mcmods.economy.card.IssuedCards.ISSUE,
              request.request(),
              null,
              null);
      var handler =
          ca.emilybelnavis.mcmods.economy.card.IssuedCards.registration(
              f.owner, request, BankProviders.NO_TIER2, f.time::get, null);
      assertEquals(
          Journal.Status.COMMITTED,
          new JournalEngine(f.store, (a, k) -> true, Map.of(issue.kind(), handler))
              .submit(issue)
              .status());
      assertEquals(
          "PROVIDER_SWITCH_REQUEST",
          f.submit(
                  f.request(
                      BranchAdmission.Action.SWITCH_SOURCE,
                      "crownstone",
                      "orchard_direct",
                      account,
                      1,
                      1,
                      0))
              .result()
              .code());
      assertTrue(
          f.store.<Boolean>transaction(
              tx ->
                  ca.emilybelnavis.mcmods.economy.card.IssuedCards.usable(
                          tx::query, f.owner.world(), f.actor, operation.value(), f.time.get())
                      .isPresent()));
      assertEquals(
          "PROVIDER_ASSIGNED",
          f.submit(
                  f.request(
                      BranchAdmission.Action.SWITCH_TARGET,
                      "orchard_direct",
                      null,
                      account,
                      1,
                      2,
                      0))
              .result()
              .code());
      var token =
          f.store.transaction(
              tx ->
                  ca.emilybelnavis.mcmods.economy.card.IssuedCards.find(
                          tx::query, f.owner.world(), operation.value())
                      .orElseThrow());
      assertEquals(ca.emilybelnavis.mcmods.economy.card.IssuedCards.Status.REVOKED, token.status());
      assertTrue(
          f.store.<Boolean>transaction(
              tx ->
                  ca.emilybelnavis.mcmods.economy.card.IssuedCards.usable(
                          tx::query, f.owner.world(), f.actor, operation.value(), f.time.get())
                      .isEmpty()));
    }
  }

  @Test
  void switchRequiresSourceAndNamedTargetVisitsAndPreservesIdentityAndBalance() throws Exception {
    try (var f = new Fixture()) {
      var id = f.primary();
      f.store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO ledger_balances VALUES(?,'CAD',12345)",
                List.of(id.value().toString()));
            return null;
          });
      assertEquals(
          "SOURCE_BANK",
          f.submit(
                  f.request(
                      BranchAdmission.Action.SWITCH_SOURCE,
                      "orchard_direct",
                      "tidecrest",
                      id,
                      1,
                      1,
                      0))
              .result()
              .code());
      assertEquals(
          "SWITCH_UNAVAILABLE",
          f.submit(
                  f.request(
                      BranchAdmission.Action.SWITCH_TARGET, "orchard_direct", null, id, 1, 1, 0))
              .result()
              .code());
      var premature =
          f.request(BranchAdmission.Action.SWITCH_TARGET, "orchard_direct", null, id, 1, 1, 0);
      assertEquals(
          "PROVIDER_SWITCH_REQUEST",
          f.submit(
                  f.request(
                      BranchAdmission.Action.SWITCH_SOURCE,
                      "crownstone",
                      "orchard_direct",
                      id,
                      1,
                      1,
                      0))
              .result()
              .code());
      assertEquals("PROVIDER_STALE", f.submit(premature).result().code());
      assertEquals(
          "SWITCH_STALE",
          f.submit(f.request(BranchAdmission.Action.SWITCH_TARGET, "tidecrest", null, id, 1, 2, 0))
              .result()
              .code());
      var target =
          f.request(BranchAdmission.Action.SWITCH_TARGET, "orchard_direct", null, id, 1, 2, 0);
      assertEquals("PROVIDER_ASSIGNED", f.submit(target).result().code());
      assertEquals("LEASE_USED", f.submit(target).result().code());
      assertEquals(bank("orchard_direct").key(), f.binding(id).bankKey());
      assertEquals(1, f.binding(id).debitEpoch());
      assertEquals(
          id,
          f.store.transaction(tx -> AccountCatalog.primary(tx::query, f.owner).orElseThrow().id()));
      assertEquals(
          "12345",
          f.store.transaction(
              tx ->
                  tx.query(
                          "SELECT minor_units FROM ledger_balances WHERE account_id=?",
                          List.of(id.value().toString()),
                          1)
                      .getFirst()
                      .get("minor_units")));
    }
  }

  @Test
  void switchingExpiresAtSevenFinancialDaysAndRejectsStaleAccountOrDenomination() throws Exception {
    try (var f = new Fixture()) {
      var id = f.primary();
      f.submit(
          f.request(
              BranchAdmission.Action.SWITCH_SOURCE, "crownstone", "orchard_direct", id, 1, 1, 0));
      f.time.set(BankProviders.SWITCH_LIFETIME);
      assertEquals(
          "SWITCH_STALE",
          f.submit(
                  f.request(
                      BranchAdmission.Action.SWITCH_TARGET, "orchard_direct", null, id, 1, 2, 0))
              .result()
              .code());
      f.submit(
          f.request(
              BranchAdmission.Action.SWITCH_SOURCE, "crownstone", "orchard_direct", id, 1, 2, 0));
      assertEquals(
          "DENOMINATION_STALE",
          f.submit(
                  f.request(
                      BranchAdmission.Action.SWITCH_TARGET, "orchard_direct", null, id, 1, 3, 1))
              .result()
              .code());
      f.store.transaction(
          tx -> {
            tx.execute(
                "UPDATE account_catalog SET revision=2 WHERE account_id=?",
                List.of(id.value().toString()));
            return null;
          });
      assertEquals(
          "ACCOUNT_STALE",
          f.submit(
                  f.request(
                      BranchAdmission.Action.SWITCH_TARGET, "orchard_direct", null, id, 1, 3, 0))
              .result()
              .code());
      assertEquals(
          "SWITCH_STALE",
          f.submit(
                  f.request(
                      BranchAdmission.Action.SWITCH_TARGET, "orchard_direct", null, id, 2, 3, 0))
              .result()
              .code());
      assertEquals(bank("crownstone").key(), f.binding(id).bankKey());
    }
  }

  @Test
  void legacyAssignmentIsExplicitAndNativeAndKeepsAccountAndFunds() throws Exception {
    try (var f = new Fixture()) {
      var id = new AccountId(UUID.randomUUID());
      f.store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                List.of(id.value().toString(), f.actor.value().toString()));
            return null;
          });
      var migration =
          new JournalEngine(
              f.store, (a, k) -> true, Map.of(BankProviders.MIGRATE, BankProviders.migrateStep()));
      var intent =
          new Journal.Intent(
              new OperationId(UUID.randomUUID()),
              new RequestId(UUID.randomUUID()),
              f.actor,
              BankProviders.MIGRATE,
              new Journal.CanonicalRequest(1, Map.of()),
              null,
              null);
      assertEquals("MIGRATION_PAGE", migration.submit(intent).result().code());
      assertEquals(BankProviders.UNASSIGNED_PROVIDER, f.binding(id).bankKey());
      assertTrue(f.binding(id).legacy());
      assertEquals(
          "BANK_CURRENCY",
          f.submit(f.request(BranchAdmission.Action.ASSIGN, "crosswind", null, id, 1, 1, 0))
              .result()
              .code());
      assertEquals(
          "PROVIDER_ASSIGNED",
          f.submit(f.request(BranchAdmission.Action.ASSIGN, "orchard_direct", null, id, 1, 1, 0))
              .result()
              .code());
      assertTrue(f.binding(id).legacy());
      assertEquals(id, f.binding(id).account());
    }
  }

  @Test
  void disabledPersistedInstitutionRejectsAdmission() throws Exception {
    try (var f = new Fixture()) {
      var bank = InstitutionCatalog.require(bank("crownstone"));
      f.store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO institution_parties VALUES(?,?,?,?,?,?,0)",
                List.of(
                    f.owner.world().value().toString(),
                    bank.id().key(),
                    UUID.randomUUID().toString(),
                    bank.name(),
                    bank.baseCurrency().code(),
                    bank.tier().name()));
            return null;
          });
      assertEquals(
          "BANK_UNAVAILABLE",
          f.submit(f.request(BranchAdmission.Action.PRIMARY, "crownstone", null, null, 0, 0, 0))
              .result()
              .code());
    }
  }

  @Test
  void providerFactsAreCompleteAndValidatedAcrossRestart() throws Exception {
    AccountId account;
    PartyRef owner;
    try (var f = new Fixture()) {
      account = f.primary();
      owner = f.owner;
      assertEquals(
          "PROVIDER_SWITCH_REQUEST",
          f.submit(
                  f.request(
                      BranchAdmission.Action.SWITCH_SOURCE,
                      "crownstone",
                      "orchard_direct",
                      account,
                      1,
                      1,
                      0))
              .result()
              .code());
      var records =
          f.store.transaction(
              tx ->
                  tx.query(
                      "SELECT record_type AS type,payload FROM journal_records WHERE record_type LIKE 'economy:provider-%'",
                      List.of(), 16));
      assertTrue(
          records.stream()
              .anyMatch(
                  r ->
                      r.get("type").equals("economy:provider-account")
                          && r.get("payload").contains(f.actor.value().toString())));
      assertTrue(
          records.stream()
              .anyMatch(
                  r ->
                      r.get("type").equals("economy:provider-switch")
                          && r.get("payload").contains(bank("orchard_direct").key())));
    }
    var reopened = new SqliteStorage();
    reopened.initialize(world.toRealPath());
    try {
      assertEquals(
          account,
          reopened.transaction(tx -> AccountCatalog.primary(tx::query, owner).orElseThrow().id()));
      assertEquals(
          bank("crownstone").key(),
          reopened.transaction(
              tx -> BankProviders.find(tx::query, account).orElseThrow().bankKey()));
      assertEquals(
          bank("orchard_direct").key(),
          reopened.transaction(
              tx ->
                  tx.query(
                          "SELECT target_key FROM bank_provider_switches WHERE account_id=?",
                          List.of(account.value().toString()),
                          1)
                      .getFirst()
                      .get("target_key")));
    } finally {
      reopened.close();
    }
    var catalog = ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog.core();
    assertThrows(
        RuntimeException.class,
        () ->
            catalog.validate(
                "economy:bank-provider",
                1,
                "assigned|" + account.value() + "|economy:bank/crownstone|2"));
    assertThrows(
        RuntimeException.class,
        () ->
            catalog.validate(
                "economy:provider-account",
                1,
                owner.world().value() + "|" + account.value() + "|bad|CAD|OPEN|1|1"));
    assertThrows(
        RuntimeException.class,
        () ->
            catalog.validate(
                "economy:provider-switch",
                1,
                account.value() + "|economy:bank/crownstone|economy:bank/orchard_direct|1|1|0|-1"));
  }

  @Test
  void migrationReadsOnlyBoundedIdentityPageAndPreservesIdentity() throws Exception {
    var id = UUID.randomUUID().toString();
    var queries = new ArrayList<String>();
    JournalEngine.ReadView reads =
        (sql, p, max) -> {
          queries.add(sql);
          if (sql.contains("migration")) return List.of(Map.of("cursor", ""));
          assertEquals(16, max);
          assertTrue(sql.contains("purpose='BANK'"));
          assertTrue(sql.contains("LIMIT 16"));
          return List.of(Map.of("account_id", id));
        };
    var intent =
        new Journal.Intent(
            new OperationId(UUID.randomUUID()),
            new RequestId(UUID.randomUUID()),
            new SystemActorId(UUID.randomUUID()),
            BankProviders.MIGRATE,
            new Journal.CanonicalRequest(1, Map.of()),
            null,
            null);
    var decision = BankProviders.migrateStep().handler().prepare(intent, reads);
    var commit = assertInstanceOf(JournalEngine.Commit.class, decision);
    assertEquals("MIGRATION_PAGE", commit.result().code());
    assertEquals(
        "legacy|" + id + "|" + BankProviders.UNASSIGNED_PROVIDER + "|1|1|0",
        commit.records().getFirst().payload());
    assertEquals(2, queries.size());
    assertEquals("economy:provider-migration-cursor", commit.records().getLast().type());
  }

  @Test
  void closureDestinationIsBoundIntoImmutableCanonicalRequest() {
    var context =
        new BranchAdmission.Context(
            new WorldId(UUID.randomUUID()),
            UUID.randomUUID(),
            new PlayerId(UUID.randomUUID()),
            BranchAdmission.Action.CLOSE,
            new InstitutionCatalog.InstitutionId("economy:bank/crownstone"),
            null,
            new AccountId(UUID.randomUUID()),
            3,
            2,
            1);
    var proof = new BranchAdmission.Issuer(() -> 0, (c, r) -> true).issue(context, 1_000);
    var fields = new HashMap<String, String>();
    fields.put("destination", UUID.randomUUID().toString());
    var request = new BankProviders.Request(proof, 0, fields);
    var canonical = request.request();
    fields.put("destination", UUID.randomUUID().toString());
    assertEquals(canonical, request.request());
    assertEquals("player:" + context.actor().value(), canonical.fields().get("economy:owner"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BankProviders.Request(proof, 0, Map.of("bank", "spoof")).request());
  }

  @Test
  void specialistAdmissionRequiresCurrentServerEligibilityWhileHybridRemainsTierOne()
      throws Exception {
    try (var f = new Fixture()) {
      var primary = f.primary(); // Crownstone hybrid accepts Tier1 admission with NO_TIER2.
      var unavailable =
          f.request(BranchAdmission.Action.SECONDARY, "atlas_reserve", null, primary, 1, 0, 0);
      assertEquals("TIER2_UNAVAILABLE", f.submit(unavailable).result().code());
      BankProviders.TierEligibility qualified =
          (reads, owner) -> new BankProviders.TierSnapshot(true, true, 7);
      var stale =
          f.request(BranchAdmission.Action.SECONDARY, "atlas_reserve", null, primary, 1, 0, 0);
      assertEquals("TIER2_UNAVAILABLE", f.submit(stale, qualified).result().code());
      var fresh =
          f.request(BranchAdmission.Action.SECONDARY, "atlas_reserve", null, primary, 1, 0, 0);
      var bound = new BankProviders.Request(fresh.proof(), 7);
      assertEquals("PROVIDER_OPENED", f.submit(bound, qualified).result().code());
    }
  }

  @Test
  void tierTwoIsUnavailableWithoutValuationService() {
    var state = BankProviders.NO_TIER2.current((sql, p, max) -> List.of(), null);
    assertFalse(state.available());
    assertFalse(state.eligible());
  }
}
