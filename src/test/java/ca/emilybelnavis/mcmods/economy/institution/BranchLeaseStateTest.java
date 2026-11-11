package ca.emilybelnavis.mcmods.economy.institution;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy;
import java.util.*;
import org.junit.jupiter.api.Test;

class BranchLeaseStateTest {
  private final PlayerId actor = new PlayerId(UUID.randomUUID());
  private final UUID session = UUID.randomUUID(), generation = UUID.randomUUID();
  private final TerminalPolicy.Anchor anchor =
      new TerminalPolicy.Anchor("minecraft:overworld", 4, 70, 9);

  private BranchLeaseState.Observation observation(
      String dimension,
      UUID gen,
      long revision,
      boolean connected,
      boolean menu,
      boolean geometry,
      double distance,
      boolean permitted) {
    return new BranchLeaseState.Observation(
        actor, session, dimension, anchor, gen, revision, connected, true, menu, geometry, distance,
        permitted);
  }

  @Test
  void staleServerObservationsRevokePermanentlyBeforeWorkerAdmission() {
    var expected = observation(anchor.dimension(), generation, 3, true, true, true, 36, true);
    var changes =
        List.of(
            observation(anchor.dimension(), generation, 3, true, true, true, 36.0001, true),
            observation("minecraft:the_nether", generation, 3, true, true, true, 1, true),
            observation(anchor.dimension(), generation, 3, false, true, true, 1, true),
            observation(anchor.dimension(), generation, 3, true, false, true, 1, true),
            observation(anchor.dimension(), generation, 3, true, true, false, 1, true),
            observation(anchor.dimension(), UUID.randomUUID(), 3, true, true, true, 1, true),
            observation(anchor.dimension(), generation, 4, true, true, true, 1, true),
            observation(anchor.dimension(), generation, 3, true, true, true, 1, false),
            observation(anchor.dimension(), generation, 3, true, true, true, Double.NaN, true));
    for (var changed : changes) {
      var lease = new BranchLeaseState(expected);
      assertTrue(lease.live());
      lease.update(changed);
      assertFalse(lease.live());
      lease.update(expected);
      assertFalse(lease.live(), "stale callback must not revive a lease");
    }
  }

  @Test
  void explicitMenuCloseRevokesAndSixBlockBoundaryRemainsValid() {
    var lease =
        new BranchLeaseState(
            observation(anchor.dimension(), generation, 3, true, true, true, 36, true));
    assertTrue(lease.live());
    lease.revoke();
    assertFalse(lease.live());
  }
}
