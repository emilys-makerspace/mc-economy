package ca.emilybelnavis.mcmods.economy.debt;

import static ca.emilybelnavis.mcmods.economy.debt.DebtRecords.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.credit.CreditFacilities;
import ca.emilybelnavis.mcmods.economy.credit.ScorePolicy;
import ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.InstitutionId;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.journal.Settlement;
import ca.emilybelnavis.mcmods.economy.loan.Loans;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable worker snapshots. Operator entry points require a separately audited service grant. */
public final class DebtDiagnostics {
  private static final long PAYMENT_REPORT_WINDOW = 364 * DAY;

  private DebtDiagnostics() {}

  public record CaseView(
      DebtRecords.Case debt,
      String state,
      String collectionReason,
      boolean collectionEligible,
      String reportStatus,
      boolean reportVisible,
      long asOf) {}

  public record CasePage(List<CaseView> entries, UUID nextCursor, long journalFrontier, long asOf) {
    public CasePage {
      entries = List.copyOf(entries);
    }
  }

  public record ReportView(
      Report report, boolean visible, String reason, long expiresAt, long asOf) {}

  public record ReportPage(
      List<ReportView> entries,
      DebtLifecycle.ReportCursor nextCursor,
      long journalFrontier,
      long asOf) {
    public ReportPage {
      entries = List.copyOf(entries);
    }
  }

  public record EventCursor(long sequence, int position) {
    public EventCursor {
      if (sequence < 0 || position < -1 || position > 63 || sequence == 0 && position != -1) {
        throw new IllegalArgumentException("Debt event cursor");
      }
    }

    public static EventCursor start() {
      return new EventCursor(0, -1);
    }
  }

  public record EventView(
      long sequence,
      int position,
      int version,
      String kind,
      long effectiveAt,
      long amountMinor,
      long allowanceMinor) {}

  public record EventPage(List<EventView> entries, EventCursor nextCursor, long journalFrontier) {
    public EventPage {
      entries = List.copyOf(entries);
    }
  }

  public record ObligationView(
      Obligation obligation,
      long reportDueAt,
      long reportExpiresAt,
      long arrearsAgeMillis,
      String reason) {}

  public record Reconciliation(
      long expectedReceivableMinor,
      long postedReceivableMinor,
      boolean receivableMatches,
      long expectedLiabilityMinor,
      long postedLiabilityMinor,
      boolean liabilityMatches,
      boolean facilityMatches) {}

  public record ProviderHead(
      String provider,
      String implementations,
      Long completedAt,
      Long applyingAt,
      String applyingCursor,
      long completedEffects) {}

  public record OperatorView(
      Kind kind,
      UUID facility,
      String facilityStatus,
      long facilityRevision,
      long facilityDebtMinor,
      EconomyCurrency currency,
      List<ObligationView> obligations,
      UUID nextObligationCursor,
      Long oldestUnmetDueAt,
      Long defaultDueAt,
      Optional<CaseView> debt,
      Reconciliation reconciliation,
      List<ProviderHead> providers,
      int debtPolicyVersion,
      int scorePolicyVersion,
      Long nextScoreBoundary,
      String scoreStatus,
      long journalFrontier,
      long asOf,
      Long nextSaleAttemptAt,
      String saleReason) {
    public OperatorView {
      obligations = List.copyOf(obligations);
      providers = List.copyOf(providers);
    }
  }

  public static CasePage ownerCases(
      JournalEngine.ReadView reads, PartyRef owner, UUID after, int limit, long now) {
    bounds(limit);
    instant(now);
    var player = player(owner);
    var cases = DebtLifecycle.cases(reads, owner, after, limit);
    for (var debt : cases) requireOwner(owner, player, debt);
    UUID next = null;
    if (cases.size() == limit) {
      var last = cases.getLast().id();
      var more =
          reads.query(
              "SELECT case_id FROM debt_cases WHERE world_id=? AND owner_id=? AND case_id>? ORDER BY case_id LIMIT 1",
              List.of(owner.world().value().toString(), player.value().toString(), last.toString()),
              1);
      if (!more.isEmpty()) next = last;
    }
    return new CasePage(
        cases.stream().map(debt -> caseView(debt, now)).toList(), next, frontier(reads), now);
  }

  public static ReportPage ownerReports(
      JournalEngine.ReadView reads,
      PartyRef owner,
      DebtLifecycle.ReportCursor after,
      int limit,
      long now) {
    bounds(limit);
    instant(now);
    player(owner);
    var page = DebtLifecycle.reports(reads, owner, Objects.requireNonNull(after), limit);
    var entries = new ArrayList<ReportView>();
    for (var report : page.entries()) {
      long expires = Math.addExact(report.reportAt(), PAYMENT_REPORT_WINDOW);
      var debt = selectedCase(reads, owner, report.kind(), report.facility());
      boolean suppressed = debt.filter(c -> DebtLifecycle.reportVisible(c, now)).isPresent();
      String selection = reportSelection(reads, owner, report, now);
      String reason =
          now < report.effectiveAt()
              ? "REPORT_REVISION_NOT_EFFECTIVE"
              : now >= expires
                  ? "PAYMENT_SCORE_WINDOW_EXPIRED"
                  : !selection.isEmpty()
                      ? selection
                      : suppressed
                          ? "PAYMENT_SUPPRESSED_BY_DEFAULT"
                          : report.metAtSnapshot()
                              ? "ORIGINAL_OBLIGATION_MET_AT_REPORT"
                              : "ORIGINAL_OBLIGATION_MISSED_AT_REPORT";
      entries.add(
          new ReportView(
              report,
              now >= report.effectiveAt() && now < expires && selection.isEmpty() && !suppressed,
              reason,
              expires,
              now));
    }
    return new ReportPage(entries, page.nextCursor(), frontier(reads), now);
  }

  public static Optional<EventPage> ownerEvents(
      JournalEngine.ReadView reads, PartyRef owner, UUID caseId, EventCursor after, int limit) {
    bounds(limit);
    player(owner);
    Objects.requireNonNull(after);
    var debt = DebtLifecycle.ownedCase(reads, owner, Objects.requireNonNull(caseId)).orElse(null);
    if (debt == null) return Optional.empty();
    long frontier = frontier(reads);
    var rows =
        reads.query(
            "SELECT sequence,position,record_version,payload FROM journal_records WHERE record_type='economy:a6/debt-accounting' AND substr(payload,38,36)=? AND sequence<=? AND (sequence,position)>(?,?) ORDER BY sequence,position LIMIT ?",
            List.of(
                caseId.toString(),
                Long.toString(frontier),
                Long.toString(after.sequence()),
                Integer.toString(after.position()),
                Integer.toString(limit + 1)),
            limit + 1);
    var entries = new ArrayList<EventView>();
    for (var row : rows.stream().limit(limit).toList()) {
      int version = Integer.parseInt(row.get("record_version"));
      if (version != VERSION)
        throw new IllegalArgumentException("Unsupported debt accounting version");
      DebtFinance.validateAccounting(row.get("payload"));
      var fields = row.get("payload").split("\\|", -1);
      if (!fields[0].equals(owner.world().value().toString())
          || !fields[1].equals(caseId.toString())) {
        throw new IllegalArgumentException("Debt accounting selection binding");
      }
      long sequence = number(row.get("sequence"));
      requireEventSnapshot(reads, debt, sequence);
      entries.add(
          new EventView(
              sequence,
              Integer.parseInt(row.get("position")),
              version,
              fields[2],
              number(fields[3]),
              number(fields[4]),
              number(fields[5])));
    }
    var last = entries.isEmpty() ? null : entries.getLast();
    return Optional.of(
        new EventPage(
            entries,
            rows.size() > limit ? new EventCursor(last.sequence(), last.position()) : null,
            frontier));
  }

  public static Optional<OperatorView> inspect(
      JournalEngine.ReadView reads,
      PartyRef owner,
      Kind kind,
      UUID facility,
      long now,
      UUID afterObligation,
      int limit) {
    bounds(limit);
    instant(now);
    var player = player(owner);
    var selected =
        facility(reads, owner, Objects.requireNonNull(kind), Objects.requireNonNull(facility));
    if (selected == null) return Optional.empty();
    var debt = selectedCase(reads, owner, kind, facility);
    var rows =
        reads.query(
            "SELECT * FROM debt_obligations WHERE world_id=? AND kind=? AND facility_id=? AND obligation_id>? ORDER BY obligation_id LIMIT ?",
            List.of(
                owner.world().value().toString(),
                kind.name(),
                facility.toString(),
                afterObligation == null ? "" : afterObligation.toString(),
                Integer.toString(limit + 1)),
            limit + 1);
    var obligations = new ArrayList<ObligationView>();
    for (var row : rows.stream().limit(limit).toList()) {
      var obligation = obligation(row);
      if (!obligation.world().equals(owner.world())
          || !obligation.owner().equals(player)
          || obligation.kind() != kind
          || !obligation.facility().equals(facility)
          || obligation.currency() != selected.currency())
        throw new IllegalArgumentException("Debt obligation selection binding");
      long reportAt = Math.addExact(obligation.dueAt(), 2 * DAY);
      boolean clearedUncollectable =
          obligation.unmetMinor() == 0
              && debt.filter(
                      c ->
                          c.collectability() == Collectability.UNCOLLECTABLE
                              && c.payment() != Payment.SETTLED)
                  .isPresent();
      String reason =
          clearedUncollectable
              ? "REQUIREMENT_CLEARED_UNCOLLECTABLE_DEBT_REMAINS"
              : obligation.unmetMinor() == 0
                  ? obligation.reported() && !obligation.metAtReport()
                      ? "CURED_ORIGINAL_MISSED_SNAPSHOT_RETAINED"
                      : "ORIGINAL_OBLIGATION_SATISFIED"
                  : now <= obligation.dueAt()
                      ? "ORIGINAL_OBLIGATION_NOT_PAST_DUE"
                      : obligation.reported()
                          ? "MISSED_OBLIGATION_REPORTED"
                          : now < reportAt ? "ARREARS_REPORT_PENDING" : "REPORT_CATCHUP_REQUIRED";
      obligations.add(
          new ObligationView(
              obligation,
              reportAt,
              Math.addExact(reportAt, PAYMENT_REPORT_WINDOW),
              obligation.unmetMinor() > 0 && now > obligation.dueAt()
                  ? now - obligation.dueAt()
                  : 0,
              reason));
    }
    var oldest =
        reads.query(
            "SELECT * FROM debt_obligations WHERE kind=? AND facility_id=? AND unmet_minor>0 ORDER BY due_at,obligation_id LIMIT 1",
            List.of(kind.name(), facility.toString()),
            1);
    Long oldestDue = null;
    if (!oldest.isEmpty()) {
      var original = obligation(oldest.getFirst());
      if (!original.world().equals(owner.world()) || !original.owner().equals(player))
        throw new IllegalArgumentException("Debt oldest obligation binding");
      oldestDue = original.dueAt();
    }
    UUID next = rows.size() > limit ? obligations.getLast().obligation().id() : null;
    var caseView = debt.map(c -> caseView(c, now));
    var providers = new ArrayList<ProviderHead>();
    for (String mode : List.of("due", "default", "sale", "expiry", "report", "score")) {
      providers.add(provider(reads, owner, kind, facility, debt, mode));
    }
    Long nextSale =
        debt.flatMap(
                c ->
                    reads
                        .query(
                            "SELECT next_at FROM debt_sale_attempts WHERE world_id=? AND case_id=?",
                            List.of(owner.world().value().toString(), c.id().toString()),
                            1)
                        .stream()
                        .findFirst())
            .map(r -> number(r.get("next_at")))
            .orElse(null);
    return Optional.of(
        new OperatorView(
            kind,
            facility,
            selected.status(),
            selected.revision(),
            selected.debtMinor(),
            selected.currency(),
            obligations,
            next,
            oldestDue,
            debt.isPresent()
                ? debt.orElseThrow().defaultAt()
                : oldestDue == null ? null : Math.addExact(oldestDue, DEFAULT_DELAY),
            caseView,
            reconcile(reads, owner, selected, debt),
            providers,
            VERSION,
            ScorePolicy.VERSION,
            nextScoreBoundary(reads, owner, now),
            ca.emilybelnavis.mcmods.economy.credit.CreditSafety.reportingStatus(reads),
            frontier(reads),
            now,
            nextSale,
            saleReason(reads, debt)));
  }

  public static CaseView caseView(DebtRecords.Case debt, long now) {
    instant(now);
    String reason =
        debt.payment() == Payment.SETTLED
            ? "DEBT_SETTLED"
            : debt.collectability() == Collectability.UNCOLLECTABLE
                ? "DEBT_UNCOLLECTABLE"
                : now < debt.defaultAt()
                    ? "BEFORE_ORIGINAL_DEFAULT"
                    : now < debt.acknowledgedAt()
                        ? "BEFORE_LAST_COMMITTED_ACKNOWLEDGEMENT"
                        : now >= debt.collectionExpires()
                            ? "COLLECTION_WINDOW_EXPIRED_TRANSITION_PENDING"
                            : debt.sale() == Sale.PENDING
                                ? "ORIGIN_CREDITOR_COLLECTION_ELIGIBLE_SALE_PENDING"
                                : "CURRENT_CREDITOR_COLLECTION_ELIGIBLE";
    boolean visible = DebtLifecycle.reportVisible(debt, now);
    String reportStatus =
        now < debt.defaultAt()
            ? "BEFORE_ORIGINAL_DEFAULT"
            : !visible
                ? "DEFAULT_REPORT_EXPIRED"
                : debt.payment() == Payment.SETTLED ? "PAID_DEFAULT" : "DEFAULT";
    return new CaseView(
        debt,
        DebtLifecycle.caseState(debt),
        reason,
        DebtLifecycle.demands(debt, now),
        reportStatus,
        visible,
        now);
  }

  private record Facility(
      Kind kind,
      UUID id,
      InstitutionId origin,
      EconomyCurrency currency,
      String status,
      long revision,
      long debtMinor) {}

  private static Facility facility(
      JournalEngine.ReadView reads, PartyRef owner, Kind kind, UUID id) {
    if (kind == Kind.CARD) {
      var card = CreditFacilities.find(reads, id).filter(c -> c.party().equals(owner)).orElse(null);
      return card == null
          ? null
          : new Facility(
              kind,
              id,
              card.issuer(),
              card.currency(),
              card.status().name(),
              card.revision(),
              card.owedMinor());
    }
    var loan = Loans.find(reads, id).filter(c -> c.party().equals(owner)).orElse(null);
    return loan == null
        ? null
        : new Facility(
            kind,
            id,
            loan.lender(),
            loan.currency(),
            loan.status().name(),
            loan.revision(),
            loan.owedMinor());
  }

  private static Optional<DebtRecords.Case> selectedCase(
      JournalEngine.ReadView reads, PartyRef owner, Kind kind, UUID facility) {
    var result = DebtLifecycle.facilityCase(reads, kind, facility);
    result.ifPresent(c -> requireOwner(owner, player(owner), c));
    return result;
  }

  private static Reconciliation reconcile(
      JournalEngine.ReadView reads,
      PartyRef owner,
      Facility facility,
      Optional<DebtRecords.Case> debt) {
    var creditor = debt.map(DebtRecords.Case::creditor).orElse(facility.origin());
    var borrower =
        new InstitutionId(
            (facility.kind() == Kind.CARD ? "economy:borrower/" : "economy:loan-borrower/")
                + player(owner).value());
    long receivable =
        Settlement.posted(
            reads,
            FinancialAccounts.id(
                owner.world(), creditor, FinancialAccounts.Role.RECEIVABLE, facility.currency()),
            facility.currency());
    long liability =
        Settlement.posted(
            reads,
            FinancialAccounts.id(
                owner.world(), borrower, FinancialAccounts.Role.LIABILITY, facility.currency()),
            facility.currency());
    boolean facilityMatches =
        debt.map(
                c ->
                    c.origin().equals(facility.origin())
                        && c.currency() == facility.currency()
                        && c.debtMinor() == facility.debtMinor()
                        && (c.payment() == Payment.SETTLED
                            ? facility.status().equals("SETTLED")
                            : facility.status().equals("DEFAULTED")))
            .orElse(true);
    return new Reconciliation(
        claim(reads, owner, creditor, FinancialAccounts.Role.RECEIVABLE, facility.currency()),
        receivable,
        DebtClaims.matches(
            reads, owner.world(), creditor, FinancialAccounts.Role.RECEIVABLE, facility.currency()),
        Math.negateExact(
            claim(reads, owner, borrower, FinancialAccounts.Role.LIABILITY, facility.currency())),
        liability,
        DebtClaims.matches(
            reads, owner.world(), borrower, FinancialAccounts.Role.LIABILITY, facility.currency()),
        facilityMatches);
  }

  private static long claim(
      JournalEngine.ReadView reads,
      PartyRef owner,
      InstitutionId party,
      FinancialAccounts.Role role,
      EconomyCurrency currency) {
    var rows =
        reads.query(
            "SELECT minor FROM debt_claim_totals WHERE world_id=? AND party=? AND role=? AND currency=?",
            List.of(owner.world().value().toString(), party.key(), role.name(), currency.name()),
            1);
    return rows.isEmpty() ? 0 : number(rows.getFirst().get("minor"));
  }

  private static ProviderHead provider(
      JournalEngine.ReadView reads,
      PartyRef owner,
      Kind kind,
      UUID facility,
      Optional<DebtRecords.Case> debt,
      String mode) {
    String id =
        mode.equals("score")
            ? ca.emilybelnavis.mcmods.economy.credit.CreditScoreRuntime.PROVIDER
            : "economy:a6/debt-" + mode;
    var rows =
        reads.query(
            "SELECT implementations,completed_ms FROM financial_deadline_providers WHERE provider=?",
            List.of(id),
            1);
    if (rows.isEmpty()) return new ProviderHead(id, "NOT_INSTALLED", null, null, "", 0);
    var applying =
        reads.query(
            "SELECT effective_ms,cursor,completed_effects FROM financial_deadline_events WHERE provider=? AND state='APPLYING' LIMIT 1",
            List.of(id),
            1);
    var row = rows.getFirst();
    if (applying.isEmpty())
      return new ProviderHead(
          id, row.get("implementations"), nullable(row.get("completed_ms")), null, "", 0);
    var event = applying.getFirst();
    String cursor = event.get("cursor");
    String redacted = "";
    if (!cursor.isEmpty()) {
      boolean selected =
          mode.equals("score")
              ? cursor.startsWith(player(owner).value() + "/")
              : cursor.equals(kind + "/" + facility)
                  || debt.filter(c -> cursor.equals(c.id().toString())).isPresent();
      if (!selected && (mode.equals("due") || mode.equals("report"))) {
        var source =
            reads.query(
                "SELECT owner_id,facility_id FROM debt_obligations WHERE world_id=? AND kind=? AND obligation_id=?",
                List.of(
                    owner.world().value().toString(),
                    kind.name(),
                    cursor.startsWith(kind + "/")
                        ? cursor.substring(kind.name().length() + 1)
                        : ""),
                1);
        selected =
            !source.isEmpty()
                && source.getFirst().get("owner_id").equals(player(owner).value().toString())
                && source.getFirst().get("facility_id").equals(facility.toString());
      }
      redacted = selected ? "THIS_SELECTION" : "OTHER_TARGET";
    }
    return new ProviderHead(
        id,
        row.get("implementations"),
        nullable(row.get("completed_ms")),
        number(event.get("effective_ms")),
        redacted,
        number(event.get("completed_effects")));
  }

  private static String saleReason(JournalEngine.ReadView reads, Optional<DebtRecords.Case> debt) {
    if (debt.isEmpty()) return "NO_DEFAULT_CASE";
    var selected = debt.orElseThrow();
    if (selected.sale() == Sale.SOLD) return "SOLD_TO_CURRENT_CREDITOR";
    var rows =
        reads.query(
            "SELECT r.sequence,r.record_version,r.payload FROM journal_records r WHERE record_type='economy:a6/debt-sale-attempt' AND substr(payload,1,36)=? ORDER BY sequence DESC,position DESC LIMIT 1",
            List.of(selected.id().toString()),
            1);
    if (rows.isEmpty()) return "SALE_PENDING_FIRST_ATTEMPT";
    var row = rows.getFirst();
    var fields = row.get("payload").split("\\|", -1);
    if (!row.get("record_version").equals("1")
        || fields.length != 3
        || !fields[0].equals(selected.id().toString())
        || number(fields[1]) < selected.defaultAt()
        || !fields[2].matches("[A-Z][A-Z0-9_]{0,95}")
        || fields[2].startsWith("BOOTSTRAP_")) {
      throw new IllegalArgumentException("Debt sale explanation binding");
    }
    var operation =
        reads.query(
            "SELECT world_id,service,operation_type FROM operation_outcomes WHERE sequence=?",
            List.of(row.get("sequence")),
            1);
    if (operation.isEmpty()
        || !operation.getFirst().get("world_id").equals(selected.world().value().toString())
        || operation.getFirst().get("operation_type").equals("economy:bootstrap")) {
      throw new IllegalArgumentException("Debt sale operation binding");
    }
    return fields[2];
  }

  private static String reportSelection(
      JournalEngine.ReadView reads, PartyRef owner, Report report, long now) {
    if (report.cured()) return "LATER_CURE_ORIGINAL_MISSED_SNAPSHOT_RETAINED";
    var rows =
        reads.query(
            "SELECT body FROM debt_reports WHERE world_id=? AND report_id=? AND effective_at<=? ORDER BY revision DESC LIMIT 17",
            List.of(owner.world().value().toString(), report.id().toString(), Long.toString(now)),
            17);
    for (var row : rows.stream().limit(16).toList()) {
      var candidate = decodeReport(row.get("body"));
      if (!candidate.world().equals(owner.world())
          || !candidate.owner().equals(player(owner))
          || !candidate.id().equals(report.id())
          || !candidate.obligation().equals(report.obligation())
          || !candidate.facility().equals(report.facility())
          || candidate.kind() != report.kind()
          || candidate.dueAt() != report.dueAt()
          || candidate.reportAt() != report.reportAt()
          || candidate.originalMinor() != report.originalMinor())
        throw new IllegalArgumentException("Debt report revision binding");
      if (!candidate.cured())
        return candidate.revision() == report.revision() ? "" : "REPORT_REVISION_SUPERSEDED";
    }
    return rows.size() > 16
        ? "REPORT_REVISION_SELECTION_INCOMPLETE"
        : "REPORT_REVISION_NOT_EFFECTIVE";
  }

  private static Long nextScoreBoundary(JournalEngine.ReadView reads, PartyRef owner, long now) {
    var head =
        reads.query(
            "SELECT next_at FROM credit_score_heads WHERE world_id=? AND owner_id=?",
            List.of(owner.world().value().toString(), player(owner).value().toString()),
            1);
    if (!head.isEmpty()) return number(head.getFirst().get("next_at"));
    var rows =
        reads.query("SELECT origin_ms FROM financial_calendar WHERE singleton=1", List.of(), 1);
    if (rows.isEmpty()) return null;
    long origin = number(rows.getFirst().get("origin_ms"));
    long cycle = now < origin ? 1 : Math.addExact((now - origin) / ScorePolicy.CYCLE_MILLIS, 1);
    return Math.addExact(origin, Math.multiplyExact(cycle, ScorePolicy.CYCLE_MILLIS));
  }

  private static Obligation obligation(Map<String, String> row) {
    var value = decodeObligation(row.get("body"));
    if (!row.get("world_id").equals(value.world().value().toString())
        || !row.get("kind").equals(value.kind().name())
        || !row.get("obligation_id").equals(value.id().toString())
        || !row.get("facility_id").equals(value.facility().toString())
        || !row.get("owner_id").equals(value.owner().value().toString())
        || !row.get("currency").equals(value.currency().name())
        || number(row.get("due_at")) != value.dueAt()
        || number(row.get("original_minor")) != value.originalMinor()
        || number(row.get("unmet_minor")) != value.unmetMinor()
        || number(row.get("revision")) != value.revision()
        || !row.get("reported").equals(value.reported() ? "1" : "0")
        || !row.get("met_at_report").equals(value.metAtReport() ? "1" : "0")) {
      throw new IllegalArgumentException("Debt obligation projection binding");
    }
    return value;
  }

  private static void requireEventSnapshot(
      JournalEngine.ReadView reads, DebtRecords.Case debt, long sequence) {
    var rows =
        reads.query(
            "SELECT record_version,payload FROM journal_records WHERE sequence=? AND record_type=? ORDER BY position LIMIT 64",
            List.of(Long.toString(sequence), CASE_FACT),
            64);
    for (var row : rows) {
      if (!row.get("record_version").equals("1"))
        throw new IllegalArgumentException("Unsupported debt case version");
      var source = decodeCase(row.get("payload"));
      if (source.id().equals(debt.id())) {
        if (!source.world().equals(debt.world())
            || !source.owner().equals(debt.owner())
            || source.kind() != debt.kind()
            || !source.facility().equals(debt.facility())
            || !source.origin().equals(debt.origin())
            || source.currency() != debt.currency()
            || source.defaultAt() != debt.defaultAt()
            || source.reportExpires() != debt.reportExpires())
          throw new IllegalArgumentException("Debt event owner binding");
        return;
      }
    }
    throw new IllegalArgumentException("Debt event source unavailable");
  }

  private static void requireOwner(PartyRef owner, PlayerId player, DebtRecords.Case debt) {
    if (!owner.world().equals(debt.world()) || !player.equals(debt.owner()))
      throw new IllegalArgumentException("Debt case owner binding");
  }

  private static long frontier(JournalEngine.ReadView reads) {
    return number(
        reads
            .query(
                "SELECT COALESCE(MAX(sequence),0) AS frontier FROM journal_transactions",
                List.of(),
                1)
            .getFirst()
            .get("frontier"));
  }

  private static PlayerId player(PartyRef owner) {
    if (!(owner.owner() instanceof PartyRef.Player player))
      throw new IllegalArgumentException("Debt player owner required");
    return player.id();
  }

  private static void bounds(int limit) {
    if (limit < 1 || limit > 16) throw new IllegalArgumentException("Debt diagnostic page bounds");
  }

  private static void instant(long now) {
    if (now < 0) throw new IllegalArgumentException("Debt diagnostic time");
  }

  private static Long nullable(String value) {
    return value == null ? null : number(value);
  }

  private static long number(String value) {
    long result = Long.parseLong(value);
    if (!Long.toString(result).equals(value))
      throw new IllegalArgumentException("Debt diagnostic number");
    return result;
  }
}
