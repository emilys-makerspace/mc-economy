package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.card.*;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.loan.*;
import java.time.*;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;

/** Small, bank-scoped terminal navigation. Financial submissions remain in their existing services. */
public final class TerminalMenus {
    private TerminalMenus() {}
    public static final Set<String> ACTIONS = Set.of("terminal_account", "terminal_history_prev", "terminal_history_next",
            "terminal_settings", "terminal_credit", "terminal_loans", "terminal_cards", "terminal_bill", "terminal_withdraw_bank", "terminal_withdraw_credit", "terminal_accounts_page", "terminal_primary");
    private static final Map<BankingMenu, State> STATES = new WeakHashMap<>();
    private static final class State { String selected = ""; int page; int accountPage; boolean bills; Set<String> offered = Set.of(); }
    record View(String page, List<String> lines, List<BankingNetwork.Choice> choices, Set<String> offered) {}
    private static BankingNetwork.Choice choice(String action, String value, String label) { return new BankingNetwork.Choice(action, value, label, false); }
    public static boolean handles(String action) { return ACTIONS.contains(action) || Set.of("accounts", "payments", "manage_accounts", "withdraw").contains(action); }
    public static void closed(BankingMenu menu) { STATES.remove(menu); }
    public static void action(ServerPlayer player, BankingMenu menu, String action, String value) {
        var services = BankingController.services(player);
        var grant = FinanceMenuSupport.grant(player, services);
        if (grant == null || menu.busy()) return;
        var audience = FinanceMenuSupport.audience(player, menu);
        var state = STATES.computeIfAbsent(menu, ignored -> new State());
        if (ACTIONS.contains(action) && !FinanceMenuSupport.offered(audience, action, value)) return;
        if (action.equals("terminal_withdraw_bank") || action.equals("terminal_withdraw_credit")) {
            boolean credit = action.equals("terminal_withdraw_credit");
            menu.setBusy(true);
            services.readAccounts(grant, false, reads -> {
                var id = UUID.fromString(value);
                if (!credit) return AccountCatalog.find(reads, grant.target(), new AccountId(id)).filter(a -> a.status() == AccountIdentity.Status.OPEN).map(AccountIdentity::id);
                var facility = CreditFacilities.find(reads, id).filter(f -> f.party().equals(grant.target())).orElseThrow();
                return AccountCatalog.openBanks(reads, grant.target()).stream().filter(a -> a.currency() == facility.currency()).map(AccountIdentity::id).findFirst();
            }).whenComplete((account, error) -> {
                if (!audience.live()) return;
                menu.setBusy(false);
                if (error != null || account.isEmpty()) { audience.reply("withdraw_sources", List.of("A bank account in the card's currency is required for this cash advance."), "", List.of()); return; }
                BankingAccounts.selectTerminalAccount(menu, account.orElseThrow());
                if (credit) { CreditUse.selectFacility(menu, UUID.fromString(value)); CreditUse.action(player, menu, "credit_advance", ""); }
                else BankingWithdrawals.action(player, menu, "withdraw", "");
            });
            return;
        }
        if (action.equals("terminal_bill")) {
            String[] parts = value.split("/", 2);
            // Re-offer only the selected debt to the existing reviewed repayment flow.
            String target = parts[0].equals("credit") ? "finance_credit_pay" : "finance_loan_view";
            menu.publish("payments", List.of(), "", List.of(choice(target, parts[1], "Continue")));
            if (parts[0].equals("credit")) CreditMenus.terminalPayment(player, menu, UUID.fromString(parts[1]));
            else LoanMenus.action(player, menu, target, parts[1]);
            return;
        }
        if (action.equals("accounts") || action.equals("payments")) { state.accountPage = 0; state.bills = action.equals("payments"); }
        if (action.equals("terminal_accounts_page")) state.accountPage = Integer.parseInt(value);
        if (action.equals("terminal_account")) { if (!state.offered.contains(value)) return; state.selected = value; state.page = 0; }
        if (action.equals("terminal_history_prev")) state.page = Math.max(0, state.page - 1);
        if (action.equals("terminal_history_next")) state.page++;
        var cards = java.util.stream.Stream.of(player.getMainHandItem(), player.getOffhandItem())
                .map(stack -> CardStackIdentity.mark(stack, services.worldId())).flatMap(Optional::stream).map(CardStackIdentity.Mark::token).toList();
        menu.setBusy(true);
        services.readAccounts(grant, false, reads -> {
            String bank = bank(reads, grant, menu, services.financialMillis());
            if (menu.remote() && action.equals("accounts") && menu.issuedToken() != null) {
                var credit = CreditFacilities.usableToken(reads, grant.target().world(), ((PartyRef.Player) grant.target().owner()).id(), menu.issuedToken(), services.financialMillis());
                if (credit.isPresent()) {
                    var token = credit.orElseThrow(); var key = "credit/" + token.facility();
                    return Optional.of(new View("terminal_accounts", List.of(), List.of(choice("terminal_account", key, "Credit Account")), Set.of(key)));
                }
            }
            if (action.equals("withdraw")) return Optional.of(withdrawal(reads, grant.target(), bank, cards, services.financialMillis(), menu.access()));
            if (action.equals("accounts") || action.equals("payments") || action.equals("terminal_accounts_page")) return Optional.of(accounts(reads, grant.target(), bank, state.bills, state.accountPage));
            if (action.equals("manage_accounts") || action.startsWith("terminal_") && Set.of("terminal_settings", "terminal_credit", "terminal_loans", "terminal_cards", "terminal_primary").contains(action))
                return Optional.of(branch(reads, grant.target(), bank, action));
            return Optional.of(detail(reads, grant.target(), bank, state.selected, state.page, services.financialMillis()));
        }).whenComplete((found, error) -> {
            if (!audience.live()) return;
            if (error != null || found.isEmpty()) { audience.reply("accounts", List.of("This service is unavailable. Please try again."), "", List.of()); return; }
            var view = found.orElseThrow();
            if (!view.offered().isEmpty() || action.equals("accounts")) state.offered = view.offered();
            audience.reply(view.page(), view.lines(), "", view.choices());
        });
    }
    /** Presentation restriction uses the held design; token ownership/status is rechecked by the controller. */
    public static boolean withdrawalOnly(ServerPlayer player, String bank) {
        var card = java.util.stream.Stream.of(player.getMainHandItem(), player.getOffhandItem())
                .map(CardCatalog::find).flatMap(Optional::stream).findFirst().orElse(null);
        return card == null || !card.issuer().key().equals(bank) || CreditCardDesigns.byId(card.id()).isPresent();
    }
    static String bank(JournalEngine.ReadView reads, PartyAuthorization.Grant grant, BankingMenu menu, long now) {
        if (menu.remote() && menu.issuedToken() != null) {
            var credit = CreditFacilities.usableToken(reads, grant.target().world(), ((PartyRef.Player) grant.target().owner()).id(), menu.issuedToken(), now);
            if (credit.isPresent()) return credit.orElseThrow().issuer().key();
            return IssuedCards.usable(reads, grant.target().world(), ((PartyRef.Player) grant.target().owner()).id(), menu.issuedToken(), now).map(t -> t.issuer().key()).orElseThrow();
        }
        var terminal = TerminalPolicy.find(reads, grant.target().world(), menu.anchor(), grant.actor(), true).orElse(null);
        return terminal == null || terminal.binding() == null || !terminal.binding().bound() ? "" : terminal.binding().institution().key();
    }
    static List<AccountIdentity> bankAccounts(JournalEngine.ReadView reads, PartyRef owner, String bank) {
        return AccountCatalog.openBanks(reads, owner).stream().filter(a -> bank.isEmpty() || BankProviders.find(reads, owner, a.id()).map(p -> p.bankKey().equals(bank)).orElse(false)).toList();
    }
    static View accounts(JournalEngine.ReadView reads, PartyRef owner, String bank, boolean bills, int page) {
        if (page < 0 || page > 100_000) throw new IllegalArgumentException("Account page");
        var choices = new ArrayList<BankingNetwork.Choice>(); var offered = new HashSet<String>();
        var parameters = new ArrayList<String>();
        String banks = "SELECT 'bank' AS kind,a.account_id AS id,a.currency FROM account_catalog a JOIN bank_account_providers p ON p.account_id=a.account_id WHERE a.owner_id=? AND a.purpose='BANK' AND a.status='OPEN' AND (?='' OR p.bank_key=?)";
        String debts = "SELECT 'credit' AS kind,facility_id AS id,currency FROM credit_facilities WHERE world_id=? AND owner_id=? AND (?='' OR issuer=?) UNION ALL SELECT 'loan' AS kind,loan_id AS id,currency FROM loans WHERE world_id=? AND owner_id=? AND (?='' OR lender=?)";
        if (!bills) parameters.addAll(List.of(player(owner), bank, bank));
        parameters.addAll(List.of(owner.world().value().toString(), player(owner), bank, bank, owner.world().value().toString(), player(owner), bank, bank, Integer.toString(page * 6)));
        var rows = reads.query("SELECT * FROM (" + (bills ? "" : banks + " UNION ALL ") + debts + ") ORDER BY kind,id LIMIT 7 OFFSET ?", parameters, 7);
        for (var row : rows.stream().limit(6).toList()) {
            String key = row.get("kind") + "/" + row.get("id"); offered.add(key);
            choices.add(choice(bills ? "terminal_bill" : "terminal_account", key, activity(row.get("kind")) + " Account · " + row.get("currency")));
        }
        if (page > 0) choices.add(choice("terminal_accounts_page", Integer.toString(page - 1), "← Previous Accounts"));
        if (rows.size() > 6) choices.add(choice("terminal_accounts_page", Integer.toString(page + 1), "More Accounts →"));
        return new View(bills ? "payments" : "terminal_accounts", choices.isEmpty() ? List.of(bills ? "You have no bills with this bank." : "You have no accounts with this bank.") : List.of(), choices, offered);
    }
    private static View withdrawal(JournalEngine.ReadView reads, PartyRef owner, String bank, List<UUID> cards, long now, BankingMenu.Access access) {
        var choices = new ArrayList<BankingNetwork.Choice>();
        var player = ((PartyRef.Player) owner.owner()).id();
        var debit = cards.stream().map(t -> IssuedCards.usable(reads, owner.world(), player, t, now)).flatMap(Optional::stream).findFirst().orElse(null);
        var credit = cards.stream().map(t -> CreditFacilities.usableToken(reads, owner.world(), player, t, now)).flatMap(Optional::stream).findFirst().orElse(null);
        var accounts = access == BankingMenu.Access.ATM ? AccountCatalog.openBanks(reads, owner).stream()
                .filter(a -> debit != null && debit.account().equals(a.id())).toList() : bankAccounts(reads, owner, bank);
        for (var account : accounts) choices.add(choice("terminal_withdraw_bank", account.id().value().toString(), "Bank Account · " + account.currency().code()));
        for (var facility : CreditFacilities.history(reads, owner, null, 16)) if (facility.status() == CreditFacilities.Status.ACTIVE
                && (access == BankingMenu.Access.ATM ? credit != null && credit.facility().equals(facility.id()) || debit != null && facility.issuer().key().equals(bank) : facility.issuer().key().equals(bank)))
            choices.add(choice("terminal_withdraw_credit", facility.id().toString(), "Credit Account · " + facility.currency().code()));
        return new View("withdraw_sources", choices.isEmpty() ? List.of("No account is available for withdrawal.") : List.of("Choose an account to withdraw from."), choices, Set.of());
    }
    private static View branch(JournalEngine.ReadView reads, PartyRef owner, String bank, String action) {
        var rows = new ArrayList<BankingNetwork.Choice>();
        var accounts = bankAccounts(reads, owner, bank);
        String page = "branch_services";
        switch (action) {
            case "terminal_settings" -> {
                page = "account_settings";
                var primary = AccountCatalog.primary(reads, owner).orElse(null);
                if (primary != null) {
                    var provider = BankProviders.find(reads, owner, primary.id()).orElse(null);
                    if (provider != null && provider.bankKey().equals(bank)) rows.add(choice("branch_switch", primary.id().value().toString(), "Change Primary Bank"));
                    else rows.add(choice("terminal_primary", "", "Assign This Bank as Primary"));
                }
                for (var a : accounts) {
                    if (!a.primary()) rows.add(choice("branch_close", a.id().value().toString(), "Close Account"));
                }
            }
            case "terminal_primary" -> {
                page = "primary_bank";
                var primary = AccountCatalog.primary(reads, owner).orElseThrow();
                var target = InstitutionCatalog.require(new InstitutionCatalog.InstitutionId(bank));
                if (target.baseCurrency() != primary.currency() || !target.tier1())
                    return new View(page, List.of("Your primary bank must offer a standard account in " + primary.currency().code() + "."), rows, Set.of());
                var provider = BankProviders.find(reads, owner, primary.id()).orElseThrow();
                String id = primary.id().value().toString();
                if (provider.bankKey().equals(bank)) {
                    rows.add(choice("branch_switch", id, "Change Primary Bank"));
                    return new View(page, List.of("This is already your primary bank."), rows, Set.of());
                }
                if (provider.bankKey().equals(BankProviders.UNASSIGNED_PROVIDER)) {
                    rows.add(choice("branch_assign", id, "Confirm Primary Bank"));
                    return new View(page, List.of("Assign your main account to " + target.name() + "."), rows, Set.of());
                }
                var pending = reads.query("SELECT target_key FROM bank_provider_switches WHERE account_id=?", List.of(id), 1);
                if (!pending.isEmpty() && pending.getFirst().get("target_key").equals(bank)) {
                    rows.add(choice("branch_accept", id, "Confirm Primary Bank"));
                    return new View(page, List.of("Complete your primary bank change to " + target.name() + ".", "Your old debit card will be cancelled. Issue a new card here afterwards."), rows, Set.of());
                }
                String source = InstitutionCatalog.require(new InstitutionCatalog.InstitutionId(provider.bankKey())).name();
                return new View(page, List.of("First visit " + source + " and choose Account Settings, then Change Primary Bank.",
                        "Select " + target.name() + ", then return here to confirm."), rows, Set.of());
            }
            case "terminal_credit" -> {
                page = "credit_services";
                rows.add(choice("finance_credit_apply", "", "Apply for Credit"));
                rows.add(choice("credit_profile", "", "Credit Profile")); rows.add(choice("finance_report", "", "Credit Report"));
                if (CreditFacilities.history(reads, owner, null, 16).stream().anyMatch(f -> f.issuer().key().equals(bank) && f.status() == CreditFacilities.Status.ACTIVE && f.access() == CreditFacilities.Access.ENABLED && f.requirements().stream().noneMatch(r -> r.unmetMinor() > 0)))
                    rows.add(choice("finance_credit_raise", "", "Request Credit Limit Increase"));
            }
            case "terminal_loans" -> {
                page = "loan_services"; rows.add(choice("finance_loan_apply", "", "Apply for a Loan"));
                rows.add(choice("credit_profile", "", "Credit Profile")); rows.add(choice("finance_report", "", "Credit Report"));
            }
            case "terminal_cards" -> {
                page = "card_services"; rows.add(choice("cards", "", "Bank Cards")); rows.add(choice("finance_credit_cards", "", "Credit Cards"));
                rows.add(choice("claims", "", "Collect Cards and Cash"));
            }
            default -> {
                if (accounts.isEmpty()) rows.add(choice("branch_open", "", "Open a Bank Account"));
                if (!accounts.isEmpty() || AccountCatalog.primary(reads, owner).isPresent()) rows.add(choice("terminal_settings", "", "Account Settings"));
                rows.add(choice("terminal_credit", "", "Credit Services")); rows.add(choice("terminal_loans", "", "Loan Services"));
                rows.add(choice("terminal_cards", "", "Card Services")); rows.add(choice("fx", "", "Currency Exchange"));
            }
        }
        return new View(page, List.of(), rows, Set.of());
    }
    static View detail(JournalEngine.ReadView reads, PartyRef owner, String bank, String selected, int page, long now) {
        var parts = selected.split("/", 2); UUID id = UUID.fromString(parts[1]);
        var lines = new ArrayList<String>(); EconomyCurrency currency;
        if (parts[0].equals("bank")) {
            var account = bankAccounts(reads, owner, bank).stream().filter(a -> a.id().value().equals(id)).findFirst().orElseThrow();
            var funds = LedgerService.bankFunds(reads, account).orElseThrow(); currency = funds.currency();
            lines.add(money(currency, funds.postedMinorUnits())); lines.add(money(currency, funds.availableMinorUnits()));
            lines.add("Holds: " + money(currency, funds.reservedMinorUnits() + funds.restrictedMinorUnits()));
            var rows = reads.query("SELECT sequence,position,minor,currency,service FROM owner_history WHERE world_id=? AND owner_id=? AND account_id=? ORDER BY sequence DESC,position DESC LIMIT 5 OFFSET ?",
                    List.of(owner.world().value().toString(), player(owner), id.toString(), Integer.toString(page * 5)), 5);
            for (var row : rows) { long amount = Long.parseLong(row.get("minor")); lines.add((amount < 0 ? "DEBIT" : "CREDIT") + " - " + activity(row.get("service")) + "\t" + money(EconomyCurrency.fromCode(row.get("currency")), Math.abs(amount))); }
            long count = Long.parseLong(reads.query("SELECT COUNT(*) AS n FROM owner_history WHERE world_id=? AND owner_id=? AND account_id=?", List.of(owner.world().value().toString(), player(owner), id.toString()), 1).getFirst().get("n"));
            return history(lines, page, count);
        }
        String fact;
        if (parts[0].equals("credit")) {
            var f = CreditFacilities.find(reads, id).filter(v -> v.party().equals(owner) && (bank.isEmpty() || v.issuer().key().equals(bank))).orElseThrow();
            var view = CreditDiagnostics.facility(reads, owner, id, now).orElseThrow(); currency = f.currency();
            lines.add(money(currency, view.currentBalanceMinor())); lines.add("Available Credit: " + money(currency, view.availableCreditMinor())); lines.add("Pending: " + money(currency, 0)); fact = CreditFacilities.EVENT_FACT;
        } else {
            var loan = Loans.find(reads, id).filter(v -> v.party().equals(owner) && (bank.isEmpty() || v.lender().key().equals(bank))).orElseThrow(); currency = loan.currency();
            lines.add(money(currency, loan.owedMinor())); lines.add(""); lines.add(""); fact = Loans.EVENT_FACT;
        }
        String encodedId = Base64.getUrlEncoder().withoutPadding().encodeToString(id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String kinds = parts[0].equals("loan") ? "'UEFZTUVOVA'" : "'UkVQQVlNRU5U','RFJBV19QVVJDSEFTRQ','RFJBV19BRFZBTkNF','REFJTFlfSU5URVJFU1Q','QURWQU5DRV9GRUVT'";
        String financial = " AND substr(payload,1,instr(payload,'|')-1) IN (" + kinds + ")";
        var params = List.of(fact, encodedId, Integer.toString(page * 5));
        // Facts encode four fields as URL-safe Base64, separated by '|'. Only financial events become history rows.
        var rows = reads.query("SELECT payload FROM journal_records WHERE record_type=? AND record_version=1 AND substr(payload,instr(payload,'|')+1,48)=? " + financial + " ORDER BY sequence DESC,position DESC LIMIT 5 OFFSET ?", params, 5);
        for (var row : rows) {
            var values = Arrays.stream(row.get("payload").split("\\|", -1)).map(s -> new String(Base64.getUrlDecoder().decode(s), java.nio.charset.StandardCharsets.UTF_8)).toList();
            String kind = values.get(0), details = values.get(3); long amount = 0;
            var detail = details.split("\\|", -1);
            if (kind.equals("DAILY_INTEREST")) amount = Math.addExact(Long.parseLong(detail[2]), Long.parseLong(detail[3]));
            else if (kind.equals("ADVANCE_FEES")) amount = Math.addExact(Long.parseLong(detail[1]), Long.parseLong(detail[2]));
            else amount = Long.parseLong(detail[0]);
            lines.add((parts[0].equals("loan") ? "Payment - " + date(Long.parseLong(values.get(2))) : (kind.equals("REPAYMENT") ? "CREDIT" : "DEBIT") + " - " + activity(kind)) + "\t" + money(currency, amount));
        }
        long count = Long.parseLong(reads.query("SELECT COUNT(*) AS n FROM journal_records WHERE record_type=? AND record_version=1 AND substr(payload,instr(payload,'|')+1,48)=?" + financial, List.of(fact, encodedId), 1).getFirst().get("n"));
        return history(lines, page, count);
    }
    private static View history(List<String> lines, int page, long count) {
        int total = Math.max(1, (int) ((count + 4) / 5)); var choices = new ArrayList<BankingNetwork.Choice>();
        if (page > 0) choices.add(choice("terminal_history_prev", "", "←"));
        if (page + 1 < total) choices.add(choice("terminal_history_next", "", "→"));
        lines.add("Page " + (page + 1) + " of " + total);
        return new View("account_overview", lines, choices, Set.of());
    }
    private static String player(PartyRef owner) { return ((PartyRef.Player) owner.owner()).id().value().toString(); }
    private static String money(EconomyCurrency currency, long amount) { return UiText.money(new Money(currency, amount)); }
    private static String date(long at) { return Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate().toString(); }
    private static String activity(String value) { String text = value.substring(value.lastIndexOf(':') + 1).replace('_', ' ').replace('/', ' '); return text.isEmpty() ? "Transaction" : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1).toLowerCase(Locale.ROOT); }
}
