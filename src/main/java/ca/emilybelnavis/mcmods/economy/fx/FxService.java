package ca.emilybelnavis.mcmods.economy.fx;

import static ca.emilybelnavis.mcmods.economy.fx.FxFacts.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.EconomicClock;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Worker-side journal handlers and bounded reads over immutable, indexed FX facts. */
public final class FxService {
  public static final String SYSTEM = "economy:fx";
  public static final Journal.Kind OPEN = new Journal.Kind(SYSTEM, "economy:fx-open");
  public static final Journal.Kind EDIT = new Journal.Kind(SYSTEM, "economy:fx-edit");
  static final long DAY_MILLIS = 86_400_000;
  private static final String PUBLICATIONS =
      "SELECT payload FROM journal_records WHERE record_type='" + PUBLICATION + "'";

  private FxService() {}

  public static EconomicClock.SystemDefinition definition() {
    return new EconomicClock.SystemDefinition(
        SYSTEM,
        DAY_MILLIS,
        0,
        1,
        Map.of(
            1,
                new EconomicClock.Version(
                    1,
                    "fx-daily-v1",
                    update -> {
                      throw new IllegalStateException("FX requires detached publication inputs");
                    }),
            2,
                new EconomicClock.Version(
                    2,
                    "fx-daily-v2-chf",
                    update -> {
                      throw new IllegalStateException("FX requires detached publication inputs");
                    }),
            3,
                new EconomicClock.Version(
                    3,
                    "fx-daily-v3-eight-decimal",
                    update -> {
                      throw new IllegalStateException("FX requires detached publication inputs");
                    })),
        Set.of());
  }

  public static EconomicClock.DeferredSystem deferred() {
    return new EconomicClock.DeferredSystem(
        reads -> origin(reads::query),
        FxService::prepare,
        reads -> {
          var opening =
              readPublication(
                  reads::query, PUBLICATIONS + " ORDER BY " + DAY_KEY + " LIMIT 1", List.of());
          if (opening == null) return 1;
          var facts =
              reads.query(
                  "SELECT record_version FROM journal_records WHERE record_type=? ORDER BY sequence LIMIT 1",
                  List.of(PUBLICATION),
                  1);
          return !facts.isEmpty() && facts.getFirst().get("record_version").equals("3")
              ? 3
              : opening.version();
        });
  }

  public static Long origin(JournalEngine.ReadView reads) {
    var opening =
        readPublication(reads, PUBLICATIONS + " ORDER BY " + DAY_KEY + " LIMIT 1", List.of());
    return opening == null
        ? null
        : opening.day().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
  }

  /** Mathematical/display compatibility view; its UTC date must never be used for freshness. */
  public static Publication latest(JournalEngine.ReadView reads) {
    var value = snapshot(reads);
    return value == null ? null : value.publication();
  }

  /** Explicit frozen daily family, including its original unique-day history. */
  public static Publication latestDaily(JournalEngine.ReadView reads) {
    return readPublication(
        reads, PUBLICATIONS + " ORDER BY " + DAY_KEY + " DESC LIMIT 1", List.of());
  }

  public static EffectiveFxSnapshot snapshot(JournalEngine.ReadView reads) {
    return snapshot(reads, Long.MAX_VALUE);
  }

  public static EffectiveFxSnapshot snapshot(
      JournalEngine.ReadView reads, long exclusiveSourceSequence) {
    return at(reads, Long.MAX_VALUE, exclusiveSourceSequence);
  }

  public static EffectiveFxSnapshot snapshot(JournalEngine.ReadView reads, UUID id) {
    return snapshot(reads, id, Long.MAX_VALUE);
  }

  public static EffectiveFxSnapshot snapshot(
      JournalEngine.ReadView reads, UUID id, long exclusiveSourceSequence) {
    var source = source(reads, id, exclusiveSourceSequence);
    return source == null ? null : source.snapshot();
  }

  public static EffectiveFxSnapshot snapshotBefore(
      JournalEngine.ReadView reads, UUID id, long exclusiveSourceSequence) {
    return snapshot(reads, id, exclusiveSourceSequence);
  }

  public static EffectiveFxFacts.Source source(
      JournalEngine.ReadView reads, UUID id, long exclusiveSourceSequence) {
    if (hasEffectiveTable(reads)) {
      var rows =
          reads.query(
              "SELECT e.payload,e.source_sequence,r.record_version FROM effective_fx_snapshots e JOIN journal_records r ON r.sequence=e.source_sequence AND r.record_type='economy:a8/effective-fx' WHERE e.id=? AND e.source_sequence<?",
              List.of(id.toString(), Long.toString(exclusiveSourceSequence)),
              1);
      if (!rows.isEmpty())
        return new EffectiveFxFacts.Source(
            EffectiveFxFacts.decode(rows.getFirst().get("payload")),
            Long.parseLong(rows.getFirst().get("source_sequence")),
            false,
            Integer.parseInt(rows.getFirst().get("record_version")));
    }
    var rows =
        reads.query(
            "SELECT payload,record_version,sequence FROM journal_records WHERE record_type=? AND substr(payload,16,36)=? AND sequence<? LIMIT 1",
            List.of(PUBLICATION, id.toString(), Long.toString(exclusiveSourceSequence)),
            1);
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    return new EffectiveFxFacts.Source(
        EffectiveFxSnapshot.legacy(
            world(reads),
            Publication.decode(row.get("payload"), Integer.parseInt(row.get("record_version")))),
        Long.parseLong(row.get("sequence")),
        true,
        Integer.parseInt(row.get("record_version")));
  }

  public static EffectiveFxSnapshot at(JournalEngine.ReadView reads, long at) {
    return at(reads, at, Long.MAX_VALUE);
  }

  public static EffectiveFxSnapshot at(
      JournalEngine.ReadView reads, long at, long exclusiveSourceSequence) {
    if (at < 0) throw new IllegalArgumentException("FX effective time");
    EffectiveFxSnapshot intraday = null;
    if (hasEffectiveTable(reads)) {
      var rows =
          reads.query(
              "SELECT payload FROM effective_fx_snapshots WHERE effective_ms<=? AND source_sequence<? ORDER BY effective_ms DESC,id DESC LIMIT 1",
              List.of(Long.toString(at), Long.toString(exclusiveSourceSequence)),
              1);
      if (!rows.isEmpty()) intraday = EffectiveFxFacts.decode(rows.getFirst().get("payload"));
    }
    String upper = at == Long.MAX_VALUE ? "9999-12-31" : dayKey(utcDay(Instant.ofEpochMilli(at)));
    var rows =
        reads.query(
            "SELECT payload,record_version FROM journal_records WHERE record_type=? AND "
                + DAY_KEY
                + "<=? AND sequence<? ORDER BY "
                + DAY_KEY
                + " DESC LIMIT 1",
            List.of(PUBLICATION, upper, Long.toString(exclusiveSourceSequence)),
            1);
    var daily =
        rows.isEmpty()
            ? null
            : EffectiveFxSnapshot.legacy(
                world(reads),
                Publication.decode(
                    rows.getFirst().get("payload"),
                    Integer.parseInt(rows.getFirst().get("record_version"))));
    return intraday != null
            && (daily == null || intraday.effectiveMillis() >= daily.effectiveMillis())
        ? intraday
        : daily;
  }

  public static long effectiveMillis(JournalEngine.ReadView reads, UUID id) {
    var value = snapshot(reads, id);
    if (value == null)
      throw new IllegalArgumentException("Original FX snapshot unavailable: " + id);
    return value.effectiveMillis();
  }

  public static long effectiveMillis(JournalEngine.ReadView reads, Publication value) {
    if (value == null) throw new IllegalArgumentException("FX unavailable");
    return effectiveMillis(reads, value.updateId());
  }

  public static Long nextEffectiveMillis(
      JournalEngine.ReadView reads, long afterExclusive, long beforeExclusive) {
    if (afterExclusive < 0 || beforeExclusive <= afterExclusive) return null;
    Long next = null;
    if (hasEffectiveTable(reads)) {
      var rows =
          reads.query(
              "SELECT effective_ms FROM effective_fx_snapshots WHERE effective_ms>? AND effective_ms<? ORDER BY effective_ms LIMIT 1",
              List.of(Long.toString(afterExclusive), Long.toString(beforeExclusive)),
              1);
      if (!rows.isEmpty()) next = Long.parseLong(rows.getFirst().get("effective_ms"));
    }
    var rows =
        reads.query(
            "SELECT payload FROM journal_records WHERE record_type=? AND "
                + DAY_KEY
                + ">? ORDER BY "
                + DAY_KEY
                + " LIMIT 1",
            List.of(PUBLICATION, dayKey(utcDay(Instant.ofEpochMilli(afterExclusive)))),
            1);
    if (!rows.isEmpty()) {
      long time =
          Publication.decode(rows.getFirst().get("payload"))
              .day()
              .atStartOfDay(ZoneOffset.UTC)
              .toInstant()
              .toEpochMilli();
      if (time < beforeExclusive && (next == null || time < next)) next = time;
    }
    return next;
  }

  private static WorldId world(JournalEngine.ReadView reads) {
    return new WorldId(
        UUID.fromString(
            reads
                .query("SELECT world_id FROM foundation_metadata WHERE singleton=1", List.of(), 1)
                .getFirst()
                .get("world_id")));
  }

  private static boolean hasEffectiveTable(JournalEngine.ReadView reads) {
    return !reads
        .query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='effective_fx_snapshots'",
            List.of(),
            1)
        .isEmpty();
  }

  public static Publication publication(JournalEngine.ReadView reads, LocalDate day) {
    return readPublication(
        reads, PUBLICATIONS + " AND " + DAY_KEY + "=? LIMIT 1", List.of(dayKey(day)));
  }

  private static Publication readPublication(
      JournalEngine.ReadView reads, String sql, List<String> parameters) {
    var rows = reads.query(sql, parameters, 1);
    return rows.isEmpty() ? null : Publication.decode(rows.getFirst().get("payload"));
  }

  public static Policy policy(JournalEngine.ReadView reads, LocalDate day) {
    var rows =
        reads.query(
            "SELECT payload FROM journal_records WHERE record_type='"
                + POLICY
                + "' AND "
                + DAY_KEY
                + "<=? ORDER BY "
                + DAY_KEY
                + " DESC,sequence DESC LIMIT 1",
            List.of(dayKey(day)),
            1);
    return rows.isEmpty() ? null : Policy.decode(rows.getFirst().get("payload"));
  }

  static Policy nextPolicy(JournalEngine.ReadView reads, LocalDate afterDay) {
    var rows =
        reads.query(
            "SELECT "
                + DAY_KEY
                + " AS day FROM journal_records WHERE record_type='"
                + POLICY
                + "' AND "
                + DAY_KEY
                + ">? ORDER BY "
                + DAY_KEY
                + " LIMIT 1",
            List.of(dayKey(afterDay)),
            1);
    return rows.isEmpty() ? null : policy(reads, LocalDate.parse(rows.getFirst().get("day")));
  }

  private static EconomicClock.Calculation prepare(
      EconomicClock.Update update, EconomicClock.Queries reads) {
    if (update.version() != 1 && update.version() != 2 && update.version() != 3)
      throw new IllegalArgumentException("Unsupported FX simulation version");
    LocalDate day = utcDay(Instant.ofEpochMilli(update.endMillis()));
    var previous = publication(reads::query, day.minusDays(1));
    var policy = policy(reads::query, day);
    if (previous == null
        || policy == null
        || previous.reference() != policy.reference()
        || publication(reads::query, day) != null) {
      throw new StorageException(
          StorageException.Kind.SCHEMA, "FX publication or policy checkpoint mismatch");
    }
    if (update.version() == 1 && (previous.version() != 1 || policy.version() != 1)
        || previous.version() != policy.version())
      throw new StorageException(
          StorageException.Kind.SCHEMA, "FX historical membership/version mismatch");
    return new Calculation(update, day, previous, policy);
  }

  private record Calculation(
      EconomicClock.Update update, LocalDate day, Publication previous, Policy policy)
      implements EconomicClock.Calculation {
    @Override
    public EconomicClock.Output compute() {
      var rates =
          update.version() == 3
              ? FxMath.advanceEight(
                  previous.reference(), previous.rates(), policy.settings(), update.randomSeed())
              : FxMath.advance(
                  previous.reference(), previous.rates(), policy.settings(), update.randomSeed());
      if (update.version() >= 2 && previous.version() == 1) {
        var expandedRates = new EnumMap<EconomyCurrency, BigDecimal>(EconomyCurrency.class);
        expandedRates.putAll(rates);
        BigDecimal baseline =
            rates
                .get(EconomyCurrency.CAD)
                .multiply(new BigDecimal("1.6"))
                .setScale(FxMath.SCALE, RoundingMode.HALF_EVEN);
        if (update.version() == 3)
          baseline = baseline.setScale(8, RoundingMode.DOWN).setScale(FxMath.SCALE);
        var settings = new EnumMap<EconomyCurrency, FxMath.Settings>(EconomyCurrency.class);
        settings.putAll(policy.settings());
        settings.put(EconomyCurrency.CHF, new FxMath.Settings(baseline, 3_000, 100_000, 50_000));
        expandedRates.put(EconomyCurrency.CHF, baseline);
        UUID policyId =
            UUID.nameUUIDFromBytes(
                ("economy:chf-opening-policy|" + update.id()).getBytes(StandardCharsets.UTF_8));
        var activation = new Policy(day, policyId, previous.reference(), settings);
        var next = new Publication(day, previous.reference(), update.id(), policyId, expandedRates);
        return new EconomicClock.Output(
            next.encode(),
            tx -> {},
            List.of(activation.record(), publicationRecord(next, update.version())));
      }
      var next = new Publication(day, previous.reference(), update.id(), policy.id(), rates);
      return new EconomicClock.Output(
          next.encode(), tx -> {}, List.of(publicationRecord(next, update.version())));
    }
  }

  /** Trusted selection registration for the future chooser or an isolated fixture. */
  public static JournalEngine.Registration opening(Clock wall) {
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          if (latestDaily(reads) != null) return reject("FX_ALREADY_OPEN");
          var day = utcDay(wall.instant());
          EconomyCurrency reference;
          try {
            reference = EconomyCurrency.fromCode(intent.request().fields().get("currency"));
          } catch (IllegalArgumentException invalid) {
            return reject("FX_INVALID_CURRENCY");
          }
          var id = intent.operationId().value();
          var policy = new Policy(day, id, reference, FxMath.defaults(reference));
          var publication =
              new Publication(
                  day, reference, id, id, FxMath.eightDecimalRates(FxMath.opening(reference)));
          return new JournalEngine.Commit(
              List.of(policy.record(), publicationRecord(publication, 3)),
              new Journal.Result(1, "FX_OPENED", publication.encode()),
              tx -> {});
        });
  }

  /** Bind this registration only behind authenticated, server-derived admin authorization. */
  public static JournalEngine.Registration editing(Clock wall) {
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          var effectiveDay = utcDay(wall.instant()).plusDays(1);
          if (ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes.activation(
                      reads, world(reads))
                  .isPresent()
              || ca.emilybelnavis.mcmods.economy.clock.FixedRetirements.read(reads, SYSTEM) != null)
            return reject("FX_TAKEOVER_CONFIGURED");
          var current = latestDaily(reads);
          if (current == null) return reject("FX_UNINITIALIZED");
          if (!effectiveDay.isAfter(current.day())) return reject("FX_BOUNDARY_PASSED");
          var previous = policy(reads, effectiveDay);
          if (previous == null || previous.reference() != current.reference()) {
            throw new StorageException(
                StorageException.Kind.SCHEMA, "FX policy checkpoint mismatch");
          }
          var fields = intent.request().fields();
          var settings = new EnumMap<>(previous.settings());
          try {
            var currency = EconomyCurrency.fromCode(fields.get("currency"));
            if (currency == current.reference()) return reject("FX_FIXED_REFERENCE");
            if (!current.rates().containsKey(currency)) return reject("FX_RATE_UNAVAILABLE");
            String baseline = fields.getOrDefault("baseline", "");
            if (!baseline.matches("[0-9]{1,7}(\\.[0-9]{1,12})?"))
              return reject("FX_INVALID_POLICY");
            settings.put(
                currency,
                new FxMath.Settings(
                    new BigDecimal(baseline),
                    Integer.parseInt(fields.get("variation")),
                    Integer.parseInt(fields.get("pull")),
                    Integer.parseInt(fields.get("bounds"))));
          } catch (IllegalArgumentException invalid) {
            return reject("FX_INVALID_POLICY");
          }
          var policy =
              new Policy(effectiveDay, intent.operationId().value(), current.reference(), settings);
          return new JournalEngine.Commit(
              List.of(policy.record()),
              new Journal.Result(1, "FX_POLICY_SAVED", effectiveDay.toString()),
              tx -> {});
        });
  }

  /** Published availability, not registry membership; legacy days deliberately lack CHF. */
  public static List<EconomyCurrency> availableCurrencies(JournalEngine.ReadView reads) {
    var current = latest(reads);
    return current == null
        ? List.of()
        : EconomyCurrency.all().stream().filter(current.rates()::containsKey).toList();
  }

  /** After legacy backlog is current, install eight-place arithmetic at a future UTC boundary. */
  public static void prepareChfActivation(FoundationStore store, EconomicClock clock, Clock wall) {
    Long boundary =
        store.transaction(
            tx -> {
              if (ca.emilybelnavis.mcmods.economy.clock.FixedRetirements.read(tx::query, SYSTEM)
                      != null
                  || ca.emilybelnavis.mcmods.economy.simulation.prime.CurrencyPrimes.activation(
                          tx::query, store.snapshot().worldId())
                      .isPresent()
                  || latestDaily(tx::query) == null) return null;
              if (!tx.query(
                      "SELECT version FROM economic_versions WHERE system_id=? AND version=3 LIMIT 1",
                      List.of(SYSTEM),
                      1)
                  .isEmpty()) return null;
              long next =
                  utcDay(wall.instant())
                      .plusDays(1)
                      .atStartOfDay(ZoneOffset.UTC)
                      .toInstant()
                      .toEpochMilli();
              var scheduled =
                  tx.query(
                      "SELECT MAX(effective_end_ms) AS latest FROM economic_versions WHERE system_id=?",
                      List.of(SYSTEM),
                      1);
              String latest = scheduled.isEmpty() ? null : scheduled.getFirst().get("latest");
              if (latest != null)
                next = Math.max(next, Math.addExact(Long.parseLong(latest), DAY_MILLIS));
              return next;
            });
    if (boundary != null) clock.scheduleVersion(SYSTEM, 3, boundary);
  }

  private static Journal.Record publicationRecord(Publication publication, int arithmeticVersion) {
    return arithmeticVersion == 3
        ? new Journal.Record(PUBLICATION, 3, publication.encode())
        : publication.record();
  }

  public static LocalDate chfActivationDay(JournalEngine.ReadView reads) {
    var opening =
        readPublication(reads, PUBLICATIONS + " ORDER BY " + DAY_KEY + " LIMIT 1", List.of());
    if (opening != null && opening.version() == 2) return opening.day();
    var rows =
        reads.query(
            "SELECT effective_end_ms FROM economic_versions WHERE system_id=? AND version IN (2,3) ORDER BY effective_end_ms LIMIT 1",
            List.of(SYSTEM),
            1);
    return rows.isEmpty()
        ? null
        : LocalDate.ofEpochDay(
            Long.parseLong(rows.getFirst().get("effective_end_ms")) / DAY_MILLIS);
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }

  private static LocalDate utcDay(Instant instant) {
    return LocalDate.ofInstant(instant, ZoneOffset.UTC);
  }
}
