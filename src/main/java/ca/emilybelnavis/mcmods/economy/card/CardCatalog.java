package ca.emilybelnavis.mcmods.economy.card;

import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Debit and credit appearances. An item is a lookup key, never an account or borrowing grant. */
public final class CardCatalog {
    public record Entry(String id, InstitutionCatalog.InstitutionId issuer, DeferredItem<? extends Item> item) {}
    private static final DeferredRegister.Items ITEMS=DeferredRegister.createItems("economy");
    private static final Map<String,Entry> ENTRIES=new LinkedHashMap<>();
    static {
        for(var design:CardDesigns.all()) {
            var bank=InstitutionCatalog.require(design.issuer());
            var item=ITEMS.register(design.id().substring("economy:".length()),()->new DebitItem(new Item.Properties().stacksTo(1),bank));
            ENTRIES.put(design.id(),new Entry(design.id(),design.issuer(),item));
        }
        for(var design:CreditCardDesigns.all()) {
            var bank=InstitutionCatalog.require(design.issuer());
            var item=ITEMS.register(design.id().substring("economy:".length()),()->new CreditItem(new Item.Properties().stacksTo(1),bank,design.network()));
            ENTRIES.put(design.id(),new Entry(design.id(),design.issuer(),item));
        }
        CardDesigns.legacyAliases().forEach(CardCatalog::alias);
        CreditCardDesigns.legacyAliases().forEach(CardCatalog::alias);
    }
    private CardCatalog() {}
    private static void alias(String oldId,String currentId) {
        ITEMS.addAlias(ResourceLocation.parse(oldId),ResourceLocation.parse(currentId));
    }
    public static String id(InstitutionCatalog.InstitutionId issuer,int tier) {
        return CardDesigns.id(issuer,tier);
    }
    public static List<Entry> all(){return List.copyOf(ENTRIES.values());}
    /** Every registered card is a current, untiered appearance. */
    public static List<Entry> collectibles() { return all(); }
    public static Optional<Entry> byId(String id){return Optional.ofNullable(ENTRIES.get(id));}
    public static Optional<Entry> find(ItemStack stack) {
        if(stack.isEmpty())return Optional.empty();
        return byId(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }
    public static void initialize(IEventBus bus){ITEMS.register(bus);}
    private static final class CreditItem extends Item {
        private final InstitutionCatalog.Entry issuer;
        private final String network;
        CreditItem(Properties properties,InstitutionCatalog.Entry issuer,String network){super(properties);this.issuer=issuer;this.network=network;}
        @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> lines,TooltipFlag flag) {
            lines.add(Component.literal(issuer.name()+" · "+network.substring("economy:".length())+" Credit"));
            if (stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) {
                lines.add(Component.translatable("item.economy.issued_card.expiry"));
            }
        }
    }
    private static final class DebitItem extends Item {
        private final InstitutionCatalog.Entry issuer;
        DebitItem(Properties properties,InstitutionCatalog.Entry issuer){super(properties);this.issuer=issuer;}
        @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> lines,TooltipFlag flag) {
            lines.add(Component.literal(issuer.name()+" · Debit"));
            if (stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) {
                lines.add(Component.translatable("item.economy.issued_card.expiry"));
            }
        }
    }
}
