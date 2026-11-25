package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.BankingMenu;
import ca.emilybelnavis.mcmods.economy.card.CorporateCards;
import ca.emilybelnavis.mcmods.economy.corporation.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import net.minecraft.server.level.ServerPlayer;

/** Ordinary corporate banking at a live bank kiosk. A review binds one operation to one proof. */
public final class CorporateBanking {
  public static final Journal.Kind KIND =
      new Journal.Kind("economy:corporations", "economy:corporations/bank");
  private static final Map<ServerServices, UUID> AUDIENCES = new WeakHashMap<>();
  private final ServerPlayer player;
  private final BankingMenu menu;
  private final ServerServices services;
  private final TerminalPolicy.Anchor anchor;
  private final UUID generation;
  private final PlayerId actor;
  private final WorldId world;

  public CorporateBanking(ServerPlayer player, BankingMenu menu) {
    this.player = Objects.requireNonNull(player);
    this.menu = Objects.requireNonNull(menu);
    this.services = Objects.requireNonNull(Economy.serverServices(player.server));
    this.anchor = menu.anchor();
    this.generation = menu.generation();
    this.actor = new PlayerId(player.getUUID());
    this.world = services.worldId();
    grant();
  }

  /** Called on the server thread before enqueuing a change which could revoke a pending read. */
  static UUID audienceStamp(ServerServices services) {
    return AUDIENCES.get(services);
  }

  static void authorityChanging(ServerServices services) {
    AUDIENCES.put(services, UUID.randomUUID());
  }

  public record Selection(UUID id, String name, long companyRevision, long membershipRevision) {}

  public record AccountView(
      CorporatePayments.Endpoint endpoint, EconomyCurrency currency, long available) {}

  public record Receipt(OperationId operation, Journal.Status status, String code) {}

  private record Branch(
      TerminalPolicy.Snapshot terminal,
      InstitutionCatalog.InstitutionId bank,
      long denominationRevision) {}

  private PartyAuthorization.Grant grant() {
    if ((menu.access() != BankingMenu.Access.KIOSK
            && menu.access() != BankingMenu.Access.CORPORATION)
        || !menu.valid(player)
        || Economy.serverServices(player.server) != services)
      throw new IllegalStateException("BRANCH_UNAVAILABLE");
    return PartyAuthorization.player(
            player, Economy::serverServices, new PartyRef(world, new PartyRef.Player(actor)))
        .orElseThrow();
  }

  private Branch branch(JournalEngine.ReadView reads) {
    var terminal =
        menu.access() == BankingMenu.Access.CORPORATION
            ? null
            : TerminalPolicy.find(reads, world, anchor, actor, true)
                .filter(
                    t ->
                        t.active()
                            && t.generation().equals(generation)
                            && t.kind() == TerminalPolicy.Kind.KIOSK
                            && t.binding() != null
                            && t.binding().bound())
                .orElseThrow(CorporateBanking::unavailable);
    var issuer = terminal == null ? menu.corporateBank() : terminal.binding().institution();
    if (issuer == null) throw unavailable();
    var bank =
        InstitutionCatalog.find(issuer)
            .filter(InstitutionCatalog.Entry::enabled)
            .orElseThrow(CorporateBanking::unavailable);
    var status =
        reads.query(
            "SELECT enabled FROM institution_parties WHERE world_id=? AND institution_key=?",
            List.of(world.value().toString(), bank.id().key()),
            1);
    if (status.isEmpty() || !"1".equals(status.getFirst().get("enabled"))) throw unavailable();
    return new Branch(
        terminal,
        bank.id(),
        RebaseSettlement.denomination(reads).orElseThrow(CorporateBanking::unavailable).revision());
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("CORPORATION_UNAVAILABLE");
  }

  <T> CompletionStage<T> read(Function<JournalEngine.ReadView, T> query) {
    var stamp = AUDIENCES.get(services);
    long menuRevision = menu.revision();
    return services
        .readAccounts(grant(), false, reads -> Optional.of(query.apply(reads)))
        .thenApplyAsync(
            result -> {
              grant();
              if (!Objects.equals(stamp, AUDIENCES.get(services))
                  || menu.revision() != menuRevision) throw unavailable();
              return result.orElseThrow(CorporateBanking::unavailable);
            },
            player.server);
  }

  /** Each indexed query returns up to nine available companies. The ninth row is lookahead. */
  public record CompanyPage(List<Selection> choices, UUID next) {
    public CompanyPage {
      choices = List.copyOf(choices);
    }
  }

  public CompletionStage<CompanyPage> companies(UUID after) {
    return read(
        reads -> {
          branch(reads);
          String last = Objects.toString(after, ""), who = actor.value().toString();
          List<Map<String, String>> candidates;
          if (menu.issuedToken() != null) {
            var card = card(reads);
            candidates =
                reads.query(
                    """
                SELECT c.id,c.name,c.revision,CASE WHEN c.founder=? THEN 0 ELSE m.revision END AS membership
                FROM corporations c LEFT JOIN corporation_managers m ON m.company=c.id AND m.manager=? AND m.active=1
                WHERE c.id=? AND c.id>? AND (c.founder=? OR m.active=1)
                """,
                    List.of(who, who, card.company().toString(), last, who),
                    1);
          } else {
            candidates =
                reads.query(
                    "SELECT id,name,revision,0 AS membership FROM corporations WHERE founder=? AND id>? AND state<>'DISSOLVED' ORDER BY id LIMIT 9",
                    List.of(who, last),
                    9);
            if (menu.access() == BankingMenu.Access.CORPORATION) {
              var managed =
                  reads.query(
                      "SELECT c.id,c.name,c.revision,m.revision AS membership FROM corporation_managers m JOIN corporations c ON c.id=m.company WHERE m.active=1 AND m.manager=? AND m.company>? AND c.state<>'DISSOLVED' ORDER BY m.company LIMIT 9",
                      List.of(who, last),
                      9);
              candidates =
                  java.util.stream.Stream.concat(candidates.stream(), managed.stream())
                      .sorted(Comparator.comparing(r -> r.get("id")))
                      .limit(9)
                      .toList();
            }
          }
          var visible =
              candidates.stream()
                  .limit(8)
                  .map(
                      r ->
                          new Selection(
                              UUID.fromString(r.get("id")),
                              r.get("name"),
                              Long.parseLong(r.get("revision")),
                              Long.parseLong(r.get("membership"))))
                  .toList();
          return new CompanyPage(
              visible, candidates.size() > 8 ? UUID.fromString(candidates.get(7).get("id")) : null);
        });
  }

  private CorporateCards.Account card(JournalEngine.ReadView reads) {
    return CorporateCards.resolve(reads, world, menu.issuedToken(), actor)
        .orElseThrow(CorporateBanking::unavailable);
  }

  String playerName(UUID id) {
    var online = player.server.getPlayerList().getPlayer(id);
    if (online != null) return online.getGameProfile().getName();
    var cache = player.server.getProfileCache();
    return cache == null
        ? "Unknown player"
        : cache.get(id).map(com.mojang.authlib.GameProfile::getName).orElse("Unknown player");
  }

  private void requireSelection(
      JournalEngine.ReadView reads, UUID company, long revision, long membership) {
    if (menu.issuedToken() != null && !card(reads).company().equals(company)) throw unavailable();
    if (membership == 0
        && Corporations.find(reads, actor, company)
            .filter(c -> c.revision() == revision && c.state() != Corporations.State.DISSOLVED)
            .isPresent()) return;
    if (membership > 0
        && !reads
            .query(
                "SELECT 1 FROM corporation_managers m JOIN corporations c ON c.id=m.company WHERE m.company=? AND m.manager=? AND m.active=1 AND m.revision=? AND c.revision=? AND c.state<>'DISSOLVED'",
                List.of(
                    company.toString(),
                    actor.value().toString(),
                    Long.toString(membership),
                    Long.toString(revision)),
                1)
            .isEmpty()) return;
    throw unavailable();
  }

  public CompletionStage<List<AccountView>> accounts(Selection selection) {
    return read(
        reads -> {
          var bank = branch(reads).bank();
          requireSelection(
              reads, selection.id, selection.companyRevision, selection.membershipRevision);
          var result = new ArrayList<AccountView>();
          for (var account : CorporateAccounts.list(reads, world, selection.id)) {
            var provider =
                BankProviders.find(reads, account.id()).orElseThrow(CorporateBanking::unavailable);
            if (!provider.bankKey().equals(bank.key())) continue;
            if (!permits(reads, selection, Managers.Use.HISTORY, account.id())) continue;
            var endpoint =
                new CorporatePayments.Endpoint(
                    account.owner(),
                    account.id(),
                    account.revision(),
                    provider.bankKey(),
                    provider.revision(),
                    selection.companyRevision);
            result.add(
                new AccountView(
                    endpoint,
                    account.currency(),
                    Holds.available(reads, account.id().value(), account.currency())));
          }
          return List.copyOf(result);
        });
  }

  InstitutionCatalog.Entry selectedBank(JournalEngine.ReadView reads, Selection selection) {
    requireSelection(
        reads, selection.id(), selection.companyRevision(), selection.membershipRevision());
    return InstitutionCatalog.require(branch(reads).bank());
  }

  boolean permits(
      JournalEngine.ReadView reads, Selection selection, Managers.Use use, AccountId account) {
    if (menu.issuedToken() != null) {
      var linked = card(reads);
      if (!linked.company().equals(selection.id())
          || (account != null && !linked.account().equals(account))) return false;
    }
    if (selection.membershipRevision() == 0)
      return Corporations.find(reads, actor, selection.id())
          .filter(
              c ->
                  c.revision() == selection.companyRevision()
                      && c.state() != Corporations.State.DISSOLVED
                      && (use != Managers.Use.OPEN || c.state() == Corporations.State.ACTIVE))
          .isPresent();
    return Managers.permits(
        reads,
        actor,
        new Managers.Requirement(
            selection.id(),
            selection.companyRevision(),
            selection.membershipRevision(),
            use,
            account == null ? null : account.value()));
  }

  CorporateKiosk kiosk() {
    return new CorporateKiosk(this, actor, world, services::financialMillis);
  }

  /** Original canonical fields can be retained for actor-bound lookup after reconnect or reopen. */
  public static final class Review {
    private final CorporateBanking issuer;
    private final Journal.CanonicalRequest request;
    private final JournalEngine.Registration registration;

    private Review(
        CorporateBanking issuer,
        Journal.CanonicalRequest request,
        JournalEngine.Registration registration) {
      this.issuer = issuer;
      this.request = request;
      this.registration = registration;
    }

    public Journal.CanonicalRequest request() {
      return request;
    }
  }

  private CompletionStage<Review> review(
      String action,
      UUID company,
      String bank,
      Map<String, String> fields,
      AccountId account,
      long revision,
      long provider,
      JournalEngine.Handler handler) {
    return read(this::branch)
        .thenApply(
            branch -> {
              if (!branch.bank().key().equals(bank)) throw unavailable();
              var context =
                  new BranchAdmission.Context(
                      world,
                      menu.session(),
                      actor,
                      BranchAdmission.Action.CORPORATE_BANK,
                      branch.bank(),
                      null,
                      account,
                      revision,
                      provider,
                      branch.denominationRevision);
              var proof =
                  branch.terminal == null
                      ? KioskAdmission.office(player, menu, context)
                      : KioskAdmission.issue(player, menu, branch.terminal, context);
              var token = menu.issuedToken();
              var canonical = new TreeMap<>(fields);
              canonical.put("world", world.value().toString());
              canonical.put("actor", actor.value().toString());
              canonical.put("selected_company", company.toString());
              canonical.put("branch_bank", bank);
              canonical.put("branch_action", action);
              canonical.put("branch_lease", proof.id().toString());
              canonical.put("menu_session", menu.session().toString());
              canonical.put("denomination_revision", Long.toString(branch.denominationRevision));
              var request = new Journal.CanonicalRequest(1, canonical);
              var bound = new TreeMap<>(canonical);
              bound.put("economy:owner", Journal.actorKey(actor));
              var registration =
                  new JournalEngine.Registration(
                      1,
                      (intent, reads) -> {
                        if (!KIND.equals(intent.kind())
                            || !actor.equals(intent.actor())
                            || !bound.equals(intent.request().fields())
                            || !proof.valid(intent, reads)) return reject("BRANCH_UNAVAILABLE");
                        if (RebaseSettlement.denomination(reads)
                            .filter(d -> d.revision() == branch.denominationRevision)
                            .isEmpty()) return reject("DENOMINATION_STALE");
                        if (!reads
                            .query(
                                "SELECT 1 FROM bank_provider_leases WHERE lease_id=?",
                                List.of(proof.id().toString()),
                                1)
                            .isEmpty()) return reject("LEASE_USED");
                        if (token != null) {
                          var linked = CorporateCards.resolve(reads, world, token, actor);
                          if (linked.isEmpty()
                              || !linked.get().company().equals(company)
                              || account != null && !linked.get().account().equals(account))
                            return reject("CORPORATION_UNAVAILABLE");
                        }
                        var decision = handler.prepare(intent, reads);
                        if (!(decision instanceof JournalEngine.Commit commit)) return decision;
                        return new JournalEngine.Commit(
                            commit.records(),
                            commit.result(),
                            tx -> {
                              tx.execute(
                                  "INSERT INTO bank_provider_leases VALUES(?,?,?)",
                                  List.of(
                                      proof.id().toString(),
                                      intent.operationId().value().toString(),
                                      intent.canonical()));
                              commit.projections().apply(tx);
                            });
                      });
              return new Review(this, request, registration);
            });
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }

  public CompletionStage<Review> reviewOpen(CorporateAccounts.Open open) {
    return review(
        "OPEN",
        open.company(),
        open.bank().key(),
        open.fields(),
        null,
        0,
        0,
        (i, r) -> CorporateAccounts.prepare(i, r, open));
  }

  private static CorporatePayments.Endpoint corporateBranch(
      CorporatePayments.Endpoint source, CorporatePayments.Endpoint target) {
    return source.owner().owner() instanceof PartyRef.Organization ? source : target;
  }

  private static UUID company(CorporatePayments.Endpoint e) {
    if (e.owner().owner() instanceof PartyRef.Organization org) return org.id().value();
    throw unavailable();
  }

  public CompletionStage<Optional<CorporatePayments.Terms>> quote(
      CorporatePayments.Endpoint source,
      CorporatePayments.Endpoint target,
      long membershipRevision,
      long principal,
      boolean drain) {
    var endpoint = corporateBranch(source, target);
    return read(
        reads -> {
          if (!source.owner().world().equals(world)
              || !target.owner().world().equals(world)
              || !branch(reads).bank().key().equals(endpoint.bank())) throw unavailable();
          return CorporatePayments.quote(
              reads,
              actor,
              source,
              target,
              membershipRevision,
              principal,
              drain,
              services.financialMillis());
        });
  }

  public CompletionStage<Review> reviewPayment(CorporatePayments.Payment payment) {
    return reviewPayment(payment, null);
  }

  CompletionStage<Review> reviewPayment(CorporatePayments.Payment payment, Selection selection) {
    if (payment.drain()
        || !payment.source().owner().world().equals(world)
        || !payment.target().owner().world().equals(world)) throw unavailable();
    var anchor = corporateBranch(payment.source(), payment.target());
    var fields = new TreeMap<>(payment.fields());
    if (selection != null) {
      if (!company(anchor).equals(selection.id())
          || anchor.companyRevision() != selection.companyRevision()) throw unavailable();
      fields.put("selected_membership_revision", Long.toString(selection.membershipRevision()));
    }
    return review(
        "PAYMENT",
        company(anchor),
        anchor.bank(),
        fields,
        anchor.account(),
        anchor.accountRevision(),
        anchor.providerRevision(),
        (i, r) -> {
          if (selection != null && !permits(r, selection, Managers.Use.PAYMENT, anchor.account()))
            return reject("CORPORATION_UNAVAILABLE");
          return CorporatePayments.prepare(i, r, payment, services.financialMillis());
        });
  }

  public CompletionStage<Review> reviewDebitIssue(
      Selection company, CorporateKiosk.Account selected) {
    var endpoint = selected.endpoint();
    if (!company(endpoint).equals(company.id()) || !selected.issueDebit()) throw unavailable();
    var account = endpoint.account();
    var fields =
        Map.of(
            "account",
            account.value().toString(),
            "account_revision",
            Long.toString(endpoint.accountRevision()),
            "provider_revision",
            Long.toString(endpoint.providerRevision()),
            "company_revision",
            Long.toString(company.companyRevision()),
            "membership_revision",
            Long.toString(company.membershipRevision()));
    return review(
        "ISSUE_DEBIT",
        company.id(),
        endpoint.bank(),
        fields,
        account,
        endpoint.accountRevision(),
        endpoint.providerRevision(),
        (intent, reads) -> {
          if (!permits(reads, company, Managers.Use.CLOSE, account))
            return reject("CORPORATION_UNAVAILABLE");
          var identity = CorporateAccounts.find(reads, world, company.id(), account).orElse(null);
          var binding = BankProviders.find(reads, account).orElse(null);
          if (identity == null
              || identity.status() != AccountIdentity.Status.OPEN
              || identity.revision() != endpoint.accountRevision()
              || binding == null
              || binding.legacy()
              || !binding.bankKey().equals(endpoint.bank())
              || binding.revision() != endpoint.providerRevision())
            return reject("CORPORATION_ACCOUNT_STALE");
          if (CorporateCards.hasDebit(reads, account))
            return reject("CORPORATE_DEBIT_ALREADY_ISSUED");
          return CorporateCards.issueExisting(
              intent,
              reads,
              world,
              company.id(),
              account,
              new InstitutionCatalog.InstitutionId(endpoint.bank()),
              actor);
        });
  }

  public CompletionStage<Review> reviewCredit(
      Selection company,
      CorporateKiosk.Account account,
      String action,
      Map<String, String> fields,
      JournalEngine.Handler handler) {
    var endpoint = account.endpoint();
    if (!company(endpoint).equals(company.id()) || !account.credit()) throw unavailable();
    return review(
        action,
        company.id(),
        endpoint.bank(),
        fields,
        endpoint.account(),
        endpoint.accountRevision(),
        endpoint.providerRevision(),
        (intent, reads) -> {
          if (!permits(reads, company, Managers.Use.BORROW, endpoint.account())
              || !CorporateCapital.profile(reads, company.id()).orElseThrow().funded())
            return reject("CORPORATION_UNAVAILABLE");
          return handler.prepare(intent, reads);
        });
  }

  public CompletionStage<Review> reviewClose(
      CorporateAccounts.Close close, CorporatePayments.Payment settlement) {
    var fields = new TreeMap<>(close.fields());
    if ((close.destination() != null) != (settlement != null)) throw unavailable();
    if (settlement != null) {
      var source = settlement.source();
      if (!settlement.drain()
          || !source.owner().world().equals(world)
          || !settlement.target().owner().world().equals(world)
          || !company(source).equals(close.company())
          || source.companyRevision() != close.companyRevision()
          || !source.bank().equals(close.bank().key())
          || !source.account().equals(close.source())
          || source.accountRevision() != close.accountRevision()
          || source.providerRevision() != close.providerRevision()
          || settlement.membershipRevision() != close.membershipRevision()
          || !settlement.target().account().equals(close.destination())
          || settlement.target().accountRevision() != close.destinationRevision())
        throw unavailable();
      fields.putAll(settlement.fields());
    }
    return review(
        "CLOSE",
        close.company(),
        close.bank().key(),
        fields,
        close.source(),
        close.accountRevision(),
        close.providerRevision(),
        (i, r) ->
            CorporateAccounts.prepare(
                i,
                r,
                close,
                settlement == null
                    ? null
                    : (intent, reads, src, dest, rev, balance) ->
                        CorporatePayments.prepare(
                            intent, reads, settlement, services.financialMillis())));
  }

  /**
   * The submitter receives operation status only. Account or history disclosure uses fresh rights.
   */
  public CompletionStage<Receipt> submit(OperationId operation, RequestId attempt, Review review) {
    if (review.issuer != this) throw unavailable();
    return services
        .submitOwned(grant(), KIND, operation, attempt, review.request, review.registration, null)
        .completion()
        .thenApplyAsync(
            delivery -> {
              grant();
              return receipt(delivery.outcome());
            },
            player.server);
  }

  public CompletionStage<Optional<Receipt>> lookup(
      OperationId operation, RequestId attempt, Journal.CanonicalRequest original) {
    return services
        .lookupOwned(
            grant(),
            KIND,
            operation,
            attempt,
            original,
            new JournalEngine.Registration(1, (i, r) -> reject("LOOKUP_ONLY")))
        .thenApplyAsync(
            result -> {
              grant();
              return result.map(CorporateBanking::receipt);
            },
            player.server);
  }

  PartyOperation operation(Review review) {
    if (review.issuer != this) throw unavailable();
    return PartyOperation.owned(
        grant(),
        KIND,
        new OperationId(UUID.randomUUID()),
        new RequestId(UUID.randomUUID()),
        review.request(),
        review.registration);
  }

  JournalService.Submission submit(PartyOperation operation) {
    var intent = operation.intent();
    return services
        .journal(
            (who, kind) ->
                KIND.equals(kind)
                    && operation.grant().allows(world, services.sessionId(), who, kind),
            Map.of(KIND, operation.registration()),
            null)
        .submit(intent);
  }

  private static Receipt receipt(Journal.Outcome outcome) {
    return new Receipt(outcome.operationId(), outcome.status(), outcome.result().code());
  }
}
