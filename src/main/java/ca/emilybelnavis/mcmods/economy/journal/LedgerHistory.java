package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.JournalSequence;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.TransactionId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable, owner-scoped posting history. Invoke on the storage worker after authorizing the
 * owner.
 */
public final class LedgerHistory {
  public static final int MAX_PAGE_SIZE = 63;
  static final String QUERY =
      """
            SELECT r.sequence,r.position,r.payload,t.transaction_id,(
                SELECT d.payload FROM journal_records d INDEXED BY ledger_rebase_latest
                WHERE d.record_type='economy:rebase' AND d.record_version=1 AND d.sequence<=r.sequence
                ORDER BY d.sequence DESC LIMIT 1
            ) AS denomination
            FROM journal_records r
            JOIN journal_transactions t ON t.sequence=r.sequence
            WHERE r.record_type='economy:posting' AND r.record_version IN (1,2,3,4) AND substr(r.payload,1,36)=?
              AND (r.sequence,r.position)>(?,?) AND t.world_id=?
            ORDER BY r.sequence,r.position LIMIT ?
            """;

  private LedgerHistory() {}

  public record Entry(
      TransactionId transactionId,
      JournalSequence sequence,
      int position,
      Money amount,
      long denominationRevision) {}

  public record Page(List<Entry> entries, String nextCursor) {
    public Page {
      entries = List.copyOf(entries);
    }
  }

  /**
   * Pages in ascending journal sequence and record position. A cursor resumes exclusively after its
   * last entry. Appends can appear in subsequent pages. All entries in a rebase transaction use its
   * resulting denomination revision.
   */
  public static Optional<Page> page(
      JournalEngine.ReadView reads, PartyRef owner, AccountId account, String token, int limit) {
    if (limit < 1 || limit > MAX_PAGE_SIZE)
      throw new IllegalArgumentException("Ledger history page size");
    var cursor = token == null ? new Cursor(owner.world(), account, 0, -1) : Cursor.decode(token);
    if (!cursor.world().equals(owner.world())
        || !cursor.account().equals(account)
        || AccountCatalog.find(reads, owner, account).isEmpty()) return Optional.empty();
    var rows =
        reads.query(
            QUERY,
            List.of(
                account.value().toString(),
                Long.toString(cursor.sequence()),
                Integer.toString(cursor.position()),
                owner.world().value().toString(),
                Integer.toString(limit + 1)),
            limit + 1);
    var entries = rows.stream().limit(limit).map(LedgerHistory::entry).toList();
    String next = null;
    if (rows.size() > limit) {
      var last = entries.getLast();
      next = new Cursor(owner.world(), account, last.sequence().value(), last.position()).encode();
    }
    return Optional.of(new Page(entries, next));
  }

  private static Entry entry(Map<String, String> row) {
    var fields = row.get("payload").split("\\|", -1);
    var denomination = row.get("denomination");
    long revision = denomination == null ? 0 : Long.parseLong(denomination.split("\\|", -1)[1]);
    return new Entry(
        new TransactionId(UUID.fromString(row.get("transaction_id"))),
        new JournalSequence(Long.parseLong(row.get("sequence"))),
        Integer.parseInt(row.get("position")),
        new Money(EconomyCurrency.valueOf(fields[2]), Long.parseLong(fields[3])),
        revision);
  }

  private record Cursor(WorldId world, AccountId account, long sequence, int position) {
    private String encode() {
      return "v1:" + world.value() + ":" + account.value() + ":" + sequence + ":" + position;
    }

    private static Cursor decode(String token) {
      if (token.length() > 100) throw new IllegalArgumentException("Malformed ledger cursor");
      var fields = token.split(":", -1);
      if (fields.length != 5) throw new IllegalArgumentException("Malformed ledger cursor");
      var cursor =
          new Cursor(
              new WorldId(UUID.fromString(fields[1])),
              new AccountId(UUID.fromString(fields[2])),
              Long.parseLong(fields[3]),
              Integer.parseInt(fields[4]));
      if (cursor.sequence() < 1
          || cursor.position() < 0
          || cursor.position() > 63
          || !cursor.encode().equals(token))
        throw new IllegalArgumentException("Malformed ledger cursor");
      return cursor;
    }
  }
}
