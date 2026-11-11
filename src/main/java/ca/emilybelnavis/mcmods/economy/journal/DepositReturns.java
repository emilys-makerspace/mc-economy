package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Original cash returns. Effects run on the server thread after durable attempt admission. */
public final class DepositReturns {
  public static final String FACT = "economy:deposit_return";
  private static final SystemActorId RECOVERY =
      new SystemActorId(UUID.fromString("8d4ff435-3516-486b-9576-cd043b110457"));

  public enum State {
    PENDING,
    DELIVERING,
    DELIVERED,
    CLAIMED,
    QUARANTINED
  }

  public enum Action {
    BEGIN,
    COMPLETE,
    CLAIM,
    QUARANTINE;

    public Kind kind() {
      return new Kind("economy:deposit_return", "economy:" + name().toLowerCase(Locale.ROOT));
    }
  }

  public enum Proof {
    UNKNOWN,
    UNDELIVERED,
    SAVED_AND_UNDELIVERED
  }

  public record Evidence(Proof proof, String reference) {
    public Evidence {
      Objects.requireNonNull(proof);
      bounded(reference, 256);
    }

    public static Evidence unknown() {
      return new Evidence(Proof.UNKNOWN, "unknown");
    }
  }

  public record Return(
      UUID deposit,
      int slot,
      ActorId owner,
      WorldId world,
      UUID lot,
      String item,
      int count,
      String components,
      State state,
      long revision,
      UUID attempt,
      int admitted,
      int delivered,
      String evidence,
      String reason) {
    public Return {
      Objects.requireNonNull(deposit);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(world);
      Objects.requireNonNull(lot);
      Objects.requireNonNull(state);
      if (slot < 0
          || slot >= 24
          || count < 1
          || count > 64
          || admitted < 0
          || admitted > count
          || delivered < 0
          || delivered > admitted
          || revision < 1) throw new IllegalArgumentException("Return bounds");
      if (item == null || item.length() > 128 || !item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
        throw new IllegalArgumentException("Return item");
      bounded(components, 2048);
      if (evidence == null || evidence.length() > 512 || reason == null || reason.length() > 256)
        throw new IllegalArgumentException("Return audit fields");
    }

    public UUID claimId() {
      return claimIdentity(deposit, slot);
    }
  }

  @FunctionalInterface
  public interface Authority {
    /**
     * Recheck authenticated owner, session and admitted direct capacity using detached
     * observations.
     */
    boolean allows(Action action, Intent intent, Return current, ReadView reads);
  }

  @FunctionalInterface
  public interface EvidenceVerifier {
    /** COMPLETE proves saved delivered units and exact undelivered remainder together. */
    boolean verifies(
        Action action, Return current, int delivered, Evidence evidence, ReadView reads);
  }

  private DepositReturns() {}

  public static CanonicalRequest request(
      UUID deposit,
      int slot,
      long revision,
      UUID attempt,
      int quantity,
      Evidence evidence,
      String reason) {
    Objects.requireNonNull(deposit);
    Objects.requireNonNull(evidence);
    bounded(reason, 256);
    if (slot < 0 || slot >= 24 || revision < 1 || quantity < 0 || quantity > 64)
      throw new IllegalArgumentException("Return request bounds");
    return new CanonicalRequest(
        1,
        Map.of(
            "deposit",
            deposit.toString(),
            "slot",
            Integer.toString(slot),
            "revision",
            Long.toString(revision),
            "attempt",
            attempt == null ? "" : attempt.toString(),
            "quantity",
            Integer.toString(quantity),
            "proof",
            evidence.proof().name(),
            "evidence",
            evidence.reference(),
            "reason",
            reason));
  }

  public static Registration registration(
      Action action, WorldId world, Authority authority, EvidenceVerifier verifier) {
    Objects.requireNonNull(action);
    Objects.requireNonNull(world);
    Objects.requireNonNull(authority);
    Objects.requireNonNull(verifier);
    return new Registration(
        1, (intent, reads) -> prepare(action, world, authority, verifier, intent, reads));
  }

  private static Decision prepare(
      Action action,
      WorldId world,
      Authority authority,
      EvidenceVerifier verifier,
      Intent intent,
      ReadView reads) {
    UUID deposit;
    UUID attempt;
    int slot;
    int quantity;
    long revision;
    Evidence evidence;
    String reason;
    try {
      var fields = intent.request().fields();
      deposit = UUID.fromString(fields.get("deposit"));
      slot = Integer.parseInt(fields.get("slot"));
      revision = Long.parseLong(fields.get("revision"));
      quantity = Integer.parseInt(fields.get("quantity"));
      attempt = fields.get("attempt").isEmpty() ? null : UUID.fromString(fields.get("attempt"));
      evidence = new Evidence(Proof.valueOf(fields.get("proof")), fields.get("evidence"));
      reason = fields.get("reason");
      if (!intent.kind().equals(action.kind())
          || !request(deposit, slot, revision, attempt, quantity, evidence, reason)
              .equals(intent.request())) return reject("DEPOSIT_RETURN_REQUEST");
    } catch (IllegalArgumentException | NullPointerException malformed) {
      return reject("DEPOSIT_RETURN_REQUEST");
    }
    var current =
        (action == Action.QUARANTINE && intent.actor().equals(RECOVERY)
                ? recoveryFind(reads, deposit, slot)
                : find(reads, intent.actor(), deposit, slot))
            .orElse(null);
    if (current == null
        || !current.world().equals(world)
        || !authority.allows(action, intent, current, reads))
      return decline("DEPOSIT_RETURN_ACCESS");
    if (current.revision() != revision) return reject("DEPOSIT_RETURN_STALE");
    if (current.state() == State.DELIVERED || current.state() == State.CLAIMED)
      return reject("DEPOSIT_RETURN_TERMINAL");
    var next = current.state();
    int admitted = current.admitted();
    int delivered = current.delivered();
    UUID nextAttempt = current.attempt();
    int released = 0;
    int claimed = 0;
    Commit custody = null;
    switch (action) {
      case BEGIN -> {
        if (current.state() != State.PENDING || quantity < 1) return reject("DEPOSIT_RETURN_STATE");
        if (quantity > current.count() || !intent.operationId().value().equals(attempt))
          return reject("DEPOSIT_RETURN_ATTEMPT");
        if (evidence.proof() != Proof.UNDELIVERED
            || !verifier.verifies(action, current, 0, evidence, reads))
          return decline("DEPOSIT_RETURN_EVIDENCE");
        admitted = quantity;
        nextAttempt = attempt;
        next = State.DELIVERING;
      }
      case COMPLETE -> {
        if (current.state() != State.DELIVERING
            || !Objects.equals(current.attempt(), attempt)
            || quantity > current.admitted()) return reject("DEPOSIT_RETURN_ATTEMPT");
        if (evidence.proof() != Proof.SAVED_AND_UNDELIVERED
            || !verifier.verifies(action, current, quantity, evidence, reads))
          return decline("DEPOSIT_RETURN_EVIDENCE");
        delivered = quantity;
        released = current.count();
        claimed = current.count() - quantity;
        next = claimed == 0 ? State.DELIVERED : State.CLAIMED;
      }
      case CLAIM -> {
        if (current.state() != State.PENDING || quantity != 0 || attempt != null)
          return reject("DEPOSIT_RETURN_STATE");
        if (evidence.proof() != Proof.UNDELIVERED
            || !verifier.verifies(action, current, 0, evidence, reads))
          return decline("DEPOSIT_RETURN_EVIDENCE");
        released = current.count();
        claimed = current.count();
        next = State.CLAIMED;
      }
      case QUARANTINE -> {
        if (current.state() == State.QUARANTINED) return reject("DEPOSIT_RETURN_STATE");
        next = State.QUARANTINED;
        released = current.count();
        claimed = current.count();
      }
    }
    if (claimed > 0) {
      String location = "deposit-return:" + deposit + ":" + slot;
      var custodyEvidence =
          new Custody.Evidence(
              action == Action.QUARANTINE
                  ? Custody.EvidenceKind.UNKNOWN
                  : Custody.EvidenceKind.UNDELIVERED,
              evidence.reference(),
              location,
              evidence.reference());
      custody =
          Custody.returnCase(
              intent,
              current.claimId(),
              deposit,
              current.owner(),
              claimItems(current, claimed),
              location,
              custodyEvidence,
              reason,
              action == Action.QUARANTINE ? Custody.State.QUARANTINED : Custody.State.CLAIM,
              reads);
      if (custody == null) return reject("DEPOSIT_RETURN_CLAIM_EXISTS");
    }
    var changed =
        new Return(
            deposit,
            slot,
            current.owner(),
            world,
            current.lot(),
            current.item(),
            current.count(),
            current.components(),
            next,
            Math.addExact(revision, 1),
            nextAttempt,
            admitted,
            delivered,
            evidence.proof().name() + ":" + evidence.reference(),
            reason);
    return commit(action, changed, released, claimed, custody);
  }

  private static Commit commit(
      Action action, Return value, int released, int claimed, Commit custody) {
    var records = new ArrayList<Journal.Record>();
    if (custody != null) records.addAll(custody.records());
    records.add(new Journal.Record(FACT, 1, encode(action, value)));
    return new Commit(
        records,
        new Result(1, "DEPOSIT_RETURN_" + action.name(), value.deposit() + ":" + value.slot()),
        tx -> {
          if (custody != null) custody.projections().apply(tx);
          int changed =
              tx.execute(
                  """
                    UPDATE deposit_returns SET state=?,revision=?,attempt=?,admitted=?,delivered=?,evidence=?,reason=?
                    WHERE deposit_id=? AND slot=? AND revision=?
                    """,
                  Arrays.asList(
                      value.state().name(),
                      Long.toString(value.revision()),
                      text(value.attempt()),
                      Integer.toString(value.admitted()),
                      Integer.toString(value.delivered()),
                      value.evidence(),
                      value.reason(),
                      value.deposit().toString(),
                      Integer.toString(value.slot()),
                      Long.toString(value.revision() - 1)));
          if (changed != 1) throw new IllegalStateException("Return revision changed");
          if (released > 0) {
            int locks =
                tx.execute(
                    """
                        UPDATE deposit_cash_reservations SET count=count-?,
                        state=CASE WHEN count-?=0 THEN 'RELEASED' ELSE 'ACTIVE' END
                        WHERE deposit_id=? AND lot_id=? AND state='ACTIVE' AND count>=?
                        """,
                    List.of(
                        Integer.toString(released),
                        Integer.toString(released),
                        value.deposit().toString(),
                        value.lot().toString(),
                        Integer.toString(released)));
            if (locks != 1) throw new IllegalStateException("Return cash reservation missing");
          }
          if (claimed > 0)
            tx.execute(
                "INSERT INTO custody_cash_reservations VALUES(?,?,?,'ACTIVE')",
                List.of(
                    value.claimId().toString(), value.lot().toString(), Integer.toString(claimed)));
        });
  }

  /** Bounded owner-only inspection, including in-flight and quarantined originals. */
  public static List<Return> list(ReadView reads, ActorId owner, UUID deposit) {
    return reads
        .query(
            """
                SELECT r.*,d.owner FROM deposit_returns r JOIN deposits d ON d.id=r.deposit_id
                WHERE r.deposit_id=? AND d.owner=? ORDER BY r.slot LIMIT 24
                """,
            List.of(deposit.toString(), actorKey(owner)),
            24)
        .stream()
        .map(DepositReturns::read)
        .toList();
  }

  public static Optional<Return> find(ReadView reads, ActorId owner, UUID deposit, int slot) {
    if (slot < 0 || slot >= 24) throw new IllegalArgumentException("Return slot");
    return reads
        .query(
            """
                SELECT r.*,d.owner FROM deposit_returns r JOIN deposits d ON d.id=r.deposit_id
                WHERE r.deposit_id=? AND r.slot=? AND d.owner=?
                """,
            List.of(deposit.toString(), Integer.toString(slot), actorKey(owner)),
            1)
        .stream()
        .map(DepositReturns::read)
        .findFirst();
  }

  /** Conservatively gate restored direct-return attempts in bounded journaled pages. */
  public static boolean reconcileBatch(FoundationStore store) {
    var rows =
        store.transaction(
            tx ->
                tx.query(
                    """
                SELECT deposit_id,slot,revision FROM deposit_returns
                WHERE state IN ('PENDING','DELIVERING') ORDER BY deposit_id,slot LIMIT 16
                """,
                    List.of(),
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
                    store.snapshot().worldId(),
                    (a, intent, current, reads) -> intent.actor().equals(RECOVERY),
                    (a, current, delivered, evidence, reads) -> false)));
    for (var row : rows) {
      var deposit = UUID.fromString(row.get("deposit_id"));
      int slot = Integer.parseInt(row.get("slot"));
      long revision = Long.parseLong(row.get("revision"));
      var operation =
          UUID.nameUUIDFromBytes(
              ("economy:deposit-return-recovery:" + deposit + ":" + slot + ":" + revision)
                  .getBytes(StandardCharsets.UTF_8));
      var result =
          engine.submit(
              new Intent(
                  new OperationId(operation),
                  new RequestId(UUID.randomUUID()),
                  RECOVERY,
                  action.kind(),
                  request(
                      deposit,
                      slot,
                      revision,
                      null,
                      0,
                      Evidence.unknown(),
                      "restored originals require external save evidence"),
                  null,
                  null));
      if (result.status() != Status.COMMITTED)
        throw new IllegalStateException("Return reconciliation failed");
    }
    return store.transaction(
        tx ->
            tx.query(
                    """
                SELECT deposit_id FROM deposit_returns WHERE state IN ('PENDING','DELIVERING') LIMIT 1
                """,
                    List.of(),
                    1)
                .isEmpty());
  }

  private static Optional<Return> recoveryFind(ReadView reads, UUID deposit, int slot) {
    return reads
        .query(
            """
                SELECT r.*,d.owner FROM deposit_returns r JOIN deposits d ON d.id=r.deposit_id
                WHERE r.deposit_id=? AND r.slot=?
                """,
            List.of(deposit.toString(), Integer.toString(slot)),
            1)
        .stream()
        .map(DepositReturns::read)
        .findFirst();
  }

  private static Return read(Map<String, String> row) {
    return new Return(
        UUID.fromString(row.get("deposit_id")),
        Integer.parseInt(row.get("slot")),
        actor(row.get("owner")),
        new WorldId(UUID.fromString(row.get("world_id"))),
        UUID.fromString(row.get("lot_id")),
        row.get("item_id"),
        Integer.parseInt(row.get("count")),
        row.get("components"),
        State.valueOf(row.get("state")),
        Long.parseLong(row.get("revision")),
        row.get("attempt") == null ? null : UUID.fromString(row.get("attempt")),
        Integer.parseInt(row.get("admitted")),
        Integer.parseInt(row.get("delivered")),
        row.get("evidence"),
        row.get("reason"));
  }

  private static UUID claimIdentity(UUID deposit, int slot) {
    return UUID.nameUUIDFromBytes(
        ("economy:deposit-return:" + deposit + ":" + slot).getBytes(StandardCharsets.UTF_8));
  }

  /** Original identities and components are retained in the owner claim without replacing cash. */
  public static String claimItems(Return value, int count) {
    if (count < 1 || count > value.count()) throw new IllegalArgumentException("Claim quantity");
    return String.join(
        "|",
        "deposit-original-v1",
        value.world().value().toString(),
        value.lot().toString(),
        value.item(),
        Integer.toString(count),
        pack(value.components()));
  }

  private static List<String> values(Return r) {
    return Arrays.asList(
        r.deposit().toString(),
        Integer.toString(r.slot()),
        actorKey(r.owner()),
        r.world().value().toString(),
        r.lot().toString(),
        r.item(),
        Integer.toString(r.count()),
        r.components(),
        r.state().name(),
        Long.toString(r.revision()),
        text(r.attempt()),
        Integer.toString(r.admitted()),
        Integer.toString(r.delivered()),
        r.evidence(),
        r.reason());
  }

  private static String encode(Action action, Return r) {
    var fields = new ArrayList<String>();
    fields.add(action.name());
    values(r).forEach(value -> fields.add(pack(value == null ? "" : value)));
    return String.join("|", fields);
  }

  public static void validateFact(String payload) {
    var fields = payload.split("\\|", -1);
    if (fields.length != 16) throw new IllegalArgumentException("Return fact fields");
    Action.valueOf(fields[0]);
    var names =
        List.of(
            "deposit_id",
            "slot",
            "owner",
            "world_id",
            "lot_id",
            "item_id",
            "count",
            "components",
            "state",
            "revision",
            "attempt",
            "admitted",
            "delivered",
            "evidence",
            "reason");
    var row = new HashMap<String, String>();
    for (int index = 0; index < names.size(); index++) {
      String decoded = unpack(fields[index + 1]);
      row.put(
          names.get(index),
          names.get(index).equals("attempt") && decoded.isEmpty() ? null : decoded);
    }
    read(row);
  }

  private static String pack(String text) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String unpack(String text) {
    return new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
  }

  private static String text(UUID id) {
    return id == null ? null : id.toString();
  }

  private static void bounded(String text, int maximum) {
    if (text == null || text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length > maximum)
      throw new IllegalArgumentException("Return field bounds");
  }

  private static Reject reject(String code) {
    return new Reject(new Result(1, code, ""));
  }

  private static Decline decline(String code) {
    return new Decline(new Result(1, code, ""));
  }
}
