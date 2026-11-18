package ca.emilybelnavis.mcmods.economy.corporation;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity;
import ca.emilybelnavis.mcmods.economy.api.CurrencyConversion;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.ActorId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.OrganizationId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.institution.BankProviders;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.FeeSettlement;
import ca.emilybelnavis.mcmods.economy.journal.Holds;
import ca.emilybelnavis.mcmods.economy.journal.InstitutionSettlement;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.journal.PostingBatch;
import ca.emilybelnavis.mcmods.economy.journal.Settlement;
import ca.emilybelnavis.mcmods.economy.overdraft.Overdrafts;
import ca.emilybelnavis.mcmods.economy.pricing.ServicePricing;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Ordinary corporate payments, with the funding check, quote and postings in one worker decision.
 */
public final class CorporatePayments {
  public static final String FACT = "economy:corporations/payment";
  private static final long FX_MAX_AGE = Duration.ofHours(48).toMillis();
  private static final int FEE_DIVISOR = 200; // Tier 1 fee: 0.50% of principal.

  private CorporatePayments() {}

  public record Endpoint(
      PartyRef owner,
      AccountId account,
      long accountRevision,
      String bank,
      long providerRevision,
      long companyRevision) {
    public Endpoint {
      Objects.requireNonNull(owner);
      Objects.requireNonNull(account);
      new InstitutionCatalog.InstitutionId(bank);
      if (accountRevision < 1
          || providerRevision < 1
          || !(owner.owner() instanceof PartyRef.Organization
              ? companyRevision > 0
              : owner.owner() instanceof PartyRef.Player && companyRevision == 0))
        throw new IllegalArgumentException("Payment endpoint");
    }

    private boolean corporate() {
      return owner.owner() instanceof PartyRef.Organization;
    }

    private String encode() {
      String ownerKey =
          switch (owner.owner()) {
            case PartyRef.Organization organization -> "organization:" + organization.id().value();
            case PartyRef.Player player -> "player:" + player.id().value();
            default -> throw new IllegalArgumentException("Payment owner type");
          };
      return String.join(
          ",",
          owner.world().value().toString(),
          ownerKey,
          account.value().toString(),
          Long.toString(accountRevision),
          bank,
          Long.toString(providerRevision),
          Long.toString(companyRevision));
    }

    private static Endpoint decode(String value) {
      var fields = value.split(",", -1);
      if (fields.length != 7) throw new IllegalArgumentException("Payment endpoint fields");
      var owner = fields[1].split(":", -1);
      if (owner.length != 2) throw new IllegalArgumentException("Payment owner");
      UUID id = UUID.fromString(owner[1]);
      PartyRef.Owner typed =
          switch (owner[0]) {
            case "organization" -> new PartyRef.Organization(new OrganizationId(id));
            case "player" -> new PartyRef.Player(new PlayerId(id));
            default -> throw new IllegalArgumentException("Payment owner type");
          };
      return new Endpoint(
          new PartyRef(new WorldId(UUID.fromString(fields[0])), typed),
          new AccountId(UUID.fromString(fields[2])),
          Long.parseLong(fields[3]),
          fields[4],
          Long.parseLong(fields[5]),
          Long.parseLong(fields[6]));
    }
  }

  public record Terms(long principal, long fee, long credit, UUID publication) {
    public Terms {
      if (principal <= 0
          || fee < 0
          || credit <= 0
          || (publication == null ? fee != 0 || credit != principal : fee != feeFor(principal)))
        throw new IllegalArgumentException("Payment terms");
      Math.addExact(principal, fee);
    }
  }

  public record Payment(
      Endpoint source, Endpoint target, long membershipRevision, boolean drain, Terms terms) {
    public Payment {
      Objects.requireNonNull(terms);
      validateEndpoints(source, target, membershipRevision, drain);
    }

    public Map<String, String> fields() {
      return Map.of(
          "payment_source", source.encode(),
          "payment_target", target.encode(),
          "payment_membership_revision", Long.toString(membershipRevision),
          "payment_drain", Boolean.toString(drain),
          "payment_principal", Long.toString(terms.principal()),
          "payment_fee", Long.toString(terms.fee()),
          "payment_credit", Long.toString(terms.credit()),
          "payment_publication", Objects.toString(terms.publication(), ""));
    }
  }

  private record Accounts(
      EconomyCurrency from, EconomyCurrency to, InstitutionCatalog.InstitutionId supplier) {}

  /** Capture reviewed terms on the worker after the caller authenticates the branch audience. */
  public static Optional<Terms> quote(
      JournalEngine.ReadView reads,
      PlayerId actor,
      Endpoint source,
      Endpoint target,
      long membershipRevision,
      long principal,
      boolean drain,
      long now) {
    validateEndpoints(source, target, membershipRevision, drain);
    try {
      var accounts = accounts(reads, actor, source, target, membershipRevision, drain);
      return Optional.of(approvedTerms(reads, source, accounts, principal, drain, now));
    } catch (Refusal | CurrencyConversion.Rejected | ArithmeticException unavailable) {
      return Optional.empty();
    }
  }

  static Optional<Terms> quoteIntegration(
      JournalEngine.ReadView reads, Endpoint source, Endpoint target, long principal, long now) {
    try {
      validateEndpoints(source, target, 0, false);
      return Optional.of(
          approvedTerms(
              reads, source, paymentAccounts(reads, source, target), principal, false, now));
    } catch (Refusal | CurrencyConversion.Rejected | ArithmeticException unavailable) {
      return Optional.empty();
    }
  }

  private static Terms approvedTerms(
      JournalEngine.ReadView reads,
      Endpoint source,
      Accounts accounts,
      long principal,
      boolean drain,
      long now) {
    var terms = terms(reads, source, accounts, principal, drain, now);
    requireFunds(reads, source, accounts.from(), terms, drain);
    return terms;
  }

  /** The outer handler admits the live branch proof and composes funded closure when applicable. */
  public static JournalEngine.Decision prepare(
      Journal.Intent intent, JournalEngine.ReadView reads, Payment payment, long now) {
    if (!(intent.actor() instanceof PlayerId actor)
        || !intent.request().fields().entrySet().containsAll(payment.fields().entrySet()))
      return reject("CORPORATE_PAYMENT_AUTHORITY");
    return prepare(reads, payment, now, actor.value(), null);
  }

  static JournalEngine.Decision prepareIntegration(
      JournalEngine.ReadView reads, IntegrationBanking.Access access, Payment payment, long now) {
    return prepare(reads, payment, now, access.actor().value(), access);
  }

  private static JournalEngine.Decision prepare(
      JournalEngine.ReadView reads,
      Payment payment,
      long now,
      UUID actor,
      IntegrationBanking.Access integration) {
    var source = payment.source();
    var target = payment.target();
    var reviewed = payment.terms();
    try {
      var accounts =
          integration == null
              ? accounts(
                  reads,
                  new PlayerId(actor),
                  source,
                  target,
                  payment.membershipRevision(),
                  payment.drain())
              : paymentAccounts(reads, source, target);
      var actual = terms(reads, source, accounts, reviewed.principal(), payment.drain(), now);
      if (!actual.equals(reviewed)) return reject("CORPORATE_PAYMENT_STALE");
      var from = accounts.from();
      var to = accounts.to();
      requireFunds(reads, source, from, reviewed, payment.drain());
      UUID payer = source.account().value();
      var legs = new ArrayList<PostingBatch.Leg>();
      legs.add(leg(payer, PostingBatch.Role.ACCOUNT, from, -reviewed.principal()));
      if (from != to) {
        legs.add(
            leg(
                Settlement.clearingAccount(from),
                PostingBatch.Role.CLEARING,
                from,
                reviewed.principal()));
        legs.add(
            leg(
                Settlement.clearingAccount(to),
                PostingBatch.Role.CLEARING,
                to,
                -reviewed.credit()));
      }
      legs.add(leg(target.account().value(), PostingBatch.Role.ACCOUNT, to, reviewed.credit()));
      var settled = InstitutionSettlement.prepareCorporatePayment(reads, legs);
      if (!(settled instanceof JournalEngine.Commit principal)) return settled;

      var facts = new ArrayList<>(principal.records());
      JournalEngine.Commit fee = null;
      if (reviewed.fee() != 0) {
        var overlay = FeeSettlement.overlay(reads, principal.records());
        var amount = new Money(from, reviewed.fee());
        var charge =
            switch (source.owner().owner()) {
              case PartyRef.Organization organization ->
                  FeeSettlement.chargeCorporate(
                      overlay,
                      source.owner().world(),
                      organization.id(),
                      accounts.supplier(),
                      amount,
                      payer);
              case PartyRef.Player player ->
                  FeeSettlement.charge(
                      overlay,
                      source.owner().world(),
                      player.id(),
                      accounts.supplier(),
                      amount,
                      payer);
              default -> throw new IllegalStateException("Unsupported payment owner");
            };
        if (!(charge instanceof JournalEngine.Commit charged)) return charge;
        fee = charged;
        facts.addAll(fee.records());
      }
      String body = encodeFact(actor, payment, from, to);
      String receipt = integration == null ? body : integration.encode() + "|" + body;
      facts.add(new Journal.Record(FACT, integration == null ? 1 : 2, receipt));
      var feeCommit = fee;
      return new JournalEngine.Commit(
          facts,
          new Journal.Result(1, "CORPORATE_PAYMENT_SETTLED", receipt),
          tx -> {
            principal.projections().apply(tx);
            if (feeCommit != null) feeCommit.projections().apply(tx);
          });
    } catch (Refusal denied) {
      return reject(denied.getMessage());
    } catch (CurrencyConversion.Rejected conversion) {
      return reject("CORPORATE_FX_" + conversion.failure().name());
    } catch (ArithmeticException overflow) {
      return reject("POSTING_OVERFLOW");
    }
  }

  private static Accounts accounts(
      JournalEngine.ReadView reads,
      PlayerId actor,
      Endpoint source,
      Endpoint target,
      long membershipRevision,
      boolean drain) {
    if (source.owner().owner() instanceof PartyRef.Player player && !player.id().equals(actor))
      throw new Refusal("CORPORATE_PAYMENT_AUTHORITY");
    var sourceCompany = company(reads, source);
    boolean sameCompany = source.corporate() && source.owner().equals(target.owner());
    if (sourceCompany != null) {
      UUID company = ((PartyRef.Organization) source.owner().owner()).id().value();
      if (!sourceCompany.get("founder").equals(actor.value().toString())) {
        var use = drain && sameCompany ? Managers.Use.CLOSE : Managers.Use.PAYMENT;
        if (!permits(reads, actor, company, source, membershipRevision, use)
            || sameCompany && !permits(reads, actor, company, target, membershipRevision, use))
          throw new Refusal("CORPORATE_PAYMENT_AUTHORITY");
      } else if (membershipRevision != 0) {
        throw new Refusal("CORPORATE_PAYMENT_AUTHORITY");
      }
    }
    return paymentAccounts(reads, source, target);
  }

  private static Accounts paymentAccounts(
      JournalEngine.ReadView reads, Endpoint source, Endpoint target) {
    var from = account(reads, source);
    EconomyCurrency to;
    try {
      company(reads, target);
      to = account(reads, target);
    } catch (Refusal denied) {
      if (!source.owner().equals(target.owner()))
        throw new Refusal("CORPORATE_DESTINATION_UNAVAILABLE");
      throw denied;
    }
    return new Accounts(
        from,
        to,
        new InstitutionCatalog.InstitutionId(source.corporate() ? source.bank() : target.bank()));
  }

  private static boolean permits(
      JournalEngine.ReadView reads,
      PlayerId actor,
      UUID company,
      Endpoint endpoint,
      long membership,
      Managers.Use use) {
    return membership > 0
        && Managers.permits(
            reads,
            actor,
            new Managers.Requirement(
                company, endpoint.companyRevision(), membership, use, endpoint.account().value()));
  }

  private static Map<String, String> company(JournalEngine.ReadView reads, Endpoint endpoint) {
    if (!(endpoint.owner().owner() instanceof PartyRef.Organization organization)) return null;
    var rows =
        reads.query(
            "SELECT founder FROM corporations WHERE id=? AND revision=? AND state<>'DISSOLVED'",
            List.of(
                organization.id().value().toString(), Long.toString(endpoint.companyRevision())),
            1);
    if (rows.isEmpty()) throw new Refusal("CORPORATE_COMPANY_STALE");
    return rows.getFirst();
  }

  private static EconomyCurrency account(JournalEngine.ReadView reads, Endpoint endpoint) {
    var owner = endpoint.owner();
    var id = endpoint.account();
    var account =
        (owner.owner() instanceof PartyRef.Organization organization
                ? CorporateAccounts.find(reads, owner.world(), organization.id().value(), id)
                : AccountCatalog.find(reads, owner, id))
            .orElse(null);
    if (account == null
        || account.purpose() != AccountIdentity.Purpose.BANK
        || account.status() != AccountIdentity.Status.OPEN
        || account.revision() != endpoint.accountRevision())
      throw new Refusal("CORPORATE_ACCOUNT_STALE");
    var binding = BankProviders.find(reads, id).orElse(null);
    if (binding == null
        || binding.revision() != endpoint.providerRevision()
        || !binding.bankKey().equals(endpoint.bank()))
      throw new Refusal("CORPORATE_PROVIDER_STALE");
    if (binding.bankKey().equals(BankProviders.UNASSIGNED_PROVIDER))
      throw new Refusal("PROVIDER_ASSIGNMENT_REQUIRED");
    var bank =
        InstitutionCatalog.find(new InstitutionCatalog.InstitutionId(endpoint.bank())).orElse(null);
    if (bank == null
        || !bank.enabled()
        || !bank.customerBank()
        || bank.baseCurrency() != account.currency() && (endpoint.corporate() || !account.primary())
        || endpoint.corporate() && !bank.tier1()
        || reads
            .query(
                "SELECT 1 FROM institution_parties WHERE world_id=? AND institution_key=? AND"
                    + " enabled=1",
                List.of(owner.world().value().toString(), endpoint.bank()),
                1)
            .isEmpty()) throw new Refusal("CORPORATE_BANK_UNAVAILABLE");
    if (endpoint.corporate() && Overdrafts.find(reads, id.value()).isPresent())
      throw new Refusal("CORPORATE_CREDIT_UNSUPPORTED");
    return account.currency();
  }

  private static Terms terms(
      JournalEngine.ReadView reads,
      Endpoint source,
      Accounts accounts,
      long requested,
      boolean drain,
      long now) {
    var from = accounts.from();
    var to = accounts.to();
    long principal = drain ? Settlement.posted(reads, source.account().value(), from) : requested;
    if (principal <= 0) throw new Refusal("CORPORATE_PAYMENT_AMOUNT");
    if (from == to) return new Terms(principal, 0, principal, null);
    if (drain) {
      // Invert principal + ceil(principal/200) without multiplying the full balance by 200.
      long full = principal;
      principal = full - (full / 201 + (full % 201 == 0 ? 0 : 1));
      if (principal <= 0 || principal + feeFor(principal) != full)
        throw new Refusal("CORPORATE_FX_DRAIN_AMOUNT");
    }
    var fx = FxService.at(reads, now);
    if (fx == null
        || !fx.world().equals(source.owner().world())
        || now < fx.effectiveMillis()
        || now - fx.effectiveMillis() >= FX_MAX_AGE) throw new Refusal("CORPORATE_FX_UNAVAILABLE");
    long credit =
        CurrencyConversion.convert(
                new Money(from, principal),
                to,
                new CurrencyConversion.Rates(fx.reference(), fx.rates()),
                CurrencyConversion.receivedRounding(to))
            .minorUnits();
    if (credit <= 0) throw new Refusal("CORPORATE_FX_ZERO_CREDIT");
    long fee =
        ServicePricing.fee(
                new Money(from, principal), ServicePricing.Service.FX, ServicePricing.Tier.TIER1)
            .minorUnits();
    return new Terms(principal, fee, credit, fx.id());
  }

  private static long feeFor(long principal) {
    return principal / FEE_DIVISOR + (principal % FEE_DIVISOR == 0 ? 0 : 1);
  }

  private static void requireFunds(
      JournalEngine.ReadView reads,
      Endpoint source,
      EconomyCurrency currency,
      Terms terms,
      boolean drain) {
    var id = source.account().value();
    if (drain && Holds.hasActiveClaim(reads, id)) throw new Refusal("CORPORATE_ACCOUNT_HELD");
    if (Holds.available(reads, id, currency) < Math.addExact(terms.principal(), terms.fee()))
      throw new Refusal("INSUFFICIENT_FUNDS");
  }

  private static PostingBatch.Leg leg(
      UUID account, PostingBatch.Role role, EconomyCurrency currency, long amount) {
    return new PostingBatch.Leg(account, role, currency, new Money(currency, amount));
  }

  private static void validateEndpoints(
      Endpoint source, Endpoint target, long membershipRevision, boolean drain) {
    Objects.requireNonNull(source);
    Objects.requireNonNull(target);
    if (!source.owner().world().equals(target.owner().world())
        || source.account().equals(target.account())
        || !source.corporate() && !target.corporate()
        || membershipRevision < 0
        || !source.corporate() && (membershipRevision != 0 || drain))
      throw new IllegalArgumentException("Payment route");
  }

  private static String encodeFact(
      UUID actor, Payment payment, EconomyCurrency from, EconomyCurrency to) {
    var terms = payment.terms();
    return String.join(
        "|",
        actor.toString(),
        payment.source().encode(),
        payment.target().encode(),
        Long.toString(payment.membershipRevision()),
        Boolean.toString(payment.drain()),
        from.name(),
        to.name(),
        Long.toString(terms.principal()),
        Long.toString(terms.fee()),
        Long.toString(terms.credit()),
        Objects.toString(terms.publication(), ""));
  }

  public record Receipt(
      ActorId actor,
      Payment payment,
      EconomyCurrency sourceCurrency,
      EconomyCurrency targetCurrency) {}

  public static void validateFact(String payload) {
    decodeFact(payload);
  }

  public static Receipt decodeFact(String payload) {
    return decodeFact(payload, null);
  }

  public static void validateIntegrationFact(String payload) {
    decodeIntegrationFact(payload);
  }

  public record IntegrationReceipt(IntegrationBanking.Access access, Receipt receipt) {}

  public static IntegrationReceipt decodeIntegrationFact(String payload) {
    var segments = payload.split("\\|", 5);
    if (segments.length != 5) throw new IllegalArgumentException("Integration payment fact fields");
    var body = segments[4].split("\\|", -1);
    if (body.length != 11) throw new IllegalArgumentException("Integration payment fact body");
    var source = Endpoint.decode(body[1]);
    var access = IntegrationBanking.Access.decode(segments, source, body[0]);
    var receipt = decodeFact(segments[4], access);
    if (!IntegrationBanking.validRoute(
            access, receipt.payment().source(), receipt.payment().target())
        || receipt.payment().membershipRevision() != 0
        || receipt.payment().drain()
        || !payload.equals(access.encode() + "|" + segments[4]))
      throw new IllegalArgumentException("Integration payment fact");
    return new IntegrationReceipt(access, receipt);
  }

  private static Receipt decodeFact(String payload, IntegrationBanking.Access access) {
    var fields = payload.split("\\|", -1);
    if (fields.length != 11) throw new IllegalArgumentException("Corporate payment fact fields");
    var actor = UUID.fromString(fields[0]);
    var from = EconomyCurrency.valueOf(fields[5]);
    var to = EconomyCurrency.valueOf(fields[6]);
    var terms =
        new Terms(
            Long.parseLong(fields[7]),
            Long.parseLong(fields[8]),
            Long.parseLong(fields[9]),
            fields[10].isEmpty() ? null : UUID.fromString(fields[10]));
    var payment =
        new Payment(
            Endpoint.decode(fields[1]),
            Endpoint.decode(fields[2]),
            Long.parseLong(fields[3]),
            Boolean.parseBoolean(fields[4]),
            terms);
    if ((from == to) != (terms.publication() == null)
        || payment.source().owner().owner() instanceof PartyRef.Player player
            && (access != null || !player.id().value().equals(actor))
        || !payload.equals(encodeFact(actor, payment, from, to)))
      throw new IllegalArgumentException("Corporate payment fact");
    return new Receipt(access == null ? new PlayerId(actor) : access.actor(), payment, from, to);
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }

  private static final class Refusal extends RuntimeException {
    private Refusal(String code) {
      super(code);
    }
  }
}
