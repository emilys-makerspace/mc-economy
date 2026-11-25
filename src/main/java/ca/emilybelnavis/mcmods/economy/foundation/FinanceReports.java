package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.debt.DebtDiagnostics;
import ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;

/** Owner-only bounded reports with server-retained exclusive cursors. */
public final class FinanceReports {
  private static final Map<BankingMenu, State> STATES = new WeakHashMap<>();

  private static final class State {
    UUID cases;
    DebtLifecycle.ReportCursor reports;
  }

  private FinanceReports() {}

  public static boolean handles(String action) {
    return Set.of(
            "finance_wealth",
            "finance_cases",
            "finance_cases_next",
            "finance_report",
            "finance_report_next")
        .contains(action);
  }

  public static void closed(BankingMenu menu) {
    STATES.remove(menu);
  }

  public static void action(ServerPlayer player, BankingMenu menu, String action, String value) {
    var audience = FinanceMenuSupport.audience(player, menu);
    if (!audience.live() || menu.busy()) return;
    if (action.endsWith("_next") && !FinanceMenuSupport.offered(audience, action, value)) return;
    var services = Economy.serverServices(player.server);
    if (services == null || services.state() != ServerServices.State.READY) return;
    var grant = FinanceMenuSupport.grant(player, services);
    if (grant == null) return;
    var state = STATES.computeIfAbsent(menu, ignored -> new State());
    menu.setBusy(true);
    if (action.equals("finance_wealth")) {
      services
          .ledger()
          .valuation(grant)
          .whenComplete(
              (found, error) -> {
                if (!audience.live()) return;
                if (error != null || found.isEmpty()) {
                  unavailable(audience);
                  return;
                }
                var snapshot = found.orElseThrow();
                var lines = new ArrayList<String>();
                if (snapshot.netWorth() != null)
                  lines.add(
                      UiText.t(
                          "ui.finance.financereports.2",
                          UiText.decimal(snapshot.netWorth()),
                          snapshot.denomination()));
                lines.add(
                    UiText.t(
                        "ui.finance.financereports.3",
                        (snapshot.available()
                            ? snapshot.eligible()
                                ? UiText.t("ui.finance.tier2")
                                : UiText.t("ui.finance.tier1")
                            : UiText.t("ui.finance.unavailable"))));
                if (!snapshot.available())
                  lines.add(
                      UiText.t(
                          "ui.finance.financereports.4", CreditReport.label(snapshot.reason())));
                else if (!snapshot.eligible()) lines.add(UiText.t("ui.finance.financereports.1"));
                audience.reply(
                    "finance_wealth", lines, UiText.t("ui.finance.financereports.7"), List.of());
              });
    } else if (action.startsWith("finance_cases")) {
      if (action.equals("finance_cases")) state.cases = null;
      services
          .ledger()
          .debtCases(grant, state.cases, 3)
          .whenComplete(
              (found, error) -> {
                if (!audience.live()) return;
                if (error != null || found.isEmpty()) {
                  unavailable(audience);
                  return;
                }
                var page = found.orElseThrow();
                state.cases = page.nextCursor();
                var choices =
                    state.cases == null
                        ? List.<BankingNetwork.Choice>of()
                        : List.of(
                            new BankingNetwork.Choice(
                                "finance_cases_next",
                                "",
                                UiText.t("ui.finance.financereports.8"),
                                false));
                var lines = cases(page);
                audience.reply(
                    "finance_cases", lines, UiText.t("ui.finance.financecollections.2"), choices);
              });
    } else {
      if (action.equals("finance_report")) state.reports = null;
      services
          .ledger()
          .debtReportPage(grant, state.reports, 3)
          .whenComplete(
              (found, error) -> {
                if (!audience.live()) return;
                if (error != null || found.isEmpty()) {
                  unavailable(audience);
                  return;
                }
                var page = found.orElseThrow();
                state.reports = page.nextCursor();
                audience.reply(
                    "finance_report",
                    reports(page),
                    UiText.t("ui.finance.financereports.10"),
                    state.reports == null
                        ? List.of()
                        : List.of(
                            new BankingNetwork.Choice(
                                "finance_report_next",
                                "",
                                UiText.t("ui.finance.financereports.11"),
                                false)));
              });
    }
  }

  static List<String> card(CreditDiagnostics.FacilityView card) {
    var lines = new ArrayList<String>();
    var currency = card.currency();
    lines.add(
        InstitutionCatalog.require(new InstitutionCatalog.InstitutionId(card.issuer())).name()
            + " · "
            + UiText.t("network.economy." + card.network().toLowerCase(Locale.ROOT)));
    if (!card.status().equals("ACTIVE") || !card.access().equals("ENABLED"))
      lines.add(
          UiText.t(
              "ui.finance.creditreport.1",
              CreditReport.label(card.status()),
              CreditReport.label(card.access())));
    lines.add(
        UiText.t(
            "ui.finance.player.balance", CreditReport.money(currency, card.currentBalanceMinor())));
    lines.add(
        UiText.t(
            "ui.finance.player.credit_left",
            CreditReport.money(currency, card.availableCreditMinor()),
            CreditReport.money(currency, card.limitMinor())));
    if (card.requiredMinor() > 0 && card.dueAt() != null)
      lines.add(
          UiText.t(
              "ui.finance.player.minimum_due",
              CreditReport.money(currency, card.requiredMinor()),
              FinanceMenuSupport.date(card.dueAt())));
    if (card.pastDueMinor() > 0)
      lines.add(
          UiText.t(
              "ui.finance.player.past_due", CreditReport.money(currency, card.pastDueMinor())));
    if (!card.scheduleCurrent()) lines.add(UiText.t("ui.finance.player.updating"));
    return lines;
  }

  static List<String> statement(
      CreditFacilities.StatementSnapshot snapshot, EconomyCurrency currency) {
    var statement = snapshot.statement();
    return List.of(
        UiText.t(
            "ui.finance.player.statement",
            FinanceMenuSupport.date(statement.closedAt()),
            CreditReport.money(currency, statement.debtMinor())),
        UiText.t(
            "ui.finance.player.minimum_due",
            CreditReport.money(currency, statement.totalRequiredMinor()),
            FinanceMenuSupport.date(statement.dueAt())));
  }

  private static List<String> cases(DebtDiagnostics.CasePage page) {
    if (page.entries().isEmpty()) return List.of(UiText.t("ui.finance.player.no_cases"));
    var lines = new ArrayList<String>();
    for (var view : page.entries()) {
      var debt = view.debt();
      lines.add(
          UiText.t(
              "ui.finance.player.case_balance",
              InstitutionCatalog.require(debt.creditor(), debt.currency()).name(),
              CreditReport.money(debt.currency(), debt.debtMinor()),
              CreditReport.label(view.state())));
    }
    if (page.entries().stream().anyMatch(DebtDiagnostics.CaseView::collectionEligible))
      lines.add(UiText.t("ui.finance.player.pay_collector"));
    return lines;
  }

  private static List<String> reports(DebtDiagnostics.ReportPage page) {
    var lines = new ArrayList<String>();
    for (var view : page.entries()) {
      if (!view.visible()) continue;
      var report = view.report();
      String status =
          report.metAtSnapshot() ? "paid_on_time" : report.cured() ? "paid_late" : "missed";
      lines.add(
          UiText.t(
              "ui.finance.player.payment_record",
              FinanceMenuSupport.date(report.dueAt()),
              CreditReport.label(report.kind()),
              UiText.t("ui.finance.player." + status)));
    }
    if (lines.isEmpty()) lines.add(UiText.t("ui.finance.player.no_reports"));
    return lines;
  }

  private static void unavailable(FinanceMenuSupport.Audience audience) {
    audience.reply(
        "finance_report",
        List.of(UiText.t("ui.finance.financereports.13")),
        UiText.t("ui.finance.financereports.12"),
        List.of());
  }
}
