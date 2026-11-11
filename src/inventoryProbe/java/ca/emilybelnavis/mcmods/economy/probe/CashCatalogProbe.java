package ca.emilybelnavis.mcmods.economy.probe;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.card.CardCatalog;
import ca.emilybelnavis.mcmods.economy.card.CardStackIdentity;
import ca.emilybelnavis.mcmods.economy.cash.CashCatalog;
import ca.emilybelnavis.mcmods.economy.cash.CashStackIdentity;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Exercises actual registered items and vanilla stack/component behavior in the server runtime. */
public final class CashCatalogProbe {
  private CashCatalogProbe() {}

  public static void verify(MinecraftServer server) {
    require(CashCatalog.all().size() == 131, "131 cash identities");
    require(CardCatalog.collectibles().size() > 28, "debit and credit card identities");
    require(CashCatalog.tabIds().size() == 13, "thirteen creative tab identities");
    net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(
        server.getWorldData().enabledFeatures(), true, server.registryAccess());
    for (var id : CashCatalog.tabIds()) {
      var key = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("economy", id);
      require(BuiltInRegistries.CREATIVE_MODE_TAB.containsKey(key), "registered creative tab");
      require(
          !BuiltInRegistries.CREATIVE_MODE_TAB.get(key).getDisplayItems().isEmpty(),
          "populated creative tab");
    }
    for (var denomination : CashCatalog.all()) {
      var item = denomination.item().get();
      require(
          BuiltInRegistries.ITEM.getKey(item).toString().equals("economy:" + denomination.id()),
          "registered cash identity");
      require(denomination.minorUnits() > 0, "positive denomination");
    }
    var cardsTab =
        BuiltInRegistries.CREATIVE_MODE_TAB.get(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("economy", "cards"));
    require(
        cardsTab.getDisplayItems().size() == CardCatalog.collectibles().size(),
        "every card design in cards tab");
    for (var entry : CardCatalog.collectibles()) {
      var card = new ItemStack(entry.item().get());
      require(card.getMaxStackSize() == 1, "single-stack card");
      require(
          CardStackIdentity.mark(card, new WorldId(UUID.randomUUID())).isEmpty(),
          "creative card has no issued token");
      require(
          cardsTab.getDisplayItems().stream().anyMatch(stack -> stack.is(entry.item().get())),
          "card design visible");
    }
    require(CardCatalog.find(new ItemStack(Items.PAPER)).isEmpty(), "unrelated item is not a card");
    var world = new WorldId(UUID.randomUUID());
    var lot = UUID.randomUUID();
    var raw = new ItemStack(CashCatalog.all().getFirst().item().get(), 16);
    require(
        CashStackIdentity.mark(raw, world).isEmpty(), "unmarked development cash collectible only");
    var marked = CashStackIdentity.marked(raw, world, lot);
    var split = marked.split(5);
    require(marked.getCount() == 11 && split.getCount() == 5, "vanilla split preserves quantity");
    require(
        CashStackIdentity.mark(marked, world).equals(CashStackIdentity.mark(split, world)),
        "split lot identity");
    var player =
        new ServerPlayer(
            server,
            server.overworld(),
            new GameProfile(UUID.randomUUID(), "CashFixture"),
            ClientInformation.createDefault());
    player.getInventory().setItem(0, marked);
    require(player.getInventory().add(split), "vanilla inventory merge");
    require(
        player.getInventory().getItem(0).getCount() == 16 && split.isEmpty(),
        "merged quantity conservation");
    var otherLot = CashStackIdentity.marked(raw, world, UUID.randomUUID());
    require(!ItemStack.isSameItemSameComponents(marked, otherLot), "different lots cannot merge");
    require(
        CashStackIdentity.mark(marked, new WorldId(UUID.randomUUID())).isEmpty(),
        "world-bound identity");
    var saved = marked.save(server.registryAccess());
    var restored = ItemStack.parse(server.registryAccess(), saved).orElseThrow();
    require(
        restored.getCount() == 16
            && CashStackIdentity.mark(restored, world).orElseThrow().lot().equals(lot),
        "persistent cash component serialization");
    var chf =
        CashCatalog.all().stream()
            .filter(
                value ->
                    value.currency() == ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CHF)
            .toList();
    require(
        chf.size() == 13 && chf.stream().filter(CashCatalog.Denomination::bill).count() == 6,
        "six CHF notes and seven coins");
    for (var denomination : chf) {
      var issued =
          CashStackIdentity.marked(
              new ItemStack(denomination.item().get(), 2), world, UUID.randomUUID());
      var encoded = issued.save(server.registryAccess());
      var decoded = ItemStack.parse(server.registryAccess(), encoded).orElseThrow();
      require(
          CashCatalog.find(decoded).orElseThrow().equals(denomination),
          "CHF registered recognition and saved item identity");
      require(
          CashStackIdentity.mark(decoded, world).equals(CashStackIdentity.mark(issued, world)),
          "CHF persistent lot identity");
      require(
          CashStackIdentity.mark(decoded.split(1), world).isPresent() && decoded.getCount() == 1,
          "CHF vanilla split keeps world/lot marker");
    }
    System.out.println(
        "CASH_CATALOG_PASS identities collectible cards vanilla split merge world binding persistent components");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException("Cash fixture failed: " + message);
  }
}
