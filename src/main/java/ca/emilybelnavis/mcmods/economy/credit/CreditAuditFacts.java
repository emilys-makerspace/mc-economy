package ca.emilybelnavis.mcmods.economy.credit;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Immutable diagnostic inputs; never recalculate historical accrual using current rates. */
public final class CreditAuditFacts {
  public static final String DAILY = "economy:a6/credit-daily-witness",
      PAYMENT = "economy:a6/credit-payment-allocation";

  public record Daily(
      UUID facility,
      WorldId world,
      long effective,
      EconomyCurrency currency,
      int policyVersion,
      CardServicing.Bucket beforePurchase,
      CardServicing.Bucket beforeAdvance,
      CardServicing.Accrual purchase,
      CardServicing.Accrual advance) {}

  public record Payment(
      UUID facility,
      WorldId world,
      long effective,
      EconomyCurrency currency,
      int policyVersion,
      long amountMinor,
      long principalMinor,
      long interestMinor,
      BigDecimal waivedResidualMajor,
      List<CardServicing.Allocation> allocations) {
    public Payment {
      allocations = List.copyOf(allocations);
    }
  }

  private CreditAuditFacts() {}

  public static Journal.Record dailyRecord(
      CreditFacilities.Facility f,
      long effective,
      CardServicing.Accrual purchase,
      CardServicing.Accrual advance) {
    return new Journal.Record(
        DAILY,
        2,
        String.join(
            "|",
            f.id().toString(),
            f.world().value().toString(),
            Long.toString(effective),
            f.currency().name(),
            "2",
            bucket(f.purchase()),
            bucket(f.advance()),
            bucket(purchase.bucket()),
            bucket(advance.bucket()),
            purchase.earnedMajor().toPlainString(),
            advance.earnedMajor().toPlainString(),
            Long.toString(purchase.postedMinor()),
            Long.toString(advance.postedMinor())));
  }

  public static Journal.Record paymentRecord(
      CreditFacilities.Facility f,
      long effective,
      long amount,
      long principal,
      long interest,
      CardServicing.Payment payment) {
    String allocations =
        String.join(
            "\n",
            payment.allocations().stream()
                .map(
                    a ->
                        String.join(
                            ",",
                            a.statementId(),
                            a.kind().name(),
                            Boolean.toString(a.interest()),
                            Long.toString(a.minor())))
                .toList());
    return new Journal.Record(
        PAYMENT,
        1,
        String.join(
            "|",
            f.id().toString(),
            f.world().value().toString(),
            Long.toString(effective),
            f.currency().name(),
            "1",
            Long.toString(amount),
            Long.toString(principal),
            Long.toString(interest),
            payment.waivedResidualMajor().toPlainString(),
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(allocations.getBytes(StandardCharsets.UTF_8))));
  }

  public static Daily decodeDaily(String payload) {
    var p = fields(payload, 13);
    var beforeP = bucket(p[5]);
    var beforeA = bucket(p[6]);
    var purchase = new CardServicing.Accrual(bucket(p[7]), decimal(p[9]), number(p[11]));
    var advance = new CardServicing.Accrual(bucket(p[8]), decimal(p[10]), number(p[12]));
    var currency = EconomyCurrency.valueOf(p[3]);
    int policy = Integer.parseInt(p[4]);
    var expectedP =
        policy == 1
            ? CardServicing.dayLegacy(currency, beforeP)
            : CardServicing.day(currency, beforeP);
    var expectedA =
        policy == 1
            ? CardServicing.dayLegacy(currency, beforeA)
            : CardServicing.day(currency, beforeA);
    if (!expectedP.equals(purchase)
        || !expectedA.equals(advance)
        || beforeP.kind() != CardServicing.Kind.PURCHASE
        || beforeA.kind() != CardServicing.Kind.ADVANCE)
      throw new IllegalArgumentException("Daily calculation mismatch");
    return new Daily(
        uuid(p[0]),
        new WorldId(uuid(p[1])),
        number(p[2]),
        currency,
        policy,
        beforeP,
        beforeA,
        purchase,
        advance);
  }

  public static Payment decodePayment(String payload) {
    var p = fields(payload, 10);
    if (!p[4].equals("1")) throw new IllegalArgumentException("Payment witness policy");
    var bytes = Base64.getUrlDecoder().decode(p[9]);
    String text = new String(bytes, StandardCharsets.UTF_8);
    if (!Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(text.getBytes(StandardCharsets.UTF_8))
        .equals(p[9])) throw new IllegalArgumentException("Allocation encoding");
    var allocations = new ArrayList<CardServicing.Allocation>();
    long total = 0, principal = 0, interest = 0;
    if (!text.isEmpty())
      for (var line : text.split("\n", -1)) {
        var a = line.split(",", -1);
        if (a.length != 4 || a[0].length() > 128 || (!a[2].equals("true") && !a[2].equals("false")))
          throw new IllegalArgumentException("Allocation fields");
        if (!a[0].isEmpty()) uuid(a[0]);
        long minor = number(a[3]);
        if (minor <= 0) throw new IllegalArgumentException("Allocation amount");
        boolean isInterest = Boolean.parseBoolean(a[2]);
        allocations.add(
            new CardServicing.Allocation(
                a[0], CardServicing.Kind.valueOf(a[1]), isInterest, minor));
        total = Math.addExact(total, minor);
        if (isInterest) interest = Math.addExact(interest, minor);
        else principal = Math.addExact(principal, minor);
      }
    if (allocations.size() > CardServicing.MAX_ROWS * 2 + 4
        || total != number(p[5])
        || total <= 0
        || principal != number(p[6])
        || interest != number(p[7])) throw new IllegalArgumentException("Allocation totals");
    return new Payment(
        uuid(p[0]),
        new WorldId(uuid(p[1])),
        number(p[2]),
        EconomyCurrency.valueOf(p[3]),
        1,
        total,
        principal,
        interest,
        decimal(p[8]),
        allocations);
  }

  private static String[] fields(String text, int count) {
    if (text.getBytes(StandardCharsets.UTF_8).length > 16 * 1024)
      throw new IllegalArgumentException("Credit witness size");
    var p = text.split("\\|", -1);
    if (p.length != count || !Set.of("1", "2").contains(p[4]) || number(p[2]) < 0)
      throw new IllegalArgumentException("Credit witness fields");
    return p;
  }

  private static String bucket(CardServicing.Bucket b) {
    return String.join(
        ",",
        b.kind().name(),
        Long.toString(b.principalMinor()),
        Long.toString(b.interestMinor()),
        b.residualMajor().toPlainString(),
        b.periodEarnedMajor().toPlainString(),
        b.apr().toPlainString());
  }

  private static CardServicing.Bucket bucket(String text) {
    var p = text.split(",", -1);
    if (p.length != 6) throw new IllegalArgumentException("Bucket fields");
    var b =
        new CardServicing.Bucket(
            CardServicing.Kind.valueOf(p[0]),
            number(p[1]),
            number(p[2]),
            decimal(p[3]),
            decimal(p[4]),
            new BigDecimal(p[5]));
    if (!bucket(b).equals(text)) throw new IllegalArgumentException("Bucket canonical");
    return b;
  }

  private static BigDecimal decimal(String text) {
    var value = new BigDecimal(text);
    if (value.scale() != 18 || value.precision() > 50 || !value.toPlainString().equals(text))
      throw new IllegalArgumentException("Witness decimal");
    return value;
  }

  private static long number(String text) {
    long n = Long.parseLong(text);
    if (!Long.toString(n).equals(text)) throw new IllegalArgumentException("Witness integer");
    return n;
  }

  private static UUID uuid(String text) {
    var id = UUID.fromString(text);
    if (!id.toString().equals(text)) throw new IllegalArgumentException("Witness UUID");
    return id;
  }

  private static void requireDailyPolicy(String payload, int version) {
    if (decodeDaily(payload).policyVersion() != version)
      throw new IllegalArgumentException("Daily witness policy version");
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog
        .with(new RecordCatalog.Key(DAILY, 1), p -> requireDailyPolicy(p, 1))
        .with(new RecordCatalog.Key(DAILY, 2), p -> requireDailyPolicy(p, 2))
        .with(new RecordCatalog.Key(PAYMENT, 1), CreditAuditFacts::decodePayment);
  }

  public static List<String> statements() {
    return List.of(
        "CREATE INDEX credit_diagnostic_records ON journal_records(record_type,substr(payload,instr(payload,'|')+1,48),sequence,position) WHERE record_type IN('economy:a6/credit-event','economy:a6/credit-facility')",
        "CREATE INDEX credit_audit_records ON journal_records(record_type,substr(payload,1,36),sequence,position) WHERE record_type IN('economy:a6/credit-daily-witness','economy:a6/credit-payment-allocation')",
        "CREATE INDEX credit_facility_issuer ON credit_facilities(world_id,issuer,currency,facility_id) WHERE debt_minor>0",
        "CREATE INDEX credit_diagnostic_owner_debt ON credit_facilities(world_id,owner_id,currency,facility_id) WHERE debt_minor>0");
  }

  public static void validateSchema(Connection connection) throws SQLException {
    var names =
        List.of(
            "credit_diagnostic_records",
            "credit_audit_records",
            "credit_facility_issuer",
            "credit_diagnostic_owner_debt");
    for (int i = 0; i < names.size(); i++)
      try (var q = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
        q.setString(1, names.get(i));
        try (var r = q.executeQuery()) {
          if (!r.next() || !statements().get(i).equals(r.getString(1)))
            throw new StorageException(
                StorageException.Kind.SCHEMA, "Missing credit diagnostic index");
        }
      }
  }
}
