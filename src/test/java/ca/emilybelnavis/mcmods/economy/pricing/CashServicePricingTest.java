package ca.emilybelnavis.mcmods.economy.pricing;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CashServicePricingTest {
  @TempDir Path path;
  private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
  private static final String USD_NOTE = "economy:usd_bill_20";
  private static final InstitutionCatalog.InstitutionId BANK =
      new InstitutionCatalog.InstitutionId("economy:bank/crownstone");

  private final class Fixture implements AutoCloseable {
    final SqliteStorage store = new SqliteStorage();
    final PlayerId owner = new PlayerId(UUID.randomUUID());
    final UUID account = UUID.randomUUID(), generation = UUID.randomUUID(), lot = UUID.randomUUID();
    final WorldId world;
    final EconomyCurrency accountCurrency;
    final InstitutionCatalog.InstitutionId provider;
    final TerminalPolicy.Anchor anchor = new TerminalPolicy.Anchor("minecraft:overworld", 1, 64, 1);
    final ServicePricing pricing = new ServicePricing(new WealthValuation());
    final Map<String, Deposits.Denomination> deposits =
        Map.of(USD_NOTE, new Deposits.Denomination(USD, 2000, true));
    final Map<String, WithdrawalPlanner.Denomination> withdrawals =
        Map.of(USD_NOTE, new WithdrawalPlanner.Denomination(USD_NOTE, USD, 2000, true));
    final FxFacts.Publication publication;

    Fixture(long cash) throws Exception {
      this(cash, false);
    }

    Fixture(long cash, boolean specialist) throws Exception {
      accountCurrency = specialist ? USD : CAD;
      provider =
          specialist ? new InstitutionCatalog.InstitutionId("economy:bank/atlas_reserve") : BANK;
      store.initialize(path.toRealPath());
      world = store.snapshot().worldId();
      var opening =
          new JournalEngine(
              store,
              (a, k) -> true,
              Map.of(FxService.OPEN, FxService.opening(Clock.fixed(NOW, ZoneOffset.UTC))));
      assertEquals(
          Journal.Status.COMMITTED,
          opening
              .submit(
                  intent(
                      FxService.OPEN, new Journal.CanonicalRequest(1, Map.of("currency", "CAD"))))
              .status());
      publication = store.transaction(tx -> FxService.latest(tx::query));
      store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK',?,'OPEN',1,?)",
                List.of(
                    account.toString(),
                    owner.value().toString(),
                    accountCurrency.name(),
                    specialist ? "0" : "1"));
            tx.execute(
                "INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",
                List.of(account.toString(), provider.key()));
            tx.execute(
                "INSERT INTO institution_parties VALUES(?,?,?,?,?,?,1)",
                List.of(
                    world.value().toString(),
                    provider.key(),
                    new InstitutionCatalog.SystemActor(world, provider)
                        .actorId()
                        .value()
                        .toString(),
                    "Fixture bank",
                    accountCurrency.name(),
                    specialist ? "TIER2_ONLY" : "HYBRID"));
            if (cash != 0) LedgerProjection.write(tx, account, accountCurrency, cash);
            tx.execute(
                "INSERT INTO cash_issuance VALUES(?,?,?,?,?,'ACTIVE',1,'fixture')",
                List.of(lot.toString(), world.value().toString(), USD_NOTE, "2", "2"));
            return null;
          });
      var catalog = Map.of(USD_NOTE, new TerminalPolicy.Denomination(USD, true));
      var place = TerminalPolicy.Action.PLACE;
      assertEquals(
          Journal.Status.COMMITTED,
          new JournalEngine(
                  store,
                  (a, k) -> true,
                  Map.of(
                      place.kind(),
                      TerminalPolicy.registration(
                          place, world, catalog, (a, i, c, r) -> true, (a, r) -> false)))
              .submit(
                  intent(
                      place.kind(),
                      TerminalPolicy.placeRequest(
                          anchor, generation, owner, TerminalPolicy.Kind.TELLER, null, 0)))
              .status());
      var terminalBank = new InstitutionCatalog.InstitutionId("economy:bank/crosswind");
      assertEquals(
          Journal.Status.COMMITTED,
          new JournalEngine(
                  store,
                  (a, k) -> true,
                  Map.of(
                      TerminalBanking.BIND,
                      TerminalBanking.registration(
                          world, catalog, (a, i, c, r) -> true, (a, r) -> false)))
              .submit(
                  intent(
                      TerminalBanking.BIND,
                      TerminalBanking.bindRequest(anchor, generation, 1, terminalBank, true)))
              .status());
      var configure = TerminalPolicy.Action.CONFIGURE;
      var current =
          store
              .transaction(tx -> TerminalPolicy.find(tx::query, world, anchor, owner, false))
              .orElseThrow();
      assertEquals(
          Journal.Status.COMMITTED,
          new JournalEngine(
                  store,
                  (a, k) -> true,
                  Map.of(
                      configure.kind(),
                      TerminalPolicy.registration(
                          configure, world, catalog, (a, i, c, r) -> true, (a, r) -> false)))
              .submit(
                  intent(
                      configure.kind(),
                      TerminalPolicy.configureRequest(
                          anchor,
                          generation,
                          2,
                          TerminalBanking.settings(current, TerminalPolicy.Mode.BOTH, catalog))))
              .status());
    }

    CashPricing.Context context() {
      return new CashPricing.Context(pricing, world, owner, account, NOW);
    }

    Deposits.FxSnapshot depositFx() {
      return new Deposits.FxSnapshot(
          publication.updateId().toString(), publication.conversionRates(), context());
    }

    Withdrawals.FxSnapshot withdrawFx() {
      return new Withdrawals.FxSnapshot(
          publication.updateId().toString(), publication.conversionRates(), context());
    }

    Deposits.Preview depositPreview() {
      return store.transaction(
          tx ->
              Deposits.preview(
                  tx::query,
                  world,
                  owner,
                  new Deposits.Binding(
                      account,
                      1,
                      accountCurrency,
                      anchor,
                      generation,
                      3,
                      publication.updateId().toString()),
                  List.of(
                      new Deposits.Slot(
                          0, new CashIssuance.Claim(world, lot, USD_NOTE, 1), "fixture-issued")),
                  deposits,
                  r -> depositFx()));
    }

    Withdrawals.Preview withdrawPreview() {
      return store.transaction(
          tx ->
              Withdrawals.preview(
                  tx::query,
                  world,
                  owner,
                  new Withdrawals.Binding(
                      account,
                      1,
                      accountCurrency,
                      anchor,
                      generation,
                      3,
                      publication.updateId().toString()),
                  new Money(USD, 2000),
                  null,
                  withdrawals,
                  r -> withdrawFx()));
    }

    Journal.Intent intent(Journal.Kind kind, Journal.CanonicalRequest request) {
      return new Journal.Intent(
          new OperationId(UUID.randomUUID()),
          new RequestId(UUID.randomUUID()),
          owner,
          kind,
          request,
          null,
          null);
    }

    Journal.Outcome deposit(Deposits.Action action, Journal.Intent intent) {
      return new JournalEngine(
              store,
              (a, k) -> true,
              Map.of(
                  action.kind(),
                  Deposits.registration(
                      action,
                      world,
                      deposits,
                      r -> depositFx(),
                      (a, i, c, r) -> true,
                      (a, i, c, e, r) -> true)))
          .submit(intent);
    }

    Journal.Outcome withdraw(Withdrawals.Action action, Journal.Intent intent) {
      return new JournalEngine(
              store,
              (a, k) -> true,
              Map.of(
                  action.kind(),
                  Withdrawals.registration(
                      action,
                      world,
                      withdrawals,
                      r -> withdrawFx(),
                      (a, i, c, r) -> true,
                      (a, i, c, r) -> true,
                      WithdrawalDelivery::prepare)))
          .submit(intent);
    }

    long bankCash(EconomyCurrency currency) {
      return store.transaction(
          tx -> FinancialAccounts.spendableCash(tx::query, world, BANK, currency));
    }

    long balance() {
      return store.transaction(tx -> Settlement.posted(tx::query, account, CAD));
    }

    public void close() throws Exception {
      store.close();
    }
  }

  @Test
  void pricedCashDepositSplitsGrossPrincipalAndFeeAndConsumesOneIssuedQuantityOnce()
      throws Exception {
    try (var f = new Fixture(0)) {
      var preview = f.depositPreview();
      var group = preview.groups().getFirst();
      assertNotNull(group.pricing());
      var price =
          f.store.transaction(
              tx -> CashPricing.requireFresh(f.context(), tx::query, group.pricing()));
      assertEquals(1990, price.principalMinor());
      assertEquals(10, price.fee().minorUnits());
      assertEquals(2000, group.subtotal().minorUnits());
      assertEquals(2778, preview.credit().minorUnits());
      var reserve = f.intent(Deposits.Action.RESERVE.kind(), Deposits.reserveRequest(preview));
      assertEquals(Journal.Status.COMMITTED, f.deposit(Deposits.Action.RESERVE, reserve).status());
      var id = reserve.operationId().value();
      assertEquals(
          Journal.Status.COMMITTED,
          f.deposit(
                  Deposits.Action.SAVED,
                  f.intent(Deposits.Action.SAVED.kind(), Deposits.request(id, "saved", "fixture")))
              .status());
      var commit =
          f.intent(Deposits.Action.COMMIT.kind(), Deposits.request(id, "saved", "fixture"));
      var result = f.deposit(Deposits.Action.COMMIT, commit);
      assertEquals(Journal.Status.COMMITTED, result.status(), result.result().code());
      assertEquals(result, f.deposit(Deposits.Action.COMMIT, commit));
      assertEquals(2778, f.balance());
      assertEquals(10, f.bankCash(USD));
      assertEquals(
          1,
          f.store
              .transaction(tx -> CashIssuance.find(tx::query, f.world, f.lot).orElseThrow())
              .remaining());
      var facts =
          f.store.transaction(
              tx ->
                  tx.query(
                      "SELECT record_version,payload FROM journal_records WHERE record_type=? ORDER BY sequence",
                      List.of(Deposits.FACT),
                      8));
      for (var fact : facts) {
        assertEquals("3", fact.get("record_version"));
        Deposits.validateFactV3(fact.get("payload"));
        assertThrows(
            IllegalArgumentException.class, () -> Deposits.validateFactV2(fact.get("payload")));
      }
    }
  }

  @Test
  void pricedWithdrawalDebitsFullPrincipalPlusSeparateFeeAndFundsOnlyTheCashPrincipal()
      throws Exception {
    try (var f = new Fixture(10000)) {
      var preview = f.withdrawPreview();
      assertNotNull(preview.pricing());
      assertEquals(2806, preview.debit().minorUnits());
      var reserve =
          f.intent(Withdrawals.Action.RESERVE.kind(), Withdrawals.reserveRequest(preview));
      assertEquals(
          Journal.Status.COMMITTED, f.withdraw(Withdrawals.Action.RESERVE, reserve).status());
      var commit =
          f.intent(
              Withdrawals.Action.COMMIT.kind(),
              Withdrawals.request(reserve.operationId().value(), "fits", "fixture"));
      var result = f.withdraw(Withdrawals.Action.COMMIT, commit);
      assertEquals(Journal.Status.COMMITTED, result.status(), result.result().code());
      assertEquals(result, f.withdraw(Withdrawals.Action.COMMIT, commit));
      assertEquals(7194, f.balance());
      assertEquals(14, f.bankCash(CAD));
      var facts =
          f.store.transaction(
              tx ->
                  tx.query(
                      "SELECT payload FROM journal_records WHERE record_type=?",
                      List.of(Withdrawals.FACT),
                      8));
      for (var fact : facts) {
        Withdrawals.validateFactV3(fact.get("payload"));
        assertThrows(
            IllegalArgumentException.class, () -> Withdrawals.validateFactV2(fact.get("payload")));
      }
    }
  }

  @Test
  void staleWealthDuringCashReservationRejectsCommitWithoutChargingOrConsumingCash()
      throws Exception {
    try (var f = new Fixture(10000)) {
      var preview = f.depositPreview();
      var reserve = f.intent(Deposits.Action.RESERVE.kind(), Deposits.reserveRequest(preview));
      f.deposit(Deposits.Action.RESERVE, reserve);
      var id = reserve.operationId().value();
      f.deposit(
          Deposits.Action.SAVED,
          f.intent(Deposits.Action.SAVED.kind(), Deposits.request(id, "saved", "fixture")));
      f.store.transaction(
          tx -> {
            LedgerProjection.write(tx, f.account, CAD, 9999);
            return null;
          });
      var result =
          f.deposit(
              Deposits.Action.COMMIT,
              f.intent(Deposits.Action.COMMIT.kind(), Deposits.request(id, "saved", "fixture")));
      assertEquals(Journal.Status.DURABLY_REJECTED, result.status());
      assertEquals(0, f.bankCash(USD));
      assertEquals(9999, f.balance());
      assertEquals(
          2,
          f.store
              .transaction(tx -> CashIssuance.find(tx::query, f.world, f.lot).orElseThrow())
              .remaining());
    }
  }

  @Test
  void assignedCashFxCannotUseLegacyReaderWithoutServerPricingContext() throws Exception {
    try (var f = new Fixture(10000)) {
      var deposit =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  f.store.transaction(
                      tx ->
                          Deposits.preview(
                              tx::query,
                              f.world,
                              f.owner,
                              new Deposits.Binding(
                                  f.account,
                                  1,
                                  CAD,
                                  f.anchor,
                                  f.generation,
                                  3,
                                  f.publication.updateId().toString()),
                              List.of(
                                  new Deposits.Slot(
                                      0,
                                      new CashIssuance.Claim(f.world, f.lot, USD_NOTE, 1),
                                      "issued")),
                              f.deposits,
                              reads ->
                                  new Deposits.FxSnapshot(
                                      f.publication.updateId().toString(),
                                      f.publication.conversionRates()))));
      assertEquals("PRICING_CONTEXT_UNAVAILABLE", deposit.getMessage());
      var withdrawal =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  f.store.transaction(
                      tx ->
                          Withdrawals.preview(
                              tx::query,
                              f.world,
                              f.owner,
                              new Withdrawals.Binding(
                                  f.account,
                                  1,
                                  CAD,
                                  f.anchor,
                                  f.generation,
                                  3,
                                  f.publication.updateId().toString()),
                              new Money(USD, 2000),
                              null,
                              f.withdrawals,
                              reads ->
                                  new Withdrawals.FxSnapshot(
                                      f.publication.updateId().toString(),
                                      f.publication.conversionRates()))));
      assertEquals("PRICING_CONTEXT_UNAVAILABLE", withdrawal.getMessage());
    }
  }

  @Test
  void specialistSameCurrencyDepositRequiresFreshTierTwoAdmissionEvenWithoutAnFxFee()
      throws Exception {
    try (var f = new Fixture(10000, true)) {
      var failure = assertThrows(IllegalArgumentException.class, f::depositPreview);
      assertEquals("TIER2_UNAVAILABLE", failure.getMessage());
      f.store.transaction(
          tx -> {
            LedgerProjection.write(tx, f.account, USD, 10_000_000_000L);
            return null;
          });
      var qualified = f.depositPreview();
      assertEquals(2000, qualified.credit().minorUnits());
      assertNull(qualified.groups().getFirst().pricing());
    }
  }

  @Test
  void qualifiedHybridCashDepositConvertsFullGrossWithExactlyZeroFee() throws Exception {
    try (var f = new Fixture(10_000_000_000L)) {
      var preview = f.depositPreview();
      var price =
          f.store.transaction(
              tx ->
                  CashPricing.requireFresh(
                      f.context(), tx::query, preview.groups().getFirst().pricing()));
      assertEquals(2000, price.principalMinor());
      assertEquals(0, price.fee().minorUnits());
      assertEquals(2792, preview.credit().minorUnits());
    }
  }
}
