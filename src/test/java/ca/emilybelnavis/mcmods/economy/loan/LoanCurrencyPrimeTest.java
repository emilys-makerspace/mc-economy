package ca.emilybelnavis.mcmods.economy.loan;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.simulation.activity.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.MacroModel;
import ca.emilybelnavis.mcmods.economy.simulation.plan.ActivationModelPlans;
import ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes;
import ca.emilybelnavis.mcmods.economy.simulation.window.WindowStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real funded-loan/journal witnesses for additive currency policy capture and fixed servicing. */
class LoanCurrencyPrimeTest {
  @TempDir Path path;

  @Test
  void twoCurrenciesCaptureDistinctPublishedPrimeAndRejectWrongOrLegacySelection()
      throws Exception {
    try (var f = new LoanTestFixture(path.toRealPath())) {
      var activation = activate(f);
      f.clock.set(activation.effectiveMillis() + 1);
      var adjustment =
          new SameCurrency.Adjustment(
              f.actor,
              new SameCurrency.Revision(f.account, 1),
              new Money(EconomyCurrency.CAD, 100_000));
      var credit =
          SameCurrency.registrations(f.owner.world(), (i, a, r) -> true).get(SameCurrency.CREDIT);
      assertEquals(
          Journal.Status.COMMITTED,
          f.submit(SameCurrency.CREDIT, credit, f.intent(SameCurrency.CREDIT, adjustment.request()))
              .status());
      long boundary = activation.firstEventMillis();
      f.clock.set(boundary);
      var open =
          WindowStore.open(f.store, boundary, boundary, activation.config(), activation.id());
      for (var currency : EconomyCurrency.all()) WindowStore.close(f.store, open.id(), currency);
      ActivationModelPlans.prepare(
          f.store,
          activation.id(),
          open.id(),
          activation.reference(),
          activation.anchors(),
          MacroModel.Config.defaults(activation.config(), 1));
      for (var currency : EconomyCurrency.all())
        CurrencyPrimes.publish(f.store, open.id(), currency, CurrencyPrimes.Mode.AUTO);
      CurrencyPrimes.complete(f.store, open.id());
      f.clock.incrementAndGet();
      var cad = selection(f, EconomyCurrency.CAD);
      var usd = selection(f, EconomyCurrency.USD);
      assertNotEquals(cad.id(), usd.id());
      assertNotEquals(0, cad.primeApr().compareTo(usd.primeApr()));
      var legacy = f.application(CreditUnderwriting.Product.BANK_LOAN, 100_000, 1);
      assertEquals(
          "LOAN_PRIME_STALE",
          f.apply(legacy, f.intent(Loans.APPLY, legacy.request())).result().code());
      var cadRequest = bound(legacy, cad);
      var originated = f.apply(cadRequest, f.intent(Loans.APPLY, cadRequest.request()));
      assertEquals("LOAN_ORIGINATED", originated.result().code());
      var cadLoan = f.get(cadRequest.loan());
      assertEquals(cad.id(), cadLoan.primePolicyId());
      assertEquals(cad.revision(), cadLoan.primePolicyRevision());
      assertEquals(0, cad.primeApr().compareTo(cadLoan.primeApr()));
      assertEquals(2, Loans.contractRecord(cadLoan).version());
      assertEquals(cadLoan, Loans.decode(Loans.contractRecord(cadLoan).payload(), 2));
      assertThrows(
          IllegalArgumentException.class,
          () -> Loans.decode(Loans.contractRecord(cadLoan).payload(), 1));
      var usdAccount = new AccountId(UUID.randomUUID());
      f.store.transaction(
          tx ->
              tx.execute(
                  "INSERT INTO account_catalog VALUES(?,?,'BANK','USD','OPEN',1,0)",
                  List.of(usdAccount.value().toString(), f.actor.value().toString())));
      var crosswind = new InstitutionCatalog.InstitutionId("economy:bank/crosswind");
      var proof =
          BranchFixtures.proof(
              new BranchAdmission.Context(
                  f.owner.world(),
                  UUID.randomUUID(),
                  f.actor,
                  BranchAdmission.Action.LOAN_APPLY,
                  crosswind,
                  null,
                  null,
                  0,
                  0,
                  0));
      long profile =
          f.store.transaction(
              tx -> CreditProfiles.find(tx::query, f.owner).orElseThrow().revision());
      UUID publication = f.store.transaction(tx -> FxService.latest(tx::query)).updateId();
      var wrong =
          new Loans.Application(
              proof,
              UUID.randomUUID(),
              UUID.randomUUID(),
              CreditUnderwriting.Product.BANK_LOAN,
              EconomyCurrency.USD,
              100_000,
              1,
              usdAccount,
              1,
              profile,
              0,
              0,
              publication,
              LoanTestFixture.NOW.toEpochMilli(),
              Loans.RateMode.FIXED,
              false,
              cad.id(),
              cad.revision());
      assertEquals(
          "LOAN_PRIME_STALE",
          f.apply(wrong, f.intent(Loans.APPLY, wrong.request())).result().code());
      var usdRequest = bound(wrong, usd);
      assertEquals(
          "LOAN_ORIGINATED",
          f.apply(usdRequest, f.intent(Loans.APPLY, usdRequest.request())).result().code());
      var usdLoan = f.get(usdRequest.loan());
      assertEquals(usd.id(), usdLoan.primePolicyId());
      assertEquals(0, usd.primeApr().compareTo(usdLoan.primeApr()));
      var decision =
          f.store.transaction(
              tx ->
                  Loans.applicationDecision(tx::query, f.owner, usdRequest.application())
                      .orElseThrow());
      assertEquals(usd.id(), decision.primePolicyId());
      assertEquals(usd.revision(), decision.primePolicyRevision());
      var originalRate = cadLoan.apr();
      f.clock.set(cadLoan.nextMonth());
      var monthly = new Journal.Kind("fixture:prime", "fixture:month");
      var registration =
          new JournalEngine.Registration(
              1, (i, r) -> Loans.month(r, cadLoan.id(), cadLoan.nextMonth()));
      assertEquals(
          Journal.Status.COMMITTED,
          f.submit(
                  monthly,
                  registration,
                  f.intent(monthly, new Journal.CanonicalRequest(1, Map.of())))
              .status());
      var serviced = f.get(cadLoan.id());
      assertEquals(originalRate, serviced.apr());
      assertEquals(cad.id(), serviced.primePolicyId());
      assertEquals(cad.revision(), serviced.primePolicyRevision());
      f.store.close();
      f.store = new ca.emilybelnavis.mcmods.economy.storage.SqliteStorage();
      f.store.initialize(path.toRealPath());
      assertEquals(serviced, f.get(cadLoan.id()));
    }
  }

  @Test
  void oldFixedFactsRemainV1AndSubprimeDoesNotCaptureCurrencyPrime() throws Exception {
    try (var f = new LoanTestFixture(path.toRealPath())) {
      var old = f.originate();
      var fact = Loans.contractRecord(old);
      assertEquals(1, fact.version());
      assertNull(old.primePolicyId());
      assertEquals(old, Loans.decode(fact.payload(), 1));
      var activation = activate(f);
      f.clock.set(activation.effectiveMillis() + 1);
      var subprime = f.application(CreditUnderwriting.Product.SUBPRIME_LOAN, 50_000, 1);
      assertEquals(
          "LOAN_ORIGINATED",
          f.apply(subprime, f.intent(Loans.APPLY, subprime.request())).result().code());
      var loan = f.get(subprime.loan());
      assertNull(loan.primePolicyId());
      assertEquals(0, loan.primePolicyRevision());
      assertEquals(0, loan.primeVersion());
      assertEquals(1, Loans.contractRecord(loan).version());
      assertEquals(fact, Loans.contractRecord(f.get(old.id())));
    }
  }

  private static CurrencyPrimes.Activation activate(LoanTestFixture f) {
    f.clock.addAndGet(10);
    var wall =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId zone) {
            return this;
          }

          public Instant instant() {
            return Instant.ofEpochMilli(f.clock.get());
          }
        };
    var activity = new ActivityRuntime(f.store, wall);
    boolean ready = false;
    for (int n = 0; n < 100; n++)
      if (activity.prepare()) {
        ready = true;
        break;
      }
    assertTrue(ready);
    f.store.commitObserver((i, r, c) -> activity.observe(i, r, c, f.clock.get()));
    return CurrencyPrimes.activate(
        f.store,
        UUID.randomUUID(),
        LoanTestFixture.NOW.toEpochMilli() + ActivityFacts.DAY_MILLIS,
        UUID.randomUUID(),
        wall.instant());
  }

  private static CurrencyPrimes.Selection selection(LoanTestFixture f, EconomyCurrency currency) {
    return f.store.transaction(
        tx -> CurrencyPrimes.select(tx::query, f.owner.world(), currency, f.clock.get()));
  }

  private static Loans.Application bound(Loans.Application a, CurrencyPrimes.Selection prime) {
    return new Loans.Application(
        a.proof(),
        a.application(),
        a.loan(),
        a.product(),
        a.currency(),
        a.principalMinor(),
        a.years(),
        a.destination(),
        a.destinationRevision(),
        a.profileRevision(),
        a.tierRevision(),
        0,
        a.fxPublication(),
        a.calendarOrigin(),
        a.rateMode(),
        a.secured(),
        prime.id(),
        prime.revision());
  }
}
