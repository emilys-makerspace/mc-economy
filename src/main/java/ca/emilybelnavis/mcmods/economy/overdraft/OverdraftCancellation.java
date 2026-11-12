package ca.emilybelnavis.mcmods.economy.overdraft;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Owner cancellation after recovery; repayment and the full positive-day obligation remain intact.
 */
public final class OverdraftCancellation {
  public static final Journal.Kind CANCEL = new Journal.Kind("economy:overdraft", "economy:cancel");

  private OverdraftCancellation() {}

  public static Journal.CanonicalRequest request(
      WorldId world, PlayerId owner, AccountId account, long protectionRevision) {
    if (protectionRevision < 1) throw new IllegalArgumentException("Overdraft revision");
    return new Journal.CanonicalRequest(
        1,
        Map.of(
            "world",
            world.value().toString(),
            "economy:owner",
            Journal.actorKey(owner),
            "account",
            account.value().toString(),
            "protection_revision",
            Long.toString(protectionRevision)));
  }

  public static JournalEngine.Registration registration(
      PartyRef owner, AccountId account, long protectionRevision, LongSupplier clock) {
    Objects.requireNonNull(clock);
    if (!(owner.owner() instanceof PartyRef.Player player))
      throw new IllegalArgumentException("Overdraft owner");
    var canonical = request(owner.world(), player.id(), account, protectionRevision);
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          if (!CANCEL.equals(intent.kind())
              || !canonical.equals(intent.request())
              || !intent.actor().equals(player.id())) return reject("OVERDRAFT_ACCESS");
          var identity = AccountCatalog.find(reads, owner, account).orElse(null);
          var state = Overdrafts.find(reads, account.value()).orElse(null);
          if (identity == null
              || identity.purpose() != AccountIdentity.Purpose.BANK
              || identity.status() != AccountIdentity.Status.OPEN
              || state == null
              || state.closed()
              || !state.world().equals(owner.world())
              || !state.owner().equals(player.id())
              || !state.protectedAccount()) return reject("OVERDRAFT_ACCOUNT");
          if (state.revision() != protectionRevision) return reject("OVERDRAFT_ACCOUNT_STALE");
          long now = clock.getAsLong();
          if (now >= state.nextDay()) return reject("OVERDRAFT_CATCHUP_REQUIRED");
          if (Settlement.posted(reads, account.value(), identity.currency()) < 0)
            return reject("OVERDRAFT_RECOVERY_REQUIRED");
          if (state.episodeAt() >= 0
              && (state.positiveAt() < 0
                  || now < Math.addExact(state.positiveAt(), Overdrafts.DAY)))
            return reject("OVERDRAFT_RECOVERY_REQUIRED");
          if (state.accruedMinor().add(state.residual()).compareTo(new BigDecimal("0.5")) >= 0)
            return reject("OVERDRAFT_CATCHUP_REQUIRED");
          var cancelled =
              new Overdrafts.Protection(
                  state.world(),
                  state.owner(),
                  state.account(),
                  state.currency(),
                  state.bank(),
                  0,
                  BigDecimal.ZERO,
                  -1,
                  state.nextDay(),
                  Long.MAX_VALUE,
                  -1,
                  -1,
                  BigDecimal.ZERO,
                  Math.incrementExact(state.revision()),
                  false);
          // Any subminor residual is explicitly waived by the committed transition to zero
          // residual.
          return Overdrafts.stateCommit(
              cancelled, new Journal.Result(1, "OVERDRAFT_CANCELLED", account.value().toString()));
        });
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }
}
