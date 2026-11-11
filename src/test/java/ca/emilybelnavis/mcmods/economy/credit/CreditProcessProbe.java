package ca.emilybelnavis.mcmods.economy.credit;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.WealthValuation;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Disposable child JVM. Only its parent test terminates it at a declared financial boundary. */
public final class CreditProcessProbe {
  static final long ANCHOR = Instant.parse("2026-11-05T12:00:00Z").toEpochMilli();
  static final PlayerId OWNER =
      new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000803"));
  static final AccountId ACCOUNT =
      new AccountId(UUID.fromString("00000000-0000-0000-0000-000000008031"));
  static final UUID FACILITY = UUID.fromString("00000000-0000-0000-0000-000000008032");
  static final InstitutionCatalog.InstitutionId BANK =
      new InstitutionCatalog.InstitutionId("economy:bank/crownstone");

  private CreditProcessProbe() {}

  static UUID stable(String key) {
    return UUID.nameUUIDFromBytes(
        ("economy:credit-recovery:v1|" + key).getBytes(StandardCharsets.UTF_8));
  }

  static PartyRef owner(FoundationStore store) {
    return new PartyRef(store.snapshot().worldId(), new PartyRef.Player(OWNER));
  }

  static CreditFacilities.Inputs inputs(JournalEngine.ReadView reads, PartyRef owner, long now) {
    var p = FxService.latest(reads);
    return new CreditFacilities.Inputs(
        new CreditUnderwriting.FxSnapshot(
            p.updateId(),
            p.day().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            now,
            p.rates(),
            true),
        true,
        false,
        0);
  }

  static Journal.Kind kind(String phase) {
    return switch (phase) {
      case "draw" -> CreditFacilities.DRAW;
      case "payment" -> CreditFacilities.REPAY;
      case "interest" -> new Journal.Kind("fixture:credit-recovery", "fixture:interest");
      case "statement" -> new Journal.Kind("fixture:credit-recovery", "fixture:statement");
      default -> throw new IllegalArgumentException("Fixture phase");
    };
  }

  static Journal.Intent intent(String key, Journal.Kind kind, Journal.CanonicalRequest request) {
    return new Journal.Intent(
        new OperationId(stable("operation|" + key)),
        new RequestId(stable("request|" + key)),
        OWNER,
        kind,
        request,
        null,
        null);
  }

  static Journal.Outcome submit(
      FoundationStore store, Journal.Intent intent, JournalEngine.Registration registration) {
    return new JournalEngine(store, (a, k) -> a.equals(OWNER), Map.of(intent.kind(), registration))
        .submit(intent);
  }

  static CreditFacilities.Facility facility(FoundationStore store) {
    return store.transaction(tx -> CreditFacilities.find(tx::query, FACILITY).orElseThrow());
  }

  private static CreditFacilities.DrawRequest drawRequest(FoundationStore store, String key) {
    var f = facility(store);
    var token =
        store.transaction(
            tx ->
                CreditFacilities.tokenForFacility(tx::query, store.snapshot().worldId(), FACILITY)
                    .orElseThrow());
    return new CreditFacilities.DrawRequest(
        f.world(),
        OWNER,
        FACILITY,
        token.id(),
        token.network(),
        CreditFacilities.DrawKind.PURCHASE,
        ACCOUNT,
        12300,
        f.revision(),
        token.revision(),
        1,
        1,
        stable("quote|" + key),
        ANCHOR + 30_000);
  }

  private static JournalEngine.Registration draw(
      FoundationStore store, CreditFacilities.DrawRequest request) {
    return CreditFacilities.draw(
        owner(store),
        request,
        CreditProcessProbe::inputs,
        (i, r, t, k, a) -> true,
        () -> ANCHOR + 1);
  }

  static void seed(Path world, String phase) throws Exception {
    var store = new SqliteStorage();
    store.initialize(world);
    try {
      committed(
          submit(
              store,
              intent(
                  "fx", FxService.OPEN, new Journal.CanonicalRequest(1, Map.of("currency", "CAD"))),
              FxService.opening(Clock.fixed(Instant.ofEpochMilli(ANCHOR), ZoneOffset.UTC))));
      var capital =
          new InstitutionCapital(
              store, new SystemActorId(stable("capital-actor")), CapitalPosting.poster());
      for (int n = 0; n < 26; n++) committed(capital.advance(Instant.ofEpochMilli(ANCHOR)));
      store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO financial_calendar(singleton,version,origin_ms,observed_wall_ms,last_effective_ms,completed_effects) VALUES(1,1,?,?,?,0)",
                List.of(Long.toString(ANCHOR), Long.toString(ANCHOR), Long.toString(ANCHOR)));
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                List.of(ACCOUNT.value().toString(), OWNER.value().toString()));
            return null;
          });
      var proof =
          BranchFixtures.proof(
              new BranchAdmission.Context(
                  store.snapshot().worldId(),
                  stable("session"),
                  OWNER,
                  BranchAdmission.Action.CREDIT_ISSUE,
                  BANK,
                  null,
                  null,
                  0,
                  0,
                  0));
      var request =
          new CreditFacilities.IssueRequest(
              proof,
              FACILITY,
              InstitutionCatalog.Network.ORBIT,
              EconomyCurrency.CAD,
              250000,
              0,
              0,
              ANCHOR);
      committed(
          submit(
              store,
              intent("issue", CreditFacilities.ISSUE, request.request()),
              CreditFacilities.issuance(
                  owner(store),
                  request,
                  BankProviders.NO_TIER2,
                  CreditProcessProbe::inputs,
                  () -> ANCHOR)));
      if (!phase.equals("draw")) {
        var prepared = drawRequest(store, "prepared");
        committed(
            submit(
                store,
                intent("prepared-draw", CreditFacilities.DRAW, prepared.request()),
                draw(store, prepared)));
      }
      if (phase.equals("statement"))
        for (int day = 1; day <= 28; day++) {
          long at = ANCHOR + day * CreditFacilities.DAY;
          var kind = kind("interest");
          committed(
              submit(
                  store,
                  intent(
                      "prepared-day|" + day,
                      kind,
                      new Journal.CanonicalRequest(1, Map.of("effective", Long.toString(at)))),
                  new JournalEngine.Registration(
                      1, (i, r) -> CreditFacilities.daily(r, FACILITY, at))));
        }
      Journal.CanonicalRequest target;
      if (phase.equals("draw")) target = drawRequest(store, "target").request();
      else if (phase.equals("payment")) {
        long ledger =
            store.transaction(tx -> WealthValuation.ledgerRevision(tx::query, ACCOUNT.value()));
        target =
            new CreditFacilities.RepaymentRequest(
                    store.snapshot().worldId(),
                    OWNER,
                    FACILITY,
                    ACCOUNT,
                    10000,
                    facility(store).revision(),
                    1,
                    ledger,
                    stable("quote|payment"),
                    ANCHOR + 30_000)
                .request();
      } else
        target =
            new Journal.CanonicalRequest(
                1,
                Map.of(
                    "effective",
                    Long.toString(
                        ANCHOR
                            + (phase.equals("interest")
                                ? CreditFacilities.DAY
                                : CreditFacilities.MONTH))));
      var properties = new Properties();
      properties.putAll(target.fields());
      try (var output =
          Files.newBufferedWriter(world.resolve("fixture-credit-request.properties"))) {
        properties.store(output, "Disposable credit recovery fixture only");
      }
    } finally {
      store.close();
    }
  }

  static Journal.CanonicalRequest request(Path world) throws Exception {
    var properties = new Properties();
    try (var input = Files.newBufferedReader(world.resolve("fixture-credit-request.properties"))) {
      properties.load(input);
    }
    var fields = new TreeMap<String, String>();
    for (var key : properties.stringPropertyNames()) fields.put(key, properties.getProperty(key));
    return new Journal.CanonicalRequest(1, fields);
  }

  static Journal.Intent target(Path world, String phase) throws Exception {
    return intent("target|" + phase, kind(phase), request(world));
  }

  static JournalEngine.Registration registration(FoundationStore store, Path world, String phase)
      throws Exception {
    var p = request(world).fields();
    var w = store.snapshot().worldId();
    return switch (phase) {
      case "draw" -> {
        var draw =
            new CreditFacilities.DrawRequest(
                w,
                OWNER,
                FACILITY,
                UUID.fromString(p.get("token")),
                InstitutionCatalog.Network.valueOf(p.get("network")),
                CreditFacilities.DrawKind.valueOf(p.get("kind")),
                new AccountId(UUID.fromString(p.get("destination"))),
                number(p, "amount"),
                number(p, "facility_revision"),
                number(p, "token_revision"),
                number(p, "destination_revision"),
                number(p, "profile_revision"),
                UUID.fromString(p.get("quote")),
                number(p, "expires"));
        yield draw(store, draw);
      }
      case "payment" -> {
        var repayment =
            new CreditFacilities.RepaymentRequest(
                w,
                OWNER,
                FACILITY,
                ACCOUNT,
                number(p, "amount"),
                number(p, "facility_revision"),
                number(p, "funding_revision"),
                number(p, "funding_ledger_revision"),
                UUID.fromString(p.get("quote")),
                number(p, "expires"));
        yield CreditFacilities.repayment(owner(store), repayment, () -> ANCHOR + 1);
      }
      case "interest" ->
          new JournalEngine.Registration(
              1, (i, r) -> CreditFacilities.daily(r, FACILITY, number(p, "effective")));
      case "statement" ->
          new JournalEngine.Registration(
              1, (i, r) -> CreditFacilities.statement(r, FACILITY, number(p, "effective")));
      default -> throw new IllegalArgumentException("Fixture phase");
    };
  }

  private static long number(Map<String, String> fields, String key) {
    return Long.parseLong(fields.get(key));
  }

  private static void committed(Journal.Outcome outcome) {
    if (outcome.status() != Journal.Status.COMMITTED)
      throw new IllegalStateException("Fixture rejected: " + outcome);
  }

  private static void pause(String phase, String edge) throws Exception {
    System.out.println(
        "BOUNDARY|" + phase + "|" + edge + "|" + stable("operation|target|" + phase));
    System.out.flush();
    if (System.in.read() < 0)
      throw new IllegalStateException("Parent closed disposable fixture input");
  }

  public static void main(String[] args) throws Exception {
    Path world = Path.of(args[0]);
    String phase = args[1], edge = args[2];
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
                    throw new IllegalStateException("Fixture target did not commit: " + decision);
                  return new JournalEngine.Commit(
                      commit.records(),
                      commit.result(),
                      tx -> {
                        commit.projections().apply(tx);
                        pause(phase, edge);
                      });
                })
            : original;
    committed(submit(store, target(world, phase), registration));
    if (edge.equals("after")) pause(phase, edge);
    throw new IllegalStateException("Fixture was not terminated by parent");
  }
}
