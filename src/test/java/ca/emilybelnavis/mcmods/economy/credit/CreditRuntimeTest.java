package ca.emilybelnavis.mcmods.economy.credit;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class CreditRuntimeTest {
  @Test
  void emptyDomainIsDormantAndApplyingEventSurvivesItsFinalTarget() {
    for (var provider : CreditRuntime.providers()) {
      EconomicClock.Queries empty = (sql, args, max) -> List.of();
      assertNull(provider.next(empty, 0, null));
      var event = UUID.randomUUID();
      EconomicClock.Queries applying =
          (sql, args, max) ->
              sql.contains("financial_deadline_events")
                  ? List.of(
                      Map.of(
                          "event_id",
                          event.toString(),
                          "effective_ms",
                          "33600000",
                          "stage",
                          "1",
                          "version",
                          "1"))
                  : List.of();
      var next = provider.next(applying, 0, null);
      assertEquals(event, next.id());
      assertEquals(33600000, next.effectiveMillis());
      assertTrue(provider.targets(next, "", 16, applying).isEmpty());
    }
  }

  @Test
  void exactAnchorDeadlineAndStageOrderHaveStableWorldBoundIdentity() {
    var world = UUID.randomUUID();
    EconomicClock.Queries reads =
        (sql, args, max) ->
            sql.contains("foundation_metadata")
                ? List.of(Map.of("world_id", world.toString()))
                : sql.contains("credit_facilities")
                    ? List.of(Map.of("due", "33600017"))
                    : List.of();
    var providers = CreditRuntime.providers();
    var day = providers.get(0).next(reads, 0, null);
    var statement = providers.get(1).next(reads, 0, null);
    assertEquals(33600017, day.effectiveMillis());
    assertEquals(day.effectiveMillis(), statement.effectiveMillis());
    assertEquals(1, day.stage());
    assertEquals(2, statement.stage());
    assertNotEquals(day.id(), statement.id());
    assertEquals(day, providers.get(0).next(reads, 0, null));
    assertThrows(IllegalStateException.class, () -> providers.get(0).next(reads, 0, day));
  }

  @Test
  void creditProceedsHaveAnAuthoritativeRequiredLiabilitySource() {
    var source = new CreditLiabilities();
    var world = new WorldId(UUID.randomUUID());
    var owner = new PlayerId(UUID.randomUUID());
    var snapshot =
        source.read(
            (sql, args, max) -> {
              if (sql.contains("FROM ledger_balances"))
                return args.get(1).equals("CAD")
                    ? List.of(Map.of("minor_units", "-12307"))
                    : List.of();
              if (!sql.contains("SUM(debt_minor)")) return List.of(Map.of("sequence", "0"));
              assertEquals(11, max);
              assertEquals(List.of(world.value().toString(), owner.value().toString()), args);
              return List.of(Map.of("currency", "CAD", "debt", "12307", "rev", "9"));
            },
            world,
            owner,
            Instant.EPOCH);
    assertEquals(
        ca.emilybelnavis.mcmods.economy.pricing.WealthValuation.Kind.LIABILITY, source.kind());
    assertEquals("123.07", snapshot.components().getFirst().major().toPlainString());
    assertEquals(9, snapshot.revision());
    assertTrue(
        ca.emilybelnavis.mcmods.economy.pricing.PricingRuntime.wealth().sourceManifest().stream()
            .anyMatch(c -> c.id().equals(source.id())));
  }
}
