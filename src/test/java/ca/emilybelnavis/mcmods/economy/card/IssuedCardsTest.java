package ca.emilybelnavis.mcmods.economy.card;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IssuedCardsTest {
  @TempDir Path world;
  private static final String TEST_FEE = "economy:test-card-fee";
  private final PlayerId actor = new PlayerId(UUID.randomUUID());
  private final AccountId account = new AccountId(UUID.randomUUID());
  private final AtomicLong time = new AtomicLong(1000);

  private static InstitutionCatalog.InstitutionId bank(String key) {
    return new InstitutionCatalog.InstitutionId("economy:bank/" + key);
  }

  private final class Fixture implements AutoCloseable {
    final SqliteStorage store =
        new SqliteStorage(
            RecordCatalog.core()
                .with(
                    new RecordCatalog.Key(TEST_FEE, 1),
                    payload -> {
                      if (!payload.matches("[0-9]+")) throw new IllegalArgumentException();
                    }));
    final PartyRef owner;

    Fixture(boolean seed) throws Exception {
      store.initialize(world.toRealPath());
      owner = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(actor));
      if (seed) {
        var opening =
            new JournalEngine(
                store,
                (a, k) -> true,
                Map.of(
                    FxService.OPEN,
                    FxService.opening(
                        Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC))));
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
                  List.of(account.value().toString(), actor.value().toString()));
              tx.execute(
                  "INSERT INTO bank_account_providers VALUES(?,'economy:bank/crownstone',0,1,0)",
                  List.of(account.value().toString()));
              tx.execute(
                  "INSERT INTO ledger_balances VALUES(?,'CAD',10000)",
                  List.of(account.value().toString()));
              return null;
            });
      }
    }

    IssuedCards.Request request(boolean replacement, UUID previous, IssuedCards.FeeQuote fee) {
      var binding = store.transaction(tx -> BankProviders.find(tx::query, account).orElseThrow());
      var proof =
          BranchFixtures.proof(
              new BranchAdmission.Context(
                  owner.world(),
                  UUID.randomUUID(),
                  actor,
                  BranchAdmission.Action.valueOf(replacement ? "CARD_REPLACE" : "CARD_ISSUE"),
                  new InstitutionCatalog.InstitutionId(binding.bankKey()),
                  null,
                  account,
                  store.transaction(
                      tx ->
                          ca.emilybelnavis.mcmods.economy.account.AccountCatalog.find(
                                  tx::query, owner, account)
                              .orElseThrow()
                              .revision()),
                  binding.revision(),
                  0));
      return new IssuedCards.Request(proof, 0, previous, fee);
    }

    JournalEngine engine(IssuedCards.Request request, IssuedCards.Fee fee) {
      return new JournalEngine(
          store,
          (a, k) -> true,
          Map.of(
              IssuedCards.ISSUE,
              IssuedCards.registration(owner, request, BankProviders.NO_TIER2, time::get, fee)));
    }

    IssuedCards.Token first() {
      var request = request(false, null, null);
      var original = intent(IssuedCards.ISSUE, request.request());
      assertEquals(Journal.Status.COMMITTED, engine(request, null).submit(original).status());
      return store.transaction(
          tx ->
              IssuedCards.find(tx::query, owner.world(), original.operationId().value())
                  .orElseThrow());
    }

    IssuedCards.FeeQuote quote(long debit) {
      return new IssuedCards.FeeQuote(
          UUID.randomUUID(),
          account,
          1,
          0,
          EconomyCurrency.CAD,
          debit,
          time.get() + 60_000,
          "trusted-test-captured-25-CAD");
    }

    IssuedCards.Fee fee() {
      return (intent, quote, reads) -> {
        long balance =
            Long.parseLong(
                reads
                    .query(
                        "SELECT minor_units FROM ledger_balances WHERE account_id=?",
                        List.of(account.value().toString()),
                        1)
                    .getFirst()
                    .get("minor_units"));
        if (balance < quote.debitMinor())
          return new JournalEngine.Reject(new Journal.Result(1, "INSUFFICIENT_FUNDS", ""));
        return new JournalEngine.Commit(
            List.of(new Journal.Record(TEST_FEE, 1, Long.toString(quote.debitMinor()))),
            new Journal.Result(1, "FEE_PAID", ""),
            tx ->
                tx.execute(
                    "UPDATE ledger_balances SET minor_units=minor_units-? WHERE account_id=?",
                    List.of(Long.toString(quote.debitMinor()), account.value().toString())));
      };
    }

    long balance() {
      return store.transaction(
          tx ->
              Long.parseLong(
                  tx.query(
                          "SELECT minor_units FROM ledger_balances WHERE account_id=?",
                          List.of(account.value().toString()),
                          1)
                      .getFirst()
                      .get("minor_units")));
    }

    long count(String table) {
      return store.transaction(
          tx ->
              Long.parseLong(
                  tx.query("SELECT count(*) AS n FROM " + table, List.of(), 1)
                      .getFirst()
                      .get("n")));
    }

    @Override
    public void close() throws Exception {
      store.close();
    }
  }

  private Journal.Intent intent(Journal.Kind kind, Journal.CanonicalRequest request) {
    return new Journal.Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        actor,
        kind,
        request,
        null,
        null);
  }

  @Test
  void firstIssuanceIsFreeDurableAndCopiedReferencesDoNotCreateAnotherBenefit() throws Exception {
    UUID token;
    try (var f = new Fixture(true)) {
      var request = f.request(false, null, null);
      var original = intent(IssuedCards.ISSUE, request.request());
      var engine = f.engine(request, null);
      var result = engine.submit(original);
      assertEquals("CARD_ISSUED", result.result().code());
      assertEquals(result, engine.submit(original));
      token = original.operationId().value();
      assertEquals(0, f.count("credit_profiles"), "Debit issuance never establishes credit");
      assertEquals(1, f.count("issued_cards"));
      assertEquals(1, f.count("custody_cases"));
      assertEquals(10000, f.balance());
      var view =
          f.store.transaction(
              tx ->
                  IssuedCards.usable(tx::query, f.owner.world(), actor, token, time.get())
                      .orElseThrow());
      assertEquals(IssuedCards.Product.DEBIT, view.product());
      assertEquals(time.get() + IssuedCards.LIFETIME, view.expiresAt());
      assertEquals(
          view,
          f.store.transaction(
              tx ->
                  IssuedCards.usable(tx::query, f.owner.world(), actor, token, time.get())
                      .orElseThrow()));
      assertTrue(
          f.store.<Boolean>transaction(
              tx ->
                  IssuedCards.usable(
                          tx::query,
                          f.owner.world(),
                          new PlayerId(UUID.randomUUID()),
                          token,
                          time.get())
                      .isEmpty()));
      assertTrue(
          f.store.<Boolean>transaction(
              tx ->
                  IssuedCards.usable(
                          tx::query, new WorldId(UUID.randomUUID()), actor, token, time.get())
                      .isEmpty()));
      var custody =
          f.store.transaction(tx -> Custody.find(tx::query, actor, token, false).orElseThrow());
      assertEquals(Custody.State.COMMITTED, custody.state());
      assertEquals(token, custody.financialOperation());
      assertEquals(IssuedCards.deliveryItems(view), custody.terms().items());
      assertFalse(custody.terms().items().contains(account.value().toString()));
      var duplicate = f.request(false, null, null);
      assertEquals(
          "CARD_REPLACEMENT_REQUIRED",
          f.engine(duplicate, null)
              .submit(intent(IssuedCards.ISSUE, duplicate.request()))
              .result()
              .code());
    }
    try (var f = new Fixture(false)) {
      assertTrue(
          f.store.<Boolean>transaction(
              tx ->
                  IssuedCards.usable(tx::query, f.owner.world(), actor, token, time.get())
                      .isPresent()));
      assertEquals(
          Custody.State.COMMITTED,
          f.store.transaction(
              tx -> Custody.find(tx::query, actor, token, false).orElseThrow().state()));
    }
  }

  @Test
  void savedTierSpecificDeliveryRemainsClaimableDuringStartupRecovery() throws Exception {
    try (var f = new Fixture(true)) {
      var token = f.first();
      String legacy = IssuedCards.deliveryItems(token) + "_t" + token.tier();
      f.store.transaction(
          tx -> {
            tx.execute(
                "UPDATE custody_cases SET items=? WHERE id=?",
                List.of(legacy, token.id().toString()));
            return null;
          });
      assertTrue(Custody.reconcileBatch(f.store));
      var claim =
          f.store.transaction(
              tx -> Custody.find(tx::query, actor, token.id(), false).orElseThrow());
      assertEquals(Custody.State.CLAIM, claim.state());
      assertEquals(legacy, claim.terms().items(), "original custody terms remain intact");
      assertEquals(IssuedCards.itemId(token), CardInventory.decode(claim).item());
    }
  }

  @Test
  void replacementFeeAndRevocationCommitTogetherAndReplayChargesOnce() throws Exception {
    try (var f = new Fixture(true)) {
      var old = f.first();
      var request = f.request(true, old.id(), f.quote(2500));
      var original = intent(IssuedCards.ISSUE, request.request());
      var engine = f.engine(request, f.fee());
      var result = engine.submit(original);
      assertEquals(Journal.Status.COMMITTED, result.status());
      assertEquals(result, engine.submit(original));
      assertEquals(7500, f.balance());
      assertEquals(2, f.count("issued_cards"));
      assertEquals(2, f.count("custody_cases"));
      assertTrue(
          f.store.<Boolean>transaction(
              tx ->
                  IssuedCards.usable(tx::query, f.owner.world(), actor, old.id(), time.get())
                      .isEmpty()));
      var current =
          f.store.transaction(
              tx -> IssuedCards.forAccount(tx::query, f.owner, account).orElseThrow());
      assertEquals(original.operationId().value(), current.id());
      assertEquals(old.id(), current.previousToken());
      assertEquals(
          1,
          f.store
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT token_id FROM issued_cards WHERE status='ACTIVE'", List.of(), 2))
              .size());
    }
  }

  @Test
  void insufficientOrMissingFeeDoesNotRevokeUsableToken() throws Exception {
    try (var f = new Fixture(true)) {
      var old = f.first();
      var noFee = f.request(true, old.id(), null);
      assertEquals(
          "CARD_FEE_REQUIRED",
          f.engine(noFee, f.fee())
              .submit(intent(IssuedCards.ISSUE, noFee.request()))
              .result()
              .code());
      var poor = f.request(true, old.id(), f.quote(20000));
      assertEquals(
          "INSUFFICIENT_FUNDS",
          f.engine(poor, f.fee())
              .submit(intent(IssuedCards.ISSUE, poor.request()))
              .result()
              .code());
      assertEquals(10000, f.balance());
      assertEquals(1, f.count("issued_cards"));
      assertEquals(1, f.count("custody_cases"));
      assertTrue(
          f.store.<Boolean>transaction(
              tx ->
                  IssuedCards.usable(tx::query, f.owner.world(), actor, old.id(), time.get())
                      .isPresent()));
    }
  }

  @Test
  void remoteLossRevokesAllCopiesAndRenewalWindowIsExact() throws Exception {
    try (var f = new Fixture(true)) {
      var old = f.first();
      var revoke =
          intent(IssuedCards.REVOKE, IssuedCards.revokeRequest(old.id(), old.revision(), actor));
      var revocations =
          new JournalEngine(
              f.store, (a, k) -> true, Map.of(IssuedCards.REVOKE, IssuedCards.revocation(f.owner)));
      assertEquals("CARD_REVOKED", revocations.submit(revoke).result().code());
      assertTrue(
          f.store.<Boolean>transaction(
              tx ->
                  IssuedCards.usable(tx::query, f.owner.world(), actor, old.id(), time.get())
                      .isEmpty()));
      time.set(old.expiresAt() - IssuedCards.RENEWAL_WINDOW - 1);
      assertFalse(IssuedCards.freeRenewal(old, time.get()));
      var paid = f.request(true, old.id(), null);
      assertEquals(
          "CARD_FEE_REQUIRED",
          f.engine(paid, null).submit(intent(IssuedCards.ISSUE, paid.request())).result().code());
      time.incrementAndGet();
      assertTrue(IssuedCards.freeRenewal(old, time.get()));
      var free = f.request(true, old.id(), null);
      assertEquals(
          "CARD_ISSUED",
          f.engine(free, null).submit(intent(IssuedCards.ISSUE, free.request())).result().code());
      assertEquals(10000, f.balance());
    }
  }

  @Test
  void expiryAndProviderEpochInvalidateTokensAndNewIssuerGetsFirstCardFree() throws Exception {
    try (var f = new Fixture(true)) {
      var old = f.first();
      time.set(old.expiresAt());
      assertTrue(
          f.store.<Boolean>transaction(
              tx ->
                  IssuedCards.usable(tx::query, f.owner.world(), actor, old.id(), time.get())
                      .isEmpty()));
      time.set(old.issuedAt() + 1);
      f.store.transaction(
          tx -> {
            tx.execute(
                "UPDATE bank_account_providers SET bank_key='economy:bank/orchard_direct',revision=2,debit_epoch=1 WHERE account_id=?",
                List.of(account.value().toString()));
            return null;
          });
      assertTrue(
          f.store.<Boolean>transaction(
              tx ->
                  IssuedCards.usable(tx::query, f.owner.world(), actor, old.id(), time.get())
                      .isEmpty()));
      var free = f.request(false, null, null);
      assertEquals(
          "CARD_ISSUED",
          f.engine(free, null).submit(intent(IssuedCards.ISSUE, free.request())).result().code());
      var current =
          f.store.transaction(
              tx -> IssuedCards.forAccount(tx::query, f.owner, account).orElseThrow());
      assertEquals(bank("orchard_direct"), current.issuer());
      assertEquals(1, current.providerEpoch());
      assertEquals(10000, f.balance());
      assertEquals(
          IssuedCards.Status.REVOKED,
          f.store.transaction(
              tx -> IssuedCards.find(tx::query, f.owner.world(), old.id()).orElseThrow().status()));
    }
  }

  @Test
  void twoChfBanksDoNotSubstituteForIssuerVisit() throws Exception {
    try (var f = new Fixture(true)) {
      f.store.transaction(
          tx -> {
            tx.execute(
                "UPDATE account_catalog SET currency='CHF',revision=revision+1 WHERE account_id=?",
                List.of(account.value().toString()));
            tx.execute(
                "UPDATE bank_account_providers SET bank_key='economy:bank/lakeward_cantonal' WHERE account_id=?",
                List.of(account.value().toString()));
            return null;
          });
      var proof =
          BranchFixtures.proof(
              new BranchAdmission.Context(
                  f.owner.world(),
                  UUID.randomUUID(),
                  actor,
                  BranchAdmission.Action.valueOf("CARD_ISSUE"),
                  bank("newhaven"),
                  null,
                  account,
                  2,
                  1,
                  0));
      var wrong = new IssuedCards.Request(proof, 0, null, null);
      assertEquals(
          "CARD_ISSUER",
          f.engine(wrong, null).submit(intent(IssuedCards.ISSUE, wrong.request())).result().code());
      var correct = f.request(false, null, null);
      assertEquals(
          "CARD_ISSUED",
          f.engine(correct, null)
              .submit(intent(IssuedCards.ISSUE, correct.request()))
              .result()
              .code());
    }
  }

  @Test
  void specialistIssuanceNeedsFreshIndependentTierEligibility() throws Exception {
    try (var f = new Fixture(true)) {
      f.store.transaction(
          tx -> {
            tx.execute(
                "UPDATE bank_account_providers SET bank_key='economy:bank/atlas_reserve' WHERE account_id=?",
                List.of(account.value().toString()));
            return null;
          });
      var unavailable = f.request(false, null, null);
      assertEquals(
          "TIER2_UNAVAILABLE",
          f.engine(unavailable, null)
              .submit(intent(IssuedCards.ISSUE, unavailable.request()))
              .result()
              .code());
      var proof = unavailable.proof();
      var eligible = new IssuedCards.Request(proof, 7, null, null);
      var engine =
          new JournalEngine(
              f.store,
              (a, k) -> true,
              Map.of(
                  IssuedCards.ISSUE,
                  IssuedCards.registration(
                      f.owner,
                      eligible,
                      (reads, owner) -> new BankProviders.TierSnapshot(true, true, 7),
                      time::get,
                      null)));
      var original = intent(IssuedCards.ISSUE, eligible.request());
      assertEquals("CARD_ISSUED", engine.submit(original).result().code());
      assertEquals(
          2,
          f.store
              .<Integer>transaction(
                  tx ->
                      IssuedCards.find(tx::query, f.owner.world(), original.operationId().value())
                          .orElseThrow()
                          .tier())
              .intValue());
    }
  }

  @Test
  void sameCurrencyWrongIssuerAndWrongBranchActionReject() throws Exception {
    try (var f = new Fixture(true)) {
      var proof =
          BranchFixtures.proof(
              new BranchAdmission.Context(
                  f.owner.world(),
                  UUID.randomUUID(),
                  actor,
                  BranchAdmission.Action.valueOf("CARD_ISSUE"),
                  bank("orchard_direct"),
                  null,
                  account,
                  1,
                  1,
                  0));
      var wrong = new IssuedCards.Request(proof, 0, null, null);
      assertEquals(
          "CARD_ISSUER",
          f.engine(wrong, null).submit(intent(IssuedCards.ISSUE, wrong.request())).result().code());
      var generic =
          BranchFixtures.proof(
              new BranchAdmission.Context(
                  f.owner.world(),
                  UUID.randomUUID(),
                  actor,
                  BranchAdmission.Action.PRIMARY,
                  bank("crownstone"),
                  null,
                  account,
                  1,
                  1,
                  0));
      var invalid = new IssuedCards.Request(generic, 0, null, null);
      assertEquals(
          "CARD_BRANCH_ACTION",
          f.engine(invalid, null)
              .submit(intent(IssuedCards.ISSUE, invalid.request()))
              .result()
              .code());
      assertEquals(0, f.count("issued_cards"));
    }
  }
}
