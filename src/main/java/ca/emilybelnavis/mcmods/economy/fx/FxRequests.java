package ca.emilybelnavis.mcmods.economy.fx;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.api.RequestContext;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Server-thread entry points for trusted handlers that derive context from the authenticated
 * player.
 */
public final class FxRequests {
  private final ServerServices services;
  private final FoundationStore store;
  private final Clock wall;
  private FxQuotes quotes;

  private record Source(
      ca.emilybelnavis.mcmods.economy.api.AccountIdentity account, java.time.Instant expires) {}

  private final java.util.Map<UUID, Source> sources = new java.util.HashMap<>();
  private final ca.emilybelnavis.mcmods.economy.pricing.ServicePricing pricing;

  public FxRequests(ServerServices services, FoundationStore store, Clock wall) {
    this.services = services;
    this.store = store;
    this.wall = wall;
    this.pricing = services.servicePricing();
  }

  public CompletionStage<FxQuotes.Quote> quote(
      RequestContext context, FxQuotes.Pair pair, FxQuotes.Side side, Money amount) {
    return quote(
        context,
        pair,
        side,
        amount,
        ca.emilybelnavis.mcmods.economy.pricing.ServicePricing.Service.FX,
        false);
  }

  /**
   * A protected close converts the full principal and charges its fee within the exact available
   * budget.
   */
  public CompletionStage<FxQuotes.Quote> quoteForClosure(
      RequestContext context, FxQuotes.Pair pair, Money budget) {
    return quote(
        context,
        pair,
        FxQuotes.Side.EXACT_DEBIT,
        budget,
        ca.emilybelnavis.mcmods.economy.pricing.ServicePricing.Service.PROTECTED_CLOSURE_FX,
        true);
  }

  /**
   * Debt repayment retains ordinary fees while preserving protected access below specialist
   * qualification.
   */
  public CompletionStage<FxQuotes.Quote> quoteRepayment(
      RequestContext context, FxQuotes.Pair pair, Money amount) {
    return quote(
        context,
        pair,
        FxQuotes.Side.EXACT_CREDIT,
        amount,
        ca.emilybelnavis.mcmods.economy.pricing.ServicePricing.Service.PROTECTED_WITHDRAWAL_FX,
        false);
  }

  private CompletionStage<FxQuotes.Quote> quote(
      RequestContext context,
      FxQuotes.Pair pair,
      FxQuotes.Side side,
      Money amount,
      ca.emilybelnavis.mcmods.economy.pricing.ServicePricing.Service service,
      boolean drain) {
    PlayerId actor = authenticate(context);
    return services.storage(
        actor,
        () ->
            store.transaction(
                tx -> {
                  if (quotes == null) quotes = new FxQuotes(store.snapshot().worldId());
                  var quote =
                      quotes.issue(
                          actor,
                          pair,
                          side,
                          amount,
                          FxService.snapshot(tx::query),
                          wall.instant(),
                          Duration.ofSeconds(60));
                  var owner =
                      new ca.emilybelnavis.mcmods.economy.api.PartyRef(
                          store.snapshot().worldId(),
                          new ca.emilybelnavis.mcmods.economy.api.PartyRef.Player(actor));
                  var account =
                      ca.emilybelnavis.mcmods.economy.account.AccountCatalog.openBanks(
                              tx::query, owner)
                          .stream()
                          .filter(a -> a.currency() == pair.source())
                          .findFirst()
                          .orElse(null);
                  var bank =
                      account == null
                          ? null
                          : ca.emilybelnavis.mcmods.economy.pricing.CashPricing.bank(
                              new ca.emilybelnavis.mcmods.economy.pricing.CashPricing.Context(
                                  pricing,
                                  owner.world(),
                                  actor,
                                  account.id().value(),
                                  wall.instant()),
                              tx::query);
                  sources.values().removeIf(s -> !s.expires().isAfter(wall.instant()));
                  if (account != null)
                    sources.put(quote.id(), new Source(account, quote.expires()));
                  if (bank == null)
                    return quote; // Explicit unassigned legacy access retains its prior pricing.
                  var fee =
                      pricing.quote(
                          tx::query,
                          owner.world(),
                          actor,
                          bank,
                          service,
                          quote.debit(),
                          wall.instant(),
                          quote.expires());
                  if (drain) {
                    var principal =
                        ca.emilybelnavis.mcmods.economy.pricing.CashPricing.principalForBudget(
                            amount, fee.tier());
                    if (!principal.equals(quote.debit())) {
                      quote =
                          quotes.issue(
                              actor,
                              pair,
                              side,
                              principal,
                              FxService.snapshot(tx::query),
                              wall.instant(),
                              Duration.ofSeconds(60));
                      sources.put(quote.id(), new Source(account, quote.expires()));
                      fee =
                          pricing.quote(
                              tx::query,
                              owner.world(),
                              actor,
                              bank,
                              service,
                              quote.debit(),
                              wall.instant(),
                              quote.expires());
                    }
                  }
                  return quotes.price(quote, fee);
                }));
  }

  public CompletionStage<FxPairHistory.Page> history(
      RequestContext context, FxQuotes.Pair pair, FxPairHistory.Cursor cursor, int limit) {
    PlayerId actor = authenticate(context);
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("FX history page size");
    return services.storage(
        actor,
        () ->
            store.transaction(
                tx ->
                    FxPairHistory.page(
                        tx::query, store.snapshot().worldId(), pair, cursor, limit)));
  }

  /** Separate exact-time intraday history; the existing history() remains explicitly daily. */
  public CompletionStage<FxPairHistory.ExactPage> intradayHistory(
      RequestContext context, FxQuotes.Pair pair, FxPairHistory.ExactCursor cursor, int limit) {
    PlayerId actor = authenticate(context);
    if (limit < 1 || limit > 16)
      throw new IllegalArgumentException("FX intraday history page size");
    return services.storage(
        actor,
        () ->
            store.transaction(
                tx ->
                    FxPairHistory.intradayPage(
                        tx::query, store.snapshot().worldId(), pair, cursor, limit)));
  }

  /** Worker-side quote lookup after journal retry resolution. */
  public FxQuotes.Quote requireFresh(
      UUID id, PlayerId actor, WorldId world, JournalEngine.ReadView reads) {
    if (quotes == null) throw new IllegalArgumentException("FX_QUOTE_UNAVAILABLE");
    var quote =
        quotes.requireFresh(
            id, actor, world, FxService.snapshot(reads), FxQuotes.PRICING_VERSION, wall.instant());
    var witness = sources.get(id);
    var owner =
        new ca.emilybelnavis.mcmods.economy.api.PartyRef(
            world, new ca.emilybelnavis.mcmods.economy.api.PartyRef.Player(actor));
    var account =
        witness == null
            ? null
            : ca.emilybelnavis.mcmods.economy.account.AccountCatalog.find(
                    reads, owner, witness.account().id())
                .orElse(null);
    if (account == null
        || account.status() != ca.emilybelnavis.mcmods.economy.api.AccountIdentity.Status.OPEN
        || account.revision() != witness.account().revision()
        || account.currency() != quote.pair().source())
      throw new IllegalArgumentException("FX_SOURCE_STALE");
    var bank =
        ca.emilybelnavis.mcmods.economy.pricing.CashPricing.bank(
            new ca.emilybelnavis.mcmods.economy.pricing.CashPricing.Context(
                pricing, world, actor, account.id().value(), wall.instant()),
            reads);
    if (quote.pricing() == null ? bank != null : !quote.pricing().institution().equals(bank))
      throw new IllegalArgumentException("FX_PROVIDER_STALE");
    if (quote.pricing() != null)
      pricing.requireFresh(quote.pricing(), reads, world, actor, wall.instant());
    return quote;
  }

  private PlayerId authenticate(RequestContext context) {
    if (!services.sessionId().equals(context.serverSessionId())) {
      throw new IllegalArgumentException("FX session mismatch");
    }
    return new PlayerId(context.actorId());
  }
}
