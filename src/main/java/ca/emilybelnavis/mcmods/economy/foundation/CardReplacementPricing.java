package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.card.IssuedCards;
import ca.emilybelnavis.mcmods.economy.fx.FxQuotes;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.util.*;

/**
 * Session-confined captured replacement fees; the journal alone consumes quotes and posts money.
 */
public final class CardReplacementPricing {
  public static final String FACT = "economy:card-replacement-fee";

  private record Capture(
      IssuedCards.FeeQuote quote,
      PartyRef owner,
      InstitutionCatalog.InstitutionId issuer,
      AccountId card,
      BankProviders.Binding provider,
      EconomyCurrency worldCurrency,
      long principal,
      FxQuotes.Quote fx) {}

  private final Map<UUID, Capture> captures = new HashMap<>();

  public IssuedCards.FeeQuote capture(
      JournalEngine.ReadView reads,
      PartyRef owner,
      InstitutionCatalog.InstitutionId issuer,
      AccountId card,
      AccountIdentity funding,
      FxQuotes.Quote fx,
      long now) {
    var denomination = RebaseSettlement.denomination(reads).orElseThrow();
    long amount = Math.multiplyExact(25L, denomination.currency().minorUnitsPerMajor());
    var player = ((PartyRef.Player) owner.owner()).id();
    long principal = amount, surcharge = 0, expires = Math.addExact(now, 60_000);
    if (funding.currency() != denomination.currency()) {
      if (fx == null
          || fx.side() != FxQuotes.Side.EXACT_CREDIT
          || !fx.actor().equals(player)
          || !fx.world().equals(owner.world())
          || fx.pair().source() != funding.currency()
          || fx.pair().target() != denomination.currency()
          || fx.credit().minorUnits() != amount)
        throw new IllegalArgumentException("CARD_FEE_QUOTE");
      principal = fx.debit().minorUnits();
      surcharge = fx.fee().minorUnits();
      expires = Math.min(expires, fx.expires().toEpochMilli());
    } else if (fx != null) throw new IllegalArgumentException("CARD_FEE_QUOTE");
    var provider = BankProviders.find(reads, owner, funding.id()).orElseThrow();
    var id = UUID.randomUUID();
    var capture =
        issuer.key()
            + "|"
            + denomination.currency()
            + "|"
            + amount
            + "|"
            + (fx == null ? "" : fx.id())
            + "|"
            + principal
            + "|"
            + surcharge
            + "|"
            + provider.revision()
            + "|"
            + provider.debitEpoch();
    var quote =
        new IssuedCards.FeeQuote(
            id,
            funding.id(),
            funding.revision(),
            denomination.revision(),
            funding.currency(),
            Math.addExact(principal, surcharge),
            expires,
            capture);
    captures.values().removeIf(c -> c.quote().expiresAt() <= now);
    if (captures.size() >= 1024) throw new IllegalStateException("CARD_FEE_CAPACITY");
    captures.put(
        id,
        new Capture(quote, owner, issuer, card, provider, denomination.currency(), principal, fx));
    return quote;
  }

  public JournalEngine.Decision prepare(
      Journal.Intent intent,
      IssuedCards.FeeQuote quote,
      JournalEngine.ReadView reads,
      java.util.function.Function<FxQuotes.Quote, FxQuotes.Quote> requireFx,
      long now) {
    var capture = captures.get(quote.id());
    if (capture == null
        || !capture.quote().equals(quote)
        || now >= quote.expiresAt()
        || !capture.owner().owner().equals(new PartyRef.Player((PlayerId) intent.actor()))
        || !capture.card().value().toString().equals(intent.request().fields().get("account"))
        || !capture.issuer().key().equals(intent.request().fields().get("bank")))
      return reject("CARD_FEE_STALE");
    if (!reads
        .query(
            "SELECT quote_id FROM issued_card_fee_quotes WHERE quote_id=?",
            List.of(quote.id().toString()),
            1)
        .isEmpty()) return reject("CARD_FEE_USED");
    var funding = AccountCatalog.find(reads, capture.owner(), quote.funding()).orElse(null);
    var denomination = RebaseSettlement.denomination(reads).orElse(null);
    if (funding == null
        || funding.status() != AccountIdentity.Status.OPEN
        || funding.revision() != quote.fundingRevision()
        || funding.currency() != quote.currency()
        || denomination == null
        || denomination.revision() != quote.denominationRevision()
        || denomination.currency() != capture.worldCurrency()
        || !BankProviders.find(reads, capture.owner(), funding.id())
            .filter(capture.provider()::equals)
            .isPresent()) return reject("CARD_FEE_STALE");
    var fx = capture.fx();
    if (fx != null) {
      try {
        if (!fx.equals(requireFx.apply(fx))) return reject("CARD_FEE_STALE");
      } catch (IllegalArgumentException stale) {
        return reject("CARD_FEE_STALE");
      }
      if (!reads
          .query(
              "SELECT quote_id FROM ledger_fx_quotes WHERE quote_id=?",
              List.of(fx.id().toString()),
              1)
          .isEmpty()) return reject("FX_QUOTE_USED");
    }
    var decision =
        FeeSettlement.charge(
            reads,
            capture.owner().world(),
            (PlayerId) intent.actor(),
            capture.issuer(),
            new Money(quote.currency(), capture.principal()),
            quote.funding().value());
    if (!(decision instanceof JournalEngine.Commit base)) return decision;
    if (fx != null && fx.pricing() != null) {
      decision =
          FeeSettlement.composeCash(
              reads,
              base,
              List.of(
                  new ca.emilybelnavis.mcmods.economy.pricing.CashPricing.Quote(
                      fx.pricing(), fx.debit().minorUnits(), fx.fee(), quote.funding().value())));
      if (!(decision instanceof JournalEngine.Commit priced)) return decision;
      base = priced;
    }
    var records = new ArrayList<>(base.records());
    records.add(new Journal.Record(FACT, 1, fact(capture)));
    var financial = base;
    return new JournalEngine.Commit(
        records,
        new Journal.Result(1, "CARD_FEE_PAID", Long.toString(quote.debitMinor())),
        tx -> {
          financial.projections().apply(tx);
          tx.execute(
              "INSERT INTO issued_card_fee_quotes VALUES(?,?)",
              List.of(quote.id().toString(), intent.operationId().value().toString()));
          if (fx != null)
            tx.execute(
                "INSERT INTO ledger_fx_quotes(quote_id) VALUES(?)", List.of(fx.id().toString()));
        });
  }

  private static String fact(Capture c) {
    var q = c.quote();
    return String.join(
        "|",
        q.id().toString(),
        c.owner().world().value().toString(),
        ((PartyRef.Player) c.owner().owner()).id().value().toString(),
        c.issuer().key(),
        q.funding().value().toString(),
        Long.toString(q.fundingRevision()),
        Long.toString(q.denominationRevision()),
        c.worldCurrency().name(),
        Long.toString(Math.multiplyExact(25L, c.worldCurrency().minorUnitsPerMajor())),
        q.currency().name(),
        Long.toString(c.principal()),
        Long.toString(q.debitMinor() - c.principal()),
        c.fx() == null ? "" : c.fx().id().toString(),
        Long.toString(q.expiresAt()),
        c.card().value().toString(),
        c.fx() == null ? "" : c.fx().publication().toString(),
        c.fx() == null ? "0" : Integer.toString(c.fx().pricingVersion()));
  }

  public static void validateFact(String payload) {
    var p = payload.split("\\|", -1);
    if (p.length != 17) throw new IllegalArgumentException("Card fee fact");
    for (int i : new int[] {0, 1, 2, 4, 14})
      if (!UUID.fromString(p[i]).toString().equals(p[i]))
        throw new IllegalArgumentException("Card fee UUID");
    InstitutionCatalog.require(new InstitutionCatalog.InstitutionId(p[3]));
    var world = EconomyCurrency.valueOf(p[7]);
    EconomyCurrency.valueOf(p[9]);
    if (Long.parseLong(p[8]) != Math.multiplyExact(25L, world.minorUnitsPerMajor())
        || Long.parseLong(p[5]) < 1
        || Long.parseLong(p[6]) < 0
        || Long.parseLong(p[10]) < 1
        || Long.parseLong(p[11]) < 0
        || Long.parseLong(p[13]) < 1) throw new IllegalArgumentException("Card fee amounts");
    if (!p[12].isEmpty()) {
      UUID.fromString(p[12]);
      UUID.fromString(p[15]);
      if (Integer.parseInt(p[16]) < 1)
        throw new IllegalArgumentException("Card fee pricing version");
    } else if (!p[15].isEmpty() || !p[16].equals("0"))
      throw new IllegalArgumentException("Unexpected card fee FX witness");
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }
}
