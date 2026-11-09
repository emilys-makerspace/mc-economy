package ca.emilybelnavis.mcmods.economy.credit;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.BankProviders;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import java.math.*;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Trusted exact-credit repayment composition. No temporary customer account or implicit
 * refinancing.
 */
public final class CreditRepaymentFx {
  public static final String FACT = "economy:a6/credit-repayment-fx";

  public record Quote(
      CreditFacilities.RepaymentRequest repayment,
      FxQuotes.Quote fx,
      long capturedAt,
      long denominationRevision,
      BankProviders.Binding fundingProvider,
      EconomyCurrency reference,
      BigDecimal referenceResidual) {
    public Quote {
      Objects.requireNonNull(repayment);
      Objects.requireNonNull(fx);
      Objects.requireNonNull(reference);
      Objects.requireNonNull(referenceResidual);
      if (capturedAt < 0
          || denominationRevision < 0
          || referenceResidual.signum() < 0
          || referenceResidual.precision() > 64
          || referenceResidual.scale() < -36
          || referenceResidual.scale() > 36)
        throw new IllegalArgumentException("Repayment FX capture");
    }

    public Journal.CanonicalRequest canonical() {
      var fields = new TreeMap<>(repayment.request().fields());
      fields.put("fx_quote", fx.id().toString());
      fields.put("fx_publication", fx.publication().toString());
      fields.put("fx_pricing_version", Integer.toString(fx.pricingVersion()));
      fields.put("fx_debit", Long.toString(fx.debit().minorUnits()));
      fields.put("fx_fee", Long.toString(fx.fee().minorUnits()));
      fields.put("fx_source", fx.pair().source().name());
      fields.put("captured", Long.toString(capturedAt));
      fields.put("denomination_revision", Long.toString(denominationRevision));
      fields.put("fx_reference", reference.name());
      fields.put("fx_residual", referenceResidual.toPlainString());
      fields.put("fx_pricing", fx.pricing() == null ? "" : fx.pricing().id().toString());
      fields.put("funding_provider", fundingProvider == null ? "" : fundingProvider.bankKey());
      fields.put(
          "funding_provider_revision",
          fundingProvider == null ? "" : Long.toString(fundingProvider.revision()));
      return new Journal.CanonicalRequest(1, fields);
    }
  }

  private CreditRepaymentFx() {}

  public static Quote capture(
      JournalEngine.ReadView reads,
      PartyRef owner,
      CreditFacilities.Facility facility,
      AccountIdentity funding,
      long amount,
      FxQuotes.Quote fx,
      long now) {
    if (!facility.party().equals(owner)
        || !funding.owner().equals(owner)
        || funding.status() != AccountIdentity.Status.OPEN
        || funding.purpose() != AccountIdentity.Purpose.BANK
        || funding.currency() == facility.currency()
        || amount <= 0
        || amount > facility.owedMinor()
        || now < facility.lastDay()
        || now >= facility.nextDay()
        || now >= facility.nextStatement()
        || facility.status() == CreditFacilities.Status.DEFAULTED)
      throw new IllegalArgumentException("CREDIT_REPAY_CAPTURE");
    var player = ((PartyRef.Player) owner.owner()).id();
    if (fx == null
        || !fx.actor().equals(player)
        || !fx.world().equals(owner.world())
        || fx.side() != FxQuotes.Side.EXACT_CREDIT
        || fx.pair().source() != funding.currency()
        || fx.pair().target() != facility.currency()
        || !fx.requested().equals(new Money(facility.currency(), amount))
        || !fx.credit().equals(new Money(facility.currency(), amount))
        || fx.debit().currency() != funding.currency()
        || fx.debit().minorUnits() <= 0
        || fx.fee().currency() != funding.currency()
        || fx.fee().minorUnits() < 0) throw new IllegalArgumentException("CREDIT_FX_QUOTE");
    var provider = BankProviders.find(reads, owner, funding.id()).orElse(null);
    validatePricing(fx, provider, owner);
    var publication = CreditTreasury.publication(reads, now);
    if (publication == null || !publication.updateId().equals(fx.publication()))
      throw new IllegalArgumentException("CREDIT_FX_PUBLICATION");
    var denom = RebaseSettlement.denomination(reads).orElseThrow();
    long expires =
        Math.min(
            Math.min(Math.addExact(now, 30_000), fx.expires().toEpochMilli()),
            Math.min(facility.nextDay(), facility.nextStatement()));
    if (expires <= now) throw new IllegalArgumentException("CREDIT_PAYOFF_STALE");
    BigDecimal debitValue =
        BigDecimal.valueOf(fx.debit().minorUnits())
            .multiply(publication.rates().get(funding.currency()))
            .divide(BigDecimal.valueOf(funding.currency().minorUnitsPerMajor()));
    BigDecimal creditValue =
        BigDecimal.valueOf(amount)
            .multiply(publication.rates().get(facility.currency()))
            .divide(BigDecimal.valueOf(facility.currency().minorUnitsPerMajor()));
    var request =
        new CreditFacilities.RepaymentRequest(
            owner.world(),
            player,
            facility.id(),
            funding.id(),
            amount,
            facility.revision(),
            funding.revision(),
            WealthValuation.ledgerRevision(reads, funding.id().value()),
            UUID.randomUUID(),
            expires);
    return new Quote(
        request,
        fx,
        now,
        denom.revision(),
        provider,
        publication.reference(),
        debitValue.subtract(creditValue));
  }

  private static void validatePricing(
      FxQuotes.Quote fx, BankProviders.Binding provider, PartyRef owner) {
    boolean assigned =
        provider != null && !provider.bankKey().equals(BankProviders.UNASSIGNED_PROVIDER);
    var p = fx.pricing();
    if (assigned && (p == null || !p.institution().key().equals(provider.bankKey())))
      throw new IllegalArgumentException("FX_PRICING_REQUIRED");
    if (p == null) {
      if (fx.fee().minorUnits() != 0) throw new IllegalArgumentException("FX_PRICING_REQUIRED");
    } else if (!p.world().equals(owner.world())
        || !p.owner().equals(((PartyRef.Player) owner.owner()).id())
        || !p.principal().equals(fx.debit())
        || !p.fee().equals(fx.fee())
        || (p.service() != ServicePricing.Service.FX
            && p.service() != ServicePricing.Service.PROTECTED_WITHDRAWAL_FX
            && p.service() != ServicePricing.Service.PROTECTED_CLOSURE_FX))
      throw new IllegalArgumentException("FX_PRICING_REQUIRED");
  }

  public static JournalEngine.Registration registration(
      PartyRef owner, Quote quote, FxSettlement.QuoteReader requireFresh, LongSupplier clock) {
    Objects.requireNonNull(requireFresh);
    Objects.requireNonNull(clock);
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          var q = quote.repayment();
          if (!(owner.owner() instanceof PartyRef.Player p)
              || !owner.world().equals(q.world())
              || !p.id().equals(q.owner())
              || !intent.actor().equals(p.id())
              || !intent.kind().equals(CreditFacilities.REPAY)
              || !intent.request().equals(quote.canonical())) return reject("CREDIT_REPAY_REQUEST");
          long now = clock.getAsLong();
          if (now < quote.capturedAt()
              || now >= q.expiresAt()
              || q.expiresAt() > Math.addExact(quote.capturedAt(), 30_000))
            return reject("CREDIT_PAYOFF_STALE");
          var f =
              CreditFacilities.find(reads, q.facility())
                  .filter(v -> v.party().equals(owner))
                  .orElse(null);
          if (f == null || f.revision() != q.facilityRevision())
            return reject("CREDIT_FACILITY_STALE");
          if (f.status() == CreditFacilities.Status.DEFAULTED)
            return decline("CREDIT_COLLECTION_PAYMENT_REQUIRED");
          if (now >= f.nextDay() || now >= f.nextStatement())
            return decline("CREDIT_CATCHUP_REQUIRED");
          var funding = AccountCatalog.find(reads, owner, q.funding()).orElse(null);
          var denom = RebaseSettlement.denomination(reads).orElse(null);
          if (funding == null
              || funding.status() != AccountIdentity.Status.OPEN
              || funding.purpose() != AccountIdentity.Purpose.BANK
              || funding.revision() != q.fundingRevision()
              || funding.currency() != quote.fx().pair().source()
              || WealthValuation.ledgerRevision(reads, funding.id().value())
                  != q.fundingLedgerRevision()
              || denom == null
              || denom.revision() != quote.denominationRevision()
              || !Objects.equals(
                  BankProviders.find(reads, owner, q.funding()).orElse(null),
                  quote.fundingProvider())) return reject("CREDIT_FUNDING_STALE");
          FxQuotes.Quote fx;
          try {
            fx = requireFresh.requireFresh(quote.fx().id(), p.id(), owner.world(), reads);
            if (!quote.fx().equals(fx)) return reject("CREDIT_FX_STALE");
            validatePricing(fx, quote.fundingProvider(), owner);
          } catch (IllegalArgumentException stale) {
            return reject("CREDIT_FX_STALE");
          }
          if (!reads
              .query(
                  "SELECT quote_id FROM ledger_fx_quotes WHERE quote_id=?",
                  List.of(fx.id().toString()),
                  1)
              .isEmpty()) return reject("FX_QUOTE_CONSUMED");
          if (fx.side() != FxQuotes.Side.EXACT_CREDIT
              || fx.pair().target() != f.currency()
              || fx.credit().minorUnits() != q.amountMinor()
              || !fx.world().equals(owner.world())
              || !fx.actor().equals(p.id())
              || now >= fx.expires().toEpochMilli()) return reject("CREDIT_FX_STALE");
          var from = fx.pair().source();
          var to = f.currency();
          var legs =
              List.of(
                  leg(
                      funding.id().value(),
                      PostingBatch.Role.ACCOUNT,
                      from,
                      -fx.debit().minorUnits()),
                  leg(
                      Settlement.clearingAccount(from),
                      PostingBatch.Role.CLEARING,
                      from,
                      fx.debit().minorUnits()),
                  leg(
                      Settlement.clearingAccount(to),
                      PostingBatch.Role.CLEARING,
                      to,
                      -q.amountMinor()));
          var decision = CreditFacilities.repay(reads, f, q.amountMinor(), now, legs, List.of());
          if (!(decision instanceof JournalEngine.Commit base)) return decision;
          if (fx.pricing() != null) {
            decision =
                FeeSettlement.composeCash(
                    reads,
                    base,
                    List.of(
                        new CashPricing.Quote(
                            fx.pricing(),
                            fx.debit().minorUnits(),
                            fx.fee(),
                            funding.id().value())));
            if (!(decision instanceof JournalEngine.Commit priced)) return decision;
            base = priced;
          }
          var records = new ArrayList<>(base.records());
          records.add(
              new Journal.Record(
                  FxSettlement.FACT,
                  3,
                  String.join(
                      "|",
                      fx.id().toString(),
                      fx.publication().toString(),
                      Integer.toString(fx.pricingVersion()),
                      from.name(),
                      to.name(),
                      fx.side().name(),
                      Long.toString(fx.debit().minorUnits()),
                      Long.toString(fx.fee().minorUnits()),
                      Long.toString(q.amountMinor()),
                      Long.toString(q.amountMinor()))));
          records.add(
              new Journal.Record(
                  FACT,
                  1,
                  String.join(
                      "|",
                      q.quote().toString(),
                      q.world().value().toString(),
                      q.owner().value().toString(),
                      q.facility().toString(),
                      q.funding().value().toString(),
                      fx.id().toString(),
                      Long.toString(q.amountMinor()),
                      Long.toString(q.facilityRevision()),
                      Long.toString(q.fundingRevision()),
                      Long.toString(q.fundingLedgerRevision()),
                      Long.toString(quote.capturedAt()),
                      Long.toString(q.expiresAt()),
                      quote.reference().name(),
                      quote.referenceResidual().toPlainString())));
          var financial = base;
          return new JournalEngine.Commit(
              records,
              base.result(),
              tx -> {
                financial.projections().apply(tx);
                tx.execute(
                    "INSERT INTO ledger_fx_quotes(quote_id) VALUES(?)",
                    List.of(fx.id().toString()));
              });
        });
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog.with(new RecordCatalog.Key(FACT, 1), CreditRepaymentFx::validate);
  }

  private static void validate(String payload) {
    var p = payload.split("\\|", -1);
    if (p.length != 14) throw new IllegalArgumentException("Credit repayment FX fields");
    for (int i = 0; i < 6; i++)
      if (!UUID.fromString(p[i]).toString().equals(p[i]))
        throw new IllegalArgumentException("Credit repayment FX UUID");
    for (int i = 6; i < 12; i++)
      if (Long.parseLong(p[i]) < (i == 6 || i == 7 || i == 8 || i == 11 ? 1 : 0))
        throw new IllegalArgumentException("Credit repayment FX value");
    if (Long.parseLong(p[11]) <= Long.parseLong(p[10])
        || Long.parseLong(p[11]) - Long.parseLong(p[10]) > 30_000)
      throw new IllegalArgumentException("Credit repayment FX expiry");
    EconomyCurrency.valueOf(p[12]);
    var residual = new BigDecimal(p[13]);
    if (residual.signum() < 0
        || residual.precision() > 64
        || residual.scale() < -36
        || residual.scale() > 36)
      throw new IllegalArgumentException("Credit repayment FX residual");
  }

  private static PostingBatch.Leg leg(
      UUID id, PostingBatch.Role role, EconomyCurrency c, long amount) {
    return new PostingBatch.Leg(id, role, c, new Money(c, amount));
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }

  private static JournalEngine.Decline decline(String code) {
    return new JournalEngine.Decline(new Journal.Result(1, code, ""));
  }
}
