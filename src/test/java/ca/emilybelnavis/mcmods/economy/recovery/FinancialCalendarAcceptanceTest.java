package ca.emilybelnavis.mcmods.economy.recovery;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.clock.DeadlineDispatcher;
import ca.emilybelnavis.mcmods.economy.clock.EconomicClock;
import ca.emilybelnavis.mcmods.economy.clock.FinancialCalendar;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Calendar foundation acceptance only: fixture stages carry no real loans or product semantics. */
class FinancialCalendarAcceptanceTest {
  @TempDir Path temporary;
  private static final long ORIGIN = 123;
  private static final long MONTH = FinancialCalendar.MONTH_MILLIS;
  private static final int MONTHS = 3;
  private static final int CHILDREN_PER_MONTH = 65 + 8 + 1;

  private static final class Wall extends Clock {
    long now = ORIGIN;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      if (!zone.equals(ZoneOffset.UTC)) throw new IllegalArgumentException("UTC fixture only");
      return this;
    }

    public Instant instant() {
      return Instant.ofEpochMilli(now);
    }

    public long millis() {
      return now;
    }
  }

  private static final class StageProvider implements DeadlineDispatcher.Provider {
    private final UUID world;
    private final String id;
    private final UUID subject;
    private final int stage;
    private final long offset;
    private final List<String> targets;
    private final AtomicInteger prepared = new AtomicInteger();
    private final AtomicInteger maximumRequested = new AtomicInteger();
    private final AtomicInteger maximumReturned = new AtomicInteger();

    StageProvider(UUID world, int stage, long offset, int targets) {
      this.world = world;
      this.stage = stage;
      this.offset = offset;
      id = offset == 0 ? "fixture:stage" + stage : "fixture:anchored";
      subject = UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      this.targets =
          IntStream.range(0, targets)
              .mapToObj(index -> String.format(Locale.ROOT, "target%03d", index))
              .toList();
    }

    public String id() {
      return id;
    }

    public Map<Integer, String> versions() {
      return Map.of(1, "acceptance-v1", 2, "acceptance-v2");
    }

    private FinancialCalendar.Boundary boundary(long origin, int month) {
      return FinancialCalendar.boundary(
          world, subject, Math.addExact(origin, offset), FinancialCalendar.Period.MONTH, month);
    }

    public DeadlineDispatcher.Event next(
        EconomicClock.Queries reads, long origin, DeadlineDispatcher.Event completed) {
      int month =
          completed == null
              ? 1
              : Math.toIntExact((completed.effectiveMillis() - origin - offset) / MONTH) + 1;
      if (month > MONTHS) return null;
      var boundary = boundary(origin, month);
      return new DeadlineDispatcher.Event(
          boundary.id(),
          id,
          boundary.instantMillis(),
          stage,
          DeadlineDispatcher.scheduledVersion(reads, id, boundary.instantMillis()));
    }

    public List<String> targets(
        DeadlineDispatcher.Event event, String cursor, int limit, EconomicClock.Queries reads) {
      maximumRequested.accumulateAndGet(limit, Math::max);
      var page =
          targets.stream().filter(target -> target.compareTo(cursor) > 0).limit(limit).toList();
      maximumReturned.accumulateAndGet(page.size(), Math::max);
      return page;
    }

    public EconomicClock.Output prepare(
        DeadlineDispatcher.Event event, String target, EconomicClock.Queries reads) {
      prepared.incrementAndGet();
      return new EconomicClock.Output(
          id + "/" + target,
          tx ->
              tx.execute(
                  "INSERT INTO fixture_calendar_effects(event,effective_ms,provider,stage,target,version) VALUES(?,?,?,?,?,?)",
                  List.of(
                      event.id().toString(),
                      Long.toString(event.effectiveMillis()),
                      id,
                      Integer.toString(stage),
                      target,
                      Integer.toString(event.version()))));
    }
  }

  private static long effectCount(FoundationStore store) {
    return store.transaction(
        tx ->
            Long.parseLong(
                tx.query("SELECT count(*) AS n FROM fixture_calendar_effects", List.of(), 1)
                    .getFirst()
                    .get("n")));
  }

  private static List<Map<String, String>> effects(FoundationStore store) {
    return store.transaction(
        tx ->
            tx.query("SELECT * FROM fixture_calendar_effects ORDER BY sequence", List.of(), 1024));
  }

  private static List<Map<String, String>> facts(FoundationStore store) {
    return store.transaction(
        tx ->
            tx.query(
                "SELECT t.sequence,t.transaction_id,t.operation_id,r.position,r.record_type,r.payload "
                    + "FROM journal_transactions t JOIN journal_records r ON r.sequence=t.sequence ORDER BY t.sequence,r.position",
                List.of(),
                1024));
  }

  private static void ready(FoundationRuntime runtime) {
    for (int turn = 0; turn < 1000; turn++) if (runtime.startupStep().ready()) return;
    fail("Calendar acceptance did not reach ready within the bounded fixture budget");
  }

  private static void oneTurn(FoundationRuntime runtime) {
    long before = effectCount(runtime);
    var step = runtime.prepareEconomicStep();
    if (step.calculation() != null) step.commit().apply(step.calculation().get());
    assertTrue(
        effectCount(runtime) - before <= 1, "A worker turn committed more than one economic child");
  }

  @Test
  void monthCheckpointOfflineLeapAndRewindKeepIdsOrderVersionsAndPagedProgress() throws Exception {
    Path world = temporary.toRealPath();
    var wall = new Wall();
    var seed = new SqliteStorage();
    seed.initialize(world);
    UUID worldId = seed.snapshot().worldId().value();
    seed.close();
    // Fixture schema is created only while no storage owner is open; production DDL is untouched.
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + world.resolve("economy/economy.sqlite"));
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE fixture_calendar_effects(sequence INTEGER PRIMARY KEY,event TEXT NOT NULL,"
              + "effective_ms INTEGER NOT NULL,provider TEXT NOT NULL,stage INTEGER NOT NULL,target TEXT NOT NULL,"
              + "version INTEGER NOT NULL,UNIQUE(event,target))");
    }
    var providers = new ArrayList<StageProvider>();
    for (int stage = 0; stage <= 8; stage++)
      providers.add(new StageProvider(worldId, stage, 0, stage == 1 ? 65 : 1));
    providers.add(new StageProvider(worldId, 4, 777, 1));
    List<DeadlineDispatcher.Provider> adapters = new ArrayList<>(providers);
    var setup = new SqliteStorage();
    setup.initialize(world);
    try {
      var scheduler = new DeadlineDispatcher(setup, wall, adapters, 16);
      scheduler.prepare();
      for (var provider : providers)
        scheduler.scheduleVersion(provider.id(), 2, ORIGIN + 2 * MONTH);
    } finally {
      setup.close();
    }
    var fixedCalls = new AtomicInteger();
    var fixedVersion =
        new EconomicClock.Version(
            1,
            "acceptance-publication-v1",
            update -> {
              fixedCalls.incrementAndGet();
              return new EconomicClock.Output(
                  "fixed",
                  tx ->
                      tx.execute(
                          "INSERT INTO fixture_calendar_effects(event,effective_ms,provider,stage,target,version) VALUES(?,?,'fixture:publication',0,'fixed',1)",
                          List.of(update.id().toString(), Long.toString(update.endMillis()))));
            });
    var fixed =
        new EconomicClock.SystemDefinition(
            "fixture:publication", MONTH, ORIGIN, 1, Map.of(1, fixedVersion), Set.of());
    var config =
        new FoundationRuntime.Configuration(
            RecordCatalog.core(),
            List.of(),
            List.of(fixed),
            wall,
            Map.of(),
            true,
            adapters,
            new FinancialCalendar.Config(16));
    var runtime = new FoundationRuntime(config);
    List<Map<String, String>> checkpointFacts;
    List<Map<String, String>> checkpointEffects;
    runtime.initialize(world);
    try {
      ready(runtime);
      wall.now = ORIGIN + MONTH - 1;
      oneTurn(runtime);
      assertEquals(0, effectCount(runtime));
      assertDoesNotThrow(runtime::requireEconomicCurrent);
      wall.now++;
      // Fixed publication + domain stage0 + seventeen accrual children crosses a page boundary.
      for (int turn = 0; effectCount(runtime) < 19 && turn < 30; turn++) oneTurn(runtime);
      assertEquals(19, effectCount(runtime));
      assertEquals(
          StorageException.Kind.ECONOMIC_CATCH_UP,
          assertThrows(StorageException.class, runtime::requireEconomicCurrent).kind());
      var active =
          runtime
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT event_id,cursor,completed_effects,state FROM financial_deadline_events "
                              + "WHERE provider='fixture:stage1'",
                          List.of(),
                          1))
              .getFirst();
      assertEquals(providers.get(1).boundary(ORIGIN, 1).id().toString(), active.get("event_id"));
      assertEquals("target016", active.get("cursor"));
      assertEquals("17", active.get("completed_effects"));
      assertEquals("APPLYING", active.get("state"));
      checkpointFacts = facts(runtime);
      checkpointEffects = effects(runtime);
    } finally {
      runtime.close();
    }

    // Stopped elapsed UTC is observed as debt, then a rewind must retain that target at restart.
    wall.now = ORIGIN + 3 * MONTH + 1234;
    var stopped = new SqliteStorage();
    stopped.initialize(world);
    try {
      var scheduler = new DeadlineDispatcher(stopped, wall, adapters, 16);
      scheduler.prepare();
      assertFalse(scheduler.observeCurrentDebt().ready());
    } finally {
      stopped.close();
    }
    wall.now = ORIGIN + MONTH + 1000;
    var resumed = new FoundationRuntime(config);
    resumed.initialize(world);
    try {
      boolean waiting = false;
      for (int turn = 0; turn < 300; turn++) {
        var progress = resumed.startupStep();
        assertFalse(progress.ready(), "A rewind erased retained stopped-time debt");
        if (progress.phase().equals("waiting-for-wall-clock")) {
          waiting = true;
          break;
        }
      }
      assertTrue(waiting);
      assertEquals(CHILDREN_PER_MONTH + 1, effectCount(resumed));
      assertTrue(
          effects(resumed).stream()
              .allMatch(row -> Long.parseLong(row.get("effective_ms")) <= wall.now));
      assertEquals(checkpointFacts, facts(resumed).subList(0, checkpointFacts.size()));
      assertEquals(checkpointEffects, effects(resumed).subList(0, checkpointEffects.size()));
      wall.now = ORIGIN + 3 * MONTH + 1234;
      ready(resumed);
      assertDoesNotThrow(resumed::requireEconomicCurrent);
      var all = effects(resumed);
      assertEquals(MONTHS * (CHILDREN_PER_MONTH + 1), all.size());
      assertEquals(checkpointFacts, facts(resumed).subList(0, checkpointFacts.size()));
      assertEquals(checkpointEffects, all.subList(0, checkpointEffects.size()));
      long previousTime = -1;
      int previousStage = -1;
      for (var row : all) {
        long effective = Long.parseLong(row.get("effective_ms"));
        int stage = Integer.parseInt(row.get("stage"));
        assertTrue(effective >= previousTime);
        if (effective == previousTime) assertTrue(stage >= previousStage);
        previousTime = effective;
        previousStage = stage;
      }
      for (int month = 1; month <= MONTHS; month++) {
        long boundary = ORIGIN + month * MONTH;
        var sameTime =
            all.stream()
                .filter(row -> Long.parseLong(row.get("effective_ms")) == boundary)
                .toList();
        assertEquals("fixture:publication", sameTime.getFirst().get("provider"));
        var orderedStages =
            sameTime.stream().skip(1).map(row -> Integer.parseInt(row.get("stage"))).toList();
        var expectedStages = new ArrayList<Integer>();
        for (int stage = 0; stage <= 8; stage++)
          for (int target = 0; target < (stage == 1 ? 65 : 1); target++) expectedStages.add(stage);
        assertEquals(expectedStages, orderedStages);
        String expectedPolicy = month == 1 ? "1" : "2";
        for (var provider : providers) {
          var expected = provider.boundary(ORIGIN, month);
          var children =
              all.stream()
                  .filter(row -> row.get("event").equals(expected.id().toString()))
                  .toList();
          assertEquals(provider.targets.size(), children.size());
          assertTrue(children.stream().allMatch(row -> row.get("version").equals(expectedPolicy)));
        }
      }
      assertEquals(
          3, fixedCalls.get(), "Restart recomputed an already committed fixed publication");
      assertEquals(
          MONTHS * CHILDREN_PER_MONTH,
          providers.stream().mapToInt(provider -> provider.prepared.get()).sum());
      assertTrue(
          providers.stream()
              .allMatch(
                  provider ->
                      provider.maximumRequested.get() <= 16
                          && provider.maximumReturned.get() <= 16));
      assertEquals(16, providers.get(1).maximumReturned.get());
      assertEquals(
          "30",
          resumed
              .transaction(
                  tx ->
                      tx.query(
                          "SELECT count(*) AS n FROM financial_deadline_events WHERE state='COMPLETE'",
                          List.of(),
                          1))
              .getFirst()
              .get("n"));
      long journal = resumed.lastCommittedSequence();
      oneTurn(resumed);
      assertEquals(
          journal, resumed.lastCommittedSequence(), "An idle replay created another completion");
    } finally {
      resumed.close();
    }
  }

  @Test
  void everyApprovedBankAndSubprimeTermMapsToThirteenPaymentsPerYear() {
    UUID world = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID facility = UUID.fromString("00000000-0000-0000-0000-000000000002");
    for (int years = 1; years <= 8; years++) {
      long payments =
          FinancialCalendar.completedPeriods(
              ORIGIN,
              FinancialCalendar.at(ORIGIN, FinancialCalendar.Period.YEAR, years),
              FinancialCalendar.Period.MONTH);
      assertEquals(13L * years, payments);
      var finalInstallment =
          FinancialCalendar.boundary(
              world, facility, ORIGIN, FinancialCalendar.Period.MONTH, payments);
      assertEquals(
          FinancialCalendar.at(ORIGIN, FinancialCalendar.Period.YEAR, years),
          finalInstallment.instantMillis());
      if (years <= 5)
        assertTrue(payments >= 13 && payments <= 65, "Bank term must map to13–65 installments");
      assertTrue(payments >= 13 && payments <= 104, "Subprime term must map to13–104 installments");
    }
  }
}
