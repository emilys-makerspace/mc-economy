package ca.emilybelnavis.mcmods.economy.fx;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.api.Money;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DenominationChangeTest {
  @Test
  void confirmationBindsActorRevisionAndSessionAndPreservesHistory() {
    var world = new WorldId(UUID.randomUUID());
    var changes = new DenominationChange(world, UUID.randomUUID());
    var admin = new PlayerId(UUID.randomUUID());
    var other = new PlayerId(UUID.randomUUID());
    var history =
        new HistoricalMoney(
            new Money(CAD, 100),
            new HistoricalMoney.FrozenDisplay(
                new Money(JPY, 110), LocalDate.of(2026, 11, 3), UUID.randomUUID()));
    var first = changes.first(admin, EUR, 1, true, 0);
    var concurrent = changes.first(other, JPY, 1, true, 0);
    assertThrows(IllegalArgumentException.class, () -> changes.second(first, other, 1, true, 1));
    assertThrows(IllegalArgumentException.class, () -> changes.second(first, admin, 1, false, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> changes.second(first, admin, 1, true, Duration.ofMinutes(5).toNanos()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DenominationChange(world, UUID.randomUUID()).second(first, admin, 1, true, 1));
    assertEquals(EUR, changes.second(first, admin, 1, true, 1).target());
    assertThrows(
        IllegalArgumentException.class, () -> changes.second(concurrent, other, 2, true, 1));
    changes.revoke(other);
    assertThrows(
        IllegalArgumentException.class, () -> changes.second(concurrent, other, 1, true, 1));
    assertEquals(
        "1.00 CAD (frozen display 110 JPY, rate day 2026-11-03)", history.format(Locale.CANADA));
  }
}
