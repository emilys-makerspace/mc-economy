package ca.emilybelnavis.mcmods.economy.loan;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.clock.*;
import ca.emilybelnavis.mcmods.economy.debt.*;
import ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebtProcessTest {
  @TempDir Path path;

  @Test
  void killedDefaultAndSaleChildrenResumeWithoutDuplicatingClaimsOrSaleProceeds() throws Exception {
    for (String mode : List.of("PENDING", "SOLD")) {
      Path world = Files.createDirectory(path.toRealPath().resolve(mode));
      UUID loan;
      long at;
      long face;
      try (var fixture = new LoanTestFixture(world)) {
        var contract = fixture.originate();
        loan = contract.id();
        at = contract.nextMonth() + DebtRecords.DEFAULT_DELAY;
        face = contract.owedMinor();
        DebtAcceptanceTest.activate(fixture);
      }
      var child =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin/java").toString(),
                  "-cp",
                  System.getProperty("economy.test.classpath"),
                  DebtProcessProbe.class.getName(),
                  world.toString(),
                  loan.toString(),
                  Long.toString(at),
                  mode)
              .redirectErrorStream(true)
              .start();
      String boundary;
      try {
        boundary =
            CompletableFuture.supplyAsync(
                    () -> {
                      try (var reader =
                          new java.io.BufferedReader(
                              new java.io.InputStreamReader(child.getInputStream()))) {
                        var output = new StringBuilder();
                        for (int line = 0; line < 32; line++) {
                          String value = reader.readLine();
                          if (value == null) break;
                          if (value.startsWith("BOUNDARY|")) return value;
                          output.append(value).append('\n');
                        }
                        throw new IllegalStateException("No debt fixture boundary " + output);
                      } catch (java.io.IOException error) {
                        throw new CompletionException(error);
                      }
                    })
                .get(20, TimeUnit.SECONDS);
        child.destroyForcibly();
        assertTrue(child.waitFor(10, TimeUnit.SECONDS));
        assertNotEquals(0, child.exitValue());
      } finally {
        if (child.isAlive()) {
          child.destroyForcibly();
          assertTrue(child.waitFor(5, TimeUnit.SECONDS));
        }
      }
      var store = new SqliteStorage();
      store.initialize(world);
      try {
        var committed =
            store.transaction(
                tx ->
                    DebtLifecycle.facilityCase(tx::query, DebtRecords.Kind.LOAN, loan)
                        .orElseThrow());
        assertEquals(boundary.split("\\|")[1], committed.id().toString());
        assertEquals(mode, committed.sale().name());
        assertTrue(committed.debtMinor() >= face);
        var providers = new ArrayList<>(DebtRuntime.providers());
        providers.add(LoanRuntime.provider());
        var scheduler =
            new DeadlineDispatcher(
                store, Clock.fixed(Instant.ofEpochMilli(at), ZoneOffset.UTC), providers, 1);
        scheduler.prepare();
        for (int turn = 0; !scheduler.progress().ready(); turn++) {
          assertTrue(turn < 16);
          scheduler.advance();
        }
        var sold =
            store.transaction(
                tx -> DebtLifecycle.findCase(tx::query, committed.id()).orElseThrow());
        assertEquals(DebtRecords.Sale.SOLD, sold.sale());
        assertEquals(committed.debtMinor(), sold.debtMinor());
        var sales =
            store.transaction(
                tx ->
                    tx.query(
                        "SELECT payload FROM journal_records WHERE record_type='economy:a6/debt-accounting' AND substr(payload,38,36)=? ORDER BY sequence,position LIMIT 4",
                        List.of(sold.id().toString()),
                        4));
        assertEquals(1, sales.size());
        assertTrue(sales.getFirst().get("payload").contains("|SALE|"));
        assertTrue(
            (boolean)
                store.transaction(
                    tx ->
                        DebtClaims.matches(
                            tx::query,
                            sold.world(),
                            sold.creditor(),
                            FinancialAccounts.Role.RECEIVABLE,
                            sold.currency())));
        long before = store.lastCommittedSequence();
        scheduler.advance();
        assertEquals(before, store.lastCommittedSequence());
      } finally {
        store.close();
      }
    }
  }
}
