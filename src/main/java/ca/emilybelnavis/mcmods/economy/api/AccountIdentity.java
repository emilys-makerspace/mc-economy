package ca.emilybelnavis.mcmods.economy.api;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import java.util.Objects;

/** Immutable catalog metadata. A trading identity has no currency policy until B2. */
public record AccountIdentity(
    AccountId id,
    PartyRef owner,
    Purpose purpose,
    EconomyCurrency currency,
    Status status,
    long revision,
    boolean primary) {
  public static final int MAX_OPEN_BANK_ACCOUNTS = 4;

  public enum Purpose {
    BANK,
    TRADING
  }

  public enum Status {
    OPEN,
    CLOSED
  }

  public AccountIdentity {
    Objects.requireNonNull(id);
    Objects.requireNonNull(owner);
    Objects.requireNonNull(purpose);
    Objects.requireNonNull(status);
    if (!(owner.owner() instanceof PartyRef.Player)
        || revision < 1
        || (purpose == Purpose.BANK) != (currency != null)
        || primary && (purpose != Purpose.BANK || status != Status.OPEN)
        || purpose == Purpose.TRADING && status != Status.OPEN) {
      throw new IllegalArgumentException("Invalid account identity");
    }
  }

  /** Product metadata only. Lifecycle and access rules are checked separately. */
  public boolean eligibleCurrency(EconomyCurrency candidate) {
    return candidate != null && currency == candidate;
  }
}
