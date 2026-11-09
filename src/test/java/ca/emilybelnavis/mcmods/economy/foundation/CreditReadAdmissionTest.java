package ca.emilybelnavis.mcmods.economy.foundation;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreditReadAdmissionTest {
  @TempDir Path world;

  @Test
  void ownerReadsNeverEstablishCreditAndOperatorReadsRequireAuditedSeparateAuthority()
      throws Exception {
    var store = new SqliteStorage();
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
      var view = await(services, services.ledger().creditProfile(grant)).orElseThrow();
      assertTrue(view.profile().isEmpty());
      assertTrue(view.latest().isEmpty());
      assertFalse(view.recurringScoringActive());
      assertTrue(
          await(services, services.ledger().creditHistory(grant, 0, 8))
              .orElseThrow()
              .entries()
              .isEmpty());
      assertTrue(
          await(services, services.ledger().creditFacilities(grant, null, 16))
              .orElseThrow()
              .isEmpty());
      assertThrows(
          IllegalArgumentException.class,
          () -> services.ledger().creditFacilities(grant, null, 17));
      assertTrue(
          await(services, services.ledger().inspectCreditProfile(grant, () -> true)).isEmpty());
      assertThrows(
          IllegalArgumentException.class, () -> services.ledger().creditHistory(grant, -1, 8));
      assertThrows(
          IllegalArgumentException.class, () -> services.ledger().creditHistory(grant, 0, 17));
      var inspection =
          PartyAuthorization.admit(
                  services.worldId(), services.sessionId(), operator, true, target, true)
              .orElseThrow();
      assertThrows(
          IllegalArgumentException.class, () -> services.ledger().creditProfile(inspection));
      assertThrows(
          IllegalArgumentException.class,
          () -> services.ledger().creditFacilities(inspection, null, 8));
      assertTrue(
          await(services, services.ledger().inspectCreditProfile(inspection, () -> false))
              .isEmpty());
      assertEquals(0, count(services, store, "journal_records"));
      assertTrue(
          await(services, services.ledger().inspectCreditProfile(inspection, () -> true))
              .orElseThrow()
              .profile()
              .isEmpty());
      assertEquals(1, count(services, store, "journal_records"));
      for (var table :
          List.of(
              "credit_profiles",
              "credit_score_history",
              "credit_inquiry_groups",
              "credit_inquiry_applications",
              "credit_facilities",
              "credit_facility_tokens",
              "credit_statements")) assertEquals(0, count(services, store, table));
      assertTrue(
          PartyAuthorization.admit(
                  services.worldId(), services.sessionId(), operator, false, target, false)
              .isEmpty());
    } finally {
      assertTrue(services.stop(Duration.ofSeconds(5)));
    }
  }

  private static int count(ServerServices services, SqliteStorage store, String table)
      throws Exception {
    return await(
        services,
        services.storage(
            () ->
                store.transaction(
                    tx ->
                        Integer.parseInt(
                            tx.query("SELECT count(*) AS n FROM " + table, List.of(), 1)
                                .getFirst()
                                .get("n")))));
  }

  private static <T> T await(ServerServices services, CompletionStage<T> stage) throws Exception {
    var future = stage.toCompletableFuture();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!future.isDone() && System.nanoTime() < end) {
      services.tick();
      Thread.sleep(1);
    }
    return future.get(1, TimeUnit.SECONDS);
  }
}
