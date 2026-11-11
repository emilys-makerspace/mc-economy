package ca.emilybelnavis.mcmods.economy.institution;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.SystemActorId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Approved fictional providers. Stable keys, rather than display names, identify every party. */
public final class InstitutionCatalog {
  private InstitutionCatalog() {}

  public record InstitutionId(String key) {
    public InstitutionId {
      if (key == null || key.length() > 128 || !key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
        throw new IllegalArgumentException("Institution key");
    }
  }

  public record SystemActor(WorldId world, InstitutionId institution) {
    public SystemActor {
      Objects.requireNonNull(world);
      Objects.requireNonNull(institution);
    }

    public SystemActorId actorId() {
      return new SystemActorId(
          UUID.nameUUIDFromBytes(
              ("economy:institution-party:v1|" + world.value() + "|" + institution.key())
                  .getBytes(StandardCharsets.UTF_8)));
    }
  }

  public enum Tier {
    TIER1,
    HYBRID,
    TIER2_ONLY,
    INTERNAL
  }

  public enum Network {
    KEYSTONE,
    ORBIT,
    WAYLINE,
    EMBER;

    public String key() {
      return "economy:" + name().toLowerCase(Locale.ROOT);
    }
  }

  public enum Capability {
    BANK_ACCOUNT,
    DEBIT,
    TIER1_PRODUCTS,
    TIER2_PRODUCTS,
    SUBPRIME_ALTERNATIVE,
    LENDING,
    COLLECTIONS
  }

  public record Entry(
      InstitutionId id,
      String name,
      EconomyCurrency baseCurrency,
      Tier tier,
      Set<Network> networks,
      Set<Capability> capabilities) {
    public Entry {
      Objects.requireNonNull(id);
      Objects.requireNonNull(name);
      Objects.requireNonNull(baseCurrency);
      Objects.requireNonNull(tier);
      networks = Set.copyOf(networks);
      capabilities = Set.copyOf(capabilities);
    }

    public boolean customerBank() {
      return tier != Tier.INTERNAL;
    }

    public boolean tier1() {
      return tier == Tier.TIER1 || tier == Tier.HYBRID;
    }

    public boolean tier2() {
      return tier == Tier.TIER2_ONLY || tier == Tier.HYBRID;
    }

    public boolean enabled() {
      return true;
    }
  }

  public static final InstitutionId IRONBRIDGE = new InstitutionId("economy:lender/ironbridge");
  public static final InstitutionId WAYSTONE = new InstitutionId("economy:collector/waystone");
  private static final List<Entry> BANKS =
      List.of(
          bank("crownstone", "Crownstone Bank", "CAD", Tier.HYBRID, Network.WAYLINE, Network.ORBIT),
          bank("orchard_direct", "Orchard Direct", "CAD", Tier.TIER1, Network.ORBIT),
          bank("tidecrest", "Tidecrest Bank", "CAD", Tier.TIER1, Network.WAYLINE),
          bank("crosswind", "Crosswind Bank", "USD", Tier.TIER1, Network.WAYLINE, Network.EMBER),
          bank(
              "lantern_union",
              "Lantern Union",
              "USD",
              Tier.HYBRID,
              Network.ORBIT,
              Network.KEYSTONE),
          bank("mesa_trail", "Mesa Trail Bank", "USD", Tier.TIER1, Network.WAYLINE, Network.EMBER),
          bank(
              "asterbridge",
              "Asterbridge Bank",
              "GBP",
              Tier.HYBRID,
              Network.ORBIT,
              Network.KEYSTONE),
          bank("greenhaven", "Greenhaven Bank", "GBP", Tier.TIER1, Network.WAYLINE),
          bank(
              "meridian_grove",
              "Meridian Grove",
              "EUR",
              Tier.HYBRID,
              Network.ORBIT,
              Network.KEYSTONE),
          bank("slate_harbor", "Slate Harbor Bank", "EUR", Tier.TIER1, Network.WAYLINE),
          bank("fjordlight", "Fjordlight Bank", "DKK", Tier.TIER1, Network.ORBIT),
          bank(
              "harbor_meridian",
              "Harbor Meridian",
              "HKD",
              Tier.HYBRID,
              Network.WAYLINE,
              Network.KEYSTONE),
          bank("jade_terrace", "Jade Terrace Bank", "CNY", Tier.TIER1, Network.ORBIT),
          bank("southern_arc", "Southern Arc Bank", "AUD", Tier.TIER1, Network.WAYLINE),
          bank("fernreach", "Fernreach Bank", "NZD", Tier.TIER1, Network.ORBIT),
          bank("cedar_parcel", "Cedar Parcel Bank", "JPY", Tier.TIER1, Network.WAYLINE),
          bank(
              "lakeward_cantonal",
              "Lakeward Cantonal Bank",
              "CHF",
              Tier.TIER1,
              Network.WAYLINE,
              Network.ORBIT),
          bank("newhaven", "Newhaven Bank", "CHF", Tier.TIER1, Network.ORBIT),
          bank(
              "atlas_reserve",
              "Atlas Reserve",
              "USD",
              Tier.TIER2_ONLY,
              Network.WAYLINE,
              Network.KEYSTONE),
          bank(
              "obsidian_capital",
              "Obsidian Capital Bank",
              "EUR",
              Tier.TIER2_ONLY,
              Network.ORBIT,
              Network.KEYSTONE),
          bank(
              "alpine_vault",
              "Alpine Vault",
              "CHF",
              Tier.TIER2_ONLY,
              Network.WAYLINE,
              Network.KEYSTONE),
          bank(
              "kintsugi_trust",
              "Kintsugi Trust",
              "JPY",
              Tier.TIER2_ONLY,
              Network.ORBIT,
              Network.KEYSTONE),
          bank(
              "mariner_crest",
              "Mariner Crest",
              "AUD",
              Tier.TIER2_ONLY,
              Network.WAYLINE,
              Network.KEYSTONE));

  private static Entry bank(
      String key, String name, String currency, Tier tier, Network... networks) {
    var capabilities = EnumSet.of(Capability.BANK_ACCOUNT, Capability.DEBIT);
    if (tier != Tier.TIER2_ONLY) {
      capabilities.add(Capability.TIER1_PRODUCTS);
      capabilities.add(Capability.SUBPRIME_ALTERNATIVE);
    }
    if (tier != Tier.TIER1) capabilities.add(Capability.TIER2_PRODUCTS);
    return new Entry(
        new InstitutionId("economy:bank/" + key),
        name,
        EconomyCurrency.valueOf(currency),
        tier,
        Set.of(networks),
        capabilities);
  }

  public static List<Entry> banks() {
    return BANKS;
  }

  public static Optional<Entry> find(InstitutionId id) {
    return BANKS.stream().filter(e -> e.id().equals(id)).findFirst();
  }

  public static Entry require(InstitutionId id) {
    return find(id).orElseThrow(() -> new IllegalArgumentException("Unknown customer bank"));
  }

  public static Entry require(InstitutionId id, EconomyCurrency serverCurrency) {
    if (id.equals(IRONBRIDGE))
      return new Entry(
          id,
          "Ironbridge Lending",
          serverCurrency,
          Tier.INTERNAL,
          Set.of(),
          Set.of(Capability.LENDING));
    if (id.equals(WAYSTONE))
      return new Entry(
          id,
          "Waystone Recovery",
          serverCurrency,
          Tier.INTERNAL,
          Set.of(),
          Set.of(Capability.COLLECTIONS));
    return require(id);
  }

  public static List<Entry> all(EconomyCurrency serverCurrency) {
    var entries = new ArrayList<>(BANKS);
    entries.add(require(IRONBRIDGE, serverCurrency));
    entries.add(require(WAYSTONE, serverCurrency));
    return List.copyOf(entries);
  }
}
