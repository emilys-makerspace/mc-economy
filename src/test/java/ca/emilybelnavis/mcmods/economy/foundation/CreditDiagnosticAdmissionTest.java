package ca.emilybelnavis.mcmods.economy.foundation;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.credit.CreditDiagnostics;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreditDiagnosticAdmissionTest {
  @TempDir Path world;

  @Test
  void inspectionIsAuditedDuringCatchupWithoutAdmittingFinancialWrites() throws Exception {
    var raw = new SqliteStorage();
    var catchup = new AtomicBoolean();
    FoundationStore store =
        (FoundationStore)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {FoundationStore.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("schedulesEconomy")) return true;
                  if (method.getName().equals("prepareEconomicStep"))
                    return new StorageLifecycle.EconomicStep<>(
                        new StorageLifecycle.EconomicProgress(
                            0, catchup.get() ? 1 : 0, false, !catchup.get()),
                        null,
                        null);
                  if (method.getName().equals("requireEconomicCurrent") && catchup.get())
                    throw new StorageException(
                        StorageException.Kind.ECONOMIC_CATCH_UP, "Fixture backlog");
                  try {
                    return method.invoke(raw, args);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
    var services = new ServerServices(world.toRealPath(), store);
    await(services, services.readiness());
    try {
      var owner = new PlayerId(UUID.randomUUID());
      var operator = new PlayerId(UUID.randomUUID());
      var target = new PartyRef(services.worldId(), new PartyRef.Player(owner));
      var grant =
          PartyAuthorization.admit(
                  services.worldId(), services.sessionId(), owner, false, target, false)
              .orElseThrow();
      var inspection =
          PartyAuthorization.admit(
                  services.worldId(), services.sessionId(), operator, true, target, true)
              .orElseThrow();
      assertTrue(
          await(services, services.ledger().creditFacilityPage(grant, null, 8))
              .orElseThrow()
              .entries()
              .isEmpty());
      assertThrows(
          IllegalArgumentException.class,
          () -> services.ledger().creditFacilityPage(inspection, null, 8));
      catchup.set(true);
      for (int n = 0; n < 1000 && services.metrics().economy().ready(); n++) {
        services.tick();
        Thread.sleep(2);
      }
      assertFalse(services.metrics().economy().ready());
      var facility = UUID.randomUUID();
      assertTrue(
          await(
                  services,
                  services.ledger().inspectCreditFacility(grant, facility, null, 4, () -> true))
              .isEmpty());
      assertTrue(
          await(
                  services,
                  services
                      .ledger()
                      .inspectCreditFacility(inspection, facility, null, 4, () -> false))
              .isEmpty());
      assertEquals(0, count(services, raw));
      assertTrue(
          await(
                  services,
                  services
                      .ledger()
                      .inspectCreditFacility(
                          inspection,
                          facility,
                          CreditDiagnostics.EventCursor.start(),
                          4,
                          () -> true))
              .isEmpty());
      assertEquals(1, count(services, raw));
      var prepared = new AtomicBoolean();
      var kind = new Journal.Kind("fixture:credit-diagnostics", "fixture:financial");
      var request = new Journal.CanonicalRequest(1, Map.of());
      var submit =
          services.submitOwned(
              grant,
              kind,
              new OperationId(UUID.randomUUID()),
              new RequestId(UUID.randomUUID()),
              request,
              new JournalEngine.Registration(
                  1,
                  (i, r) -> {
                    prepared.set(true);
                    return new JournalEngine.Commit(
                        List.of(), new Journal.Result(1, "BAD", ""), tx -> {});
                  }),
              null);
      var outcome = await(services, submit.completion()).outcome();
      assertEquals("ECONOMIC_CATCH_UP", outcome.result().code());
      assertFalse(prepared.get());
      assertEquals(1, count(services, raw));
    } finally {
      catchup.set(false);
      assertTrue(services.stop(Duration.ofSeconds(5)));
    }
  }

  private static int count(ServerServices services, SqliteStorage store) throws Exception {
    return await(
        services,
        services.storage(
            () ->
                store.transaction(
                    tx ->
                        Integer.parseInt(
                            tx.query(
                                    "SELECT count(*) AS n FROM journal_records WHERE record_type=?",
                                    List.of("economy:account_inspection"),
                                    1)
                                .getFirst()
                                .get("n")))));
  }

  private static <T> T await(ServerServices services, CompletionStage<T> stage) throws Exception {
    var future = stage.toCompletableFuture();
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!future.isDone() && System.nanoTime() < until) {
      services.tick();
      Thread.sleep(1);
    }
    return future.get(1, TimeUnit.SECONDS);
  }
}
