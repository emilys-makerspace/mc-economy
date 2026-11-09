package ca.emilybelnavis.mcmods.economy.credit;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.debt.DebtRecords;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Canonical event-time revolving snapshots, captured in the original financial transaction. */
public final class CreditReporting {
  public static final String SOURCE = "economy:a6/score-utilization-source";
  public static final String BOOTSTRAP = "economy:a6/score-source-bootstrap";

  private CreditReporting() {}

  public record Source(
      WorldId world,
      PlayerId owner,
      UUID facility,
      long revision,
      long effectiveAt,
      long sourceSequence,
      EconomyCurrency currency,
      long limitMinor,
      boolean active,
      long lastDay,
      long purchaseMinor,
      BigDecimal purchaseResidual,
      BigDecimal purchaseApr,
      long advanceMinor,
      BigDecimal advanceResidual,
      BigDecimal advanceApr) {
    public Source {
      Objects.requireNonNull(world);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(facility);
      Objects.requireNonNull(currency);
      if (revision < 1
          || effectiveAt < 0
          || sourceSequence < 0
          || limitMinor < 1
          || lastDay < 0
          || effectiveAt < lastDay
          || purchaseMinor < 0
          || advanceMinor < 0) throw new IllegalArgumentException("Utilization source fields");
      for (var n : List.of(purchaseResidual, advanceResidual, purchaseApr, advanceApr))
        if (n.precision() > 50 || Math.abs(n.scale()) > 18)
          throw new IllegalArgumentException("Utilization source decimal");
      if (purchaseApr.signum() < 0
          || advanceApr.signum() < 0
          || purchaseApr.compareTo(BigDecimal.ONE) > 0
          || advanceApr.compareTo(BigDecimal.ONE) > 0)
        throw new IllegalArgumentException("Utilization source APR");
      var unit = BigDecimal.valueOf(currency.minorUnitsPerMajor());
      var halfMinor = BigDecimal.ONE.divide(unit.multiply(BigDecimal.valueOf(2)));
      if (purchaseResidual.abs().compareTo(halfMinor) > 0
          || advanceResidual.abs().compareTo(halfMinor) > 0
          || BigDecimal.valueOf(purchaseMinor).divide(unit).add(purchaseResidual).signum() < 0
          || BigDecimal.valueOf(advanceMinor).divide(unit).add(advanceResidual).signum() < 0)
        throw new IllegalArgumentException("Utilization source exact balance");
    }
  }

  public static JournalEngine.Decision decorate(
      JournalEngine.ReadView reads, JournalEngine.Commit commit) {
    var records = new ArrayList<>(commit.records());
    var sources = new ArrayList<Source>();
    for (var record : commit.records())
      if (record.type().equals(CreditFacilities.FACT)) {
        if (record.version() != 1)
          throw new IllegalArgumentException("Unsupported revolving snapshot version");
        var facility = CreditFacilities.decodeSnapshot(record.payload());
        var source = source(facility, effective(facility, commit.records()), 0);
        sources.add(source);
        records.add(new Journal.Record(SOURCE, 1, encode(source)));
      }
    if (records.size() > 64
        || records.stream()
                .mapToLong(r -> r.payload().getBytes(StandardCharsets.UTF_8).length)
                .sum()
            > 131072)
      return new JournalEngine.Decline(new Journal.Result(1, "CREDIT_REPORTING_RECORD_BUDGET", ""));
    var profiles =
        commit.records().stream()
            .filter(r -> r.type().equals(CreditProfiles.PROFILE_FACT))
            .toList();
    if (sources.isEmpty() && profiles.isEmpty()) return commit;
    return new JournalEngine.Commit(
        records,
        commit.result(),
        tx -> {
          commit.projections().apply(tx);
          for (var source : sources) write(tx, source);
          for (var record : profiles) {
            var fields = unpack(record.payload(), 13);
            var owner = fields.get(1);
            var profile =
                CreditProfiles.find(
                        tx::query,
                        new ca.emilybelnavis.mcmods.economy.api.PartyRef(
                            new WorldId(UUID.fromString(fields.get(0))),
                            new ca.emilybelnavis.mcmods.economy.api.PartyRef.Player(
                                new PlayerId(UUID.fromString(owner)))))
                    .orElseThrow();
            long origin = CreditProfiles.origin(tx::query).orElseThrow();
            long next = nextBoundary(profile, origin);
            tx.execute(
                "INSERT INTO credit_score_heads VALUES(?,?,?) ON CONFLICT(world_id,owner_id) DO UPDATE SET next_at=excluded.next_at",
                List.of(profile.world().value().toString(), owner, Long.toString(next)));
          }
        });
  }

  public static long nextBoundary(CreditProfiles.Profile profile, long origin) {
    return profile.lastBoundary() >= 0
        ? Math.addExact(profile.lastBoundary(), ScorePolicy.CYCLE_MILLIS)
        : Math.addExact(
            origin,
            Math.multiplyExact(
                (profile.firstIssuedAt() - origin) / ScorePolicy.CYCLE_MILLIS + 1,
                ScorePolicy.CYCLE_MILLIS));
  }

  /** One immutable snapshot or one existing profile per startup transaction. */
  public static boolean bootstrap(FoundationStore store) {
    var checkpoint =
        store.transaction(
            tx ->
                tx.query("SELECT * FROM credit_reporting_bootstrap WHERE singleton=1", List.of(), 1)
                    .getFirst());
    if (checkpoint.get("ready").equals("1")) return true;
    var kind = new Journal.Kind("economy:credit-reporting", "economy:bootstrap");
    var world = store.snapshot().worldId();
    String checkpointKey =
        checkpoint.get("phase")
            + "|"
            + checkpoint.get("cursor_sequence")
            + "|"
            + checkpoint.get("cursor_position")
            + "|"
            + checkpoint.get("owner_cursor");
    var operation = stable("source-bootstrap|" + world.value() + "|" + checkpointKey);
    var engine =
        new JournalEngine(
            store,
            (a, k) -> true,
            Map.of(
                kind,
                new JournalEngine.Registration(
                    1,
                    (intent, reads) -> {
                      var facts = new ArrayList<Journal.Record>();
                      Source source = null;
                      String phase = checkpoint.get("phase"),
                          ownerCursor = checkpoint.get("owner_cursor");
                      long sequence = Long.parseLong(checkpoint.get("cursor_sequence"));
                      int position = Integer.parseInt(checkpoint.get("cursor_position"));
                      CreditProfiles.Profile profile = null;
                      if (phase.equals("SOURCES")) {
                        var rows =
                            reads.query(
                                "SELECT sequence,position,record_version,payload FROM journal_records WHERE record_type='economy:a6/credit-facility' AND (sequence,position)>(?,?) ORDER BY sequence,position LIMIT 1",
                                List.of(Long.toString(sequence), Integer.toString(position)),
                                1);
                        if (rows.isEmpty()) {
                          phase = "FACILITIES";
                          ownerCursor = "";
                        } else {
                          var row = rows.getFirst();
                          if (!row.get("record_version").equals("1"))
                            throw new IllegalArgumentException(
                                "Unsupported historical credit snapshot");
                          sequence = Long.parseLong(row.get("sequence"));
                          position = Integer.parseInt(row.get("position"));
                          var facility = CreditFacilities.decodeSnapshot(row.get("payload"));
                          var companions =
                              reads.query(
                                  "SELECT record_type,record_version,payload FROM journal_records WHERE sequence=? ORDER BY position LIMIT 64",
                                  List.of(Long.toString(sequence)),
                                  64);
                          var records =
                              companions.stream()
                                  .map(
                                      r ->
                                          new Journal.Record(
                                              r.get("record_type"),
                                              Integer.parseInt(r.get("record_version")),
                                              r.get("payload")))
                                  .toList();
                          source = source(facility, effective(facility, records), sequence);
                          facts.add(new Journal.Record(SOURCE, 1, encode(source)));
                        }
                      } else if (phase.equals("FACILITIES")) {
                        var rows =
                            reads.query(
                                "SELECT facility_id,body FROM credit_facilities WHERE world_id=? AND facility_id>? ORDER BY facility_id LIMIT 1",
                                List.of(world.value().toString(), ownerCursor),
                                1);
                        if (rows.isEmpty()) {
                          phase = "PROFILES";
                          ownerCursor = "";
                        } else {
                          var facility =
                              CreditFacilities.decodeSnapshot(rows.getFirst().get("body"));
                          ownerCursor = rows.getFirst().get("facility_id");
                          var captured =
                              reads.query(
                                  "SELECT body FROM credit_reporting_sources WHERE world_id=? AND facility_id=? AND revision=?",
                                  List.of(
                                      world.value().toString(),
                                      facility.id().toString(),
                                      Long.toString(facility.revision())),
                                  1);
                          if (captured.isEmpty())
                            throw new StorageException(
                                StorageException.Kind.MIGRATION,
                                "CREDIT_UTILIZATION_CURRENT_SNAPSHOT_UNAVAILABLE");
                          var last = decode(captured.getFirst().get("body"));
                          if (!facility.world().equals(world)
                              || !facility.id().toString().equals(ownerCursor)
                              || !source(facility, last.effectiveAt(), last.sourceSequence())
                                  .equals(last))
                            throw new StorageException(
                                StorageException.Kind.MIGRATION,
                                "CREDIT_UTILIZATION_CURRENT_SNAPSHOT_CONFLICT");
                        }
                      } else {
                        var rows =
                            reads.query(
                                "SELECT owner_id FROM credit_profiles WHERE world_id=? AND owner_id>? ORDER BY owner_id LIMIT 1",
                                List.of(world.value().toString(), ownerCursor),
                                1);
                        if (rows.isEmpty()) phase = "DONE";
                        else {
                          ownerCursor = rows.getFirst().get("owner_id");
                          profile =
                              CreditProfiles.find(
                                      reads,
                                      new ca.emilybelnavis.mcmods.economy.api.PartyRef(
                                          world,
                                          new ca.emilybelnavis.mcmods.economy.api.PartyRef.Player(
                                              new PlayerId(UUID.fromString(ownerCursor)))))
                                  .orElseThrow();
                        }
                      }
                      facts.add(
                          new Journal.Record(
                              BOOTSTRAP,
                              1,
                              pack(
                                  List.of(
                                      world.value().toString(),
                                      phase,
                                      Long.toString(sequence),
                                      Integer.toString(position),
                                      ownerCursor))));
                      var captured = source;
                      var capturedProfile = profile;
                      String nextPhase = phase, nextOwner = ownerCursor;
                      long nextSequence = sequence;
                      int nextPosition = position;
                      return new JournalEngine.Commit(
                          facts,
                          new Journal.Result(1, "CREDIT_REPORT_SOURCE_BOOTSTRAP", phase),
                          tx -> {
                            if (captured != null) write(tx, captured);
                            if (capturedProfile != null)
                              tx.execute(
                                  "INSERT INTO credit_score_heads VALUES(?,?,?) ON CONFLICT(world_id,owner_id) DO UPDATE SET next_at=excluded.next_at",
                                  List.of(
                                      world.value().toString(),
                                      capturedProfile.owner().value().toString(),
                                      Long.toString(
                                          nextBoundary(
                                              capturedProfile,
                                              CreditProfiles.origin(tx::query).orElseThrow()))));
                            tx.execute(
                                "UPDATE credit_reporting_bootstrap SET phase=?,cursor_sequence=?,cursor_position=?,owner_cursor=?,ready=? WHERE singleton=1",
                                List.of(
                                    nextPhase,
                                    Long.toString(nextSequence),
                                    Integer.toString(nextPosition),
                                    nextOwner,
                                    nextPhase.equals("DONE") ? "1" : "0"));
                          });
                    })));
    var actor = new SystemActorId(stable("source-bootstrap-actor|" + world.value()));
    var outcome =
        engine.submit(
            new Journal.Intent(
                new OperationId(operation),
                new RequestId(operation),
                actor,
                kind,
                new Journal.CanonicalRequest(1, Map.of("checkpoint", checkpointKey)),
                null,
                null));
    if (outcome.status() != Journal.Status.COMMITTED)
      throw new IllegalStateException("Score source bootstrap unavailable");
    return false;
  }

  private static Source source(CreditFacilities.Facility f, long effective, long sequence) {
    return new Source(
        f.world(),
        f.owner(),
        f.id(),
        f.revision(),
        effective,
        sequence,
        f.currency(),
        f.limitMinor(),
        f.status() == CreditFacilities.Status.ACTIVE
            && f.access() != CreditFacilities.Access.CLOSED,
        f.lastDay(),
        f.purchase().debtMinor(),
        f.purchase().residualMajor(),
        f.purchase().apr(),
        f.advance().debtMinor(),
        f.advance().residualMajor(),
        f.advance().apr());
  }

  private static long effective(CreditFacilities.Facility facility, List<Journal.Record> records) {
    Long effective = facility.revision() == 1 ? facility.anchor() : null;
    for (var record : records) {
      if (record.type().equals(CreditFacilities.EVENT_FACT)) {
        var fields = unpack(record.payload(), 4);
        if (fields.get(1).equals(facility.id().toString()))
          effective = Long.parseLong(fields.get(2));
      } else if (record.type().equals(CreditFacilities.STATEMENT_FACT)) {
        var statement = CreditFacilities.decodeStatementSnapshot(record.payload());
        if (statement.facility().equals(facility.id()))
          effective = statement.statement().closedAt();
      } else if (record.type().equals(DebtRecords.CASE_FACT)) {
        var debt = DebtRecords.decodeCase(record.payload());
        if (debt.kind() == DebtRecords.Kind.CARD && debt.facility().equals(facility.id())) {
          effective = debt.defaultAt();
          for (var accounting : records)
            if (accounting.type().equals("economy:a6/debt-accounting")) {
              var fields = accounting.payload().split("\\|", -1);
              if (fields.length == 6 && fields[1].equals(debt.id().toString()))
                effective = Long.parseLong(fields[3]);
            }
        }
      }
    }
    if (effective == null)
      throw new StorageException(
          StorageException.Kind.MIGRATION, "CREDIT_UTILIZATION_EVENT_TIME_UNAVAILABLE");
    if (effective < facility.lastDay())
      throw new IllegalArgumentException("Credit source effective time before accrual");
    return effective;
  }

  private static void write(FoundationStore.Transaction tx, Source source) {
    var prior =
        tx.query(
            "SELECT body FROM credit_reporting_sources WHERE world_id=? AND facility_id=? AND revision=?",
            List.of(
                source.world().value().toString(),
                source.facility().toString(),
                Long.toString(source.revision())),
            1);
    if (!prior.isEmpty()) {
      var existing = decode(prior.getFirst().get("body"));
      if (!sameSource(existing, source))
        throw new IllegalArgumentException("Conflicting utilization source");
      return;
    }
    var previous =
        tx.query(
            "SELECT revision,body FROM credit_reporting_sources WHERE world_id=? AND facility_id=? ORDER BY revision DESC LIMIT 1",
            List.of(source.world().value().toString(), source.facility().toString()),
            1);
    long expected =
        previous.isEmpty()
            ? 1
            : Math.addExact(Long.parseLong(previous.getFirst().get("revision")), 1);
    if (source.revision() != expected)
      throw new StorageException(
          StorageException.Kind.MIGRATION, "CREDIT_UTILIZATION_SOURCE_REVISION_GAP");
    if (!previous.isEmpty()) {
      var before = decode(previous.getFirst().get("body"));
      if (!before.owner().equals(source.owner())
          || before.currency() != source.currency()
          || source.effectiveAt() < before.effectiveAt())
        throw new StorageException(
            StorageException.Kind.MIGRATION, "CREDIT_UTILIZATION_SOURCE_IDENTITY_OR_TIME_CONFLICT");
    }
    tx.execute(
        "INSERT INTO credit_reporting_sources VALUES(?,?,?,?,?,?,?)",
        List.of(
            source.world().value().toString(),
            source.owner().value().toString(),
            source.facility().toString(),
            Long.toString(source.revision()),
            Long.toString(source.effectiveAt()),
            Long.toString(source.sourceSequence()),
            encode(source)));
  }

  private static boolean sameSource(Source a, Source b) {
    return new Source(
            a.world(),
            a.owner(),
            a.facility(),
            a.revision(),
            a.effectiveAt(),
            0,
            a.currency(),
            a.limitMinor(),
            a.active(),
            a.lastDay(),
            a.purchaseMinor(),
            a.purchaseResidual(),
            a.purchaseApr(),
            a.advanceMinor(),
            a.advanceResidual(),
            a.advanceApr())
        .equals(
            new Source(
                b.world(),
                b.owner(),
                b.facility(),
                b.revision(),
                b.effectiveAt(),
                0,
                b.currency(),
                b.limitMinor(),
                b.active(),
                b.lastDay(),
                b.purchaseMinor(),
                b.purchaseResidual(),
                b.purchaseApr(),
                b.advanceMinor(),
                b.advanceResidual(),
                b.advanceApr()));
  }

  public static String encode(Source s) {
    return pack(
        List.of(
            s.world().value().toString(),
            s.owner().value().toString(),
            s.facility().toString(),
            Long.toString(s.revision()),
            Long.toString(s.effectiveAt()),
            Long.toString(s.sourceSequence()),
            s.currency().name(),
            Long.toString(s.limitMinor()),
            s.active() ? "1" : "0",
            Long.toString(s.lastDay()),
            Long.toString(s.purchaseMinor()),
            s.purchaseResidual().toPlainString(),
            s.purchaseApr().toPlainString(),
            Long.toString(s.advanceMinor()),
            s.advanceResidual().toPlainString(),
            s.advanceApr().toPlainString()));
  }

  public static Source decode(String body) {
    var f = unpack(body, 16);
    var result =
        new Source(
            new WorldId(UUID.fromString(f.get(0))),
            new PlayerId(UUID.fromString(f.get(1))),
            UUID.fromString(f.get(2)),
            Long.parseLong(f.get(3)),
            Long.parseLong(f.get(4)),
            Long.parseLong(f.get(5)),
            EconomyCurrency.valueOf(f.get(6)),
            Long.parseLong(f.get(7)),
            f.get(8).equals("1"),
            Long.parseLong(f.get(9)),
            Long.parseLong(f.get(10)),
            new BigDecimal(f.get(11)),
            new BigDecimal(f.get(12)),
            Long.parseLong(f.get(13)),
            new BigDecimal(f.get(14)),
            new BigDecimal(f.get(15)));
    if (!encode(result).equals(body))
      throw new IllegalArgumentException("Noncanonical utilization source");
    return result;
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return CreditScoreRuntime.register(
        catalog
            .with(new RecordCatalog.Key(SOURCE, 1), CreditReporting::decode)
            .with(
                new RecordCatalog.Key(BOOTSTRAP, 1),
                body -> {
                  var f = unpack(body, 5);
                  UUID.fromString(f.get(0));
                  if (!Set.of("SOURCES", "FACILITIES", "PROFILES", "DONE").contains(f.get(1))
                      || Long.parseLong(f.get(2)) < 0
                      || Integer.parseInt(f.get(3)) < -1
                      || Integer.parseInt(f.get(3)) > 63)
                    throw new IllegalArgumentException("Score bootstrap checkpoint");
                  if (!f.get(4).isEmpty()) UUID.fromString(f.get(4));
                }));
  }

  static String pack(List<String> fields) {
    return String.join(
        "|",
        fields.stream()
            .map(
                s ->
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(s.getBytes(StandardCharsets.UTF_8)))
            .toList());
  }

  static List<String> unpack(String body, int count) {
    if (body.length() > 16384) throw new IllegalArgumentException("Credit reporting record bound");
    var fields =
        Arrays.stream(body.split("\\|", -1))
            .map(s -> new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8))
            .toList();
    if (fields.size() != count || !pack(fields).equals(body))
      throw new IllegalArgumentException("Credit reporting encoding");
    return fields;
  }

  static UUID stable(String value) {
    return UUID.nameUUIDFromBytes(
        ("economy:score-reporting:v1|" + value).getBytes(StandardCharsets.UTF_8));
  }
}
