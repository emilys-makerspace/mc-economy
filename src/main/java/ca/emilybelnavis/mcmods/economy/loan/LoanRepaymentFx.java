package ca.emilybelnavis.mcmods.economy.loan;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.credit.CreditTreasury;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.BankProviders;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import java.math.*;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Exact-credit loan payments; source fees never satisfy debt or create a temporary BANK account.
 */
public final class LoanRepaymentFx {
  public static final String FACT = "economy:a6/loan-repayment-fx";

  public record Quote(
      Loans.PaymentQuote payment,
      FxQuotes.Quote fx,
      long denominationRevision,
      BankProviders.Binding fundingProvider,
      EconomyCurrency reference,
      BigDecimal referenceResidual) {
    public Quote {
      Objects.requireNonNull(payment);
      Objects.requireNonNull(fx);
      Objects.requireNonNull(reference);
      Objects.requireNonNull(referenceResidual);
      if (denominationRevision < 0
          || referenceResidual.precision() > 64
          || referenceResidual.scale() < -36
          || referenceResidual.scale() > 36) throw new IllegalArgumentException("Loan FX capture");
    }

    public Journal.CanonicalRequest canonical() {
      var fields = new TreeMap<>(payment.request().request().fields());
      fields.put("fx_quote", fx.id().toString());
      fields.put("fx_publication", fx.publication().toString());
      fields.put("fx_pricing_version", Integer.toString(fx.pricingVersion()));
      fields.put("fx_debit", Long.toString(fx.debit().minorUnits()));
      fields.put("fx_fee", Long.toString(fx.fee().minorUnits()));
      fields.put("fx_source", fx.pair().source().name());
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

  private LoanRepaymentFx() {}

  /**
   * Pass the original worker-captured loan quote so successful full payoff retains its frozen
   * instant.
   */
  public static Quote capture(
      JournalEngine.ReadView reads,
      PartyRef owner,
      Loans.PaymentQuote payment,
      FxQuotes.Quote fx,
      long now) {
    var loan = Loans.validatePayment(reads, owner, payment, now);
    if (loan == null) throw new IllegalArgumentException("LOAN_PAYMENT_STALE");
    var q = payment.request();
    var funding = AccountCatalog.find(reads, owner, q.funding()).orElseThrow();
    if (funding.currency() == loan.currency() || q.amountMinor() <= 0)
      throw new IllegalArgumentException("LOAN_EXPLICIT_FX_REQUIRED");
    if (!q.fullPayoff()
        && q.amountMinor() > LoanServicing.payoff(loan.servicing(), now).amountMinor())
      throw new IllegalArgumentException("LOAN_FRESH_PAYOFF_REQUIRED");
    var player = ((PartyRef.Player) owner.owner()).id();
    if (fx == null
        || !fx.actor().equals(player)
        || !fx.world().equals(owner.world())
        || fx.side() != FxQuotes.Side.EXACT_CREDIT
        || fx.pair().source() != funding.currency()
        || fx.pair().target() != loan.currency()
        || !fx.requested().equals(new Money(loan.currency(), q.amountMinor()))
        || !fx.credit().equals(new Money(loan.currency(), q.amountMinor()))
        || fx.debit().currency() != funding.currency()
        || fx.debit().minorUnits() <= 0
        || fx.fee().currency() != funding.currency()
        || fx.fee().minorUnits() < 0) throw new IllegalArgumentException("LOAN_FX_QUOTE");
    var provider = BankProviders.find(reads, owner, q.funding()).orElse(null);
    validatePricing(fx, provider, owner);
    var publication = CreditTreasury.publication(reads, now);
    if (publication == null || !publication.updateId().equals(fx.publication()))
      throw new IllegalArgumentException("LOAN_FX_PUBLICATION");
    var denomination = RebaseSettlement.denomination(reads).orElseThrow();
    long expires = Math.min(q.expiresAt(), fx.expires().toEpochMilli());
    if (expires <= now) throw new IllegalArgumentException("LOAN_PAYMENT_STALE");
    var request =
        new Loans.RepaymentRequest(
            q.world(),
            q.owner(),
            q.loan(),
            q.funding(),
            q.amountMinor(),
            q.loanRevision(),
            q.fundingRevision(),
            q.fundingLedgerRevision(),
            q.quote(),
            q.capturedAt(),
            expires,
            q.fullPayoff());
    var captured = new Loans.PaymentQuote(request, payment.payoff());
    BigDecimal debit =
        BigDecimal.valueOf(fx.debit().minorUnits())
            .multiply(publication.rates().get(funding.currency()))
            .divide(BigDecimal.valueOf(funding.currency().minorUnitsPerMajor()));
    BigDecimal credit =
        BigDecimal.valueOf(q.amountMinor())
            .multiply(publication.rates().get(loan.currency()))
            .divide(BigDecimal.valueOf(loan.currency().minorUnitsPerMajor()));
    return new Quote(
        captured,
        fx,
        denomination.revision(),
        provider,
        publication.reference(),
        debit.subtract(credit));
  }

  private static void validatePricing(
      FxQuotes.Quote fx, BankProviders.Binding provider, PartyRef owner) {
    boolean assigned =
        provider != null && !provider.bankKey().equals(BankProviders.UNASSIGNED_PROVIDER);
    var price = fx.pricing();
    if (assigned && (price == null || !price.institution().key().equals(provider.bankKey())))
      throw new IllegalArgumentException("FX_PRICING_REQUIRED");
    if (price == null) {
      if (fx.fee().minorUnits() != 0) throw new IllegalArgumentException("FX_PRICING_REQUIRED");
    } else if (!price.world().equals(owner.world())
        || !price.owner().equals(((PartyRef.Player) owner.owner()).id())
        || !price.principal().equals(fx.debit())
        || !price.fee().equals(fx.fee())
        || !Set.of(
                ServicePricing.Service.FX,
                ServicePricing.Service.PROTECTED_WITHDRAWAL_FX,
                ServicePricing.Service.PROTECTED_CLOSURE_FX)
            .contains(price.service())) throw new IllegalArgumentException("FX_PRICING_REQUIRED");
  }

  public static JournalEngine.Registration registration(
      PartyRef owner, Quote quote, FxSettlement.QuoteReader requireFresh, LongSupplier clock) {
    Objects.requireNonNull(requireFresh);
    Objects.requireNonNull(clock);
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          var q = quote.payment().request();
          if (!(owner.owner() instanceof PartyRef.Player p)
              || !owner.world().equals(q.world())
              || !p.id().equals(q.owner())
              || !intent.actor().equals(p.id())
              || !intent.kind().equals(Loans.REPAY)
              || !intent.request().equals(quote.canonical())) return reject("LOAN_REPAY_REQUEST");
          long now = clock.getAsLong();
          var loan = Loans.validatePayment(reads, owner, quote.payment(), now);
          if (loan == null) return reject("LOAN_PAYMENT_STALE");
          var funding = AccountCatalog.find(reads, owner, q.funding()).orElse(null);
          var denomination = RebaseSettlement.denomination(reads).orElse(null);
          if (funding == null
              || funding.currency() != quote.fx().pair().source()
              || denomination == null
              || denomination.revision() != quote.denominationRevision()
              || !Objects.equals(
                  BankProviders.find(reads, owner, q.funding()).orElse(null),
                  quote.fundingProvider())) return reject("LOAN_FUNDING_STALE");
          FxQuotes.Quote fx;
          try {
            fx = requireFresh.requireFresh(quote.fx().id(), p.id(), owner.world(), reads);
            if (!quote.fx().equals(fx)) return reject("LOAN_FX_STALE");
            validatePricing(fx, quote.fundingProvider(), owner);
          } catch (IllegalArgumentException stale) {
            return reject("LOAN_FX_STALE");
          }
          if (!reads
              .query(
                  "SELECT quote_id FROM ledger_fx_quotes WHERE quote_id=?",
                  List.of(fx.id().toString()),
                  1)
              .isEmpty()) return reject("FX_QUOTE_CONSUMED");
          if (fx.side() != FxQuotes.Side.EXACT_CREDIT
              || fx.pair().target() != loan.currency()
              || fx.credit().minorUnits() != q.amountMinor()
              || !fx.actor().equals(p.id())
              || !fx.world().equals(owner.world())
              || now >= fx.expires().toEpochMilli()) return reject("LOAN_FX_STALE");
          var from = funding.currency();
          var to = loan.currency();
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
          var decision = Loans.planRepayment(reads, loan, quote.payment(), now, legs, List.of());
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
                  2,
                  String.join(
                      "|",
                      q.quote().toString(),
                      q.world().value().toString(),
                      q.owner().value().toString(),
                      q.loan().toString(),
                      q.funding().value().toString(),
                      fx.id().toString(),
                      Long.toString(q.amountMinor()),
                      Long.toString(q.loanRevision()),
                      Long.toString(q.fundingRevision()),
                      Long.toString(q.fundingLedgerRevision()),
                      Long.toString(q.capturedAt()),
                      Long.toString(q.expiresAt()),
                      quote.reference().name(),
                      quote.referenceResidual().toPlainString(),
                      Boolean.toString(q.fullPayoff()))));
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
    return catalog
        .with(new RecordCatalog.Key(FACT, 1), LoanRepaymentFx::validate)
        .with(new RecordCatalog.Key(FACT, 2), payload -> validate(payload, true));
  }

  public static void validate(String payload) {
    validate(payload, false);
  }

  private static void validate(String payload, boolean signedResidual) {
    var p = payload.split("\\|", -1);
    if (p.length != 15) throw new IllegalArgumentException("Loan FX fields");
    for (int i = 0; i < 6; i++)
      if (!UUID.fromString(p[i]).toString().equals(p[i]))
        throw new IllegalArgumentException("Loan FX UUID");
    for (int i = 6; i < 12; i++) {
      long value = Long.parseLong(p[i]);
      if (value < (i == 6 || i == 7 || i == 8 || i == 11 ? 1 : 0)
          || !Long.toString(value).equals(p[i]))
        throw new IllegalArgumentException("Loan FX value");
    }
    if (Long.parseLong(p[11]) <= Long.parseLong(p[10])
        || Long.parseLong(p[11]) - Long.parseLong(p[10]) > 30000)
      throw new IllegalArgumentException("Loan FX expiry");
    EconomyCurrency.valueOf(p[12]);
    var residual = new BigDecimal(p[13]);
    if ((!signedResidual && residual.signum() < 0)
        || residual.precision() > 64
        || residual.scale() < -36
        || residual.scale() > 36
        || !residual.toPlainString().equals(p[13])
        || !Set.of("true", "false").contains(p[14]))
      throw new IllegalArgumentException("Loan FX residual/mode");
  }

  private static PostingBatch.Leg leg(
      UUID id, PostingBatch.Role role, EconomyCurrency c, long amount) {
    return new PostingBatch.Leg(id, role, c, new Money(c, amount));
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }
}
