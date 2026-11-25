package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.corporation.*;
import ca.emilybelnavis.mcmods.economy.credit.CardServicing;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.Network;
import java.math.BigDecimal;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;

/**
 * The server binds every credit offer to the current company, bank account and facility version.
 */
public final class CorporateCreditMenu {
  private record Offer(CorporateCredit.Action action, Network network, long revision) {}

  private record Page(
      CorporateBanking.Selection company, CorporateKiosk.Account account, List<Offer> offers) {}

  private static final Map<BankingMenu, Page> PAGES = new WeakHashMap<>();

  static void open(
      ServerPlayer player,
      BankingMenu menu,
      CorporateBanking banking,
      CorporateBanking.Selection company,
      CorporateKiosk.Account account) {
    var services = BankingController.services(player);
    var endpoint = account.endpoint();
    long revision = menu.revision();
    menu.setBusy(true);
    banking
        .read(
            reads -> {
              var bank = banking.selectedBank(reads, company);
              if (!bank.id().key().equals(endpoint.bank())
                  || !banking.permits(reads, company, Managers.Use.BORROW, endpoint.account()))
                throw new IllegalArgumentException();
              var line =
                  CorporateCredit.find(reads, endpoint.account(), services.financialMillis());
              var offers = new ArrayList<Offer>();
              var details = new ArrayList<String>();
              details.add(company.name() + " ⋅ " + bank.name());
              if (line.isPresent()) {
                var facility = line.get();
                details.add(
                    "Credit limit "
                        + money(account, facility.limit())
                        + " · Available "
                        + money(account, facility.available()));
                details.add(
                    "Balance due "
                        + money(account, facility.debt())
                        + " · Interest "
                        + money(account, facility.interest()));
                if (facility.available() > 0)
                  offers.add(
                      new Offer(
                          CorporateCredit.Action.BORROW, facility.network(), facility.revision()));
                if (facility.debt() > 0)
                  offers.add(
                      new Offer(
                          CorporateCredit.Action.REPAY, facility.network(), facility.revision()));
                else
                  offers.add(
                      new Offer(
                          CorporateCredit.Action.CLOSE, facility.network(), facility.revision()));
              } else {
                var capital =
                    CorporateCapital.profile(reads, company.id()).orElseThrow().required();
                if (capital == null || capital.currency() != account.currency()) {
                  details.add("Apply from an account in the company’s business currency.");
                  return new View(List.of(), List.copyOf(details));
                }
                details.add(
                    "Choose a network. You can request up to "
                        + money(account, capital.minorUnits())
                        + " in credit.");
                for (var network : bank.networks().stream().sorted().toList())
                  offers.add(new Offer(CorporateCredit.Action.APPLY, network, 0));
              }
              return new View(List.copyOf(offers), List.copyOf(details));
            })
        .whenComplete(
            (view, error) -> {
              if (!menu.valid(player) || revision != menu.revision()) return;
              menu.setBusy(false);
              if (error != null) {
                failed(menu);
                return;
              }
              PAGES.put(menu, new Page(company, account, view.offers()));
              var choices = new ArrayList<BankingNetwork.Choice>();
              for (int i = 0; i < view.offers().size(); i++)
                choices.add(
                    choice("corp_cc_pick", Integer.toString(i), label(view.offers().get(i))));
              menu.publish("corp_cc", view.details(), "", choices);
            });
  }

  private record View(List<Offer> offers, List<String> details) {}

  private static String label(Offer offer) {
    return switch (offer.action()) {
      case APPLY -> "Apply for a " + networkName(offer.network()) + " card";
      case BORROW -> "Borrow money";
      case REPAY -> "Make a payment";
      case CLOSE -> "Close credit account";
    };
  }

  private static String networkName(Network network) {
    String text = network.name();
    return text.charAt(0) + text.substring(1).toLowerCase(Locale.ROOT);
  }

  private static String money(CorporateKiosk.Account account, long amount) {
    return MoneyFormatter.format(new Money(account.currency(), amount), Locale.UK, true);
  }

  private static BankingNetwork.Choice choice(String action, String value, String label) {
    return new BankingNetwork.Choice(action, value, label, false);
  }

  private static void failed(BankingMenu menu) {
    menu.publish(
        "corp_cc",
        List.of("Credit details have changed. Select your company account again."),
        "",
        List.of());
  }

  public static void action(ServerPlayer player, BankingMenu menu, String action, String value) {
    var page = PAGES.get(menu);
    if (page == null || menu.busy()) return;
    if (action.equals("corp_cc_pick")
        && menu.page().equals("corp_cc")
        && menu.choices().stream()
            .anyMatch(c -> c.action().equals(action) && c.value().equals(value))) {
      var offer = page.offers().get(Integer.parseInt(value));
      PAGES.put(menu, new Page(page.company(), page.account(), List.of(offer)));
      var lines = new ArrayList<String>();
      lines.add(label(offer) + " ⋅ " + page.company().name());
      lines.add(
          networkName(offer.network())
              + " annual interest on cash advances "
              + CardServicing.advanceApr(offer.network().key())
                  .movePointRight(2)
                  .stripTrailingZeros()
                  .toPlainString()
              + "%");
      lines.add(
          switch (offer.action()) {
            case APPLY -> "Enter the credit limit in " + page.account().currency() + ".";
            case BORROW -> "How much do you want to borrow?";
            case REPAY -> "How much do you want to pay from the company account?";
            case CLOSE -> "Review before closing this credit account.";
          });
      menu.publish(
          offer.action() == CorporateCredit.Action.CLOSE ? "corp_cc_close" : "corp_cc_amount",
          lines,
          "",
          List.of(choice("corp_cc_submit", "", "Review")));
      return;
    }
    if (!action.equals("corp_cc_submit")
        || !Set.of("corp_cc_amount", "corp_cc_close").contains(menu.page())
        || page.offers().size() != 1) return;
    var offer = page.offers().getFirst();
    long amount = 0;
    if (offer.action() != CorporateCredit.Action.CLOSE) {
      try {
        if (!value.matches("[0-9]{1,19}(\\.[0-9]{1,2})?")) throw new IllegalArgumentException();
        amount =
            new BigDecimal(value)
                .movePointRight(page.account().currency().fractionDigits())
                .longValueExact();
        if (amount <= 0) throw new IllegalArgumentException();
      } catch (ArithmeticException | IllegalArgumentException ex) {
        menu.publish(
            menu.page(),
            menu.lines(),
            "Enter a positive amount in " + page.account().currency() + ".",
            menu.choices());
        return;
      }
    }
    var services = BankingController.services(player);
    var endpoint = page.account().endpoint();
    var req =
        new CorporateCredit.Request(
            offer.action(),
            page.company().id(),
            page.company().companyRevision(),
            page.company().membershipRevision(),
            endpoint.account(),
            endpoint.accountRevision(),
            offer.network(),
            amount,
            offer.revision(),
            CorporateCredit.day(services.financialMillis()));
    var banking = new CorporateBanking(player, menu);
    long revision = menu.revision();
    var lines = new ArrayList<>(menu.lines().subList(0, 2));
    if (amount > 0) lines.add("Amount " + money(page.account(), amount));
    var actor = BankingController.actor(player);
    var world = services.worldId();
    menu.setBusy(true);
    banking
        .reviewCredit(
            page.company(),
            page.account(),
            offer.action() == CorporateCredit.Action.APPLY
                ? "CREDIT_OPEN"
                : "CREDIT_" + offer.action(),
            req.fields(),
            (intent, reads) ->
                CorporateCredit.prepare(
                    intent, reads, world, actor, req, services.financialMillis()))
        .whenComplete(
            (review, error) -> {
              if (!menu.valid(player) || revision != menu.revision()) return;
              menu.setBusy(false);
              if (error != null) failed(menu);
              else BankingAccounts.creditReview(player, menu, banking, review, lines);
            });
  }

  private CorporateCreditMenu() {}
}
