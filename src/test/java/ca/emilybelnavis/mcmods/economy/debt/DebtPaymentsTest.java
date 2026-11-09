package ca.emilybelnavis.mcmods.economy.debt;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.fx.FxQuotes;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.storage.LedgerProjection;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Adapter quote/validation fixtures. Financial case settlement is covered by the lifecycle tests.
 */
class DebtPaymentsTest {
  @TempDir Path path;
  private static final long NOW = Instant.parse("2026-11-05T12:00:00Z").toEpochMilli();
  private static final EconomyCurrency CAD = EconomyCurrency.CAD;
  private static final EconomyCurrency USD = EconomyCurrency.USD;

  private final class Fixture implements AutoCloseable {
    final SqliteStorage store = new SqliteStorage();
    final PlayerId actor = new PlayerId(UUID.randomUUID());
    final AccountId account = new AccountId(UUID.randomUUID());
    final PartyRef owner;
    final DebtRecords.Case debt;
    final FxQuotes book;
    long now = NOW;

    Fixture(EconomyCurrency debtCurrency) throws Exception {
      store.initialize(path.toRealPath());
      owner = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(actor));
      book = new FxQuotes(owner.world());
      var open =
          new JournalEngine(
              store,
              (a, kind) -> true,
              Map.of(
                  FxService.OPEN,
                  FxService.opening(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC))));
      var opening =
          new Journal.Intent(
              new OperationId(UUID.randomUUID()),
              new RequestId(UUID.randomUUID()),
              actor,
              FxService.OPEN,
              new Journal.CanonicalRequest(1, Map.of("currency", "CAD")),
              null,
              null);
      assertEquals(Journal.Status.COMMITTED, open.submit(opening).status());
      long defaultAt = NOW - DebtRecords.COLLECTION_WINDOW + 5_000;
      var lender =
          new InstitutionCatalog.InstitutionId(
              debtCurrency == CAD ? "economy:bank/crownstone" : "economy:bank/crosswind");
      debt =
          new DebtRecords.Case(
              owner.world(),
              UUID.randomUUID(),
              DebtRecords.Kind.LOAN,
              UUID.randomUUID(),
              actor,
              lender,
              lender,
              debtCurrency,
              100_000,
              0,
              defaultAt,
              defaultAt,
              defaultAt + DebtRecords.COLLECTION_WINDOW,
              defaultAt + DebtRecords.REPORT_WINDOW,
              DebtRecords.Sale.PENDING,
              DebtRecords.Collectability.COLLECTIBLE,
              DebtRecords.Payment.UNPAID,
              0,
              1);
      store.transaction(
          transaction -> {
            transaction.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                List.of(account.value().toString(), actor.value().toString()));
            LedgerProjection.write(transaction, account.value(), CAD, 1_000_000);
            DebtRecords.write(transaction, debt);
            return null;
          });
    }

    DebtPayments.Quote quote() {
      return store.transaction(
          transaction ->
              DebtPayments.quote(transaction::query, owner, debt.id(), account, 10_000, now));
    }

    FxQuotes.Quote fx(long amount, long lifetimeMillis) {
      return store.transaction(
          transaction ->
              book.issue(
                  actor,
                  new FxQuotes.Pair(CAD, USD),
                  FxQuotes.Side.EXACT_CREDIT,
                  new Money(USD, amount),
                  FxService.latest(transaction::query),
                  Instant.ofEpochMilli(now),
                  Duration.ofMillis(lifetimeMillis)));
    }

    Journal.Intent intent(DebtPayments.Quote quote) {
      return new Journal.Intent(
          new OperationId(UUID.randomUUID()),
          new RequestId(UUID.randomUUID()),
          actor,
          DebtPayments.PAY,
          quote.request(),
          null,
          null);
    }

    @Override
    public void close() throws Exception {
      store.close();
    }
  }

  @Test
  void collectionExpiryClampsQuoteAndExpiredConfirmationCannotAcknowledge() throws Exception {
    try (var fixture = new Fixture(CAD)) {
      var quote = fixture.quote();
      assertEquals(NOW, quote.capturedAt());
      assertEquals(NOW + 5_000, quote.expiresAt());
      assertEquals(Journal.actorKey(fixture.actor), quote.request().fields().get("economy:owner"));
      assertEquals(fixture.owner.world().value().toString(), quote.request().fields().get("world"));
      assertEquals(fixture.debt.id().toString(), quote.request().fields().get("case"));
      fixture.now = quote.expiresAt();
      var registration = DebtPayments.registration(fixture.owner, quote, () -> fixture.now);
      var result =
          new JournalEngine(
                  fixture.store, (actor, kind) -> true, Map.of(DebtPayments.PAY, registration))
              .submit(fixture.intent(quote));
      assertEquals("DEBT_PAYMENT_STALE", result.result().code());
      var unchanged =
          fixture.store.transaction(
              transaction ->
                  DebtLifecycle.ownedCase(transaction::query, fixture.owner, fixture.debt.id())
                      .orElseThrow());
      assertEquals(fixture.debt, unchanged);
      long funding =
          fixture.store.transaction(
              transaction ->
                  ca.emilybelnavis.mcmods.economy.journal.Settlement.posted(
                      transaction::query, fixture.account.value(), CAD));
      assertEquals(1_000_000, funding);
    }
  }

  @Test
  void fxCaptureRetainsOriginalInstantAndRejectsForeignOwnerOrChangedFunding() throws Exception {
    try (var fixture = new Fixture(USD)) {
      var payment = fixture.quote();
      var fx = fixture.fx(10_000, 2_000);
      fixture.now += 100;
      var quote =
          fixture.store.transaction(
              transaction ->
                  DebtPayments.capture(
                      transaction::query, fixture.owner, payment, fx, fixture.now));
      assertEquals(payment.capturedAt(), quote.payment().capturedAt());
      assertEquals(payment.id(), quote.payment().id());
      assertEquals(NOW + 2_000, quote.payment().expiresAt());
      assertEquals(Long.toString(NOW), quote.canonical().fields().get("captured_at"));
      assertEquals("10000", quote.canonical().fields().get("amount"));
      assertEquals(fx.id().toString(), quote.canonical().fields().get("fx_quote"));
      var stranger =
          new PartyRef(fixture.owner.world(), new PartyRef.Player(new PlayerId(UUID.randomUUID())));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.store.transaction(
                  transaction ->
                      DebtPayments.capture(
                          transaction::query, stranger, payment, fx, fixture.now)));
      fixture.store.transaction(
          transaction -> {
            transaction.execute(
                "UPDATE account_catalog SET revision=2 WHERE account_id=?",
                List.of(fixture.account.value().toString()));
            return null;
          });
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.store.transaction(
                  transaction ->
                      DebtPayments.capture(
                          transaction::query, fixture.owner, payment, fx, fixture.now)));
      assertTrue(
          fixture
              .store
              .transaction(
                  transaction ->
                      transaction.query("SELECT quote_id FROM ledger_fx_quotes", List.of(), 1))
              .isEmpty());
    }
  }

  @Test
  void fxReceiptDecoderPreservesOriginalWindowAndRejectsNoncanonicalAmounts() throws Exception {
    try (var fixture = new Fixture(USD)) {
      var payment = fixture.quote();
      var fx = fixture.fx(payment.amountMinor(), 2_000);
      var capture =
          fixture.store.transaction(
              transaction ->
                  DebtPayments.capture(
                      transaction::query, fixture.owner, payment, fx, fixture.now));
      var frozen = capture.payment();
      var receipt =
          String.join(
              "|",
              frozen.id().toString(),
              frozen.world().value().toString(),
              frozen.owner().value().toString(),
              frozen.caseId().toString(),
              frozen.funding().value().toString(),
              fx.id().toString(),
              Long.toString(frozen.amountMinor()),
              Long.toString(frozen.caseRevision()),
              Long.toString(frozen.fundingRevision()),
              Long.toString(frozen.fundingLedgerRevision()),
              Long.toString(frozen.capturedAt()),
              Long.toString(frozen.expiresAt()),
              capture.reference().name(),
              capture.referenceResidual().toPlainString());
      DebtPayments.validate(receipt);
      var fields = receipt.split("\\|", -1);
      fields[6] = "0" + fields[6];
      assertThrows(
          IllegalArgumentException.class, () -> DebtPayments.validate(String.join("|", fields)));
    }
  }

  @Test
  void invalidOrForeignPaymentRequestsFailBeforeAnyCollectionMutation() throws Exception {
    try (var fixture = new Fixture(USD)) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.store.transaction(
                  transaction ->
                      DebtPayments.quote(
                          transaction::query,
                          fixture.owner,
                          fixture.debt.id(),
                          fixture.account,
                          0,
                          fixture.now)));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.store.transaction(
                  transaction ->
                      DebtPayments.quote(
                          transaction::query,
                          fixture.owner,
                          fixture.debt.id(),
                          fixture.account,
                          100_001,
                          fixture.now)));
      var quote = fixture.quote();
      var registration = DebtPayments.registration(fixture.owner, quote, () -> fixture.now);
      var engine =
          new JournalEngine(
              fixture.store, (actor, kind) -> true, Map.of(DebtPayments.PAY, registration));
      assertEquals(
          "DEBT_EXPLICIT_FX_REQUIRED", engine.submit(fixture.intent(quote)).result().code());
      var foreign =
          new Journal.Intent(
              new OperationId(UUID.randomUUID()),
              new RequestId(UUID.randomUUID()),
              new PlayerId(UUID.randomUUID()),
              DebtPayments.PAY,
              quote.request(),
              null,
              null);
      assertEquals("DEBT_PAYMENT_REQUEST", engine.submit(foreign).result().code());
      assertEquals(
          fixture.debt,
          fixture.store.transaction(
              transaction ->
                  DebtLifecycle.ownedCase(transaction::query, fixture.owner, fixture.debt.id())
                      .orElseThrow()));
    }
  }
}
