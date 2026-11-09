package ca.emilybelnavis.mcmods.economy.trade.stock;

import static ca.emilybelnavis.mcmods.economy.trade.stock.StockFacts.*;

import ca.emilybelnavis.mcmods.economy.trade.catalog.*;
import ca.emilybelnavis.mcmods.economy.trade.market.MarketFacts;
import java.sql.*;
import java.util.*;

/** Strict v1 source/transition validation streams bounded detached stock snapshots. */
public final class StockSchema {
  private StockSchema() {}

  public static List<String> statements() {
    return List.of(
        "CREATE TABLE trade_stock_events(operation_id TEXT PRIMARY KEY,world_id TEXT NOT NULL,sequence INTEGER NOT NULL UNIQUE REFERENCES journal_transactions(sequence),trader_id TEXT NOT NULL,market_id TEXT NOT NULL,progression_id TEXT NOT NULL,payload TEXT NOT NULL)",
        "CREATE TABLE trade_stock_traders(trader_id TEXT PRIMARY KEY,operation_id TEXT NOT NULL REFERENCES trade_stock_events(operation_id),payload TEXT NOT NULL)",
        "CREATE TABLE trade_stock_markets(market_id TEXT PRIMARY KEY REFERENCES trade_markets(id),operation_id TEXT NOT NULL REFERENCES trade_stock_events(operation_id),payload TEXT NOT NULL)",
        "CREATE TABLE trade_stock_progressions(original_operation TEXT PRIMARY KEY,operation_id TEXT NOT NULL REFERENCES trade_stock_events(operation_id),payload TEXT NOT NULL)",
        "CREATE INDEX trade_stock_trader_history ON trade_stock_events(trader_id,sequence)",
        "CREATE INDEX trade_stock_market_history ON trade_stock_events(market_id,sequence)",
        "CREATE INDEX trade_stock_progression_history ON trade_stock_events(progression_id,sequence)",
        "CREATE INDEX trade_stock_fact_lookup ON journal_records(record_type,record_version,payload) WHERE record_type='economy:a8/trade-stock'",
        "CREATE TRIGGER trade_stock_events_update BEFORE UPDATE ON trade_stock_events BEGIN SELECT RAISE(ABORT,'immutable stock receipt'); END",
        "CREATE TRIGGER trade_stock_events_delete BEFORE DELETE ON trade_stock_events BEGIN SELECT RAISE(ABORT,'immutable stock receipt'); END",
        "CREATE TRIGGER trade_stock_events_replace BEFORE INSERT ON trade_stock_events WHEN EXISTS(SELECT 1 FROM trade_stock_events WHERE operation_id=NEW.operation_id OR sequence=NEW.sequence) BEGIN SELECT RAISE(ABORT,'existing stock receipt'); END");
  }

  public static void validate(Connection c) throws SQLException {
    try {
      for (String expected : statements()) {
        String name = expected.split(" ")[2].split("\\(", 2)[0],
            actual = scalar(c, "SELECT sql FROM sqlite_master WHERE name=?", name);
        if (actual == null || !normalize(expected).equals(normalize(actual)))
          throw invalid("Stock schema: " + name);
      }
      String world = scalar(c, "SELECT world_id FROM foundation_metadata WHERE singleton=1");
      if (count(c, "SELECT COUNT(*) FROM trade_stock_events")
          != count(c, "SELECT COUNT(*) FROM journal_records WHERE record_type='" + TYPE + "'"))
        throw invalid("Stock immutable fact count");
      try (var s = c.createStatement();
          var rows = s.executeQuery("SELECT * FROM trade_stock_events ORDER BY sequence")) {
        while (rows.next()) {
          Event e = decode(rows.getString("payload"));
          long seq = rows.getLong("sequence");
          if (!StockStore.expectedOperation(e).equals(e.operation()))
            throw invalid("Stock operation identity");
          if (!world.equals(e.world().value().toString())
              || !world.equals(rows.getString("world_id"))
              || !e.operation().toString().equals(rows.getString("operation_id"))
              || !(e.trader() == null ? "" : e.trader().trader().toString())
                  .equals(rows.getString("trader_id"))
              || !(e.market() == null ? "" : e.market().market().toString())
                  .equals(rows.getString("market_id"))
              || !(e.progression() == null ? "" : e.progression().originalOperation().toString())
                  .equals(rows.getString("progression_id"))) throw invalid("Stock source identity");
          String fact =
              scalar(
                  c,
                  "SELECT r.payload FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE t.world_id=? AND t.operation_id=? AND t.sequence=? AND r.record_type=? AND r.record_version=1",
                  world,
                  e.operation().toString(),
                  Long.toString(seq),
                  TYPE);
          if (!rows.getString("payload").equals(fact)) throw invalid("Stock journal linkage");
          Trader priorTrader =
              e.trader() == null ? null : previousTrader(c, e.trader().trader().toString(), seq);
          switch (e.action()) {
            case INSTALL -> validateInstall(c, e, priorTrader);
            case TRADER_TICKS -> {
              if (!e.trader().equals(StockStore.ticked(priorTrader, e.sample(), e.eligibility())))
                throw invalid("Stock eligible-tick history");
            }
            case MARKET_TICKS -> {
              String previous = previous(c, "market_id", e.market().market().toString(), seq);
              MarketTicks prior = previous == null ? null : decode(previous).market();
              if (!e.market()
                  .equals(StockStore.tickedMarket(e.market().market(), prior, e.sample())))
                throw invalid("Market running-tick history");
              if (scalar(
                      c, "SELECT id FROM trade_markets WHERE id=?", e.market().market().toString())
                  == null) throw invalid("Unknown loaded market");
            }
            case TRADE -> {
              if (!e.operation().equals(e.progression().originalOperation())
                  || !e.trader().equals(StockStore.traded(priorTrader, e.progression()))
                  || previous(
                          c, "progression_id", e.progression().originalOperation().toString(), seq)
                      != null) throw invalid("Trade stock/progression history");
              String binding =
                  scalar(
                      c,
                      "SELECT payload FROM trade_market_events WHERE binding_trader=? AND sequence<? ORDER BY sequence DESC LIMIT 1",
                      e.trader().trader().toString(),
                      Long.toString(seq));
              if (binding == null
                  || MarketFacts.decode(binding).binding().revision() != e.bindingRevision())
                throw invalid("Stock binding witness");
            }
            case PROGRESSION -> {
              String previous =
                  previous(
                      c, "progression_id", e.progression().originalOperation().toString(), seq);
              StockStore.progressionTransition(
                  previous == null ? null : decode(previous).progression(), e.progression());
            }
          }
        }
      }
      validateLatest(c, "trade_stock_traders", "trader_id");
      validateLatest(c, "trade_stock_markets", "market_id");
      validateLatest(c, "trade_stock_progressions", "progression_id");
    } catch (RuntimeException malformed) {
      throw new SQLException("Invalid stock evidence", malformed);
    }
  }

  private static void validateInstall(Connection c, Event e, Trader old) throws SQLException {
    Trader next = e.trader();
    if (next.revision() != (old == null ? 1 : old.revision() + 1)
        || old != null
            && (next.catalogRevision() <= old.catalogRevision()
                || next.wandering() != old.wandering())
        || next.restockTicks() != (old == null ? 0 : old.restockTicks())
        || !Objects.equals(next.session(), old == null ? null : old.session())
        || next.lastObservedServerTick() != (old == null ? 0 : old.lastObservedServerTick()))
      throw invalid("Stock install revision/progress");
    long seq = sequence(c, e.operation().toString());
    String catalogSequence =
        scalar(
            c,
            "SELECT t.sequence FROM trade_catalogs a JOIN journal_transactions t ON t.operation_id=a.operation_id WHERE a.trader_id=? AND a.revision=?",
            next.trader().toString(),
            Long.toString(next.catalogRevision()));
    if (catalogSequence == null || Long.parseLong(catalogSequence) >= seq)
      throw invalid("Stock catalog must precede installation");
    var offers = new ArrayList<OfferStock>();
    try (var s =
        c.prepareStatement(
            "SELECT payload FROM trade_catalog_offers WHERE trader_id=? AND revision=? ORDER BY ordinal")) {
      s.setString(1, next.trader().toString());
      s.setLong(2, next.catalogRevision());
      try (var rows = s.executeQuery()) {
        while (rows.next()) {
          if (offers.size() >= 32) throw invalid("Stock offer bound");
          var entry = CatalogFacts.decodeEntry(rows.getString(1));
          var offer = entry.offer();
          int remaining = offer.originalMaxUses();
          if (old != null)
            for (var prior : old.offers())
              if (prior.identity().equals(offer.identity())) {
                remaining = prior.remaining();
                break;
              }
          offers.add(
              new OfferStock(
                  offer.ordinal(),
                  offer.identity(),
                  offer.side(),
                  offer.originalMaxUses(),
                  remaining,
                  offer.villagerXp()));
        }
      }
    }
    if (!offers.equals(next.offers())) throw invalid("Stock/catalog identity");
    String binding =
        scalar(
            c,
            "SELECT payload FROM trade_market_events WHERE binding_trader=? AND sequence<? ORDER BY sequence DESC LIMIT 1",
            next.trader().toString(),
            Long.toString(sequence(c, e.operation().toString())));
    if (binding == null) throw invalid("Stock install without binding");
    UUID market = MarketFacts.decode(binding).binding().market();
    String payload = scalar(c, "SELECT payload FROM trade_markets WHERE id=?", market.toString());
    if (payload == null
        || (MarketFacts.decode(payload).market().kind() == MarketFacts.Kind.WANDERING)
            != next.wandering()) throw invalid("Stock trader kind");
  }

  private static Trader previousTrader(Connection c, String id, long seq) throws SQLException {
    String payload = previous(c, "trader_id", id, seq);
    return payload == null ? null : decode(payload).trader();
  }

  private static String previous(Connection c, String column, String id, long seq)
      throws SQLException {
    return scalar(
        c,
        "SELECT payload FROM trade_stock_events WHERE "
            + column
            + "=? AND sequence<? ORDER BY sequence DESC LIMIT 1",
        id,
        Long.toString(seq));
  }

  private static long sequence(Connection c, String operation) throws SQLException {
    return Long.parseLong(
        scalar(c, "SELECT sequence FROM trade_stock_events WHERE operation_id=?", operation));
  }

  private static void validateLatest(Connection c, String table, String eventKey)
      throws SQLException {
    String projectionKey =
        table.equals("trade_stock_progressions") ? "original_operation" : eventKey;
    try (var s = c.createStatement();
        var rows = s.executeQuery("SELECT * FROM " + table)) {
      while (rows.next()) {
        String payload =
            scalar(
                c,
                "SELECT payload FROM trade_stock_events WHERE "
                    + eventKey
                    + "=? ORDER BY sequence DESC LIMIT 1",
                rows.getString(projectionKey));
        if (!rows.getString("payload").equals(payload)
            || !decode(payload).operation().toString().equals(rows.getString("operation_id")))
          throw invalid("Stock latest projection: " + table);
      }
    }
    if (count(c, "SELECT COUNT(*) FROM " + table)
        != count(
            c,
            "SELECT COUNT(DISTINCT "
                + eventKey
                + ") FROM trade_stock_events WHERE "
                + eventKey
                + "!=''")) throw invalid("Stock projection count: " + table);
  }

  private static String scalar(Connection c, String sql, String... params) throws SQLException {
    try (var s = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) s.setString(i + 1, params[i]);
      try (var rows = s.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  private static long count(Connection c, String sql) throws SQLException {
    return Long.parseLong(scalar(c, sql));
  }

  private static String normalize(String s) {
    return s.replaceAll("\\s+", " ").trim();
  }

  private static SQLException invalid(String reason) {
    return new SQLException(reason);
  }
}
