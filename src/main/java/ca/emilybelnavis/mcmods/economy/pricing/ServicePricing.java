package ca.emilybelnavis.mcmods.economy.pricing;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * Worker-confined, institution-owned fee quotes. A4 remains responsible for transfer and durable
 * consumption.
 */
public final class ServicePricing {
  public enum Service {
    FX,
    NPC_CASH_FX,
    TRADING,
    PROTECTED_WITHDRAWAL_FX,
    PROTECTED_CLOSURE_FX
  }

  public enum Tier {
    TIER1,
    TIER2
  }

  public static final String FACT = "economy:a6/service-pricing";

  public record Quote(
      UUID id,
      WorldId world,
      PlayerId owner,
      InstitutionCatalog.InstitutionId institution,
      Service service,
      Money principal,
      Money fee,
      Tier tier,
      int policyVersion,
      long quoteRevision,
      Instant expires,
      WealthValuation.Snapshot valuation) {
    public boolean tierTwo() {
      return tier == Tier.TIER2;
    }
  }

  private final WealthValuation valuation;
  private final Map<UUID, Quote> quotes = new HashMap<>();

  public ServicePricing(WealthValuation valuation) {
    this.valuation = Objects.requireNonNull(valuation);
  }

  public WealthValuation valuation() {
    return valuation;
  }

  public Quote quote(
      JournalEngine.ReadView reads,
      WorldId world,
      PlayerId owner,
      InstitutionCatalog.InstitutionId institution,
      Service service,
      Money principal,
      Instant now,
      Instant underlyingExpiry) {
    Objects.requireNonNull(service);
    Objects.requireNonNull(principal);
    Objects.requireNonNull(now);
    Objects.requireNonNull(underlyingExpiry);
    if (principal.minorUnits() <= 0)
      throw new IllegalArgumentException("Positive executed/exchanged principal required");
    var bank = pricingInstitution(institution, principal.currency());
    enabled(reads, world, institution);
    var snapshot = valuation.snapshot(reads, world, owner, now);
    boolean protectedService =
        service == Service.PROTECTED_WITHDRAWAL_FX || service == Service.PROTECTED_CLOSURE_FX;
    if (!snapshot.available() && (!protectedService || snapshot.publication() == null))
      throw new IllegalArgumentException(snapshot.reason());
    if (bank.customerBank()
        && !bank.tier1()
        && !snapshot.eligible()
        && !protectedService
        && service != Service.NPC_CASH_FX) throw new IllegalArgumentException("TIER2_UNAVAILABLE");
    var tier =
        bank.tier2() && snapshot.eligible() && cashBanked(reads, owner, service)
            ? Tier.TIER2
            : Tier.TIER1;
    var policy = PricingPolicy.current(reads, now.toEpochMilli());
    Instant expires =
        now.plusSeconds(60).isBefore(underlyingExpiry) ? now.plusSeconds(60) : underlyingExpiry;
    if (!expires.isAfter(now)) throw new IllegalArgumentException("PRICING_QUOTE_EXPIRED");
    quotes.values().removeIf(q -> !q.expires().isAfter(now));
    if (quotes.size() >= 1024) throw new IllegalStateException("PRICING_QUOTE_OVERLOAD");
    var quote =
        new Quote(
            UUID.randomUUID(),
            world,
            owner,
            institution,
            service,
            principal,
            fee(principal, service, tier),
            tier,
            policy.version(),
            WealthValuation.revision(snapshot),
            expires,
            snapshot);
    quotes.put(quote.id(), quote);
    return quote;
  }

  public Quote requireFresh(
      Quote quote, JournalEngine.ReadView reads, WorldId world, PlayerId owner, Instant now) {
    if (quote == null
        || !quote.equals(quotes.get(quote.id()))
        || !quote.world().equals(world)
        || !quote.owner().equals(owner)
        || !now.isBefore(quote.expires()))
      throw new IllegalArgumentException("PRICING_QUOTE_STALE");
    enabled(reads, world, quote.institution());
    if (now.isBefore(quote.valuation().valuedAt()))
      throw new IllegalArgumentException("PRICING_QUOTE_STALE");
    var current = valuation.snapshot(reads, world, owner, now);
    var captured = valuation.snapshot(reads, world, owner, quote.valuation().valuedAt());
    var bank = pricingInstitution(quote.institution(), quote.principal().currency());
    var currentTier =
        bank.tier2() && current.eligible() && cashBanked(reads, owner, quote.service())
            ? Tier.TIER2
            : Tier.TIER1;
    if (current.available() != quote.valuation().available()
        || current.eligible() != quote.valuation().eligible()
        || currentTier != quote.tier()
        || !captured.fingerprint().equals(quote.valuation().fingerprint())
        || PricingPolicy.current(reads, now.toEpochMilli()).version() != quote.policyVersion())
      throw new IllegalArgumentException("PRICING_QUOTE_STALE");
    return quote;
  }

  public Quote requireFresh(
      UUID quote, JournalEngine.ReadView reads, WorldId world, PlayerId owner, Instant now) {
    return requireFresh(quotes.get(quote), reads, world, owner, now);
  }

  private static boolean cashBanked(JournalEngine.ReadView reads, PlayerId owner, Service service) {
    return service != Service.NPC_CASH_FX
        || !reads
            .query(
                "SELECT account_id FROM account_catalog WHERE owner_id=? AND purpose='BANK' AND status='OPEN' LIMIT 1",
                List.of(owner.value().toString()),
                1)
            .isEmpty();
  }

  private static InstitutionCatalog.Entry pricingInstitution(
      InstitutionCatalog.InstitutionId id, EconomyCurrency currency) {
    return id.equals(InstitutionCatalog.IRONBRIDGE)
        ? InstitutionCatalog.require(id, currency)
        : InstitutionCatalog.require(id);
  }

  private static void enabled(
      JournalEngine.ReadView reads, WorldId world, InstitutionCatalog.InstitutionId institution) {
    var rows =
        reads.query(
            "SELECT enabled FROM institution_parties WHERE world_id=? AND institution_key=?",
            List.of(world.value().toString(), institution.key()),
            1);
    if (!rows.isEmpty() && !rows.getFirst().get("enabled").equals("1"))
      throw new IllegalArgumentException("PRICING_INSTITUTION_DISABLED");
  }

  public static Money fee(Money principal, Service service, Tier tier) {
    if (principal.minorUnits() < 0) throw new IllegalArgumentException("Negative fee principal");
    BigDecimal rate =
        tier == Tier.TIER2
            ? BigDecimal.ZERO
            : service == Service.TRADING ? PricingPolicy.TRADING_RATE : PricingPolicy.FX_RATE;
    return new Money(
        principal.currency(),
        BigDecimal.valueOf(principal.minorUnits())
            .multiply(rate)
            .setScale(0, RoundingMode.CEILING)
            .longValueExact());
  }

  public static BigDecimal depositApr(
      JournalEngine.ReadView reads,
      PlayerId owner,
      InstitutionCatalog.InstitutionId institution,
      long effectiveMillis) {
    var bank = InstitutionCatalog.require(institution);
    boolean tierTwo = bank.tier2() && TierReevaluation.tierTwoAt(reads, owner, effectiveMillis);
    return tierTwo
        ? PricingPolicy.TIER2_DEPOSIT_APR
        : bank.tier1() ? PricingPolicy.TIER1_DEPOSIT_APR : BigDecimal.ZERO;
  }

  public static Journal.Record fact(Quote q) {
    String inputs =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(q.valuation().inputs().getBytes(StandardCharsets.UTF_8));
    return new Journal.Record(
        FACT,
        q.institution().equals(InstitutionCatalog.IRONBRIDGE) ? 2 : 1,
        String.join(
            "|",
            q.id().toString(),
            q.world().value().toString(),
            q.owner().value().toString(),
            q.institution().key(),
            q.service().name(),
            q.principal().currency().name(),
            Long.toString(q.principal().minorUnits()),
            Long.toString(q.fee().minorUnits()),
            q.tier().name(),
            Integer.toString(q.policyVersion()),
            Long.toString(q.quoteRevision()),
            Long.toString(q.expires().toEpochMilli()),
            q.valuation().fingerprint(),
            inputs));
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return TierReevaluation.register(catalog)
        .with(new RecordCatalog.Key(FACT, 1), ServicePricing::validate)
        .with(new RecordCatalog.Key(FACT, 2), payload -> validate(payload, true));
  }

  private static void validate(String payload) {
    validate(payload, false);
  }

  private static void validate(String payload, boolean internal) {
    var p = payload.split("\\|", -1);
    if (p.length != 14) throw new IllegalArgumentException("Service pricing fields");
    for (int i = 0; i < 3; i++)
      if (!UUID.fromString(p[i]).toString().equals(p[i]))
        throw new IllegalArgumentException("Pricing UUID");
    var institution = new InstitutionCatalog.InstitutionId(p[3]);
    if (internal) {
      if (!institution.equals(InstitutionCatalog.IRONBRIDGE) || !p[4].equals("FX"))
        throw new IllegalArgumentException("Internal lender FX pricing");
    } else InstitutionCatalog.require(institution);
    var service = Service.valueOf(p[4]);
    var amount = new Money(EconomyCurrency.valueOf(p[5]), Long.parseLong(p[6]));
    if (amount.minorUnits() <= 0
        || fee(amount, service, Tier.valueOf(p[8])).minorUnits() != Long.parseLong(p[7])
        || Integer.parseInt(p[9]) < 1
        || Long.parseLong(p[10]) < 0
        || Long.parseLong(p[11]) < 0)
      throw new IllegalArgumentException("Pricing fact amount/version");
    var inputs = new String(Base64.getUrlDecoder().decode(p[13]), StandardCharsets.UTF_8);
    if (inputs.getBytes(StandardCharsets.UTF_8).length > 10_000
        || !WealthValuation.digest(inputs).equals(p[12]))
      throw new IllegalArgumentException("Pricing witness");
  }
}
