package ca.emilybelnavis.mcmods.economy.loan;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.credit.CreditTreasury;
import ca.emilybelnavis.mcmods.economy.fx.FxFacts;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.ServicePricing;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Explicit lender-currency disbursement into an owned foreign BANK account, without an extra
 * account slot.
 */
public final class LoanDisbursementFx {
  public static final String FACT = "economy:a6/loan-disbursement-fx";

  public record Quote(
      UUID id,
      Loans.Application application,
      FxFacts.Publication fx,
      ServicePricing.Quote pricing,
      EconomyCurrency destinationCurrency,
      long destinationLedgerRevision,
      long creditedMinor,
      long feeFundingMinor,
      long capturedAt,
      long expiresAt) {
    public Quote {
      Objects.requireNonNull(id);
      Objects.requireNonNull(application);
      Objects.requireNonNull(fx);
      Objects.requireNonNull(pricing);
      Objects.requireNonNull(destinationCurrency);
      if (destinationLedgerRevision < 0
          || creditedMinor <= feeFundingMinor
          || feeFundingMinor < 0
          || capturedAt < 0
          || expiresAt <= capturedAt) throw new IllegalArgumentException("Loan disbursement quote");
    }

    public Journal.CanonicalRequest canonical() {
      var fields = new TreeMap<>(application.request().fields());
      fields.put("disbursement_fx", id.toString());
      fields.put("disbursement_pricing", pricing.id().toString());
      fields.put("destination_currency", destinationCurrency.name());
      fields.put("destination_ledger", Long.toString(destinationLedgerRevision));
      fields.put("destination_credit", Long.toString(creditedMinor));
      fields.put("fx_fee_funding", Long.toString(feeFundingMinor));
      fields.put("disbursement_captured", Long.toString(capturedAt));
      fields.put("disbursement_expires", Long.toString(expiresAt));
      return new Journal.CanonicalRequest(application.request().version(), fields);
    }
  }

  private LoanDisbursementFx() {}

  public static Quote capture(
      JournalEngine.ReadView reads,
      PartyRef owner,
      Loans.Application application,
      ServicePricing book,
      long now) {
    if (!(owner.owner() instanceof PartyRef.Player p)
        || !application.proof().context().world().equals(owner.world())
        || !application.proof().context().actor().equals(p.id()))
      throw new IllegalArgumentException("Loan disbursement owner");
    var destination = AccountCatalog.find(reads, owner, application.destination()).orElseThrow();
    if (destination.status() != AccountIdentity.Status.OPEN
        || destination.purpose() != AccountIdentity.Purpose.BANK
        || destination.currency() == application.currency()
        || destination.revision() != application.destinationRevision())
      throw new IllegalArgumentException("Loan disbursement destination");
    var fx = CreditTreasury.publication(reads, now);
    if (fx == null || !fx.updateId().equals(application.fxPublication()))
      throw new IllegalArgumentException("Loan disbursement FX stale");
    var lender =
        application.product()
                == ca.emilybelnavis.mcmods.economy.credit.CreditUnderwriting.Product.SUBPRIME_LOAN
            ? InstitutionCatalog.IRONBRIDGE
            : application.proof().context().bank();
    var expiry = Instant.ofEpochMilli(now).plusSeconds(60);
    var pricing =
        book.quote(
            reads,
            owner.world(),
            p.id(),
            lender,
            ServicePricing.Service.FX,
            new Money(application.currency(), application.principalMinor()),
            Instant.ofEpochMilli(now),
            expiry);
    long credited =
        convert(
            application.principalMinor(),
            application.currency(),
            destination.currency(),
            fx,
            RoundingMode.FLOOR);
    long fee =
        convert(
            pricing.fee().minorUnits(),
            application.currency(),
            destination.currency(),
            fx,
            RoundingMode.CEILING);
    return new Quote(
        UUID.randomUUID(),
        application,
        fx,
        pricing,
        destination.currency(),
        ca.emilybelnavis.mcmods.economy.pricing.WealthValuation.ledgerRevision(
            reads, destination.id().value()),
        credited,
        fee,
        now,
        pricing.expires().toEpochMilli());
  }

  public static JournalEngine.Registration application(
      PartyRef owner,
      Quote quote,
      BankProviders.TierEligibility tiers,
      Loans.InputSource inputs,
      ServicePricing pricing,
      LongSupplier clock) {
    var core =
        Loans.application(
            owner,
            quote.application(),
            tiers,
            inputs,
            clock,
            (intent, reads, request, lender, now) ->
                plan(reads, owner, quote, pricing, lender, now));
    return new JournalEngine.Registration(
        quote.canonical().version(),
        (intent, reads) -> {
          if (!intent.kind().equals(Loans.APPLY) || !intent.request().equals(quote.canonical()))
            return new JournalEngine.Reject(new Journal.Result(1, "LOAN_DISBURSEMENT_REQUEST", ""));
          var adapted =
              new Journal.Intent(
                  intent.operationId(),
                  intent.requestId(),
                  intent.actor(),
                  intent.kind(),
                  quote.application().request(),
                  intent.economicUpdateId(),
                  intent.correctionOf());
          try {
            return core.handler().prepare(adapted, reads);
          } catch (IllegalArgumentException stale) {
            return new JournalEngine.Reject(new Journal.Result(1, "LOAN_DISBURSEMENT_STALE", ""));
          }
        });
  }

  private static Loans.DisbursementPlan plan(
      JournalEngine.ReadView reads,
      PartyRef owner,
      Quote quote,
      ServicePricing book,
      InstitutionCatalog.InstitutionId lender,
      long now) {
    if (now < quote.capturedAt()
        || now >= quote.expiresAt()
        || quote.expiresAt() > quote.capturedAt() + 60_000L)
      throw new IllegalArgumentException("LOAN_DISBURSEMENT_STALE");
    var request = quote.application();
    var destination = AccountCatalog.find(reads, owner, request.destination()).orElseThrow();
    var fx = CreditTreasury.publication(reads, now);
    if (!quote.fx().equals(fx)
        || !fx.updateId().equals(request.fxPublication())
        || destination.currency() != quote.destinationCurrency()
        || destination.revision() != request.destinationRevision()
        || ca.emilybelnavis.mcmods.economy.pricing.WealthValuation.ledgerRevision(
                reads, destination.id().value())
            != quote.destinationLedgerRevision())
      throw new IllegalArgumentException("LOAN_DISBURSEMENT_STALE");
    var player = ((PartyRef.Player) owner.owner()).id();
    var pricing =
        book.requireFresh(quote.pricing(), reads, owner.world(), player, Instant.ofEpochMilli(now));
    if (!pricing.institution().equals(lender)
        || pricing.service() != ServicePricing.Service.FX
        || !pricing.principal().equals(new Money(request.currency(), request.principalMinor())))
      throw new IllegalArgumentException("LOAN_DISBURSEMENT_PRICING");
    if (!reads
        .query(
            "SELECT quote_id FROM ledger_fx_quotes WHERE quote_id=?",
            List.of(quote.id().toString()),
            1)
        .isEmpty()) throw new IllegalArgumentException("LOAN_DISBURSEMENT_USED");
    var source = request.currency();
    var target = destination.currency();
    long amount = request.principalMinor(), fee = pricing.fee().minorUnits();
    if (convert(amount, source, target, fx, RoundingMode.FLOOR) != quote.creditedMinor()
        || convert(fee, source, target, fx, RoundingMode.CEILING) != quote.feeFundingMinor())
      throw new IllegalArgumentException("LOAN_DISBURSEMENT_AMOUNT");
    var reserve =
        FinancialAccounts.account(
            owner.world(), lender, FinancialAccounts.Role.RESERVATION, source);
    var cash =
        FinancialAccounts.account(owner.world(), lender, FinancialAccounts.Role.CASH, source);
    var income =
        FinancialAccounts.account(owner.world(), lender, FinancialAccounts.Role.INCOME, source);
    var borrower = new InstitutionCatalog.InstitutionId("economy:loan-borrower/" + player.value());
    var expense =
        FinancialAccounts.account(owner.world(), borrower, FinancialAccounts.Role.EXPENSE, source);
    var rounding =
        FinancialAccounts.account(
            owner.world(), lender, FinancialAccounts.Role.ROUNDING, fx.reference());
    var accounts = new ArrayList<>(List.of(reserve, cash, rounding));
    var legs = new ArrayList<PostingBatch.Leg>();
    legs.add(financial(reserve, -amount));
    legs.add(clearing(source, amount));
    legs.add(clearing(target, -quote.creditedMinor()));
    legs.add(customer(destination.id(), target, quote.creditedMinor()));
    if (fee > 0) {
      // The ordinary source-currency FX fee is paid by an explicitly quoted target-account
      // conversion.
      // Principal converts in full; no fee is added to the contractual loan balance.
      accounts.add(income);
      accounts.add(expense);
      legs.add(customer(destination.id(), target, -quote.feeFundingMinor()));
      legs.add(clearing(target, quote.feeFundingMinor()));
      legs.add(clearing(source, -fee));
      legs.add(financial(cash, fee));
      legs.add(financial(expense, fee));
      legs.add(financial(income, -fee));
    }
    var principalResidual =
        value(amount, source, fx).subtract(value(quote.creditedMinor(), target, fx));
    var feeResidual = value(quote.feeFundingMinor(), target, fx).subtract(value(fee, source, fx));
    var facts =
        List.of(
            ServicePricing.fact(pricing),
            new Journal.Record(
                FACT,
                1,
                String.join(
                    "|",
                    quote.id().toString(),
                    owner.world().value().toString(),
                    request.loan().toString(),
                    destination.id().value().toString(),
                    fx.updateId().toString(),
                    source.name(),
                    Long.toString(amount),
                    target.name(),
                    Long.toString(quote.creditedMinor()),
                    Long.toString(fee),
                    Long.toString(quote.feeFundingMinor()),
                    fx.reference().name(),
                    principalResidual.toPlainString(),
                    feeResidual.toPlainString(),
                    rounding.id().toString())));
    return new Loans.DisbursementPlan(
        legs,
        accounts,
        facts,
        tx ->
            tx.execute(
                "INSERT INTO ledger_fx_quotes(quote_id) VALUES(?)",
                List.of(quote.id().toString())));
  }

  private static long convert(
      long minor,
      EconomyCurrency source,
      EconomyCurrency target,
      FxFacts.Publication fx,
      RoundingMode rounding) {
    if (minor == 0) return 0;
    return BigDecimal.valueOf(minor)
        .multiply(fx.rates().get(source))
        .multiply(BigDecimal.valueOf(target.minorUnitsPerMajor()))
        .divide(
            fx.rates().get(target).multiply(BigDecimal.valueOf(source.minorUnitsPerMajor())),
            0,
            rounding)
        .longValueExact();
  }

  private static BigDecimal value(long minor, EconomyCurrency currency, FxFacts.Publication fx) {
    return BigDecimal.valueOf(minor)
        .multiply(fx.rates().get(currency))
        .divide(BigDecimal.valueOf(currency.minorUnitsPerMajor()));
  }

  private static PostingBatch.Leg financial(FinancialAccounts.Account a, long minor) {
    return new PostingBatch.Leg(
        a.id(),
        a.role() == FinancialAccounts.Role.CASH
            ? PostingBatch.Role.INSTITUTION_CASH
            : PostingBatch.Role.INSTITUTION_CLAIM,
        a.currency(),
        new Money(a.currency(), minor));
  }

  private static PostingBatch.Leg clearing(EconomyCurrency c, long minor) {
    return new PostingBatch.Leg(
        Settlement.clearingAccount(c), PostingBatch.Role.CLEARING, c, new Money(c, minor));
  }

  private static PostingBatch.Leg customer(AccountId a, EconomyCurrency c, long minor) {
    return new PostingBatch.Leg(a.value(), PostingBatch.Role.ACCOUNT, c, new Money(c, minor));
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog.with(new RecordCatalog.Key(FACT, 1), LoanDisbursementFx::validate);
  }

  private static void validate(String payload) {
    var p = payload.split("\\|", -1);
    if (p.length != 15) throw new IllegalArgumentException("Loan disbursement fields");
    for (int i : new int[] {0, 1, 2, 3, 4, 14}) UUID.fromString(p[i]);
    for (int i : new int[] {5, 7, 11}) EconomyCurrency.valueOf(p[i]);
    for (int i : new int[] {6, 8})
      if (Long.parseLong(p[i]) <= 0)
        throw new IllegalArgumentException("Loan disbursement principal");
    for (int i : new int[] {9, 10})
      if (Long.parseLong(p[i]) < 0) throw new IllegalArgumentException("Loan disbursement fee");
    for (int i : new int[] {12, 13})
      if (new BigDecimal(p[i]).signum() < 0 || new BigDecimal(p[i]).precision() > 64)
        throw new IllegalArgumentException("Loan disbursement residual");
  }
}
