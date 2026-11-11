package ca.emilybelnavis.mcmods.economy.account;

import ca.emilybelnavis.mcmods.economy.api.AccountIdentity;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity.Purpose;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity.Status;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.institution.BankProviders;
import ca.emilybelnavis.mcmods.economy.institution.BranchAdmission;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;

/** Trusted worker registrations. A5 owns player-facing teller admission. */
public final class AccountLifecycle {
  public static final Journal.Kind OPEN = new Journal.Kind("economy:accounts", "economy:open-bank");
  public static final Journal.Kind CLOSE =
      new Journal.Kind("economy:accounts", "economy:close-bank");
  private static final String FACT = "economy:bank-lifecycle";

  private AccountLifecycle() {}

  public record Open(EconomyCurrency currency, long expectedPrimaryRevision) {
    public Open {
      Objects.requireNonNull(currency);
      requireRevision(expectedPrimaryRevision);
    }

    public Journal.CanonicalRequest request() {
      return new Journal.CanonicalRequest(
          1,
          Map.of(
              "currency",
              currency.code(),
              "primary_revision",
              Long.toString(expectedPrimaryRevision)));
    }
  }

  public record Close(
      AccountId source,
      long expectedRevision,
      AccountId destination,
      Long expectedDestinationRevision) {
    public Close {
      Objects.requireNonNull(source);
      requireRevision(expectedRevision);
      if ((destination == null) != (expectedDestinationRevision == null))
        throw new IllegalArgumentException("Destination and revision must be supplied together");
      if (expectedDestinationRevision != null) requireRevision(expectedDestinationRevision);
    }

    public Journal.CanonicalRequest request() {
      var fields = new HashMap<String, String>();
      fields.put("source", source.value().toString());
      fields.put("source_revision", Long.toString(expectedRevision));
      if (destination != null) {
        fields.put("destination", destination.value().toString());
        fields.put("destination_revision", expectedDestinationRevision.toString());
      }
      return new Journal.CanonicalRequest(1, fields);
    }
  }

  /**
   * Read the authoritative ledger and claims in the same worker snapshot. Empty means unavailable.
   */
  @FunctionalInterface
  public interface ClosureReader {
    Optional<ClosureState> read(JournalEngine.ReadView reads, AccountIdentity source);
  }

  public record ClosureState(
      long postedMinorUnits, boolean activeHold, boolean restricted, boolean unresolvedObligation) {
    public ClosureState {
      if (postedMinorUnits < 0) throw new IllegalArgumentException("Negative BANK balance");
    }
  }

  public enum Eligibility {
    EMPTY,
    FUNDED,
    ACCOUNT_UNAVAILABLE,
    ACCOUNT_STALE,
    ACCOUNT_PROTECTED,
    REVISION_EXHAUSTED,
    DENOMINATION_PENDING,
    CLOSURE_STATE_UNAVAILABLE,
    ACTIVE_HOLD,
    RESTRICTED_FUNDS,
    UNRESOLVED_OBLIGATION,
    DESTINATION_UNAVAILABLE,
    DESTINATION_STALE
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog.with(new RecordCatalog.Key(FACT, 1), AccountLifecycle::validateFact);
  }

  public static JournalEngine.Registration opening(PartyRef owner, Open open) {
    return owned(owner, open.request(), (intent, reads) -> reject("BRANCH_REQUIRED"));
  }

  public static JournalEngine.Registration closing(
      PartyRef owner, Close close, ClosureReader reader) {
    return closing(
        owner,
        close,
        reader,
        close.request(),
        (intent, reads) -> true,
        (intent, reads) -> reject("FUNDED_SETTLEMENT_UNAVAILABLE"));
  }

  /** Compose an authorized settlement with the original account closure transition. */
  public static JournalEngine.Registration closing(
      PartyRef owner,
      Close close,
      ClosureReader reader,
      Journal.CanonicalRequest request,
      BiPredicate<Journal.Intent, JournalEngine.ReadView> authority,
      JournalEngine.Handler settleFunded) {
    return owned(owner, request, (intent, reads) -> reject("BRANCH_REQUIRED"));
  }

  /**
   * Sealed branch capability plus original A3/A4 closure protections, consumed atomically with
   * closure.
   */
  public static JournalEngine.Registration closingAtBranch(
      PartyRef owner,
      Close close,
      ClosureReader reader,
      BranchAdmission.Proof proof,
      BiPredicate<Journal.Intent, JournalEngine.ReadView> authority,
      JournalEngine.Handler settleFunded) {
    return closingAtBranch(owner, close, reader, proof, close.request(), authority, settleFunded);
  }

  public static JournalEngine.Registration closingAtBranch(
      PartyRef owner,
      Close close,
      ClosureReader reader,
      BranchAdmission.Proof proof,
      Journal.CanonicalRequest settlementRequest,
      BiPredicate<Journal.Intent, JournalEngine.ReadView> authority,
      JournalEngine.Handler settleFunded) {
    if (settlementRequest.version() != 1
        || !settlementRequest.fields().entrySet().containsAll(close.request().fields().entrySet()))
      throw new IllegalArgumentException("Closure settlement binding");
    Objects.requireNonNull(proof);
    var context = proof.context();
    if (context.action() != BranchAdmission.Action.CLOSE
        || !close.source().equals(context.account())
        || close.expectedRevision() != context.accountRevision())
      throw new IllegalArgumentException("Closure capability target mismatch");
    var request = new BankProviders.Request(proof, 0, settlementRequest.fields());
    return BankProviders.closure(
        owner,
        request,
        closingProtected(owner, close, reader, request.request(), authority, settleFunded));
  }

  public static JournalEngine.Registration closingAtBranch(
      PartyRef owner, Close close, ClosureReader reader, BranchAdmission.Proof proof) {
    return closingAtBranch(
        owner,
        close,
        reader,
        proof,
        (intent, reads) -> true,
        (intent, reads) -> reject("FUNDED_SETTLEMENT_UNAVAILABLE"));
  }

  private static JournalEngine.Registration closingProtected(
      PartyRef owner,
      Close close,
      ClosureReader reader,
      Journal.CanonicalRequest request,
      BiPredicate<Journal.Intent, JournalEngine.ReadView> authority,
      JournalEngine.Handler settleFunded) {
    Objects.requireNonNull(reader);
    return owned(
        owner,
        request,
        (intent, reads) -> {
          if (!authority.test(intent, reads)) return reject("AUTHORITY_REVOKED");
          var source = AccountCatalog.find(reads, owner, close.source()).orElse(null);
          var eligibility = eligibility(reads, owner, close, reader, source);
          JournalEngine.Commit settlement = null;
          if (eligibility == Eligibility.FUNDED) {
            var decision = settleFunded.prepare(intent, reads);
            if (!(decision instanceof JournalEngine.Commit postings)) return decision;
            settlement = postings;
          } else if (eligibility != Eligibility.EMPTY) {
            return reject(eligibility.name());
          }

          long revision = source.revision() + 1;
          var closure =
              commit(
                  "closed",
                  source,
                  revision,
                  "BANK_CLOSED",
                  tx -> {
                    int changed =
                        tx.execute(
                            """
                        UPDATE account_catalog SET status='CLOSED',revision=?
                        WHERE account_id=? AND owner_id=? AND status='OPEN' AND revision=?
                        """,
                            List.of(
                                Long.toString(revision),
                                source.id().value().toString(),
                                AccountCatalog.ownerId(owner),
                                Long.toString(close.expectedRevision())));
                    if (changed != 1)
                      throw new IllegalStateException("Authorized closure target changed");
                  });
          if (settlement == null) return closure;
          var postings = settlement;
          var records = new ArrayList<>(postings.records());
          records.addAll(closure.records());
          return new JournalEngine.Commit(
              records,
              closure.result(),
              tx -> {
                postings.projections().apply(tx);
                closure.projections().apply(tx);
              });
        });
  }

  /**
   * A4 rechecks this contract with authoritative inputs in the transaction that would settle
   * closure.
   */
  public static Eligibility eligibility(
      JournalEngine.ReadView reads, PartyRef owner, Close close, ClosureReader reader) {
    return eligibility(
        reads,
        owner,
        close,
        reader,
        AccountCatalog.find(reads, owner, close.source()).orElse(null));
  }

  private static Eligibility eligibility(
      JournalEngine.ReadView reads,
      PartyRef owner,
      Close close,
      ClosureReader reader,
      AccountIdentity source) {
    if (!openBank(source)) return Eligibility.ACCOUNT_UNAVAILABLE;
    if (source.revision() != close.expectedRevision()) return Eligibility.ACCOUNT_STALE;
    if (source.primary()) return Eligibility.ACCOUNT_PROTECTED;
    var denomination = AccountCatalog.currentDenomination(reads);
    if (denomination == null) return Eligibility.DENOMINATION_PENDING;
    if (source.currency() == denomination) return Eligibility.ACCOUNT_PROTECTED;
    if (source.revision() == Long.MAX_VALUE) return Eligibility.REVISION_EXHAUSTED;

    var state = reader.read(reads, source).orElse(null);
    if (state == null) return Eligibility.CLOSURE_STATE_UNAVAILABLE;
    if (state.activeHold()) return Eligibility.ACTIVE_HOLD;
    if (state.restricted()) return Eligibility.RESTRICTED_FUNDS;
    if (state.unresolvedObligation()) return Eligibility.UNRESOLVED_OBLIGATION;

    if (close.destination() == null)
      return state.postedMinorUnits() == 0
          ? Eligibility.EMPTY
          : Eligibility.DESTINATION_UNAVAILABLE;
    if (close.source().equals(close.destination())) return Eligibility.DESTINATION_UNAVAILABLE;
    var destination = AccountCatalog.find(reads, owner, close.destination()).orElse(null);
    if (!openBank(destination)) return Eligibility.DESTINATION_UNAVAILABLE;
    if (destination.revision() != close.expectedDestinationRevision())
      return Eligibility.DESTINATION_STALE;
    return state.postedMinorUnits() == 0 ? Eligibility.EMPTY : Eligibility.FUNDED;
  }

  private static boolean openBank(AccountIdentity account) {
    return account != null && account.purpose() == Purpose.BANK && account.status() == Status.OPEN;
  }

  private static JournalEngine.Registration owned(
      PartyRef owner, Journal.CanonicalRequest request, JournalEngine.Handler handler) {
    if (!(owner.owner() instanceof PartyRef.Player player))
      throw new IllegalArgumentException("Player owner required");
    var fields = new HashMap<>(request.fields());
    fields.put("economy:owner", Journal.actorKey(player.id()));
    return new JournalEngine.Registration(
        request.version(),
        (intent, reads) -> {
          if (!player.id().equals(intent.actor()) || !fields.equals(intent.request().fields()))
            throw new IllegalArgumentException("Mismatched trusted bank lifecycle request");
          return handler.prepare(intent, reads);
        });
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }

  private static JournalEngine.Commit commit(
      String action,
      AccountIdentity account,
      long revision,
      String code,
      JournalEngine.Projection projection) {
    String id = account.id().value().toString();
    String payload =
        String.join(
            "|",
            action,
            id,
            AccountCatalog.ownerId(account.owner()),
            account.currency().code(),
            Long.toString(revision));
    return new JournalEngine.Commit(
        List.of(new Journal.Record(FACT, 1, payload)), new Journal.Result(1, code, id), projection);
  }

  private static void validateFact(String payload) {
    String[] parts = payload.split("\\|", -1);
    if (parts.length != 5) throw new IllegalArgumentException("Bank lifecycle fact");
    AccountCatalog.requireCanonicalUuid(parts[1]);
    AccountCatalog.requireCanonicalUuid(parts[2]);
    EconomyCurrency.fromCode(parts[3]);
    long revision = Long.parseLong(parts[4]);
    boolean validRevision =
        switch (parts[0]) {
          case "opened" -> revision == 1;
          case "closed" -> revision >= 2;
          default -> false;
        };
    if (!validRevision || !Long.toString(revision).equals(parts[4]))
      throw new IllegalArgumentException("Bank lifecycle action or revision");
  }

  private static void requireRevision(long revision) {
    if (revision < 1) throw new IllegalArgumentException("Account revision");
  }
}
