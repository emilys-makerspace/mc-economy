package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.corporation.*;
import ca.emilybelnavis.mcmods.economy.institution.BankProviders;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Founder workflows share the terminal screen and the same reviewed operations as chat commands.
 */
public final class CorporationOffice {
  private CorporationOffice() {}

  private static final Map<BankingMenu, State> STATES = new WeakHashMap<>();

  private static final class State {
    UUID company, manager;
    String registrationName;
    Corporations.ShareIssue registrationShares;
    long membershipRevision;
    String companyName;
    EconomyCurrency setupCurrency;
    Corporations.State companyState;
    Map<UUID, Managers.Membership> roster = Map.of();
  }

  public static void home(ServerPlayer player, BankingMenu menu) {
    STATES.remove(menu);
    menu.setBusy(false);
    var service = new CorporationService(player);
    load(
        player,
        menu,
        service.companies(null, 4),
        rows -> {
          var choices = new ArrayList<BankingNetwork.Choice>();
          for (var company : rows.stream().limit(3).toList())
            choices.add(pick("company:" + company.id(), company.name()));
          if (rows.size() > 3) choices.add(page("companies", "All my corporations"));
          choices.add(page("register", "Register a corporation"));
          choices.add(page("banks", "Corporate banking"));
          choices.add(page("more", "More options"));
          menu.publish(
              "office_home",
              List.of(rows.isEmpty() ? "Get started" : "Your corporations"),
              "",
              choices);
        });
  }

  private static BankingNetwork.Choice page(String value, String label) {
    return new BankingNetwork.Choice("office_page", value, label, false);
  }

  private static BankingNetwork.Choice pick(String value, String label) {
    return new BankingNetwork.Choice("office_pick", value, label, false);
  }

  static String failureMessage(String code) {
    return switch (code) {
      case "CORPORATION_NAME_RESERVED" -> "That company name or identifier is already in use.";
      case "CORPORATION_CHOOSE_EXISTING_ACCOUNT_CURRENCY" ->
          "All four account slots are in use. Choose the currency of an existing company account.";
      case "INSUFFICIENT_FUNDS" -> "Your selected account has insufficient funds.";
      case "CORPORATION_STALE", "CORPORATION_FEE_STALE" ->
          "The company or fee changed. Open a fresh review.";
      case "CORPORATION_CLAIMS_OPEN" -> "Settle outstanding company obligations before closing.";
      default ->
          "The company request could not be completed. Reopen the company and check its current details.";
    };
  }

  static void completed(
      BankingMenu menu, ca.emilybelnavis.mcmods.economy.journal.Journal.Result receipt) {
    var state = STATES.get(menu);
    menu.setBusy(false);
    // A new registration is the first revision of an active company.
    if (state != null
        && state.registrationShares != null
        && receipt.code().equals("CORPORATION_ACTIVE")
        && receipt.payload().endsWith("|1")) {
      state.company =
          UUID.fromString(receipt.payload().substring(0, receipt.payload().indexOf('|')));
      state.setupCurrency = state.registrationShares.price().currency();
      menu.publish(
          "office_result",
          List.of(
              state.registrationName
                  + " is registered. Open a "
                  + state.setupCurrency
                  + " account to finish setting it up."),
          "",
          List.of(page("banks", "Choose a bank")));
    } else {
      menu.publish(
          "office_result",
          List.of("Company changes saved."),
          "",
          List.of(page("companies", "My corporations")));
    }
  }

  static void result(BankingMenu menu, String message) {
    menu.setBusy(false);
    menu.publish("office_result", lines(message), "", List.of());
  }

  static void review(BankingMenu menu, UUID token, String message, boolean registration) {
    var buttons = new ArrayList<BankingNetwork.Choice>();
    buttons.add(new BankingNetwork.Choice("office_approve", token.toString(), "Confirm", false));
    if (registration) buttons.add(page("register", "Edit company details"));
    menu.setBusy(false);
    menu.publish("office_review", lines(message), "", buttons);
  }

  private static List<String> lines(String description) {
    var lines = new ArrayList<String>();
    for (var paragraph : description.split("\n")) {
      var line = new StringBuilder();
      for (var word : paragraph.split(" ")) {
        if (line.length() + word.length() > 220) {
          lines.add(line.toString());
          line.setLength(0);
        }
        if (!line.isEmpty()) line.append(' ');
        line.append(word);
      }
      if (!line.isEmpty()) lines.add(line.toString());
    }
    return lines;
  }

  private static void list(BankingMenu menu, String title, List<BankingNetwork.Choice> choices) {
    menu.setBusy(false);
    menu.publish("office_choices", List.of(title), "", choices);
  }

  private static void form(BankingMenu menu, String type, String title) {
    menu.publish(
        "office_" + type,
        List.of(title),
        "",
        List.of(new BankingNetwork.Choice("office_submit", "", "Continue", false)));
  }

  private static <T> void load(
      ServerPlayer player, BankingMenu menu, CompletionStage<T> result, Consumer<T> show) {
    long revision = menu.revision();
    menu.setBusy(true);
    result.whenCompleteAsync(
        (value, failure) -> {
          if (!menu.valid(player) || revision != menu.revision()) return;
          menu.setBusy(false);
          if (failure != null) {
            result(menu, "Unable to load the requested information.");
            return;
          }
          try {
            show.accept(value);
          } catch (IllegalArgumentException | IllegalStateException e) {
            result(menu, e.getMessage());
          }
        },
        player.server);
  }

  public static Corporations.ShareIssue shareIssue(String quantity, String price, String code) {
    try {
      var currency = EconomyCurrency.fromCode(code.toUpperCase(Locale.ROOT));
      if (!price.matches("[0-9]{1,19}(\\.[0-9]{1,2})?"))
        throw new IllegalArgumentException(
            "Enter a positive share price in the business currency.");
      return new Corporations.ShareIssue(
          Long.parseLong(quantity),
          new Money(
              currency,
              new BigDecimal(price).movePointRight(currency.fractionDigits()).longValueExact()));
    } catch (ArithmeticException | NumberFormatException e) {
      throw new IllegalArgumentException(
          "Share quantity, price or total is outside the supported range.");
    }
  }

  static String shareTerms(Corporations.ShareIssue issue) {
    var price = issue.price();
    var total = issue.total();
    return issue.quantity()
        + " Class A shares at "
        + price.currency()
        + " "
        + price.majorUnits().toPlainString()
        + " each. Required deposit: "
        + total.currency()
        + " "
        + total.majorUnits().toPlainString();
  }

  public static void action(ServerPlayer player, BankingMenu menu, String action, String value) {
    if (menu.access() != BankingMenu.Access.CORPORATION || !menu.valid(player)) return;
    if (action.equals("home")) {
      home(player, menu);
      return;
    }
    if (menu.busy()
        || menu.choices().stream()
            .noneMatch(
                c ->
                    c.action().equals(action)
                        && (action.equals("office_submit") || c.value().equals(value)))) return;
    var state = STATES.computeIfAbsent(menu, k -> new State());
    var service = new CorporationService(player);
    var commands = new CorporationCommands(player, menu, null);
    try {
      switch (action) {
        case "office_page" -> navigate(player, menu, state, service, commands, value);
        case "office_pick" -> select(player, menu, state, service, commands, value);
        case "office_submit" -> submit(player, menu, state, service, commands, value);
        case "office_approve" -> commands.approve(UUID.fromString(value));
        default -> throw new IllegalArgumentException("Unknown action");
      }
    } catch (IllegalStateException | IllegalArgumentException e) {
      String message = Objects.toString(e.getMessage(), "Check the details and try again.");
      if (action.equals("office_submit") && !menu.page().equals("office_rights"))
        menu.publish(menu.page(), List.of(menu.lines().getFirst(), message), "", menu.choices());
      else result(menu, message);
    }
  }

  private static void navigate(
      ServerPlayer player,
      BankingMenu menu,
      State state,
      CorporationService service,
      CorporationCommands commands,
      String value) {
    if (value.startsWith("companies:") || value.equals("companies")) {
      UUID after = value.equals("companies") ? null : UUID.fromString(value.substring(10));
      load(
          player,
          menu,
          service.companies(after, 17),
          rows -> {
            var choices = new ArrayList<BankingNetwork.Choice>();
            rows.stream().limit(16).forEach(c -> choices.add(pick("company:" + c.id(), c.name())));
            if (rows.size() > 16)
              choices.add(page("companies:" + rows.get(15).id(), "Next companies"));
            list(
                menu,
                choices.isEmpty() ? "You have no corporations yet." : "Select a corporation",
                choices.isEmpty() ? List.of(page("register", "Register a corporation")) : choices);
          });
      return;
    }
    if (value.startsWith("roster:") || value.equals("roster")) {
      UUID after = value.equals("roster") ? null : UUID.fromString(value.substring(7));
      load(
          player,
          menu,
          service.managers(state.company, after, 17),
          found -> {
            var rows = found.orElseThrow();
            state.roster =
                rows.stream()
                    .collect(
                        java.util.stream.Collectors.toMap(Managers.Membership::manager, m -> m));
            var choices = new ArrayList<BankingNetwork.Choice>();
            rows.stream()
                .limit(16)
                .forEach(
                    m ->
                        choices.add(
                            pick(
                                "manager:" + m.manager() + ":" + m.revision() + ":" + m.active(),
                                playerName(player, m.manager())
                                    + (m.active() ? "" : " (removed)"))));
            if (state.companyState == Corporations.State.ACTIVE)
              choices.add(page("appoint", "Add a manager"));
            choices.add(pick("company:" + state.company, "Back to company"));
            if (rows.size() > 16)
              choices.add(page("roster:" + rows.get(15).manager(), "Next managers"));
            list(menu, "Director: " + player.getGameProfile().getName(), choices);
          });
      return;
    }
    switch (value) {
      case "more" ->
          list(
              menu,
              "More options",
              List.of(
                  page("latest_receipt", "Latest receipt"),
                  page("receipts", "Find a receipt by reference"),
                  page("integrations", "Connected mods")));
      case "latest_receipt" -> commands.latestReceipt();
      case "settings" -> {
        var choices = new ArrayList<BankingNetwork.Choice>();
        if (state.companyState == Corporations.State.ACTIVE) {
          choices.add(page("rename", "Rename corporation"));
          choices.add(page("wind", "Begin wind-down"));
        } else if (state.companyState == Corporations.State.WINDING_DOWN) {
          choices.add(page("resume", "Resume operations"));
          choices.add(page("dissolve", "Dissolve corporation"));
        }
        choices.add(pick("company:" + state.company, "Back to company"));
        list(menu, state.companyName, choices);
      }
      case "register" -> form(menu, "register", "Register a corporation");
      case "banks" ->
          list(
              menu,
              state.setupCurrency == null
                  ? "Choose a bank"
                  : "Choose a bank for your " + state.setupCurrency + " account",
              InstitutionCatalog.banks().stream()
                  .filter(
                      bank ->
                          state.setupCurrency == null || bank.baseCurrency() == state.setupCurrency)
                  .map(b -> pick("bank:" + b.id().key(), b.name() + " (" + b.baseCurrency() + ")"))
                  .toList());
      case "issue" -> form(menu, "issue", "Issue Class A shares");
      case "rename" -> form(menu, "rename", "Rename " + state.companyName);
      case "wind" -> commands.lifecycle(state.company, Corporations.Action.WIND_DOWN, "");
      case "resume" -> commands.lifecycle(state.company, Corporations.Action.CANCEL, "");
      case "dissolve" -> commands.lifecycle(state.company, Corporations.Action.DISSOLVE, "");
      case "appoint" -> {
        state.membershipRevision = 0;
        form(menu, "appoint", "Appoint a manager");
      }
      case "rights" -> managerRights(player, menu, state);
      case "remove" ->
          commands.changeManager(
              state.company,
              state.manager,
              Managers.Mutation.REVOKE,
              state.membershipRevision,
              "none",
              "none");
      case "restore_manager" ->
          commands.changeManager(
              state.company,
              state.manager,
              Managers.Mutation.APPOINT,
              state.membershipRevision,
              "view",
              "all");
      case "receipts" -> form(menu, "receipt", "Find a management receipt");
      case "integrations" -> CorporationConsent.office(player, menu);
      case "revoke", "grants_next", "grants_first" ->
          CorporationConsent.grants(player, menu, value);
      case "integration_approve", "integration_lookup" ->
          CorporationConsent.officeAction(player, menu, value);
      default -> throw new IllegalArgumentException("Action unavailable");
    }
  }

  private static void managerRights(ServerPlayer player, BankingMenu menu, State state) {
    var member = Objects.requireNonNull(state.roster.get(state.manager));
    var company = member.company();
    var services = Economy.serverServices(player.server);
    var actor = new PlayerId(player.getUUID());
    var owner = new PartyRef(services.partyWorld(), new PartyRef.Player(actor));
    var grant = PartyAuthorization.player(player, Economy::serverServices, owner).orElseThrow();
    load(
        player,
        menu,
        services.readAccounts(
            grant,
            false,
            reads ->
                Corporations.find(reads, actor, company)
                    .map(
                        found -> {
                          var choices = new ArrayList<BankingNetwork.Choice>();
                          var rights = member.rights();
                          for (var capability : Managers.Capability.values()) {
                            String key =
                                switch (capability) {
                                  case VIEW_HISTORY -> "view";
                                  case PAY_TRANSFER -> "pay";
                                  case MANAGE_ACCOUNTS -> "accounts";
                                  case BORROW -> "borrow";
                                };
                            choices.add(
                                new BankingNetwork.Choice(
                                    "office_submit",
                                    "right:" + key,
                                    key,
                                    rights.capabilities().contains(capability)));
                          }
                          choices.add(
                              new BankingNetwork.Choice(
                                  "office_submit",
                                  "scope:all",
                                  "All accounts",
                                  rights.scope().allAccounts()));
                          for (var account :
                              CorporateAccounts.list(reads, owner.world(), company)) {
                            var bank =
                                BankProviders.find(reads, account.id())
                                    .filter(b -> !b.legacy())
                                    .map(
                                        b ->
                                            InstitutionCatalog.require(
                                                    new InstitutionCatalog.InstitutionId(
                                                        b.bankKey()))
                                                .name())
                                    .orElse("Bank");
                            choices.add(
                                new BankingNetwork.Choice(
                                    "office_submit",
                                    "scope:" + account.id().value(),
                                    bank + " · " + account.currency(),
                                    rights.scope().accounts().contains(account.id().value())));
                          }
                          return choices;
                        })),
        found ->
            menu.publish(
                "office_rights", List.of("Change manager permissions"), "", found.orElseThrow()));
  }

  static String playerName(ServerPlayer player, UUID id) {
    var live = player.server.getPlayerList().getPlayer(id);
    if (live != null) return live.getGameProfile().getName();
    var cache = player.server.getProfileCache();
    return cache == null
        ? "Unknown player"
        : cache.get(id).map(com.mojang.authlib.GameProfile::getName).orElse("Unknown player");
  }

  private static void select(
      ServerPlayer player,
      BankingMenu menu,
      State state,
      CorporationService service,
      CorporationCommands commands,
      String value) {
    if (value.startsWith("grant:")) {
      CorporationConsent.selectGrant(player, menu, value);
      return;
    }
    if (value.startsWith("bank:")) {
      menu.selectCorporateBank(
          InstitutionCatalog.require(new InstitutionCatalog.InstitutionId(value.substring(5)))
              .id());
      if (state.company == null) BankingAccounts.action(player, menu, "corp_list", "");
      else
        load(
            player,
            menu,
            service.find(state.company),
            found -> BankingAccounts.openOfficeAccounts(player, menu, found.orElseThrow()));
      return;
    }
    if (value.startsWith("fee:")) {
      commands.registration(
          UUID.fromString(value.substring(4)), state.registrationName, state.registrationShares);
      return;
    }
    if (value.startsWith("manager:")) {
      var f = value.split(":");
      state.manager = UUID.fromString(f[1]);
      state.membershipRevision = Long.parseLong(f[2]);
      list(
          menu,
          playerName(player, state.manager),
          Boolean.parseBoolean(f[3])
              ? List.of(page("rights", "Change permissions"), page("remove", "Remove manager"))
              : List.of(page("restore_manager", "Reappoint manager")));
      return;
    }
    var id = UUID.fromString(value.substring("company:".length()));
    load(
        player,
        menu,
        service.find(id),
        found -> {
          var company = found.orElseThrow();
          state.company = id;
          state.companyName = company.name();
          load(
              player,
              menu,
              service.profile(id),
              profile -> {
                var capital = profile.orElseThrow();
                var choices = new ArrayList<BankingNetwork.Choice>();
                if (capital.required() == null && company.state() == Corporations.State.ACTIVE)
                  choices.add(page("issue", "Issue Class A shares"));
                state.setupCurrency =
                    !capital.funded() && capital.required() != null
                        ? capital.required().currency()
                        : null;
                if (company.state() != Corporations.State.DISSOLVED)
                  choices.add(
                      page(
                          "banks",
                          state.setupCurrency == null ? "Bank accounts" : "Fund share capital"));
                choices.add(page("roster", "People and permissions"));
                state.companyState = company.state();
                if (company.state() != Corporations.State.DISSOLVED)
                  choices.add(page("settings", "Company settings"));
                list(menu, company.name(), choices);
              });
        });
  }

  private static void submit(
      ServerPlayer player,
      BankingMenu menu,
      State state,
      CorporationService service,
      CorporationCommands commands,
      String value) {
    var f = value.split("\\t", -1);
    switch (menu.page()) {
      case "office_register" -> {
        require(f, 4);
        state.registrationName = Corporations.validateName(f[0]);
        state.registrationShares = shareIssue(f[1], f[2], f[3]);
        load(
            player,
            menu,
            service.fundingAccounts(),
            rows -> {
              var choices = new ArrayList<BankingNetwork.Choice>();
              for (var account : rows)
                choices.add(
                    pick(
                        "fee:" + account.id().value(),
                        account.currency() + " account" + (account.primary() ? " (primary)" : "")));
              choices.add(page("register", "Edit company details"));
              list(
                  menu,
                  rows.isEmpty()
                      ? "Open and fund a personal account to pay the fee."
                      : "Which account will pay the registration fee?",
                  choices);
            });
      }
      case "office_issue" -> {
        require(f, 3);
        commands.issue(state.company, shareIssue(f[0], f[1], f[2]));
      }
      case "office_rename" -> {
        require(f, 1);
        commands.lifecycle(state.company, Corporations.Action.RENAME, f[0]);
      }
      case "office_appoint" -> {
        require(f, 1);
        var target = player.server.getPlayerList().getPlayerByName(f[0]);
        UUID id =
            target == null
                ? Optional.ofNullable(player.server.getProfileCache())
                    .flatMap(c -> c.get(f[0]))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown player name"))
                    .getId()
                : target.getUUID();
        commands.changeManager(state.company, id, Managers.Mutation.APPOINT, 0, "view", "all");
      }
      case "office_rights" -> {
        require(f, 2);
        commands.changeManager(
            state.company,
            state.manager,
            Managers.Mutation.CHANGE,
            state.membershipRevision,
            f[0],
            f[1]);
      }
      case "office_receipt" -> {
        require(f, 1);
        try {
          commands.receipt(UUID.fromString(f[0].trim()));
        } catch (IllegalArgumentException invalid) {
          result(menu, "Enter the reference from your receipt.");
        }
      }
      default -> throw new IllegalArgumentException("Form unavailable");
    }
  }

  private static void require(String[] f, int n) {
    if (f.length != n) throw new IllegalArgumentException("Complete the displayed form.");
  }
}
