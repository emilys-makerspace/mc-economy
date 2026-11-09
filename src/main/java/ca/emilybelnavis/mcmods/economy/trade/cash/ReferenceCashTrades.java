package ca.emilybelnavis.mcmods.economy.trade.cash;

import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Role.*;
import static ca.emilybelnavis.mcmods.economy.trade.cash.ReferenceCashFacts.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityStore;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.trade.catalog.*;
import ca.emilybelnavis.mcmods.economy.trade.market.*;
import ca.emilybelnavis.mcmods.economy.trade.pricing.*;
import ca.emilybelnavis.mcmods.economy.trade.settlement.NpcTrades;
import ca.emilybelnavis.mcmods.economy.trade.stock.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * Explicit internal cash rail. Never mutates a physical inventory or enables trader interaction.
 */
public final class ReferenceCashTrades {
  public static final Journal.Kind
      BOOK = new Journal.Kind("economy:a9", "economy:a9/reference-cash/quote"),
      SETTLE = new Journal.Kind("economy:a9", "economy:a9/reference-cash/settle");

  public enum Phase {
    BOOK,
    SETTLE,
    RETURN
  }

  /**
   * Authenticated server policy proves exact stacks satisfy predicates, independent saves,
   * <=6-block live session, complete comparison/reputation/source revisions, all output capacity,
   * and drained tick buffers.
   */
  @FunctionalInterface
  public interface Admission {
    boolean allows(Phase phase, Terms terms, Quote quote, JournalEngine.ReadView reads);
  }

  private final FoundationStore store;
  private final NpcCashFx fx;
  private final Clock wall;
  private final Admission admission;

  public ReferenceCashTrades(FoundationStore store, NpcCashFx fx, Clock wall, Admission admission) {
    this.store = Objects.requireNonNull(store);
    this.fx = fx;
    this.wall = Objects.requireNonNull(wall);
    this.admission = Objects.requireNonNull(admission);
  }

  public static Journal.CanonicalRequest request(UUID quote) {
    return new Journal.CanonicalRequest(1, Map.of("quote", quote.toString()));
  }

  public static Journal.Intent intent(WorldId world, UUID original, PlayerId actor) {
    return new Journal.Intent(
        new OperationId(original),
        new RequestId(original),
        actor,
        SETTLE,
        request(quoteId(world, original)),
        null,
        null);
  }

  public static String financialIntent(WorldId world, UUID original, PlayerId actor) {
    return Custody.financialIntent(intent(world, original, actor));
  }

  public static String inputSource(Terms t) {
    return "npc-cash-player-inventory:" + t.actor().value();
  }

  public static String inputDestination(Terms t) {
    return "npc-cash-input:" + t.trader();
  }

  public static String outputSource(Terms t) {
    return "npc-cash-output:" + t.trader();
  }

  public static String outputDestination(Terms t) {
    return "npc-cash-player-inventory:" + t.actor().value();
  }

  public static Quote read(JournalEngine.ReadView reads, UUID id) {
    var rows =
        reads.query(
            "SELECT payload FROM reference_cash_quotes WHERE id=?", List.of(id.toString()), 1);
    return rows.isEmpty() ? null : decodeQuote(rows.getFirst().get("payload"));
  }

  public static Receipt receipt(JournalEngine.ReadView reads, UUID id) {
    var rows =
        reads.query(
            "SELECT payload FROM reference_cash_receipts WHERE operation_id=?",
            List.of(id.toString()),
            1);
    return rows.isEmpty() ? null : decodeReceipt(rows.getFirst().get("payload"));
  }

  public Quote quote(Terms terms) {
    var world = store.snapshot().worldId();
    var id = quoteId(world, terms.operation());
    var retained = store.transaction(tx -> read(tx::query, id));
    if (retained != null) {
      if (!retained.terms().equals(terms))
        throw new IllegalArgumentException("Original cash quote changed");
      return retained;
    }
    var quote = store.transaction(tx -> build(tx::query, world, terms, wall.instant()));
    String payload = encode(quote);
    UUID operation = stable("book|" + world.value() + "|" + id);
    var canonical = new Journal.CanonicalRequest(1, Map.of("quote", TradeCatalog.digest(payload)));
    var registration =
        new JournalEngine.Registration(
            1,
            (i, r) -> {
              if (!canonical.equals(i.request())
                  || !terms.actor().equals(i.actor())
                  || !admission.allows(Phase.BOOK, terms, quote, r))
                return reject("NPC_CASH_ADMISSION");
              validateCurrent(r, quote, wall.instant(), false);
              validateCash(r, world, terms, false);
              preflight(r, quote, wall.instant());
              return new JournalEngine.Commit(
                  List.of(new Journal.Record(QUOTE, 1, payload)),
                  new Journal.Result(1, "NPC_CASH_QUOTED", id.toString()),
                  tx -> {
                    tx.execute(
                        "INSERT INTO reference_cash_quotes VALUES(?,?,?,?,?,?)",
                        List.of(
                            id.toString(),
                            world.value().toString(),
                            operation.toString(),
                            terms.operation().toString(),
                            terms.inputCustody().toString(),
                            payload));
                    for (var amount : markedQuantities(terms.cash()).entrySet())
                      tx.execute(
                          "INSERT INTO custody_cash_reservations VALUES(?,?,?,'ACTIVE')",
                          List.of(
                              terms.inputCustody().toString(),
                              amount.getKey().toString(),
                              Integer.toString(amount.getValue())));
                  });
            });
    var engine =
        new JournalEngine(
            store, (a, k) -> a.equals(terms.actor()) && k.equals(BOOK), Map.of(BOOK, registration));
    var outcome =
        engine.submit(
            new Journal.Intent(
                new OperationId(operation),
                new RequestId(operation),
                terms.actor(),
                BOOK,
                canonical,
                null,
                null));
    if (outcome.status() != Journal.Status.COMMITTED)
      throw new IllegalArgumentException("Cash quote: " + outcome.result().code());
    return store.transaction(tx -> read(tx::query, id));
  }

  private Quote build(JournalEngine.ReadView reads, WorldId world, Terms terms, Instant now) {
    if (!ActivityStore.ready(reads))
      throw new IllegalArgumentException("Cash activity source unavailable");
    var binding = MarketStore.binding(reads, terms.trader());
    var stock = StockStore.trader(reads, terms.trader());
    var capture = CatalogStore.read(reads, terms.trader(), terms.catalogRevision());
    if (binding == null
        || stock == null
        || capture == null
        || stock.catalogRevision() != terms.catalogRevision()
        || terms.offerOrdinal() >= capture.offers().size())
      throw new IllegalArgumentException("Cash trader unavailable");
    var market = MarketStore.market(reads, binding.market());
    var offer = capture.offers().get(terms.offerOrdinal());
    if ((offer.side() == Pricing.Side.BUY) != (!terms.cash().isEmpty())
        || (offer.side() == Pricing.Side.BUY) != (terms.outputCustody() != null)
        || offer.goods().match() != TradeCatalog.Match.EXACT_COMPONENTS)
      throw new IllegalArgumentException("Cash rail/input/output mismatch");
    if (offer.side() == Pricing.Side.BUY
        && terms.executions() > stock.offers().get(offer.ordinal()).remaining())
      throw new IllegalArgumentException("Sale stock unavailable");
    outputsAbsent(reads, terms);
    custody(reads, world, terms, Custody.State.RESERVED);
    var totals = validateCash(reads, world, terms, false);
    terms.source().validate(reads, offer);
    if (!terms.comparisons().equals(terms.source().comparisons(reads, capture, offer)))
      throw new IllegalArgumentException("Reference cash comparison witness changed");
    var publication = FxService.at(reads, now.toEpochMilli());
    if (publication == null
        || !publication.id().equals(terms.source().fxSnapshot())
        || terms.source().marketCurrency() != market.currency())
      throw new IllegalArgumentException("Cash price FX unavailable");
    Instant effective = Instant.ofEpochMilli(publication.effectiveMillis()),
        expiry = effective.plus(Duration.ofHours(48));
    if (now.isBefore(effective) || !now.isBefore(expiry))
      throw new IllegalArgumentException("Cash price FX stale");
    if (now.plusSeconds(30).isBefore(expiry)) expiry = now.plusSeconds(30);
    var ticks = StockStore.market(reads, market.id());
    long tick = ticks == null ? 0 : ticks.eligibleTicks();
    var prior = TradeSources.price(reads, market.id(), offer.goods().priceKey());
    var key =
        new Pricing.Key(
            market.id(),
            offer.goods().priceKey(),
            terms.actor().value(),
            offer.side(),
            terms.epoch());
    var raw =
        terms.source().basis(offer.emeraldCount(), offer.goodsCount(), offer.standardQuantity());
    var comparison =
        terms.comparisons().stream()
            .filter(
                c ->
                    offer.side() == Pricing.Side.BUY
                        ? c.neutralAsk().equals(raw.minorPerStandard())
                        : raw.minorPerStandard().compareTo(c.neutralBid()) >= 0)
            .reduce((a, b) -> b)
            .orElseThrow(() -> new IllegalArgumentException("Cash source comparison unavailable"));
    var priceRequest =
        new ReferencePricing.Request(
            key,
            ReferencePricing.normalizedBasis(offer.side(), raw, comparison),
            offer.standardQuantity(),
            Math.multiplyExact((long) offer.goodsCount(), terms.executions()),
            prior == null ? new Pricing.Demand(Rational.ZERO, 0) : prior.demand(),
            tick,
            terms.reputation(),
            terms.comparisons());
    var calculated = ReferencePricing.quote(priceRequest, terms.source().ref().implementation());
    if (!calculated.available()) throw new IllegalArgumentException(calculated.reason());
    var pricing =
        new PricingSnapshot(
            market.id(),
            binding.revision(),
            stock.revision(),
            prior == null ? 0 : prior.revision(),
            ticks == null ? 0 : ticks.revision(),
            tick,
            priceRequest,
            calculated.quote());
    var supplier = supplier(reads, world, market.id());
    NpcCashFx.Quote conversion = null;
    if (terms.cashCurrency() != market.currency()) {
      if (fx == null) throw new IllegalArgumentException("Cash FX unavailable");
      conversion =
          fx.issue(
              reads,
              world,
              terms.actor(),
              supplier,
              offer.side(),
              new Money(market.currency(), calculated.quote().minor()),
              terms.cashCurrency(),
              now,
              expiry);
      if (conversion.expiresAt().isBefore(expiry)) expiry = conversion.expiresAt();
    }
    long required =
        offer.side() == Pricing.Side.SELL
            ? 0
            : conversion == null
                ? calculated.quote().minor()
                : Math.addExact(
                    conversion.conversion().debit().minorUnits(),
                    conversion.pricing().fee().minorUnits());
    if (totals.total() < required) throw new IllegalArgumentException("Cash input insufficient");
    long output =
        offer.side() == Pricing.Side.BUY
            ? totals.total() - required
            : conversion == null
                ? calculated.quote().minor()
                : conversion.conversion().credit().minorUnits();
    WithdrawalPlanner.Plan plan = null;
    List<CashIssuance.Lot> outputLots = List.of();
    if (output > 0) {
      try {
        plan =
            WithdrawalPlanner.plan(
                terms.cashCurrency(),
                output,
                false,
                CashDenominations.forCurrency(terms.cashCurrency()));
      } catch (IllegalArgumentException impossible) {
        throw new IllegalArgumentException(
            "CASH_EXACT_CHANGE_UNAVAILABLE: choose an exact cash batch or account rail",
            impossible);
      }
      if (plan.lines().size() > 24) throw new IllegalArgumentException("Cash lot bound");
      outputLots = lots(world, terms.operation(), plan);
    }
    var quote =
        new Quote(
            quoteId(world, terms.operation()),
            world,
            terms,
            pricing,
            supplier,
            conversion,
            totals.total(),
            totals.unmarked(),
            required,
            output,
            totals.lots(),
            plan,
            outputLots,
            CashDenominations.SHA256,
            now.toEpochMilli(),
            expiry.toEpochMilli(),
            financialIntent(world, terms.operation(), terms.actor()));
    preflight(reads, quote, now);
    if (!admission.allows(Phase.BOOK, terms, quote, reads))
      throw new IllegalArgumentException("Cash admission/capacity unavailable");
    return quote;
  }

  public Journal.Outcome settle(Journal.Intent intent) {
    return new JournalEngine(
            store,
            (a, k) -> a instanceof PlayerId && k.equals(SETTLE),
            Map.of(SETTLE, new JournalEngine.Registration(1, this::prepare)))
        .submit(intent);
  }

  private JournalEngine.Decision prepare(Journal.Intent original, JournalEngine.ReadView reads)
      throws Exception {
    try {
      UUID id = UUID.fromString(original.request().fields().get("quote"));
      if (!request(id).equals(original.request())) return reject("NPC_CASH_REQUEST");
      var quote = read(reads, id);
      if (quote == null
          || !quote.world().equals(store.snapshot().worldId())
          || !quote.terms().actor().equals(original.actor())
          || !quote.terms().operation().equals(original.operationId().value())
          || !quote.financialIntent().equals(Custody.financialIntent(original)))
        return reject("NPC_CASH_ORIGINAL");
      if (!ActivityStore.ready(reads)) return reject("NPC_CASH_ACTIVITY");
      if (!reads
          .query(
              "SELECT operation_id FROM reference_cash_receipts WHERE quote_id=?",
              List.of(id.toString()),
              1)
          .isEmpty()) return reject("NPC_CASH_CONSUMED");
      var now = wall.instant();
      validateCurrent(reads, quote, now, true);
      if (!admission.allows(Phase.SETTLE, quote.terms(), quote, reads))
        return reject("NPC_CASH_ADMISSION");
      var terms = quote.terms();
      var foreign =
          quote.fx() == null
              ? null
              : fx.prepare(reads, quote.fx(), quote.world(), terms.actor(), quote.supplier(), now);
      if (foreign != null) {
        var expected = expectedGroups(quote);
        int suffix =
            (quote.pricing().request().key().side() == Pricing.Side.BUY ? 1 : 0)
                + (quote.cashOutputMinor() > 0 ? 1 : 0);
        if (!foreign.groups().equals(expected.subList(1, expected.size() - suffix)))
          return reject("NPC_CASH_FX_PLAN_CHANGED");
      }
      var financial =
          NpcSettlement.prepare(
              reads,
              quote.world(),
              expectedGroups(quote),
              foreign == null ? List.of() : foreign.accounts());
      if (!(financial instanceof JournalEngine.Commit base)) return financial;
      if (!quote.outputLots().isEmpty()) {
        var issued = CashIssuance.composeIssuance(quote.world(), quote.outputLots(), base, reads);
        if (!(issued instanceof JournalEngine.Commit accepted)) return issued;
        base = accepted;
      }
      var claims =
          terms.cash().stream().filter(s -> !s.unmarked()).map(Deposits.Slot::claim).toList();
      if (!claims.isEmpty()) {
        var redeemed =
            CashIssuance.composeRedemption(
                quote.world(), claims, terms.inputCustody(), base, reads);
        if (!(redeemed instanceof JournalEngine.Commit accepted)) return redeemed;
        base = accepted;
      }
      var input = custody(reads, quote.world(), terms, Custody.State.WITNESSED);
      var financialBase = base;
      var child =
          new Journal.Intent(
              original.operationId(),
              original.requestId(),
              original.actor(),
              Custody.Action.COMMIT.kind(),
              Custody.request(
                  input.id(), null, Custody.Evidence.unknown(), "NPC cash original input"),
              null,
              null);
      var inputCommit =
          Custody.registration(
                  Custody.Action.COMMIT,
                  (a, i, c, r) -> c.id().equals(input.id()) && c.terms().equals(input.terms()),
                  (c, e, r) -> false,
                  (i, c, r) -> financialBase,
                  (i, c, r) -> true)
              .handler()
              .prepare(child, reads);
      if (!(inputCommit instanceof JournalEngine.Commit committedInput)) return inputCommit;
      var parts = new ArrayList<JournalEngine.Commit>();
      parts.add(committedInput);
      var offer =
          CatalogStore.read(reads, terms.trader(), terms.catalogRevision())
              .offers()
              .get(terms.offerOrdinal());
      if (terms.outputCustody() != null)
        parts.add(
            Custody.committedDelivery(
                original,
                terms.outputCustody(),
                terms.actor(),
                NpcTrades.outputItems(offer, terms.executions()),
                outputSource(terms),
                outputDestination(terms),
                reads));
      if (!quote.outputLots().isEmpty())
        parts.add(
            Custody.committedDelivery(
                original,
                terms.cashCustody(),
                terms.actor(),
                WithdrawalDelivery.items(quote.outputLots()),
                "npc-cash:" + terms.trader(),
                Journal.actorKey(terms.actor()),
                reads));
      parts.add(
          TradeSources.preparePriceTransition(
              reads,
              quote.world(),
              terms.operation(),
              quote.pricing().market(),
              quote.pricing().request().key().item(),
              quote.pricing().priceRevision(),
              quote.pricing().eligibleTick(),
              quote.pricing().price()));
      parts.add(
          StockStore.prepareTrade(
              reads,
              quote.world(),
              terms.operation(),
              terms.trader(),
              terms.catalogRevision(),
              quote.pricing().bindingRevision(),
              quote.pricing().stockRevision(),
              terms.offerOrdinal(),
              terms.executions()));
      var receipt = receipt(quote, now.toEpochMilli());
      String payload = encode(receipt), adapter = encode(new Adapter(quote.world(), SOURCES));
      boolean install =
          reads
              .query("SELECT world_id FROM reference_cash_adapter WHERE singleton=1", List.of(), 1)
              .isEmpty();
      var records = new ArrayList<Journal.Record>();
      for (var part : parts) records.addAll(part.records());
      if (foreign != null) records.addAll(foreign.facts());
      if (install) records.add(new Journal.Record(ADAPTER, 1, adapter));
      records.add(new Journal.Record(RECEIPT, 1, payload));
      if (records.size() > 63) return reject("NPC_CASH_RECORD_BUDGET");
      var foreignPlan = foreign;
      return new JournalEngine.Commit(
          records,
          new Journal.Result(1, "NPC_CASH_COMMITTED", id.toString()),
          tx -> {
            for (var part : parts) part.projections().apply(tx);
            if (foreignPlan != null) foreignPlan.registrations().apply(tx);
            for (var lot : quote.outputLots())
              tx.execute(
                  "INSERT INTO custody_cash_reservations VALUES(?,?,?,'ACTIVE')",
                  List.of(
                      terms.cashCustody().toString(),
                      lot.id().toString(),
                      Integer.toString(lot.issued())));
            if (install)
              tx.execute(
                  "INSERT INTO reference_cash_adapter VALUES(1,?,?,?)",
                  List.of(quote.world().value().toString(), terms.operation().toString(), adapter));
            tx.execute(
                "INSERT INTO reference_cash_receipts VALUES(?,?,?,?,?,?,?)",
                Arrays.asList(
                    terms.operation().toString(),
                    id.toString(),
                    quote.world().value().toString(),
                    Long.toString(receipt.effectiveMillis()),
                    quote.fx() == null ? null : quote.fx().conversion().id().toString(),
                    terms.inputCustody().toString(),
                    payload));
          });
    } catch (IllegalArgumentException | ArithmeticException stale) {
      return reject("NPC_CASH_STALE");
    }
  }

  private void validateCurrent(
      JournalEngine.ReadView reads, Quote q, Instant now, boolean settling) {
    if (now.toEpochMilli() < q.issuedMillis() || now.toEpochMilli() >= q.expiresMillis())
      throw new IllegalArgumentException("Cash quote expired");
    var t = q.terms();
    var b = MarketStore.binding(reads, t.trader());
    var s = StockStore.trader(reads, t.trader());
    var p = TradeSources.price(reads, q.pricing().market(), q.pricing().request().key().item());
    var ticks = StockStore.market(reads, q.pricing().market());
    if (b == null
        || !b.market().equals(q.pricing().market())
        || b.revision() != q.pricing().bindingRevision()
        || s == null
        || s.revision() != q.pricing().stockRevision()
        || s.catalogRevision() != t.catalogRevision()
        || (p == null ? 0 : p.revision()) != q.pricing().priceRevision()
        || (ticks == null ? 0 : ticks.revision()) != q.pricing().marketTickRevision()
        || (ticks == null ? 0 : ticks.eligibleTicks()) != q.pricing().eligibleTick()
        || !supplier(reads, q.world(), q.pricing().market()).equals(q.supplier()))
      throw new IllegalArgumentException("Cash quote revisions changed");
    var capture = CatalogStore.read(reads, t.trader(), t.catalogRevision());
    if (capture == null || t.offerOrdinal() >= capture.offers().size())
      throw new IllegalArgumentException("Reference cash catalog changed");
    var offer = capture.offers().get(t.offerOrdinal());
    t.source().validate(reads, offer);
    if (!t.comparisons().equals(t.source().comparisons(reads, capture, offer)))
      throw new IllegalArgumentException("Reference cash comparisons changed");
    var publication = FxService.at(reads, now.toEpochMilli());
    if (publication == null || !publication.id().equals(t.source().fxSnapshot()))
      throw new IllegalArgumentException("Cash source FX changed");
    var calculated =
        ReferencePricing.quote(q.pricing().request(), t.source().ref().implementation());
    if (!calculated.available() || !calculated.quote().equals(q.pricing().price()))
      throw new IllegalArgumentException("Cash pricing changed");
    outputsAbsent(reads, t);
    custody(reads, q.world(), t, settling ? Custody.State.WITNESSED : Custody.State.RESERVED);
    var value = validateCash(reads, q.world(), t, settling);
    if (value.total() != q.inputCashMinor()
        || value.unmarked() != q.operatorCashMinor()
        || !value.lots().equals(q.inputLots()))
      throw new IllegalArgumentException("Cash originals changed");
    if (q.fx() != null) {
      if (fx == null) throw new IllegalArgumentException("Cash FX unavailable");
      fx.requireFresh(reads, q.fx(), q.world(), t.actor(), q.supplier(), now);
    }
  }

  private static void outputsAbsent(JournalEngine.ReadView reads, Terms t) {
    for (UUID id : Arrays.asList(t.outputCustody(), t.cashCustody()))
      if (id != null && Custody.find(reads, t.actor(), id, true).isPresent())
        throw new IllegalArgumentException("Future output custody already exists");
  }

  /** Permanently close the original financial intent before authorizing an exact input return. */
  public Custody.Case returnInputs(UUID quoteId, boolean knownHeld, Custody.Evidence evidence)
      throws Exception {
    Quote q = store.transaction(tx -> read(tx::query, quoteId));
    if (q == null) throw new IllegalArgumentException("Cash quote missing");
    var original = intent(q.world(), q.terms().operation(), q.terms().actor());
    var terminal =
        new JournalEngine(
                store,
                (a, k) -> a.equals(q.terms().actor()) && k.equals(SETTLE),
                Map.of(
                    SETTLE,
                    new JournalEngine.Registration(
                        1,
                        (i, r) -> {
                          var input =
                              Custody.find(r, q.terms().actor(), q.terms().inputCustody(), false)
                                  .orElse(null);
                          if (input == null
                              || input.financialOperation() != null
                              || !input.terms().items().equals(q.terms().exactInputItems())
                              || !input.terms().financialIntent().equals(q.financialIntent())
                              || !evidence.location().equals(input.terms().source())
                              || knownHeld && evidence.kind() != Custody.EvidenceKind.UNDELIVERED
                              || !admission.allows(Phase.RETURN, q.terms(), q, r))
                            return new JournalEngine.Decline(
                                new Journal.Result(1, "NPC_CASH_RETURN_ADMISSION", ""));
                          return reject("NPC_CASH_CANCELLED");
                        })))
            .submit(original);
    if (terminal.status() != Journal.Status.DURABLY_REJECTED)
      throw new IllegalArgumentException(
          "Original cash financial outcome must be conclusively rejected before returning inputs");
    var kind = new Journal.Kind("economy:a9", "economy:a9/reference-cash/return");
    UUID operation = stable("return|" + q.world().value() + "|" + q.terms().operation()),
        returned = stable("return-custody|" + q.world().value() + "|" + q.terms().operation());
    var fact =
        new Return(
            q.world(),
            operation,
            q.id(),
            q.terms().operation(),
            q.terms().inputCustody(),
            returned,
            knownHeld,
            evidence);
    String payload = encode(fact);
    var request = new Journal.CanonicalRequest(1, Map.of("return", TradeCatalog.digest(payload)));
    var registration =
        new JournalEngine.Registration(
            1,
            (i, r) -> {
              if (!request.equals(i.request()) || !admission.allows(Phase.RETURN, q.terms(), q, r))
                return reject("NPC_CASH_RETURN_ADMISSION");
              var input =
                  Custody.find(r, q.terms().actor(), q.terms().inputCustody(), false)
                      .orElseThrow(
                          () -> new IllegalArgumentException("Original input custody missing"));
              if (input.financialOperation() != null
                  || !input.terms().items().equals(q.terms().exactInputItems())
                  || !input.terms().financialIntent().equals(q.financialIntent())
                  || !evidence.location().equals(input.terms().source()))
                return reject("NPC_CASH_RETURN_INPUT");
              JournalEngine.Commit quarantine = null;
              if (input.state() != Custody.State.QUARANTINED) {
                var child =
                    new Journal.Intent(
                        i.operationId(),
                        i.requestId(),
                        i.actor(),
                        Custody.Action.QUARANTINE.kind(),
                        Custody.request(
                            input.id(),
                            null,
                            evidence,
                            "NPC cash original closed; return requires independent held evidence"),
                        null,
                        null);
                var decision =
                    Custody.registration(
                            Custody.Action.QUARANTINE,
                            (a, in, c, rs) -> c.id().equals(input.id()),
                            (c, e, rs) -> false,
                            null)
                        .handler()
                        .prepare(child, r);
                if (!(decision instanceof JournalEngine.Commit accepted)) return decision;
                quarantine = accepted;
              }
              var claim =
                  Custody.returnCase(
                      i,
                      returned,
                      input.id(),
                      q.terms().actor(),
                      input.terms().items(),
                      input.terms().source(),
                      evidence,
                      "NPC cash exact original input return",
                      knownHeld ? Custody.State.CLAIM : Custody.State.QUARANTINED,
                      r);
              if (claim == null) return reject("NPC_CASH_RETURN_EXISTS");
              var records = new ArrayList<Journal.Record>();
              if (quarantine != null) records.addAll(quarantine.records());
              records.addAll(claim.records());
              records.add(new Journal.Record(RETURN, 1, payload));
              var protectedInput = quarantine;
              return new JournalEngine.Commit(
                  records,
                  new Journal.Result(1, "NPC_CASH_INPUT_RETURN", returned.toString()),
                  tx -> {
                    if (protectedInput != null) protectedInput.projections().apply(tx);
                    claim.projections().apply(tx);
                    if (knownHeld) {
                      var locks =
                          tx.query(
                              "SELECT lot_id,count FROM custody_cash_reservations WHERE custody_id=? AND state='ACTIVE' ORDER BY lot_id LIMIT 25",
                              List.of(input.id().toString()),
                              25);
                      if (locks.size() > 24)
                        throw new IllegalArgumentException("Cash return reservation bound");
                      for (var lock : locks)
                        tx.execute(
                            "INSERT INTO custody_cash_reservations VALUES(?,?,?,'ACTIVE')",
                            List.of(returned.toString(), lock.get("lot_id"), lock.get("count")));
                      tx.execute(
                          "UPDATE custody_cash_reservations SET state='RELEASED' WHERE custody_id=? AND state='ACTIVE'",
                          List.of(input.id().toString()));
                    }
                    tx.execute(
                        "INSERT INTO reference_cash_returns VALUES(?,?,?,?,?,?)",
                        List.of(
                            operation.toString(),
                            q.id().toString(),
                            q.world().value().toString(),
                            input.id().toString(),
                            returned.toString(),
                            payload));
                  });
            });
    var outcome =
        new JournalEngine(
                store,
                (a, k) -> a.equals(q.terms().actor()) && k.equals(kind),
                Map.of(kind, registration))
            .submit(
                new Journal.Intent(
                    new OperationId(operation),
                    new RequestId(operation),
                    q.terms().actor(),
                    kind,
                    request,
                    null,
                    null));
    if (outcome.status() != Journal.Status.COMMITTED)
      throw new IllegalArgumentException("Cash return: " + outcome.result().code());
    return store.transaction(
        tx -> Custody.find(tx::query, q.terms().actor(), returned, false).orElseThrow());
  }

  private record CashValue(long total, long unmarked, List<CashIssuance.Lot> lots) {}

  private static CashValue validateCash(
      JournalEngine.ReadView reads, WorldId world, Terms t, boolean own) {
    long total = 0, unmarked = 0;
    var lots = new TreeMap<String, CashIssuance.Lot>();
    for (var slot : t.cash()) {
      var denomination = CashDenominations.require(slot.claim().item());
      if (denomination.currency() != t.cashCurrency() || !slot.claim().world().equals(world))
        throw new IllegalArgumentException("CASH_SINGLE_SELECTED_CURRENCY_REQUIRED");
      long value = Math.multiplyExact(denomination.minorUnits(), slot.claim().count());
      total = Math.addExact(total, value);
      var lot = CashIssuance.find(reads, world, slot.claim().lot()).orElse(null);
      if (slot.unmarked()) {
        if (lot != null) throw new IllegalArgumentException("Unmarked cash has tracked identity");
        unmarked = Math.addExact(unmarked, value);
      } else {
        if (lot == null
            || lot.state() != CashIssuance.State.ACTIVE
            || !lot.item().equals(slot.claim().item()))
          throw new IllegalArgumentException("Marked original cash unavailable");
        lots.put(lot.id().toString(), lot);
      }
    }
    for (var entry : markedQuantities(t.cash()).entrySet()) {
      var lot = lots.get(entry.getKey().toString());
      long reserved = CashIssuance.reserved(reads, lot.id(), null);
      if (own) {
        var rows =
            reads.query(
                "SELECT count FROM custody_cash_reservations WHERE custody_id=? AND lot_id=? AND state='ACTIVE'",
                List.of(t.inputCustody().toString(), lot.id().toString()),
                1);
        if (rows.size() != 1 || Long.parseLong(rows.getFirst().get("count")) != entry.getValue())
          throw new IllegalArgumentException("Original cash reservation mismatch");
        reserved -= entry.getValue();
      }
      if (lot.remaining() - reserved < entry.getValue())
        throw new IllegalArgumentException("Cash original quantity reserved");
    }
    return new CashValue(total, unmarked, List.copyOf(lots.values()));
  }

  static Map<UUID, Integer> markedQuantities(List<Deposits.Slot> slots) {
    var values = new LinkedHashMap<UUID, Integer>();
    for (var slot : slots)
      if (!slot.unmarked()) values.merge(slot.claim().lot(), slot.claim().count(), Math::addExact);
    return values;
  }

  private static Custody.Case custody(
      JournalEngine.ReadView reads, WorldId world, Terms t, Custody.State state) {
    var c =
        Custody.find(reads, t.actor(), t.inputCustody(), false)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Original input reservation required before cash quote"));
    if (c.state() != state
        || !c.terms().owner().equals(t.actor())
        || !c.terms().items().equals(t.exactInputItems())
        || !c.terms().source().equals(inputSource(t))
        || !c.terms().destination().equals(inputDestination(t))
        || !c.terms().financialIntent().equals(financialIntent(world, t.operation(), t.actor())))
      throw new IllegalArgumentException("Exact saved cash/goods custody mismatch");
    return c;
  }

  public static InstitutionCatalog.InstitutionId supplier(
      JournalEngine.ReadView reads, WorldId world, UUID marketId) {
    var m = MarketStore.market(reads, marketId);
    if (m == null) throw new IllegalArgumentException("Unknown cash market");
    if (m.kind() == MarketFacts.Kind.VILLAGE) {
      var plans =
          reads.query(
              "SELECT plan FROM village_plans WHERE world_id=? AND village_id=?",
              List.of(world.value().toString(), marketId.toString()),
              1);
      if (!plans.isEmpty()) {
        var plan =
            ca.emilybelnavis.mcmods.economy.village.BranchPlans.decode(
                plans.getFirst().get("plan"));
        var first = plan.branches().stream().filter(b -> b.slot() == 0).findFirst();
        if (first.isPresent() && first.get().institution() != null)
          return new InstitutionCatalog.InstitutionId(first.get().institution());
      }
      var b =
          Branches.find(
              reads,
              world,
              ca.emilybelnavis.mcmods.economy.village.BranchPlans.branchId(marketId, 0));
      if (b.isPresent() && b.get().choice().institution() != null)
        return new InstitutionCatalog.InstitutionId(b.get().choice().institution());
    }
    return new InstitutionCatalog.InstitutionId("economy:bank/crownstone");
  }

  public static List<CashIssuance.Lot> lots(
      WorldId world, UUID original, WithdrawalPlanner.Plan plan) {
    var values = new ArrayList<CashIssuance.Lot>();
    for (var line : plan.lines())
      values.add(
          new CashIssuance.Lot(
              world,
              stable("lot|" + world.value() + "|" + original + "|" + line.denomination().id()),
              line.denomination().id(),
              line.count(),
              line.count(),
              CashIssuance.State.ACTIVE,
              1,
              "npc-cash:" + original));
    return List.copyOf(values);
  }

  public static Receipt receipt(Quote q, long effective) {
    var t = q.terms();
    var f = q.fx();
    return new Receipt(
        q.world(),
        t.operation(),
        q.id(),
        t.actor(),
        t.trader(),
        q.pricing().market(),
        q.pricing().request().key().side(),
        q.pricing().request().basis().currency(),
        q.pricing().price().minor(),
        effective,
        f == null ? null : f.conversion().pair().source(),
        f == null ? 0 : f.conversion().debit().minorUnits(),
        f == null ? null : f.conversion().pair().target(),
        f == null ? 0 : f.conversion().credit().minorUnits(),
        f == null ? null : f.conversion().publication(),
        f == null ? 0 : f.pricing().fee().minorUnits(),
        t.inputCustody(),
        t.outputCustody(),
        q.outputLots().isEmpty() ? null : t.cashCustody(),
        t.cashCurrency(),
        q.inputCashMinor(),
        q.cashOutputMinor(),
        q.operatorCashMinor());
  }

  public static List<NpcSettlement.Group> expectedGroups(Quote q) {
    var t = q.terms();
    var c = q.pricing().request().basis().currency();
    var side = q.pricing().request().key().side();
    long goods = q.pricing().price().minor();
    var out = new ArrayList<NpcSettlement.Group>();
    if (q.inputCashMinor() > 0) out.add(issue(q.world(), t.cashCurrency(), q.inputCashMinor()));
    if (side == Pricing.Side.SELL) out.add(issue(q.world(), c, goods));
    if (q.fx() != null) {
      var f = q.fx();
      var from = f.conversion().pair().source();
      var to = f.conversion().pair().target();
      long fee = f.pricing().fee().minorUnits();
      if (fee > 0) {
        var bank = f.supplier();
        var depositor =
            new InstitutionCatalog.InstitutionId("economy:depositor/" + t.actor().value());
        out.add(
            new NpcSettlement.Group(
                PostingBatch.Authority.TRANSFER,
                List.of(
                    leg(NpcSettlement.bridge(q.world(), from), NPC_BRIDGE, from, -fee),
                    leg(
                        FinancialAccounts.id(q.world(), bank, FinancialAccounts.Role.CASH, from),
                        INSTITUTION_CASH,
                        from,
                        fee))));
        out.add(
            new NpcSettlement.Group(
                PostingBatch.Authority.TRANSFER,
                List.of(
                    leg(
                        FinancialAccounts.id(
                            q.world(), depositor, FinancialAccounts.Role.EXPENSE, from),
                        INSTITUTION_CLAIM,
                        from,
                        fee),
                    leg(
                        FinancialAccounts.id(q.world(), bank, FinancialAccounts.Role.INCOME, from),
                        INSTITUTION_CLAIM,
                        from,
                        -fee))));
      }
      out.add(
          new NpcSettlement.Group(
              PostingBatch.Authority.FX,
              List.of(
                  leg(
                      NpcSettlement.bridge(q.world(), from),
                      NPC_BRIDGE,
                      from,
                      -f.conversion().debit().minorUnits()),
                  leg(
                      Settlement.clearingAccount(from),
                      CLEARING,
                      from,
                      f.conversion().debit().minorUnits()),
                  leg(
                      Settlement.clearingAccount(to),
                      CLEARING,
                      to,
                      -f.conversion().credit().minorUnits()),
                  leg(
                      NpcSettlement.bridge(q.world(), to),
                      NPC_BRIDGE,
                      to,
                      f.conversion().credit().minorUnits()))));
    }
    if (side == Pricing.Side.BUY) out.add(redeem(q.world(), c, goods));
    if (q.cashOutputMinor() > 0) out.add(redeem(q.world(), t.cashCurrency(), q.cashOutputMinor()));
    return List.copyOf(out);
  }

  private static NpcSettlement.Group issue(WorldId w, EconomyCurrency c, long n) {
    return new NpcSettlement.Group(
        PostingBatch.Authority.ISSUE,
        List.of(
            leg(Settlement.issuanceAccount(c), ISSUANCE, c, -n),
            leg(NpcSettlement.bridge(w, c), NPC_BRIDGE, c, n)));
  }

  private static NpcSettlement.Group redeem(WorldId w, EconomyCurrency c, long n) {
    return new NpcSettlement.Group(
        PostingBatch.Authority.REDEEM,
        List.of(
            leg(NpcSettlement.bridge(w, c), NPC_BRIDGE, c, -n),
            leg(Settlement.issuanceAccount(c), ISSUANCE, c, n)));
  }

  private static PostingBatch.Leg leg(UUID id, PostingBatch.Role role, EconomyCurrency c, long n) {
    return new PostingBatch.Leg(id, role, c, new Money(c, n));
  }

  private void preflight(JournalEngine.ReadView reads, Quote q, Instant now) {
    var groups = expectedGroups(q);
    var foreign =
        q.fx() == null
            ? null
            : fx.prepare(reads, q.fx(), q.world(), q.terms().actor(), q.supplier(), now);
    var decision =
        NpcSettlement.prepare(
            reads, q.world(), groups, foreign == null ? List.of() : foreign.accounts());
    if (!(decision instanceof JournalEngine.Commit money))
      throw new IllegalArgumentException("Cash financial plan unavailable");
    var records = new ArrayList<Journal.Record>(money.records());
    if (foreign != null) records.addAll(foreign.facts());
    records.addAll(
        TradeSources.preparePriceTransition(
                reads,
                q.world(),
                q.terms().operation(),
                q.pricing().market(),
                q.pricing().request().key().item(),
                q.pricing().priceRevision(),
                q.pricing().eligibleTick(),
                q.pricing().price())
            .records());
    records.addAll(
        StockStore.prepareTrade(
                reads,
                q.world(),
                q.terms().operation(),
                q.terms().trader(),
                q.terms().catalogRevision(),
                q.pricing().bindingRevision(),
                q.pricing().stockRevision(),
                q.terms().offerOrdinal(),
                q.terms().executions())
            .records());
    records.add(new Journal.Record(RECEIPT, 1, encode(receipt(q, q.issuedMillis()))));
    records.add(new Journal.Record(ADAPTER, 1, encode(new Adapter(q.world(), SOURCES))));
    int
        custodys =
            1 + (q.terms().outputCustody() == null ? 0 : 1) + (q.outputLots().isEmpty() ? 0 : 1),
        cashFacts = q.inputLots().size() + q.outputLots().size();
    int count = records.size() + cashFacts + custodys + 1;
    long bytes = 4096L + cashFacts * 1024L + (custodys + 1) * 16384L;
    for (var record : records)
      bytes +=
          record.type().length() + 64L + record.payload().getBytes(StandardCharsets.UTF_8).length;
    if (count > 64 || bytes > 131072)
      throw new IllegalArgumentException(
          "CASH_BATCH_BUDGET: choose a smaller supported cash batch before reserving/removing inputs");
  }

  private static JournalEngine.Reject reject(String code) {
    return new JournalEngine.Reject(new Journal.Result(1, code, ""));
  }
}
