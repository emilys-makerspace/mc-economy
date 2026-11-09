package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static ca.emilybelnavis.mcmods.economy.journal.JournalFixtures.ACTOR;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.account.AccountLifecycle;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.fx.FxQuotes;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
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

class FundedClosureTest {
  @org.junit.jupiter.api.BeforeAll
  static void bootstrapCashCatalog() {
    if (net.neoforged.fml.loading.LoadingModList.get() == null)
      net.neoforged.fml.loading.LoadingModList.of(
          List.of(), List.of(), List.of(), List.of(), Map.of());
    net.minecraft.SharedConstants.tryDetectVersion();
    net.minecraft.server.Bootstrap.bootStrap();
  }

  @TempDir Path world;

  @Test
  void zeroPayoutStillDrainsAndClosesInOneTransaction() throws Exception {
    var store = new SqliteStorage();
    try {
      store.initialize(world.toRealPath());
      var worldId = store.snapshot().worldId();
      var owner = new PartyRef(worldId, new PartyRef.Player(ACTOR));
      var source = new AccountId(UUID.randomUUID());
      var destination = new AccountId(UUID.randomUUID());
      var now = Instant.parse("2026-10-01T00:00:00Z");
      var opening =
          new JournalEngine(
              store,
              (actor, kind) -> true,
              Map.of(FxService.OPEN, FxService.opening(Clock.fixed(now, ZoneOffset.UTC))));
      assertEquals(
          Journal.Status.COMMITTED,
          opening
              .submit(
                  intent(
                      FxService.OPEN, new Journal.CanonicalRequest(1, Map.of("currency", "CAD"))))
              .status());
      store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                List.of(destination.value().toString(), ACTOR.value().toString()));
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','JPY','OPEN',1,0)",
                List.of(source.value().toString(), ACTOR.value().toString()));
            return null;
          });
      var ledger =
          new JournalEngine(
              store,
              (actor, kind) -> true,
              SameCurrency.registrations(worldId, (intent, instruction, reads) -> true));
      var amount = new Money(JPY, 2);
      var credit = new SameCurrency.Adjustment(ACTOR, new SameCurrency.Revision(source, 1), amount);
      assertEquals(
          Journal.Status.COMMITTED,
          ledger.submit(intent(SameCurrency.CREDIT, credit.request())).status());
      var quotes = new FxQuotes(worldId);
      var proof =
          ca.emilybelnavis.mcmods.economy.institution.BranchFixtures.closure(store, owner, source);
      var pricing =
          new ca.emilybelnavis.mcmods.economy.pricing.ServicePricing(
              ca.emilybelnavis.mcmods.economy.pricing.PricingRuntime.wealth());
      var principal =
          new Money(JPY, 1); // Two-yen budget: one principal plus one rounded source fee.
      var raw =
          store.transaction(
              tx ->
                  quotes.issue(
                      ACTOR,
                      new FxQuotes.Pair(JPY, CAD),
                      FxQuotes.Side.EXACT_DEBIT,
                      principal,
                      FxService.latest(tx::query),
                      now,
                      Duration.ofSeconds(60)));
      var fee =
          store.transaction(
              tx ->
                  pricing.quote(
                      tx::query,
                      worldId,
                      ACTOR,
                      proof.context().bank(),
                      ca.emilybelnavis.mcmods.economy.pricing.ServicePricing.Service
                          .PROTECTED_CLOSURE_FX,
                      principal,
                      now,
                      raw.expires()));
      var quote = quotes.price(raw, fee);
      var request =
          new FundedClosure.Instruction(
              new AccountLifecycle.Close(source, 1, destination, 1L), quote.id());
      var registration =
          FundedClosure.registrationAtBranch(
              owner,
              request,
              (id, actor, world, reads) ->
                  quotes.requireFresh(
                      id, actor, world, FxService.latest(reads), quote.pricingVersion(), now),
              BankingClosureClaims::read,
              proof,
              (intent, reads) -> true);
      var engine =
          new JournalEngine(
              store,
              (actor, kind) -> true,
              Map.of(
                  ca.emilybelnavis.mcmods.economy.institution.BankProviders.CLOSE, registration));
      var fields =
          new ca.emilybelnavis.mcmods.economy.institution.BankProviders.Request(
                  proof, 0, request.request().fields())
              .request()
              .fields();
      var operation =
          intent(
              ca.emilybelnavis.mcmods.economy.institution.BankProviders.CLOSE,
              new Journal.CanonicalRequest(1, fields));
      var closed = engine.submit(operation);
      assertEquals(Journal.Status.COMMITTED, closed.status(), closed.result().code());
      assertEquals(closed, engine.submit(operation));
      var envelope = engine.readAfter(0, 10).getLast();
      assertEquals(closed.transactionId(), envelope.transactionId());
      assertEquals(
          6,
          envelope.records().stream()
              .filter(record -> record.type().equals("economy:posting"))
              .count());
      assertTrue(
          envelope.records().stream()
              .anyMatch(record -> record.type().equals("economy:bank-lifecycle")));
      store.transaction(
          tx -> {
            assertEquals(0, Settlement.posted(tx::query, source.value(), JPY));
            assertEquals(0, Settlement.posted(tx::query, destination.value(), CAD));
            assertFalse(Settlement.openBank(tx::query, source.value(), JPY));
            return null;
          });
    } finally {
      store.close();
    }
  }

  @Test
  void cashClosureDrainsClosesAndProtectsOnePayoutWithoutCreditingPrimary() throws Exception {
    var store = new SqliteStorage();
    try {
      store.initialize(world.toRealPath());
      var worldId = store.snapshot().worldId();
      var owner = new PartyRef(worldId, new PartyRef.Player(ACTOR));
      var source = new AccountId(UUID.randomUUID());
      var primary = new AccountId(UUID.randomUUID());
      var opening =
          new JournalEngine(
              store,
              (actor, kind) -> true,
              Map.of(
                  FxService.OPEN,
                  FxService.opening(
                      Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC))));
      assertEquals(
          Journal.Status.COMMITTED,
          opening
              .submit(
                  intent(
                      FxService.OPEN, new Journal.CanonicalRequest(1, Map.of("currency", "CAD"))))
              .status());
      store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                List.of(primary.value().toString(), ACTOR.value().toString()));
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','JPY','OPEN',1,0)",
                List.of(source.value().toString(), ACTOR.value().toString()));
            return null;
          });
      var ledger =
          new JournalEngine(
              store,
              (actor, kind) -> true,
              SameCurrency.registrations(worldId, (intent, instruction, reads) -> true));
      var amount = new Money(JPY, 1000);
      assertEquals(
          Journal.Status.COMMITTED,
          ledger
              .submit(
                  intent(
                      SameCurrency.CREDIT,
                      new SameCurrency.Adjustment(
                              ACTOR, new SameCurrency.Revision(source, 1), amount)
                          .request()))
              .status());
      var proof =
          ca.emilybelnavis.mcmods.economy.institution.BranchFixtures.closure(store, owner, source);
      var instruction =
          new CashClosure.Instruction(new AccountLifecycle.Close(source, 1, primary, 1L), amount);
      var kind = ca.emilybelnavis.mcmods.economy.institution.BankProviders.CLOSE;
      var engine =
          new JournalEngine(
              store,
              (actor, requested) -> true,
              Map.of(
                  kind,
                  CashClosure.registration(
                      owner,
                      instruction,
                      BankingClosureClaims::read,
                      proof,
                      (intent, reads) -> true)));
      var request =
          new ca.emilybelnavis.mcmods.economy.institution.BankProviders.Request(
                  proof, 0, instruction.request().fields())
              .request();
      var operation = intent(kind, request);
      var result = engine.submit(operation);
      assertEquals(Journal.Status.COMMITTED, result.status(), result.result().code());
      assertEquals(result, engine.submit(operation));
      store.transaction(
          tx -> {
            assertEquals(0, Settlement.posted(tx::query, source.value(), JPY));
            assertEquals(0, Settlement.posted(tx::query, primary.value(), CAD));
            assertFalse(Settlement.openBank(tx::query, source.value(), JPY));
            var delivery =
                Custody.find(tx::query, ACTOR, operation.operationId().value(), false)
                    .orElseThrow();
            assertEquals(Custody.State.COMMITTED, delivery.state());
            var cash = WithdrawalDelivery.claims(delivery);
            assertEquals(1, cash.size());
            assertEquals(1, cash.getFirst().count());
            assertEquals(1000, cashCatalogValue(cash.getFirst().item()));
            assertEquals(
                1,
                tx.query(
                        "SELECT lot_id FROM custody_cash_reservations WHERE custody_id=? AND state='ACTIVE'",
                        List.of(delivery.id().toString()),
                        2)
                    .size());
            return null;
          });
    } finally {
      store.close();
    }
  }

  private static long cashCatalogValue(String item) {
    return ca.emilybelnavis.mcmods.economy.cash.CashCatalog.all().stream()
        .filter(d -> ("economy:" + d.id()).equals(item))
        .findFirst()
        .orElseThrow()
        .minorUnits();
  }

  @Test
  void realPendingCashAndRefundObligationsBlockClosureAndRemainOwnerScoped() throws Exception {
    var store = new SqliteStorage();
    try {
      store.initialize(world.toRealPath());
      var owner = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(ACTOR));
      var id = new AccountId(UUID.randomUUID());
      var account =
          new AccountIdentity(
              id, owner, AccountIdentity.Purpose.BANK, USD, AccountIdentity.Status.OPEN, 1, false);
      store.transaction(
          tx -> {
            assertFalse(
                BankingClosureClaims.read(tx::query, account).orElseThrow().unresolvedObligation());
            tx.execute(
                "INSERT INTO withdrawals VALUES(? ,?,'QUARANTINED','preview','','',1)",
                List.of(UUID.randomUUID().toString(), Journal.actorKey(ACTOR)));
            assertTrue(
                BankingClosureClaims.read(tx::query, account).orElseThrow().unresolvedObligation());
            tx.execute("UPDATE withdrawals SET state='CANCELLED'", List.of());
            assertFalse(
                BankingClosureClaims.read(tx::query, account).orElseThrow().unresolvedObligation());
            tx.execute(
                "INSERT INTO withdrawals VALUES(? ,?,'QUARANTINED','preview','','',1)",
                List.of(
                    UUID.randomUUID().toString(),
                    Journal.actorKey(new PlayerId(UUID.randomUUID()))));
            assertFalse(
                BankingClosureClaims.read(tx::query, account).orElseThrow().unresolvedObligation());
            tx.execute(
                "INSERT INTO payment_invoices(id,merchant,payer,source,target,currency,units,state) VALUES('invoice','merchant','payer',?,?,'USD',100,'CAPTURED')",
                List.of(UUID.randomUUID().toString(), id.value().toString()));
            assertTrue(
                BankingClosureClaims.read(tx::query, account).orElseThrow().unresolvedObligation(),
                "captured refund still requires target account");
            tx.execute("UPDATE payment_invoices SET state='REFUNDED'", List.of());
            assertFalse(
                BankingClosureClaims.read(tx::query, account).orElseThrow().unresolvedObligation());
            return null;
          });
    } finally {
      store.close();
    }
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
