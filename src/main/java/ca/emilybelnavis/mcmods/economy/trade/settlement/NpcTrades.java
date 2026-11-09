package ca.emilybelnavis.mcmods.economy.trade.settlement;

import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Role.*;
import static ca.emilybelnavis.mcmods.economy.trade.settlement.NpcFacts.*;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.trade.catalog.*;
import ca.emilybelnavis.mcmods.economy.trade.market.*;
import ca.emilybelnavis.mcmods.economy.trade.pricing.*;
import ca.emilybelnavis.mcmods.economy.trade.stock.*;
import com.google.gson.Gson;
import java.time.*;
import java.util.*;

/**
 * Account-only internal worker service. No runtime interaction or production activation is
 * installed.
 */
public final class NpcTrades {
  public static final Journal.Kind BOOK = new Journal.Kind("economy:a8", "economy:a8/npc/quote");
  public static final Journal.Kind SETTLE = new Journal.Kind("economy:a8", "economy:a8/npc/settle");

  public enum Phase {
    BOOK,
    SETTLE
  }

  /**
   * Installed by the authenticated server adapter, never supplied by a packet. It proves the
   * session/entity is alive, loaded, <=6 blocks away, current exact offer/equivalence/reputation
   * and source-policy revisions, all item/save/capacity reservations, and flushed tick buffers. SQL
   * access remains internal to this trusted worker boundary.
   */
  @FunctionalInterface
  public interface Admission {
    boolean allows(Phase phase, Terms terms, Quote quote, JournalEngine.ReadView reads);
  }

  private final FoundationStore store;
  private final NpcAccountFx fx;
  private final Clock wall;
  private final Admission admission;

  public NpcTrades(FoundationStore store, NpcAccountFx fx, Clock wall, Admission admission) {
    this.store = Objects.requireNonNull(store);
    this.fx = fx;
    this.wall = Objects.requireNonNull(wall);
    this.admission = Objects.requireNonNull(admission);
  }

  public Quote quote(Terms terms) {
    if (terms.inputCustody() != null && terms.inputCustody().equals(terms.outputCustody()))
      throw new IllegalArgumentException("Distinct NPC custody identities required");
    var world = store.snapshot().worldId();
    UUID id = quoteId(world, terms.operation());
    var old = store.transaction(tx -> read(tx::query, id));
    if (old != null) {
      if (!old.terms().equals(terms))
        throw new IllegalArgumentException("Original quote intent changed");
      return old;
    }
    Instant now = wall.instant();
    Quote quote = store.transaction(tx -> build(tx::query, world, terms, now));
    String payload = encode(quote);
    UUID operation = stable("book|" + world.value() + "|" + id);
    var request = new Journal.CanonicalRequest(1, Map.of("quote", TradeCatalog.digest(payload)));
    var registration =
        new JournalEngine.Registration(
            1,
            (intent, reads) -> {
              if (!request.equals(intent.request())
                  || !terms.actor().equals(intent.actor())
                  || !admission.allows(Phase.BOOK, terms, quote, reads))
                return rejected("NPC_ADMISSION");
              validateCurrent(reads, quote, now);
              return new JournalEngine.Commit(
                  List.of(new Journal.Record(QUOTE, 1, payload)),
                  new Journal.Result(1, "NPC_QUOTED", id.toString()),
                  tx ->
                      tx.execute(
                          "INSERT INTO npc_trade_quotes VALUES(?,?,?,?,?)",
                          List.of(
                              id.toString(),
                              world.value().toString(),
                              operation.toString(),
                              terms.operation().toString(),
                              payload)));
            });
    var engine =
        new JournalEngine(
            store, (a, k) -> a.equals(terms.actor()) && k.equals(BOOK), Map.of(BOOK, registration));
    var result =
        engine.submit(
            new Journal.Intent(
                new OperationId(operation),
                new RequestId(operation),
                terms.actor(),
                BOOK,
                request,
                null,
                null));
    if (result.status() != Journal.Status.COMMITTED)
      throw new IllegalArgumentException("NPC quote: " + result.result().code());
    return store.transaction(tx -> read(tx::query, id));
  }

  private Quote build(JournalEngine.ReadView reads, WorldId world, Terms terms, Instant now) {
    if (!ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityStore.ready(reads))
      throw new IllegalArgumentException("NPC activity source unavailable");
    var account = account(reads, world, terms.actor(), terms.account());
    var issuer = issuer(reads, account);
    var binding = MarketStore.binding(reads, terms.trader());
    if (binding == null) throw new IllegalArgumentException("Trader market unavailable");
    var market = MarketStore.market(reads, binding.market());
    var capture = CatalogStore.read(reads, terms.trader(), terms.catalogRevision());
    var stock = StockStore.trader(reads, terms.trader());
    if (capture == null
        || stock == null
        || stock.catalogRevision() != terms.catalogRevision()
        || terms.offerOrdinal() >= capture.offers().size())
      throw new IllegalArgumentException("Trader catalog/stock unavailable");
    var offer = capture.offers().get(terms.offerOrdinal());
    if (offer.side() == Pricing.Side.BUY
        && offer.goods().match() != TradeCatalog.Match.EXACT_COMPONENTS)
      throw new IllegalArgumentException("Exact output components required");
    var offerStock = stock.offers().get(terms.offerOrdinal());
    if (!offer.identity().equals(offerStock.identity())
        || offer.side() == Pricing.Side.BUY && terms.executions() > offerStock.remaining())
      throw new IllegalArgumentException("Trader stock unavailable");
    if ((inputItems(offer, terms.executions()).equals("[]")) != (terms.inputCustody() == null)
        || (offer.side() == Pricing.Side.BUY) != (terms.outputCustody() != null))
      throw new IllegalArgumentException("Exact input/output custody required");
    var publication = FxService.latest(reads);
    if (publication == null
        || !publication.updateId().equals(terms.source().fxSnapshot())
        || terms.source().marketCurrency() != market.currency())
      throw new IllegalArgumentException("Price source FX unavailable");
    Rational cadPerMarket =
        Rational.of(publication.rates().get(market.currency()))
            .divide(Rational.of(publication.rates().get(EconomyCurrency.CAD)));
    if (!cadPerMarket.equals(terms.source().cadPerMarketMajor()))
      throw new IllegalArgumentException("Price source FX changed");
    Instant sourceEffective =
        Instant.ofEpochMilli(FxService.effectiveMillis(reads, publication.updateId()));
    Instant sourceExpiry = sourceEffective.plus(Duration.ofHours(48));
    if (now.isBefore(sourceEffective) || !now.isBefore(sourceExpiry))
      throw new IllegalArgumentException("Price source FX stale");
    var ticks = StockStore.market(reads, market.id());
    long eligible = ticks == null ? 0 : ticks.eligibleTicks(),
        tickRevision = ticks == null ? 0 : ticks.revision();
    var priceState = MarketStore.price(reads, market.id(), offer.goods().priceKey());
    var key =
        new Pricing.Key(
            market.id(),
            offer.goods().priceKey(),
            terms.actor().value(),
            offer.side(),
            terms.epoch());
    var raw =
        terms.source().basis(offer.emeraldCount(), offer.goodsCount(), offer.standardQuantity());
    var selectedComparison =
        terms.comparisons().stream()
            .filter(
                c ->
                    offer.side() == Pricing.Side.BUY
                        ? c.neutralAsk().equals(raw.minorPerStandard())
                        : raw.minorPerStandard().compareTo(c.neutralBid()) >= 0)
            .reduce((a, b) -> b)
            .orElseThrow(
                () -> new IllegalArgumentException("Selected source comparison unavailable"));
    var basis = Pricing.normalizedBasis(offer.side(), raw, selectedComparison);
    var pricingRequest =
        new Pricing.Request(
            key,
            basis,
            offer.standardQuantity(),
            Math.multiplyExact((long) terms.executions(), offer.goodsCount()),
            priceState == null ? new Pricing.Demand(Rational.ZERO, 0) : priceState.demand(),
            eligible,
            terms.reputation(),
            terms.comparisons(),
            MarketStore.carry(reads, key));
    var outcome = Pricing.quote(pricingRequest);
    if (!outcome.available()) throw new IllegalArgumentException(outcome.reason());
    Instant expiry =
        now.plusSeconds(30).isBefore(sourceExpiry) ? now.plusSeconds(30) : sourceExpiry;
    NpcAccountFx.Quote foreign = null;
    if (account.currency() != market.currency()) {
      if (fx == null) throw new IllegalArgumentException("NPC FX unavailable");
      foreign =
          fx.issue(
              reads,
              world,
              terms.actor(),
              account,
              offer.side(),
              new Money(market.currency(), outcome.quote().minor()),
              now,
              expiry);
      if (foreign.expiresAt().isBefore(expiry)) expiry = foreign.expiresAt();
    }
    var quote =
        new Quote(
            quoteId(world, terms.operation()),
            world,
            terms,
            account.currency(),
            account.revision(),
            issuer,
            market.id(),
            binding.revision(),
            stock.revision(),
            priceState == null ? 0 : priceState.revision(),
            tickRevision,
            eligible,
            pricingRequest,
            outcome.quote(),
            foreign,
            now.toEpochMilli(),
            expiry.toEpochMilli(),
            financialIntent(world, terms.operation(), terms.actor()));
    if (!admission.allows(Phase.BOOK, terms, quote, reads))
      throw new IllegalArgumentException("NPC admission unavailable");
    return quote;
  }

  public static Journal.CanonicalRequest settleRequest(UUID quote) {
    return new Journal.CanonicalRequest(1, Map.of("quote", quote.toString()));
  }

  public static Journal.Intent intent(WorldId world, UUID original, PlayerId actor) {
    return new Journal.Intent(
        new OperationId(original),
        new RequestId(original),
        actor,
        SETTLE,
        settleRequest(quoteId(world, original)),
        null,
        null);
  }

  public static String financialIntent(WorldId world, UUID original, PlayerId actor) {
    return Custody.financialIntent(intent(world, original, actor));
  }

  public Journal.Outcome settle(Journal.Intent intent) {
    var engine =
        new JournalEngine(
            store,
            (a, k) -> a instanceof PlayerId && k.equals(SETTLE),
            Map.of(SETTLE, new JournalEngine.Registration(1, this::prepare)));
    return engine.submit(intent);
  }

  private JournalEngine.Decision prepare(Journal.Intent intent, JournalEngine.ReadView reads)
      throws Exception {
    try {
      UUID id = UUID.fromString(intent.request().fields().get("quote"));
      if (!settleRequest(id).equals(intent.request())) return rejected("NPC_REQUEST");
      var quote = read(reads, id);
      if (quote == null
          || !quote.world().equals(store.snapshot().worldId())
          || !quote.terms().actor().equals(intent.actor())
          || !quote.terms().operation().equals(intent.operationId().value())
          || !quote.financialIntent().equals(Custody.financialIntent(intent)))
        return rejected("NPC_ORIGINAL_INTENT");
      if (!reads
          .query(
              "SELECT operation_id FROM npc_trade_receipts WHERE quote_id=?",
              List.of(id.toString()),
              1)
          .isEmpty()) return rejected("NPC_QUOTE_CONSUMED");
      if (!ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityStore.ready(reads))
        return rejected("NPC_ACTIVITY_UNAVAILABLE");
      Instant now = wall.instant();
      validateCurrent(reads, quote, now);
      if (!admission.allows(Phase.SETTLE, quote.terms(), quote, reads))
        return rejected("NPC_ADMISSION");
      var terms = quote.terms();
      var offer =
          CatalogStore.read(reads, terms.trader(), terms.catalogRevision())
              .offers()
              .get(terms.offerOrdinal());
      long principal = quote.price().minor();
      EconomyCurrency currency = quote.priceRequest().basis().currency();
      var groups = new ArrayList<NpcSettlement.Group>();
      NpcAccountFx.Plan foreign =
          quote.fx() == null
              ? null
              : fx.prepare(reads, quote.fx(), quote.world(), terms.actor(), now);
      var bridge = NpcSettlement.bridge(quote.world(), currency);
      var issuance = Settlement.issuanceAccount(currency);
      if (offer.side() == Pricing.Side.SELL)
        groups.add(
            new NpcSettlement.Group(
                PostingBatch.Authority.ISSUE,
                List.of(
                    leg(issuance, ISSUANCE, currency, -principal),
                    leg(bridge, NPC_BRIDGE, currency, principal))));
      if (foreign != null) groups.addAll(foreign.groups());
      else
        groups.add(
            new NpcSettlement.Group(
                PostingBatch.Authority.TRANSFER,
                offer.side() == Pricing.Side.BUY
                    ? List.of(
                        leg(terms.account().value(), ACCOUNT, currency, -principal),
                        leg(bridge, NPC_BRIDGE, currency, principal))
                    : List.of(
                        leg(bridge, NPC_BRIDGE, currency, -principal),
                        leg(terms.account().value(), ACCOUNT, currency, principal))));
      if (offer.side() == Pricing.Side.BUY)
        groups.add(
            new NpcSettlement.Group(
                PostingBatch.Authority.REDEEM,
                List.of(
                    leg(bridge, NPC_BRIDGE, currency, -principal),
                    leg(issuance, ISSUANCE, currency, principal))));
      if (!groups.equals(expectedGroups(quote))) return rejected("NPC_FINANCIAL_PLAN_CHANGED");
      long accountDebit =
          offer.side() == Pricing.Side.BUY
              ? (foreign == null
                  ? principal
                  : Math.addExact(
                      foreign.conversion().debit().minorUnits(),
                      foreign.pricing().fee().minorUnits()))
              : 0;
      if (accountDebit > Holds.available(reads, terms.account().value(), quote.accountCurrency()))
        return rejected("INSUFFICIENT_FUNDS");
      var financialDecision =
          NpcSettlement.prepare(
              reads, quote.world(), groups, foreign == null ? List.of() : foreign.accounts());
      if (!(financialDecision instanceof JournalEngine.Commit financial)) return financialDecision;
      JournalEngine.Commit base = financial;
      if (terms.inputCustody() != null) {
        var input =
            Custody.find(reads, terms.actor(), terms.inputCustody(), false)
                .orElseThrow(() -> new IllegalArgumentException("Input custody unavailable"));
        if (input.state() != Custody.State.WITNESSED
            || !input.terms().financialIntent().equals(quote.financialIntent())
            || !input.terms().items().equals(terms.exactInputItems())
            || !input.terms().source().equals(inputSource(terms))
            || !input.terms().destination().equals(inputDestination(terms)))
          return rejected("NPC_INPUT_CUSTODY");
        var child =
            new Journal.Intent(
                intent.operationId(),
                intent.requestId(),
                intent.actor(),
                Custody.Action.COMMIT.kind(),
                Custody.request(
                    input.id(), null, Custody.Evidence.unknown(), "NPC original goods input"),
                null,
                null);
        var financialBase = financial;
        var handler =
            Custody.registration(
                Custody.Action.COMMIT,
                (a, i, c, r) -> c.id().equals(input.id()) && c.terms().equals(input.terms()),
                (c, e, r) -> false,
                (i, c, r) -> financialBase,
                (i, c, r) -> true);
        var inputDecision = handler.handler().prepare(child, reads);
        if (!(inputDecision instanceof JournalEngine.Commit accepted)) return inputDecision;
        base = accepted;
      }
      var components = new ArrayList<JournalEngine.Commit>();
      components.add(base);
      if (terms.outputCustody() != null)
        components.add(
            Custody.committedDelivery(
                intent,
                terms.outputCustody(),
                terms.actor(),
                outputItems(offer, terms.executions()),
                outputSource(terms),
                outputDestination(terms),
                reads));
      components.add(
          MarketStore.preparePriceTransition(
                  reads,
                  quote.world(),
                  terms.operation(),
                  quote.market(),
                  quote.priceRequest().key().item(),
                  quote.priceRevision(),
                  quote.eligibleTick(),
                  quote.price())
              .commit());
      components.add(
          StockStore.prepareTrade(
              reads,
              quote.world(),
              terms.operation(),
              terms.trader(),
              terms.catalogRevision(),
              quote.bindingRevision(),
              quote.stockRevision(),
              terms.offerOrdinal(),
              terms.executions()));
      var receipt =
          new Receipt(
              quote.world(),
              terms.operation(),
              quote.id(),
              terms.actor(),
              terms.account().value(),
              terms.trader(),
              quote.market(),
              offer.side(),
              currency,
              principal,
              now.toEpochMilli(),
              foreign == null ? null : foreign.conversion().pair().source(),
              foreign == null ? 0 : foreign.conversion().debit().minorUnits(),
              foreign == null ? null : foreign.conversion().pair().target(),
              foreign == null ? 0 : foreign.conversion().credit().minorUnits(),
              foreign == null ? null : foreign.conversion().publication(),
              foreign == null ? 0 : foreign.pricing().fee().minorUnits(),
              terms.inputCustody(),
              terms.outputCustody());
      var records = new ArrayList<Journal.Record>();
      for (var part : components) records.addAll(part.records());
      if (foreign != null) records.addAll(foreign.facts());
      boolean install =
          reads
              .query("SELECT world_id FROM npc_trade_adapter WHERE singleton=1", List.of(), 1)
              .isEmpty();
      String adapter = encode(new Adapter(quote.world(), SOURCES));
      if (install) records.add(new Journal.Record(ADAPTER, 1, adapter));
      String payload = encode(receipt);
      records.add(new Journal.Record(RECEIPT, 1, payload));
      var foreignPlan = foreign;
      return new JournalEngine.Commit(
          records,
          new Journal.Result(1, "NPC_TRADE_COMMITTED", quote.id().toString()),
          tx -> {
            for (var part : components) part.projections().apply(tx);
            if (foreignPlan != null) foreignPlan.registrations().apply(tx);
            if (install)
              tx.execute(
                  "INSERT INTO npc_trade_adapter VALUES(1,?,?,?)",
                  List.of(quote.world().value().toString(), terms.operation().toString(), adapter));
            tx.execute(
                "INSERT INTO npc_trade_receipts VALUES(?,?,?,?,?,?)",
                Arrays.asList(
                    terms.operation().toString(),
                    quote.id().toString(),
                    quote.world().value().toString(),
                    Long.toString(receipt.effectiveMillis()),
                    quote.fx() == null ? null : quote.fx().conversion().id().toString(),
                    payload));
          });
    } catch (IllegalArgumentException | ArithmeticException stale) {
      return rejected("NPC_QUOTE_STALE");
    }
  }

  /** Pure captured financial plan, shared by live composition and offline source validation. */
  public static List<NpcSettlement.Group> expectedGroups(Quote quote) {
    var t = quote.terms();
    var side = quote.priceRequest().key().side();
    var c = quote.priceRequest().basis().currency();
    long principal = quote.price().minor();
    UUID bridge = NpcSettlement.bridge(quote.world(), c), issuance = Settlement.issuanceAccount(c);
    var groups = new ArrayList<NpcSettlement.Group>();
    if (side == Pricing.Side.SELL)
      groups.add(
          new NpcSettlement.Group(
              PostingBatch.Authority.ISSUE,
              List.of(
                  leg(issuance, ISSUANCE, c, -principal), leg(bridge, NPC_BRIDGE, c, principal))));
    if (quote.fx() == null)
      groups.add(
          new NpcSettlement.Group(
              PostingBatch.Authority.TRANSFER,
              side == Pricing.Side.BUY
                  ? List.of(
                      leg(t.account().value(), ACCOUNT, c, -principal),
                      leg(bridge, NPC_BRIDGE, c, principal))
                  : List.of(
                      leg(bridge, NPC_BRIDGE, c, -principal),
                      leg(t.account().value(), ACCOUNT, c, principal))));
    else {
      var fx = quote.fx();
      var from = fx.conversion().pair().source();
      var to = fx.conversion().pair().target();
      long fee = fx.pricing().fee().minorUnits();
      UUID payer =
          side == Pricing.Side.BUY
              ? t.account().value()
              : NpcSettlement.bridge(quote.world(), from);
      var payerRole = side == Pricing.Side.BUY ? ACCOUNT : NPC_BRIDGE;
      if (fee > 0) {
        var bank = new InstitutionCatalog.InstitutionId(fx.supplier().bankKey());
        var depositor =
            new InstitutionCatalog.InstitutionId("economy:depositor/" + t.actor().value());
        UUID cash = FinancialAccounts.id(quote.world(), bank, FinancialAccounts.Role.CASH, from),
            income = FinancialAccounts.id(quote.world(), bank, FinancialAccounts.Role.INCOME, from),
            expense =
                FinancialAccounts.id(
                    quote.world(), depositor, FinancialAccounts.Role.EXPENSE, from);
        groups.add(
            new NpcSettlement.Group(
                PostingBatch.Authority.TRANSFER,
                List.of(
                    leg(payer, payerRole, from, -fee), leg(cash, INSTITUTION_CASH, from, fee))));
        groups.add(
            new NpcSettlement.Group(
                PostingBatch.Authority.TRANSFER,
                List.of(
                    leg(expense, INSTITUTION_CLAIM, from, fee),
                    leg(income, INSTITUTION_CLAIM, from, -fee))));
      }
      UUID target =
          side == Pricing.Side.BUY ? NpcSettlement.bridge(quote.world(), to) : t.account().value();
      groups.add(
          new NpcSettlement.Group(
              PostingBatch.Authority.FX,
              List.of(
                  leg(payer, payerRole, from, -fx.conversion().debit().minorUnits()),
                  leg(
                      Settlement.clearingAccount(from),
                      CLEARING,
                      from,
                      fx.conversion().debit().minorUnits()),
                  leg(
                      Settlement.clearingAccount(to),
                      CLEARING,
                      to,
                      -fx.conversion().credit().minorUnits()),
                  leg(
                      target,
                      side == Pricing.Side.BUY ? NPC_BRIDGE : ACCOUNT,
                      to,
                      fx.conversion().credit().minorUnits()))));
    }
    if (side == Pricing.Side.BUY)
      groups.add(
          new NpcSettlement.Group(
              PostingBatch.Authority.REDEEM,
              List.of(
                  leg(bridge, NPC_BRIDGE, c, -principal), leg(issuance, ISSUANCE, c, principal))));
    return List.copyOf(groups);
  }

  private void validateCurrent(JournalEngine.ReadView reads, Quote quote, Instant now) {
    TradeSources.requireLegacy(
        reads, quote.world(), quote.market(), quote.priceRequest().key().item());
    if (now.toEpochMilli() < quote.issuedMillis() || now.toEpochMilli() >= quote.expiresMillis())
      throw new IllegalArgumentException("NPC quote expired");
    var terms = quote.terms();
    if (terms.outputCustody() != null
        && !reads
            .query(
                "SELECT id FROM custody_cases WHERE id=?",
                List.of(terms.outputCustody().toString()),
                1)
            .isEmpty()) throw new IllegalArgumentException("NPC output custody already exists");
    var selected = account(reads, quote.world(), terms.actor(), terms.account());
    if (selected.revision() != quote.accountRevision()
        || selected.currency() != quote.accountCurrency()
        || !issuer(reads, selected).equals(quote.issuer()))
      throw new IllegalArgumentException("NPC account/provider changed");
    var binding = MarketStore.binding(reads, terms.trader());
    var stock = StockStore.trader(reads, terms.trader());
    var price = MarketStore.price(reads, quote.market(), quote.priceRequest().key().item());
    var ticks = StockStore.market(reads, quote.market());
    if (binding == null
        || !binding.market().equals(quote.market())
        || binding.revision() != quote.bindingRevision()
        || stock == null
        || stock.revision() != quote.stockRevision()
        || stock.catalogRevision() != terms.catalogRevision()
        || (price == null ? 0 : price.revision()) != quote.priceRevision()
        || (ticks == null ? 0 : ticks.revision()) != quote.marketTickRevision()
        || (ticks == null ? 0 : ticks.eligibleTicks()) != quote.eligibleTick())
      throw new IllegalArgumentException("NPC market/catalog/stock/tick changed");
    var capture = CatalogStore.read(reads, terms.trader(), terms.catalogRevision());
    if (capture == null
        || terms.offerOrdinal() >= capture.offers().size()
        || !stock
            .offers()
            .get(terms.offerOrdinal())
            .identity()
            .equals(capture.offers().get(terms.offerOrdinal()).identity()))
      throw new IllegalArgumentException("NPC exact offer changed");
    if (!MarketStore.carry(reads, quote.priceRequest().key()).equals(quote.priceRequest().carry()))
      throw new IllegalArgumentException("NPC rounding carry changed");
    var latest = FxService.latest(reads);
    if (latest == null || !latest.updateId().equals(terms.source().fxSnapshot()))
      throw new IllegalArgumentException("NPC price FX changed");
    var recomputed = Pricing.quote(quote.priceRequest());
    if (!recomputed.available() || !recomputed.quote().equals(quote.price()))
      throw new IllegalArgumentException("NPC price changed");
    if (quote.fx() != null) {
      if (fx == null) throw new IllegalArgumentException("NPC FX unavailable");
      fx.requireFresh(reads, quote.fx(), quote.world(), terms.actor(), now);
    }
  }

  static AccountIdentity account(
      JournalEngine.ReadView reads, WorldId world, PlayerId actor, AccountId id) {
    var value =
        AccountCatalog.find(reads, new PartyRef(world, new PartyRef.Player(actor)), id)
            .orElseThrow(() -> new IllegalArgumentException("Authorized account required"));
    if (value.purpose() != AccountIdentity.Purpose.BANK
        || value.status() != AccountIdentity.Status.OPEN)
      throw new IllegalArgumentException("Open BANK account required");
    return value;
  }

  static BankProviders.Binding issuer(JournalEngine.ReadView reads, AccountIdentity account) {
    var value =
        BankProviders.find(reads, account.owner(), account.id())
            .orElseThrow(() -> new IllegalArgumentException("Account issuer unavailable"));
    var bank = InstitutionCatalog.require(new InstitutionCatalog.InstitutionId(value.bankKey()));
    if (value.legacy()
        || !bank.enabled()
        || !bank.customerBank()
        || bank.baseCurrency() != account.currency())
      throw new IllegalArgumentException("Account issuer unavailable");
    var parties =
        reads.query(
            "SELECT enabled FROM institution_parties WHERE world_id=? AND institution_key=?",
            List.of(account.owner().world().value().toString(), value.bankKey()),
            1);
    if (parties.size() != 1 || !"1".equals(parties.getFirst().get("enabled")))
      throw new IllegalArgumentException("Enabled issuer party required");
    return value;
  }

  public static Quote read(JournalEngine.ReadView reads, UUID id) {
    var rows =
        reads.query("SELECT payload FROM npc_trade_quotes WHERE id=?", List.of(id.toString()), 1);
    return rows.isEmpty() ? null : decodeQuote(rows.getFirst().get("payload"));
  }

  public static Receipt receipt(JournalEngine.ReadView reads, UUID operation) {
    var rows =
        reads.query(
            "SELECT payload FROM npc_trade_receipts WHERE operation_id=?",
            List.of(operation.toString()),
            1);
    return rows.isEmpty() ? null : decodeReceipt(rows.getFirst().get("payload"));
  }

  private record Item(TradeCatalog.Goods goods, long count) {}

  private static String items(List<Item> items) {
    String encoded = new Gson().toJson(items);
    if (encoded.length() > 4096) throw new IllegalArgumentException("Custody item bound");
    return encoded;
  }

  /**
   * Requirements only; Admission must separately prove exact reserved stacks satisfy these
   * predicates.
   */
  public static String inputItems(TradeCatalog.Offer offer, int executions) {
    var list = new ArrayList<Item>();
    if (offer.side() == Pricing.Side.SELL)
      list.add(new Item(offer.goods(), Math.multiplyExact((long) offer.goodsCount(), executions)));
    for (var secondary : offer.secondary())
      list.add(
          new Item(secondary.goods(), Math.multiplyExact((long) secondary.count(), executions)));
    return items(list);
  }

  public static String outputItems(TradeCatalog.Offer offer, int executions) {
    return offer.side() == Pricing.Side.BUY
        ? items(
            List.of(
                new Item(offer.goods(), Math.multiplyExact((long) offer.goodsCount(), executions))))
        : "[]";
  }

  public static String inputSource(Terms t) {
    return "npc-player-inventory:" + t.actor().value();
  }

  public static String inputDestination(Terms t) {
    return "npc-input:" + t.trader();
  }

  public static String outputSource(Terms t) {
    return "npc-output:" + t.trader();
  }

  public static String outputDestination(Terms t) {
    return "npc-player-inventory:" + t.actor().value();
  }

  private static PostingBatch.Leg leg(
      UUID account, PostingBatch.Role role, EconomyCurrency currency, long minor) {
    return new PostingBatch.Leg(account, role, currency, new Money(currency, minor));
  }

  private static JournalEngine.Reject rejected(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }
}
