package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import java.util.*;

/** Newest-first owner projection. Legacy LedgerHistory and its v1 cursor remain unchanged. */
public final class OwnerHistory {
  private OwnerHistory() {}

  public enum Type {
    ALL,
    TRANSFER,
    FX,
    CASH,
    PAYMENT,
    CREDIT,
    LOAN,
    COLLECTION,
    OTHER
  }

  public enum Availability {
    AVAILABLE,
    INDEX_CATCHING_UP,
    WORK_LIMIT,
    DENIED
  }

  public record Query(
      UUID account,
      EconomyCurrency currency,
      Long fromInclusive,
      Long toExclusive,
      Type type,
      int limit) {
    public Query {
      Objects.requireNonNull(type);
      if (limit < 1
          || limit > 16
          || fromInclusive != null && fromInclusive < 0
          || toExclusive != null && toExclusive < 0
          || fromInclusive != null && toExclusive != null && fromInclusive >= toExclusive)
        throw new IllegalArgumentException("Owner history query bounds");
    }

    public static Query all() {
      return new Query(null, null, null, null, Type.ALL, 8);
    }
  }

  public record Key(long sequence, int position) {
    public Key {
      if (sequence < 1 || position < 0 || position > 63)
        throw new IllegalArgumentException("History key");
    }
  }

  public record Movement(
      UUID transaction,
      UUID operation,
      UUID account,
      long sequence,
      int position,
      long recordedAt,
      Money amount,
      long denominationRevision,
      int postingVersion,
      String service,
      String operationType,
      boolean internalMovement) {
    public Movement {
      Objects.requireNonNull(transaction);
      Objects.requireNonNull(operation);
      Objects.requireNonNull(account);
      Objects.requireNonNull(amount);
      Objects.requireNonNull(service);
      Objects.requireNonNull(operationType);
      new Key(sequence, position);
      if (recordedAt < 0
          || denominationRevision < 0
          || postingVersion < 1
          || postingVersion > 4
          || service.length() > 96
          || operationType.length() > 96)
        throw new IllegalArgumentException("History movement bounds");
    }

    public Key key() {
      return new Key(sequence, position);
    }
  }

  public record Page(
      Availability availability, String reason, long highWater, List<Movement> rows, Key next) {
    public Page {
      Objects.requireNonNull(availability);
      Objects.requireNonNull(reason);
      rows = List.copyOf(rows);
      if (highWater < 0
          || rows.size() > 16
          || reason.length() > 128
          || availability != Availability.AVAILABLE && (!rows.isEmpty() || next != null))
        throw new IllegalArgumentException("History page bounds");
    }
  }

  /** Immutable continuation, bounded to page+lookahead and sixteen 256-candidate worker reads. */
  public record Scan(
      Query query,
      long highWater,
      Key start,
      Key cursor,
      List<Movement> rows,
      int examined,
      boolean complete,
      Availability availability) {
    public Scan {
      Objects.requireNonNull(query);
      Objects.requireNonNull(availability);
      rows = List.copyOf(rows);
      if (highWater < 0 || rows.size() > query.limit() + 1 || examined < 0 || examined > 4096)
        throw new IllegalArgumentException("History scan bounds");
    }
  }

  public static Scan begin(Query query, long highWater, Key before) {
    if (highWater < 0 || before != null && before.sequence() > highWater)
      throw new IllegalArgumentException("History high water");
    return new Scan(query, highWater, before, before, List.of(), 0, false, Availability.AVAILABLE);
  }

  public static long highWater(JournalEngine.ReadView reads) {
    return Long.parseLong(
        reads
            .query("SELECT COALESCE(MAX(sequence),0) AS n FROM journal_transactions", List.of(), 1)
            .getFirst()
            .get("n"));
  }

  public static Scan scan(JournalEngine.ReadView reads, PartyRef owner, Scan state) {
    if (state.complete()) return state;
    if (!(owner.owner() instanceof PartyRef.Player player)
        || !owner
            .world()
            .value()
            .toString()
            .equals(
                reads
                    .query(
                        "SELECT world_id FROM foundation_metadata WHERE singleton=1", List.of(), 1)
                    .getFirst()
                    .get("world_id"))) return failed(state, Availability.DENIED);
    if (state.query().account() != null
        && AccountCatalog.find(reads, owner, new AccountId(state.query().account())).isEmpty())
      return failed(state, Availability.DENIED);
    var metadata =
        reads
            .query("SELECT frontier,ready FROM owner_history_state WHERE singleton=1", List.of(), 1)
            .getFirst();
    if (!metadata.get("ready").equals("1")
        || Long.parseLong(metadata.get("frontier")) < state.highWater())
      return failed(state, Availability.INDEX_CATCHING_UP);
    var q = state.query();
    var params =
        new ArrayList<String>(
            List.of(owner.world().value().toString(), player.id().value().toString()));
    String sql =
        "SELECT h.* FROM owner_history h INDEXED BY "
            + index(q)
            + " JOIN account_catalog a ON a.account_id=h.account_id AND a.owner_id=h.owner_id WHERE h.world_id=? AND h.owner_id=?";
    if (q.account() != null) {
      sql += " AND h.account_id=?";
      params.add(q.account().toString());
    }
    if (q.currency() != null) {
      sql += " AND h.currency=?";
      params.add(q.currency().code());
    }
    if (q.type() != Type.ALL) {
      sql += " AND h.category=?";
      params.add(q.type().name());
    }
    sql += " AND h.sequence<=?";
    params.add(Long.toString(state.highWater()));
    if (state.cursor() != null) {
      sql += " AND (h.sequence,h.position)<(?,?)";
      params.add(Long.toString(state.cursor().sequence()));
      params.add(Integer.toString(state.cursor().position()));
    }
    sql += " ORDER BY h.sequence DESC,h.position DESC LIMIT 256";
    var candidates = reads.query(sql, params, 256);
    var rows = new ArrayList<>(state.rows());
    Key cursor = state.cursor();
    int examined = state.examined();
    for (var candidate : candidates) {
      if (examined == 4096) return failed(state, Availability.WORK_LIMIT);
      examined++;
      var movement = movement(candidate);
      cursor = movement.key();
      if ((q.fromInclusive() == null || movement.recordedAt() >= q.fromInclusive())
          && (q.toExclusive() == null || movement.recordedAt() < q.toExclusive()))
        rows.add(movement);
      if (rows.size() > q.limit())
        return new Scan(
            q,
            state.highWater(),
            state.start(),
            cursor,
            rows,
            examined,
            true,
            Availability.AVAILABLE);
    }
    boolean complete = candidates.size() < 256;
    if (!complete && examined == 4096)
      return new Scan(
          q,
          state.highWater(),
          state.start(),
          state.start(),
          List.of(),
          examined,
          true,
          Availability.WORK_LIMIT);
    return new Scan(
        q,
        state.highWater(),
        state.start(),
        cursor,
        rows,
        examined,
        complete,
        Availability.AVAILABLE);
  }

  private static Scan failed(Scan state, Availability reason) {
    return new Scan(
        state.query(),
        state.highWater(),
        state.start(),
        state.start(),
        List.of(),
        state.examined(),
        true,
        reason);
  }

  public static Page finish(Scan state) {
    if (!state.complete()) throw new IllegalArgumentException("History scan is incomplete");
    var rows = state.rows().stream().limit(state.query().limit()).toList();
    Key next = state.rows().size() > state.query().limit() ? rows.getLast().key() : null;
    return new Page(
        state.availability(),
        "history." + state.availability().name().toLowerCase(Locale.ROOT),
        state.highWater(),
        rows,
        next);
  }

  /**
   * Standalone convenience; production facades resubmit scan() through owner worker admission each
   * turn.
   */
  public static Page page(
      JournalEngine.ReadView reads, PartyRef owner, Query query, long highWater, Key before) {
    var state = begin(query, highWater, before);
    while (!state.complete()) state = scan(reads, owner, state);
    return finish(state);
  }

  public static Optional<Movement> movement(
      JournalEngine.ReadView reads, PartyRef owner, Key key, long highWater) {
    if (!(owner.owner() instanceof PartyRef.Player player)
        || key.sequence() > highWater
        || !owner
            .world()
            .value()
            .toString()
            .equals(
                reads
                    .query(
                        "SELECT world_id FROM foundation_metadata WHERE singleton=1", List.of(), 1)
                    .getFirst()
                    .get("world_id"))) return Optional.empty();
    var meta =
        reads
            .query("SELECT frontier,ready FROM owner_history_state WHERE singleton=1", List.of(), 1)
            .getFirst();
    if (!meta.get("ready").equals("1") || Long.parseLong(meta.get("frontier")) < highWater)
      return Optional.empty();
    var rows =
        reads.query(
            "SELECT h.* FROM owner_history h JOIN account_catalog a ON a.account_id=h.account_id AND a.owner_id=h.owner_id WHERE h.world_id=? AND h.owner_id=? AND h.sequence=? AND h.position=?",
            List.of(
                owner.world().value().toString(),
                player.id().value().toString(),
                Long.toString(key.sequence()),
                Integer.toString(key.position())),
            1);
    return rows.isEmpty() ? Optional.empty() : Optional.of(movement(rows.getFirst()));
  }

  public static String index(Query q) {
    return "owner_history_"
        + (q.account() == null ? "owner" : "account")
        + (q.currency() == null ? "" : "_currency")
        + (q.type() == Type.ALL ? "" : "_type");
  }

  public static Type classify(String service, String operation) {
    if (service.equals("economy:ledger") && operation.equals("economy:exchange")) return Type.FX;
    if (service.equals("economy:ledger") && operation.equals("economy:transfer"))
      return Type.TRANSFER;
    if (service.equals("economy:ledger") && operation.equals("economy:pay")) return Type.PAYMENT;
    if (service.contains("deposit") || service.contains("withdraw")) return Type.CASH;
    if (service.equals("economy:credit")) return Type.CREDIT;
    if (service.equals("economy:loans")) return Type.LOAN;
    if (service.equals("economy:debt")) return Type.COLLECTION;
    if (service.contains("payment") || service.contains("register") || service.contains("npc"))
      return Type.PAYMENT;
    return Type.OTHER;
  }

  private static Movement movement(Map<String, String> row) {
    return new Movement(
        UUID.fromString(row.get("transaction_id")),
        UUID.fromString(row.get("operation_id")),
        UUID.fromString(row.get("account_id")),
        Long.parseLong(row.get("sequence")),
        Integer.parseInt(row.get("position")),
        Long.parseLong(row.get("recorded_ms")),
        new Money(EconomyCurrency.fromCode(row.get("currency")), Long.parseLong(row.get("minor"))),
        Long.parseLong(row.get("denomination_revision")),
        Integer.parseInt(row.get("posting_version")),
        row.get("service"),
        row.get("operation_type"),
        row.get("internal_movement").equals("1"));
  }
}
