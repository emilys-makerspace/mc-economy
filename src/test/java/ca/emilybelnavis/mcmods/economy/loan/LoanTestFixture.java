package ca.emilybelnavis.mcmods.economy.loan;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.math.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Shared real SQLite loan fixture, owned only by disposable test processes. */
class LoanTestFixture implements AutoCloseable {
  static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
  static final InstitutionCatalog.InstitutionId BANK =
      new InstitutionCatalog.InstitutionId("economy:bank/crownstone");
  final Path path;

  SqliteStorage store = new SqliteStorage();
  final PlayerId actor = new PlayerId(UUID.randomUUID());
  final AccountId account = new AccountId(UUID.randomUUID());
  final AtomicLong clock = new AtomicLong(NOW.toEpochMilli());
  final PartyRef owner;

  LoanTestFixture(Path testPath) throws Exception {
    this.path = testPath;
    store.initialize(path.toRealPath());
    owner = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(actor));
    assertEquals(
        Journal.Status.COMMITTED,
        submit(
                FxService.OPEN,
                FxService.opening(Clock.fixed(NOW, ZoneOffset.UTC)),
                intent(FxService.OPEN, new Journal.CanonicalRequest(1, Map.of("currency", "CAD"))))
            .status());
    var capital =
        new InstitutionCapital(
            store, new SystemActorId(UUID.randomUUID()), CapitalPosting.poster());
    for (int n = 0; n < 26; n++)
      assertEquals(Journal.Status.COMMITTED, capital.advance(NOW).status());
    store.transaction(
        tx -> {
          tx.execute(
              "INSERT INTO financial_calendar(singleton,version,origin_ms,observed_wall_ms,last_effective_ms,completed_effects) VALUES(1,1,?,?,?,0)",
              List.of(
                  Long.toString(clock.get()),
                  Long.toString(clock.get()),
                  Long.toString(clock.get())));
          tx.execute(
              "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
              List.of(account.value().toString(), actor.value().toString()));
          return null;
        });
    var proof =
        BranchFixtures.proof(
            new BranchAdmission.Context(
                owner.world(),
                UUID.randomUUID(),
                actor,
                BranchAdmission.Action.CREDIT_ISSUE,
                BANK,
                null,
                null,
                0,
                0,
                0));
    var issue =
        new CreditFacilities.IssueRequest(
            proof,
            UUID.randomUUID(),
            InstitutionCatalog.Network.ORBIT,
            EconomyCurrency.CAD,
            250000,
            0,
            0,
            NOW.toEpochMilli());
    assertEquals(
        "CREDIT_CARD_ISSUED",
        submit(
                CreditFacilities.ISSUE,
                CreditFacilities.issuance(
                    owner,
                    issue,
                    BankProviders.NO_TIER2,
                    (r, o, n) -> new CreditFacilities.Inputs(fx(r, n), true, false, 0),
                    clock::get),
                intent(CreditFacilities.ISSUE, issue.request()))
            .result()
            .code());
    var close = CreditFacilities.closeRequest(owner.world(), actor, issue.facility(), 1);
    assertEquals(
        "CREDIT_FACILITY_CLOSED",
        submit(
                CreditFacilities.CLOSE,
                CreditFacilities.closure(owner, issue.facility(), 1, clock::get),
                intent(CreditFacilities.CLOSE, close))
            .result()
            .code());
    var fundingKind = new Journal.Kind("fixture:loans", "fixture:fund");
    var legs =
        List.of(
            new PostingBatch.Leg(
                Settlement.issuanceAccount(EconomyCurrency.CAD),
                PostingBatch.Role.ISSUANCE,
                EconomyCurrency.CAD,
                new Money(EconomyCurrency.CAD, -1000000)),
            new PostingBatch.Leg(
                account.value(),
                PostingBatch.Role.ACCOUNT,
                EconomyCurrency.CAD,
                new Money(EconomyCurrency.CAD, 1000000)));
    assertEquals(
        Journal.Status.COMMITTED,
        submit(
                fundingKind,
                Settlement.registrationMinor((i, l, r) -> PostingBatch.Authority.ISSUE),
                intent(fundingKind, Settlement.minorRequest(legs)))
            .status());
  }

  CreditUnderwriting.FxSnapshot fx(JournalEngine.ReadView reads, long now) {
    var f = FxService.latest(reads);
    return new CreditUnderwriting.FxSnapshot(
        f.updateId(),
        f.day().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        now,
        f.rates(),
        true);
  }

  Loans.Inputs inputs(JournalEngine.ReadView reads, PartyRef owner, long now) {
    return new Loans.Inputs(
        fx(reads, now),
        true,
        false,
        CreditFacilities.exposure(reads, owner, EconomyCurrency.CAD, fx(reads, now)));
  }

  Loans.Application application(CreditUnderwriting.Product product, long principal, int years) {
    var proof =
        BranchFixtures.proof(
            new BranchAdmission.Context(
                owner.world(),
                UUID.randomUUID(),
                actor,
                BranchAdmission.Action.LOAN_APPLY,
                BANK,
                null,
                null,
                0,
                0,
                0));
    var publication = store.transaction(tx -> FxService.latest(tx::query)).updateId();
    return new Loans.Application(
        proof,
        UUID.randomUUID(),
        UUID.randomUUID(),
        product,
        EconomyCurrency.CAD,
        principal,
        years,
        account,
        1,
        store.transaction(tx -> CreditProfiles.find(tx::query, owner).orElseThrow().revision()),
        0,
        product == CreditUnderwriting.Product.BANK_LOAN ? 1 : 0,
        publication,
        NOW.toEpochMilli(),
        Loans.RateMode.FIXED,
        false);
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

  Journal.Outcome submit(
      Journal.Kind kind, JournalEngine.Registration registration, Journal.Intent intent) {
    return new JournalEngine(store, (a, k) -> true, Map.of(kind, registration)).submit(intent);
  }

  Journal.Outcome apply(Loans.Application a, Journal.Intent intent) {
    return submit(
        Loans.APPLY,
        Loans.application(owner, a, BankProviders.NO_TIER2, this::inputs, clock::get),
        intent);
  }

  Loans.Contract get(UUID id) {
    return store.transaction(tx -> Loans.find(tx::query, id).orElseThrow());
  }

  Loans.Contract originate() {
    var a = application(CreditUnderwriting.Product.BANK_LOAN, 100000, 1);
    assertEquals("LOAN_ORIGINATED", apply(a, intent(Loans.APPLY, a.request())).result().code());
    return get(a.loan());
  }

  long amount(InstitutionCatalog.InstitutionId lender, FinancialAccounts.Role role) {
    return store.transaction(
        tx ->
            Settlement.posted(
                tx::query,
                FinancialAccounts.id(owner.world(), lender, role, EconomyCurrency.CAD),
                EconomyCurrency.CAD));
  }

  void reopen() throws Exception {
    store.close();
    store = new SqliteStorage();
    store.initialize(path.toRealPath());
  }

  @Override
  public void close() throws Exception {
    store.close();
  }
}
