package ca.emilybelnavis.mcmods.economy.credit;

import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.FinancialCalendar;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Worker-only composition contracts. Nothing here installs an issuance/application endpoint. */
public final class CreditProfiles {
  public static final int VERSION = 1, INITIAL_SCORE = 650, MAX_PAGE = 16;
  public static final Journal.Kind FIRST_ISSUANCE =
      new Journal.Kind("economy:credit-profile", "economy:first-credit-issuance");
  public static final Journal.Kind INQUIRY =
      new Journal.Kind("economy:credit-profile", "economy:accepted-inquiry");
  public static final String PROFILE_FACT = "economy:credit-profile",
      HISTORY_FACT = "economy:credit-score-history",
      INQUIRY_FACT = "economy:credit-inquiry";

  private CreditProfiles() {}

  public record Profile(
      WorldId world,
      PlayerId owner,
      int score,
      long firstIssuedAt,
      UUID firstFacility,
      InstitutionCatalog.InstitutionId firstIssuer,
      EconomyCurrency starterCurrency,
      long starterMinor,
      int policyVersion,
      long revision,
      long lastBoundary,
      long reportingFrontier,
      String inputFingerprint) {
    public Profile {
      Objects.requireNonNull(world);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(firstFacility);
      InstitutionCatalog.require(firstIssuer);
      Objects.requireNonNull(starterCurrency);
      fingerprint(inputFingerprint);
      if (score < 300
          || score > 900
          || firstIssuedAt < 0
          || starterMinor != Math.multiplyExact(2500L, starterCurrency.minorUnitsPerMajor())
          || policyVersion != VERSION
          || revision < 1
          || lastBoundary < -1
          || reportingFrontier < 0
          || revision == 1 && (score != 650 || lastBoundary != -1 || reportingFrontier != 0)
          || revision > 1 && lastBoundary <= firstIssuedAt)
        throw new IllegalArgumentException("Credit profile fields");
    }
  }

  public record History(
      long id,
      WorldId world,
      PlayerId owner,
      long boundary,
      String kind,
      int policyVersion,
      long profileRevision,
      int score,
      ScorePolicy.Components components,
      String inputFingerprint,
      String inputSnapshot,
      List<String> reasons,
      UUID periodId,
      long reportingFrontier) {
    public History {
      Objects.requireNonNull(world);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(components);
      Objects.requireNonNull(periodId);
      fingerprint(inputFingerprint);
      bounded(inputSnapshot, 8192);
      reasons = List.copyOf(reasons);
      reasonText(reasons);
      if (id < 0
          || boundary < 0
          || !Set.of("INITIAL", "SCORE").contains(kind)
          || policyVersion != VERSION
          || profileRevision < 1
          || score < 300
          || score > 900
          || components.baseline() != 650
          || reportingFrontier < 0
          || !hash(inputSnapshot).equals(inputFingerprint))
        throw new IllegalArgumentException("Credit score history");
      int computed =
          Math.clamp(
              650
                  + components.payment()
                  + components.utilization()
                  + components.age()
                  - components.inquiry()
                  - components.defaults(),
              300,
              900);
      if (kind.equals("INITIAL")
              && (score != 650
                  || profileRevision != 1
                  || reportingFrontier != 0
                  || !components.equals(new ScorePolicy.Components(650, 0, 0, 0, 0, 0)))
          || kind.equals("SCORE") && (profileRevision < 2 || reportingFrontier < boundary)
          || score != computed
          || components.payment() < -90
          || components.payment() > 100
          || !Set.of(-90, -60, -30, 0, 30, 60).contains(components.utilization())
          || components.age() < 0
          || components.age() > 90
          || components.age() % 2 != 0
          || components.inquiry() < 0
          || components.inquiry() > 30
          || components.inquiry() % 5 != 0
          || components.defaults() < 0
          || components.defaults() > 360
          || components.defaults() % 30 != 0)
        throw new IllegalArgumentException("Credit score baseline");
    }
  }

  public record HistoryPage(List<History> entries, Long nextCursor) {
    public HistoryPage {
      entries = List.copyOf(entries);
    }
  }

  public record InitialRequest(
      WorldId world,
      PlayerId owner,
      UUID session,
      InstitutionCatalog.InstitutionId issuer,
      UUID facility,
      EconomyCurrency currency,
      long denominationRevision,
      long calendarOrigin) {
    public InitialRequest {
      Objects.requireNonNull(world);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(session);
      InstitutionCatalog.require(issuer);
      Objects.requireNonNull(facility);
      Objects.requireNonNull(currency);
      if (denominationRevision < 0 || calendarOrigin < 0)
        throw new IllegalArgumentException("Initial credit revisions");
    }

    public Journal.CanonicalRequest request() {
      return new Journal.CanonicalRequest(
          1,
          Map.of(
              "world",
              world.value().toString(),
              "economy:owner",
              Journal.actorKey(owner),
              "session",
              session.toString(),
              "issuer",
              issuer.key(),
              "facility",
              facility.toString(),
              "currency",
              currency.name(),
              "denomination_revision",
              Long.toString(denominationRevision),
              "calendar_origin",
              Long.toString(calendarOrigin)));
    }
  }

  public record Starter(
      WorldId world,
      PlayerId owner,
      InstitutionCatalog.InstitutionId issuer,
      UUID facility,
      Money limit,
      int score,
      long firstIssuedAt,
      UUID operation) {}

  @FunctionalInterface
  public interface ActualIssuance {
    /**
     * A6-08 must return its real facility/custody commit with CREDIT_CARD_ISSUED and the facility
     * UUID payload.
     */
    Decision prepare(Journal.Intent intent, ReadView reads, Starter starter) throws Exception;
  }

  public static Registration firstIssuance(
      PartyRef owner, InitialRequest request, LongSupplier processingClock, ActualIssuance actual) {
    Objects.requireNonNull(processingClock);
    return new Registration(
        1,
        (intent, reads) -> {
          if (!owns(owner, request.world(), request.owner())
              || !worldMatches(reads, owner.world())
              || !intent.actor().equals(request.owner())
              || !intent.kind().equals(FIRST_ISSUANCE)
              || !request.request().equals(intent.request()))
            return reject("CREDIT_INITIAL_REQUEST");
          if (actual == null) return unavailable("CREDIT_ISSUANCE_UNAVAILABLE");
          if (find(reads, owner).isPresent()) return reject("CREDIT_ALREADY_ESTABLISHED");
          var denomination = RebaseSettlement.denomination(reads).orElse(null);
          if (denomination == null
              || denomination.revision() != request.denominationRevision()
              || denomination.currency() != request.currency()) return reject("DENOMINATION_STALE");
          if (!origin(reads).filter(o -> o == request.calendarOrigin()).isPresent())
            return unavailable("CREDIT_CALENDAR_UNAVAILABLE");
          var enabled =
              reads.query(
                  "SELECT enabled FROM institution_parties WHERE world_id=? AND institution_key=?",
                  List.of(owner.world().value().toString(), request.issuer().key()),
                  1);
          if (!enabled.isEmpty() && !enabled.getFirst().get("enabled").equals("1"))
            return reject("BANK_UNAVAILABLE");
          long now = processingClock.getAsLong();
          if (now < request.calendarOrigin()) return unavailable("CREDIT_CLOCK_STALE");
          var allowance =
              new Starter(
                  owner.world(),
                  request.owner(),
                  request.issuer(),
                  request.facility(),
                  new Money(
                      request.currency(),
                      Math.multiplyExact(2500L, request.currency().minorUnitsPerMajor())),
                  650,
                  now,
                  intent.operationId().value());
          var actualDecision = actual.prepare(intent, reads, allowance);
          if (!(actualDecision instanceof Commit issued)) return actualDecision;
          if (!issued.result().code().equals("CREDIT_CARD_ISSUED")
              || !issued.result().payload().equals(request.facility().toString()))
            return unavailable("CREDIT_ISSUANCE_NOT_ACTUAL");
          String snapshot =
              "initial-v1|"
                  + owner.world().value()
                  + "|"
                  + request.owner().value()
                  + "|"
                  + request.issuer().key()
                  + "|"
                  + request.facility()
                  + "|"
                  + now
                  + "|"
                  + request.currency()
                  + "|"
                  + allowance.limit().minorUnits()
                  + "|"
                  + request.session();
          var profile =
              new Profile(
                  owner.world(),
                  request.owner(),
                  650,
                  now,
                  request.facility(),
                  request.issuer(),
                  request.currency(),
                  allowance.limit().minorUnits(),
                  VERSION,
                  1,
                  -1,
                  0,
                  hash(snapshot));
          var period =
              UUID.nameUUIDFromBytes(
                  ("economy:first-credit-profile:v1|"
                          + owner.world().value()
                          + "|"
                          + request.owner().value())
                      .getBytes(StandardCharsets.UTF_8));
          var history =
              new History(
                  0,
                  profile.world(),
                  profile.owner(),
                  now,
                  "INITIAL",
                  VERSION,
                  1,
                  650,
                  new ScorePolicy.Components(650, 0, 0, 0, 0, 0),
                  profile.inputFingerprint(),
                  snapshot,
                  List.of("FIRST_ACTUAL_CREDIT_ISSUANCE"),
                  period,
                  0);
          var records = new ArrayList<>(issued.records());
          records.add(new Journal.Record(PROFILE_FACT, 1, encode(profile)));
          records.add(new Journal.Record(HISTORY_FACT, 1, encode(history)));
          if (!fits(records)) return reject("CREDIT_ISSUANCE_BUDGET");
          return new Commit(
              records,
              issued.result(),
              tx -> {
                issued.projections().apply(tx);
                tx.execute(
                    "INSERT INTO credit_profiles VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    values(profile));
                writeHistory(tx, history);
              });
        });
  }

  public enum ApplicationKind {
    CARD,
    LOAN
  }

  public record InquiryRequest(
      WorldId world,
      PlayerId owner,
      InstitutionCatalog.InstitutionId issuer,
      UUID application,
      ApplicationKind kind,
      long submittedAt,
      long calendarOrigin,
      boolean firstIssuance) {
    public InquiryRequest {
      Objects.requireNonNull(world);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(kind);
      if (!issuer.equals(InstitutionCatalog.IRONBRIDGE) || kind != ApplicationKind.LOAN)
        InstitutionCatalog.require(issuer);
      Objects.requireNonNull(application);
      if (submittedAt < calendarOrigin || calendarOrigin < 0)
        throw new IllegalArgumentException("Inquiry instant");
    }

    public Journal.CanonicalRequest request() {
      return new Journal.CanonicalRequest(
          1,
          Map.of(
              "world",
              world.value().toString(),
              "economy:owner",
              Journal.actorKey(owner),
              "issuer",
              issuer.key(),
              "application",
              application.toString(),
              "application_kind",
              kind.name(),
              "submitted_at",
              Long.toString(submittedAt),
              "calendar_origin",
              Long.toString(calendarOrigin),
              "first_issuance",
              Boolean.toString(firstIssuance)));
    }
  }

  @FunctionalInterface
  public interface AcceptedUnderwriting {
    Decision prepare(Journal.Intent intent, ReadView reads) throws Exception;
  }

  /**
   * Business denial is a committed underwriting result; malformed/unauthorized Reject never creates
   * an inquiry.
   */
  public static Registration acceptedInquiry(
      PartyRef owner,
      InquiryRequest request,
      LongSupplier processingClock,
      AcceptedUnderwriting actual) {
    return new Registration(
        1,
        (intent, reads) -> {
          if (!owns(owner, request.world(), request.owner())
              || !worldMatches(reads, owner.world())
              || !intent.actor().equals(request.owner())
              || !intent.kind().equals(INQUIRY)
              || !request.request().equals(intent.request()))
            return reject("CREDIT_INQUIRY_REQUEST");
          if (actual == null) return unavailable("CREDIT_UNDERWRITING_UNAVAILABLE");
          long acceptedAt = processingClock.getAsLong();
          if (acceptedAt < request.submittedAt() || acceptedAt < request.calendarOrigin())
            return unavailable("CREDIT_CLOCK_STALE");
          if (!origin(reads).filter(o -> o == request.calendarOrigin()).isPresent())
            return unavailable("CREDIT_CALENDAR_UNAVAILABLE");
          if (request.firstIssuance()) return unavailable("CREDIT_INITIAL_COMPOSITION_REQUIRED");
          if (!reads
              .query(
                  "SELECT application_id FROM credit_inquiry_applications WHERE world_id=? AND owner_id=? AND application_id=?",
                  List.of(
                      request.world().value().toString(),
                      request.owner().value().toString(),
                      request.application().toString()),
                  1)
              .isEmpty()) return reject("CREDIT_APPLICATION_REUSED");
          var decision = actual.prepare(intent, reads);
          if (!(decision instanceof Commit accepted)) return decision;
          long bucket = (acceptedAt - request.calendarOrigin()) / FinancialCalendar.WEEK_MILLIS;
          boolean fresh =
              reads
                  .query(
                      "SELECT bucket FROM credit_inquiry_groups WHERE world_id=? AND owner_id=? AND bucket=?",
                      List.of(
                          request.world().value().toString(),
                          request.owner().value().toString(),
                          Long.toString(bucket)),
                      1)
                  .isEmpty();
          String payload =
              String.join(
                  "|",
                  request.world().value().toString(),
                  request.owner().value().toString(),
                  request.issuer().key(),
                  request.application().toString(),
                  request.kind().name(),
                  Long.toString(acceptedAt),
                  Long.toString(request.calendarOrigin()),
                  Long.toString(bucket),
                  fresh ? "1" : "0",
                  accepted.result().code(),
                  intent.operationId().value().toString());
          var records = new ArrayList<>(accepted.records());
          records.add(
              new Journal.Record(
                  INQUIRY_FACT,
                  request.issuer().equals(InstitutionCatalog.IRONBRIDGE) ? 2 : 1,
                  payload));
          if (!fits(records)) return reject("CREDIT_INQUIRY_BUDGET");
          return new Commit(
              records,
              accepted.result(),
              tx -> {
                long sequence =
                    Long.parseLong(
                        tx.query(
                                "SELECT MAX(sequence) AS sequence FROM journal_transactions",
                                List.of(),
                                1)
                            .getFirst()
                            .get("sequence"));
                accepted.projections().apply(tx);
                tx.execute(
                    "INSERT INTO credit_inquiry_applications VALUES(?,?,?,?,?,?,?,?,?,?)",
                    List.of(
                        request.world().value().toString(),
                        request.owner().value().toString(),
                        request.application().toString(),
                        request.issuer().key(),
                        request.kind().name(),
                        Long.toString(acceptedAt),
                        Long.toString(request.calendarOrigin()),
                        Long.toString(bucket),
                        intent.operationId().value().toString(),
                        Long.toString(sequence)));
                if (fresh)
                  tx.execute(
                      "INSERT INTO credit_inquiry_groups VALUES(?,?,?,?,?,?,?)",
                      List.of(
                          request.world().value().toString(),
                          request.owner().value().toString(),
                          Long.toString(bucket),
                          Long.toString(request.calendarOrigin()),
                          Long.toString(acceptedAt),
                          request.application().toString(),
                          request.issuer().key()));
              });
        });
  }

  public static Optional<Profile> find(ReadView reads, PartyRef owner) {
    if (!(owner.owner() instanceof PartyRef.Player player)) return Optional.empty();
    return reads
        .query(
            "SELECT * FROM credit_profiles WHERE world_id=? AND owner_id=?",
            List.of(owner.world().value().toString(), player.id().value().toString()),
            1)
        .stream()
        .map(CreditProfiles::readProfile)
        .findFirst();
  }

  public static HistoryPage history(ReadView reads, PartyRef owner, long after, int limit) {
    if (after < 0 || limit < 1 || limit > MAX_PAGE)
      throw new IllegalArgumentException("Credit history page");
    if (!(owner.owner() instanceof PartyRef.Player player)) return new HistoryPage(List.of(), null);
    var rows =
        reads.query(
            "SELECT * FROM credit_score_history WHERE world_id=? AND owner_id=? AND history_id>? ORDER BY history_id LIMIT "
                + (limit + 1),
            List.of(
                owner.world().value().toString(),
                player.id().value().toString(),
                Long.toString(after)),
            limit + 1);
    var page = rows.stream().limit(limit).map(CreditProfiles::readHistory).toList();
    return new HistoryPage(page, rows.size() > limit ? page.getLast().id() : null);
  }

  public static Optional<History> latestHistory(ReadView reads, PartyRef owner) {
    if (!(owner.owner() instanceof PartyRef.Player player)) return Optional.empty();
    return reads
        .query(
            "SELECT * FROM credit_score_history WHERE world_id=? AND owner_id=? ORDER BY history_id DESC LIMIT 1",
            List.of(owner.world().value().toString(), player.id().value().toString()),
            1)
        .stream()
        .map(CreditProfiles::readHistory)
        .findFirst();
  }

  /**
   * At most thirteen indexed probes; current/future applications cannot rewrite a past scoring
   * window.
   */
  public static List<Long> inquiryBuckets(
      ReadView reads, PartyRef owner, long origin, long boundary) {
    return inquiryBuckets(reads, owner, origin, boundary, Long.MAX_VALUE);
  }

  /**
   * Historical scoring must pass the reporting stage's captured journal frontier, including at
   * same-instant boundaries.
   */
  public static List<Long> inquiryBuckets(
      ReadView reads, PartyRef owner, long origin, long boundary, long maxJournalSequence) {
    if (!(owner.owner() instanceof PartyRef.Player player)
        || origin < 0
        || boundary < origin
        || maxJournalSequence < 0) throw new IllegalArgumentException("Inquiry period");
    long from = boundary - 84L * FinancialCalendar.DAY_MILLIS;
    long first = (Math.max(origin, from) - origin) / FinancialCalendar.WEEK_MILLIS,
        last = (boundary - origin) / FinancialCalendar.WEEK_MILLIS;
    if (last - first > 12) throw new IllegalArgumentException("Inquiry bucket bound");
    var result = new ArrayList<Long>();
    for (long bucket = first; bucket <= last; bucket++) {
      var found =
          reads.query(
              "SELECT application_id FROM credit_inquiry_applications WHERE world_id=? AND owner_id=? AND bucket=? AND accepted_at>? AND accepted_at<=? AND journal_sequence<=? ORDER BY journal_sequence DESC LIMIT 1",
              List.of(
                  owner.world().value().toString(),
                  player.id().value().toString(),
                  Long.toString(bucket),
                  Long.toString(from),
                  Long.toString(boundary),
                  Long.toString(maxJournalSequence)),
              1);
      if (!found.isEmpty()) result.add(bucket);
    }
    return List.copyOf(result);
  }

  static boolean worldMatches(ReadView reads, WorldId world) {
    return reads
        .query("SELECT world_id FROM foundation_metadata WHERE singleton=1", List.of(), 1)
        .stream()
        .anyMatch(row -> row.get("world_id").equals(world.value().toString()));
  }

  static Optional<Long> origin(ReadView reads) {
    return reads
        .query("SELECT origin_ms FROM financial_calendar WHERE singleton=1", List.of(), 1)
        .stream()
        .map(r -> Long.parseLong(r.get("origin_ms")))
        .findFirst();
  }

  static void writeHistory(
      ca.emilybelnavis.mcmods.economy.storage.FoundationStore.Transaction tx, History value) {
    tx.execute(
        "INSERT INTO credit_score_history(world_id,owner_id,boundary,entry_kind,policy_version,profile_revision,score,baseline,payment,utilization,age,inquiry,defaults,input_fingerprint,input_snapshot,reasons,period_id,reporting_frontier) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        historyValues(value));
  }

  static List<String> historyValues(History h) {
    return List.of(
        h.world().value().toString(),
        h.owner().value().toString(),
        Long.toString(h.boundary()),
        h.kind(),
        Integer.toString(h.policyVersion()),
        Long.toString(h.profileRevision()),
        Integer.toString(h.score()),
        Integer.toString(h.components().baseline()),
        Integer.toString(h.components().payment()),
        Integer.toString(h.components().utilization()),
        Integer.toString(h.components().age()),
        Integer.toString(h.components().inquiry()),
        Integer.toString(h.components().defaults()),
        h.inputFingerprint(),
        h.inputSnapshot(),
        reasonText(h.reasons()),
        h.periodId().toString(),
        Long.toString(h.reportingFrontier()));
  }

  static List<String> values(Profile p) {
    return List.of(
        p.world().value().toString(),
        p.owner().value().toString(),
        Integer.toString(p.score()),
        Long.toString(p.firstIssuedAt()),
        p.firstFacility().toString(),
        p.firstIssuer().key(),
        p.starterCurrency().name(),
        Long.toString(p.starterMinor()),
        Integer.toString(p.policyVersion()),
        Long.toString(p.revision()),
        Long.toString(p.lastBoundary()),
        Long.toString(p.reportingFrontier()),
        p.inputFingerprint());
  }

  private static Profile readProfile(Map<String, String> r) {
    return new Profile(
        new WorldId(UUID.fromString(r.get("world_id"))),
        new PlayerId(UUID.fromString(r.get("owner_id"))),
        Integer.parseInt(r.get("score")),
        Long.parseLong(r.get("first_issued_at")),
        UUID.fromString(r.get("first_facility")),
        new InstitutionCatalog.InstitutionId(r.get("first_issuer")),
        EconomyCurrency.valueOf(r.get("starter_currency")),
        Long.parseLong(r.get("starter_minor")),
        Integer.parseInt(r.get("policy_version")),
        Long.parseLong(r.get("revision")),
        Long.parseLong(r.get("last_boundary")),
        Long.parseLong(r.get("reporting_frontier")),
        r.get("input_fingerprint"));
  }

  private static History readHistory(Map<String, String> r) {
    return decodeHistory(r, Long.parseLong(r.get("history_id")));
  }

  static String encode(Profile p) {
    return encoded(values(p));
  }

  static String encode(History h) {
    return encoded(historyValues(h));
  }

  private static String encoded(List<String> values) {
    return values.stream()
        .map(
            v ->
                Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(v.getBytes(StandardCharsets.UTF_8)))
        .collect(java.util.stream.Collectors.joining("|"));
  }

  private static List<String> decoded(String text, int count) {
    var p = text.split("\\|", -1);
    if (p.length != count) throw new IllegalArgumentException("Credit fact fields");
    return Arrays.stream(p)
        .map(v -> new String(Base64.getUrlDecoder().decode(v), StandardCharsets.UTF_8))
        .toList();
  }

  private static History decodeHistory(Map<String, String> r, long id) {
    return new History(
        id,
        new WorldId(UUID.fromString(r.get("world_id"))),
        new PlayerId(UUID.fromString(r.get("owner_id"))),
        Long.parseLong(r.get("boundary")),
        r.get("entry_kind"),
        Integer.parseInt(r.get("policy_version")),
        Long.parseLong(r.get("profile_revision")),
        Integer.parseInt(r.get("score")),
        new ScorePolicy.Components(
            Integer.parseInt(r.get("baseline")),
            Integer.parseInt(r.get("payment")),
            Integer.parseInt(r.get("utilization")),
            Integer.parseInt(r.get("age")),
            Integer.parseInt(r.get("inquiry")),
            Integer.parseInt(r.get("defaults"))),
        r.get("input_fingerprint"),
        r.get("input_snapshot"),
        r.get("reasons").isEmpty() ? List.of() : List.of(r.get("reasons").split("\n")),
        UUID.fromString(r.get("period_id")),
        Long.parseLong(r.get("reporting_frontier")));
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog
        .with(new RecordCatalog.Key(PROFILE_FACT, 1), CreditProfiles::validateProfileFact)
        .with(new RecordCatalog.Key(HISTORY_FACT, 1), CreditProfiles::validateHistoryFact)
        .with(new RecordCatalog.Key(INQUIRY_FACT, 1), CreditProfiles::validateInquiryFact)
        .with(new RecordCatalog.Key(INQUIRY_FACT, 2), CreditProfiles::validateInternalInquiryFact);
  }

  public static void validateProfileFact(String payload) {
    var v = decoded(payload, 13);
    var keys =
        List.of(
            "world_id",
            "owner_id",
            "score",
            "first_issued_at",
            "first_facility",
            "first_issuer",
            "starter_currency",
            "starter_minor",
            "policy_version",
            "revision",
            "last_boundary",
            "reporting_frontier",
            "input_fingerprint");
    var r = new HashMap<String, String>();
    for (int i = 0; i < keys.size(); i++) r.put(keys.get(i), v.get(i));
    if (!encode(readProfile(r)).equals(payload))
      throw new IllegalArgumentException("Noncanonical credit profile fact");
  }

  public static void validateHistoryFact(String payload) {
    var v = decoded(payload, 18);
    var keys =
        List.of(
            "world_id",
            "owner_id",
            "boundary",
            "entry_kind",
            "policy_version",
            "profile_revision",
            "score",
            "baseline",
            "payment",
            "utilization",
            "age",
            "inquiry",
            "defaults",
            "input_fingerprint",
            "input_snapshot",
            "reasons",
            "period_id",
            "reporting_frontier");
    var r = new HashMap<String, String>();
    for (int i = 0; i < keys.size(); i++) r.put(keys.get(i), v.get(i));
    if (!encode(decodeHistory(r, 0)).equals(payload))
      throw new IllegalArgumentException("Noncanonical credit history fact");
  }

  public static void validateInquiryFact(String payload) {
    validateInquiry(payload, false);
  }

  public static void validateInternalInquiryFact(String payload) {
    validateInquiry(payload, true);
  }

  private static void validateInquiry(String payload, boolean internal) {
    var p = payload.split("\\|", -1);
    if (p.length != 11) throw new IllegalArgumentException("Inquiry fact");
    for (int i : new int[] {0, 1, 3, 10})
      if (!UUID.fromString(p[i]).toString().equals(p[i]))
        throw new IllegalArgumentException("Inquiry UUID");
    var issuer = new InstitutionCatalog.InstitutionId(p[2]);
    if (internal) {
      if (!issuer.equals(InstitutionCatalog.IRONBRIDGE) || !p[4].equals("LOAN"))
        throw new IllegalArgumentException("Internal loan inquiry");
    } else InstitutionCatalog.require(issuer);
    UUID.fromString(p[3]);
    ApplicationKind.valueOf(p[4]);
    long at = Long.parseLong(p[5]), origin = Long.parseLong(p[6]), bucket = Long.parseLong(p[7]);
    if (!Long.toString(at).equals(p[5])
        || !Long.toString(origin).equals(p[6])
        || !Long.toString(bucket).equals(p[7])
        || origin < 0
        || at < origin
        || (at - origin) / FinancialCalendar.WEEK_MILLIS != bucket
        || !Set.of("0", "1").contains(p[8])
        || !p[9].matches("[A-Z][A-Z0-9_]{0,63}"))
      throw new IllegalArgumentException("Inquiry fact values");
  }

  private static boolean fits(List<Journal.Record> records) {
    return records.size() <= 64
        && records.stream()
                .mapToLong(r -> r.payload().getBytes(StandardCharsets.UTF_8).length)
                .sum()
            <= 131072;
  }

  static boolean owns(PartyRef owner, WorldId world, PlayerId player) {
    return owner.world().equals(world)
        && owner.owner() instanceof PartyRef.Player p
        && p.id().equals(player);
  }

  static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  static void fingerprint(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Input fingerprint");
  }

  private static void bounded(String value, int bytes) {
    Objects.requireNonNull(value);
    if (value.getBytes(StandardCharsets.UTF_8).length > bytes)
      throw new IllegalArgumentException("Credit snapshot bounds");
  }

  private static String reasonText(List<String> reasons) {
    if (reasons.size() > 16) throw new IllegalArgumentException("Credit reason count");
    for (var r : reasons) {
      bounded(r, 128);
      if (r.contains("\n")) throw new IllegalArgumentException("Credit reason format");
    }
    var result = String.join("\n", reasons);
    bounded(result, 2048);
    return result;
  }

  static Reject reject(String code) {
    return new Reject(new Journal.Result(1, code, ""));
  }

  static Decline unavailable(String code) {
    return new Decline(new Journal.Result(1, code, ""));
  }
}
