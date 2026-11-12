package ca.emilybelnavis.mcmods.economy.debt;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.InstitutionId;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Version-one obligation, reporting and independent collection-clock snapshots. */
public final class DebtRecords {
  public static final int VERSION = 1;
  public static final long DAY = 1_200_000L;
  public static final long DEFAULT_DELAY = 84 * DAY;
  public static final long COLLECTION_WINDOW = 728 * DAY;
  public static final long REPORT_WINDOW = 2_548 * DAY;
  public static final String OBLIGATION_FACT = "economy:a6/debt-obligation";
  public static final String CASE_FACT = "economy:a6/debt-case";
  public static final String REPORT_FACT = "economy:a6/debt-report";

  public enum Kind {
    CARD,
    LOAN,
    OVERDRAFT
  }

  public enum Sale {
    PENDING,
    SOLD
  }

  public enum Collectability {
    COLLECTIBLE,
    UNCOLLECTABLE
  }

  public enum Payment {
    UNPAID,
    PARTIAL,
    SETTLED
  }

  private DebtRecords() {}

  public record Obligation(
      WorldId world,
      UUID id,
      Kind kind,
      UUID facility,
      PlayerId owner,
      EconomyCurrency currency,
      long originalMinor,
      long unmetMinor,
      long dueAt,
      boolean reported,
      boolean metAtReport,
      long revision,
      long effectiveAt) {
    public Obligation {
      identity(world, id, kind, facility, owner, currency);
      amounts(originalMinor, unmetMinor);
      if (dueAt < 0 || revision < 1 || effectiveAt < 0 || metAtReport && !reported) {
        throw new IllegalArgumentException("Debt obligation fields");
      }
      Math.addExact(dueAt, 2 * DAY);
      Math.addExact(dueAt, DEFAULT_DELAY);
    }
  }

  public record Case(
      WorldId world,
      UUID id,
      Kind kind,
      UUID facility,
      PlayerId owner,
      InstitutionId origin,
      InstitutionId creditor,
      EconomyCurrency currency,
      long principalMinor,
      long interestMinor,
      long defaultAt,
      long acknowledgedAt,
      long collectionExpires,
      long reportExpires,
      Sale sale,
      Collectability collectability,
      Payment payment,
      long discountRemainingMinor,
      long revision) {
    public Case {
      identity(world, id, kind, facility, owner, currency);
      Objects.requireNonNull(origin);
      Objects.requireNonNull(creditor);
      Objects.requireNonNull(sale);
      Objects.requireNonNull(collectability);
      Objects.requireNonNull(payment);
      boolean originAllowed =
          InstitutionCatalog.find(origin).isPresent()
              || kind == Kind.LOAN && origin.equals(InstitutionCatalog.IRONBRIDGE);
      long debt = Math.addExact(principalMinor, interestMinor);
      if (!originAllowed
          || principalMinor < 0
          || interestMinor < 0
          || defaultAt < 0
          || acknowledgedAt < defaultAt
          || revision < 1
          || discountRemainingMinor < 0
          || discountRemainingMinor > debt
          || (payment == Payment.SETTLED) != (debt == 0)
          || collectionExpires != Math.addExact(acknowledgedAt, COLLECTION_WINDOW)
          || reportExpires != Math.addExact(defaultAt, REPORT_WINDOW)) {
        throw new IllegalArgumentException("Debt case fields");
      }
      if (sale == Sale.PENDING && (!creditor.equals(origin) || discountRemainingMinor != 0)
          || sale == Sale.SOLD && !creditor.equals(InstitutionCatalog.WAYSTONE)) {
        throw new IllegalArgumentException("Debt creditor ownership");
      }
    }

    public long debtMinor() {
      return Math.addExact(principalMinor, interestMinor);
    }
  }

  public record Report(
      WorldId world,
      UUID id,
      Kind kind,
      UUID facility,
      PlayerId owner,
      UUID obligation,
      long dueAt,
      long reportAt,
      boolean metAtSnapshot,
      boolean cured,
      long originalMinor,
      long unmetMinor,
      long revision,
      long effectiveAt) {
    public Report {
      Objects.requireNonNull(obligation);
      Objects.requireNonNull(world);
      Objects.requireNonNull(id);
      Objects.requireNonNull(kind);
      Objects.requireNonNull(facility);
      Objects.requireNonNull(owner);
      amounts(originalMinor, unmetMinor);
      if (dueAt < 0
          || reportAt != Math.addExact(dueAt, 2 * DAY)
          || effectiveAt < reportAt
          || revision < 1
          || metAtSnapshot && unmetMinor != 0
          || cured && (metAtSnapshot || unmetMinor != 0)) {
        throw new IllegalArgumentException("Debt report fields");
      }
    }
  }

  public static Journal.Record fact(Obligation value) {
    return new Journal.Record(OBLIGATION_FACT, VERSION, encode(value));
  }

  public static Journal.Record fact(Case value) {
    return new Journal.Record(CASE_FACT, VERSION, encode(value));
  }

  public static Journal.Record fact(Report value) {
    return new Journal.Record(REPORT_FACT, VERSION, encode(value));
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog
        .with(new RecordCatalog.Key(OBLIGATION_FACT, VERSION), DebtRecords::decodeObligation)
        .with(new RecordCatalog.Key(CASE_FACT, VERSION), DebtRecords::decodeCase)
        .with(new RecordCatalog.Key(REPORT_FACT, VERSION), DebtRecords::decodeReport);
  }

  public static Obligation decodeObligation(String payload) {
    var fields = unpack(payload, 13);
    return new Obligation(
        world(fields.get(0)),
        uuid(fields.get(1)),
        Kind.valueOf(fields.get(2)),
        uuid(fields.get(3)),
        player(fields.get(4)),
        EconomyCurrency.valueOf(fields.get(5)),
        number(fields.get(6)),
        number(fields.get(7)),
        number(fields.get(8)),
        bool(fields.get(9)),
        bool(fields.get(10)),
        number(fields.get(11)),
        number(fields.get(12)));
  }

  public static Case decodeCase(String payload) {
    var fields = unpack(payload, 19);
    return new Case(
        world(fields.get(0)),
        uuid(fields.get(1)),
        Kind.valueOf(fields.get(2)),
        uuid(fields.get(3)),
        player(fields.get(4)),
        new InstitutionId(fields.get(5)),
        new InstitutionId(fields.get(6)),
        EconomyCurrency.valueOf(fields.get(7)),
        number(fields.get(8)),
        number(fields.get(9)),
        number(fields.get(10)),
        number(fields.get(11)),
        number(fields.get(12)),
        number(fields.get(13)),
        Sale.valueOf(fields.get(14)),
        Collectability.valueOf(fields.get(15)),
        Payment.valueOf(fields.get(16)),
        number(fields.get(17)),
        number(fields.get(18)));
  }

  public static Report decodeReport(String payload) {
    var fields = unpack(payload, 14);
    return new Report(
        world(fields.get(0)),
        uuid(fields.get(1)),
        Kind.valueOf(fields.get(2)),
        uuid(fields.get(3)),
        player(fields.get(4)),
        uuid(fields.get(5)),
        number(fields.get(6)),
        number(fields.get(7)),
        bool(fields.get(8)),
        bool(fields.get(9)),
        number(fields.get(10)),
        number(fields.get(11)),
        number(fields.get(12)),
        number(fields.get(13)));
  }

  public static void write(FoundationStore.Transaction transaction, Obligation value) {
    String body = encode(value);
    var rows =
        transaction.query(
            "SELECT body FROM debt_obligations WHERE world_id=? AND kind=? AND obligation_id=?",
            List.of(value.world().value().toString(), value.kind().name(), value.id().toString()),
            1);
    if (!rows.isEmpty()) {
      var previous = decodeObligation(rows.getFirst().get("body"));
      if (!previous.facility().equals(value.facility())
          || !previous.owner().equals(value.owner())
          || previous.currency() != value.currency()
          || previous.dueAt() != value.dueAt()
          || previous.originalMinor() != value.originalMinor()
          || previous.reported()
              && (!value.reported() || previous.metAtReport() != value.metAtReport())) {
        throw new IllegalArgumentException("Original obligation changed");
      }
    }
    transaction.execute(
        "INSERT INTO debt_obligations VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(world_id,kind,obligation_id) DO UPDATE SET unmet_minor=excluded.unmet_minor,reported=excluded.reported,met_at_report=excluded.met_at_report,revision=excluded.revision,body=excluded.body",
        List.of(
            value.world().value().toString(),
            value.kind().name(),
            value.id().toString(),
            value.facility().toString(),
            value.owner().value().toString(),
            value.currency().name(),
            Long.toString(value.dueAt()),
            Long.toString(value.originalMinor()),
            Long.toString(value.unmetMinor()),
            value.reported() ? "1" : "0",
            value.metAtReport() ? "1" : "0",
            Long.toString(value.revision()),
            body));
  }

  public static void write(FoundationStore.Transaction transaction, Case value) {
    String body = encode(value);
    var rows =
        transaction.query(
            "SELECT body FROM debt_cases WHERE world_id=? AND case_id=?",
            List.of(value.world().value().toString(), value.id().toString()),
            1);
    if (!rows.isEmpty()) {
      var previous = decodeCase(rows.getFirst().get("body"));
      if (previous.kind() != value.kind()
          || !previous.facility().equals(value.facility())
          || !previous.owner().equals(value.owner())
          || !previous.origin().equals(value.origin())
          || previous.currency() != value.currency()
          || previous.defaultAt() != value.defaultAt()
          || previous.reportExpires() != value.reportExpires()) {
        throw new IllegalArgumentException("Original default changed");
      }
    }
    transaction.execute(
        "INSERT INTO debt_cases VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(world_id,case_id) DO UPDATE SET creditor=excluded.creditor,principal_minor=excluded.principal_minor,interest_minor=excluded.interest_minor,acknowledged_at=excluded.acknowledged_at,collection_expires=excluded.collection_expires,sale=excluded.sale,collectability=excluded.collectability,payment=excluded.payment,discount_minor=excluded.discount_minor,revision=excluded.revision,body=excluded.body",
        List.of(
            value.world().value().toString(),
            value.id().toString(),
            value.kind().name(),
            value.facility().toString(),
            value.owner().value().toString(),
            value.creditor().key(),
            value.currency().name(),
            Long.toString(value.principalMinor()),
            Long.toString(value.interestMinor()),
            Long.toString(value.defaultAt()),
            Long.toString(value.acknowledgedAt()),
            Long.toString(value.collectionExpires()),
            Long.toString(value.reportExpires()),
            value.sale().name(),
            value.collectability().name(),
            value.payment().name(),
            Long.toString(value.discountRemainingMinor()),
            Long.toString(value.revision()),
            body));
  }

  public static void write(FoundationStore.Transaction transaction, Report value) {
    String body = encode(value);
    var rows =
        transaction.query(
            "SELECT body FROM debt_reports WHERE world_id=? AND report_id=? AND revision=?",
            List.of(
                value.world().value().toString(),
                value.id().toString(),
                Long.toString(value.revision())),
            1);
    if (!rows.isEmpty()) {
      if (!body.equals(rows.getFirst().get("body")))
        throw new IllegalArgumentException("Report revision changed");
      return;
    }
    var prior =
        transaction.query(
            "SELECT body FROM debt_reports WHERE world_id=? AND report_id=? ORDER BY revision DESC LIMIT 1",
            List.of(value.world().value().toString(), value.id().toString()),
            1);
    if (!prior.isEmpty()) {
      var previous = decodeReport(prior.getFirst().get("body"));
      if (previous.kind() != value.kind()
          || !previous.facility().equals(value.facility())
          || !previous.owner().equals(value.owner())
          || !previous.obligation().equals(value.obligation())
          || previous.dueAt() != value.dueAt()
          || previous.reportAt() != value.reportAt()
          || previous.originalMinor() != value.originalMinor()) {
        throw new IllegalArgumentException("Original report changed");
      }
    }
    transaction.execute(
        "INSERT INTO debt_reports VALUES(?,?,?,?,?,?,?,?,?,?)",
        List.of(
            value.world().value().toString(),
            value.id().toString(),
            Long.toString(value.revision()),
            value.owner().value().toString(),
            value.kind().name(),
            value.facility().toString(),
            value.obligation().toString(),
            Long.toString(value.reportAt()),
            Long.toString(value.effectiveAt()),
            body));
  }

  private static String encode(Obligation value) {
    return pack(
        List.of(
            value.world().value().toString(),
            value.id().toString(),
            value.kind().name(),
            value.facility().toString(),
            value.owner().value().toString(),
            value.currency().name(),
            Long.toString(value.originalMinor()),
            Long.toString(value.unmetMinor()),
            Long.toString(value.dueAt()),
            Boolean.toString(value.reported()),
            Boolean.toString(value.metAtReport()),
            Long.toString(value.revision()),
            Long.toString(value.effectiveAt())));
  }

  private static String encode(Case value) {
    return pack(
        List.of(
            value.world().value().toString(),
            value.id().toString(),
            value.kind().name(),
            value.facility().toString(),
            value.owner().value().toString(),
            value.origin().key(),
            value.creditor().key(),
            value.currency().name(),
            Long.toString(value.principalMinor()),
            Long.toString(value.interestMinor()),
            Long.toString(value.defaultAt()),
            Long.toString(value.acknowledgedAt()),
            Long.toString(value.collectionExpires()),
            Long.toString(value.reportExpires()),
            value.sale().name(),
            value.collectability().name(),
            value.payment().name(),
            Long.toString(value.discountRemainingMinor()),
            Long.toString(value.revision())));
  }

  private static String encode(Report value) {
    return pack(
        List.of(
            value.world().value().toString(),
            value.id().toString(),
            value.kind().name(),
            value.facility().toString(),
            value.owner().value().toString(),
            value.obligation().toString(),
            Long.toString(value.dueAt()),
            Long.toString(value.reportAt()),
            Boolean.toString(value.metAtSnapshot()),
            Boolean.toString(value.cured()),
            Long.toString(value.originalMinor()),
            Long.toString(value.unmetMinor()),
            Long.toString(value.revision()),
            Long.toString(value.effectiveAt())));
  }

  private static void identity(
      WorldId world, UUID id, Kind kind, UUID facility, PlayerId owner, EconomyCurrency currency) {
    Objects.requireNonNull(world);
    Objects.requireNonNull(id);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(facility);
    Objects.requireNonNull(owner);
    Objects.requireNonNull(currency);
  }

  private static void amounts(long original, long unmet) {
    if (original < 0 || unmet < 0 || unmet > original)
      throw new IllegalArgumentException("Debt obligation amount");
  }

  private static String pack(List<String> fields) {
    return String.join(
        "|",
        fields.stream()
            .map(
                value ->
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(value.getBytes(StandardCharsets.UTF_8)))
            .toList());
  }

  private static List<String> unpack(String payload, int count) {
    if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > 16_384) {
      throw new IllegalArgumentException("Debt record size");
    }
    var encoded = payload.split("\\|", -1);
    if (encoded.length != count) throw new IllegalArgumentException("Debt record fields");
    var values =
        java.util.Arrays.stream(encoded)
            .map(value -> new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8))
            .toList();
    if (!pack(values).equals(payload)) throw new IllegalArgumentException("Debt record encoding");
    return values;
  }

  private static long number(String value) {
    long result = Long.parseLong(value);
    if (!Long.toString(result).equals(value))
      throw new IllegalArgumentException("Debt integer encoding");
    return result;
  }

  private static boolean bool(String value) {
    if (!value.equals("true") && !value.equals("false"))
      throw new IllegalArgumentException("Debt boolean encoding");
    return Boolean.parseBoolean(value);
  }

  private static UUID uuid(String value) {
    var result = UUID.fromString(value);
    if (!result.toString().equals(value)) throw new IllegalArgumentException("Debt UUID encoding");
    return result;
  }

  private static WorldId world(String value) {
    return new WorldId(uuid(value));
  }

  private static PlayerId player(String value) {
    return new PlayerId(uuid(value));
  }
}
