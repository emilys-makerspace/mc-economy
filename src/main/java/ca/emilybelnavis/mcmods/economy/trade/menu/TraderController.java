package ca.emilybelnavis.mcmods.economy.trade.menu;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.BankingInventory;
import ca.emilybelnavis.mcmods.economy.cash.*;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.pricing.ServicePricing;
import ca.emilybelnavis.mcmods.economy.simulation.SimulationConfig;
import ca.emilybelnavis.mcmods.economy.simulation.model.Rational;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import ca.emilybelnavis.mcmods.economy.trade.cash.*;
import ca.emilybelnavis.mcmods.economy.trade.catalog.*;
import ca.emilybelnavis.mcmods.economy.trade.custody.NpcDeliveries;
import ca.emilybelnavis.mcmods.economy.trade.custody.TraderInventory;
import ca.emilybelnavis.mcmods.economy.trade.entity.*;
import ca.emilybelnavis.mcmods.economy.trade.market.*;
import ca.emilybelnavis.mcmods.economy.trade.pricing.*;
import ca.emilybelnavis.mcmods.economy.trade.settlement.*;
import ca.emilybelnavis.mcmods.economy.trade.stock.*;
import ca.emilybelnavis.mcmods.economy.valuation.ValuationRuntime;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.ItemStack;

/** Authenticated server-thread merchant flow. Worker closures contain detached facts only. */
public final class TraderController {
  private TraderController() {}

  private static final Map<TraderMenu, Flow> FLOWS = new WeakHashMap<>();
  private static final Map<FoundationStore, Books> BOOKS =
      new WeakHashMap<>(); // confined to storage worker
  private static final Map<ServerServices, Deque<Flow>> CLOSE_RETRIES = new IdentityHashMap<>();

  private record Books(NpcAccountFx account, NpcCashFx cash) {}

  private record Delivery(Custody.Case current, Custody.Case originalInput) {}

  private record View(
      TradeCatalog.Capture catalog,
      StockFacts.Trader stock,
      MarketFacts.Binding binding,
      MarketFacts.Market market,
      List<AccountIdentity> accounts,
      TradePriceSource.EmeraldV1 source,
      List<TradeCatalog.Offer> cohort,
      String cohortHash,
      Map<Integer, ReferenceSource> references,
      Map<Integer, List<Pricing.Comparison>> referenceComparisons,
      Set<Integer> active,
      Set<Integer> available) {}

  private static final class Flow {
    final ServerServices services;
    final PlayerId actor;
    final UUID session;
    final AtomicBoolean live = new AtomicBoolean(true);
    View view;
    long sourceGeneration;
    ValuationRuntime.Lease lease;
    int offer, quantity = 1, page;
    String rail = "";
    AccountId account;
    EconomyCurrency cash;
    boolean busy;
    String section = "browse";
    Pending pending;
    String status = "Choose an offer";

    Flow(ServerServices services, PlayerId actor, UUID session) {
      this.services = services;
      this.actor = actor;
      this.session = session;
    }
  }

  /** Mutable authorization is only revoked on the server thread; workers read its atomic flag. */
  private static final class Permit {
    final AtomicBoolean live = new AtomicBoolean(true);
    List<ItemStack> inventory;
    final String reputation, cohort;
    final UUID session, trader;
    final int ordinal, quantity;
    volatile boolean capacity;
    MinecraftServer server;
    WorldId world;
    long sourceGeneration;
    ValuationRuntime.Lease lease;
    ReferenceSource reference;
    Rational reputationValue = Rational.ZERO;

    Permit(
        List<ItemStack> inventory,
        String reputation,
        String cohort,
        UUID session,
        UUID trader,
        int ordinal,
        int quantity) {
      this.inventory = inventory;
      this.reputation = reputation;
      this.cohort = cohort;
      this.session = session;
      this.trader = trader;
      this.ordinal = ordinal;
      this.quantity = quantity;
    }
  }

  private static final class Pending {
    final UUID operation, input, output, cashOutput;
    final AtomicBoolean reserveStarted = new AtomicBoolean();
    final TraderInventory.Snapshot snapshot;
    final Permit permit;
    final TradeCatalog.Offer offer;
    final String rail;
    final List<Pricing.Comparison> comparisons;
    volatile NpcFacts.Quote accountQuote;
    volatile NpcCashFacts.Quote cashQuote;
    volatile ReferenceNpcFacts.Quote referenceAccountQuote;
    volatile ReferenceCashFacts.Quote referenceCashQuote;
    Custody.Case inputCase;
    List<ItemStack> outputs = List.of();
    boolean held, committed;

    Pending(
        UUID operation,
        TraderInventory.Snapshot snapshot,
        Permit permit,
        TradeCatalog.Offer offer,
        String rail,
        List<Pricing.Comparison> comparisons) {
      this.operation = operation;
      this.snapshot = snapshot;
      this.permit = permit;
      this.offer = offer;
      this.rail = rail;
      this.comparisons = comparisons;
      input = snapshot == null ? null : stable(operation, "input");
      output = offer.side() == Pricing.Side.BUY ? stable(operation, "goods") : null;
      cashOutput = rail.equals("cash") ? stable(operation, "cash") : null;
    }

    boolean reference() {
      return permit.reference != null;
    }

    boolean cash() {
      return cashQuote != null || referenceCashQuote != null;
    }

    long cashOutputMinor() {
      return referenceCashQuote != null
          ? referenceCashQuote.cashOutputMinor()
          : cashQuote != null ? cashQuote.cashOutputMinor() : 0;
    }

    boolean account() {
      return accountQuote != null || referenceAccountQuote != null;
    }

    EconomyCurrency marketCurrency() {
      return referenceAccountQuote != null
          ? referenceAccountQuote.priceRequest().basis().currency()
          : referenceCashQuote != null
              ? referenceCashQuote.pricing().request().basis().currency()
              : accountQuote != null
                  ? accountQuote.priceRequest().basis().currency()
                  : cashQuote.pricing().request().basis().currency();
    }

    long principal() {
      return referenceAccountQuote != null
          ? referenceAccountQuote.price().minor()
          : referenceCashQuote != null
              ? referenceCashQuote.pricing().price().minor()
              : accountQuote != null
                  ? accountQuote.price().minor()
                  : cashQuote.pricing().price().minor();
    }

    long unitMinor() {
      return referenceAccountQuote != null
          ? referenceAccountQuote.price().unitMinor()
          : referenceCashQuote != null ? referenceCashQuote.pricing().price().unitMinor() : 0;
    }

    NpcAccountFx.Quote accountFx() {
      return referenceAccountQuote != null
          ? referenceAccountQuote.fx()
          : accountQuote == null ? null : accountQuote.fx();
    }

    NpcCashFx.Quote cashFx() {
      return referenceCashQuote != null
          ? referenceCashQuote.fx()
          : cashQuote == null ? null : cashQuote.fx();
    }

    EconomyCurrency cashCurrency() {
      return referenceCashQuote != null
          ? referenceCashQuote.terms().cashCurrency()
          : cashQuote.terms().cashCurrency();
    }

    long inputCashMinor() {
      return referenceCashQuote != null
          ? referenceCashQuote.inputCashMinor()
          : cashQuote.inputCashMinor();
    }

    long requiredCashMinor() {
      return referenceCashQuote != null
          ? referenceCashQuote.requiredCashMinor()
          : cashQuote.requiredCashMinor();
    }

    List<CashIssuance.Lot> outputLots() {
      return referenceCashQuote != null
          ? referenceCashQuote.outputLots()
          : cashQuote != null ? cashQuote.outputLots() : List.of();
    }

    long expires() {
      return referenceAccountQuote != null
          ? referenceAccountQuote.expiresMillis()
          : referenceCashQuote != null
              ? referenceCashQuote.expiresMillis()
              : accountQuote != null
                  ? accountQuote.expiresMillis()
                  : cashQuote != null ? cashQuote.expiresMillis() : 0;
    }

    UUID quoteId() {
      return referenceAccountQuote != null
          ? referenceAccountQuote.id()
          : referenceCashQuote != null
              ? referenceCashQuote.id()
              : accountQuote != null
                  ? accountQuote.id()
                  : cashQuote != null ? cashQuote.id() : null;
    }
  }

  public static void open(ServerPlayer player, AbstractVillager entity) {
    if (!player.server.isSameThread()) throw new IllegalStateException("Server thread required");
    var services = Economy.serverServices(player.server);
    if (services == null
        || !TradeRuntime.enabled(player.server)
        || !entity.isAlive()
        || entity.distanceToSqr(player) > 36) return;
    UUID session = UUID.randomUUID();
    player.openMenu(
        new SimpleMenuProvider(
            (id, inventory, who) -> new TraderMenu(id, inventory, entity.getUUID(), session),
            Component.literal("Trader")),
        b -> {
          b.writeUUID(entity.getUUID());
          b.writeUUID(session);
        });
    if (!(player.containerMenu instanceof TraderMenu menu)) return;
    var flow = new Flow(services, new PlayerId(player.getUUID()), session);
    FLOWS.put(menu, flow);
    reload(player, menu, flow);
  }

  public static void closed(ServerPlayer player, TraderMenu menu) {
    var flow = FLOWS.remove(menu);
    if (flow != null) closeFlow(flow);
  }

  private static void closeFlow(Flow flow) {
    flow.live.set(false);
    var pending = flow.pending;
    if (pending == null) return;
    pending.permit.live.set(false);
    if (pending.input != null && !pending.reserveStarted.get() && pending.quoteId() != null)
      abandonClosedQuote(flow);
  }

  private static void abandonClosedQuote(Flow flow) {
    if (flow.services.state() != ServerServices.State.READY) return;
    var pending = flow.pending;
    var actor = flow.actor;
    flow.services
        .tradeStorage(
            store -> {
              abandonUntouched(store, actor, pending);
              return true;
            })
        .whenComplete(
            (ignored, error) -> {
              if (error instanceof StorageException storage
                  && storage.kind() == StorageException.Kind.OVERLOAD)
                CLOSE_RETRIES
                    .computeIfAbsent(flow.services, key -> new ArrayDeque<>())
                    .addLast(flow);
            });
  }

  private static void abandonUntouched(FoundationStore store, PlayerId actor, Pending pending) {
    UUID quote = pending.quoteId();
    if (quote != null && !pending.reserveStarted.get()) {
      if (pending.reference())
        ReferenceInputReturns.abandon(
            store, actor, quote, pending.cash(), () -> !pending.reserveStarted.get());
      else
        NpcInputReturns.abandon(
            store, actor, quote, pending.cash(), () -> !pending.reserveStarted.get());
    }
  }

  public static void stopping(MinecraftServer server) {
    var services = Economy.serverServices(server);
    FLOWS
        .values()
        .removeIf(
            flow -> {
              if (flow.services != services) return false;
              closeFlow(flow);
              return true;
            });
    CLOSE_RETRIES.remove(services);
  }

  public static void tick(MinecraftServer server) {
    var services = Economy.serverServices(server);
    var retries = CLOSE_RETRIES.get(services);
    if (retries != null && !retries.isEmpty()) {
      var flow = retries.removeFirst();
      if (retries.isEmpty()) CLOSE_RETRIES.remove(services);
      abandonClosedQuote(flow);
    }
    for (var entry : new ArrayList<>(FLOWS.entrySet())) {
      var menu = entry.getKey();
      var flow = entry.getValue();
      var player = server.getPlayerList().getPlayer(flow.actor.value());
      if (flow.services != services) continue;
      if (player == null || !live(player, menu, flow)) {
        FLOWS.remove(menu);
        closeFlow(flow);
        continue;
      }
      var pending = flow.pending;
      if (pending == null
          && !flow.busy
          && flow.view != null
          && (!Objects.equals(flow.lease, ValuationRuntime.lease(server))
              || flow.sourceGeneration != ReferenceTrading.generation(flow.services.worldId()))) {
        reload(player, menu, flow);
        continue;
      }
      if (pending == null || pending.committed) continue;
      if (!flow.busy
          && !pending.reserveStarted.get()
          && pending.expires() > 0
          && pending.expires() <= System.currentTimeMillis()) {
        cancel(player, menu, flow);
        continue;
      }
      if (pending.permit.live.get() && !witness(player, menu, pending.permit)) {
        pending.permit.live.set(false);
        if (!flow.busy) {
          flow.status = "Trader or inventory changed; cancel the quote to continue";
          render(menu, flow);
        }
      }
    }
  }

  public static void action(ServerPlayer player, TraderMenu menu, String action, String value) {
    var flow = FLOWS.get(menu);
    if (flow == null || !live(player, menu, flow)) return;
    if (!menu.choices().stream()
        .anyMatch(c -> c.action().equals(action) && c.value().equals(value))) return;
    if (action.equals("exit")) {
      player.closeContainer();
      return;
    }
    if (flow.busy) return;
    try {
      switch (action) {
        case "offer" -> {
          reset(flow);
          flow.offer = Integer.parseInt(value);
          flow.quantity = 1;
          reload(player, menu, flow);
        }
        case "rail" -> {
          reset(flow);
          flow.rail = value;
          flow.account = null;
          flow.cash = null;
          flow.section = value;
          render(menu, flow);
        }
        case "account" -> {
          reset(flow);
          var id = new AccountId(UUID.fromString(value));
          if (flow.view.accounts().stream().noneMatch(a -> a.id().equals(id))) return;
          flow.account = id;
          flow.section = "browse";
          render(menu, flow);
        }
        case "currency" -> {
          reset(flow);
          flow.cash = EconomyCurrency.valueOf(value);
          flow.section = "browse";
          render(menu, flow);
        }
        case "quantity" -> {
          reset(flow);
          int n = Integer.parseInt(value);
          if (n < 1 || n > 1024) return;
          flow.quantity = n;
          reload(player, menu, flow);
        }
        case "quote" -> quote(player, menu, flow);
        case "confirm" -> confirm(player, menu, flow);
        case "cancel" -> cancel(player, menu, flow);
        case "claim" -> claims(player, menu, flow, value);
        case "next" -> {
          if (menu.page().equals("claims")) claimPage(player, menu, flow, value);
          else {
            flow.page++;
            render(menu, flow);
          }
        }
        case "back" -> {
          if (menu.page().equals("claims")) claimPage(player, menu, flow, "");
          else {
            flow.page = Math.max(0, flow.page - 1);
            render(menu, flow);
          }
        }
        case "browse" -> {
          flow.section = "browse";
          if (menu.page().equals("claims")) render(menu, flow);
          else reload(player, menu, flow);
        }
        default -> {}
      }
    } catch (RuntimeException failure) {
      flow.status = message(failure);
      flow.busy = false;
      render(menu, flow);
    }
  }

  private static void reset(Flow flow) {
    if (flow.pending != null) {
      if (flow.pending.held || flow.pending.committed || flow.pending.cash())
        throw new IllegalStateException("Finish the original delivery or claim first");
      flow.pending.permit.live.set(false);
    }
    flow.pending = null;
  }

  private static boolean live(ServerPlayer player, TraderMenu menu, Flow flow) {
    return flow.live.get()
        && FLOWS.get(menu) == flow
        && menu.session().equals(flow.session)
        && Economy.serverServices(player.server) == flow.services
        && flow.services.state() == ServerServices.State.READY
        && TradeRuntime.enabled(player.server)
        && menu.valid(player);
  }

  private static void reload(ServerPlayer player, TraderMenu menu, Flow flow) {
    if (flow.pending != null && (flow.pending.held || flow.pending.committed)) {
      render(menu, flow);
      return;
    }
    reset(flow);
    flow.busy = true;
    render(menu, flow);
    var trader = menu.traderId();
    var actor = flow.actor;
    var world = flow.services.worldId();
    var config = flow.services.simulationConfig();
    ReferenceTrading.capture(player.server, menu.entity(player));
    var lease = ValuationRuntime.lease(player.server);
    flow.lease = lease;
    flow.sourceGeneration = ReferenceTrading.generation(flow.services.worldId());
    var reputations = reputations(menu.entity(player), player);
    int quantity = flow.quantity;
    async(
        player,
        menu,
        flow,
        flow.services.tradeStorage(
            store ->
                store.transaction(
                    tx ->
                        view(
                            tx::query,
                            world,
                            actor,
                            trader,
                            config,
                            lease,
                            reputations,
                            quantity))),
        (view) -> {
          flow.view = view;
          selectAvailable(flow);
          flow.busy = false;
          flow.status = "Choose rail, quantity, and account or cash currency";
          render(menu, flow);
        });
  }

  private static void selectAvailable(Flow flow) {
    if (!flow.view.available().contains(flow.offer))
      flow.offer =
          flow.view.catalog().offers().stream()
              .map(TradeCatalog.Offer::ordinal)
              .filter(flow.view.available()::contains)
              .findFirst()
              .orElse(-1);
  }

  private static Map<Integer, Rational> reputations(AbstractVillager entity, ServerPlayer player) {
    var values = new HashMap<Integer, Rational>();
    for (int i = 0; i < entity.getOffers().size(); i++)
      values.put(i, reputationValue(entity, player, i));
    return Map.copyOf(values);
  }

  private static View view(
      JournalEngine.ReadView reads,
      WorldId world,
      PlayerId actor,
      UUID trader,
      SimulationConfig config) {
    return view(reads, world, actor, trader, config, null, Map.of(), 1);
  }

  private static View view(
      JournalEngine.ReadView reads,
      WorldId world,
      PlayerId actor,
      UUID trader,
      SimulationConfig config,
      ValuationRuntime.Lease lease,
      Map<Integer, Rational> reputations,
      int quantity) {
    var manifest = ca.emilybelnavis.mcmods.economy.simulation.SimulationLifecycle.read(reads);
    if (manifest == null
        || !manifest.world().equals(world)
        || !manifest.config().equals(config)
        || !manifest.config().traders())
      throw new IllegalArgumentException("Trader production policy unavailable");
    var stock = StockStore.trader(reads, trader);
    var binding = MarketStore.binding(reads, trader);
    if (stock == null || binding == null)
      throw new IllegalArgumentException("Trader is still preparing");
    var catalog = CatalogStore.read(reads, trader, stock.catalogRevision());
    var market = MarketStore.market(reads, binding.market());
    var fx = FxService.snapshot(reads);
    if (catalog == null || market == null || fx == null)
      throw new IllegalArgumentException("Trader pricing unavailable");
    var source =
        new TradePriceSource.EmeraldV1(
            config.sourceId(),
            config.sourceRevision(),
            config.cadPerEmerald(),
            fx.id(),
            market.currency(),
            Rational.of(fx.rates().get(market.currency()))
                .divide(Rational.of(fx.rates().get(EconomyCurrency.CAD))));
    var cohort = new ArrayList<TradeCatalog.Offer>();
    var identities = new ArrayList<String>();
    var rows =
        reads.query(
            "SELECT trader_id FROM trade_market_bindings WHERE market_id=? ORDER BY trader_id",
            List.of(market.id().toString()),
            33);
    if (rows.size() > 32)
      throw new IllegalArgumentException("Market comparison cohort unavailable");
    for (var row : rows) {
      UUID id = UUID.fromString(row.get("trader_id"));
      var member = StockStore.trader(reads, id);
      if (member == null) throw new IllegalArgumentException("Market comparison stock unavailable");
      var capture = CatalogStore.read(reads, id, member.catalogRevision());
      if (capture == null)
        throw new IllegalArgumentException("Market comparison catalog unavailable");
      identities.add(id + ":" + capture.revision() + ":" + CatalogFacts.digest(capture));
      cohort.addAll(capture.offers());
      if (cohort.size() > 1024) throw new IllegalArgumentException("Market comparison bound");
    }
    var active = new HashSet<Integer>();
    boolean referencePolicy = ReferenceTrading.policyActive(reads, catalog.trader());
    for (var offer : catalog.offers())
      if (referencePolicy
          || TradeSources.current(reads, market.id(), offer.goods().priceKey()) != null)
        active.add(offer.ordinal());
    var references =
        lease == null
            ? Map.<Integer, ReferenceSource>of()
            : ReferenceTrading.sources(reads, world, catalog, System.currentTimeMillis());
    var available = new HashSet<Integer>();
    var referenceComparisons = new HashMap<Integer, List<Pricing.Comparison>>();
    for (var offer : catalog.offers()) {
      if (!active.contains(offer.ordinal())) available.add(offer.ordinal());
      else if (references.containsKey(offer.ordinal())
          && referenceAvailable(
              reads,
              references.get(offer.ordinal()),
              catalog,
              offer,
              actor,
              quantity,
              reputations.getOrDefault(offer.ordinal(), Rational.ZERO))) {
        available.add(offer.ordinal());
        referenceComparisons.put(
            offer.ordinal(), references.get(offer.ordinal()).comparisons(reads, catalog, offer));
      }
    }
    return new View(
        catalog,
        stock,
        binding,
        market,
        AccountCatalog.openBanks(reads, new PartyRef(world, new PartyRef.Player(actor))),
        source,
        List.copyOf(cohort),
        TradeCatalog.digest(String.join("|", identities)),
        Map.copyOf(references),
        Map.copyOf(referenceComparisons),
        Set.copyOf(active),
        Set.copyOf(available));
  }

  private static boolean referenceAvailable(
      JournalEngine.ReadView reads,
      ReferenceSource source,
      TradeCatalog.Capture catalog,
      TradeCatalog.Offer offer,
      PlayerId actor,
      int quantity,
      Rational reputation) {
    try {
      if (offer.goods().match() != TradeCatalog.Match.EXACT_COMPONENTS
          || !ReferenceTrading.available(reads, source, catalog, offer, System.currentTimeMillis()))
        return false;
      var market = source.ref().market();
      var prior = TradeSources.price(reads, market, offer.goods().priceKey());
      var ticks = StockStore.market(reads, market);
      long tick = ticks == null ? 0 : ticks.eligibleTicks();
      var comparisons = source.comparisons(reads, catalog, offer);
      var raw = source.basis(offer.emeraldCount(), offer.goodsCount(), offer.standardQuantity());
      var basis = ReferencePricing.normalizedBasis(offer.side(), raw, comparisons.getFirst());
      var request =
          new ReferencePricing.Request(
              new Pricing.Key(
                  market,
                  offer.goods().priceKey(),
                  actor.value(),
                  offer.side(),
                  source.ref().epoch()),
              basis,
              offer.standardQuantity(),
              Math.multiplyExact((long) offer.goodsCount(), quantity),
              prior == null ? new Pricing.Demand(Rational.ZERO, 0) : prior.demand(),
              tick,
              reputation,
              comparisons);
      return ReferencePricing.quote(request, source.ref().implementation()).available();
    } catch (RuntimeException unsupported) {
      return false;
    }
  }

  private static void quote(ServerPlayer player, TraderMenu menu, Flow flow) {
    if (flow.view == null
        || flow.offer < 0
        || !flow.view.available().contains(flow.offer)
        || flow.rail.isEmpty()
        || flow.rail.equals("account") && flow.account == null
        || flow.rail.equals("cash") && flow.cash == null)
      throw new IllegalArgumentException("Select a rail and its account or currency first");
    reset(flow);
    flow.busy = true;
    flow.status = "Flushing trader activity…";
    render(menu, flow);
    async(
        player,
        menu,
        flow,
        TradeRuntime.flush(menu.entity(player)),
        ignored -> {
          var trader = menu.traderId();
          var actor = flow.actor;
          var world = flow.services.worldId();
          var config = flow.services.simulationConfig();
          ReferenceTrading.capture(player.server, menu.entity(player));
          var lease = ValuationRuntime.lease(player.server);
          flow.lease = lease;
          flow.sourceGeneration = ReferenceTrading.generation(flow.services.worldId());
          var reputations = reputations(menu.entity(player), player);
          int quantity = flow.quantity;
          async(
              player,
              menu,
              flow,
              flow.services.tradeStorage(
                  store ->
                      store.transaction(
                          tx ->
                              view(
                                  tx::query,
                                  world,
                                  actor,
                                  trader,
                                  config,
                                  lease,
                                  reputations,
                                  quantity))),
              fresh -> {
                flow.view = fresh;
                if (!fresh.available().contains(flow.offer)) {
                  selectAvailable(flow);
                  flow.busy = false;
                  flow.status = "Choose an offer";
                  render(menu, flow);
                  return;
                }
                book(player, menu, flow);
              });
        });
  }

  private static void book(ServerPlayer player, TraderMenu menu, Flow flow) {
    var offer = flow.view.catalog().offers().get(flow.offer);
    if (!NativeCatalog.matches(menu.entity(player), offer))
      throw new IllegalArgumentException("Native offer changed");
    UUID original = UUID.randomUUID();
    var selected =
        TraderItems.select(
            player, offer, flow.quantity, flow.rail.equals("cash") ? flow.cash : null);
    var snapshot =
        selected.isEmpty()
            ? null
            : TraderInventory.snapshot(player, flow.services.worldId(), original, selected);
    if (flow.rail.equals("cash") && snapshot == null)
      throw new IllegalArgumentException("Cash input batch is empty");
    if (snapshot != null
        && !TraderInventory.matchesRequirements(
            player, snapshot, NpcTrades.inputItems(offer, flow.quantity)))
      throw new IllegalArgumentException("Exact goods inputs unavailable");
    var equivalents =
        flow.view.cohort().stream().filter(o -> TradeCatalog.equivalent(offer, o)).toList();
    var reference = flow.view.references().get(flow.offer);
    if (flow.view.active().contains(flow.offer) && reference == null) {
      flow.busy = false;
      flow.status = "Choose an offer";
      render(menu, flow);
      return;
    }
    var comparisons =
        reference == null
            ? CatalogPricing.compare(offer, equivalents, true, flow.view.source()).comparisons()
            : flow.view.referenceComparisons().get(flow.offer);
    String reputation = reputation(menu.entity(player), player);
    var permit =
        new Permit(
            copyInventory(player),
            reputation,
            flow.view.cohortHash(),
            flow.session,
            menu.traderId(),
            flow.offer,
            flow.quantity);
    permit.server = player.server;
    permit.world = flow.services.worldId();
    permit.sourceGeneration = flow.sourceGeneration;
    permit.lease = flow.lease;
    permit.reference = reference;
    permit.reputationValue = reputationValue(menu.entity(player), player, flow.offer);
    var pending = new Pending(original, snapshot, permit, offer, flow.rail, comparisons);
    flow.pending = pending;
    var actor = flow.actor;
    var world = flow.services.worldId();
    var source = flow.view.source();
    var revision = flow.view.catalog().revision();
    var account = flow.account;
    var cashCurrency = flow.cash;
    var slots =
        snapshot == null ? List.<Deposits.Slot>of() : TraderInventory.cashSlots(player, snapshot);
    var pricing = flow.services.servicePricing();
    var frozenConfig = flow.services.simulationConfig();
    var reputationValue = reputationValue(menu.entity(player), player, flow.offer);
    var accountTerms =
        reference == null && flow.rail.equals("account")
            ? new NpcFacts.Terms(
                original,
                actor,
                flow.session,
                menu.traderId(),
                revision,
                flow.offer,
                flow.quantity,
                account,
                source.source(),
                source,
                reputationValue,
                1,
                reputation,
                comparisons,
                pending.input,
                snapshot == null ? "" : snapshot.encode(),
                pending.output,
                original.toString(),
                1)
            : null;
    var cashTerms =
        reference == null && flow.rail.equals("cash")
            ? new NpcCashFacts.Terms(
                original,
                actor,
                flow.session,
                menu.traderId(),
                revision,
                flow.offer,
                flow.quantity,
                cashCurrency,
                slots,
                source.source(),
                source,
                reputationValue,
                1,
                reputation,
                comparisons,
                pending.input,
                snapshot.encode(),
                pending.output,
                pending.cashOutput,
                original.toString(),
                1)
            : null;
    var referenceAccountTerms =
        reference != null && flow.rail.equals("account")
            ? new ReferenceNpcFacts.Terms(
                original,
                actor,
                flow.session,
                menu.traderId(),
                revision,
                flow.offer,
                flow.quantity,
                account,
                reference.ref().epoch(),
                reference,
                reputationValue,
                1,
                reputation,
                comparisons,
                pending.input,
                snapshot == null ? "" : snapshot.encode(),
                pending.output,
                original.toString(),
                1)
            : null;
    var referenceCashTerms =
        reference != null && flow.rail.equals("cash")
            ? new ReferenceCashFacts.Terms(
                original,
                actor,
                flow.session,
                menu.traderId(),
                revision,
                flow.offer,
                flow.quantity,
                cashCurrency,
                slots,
                reference.ref().epoch(),
                reference,
                reputationValue,
                1,
                reputation,
                comparisons,
                pending.input,
                snapshot.encode(),
                pending.output,
                pending.cashOutput,
                original.toString(),
                1)
            : null;
    async(
        player,
        menu,
        flow,
        flow.services.tradeMonetaryStorage(
            actor,
            store -> {
              var books = books(store, pricing);
              if (referenceAccountTerms != null || referenceCashTerms != null) {
                if (pending.input != null) {
                  String financial =
                      referenceAccountTerms != null
                          ? ReferenceNpcTrades.financialIntent(world, original, actor)
                          : ReferenceCashTrades.financialIntent(world, original, actor);
                  String inputSource =
                      referenceAccountTerms != null
                          ? ReferenceNpcTrades.inputSource(referenceAccountTerms)
                          : ReferenceCashTrades.inputSource(referenceCashTerms);
                  String destination =
                      referenceAccountTerms != null
                          ? ReferenceNpcTrades.inputDestination(referenceAccountTerms)
                          : ReferenceCashTrades.inputDestination(referenceCashTerms);
                  reserve(
                      store,
                      actor,
                      pending.input,
                      new Custody.Terms(
                          actor, snapshot.encode(), inputSource, destination, financial),
                      permit);
                }
                if (referenceAccountTerms != null)
                  pending.referenceAccountQuote =
                      ValuationRuntime.locked(
                          permit.lease,
                          () ->
                              new ReferenceNpcTrades(
                                      store,
                                      books.account(),
                                      Clock.systemUTC(),
                                      (phase, t, q, r) ->
                                          admitReference(
                                              permit,
                                              phase == ReferenceNpcTrades.Phase.SETTLE,
                                              r,
                                              world,
                                              actor,
                                              t.trader(),
                                              frozenConfig,
                                              t.comparisons(),
                                              offer))
                                  .quote(referenceAccountTerms));
                else
                  pending.referenceCashQuote =
                      ValuationRuntime.locked(
                          permit.lease,
                          () ->
                              new ReferenceCashTrades(
                                      store,
                                      books.cash(),
                                      Clock.systemUTC(),
                                      (phase, t, q, r) ->
                                          admitReference(
                                              permit,
                                              phase == ReferenceCashTrades.Phase.SETTLE,
                                              r,
                                              world,
                                              actor,
                                              t.trader(),
                                              frozenConfig,
                                              t.comparisons(),
                                              offer))
                                  .quote(referenceCashTerms));
              } else {
                if (pending.input != null) {
                  String financial =
                      accountTerms != null
                          ? NpcTrades.financialIntent(world, original, actor)
                          : NpcCashTrades.financialIntent(world, original, actor);
                  String inputSource =
                      accountTerms != null
                          ? NpcTrades.inputSource(accountTerms)
                          : NpcCashTrades.inputSource(cashTerms);
                  String destination =
                      accountTerms != null
                          ? NpcTrades.inputDestination(accountTerms)
                          : NpcCashTrades.inputDestination(cashTerms);
                  reserve(
                      store,
                      actor,
                      pending.input,
                      new Custody.Terms(
                          actor, snapshot.encode(), inputSource, destination, financial),
                      permit);
                }
                if (accountTerms != null)
                  pending.accountQuote =
                      new NpcTrades(
                              store,
                              books.account(),
                              Clock.systemUTC(),
                              (phase, t, q, r) ->
                                  admit(
                                      permit,
                                      phase == NpcTrades.Phase.SETTLE,
                                      r,
                                      world,
                                      actor,
                                      t.trader(),
                                      frozenConfig,
                                      t.comparisons(),
                                      offer))
                          .quote(accountTerms);
                else
                  pending.cashQuote =
                      new NpcCashTrades(
                              store,
                              books.cash(),
                              Clock.systemUTC(),
                              (phase, t, q, r) ->
                                  admit(
                                      permit,
                                      phase == NpcCashTrades.Phase.SETTLE,
                                      r,
                                      world,
                                      actor,
                                      t.trader(),
                                      frozenConfig,
                                      t.comparisons(),
                                      offer))
                          .quote(cashTerms);
              }
              // A close racing with booking can happen before the server thread sees the quote.
              if (!flow.live.get() && pending.input != null)
                abandonUntouched(store, actor, pending);
              return true;
            }),
        ignored -> {
          var output = new ArrayList<>(TraderItems.goods(player, offer, flow.quantity));
          if (pending.cash()) for (var lot : pending.outputLots()) output.addAll(cashStacks(lot));
          pending.outputs = List.copyOf(output);
          if (!TraderInventory.fitsCombined(player, snapshot, pending.outputs))
            throw new IllegalArgumentException(
                "Combined goods and cash/change do not fit; inputs untouched");
          pending.permit.capacity = true;
          flow.busy = false;
          flow.status = "Review the exact quote, then Confirm";
          render(menu, flow);
        });
  }

  private static Books books(FoundationStore store, ServicePricing pricing) {
    return BOOKS.computeIfAbsent(
        store,
        s ->
            new Books(
                new NpcAccountFx(new FxQuotes(s.snapshot().worldId()), pricing),
                new NpcCashFx(new FxQuotes(s.snapshot().worldId()), pricing)));
  }

  private static boolean admit(
      Permit permit,
      boolean settlement,
      JournalEngine.ReadView reads,
      WorldId world,
      PlayerId actor,
      UUID trader,
      SimulationConfig config,
      List<Pricing.Comparison> comparisons,
      TradeCatalog.Offer offer) {
    if (!permit.live.get() || settlement && !permit.capacity) return false;
    try {
      var current = view(reads, world, actor, trader, config);
      if (!current.cohortHash().equals(permit.cohort)) return false;
      var cohort =
          current.cohort().stream().filter(o -> TradeCatalog.equivalent(offer, o)).toList();
      return CatalogPricing.compare(offer, cohort, true, current.source())
          .comparisons()
          .equals(comparisons);
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private static boolean admitReference(
      Permit permit,
      boolean settlement,
      JournalEngine.ReadView reads,
      WorldId world,
      PlayerId actor,
      UUID trader,
      SimulationConfig config,
      List<Pricing.Comparison> comparisons,
      TradeCatalog.Offer offer) {
    if (!permit.live.get()
        || settlement && !permit.capacity
        || permit.sourceGeneration != ReferenceTrading.generation(world)
        || !ValuationRuntime.matches(permit.server, permit.lease)) return false;
    try {
      var current =
          view(
              reads,
              world,
              actor,
              trader,
              config,
              permit.lease,
              Map.of(offer.ordinal(), permit.reputationValue),
              permit.quantity);
      var source = current.references().get(offer.ordinal());
      return current.cohortHash().equals(permit.cohort)
          && current.available().contains(offer.ordinal())
          && source != null
          && source.equals(permit.reference)
          && source.comparisons(reads, current.catalog(), offer).equals(comparisons);
    } catch (RuntimeException unsupported) {
      return false;
    }
  }

  private static List<ItemStack> cashStacks(CashIssuance.Lot lot) {
    var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(lot.item()));
    int left = lot.issued();
    var result = new ArrayList<ItemStack>();
    while (left > 0) {
      int n = Math.min(left, 64);
      result.add(CashStackIdentity.marked(new ItemStack(item, n), lot.world(), lot.id()));
      left -= n;
    }
    return result;
  }

  private static List<ItemStack> copyInventory(ServerPlayer player) {
    var stacks = new ArrayList<ItemStack>(36);
    for (int slot = 0; slot < 36; slot++) stacks.add(player.getInventory().getItem(slot).copy());
    return stacks;
  }

  private static boolean inventoryMatches(ServerPlayer player, List<ItemStack> expected) {
    for (int slot = 0; slot < expected.size(); slot++)
      if (!ItemStack.matches(expected.get(slot), player.getInventory().getItem(slot))) return false;
    return true;
  }

  private static Rational reputationValue(AbstractVillager entity, ServerPlayer player) {
    Rational value =
        entity instanceof net.minecraft.world.entity.npc.Villager villager
            ? Rational.of(villager.getPlayerReputation(player)).divide(Rational.of(100))
            : Rational.ZERO;
    var hero = player.getEffect(net.minecraft.world.effect.MobEffects.HERO_OF_THE_VILLAGE);
    if (entity instanceof net.minecraft.world.entity.npc.Villager && hero != null)
      value = value.add(Rational.of(hero.getAmplifier() + 1).divide(Rational.of(20)));
    return value.max(Rational.of("-0.25")).min(Rational.of("0.25"));
  }

  /**
   * Existing special-price output is a signed discount relative to its original cost, without
   * vanilla demand.
   */
  private static Rational reputationValue(
      AbstractVillager entity, ServerPlayer player, int ordinal) {
    var offer = entity.getOffers().get(ordinal);
    int original = offer.getBaseCostA().getCount();
    return reputationValue(entity, player)
        .subtract(Rational.of(offer.getSpecialPriceDiff()).divide(Rational.of(original)))
        .max(Rational.of("-0.25"))
        .min(Rational.of("0.25"));
  }

  private static String reputation(AbstractVillager entity, ServerPlayer player) {
    return TradeCatalog.digest(
        entity.getUUID()
            + "|"
            + player.getUUID()
            + "|"
            + reputationValue(entity, player)
            + "|"
            + entity.saveWithoutId(new net.minecraft.nbt.CompoundTag()).get("Gossips")
            + "|"
            + entity.getOffers().stream()
                .map(o -> o.getSpecialPriceDiff() + ":" + o.getPriceMultiplier())
                .toList());
  }

  private static boolean witness(ServerPlayer player, TraderMenu menu, Permit permit) {
    return permit.sourceGeneration == ReferenceTrading.generation(permit.world)
        && (permit.reference == null || ValuationRuntime.matches(player.server, permit.lease))
        && menu.valid(player)
        && menu.session().equals(permit.session)
        && NativeCatalog.matches(
            menu.entity(player), FLOWS.get(menu).view.catalog().offers().get(permit.ordinal))
        && inventoryMatches(player, permit.inventory)
        && reputation(menu.entity(player), player).equals(permit.reputation);
  }

  private static void cancel(ServerPlayer player, TraderMenu menu, Flow flow) {
    var pending = flow.pending;
    if (pending == null) {
      render(menu, flow);
      return;
    }
    if (pending.committed)
      throw new IllegalStateException("Committed trade outputs remain under Claims");
    UUID quote = pending.quoteId();
    if (quote == null) {
      reset(flow);
      render(menu, flow);
      return;
    }
    var actor = flow.actor;
    boolean cash = pending.cash();
    flow.busy = true;
    flow.status = "Closing the original quote…";
    render(menu, flow);
    if (!pending.held) {
      if (pending.reserveStarted.get())
        throw new IllegalStateException("The original input attempt requires reconciliation");
      pending.permit.live.set(false);
      async(
          player,
          menu,
          flow,
          flow.services.tradeStorage(
              store -> {
                abandonUntouched(store, actor, pending);
                return true;
              }),
          ignored -> {
            flow.pending = null;
            flow.busy = false;
            flow.status = "Quote closed; no inputs were taken";
            render(menu, flow);
          });
      return;
    }
    var world = flow.services.worldId();
    var pricing = flow.services.servicePricing();
    async(
        player,
        menu,
        flow,
        flow.services.tradeStorage(store -> find(store, actor, pending.input)),
        input -> {
          var proof = TraderInventory.heldReturnProof(player, input, world);
          var verifier = TraderInventory.verifier(player);
          var live = flow.live;
          async(
              player,
              menu,
              flow,
              flow.services.tradeStorage(
                  store -> {
                    if (!cash)
                      return pending.reference()
                          ? ReferenceInputReturns.accountReturn(
                              store, actor, quote, proof.evidence(), verifier, live::get)
                          : NpcInputReturns.accountReturn(
                              store, actor, quote, proof.evidence(), verifier, live::get);
                    if (pending.reference()) {
                      var books = books(store, pricing);
                      var service =
                          new ReferenceCashTrades(
                              store,
                              books.cash(),
                              Clock.systemUTC(),
                              (phase, t, q, r) -> {
                                if (phase != ReferenceCashTrades.Phase.RETURN
                                    || !live.get()
                                    || !t.operation().equals(pending.operation)
                                    || !t.actor().equals(actor)) return false;
                                var current =
                                    Custody.find(r, actor, t.inputCustody(), false).orElse(null);
                                return current != null
                                    && verifier.verifies(current, proof.evidence(), r);
                              });
                      try {
                        return service.returnInputs(quote, true, proof.evidence());
                      } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                      }
                    }
                    var books = books(store, pricing);
                    var service =
                        new NpcCashTrades(
                            store,
                            books.cash(),
                            Clock.systemUTC(),
                            (phase, t, q, r) -> {
                              if (phase != NpcCashTrades.Phase.RETURN
                                  || !live.get()
                                  || !t.operation().equals(pending.operation)
                                  || !t.actor().equals(actor)) return false;
                              var current =
                                  Custody.find(r, actor, t.inputCustody(), false).orElse(null);
                              return current != null
                                  && verifier.verifies(current, proof.evidence(), r);
                            });
                    try {
                      return service.returnInputs(quote, true, proof.evidence());
                    } catch (Exception failure) {
                      throw new IllegalStateException(failure);
                    }
                  }),
              returned -> {
                async(
                    player,
                    menu,
                    flow,
                    flow.services.tradeStorage(store -> find(store, actor, pending.input)),
                    quarantined -> {
                      TraderInventory.stageInputReturn(
                          player, quarantined, returned, pending.snapshot, proof);
                      pending.permit.live.set(false);
                      flow.pending = null;
                      deliver(
                          player,
                          menu,
                          flow,
                          returned.id(),
                          () -> {
                            flow.busy = false;
                            flow.status = "Original inputs returned or retained as a safe claim";
                            render(menu, flow);
                          });
                    });
              });
        });
  }

  private static void confirm(ServerPlayer player, TraderMenu menu, Flow flow) {
    var pending = flow.pending;
    if (pending == null
        || pending.expires() <= System.currentTimeMillis()
        || !pending.permit.live.get())
      throw new IllegalArgumentException("Quote expired or changed; request a new quote");
    if (!witness(player, menu, pending.permit)
        || !TraderInventory.fitsCombined(player, pending.snapshot, pending.outputs))
      throw new IllegalArgumentException("Inventory or trader changed; inputs untouched");
    flow.busy = true;
    flow.status = "Reserving exact inputs…";
    render(menu, flow);
    {
      if (!witness(player, menu, pending.permit))
        throw new IllegalArgumentException("Trader or inventory changed");
      if (pending.input == null) {
        settle(player, menu, flow, pending);
        return;
      }
      var actor = flow.actor;
      async(
          player,
          menu,
          flow,
          flow.services.tradeStorage(store -> find(store, actor, pending.input)),
          input -> {
            if (!pending.permit.live.get()
                || pending.expires() <= System.currentTimeMillis()
                || !witness(player, menu, pending.permit))
              throw new IllegalArgumentException("Quote expired or changed; inputs untouched");
            pending.inputCase = input;
            pending.reserveStarted.set(true);
            String reason =
                TraderInventory.reserve(player, input, pending.snapshot, pending.outputs);
            if (!TraderInventory.held(player, input, pending.snapshot)) {
              if (reason.equals("CAPACITY_OR_INPUT_CHANGED")
                  || reason.equals("INPUT_RECEIPT_BOUND")) pending.reserveStarted.set(false);
              throw new IllegalArgumentException(reason);
            }
            pending.held = true;
            pending.permit.inventory = copyInventory(player);
            var proof = TraderInventory.saveProof(player, input, flow.services.worldId(), true);
            var verifier = TraderInventory.verifier(player);
            async(
                player,
                menu,
                flow,
                flow.services.tradeStorage(
                    store ->
                        transition(
                            store,
                            actor,
                            Custody.Action.RESERVATION_SAVED,
                            input,
                            proof.evidence(),
                            verifier,
                            pending.permit)),
                saved -> {
                  pending.inputCase = saved;
                  settle(player, menu, flow, pending);
                });
          });
    }
  }

  private static void settle(ServerPlayer player, TraderMenu menu, Flow flow, Pending pending) {
    if (!live(player, menu, flow)
        || !NativeCatalog.matches(menu.entity(player), pending.offer)
        || !reputation(menu.entity(player), player).equals(pending.permit.reputation))
      throw new IllegalArgumentException("Trader changed; retained inputs require recovery");
    if (!TraderInventory.fitsCombined(player, null, pending.outputs))
      throw new IllegalArgumentException(
          "Output capacity changed; retained inputs require recovery");
    var actor = flow.actor;
    var world = flow.services.worldId();
    var config = flow.services.simulationConfig();
    var pricing = flow.services.servicePricing();
    // Held inputs replace the original inventory witness after their independently saved removal.
    pending.permit.capacity = true;
    flow.status = "Committing the original trade…";
    render(menu, flow);
    async(
        player,
        menu,
        flow,
        flow.services.tradeMonetaryStorage(
            actor,
            store -> {
              var books = books(store, pricing);
              if (pending.referenceAccountQuote != null)
                return ValuationRuntime.locked(
                    pending.permit.lease,
                    () ->
                        new ReferenceNpcTrades(
                                store,
                                books.account(),
                                Clock.systemUTC(),
                                (phase, t, q, r) ->
                                    admitReference(
                                        pending.permit,
                                        true,
                                        r,
                                        world,
                                        actor,
                                        t.trader(),
                                        config,
                                        t.comparisons(),
                                        pending.offer))
                            .settle(ReferenceNpcTrades.intent(world, pending.operation, actor)));
              if (pending.referenceCashQuote != null)
                return ValuationRuntime.locked(
                    pending.permit.lease,
                    () ->
                        new ReferenceCashTrades(
                                store,
                                books.cash(),
                                Clock.systemUTC(),
                                (phase, t, q, r) ->
                                    admitReference(
                                        pending.permit,
                                        true,
                                        r,
                                        world,
                                        actor,
                                        t.trader(),
                                        config,
                                        t.comparisons(),
                                        pending.offer))
                            .settle(ReferenceCashTrades.intent(world, pending.operation, actor)));
              if (pending.accountQuote != null)
                return new NpcTrades(
                        store,
                        books.account(),
                        Clock.systemUTC(),
                        (phase, t, q, r) ->
                            admit(
                                pending.permit,
                                true,
                                r,
                                world,
                                actor,
                                t.trader(),
                                config,
                                t.comparisons(),
                                pending.offer))
                    .settle(NpcTrades.intent(world, pending.operation, actor));
              return new NpcCashTrades(
                      store,
                      books.cash(),
                      Clock.systemUTC(),
                      (phase, t, q, r) ->
                          admit(
                              pending.permit,
                              true,
                              r,
                              world,
                              actor,
                              t.trader(),
                              config,
                              t.comparisons(),
                              pending.offer))
                  .settle(NpcCashTrades.intent(world, pending.operation, actor));
            }),
        outcome -> {
          if (outcome.status() != Journal.Status.COMMITTED) {
            flow.busy = false;
            flow.status = "Quote changed or could not complete; returning saved original inputs";
            cancel(player, menu, flow);
            return;
          }
          pending.committed = true;
          if (pending.input != null)
            async(
                player,
                menu,
                flow,
                flow.services.tradeStorage(store -> find(store, actor, pending.input)),
                input -> {
                  pending.inputCase = input;
                  TraderInventory.consumeCommitted(player, input);
                  var proof = TraderInventory.saveProof(player, input, world, true);
                  TraderInventory.clearConsumed(player, input, proof);
                  finishTrade(player, menu, flow, pending);
                });
          else finishTrade(player, menu, flow, pending);
        });
  }

  private static void finishTrade(
      ServerPlayer player, TraderMenu menu, Flow flow, Pending pending) {
    progression(player, menu, flow, pending);
    var ids = new ArrayList<UUID>();
    if (pending.output != null) ids.add(pending.output);
    if (pending.cash() && pending.cashOutputMinor() > 0) ids.add(pending.cashOutput);
    deliverAll(
        player,
        menu,
        flow,
        ids,
        0,
        () -> {
          flow.busy = false;
          flow.status = "Trade committed. Any retained delivery is available under Claims.";
          pending.permit.live.set(false);
          flow.pending = null;
          render(menu, flow);
        });
  }

  private static void progression(
      ServerPlayer player, TraderMenu menu, Flow flow, Pending pending) {
    var actor = flow.actor;
    var original = pending.operation;
    flow.services
        .tradeStorage(
            store ->
                flow.live.get()
                    ? StockStore.dispatch(
                        store, original, "authenticated trader receipt " + actor.value())
                    : null)
        .whenComplete(
            (dispatch, error) ->
                player.server.execute(
                    () -> {
                      if (error != null || dispatch == null || !live(player, menu, flow)) return;
                      var entity = menu.entity(player);
                      var staged = TradeProgression.stage(entity, player, dispatch, pending.offer);
                      if (staged.attempt() == null) return;
                      var attempt = staged.attempt();
                      TradeEntitySaves.witness(player, entity, attempt)
                          .whenComplete(
                              (proof, failure) ->
                                  player.server.execute(
                                      () -> {
                                        if (failure != null || !live(player, menu, flow)) return;
                                        var applied = TradeProgression.apply(attempt, proof);
                                        if (!applied.applied()) return;
                                        TradeEntitySaves.witness(player, entity, attempt)
                                            .whenComplete(
                                                (saved, saveError) ->
                                                    player.server.execute(
                                                        () -> {
                                                          if (saveError != null) return;
                                                          flow.services
                                                              .tradeStorage(
                                                                  store ->
                                                                      StockStore.markProgression(
                                                                          store,
                                                                          original,
                                                                          StockFacts.Stage
                                                                              .DISPATCHED,
                                                                          StockFacts.Stage
                                                                              .ACKNOWLEDGED,
                                                                          saved.evidence()))
                                                              .whenComplete(
                                                                  (intent, ackError) ->
                                                                      player.server.execute(
                                                                          () -> {
                                                                            if (ackError == null
                                                                                && entity.isAlive())
                                                                              TradeProgression
                                                                                  .releaseAcknowledged(
                                                                                      entity,
                                                                                      intent,
                                                                                      saved);
                                                                          }));
                                                        }));
                                      }));
                    }));
  }

  private static void deliverAll(
      ServerPlayer player, TraderMenu menu, Flow flow, List<UUID> ids, int index, Runnable done) {
    if (index >= ids.size()) {
      done.run();
      return;
    }
    deliver(
        player,
        menu,
        flow,
        ids.get(index),
        () -> deliverAll(player, menu, flow, ids, index + 1, done));
  }

  private static void deliver(
      ServerPlayer player, TraderMenu menu, Flow flow, UUID id, Runnable done) {
    var actor = flow.actor;
    var world = flow.services.worldId();
    async(
        player,
        menu,
        flow,
        flow.services.tradeStorage(
            store ->
                store.transaction(
                    tx -> {
                      var current =
                          Custody.find(tx::query, actor, id, false)
                              .orElseThrow(
                                  () -> new IllegalArgumentException("Custody unavailable"));
                      if (!NpcDeliveries.isClaimable(tx::query, current)) return null;
                      var input =
                          current.state() == Custody.State.CLAIM
                                  && current.terms().items().startsWith("{")
                              ? Custody.find(
                                      tx::query, actor, current.reservationOperation(), false)
                                  .orElseThrow()
                              : null;
                      return new Delivery(current, input);
                    })),
        delivery -> {
          if (delivery == null) {
            done.run();
            return;
          }
          var current = delivery.current();
          TraderInventory.prepareDelivery(player, current, delivery.originalInput(), world);
          var outputs = TraderInventory.outputStacks(player, current, world);
          if (!BankingInventory.fits(player, outputs)) {
            flow.status = "Delivery retained: clear inventory space and use Claims";
            done.run();
            return;
          }
          var permit = flow.live;
          var inventory = copyInventory(player);
          UUID operation = UUID.randomUUID();
          var action =
              current.state() == Custody.State.CLAIM
                  ? Custody.Action.COLLECT
                  : Custody.Action.BEGIN_DELIVERY;
          var intent =
              custodyIntent(
                  actor,
                  operation,
                  action,
                  Custody.request(
                      id,
                      operation,
                      Custody.Evidence.unknown(),
                      "authenticated merchant delivery"));
          async(
              player,
              menu,
              flow,
              flow.services.tradeStorage(
                  store ->
                      TraderInventory.beginDelivery(
                          store,
                          intent,
                          (a, i, c, r) -> permit.get() && c.equals(current),
                          (i, c, r) -> permit.get())),
              fresh -> {
                if (fresh == null) {
                  done.run();
                  return;
                }
                // Recheck before the saved STARTED marker; an inventory change retains an uncertain
                // attempt.
                if (!inventoryMatches(player, inventory)) {
                  flow.status = "Inventory changed; delivery retained for reconciliation";
                  done.run();
                  return;
                }
                var started = TraderInventory.markDeliveryStart(player, fresh, world);
                async(
                    player,
                    menu,
                    flow,
                    flow.services.tradeStorage(store -> find(store, actor, id)),
                    delivering -> {
                      var proof = TraderInventory.saveProof(player, delivering, world, false);
                      if (started.attempt() == null) {
                        saveDeliveryState(player, menu, flow, delivering, proof, false, done);
                        return;
                      }
                      async(
                          player,
                          menu,
                          flow,
                          flow.services.tradeStorage(store -> find(store, actor, id)),
                          latest -> {
                            String result =
                                TraderInventory.deliverOnce(started.attempt(), proof, latest);
                            if (result.equals("KNOWN_UNDELIVERED_REQUIRES_SAVE")) {
                              var saved = TraderInventory.saveProof(player, latest, world, false);
                              saveDeliveryState(player, menu, flow, latest, saved, false, done);
                              return;
                            }
                            if (!result.equals("DELIVERED_REQUIRES_SAVE")) {
                              flow.status = result + "; retained for reconciliation";
                              done.run();
                              return;
                            }
                            var saved = TraderInventory.saveProof(player, latest, world, false);
                            saveDeliveryState(player, menu, flow, latest, saved, true, done);
                          });
                    });
              });
        });
  }

  private static void saveDeliveryState(
      ServerPlayer player,
      TraderMenu menu,
      Flow flow,
      Custody.Case current,
      TraderInventory.Proof proof,
      boolean delivered,
      Runnable done) {
    var verifier = TraderInventory.verifier(player);
    var actor = flow.actor;
    var permit = new Permit(List.of(), "", "", flow.session, menu.traderId(), 0, 1);
    var action = delivered ? Custody.Action.DELIVERY_SAVED : Custody.Action.CLAIM;
    async(
        player,
        menu,
        flow,
        flow.services.tradeStorage(
            store -> transition(store, actor, action, current, proof.evidence(), verifier, permit)),
        saved -> {
          if (delivered) TraderInventory.clearDelivered(player, saved, proof);
          else TraderInventory.clearUndeliveredClaim(player, saved, proof);
          done.run();
        });
  }

  private static void claims(ServerPlayer player, TraderMenu menu, Flow flow, String value) {
    if (value.isEmpty()) {
      claimPage(player, menu, flow, "");
      return;
    }
    UUID id = UUID.fromString(value);
    flow.busy = true;
    render(menu, flow);
    deliver(
        player,
        menu,
        flow,
        id,
        () -> {
          flow.busy = false;
          flow.status = "Claim processed; retained cases remain recoverable";
          render(menu, flow);
        });
  }

  private static void claimPage(ServerPlayer player, TraderMenu menu, Flow flow, String cursor) {
    UUID after = cursor.isEmpty() ? null : UUID.fromString(cursor);
    int pageSize = 12;
    flow.busy = true;
    render(menu, flow);
    var actor = flow.actor;
    async(
        player,
        menu,
        flow,
        flow.services.tradeStorage(
            store ->
                store.transaction(tx -> NpcDeliveries.page(tx::query, actor, after, pageSize + 1))),
        ids -> {
          var choices = new ArrayList<TraderNetwork.Choice>();
          for (var id : ids.stream().limit(pageSize).toList())
            choices.add(
                choice("claim", id.toString(), "Collect " + id.toString().substring(0, 8), false));
          if (after != null) choices.add(choice("back", "", "First page", false));
          if (ids.size() > pageSize)
            choices.add(choice("next", ids.get(pageSize - 1).toString(), "More claims", false));
          choices.add(choice("browse", "", "Back", false));
          choices.add(choice("exit", "", "Close", false));
          flow.busy = false;
          menu.publish(
              "claims",
              List.of(
                  "Committed goods and cash are retained until saved delivery.",
                  "Ambiguous attempts require operator reconciliation."),
              ids.isEmpty() ? "No retained deliveries on this page" : "Choose a retained delivery",
              false,
              choices);
        });
  }

  private static Custody.Case reserve(
      FoundationStore store, PlayerId actor, UUID id, Custody.Terms terms, Permit permit) {
    var intent = custodyIntent(actor, id, Custody.Action.RESERVE, Custody.reserveRequest(terms));
    var registration =
        Custody.registration(
            Custody.Action.RESERVE,
            (a, i, c, r) -> permit.live.get() && c.id().equals(id) && c.terms().equals(terms),
            (c, e, r) -> false,
            null);
    var outcome =
        new JournalEngine(
                store,
                (a, k) -> a.equals(actor) && k.equals(intent.kind()),
                Map.of(intent.kind(), registration))
            .submit(intent);
    if (outcome.status() != Journal.Status.COMMITTED)
      throw new IllegalArgumentException(outcome.result().code());
    return find(store, actor, id);
  }

  private static Custody.Case transition(
      FoundationStore store,
      PlayerId actor,
      Custody.Action action,
      Custody.Case current,
      Custody.Evidence evidence,
      Custody.EvidenceVerifier verifier,
      Permit permit) {
    UUID id = stable(current.id(), action + "|" + current.revision() + "|" + evidence.saveToken());
    var intent =
        custodyIntent(
            actor,
            id,
            action,
            Custody.request(
                current.id(),
                current.deliveryAttempt(),
                evidence,
                "authenticated merchant custody"));
    var registration =
        Custody.registration(
            action,
            (a, i, c, r) ->
                permit.live.get() && c.equals(current) && c.terms().owner().equals(actor),
            verifier,
            null,
            (i, c, r) -> permit.capacity);
    var outcome =
        new JournalEngine(
                store,
                (a, k) -> a.equals(actor) && k.equals(intent.kind()),
                Map.of(intent.kind(), registration))
            .submit(intent);
    if (outcome.status() != Journal.Status.COMMITTED)
      throw new IllegalArgumentException(outcome.result().code());
    return find(store, actor, current.id());
  }

  private static Custody.Case find(FoundationStore store, PlayerId actor, UUID id) {
    return store.transaction(
        tx ->
            Custody.find(tx::query, actor, id, false)
                .orElseThrow(() -> new IllegalArgumentException("Custody unavailable")));
  }

  private static Journal.Intent custodyIntent(
      PlayerId actor, UUID operation, Custody.Action action, Journal.CanonicalRequest request) {
    return new Journal.Intent(
        new OperationId(operation),
        new RequestId(operation),
        actor,
        action.kind(),
        request,
        null,
        null);
  }

  private static UUID stable(UUID operation, String purpose) {
    return UUID.nameUUIDFromBytes(
        ("economy:trader-ui:v1|" + operation + "|" + purpose)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static <T> void async(
      ServerPlayer player,
      TraderMenu menu,
      Flow flow,
      CompletionStage<T> stage,
      Consumer<T> success) {
    stage.whenComplete(
        (value, error) ->
            player.server.execute(
                () -> {
                  if (!live(player, menu, flow)) return;
                  try {
                    if (error != null) throw new CompletionException(error);
                    success.accept(value);
                  } catch (RuntimeException failure) {
                    flow.busy = false;
                    flow.status = message(failure);
                    render(menu, flow);
                  }
                }));
  }

  private static String message(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause())
      if (cause instanceof ca.emilybelnavis.mcmods.economy.storage.StorageException storage)
        return switch (storage.kind()) {
          case ECONOMIC_CATCH_UP -> "The economy is catching up. Request another quote when ready.";
          case OVERLOAD -> "The trader is busy. Try again shortly.";
          default -> "Trading is unavailable. Your original items remain protected.";
        };
    while (error.getCause() != null) error = error.getCause();
    String text = error.getMessage();
    if (text == null) return "Trader operation unavailable";
    if (text.contains("WITHDRAW_IMPOSSIBLE") || text.contains("CASH_EXACT_CHANGE_UNAVAILABLE"))
      return "Exact cash change is unavailable. Choose another cash currency or an account, or try a different quantity.";
    if (text.contains("REFERENCE_")
        || text.contains("ROUNDED_COHORT")
        || text.contains("Reference ")
        || text.contains("reference ")) return "Choose an offer";
    if (text.contains("LARGER_SPREAD_SAFE_BATCH_REQUIRED"))
      return "Choose a larger supported quantity and request another quote.";
    if (text.startsWith("NPC_"))
      return "The quote or trader changed. Cancel it and request another quote.";
    return text.substring(0, Math.min(240, text.length()));
  }

  private static String itemName(String id) {
    return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)).getDescription().getString();
  }

  private static String money(Money amount) {
    return amount.currency()
        + " "
        + java.math.BigDecimal.valueOf(amount.minorUnits(), amount.currency().fractionDigits())
            .toPlainString();
  }

  private static TraderNetwork.Choice choice(
      String action, String value, String label, boolean selected) {
    return new TraderNetwork.Choice(
        action, value, label.substring(0, Math.min(128, label.length())), selected);
  }

  private static void render(TraderMenu menu, Flow flow) {
    var lines = new ArrayList<String>();
    var choices = new ArrayList<TraderNetwork.Choice>();
    var view = flow.view;
    if (view != null) {
      lines.add("Trader currency: " + view.market().currency());
      if (flow.offer >= 0 && view.available().contains(flow.offer)) {
        var offer = view.catalog().offers().get(flow.offer);
        lines.add(
            "Selected: "
                + (offer.side() == Pricing.Side.BUY ? "Buy " : "Sell ")
                + offer.goodsCount()
                + " "
                + itemName(offer.goods().item())
                + " × "
                + flow.quantity);
        lines.add(
            "Available trades: "
                + view.stock().offers().get(flow.offer).remaining()
                + " · Payment: "
                + flow.rail);
      }
      var pending = flow.pending;
      if (pending != null && pending.expires() > 0) {
        if (pending.account()) {
          lines.add(
              "Goods price: " + money(new Money(pending.marketCurrency(), pending.principal())));
          if (pending.reference())
            lines.add(
                "Unit price: " + money(new Money(pending.marketCurrency(), pending.unitMinor())));
          var fx = pending.accountFx();
          if (fx != null) {
            lines.add(
                "FX: " + money(fx.conversion().debit()) + " → " + money(fx.conversion().credit()));
            lines.add("Issuer fee: " + money(fx.pricing().fee()));
          }
        } else {
          lines.add(
              "Cash presented: "
                  + money(new Money(pending.cashCurrency(), pending.inputCashMinor())));
          lines.add(
              "Cash required: "
                  + money(new Money(pending.cashCurrency(), pending.requiredCashMinor())));
          lines.add(
              "Cash payout/change: "
                  + money(new Money(pending.cashCurrency(), pending.cashOutputMinor())));
          if (pending.reference()) {
            lines.add(
                "Goods price: " + money(new Money(pending.marketCurrency(), pending.principal())));
            lines.add(
                "Unit price: " + money(new Money(pending.marketCurrency(), pending.unitMinor())));
          }
          var fx = pending.cashFx();
          if (fx != null) {
            if (!pending.reference())
              lines.add(
                  "Goods price: "
                      + money(new Money(pending.marketCurrency(), pending.principal())));
            lines.add(
                "FX: " + money(fx.conversion().debit()) + " → " + money(fx.conversion().credit()));
            lines.add("FX fee: " + money(fx.pricing().fee()));
          }
        }
        lines.add(
            "Valid until: "
                + java.time.Instant.ofEpochMilli(pending.expires())
                    .atOffset(java.time.ZoneOffset.UTC)
                    .toLocalTime()
                    .withNano(0)
                + " UTC");
        choices.add(choice("confirm", "", "Confirm", false));
        choices.add(choice("cancel", "", "Cancel quote", false));
      } else if (flow.section.equals("cash")) {
        for (var currency : EconomyCurrency.values())
          choices.add(choice("currency", currency.name(), currency.name(), currency == flow.cash));
        choices.add(choice("browse", "", "Back", false));
      } else if (flow.section.equals("account")) {
        for (var account : view.accounts())
          choices.add(
              choice(
                  "account",
                  account.id().value().toString(),
                  account.currency() + " " + account.id().value().toString().substring(0, 8),
                  account.id().equals(flow.account)));
        choices.add(choice("browse", "", "Back", false));
      } else {
        var shown =
            view.catalog().offers().stream()
                .filter(item -> view.available().contains(item.ordinal()))
                .toList();
        int start = Math.min(flow.page * 3, Math.max(0, shown.size() - 1));
        for (int i = start; i < Math.min(start + 3, shown.size()); i++) {
          var item = shown.get(i);
          choices.add(
              choice(
                  "offer",
                  Integer.toString(item.ordinal()),
                  (item.side() == Pricing.Side.BUY ? "Buy " : "Sell ")
                      + item.goodsCount()
                      + " "
                      + itemName(item.goods().item()),
                  item.ordinal() == flow.offer));
        }
        choices.add(choice("rail", "account", "Account", flow.rail.equals("account")));
        choices.add(choice("rail", "cash", "Cash currency", flow.rail.equals("cash")));
        for (int n : new int[] {1, 4, 16, 64})
          choices.add(choice("quantity", Integer.toString(n), "×" + n, flow.quantity == n));
        if (flow.offer >= 0 && view.available().contains(flow.offer))
          choices.add(choice("quote", "", "Request quote", false));
        if (flow.page > 0) choices.add(choice("back", "", "Previous offers", false));
        if (start + 3 < shown.size()) choices.add(choice("next", "", "More offers", false));
        if (flow.account != null) lines.add("Selected account: " + flow.account.value());
        if (flow.cash != null) lines.add("Selected cash: " + flow.cash);
      }
    }
    choices.add(choice("claim", "", "Claims", false));
    choices.add(choice("exit", "", "Close", false));
    menu.publish(
        "browse",
        lines.stream().map(line -> line.substring(0, Math.min(256, line.length()))).toList(),
        flow.status,
        flow.busy,
        choices);
  }
}
