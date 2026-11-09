package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.credit.CreditUnderwriting;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.loan.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;
import net.minecraft.server.level.ServerPlayer;

/** Owner-only loan navigation and explicit, server-priced application/payment confirmations. */
public final class LoanMenus {
    private static final String PREFIX = "finance_loan_";
    private static final Set<String> ACTIONS = Set.of("home", "next", "view", "schedule", "sched_next",
            "inst", "inst_next", "apply", "product", "destination", "preview", "confirm",
            "funding", "repay", "payoff", "pay_confirm");
    private static final Map<BankingMenu, State> STATES = new WeakHashMap<>();
    private static final class State {
        UUID next;
        Loans.Contract loan;
        int scheduleAfter;
        Integer scheduleNext;
        int installmentAfter;
        Integer installmentNext;
        CreditUnderwriting.Product product = CreditUnderwriting.Product.BANK_LOAN;
        Map<UUID, AccountIdentity> accounts = Map.of();
        AccountIdentity destination;
        AccountIdentity funding;
        ApplicationPreview application;
        Loans.PaymentQuote payment;
        LoanRepaymentFx.Quote paymentFx;
    }
    private record ApplicationPreview(LedgerService.BranchContext branch, LedgerService.LoanContext context,
            CreditUnderwriting.NativeLoanOffer offer, Loans.Application request, LoanDisbursementFx.Quote fx, long expiresAt) {}
    private LoanMenus() {}
    public static boolean handles(String action) {
        return action.startsWith(PREFIX) && ACTIONS.contains(action.substring(PREFIX.length()));
    }
    public static Set<String> actions() {
        var result = new HashSet<String>();
        ACTIONS.forEach(action -> result.add(PREFIX + action));
        return Set.copyOf(result);
    }
    public static void closed(BankingMenu menu) { STATES.remove(menu); }
    public static void action(ServerPlayer player, BankingMenu menu, String action, String value) {
        if (!handles(action) || !menu.valid(player) || menu.busy() || value == null || value.length() > 2048) return;
        var services = Economy.serverServices(player.server);
        if (services == null || services.state() != ServerServices.State.READY) return;
        var grant = FinanceMenuSupport.grant(player, services);
        if (grant == null) return;
        var audience = FinanceMenuSupport.audience(player, menu);
        var state = STATES.computeIfAbsent(menu, ignored -> new State());
        try {
            switch (action.substring(PREFIX.length())) {
                case "home", "next" -> home(audience, services, grant, state, action.endsWith("_next"));
                case "view" -> view(audience, services, grant, state, value);
                case "schedule", "sched_next" -> schedule(audience, services, grant, state, action.endsWith("_next"));
                case "inst", "inst_next" -> installments(audience, services, grant, state, action.endsWith("_next"));
                case "apply" -> application(audience, services, grant, state);
                case "product" -> {
                    requireOffered(audience, action, value);
                    state.product = CreditUnderwriting.Product.valueOf(value);
                    state.application = null;
                    application(audience, services, grant, state);
                }
                case "destination" -> {
                    requireOffered(audience, action, value);
                    state.destination = Objects.requireNonNull(state.accounts.get(UUID.fromString(value)));
                    state.application = null;
                    application(audience, services, grant, state);
                }
                case "preview" -> preview(audience, services, grant, state, value);
                case "confirm" -> confirm(audience, services, grant, state);
                case "funding" -> funding(audience, services, grant, state, value);
                case "repay", "payoff" -> payment(audience, services, grant, state, value, action.endsWith("_payoff"));
                case "pay_confirm" -> payConfirm(audience, services, grant, state);
                default -> throw new IllegalArgumentException("Loan action");
            }
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException invalid) {
            unavailable(audience,UiText.t("ui.finance.loanmenus.1"));
        }
    }
    private static void home(FinanceMenuSupport.Audience audience, ServerServices services, PartyAuthorization.Grant grant,
            State state, boolean next) {
        UUID after = next ? Objects.requireNonNull(state.next) : null;
        audience.menu().setBusy(true);
        services.ledger().loanPage(grant, after, 8).whenComplete((found, error) -> {
            if (!audience.live()) return;
            if (error != null || found.isEmpty()) { unavailable(audience,UiText.t("ui.finance.loanmenus.2")); return; }
            var page = found.orElseThrow();
            state.next = page.nextCursor();
            var choices = new ArrayList<BankingNetwork.Choice>();
            for (var entry : page.entries()) {
                var loan = entry.contract();
                choices.add(choice("view", loan.id().toString(), InstitutionCatalog.require(loan.lender()).name()
                        + " · " + money(loan.currency(), loan.owedMinor()) + " · " + CreditReport.label(loan.status()), false));
            }
            if (state.next != null) choices.add(choice("next", "",UiText.t("ui.finance.loanmenus.3"), false));
            if (audience.menu().access() == BankingMenu.Access.KIOSK) choices.add(choice("apply", "",UiText.t("ui.finance.loanmenus.4"), false));
            reply(audience, "finance_loan_home", List.of(page.entries().isEmpty() ? UiText.t("ui.finance.loanmenus.5") : UiText.t("ui.finance.loanmenus.6")), UiText.t("ui.finance.my_loans"), choices);
        });
    }
    private static void view(FinanceMenuSupport.Audience audience, ServerServices services, PartyAuthorization.Grant grant,
            State state, String value) {
        requireOffered(audience, PREFIX + "view", value);
        UUID id = UUID.fromString(value);
        audience.menu().setBusy(true);
        services.ledger().loanView(grant, id).thenCombine(accounts(services, grant), Map::entry).whenComplete((result, error) -> {
            if (!audience.live()) return;
            if (error != null || result.getKey().isEmpty()) { unavailable(audience,UiText.t("ui.finance.loanmenus.7")); return; }
            var found = result.getKey().orElseThrow();
            state.loan = found.contract();
            state.accounts = result.getValue();
            state.payment = null;
            state.paymentFx = null;
            if (state.funding == null || !state.accounts.containsKey(state.funding.id().value())) state.funding = first(state.accounts);
            var choices = new ArrayList<BankingNetwork.Choice>();
            choices.add(choice("schedule", "",UiText.t("ui.finance.loanmenus.8"), false));
            choices.add(choice("inst", "",UiText.t("ui.finance.loanmenus.9"), false));
            if (state.loan.status() == Loans.Status.ACTIVE) {
                state.accounts.values().forEach(account -> choices.add(choice("funding", account.id().value().toString(),UiText.t("ui.finance.loanmenus.10", account.currency().code()), state.funding != null && account.id().equals(state.funding.id()))));
                choices.add(choice("repay", "",UiText.t("ui.finance.loanmenus.11"), false));
                choices.add(choice("payoff", "",UiText.t("ui.finance.loanmenus.12"), false));
            }
            reply(audience, "finance_loan_payment", List.of("Current Balance: " + money(state.loan.currency(), state.loan.owedMinor()),
                    "Due Date: " + (found.oldestDueAt() == null ? java.time.Instant.ofEpochMilli(state.loan.nextMonth()).atZone(java.time.ZoneOffset.UTC).toLocalDate() : java.time.Instant.ofEpochMilli(found.oldestDueAt()).atZone(java.time.ZoneOffset.UTC).toLocalDate()),
                    "Minimum Payment: " + money(state.loan.currency(), found.requiredMinor() > 0 ? found.requiredMinor() : state.loan.servicing().installmentMinor())), UiText.t("ui.finance.loan_contract"), choices);
        });
    }
    private static void schedule(FinanceMenuSupport.Audience audience, ServerServices services, PartyAuthorization.Grant grant,
            State state, boolean next) {
        requireOffered(audience, PREFIX + (next ? "sched_next" : "schedule"), "");
        if (state.loan == null) throw new IllegalArgumentException("Loan selection");
        state.scheduleAfter = next ? Objects.requireNonNull(state.scheduleNext) : 0;
        audience.menu().setBusy(true);
        services.ledger().loanSchedulePage(grant, state.loan.id(), state.scheduleAfter, 8).whenComplete((found, error) -> {
            if (!audience.live()) return;
            if (error != null || found.isEmpty()) { unavailable(audience,UiText.t("ui.finance.loanmenus.13")); return; }
            var page = found.orElseThrow();
            state.scheduleNext = page.nextOrdinal();
            var choices = new ArrayList<BankingNetwork.Choice>();
            if (state.scheduleNext != null) choices.add(choice("sched_next", "",UiText.t("ui.finance.loanmenus.14"), false));
            choices.add(choice("home", "",UiText.t("ui.finance.loanmenus.15"), false));
            reply(audience, "finance_loan_schedule", LoanReport.schedule(page), UiText.t("ui.finance.schedule"), choices);
        });
    }
    private static void installments(FinanceMenuSupport.Audience audience, ServerServices services, PartyAuthorization.Grant grant,
            State state, boolean next) {
        requireOffered(audience, PREFIX + (next ? "inst_next" : "inst"), "");
        if (state.loan == null) throw new IllegalArgumentException("Loan selection");
        state.installmentAfter = next ? Objects.requireNonNull(state.installmentNext) : 0;
        audience.menu().setBusy(true);
        services.ledger().loanInstallments(grant, state.loan.id(), state.installmentAfter, 8).whenComplete((found, error) -> {
            if (!audience.live()) return;
            if (error != null || found.isEmpty()) { unavailable(audience,UiText.t("ui.finance.loanmenus.16")); return; }
            var page = found.orElseThrow();
            state.installmentNext = page.nextOrdinal();
            var choices = new ArrayList<BankingNetwork.Choice>();
            if (state.installmentNext != null) choices.add(choice("inst_next", "",UiText.t("ui.finance.loanmenus.17"), false));
            choices.add(choice("home", "",UiText.t("ui.finance.loanmenus.15"), false));
            reply(audience, "finance_loan_schedule", LoanReport.installments(page), UiText.t("ui.finance.installments"), choices);
        });
    }
    private static void application(FinanceMenuSupport.Audience audience, ServerServices services, PartyAuthorization.Grant grant, State state) {
        if (audience.menu().access() != BankingMenu.Access.KIOSK) throw new IllegalArgumentException("Live branch required");
        audience.menu().setBusy(true);
        services.ledger().branchContext(grant, audience.menu().anchor(), null).thenCombine(accounts(services, grant), Map::entry)
                .whenComplete((result, error) -> {
            if (!audience.live()) return;
            if (error != null || result.getKey().isEmpty()) { unavailable(audience,UiText.t("ui.finance.loanmenus.18")); return; }
            var branch = result.getKey().orElseThrow();
            InstitutionCatalog.Entry bank;
            try { bank = bank(branch); }
            catch (IllegalArgumentException unavailable) { unavailable(audience,UiText.t("ui.finance.loanmenus.19")); return; }
            state.accounts = result.getValue();
            if (state.destination == null || !state.accounts.containsKey(state.destination.id().value())) state.destination = first(state.accounts);
            if (!bank.tier1()) state.product = CreditUnderwriting.Product.BANK_LOAN;
            var choices = new ArrayList<BankingNetwork.Choice>();
            choices.add(choice("product", CreditUnderwriting.Product.BANK_LOAN.name(),UiText.t("ui.finance.loanmenus.20"), state.product == CreditUnderwriting.Product.BANK_LOAN));
            if (bank.tier1()) choices.add(choice("product", CreditUnderwriting.Product.SUBPRIME_LOAN.name(),UiText.t("ui.finance.loanmenus.21"), state.product == CreditUnderwriting.Product.SUBPRIME_LOAN));
            state.accounts.values().forEach(account -> choices.add(choice("destination", account.id().value().toString(),UiText.t("ui.finance.loanmenus.22", account.currency().code()), state.destination != null && account.id().equals(state.destination.id()))));
            if (state.destination != null) choices.add(choice("preview", "",UiText.t("ui.finance.loanmenus.23"), false));
            reply(audience, "finance_loan_input", List.of(UiText.t("ui.finance.loanmenus.24", bank.name()),UiText.t("ui.finance.loanmenus.25"),UiText.t("ui.finance.loanmenus.26"),UiText.t("ui.finance.loanmenus.27"),UiText.t("ui.finance.loanmenus.28")), UiText.t("ui.finance.choose_product_destination"), choices);
        });
    }
    private static void preview(FinanceMenuSupport.Audience audience, ServerServices services, PartyAuthorization.Grant grant,
            State state, String value) {
        if (!audience.menu().page().equals("finance_loan_input") || state.destination == null || audience.menu().access() != BankingMenu.Access.KIOSK)
            throw new IllegalArgumentException("Application selection");
        String[] fields = value.trim().split("\\s+");
        if (fields.length != 2 || !fields[0].matches("[1-9][0-9]{0,17}") || !fields[1].matches("[1-8]")) throw new IllegalArgumentException("Principal and years");
        long principalMajor = Long.parseLong(fields[0]);
        int years = Integer.parseInt(fields[1]);
        var destination = state.destination;
        var product = state.product;
        state.application = null;
        audience.menu().setBusy(true);
        services.ledger().branchContext(grant, audience.menu().anchor(), destination.id())
                .thenCompose(branch -> {
                    var current=branch.orElseThrow();
                    return (product==CreditUnderwriting.Product.SUBPRIME_LOAN?services.ledger().loanContext(grant):services.ledger().loanContext(grant,bank(current).baseCurrency()))
                            .thenApply(context->Map.entry(branch,context));
                }).thenCompose(result -> {
            if (!audience.live()) throw new IllegalStateException("Replaced audience");
            var branch = result.getKey().orElseThrow();
            var context = result.getValue().orElseThrow();
            var bank = bank(branch);
            var currency = product == CreditUnderwriting.Product.SUBPRIME_LOAN ? context.denomination().currency() : bank.baseCurrency();
            long principal = Math.multiplyExact(principalMajor, currency.minorUnitsPerMajor());
            return services.readAccounts(grant, false, reads -> {
                long exposure = Math.addExact(context.inputs().otherExposureWorldMinor(), Loans.exposure(reads, grant.target(), context.denomination().currency(), context.inputs().fx(), services.financialMillis()));
                boolean blocked = context.inputs().creditBlocked() || Loans.blocked(reads, grant.target(), services.financialMillis());
                var terms = new CreditUnderwriting.LoanRequest(context.profile().isPresent(), context.profile().map(p -> p.score()).orElse(650),
                        context.denomination().currency(), product, product == CreditUnderwriting.Product.BANK_LOAN && bank.tier2() && context.tier().available() && context.tier().eligible(), context.tier().available() && context.tier().eligible(),
                        context.inputs().complete(), blocked, exposure, principal, years,
                        product == CreditUnderwriting.Product.SUBPRIME_LOAN ? BigDecimal.ZERO : context.currencyPrime().orElseThrow().primeApr());
                var offer = CreditUnderwriting.loanForFacility(terms, new Money(currency, principal), context.inputs().fx());
                return Optional.of(Map.entry(branch, Map.entry(context, offer)));
            });
        }).whenComplete((found, error) -> {
            if (!audience.live()) return;
            if (error != null || found.isEmpty()) { unavailable(audience,UiText.t("ui.finance.loanmenus.29")); return; }
            var data = found.orElseThrow();
            var branch = data.getKey();
            var context = data.getValue().getKey();
            var offer = data.getValue().getValue();
            if (!offer.worldOffer().available() || !offer.worldOffer().approved()) {
                reply(audience, "finance_loan_offer", List.of(UiText.t("ui.finance.loanmenus.30", CreditReport.label(offer.worldOffer().reason())),UiText.t("ui.finance.loanmenus.31", money(offer.maximumNativePrincipal())),UiText.t("ui.finance.loanmenus.32")), UiText.t("ui.finance.application_unavailable"), List.of(choice("apply", "",UiText.t("ui.finance.loanmenus.33"), false)));
                return;
            }
            try {
                long expiresAt = Math.addExact(BankingController.services(audience.player()).financialMillis(), 30_000);
                var proof = FinanceMenuSupport.branch(audience, grant, branch, BranchAdmission.Action.LOAN_APPLY, null);
                var request = new Loans.Application(proof, UUID.randomUUID(), UUID.randomUUID(), product,
                        offer.requestedNative().currency(), offer.requestedNative().minorUnits(), years, destination.id(), destination.revision(),
                        context.profile().map(p -> p.revision()).orElse(0L), context.tier().revision(),
                        product == CreditUnderwriting.Product.SUBPRIME_LOAN ? 0 : context.currencyPrime().orElseThrow().legacyVersion(),
                        context.inputs().fx().publication(), context.calendarOrigin(), Loans.RateMode.FIXED, false,
                        product==CreditUnderwriting.Product.BANK_LOAN&&context.currencyPrime().orElseThrow().currencySpecific()?context.currencyPrime().orElseThrow().id():null,
                        product==CreditUnderwriting.Product.BANK_LOAN&&context.currencyPrime().orElseThrow().currencySpecific()?context.currencyPrime().orElseThrow().revision():0);
                if (destination.currency() == request.currency()) {
                    publishOffer(audience, state, new ApplicationPreview(branch, context, offer, request, null, expiresAt));
                } else {
                    services.ledger().quoteLoanDisbursementFx(grant, request).whenComplete((fx, fxError) -> {
                        if (!audience.live()) return;
                        if (fxError != null || fx.isEmpty()) { unavailable(audience,UiText.t("ui.finance.loanmenus.34")); return; }
                        publishOffer(audience, state, new ApplicationPreview(branch, context, offer, request, fx.orElseThrow(), expiresAt));
                    });
                }
            } catch (IllegalArgumentException | IllegalStateException invalid) { unavailable(audience,UiText.t("ui.finance.loanmenus.35")); }
        });
    }
    private static void publishOffer(FinanceMenuSupport.Audience audience, State state, ApplicationPreview preview) {
        state.application = preview;
        var request = preview.request();
        var offer = preview.offer().worldOffer();
        var schedule = LoanServicing.schedule(request.currency(), request.principalMinor(), offer.apr(), request.years(), request.product() == CreditUnderwriting.Product.SUBPRIME_LOAN);
        var lines = new ArrayList<String>();
        lines.add(UiText.t("ui.finance.loanmenus.36", money(request.currency(), request.principalMinor()), request.years()));
        lines.add(UiText.t("ui.finance.loanmenus.37", UiText.decimal(offer.apr().movePointRight(2).stripTrailingZeros())));
        lines.add(UiText.t("ui.finance.loanmenus.38", money(request.currency(), schedule.installmentMinor())));
        long firstDue = Math.addExact(BankingController.services(audience.player()).financialMillis(), Loans.MONTH);
        lines.add(UiText.t("ui.finance.loanmenus.39", Instant.ofEpochMilli(firstDue)));
        lines.add(UiText.t("ui.finance.loanmenus.40"));
        lines.add(UiText.t("ui.finance.loanmenus.41"));
        if (preview.fx() != null) {
            lines.add(UiText.t("ui.finance.loanmenus.42", money(preview.fx().destinationCurrency(), preview.fx().creditedMinor())));
            lines.add(UiText.t("ui.finance.loanmenus.43", money(preview.fx().destinationCurrency(), preview.fx().feeFundingMinor()), money(preview.fx().destinationCurrency(), Math.subtractExact(preview.fx().creditedMinor(), preview.fx().feeFundingMinor()))));
        } else lines.add(UiText.t("ui.finance.loanmenus.44", money(request.currency(), request.principalMinor())));
        lines.add(UiText.t("ui.finance.loanmenus.45"));
        reply(audience, "finance_loan_offer", lines, UiText.t("ui.finance.review_terms"), List.of(choice("confirm", "",UiText.t("ui.finance.loanmenus.46"), false), choice("apply", "",UiText.t("ui.finance.loanmenus.47"), false)));
    }
    private static void confirm(FinanceMenuSupport.Audience audience, ServerServices services, PartyAuthorization.Grant grant, State state) {
        requireOffered(audience, PREFIX + "confirm", "");
        var preview = Objects.requireNonNull(state.application);
        if (audience.menu().access() != BankingMenu.Access.KIOSK || BankingController.services(audience.player()).financialMillis() >= preview.expiresAt()) throw new IllegalArgumentException("Fresh live branch offer required");
        state.application = null;
        if (preview.fx() != null) {
            FinanceMenuSupport.submit(audience, services, grant, Loans.APPLY, preview.fx().canonical(),
                    (operation, attempt) -> services.ledger().applyLoanFx(grant, operation, attempt, preview.fx()));
        } else {
            var old = preview.request();
            var proof = FinanceMenuSupport.branch(audience, grant, preview.branch(), BranchAdmission.Action.LOAN_APPLY, null);
            var request = new Loans.Application(proof, old.application(), old.loan(), old.product(), old.currency(), old.principalMinor(), old.years(), old.destination(), old.destinationRevision(),
                    old.profileRevision(), old.tierRevision(), old.primeVersion(), old.fxPublication(), old.calendarOrigin(), old.rateMode(), old.secured(),old.primePolicyId(),old.primePolicyRevision());
            FinanceMenuSupport.submit(audience, services, grant, Loans.APPLY, request.request(),
                    (operation, attempt) -> services.ledger().applyLoan(grant, operation, attempt, request));
        }
    }
    private static void funding(FinanceMenuSupport.Audience audience, ServerServices services, PartyAuthorization.Grant grant, State state, String value) {
        requireOffered(audience, PREFIX + "funding", value);
        state.funding = Objects.requireNonNull(state.accounts.get(UUID.fromString(value)));
        state.payment = null;
        state.paymentFx = null;
        reply(audience, "finance_loan_payment", List.of(UiText.t("ui.finance.loanmenus.48", state.funding.currency().code()),UiText.t("ui.finance.loanmenus.49")), UiText.t("ui.finance.choose_amount"), paymentChoices(state));
    }
    private static List<BankingNetwork.Choice> paymentChoices(State state) {
        var choices = new ArrayList<BankingNetwork.Choice>();
        state.accounts.values().forEach(account -> choices.add(choice("funding", account.id().value().toString(),UiText.t("ui.finance.loanmenus.10", account.currency().code()), account.id().equals(state.funding.id()))));
        choices.add(choice("repay", "",UiText.t("ui.finance.loanmenus.11"), false));
        choices.add(choice("payoff", "",UiText.t("ui.finance.loanmenus.12"), false));
        return choices;
    }
    private static void payment(FinanceMenuSupport.Audience audience, ServerServices services, PartyAuthorization.Grant grant, State state, String value, boolean payoff) {
        if (!audience.menu().page().equals("finance_loan_payment") || state.loan == null || state.funding == null
                || audience.menu().choices().stream().noneMatch(c -> c.action().equals(PREFIX + (payoff ? "payoff" : "repay")))) throw new IllegalArgumentException("Payment selection");
        long amount = payoff ? 0 : amount(state.loan.currency(), value);
        var loan = state.loan;
        var funding = state.funding;
        state.payment = null;
        state.paymentFx = null;
        audience.menu().setBusy(true);
        if (funding.currency() == loan.currency()) {
            services.ledger().quoteLoanRepayment(grant, loan.id(), funding.id(), amount, payoff).whenComplete((found, error) -> {
                if (!audience.live()) return;
                if (error != null || found.isEmpty()) { unavailable(audience,UiText.t("ui.finance.loanmenus.50")); return; }
                state.payment = found.orElseThrow();
                publishPayment(audience, state.payment, null, loan.currency());
            });
        } else {
            services.ledger().quoteLoanRepaymentFx(grant, loan.id(), funding.id(), amount, payoff).whenComplete((quote, error) -> {
                if (!audience.live()) return;
                if (error != null) { unavailable(audience,UiText.t("ui.finance.loanmenus.51")); return; }
                state.paymentFx = quote;
                publishPayment(audience, quote.payment(), quote, loan.currency());
            });
        }
    }
    private static void publishPayment(FinanceMenuSupport.Audience audience, Loans.PaymentQuote quote, LoanRepaymentFx.Quote fx, EconomyCurrency currency) {
        var r = quote.request();
        var lines = new ArrayList<String>();
        lines.add(r.fullPayoff() ? UiText.t("ui.finance.loanmenus.52") : UiText.t("ui.finance.loanmenus.53"));
        if (fx != null) {
            lines.add(UiText.t("ui.finance.loanmenus.54", money(fx.fx().credit()), money(fx.fx().debit())));
            lines.add(UiText.t("ui.finance.loanmenus.55", money(fx.fx().fee()), money(fx.fx().debit().currency(), Math.addExact(fx.fx().debit().minorUnits(), fx.fx().fee().minorUnits()))));
        } else lines.add(UiText.t("ui.finance.loanmenus.56", money(currency, r.amountMinor())));
        lines.add(UiText.t("ui.finance.loanmenus.57", Instant.ofEpochMilli(r.expiresAt())));
        if (r.fullPayoff()) lines.add(UiText.t("ui.finance.loanmenus.58"));
        reply(audience, "finance_loan_pay_quote", lines, UiText.t("ui.finance.review_repayment"), List.of(choice("pay_confirm", "",UiText.t("ui.finance.loanmenus.59"), false), choice("home", "",UiText.t("ui.finance.loanmenus.15"), false)));
    }
    private static void payConfirm(FinanceMenuSupport.Audience audience, ServerServices services, PartyAuthorization.Grant grant, State state) {
        requireOffered(audience, PREFIX + "pay_confirm", "");
        var fx = state.paymentFx;
        var nativeQuote = state.payment;
        state.paymentFx = null;
        state.payment = null;
        if (fx != null) FinanceMenuSupport.submit(audience, services, grant, Loans.REPAY, fx.canonical(), (operation, attempt) -> services.ledger().repayLoanFx(grant, operation, attempt, fx));
        else {
            var quote = Objects.requireNonNull(nativeQuote);
            FinanceMenuSupport.submit(audience, services, grant, Loans.REPAY, quote.request().request(), (operation, attempt) -> services.ledger().repayLoan(grant, operation, attempt, quote));
        }
    }
    private static CompletionStage<Map<UUID, AccountIdentity>> accounts(ServerServices services, PartyAuthorization.Grant grant) {
        return services.readAccounts(grant, false, reads -> {
            var rows = reads.query("SELECT account_id FROM account_catalog WHERE owner_id=? AND purpose='BANK' AND status='OPEN' ORDER BY account_id LIMIT 4", List.of(((PartyRef.Player) grant.target().owner()).id().value().toString()), 4);
            var result = new LinkedHashMap<UUID, AccountIdentity>();
            for (var row : rows) {
                var id = UUID.fromString(row.get("account_id"));
                result.put(id, AccountCatalog.find(reads, grant.target(), new AccountId(id)).orElseThrow());
            }
            return Optional.of(Collections.unmodifiableMap(result));
        }).thenApply(Optional::orElseThrow);
    }
    private static InstitutionCatalog.Entry bank(LedgerService.BranchContext branch) {
        if (branch.terminal() == null || branch.terminal().binding() == null || !branch.terminal().binding().bound()) throw new IllegalArgumentException("Unbound branch");
        var bank = InstitutionCatalog.require(branch.terminal().binding().institution());
        if (!bank.customerBank()) throw new IllegalArgumentException("Customer bank required");
        return bank;
    }
    private static AccountIdentity first(Map<UUID, AccountIdentity> accounts) { return accounts.values().stream().findFirst().orElse(null); }
    private static void requireOffered(FinanceMenuSupport.Audience audience, String action, String value) {
        if (!FinanceMenuSupport.offered(audience, action, value)) throw new IllegalArgumentException("Unadvertised loan action");
    }
    private static BankingNetwork.Choice choice(String action, String value, String label, boolean selected) {
        return new BankingNetwork.Choice(PREFIX + action, value, label, selected);
    }
    private static void unavailable(FinanceMenuSupport.Audience audience, String text) {
        audience.decline("finance_loan_home", List.of(text),UiText.t("ui.finance.loanmenus.60"), List.of(choice("home", "",UiText.t("ui.finance.loanmenus.15"), false)));
    }
    private static void reply(FinanceMenuSupport.Audience audience, String page, List<String> lines, String status, List<BankingNetwork.Choice> choices) {
        audience.reply(page, lines, status, choices);
    }
    private static String money(EconomyCurrency currency, long minor) { return money(new Money(currency, minor)); }
    private static String money(Money money) { return UiText.money(money); }
    private static long amount(EconomyCurrency currency, String value) {
        if (!value.matches("[0-9]{1,18}(\\.[0-9]{1,2})?")) throw new IllegalArgumentException("Payment amount");
        long amount = new BigDecimal(value).multiply(BigDecimal.valueOf(currency.minorUnitsPerMajor())).longValueExact();
        if (amount <= 0) throw new IllegalArgumentException("Payment amount");
        return amount;
    }
}
