package ca.emilybelnavis.mcmods.economy.corporation;

import ca.emilybelnavis.mcmods.economy.api.AccountFunds;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity;
import ca.emilybelnavis.mcmods.economy.api.CorporationIntegration.Capability;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.SystemActorId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.institution.BankProviders;
import ca.emilybelnavis.mcmods.economy.journal.Holds;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.journal.Settlement;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Worker operations for authenticated integration sessions and founder-approved mandates. */
public final class IntegrationBanking {
  public static final Journal.Kind PAYMENT =
      new Journal.Kind("economy:corporate-integrations", "economy:integration-payment");

  private IntegrationBanking() {}

  public static SystemActorId actor(WorldId world, String providerKey) {
    Objects.requireNonNull(world);
    Objects.requireNonNull(providerKey);
    return new SystemActorId(
        UUID.nameUUIDFromBytes(
            ("economy:corporate-provider:v1|" + world.value() + "|" + providerKey)
                .getBytes(StandardCharsets.UTF_8)));
  }

  public record Access(
      IntegrationBindings.Key key,
      SystemActorId actor,
      WorldId world,
      UUID company,
      long companyRevision,
      long mandateRevision,
      long providerGeneration) {
    public Access {
      Objects.requireNonNull(key);
      Objects.requireNonNull(company);
      if (!IntegrationBanking.actor(world, key.provider()).equals(actor)
          || companyRevision < 1
          || mandateRevision < 1
          || providerGeneration < 1) throw new IllegalArgumentException("Integration access");
    }

    public Map<String, String> fields() {
      return Map.of(
          "integration_provider", key.provider(),
          "integration_external_key", key.externalKey(),
          "integration_world", world.value().toString(),
          "integration_company", company.toString(),
          "integration_company_revision", Long.toString(companyRevision),
          "integration_mandate_revision", Long.toString(mandateRevision));
    }

    String encode() {
      return String.join(
          "|",
          key.provider(),
          key.externalKey(),
          Long.toString(mandateRevision),
          Long.toString(providerGeneration));
    }

    static Access decode(String[] fields, CorporatePayments.Endpoint source, String actor) {
      if (!(source.owner().owner() instanceof PartyRef.Organization organization))
        throw new IllegalArgumentException("Integration payment source");
      return new Access(
          new IntegrationBindings.Key(fields[0], fields[1]),
          new SystemActorId(UUID.fromString(actor)),
          source.owner().world(),
          organization.id().value(),
          source.companyRevision(),
          Long.parseLong(fields[2]),
          Long.parseLong(fields[3]));
    }
  }

  public record AccountView(
      AccountIdentity account, BankProviders.Binding provider, AccountFunds funds) {}

  /** Bounded account view under the current company, mandate, and provider session. */
  public static Optional<List<AccountView>> accounts(
      JournalEngine.ReadView reads, Access access, BooleanSupplier providerCurrent) {
    if (!providerCurrent.getAsBoolean() || !permits(reads, access, Capability.ACCOUNTS, null))
      return Optional.empty();
    var result = new ArrayList<AccountView>();
    for (var account : CorporateAccounts.list(reads, access.world(), access.company())) {
      if (!permits(reads, access, Capability.ACCOUNTS, account.id())) continue;
      var provider = BankProviders.find(reads, account.id()).orElse(null);
      if (provider == null) return Optional.empty();
      var currency = account.currency();
      var id = account.id().value();
      long reserved = Holds.reserved(reads, id, currency);
      long posted = Settlement.posted(reads, id, currency);
      result.add(
          new AccountView(
              account,
              provider,
              new AccountFunds(currency, Math.subtractExact(posted, reserved), reserved, 0)));
    }
    return providerCurrent.getAsBoolean() ? Optional.of(List.copyOf(result)) : Optional.empty();
  }

  public static Optional<CorporatePayments.Terms> quote(
      JournalEngine.ReadView reads,
      Access access,
      BooleanSupplier providerCurrent,
      CorporatePayments.Endpoint source,
      CorporatePayments.Endpoint target,
      long principal,
      long now) {
    if (!validRoute(access, source, target)
        || !providerCurrent.getAsBoolean()
        || !canPay(reads, access, source, target)) return Optional.empty();
    var result = CorporatePayments.quoteIntegration(reads, source, target, principal, now);
    return providerCurrent.getAsBoolean() ? result : Optional.empty();
  }

  public static Journal.CanonicalRequest request(Access access, CorporatePayments.Payment payment) {
    var fields = new HashMap<>(access.fields());
    fields.putAll(payment.fields());
    return new Journal.CanonicalRequest(1, fields);
  }

  public static JournalEngine.Decision prepare(
      Journal.Intent intent,
      JournalEngine.ReadView reads,
      Access access,
      BooleanSupplier providerCurrent,
      CorporatePayments.Payment payment,
      long now) {
    if (!PAYMENT.equals(intent.kind())
        || !intent.actor().equals(access.actor())
        || !intent.request().equals(request(access, payment))
        || intent.correctionOf() != null
        || payment.membershipRevision() != 0
        || payment.drain()
        || !validRoute(access, payment.source(), payment.target()))
      return reject("INTEGRATION_REQUEST");
    if (!providerCurrent.getAsBoolean()) return reject("INTEGRATION_PROVIDER_STALE");
    if (!canPay(reads, access, payment.source(), payment.target()))
      return reject("INTEGRATION_SCOPE_DENIED");
    var decision = CorporatePayments.prepareIntegration(reads, access, payment, now);
    return providerCurrent.getAsBoolean() ? decision : reject("INTEGRATION_PROVIDER_STALE");
  }

  static boolean validRoute(
      Access access, CorporatePayments.Endpoint source, CorporatePayments.Endpoint target) {
    return source.owner().world().equals(access.world())
        && target.owner().world().equals(access.world())
        && source.owner().owner() instanceof PartyRef.Organization organization
        && organization.id().value().equals(access.company())
        && source.companyRevision() == access.companyRevision()
        && !source.account().equals(target.account())
        && (!source.owner().equals(target.owner())
            || target.companyRevision() == access.companyRevision());
  }

  private static boolean canPay(
      JournalEngine.ReadView reads,
      Access access,
      CorporatePayments.Endpoint source,
      CorporatePayments.Endpoint target) {
    return permits(reads, access, Capability.PAYMENTS, source.account())
        && (!source.owner().equals(target.owner())
            || permits(reads, access, Capability.PAYMENTS, target.account()));
  }

  private static boolean permits(
      JournalEngine.ReadView reads, Access access, Capability capability, AccountId account) {
    return IntegrationBindings.permits(
        reads,
        access.key(),
        access.company(),
        access.companyRevision(),
        access.mandateRevision(),
        capability,
        account);
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }
}
