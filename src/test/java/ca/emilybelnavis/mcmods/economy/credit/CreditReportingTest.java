package ca.emilybelnavis.mcmods.economy.credit;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.debt.*;
import ca.emilybelnavis.mcmods.economy.debt.DebtRecords;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreditReportingTest {
  @TempDir Path world;
  private static final long DAY = ScorePolicy.DAY_MILLIS, CYCLE = ScorePolicy.CYCLE_MILLIS;

  private void establish(CreditFixtures f) {
    var opening = f.initial("crownstone");
    assertEquals(
        Journal.Status.COMMITTED,
        f.issue(opening, f.intent(CreditProfiles.FIRST_ISSUANCE, opening.request()), f.actual())
            .status());
  }

  private void source(
      CreditFixtures f, long revision, long effective, long lastDay, long owed, boolean active) {
    var s =
        new CreditReporting.Source(
            f.owner.world(),
            f.actor,
            f.profile().firstFacility(),
            revision,
            effective,
            1,
            EconomyCurrency.CAD,
            250000,
            active,
            lastDay,
            owed,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            0,
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    f.store.transaction(
        tx -> {
          tx.execute(
              "INSERT INTO credit_reporting_sources VALUES(?,?,?,?,?,?,?)",
              List.of(
                  s.world().value().toString(),
                  s.owner().value().toString(),
                  s.facility().toString(),
                  Long.toString(revision),
                  Long.toString(effective),
                  "1",
                  CreditReporting.encode(s)));
          return null;
        });
  }

  private Journal.Outcome turn(CreditFixtures f, long boundary) {
    var kind = new Journal.Kind("fixture:score", "fixture:fold");
    var intent =
        f.intent(
            kind, new Journal.CanonicalRequest(1, Map.of("boundary", Long.toString(boundary))));
    return new JournalEngine(
            f.store,
            (a, k) -> true,
            Map.of(
                kind,
                new JournalEngine.Registration(
                    1, (i, r) -> CreditScoreRuntime.step(r, f.actor.value(), boundary))))
        .submit(intent);
  }

  private void finish(CreditFixtures f, long boundary) {
    for (int step = 0; step < 500 && f.profile().lastBoundary() < boundary; step++)
      assertEquals(Journal.Status.COMMITTED, turn(f, boundary).status());
    assertEquals(boundary, f.profile().lastBoundary());
  }

  private void daily(CreditFixtures f, long owed) {
    for (int day = 0; day <= 28; day++) source(f, day + 1, day * DAY, day * DAY, owed, true);
  }

  @Test
  void savedFoldMustMatchItsOwnerAndNormalizedCheckpoint() throws Exception {
    try (var f = new CreditFixtures(world, UUID.randomUUID(), true)) {
      establish(f);
      daily(f, 20000);
      turn(f, CYCLE);
      var original =
          f.store.transaction(
              tx ->
                  tx.query("SELECT body FROM credit_score_tasks", List.of(), 1)
                      .getFirst()
                      .get("body"));
      var fields =
          new ArrayList<>(CreditReporting.unpack(original, original.split("\\|", -1).length));
      int owner = fields.indexOf("owner");
      fields.set(owner + 1, UUID.randomUUID().toString());
      f.store.transaction(
          tx -> {
            tx.execute(
                "UPDATE credit_score_tasks SET body=?", List.of(CreditReporting.pack(fields)));
            return null;
          });
      assertThrows(IllegalArgumentException.class, () -> turn(f, CYCLE));
      f.store.transaction(
          tx -> {
            tx.execute("UPDATE credit_score_tasks SET body=?,step=step+1", List.of(original));
            return null;
          });
      assertThrows(IllegalArgumentException.class, () -> turn(f, CYCLE));
      assertEquals(1, f.count("credit_score_history"));
    }
  }

  @Test
  void simultaneousPortfolioReplacementKeepsZeroDurationTransientSourcesBounded() throws Exception {
    try (var f = new CreditFixtures(world, UUID.randomUUID(), true)) {
      establish(f);
      var old =
          List.of(
              f.profile().firstFacility(),
              UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff1"),
              UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff2"));
      var replacements =
          List.of(
              UUID.fromString("00000000-0000-0000-0000-000000000001"),
              UUID.fromString("00000000-0000-0000-0000-000000000002"),
              UUID.fromString("00000000-0000-0000-0000-000000000003"));
      var transientId = UUID.fromString("00000000-0000-0000-0000-000000000004");
      var inputs = new ArrayList<CreditReporting.Source>();
      for (var id : old) {
        inputs.add(
            new CreditReporting.Source(
                f.owner.world(),
                f.actor,
                id,
                1,
                0,
                1,
                EconomyCurrency.CAD,
                250000,
                true,
                0,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
        inputs.add(
            new CreditReporting.Source(
                f.owner.world(),
                f.actor,
                id,
                2,
                DAY / 2,
                1,
                EconomyCurrency.CAD,
                250000,
                false,
                0,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
      }
      for (var id : replacements) {
        inputs.add(
            new CreditReporting.Source(
                f.owner.world(),
                f.actor,
                id,
                1,
                DAY / 2,
                1,
                EconomyCurrency.CAD,
                250000,
                true,
                0,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
        for (int day = 1; day <= 28; day++)
          inputs.add(
              new CreditReporting.Source(
                  f.owner.world(),
                  f.actor,
                  id,
                  day + 1,
                  day * DAY,
                  1,
                  EconomyCurrency.CAD,
                  250000,
                  true,
                  day * DAY,
                  0,
                  BigDecimal.ZERO,
                  BigDecimal.ZERO,
                  0,
                  BigDecimal.ZERO,
                  BigDecimal.ZERO));
      }
      for (int revision = 1; revision <= 2; revision++)
        inputs.add(
            new CreditReporting.Source(
                f.owner.world(),
                f.actor,
                transientId,
                revision,
                DAY / 2,
                1,
                EconomyCurrency.CAD,
                250000,
                revision == 1,
                0,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
      for (var source : inputs)
        f.store.transaction(
            tx -> {
              tx.execute(
                  "INSERT INTO credit_reporting_sources VALUES(?,?,?,?,?,?,?)",
                  List.of(
                      source.world().value().toString(),
                      source.owner().value().toString(),
                      source.facility().toString(),
                      Long.toString(source.revision()),
                      Long.toString(source.effectiveAt()),
                      "1",
                      CreditReporting.encode(source)));
              return null;
            });
      finish(f, CYCLE);
      assertEquals(682, f.profile().score());
    }
  }

  @Test
  void dailyAreasAndDurableFoldResumeAcrossRestartWithoutDoublePenalty() throws Exception {
    UUID player = UUID.randomUUID();
    try (var f = new CreditFixtures(world, player, true)) {
      establish(f);
      daily(f, 20000);
      for (int step = 0; step < 15; step++)
        assertEquals(Journal.Status.COMMITTED, turn(f, CYCLE).status());
      assertEquals(650, f.profile().score());
      assertEquals(-1, f.profile().lastBoundary());
    }
    try (var f = new CreditFixtures(world, player, false)) {
      finish(f, CYCLE);
      assertEquals(712, f.profile().score());
      assertEquals(28, f.count("credit_utilization_days"));
      assertEquals(2, f.count("credit_score_history"));
      var rows =
          f.store.transaction(
              tx ->
                  tx.query(
                      "SELECT body FROM credit_utilization_days ORDER BY day_start",
                      List.of(),
                      32));
      assertEquals(
          CYCLE,
          rows.stream()
              .map(r -> CreditScoreRuntime.decodeDay(r.get("body")))
              .mapToLong(ScorePolicy.DailyArea::activeMillis)
              .sum());
      long frontier = f.store.lastCommittedSequence();
      assertThrows(IllegalArgumentException.class, () -> turn(f, CYCLE));
      assertEquals(frontier, f.store.lastCommittedSequence());
    }
  }

  @Test
  void lastSecondDrawUsesElapsedUtilizationAndDoesNotReplaceTheWholeCycle() throws Exception {
    try (var f = new CreditFixtures(world, UUID.randomUUID(), true)) {
      establish(f);
      for (int day = 0; day < 28; day++) source(f, day + 1, day * DAY, day * DAY, 0, true);
      source(f, 29, CYCLE - 1, 27 * DAY, 250000, true);
      source(f, 30, CYCLE, 28 * DAY, 250000, true);
      finish(f, CYCLE);
      assertEquals(712, f.profile().score());
      var history = f.store.transaction(tx -> CreditProfiles.history(tx::query, f.owner, 0, 8));
      assertEquals(60, history.entries().getLast().components().utilization());
    }
  }

  @Test
  void reportFoldPagesAllOriginalsAndCuresWithoutDuplicatingMisses() throws Exception {
    try (var f = new CreditFixtures(world, UUID.randomUUID(), true)) {
      establish(f);
      daily(f, 20000);
      for (int number = 0; number < 33; number++) {
        var id = UUID.randomUUID();
        var obligation = UUID.randomUUID();
        var missed =
            new DebtRecords.Report(
                f.owner.world(),
                id,
                DebtRecords.Kind.CARD,
                f.profile().firstFacility(),
                f.actor,
                obligation,
                0,
                2 * DAY,
                false,
                false,
                100,
                100,
                1,
                2 * DAY);
        var cured =
            new DebtRecords.Report(
                f.owner.world(),
                id,
                DebtRecords.Kind.CARD,
                f.profile().firstFacility(),
                f.actor,
                obligation,
                0,
                2 * DAY,
                false,
                true,
                100,
                0,
                2,
                3 * DAY);
        f.store.transaction(
            tx -> {
              DebtRecords.write(tx, missed);
              DebtRecords.write(tx, cured);
              return null;
            });
      }
      finish(f, CYCLE);
      assertEquals(622, f.profile().score());
      var score =
          f.store
              .transaction(tx -> CreditProfiles.history(tx::query, f.owner, 0, 8))
              .entries()
              .getLast();
      assertEquals(-90, score.components().payment());
    }
  }

  @Test
  void missingFirstIssuanceSourceFailsClosedBeforeAnyScoreHistory() throws Exception {
    try (var f = new CreditFixtures(world, UUID.randomUUID(), true)) {
      establish(f);
      long sequence = f.store.lastCommittedSequence();
      var failure = assertThrows(IllegalStateException.class, () -> turn(f, CYCLE));
      assertTrue(
          failure.getMessage().contains("CREDIT_UTILIZATION_FIRST_ISSUANCE_SOURCE_UNAVAILABLE"));
      assertEquals(1, f.count("credit_score_history"));
      assertEquals(sequence, f.store.lastCommittedSequence());
    }
  }

  @Test
  void acceptedNonStarterApplicationAtExactWorldOriginCountsInFirstCycle() throws Exception {
    try (var f = new CreditFixtures(world, UUID.randomUUID(), true)) {
      establish(f);
      daily(f, 20000);
      var application = f.inquiry("crownstone", 0);
      assertEquals(
          Journal.Status.COMMITTED,
          f.inquire(application, f.intent(CreditProfiles.INQUIRY, application.request()), true)
              .status());
      finish(f, CYCLE);
      assertEquals(707, f.profile().score());
    }
  }

  @Test
  void accruedInterestIsIntegratedOverElapsedTime() throws Exception {
    try (var f = new CreditFixtures(world, UUID.randomUUID(), true)) {
      var source =
          new CreditReporting.Source(
              f.owner.world(),
              f.actor,
              UUID.randomUUID(),
              1,
              0,
              1,
              EconomyCurrency.CAD,
              100000,
              true,
              0,
              10000,
              BigDecimal.ZERO,
              new BigDecimal("0.364"),
              0,
              BigDecimal.ZERO,
              BigDecimal.ZERO);
      var area =
          f.store.transaction(
              tx -> CreditScoreRuntime.integral(tx::query, List.of(source), 0, DAY));
      assertEquals(
          new CreditScoreRuntime.Fraction(
              java.math.BigInteger.valueOf(120060), java.math.BigInteger.ONE),
          area.ratio());
    }
  }

  @Test
  void historicalCommonUnitFxAndSignedResidualEnterTheExactArea() throws Exception {
    try (var f = new CreditFixtures(world, UUID.randomUUID(), true)) {
      long start = START.toEpochMilli();
      var cad =
          new CreditReporting.Source(
              f.owner.world(),
              f.actor,
              UUID.randomUUID(),
              1,
              start,
              1,
              EconomyCurrency.CAD,
              10000,
              true,
              start,
              1000,
              new BigDecimal("-0.002"),
              BigDecimal.ZERO,
              0,
              BigDecimal.ZERO,
              BigDecimal.ZERO);
      var usd =
          new CreditReporting.Source(
              f.owner.world(),
              f.actor,
              UUID.randomUUID(),
              1,
              start,
              1,
              EconomyCurrency.USD,
              10000,
              true,
              start,
              0,
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              0,
              BigDecimal.ZERO,
              BigDecimal.ZERO);
      var area =
          f.store.transaction(
              tx -> CreditScoreRuntime.integral(tx::query, List.of(cad, usd), start, start + 100));
      var fx =
          f.store.transaction(
              tx -> FxService.publication(tx::query, START.atZone(ZoneOffset.UTC).toLocalDate()));
      var expected =
          CreditScoreRuntime.Fraction.decimal(new BigDecimal("9.998"))
              .multiply(CreditScoreRuntime.Fraction.decimal(new BigDecimal("100")))
              .divide(
                  CreditScoreRuntime.Fraction.decimal(
                      new BigDecimal("100")
                          .add(
                              new BigDecimal("100")
                                  .multiply(fx.rates().get(EconomyCurrency.USD)))));
      assertEquals(expected, area.ratio());
      assertEquals("FX_" + fx.updateId(), area.source());
      assertThrows(
          IllegalStateException.class,
          () ->
              f.store.transaction(
                  tx ->
                      CreditScoreRuntime.integral(
                          tx::query, List.of(cad, usd), start + 86_400_000, start + 86_400_100)));
    }
  }

  private static final InstitutionCatalog.InstitutionId BANK =
      new InstitutionCatalog.InstitutionId("economy:bank/crownstone");
  private static final Instant START = Instant.parse("2026-11-05T12:00:00Z");

  private final class ActualFixture implements AutoCloseable {
    final SqliteStorage store = new SqliteStorage();
    final PlayerId actor = new PlayerId(UUID.randomUUID());
    final AccountId account = new AccountId(UUID.randomUUID());
    final AtomicLong time = new AtomicLong(START.toEpochMilli());
    final PartyRef owner;

    ActualFixture() throws Exception {
      store.initialize(world.toRealPath());
      owner = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(actor));
      assertEquals(
          Journal.Status.COMMITTED,
          submit(
                  FxService.OPEN,
                  FxService.opening(Clock.fixed(START, ZoneOffset.UTC)),
                  new Journal.CanonicalRequest(1, Map.of("currency", "CAD")))
              .status());
      var capital =
          new InstitutionCapital(
              store, new SystemActorId(UUID.randomUUID()), CapitalPosting.poster());
      for (int at = 0; at < 26; at++)
        assertEquals(Journal.Status.COMMITTED, capital.advance(START).status());
      var activation = new Journal.Kind("fixture:score", "fixture:activation");
      submit(
          activation,
          new JournalEngine.Registration(
              1,
              (intent, reads) ->
                  new JournalEngine.Commit(
                      List.of(
                          new Journal.Record(DeadlineDispatcher.ACTIVATION, 1, "1|" + time.get())),
                      new Journal.Result(1, "FIXTURE", ""),
                      tx -> {
                        tx.execute(
                            "INSERT INTO financial_calendar(singleton,version,origin_ms,observed_wall_ms,last_effective_ms,completed_effects) VALUES(1,1,?,?,?,0)",
                            List.of(
                                Long.toString(time.get()),
                                Long.toString(time.get()),
                                Long.toString(time.get())));
                        tx.execute(
                            "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                            List.of(account.value().toString(), actor.value().toString()));
                      })),
          new Journal.CanonicalRequest(1, Map.of()));
    }

    CreditFacilities.Inputs inputs(JournalEngine.ReadView reads, PartyRef target, long now) {
      var fx = FxService.latest(reads);
      return new CreditFacilities.Inputs(
          new CreditUnderwriting.FxSnapshot(
              fx.updateId(),
              fx.day().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
              now,
              fx.rates(),
              true),
          true,
          false,
          0);
    }

    Journal.Outcome submit(
        Journal.Kind kind, JournalEngine.Registration handler, Journal.CanonicalRequest request) {
      return new JournalEngine(store, (a, k) -> true, Map.of(kind, handler))
          .submit(
              new Journal.Intent(
                  new OperationId(UUID.randomUUID()),
                  new RequestId(UUID.randomUUID()),
                  actor,
                  kind,
                  request,
                  null,
                  null));
    }

    UUID issueAndDraw() {
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
      var request =
          new CreditFacilities.IssueRequest(
              proof,
              UUID.randomUUID(),
              InstitutionCatalog.Network.ORBIT,
              EconomyCurrency.CAD,
              250000,
              0,
              0,
              time.get());
      assertEquals(
          "CREDIT_CARD_ISSUED",
          submit(
                  CreditFacilities.ISSUE,
                  CreditFacilities.issuance(
                      owner, request, BankProviders.NO_TIER2, this::inputs, time::get),
                  request.request())
              .result()
              .code());
      var facility =
          store.transaction(
              tx -> CreditFacilities.find(tx::query, request.facility()).orElseThrow());
      var token =
          store.transaction(
              tx ->
                  CreditFacilities.tokenForFacility(tx::query, owner.world(), facility.id())
                      .orElseThrow());
      var draw =
          new CreditFacilities.DrawRequest(
              owner.world(),
              actor,
              facility.id(),
              token.id(),
              token.network(),
              CreditFacilities.DrawKind.PURCHASE,
              account,
              20000,
              facility.revision(),
              token.revision(),
              1,
              1,
              UUID.randomUUID(),
              time.get() + 30000);
      assertEquals(
          "CREDIT_DRAWN",
          submit(
                  CreditFacilities.DRAW,
                  CreditFacilities.draw(
                      owner, draw, this::inputs, (i, r, t, k, a) -> true, time::get),
                  draw.request())
              .result()
              .code());
      return facility.id();
    }

    DeadlineDispatcher scheduler() {
      var providers = new ArrayList<>(CreditRuntime.providers());
      providers.addAll(DebtRuntime.providers());
      providers.addAll(CreditScoreRuntime.providers());
      var clock =
          new Clock() {
            public ZoneId getZone() {
              return ZoneOffset.UTC;
            }

            public Clock withZone(ZoneId zone) {
              return this;
            }

            public Instant instant() {
              return Instant.ofEpochMilli(time.get());
            }
          };
      var dispatcher = new DeadlineDispatcher(store, clock, providers, 1);
      dispatcher.prepare();
      return dispatcher;
    }

    @Override
    public void close() throws Exception {
      store.close();
    }
  }

  @Test
  void actualCardSourcesBootstrapThenDailyReportAndScoreProvidersCatchUpOffline() throws Exception {
    try (var f = new ActualFixture()) {
      var facility = f.issueAndDraw();
      for (int turn = 0; turn < 32 && !CreditReporting.bootstrap(f.store); turn++) {}
      assertTrue(CreditReporting.bootstrap(f.store));
      assertEquals(
          2,
          (int)
              f.store.transaction(
                  tx ->
                      Integer.parseInt(
                          tx.query(
                                  "SELECT COUNT(*) AS n FROM credit_reporting_sources",
                                  List.of(),
                                  1)
                              .getFirst()
                              .get("n"))));
      var dispatcher = f.scheduler();
      f.time.set(START.toEpochMilli() + 2 * CYCLE);
      for (int turn = 0; turn < 2048 && !dispatcher.progress().ready(); turn++)
        dispatcher.advance();
      assertTrue(dispatcher.progress().ready());
      var profile =
          f.store.transaction(tx -> CreditProfiles.find(tx::query, f.owner).orElseThrow());
      assertEquals(START.toEpochMilli() + 2 * CYCLE, profile.lastBoundary());
      assertEquals(699, profile.score());
      var reports =
          f.store.transaction(
              tx ->
                  DebtLifecycle.reports(tx::query, f.owner, DebtLifecycle.ReportCursor.start(), 8));
      assertEquals(1, reports.entries().size());
      assertFalse(reports.entries().getFirst().metAtSnapshot());
      assertEquals(
          60,
          (int)
              f.store.transaction(
                  tx ->
                      Integer.parseInt(
                          tx.query(
                                  "SELECT COUNT(*) AS n FROM credit_reporting_sources WHERE facility_id=?",
                                  List.of(facility.toString()),
                                  1)
                              .getFirst()
                              .get("n"))));
      assertEquals(
          56,
          (int)
              f.store.transaction(
                  tx ->
                      Integer.parseInt(
                          tx.query(
                                  "SELECT COUNT(*) AS n FROM credit_utilization_days", List.of(), 1)
                              .getFirst()
                              .get("n"))));
      long sequence = f.store.lastCommittedSequence();
      dispatcher.advance();
      assertEquals(sequence, f.store.lastCommittedSequence());
    }
  }
}
