package ca.emilybelnavis.mcmods.economy.account;

import ca.emilybelnavis.mcmods.economy.api.AccountIdentity;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity.Purpose;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity.Status;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.journal.RebaseSettlement;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Worker-side catalog. Use the active world and an owner authorized for the caller and operation.
 */
public final class AccountCatalog {
  public static final Journal.Kind INITIALIZE_PRIMARY =
      new Journal.Kind("economy:accounts", "economy:initialize-primary");
  public static final Journal.Kind INITIALIZE_TRADING =
      new Journal.Kind("economy:accounts", "economy:initialize-trading");
  public static final int MAX_PAGE_SIZE = 64;
  private static final String FACT = "economy:account-initialization";
  private static final String OWNED =
      "SELECT account_id,purpose,currency,status,revision,is_primary "
          + "FROM account_catalog WHERE owner_id=?";

  private AccountCatalog() {}

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog.with(new RecordCatalog.Key(FACT, 1), AccountCatalog::validateFact);
  }

  /**
   * Read only. A4 can require an initialized recipient using this in its settlement transaction.
   */
  public static Optional<AccountIdentity> primary(JournalEngine.ReadView reads, PartyRef owner) {
    return one(reads, owner, " AND is_primary=1", List.of(ownerId(owner)));
  }

  public static Optional<AccountIdentity> trading(JournalEngine.ReadView reads, PartyRef owner) {
    return one(reads, owner, " AND purpose='TRADING'", List.of(ownerId(owner)));
  }

  public static Optional<AccountIdentity> find(
      JournalEngine.ReadView reads, PartyRef owner, AccountId id) {
    return one(reads, owner, " AND account_id=?", List.of(ownerId(owner), id.value().toString()));
  }

  /**
   * Worker-only keyset page, including retained identities. A3-04 owns the public query contract.
   */
  public static List<AccountIdentity> history(
      JournalEngine.ReadView reads, PartyRef owner, AccountId after, int limit) {
    if (limit < 1 || limit > MAX_PAGE_SIZE) throw new IllegalArgumentException("Account page size");
    String cursor = after == null ? "" : after.value().toString();
    return reads
        .query(
            OWNED + " AND account_id>? ORDER BY account_id LIMIT ?",
            List.of(ownerId(owner), cursor, Integer.toString(limit)),
            limit)
        .stream()
        .map(row -> decode(owner, row))
        .toList();
  }

  public static List<AccountIdentity> openBanks(JournalEngine.ReadView reads, PartyRef owner) {
    return reads
        .query(
            OWNED + " AND purpose='BANK' AND status='OPEN'",
            List.of(ownerId(owner)),
            AccountIdentity.MAX_OPEN_BANK_ACCOUNTS)
        .stream()
        .map(row -> decode(owner, row))
        .toList();
  }

  static EconomyCurrency currentDenomination(JournalEngine.ReadView reads) {
    return RebaseSettlement.denomination(reads)
        .map(AccountRebasePlanner.Denomination::currency)
        .orElse(null);
  }

  private static Optional<AccountIdentity> one(
      JournalEngine.ReadView reads, PartyRef owner, String filter, List<String> parameters) {
    return reads.query(OWNED + filter, parameters, 1).stream()
        .findFirst()
        .map(row -> decode(owner, row));
  }

  public static JournalEngine.Registration initialization(PartyRef owner, Purpose purpose) {
    Objects.requireNonNull(purpose);
    if (!(owner.owner() instanceof PartyRef.Player playerOwner))
      throw new IllegalArgumentException("Player owner required");
    String player = ownerId(owner);
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          if (!playerOwner.id().equals(intent.actor())
              || !intent.request().fields().equals(Map.of("economy:owner", "player:" + player))) {
            throw new IllegalArgumentException("Mismatched trusted account initialization");
          }
          Optional<AccountIdentity> existing =
              purpose == Purpose.BANK ? primary(reads, owner) : trading(reads, owner);
          if (existing.isPresent()) return resolved(existing.get(), false, tx -> {});

          if (purpose == Purpose.BANK)
            return new JournalEngine.Reject(new Journal.Result(1, "BRANCH_REQUIRED", ""));
          EconomyCurrency currency = null;
          var account =
              new AccountIdentity(
                  new AccountId(UUID.randomUUID()),
                  owner,
                  purpose,
                  currency,
                  Status.OPEN,
                  1,
                  purpose == Purpose.BANK);
          return resolved(account, true, tx -> insert(tx, account));
        });
  }

  static void insert(FoundationStore.Transaction tx, AccountIdentity account) {
    tx.execute(
        """
                INSERT INTO account_catalog(account_id,owner_id,purpose,currency,status,revision,is_primary)
                VALUES(?,?,?,NULLIF(?,''),'OPEN',1,?)
                """,
        List.of(
            account.id().value().toString(),
            ownerId(account.owner()),
            account.purpose().name(),
            currencyCode(account),
            account.primary() ? "1" : "0"));
  }

  private static JournalEngine.Commit resolved(
      AccountIdentity account, boolean created, JournalEngine.Projection projection) {
    String id = account.id().value().toString();
    return new JournalEngine.Commit(
        List.of(new Journal.Record(FACT, 1, fact(account, created))),
        new Journal.Result(1, "ACCOUNT_READY", id),
        projection);
  }

  private static String fact(AccountIdentity account, boolean created) {
    return String.join(
        "|",
        created ? "created" : "resolved",
        account.id().value().toString(),
        ownerId(account.owner()),
        account.purpose().name(),
        currencyCode(account));
  }

  private static void validateFact(String payload) {
    String[] parts = payload.split("\\|", -1);
    if (parts.length != 5 || !(parts[0].equals("created") || parts[0].equals("resolved")))
      throw new IllegalArgumentException("Account initialization fact");
    requireCanonicalUuid(parts[1]);
    requireCanonicalUuid(parts[2]);
    var purpose = Purpose.valueOf(parts[3]);
    if (purpose == Purpose.BANK) EconomyCurrency.fromCode(parts[4]);
    else if (!parts[4].isEmpty())
      throw new IllegalArgumentException("Trading initialization currency");
  }

  static void requireCanonicalUuid(String value) {
    if (!UUID.fromString(value).toString().equals(value))
      throw new IllegalArgumentException("Noncanonical account initialization UUID");
  }

  private static AccountIdentity decode(PartyRef owner, Map<String, String> row) {
    String currency = row.get("currency");
    return new AccountIdentity(
        new AccountId(UUID.fromString(row.get("account_id"))),
        owner,
        Purpose.valueOf(row.get("purpose")),
        currency == null ? null : EconomyCurrency.fromCode(currency),
        Status.valueOf(row.get("status")),
        Long.parseLong(row.get("revision")),
        "1".equals(row.get("is_primary")));
  }

  static String ownerId(PartyRef owner) {
    if (!(owner.owner() instanceof PartyRef.Player player))
      throw new IllegalArgumentException("Player owner required");
    return player.id().value().toString();
  }

  private static String currencyCode(AccountIdentity account) {
    return account.currency() == null ? "" : account.currency().code();
  }
}
