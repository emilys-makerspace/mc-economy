package ca.emilybelnavis.mcmods.economy.institution;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstitutionRuntimeTest {
  @TempDir Path path;
  private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
  private static final Clock WALL = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final SystemActorId ACTOR = new SystemActorId(new UUID(0, 818));

  @Test
  void coreStartupMigratesLegacyProvidersInSixteenAccountPages() throws Exception {
    var raw = new SqliteStorage();
    raw.initialize(path.toRealPath());
    try {
      raw.transaction(
          tx -> {
            for (int i = 1; i <= 40; i++)
              tx.execute(
                  "INSERT INTO account_catalog(account_id,owner_id,purpose,currency,status,revision,is_primary) VALUES(?,?,'BANK','CAD','OPEN',1,0)",
                  List.of(new UUID(0, i).toString(), new UUID(1, i).toString()));
            return null;
          });
    } finally {
      raw.close();
    }
    var runtime = new FoundationRuntime();
    runtime.initialize(path.toRealPath());
    try {
      long previous = 0;
      int pages = 0;
      for (int turn = 0; turn < 1000; turn++) {
        var progress = runtime.startupStep();
        if (progress.phase().equals("bank-provider-migration")) {
          long migrated = count(runtime, "bank_account_providers");
          assertTrue(migrated - previous > 0 && migrated - previous <= 16);
          previous = migrated;
          pages++;
        }
        if (progress.ready()) break;
        assertTrue(turn < 999);
      }
      assertEquals(3, pages);
      assertEquals(40, count(runtime, "bank_account_providers"));
      assertNull(runtime.readInstitutionCapitalStatus());
      assertEquals(
          40L,
          runtime.<Long>transaction(
              tx ->
                  Long.parseLong(
                      tx.query(
                              "SELECT count(*) AS n FROM bank_account_providers WHERE bank_key=? AND legacy=1",
                              List.of(BankProviders.UNASSIGNED_PROVIDER),
                              1)
                          .getFirst()
                          .get("n"))));
    } finally {
      runtime.close();
    }
  }

  @Test
  void productionStartupResumesPartialCapitalBeforeReady() throws Exception {
    var raw = new SqliteStorage();
    raw.initialize(path.toRealPath());
    try {
      seedFx(raw);
    } finally {
      raw.close();
    }
    var config = FoundationRuntime.Configuration.production(WALL);
    var runtime = new FoundationRuntime(config);
    runtime.initialize(path.toRealPath());
    try {
      for (int turn = 0; turn < 1000; turn++) {
        var progress = runtime.startupStep();
        var capital = runtime.readInstitutionCapitalStatus();
        if (capital != null && capital.nextAllocation() == 5) {
          assertFalse(progress.ready());
          break;
        }
        assertTrue(turn < 999);
      }
    } finally {
      runtime.close();
    }
    runtime = new FoundationRuntime(config);
    runtime.initialize(path.toRealPath());
    try {
      ready(runtime);
      assertTrue(runtime.readInstitutionCapitalStatus().active());
      assertEquals(25, count(runtime, "institution_capital_allocations"));
      assertEquals(1, count(runtime, "institution_capital_manifest"));
    } finally {
      runtime.close();
    }
  }

  @Test
  void missingDenominationIsDormantAndLaterSelectionSchedulesBoundedFunding() throws Exception {
    var runtime = new FoundationRuntime(FoundationRuntime.Configuration.production(WALL));
    runtime.initialize(path.toRealPath());
    try {
      ready(runtime);
      assertEquals(
          InstitutionCapital.State.WAITING_DENOMINATION,
          runtime.readInstitutionCapitalStatus().state());
      runtime.requireEconomicCurrent();
      seedFx(runtime);
      assertThrows(StorageException.class, runtime::requireEconomicCurrent);
      long previous = 0;
      for (int turn = 0; turn < 40; turn++) {
        var step = runtime.prepareEconomicStep();
        if (step.calculation() != null) step.commit().apply(step.calculation().get());
        long allocated = count(runtime, "institution_capital_allocations");
        assertTrue(allocated - previous >= 0 && allocated - previous <= 1);
        previous = allocated;
        if (runtime.readInstitutionCapitalStatus().active()) break;
      }
      assertEquals(25, previous);
      runtime.requireEconomicCurrent();
    } finally {
      runtime.close();
    }
  }

  private static void ready(FoundationRuntime runtime) {
    for (int turn = 0; turn < 1000; turn++) if (runtime.startupStep().ready()) return;
    fail("Startup did not complete in bounded fixture turns");
  }

  private static long count(FoundationStore store, String table) {
    return store.transaction(
        tx ->
            Long.parseLong(
                tx.query("SELECT count(*) AS n FROM " + table, List.of(), 1).getFirst().get("n")));
  }

  private static void seedFx(FoundationStore store) {
    var engine =
        new JournalEngine(
            store,
            (actor, kind) -> actor.equals(ACTOR),
            Map.of(FxService.OPEN, FxService.opening(WALL)));
    var id = UUID.randomUUID();
    assertEquals(
        Journal.Status.COMMITTED,
        engine
            .submit(
                new Journal.Intent(
                    new OperationId(id),
                    new RequestId(id),
                    ACTOR,
                    FxService.OPEN,
                    new Journal.CanonicalRequest(1, Map.of("currency", "CAD")),
                    null,
                    null))
            .status());
  }
}
