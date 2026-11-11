package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.trade.custody.NpcDeliveries;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Trusted worker-side quantity authority. A vanilla stack split retains the same lot marker, so
 * redemption conserves a lot's aggregate quantity rather than claiming to distinguish physically
 * identical copied stacks.
 */
public final class CashIssuance {
  public static final String FACT = "economy:cash_issuance";
  public static final int MAX_STACKS = 64;
  public static final int MAX_ISSUED = 4096;
  private static final SystemActorId RECOVERY =
      new SystemActorId(UUID.fromString("f28dcb59-cc47-4f5c-8eb7-4ce3fb7c3d75"));
  // Protected payouts stay active, so advance past them between bounded startup batches.
  private static final Map<FoundationStore, String> RECONCILIATION_CURSORS =
      Collections.synchronizedMap(new WeakHashMap<>());

  public enum State {
    ACTIVE,
    QUARANTINED
  }

  public enum Action {
    ISSUE,
    REDEEM,
    RECONCILE,
    QUARANTINE;

    public Kind kind() {
      return new Kind("economy:cash_issuance", "economy:" + name().toLowerCase(Locale.ROOT));
    }
  }

  public record Lot(
      WorldId world,
      UUID id,
      String item,
      int issued,
      int remaining,
      State state,
      long revision,
      String evidence) {
    public Lot {
      Objects.requireNonNull(world);
      Objects.requireNonNull(id);
      Objects.requireNonNull(state);
      itemId(item);
      text(evidence, 512);
      if (issued < 1 || issued > MAX_ISSUED || remaining < 0 || remaining > issued || revision < 1)
        throw new IllegalArgumentException("Cash lot quantities");
    }
  }

  /** One observed physical stack. Its world and item identity must match the persisted lot. */
  public record Claim(WorldId world, UUID lot, String item, int count) {
    public Claim {
      Objects.requireNonNull(world);
      Objects.requireNonNull(lot);
      itemId(item);
      if (count < 1 || count > 64) throw new IllegalArgumentException("Cash stack quantity");
    }
  }

  /** Live integration grants and exact catalog identity validation belong to trusted policy. */
  @FunctionalInterface
  public interface Authority {
    boolean allows(Action action, Intent intent, List<Lot> lots, ReadView reads);
  }

  /**
   * Verify original custody/reservation or conclusive reconciliation evidence before accepting
   * cash.
   */
  @FunctionalInterface
  public interface EvidenceVerifier {
    boolean verifies(Action action, Intent intent, List<Lot> lots, String evidence, ReadView reads);
  }

  /** Financial postings are composed with quantity changes in one journal transaction. */
  @FunctionalInterface
  public interface Financial {
    Decision prepare(Intent intent, List<Lot> lots, ReadView reads) throws Exception;
  }

  private CashIssuance() {}

  public static String financialIntent(Intent original) {
    return original.operationId().value()
        + "\n"
        + actorKey(original.actor())
        + "\n"
        + original.kind().service()
        + "\n"
        + original.kind().operationType()
        + "\n"
        + original.canonical();
  }

  public static Financial financial(Intent original, Registration registration) {
    var frozen = financialIntent(original);
    return (intent, lots, reads) -> {
      if (!intent.operationId().equals(original.operationId())
          || !frozen.equals(intent.request().fields().get("financial")))
        return reject("CASH_FINANCIAL_INTENT");
      return registration.handler().prepare(original, reads);
    };
  }

  public static CanonicalRequest issueRequest(
      String item, int quantity, String evidence, Intent financial) {
    itemId(item);
    text(evidence, 512);
    if (quantity < 1 || quantity > MAX_ISSUED)
      throw new IllegalArgumentException("Cash issuance quantity");
    return new CanonicalRequest(
        1,
        Map.of(
            "item",
            item,
            "quantity",
            Integer.toString(quantity),
            "evidence",
            evidence,
            "financial",
            financialIntent(financial)));
  }

  public static CanonicalRequest redeemRequest(
      List<Claim> claims, String evidence, Intent financial) {
    text(evidence, 512);
    return new CanonicalRequest(
        1,
        Map.of(
            "stacks",
            encodeClaims(claims),
            "evidence",
            evidence,
            "financial",
            financialIntent(financial)));
  }

  public static CanonicalRequest reconcileRequest(UUID lot, String evidence, String reason) {
    text(evidence, 512);
    text(reason, 512);
    return new CanonicalRequest(
        1, Map.of("lot", lot.toString(), "evidence", evidence, "reason", reason));
  }

  public static Registration registration(
      Action action,
      WorldId world,
      Authority authority,
      EvidenceVerifier verifier,
      Financial financial) {
    return registration(action, world, authority, verifier, financial, null);
  }

  /** Trusted deposit composition may consume only its own durable reservation. */
  public static Registration registration(
      Action action,
      WorldId world,
      Authority authority,
      EvidenceVerifier verifier,
      Financial financial,
      UUID consumingDeposit) {
    Objects.requireNonNull(action);
    Objects.requireNonNull(world);
    Objects.requireNonNull(authority);
    Objects.requireNonNull(verifier);
    return new Registration(
        1,
        (intent, reads) ->
            prepare(
                action, world, authority, verifier, financial, consumingDeposit, intent, reads));
  }

  private static Decision prepare(
      Action action,
      WorldId world,
      Authority authority,
      EvidenceVerifier verifier,
      Financial financial,
      UUID consumingDeposit,
      Intent intent,
      ReadView reads)
      throws Exception {
    if (!intent.kind().equals(action.kind())) return reject("CASH_REQUEST");
    var fields = intent.request().fields();
    List<Lot> current;
    List<Lot> changed;
    String evidence;
    try {
      evidence = fields.get("evidence");
      text(evidence, 512);
      if (action == Action.ISSUE) {
        var item = fields.get("item");
        itemId(item);
        int quantity = Integer.parseInt(fields.get("quantity"));
        requireFinancialIdentity(intent);
        var lot =
            new Lot(
                world,
                intent.operationId().value(),
                item,
                quantity,
                quantity,
                State.ACTIVE,
                1,
                evidence);
        if (!fields.keySet().equals(Set.of("item", "quantity", "evidence", "financial"))
            || !Integer.toString(quantity).equals(fields.get("quantity")))
          return reject("CASH_REQUEST");
        current = List.of(lot);
        changed = current;
      } else if (action == Action.REDEEM) {
        requireFinancialIdentity(intent);
        var claims = decodeClaims(fields.get("stacks"));
        if (!fields.keySet().equals(Set.of("stacks", "evidence", "financial"))
            || !encodeClaims(claims).equals(fields.get("stacks"))) return reject("CASH_REQUEST");
        var quantities = new LinkedHashMap<UUID, Integer>();
        var lots = new LinkedHashMap<UUID, Lot>();
        for (var claim : claims) {
          if (!claim.world().equals(world)) return reject("CASH_WORLD");
          var lot = find(reads, world, claim.lot()).orElse(null);
          if (lot == null || !lot.item().equals(claim.item())) return reject("CASH_UNRECOGNIZED");
          if (lot.state() != State.ACTIVE) return decline("CASH_QUARANTINED");
          lots.putIfAbsent(lot.id(), lot);
          quantities.merge(lot.id(), claim.count(), Math::addExact);
        }
        current = List.copyOf(lots.values());
        var updates = new ArrayList<Lot>();
        for (var lot : current) {
          int quantity = quantities.get(lot.id());
          if (quantity > lot.remaining()) return reject("CASH_QUANTITY");
          long reserved = reserved(reads, lot.id(), consumingDeposit);
          if (quantity > lot.remaining() - reserved) return decline("CASH_RESERVED");
          if (consumingDeposit != null) {
            var own =
                reads.query(
                    """
                                SELECT count FROM deposit_cash_reservations
                                WHERE deposit_id=? AND lot_id=? AND state='ACTIVE'
                                """,
                    List.of(consumingDeposit.toString(), lot.id().toString()),
                    1);
            if (own.isEmpty() || Long.parseLong(own.getFirst().get("count")) < quantity)
              return decline("CASH_RESERVATION");
          }
          updates.add(
              new Lot(
                  world,
                  lot.id(),
                  lot.item(),
                  lot.issued(),
                  lot.remaining() - quantity,
                  lot.state(),
                  Math.addExact(lot.revision(), 1),
                  evidence));
        }
        changed = List.copyOf(updates);
      } else {
        var id = UUID.fromString(fields.get("lot"));
        var reason = fields.get("reason");
        if (!reconcileRequest(id, evidence, reason).equals(intent.request()))
          return reject("CASH_REQUEST");
        var lot = find(reads, world, id).orElse(null);
        if (lot == null) return reject("CASH_UNRECOGNIZED");
        if (action == Action.RECONCILE && lot.state() != State.QUARANTINED)
          return reject("CASH_STATE");
        if (action == Action.QUARANTINE && lot.state() != State.ACTIVE) return reject("CASH_STATE");
        current = List.of(lot);
        changed =
            List.of(
                new Lot(
                    world,
                    lot.id(),
                    lot.item(),
                    lot.issued(),
                    lot.remaining(),
                    action == Action.RECONCILE ? State.ACTIVE : State.QUARANTINED,
                    Math.addExact(lot.revision(), 1),
                    evidence));
      }
    } catch (IllegalArgumentException | NullPointerException | ArithmeticException malformed) {
      return reject("CASH_REQUEST");
    }
    if (!authority.allows(action, intent, current, reads)) return decline("CASH_ACCESS");
    if (action != Action.QUARANTINE && !verifier.verifies(action, intent, current, evidence, reads))
      return decline("CASH_EVIDENCE");
    Commit composed = null;
    if (action == Action.ISSUE || action == Action.REDEEM) {
      if (financial == null) return decline("CASH_FINANCIAL_HANDLER");
      var decision = financial.prepare(intent, current, reads);
      if (decision instanceof Reject rejection
          && rejection.result().code().equals("INSUFFICIENT_FUNDS"))
        return new Retry(rejection.result());
      if (!(decision instanceof Commit accepted)) return decision;
      composed = accepted;
    }
    return commit(action, changed, composed);
  }

  /** Trusted multi-denomination issuance shares the debit and delivery transaction. */
  public static Decision composeIssuance(
      WorldId world, List<Lot> lots, Commit financial, ReadView reads) {
    Objects.requireNonNull(financial);
    if (lots.isEmpty() || lots.size() > 24) return reject("CASH_ISSUANCE_COUNT");
    var identities = new HashSet<UUID>();
    for (var lot : lots) {
      if (!lot.world().equals(world)
          || lot.state() != State.ACTIVE
          || lot.remaining() != lot.issued()
          || lot.revision() != 1
          || !identities.add(lot.id())) return reject("CASH_ISSUANCE_REQUEST");
      if (find(reads, world, lot.id()).isPresent()) return reject("CASH_ISSUANCE_EXISTS");
    }
    return commit(Action.ISSUE, List.copyOf(lots), financial);
  }

  /**
   * NPC input consumption: exclude only this witnessed original custody's exact locked quantity.
   */
  public static Decision composeRedemption(
      WorldId world, List<Claim> claims, UUID ownCustody, Commit financial, ReadView reads) {
    Objects.requireNonNull(financial);
    Objects.requireNonNull(ownCustody);
    if (claims.isEmpty() || claims.size() > MAX_STACKS) return reject("CASH_STACK_COUNT");
    var custody = Custody.find(reads, null, ownCustody, true).orElse(null);
    if (custody == null || custody.state() != Custody.State.WITNESSED)
      return reject("CASH_CUSTODY");
    var quantities = new LinkedHashMap<UUID, Integer>();
    var identities = new HashMap<UUID, String>();
    for (var claim : claims) {
      if (!world.equals(claim.world())) return reject("CASH_WORLD");
      String previous = identities.putIfAbsent(claim.lot(), claim.item());
      if (previous != null && !previous.equals(claim.item())) return reject("CASH_UNRECOGNIZED");
      quantities.merge(claim.lot(), claim.count(), Math::addExact);
    }
    var own =
        reads.query(
            "SELECT lot_id,count FROM custody_cash_reservations WHERE custody_id=? AND state='ACTIVE' ORDER BY lot_id LIMIT 25",
            List.of(ownCustody.toString()),
            25);
    if (own.size() != quantities.size() || own.size() > 24) return reject("CASH_RESERVATION");
    var changed = new ArrayList<Lot>();
    for (var row : own) {
      var id = UUID.fromString(row.get("lot_id"));
      int quantity = Integer.parseInt(row.get("count"));
      if (!Objects.equals(quantities.get(id), quantity)) return reject("CASH_RESERVATION");
      var lot = find(reads, world, id).orElse(null);
      if (lot == null || !lot.item().equals(identities.get(id))) return reject("CASH_UNRECOGNIZED");
      if (lot.state() != State.ACTIVE) return decline("CASH_QUARANTINED");
      long other = Math.subtractExact(reserved(reads, id, null), quantity);
      if (quantity > lot.remaining() - other) return reject("CASH_QUANTITY");
      changed.add(
          new Lot(
              world,
              id,
              lot.item(),
              lot.issued(),
              lot.remaining() - quantity,
              lot.state(),
              Math.addExact(lot.revision(), 1),
              "npc-custody:" + ownCustody));
    }
    var decision = commit(Action.REDEEM, changed, financial);
    if (!(decision instanceof Commit accepted)) return decision;
    return new Commit(
        accepted.records(),
        accepted.result(),
        tx -> {
          accepted.projections().apply(tx);
          tx.execute(
              "UPDATE custody_cash_reservations SET state='RELEASED' WHERE custody_id=? AND state='ACTIVE'",
              List.of(ownCustody.toString()));
        });
  }

  /** Confirmed loss compensation retires the protected original quantity atomically. */
  static Decision retireClaim(ReadView reads, UUID custody, Commit compensation) {
    var rows =
        reads.query(
            """
                SELECT lot_id,count FROM custody_cash_reservations WHERE custody_id=? AND state='ACTIVE'
                ORDER BY lot_id LIMIT 64
                """,
            List.of(custody.toString()),
            64);
    if (rows.isEmpty()) return compensation;
    var changes = new ArrayList<Lot>();
    for (var row : rows) {
      var lots =
          reads.query("SELECT * FROM cash_issuance WHERE lot_id=?", List.of(row.get("lot_id")), 1);
      if (lots.isEmpty()) return reject("CASH_CLAIM_QUANTITY");
      var lot = read(lots.getFirst());
      int quantity = Integer.parseInt(row.get("count"));
      if (lot.remaining() < quantity) return reject("CASH_CLAIM_QUANTITY");
      changes.add(
          new Lot(
              lot.world(),
              lot.id(),
              lot.item(),
              lot.issued(),
              lot.remaining() - quantity,
              lot.state(),
              Math.addExact(lot.revision(), 1),
              "compensated-custody:" + custody));
    }
    return commit(Action.REDEEM, changes, compensation);
  }

  public static long reserved(ReadView reads, UUID lot, UUID excludingDeposit) {
    var deposits =
        reads.query(
            """
                SELECT COALESCE(SUM(count),0) AS total FROM deposit_cash_reservations
                WHERE lot_id=? AND state='ACTIVE' AND deposit_id<>?
                """,
            List.of(lot.toString(), excludingDeposit == null ? "" : excludingDeposit.toString()),
            1);
    var claims =
        reads.query(
            """
                SELECT COALESCE(SUM(count),0) AS total FROM custody_cash_reservations
                WHERE lot_id=? AND state='ACTIVE'
                """,
            List.of(lot.toString()),
            1);
    return Math.addExact(
        Long.parseLong(deposits.getFirst().get("total")),
        Long.parseLong(claims.getFirst().get("total")));
  }

  /** Trusted server-only inspection. Do not expose it before authenticating the caller. */
  public static Optional<Lot> find(ReadView reads, WorldId world, UUID lot) {
    return reads
        .query(
            "SELECT * FROM cash_issuance WHERE world_id=? AND lot_id=?",
            List.of(world.value().toString(), lot.toString()),
            1)
        .stream()
        .map(CashIssuance::read)
        .findFirst();
  }

  /** Inspect outstanding restored lots before any redemption can be admitted. */
  public static boolean reconcileBatch(FoundationStore store) {
    var world = store.snapshot().worldId();
    String after = RECONCILIATION_CURSORS.getOrDefault(store, "");
    var rows =
        store.transaction(
            tx ->
                tx.query(
                    """
                SELECT * FROM cash_issuance WHERE state='ACTIVE' AND remaining>0 AND lot_id>?
                ORDER BY lot_id LIMIT 16
                """,
                    List.of(after),
                    16));
    var action = Action.QUARANTINE;
    var engine =
        new JournalEngine(
            store,
            (actor, kind) -> actor.equals(RECOVERY) && kind.equals(action.kind()),
            Map.of(
                action.kind(),
                registration(
                    action,
                    world,
                    (a, intent, lots, reads) -> intent.actor().equals(RECOVERY),
                    (a, intent, lots, evidence, reads) -> false,
                    null)));
    for (var row : rows) {
      var lot = read(row);
      if (store.<Boolean>transaction(tx -> NpcDeliveries.protectsCash(tx::query, lot))) continue;
      var id = lot.id();
      var operation =
          UUID.nameUUIDFromBytes(
              ("economy:cash-reconcile:" + id + ":" + lot.revision())
                  .getBytes(StandardCharsets.UTF_8));
      var result =
          engine.submit(
              new Intent(
                  new OperationId(operation),
                  new RequestId(UUID.randomUUID()),
                  RECOVERY,
                  action.kind(),
                  reconcileRequest(
                      id,
                      "startup-unknown",
                      "restored quantities require verified custody evidence"),
                  null,
                  null));
      if (result.status() != Status.COMMITTED)
        throw new IllegalStateException("Cash reconciliation failed");
    }
    String cursor = rows.isEmpty() ? after : rows.getLast().get("lot_id");
    boolean complete =
        store.transaction(
            tx ->
                tx.query(
                        """
                SELECT lot_id FROM cash_issuance WHERE state='ACTIVE' AND remaining>0 AND lot_id>? LIMIT 1
                """,
                        List.of(cursor),
                        1)
                    .isEmpty());
    if (complete) RECONCILIATION_CURSORS.remove(store);
    else RECONCILIATION_CURSORS.put(store, cursor);
    return complete;
  }

  private static Decision commit(Action action, List<Lot> changed, Commit base) {
    var records = new ArrayList<Journal.Record>();
    if (base != null) records.addAll(base.records());
    for (var lot : changed) records.add(new Journal.Record(FACT, 1, encode(action, lot)));
    if (records.size() > 64) return reject("CASH_RECORD_LIMIT");
    return new Commit(
        records,
        new Result(
            1,
            "CASH_" + action.name(),
            changed.size() == 1
                ? changed.getFirst().id().toString()
                : Integer.toString(changed.size())),
        tx -> {
          if (base != null) base.projections().apply(tx);
          for (var lot : changed) {
            if (action == Action.ISSUE) {
              tx.execute("INSERT INTO cash_issuance VALUES(?,?,?,?,?,?,?,?)", values(lot));
            } else {
              int updated =
                  tx.execute(
                      """
                            UPDATE cash_issuance SET remaining=?,state=?,revision=?,evidence=?
                            WHERE lot_id=? AND world_id=? AND revision=?
                            """,
                      List.of(
                          Integer.toString(lot.remaining()),
                          lot.state().name(),
                          Long.toString(lot.revision()),
                          lot.evidence(),
                          lot.id().toString(),
                          lot.world().value().toString(),
                          Long.toString(lot.revision() - 1)));
              if (updated != 1) throw new IllegalStateException("Cash lot revision changed");
            }
          }
        });
  }

  private static List<String> values(Lot lot) {
    return List.of(
        lot.id().toString(),
        lot.world().value().toString(),
        lot.item(),
        Integer.toString(lot.issued()),
        Integer.toString(lot.remaining()),
        lot.state().name(),
        Long.toString(lot.revision()),
        lot.evidence());
  }

  private static Lot read(Map<String, String> row) {
    return new Lot(
        new WorldId(UUID.fromString(row.get("world_id"))),
        UUID.fromString(row.get("lot_id")),
        row.get("item_id"),
        Integer.parseInt(row.get("issued")),
        Integer.parseInt(row.get("remaining")),
        State.valueOf(row.get("state")),
        Long.parseLong(row.get("revision")),
        row.get("evidence"));
  }

  private static String encodeClaims(List<Claim> claims) {
    if (claims == null || claims.isEmpty() || claims.size() > MAX_STACKS)
      throw new IllegalArgumentException("Cash stack count");
    return claims.stream()
        .map(
            claim ->
                claim.world().value()
                    + "|"
                    + claim.lot()
                    + "|"
                    + claim.item()
                    + "|"
                    + claim.count())
        .sorted()
        .reduce((left, right) -> left + "\n" + right)
        .orElseThrow();
  }

  private static List<Claim> decodeClaims(String encoded) {
    var rows = encoded.split("\n", -1);
    if (rows.length > MAX_STACKS) throw new IllegalArgumentException("Cash stack count");
    var claims = new ArrayList<Claim>();
    for (var row : rows) {
      var fields = row.split("\\|", -1);
      if (fields.length != 4) throw new IllegalArgumentException("Cash claim fields");
      claims.add(
          new Claim(
              new WorldId(UUID.fromString(fields[0])),
              UUID.fromString(fields[1]),
              fields[2],
              Integer.parseInt(fields[3])));
    }
    return List.copyOf(claims);
  }

  private static void requireFinancialIdentity(Intent intent) {
    String financial = intent.request().fields().get("financial");
    text(financial, 4096);
    var fields = financial.split("\n", 5);
    if (fields.length != 5 || !intent.operationId().value().toString().equals(fields[0]))
      throw new IllegalArgumentException("Cash financial identity");
    actor(fields[1]);
    new Kind(fields[2], fields[3]);
    if (fields[4].isBlank()) throw new IllegalArgumentException("Cash financial canonical");
  }

  private static String encode(Action action, Lot lot) {
    return action.name()
        + "|"
        + lot.world().value()
        + "|"
        + lot.id()
        + "|"
        + lot.item()
        + "|"
        + lot.issued()
        + "|"
        + lot.remaining()
        + "|"
        + lot.state().name()
        + "|"
        + lot.revision()
        + "|"
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(lot.evidence().getBytes(StandardCharsets.UTF_8));
  }

  public static void validateFact(String payload) {
    var fields = payload.split("\\|", -1);
    if (fields.length != 9) throw new IllegalArgumentException("Cash fact fields");
    Action.valueOf(fields[0]);
    new Lot(
        new WorldId(UUID.fromString(fields[1])),
        UUID.fromString(fields[2]),
        fields[3],
        Integer.parseInt(fields[4]),
        Integer.parseInt(fields[5]),
        State.valueOf(fields[6]),
        Long.parseLong(fields[7]),
        new String(Base64.getUrlDecoder().decode(fields[8]), StandardCharsets.UTF_8));
  }

  private static void itemId(String item) {
    if (item == null || item.length() > 128 || !item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
      throw new IllegalArgumentException("Cash item identity");
  }

  private static void text(String value, int maximum) {
    if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > maximum)
      throw new IllegalArgumentException("Cash text bounds");
  }

  private static Reject reject(String code) {
    return new Reject(new Result(1, code, ""));
  }

  private static Decline decline(String code) {
    return new Decline(new Result(1, code, ""));
  }
}
