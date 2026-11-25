package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.UiText;
import ca.emilybelnavis.mcmods.economy.corporation.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

/**
 * Resolves kiosk input against current, authenticated worker data before issuing a branch review.
 */
final class CorporateKiosk {
  private final CorporateBanking banking;
  private final PlayerId actor;
  private final WorldId world;
  private final LongSupplier clock;

  CorporateKiosk(CorporateBanking banking, PlayerId actor, WorldId world, LongSupplier clock) {
    this.banking = banking;
    this.actor = actor;
    this.world = world;
    this.clock = clock;
  }

  record Account(
      CorporatePayments.Endpoint endpoint,
      EconomyCurrency currency,
      Long available,
      boolean issueDebit,
      boolean credit,
      boolean fund,
      boolean pay,
      boolean close) {}

  record People(UUID director, List<UUID> managers, UUID next) {}

  record Accounts(
      List<Account> visible,
      EconomyCurrency openCurrency,
      CorporateCapital.Profile profile,
      People people) {}

  private People directors(
      JournalEngine.ReadView reads, CorporateBanking.Selection company, UUID after) {
    UUID founder =
        UUID.fromString(
            reads
                .query(
                    "SELECT founder FROM corporations WHERE id=?",
                    List.of(company.id().toString()),
                    1)
                .getFirst()
                .get("founder"));
    var rows =
        reads.query(
            "SELECT manager FROM corporation_managers WHERE company=? AND active=1 AND manager>? ORDER BY manager LIMIT 9",
            List.of(company.id().toString(), Objects.toString(after, "")),
            9);
    var managers = rows.stream().limit(8).map(r -> UUID.fromString(r.get("manager"))).toList();
    return new People(founder, managers, rows.size() == 9 ? managers.getLast() : null);
  }

  CompletionStage<People> people(CorporateBanking.Selection company, UUID after) {
    return banking.read(
        reads -> {
          banking.selectedBank(reads, company);
          return directors(reads, company, after);
        });
  }

  List<String> names(People people) {
    var lines = new ArrayList<String>();
    lines.add(t("director", banking.playerName(people.director())));
    for (var manager : people.managers()) lines.add(t("manager", banking.playerName(manager)));
    return lines;
  }

  record Prepared(CorporateBanking.Review review, List<String> lines) {}

  CompletionStage<Accounts> accounts(CorporateBanking.Selection company) {
    return banking.read(
        reads -> {
          var bank = banking.selectedBank(reads, company);
          var currency = bank.baseCurrency();
          var all = CorporateAccounts.list(reads, world, company.id());
          var profile = CorporateCapital.profile(reads, company.id()).orElseThrow();
          boolean canOpen =
              banking.permits(reads, company, Managers.Use.OPEN, null)
                  && (profile.funded()
                      || profile.required() != null && profile.required().currency() == currency)
                  && all.size() < AccountIdentity.MAX_OPEN_BANK_ACCOUNTS
                  && all.stream().noneMatch(a -> a.currency() == currency)
                  && ca.emilybelnavis.mcmods.economy.fx.FxService.availableCurrencies(reads)
                      .contains(currency);
          var visible = new ArrayList<Account>();
          for (var identity : all) {
            var provider = BankProviders.find(reads, identity.id()).orElseThrow();
            if (!provider.legacy() && bank.id().key().equals(provider.bankKey())) {
              var account = account(reads, company, identity, provider);
              if (account.available() != null
                  || account.credit()
                  || account.fund()
                  || account.pay()
                  || account.close()) visible.add(account);
            }
          }
          return new Accounts(
              List.copyOf(visible),
              canOpen ? currency : null,
              profile,
              directors(reads, company, null));
        });
  }

  private Account account(
      JournalEngine.ReadView reads,
      CorporateBanking.Selection company,
      AccountIdentity identity,
      BankProviders.Binding provider) {
    var id = identity.id();
    var endpoint =
        new CorporatePayments.Endpoint(
            identity.owner(),
            id,
            identity.revision(),
            provider.bankKey(),
            provider.revision(),
            company.companyRevision());
    boolean history = banking.permits(reads, company, Managers.Use.HISTORY, id);
    Long available =
        history
            ? ca.emilybelnavis.mcmods.economy.journal.Holds.available(
                reads, id.value(), identity.currency())
            : null;
    return new Account(
        endpoint,
        identity.currency(),
        available,
        banking.permits(reads, company, Managers.Use.CLOSE, id)
            && !ca.emilybelnavis.mcmods.economy.card.CorporateCards.hasDebit(reads, id),
        banking.permits(reads, company, Managers.Use.BORROW, id)
            && CorporateCapital.profile(reads, company.id()).orElseThrow().funded(),
        banking.permits(reads, company, Managers.Use.PAYMENT, id),
        banking.permits(reads, company, Managers.Use.PAYMENT, id)
            && CorporateCapital.profile(reads, company.id()).orElseThrow().funded(),
        banking.permits(reads, company, Managers.Use.CLOSE, id));
  }

  CompletionStage<Account> detail(CorporateBanking.Selection company, Account selected) {
    return banking.read(
        reads -> {
          var current = current(reads, company, selected);
          if (current.available() == null
              && !current.credit()
              && !current.fund()
              && !current.pay()
              && !current.close()) throw new IllegalArgumentException("CORPORATION_UNAVAILABLE");
          return current;
        });
  }

  private Account current(
      JournalEngine.ReadView reads, CorporateBanking.Selection company, Account selected) {
    var bank = banking.selectedBank(reads, company);
    var endpoint = selected.endpoint();
    var fresh =
        CorporateAccounts.find(reads, world, company.id(), endpoint.account()).orElseThrow();
    var provider = BankProviders.find(reads, endpoint.account()).orElseThrow();
    if (fresh.status() != AccountIdentity.Status.OPEN
        || fresh.revision() != endpoint.accountRevision()
        || provider.legacy()
        || !bank.id().key().equals(provider.bankKey())
        || provider.revision() != endpoint.providerRevision())
      throw new IllegalArgumentException("CORPORATION_UNAVAILABLE");
    return account(reads, company, fresh, provider);
  }

  CompletionStage<List<String>> history(CorporateBanking.Selection company, Account selected) {
    return banking.read(
        reads -> {
          var current = current(reads, company, selected);
          if (current.available() == null)
            throw new IllegalArgumentException("CORPORATION_UNAVAILABLE");
          var lines = new ArrayList<String>();
          lines.add(t("selected", company.name()));

          lines.addAll(
              CorporateStatement.recent(reads, world, company.id(), selected.endpoint().account()));
          return List.copyOf(lines);
        });
  }

  CompletionStage<Prepared> open(CorporateBanking.Selection company, EconomyCurrency currency) {
    return banking
        .read(
            reads -> {
              var bank = banking.selectedBank(reads, company);
              if (bank.baseCurrency() != currency
                  || !banking.permits(reads, company, Managers.Use.OPEN, null))
                throw new IllegalArgumentException("CORPORATION_UNAVAILABLE");
              return bank;
            })
        .thenCompose(
            bank ->
                banking
                    .reviewOpen(
                        new CorporateAccounts.Open(
                            company.id(),
                            company.companyRevision(),
                            company.membershipRevision(),
                            bank.id(),
                            currency))
                    .thenApply(
                        review ->
                            new Prepared(
                                review,
                                List.of(
                                    t("owner", company.name()),
                                    t("open_review", currency.code(), bank.name()),
                                    t("fee", new Money(currency, 0))))));
  }

  record Source(AccountId account, String bank, Money balance) {}

  record Sources(List<Source> accounts, Money required) {}

  CompletionStage<Sources> funding(CorporateBanking.Selection company, Account selected) {
    return banking.read(
        reads -> {
          var rights = current(reads, company, selected);
          if (!rights.fund()) throw new IllegalStateException("CORPORATION_UNAVAILABLE");
          var profile = CorporateCapital.profile(reads, company.id()).orElseThrow();
          var required = profile.funded() ? null : profile.required();
          var sources = new ArrayList<Source>();
          for (var account :
              ca.emilybelnavis.mcmods.economy.account.AccountCatalog.openBanks(
                  reads, new PartyRef(world, new PartyRef.Player(actor)))) {
            if (required != null && required.currency() != account.currency()) continue;
            var provider = BankProviders.find(reads, account.id()).orElse(null);
            if (provider == null || provider.legacy()) continue;
            var bank =
                InstitutionCatalog.require(
                    new InstitutionCatalog.InstitutionId(provider.bankKey()));
            sources.add(
                new Source(
                    account.id(),
                    bank.name(),
                    new Money(
                        account.currency(),
                        ca.emilybelnavis.mcmods.economy.journal.Holds.available(
                            reads, account.id().value(), account.currency()))));
          }
          return new Sources(List.copyOf(sources), required);
        });
  }

  record Destination(AccountId account, String label) {}

  record Destinations(List<Destination> accounts, boolean emptyClosure, boolean external) {}

  CompletionStage<Destinations> destinations(
      CorporateBanking.Selection company, Account selected, boolean close) {
    return banking.read(
        reads -> {
          var rights = current(reads, company, selected);
          if (!(close ? rights.close() : rights.pay()))
            throw new IllegalStateException("CORPORATION_UNAVAILABLE");
          boolean empty =
              close
                  && ca.emilybelnavis.mcmods.economy.journal.Settlement.posted(
                          reads, selected.endpoint().account().value(), selected.currency())
                      == 0;
          var list = new ArrayList<Destination>();
          if (empty) return new Destinations(List.of(), true, false);
          for (var account : CorporateAccounts.list(reads, world, company.id())) {
            if (!account.id().equals(selected.endpoint().account())
                && banking.permits(
                    reads,
                    company,
                    close ? Managers.Use.CLOSE : Managers.Use.PAYMENT,
                    account.id())) addDestination(reads, list, account, company.name());
          }
          boolean external = rights.pay();
          if (external)
            for (var account :
                AccountCatalog.openBanks(reads, new PartyRef(world, new PartyRef.Player(actor))))
              addDestination(reads, list, account, t("my_account"));
          return new Destinations(List.copyOf(list), false, external);
        });
  }

  private void addDestination(
      JournalEngine.ReadView reads, List<Destination> list, AccountIdentity account, String owner) {
    BankProviders.find(reads, account.id())
        .filter(p -> !p.legacy())
        .ifPresent(
            provider ->
                list.add(
                    new Destination(
                        account.id(),
                        t(
                            "destination_label",
                            owner,
                            bankName(provider.bankKey()),
                            account.currency().code()))));
  }

  static String bankName(String key) {
    return InstitutionCatalog.require(new InstitutionCatalog.InstitutionId(key)).name();
  }

  CompletionStage<Destination> resolveDestination(
      CorporateBanking.Selection company,
      Account selected,
      boolean close,
      PlayerId targetPlayer,
      AccountId reference) {
    return banking
        .read(
            reads -> {
              var rights = current(reads, company, selected);
              if (!rights.pay() || close && !rights.close())
                throw new IllegalStateException("CORPORATION_UNAVAILABLE");
              var id =
                  reference != null
                      ? reference
                      : AccountCatalog.primary(
                              reads, new PartyRef(world, new PartyRef.Player(targetPlayer)))
                          .orElseThrow()
                          .id();
              if (id.equals(selected.endpoint().account()))
                throw new IllegalArgumentException("Same account");
              return counterparty(reads, id, false);
            })
        .thenApply(
            peer -> {
              String owner = peer.label();
              if (peer.endpoint().owner().owner() instanceof PartyRef.Player payee
                  && !payee.id().equals(actor)) owner = banking.playerName(payee.id().value());
              return new Destination(
                  peer.endpoint().account(),
                  t(
                      "destination_label",
                      owner,
                      bankName(peer.endpoint().bank()),
                      peer.currency().code()));
            });
  }

  private record Counterparty(
      CorporatePayments.Endpoint endpoint, EconomyCurrency currency, String label) {}

  private record Transfer(CorporatePayments.Payment payment, List<String> lines) {}

  private Counterparty counterparty(JournalEngine.ReadView reads, AccountId id, boolean funding) {
    var rows =
        reads.query(
            "SELECT owner_id,currency,revision FROM account_catalog WHERE account_id=? AND purpose='BANK' AND status='OPEN'",
            List.of(id.value().toString()),
            1);
    var row = rows.getFirst();
    String owner = row.get("owner_id");
    long revision = 0;
    PartyRef.Owner party;
    String label;
    if (owner.startsWith("organization:")) {
      if (funding) throw new IllegalArgumentException("Personal source required");
      var company = UUID.fromString(owner.substring("organization:".length()));
      var record =
          reads
              .query(
                  "SELECT revision,name FROM corporations WHERE id=? AND state<>'DISSOLVED'",
                  List.of(company.toString()),
                  1)
              .getFirst();
      revision = Long.parseLong(record.get("revision"));
      label = record.get("name");
      party = new PartyRef.Organization(new OrganizationId(company));
    } else {
      var player = new PlayerId(UUID.fromString(owner));
      if (funding && !actor.equals(player))
        throw new IllegalArgumentException("Personal source required");
      party = new PartyRef.Player(player);
      label = t("my_account");
    }
    var provider = BankProviders.find(reads, id).filter(p -> !p.legacy()).orElseThrow();
    var endpoint =
        new CorporatePayments.Endpoint(
            new PartyRef(world, party),
            id,
            Long.parseLong(row.get("revision")),
            provider.bankKey(),
            provider.revision(),
            revision);
    return new Counterparty(endpoint, EconomyCurrency.fromCode(row.get("currency")), label);
  }

  CompletionStage<Prepared> transfer(
      CorporateBanking.Selection company,
      Account account,
      String action,
      AccountId other,
      long amount) {
    return transfer(company, account, action, other, amount, null);
  }

  CompletionStage<Prepared> transfer(
      CorporateBanking.Selection company,
      Account account,
      String action,
      AccountId other,
      long amount,
      String destinationLabel) {
    boolean close = action.equals("corp_close");
    boolean funding = action.equals("corp_fund");
    return banking
        .read(
            reads -> {
              var rights = current(reads, company, account);
              var endpoint = account.endpoint();
              if (!(close ? rights.close() : funding ? rights.fund() : rights.pay()))
                throw new IllegalArgumentException("CORPORATION_UNAVAILABLE");
              var lines = new ArrayList<String>();

              if (close)
                lines.add(
                    t(
                        "close_review",
                        bankName(endpoint.bank()) + " · " + account.currency().code()));
              if (other == null) {
                if (!close
                    || ca.emilybelnavis.mcmods.economy.journal.Settlement.posted(
                            reads, endpoint.account().value(), account.currency())
                        != 0) throw new IllegalArgumentException("Settlement destination required");
                lines.add(t("balance", new Money(account.currency(), 0)));
                lines.add(t("fee", new Money(account.currency(), 0)));
                return new Transfer(null, List.copyOf(lines));
              }
              var peer = counterparty(reads, other, funding);
              var source = funding ? peer.endpoint() : endpoint;
              var target = funding ? endpoint : peer.endpoint();
              var from = funding ? peer.currency() : account.currency();
              var to = funding ? account.currency() : peer.currency();
              long membership = funding ? 0 : company.membershipRevision();
              var terms =
                  CorporatePayments.quote(
                          reads,
                          actor,
                          source,
                          target,
                          membership,
                          amount,
                          close,
                          clock.getAsLong())
                      .orElseThrow();
              lines.add(
                  t(
                      "source",
                      t(
                          "destination_label",
                          funding ? peer.label() : company.name(),
                          bankName(source.bank()),
                          from.code())));
              lines.add(
                  t(
                      "target",
                      destinationLabel != null
                          ? destinationLabel
                          : t(
                              "destination_label",
                              funding ? company.name() : peer.label(),
                              bankName(target.bank()),
                              to.code())));
              lines.add(t("principal", new Money(from, terms.principal())));
              if (terms.fee() > 0) lines.add(t("fee", new Money(from, terms.fee())));
              if (from != to) lines.add(t("credit", new Money(to, terms.credit())));

              return new Transfer(
                  new CorporatePayments.Payment(source, target, membership, close, terms),
                  List.copyOf(lines));
            })
        .thenCompose(
            transfer -> {
              var payment = transfer.payment();
              var source = account.endpoint();
              var review =
                  close
                      ? banking.reviewClose(
                          new CorporateAccounts.Close(
                              company.id(),
                              company.companyRevision(),
                              company.membershipRevision(),
                              new InstitutionCatalog.InstitutionId(source.bank()),
                              source.account(),
                              source.accountRevision(),
                              source.providerRevision(),
                              other,
                              payment == null ? null : payment.target().accountRevision()),
                          payment)
                      : banking.reviewPayment(payment, company);
              return review.thenApply(issued -> new Prepared(issued, transfer.lines()));
            });
  }

  static String t(String key, Object... args) {
    return UiText.t("ui.bank.corporate." + key, args);
  }
}
