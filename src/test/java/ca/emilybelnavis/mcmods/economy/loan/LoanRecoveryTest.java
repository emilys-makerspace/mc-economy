package ca.emilybelnavis.mcmods.economy.loan;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.DeadlineDispatcher;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoanRecoveryTest {
  @TempDir Path temporary;

  private String killOnlyOwnedProbe(Path world, String phase, String edge) throws Exception {
    var child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp",
                System.getProperty("economy.test.classpath"),
                LoanProcessProbe.class.getName(),
                world.toString(),
                phase,
                edge)
            .redirectErrorStream(true)
            .start();
    try {
      String boundary =
          CompletableFuture.supplyAsync(
                  () -> {
                    var output = new StringBuilder();
                    try (var reader =
                        new java.io.BufferedReader(
                            new java.io.InputStreamReader(child.getInputStream()))) {
                      for (int line = 0; line < 32; line++) {
                        String value = reader.readLine();
                        if (value == null) break;
                        if (value.startsWith("BOUNDARY|")) return value;
                        output.append(value).append('\n');
                      }
                      throw new IllegalStateException(
                          "Owned loan process missed boundary " + output);
                    } catch (java.io.IOException error) {
                      throw new CompletionException(error);
                    }
                  })
              .get(15, TimeUnit.SECONDS);
      child.destroyForcibly();
      assertTrue(child.waitFor(10, TimeUnit.SECONDS));
      assertNotEquals(0, child.exitValue());
      return boundary;
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        assertTrue(child.waitFor(5, TimeUnit.SECONDS));
      }
    }
  }

  private static List<Map<String, String>> outcome(FoundationStore store, String phase) {
    return store.transaction(
        tx ->
            tx.query(
                "SELECT operation_id,transaction_id,sequence,canonical_request,result_code,result_payload FROM operation_outcomes WHERE operation_id=?",
                List.of(LoanProcessProbe.stable("operation|" + phase).toString()),
                1));
  }

  private static long balance(FoundationStore store, FinancialAccounts.Role role) {
    return store.transaction(
        tx ->
            Settlement.posted(
                tx::query,
                FinancialAccounts.id(
                    store.snapshot().worldId(), LoanTestFixture.BANK, role, EconomyCurrency.CAD),
                EconomyCurrency.CAD));
  }

  private static void assertFinancialState(
      FoundationStore store, Path world, String phase, Journal.Outcome outcome) throws Exception {
    var properties = LoanProcessProbe.properties(world);
    var loan = LoanProcessProbe.contract(store, world);
    var currency = loan.currency();
    assertEquals(loan.owedMinor(), balance(store, FinancialAccounts.Role.RECEIVABLE));
    var liabilityAccount =
        FinancialAccounts.id(
            loan.world(), Loans.borrower(loan.owner()), FinancialAccounts.Role.LIABILITY, currency);
    long liability =
        store.transaction(tx -> Settlement.posted(tx::query, liabilityAccount, currency));
    assertEquals(-loan.owedMinor(), liability);
    assertEquals(0, balance(store, FinancialAccounts.Role.RESERVATION));
    long received =
        phase.equals("partial") || phase.equals("payoff")
            ? Long.parseLong(LoanProcessProbe.target(world, phase).request().fields().get("amount"))
            : 0;
    long expectedCash =
        Long.parseLong(properties.getProperty("cash"))
            + received
            - (phase.equals("origination") ? 100000 : 0);
    long expectedCustomer =
        Long.parseLong(properties.getProperty("customer"))
            - received
            + (phase.equals("origination") ? 100000 : 0);
    assertEquals(expectedCash, balance(store, FinancialAccounts.Role.CASH));
    var account = UUID.fromString(properties.getProperty("account"));
    long customer = store.transaction(tx -> Settlement.posted(tx::query, account, currency));
    assertEquals(expectedCustomer, customer);
    var records =
        store.transaction(
            tx ->
                tx.query(
                    "SELECT record_type FROM journal_records WHERE sequence=? ORDER BY position",
                    List.of(Long.toString(outcome.sequence().value())),
                    64));
    assertEquals(
        1, records.stream().filter(row -> row.get("record_type").equals(Loans.FACT)).count());
    assertEquals(
        1, records.stream().filter(row -> row.get("record_type").equals(Loans.EVENT_FACT)).count());
    if (phase.equals("month")) {
      assertEquals(1, loan.servicing().completedPeriods());
      assertEquals(
          1,
          records.stream()
              .filter(row -> row.get("record_type").equals(Loans.INSTALLMENT_FACT))
              .count());
      assertEquals(
          1,
          store
              .transaction(tx -> Loans.installments(tx::query, loan.party(), loan.id(), 0, 16))
              .installments()
              .size());
    }
    if (phase.equals("payoff")) {
      assertEquals(Loans.Status.SETTLED, loan.status());
      assertEquals(0, loan.servicing().exactDebtMajor().signum());
      assertTrue(loan.servicing().requirements().isEmpty());
    } else {
      assertEquals(Loans.Status.ACTIVE, loan.status());
      assertTrue(loan.owedMinor() > 0);
    }
    var diagnostic =
        store.transaction(
            tx ->
                LoanDiagnostics.inspect(
                        tx::query,
                        loan.party(),
                        loan.id(),
                        loan.servicing().lastAccrued(),
                        LoanDiagnostics.EventCursor.start(),
                        16)
                    .orElseThrow());
    assertTrue(diagnostic.reconciliation().ownerMatches());
    assertTrue(diagnostic.reconciliation().issuerMatches());
    assertTrue(diagnostic.reconciliation().ownerJournalMatches());
    assertTrue(diagnostic.reconciliation().issuerJournalMatches());
  }

  @Test
  void actualProcessDeathBeforeAndAfterCommitReplaysOriginationMonthPartialAndPayoffOnce()
      throws Exception {
    for (String phase : List.of("origination", "month", "partial", "payoff")) {
      for (String edge : List.of("before", "after")) {
        Path world = Files.createDirectory(temporary.toRealPath().resolve(phase + "-" + edge));
        LoanProcessProbe.seed(world, phase);
        String boundary = killOnlyOwnedProbe(world, phase, edge);
        assertEquals(
            "BOUNDARY|" + phase + "|" + edge + "|" + LoanProcessProbe.stable("operation|" + phase),
            boundary);
        var store = new SqliteStorage();
        store.initialize(world);
        try {
          var savedOutcome = outcome(store, phase);
          assertEquals(edge.equals("after") ? 1 : 0, savedOutcome.size());
          var original = LoanProcessProbe.registration(store, world, phase);
          var preparations = new AtomicInteger();
          var registration =
              new JournalEngine.Registration(
                  1,
                  (intent, reads) -> {
                    preparations.incrementAndGet();
                    return original.handler().prepare(intent, reads);
                  });
          var intent = LoanProcessProbe.target(world, phase);
          var completed = LoanProcessProbe.submit(store, intent, registration);
          assertEquals(Journal.Status.COMMITTED, completed.status());
          assertEquals(edge.equals("after") ? 0 : 1, preparations.get());
          if (!savedOutcome.isEmpty()) assertEquals(savedOutcome, outcome(store, phase));
          assertFinancialState(store, world, phase, completed);
          long sequence = store.lastCommittedSequence();
          var snapshot = LoanProcessProbe.contract(store, world);
          var replay =
              LoanProcessProbe.submit(
                  store,
                  intent,
                  new JournalEngine.Registration(
                      1,
                      (ignored, reads) -> {
                        throw new AssertionError("Stored loan outcome must bypass preparation");
                      }));
          assertEquals(completed, replay);
          assertEquals(sequence, store.lastCommittedSequence());
          assertEquals(snapshot, LoanProcessProbe.contract(store, world));
          store.recover();
          assertEquals(completed, LoanProcessProbe.submit(store, intent, registration));
          assertEquals(sequence, store.lastCommittedSequence());
          assertFinancialState(store, world, phase, completed);
        } finally {
          store.close();
        }
      }
    }
  }

  @Test
  void downtimeCatchupResumesApplyingCursorAfterRestartAndKeepsOriginalDueIdentities()
      throws Exception {
    var fixture = new LoanTestFixture(temporary.toRealPath());
    var first = fixture.originate();
    var second = fixture.originate();
    var activation = new Journal.Kind("fixture:loan-recovery", "fixture:activation");
    fixture.submit(
        activation,
        new JournalEngine.Registration(
            1,
            (intent, reads) ->
                new JournalEngine.Commit(
                    List.of(
                        new Journal.Record(
                            DeadlineDispatcher.ACTIVATION, 1, "1|" + first.anchor())),
                    new Journal.Result(1, "FIXTURE", ""),
                    transaction -> {})),
        fixture.intent(activation, new Journal.CanonicalRequest(1, Map.of())));
    var wall = Clock.fixed(Instant.ofEpochMilli(first.anchor() + 3 * Loans.MONTH), ZoneOffset.UTC);
    var scheduler = new DeadlineDispatcher(fixture.store, wall, List.of(LoanRuntime.provider()), 1);
    scheduler.prepare();
    long before = fixture.store.lastCommittedSequence();
    scheduler.advance();
    assertTrue(fixture.store.lastCommittedSequence() - before <= 1);
    var applying =
        fixture.store.transaction(
            tx ->
                tx.query(
                    "SELECT event_id,cursor,completed_effects FROM financial_deadline_events WHERE state='APPLYING'",
                    List.of(),
                    1));
    assertEquals(1, applying.size());
    assertEquals("1", applying.getFirst().get("completed_effects"));
    fixture.close();

    var store = new SqliteStorage();
    store.initialize(temporary.toRealPath());
    try {
      var resumed = new DeadlineDispatcher(store, wall, List.of(LoanRuntime.provider()), 1);
      resumed.prepare();
      assertEquals(
          applying,
          store.transaction(
              tx ->
                  tx.query(
                      "SELECT event_id,cursor,completed_effects FROM financial_deadline_events WHERE state='APPLYING'",
                      List.of(),
                      1)));
      int turns = 0;
      while (!resumed.progress().ready() && turns++ < 16) {
        long sequence = store.lastCommittedSequence();
        resumed.advance();
        assertTrue(store.lastCommittedSequence() - sequence <= 1);
      }
      assertTrue(resumed.progress().ready());
      assertTrue(turns <= 8);
      for (var original : List.of(first, second)) {
        var loan = store.transaction(tx -> Loans.find(tx::query, original.id()).orElseThrow());
        assertEquals(3, loan.servicing().completedPeriods());
        var installments =
            store
                .transaction(tx -> Loans.installments(tx::query, loan.party(), loan.id(), 0, 16))
                .installments();
        assertEquals(3, installments.size());
        for (int index = 0; index < installments.size(); index++) {
          var installment = installments.get(index);
          assertEquals(original.anchor() + (index + 1L) * Loans.MONTH, installment.dueAt());
          assertEquals(installment.id(), loan.servicing().requirements().get(index).id());
          assertEquals(
              installment.originalMinor(),
              loan.servicing().requirements().get(index).originalMinor());
        }
      }
      long completed = store.lastCommittedSequence();
      resumed.prepare();
      resumed.advance();
      assertEquals(completed, store.lastCommittedSequence());
    } finally {
      store.close();
    }
  }
}
