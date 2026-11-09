package ca.emilybelnavis.mcmods.economy.credit;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreditDiagnosticsTest {
  @TempDir Path path;
  private static final InstitutionCatalog.InstitutionId BANK =
      new InstitutionCatalog.InstitutionId("economy:bank/crownstone");
  private static final long ANCHOR = 10_000L,
      CLOSE = ANCHOR + 28 * CreditFacilities.DAY,
      DUE = CLOSE + 14 * CreditFacilities.DAY;

  private final class Fixture implements AutoCloseable {
    final SqliteStorage store = new SqliteStorage();
    final PlayerId ownerId = new PlayerId(UUID.randomUUID());
    final PartyRef owner;
    final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    final CreditFacilities.Facility facility;

    Fixture() throws Exception {
      store.initialize(path.toRealPath());
      owner = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(ownerId));
      var purchase =
          new CardServicing.Bucket(
              CardServicing.Kind.PURCHASE,
              12300,
              7,
              new BigDecimal("-0.002451373626373626"),
              new BigDecimal("0.067548626373626374"),
              new BigDecimal("0.1999"));
      var advance = CardServicing.empty(CardServicing.Kind.ADVANCE, new BigDecimal("0.2199"));
      facility =
          new CreditFacilities.Facility(
              owner.world(),
              id,
              ownerId,
              BANK,
              InstitutionCatalog.Network.ORBIT,
              EconomyCurrency.CAD,
              250000,
              1,
              ANCHOR,
              ANCHOR + 41 * CreditFacilities.DAY,
              CLOSE,
              3,
              CreditFacilities.Status.ACTIVE,
              CreditFacilities.Access.ENABLED,
              purchase,
              advance,
              List.of(
                  new CardServicing.Requirement(
                      "b9cb9371-4f7b-49e4-a5f4-9e4abc767915", CLOSE, DUE, 280, 100)),
              List.of());
      store.transaction(
          tx -> {
            CreditFacilities.write(tx, facility);
            var borrower =
                new InstitutionCatalog.InstitutionId("economy:borrower/" + ownerId.value());
            party(tx, BANK);
            party(tx, borrower);
            var liability =
                FinancialAccounts.account(
                    owner.world(), borrower, FinancialAccounts.Role.LIABILITY, EconomyCurrency.CAD);
            var receivable =
                FinancialAccounts.account(
                    owner.world(), BANK, FinancialAccounts.Role.RECEIVABLE, EconomyCurrency.CAD);
            for (var a : List.of(liability, receivable)) FinancialAccounts.register(tx, a);
            LedgerProjection.write(tx, liability.id(), EconomyCurrency.CAD, -facility.owedMinor());
            LedgerProjection.write(tx, receivable.id(), EconomyCurrency.CAD, facility.owedMinor());
            String statement =
                pack(
                    List.of(
                        owner.world().value().toString(),
                        id.toString(),
                        ownerId.value().toString(),
                        UUID.randomUUID().toString(),
                        Long.toString(CLOSE),
                        Long.toString(DUE),
                        "15000",
                        "14000",
                        "1.000000000000000000",
                        "280",
                        "280"));
            tx.execute(
                "INSERT INTO credit_statements VALUES(?,?,?,?,?,?,?)",
                List.of(
                    owner.world().value().toString(),
                    id.toString(),
                    unpack(statement).get(3),
                    ownerId.value().toString(),
                    Long.toString(CLOSE),
                    Long.toString(DUE),
                    statement));
            return null;
          });
    }

    void party(FoundationStore.Transaction tx, InstitutionCatalog.InstitutionId bank) {
      tx.execute(
          "INSERT INTO institution_parties VALUES(?,?,?,?,?,'INTERNAL',1)",
          List.of(
              owner.world().value().toString(),
              bank.key(),
              new InstitutionCatalog.SystemActor(owner.world(), bank).actorId().value().toString(),
              "Fixture",
              EconomyCurrency.CAD.name()));
    }

    Journal.Outcome record(List<Journal.Record> records) {
      var kind = new Journal.Kind("fixture:credit-diagnostics", "fixture:record");
      return new JournalEngine(
              store,
              (a, k) -> true,
              Map.of(
                  kind,
                  new JournalEngine.Registration(
                      1,
                      (i, r) ->
                          new JournalEngine.Commit(
                              records, new Journal.Result(1, "FIXTURE", ""), tx -> {}))))
          .submit(
              new Journal.Intent(
                  new OperationId(UUID.randomUUID()),
                  new RequestId(UUID.randomUUID()),
                  ownerId,
                  kind,
                  new Journal.CanonicalRequest(1, Map.of()),
                  null,
                  null));
    }

    CreditDiagnostics.OperatorView inspect() {
      return store.transaction(
          tx ->
              CreditDiagnostics.inspect(
                      tx::query, owner, id, DUE + 1, CreditDiagnostics.EventCursor.start(), 16)
                  .orElseThrow());
    }

    @Override
    public void close() throws Exception {
      store.close();
    }
  }

  private static String pack(List<String> fields) {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    return String.join(
        "|",
        fields.stream()
            .map(v -> encoder.encodeToString(v.getBytes(StandardCharsets.UTF_8)))
            .toList());
  }

  private static List<String> unpack(String body) {
    return Arrays.stream(body.split("\\|", -1))
        .map(v -> new String(Base64.getUrlDecoder().decode(v), StandardCharsets.UTF_8))
        .toList();
  }

  @Test
  void ownerViewSeparatesCurrentDebtFrozenStatementAndDueAmountsWithoutTokens() throws Exception {
    try (var f = new Fixture()) {
      long sequence = f.store.lastCommittedSequence();
      var before =
          f.store.transaction(
              tx -> CreditDiagnostics.facility(tx::query, f.owner, f.id, DUE).orElseThrow());
      var after =
          f.store.transaction(
              tx -> CreditDiagnostics.facility(tx::query, f.owner, f.id, DUE + 1).orElseThrow());
      assertEquals(12300, after.principalMinor());
      assertEquals(7, after.postedInterestMinor());
      assertEquals(12307, after.currentBalanceMinor());
      assertEquals(15000, after.latestStatement().balanceMinor());
      assertEquals(280, after.latestStatement().newMinimumMinor());
      assertEquals(100, after.requiredMinor());
      assertEquals(0, before.pastDueMinor());
      assertEquals(100, after.pastDueMinor());
      assertEquals(DUE, after.dueAt());
      assertEquals(237693, after.availableCreditMinor());
      assertEquals(new BigDecimal("123.067548626373626374"), after.exactBalanceMajor());
      assertEquals(new BigDecimal("0.1999"), after.purchase().apr());
      assertEquals(1, after.latestStatement().version());
      assertFalse(after.toString().toLowerCase(Locale.ROOT).contains("token"));
      assertEquals(sequence, f.store.lastCommittedSequence());
      var stranger =
          new PartyRef(f.owner.world(), new PartyRef.Player(new PlayerId(UUID.randomUUID())));
      assertTrue(
          f.store
              .transaction(tx -> CreditDiagnostics.facility(tx::query, stranger, f.id, DUE))
              .isEmpty());
      assertTrue(
          f.store
              .transaction(
                  tx ->
                      CreditDiagnostics.inspect(
                          tx::query,
                          stranger,
                          f.id,
                          DUE,
                          CreditDiagnostics.EventCursor.start(),
                          16))
              .isEmpty());
      assertTrue(
          f.store
              .transaction(tx -> CreditDiagnostics.statements(tx::query, stranger, f.id, -1, 16))
              .statements()
              .isEmpty());
    }
  }

  @Test
  void ownerPagesUseExclusiveCursorAndReconciliationDetectsLedgerMismatch() throws Exception {
    try (var f = new Fixture()) {
      f.store.transaction(
          tx -> {
            var old = f.facility;
            CreditFacilities.write(
                tx,
                new CreditFacilities.Facility(
                    old.world(),
                    UUID.fromString("00000000-0000-0000-0000-000000000002"),
                    old.owner(),
                    old.issuer(),
                    old.network(),
                    old.currency(),
                    old.limitMinor(),
                    old.tier(),
                    old.anchor(),
                    old.lastDay(),
                    old.lastStatement(),
                    old.revision(),
                    old.status(),
                    old.access(),
                    CardServicing.empty(CardServicing.Kind.PURCHASE, old.purchase().apr()),
                    old.advance(),
                    List.of(),
                    List.of()));
            return null;
          });
      var first =
          f.store.transaction(tx -> CreditDiagnostics.ownerPage(tx::query, f.owner, null, 1, DUE));
      assertEquals(1, first.entries().size());
      assertEquals(f.id, first.nextCursor());
      var second =
          f.store.transaction(
              tx -> CreditDiagnostics.ownerPage(tx::query, f.owner, first.nextCursor(), 1, DUE));
      assertEquals(1, second.entries().size());
      assertNull(second.nextCursor());
      assertNotEquals(first.entries().getFirst().id(), second.entries().getFirst().id());
      assertTrue(f.inspect().reconciliation().ownerMatches());
      assertTrue(f.inspect().reconciliation().issuerMatches());
      var liability =
          FinancialAccounts.id(
              f.owner.world(),
              new InstitutionCatalog.InstitutionId("economy:borrower/" + f.ownerId.value()),
              FinancialAccounts.Role.LIABILITY,
              EconomyCurrency.CAD);
      f.store.transaction(
          tx -> {
            LedgerProjection.write(tx, liability, EconomyCurrency.CAD, -1);
            return null;
          });
      assertFalse(f.inspect().reconciliation().ownerMatches());
      assertTrue(f.inspect().reconciliation().issuerMatches());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              f.store.transaction(
                  tx -> CreditDiagnostics.ownerPage(tx::query, f.owner, null, 17, DUE)));
    }
  }

  @Test
  void immutableWitnessPagesReproduceDailyMathAndSanitizeLegacyPrivateReferences()
      throws Exception {
    try (var f = new Fixture()) {
      var p = CardServicing.day(EconomyCurrency.CAD, f.facility.purchase());
      var a = CardServicing.day(EconomyCurrency.CAD, f.facility.advance());
      var first = CreditAuditFacts.daily(f.facility, DUE, p, a);
      var token = UUID.randomUUID().toString();
      var account = UUID.randomUUID().toString();
      f.record(
          List.of(
              first,
              new Journal.Record(
                  CreditFacilities.EVENT_FACT,
                  1,
                  pack(
                      List.of(
                          "DRAW_PURCHASE",
                          f.id.toString(),
                          Long.toString(DUE),
                          "100|" + token + "|" + account)))));
      f.record(List.of(CreditAuditFacts.daily(f.facility, DUE, p, a)));
      var page =
          f.store.transaction(
              tx ->
                  CreditDiagnostics.inspect(
                          tx::query, f.owner, f.id, DUE, CreditDiagnostics.EventCursor.start(), 1)
                      .orElseThrow());
      assertEquals(1, page.daily().entries().size());
      assertNotNull(page.daily().nextCursor());
      assertEquals(p, page.daily().entries().getFirst().witness().purchase());
      assertEquals("100", page.legacyEvents().entries().getFirst().details().get("amountMinor"));
      assertFalse(page.legacyEvents().toString().contains(token));
      assertFalse(page.legacyEvents().toString().contains(account));
      var next =
          f.store.transaction(
              tx ->
                  CreditDiagnostics.inspect(
                          tx::query, f.owner, f.id, DUE, page.daily().nextCursor(), 1)
                      .orElseThrow());
      assertEquals(1, next.daily().entries().size());
      assertNull(next.daily().nextCursor());
      assertTrue(
          next.daily().entries().getFirst().sequence()
              > page.daily().entries().getFirst().sequence());
      var plan =
          explain(
              "EXPLAIN QUERY PLAN SELECT sequence,position FROM journal_records WHERE record_type IN('economy:a6/credit-daily-witness','economy:a6/credit-payment-allocation') AND record_type=? AND substr(payload,1,36)=? AND sequence<=? AND (sequence>? OR (sequence=? AND position>?)) ORDER BY sequence,position LIMIT ?",
              List.of(
                  CreditAuditFacts.DAILY,
                  f.id.toString(),
                  Long.toString(page.journalFrontier()),
                  "0",
                  "0",
                  "-1",
                  "2"));
      assertTrue(plan.toString().contains("credit_audit_records"), plan.toString());
      String encoded =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(f.id.toString().getBytes(StandardCharsets.UTF_8));
      var legacyPlan =
          explain(
              "EXPLAIN QUERY PLAN SELECT sequence,position FROM journal_records WHERE record_type IN('economy:a6/credit-event','economy:a6/credit-facility') AND record_type='economy:a6/credit-event' AND substr(payload,instr(payload,'|')+1,48)=? AND sequence<=? AND (sequence>? OR (sequence=? AND position>?)) ORDER BY sequence,position LIMIT ?",
              List.of(encoded, Long.toString(page.journalFrontier()), "0", "0", "-1", "2"));
      assertTrue(
          legacyPlan.toString().contains("credit_diagnostic_records"), legacyPlan.toString());
      var ownerPlan =
          explain(
              "EXPLAIN QUERY PLAN SELECT debt_minor FROM credit_facilities WHERE world_id=? AND owner_id=? AND currency=? AND debt_minor>0 ORDER BY facility_id LIMIT 129",
              List.of(f.owner.world().value().toString(), f.ownerId.value().toString(), "CAD"));
      assertTrue(
          ownerPlan.toString().contains("credit_diagnostic_owner_debt"), ownerPlan.toString());
      var issuerPlan =
          explain(
              "EXPLAIN QUERY PLAN SELECT debt_minor FROM credit_facilities WHERE world_id=? AND issuer=? AND currency=? AND debt_minor>0 ORDER BY facility_id LIMIT 129",
              List.of(f.owner.world().value().toString(), BANK.key(), "CAD"));
      assertTrue(issuerPlan.toString().contains("credit_facility_issuer"), issuerPlan.toString());
    }
  }

  @Test
  void journalReconciliationDetectsMatchingCorruptProjectionsAndReportsBoundedIncompleteHistory()
      throws Exception {
    try (var f = new Fixture()) {
      var initial = f.inspect().reconciliation();
      assertTrue(initial.ownerMatches());
      assertTrue(initial.ownerJournalComplete());
      assertFalse(initial.ownerJournalMatches());
      var borrower =
          FinancialAccounts.id(
              f.owner.world(),
              new InstitutionCatalog.InstitutionId("economy:borrower/" + f.ownerId.value()),
              FinancialAccounts.Role.LIABILITY,
              EconomyCurrency.CAD);
      var issuer =
          FinancialAccounts.id(
              f.owner.world(), BANK, FinancialAccounts.Role.RECEIVABLE, EconomyCurrency.CAD);
      f.record(
          List.of(
              posting(borrower, -f.facility.owedMinor()), posting(issuer, f.facility.owedMinor())));
      assertTrue(f.inspect().reconciliation().ownerJournalMatches());
      assertTrue(f.inspect().reconciliation().issuerJournalMatches());
      f.record(List.of(posting(borrower, -1), posting(issuer, 1)));
      var corrupt = f.inspect().reconciliation();
      assertTrue(corrupt.ownerMatches());
      assertTrue(corrupt.issuerMatches());
      assertFalse(corrupt.ownerJournalMatches());
      assertFalse(corrupt.issuerJournalMatches());
      for (int batch = 0; batch < 4; batch++) {
        var facts = new ArrayList<Journal.Record>();
        for (int n = 0; n < 32; n++) {
          facts.add(posting(borrower, -1));
          facts.add(posting(issuer, 1));
        }
        f.record(facts);
      }
      var bounded = f.inspect().reconciliation();
      assertFalse(bounded.ownerJournalComplete());
      assertFalse(bounded.issuerJournalComplete());
      assertFalse(bounded.ownerJournalMatches());
    }
  }

  @Test
  void ownerPaymentHistoryRequiresBoundSameTransactionAndRejectsOtherOwners() throws Exception {
    try (var f = new Fixture()) {
      var payment =
          CardServicing.repay(
              EconomyCurrency.CAD,
              List.of(f.facility.purchase(), f.facility.advance()),
              f.facility.requirements(),
              f.facility.slices(),
              100,
              DUE + 1);
      var fact = CreditAuditFacts.payment(f.facility, DUE + 1, 100, 93, 7, payment);
      f.record(List.of(CreditFacilities.fact(f.facility), fact));
      var page =
          f.store.transaction(
              tx ->
                  CreditDiagnostics.ownerPayments(
                          tx::query, f.owner, f.id, CreditDiagnostics.EventCursor.start(), 1)
                      .orElseThrow());
      assertEquals(100, page.entries().getFirst().witness().amountMinor());
      assertNull(page.nextCursor());
      var other =
          new PartyRef(f.owner.world(), new PartyRef.Player(new PlayerId(UUID.randomUUID())));
      assertTrue(
          f.store
              .transaction(
                  tx ->
                      CreditDiagnostics.ownerPayments(
                          tx::query, other, f.id, CreditDiagnostics.EventCursor.start(), 1))
              .isEmpty());
      f.record(List.of(fact));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              f.store.transaction(
                  tx ->
                      CreditDiagnostics.ownerPayments(
                          tx::query, f.owner, f.id, CreditDiagnostics.EventCursor.start(), 4)));
    }
  }

  @Test
  void allocationWitnessUsesFrozenPaymentAmountsAndOutstandingFacilityBoundsAreExplicit()
      throws Exception {
    try (var f = new Fixture()) {
      var payment =
          CardServicing.repay(
              EconomyCurrency.CAD,
              List.of(f.facility.purchase(), f.facility.advance()),
              f.facility.requirements(),
              f.facility.slices(),
              100,
              DUE + 1);
      f.record(List.of(CreditAuditFacts.payment(f.facility, DUE + 1, 100, 93, 7, payment)));
      var receipt = f.inspect().payments().entries().getFirst();
      assertEquals(100, receipt.witness().amountMinor());
      assertEquals(93, receipt.witness().principalMinor());
      assertEquals(7, receipt.witness().interestMinor());
      assertEquals(
          100,
          receipt.witness().allocations().stream()
              .mapToLong(CardServicing.Allocation::minor)
              .sum());
      f.store.transaction(
          tx -> {
            for (int n = 2; n <= 129; n++) {
              var old = f.facility;
              var id = new UUID(0, n);
              CreditFacilities.write(
                  tx,
                  new CreditFacilities.Facility(
                      old.world(),
                      id,
                      old.owner(),
                      old.issuer(),
                      old.network(),
                      old.currency(),
                      old.limitMinor(),
                      old.tier(),
                      old.anchor(),
                      old.lastDay(),
                      old.lastStatement(),
                      old.revision(),
                      old.status(),
                      CreditFacilities.Access.CLOSED,
                      old.purchase(),
                      old.advance(),
                      old.requirements(),
                      old.slices()));
            }
            return null;
          });
      var bounded = f.inspect().reconciliation();
      assertFalse(bounded.ownerFacilitiesComplete());
      assertFalse(bounded.issuerFacilitiesComplete());
      assertFalse(bounded.ownerMatches());
      assertFalse(bounded.issuerMatches());
    }
  }

  @Test
  void providerHeadsDoNotExposeAnotherOwnersFacilityCursor() throws Exception {
    try (var f = new Fixture()) {
      var foreign = UUID.randomUUID();
      f.store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO financial_deadline_providers(provider,implementations) VALUES(?,?)",
                List.of("economy:a6/credit-daily", "1:fixture;"));
            tx.execute(
                "INSERT INTO financial_deadline_events VALUES(?,?,?,1,1,?,0,'APPLYING',0)",
                List.of(
                    UUID.randomUUID().toString(),
                    "economy:a6/credit-daily",
                    Long.toString(DUE),
                    foreign.toString()));
            return null;
          });
      var view = f.inspect();
      assertEquals("OTHER_TARGET", view.providers().getFirst().applyingCursor());
      assertFalse(view.providers().toString().contains(foreign.toString()));
    }
  }

  private String explain(String sql, List<String> args) throws Exception {
    var result = new StringBuilder();
    try (var connection =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:file:" + path.resolve("economy/economy.sqlite") + "?mode=ro");
        var statement = connection.prepareStatement(sql)) {
      for (int n = 0; n < args.size(); n++) statement.setString(n + 1, args.get(n));
      try (var rows = statement.executeQuery()) {
        while (rows.next()) result.append(rows.getString(4)).append('\n');
      }
    }
    return result.toString();
  }

  private static Journal.Record posting(UUID account, long minor) {
    return new Journal.Record(
        "economy:posting", 3, account + "|INSTITUTION_CLAIM|CAD|" + minor + "|TRANSFER");
  }
}
