package ca.emilybelnavis.mcmods.economy.cash;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.card.CardCatalog;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Original collectible identities and artwork, with face values independent of issuance. */
public final class CashCatalog {
    public record Denomination(String id, EconomyCurrency currency, long minorUnits, boolean bill,
                               DeferredItem<Item> item) {}

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("economy");
    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, "economy");
    private static final Map<String, Denomination> CASH;
    private static final List<String> TAB_IDS;

    static {
        var cash = new LinkedHashMap<String, Denomination>();
        register(cash, "eur_coin_1", EconomyCurrency.EUR, 1L, false);
        register(cash, "eur_coin_2", EconomyCurrency.EUR, 2L, false);
        register(cash, "eur_coin_5", EconomyCurrency.EUR, 5L, false);
        register(cash, "eur_coin_10", EconomyCurrency.EUR, 10L, false);
        register(cash, "eur_coin_20", EconomyCurrency.EUR, 20L, false);
        register(cash, "eur_coin_50", EconomyCurrency.EUR, 50L, false);
        register(cash, "eur_coin_100", EconomyCurrency.EUR, 100L, false);
        register(cash, "eur_coin_200", EconomyCurrency.EUR, 200L, false);
        register(cash, "eur_bill_5", EconomyCurrency.EUR, 500L, true);
        register(cash, "eur_bill_10", EconomyCurrency.EUR, 1000L, true);
        register(cash, "eur_bill_20", EconomyCurrency.EUR, 2000L, true);
        register(cash, "eur_bill_50", EconomyCurrency.EUR, 5000L, true);
        register(cash, "eur_bill_100", EconomyCurrency.EUR, 10000L, true);
        register(cash, "eur_bill_200", EconomyCurrency.EUR, 20000L, true);

        register(cash, "gbp_coin_1", EconomyCurrency.GBP, 1L, false);
        register(cash, "gbp_coin_2", EconomyCurrency.GBP, 2L, false);
        register(cash, "gbp_coin_5", EconomyCurrency.GBP, 5L, false);
        register(cash, "gbp_coin_10", EconomyCurrency.GBP, 10L, false);
        register(cash, "gbp_coin_20", EconomyCurrency.GBP, 20L, false);
        register(cash, "gbp_coin_50", EconomyCurrency.GBP, 50L, false);
        register(cash, "gbp_coin_100", EconomyCurrency.GBP, 100L, false);
        register(cash, "gbp_coin_200", EconomyCurrency.GBP, 200L, false);
        register(cash, "gbp_bill_5", EconomyCurrency.GBP, 500L, true);
        register(cash, "gbp_bill_10", EconomyCurrency.GBP, 1000L, true);
        register(cash, "gbp_bill_20", EconomyCurrency.GBP, 2000L, true);
        register(cash, "gbp_bill_50", EconomyCurrency.GBP, 5000L, true);

        register(cash, "aud_coin_5", EconomyCurrency.AUD, 5L, false);
        register(cash, "aud_coin_10", EconomyCurrency.AUD, 10L, false);
        register(cash, "aud_coin_20", EconomyCurrency.AUD, 20L, false);
        register(cash, "aud_coin_50", EconomyCurrency.AUD, 50L, false);
        register(cash, "aud_coin_100", EconomyCurrency.AUD, 100L, false);
        register(cash, "aud_coin_200", EconomyCurrency.AUD, 200L, false);
        register(cash, "aud_bill_5", EconomyCurrency.AUD, 500L, true);
        register(cash, "aud_bill_10", EconomyCurrency.AUD, 1000L, true);
        register(cash, "aud_bill_20", EconomyCurrency.AUD, 2000L, true);
        register(cash, "aud_bill_50", EconomyCurrency.AUD, 5000L, true);
        register(cash, "aud_bill_100", EconomyCurrency.AUD, 10000L, true);

        register(cash, "nzd_coin_10", EconomyCurrency.NZD, 10L, false);
        register(cash, "nzd_coin_20", EconomyCurrency.NZD, 20L, false);
        register(cash, "nzd_coin_50", EconomyCurrency.NZD, 50L, false);
        register(cash, "nzd_coin_100", EconomyCurrency.NZD, 100L, false);
        register(cash, "nzd_coin_200", EconomyCurrency.NZD, 200L, false);
        register(cash, "nzd_bill_5", EconomyCurrency.NZD, 500L, true);
        register(cash, "nzd_bill_10", EconomyCurrency.NZD, 1000L, true);
        register(cash, "nzd_bill_20", EconomyCurrency.NZD, 2000L, true);
        register(cash, "nzd_bill_50", EconomyCurrency.NZD, 5000L, true);
        register(cash, "nzd_bill_100", EconomyCurrency.NZD, 10000L, true);

        register(cash, "cad_coin_5", EconomyCurrency.CAD, 5L, false);
        register(cash, "cad_coin_10", EconomyCurrency.CAD, 10L, false);
        register(cash, "cad_coin_25", EconomyCurrency.CAD, 25L, false);
        register(cash, "cad_coin_50", EconomyCurrency.CAD, 50L, false);
        register(cash, "cad_coin_100", EconomyCurrency.CAD, 100L, false);
        register(cash, "cad_coin_200", EconomyCurrency.CAD, 200L, false);
        register(cash, "cad_bill_5", EconomyCurrency.CAD, 500L, true);
        register(cash, "cad_bill_10", EconomyCurrency.CAD, 1000L, true);
        register(cash, "cad_bill_20", EconomyCurrency.CAD, 2000L, true);
        register(cash, "cad_bill_50", EconomyCurrency.CAD, 5000L, true);
        register(cash, "cad_bill_100", EconomyCurrency.CAD, 10000L, true);
        register(cash, "cad_bill_5_horizontal", EconomyCurrency.CAD, 500L, true);
        register(cash, "cad_bill_10_horizontal", EconomyCurrency.CAD, 1000L, true);
        register(cash, "cad_bill_20_horizontal", EconomyCurrency.CAD, 2000L, true);
        register(cash, "cad_bill_50_horizontal", EconomyCurrency.CAD, 5000L, true);
        register(cash, "cad_bill_100_horizontal", EconomyCurrency.CAD, 10000L, true);

        register(cash, "usd_coin_1", EconomyCurrency.USD, 1L, false);
        register(cash, "usd_coin_5", EconomyCurrency.USD, 5L, false);
        register(cash, "usd_coin_10", EconomyCurrency.USD, 10L, false);
        register(cash, "usd_coin_25", EconomyCurrency.USD, 25L, false);
        register(cash, "usd_coin_50", EconomyCurrency.USD, 50L, false);
        register(cash, "usd_coin_100", EconomyCurrency.USD, 100L, false);
        register(cash, "usd_bill_1", EconomyCurrency.USD, 100L, true);
        register(cash, "usd_bill_2", EconomyCurrency.USD, 200L, true);
        register(cash, "usd_bill_5", EconomyCurrency.USD, 500L, true);
        register(cash, "usd_bill_10", EconomyCurrency.USD, 1000L, true);
        register(cash, "usd_bill_20", EconomyCurrency.USD, 2000L, true);
        register(cash, "usd_bill_50", EconomyCurrency.USD, 5000L, true);
        register(cash, "usd_bill_100", EconomyCurrency.USD, 10000L, true);

        register(cash, "hkd_coin_10", EconomyCurrency.HKD, 10L, false);
        register(cash, "hkd_coin_20", EconomyCurrency.HKD, 20L, false);
        register(cash, "hkd_coin_50", EconomyCurrency.HKD, 50L, false);
        register(cash, "hkd_coin_100", EconomyCurrency.HKD, 100L, false);
        register(cash, "hkd_coin_200", EconomyCurrency.HKD, 200L, false);
        register(cash, "hkd_coin_500", EconomyCurrency.HKD, 500L, false);
        register(cash, "hkd_coin_1000", EconomyCurrency.HKD, 1000L, false);
        register(cash, "hkd_bill_10", EconomyCurrency.HKD, 1000L, true);
        register(cash, "hkd_bill_20", EconomyCurrency.HKD, 2000L, true);
        register(cash, "hkd_bill_50", EconomyCurrency.HKD, 5000L, true);
        register(cash, "hkd_bill_100", EconomyCurrency.HKD, 10000L, true);
        register(cash, "hkd_bill_500", EconomyCurrency.HKD, 50000L, true);
        register(cash, "hkd_bill_1000", EconomyCurrency.HKD, 100000L, true);

        register(cash, "jpy_coin_1", EconomyCurrency.JPY, 1L, false);
        register(cash, "jpy_coin_5", EconomyCurrency.JPY, 5L, false);
        register(cash, "jpy_coin_10", EconomyCurrency.JPY, 10L, false);
        register(cash, "jpy_coin_50", EconomyCurrency.JPY, 50L, false);
        register(cash, "jpy_coin_100", EconomyCurrency.JPY, 100L, false);
        register(cash, "jpy_coin_500", EconomyCurrency.JPY, 500L, false);
        register(cash, "jpy_bill_1000", EconomyCurrency.JPY, 1000L, true);
        register(cash, "jpy_bill_2000", EconomyCurrency.JPY, 2000L, true);
        register(cash, "jpy_bill_5000", EconomyCurrency.JPY, 5000L, true);
        register(cash, "jpy_bill_10000", EconomyCurrency.JPY, 10000L, true);

        register(cash, "cny_coin_10", EconomyCurrency.CNY, 10L, false);
        register(cash, "cny_coin_50", EconomyCurrency.CNY, 50L, false);
        register(cash, "cny_coin_100", EconomyCurrency.CNY, 100L, false);
        register(cash, "cny_bill_1", EconomyCurrency.CNY, 100L, true);
        register(cash, "cny_bill_5", EconomyCurrency.CNY, 500L, true);
        register(cash, "cny_bill_10", EconomyCurrency.CNY, 1000L, true);
        register(cash, "cny_bill_20", EconomyCurrency.CNY, 2000L, true);
        register(cash, "cny_bill_50", EconomyCurrency.CNY, 5000L, true);
        register(cash, "cny_bill_100", EconomyCurrency.CNY, 10000L, true);

        register(cash, "dkk_coin_50", EconomyCurrency.DKK, 50L, false);
        register(cash, "dkk_coin_100", EconomyCurrency.DKK, 100L, false);
        register(cash, "dkk_coin_200", EconomyCurrency.DKK, 200L, false);
        register(cash, "dkk_coin_500", EconomyCurrency.DKK, 500L, false);
        register(cash, "dkk_coin_1000", EconomyCurrency.DKK, 1000L, false);
        register(cash, "dkk_coin_2000", EconomyCurrency.DKK, 2000L, false);
        register(cash, "dkk_bill_50", EconomyCurrency.DKK, 5000L, true);
        register(cash, "dkk_bill_100", EconomyCurrency.DKK, 10000L, true);
        register(cash, "dkk_bill_200", EconomyCurrency.DKK, 20000L, true);
        register(cash, "dkk_bill_500", EconomyCurrency.DKK, 50000L, true);
        register(cash, "chf_coin_5", EconomyCurrency.CHF, 5L, false);
        register(cash, "chf_coin_10", EconomyCurrency.CHF, 10L, false);
        register(cash, "chf_coin_20", EconomyCurrency.CHF, 20L, false);
        register(cash, "chf_coin_50", EconomyCurrency.CHF, 50L, false);
        register(cash, "chf_coin_100", EconomyCurrency.CHF, 100L, false);
        register(cash, "chf_coin_200", EconomyCurrency.CHF, 200L, false);
        register(cash, "chf_coin_500", EconomyCurrency.CHF, 500L, false);
        register(cash, "chf_bill_10", EconomyCurrency.CHF, 1000L, true);
        register(cash, "chf_bill_20", EconomyCurrency.CHF, 2000L, true);
        register(cash, "chf_bill_50", EconomyCurrency.CHF, 5000L, true);
        register(cash, "chf_bill_100", EconomyCurrency.CHF, 10000L, true);
        register(cash, "chf_bill_200", EconomyCurrency.CHF, 20000L, true);
        register(cash, "chf_bill_1000", EconomyCurrency.CHF, 100000L, true);
        CASH = Collections.unmodifiableMap(cash);
        var tabs = new ArrayList<String>();
        var overview = TABS.register("economy", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.economy"))
                .icon(() -> new ItemStack(CASH.get("cad_bill_20_horizontal").item().get()))
                .displayItems((parameters, output) -> {
                    output.accept(ca.emilybelnavis.mcmods.economy.collection.CollectionCatalog.SPAWN_EGG.get());
                    output.accept(ca.emilybelnavis.mcmods.economy.banker.entity.BankerCatalog.SPAWN_EGG.get());
                    output.accept(ca.emilybelnavis.mcmods.economy.banker.desk.DeskCatalog.ITEM.get());
                    CASH.values().forEach(value -> output.accept(value.item().get()));
                    CardCatalog.collectibles().forEach(value -> output.accept(value.item().get()));
                }).build());
        tabs.add("economy");
        DeferredHolder<CreativeModeTab, CreativeModeTab> previous = overview;
        previous = currencyTab("eur", "eur_bill_20", EconomyCurrency.EUR, previous);
        tabs.add("eur");
        previous = currencyTab("gbp", "gbp_bill_50", EconomyCurrency.GBP, previous);
        tabs.add("gbp");
        previous = currencyTab("aud", "aud_bill_20", EconomyCurrency.AUD, previous);
        tabs.add("aud");
        previous = currencyTab("nzd", "nzd_bill_20", EconomyCurrency.NZD, previous);
        tabs.add("nzd");
        previous = currencyTab("cad", "cad_bill_20_horizontal", EconomyCurrency.CAD, previous);
        tabs.add("cad");
        previous = currencyTab("usd", "usd_bill_100", EconomyCurrency.USD, previous);
        tabs.add("usd");
        previous = currencyTab("hkd", "hkd_bill_20", EconomyCurrency.HKD, previous);
        tabs.add("hkd");
        previous = currencyTab("jpy", "jpy_bill_1000", EconomyCurrency.JPY, previous);
        tabs.add("jpy");
        previous = currencyTab("cny", "cny_bill_20", EconomyCurrency.CNY, previous);
        tabs.add("cny");
        previous = currencyTab("dkk", "dkk_coin_2000", EconomyCurrency.DKK, previous);
        tabs.add("dkk");
        previous = currencyTab("chf", "chf_bill_100", EconomyCurrency.CHF, previous);
        tabs.add("chf");
        var lastCurrency = previous;
        TABS.register("cards", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.economy.cards"))
                .withTabsBefore(lastCurrency.getKey())
                .icon(() -> new ItemStack(CardCatalog.collectibles().getFirst().item().get()))
                .displayItems((parameters, output) -> CardCatalog.collectibles().forEach(value -> output.accept(value.item().get())))
                .build());
        tabs.add("cards");
        TAB_IDS = List.copyOf(tabs);
    }

    private CashCatalog() {}

    public static void initialize(IEventBus bus) {
        ITEMS.register(bus);
        TABS.register(bus);
    }

    public static List<Denomination> all() { return List.copyOf(CASH.values()); }
    public static List<String> tabIds() { return TAB_IDS; }

    public static Optional<Denomination> byId(String id) {
        String path = id.startsWith("economy:") ? id.substring("economy:".length()) : id;
        return Optional.ofNullable(CASH.get(path));
    }

    public static Optional<Denomination> find(ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();
        var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key.getNamespace().equals("economy") ? Optional.ofNullable(CASH.get(key.getPath())) : Optional.empty();
    }

    private static void register(Map<String, Denomination> cash, String id, EconomyCurrency currency,
                                 long minorUnits, boolean bill) {
        DeferredItem<Item> item = ITEMS.register(id, () -> new CollectibleItem(new Item.Properties().stacksTo(64)));
        cash.put(id, new Denomination(id, currency, minorUnits, bill, item));
    }

    private static DeferredHolder<CreativeModeTab, CreativeModeTab> currencyTab(String code, String icon,
            EconomyCurrency currency, DeferredHolder<CreativeModeTab, CreativeModeTab> previous) {
        return TABS.register(code, () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.economy." + code))
                .withTabsBefore(previous.getKey())
                .icon(() -> new ItemStack(CASH.get(icon).item().get()))
                .displayItems((parameters, output) -> CASH.values().stream()
                        .filter(value -> value.currency() == currency)
                        .forEach(value -> output.accept(value.item().get())))
                .build());
    }

    private static final class CollectibleItem extends Item {
        private CollectibleItem(Properties properties) {
            super(properties);
        }

        @Override
        public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                    List<Component> tooltip, TooltipFlag flag) {
            super.appendHoverText(stack, context, tooltip, flag);
            tooltip.add(Component.translatable("cash.economy.collectible_tooltip"));
        }
    }
}
