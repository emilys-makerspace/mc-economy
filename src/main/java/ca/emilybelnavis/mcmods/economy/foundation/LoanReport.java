package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.loan.*;
import java.time.Instant;
import java.util.*;

/** Bounded labels from captured contracts and receipts, without raw private payloads. */
public final class LoanReport {
  private LoanReport() {}

  public static List<String> owner(LoanDiagnostics.ContractView view) {
    var l = view.contract();
    var s = l.servicing();
    var lines = new ArrayList<String>();
    lines.add(
        UiText.t(
            "ui.finance.loanreport.1",
            l.id(),
            l.lender().key(),
            CreditReport.label(l.status()),
            l.revision()));
    lines.add(
        UiText.t(
            "ui.finance.loanreport.2",
            CreditReport.label(l.product()),
            l.currency().code(),
            CreditReport.money(l.currency(), l.originalPrincipalMinor())));
    lines.add(
        UiText.t(
            "ui.finance.loanreport.3",
            UiText.decimal(l.apr().movePointRight(2).stripTrailingZeros()),
            l.primeApr(),
            l.primeVersion(),
            l.policyVersion()));
    lines.add(
        UiText.t("ui.finance.loanreport.4", l.capturedScore(), l.profileRevision(), l.tier()));
    lines.add(
        UiText.t(
            "ui.finance.loanreport.5",
            l.years(),
            CreditReport.money(l.currency(), s.installmentMinor())));
    lines.add(
        UiText.t(
            "ui.finance.loanreport.6",
            CreditReport.money(l.currency(), s.principalMinor()),
            CreditReport.money(l.currency(), s.interestMinor())));
    lines.add(
        UiText.t(
            "ui.finance.loanreport.7", s.accruedMajor(), s.residualMajor(), l.currency().code()));
    lines.add(
        UiText.t(
            "ui.finance.loanreport.8",
            (view.current()
                ? UiText.t(
                    "ui.finance.amount", UiText.decimal(view.exactDebtMajor()), l.currency().code())
                : UiText.t("ui.finance.snapshot_only"))));
    lines.add(
        UiText.t(
            "ui.finance.loanreport.9",
            CreditReport.money(l.currency(), view.requiredMinor()),
            CreditReport.money(l.currency(), view.pastDueMinor()),
            date(view.oldestDueAt())));
    lines.add(
        UiText.t(
            "ui.finance.loanreport.10",
            (l.status() != Loans.Status.ACTIVE ? UiText.t("ui.finance.none") : date(l.nextMonth())),
            view.overdueMonths(),
            CreditReport.label(view.availability())));
    if (l.status() == Loans.Status.DEFAULTED) lines.add(UiText.t("ui.finance.loanreport.11"));
    lines.add(
        UiText.t(
            "ui.finance.loanreport.12",
            (s.closed()
                ? UiText.t("ui.finance.settled")
                : UiText.t("ui.finance.unsecured_receivable"))));
    return List.copyOf(lines);
  }

  public static List<String> schedule(LoanDiagnostics.SchedulePage page) {
    var lines = new ArrayList<String>();
    lines.add(UiText.t("ui.finance.loanreport.13", page.version(), page.scheduleHash()));
    for (var row : page.entries())
      lines.add(
          UiText.t(
              "ui.finance.loanreport.14",
              row.ordinal(),
              date(row.dueAt()),
              row.paymentMinor(),
              row.principalMinor(),
              row.interestMinor(),
              row.remainingPrincipalMinor(),
              page.currency().code()));
    if (page.entries().isEmpty()) lines.add(UiText.t("ui.finance.loanreport.15"));
    if (String.valueOf(page.nextOrdinal()) != null)
      lines.add(UiText.t("ui.finance.loanreport.16", String.valueOf(page.nextOrdinal())));
    return List.copyOf(lines);
  }

  public static List<String> installments(Loans.InstallmentPage page) {
    var lines = new ArrayList<String>();
    lines.add(UiText.t("ui.finance.loanreport.17"));
    for (var row : page.installments())
      lines.add(
          UiText.t(
              "ui.finance.loanreport.18",
              row.ordinal(),
              date(row.dueAt()),
              row.originalMinor(),
              row.earnedMajor(),
              row.postedInterestMinor(),
              row.debtMinor()));
    if (page.installments().isEmpty()) lines.add(UiText.t("ui.finance.loanreport.19"));
    if (String.valueOf(page.nextOrdinal()) != null)
      lines.add(UiText.t("ui.finance.loanreport.20", String.valueOf(page.nextOrdinal())));
    return List.copyOf(lines);
  }

  public static List<String> payoff(Loans.PaymentQuote quote) {
    var r = quote.request();
    return List.of(
        UiText.t(
            "ui.finance.loanreport.21",
            CreditReport.money(quote.payoff().source().currency(), r.amountMinor())),
        UiText.t(
            "ui.finance.loanreport.22",
            date(r.capturedAt()),
            date(r.expiresAt()),
            r.loanRevision(),
            r.fundingRevision(),
            r.fundingLedgerRevision()),
        UiText.t("ui.finance.loanreport.23"),
        UiText.t("ui.finance.loanreport.24"));
  }

  public static List<String> application(Loans.ApplicationDecision d) {
    return List.of(
        UiText.t(
            "ui.finance.loanreport.25",
            d.application(),
            CreditReport.label(d.reason()),
            date(d.effectiveAt())),
        UiText.t(
            "ui.finance.loanreport.26",
            d.lender().key(),
            CreditReport.label(d.product()),
            CreditReport.money(d.currency(), d.requestedMinor()),
            d.years()),
        UiText.t(
            "ui.finance.loanreport.27",
            (d.established()
                ? UiText.t("ui.finance.profile", d.capturedScore(), d.profileRevision())
                : UiText.t("ui.finance.not_established")),
            d.tier(),
            d.policyVersion()),
        UiText.t(
            "ui.finance.loanreport.28", d.primeApr(), d.primeVersion(), d.apr(), d.maximumYears()),
        UiText.t(
            "ui.finance.loanreport.29",
            d.currentExposureWorldMinor(),
            d.maximumPrincipalWorldMinor(),
            d.totalExposureCeilingWorldMinor(),
            d.worldCurrency().code(),
            d.fxPublication()));
  }

  public static List<String> operator(LoanDiagnostics.OperatorView view) {
    var lines = new ArrayList<>(owner(view.contract()));
    var r = view.reconciliation();
    lines.add(UiText.t("ui.finance.loanreport.30", view.journalFrontier(), date(view.asOf())));
    lines.add(
        UiText.t(
            "ui.finance.loanreport.31",
            r.ownerCurrencyDebtMinor(),
            r.borrowerLiabilityMinor(),
            r.ownerMatches()));
    lines.add(
        UiText.t(
            "ui.finance.loanreport.32",
            r.issuerCurrencyDebtMinor(),
            r.issuerReceivableMinor(),
            r.issuerMatches()));
    lines.add(
        UiText.t(
            "ui.finance.loanreport.33",
            r.ownerJournalMinor(),
            r.ownerJournalComplete(),
            r.ownerJournalMatches()));
    lines.add(
        UiText.t(
            "ui.finance.loanreport.34",
            r.issuerJournalMinor(),
            r.issuerJournalComplete(),
            r.issuerJournalMatches()));
    if (!r.ownerFacilitiesComplete()
        || !r.issuerFacilitiesComplete()
        || !r.ownerJournalComplete()
        || !r.issuerJournalComplete()) lines.add(UiText.t("ui.finance.loanreport.35"));
    lines.add(UiText.t("ui.finance.loanreport.36", view.provider()));
    lines.add(view.currentPrimeStatus());
    view.application().ifPresent(d -> lines.addAll(application(d)));
    view.funding().ifPresent(f -> lines.add(UiText.t("ui.finance.loanreport.37", f)));
    if (view.funding().isEmpty()) lines.add(UiText.t("ui.finance.loanreport.38"));
    for (var e : view.events().entries()) {
      lines.add(
          UiText.t(
              "ui.finance.creditreport.50",
              e.sequence(),
              e.position(),
              e.version(),
              e.kind(),
              date(e.effectiveAt()),
              new TreeMap<>(e.details())));
      var p = e.payment();
      if (p != null) {
        lines.add(
            UiText.t(
                "ui.finance.loanreport.39",
                p.amountMinor(),
                p.principalMinor(),
                p.interestMinor(),
                p.journalMatches()));
        lines.add(
            UiText.t(
                "ui.finance.loanreport.40",
                p.recognizedInterestMinor(),
                p.residualDispositionMajor(),
                p.additionalInterestWaivedMajor()));
        lines.add(UiText.t("ui.finance.loanreport.41", p.allocations().size()));
      }
    }
    var next = view.events().nextCursor();
    if (next != null)
      lines.add(
          UiText.t(
              "ui.finance.loanreport.42",
              String.valueOf(next.sequence()),
              String.valueOf(next.position())));
    return List.copyOf(lines);
  }

  private static String date(Long time) {
    return time == null ? UiText.t("ui.finance.none") : Instant.ofEpochMilli(time).toString();
  }
}
