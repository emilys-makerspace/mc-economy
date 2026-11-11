package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.card.CreditCardDesigns;
import ca.emilybelnavis.mcmods.economy.credit.*;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;

/**
 * Physical credit use. Advances and subsequent physical withdrawals have distinct confirmations.
 */
public final class CreditUse {
  private static final Map<BankingMenu, LedgerService.CreditDrawQuote> QUOTES = new WeakHashMap<>();

  private record AdvanceStart(
      CreditFacilities.Token token,
      CreditFacilities.Facility facility,
      CreditFacilities.AdvanceFees fees) {}

  private static final Map<BankingMenu, UUID> SELECTED = new WeakHashMap<>();
  private static final Map<BankingMenu, UUID> TOKENS = new WeakHashMap<>();

  public static void selectFacility(BankingMenu menu, UUID facility) {
    SELECTED.put(menu, facility);
  }

  private CreditUse() {}

  public static boolean handles(String action) {
    return Set.of("credit_advance", "credit_adv_preview", "credit_adv_confirm").contains(action);
  }

  public static UUID heldToken(
      ServerPlayer player, ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId world) {
    return java.util.stream.Stream.of(player.getMainHandItem(), player.getOffhandItem())
        .filter(
            stack ->
                CreditCardDesigns.byId(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(stack.getItem())
                            .toString())
                    .isPresent())
        .map(stack -> ca.emilybelnavis.mcmods.economy.card.CardStackIdentity.mark(stack, world))
        .flatMap(Optional::stream)
        .map(ca.emilybelnavis.mcmods.economy.card.CardStackIdentity.Mark::token)
        .findFirst()
        .orElse(null);
  }

  public static void closed(BankingMenu menu) {
    QUOTES.remove(menu);
    SELECTED.remove(menu);
    TOKENS.remove(menu);
  }

  public static void action(ServerPlayer player, BankingMenu menu, String action, String value) {
    var audience = FinanceMenuSupport.audience(player, menu);
    if (!audience.live()
        || menu.busy()
        || (menu.access() != BankingMenu.Access.ATM
            && menu.access() != BankingMenu.Access.TELLER
            && menu.access() != BankingMenu.Access.KIOSK)) return;
    var services = Economy.serverServices(player.server);
    if (services == null || services.state() != ServerServices.State.READY) return;
    var grant = FinanceMenuSupport.grant(player, services);
    if (grant == null) return;
    try {
      if (action.equals("credit_advance")) {
        QUOTES.remove(menu);
        var token = heldToken(player, grant.target().world());
        menu.setBusy(true);
        services
            .readAccounts(
                grant,
                false,
                reads -> {
                  var selected = SELECTED.get(menu);
                  var resolved =
                      selected == null
                          ? (token == null
                              ? Optional.<CreditFacilities.Token>empty()
                              : CreditFacilities.usableToken(
                                  reads,
                                  grant.target().world(),
                                  ((PartyRef.Player) grant.target().owner()).id(),
                                  token,
                                  services.financialMillis()))
                          : CreditFacilities.tokenForFacility(
                                  reads, grant.target().world(), selected)
                              .flatMap(
                                  t ->
                                      CreditFacilities.usableToken(
                                          reads,
                                          grant.target().world(),
                                          ((PartyRef.Player) grant.target().owner()).id(),
                                          t.id(),
                                          services.financialMillis()));
                  return resolved.flatMap(
                      t ->
                          CreditFacilities.find(reads, t.facility())
                              .map(
                                  f -> {
                                    var terminal =
                                        ca.emilybelnavis.mcmods.economy.journal.TerminalPolicy.find(
                                                reads,
                                                grant.target().world(),
                                                menu.anchor(),
                                                grant.actor(),
                                                true)
                                            .orElseThrow();
                                    return new AdvanceStart(
                                        t,
                                        f,
                                        CreditFacilities.AdvanceFees.capture(reads, f, terminal));
                                  }));
                })
            .whenComplete(
                (found, error) -> {
                  if (!audience.live()) return;
                  if (error != null || found.isEmpty()) {
                    unavailable(audience);
                    return;
                  }
                  var f = found.orElseThrow().facility();
                  TOKENS.put(menu, found.orElseThrow().token().id());
                  var fees = found.orElseThrow().fees();
                  long fee = Math.addExact(fees.advanceMinor(), fees.atmMinor());
                  long available =
                      Math.max(
                          0,
                          Math.min(
                                  f.limitMinor() - f.owedMinor(),
                                  f.limitMinor() / 5 - f.advance().principalMinor())
                              - fee);
                  audience.reply(
                      "credit_advance",
                      List.of(
                          "Cash Advance Available: " + CreditReport.money(f.currency(), available),
                          "Fees: " + CreditReport.money(f.currency(), fee),
                          "Enter the amount to withdraw in " + f.currency().code() + ".",
                          "After the advance, review the physical cash withdrawal to receive your notes."),
                      "",
                      List.of(
                          new BankingNetwork.Choice(
                              "credit_adv_preview", "", "Review Cash Advance", false)));
                });
      } else if (action.equals("credit_adv_preview")) {
        if (!FinanceMenuSupport.offered(audience, action, "")) return;
        var account = BankingAccounts.selected(menu);
        var token = TOKENS.get(menu);
        if (account == null || token == null) throw new IllegalArgumentException();
        menu.setBusy(true);
        // Resolve currency first, then parse exact currency minor units.
        services
            .ledger()
            .ownedBankAccount(grant, account)
            .whenComplete(
                (found, error) -> {
                  if (!audience.live()) return;
                  if (error != null || found.isEmpty()) {
                    unavailable(audience);
                    return;
                  }
                  try {
                    long amount =
                        new java.math.BigDecimal(value)
                            .multiply(
                                java.math.BigDecimal.valueOf(
                                    found.orElseThrow().currency().minorUnitsPerMajor()))
                            .longValueExact();
                    services
                        .ledger()
                        .quoteCreditDraw(
                            grant, token, menu.anchor(), menu.generation(), account, amount, false)
                        .whenComplete(
                            (quote, failure) -> {
                              if (!audience.live()) return;
                              if (failure != null || quote.isEmpty()) {
                                unavailable(audience);
                                return;
                              }
                              var q = quote.orElseThrow();
                              QUOTES.put(menu, q);
                              var currency = q.facility().currency();
                              var fees = q.request().fees();
                              var lines = new ArrayList<String>();
                              lines.add(
                                  UiText.t(
                                      "ui.finance.credituse.6",
                                      CreditReport.money(currency, q.request().amountMinor())));
                              lines.add(
                                  UiText.t(
                                      "ui.finance.credituse.advance_fee",
                                      CreditReport.money(currency, fees.advanceMinor())));
                              if (fees.atmMinor() > 0)
                                lines.add(
                                    UiText.t(
                                        "ui.bank.atm_fee",
                                        CreditReport.money(currency, fees.atmMinor())));
                              lines.add(
                                  UiText.t(
                                      "ui.finance.credituse.total",
                                      CreditReport.money(
                                          currency,
                                          Math.addExact(
                                              q.request().amountMinor(),
                                              Math.addExact(
                                                  fees.advanceMinor(), fees.atmMinor())))));
                              lines.add(
                                  UiText.t(
                                      "ui.finance.credituse.7",
                                      UiText.decimal(
                                          q.facility()
                                              .advance()
                                              .apr()
                                              .multiply(new java.math.BigDecimal("100")))));
                              lines.add(UiText.t("ui.finance.credituse.8"));
                              lines.add(UiText.t("ui.finance.credituse.9"));
                              lines.add(
                                  UiText.t(
                                      "ui.finance.creditmenus.67",
                                      java.time.Instant.ofEpochMilli(q.request().expiresAt())));
                              audience.reply(
                                  "credit_advance",
                                  lines,
                                  UiText.t("ui.finance.credituse.5"),
                                  List.of(
                                      new BankingNetwork.Choice(
                                          "credit_adv_confirm",
                                          "",
                                          UiText.t("ui.finance.credituse.10"),
                                          false)));
                            });
                  } catch (RuntimeException invalid) {
                    unavailable(audience);
                  }
                });
      } else if (FinanceMenuSupport.offered(audience, action, "")) {
        var q = Objects.requireNonNull(QUOTES.remove(menu));
        var proof = CreditDrawAdmission.captureAssisted(player, menu, q.request(), q.terminal());
        FinanceMenuSupport.submit(
            audience,
            services,
            grant,
            CreditFacilities.DRAW,
            q.request().request(),
            (operation, attempt) ->
                services.ledger().drawCredit(grant, operation, attempt, q.request(), proof));
      }
    } catch (RuntimeException invalid) {
      unavailable(audience);
    }
  }

  private static void unavailable(FinanceMenuSupport.Audience audience) {
    audience.decline(
        "credit_advance",
        List.of(UiText.t("ui.finance.credituse.12")),
        UiText.t("ui.finance.credituse.11"),
        List.of());
  }
}
