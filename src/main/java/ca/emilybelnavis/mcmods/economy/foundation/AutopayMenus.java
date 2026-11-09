package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.autopay.Autopay;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.credit.CreditFacilities;
import ca.emilybelnavis.mcmods.economy.debt.DebtRecords.Kind;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.loan.Loans;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;
import net.minecraft.server.level.ServerPlayer;

/**
 * Owner-consented automatic-payment settings. The server resolves every product and funding
 * account.
 */
public final class AutopayMenus {
  private static final String PAGE = "finance_autopay";
  private static final Set<String> ACTIONS =
      Set.of(
          "auto_home",
          "auto_cards",
          "auto_loans",
          "auto_next",
          "auto_select",
          "auto_funding",
          "auto_mode",
          "auto_disable",
          "auto_confirm");
  private static final Map<BankingMenu, State> STATES = new WeakHashMap<>();

  private record Product(
      Kind kind,
      UUID id,
      EconomyCurrency currency,
      long revision,
      boolean available,
      String label) {}

  private static final class State {
    Kind kind;
    UUID after;
    Map<UUID, Product> offered = Map.of();
    Product selected;
    Autopay.Settings settings;
    Map<AccountId, AccountIdentity> accounts = Map.of();
    AccountIdentity funding;
    Autopay.Configure preview;
    long previewRevision;
    long expiresAt;
  }

  private record Selection(
      Product product,
      Optional<Autopay.Settings> settings,
      Map<AccountId, AccountIdentity> accounts) {}

  private AutopayMenus() {}

  public static Set<String> actions() {
    return ACTIONS;
  }

  public static boolean handles(String action) {
    return ACTIONS.contains(action);
  }

  public static void closed(BankingMenu menu) {
    STATES.remove(menu);
  }

  public static void action(ServerPlayer player, BankingMenu menu, String action, String value) {
    if (!handles(action)
        || !menu.valid(player)
        || menu.busy()
        || value == null
        || value.length() > 128
        || !Set.of(
                BankingMenu.Access.ATM,
                BankingMenu.Access.TELLER,
                BankingMenu.Access.KIOSK,
                BankingMenu.Access.CARD)
            .contains(menu.access())) return;
    var services = Economy.serverServices(player.server);
    if (services == null || services.state() != ServerServices.State.READY) return;
    var grant = FinanceMenuSupport.grant(player, services);
    if (grant == null) return;
    var audience = FinanceMenuSupport.audience(player, menu);
    var state = STATES.computeIfAbsent(menu, ignored -> new State());
    try {
      switch (action) {
        case "auto_home" -> home(audience, state);
        case "auto_cards", "auto_loans" -> {
          state.kind = action.equals("auto_cards") ? Kind.CARD : Kind.LOAN;
          state.after = null;
          page(audience, services, grant, state);
        }
        case "auto_next" -> {
          requireOffered(audience, action, value);
          page(audience, services, grant, state);
        }
        case "auto_select" -> select(audience, services, grant, state, value);
        case "auto_funding" -> funding(audience, state, value);
        case "auto_mode" -> {
          requireOffered(audience, action, value);
          review(audience, grant, state, true, Autopay.Mode.valueOf(value));
        }
        case "auto_disable" -> {
          requireOffered(audience, action, value);
          if (state.settings == null || !state.settings.enabled())
            throw new IllegalArgumentException("Automatic payments already off");
          review(audience, grant, state, false, state.settings.mode());
        }
        case "auto_confirm" -> confirm(audience, services, grant, state);
        default -> {}
      }
    } catch (IllegalArgumentException | IllegalStateException | ArithmeticException invalid) {
      unavailable(audience);
    }
  }

  private static void home(FinanceMenuSupport.Audience audience, State state) {
    state.kind = null;
    state.offered = Map.of();
    clearSelection(state);
    audience.reply(
        PAGE,
        List.of(UiText.t("ui.finance.autopaymenus.2"), UiText.t("ui.finance.autopaymenus.3")),
        UiText.t("ui.finance.autopaymenus.1"),
        List.of(
            choice("auto_cards", "", UiText.t("ui.finance.autopaymenus.4")),
            choice("auto_loans", "", UiText.t("ui.finance.autopaymenus.5"))));
  }

  private static void page(
      FinanceMenuSupport.Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state) {
    if (state.kind == null) throw new IllegalArgumentException("Choose cards or loans");
    clearSelection(state);
    audience.menu().setBusy(true);
    CompletionStage<Optional<List<Product>>> result;
    if (state.kind == Kind.CARD) {
      result =
          services
              .ledger()
              .creditFacilities(grant, state.after, 16)
              .thenApply(
                  found -> found.map(cards -> cards.stream().map(AutopayMenus::card).toList()));
    } else {
      result =
          services
              .ledger()
              .loans(grant, state.after, 16)
              .thenApply(
                  found -> found.map(loans -> loans.stream().map(AutopayMenus::loan).toList()));
    }
    result.whenComplete(
        (found, error) -> {
          if (!audience.live()) return;
          if (error != null || found.isEmpty()) {
            unavailable(audience);
            return;
          }
          var products = found.orElseThrow();
          var offered = new LinkedHashMap<UUID, Product>();
          var choices = new ArrayList<BankingNetwork.Choice>();
          for (var product : products) {
            offered.put(product.id(), product);
            choices.add(choice("auto_select", product.id().toString(), product.label()));
          }
          state.offered = Map.copyOf(offered);
          if (products.size() == 16) {
            state.after = products.getLast().id();
            choices.add(choice("auto_next", "", UiText.t("ui.finance.creditmenus.23")));
          } else state.after = null;
          choices.add(choice("auto_home", "", UiText.t("ui.finance.autopaymenus.6")));
          audience.reply(
              PAGE,
              List.of(
                  products.isEmpty()
                      ? UiText.t("ui.finance.autopaymenus.8")
                      : UiText.t("ui.finance.autopaymenus.9")),
              UiText.t("ui.finance.autopaymenus.7"),
              choices);
        });
  }

  private static void select(
      FinanceMenuSupport.Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      String value) {
    requireOffered(audience, "auto_select", value);
    var offered = state.offered.get(UUID.fromString(value));
    if (offered == null) throw new IllegalArgumentException("Facility not offered");
    clearSelection(state);
    audience.menu().setBusy(true);
    current(services, grant, offered)
        .thenCompose(
            product ->
                services
                    .ledger()
                    .autopay(grant, product.kind(), product.id())
                    .thenCompose(
                        settings ->
                            accounts(services, grant, product.currency())
                                .thenApply(
                                    accounts ->
                                        new Selection(product, settings, accounts.orElseThrow()))))
        .whenComplete(
            (selection, error) -> {
              if (!audience.live()) return;
              if (error != null || selection == null) {
                unavailable(audience);
                return;
              }
              state.selected = selection.product();
              state.settings = selection.settings().orElse(null);
              state.accounts = selection.accounts();
              var lines = new ArrayList<String>();
              lines.add(state.selected.label());
              lines.add(
                  UiText.t(
                      "ui.finance.autopaymenus.10",
                      (state.settings != null && state.settings.enabled()
                          ? UiText.t("ui.finance.on")
                          : UiText.t("ui.finance.off"))));
              if (state.settings != null && state.settings.enabled()) {
                lines.add(UiText.t("ui.finance.autopaymenus.11", modeName(state.settings.mode())));
                var source = state.accounts.get(state.settings.funding());
                lines.add(
                    source == null
                        ? UiText.t("ui.finance.autopaymenus.12")
                        : UiText.t("ui.finance.autopaymenus.13", accountName(source)));
                lines.add(
                    UiText.t(
                        "ui.finance.autopaymenus.14",
                        Instant.ofEpochMilli(state.settings.effectiveAt())));
              }
              var choices = new ArrayList<BankingNetwork.Choice>();
              if (state.selected.available()) {
                lines.add(UiText.t("ui.finance.autopaymenus.15", state.selected.currency().code()));
                for (var account : state.accounts.values())
                  choices.add(
                      choice(
                          "auto_funding", account.id().value().toString(), accountName(account)));
                if (state.accounts.isEmpty()) lines.add(UiText.t("ui.finance.autopaymenus.16"));
              } else lines.add(UiText.t("ui.finance.autopaymenus.17"));
              if (state.settings != null && state.settings.enabled())
                choices.add(choice("auto_disable", "", UiText.t("ui.finance.autopaymenus.18")));
              choices.add(choice("auto_home", "", UiText.t("ui.finance.autopaymenus.19")));
              audience.reply(PAGE, lines, UiText.t("ui.finance.autopaymenus.20"), choices);
            });
  }

  private static CompletionStage<Product> current(
      ServerServices services, PartyAuthorization.Grant grant, Product offered) {
    return services
        .readAccounts(
            grant,
            false,
            reads ->
                offered.kind() == Kind.CARD
                    ? CreditFacilities.find(reads, offered.id())
                        .filter(f -> f.party().equals(grant.target()))
                        .map(AutopayMenus::card)
                    : Loans.find(reads, offered.id())
                        .filter(l -> l.party().equals(grant.target()))
                        .map(AutopayMenus::loan))
        .thenApply(Optional::orElseThrow);
  }

  private static CompletionStage<Optional<Map<AccountId, AccountIdentity>>> accounts(
      ServerServices services, PartyAuthorization.Grant grant, EconomyCurrency currency) {
    return services.readAccounts(
        grant,
        false,
        reads -> {
          var result = new LinkedHashMap<AccountId, AccountIdentity>();
          for (var account : AccountCatalog.openBanks(reads, grant.target()))
            if (account.currency() == currency) result.put(account.id(), account);
          return Optional.of(Collections.unmodifiableMap(result));
        });
  }

  private static void funding(FinanceMenuSupport.Audience audience, State state, String value) {
    requireOffered(audience, "auto_funding", value);
    state.funding = state.accounts.get(new AccountId(UUID.fromString(value)));
    state.preview = null;
    if (state.funding == null || state.selected == null || !state.selected.available())
      throw new IllegalArgumentException("Funding not offered");
    if (state.selected.kind() == Kind.LOAN) {
      audience.reply(
          PAGE,
          List.of(
              UiText.t("ui.finance.autopaymenus.13", accountName(state.funding)),
              UiText.t("ui.finance.autopaymenus.22")),
          UiText.t("ui.finance.autopaymenus.21"),
          List.of(
              choice(
                  "auto_mode",
                  Autopay.Mode.LOAN_DUE.name(),
                  UiText.t("ui.finance.autopaymenus.23"))));
    } else {
      audience.reply(
          PAGE,
          List.of(
              UiText.t("ui.finance.autopaymenus.13", accountName(state.funding)),
              UiText.t("ui.finance.autopaymenus.25"),
              UiText.t("ui.finance.autopaymenus.26")),
          UiText.t("ui.finance.autopaymenus.24"),
          List.of(
              new BankingNetwork.Choice(
                  "auto_mode",
                  Autopay.Mode.MINIMUM.name(),
                  UiText.t("ui.finance.autopaymenus.27"),
                  true),
              choice(
                  "auto_mode",
                  Autopay.Mode.STATEMENT.name(),
                  UiText.t("ui.finance.autopaymenus.28")),
              choice(
                  "auto_mode",
                  Autopay.Mode.FULL_PAYOFF.name(),
                  UiText.t("ui.finance.autopaymenus.29"))));
    }
  }

  private static void review(
      FinanceMenuSupport.Audience audience,
      PartyAuthorization.Grant grant,
      State state,
      boolean enabled,
      Autopay.Mode mode) {
    var product = Objects.requireNonNull(state.selected);
    if (enabled
        && (!product.available()
            || state.funding == null
            || product.kind() == Kind.CARD && mode == Autopay.Mode.LOAN_DUE
            || product.kind() == Kind.LOAN && mode != Autopay.Mode.LOAN_DUE))
      throw new IllegalArgumentException("Payment target unavailable");
    var owner = ((PartyRef.Player) grant.target().owner()).id();
    state.preview =
        new Autopay.Configure(
            grant.target().world(),
            owner,
            product.kind(),
            product.id(),
            state.settings == null ? 0 : state.settings.revision(),
            product.revision(),
            enabled,
            enabled ? state.funding.id() : null,
            enabled ? state.funding.revision() : 0,
            mode);
    state.expiresAt =
        Math.addExact(BankingController.services(audience.player()).financialMillis(), 30_000);
    var lines = new ArrayList<String>();
    lines.add(product.label());
    if (enabled) {
      lines.add(UiText.t("ui.finance.autopaymenus.30", accountName(state.funding)));
      lines.add(UiText.t("ui.finance.autopaymenus.31", modeName(mode)));
      lines.add(targetDescription(mode));
      lines.add(UiText.t("ui.finance.autopaymenus.32"));
      lines.add(UiText.t("ui.finance.autopaymenus.33"));
      lines.add(UiText.t("ui.finance.autopaymenus.34"));
      lines.add(UiText.t("ui.finance.autopaymenus.35"));
      lines.add(UiText.t("ui.finance.autopaymenus.36"));
      lines.add(UiText.t("ui.finance.autopaymenus.37"));
      lines.add(UiText.t("ui.finance.autopaymenus.38"));
      lines.add(UiText.t("ui.finance.autopaymenus.39"));
    } else {
      lines.add(UiText.t("ui.finance.autopaymenus.40"));
      lines.add(UiText.t("ui.finance.autopaymenus.41"));
    }
    lines.add(UiText.t("ui.finance.autopaymenus.42", Instant.ofEpochMilli(state.expiresAt)));
    audience.reply(
        PAGE,
        lines,
        UiText.t("ui.finance.autopaymenus.43"),
        List.of(
            choice(
                "auto_confirm",
                "",
                enabled
                    ? UiText.t("ui.finance.autopaymenus.44")
                    : UiText.t("ui.finance.autopaymenus.45")),
            choice("auto_home", "", UiText.t("ui.finance.autopaymenus.46"))));
    state.previewRevision = audience.menu().revision();
  }

  private static void confirm(
      FinanceMenuSupport.Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state) {
    requireOffered(audience, "auto_confirm", "");
    var request = state.preview;
    if (request == null
        || audience.menu().revision() != state.previewRevision
        || BankingController.services(audience.player()).financialMillis() >= state.expiresAt)
      throw new IllegalArgumentException("Review automatic-payment consent again");
    state.preview = null;
    FinanceMenuSupport.submit(
        audience,
        services,
        grant,
        Autopay.CONFIGURE,
        request.request(),
        (operation, attempt) ->
            services.ledger().configureAutopay(grant, operation, attempt, request));
  }

  private static Product card(CreditFacilities.Facility card) {
    return new Product(
        Kind.CARD,
        card.id(),
        card.currency(),
        card.revision(),
        card.status() == CreditFacilities.Status.ACTIVE
            && card.access() != CreditFacilities.Access.CLOSED,
        UiText.t(
            "ui.finance.card_label",
            InstitutionCatalog.require(card.issuer()).name(),
            money(card.currency(), card.owedMinor())));
  }

  private static Product loan(Loans.Contract loan) {
    return new Product(
        Kind.LOAN,
        loan.id(),
        loan.currency(),
        loan.revision(),
        loan.status() == Loans.Status.ACTIVE,
        UiText.t(
            "ui.finance.loan_label",
            InstitutionCatalog.require(loan.lender()).name(),
            money(loan.currency(), loan.owedMinor())));
  }

  private static String accountName(AccountIdentity account) {
    return UiText.t(
        account.primary() ? "ui.finance.primary_account" : "ui.finance.account",
        account.currency().code());
  }

  private static String money(EconomyCurrency currency, long minor) {
    return UiText.money(new Money(currency, minor));
  }

  private static String modeName(Autopay.Mode mode) {
    return switch (mode) {
      case MINIMUM -> UiText.t("ui.finance.mode.minimum");
      case STATEMENT -> UiText.t("ui.finance.mode.statement");
      case FULL_PAYOFF -> UiText.t("ui.finance.mode.full");
      case LOAN_DUE -> UiText.t("ui.finance.mode.loan");
    };
  }

  private static String targetDescription(Autopay.Mode mode) {
    return switch (mode) {
      case MINIMUM -> UiText.t("ui.finance.target.minimum");
      case STATEMENT -> UiText.t("ui.finance.target.statement");
      case FULL_PAYOFF -> UiText.t("ui.finance.target.full");
      case LOAN_DUE -> UiText.t("ui.finance.target.loan");
    };
  }

  private static BankingNetwork.Choice choice(String action, String value, String label) {
    return new BankingNetwork.Choice(action, value, label, false);
  }

  private static void clearSelection(State state) {
    state.selected = null;
    state.settings = null;
    state.accounts = Map.of();
    state.funding = null;
    state.preview = null;
  }

  private static void requireOffered(
      FinanceMenuSupport.Audience audience, String action, String value) {
    if (!FinanceMenuSupport.offered(audience, action, value))
      throw new IllegalArgumentException("Selection not offered");
  }

  private static void unavailable(FinanceMenuSupport.Audience audience) {
    audience.decline(
        PAGE,
        List.of(UiText.t("ui.finance.autopaymenus.48")),
        UiText.t("ui.finance.autopaymenus.47"),
        List.of(choice("auto_home", "", UiText.t("ui.finance.autopaymenus.20"))));
  }
}
