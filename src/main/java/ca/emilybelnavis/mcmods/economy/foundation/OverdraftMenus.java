package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.clock.FinancialCalendar;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.overdraft.OverdraftCancellation;
import ca.emilybelnavis.mcmods.economy.overdraft.Overdrafts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;

/** Bank-scoped overdraft status and explicit, server-quoted kiosk applications. */
public final class OverdraftMenus {
  private static final Set<String> ACTIONS =
      Set.of(
          "finance_od_home",
          "finance_od_account",
          "finance_od_preview",
          "finance_od_confirm",
          "finance_od_cancel",
          "finance_od_cancel_ok");
  private static final Map<BankingMenu, State> STATES = new WeakHashMap<>();

  private static final class State {
    Map<AccountId, AccountIdentity> accounts = Map.of();
    AccountId selected;
    LedgerService.OverdraftContext context;
    Overdrafts.ApplicationRequest application;
    long expiresAt;
    long reviewRevision;
  }

  private OverdraftMenus() {}

  public static boolean handles(String action) {
    return ACTIONS.contains(action);
  }

  public static Set<String> actions() {
    return ACTIONS;
  }

  public static void closed(BankingMenu menu) {
    STATES.remove(menu);
  }

  public static void action(ServerPlayer player, BankingMenu menu, String action, String value) {
    if (!handles(action) || !menu.valid(player) || menu.busy()) return;
    var services = Economy.serverServices(player.server);
    if (services == null || services.state() != ServerServices.State.READY) return;
    var grant = FinanceMenuSupport.grant(player, services);
    if (grant == null) return;
    var audience = FinanceMenuSupport.audience(player, menu);
    var state = STATES.computeIfAbsent(menu, ignored -> new State());
    try {
      switch (action) {
        case "finance_od_home" -> home(audience, services, grant, state);
        case "finance_od_account" -> select(audience, services, grant, state, value);
        case "finance_od_preview" -> preview(audience, services, grant, state, value);
        case "finance_od_confirm" -> confirm(audience, services, grant, state);
        case "finance_od_cancel" -> cancelPreview(audience, state);
        case "finance_od_cancel_ok" -> cancel(audience, services, grant, state);
        default -> {}
      }
    } catch (IllegalArgumentException | IllegalStateException | ArithmeticException invalid) {
      unavailable(audience);
    }
  }

  private static void home(
      FinanceMenuSupport.Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state) {
    state.application = null;
    state.selected = null;
    audience.menu().setBusy(true);
    services
        .ledger()
        .branchContext(grant, audience.menu().anchor(), null)
        .thenCompose(
            branch -> {
              var view = branch.orElseThrow();
              var bank = bank(view);
              return services.readAccounts(
                  grant,
                  false,
                  reads -> {
                    var result = new LinkedHashMap<AccountId, AccountIdentity>();
                    for (var account : AccountCatalog.openBanks(reads, grant.target()))
                      if (BankProviders.find(reads, account.id())
                          .filter(p -> p.bankKey().equals(bank.key()))
                          .isPresent()) result.put(account.id(), account);
                    return Optional.of(result);
                  });
            })
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              if (error != null || found == null || found.isEmpty()) {
                unavailable(audience);
                return;
              }
              state.accounts = Map.copyOf(found.orElseThrow());
              var choices =
                  state.accounts.values().stream()
                      .sorted(Comparator.comparing(a -> a.id().value()))
                      .map(
                          a ->
                              choice(
                                  "finance_od_account",
                                  a.id().value().toString(),
                                  UiText.t(
                                      "ui.finance.od.account",
                                      a.currency().code(),
                                      a.id().value().toString())))
                      .toList();
              audience.reply(
                  "finance_overdraft",
                  List.of(
                      UiText.t(
                          choices.isEmpty()
                              ? "ui.finance.od.no_accounts"
                              : "ui.finance.od.choose_account")),
                  UiText.t("ui.finance.label.overdraft"),
                  choices);
            });
  }

  private static void select(
      FinanceMenuSupport.Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      String value) {
    offered(audience, "finance_od_account", value);
    var id = new AccountId(UUID.fromString(value));
    if (!state.accounts.containsKey(id)) throw new IllegalArgumentException("OD_ACCOUNT");
    state.selected = id;
    state.application = null;
    audience.menu().setBusy(true);
    services
        .ledger()
        .overdraftContext(grant, id)
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              if (error != null || found == null || found.isEmpty()) {
                unavailable(audience);
                return;
              }
              state.context = found.orElseThrow();
              var context = state.context;
              var lines = status(context);
              var choices = new ArrayList<BankingNetwork.Choice>();
              if (audience.menu().access() == BankingMenu.Access.KIOSK
                  && context.maximumMinor() >= context.minimumMinor()
                  && context.maximumMinor() > 0)
                choices.add(choice("finance_od_preview", "", UiText.t("ui.finance.od.review")));
              else
                lines.add(
                    UiText.t(
                        audience.menu().access() == BankingMenu.Access.KIOSK
                            ? "ui.finance.od.ineligible"
                            : "ui.finance.od.visit_kiosk"));
              if (audience.menu().access() == BankingMenu.Access.KIOSK
                  && context
                      .protection()
                      .filter(p -> p.protectedAccount() && !p.closed())
                      .isPresent())
                choices.add(choice("finance_od_cancel", "", UiText.t("ui.finance.od.cancel")));
              choices.add(choice("finance_od_home", "", UiText.t("ui.finance.od.accounts")));
              audience.reply(
                  "finance_overdraft", lines, UiText.t("ui.finance.label.overdraft"), choices);
            });
  }

  private static List<String> status(LedgerService.OverdraftContext context) {
    var currency = context.account().currency();
    var lines = new ArrayList<String>();
    lines.add(UiText.t("ui.finance.od.balance", money(currency, context.postedMinor())));
    var protection = context.protection().filter(p -> p.limitMinor() > 0 && !p.closed());
    lines.add(
        protection
            .map(p -> UiText.t("ui.finance.od.allowance", money(currency, p.limitMinor())))
            .orElse(UiText.t("ui.finance.od.no_protection")));
    lines.add(
        UiText.t(
            "ui.finance.od.apr",
            context.apr().movePointRight(2).stripTrailingZeros().toPlainString()));
    lines.add(UiText.t("ui.finance.od.fee"));
    lines.add(UiText.t("ui.finance.od.unprotected"));
    lines.add(UiText.t("ui.finance.od.pad_fee"));
    lines.add(
        UiText.t(
            "ui.finance.od.range",
            money(currency, context.minimumMinor()),
            money(currency, context.maximumMinor())));
    context
        .protection()
        .filter(p -> p.episodeAt() >= 0 && !p.closed())
        .ifPresent(
            p -> {
              long deadline = Math.addExact(p.episodeAt(), 90 * FinancialCalendar.DAY_MILLIS);
              lines.add(
                  UiText.t("ui.finance.od.deadline", Instant.ofEpochMilli(deadline).toString()));
            });
    lines.add(UiText.t("ui.finance.od.recovery"));
    lines.add(UiText.t("ui.finance.od.default"));
    return lines;
  }

  private static void preview(
      FinanceMenuSupport.Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state,
      String value) {
    kiosk(audience);
    offered(audience, "finance_od_preview", "");
    if (state.selected == null || state.context == null || !value.matches("[0-9]{1,12}"))
      throw new IllegalArgumentException("OD_AMOUNT");
    long amount =
        new BigDecimal(value)
            .multiply(BigDecimal.valueOf(state.context.account().currency().minorUnitsPerMajor()))
            .longValueExact();
    state.application = null;
    audience.menu().setBusy(true);
    services
        .ledger()
        .quoteOverdraft(grant, state.selected, amount)
        .thenCompose(
            quoted -> {
              var request = quoted.orElseThrow();
              return services
                  .ledger()
                  .overdraftContext(grant, state.selected)
                  .thenApply(context -> new Review(request, context.orElseThrow()));
            })
        .whenComplete(
            (review, error) -> {
              if (!audience.live()) return;
              if (error != null || review == null) {
                unavailable(audience);
                return;
              }
              state.application = review.request();
              state.context = review.context();
              state.expiresAt = System.currentTimeMillis() + 30000;
              var lines = status(state.context);
              lines.add(
                  0,
                  UiText.t(
                      "ui.finance.od.requested",
                      money(state.context.account().currency(), amount)));
              lines.add(UiText.t("ui.finance.od.score_terms"));
              audience.reply(
                  "finance_overdraft",
                  lines,
                  UiText.t("ui.finance.od.review_terms"),
                  List.of(
                      choice("finance_od_confirm", "", UiText.t("ui.finance.od.confirm")),
                      choice("finance_od_home", "", UiText.t("ui.finance.od.accounts"))));
              state.reviewRevision = audience.menu().revision();
            });
  }

  private record Review(
      Overdrafts.ApplicationRequest request, LedgerService.OverdraftContext context) {}

  private static void confirm(
      FinanceMenuSupport.Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state) {
    kiosk(audience);
    offered(audience, "finance_od_confirm", "");
    if (state.application == null
        || state.reviewRevision != audience.menu().revision()
        || System.currentTimeMillis() >= state.expiresAt)
      throw new IllegalArgumentException("OD_REVIEW_STALE");
    var request = state.application;
    audience.menu().setBusy(true);
    services
        .ledger()
        .branchContext(grant, audience.menu().anchor(), state.selected)
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              try {
                if (error != null || found == null || found.isEmpty())
                  throw new IllegalArgumentException("OD_BRANCH");
                var view = found.orElseThrow();
                if (view.provider() == null
                    || !view.provider().bankKey().equals(bank(view).key())
                    || view.account() == null
                    || !view.account().id().equals(state.selected)
                    || System.currentTimeMillis() >= state.expiresAt)
                  throw new IllegalArgumentException("OD_BRANCH");
                FinanceMenuSupport.submit(
                    audience,
                    services,
                    grant,
                    Overdrafts.APPLY,
                    request.request(),
                    (operation, attempt) ->
                        services.ledger().applyOverdraft(grant, operation, attempt, request));
              } catch (IllegalArgumentException | IllegalStateException invalid) {
                unavailable(audience);
              }
            });
  }

  private static void cancelPreview(FinanceMenuSupport.Audience audience, State state) {
    kiosk(audience);
    offered(audience, "finance_od_cancel", "");
    if (state.selected == null
        || state.context == null
        || state.context.protection().filter(p -> p.protectedAccount() && !p.closed()).isEmpty())
      throw new IllegalArgumentException("OD_PROTECTION");
    state.application = null;
    state.expiresAt = System.currentTimeMillis() + 30000;
    audience.reply(
        "finance_overdraft",
        List.of(UiText.t("ui.finance.od.cancel_terms"), UiText.t("ui.finance.od.cancel_recovery")),
        UiText.t("ui.finance.od.cancel"),
        List.of(
            choice("finance_od_cancel_ok", "", UiText.t("ui.finance.od.cancel_confirm")),
            choice("finance_od_home", "", UiText.t("ui.finance.od.accounts"))));
    state.reviewRevision = audience.menu().revision();
  }

  private static void cancel(
      FinanceMenuSupport.Audience audience,
      ServerServices services,
      PartyAuthorization.Grant grant,
      State state) {
    kiosk(audience);
    offered(audience, "finance_od_cancel_ok", "");
    if (state.selected == null
        || state.context == null
        || state.reviewRevision != audience.menu().revision()
        || System.currentTimeMillis() >= state.expiresAt)
      throw new IllegalArgumentException("OD_REVIEW_STALE");
    var protection = state.context.protection().orElseThrow();
    var account = state.selected;
    var request =
        OverdraftCancellation.request(
            grant.target().world(),
            ((PartyRef.Player) grant.target().owner()).id(),
            account,
            protection.revision());
    audience.menu().setBusy(true);
    services
        .ledger()
        .branchContext(grant, audience.menu().anchor(), account)
        .whenComplete(
            (found, error) -> {
              if (!audience.live()) return;
              try {
                if (error != null || found == null || found.isEmpty())
                  throw new IllegalArgumentException("OD_BRANCH");
                var view = found.orElseThrow();
                if (view.provider() == null
                    || !view.provider().bankKey().equals(bank(view).key())
                    || view.account() == null
                    || !view.account().id().equals(account)
                    || System.currentTimeMillis() >= state.expiresAt)
                  throw new IllegalArgumentException("OD_BRANCH");
                FinanceMenuSupport.submit(
                    audience,
                    services,
                    grant,
                    OverdraftCancellation.CANCEL,
                    request,
                    (operation, attempt) ->
                        services
                            .ledger()
                            .cancelOverdraft(
                                grant, operation, attempt, account, protection.revision()));
              } catch (IllegalArgumentException | IllegalStateException invalid) {
                unavailable(audience);
              }
            });
  }

  private static InstitutionCatalog.InstitutionId bank(LedgerService.BranchContext view) {
    if (view.terminal() == null
        || view.terminal().binding() == null
        || !view.terminal().binding().bound()
        || view.terminal().binding().institution() == null)
      throw new IllegalArgumentException("OD_BANK");
    return view.terminal().binding().institution();
  }

  private static void kiosk(FinanceMenuSupport.Audience audience) {
    if (audience.menu().access() != BankingMenu.Access.KIOSK)
      throw new IllegalArgumentException("OD_KIOSK");
  }

  private static void offered(FinanceMenuSupport.Audience audience, String action, String value) {
    if (!FinanceMenuSupport.offered(audience, action, value))
      throw new IllegalArgumentException("OD_NOT_OFFERED");
  }

  private static BankingNetwork.Choice choice(String action, String value, String label) {
    return new BankingNetwork.Choice(action, value, label, false);
  }

  private static String money(EconomyCurrency currency, long minor) {
    return UiText.money(new Money(currency, minor));
  }

  private static void unavailable(FinanceMenuSupport.Audience audience) {
    audience.reply(
        "finance_overdraft",
        List.of(UiText.t("ui.finance.od.unavailable")),
        UiText.t("ui.finance.label.overdraft"),
        List.of(choice("finance_od_home", "", UiText.t("ui.finance.od.accounts"))));
  }
}
