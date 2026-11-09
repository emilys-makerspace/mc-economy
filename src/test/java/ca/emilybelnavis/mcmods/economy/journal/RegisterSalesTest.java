package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegisterSalesTest {
  @TempDir Path world;
  private final PlayerId seller = new PlayerId(UUID.randomUUID());
  private final PlayerId buyer = new PlayerId(UUID.randomUUID());
  private final PlayerId operator = new PlayerId(UUID.randomUUID());
  private final UUID sellerAccount = UUID.randomUUID();
  private final UUID buyerAccount = UUID.randomUUID();
  private final UUID generation = UUID.randomUUID();
  private final UUID sourceGeneration = UUID.randomUUID();
  private final TerminalPolicy.Anchor anchor =
      new TerminalPolicy.Anchor("minecraft:overworld", 7, 65, 9);
  private final String template = "exact-components-template";
  private RegisterSales.Source source =
      new RegisterSales.Source(sourceGeneration, 1, 4, template, true, "before");
  private boolean capacity = true;
  private boolean evidence = true;
  private EconomyCurrency sellerCurrency = CAD;

  @Test
  void fullBatchCapacityAndLastStockReservationThenAtomicReceiptReplay() throws Exception {
    var store = open();
    try {
      seed(store);
      var quote = quote(store);
      var reserve =
          intent(RegisterSales.Action.RESERVE, buyer, RegisterSales.reserveRequest(quote));
      var racing = intent(RegisterSales.Action.RESERVE, buyer, RegisterSales.reserveRequest(quote));
      capacity = false;
      assertEquals(
          "SALE_CAPACITY",
          engine(store, RegisterSales.Action.RESERVE).submit(reserve).result().code());
      assertEquals(1000L, balance(store, buyerAccount));
      capacity = true;
      assertEquals(
          Status.COMMITTED, engine(store, RegisterSales.Action.RESERVE).submit(reserve).status());
      assertEquals(
          "SALE_STOCK", engine(store, RegisterSales.Action.RESERVE).submit(racing).result().code());
      assertThrows(IllegalArgumentException.class, () -> quote(store));
      saved(store, reserve.operationId().value());
      var commit =
          intent(
              RegisterSales.Action.COMMIT,
              buyer,
              RegisterSales.request(reserve.operationId().value(), "source saved", "buy"));
      capacity = false;
      assertEquals(
          "SALE_CAPACITY",
          engine(store, RegisterSales.Action.COMMIT).submit(commit).result().code());
      assertEquals(1000L, balance(store, buyerAccount));
      capacity = true;
      var paid = engine(store, RegisterSales.Action.COMMIT).submit(commit);
      assertEquals(Status.COMMITTED, paid.status());
      assertEquals(paid, engine(store, RegisterSales.Action.COMMIT).submit(commit));
      assertEquals(900L, balance(store, buyerAccount));
      assertEquals(100L, balance(store, sellerAccount));
      assertEquals(
          "CAPTURED",
          store
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT state FROM ledger_holds WHERE hold_id=?",
                          List.of(commit.operationId().value().toString()),
                          1))
              .getFirst()
              .get("state"));
      assertTrue(
          store
              .transaction(
                  tx ->
                      MerchantPayments.receipt(
                          tx::query,
                          buyer,
                          commit.operationId().value(),
                          (actor, merchant, terms, reads) -> actor.equals(terms.payer())))
              .isPresent());
      assertEquals(
          Custody.State.COMMITTED,
          store
              .transaction(
                  tx -> Custody.find(tx::query, buyer, reserve.operationId().value(), false))
              .orElseThrow()
              .state());
      var delivery =
          store
              .transaction(
                  tx -> Custody.find(tx::query, buyer, reserve.operationId().value(), false))
              .orElseThrow();
      var claimAction = Custody.Action.CLAIM;
      var claimHandler =
          Custody.registration(
              claimAction,
              (a, i, current, reads) -> true,
              (current, proof, reads) -> proof.kind() == Custody.EvidenceKind.UNDELIVERED,
              null);
      var claimEngine =
          new JournalEngine(store, (actor, kind) -> true, Map.of(claimAction.kind(), claimHandler));
      var claim =
          new Intent(
              new OperationId(UUID.randomUUID()),
              new RequestId(UUID.randomUUID()),
              buyer,
              claimAction.kind(),
              Custody.request(
                  delivery.id(),
                  null,
                  new Custody.Evidence(
                      Custody.EvidenceKind.UNDELIVERED,
                      "no physical insertion",
                      delivery.terms().destination(),
                      "saved held originals"),
                  "postcommit no room"),
              null,
              null);
      assertEquals(Status.COMMITTED, claimEngine.submit(claim).status());
      assertEquals(
          1, store.transaction(tx -> Custody.claims(tx::query, buyer, null, 63)).cases().size());
      assertTrue(
          store.transaction(tx -> Custody.claims(tx::query, seller, null, 63)).cases().isEmpty());
      assertEquals(900L, balance(store, buyerAccount));
    } finally {
      store.close();
    }
  }

  @Test
  void ownerPricePermissionsCurrencyAndStaleSourceAreEnforced() throws Exception {
    var store = open();
    try {
      seed(store);
      var configure =
          RegisterSales.configureRequest(
              anchor, generation, 1, 1, template, 4, new Money(CAD, 200));
      assertEquals(
          Status.NOT_ACCEPTED,
          engine(store, RegisterSales.Action.CONFIGURE)
              .submit(intent(RegisterSales.Action.CONFIGURE, buyer, configure))
              .status());
      var sale = reserve(store);
      saved(store, sale);
      source = new RegisterSales.Source(sourceGeneration, 3, 1, template, true, "hopper changed");
      assertEquals(
          Status.DURABLY_REJECTED,
          change(store, RegisterSales.Action.COMMIT, sale, "saved", "buy").status());
      assertEquals(1000L, balance(store, buyerAccount));
      source = new RegisterSales.Source(UUID.randomUUID(), 2, 0, template, true, "replacement");
      assertEquals(
          Status.DURABLY_REJECTED,
          change(store, RegisterSales.Action.COMMIT, sale, "saved", "buy").status());
      assertEquals(
          Status.COMMITTED,
          change(store, RegisterSales.Action.CANCEL, sale, "held originals", "cancel").status());
      assertEquals(
          1, store.transaction(tx -> Custody.claims(tx::query, seller, null, 63)).cases().size());
      assertTrue(
          store.transaction(tx -> Custody.claims(tx::query, buyer, null, 63)).cases().isEmpty());
    } finally {
      store.close();
    }
  }

  @Test
  void incompatiblePrimariesRejectWithCurrencyGuidance() throws Exception {
    var store = open();
    try {
      sellerCurrency = EUR;
      seed(store);
      var error = assertThrows(IllegalArgumentException.class, () -> quote(store));
      assertEquals("SALE_CURRENCY_MISMATCH", error.getMessage());
      assertFalse(RegisterSales.guidance(error.getMessage()).isEmpty());
      assertEquals(1000L, balance(store, buyerAccount));
    } finally {
      store.close();
    }
  }

  @Test
  void sourceComponentsAndOwnerPriceRevisionRejectBeforePayment() throws Exception {
    var store = open();
    try {
      seed(store);
      source =
          new RegisterSales.Source(
              sourceGeneration, 1, 4, "different-components", true, "different");
      assertThrows(IllegalArgumentException.class, () -> quote(store));
      source = new RegisterSales.Source(sourceGeneration, 1, 4, template, true, "before");
      var sale = reserve(store);
      saved(store, sale);
      assertEquals(
          Status.COMMITTED,
          engine(store, RegisterSales.Action.CONFIGURE)
              .submit(
                  intent(
                      RegisterSales.Action.CONFIGURE,
                      seller,
                      RegisterSales.configureRequest(
                          anchor, generation, 1, 1, template, 4, new Money(CAD, 200))))
              .status());
      assertEquals(
          "SALE_PRICE_STALE",
          change(store, RegisterSales.Action.COMMIT, sale, "saved", "buy").result().code());
      assertEquals(1000L, balance(store, buyerAccount));
    } finally {
      store.close();
    }
  }

  @Test
  void receiptFailureRollsBackPaymentHoldStockStateAndDelivery() throws Exception {
    var store = open();
    try {
      seed(store);
      var sale = reserve(store);
      saved(store, sale);
      var commit =
          intent(RegisterSales.Action.COMMIT, buyer, RegisterSales.request(sale, "saved", "buy"));
      var injectedInvoice = UUID.randomUUID();
      store.transaction(
          tx ->
              tx.execute(
                  "INSERT INTO payment_invoices VALUES(?,?,?,?,?,?,?,'OPEN',NULL,NULL)",
                  List.of(
                      injectedInvoice.toString(),
                      actorKey(seller),
                      actorKey(buyer),
                      buyerAccount.toString(),
                      sellerAccount.toString(),
                      "CAD",
                      "100")));
      store.transaction(
          tx ->
              tx.execute(
                  "INSERT INTO payment_receipts VALUES(?,?,'CAPTURE')",
                  List.of(commit.operationId().value().toString(), injectedInvoice.toString())));
      assertThrows(
          RuntimeException.class, () -> engine(store, RegisterSales.Action.COMMIT).submit(commit));
      store.recover();
      assertEquals(1000L, balance(store, buyerAccount));
      assertEquals(0L, balance(store, sellerAccount));
      assertEquals(
          RegisterSales.State.SAVED,
          store
              .transaction(tx -> RegisterSales.find(tx::query, buyer, sale))
              .orElseThrow()
              .state());
      assertTrue(
          store
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT hold_id FROM ledger_holds WHERE hold_id=?",
                          List.of(commit.operationId().value().toString()),
                          1))
              .isEmpty());
      assertTrue(engine(store, RegisterSales.Action.COMMIT).lookup(commit).isEmpty());
      assertTrue(store.transaction(tx -> Custody.find(tx::query, buyer, sale, false)).isEmpty());
      store.transaction(
          tx ->
              tx.execute(
                  "DELETE FROM payment_receipts WHERE operation_id=?",
                  List.of(commit.operationId().value().toString())));
      store.transaction(
          tx ->
              tx.execute(
                  "DELETE FROM payment_invoices WHERE id=?", List.of(injectedInvoice.toString())));
      assertEquals(
          Status.COMMITTED, engine(store, RegisterSales.Action.COMMIT).submit(commit).status());
      assertEquals(900L, balance(store, buyerAccount));
    } finally {
      store.close();
    }
  }

  @Test
  void restartKeepsPaidOriginalOutcomeAndQuarantinesDelivery() throws Exception {
    var store = open();
    Intent commit;
    Outcome paid;
    UUID sale;
    try {
      seed(store);
      sale = reserve(store);
      saved(store, sale);
      commit =
          intent(RegisterSales.Action.COMMIT, buyer, RegisterSales.request(sale, "saved", "buy"));
      paid = engine(store, RegisterSales.Action.COMMIT).submit(commit);
    } finally {
      store.close();
    }
    var reopened = open();
    try {
      assertTrue(RegisterSales.reconcileBatch(reopened, RegisterSaleSettlement::returnOriginals));
      assertTrue(Custody.reconcileBatch(reopened));
      assertEquals(paid, engine(reopened, RegisterSales.Action.COMMIT).submit(commit));
      assertEquals(900L, balance(reopened, buyerAccount));
      assertEquals(
          Custody.State.QUARANTINED,
          reopened
              .transaction(tx -> Custody.find(tx::query, buyer, sale, false))
              .orElseThrow()
              .state());
    } finally {
      reopened.close();
    }
  }

  @Test
  void provenCancellationRestockCreatesNoDuplicateSellerClaim() throws Exception {
    var store = open();
    try {
      seed(store);
      var sale = reserve(store);
      saved(store, sale);
      source =
          new RegisterSales.Source(sourceGeneration, 3, 5, template, true, "restocked fingerprint");
      assertEquals(
          Status.COMMITTED,
          change(store, RegisterSales.Action.CANCEL, sale, "restocked", "cancel").status());
      assertTrue(
          store.transaction(tx -> Custody.claims(tx::query, seller, null, 63)).cases().isEmpty());
      assertEquals(5, quote(store).source().quantity());
      assertEquals(1000L, balance(store, buyerAccount));
    } finally {
      store.close();
    }
  }

  @Test
  void interruptedReservedGoodsBecomeSellerReviewWithoutPayment() throws Exception {
    var store = open();
    try {
      seed(store);
      var sale = reserve(store);
      saved(store, sale);
      assertTrue(RegisterSales.reconcileBatch(store, RegisterSaleSettlement::returnOriginals));
      assertEquals(
          RegisterSales.State.QUARANTINED,
          store
              .transaction(tx -> RegisterSales.find(tx::query, seller, sale))
              .orElseThrow()
              .state());
      assertTrue(
          store.transaction(tx -> Custody.claims(tx::query, seller, null, 63)).cases().isEmpty());
      assertEquals(1000L, balance(store, buyerAccount));
      assertThrows(IllegalArgumentException.class, () -> quote(store));
    } finally {
      store.close();
    }
  }

  @Test
  void hopperChangeBeforePhysicalReservationCanCancelWithoutInventingGoods() throws Exception {
    var store = open();
    try {
      seed(store);
      var sale = reserve(store);
      source =
          new RegisterSales.Source(sourceGeneration, 2, 3, template, true, "hopper removed one");
      assertEquals(
          Status.COMMITTED,
          change(
                  store,
                  RegisterSales.Action.CANCEL,
                  sale,
                  "verified reservation never removed stock",
                  "cancel stale preview")
              .status());
      assertTrue(
          store.transaction(tx -> Custody.claims(tx::query, seller, null, 63)).cases().isEmpty());
      assertEquals(1000L, balance(store, buyerAccount));
    } finally {
      store.close();
    }
  }

  private SqliteStorage open() throws Exception {
    var store = new SqliteStorage();
    store.initialize(world.toRealPath());
    return store;
  }

  @Test
  void creditCommitHasAnExactBindingAndCannotFallBackToAccountDebit() throws Exception {
    var store = open();
    try {
      seed(store);
      var sale = reserve(store);
      saved(store, sale);
      var request =
          RegisterSales.creditRequest(sale, "source saved", "credit purchase", "frozen-credit-v1");
      var intent = intent(RegisterSales.Action.COMMIT, buyer, request);
      assertEquals(
          "SALE_CREDIT_COMPOSER_REQUIRED",
          engine(store, RegisterSales.Action.COMMIT).submit(intent).result().code());
      assertEquals(1000, balance(store, buyerAccount));
      assertEquals(
          RegisterSales.State.SAVED,
          store
              .transaction(tx -> RegisterSales.find(tx::query, buyer, sale))
              .orElseThrow()
              .state());
      assertThrows(
          IllegalArgumentException.class,
          () -> RegisterSales.creditRequest(sale, "proof", "purchase", ""));
      assertThrows(
          IllegalArgumentException.class,
          () -> RegisterSales.creditRequest(sale, "proof", "purchase", "x".repeat(2049)));
    } finally {
      store.close();
    }
  }

  @Test
  void matchingFacilityCurrencyCanQuoteAndReserveAcrossDifferentBuyerPrimaryCurrency()
      throws Exception {
    var store = open();
    try {
      sellerCurrency = CHF;
      seed(store);
      assertThrows(IllegalArgumentException.class, () -> quote(store));
      var quote =
          store.transaction(
              tx ->
                  RegisterSales.quote(
                      tx::query,
                      store.snapshot().worldId(),
                      buyer,
                      anchor,
                      generation,
                      1,
                      (below, requested, reads) -> source,
                      true));
      var request =
          intent(RegisterSales.Action.RESERVE, buyer, RegisterSales.reserveRequest(quote, true));
      assertEquals(
          Status.COMMITTED, engine(store, RegisterSales.Action.RESERVE).submit(request).status());
      saved(store, request.operationId().value());
      var called = new java.util.concurrent.atomic.AtomicBoolean();
      var action = RegisterSales.Action.COMMIT;
      var registration =
          RegisterSales.registration(
              action,
              store.snapshot().worldId(),
              (a, i, current, reads) -> true,
              (a, reads) -> false,
              (a, i, current, reads) -> true,
              (below, item, reads) -> source,
              (a, i, current, proof, reads) -> true,
              (i, current, reads) -> {
                assertEquals("frozen-credit-v1", i.request().fields().get("credit_request"));
                called.set(true);
                return Custody.committedDelivery(
                    i,
                    current.id(),
                    buyer,
                    RegisterSaleSettlement.items(current),
                    "fixture-credit-stock",
                    Journal.actorKey(buyer),
                    reads);
              },
              (i, current, uncertain, reads) -> null);
      var engine =
          new JournalEngine(store, (actor, kind) -> true, Map.of(action.kind(), registration));
      var commit =
          intent(
              action,
              buyer,
              RegisterSales.creditRequest(
                  request.operationId().value(), "proof", "credit", "frozen-credit-v1"));
      var result = engine.submit(commit);
      assertEquals(Status.COMMITTED, result.status());
      assertTrue(called.get());
      called.set(false);
      assertEquals(result, engine.submit(commit));
      assertFalse(called.get());
    } finally {
      store.close();
    }
  }

  private RegisterSales.Quote quote(SqliteStorage store) {
    return store.transaction(
        tx ->
            RegisterSales.quote(
                tx::query,
                store.snapshot().worldId(),
                buyer,
                anchor,
                generation,
                1,
                (below, requested, reads) -> source));
  }

  private UUID reserve(SqliteStorage store) {
    var request =
        intent(RegisterSales.Action.RESERVE, buyer, RegisterSales.reserveRequest(quote(store)));
    assertEquals(
        Status.COMMITTED, engine(store, RegisterSales.Action.RESERVE).submit(request).status());
    return request.operationId().value();
  }

  private void saved(SqliteStorage store, UUID sale) {
    source = new RegisterSales.Source(sourceGeneration, 2, 0, template, true, "removed");
    assertEquals(
        Status.COMMITTED,
        change(store, RegisterSales.Action.SAVED, sale, "source-file-proof", "reserved").status());
  }

  private JournalEngine engine(SqliteStorage store, RegisterSales.Action action) {
    RegisterSales.SourceReader observer = (below, requested, reads) -> source;
    return new JournalEngine(
        store,
        (actor, kind) -> true,
        Map.of(
            action.kind(),
            RegisterSales.registration(
                action,
                store.snapshot().worldId(),
                (a, intent, current, reads) -> true,
                (actor, reads) -> actor.equals(operator),
                (a, intent, current, reads) -> capacity,
                observer,
                (a, intent, current, proof, reads) -> evidence,
                RegisterSaleSettlement::checkout,
                (intent, current, uncertain, reads) ->
                    RegisterSaleSettlement.returnOriginals(
                        intent, current, uncertain, reads, observer))));
  }

  private Outcome change(
      SqliteStorage store, RegisterSales.Action action, UUID sale, String proof, String reason) {
    return engine(store, action)
        .submit(intent(action, buyer, RegisterSales.request(sale, proof, reason)));
  }

  private Intent intent(RegisterSales.Action action, ActorId actor, CanonicalRequest request) {
    return new Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        actor,
        action.kind(),
        request,
        null,
        null);
  }

  private long balance(SqliteStorage store, UUID account) {
    return store.transaction(
        tx ->
            Holds.available(
                tx::query, account, account.equals(sellerAccount) ? sellerCurrency : CAD));
  }

  private void seed(SqliteStorage store) {
    store.transaction(
        tx -> {
          tx.execute(
              "INSERT INTO account_catalog VALUES(?,?,'BANK',?,'OPEN',1,1)",
              List.of(sellerAccount.toString(), seller.value().toString(), sellerCurrency.name()));
          tx.execute(
              "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
              List.of(buyerAccount.toString(), buyer.value().toString()));
          return null;
        });
    var kind = new Kind("economy:sale_fixture", "economy:fund");
    var funding =
        new JournalEngine(
            store,
            (a, k) -> true,
            Map.of(
                kind,
                Settlement.registration((intent, legs, reads) -> PostingBatch.Authority.ISSUE)));
    funding.submit(
        new Intent(
            new OperationId(UUID.randomUUID()),
            new RequestId(UUID.randomUUID()),
            buyer,
            kind,
            Settlement.request(
                List.of(
                    new PostingBatch.Leg(
                        buyerAccount, PostingBatch.Role.ACCOUNT, CAD, new Money(CAD, 1000)),
                    new PostingBatch.Leg(
                        Settlement.issuanceAccount(CAD),
                        PostingBatch.Role.ISSUANCE,
                        CAD,
                        new Money(CAD, -1000)))),
            null,
            null));
    var place = TerminalPolicy.Action.PLACE;
    new JournalEngine(
            store,
            (a, k) -> true,
            Map.of(
                place.kind(),
                TerminalPolicy.registration(
                    place,
                    store.snapshot().worldId(),
                    Map.of(),
                    (a, i, current, reads) -> true,
                    (a, reads) -> false)))
        .submit(
            new Intent(
                new OperationId(UUID.randomUUID()),
                new RequestId(UUID.randomUUID()),
                seller,
                place.kind(),
                TerminalPolicy.placeRequest(
                    anchor, generation, seller, TerminalPolicy.Kind.REGISTER, null, 0),
                null,
                null));
    assertEquals(
        Status.COMMITTED,
        engine(store, RegisterSales.Action.CONFIGURE)
            .submit(
                intent(
                    RegisterSales.Action.CONFIGURE,
                    seller,
                    RegisterSales.configureRequest(
                        anchor, generation, 1, 0, template, 4, new Money(sellerCurrency, 100))))
            .status());
  }
}
