package ca.emilybelnavis.mcmods.economy.api;

import java.util.Objects;
import java.util.UUID;

/** No success here implies a financial commit; durable outcomes arrive in A1-03. */
public record FoundationResponse(UUID correlationId, Status status) {
  public enum Status {
    READY,
    NOT_READY,
    UNAVAILABLE,
    OVERLOADED,
    STOPPED,
    INVALID
  }

  public FoundationResponse {
    Objects.requireNonNull(correlationId);
    Objects.requireNonNull(status);
  }
}
