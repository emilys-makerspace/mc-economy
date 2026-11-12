package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.credit.*;
import java.time.Instant;
import java.util.*;

/**
 * Bounded owner-facing labels; token identities and raw financial payloads never reach the screen.
 */
public final class CreditReport {
  private CreditReport() {}

  public static List<String> owner(CreditDiagnostics.FacilityView v) {
    var c = v.currency();
    var lines = new ArrayList<String>();
    lines.add(v.issuer() + " · " + v.network());
    lines.add(UiText.t("ui.finance.creditreport.1", label(v.status()), label(v.access())));
    lines.add(UiText.t("ui.finance.creditreport.2", money(c, v.limitMinor())));
    lines.add(UiText.t("ui.finance.creditreport.3", money(c, v.availableCreditMinor())));
    lines.add(UiText.t("ui.finance.creditreport.4"));
    lines.add(UiText.t("ui.finance.creditreport.5", money(c, v.currentBalanceMinor())));
    lines.add(UiText.t("ui.finance.creditreport.6", money(c, v.principalMinor())));
    lines.add(UiText.t("ui.finance.creditreport.7", money(c, v.postedInterestMinor())));
    lines.add(
        UiText.t("ui.finance.creditreport.8", UiText.decimal(v.exactBalanceMajor()), c.code()));
    bucket(lines, c, v.purchase());
    bucket(lines, c, v.advance());
    lines.add(UiText.t("ui.finance.creditreport.9", money(c, v.requiredMinor())));
    lines.add(UiText.t("ui.finance.creditreport.10", money(c, v.pastDueMinor())));
    lines.add(UiText.t("ui.finance.creditreport.11", date(v.dueAt())));
    if (v.latestStatement() != null) {
      var s = v.latestStatement();
      lines.add(UiText.t("ui.finance.creditreport.12", money(c, s.balanceMinor())));
      lines.add(UiText.t("ui.finance.creditreport.13", date(s.closedAt()), date(s.dueAt())));
    } else lines.add(UiText.t("ui.finance.creditreport.14"));
    if (v.status().equals("ACTIVE")) {
      lines.add(UiText.t("ui.finance.creditreport.15", date(v.nextDay())));
      lines.add(UiText.t("ui.finance.creditreport.16", date(v.nextStatement())));
    } else lines.add(UiText.t("ui.finance.creditreport.17"));
    if (!v.scheduleCurrent()) lines.add(UiText.t("ui.finance.creditreport.18"));
    return List.copyOf(lines);
  }

  private static void bucket(
      List<String> lines, EconomyCurrency currency, CreditDiagnostics.BucketView b) {
    lines.add(
        UiText.t(
            "ui.finance.creditreport.19",
            label(b.kind()),
            money(currency, b.principalMinor()),
            money(currency, b.postedInterestMinor())));
    lines.add(
        UiText.t(
            "ui.finance.creditreport.20",
            UiText.decimal(b.apr().movePointRight(2).stripTrailingZeros()),
            UiText.decimal(b.periodEarnedMajor()),
            currency.code()));
    lines.add(
        UiText.t("ui.finance.creditreport.21", UiText.decimal(b.residualMajor()), currency.code()));
  }

  public static List<String> statement(
      CreditFacilities.StatementSnapshot snapshot, EconomyCurrency currency) {
    var s = snapshot.statement();
    return List.of(
        UiText.t("ui.finance.creditreport.22", s.id()),
        UiText.t("ui.finance.creditreport.23", date(s.closedAt()), date(s.dueAt())),
        UiText.t("ui.finance.creditreport.24", money(currency, s.debtMinor())),
        UiText.t("ui.finance.creditreport.25", money(currency, s.principalMinor())),
        UiText.t(
            "ui.finance.creditreport.26", UiText.decimal(s.periodInterestMajor()), currency.code()),
        UiText.t(
            "ui.finance.creditreport.27",
            money(currency, s.newRequirementMinor()),
            money(currency, s.totalRequiredMinor())),
        UiText.t("ui.finance.creditreport.28"));
  }

  public static List<String> operator(
      CreditDiagnostics.OperatorView view, String section, int allocationOffset) {
    if (!Set.of("daily", "payments", "events").contains(section)
        || allocationOffset < 0
        || allocationOffset > 260)
      throw new IllegalArgumentException("Credit report section/cursor");
    var lines = new ArrayList<String>();
    var f = view.facility();
    var c = f.currency();
    var r = view.reconciliation();
    lines.add(UiText.t("ui.finance.creditreport.29", f.id(), f.revision(), view.journalFrontier()));
    lines.add(
        UiText.t(
            "ui.finance.creditreport.30",
            money(c, f.currentBalanceMinor()),
            (f.latestStatement() == null
                ? UiText.t("ui.finance.none_title")
                : money(c, f.latestStatement().balanceMinor()))));
    lines.add(
        UiText.t(
            "ui.finance.creditreport.31",
            r.ownerCurrencyDebtMinor(),
            r.borrowerLiabilityMinor(),
            r.ownerMatches()));
    lines.add(
        UiText.t(
            "ui.finance.creditreport.32",
            r.issuerCurrencyDebtMinor(),
            r.issuerReceivableMinor(),
            r.issuerMatches()));
    lines.add(
        UiText.t(
            "ui.finance.creditreport.33",
            r.ownerJournalMinor(),
            r.ownerJournalComplete(),
            r.ownerJournalMatches()));
    lines.add(
        UiText.t(
            "ui.finance.creditreport.34",
            r.issuerJournalMinor(),
            r.issuerJournalComplete(),
            r.issuerJournalMatches()));
    if (!r.ownerFacilitiesComplete()
        || !r.issuerFacilitiesComplete()
        || !r.ownerJournalComplete()
        || !r.issuerJournalComplete()) lines.add(UiText.t("ui.finance.creditreport.35"));
    lines.add(
        UiText.t(
            "ui.finance.creditreport.36",
            view.schedule().overdueDays(),
            view.schedule().overdueStatements()));
    for (var p : view.providers())
      lines.add(
          UiText.t(
              "ui.finance.creditreport.37",
              p.provider(),
              date(p.completedAt()),
              date(p.applyingAt()),
              p.applyingCursor()));
    CreditDiagnostics.EventCursor next;
    switch (section) {
      case "daily" -> {
        for (var receipt : view.daily().entries()) {
          var w = receipt.witness();
          lines.add(
              UiText.t(
                  "ui.finance.creditreport.38",
                  receipt.sequence(),
                  receipt.position(),
                  receipt.version(),
                  w.policyVersion(),
                  date(w.effective())));
          lines.add(
              UiText.t(
                  "ui.finance.creditreport.39",
                  w.beforePurchase().principalMinor(),
                  w.beforePurchase().interestMinor(),
                  w.beforePurchase().residualMajor(),
                  w.beforePurchase().apr()));
          lines.add(
              UiText.t(
                  "ui.finance.creditreport.40",
                  w.purchase().earnedMajor(),
                  c.code(),
                  w.purchase().postedMinor(),
                  w.purchase().bucket().residualMajor()));
          lines.add(
              UiText.t(
                  "ui.finance.creditreport.41",
                  w.beforeAdvance().principalMinor(),
                  w.beforeAdvance().interestMinor(),
                  w.beforeAdvance().residualMajor(),
                  w.beforeAdvance().apr()));
          lines.add(
              UiText.t(
                  "ui.finance.creditreport.42",
                  w.advance().earnedMajor(),
                  c.code(),
                  w.advance().postedMinor(),
                  w.advance().bucket().residualMajor()));
        }
        if (view.daily().entries().isEmpty()) lines.add(UiText.t("ui.finance.creditreport.43"));
        next = view.daily().nextCursor();
      }
      case "payments" -> {
        if (view.payments().entries().size() > 1)
          throw new IllegalArgumentException("Payment report uses one receipt per page");
        for (var receipt : view.payments().entries()) {
          var w = receipt.witness();
          lines.add(
              UiText.t(
                  "ui.finance.creditreport.44",
                  receipt.sequence(),
                  receipt.position(),
                  receipt.version(),
                  date(w.effective())));
          lines.add(
              UiText.t(
                  "ui.finance.creditreport.45",
                  money(c, w.amountMinor()),
                  money(c, w.principalMinor()),
                  money(c, w.interestMinor())));
          lines.add(UiText.t("ui.finance.creditreport.46", w.waivedResidualMajor(), c.code()));
          if (allocationOffset > w.allocations().size())
            throw new IllegalArgumentException("Allocation offset");
          int end = Math.min(w.allocations().size(), allocationOffset + 16);
          for (var a : w.allocations().subList(allocationOffset, end))
            lines.add(
                UiText.t(
                    "ui.finance.creditreport.47",
                    (a.statementId().isEmpty() ? UiText.t("ui.finance.unbilled") : a.statementId()),
                    label(a.kind()),
                    (a.interest()
                        ? UiText.t("ui.finance.interest")
                        : UiText.t("ui.finance.principal")),
                    money(c, a.minor())));
          if (end < w.allocations().size())
            lines.add(UiText.t("ui.finance.creditreport.48", String.valueOf(end)));
        }
        if (view.payments().entries().isEmpty()) lines.add(UiText.t("ui.finance.creditreport.49"));
        next = view.payments().nextCursor();
      }
      default -> {
        for (var event : view.legacyEvents().entries())
          lines.add(
              UiText.t(
                  "ui.finance.creditreport.50",
                  event.sequence(),
                  event.position(),
                  event.version(),
                  event.kind(),
                  date(event.effectiveAt()),
                  new TreeMap<>(event.details())));
        if (view.legacyEvents().entries().isEmpty())
          lines.add(UiText.t("ui.finance.creditreport.51"));
        next = view.legacyEvents().nextCursor();
      }
    }
    if (next != null)
      lines.add(
          UiText.t(
              "ui.finance.creditreport.52",
              section,
              String.valueOf(next.sequence()),
              String.valueOf(next.position())));
    return List.copyOf(lines);
  }

  public static String money(EconomyCurrency c, long minor) {
    return UiText.money(new ca.emilybelnavis.mcmods.economy.api.Money(c, minor));
  }

  /** Presentation only: canonical codes remain unchanged in domain objects and requests. */
  public static String label(Object value) {
    String code = value instanceof Enum<?> enumeration ? enumeration.name() : String.valueOf(value);
    return switch (code) {
      case "ACTIVE" -> UiText.t("ui.finance.label.active");
      case "DEFAULTED" -> UiText.t("ui.finance.label.defaulted");
      case "SETTLED" -> UiText.t("ui.finance.label.settled");
      case "ENABLED" -> UiText.t("ui.finance.label.enabled");
      case "SUSPENDED" -> UiText.t("ui.finance.label.suspended");
      case "CLOSED" -> UiText.t("ui.finance.label.closed");
      case "PURCHASE" -> UiText.t("ui.finance.label.purchase");
      case "ADVANCE" -> UiText.t("ui.finance.label.advance");
      case "CARD" -> UiText.t("ui.finance.label.card");
      case "LOAN" -> UiText.t("ui.finance.label.loan");
      case "OVERDRAFT" -> UiText.t("ui.finance.label.overdraft");
      case "BANK_LOAN" -> UiText.t("ui.finance.label.bank_loan");
      case "SUBPRIME_LOAN" -> UiText.t("ui.finance.label.subprime_loan");
      case "PENDING" -> UiText.t("ui.finance.label.pending");
      case "SOLD" -> UiText.t("ui.finance.label.sold");
      case "COLLECTIBLE" -> UiText.t("ui.finance.label.collectible");
      case "UNCOLLECTABLE" -> UiText.t("ui.finance.label.uncollectable");
      case "UNPAID" -> UiText.t("ui.finance.label.unpaid");
      case "PARTIAL" -> UiText.t("ui.finance.label.partial");
      case "COLLECTIONS" -> UiText.t("ui.finance.label.collections");
      case "DEFAULT" -> UiText.t("ui.finance.label.default");
      case "PAID_DEFAULT" -> UiText.t("ui.finance.label.paid_default");
      case "EXPIRED" -> UiText.t("ui.finance.label.expired");
      case "CURRENT" -> UiText.t("ui.finance.label.current");
      case "DELINQUENT" -> UiText.t("ui.finance.label.delinquent");
      case "ISSUED" -> UiText.t("ui.finance.label.issued");
      case "REVOKED" -> UiText.t("ui.finance.label.revoked");
      case "APPROVED" -> UiText.t("ui.finance.label.approved");
      case "UNDERWRITING_INPUTS_UNAVAILABLE" ->
          UiText.t("ui.finance.label.underwriting_inputs_unavailable");
      case "CREDIT_UNESTABLISHED" -> UiText.t("ui.finance.label.credit_unestablished");
      case "TIER2_UNAVAILABLE" -> UiText.t("ui.finance.label.tier2_unavailable");
      case "ARREARS_DEFAULT_OR_COLLECTION" ->
          UiText.t("ui.finance.label.arrears_default_or_collection");
      case "SCORE_BELOW_PRODUCT_FLOOR" -> UiText.t("ui.finance.label.score_below_product_floor");
      case "TERM_NOT_PERMITTED" -> UiText.t("ui.finance.label.term_not_permitted");
      case "WHOLE_MAJOR_PRINCIPAL_REQUIRED" ->
          UiText.t("ui.finance.label.whole_major_principal_required");
      case "PRINCIPAL_BELOW_MINIMUM" -> UiText.t("ui.finance.label.principal_below_minimum");
      case "PRODUCT_PRINCIPAL_CEILING" -> UiText.t("ui.finance.label.product_principal_ceiling");
      case "OWNER_EXPOSURE_CEILING" -> UiText.t("ui.finance.label.owner_exposure_ceiling");
      case "MONTHLY_CATCHUP_REQUIRED" -> UiText.t("ui.finance.label.monthly_catchup_required");
      case "TIME_BEFORE_SNAPSHOT" -> UiText.t("ui.finance.label.time_before_snapshot");
      case "REPORT_REVISION_NOT_EFFECTIVE" ->
          UiText.t("ui.finance.label.report_revision_not_effective");
      case "PAYMENT_SCORE_WINDOW_EXPIRED" ->
          UiText.t("ui.finance.label.payment_score_window_expired");
      case "PAYMENT_SUPPRESSED_BY_DEFAULT" ->
          UiText.t("ui.finance.label.payment_suppressed_by_default");
      case "ORIGINAL_OBLIGATION_MET_AT_REPORT" ->
          UiText.t("ui.finance.label.original_obligation_met_at_report");
      case "ORIGINAL_OBLIGATION_MISSED_AT_REPORT" ->
          UiText.t("ui.finance.label.original_obligation_missed_at_report");
      case "REQUIREMENT_CLEARED_UNCOLLECTABLE_DEBT_REMAINS" ->
          UiText.t("ui.finance.label.requirement_cleared_uncollectable_debt_remains");
      case "CURED_ORIGINAL_MISSED_SNAPSHOT_RETAINED" ->
          UiText.t("ui.finance.label.cured_original_missed_snapshot_retained");
      case "ORIGINAL_OBLIGATION_SATISFIED" ->
          UiText.t("ui.finance.label.original_obligation_satisfied");
      case "ORIGINAL_OBLIGATION_NOT_PAST_DUE" ->
          UiText.t("ui.finance.label.original_obligation_not_past_due");
      case "MISSED_OBLIGATION_REPORTED" -> UiText.t("ui.finance.label.missed_obligation_reported");
      case "ARREARS_REPORT_PENDING" -> UiText.t("ui.finance.label.arrears_report_pending");
      case "REPORT_CATCHUP_REQUIRED" -> UiText.t("ui.finance.label.report_catchup_required");
      case "DEBT_SETTLED" -> UiText.t("ui.finance.label.debt_settled");
      case "DEBT_UNCOLLECTABLE" -> UiText.t("ui.finance.label.debt_uncollectable");
      case "BEFORE_ORIGINAL_DEFAULT" -> UiText.t("ui.finance.label.before_original_default");
      case "BEFORE_LAST_COMMITTED_ACKNOWLEDGEMENT" ->
          UiText.t("ui.finance.label.before_last_committed_acknowledgement");
      case "COLLECTION_WINDOW_EXPIRED_TRANSITION_PENDING" ->
          UiText.t("ui.finance.label.collection_window_expired_transition_pending");
      case "ORIGIN_CREDITOR_COLLECTION_ELIGIBLE_SALE_PENDING" ->
          UiText.t("ui.finance.label.origin_creditor_collection_eligible_sale_pending");
      case "CURRENT_CREDITOR_COLLECTION_ELIGIBLE" ->
          UiText.t("ui.finance.label.current_creditor_collection_eligible");
      case "DEFAULT_REPORT_EXPIRED" -> UiText.t("ui.finance.label.default_report_expired");
      case "LATER_CURE_ORIGINAL_MISSED_SNAPSHOT_RETAINED" ->
          UiText.t("ui.finance.label.later_cure_original_missed_snapshot_retained");
      case "REPORT_REVISION_SUPERSEDED" -> UiText.t("ui.finance.label.report_revision_superseded");
      case "REPORT_REVISION_SELECTION_INCOMPLETE" ->
          UiText.t("ui.finance.label.report_revision_selection_incomplete");
      case "VALUATION_COMPLETE" -> UiText.t("ui.finance.label.valuation_complete");
      case "VALUATION_ACCOUNT_BOUND" -> UiText.t("ui.finance.label.valuation_account_bound");
      case "VALUATION_COMPONENT_BOUND" -> UiText.t("ui.finance.label.valuation_component_bound");
      case "VALUATION_FX_STALE" -> UiText.t("ui.finance.label.valuation_fx_stale");
      case "VALUATION_FX_UNAVAILABLE" -> UiText.t("ui.finance.label.valuation_fx_unavailable");
      case "VALUATION_INPUT_BOUND" -> UiText.t("ui.finance.label.valuation_input_bound");
      case "VALUATION_INVALID_INPUT" -> UiText.t("ui.finance.label.valuation_invalid_input");
      case "VALUATION_NEGATIVE_ASSET" -> UiText.t("ui.finance.label.valuation_negative_asset");
      case "VALUATION_RATE_MISSING" -> UiText.t("ui.finance.label.valuation_rate_missing");
      case "UNAVAILABLE" -> UiText.t("ui.finance.label.unavailable");
      default -> code;
    };
  }

  private static String date(Long value) {
    return value == null
        ? UiText.t("ui.finance.none_title")
        : Instant.ofEpochMilli(value).toString();
  }
}
