package ca.emilybelnavis.mcmods.economy.debt;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebtRecordsTest {
  @TempDir Path path;
  private static final EconomyCurrency CAD = EconomyCurrency.CAD;
  private static final InstitutionCatalog.InstitutionId BANK =
      new InstitutionCatalog.InstitutionId("economy:bank/crownstone");
  private static final PlayerId OWNER =
      new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000001001"));
  private static final UUID FACILITY = UUID.fromString("00000000-0000-0000-0000-000000001002");
  private static final UUID OBLIGATION = UUID.fromString("00000000-0000-0000-0000-000000001003");
  private static final UUID CASE = UUID.fromString("00000000-0000-0000-0000-000000001004");
  private static final UUID REPORT = UUID.fromString("00000000-0000-0000-0000-000000001005");

  @Test
  void canonicalFactsRetainIndependentCollectionAndReportingClocks() {
    var world = new WorldId(UUID.randomUUID());
    long defaultAt = 0;
    var original =
        caseValue(
            world,
            10_000,
            500,
            defaultAt,
            defaultAt,
            DebtRecords.Sale.PENDING,
            DebtRecords.Collectability.COLLECTIBLE,
            DebtRecords.Payment.UNPAID,
            0,
            1);
    assertEquals(873_600_000L, original.collectionExpires());
    assertEquals(3_057_600_000L, original.reportExpires());
    long qualifyingPaymentAt = original.collectionExpires() - 1;
    var acknowledged =
        caseValue(
            world,
            9_900,
            500,
            defaultAt,
            qualifyingPaymentAt,
            DebtRecords.Sale.SOLD,
            DebtRecords.Collectability.COLLECTIBLE,
            DebtRecords.Payment.PARTIAL,
            8_000,
            2);
    assertEquals(original.reportExpires(), acknowledged.reportExpires());
    assertEquals(
        qualifyingPaymentAt + DebtRecords.COLLECTION_WINDOW, acknowledged.collectionExpires());
    assertEquals(10_400, acknowledged.debtMinor());
    assertEquals(acknowledged, DebtRecords.decodeCase(DebtRecords.fact(acknowledged).payload()));
    assertEquals(original, DebtRecords.decodeCase(DebtRecords.fact(original).payload()));

    var obligation =
        new DebtRecords.Obligation(
            world,
            OBLIGATION,
            DebtRecords.Kind.LOAN,
            FACILITY,
            OWNER,
            CAD,
            1_000,
            600,
            100,
            true,
            false,
            2,
            100 + 2 * DebtRecords.DAY);
    var report =
        new DebtRecords.Report(
            world,
            REPORT,
            DebtRecords.Kind.LOAN,
            FACILITY,
            OWNER,
            OBLIGATION,
            obligation.dueAt(),
            obligation.dueAt() + 2 * DebtRecords.DAY,
            false,
            false,
            1_000,
            600,
            1,
            obligation.dueAt() + 2 * DebtRecords.DAY);
    assertEquals(obligation, DebtRecords.decodeObligation(DebtRecords.fact(obligation).payload()));
    assertEquals(report, DebtRecords.decodeReport(DebtRecords.fact(report).payload()));
    var catalog = DebtRecords.register(new RecordCatalog(Map.of()));
    for (var fact :
        List.of(
            DebtRecords.fact(original), DebtRecords.fact(obligation), DebtRecords.fact(report))) {
      catalog.validate(fact.type(), fact.version(), fact.payload());
    }
    assertThrows(
        StorageException.class,
        () -> catalog.validate(DebtRecords.CASE_FACT, 2, DebtRecords.fact(original).payload()));
  }

  @Test
  void invalidAmountsDatesOwnershipAndNoncanonicalEncodingFailClosed() {
    var world = new WorldId(UUID.randomUUID());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            caseValue(
                world,
                100,
                0,
                1,
                1,
                DebtRecords.Sale.PENDING,
                DebtRecords.Collectability.COLLECTIBLE,
                DebtRecords.Payment.SETTLED,
                0,
                1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            caseValue(
                world,
                100,
                0,
                1,
                1,
                DebtRecords.Sale.SOLD,
                DebtRecords.Collectability.COLLECTIBLE,
                DebtRecords.Payment.PARTIAL,
                101,
                1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DebtRecords.Case(
                world,
                CASE,
                DebtRecords.Kind.LOAN,
                FACILITY,
                OWNER,
                BANK,
                InstitutionCatalog.WAYSTONE,
                CAD,
                100,
                0,
                1,
                1,
                1 + DebtRecords.COLLECTION_WINDOW,
                1 + DebtRecords.REPORT_WINDOW,
                DebtRecords.Sale.PENDING,
                DebtRecords.Collectability.COLLECTIBLE,
                DebtRecords.Payment.UNPAID,
                0,
                1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DebtRecords.Report(
                world,
                REPORT,
                DebtRecords.Kind.LOAN,
                FACILITY,
                OWNER,
                OBLIGATION,
                1,
                2 * DebtRecords.DAY,
                false,
                false,
                100,
                10,
                1,
                2 * DebtRecords.DAY));
    assertThrows(
        ArithmeticException.class,
        () ->
            caseValue(
                world,
                1,
                0,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                DebtRecords.Sale.PENDING,
                DebtRecords.Collectability.COLLECTIBLE,
                DebtRecords.Payment.UNPAID,
                0,
                1));
    var original =
        caseValue(
            world,
            100,
            0,
            1,
            1,
            DebtRecords.Sale.PENDING,
            DebtRecords.Collectability.COLLECTIBLE,
            DebtRecords.Payment.UNPAID,
            0,
            1);
    var fields = DebtRecords.fact(original).payload().split("\\|", -1);
    fields[8] =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("0100".getBytes(StandardCharsets.UTF_8));
    assertThrows(
        IllegalArgumentException.class, () -> DebtRecords.decodeCase(String.join("|", fields)));
    assertThrows(
        IllegalArgumentException.class,
        () -> DebtRecords.decodeCase(DebtRecords.fact(original).payload() + "="));
  }

  @Test
  void projectionWritesPreserveOriginalsAndReportCorrectionsAppend() throws Exception {
    var store = new SqliteStorage();
    store.initialize(path.toRealPath());
    try {
      var world = store.snapshot().worldId();
      var original =
          caseValue(
              world,
              100,
              0,
              10,
              10,
              DebtRecords.Sale.PENDING,
              DebtRecords.Collectability.COLLECTIBLE,
              DebtRecords.Payment.UNPAID,
              0,
              1);
      var obligation =
          new DebtRecords.Obligation(
              world,
              OBLIGATION,
              DebtRecords.Kind.LOAN,
              FACILITY,
              OWNER,
              CAD,
              100,
              100,
              5,
              true,
              false,
              1,
              5 + 2 * DebtRecords.DAY);
      var missed =
          new DebtRecords.Report(
              world,
              REPORT,
              DebtRecords.Kind.LOAN,
              FACILITY,
              OWNER,
              OBLIGATION,
              5,
              5 + 2 * DebtRecords.DAY,
              false,
              false,
              100,
              100,
              1,
              5 + 2 * DebtRecords.DAY);
      store.transaction(
          transaction -> {
            DebtRecords.write(transaction, original);
            DebtRecords.write(transaction, obligation);
            DebtRecords.write(transaction, missed);
            DebtRecords.write(transaction, missed);
            return null;
          });
      var corrected =
          new DebtRecords.Report(
              world,
              REPORT,
              DebtRecords.Kind.LOAN,
              FACILITY,
              OWNER,
              OBLIGATION,
              5,
              5 + 2 * DebtRecords.DAY,
              false,
              true,
              100,
              0,
              2,
              6 + 2 * DebtRecords.DAY);
      var paidObligation =
          new DebtRecords.Obligation(
              world,
              OBLIGATION,
              DebtRecords.Kind.LOAN,
              FACILITY,
              OWNER,
              CAD,
              100,
              0,
              5,
              true,
              false,
              2,
              corrected.effectiveAt());
      store.transaction(
          transaction -> {
            DebtRecords.write(transaction, paidObligation);
            DebtRecords.write(transaction, corrected);
            return null;
          });
      var reports =
          store.transaction(
              transaction ->
                  transaction.query(
                      "SELECT body FROM debt_reports ORDER BY revision", List.of(), 4));
      assertEquals(2, reports.size());
      assertEquals(missed, DebtRecords.decodeReport(reports.getFirst().get("body")));
      assertEquals(corrected, DebtRecords.decodeReport(reports.getLast().get("body")));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              store.transaction(
                  transaction -> {
                    DebtRecords.write(
                        transaction,
                        new DebtRecords.Obligation(
                            world,
                            OBLIGATION,
                            DebtRecords.Kind.LOAN,
                            FACILITY,
                            OWNER,
                            CAD,
                            100,
                            0,
                            6,
                            true,
                            false,
                            3,
                            corrected.effectiveAt()));
                    return null;
                  }));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              store.transaction(
                  transaction -> {
                    DebtRecords.write(
                        transaction,
                        caseValue(
                            world,
                            100,
                            0,
                            11,
                            11,
                            DebtRecords.Sale.PENDING,
                            DebtRecords.Collectability.COLLECTIBLE,
                            DebtRecords.Payment.UNPAID,
                            0,
                            2));
                    return null;
                  }));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              store.transaction(
                  transaction -> {
                    DebtRecords.write(
                        transaction,
                        new DebtRecords.Report(
                            world,
                            REPORT,
                            DebtRecords.Kind.LOAN,
                            FACILITY,
                            OWNER,
                            OBLIGATION,
                            5,
                            5 + 2 * DebtRecords.DAY,
                            true,
                            false,
                            100,
                            0,
                            1,
                            5 + 2 * DebtRecords.DAY));
                    return null;
                  }));
    } finally {
      store.close();
    }
  }

  private static DebtRecords.Case caseValue(
      WorldId world,
      long principal,
      long interest,
      long defaultAt,
      long acknowledgedAt,
      DebtRecords.Sale sale,
      DebtRecords.Collectability collectability,
      DebtRecords.Payment payment,
      long discount,
      long revision) {
    return new DebtRecords.Case(
        world,
        CASE,
        DebtRecords.Kind.LOAN,
        FACILITY,
        OWNER,
        BANK,
        sale == DebtRecords.Sale.SOLD ? InstitutionCatalog.WAYSTONE : BANK,
        CAD,
        principal,
        interest,
        defaultAt,
        acknowledgedAt,
        Math.addExact(acknowledgedAt, DebtRecords.COLLECTION_WINDOW),
        Math.addExact(defaultAt, DebtRecords.REPORT_WINDOW),
        sale,
        collectability,
        payment,
        discount,
        revision);
  }
}
