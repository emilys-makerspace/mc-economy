package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.Economy;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.banking.*;
import ca.emilybelnavis.mcmods.economy.foundation.ServerServices;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.valuation.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Actual ordinary recipe capture and reload, confined to a fresh disposable integrated world. */
public final class RecipeClient {
  private boolean opened, stopped;
  private final long start = System.nanoTime();
  private volatile String result, failure;
  private int phase, ticks;
  private UUID original;
  private CompletableFuture<Boolean> query;

  public RecipeClient() {
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
        String name = "economy-recipes-" + UUID.randomUUID();
        require(
            !Files.exists(client.gameDirectory.toPath().resolve("saves").resolve(name)),
            "fresh world");
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
                new WorldOptions(90301L, false, false),
                WorldPresets::createNormalWorldDimensions,
                client.screen);
      }
      if (client.player != null
          && client.player.containerMenu instanceof BankingMenu menu
          && menu.worldSetup()
          && !menu.busy()
          && ticks % 20 == 0) BankingNetwork.action(menu, "setup", "CAD");
      if (result == null && failure == null && System.nanoTime() - start > 240_000_000_000L)
        failure = "Recipe fixture deadline phase=" + phase;
      if (result != null || failure != null) {
        stopped = true;
        Files.writeString(
            client
                .gameDirectory
                .toPath()
                .resolve(failure == null ? "recipes-passed.txt" : "recipes-failed.txt"),
            failure == null ? result : failure);
        client.execute(client::stop);
      }
    } catch (Throwable error) {
      error.printStackTrace();
      failure = error.toString();
      stopped = true;
      client.execute(client::stop);
    }
  }

  private void server(ServerTickEvent.Post event) {
    if (result != null || failure != null) return;
    var server = event.getServer();
    var services = Economy.serverServices(server);
    if (services == null
        || services.state() != ServerServices.State.READY
        || server.getPlayerList().getPlayers().isEmpty()) return;
    try {
      Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
      require(
          world.getFileName().toString().matches("economy-recipes-[0-9a-f-]{36}"),
          "guarded disposable world");
      if (query != null) {
        if (!query.isDone()) return;
        boolean ready = query.join();
        query = null;
        if (!ready) return;
        phase++;
      }
      var status = ValuationRuntime.status(server);
      if (phase == 0 && status.ready()) {
        original = status.snapshot();
        query =
            services
                .tradeStorage(
                    store -> {
                      var fx =
                          store.transaction(
                              tx -> FxService.at(tx::query, System.currentTimeMillis()));
                      if (fx == null) return false;
                      var saved = ValuationStore.active(store);
                      var manifest = ValuationData.decode(saved.payload());
                      require(
                          manifest.version() == 2 && manifest.recipes() != null,
                          "ordinary graph manifest");
                      var serializers = new TreeSet<String>();
                      manifest.recipes().recipes().forEach(n -> serializers.add(n.serializer()));
                      require(
                          serializers.equals(RecipeGraph.SERIALIZERS),
                          "all seven ordinary serializers captured");
                      check(manifest, fx, "oak_planks", 50);
                      check(manifest, fx, "cherry_planks", 200);
                      check(manifest, fx, "stick", 25);
                      check(manifest, fx, "copper_ingot", 300);
                      check(manifest, fx, "iron_ingot", 600);
                      check(manifest, fx, "gold_ingot", 2400);
                      check(manifest, fx, "cooked_beef", 457);
                      check(manifest, fx, "glass", 10);
                      require(
                          manifest.recipes().recipes().stream()
                              .filter(n -> n.id().equals("minecraft:honey_block"))
                              .anyMatch(
                                  n ->
                                      !n.exclusions().isEmpty()
                                          && n.ingredients().stream()
                                              .anyMatch(
                                                  i -> !i.emptySlot() && i.items().isEmpty())),
                          "returned-bottle ingredient excluded without becoming free");
                      System.out.println(
                          "RECIPE_CAPTURE_COUNTS nodes="
                              + manifest.recipes().recipes().size()
                              + " excluded="
                              + manifest.recipes().exclusions().size());
                      return true;
                    })
                .toCompletableFuture();
      } else if (phase == 1) {
        Path pack = world.resolve("datapacks/valuation_recipe_probe");
        Files.createDirectories(pack.resolve("data/minecraft/recipe"));
        Files.writeString(
            pack.resolve("pack.mcmeta"),
            "{\"pack\":{\"pack_format\":48,\"description\":\"Disposable recipe capture witness\"}}");
        Files.writeString(
            pack.resolve("data/minecraft/recipe/oak_planks.json"),
            "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"tag\":\"minecraft:oak_logs\"}],\"result\":{\"id\":\"minecraft:oak_planks\",\"count\":2}}");
        server.getPackRepository().reload();
        var packs = new ArrayList<>(server.getPackRepository().getSelectedIds());
        packs.add("file/valuation_recipe_probe");
        server.reloadResources(packs).join();
        require(!ValuationRuntime.status(server).ready(), "pending reload admission");
        phase = 2;
      } else if (phase == 2 && status.ready()) {
        require(
            !original.equals(status.snapshot()), "changed recipe yields new immutable snapshot");
        query =
            services
                .tradeStorage(
                    store -> {
                      var fx =
                          store.transaction(
                              tx -> FxService.at(tx::query, System.currentTimeMillis()));
                      var newer = ValuationData.decode(ValuationStore.active(store).payload());
                      var older =
                          ValuationData.decode(ValuationStore.load(store, original).payload());
                      check(newer, fx, "oak_planks", 100);
                      check(newer, fx, "stick", 50);
                      check(older, fx, "oak_planks", 50);
                      check(older, fx, "stick", 25);
                      require(
                          newer.recipes().recipes().stream()
                              .filter(n -> n.id().equals("minecraft:oak_planks"))
                              .allMatch(n -> n.count() == 2),
                          "actual changed output count");
                      return true;
                    })
                .toCompletableFuture();
      } else if (phase == 3) {
        result =
            "PASS actual seven-serializer capture; wood/stick, processing and ordinary cooking values; unsafe remainder excluded; changed datapack output 4->2 published new graph; new oak planks/sticks100/50 cents; historical50/25 retained; normal shutdown.";
        System.out.println("RECIPE_PROBE_PASS " + result);
      }
    } catch (Throwable error) {
      error.printStackTrace();
      failure = error.toString();
    }
  }

  private static void check(
      ValuationData.Manifest manifest,
      ca.emilybelnavis.mcmods.economy.fx.EffectiveFxSnapshot fx,
      String item,
      long expected) {
    var value =
        RecipeValues.evaluate(manifest, "minecraft:" + item, "{}", 1, EconomyCurrency.CAD, fx);
    require(
        value.status() == RecipeValues.Status.READY && value.unitMoney().minorUnits() == expected,
        item
            + " expected="
            + expected
            + " status="
            + value.status()
            + " value="
            + value.unitMoney()
            + " reason="
            + value.reason());
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
