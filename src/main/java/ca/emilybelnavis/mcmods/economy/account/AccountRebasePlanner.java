package ca.emilybelnavis.mcmods.economy.account;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.fx.DenominationChange;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read-only worker policy for A4's future atomic rebase transaction. */
public final class AccountRebasePlanner {
  public static final int MAX_PRIMARIES = 256;
  private static final String ACCOUNTS =
      """
            SELECT p.owner_id,p.account_id,p.currency,p.revision,
                   c.account_id AS collision_id,c.currency AS collision_currency,c.revision AS collision_revision
            FROM account_catalog AS p INDEXED BY account_primary
            LEFT JOIN account_catalog AS c INDEXED BY account_open_bank_currency
              ON c.owner_id=p.owner_id AND c.currency=? AND c.purpose='BANK' AND c.status='OPEN'
            WHERE p.is_primary=1
            ORDER BY p.owner_id LIMIT ?
            """;

  private AccountRebasePlanner() {}

  public enum Status {
    READY,
    UNAUTHORIZED,
    DENOMINATION_UNAVAILABLE,
    STALE_DENOMINATION,
    SAME_CURRENCY,
    LEDGER_UNAVAILABLE,
    ENCUMBERED,
    PLAN_TOO_LARGE,
    CATALOG_MISMATCH
  }

  public record Denomination(EconomyCurrency currency, long revision) {
    public Denomination {
      Objects.requireNonNull(currency);
      if (revision < 0) throw new IllegalArgumentException("Denomination revision");
    }
  }

  /**
   * Read the authoritative denomination and its revision from the same snapshot as the account
   * rows.
   */
  @FunctionalInterface
  public interface DenominationReader {
    Optional<Denomination> read(JournalEngine.ReadView reads);
  }

  public record Claims(boolean activeHold, boolean restricted, boolean unresolvedObligation) {
    boolean blocked() {
      return activeHold || restricted || unresolvedObligation;
    }
  }

  /** Empty means the authoritative ledger preconditions cannot be established. */
  @FunctionalInterface
  public interface ClaimsReader {
    Optional<Claims> read(JournalEngine.ReadView reads, AccountId account);
  }

  public record AccountVersion(AccountId id, long revision, EconomyCurrency currency) {}

  /** Retire the optional collision with the primary ID as its audit successor. */
  public record Mapping(PlayerId owner, AccountVersion primary, AccountVersion collision) {}

  public record Plan(
      DenominationChange.Confirmation intent, Denomination current, List<Mapping> mappings) {
    public Plan {
      mappings = List.copyOf(mappings);
    }
  }

  public record Result(Status status, Plan plan) {
    public Result {
      Objects.requireNonNull(status);
      if ((status == Status.READY) != (plan != null))
        throw new IllegalArgumentException("Rebase result");
    }
  }

  /**
   * Call with live server-authorized actor and a single consistent read snapshot. Before applying
   * effects A4 must rerun this inside the write transaction and compare the complete plan.
   */
  public static Result plan(
      JournalEngine.ReadView reads,
      WorldId world,
      UUID session,
      PlayerId actor,
      DenominationChange.Confirmed confirmed,
      Denomination current,
      ClaimsReader claims) {
    var intent = confirmed.intent();
    if (!intent.world().equals(world)
        || !intent.session().equals(session)
        || !intent.actor().equals(actor)) return unavailable(Status.UNAUTHORIZED);
    if (current == null) return unavailable(Status.DENOMINATION_UNAVAILABLE);
    if (intent.revision() != current.revision()) return unavailable(Status.STALE_DENOMINATION);
    if (intent.target() == current.currency()) return unavailable(Status.SAME_CURRENCY);

    var rows =
        reads.query(
            ACCOUNTS,
            List.of(intent.target().code(), Integer.toString(MAX_PRIMARIES + 1)),
            MAX_PRIMARIES + 1);
    if (rows.size() > MAX_PRIMARIES) return unavailable(Status.PLAN_TOO_LARGE);

    var mappings = new ArrayList<Mapping>(rows.size());
    for (var row : rows) {
      var primary = account(row, "account_id", "revision", "currency");
      if (primary.currency() != current.currency() || primary.revision() == Long.MAX_VALUE)
        return unavailable(Status.CATALOG_MISMATCH);

      var status = checkClaims(reads, claims, primary.id());
      if (status != Status.READY) return unavailable(status);

      AccountVersion collision = null;
      if (row.get("collision_id") != null) {
        collision = account(row, "collision_id", "collision_revision", "collision_currency");
        if (collision.revision() == Long.MAX_VALUE) return unavailable(Status.CATALOG_MISMATCH);
        status = checkClaims(reads, claims, collision.id());
        if (status != Status.READY) return unavailable(status);
      }

      mappings.add(
          new Mapping(new PlayerId(UUID.fromString(row.get("owner_id"))), primary, collision));
    }
    return new Result(Status.READY, new Plan(intent, current, mappings));
  }

  private static Status checkClaims(
      JournalEngine.ReadView reads, ClaimsReader reader, AccountId id) {
    var claims = reader.read(reads, id).orElse(null);
    if (claims == null) return Status.LEDGER_UNAVAILABLE;
    return claims.blocked() ? Status.ENCUMBERED : Status.READY;
  }

  private static AccountVersion account(
      Map<String, String> row, String id, String revision, String currency) {
    return new AccountVersion(
        new AccountId(UUID.fromString(row.get(id))),
        Long.parseLong(row.get(revision)),
        EconomyCurrency.fromCode(row.get(currency)));
  }

  private static Result unavailable(Status status) {
    return new Result(status, null);
  }
}
