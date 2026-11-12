package ca.emilybelnavis.mcmods.economy.card;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.foundation.CardReplacementPricing;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.math.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CardReplacementPricingTest {
  @TempDir Path path;
  private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
  private static final InstitutionCatalog.InstitutionId ISSUER =
      new InstitutionCatalog.InstitutionId("economy:bank/crownstone");

  private final class Fixture implements AutoCloseable {
    final SqliteStorage store = new SqliteStorage();
    final PlayerId actor = new PlayerId(UUID.randomUUID());
    final AccountId account = new AccountId(UUID.randomUUID());
    final AccountId card;
    final CardReplacementPricing fees = new CardReplacementPricing();
    final ServicePricing pricing = new ServicePricing(new WealthValuation());
    final EconomyCurrency currency;
    final InstitutionCatalog.InstitutionId provider;
    final WorldId world;
    final PartyRef owner;
    final FxFacts.Publication publication;
    final FxQuotes fx;

    Fixture(EconomyCurrency currency, long balance) throws Exception {
      this.card = currency == EconomyCurrency.CAD ? account : new AccountId(UUID.randomUUID());
      this.currency = currency;
      this.provider =
          currency == EconomyCurrency.CAD
              ? ISSUER
              : new InstitutionCatalog.InstitutionId("economy:bank/meridian_grove");
      store.initialize(path.toRealPath());
      world = store.snapshot().worldId();
      owner = new PartyRef(world, new PartyRef.Player(actor));
      fx = new FxQuotes(world);
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
                "INSERT INTO account_catalog VALUES(?,?,'BANK',?,'OPEN',1,1)",
                List.of(account.value().toString(), actor.value().toString(), currency.name()));
            tx.execute(
                "INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",
                List.of(account.value().toString(), provider.key()));
            if (!card.equals(account)) {
              tx.execute(
                  "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,0)",
                  List.of(card.value().toString(), actor.value().toString()));
              tx.execute(
                  "INSERT INTO bank_account_providers VALUES(?,?,0,1,0)",
                  List.of(card.value().toString(), ISSUER.key()));
            }
            for (var bank : List.of(ISSUER, provider)) {
              var entry = InstitutionCatalog.require(bank);
              tx.execute(
                  "INSERT OR IGNORE INTO institution_parties VALUES(?,?,?,?,?,?,1)",
                  List.of(
                      world.value().toString(),
                      bank.key(),
                      new InstitutionCatalog.SystemActor(world, bank).actorId().value().toString(),
                      entry.name(),
                      entry.baseCurrency().name(),
                      entry.tier().name()));
            }
            LedgerProjection.write(tx, account.value(), currency, balance);
            return null;
          });
    }

    FxQuotes.Quote fxQuote() {
      if (currency == EconomyCurrency.CAD) return null;
      var quote =
          fx.issue(
              actor,
              new FxQuotes.Pair(currency, EconomyCurrency.CAD),
              FxQuotes.Side.EXACT_CREDIT,
              new Money(EconomyCurrency.CAD, 2500),
              publication,
              NOW,
              Duration.ofSeconds(60));
      return store.transaction(
          tx ->
              fx.price(
                  quote,
                  pricing.quote(
                      tx::query,
                      world,
                      actor,
                      provider,
                      ServicePricing.Service.FX,
                      quote.debit(),
                      NOW,
                      quote.expires())));
    }

    IssuedCards.FeeQuote capture(FxQuotes.Quote quote) {
      return store.transaction(
          tx ->
              fees.capture(
                  tx::query,
                  owner,
                  ISSUER,
                  card,
                  AccountCatalog.find(tx::query, owner, account).orElseThrow(),
                  quote,
                  NOW.toEpochMilli()));
    }

    Journal.Intent intent(Journal.Kind kind, Journal.CanonicalRequest request) {
      return new Journal.Intent(
          new OperationId(UUID.randomUUID()),
          new RequestId(UUID.randomUUID()),
          actor,
          kind,
          request,
          null,
          null);
    }

    Journal.Intent feeIntent(IssuedCards.FeeQuote quote) {
      return intent(
          IssuedCards.ISSUE,
          new Journal.CanonicalRequest(
              1,
              Map.of(
                  "bank",
                  ISSUER.key(),
                  "account",
                  card.value().toString(),
                  "quote",
                  quote.id().toString())));
    }

    JournalEngine engine(IssuedCards.FeeQuote quote) {
      return new JournalEngine(
          store,
          (a, k) -> true,
          Map.of(
              IssuedCards.ISSUE,
              new JournalEngine.Registration(
                  1,
                  (intent, reads) ->
                      fees.prepare(
                          intent,
                          quote,
                          reads,
                          original -> {
                            var fresh =
                                fx.requireFresh(
                                    original.id(), actor, world, FxService.latest(reads), 1, NOW);
                            if (fresh.pricing() != null)
                              pricing.requireFresh(fresh.pricing(), reads, world, actor, NOW);
                            return fresh;
                          },
                          NOW.toEpochMilli()))));
    }

    long balance() {
      return store.transaction(tx -> Settlement.posted(tx::query, account.value(), currency));
    }

    long cash(InstitutionCatalog.InstitutionId bank) {
      return store.transaction(
          tx -> FinancialAccounts.spendableCash(tx::query, world, bank, currency));
    }

    long feesConsumed() {
      return store.transaction(
          tx ->
              Long.parseLong(
                  tx.query("SELECT count(*) AS n FROM issued_card_fee_quotes", List.of(), 1)
                      .getFirst()
                      .get("n")));
    }

    long consumed() {
      return store.transaction(
          tx ->
              Long.parseLong(
                  tx.query("SELECT count(*) AS n FROM ledger_fx_quotes", List.of(), 1)
                      .getFirst()
                      .get("n")));
    }

    @Override
    public void close() throws Exception {
      store.close();
    }
  }

  @Test
  void sameCurrencyChargesExactlyTwentyFiveWithoutFxAndReplayChargesOnce() throws Exception {
    try (var f = new Fixture(EconomyCurrency.CAD, 10000)) {
      var quote = f.capture(null);
      assertEquals(2500, quote.debitMinor());
      assertEquals(EconomyCurrency.CAD, quote.currency());
      var original = f.feeIntent(quote);
      var engine = f.engine(quote);
      var result = engine.submit(original);
      assertEquals(Journal.Status.COMMITTED, result.status(), result.result().code());
      assertEquals(result, engine.submit(original));
      assertEquals(7500, f.balance());
      assertEquals(2500, f.cash(ISSUER));
      assertEquals(0, f.consumed());
      assertEquals(1, f.feesConsumed());
      assertEquals("CARD_FEE_USED", engine.submit(f.feeIntent(quote)).result().code());
      assertEquals(7500, f.balance());
    }
  }

  @Test
  void foreignFundingUsesNearestMinorConversionAndOneCombinedFxFee() throws Exception {
    try (var f = new Fixture(EconomyCurrency.EUR, 10000)) {
      var fx = f.fxQuote();
      long principal =
          new BigDecimal("25.00")
              .multiply(
                  f.publication
                      .rates()
                      .get(EconomyCurrency.CAD)
                      .divide(f.publication.rates().get(EconomyCurrency.EUR), 8, RoundingMode.DOWN))
              .setScale(8, RoundingMode.DOWN)
              .movePointRight(2)
              .setScale(0, RoundingMode.HALF_UP)
              .longValueExact();
      assertEquals(principal, fx.debit().minorUnits());
      assertEquals(2500, fx.credit().minorUnits());
      long surcharge =
          BigDecimal.valueOf(principal)
              .multiply(new BigDecimal("0.005"))
              .setScale(0, RoundingMode.CEILING)
              .longValueExact();
      assertEquals(surcharge, fx.fee().minorUnits());
      var quote = f.capture(fx);
      assertEquals(principal + surcharge, quote.debitMinor());
      var original = f.feeIntent(quote);
      var engine = f.engine(quote);
      var result = engine.submit(original);
      assertEquals(Journal.Status.COMMITTED, result.status(), result.result().code());
      assertEquals(result, engine.submit(original));
      assertEquals(10000 - principal - surcharge, f.balance());
      assertEquals(principal, f.cash(ISSUER));
      assertEquals(surcharge, f.cash(f.provider));
      assertEquals(1, f.consumed());
      assertEquals(1, f.feesConsumed());
    }
  }

  @Test
  void combinedBankFeeCanOverdrawAndConsumesFxQuoteExactlyOnce() throws Exception {
    try (var f = new Fixture(EconomyCurrency.EUR, 10000)) {
      long principal = f.fxQuote().debit().minorUnits();
      f.store.transaction(
          tx -> {
            LedgerProjection.write(tx, f.account.value(), f.currency, principal);
            return null;
          });
      var fx = f.fxQuote();
      var quote = f.capture(fx);
      var original = f.feeIntent(quote);
      var engine = f.engine(quote);
      var result = engine.submit(original);
      assertEquals(Journal.Status.COMMITTED, result.status());
      assertEquals(result, engine.submit(original));
      assertEquals(-fx.fee().minorUnits(), f.balance());
      assertEquals(principal, f.cash(ISSUER));
      assertEquals(fx.fee().minorUnits(), f.cash(f.provider));
      assertEquals(1, f.consumed());
      assertEquals(1, f.feesConsumed());
    }
  }

  @Test
  void forgedFeeAndChangedFundingProviderAreRejectedWithoutPosting() throws Exception {
    try (var f = new Fixture(EconomyCurrency.CAD, 10000)) {
      var quote = f.capture(null);
      var forged =
          new IssuedCards.FeeQuote(
              quote.id(),
              quote.funding(),
              quote.fundingRevision(),
              quote.denominationRevision(),
              quote.currency(),
              quote.debitMinor() - 1,
              quote.expiresAt(),
              quote.capture());
      assertEquals("CARD_FEE_STALE", f.engine(forged).submit(f.feeIntent(forged)).result().code());
      f.store.transaction(
          tx -> {
            tx.execute(
                "UPDATE bank_account_providers SET revision=2,debit_epoch=1 WHERE account_id=?",
                List.of(f.account.value().toString()));
            return null;
          });
      assertEquals("CARD_FEE_STALE", f.engine(quote).submit(f.feeIntent(quote)).result().code());
      assertEquals(10000, f.balance());
      assertEquals(0, f.cash(ISSUER));
    }
  }

  @Test
  void changedDenominationRevisionAndExpiredQuoteCannotCharge() throws Exception {
    try (var f = new Fixture(EconomyCurrency.CAD, 10000)) {
      var quote = f.capture(null);
      var intent = f.feeIntent(quote);
      var changed =
          f.store.transaction(
              tx ->
                  f.fees.prepare(
                      intent,
                      quote,
                      (sql, args, max) ->
                          sql.contains("record_type='economy:rebase'")
                              ? List.of(Map.of("payload", "CAD|1"))
                              : tx.query(sql, args, max),
                      q -> q,
                      NOW.toEpochMilli()));
      assertEquals("CARD_FEE_STALE", ((JournalEngine.Reject) changed).result().code());
      var expired =
          f.store.transaction(
              tx -> f.fees.prepare(intent, quote, tx::query, q -> q, quote.expiresAt()));
      assertEquals("CARD_FEE_STALE", ((JournalEngine.Reject) expired).result().code());
      assertEquals(10000, f.balance());
    }
  }
}
