package ca.emilybelnavis.mcmods.economy.credit;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreditActivationTest {
  @TempDir Path path;

  @Test
  void acceptedDependenciesAdmitFoundationOnlyWhenSourceAndScoreCatchupAreComplete()
      throws Exception {
    var store = new SqliteStorage();
    store.initialize(path.toRealPath());
    try {
      var calls = new AtomicInteger();
      var kind = new Journal.Kind("fixture:credit", "fixture:admission");
      var registration =
          CreditSafety.production(
              new JournalEngine.Registration(
                  1,
                  (i, r) -> {
                    calls.incrementAndGet();
                    return new JournalEngine.Commit(
                        List.of(
                            new Journal.Record(
                                ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle.ATTEMPT,
                                1,
                                UUID.randomUUID() + "|0|BOOTSTRAP_CARD")),
                        new Journal.Result(1, "FOUNDATION_REACHED", ""),
                        tx -> {});
                  }));
      var engine = new JournalEngine(store, (a, k) -> true, Map.of(kind, registration));
      var actor = new PlayerId(UUID.randomUUID());
      var initial =
          new Journal.Intent(
              new OperationId(UUID.randomUUID()),
              new RequestId(UUID.randomUUID()),
              actor,
              kind,
              new Journal.CanonicalRequest(1, Map.of()),
              null,
              null);
      assertEquals(CreditSafety.STATUS, engine.submit(initial).result().code());
      assertEquals(0, calls.get());
      store.transaction(
          tx -> {
            for (String table :
                List.of(
                    "debt_bootstrap", "debt_legacy_reconciliation", "credit_reporting_bootstrap"))
              tx.execute("UPDATE " + table + " SET ready=1 WHERE singleton=1", List.of());
            tx.execute(
                "INSERT INTO financial_calendar(singleton,version,origin_ms,observed_wall_ms,last_effective_ms,completed_effects) VALUES(1,1,0,0,0,0)",
                List.of());
            for (String provider :
                List.of(
                    "economy:a6/debt-default",
                    "economy:a6/debt-due",
                    "economy:a6/debt-expiry",
                    "economy:a6/debt-sale",
                    "economy:a6/debt-report",
                    CreditScoreRuntime.PROVIDER))
              tx.execute(
                  "INSERT INTO financial_deadline_providers(provider,implementations) VALUES(?,?)",
                  List.of(provider, "fixture-v1"));
            return null;
          });
      assertTrue((boolean) store.transaction(tx -> CreditSafety.available(tx::query)));
      var accepted =
          new Journal.Intent(
              new OperationId(UUID.randomUUID()),
              new RequestId(UUID.randomUUID()),
              actor,
              kind,
              new Journal.CanonicalRequest(1, Map.of()),
              null,
              null);
      assertEquals("FOUNDATION_REACHED", engine.submit(accepted).result().code());
      assertEquals(1, calls.get());
      store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO credit_score_heads VALUES(?,?,0)",
                List.of(store.snapshot().worldId().value().toString(), actor.value().toString()));
            return null;
          });
      assertFalse((boolean) store.transaction(tx -> CreditSafety.available(tx::query)));
      assertEquals(
          "REPORTING_CATCHUP_REQUIRED",
          store.transaction(tx -> CreditSafety.reportingStatus(tx::query)));
      var blocked =
          new Journal.Intent(
              new OperationId(UUID.randomUUID()),
              new RequestId(UUID.randomUUID()),
              actor,
              kind,
              new Journal.CanonicalRequest(1, Map.of()),
              null,
              null);
      assertEquals(CreditSafety.STATUS, engine.submit(blocked).result().code());
      assertEquals(1, calls.get());
      assertEquals("FOUNDATION_REACHED", engine.submit(accepted).result().code());
      assertEquals(1, calls.get());
    } finally {
      store.close();
    }
  }
}
