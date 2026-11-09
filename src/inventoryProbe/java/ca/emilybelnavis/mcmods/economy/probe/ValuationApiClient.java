package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.ItemValuationIntegration.*;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.foundation.*;
import ca.emilybelnavis.mcmods.economy.valuation.ValuationRuntime;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Opt-in actual-session/API/provider witness in a fresh disposable client world. */
public final class ValuationApiClient {
  private static final String RAW = "economy_inventory_probe:api_raw";
  private static final String PROVIDER = "economy_inventory_probe:prices";
  private static final Identity EXACT =
      new Identity(RAW, "{\"economy_inventory_probe:quality\":\"polished\"}", 1);
  private static final DeferredRegister.Items ITEMS =
      DeferredRegister.createItems("economy_inventory_probe");

  static {
    ITEMS.register("api_raw", () -> new Item(new Item.Properties()));
  }

  public static void register(IEventBus bus) {
    ITEMS.register(bus);
  }

  private boolean opened, stopped;
  private final long started = System.nanoTime();
  private int phase, ticks;
  private volatile String result, failure;
  private CompletableFuture<?> work;
  private ItemValuationIntegration.Provider provider;
  private SnapshotMetadata registered;
  private Result original;

  public ValuationApiClient() {
    require(
        Boolean.getBoolean("economy.valuationApiProbe"),
        "explicit valuation API probe property required");
    NeoForge.EVENT_BUS.addListener(this::client);
    NeoForge.EVENT_BUS.addListener(this::server);
  }

  private void client(ClientTickEvent.Post event) {
    if (stopped) return;
    var client = Minecraft.getInstance();
    client.options.pauseOnLostFocus = false;
    ticks++;
    try {
      if (!opened && client.getOverlay() == null && client.screen != null && client.level == null) {
        opened = true;
        Path root = client.gameDirectory.toPath().toAbsolutePath().normalize();
        require(
            root.startsWith(Path.of("/private/tmp"))
                && root.getParent().getFileName().toString().startsWith("economy-a9-04-"),
            "guarded disposable run root");
        require(
            !Files.exists(FMLPaths.CONFIGDIR.get().resolve("economy-valuation.json")),
            "existing valuation config refused");
        String name = "economy-valuation-api-" + UUID.randomUUID();
        require(!Files.exists(root.resolve("saves").resolve(name)), "fresh world only");
        client
            .createWorldOpenFlows()
            .createFreshLevel(
                name,
                new LevelSettings(
                    name,
                    GameType.CREATIVE,
                    false,
                    Difficulty.PEACEFUL,
                    true,
                    new GameRules(),
                    WorldDataConfiguration.DEFAULT),
                new WorldOptions(904L, false, false),
                WorldPresets::createNormalWorldDimensions,
                client.screen);
      }
      if (client.player != null
          && client.player.containerMenu instanceof BankingMenu menu
          && menu.worldSetup()
          && !menu.busy()
          && ticks % 20 == 0) BankingNetwork.action(menu, "setup", "CAD");
      if (result == null && failure == null && System.nanoTime() - started > 90_000_000_000L)
        failure = "Valuation API fixture deadline phase=" + phase;
      if (result != null || failure != null) finish(client);
    } catch (Throwable error) {
      error.printStackTrace();
      failure = error.toString();
      try {
        finish(client);
      } catch (Exception markerFailure) {
        markerFailure.printStackTrace();
        client.execute(client::stop);
      }
    }
  }

  private void finish(Minecraft client) throws java.io.IOException {
    stopped = true;
    Files.writeString(
        client
            .gameDirectory
            .toPath()
            .resolve(failure == null ? "valuation-api-passed.txt" : "valuation-api-failed.txt"),
        failure == null ? result : failure);
    client.execute(client::stop);
  }

  private void server(ServerTickEvent.Post event) {
    if (result != null || failure != null) return;
    var server = event.getServer();
    var services = Economy.serverServices(server);
    if (services == null
        || services.state() != ServerServices.State.READY
        || server.getPlayerList().getPlayers().isEmpty()) return;
    try {
      require(
          server
              .getWorldPath(LevelResource.ROOT)
              .toAbsolutePath()
              .normalize()
              .getFileName()
              .toString()
              .matches("economy-valuation-api-[0-9a-f-]{36}"),
          "fresh guarded world");
      var player = server.getPlayerList().getPlayers().getFirst();
      var target =
          new PartyRef(services.worldId(), new PartyRef.Player(new PlayerId(player.getUUID())));
      var grant = PartyAuthorization.player(player, Economy::serverServices, target).orElseThrow();
      var api = services.itemValuation(server);
      if (work != null && !work.isDone()) return;
      switch (phase) {
        case 0 -> {
          if (!ValuationRuntime.status(server).ready()) return;
          boolean unauthenticated = false;
          try {
            api.evaluate(null, current(new Identity(RAW, "{}", 1), EconomyCurrency.USD, 3));
          } catch (SecurityException expected) {
            unauthenticated = true;
          }
          require(unauthenticated, "null ownership grant rejected at admission");
          provider = services.itemValuationProvider(server);
          boolean wrongNamespace = false;
          try {
            provider.register(contribution("other_mod:prices"));
          } catch (SecurityException expected) {
            wrongNamespace = true;
          }
          require(wrongNamespace, "loaded module cannot spoof another provider namespace");
          work = provider.register(contribution(PROVIDER)).toCompletableFuture();
          phase = 1;
        }
        case 1 -> {
          registered = (SnapshotMetadata) work.join();
          require(
              registered.id().equals(ValuationRuntime.status(server).snapshot()),
              "registration durably active");
          work =
              api.evaluate(grant, current(new Identity(RAW, "{}", 1), EconomyCurrency.USD, 3))
                  .toCompletableFuture();
          phase = 2;
        }
        case 2 -> {
          var read = (Result) work.join();
          if (read.status() == Status.FX_UNAVAILABLE) {
            work =
                api.evaluate(grant, current(new Identity(RAW, "{}", 1), EconomyCurrency.USD, 3))
                    .toCompletableFuture();
            return;
          }
          ready(read, "registered base foreign read");
          require(
              read.unitMoney().orElseThrow().currency() == EconomyCurrency.USD
                  && read.unitMoney().orElseThrow().minorUnits() > 0,
              "USD published amount");
          require(
              read.totalMoney().orElseThrow().minorUnits()
                  == 3 * read.unitMoney().orElseThrow().minorUnits(),
              "quantity uses published units");
          require(
              read.snapshot().orElseThrow().valuationSnapshot().equals(registered.id())
                  && read.snapshot().orElseThrow().manifestVersion() == 5
                  && read.snapshot().orElseThrow().fxPublication() != null,
              "captured provider/manifest/FX identity");
          original = read;
          work = api.evaluate(grant, current(EXACT, EconomyCurrency.CAD, 1)).toCompletableFuture();
          phase = 3;
        }
        case 3 -> {
          var read = (Result) work.join();
          ready(read, "exact selector");
          require(
              read.unitMoney().orElseThrow().minorUnits() == 1000,
              "opted-in exact custom identity priced once");
          var cached =
              api.currentCached(grant, current(new Identity(RAW, "{}", 1), EconomyCurrency.CAD, 2));
          require(
              cached.status() == Status.NOT_READY && cached.reasonCode().equals("PENDING"),
              "cache miss typed pending");
          work = null;
          phase = 4;
        }
        case 4 -> {
          var cached =
              api.currentCached(grant, current(new Identity(RAW, "{}", 1), EconomyCurrency.CAD, 2));
          if (cached.status() == Status.NOT_READY) return;
          ready(cached, "completed cached read");
          require(
              cached.unitMoney().orElseThrow().minorUnits() == 200
                  && cached.totalMoney().orElseThrow().minorUnits() == 400,
              "cached sampled money");
          work =
              provider
                  .register(contribution("economy_inventory_probe:conflicting_prices"))
                  .toCompletableFuture();
          phase = 5;
        }
        case 5 -> {
          require(
              work.isCompletedExceptionally(), "overlapping identical claim rejects atomically");
          require(
              ValuationRuntime.status(server).ready()
                  && registered.id().equals(ValuationRuntime.status(server).snapshot()),
              "failed conflict preserves active pointer/readiness");
          work =
              api.evaluate(grant, current(new Identity(RAW, "{}", 1), EconomyCurrency.USD, 3))
                  .toCompletableFuture();
          phase = 6;
        }
        case 6 -> {
          var read = (Result) work.join();
          ready(read, "read after conflict");
          require(read.unitMoney().equals(original.unitMoney()), "conflict leaves original price");
          work = api.history(grant, Optional.empty(), 16).toCompletableFuture();
          phase = 7;
        }
        case 7 -> {
          @SuppressWarnings("unchecked")
          var rows = (List<SnapshotMetadata>) work.join();
          require(
              rows.size() <= 16 && rows.stream().anyMatch(row -> row.id().equals(registered.id())),
              "bounded durable history contains registration");
          work = provider.remove(PROVIDER).toCompletableFuture();
          phase = 8;
        }
        case 8 -> {
          var removed = (SnapshotMetadata) work.join();
          require(
              !removed.id().equals(registered.id())
                  && removed.id().equals(ValuationRuntime.status(server).snapshot()),
              "removal publishes new active snapshot");
          work =
              api.evaluate(grant, current(new Identity(RAW, "{}", 1), EconomyCurrency.USD, 3))
                  .toCompletableFuture();
          phase = 9;
        }
        case 9 -> {
          var missing = (Result) work.join();
          require(
              missing.status() == Status.MOD_UNREGISTERED && missing.unitMoney().isEmpty(),
              "removed mod identity unavailable without partial money");
          var retained =
              new Request(
                  original.identity(),
                  original.quantity(),
                  EconomyCurrency.USD,
                  new Retained(registered.id(), original.snapshot().orElseThrow().fxPublication()));
          work = api.evaluate(grant, retained).toCompletableFuture();
          phase = 10;
        }
        case 10 -> {
          var retained = (Result) work.join();
          ready(retained, "retained original after removal");
          require(
              retained.unitMoney().equals(original.unitMoney())
                  && retained.totalMoney().equals(original.totalMoney())
                  && retained.explanation().equals(original.explanation())
                  && retained.snapshot().equals(original.snapshot()),
              "retained exact original money/proof/FX");
          result =
              "PASS loaded-mod provider namespace auth; ownership grant admission; registration exact selector and USD quantity; cached PENDING/completion; conflict preserves active snapshot; bounded history; removal MOD_UNREGISTERED; retained original money/proof/FX; normal shutdown.";
          System.out.println("VALUATION_API_PROBE_PASS " + result);
        }
        default -> throw new IllegalStateException("Unknown API probe phase");
      }
    } catch (Throwable error) {
      error.printStackTrace();
      failure = error.toString();
    }
  }

  private static Contribution contribution(String providerId) {
    return new Contribution(
        providerId,
        "1",
        Set.of(RAW),
        List.of(new Base(RAW, EconomyCurrency.CAD, "2", 1)),
        List.of(new ExactSelector(EXACT, new Base(RAW, EconomyCurrency.CAD, "10", 1))),
        Set.of(),
        Map.of(),
        Set.of());
  }

  private static Request current(Identity identity, EconomyCurrency currency, long quantity) {
    return new Request(identity, quantity, currency, new Current());
  }

  private static void ready(Result read, String message) {
    require(
        read.status() == Status.READY,
        message + " status=" + read.status() + " reason=" + read.reasonCode());
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
