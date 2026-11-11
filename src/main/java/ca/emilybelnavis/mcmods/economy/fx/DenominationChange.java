package ca.emilybelnavis.mcmods.economy.fx;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import java.time.Duration;
import java.util.UUID;

/** Session-scoped confirmation protocol. A4 owns durable revision and account effects. */
public final class DenominationChange {
  private static final long WINDOW_NANOS = Duration.ofMinutes(5).toNanos();
  private final WorldId world;
  private final UUID session;
  private final java.util.Map<PlayerId, Confirmation> pending = new java.util.HashMap<>();

  public DenominationChange(WorldId world, UUID session) {
    this.world = java.util.Objects.requireNonNull(world);
    this.session = java.util.Objects.requireNonNull(session);
  }

  public record Confirmation(
      UUID id,
      WorldId world,
      UUID session,
      PlayerId actor,
      EconomyCurrency target,
      long revision,
      long startedNanos) {}

  /** Proof of successful second confirmation, issued only by this protocol. */
  public static final class Confirmed {
    private final Confirmation intent;

    private Confirmed(Confirmation intent) {
      this.intent = intent;
    }

    public Confirmation intent() {
      return intent;
    }
  }

  public Confirmation first(
      PlayerId actor, EconomyCurrency target, long revision, boolean authorized, long now) {
    if (!authorized || revision < 0 || target == null || actor == null) {
      throw new IllegalArgumentException("Invalid denomination confirmation");
    }
    pending.values().removeIf(value -> value.revision() != revision || expired(value, now));
    if (pending.size() >= 128 && !pending.containsKey(actor))
      throw new IllegalStateException("Confirmation overload");
    var value = new Confirmation(UUID.randomUUID(), world, session, actor, target, revision, now);
    pending.put(actor, value);
    return value;
  }

  /** Invoke at authoritative admission with live permission and the current committed revision. */
  public Confirmation second(
      Confirmation candidate, PlayerId actor, long currentRevision, boolean authorized, long now) {
    return confirm(candidate, actor, currentRevision, authorized, now).intent();
  }

  /** Issues a detached proof for bounded planning. Recheck live authority before each use. */
  public Confirmed confirm(
      Confirmation candidate, PlayerId actor, long currentRevision, boolean authorized, long now) {
    var value = pending.get(actor);
    if (!authorized
        || value == null
        || !value.equals(candidate)
        || value.revision() != currentRevision
        || expired(value, now)) {
      throw new IllegalArgumentException("Denomination confirmation unavailable");
    }
    pending.remove(actor);
    return new Confirmed(value);
  }

  /** Disconnect or loss of authorization revokes the pending confirmation. */
  public void revoke(PlayerId actor) {
    pending.remove(actor);
  }

  private static boolean expired(Confirmation value, long now) {
    long elapsed = now - value.startedNanos();
    return elapsed < 0 || elapsed >= WINDOW_NANOS;
  }
}
