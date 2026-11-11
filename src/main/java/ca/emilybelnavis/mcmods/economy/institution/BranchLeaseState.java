package ca.emilybelnavis.mcmods.economy.institution;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy;
import java.util.UUID;

/** Detached live observations cross to the worker only as a one-way revocable flag. */
public final class BranchLeaseState {
  public record Observation(
      PlayerId actor,
      UUID session,
      String dimension,
      TerminalPolicy.Anchor anchor,
      UUID generation,
      long revision,
      boolean connected,
      boolean alive,
      boolean currentMenu,
      boolean completeGeometry,
      double distanceSquared,
      boolean permitted) {}

  private final Observation expected;
  private volatile boolean live = true;

  public BranchLeaseState(Observation expected) {
    this.expected = expected;
    update(expected);
  }

  /** Once revoked, returning to the old position/session cannot revive a submitted capability. */
  public void update(Observation current) {
    if (!current.actor().equals(expected.actor())
        || !current.session().equals(expected.session())
        || !current.dimension().equals(expected.dimension())
        || !current.anchor().equals(expected.anchor())
        || !current.generation().equals(expected.generation())
        || current.revision() != expected.revision()
        || !current.connected()
        || !current.alive()
        || !current.currentMenu()
        || !current.completeGeometry()
        || !current.permitted()
        || !Double.isFinite(current.distanceSquared())
        || current.distanceSquared() > 36
        || current.distanceSquared() < 0) live = false;
  }

  public void revoke() {
    live = false;
  }

  public boolean live() {
    return live;
  }
}
