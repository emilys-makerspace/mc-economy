package ca.emilybelnavis.mcmods.economy.debt;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.credit.CreditFacilities;
import ca.emilybelnavis.mcmods.economy.credit.CreditTreasury;
import ca.emilybelnavis.mcmods.economy.fx.FxQuotes;
import ca.emilybelnavis.mcmods.economy.institution.BankProviders;
import ca.emilybelnavis.mcmods.economy.journal.FeeSettlement;
import ca.emilybelnavis.mcmods.economy.journal.FxSettlement;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.journal.PostingBatch;
import ca.emilybelnavis.mcmods.economy.journal.RebaseSettlement;
import ca.emilybelnavis.mcmods.economy.journal.Settlement;
import ca.emilybelnavis.mcmods.economy.loan.Loans;
import ca.emilybelnavis.mcmods.economy.pricing.CashPricing;
import ca.emilybelnavis.mcmods.economy.pricing.ServicePricing;
import ca.emilybelnavis.mcmods.economy.pricing.WealthValuation;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Protected collection quotes retain their capture window and payments use their commit instant.
 */
public final class DebtPayments {
  public static final Journal.Kind PAY = new Journal.Kind("economy:debt", "economy:repay");
  public static final String FACT = "economy:a6/debt-repayment-fx";
  private static final long QUOTE_WINDOW = 30_000;

  private DebtPayments() {}

  public record Quote(
      WorldId world,
      PlayerId owner,
      UUID caseId,
      AccountId funding,
      long amountMinor,
      long caseRevision,
      long fundingRevision,
      long fundingLedgerRevision,
      long capturedAt,
      long expiresAt,
      UUID id) {
    public Quote {
      Objects.requireNonNull(world);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(caseId);
      Objects.requireNonNull(funding);
      Objects.requireNonNull(id);
      if (amountMinor <= 0
          || caseRevision < 1
          || fundingRevision < 1
          || fundingLedgerRevision < 0
          || capturedAt < 0
          || expiresAt <= capturedAt
          || expiresAt > Math.addExact(capturedAt, QUOTE_WINDOW)) {
        throw new IllegalArgumentException("Collection payment quote");
      }
    }

    public Journal.CanonicalRequest request() {
      return new Journal.CanonicalRequest(
          1,
          Map.ofEntries(
              Map.entry("world", world.value().toString()),
              Map.entry("economy:owner", Journal.actorKey(owner)),
              Map.entry("case", caseId.toString()),
              Map.entry("funding", funding.value().toString()),
              Map.entry("amount", Long.toString(amountMinor)),
              Map.entry("case_revision", Long.toString(caseRevision)),
              Map.entry("funding_revision", Long.toString(fundingRevision)),
              Map.entry("funding_ledger_revision", Long.toString(fundingLedgerRevision)),
              Map.entry("captured_at", Long.toString(capturedAt)),
              Map.entry("expires_at", Long.toString(expiresAt)),
              Map.entry("quote", id.toString())));
    }
  }

  public record FxQuote(
      Quote payment,
      FxQuotes.Quote fx,
      long denominationRevision,
      BankProviders.Binding fundingProvider,
      EconomyCurrency reference,
      BigDecimal referenceResidual) {
    public FxQuote {
      Objects.requireNonNull(payment);
      Objects.requireNonNull(fx);
      Objects.requireNonNull(reference);
      Objects.requireNonNull(referenceResidual);
      if (denominationRevision < 0
          || referenceResidual.precision() > 64
          || referenceResidual.scale() < -36
          || referenceResidual.scale() > 36) {
        throw new IllegalArgumentException("Collection FX capture");
      }
    }

    public Journal.CanonicalRequest canonical() {
      var fields = new TreeMap<>(payment.request().fields());
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

  public static Quote quote(
      JournalEngine.ReadView reads,
      PartyRef owner,
      UUID caseId,
      AccountId funding,
      long amount,
      long now) {
    var actor = player(owner);
    var debt =
        DebtLifecycle.ownedCase(reads, owner, caseId)
            .orElseThrow(() -> new IllegalArgumentException("Collection case unavailable"));
    if (!payable(debt, amount, now))
      throw new IllegalArgumentException("Collection payment unavailable");
    var account =
        AccountCatalog.find(reads, owner, funding)
            .orElseThrow(() -> new IllegalArgumentException("Collection funding unavailable"));
    if (!openBank(account)) throw new IllegalArgumentException("Collection funding unavailable");
    long expires = Math.min(Math.addExact(now, QUOTE_WINDOW), debt.collectionExpires());
    var cards = CreditFacilities.owned(reads, owner);
    if (cards.size() > 128) throw new IllegalArgumentException("Credit boundary input unavailable");
    for (var card : cards) {
      if (card.status() == CreditFacilities.Status.ACTIVE)
        expires = Math.min(expires, Math.min(card.nextDay(), card.nextStatement()));
    }
    var loans = Loans.owned(reads, owner);
    if (loans.size() > Loans.MAX_LIVE)
      throw new IllegalArgumentException("Loan boundary input unavailable");
    for (var loan : loans) {
      if (loan.status() == Loans.Status.ACTIVE) expires = Math.min(expires, loan.nextMonth());
    }
    if (expires <= now) throw new IllegalArgumentException("Scheduled debt catch-up required");
    return new Quote(
        owner.world(),
        actor,
        caseId,
        funding,
        amount,
        debt.revision(),
        account.revision(),
        WealthValuation.ledgerRevision(reads, funding.value()),
        now,
        expires,
        UUID.randomUUID());
  }

  public static JournalEngine.Registration registration(
      PartyRef owner, Quote quote, LongSupplier clock) {
    Objects.requireNonNull(clock);
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          if (!matches(owner, intent, quote, quote.request()))
            return reject("DEBT_PAYMENT_REQUEST");
          long now = clock.getAsLong();
          var debt = validatePayment(reads, owner, quote, now);
          if (debt == null) return reject("DEBT_PAYMENT_STALE");
          var funding = AccountCatalog.find(reads, owner, quote.funding()).orElseThrow();
          if (funding.currency() != debt.currency()) return decline("DEBT_EXPLICIT_FX_REQUIRED");
          return DebtLifecycle.pay(
              reads,
              debt,
              quote.amountMinor(),
              now,
              List.of(
                  leg(
                      funding.id().value(),
                      PostingBatch.Role.ACCOUNT,
                      debt.currency(),
                      -quote.amountMinor())),
              List.of());
        });
  }

  public static FxQuote capture(
      JournalEngine.ReadView reads, PartyRef owner, Quote payment, FxQuotes.Quote fx, long now) {
    var debt = validatePayment(reads, owner, payment, now);
    if (debt == null) throw new IllegalArgumentException("DEBT_PAYMENT_STALE");
    var funding = AccountCatalog.find(reads, owner, payment.funding()).orElseThrow();
    if (funding.currency() == debt.currency())
      throw new IllegalArgumentException("DEBT_EXPLICIT_FX_REQUIRED");
    validateFx(fx, owner, funding.currency(), debt.currency(), payment.amountMinor());
    var provider = BankProviders.find(reads, owner, funding.id()).orElse(null);
    validatePricing(fx, provider, owner);
    var publication = CreditTreasury.publication(reads, now);
    if (publication == null || !publication.updateId().equals(fx.publication()))
      throw new IllegalArgumentException("DEBT_FX_PUBLICATION");
    var denomination = RebaseSettlement.denomination(reads).orElseThrow();
    long expires = Math.min(payment.expiresAt(), fx.expires().toEpochMilli());
    if (expires <= now) throw new IllegalArgumentException("DEBT_PAYMENT_STALE");
    var captured =
        new Quote(
            payment.world(),
            payment.owner(),
            payment.caseId(),
            payment.funding(),
            payment.amountMinor(),
            payment.caseRevision(),
            payment.fundingRevision(),
            payment.fundingLedgerRevision(),
            payment.capturedAt(),
            expires,
            payment.id());
    var debitValue =
        BigDecimal.valueOf(fx.debit().minorUnits())
            .multiply(publication.rates().get(funding.currency()))
            .divide(BigDecimal.valueOf(funding.currency().minorUnitsPerMajor()));
    var creditValue =
        BigDecimal.valueOf(payment.amountMinor())
            .multiply(publication.rates().get(debt.currency()))
            .divide(BigDecimal.valueOf(debt.currency().minorUnitsPerMajor()));
    return new FxQuote(
        captured,
        fx,
        denomination.revision(),
        provider,
        publication.reference(),
        debitValue.subtract(creditValue));
  }

  public static JournalEngine.Registration registrationFx(
      PartyRef owner, FxQuote quote, FxSettlement.QuoteReader requireFresh, LongSupplier clock) {
    Objects.requireNonNull(requireFresh);
    Objects.requireNonNull(clock);
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          var payment = quote.payment();
          if (!matches(owner, intent, payment, quote.canonical()))
            return reject("DEBT_PAYMENT_REQUEST");
          long now = clock.getAsLong();
          var debt = validatePayment(reads, owner, payment, now);
          if (debt == null) return reject("DEBT_PAYMENT_STALE");
          var funding = AccountCatalog.find(reads, owner, payment.funding()).orElseThrow();
          var denomination = RebaseSettlement.denomination(reads).orElse(null);
          if (funding.currency() != quote.fx().pair().source()
              || denomination == null
              || denomination.revision() != quote.denominationRevision()
              || !Objects.equals(
                  BankProviders.find(reads, owner, funding.id()).orElse(null),
                  quote.fundingProvider())) {
            return reject("DEBT_FUNDING_STALE");
          }
          FxQuotes.Quote fx;
          try {
            fx = requireFresh.requireFresh(quote.fx().id(), payment.owner(), owner.world(), reads);
            if (!fx.equals(quote.fx())) return reject("DEBT_FX_STALE");
            validateFx(fx, owner, funding.currency(), debt.currency(), payment.amountMinor());
            validatePricing(fx, quote.fundingProvider(), owner);
          } catch (IllegalArgumentException stale) {
            return reject("DEBT_FX_STALE");
          }
          if (now >= fx.expires().toEpochMilli()) return reject("DEBT_FX_STALE");
          if (!reads
              .query(
                  "SELECT quote_id FROM ledger_fx_quotes WHERE quote_id=?",
                  List.of(fx.id().toString()),
                  1)
              .isEmpty()) {
            return reject("FX_QUOTE_CONSUMED");
          }
          var from = funding.currency();
          var to = debt.currency();
          var fundingLegs =
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
                      -payment.amountMinor()));
          var decision =
              DebtLifecycle.pay(reads, debt, payment.amountMinor(), now, fundingLegs, List.of());
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
                      Long.toString(payment.amountMinor()),
                      Long.toString(payment.amountMinor()))));
          records.add(new Journal.Record(FACT, 2, receipt(quote)));
          if (records.size() > 64) return reject("DEBT_BATCH_LIMIT");
          var financial = base;
          return new JournalEngine.Commit(
              records,
              base.result(),
              transaction -> {
                financial.projections().apply(transaction);
                transaction.execute(
                    "INSERT INTO ledger_fx_quotes(quote_id) VALUES(?)",
                    List.of(fx.id().toString()));
              });
        });
  }

  public static DebtRecords.Case validatePayment(
      JournalEngine.ReadView reads, PartyRef owner, Quote quote, long now) {
    if (!(owner.owner() instanceof PartyRef.Player actor)
        || !owner.world().equals(quote.world())
        || !actor.id().equals(quote.owner())
        || now < quote.capturedAt()
        || now >= quote.expiresAt()) return null;
    var debt = DebtLifecycle.ownedCase(reads, owner, quote.caseId()).orElse(null);
    if (debt == null
        || debt.revision() != quote.caseRevision()
        || !payable(debt, quote.amountMinor(), now)) return null;
    var funding = AccountCatalog.find(reads, owner, quote.funding()).orElse(null);
    if (funding == null
        || !openBank(funding)
        || funding.revision() != quote.fundingRevision()
        || WealthValuation.ledgerRevision(reads, funding.id().value())
            != quote.fundingLedgerRevision()) return null;
    return debt;
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog
        .with(new RecordCatalog.Key(FACT, 1), DebtPayments::validate)
        .with(new RecordCatalog.Key(FACT, 2), payload -> validate(payload, true));
  }

  public static void validate(String payload) {
    validate(payload, false);
  }

  private static void validate(String payload, boolean signedResidual) {
    var fields = payload.split("\\|", -1);
    if (fields.length != 14) throw new IllegalArgumentException("Debt FX fields");
    for (int index = 0; index < 6; index++) {
      var id = UUID.fromString(fields[index]);
      if (!id.toString().equals(fields[index])) throw new IllegalArgumentException("Debt FX UUID");
    }
    for (int index = 6; index < 12; index++) {
      long value = Long.parseLong(fields[index]);
      long minimum = index == 6 || index == 7 || index == 8 || index == 11 ? 1 : 0;
      if (value < minimum || !Long.toString(value).equals(fields[index]))
        throw new IllegalArgumentException("Debt FX value");
    }
    long captured = Long.parseLong(fields[10]);
    long expires = Long.parseLong(fields[11]);
    if (expires <= captured || expires - captured > QUOTE_WINDOW)
      throw new IllegalArgumentException("Debt FX expiry");
    EconomyCurrency.valueOf(fields[12]);
    // Nearest-minor settlement can round either side of the exact reference value.
    var residual = new BigDecimal(fields[13]);
    if ((!signedResidual && residual.signum() < 0)
        || residual.precision() > 64
        || residual.scale() < -36
        || residual.scale() > 36
        || !residual.toPlainString().equals(fields[13]))
      throw new IllegalArgumentException("Debt FX residual");
  }

  private static void validateFx(
      FxQuotes.Quote fx,
      PartyRef owner,
      EconomyCurrency source,
      EconomyCurrency target,
      long amount) {
    if (fx == null
        || !fx.actor().equals(player(owner))
        || !fx.world().equals(owner.world())
        || fx.side() != FxQuotes.Side.EXACT_CREDIT
        || fx.pair().source() != source
        || fx.pair().target() != target
        || !fx.requested().equals(new Money(target, amount))
        || !fx.credit().equals(new Money(target, amount))
        || fx.debit().currency() != source
        || fx.debit().minorUnits() <= 0
        || fx.fee().currency() != source
        || fx.fee().minorUnits() < 0) {
      throw new IllegalArgumentException("DEBT_FX_QUOTE");
    }
  }

  private static void validatePricing(
      FxQuotes.Quote fx, BankProviders.Binding provider, PartyRef owner) {
    boolean assigned =
        provider != null && !provider.bankKey().equals(BankProviders.UNASSIGNED_PROVIDER);
    var pricing = fx.pricing();
    if (assigned && (pricing == null || !pricing.institution().key().equals(provider.bankKey()))) {
      throw new IllegalArgumentException("FX_PRICING_REQUIRED");
    }
    if (pricing == null) {
      if (fx.fee().minorUnits() != 0) throw new IllegalArgumentException("FX_PRICING_REQUIRED");
    } else if (!pricing.world().equals(owner.world())
        || !pricing.owner().equals(player(owner))
        || !pricing.principal().equals(fx.debit())
        || !pricing.fee().equals(fx.fee())
        || !Set.of(
                ServicePricing.Service.FX,
                ServicePricing.Service.PROTECTED_WITHDRAWAL_FX,
                ServicePricing.Service.PROTECTED_CLOSURE_FX)
            .contains(pricing.service())) {
      throw new IllegalArgumentException("FX_PRICING_REQUIRED");
    }
  }

  private static String receipt(FxQuote quote) {
    var payment = quote.payment();
    return String.join(
        "|",
        payment.id().toString(),
        payment.world().value().toString(),
        payment.owner().value().toString(),
        payment.caseId().toString(),
        payment.funding().value().toString(),
        quote.fx().id().toString(),
        Long.toString(payment.amountMinor()),
        Long.toString(payment.caseRevision()),
        Long.toString(payment.fundingRevision()),
        Long.toString(payment.fundingLedgerRevision()),
        Long.toString(payment.capturedAt()),
        Long.toString(payment.expiresAt()),
        quote.reference().name(),
        quote.referenceResidual().toPlainString());
  }

  private static boolean payable(DebtRecords.Case debt, long amount, long now) {
    return debt.collectability() == DebtRecords.Collectability.COLLECTIBLE
        && debt.payment() != DebtRecords.Payment.SETTLED
        && amount > 0
        && amount <= debt.debtMinor()
        && now >= debt.defaultAt()
        && now < debt.collectionExpires();
  }

  private static boolean matches(
      PartyRef owner, Journal.Intent intent, Quote quote, Journal.CanonicalRequest request) {
    return owner.owner() instanceof PartyRef.Player actor
        && owner.world().equals(quote.world())
        && actor.id().equals(quote.owner())
        && intent.actor().equals(actor.id())
        && intent.kind().equals(PAY)
        && intent.request().equals(request);
  }

  private static boolean openBank(AccountIdentity account) {
    return account.status() == AccountIdentity.Status.OPEN
        && account.purpose() == AccountIdentity.Purpose.BANK;
  }

  private static PlayerId player(PartyRef owner) {
    if (!(owner.owner() instanceof PartyRef.Player actor))
      throw new IllegalArgumentException("Collection owner required");
    return actor.id();
  }

  private static PostingBatch.Leg leg(
      UUID account, PostingBatch.Role role, EconomyCurrency currency, long amount) {
    return new PostingBatch.Leg(account, role, currency, new Money(currency, amount));
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }

  private static JournalEngine.Decline decline(String code) {
    return new JournalEngine.Decline(new Journal.Result(1, code, ""));
  }
}
