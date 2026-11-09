package ca.emilybelnavis.mcmods.economy.trade.entity;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.trade.market.*;
import ca.emilybelnavis.mcmods.economy.trade.stock.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.*;
import net.minecraft.world.entity.schedule.Activity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Loaded-only, bounded opt-in observations. No trader is claimed or monetarily activated here. */
public final class TradeRuntime {
  private TradeRuntime() {}

  private static final int MAX_WATCHED = 256, MAX_SUBMISSIONS = 4;

  private static final class Trader {
    final UUID id;
    MarketFacts.Binding binding;
    StockFacts.Trader saved;
    boolean busy;
    int observed, eligible;
    long last;
    MarketFacts.Position relocate;
    long nextTry;
    String reason = "SOURCE_NOT_LOADED";
    final List<CompletableFuture<Snapshot>> waiters = new ArrayList<>();

    Trader(UUID id) {
      this.id = id;
    }
  }

  private static final class Market {
    final UUID id;
    StockFacts.MarketTicks saved;
    boolean busy;
    int observed;
    long last = -1;
    String reason = "";

    Market(UUID id) {
      this.id = id;
    }
  }

  private static final class Session {
    final UUID id = UUID.randomUUID();
    final LinkedHashMap<UUID, Trader> traders = new LinkedHashMap<>();
    final LinkedHashMap<UUID, Market> markets = new LinkedHashMap<>();
    boolean stopping;
  }

  public record Snapshot(
      StockFacts.Trader stock, MarketFacts.Binding binding, StockFacts.MarketTicks market) {}

  private static final Map<MinecraftServer, Boolean> ENABLED = new IdentityHashMap<>();

  public static void configure(MinecraftServer server, boolean enabled) {
    ENABLED.put(server, enabled);
  }

  public static boolean enabled(MinecraftServer server) {
    return server != null && Boolean.TRUE.equals(ENABLED.get(server));
  }

  private static final Map<MinecraftServer, Session> SESSIONS = new IdentityHashMap<>();

  public static void initialize() {
    NeoForge.EVENT_BUS.addListener(TradeRuntime::joined);
    NeoForge.EVENT_BUS.addListener(TradeRuntime::entityTick);
  }

  private static ServerServices services(MinecraftServer server) {
    var svc = Economy.serverServices(server);
    return svc != null && svc.state() == ServerServices.State.READY ? svc : null;
  }

  private static Session session(MinecraftServer server) {
    return SESSIONS.computeIfAbsent(server, k -> new Session());
  }

  private static void joined(EntityJoinLevelEvent event) {
    if (event.getLevel() instanceof ServerLevel level && TradeHooks.owned(event.getEntity()))
      watch(level.getServer(), event.getEntity().getUUID());
  }

  private static Trader watch(MinecraftServer server, UUID id) {
    var s = session(server);
    if (s.stopping) return null;
    var existing = s.traders.get(id);
    if (existing != null) return existing;
    if (s.traders.size() >= MAX_WATCHED && !retire(server, s)) return null;
    var trader = new Trader(id);
    s.traders.put(id, trader);
    return trader;
  }

  private static boolean retire(MinecraftServer server, Session s) {
    for (var t : List.copyOf(s.traders.values()))
      if (!t.busy && t.observed == 0 && t.waiters.isEmpty() && entity(server, t.id) == null) {
        s.traders.remove(t.id);
        if (t.binding != null
            && s.traders.values().stream()
                .noneMatch(
                    o -> o.binding != null && o.binding.market().equals(t.binding.market()))) {
          var m = s.markets.get(t.binding.market());
          if (m != null && !m.busy && m.observed == 0) s.markets.remove(m.id);
        }
        return true;
      }
    return false;
  }

  public static void interact(ServerPlayer player, AbstractVillager trader) {
    if (!TradeHooks.owned(trader) || !trader.isAlive() || player.distanceToSqr(trader) > 36) return;
    if (!enabled(player.server)) {
      player.displayClientMessage(Component.literal("Monetary trading is disabled."), true);
      return;
    }
    trader.getPersistentData().putBoolean(TradeHooks.OWNED, true);
    var t = watch(player.server, trader.getUUID());
    var svc = services(player.server);
    if (t == null || svc == null) {
      player.displayClientMessage(
          Component.literal("Trading is waiting for the economy to become ready."), true);
      return;
    }
    if (t.busy) {
      player.displayClientMessage(
          Component.literal("Preparing this trader. Try again shortly."), true);
      return;
    }
    bootstrap(player, trader, t, svc);
  }

  /** Capture native/entity evidence only here; each worker continuation is bounded and detached. */
  private static void bootstrap(
      ServerPlayer player, AbstractVillager entity, Trader t, ServerServices svc) {
    var capture = NativeCatalog.capture(entity, 1);
    if (!capture.available()) {
      player.displayClientMessage(
          Component.literal("This trader's offers are unavailable: " + capture.reason()), true);
      return;
    }
    var current =
        new MarketFacts.Position(
            entity.level().dimension().location().toString(),
            entity.blockPosition().getX(),
            entity.blockPosition().getY(),
            entity.blockPosition().getZ());
    var job =
        workstation(entity)
            .map(
                p ->
                    new MarketFacts.Position(
                        p.dimension().location().toString(),
                        p.pos().getX(),
                        p.pos().getY(),
                        p.pos().getZ()))
            .orElse(null);
    UUID id = entity.getUUID();
    boolean wandering = entity instanceof WanderingTrader;
    long seed = player.server.overworld().getSeed();
    var server = player.server;
    t.busy = true;
    svc.tradeStorage(
            store -> {
              var denomination =
                  store.transaction(
                      tx ->
                          ca.emilybelnavis.mcmods.economy.journal.RebaseSettlement.denomination(
                              tx::query));
              if (denomination.isEmpty())
                throw new IllegalStateException("World currency is not selected");
              if (!ca.emilybelnavis.mcmods.economy.trade.catalog.VillageMarketSource.step(
                  store, denomination.get().currency())) return null;
              MarketFacts.Binding binding;
              if (wandering)
                binding =
                    MarketStore.bindWandering(
                        store,
                        id,
                        seed,
                        current,
                        ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.all());
              else {
                var resolution =
                    MarketStore.resolve(
                        store, id, job, current, denomination.get().currency(), true);
                if (resolution.pending()) return null;
                binding = resolution.binding();
              }
              var saved = store.transaction(tx -> StockStore.trader(tx::query, id));
              long revision = saved == null ? 1 : saved.catalogRevision();
              var prior =
                  store.transaction(
                      tx ->
                          ca.emilybelnavis.mcmods.economy.trade.catalog.CatalogStore.read(
                              tx::query, id, revision));
              var incoming = capture.capture();
              if (prior == null || !prior.offers().equals(incoming.offers())) {
                long next = prior == null ? 1 : Math.addExact(revision, 1);
                ca.emilybelnavis.mcmods.economy.trade.catalog.CatalogStore.capture(
                    store,
                    new ca.emilybelnavis.mcmods.economy.trade.catalog.TradeCatalog.Capture(
                        id, next, incoming.offers()));
                saved = StockStore.install(store, id, next, wandering);
              }
              var stock = saved;
              return store.transaction(
                  tx ->
                      new Snapshot(stock, binding, StockStore.market(tx::query, binding.market())));
            })
        .whenComplete(
            (snapshot, error) ->
                server.execute(
                    () -> {
                      t.busy = false;
                      if (server.getPlayerList().getPlayer(player.getUUID()) != player
                          || !entity.isAlive()
                          || player.distanceToSqr(entity) > 36) return;
                      if (error != null) {
                        t.reason = "TRADER_ADMISSION_UNAVAILABLE";
                        player.displayClientMessage(
                            Component.literal("Trading is unavailable. Try again shortly."), true);
                        return;
                      }
                      if (snapshot == null) {
                        server.execute(
                            () -> {
                              if (player.isAlive()
                                  && entity.isAlive()
                                  && player.distanceToSqr(entity) <= 36
                                  && !t.busy) bootstrap(player, entity, t, svc);
                            });
                        return;
                      }
                      t.saved = snapshot.stock();
                      t.binding = snapshot.binding();
                      var m =
                          session(server).markets.computeIfAbsent(t.binding.market(), Market::new);
                      m.saved = snapshot.market();
                      t.reason = "";
                      ca.emilybelnavis.mcmods.economy.trade.menu.TraderController.open(
                          player, entity);
                    }));
  }

  private static void entityTick(EntityTickEvent.Post event) {
    if (!(event.getEntity() instanceof AbstractVillager entity)
        || !(entity.level() instanceof ServerLevel level)
        || !TradeHooks.owned(entity)
        || !entity.isAlive()) return;
    var s = session(level.getServer());
    var t = watch(level.getServer(), entity.getUUID());
    if (t == null || t.saved == null || t.binding == null || s.stopping) return;
    var m = s.markets.get(t.binding.market());
    if (m == null) return;
    long tick = Integer.toUnsignedLong(level.getServer().getTickCount());
    var assigned = workstation(entity);
    if (assigned.isPresent()) {
      var p = assigned.get().pos();
      var target =
          new MarketFacts.Position(
              assigned.get().dimension().location().toString(), p.getX(), p.getY(), p.getZ());
      if (!target.equals(t.binding.workstation())) {
        t.relocate = target;
        t.reason = "WORKSTATION_RELOCATION_PENDING";
      }
    }
    // Freeze at pressure/barriers rather than accumulating beyond the 200-tick crash-loss bound.
    if (!t.busy
        && t.observed < StockFacts.BUFFER_TICKS
        && t.waiters.isEmpty()
        && t.relocate == null
        && t.last != tick) {
      t.observed++;
      t.last = tick;
      if (eligibility(entity).eligible()) t.eligible++;
    }
    if (!m.busy
        && m.observed < StockFacts.BUFFER_TICKS
        && m.last != tick
        && !marketBarrier(s, m.id)) {
      m.observed++;
      m.last = tick;
    }
  }

  private static boolean marketBarrier(Session s, UUID id) {
    return s.traders.values().stream()
        .anyMatch(
            t ->
                t.binding != null
                    && t.binding.market().equals(id)
                    && (!t.waiters.isEmpty() || t.relocate != null));
  }

  public static StockFacts.Eligibility eligibility(AbstractVillager entity) {
    if (!(entity instanceof Villager villager)
        || !(entity.level() instanceof ServerLevel level)
        || !entity.isAlive()) return new StockFacts.Eligibility(false, false, false, false);
    var site = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
    boolean work = villager.getBrain().isActive(Activity.WORK), valid = false, near = false;
    if (site.isPresent() && site.get().dimension().equals(level.dimension())) {
      var pos = site.get().pos();
      boolean loaded = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
      valid =
          loaded
              && level
                  .getPoiManager()
                  .exists(pos, villager.getVillagerData().getProfession().heldJobSite());
      near = pos.closerToCenterThan(villager.position(), 2);
    }
    return new StockFacts.Eligibility(true, work, valid, near);
  }

  public static Optional<GlobalPos> workstation(AbstractVillager trader) {
    if (!(trader instanceof Villager v) || !(v.level() instanceof ServerLevel level))
      return Optional.empty();
    var site = v.getBrain().getMemory(MemoryModuleType.JOB_SITE);
    if (site.isEmpty() || !site.get().dimension().equals(level.dimension()))
      return Optional.empty();
    var p = site.get().pos();
    if (level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null
        || !level.getPoiManager().exists(p, v.getVillagerData().getProfession().heldJobSite()))
      return Optional.empty();
    return site;
  }

  /** Future admission must await this barrier before preparing a trade/restock-bound quote. */
  public static CompletionStage<Snapshot> flush(AbstractVillager entity) {
    var server = entity.getServer();
    if (server == null || !server.isSameThread() || !TradeHooks.owned(entity))
      return CompletableFuture.failedStage(
          new IllegalStateException("Owned server trader required"));
    var t = watch(server, entity.getUUID());
    if (t == null || t.waiters.size() >= 16)
      return CompletableFuture.failedStage(
          new IllegalStateException("Trader checkpoint queue is full"));
    var result = new CompletableFuture<Snapshot>();
    t.waiters.add(result);
    return result;
  }

  public static void tick(MinecraftServer server) {
    var s = SESSIONS.get(server);
    var svc = services(server);
    if (s == null || svc == null || s.stopping) return;
    s.markets
        .entrySet()
        .removeIf(
            e ->
                !e.getValue().busy
                    && e.getValue().observed == 0
                    && s.traders.values().stream()
                        .noneMatch(
                            t -> t.binding != null && t.binding.market().equals(e.getKey())));
    int submitted = 0;
    for (var t : s.traders.values()) {
      if (submitted >= MAX_SUBMISSIONS) break;
      if (t.saved == null
          && !t.busy
          && Integer.toUnsignedLong(server.getTickCount()) >= t.nextTry) {
        hydrate(server, s, svc, t);
        submitted++;
        continue;
      }
      boolean forced = !t.waiters.isEmpty() || t.relocate != null || entity(server, t.id) == null;
      if (!t.busy && t.observed > 0 && (forced || t.observed >= StockFacts.BUFFER_TICKS)) {
        checkpointTrader(server, s, svc, t);
        submitted++;
        continue;
      }
      if (!t.busy && t.relocate != null && t.observed == 0) {
        var old = s.markets.get(t.binding.market());
        if (old != null && !old.busy && old.observed == 0) {
          relocate(server, s, svc, t);
          submitted++;
        }
      }
    }
    for (var m : s.markets.values()) {
      if (submitted >= MAX_SUBMISSIONS) break;
      boolean forced =
          marketBarrier(s, m.id)
              || s.traders.values().stream()
                  .noneMatch(
                      t ->
                          t.binding != null
                              && t.binding.market().equals(m.id)
                              && entity(server, t.id) != null);
      if (!m.busy && m.observed > 0 && (forced || m.observed >= StockFacts.BUFFER_TICKS)) {
        checkpointMarket(server, s, svc, m);
        submitted++;
      }
    }
    for (var t : s.traders.values())
      if (!t.waiters.isEmpty()
          && t.relocate == null
          && !t.busy
          && t.saved != null
          && t.binding != null) {
        var m = s.markets.get(t.binding.market());
        if (m != null && !m.busy && t.observed == 0 && m.observed == 0) {
          var snapshot = new Snapshot(t.saved, t.binding, m.saved);
          var pending = List.copyOf(t.waiters);
          t.waiters.clear();
          pending.forEach(f -> f.complete(snapshot));
        }
      }
  }

  private static void hydrate(MinecraftServer server, Session s, ServerServices svc, Trader t) {
    t.busy = true;
    UUID id = t.id;
    svc.tradeStorage(
            store ->
                store.transaction(
                    tx -> {
                      var binding = MarketStore.binding(tx::query, id);
                      return new Snapshot(
                          StockStore.trader(tx::query, id),
                          binding,
                          binding == null ? null : StockStore.market(tx::query, binding.market()));
                    }))
        .whenComplete(
            (snapshot, error) ->
                server.execute(
                    () -> {
                      t.busy = false;
                      if (error != null || snapshot.stock() == null || snapshot.binding() == null) {
                        t.nextTry = Integer.toUnsignedLong(server.getTickCount()) + 200;
                        t.reason = "OWNED_TRADER_SOURCE_UNAVAILABLE";
                        fail(t, t.reason);
                        return;
                      }
                      t.saved = snapshot.stock();
                      t.binding = snapshot.binding();
                      t.reason = "";
                      var m = s.markets.computeIfAbsent(t.binding.market(), Market::new);
                      if (m.saved == null) m.saved = snapshot.market();
                    }));
  }

  private static void relocate(MinecraftServer server, Session s, ServerServices svc, Trader t) {
    t.busy = true;
    var target = t.relocate;
    UUID id = t.id;
    svc.tradeStorage(
            store -> {
              var denomination =
                  store.transaction(
                      tx ->
                          ca.emilybelnavis.mcmods.economy.journal.RebaseSettlement.denomination(
                              tx::query));
              if (denomination.isEmpty())
                throw new IllegalStateException("World denomination unavailable");
              if (!store.transaction(
                  tx ->
                      ca.emilybelnavis.mcmods.economy.trade.catalog.VillageMarketSource.ready(
                          tx::query))) {
                ca.emilybelnavis.mcmods.economy.trade.catalog.VillageMarketSource.step(
                    store, denomination.get().currency());
                return null;
              }
              var result =
                  MarketStore.resolve(
                      store, id, target, target, denomination.get().currency(), true);
              if (result.pending()) return null;
              return store.transaction(
                  tx ->
                      new Snapshot(
                          StockStore.trader(tx::query, id),
                          result.binding(),
                          StockStore.market(tx::query, result.binding().market())));
            })
        .whenComplete(
            (snapshot, error) ->
                server.execute(
                    () -> {
                      t.busy = false;
                      if (error != null) {
                        t.reason = "WORKSTATION_RELOCATION_UNAVAILABLE";
                        fail(t, t.reason);
                        return;
                      }
                      if (snapshot == null) return;
                      t.binding = snapshot.binding();
                      t.saved = snapshot.stock();
                      var m = s.markets.computeIfAbsent(t.binding.market(), Market::new);
                      if (m.saved == null) m.saved = snapshot.market();
                      if (target.equals(t.relocate)) {
                        t.relocate = null;
                        t.reason = "";
                      }
                    }));
  }

  private static void checkpointTrader(
      MinecraftServer server, Session s, ServerServices svc, Trader t) {
    t.busy = true;
    int observed = t.observed, eligible = t.eligible;
    var sample = new StockFacts.TickSample(s.id, t.last, observed, eligible);
    UUID id = t.id;
    // Flags certify the credited samples observed above, not current/offline conditions.
    svc.tradeStorage(
            store ->
                StockStore.checkpointTrader(
                    store, id, sample, new StockFacts.Eligibility(true, true, true, true)))
        .whenComplete(
            (saved, error) ->
                server.execute(
                    () -> {
                      t.busy = false;
                      if (error != null) {
                        t.reason = "TRADER_CHECKPOINT_UNAVAILABLE";
                        fail(t, t.reason);
                        return;
                      }
                      t.saved = saved;
                      t.observed -= observed;
                      t.eligible -= eligible;
                      t.reason = "";
                    }));
  }

  private static void checkpointMarket(
      MinecraftServer server, Session s, ServerServices svc, Market m) {
    m.busy = true;
    int observed = m.observed;
    var sample = new StockFacts.TickSample(s.id, m.last, observed, observed);
    svc.tradeStorage(store -> StockStore.checkpointMarket(store, m.id, sample))
        .whenComplete(
            (saved, error) ->
                server.execute(
                    () -> {
                      m.busy = false;
                      if (error != null) {
                        m.reason = "MARKET_CHECKPOINT_UNAVAILABLE";
                        return;
                      }
                      m.saved = saved;
                      m.observed -= observed;
                      m.reason = "";
                    }));
  }

  private static void fail(Trader t, String reason) {
    var pending = List.copyOf(t.waiters);
    t.waiters.clear();
    pending.forEach(f -> f.completeExceptionally(new IllegalStateException(reason)));
  }

  private static AbstractVillager entity(MinecraftServer server, UUID id) {
    for (var level : server.getAllLevels()) {
      var entity = level.getEntity(id);
      if (entity instanceof AbstractVillager trader && trader.isAlive() && TradeHooks.owned(trader))
        return trader;
    }
    return null;
  }

  public static void stopping(MinecraftServer server) {
    ENABLED.remove(server);
    var s = SESSIONS.remove(server);
    var svc = services(server);
    if (s == null) return;
    s.stopping = true;
    for (var t : s.traders.values()) {
      if (svc != null && !t.busy && t.observed > 0) checkpointTrader(server, s, svc, t);
      fail(t, "SERVER_STOPPING");
    }
    if (svc != null)
      for (var m : s.markets.values())
        if (!m.busy && m.observed > 0) checkpointMarket(server, s, svc, m);
  }
}
