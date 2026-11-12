package ca.emilybelnavis.mcmods.economy.banking;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.cash.*;
import ca.emilybelnavis.mcmods.economy.foundation.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.terminal.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.SimpleMenuProvider;

/**
 * Normal server-authoritative player entry points. No packet supplies actor, world, money postings
 * or evidence.
 */
public final class BankingController {
  private static final String PENDING = "economy_banking_cash_pending";
  private static final Map<BankingMenu, Flow> FLOWS = new WeakHashMap<>();

  static final class Flow {
    LedgerService.BankingContext context;
    Deposits.Preview deposit;
    long depositRevision;
    long saleRevision;
    RegisterSales.Quote quote;
    LedgerService.CreditDrawQuote credit;
    ca.emilybelnavis.mcmods.economy.credit.CreditDrawAdmission.Proof creditProof;
    RegisterInventory.Snapshot stock;
    UUID reservation;
    Runnable retry;
    boolean inFlight;
    Journal.Outcome financialOutcome;
    boolean ownsInputs;
    Set<UUID> claims = Set.of();
    List<UUID> claimOrder = List.of();
    UUID claimCursor;
  }

  private BankingController() {}

  public static ServerServices services(ServerPlayer player) {
    return Economy.serverServices(player.server);
  }

  public static PlayerId actor(ServerPlayer player) {
    return new PlayerId(player.getUUID());
  }

  public static boolean canChangeAccount(BankingMenu menu) {
    var flow = FLOWS.get(menu);
    return (flow == null || flow.reservation == null && !flow.inFlight)
        && BankingWithdrawals.canChangeAccount(menu);
  }

  public static void open(ServerPlayer player, BlockPos clicked) {
    var anchor = TerminalGeometry.resolveAnchor(player.serverLevel(), clicked);
    if (!(player.serverLevel().getBlockEntity(anchor) instanceof TerminalBlockEntity entity)
        || !TerminalLifecycle.matches(player.serverLevel(), anchor, entity.generation())) {
      player.displayClientMessage(
          UiText.component(
              player, UiText.t("ui.bank.terminal_is_still_preparing_try_again_shortly.0401c4")),
          true);
      return;
    }
    if ((entity.kind() == TerminalPolicy.Kind.ATM
            || entity.kind() == TerminalPolicy.Kind.KIOSK
            || entity.kind() == TerminalPolicy.Kind.TELLER)
        && player.getMainHandItem().isEmpty()
        && player.isShiftKeyDown()
        && mayConfigure(entity.owner(), player.getUUID(), player.hasPermissions(2))) {
      openAdmin(player, anchor);
      return;
    }
    var access =
        switch (entity.kind()) {
          case ATM -> BankingMenu.Access.ATM;
          case TELLER -> BankingMenu.Access.TELLER;
          case KIOSK -> BankingMenu.Access.KIOSK;
          case REGISTER -> BankingMenu.Access.REGISTER;
        };
    var terminalAnchor =
        new TerminalPolicy.Anchor(
            player.serverLevel().dimension().location().toString(),
            anchor.getX(),
            anchor.getY(),
            anchor.getZ());
    if (access == BankingMenu.Access.ATM) {
      var services = services(player);
      if (services == null
          || services.state() != ServerServices.State.READY
          || services.worldId() == null) return;
      var mark =
          java.util.stream.Stream.of(player.getMainHandItem(), player.getOffhandItem())
              .map(
                  stack ->
                      ca.emilybelnavis.mcmods.economy.card.CardStackIdentity.mark(
                          stack, services.worldId()))
              .flatMap(Optional::stream)
              .findFirst()
              .orElse(null);
      if (mark == null) {
        player.displayClientMessage(
            Component.literal("Use a bank or credit card to access this ATM."), true);
        return;
      }
      services
          .ledger()
          .usableCardAccess(actor(player), services.worldId(), mark.token())
          .whenComplete(
              (found, error) -> {
                if (player.server.getPlayerList().getPlayer(player.getUUID()) != player
                    || error != null
                    || found.isEmpty()
                    || !heldIssuedCard(player, services.worldId(), found.orElseThrow())
                    || !TerminalLifecycle.matches(player.serverLevel(), anchor, entity.generation())
                    || !player
                        .serverLevel()
                        .dimension()
                        .location()
                        .toString()
                        .equals(terminalAnchor.dimension())
                    || player.distanceToSqr(
                            anchor.getX() + .5, anchor.getY() + .5, anchor.getZ() + .5)
                        > 36) return;
                open(player, terminalAnchor, entity.generation(), access, mark.token());
              });
    } else open(player, terminalAnchor, entity.generation(), access);
  }

  public static boolean openAdmin(ServerPlayer player, BlockPos clicked) {
    var anchor = TerminalGeometry.resolveAnchor(player.serverLevel(), clicked);
    if (!(player.serverLevel().getBlockEntity(anchor) instanceof TerminalBlockEntity entity)
        || !TerminalLifecycle.matches(player.serverLevel(), anchor, entity.generation())
        || !mayConfigure(entity.owner(), player.getUUID(), player.hasPermissions(2))) return false;
    open(
        player,
        new TerminalPolicy.Anchor(
            player.serverLevel().dimension().location().toString(),
            anchor.getX(),
            anchor.getY(),
            anchor.getZ()),
        entity.generation(),
        BankingMenu.Access.ADMIN);
    return true;
  }

  public static boolean mayConfigure(UUID owner, UUID player, boolean operator) {
    return operator || player.equals(owner);
  }

  public static void openCard(ServerPlayer player) {
    var position = player.blockPosition();
    var anchor =
        new TerminalPolicy.Anchor(
            player.serverLevel().dimension().location().toString(),
            position.getX(),
            position.getY(),
            position.getZ());
    var services = services(player);
    if (services == null || services.worldId() == null) return;
    var mark =
        java.util.stream.Stream.of(player.getMainHandItem(), player.getOffhandItem())
            .map(
                stack ->
                    ca.emilybelnavis.mcmods.economy.card.CardStackIdentity.mark(
                        stack, services.worldId()))
            .flatMap(Optional::stream)
            .findFirst()
            .orElse(null);
    if (mark == null) return;
    services
        .ledger()
        .usableCardAccess(actor(player), services.worldId(), mark.token())
        .whenComplete(
            (found, error) -> {
              if (player.server.getPlayerList().getPlayer(player.getUUID()) != player
                  || services.state() != ServerServices.State.READY
                  || services.worldId() == null) return;
              boolean held =
                  java.util.stream.Stream.of(player.getMainHandItem(), player.getOffhandItem())
                      .anyMatch(
                          stack ->
                              ca.emilybelnavis.mcmods.economy.card.CardStackIdentity.mark(
                                      stack, services.worldId())
                                  .filter(mark::equals)
                                  .isPresent());
              if (error != null
                  || found.isEmpty()
                  || !held
                  || !heldIssuedCard(player, services.worldId(), found.orElseThrow())) {
                player.displayClientMessage(
                    UiText.component(
                        player,
                        UiText.t("ui.bank.this_card_is_unavailable_expired_or_revoked.a04e80")),
                    true);
                return;
              }
              open(player, anchor, UUID.randomUUID(), BankingMenu.Access.CARD, mark.token());
            });
  }

  private static boolean heldIssuedCard(
      ServerPlayer player, WorldId world, ca.emilybelnavis.mcmods.economy.card.CardAccess token) {
    return java.util.stream.Stream.of(player.getMainHandItem(), player.getOffhandItem())
        .anyMatch(
            stack ->
                ca.emilybelnavis.mcmods.economy.card.CardStackIdentity.mark(stack, world)
                        .filter(mark -> mark.token().equals(token.id()))
                        .isPresent()
                    && ca.emilybelnavis.mcmods.economy.card.CardCatalog.find(stack)
                        .filter(item -> item.id().equals(token.design()))
                        .isPresent());
  }

  public static void checkedAction(
      ServerPlayer player, BankingMenu menu, String action, String value) {
    if (menu.access() == BankingMenu.Access.ATM
        && BankingHome.restricted(menu)
        && !Set.of(
                "home",
                "withdraw",
                "terminal_withdraw_bank",
                "terminal_withdraw_credit",
                "withdraw_preview",
                "withdraw_confirm",
                "withdraw_mix",
                "withdraw_mix_change",
                "withdraw_mix_review",
                "withdraw_mix_reset",
                "credit_advance",
                "credit_adv_preview",
                "credit_adv_confirm",
                "retry",
                "bank_retry",
                "cancel",
                "claims",
                "claims_next",
                "collect")
            .contains(action)) return;
    if (menu.page().equals("home")
        && !action.equals("home")
        && !Set.of("retry", "bank_retry", "cancel").contains(action)
        && menu.choices().stream()
            .noneMatch(c -> c.action().equals(action) && c.value().equals(value))) return;
    if (menu.issuedToken() == null) {
      action(player, menu, action, value);
      return;
    }
    var services = services(player);
    if (services == null || services.worldId() == null) return;
    long revision = menu.revision();
    var session = menu.session();
    menu.setBusy(true);
    services
        .ledger()
        .usableCardAccess(actor(player), services.worldId(), menu.issuedToken())
        .whenComplete(
            (found, error) -> {
              if (player.server.getPlayerList().getPlayer(player.getUUID()) != player
                  || !menu.valid(player)
                  || menu.revision() != revision
                  || !menu.session().equals(session)) return;
              menu.setBusy(false);
              if (error != null
                  || found.isEmpty()
                  || !heldIssuedCard(player, services.worldId(), found.orElseThrow())) {
                player.closeContainer();
                return;
              }
              action(player, menu, action, value);
            });
  }

  public static void openWorldSetup(ServerPlayer player, UUID lease) {
    var position = player.blockPosition();
    open(
        player,
        new TerminalPolicy.Anchor(
            player.serverLevel().dimension().location().toString(),
            position.getX(),
            position.getY(),
            position.getZ()),
        lease,
        BankingMenu.Access.WORLD_SETUP);
  }

  private static void open(
      ServerPlayer player,
      TerminalPolicy.Anchor anchor,
      UUID generation,
      BankingMenu.Access access) {
    open(player, anchor, generation, access, null);
  }

  private static void open(
      ServerPlayer player,
      TerminalPolicy.Anchor anchor,
      UUID generation,
      BankingMenu.Access access,
      UUID issuedToken) {
    if (services(player) == null || services(player).state() != ServerServices.State.READY) return;
    var session = UUID.randomUUID();
    var holder = new BankingMenu[1];
    player.openMenu(
        new SimpleMenuProvider(
            (id, inventory, ignored) ->
                holder[0] = new BankingMenu(id, inventory, anchor, generation, session, access),
            UiText.component(
                player,
                access == BankingMenu.Access.REGISTER
                    ? UiText.t("ui.bank.register.d67299")
                    : access == BankingMenu.Access.ADMIN
                        ? UiText.t("ui.bank.terminal_configuration.9560f9")
                        : UiText.t("ui.bank.banking.fff5a7"))),
        buffer -> holder[0].writeOpening(buffer));
    if (holder[0] != null && issuedToken != null) holder[0].setIssuedToken(issuedToken);
    if (holder[0] != null && access != BankingMenu.Access.WORLD_SETUP) {
      try {
        recoverInputs(player, holder[0]);
      } catch (RuntimeException unavailable) {
        player.displayClientMessage(
            UiText.component(
                player, UiText.t("ui.bank.retained_originals_could_not_be_verified_they_.833970")),
            false);
      }
    }
    if (holder[0] != null)
      holder[0].publish(
          access == BankingMenu.Access.WORLD_SETUP
              ? "setup"
              : access == BankingMenu.Access.ADMIN ? "settings" : "home",
          access == BankingMenu.Access.WORLD_SETUP
              ? List.of(
                  UiText.t("ui.bank.choose_this_world_s_bank_currency.ad1b8c"),
                  UiText.t("ui.bank.the_first_connected_player_s_choice_sets_the_o.0b5645"),
                  UiText.t("ui.bank.available_currencies.4c3691")
                      + EconomyCurrency.all().stream()
                          .map(EconomyCurrency::code)
                          .collect(java.util.stream.Collectors.joining(", ")))
              : access == BankingMenu.Access.REGISTER
                  ? List.of()
                  : access == BankingMenu.Access.ADMIN
                      ? List.of(
                          UiText.t("ui.bank.configure_the_terminal_s_cash_services.6dfd1b"),
                          UiText.t("ui.bank.debug_details_are_controlled_by_debuggui_in_ec.ffa73c"))
                      : List.of(),
          UiText.t("ui.bank.how_can_we_help.d99615"));
    if (holder[0] != null
        && Set.of(
                BankingMenu.Access.ATM,
                BankingMenu.Access.TELLER,
                BankingMenu.Access.KIOSK,
                BankingMenu.Access.CARD)
            .contains(access)) BankingHome.open(player, holder[0]);
    if (holder[0] != null && access == BankingMenu.Access.REGISTER)
      holder[0].publish(
          "home", List.of(), UiText.t("ui.bank.register_services.176010"), registerDestinations());
    if (holder[0] != null && access == BankingMenu.Access.ADMIN)
      BankingAccounts.action(player, holder[0], "settings", "");
  }

  public static void action(ServerPlayer player, BankingMenu menu, String action, String value) {
    if (!menu.valid(player) || player.server.getPlayerList().getPlayer(player.getUUID()) != player)
      return;
    if (!menu.allows(action)) {
      report(
          menu,
          menu.page(),
          menu.lines(),
          UiText.t("ui.bank.this_control_is_not_available_here.a3df37"));
      return;
    }
    if (action.equals("banking")) {
      var anchor = new BlockPos(menu.anchor().x(), menu.anchor().y(), menu.anchor().z());
      if (!(player.serverLevel().getBlockEntity(anchor) instanceof TerminalBlockEntity entity))
        return;
      var access =
          switch (entity.kind()) {
            case ATM -> BankingMenu.Access.ATM;
            case TELLER -> BankingMenu.Access.TELLER;
            case KIOSK -> BankingMenu.Access.KIOSK;
            case REGISTER -> BankingMenu.Access.REGISTER;
          };
      open(player, menu.anchor(), menu.generation(), access);
      return;
    }
    if (menu.worldSetup()) {
      if (action.equals("setup")
          && !value.isBlank()
          && Economy.chooseWorld(player, menu.generation(), value.trim().toUpperCase(Locale.ROOT)))
        player.closeContainer();
      return;
    }
    if (Set.of("retry", "bank_retry").contains(action)) {
      if (player.getPersistentData().getCompound("economy_banking_pending").hasUUID("operation")) {
        BankingAccounts.action(player, menu, "bank_retry", value);
        return;
      }
      if (player.getPersistentData().getCompound(PENDING).hasUUID("operation")) action = "retry";
    }
    if (menu.remote()
        && Set.of(
                "deposit",
                "deposit_preview",
                "deposit_confirm",
                "withdraw",
                "withdraw_preview",
                "withdraw_confirm",
                "shop",
                "buy_preview",
                "buy_confirm",
                "settings",
                "configure",
                "price")
            .contains(action)) {
      report(
          menu,
          "home",
          List.of(UiText.t("ui.bank.visit_a_physical_terminal_for_this_action.131f89")),
          UiText.t("ui.bank.terminal_required.c2a487"));
      return;
    }
    if (player.getPersistentData().getCompound(PENDING).hasUUID("operation")
        || player.getPersistentData().getCompound("economy_banking_pending").hasUUID("operation")) {
      if (!Set.of(
              "auto_home",
              "auto_cards",
              "auto_loans",
              "auto_next",
              "auto_select",
              "finance_home",
              "finance_wealth",
              "finance_report",
              "finance_report_next",
              "finance_cases",
              "finance_cases_next",
              "finance_debt_home",
              "finance_debt_next",
              "finance_debt_case",
              "finance_loan_home",
              "finance_loan_next",
              "finance_loan_view",
              "finance_loan_schedule",
              "finance_loan_sched_next",
              "finance_loan_inst",
              "finance_loan_inst_next",
              "retry",
              "bank_retry",
              "home",
              "payments",
              "accounts",
              "manage_accounts",
              "history",
              "fx",
              "claims",
              "claims_next",
              "credit_profile",
              "credit_history",
              "credit_next",
              "credit_facilities",
              "credit_facilities_next",
              "credit_facility",
              "credit_statements",
              "credit_statements_next")
          .contains(action)) {
        report(
            menu,
            menu.page(),
            menu.lines(),
            UiText.t("ui.bank.resolve_the_original_pending_operation_with_re.f260ba"));
        return;
      }
    }
    if (!menu.busy()
        && menu.page().equals("deposit")
        && Set.of(
                "home",
                "payments",
                "manage_accounts",
                "setup",
                "accounts",
                "history",
                "fx",
                "withdraw",
                "shop",
                "claims",
                "settings",
                "credit_profile",
                "credit_history",
                "credit_facilities",
                "credit_facilities_next",
                "credit_facility",
                "credit_statements",
                "credit_statements_next")
            .contains(action)) {
      var prior = FLOWS.get(menu);
      if (prior != null && prior.reservation != null) {
        report(
            menu,
            "deposit",
            menu.lines(),
            UiText.t("ui.bank.resolve_the_current_reservation_first.3fab5d"));
        return;
      }
      returnInputs(player, menu);
    }
    if (FinanceActions.handles(action)) {
      if (!menu.busy() && menu.page().equals("deposit")) {
        var prior = FLOWS.get(menu);
        if (prior != null && prior.reservation != null) return;
        returnInputs(player, menu);
      }
      if (action.equals("finance_home")) {
        var choices = new ArrayList<BankingNetwork.Choice>();
        choices.add(
            new BankingNetwork.Choice(
                "credit_profile", "", UiText.t("ui.bank.credit_profile.a973eb"), false));
        choices.add(
            new BankingNetwork.Choice(
                "credit_facilities",
                "",
                UiText.t("ui.bank.credit_facilities_and_statements.afbc14"),
                false));
        choices.add(
            new BankingNetwork.Choice(
                "finance_credit_pay", "", UiText.t("ui.bank.repay_credit_card.9bf204"), false));
        choices.add(
            new BankingNetwork.Choice(
                "finance_credit_cards", "", UiText.t("ui.bank.credit_tokens.38dfb2"), false));
        choices.add(
            new BankingNetwork.Choice(
                "finance_loan_home", "", UiText.t("ui.bank.loans_and_repayments.192237"), false));
        choices.add(
            new BankingNetwork.Choice(
                "auto_home", "", UiText.t("ui.bank.automatic_payments.0d1f62"), false));
        choices.add(
            new BankingNetwork.Choice(
                "finance_wealth", "", UiText.t("ui.bank.net_worth_and_tier.1d085d"), false));
        choices.add(
            new BankingNetwork.Choice(
                "finance_report", "", UiText.t("ui.bank.credit_report.f5bc6d"), false));
        choices.add(
            new BankingNetwork.Choice(
                "finance_debt_home", "", UiText.t("ui.bank.collection_payments.ef18b2"), false));
        if (menu.allows("finance_od_home"))
          choices.add(
              new BankingNetwork.Choice(
                  "finance_od_home", "", UiText.t("ui.finance.label.overdraft"), false));
        if (menu.allows("credit_advance"))
          choices.add(
              new BankingNetwork.Choice(
                  "credit_advance", "", UiText.t("ui.bank.credit_cash_advance.dab8f6"), false));
        if (menu.access() == BankingMenu.Access.KIOSK) {
          choices.add(
              new BankingNetwork.Choice(
                  "finance_credit_apply", "", UiText.t("ui.bank.apply_for_credit.98f7be"), false));
          choices.add(
              new BankingNetwork.Choice(
                  "finance_loan_apply", "", UiText.t("ui.bank.apply_for_a_loan.fae248"), false));
          choices.add(
              new BankingNetwork.Choice(
                  "finance_credit_raise",
                  "",
                  UiText.t("ui.bank.request_credit_limit_increase.b9a241"),
                  false));
        }
        menu.publish(
            "finance_home",
            List.of(UiText.t("ui.bank.choose_a_service_applications_and_card_replace.c4f0dd")),
            UiText.t("ui.bank.bank_and_credit_services.c9cdbc"),
            choices);
      } else if (AutopayMenus.handles(action)) AutopayMenus.action(player, menu, action, value);
      else if (OverdraftMenus.handles(action)) OverdraftMenus.action(player, menu, action, value);
      else if (CreditMenus.handles(action)) CreditMenus.action(player, menu, action, value);
      else if (LoanMenus.handles(action)) LoanMenus.action(player, menu, action, value);
      else if (FinanceCollections.handles(action))
        FinanceCollections.action(player, menu, action, value);
      else if (FinanceReports.handles(action)) FinanceReports.action(player, menu, action, value);
      else CreditUse.action(player, menu, action, value);
      return;
    }
    if (TerminalMenus.handles(action)) {
      TerminalMenus.action(player, menu, action, value);
      return;
    }
    if (BankingHistory.handles(action)) {
      BankingHistory.action(player, menu, action, value);
      return;
    }
    if (BankingAccounts.handles(action)) {
      BankingAccounts.action(player, menu, action, value);
      return;
    }
    if (BankingWithdrawals.handles(action)) {
      BankingWithdrawals.action(player, menu, action, value);
      return;
    }
    var flow = FLOWS.computeIfAbsent(menu, ignored -> new Flow());
    if (menu.busy() && !action.equals("retry")) return;
    try {
      switch (action) {
        case "home" -> {
          if (menu.access() == BankingMenu.Access.REGISTER) {
            menu.setBusy(false);
            menu.publish(
                "home",
                List.of(),
                UiText.t("ui.bank.register_services.176010"),
                registerDestinations());
          } else BankingHome.open(player, menu);
        }
        case "payments" -> BankingHome.payments(menu);
        case "deposit" -> {
          flow.deposit = null;
          BankingAccounts.cashAccounts(
              player,
              menu,
              "deposit",
              List.of(
                  UiText.t("ui.bank.add_cash_in_the_left_slots.6dfb45"),
                  UiText.t("ui.bank.review_before_depositing.c01489")),
              UiText.t("ui.bank.add_cash_then_choose_review.2fb48a"));
        }
        case "deposit_preview" -> depositPreview(player, menu, flow);
        case "deposit_confirm" -> depositConfirm(player, menu, flow);
        case "shop", "buy_preview" -> salePreview(player, menu, flow);
        case "buy_confirm" -> saleConfirm(player, menu, flow);
        case "price" -> price(player, menu, value);
        case "claims", "claims_next" -> claims(player, menu, flow, action.endsWith("next"));
        case "collect" -> {
          var text = value.trim();
          var id =
              text.matches("[1-8]")
                  ? flow.claimOrder.get(Integer.parseInt(text) - 1)
                  : UUID.fromString(text);
          if (!flow.claims.contains(id))
            throw new IllegalArgumentException("Select a claim from the current page.");
          withContext(player, menu, context -> deliver(player, menu, context.world(), id));
        }
        case "retry" -> {
          lookupPending(player, menu);
        }
        case "cancel" -> {
          flow.deposit = null;
          flow.quote = null;
          returnInputs(player, menu);
          menu.setBusy(false);
          if (menu.access() == BankingMenu.Access.REGISTER)
            menu.publish(
                "home",
                List.of(UiText.t("ui.bank.your_unsubmitted_items_were_returned.3b128c")),
                UiText.t("ui.bank.cancelled.a1bf92"),
                registerDestinations());
          else BankingHome.open(player, menu);
        }
        default ->
            report(menu, menu.page(), menu.lines(), UiText.t("ui.bank.unsupported_action.6f84b4"));
      }
    } catch (RuntimeException failure) {
      fail(menu, failure);
    }
  }

  private static List<BankingNetwork.Choice> registerDestinations() {
    return List.of(
        new BankingNetwork.Choice("shop", "", UiText.t("ui.bank.register_purchase.1e97c3"), false),
        new BankingNetwork.Choice(
            "claims", "", UiText.t("ui.bank.collect_retained_items.b6b886"), false));
  }

  private static void review(
      BankingMenu menu, String page, List<String> lines, String status, String confirm) {
    if (!(menu.player() instanceof ServerPlayer player) || !menu.valid(player)) return;
    menu.setBusy(false);
    if (lines.size() > 32
        || lines.stream().anyMatch(line -> !UiText.isDeferred(line) && line.length() > 256)) {
      menu.publish(
          page,
          List.of(UiText.t("ui.bank.review_terms_exceed_this_page_s_display_bound_.e76fe2")),
          UiText.t("ui.bank.review_unavailable.73500b"),
          List.of(),
          BankingOperation.View.of(
              BankingOperation.State.DECLINED, "screen.economy.operation.review_unavailable"),
          null);
      return;
    }
    var choices =
        new ArrayList<>(
            menu.choices().stream()
                .filter(c -> !BankingOperation.confirmation(c.action()))
                .toList());
    choices.add(new BankingNetwork.Choice(confirm, "", UiText.t("ui.bank.confirm.04a212"), false));
    menu.publish(page, lines, status, choices);
  }

  public static void report(BankingMenu menu, String page, List<String> lines, String status) {
    if (!(menu.player() instanceof ServerPlayer player) || !menu.valid(player)) return;
    menu.setBusy(false);
    menu.publish(page, lines, status);
  }

  private static String money(Money value) {
    return UiText.money(value);
  }

  private static String shortText(String value, int maximum) {
    return value == null
        ? UiText.t("ui.bank.unavailable.2c9c1f")
        : UiText.isDeferred(value) ? value : value.substring(0, Math.min(maximum, value.length()));
  }

  static void fail(BankingMenu menu, Throwable error) {
    var cause = error;
    while (cause.getCause() != null) cause = cause.getCause();
    com.mojang.logging.LogUtils.getLogger()
        .warn(
            "event=BANKING_REQUEST_FAILED session={} reason={}",
            menu.session(),
            shortText(cause.getMessage(), 128));
    var player = menu.player() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
    String status =
        player != null && services(player).debugGui()
            ? shortText(cause.getMessage(), 200)
            : menu.page().equals("deposit")
                ? depositProblem(cause)
                : UiText.t("ui.bank.request_could_not_be_completed_try_again_or_us.f4fa69");
    if (player == null || !menu.valid(player)) return;
    menu.setBusy(false);
    menu.publish(
        menu.page(),
        List.of(UiText.t("ui.bank.the_current_request_could_not_be_completed_pre.44c91e")),
        status,
        List.of(),
        BankingOperation.View.of(
            BankingOperation.State.DECLINED, "screen.economy.operation.declined"),
        null);
  }

  static String depositProblem(Throwable failure) {
    String code = failure.getMessage() == null ? "" : failure.getMessage();
    return switch (code) {
      case "PRICING_BATCH_LIMIT" ->
          UiText.t("ui.bank.review_at_most_four_exchange_currencies_per_de.4dc3c3");
      case "FX_CASH_BUDGET_UNREPRESENTABLE" ->
          UiText.t("ui.bank.this_cash_total_cannot_exactly_cover_the_excha.4494e2");
      case "TIER2_UNAVAILABLE", "SERVICE_ACCOUNT_UNAVAILABLE" ->
          UiText.t("ui.bank.this_bank_requires_current_tier_2_qualificatio.1ba8c4");
      case "PRICING_QUOTE_STALE", "CASH_PRICING_STALE", "PRICING_CONTEXT_UNAVAILABLE" ->
          UiText.t("ui.bank.account_pricing_changed_or_is_unavailable_revi.fd999e");
      case "DEPOSIT_MODE" ->
          UiText.t("ui.bank.deposits_are_disabled_on_this_atm_its_owner_ca.706e0e");
      case "DEPOSIT_DENOMINATION" -> UiText.t("ui.bank.386c9aa2e8");
      case "DEPOSIT_ACCOUNT", "DEPOSIT_ACCOUNT_STALE" ->
          UiText.t("ui.bank.choose_an_open_account_then_review_again_creat.02a29e");
      case "DEPOSIT_TERMINAL", "DEPOSIT_TERMINAL_STALE" ->
          UiText.t("ui.bank.the_terminal_changed_or_is_unavailable_reopen_.f19998");
      case "DEPOSIT_FX_STALE" ->
          UiText.t("ui.bank.exchange_rates_changed_or_are_unavailable_revi.a44656");
      case "DEPOSIT_SLOT_COUNT" ->
          UiText.t("ui.bank.place_cash_in_the_deposit_slots_before_choosin.fcc4d9");
      case "DEPOSIT_WORLD" ->
          UiText.t("ui.bank.this_marked_cash_belongs_to_another_world_and_.47bfbb");
      case "DEPOSIT_UNRECOGNIZED", "DEPOSIT_CASH_RESERVED" ->
          UiText.t("ui.bank.this_marked_cash_has_no_available_issued_value.51af6e");
      case "DEPOSIT_COMPONENT_BUDGET", "DEPOSIT_FIELD", "JOURNAL_REQUEST_BOUNDS" ->
          UiText.t("ui.bank.this_batch_has_too_much_item_data_try_fewer_ca.53ea46");
      case "DEPOSIT_INPUT_CHANGED" ->
          UiText.t("ui.bank.the_cash_changed_after_review_review_the_curre.1c5a4f");
      default -> UiText.t("ui.bank.10d54ccba3");
    };
  }

  static BlockPos position(BankingMenu menu) {
    return new BlockPos(menu.anchor().x(), menu.anchor().y(), menu.anchor().z());
  }

  static void withContext(
      ServerPlayer player, BankingMenu menu, Consumer<LedgerService.BankingContext> action) {
    menu.setBusy(true);
    var revision = menu.revision();
    services(player)
        .ledger()
        .bankingContext(actor(player), menu.anchor(), BankingAccounts.selected(menu))
        .whenComplete(
            (context, error) -> {
              if (!menu.valid(player) || menu.revision() != revision) return;
              if (error != null) {
                fail(menu, error);
                return;
              }
              try {
                FLOWS.computeIfAbsent(menu, ignored -> new Flow()).context = context;
                action.accept(context);
              } catch (RuntimeException failure) {
                fail(menu, failure);
              }
            });
  }

  public static Journal.Intent intent(
      ServerPlayer player, Journal.Kind kind, Journal.CanonicalRequest request) {
    return new Journal.Intent(
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        actor(player),
        kind,
        request,
        null,
        null);
  }

  /**
   * Internal reserve/save/commit continuations retain exact identity; UI retry only looks up
   * originals.
   */
  public static void submit(
      ServerPlayer player,
      BankingMenu menu,
      Journal.Intent intent,
      Supplier<JournalService> factory,
      Consumer<Journal.Outcome> completed) {
    var flow = FLOWS.computeIfAbsent(menu, ignored -> new Flow());
    if (flow.inFlight) return;
    boolean reachedSubmission = false;
    var oldPending = player.getPersistentData().getCompound(PENDING);
    boolean retainedOriginal =
        oldPending.hasUUID("operation")
            && oldPending.getUUID("operation").equals(intent.operationId().value());
    try {
      if (player.getPersistentData().getCompound("economy_banking_pending").hasUUID("operation"))
        throw new IllegalStateException("Resolve the pending banking operation first.");
      var pending = player.getPersistentData().getCompound(PENDING);
      if (pending.hasUUID("operation")
          && !pending.getUUID("operation").equals(intent.operationId().value()))
        throw new IllegalStateException("Resolve the pending operation before starting another.");
      if (!retainedOriginal && intent.kind().operationType().equals("economy:reserve"))
        flow.financialOutcome = null;
      var journal =
          factory
              .get(); // A local pre-admission failure must never manufacture an unknown operation.
      var tag = new CompoundTag();
      tag.putUUID("world", services(player).worldId().value());
      tag.putUUID("actor", player.getUUID());
      tag.putUUID("operation", intent.operationId().value());
      tag.putUUID("request", intent.requestId().value());
      tag.putString("service", intent.kind().service());
      tag.putString("type", intent.kind().operationType());
      var fields = new CompoundTag();
      intent.request().fields().forEach(fields::putString);
      tag.put("fields", fields);
      player.getPersistentData().put(PENDING, tag);
      menu.setBusy(true);
      flow.retry = () -> submit(player, menu, intent, factory, completed);
      inputsChanged(player, menu);
      boolean cleanup =
          Set.of(
                  "economy:cancel",
                  "economy:quarantine",
                  "economy:saved",
                  "economy:claim",
                  "economy:delivery_saved",
                  "economy:begin_delivery",
                  "economy:collect",
                  "economy:complete")
              .contains(intent.kind().operationType());
      if (player.server.getPlayerList().getPlayer(player.getUUID()) == player && !cleanup)
        BankingSaves.player(player, services(player).worldId(), intent.operationId().value());
      else if (player.server.getPlayerList().getPlayer(player.getUUID()) != player
          && !Set.of(
                  "economy:cancel",
                  "economy:quarantine",
                  "economy:saved",
                  "economy:claim",
                  "economy:delivery_saved")
              .contains(intent.kind().operationType()))
        throw new IllegalStateException("Player session ended before admission");
      if (menu.valid(player))
        menu.processing(intent.operationId().value(), intent.requestId().value());
      flow.inFlight = true;
      reachedSubmission = true;
      journal
          .submit(intent)
          .completion()
          .whenComplete(
              (delivery, error) -> {
                flow.inFlight = false;
                if (error != null) {
                  fail(menu, error);
                  return;
                }
                var outcome = delivery.outcome();
                if (outcome.status() == Journal.Status.COMMITTED
                    || outcome.status() == Journal.Status.DURABLY_REJECTED) {
                  var pendingNow = player.getPersistentData().getCompound(PENDING);
                  if (pendingNow.hasUUID("operation")
                      && pendingNow.getUUID("operation").equals(intent.operationId().value()))
                    player.getPersistentData().remove(PENDING);
                  flow.retry = null;
                  boolean financial =
                      intent.kind().equals(Deposits.Action.COMMIT.kind())
                          || intent.kind().equals(Withdrawals.Action.COMMIT.kind())
                          || intent.kind().equals(RegisterSales.Action.COMMIT.kind());
                  if (financial && outcome.status() == Journal.Status.COMMITTED)
                    flow.financialOutcome = outcome;
                  if (flow.financialOutcome != null) menu.result(flow.financialOutcome);
                  else if (outcome.status() == Journal.Status.DURABLY_REJECTED
                      || intent.kind().equals(RegisterSales.Action.CONFIGURE.kind()))
                    menu.result(outcome);
                  try {
                    completed.accept(outcome);
                  } catch (RuntimeException failure) {
                    fail(menu, failure);
                  }
                } else
                  report(
                      menu,
                      menu.page(),
                      menu.lines(),
                      outcome.result().code()
                          + UiText.t("ui.bank.retry_preserves_operation.864839")
                          + intent.operationId().value());
              });
    } catch (RuntimeException error) {
      flow.inFlight = false;
      if (!reachedSubmission && !retainedOriginal) {
        var current = player.getPersistentData().getCompound(PENDING);
        if (current.hasUUID("operation")
            && current.getUUID("operation").equals(intent.operationId().value()))
          player.getPersistentData().remove(PENDING);
        flow.retry = null;
        if (intent.kind().operationType().equals("economy:reserve")
            && Objects.equals(flow.reservation, intent.operationId().value())) {
          flow.reservation = null;
          flow.ownsInputs = false;
        }
      }
      fail(menu, error);
    }
  }

  private static void lookupPending(ServerPlayer player, BankingMenu menu) {
    var tag = player.getPersistentData().getCompound(PENDING);
    if (tag.hasUUID("world")
        && (!tag.getUUID("world").equals(services(player).worldId().value())
            || !tag.getUUID("actor").equals(player.getUUID())))
      throw new IllegalStateException(
          "Pending operation belongs to a different saved world or actor.");
    if (!tag.hasUUID("operation")) {
      report(menu, menu.page(), menu.lines(), UiText.t("ui.bank.no_pending_operation.fc3c36"));
      return;
    }
    var fields = new TreeMap<String, String>();
    var stored = tag.getCompound("fields");
    stored.getAllKeys().forEach(key -> fields.put(key, stored.getString(key)));
    var original =
        new Journal.Intent(
            new OperationId(tag.getUUID("operation")),
            new RequestId(tag.getUUID("request")),
            actor(player),
            new Journal.Kind(tag.getString("service"), tag.getString("type")),
            new Journal.CanonicalRequest(1, fields),
            null,
            null);
    var audience = FinanceMenuSupport.audience(player, menu);
    menu.setBusy(true);
    services(player)
        .journal((who, kind) -> who.equals(actor(player)), Map.of(), null)
        .lookup(original)
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              if (error != null) {
                fail(menu, error);
                return;
              }
              if (found.isPresent()
                  && (found.orElseThrow().status() == Journal.Status.COMMITTED
                      || found.orElseThrow().status() == Journal.Status.DURABLY_REJECTED)) {
                player.getPersistentData().remove(PENDING);
                menu.result(found.orElseThrow());
                try {
                  BankingSaves.player(
                      player, services(player).worldId(), original.operationId().value());
                } catch (Exception ignored) {
                  /* Original saved marker replays safely. */
                }
                report(
                    menu,
                    "claims",
                    List.of(
                        UiText.t("ui.bank.original_operation.cf0953")
                            + original.operationId().value(),
                        found.orElseThrow().result().code(),
                        UiText.t("ui.bank.inspect_claims_for_retained_delivery_unknown_s.4d1909")),
                    UiText.t("ui.bank.original_outcome_resolved.ef834f"));
              } else if (found.isEmpty()) {
                player.getPersistentData().put("economy_banking_cash_last_absent", tag.copy());
                player.getPersistentData().remove(PENDING);
                try {
                  BankingSaves.player(
                      player, services(player).worldId(), original.operationId().value());
                } catch (Exception ignored) {
                  /* Original saved marker replays safely. */
                }
                menu.setBusy(false);
                menu.publish(
                    "claims",
                    List.of(
                        UiText.t("ui.bank.the_ordered_original_outcome_lookup_confirms_n.98b7c7"),
                        UiText.t("ui.bank.no_replacement_operation_was_submitted_retaine.92af59")),
                    UiText.t("ui.bank.original_operation_was_not_committed.e8bcf8"),
                    List.of(),
                    BankingOperation.View.original(
                        BankingOperation.State.EXPIRED_STALE,
                        original.operationId().value(),
                        original.requestId().value(),
                        null),
                    null);
              } else
                report(
                    menu,
                    menu.page(),
                    menu.lines(),
                    UiText.t("ui.bank.the_original_request_conflicts_its_identity_is.95f3c9"));
            });
  }

  private static void claims(ServerPlayer player, BankingMenu menu, Flow flow, boolean next) {
    if (next && flow.claimCursor == null) {
      report(menu, "claims", menu.lines(), UiText.t("ui.bank.end_of_claims.52fed5"));
      return;
    }
    if (!next) flow.claimCursor = null;
    menu.setBusy(true);
    services(player)
        .ledger()
        .custodyClaims(actor(player), flow.claimCursor, 8)
        .whenComplete(
            (page, error) -> {
              if (error != null) {
                fail(menu, error);
                return;
              }
              if (!menu.valid(player)) return;
              flow.claimOrder = page.cases().stream().map(Custody.Case::id).toList();
              flow.claims =
                  page.cases().stream()
                      .map(Custody.Case::id)
                      .collect(java.util.stream.Collectors.toUnmodifiableSet());
              flow.claimCursor = page.nextCursor();
              var choices = new ArrayList<BankingNetwork.Choice>();
              for (var value : page.cases()) {
                String kind = value.terms().items().split("[|\\n]", 2)[0];
                String label = claimLabel(value, kind);
                choices.add(
                    new BankingNetwork.Choice("collect", value.id().toString(), label, false));
              }
              menu.setBusy(false);
              menu.publish(
                  "claims",
                  choices.isEmpty()
                      ? List.of(
                          UiText.t("ui.bank.you_have_no_items_waiting_to_be_collected.de5537"))
                      : List.of(
                          UiText.t("ui.bank.click_a_batch_to_put_it_in_your_inventory.430b5f"),
                          UiText.t(
                              "ui.bank.make_room_for_the_complete_batch_before_collec.262cb0")),
                  choices.isEmpty()
                      ? UiText.t("ui.bank.nothing_to_collect.ec848f")
                      : UiText.t("ui.bank.click_a_batch_to_collect_make_room_in_your_inv.da46f8"),
                  choices);
            });
  }

  private static String claimLabel(Custody.Case value, String kind) {
    String label =
        switch (kind) {
          case "register-goods-v1" -> UiText.t("ui.bank.purchased_items.b83dce");
          case "withdrawal-cash-v1" -> UiText.t("ui.bank.cash_withdrawal.af00f5");
          case "deposit-original-v1" -> UiText.t("ui.bank.returned_deposit_cash.e9e1d3");
          case "issued-card-v1" -> UiText.t("ui.bank.issued_card.5fcbe9");
          default -> UiText.t("ui.bank.returned_items.3845c4");
        };
    try {
      var fields = value.terms().items().split("[|\\n]", -1);
      int count =
          switch (kind) {
            case "register-goods-v1" -> Integer.parseInt(fields[1]);
            case "deposit-original-v1" -> Integer.parseInt(fields[4]);
            case "withdrawal-cash-v1" ->
                WithdrawalDelivery.claims(value).stream().mapToInt(CashIssuance.Claim::count).sum();
            default -> 0;
          };
      return count > 0
          ? label
              + " ("
              + count
              + (count == 1 ? UiText.t("ui.bank.item.3d558f") : UiText.t("ui.bank.items.ac8615"))
          : label;
    } catch (IllegalArgumentException | IndexOutOfBoundsException unavailable) {
      return label;
    }
  }

  private static void depositPreview(ServerPlayer player, BankingMenu menu, Flow flow) {
    withContext(
        player,
        menu,
        context -> {
          if (context.account() == null) throw new IllegalStateException("DEPOSIT_ACCOUNT");
          if (context.terminal() == null) throw new IllegalStateException("DEPOSIT_TERMINAL");
          if (context.fx() == null) throw new IllegalStateException("DEPOSIT_FX_STALE");
          var binding =
              new Deposits.Binding(
                  context.account().id().value(),
                  context.account().revision(),
                  context.account().currency(),
                  menu.anchor(),
                  menu.generation(),
                  context.terminal().revision(),
                  context.fx().revision());
          var slots = DepositInventory.snapshot(player, menu.depositInputs(), context.world());
          boolean live = menu.valid(player) && menu.page().equals("deposit");
          services(player)
              .ledger()
              .previewDeposit(actor(player), binding, slots, (b, reads) -> live)
              .whenComplete(
                  (preview, error) -> {
                    if (error != null) {
                      fail(menu, error);
                      return;
                    }
                    if (!menu.valid(player)) return;
                    flow.deposit = preview;
                    var lines = new ArrayList<String>();
                    lines.add(UiText.t("ui.bank.bank_credit.ff2d69") + money(preview.credit()));
                    for (var group : preview.groups()) {
                      lines.add(
                          group.count()
                              + UiText.t("ui.bank.items.7bb96e")
                              + money(group.subtotal())
                              + " → "
                              + (group.skipped()
                                  ? UiText.t("ui.bank.returned_unchanged.236bcd")
                                  : money(group.credit())));
                      if (group.pricing() != null && !group.skipped()) {
                        var price = group.pricing().split("\\|", -1);
                        lines.add(
                            UiText.t("ui.bank.source_principal.90f491")
                                + money(new Money(group.currency(), Long.parseLong(price[1])))
                                + UiText.t("ui.bank.9e5ab828c0")
                                + money(new Money(group.currency(), Long.parseLong(price[3]))));
                      }
                    }
                    if (preview.credit().minorUnits() == 0) {
                      boolean chfAccount = preview.credit().currency() == EconomyCurrency.CHF;
                      boolean chfGroup =
                          preview.groups().stream()
                              .anyMatch(group -> group.currency() == EconomyCurrency.CHF);
                      if (chfAccount || chfGroup) {
                        String minimum = money(new Money(preview.credit().currency(), 1));
                        lines.add(
                            (chfAccount
                                    ? UiText.t("ui.bank.each_currency_group.0a57ec")
                                    : UiText.t("ui.bank.each_chf_group.44294c"))
                                + UiText.t("ui.bank.must_credit_at_least.0f7939")
                                + minimum
                                + (chfAccount
                                    ? "."
                                    : UiText.t(
                                        "ui.bank.other_legacy_groups_retain_whole_unit_rounding.9228e6")));
                        report(
                            menu,
                            "deposit",
                            lines,
                            UiText.t(
                                "ui.bank.no_credit_yet_add_more_cash_to_a_currency_grou.c9dd9a"));
                      } else {
                        lines.add(
                            UiText.t("ui.bank.each_currency_group_must_credit_at_least_1.a389f5")
                                + preview.credit().currency().code()
                                + UiText.t("ui.bank.add_more_cash.44da20"));
                        report(
                            menu,
                            "deposit",
                            lines,
                            UiText.t("ui.bank.no_credit_yet_add_cash_worth_at_least_1.a491fc")
                                + preview.credit().currency().code()
                                + ".");
                      }
                    } else
                      review(
                          menu,
                          "deposit",
                          lines,
                          UiText.t("ui.bank.preview_ready_confirm_to_deposit_this_cash.6f577c"),
                          "deposit_confirm");
                    flow.depositRevision = menu.revision();
                  });
        });
  }

  private static void depositConfirm(ServerPlayer player, BankingMenu menu, Flow flow) {
    var preview =
        Objects.requireNonNull(flow.deposit, UiText.t("ui.bank.preview_the_deposit_first.5d61ea"));
    if (!menu.page().equals("deposit")
        || menu.revision() != flow.depositRevision
        || flow.reservation != null)
      throw new IllegalStateException("This preview expired. Preview the deposit again.");
    if (!DepositInventory.snapshot(player, menu.depositInputs(), flow.context.world())
        .equals(preview.slots())) throw new IllegalStateException("Inputs changed; preview again.");
    var reserve = intent(player, Deposits.Action.RESERVE.kind(), Deposits.reserveRequest(preview));
    flow.reservation = reserve.operationId().value();
    flow.ownsInputs = true;
    submit(
        player,
        menu,
        reserve,
        () -> depositJournal(player, menu, Deposits.Action.RESERVE, false, false),
        outcome -> {
          if (outcome.status() != Journal.Status.COMMITTED) {
            flow.reservation = null;
            flow.ownsInputs = false;
            if (!menu.valid(player)
                && player.server.getPlayerList().getPlayer(player.getUUID()) == player)
              returnInputs(player, menu);
            report(menu, "deposit", menu.lines(), outcome.result().code());
            return;
          }
          try {
            if (player.server.getPlayerList().getPlayer(player.getUUID()) != player) {
              quarantineDeposit(player, menu, flow);
              return;
            }
            DepositInventory.reserve(
                player,
                menu.depositInputs(),
                flow.context.world(),
                flow.reservation,
                preview.slots());
            flow.ownsInputs = false;
            inputsChanged(player, menu);
            var proof = BankingSaves.player(player, flow.context.world(), flow.reservation);
            if (!menu.valid(player)) {
              cancelDeposit(player, menu, flow, proof);
              return;
            }
            var saved =
                intent(
                    player,
                    Deposits.Action.SAVED.kind(),
                    Deposits.request(flow.reservation, proof.token(), "saved exact originals"));
            submit(
                player,
                menu,
                saved,
                () -> depositJournal(player, menu, Deposits.Action.SAVED, true, true),
                savedOutcome -> {
                  if (savedOutcome.status() != Journal.Status.COMMITTED) {
                    report(menu, "deposit", menu.lines(), savedOutcome.result().code());
                    return;
                  }
                  if (!menu.valid(player)) {
                    cancelDeposit(player, menu, flow, proof);
                    return;
                  }
                  var commit =
                      intent(
                          player,
                          Deposits.Action.COMMIT.kind(),
                          Deposits.request(flow.reservation, proof.token(), "confirmed deposit"));
                  submit(
                      player,
                      menu,
                      commit,
                      () -> depositJournal(player, menu, Deposits.Action.COMMIT, false, true),
                      committed -> {
                        if (committed.status() != Journal.Status.COMMITTED) {
                          cancelDeposit(player, menu, flow, proof);
                          return;
                        }
                        DepositInventory.consumeCommitted(
                            player, flow.reservation, preview.payable());
                        BankingSaves.player(player, flow.context.world(), flow.reservation);
                        finishDepositReturns(
                            player,
                            menu,
                            flow,
                            returns ->
                                report(
                                    menu,
                                    "deposit",
                                    depositReturnMessages(returns),
                                    UiText.t(
                                        "ui.bank.thank_you_for_using_our_banking_service.61cd28")));
                      });
                });
          } catch (RuntimeException error) {
            quarantineDeposit(player, menu, flow);
            fail(menu, error);
          }
        });
  }

  private static JournalService depositJournal(
      ServerPlayer player,
      BankingMenu menu,
      Deposits.Action action,
      boolean cleanup,
      boolean proof) {
    boolean live = cleanup || (menu.valid(player) && menu.page().equals("deposit"));
    var owner = actor(player);
    return services(player)
        .ledger()
        .deposits(
            action,
            (who, kind) -> who.equals(owner),
            (a, i, current, reads) ->
                live && i.actor().equals(owner) && current.owner().equals(owner),
            (a, i, current, evidence, reads) -> proof,
            null);
  }

  private static void cancelDeposit(
      ServerPlayer player, BankingMenu menu, Flow flow, BankingSaves.Proof proof) {
    submit(
        player,
        menu,
        intent(
            player,
            Deposits.Action.CANCEL.kind(),
            Deposits.request(flow.reservation, proof.token(), "closed before settlement")),
        () -> depositJournal(player, menu, Deposits.Action.CANCEL, true, true),
        outcome -> {
          if (outcome.status() == Journal.Status.COMMITTED)
            finishDepositReturns(
                player,
                menu,
                flow,
                returns ->
                    report(
                        menu,
                        "deposit",
                        List.of(
                            UiText.t(
                                "ui.bank.your_cash_was_returned_or_is_waiting_in_collec.8fd57f")),
                        UiText.t("ui.bank.cancelled.a1bf92")));
          else report(menu, "deposit", menu.lines(), outcome.result().code());
        });
  }

  private static void quarantineDeposit(ServerPlayer player, BankingMenu menu, Flow flow) {
    if (flow.reservation == null) return;
    submit(
        player,
        menu,
        intent(
            player,
            Deposits.Action.QUARANTINE.kind(),
            Deposits.request(flow.reservation, "unknown", "save or physical effect uncertain")),
        () -> depositJournal(player, menu, Deposits.Action.QUARANTINE, true, false),
        outcome ->
            report(
                menu,
                "claims",
                List.of(UiText.t("ui.bank.uncertain_originals_are_held_for_operator_evid.1ecd6e")),
                outcome.result().code()));
  }

  static List<String> depositReturnMessages(List<DepositReturns.Return> returns) {
    var lines = new ArrayList<String>();
    if (returns.stream().anyMatch(value -> value.delivered() > 0))
      lines.add(UiText.t("ui.bank.unredeemable_bills_were_returned.834d31"));
    if (returns.stream().anyMatch(value -> value.state() == DepositReturns.State.CLAIMED))
      lines.add(UiText.t("ui.bank.remaining_bills_are_waiting_in_collect_items.63198a"));
    return List.copyOf(lines);
  }

  private static void finishDepositReturns(
      ServerPlayer player,
      BankingMenu menu,
      Flow flow,
      Consumer<List<DepositReturns.Return>> finished) {
    services(player)
        .ledger()
        .depositReturns(actor(player), flow.reservation)
        .whenComplete(
            (returns, error) -> {
              if (error != null) {
                fail(menu, error);
                return;
              }
              var pending =
                  returns.stream()
                      .filter(value -> value.state() == DepositReturns.State.PENDING)
                      .findFirst();
              if (pending.isEmpty()) {
                try {
                  DepositInventory.clearDelivered(player, flow.reservation);
                } catch (IllegalStateException claimedOriginals) {
                  /* Claims retain their exact original stacks. */
                }
                BankingSaves.player(player, flow.context.world(), flow.reservation);
                flow.reservation = null;
                flow.deposit = null;
                finished.accept(returns);
                return;
              }
              var value = pending.orElseThrow();
              var begin =
                  intent(
                      player,
                      DepositReturns.Action.BEGIN.kind(),
                      DepositReturns.request(
                          value.deposit(),
                          value.slot(),
                          value.revision(),
                          UUID.randomUUID(),
                          value.count(),
                          new DepositReturns.Evidence(
                              DepositReturns.Proof.UNDELIVERED, "held original"),
                          "direct original return"));
              // BEGIN attempt is the operation identity, never a client value.
              begin =
                  new Journal.Intent(
                      begin.operationId(),
                      begin.requestId(),
                      begin.actor(),
                      begin.kind(),
                      DepositReturns.request(
                          value.deposit(),
                          value.slot(),
                          value.revision(),
                          begin.operationId().value(),
                          value.count(),
                          new DepositReturns.Evidence(
                              DepositReturns.Proof.UNDELIVERED, "held original"),
                          "direct original return"),
                      null,
                      null);
              var start = begin;
              boolean held = depositHeld(player, value);
              submit(
                  player,
                  menu,
                  start,
                  () -> returnJournal(player, DepositReturns.Action.BEGIN, held),
                  begun -> {
                    if (begun.status() != Journal.Status.COMMITTED) {
                      report(menu, "claims", menu.lines(), begun.result().code());
                      return;
                    }
                    var returned =
                        DepositInventory.returnOnce(
                            player,
                            value.deposit(),
                            value.slot(),
                            start.operationId().value(),
                            value.count());
                    var proof =
                        BankingSaves.player(
                            player, flow.context.world(), start.operationId().value());
                    var complete =
                        intent(
                            player,
                            DepositReturns.Action.COMPLETE.kind(),
                            DepositReturns.request(
                                value.deposit(),
                                value.slot(),
                                value.revision() + 1,
                                start.operationId().value(),
                                returned.delivered(),
                                new DepositReturns.Evidence(
                                    DepositReturns.Proof.SAVED_AND_UNDELIVERED, proof.token()),
                                "saved direct return and remainder"));
                    submit(
                        player,
                        menu,
                        complete,
                        () -> returnJournal(player, DepositReturns.Action.COMPLETE, true),
                        completed -> {
                          if (completed.status() == Journal.Status.COMMITTED)
                            finishDepositReturns(player, menu, flow, finished);
                          else report(menu, "claims", menu.lines(), completed.result().code());
                        });
                  });
            });
  }

  private static boolean depositHeld(ServerPlayer player, DepositReturns.Return value) {
    var tag =
        player
            .getPersistentData()
            .getCompound("economy_deposit_originals")
            .getCompound(value.deposit().toString())
            .getCompound(Integer.toString(value.slot()));
    var stack =
        net.minecraft.world.item.ItemStack.parseOptional(
            player.registryAccess(), tag.getCompound("stack"));
    return !stack.isEmpty()
        && stack.getCount() == value.count()
        && BankingInventory.encode(player, stack).equals(value.components());
  }

  private static JournalService returnJournal(
      ServerPlayer player, DepositReturns.Action action, boolean proof) {
    var owner = actor(player);
    return services(player)
        .ledger()
        .depositReturns(
            action,
            (who, kind) -> who.equals(owner),
            (a, i, current, reads) -> current.owner().equals(owner),
            (a, current, delivered, evidence, reads) -> proof,
            null);
  }

  private static void salePreview(ServerPlayer player, BankingMenu menu, Flow flow) {
    withContext(
        player,
        menu,
        context -> {
          if (context.price() == null
              || context.terminal() == null
              || context.terminal().kind() != TerminalPolicy.Kind.REGISTER)
            throw new IllegalStateException("This register has no configured sale.");
          var template =
              RegisterInventory.decodeTemplate(player.server, context.price().template());
          flow.stock =
              RegisterInventory.snapshot(
                  player.server,
                  menu.anchor().dimension(),
                  position(menu),
                  template,
                  context.price().batch());
          var source = source(flow.stock);
          boolean live = menu.valid(player);
          services(player)
              .ledger()
              .quoteSale(
                  actor(player),
                  menu.anchor(),
                  menu.generation(),
                  context.terminal().revision(),
                  (below, item, reads) -> source,
                  (anchor, reads) -> live,
                  ca.emilybelnavis.mcmods.economy.foundation.CreditUse.heldToken(
                          player, context.world())
                      != null)
              .whenComplete(
                  (quote, error) -> {
                    if (error != null) {
                      fail(menu, error);
                      return;
                    }
                    if (!menu.valid(player)) return;
                    flow.quote = quote;
                    var lines =
                        new ArrayList<>(
                            List.of(
                                UiText.t("ui.bank.product.c55370")
                                    + template.getHoverName().getString(),
                                UiText.t("ui.bank.batch.f22bb8") + quote.price().batch(),
                                UiText.t("ui.bank.price.4420c8") + money(quote.price().amount()),
                                UiText.t("ui.bank.available.ea12b5") + source.quantity()));
                    flow.credit = null;
                    flow.creditProof = null;
                    var token =
                        ca.emilybelnavis.mcmods.economy.foundation.CreditUse.heldToken(
                            player, context.world());
                    if (token == null) {
                      review(
                          menu,
                          "shop",
                          lines,
                          UiText.t("ui.bank.review_the_full_batch_then_confirm.e7995d"),
                          "buy_confirm");
                      flow.saleRevision = menu.revision();
                    } else {
                      var audience =
                          ca.emilybelnavis.mcmods.economy.foundation.FinanceMenuSupport.audience(
                              player, menu);
                      var grant =
                          ca.emilybelnavis.mcmods.economy.foundation.FinanceMenuSupport.grant(
                              player, services(player));
                      menu.setBusy(true);
                      services(player)
                          .ledger()
                          .quoteCreditDraw(
                              grant, token, menu.anchor(), menu.generation(), null, 0, true)
                          .whenComplete(
                              (credit, failure) -> {
                                if (!audience.live()) return;
                                if (failure != null || credit.isEmpty()) {
                                  fail(
                                      menu,
                                      failure == null
                                          ? new IllegalArgumentException("Credit unavailable")
                                          : failure);
                                  return;
                                }
                                flow.credit = credit.orElseThrow();
                                lines.add(
                                    UiText.t(
                                            "ui.bank.pay_using_held_credit_card_purchase_apr.179d47")
                                        + UiText.decimal(
                                            flow.credit
                                                .facility()
                                                .purchase()
                                                .apr()
                                                .multiply(new java.math.BigDecimal("100")))
                                        + "%");
                                lines.add(
                                    UiText.t(
                                        "ui.bank.interest_accrues_daily_statement_every_28_days.82e5c6"));
                                lines.add(UiText.t("ui.bank.minimum_terms"));
                                lines.add(
                                    UiText.t(
                                        "ui.bank.missed_requirements_may_default_after_84_days_.d3ddf3"));
                                review(
                                    menu,
                                    "shop",
                                    lines,
                                    UiText.t("ui.bank.review_credit_purchase_then_confirm.3a3d70"),
                                    "buy_confirm");
                                flow.saleRevision = menu.revision();
                              });
                    }
                  });
        });
  }

  private static RegisterSales.Source source(RegisterInventory.Snapshot stock) {
    return new RegisterSales.Source(
        stock.sourceGeneration(),
        stock.revision(),
        stock.available(),
        stock.template(),
        true,
        stock.fingerprint());
  }

  private static void saleConfirm(ServerPlayer player, BankingMenu menu, Flow flow) {
    var quote =
        Objects.requireNonNull(flow.quote, UiText.t("ui.bank.preview_this_sale_first.5cae44"));
    if (!menu.page().equals("shop")
        || menu.revision() != flow.saleRevision
        || flow.reservation != null)
      throw new IllegalStateException("This sale preview expired. Preview again.");
    if (flow.credit != null)
      flow.creditProof =
          ca.emilybelnavis.mcmods.economy.credit.CreditDrawAdmission.capture(
              player, menu, flow.credit.request(), flow.credit.terminal());
    var reserve =
        intent(
            player,
            RegisterSales.Action.RESERVE.kind(),
            RegisterSales.reserveRequest(quote, flow.credit != null));
    flow.reservation = reserve.operationId().value();
    submit(
        player,
        menu,
        reserve,
        () -> saleJournal(player, menu, flow, RegisterSales.Action.RESERVE, false, false),
        outcome -> {
          if (outcome.status() != Journal.Status.COMMITTED) {
            flow.reservation = null;
            report(menu, "shop", menu.lines(), outcome.result().code());
            return;
          }
          if (!menu.valid(player)) {
            submit(
                player,
                menu,
                intent(
                    player,
                    RegisterSales.Action.CANCEL.kind(),
                    RegisterSales.request(
                        flow.reservation, "not-removed", "closed before removal")),
                () -> saleJournal(player, menu, flow, RegisterSales.Action.CANCEL, true, true),
                cancelled -> {
                  if (cancelled.status() == Journal.Status.COMMITTED)
                    releaseFlow(menu, flow.reservation);
                  report(menu, "shop", menu.lines(), cancelled.result().code());
                });
            return;
          }
          try {
            flow.stock =
                RegisterInventory.reserve(player.server, player, flow.stock, flow.reservation);
            BankingSaves.source(
                    player, position(menu).below(), flow.context.world(), flow.reservation)
                .whenComplete(
                    (proof, error) -> {
                      if (error != null) {
                        quarantineSale(player, menu, flow);
                        fail(menu, error);
                        return;
                      }
                      var saved =
                          intent(
                              player,
                              RegisterSales.Action.SAVED.kind(),
                              RegisterSales.request(
                                  flow.reservation, proof.token(), "source originals saved"));
                      submit(
                          player,
                          menu,
                          saved,
                          () ->
                              saleJournal(
                                  player, menu, flow, RegisterSales.Action.SAVED, true, true),
                          savedOutcome -> {
                            if (savedOutcome.status() != Journal.Status.COMMITTED) {
                              quarantineSale(player, menu, flow);
                              return;
                            }
                            if (!menu.valid(player)) {
                              cancelSale(player, menu, flow);
                              return;
                            }
                            var request =
                                RegisterSales.request(
                                    flow.reservation, proof.token(), "confirmed exact stock");
                            if (flow.credit != null) {
                              var fields = new java.util.TreeMap<>(request.fields());
                              fields.put(
                                  "credit_request",
                                  new java.util.TreeMap<>(flow.credit.request().request().fields())
                                      .toString());
                              request = new Journal.CanonicalRequest(request.version(), fields);
                            }
                            var commit =
                                intent(player, RegisterSales.Action.COMMIT.kind(), request);
                            submit(
                                player,
                                menu,
                                commit,
                                () ->
                                    saleJournal(
                                        player,
                                        menu,
                                        flow,
                                        RegisterSales.Action.COMMIT,
                                        false,
                                        true),
                                committed -> {
                                  if (committed.status() == Journal.Status.COMMITTED)
                                    deliver(player, menu, flow.context.world(), flow.reservation);
                                  else cancelSale(player, menu, flow);
                                });
                          });
                    });
          } catch (RuntimeException failed) {
            quarantineSale(player, menu, flow);
            fail(menu, failed);
          }
        });
  }

  private static JournalService saleJournal(
      ServerPlayer player,
      BankingMenu menu,
      Flow flow,
      RegisterSales.Action action,
      boolean cleanup,
      boolean proof) {
    var snapshot =
        action == RegisterSales.Action.QUARANTINE
            ? flow.stock
            : RegisterInventory.snapshot(
                player.server,
                menu.anchor().dimension(),
                position(menu),
                RegisterInventory.decodeTemplate(player.server, flow.quote.price().template()),
                flow.quote.price().batch());
    var observed = source(snapshot);
    boolean live = cleanup || menu.valid(player);
    boolean fits =
        RegisterInventory.fitsBuyer(
            player,
            RegisterInventory.decodeTemplate(player.server, snapshot.template()),
            snapshot.batch());
    var owner = actor(player);
    return services(player)
        .ledger()
        .registerSales(
            action,
            (who, kind) -> who.equals(owner),
            (a, i, current, reads) -> live && i.actor().equals(owner),
            (who, reads) -> false,
            (a, i, current, reads) -> fits,
            (below, template, reads) -> observed,
            (a, i, current, evidence, reads) -> proof,
            null,
            flow.credit == null || action != RegisterSales.Action.COMMIT
                ? RegisterSaleSettlement::checkout
                : services(player)
                    .ledger()
                    .creditCheckout(
                        ca.emilybelnavis.mcmods.economy.foundation.FinanceMenuSupport.grant(
                            player, services(player)),
                        flow.credit.request(),
                        flow.creditProof));
  }

  private static void cancelSale(ServerPlayer player, BankingMenu menu, Flow flow) {
    // Known held originals become an owner-bound seller claim; no speculative source insertion.
    boolean held =
        RegisterInventory.reservationMatches(player.server, flow.stock, flow.reservation);
    if (!held) {
      quarantineSale(player, menu, flow);
      return;
    }
    submit(
        player,
        menu,
        intent(
            player,
            RegisterSales.Action.CANCEL.kind(),
            RegisterSales.request(flow.reservation, "held", "cancelled before payment")),
        () -> saleJournal(player, menu, flow, RegisterSales.Action.CANCEL, true, true),
        cancelled -> {
          if (cancelled.status() == Journal.Status.COMMITTED) releaseFlow(menu, flow.reservation);
          report(
              menu,
              "claims",
              List.of(UiText.t("ui.bank.unpaid_originals_are_retained_for_the_seller.3b1da7")),
              cancelled.result().code());
        });
  }

  private static void quarantineSale(ServerPlayer player, BankingMenu menu, Flow flow) {
    submit(
        player,
        menu,
        intent(
            player,
            RegisterSales.Action.QUARANTINE.kind(),
            RegisterSales.request(flow.reservation, "unknown", "source or outcome uncertain")),
        () -> saleJournal(player, menu, flow, RegisterSales.Action.QUARANTINE, true, false),
        outcome -> {
          if (outcome.status() == Journal.Status.COMMITTED) releaseFlow(menu, flow.reservation);
          report(
              menu,
              "claims",
              List.of(UiText.t("ui.bank.uncertain_source_originals_require_operator_ev.3cb8c3")),
              outcome.result().code());
        });
  }

  private static void price(ServerPlayer player, BankingMenu menu, String value) {
    long units = Long.parseLong(value.trim());
    if (units < 1) throw new IllegalArgumentException("Positive whole-unit price required.");
    var product = player.getMainHandItem().copy();
    if (product.isEmpty()) throw new IllegalArgumentException("Hold the exact product batch.");
    boolean operator = player.hasPermissions(2);
    menu.setBusy(true);
    services(player)
        .ledger()
        .registerPriceContext(actor(player), menu.anchor(), (who, reads) -> operator)
        .whenComplete(
            (found, error) -> {
              if (error != null) {
                fail(menu, error);
                return;
              }
              if (!menu.valid(player) || found.isEmpty()) {
                report(
                    menu,
                    "shop",
                    List.of(
                        UiText.t("ui.bank.an_owned_register_and_seller_primary_are_requi.753e09")),
                    UiText.t("ui.bank.not_authorized.98c072"));
                return;
              }
              var context = found.orElseThrow();
              RegisterInventory.initializeSource(
                  player.server, menu.anchor().dimension(), position(menu));
              var stock =
                  RegisterInventory.snapshot(
                      player.server,
                      menu.anchor().dimension(),
                      position(menu),
                      product,
                      product.getCount());
              var request =
                  RegisterSales.configureRequest(
                      menu.anchor(),
                      menu.generation(),
                      context.terminal().revision(),
                      context.price() != null
                              && context.price().generation().equals(menu.generation())
                          ? context.price().revision()
                          : 0,
                      stock.template(),
                      stock.batch(),
                      new Money(
                          context.seller().currency(),
                          Math.multiplyExact(
                              units, context.seller().currency().minorUnitsPerMajor())));
              submit(
                  player,
                  menu,
                  intent(player, RegisterSales.Action.CONFIGURE.kind(), request),
                  () -> {
                    boolean live = menu.valid(player);
                    boolean op = player.hasPermissions(2);
                    var owner = actor(player);
                    return services(player)
                        .ledger()
                        .registerSales(
                            RegisterSales.Action.CONFIGURE,
                            (who, kind) -> who.equals(owner),
                            (a, i, current, reads) -> live,
                            (who, reads) -> op,
                            (a, i, c, reads) -> false,
                            (below, template, reads) -> null,
                            (a, i, c, proof, reads) -> false,
                            null);
                  },
                  outcome ->
                      report(
                          menu,
                          "shop",
                          List.of(
                              UiText.t("ui.bank.product.45e337")
                                  + product.getHoverName().getString(),
                              UiText.t("ui.bank.batch.eb9c1b") + product.getCount()),
                          outcome.result().code()));
            });
  }

  /**
   * Financial success is resolved first; only a current custody state can authorize physical
   * delivery.
   */
  public static void deliver(ServerPlayer player, BankingMenu menu, WorldId world, UUID custody) {
    services(player)
        .ledger()
        .custodyCase(actor(player), custody, (who, reads) -> false)
        .whenComplete(
            (found, error) -> {
              if (error != null) {
                fail(menu, error);
                return;
              }
              if (found.isEmpty()) {
                report(
                    menu,
                    "claims",
                    List.of(),
                    UiText.t("ui.bank.delivery_case_unavailable.364a6a"));
                return;
              }
              var current = found.orElseThrow();
              if (current.state() != Custody.State.COMMITTED
                  && current.state() != Custody.State.CLAIM) {
                report(
                    menu,
                    "claims",
                    List.of(
                        UiText.t("ui.bank.current_delivery.5acaaf")
                            + deliveryLabel(current.state()),
                        UiText.t("ui.bank.no_replayed_outcome_authorizes_another_item_de.aae39f")),
                    UiText.t("ui.bank.delivery_requires_current_evidence.ea5a31"));
                return;
              }
              if (current.terms().items().startsWith("register-goods-v1")) {
                var source = current.terms().source().split(":", -1);
                var saleId = UUID.fromString(source[source.length - 1]);
                services(player)
                    .ledger()
                    .registerSale(actor(player), saleId)
                    .whenComplete(
                        (sale, failure) -> {
                          if (failure != null || sale.isEmpty()) {
                            report(
                                menu,
                                "claims",
                                List.of(),
                                UiText.t("ui.bank.original_stock_record_unavailable.67c54c"));
                            return;
                          }
                          var reservation = sale.orElseThrow();
                          var price = reservation.quote().price();
                          try {
                            if (!price
                                .anchor()
                                .dimension()
                                .equals(player.serverLevel().dimension().location().toString()))
                              throw new IllegalStateException(
                                  "Visit the original stock dimension to collect.");
                            var stock =
                                RegisterInventory.snapshot(
                                    player.server,
                                    price.anchor().dimension(),
                                    new BlockPos(
                                        price.anchor().x(), price.anchor().y(), price.anchor().z()),
                                    RegisterInventory.decodeTemplate(
                                        player.server, price.template()),
                                    price.batch());
                            if (!stock
                                    .sourceGeneration()
                                    .equals(reservation.quote().source().generation())
                                || !RegisterInventory.reservationMatches(
                                    player.server, stock, saleId))
                              throw new IllegalStateException(
                                  "Original stock is unavailable; operator evidence review is required.");
                            startDelivery(player, menu, world, current, stock, saleId);
                          } catch (RuntimeException invalid) {
                            fail(menu, invalid);
                          }
                        });
              } else startDelivery(player, menu, world, current, null, null);
            });
  }

  private static boolean capacity(
      ServerPlayer player, Custody.Case value, RegisterInventory.Snapshot stock) {
    if (value.terms().items().startsWith("issued-card-v1")) {
      ca.emilybelnavis.mcmods.economy.card.CardInventory.decode(value);
      return ca.emilybelnavis.mcmods.economy.card.CardInventory.fits(player);
    }
    if (stock != null)
      return RegisterInventory.fitsBuyer(
          player, RegisterInventory.decodeTemplate(player.server, stock.template()), stock.batch());
    if (value.terms().items().startsWith("withdrawal-cash-v1"))
      return WithdrawalInventory.fits(player, WithdrawalDelivery.claims(value).size());
    if (BankingInventory.supports(value))
      return BankingInventory.fits(
          player,
          value.terms().items().startsWith("deposit-original-v1|")
              ? BankingInventory.depositClaimItems(player, value)
              : List.of(
                  BankingInventory.decode(
                      player, value.terms().items().substring("menu-original-v1|".length()))));
    return false;
  }

  private static void startDelivery(
      ServerPlayer player,
      BankingMenu menu,
      WorldId world,
      Custody.Case current,
      RegisterInventory.Snapshot stock,
      UUID sale) {
    boolean online =
        player.server.getPlayerList().getPlayer(player.getUUID()) == player && menu.valid(player);
    if (!online || !capacity(player, current, stock)) {
      if (current.state() == Custody.State.CLAIM) {
        report(
            menu,
            "claims",
            List.of(UiText.t("ui.bank.the_complete_batch_must_fit_in_your_main_inven.8886c2")),
            UiText.t("ui.bank.claim_remains_protected.31a3e0"));
        return;
      }
      var evidence =
          new Custody.Evidence(
              Custody.EvidenceKind.UNDELIVERED,
              "not materialized",
              current.terms().destination(),
              "admitted-no-insertion");
      custodyStep(
          player,
          menu,
          current,
          Custody.Action.CLAIM,
          null,
          evidence,
          true,
          true,
          outcome -> {
            if (outcome.status() == Journal.Status.COMMITTED) releaseFlow(menu, current.id());
            report(
                menu,
                "claims",
                List.of(UiText.t("ui.bank.paid_goods_remain_in_your_owner_bound_claim.433cb0")),
                outcome.result().code());
          });
      return;
    }
    var action =
        current.state() == Custody.State.CLAIM
            ? Custody.Action.COLLECT
            : Custody.Action.BEGIN_DELIVERY;
    var attempt = UUID.randomUUID();
    var request =
        Custody.request(current.id(), attempt, Custody.Evidence.unknown(), "full batch delivery");
    var begin =
        new Journal.Intent(
            new OperationId(attempt),
            new RequestId(UUID.randomUUID()),
            actor(player),
            action.kind(),
            request,
            null,
            null);
    submit(
        player,
        menu,
        begin,
        () -> {
          boolean admitted = menu.valid(player) && capacity(player, current, stock);
          var owner = actor(player);
          return services(player)
              .ledger()
              .custody(
                  action,
                  (who, kind) -> who.equals(owner),
                  (a, i, value, reads) -> admitted && value.terms().owner().equals(owner),
                  (value, evidence, reads) -> false,
                  null,
                  (i, value, reads) -> admitted,
                  null);
        },
        outcome -> {
          if (outcome.status() != Journal.Status.COMMITTED) {
            report(menu, "claims", menu.lines(), outcome.result().code());
            return;
          }
          services(player)
              .ledger()
              .custodyCase(actor(player), current.id(), (who, reads) -> false)
              .whenComplete(
                  (fresh, error) -> {
                    if (error != null || fresh.isEmpty()) {
                      fail(menu, new IllegalStateException("Current delivery attempt unavailable"));
                      return;
                    }
                    var delivering = fresh.orElseThrow();
                    if (delivering.state() != Custody.State.DELIVERING
                        || !attempt.equals(delivering.deliveryAttempt())) {
                      report(
                          menu,
                          "claims",
                          menu.lines(),
                          UiText.t("ui.bank.delivery_attempt_changed.5a3508"));
                      return;
                    }
                    if (delivering.terms().items().startsWith("issued-card-v1")
                        && (!menu.valid(player)
                            || player.server.getPlayerList().getPlayer(player.getUUID())
                                != player)) {
                      var undelivered =
                          new Custody.Evidence(
                              Custody.EvidenceKind.UNDELIVERED,
                              "card-insertion-not-started",
                              delivering.terms().destination(),
                              "server-before-insertion");
                      custodyStep(
                          player,
                          menu,
                          delivering,
                          Custody.Action.CLAIM,
                          attempt,
                          undelivered,
                          true,
                          true,
                          ignoredCardClaim -> {});
                      return;
                    }
                    boolean delivered;
                    try {
                      if (player.server.getPlayerList().getPlayer(player.getUUID()) != player)
                        delivered = false;
                      else if (stock != null)
                        delivered =
                            RegisterInventory.deliverOnce(
                                    player.server, player, stock, sale, attempt)
                                != RegisterInventory.Result.UNDELIVERED;
                      else if (delivering.terms().items().startsWith("issued-card-v1"))
                        delivered =
                            ca.emilybelnavis.mcmods.economy.card.CardInventory.deliverOnce(
                                    player, world, delivering)
                                != ca.emilybelnavis.mcmods.economy.card.CardInventory.Result
                                    .UNDELIVERED;
                      else if (delivering.terms().items().startsWith("withdrawal-cash-v1"))
                        delivered =
                            WithdrawalInventory.deliverOnce(player, delivering)
                                != WithdrawalInventory.Result.UNDELIVERED;
                      else delivered = BankingInventory.deliverOnce(player, delivering);
                      var proof = BankingSaves.player(player, world, attempt);
                      if (!delivered) {
                        var evidence =
                            new Custody.Evidence(
                                Custody.EvidenceKind.UNDELIVERED,
                                proof.reference(),
                                delivering.terms().destination(),
                                proof.token());
                        custodyStep(
                            player,
                            menu,
                            delivering,
                            Custody.Action.CLAIM,
                            attempt,
                            evidence,
                            true,
                            true,
                            claimed -> {
                              if (claimed.status() == Journal.Status.COMMITTED)
                                releaseFlow(menu, current.id());
                              report(
                                  menu,
                                  "claims",
                                  List.of(UiText.t("ui.bank.026f4e050f")),
                                  claimed.result().code());
                            });
                        return;
                      }
                      if (stock == null)
                        completeDelivery(player, menu, world, delivering, proof, null, null);
                      else
                        BankingSaves.source(player, stock.anchor().below(), world, attempt)
                            .whenComplete(
                                (sourceProof, failure) -> {
                                  if (failure != null) {
                                    quarantineDelivery(player, menu, delivering);
                                    fail(menu, failure);
                                  } else {
                                    var sourceHash =
                                        sourceProof
                                            .token()
                                            .substring(sourceProof.token().lastIndexOf(':') + 1);
                                    var paired =
                                        new BankingSaves.Proof(
                                            proof.reference()
                                                + "|source="
                                                + sourceProof.reference()
                                                + "#"
                                                + sourceHash,
                                            proof.location(),
                                            proof.token());
                                    completeDelivery(
                                        player, menu, world, delivering, paired, stock, sale);
                                  }
                                });
                    } catch (RuntimeException uncertain) {
                      quarantineDelivery(player, menu, delivering);
                      fail(menu, uncertain);
                    }
                  });
        });
  }

  private static void completeDelivery(
      ServerPlayer player,
      BankingMenu menu,
      WorldId world,
      Custody.Case current,
      BankingSaves.Proof proof,
      RegisterInventory.Snapshot stock,
      UUID sale) {
    var evidence =
        new Custody.Evidence(
            Custody.EvidenceKind.DELIVERY_SAVED,
            proof.reference(),
            current.terms().destination(),
            proof.token());
    custodyStep(
        player,
        menu,
        current,
        Custody.Action.DELIVERY_SAVED,
        current.deliveryAttempt(),
        evidence,
        true,
        true,
        outcome -> {
          if (outcome.status() != Journal.Status.COMMITTED) {
            report(menu, "claims", menu.lines(), outcome.result().code());
            return;
          }
          services(player)
              .ledger()
              .custodyCase(actor(player), current.id(), (who, reads) -> false)
              .whenComplete(
                  (done, error) -> {
                    if (error != null
                        || done.isEmpty()
                        || done.orElseThrow().state() != Custody.State.DELIVERED) return;
                    if (stock != null)
                      RegisterInventory.clearDelivered(
                          player.server, player, stock, sale, done.orElseThrow());
                    else if (current.terms().items().startsWith("issued-card-v1"))
                      ca.emilybelnavis.mcmods.economy.card.CardInventory.clearDelivered(
                          player, world, done.orElseThrow());
                    else if (current.terms().items().startsWith("withdrawal-cash-v1"))
                      WithdrawalInventory.clearDelivered(player, done.orElseThrow());
                    else BankingInventory.clearDelivered(player, done.orElseThrow());
                    BankingSaves.player(player, world, current.id());
                    if (stock != null)
                      BankingSaves.source(player, stock.anchor().below(), world, current.id());
                    var flow = FLOWS.get(menu);
                    if (flow != null) {
                      flow.quote = null;
                      flow.reservation = null;
                      flow.claims = Set.of();
                      flow.claimOrder = List.of();
                    }
                    if (!menu.valid(player)) return;
                    menu.setBusy(false);
                    menu.publish(
                        "claims",
                        List.of(
                            UiText.t("ui.bank.the_complete_batch_is_now_in_your_inventory.1b05f8"),
                            UiText.t("ui.bank.choose_collect_items_to_check_for_more.260d59")),
                        UiText.t("ui.bank.delivery_complete.a50176"),
                        List.of());
                  });
        });
  }

  private static void quarantineDelivery(
      ServerPlayer player, BankingMenu menu, Custody.Case current) {
    custodyStep(
        player,
        menu,
        current,
        Custody.Action.QUARANTINE,
        current.deliveryAttempt(),
        Custody.Evidence.unknown(),
        true,
        false,
        outcome ->
            report(
                menu,
                "claims",
                List.of(UiText.t("ui.bank.delivery_state_is_uncertain_operator_evidence_.cbcfb3")),
                outcome.result().code()));
  }

  private static void custodyStep(
      ServerPlayer player,
      BankingMenu menu,
      Custody.Case current,
      Custody.Action action,
      UUID attempt,
      Custody.Evidence evidence,
      boolean admitted,
      boolean proof,
      Consumer<Journal.Outcome> next) {
    var owner = actor(player);
    submit(
        player,
        menu,
        intent(
            player,
            action.kind(),
            Custody.request(current.id(), attempt, evidence, "banking saved delivery")),
        () ->
            services(player)
                .ledger()
                .custody(
                    action,
                    (who, kind) -> who.equals(owner),
                    (a, i, value, reads) ->
                        admitted
                            && value.terms().owner().equals(owner)
                            && value.id().equals(current.id()),
                    (value, actual, reads) -> proof && actual.equals(evidence),
                    null,
                    (i, value, reads) -> admitted,
                    null),
        next);
  }

  private static void recoverInputs(ServerPlayer player, BankingMenu newMenu) {
    boolean active =
        FLOWS.entrySet().stream()
            .anyMatch(
                entry ->
                    entry.getKey() != newMenu
                        && entry.getKey().player() == player
                        && entry.getValue().ownsInputs);
    if (active) return;
    var saved =
        player.getPersistentData().getList("economy_banking_menu_inputs", Tag.TAG_COMPOUND).copy();
    var inputs = new net.minecraft.world.SimpleContainer(24);
    if (saved.size() > 24) throw new IllegalStateException("Saved input bounds");
    for (int index = 0; index < saved.size(); index++) {
      var entry = saved.getCompound(index);
      int slot = entry.getInt("slot");
      if (slot < 0 || slot >= 24 || !inputs.getItem(slot).isEmpty())
        throw new IllegalStateException("Saved input slots");
      inputs.setItem(
          slot,
          net.minecraft.world.item.ItemStack.parseOptional(
              player.registryAccess(), entry.getCompound("stack")));
    }
    player.getPersistentData().remove("economy_banking_menu_inputs");
    var ids = new ArrayList<>(BankingInventory.stashInputs(player, inputs));
    var stashes = player.getPersistentData().getCompound("economy_banking_originals");
    for (var key : stashes.getAllKeys())
      if (stashes.getCompound(key).getString("state").equals("HELD")) {
        var id = UUID.fromString(key);
        if (!ids.contains(id)) ids.add(id);
      }
    if (!saved.isEmpty() || !ids.isEmpty())
      BankingSaves.player(player, services(player).worldId(), UUID.randomUUID());
    if (!ids.isEmpty()
        && !player.getPersistentData().getCompound(PENDING).hasUUID("operation")
        && !player
            .getPersistentData()
            .getCompound("economy_banking_pending")
            .hasUUID("operation")) {
      BankingSaves.player(player, services(player).worldId(), UUID.randomUUID());
      BankingReturns.create(player, newMenu, services(player).worldId(), ids);
    }
  }

  public static boolean canInput(ServerPlayer player, net.minecraft.world.item.ItemStack stack) {
    if (!BankingInventory.canStageInputs(player)) return false;
    try {
      BankingInventory.encode(player, stack);
      return true;
    } catch (RuntimeException oversized) {
      return false;
    }
  }

  private static void releaseFlow(BankingMenu menu, UUID reservation) {
    var flow = FLOWS.get(menu);
    if (flow != null && Objects.equals(flow.reservation, reservation)) {
      flow.reservation = null;
      flow.quote = null;
      flow.deposit = null;
      flow.ownsInputs = false;
    }
  }

  /** Persist transient input ownership with the same player inventory save, never a ground drop. */
  public static void inputsChanged(ServerPlayer player, BankingMenu menu) {
    if (FLOWS.entrySet().stream()
        .anyMatch(
            entry ->
                entry.getKey() != menu
                    && entry.getKey().player() == player
                    && entry.getValue().ownsInputs)) return;
    var list = new ListTag();
    for (int slot = 0; slot < 24; slot++)
      if (!menu.depositInputs().getItem(slot).isEmpty()) {
        var entry = new CompoundTag();
        entry.putInt("slot", slot);
        entry.put("stack", menu.depositInputs().getItem(slot).save(player.registryAccess()));
        list.add(entry);
      }
    player.getPersistentData().put("economy_banking_menu_inputs", list);
  }

  private static void returnInputs(ServerPlayer player, BankingMenu menu) {
    var ids = BankingInventory.stashInputs(player, menu.depositInputs());
    inputsChanged(player, menu);
    var world = services(player).worldId();
    if (world == null) return;
    BankingSaves.player(player, world, UUID.randomUUID());
    if (!ids.isEmpty()) BankingReturns.create(player, menu, world, ids);
  }

  public static void closed(ServerPlayer player, BankingMenu menu) {
    BankingHome.closed(menu);
    TerminalMenus.closed(menu);
    BankingHistory.closed(menu);
    BankingAccounts.closed(menu);
    BankingWithdrawals.closed(menu);
    AutopayMenus.closed(menu);
    CreditMenus.closed(menu);
    OverdraftMenus.closed(menu);
    LoanMenus.closed(menu);
    FinanceReports.closed(menu);
    FinanceCollections.closed(menu);
    CreditUse.closed(menu);
    if (player.server.getPlayerList().getPlayer(player.getUUID()) != player) return;
    var flow = FLOWS.get(menu);
    if (flow == null || !flow.ownsInputs) {
      try {
        returnInputs(player, menu);
      } catch (RuntimeException unavailable) {
        com.mojang.logging.LogUtils.getLogger()
            .warn(
                "Banking menu originals retained; save verification unavailable for {}",
                player.getUUID());
      }
    }
    // An accepted reservation retains this menu's inputs until its callback saves originals or
    // quarantines uncertainty.
  }

  private static String deliveryLabel(Object value) {
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
