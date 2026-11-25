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
  public record Entry(
      String id, InstitutionCatalog.InstitutionId issuer, DeferredItem<? extends Item> item) {}

  private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("economy");
  private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

  static {
    for (var design : CardDesigns.all()) {
      var bank = InstitutionCatalog.require(design.issuer());
      register(design.id(), bank, null);
    }
    for (var design : CreditCardDesigns.all())
      register(design.id(), InstitutionCatalog.require(design.issuer()), design.network());
    CardDesigns.legacyAliases().forEach(CardCatalog::alias);
    CreditCardDesigns.legacyAliases().forEach(CardCatalog::alias);
  }

  private CardCatalog() {}

  private static void alias(String oldId, String currentId) {
    ITEMS.addAlias(ResourceLocation.parse(oldId), ResourceLocation.parse(currentId));
  }

  public static String id(InstitutionCatalog.InstitutionId issuer, int tier) {
    return CardDesigns.id(issuer, tier);
  }

  public static List<Entry> all() {
    return List.copyOf(ENTRIES.values());
  }

  /** Every registered card is a current, untiered appearance. */
  public static List<Entry> collectibles() {
    return all();
  }

  public static Optional<Entry> byId(String id) {
    return Optional.ofNullable(ENTRIES.get(id));
  }

  public static Optional<Entry> find(ItemStack stack) {
    if (stack.isEmpty()) return Optional.empty();
    return byId(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
  }

  public static void initialize(IEventBus bus) {
    ITEMS.register(bus);
  }

  private static void register(String personalId, InstitutionCatalog.Entry bank, String network) {
    for (boolean corporate : List.of(false, true)) {
      String id = corporate ? CardDesigns.corporateId(personalId) : personalId;
      var item =
          ITEMS.register(
              ResourceLocation.parse(id).getPath(),
              () -> new PaymentCard(bank, corporate, network));
      ENTRIES.put(id, new Entry(id, bank.id(), item));
    }
  }

  private static final class PaymentCard extends Item {
    private final InstitutionCatalog.Entry bank;
    private final boolean corporate;
    private final String network;

    PaymentCard(InstitutionCatalog.Entry bank, boolean corporate, String network) {
      super(new Item.Properties().stacksTo(1));
      this.bank = bank;
      this.corporate = corporate;
      this.network = network;
    }

    @Override
    public Component getName(ItemStack stack) {
      return Component.translatable(
          network == null ? "item.economy.card.debit_name" : "item.economy.card.credit_name",
          bank.name());
    }

    @Override
    public void appendHoverText(
        ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
      var scope =
          Component.translatable(
              corporate ? "item.economy.card.corporate" : "item.economy.card.personal");
      if (network != null)
        scope
            .append(" ⋅ ")
            .append(
                Component.translatable(
                    "network.economy." + ResourceLocation.parse(network).getPath()));
      lines.add(scope);
    }
  }
}
