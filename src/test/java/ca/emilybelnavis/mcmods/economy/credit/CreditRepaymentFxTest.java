package ca.emilybelnavis.mcmods.economy.credit;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.math.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreditRepaymentFxTest {
  @TempDir Path path;
  private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
  private static final InstitutionCatalog.InstitutionId BANK =
      new InstitutionCatalog.InstitutionId("economy:bank/crownstone");

  private final class Fixture implements AutoCloseable {
    final SqliteStorage store = new SqliteStorage();
    final WorldId world;
    final PlayerId player = new PlayerId(UUID.randomUUID());
    final PartyRef owner;
    final AccountIdentity funding;
    final CreditFacilities.Facility facility;
    final FxQuotes book;
    final ServicePricing pricing = new ServicePricing(new WealthValuation());
    long now = NOW.toEpochMilli();

    Fixture() throws Exception {
      store.initialize(path.toRealPath());
      world = store.snapshot().worldId();
      owner = new PartyRef(world, new PartyRef.Player(player));
      book = new FxQuotes(world);
      var system = new SystemActorId(UUID.randomUUID());
      var open =
          new JournalEngine(
              store,
              (a, k) -> true,
              Map.of(FxService.OPEN, FxService.opening(Clock.fixed(NOW, ZoneOffset.UTC))));
      assertEquals(
          Journal.Status.COMMITTED,
          open.submit(
                  new Journal.Intent(
                      new OperationId(UUID.randomUUID()),
                      new RequestId(UUID.randomUUID()),
                      system,
                      FxService.OPEN,
                      new Journal.CanonicalRequest(1, Map.of("currency", "CAD")),
                      null,
                      null))
              .status());
      funding =
          new AccountIdentity(
              new AccountId(UUID.randomUUID()),
              owner,
              AccountIdentity.Purpose.BANK,
              CAD,
              AccountIdentity.Status.OPEN,
              1,
              true);
      var purchase =
          CardServicing.draw(
              CardServicing.empty(
                  CardServicing.Kind.PURCHASE,
                  CreditFacilities.apr(InstitutionCatalog.Network.KEYSTONE, false)),
              500);
      var advance =
          CardServicing.empty(
              CardServicing.Kind.ADVANCE,
              CreditFacilities.apr(InstitutionCatalog.Network.KEYSTONE, true));
      facility =
          new CreditFacilities.Facility(
              world,
              UUID.randomUUID(),
              player,
              BANK,
              InstitutionCatalog.Network.KEYSTONE,
              USD,
              250000,
              1,
              now,
              now,
              now,
              1,
              CreditFacilities.Status.ACTIVE,
              CreditFacilities.Access.ENABLED,
              purchase,
              advance,
              List.of(),
              List.of());
      store.transaction(
          tx -> {
            party(tx, BANK);
            var borrower =
                new InstitutionCatalog.InstitutionId("economy:borrower/" + player.value());
            party(tx, borrower);
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                List.of(funding.id().value().toString(), player.value().toString()));
            tx.execute(
                "INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",
                List.of(funding.id().value().toString(), BANK.key()));
            LedgerProjection.write(tx, funding.id().value(), CAD, 10000);
            var receivable =
                FinancialAccounts.account(world, BANK, FinancialAccounts.Role.RECEIVABLE, USD);
            var liability =
                FinancialAccounts.account(world, borrower, FinancialAccounts.Role.LIABILITY, USD);
            for (var a : List.of(receivable, liability)) FinancialAccounts.register(tx, a);
            LedgerProjection.write(tx, receivable.id(), USD, 500);
            LedgerProjection.write(tx, liability.id(), USD, -500);
            CreditFacilities.write(tx, facility);
            return null;
          });
    }

    void party(FoundationStore.Transaction tx, InstitutionCatalog.InstitutionId id) {
      tx.execute(
          "INSERT INTO institution_parties VALUES(?,?,?,?,?,'HYBRID',1)",
          List.of(
              world.value().toString(),
              id.key(),
              new InstitutionCatalog.SystemActor(world, id).actorId().value().toString(),
              "Fixture",
              "CAD"));
    }

    CreditRepaymentFx.Quote quote(long amount) {
      return store.transaction(
          tx -> {
            var fx =
                book.issue(
                    player,
                    new FxQuotes.Pair(CAD, USD),
                    FxQuotes.Side.EXACT_CREDIT,
                    new Money(USD, amount),
                    FxService.latest(tx::query),
                    Instant.ofEpochMilli(now),
                    Duration.ofSeconds(60));
            var price =
                pricing.quote(
                    tx::query,
                    world,
                    player,
                    BANK,
                    ServicePricing.Service.FX,
                    fx.debit(),
                    Instant.ofEpochMilli(now),
                    fx.expires());
            fx = book.price(fx, price);
            return CreditRepaymentFx.capture(tx::query, owner, facility, funding, amount, fx, now);
          });
    }

    FxQuotes.Quote fresh(UUID id, PlayerId actor, WorldId world, JournalEngine.ReadView reads) {
      var fx =
          book.requireFresh(
              id, actor, world, FxService.latest(reads), 1, Instant.ofEpochMilli(now));
      pricing.requireFresh(fx.pricing(), reads, world, actor, Instant.ofEpochMilli(now));
      return fx;
    }

    Journal.Intent intent(CreditRepaymentFx.Quote quote) {
      return new Journal.Intent(
          new OperationId(UUID.randomUUID()),
          new RequestId(UUID.randomUUID()),
          player,
          CreditFacilities.REPAY,
          quote.canonical(),
          null,
          null);
    }

    Journal.Outcome submit(CreditRepaymentFx.Quote quote, Journal.Intent intent) {
      return new JournalEngine(
              store,
              (a, k) -> true,
              Map.of(
                  CreditFacilities.REPAY,
                  CreditRepaymentFx.registration(owner, quote, this::fresh, () -> now)))
          .submit(intent);
    }

    long balance(UUID id, EconomyCurrency c) {
      return store.transaction(tx -> Settlement.posted(tx::query, id, c));
    }

    public void close() throws Exception {
      store.close();
    }
  }

  @Test
  void nearestMinorFundingRetainsSignedResidualWithVersionedHistory() throws Exception {
    try (var f = new Fixture()) {
      var quote = f.quote(102);
      assertEquals(142, quote.fx().debit().minorUnits());
      assertTrue(quote.referenceResidual().signum() < 0);
      var intent = f.intent(quote);
      var outcome = f.submit(quote, intent);
      assertEquals(Journal.Status.COMMITTED, outcome.status());
      assertEquals(outcome, f.submit(quote, intent));
      var fact =
          f.store.transaction(
              tx ->
                  tx.query(
                          "SELECT record_version,payload FROM journal_records WHERE record_type=?",
                          List.of(CreditRepaymentFx.FACT),
                          1)
                      .getFirst());
      assertEquals("2", fact.get("record_version"));
      var payload = fact.get("payload");
      var fields = payload.split("\\|", -1);
      assertEquals(quote.referenceResidual(), new BigDecimal(fields[13]));
      var catalog = RecordCatalog.core();
      assertDoesNotThrow(() -> catalog.validate(CreditRepaymentFx.FACT, 2, payload));
      assertThrows(
          StorageException.class, () -> catalog.validate(CreditRepaymentFx.FACT, 1, payload));
      fields[13] = quote.referenceResidual().abs().toPlainString();
      String legacy = String.join("|", fields);
      assertDoesNotThrow(() -> catalog.validate(CreditRepaymentFx.FACT, 1, legacy));
    }
  }

  @Test
  void realFxRepaymentPaysExactDebtAndSeparateSourceFeeOnce() throws Exception {
    try (var f = new Fixture()) {
      var quote = f.quote(100);
      var fx = quote.fx();
      var intent = f.intent(quote);
      assertEquals(Journal.Status.COMMITTED, f.submit(quote, intent).status());
      assertEquals(
          10000 - fx.debit().minorUnits() - fx.fee().minorUnits(),
          f.balance(f.funding.id().value(), CAD));
      var reserve =
          FinancialAccounts.account(f.world, BANK, FinancialAccounts.Role.RESERVATION, USD);
      assertEquals(100, f.balance(reserve.id(), USD));
      assertEquals(
          400,
          f.store
              .transaction(
                  tx -> CreditFacilities.find(tx::query, f.facility.id()).orElseThrow().owedMinor())
              .longValue());
      assertEquals(
          fx.fee().minorUnits(),
          f.balance(FinancialAccounts.id(f.world, BANK, FinancialAccounts.Role.CASH, CAD), CAD));
      assertEquals(fx.debit().minorUnits(), f.balance(Settlement.clearingAccount(CAD), CAD));
      assertEquals(-100, f.balance(Settlement.clearingAccount(USD), USD));
      long seq = f.store.lastCommittedSequence();
      assertEquals(Journal.Status.COMMITTED, f.submit(quote, intent).status());
      assertEquals(seq, f.store.lastCommittedSequence());
      assertEquals(
          1,
          f.store
              .transaction(
                  tx ->
                      tx.query(
                              "SELECT quote_id FROM ledger_fx_quotes WHERE quote_id=?",
                              List.of(fx.id().toString()),
                              1)
                          .size())
              .intValue());
    }
  }

  @Test
  void feeCannotOverdrawAfterPrincipalAndFailureConsumesNothing() throws Exception {
    try (var f = new Fixture()) {
      var initial = f.quote(100);
      f.store.transaction(
          tx -> {
            LedgerProjection.write(
                tx, f.funding.id().value(), CAD, initial.fx().debit().minorUnits());
            return null;
          });
      var q =
          f.quote(100); // Capture current pricing after setting the source to principal-only funds.
      var outcome = f.submit(q, f.intent(q));
      assertNotEquals(Journal.Status.COMMITTED, outcome.status());
      assertEquals(q.fx().debit().minorUnits(), f.balance(f.funding.id().value(), CAD));
      assertEquals(
          0,
          f.store
              .transaction(
                  tx ->
                      tx.query(
                              "SELECT quote_id FROM ledger_fx_quotes WHERE quote_id=?",
                              List.of(q.fx().id().toString()),
                              1)
                          .size())
              .intValue());
      assertEquals(
          500,
          f.store
              .transaction(
                  tx -> CreditFacilities.find(tx::query, f.facility.id()).orElseThrow().owedMinor())
              .longValue());
    }
  }

  @Test
  void expiryAndWrongOwnerFailBeforeFinanceAndCaptureCannotUseAnotherOwner() throws Exception {
    try (var f = new Fixture()) {
      var q = f.quote(100);
      assertEquals(f.now + 30000, q.repayment().expiresAt());
      f.now += 30000;
      assertEquals("CREDIT_PAYOFF_STALE", f.submit(q, f.intent(q)).result().code());
      var foreign = new PartyRef(f.world, new PartyRef.Player(new PlayerId(UUID.randomUUID())));
      var bad =
          f.store.transaction(
              tx -> {
                try {
                  return CreditRepaymentFx.capture(
                      tx::query, foreign, f.facility, f.funding, 100, q.fx(), NOW.toEpochMilli());
                } catch (IllegalArgumentException expected) {
                  return null;
                }
              });
      assertNull(bad);
      var intent = f.intent(q);
      var wrong =
          new Journal.Intent(
              intent.operationId(),
              intent.requestId(),
              new PlayerId(UUID.randomUUID()),
              intent.kind(),
              intent.request(),
              null,
              null);
      var rejected =
          f.store.transaction(
              tx -> {
                try {
                  return CreditRepaymentFx.registration(
                          f.owner, q, f::fresh, () -> NOW.toEpochMilli())
                      .handler()
                      .prepare(wrong, tx::query);
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              });
      assertInstanceOf(JournalEngine.Reject.class, rejected);
      assertEquals(10000, f.balance(f.funding.id().value(), CAD));
    }
  }
}
