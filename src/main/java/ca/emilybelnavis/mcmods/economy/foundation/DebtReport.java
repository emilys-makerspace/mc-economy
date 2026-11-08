package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.debt.*;
import java.time.Instant;
import java.util.*;

/** Bounded descriptions of captured immutable history and independent clocks. */
public final class DebtReport {
    private DebtReport() {}
    public static List<String> cases(DebtDiagnostics.CasePage page) {
        var lines=new ArrayList<String>();
        lines.add(UiText.t("ui.finance.debtreport.1", date(page.asOf()), page.journalFrontier()));
        for(var entry:page.entries())lines.addAll(debt(entry));
        if(page.entries().isEmpty())lines.add(UiText.t("ui.finance.debtreport.2"));
        if(page.nextCursor()!=null)lines.add(UiText.t("ui.finance.debtreport.3", page.nextCursor()));
        return List.copyOf(lines);
    }
    private static List<String> debt(DebtDiagnostics.CaseView view) {
        var d=view.debt();
        return List.of(UiText.t("ui.finance.debtreport.4", d.id(), CreditReport.label(d.kind()), d.facility(), d.revision()),UiText.t("ui.finance.debtreport.5", d.creditor().key(), d.origin().key(), CreditReport.label(d.sale())),UiText.t("ui.finance.debtreport.6", CreditReport.money(d.currency(),d.principalMinor()), CreditReport.money(d.currency(),d.interestMinor()), CreditReport.label(view.state()), CreditReport.label(d.payment())),UiText.t("ui.finance.debtreport.7", date(d.defaultAt()), date(d.acknowledgedAt()), date(d.collectionExpires())),UiText.t("ui.finance.debtreport.8", view.collectionEligible(), CreditReport.label(view.collectionReason()), CreditReport.label(d.collectability())),UiText.t("ui.finance.debtreport.9", date(d.reportExpires()), view.reportVisible(), CreditReport.label(view.reportStatus())));
    }
    public static List<String> reports(DebtDiagnostics.ReportPage page,UUID inspectedOwner) {
        var lines=new ArrayList<String>();
        lines.add(UiText.t("ui.finance.debtreport.10", date(page.asOf()), page.journalFrontier()));
        for(var view:page.entries()) {
            var r=view.report();
            lines.add(UiText.t("ui.finance.debtreport.11", r.id(), r.revision(), CreditReport.label(r.kind()), r.facility(), r.obligation()));
            lines.add(UiText.t("ui.finance.debtreport.12", date(r.dueAt()), date(r.reportAt()), date(r.effectiveAt())));
            lines.add(UiText.t("ui.finance.debtreport.13", r.originalMinor(), r.unmetMinor(), r.metAtSnapshot(), r.cured()));
            lines.add(UiText.t("ui.finance.debtreport.14", view.visible(), CreditReport.label(view.reason()), date(view.expiresAt())));
        }
        if(page.entries().isEmpty())lines.add(UiText.t("ui.finance.debtreport.15"));
        var next=page.nextCursor();
        if(next!=null)lines.add(UiText.t("ui.finance.debtreport.16", (inspectedOwner==null?"reports":"inspect_reports "+inspectedOwner), String.valueOf(next.effectiveAt()), next.reportId(), String.valueOf(next.revision())));
        return List.copyOf(lines);
    }
    public static List<String> events(UUID id,DebtDiagnostics.EventPage page,UUID inspectedOwner) {
        var lines=new ArrayList<String>();lines.add(UiText.t("ui.finance.debtreport.17", id, page.journalFrontier()));
        for(var e:page.entries())lines.add(UiText.t("ui.finance.debtreport.18", e.sequence(), e.position(), e.version(), e.kind(), date(e.effectiveAt()), e.amountMinor(), e.allowanceMinor()));
        if(page.entries().isEmpty())lines.add(UiText.t("ui.finance.debtreport.19"));
        var next=page.nextCursor();if(next!=null)lines.add(UiText.t("ui.finance.debtreport.20", (inspectedOwner==null?"history":"inspect_history "+inspectedOwner), id, String.valueOf(next.sequence()), String.valueOf(next.position())));
        return List.copyOf(lines);
    }
    public static List<String> cardPayments(UUID id,ca.emilybelnavis.mcmods.economy.credit.CreditDiagnostics.PaymentPage page) {
        var lines=new ArrayList<String>();lines.add(UiText.t("ui.finance.debtreport.21", id, page.journalFrontier()));
        for(var receipt:page.entries()) {
            var p=receipt.witness();
            lines.add(UiText.t("ui.finance.debtreport.22", receipt.sequence(), receipt.position(), receipt.version(), date(p.effective()), CreditReport.money(p.currency(),p.amountMinor())));
            lines.add(UiText.t("ui.finance.debtreport.23", p.principalMinor(), p.interestMinor(), p.waivedResidualMajor(), p.policyVersion()));
        }
        if(page.entries().isEmpty())lines.add(UiText.t("ui.finance.debtreport.24"));
        var next=page.nextCursor();if(next!=null)lines.add(UiText.t("ui.finance.debtreport.25", id, String.valueOf(next.sequence()), String.valueOf(next.position())));
        return List.copyOf(lines);
    }
    public static List<String> loanPayments(UUID id,ca.emilybelnavis.mcmods.economy.loan.LoanDiagnostics.EventPage page) {
        var lines=new ArrayList<String>();lines.add(UiText.t("ui.finance.debtreport.26", id, page.journalFrontier()));
        for(var receipt:page.entries()) {
            var p=receipt.payment();
            lines.add(UiText.t("ui.finance.debtreport.27", receipt.sequence(), receipt.position(), receipt.version(), date(receipt.effectiveAt()), p.amountMinor()));
            lines.add(UiText.t("ui.finance.debtreport.28", p.principalMinor(), p.interestMinor(), p.journalMatches(), p.residualDispositionMajor(), p.additionalInterestWaivedMajor()));
        }
        if(page.entries().isEmpty())lines.add(UiText.t("ui.finance.debtreport.29"));
        var next=page.nextCursor();if(next!=null)lines.add(UiText.t("ui.finance.debtreport.30", id, String.valueOf(next.sequence()), String.valueOf(next.position())));
        return List.copyOf(lines);
    }
    public static List<String> operator(DebtDiagnostics.OperatorView view) {
        var lines=new ArrayList<String>();
        lines.add(UiText.t("ui.finance.debtreport.31", CreditReport.label(view.kind()), view.facility(), CreditReport.label(view.facilityStatus()), view.facilityRevision(), view.journalFrontier(), date(view.asOf())));
        lines.add(UiText.t("ui.finance.debtreport.32", CreditReport.money(view.currency(),view.facilityDebtMinor()), date(view.oldestUnmetDueAt()), date(view.defaultDueAt())));
        view.debt().ifPresent(d->lines.addAll(debt(d)));
        for(var o:view.obligations()) {
            var r=o.obligation();
            lines.add(UiText.t("ui.finance.debtreport.33", r.id(), r.revision(), date(r.dueAt()), r.originalMinor(), r.unmetMinor()));
            lines.add(UiText.t("ui.finance.debtreport.34", o.arrearsAgeMillis(), CreditReport.label(o.reason()), date(o.reportDueAt()), date(o.reportExpiresAt())));
            lines.add(UiText.t("ui.finance.debtreport.35", r.reported(), r.metAtReport(), date(r.effectiveAt())));
        }
        if(String.valueOf(view.nextObligationCursor())!=null)lines.add(UiText.t("ui.finance.debtreport.36", view.kind().name().toLowerCase(Locale.ROOT), view.facility(), String.valueOf(view.nextObligationCursor())));
        lines.add(UiText.t("ui.finance.debtreport.37", view.saleReason(), date(view.nextSaleAttemptAt())));
        var r=view.reconciliation();
        lines.add(UiText.t("ui.finance.debtreport.38", r.expectedReceivableMinor(), r.postedReceivableMinor(), r.receivableMatches()));
        lines.add(UiText.t("ui.finance.debtreport.39", r.expectedLiabilityMinor(), r.postedLiabilityMinor(), r.liabilityMatches(), r.facilityMatches()));
        for(var provider:view.providers())lines.add(UiText.t("ui.finance.debtreport.40", provider.provider(), provider.implementations(), date(provider.completedAt()), date(provider.applyingAt()), provider.applyingCursor(), provider.completedEffects()));
        lines.add(UiText.t("ui.finance.debtreport.41", view.debtPolicyVersion(), view.scorePolicyVersion(), date(view.nextScoreBoundary()), view.scoreStatus()));
        return List.copyOf(lines);
    }
    private static String date(Long time) {return time==null?UiText.t("ui.finance.none"):Instant.ofEpochMilli(time).toString();}
}
