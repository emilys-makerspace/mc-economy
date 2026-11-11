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
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.*;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Native typed component projection and retained pricing in a fresh guarded fixture only. */
public final class VariantClient {
  private static final String BASE =
      "{\"bases\":[],\"overrides\":[{\"item\":\"minecraft:shulker_box\",\"currency\":\"CAD\",\"amount\":\"80\",\"referenceCount\":1},{\"item\":\"minecraft:dirt\",\"currency\":\"CAD\",\"amount\":\"0.01\",\"referenceCount\":3}]";
  private boolean opened, stopped;
  private final long start = System.nanoTime();
  private int phase, ticks;
  private volatile String result, failure;
  private CompletableFuture<Boolean> query;
  private UUID original;
  private String swordIdentity, nestedIdentity;

  public VariantClient() {
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
        Path root =
            DisposableProbeWorld.requireClientDirectory(
                client.gameDirectory.toPath(), "economy-a9-03-03-");
        Path config = FMLPaths.CONFIGDIR.get().resolve("economy-valuation.json");
        require(!Files.exists(config), "existing config refused");
        Files.writeString(config, BASE + "}");
        String name = "economy-variants-" + UUID.randomUUID();
        DisposableProbeWorld.requireFreshWorld(root, name);
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
                new WorldOptions(90303L, false, false),
                WorldPresets::createNormalWorldDimensions,
                client.screen);
      }
      if (client.player != null
          && client.player.containerMenu instanceof BankingMenu menu
          && menu.worldSetup()
          && !menu.busy()
          && ticks % 20 == 0) BankingNetwork.action(menu, "setup", "CAD");
      if (result == null && failure == null && System.nanoTime() - start > 240_000_000_000L)
        failure = "Variant fixture deadline phase=" + phase;
      if (result != null || failure != null) {
        stopped = true;
        Files.writeString(
            client
                .gameDirectory
                .toPath()
                .resolve(failure == null ? "variants-passed.txt" : "variants-failed.txt"),
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
      require(
          server
              .getWorldPath(LevelResource.ROOT)
              .toAbsolutePath()
              .normalize()
              .getFileName()
              .toString()
              .matches("economy-variants-[0-9a-f-]{36}"),
          "fresh guarded world");
      if (query != null) {
        if (!query.isDone()) return;
        boolean done = query.join();
        query = null;
        if (!done) return;
        phase++;
      }
      var status = ValuationRuntime.status(server);
      if (phase == 0 && status.ready()) {
        original = status.snapshot();
        var registry = server.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        var sword = new ItemStack(Items.IRON_SWORD);
        var enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        for (var entry : Map.of("sharpness", 3, "unbreaking", 2, "vanishing_curse", 1).entrySet())
          enchantments.set(
              registry.getHolderOrThrow(
                  ResourceKey.create(
                      Registries.ENCHANTMENT,
                      ResourceLocation.fromNamespaceAndPath("minecraft", entry.getKey()))),
              entry.getValue());
        sword.set(DataComponents.ENCHANTMENTS, enchantments.toImmutable());
        sword.set(DataComponents.DAMAGE, 125);
        sword.set(DataComponents.REPAIR_COST, 2);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Reference witness"));
        var originalSword = sword.copy();
        swordIdentity = VariantIdentity.components(sword, server);
        require(
            ItemStack.isSameItemSameComponents(sword, originalSword),
            "identity capture does not mutate stack");
        var potion =
            PotionContents.createItemStack(
                Items.LINGERING_POTION,
                server
                    .registryAccess()
                    .registryOrThrow(Registries.POTION)
                    .getHolderOrThrow(
                        ResourceKey.create(
                            Registries.POTION,
                            ResourceLocation.fromNamespaceAndPath("minecraft", "awkward"))));
        var inner = new ItemStack(Items.SHULKER_BOX);
        inner.set(
            DataComponents.CONTAINER,
            ItemContainerContents.fromItems(List.of(sword, potion, new ItemStack(Items.DIRT, 3))));
        var outer = new ItemStack(Items.SHULKER_BOX);
        outer.set(
            DataComponents.CONTAINER,
            ItemContainerContents.fromItems(List.of(inner, new ItemStack(Items.DIAMOND, 2))));
        nestedIdentity = VariantIdentity.components(outer, server);
        var book = new ItemStack(Items.ENCHANTED_BOOK);
        var stored = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        stored.set(
            registry.getHolderOrThrow(
                ResourceKey.create(
                    Registries.ENCHANTMENT,
                    ResourceLocation.fromNamespaceAndPath("minecraft", "mending"))),
            1);
        book.set(DataComponents.STORED_ENCHANTMENTS, stored.toImmutable());
        String bookIdentity = VariantIdentity.components(book, server);
        var hive = new ItemStack(Items.BEEHIVE);
        String emptyHive = VariantIdentity.components(hive, server);
        var bee = new CompoundTag();
        bee.putString("id", "minecraft:bee");
        hive.set(
            DataComponents.BEES,
            List.of(new BeehiveBlockEntity.Occupant(CustomData.of(bee), 0, 2400)));
        String occupiedHive = VariantIdentity.components(hive, server);
        String water = VariantIdentity.components(new ItemStack(Items.WATER_BUCKET), server),
            lava = VariantIdentity.components(new ItemStack(Items.LAVA_BUCKET), server);
        String rocket = VariantIdentity.components(new ItemStack(Items.FIREWORK_ROCKET), server),
            pot = VariantIdentity.components(new ItemStack(Items.DECORATED_POT), server);
        var ominous = new ItemStack(Items.OMINOUS_BOTTLE);
        ominous.set(DataComponents.OMINOUS_BOTTLE_AMPLIFIER, 4);
        String omen = VariantIdentity.components(ominous, server);
        query =
            services
                .tradeStorage(
                    store -> {
                      var fx =
                          store.transaction(
                              tx -> FxService.at(tx::query, System.currentTimeMillis()));
                      if (fx == null) return false;
                      var manifest = ValuationData.decode(ValuationStore.active(store).payload());
                      require(
                          manifest.version() == 4
                              && manifest.variants().enchantments().size() == 42,
                          "captured42premiumdefinitions");
                      check(manifest, fx, "iron_sword", swordIdentity, 7397);
                      check(manifest, fx, "shulker_box", nestedIdentity, 84317);
                      check(manifest, fx, "enchanted_book", bookIdentity, 30080);
                      check(manifest, fx, "water_bucket", water, 1800);
                      check(manifest, fx, "lava_bucket", lava, 2100);
                      check(manifest, fx, "ominous_bottle", omen, 12510);
                      check(manifest, fx, "firework_rocket", rocket, 20);
                      check(manifest, fx, "decorated_pot", pot, 100);
                      long empty =
                          value(manifest, fx, "beehive", emptyHive).unitMoney().minorUnits();
                      check(manifest, fx, "beehive", occupiedHive, empty + 200);
                      System.out.println(
                          "VARIANT_CAPTURE_COUNTS items="
                              + manifest.variants().items().size()
                              + " enchants="
                              + manifest.variants().enchantments().size()
                              + " flowers="
                              + manifest.variants().flowers().size());
                      return true;
                    })
                .toCompletableFuture();
      } else if (phase == 1) {
        Files.writeString(
            FMLPaths.CONFIGDIR.get().resolve("economy-valuation.json"),
            BASE
                + ",\"enchantmentPremiums\":{\"minecraft:sharpness\":[\"20\",\"45\",\"80\",\"110\",\"150\"]}}");
        server.reloadResources(server.getPackRepository().getSelectedIds()).join();
        require(!ValuationRuntime.status(server).ready(), "pending reload admission");
        phase = 2;
      } else if (phase == 2 && status.ready()) {
        require(!original.equals(status.snapshot()), "premium change new snapshot");
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
                      check(newer, fx, "iron_sword", swordIdentity, 7584);
                      check(newer, fx, "shulker_box", nestedIdentity, 84504);
                      check(older, fx, "iron_sword", swordIdentity, 7397);
                      check(older, fx, "shulker_box", nestedIdentity, 84317);
                      return true;
                    })
                .toCompletableFuture();
      } else if (phase == 3) {
        result =
            "PASS actual typed stack identity;42enchants; enchanted damaged cursed named sword7397cents; nestedcontainer84317 with potion once/published children; books/buckets/ominous/rocket/pot/bees; premium reload7584/84504 and original7397/84317 retained; normal shutdown.";
        System.out.println("VARIANT_PROBE_PASS " + result);
      }
    } catch (Throwable error) {
      error.printStackTrace();
      failure = error.toString();
    }
  }

  private static RecipeValues.Result value(
      ValuationData.Manifest manifest,
      ca.emilybelnavis.mcmods.economy.fx.EffectiveFxSnapshot fx,
      String item,
      String identity) {
    return RecipeValues.evaluate(
        manifest, "minecraft:" + item, identity, 1, EconomyCurrency.CAD, fx);
  }

  private static void check(
      ValuationData.Manifest manifest,
      ca.emilybelnavis.mcmods.economy.fx.EffectiveFxSnapshot fx,
      String item,
      String identity,
      long expected) {
    var result = value(manifest, fx, item, identity);
    require(
        result.status() == RecipeValues.Status.READY && result.unitMoney().minorUnits() == expected,
        item
            + " expected="
            + expected
            + " status="
            + result.status()
            + " value="
            + result.unitMoney()
            + " reason="
            + result.reason());
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
