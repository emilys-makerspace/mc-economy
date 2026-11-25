package ca.emilybelnavis.mcmods.economy.corporation;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.overdraft.Overdrafts;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagersTest {
  @TempDir Path world;
  final PlayerId founder = new PlayerId(UUID.randomUUID()),
      manager = new PlayerId(UUID.randomUUID());
  final UUID company = UUID.randomUUID(), account = UUID.randomUUID(), target = UUID.randomUUID();
  final SqliteStorage store = new SqliteStorage();
  final Managers.Scope scope = new Managers.Scope(false, Set.of(account));
  final Journal.Kind payment = new Journal.Kind("fixture:managers", "fixture:payment");

  private Journal.Outcome mutate(
      PlayerId actor, Managers.Mutation action, long revision, Managers.Rights rights) {
    var change =
        new Managers.Change(
            company,
            1,
            manager.value(),
            revision,
            action,
            rights,
            rights.capabilities().contains(Managers.Capability.PAY_TRANSFER));
    var party = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(actor));
    var fields = new HashMap<>(change.request().fields());
    fields.put("economy:owner", Journal.actorKey(actor));
    return new JournalEngine(
            store,
            (a, k) -> a.equals(actor),
            Map.of(Managers.KIND, Managers.mutation(party, change)))
        .submit(intent(actor, Managers.KIND, fields));
  }

  private Journal.Intent intent(PlayerId actor, Journal.Kind kind, Map<String, String> fields) {
    return new Journal.Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        actor,
        kind,
        new Journal.CanonicalRequest(1, fields),
        null,
        null);
  }

  private boolean permits(Managers.Use use, UUID source, long revision) {
    return store.transaction(
        tx ->
            Managers.permits(
                tx::query, manager, new Managers.Requirement(company, 1, revision, use, source)));
  }

  @Test
  void revocationFencesPreparedPaymentAndRetainsOriginalOutcome() throws Exception {
    store.initialize(world.toRealPath());
    try {
      store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO corporations VALUES(?,?,'Fixture Workshop','ACTIVE',1)",
                List.of(company.toString(), founder.value().toString()));
            tx.execute(
                "INSERT INTO corporation_registry(identifier,party,funded) VALUES('fixture-workshop',?,1)",
                List.of(company.toString()));
            tx.execute(
                "INSERT INTO corporation_shares VALUES('fixture-workshop','A',1,100,'CAD')",
                List.of());
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,0)",
                List.of(account.toString(), "organization:" + company));
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,0)",
                List.of(target.toString(), manager.value().toString()));
            LedgerProjection.write(tx, account, CAD, 1000);
            return null;
          });
      var initial = Managers.Rights.viewOnly(scope);
      assertEquals(
          Journal.Status.COMMITTED,
          mutate(founder, Managers.Mutation.APPOINT, 0, initial).status());
      assertTrue(permits(Managers.Use.HISTORY, account, 1));
      assertFalse(permits(Managers.Use.PAYMENT, account, 1));
      assertFalse(permits(Managers.Use.HISTORY, target, 1));
      var spend = new Managers.Rights(Set.of(Managers.Capability.PAY_TRANSFER), scope);
      assertEquals(
          "CORPORATION_UNAVAILABLE",
          mutate(manager, Managers.Mutation.CHANGE, 1, spend).result().code());
      assertEquals(
          Journal.Status.COMMITTED, mutate(founder, Managers.Mutation.CHANGE, 1, spend).status());
      assertFalse(permits(Managers.Use.HISTORY, account, 2));
      var required = new Managers.Requirement(company, 1, 2, Managers.Use.PAYMENT, account);
      var fields = new HashMap<>(required.fields());
      fields.put("recipient", target.toString());
      fields.put("minor", "100");
      var first = intent(manager, payment, fields);
      var pending = intent(manager, payment, fields);
      var debit =
          Managers.guarded(
              required,
              new JournalEngine.Registration(
                  1,
                  (i, reads) ->
                      Settlement.prepareTrusted(
                          reads,
                          List.of(
                              new PostingBatch.Leg(
                                  account, PostingBatch.Role.ACCOUNT, CAD, new Money(CAD, -100)),
                              new PostingBatch.Leg(
                                  target, PostingBatch.Role.ACCOUNT, CAD, new Money(CAD, 100))),
                          PostingBatch.Authority.TRANSFER,
                          Overdrafts.DebitKind.NORMAL)));
      var engine = new JournalEngine(store, (a, k) -> a.equals(manager), Map.of(payment, debit));
      var original = engine.submit(first);
      assertEquals(Journal.Status.COMMITTED, original.status(), original.result().code());
      assertEquals(
          Journal.Status.COMMITTED,
          mutate(founder, Managers.Mutation.REVOKE, 2, Managers.Rights.none()).status());
      assertEquals("CORPORATION_UNAVAILABLE", engine.submit(pending).result().code());
      assertEquals(original, engine.submit(first));
      assertEquals(
          900L,
          store.<Long>transaction(tx -> Settlement.posted(tx::query, account, CAD)).longValue());
    } finally {
      store.close();
    }
    var reopened = new SqliteStorage();
    try {
      reopened.initialize(world.toRealPath());
      var roster =
          reopened.transaction(
              tx -> Managers.roster(tx::query, founder, company, null, 10).orElseThrow());
      assertEquals(3, roster.getFirst().revision());
      assertFalse(roster.getFirst().active());
      assertTrue(
          reopened
              .transaction(tx -> Managers.roster(tx::query, manager, company, null, 10))
              .isEmpty());
    } finally {
      reopened.close();
    }
  }
}
