package ca.emilybelnavis.mcmods.economy.foundation;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.collection.CollectionPresence;
import ca.emilybelnavis.mcmods.economy.debt.*;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
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

class CollectionPresenceAdmissionTest {
  @TempDir Path path;

  @Test
  void presentationRemovalDrainsDuringCatchupAndInspectionCannotUseOwnerCapabilities()
      throws Exception {
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
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                });
    var services = new ServerServices(path.toRealPath(), store);
    await(services, services.readiness());
    try {
      var player = new PlayerId(UUID.randomUUID());
      var target = new PartyRef(services.worldId(), new PartyRef.Player(player));
      var owner =
          PartyAuthorization.admit(
                  services.worldId(), services.sessionId(), player, false, target, false)
              .orElseThrow();
      var inspection =
          PartyAuthorization.admit(
                  services.worldId(),
                  services.sessionId(),
                  new PlayerId(UUID.randomUUID()),
                  true,
                  target,
                  true)
              .orElseThrow();
      long now = System.currentTimeMillis();
      var bank = new InstitutionCatalog.InstitutionId("economy:bank/crownstone");
      var debt =
          new DebtRecords.Case(
              target.world(),
              UUID.randomUUID(),
              DebtRecords.Kind.CARD,
              UUID.randomUUID(),
              player,
              bank,
              bank,
              EconomyCurrency.CAD,
              1000,
              0,
              now,
              now,
              now + DebtRecords.COLLECTION_WINDOW,
              now + DebtRecords.REPORT_WINDOW,
              DebtRecords.Sale.PENDING,
              DebtRecords.Collectability.COLLECTIBLE,
              DebtRecords.Payment.UNPAID,
              0,
              1);
      await(
          services,
          services.storage(
              () ->
                  raw.transaction(
                      tx -> {
                        DebtRecords.write(tx, debt);
                        return null;
                      })));
      assertThrows(
          IllegalArgumentException.class,
          () -> services.ledger().collectionAgentState(inspection, null, 4));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              services
                  .ledger()
                  .collectionAgentTransition(
                      inspection, CollectionPresence.Action.RESERVE, UUID.randomUUID(), 0));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              services
                  .ledger()
                  .lookupCollectionPaymentReceipt(inspection, new OperationId(UUID.randomUUID())));
      var lease = UUID.randomUUID();
      assertEquals(
          "COLLECTION_AGENT_RESERVED",
          await(
                  services,
                  services
                      .ledger()
                      .collectionAgentTransition(
                          owner, CollectionPresence.Action.RESERVE, lease, 0))
              .result()
              .code());
      catchup.set(true);
      for (int turn = 0; turn < 1000 && services.metrics().economy().ready(); turn++) {
        services.tick();
        Thread.sleep(2);
      }
      assertFalse(services.metrics().economy().ready());
      assertEquals(
          "COLLECTION_AGENT_RELEASED",
          await(
                  services,
                  services
                      .ledger()
                      .collectionAgentTransition(
                          owner, CollectionPresence.Action.RELEASE, lease, 0))
              .result()
              .code());
      assertTrue(
          await(
                  services,
                  services
                      .ledger()
                      .lookupCollectionPaymentReceipt(owner, new OperationId(UUID.randomUUID())))
              .isEmpty());
      assertThrows(
          ExecutionException.class,
          () ->
              await(
                  services,
                  services
                      .ledger()
                      .collectionAgentTransition(
                          owner, CollectionPresence.Action.RESERVE, UUID.randomUUID(), 0)));
    } finally {
      catchup.set(false);
      assertTrue(services.stop(Duration.ofSeconds(5)));
    }
  }

  @Test
  void productionSnapshotReadChecksReadinessBeforeOpeningItsTransaction() throws Exception {
    var runtime =
        new ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime(
            ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime.Configuration.production(
                java.time.Clock.systemUTC()));
    var services = new ServerServices(path.toRealPath(), runtime);
    await(services, services.readiness());
    try {
      var player = new PlayerId(UUID.randomUUID());
      var target = new PartyRef(services.worldId(), new PartyRef.Player(player));
      var grant =
          PartyAuthorization.admit(
                  services.worldId(), services.sessionId(), player, false, target, false)
              .orElseThrow();
      assertTrue(
          await(services, services.ledger().collectionAgentState(grant, null, 4))
              .orElseThrow()
              .cases()
              .isEmpty());
    } finally {
      assertTrue(services.stop(Duration.ofSeconds(5)));
    }
  }

  private static <T> T await(ServerServices services, CompletionStage<T> stage) throws Exception {
    var future = stage.toCompletableFuture();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!future.isDone() && System.nanoTime() < deadline) {
      services.tick();
      Thread.sleep(1);
    }
    return future.get(1, TimeUnit.SECONDS);
  }
}
