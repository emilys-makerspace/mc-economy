package ca.emilybelnavis.mcmods.economy.valuation;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.ItemValuationIntegration.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public DTOs are exercised against actual evaluator output and retained SQLite data. */
class ValuationApiTest {
  @TempDir Path world;

  @Test
  void publicResultPreservesExactForeignValueProofAndRetainedProvenance() throws Exception {
    var store = new SqliteStorage();
    store.initialize(world.toRealPath());
    try {
      var wall = Clock.fixed(Instant.parse("2026-11-08T12:00:00Z"), ZoneOffset.UTC);
      var id = UUID.randomUUID();
      assertEquals(
          Journal.Status.COMMITTED,
          new JournalEngine(store, (a, k) -> true, Map.of(FxService.OPEN, FxService.opening(wall)))
              .submit(
                  new Journal.Intent(
                      new OperationId(id),
                      new RequestId(id),
                      new PlayerId(UUID.randomUUID()),
                      FxService.OPEN,
                      new Journal.CanonicalRequest(1, Map.of("currency", "CAD")),
                      null,
                      null))
              .status());
      var fx = store.transaction(tx -> FxService.at(tx::query, wall.millis()));
      var inputs =
          new ValuationData.Inputs(
              ValuationData.catalog().entries().keySet(),
              ValuationData.hash("recipes"),
              ValuationData.hash("tags"),
              ValuationData.hash("features"));
      var manifest =
          ValuationData.prepare(
              inputs,
              new ValuationConfig.Config(
                  List.of(),
                  List.of(
                      new ValuationConfig.Entry("minecraft:diamond", EconomyCurrency.CAD, "1", 3))),
              List.of());
      var saved =
          ValuationStore.publish(
              store, ValuationData.encode(manifest), wall.millis(), new ValuationStore.Gate(), 0);
      var request =
          new Request(
              new Identity("minecraft:diamond", "{}", 1),
              3,
              EconomyCurrency.USD,
              new Retained(saved.id(), fx.id()));
      var value =
          RecipeValues.evaluate(
              manifest, request.identity().itemId(), "{}", 3, request.currency(), fx);
      var result =
          ValuationApi.result(
              request,
              new ValuationRuntime.Read(
                  saved, manifest, wall.millis() + 1, fx, value, 7, Long.MAX_VALUE));
      assertEquals(Status.READY, result.status(), result.reasonCode());
      assertEquals(
          new Exact(BigInteger.valueOf(25000), BigInteger.valueOf(1047)),
          result.exactUnitMinor().orElseThrow());
      assertEquals(24, result.unitMoney().orElseThrow().minorUnits());
      assertEquals(72, result.totalMoney().orElseThrow().minorUnits());
      assertEquals(
          Instant.ofEpochMilli(saved.publishedMillis()), result.effectiveFrom().orElseThrow());
      var ref = result.snapshot().orElseThrow();
      assertEquals(saved.id(), ref.valuationSnapshot());
      assertEquals(saved.hash(), ref.contentHash());
      assertEquals(manifest.configHash(), ref.configHash());
      assertEquals(manifest.inputs().recipeHash(), ref.recipeHash());
      assertEquals(manifest.providerHash(), ref.providerHash());
      assertEquals(fx.id(), ref.fxPublication());
      assertEquals(Instant.ofEpochMilli(fx.effectiveMillis()), ref.fxEffectiveFrom());
      var proof = result.explanation().orElseThrow();
      assertEquals(value.explanation().rootHash(), proof.rootHash());
      assertFalse(proof.steps().isEmpty());
      assertEquals(
          value.explanation().steps().getFirst().source().kind(),
          proof.steps().getFirst().sourceKind());
      assertThrows(UnsupportedOperationException.class, () -> proof.steps().clear());
      assertEquals(saved.id(), ValuationRuntime.metadata(saved).id());
      assertEquals(
          ValuationConfig.bytes(saved.payload()), ValuationRuntime.metadata(saved).bytes());

      // An unavailable read still identifies the retained source, with no partial price or proof.
      var missing =
          new Request(
              new Identity("minecraft:air", "{}", 1), 1, EconomyCurrency.CAD, new Current());
      var refused =
          RecipeValues.evaluate(manifest, "minecraft:air", "{}", 1, EconomyCurrency.CAD, null);
      var unavailable =
          ValuationApi.result(
              missing,
              new ValuationRuntime.Read(
                  saved, manifest, wall.millis(), null, refused, 0, Long.MAX_VALUE));
      assertEquals(Status.UNPRICED_OPERATOR, unavailable.status());
      assertTrue(unavailable.unitMoney().isEmpty());
      assertTrue(unavailable.exactUnitMinor().isEmpty());
      assertTrue(unavailable.explanation().isEmpty());
      assertNull(unavailable.snapshot().orElseThrow().fxPublication());
    } finally {
      store.close();
    }
  }

  @Test
  void typedContributionKeepsExactSelectorsAndMaterialFactorsThroughConversion() {
    var owned = Set.of("fixture:raw", "fixture:variant", "fixture:stateful");
    var base = new Base("fixture:raw", EconomyCurrency.USD, "2.50", 3);
    var selected =
        new ExactSelector(
            new Identity("fixture:stateful", "{\"fixture:state\":{\"z\":2,\"a\":1}}", 1),
            new Base("fixture:stateful", EconomyCurrency.CAD, "10", 1));
    var contribution =
        new Contribution(
            "fixture:provider",
            "r1",
            owned,
            List.of(base),
            List.of(selected),
            Set.of(),
            Map.of(
                "fixture:variant",
                new MaterialRule(List.of(new Material("fixture:raw", 2, 3)), "0.25")),
            Set.of(ValuationContributions.MATERIALS));
    var converted = ValuationApi.convert(contribution);
    assertEquals(owned, converted.claimedItems());
    assertEquals("2.5", converted.bases().getFirst().amount());
    assertEquals(3, converted.bases().getFirst().referenceCount());
    assertEquals(
        "{\"fixture:state\":{\"a\":1,\"z\":2}}",
        converted.exactSelectors().getFirst().canonicalComponents());
    assertEquals(
        new VariantGraph.Term("fixture:raw", 2, 3),
        converted.variantRules().get("fixture:variant").materials().getFirst());
    assertEquals("0.25", converted.variantRules().get("fixture:variant").contentCad());
    assertThrows(UnsupportedOperationException.class, () -> contribution.ownedItemIds().clear());
    assertThrows(
        IllegalArgumentException.class, () -> new ExactSelector(selected.identity(), base));
    assertThrows(IllegalArgumentException.class, () -> new Material("fixture:raw", 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new MaterialRule(List.of(), null));
  }

  @Test
  void publicRequestsRejectAmbiguousIdentityAndUnboundedExactValues() {
    var identity =
        new Identity("minecraft:diamond", "{\"minecraft:custom_name\":{\"text\":\"Gem\"}}", 1);
    assertThrows(IllegalArgumentException.class, () -> new Identity("diamond", "{}", 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Identity("minecraft:diamond", "{\"a\":1,\"a\":2}", 1));
    assertThrows(IllegalArgumentException.class, () -> new Identity("minecraft:diamond", "{}", 2));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Request(identity, 0, EconomyCurrency.CAD, new Current()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Request(identity, 1_000_001, EconomyCurrency.CAD, new Current()));
    assertThrows(NullPointerException.class, () -> new Retained(UUID.randomUUID(), null));
    assertEquals(
        new Exact(BigInteger.ONE, BigInteger.valueOf(3)),
        new Exact(BigInteger.valueOf(2), BigInteger.valueOf(6)));
    assertThrows(IllegalArgumentException.class, () -> new Exact(BigInteger.ONE, BigInteger.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Exact(BigInteger.ONE.shiftLeft(4096), BigInteger.ONE));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Result(
                Status.READY,
                "READY",
                Coverage.COMPLETE,
                identity,
                1,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Instant.EPOCH,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
  }
}
