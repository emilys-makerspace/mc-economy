package ca.emilybelnavis.mcmods.economy.trade.market;

import static ca.emilybelnavis.mcmods.economy.trade.market.MarketFacts.*;

import ca.emilybelnavis.mcmods.economy.village.BranchPlans;
import java.sql.*;
import java.util.*;

/**
 * V1 immutable market receipts and indexed projections, validated without source reclassification.
 */
public final class MarketSchema {
  private MarketSchema() {}

  public static List<String> statements() {
    return List.of(
        "CREATE TABLE trade_market_events(operation_id TEXT PRIMARY KEY,world_id TEXT NOT NULL,sequence INTEGER NOT NULL UNIQUE REFERENCES journal_transactions(sequence),market_id TEXT NOT NULL,index_market TEXT NOT NULL,binding_trader TEXT NOT NULL,search_trader TEXT NOT NULL,price_market TEXT NOT NULL,price_item TEXT NOT NULL,carry_key TEXT NOT NULL,payload TEXT NOT NULL)",
        "CREATE TABLE trade_markets(id TEXT PRIMARY KEY,dimension TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN ('VILLAGE','DYNAMIC','WANDERING')),operation_id TEXT NOT NULL UNIQUE REFERENCES trade_market_events(operation_id),payload TEXT NOT NULL)",
        "CREATE TABLE trade_market_dimensions(dimension TEXT PRIMARY KEY,revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision>0))",
        "CREATE TABLE trade_market_indexes(market_id TEXT PRIMARY KEY REFERENCES trade_markets(id),dimension TEXT NOT NULL,next_cell INTEGER NOT NULL CHECK(typeof(next_cell)='integer' AND next_cell>=0),ready INTEGER NOT NULL CHECK(ready IN (0,1)),operation_id TEXT NOT NULL REFERENCES trade_market_events(operation_id))",
        "CREATE TABLE trade_market_cells(dimension TEXT NOT NULL,cell_x INTEGER NOT NULL,cell_y INTEGER NOT NULL,cell_z INTEGER NOT NULL,market_id TEXT NOT NULL REFERENCES trade_markets(id),PRIMARY KEY(dimension,cell_x,cell_y,cell_z,market_id))",
        "CREATE TABLE trade_market_bindings(trader_id TEXT PRIMARY KEY,market_id TEXT NOT NULL REFERENCES trade_markets(id),operation_id TEXT NOT NULL REFERENCES trade_market_events(operation_id),payload TEXT NOT NULL)",
        "CREATE TABLE trade_market_searches(trader_id TEXT PRIMARY KEY,operation_id TEXT NOT NULL REFERENCES trade_market_events(operation_id),payload TEXT NOT NULL)",
        "CREATE TABLE trade_market_prices(market_id TEXT NOT NULL REFERENCES trade_markets(id),item TEXT NOT NULL,operation_id TEXT NOT NULL REFERENCES trade_market_events(operation_id),payload TEXT NOT NULL,PRIMARY KEY(market_id,item))",
        "CREATE TABLE trade_market_carries(market_id TEXT NOT NULL REFERENCES trade_markets(id),item TEXT NOT NULL,actor TEXT NOT NULL,side TEXT NOT NULL CHECK(side IN ('BUY','SELL')),epoch TEXT NOT NULL,operation_id TEXT NOT NULL REFERENCES trade_market_events(operation_id),payload TEXT NOT NULL,PRIMARY KEY(market_id,item,actor,side,epoch))",
        "CREATE INDEX trade_market_dimension_pending ON trade_market_indexes(dimension,ready,market_id)",
        "CREATE INDEX trade_market_index_history ON trade_market_events(index_market,sequence)",
        "CREATE INDEX trade_market_binding_history ON trade_market_events(binding_trader,sequence)",
        "CREATE INDEX trade_market_search_history ON trade_market_events(search_trader,sequence)",
        "CREATE INDEX trade_market_price_history ON trade_market_events(price_market,price_item,sequence)",
        "CREATE INDEX trade_market_carry_history ON trade_market_events(carry_key,sequence)",
        "CREATE INDEX trade_market_fact_lookup ON journal_records(record_type,record_version,payload) WHERE record_type='economy:a8/market-state'",
        immutable("trade_market_events", "UPDATE"),
        immutable("trade_market_events", "DELETE"),
        immutable("trade_markets", "UPDATE"),
        immutable("trade_markets", "DELETE"),
        immutable("trade_market_cells", "UPDATE"),
        immutable("trade_market_cells", "DELETE"),
        "CREATE TRIGGER trade_market_events_replace BEFORE INSERT ON trade_market_events WHEN EXISTS(SELECT 1 FROM trade_market_events WHERE operation_id=NEW.operation_id OR sequence=NEW.sequence) BEGIN SELECT RAISE(ABORT,'existing market receipt'); END",
        "CREATE TRIGGER trade_markets_replace BEFORE INSERT ON trade_markets WHEN EXISTS(SELECT 1 FROM trade_markets WHERE id=NEW.id OR operation_id=NEW.operation_id) BEGIN SELECT RAISE(ABORT,'existing captured market'); END",
        "CREATE TRIGGER trade_market_cells_replace BEFORE INSERT ON trade_market_cells WHEN EXISTS(SELECT 1 FROM trade_market_cells WHERE dimension=NEW.dimension AND cell_x=NEW.cell_x AND cell_y=NEW.cell_y AND cell_z=NEW.cell_z AND market_id=NEW.market_id) BEGIN SELECT RAISE(ABORT,'existing market cell'); END");
  }

  private static String immutable(String table, String action) {
    return "CREATE TRIGGER "
        + table
        + "_"
        + action.toLowerCase(Locale.ROOT)
        + " BEFORE "
        + action
        + " ON "
        + table
        + " BEGIN SELECT RAISE(ABORT,'immutable market evidence'); END";
  }

  public static void validate(Connection c) throws SQLException {
    try {
      for (String expected : statements()) {
        String name = expected.split(" ")[2].split("\\(", 2)[0];
        String actual = scalar(c, "SELECT sql FROM sqlite_master WHERE name=?", name);
        if (actual == null || !normalize(expected).equals(normalize(actual)))
          throw invalid("Market schema declaration: " + name);
      }
      String world = scalar(c, "SELECT world_id FROM foundation_metadata WHERE singleton=1");
      if (count(c, "SELECT COUNT(*) FROM trade_market_events")
          != count(c, "SELECT COUNT(*) FROM journal_records WHERE record_type='" + TYPE + "'"))
        throw invalid("Market receipt count");
      try (var s = c.createStatement();
          var rows = s.executeQuery("SELECT * FROM trade_market_events ORDER BY sequence")) {
        while (rows.next()) {
          Event e = decode(rows.getString("payload"));
          long seq = rows.getLong("sequence");
          if (!world.equals(e.world().value().toString())
              || !world.equals(rows.getString("world_id"))
              || !e.operation().toString().equals(rows.getString("operation_id")))
            throw invalid("Market receipt identity");
          String fact =
              scalar(
                  c,
                  "SELECT r.payload FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE t.world_id=? AND t.operation_id=? AND t.sequence=? AND r.record_type=? AND r.record_version=1",
                  world,
                  e.operation().toString(),
                  Long.toString(seq),
                  TYPE);
          if (!rows.getString("payload").equals(fact)) throw invalid("Market journal linkage");
          if (!Objects.equals(
                  rows.getString("market_id"), e.market() == null ? "" : e.market().id().toString())
              || !Objects.equals(
                  rows.getString("index_market"),
                  e.index() == null ? "" : e.index().market().toString())
              || !Objects.equals(
                  rows.getString("binding_trader"),
                  e.binding() == null ? "" : e.binding().trader().toString())
              || !Objects.equals(
                  rows.getString("search_trader"),
                  e.search() == null ? "" : e.search().trader().toString())
              || !Objects.equals(
                  rows.getString("price_market"),
                  e.price() == null ? "" : e.price().market().toString())
              || !Objects.equals(
                  rows.getString("price_item"), e.price() == null ? "" : e.price().item())
              || !Objects.equals(
                  rows.getString("carry_key"),
                  e.carry() == null ? "" : MarketStore.carryIdentity(e.carry().key())))
            throw invalid("Market event lookup keys");
          if (e.search() != null && e.search().best() != null) {
            String bestPayload =
                scalar(
                    c,
                    "SELECT payload FROM trade_markets WHERE id=?",
                    e.search().best().toString());
            if (bestPayload == null) throw invalid("Missing membership candidate");
            Market best = decode(bestPayload).market();
            if (best.kind() != (e.search().phase() == 0 ? Kind.VILLAGE : Kind.DYNAMIC)
                || !best.anchor().dimension().equals(e.search().target().dimension())
                || !best.bounds().contains(e.search().target())
                || e.search().distance() != e.search().target().distance(best.anchor())
                || e.search().phase() == 1 && e.search().distance() > 4096)
              throw invalid("Membership candidate evidence");
          }
          if (e.market() != null) {
            validateMarket(e.market());
            String payload =
                scalar(
                    c, "SELECT payload FROM trade_markets WHERE id=?", e.market().id().toString());
            if (!rows.getString("payload").equals(payload))
              throw invalid("Captured market projection");
          }
          if (e.index() != null) {
            String marketPayload =
                scalar(
                    c,
                    "SELECT payload FROM trade_markets WHERE id=?",
                    e.index().market().toString());
            if (marketPayload == null
                || MarketStore.cellCount(decode(marketPayload).market()) != e.index().total())
              throw invalid("Spatial manifest receipt");
            String old = previous(c, "index_market=?", List.of(e.index().market().toString()), seq);
            long start = old == null ? 0 : decode(old).index().next();
            if (e.index().start() != start) throw invalid("Spatial page history");
          }
          if (e.binding() != null) {
            String old =
                previous(c, "binding_trader=?", List.of(e.binding().trader().toString()), seq);
            long revision = old == null ? 1 : decode(old).binding().revision() + 1;
            if (e.binding().revision() != revision) throw invalid("Binding revision history");
          }
          if (e.search() != null) {
            long generation =
                Long.parseLong(
                    scalar(
                        c,
                        "SELECT COUNT(*) FROM trade_market_events e JOIN trade_markets m ON m.id=CASE WHEN e.index_market!='' THEN e.index_market ELSE e.market_id END WHERE m.dimension=? AND m.kind!='WANDERING' AND e.sequence<=?",
                        e.search().target().dimension(),
                        Long.toString(seq)));
            if (generation != e.search().spatialRevision())
              throw invalid("Search spatial generation");
            String old =
                previous(c, "search_trader=?", List.of(e.search().trader().toString()), seq);
            long revision = old == null ? 1 : decode(old).search().revision() + 1;
            if (e.search().revision() != revision) throw invalid("Search revision history");
          }
          if (e.price() != null) {
            String old =
                previous(
                    c,
                    "price_market=? AND price_item=?",
                    List.of(e.price().market().toString(), e.price().item()),
                    seq);
            Price p = old == null ? null : decode(old).price();
            if (e.price().revision() != (p == null ? 1 : p.revision() + 1)
                || p != null && e.price().eligibleTick() < p.eligibleTick())
              throw invalid("Price history");
            if (e.carry() == null
                || !e.carry().key().market().equals(e.price().market())
                || !e.carry().key().item().equals(e.price().item())
                || e.price().demand().anchoredEligibleTick() != e.price().eligibleTick()
                || old != null
                    && !decode(old).carry().key().epoch().equals(e.carry().key().epoch()))
              throw invalid("Price epoch/carry history");
          }
        }
      }
      if (count(c, "SELECT COUNT(*) FROM trade_markets")
          != count(c, "SELECT COUNT(*) FROM trade_market_events WHERE market_id!=''"))
        throw invalid("Market projection count");
      validateProjections(c);
    } catch (RuntimeException malformed) {
      throw new SQLException("Invalid market evidence", malformed);
    }
  }

  private static void validateMarket(Market m) throws SQLException {
    if (m.kind() == Kind.VILLAGE) {
      String[] p = m.source().split("\\|", -1);
      if (p.length != 4
          || !BranchPlans.id(
                  m.world(),
                  new BranchPlans.Source(
                      p[0], p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3])))
              .equals(m.id())) throw invalid("Village identity");
    }
    if (m.kind() == Kind.DYNAMIC) {
      var p = m.anchor();
      if (!stable(
                  "dynamic|"
                      + m.world().value()
                      + "|"
                      + p.dimension()
                      + "|"
                      + p.x()
                      + "|"
                      + p.y()
                      + "|"
                      + p.z())
              .equals(m.id())
          || !new Box(p.x() - 64, p.y() - 64, p.z() - 64, p.x() + 64, p.y() + 64, p.z() + 64)
              .equals(m.bounds())) throw invalid("Dynamic market identity/bounds");
    }
    if (m.kind() == Kind.WANDERING) {
      String[] source = m.source().split("\\|", -1);
      if (source.length != 2
          || !stable("wandering|" + m.world().value() + "|" + UUID.fromString(source[1]))
              .equals(m.id())
          || m.currency()
              != m.manifest()
                  .get(
                      MarketStore.wanderingIndex(
                          m.world(),
                          UUID.fromString(source[1]),
                          Long.parseLong(source[0]),
                          m.manifest()))) throw invalid("Wandering identity/currency");
    }
    MarketStore.cellCount(m);
  }

  private static void validateProjections(Connection c) throws SQLException {
    validateLatest(c, "trade_market_bindings", "binding_trader", "trader_id");
    validateLatest(c, "trade_market_searches", "search_trader", "trader_id");
    try (var s = c.createStatement();
        var r = s.executeQuery("SELECT * FROM trade_market_prices")) {
      while (r.next()) {
        String latest =
            scalar(
                c,
                "SELECT payload FROM trade_market_events WHERE price_market=? AND price_item=? ORDER BY sequence DESC LIMIT 1",
                r.getString("market_id"),
                r.getString("item"));
        if (!r.getString("payload").equals(latest)
            || !decode(latest).operation().toString().equals(r.getString("operation_id")))
          throw invalid("Price latest projection");
      }
    }
    if (count(c, "SELECT COUNT(*) FROM trade_market_prices")
        != count(
            c,
            "SELECT COUNT(*) FROM (SELECT price_market,price_item FROM trade_market_events WHERE price_market!='' GROUP BY price_market,price_item)"))
      throw invalid("Price projection count");
    try (var s = c.createStatement();
        var r = s.executeQuery("SELECT * FROM trade_market_carries")) {
      while (r.next()) {
        Event e = decode(r.getString("payload"));
        if (e.carry() == null) throw invalid("Missing carry receipt");
        var key = e.carry().key();
        if (!key.market().toString().equals(r.getString("market_id"))
            || !key.item().equals(r.getString("item"))
            || !key.actor().toString().equals(r.getString("actor"))
            || !key.side().name().equals(r.getString("side"))
            || !key.epoch().toString().equals(r.getString("epoch")))
          throw invalid("Carry projection identity");
        String latest =
            scalar(
                c,
                "SELECT payload FROM trade_market_events WHERE carry_key=? ORDER BY sequence DESC LIMIT 1",
                MarketStore.carryIdentity(key));
        if (!r.getString("payload").equals(latest)
            || !decode(latest).operation().toString().equals(r.getString("operation_id")))
          throw invalid("Carry latest projection");
      }
    }
    if (count(c, "SELECT COUNT(*) FROM trade_market_carries")
        != count(
            c, "SELECT COUNT(DISTINCT carry_key) FROM trade_market_events WHERE carry_key!=''"))
      throw invalid("Carry projection count");
    try (var dimensions = c.createStatement();
        var ds =
            dimensions.executeQuery("SELECT dimension,revision FROM trade_market_dimensions")) {
      while (ds.next()) {
        String expected =
            scalar(
                c,
                "SELECT COUNT(*) FROM trade_market_events e JOIN trade_markets m ON m.id=CASE WHEN e.index_market!='' THEN e.index_market ELSE e.market_id END WHERE m.dimension=? AND m.kind!='WANDERING'",
                ds.getString("dimension"));
        if (!expected.equals(ds.getString("revision")))
          throw invalid("Spatial generation projection");
      }
    }
    if (count(c, "SELECT COUNT(*) FROM trade_market_dimensions")
        != count(c, "SELECT COUNT(DISTINCT dimension) FROM trade_markets WHERE kind!='WANDERING'"))
      throw invalid("Spatial generation count");
    long expectedCells = 0, indexes = 0;
    try (var s = c.createStatement();
        var r = s.executeQuery("SELECT * FROM trade_markets")) {
      while (r.next()) {
        Market m = decode(r.getString("payload")).market();
        if (!m.id().toString().equals(r.getString("id"))
            || !m.kind().name().equals(r.getString("kind"))
            || !m.anchor().dimension().equals(r.getString("dimension"))
            || !decode(r.getString("payload"))
                .operation()
                .toString()
                .equals(r.getString("operation_id"))) throw invalid("Market projection keys");
        if (m.kind() == Kind.WANDERING) continue;
        indexes++;
        String next =
            scalar(
                c,
                "SELECT next_cell FROM trade_market_indexes WHERE market_id=?",
                m.id().toString());
        if (next == null) throw invalid("Missing spatial index state");
        long n = Long.parseLong(next), total = MarketStore.cellCount(m);
        String lastIndex =
            scalar(
                c,
                "SELECT payload FROM trade_market_events WHERE index_market=? ORDER BY sequence DESC LIMIT 1",
                m.id().toString());
        if (!m.anchor()
            .dimension()
            .equals(
                scalar(
                    c,
                    "SELECT dimension FROM trade_market_indexes WHERE market_id=?",
                    m.id().toString()))) throw invalid("Spatial dimension projection");
        String expectedOperation =
            lastIndex == null
                ? decode(r.getString("payload")).operation().toString()
                : decode(lastIndex).operation().toString();
        if (!expectedOperation.equals(
            scalar(
                c,
                "SELECT operation_id FROM trade_market_indexes WHERE market_id=?",
                m.id().toString()))) throw invalid("Spatial source projection");
        if (n != (lastIndex == null ? 0 : decode(lastIndex).index().next())
            || lastIndex != null && decode(lastIndex).index().total() != total)
          throw invalid("Spatial cursor receipt mismatch");
        if (n > total
            || !Long.toString(n == total ? 1 : 0)
                .equals(
                    scalar(
                        c,
                        "SELECT ready FROM trade_market_indexes WHERE market_id=?",
                        m.id().toString()))) throw invalid("Spatial cursor");
        expectedCells = Math.addExact(expectedCells, n);
        for (long i = 0; i < n; i++) {
          int[] cell = MarketStore.cellAt(m, i);
          if (!"1"
              .equals(
                  scalar(
                      c,
                      "SELECT COUNT(*) FROM trade_market_cells WHERE dimension=? AND cell_x=? AND cell_y=? AND cell_z=? AND market_id=?",
                      m.anchor().dimension(),
                      Integer.toString(cell[0]),
                      Integer.toString(cell[1]),
                      Integer.toString(cell[2]),
                      m.id().toString()))) throw invalid("Missing spatial cell");
        }
      }
    }
    if (expectedCells != count(c, "SELECT COUNT(*) FROM trade_market_cells")
        || indexes != count(c, "SELECT COUNT(*) FROM trade_market_indexes"))
      throw invalid("Spatial projection count");
  }

  // All identifiers come from fixed schema literals at the call sites. Row values are bound.
  @SuppressWarnings("java:S2077")
  private static void validateLatest(
      Connection c, String table, String eventKey, String projectionKey) throws SQLException {
    try (var s = c.createStatement();
        var r = s.executeQuery("SELECT * FROM " + table)) {
      while (r.next()) {
        String latest =
            scalar(
                c,
                "SELECT payload FROM trade_market_events WHERE "
                    + eventKey
                    + "=? ORDER BY sequence DESC LIMIT 1",
                r.getString(projectionKey));
        if (!r.getString("payload").equals(latest)
            || !decode(latest).operation().toString().equals(r.getString("operation_id")))
          throw invalid("Market latest projection: " + table);
        if (table.equals("trade_market_bindings")
            && !decode(latest).binding().market().toString().equals(r.getString("market_id")))
          throw invalid("Binding market projection");
      }
    }
    if (count(c, "SELECT COUNT(*) FROM " + table)
        != count(
            c,
            "SELECT COUNT(DISTINCT "
                + eventKey
                + ") FROM trade_market_events WHERE "
                + eventKey
                + "!=''")) throw invalid("Market projection count: " + table);
  }

  private static String previous(Connection c, String predicate, List<String> params, long sequence)
      throws SQLException {
    var args = new ArrayList<>(params);
    args.add(Long.toString(sequence));
    return scalar(
        c,
        "SELECT payload FROM trade_market_events WHERE "
            + predicate
            + " AND sequence<? ORDER BY sequence DESC LIMIT 1",
        args.toArray(String[]::new));
  }

  private static String scalar(Connection c, String sql, String... params) throws SQLException {
    try (var s = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) s.setString(i + 1, params[i]);
      try (var r = s.executeQuery()) {
        return r.next() ? r.getString(1) : null;
      }
    }
  }

  private static long count(Connection c, String sql) throws SQLException {
    return Long.parseLong(scalar(c, sql));
  }

  private static String normalize(String s) {
    return s.replaceAll("\\s+", " ").trim();
  }

  private static SQLException invalid(String s) {
    return new SQLException(s);
  }
}
