package ca.emilybelnavis.mcmods.economy.loan;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.credit.CreditUnderwriting;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Disposable child process paused only at the parent test's declared transaction boundary. */
public final class LoanProcessProbe {
  private LoanProcessProbe() {}

  static UUID stable(String key) {
    return UUID.nameUUIDFromBytes(
        ("economy:loan-recovery:v1|" + key).getBytes(StandardCharsets.UTF_8));
  }

  static Properties properties(Path world) throws Exception {
    var properties = new Properties();
    try (var input = Files.newBufferedReader(world.resolve("fixture-loan.properties"))) {
      properties.load(input);
    }
    return properties;
  }

  static void seed(Path world, String phase) throws Exception {
    try (var fixture = new LoanTestFixture(world)) {
      var properties = new Properties();
      properties.setProperty("owner", fixture.actor.value().toString());
      properties.setProperty("account", fixture.account.value().toString());
      properties.setProperty("anchor", Long.toString(fixture.clock.get()));
      Journal.CanonicalRequest request;
      if (phase.equals("origination")) {
        var application = fixture.application(CreditUnderwriting.Product.BANK_LOAN, 100000, 1);
        properties.setProperty("loan", application.loan().toString());
        request = application.request();
      } else {
        var loan = fixture.originate();
        properties.setProperty("loan", loan.id().toString());
        properties.setProperty(
            "source",
            fixture
                .store
                .transaction(
                    tx ->
                        tx.query(
                            "SELECT body FROM loans WHERE loan_id=?",
                            List.of(loan.id().toString()),
                            1))
                .getFirst()
                .get("body"));
        if (phase.equals("month")) {
          request =
              new Journal.CanonicalRequest(1, Map.of("effective", Long.toString(loan.nextMonth())));
        } else {
          long captured = fixture.clock.get() + Loans.MONTH / 2;
          var quote =
              fixture.store.transaction(
                  tx ->
                      Loans.quote(
                          tx::query,
                          fixture.owner,
                          loan.id(),
                          fixture.account,
                          10000,
                          phase.equals("payoff"),
                          captured));
          request = quote.request().request();
        }
      }
      for (var entry : request.fields().entrySet())
        properties.setProperty("request." + entry.getKey(), entry.getValue());
      properties.setProperty(
          "cash", Long.toString(fixture.amount(LoanTestFixture.BANK, FinancialAccounts.Role.CASH)));
      properties.setProperty(
          "customer",
          Long.toString(
              fixture.store.transaction(
                  tx ->
                      Settlement.posted(tx::query, fixture.account.value(), EconomyCurrency.CAD))));
      try (var output = Files.newBufferedWriter(world.resolve("fixture-loan.properties"))) {
        properties.store(output, "Disposable loan recovery fixture");
      }
    }
  }

  static Journal.Kind kind(String phase) {
    return switch (phase) {
      case "origination" -> Loans.APPLY;
      case "partial", "payoff" -> Loans.REPAY;
      case "month" -> new Journal.Kind("fixture:loan-recovery", "fixture:month");
      default -> throw new IllegalArgumentException("Unknown loan recovery phase");
    };
  }

  static Journal.Intent target(Path world, String phase) throws Exception {
    var properties = properties(world);
    var fields = new TreeMap<String, String>();
    for (String key : properties.stringPropertyNames()) {
      if (key.startsWith("request.")) fields.put(key.substring(8), properties.getProperty(key));
    }
    return new Journal.Intent(
        new OperationId(stable("operation|" + phase)),
        new RequestId(stable("request|" + phase)),
        new PlayerId(UUID.fromString(properties.getProperty("owner"))),
        kind(phase),
        new Journal.CanonicalRequest(1, fields),
        null,
        null);
  }

  static Loans.Contract contract(FoundationStore store, Path world) throws Exception {
    UUID loan = UUID.fromString(properties(world).getProperty("loan"));
    return store.transaction(tx -> Loans.find(tx::query, loan).orElseThrow());
  }

  static Journal.Outcome submit(
      FoundationStore store, Journal.Intent intent, JournalEngine.Registration registration) {
    return new JournalEngine(
            store,
            (actor, kind) -> actor.equals(intent.actor()),
            Map.of(intent.kind(), registration))
        .submit(intent);
  }

  static JournalEngine.Registration registration(FoundationStore store, Path world, String phase)
      throws Exception {
    var properties = properties(world);
    var target = target(world, phase);
    var fields = target.request().fields();
    var actor = (PlayerId) target.actor();
    var owner = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(actor));
    var account = new AccountId(UUID.fromString(properties.getProperty("account")));
    UUID loanId = UUID.fromString(properties.getProperty("loan"));
    long anchor = Long.parseLong(properties.getProperty("anchor"));
    if (phase.equals("origination")) {
      var proof =
          BranchFixtures.proof(
              new BranchAdmission.Context(
                  owner.world(),
                  UUID.fromString(fields.get("session")),
                  actor,
                  BranchAdmission.Action.LOAN_APPLY,
                  LoanTestFixture.BANK,
                  null,
                  null,
                  0,
                  0,
                  0));
      var application =
          new Loans.Application(
              proof,
              UUID.fromString(fields.get("application")),
              loanId,
              CreditUnderwriting.Product.BANK_LOAN,
              EconomyCurrency.CAD,
              100000,
              1,
              account,
              1,
              Long.parseLong(fields.get("profile_revision")),
              0,
              1,
              UUID.fromString(fields.get("publication")),
              anchor,
              Loans.RateMode.FIXED,
              false);
      var core =
          Loans.application(
              owner,
              application,
              BankProviders.NO_TIER2,
              (reads, party, now) -> {
                var fx = FxService.latest(reads);
                return new Loans.Inputs(
                    new CreditUnderwriting.FxSnapshot(fx.updateId(), anchor, now, fx.rates(), true),
                    true,
                    false,
                    0);
              },
              () -> anchor);
      // A test-only fresh branch admission bridges the killed JVM's ephemeral proof. The outer
      // original intent stays frozen.
      return new JournalEngine.Registration(
          1,
          (intent, reads) ->
              core.handler()
                  .prepare(
                      new Journal.Intent(
                          intent.operationId(),
                          intent.requestId(),
                          intent.actor(),
                          intent.kind(),
                          application.request(),
                          intent.economicUpdateId(),
                          intent.correctionOf()),
                      reads));
    }
    if (phase.equals("month")) {
      return new JournalEngine.Registration(
          1,
          (intent, reads) -> Loans.month(reads, loanId, Long.parseLong(fields.get("effective"))));
    }
    var source = Loans.decode(properties.getProperty("source"));
    long captured = Long.parseLong(fields.get("captured_at"));
    var request =
        new Loans.RepaymentRequest(
            owner.world(),
            actor,
            loanId,
            account,
            Long.parseLong(fields.get("amount")),
            Long.parseLong(fields.get("loan_revision")),
            Long.parseLong(fields.get("funding_revision")),
            Long.parseLong(fields.get("funding_ledger_revision")),
            UUID.fromString(fields.get("quote")),
            captured,
            Long.parseLong(fields.get("expires_at")),
            Boolean.parseBoolean(fields.get("full_payoff")));
    var quote =
        new Loans.PaymentQuote(
            request,
            request.fullPayoff() ? LoanServicing.payoff(source.servicing(), captured) : null);
    return Loans.repayment(owner, quote, () -> captured + 1);
  }

  private static void pause(String phase, String edge) throws Exception {
    System.out.println("BOUNDARY|" + phase + "|" + edge + "|" + stable("operation|" + phase));
    System.out.flush();
    if (System.in.read() < 0)
      throw new IllegalStateException("Parent closed disposable fixture input");
  }

  public static void main(String[] arguments) throws Exception {
    Path world = Path.of(arguments[0]);
    String phase = arguments[1], edge = arguments[2];
    var store = new SqliteStorage();
    store.initialize(world);
    var original = registration(store, world, phase);
    var registration =
        edge.equals("before")
            ? new JournalEngine.Registration(
                1,
                (intent, reads) -> {
                  var decision = original.handler().prepare(intent, reads);
                  if (!(decision instanceof JournalEngine.Commit commit))
                    throw new IllegalStateException("Fixture target rejected " + decision);
                  return new JournalEngine.Commit(
                      commit.records(),
                      commit.result(),
                      transaction -> {
                        commit.projections().apply(transaction);
                        pause(phase, edge);
                      });
                })
            : original;
    var outcome = submit(store, target(world, phase), registration);
    if (outcome.status() != Journal.Status.COMMITTED)
      throw new IllegalStateException("Fixture target rejected " + outcome);
    if (edge.equals("after")) pause(phase, edge);
    throw new IllegalStateException("Parent failed to terminate disposable fixture");
  }
}
