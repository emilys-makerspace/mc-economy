package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.account.AccountLifecycle;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.BankingController;
import ca.emilybelnavis.mcmods.economy.banking.BankingMenu;
import ca.emilybelnavis.mcmods.economy.banking.BankingNetwork;
import ca.emilybelnavis.mcmods.economy.banking.BankingOperation;
import ca.emilybelnavis.mcmods.economy.banking.BankingSaves;
import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.cash.CashCatalog;
import ca.emilybelnavis.mcmods.economy.fx.FxQuotes;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/**
 * Authenticated owner views and noncash banking actions. No transport field chooses the actor or
 * world.
 */
public final class BankingAccounts {
  private static final String PENDING = "economy_banking_pending";
  private static final Set<String> ACTIONS =
      Set.of(
          "setup",
          "accounts",
          "accounts_next",
          "manage_accounts",
          "account_select",
          "account_open",
          "account_close_preview",
          "account_close_confirm",
          "history",
          "history_next",
          "fx",
          "exchange_preview",
          "exchange_confirm",
          "settings",
          "kiosk_upgrade",
          "configure",
          "configure_confirm",
          "bank_retry",
          "branch_open",
          "branch_assign",
          "branch_switch",
          "branch_accept",
          "branch_close",
          "branch_products",
          "brand_preview",
          "brand_confirm",
          "cards",
          "card_select",
          "card_preview",
          "card_confirm",
          "card_lost",
          "credit_profile",
          "credit_history",
          "credit_next",
          "credit_facilities",
          "credit_facilities_next",
          "credit_facility",
          "credit_statements",
          "credit_statements_next");
  private static final Map<BankingMenu, State> STATES = new WeakHashMap<>();

  private static final class State {
    AccountId cardAccount;
    ca.emilybelnavis.mcmods.economy.card.IssuedCards.Token cardToken;
    ca.emilybelnavis.mcmods.economy.card.IssuedCards.FeeQuote cardFee;
    LedgerService.BranchContext cardBranch;
    long creditAfter;
    Long creditNext;
    UUID creditFacilityNext;
    UUID creditFacility;
    EconomyCurrency creditCurrency;
    Long creditStatementNext;
    long cardPreviewRevision;
    LedgerService.BranchContext branchView;
    String pendingBrand;
    long brandRevision;
    TerminalPolicy.Snapshot settingsView;
    TerminalSettingsReview settingsReview;
    AccountId selected;
    String accountCursor;
    String historyCursor;
    Map<UUID, AccountIdentity> offered = Map.of();
    Set<EconomyCurrency> openCurrencies = Set.of();
    TerminalPolicy.Snapshot fxTerminal;
    FxSettlement.Exchange exchange;
    FxQuotes.Quote quote;
    CashClosure.Instruction closure;
    long closureRevision;
    long closureTerminalRevision;
    Function<PartyAuthorization.Grant, JournalService.Submission> retry;
  }

  private record Audience(ServerPlayer player, BankingMenu menu, UUID session, long revision) {
    boolean live() {
      return menu.valid(player) && menu.session().equals(session) && menu.revision() == revision;
    }
  }

  private BankingAccounts() {}

  public static boolean handles(String action) {
    return ACTIONS.contains(action);
  }

  public static AccountId selected(BankingMenu menu) {
    var state = STATES.get(menu);
    return state == null ? null : state.selected;
  }

  public static void selectTerminalAccount(BankingMenu menu, AccountId id) {
    if (!BankingController.canChangeAccount(menu))
      throw new IllegalStateException("Resolve the current cash transaction first.");
    STATES.computeIfAbsent(menu, ignored -> new State()).selected = id;
  }

  public static void closed(BankingMenu menu) {
    STATES.remove(menu);
  }

  public static void action(ServerPlayer player, BankingMenu menu, String action, String value) {
    if (!handles(action) || !menu.valid(player) || menu.busy()) return;
    if (value == null || value.getBytes(StandardCharsets.UTF_8).length > 2048) return;
    var services = Economy.serverServices(player.server);
    if (services == null || services.state() != ServerServices.State.READY) return;
    var grant = grant(player, services);
    if (grant == null) return;
    var state = STATES.computeIfAbsent(menu, ignored -> new State());
    var audience = new Audience(player, menu, menu.session(), menu.revision());
    if (Set.of("setup", "account_open", "account_close_preview", "account_close_confirm")
            .contains(action)
        && audience.menu().access() != BankingMenu.Access.KIOSK) {
      reply(
          audience,
          menu.page(),
          List.of(
              UiText.t("ui.bank.account_opening_and_closure_require_a_bank_bra.a96252"),
              UiText.t("ui.bank.visit_the_account_institution_s_branded_kiosk.548bb1")),
          UiText.t("ui.bank.branch_service_unavailable.ecd8a5"));
      return;
    }
    try {
      switch (action) {
        case "credit_facilities", "credit_facilities_next" ->
            creditFacilities(
                audience, services, grant, state, action.equals("credit_facilities_next"));
        case "credit_facility" -> creditFacility(audience, services, grant, state, value);
        case "credit_statements", "credit_statements_next" ->
            creditStatements(
                audience, services, grant, state, action.equals("credit_statements_next"));
        case "credit_profile" -> creditProfile(audience, services, grant);
        case "credit_history", "credit_next" ->
            creditHistory(audience, services, grant, state, action.equals("credit_next"));
        case "cards" -> cards(audience, services, grant, state, "");
        case "card_select" -> {
          if (!offered(audience, action, value))
            throw new IllegalArgumentException("Card not offered");
          cards(audience, services, grant, state, value);
        }
        case "card_preview", "card_confirm", "card_lost" ->
            cardAction(audience, services, grant, state, action, value);
        case "setup" -> setup(audience, services, grant, state);
        case "accounts", "accounts_next" ->
            accounts(audience, services, grant, state, action.equals("accounts_next"));
        case "manage_accounts" -> {
          if (menu.access() == BankingMenu.Access.KIOSK)
            branchHome(audience, services, grant, state);
          else manage(audience, services, grant, state);
        }
        case "branch_open",
            "branch_assign",
            "branch_switch",
            "branch_accept",
            "branch_close",
            "branch_products" ->
            branchAction(audience, services, grant, state, action, value);
        case "brand_preview", "brand_confirm" ->
            brand(audience, services, grant, state, action, value);
        case "account_select" -> select(audience, services, grant, state, value);
        case "account_open" -> open(audience, services, grant, state, value);
        case "account_close_preview" -> closePreview(audience, services, grant, state, value);
        case "account_close_confirm" -> closeConfirm(audience, services, grant, state);
        case "history", "history_next" ->
            history(audience, services, grant, state, action.equals("history_next"));
        case "fx" -> fx(audience, services, grant, state);
        case "exchange_preview" -> exchangePreview(audience, services, grant, state, value);
        case "exchange_confirm" -> exchangeConfirm(audience, services, grant, state);
        case "kiosk_upgrade" -> {
          if (audience.menu().access() != BankingMenu.Access.ADMIN) {
            unavailable(audience, "settings");
            return;
          }
          audience.menu().setBusy(true);
          ca.emilybelnavis.mcmods.economy.terminal.KioskUpgradeController.request(
              audience.player(),
              new net.minecraft.core.BlockPos(
                  audience.menu().anchor().x(),
                  audience.menu().anchor().y(),
                  audience.menu().anchor().z()),
              message -> {
                if (audience.player().server.getPlayerList().getPlayer(audience.player().getUUID())
                    == audience.player())
                  audience
                      .player()
                      .displayClientMessage(UiText.component(audience.player(), message), false);
              });
          audience.player().closeContainer();
        }
        case "settings" -> settings(audience, services, state);
        case "configure" -> configure(audience, services, grant, state, value);
        case "configure_confirm" -> configureConfirm(audience, services, grant, state);
        case "bank_retry" -> retry(audience, services, grant, state);
        default -> {}
      }
    } catch (IllegalArgumentException | IllegalStateException | ArithmeticException invalid) {
      decline(
          audience,
          menu.page(),
          List.of(UiText.t("ui.bank.the_request_was_not_submitted_check_the_values.ab9e34")),
          UiText.t("ui.bank.invalid_banking_request.299654"));
    }
  }

  private static void creditProfile(
      Audience audience, ServerServices services, PartyAuthorization.Grant grant) {
    audience.menu().setBusy(true);
    services
        .ledger()
        .creditProfile(grant)
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              if (error != null || found.isEmpty()) {
                unavailable(audience, "credit_profile");
                return;
              }
              var view = found.orElseThrow();
              var lines = new ArrayList<String>();
              if (view.profile().isEmpty()) {
                lines.add(UiText.t("ui.bank.credit_is_not_established.295f70"));
                lines.add(
                    UiText.t("ui.bank.a_debit_card_login_or_failed_application_does_.410d32"));
                lines.add(
                    UiText.t("ui.bank.a_first_actual_credit_card_issuance_establishe.0d2196"));
              } else {
                var profile = view.profile().orElseThrow();
                lines.add(
                    UiText.t("ui.bank.saved_credit_score.0f65f3") + profile.score() + " (300–900)");
                lines.add(
                    UiText.t("ui.bank.first_credit_issuance.8ef672")
                        + java.time.Instant.ofEpochMilli(profile.firstIssuedAt()));
                view.latest()
                    .ifPresent(
                        history -> {
                          var c = history.components();
                          lines.add(
                              UiText.t("ui.bank.baseline.63e8dd")
                                  + c.baseline()
                                  + UiText.t("ui.bank.5e1bd6cc79")
                                  + signed(c.payment()));
                          lines.add(
                              UiText.t("ui.bank.utilization.0c883c")
                                  + signed(c.utilization())
                                  + UiText.t("ui.bank.8177d78cda")
                                  + signed(c.age()));
                          lines.add(
                              UiText.t("ui.bank.inquiries.aa5930")
                                  + c.inquiry()
                                  + "; defaults: -"
                                  + c.defaults());
                          lines.add(
                              UiText.t("ui.bank.recorded_at.0a1781")
                                  + java.time.Instant.ofEpochMilli(history.boundary()));
                        });
              }
              lines.add(UiText.t("ui.bank.scheduled_score_updates_await_the_reporting_se.8729f7"));
              reply(
                  audience,
                  "credit_profile",
                  lines,
                  UiText.t("ui.bank.credit_profile.a973eb"),
                  List.of(
                      new BankingNetwork.Choice(
                          "credit_history", "", UiText.t("ui.bank.score_history.db246b"), false),
                      new BankingNetwork.Choice(
                          "credit_facilities",
                          "",
                          UiText.t("ui.bank.credit_facilities.bf6b7f"),
                          false)));
            });
  }

  private static String signed(int value) {
    return (value >= 0 ? "+" : "") + value;
  }

  private static void creditHistory(
      Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      boolean next) {
    if (next && (state.creditNext == null || !offered(audience, "credit_next", "")))
      throw new IllegalArgumentException("No credit history page offered");
    state.creditAfter = next ? state.creditNext : 0;
    audience.menu().setBusy(true);
    services
        .ledger()
        .creditHistory(grant, state.creditAfter, 8)
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              if (error != null || found.isEmpty()) {
                unavailable(audience, "credit_history");
                return;
              }
              var page = found.orElseThrow();
              state.creditNext = page.nextCursor();
              var lines = new ArrayList<String>();
              for (var entry : page.entries()) {
                lines.add(
                    java.time.Instant.ofEpochMilli(entry.boundary())
                        + UiText.t("ui.bank.score.2011c7")
                        + entry.score());
                var c = entry.components();
                lines.add(
                    UiText.t("ui.bank.payment.68dc81")
                        + signed(c.payment())
                        + UiText.t("ui.bank.utilization.e9789c")
                        + signed(c.utilization())
                        + UiText.t("ui.bank.age.678f7b")
                        + signed(c.age())
                        + UiText.t("ui.bank.inquiries.ccd0e0")
                        + c.inquiry()
                        + UiText.t("ui.bank.defaults.ed3d5a")
                        + c.defaults());
              }
              if (lines.isEmpty())
                lines.add(
                    UiText.t("ui.bank.no_credit_history_debit_issuance_does_not_esta.faa985"));
              var choices = new ArrayList<BankingNetwork.Choice>();
              choices.add(
                  new BankingNetwork.Choice(
                      "credit_profile", "", UiText.t("ui.bank.back_to_profile.ca6ecc"), false));
              if (state.creditNext != null)
                choices.add(
                    new BankingNetwork.Choice(
                        "credit_next", "", UiText.t("ui.bank.next_history_page.11cf83"), false));
              reply(
                  audience,
                  "credit_history",
                  lines,
                  UiText.t("ui.bank.saved_credit_history.e605a4"),
                  choices);
            });
  }

  private static void creditFacilities(
      Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      boolean next) {
    if (next
        && (state.creditFacilityNext == null || !offered(audience, "credit_facilities_next", "")))
      throw new IllegalArgumentException("No facility page offered");
    UUID after = next ? state.creditFacilityNext : null;
    audience.menu().setBusy(true);
    services
        .ledger()
        .creditFacilityPage(grant, after, 8)
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              if (error != null || found.isEmpty()) {
                unavailable(audience, "credit_facilities");
                return;
              }
              var page = found.orElseThrow();
              state.creditFacilityNext = page.nextCursor();
              var choices = new ArrayList<BankingNetwork.Choice>();
              for (var view : page.entries()) {
                var bank =
                    InstitutionCatalog.require(new InstitutionCatalog.InstitutionId(view.issuer()));
                choices.add(
                    new BankingNetwork.Choice(
                        "credit_facility",
                        view.id().toString(),
                        bank.name() + " · " + view.network() + " · " + view.currency().code(),
                        false));
              }
              if (state.creditFacilityNext != null)
                choices.add(
                    new BankingNetwork.Choice(
                        "credit_facilities_next",
                        "",
                        UiText.t("ui.bank.next_facilities.a66ebf"),
                        false));
              reply(
                  audience,
                  "credit_facilities",
                  List.of(
                      choices.isEmpty()
                          ? UiText.t("ui.bank.you_have_no_credit_facilities.2ad383")
                          : UiText.t(
                              "ui.bank.select_a_facility_to_view_balances_and_stateme.42f9b7")),
                  UiText.t("ui.bank.credit_facilities.bf6b7f"),
                  choices);
            });
  }

  private static void creditFacility(
      Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      String value) {
    if (!offered(audience, "credit_facility", value))
      throw new IllegalArgumentException("Facility not offered");
    var id = UUID.fromString(value);
    audience.menu().setBusy(true);
    services
        .ledger()
        .creditFacilityView(grant, id)
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              if (error != null || found.isEmpty()) {
                unavailable(audience, "credit_facility");
                return;
              }
              state.creditFacility = id;
              state.creditCurrency = found.orElseThrow().currency();
              state.creditStatementNext = null;
              reply(
                  audience,
                  "credit_facility",
                  CreditReport.owner(found.orElseThrow()),
                  UiText.t("ui.bank.credit_facility.09a380"),
                  List.of(
                      new BankingNetwork.Choice(
                          "credit_facilities",
                          "",
                          UiText.t("ui.bank.back_to_facilities.c87eb8"),
                          false),
                      new BankingNetwork.Choice(
                          "credit_statements", "", UiText.t("ui.bank.statements.5653ce"), false)));
            });
  }

  private static void creditStatements(
      Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      boolean next) {
    if (state.creditFacility == null
        || !offered(audience, next ? "credit_statements_next" : "credit_statements", ""))
      throw new IllegalArgumentException("Statement page not offered");
    if (next && state.creditStatementNext == null)
      throw new IllegalArgumentException("No statement page offered");
    long after = next ? state.creditStatementNext : 0;
    audience.menu().setBusy(true);
    services
        .ledger()
        .creditStatements(grant, state.creditFacility, after, 2)
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              if (error != null || found.isEmpty()) {
                unavailable(audience, "credit_statements");
                return;
              }
              var page = found.orElseThrow();
              state.creditStatementNext = page.nextClosedAt();
              var lines = new ArrayList<String>();
              for (var statement : page.statements())
                lines.addAll(CreditReport.statement(statement, state.creditCurrency));
              if (lines.isEmpty())
                lines.add(UiText.t("ui.bank.no_statements_have_closed_for_this_facility.129ab9"));
              var choices = new ArrayList<BankingNetwork.Choice>();
              choices.add(
                  new BankingNetwork.Choice(
                      "credit_facility",
                      state.creditFacility.toString(),
                      UiText.t("ui.bank.back_to_facility.17429a"),
                      false));
              if (state.creditStatementNext != null)
                choices.add(
                    new BankingNetwork.Choice(
                        "credit_statements_next",
                        "",
                        UiText.t("ui.bank.next_statements.5030fd"),
                        false));
              reply(
                  audience,
                  "credit_statements",
                  lines,
                  UiText.t("ui.bank.frozen_statements.27414c"),
                  choices);
            });
  }

  private static void cards(
      Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      String selected) {
    audience.menu().setBusy(true);
    state.cardFee = null;
    state.cardBranch = null;
    services
        .readAccounts(
            grant,
            false,
            reads ->
                Optional.of(
                    TerminalMenus.bankAccounts(
                        reads,
                        grant.target(),
                        TerminalMenus.bank(
                            reads, grant, audience.menu(), services.financialMillis()))))
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              if (error != null || found.isEmpty()) {
                unavailable(audience, "cards");
                return;
              }
              state.offered = offeredAccounts(found.orElseThrow());
              if (state.offered.isEmpty()) {
                reply(
                    audience,
                    "cards",
                    List.of(
                        UiText.t("ui.bank.open_and_assign_an_account_at_its_bank_kiosk_f.79b3c2")),
                    UiText.t("ui.bank.no_issued_debit_account.b1983d"),
                    List.of());
                return;
              }
              var id =
                  selected.isBlank()
                      ? state.offered.keySet().iterator().next()
                      : UUID.fromString(selected);
              var account = state.offered.get(id);
              if (account == null) {
                unavailable(audience, "cards");
                return;
              }
              state.cardAccount = account.id();
              services
                  .ledger()
                  .branchContext(grant, audience.menu().anchor(), account.id())
                  .thenCombine(services.ledger().accountCard(grant, account.id()), Map::entry)
                  .whenComplete(
                      (result, failure) -> {
                        if (!audience.live()) return;
                        if (failure != null || result.getKey().isEmpty()) {
                          unavailable(audience, "cards");
                          return;
                        }
                        var view = result.getKey().orElseThrow();
                        var provider = view.provider();
                        var token =
                            result
                                .getValue()
                                .filter(
                                    t ->
                                        provider != null
                                            && t.providerEpoch() == provider.debitEpoch()
                                            && t.issuer().key().equals(provider.bankKey()))
                                .orElse(null);
                        state.cardToken = token;
                        var choices = new ArrayList<BankingNetwork.Choice>();
                        for (var a : state.offered.values())
                          choices.add(
                              new BankingNetwork.Choice(
                                  "card_select",
                                  a.id().value().toString(),
                                  a.currency().code()
                                      + (a.primary()
                                          ? UiText.t("ui.bank.primary.4d9273")
                                          : UiText.t("ui.bank.account.6f9a89")),
                                  a.id().equals(account.id())));
                        var lines = new ArrayList<String>();
                        lines.add(
                            UiText.t("ui.bank.debit_card_for.73be22")
                                + account.currency().code()
                                + UiText.t("ui.bank.account.6f9a89"));
                        if (provider == null
                            || provider.legacy()
                                && provider.bankKey().equals(BankProviders.UNASSIGNED_PROVIDER))
                          lines.set(
                              0,
                              UiText.t(
                                  "ui.bank.unassigned_account_visit_a_bank_kiosk_before_i.c15e45"));
                        else {
                          lines.add(
                              InstitutionCatalog.require(
                                      new InstitutionCatalog.InstitutionId(provider.bankKey()))
                                  .name());
                          if (token != null) {
                            lines.add(
                                UiText.t("ui.bank.status.f8c824")
                                    + (BankingController.services(audience.player())
                                                .financialMillis()
                                            >= token.expiresAt()
                                        ? display("EXPIRED")
                                        : display(token.status())));
                            lines.add(
                                UiText.t("ui.bank.expires_utc.7c2031")
                                    + java.time.Instant.ofEpochMilli(token.expiresAt()));
                            if (token.status()
                                    == ca.emilybelnavis.mcmods.economy.card.IssuedCards.Status
                                        .ACTIVE
                                && BankingController.services(audience.player()).financialMillis()
                                    < token.expiresAt())
                              choices.add(
                                  new BankingNetwork.Choice(
                                      "card_lost",
                                      account.id().value().toString(),
                                      UiText.t("ui.bank.report_card_lost_free.124722"),
                                      false));
                          } else
                            lines.add(
                                UiText.t(
                                    "ui.bank.first_debit_card_free_no_credit_or_score_is_cr.04d8e7"));
                          String issuerName =
                              InstitutionCatalog.require(
                                      new InstitutionCatalog.InstitutionId(provider.bankKey()))
                                  .name();
                          lines.set(
                              0,
                              token == null
                                  ? issuerName + UiText.t("ui.bank.first_debit_card_free.337b7b")
                                  : issuerName
                                      + " — "
                                      + ca.emilybelnavis.mcmods.economy.card.IssuedCards
                                          .effectiveStatus(
                                              token,
                                              BankingController.services(audience.player())
                                                  .financialMillis())
                                      + " — "
                                      + java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
                                          .withZone(java.time.ZoneOffset.UTC)
                                          .format(
                                              java.time.Instant.ofEpochMilli(token.expiresAt())));
                          boolean issuerKiosk =
                              audience.menu().access() == BankingMenu.Access.KIOSK
                                  && view.terminal() != null
                                  && view.terminal().binding() != null
                                  && view.terminal().binding().bound()
                                  && view.terminal()
                                      .binding()
                                      .institution()
                                      .key()
                                      .equals(provider.bankKey());
                          if (issuerKiosk)
                            choices.add(
                                new BankingNetwork.Choice(
                                    "card_preview",
                                    account.id().value().toString(),
                                    token == null
                                        ? UiText.t("ui.bank.review_free_issuance.e5773f")
                                        : UiText.t("ui.bank.review_replacement_renewal.05cafb"),
                                    false));
                          else
                            lines.add(
                                UiText.t(
                                    "ui.bank.issuance_and_replacement_require_this_issuer_s.281182"));
                        }
                        lines.add(
                            UiText.t(
                                "ui.bank.use_collect_items_for_committed_card_deliverie.e451bc"));
                        choices.add(
                            new BankingNetwork.Choice(
                                "claims",
                                "",
                                UiText.t("ui.bank.collect_issued_cards_protected_items.a6dc94"),
                                false));
                        reply(
                            audience,
                            "cards",
                            lines,
                            UiText.t("ui.bank.issued_debit_cards.366689"),
                            choices);
                      });
            });
  }

  private static void cardAction(
      Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      String action,
      String value) {
    if (!offered(audience, action, value) || state.cardAccount == null)
      throw new IllegalArgumentException("Choose a server-offered card action");
    if (action.equals("card_lost")) {
      var token = state.cardToken;
      if (token == null || !value.equals(state.cardAccount.value().toString()))
        throw new IllegalArgumentException("Card selection stale");
      var operation = new OperationId(UUID.randomUUID());
      var attempt = new RequestId(UUID.randomUUID());
      var request =
          ca.emilybelnavis.mcmods.economy.card.IssuedCards.revokeRequest(
              token.id(), token.revision(), new PlayerId(audience.player().getUUID()));
      start(
          audience,
          services,
          grant,
          state,
          new Journal.Intent(
              operation,
              attempt,
              grant.actor(),
              ca.emilybelnavis.mcmods.economy.card.IssuedCards.REVOKE,
              request,
              null,
              null),
          fresh ->
              services.ledger().revokeCard(fresh, operation, attempt, token.id(), token.revision()),
          "cards");
      return;
    }
    if (audience.menu().access() != BankingMenu.Access.KIOSK)
      throw new IllegalArgumentException("Issuer kiosk required");
    if (action.equals("card_confirm")) {
      if (!audience.menu().page().equals("card_review")
          || audience.menu().revision() != state.cardPreviewRevision
          || state.cardBranch == null) throw new IllegalArgumentException("Review card first");
      var view = state.cardBranch;
      var old = state.cardToken;
      var lease =
          proof(
              audience,
              grant,
              view,
              old == null ? BranchAdmission.Action.CARD_ISSUE : BranchAdmission.Action.CARD_REPLACE,
              null);
      var request =
          new ca.emilybelnavis.mcmods.economy.card.IssuedCards.Request(
              lease, view.tier().revision(), old == null ? null : old.id(), state.cardFee);
      var operation = new OperationId(UUID.randomUUID());
      var attempt = new RequestId(UUID.randomUUID());
      start(
          audience,
          services,
          grant,
          state,
          new Journal.Intent(
              operation,
              attempt,
              grant.actor(),
              ca.emilybelnavis.mcmods.economy.card.IssuedCards.ISSUE,
              request.request(),
              null,
              null),
          fresh -> services.ledger().issueCard(fresh, operation, attempt, request),
          "cards");
      state.cardBranch = null;
      state.cardFee = null;
      return;
    }
    String[] parts = value.split("\\|", -1);
    if (!parts[0].equals(state.cardAccount.value().toString()))
      throw new IllegalArgumentException("Card selection changed");
    var old = state.cardToken;
    boolean paid =
        old != null
            && !ca.emilybelnavis.mcmods.economy.card.IssuedCards.freeRenewal(
                old, BankingController.services(audience.player()).financialMillis());
    if (paid && parts.length == 1) {
      var choices =
          state.offered.values().stream()
              .map(
                  a ->
                      new BankingNetwork.Choice(
                          "card_preview",
                          value + "|" + a.id().value(),
                          UiText.t("ui.bank.pay_from.bc4706")
                              + a.currency().code()
                              + UiText.t("ui.bank.account.6f9a89"),
                          false))
              .toList();
      reply(
          audience,
          "card_funding",
          List.of(
              UiText.t("ui.bank.replacement_costs_25_in_the_world_s_currency.5b16d6"),
              UiText.t("ui.bank.choose_a_funding_account_then_review_the_combi.94dd8d")),
          UiText.t("ui.bank.choose_funding_account.4990d2"),
          choices);
      return;
    }
    audience.menu().setBusy(true);
    var funding = paid ? new AccountId(UUID.fromString(parts[1])) : null;
    if (paid && !state.offered.containsKey(funding.value()))
      throw new IllegalArgumentException("Funding not offered");
    services
        .ledger()
        .branchContext(grant, audience.menu().anchor(), state.cardAccount)
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              if (error != null || found.isEmpty()) {
                unavailable(audience, "cards");
                return;
              }
              var view = found.orElseThrow();
              if (view.provider() == null
                  || view.terminal() == null
                  || view.terminal().binding() == null
                  || !view.terminal().binding().bound()
                  || !view.provider()
                      .bankKey()
                      .equals(view.terminal().binding().institution().key())) {
                reply(
                    audience,
                    "cards",
                    List.of(UiText.t("ui.bank.visit_the_card_account_s_issuing_bank_kiosk.698d7b")),
                    UiText.t("ui.bank.wrong_issuer_kiosk.5efdfa"),
                    List.of());
                return;
              }
              java.util.concurrent.CompletionStage<
                      ca.emilybelnavis.mcmods.economy.card.IssuedCards.FeeQuote>
                  quoted =
                      paid
                          ? services
                              .ledger()
                              .quoteCardReplacement(grant, state.cardAccount, funding)
                          : java.util.concurrent.CompletableFuture.completedFuture(null);
              quoted.whenComplete(
                  (fee, failure) -> {
                    if (!audience.live()) return;
                    if (failure != null) {
                      reply(
                          audience,
                          "cards",
                          List.of(
                              UiText.t(
                                  "ui.bank.a_fresh_fee_preview_is_unavailable_no_card_was.e20e86")),
                          UiText.t("ui.bank.fee_quote_unavailable.54b645"),
                          List.of());
                      return;
                    }
                    state.cardBranch = view;
                    state.cardFee = fee;
                    var lines = new ArrayList<String>();
                    lines.add(
                        old == null
                            ? UiText.t(
                                "ui.bank.issue_the_first_debit_token_for_this_account.34b558")
                            : UiText.t(
                                "ui.bank.replace_the_current_token_and_revoke_all_its_c.46fd23"));
                    lines.add(
                        UiText.t("ui.bank.issuer.25666b")
                            + InstitutionCatalog.require(view.terminal().binding().institution())
                                .name());
                    lines.add(
                        fee == null
                            ? UiText.t("ui.bank.fee_free.858439")
                            : UiText.t("ui.bank.combined_account_debit.9f317c")
                                + money(new Money(fee.currency(), fee.debitMinor())));
                    if (fee != null) {
                      var amounts = fee.capture().split("\\|", -1);
                      lines.add(
                          UiText.t("ui.bank.replacement_principal.da035b")
                              + money(new Money(fee.currency(), Long.parseLong(amounts[4]))));
                      lines.add(
                          UiText.t("ui.bank.fx_fee.a7b895")
                              + money(new Money(fee.currency(), Long.parseLong(amounts[5]))));
                    }
                    lines.add(
                        UiText.t("ui.bank.expiry_five_financial_years_from_successful_is.128bef"));
                    lines.add(UiText.t("ui.bank.b3d4e43b26"));
                    reply(
                        audience,
                        "card_review",
                        lines,
                        UiText.t("ui.bank.confirm_debit_card.52f9a8"),
                        List.of(
                            new BankingNetwork.Choice(
                                "card_confirm",
                                "",
                                UiText.t("ui.bank.confirm_issuance.e24f3e"),
                                false)));
                    state.cardPreviewRevision = audience.menu().revision();
                  });
            });
  }

  private static void branchHome(
      Audience audience, ServerServices services, PartyAuthorization.Grant grant, State state) {
    audience.menu().setBusy(true);
    services
        .ledger()
        .branchContext(grant, audience.menu().anchor(), null)
        .thenCombine(ownerAccounts(services, grant), Map::entry)
        .whenComplete(
            (result, error) -> {
              if (!audience.live()) return;
              if (error != null || result.getKey().isEmpty() || result.getValue().isEmpty()) {
                if (error != null)
                  com.mojang.logging.LogUtils.getLogger()
                      .warn(
                          "event=BANKING_BRANCH_READ_FAILED session={} reason={}",
                          audience.session(),
                          ServerServices.failureCode(error));
                unavailable(audience, "branch");
                return;
              }
              var context = result.getKey().orElseThrow();
              var terminal = context.terminal();
              if (terminal == null || terminal.binding() == null || !terminal.binding().bound()) {
                reply(
                    audience,
                    "branch",
                    List.of(
                        UiText.t("ui.bank.this_kiosk_has_no_bank_binding.7b9080"),
                        UiText.t("ui.bank.owner_sneak_right_click_with_an_empty_hand_to_.2fa93f")),
                    UiText.t("ui.bank.bank_selection_required.bb007a"),
                    List.of());
                return;
              }
              var bank = InstitutionCatalog.require(terminal.binding().institution());
              state.offered = offeredAccounts(result.getValue().orElseThrow().accounts());
              var choices = new ArrayList<BankingNetwork.Choice>();
              choices.add(
                  new BankingNetwork.Choice(
                      "branch_open",
                      "",
                      context.primary() == null
                          ? UiText.t("ui.bank.open_primary_account.77c8a5")
                          : UiText.t("ui.bank.open.79d37e")
                              + bank.baseCurrency().code()
                              + UiText.t("ui.bank.account.6f9a89"),
                      false));
              for (var account : state.offered.values()) {
                String id = account.id().value().toString();
                String label =
                    account.currency().code()
                        + (account.primary()
                            ? UiText.t("ui.bank.primary.4d9273")
                            : UiText.t("ui.bank.account.6f9a89"));
                choices.add(
                    new BankingNetwork.Choice(
                        "branch_assign",
                        id,
                        UiText.t("ui.bank.assign.f1e181") + label + UiText.t("ui.bank.here.658ef1"),
                        false));
                choices.add(
                    new BankingNetwork.Choice(
                        "branch_switch",
                        id,
                        UiText.t("ui.bank.switch_provider.20489a") + label,
                        false));
                choices.add(
                    new BankingNetwork.Choice(
                        "branch_accept",
                        id,
                        UiText.t("ui.bank.confirm_switch_here.30c477") + label,
                        false));
                if (!account.primary())
                  choices.add(
                      new BankingNetwork.Choice(
                          "branch_close", id, UiText.t("ui.bank.close.0ed4eb") + label, false));
              }
              choices.add(
                  new BankingNetwork.Choice(
                      "cards", "", UiText.t("ui.bank.issued_debit_cards.366689"), false));
              choices.add(
                  new BankingNetwork.Choice(
                      "credit_profile", "", UiText.t("ui.bank.credit_profile.a973eb"), false));
              choices.add(
                  new BankingNetwork.Choice(
                      "credit_facilities",
                      "",
                      UiText.t("ui.bank.credit_facilities.bf6b7f"),
                      false));
              choices.add(
                  new BankingNetwork.Choice(
                      "finance_home",
                      "",
                      UiText.t("ui.bank.bank_and_credit_services.c9cdbc"),
                      false));
              choices.add(
                  new BankingNetwork.Choice(
                      "branch_products",
                      "",
                      UiText.t("ui.bank.cards_and_applications.9a6881"),
                      false));
              reply(
                  audience,
                  "branch",
                  List.of(
                      bank.name() + " — " + bank.baseCurrency().code(),
                      UiText.t("ui.bank.four_open_accounts_total_including_primary.838b03"),
                      UiText.t("ui.bank.assignment_and_closure_require_the_account_s_b.92bca9")),
                  UiText.t("ui.bank.branch_services.f326c8"),
                  choices);
            });
  }

  private static boolean offered(Audience audience, String action, String value) {
    return audience.menu().choices().stream()
        .anyMatch(c -> c.action().equals(action) && c.value().equals(value));
  }

  private static BranchAdmission.Proof proof(
      Audience audience,
      PartyAuthorization.Grant grant,
      LedgerService.BranchContext view,
      BranchAdmission.Action action,
      InstitutionCatalog.InstitutionId target) {
    var terminal = view.terminal();
    if (terminal == null
        || terminal.binding() == null
        || !terminal.binding().bound()
        || view.denomination() == null) throw new IllegalArgumentException("BRANCH_UNAVAILABLE");
    var account = action == BranchAdmission.Action.SECONDARY ? view.primary() : view.account();
    var context =
        new BranchAdmission.Context(
            grant.target().world(),
            audience.menu().session(),
            new PlayerId(audience.player().getUUID()),
            action,
            terminal.binding().institution(),
            target,
            account == null ? null : account.id(),
            account == null ? 0 : account.revision(),
            view.provider() == null ? 0 : view.provider().revision(),
            view.denomination().revision());
    return KioskAdmission.issue(audience.player(), audience.menu(), terminal, context);
  }

  private static void branchAction(
      Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      String action,
      String value) {
    if (audience.menu().access() != BankingMenu.Access.KIOSK || !offered(audience, action, value))
      throw new IllegalArgumentException("Choose an offered branch action");
    if (action.equals("branch_products")) {
      reply(
          audience,
          "branch_products",
          List.of(
              UiText.t("ui.bank.issuer_applications_and_card_services_require_.32e657"),
              UiText.t("ui.bank.review_current_terms_before_confirming_trading.44dfaa")),
          UiText.t("ui.bank.branch_services.f326c8"),
          List.of(
              new BankingNetwork.Choice(
                  "cards", "", UiText.t("ui.bank.issued_debit_cards.366689"), false),
              new BankingNetwork.Choice(
                  "finance_credit_apply", "", UiText.t("ui.bank.credit_application.41a360"), false),
              new BankingNetwork.Choice(
                  "finance_credit_cards",
                  "",
                  UiText.t("ui.bank.credit_tokens_and_replacement.5a0324"),
                  false),
              new BankingNetwork.Choice(
                  "finance_credit_raise",
                  "",
                  UiText.t("ui.bank.credit_limit_increase.7e1b58"),
                  false),
              new BankingNetwork.Choice(
                  "finance_loan_apply", "", UiText.t("ui.bank.loan_application.ff2850"), false),
              new BankingNetwork.Choice(
                  "finance_home", "", UiText.t("ui.bank.all_financial_services.56fd6c"), false)));
      return;
    }
    String[] fields = value.split("\\|", -1);
    AccountId selected =
        action.equals("branch_open") ? null : new AccountId(UUID.fromString(fields[0]));
    // Current offered action and branchContext independently bind this owner and account.
    audience.menu().setBusy(true);
    services
        .ledger()
        .branchContext(grant, audience.menu().anchor(), selected)
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              if (error != null || found.isEmpty()) {
                unavailable(audience, "branch");
                return;
              }
              try {
                var view = found.orElseThrow();
                var terminal = view.terminal();
                if (terminal == null || terminal.binding() == null || !terminal.binding().bound())
                  throw new IllegalArgumentException("BRANCH_UNAVAILABLE");
                var bank = InstitutionCatalog.require(terminal.binding().institution());
                if (action.equals("branch_close")) {
                  if (view.provider() == null || !view.provider().bankKey().equals(bank.id().key()))
                    throw new IllegalArgumentException("Wrong account bank");
                  state.branchView = view;
                  state.offered = Map.of(view.account().id().value(), view.account());
                  audience.menu().setBusy(false);
                  closePreview(audience, services, grant, state, value);
                  return;
                }
                if (action.equals("branch_switch") && fields.length == 1) {
                  if (view.account() == null
                      || view.provider() == null
                      || !view.provider().bankKey().equals(bank.id().key()))
                    throw new IllegalArgumentException("Visit the source bank");
                  var choices =
                      InstitutionCatalog.banks().stream()
                          .filter(
                              b ->
                                  b.baseCurrency() == view.account().currency()
                                      && !b.id().equals(bank.id())
                                      && (!view.account().primary() || b.tier1()))
                          .map(
                              b ->
                                  new BankingNetwork.Choice(
                                      "branch_switch",
                                      value + "|" + b.id().key(),
                                      UiText.t("ui.bank.switch_to.16f98e") + b.name(),
                                      false))
                          .toList();
                  reply(
                      audience,
                      "branch_targets",
                      List.of(
                          UiText.t(
                              "ui.bank.authorize_one_target_bank_then_visit_that_targ.e51d98")),
                      UiText.t("ui.bank.choose_target_bank.12c2be"),
                      choices);
                  return;
                }
                var branchAction =
                    switch (action) {
                      case "branch_open" ->
                          view.primary() == null
                              ? BranchAdmission.Action.PRIMARY
                              : BranchAdmission.Action.SECONDARY;
                      case "branch_assign" -> BranchAdmission.Action.ASSIGN;
                      case "branch_accept" -> BranchAdmission.Action.SWITCH_TARGET;
                      case "branch_switch" -> BranchAdmission.Action.SWITCH_SOURCE;
                      default -> throw new IllegalArgumentException("Branch action");
                    };
                var target =
                    fields.length == 2 ? new InstitutionCatalog.InstitutionId(fields[1]) : null;
                var lease = proof(audience, grant, view, branchAction, target);
                var request = new BankProviders.Request(lease, view.tier().revision());
                var kind =
                    switch (branchAction) {
                      case PRIMARY -> BankProviders.PRIMARY;
                      case SECONDARY -> BankProviders.SECONDARY;
                      case ASSIGN -> BankProviders.ASSIGN;
                      case SWITCH_SOURCE -> BankProviders.SWITCH_SOURCE;
                      case SWITCH_TARGET -> BankProviders.SWITCH_TARGET;
                      default -> throw new IllegalArgumentException();
                    };
                var operation = new OperationId(UUID.randomUUID());
                var attempt = new RequestId(UUID.randomUUID());
                var intent =
                    new Journal.Intent(
                        operation, attempt, grant.actor(), kind, request.request(), null, null);
                start(
                    audience,
                    services,
                    grant,
                    state,
                    intent,
                    fresh -> services.bankLifecycle(fresh, operation, attempt, request, null),
                    "branch");
              } catch (IllegalArgumentException | IllegalStateException invalid) {
                reply(
                    audience,
                    "branch",
                    List.of(
                        UiText.t("ui.bank.visit_the_correct_bank_and_refresh_the_account.92cc6f")),
                    UiText.t("ui.bank.branch_admission_unavailable.8a32d7"),
                    List.of());
              }
            });
  }

  private static PartyAuthorization.Grant grant(ServerPlayer player, ServerServices services) {
    var world = services.partyWorld();
    if (world == null) return null;
    var target = new PartyRef(world, new PartyRef.Player(new PlayerId(player.getUUID())));
    return PartyAuthorization.player(player, Economy::serverServices, target).orElse(null);
  }

  private static void setup(
      Audience audience, ServerServices services, PartyAuthorization.Grant grant, State state) {
    if (!audience.menu().page().equals("accounts")
        || audience.menu().choices().stream().noneMatch(choice -> choice.action().equals("setup")))
      throw new IllegalArgumentException("Open account from My accounts");
    var request = new Journal.CanonicalRequest(1, Map.of());
    var op = new OperationId(UUID.randomUUID());
    var attempt = new RequestId(UUID.randomUUID());
    var kind = ca.emilybelnavis.mcmods.economy.account.AccountCatalog.INITIALIZE_PRIMARY;
    start(
        audience,
        services,
        grant,
        state,
        ownedIntent(grant, kind, op, attempt, request),
        fresh -> services.initializePrimary(fresh, op, attempt, null),
        "accounts");
  }

  private record OwnerAccounts(
      List<AccountIdentity> accounts,
      EconomyCurrency worldCurrency,
      Set<EconomyCurrency> availableCurrencies) {}

  /** Query open identities directly so retained closed history never hides an active account. */
  private static java.util.concurrent.CompletionStage<Optional<OwnerAccounts>> ownerAccounts(
      ServerServices services, PartyAuthorization.Grant grant) {
    return services.readAccounts(
        grant,
        false,
        reads -> {
          var player = (PartyRef.Player) grant.target().owner();
          var ids =
              reads.query(
                  "SELECT account_id FROM account_catalog WHERE owner_id=? AND purpose='BANK' AND status='OPEN' ORDER BY account_id LIMIT 4",
                  List.of(player.id().value().toString()),
                  4);
          var accounts =
              ids.stream()
                  .map(
                      row ->
                          AccountCatalog.find(
                                  reads,
                                  grant.target(),
                                  new AccountId(UUID.fromString(row.get("account_id"))))
                              .orElseThrow())
                  .toList();
          var denomination =
              RebaseSettlement.denomination(reads).map(value -> value.currency()).orElse(null);
          return Optional.of(
              new OwnerAccounts(
                  accounts,
                  denomination,
                  Set.copyOf(
                      ca.emilybelnavis.mcmods.economy.fx.FxService.availableCurrencies(reads))));
        });
  }

  private static void accounts(
      Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      boolean next) {
    audience.menu().setBusy(true);
    state.openCurrencies = Set.of();
    state.closure = null;
    ownerAccounts(services, grant)
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              if (error != null || found.isEmpty()) {
                unavailable(audience, "accounts");
                return;
              }
              state.offered = offeredAccounts(found.orElseThrow().accounts());
              state.accountCursor = null;
              var choices = accountChoices(state, "history");
              if (choices.isEmpty() && audience.menu().access() == BankingMenu.Access.KIOSK)
                choices.add(
                    new BankingNetwork.Choice(
                        "manage_accounts",
                        "",
                        UiText.t("ui.bank.visit_branch_services.d0b87b"),
                        false));
              else
                choices.add(
                    new BankingNetwork.Choice(
                        "manage_accounts", "", UiText.t("ui.bank.manage_account.acacec"), false));
              reply(
                  audience,
                  "accounts",
                  List.of(),
                  choices.size() == 1 && state.offered.isEmpty()
                      ? UiText.t("ui.bank.visit_a_bank_kiosk_to_open_your_primary_accoun.c0625a")
                      : UiText.t("ui.bank.choose_an_account_to_view_its_transactions.f30613"),
                  choices);
            });
  }

  static Set<EconomyCurrency> availableCurrencies(List<AccountIdentity> accounts) {
    var banks =
        accounts.stream()
            .filter(
                account ->
                    account.purpose() == AccountIdentity.Purpose.BANK
                        && account.status() == AccountIdentity.Status.OPEN)
            .toList();
    if (banks.size() >= AccountIdentity.MAX_OPEN_BANK_ACCOUNTS) return Set.of();
    var available = EnumSet.allOf(EconomyCurrency.class);
    banks.forEach(account -> available.remove(account.currency()));
    return Collections.unmodifiableSet(available);
  }

  static Set<EconomyCurrency> availableCurrencies(
      List<AccountIdentity> accounts, Set<EconomyCurrency> published) {
    var available = EnumSet.noneOf(EconomyCurrency.class);
    available.addAll(availableCurrencies(accounts));
    available.retainAll(published);
    return Collections.unmodifiableSet(available);
  }

  static boolean mayClose(
      AccountIdentity account, EconomyCurrency worldCurrency, AccountFunds funds, boolean teller) {
    return teller
        && account.purpose() == AccountIdentity.Purpose.BANK
        && account.status() == AccountIdentity.Status.OPEN
        && !account.primary()
        && account.currency() != worldCurrency
        && funds.reservedMinorUnits() == 0
        && funds.restrictedMinorUnits() == 0;
  }

  private static void manage(
      Audience audience, ServerServices services, PartyAuthorization.Grant grant, State state) {
    state.closure = null;
    state.openCurrencies = Set.of();
    audience.menu().setBusy(true);
    ownerAccounts(services, grant)
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              if (error != null || found.isEmpty()) {
                unavailable(audience, "manage");
                return;
              }
              var snapshot = found.orElseThrow();
              state.offered = offeredAccounts(snapshot.accounts());
              manageBalances(
                  audience,
                  services,
                  grant,
                  state,
                  snapshot,
                  List.copyOf(state.offered.values()),
                  0,
                  new ArrayList<>());
            });
  }

  private static void manageBalances(
      Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      OwnerAccounts snapshot,
      List<AccountIdentity> accounts,
      int index,
      List<BankingNetwork.Choice> rows) {
    boolean teller = audience.menu().access() == BankingMenu.Access.TELLER;
    if (index == accounts.size()) {
      state.openCurrencies = Set.of();
      for (var currency : EconomyCurrency.all())
        if (state.openCurrencies.contains(currency))
          rows.add(
              new BankingNetwork.Choice("account_open", currency.code(), currency.code(), false));
      String status = UiText.t("ui.bank.visit_the_account_institution_s_branch_kiosk_f.6c01d6");
      reply(audience, "manage", List.of(), status, rows);
      return;
    }
    var account = accounts.get(index);
    services
        .ledger()
        .funds(grant, account.id())
        .whenComplete(
            (funds, error) -> {
              if (!audience.live()) return;
              if (error != null || funds.isEmpty()) {
                unavailable(audience, "manage");
                return;
              }
              var balance = funds.orElseThrow();
              String label =
                  account.currency().code()
                      + (account.primary()
                          ? UiText.t("ui.bank.primary.4d9273")
                          : UiText.t("ui.bank.account.6f9a89"))
                      + " — "
                      + money(balance.posted());
              rows.add(
                  new BankingNetwork.Choice(
                      "account_close_preview", account.id().value().toString(), label, false));
              manageBalances(audience, services, grant, state, snapshot, accounts, index + 1, rows);
            });
  }

  private static Map<UUID, AccountIdentity> offeredAccounts(List<AccountIdentity> accounts) {
    var offered = new LinkedHashMap<UUID, AccountIdentity>();
    for (var account : accounts)
      if (account.purpose() == AccountIdentity.Purpose.BANK
          && account.status() == AccountIdentity.Status.OPEN)
        offered.put(account.id().value(), account);
    return Collections.unmodifiableMap(offered);
  }

  private static List<BankingNetwork.Choice> accountChoices(State state, String page) {
    var choices = new ArrayList<BankingNetwork.Choice>();
    for (var account : state.offered.values())
      choices.add(
          new BankingNetwork.Choice(
              "account_select",
              account.id().value() + "|" + page,
              account.currency().code()
                  + (account.primary()
                      ? UiText.t("ui.bank.primary_account.fa2f5b")
                      : UiText.t("ui.bank.account.6f9a89")),
              account.id().equals(state.selected)));
    return choices;
  }

  /**
   * Offers only authenticated owner accounts; the selected account determines the transaction
   * currency.
   */
  public static void cashAccounts(
      ServerPlayer player, BankingMenu menu, String page, List<String> lines, String status) {
    var services = Economy.serverServices(player.server);
    if (services == null || !menu.valid(player)) return;
    var grant = grant(player, services);
    if (grant == null) return;
    var state = STATES.computeIfAbsent(menu, ignored -> new State());
    var audience = new Audience(player, menu, menu.session(), menu.revision());
    menu.setBusy(true);
    java.util.concurrent.CompletionStage<LedgerService.BankingContext> contextRead;
    if (page.equals("deposit")) {
      contextRead =
          services
              .readAccounts(
                  grant,
                  false,
                  reads ->
                      TerminalMenus.bankAccounts(
                              reads,
                              grant.target(),
                              TerminalMenus.bank(reads, grant, menu, services.financialMillis()))
                          .stream()
                          .findFirst())
              .thenCompose(
                  found ->
                      found.isEmpty()
                          ? java.util.concurrent.CompletableFuture.failedFuture(
                              new IllegalStateException("No account at this bank"))
                          : services
                              .ledger()
                              .bankingContext(
                                  new PlayerId(player.getUUID()),
                                  menu.anchor(),
                                  found.orElseThrow().id()));
    } else
      contextRead =
          services
              .ledger()
              .bankingContext(new PlayerId(player.getUUID()), menu.anchor(), state.selected);
    contextRead.whenComplete(
        (context, error) -> {
          if (!audience.live()) return;
          if (error != null || context.account() == null) {
            if (page.equals("deposit"))
              reply(
                  audience,
                  page,
                  List.of("Open an account with this bank before making a deposit."),
                  "",
                  List.of());
            else unavailable(audience, page);
            return;
          }
          state.selected = context.account().id();
          ownerAccounts(services, grant)
              .thenCombine(services.ledger().funds(grant, context.account().id()), Map::entry)
              .whenComplete(
                  (result, accountError) -> {
                    if (!audience.live()) return;
                    if (accountError != null
                        || result.getKey().isEmpty()
                        || result.getValue().isEmpty()) {
                      unavailable(audience, page);
                      return;
                    }
                    state.offered = offeredAccounts(result.getKey().orElseThrow().accounts());
                    if (page.equals("deposit") || menu.access() == BankingMenu.Access.ATM)
                      state.offered = Map.of(context.account().id().value(), context.account());
                    var display = new ArrayList<String>();
                    if (page.equals("withdraw"))
                      display.add(
                          "Account Balance: " + money(result.getValue().orElseThrow().posted()));
                    else
                      display.add(
                          UiText.t("ui.bank.selected.ae3c35")
                              + context.account().currency().code()
                              + UiText.t("ui.bank.account.6f9a89"));
                    display.addAll(lines);
                    if (context.terminal() != null
                        && context.terminal().binding() != null
                        && context.terminal().binding().bound())
                      display.add(
                          UiText.t("ui.bank.terminal_cash_currency.3ece81")
                              + context.terminal().binding().nativeCurrency().code()
                              + UiText.t("ui.bank.2a342dcae1")
                              + context.account().currency().code());
                    String pageStatus =
                        page.equals("deposit")
                                && context.terminal() != null
                                && context.terminal().kind() == TerminalPolicy.Kind.ATM
                                && !context.terminal().settings().mode().deposits()
                            ? UiText.t(
                                "ui.bank.deposits_disabled_on_this_atm_its_owner_can_en.49b7ec")
                            : status;
                    reply(audience, page, display, pageStatus, accountChoices(state, page));
                  });
        });
  }

  private static AccountIdentity offered(State state, String value) {
    var text = value.trim();
    if (text.matches("[1-9][0-9]?")) {
      int index = Integer.parseInt(text) - 1;
      var accounts = List.copyOf(state.offered.values());
      if (index >= accounts.size()) throw new IllegalArgumentException("Account selection");
      return accounts.get(index);
    }
    var account = state.offered.get(UUID.fromString(text));
    if (account == null) throw new IllegalArgumentException("Account not offered");
    return account;
  }

  private static void select(
      Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      String value) {
    var fields = value.split("\\|", -1);
    if (fields.length > 2) throw new IllegalArgumentException("Account selection");
    String page = fields.length == 2 ? fields[1] : "history";
    if (!Set.of("history", "deposit", "withdraw", "fx").contains(page)
        || (!audience.menu().page().equals("accounts") && !audience.menu().page().equals(page)))
      throw new IllegalArgumentException("Account selection page");
    if (!BankingController.canChangeAccount(audience.menu()))
      throw new IllegalStateException("Resolve the current cash transaction first.");
    var selected = offered(state, fields[0]);
    audience.menu().setBusy(true);
    services
        .findOwnedAccount(grant, selected.id())
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              if (error != null
                  || found.isEmpty()
                  || found.orElseThrow().status() != AccountIdentity.Status.OPEN) {
                unavailable(audience, page);
                return;
              }
              if (!BankingController.canChangeAccount(audience.menu())) {
                reply(
                    audience,
                    audience.menu().page(),
                    audience.menu().lines(),
                    UiText.t("ui.bank.resolve_the_current_cash_transaction_first.06997f"));
                return;
              }
              state.selected = selected.id();
              state.historyCursor = null;
              state.exchange = null;
              state.quote = null;
              state.closure = null;
              audience.menu().setBusy(false);
              switch (page) {
                case "history" ->
                    BankingHistory.action(audience.player(), audience.menu(), "history", "");
                case "fx" -> fx(audience, services, grant, state);
                default -> BankingController.action(audience.player(), audience.menu(), page, "");
              }
            });
  }

  private static void open(
      Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      String value) {
    var currency = EconomyCurrency.fromCode(value.trim().toUpperCase(Locale.ROOT));
    if (!audience.menu().page().equals("manage")
        || audience.menu().access() != BankingMenu.Access.TELLER
        || !state.openCurrencies.contains(currency))
      throw new IllegalArgumentException("Choose an offered currency at a teller");
    audience.menu().setBusy(true);
    services
        .ledger()
        .bankingContext(new PlayerId(audience.player().getUUID()), audience.menu().anchor(), null)
        .whenComplete(
            (context, error) -> {
              if (!audience.live()) return;
              if (error != null
                  || context.account() == null
                  || context.terminal() == null
                  || context.terminal().kind() != TerminalPolicy.Kind.TELLER) {
                reply(
                    audience,
                    "manage",
                    List.of(),
                    UiText.t("ui.bank.open_your_first_account_from_my_accounts_then_.9ec171"));
                return;
              }
              var open = new AccountLifecycle.Open(currency, context.account().revision());
              var operation = new OperationId(UUID.randomUUID());
              var attempt = new RequestId(UUID.randomUUID());
              start(
                  audience,
                  services,
                  grant,
                  state,
                  ownedIntent(grant, AccountLifecycle.OPEN, operation, attempt, open.request()),
                  fresh ->
                      services.submitOwned(
                          fresh,
                          AccountLifecycle.OPEN,
                          operation,
                          attempt,
                          open.request(),
                          AccountLifecycle.opening(fresh.target(), open),
                          null),
                  "manage");
            });
  }

  private static void history(
      Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      boolean next) {
    if (next && state.historyCursor == null) {
      reply(
          audience, "history", audience.menu().lines(), UiText.t("ui.bank.end_of_history.72eaf9"));
      return;
    }
    if (!next) state.historyCursor = null;
    audience.menu().setBusy(true);
    services
        .ledger()
        .bankingContext(
            new PlayerId(audience.player().getUUID()), audience.menu().anchor(), state.selected)
        .whenComplete(
            (context, contextError) -> {
              if (!audience.live()) return;
              if (contextError != null || context.account() == null) {
                unavailable(audience, "history");
                return;
              }
              services
                  .ledger()
                  .funds(grant, context.account().id())
                  .whenComplete(
                      (funds, fundsError) -> {
                        if (!audience.live()) return;
                        if (fundsError != null || funds.isEmpty()) {
                          unavailable(audience, "history");
                          return;
                        }
                        services
                            .ledger()
                            .history(grant, context.account().id(), state.historyCursor, 8)
                            .whenComplete(
                                (found, error) -> {
                                  if (!audience.live()) return;
                                  if (error != null || found.isEmpty()) {
                                    unavailable(audience, "history");
                                    return;
                                  }
                                  var page = found.orElseThrow();
                                  state.historyCursor = page.nextCursor();
                                  var lines = new ArrayList<String>();
                                  lines.add(UiText.t("ui.bank.transaction_history.4ed32a"));
                                  lines.add(
                                      UiText.t("ui.bank.balance.c0edd5")
                                          + money(
                                              new Money(
                                                  funds.orElseThrow().currency(),
                                                  funds.orElseThrow().availableMinorUnits())));
                                  for (var entry : page.entries()) {
                                    lines.add(
                                        "#"
                                            + entry.sequence().value()
                                            + " "
                                            + money(entry.amount()));
                                    lines.add(
                                        UiText.t("ui.bank.transaction.caaf7d")
                                            + entry.transactionId().value());
                                  }
                                  if (page.entries().isEmpty())
                                    lines.add(UiText.t("ui.bank.no_transactions_yet.f794b6"));
                                  reply(
                                      audience,
                                      "history",
                                      lines,
                                      state.historyCursor == null
                                          ? UiText.t("ui.bank.all_entries_shown.a072e6")
                                          : UiText.t("ui.bank.more_history_available.210d3c"),
                                      List.of());
                                });
                      });
            });
  }

  private static void closePreview(
      Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      String value) {
    if (audience.menu().access() != BankingMenu.Access.KIOSK
        || !audience.menu().page().equals("branch")
        || !BankingController.canChangeAccount(audience.menu()))
      throw new IllegalArgumentException("Choose an account at its branch kiosk");
    var offered = state.offered.get(UUID.fromString(value));
    if (offered == null
        || audience.menu().choices().stream()
            .noneMatch(
                choice -> choice.action().equals("branch_close") && choice.value().equals(value)))
      throw new IllegalArgumentException("Choose an eligible offered account");
    state.selected = offered.id();
    state.closure = null;
    audience.menu().setBusy(true);
    services
        .ledger()
        .bankingContext(
            new PlayerId(audience.player().getUUID()), audience.menu().anchor(), state.selected)
        .whenComplete(
            (context, error) -> {
              if (!audience.live()) return;
              if (error != null
                  || context.account() == null
                  || context.terminal() == null
                  || context.terminal().kind() != TerminalPolicy.Kind.KIOSK
                  || !context.terminal().active()
                  || !context.terminal().generation().equals(audience.menu().generation())) {
                unavailable(audience, "manage");
                return;
              }
              var source = context.account();
              if (source.status() != AccountIdentity.Status.OPEN) {
                unavailable(audience, "manage");
                return;
              }
              if (source.primary()) {
                reply(audience, "manage", List.of(), failureMessage("ACCOUNT_PROTECTED"));
                return;
              }
              services
                  .ledger()
                  .primaryFunds(grant)
                  .thenCombine(
                      services.ledger().funds(grant, source.id()),
                      (primary, funds) -> {
                        if (primary.isEmpty() || funds.isEmpty())
                          throw new IllegalStateException("Account unavailable");
                        return Map.entry(primary.orElseThrow(), funds.orElseThrow());
                      })
                  .whenComplete(
                      (found, fundsError) -> {
                        if (!audience.live()) return;
                        if (fundsError != null) {
                          unavailable(audience, "manage");
                          return;
                        }
                        var destination = found.getKey().account();
                        var funds = found.getValue();
                        if (funds.reservedMinorUnits() != 0 || funds.restrictedMinorUnits() != 0) {
                          reply(audience, "manage", List.of(), failureMessage("ACTIVE_HOLD"));
                          return;
                        }
                        var close =
                            new AccountLifecycle.Close(
                                source.id(),
                                source.revision(),
                                destination.id(),
                                destination.revision());
                        state.closureTerminalRevision = context.terminal().revision();
                        try {
                          var closure = new CashClosure.Instruction(close, funds.posted());
                          if (closure.stacks() > 0
                              && !ca.emilybelnavis.mcmods.economy.cash.WithdrawalInventory.fits(
                                  audience.player(), closure.stacks())) {
                            reply(
                                audience,
                                "close",
                                List.of("Make room in your inventory for the full cash payout."),
                                "Inventory space needed");
                            return;
                          }
                          showClosure(
                              audience,
                              state,
                              closure,
                              List.of(
                                  "Close your " + source.currency().code() + " account",
                                  "Cash payout: " + money(funds.posted()),
                                  "The cash will be placed in your inventory.",
                                  "Your transaction history will be preserved."));
                        } catch (IllegalArgumentException unavailableCash) {
                          reply(
                              audience,
                              "close",
                              List.of(
                                  "This balance cannot be paid in one batch of notes and coins. Adjust the balance before closing the account."),
                              "Cash payout unavailable");
                        }
                      });
            });
  }

  private static void showClosure(
      Audience audience, State state, CashClosure.Instruction closure, List<String> lines) {
    state.closure = closure;
    reply(
        audience,
        "close",
        lines,
        UiText.t("ui.bank.review_account_closure.011421"),
        List.of(
            new BankingNetwork.Choice(
                "account_close_confirm", "", UiText.t("ui.bank.confirm_closure.8648b3"), false)));
    state.closureRevision = audience.menu().revision();
  }

  private static void closeConfirm(
      Audience audience, ServerServices services, PartyAuthorization.Grant grant, State state) {
    if (audience.menu().access() != BankingMenu.Access.KIOSK
        || !audience.menu().page().equals("close")
        || audience.menu().revision() != state.closureRevision
        || state.closure == null
        || !BankingController.canChangeAccount(audience.menu()))
      throw new IllegalArgumentException("Review closure first");
    var closure = state.closure;
    var view = state.branchView;
    if (view == null || view.terminal().revision() != state.closureTerminalRevision)
      throw new IllegalArgumentException("Stale branch preview");
    var lease = proof(audience, grant, view, BranchAdmission.Action.CLOSE, null);
    var request = new BankProviders.Request(lease, 0, closure.request().fields()).request();
    var operation = new OperationId(UUID.randomUUID());
    var attempt = new RequestId(UUID.randomUUID());
    start(
        audience,
        services,
        grant,
        state,
        new Journal.Intent(
            operation, attempt, grant.actor(), BankProviders.CLOSE, request, null, null),
        fresh ->
            services
                .ledger()
                .closeCashAtBranch(
                    fresh,
                    operation,
                    attempt,
                    closure,
                    BankingClosureClaims::read,
                    lease,
                    closure.stacks() == 0
                        || ca.emilybelnavis.mcmods.economy.cash.WithdrawalInventory.fits(
                            audience.player(), closure.stacks())),
        "close");
    state.closure = null;
    state.branchView = null;
  }

  private static void fx(
      Audience audience, ServerServices services, PartyAuthorization.Grant grant, State state) {
    state.exchange = null;
    state.quote = null;
    state.fxTerminal = null;
    audience.menu().setBusy(true);
    services
        .ledger()
        .bankingContext(
            new PlayerId(audience.player().getUUID()), audience.menu().anchor(), state.selected)
        .whenComplete(
            (context, error) -> {
              if (!audience.live()) return;
              if (error != null || context.fx() == null || context.account() == null) {
                unavailable(audience, "fx");
                return;
              }
              state.selected = context.account().id();
              ownerAccounts(services, grant)
                  .whenComplete(
                      (found, accountError) -> {
                        if (!audience.live()) return;
                        if (accountError != null || found.isEmpty()) {
                          unavailable(audience, "fx");
                          return;
                        }
                        state.offered = offeredAccounts(found.orElseThrow().accounts());
                        var choices = accountChoices(state, "fx");
                        int destinations = 0;
                        for (var account : state.offered.values()) {
                          if (account.currency() == context.account().currency()) continue;
                          choices.add(
                              new BankingNetwork.Choice(
                                  "destination",
                                  account.id().value().toString(),
                                  account.currency().code() + UiText.t("ui.bank.account.6f9a89"),
                                  false));
                          destinations++;
                        }
                        var lines =
                            destinations == 0
                                ? List.of(
                                    UiText.t(
                                        "ui.bank.open_another_currency_account_at_its_bank_kios.744d86"))
                                : List.of(
                                    UiText.t("ui.bank.from.b4f579")
                                        + context.account().currency().code()
                                        + UiText.t("ui.bank.account.6f9a89"),
                                    UiText.t(
                                        "ui.bank.choose_a_destination_account_and_enter_the_amo.7f6126"),
                                    UiText.t(
                                        "ui.bank.review_the_amount_received_and_fee_before_conf.5f1c9a"));
                        reply(
                            audience,
                            "fx",
                            lines,
                            destinations == 0
                                ? UiText.t("ui.bank.another_currency_account_is_needed.5b4fc8")
                                : UiText.t("ui.bank.choose_accounts_and_amount.55dec0"),
                            choices);
                      });
            });
  }

  static Money exchangeAmount(EconomyCurrency source, EconomyCurrency target, String text) {
    long units;
    if (source == EconomyCurrency.CHF || target == EconomyCurrency.CHF) {
      if (!text.matches("[0-9]{1,19}(\\.[0-9]{1,2})?"))
        throw new IllegalArgumentException("Exchange amount");
      units = new BigDecimal(text).movePointRight(source.fractionDigits()).longValueExact();
    } else units = Math.multiplyExact(Long.parseLong(text), source.minorUnitsPerMajor());
    if (units < 1) throw new IllegalArgumentException("Exchange amount");
    return new Money(source, units);
  }

  private static long displayCredit(FxQuotes.Quote quote, boolean drain) {
    boolean minor =
        quote.pricing() != null
            || quote.pair().source() == EconomyCurrency.CHF
            || quote.pair().target() == EconomyCurrency.CHF
            || drain
                && quote.debit().minorUnits() % quote.pair().source().minorUnitsPerMajor() != 0;
    return minor
        ? quote.credit().minorUnits()
        : quote.credit().minorUnits()
            - quote.credit().minorUnits() % quote.credit().currency().minorUnitsPerMajor();
  }

  private static void exchangePreview(
      Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      String value) {
    var fields = value.trim().split("\\s+", -1);
    if (fields.length != 2) throw new IllegalArgumentException("Exchange input");
    var target = offered(state, fields[0]);
    String amountText = fields[1];
    state.exchange = null;
    state.quote = null;
    state.fxTerminal = null;
    audience.menu().setBusy(true);
    services
        .ledger()
        .bankingContext(
            new PlayerId(audience.player().getUUID()), audience.menu().anchor(), state.selected)
        .whenComplete(
            (context, error) -> {
              if (!audience.live()) return;
              if (error != null || context.account() == null) {
                unavailable(audience, "fx");
                return;
              }
              if (!audience.menu().remote()
                  && (context.terminal() == null
                      || context.terminal().binding() == null
                      || !context.terminal().binding().bound())) {
                reply(
                    audience,
                    "fx",
                    List.of(
                        UiText.t("ui.bank.the_terminal_must_be_bound_to_a_bank_before_a_.44d5a6")),
                    UiText.t("ui.bank.bank_selection_required.bb007a"),
                    List.of());
                return;
              }
              state.fxTerminal = context.terminal();
              var source = context.account();
              services
                  .findOwnedAccount(grant, target.id())
                  .whenComplete(
                      (found, targetError) -> {
                        if (!audience.live()) return;
                        if (targetError != null || found.isEmpty()) {
                          unavailable(audience, "fx");
                          return;
                        }
                        var destination = found.orElseThrow();
                        try {
                          if (source.status() != AccountIdentity.Status.OPEN
                              || destination.status() != AccountIdentity.Status.OPEN
                              || source.currency() == destination.currency())
                            throw new IllegalArgumentException("Exchange accounts");
                          var amount =
                              exchangeAmount(source.currency(), destination.currency(), amountText);
                          services
                              .ledger()
                              .quote(
                                  grant,
                                  new RequestId(UUID.randomUUID()),
                                  new FxQuotes.Pair(source.currency(), destination.currency()),
                                  FxQuotes.Side.EXACT_DEBIT,
                                  amount)
                              .whenComplete(
                                  (quote, quoteError) -> {
                                    if (!audience.live()) return;
                                    if (quoteError != null) {
                                      unavailable(audience, "fx");
                                      return;
                                    }
                                    state.quote = quote;
                                    state.exchange =
                                        new FxSettlement.Exchange(
                                            quote.id(),
                                            new PlayerId(audience.player().getUUID()),
                                            new SameCurrency.Revision(
                                                source.id(), source.revision()),
                                            new SameCurrency.Revision(
                                                destination.id(), destination.revision()));
                                    long credited = displayCredit(quote, false);
                                    var choices =
                                        new ArrayList<>(
                                            audience.menu().choices().stream()
                                                .filter(
                                                    c -> !BankingOperation.confirmation(c.action()))
                                                .toList());
                                    choices.add(
                                        new BankingNetwork.Choice(
                                            "exchange_confirm",
                                            "",
                                            UiText.t("ui.bank.confirm_exchange.cc7363"),
                                            false));
                                    audience.menu().setBusy(false);
                                    audience
                                        .menu()
                                        .publish(
                                            "fx",
                                            List.of(
                                                UiText.t("ui.bank.debit.eaafe5")
                                                    + money(quote.debit()),
                                                UiText.t("ui.bank.credit.d42d88")
                                                    + money(
                                                        new Money(
                                                            quote.credit().currency(), credited)),
                                                UiText.t("ui.bank.fee.0cfb2a") + money(quote.fee()),
                                                UiText.t("ui.bank.quote_expires.9a6460")
                                                    + quote.expires(),
                                                UiText.t(
                                                    "ui.bank.choose_confirm_exchange_before_this_quote_expi.c434a6")),
                                            UiText.t("ui.bank.exchange_preview.34fe4d"),
                                            choices,
                                            new BankingOperation.View(
                                                BankingOperation.State.REVIEW,
                                                "screen.economy.operation.review",
                                                List.of(
                                                    new BankingOperation.Argument(
                                                        BankingOperation.ArgumentKind.MONEY,
                                                        quote.debit().currency().code(),
                                                        quote.debit().minorUnits()),
                                                    new BankingOperation.Argument(
                                                        BankingOperation.ArgumentKind.MONEY,
                                                        quote.fee().currency().code(),
                                                        quote.fee().minorUnits())),
                                                null,
                                                null,
                                                null,
                                                quote.id(),
                                                quote.expires().toEpochMilli()),
                                            null);
                                  });
                        } catch (IllegalArgumentException | ArithmeticException invalid) {
                          reply(
                              audience,
                              "fx",
                              List.of(UiText.t("ui.bank.fx_amount_help")),
                              UiText.t("ui.bank.invalid_exchange.065986"));
                        }
                      });
            });
  }

  private static void exchangeConfirm(
      Audience audience, ServerServices services, PartyAuthorization.Grant grant, State state) {
    if (state.exchange == null || state.quote == null || !audience.menu().page().equals("fx"))
      throw new IllegalArgumentException("Preview required");
    var exchange = state.exchange;
    var operation = new OperationId(UUID.randomUUID());
    var attempt = new RequestId(UUID.randomUUID());
    var intent =
        new Journal.Intent(
            operation,
            attempt,
            grant.actor(),
            FxSettlement.EXCHANGE,
            exchange.request(),
            null,
            null);
    var terminal = state.fxTerminal;
    var remoteToken = audience.menu().issuedToken();
    var remoteActor = new PlayerId(audience.player().getUUID());
    var live =
        audience.menu().remote()
            ? null
            : KioskAdmission.financialAuthority(
                audience.player(), audience.menu(), terminal, intent);
    start(
        audience,
        services,
        grant,
        state,
        intent,
        fresh ->
            services
                .ledger()
                .exchange(
                    fresh,
                    operation,
                    attempt,
                    exchange,
                    (candidate, reads) ->
                        (remoteToken == null
                                || ca.emilybelnavis.mcmods.economy.card.CardAccess.usable(
                                        reads,
                                        grant.target().world(),
                                        remoteActor,
                                        remoteToken,
                                        services.financialMillis())
                                    .isPresent())
                            && (live == null
                                || live.allows(
                                        TerminalPolicy.Action.CONFIGURE, candidate, terminal, reads)
                                    && TerminalBanking.matches(
                                        reads,
                                        grant.target().world(),
                                        terminal.anchor(),
                                        terminal.generation(),
                                        terminal.revision(),
                                        terminal.binding().institution()))),
        "fx");
    state.exchange = null;
    state.quote = null;
    state.fxTerminal = null;
  }

  private static void brand(
      Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      String action,
      String value) {
    if (audience.menu().access() != BankingMenu.Access.ADMIN || !offered(audience, action, value))
      throw new IllegalArgumentException("Brand selection unavailable");
    if (action.equals("brand_preview") && value.isEmpty()) {
      var choices =
          InstitutionCatalog.banks().stream()
              .map(
                  bank ->
                      new BankingNetwork.Choice(
                          "brand_preview",
                          bank.id().key(),
                          bank.name() + " (" + bank.baseCurrency().code() + ")",
                          false))
              .toList();
      reply(audience, "brands", List.of(), UiText.t("ui.bank.choose_a_bank.5cade1"), choices);
      return;
    }
    audience.menu().setBusy(true);
    boolean operator = audience.player().hasPermissions(2);
    services
        .ledger()
        .terminalSettings(grant.actor(), (actor, reads) -> operator, audience.menu().anchor())
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              if (error != null || found.isEmpty()) {
                unavailable(audience, "settings");
                return;
              }
              var current = found.orElseThrow();
              if (action.equals("brand_preview")) {
                var bank = InstitutionCatalog.require(new InstitutionCatalog.InstitutionId(value));
                state.pendingBrand = bank.id().key();
                state.brandRevision = current.revision();
                var scope = TerminalSettingsReview.scope(current);
                reply(
                    audience,
                    "brand_review",
                    List.of(
                        scope.get(0),
                        scope.get(1),
                        UiText.t("ui.bank.bind_this_machine_to.24936f") + bank.name() + "?",
                        UiText.t("ui.bank.native_currency.16da54") + bank.baseCurrency().code(),
                        UiText.t("ui.bank.existing_settings_are_retained_as_legacy_metad.d5f15b"),
                        UiText.t("ui.bank.cash_service_will_be_disabled_enable_native_ca.69457f"),
                        UiText.t("ui.bank.pending_cash_custody_work_must_finish_before_b.eb817b")),
                    UiText.t("ui.bank.confirm_native_currency_change.c1bff9"),
                    List.of(
                        new BankingNetwork.Choice(
                            "brand_confirm",
                            bank.id().key(),
                            UiText.t("ui.bank.confirm_bank_binding.48478d"),
                            false)));
                return;
              }
              if (state.pendingBrand == null
                  || !state.pendingBrand.equals(value)
                  || state.brandRevision != current.revision()) {
                reply(
                    audience,
                    "settings",
                    List.of(UiText.t("ui.bank.the_terminal_changed_review_the_bank_again.324a1d")),
                    UiText.t("ui.bank.stale_binding_preview.ac6fcc"),
                    List.of());
                return;
              }
              var request =
                  TerminalBanking.bindRequest(
                      current.anchor(),
                      current.generation(),
                      current.revision(),
                      new InstitutionCatalog.InstitutionId(state.pendingBrand),
                      true);
              var operation = new OperationId(UUID.randomUUID());
              var attempt = new RequestId(UUID.randomUUID());
              var intent =
                  new Journal.Intent(
                      operation, attempt, grant.actor(), TerminalBanking.BIND, request, null, null);
              var authority =
                  KioskAdmission.authority(audience.player(), audience.menu(), current, intent);
              start(
                  audience,
                  services,
                  grant,
                  state,
                  intent,
                  fresh ->
                      services
                          .ledger()
                          .terminalBanking(
                              (actor, kind) -> actor.equals(grant.actor()),
                              authority,
                              (actor, reads) -> operator)
                          .submit(intent),
                  "settings");
              state.pendingBrand = null;
            });
  }

  private static void settings(Audience audience, ServerServices services, State state) {
    state.settingsReview = null;
    state.settingsView = null;
    audience.menu().setBusy(true);
    var actor = new PlayerId(audience.player().getUUID());
    boolean operator = audience.player().hasPermissions(2);
    services
        .ledger()
        .terminalSettings(actor, (who, reads) -> operator, audience.menu().anchor())
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              if (error != null || found.isEmpty()) {
                reply(
                    audience,
                    "settings",
                    List.of(
                        UiText.t("ui.bank.only_this_terminal_s_owner_or_an_operator_may_.2d039a")),
                    UiText.t("ui.bank.owner_access_required.a11bd4"));
                return;
              }
              var terminal = found.orElseThrow();
              if (!terminal.generation().equals(audience.menu().generation())
                  || !terminal.active()) {
                reply(
                    audience,
                    "settings",
                    List.of(
                        UiText.t("ui.bank.reopen_this_terminal_to_see_its_current_settin.470c89")),
                    UiText.t("ui.bank.terminal_changed.420747"),
                    List.of());
                return;
              }
              state.settingsView = terminal;
              var binding = terminal.binding();
              boolean bound = binding != null && binding.bound();
              String identity =
                  bound
                      ? InstitutionCatalog.require(binding.institution()).name()
                          + " — "
                          + binding.nativeCurrency().code()
                      : UiText.t("ui.bank.unbound_bank_bank_selection_required.0d05f5");
              var lines = new ArrayList<String>();
              lines.add(identity);
              lines.addAll(TerminalSettingsReview.scope(terminal));
              lines.add(
                  UiText.t(
                      "ui.admin.binding."
                          + (bound
                              ? binding.migrationState().name().toLowerCase(Locale.ROOT)
                              : "unbound")));
              lines.add(
                  UiText.t("ui.bank.cash.f016dc")
                      + deliveryLabel(terminal.settings().mode())
                      + UiText.t("ui.bank.f9f879217a"));
              var controls = new ArrayList<BankingNetwork.Choice>();
              if (terminal.kind() != TerminalPolicy.Kind.REGISTER)
                controls.add(
                    new BankingNetwork.Choice(
                        "brand_preview", "", UiText.t("ui.bank.select_bank_brand.73c40b"), false));
              if (bound
                  && terminal.kind() != TerminalPolicy.Kind.KIOSK
                  && terminal.kind() != TerminalPolicy.Kind.REGISTER) {
                String form =
                    terminal.settings().mode().name()
                        + ";"
                        + binding.nativeCurrency().code()
                        + ";all";
                controls.add(
                    new BankingNetwork.Choice(
                        "configure", form, UiText.t("ui.admin.settings.review_action"), true));
              }
              var position =
                  new net.minecraft.core.BlockPos(
                      terminal.anchor().x(), terminal.anchor().y(), terminal.anchor().z());
              var physical = audience.player().serverLevel().getBlockState(position);
              if (terminal.kind() == TerminalPolicy.Kind.KIOSK
                  && physical.getBlock()
                      instanceof ca.emilybelnavis.mcmods.economy.terminal.BranchKioskBlock
                  && !physical.getValue(
                      ca.emilybelnavis.mcmods.economy.terminal.BranchKioskBlock.WIDE)) {
                lines.add(
                    UiText.t("ui.bank.legacy_narrow_kiosk_upgrade_is_free_and_needs_.7c7536"));
                controls.add(
                    new BankingNetwork.Choice(
                        "kiosk_upgrade", "", UiText.t("ui.bank.upgrade_kiosk.242437"), false));
              }
              if (audience.player().serverLevel().getBlockEntity(position)
                  instanceof ca.emilybelnavis.mcmods.economy.terminal.TerminalBlockEntity entity)
                entity.applyPolicy(terminal);
              reply(
                  audience,
                  "settings",
                  lines,
                  UiText.t("ui.bank.owner_operator_configuration.c2d446"),
                  controls);
            });
  }

  private static void configure(
      Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      String value) {
    if (audience.menu().access() != BankingMenu.Access.ADMIN
        || !audience.menu().page().equals("settings")
        || audience.menu().choices().stream().noneMatch(c -> c.action().equals("configure")))
      throw new IllegalArgumentException("Settings not offered");
    var shown = state.settingsView;
    state.settingsReview = null;
    audience.menu().setBusy(true);
    boolean operator = audience.player().hasPermissions(2);
    services
        .ledger()
        .terminalSettings(grant.actor(), (who, reads) -> operator, audience.menu().anchor())
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              if (error != null || found.isEmpty()) {
                unavailable(audience, "settings");
                return;
              }
              try {
                if (shown == null) throw new TerminalSettingsReview.Stale();
                var review =
                    TerminalSettingsReview.capture(
                        shown, found.orElseThrow(), parseSettings(value, shown.kind()));
                var lines = review.lines(CashCatalog.all());
                state.settingsReview = review;
                reply(
                    audience,
                    "settings_review",
                    lines,
                    UiText.t("ui.admin.settings.ready"),
                    List.of(
                        new BankingNetwork.Choice(
                            "configure_confirm",
                            "",
                            UiText.t("ui.admin.settings.confirm"),
                            false)));
              } catch (TerminalSettingsReview.Stale stale) {
                staleSettings(audience, state);
              } catch (IllegalArgumentException invalid) {
                reply(
                    audience,
                    "settings",
                    List.of(UiText.t("ui.admin.settings.native_only")),
                    UiText.t("ui.bank.check_the_setting_and_try_again.74e64e"),
                    List.of());
              }
            });
  }

  private static void configureConfirm(
      Audience audience, ServerServices services, PartyAuthorization.Grant grant, State state) {
    if (audience.menu().access() != BankingMenu.Access.ADMIN
        || !audience.menu().page().equals("settings_review")
        || !offered(audience, "configure_confirm", "")
        || state.settingsReview == null)
      throw new IllegalArgumentException("Settings review required");
    var review = state.settingsReview;
    audience.menu().setBusy(true);
    boolean operator = audience.player().hasPermissions(2);
    services
        .ledger()
        .terminalSettings(grant.actor(), (who, reads) -> operator, audience.menu().anchor())
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              if (error != null || found.isEmpty()) {
                unavailable(audience, "settings");
                return;
              }
              try {
                var request = review.request(found.orElseThrow());
                var operation = new OperationId(UUID.randomUUID());
                var attempt = new RequestId(UUID.randomUUID());
                var intent =
                    new Journal.Intent(
                        operation,
                        attempt,
                        grant.actor(),
                        TerminalPolicy.Action.CONFIGURE.kind(),
                        request,
                        null,
                        null);
                var authority =
                    KioskAdmission.authority(
                        audience.player(), audience.menu(), review.shown(), intent);
                start(
                    audience,
                    services,
                    grant,
                    state,
                    intent,
                    fresh ->
                        services
                            .ledger()
                            .terminalPolicy(
                                TerminalPolicy.Action.CONFIGURE,
                                (who, kind) -> who.equals(grant.actor()),
                                authority,
                                (who, reads) -> operator,
                                null)
                            .submit(intent),
                    "settings");
                state.settingsReview = null;
              } catch (TerminalSettingsReview.Stale stale) {
                staleSettings(audience, state);
              }
            });
  }

  private static void staleSettings(Audience audience, State state) {
    state.settingsReview = null;
    state.settingsView = null;
    reply(
        audience,
        "settings",
        List.of(UiText.t("ui.admin.settings.stale")),
        UiText.t("ui.bank.terminal_changed.420747"),
        List.of(
            new BankingNetwork.Choice(
                "settings", "", UiText.t("ui.admin.settings.refresh"), false)));
  }

  static TerminalPolicy.Settings parseSettings(String value, TerminalPolicy.Kind kind) {
    return parseSettings(value, kind, CashCatalog.all());
  }

  static TerminalPolicy.Settings parseSettings(
      String value, TerminalPolicy.Kind kind, java.util.List<CashCatalog.Denomination> catalog) {
    var simple = value.trim().split("\\s+", -1);
    if (simple.length == 1 && simple[0].equalsIgnoreCase("OFF"))
      return TerminalPolicy.Settings.disabled();
    if (simple.length == 2 && simple[0].matches("(?i)BOTH|DEPOSIT|WITHDRAW"))
      value =
          simple[0].toUpperCase(Locale.ROOT) + ";" + simple[1].toUpperCase(Locale.ROOT) + ";all";
    var fields = value.trim().split(";", -1);
    if (fields.length != 3) throw new IllegalArgumentException("Settings fields");
    var mode = TerminalPolicy.Mode.valueOf(fields[0].trim().toUpperCase(Locale.ROOT));
    if (mode == TerminalPolicy.Mode.DISABLED) return TerminalPolicy.Settings.disabled();
    if (kind == TerminalPolicy.Kind.REGISTER)
      throw new IllegalArgumentException("Register cash mode");
    var currencies = EnumSet.noneOf(EconomyCurrency.class);
    for (var code : fields[1].split(",", -1))
      currencies.add(EconomyCurrency.fromCode(code.trim().toUpperCase(Locale.ROOT)));
    var denominations = new TreeSet<String>();
    if (fields[2].trim().equalsIgnoreCase("all")) {
      for (var entry : catalog)
        if (currencies.contains(entry.currency())
            && (kind != TerminalPolicy.Kind.ATM || entry.bill()))
          denominations.add("economy:" + entry.id());
    } else {
      if (fields[2].split(",", -1).length > TerminalPolicy.MAX_DENOMINATIONS)
        throw new IllegalArgumentException("Cash type bound");
      for (var id : fields[2].split(",", -1)) {
        var key = id.trim();
        if (!key.startsWith("economy:")) key = "economy:" + key;
        final String wanted = key;
        var entry =
            catalog.stream()
                .filter(item -> ("economy:" + item.id()).equals(wanted))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown cash type"));
        if (!currencies.contains(entry.currency())
            || kind == TerminalPolicy.Kind.ATM && !entry.bill())
          throw new IllegalArgumentException("Cash type currency");
        denominations.add(key);
      }
    }
    return new TerminalPolicy.Settings(mode, currencies, denominations);
  }

  static void financeSubmit(
      FinanceMenuSupport.Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      Journal.Kind kind,
      Journal.CanonicalRequest request,
      java.util.function.BiFunction<OperationId, RequestId, JournalService.Submission> submit) {
    var operation = new OperationId(UUID.randomUUID());
    var attempt = new RequestId(UUID.randomUUID());
    var state = STATES.computeIfAbsent(audience.menu(), ignored -> new State());
    start(
        new Audience(audience.player(), audience.menu(), audience.session(), audience.revision()),
        services,
        grant,
        state,
        ownedIntent(grant, kind, operation, attempt, request),
        ignored -> submit.apply(operation, attempt),
        audience.menu().page());
  }

  private static Journal.Intent ownedIntent(
      PartyAuthorization.Grant grant,
      Journal.Kind kind,
      OperationId operation,
      RequestId attempt,
      Journal.CanonicalRequest request) {
    var fields = new HashMap<>(request.fields());
    fields.put("economy:owner", Journal.actorKey(grant.actor()));
    return new Journal.Intent(
        operation,
        attempt,
        grant.actor(),
        kind,
        new Journal.CanonicalRequest(request.version(), fields),
        null,
        null);
  }

  private static void start(
      Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      Journal.Intent intent,
      Function<PartyAuthorization.Grant, JournalService.Submission> submit,
      String page) {
    if (!audience.live()) return;
    if (audience.player().getPersistentData().contains(PENDING)) {
      reply(
          audience,
          page,
          List.of(UiText.t("ui.bank.a_previous_banking_operation_needs_its_origina.40e4b0")),
          UiText.t("ui.bank.previous_operation_unresolved.fde0de"));
      return;
    }
    var tag = new CompoundTag();
    tag.putUUID("world", grant.target().world().value());
    tag.putUUID("actor", audience.player().getUUID());
    tag.putUUID("operation", intent.operationId().value());
    tag.putUUID("request", intent.requestId().value());
    tag.putString("service", intent.kind().service());
    tag.putString("type", intent.kind().operationType());
    tag.putInt("version", intent.request().version());
    var fields = new CompoundTag();
    intent.request().fields().forEach(fields::putString);
    tag.put("fields", fields);
    if (audience
        .player()
        .getPersistentData()
        .getCompound("economy_banking_cash_pending")
        .hasUUID("operation"))
      throw new IllegalStateException("Resolve the pending cash operation first.");
    audience.player().getPersistentData().put(PENDING, tag);
    state.retry = submit;
    audience.menu().setBusy(true);
    boolean reachedSubmission = false;
    try {
      BankingSaves.player(audience.player(), grant.target().world(), intent.operationId().value());
      audience.menu().processing(intent.operationId().value(), intent.requestId().value());
      var completionAudience =
          new Audience(
              audience.player(), audience.menu(), audience.session(), audience.menu().revision());
      reachedSubmission = true;
      // The same original operation and canonical request are retained if admission or publication
      // is uncertain.
      submit
          .apply(grant)
          .completion()
          .whenComplete(
              (delivery, error) ->
                  finish(
                      completionAudience,
                      grant.target().world(),
                      state,
                      intent,
                      page,
                      error == null ? delivery.outcome() : null));
    } catch (Exception unavailable) {
      if (!reachedSubmission) {
        audience.player().getPersistentData().remove(PENDING);
        state.retry = null;
        reply(
            audience,
            page,
            List.of(UiText.t("ui.bank.the_request_was_not_submitted_because_its_save.b19980")),
            UiText.t("ui.bank.banking_save_unavailable.9b1e6c"));
      } else
        reply(
            new Audience(
                audience.player(), audience.menu(), audience.session(), audience.menu().revision()),
            page,
            List.of(UiText.t("ui.bank.your_original_operation_is_retained_choose_ret.07c849")),
            UiText.t("ui.bank.banking_admission_unavailable.e0c6bd"));
    }
  }

  private static Journal.Intent pending(ServerPlayer player, WorldId world) {
    var tag = player.getPersistentData().getCompound(PENDING);
    if (!tag.hasUUID("world")
        || !tag.getUUID("world").equals(world.value())
        || !tag.hasUUID("actor")
        || !tag.getUUID("actor").equals(player.getUUID()))
      throw new IllegalArgumentException("Pending banking owner");
    var kind = new Journal.Kind(tag.getString("service"), tag.getString("type"));
    if (!Set.of(
            ca.emilybelnavis.mcmods.economy.account.AccountCatalog.INITIALIZE_PRIMARY,
            AccountLifecycle.OPEN,
            AccountLifecycle.CLOSE,
            BankProviders.PRIMARY,
            BankProviders.SECONDARY,
            BankProviders.ASSIGN,
            BankProviders.CLOSE,
            BankProviders.SWITCH_SOURCE,
            BankProviders.SWITCH_TARGET,
            FxSettlement.EXCHANGE,
            TerminalPolicy.Action.CONFIGURE.kind(),
            TerminalBanking.BIND,
            ca.emilybelnavis.mcmods.economy.card.IssuedCards.ISSUE,
            ca.emilybelnavis.mcmods.economy.card.IssuedCards.REVOKE,
            ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.ISSUE,
            ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.INCREASE,
            ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.REPAY,
            ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DRAW,
            ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.REPLACE,
            ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.REVOKE,
            ca.emilybelnavis.mcmods.economy.loan.Loans.APPLY,
            ca.emilybelnavis.mcmods.economy.loan.Loans.REPAY,
            ca.emilybelnavis.mcmods.economy.debt.DebtPayments.PAY,
            ca.emilybelnavis.mcmods.economy.autopay.Autopay.CONFIGURE)
        .contains(kind)) throw new IllegalArgumentException("Pending banking kind");
    var fieldsTag = tag.getCompound("fields");
    if (fieldsTag.size() > 64) throw new IllegalArgumentException("Pending banking fields");
    var fields = new HashMap<String, String>();
    for (var key : fieldsTag.getAllKeys()) fields.put(key, fieldsTag.getString(key));
    return new Journal.Intent(
        new OperationId(tag.getUUID("operation")),
        new RequestId(tag.getUUID("request")),
        new PlayerId(player.getUUID()),
        kind,
        new Journal.CanonicalRequest(tag.getInt("version"), fields),
        null,
        null);
  }

  private static void retry(
      Audience audience, ServerServices services, PartyAuthorization.Grant grant, State state) {
    if (!audience.player().getPersistentData().contains(PENDING)) {
      reply(
          audience,
          audience.menu().page(),
          List.of(UiText.t("ui.bank.there_is_no_unresolved_banking_operation.7f45fe")),
          UiText.t("ui.bank.nothing_to_retry.def91a"));
      return;
    }
    var intent = pending(audience.player(), grant.target().world());
    audience.menu().setBusy(true);
    var session = services.sessionId();
    var world = grant.target().world();
    services
        .journal((actor, kind) -> grant.allows(world, session, actor, kind), Map.of(), null)
        .lookup(intent)
        .whenComplete(
            (found, error) -> {
              if (error != null) {
                finish(audience, world, state, intent, audience.menu().page(), null);
                return;
              }
              if (found.isPresent()) {
                finish(audience, world, state, intent, audience.menu().page(), found.orElseThrow());
                return;
              }
              if (!audience.live()) return;
              audience
                  .player()
                  .getPersistentData()
                  .put(
                      "economy_banking_last_absent",
                      audience.player().getPersistentData().getCompound(PENDING).copy());
              audience.player().getPersistentData().remove(PENDING);
              state.retry = null;
              try {
                BankingSaves.player(audience.player(), world, intent.operationId().value());
              } catch (Exception ignored) {
                /* Retained saved marker safely replays the same lookup. */
              }
              audience.menu().setBusy(false);
              audience
                  .menu()
                  .publish(
                      audience.menu().page(),
                      List.of(
                          UiText.t("ui.bank.operation.7811f5") + intent.operationId().value(),
                          UiText.t("ui.bank.the_ordered_original_outcome_lookup_confirms_n.596e24"),
                          UiText.t(
                              "ui.bank.prepare_a_fresh_request_and_review_current_ter.488eaf")),
                      UiText.t("ui.bank.original_operation_was_not_committed.e8bcf8"),
                      List.of(),
                      BankingOperation.View.original(
                          BankingOperation.State.EXPIRED_STALE,
                          intent.operationId().value(),
                          intent.requestId().value(),
                          null),
                      null);
            });
  }

  private static void finish(
      Audience audience,
      WorldId world,
      State state,
      Journal.Intent intent,
      String page,
      Journal.Outcome outcome) {
    boolean terminal =
        outcome != null
            && (outcome.status() == Journal.Status.COMMITTED
                || outcome.status() == Journal.Status.DURABLY_REJECTED);
    if (terminal) {
      var saved = audience.player().getPersistentData().getCompound(PENDING);
      if (saved.hasUUID("operation")
          && saved.getUUID("operation").equals(intent.operationId().value())) {
        audience.player().getPersistentData().remove(PENDING);
        // A disconnected old ServerPlayer must never overwrite a newer login's inventory file.
        if (audience.player().server.getPlayerList().getPlayer(audience.player().getUUID())
            == audience.player()) {
          try {
            BankingSaves.player(audience.player(), world, intent.operationId().value());
          } catch (Exception ignored) {
            /* A persisted old marker is safe: exact lookup will resolve it again. */
          }
        }
      }
      state.retry = null;
    }
    if (!audience.live()) return;
    var lines = new ArrayList<String>();
    if (outcome != null && outcome.status() == Journal.Status.COMMITTED)
      lines.add(
          switch (page) {
            case "setup" -> UiText.t("ui.bank.your_primary_account_is_ready.11212e");
            case "manage" -> UiText.t("ui.bank.your_account_is_ready.abcd4b");
            case "accounts" -> UiText.t("ui.bank.your_account_is_ready.abcd4b");
            case "close" ->
                UiText.t("ui.bank.your_account_was_closed_its_history_is_preserv.c8c94f");
            case "fx" -> UiText.t("ui.bank.your_exchange_is_complete.759dbc");
            case "settings" -> UiText.t("ui.bank.the_terminal_settings_were_saved.0371d0");
            case "cards" ->
                intent.kind().equals(ca.emilybelnavis.mcmods.economy.card.IssuedCards.ISSUE)
                    ? UiText.t("ui.bank.your_card_issuance_committed_use_collect_items.9d1fa9")
                    : UiText.t("ui.bank.token_revoked_help");
            default -> "Done.";
          });
    else if (outcome != null && outcome.status() == Journal.Status.DURABLY_REJECTED)
      lines.add(failureMessage(outcome.result().code()));
    lines.add(UiText.t("ui.bank.operation.7811f5") + intent.operationId().value());
    if (outcome != null)
      lines.add(outcome.result().code().replace('_', ' ').toLowerCase(Locale.ROOT));
    if (!terminal)
      lines.add(UiText.t("ui.bank.keep_the_original_operation_choose_retry_banki.adc7ba"));
    String resultStatus =
        !terminal
            ? UiText.t("ui.bank.banking_outcome_needs_checking.df6a34")
            : outcome.status() == Journal.Status.DURABLY_REJECTED
                ? failureMessage(outcome.result().code())
                : (intent.kind().equals(AccountLifecycle.CLOSE)
                        || intent.kind().equals(BankProviders.CLOSE))
                    ? UiText.t("ui.bank.account_closed_an_account_slot_is_now_availabl.bff8b6")
                    : UiText.t("ui.bank.banking_operation_resolved.8b3bf3");
    audience.menu().setBusy(false);
    var view =
        outcome == null
            ? BankingOperation.View.original(
                BankingOperation.State.OUTCOME_UNCERTAIN,
                intent.operationId().value(),
                intent.requestId().value(),
                null)
            : BankingOperation.outcome(outcome);
    audience.menu().publish(page, lines, resultStatus, List.of(), view, null);
    if (outcome != null
        && outcome.status() == Journal.Status.COMMITTED
        && (intent.kind().equals(TerminalPolicy.Action.CONFIGURE.kind())
            || intent.kind().equals(TerminalBanking.BIND)))
      action(audience.player(), audience.menu(), "settings", "");
    if (outcome != null && outcome.status() == Journal.Status.COMMITTED) {
      if (intent.kind().equals(ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.DRAW)
          && "ADVANCE".equals(intent.request().fields().get("kind"))) {
        state.selected =
            new AccountId(UUID.fromString(intent.request().fields().get("destination")));
        var services = Economy.serverServices(audience.player().server);
        var current =
            new Audience(
                audience.player(), audience.menu(), audience.session(), audience.menu().revision());
        services
            .ledger()
            .bankingContext(
                new PlayerId(audience.player().getUUID()), audience.menu().anchor(), state.selected)
            .whenComplete(
                (context, error) -> {
                  if (!current.live()) return;
                  if (error == null
                      && context.account() != null
                      && context.terminal() != null
                      && context.terminal().binding() != null
                      && context.account().currency()
                          == context.terminal().binding().nativeCurrency()) {
                    var amount =
                        new Money(
                            context.account().currency(),
                            Long.parseLong(intent.request().fields().get("amount")));
                    ca.emilybelnavis.mcmods.economy.banking.BankingWithdrawals.action(
                        audience.player(),
                        audience.menu(),
                        "withdraw_preview",
                        amount.majorUnits().toPlainString());
                  } else
                    ca.emilybelnavis.mcmods.economy.banking.BankingWithdrawals.action(
                        audience.player(), audience.menu(), "withdraw", "");
                });
        return;
      }
      if (intent.kind().service().equals("economy:cards")) {
        cards(
            new Audience(
                audience.player(),
                audience.menu(),
                audience.menu().session(),
                audience.menu().revision()),
            Economy.serverServices(audience.player().server),
            grant(audience.player(), Economy.serverServices(audience.player().server)),
            state,
            state.cardAccount == null ? "" : state.cardAccount.value().toString());
        return;
      }
      if (intent.kind().equals(BankProviders.CLOSE)
          && "physical-cash-v1".equals(intent.request().fields().get("payout"))
          && Long.parseLong(intent.request().fields().get("amount")) > 0) {
        state.selected = null;
        BankingController.deliver(
            audience.player(), audience.menu(), world, intent.operationId().value());
        return;
      }
      if ((intent.kind().equals(AccountLifecycle.CLOSE)
          || intent.kind().equals(BankProviders.CLOSE))) state.selected = null;
      if (intent.kind().service().equals("economy:providers")
          || intent.kind().equals(AccountLifecycle.OPEN)
          || (intent.kind().equals(AccountLifecycle.CLOSE)
              || intent.kind().equals(BankProviders.CLOSE)))
        BankingController.action(audience.player(), audience.menu(), "manage_accounts", "");
      else if (intent.kind().equals(AccountCatalog.INITIALIZE_PRIMARY))
        BankingController.action(audience.player(), audience.menu(), "accounts", "");
    }
  }

  static String failureMessage(String code) {
    return switch (code) {
      case "TERMINAL_STALE" -> UiText.t("ui.admin.settings.stale");
      case "PRICING_QUOTE_STALE", "FX_PRICING_REQUIRED", "FX_PROVIDER_STALE" ->
          UiText.t("ui.bank.account_service_or_pricing_changed_review_a_fr.1a880a");
      case "YIELD_SETTLEMENT_REQUIRED", "YIELD_FUNDING_UNAVAILABLE" ->
          UiText.t("ui.bank.earned_deposit_yield_must_be_settled_before_cl.16a68f");
      case "BRANCH_REQUIRED" ->
          UiText.t("ui.bank.visit_the_account_institution_s_branded_branch.4e6008");
      case "BANK_CAP_REACHED" ->
          UiText.t("ui.bank.limit_4_open_accounts_a_bank_branch_is_require.040bdf");
      case "BANK_CURRENCY_EXISTS" ->
          UiText.t("ui.bank.you_already_have_an_open_account_in_this_curre.eaedc1");
      case "ACCOUNT_PROTECTED" ->
          UiText.t("ui.bank.your_primary_and_world_currency_accounts_canno.2615f6");
      case "ACTIVE_HOLD", "RESTRICTED_FUNDS" ->
          UiText.t("ui.bank.this_account_has_reserved_or_restricted_funds_.8c75d2");
      case "UNRESOLVED_OBLIGATION" ->
          UiText.t("ui.bank.resolve_pending_payments_refunds_or_item_deliv.388d51");
      case "FX_QUOTE_STALE",
          "FX_QUOTE_CONSUMED",
          "FX_CLOSURE_AMOUNT",
          "ACCOUNT_STALE",
          "DESTINATION_STALE" ->
          UiText.t("ui.bank.the_account_or_quote_changed_review_the_closur.37a26a");
      case "PRIMARY_REQUIRED" ->
          UiText.t("ui.bank.open_your_first_account_from_my_accounts.899393");
      default -> UiText.t("ui.bank.request_declined_refresh_your_accounts_and_rev.634994");
    };
  }

  private static void reply(Audience audience, String page, List<String> lines, String status) {
    if (!audience.live()) return;
    audience.menu().setBusy(false);
    audience.menu().publish(page, lines, status);
  }

  private static void reply(
      Audience audience,
      String page,
      List<String> lines,
      String status,
      List<BankingNetwork.Choice> choices) {
    if (!audience.live()) return;
    audience.menu().setBusy(false);
    audience.menu().publish(page, lines, status, choices);
  }

  private static void decline(Audience audience, String page, List<String> lines, String status) {
    if (!audience.live()) return;
    audience.menu().setBusy(false);
    audience
        .menu()
        .publish(
            page,
            lines,
            status,
            List.of(),
            BankingOperation.View.of(
                BankingOperation.State.DECLINED, "screen.economy.operation.declined"),
            null);
  }

  private static void unavailable(Audience audience, String page) {
    decline(
        audience,
        page,
        List.of(UiText.t("ui.bank.the_account_or_terminal_is_unavailable_refresh.4345b6")),
        UiText.t("ui.bank.banking_view_unavailable.d7a528"));
  }

  private static String money(Money value) {
    return UiText.money(value);
  }

  private static String friendlyMode(TerminalPolicy.Mode mode) {
    return switch (mode) {
      case BOTH -> UiText.t("ui.bank.deposits_and_withdrawals.d92934");
      case DEPOSIT -> UiText.t("ui.bank.deposits_only.5f9506");
      case WITHDRAW -> UiText.t("ui.bank.withdrawals_only.2c3fe2");
      case DISABLED -> "off";
    };
  }

  private static String display(Object value) {
    return switch (String.valueOf(value)) {
      case "ALL" -> UiText.t("ui.bank.state.all");
      case "TRANSFER" -> UiText.t("ui.bank.state.transfer");
      case "FX" -> UiText.t("ui.bank.state.fx");
      case "CASH" -> UiText.t("ui.bank.state.cash");
      case "PAYMENT" -> UiText.t("ui.bank.state.payment");
      case "CREDIT" -> UiText.t("ui.bank.state.credit");
      case "LOAN" -> UiText.t("ui.bank.state.loan");
      case "COLLECTION" -> UiText.t("ui.bank.state.collection");
      case "OTHER" -> UiText.t("ui.bank.state.other");
      case "KNOWN" -> UiText.t("ui.bank.state.known");
      case "NOT_RECORDED" -> UiText.t("ui.bank.state.not_recorded");
      case "UNAVAILABLE" -> UiText.t("ui.bank.state.unavailable");
      case "PRIVATE" -> UiText.t("ui.bank.state.private");
      case "CAPTURED" -> UiText.t("ui.bank.state.captured");
      case "AVAILABLE" -> UiText.t("ui.bank.state.available");
      case "PROVENANCE_UNAVAILABLE" -> UiText.t("ui.bank.state.provenance_unavailable");
      case "CURRENCY_UNAVAILABLE" -> UiText.t("ui.bank.state.currency_unavailable");
      case "MISSING_PUBLICATION" -> UiText.t("ui.bank.state.missing_publication");
      case "EFFECTIVE" -> UiText.t("ui.bank.state.effective");
      case "DAILY" -> UiText.t("ui.bank.state.daily");
      case "OPEN" -> UiText.t("ui.bank.state.open");
      case "CLOSED" -> UiText.t("ui.bank.state.closed");
      case "EXPIRED" -> UiText.t("ui.bank.state.expired");
      case "ACTIVE" -> UiText.t("ui.bank.state.active");
      case "REVOKED" -> UiText.t("ui.bank.state.revoked");
      default -> String.valueOf(value);
    };
  }

  static String deliveryLabel(Object value) {
    return switch (String.valueOf(value)) {
      case "BOTH" -> UiText.t("ui.bank.state.both");
      case "DEPOSIT" -> UiText.t("ui.bank.state.deposit");
      case "WITHDRAW" -> UiText.t("ui.bank.state.withdraw");
      case "DISABLED" -> UiText.t("ui.bank.state.disabled");
      case "RESERVED" -> UiText.t("ui.bank.state.reserved");
      case "WITNESSED" -> UiText.t("ui.bank.state.witnessed");
      case "COMMITTED" -> UiText.t("ui.bank.state.committed");
      case "DELIVERING" -> UiText.t("ui.bank.state.delivering");
      case "ACKNOWLEDGED" -> UiText.t("ui.bank.state.acknowledged");
      case "CLAIM" -> UiText.t("ui.bank.state.claim");
      case "QUARANTINED" -> UiText.t("ui.bank.state.quarantined");
      case "DELIVERED" -> UiText.t("ui.bank.state.delivered");
      case "COMPENSATED" -> UiText.t("ui.bank.state.compensated");
      default -> String.valueOf(value);
    };
  }
}
