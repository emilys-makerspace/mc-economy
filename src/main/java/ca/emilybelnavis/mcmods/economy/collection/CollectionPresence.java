package ca.emilybelnavis.mcmods.economy.collection;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.debt.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;

/** Presentation authority and cooldowns share the existing worker and immutable journal. */
public final class CollectionPresence {
  public static final long COOLDOWN = 7 * DebtRecords.DAY;
  public static final String FACT = "economy:a6/collection-presence";
  public static final Journal.Kind KIND =
      new Journal.Kind("economy:collection-agent", "economy:presentation");
  private static final UUID EMPTY = new UUID(0, 0);

  public enum Phase {
    LIVE,
    RELEASED,
    KILLED,
    UNCERTAIN
  }

  public enum Action {
    RESERVE,
    RELEASE,
    DEATH,
    RECONCILE
  }

  private CollectionPresence() {}

  public record State(
      WorldId world,
      PlayerId owner,
      UUID lease,
      Phase phase,
      long observedAt,
      long cooldownUntil,
      long revision) {
    public State {
      Objects.requireNonNull(world);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(lease);
      Objects.requireNonNull(phase);
      if (observedAt < 0
          || revision < 0
          || revision == 0 && (phase != Phase.RELEASED || !lease.equals(EMPTY) || observedAt != 0)
          || revision > 0 && lease.equals(EMPTY))
        throw new IllegalArgumentException("Collection presentation identity");
      if (phase == Phase.LIVE || phase == Phase.RELEASED) {
        if (cooldownUntil != 0)
          throw new IllegalArgumentException("Collection presentation window");
      } else if (cooldownUntil < Math.addExact(observedAt, COOLDOWN))
        throw new IllegalArgumentException("Collection cooldown window");
    }
  }

  public record Snapshot(List<DebtRecords.Case> cases, UUID nextCursor, State state, long asOf) {
    public Snapshot {
      cases = List.copyOf(cases);
    }
  }

  public static Snapshot snapshot(
      JournalEngine.ReadView reads, PartyRef owner, UUID after, int limit, long now) {
    if (limit < 1 || limit > 16 || now < 0)
      throw new IllegalArgumentException("Collection presentation page");
    var player = player(owner);
    var rows =
        reads.query(
            "SELECT case_id FROM debt_cases WHERE world_id=? AND owner_id=? AND collectability='COLLECTIBLE' AND payment<>'SETTLED' AND acknowledged_at<=? AND collection_expires>? AND case_id>? ORDER BY case_id LIMIT ?",
            List.of(
                owner.world().value().toString(),
                player.value().toString(),
                Long.toString(now),
                Long.toString(now),
                after == null ? "" : after.toString(),
                Integer.toString(limit + 1)),
            limit + 1);
    var cases = new ArrayList<DebtRecords.Case>();
    for (var row : rows.stream().limit(limit).toList()) {
      var debt =
          DebtLifecycle.ownedCase(reads, owner, UUID.fromString(row.get("case_id"))).orElseThrow();
      if (!DebtLifecycle.demands(debt, now))
        throw new IllegalArgumentException("Collection eligibility binding");
      cases.add(debt);
    }
    return new Snapshot(
        cases, rows.size() > limit ? cases.getLast().id() : null, state(reads, owner), now);
  }

  public static State state(JournalEngine.ReadView reads, PartyRef owner) {
    var player = player(owner);
    var rows =
        reads.query(
            "SELECT * FROM collection_agent_state WHERE world_id=? AND owner_id=?",
            List.of(owner.world().value().toString(), player.value().toString()),
            1);
    if (rows.isEmpty()) return new State(owner.world(), player, EMPTY, Phase.RELEASED, 0, 0, 0);
    var row = rows.getFirst();
    var value = decode(row.get("body"));
    if (!value.world().equals(owner.world())
        || !value.owner().equals(player)
        || !row.get("lease_id").equals(value.lease().toString())
        || !row.get("phase").equals(value.phase().name())
        || number(row.get("observed_at")) != value.observedAt()
        || number(row.get("cooldown_until")) != value.cooldownUntil()
        || number(row.get("revision")) != value.revision())
      throw new IllegalArgumentException("Collection presentation projection binding");
    return value;
  }

  public static UUID operation(PartyRef owner, Action action, UUID lease) {
    return UUID.nameUUIDFromBytes(
        ("economy:collection-presence:v1|"
                + owner.world().value()
                + "|"
                + player(owner).value()
                + "|"
                + action
                + "|"
                + lease)
            .getBytes(StandardCharsets.UTF_8));
  }

  public static Journal.CanonicalRequest request(
      PartyRef owner, Action action, UUID lease, long observedDeathAt) {
    if (lease.equals(EMPTY)
        || observedDeathAt < 0
        || action != Action.DEATH && observedDeathAt != 0)
      throw new IllegalArgumentException("Collection presentation request");
    return new Journal.CanonicalRequest(
        1,
        Map.of(
            "world",
            owner.world().value().toString(),
            "economy:owner",
            Journal.actorKey(player(owner)),
            "action",
            action.name(),
            "lease",
            lease.toString(),
            "observed",
            Long.toString(observedDeathAt)));
  }

  public static JournalEngine.Registration registration(
      PartyRef owner, Action action, UUID lease, long observedDeathAt, LongSupplier clock) {
    var expected = request(owner, action, lease, observedDeathAt);
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          if (!intent.kind().equals(KIND)
              || !intent.actor().equals(player(owner))
              || !intent.request().equals(expected)) return decline("COLLECTION_AGENT_REQUEST");
          var world =
              reads.query(
                  "SELECT world_id FROM foundation_metadata WHERE singleton=1", List.of(), 1);
          if (world.isEmpty()
              || !world.getFirst().get("world_id").equals(owner.world().value().toString()))
            return decline("COLLECTION_AGENT_WORLD");
          long now = clock.getAsLong();
          var old = state(reads, owner);
          if (now < old.observedAt()) return decline("COLLECTION_AGENT_CLOCK");
          Phase phase;
          long at = now, until = 0;
          String code;
          switch (action) {
            case RESERVE -> {
              if (old.phase() == Phase.LIVE
                  || now < old.cooldownUntil()
                  || snapshot(reads, owner, null, 1, now).cases().isEmpty())
                return decline("COLLECTION_AGENT_BLOCKED");
              phase = Phase.LIVE;
              code = "COLLECTION_AGENT_RESERVED";
            }
            case RELEASE -> {
              if (old.phase() != Phase.LIVE || !old.lease().equals(lease))
                return decline("COLLECTION_AGENT_STALE");
              phase = Phase.RELEASED;
              code = "COLLECTION_AGENT_RELEASED";
            }
            case DEATH -> {
              if (old.phase() != Phase.LIVE
                  || !old.lease().equals(lease)
                  || observedDeathAt < old.observedAt()
                  || observedDeathAt > now) return decline("COLLECTION_AGENT_STALE");
              at = observedDeathAt;
              until = Math.addExact(at, COOLDOWN);
              phase = Phase.KILLED;
              code = "COLLECTION_AGENT_KILLED";
            }
            case RECONCILE -> {
              if (old.phase() != Phase.LIVE || !old.lease().equals(lease))
                return decline("COLLECTION_AGENT_STALE");
              until = Math.addExact(now, COOLDOWN);
              phase = Phase.UNCERTAIN;
              code = "COLLECTION_AGENT_UNCERTAIN";
            }
            default -> throw new IllegalArgumentException("Collection presentation action");
          }
          var changed =
              new State(
                  owner.world(),
                  player(owner),
                  lease,
                  phase,
                  at,
                  until,
                  Math.addExact(old.revision(), 1));
          return new JournalEngine.Commit(
              List.of(new Journal.Record(FACT, 1, encode(changed))),
              new Journal.Result(1, code, lease.toString()),
              tx -> write(tx, changed));
        });
  }

  private static JournalEngine.Decision decline(String code) {
    return new JournalEngine.Decline(new Journal.Result(1, code, ""));
  }

  private static void write(FoundationStore.Transaction tx, State value) {
    tx.execute(
        "INSERT INTO collection_agent_state VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(world_id,owner_id) DO UPDATE SET lease_id=excluded.lease_id,phase=excluded.phase,observed_at=excluded.observed_at,cooldown_until=excluded.cooldown_until,revision=excluded.revision,body=excluded.body",
        List.of(
            value.world().value().toString(),
            value.owner().value().toString(),
            value.lease().toString(),
            value.phase().name(),
            Long.toString(value.observedAt()),
            Long.toString(value.cooldownUntil()),
            Long.toString(value.revision()),
            encode(value)));
  }

  public static String encode(State value) {
    return String.join(
        "|",
        value.world().value().toString(),
        value.owner().value().toString(),
        value.lease().toString(),
        value.phase().name(),
        Long.toString(value.observedAt()),
        Long.toString(value.cooldownUntil()),
        Long.toString(value.revision()));
  }

  public static State decode(String body) {
    if (body.length() > 256)
      throw new IllegalArgumentException("Collection presentation record size");
    var fields = body.split("\\|", -1);
    if (fields.length != 7) throw new IllegalArgumentException("Collection presentation fields");
    var value =
        new State(
            new WorldId(UUID.fromString(fields[0])),
            new PlayerId(UUID.fromString(fields[1])),
            UUID.fromString(fields[2]),
            Phase.valueOf(fields[3]),
            number(fields[4]),
            number(fields[5]),
            number(fields[6]));
    if (value.revision() < 1 || !encode(value).equals(body))
      throw new IllegalArgumentException("Collection presentation canonical record");
    return value;
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog.with(new RecordCatalog.Key(FACT, 1), CollectionPresence::decode);
  }

  private static PlayerId player(PartyRef owner) {
    if (!(owner.owner() instanceof PartyRef.Player p))
      throw new IllegalArgumentException("Collection player required");
    return p.id();
  }

  private static long number(String text) {
    long value = Long.parseLong(text);
    if (!Long.toString(value).equals(text))
      throw new IllegalArgumentException("Collection presentation number");
    return value;
  }
}
