package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD;
import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.MerchantPayments.Action.*;
import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Role.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MerchantPaymentsTest {
  @TempDir Path world;
  private final PlayerId merchant = new PlayerId(UUID.randomUUID());
  private final PlayerId payer = new PlayerId(UUID.randomUUID());
  private final PlayerId operator = new PlayerId(UUID.randomUUID());
  private final UUID source = UUID.randomUUID();
  private final UUID target = UUID.randomUUID();
  private final Money price = new Money(CAD, 3_000);
  private long millis = 1_000;
  private boolean live = true;
  private final Clock wall =
      new Clock() {
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
          return Clock.fixed(instant(), zone);
        }

        public Instant instant() {
          return Instant.ofEpochMilli(millis);
        }
      };

  @Test
  void deadlineSurvivesRestartAndBackwardClockBeforeRelease() throws Exception {
    UUID invoice;
    var store = open();
    try {
      seed(store);
      invoice = authorize(store);
    } finally {
      store.close();
    }
    var reopened = open();
    try {
      millis += MerchantPayments.AUTHORIZATION_LIFETIME.toMillis();
      assertEquals(
          "PAYMENT_DEADLINE", submit(reopened, CAPTURE, merchant, invoice).result().code());
      assertEquals("AUTHORIZED", state(reopened, invoice));
      assertEquals(7_000L, available(reopened, source));
      millis -= 1;
      assertEquals(
          "PAYMENT_DEADLINE", submit(reopened, CAPTURE, merchant, invoice).result().code());
      MerchantPayments.expireBatch(reopened, wall);
      assertEquals("EXPIRED", state(reopened, invoice));
      assertEquals(10_000L, available(reopened, source));
    } finally {
      reopened.close();
    }
  }

  @Test
  void captureRollsBackOnReceiptFailureAndRefundBindsOriginalIntent() throws Exception {
    var store = open();
    try {
      seed(store);
      var invoice = authorize(store);
      var capture = intent(CAPTURE, merchant, MerchantPayments.invoiceRequest(invoice));
      live = false;
      assertEquals(Status.NOT_ACCEPTED, engine(store, CAPTURE).submit(capture).status());
      live = true;
      store.transaction(
          tx ->
              tx.execute(
                  """
                    INSERT INTO payment_receipts VALUES(?,?,'CAPTURE')
                    """,
                  List.of(capture.operationId().value().toString(), invoice.toString())));
      assertThrows(RuntimeException.class, () -> engine(store, CAPTURE).submit(capture));
      store.recover();
      assertEquals("AUTHORIZED", state(store, invoice));
      assertEquals(0L, available(store, target));
      assertEquals(7_000L, available(store, source));
      assertTrue(engine(store, CAPTURE).lookup(capture).isEmpty());
      store.transaction(
          tx ->
              tx.execute(
                  "DELETE FROM payment_receipts WHERE operation_id=?",
                  List.of(capture.operationId().value().toString())));
      var captured = engine(store, CAPTURE).submit(capture);
      assertEquals(Status.COMMITTED, captured.status());
      assertEquals(captured, engine(store, CAPTURE).submit(capture));
      assertEquals(3_000L, available(store, target));
      assertTrue(
          store
              .transaction(
                  tx ->
                      MerchantPayments.receipt(
                          tx::query,
                          payer,
                          capture.operationId().value(),
                          (actor, seller, terms, reads) -> actor.equals(terms.payer())))
              .isPresent());

      fund(store, target, -3_000);
      var refund = intent(REFUND, operator, MerchantPayments.invoiceRequest(invoice));
      var deferred = engine(store, REFUND).submit(refund);
      assertEquals(Status.NOT_ACCEPTED, deferred.status());
      assertEquals("INSUFFICIENT_FUNDS", deferred.result().code());
      assertTrue(engine(store, REFUND).lookup(refund).isEmpty());
      var conflict =
          new Intent(
              refund.operationId(),
              new RequestId(UUID.randomUUID()),
              operator,
              REFUND.kind(),
              MerchantPayments.invoiceRequest(UUID.randomUUID()),
              null,
              null);
      assertEquals("OPERATION_CONFLICT", engine(store, REFUND).submit(conflict).result().code());
      fund(store, target, 3_000);
      var refunded = engine(store, REFUND).submit(refund);
      assertEquals(Status.COMMITTED, refunded.status());
      assertEquals(refunded, engine(store, REFUND).submit(refund));
      assertEquals("REFUNDED", state(store, invoice));
      assertEquals(10_000L, available(store, source));
      assertEquals(0L, available(store, target));
    } finally {
      store.close();
    }
  }

  @Test
  void diagnosticsAreScopedBoundedAndSurviveRestart() throws Exception {
    var store = open();
    UUID first;
    UUID second;
    Intent capture;
    try {
      seed(store);
      first = authorize(store);
      second = authorize(store);
      capture = intent(CAPTURE, merchant, MerchantPayments.invoiceRequest(first));
      assertEquals(Status.COMMITTED, engine(store, CAPTURE).submit(capture).status());
      millis += MerchantPayments.AUTHORIZATION_LIFETIME.toMillis();
      var denied = new PlayerId(UUID.randomUUID());
      assertTrue(page(store, denied, null, 1, false).invoices().isEmpty());
      assertThrows(IllegalArgumentException.class, () -> page(store, payer, null, 64, false));
      var owner = page(store, payer, null, 1, false);
      assertEquals(1, owner.invoices().size());
      assertNotNull(owner.nextCursor());
      var next = page(store, payer, owner.nextCursor(), 1, false);
      assertEquals(1, next.invoices().size());
      assertNull(next.nextCursor());
      assertNotEquals(owner.invoices().getFirst().id(), next.invoices().getFirst().id());
      assertEquals(2, page(store, merchant, null, 63, false).invoices().size());
      assertEquals(2, page(store, operator, null, 63, false).invoices().size());
      var overdue = page(store, payer, null, 63, true);
      assertEquals(second, overdue.invoices().getFirst().id());
      assertEquals("ACTIVE", overdue.invoices().getFirst().holdState());
      assertEquals("NO_DURABLE_OUTCOME", overdue.invoices().getFirst().releaseResult());
      var op = capture.operationId().value();
      assertTrue(audit(store, denied, first, op).isEmpty());
      assertTrue(audit(store, payer, second, op).isEmpty());
      var entry = audit(store, payer, first, op).orElseThrow();
      assertTrue(entry.receipt());
      assertNotNull(entry.transaction());
      assertEquals("PAYMENT_CAPTURE", entry.resultCode());
    } finally {
      store.close();
    }
    var reopened = open();
    try {
      assertEquals(2, page(reopened, payer, null, 63, false).invoices().size());
      assertEquals(
          capture.operationId().value(),
          audit(reopened, operator, first, capture.operationId().value())
              .orElseThrow()
              .operation());
      MerchantPayments.expireBatch(reopened, wall);
      var expired =
          page(reopened, payer, null, 63, false).invoices().stream()
              .filter(invoice -> invoice.id().equals(second))
              .findFirst()
              .orElseThrow();
      assertEquals("EXPIRED", expired.state());
      assertEquals("RELEASED", expired.holdState());
      assertEquals("COMMITTED/PAYMENT_EXPIRE", expired.releaseResult());
    } finally {
      reopened.close();
    }
  }

  @Test
  void terminalOrdersHaveOneWinnerAndConserveFunds() throws Exception {
    var store = open();
    try {
      seed(store);
      for (boolean captureFirst : List.of(true, false)) {
        var invoice = authorize(store);
        var winner =
            submit(
                store, captureFirst ? CAPTURE : CANCEL, captureFirst ? merchant : payer, invoice);
        assertEquals(Status.COMMITTED, winner.status());
        assertEquals(
            Status.DURABLY_REJECTED,
            submit(store, captureFirst ? CANCEL : CAPTURE, captureFirst ? payer : merchant, invoice)
                .status());
        millis += MerchantPayments.AUTHORIZATION_LIFETIME.toMillis();
        MerchantPayments.expireBatch(store, wall);
        assertEquals(captureFirst ? "CAPTURED" : "CANCELLED", state(store, invoice));
        assertEquals(10_000L, available(store, source) + available(store, target));
        assertEquals(
            0L, store.<Long>transaction(tx -> Holds.reserved(tx::query, source, CAD)).longValue());
      }
      var expiring = authorize(store);
      millis += MerchantPayments.AUTHORIZATION_LIFETIME.toMillis();
      MerchantPayments.expireBatch(store, wall);
      assertEquals(Status.DURABLY_REJECTED, submit(store, CAPTURE, merchant, expiring).status());
      assertEquals(Status.DURABLY_REJECTED, submit(store, CANCEL, payer, expiring).status());
      assertEquals("EXPIRED", state(store, expiring));
      assertEquals(10_000L, available(store, source) + available(store, target));
    } finally {
      store.close();
    }
  }

  @Test
  void lostCaptureResponseReturnsOriginalReceiptAfterRestart() throws Exception {
    var store = open();
    Intent capture;
    Outcome original;
    MerchantPayments.ReceiptView receipt;
    try {
      seed(store);
      var invoice = authorize(store);
      capture = intent(CAPTURE, merchant, MerchantPayments.invoiceRequest(invoice));
      original = engine(store, CAPTURE).submit(capture);
      receipt =
          store
              .transaction(
                  tx ->
                      MerchantPayments.receipt(
                          tx::query,
                          payer,
                          capture.operationId().value(),
                          (actor, seller, terms, reads) -> actor.equals(terms.payer())))
              .orElseThrow();
    } finally {
      store.close();
    }
    var reopened = open();
    try {
      var retry =
          new Intent(
              capture.operationId(),
              new RequestId(UUID.randomUUID()),
              capture.actor(),
              capture.kind(),
              capture.request(),
              null,
              null);
      assertEquals(original, engine(reopened, CAPTURE).submit(retry));
      assertEquals(
          receipt,
          reopened
              .transaction(
                  tx ->
                      MerchantPayments.receipt(
                          tx::query,
                          payer,
                          capture.operationId().value(),
                          (actor, seller, terms, reads) -> actor.equals(terms.payer())))
              .orElseThrow());
      assertTrue(
          reopened
              .transaction(
                  tx ->
                      MerchantPayments.receipt(
                          tx::query,
                          operator,
                          capture.operationId().value(),
                          (actor, seller, terms, reads) -> actor.equals(terms.payer())))
              .isEmpty());
      assertEquals(7_000L, available(reopened, source));
      assertEquals(3_000L, available(reopened, target));
    } finally {
      reopened.close();
    }
  }

  @Test
  void overdueCatchUpReleasesAtMostSixteenPerBatch() throws Exception {
    var store = open();
    try {
      seed(store);
      fund(store, source, 100_000);
      for (int index = 0; index < 33; index++) authorize(store);
      assertEquals(11_000L, available(store, source));
      millis += MerchantPayments.AUTHORIZATION_LIFETIME.toMillis();
      for (int remaining : List.of(17, 1, 0)) {
        MerchantPayments.expireBatch(store, wall);
        assertEquals(remaining, page(store, operator, null, 63, true).invoices().size());
        assertEquals(110_000L - remaining * 3_000L, available(store, source));
      }
      MerchantPayments.expireBatch(store, wall);
      assertEquals(110_000L, available(store, source));
      assertEquals(0L, available(store, target));
    } finally {
      store.close();
    }
  }

  private PaymentDiagnostics.Page page(
      SqliteStorage store, ActorId actor, UUID after, int limit, boolean overdue) {
    return store.transaction(
        tx ->
            PaymentDiagnostics.page(
                tx::query, actor, (a, reads) -> a.equals(operator), after, limit, overdue, wall));
  }

  private Optional<PaymentDiagnostics.Audit> audit(
      SqliteStorage store, ActorId actor, UUID invoice, UUID operation) {
    return store.transaction(
        tx ->
            PaymentDiagnostics.audit(
                tx::query, actor, (a, reads) -> a.equals(operator), invoice, operation));
  }

  private SqliteStorage open() throws Exception {
    var store = new SqliteStorage();
    store.initialize(world.toRealPath());
    return store;
  }

  private void seed(SqliteStorage store) {
    store.transaction(
        tx -> {
          for (var account : List.of(source, target))
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,0)",
                List.of(account.toString(), UUID.randomUUID().toString()));
          return null;
        });
    fund(store, source, 10_000);
  }

  private UUID authorize(SqliteStorage store) {
    assertEquals(
        Status.COMMITTED,
        engine(store, GRANT)
            .submit(intent(GRANT, operator, MerchantPayments.merchantRequest(merchant)))
            .status());
    var opened =
        engine(store, OPEN)
            .submit(
                intent(
                    OPEN,
                    merchant,
                    new MerchantPayments.Terms(payer, source, target, price).request()));
    assertEquals(Status.COMMITTED, opened.status());
    var id = opened.operationId().value();
    assertEquals(Status.COMMITTED, submit(store, AUTHORIZE, payer, id).status());
    return id;
  }

  private void fund(SqliteStorage store, UUID account, long units) {
    var kind = new Kind("economy:payment_test", "economy:adjust");
    var engine =
        new JournalEngine(
            store,
            (actor, requested) -> actor.equals(operator),
            Map.of(
                kind,
                Settlement.registration(
                    (intent, legs, reads) ->
                        units > 0 ? PostingBatch.Authority.ISSUE : PostingBatch.Authority.REDEEM)));
    var request =
        Settlement.request(
            List.of(
                new PostingBatch.Leg(account, ACCOUNT, CAD, new Money(CAD, units)),
                new PostingBatch.Leg(
                    Settlement.issuanceAccount(CAD), ISSUANCE, CAD, new Money(CAD, -units))));
    assertEquals(
        Status.COMMITTED,
        engine
            .submit(
                new Intent(
                    new OperationId(UUID.randomUUID()),
                    new RequestId(UUID.randomUUID()),
                    operator,
                    kind,
                    request,
                    null,
                    null))
            .status());
  }

  private JournalEngine engine(SqliteStorage store, MerchantPayments.Action action) {
    return new JournalEngine(
        store,
        (actor, kind) -> kind.equals(action.kind()),
        Map.of(
            action.kind(),
            MerchantPayments.registration(
                action,
                (a, actor, seller, terms, reads) ->
                    live
                        && switch (a) {
                          case GRANT, REVOKE -> actor.equals(operator);
                          case REFUND -> actor.equals(operator) || actor.equals(seller);
                          default -> true;
                        },
                wall)));
  }

  private Outcome submit(
      SqliteStorage store, MerchantPayments.Action action, ActorId actor, UUID invoice) {
    return engine(store, action)
        .submit(intent(action, actor, MerchantPayments.invoiceRequest(invoice)));
  }

  private static Intent intent(
      MerchantPayments.Action action, ActorId actor, CanonicalRequest request) {
    return new Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        actor,
        action.kind(),
        request,
        null,
        null);
  }

  private static long available(SqliteStorage store, UUID account) {
    return store.transaction(tx -> Holds.available(tx::query, account, CAD));
  }

  private static String state(SqliteStorage store, UUID invoice) {
    return store.transaction(
        tx ->
            tx.query(
                    "SELECT state FROM payment_invoices WHERE id=?", List.of(invoice.toString()), 1)
                .getFirst()
                .get("state"));
  }
}
