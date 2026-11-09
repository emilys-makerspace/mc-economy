package ca.emilybelnavis.mcmods.economy.api;

import java.util.Objects;

/** Read-only funding check for an authorized account in the caller's consistent ledger snapshot. */
@FunctionalInterface
public interface FundingEligibility {
  enum Status {
    ACCOUNT_UNAVAILABLE,
    STALE,
    CURRENCY_INELIGIBLE,
    LEDGER_UNAVAILABLE,
    INSUFFICIENT_FUNDS,
    ELIGIBLE
  }

  /**
   * Check the latest account identity against a positive expected revision and positive amount. A
   * missing account is represented by null. An eligible result does not reserve funds.
   */
  Status evaluate(AccountIdentity account, long expectedRevision, Money amount);

  /** A3 metadata checks. A4 supplies the ledger and B2 supplies trading currency policy. */
  static FundingEligibility withoutLedger() {
    return (account, expectedRevision, amount) -> {
      Objects.requireNonNull(amount, "amount");
      if (expectedRevision < 1 || amount.minorUnits() <= 0) {
        throw new IllegalArgumentException("Funding requires a positive revision and amount");
      }
      if (account == null || account.status() != AccountIdentity.Status.OPEN) {
        return Status.ACCOUNT_UNAVAILABLE;
      }
      if (account.revision() != expectedRevision) {
        return Status.STALE;
      }
      if (account.purpose() == AccountIdentity.Purpose.BANK
          && !account.eligibleCurrency(amount.currency())) {
        return Status.CURRENCY_INELIGIBLE;
      }
      return Status.LEDGER_UNAVAILABLE;
    };
  }
}
