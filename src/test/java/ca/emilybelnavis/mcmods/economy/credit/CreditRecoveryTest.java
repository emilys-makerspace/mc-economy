package ca.emilybelnavis.mcmods.economy.credit;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreditRecoveryTest {
  @TempDir Path temporary;

  private String killOnlyOwnedProbe(Path world, String phase, String edge) throws Exception {
    var child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp",
                System.getProperty("economy.test.classpath"),
                CreditProcessProbe.class.getName(),
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
                        var value = reader.readLine();
                        if (value == null) break;
                        if (value.startsWith("BOUNDARY|")) return value;
                        output.append(value).append('\n');
                      }
                      throw new IllegalStateException(
                          "Disposable credit probe never reached boundary: " + output);
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
                List.of(CreditProcessProbe.stable("operation|target|" + phase).toString()),
                1));
  }

  private static long balance(FoundationStore store, FinancialAccounts.Role role) {
    return store.transaction(
        tx ->
            Settlement.posted(
                tx::query,
                FinancialAccounts.id(
                    store.snapshot().worldId(), CreditProcessProbe.BANK, role, EconomyCurrency.CAD),
                EconomyCurrency.CAD));
  }

  private static long customer(FoundationStore store) {
    return store.transaction(
        tx ->
            Settlement.posted(tx::query, CreditProcessProbe.ACCOUNT.value(), EconomyCurrency.CAD));
  }

  private static int recordCount(FoundationStore store, Journal.Outcome outcome, String fact) {
    return store.transaction(
        tx ->
            Integer.parseInt(
                tx.query(
                        "SELECT count(*) AS n FROM journal_records WHERE sequence=? AND record_type=?",
                        List.of(Long.toString(outcome.sequence().value()), fact),
                        1)
                    .getFirst()
                    .get("n")));
  }

  private static void assertFinancialState(
      FoundationStore store, String phase, Journal.Outcome outcome) {
    var f = CreditProcessProbe.facility(store);
    long debt = f.owedMinor();
    assertEquals(12300, f.principalMinor() + (phase.equals("payment") ? 10000 : 0));
    assertEquals(debt, balance(store, FinancialAccounts.Role.RECEIVABLE));
    var borrower =
        new InstitutionCatalog.InstitutionId(
            "economy:borrower/" + CreditProcessProbe.OWNER.value());
    long liability =
        store.transaction(
            tx ->
                Settlement.posted(
                    tx::query,
                    FinancialAccounts.id(
                        f.world(), borrower, FinancialAccounts.Role.LIABILITY, EconomyCurrency.CAD),
                    EconomyCurrency.CAD));
    assertEquals(-debt, liability);
    assertEquals(250000 - f.principalMinor(), balance(store, FinancialAccounts.Role.RESERVATION));
    assertEquals(phase.equals("payment") ? 2300 : 12300, customer(store));
    switch (phase) {
      case "draw" -> {
        assertEquals(12300, debt);
        assertEquals(1, recordCount(store, outcome, CreditFacilities.EVENT_FACT));
      }
      case "interest" -> {
        assertEquals(12307, debt);
        assertEquals(7, f.purchase().interestMinor());
        assertEquals(1, recordCount(store, outcome, CreditAuditFacts.DAILY));
      }
      case "statement" -> {
        assertTrue(debt > 12300);
        assertTrue(f.requiredMinor() > 0);
        assertEquals(CreditProcessProbe.ANCHOR + CreditFacilities.MONTH, f.lastStatement());
        assertEquals(1, recordCount(store, outcome, CreditFacilities.STATEMENT_FACT));
        assertEquals(
            1,
            store
                .transaction(
                    tx ->
                        CreditFacilities.statements(
                            tx::query,
                            CreditProcessProbe.owner(store),
                            CreditProcessProbe.FACILITY,
                            -1,
                            16))
                .statements()
                .size());
      }
      case "payment" -> {
        assertEquals(2300, debt);
        assertEquals(1, recordCount(store, outcome, CreditAuditFacts.PAYMENT));
      }
      default -> fail("Unknown fixture phase");
    }
    var diagnostic =
        store.transaction(
            tx ->
                CreditDiagnostics.inspect(
                        tx::query,
                        CreditProcessProbe.owner(store),
                        CreditProcessProbe.FACILITY,
                        CreditProcessProbe.ANCHOR + CreditFacilities.MONTH,
                        CreditDiagnostics.EventCursor.start(),
                        16)
                    .orElseThrow());
    assertTrue(diagnostic.reconciliation().ownerMatches());
    assertTrue(diagnostic.reconciliation().issuerMatches());
    assertTrue(diagnostic.reconciliation().ownerJournalComplete());
    assertTrue(diagnostic.reconciliation().issuerJournalComplete());
    assertTrue(diagnostic.reconciliation().ownerJournalMatches());
    assertTrue(diagnostic.reconciliation().issuerJournalMatches());
  }

  @Test
  void actualProcessDeathBeforeAndAfterFinancialCommitReplaysDrawInterestStatementAndPaymentOnce()
      throws Exception {
    for (var phase : List.of("draw", "interest", "statement", "payment"))
      for (var edge : List.of("before", "after")) {
        Path world = Files.createDirectory(temporary.toRealPath().resolve(phase + "-" + edge));
        CreditProcessProbe.seed(world, phase);
        var boundary = killOnlyOwnedProbe(world, phase, edge).split("\\|", -1);
        assertArrayEquals(
            new String[] {
              "BOUNDARY",
              phase,
              edge,
              CreditProcessProbe.stable("operation|target|" + phase).toString()
            },
            boundary);
        var store = new SqliteStorage();
        store.initialize(world);
        try {
          var prefix = outcome(store, phase);
          assertEquals(edge.equals("after") ? 1 : 0, prefix.size());
          var original = CreditProcessProbe.registration(store, world, phase);
          var preparations = new AtomicInteger();
          var registration =
              new JournalEngine.Registration(
                  1,
                  (i, r) -> {
                    preparations.incrementAndGet();
                    return original.handler().prepare(i, r);
                  });
          var intent = CreditProcessProbe.target(world, phase);
          var completed = CreditProcessProbe.submit(store, intent, registration);
          assertEquals(Journal.Status.COMMITTED, completed.status());
          assertEquals(edge.equals("after") ? 0 : 1, preparations.get());
          if (!prefix.isEmpty()) assertEquals(prefix, outcome(store, phase));
          assertFinancialState(store, phase, completed);
          long sequence = store.lastCommittedSequence();
          var saved = outcome(store, phase);
          var f = CreditProcessProbe.facility(store);
          var replay =
              CreditProcessProbe.submit(
                  store,
                  intent,
                  new JournalEngine.Registration(
                      1,
                      (i, r) -> {
                        throw new AssertionError("Replay must not prepare financial effects");
                      }));
          assertEquals(completed, replay);
          assertEquals(sequence, store.lastCommittedSequence());
          assertEquals(saved, outcome(store, phase));
          assertEquals(f, CreditProcessProbe.facility(store));
          store.recover();
          assertEquals(completed, CreditProcessProbe.submit(store, intent, registration));
          assertEquals(sequence, store.lastCommittedSequence());
          assertFinancialState(store, phase, completed);
        } finally {
          store.close();
        }
      }
  }

  @Test
  void unknownCommittedOutcomeReconnectsAndLooksUpOriginalWithoutPreparingAgain() throws Exception {
    for (var phase : List.of("draw", "interest", "statement", "payment")) {
      Path world = Files.createDirectory(temporary.toRealPath().resolve("unknown-" + phase));
      CreditProcessProbe.seed(world, phase);
      var raw = new SqliteStorage();
      raw.initialize(world);
      var armed = new AtomicBoolean(true);
      var recoveries = new AtomicInteger();
      FoundationStore uncertain =
          new FoundationStore() {
            public void initialize(Path ignored) {
              throw new UnsupportedOperationException();
            }

            public Snapshot snapshot() {
              return raw.snapshot();
            }

            public long lastCommittedSequence() {
              return raw.lastCommittedSequence();
            }

            public RecordCatalog recordCatalog() {
              return raw.recordCatalog();
            }

            public void recover() {
              recoveries.incrementAndGet();
              raw.recover();
            }

            public void close() throws Exception {
              raw.close();
            }

            public JournalEngine.Decision decorateCommit(
                Journal.Intent intent, JournalEngine.ReadView reads, JournalEngine.Commit commit) {
              return raw.decorateCommit(intent, reads, commit);
            }

            public <T> T transaction(Work<T> work) {
              T result = raw.transaction(work);
              if (result instanceof Journal.Outcome o
                  && o.status() == Journal.Status.COMMITTED
                  && armed.getAndSet(false))
                throw new StorageException(
                    StorageException.Kind.UNKNOWN_COMMIT,
                    "Injected after real committed credit fixture transaction");
              return result;
            }
          };
      try {
        var original = CreditProcessProbe.registration(uncertain, world, phase);
        var prepared = new AtomicInteger();
        var registration =
            new JournalEngine.Registration(
                1,
                (i, r) -> {
                  prepared.incrementAndGet();
                  return original.handler().prepare(i, r);
                });
        var intent = CreditProcessProbe.target(world, phase);
        var result = CreditProcessProbe.submit(uncertain, intent, registration);
        assertEquals(Journal.Status.COMMITTED, result.status());
        assertEquals(1, prepared.get());
        assertEquals(1, recoveries.get());
        assertFinancialState(uncertain, phase, result);
        long sequence = raw.lastCommittedSequence();
        assertEquals(result, CreditProcessProbe.submit(uncertain, intent, registration));
        assertEquals(1, prepared.get());
        assertEquals(sequence, raw.lastCommittedSequence());
      } finally {
        uncertain.close();
      }
    }
  }
}
