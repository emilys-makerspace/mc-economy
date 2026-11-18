package ca.emilybelnavis.mcmods.economy.foundation;

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
      boolean pay,
      boolean close) {}

  record Accounts(List<Account> visible, EconomyCurrency openCurrency) {}

  record Prepared(CorporateBanking.Review review, List<String> lines) {}

  CompletionStage<Accounts> accounts(CorporateBanking.Selection company) {
    return banking.read(
        reads -> {
          var bank = banking.selectedBank(reads, company);
          var currency = bank.baseCurrency();
          var all = CorporateAccounts.list(reads, world, company.id());
          boolean canOpen =
              banking.permits(reads, company, Managers.Use.OPEN, null)
                  && all.size() < AccountIdentity.MAX_OPEN_BANK_ACCOUNTS
                  && all.stream().noneMatch(a -> a.currency() == currency)
                  && ca.emilybelnavis.mcmods.economy.fx.FxService.availableCurrencies(reads)
                      .contains(currency);
          var visible = new ArrayList<Account>();
          for (var identity : all) {
            var provider = BankProviders.find(reads, identity.id()).orElseThrow();
            if (!provider.legacy() && bank.id().key().equals(provider.bankKey())) {
              var account = account(reads, company, identity, provider);
              if (account.available() != null || account.pay() || account.close())
                visible.add(account);
            }
          }
          return new Accounts(List.copyOf(visible), canOpen ? currency : null);
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
        banking.permits(reads, company, Managers.Use.PAYMENT, id),
        banking.permits(reads, company, Managers.Use.CLOSE, id));
  }

  CompletionStage<Account> detail(CorporateBanking.Selection company, Account selected) {
    return banking.read(
        reads -> {
          var current = current(reads, company, selected);
          if (current.available() == null && !current.pay() && !current.close())
            throw new IllegalArgumentException("CORPORATION_UNAVAILABLE");
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
          lines.add(t("account_id", selected.endpoint().account().value()));
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
      label = t("player", owner);
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
    boolean close = action.equals("corp_close");
    boolean funding = action.equals("corp_fund");
    return banking
        .read(
            reads -> {
              var rights = current(reads, company, account);
              var endpoint = account.endpoint();
              if (!(close ? rights.close() : rights.pay()))
                throw new IllegalArgumentException("CORPORATION_UNAVAILABLE");
              var lines = new ArrayList<String>();
              lines.add(t("owner", company.name()));
              if (close) lines.add(t("close_review", endpoint.account().value()));
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
              lines.add(t("source", funding ? peer.label() : company.name()));
              lines.add(t("account_id", source.account().value()));
              lines.add(t("target", funding ? company.name() : peer.label()));
              lines.add(t("account_id", target.account().value()));
              lines.add(t("principal", new Money(from, terms.principal())));
              lines.add(t("fee", new Money(from, terms.fee())));
              lines.add(t("credit", new Money(to, terms.credit())));
              if (terms.publication() != null) lines.add(t("fx_publication", terms.publication()));
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
