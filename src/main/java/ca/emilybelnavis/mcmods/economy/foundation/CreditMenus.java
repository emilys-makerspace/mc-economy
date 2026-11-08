package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.BankingMenu;
import ca.emilybelnavis.mcmods.economy.banking.BankingNetwork;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Owner credit workflows. All monetary quotes and branch capabilities are server generated. */
public final class CreditMenus {
    private static final Set<String> ACTIONS = Set.of("finance_credit_apply", "finance_credit_network",
            "finance_credit_preview", "finance_credit_confirm", "finance_credit_pay", "finance_credit_fund",
            "finance_credit_amount", "finance_credit_pay_ok", "finance_credit_raise", "finance_credit_limit",
            "finance_credit_raise_ok", "finance_credit_more", "finance_credit_cards", "finance_credit_token",
            "finance_credit_lost", "finance_credit_lost_ok", "finance_credit_replace", "finance_credit_fee", "finance_credit_repl_ok");
    private static final Map<BankingMenu, State> STATES = new WeakHashMap<>();

    private static final class State {
        LedgerService.BranchContext branch;
        LedgerService.LoanContext context;
        InstitutionCatalog.Network network;
        EconomyCurrency currency;
        long limit;
        long previewRevision;
        CreditFacilities.Facility facility;
        AccountIdentity funding;
        CreditFacilities.RepaymentRequest repayment;
        CreditRepaymentFx.Quote fx;
        UUID after;
        boolean increasing;
        boolean cardMode;
        CreditFacilities.Token token;
        ca.emilybelnavis.mcmods.economy.card.IssuedCards.FeeQuote replacementFee;
        Map<UUID, CreditFacilities.Facility> facilities = Map.of();
        Map<AccountId, AccountIdentity> accounts = Map.of();
    }

    private CreditMenus() {}
    public static Set<String> actions() { return ACTIONS; }
    public static boolean handles(String action) { return ACTIONS.contains(action); }
    public static void closed(BankingMenu menu) { STATES.remove(menu); }

    public static void action(net.minecraft.server.level.ServerPlayer player, BankingMenu menu, String action, String value) {
        if (!handles(action) || !menu.valid(player) || menu.busy()) return;
        var services = Economy.serverServices(player.server);
        if (services == null || services.state() != ServerServices.State.READY) return;
        var grant = FinanceMenuSupport.grant(player, services);
        if (grant == null) return;
        var audience = FinanceMenuSupport.audience(player, menu);
        var state = STATES.computeIfAbsent(menu, ignored -> new State());
        try {
            switch (action) {
                case "finance_credit_cards" -> {
                    state.after = null;
                    state.cardMode = true;
                    state.increasing = false;
                    facilityPage(audience, services, grant, state);
                }
                case "finance_credit_token" -> token(audience, services, grant, state, value);
                case "finance_credit_lost" -> lostPreview(audience, state);
                case "finance_credit_lost_ok" -> lost(audience, services, grant, state);
                case "finance_credit_replace" -> replacement(audience, services, grant, state);
                case "finance_credit_fee" -> replacementFee(audience, services, grant, state, value);
                case "finance_credit_repl_ok" -> replace(audience, services, grant, state);
                case "finance_credit_apply" -> application(audience, services, grant, state);
                case "finance_credit_network" -> {
                    requireOffered(audience, action, value);
                    state.network = InstitutionCatalog.Network.valueOf(value);
                    audience.reply("finance_credit", terms(state.network, List.of(UiText.t("ui.finance.creditmenus.2"),
                            state.context.profile().isEmpty() ? UiText.t("ui.finance.creditmenus.3") : UiText.t("ui.finance.creditmenus.4"))),UiText.t("ui.finance.creditmenus.1"), List.of(choice("finance_credit_preview", "",UiText.t("ui.finance.creditmenus.5"))));
                }
                case "finance_credit_preview" -> preview(audience, state, value, false);
                case "finance_credit_limit" -> preview(audience, state, value, true);
                case "finance_credit_confirm" -> issue(audience, services, grant, state);
                case "finance_credit_more" -> {
                    requireOffered(audience, action, value);
                    facilityPage(audience, services, grant, state);
                }
                case "finance_credit_pay" -> facilities(audience, services, grant, state, value, false);
                case "finance_credit_raise" -> facilities(audience, services, grant, state, value, true);
                case "finance_credit_fund" -> funding(audience, state, value);
                case "finance_credit_amount" -> paymentPreview(audience, services, grant, state, value);
                case "finance_credit_pay_ok" -> pay(audience, services, grant, state);
                case "finance_credit_raise_ok" -> increase(audience, services, grant, state);
                default -> { }
            }
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException invalid) {
            unavailable(audience);
        }
    }

    private static void application(FinanceMenuSupport.Audience audience, ServerServices services,
                                    PartyAuthorization.Grant grant, State state) {
        requireKiosk(audience);
        state.facility = null;
        state.network = null;
        state.repayment = null;
        state.fx = null;
        audience.menu().setBusy(true);
        applicationContext(audience, services, grant).whenComplete((snapshot, error) -> {
            if (!audience.live()) return;
            if (error != null || snapshot == null || !bound(snapshot.branch())) { unavailable(audience); return; }
            state.branch = snapshot.branch();
            state.context = snapshot.context();
            var bank = InstitutionCatalog.require(state.branch.terminal().binding().institution());
            if (!bank.tier1() && (!state.context.tier().available() || !state.context.tier().eligible())) {
                audience.reply("finance_credit", List.of(UiText.t("ui.finance.creditmenus.7")),UiText.t("ui.finance.creditmenus.6"), List.of());
                return;
            }
            state.currency = state.context.profile().isEmpty() ? state.context.denomination().currency() : bank.baseCurrency();
            var choices = bank.networks().stream().sorted().map(network -> choice("finance_credit_network", network.name(), network.name())).toList();
            audience.reply("finance_credit", List.of(bank.name(),UiText.t("ui.finance.creditmenus.9", state.currency.code()),UiText.t("ui.finance.creditmenus.10"),UiText.t("ui.finance.creditmenus.11")),UiText.t("ui.finance.creditmenus.8"), choices);
        });
    }

    private record ApplicationContext(LedgerService.BranchContext branch, LedgerService.LoanContext context) {}

    private static java.util.concurrent.CompletionStage<ApplicationContext> applicationContext(
            FinanceMenuSupport.Audience audience, ServerServices services, PartyAuthorization.Grant grant) {
        return services.ledger().branchContext(grant, audience.menu().anchor(), null).thenCompose(branch ->
                services.ledger().loanContext(grant).thenApply(context -> new ApplicationContext(branch.orElseThrow(), context.orElseThrow())));
    }

    private static boolean bound(LedgerService.BranchContext branch) {
        return branch != null && branch.terminal() != null && branch.terminal().binding() != null
                && branch.terminal().binding().bound() && branch.denomination() != null;
    }

    private static void preview(FinanceMenuSupport.Audience audience, State state, String value, boolean increase) {
        requireOffered(audience, increase ? "finance_credit_limit" : "finance_credit_preview", "");
        requireKiosk(audience);
        if (state.context == null || state.network == null || state.currency == null || !bound(state.branch)) throw new IllegalStateException("Missing credit terms");
        state.limit = wholeLimit(value, state.currency);
        if (increase && (state.facility == null || state.limit <= state.facility.limitMinor())) throw new IllegalArgumentException("Limit increase required");
        if (!increase && state.context.profile().isEmpty() && state.limit != Math.multiplyExact(2_500L, state.currency.minorUnitsPerMajor()))
            throw new IllegalArgumentException("Starter exact limit");
        String confirm = increase ? "finance_credit_raise_ok" : "finance_credit_confirm";
        var lines = terms(state.network, List.of(UiText.t("ui.finance.creditmenus.12", money(state.currency, state.limit)),UiText.t("ui.finance.creditmenus.13", state.context.profile().map(profile -> Integer.toString(profile.score())).orElse(UiText.t("ui.finance.first_score"))),UiText.t("ui.finance.creditmenus.14"),UiText.t("ui.finance.creditmenus.15")));
        audience.reply("finance_credit", lines,UiText.t("ui.finance.creditmenus.16"), List.of(choice(confirm, "",UiText.t("ui.finance.creditmenus.17"))));
        state.previewRevision = audience.menu().revision();
    }

    private static void issue(FinanceMenuSupport.Audience audience, ServerServices services,
                              PartyAuthorization.Grant grant, State state) {
        requireOffered(audience, "finance_credit_confirm", "");
        requirePreview(audience, state);
        var issuer = state.branch.terminal().binding().institution();
        var proof = FinanceMenuSupport.branch(audience, grant, state.branch, BranchAdmission.Action.CREDIT_ISSUE, issuer);
        var request = new CreditFacilities.IssueRequest(proof, UUID.randomUUID(), state.network, state.currency,
                state.limit, state.context.profile().map(CreditProfiles.Profile::revision).orElse(0L),
                state.context.tier().revision(), state.context.calendarOrigin());
        FinanceMenuSupport.submit(audience, services, grant, CreditFacilities.ISSUE, request.request(),
                (operation, attempt) -> services.ledger().issueCreditFacility(grant, operation, attempt, request));
    }

    private static void facilities(FinanceMenuSupport.Audience audience, ServerServices services,
                                   PartyAuthorization.Grant grant, State state, String value, boolean increase) {
        if (increase) requireKiosk(audience);
        String action = increase ? "finance_credit_raise" : "finance_credit_pay";
        if (value.isEmpty()) {
            state.after = null;
            state.increasing = increase;
            state.cardMode = false;
            facilityPage(audience, services, grant, state);
            return;
        }
        requireOffered(audience, action, value);
        state.facility = state.facilities.get(UUID.fromString(value));
        if (state.facility == null) throw new IllegalArgumentException("Facility not offered");
        state.repayment = null;
        state.fx = null;
        if (increase) { increaseContext(audience, services, grant, state); return; }
        audience.menu().setBusy(true);
        ownedAccounts(services, grant).whenComplete((found, error) -> {
            if (!audience.live()) return;
            if (error != null || found.isEmpty()) { unavailable(audience); return; }
            state.accounts = found.orElseThrow();
            var choices = state.accounts.values().stream().map(account -> choice("finance_credit_fund", account.id().value().toString(),UiText.t("ui.finance.creditmenus.18", account.currency().code(), (account.currency() == state.facility.currency() ? "" : UiText.t("ui.finance.fx_required"))))).toList();
            audience.reply("finance_credit", List.of(UiText.t("ui.finance.creditmenus.20", money(state.facility.currency(), state.facility.owedMinor())),UiText.t("ui.finance.creditmenus.21", money(state.facility.currency(), state.facility.requiredMinor())),UiText.t("ui.finance.creditmenus.22")),UiText.t("ui.finance.creditmenus.19"), choices);
        });
    }

    private static void facilityPage(FinanceMenuSupport.Audience audience, ServerServices services,
                                     PartyAuthorization.Grant grant, State state) {
        audience.menu().setBusy(true);
        services.ledger().creditFacilities(grant, state.after, 16).whenComplete((found, error) -> {
            if (!audience.live()) return;
            if (error != null || found.isEmpty()) { unavailable(audience); return; }
            var page = found.orElseThrow();
            var offered = new LinkedHashMap<UUID, CreditFacilities.Facility>();
            for (var facility : page) if (facility.status() == CreditFacilities.Status.ACTIVE
                    && (state.increasing || state.cardMode || facility.owedMinor() > 0)
                    && (!state.cardMode || facility.access() != CreditFacilities.Access.CLOSED)) offered.put(facility.id(), facility);
            state.facilities = Map.copyOf(offered);
            String action = state.cardMode ? "finance_credit_token" : state.increasing ? "finance_credit_raise" : "finance_credit_pay";
            var choices = new ArrayList<BankingNetwork.Choice>();
            for (var facility : offered.values()) choices.add(choice(action, facility.id().toString(),
                    InstitutionCatalog.require(facility.issuer()).name() + " · " + facility.network() + " · " + money(facility.currency(), facility.owedMinor())));
            if (page.size() == 16) {
                state.after = page.getLast().id();
                choices.add(choice("finance_credit_more", "",UiText.t("ui.finance.creditmenus.23")));
            }
            audience.reply("finance_credit", List.of(offered.isEmpty() ? UiText.t("ui.finance.creditmenus.24") : UiText.t("ui.finance.creditmenus.25"),UiText.t("ui.finance.creditmenus.26")), state.cardMode ? UiText.t("ui.finance.cards") : state.increasing ? UiText.t("ui.finance.increase") : UiText.t("ui.finance.repayment"), choices);
        });
    }

    private static void token(FinanceMenuSupport.Audience audience, ServerServices services,
                              PartyAuthorization.Grant grant, State state, String value) {
        requireOffered(audience, "finance_credit_token", value);
        state.facility = state.facilities.get(UUID.fromString(value));
        if (state.facility == null) throw new IllegalArgumentException("Facility not offered");
        audience.menu().setBusy(true);
        var facilityId = state.facility.id();
        services.readAccounts(grant, false, reads -> {
            var facility = CreditFacilities.find(reads, facilityId).filter(found -> found.party().equals(grant.target())).orElseThrow();
            var rows = reads.query("SELECT token_id FROM credit_facility_tokens WHERE world_id=? AND facility_id=? ORDER BY rowid DESC LIMIT 1",
                    List.of(grant.target().world().value().toString(), facility.id().toString()), 1);
            if (rows.isEmpty()) return Optional.<CreditFacilities.Token>empty();
            return CreditFacilities.token(reads, grant.target().world(), UUID.fromString(rows.getFirst().get("token_id")));
        }).whenComplete((found, error) -> {
            if (!audience.live()) return;
            if (error != null || found.isEmpty()) { unavailable(audience); return; }
            state.token = found.orElseThrow();
            var choices = new ArrayList<BankingNetwork.Choice>();
            if (state.token.status() == ca.emilybelnavis.mcmods.economy.card.IssuedCards.Status.ACTIVE)
                choices.add(choice("finance_credit_lost", "",UiText.t("ui.finance.creditmenus.27")));
            if (audience.menu().access() == BankingMenu.Access.KIOSK)
                choices.add(choice("finance_credit_replace", "",UiText.t("ui.finance.creditmenus.28")));
            choices.add(choice("claims", "",UiText.t("ui.finance.creditmenus.29")));
            audience.reply("finance_credit", List.of(InstitutionCatalog.require(state.token.issuer()).name() + " · " + state.token.network(),UiText.t("ui.finance.creditmenus.31", CreditReport.label(state.token.status())),UiText.t("ui.finance.creditmenus.32", Instant.ofEpochMilli(state.token.expiresAt())),UiText.t("ui.finance.creditmenus.33"),UiText.t("ui.finance.creditmenus.34")),UiText.t("ui.finance.creditmenus.30"), choices);
        });
    }

    private static void lostPreview(FinanceMenuSupport.Audience audience, State state) {
        requireOffered(audience, "finance_credit_lost", "");
        if (state.token == null) throw new IllegalStateException("Missing card");
        audience.reply("finance_credit", List.of(UiText.t("ui.finance.creditmenus.36"),UiText.t("ui.finance.creditmenus.37")),UiText.t("ui.finance.creditmenus.35"), List.of(choice("finance_credit_lost_ok", "",UiText.t("ui.finance.creditmenus.38"))));
        state.previewRevision = audience.menu().revision();
    }

    private static void lost(FinanceMenuSupport.Audience audience, ServerServices services,
                             PartyAuthorization.Grant grant, State state) {
        requireOffered(audience, "finance_credit_lost_ok", "");
        if (state.token == null || audience.menu().revision() != state.previewRevision) throw new IllegalStateException("Card preview stale");
        var token = state.token;
        var request = CreditFacilities.revokeRequest(grant.target().world(), token.owner(), token.id(), token.revision());
        FinanceMenuSupport.submit(audience, services, grant, CreditFacilities.REVOKE, request,
                (operation, attempt) -> services.ledger().revokeCreditCard(grant, operation, attempt, token.id(), token.revision()));
    }

    private static void replacement(FinanceMenuSupport.Audience audience, ServerServices services,
                                    PartyAuthorization.Grant grant, State state) {
        requireOffered(audience, "finance_credit_replace", "");
        requireKiosk(audience);
        if (state.token == null || state.facility == null) throw new IllegalStateException("Missing card");
        state.replacementFee = null;
        audience.menu().setBusy(true);
        services.ledger().branchContext(grant, audience.menu().anchor(), null).whenComplete((branch, error) -> {
            if (!audience.live()) return;
            if (error != null || branch.isEmpty() || !bound(branch.orElseThrow())
                    || !branch.orElseThrow().terminal().binding().institution().equals(state.token.issuer())) { unavailable(audience); return; }
            state.branch = branch.orElseThrow();
            if (System.currentTimeMillis() >= state.token.expiresAt() - ca.emilybelnavis.mcmods.economy.card.IssuedCards.RENEWAL_WINDOW) {
                replacementReview(audience, state);
                return;
            }
            ownedAccounts(services, grant).whenComplete((found, failed) -> {
                if (!audience.live()) return;
                if (failed != null || found.isEmpty()) { unavailable(audience); return; }
                state.accounts = found.orElseThrow();
                var choices = state.accounts.values().stream().map(account -> choice("finance_credit_fee", account.id().value().toString(),UiText.t("ui.finance.creditmenus.39", account.currency().code()))).toList();
                audience.reply("finance_credit", List.of(UiText.t("ui.finance.creditmenus.41"),UiText.t("ui.finance.creditmenus.42")),UiText.t("ui.finance.creditmenus.40"), choices);
            });
        });
    }

    private static java.util.concurrent.CompletionStage<Optional<Map<AccountId, AccountIdentity>>> ownedAccounts(
            ServerServices services, PartyAuthorization.Grant grant) {
        return services.readAccounts(grant, false, reads -> {
            var result = new LinkedHashMap<AccountId, AccountIdentity>();
            for (var row : reads.query("SELECT account_id FROM account_catalog WHERE owner_id=? AND purpose='BANK' AND status='OPEN' ORDER BY account_id LIMIT 4",
                    List.of(((PartyRef.Player) grant.target().owner()).id().value().toString()), 4)) {
                var id = new AccountId(UUID.fromString(row.get("account_id")));
                AccountCatalog.find(reads, grant.target(), id).ifPresent(account -> result.put(id, account));
            }
            return Optional.of(Map.copyOf(result));
        });
    }

    private static void replacementFee(FinanceMenuSupport.Audience audience, ServerServices services,
                                       PartyAuthorization.Grant grant, State state, String value) {
        requireOffered(audience, "finance_credit_fee", value);
        requireKiosk(audience);
        var funding = state.accounts.get(new AccountId(UUID.fromString(value)));
        if (funding == null || state.facility == null) throw new IllegalArgumentException("Funding not offered");
        audience.menu().setBusy(true);
        services.ledger().quoteCreditCardReplacement(grant, state.facility.id(), funding.id()).whenComplete((quote, error) -> {
            if (!audience.live()) return;
            if (error != null) { unavailable(audience); return; }
            state.replacementFee = quote;
            replacementReview(audience, state);
        });
    }

    private static void replacementReview(FinanceMenuSupport.Audience audience, State state) {
        var lines = new ArrayList<String>();
        lines.add(UiText.t("ui.finance.creditmenus.43", InstitutionCatalog.require(state.token.issuer()).name()));
        if (state.replacementFee == null) lines.add(UiText.t("ui.finance.creditmenus.44"));
        else {
            var capture = state.replacementFee.capture().split("\\|", -1);
            lines.add(UiText.t("ui.finance.creditmenus.45", money(state.replacementFee.currency(), Long.parseLong(capture[4]))));
            lines.add(UiText.t("ui.finance.creditmenus.46", money(state.replacementFee.currency(), Long.parseLong(capture[5]))));
            lines.add(UiText.t("ui.finance.creditmenus.47", money(state.replacementFee.currency(), state.replacementFee.debitMinor())));
            lines.add(UiText.t("ui.finance.creditmenus.48", Instant.ofEpochMilli(state.replacementFee.expiresAt())));
        }
        lines.add(UiText.t("ui.finance.creditmenus.49"));
        lines.add(UiText.t("ui.finance.creditmenus.50"));
        audience.reply("finance_credit", lines,UiText.t("ui.finance.creditmenus.51"), List.of(choice("finance_credit_repl_ok", "",UiText.t("ui.finance.creditmenus.52"))));
        state.previewRevision = audience.menu().revision();
    }

    private static void replace(FinanceMenuSupport.Audience audience, ServerServices services,
                                PartyAuthorization.Grant grant, State state) {
        requireOffered(audience, "finance_credit_repl_ok", "");
        requireKiosk(audience);
        if (state.token == null || state.facility == null || audience.menu().revision() != state.previewRevision)
            throw new IllegalStateException("Replacement preview stale");
        var proof = FinanceMenuSupport.branch(audience, grant, state.branch, BranchAdmission.Action.CREDIT_REPLACE, state.token.issuer());
        var request = new CreditFacilities.ReplacementRequest(proof, state.facility.id(), state.token.id(),
                state.facility.revision(), state.token.revision(), state.replacementFee);
        FinanceMenuSupport.submit(audience, services, grant, CreditFacilities.REPLACE, request.request(),
                (operation, attempt) -> services.ledger().replaceCreditCard(grant, operation, attempt, request));
    }

    private static void increaseContext(FinanceMenuSupport.Audience audience, ServerServices services,
                                        PartyAuthorization.Grant grant, State state) {
        audience.menu().setBusy(true);
        applicationContext(audience, services, grant).whenComplete((snapshot, error) -> {
            if (!audience.live()) return;
            if (error != null || snapshot == null || !bound(snapshot.branch())
                    || !state.facility.issuer().equals(snapshot.branch().terminal().binding().institution())) { unavailable(audience); return; }
            state.branch = snapshot.branch();
            state.context = snapshot.context();
            state.network = state.facility.network();
            state.currency = state.facility.currency();
            audience.reply("finance_credit", terms(state.network, List.of(UiText.t("ui.finance.creditmenus.54", money(state.currency, state.facility.limitMinor())),UiText.t("ui.finance.creditmenus.55"))),UiText.t("ui.finance.creditmenus.53"), List.of(choice("finance_credit_limit", "",UiText.t("ui.finance.creditmenus.56"))));
        });
    }

    private static void increase(FinanceMenuSupport.Audience audience, ServerServices services,
                                 PartyAuthorization.Grant grant, State state) {
        requireOffered(audience, "finance_credit_raise_ok", "");
        requirePreview(audience, state);
        var proof = FinanceMenuSupport.branch(audience, grant, state.branch, BranchAdmission.Action.CREDIT_INCREASE, state.facility.issuer());
        var request = new CreditFacilities.IncreaseRequest(proof, state.facility.id(), state.limit, state.facility.revision(),
                state.context.profile().orElseThrow().revision(), state.context.tier().revision(), state.context.calendarOrigin());
        FinanceMenuSupport.submit(audience, services, grant, CreditFacilities.INCREASE, request.request(),
                (operation, attempt) -> services.ledger().increaseCreditFacility(grant, operation, attempt, request));
    }

    private static void funding(FinanceMenuSupport.Audience audience, State state, String value) {
        requireOffered(audience, "finance_credit_fund", value);
        state.funding = state.accounts.get(new AccountId(UUID.fromString(value)));
        if (state.funding == null || state.facility == null) throw new IllegalArgumentException("Funding not offered");
        audience.reply("finance_credit", List.of(UiText.t("ui.finance.creditmenus.58", state.facility.currency().code()),UiText.t("ui.finance.creditmenus.59", money(state.facility.currency(), state.facility.owedMinor())),UiText.t("ui.finance.creditmenus.60")),UiText.t("ui.finance.creditmenus.57"), List.of(choice("finance_credit_amount", "",UiText.t("ui.finance.creditmenus.61"))));
    }

    private static void paymentPreview(FinanceMenuSupport.Audience audience, ServerServices services,
                                       PartyAuthorization.Grant grant, State state, String value) {
        requireOffered(audience, "finance_credit_amount", "");
        if (state.facility == null || state.funding == null) throw new IllegalStateException("Missing funding");
        long amount = paymentAmount(value, state.facility.currency());
        if (amount > state.facility.owedMinor()) throw new IllegalArgumentException("Payment exceeds debt");
        state.repayment = null;
        state.fx = null;
        audience.menu().setBusy(true);
        if (state.funding.currency() == state.facility.currency()) {
            services.ledger().quoteCreditRepayment(grant, state.facility.id(), state.funding.id(), amount).whenComplete((quote, error) -> {
                if (!audience.live()) return;
                if (error != null || quote.isEmpty()) { unavailable(audience); return; }
                state.repayment = quote.orElseThrow();
                if (state.repayment.facilityRevision() != state.facility.revision()) { unavailable(audience); return; }
                paymentReview(audience, state, new Money(state.funding.currency(), amount), new Money(state.funding.currency(), 0), state.repayment.expiresAt());
            });
        } else {
            services.ledger().quoteCreditRepaymentFx(grant, state.facility.id(), state.funding.id(), amount).whenComplete((quote, error) -> {
                if (!audience.live()) return;
                if (error != null) { unavailable(audience); return; }
                state.fx = quote;
                state.repayment = quote.repayment();
                if (state.repayment.facilityRevision() != state.facility.revision()) { unavailable(audience); return; }
                paymentReview(audience, state, quote.fx().debit(), quote.fx().fee(), quote.repayment().expiresAt());
            });
        }
    }

    private static void paymentReview(FinanceMenuSupport.Audience audience, State state, Money debit, Money fee, long expires) {
        var lines = new ArrayList<String>();
        lines.add(UiText.t("ui.finance.creditmenus.62", money(debit.currency(), debit.minorUnits())));
        lines.add(UiText.t("ui.finance.creditmenus.46", money(fee.currency(), fee.minorUnits())));
        lines.add(UiText.t("ui.finance.creditmenus.63", money(debit.currency(), Math.addExact(debit.minorUnits(), fee.minorUnits()))));
        lines.add(UiText.t("ui.finance.creditmenus.64", money(state.facility.currency(), state.repayment.amountMinor())));
        lines.add(UiText.t("ui.finance.creditmenus.65", money(state.facility.currency(), state.facility.requiredMinor())));
        state.facility.requirements().stream().filter(requirement -> requirement.unmetMinor() > 0)
                .mapToLong(CardServicing.Requirement::dueAt).min().ifPresent(due -> lines.add(UiText.t("ui.finance.creditmenus.66", Instant.ofEpochMilli(due))));
        lines.add(UiText.t("ui.finance.creditmenus.67", Instant.ofEpochMilli(expires)));
        lines.add(UiText.t("ui.finance.creditmenus.68"));
        lines.add(UiText.t("ui.finance.creditmenus.69"));
        audience.reply("finance_credit", lines,UiText.t("ui.finance.creditmenus.70"), List.of(choice("finance_credit_pay_ok", "",UiText.t("ui.finance.creditmenus.71"))));
        state.previewRevision = audience.menu().revision();
    }

    private static void pay(FinanceMenuSupport.Audience audience, ServerServices services,
                            PartyAuthorization.Grant grant, State state) {
        requireOffered(audience, "finance_credit_pay_ok", "");
        if (state.repayment == null || audience.menu().revision() != state.previewRevision) throw new IllegalStateException("Payment preview stale");
        if (state.fx != null) {
            var quote = state.fx;
            FinanceMenuSupport.submit(audience, services, grant, CreditFacilities.REPAY, quote.canonical(),
                    (operation, attempt) -> services.ledger().repayCreditFx(grant, operation, attempt, quote));
        } else {
            var quote = state.repayment;
            FinanceMenuSupport.submit(audience, services, grant, CreditFacilities.REPAY, quote.request(),
                    (operation, attempt) -> services.ledger().repayCredit(grant, operation, attempt, quote));
        }
    }

    static long wholeLimit(String value, EconomyCurrency currency) {
        if (value == null || !value.matches("[0-9]{1,18}")) throw new IllegalArgumentException("Whole-unit limit required");
        long result = Math.multiplyExact(Long.parseLong(value), currency.minorUnitsPerMajor());
        if (result <= 0) throw new IllegalArgumentException("Positive limit required");
        return result;
    }

    static long paymentAmount(String value, EconomyCurrency currency) {
        if (value == null || !value.matches("[0-9]{1,19}(\\.[0-9]{1,2})?")) throw new IllegalArgumentException("Exact payment amount required");
        long result = new BigDecimal(value).multiply(BigDecimal.valueOf(currency.minorUnitsPerMajor())).longValueExact();
        if (result <= 0) throw new IllegalArgumentException("Positive payment required");
        return result;
    }

    private static List<String> terms(InstitutionCatalog.Network network, List<String> introduction) {
        var lines = new ArrayList<>(introduction);
        lines.add(UiText.t("ui.finance.creditmenus.72", UiText.decimal(CreditFacilities.apr(network, false).movePointRight(2))));
        lines.add(UiText.t("ui.finance.creditmenus.73", UiText.decimal(CreditFacilities.apr(network, true).movePointRight(2))));
        lines.add(UiText.t("ui.finance.creditmenus.74"));
        lines.add(UiText.t("ui.finance.creditmenus.75"));
        lines.add(UiText.t("ui.finance.creditmenus.76"));
        lines.add(UiText.t("ui.finance.creditmenus.77"));
        lines.add(UiText.t("ui.finance.creditmenus.78"));
        return List.copyOf(lines);
    }

    private static void requireKiosk(FinanceMenuSupport.Audience audience) {
        if (audience.menu().access() != BankingMenu.Access.KIOSK) throw new IllegalArgumentException("Issuer kiosk required");
    }

    private static void requirePreview(FinanceMenuSupport.Audience audience, State state) {
        requireKiosk(audience);
        if (state.context == null || state.network == null || state.limit <= 0 || !bound(state.branch)
                || audience.menu().revision() != state.previewRevision) throw new IllegalStateException("Credit preview stale");
    }

    private static void requireOffered(FinanceMenuSupport.Audience audience, String action, String value) {
        if (!FinanceMenuSupport.offered(audience, action, value)) throw new IllegalArgumentException("Credit choice not offered");
    }

    private static BankingNetwork.Choice choice(String action, String value, String label) {
        return new BankingNetwork.Choice(action, value, label, false);
    }

    private static String money(EconomyCurrency currency, long minor) {
        return UiText.money(new Money(currency, minor));
    }

    private static void unavailable(FinanceMenuSupport.Audience audience) {
        audience.decline("finance_credit", List.of(UiText.t("ui.finance.creditmenus.80")),UiText.t("ui.finance.creditmenus.79"), List.of());
    }
}
