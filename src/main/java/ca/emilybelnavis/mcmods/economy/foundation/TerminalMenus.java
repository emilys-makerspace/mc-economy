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

/**
 * Small, bank-scoped terminal navigation. Financial submissions remain in their existing services.
 */
public final class TerminalMenus {
  private TerminalMenus() {}

  public static final Set<String> ACTIONS =
      Set.of(
          "terminal_account",
          "terminal_history_prev",
          "terminal_history_next",
          "terminal_settings",
          "terminal_credit",
          "terminal_loans",
          "terminal_cards",
          "terminal_bill",
          "terminal_withdraw_bank",
          "terminal_withdraw_credit",
          "terminal_accounts_page",
          "terminal_primary");
  private static final Map<BankingMenu, State> STATES = new WeakHashMap<>();

  private static final class State {
    String selected = "";
    int page;
    int accountPage;
    boolean bills;
    Set<String> offered = Set.of();
  }

  record View(
      String page, List<String> lines, List<BankingNetwork.Choice> choices, Set<String> offered) {}

  private static BankingNetwork.Choice choice(String action, String value, String label) {
    return new BankingNetwork.Choice(action, value, label, false);
  }

  public static boolean handles(String action) {
    return ACTIONS.contains(action)
        || Set.of("accounts", "payments", "manage_accounts", "withdraw").contains(action);
  }

  public static void closed(BankingMenu menu) {
    STATES.remove(menu);
  }

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
      services
          .readAccounts(
              grant,
              false,
              reads -> {
                var id = UUID.fromString(value);
                if (!credit)
                  return AccountCatalog.find(reads, grant.target(), new AccountId(id))
                      .filter(a -> a.status() == AccountIdentity.Status.OPEN)
                      .map(AccountIdentity::id);
                var facility =
                    CreditFacilities.find(reads, id)
                        .filter(f -> f.party().equals(grant.target()))
                        .orElseThrow();
                return AccountCatalog.openBanks(reads, grant.target()).stream()
                    .filter(a -> a.currency() == facility.currency())
                    .map(AccountIdentity::id)
                    .findFirst();
              })
          .whenComplete(
              (account, error) -> {
                if (!audience.live()) return;
                menu.setBusy(false);
                if (error != null || account.isEmpty()) {
                  audience.reply(
                      "withdraw_sources",
                      List.of(UiText.t("ui.terminal.advance_requires_account")),
                      "",
                      List.of());
                  return;
                }
                BankingAccounts.selectTerminalAccount(menu, account.orElseThrow());
                if (credit) {
                  CreditUse.selectFacility(menu, UUID.fromString(value));
                  CreditUse.action(player, menu, "credit_advance", "");
                } else BankingWithdrawals.action(player, menu, "withdraw", "");
              });
      return;
    }
    if (action.equals("terminal_bill")) {
      String[] parts = value.split("/", 2);
      // Re-offer only the selected debt to the existing reviewed repayment flow.
      String target = parts[0].equals("credit") ? "finance_credit_pay" : "finance_loan_view";
      menu.publish(
          "payments",
          List.of(),
          "",
          List.of(choice(target, parts[1], UiText.t("ui.terminal.continue"))));
      if (parts[0].equals("credit"))
        CreditMenus.terminalPayment(player, menu, UUID.fromString(parts[1]));
      else LoanMenus.action(player, menu, target, parts[1]);
      return;
    }
    if (action.equals("accounts") || action.equals("payments")) {
      state.accountPage = 0;
      state.bills = action.equals("payments");
    }
    if (action.equals("terminal_accounts_page")) state.accountPage = Integer.parseInt(value);
    if (action.equals("terminal_account")) {
      if (!state.offered.contains(value)) return;
      state.selected = value;
      state.page = 0;
    }
    if (action.equals("terminal_history_prev")) state.page = Math.max(0, state.page - 1);
    if (action.equals("terminal_history_next")) state.page++;
    var cards =
        java.util.stream.Stream.of(player.getMainHandItem(), player.getOffhandItem())
            .map(stack -> CardStackIdentity.mark(stack, services.worldId()))
            .flatMap(Optional::stream)
            .map(CardStackIdentity.Mark::token)
            .toList();
    menu.setBusy(true);
    services
        .readAccounts(
            grant,
            false,
            reads -> {
              String bank = bank(reads, grant, menu, services.financialMillis());
              if (menu.remote() && action.equals("accounts") && menu.issuedToken() != null) {
                var credit =
                    CreditFacilities.usableToken(
                        reads,
                        grant.target().world(),
                        ((PartyRef.Player) grant.target().owner()).id(),
                        menu.issuedToken(),
                        services.financialMillis());
                if (credit.isPresent()) {
                  var token = credit.orElseThrow();
                  var key = "credit/" + token.facility();
                  return Optional.of(
                      new View(
                          "terminal_accounts",
                          List.of(),
                          List.of(
                              choice(
                                  "terminal_account",
                                  key,
                                  UiText.t("ui.terminal.account.credit_short"))),
                          Set.of(key)));
                }
              }
              if (action.equals("withdraw"))
                return Optional.of(
                    withdrawal(
                        reads,
                        grant.target(),
                        bank,
                        cards,
                        services.financialMillis(),
                        menu.access()));
              if (action.equals("accounts")
                  || action.equals("payments")
                  || action.equals("terminal_accounts_page"))
                return Optional.of(
                    accounts(reads, grant.target(), bank, state.bills, state.accountPage));
              if (action.equals("manage_accounts")
                  || action.startsWith("terminal_")
                      && Set.of(
                              "terminal_settings",
                              "terminal_credit",
                              "terminal_loans",
                              "terminal_cards",
                              "terminal_primary")
                          .contains(action))
                return Optional.of(branch(reads, grant.target(), bank, action));
              return Optional.of(
                  detail(
                      reads,
                      grant.target(),
                      bank,
                      state.selected,
                      state.page,
                      services.financialMillis()));
            })
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              if (error != null || found.isEmpty()) {
                audience.reply(
                    "accounts", List.of(UiText.t("ui.terminal.unavailable")), "", List.of());
                return;
              }
              var view = found.orElseThrow();
              if (!view.offered().isEmpty() || action.equals("accounts"))
                state.offered = view.offered();
              audience.reply(view.page(), view.lines(), "", view.choices());
            });
  }

  /**
   * Presentation restriction uses the held design; token ownership/status is rechecked by the
   * controller.
   */
  public static boolean withdrawalOnly(ServerPlayer player, String bank) {
    var card =
        java.util.stream.Stream.of(player.getMainHandItem(), player.getOffhandItem())
            .map(CardCatalog::find)
            .flatMap(Optional::stream)
            .findFirst()
            .orElse(null);
    return card == null
        || !card.issuer().key().equals(bank)
        || CreditCardDesigns.byId(card.id()).isPresent();
  }

  static String bank(
      JournalEngine.ReadView reads, PartyAuthorization.Grant grant, BankingMenu menu, long now) {
    if (menu.remote() && menu.issuedToken() != null) {
      var credit =
          CreditFacilities.usableToken(
              reads,
              grant.target().world(),
              ((PartyRef.Player) grant.target().owner()).id(),
              menu.issuedToken(),
              now);
      if (credit.isPresent()) return credit.orElseThrow().issuer().key();
      return IssuedCards.usable(
              reads,
              grant.target().world(),
              ((PartyRef.Player) grant.target().owner()).id(),
              menu.issuedToken(),
              now)
          .map(t -> t.issuer().key())
          .orElseThrow();
    }
    var terminal =
        TerminalPolicy.find(reads, grant.target().world(), menu.anchor(), grant.actor(), true)
            .orElse(null);
    return terminal == null || terminal.binding() == null || !terminal.binding().bound()
        ? ""
        : terminal.binding().institution().key();
  }

  static List<AccountIdentity> bankAccounts(
      JournalEngine.ReadView reads, PartyRef owner, String bank) {
    return AccountCatalog.openBanks(reads, owner).stream()
        .filter(
            a ->
                bank.isEmpty()
                    || BankProviders.find(reads, owner, a.id())
                        .map(p -> p.bankKey().equals(bank))
                        .orElse(false))
        .toList();
  }

  static View accounts(
      JournalEngine.ReadView reads, PartyRef owner, String bank, boolean bills, int page) {
    if (page < 0 || page > 100_000) throw new IllegalArgumentException("Account page");
    var choices = new ArrayList<BankingNetwork.Choice>();
    var offered = new HashSet<String>();
    var parameters = new ArrayList<String>();
    String banks =
        "SELECT 'bank' AS kind,a.account_id AS id,a.currency FROM account_catalog a JOIN bank_account_providers p ON p.account_id=a.account_id WHERE a.owner_id=? AND a.purpose='BANK' AND a.status='OPEN' AND (?='' OR p.bank_key=?)";
    String debts =
        "SELECT 'credit' AS kind,facility_id AS id,currency FROM credit_facilities WHERE world_id=? AND owner_id=? AND (?='' OR issuer=?) UNION ALL SELECT 'loan' AS kind,loan_id AS id,currency FROM loans WHERE world_id=? AND owner_id=? AND (?='' OR lender=?)";
    if (!bills) parameters.addAll(List.of(player(owner), bank, bank));
    parameters.addAll(
        List.of(
            owner.world().value().toString(),
            player(owner),
            bank,
            bank,
            owner.world().value().toString(),
            player(owner),
            bank,
            bank,
            Integer.toString(page * 6)));
    var rows =
        reads.query(
            "SELECT * FROM ("
                + (bills ? "" : banks + " UNION ALL ")
                + debts
                + ") ORDER BY kind,id LIMIT 7 OFFSET ?",
            parameters,
            7);
    for (var row : rows.stream().limit(6).toList()) {
      String key = row.get("kind") + "/" + row.get("id");
      offered.add(key);
      choices.add(
          choice(
              bills ? "terminal_bill" : "terminal_account",
              key,
              accountLabel(row.get("kind"), row.get("currency"))));
    }
    if (page > 0)
      choices.add(
          choice(
              "terminal_accounts_page",
              Integer.toString(page - 1),
              UiText.t("ui.terminal.accounts.previous")));
    if (rows.size() > 6)
      choices.add(
          choice(
              "terminal_accounts_page",
              Integer.toString(page + 1),
              UiText.t("ui.terminal.accounts.next")));
    return new View(
        bills ? "payments" : "terminal_accounts",
        choices.isEmpty()
            ? List.of(
                bills
                    ? UiText.t("ui.terminal.accounts.no_bills")
                    : UiText.t("ui.terminal.accounts.empty"))
            : List.of(),
        choices,
        offered);
  }

  private static View withdrawal(
      JournalEngine.ReadView reads,
      PartyRef owner,
      String bank,
      List<UUID> cards,
      long now,
      BankingMenu.Access access) {
    var choices = new ArrayList<BankingNetwork.Choice>();
    var player = ((PartyRef.Player) owner.owner()).id();
    var debit =
        cards.stream()
            .map(t -> IssuedCards.usable(reads, owner.world(), player, t, now))
            .flatMap(Optional::stream)
            .findFirst()
            .orElse(null);
    var credit =
        cards.stream()
            .map(t -> CreditFacilities.usableToken(reads, owner.world(), player, t, now))
            .flatMap(Optional::stream)
            .findFirst()
            .orElse(null);
    var accounts =
        access == BankingMenu.Access.ATM
            ? AccountCatalog.openBanks(reads, owner).stream()
                .filter(a -> debit != null && debit.account().equals(a.id()))
                .toList()
            : bankAccounts(reads, owner, bank);
    for (var account : accounts)
      choices.add(
          choice(
              "terminal_withdraw_bank",
              account.id().value().toString(),
              accountLabel("bank", account.currency().code())));
    for (var facility : CreditFacilities.history(reads, owner, null, 16))
      if (facility.status() == CreditFacilities.Status.ACTIVE
          && (access == BankingMenu.Access.ATM
              ? credit != null && credit.facility().equals(facility.id())
                  || debit != null && facility.issuer().key().equals(bank)
              : facility.issuer().key().equals(bank)))
        choices.add(
            choice(
                "terminal_withdraw_credit",
                facility.id().toString(),
                accountLabel("credit", facility.currency().code())));
    return new View(
        "withdraw_sources",
        choices.isEmpty()
            ? List.of(UiText.t("ui.terminal.withdraw.empty"))
            : List.of(UiText.t("ui.terminal.withdraw.choose")),
        choices,
        Set.of());
  }

  private static View branch(
      JournalEngine.ReadView reads, PartyRef owner, String bank, String action) {
    var rows = new ArrayList<BankingNetwork.Choice>();
    var accounts = bankAccounts(reads, owner, bank);
    String page = "branch_services";
    switch (action) {
      case "terminal_settings" -> {
        page = "account_settings";
        var primary = AccountCatalog.primary(reads, owner).orElse(null);
        if (primary != null) {
          var provider = BankProviders.find(reads, owner, primary.id()).orElse(null);
          if (provider != null && provider.bankKey().equals(bank))
            rows.add(
                choice(
                    "branch_switch",
                    primary.id().value().toString(),
                    UiText.t("ui.terminal.primary.change")));
          else rows.add(choice("terminal_primary", "", UiText.t("ui.terminal.primary.assign")));
        }
        for (var a : accounts) {
          if (!a.primary())
            rows.add(
                choice(
                    "branch_close",
                    a.id().value().toString(),
                    UiText.t("ui.terminal.account.close")));
        }
      }
      case "terminal_primary" -> {
        page = "primary_bank";
        var primary = AccountCatalog.primary(reads, owner).orElseThrow();
        var target = InstitutionCatalog.require(new InstitutionCatalog.InstitutionId(bank));
        if (target.baseCurrency() != primary.currency() || !target.tier1())
          return new View(
              page,
              List.of(UiText.t("ui.terminal.primary.currency_required", primary.currency().code())),
              rows,
              Set.of());
        var provider = BankProviders.find(reads, owner, primary.id()).orElseThrow();
        String id = primary.id().value().toString();
        if (provider.bankKey().equals(bank)) {
          rows.add(choice("branch_switch", id, UiText.t("ui.terminal.primary.change")));
          return new View(page, List.of(UiText.t("ui.terminal.primary.already")), rows, Set.of());
        }
        if (provider.bankKey().equals(BankProviders.UNASSIGNED_PROVIDER)) {
          rows.add(choice("branch_assign", id, UiText.t("ui.terminal.primary.confirm")));
          return new View(
              page,
              List.of(UiText.t("ui.terminal.primary.assign_review", target.name())),
              rows,
              Set.of());
        }
        var pending =
            reads.query(
                "SELECT target_key FROM bank_provider_switches WHERE account_id=?", List.of(id), 1);
        if (!pending.isEmpty() && pending.getFirst().get("target_key").equals(bank)) {
          rows.add(choice("branch_accept", id, UiText.t("ui.terminal.primary.confirm")));
          return new View(
              page,
              List.of(
                  UiText.t("ui.terminal.primary.complete_review", target.name()),
                  UiText.t("ui.terminal.primary.card_cancelled")),
              rows,
              Set.of());
        }
        String source =
            InstitutionCatalog.require(new InstitutionCatalog.InstitutionId(provider.bankKey()))
                .name();
        return new View(
            page,
            List.of(
                UiText.t("ui.terminal.primary.visit_source", source),
                UiText.t("ui.terminal.primary.return_target", target.name())),
            rows,
            Set.of());
      }
      case "terminal_credit" -> {
        page = "credit_services";
        rows.add(choice("finance_credit_apply", "", UiText.t("ui.terminal.credit.apply")));
        rows.add(choice("credit_profile", "", UiText.t("ui.terminal.credit.profile")));
        rows.add(choice("finance_report", "", UiText.t("ui.terminal.credit.report")));
        if (CreditFacilities.history(reads, owner, null, 16).stream()
            .anyMatch(
                f ->
                    f.issuer().key().equals(bank)
                        && f.status() == CreditFacilities.Status.ACTIVE
                        && f.access() == CreditFacilities.Access.ENABLED
                        && f.requirements().stream().noneMatch(r -> r.unmetMinor() > 0)))
          rows.add(choice("finance_credit_raise", "", UiText.t("ui.terminal.credit.increase")));
      }
      case "terminal_loans" -> {
        page = "loan_services";
        rows.add(choice("finance_loan_apply", "", UiText.t("ui.terminal.loan.apply")));
        rows.add(choice("credit_profile", "", UiText.t("ui.terminal.credit.profile")));
        rows.add(choice("finance_report", "", UiText.t("ui.terminal.credit.report")));
      }
      case "terminal_cards" -> {
        page = "card_services";
        rows.add(choice("cards", "", UiText.t("ui.terminal.cards.bank")));
        rows.add(choice("finance_credit_cards", "", UiText.t("ui.terminal.cards.credit")));
        rows.add(choice("claims", "", UiText.t("ui.terminal.cards.collect")));
      }
      default -> {
        if (accounts.isEmpty())
          rows.add(choice("branch_open", "", UiText.t("ui.terminal.account.open")));
        if (!accounts.isEmpty() || AccountCatalog.primary(reads, owner).isPresent())
          rows.add(choice("terminal_settings", "", UiText.t("ui.terminal.account.settings")));
        rows.add(choice("terminal_credit", "", UiText.t("ui.terminal.services.credit")));
        rows.add(choice("terminal_loans", "", UiText.t("ui.terminal.services.loan")));
        rows.add(choice("terminal_cards", "", UiText.t("ui.terminal.services.card")));
        rows.add(choice("fx", "", UiText.t("ui.terminal.services.fx")));
      }
    }
    return new View(page, List.of(), rows, Set.of());
  }

  static View detail(
      JournalEngine.ReadView reads,
      PartyRef owner,
      String bank,
      String selected,
      int page,
      long now) {
    var parts = selected.split("/", 2);
    UUID id = UUID.fromString(parts[1]);
    var lines = new ArrayList<String>();
    EconomyCurrency currency;
    if (parts[0].equals("bank")) {
      var account =
          bankAccounts(reads, owner, bank).stream()
              .filter(a -> a.id().value().equals(id))
              .findFirst()
              .orElseThrow();
      var funds = LedgerService.bankFunds(reads, account).orElseThrow();
      currency = funds.currency();
      lines.add(money(currency, funds.postedMinorUnits()));
      lines.add(money(currency, funds.availableMinorUnits()));
      lines.add(
          UiText.t(
              "ui.terminal.account.holds",
              new Money(currency, funds.reservedMinorUnits() + funds.restrictedMinorUnits())));
      var rows =
          reads.query(
              "SELECT sequence,position,minor,currency,service FROM owner_history WHERE world_id=? AND owner_id=? AND account_id=? ORDER BY sequence DESC,position DESC LIMIT 5 OFFSET ?",
              List.of(
                  owner.world().value().toString(),
                  player(owner),
                  id.toString(),
                  Integer.toString(page * 5)),
              5);
      for (var row : rows) {
        long amount = Long.parseLong(row.get("minor"));
        lines.add(
            UiText.t(
                "ui.terminal.history.movement",
                UiText.t(amount < 0 ? "ui.terminal.history.debit" : "ui.terminal.history.credit"),
                activity(row.get("service")),
                new Money(EconomyCurrency.fromCode(row.get("currency")), Math.abs(amount))));
      }
      long count =
          Long.parseLong(
              reads
                  .query(
                      "SELECT COUNT(*) AS n FROM owner_history WHERE world_id=? AND owner_id=? AND account_id=?",
                      List.of(owner.world().value().toString(), player(owner), id.toString()),
                      1)
                  .getFirst()
                  .get("n"));
      return history(lines, page, count);
    }
    String fact;
    if (parts[0].equals("credit")) {
      var f =
          CreditFacilities.find(reads, id)
              .filter(
                  v -> v.party().equals(owner) && (bank.isEmpty() || v.issuer().key().equals(bank)))
              .orElseThrow();
      var view = CreditDiagnostics.facility(reads, owner, id, now).orElseThrow();
      currency = f.currency();
      lines.add(money(currency, view.currentBalanceMinor()));
      lines.add(money(currency, view.availableCreditMinor()));
      lines.add(UiText.t("ui.terminal.account.pending", new Money(currency, 0)));
      fact = CreditFacilities.EVENT_FACT;
    } else {
      var loan =
          Loans.find(reads, id)
              .filter(
                  v -> v.party().equals(owner) && (bank.isEmpty() || v.lender().key().equals(bank)))
              .orElseThrow();
      currency = loan.currency();
      lines.add(money(currency, loan.owedMinor()));
      lines.add("");
      lines.add("");
      fact = Loans.EVENT_FACT;
    }
    String encodedId =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String kinds =
        parts[0].equals("loan")
            ? "'UEFZTUVOVA'"
            : "'UkVQQVlNRU5U','RFJBV19QVVJDSEFTRQ','RFJBV19BRFZBTkNF','REFJTFlfSU5URVJFU1Q','QURWQU5DRV9GRUVT'";
    String financial = " AND substr(payload,1,instr(payload,'|')-1) IN (" + kinds + ")";
    var params = List.of(fact, encodedId, Integer.toString(page * 5));
    // Facts encode four fields as URL-safe Base64, separated by '|'. Only financial events become
    // history rows.
    var rows =
        reads.query(
            "SELECT payload FROM journal_records WHERE record_type=? AND record_version=1 AND substr(payload,instr(payload,'|')+1,48)=? "
                + financial
                + " ORDER BY sequence DESC,position DESC LIMIT 5 OFFSET ?",
            params,
            5);
    for (var row : rows) {
      var values =
          Arrays.stream(row.get("payload").split("\\|", -1))
              .map(
                  s ->
                      new String(
                          Base64.getUrlDecoder().decode(s),
                          java.nio.charset.StandardCharsets.UTF_8))
              .toList();
      String kind = values.get(0), details = values.get(3);
      long amount = 0;
      var detail = details.split("\\|", -1);
      if (kind.equals("DAILY_INTEREST"))
        amount = Math.addExact(Long.parseLong(detail[2]), Long.parseLong(detail[3]));
      else if (kind.equals("ADVANCE_FEES"))
        amount = Math.addExact(Long.parseLong(detail[1]), Long.parseLong(detail[2]));
      else amount = Long.parseLong(detail[0]);
      lines.add(
          parts[0].equals("loan")
              ? UiText.t(
                  "ui.terminal.history.loan_payment",
                  date(Long.parseLong(values.get(2))),
                  new Money(currency, amount))
              : UiText.t(
                  "ui.terminal.history.movement",
                  UiText.t(
                      kind.equals("REPAYMENT")
                          ? "ui.terminal.history.credit"
                          : "ui.terminal.history.debit"),
                  activity(kind),
                  new Money(currency, amount)));
    }
    long count =
        Long.parseLong(
            reads
                .query(
                    "SELECT COUNT(*) AS n FROM journal_records WHERE record_type=? AND record_version=1 AND substr(payload,instr(payload,'|')+1,48)=?"
                        + financial,
                    List.of(fact, encodedId),
                    1)
                .getFirst()
                .get("n"));
    return history(lines, page, count);
  }

  private static View history(List<String> lines, int page, long count) {
    int total = Math.max(1, (int) ((count + 4) / 5));
    var choices = new ArrayList<BankingNetwork.Choice>();
    if (page > 0) choices.add(choice("terminal_history_prev", "", "←"));
    if (page + 1 < total) choices.add(choice("terminal_history_next", "", "→"));
    lines.add(UiText.t("ui.terminal.history.page", page + 1, total));
    return new View("account_overview", lines, choices, Set.of());
  }

  private static String player(PartyRef owner) {
    return ((PartyRef.Player) owner.owner()).id().value().toString();
  }

  private static String money(EconomyCurrency currency, long amount) {
    return UiText.money(new Money(currency, amount));
  }

  private static String date(long at) {
    return Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate().toString();
  }

  private static String accountLabel(String kind, String currency) {
    return UiText.t(
        "ui.terminal.account."
            + switch (kind) {
              case "bank" -> "bank";
              case "credit" -> "credit";
              case "loan" -> "loan";
              default -> throw new IllegalArgumentException("Account kind");
            },
        currency);
  }

  private static String activity(String value) {
    String name = value.substring(value.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
    String key =
        switch (name) {
          case "same_currency", "pay", "transfer", "payments" -> "transfer";
          case "ledger" -> "account";
          case "loans" -> "loan";
          case "fx", "fx_settlement", "exchange" -> "fx";
          case "deposits", "deposit" -> "deposit";
          case "withdrawals", "withdrawal" -> "withdrawal";
          case "merchant_payment", "register_sales", "draw_purchase" -> "purchase";
          case "npc_trades", "npc_cash_trades", "npc_trade", "npc_cash" -> "trade";
          case "repayment", "payment" -> "payment";
          case "draw_advance" -> "advance";
          case "daily_interest" -> "interest";
          case "advance_fees" -> "advance_fee";
          case "credit" -> "credit";
          case "debit" -> "debit";
          default -> "transaction";
        };
    return UiText.t("ui.terminal.activity." + key);
  }
}
