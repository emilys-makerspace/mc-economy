package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import java.util.*;

/** Durable coordination only; no journal callback can place world blocks. */
public final class KioskUpgrades {
  public static final String FACT = "economy:kiosk_geometry";

  public enum Action {
    BEGIN,
    COMPLETE,
    ABORT;

    public Journal.Kind kind() {
      return new Journal.Kind(
          "economy:kiosk_geometry", "economy:" + name().toLowerCase(Locale.ROOT));
    }
  }

  public record Upgrade(UUID operation, long expectedRevision, String phase) {}

  private KioskUpgrades() {}

  public static List<String> statements() {
    return List.of(
        """
            CREATE TABLE kiosk_geometry_upgrades(
                world_id TEXT NOT NULL,generation TEXT NOT NULL,operation_id TEXT NOT NULL,
                expected_revision INTEGER NOT NULL CHECK(expected_revision>0),phase TEXT NOT NULL CHECK(phase IN ('PREPARED','COMPLETE','ABORTED')),
                PRIMARY KEY(world_id,generation))
            """);
  }

  public static Journal.CanonicalRequest request(
      TerminalPolicy.Anchor a, UUID generation, long revision, UUID operation) {
    if (revision < 1) throw new IllegalArgumentException("Kiosk revision");
    return new Journal.CanonicalRequest(
        1,
        Map.of(
            "dimension",
            a.dimension(),
            "x",
            "" + a.x(),
            "y",
            "" + a.y(),
            "z",
            "" + a.z(),
            "generation",
            generation.toString(),
            "revision",
            "" + revision,
            "upgrade",
            operation.toString()));
  }

  public static Optional<Upgrade> find(ReadView reads, WorldId world, UUID generation) {
    return reads
        .query(
            "SELECT * FROM kiosk_geometry_upgrades WHERE world_id=? AND generation=?",
            List.of(world.value().toString(), generation.toString()),
            1)
        .stream()
        .map(
            r ->
                new Upgrade(
                    UUID.fromString(r.get("operation_id")),
                    Long.parseLong(r.get("expected_revision")),
                    r.get("phase")))
        .findFirst();
  }

  public static boolean usable(ReadView reads, WorldId world, UUID generation) {
    return find(reads, world, generation).map(u -> !u.phase().equals("PREPARED")).orElse(true);
  }

  public static Registration registration(
      Action action,
      WorldId world,
      TerminalPolicy.Authority live,
      TerminalPolicy.Operators operators) {
    return new Registration(
        1,
        (intent, reads) -> {
          TerminalPolicy.Anchor anchor;
          UUID generation, operation;
          long revision;
          try {
            var f = intent.request().fields();
            anchor =
                new TerminalPolicy.Anchor(
                    f.get("dimension"),
                    Integer.parseInt(f.get("x")),
                    Integer.parseInt(f.get("y")),
                    Integer.parseInt(f.get("z")));
            generation = UUID.fromString(f.get("generation"));
            operation = UUID.fromString(f.get("upgrade"));
            revision = Long.parseLong(f.get("revision"));
            if (!intent.kind().equals(action.kind())
                || !request(anchor, generation, revision, operation).equals(intent.request()))
              return reject("KIOSK_REQUEST");
          } catch (IllegalArgumentException | NullPointerException bad) {
            return reject("KIOSK_REQUEST");
          }
          var current = TerminalPolicy.raw(reads, world, anchor).orElse(null);
          if (current == null
              || !current.active()
              || current.kind() != TerminalPolicy.Kind.KIOSK
              || !current.generation().equals(generation)
              || current.revision() != revision) return reject("TERMINAL_STALE");
          if (!live.allows(TerminalPolicy.Action.CONFIGURE, intent, current, reads))
            return new Decline(new Journal.Result(1, "KIOSK_LIVE_REQUIRED", ""));
          var prior = find(reads, world, generation).orElse(null);
          if (action == Action.BEGIN) {
            if (!current.owner().equals(intent.actor()) && !operators.allows(intent.actor(), reads))
              return new Decline(new Journal.Result(1, "TERMINAL_ACCESS", ""));
            if (prior != null && !prior.phase().equals("ABORTED"))
              return reject("KIOSK_UPGRADE_EXISTS");
            if (TerminalBanking.hasPending(reads, world, generation))
              return new Decline(new Journal.Result(1, "TERMINAL_BANK_PENDING", ""));
          } else if (prior == null
              || !prior.phase().equals("PREPARED")
              || !prior.operation().equals(operation)
              || prior.expectedRevision() != revision - 1) return reject("KIOSK_UPGRADE_STALE");
          if (revision == Long.MAX_VALUE
              || action == Action.BEGIN && revision == Long.MAX_VALUE - 1)
            return reject("TERMINAL_REVISION_LIMIT");
          var changed =
              new TerminalPolicy.Snapshot(
                  world,
                  anchor,
                  generation,
                  current.owner(),
                  current.kind(),
                  revision + 1,
                  true,
                  current.settings(),
                  current.binding());
          String phase =
              action == Action.BEGIN
                  ? "PREPARED"
                  : action == Action.COMPLETE ? "COMPLETE" : "ABORTED";
          long original = action == Action.BEGIN ? revision : prior.expectedRevision();
          String fact =
              String.join(
                  "|",
                  world.value().toString(),
                  generation.toString(),
                  operation.toString(),
                  "" + original,
                  phase);
          return new Commit(
              List.of(
                  new Journal.Record(FACT, 1, fact),
                  new Journal.Record(
                      TerminalPolicy.FACT,
                      1,
                      TerminalPolicy.encode(TerminalPolicy.Action.CONFIGURE, changed))),
              new Journal.Result(1, "KIOSK_" + phase, generation.toString()),
              tx -> {
                TerminalPolicy.project(tx, current, changed);
                tx.execute(
                    "INSERT INTO kiosk_geometry_upgrades VALUES(?,?,?,?,?) ON CONFLICT(world_id,generation) DO UPDATE SET operation_id=excluded.operation_id,expected_revision=excluded.expected_revision,phase=excluded.phase",
                    List.of(
                        world.value().toString(),
                        generation.toString(),
                        operation.toString(),
                        "" + original,
                        phase));
              });
        });
  }

  public static void validateFact(String payload) {
    var f = payload.split("\\|", -1);
    if (f.length != 5) throw new IllegalArgumentException("Kiosk geometry fact");
    for (int i = 0; i < 3; i++)
      if (!UUID.fromString(f[i]).toString().equals(f[i]))
        throw new IllegalArgumentException("Kiosk UUID");
    long revision = Long.parseLong(f[3]);
    if (revision < 1
        || !Long.toString(revision).equals(f[3])
        || !Set.of("PREPARED", "COMPLETE", "ABORTED").contains(f[4]))
      throw new IllegalArgumentException("Kiosk fact phase/revision");
  }

  public static void validate(java.sql.Connection c) throws java.sql.SQLException {
    try (var s = c.createStatement();
        var r =
            s.executeQuery("SELECT sql FROM sqlite_schema WHERE name='kiosk_geometry_upgrades'")) {
      if (!r.next() || !r.getString(1).equals(statements().getFirst().strip()))
        throw new java.sql.SQLException("Kiosk upgrade schema");
    }
    try (var s = c.createStatement();
        var r =
            s.executeQuery(
                "SELECT g.*,t.terminal_kind,t.revision,t.world_id AS terminal_world,h.generation AS historical_generation FROM kiosk_geometry_upgrades g LEFT JOIN terminal_settings t ON t.world_id=g.world_id AND t.generation=g.generation LEFT JOIN terminal_generations h ON h.world_id=g.world_id AND h.generation=g.generation")) {
      while (r.next()) {
        String fact =
            String.join(
                "|",
                r.getString("world_id"),
                r.getString("generation"),
                r.getString("operation_id"),
                r.getString("expected_revision"),
                r.getString("phase"));
        try {
          validateFact(fact);
        } catch (RuntimeException bad) {
          throw new java.sql.SQLException("Kiosk upgrade data", bad);
        }
        if (r.getString("historical_generation") == null
            || r.getString("terminal_world") != null
                && (!"KIOSK".equals(r.getString("terminal_kind"))
                    || r.getLong("revision") <= r.getLong("expected_revision")))
          throw new java.sql.SQLException("Kiosk terminal linkage");
      }
    }
  }

  private static Reject reject(String code) {
    return new Reject(new Journal.Result(1, code, ""));
  }
}
