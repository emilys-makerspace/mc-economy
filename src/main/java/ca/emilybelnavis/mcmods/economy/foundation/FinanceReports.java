package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle;
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
                lines.add(UiText.t("ui.finance.financereports.1"));
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
                lines.add(
                    UiText.t("ui.finance.financereports.4", CreditReport.label(snapshot.reason())));
                lines.add(
                    UiText.t(
                        "ui.finance.financereports.5",
                        snapshot.valuedAt(),
                        snapshot.policyVersion()));
                lines.add(UiText.t("ui.finance.financereports.6"));
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
                var lines = new ArrayList<>(DebtReport.cases(page));
                lines.add(UiText.t("ui.finance.financereports.9"));
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
                    DebtReport.reports(page, null),
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

  private static void unavailable(FinanceMenuSupport.Audience audience) {
    audience.reply(
        "finance_report",
        List.of(UiText.t("ui.finance.financereports.13")),
        UiText.t("ui.finance.financereports.12"),
        List.of());
  }
}
