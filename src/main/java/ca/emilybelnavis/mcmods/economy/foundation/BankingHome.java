package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.banking.BankingDashboardView.*;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import net.minecraft.server.level.ServerPlayer;

/** Live audience and navigation around the detached, single-transaction dashboard. */
public final class BankingHome {
    private BankingHome() {}
    public static void open(ServerPlayer player, BankingMenu menu) {
        var audience = FinanceMenuSupport.audience(player, menu);
        if (!audience.live() || menu.busy()) return;
        var services = BankingController.services(player);
        var grant = FinanceMenuSupport.grant(player, services);
        if (grant == null) return;
        menu.setBusy(true);
        services.ledger().dashboard(grant, BankingAccounts.selected(menu), menu.remote() ? null : menu.anchor())
                .whenComplete((found, error) -> {
                    if (!audience.live()) return;
                    menu.setBusy(false);
                    var choices = destinations(menu);
                    if (error != null || found.isEmpty()) {
                        menu.publish("home", List.of(UiText.t("ui.bank.banking_summary_unavailable_refresh_when_servi.892e75")),
                                UiText.t("ui.bank.summary_unavailable.0bee06"), choices, BankingOperation.View.ready(), null);
                        return;
                    }
                    var view = found.orElseThrow();
                    menu.publish("home", lines(view), UiText.t("ui.bank.banking_overview.bd8549"), choices, BankingOperation.View.ready(), view);
                });
    }
    public static List<BankingNetwork.Choice> destinations(BankingMenu menu) {
        var choices = new ArrayList<BankingNetwork.Choice>();
        offer(menu, choices, "accounts", UiText.t("ui.bank.accounts.36bae3"));
        offer(menu, choices, "payments", UiText.t("ui.bank.payments.44357a"));
        offer(menu, choices, "fx", "FX");
        offer(menu, choices, "finance_home", UiText.t("ui.bank.credit.8a45d3"));
        offer(menu, choices, "finance_loan_home", UiText.t("ui.bank.loans.d86fda"));
        offer(menu, choices, "history", UiText.t("ui.bank.history.90ccd6"));
        offer(menu, choices, "home", UiText.t("ui.bank.refresh.56e3ba"));
        return List.copyOf(choices);
    }
    public static void payments(BankingMenu menu) {
        var choices = new ArrayList<BankingNetwork.Choice>();
        offer(menu, choices, "deposit", UiText.t("ui.bank.deposit_cash.bc5a1f"));
        offer(menu, choices, "withdraw", UiText.t("ui.bank.withdraw_cash.4a4872"));
        offer(menu, choices, "finance_credit_pay", UiText.t("ui.bank.repay_credit.d8e9c6"));
        offer(menu, choices, "finance_loan_home", UiText.t("ui.bank.loans_and_repayments.192237"));
        offer(menu, choices, "finance_debt_home", UiText.t("ui.bank.collection_payments.ef18b2"));
        offer(menu, choices, "auto_home", UiText.t("ui.bank.automatic_payments.0d1f62"));
        offer(menu, choices, "claims", UiText.t("ui.bank.collect_retained_items.b6b886"));
        offer(menu, choices, "cards", UiText.t("ui.bank.cards.0f830b"));
        menu.setBusy(false);
        menu.publish("payments", List.of(UiText.t("ui.bank.choose_an_available_payment_service.4a3ddf"),
                UiText.t("ui.bank.each_payment_requires_its_own_current_source_a.3f4b78")), UiText.t("ui.bank.payment_services.6e8fec"), choices);
    }
    private static void offer(BankingMenu menu, List<BankingNetwork.Choice> rows, String action, String label) {
        if (menu.allows(action)) rows.add(new BankingNetwork.Choice(action, "", label, false));
    }
    private static List<String> lines(BankingDashboardView view) {
        var lines = new ArrayList<String>();
        String bank = InstitutionCatalog.banks().stream().filter(b -> b.id().key().equals(view.institution()))
                .map(bankEntry -> bankEntry.name()).findFirst().orElse(view.institution().isEmpty() ? UiText.t("ui.bank.owner_view.df5674") : view.institution());
        lines.add(bank + (view.currency().isEmpty() ? "" : " · " + view.currency()));
        lines.add(UiText.t("ui.bank.captured_utc.3ea136") + Instant.ofEpochMilli(view.capturedAt()));
        section(lines, UiText.t("ui.bank.accounts.36bae3"), view.accounts(), r -> UiText.t("ui.bank.account_summary", r.primary() ? UiText.t("ui.bank.primary.547618") : UiText.t("ui.bank.account.131e33"), money(r.available()), money(r.reserved())));
        section(lines, UiText.t("ui.bank.credit_up_to_3_facilities.cb6fb9"), view.credit(), r -> money(r.balance()) + UiText.t("ui.bank.posted_debt.220dd9") + (r.current() ? "" : UiText.t("ui.bank.servicing_pending")));
        section(lines, UiText.t("ui.bank.loans_up_to_3_contracts.dcd083"), view.loans(), r -> money(r.balance()) + UiText.t("ui.bank.posted_debt.220dd9") + (r.current() ? "" : UiText.t("ui.bank.servicing_pending")));
        section(lines, UiText.t("ui.bank.upcoming_sampled_facilities.b27da8"), view.upcoming(), r -> money(r.amount()) + UiText.t("ui.bank.due_utc.a0dcf3") + Instant.ofEpochMilli(r.dueAt()));
        section(lines, UiText.t("ui.bank.recent_selected_account.b93d1e"), view.recent(), r -> money(r.amount()) + UiText.t("ui.bank.utc.9f24a3") + Instant.ofEpochMilli(r.recordedAt()));
        return List.copyOf(lines);
    }
    private static <T> void section(List<String> lines, String heading, Section<T> section, Function<T, String> row) {
        lines.add(heading + (section.hasMore() ? UiText.t("ui.bank.more_in_service_view.e6775f") : ""));
        if (section.availability() == Availability.UNAVAILABLE) lines.add(UiText.t("ui.bank.summary_unavailable_help"));
        else if (section.rows().isEmpty()) lines.add(UiText.t("ui.bank.none_in_this_view.619570"));
        else section.rows().forEach(value -> lines.add(row.apply(value)));
    }
    private static String money(Money amount) { return UiText.money(amount); }
}
