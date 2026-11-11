package ca.emilybelnavis.mcmods.economy.collection;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CollectionPlacementTest {
  @Test
  void deterministicCandidatesKeepApprovedRingVerticalAndWorkBounds() {
    var world = UUID.fromString("887535ce-c65f-4fca-8b25-d78fc2181d0b");
    var owner = UUID.fromString("30652b8b-3764-4e82-b212-ed210ea935ee");
    var debt = UUID.fromString("205d5a77-6c12-43f2-a882-a604ad848031");
    assertEquals(16, CollectionPlacement.CANDIDATES);
    assertEquals(4, CollectionPlacement.GLOBAL_BUDGET);
    assertEquals(200, CollectionPlacement.RETRY_TICKS);
    boolean changed = false;
    for (int candidate = 0; candidate < 16; candidate++) {
      var first = CollectionPlacement.candidate(world, owner, debt, 0, candidate);
      assertEquals(first, CollectionPlacement.candidate(world, owner, debt, 0, candidate));
      int radius = first.x() * first.x() + first.z() * first.z();
      assertTrue(radius >= 64 && radius <= 144);
      assertTrue(Math.abs(first.y()) <= 3);
      changed |= !first.equals(CollectionPlacement.candidate(world, owner, debt, 1, candidate));
    }
    assertTrue(changed);
    assertThrows(
        IllegalArgumentException.class,
        () -> CollectionPlacement.candidate(world, owner, debt, 0, 16));
    assertThrows(
        IllegalArgumentException.class,
        () -> CollectionPlacement.candidate(world, owner, debt, -1, 0));
  }
}
