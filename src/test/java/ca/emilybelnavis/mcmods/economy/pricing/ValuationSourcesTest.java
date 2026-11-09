package ca.emilybelnavis.mcmods.economy.pricing;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValuationSourcesTest {
  @TempDir Path path;
  private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
  private static final PlayerId OWNER = new PlayerId(new UUID(61, 1));

  private static WealthValuation.Source source(
      String id, WealthValuation.Kind kind, boolean fresh, boolean available) {
    return new WealthValuation.Source() {
      public String id() {
        return id;
      }

      public WealthValuation.Kind kind() {
        return kind;
      }

      public boolean requiresFreshMark() {
        return fresh;
      }

      public WealthValuation.SourceSnapshot read(
          JournalEngine.ReadView reads, WorldId world, PlayerId owner, Instant now) {
        return available
            ? new WealthValuation.SourceSnapshot(true, 1, "fixture-v1", NOW, List.of())
            : WealthValuation.SourceSnapshot.unavailable(1, NOW);
      }
    };
  }

  private SqliteStorage open() throws Exception {
    var store = new SqliteStorage();
    store.initialize(path.toRealPath());
    return store;
  }

  private static long count(FoundationStore store) {
    return store.transaction(
        tx ->
            Long.parseLong(
                tx.query("SELECT count(*) AS n FROM pricing_valuation_sources", List.of(), 1)
                    .getFirst()
                    .get("n")));
  }

  @Test
  void registrationIsBoundedAndIdempotentAndAnUnmodifiableManifestSurvivesRestart()
      throws Exception {
    var valuation =
        new WealthValuation(
            List.of(
                source("fixture:asset", WealthValuation.Kind.ASSET, true, true),
                source("fixture:debt", WealthValuation.Kind.LIABILITY, false, true)));
    assertThrows(UnsupportedOperationException.class, () -> valuation.sourceManifest().clear());
    long frontier;
    {
      var store = open();
      try (AutoCloseable close = store::close) {
        assertFalse(ValuationSources.prepare(store, valuation));
        assertEquals(1, count(store));
        assertFalse(ValuationSources.prepare(store, valuation));
        assertEquals(2, count(store));
        frontier = store.lastCommittedSequence();
        assertTrue(ValuationSources.prepare(store, valuation));
        assertEquals(frontier, store.lastCommittedSequence());
      }
    }
    {
      var store = open();
      try (AutoCloseable close = store::close) {
        assertTrue(ValuationSources.prepare(store, valuation));
        assertEquals(2, count(store));
        assertEquals(frontier, store.lastCommittedSequence());
      }
    }
  }

  @Test
  void removingARequiredLiabilityAdapterAfterRestartFailsClosedInsteadOfBecomingZeroDebt()
      throws Exception {
    var liabilities =
        new WealthValuation(
            List.of(source("fixture:debt", WealthValuation.Kind.LIABILITY, false, true)));
    {
      var store = open();
      try (AutoCloseable close = store::close) {
        assertFalse(ValuationSources.prepare(store, liabilities));
        assertTrue(ValuationSources.prepare(store, liabilities));
      }
    }
    {
      var store = open();
      try (AutoCloseable close = store::close) {
        var error =
            assertThrows(
                StorageException.class,
                () -> ValuationSources.prepare(store, new WealthValuation()));
        assertEquals(StorageException.Kind.SCHEMA, error.kind());
        assertTrue(error.getMessage().contains("Missing or changed required valuation source"));
        store.recover();
        assertEquals(1, count(store));
      }
    }
  }

  @Test
  void newSourcesMayBeAddedButAnExistingKindOrFreshnessContractCannotChangeSilently()
      throws Exception {
    {
      var store = open();
      try (AutoCloseable close = store::close) {
        var debt = source("fixture:debt", WealthValuation.Kind.LIABILITY, false, true);
        assertFalse(ValuationSources.prepare(store, new WealthValuation(List.of(debt))));
        var expanded =
            new WealthValuation(
                List.of(
                    debt, source("fixture:securities", WealthValuation.Kind.ASSET, true, true)));
        assertFalse(ValuationSources.prepare(store, expanded));
        assertEquals(2, count(store));
        assertTrue(ValuationSources.prepare(store, expanded));
        assertThrows(
            StorageException.class,
            () ->
                ValuationSources.prepare(
                    store,
                    new WealthValuation(
                        List.of(
                            source("fixture:debt", WealthValuation.Kind.ASSET, false, true),
                            source(
                                "fixture:securities", WealthValuation.Kind.ASSET, true, true)))));
        assertThrows(
            StorageException.class,
            () ->
                ValuationSources.prepare(
                    store,
                    new WealthValuation(
                        List.of(
                            debt,
                            source(
                                "fixture:securities", WealthValuation.Kind.ASSET, false, true)))));
      }
    }
  }

  @Test
  void
      unavailableInstalledSourceStillRegistersItsRequiredContractAndCannotProduceACompleteValuation()
          throws Exception {
    {
      var store = open();
      try (AutoCloseable close = store::close) {
        var valuation =
            new WealthValuation(
                List.of(source("fixture:debt", WealthValuation.Kind.LIABILITY, false, false)));
        assertFalse(ValuationSources.prepare(store, valuation));
        assertTrue(ValuationSources.prepare(store, valuation));
        var engine =
            new JournalEngine(
                store,
                (a, k) -> true,
                Map.of(FxService.OPEN, FxService.opening(Clock.fixed(NOW, ZoneOffset.UTC))));
        var operation =
            new Journal.Intent(
                new OperationId(UUID.randomUUID()),
                new RequestId(UUID.randomUUID()),
                OWNER,
                FxService.OPEN,
                new Journal.CanonicalRequest(1, Map.of("currency", "CAD")),
                null,
                null);
        assertEquals(Journal.Status.COMMITTED, engine.submit(operation).status());
        var snapshot =
            store.transaction(
                tx -> valuation.snapshot(tx::query, store.snapshot().worldId(), OWNER, NOW));
        assertFalse(snapshot.available());
        assertNull(snapshot.netWorth());
        assertTrue(snapshot.reason().startsWith("VALUATION_SOURCE_UNAVAILABLE"));
      }
    }
  }

  @Test
  void deletingTheProjectionCannotEraseAnInstalledSourceFromTheImmutableContractHistory()
      throws Exception {
    {
      var store = open();
      try (AutoCloseable close = store::close) {
        var valuation =
            new WealthValuation(
                List.of(source("fixture:debt", WealthValuation.Kind.LIABILITY, false, true)));
        ValuationSources.prepare(store, valuation);
        store.transaction(
            tx -> {
              tx.execute("DELETE FROM pricing_valuation_sources", List.of());
              return null;
            });
        var error =
            assertThrows(
                StorageException.class,
                () -> ValuationSources.prepare(store, new WealthValuation()));
        assertEquals(StorageException.Kind.SCHEMA, error.kind());
        assertTrue(error.getMessage().contains("immutable evidence"));
      }
    }
  }
}
