package ca.emilybelnavis.mcmods.economy.trade.pricing;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.trade.catalog.*;
import ca.emilybelnavis.mcmods.economy.trade.entity.NativeCatalog;
import ca.emilybelnavis.mcmods.economy.trade.market.*;
import ca.emilybelnavis.mcmods.economy.trade.stock.*;
import ca.emilybelnavis.mcmods.economy.valuation.*;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.npc.AbstractVillager;

/** Explicit operator activation and detached production selection. No startup activation. */
public final class ReferenceTrading {
  private ReferenceTrading() {}

  public static final String TYPE = "economy:a9/reference-activation";
  public static final Journal.Kind ACTIVATE =
      new Journal.Kind("economy:a9", "economy:a9/reference-activation");
  private static final Gson JSON =
      new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

  private record Projected(UUID snapshot, String components) {}

  private static final Map<WorldId, java.util.concurrent.atomic.AtomicLong> GENERATIONS =
      new ConcurrentHashMap<>();

  public static long generation(WorldId world) {
    var value = GENERATIONS.get(world);
    return value == null ? 0 : value.get();
  }

  private static final Map<WorldId, Map<String, Projected>> PROJECTIONS = new ConcurrentHashMap<>();

  private record CoverageKey(UUID snapshot, UUID fx, EconomyCurrency currency) {}

  private static final Map<CoverageKey, ValuationCoverage.Report> COVERAGE =
      new LinkedHashMap<>(16, .75f, true);

  public record OfferReview(int ordinal, String item, boolean available, String reason) {}

  public record Review(
      UUID snapshot,
      UUID trader,
      long catalogRevision,
      UUID market,
      String hash,
      ValuationCoverage.Report registry,
      List<OfferReview> offers) {
    public Review {
      offers = List.copyOf(offers);
      if (offers.isEmpty() || offers.size() > 32)
        throw new IllegalArgumentException("Review offer bound");
    }
  }

  public record Activation(
      UUID world,
      String actor,
      UUID operation,
      UUID trader,
      UUID market,
      UUID snapshot,
      String reviewHash,
      String registrySummary,
      List<String> scopes,
      String implementation) {
    public Activation {
      Objects.requireNonNull(world);
      Objects.requireNonNull(operation);
      Objects.requireNonNull(trader);
      Objects.requireNonNull(market);
      Objects.requireNonNull(snapshot);
      scopes = List.copyOf(scopes);
      if (actor == null
          || actor.length() > 128
          || reviewHash == null
          || !reviewHash.matches("[0-9a-f]{64}")
          || registrySummary == null
          || registrySummary.length() > 512
          || scopes.isEmpty()
          || scopes.size() > 32
          || new HashSet<>(scopes).size() != scopes.size()
          || !ReferencePricing.V2.equals(implementation))
        throw new IllegalArgumentException("Activation identity");
      for (String scope : scopes) MarketFacts.key(scope);
    }
  }

  public static void capture(MinecraftServer server, AbstractVillager entity) {
    if (entity == null || !server.isSameThread()) return;
    var lease = ValuationRuntime.lease(server);
    var services = Economy.serverServices(server);
    if (lease == null || services == null) return;
    var values = new HashMap<String, Projected>();
    var offers = entity.getOffers();
    for (int i = 0; i < offers.size() && i < 32; i++)
      try {
        var offer = NativeCatalog.detachOffer(i, offers.get(i), entity.registryAccess());
        if (offer.side() == Pricing.Side.BUY
            && offer.goods().match() == TradeCatalog.Match.EXACT_COMPONENTS)
          values.put(
              offer.goods().priceKey(),
              new Projected(
                  lease.snapshot(), VariantIdentity.components(offers.get(i).getResult(), server)));
      } catch (RuntimeException unsupported) {
        /* Keep unsupported state unavailable. */
      }
    var captured = PROJECTIONS.computeIfAbsent(services.worldId(), k -> new ConcurrentHashMap<>());
    captured.putAll(values);
    while (captured.size() > 4096) captured.remove(captured.keySet().iterator().next());
  }

  public static void stop(WorldId world) {
    if (world != null) {
      PROJECTIONS.remove(world);
      GENERATIONS.remove(world);
    }
  }

  private static ReferenceSource source(
      JournalEngine.ReadView reads,
      WorldId world,
      TradeCatalog.Offer offer,
      SourceRef ref,
      long now) {
    if (offer.goods().match() != TradeCatalog.Match.EXACT_COMPONENTS)
      throw new IllegalArgumentException("EXACT_PREDICATE_REQUIRED");
    var saved = ValuationStore.load(reads, ref.valuationManifest());
    if (saved == null) throw new IllegalArgumentException("SNAPSHOT_UNAVAILABLE");
    var fx = FxService.at(reads, now);
    if (fx == null) throw new IllegalArgumentException("FX_UNAVAILABLE");
    var market = MarketStore.market(reads, ref.market());
    if (market == null || !market.world().equals(world))
      throw new IllegalArgumentException("MARKET_UNAVAILABLE");
    var json = JsonParser.parseString(offer.goods().components()).getAsJsonObject();
    if (json.has("values") || json.has("patch")) {
      var projected = PROJECTIONS.getOrDefault(world, Map.of()).get(offer.goods().priceKey());
      if (projected == null || !projected.snapshot().equals(saved.id()))
        throw new IllegalArgumentException("NATIVE_PROJECTION_UNAVAILABLE");
      return ReferenceSource.captureNative(
          ref, saved, offer.goods(), projected.components(), market.currency(), fx);
    }
    return ReferenceSource.capture(
        ref, saved, offer.goods().item(), offer.goods().components(), market.currency(), fx);
  }

  public static boolean policyActive(JournalEngine.ReadView reads, UUID trader) {
    return !reads
        .query(
            "SELECT operation_id FROM reference_activations WHERE trader=? LIMIT 1",
            List.of(trader.toString()),
            1)
        .isEmpty();
  }

  public static Map<Integer, ReferenceSource> sources(
      JournalEngine.ReadView reads, WorldId world, TradeCatalog.Capture catalog, long now) {
    var binding = MarketStore.binding(reads, catalog.trader());
    if (binding == null) return Map.of();
    var result = new TreeMap<Integer, ReferenceSource>();
    for (var offer : catalog.offers()) {
      var ref = TradeSources.current(reads, binding.market(), offer.goods().priceKey());
      if (ref == null) continue;
      try {
        var source = source(reads, world, offer, ref, now);
        source.validate(reads, offer);
        result.put(offer.ordinal(), source);
      } catch (RuntimeException unsupported) {
        /* Operator review retains reasons. */
      }
    }
    return Collections.unmodifiableMap(result);
  }

  public static boolean available(
      JournalEngine.ReadView reads,
      ReferenceSource source,
      TradeCatalog.Capture catalog,
      TradeCatalog.Offer offer,
      long now) {
    try {
      source.validate(reads, offer);
      return quoteAvailable(reads, source, catalog, offer);
    } catch (RuntimeException unsupported) {
      return false;
    }
  }

  private static boolean quoteAvailable(
      JournalEngine.ReadView reads,
      ReferenceSource source,
      TradeCatalog.Capture catalog,
      TradeCatalog.Offer offer) {
    var price = TradeSources.price(reads, source.ref().market(), offer.goods().priceKey());
    var ticks = StockStore.market(reads, source.ref().market());
    long tick = ticks == null ? 0 : ticks.eligibleTicks();
    var comparisons = source.comparisons(reads, catalog, offer);
    var basis =
        ReferencePricing.normalizedBasis(
            offer.side(),
            source.basis(offer.emeraldCount(), offer.goodsCount(), offer.standardQuantity()),
            comparisons.getFirst());
    var request =
        new ReferencePricing.Request(
            new Pricing.Key(
                source.ref().market(),
                offer.goods().priceKey(),
                new UUID(0, 0),
                offer.side(),
                source.ref().epoch()),
            basis,
            offer.standardQuantity(),
            offer.goodsCount(),
            price == null ? new Pricing.Demand(Rational.ZERO, 0) : price.demand(),
            tick,
            Rational.ZERO,
            comparisons);
    return ReferencePricing.quote(request, source.ref().implementation()).available();
  }

  public static Review review(FoundationStore store, WorldId world, UUID trader, UUID snapshot) {
    return store.transaction(
        tx -> review(tx::query, world, trader, snapshot, System.currentTimeMillis()));
  }

  private static Review review(
      JournalEngine.ReadView reads, WorldId world, UUID trader, UUID snapshot, long now) {
    var active =
        reads.query("SELECT manifest_id FROM valuation_active WHERE singleton=1", List.of(), 1);
    if (active.isEmpty() || !active.getFirst().get("manifest_id").equals(snapshot.toString()))
      throw new IllegalArgumentException("VALUATION_NOT_CURRENT");
    var saved = ValuationStore.load(reads, snapshot);
    var manifest = ValuationData.decode(saved.payload());
    var fx = FxService.at(reads, now);
    if (fx == null) throw new IllegalArgumentException("FX_UNAVAILABLE");
    var binding = MarketStore.binding(reads, trader);
    var stock = StockStore.trader(reads, trader);
    if (binding == null || stock == null) throw new IllegalArgumentException("TRADER_NOT_CAPTURED");
    var market = MarketStore.market(reads, binding.market());
    if (market == null || !market.world().equals(world))
      throw new IllegalArgumentException("MARKET_WORLD");
    var catalog = CatalogStore.read(reads, trader, stock.catalogRevision());
    var coverageKey = new CoverageKey(snapshot, fx.id(), market.currency());
    ValuationCoverage.Report report;
    synchronized (COVERAGE) {
      report = COVERAGE.get(coverageKey);
    }
    if (report == null) {
      report = ValuationCoverage.report(manifest, fx, market.currency());
      synchronized (COVERAGE) {
        COVERAGE.put(coverageKey, report);
        while (COVERAGE.size() > 16) COVERAGE.remove(COVERAGE.keySet().iterator().next());
      }
    }
    var offers = new ArrayList<OfferReview>();
    for (var offer : catalog.offers()) {
      String reason = "AVAILABLE";
      try {
        var ref = preview(world, market.id(), offer.goods().priceKey(), snapshot, manifest);
        var source = source(reads, world, offer, ref, now);
        if (!quoteAvailable(reads, source, catalog, offer))
          reason = "ROUNDED_COHORT_SPREAD_UNAVAILABLE";
      } catch (RuntimeException unsupported) {
        reason = unsupported.getMessage() == null ? "UNSUPPORTED" : unsupported.getMessage();
        if (reason.length() > 160) reason = reason.substring(0, 160);
      }
      offers.add(
          new OfferReview(
              offer.ordinal(), offer.goods().item(), reason.equals("AVAILABLE"), reason));
    }
    String hash =
        ValuationData.hash(
            snapshot
                + "|"
                + trader
                + "|"
                + binding.revision()
                + "|"
                + CatalogFacts.digest(catalog)
                + "|"
                + fx.id()
                + "|"
                + JSON.toJson(report)
                + "|"
                + JSON.toJson(offers));
    return new Review(snapshot, trader, catalog.revision(), market.id(), hash, report, offers);
  }

  private static SourceRef preview(
      WorldId world, UUID market, String item, UUID snapshot, ValuationData.Manifest manifest) {
    UUID id =
        UUID.nameUUIDFromBytes(
            (world.value() + "|" + market + "|" + item).getBytes(StandardCharsets.UTF_8));
    return new SourceRef(
        world,
        market,
        item,
        id,
        1,
        id,
        snapshot,
        manifest.configHash(),
        manifest.inputs().recipeHash(),
        manifest.providerHash(),
        ReferencePricing.V2,
        ReferencePricing.ROUNDING,
        id);
  }

  public static Journal.Outcome activate(
      FoundationStore store,
      WorldId world,
      UUID trader,
      ActorId actor,
      UUID operation,
      Review reviewed,
      BooleanSupplier current) {
    Objects.requireNonNull(current);
    var request =
        new Journal.CanonicalRequest(
            1,
            Map.of(
                "trader",
                trader.toString(),
                "review",
                reviewed.hash(),
                "snapshot",
                reviewed.snapshot().toString(),
                "pricing",
                ReferencePricing.V2));
    var registration =
        new JournalEngine.Registration(
            1,
            (intent, reads) -> {
              if (!current.getAsBoolean())
                return new JournalEngine.Decline(new Journal.Result(1, "REFERENCE_NOT_READY", ""));
              var fresh =
                  review(reads, world, trader, reviewed.snapshot(), System.currentTimeMillis());
              if (!fresh.hash().equals(reviewed.hash()))
                return new JournalEngine.Reject(new Journal.Result(1, "REVIEW_CHANGED", ""));
              if (!fresh.registry().ordinaryComplete())
                return new JournalEngine.Reject(new Journal.Result(1, "VANILLA_NUMERIC_GAPS", ""));
              if (fresh.offers().stream().noneMatch(OfferReview::available))
                return new JournalEngine.Reject(
                    new Journal.Result(1, "NO_SUPPORTED_REFERENCE_LISTING", ""));
              var catalog = CatalogStore.read(reads, trader, fresh.catalogRevision());
              var manifest =
                  ValuationData.decode(ValuationStore.load(reads, fresh.snapshot()).payload());
              var scopes =
                  catalog.offers().stream()
                      .map(o -> o.goods().priceKey())
                      .distinct()
                      .sorted()
                      .toList();
              var existing =
                  reads.query("SELECT item,market_id FROM trade_source_active", List.of(), 64);
              long additional =
                  scopes.stream()
                      .filter(
                          key ->
                              existing.stream()
                                  .noneMatch(
                                      r ->
                                          r.get("market_id").equals(fresh.market().toString())
                                              && r.get("item").equals(key)))
                      .count();
              if (existing.size() + additional > 63)
                return new JournalEngine.Reject(new Journal.Result(1, "SOURCE_SCOPE_LIMIT", ""));
              var parts = new ArrayList<JournalEngine.Commit>();
              for (String key : scopes) {
                var prior = TradeSources.current(reads, fresh.market(), key);
                if (prior != null
                    && prior.implementation().equals(ReferencePricing.V2)
                    && prior.valuationManifest().equals(fresh.snapshot())) continue;
                var initial = preview(world, fresh.market(), key, fresh.snapshot(), manifest);
                var next =
                    new SourceRef(
                        world,
                        fresh.market(),
                        key,
                        prior == null ? initial.source() : prior.source(),
                        prior == null ? 1 : Math.addExact(prior.revision(), 1),
                        UUID.nameUUIDFromBytes(
                            (operation + "|" + key).getBytes(StandardCharsets.UTF_8)),
                        fresh.snapshot(),
                        manifest.configHash(),
                        manifest.inputs().recipeHash(),
                        manifest.providerHash(),
                        ReferencePricing.V2,
                        ReferencePricing.ROUNDING,
                        prior == null ? operation : prior.activation());
                parts.add(
                    TradeSources.prepareAdvance(
                        reads, next, prior, operation, System.currentTimeMillis()));
              }
              var fact =
                  new Activation(
                      world.value(),
                      Journal.actorKey(actor),
                      operation,
                      trader,
                      fresh.market(),
                      fresh.snapshot(),
                      fresh.hash(),
                      fresh.registry().summary(),
                      scopes,
                      ReferencePricing.V2);
              String payload = encode(fact);
              var records = new ArrayList<Journal.Record>();
              for (var part : parts) records.addAll(part.records());
              records.add(new Journal.Record(TYPE, 1, payload));
              if (!current.getAsBoolean())
                return new JournalEngine.Decline(new Journal.Result(1, "REFERENCE_NOT_READY", ""));
              return new JournalEngine.Commit(
                  records,
                  new Journal.Result(1, "REFERENCE_SOURCE_ACTIVATED", operation.toString()),
                  tx -> {
                    for (var part : parts) part.projections().apply(tx);
                    tx.execute(
                        "INSERT INTO reference_activations VALUES(?,?,?,?,?,?,?,?)",
                        List.of(
                            operation.toString(),
                            world.value().toString(),
                            Journal.actorKey(actor),
                            trader.toString(),
                            fresh.market().toString(),
                            fresh.snapshot().toString(),
                            fresh.hash(),
                            payload));
                  });
            });
    var outcome =
        new JournalEngine(
                store,
                (a, k) -> a.equals(actor) && k.equals(ACTIVATE),
                Map.of(ACTIVATE, registration))
            .submit(
                new Journal.Intent(
                    new OperationId(operation),
                    new RequestId(operation),
                    actor,
                    ACTIVATE,
                    request,
                    null,
                    null));
    if (outcome.status() == Journal.Status.COMMITTED)
      GENERATIONS
          .computeIfAbsent(world, k -> new java.util.concurrent.atomic.AtomicLong())
          .incrementAndGet();
    return outcome;
  }

  public static List<String> statements() {
    return List.of(
        "CREATE TABLE reference_activations(operation_id TEXT PRIMARY KEY,world_id TEXT NOT NULL,actor TEXT NOT NULL,trader TEXT NOT NULL,market TEXT NOT NULL,manifest_id TEXT NOT NULL,review_hash TEXT NOT NULL,payload TEXT NOT NULL)",
        "CREATE TRIGGER reference_activations_update BEFORE UPDATE ON reference_activations BEGIN SELECT RAISE(ABORT,'immutable reference activation'); END",
        "CREATE TRIGGER reference_activations_delete BEFORE DELETE ON reference_activations BEGIN SELECT RAISE(ABORT,'immutable reference activation'); END",
        "CREATE TRIGGER reference_activations_replace BEFORE INSERT ON reference_activations WHEN EXISTS(SELECT 1 FROM reference_activations WHERE operation_id=NEW.operation_id) BEGIN SELECT RAISE(ABORT,'existing reference activation'); END");
  }

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog.with(new RecordCatalog.Key(TYPE, 1), ReferenceTrading::decode);
  }

  private static String encode(Activation value) {
    String text = JSON.toJson(value);
    if (text.getBytes(StandardCharsets.UTF_8).length > 16384)
      throw new IllegalArgumentException("Activation bound");
    return text;
  }

  private static Activation decode(String text) {
    if (text == null || text.getBytes(StandardCharsets.UTF_8).length > 16384)
      throw new IllegalArgumentException("Activation bound");
    var value = JSON.fromJson(text, Activation.class);
    if (value == null || !encode(value).equals(text))
      throw new IllegalArgumentException("Noncanonical activation");
    return value;
  }

  public static void validate(Connection connection) throws SQLException {
    try {
      var reads = TradeSources.reads(connection);
      for (String declaration : statements()) {
        String name = declaration.split(" ")[2].split("\\(")[0];
        var rows = reads.query("SELECT sql FROM sqlite_schema WHERE name=?", List.of(name), 1);
        if (rows.isEmpty()
            || !declaration
                .replaceAll("\\s+", " ")
                .strip()
                .equals(rows.getFirst().get("sql").replaceAll("\\s+", " ").strip()))
          throw new IllegalArgumentException("Activation schema");
      }
      String world =
          reads
              .query("SELECT world_id FROM foundation_metadata", List.of(), 1)
              .getFirst()
              .get("world_id");
      try (var q = connection.createStatement();
          var rows = q.executeQuery("SELECT * FROM reference_activations")) {
        while (rows.next()) {
          var e = decode(rows.getString("payload"));
          if (!world.equals(e.world().toString())
              || !world.equals(rows.getString("world_id"))
              || !e.operation().toString().equals(rows.getString("operation_id"))
              || !e.actor().equals(rows.getString("actor"))
              || !e.trader().toString().equals(rows.getString("trader"))
              || !e.market().toString().equals(rows.getString("market"))
              || !e.snapshot().toString().equals(rows.getString("manifest_id"))
              || !e.reviewHash().equals(rows.getString("review_hash")))
            throw new IllegalArgumentException("Activation projection");
          var facts =
              reads.query(
                  "SELECT r.sequence FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence JOIN operation_outcomes o ON o.sequence=t.sequence WHERE t.operation_id=? AND o.actor=? AND o.service='economy:a9' AND o.operation_type='economy:a9/reference-activation' AND r.record_type=? AND r.record_version=1 AND r.payload=?",
                  List.of(e.operation().toString(), e.actor(), TYPE, rows.getString("payload")),
                  2);
          if (facts.size() != 1) throw new IllegalArgumentException("Activation journal ownership");
          long sequence = Long.parseLong(facts.getFirst().get("sequence"));
          for (String key : e.scopes()) {
            var scope =
                reads.query(
                    "SELECT payload FROM trade_source_events WHERE market_id=? AND item=? AND sequence<=? ORDER BY sequence DESC LIMIT 1",
                    List.of(e.market().toString(), key, Long.toString(sequence)),
                    1);
            if (scope.isEmpty()
                || !TradeSources.decodeBoundary(scope.getFirst().get("payload"))
                    .next()
                    .valuationManifest()
                    .equals(e.snapshot()))
              throw new IllegalArgumentException("Activation source scope");
          }
        }
      }
      if (!reads
          .query("SELECT COUNT(*) AS n FROM reference_activations", List.of(), 1)
          .getFirst()
          .get("n")
          .equals(
              reads
                  .query(
                      "SELECT COUNT(*) AS n FROM journal_records WHERE record_type=?",
                      List.of(TYPE),
                      1)
                  .getFirst()
                  .get("n"))) throw new IllegalArgumentException("Orphan activation fact");
    } catch (RuntimeException failure) {
      throw new SQLException("Invalid reference activation history", failure);
    }
  }
}
