package ca.emilybelnavis.mcmods.economy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Value types only. These conventions do not create financial entities. */
public final class Identifiers {
  private Identifiers() {}

  public record WorldId(UUID value) {
    public WorldId {
      Objects.requireNonNull(value);
    }
  }

  public sealed interface ActorId permits PlayerId, OrganizationId, SystemActorId {}

  public record PlayerId(UUID value) implements ActorId {
    public PlayerId {
      Objects.requireNonNull(value);
    }
  }

  public record OrganizationId(UUID value) implements ActorId {
    public OrganizationId {
      Objects.requireNonNull(value);
    }
  }

  public record SystemActorId(UUID value) implements ActorId {
    public SystemActorId {
      Objects.requireNonNull(value);
    }
  }

  public record AccountId(UUID value) {
    public AccountId {
      Objects.requireNonNull(value);
    }
  }

  public record TransactionId(UUID value) {
    public TransactionId {
      Objects.requireNonNull(value);
    }
  }

  public record OperationId(UUID value) {
    public OperationId {
      Objects.requireNonNull(value);
    }
  }

  public record RequestId(UUID value) {
    public RequestId {
      Objects.requireNonNull(value);
    }
  }

  public record PaymentId(UUID value) {
    public PaymentId {
      Objects.requireNonNull(value);
    }
  }

  public record LoanId(UUID value) {
    public LoanId {
      Objects.requireNonNull(value);
    }
  }

  public record OrderId(UUID value) {
    public OrderId {
      Objects.requireNonNull(value);
    }
  }

  public record CurrencyCode(String value) {
    public CurrencyCode {
      if (value == null || !value.matches("[A-Z][A-Z0-9]{2,11}"))
        throw new IllegalArgumentException("Currency code");
    }
  }

  public enum CatalogKind {
    COMPANY,
    SECURITY,
    EXCHANGE,
    LISTING
  }

  /** Stable namespaced key; never derived from a mutable display name. */
  public record CatalogId(CatalogKind kind, String value) {
    public CatalogId {
      Objects.requireNonNull(kind);
      if (value == null || value.length() > 128 || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
        throw new IllegalArgumentException("Catalog key");
    }
  }

  /** Assigned by the database in A1-03; independent of clocks and economic ordering. */
  public record JournalSequence(long value) {
    public JournalSequence {
      if (value < 1) throw new IllegalArgumentException("Sequence");
    }
  }

  public record EconomicSequence(long value) {
    public EconomicSequence {
      if (value < 0) throw new IllegalArgumentException("Sequence");
    }
  }

  public record AuditInstant(Instant value) {
    public AuditInstant {
      Objects.requireNonNull(value);
    }
  }
}
