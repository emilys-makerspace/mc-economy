package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD;
import static ca.emilybelnavis.mcmods.economy.journal.CashIssuance.Action.*;
import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Role.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CashIssuanceTest {
  @TempDir Path world;
  private static final String ITEM = "economy:cad_1";
  private final PlayerId actor = new PlayerId(UUID.randomUUID());
  private final UUID bank = UUID.randomUUID();
  private final UUID backing = UUID.randomUUID();
  private boolean proof = true;
  private boolean allowed = true;

  @Test
  void splitMergeAndDuplicateCopiesConserveRecognizedQuantity() throws Exception {
    var store = open();
    try {
      seed(store);
      var issue = issue(store, 10);
      var issued = engine(store, ISSUE, financial(issue)).submit(issue.intent());
      assertEquals(Status.COMMITTED, issued.status());
      assertEquals(issued, engine(store, ISSUE, financial(issue)).submit(issue.intent()));
      assertEquals(9_000L, balance(store, bank));
      var id = issue.intent().operationId().value();
      var tooMany = redeem(store, List.of(claim(store, id, 6), claim(store, id, 6)), 12);
      assertEquals(
          "CASH_QUANTITY",
          engine(store, REDEEM, financial(tooMany)).submit(tooMany.intent()).result().code());
      assertEquals(10, lot(store, id).remaining());

      var split = redeem(store, List.of(claim(store, id, 3)), 3);
      var redeemed = engine(store, REDEEM, financial(split)).submit(split.intent());
      assertEquals(Status.COMMITTED, redeemed.status());
      var retry =
          new Intent(
              split.intent().operationId(),
              new RequestId(UUID.randomUUID()),
              actor,
              REDEEM.kind(),
              split.intent().request(),
              null,
              null);
      assertEquals(redeemed, engine(store, REDEEM, financial(split)).submit(retry));
      assertEquals(7, lot(store, id).remaining());
      var duplicate = redeem(store, List.of(claim(store, id, 10)), 10);
      assertEquals(
          "CASH_QUANTITY",
          engine(store, REDEEM, financial(duplicate)).submit(duplicate.intent()).result().code());

      var merged = redeem(store, List.of(claim(store, id, 4), claim(store, id, 3)), 7);
      assertEquals(
          Status.COMMITTED,
          engine(store, REDEEM, financial(merged)).submit(merged.intent()).status());
      assertEquals(0, lot(store, id).remaining());
      var spentCopy = redeem(store, List.of(claim(store, id, 1)), 1);
      assertEquals(
          "CASH_QUANTITY",
          engine(store, REDEEM, financial(spentCopy)).submit(spentCopy.intent()).result().code());
      assertEquals(10_000L, balance(store, bank));
      assertEquals(0L, balance(store, backing));
      var changed =
          new Intent(
              issue.intent().operationId(),
              new RequestId(UUID.randomUUID()),
              actor,
              issue.intent().kind(),
              CashIssuance.issueRequest(ITEM, 9, "custody-proof", issue.original()),
              null,
              null);
      assertEquals(
          "OPERATION_CONFLICT",
          engine(store, ISSUE, financial(issue)).submit(changed).result().code());
    } finally {
      store.close();
    }
  }

  @Test
  void restartQuarantinesOutstandingLotsUntilVerifiedReconciliation() throws Exception {
    var store = open();
    UUID id;
    Operation issued;
    try {
      seed(store);
      issued = issue(store, 2);
      assertEquals(
          Status.COMMITTED,
          engine(store, ISSUE, financial(issued)).submit(issued.intent()).status());
      id = issued.intent().operationId().value();
    } finally {
      store.close();
    }
    var reopened = open();
    try {
      assertTrue(CashIssuance.reconcileBatch(reopened));
      assertEquals(CashIssuance.State.QUARANTINED, lot(reopened, id).state());
      var redeem = redeem(reopened, List.of(claim(reopened, id, 2)), 2);
      assertEquals(
          "CASH_QUARANTINED",
          engine(reopened, REDEEM, financial(redeem)).submit(redeem.intent()).result().code());
      var reconcile =
          new Intent(
              new OperationId(UUID.randomUUID()),
              new RequestId(UUID.randomUUID()),
              actor,
              RECONCILE.kind(),
              CashIssuance.reconcileRequest(
                  id, "observed exact saved quantities", "paired restore verified"),
              null,
              null);
      proof = false;
      assertEquals(
          "CASH_EVIDENCE", engine(reopened, RECONCILE, null).submit(reconcile).result().code());
      proof = true;
      assertEquals(Status.COMMITTED, engine(reopened, RECONCILE, null).submit(reconcile).status());
      assertEquals(
          Status.COMMITTED,
          engine(reopened, REDEEM, financial(redeem)).submit(redeem.intent()).status());
      assertEquals(10_000L, balance(reopened, bank));
      var foreign = new CashIssuance.Claim(new WorldId(UUID.randomUUID()), id, ITEM, 1);
      var forged = redeem(reopened, List.of(foreign), 1);
      assertEquals(
          "CASH_WORLD",
          engine(reopened, REDEEM, financial(forged)).submit(forged.intent()).result().code());
    } finally {
      reopened.close();
    }
  }

  @Test
  void issuerAndCustodyAuthorityAreRequiredAndFinancialFailureRollsBack() throws Exception {
    var store = open();
    try {
      seed(store);
      var issue = issue(store, 5);
      allowed = false;
      assertEquals(
          "CASH_ACCESS",
          engine(store, ISSUE, financial(issue)).submit(issue.intent()).result().code());
      allowed = true;
      proof = false;
      assertEquals(
          "CASH_EVIDENCE",
          engine(store, ISSUE, financial(issue)).submit(issue.intent()).result().code());
      proof = true;
      var financial = financial(issue);
      CashIssuance.Financial failing =
          (intent, lots, reads) -> {
            var base = (JournalEngine.Commit) financial.prepare(intent, lots, reads);
            return new JournalEngine.Commit(
                base.records(),
                base.result(),
                tx -> {
                  base.projections().apply(tx);
                  throw new IllegalStateException("injected issuance failure");
                });
          };
      assertThrows(
          IllegalStateException.class, () -> engine(store, ISSUE, failing).submit(issue.intent()));
      assertEquals(10_000L, balance(store, bank));
      assertEquals(0L, balance(store, backing));
      assertTrue(
          store
              .transaction(
                  tx ->
                      CashIssuance.find(
                          tx::query,
                          store.snapshot().worldId(),
                          issue.intent().operationId().value()))
              .isEmpty());
      assertTrue(engine(store, ISSUE, financial).lookup(issue.intent()).isEmpty());
      assertEquals(
          Status.COMMITTED, engine(store, ISSUE, financial).submit(issue.intent()).status());

      var redeem = redeem(store, List.of(claim(store, issue.intent().operationId().value(), 5)), 5);
      var redeemFinancial = financial(redeem);
      CashIssuance.Financial failedRedeem =
          (intent, lots, reads) -> {
            var base = (JournalEngine.Commit) redeemFinancial.prepare(intent, lots, reads);
            return new JournalEngine.Commit(
                base.records(),
                base.result(),
                tx -> {
                  base.projections().apply(tx);
                  throw new IllegalStateException("injected redemption failure");
                });
          };
      assertThrows(
          IllegalStateException.class,
          () -> engine(store, REDEEM, failedRedeem).submit(redeem.intent()));
      assertEquals(5, lot(store, issue.intent().operationId().value()).remaining());
      assertEquals(9_500L, balance(store, bank));
      assertEquals(
          Status.COMMITTED,
          engine(store, REDEEM, redeemFinancial).submit(redeem.intent()).status());
    } finally {
      store.close();
    }
  }

  @Test
  void underfundedIssueRetainsIntentAndStartupWorkIsBounded() throws Exception {
    var store = open();
    try {
      seed(store);
      var issue = issue(store, 106);
      assertEquals(
          Status.NOT_ACCEPTED,
          engine(store, ISSUE, financial(issue)).submit(issue.intent()).status());
      var conflict =
          new Intent(
              issue.intent().operationId(),
              new RequestId(UUID.randomUUID()),
              actor,
              ISSUE.kind(),
              CashIssuance.issueRequest(ITEM, 100, "custody-proof", issue.original()),
              null,
              null);
      assertEquals(
          "OPERATION_CONFLICT",
          engine(store, ISSUE, financial(issue)).submit(conflict).result().code());
      for (int index = 0; index < 17; index++) {
        var small = issue(store, 1);
        assertEquals(
            Status.COMMITTED,
            engine(store, ISSUE, financial(small)).submit(small.intent()).status());
      }
      assertFalse(CashIssuance.reconcileBatch(store));
      assertTrue(CashIssuance.reconcileBatch(store));
    } finally {
      store.close();
    }
  }

  private record Operation(Intent intent, Intent original) {}

  private Operation issue(SqliteStorage store, int count) {
    var original = original(bank, backing, count * 100L);
    return new Operation(
        new Intent(
            original.operationId(),
            new RequestId(UUID.randomUUID()),
            actor,
            ISSUE.kind(),
            CashIssuance.issueRequest(ITEM, count, "custody-proof", original),
            null,
            null),
        original);
  }

  private Operation redeem(SqliteStorage store, List<CashIssuance.Claim> claims, int count) {
    var original = original(backing, bank, count * 100L);
    return new Operation(
        new Intent(
            original.operationId(),
            new RequestId(UUID.randomUUID()),
            actor,
            REDEEM.kind(),
            CashIssuance.redeemRequest(claims, "custody-proof", original),
            null,
            null),
        original);
  }

  private Intent original(UUID source, UUID target, long units) {
    return new Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        actor,
        new Kind("economy:cash_test", "economy:transfer"),
        Settlement.request(
            List.of(
                new PostingBatch.Leg(source, ACCOUNT, CAD, new Money(CAD, -units)),
                new PostingBatch.Leg(target, ACCOUNT, CAD, new Money(CAD, units)))),
        null,
        null);
  }

  private CashIssuance.Claim claim(SqliteStorage store, UUID lot, int count) {
    return new CashIssuance.Claim(store.snapshot().worldId(), lot, ITEM, count);
  }

  private CashIssuance.Financial financial(Operation operation) {
    return CashIssuance.financial(
        operation.original(),
        Settlement.registration((intent, legs, reads) -> PostingBatch.Authority.TRANSFER));
  }

  private JournalEngine engine(
      SqliteStorage store, CashIssuance.Action action, CashIssuance.Financial financial) {
    return new JournalEngine(
        store,
        (a, kind) -> a.equals(actor) && kind.equals(action.kind()),
        Map.of(
            action.kind(),
            CashIssuance.registration(
                action,
                store.snapshot().worldId(),
                (a, intent, lots, reads) ->
                    allowed && lots.stream().allMatch(lot -> lot.item().equals(ITEM)),
                (a, intent, lots, evidence, reads) -> proof,
                financial)));
  }

  private CashIssuance.Lot lot(SqliteStorage store, UUID id) {
    return store
        .transaction(tx -> CashIssuance.find(tx::query, store.snapshot().worldId(), id))
        .orElseThrow();
  }

  private SqliteStorage open() throws Exception {
    var store = new SqliteStorage();
    store.initialize(world.toRealPath());
    return store;
  }

  private long balance(SqliteStorage store, UUID account) {
    return store.transaction(tx -> Holds.available(tx::query, account, CAD));
  }

  private void seed(SqliteStorage store) {
    store.transaction(
        tx -> {
          for (var account : List.of(bank, backing))
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,0)",
                List.of(account.toString(), UUID.randomUUID().toString()));
          return null;
        });
    var kind = new Kind("economy:cash_test", "economy:issue");
    var engine =
        new JournalEngine(
            store,
            (a, k) -> true,
            Map.of(
                kind,
                Settlement.registration((intent, legs, reads) -> PostingBatch.Authority.ISSUE)));
    assertEquals(
        Status.COMMITTED,
        engine
            .submit(
                new Intent(
                    new OperationId(UUID.randomUUID()),
                    new RequestId(UUID.randomUUID()),
                    actor,
                    kind,
                    Settlement.request(
                        List.of(
                            new PostingBatch.Leg(bank, ACCOUNT, CAD, new Money(CAD, 10_000)),
                            new PostingBatch.Leg(
                                Settlement.issuanceAccount(CAD),
                                ISSUANCE,
                                CAD,
                                new Money(CAD, -10_000)))),
                    null,
                    null))
            .status());
  }
}
