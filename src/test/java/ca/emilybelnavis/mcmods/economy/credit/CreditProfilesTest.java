package ca.emilybelnavis.mcmods.economy.credit;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreditProfilesTest {
  @TempDir Path world;

  @Test
  void noProfileWithoutActualCreditAndFirstCommitSurvivesReplayAndRestart() throws Exception {
    UUID actor = UUID.randomUUID();
    CreditProfiles.Profile original;
    try (var f = new CreditFixtures(world, actor, true)) {
      assertTrue(
          f.store.<Boolean>transaction(tx -> CreditProfiles.find(tx::query, f.owner).isEmpty()));
      var request = f.initial("crownstone");
      var intent = f.intent(CreditProfiles.FIRST_ISSUANCE, request.request());
      assertEquals("CREDIT_ISSUANCE_UNAVAILABLE", f.issue(request, intent, null).result().code());
      assertEquals(
          "UNDERWRITING_DENIED",
          f.issue(
                  request,
                  f.intent(CreditProfiles.FIRST_ISSUANCE, request.request()),
                  (i, r, a) ->
                      new JournalEngine.Reject(new Journal.Result(1, "UNDERWRITING_DENIED", "")))
              .result()
              .code());
      assertEquals(
          "CREDIT_ISSUANCE_NOT_ACTUAL",
          f.issue(
                  request,
                  f.intent(CreditProfiles.FIRST_ISSUANCE, request.request()),
                  (i, r, a) ->
                      new JournalEngine.Commit(
                          List.of(
                              new Journal.Record(
                                  CreditFixtures.APP, 1, UUID.randomUUID().toString())),
                          new Journal.Result(1, "APPLICATION_DENIED", ""),
                          tx -> {}))
              .result()
              .code());
      assertEquals(0, f.count("credit_profiles"));
      assertEquals(0, f.count("credit_score_history"));
      var outcome = f.issue(request, intent, f.actual());
      assertEquals(Journal.Status.COMMITTED, outcome.status());
      assertEquals(outcome, f.issue(request, intent, f.actual()));
      original = f.profile();
      assertEquals(650, original.score());
      assertEquals(250000, original.starterMinor());
      assertEquals(EconomyCurrency.CAD, original.starterCurrency());
      assertEquals(1, f.count("credit_profiles"));
      assertEquals(1, f.issuances());
      assertEquals(1, f.count("credit_score_history"));
      assertEquals(0, f.count("credit_inquiry_groups"));
      var second = f.initial("orchard_direct");
      assertEquals(
          "CREDIT_ALREADY_ESTABLISHED",
          f.issue(second, f.intent(CreditProfiles.FIRST_ISSUANCE, second.request()), f.actual())
              .result()
              .code());
      assertEquals(1, f.issuances());
      var stranger =
          new PartyRef(f.owner.world(), new PartyRef.Player(new PlayerId(UUID.randomUUID())));
      assertTrue(
          f.store.<Boolean>transaction(tx -> CreditProfiles.find(tx::query, stranger).isEmpty()));
      assertTrue(
          f.store.<Boolean>transaction(
              tx -> CreditProfiles.history(tx::query, stranger, 0, 8).entries().isEmpty()));
    }
    try (var f = new CreditFixtures(world, actor, false)) {
      assertEquals(original, f.profile());
      assertEquals(
          650,
          f.store
              .transaction(tx -> CreditProfiles.latestHistory(tx::query, f.owner).orElseThrow())
              .score());
    }
  }

  @Test
  void concurrentIssuersCannotDuplicateTheStarterAllowance() throws Exception {
    try (var worker = Executors.newSingleThreadExecutor();
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var f = worker.submit(() -> new CreditFixtures(world, UUID.randomUUID(), true)).get();
      try {
        var requests =
            worker.submit(() -> List.of(f.initial("crownstone"), f.initial("lantern_union"))).get();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<String>>();
        for (var request : requests)
          results.add(
              callers.submit(
                  () -> {
                    ready.countDown();
                    start.await();
                    return worker
                        .submit(
                            () ->
                                f.issue(
                                        request,
                                        f.intent(CreditProfiles.FIRST_ISSUANCE, request.request()),
                                        f.actual())
                                    .result()
                                    .code())
                        .get();
                  }));
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        var codes = new HashSet<String>();
        for (var future : results) codes.add(future.get());
        assertEquals(Set.of("CREDIT_CARD_ISSUED", "CREDIT_ALREADY_ESTABLISHED"), codes);
        assertEquals(1, worker.submit(() -> f.issuances()).get().intValue());
        assertEquals(250000, worker.submit(() -> f.profile().starterMinor()).get().longValue());
      } finally {
        worker
            .submit(
                () -> {
                  f.close();
                  return null;
                })
            .get();
      }
    }
  }

  @Test
  void initialInputsAndAuthoritativeProcessingInstantCannotBeForged() throws Exception {
    try (var f = new CreditFixtures(world, UUID.randomUUID(), true)) {
      var request = f.initial("crownstone");
      var wrong =
          new CreditProfiles.InitialRequest(
              request.world(),
              request.owner(),
              request.session(),
              request.issuer(),
              request.facility(),
              EconomyCurrency.CHF,
              0,
              0);
      assertEquals(
          "DENOMINATION_STALE",
          f.issue(wrong, f.intent(CreditProfiles.FIRST_ISSUANCE, wrong.request()), f.actual())
              .result()
              .code());
      var foreign =
          new CreditProfiles.InitialRequest(
              request.world(),
              new PlayerId(UUID.randomUUID()),
              request.session(),
              request.issuer(),
              request.facility(),
              EconomyCurrency.CAD,
              0,
              0);
      assertEquals(
          "CREDIT_INITIAL_REQUEST",
          f.issue(foreign, f.intent(CreditProfiles.FIRST_ISSUANCE, foreign.request()), f.actual())
              .result()
              .code());
      f.time.set(1234);
      assertEquals(
          "CREDIT_CARD_ISSUED",
          f.issue(request, f.intent(CreditProfiles.FIRST_ISSUANCE, request.request()), f.actual())
              .result()
              .code());
      assertEquals(1234, f.profile().firstIssuedAt());
    }
  }

  @Test
  void sameInstantManualInquiryCannotEnterAnEarlierReportingSnapshot() throws Exception {
    try (var f = new CreditFixtures(world, UUID.randomUUID(), true)) {
      f.time.set(ScorePolicy.CYCLE_MILLIS - 1);
      var before = f.inquiry("crownstone", f.time.get());
      f.inquire(before, f.intent(CreditProfiles.INQUIRY, before.request()), true);
      long frontier =
          f.store.transaction(
              tx ->
                  Long.parseLong(
                      tx.query("SELECT MAX(sequence) AS n FROM journal_transactions", List.of(), 1)
                          .getFirst()
                          .get("n")));
      f.time.set(ScorePolicy.CYCLE_MILLIS);
      var after = f.inquiry("lantern_union", f.time.get());
      f.inquire(after, f.intent(CreditProfiles.INQUIRY, after.request()), true);
      assertEquals(
          List.of(3L),
          f.store.transaction(
              tx ->
                  CreditProfiles.inquiryBuckets(
                      tx::query, f.owner, 0, ScorePolicy.CYCLE_MILLIS, frontier)));
      assertEquals(
          List.of(3L, 4L),
          f.store.transaction(
              tx ->
                  CreditProfiles.inquiryBuckets(tx::query, f.owner, 0, ScorePolicy.CYCLE_MILLIS)));
    }
  }

  @Test
  void inquiryCompositionRejectsAnOversizedAcceptedCallbackWithoutWriting() throws Exception {
    try (var f = new CreditFixtures(world, UUID.randomUUID(), true)) {
      var request = f.inquiry("crownstone", 0);
      var records = new ArrayList<Journal.Record>();
      for (int i = 0; i < 64; i++)
        records.add(new Journal.Record(CreditFixtures.APP, 1, UUID.randomUUID().toString()));
      var registration =
          CreditProfiles.acceptedInquiry(
              f.owner,
              request,
              f.time::get,
              (intent, reads) ->
                  new JournalEngine.Commit(
                      records, new Journal.Result(1, "BUSINESS_DENIED", ""), tx -> {}));
      var result =
          new JournalEngine(f.store, (a, k) -> true, Map.of(CreditProfiles.INQUIRY, registration))
              .submit(f.intent(CreditProfiles.INQUIRY, request.request()));
      assertEquals("CREDIT_INQUIRY_BUDGET", result.result().code());
      assertEquals(0, f.count("credit_inquiry_applications"));
      assertEquals(0, f.count("credit_profiles"));
    }
  }

  @Test
  void acceptedDenialsGroupAcrossIssuersWithoutEstablishingCreditAndHistoricalWindowsRemainExact()
      throws Exception {
    try (var f = new CreditFixtures(world, UUID.randomUUID(), true)) {
      var zero = f.inquiry("crownstone", 0);
      var original = f.intent(CreditProfiles.INQUIRY, zero.request());
      assertEquals(Journal.Status.COMMITTED, f.inquire(zero, original, true).status());
      assertEquals(Journal.Status.COMMITTED, f.inquire(zero, original, true).status());
      assertEquals(
          List.of(0L),
          f.store.transaction(
              tx ->
                  CreditProfiles.inquiryBuckets(tx::query, f.owner, 0, ScorePolicy.CYCLE_MILLIS)));
      var another = f.inquiry("lantern_union", 1);
      f.time.set(1);
      assertEquals(
          Journal.Status.COMMITTED,
          f.inquire(another, f.intent(CreditProfiles.INQUIRY, another.request()), true).status());
      assertEquals(1, f.count("credit_inquiry_groups"));
      assertEquals(2, f.count("credit_inquiry_applications"));
      assertEquals(0, f.count("credit_profiles"));
      assertEquals(
          "CREDIT_APPLICATION_REUSED",
          f.inquire(another, f.intent(CreditProfiles.INQUIRY, another.request()), true)
              .result()
              .code());
      long boundary = 84L * ScorePolicy.DAY_MILLIS;
      assertEquals(
          List.of(0L),
          f.store.transaction(
              tx -> CreditProfiles.inquiryBuckets(tx::query, f.owner, 0, boundary)));
      var future = f.inquiry("asterbridge", boundary + 1);
      f.time.set(boundary + 1);
      f.inquire(future, f.intent(CreditProfiles.INQUIRY, future.request()), true);
      assertEquals(
          List.of(0L),
          f.store.transaction(
              tx -> CreditProfiles.inquiryBuckets(tx::query, f.owner, 0, boundary)));
      var malformed = f.inquiry("crosswind", boundary + 2);
      f.time.set(boundary + 2);
      assertEquals(
          "MALFORMED_APPLICATION",
          f.inquire(malformed, f.intent(CreditProfiles.INQUIRY, malformed.request()), false)
              .result()
              .code());
      assertEquals(3, f.count("credit_inquiry_applications"));
      assertEquals(0, f.count("credit_profiles"));
      var initial = f.initial("crownstone");
      f.issue(initial, f.intent(CreditProfiles.FIRST_ISSUANCE, initial.request()), f.actual());
      assertEquals(650, f.profile().score());
    }
  }
}

/** Detached test-only actual credit backend: it is never installed in production. */
final class CreditFixtures implements AutoCloseable {
  static final String FACILITY = "economy:test-actual-credit",
      APP = "economy:test-accepted-application";
  final SqliteStorage store;
  final PlayerId actor;
  final PartyRef owner;
  final AtomicLong time = new AtomicLong();

  CreditFixtures(Path world, UUID actor, boolean seed) throws Exception {
    this.actor = new PlayerId(actor);
    store =
        new SqliteStorage(
            RecordCatalog.core()
                .with(new RecordCatalog.Key(FACILITY, 1), payload -> UUID.fromString(payload))
                .with(new RecordCatalog.Key(APP, 1), payload -> UUID.fromString(payload)));
    store.initialize(world.toRealPath());
    owner = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(this.actor));
    if (seed) {
      store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO financial_calendar(singleton,version,origin_ms,observed_wall_ms,last_effective_ms,completed_effects) VALUES(1,1,0,0,0,0)",
                List.of());
            return null;
          });
      var engine =
          new JournalEngine(
              store,
              (a, k) -> true,
              Map.of(
                  FxService.OPEN,
                  FxService.opening(
                      Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC))));
      assertEquals(
          Journal.Status.COMMITTED,
          engine
              .submit(
                  intent(
                      FxService.OPEN, new Journal.CanonicalRequest(1, Map.of("currency", "CAD"))))
              .status());
    }
  }

  CreditProfiles.InitialRequest initial(String issuer) {
    return new CreditProfiles.InitialRequest(
        owner.world(),
        actor,
        UUID.randomUUID(),
        new InstitutionCatalog.InstitutionId("economy:bank/" + issuer),
        UUID.randomUUID(),
        EconomyCurrency.CAD,
        0,
        0);
  }

  CreditProfiles.ActualIssuance actual() {
    return (intent, reads, starter) -> {
      assertEquals(650, starter.score());
      assertEquals(
          Math.multiplyExact(2500L, starter.limit().currency().minorUnitsPerMajor()),
          starter.limit().minorUnits());
      return new JournalEngine.Commit(
          List.of(new Journal.Record(FACILITY, 1, starter.facility().toString())),
          new Journal.Result(1, "CREDIT_CARD_ISSUED", starter.facility().toString()),
          tx -> {});
    };
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

  Journal.Outcome issue(
      CreditProfiles.InitialRequest request,
      Journal.Intent intent,
      CreditProfiles.ActualIssuance actual) {
    return new JournalEngine(
            store,
            (a, k) -> true,
            Map.of(
                CreditProfiles.FIRST_ISSUANCE,
                CreditProfiles.firstIssuance(owner, request, time::get, actual)))
        .submit(intent);
  }

  CreditProfiles.InquiryRequest inquiry(String issuer, long submitted) {
    return new CreditProfiles.InquiryRequest(
        owner.world(),
        actor,
        new InstitutionCatalog.InstitutionId("economy:bank/" + issuer),
        UUID.randomUUID(),
        CreditProfiles.ApplicationKind.LOAN,
        submitted,
        0,
        false);
  }

  Journal.Outcome inquire(
      CreditProfiles.InquiryRequest request, Journal.Intent intent, boolean accepted) {
    return new JournalEngine(
            store,
            (a, k) -> true,
            Map.of(
                CreditProfiles.INQUIRY,
                CreditProfiles.acceptedInquiry(
                    owner,
                    request,
                    time::get,
                    (i, r) ->
                        accepted
                            ? new JournalEngine.Commit(
                                List.of(
                                    new Journal.Record(APP, 1, request.application().toString())),
                                new Journal.Result(1, "BUSINESS_DENIED", ""),
                                tx -> {})
                            : new JournalEngine.Reject(
                                new Journal.Result(1, "MALFORMED_APPLICATION", "")))))
        .submit(intent);
  }

  CreditProfiles.Profile profile() {
    return store.transaction(tx -> CreditProfiles.find(tx::query, owner).orElseThrow());
  }

  int issuances() {
    return store.transaction(
        tx ->
            Integer.parseInt(
                tx.query(
                        "SELECT count(*) AS n FROM journal_records WHERE record_type=?",
                        List.of(FACILITY),
                        1)
                    .getFirst()
                    .get("n")));
  }

  int count(String table) {
    return store.transaction(
        tx ->
            Integer.parseInt(
                tx.query("SELECT count(*) AS n FROM " + table, List.of(), 1).getFirst().get("n")));
  }

  @Override
  public void close() throws Exception {
    store.close();
  }
}
