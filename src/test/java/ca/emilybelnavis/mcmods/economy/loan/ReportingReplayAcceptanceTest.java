package ca.emilybelnavis.mcmods.economy.loan;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.debt.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportingReplayAcceptanceTest {
  @TempDir Path root;

  private record Result(
      List<Map<String, String>> balances,
      List<Map<String, String>> cases,
      List<Map<String, String>> reports,
      List<Map<String, String>> scores) {}

  @Test
  void offlineExpiredCasesAndOverdueScoresMatchChronologicalProcessingAfterRestart()
      throws Exception {
    Path seed = Files.createDirectory(root.toRealPath().resolve("seed"));
    long origin;
    try (var fixture = new LoanTestFixture(seed)) {
      origin = fixture.clock.get();
      fixture.originate();
      fixture.originate();
      fixture.originate();
      DebtAcceptanceTest.activate(fixture);
      for (int turn = 0; !CreditReporting.bootstrap(fixture.store); turn++) assertTrue(turn < 32);
    }
    Path daily = cloneSeed(seed, "daily"), offline = cloneSeed(seed, "offline");
    long lastDay = 28 + 84 + 728 + 2;
    var chronological = run(daily, origin, lastDay, false);
    var restored = run(offline, origin, lastDay, true);
    assertEquals(chronological, restored);
    assertEquals(3, restored.cases().size());
    assertTrue(
        restored.cases().stream()
            .allMatch(row -> row.get("collectability").equals("UNCOLLECTABLE")));
    assertEquals(31, restored.scores().size());
    assertEquals("530", restored.scores().getLast().get("score"));
  }

  private Path cloneSeed(Path seed, String name) throws Exception {
    Path destination = Files.createDirectory(root.toRealPath().resolve(name));
    Files.createDirectory(destination.resolve("economy"));
    for (String file : List.of("economy.sqlite", "world-identity"))
      Files.copy(
          seed.resolve("economy").resolve(file), destination.resolve("economy").resolve(file));
    return destination;
  }

  private Result run(Path world, long origin, long lastDay, boolean offline) throws Exception {
    var now = new AtomicLong(origin);
    var providers = new ArrayList<>(DebtRuntime.providers());
    providers.add(LoanRuntime.provider());
    providers.addAll(CreditScoreRuntime.providers());
    var wall =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId zone) {
            return this;
          }

          public Instant instant() {
            return Instant.ofEpochMilli(now.get());
          }
        };
    var store = new SqliteStorage();
    store.initialize(world);
    var scheduler = new DeadlineDispatcher(store, wall, providers, 1);
    scheduler.prepare();
    boolean reopened = false;
    int turns = 0;
    try {
      for (long day = offline ? lastDay : 0; day <= lastDay; day++) {
        now.set(origin + day * DebtRecords.DAY);
        while (!scheduler.progress().ready()) {
          assertTrue(++turns < 8192);
          long before = store.lastCommittedSequence();
          scheduler.advance();
          assertTrue(store.lastCommittedSequence() - before <= 1);
          if (offline && !reopened && turns == 17) {
            store.close();
            store = new SqliteStorage();
            store.initialize(world);
            scheduler = new DeadlineDispatcher(store, wall, providers, 1);
            scheduler.prepare();
            reopened = true;
          }
        }
      }
      if (offline) assertTrue(reopened);
      long sequence = store.lastCommittedSequence();
      scheduler.advance();
      assertEquals(sequence, store.lastCommittedSequence());
      return store.transaction(
          tx ->
              new Result(
                  tx.query(
                      "SELECT account_id,currency,minor_units FROM ledger_balances ORDER BY account_id,currency",
                      List.of(),
                      128),
                  tx.query(
                      "SELECT case_id,body,collectability FROM debt_cases ORDER BY case_id",
                      List.of(),
                      16),
                  tx.query(
                      "SELECT report_id,revision,body FROM debt_reports ORDER BY report_id,revision",
                      List.of(),
                      64),
                  tx.query(
                      "SELECT boundary,score,input_fingerprint,input_snapshot FROM credit_score_history ORDER BY boundary",
                      List.of(),
                      64)));
    } finally {
      store.close();
    }
  }
}
