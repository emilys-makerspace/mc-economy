package ca.emilybelnavis.mcmods.economy.corporation;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.CorporationIntegration.Capability;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntegrationBindingsTest {
  private static final long NOW = 0;
  private static final InstitutionCatalog.Entry BANK = InstitutionCatalog.banks().getFirst();

  @TempDir Path path;
  private final PlayerId founder = new PlayerId(UUID.randomUUID());
  private final AccountId funding = new AccountId(UUID.randomUUID());
  private final AccountId corporate = new AccountId(UUID.randomUUID());

  @Test
  void oneCreationFeeAndCurrentScopedAuthoritySurviveRetryAndReopen() throws Exception {
    var primary = new IntegrationBindings.Key("fixture_primary:api", "workshop-1");
    var other = new IntegrationBindings.Key("fixture_other:api", primary.externalKey());
    var registration =
        new Corporations.Register(
            "Fixture Studio", new Corporations.Fee(funding, 1, CAD, 50_000, null));
    var create = new IntegrationBindings.Create(primary, registration);
    var createIntent = intent(founder, IntegrationBindings.kind(primary), create.request(founder));
    var store = new SqliteStorage();
    UUID company;
    Journal.Outcome original;
    Journal.Outcome paid;
    Journal.Intent paidIntent;
    WorldId world;
    try {
      store.initialize(path.toRealPath());
      world = store.snapshot().worldId();
      seed(store, world);
      original = binding(store, createIntent, create);
      assertEquals("INTEGRATION_CREATED", original.result().code());
      company = UUID.fromString(original.result().payload());

      assertEquals(original, binding(store, createIntent, create));
      assertEquals(original.result(), binding(store, create).result());
      assertEquals(50_000, balance(store, funding));
      var changed =
          new IntegrationBindings.Create(
              primary, new Corporations.Register("Another Studio", registration.fee()));
      assertEquals("INTEGRATION_BINDING_CONFLICT", binding(store, changed).result().code());

      fundCorporate(store, company);
      var capability = Capability.PAYMENTS;
      assertEquals(
          "INTEGRATION_BINDING_UNAVAILABLE",
          binding(
                  store,
                  new IntegrationBindings.Approve(
                      other, company, 1, 0, Set.of(capability), Set.of(corporate)))
              .result()
              .code());
      var approved =
          binding(
              store,
              new IntegrationBindings.Approve(
                  primary, company, 1, 0, Set.of(capability), Set.of(corporate)));
      assertEquals(Journal.Status.COMMITTED, approved.status(), approved.result().code());
      assertTrue(
          store.<Boolean>transaction(
              tx ->
                  IntegrationBindings.permits(
                      tx::query, primary, company, 1, 1, capability, corporate)));
      assertFalse(
          store.<Boolean>transaction(
              tx ->
                  IntegrationBindings.permits(
                      tx::query, primary, company, 1, 1, capability, funding)));

      var payment = payment(world, company);
      var crossProvider = access(other, world, company);
      assertEquals(
          "INTEGRATION_SCOPE_DENIED",
          pay(store, crossProvider, payIntent(crossProvider, payment), payment).result().code());
      assertEquals(1_000, balance(store, corporate));

      var access = access(primary, world, company);
      var disabled =
          submit(
              store,
              payIntent(access, payment),
              (i, reads) ->
                  IntegrationBanking.prepare(i, reads, access, () -> false, payment, NOW));
      assertEquals("INTEGRATION_PROVIDER_STALE", disabled.result().code());
      assertEquals(1_000, balance(store, corporate));

      paidIntent = payIntent(access, payment);
      paid = pay(store, access, paidIntent, payment);
      assertEquals(Journal.Status.COMMITTED, paid.status(), paid.result().code());
      assertEquals(
          Journal.actorKey(access.actor()),
          store.<String>transaction(
              tx ->
                  tx.query(
                          "SELECT actor FROM operation_outcomes WHERE transaction_id=?",
                          List.of(paid.transactionId().value().toString()),
                          1)
                      .getFirst()
                      .get("actor")));
      assertEquals(900, balance(store, corporate));
      assertEquals(50_100, balance(store, funding));

      var pending = payIntent(access, payment);
      var revoked = binding(store, new IntegrationBindings.Revoke(primary, company, 1, 1));
      assertEquals(Journal.Status.COMMITTED, revoked.status(), revoked.result().code());
      assertEquals(
          "INTEGRATION_SCOPE_DENIED", pay(store, access, pending, payment).result().code());
      assertEquals(900, balance(store, corporate));
    } finally {
      store.close();
    }
    var reopened = new SqliteStorage();
    try {
      reopened.initialize(path.toRealPath());
      assertEquals(world, reopened.snapshot().worldId());
      assertEquals(original, binding(reopened, createIntent, create));
      assertTrue(
          reopened.<Boolean>transaction(
              tx -> IntegrationBindings.find(tx::query, primary).isPresent()));
      assertFalse(
          reopened.<Boolean>transaction(
              tx -> IntegrationBindings.find(tx::query, other).isPresent()));
      var payment = payment(world, company);
      var access = access(primary, world, company);
      assertEquals(paid, pay(reopened, access, paidIntent, payment));
      assertEquals(
          "INTEGRATION_SCOPE_DENIED",
          pay(reopened, access, payIntent(access, payment), payment).result().code());
      assertEquals(900, balance(reopened, corporate));
      assertEquals(50_100, balance(reopened, funding));
    } finally {
      reopened.close();
    }
  }

  private void seed(SqliteStorage store, WorldId world) {
    store.transaction(
        tx -> {
          tx.execute(
              "INSERT INTO institution_parties VALUES(?,?,?,?,?,?,1)",
              List.of(
                  world.value().toString(),
                  BANK.id().key(),
                  new InstitutionCatalog.SystemActor(world, BANK.id()).actorId().value().toString(),
                  BANK.name(),
                  CAD.code(),
                  BANK.tier().name()));
          bankAccount(tx, funding, founder.value().toString(), true, 100_000);
          return null;
        });
  }

  private void fundCorporate(SqliteStorage store, UUID company) {
    store.transaction(
        tx -> {
          bankAccount(tx, corporate, "organization:" + company, false, 1_000);
          return null;
        });
  }

  private static void bankAccount(
      FoundationStore.Transaction tx,
      AccountId account,
      String owner,
      boolean primary,
      long minor) {
    String id = account.value().toString();
    tx.execute(
        "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,?)",
        List.of(id, owner, primary ? "1" : "0"));
    tx.execute(
        "INSERT INTO bank_account_providers VALUES(?,?,0,1,0)", List.of(id, BANK.id().key()));
    LedgerProjection.write(tx, account.value(), CAD, minor);
  }

  private CorporatePayments.Payment payment(WorldId world, UUID company) {
    var companyOwner = new PartyRef(world, new PartyRef.Organization(new OrganizationId(company)));
    var personalOwner = new PartyRef(world, new PartyRef.Player(founder));
    return new CorporatePayments.Payment(
        new CorporatePayments.Endpoint(companyOwner, corporate, 1, BANK.id().key(), 1, 1),
        new CorporatePayments.Endpoint(personalOwner, funding, 1, BANK.id().key(), 1, 0),
        0,
        false,
        new CorporatePayments.Terms(100, 0, 100, null));
  }

  private static IntegrationBanking.Access access(
      IntegrationBindings.Key key, WorldId world, UUID company) {
    return new IntegrationBanking.Access(
        key, IntegrationBanking.actor(world, key.provider()), world, company, 1, 1, 1);
  }

  private static Journal.Intent payIntent(
      IntegrationBanking.Access access, CorporatePayments.Payment payment) {
    return intent(
        access.actor(), IntegrationBanking.PAYMENT, IntegrationBanking.request(access, payment));
  }

  private static Journal.Outcome pay(
      SqliteStorage store,
      IntegrationBanking.Access access,
      Journal.Intent intent,
      CorporatePayments.Payment payment) {
    return submit(
        store,
        intent,
        (i, reads) -> IntegrationBanking.prepare(i, reads, access, () -> true, payment, NOW));
  }

  private Journal.Outcome binding(SqliteStorage store, IntegrationBindings.Command command) {
    return binding(
        store,
        intent(founder, IntegrationBindings.kind(command.key()), command.request(founder)),
        command);
  }

  private Journal.Outcome binding(
      SqliteStorage store, Journal.Intent intent, IntegrationBindings.Command command) {
    var party = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(founder));
    return submit(
        store,
        intent,
        (i, reads) -> IntegrationBindings.prepare(i, reads, party, command, () -> NOW));
  }

  private static Journal.Outcome submit(
      SqliteStorage store, Journal.Intent intent, JournalEngine.Handler handler) {
    return new JournalEngine(
            store,
            (actor, kind) -> actor.equals(intent.actor()),
            Map.of(intent.kind(), new JournalEngine.Registration(1, handler)))
        .submit(intent);
  }

  private static Journal.Intent intent(
      ActorId actor, Journal.Kind kind, Journal.CanonicalRequest request) {
    return new Journal.Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        actor,
        kind,
        request,
        null,
        null);
  }

  private static long balance(SqliteStorage store, AccountId account) {
    return store.transaction(tx -> Settlement.posted(tx::query, account.value(), CAD));
  }
}
