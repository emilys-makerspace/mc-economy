package ca.emilybelnavis.mcmods.economy.api;

import java.util.Objects;
import java.util.UUID;

/** Created by trusted server handlers, never decoded as client-supplied identity or permission. */
public record RequestContext(UUID actorId, UUID serverSessionId, UUID correlationId) {
  public static final int MAX_PAYLOAD_BYTES = 16_384;

  public RequestContext {
    Objects.requireNonNull(actorId);
    Objects.requireNonNull(serverSessionId);
    Objects.requireNonNull(correlationId);
  }

  /** Transport checks sizes; domain services will own permissions and financial validation. */
  public static void validatePayloadSize(int bytes) {
    if (bytes < 0 || bytes > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Payload size");
  }
}
