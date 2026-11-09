package ca.emilybelnavis.mcmods.economy.simulation.macro;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomicSimulationHistory;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.simulation.SimulationHistory;
import ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimulationHistoryTest {
  @TempDir Path path;

  @Test
  void savedSelectionsTraverseWithoutRepeatsAndReadsRetainExactValuesWithoutAdvancing()
      throws Exception {
    try (var f = new MacroProviderTest.Fixture(path, MacroControls.Mode.PAUSED)) {
      f.finish(f.f);
      long secondAt = f.f + CurrencyPrimes.DAY_MILLIS;
      var deltas = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
      var multipliers = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
      for (var currency : EconomyCurrency.all()) {
        deltas.put(currency, BigDecimal.ZERO);
        multipliers.put(currency, BigDecimal.ONE);
      }
      deltas.put(EconomyCurrency.CAD, new BigDecimal("0.0005"));
      var manual =
          new MacroControls.Policy(
              UUID.randomUUID(),
              f.world(),
              2,
              secondAt,
              MacroControls.Mode.MANUAL,
              f.config,
              deltas,
              multipliers);
      MacroControls.schedule(f.store, manual, f.wall.instant());
      f.finish(secondAt);
      long thirdAt = secondAt + CurrencyPrimes.DAY_MILLIS;
      f.now = thirdAt;
      assertTrue(f.clock.runBatch(16).ready());
      // Open + eleven currency windows + selection; publication children have not run.
      for (int child = 0; child < 13; child++) f.dispatcher.advance();
      assertNotNull(f.outcome(thirdAt));
      assertNull(
          f.store.transaction(
              tx -> MacroPlans.completion(tx::query, MacroProvider.windowId(f.world(), thirdAt))));
      assertFalse(f.dispatcher.progress().ready());
      var expected = List.of(f.outcome(thirdAt), f.outcome(secondAt), f.outcome(f.f));
      long sequence = f.store.lastCommittedSequence(), now = f.now;
      var progress = f.dispatcher.progress();

      var first = SimulationHistory.page(f.store, Long.MAX_VALUE, 2);
      assertEquals(2, first.entries().size());
      assertEquals(first.entries().getLast().financialDay(), first.nextBeforeDay().orElseThrow());
      var second = SimulationHistory.page(f.store, first.nextBeforeDay().orElseThrow(), 2);
      assertEquals(1, second.entries().size());
      assertTrue(second.nextBeforeDay().isEmpty());
      var all = new ArrayList<>(first.entries());
      all.addAll(second.entries());
      assertEquals(3, all.stream().map(EconomicSimulationHistory.Entry::window).distinct().count());
      for (int index = 0; index < all.size(); index++) {
        var actual = all.get(index);
        var saved = expected.get(index);
        long at = thirdAt - index * CurrencyPrimes.DAY_MILLIS;
        assertEquals(1, actual.recordVersion());
        assertEquals(at, actual.effectiveMillis());
        assertEquals((at - f.origin) / CurrencyPrimes.DAY_MILLIS - 1, actual.financialDay());
        assertEquals(saved.window(), actual.window());
        assertEquals(saved.world(), actual.world());
        assertEquals(saved.policyId(), actual.policyId());
        assertEquals(saved.policyRevision(), actual.policyRevision());
        assertEquals(saved.mode().name(), actual.mode().name());
        assertEquals(saved.inputHash(), actual.inputHash());
        assertEquals(saved.automaticHash(), actual.automaticHash());
        assertEquals(saved.hash(), actual.hash());
        assertEquals(saved.primes(), actual.primes());
        assertEquals(saved.fx(), actual.fx());
        assertEquals(saved.explanations(), actual.explanations());
        assertEquals(saved.alpha().numerator(), actual.alpha().numerator());
        assertEquals(saved.alpha().denominator(), actual.alpha().denominator());
      }
      assertEquals(
          List.of(
              EconomicSimulationHistory.Mode.PAUSED,
              EconomicSimulationHistory.Mode.MANUAL,
              EconomicSimulationHistory.Mode.PAUSED),
          all.stream().map(EconomicSimulationHistory.Entry::mode).toList());
      assertTrue(SimulationHistory.page(f.store, 0, 16).entries().isEmpty());
      assertTrue(SimulationHistory.page(f.store, 0, 16).nextBeforeDay().isEmpty());
      assertThrows(IllegalArgumentException.class, () -> SimulationHistory.page(f.store, -1, 2));
      assertThrows(
          IllegalArgumentException.class,
          () -> SimulationHistory.page(f.store, Long.MAX_VALUE, 17));
      assertThrows(UnsupportedOperationException.class, () -> first.entries().clear());
      assertThrows(UnsupportedOperationException.class, () -> all.getFirst().primes().clear());
      assertThrows(UnsupportedOperationException.class, () -> all.getFirst().fx().clear());
      assertThrows(
          UnsupportedOperationException.class, () -> all.getFirst().explanations().clear());
      var mutableEntries = new ArrayList<>(all);
      var detached = new EconomicSimulationHistory.Page(mutableEntries, OptionalLong.empty());
      mutableEntries.clear();
      assertEquals(3, detached.entries().size());
      var original = all.getFirst();
      var mutablePrimes = new EnumMap<EconomyCurrency, BigDecimal>(original.primes());
      var mutableFx = new EnumMap<EconomyCurrency, BigDecimal>(original.fx());
      var mutableExplanations = new ArrayList<>(original.explanations());
      var detachedEntry =
          new EconomicSimulationHistory.Entry(
              original.recordVersion(),
              original.financialDay(),
              original.effectiveMillis(),
              original.window(),
              original.world(),
              original.policyId(),
              original.policyRevision(),
              original.mode(),
              original.inputHash(),
              original.automaticHash(),
              mutablePrimes,
              mutableFx,
              original.alpha(),
              mutableExplanations,
              original.hash());
      mutablePrimes.clear();
      mutableFx.clear();
      mutableExplanations.clear();
      assertEquals(
          original, detachedEntry, "DTO retains independent copies of captured collections");
      assertEquals(
          sequence, f.store.lastCommittedSequence(), "History reads create no journal effects");
      assertEquals(now, f.now, "History reads never observe or advance financial time");
      assertEquals(
          progress, f.dispatcher.progress(), "Read-only paging leaves dispatcher state unchanged");
    }
  }
}
