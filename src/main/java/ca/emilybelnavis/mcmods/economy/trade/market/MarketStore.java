package ca.emilybelnavis.mcmods.economy.trade.market;

import static ca.emilybelnavis.mcmods.economy.trade.market.MarketFacts.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.storage.*;
import ca.emilybelnavis.mcmods.economy.trade.pricing.Pricing;
import ca.emilybelnavis.mcmods.economy.village.BranchPlans;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.util.*;

/** Trusted worker calls only. Each membership turn examines at most sixteen indexed candidates. */
public final class MarketStore {
  private MarketStore() {}

  private static final Journal.Kind KIND =
      new Journal.Kind("economy:a8", "economy:a8/market/change");

  public record Resolution(boolean pending, String reason, Binding binding) {}

  public record PriceTransition(JournalEngine.Commit commit, Price state, Pricing.Carry carry) {}

  public static Market importVillage(
      FoundationStore store,
      BranchPlans.Source source,
      BranchPlans.Bounds bounds,
      Position anchor,
      EconomyCurrency capturedCurrency) {
    UUID id = BranchPlans.id(store.snapshot().worldId(), source);
    var existing = store.transaction(tx -> market(tx::query, id));
    if (existing != null) return existing;
    if (!anchor.dimension().equals(source.dimension()))
      throw new IllegalArgumentException("Village anchor dimension");
    var box =
        new Box(
            Math.subtractExact(bounds.minX(), 32),
            Math.subtractExact(bounds.minY(), 32),
            Math.subtractExact(bounds.minZ(), 32),
            Math.addExact(bounds.maxX(), 32),
            Math.addExact(bounds.maxY(), 32),
            Math.addExact(bounds.maxZ(), 32));
    var market =
        new Market(
            id,
            store.snapshot().worldId(),
            Kind.VILLAGE,
            capturedCurrency,
            anchor,
            box,
            source.key(),
            List.of());
    cellCount(market);
    commit(
        store,
        new Event(market.world(), stable("village|" + id), market, null, null, null, null, null));
    return store.transaction(tx -> market(tx::query, id));
  }

  /** Explicit bounded import continuation; incomplete dimension imports make resolution pending. */
  public static boolean indexPage(FoundationStore store, UUID marketId) {
    var market = store.transaction(tx -> market(tx::query, marketId));
    if (market == null) throw new IllegalArgumentException("Unknown market");
    if (market.kind() == Kind.WANDERING) return true;
    var rows =
        store.transaction(
            tx ->
                tx.query(
                    "SELECT next_cell,ready FROM trade_market_indexes WHERE market_id=?",
                    List.of(marketId.toString()),
                    1));
    if ("1".equals(rows.getFirst().get("ready"))) return true;
    long start = Long.parseLong(rows.getFirst().get("next_cell")),
        total = cellCount(market),
        next = Math.min(total, start + 16);
    var index = new Index(marketId, start, next, total, next == total);
    commit(
        store,
        new Event(
            market.world(),
            stable("index|" + marketId + "|" + start),
            null,
            null,
            index,
            null,
            null,
            null));
    return index.ready();
  }

  /**
   * Workstation is authoritative detached server evidence. Without one, initialize fallback only
   * for an actual trade.
   */
  public static Resolution resolve(
      FoundationStore store,
      UUID trader,
      Position workstation,
      Position current,
      EconomyCurrency serverCurrency,
      boolean actualTrade) {
    if (!store.transaction(
        tx -> ca.emilybelnavis.mcmods.economy.trade.catalog.VillageMarketSource.ready(tx::query)))
      return new Resolution(true, "A7_SOURCE_SYNC_REQUIRED", null);
    var old = store.transaction(tx -> binding(tx::query, trader));
    if (old != null && (workstation == null || Objects.equals(old.workstation(), workstation)))
      return new Resolution(false, "", old);
    if (workstation == null && !actualTrade)
      return new Resolution(true, "WORKSTATION_OR_ACTUAL_TRADE_REQUIRED", null);
    Position target = workstation == null ? Objects.requireNonNull(current) : workstation;
    var incomplete =
        store.transaction(
            tx ->
                tx.query(
                    "SELECT i.market_id FROM trade_market_indexes i JOIN trade_markets m ON m.id=i.market_id WHERE i.dimension=? AND i.ready=0 LIMIT 1",
                    List.of(target.dimension()),
                    1));
    if (!incomplete.isEmpty()) return new Resolution(true, "SPATIAL_IMPORT_PENDING", null);
    Search prior = store.transaction(tx -> search(tx::query, trader));
    long spatialRevision = store.transaction(tx -> spatialRevision(tx::query, target.dimension()));
    if (prior == null
        || !prior.target().equals(target)
        || prior.assigned() != (workstation != null)
        || prior.spatialRevision() != spatialRevision) {
      var fallback =
          prior != null && prior.target().equals(target) ? prior.fallback() : serverCurrency;
      var start =
          new Search(
              trader,
              target,
              workstation != null,
              spatialRevision,
              fallback,
              0,
              "",
              null,
              Long.MAX_VALUE,
              prior == null ? 1 : prior.revision() + 1);
      commit(
          store,
          new Event(
              store.snapshot().worldId(),
              stable(
                  "search|"
                      + store.snapshot().worldId().value()
                      + "|"
                      + trader
                      + "|"
                      + start.revision()),
              null,
              null,
              null,
              start,
              null,
              null));
      return new Resolution(true, "MEMBERSHIP_PENDING", null);
    }
    var state = prior;
    var rows =
        store.transaction(
            tx ->
                tx.query(
                    "SELECT m.payload FROM trade_market_cells c JOIN trade_markets m ON m.id=c.market_id WHERE c.dimension=? AND c.cell_x=? AND c.cell_y=? AND c.cell_z=? AND m.kind=? AND m.id>? ORDER BY m.id LIMIT 16",
                    List.of(
                        target.dimension(),
                        Integer.toString(cell(target.x())),
                        Integer.toString(cell(target.y())),
                        Integer.toString(cell(target.z())),
                        state.phase() == 0 ? Kind.VILLAGE.name() : Kind.DYNAMIC.name(),
                        state.cursor()),
                    16));
    UUID best = state.best();
    long distance = state.distance();
    String cursor = state.cursor();
    for (var row : rows) {
      var candidate = decode(row.get("payload")).market();
      cursor = candidate.id().toString();
      long d = target.distance(candidate.anchor());
      if (candidate.bounds().contains(target)
          && (state.phase() == 0 || d <= 4096)
          && (best == null
              || d < distance
              || d == distance && candidate.id().toString().compareTo(best.toString()) < 0)) {
        best = candidate.id();
        distance = d;
      }
    }
    long revision = state.revision() + 1;
    if (rows.size() == 16) {
      var next =
          new Search(
              trader,
              target,
              state.assigned(),
              state.spatialRevision(),
              state.fallback(),
              state.phase(),
              cursor,
              best,
              distance,
              revision);
      saveSearch(store, next);
      return new Resolution(true, "MEMBERSHIP_PAGE_PENDING", null);
    }
    if (best == null && state.phase() == 0) {
      saveSearch(
          store,
          new Search(
              trader,
              target,
              state.assigned(),
              state.spatialRevision(),
              state.fallback(),
              1,
              "",
              null,
              Long.MAX_VALUE,
              revision));
      return new Resolution(true, "DYNAMIC_MEMBERSHIP_PENDING", null);
    }
    Market created = null;
    if (best == null) {
      best =
          stable(
              "dynamic|"
                  + store.snapshot().worldId().value()
                  + "|"
                  + target.dimension()
                  + "|"
                  + target.x()
                  + "|"
                  + target.y()
                  + "|"
                  + target.z());
      created =
          new Market(
              best,
              store.snapshot().worldId(),
              Kind.DYNAMIC,
              state.fallback(),
              target,
              around(target, 64),
              "dynamic",
              List.of());
    }
    var binding = new Binding(trader, best, workstation, old == null ? 1 : old.revision() + 1);
    var finished =
        new Search(
            trader,
            target,
            state.assigned(),
            Math.addExact(state.spatialRevision(), created == null ? 0 : 1),
            state.fallback(),
            state.phase(),
            cursor,
            best,
            distance == Long.MAX_VALUE ? 0 : distance,
            revision);
    Index index =
        created == null
            ? null
            : new Index(created.id(), 0, cellCount(created), cellCount(created), true);
    commit(
        store,
        new Event(
            store.snapshot().worldId(),
            stable(
                "bind|"
                    + store.snapshot().worldId().value()
                    + "|"
                    + trader
                    + "|"
                    + binding.revision()),
            created,
            binding,
            index,
            finished,
            null,
            null));
    return new Resolution(false, "", store.transaction(tx -> binding(tx::query, trader)));
  }

  private static void saveSearch(FoundationStore store, Search search) {
    commit(
        store,
        new Event(
            store.snapshot().worldId(),
            stable(
                "search|"
                    + store.snapshot().worldId().value()
                    + "|"
                    + search.trader()
                    + "|"
                    + search.revision()),
            null,
            null,
            null,
            search,
            null,
            null));
  }

  public static Binding bindWandering(
      FoundationStore store,
      UUID trader,
      long seed,
      Position captured,
      List<EconomyCurrency> enabled) {
    var old = store.transaction(tx -> binding(tx::query, trader));
    if (old != null) {
      if (store.transaction(tx -> market(tx::query, old.market())).kind() != Kind.WANDERING)
        throw new IllegalArgumentException("Trader already has ordinary market");
      return old;
    }
    var manifest = enabled.stream().distinct().sorted(Comparator.comparing(Enum::name)).toList();
    if (manifest.isEmpty() || manifest.size() > 11 || manifest.size() != enabled.size())
      throw new IllegalArgumentException("Enabled currency manifest");
    var world = store.snapshot().worldId();
    UUID id = stable("wandering|" + world.value() + "|" + trader);
    EconomyCurrency currency = manifest.get(wanderingIndex(world, trader, seed, manifest));
    var market =
        new Market(
            id,
            world,
            Kind.WANDERING,
            currency,
            captured,
            around(captured, 0),
            Long.toString(seed) + "|" + trader,
            manifest);
    var binding = new Binding(trader, id, null, 1);
    commit(
        store,
        new Event(
            world,
            stable("wandering-bind|" + world.value() + "|" + trader),
            market,
            binding,
            null,
            null,
            null,
            null));
    return store.transaction(tx -> binding(tx::query, trader));
  }

  static int wanderingIndex(WorldId world, UUID trader, long seed, List<EconomyCurrency> manifest) {
    BigInteger bound = BigInteger.valueOf(manifest.size()),
        range = BigInteger.ONE.shiftLeft(256),
        limit = range.subtract(range.mod(bound));
    for (int counter = 0; counter < Integer.MAX_VALUE; counter++)
      try {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeUTF("economy:a8:wandering:v1");
        out.writeLong(seed);
        out.writeUTF(world.value().toString());
        out.writeUTF(trader.toString());
        for (var c : manifest) out.writeUTF(c.name());
        out.writeInt(counter);
        out.close();
        var n = new BigInteger(1, MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        if (n.compareTo(limit) < 0) return n.mod(bound).intValueExact();
      } catch (IOException | NoSuchAlgorithmException impossible) {
        throw new AssertionError(impossible);
      }
    throw new IllegalStateException("Currency rejection sampling exhausted");
  }

  public static Market market(JournalEngine.ReadView reads, UUID id) {
    var row =
        reads.query("SELECT payload FROM trade_markets WHERE id=?", List.of(id.toString()), 1);
    return row.isEmpty() ? null : decode(row.getFirst().get("payload")).market();
  }

  public static Binding binding(JournalEngine.ReadView reads, UUID trader) {
    var row =
        reads.query(
            "SELECT payload FROM trade_market_bindings WHERE trader_id=?",
            List.of(trader.toString()),
            1);
    return row.isEmpty() ? null : decode(row.getFirst().get("payload")).binding();
  }

  public static Search search(JournalEngine.ReadView reads, UUID trader) {
    var row =
        reads.query(
            "SELECT payload FROM trade_market_searches WHERE trader_id=?",
            List.of(trader.toString()),
            1);
    return row.isEmpty() ? null : decode(row.getFirst().get("payload")).search();
  }

  public static Price price(JournalEngine.ReadView reads, UUID market, String item) {
    var row =
        reads.query(
            "SELECT payload FROM trade_market_prices WHERE market_id=? AND item=?",
            List.of(market.toString(), item),
            1);
    return row.isEmpty() ? null : decode(row.getFirst().get("payload")).price();
  }

  public static Pricing.Carry carry(JournalEngine.ReadView reads, Pricing.Key key) {
    var row =
        reads.query(
            "SELECT payload FROM trade_market_carries WHERE market_id=? AND item=? AND actor=? AND side=? AND epoch=?",
            carryKey(key),
            1);
    return row.isEmpty()
        ? new Pricing.Carry(key, Rational.ZERO)
        : decode(row.getFirst().get("payload")).carry().pricing();
  }

  /**
   * Compose this commit with the owning settlement commit. This method itself does not submit an
   * operation.
   */
  public static PriceTransition preparePriceTransition(
      JournalEngine.ReadView reads,
      WorldId world,
      UUID operation,
      UUID market,
      String item,
      long expectedRevision,
      long eligibleTick,
      Pricing.Quote quote) {
    var prior = price(reads, market, item);
    if ((prior == null ? 0 : prior.revision()) != expectedRevision || market(reads, market) == null)
      throw new IllegalArgumentException("Stale market price revision");
    if (prior != null && eligibleTick < prior.eligibleTick())
      throw new IllegalArgumentException("Market tick rewind");
    if (quote.nextDemand().anchoredEligibleTick() != eligibleTick)
      throw new IllegalArgumentException("Trade demand anchor must match captured tick");
    if (prior != null) {
      var previous =
          reads.query(
              "SELECT payload FROM trade_market_prices WHERE market_id=? AND item=?",
              List.of(market.toString(), item),
              1);
      var old = decode(previous.getFirst().get("payload"));
      if (old.carry() == null || !old.carry().key().epoch().equals(quote.nextCarry().key().epoch()))
        throw new IllegalArgumentException(
            "Price epoch changes require an explicit residual-handling transition");
    }
    if (!quote.nextCarry().key().market().equals(market)
        || !quote.nextCarry().key().item().equals(item))
      throw new IllegalArgumentException("Price carry key");
    var price =
        new Price(
            market, item, Math.addExact(expectedRevision, 1), quote.nextDemand(), eligibleTick);
    var event =
        new Event(
            world,
            operation,
            null,
            null,
            null,
            null,
            price,
            new Carry(quote.nextCarry().key(), quote.nextCarry().residual()));
    return new PriceTransition(prepare(reads, event), price, quote.nextCarry());
  }

  static JournalEngine.Commit prepare(JournalEngine.ReadView reads, Event event) {
    validateTransition(reads, event);
    String payload = encode(event);
    return new JournalEngine.Commit(
        List.of(new Journal.Record(TYPE, 1, payload)),
        new Journal.Result(1, "MARKET_STATE_RECORDED", event.operation().toString()),
        tx -> apply(tx, event, payload));
  }

  private static void commit(FoundationStore store, Event event) {
    String payload = encode(event);
    var actor = new SystemActorId(stable("actor|" + event.world().value()));
    var request = new Journal.CanonicalRequest(1, Map.of("state", payload));
    var registration =
        new JournalEngine.Registration(
            1,
            (i, r) -> {
              if (!request.equals(i.request()))
                throw new IllegalArgumentException("Market request");
              return prepare(r, event);
            });
    var engine =
        new JournalEngine(
            store, (a, k) -> actor.equals(a) && KIND.equals(k), Map.of(KIND, registration));
    var result =
        engine.submit(
            new Journal.Intent(
                new OperationId(event.operation()),
                new RequestId(event.operation()),
                actor,
                KIND,
                request,
                null,
                null));
    if (result.status() != Journal.Status.COMMITTED)
      throw new IllegalStateException("Market commit: " + result.result().code());
  }

  static void validateTransition(JournalEngine.ReadView reads, Event e) {
    if (!reads
        .query("SELECT world_id FROM foundation_metadata WHERE singleton=1", List.of(), 1)
        .getFirst()
        .get("world_id")
        .equals(e.world().value().toString()))
      throw new IllegalArgumentException("Market world mismatch");
    if (e.market() != null && e.market().kind() != Kind.WANDERING)
      Math.addExact(spatialRevision(reads, e.market().anchor().dimension()), 1);
    if (e.index() != null) {
      var indexed = e.market() != null ? e.market() : market(reads, e.index().market());
      if (indexed != null) Math.addExact(spatialRevision(reads, indexed.anchor().dimension()), 1);
    }
    if (e.market() != null && market(reads, e.market().id()) != null)
      throw new IllegalArgumentException("Captured market already exists");
    if (e.binding() != null) {
      var old = binding(reads, e.binding().trader());
      if (e.binding().revision() != (old == null ? 1 : old.revision() + 1))
        throw new IllegalArgumentException("Binding revision");
      if ((e.market() == null || !e.market().id().equals(e.binding().market()))
          && market(reads, e.binding().market()) == null)
        throw new IllegalArgumentException("Unknown binding market");
    }
    if (e.search() != null) {
      var old = search(reads, e.search().trader());
      if (e.search().revision() != (old == null ? 1 : old.revision() + 1))
        throw new IllegalArgumentException("Search revision");
    }
    if (e.index() != null) {
      var rows =
          reads.query(
              "SELECT next_cell FROM trade_market_indexes WHERE market_id=?",
              List.of(e.index().market().toString()),
              1);
      long start = rows.isEmpty() ? 0 : Long.parseLong(rows.getFirst().get("next_cell"));
      if (start != e.index().start()) throw new IllegalArgumentException("Index cursor");
      var m =
          e.market() != null && e.market().id().equals(e.index().market())
              ? e.market()
              : market(reads, e.index().market());
      if (m == null || cellCount(m) != e.index().total())
        throw new IllegalArgumentException("Index manifest");
    }
    if (e.price() != null) {
      var p = price(reads, e.price().market(), e.price().item());
      if (e.price().revision() != (p == null ? 1 : p.revision() + 1)
          || p != null && e.price().eligibleTick() < p.eligibleTick())
        throw new IllegalArgumentException("Price state revision");
    }
  }

  static void apply(FoundationStore.Transaction tx, Event e, String payload) {
    String op = e.operation().toString();
    var sequence =
        tx.query(
                "SELECT sequence FROM journal_transactions WHERE world_id=? AND operation_id=?",
                List.of(e.world().value().toString(), op),
                1)
            .getFirst()
            .get("sequence");
    tx.execute(
        "INSERT INTO trade_market_events VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        List.of(
            op,
            e.world().value().toString(),
            sequence,
            e.market() == null ? "" : e.market().id().toString(),
            e.index() == null ? "" : e.index().market().toString(),
            e.binding() == null ? "" : e.binding().trader().toString(),
            e.search() == null ? "" : e.search().trader().toString(),
            e.price() == null ? "" : e.price().market().toString(),
            e.price() == null ? "" : e.price().item(),
            e.carry() == null ? "" : carryIdentity(e.carry().key()),
            payload));
    if (e.market() != null) {
      var m = e.market();
      tx.execute(
          "INSERT INTO trade_markets VALUES(?,?,?,?,?)",
          List.of(m.id().toString(), m.anchor().dimension(), m.kind().name(), op, payload));
      if (m.kind() != Kind.WANDERING)
        tx.execute(
            "INSERT INTO trade_market_indexes VALUES(?,?,0,0,?)",
            List.of(m.id().toString(), m.anchor().dimension(), op));
    }
    if (e.index() != null) {
      var page = e.index();
      var m = e.market() != null ? e.market() : market(tx::query, page.market());
      for (long n = page.start(); n < page.next(); n++) {
        int[] c = cellAt(m, n);
        tx.execute(
            "INSERT INTO trade_market_cells VALUES(?,?,?,?,?)",
            List.of(
                m.anchor().dimension(),
                Integer.toString(c[0]),
                Integer.toString(c[1]),
                Integer.toString(c[2]),
                m.id().toString()));
      }
      tx.execute(
          "UPDATE trade_market_indexes SET next_cell=?,ready=?,operation_id=? WHERE market_id=?",
          List.of(
              Long.toString(page.next()), page.ready() ? "1" : "0", op, page.market().toString()));
    }
    String changedDimension =
        e.market() != null && e.market().kind() != Kind.WANDERING
            ? e.market().anchor().dimension()
            : e.index() != null ? market(tx::query, e.index().market()).anchor().dimension() : null;
    if (changedDimension != null)
      tx.execute(
          "INSERT INTO trade_market_dimensions VALUES(?,1) ON CONFLICT(dimension) DO UPDATE SET revision=revision+1",
          List.of(changedDimension));
    if (e.binding() != null)
      tx.execute(
          "INSERT INTO trade_market_bindings VALUES(?,?,?,?) ON CONFLICT(trader_id) DO UPDATE SET market_id=excluded.market_id,operation_id=excluded.operation_id,payload=excluded.payload",
          List.of(e.binding().trader().toString(), e.binding().market().toString(), op, payload));
    if (e.search() != null)
      tx.execute(
          "INSERT INTO trade_market_searches VALUES(?,?,?) ON CONFLICT(trader_id) DO UPDATE SET operation_id=excluded.operation_id,payload=excluded.payload",
          List.of(e.search().trader().toString(), op, payload));
    if (e.price() != null)
      tx.execute(
          "INSERT INTO trade_market_prices VALUES(?,?,?,?) ON CONFLICT(market_id,item) DO UPDATE SET operation_id=excluded.operation_id,payload=excluded.payload",
          List.of(e.price().market().toString(), e.price().item(), op, payload));
    if (e.carry() != null) {
      var params = new ArrayList<>(carryKey(e.carry().key()));
      params.add(op);
      params.add(payload);
      tx.execute(
          "INSERT INTO trade_market_carries VALUES(?,?,?,?,?,?,?) ON CONFLICT(market_id,item,actor,side,epoch) DO UPDATE SET operation_id=excluded.operation_id,payload=excluded.payload",
          params);
    }
  }

  static long spatialRevision(JournalEngine.ReadView reads, String dimension) {
    var rows =
        reads.query(
            "SELECT revision FROM trade_market_dimensions WHERE dimension=?",
            List.of(dimension),
            1);
    return rows.isEmpty() ? 0 : Long.parseLong(rows.getFirst().get("revision"));
  }

  static List<String> carryKey(Pricing.Key key) {
    return List.of(
        key.market().toString(),
        key.item(),
        key.actor().toString(),
        key.side().name(),
        key.epoch().toString());
  }

  static String carryIdentity(Pricing.Key key) {
    return stable(String.join("|", carryKey(key))).toString();
  }

  static int cell(int coordinate) {
    return Math.floorDiv(coordinate, 128);
  }

  static long cellCount(Market m) {
    if (m.kind() == Kind.WANDERING) return 0;
    var b = m.bounds();
    long x = cell(b.maxX()) - cell(b.minX()) + 1L,
        y = cell(b.maxY()) - cell(b.minY()) + 1L,
        z = cell(b.maxZ()) - cell(b.minZ()) + 1L;
    long n = Math.multiplyExact(Math.multiplyExact(x, y), z);
    if (n > 65536)
      throw new IllegalArgumentException("Market spatial import exceeds bounded manifest");
    return n;
  }

  static int[] cellAt(Market m, long n) {
    var b = m.bounds();
    int xs = cell(b.maxX()) - cell(b.minX()) + 1, ys = cell(b.maxY()) - cell(b.minY()) + 1;
    return new int[] {
      cell(b.minX()) + (int) (n % xs),
      cell(b.minY()) + (int) ((n / xs) % ys),
      cell(b.minZ()) + (int) (n / (xs * (long) ys))
    };
  }

  private static Box around(Position p, int radius) {
    return new Box(
        p.x() - radius,
        p.y() - radius,
        p.z() - radius,
        p.x() + radius,
        p.y() + radius,
        p.z() + radius);
  }
}
