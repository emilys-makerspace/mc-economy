package ca.emilybelnavis.mcmods.economy.trade.pricing;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.trade.catalog.*;
import ca.emilybelnavis.mcmods.economy.trade.market.*;
import ca.emilybelnavis.mcmods.economy.valuation.*;
import com.google.gson.*;
import java.lang.reflect.Type;
import java.util.*;

/** Detached published reference unit and complete selected valuation result/proof. */
public record ReferenceSource(
    SourceRef ref,
    UUID fxSnapshot,
    EconomyCurrency marketCurrency,
    long unitMinor,
    String itemId,
    String canonicalComponents,
    RecipeValues.Result valuation,
    Projection projection)
    implements TradePriceSource {
  public record Projection(String adapter, String components) {
    public Projection {
      if (!"economy:native-reference/v1".equals(adapter))
        throw new IllegalArgumentException("Native projection version");
      components = ValuationIdentity.canonical(components);
    }
  }

  public ReferenceSource(
      SourceRef ref,
      UUID fx,
      EconomyCurrency currency,
      long unit,
      String item,
      String components,
      RecipeValues.Result value) {
    this(ref, fx, currency, unit, item, components, value, null);
  }

  /** Omit absent extension for retained A9-05 payloads. */
  public static final class Codec
      implements JsonSerializer<ReferenceSource>, JsonDeserializer<ReferenceSource> {
    private static final Gson JSON =
        new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    public JsonElement serialize(
        ReferenceSource value, Type type, JsonSerializationContext context) {
      var out = JSON.toJsonTree(value).getAsJsonObject();
      if (value.projection() == null) out.remove("projection");
      return out;
    }

    public ReferenceSource deserialize(
        JsonElement value, Type type, JsonDeserializationContext context) {
      return JSON.fromJson(value, ReferenceSource.class);
    }
  }

  public ReferenceSource {
    Objects.requireNonNull(ref);
    Objects.requireNonNull(fxSnapshot);
    Objects.requireNonNull(marketCurrency);
    Objects.requireNonNull(valuation);
    canonicalComponents = ValuationIdentity.canonical(canonicalComponents);
    ValuationIdentity.itemId(itemId);
    if (unitMinor < 1
        || valuation.status() != RecipeValues.Status.READY
        || valuation.unitMoney().currency() != marketCurrency
        || valuation.unitMoney().minorUnits() != unitMinor
        || !valuation.totalMoney().equals(valuation.unitMoney())
        || !fxSnapshot.equals(valuation.fxPublication())
        || !valuation.configHash().equals(ref.configHash())
        || projection != null && !ReferencePricing.V2.equals(ref.implementation()))
      throw new IllegalArgumentException("Complete reference unit/proof required");
    var goods =
        new TradeCatalog.Goods(itemId, TradeCatalog.Match.EXACT_COMPONENTS, canonicalComponents);
    if (!goods.priceKey().equals(ref.item()))
      throw new IllegalArgumentException("Exact source scope mismatch");
  }

  public UUID source() {
    return ref.source();
  }

  public int revision() {
    return ref.revision();
  }

  @Override
  public Basis basis(long emeralds, long goods, int standardQuantity) {
    if (emeralds < 1 || goods < 1 || standardQuantity < 1)
      throw new IllegalArgumentException("Original offer provenance");
    return new Basis(
        source(),
        revision(),
        fxSnapshot,
        marketCurrency,
        Rational.of(unitMinor).multiply(Rational.of(standardQuantity)));
  }

  public static ReferenceSource capture(
      SourceRef ref,
      ValuationStore.Snapshot snapshot,
      String item,
      String components,
      EconomyCurrency currency,
      EffectiveFxSnapshot fx) {
    var manifest = ValuationData.decode(snapshot.payload());
    var result =
        RecipeValues.evaluate(
            manifest, item, ValuationIdentity.canonical(components), 1, currency, fx);
    if (result.status() != RecipeValues.Status.READY)
      throw new IllegalArgumentException("Reference value unavailable: " + result.reason());
    return new ReferenceSource(
        ref, fx.id(), currency, result.unitMoney().minorUnits(), item, components, result);
  }

  public static ReferenceSource captureNative(
      SourceRef ref,
      ValuationStore.Snapshot snapshot,
      TradeCatalog.Goods goods,
      String projected,
      EconomyCurrency currency,
      EffectiveFxSnapshot fx) {
    var manifest = ValuationData.decode(snapshot.payload());
    var projection = new Projection("economy:native-reference/v1", projected);
    var value =
        RecipeValues.evaluate(manifest, goods.item(), projection.components(), 1, currency, fx);
    if (value.status() != RecipeValues.Status.READY)
      throw new IllegalArgumentException(value.reason());
    return new ReferenceSource(
        ref,
        fx.id(),
        currency,
        value.unitMoney().minorUnits(),
        goods.item(),
        goods.components(),
        value,
        projection);
  }

  public void validate(JournalEngine.ReadView reads, TradeCatalog.Offer offer) {
    TradeSources.requireCurrent(reads, ref);
    validateProof(reads, offer);
    var active =
        reads.query("SELECT manifest_id FROM valuation_active WHERE singleton=1", List.of(), 1);
    if (active.isEmpty()
        || !active.getFirst().get("manifest_id").equals(ref.valuationManifest().toString()))
      throw new IllegalArgumentException("Reference valuation epoch changed");
  }

  public void validateHistorical(
      JournalEngine.ReadView reads, TradeCatalog.Offer offer, long sequence) {
    TradeSources.requireAt(reads, ref, sequence);
    validateProof(reads, offer);
  }

  private void validateProof(JournalEngine.ReadView reads, TradeCatalog.Offer offer) {
    if (offer.goods().match() != TradeCatalog.Match.EXACT_COMPONENTS
        || !offer.goods().item().equals(itemId)
        || !offer.goods().components().equals(canonicalComponents)
        || !offer.goods().priceKey().equals(ref.item()))
      throw new IllegalArgumentException("Exact merchandise identity changed");
    var market = MarketStore.market(reads, ref.market());
    if (market == null
        || !market.world().equals(ref.world())
        || market.currency() != marketCurrency)
      throw new IllegalArgumentException("Reference currency/world");
    var saved = ValuationStore.load(reads, ref.valuationManifest());
    if (saved == null || !saved.hash().equals(valuation.manifestHash()))
      throw new IllegalArgumentException("Reference manifest unavailable");
    var manifest = ValuationData.decode(saved.payload());
    if (!manifest.configHash().equals(ref.configHash())
        || !manifest.inputs().recipeHash().equals(ref.recipeHash())
        || !manifest.providerHash().equals(ref.providerHash()))
      throw new IllegalArgumentException("Reference input versions changed");
    var fx = FxService.snapshot(reads, fxSnapshot);
    if (fx == null
        || !RecipeValues.evaluate(
                manifest,
                itemId,
                projection == null ? canonicalComponents : projection.components(),
                1,
                marketCurrency,
                fx)
            .equals(valuation)) throw new IllegalArgumentException("Reference proof changed");
  }

  public List<Pricing.Comparison> comparisons(
      JournalEngine.ReadView reads, TradeCatalog.Capture selected, TradeCatalog.Offer offer) {
    return comparisonsHistorical(reads, selected, offer, Long.MAX_VALUE);
  }

  public List<Pricing.Comparison> comparisonsHistorical(
      JournalEngine.ReadView reads,
      TradeCatalog.Capture selected,
      TradeCatalog.Offer offer,
      long sequence) {
    // Latest historical binding/catalog per trader. Never treat an arbitrary caller-supplied
    // subset as a complete cohort. Exhaustion disables admission instead of truncating it.
    var bindings =
        reads.query(
            "SELECT binding_trader,payload FROM trade_market_events e WHERE binding_trader<>'' AND sequence<? AND NOT EXISTS(SELECT 1 FROM trade_market_events n WHERE n.binding_trader=e.binding_trader AND n.sequence>e.sequence AND n.sequence<?) ORDER BY binding_trader LIMIT 65",
            List.of(Long.toString(sequence), Long.toString(sequence)),
            65);
    if (bindings.size() > 64) throw new IllegalArgumentException("Reference cohort binding limit");
    Rational ask = null, bid = null;
    boolean found = false;
    int count = 0;
    for (var row : bindings) {
      var binding = MarketFacts.decode(row.get("payload")).binding();
      if (!binding.market().equals(ref.market())) continue;
      var heads =
          reads.query(
              "SELECT c.revision FROM trade_catalogs c JOIN journal_transactions t ON t.operation_id=c.operation_id WHERE c.trader_id=? AND t.sequence<? ORDER BY c.revision DESC LIMIT 1",
              List.of(binding.trader().toString(), Long.toString(sequence)),
              1);
      if (heads.isEmpty()) throw new IllegalArgumentException("Reference cohort catalog missing");
      var capture =
          CatalogStore.read(
              reads, binding.trader(), Long.parseLong(heads.getFirst().get("revision")));
      for (var candidate : capture.offers()) {
        if (!candidate.goods().equals(offer.goods())) continue;
        if (!TradeCatalog.equivalent(offer, candidate))
          throw new IllegalArgumentException("Unresolved secondary comparison");
        if (++count > 32) throw new IllegalArgumentException("Reference equivalent cohort limit");
        if (binding.trader().equals(selected.trader())
            && capture.revision() == selected.revision()
            && candidate.equals(offer)) found = true;
        Rational original =
            Rational.of(candidate.emeraldCount()).divide(Rational.of(candidate.goodsCount()));
        if (candidate.side() == Pricing.Side.BUY) ask = ask == null ? original : ask.min(original);
        else bid = bid == null ? original : bid.max(original);
      }
    }
    if (!found)
      throw new IllegalArgumentException(
          "Selected reference offer is not current in complete cohort");
    Rational ratio =
        ask == null || bid == null ? Rational.of("0.8") : bid.divide(ask).min(Rational.of("0.8"));
    Rational principal = Rational.of(unitMinor).multiply(Rational.of(offer.standardQuantity()));
    return List.of(new Pricing.Comparison(ref.item(), principal, principal.multiply(ratio)));
  }
}
