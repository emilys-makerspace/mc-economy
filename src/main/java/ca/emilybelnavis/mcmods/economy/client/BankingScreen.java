package ca.emilybelnavis.mcmods.economy.client;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.banking.UiText;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Compact banking pages. Only server-offered accounts and deliveries become controls. */
public final class BankingScreen extends AbstractContainerScreen<BankingMenu> {
  private static String ui(String key, Object... args) {
    return UiTextClient.render(UiText.t(key, args));
  }

  private static final int ROW_STEP = 19, ROW_HEIGHT = 16, CASH_ROW_STEP = 20;
  private static final int TEXT = 0xff263238, MUTED = 0xff5f655f, TEAL = 0xff526f68;
  private BankingTheme theme = BankingTheme.DEFAULT;
  private String institution = "", termDraft = "";
  private EditBox termInput;
  private String renderedPage = "",
      destination = "",
      draft = "",
      reviewedDraft = "",
      currency = "CAD";
  private final Map<String, String> officeDraft = new HashMap<>();
  private long rightsRevision = -1;
  private final Set<String> officeRights = new LinkedHashSet<>(),
      officeScope = new LinkedHashSet<>();
  private boolean officeAllAccounts;
  private List<EditBox> officeFields = List.of();
  private String corporateAccount = "", counterparty = "", corporateAmount = "";
  private String chooser = "", submittedReview = "";
  private long revision = -1;
  private boolean wasBusy;
  private boolean wasExpired;
  private UUID reviewId;
  private long reviewRevision = -1;
  private final Map<String, String> drafts = new LinkedHashMap<>();
  private int scroll;
  private long settingsRevision = -1;
  private String serviceMode = "BOTH";
  private final Set<String> acceptedCurrencies = new TreeSet<>();
  private EditBox input;
  private Button primary;
  private String primaryAction = "";

  public BankingScreen(BankingMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 316;
    imageHeight = 236;
  }

  public static void initialize(IEventBus bus) {
    bus.addListener(BankingScreen::register);
  }

  private static void register(RegisterMenuScreensEvent event) {
    event.register(BankingCatalog.BANKING.get(), BankingScreen::new);
  }

  private static Component text(String key) {
    return Component.translatable("screen.economy." + key);
  }

  private void send(String action, String value) {
    if (menu.operation().state() == BankingOperation.State.PROCESSING) return;
    if (needsRetry()) {
      if (!lookupChoices().stream()
          .anyMatch(choice -> choice.action().equals(action) && choice.value().equals(value)))
        return;
    } else if (confirmation(action) && !reviewed()) return;
    if (!menu.busy() || needsRetry()) {
      if (!confirmation(action) && !Set.of("retry", "bank_retry").contains(action))
        submittedReview = reviewKey();
      BankingNetwork.action(menu, action, value);
    }
  }

  private static boolean confirmation(String action) {
    return BankingOperation.confirmation(action);
  }

  private boolean blocked() {
    return menu.busy()
        || menu.operation().state() == BankingOperation.State.PROCESSING
        || needsRetry();
  }

  private List<BankingNetwork.Choice> lookupChoices() {
    return menu.choices().stream()
        .filter(choice -> Set.of("retry", "bank_retry").contains(choice.action()))
        .toList();
  }

  private Button button(int x, int y, int width, Component label, Runnable press) {
    label = Component.literal(cleanText(label.getString()));
    long offeredRevision = menu.revision();
    var result =
        addRenderableWidget(
            new FinanceButton(
                    leftPos + x,
                    topPos + y,
                    width,
                    18,
                    label,
                    ignored -> {
                      if (offeredRevision == menu.revision()) press.run();
                    })
                .themed(theme.accent()));
    result.active = !blocked();
    if (font.width(label) > width - 8) result.setTooltip(Tooltip.create(label));
    return result;
  }

  @Override
  protected void init() {
    super.init();
    rebuild();
  }

  private List<BankingNetwork.Choice> choices(String action) {
    if (action.equals("corp_actions")) return menu.choices();
    if (action.equals("history_controls"))
      return menu.choices().stream().filter(c -> !c.action().equals("history_detail")).toList();
    if (action.equals("home_choices"))
      return menu.choices().stream().filter(c -> !c.action().equals("home")).toList();
    if (action.equals("finance_choices"))
      return menu.choices().stream()
          .filter(c -> !FinanceActions.input(c.action()) || !c.value().isEmpty())
          .toList();
    return menu.choices().stream().filter(choice -> choice.action().equals(action)).toList();
  }

  private String selectedLabel() {
    return choices("account_select").stream()
        .filter(BankingNetwork.Choice::selected)
        .map(BankingNetwork.Choice::label)
        .findFirst()
        .orElse(ui("ui.client.choose_account"));
  }

  private String reviewKey() {
    return draft + "|" + destination + "|" + termDraft;
  }

  private boolean reviewed() {
    var operation = menu.operation();
    return operation.state() == BankingOperation.State.REVIEW
        && reviewId != null
        && reviewId.equals(operation.reviewId())
        && reviewRevision == menu.revision()
        && !expiredReview()
        && reviewedDraft.equals(reviewKey());
  }

  private boolean expiredReview() {
    return menu.operation().state() == BankingOperation.State.REVIEW
        && menu.operation().expiresAt() > 0
        && System.currentTimeMillis() >= menu.operation().expiresAt();
  }

  private void rebuild() {
    if (chooser.equals("cash_modes") && choices("configure").isEmpty()) chooser = "";
    if (menu.page().equals("settings")
        && settingsRevision != menu.revision()
        && !choices("configure").isEmpty()) {
      var fields = choices("configure").getFirst().value().split(";", -1);
      if (fields.length == 3) {
        serviceMode = fields[0];
        acceptedCurrencies.clear();
        if (!fields[1].isBlank()) acceptedCurrencies.addAll(Arrays.asList(fields[1].split(",")));
      }
      settingsRevision = menu.revision();
    }
    menu.setDepositSlotsVisible(chooser.isEmpty() && !blocked());
    clearWidgets();
    input = null;
    termInput = null;
    primary = null;
    primaryAction = "";
    if (!menu.page().equals("home")
        && !menu.page().equals("office_home")
        && !menu.worldSetup()
        && menu.access() != BankingMenu.Access.ATM_ENROLLMENT) {
      var home =
          button(
              282,
              9,
              24,
              Component.literal("⌂"),
              () -> send(menu.access() == BankingMenu.Access.ADMIN ? "banking" : "home", ""));
      home.setTooltip(Tooltip.create(Component.literal(terminalLabel("Home"))));
    }
    if (chooser.isEmpty()) {
      String parent =
          switch (menu.page()) {
            case "account_overview" -> "accounts";
            case "account_settings",
                "primary_bank",
                "credit_services",
                "loan_services",
                "card_services" ->
                "manage_accounts";
            default -> "";
          };
      if (!parent.isEmpty() && menu.allows(parent)) {
        var back = button(254, 9, 24, Component.literal("←"), () -> send(parent, ""));
        back.setTooltip(Tooltip.create(UiTextClient.component(ui("ui.client.back"))));
      }
    }
    if (needsScrollControls()) {
      var up = button(258, 34, 22, Component.literal("▲"), () -> scrollBy(-1));
      var down = button(284, 34, 22, Component.literal("▼"), () -> scrollBy(1));
      up.active = down.active = true;
      up.setTooltip(Tooltip.create(text("scroll_up")));
      down.setTooltip(Tooltip.create(text("scroll_down")));
    }
    if (menu.operation().state() == BankingOperation.State.PROCESSING) return;
    if (needsRetry()) {
      int index = 0;
      for (var choice : lookupChoices()) {
        var lookup =
            button(
                10,
                188 - index++ * 22,
                296,
                text("lookup_original"),
                () -> send(choice.action(), choice.value()));
        lookup.active = true;
      }
      return;
    }
    if (menu.access() == BankingMenu.Access.WORLD_SETUP) {
      // World denomination selection is separate from opening a player's bank account.
    } else if (menu.access() == BankingMenu.Access.ADMIN) {
      button(
          10,
          32,
          136,
          UiTextClient.component(ui("ui.client.terminal_settings")),
          () -> send("settings", ""));
    }
    var destinations = choices("destination");
    if (destinations.stream().noneMatch(choice -> choice.value().equals(destination)))
      destination = destinations.isEmpty() ? "" : destinations.getFirst().value();
    if (!chooser.isEmpty()) {
      if (chooser.equals("cash_modes")) cashModes();
      else buildRows(choices(chooser), true);
      button(
          10,
          188,
          92,
          UiTextClient.component(ui("ui.client.back")),
          () -> {
            chooser = "";
            scroll = 0;
            rebuild();
          });
      return;
    }
    if (menu.page().startsWith("office_")) {
      buildOffice();
      return;
    }
    switch (menu.page()) {
      case "home" -> buildRows(choices("home_choices"), false);
      case "primary_bank", "atm_enrollment" -> {
        for (var choice : menu.choices())
          button(
              10,
              188,
              296,
              UiTextClient.component(choice.label()),
              () -> send(choice.action(), choice.value()));
      }
      case "account_overview" -> {
        for (var choice : menu.choices()) {
          if (choice.action().endsWith("prev"))
            button(
                10, 210, 32, Component.literal("←"), () -> send(choice.action(), choice.value()));
          else if (choice.action().endsWith("next"))
            button(
                274, 210, 32, Component.literal("→"), () -> send(choice.action(), choice.value()));
        }
      }
      case "branch_services",
          "account_settings",
          "credit_services",
          "loan_services",
          "terminal_accounts",
          "bill_accounts",
          "withdraw_sources",
          "withdrawal_sources",
          "card_services" ->
          buildRows(menu.choices(), false);
      case "payments" -> {
        buildRows(menu.choices(), false);
        button(10, 188, 136, text("tab.home"), () -> send("home", ""));
      }
      case "accounts" -> {
        var accounts = choices("account_select");
        if (accounts.isEmpty()) {
          var management = choices("manage_accounts");
          if (!management.isEmpty()) {
            var choice = management.getFirst();
            button(
                10,
                106,
                296,
                UiTextClient.component(choice.label()),
                () -> send(choice.action(), choice.value()));
          } else if (!choices("setup").isEmpty()) {
            var choice = choices("setup").getFirst();
            button(10, 106, 296, text("action.setup"), () -> send(choice.action(), choice.value()));
          }
        } else {
          buildRows(accounts, false);
          button(10, 188, 296, text("action.new_account"), () -> send("manage_accounts", ""));
        }
      }
      case "manage" -> {
        var accounts = choices("account_close_preview");
        for (int i = 0; i < accounts.size(); i++) {
          var account = accounts.get(i);
          var close =
              button(
                  204,
                  74 + i * 21,
                  102,
                  text("action.account_close_preview"),
                  () -> send("account_close_preview", account.value()));
          close.active = !menu.busy() && account.selected();
          if (!account.selected())
            close.setTooltip(Tooltip.create(UiTextClient.component(ui("ui.client.cannot_close"))));
        }
        var currencies = choices("account_open");
        if (currencies.stream().noneMatch(choice -> choice.value().equals(currency)))
          currency = currencies.isEmpty() ? "" : currencies.getFirst().value();
        var selector =
            button(
                10,
                164,
                176,
                UiTextClient.component(
                    currency.isEmpty() ? ui("ui.client.no_currencies") : currency + " ▾"),
                () -> openChooser("account_open"));
        selector.active = !menu.busy() && !currencies.isEmpty();
        var open =
            button(
                192, 164, 114, text("action.account_open"), () -> send("account_open", currency));
        open.active = !menu.busy() && !currencies.isEmpty();
      }
      case "branch", "branch_targets", "brands", "branch_products", "cards", "card_funding" -> {
        buildRows(menu.choices(), false);
        button(
            10,
            188,
            136,
            UiTextClient.component(
                menu.access() == BankingMenu.Access.ADMIN
                    ? ui("ui.client.back_settings")
                    : ui("ui.client.back_branch")),
            () ->
                send(
                    menu.access() == BankingMenu.Access.ADMIN ? "settings" : "manage_accounts",
                    ""));
      }
      case "corp_list" -> {
        buildRows(choices("corp_select"), false);
        if (menu.access() == BankingMenu.Access.CORPORATION)
          button(10, 188, 136, text("tab.home"), () -> send("home", ""));
        else if (menu.issuedToken() == null)
          button(
              10,
              188,
              136,
              UiTextClient.component(ui("ui.client.back_branch")),
              () -> send("manage_accounts", ""));
        if (!choices("corp_recover").isEmpty())
          button(148, 188, 92, text("corp_receipt"), () -> send("corp_recover", ""));
        for (var choice : choices("corp_next"))
          button(
              242,
              188,
              64,
              UiTextClient.component(choice.label()),
              () -> send(choice.action(), choice.value()));
      }
      case "corp_accounts" -> {
        buildRows(menu.choices(), false);
        corporateCompanies();
      }
      case "corp_details" -> {
        corporateBack();
        for (var choice : choices("corp_people_next"))
          button(
              158,
              188,
              148,
              UiTextClient.component(choice.label()),
              () -> send(choice.action(), choice.value()));
      }
      case "corp_people" -> {
        corporateCompanies();
        for (var choice : choices("corp_people_next"))
          button(
              158,
              188,
              148,
              UiTextClient.component(choice.label()),
              () -> send(choice.action(), choice.value()));
      }
      case "corp_sources" -> {
        buildRows(choices("corp_source"), false);
        corporateBack();
      }
      case "corp_destinations" -> buildRows(menu.choices(), false);
      case "corp_lookup", "corp_send" -> {
        boolean lookup = menu.page().equals("corp_lookup");
        corporateField(
            10,
            286,
            lookup ? 36 : 23,
            lookup ? "corp_counterparty" : "corp_amount",
            lookup ? counterparty : corporateAmount,
            v -> {
              if (lookup) counterparty = v;
              else corporateAmount = v;
            });
        for (var choice : menu.choices()) {
          boolean back = choice.action().equals("corp_to_account");
          button(
              back ? 10 : 160,
              188,
              146,
              UiTextClient.component(choice.label()),
              () -> send(choice.action(), back ? "" : lookup ? counterparty : corporateAmount));
        }
      }
      case "corp_contribute" -> {
        corporateBack();
        corporateField(10, 190, 23, "corp_amount", corporateAmount, v -> corporateAmount = v);
        button(
            158,
            188,
            148,
            UiTextClient.component(ui("ui.bank.corporate.review")),
            () -> send("corp_amount", corporateAmount));
      }
      case "corp_cc" -> {
        corporateCompanies();
        for (int i = 0; i < menu.choices().size(); i++) {
          var c = menu.choices().get(i);
          button(
              10 + i % 2 * 150,
              132 + i / 2 * 22,
              146,
              UiTextClient.component(c.label()),
              () -> {
                corporateAmount = "";
                send(c.action(), c.value());
              });
        }
      }
      case "corp_cc_amount", "corp_cc_close" -> {
        corporateCompanies();
        if (menu.page().equals("corp_cc_amount"))
          corporateField(10, 146, 23, "corp_amount", corporateAmount, v -> corporateAmount = v);
        button(
            160,
            188,
            146,
            Component.literal("Review"),
            () -> send("corp_cc_submit", corporateAmount));
      }
      case "corp_account" -> corporateAccountForm();
      case "corp_history", "corp_review", "corp_receipt" -> {
        corporateBack();
        for (var choice : choices("corp_confirm")) {
          var confirm =
              button(
                  166,
                  188,
                  140,
                  UiTextClient.component(choice.label()),
                  () -> send(choice.action(), choice.value()));
          confirm.active = !blocked() && reviewed();
        }
      }
      case "credit_facilities" -> {
        buildRows(menu.choices(), false);
        button(
            10,
            188,
            144,
            UiTextClient.component(ui("ui.client.back_profile")),
            () -> send("credit_profile", ""));
      }
      case "credit_profile", "credit_history", "credit_facility", "credit_statements" -> {
        financeControls(false);
      }
      case "brand_review", "card_review", "settings_review" -> {
        for (var choice : menu.choices()) {
          var control =
              button(
                  160,
                  188,
                  146,
                  UiTextClient.component(choice.label()),
                  () -> send(choice.action(), choice.value()));
          control.active = !blocked() && (!confirmation(choice.action()) || reviewed());
        }
        button(
            10,
            188,
            136,
            UiTextClient.component(
                menu.page().equals("card_review")
                    ? ui("ui.client.back_cards")
                    : ui("ui.client.back_settings")),
            () -> send(menu.page().equals("card_review") ? "cards" : "settings", ""));
      }
      case "claims" -> {
        buildRows(choices("collect"), false);
        button(10, 188, 96, text("tab.claims"), () -> send("claims", ""));
        button(210, 188, 96, text("action.claims_next"), () -> send("claims_next", ""));
      }
      case "deposit", "withdraw", "fx" -> {
        boolean deposit = menu.page().equals("deposit"), fx = menu.page().equals("fx");
        if (!deposit)
          button(
              10,
              74,
              fx ? 144 : 296,
              UiTextClient.component(
                  fx ? ui("ui.client.from", selectedLabel()) : selectedLabel() + " ▾"),
              () -> openChooser("account_select"));
        if (fx) {
          String label =
              destinations.stream()
                  .filter(choice -> choice.value().equals(destination))
                  .map(BankingNetwork.Choice::label)
                  .findFirst()
                  .orElse(ui("ui.client.choose_account"));
          button(
              160,
              74,
              146,
              UiTextClient.component(ui("ui.client.to", label)),
              () -> openChooser("destination"));
        }
        if (!deposit)
          amountBox(
              10,
              108,
              136,
              fx ? ui("ui.client.fx_amount") : ui("ui.client.withdraw_amount"),
              fx ? "10" : "60.00");
        primaryAction = menu.page() + "_preview";
        if (fx) primaryAction = "exchange_preview";
        primary =
            button(
                deposit ? 184 : 160,
                deposit ? 188 : 108,
                deposit ? 122 : 146,
                Component.empty(),
                this::performPrimary);
        if (deposit) button(184, 210, 122, text("cancel"), () -> send("cancel", ""));
        if (menu.page().equals("withdraw") && !choices("withdraw_mix").isEmpty())
          button(
              10,
              188,
              136,
              UiTextClient.component(ui("ui.client.cash_select")),
              () -> send("withdraw_mix", ""));
      }
      case "withdraw_mix" -> {
        var rows = choices("withdraw_mix_change");
        scroll = Math.clamp(scroll, 0, Math.max(0, rows.size() - 5));
        for (int i = scroll; i < Math.min(rows.size(), scroll + 5); i++) {
          var choice = rows.get(i);
          int y = 82 + (i - scroll) * CASH_ROW_STEP;
          button(
              10,
              y,
              28,
              UiTextClient.component("−"),
              () -> send("withdraw_mix_change", choice.value() + ";-1"));
          button(
              278,
              y,
              28,
              UiTextClient.component("+"),
              () -> send("withdraw_mix_change", choice.value() + ";1"));
        }
        button(
            10,
            188,
            142,
            UiTextClient.component(ui("ui.client.default_mix")),
            () -> send("withdraw_mix_reset", ""));
        button(
            164,
            188,
            142,
            UiTextClient.component(ui("ui.client.review_mix")),
            () -> send("withdraw_mix_review", ""));
      }
      case "history" -> {
        buildRows(choices("history_detail"), false);
        button(10, 188, 296, text("history_controls"), () -> openChooser("history_controls"));
      }
      case "history_detail" -> {
        menu.choices().stream()
            .filter(c -> c.action().equals("history_back"))
            .findFirst()
            .ifPresent(
                c ->
                    button(
                        10,
                        188,
                        296,
                        UiTextClient.component(c.label()),
                        () -> send(c.action(), c.value())));
      }
      case "history_rates" ->
          button(10, 188, 296, text("rate_controls"), () -> openChooser("history_controls"));
      case "history_filters" -> {
        buildRows(menu.choices(), false);
        menu.choices().stream()
            .filter(c -> c.action().equals("history_back"))
            .findFirst()
            .ifPresent(
                c ->
                    button(
                        10,
                        188,
                        296,
                        UiTextClient.component(c.label()),
                        () -> send(c.action(), c.value())));
      }
      case "history_dates", "history_rate_date" -> {
        amountBox(
            10,
            108,
            296,
            ui("ui.client.utc_filter"),
            menu.page().equals("history_dates") ? "2026-01-01 2027-01-01" : "-");
        String action = menu.page().equals("history_dates") ? "history_dates" : "rate_at";
        button(10, 164, 144, text("history_apply"), () -> send(action, draft));
        menu.choices().stream()
            .filter(c -> c.action().equals("history_back"))
            .findFirst()
            .ifPresent(
                c ->
                    button(162, 164, 144, text("history_back"), () -> send(c.action(), c.value())));
      }
      case "close" -> {
        button(
            10,
            188,
            136,
            UiTextClient.component(ui("ui.client.back_management")),
            () -> send("manage_accounts", ""));
        for (var choice : choices("account_close_confirm")) {
          var control =
              button(
                  160,
                  188,
                  146,
                  UiTextClient.component(choice.label()),
                  () -> send(choice.action(), choice.value()));
          control.active = !blocked() && reviewed();
        }
      }
      case "setup" -> {
        if (menu.worldSetup()) {
          button(10, 166, 136, UiTextClient.component(currency + " ▾"), this::nextCurrency);
          button(
              160,
              188,
              146,
              UiTextClient.component(ui("ui.client.choose_currency")),
              () -> send("setup", currency));
        }
      }
      case "settings" -> {
        if (!choices("settings").isEmpty())
          button(
              10,
              76,
              296,
              UiTextClient.component(ui("ui.admin.settings.refresh")),
              () -> send("settings", ""));
        button(
            10,
            188,
            136,
            UiTextClient.component(ui("ui.client.use_terminal")),
            () -> send("banking", ""));
        if (!choices("brand_preview").isEmpty())
          button(
              10,
              76,
              296,
              UiTextClient.component(ui("ui.client.choose_brand")),
              () -> send("brand_preview", ""));
        if (!choices("configure").isEmpty()) {
          button(
              10,
              104,
              296,
              UiTextClient.component(ui("ui.client.cash_services") + " ▸"),
              () -> openChooser("cash_modes"));
        }
      }
      case "shop" -> {
        boolean held =
            minecraft != null
                && minecraft.player != null
                && !minecraft.player.getMainHandItem().isEmpty();
        if (held) {
          amountBox(10, 166, 136, ui("ui.client.held_price"), "10");
          button(10, 188, 136, text("action.price"), () -> send("price", draft));
        }
        primaryAction = "buy_preview";
        primary = button(160, 188, 146, Component.empty(), this::performPrimary);
      }
      default -> {
        if (menu.page().startsWith("finance_") || menu.page().equals("credit_advance")) {
          var editable =
              menu.choices().stream()
                  .filter(c -> FinanceActions.input(c.action()) && c.value().isEmpty())
                  .findFirst();
          financeControls(editable.isPresent());
          if (editable.isPresent()) {
            var choice = editable.orElseThrow();
            String label =
                switch (choice.action()) {
                  case "finance_loan_preview" -> ui("ui.client.principal_years");
                  case "finance_credit_preview", "finance_credit_limit" ->
                      ui("ui.client.credit_limit");
                  case "credit_adv_preview" -> ui("ui.client.advance_amount");
                  case "finance_od_preview" -> ui("ui.finance.od.input");
                  default -> ui("ui.client.debt_payment");
                };
            amountBox(
                10,
                164,
                choice.action().equals("finance_loan_preview") ? 184 : 136,
                choice.action().equals("finance_loan_preview")
                    ? terminalLabel("Loan Amount")
                    : label,
                choice.action().equals("finance_loan_preview")
                    ? "1000"
                    : Set.of("finance_credit_preview", "finance_credit_limit")
                            .contains(choice.action())
                        ? "2500"
                        : choice.action().equals("finance_od_preview") ? "100" : "100.00");
            if (choice.action().equals("finance_loan_preview")) {
              termInput =
                  addRenderableWidget(
                      new EditBox(
                          font,
                          leftPos + 210,
                          topPos + 164,
                          96,
                          18,
                          Component.literal(terminalLabel("Term"))));
              termInput.setTextShadow(false);
              termInput.setMaxLength(3);
              termInput.setValue(termDraft);
              termInput.setHint(Component.literal(terminalLabel("Years")));
              termInput.setResponder(value -> termDraft = value);
            }
            button(
                160,
                188,
                146,
                UiTextClient.component(choice.label()),
                () ->
                    send(
                        choice.action(),
                        choice.action().equals("finance_loan_preview")
                            ? draft + " " + termDraft
                            : draft));
          }
        }
      }
    }
    updatePrimary();
  }

  private void financeControls(boolean amountForm) {
    var choices = choices("finance_choices");
    if (choices.isEmpty()) return;
    if (choices.size() <= (amountForm ? 1 : 4)) {
      for (int i = 0; i < choices.size(); i++) {
        var c = choices.get(i);
        var control =
            button(
                10 + (i % 2) * 150,
                188 + (i / 2) * 22,
                choices.size() == 1 && !amountForm ? 296 : 146,
                UiTextClient.component(c.label()),
                () -> send(c.action(), c.value()));
        control.active = !blocked() && (!confirmation(c.action()) || reviewed());
      }
    } else {
      button(
          10,
          188,
          amountForm ? 136 : 296,
          UiTextClient.component(ui("ui.client.actions")),
          () -> openChooser("finance_choices"));
    }
  }

  private void cashModes() {
    var modes = ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy.Mode.values();
    for (int i = 0; i < modes.length; i++) {
      String mode = modes[i].name();
      button(
          10,
          68 + i * 24,
          296,
          Component.literal((mode.equals(serviceMode) ? "✓ " : "") + serviceLabel(mode)),
          () -> {
            serviceMode = mode;
            rebuild();
          });
    }
    button(
        160,
        188,
        146,
        UiTextClient.component(ui("ui.admin.settings.review_action")),
        () -> send("configure", serviceMode + ";" + String.join(",", acceptedCurrencies) + ";all"));
  }

  private String serviceLabel(String mode) {
    return switch (mode) {
      case "BOTH" -> ui("ui.client.both");
      case "DEPOSIT" -> ui("ui.client.deposits_only");
      case "WITHDRAW" -> ui("ui.client.withdrawals_only");
      default -> ui("ui.client.disabled");
    };
  }

  private void buildOffice() {
    if (menu.page().equals("office_home") || menu.page().equals("office_choices")) {
      buildRows(menu.choices(), false);
      return;
    }
    if (choices("office_submit").isEmpty()) {
      for (int i = 0; i < menu.choices().size(); i++) {
        var choice = menu.choices().get(i);
        button(
            10 + 150 * (i % 2),
            188 + 22 * (i / 2),
            146,
            UiTextClient.component(choice.label()),
            () -> send(choice.action(), choice.value()));
      }
      return;
    }
    if (menu.page().equals("office_rights")) {
      buildOfficeRights();
      return;
    }
    boolean shares = menu.page().equals("office_register") || menu.page().equals("office_issue");
    var labels =
        switch (menu.page()) {
          case "office_register" ->
              List.of("Corporation name", "Number of Class A shares", "Price per share");
          case "office_issue" -> List.of("Number of Class A shares", "Price per share");
          case "office_rename" -> List.of("New company name");
          case "office_appoint" -> List.of("Player name");
          case "office_receipt" -> List.of("Receipt reference");
          default -> List.<String>of();
        };
    var fields = new ArrayList<EditBox>();
    for (int i = 0; i < labels.size(); i++) {
      String label = labels.get(i);
      String key = menu.page() + "/" + label;
      int x = 10 + (i % 2) * 150, y = 74 + (i / 2) * 38;
      var box =
          addRenderableWidget(
              new EditBox(font, leftPos + x, topPos + y, 142, 18, Component.literal(label)));
      box.setMaxLength(label.toLowerCase(Locale.ROOT).contains("name") ? 32 : 128);
      box.setValue(officeDraft.getOrDefault(key, ""));
      box.setResponder(v -> officeDraft.put(key, v));
      fields.add(box);
    }
    officeFields = List.copyOf(fields);
    String currencyKey = menu.page() + "/currency";
    if (shares) {
      var currencies = EconomyCurrency.all();
      String selected = officeDraft.getOrDefault(currencyKey, "CAD");
      if (officeDraft.containsKey(currencyKey + "/open")) {
        for (int i = 0; i < currencies.size(); i++) {
          String code = currencies.get(i).code();
          button(
              10 + i % 6 * 50,
              144 + i / 6 * 20,
              47,
              Component.literal(code),
              () -> {
                officeDraft.put(currencyKey, code);
                officeDraft.remove(currencyKey + "/open");
                rebuild();
              });
        }
      } else {
        button(
            10,
            144,
            142,
            Component.literal(selected + " ▾"),
            () -> {
              officeDraft.put(currencyKey + "/open", "1");
              rebuild();
            });
      }
    }
    button(
        160,
        188,
        146,
        Component.literal(
            menu.page().equals("office_receipt")
                ? "Find receipt"
                : menu.page().equals("office_register") ? "Choose fee account" : "Review"),
        () -> {
          var values = new ArrayList<>(fields.stream().map(EditBox::getValue).toList());
          if (shares) values.add(officeDraft.getOrDefault(currencyKey, "CAD"));
          send("office_submit", String.join("\t", values));
        });
  }

  private void buildOfficeRights() {
    officeFields = List.of();
    var options = choices("office_submit");
    if (rightsRevision != menu.revision()) {
      rightsRevision = menu.revision();
      officeRights.clear();
      officeScope.clear();
      officeAllAccounts = false;
      for (var choice : options) {
        if (!choice.selected()) continue;
        String[] parts = choice.value().split(":", 2);
        if (parts[0].equals("right")) officeRights.add(parts[1]);
        else if (parts[1].equals("all")) officeAllAccounts = true;
        else officeScope.add(parts[1]);
      }
    }
    int rightIndex = 0, scopeIndex = 0;
    for (var choice : options) {
      String[] parts = choice.value().split(":", 2);
      String value = parts[1];
      if (parts[0].equals("right")) {
        String label =
            switch (value) {
              case "view" -> "View history";
              case "pay" -> "Pay";
              case "accounts" -> "Manage accounts";
              default -> "Borrow";
            };
        button(
            10 + rightIndex % 2 * 150,
            66 + rightIndex / 2 * 19,
            146,
            Component.literal((officeRights.contains(value) ? "[x] " : "[ ] ") + label),
            () -> {
              if (!officeRights.remove(value)) officeRights.add(value);
              rebuild();
            });
        rightIndex++;
      } else {
        boolean all = value.equals("all");
        boolean checked = all ? officeAllAccounts : officeScope.contains(value);
        var option =
            button(
                10 + scopeIndex % 2 * 150,
                120 + scopeIndex / 2 * 19,
                146,
                Component.literal((checked ? "[x] " : "[ ] ") + choice.label()),
                () -> {
                  if (all) officeAllAccounts = !officeAllAccounts;
                  else if (!officeScope.remove(value)) officeScope.add(value);
                  rebuild();
                });
        if (!all && officeAllAccounts) option.active = false;
        scopeIndex++;
      }
    }
    button(
        160,
        188,
        146,
        Component.literal("Review changes"),
        () ->
            send(
                "office_submit",
                (officeRights.isEmpty() ? "none" : String.join(",", officeRights))
                    + "\t"
                    + (officeAllAccounts
                        ? "all"
                        : officeScope.isEmpty() ? "none" : String.join(",", officeScope))));
  }

  private void corporateBack() {
    var choice =
        menu.choices().stream()
            .filter(c -> c.action().equals("corp_to_account") || c.action().equals("corp_summary"))
            .findFirst();
    if (choice.isEmpty()) {
      corporateCompanies();
      return;
    }
    var back = choice.get();
    button(
        10,
        188,
        146,
        UiTextClient.component(back.label()),
        () -> send(back.action(), back.value()));
  }

  private void corporateCompanies() {
    button(
        10,
        188,
        136,
        UiTextClient.component(ui("ui.bank.corporate.companies")),
        () -> send("corp_list", ""));
  }

  private void corporateAccountForm() {
    corporateCompanies();
    var actions =
        menu.choices().stream()
            .sorted(
                java.util.Comparator.comparingInt(
                    c ->
                        List.of(
                                "corp_funding",
                                "corp_pay",
                                "corp_history",
                                "corp_credit",
                                "corp_debit_issue",
                                "corp_close")
                            .indexOf(c.action())))
            .toList();
    for (int i = 0; i < actions.size(); i++) {
      var choice = actions.get(i);
      button(
          10 + (i % 2) * 150,
          108 + (i / 2) * 24,
          146,
          UiTextClient.component(choice.label()),
          () -> {
            counterparty = "";
            corporateAmount = "";
            send(choice.action(), choice.value());
          });
    }
    if (!corporateAccount.isEmpty())
      button(
          160,
          188,
          146,
          text("corp_copy"),
          () -> {
            minecraft.keyboardHandler.setClipboard(corporateAccount);
            minecraft.player.displayClientMessage(text("corp_copied"), true);
          });
  }

  private void corporateField(
      int x,
      int width,
      int limit,
      String key,
      String initial,
      java.util.function.Consumer<String> change) {
    var label = text(key);
    var box = addRenderableWidget(new EditBox(font, leftPos + x, topPos + 132, width, 18, label));
    box.setHint(label);
    box.setTooltip(Tooltip.create(label));
    box.setMaxLength(limit);
    box.setValue(initial);
    box.setResponder(change);
    box.setEditable(!blocked());
  }

  private void nextCurrency() {
    var values = EconomyCurrency.all();
    int index = 0;
    for (int i = 0; i < values.size(); i++) if (values.get(i).code().equals(currency)) index = i;
    currency = values.get((index + 1) % values.size()).code();
    rebuild();
  }

  private boolean needsRetry() {
    return menu.operation().state() == BankingOperation.State.OUTCOME_UNCERTAIN;
  }

  private void openChooser(String action) {
    chooser = action;
    scroll = 0;
    rebuild();
  }

  private void buildRows(List<BankingNetwork.Choice> rows, boolean selection) {
    scroll = Math.clamp(scroll, 0, Math.max(0, rows.size() - 6));
    for (int i = scroll; i < Math.min(rows.size(), scroll + 6); i++) {
      var choice = rows.get(i);
      String label =
          (choice.selected() ? "✓ " : "")
              + (choice.action().equals("collect")
                  ? ui("ui.client.collect", choice.label())
                  : choice.label());
      var control =
          button(
              10,
              74 + (i - scroll) * ROW_STEP,
              286,
              UiTextClient.component(label),
              () -> {
                if (choice.action().equals("destination")) {
                  destination = choice.value();
                  reviewedDraft = "";
                } else if (choice.action().equals("account_open")
                    && chooser.equals("account_open")) {
                  currency = choice.value();
                } else {
                  if (choice.action().equals("corp_account")) {
                    corporateAccount = choice.value();
                    counterparty = "";
                    corporateAmount = "";
                  }
                  send(choice.action(), choice.value());
                }
                if (selection) {
                  chooser = "";
                  scroll = 0;
                  rebuild();
                }
              });
      control.setHeight(ROW_HEIGHT);
      control.active = !blocked() && (!confirmation(choice.action()) || reviewed());
    }
  }

  private void amountBox(int x, int y, int width, String label, String hint) {
    input =
        addRenderableWidget(
            new EditBox(font, leftPos + x, topPos + y, width, 18, UiTextClient.component(label)));
    input.setTextShadow(false);
    input.setMaxLength(menu.page().equals("settings") ? 2048 : 32);
    input.setValue(draft);
    input.setHint(UiTextClient.component(hint));
    input.setEditable(!blocked());
    if (!Set.of("history_dates", "history_rate_date").contains(menu.page()))
      input.setTooltip(
          Tooltip.create(UiTextClient.component(UiText.t("ui.client.numeric_convention"))));
    input.setResponder(
        value -> {
          draft = value;
          updatePrimary();
        });
  }

  private void performPrimary() {
    String action = reviewed() ? primaryAction.replace("_preview", "_confirm") : primaryAction;
    if (action.endsWith("_preview")) submittedReview = reviewKey();
    send(action, menu.page().equals("fx") ? destination + " " + draft : draft);
  }

  private void updatePrimary() {
    if (primary == null) return;
    primary.setMessage(
        text(
            "action."
                + (reviewed() ? primaryAction.replace("_preview", "_confirm") : primaryAction)));
    primary.active =
        !blocked()
            && (menu.page().equals("deposit") || menu.page().equals("shop") || !draft.isBlank())
            && (!menu.page().equals("fx") || !destination.isEmpty())
            && (!Set.of("deposit", "withdraw", "fx").contains(menu.page())
                || !choices("account_select").isEmpty());
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    if (menu.dashboard() != null && !menu.dashboard().institution().equals(institution)) {
      institution = menu.dashboard().institution();
      theme = BankingTheme.load(institution);
    }
    if (revision != menu.revision() || wasBusy != menu.busy() || wasExpired != expiredReview()) {
      boolean revised = revision != menu.revision();
      if (!renderedPage.equals(menu.page())) {
        if (!renderedPage.isEmpty()) {
          drafts.put(renderedPage, draft);
          if (drafts.size() > 32) drafts.remove(drafts.keySet().iterator().next());
        }
        draft = drafts.getOrDefault(menu.page(), "");
        chooser = "";
        scroll = 0;
        reviewedDraft = "";
        if (menu.page().equals("withdraw") && !choices("withdraw_confirm").isEmpty()) {
          draft = choices("withdraw_confirm").getFirst().value();
          submittedReview = reviewKey();
        }
      }
      if (revised) {
        if (menu.page().equals("corp_list")) scroll = 0;
        var operation = menu.operation();
        UUID incoming = operation.reviewId();
        boolean newReview =
            operation.state() == BankingOperation.State.REVIEW
                && incoming != null
                && !incoming.equals(reviewId);
        boolean amountPage = Set.of("deposit", "withdraw", "fx", "shop").contains(menu.page());
        if (newReview && (!amountPage || submittedReview.equals(reviewKey()))) {
          reviewId = incoming;
          reviewRevision = menu.revision();
          reviewedDraft = reviewKey();
        } else {
          reviewId = null;
          reviewRevision = -1;
          reviewedDraft = "";
        }
      }
      renderedPage = menu.page();
      revision = menu.revision();
      wasBusy = menu.busy();
      wasExpired = expiredReview();
      rebuild();
    }
    updatePrimary();
  }

  @Override
  protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
    g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, theme.accent());
    g.fill(
        leftPos + 4,
        topPos + 4,
        leftPos + imageWidth - 4,
        topPos + imageHeight - 4,
        BankingTheme.CREAM);
    g.fill(leftPos + 8, topPos + 8, leftPos + imageWidth - 8, topPos + 29, theme.primary());
    if (chooser.isEmpty() && menu.page().equals("deposit"))
      for (var slot : menu.slots)
        if (slot.isActive()) {
          g.fill(
              leftPos + slot.x - 1,
              topPos + slot.y - 1,
              leftPos + slot.x + 17,
              topPos + slot.y + 17,
              theme.accent());
          g.fill(
              leftPos + slot.x,
              topPos + slot.y,
              leftPos + slot.x + 16,
              topPos + slot.y + 16,
              0xffe7dfcd);
        }
  }

  @Override
  protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
    int nameX = theme.logo() == null ? 13 : 36;
    if (theme.logo() != null) g.blitSprite(theme.logo(), 12, 10, 20, 18);
    g.pose().pushPose();
    float nameScale = Math.min(.8f, 80f / Math.max(1, font.width(theme.name())));
    g.pose().translate(nameX, 14, 0);
    g.pose().scale(nameScale, nameScale, 1);
    g.drawString(font, theme.name(), 0, 0, theme.headerText(), false);
    g.pose().popPose();
    String heading = terminalLabel(pageTitle());
    g.pose().pushPose();
    g.pose().translate(158, 14, 0);
    g.pose().scale(.8f, .8f, 1);
    String visibleHeading = font.plainSubstrByWidth(heading, 102);
    g.drawString(
        font, visibleHeading, -font.width(visibleHeading) / 2, 0, theme.headerText(), false);
    g.pose().popPose();
    if (declinedRowPage() && chooser.isEmpty()) {
      drawLines(g, declineDetails(), 10, 56, 296, 176);
      return;
    }
    boolean deposit = menu.page().equals("deposit");
    boolean completedDeposit =
        deposit && menu.operation().state() == BankingOperation.State.COMPLETED;
    if (completedDeposit) {
      int width = needsScrollControls() ? 238 : 296;
      g.drawWordWrap(font, Component.literal(cleanText(statusText())), 10, 36, width, MUTED);
    } else if (Set.of(
            BankingOperation.State.PROCESSING,
            BankingOperation.State.OUTCOME_UNCERTAIN,
            BankingOperation.State.COMPLETED,
            BankingOperation.State.DECLINED)
        .contains(menu.operation().state()))
      g.drawString(
          font, font.plainSubstrByWidth(cleanText(statusText()), 238), 10, 36, MUTED, false);
    if (!chooser.isEmpty()) {
      scrollbar(g, choices(chooser).size());
      return;
    }
    int x = deposit ? 164 : 10,
        y = deposit ? 98 : Set.of("fx", "withdraw").contains(menu.page()) ? 134 : 76;
    int bottom =
        (menu.page().startsWith("finance_") || menu.page().equals("credit_advance"))
            ? (input == null ? 180 : 148)
            : deposit ? 136 : Set.of("settings", "shop", "setup").contains(menu.page()) ? 154 : 184;
    if (menu.page().startsWith("corp_cc")) {
      drawLines(g, menu.lines(), 10, 54, 296, 130);
      return;
    }
    if (menu.page().startsWith("office_")
        && !menu.page().equals("office_home")
        && !menu.page().equals("office_choices")) {
      if (!choices("office_submit").isEmpty()) {
        if (menu.lines().size() > 1) drawLines(g, List.of(menu.lines().get(1)), 10, 35, 296, 61);
        if (Set.of("office_register", "office_issue").contains(menu.page()))
          g.drawString(font, "Business currency", 10, 133, MUTED, false);
        if (menu.page().equals("office_rights")) {
          g.drawString(font, "Permissions", 10, 55, MUTED, false);
          g.drawString(font, "Applies to", 10, 109, MUTED, false);
        }
        for (var field : officeFields)
          g.drawString(
              font,
              field.getMessage(),
              field.getX() - leftPos,
              field.getY() - topPos - 11,
              MUTED,
              false);
      } else drawLines(g, menu.lines(), 10, 55, 296, 182);
      return;
    }
    boolean rowPage = rowPage();
    if (rowPage
        && !menu.choices().isEmpty()
        && !menu.lines().isEmpty()
        && !menu.page().equals("corp_list")) {
      var headingLines = font.split(UiTextClient.component(menu.lines().getFirst()), 296);
      for (int i = 0; i < Math.min(2, headingLines.size()); i++)
        g.drawString(font, headingLines.get(i), 10, 53 + i * 10, MUTED, false);
    }
    boolean configurationForm = menu.page().equals("settings") && !choices("configure").isEmpty();
    if (menu.operation().state() == BankingOperation.State.PROCESSING || needsRetry()) {
      drawLines(g, menu.lines(), 10, 76, 296, 180);
    } else if (menu.page().equals("manage")) {
      var accounts = choices("account_close_preview");
      for (int i = 0; i < accounts.size(); i++)
        g.drawString(
            font,
            font.plainSubstrByWidth(accounts.get(i).label(), 186),
            10,
            79 + i * 21,
            TEXT,
            false);
      String label = "";
      g.drawString(font, label, 10, 151, MUTED, false);
    } else if (menu.page().equals("account_overview")) {
      drawAccount(g);
    } else if (menu.page().equals("home")) {
      if (menu.choices().isEmpty()) drawLines(g, menu.lines(), 10, 62, 296, 180);
    } else if (Set.of("history_detail", "history_rates").contains(menu.page())) {
      drawLines(g, menu.lines(), 10, 62, 296, 180);
    } else if (Set.of("history_dates", "history_rate_date").contains(menu.page())) {
      drawLines(g, menu.lines(), 10, 58, 296, 94);
    } else if (menu.page().equals("history") && choices("history_detail").isEmpty()) {
      drawLines(g, menu.lines(), 10, 76, 296, 180);
    } else if (menu.page().equals("settings")) {
      if (configurationForm) {
        g.drawString(
            font,
            ui("ui.client.cash_currency", String.join(", ", acceptedCurrencies)),
            10,
            132,
            MUTED,
            false);
        g.drawString(font, ui("ui.client.bank_binding"), 10, 146, MUTED, false);
      } else drawLines(g, menu.lines(), 10, 104, 296, 184);
    } else if (menu.page().equals("withdraw_mix")) {
      for (int i = 0; i < Math.min(2, menu.lines().size()); i++)
        g.drawString(
            font, font.plainSubstrByWidth(menu.lines().get(i), 296), 10, 55 + i * 11, TEXT, false);
      var rows = choices("withdraw_mix_change");
      for (int i = scroll; i < Math.min(rows.size(), scroll + 5); i++) {
        var choice = rows.get(i);
        String id = choice.value().replace("economy:", "");
        var denomination =
            ca.emilybelnavis.mcmods.economy.cash.CashCatalog.all().stream()
                .filter(d -> d.id().equals(id))
                .findFirst()
                .orElse(null);
        if (denomination != null) {
          var display =
              ca.emilybelnavis.mcmods.economy.cash.CashCatalog.all().stream()
                  .filter(d -> d.id().equals(id + "_horizontal"))
                  .findFirst()
                  .orElse(denomination);
          g.renderItem(
              new net.minecraft.world.item.ItemStack(display.item().get()),
              44,
              83 + (i - scroll) * CASH_ROW_STEP);
        }
        g.drawString(
            font,
            font.plainSubstrByWidth(choice.label(), 204),
            66,
            87 + (i - scroll) * CASH_ROW_STEP,
            TEXT,
            false);
      }

    } else if (menu.page().equals("corp_sources") && menu.choices().isEmpty()) {
      drawLines(g, menu.lines(), 10, 55, 296, 180);
    } else if (Set.of("corp_details", "corp_people", "corp_lookup", "corp_send", "corp_contribute")
        .contains(menu.page())) {
      drawLines(g, menu.lines(), 10, 55, 296, 183);
    } else if (menu.page().equals("corp_list")) {
      // The row list and the heading have independent scrolling.
      if (menu.page().equals("corp_list") && choices("corp_select").isEmpty()) {
        drawLines(g, menu.lines(), 10, 55, 296, 180);
      } else if (!menu.lines().isEmpty()) {
        g.drawString(
            font, font.plainSubstrByWidth(menu.lines().getFirst(), 296), 10, 55, TEXT, false);
        if (menu.choices().isEmpty())
          drawLines(g, menu.lines().stream().skip(1).toList(), 10, 75, 296, 180);
      }
    } else if (menu.page().equals("corp_account")) {
      drawLines(g, menu.lines(), 10, 55, 296, 106);
    } else if (!rowPage || menu.choices().isEmpty())
      drawLines(g, menu.lines(), x, y, 306 - x, bottom);
    if (rowPage)
      scrollbar(
          g,
          switch (menu.page()) {
            case "history" -> choices("history_detail").size();
            case "corp_list" -> choices("corp_select").size();
            case "corp_accounts" -> menu.choices().size();
            default -> menu.choices().size();
          });
    if (input != null) {
      String label = input.getMessage().getString();
      g.drawString(
          font,
          font.plainSubstrByWidth(label, 296),
          input.getX() - leftPos,
          input.getY() - topPos - 10,
          MUTED,
          false);
    }
    if (termInput != null) g.drawString(font, terminalLabel("Term"), 210, 154, MUTED, false);
    if (deposit)
      g.drawString(font, Component.translatable("container.inventory"), 12, 134, MUTED, false);
    if (deposit && !completedDeposit)
      drawLines(g, List.of(statusText()), 184, 144, 122, needsRetry() ? 164 : 182);
  }

  private static String terminalLabel(String english) {
    String key = "ui.terminal." + english.toLowerCase(Locale.ROOT).replace(' ', '_');
    return I18n.exists(key) ? ui(key) : english;
  }

  private boolean rowPage() {
    if (menu.page().equals("office_home") || menu.page().equals("office_choices")) return true;
    return Set.of(
            "accounts",
            "claims",
            "branch",
            "branch_targets",
            "brands",
            "branch_products",
            "cards",
            "card_funding",
            "credit_facilities",
            "payments",
            "history_filters",
            "history",
            "branch_services",
            "corp_list",
            "corp_sources",
            "corp_destinations",
            "corp_accounts",
            "account_settings",
            "credit_services",
            "loan_services",
            "terminal_accounts",
            "bill_accounts",
            "withdraw_sources",
            "withdrawal_sources",
            "card_services")
        .contains(menu.page());
  }

  private boolean declinedRowPage() {
    return menu.operation().state() == BankingOperation.State.DECLINED
        && menu.choices().isEmpty()
        && rowPage();
  }

  private List<String> declineDetails() {
    var details = new LinkedHashSet<String>();
    String reason = menu.operation().reasonKey();
    if (I18n.exists(reason) && !reason.equals("screen.economy.operation.declined"))
      details.add(operationText());
    details.add(cleanText(UiTextClient.render(menu.status())));
    for (var line : menu.lines()) details.add(cleanText(UiTextClient.render(line)));
    details.removeIf(String::isBlank);
    return details.isEmpty() ? List.of(operationText()) : List.copyOf(details);
  }

  private boolean needsScrollControls() {
    if (Set.of("home", "account_overview").contains(menu.page())) return false;
    if (!chooser.isEmpty()) return choices(chooser).size() > 6;
    if (declinedRowPage())
      return declineDetails().stream()
              .mapToInt(line -> font.split(Component.literal(line), 296).size())
              .sum()
          > 12;
    if (menu.page().equals("withdraw_mix")) return choices("withdraw_mix_change").size() > 5;
    if (menu.choices().size() > 6) return true;
    int textWidth = menu.page().equals("deposit") ? 142 : 296;
    int rows =
        menu.lines().stream()
            .mapToInt(line -> font.split(UiTextClient.component(line), textWidth).size())
            .sum();
    return rows > (menu.page().equals("deposit") ? 3 : menu.page().startsWith("finance_") ? 7 : 10);
  }

  private static String cleanText(String text) {
    return text.replaceAll(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "")
        .replaceAll("(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2})(?::\\d{2}(?:\\.\\d+)?)?Z", "$1 $2");
  }

  private String pageTitle() {
    if (menu.page().startsWith("corp_cc")) return "Corporate credit";
    if (menu.page().startsWith("office_"))
      return switch (menu.page()) {
        case "office_home", "office_choices" -> "Corporations";
        case "office_register" -> "Register company";
        case "office_issue" -> "Issue shares";
        case "office_review" -> "Review";
        case "office_rights" -> "Permissions";
        default -> "Corporation office";
      };
    if (chooser.equals("cash_modes")) return ui("ui.client.cash_services");
    if (menu.page().equals("finance_loan_payment")
        || menu.choices().stream()
            .anyMatch(
                c ->
                    Set.of(
                            "finance_credit_amount",
                            "finance_credit_pay_ok",
                            "finance_loan_repay",
                            "finance_loan_pay_ok")
                        .contains(c.action()))) return "Bill Payments";
    return switch (menu.page()) {
      case "home" -> "Home";
      case "accounts", "terminal_accounts" -> "My Accounts";
      case "account_overview" -> "Account";
      case "deposit" -> "Deposit";
      case "withdraw", "withdraw_sources", "withdrawal_sources", "credit_advance" -> "Withdrawal";
      case "payments", "bill_accounts" -> "Bill Payments";
      case "branch", "branch_services" -> "Branch Services";
      case "account_settings", "primary_bank" -> "Account Settings";
      case "credit_services" -> "Credit Services";
      case "loan_services" -> "Loan Services";
      case "cards", "card_funding", "card_services" -> "Card Services";
      case "fx" -> "Currency Exchange";
      default -> {
        String key = "screen.economy.tab." + menu.page();
        yield I18n.exists(key)
            ? I18n.get(key)
            : menu.page().startsWith("finance_loan")
                ? "Loans"
                : menu.page().startsWith("finance_credit") ? "Credit" : "Banking";
      }
    };
  }

  private void drawAccount(GuiGraphics g) {
    var lines = menu.lines();
    if (lines.size() < 3) {
      drawLines(g, lines, 10, 48, 296, 200);
      return;
    }
    g.drawString(font, terminalLabel("Current Balance"), 14, 43, MUTED, false);
    g.drawString(font, UiTextClient.component(lines.get(0)), 14, 57, TEXT, false);
    if (!lines.get(1).isBlank()) {
      g.drawString(font, terminalLabel("Available"), 172, 43, MUTED, false);
      g.drawString(font, UiTextClient.component(lines.get(1)), 172, 57, TEXT, false);
      g.pose().pushPose();
      g.pose().scale(.8f, .8f, 1);
      g.drawString(
          font,
          UiTextClient.component(lines.get(2)).copy().withStyle(net.minecraft.ChatFormatting.BOLD),
          215,
          91,
          TEXT,
          false);
      g.pose().popPose();
    }
    g.fill(10, 88, 306, 202, theme.accent());
    g.fill(11, 89, 305, 201, BankingTheme.CREAM);
    for (int i = 3; i < Math.min(lines.size() - 1, 8); i++) {
      var parts = UiTextClient.render(lines.get(i)).split("\t", 2);
      int y = 98 + (i - 3) * 20;
      int amountWidth = parts.length == 2 ? font.width(parts[1]) : 0;
      g.drawString(
          font,
          font.plainSubstrByWidth(parts[0], Math.max(0, 274 - amountWidth - 8)),
          16,
          y,
          TEXT,
          false);
      if (parts.length == 2)
        g.drawString(font, parts[1], 298 - font.width(parts[1]), y, TEXT, false);
    }
    String footer = UiTextClient.render(lines.getLast());
    g.drawString(font, footer, 158 - font.width(footer) / 2, 215, TEXT, false);
  }

  private String statusText() {
    return operationText() + " · " + UiTextClient.render(menu.status());
  }

  private String operationText() {
    Object[] arguments =
        menu.operation().args().stream()
            .map(
                argument ->
                    switch (argument.kind()) {
                      case MONEY ->
                          UiTextClient.render(
                              UiText.money(
                                  new ca.emilybelnavis.mcmods.economy.api.Money(
                                      EconomyCurrency.fromCode(argument.value()),
                                      argument.number())));
                      case CURRENCY, ID -> argument.value();
                      case INTEGER -> argument.number();
                      case UTC_INSTANT ->
                          java.time.Instant.ofEpochMilli(argument.number()).toString();
                      case MESSAGE_KEY -> Component.translatable(argument.value());
                    })
            .toArray();
    String reason = menu.operation().reasonKey();
    if (!I18n.exists(reason))
      reason =
          "screen.economy.operation." + menu.operation().state().name().toLowerCase(Locale.ROOT);
    return Component.translatable(reason, arguments).getString();
  }

  private void drawLines(GuiGraphics g, List<String> lines, int x, int y, int width, int bottom) {
    var wrapped = new ArrayList<net.minecraft.util.FormattedCharSequence>();
    for (var line : lines)
      wrapped.addAll(
          font.split(
              Component.literal(
                  (menu.page().startsWith("corp_") || menu.page().startsWith("office_"))
                      ? UiTextClient.render(line)
                      : cleanText(UiTextClient.render(line))),
              width));
    int visible = Math.max(1, (bottom - y) / 10);
    boolean body = y != 212 && y != 144;
    int start = body ? Math.clamp(scroll, 0, Math.max(0, wrapped.size() - visible)) : 0;
    if (body) scroll = start;
    g.flush();
    g.enableScissor(leftPos + x, topPos + y, leftPos + x + width, topPos + bottom);
    for (int i = start; i < Math.min(wrapped.size(), start + visible); i++)
      g.drawString(font, wrapped.get(i), x, y + (i - start) * 10, TEXT, false);
    g.flush();
    g.disableScissor();
  }

  private void scrollbar(GuiGraphics g, int count) {
    if (count <= 6) return;
    int track = ROW_STEP * 5 + ROW_HEIGHT;
    int thumb = track * 6 / count, y = 74 + (track - thumb) * scroll / Math.max(1, count - 6);
    g.fill(300, 74, 303, 74 + track, 0xff253e42);
    g.fill(300, y, 303, y + thumb, TEAL);
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    super.render(g, mouseX, mouseY, partialTick);
    renderTooltip(g, mouseX, mouseY);
  }

  @Override
  public void removed() {
    menu.setDepositSlotsVisible(true);
    super.removed();
  }

  private void scrollBy(int amount) {
    // Use the same bounded row/body path as the wheel; preserve keyboard focus on rebuilt controls.
    var focused = getFocused();
    Component message = focused instanceof Button b ? b.getMessage() : null;
    mouseScrolled(leftPos + 200, topPos + 100, 0, -amount);
    if (message != null)
      children().stream()
          .filter(Button.class::isInstance)
          .map(Button.class::cast)
          .filter(b -> b.getMessage().equals(message))
          .findFirst()
          .ifPresent(this::setFocused);
  }

  @Override
  public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    if (getFocused() instanceof EditBox field
        && field.canConsumeInput()
        && keyCode != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
        && keyCode != org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) {
      // Printable characters arrive in charTyped after this key event.
      field.keyPressed(keyCode, scanCode, modifiers);
      return true;
    }
    if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN
        || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP) {
      scrollBy(keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN ? 5 : -5);
      return true;
    }
    return super.keyPressed(keyCode, scanCode, modifiers);
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
    if (mouseX >= leftPos + 8
        && mouseX < leftPos + 308
        && mouseY >= topPos + 72
        && mouseY < topPos + 184) {
      if (declinedRowPage() && chooser.isEmpty()) {
        scroll = Math.max(0, scroll - (int) vertical);
      } else if (menu.page().equals("withdraw_mix")) {
        scroll =
            Math.clamp(
                scroll - (int) vertical, 0, Math.max(0, choices("withdraw_mix_change").size() - 5));
        rebuild();
      } else if (!chooser.isEmpty() || rowPage()) {
        int count =
            chooser.isEmpty()
                ? switch (menu.page()) {
                  case "history" -> choices("history_detail").size();
                  case "corp_list" -> choices("corp_select").size();
                  case "corp_accounts" -> menu.choices().size();
                  default -> menu.choices().size();
                }
                : choices(chooser).size();
        scroll = Math.clamp(scroll - (int) vertical, 0, Math.max(0, count - 6));
        rebuild();
      } else if (!menu.page().equals("deposit") || mouseX >= leftPos + 164)
        scroll = Math.max(0, scroll - (int) vertical);
      return true;
    }
    return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
  }
}
