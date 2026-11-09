package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD;
import static ca.emilybelnavis.mcmods.economy.journal.JournalFixtures.ACTOR;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.OperationId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.RequestId;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LedgerHistoryTest {
  @TempDir Path world;

  @Test
  void closedAccountHistoryRemainsOrderedAndPairedAcrossAppends() throws Exception {
    var account = new AccountId(UUID.randomUUID());
    var recipient = new PlayerId(UUID.randomUUID());
    var target = new AccountId(UUID.randomUUID());
    var store = new SqliteStorage();
    try {
      store.initialize(world.toRealPath());
      var worldId = store.snapshot().worldId();
      var owner = new PartyRef(worldId, new PartyRef.Player(ACTOR));
      var other = new PartyRef(worldId, new PartyRef.Player(recipient));
      store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,0)",
                List.of(account.value().toString(), ACTOR.value().toString()));
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,0)",
                List.of(target.value().toString(), recipient.value().toString()));
            return null;
          });
      var kind = new Journal.Kind("test:ledger", "test:post");
      var engine =
          new JournalEngine(
              store,
              (actor, requested) -> ACTOR.equals(actor),
              Map.of(
                  kind,
                  Settlement.registration(
                      (intent, legs, reads) ->
                          legs.stream().anyMatch(leg -> leg.role() == PostingBatch.Role.ISSUANCE)
                              ? PostingBatch.Authority.ISSUE
                              : PostingBatch.Authority.TRANSFER)));
      var first =
          post(
              engine,
              kind,
              leg(account, 10_000),
              new PostingBatch.Leg(
                  Settlement.issuanceAccount(CAD),
                  PostingBatch.Role.ISSUANCE,
                  CAD,
                  new Money(CAD, -10_000)));
      var transfer = post(engine, kind, leg(account, -2_000), leg(target, 2_000));
      var page =
          store
              .transaction(tx -> LedgerHistory.page(tx::query, owner, account, null, 1))
              .orElseThrow();
      assertEquals(first.transactionId(), page.entries().getFirst().transactionId());
      assertEquals(0, page.entries().getFirst().denominationRevision());
      assertNotNull(page.nextCursor());

      var rebase =
          new JournalEngine(
              store,
              (actor, requested) -> true,
              Map.of(
                  RebaseSettlement.KIND,
                  new JournalEngine.Registration(
                      1,
                      (intent, reads) ->
                          new JournalEngine.Commit(
                              List.of(
                                  new Journal.Record(
                                      RebaseSettlement.FACT, 1, "USD|1|" + UUID.randomUUID())),
                              new Journal.Result(1, "TEST_REBASE", ""),
                              tx -> {}))));
      assertEquals(
          Journal.Status.COMMITTED,
          rebase
              .submit(intent(RebaseSettlement.KIND, new Journal.CanonicalRequest(1, Map.of())))
              .status());
      var appended = post(engine, kind, leg(account, -8_000), leg(target, 8_000));
      store.transaction(
          tx -> {
            tx.execute(
                "UPDATE account_catalog SET status='CLOSED',revision=2 WHERE account_id=?",
                List.of(account.value().toString()));
            var tail =
                LedgerHistory.page(tx::query, owner, account, page.nextCursor(), 2).orElseThrow();
            assertEquals(
                List.of(transfer.transactionId(), appended.transactionId()),
                tail.entries().stream().map(LedgerHistory.Entry::transactionId).toList());
            assertEquals(
                List.of(new Money(CAD, -2_000), new Money(CAD, -8_000)),
                tail.entries().stream().map(LedgerHistory.Entry::amount).toList());
            assertEquals(
                List.of(0L, 1L),
                tail.entries().stream().map(LedgerHistory.Entry::denominationRevision).toList());
            assertNull(tail.nextCursor());

            var paired = LedgerHistory.page(tx::query, other, target, null, 2).orElseThrow();
            assertEquals(transfer.transactionId(), paired.entries().getFirst().transactionId());
            assertTrue(
                LedgerHistory.page(tx::query, other, account, page.nextCursor(), 2).isEmpty());
            assertTrue(
                LedgerHistory.page(tx::query, other, target, page.nextCursor(), 2).isEmpty());
            return null;
          });
    } finally {
      store.close();
    }
  }

  @Test
  void queryPlanSeeksByAccountAndCursorWithIndexedDenominationLookup() throws Exception {
    var store = new SqliteStorage();
    try {
      store.initialize(world.toRealPath());
    } finally {
      store.close();
    }
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + world.resolve("economy/economy.sqlite"));
        var query = connection.prepareStatement("EXPLAIN QUERY PLAN " + LedgerHistory.QUERY)) {
      query.setString(1, UUID.randomUUID().toString());
      query.setString(2, "10");
      query.setString(3, "2");
      query.setString(4, UUID.randomUUID().toString());
      query.setString(5, "64");
      var plan = new ArrayList<String>();
      try (var rows = query.executeQuery()) {
        while (rows.next()) plan.add(rows.getString("detail"));
      }
      assertTrue(
          plan.stream()
              .anyMatch(
                  line ->
                      line.contains("SEARCH r USING INDEX ledger_posting_account")
                          && line.contains("(sequence,position)>")),
          plan.toString());
      assertTrue(
          plan.stream()
              .anyMatch(line -> line.contains("SEARCH d USING INDEX ledger_rebase_latest")),
          plan.toString());
      assertFalse(plan.stream().anyMatch(line -> line.contains("TEMP B-TREE")), plan.toString());
    }
  }

  private static PostingBatch.Leg leg(AccountId account, long units) {
    return new PostingBatch.Leg(
        account.value(), PostingBatch.Role.ACCOUNT, CAD, new Money(CAD, units));
  }

  private static Journal.Outcome post(
      JournalEngine engine, Journal.Kind kind, PostingBatch.Leg first, PostingBatch.Leg second) {
    var result = engine.submit(intent(kind, Settlement.request(List.of(first, second))));
    assertEquals(Journal.Status.COMMITTED, result.status(), result.result().code());
    return result;
  }

  private static Journal.Intent intent(Journal.Kind kind, Journal.CanonicalRequest request) {
    return new Journal.Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        ACTOR,
        kind,
        request,
        null,
        null);
  }
}
