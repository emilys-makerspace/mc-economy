package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.account.AccountLifecycle;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;

/** Worker-side account closure using a fresh exact-debit quote for the full balance. */
public final class FundedClosure {
  private FundedClosure() {}

  public record Instruction(AccountLifecycle.Close close, UUID quoteId) {
    public Instruction {
      Objects.requireNonNull(close);
    }

    public Journal.CanonicalRequest request() {
      var fields = new HashMap<>(close.request().fields());
      if (quoteId != null) fields.put("quote", quoteId.toString());
      return new Journal.CanonicalRequest(1, fields);
    }
  }

  @FunctionalInterface
  public interface ClaimsReader {
    /** Supplies restrictions and unresolved obligations. Empty means unavailable. */
    Optional<Claims> read(JournalEngine.ReadView reads, AccountIdentity source);
  }

  public record Claims(boolean restricted, boolean unresolvedObligation) {}

  /**
   * Trusted branch adapter entry. Proof and all quote/destination fields are bound to one lease.
   */
  public static JournalEngine.Registration registrationAtBranch(
      PartyRef owner,
      Instruction instruction,
      FxSettlement.QuoteReader quotes,
      ClaimsReader claims,
      ca.emilybelnavis.mcmods.economy.institution.BranchAdmission.Proof proof,
      BiPredicate<Journal.Intent, JournalEngine.ReadView> authority) {
    var player = (PartyRef.Player) owner.owner();
    var close = instruction.close();
    return AccountLifecycle.closingAtBranch(
        owner,
        close,
        (reads, source) ->
            claims
                .read(reads, source)
                .map(
                    state ->
                        new AccountLifecycle.ClosureState(
                            Settlement.posted(reads, source.id().value(), source.currency()),
                            Holds.hasActiveClaim(reads, source.id().value()),
                            state.restricted(),
                            state.unresolvedObligation())),
        proof,
        instruction.request(),
        authority,
        (intent, reads) -> {
          if (instruction.quoteId() == null)
            return new JournalEngine.Reject(new Journal.Result(1, "FX_QUOTE_REQUIRED", ""));
          var source = AccountCatalog.find(reads, owner, close.source()).orElseThrow();
          var destination = AccountCatalog.find(reads, owner, close.destination()).orElseThrow();
          return FxSettlement.prepare(
              reads,
              quotes,
              instruction.quoteId(),
              player.id(),
              owner.world(),
              source,
              destination,
              true);
        });
  }

  /** Bind with ServerServices.submitOwned using the account closure kind. */
  public static JournalEngine.Registration registration(
      PartyRef owner,
      Instruction instruction,
      FxSettlement.QuoteReader quotes,
      ClaimsReader claims,
      BiPredicate<Journal.Intent, JournalEngine.ReadView> authority) {
    if (!(owner.owner() instanceof PartyRef.Player player))
      throw new IllegalArgumentException("Player owner required");
    var close = instruction.close();
    return AccountLifecycle.closing(
        owner,
        close,
        (reads, source) ->
            claims
                .read(reads, source)
                .map(
                    state ->
                        new AccountLifecycle.ClosureState(
                            Settlement.posted(reads, source.id().value(), source.currency()),
                            Holds.hasActiveClaim(reads, source.id().value()),
                            state.restricted(),
                            state.unresolvedObligation())),
        instruction.request(),
        authority,
        (intent, reads) -> {
          if (instruction.quoteId() == null) {
            return new JournalEngine.Reject(new Journal.Result(1, "FX_QUOTE_REQUIRED", ""));
          }
          var source = AccountCatalog.find(reads, owner, close.source()).orElseThrow();
          var destination = AccountCatalog.find(reads, owner, close.destination()).orElseThrow();
          return FxSettlement.prepare(
              reads,
              quotes,
              instruction.quoteId(),
              player.id(),
              owner.world(),
              source,
              destination,
              true);
        });
  }
}
