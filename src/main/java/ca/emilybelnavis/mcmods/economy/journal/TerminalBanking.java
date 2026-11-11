package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Worker-owned bank binding. Neither block state nor item appearance is financial evidence. */
public final class TerminalBanking {
  public static final String FACT = "economy:terminal_bank_binding";
  public static final Kind BIND = new Kind("economy:terminal_policy", "economy:bind_bank");
  public static final long CATALOG_REVISION = 1;

  public enum MigrationState {
    UNBOUND_BANK,
    BOUND,
    ENABLED
  }

  public record Binding(
      WorldId world,
      TerminalPolicy.Anchor anchor,
      UUID generation,
      InstitutionCatalog.InstitutionId institution,
      long catalogRevision,
      EconomyCurrency nativeCurrency,
      MigrationState migrationState,
      TerminalPolicy.Settings legacySettings) {
    public Binding {
      Objects.requireNonNull(world);
      Objects.requireNonNull(anchor);
      Objects.requireNonNull(generation);
      Objects.requireNonNull(migrationState);
      Objects.requireNonNull(legacySettings);
      if (migrationState != MigrationState.UNBOUND_BANK) {
        var entry = InstitutionCatalog.require(Objects.requireNonNull(institution));
        if (catalogRevision != CATALOG_REVISION || entry.baseCurrency() != nativeCurrency)
          throw new IllegalArgumentException("Terminal bank catalog mismatch");
      } else if (institution != null || nativeCurrency != null || catalogRevision != 0)
        throw new IllegalArgumentException("Unbound terminal metadata");
    }

    public boolean bound() {
      return migrationState != MigrationState.UNBOUND_BANK;
    }
  }

  private TerminalBanking() {}

  public static CanonicalRequest bindRequest(
      TerminalPolicy.Anchor anchor,
      UUID generation,
      long revision,
      InstitutionCatalog.InstitutionId bank,
      boolean confirmNativeChange) {
    Objects.requireNonNull(anchor);
    Objects.requireNonNull(generation);
    InstitutionCatalog.require(bank);
    if (revision < 1) throw new IllegalArgumentException("Terminal revision");
    return new CanonicalRequest(
        1,
        Map.of(
            "dimension",
            anchor.dimension(),
            "x",
            Integer.toString(anchor.x()),
            "y",
            Integer.toString(anchor.y()),
            "z",
            Integer.toString(anchor.z()),
            "generation",
            generation.toString(),
            "revision",
            Long.toString(revision),
            "bank",
            bank.key(),
            "confirm_native_change",
            Boolean.toString(confirmNativeChange)));
  }

  public static Registration registration(
      WorldId world,
      Map<String, TerminalPolicy.Denomination> catalog,
      TerminalPolicy.Authority authority,
      TerminalPolicy.Operators operators) {
    var immutable = Map.copyOf(catalog);
    return new Registration(
        1, (intent, reads) -> prepare(world, immutable, authority, operators, intent, reads));
  }

  private static Decision prepare(
      WorldId world,
      Map<String, TerminalPolicy.Denomination> catalog,
      TerminalPolicy.Authority authority,
      TerminalPolicy.Operators operators,
      Intent intent,
      ReadView reads) {
    TerminalPolicy.Anchor anchor;
    UUID generation;
    long revision;
    InstitutionCatalog.Entry bank;
    try {
      var f = intent.request().fields();
      anchor =
          new TerminalPolicy.Anchor(
              f.get("dimension"),
              Integer.parseInt(f.get("x")),
              Integer.parseInt(f.get("y")),
              Integer.parseInt(f.get("z")));
      generation = UUID.fromString(f.get("generation"));
      revision = Long.parseLong(f.get("revision"));
      bank = InstitutionCatalog.require(new InstitutionCatalog.InstitutionId(f.get("bank")));
      if (!intent.kind().equals(BIND)
          || !bindRequest(
                  anchor,
                  generation,
                  revision,
                  bank.id(),
                  Boolean.parseBoolean(f.get("confirm_native_change")))
              .equals(intent.request())) return reject("TERMINAL_BANK_REQUEST");
      if (!f.get("confirm_native_change").equals("true")) return reject("TERMINAL_BANK_CONFIRM");
    } catch (IllegalArgumentException | NullPointerException malformed) {
      return reject("TERMINAL_BANK_REQUEST");
    }
    var current = TerminalPolicy.raw(reads, world, anchor).orElse(null);
    if (current == null
        || !authority.allows(TerminalPolicy.Action.CONFIGURE, intent, current, reads)
        || !current.owner().equals(intent.actor()) && !operators.allows(intent.actor(), reads))
      return decline("TERMINAL_ACCESS");
    if (!current.active()
        || !current.generation().equals(generation)
        || current.revision() != revision) return reject("TERMINAL_STALE");
    return prepareBinding(world, bank, current, reads);
  }

  /**
   * Package-internal composition for a prepared placement; retains the same custody/native binding
   * rules.
   */
  static Decision prepareBinding(
      WorldId world,
      InstitutionCatalog.Entry bank,
      TerminalPolicy.Snapshot current,
      ReadView reads) {
    return prepareBinding(world, bank, current, reads, TerminalPolicy.Settings.disabled());
  }

  /** Generated ATMs are commissioned for native bills in the same transaction that binds them. */
  static Decision prepareGeneratedBinding(
      WorldId world,
      InstitutionCatalog.Entry bank,
      TerminalPolicy.Snapshot current,
      ReadView reads,
      Map<String, TerminalPolicy.Denomination> catalog) {
    if (current.kind() != TerminalPolicy.Kind.ATM)
      return prepareBinding(world, bank, current, reads);
    var currency = bank.baseCurrency();
    var bills = nativeDenominations(current.kind(), currency, catalog);
    if (bills.isEmpty()) return decline("TERMINAL_BANK_CASH_UNAVAILABLE");
    var settings = new TerminalPolicy.Settings(TerminalPolicy.Mode.BOTH, Set.of(currency), bills);
    return prepareBinding(world, bank, current, reads, settings);
  }

  private static Decision prepareBinding(
      WorldId world,
      InstitutionCatalog.Entry bank,
      TerminalPolicy.Snapshot current,
      ReadView reads,
      TerminalPolicy.Settings settings) {
    long revision = current.revision();
    var anchor = current.anchor();
    var generation = current.generation();
    if (!KioskUpgrades.usable(reads, world, generation)) return decline("KIOSK_UPGRADE_PENDING");
    if (!Branches.providerMatches(reads, world, generation, bank.id().key()))
      return reject("TERMINAL_GENERATED_BANK_LOCKED");
    if (current.kind() == TerminalPolicy.Kind.REGISTER) return reject("TERMINAL_BANK_KIND");
    if (revision == Long.MAX_VALUE) return reject("TERMINAL_REVISION_LIMIT");
    if (current.binding() != null
        && current.binding().migrationState() == MigrationState.ENABLED
        && !current.binding().institution().equals(bank.id()))
      return reject("TERMINAL_BANK_LOCKED");
    if (hasPending(reads, world, generation)) return decline("TERMINAL_BANK_PENDING");
    var legacy =
        current.binding() == null ? current.settings() : current.binding().legacySettings();
    boolean enabled =
        settings.mode() != TerminalPolicy.Mode.DISABLED
            || current.binding() != null
                && current.binding().migrationState() == MigrationState.ENABLED;
    var binding =
        new Binding(
            world,
            anchor,
            generation,
            bank.id(),
            CATALOG_REVISION,
            bank.baseCurrency(),
            enabled ? MigrationState.ENABLED : MigrationState.BOUND,
            legacy);
    var changed =
        new TerminalPolicy.Snapshot(
            world,
            anchor,
            generation,
            current.owner(),
            current.kind(),
            revision + 1,
            true,
            settings,
            binding);
    return new Commit(
        List.of(
            new Journal.Record(
                TerminalPolicy.FACT,
                1,
                TerminalPolicy.encode(TerminalPolicy.Action.CONFIGURE, changed)),
            new Journal.Record(FACT, 1, encode(binding, changed.revision()))),
        new Result(1, "TERMINAL_BANK_BOUND", bank.id().key()),
        tx -> {
          TerminalPolicy.project(tx, current, changed);
          write(tx, binding);
        });
  }

  public static Optional<Binding> find(
      ReadView reads, WorldId world, TerminalPolicy.Anchor anchor, UUID generation) {
    var args = new ArrayList<>(parameters(world, anchor));
    args.add(generation.toString());
    return reads
        .query(
            "SELECT * FROM terminal_bank_bindings WHERE world_id=? AND dimension=? AND x=? AND y=? AND z=? AND generation=?",
            args,
            1)
        .stream()
        .map(TerminalBanking::read)
        .findFirst();
  }

  public static boolean matches(
      ReadView reads,
      WorldId world,
      TerminalPolicy.Anchor anchor,
      UUID generation,
      long revision,
      InstitutionCatalog.InstitutionId bank) {
    return TerminalPolicy.raw(reads, world, anchor)
        .filter(
            s ->
                s.active()
                    && s.generation().equals(generation)
                    && s.revision() == revision
                    && s.binding() != null
                    && s.binding().bound()
                    && s.binding().institution().equals(bank)
                    && Branches.serviceUsable(reads, world, generation)
                    && KioskUpgrades.usable(reads, world, generation))
        .isPresent();
  }

  public static boolean bound(
      ReadView reads, WorldId world, TerminalPolicy.Anchor anchor, UUID generation) {
    return find(reads, world, anchor, generation).filter(Binding::bound).isPresent();
  }

  public static TerminalPolicy.Settings settings(
      TerminalPolicy.Snapshot snapshot,
      TerminalPolicy.Mode mode,
      Map<String, TerminalPolicy.Denomination> catalog) {
    if (snapshot.binding() == null || !snapshot.binding().bound())
      throw new IllegalArgumentException("UNBOUND_BANK");
    if (snapshot.kind() == TerminalPolicy.Kind.KIOSK) return TerminalPolicy.Settings.disabled();
    var currency = snapshot.binding().nativeCurrency();
    return new TerminalPolicy.Settings(
        mode, Set.of(currency), nativeDenominations(snapshot.kind(), currency, catalog));
  }

  private static Set<String> nativeDenominations(
      TerminalPolicy.Kind kind,
      EconomyCurrency currency,
      Map<String, TerminalPolicy.Denomination> catalog) {
    var ids = new TreeSet<String>();
    catalog.forEach(
        (id, d) -> {
          if (d.currency() == currency && (kind == TerminalPolicy.Kind.TELLER || d.bill()))
            ids.add(id);
        });
    return ids;
  }

  static boolean validSettings(
      TerminalPolicy.Snapshot snapshot,
      TerminalPolicy.Settings settings,
      Map<String, TerminalPolicy.Denomination> catalog) {
    if (snapshot.kind() == TerminalPolicy.Kind.REGISTER)
      return settings.equals(TerminalPolicy.Settings.disabled());
    if (snapshot.binding() == null || !snapshot.binding().bound()) return false;
    if (snapshot.kind() == TerminalPolicy.Kind.KIOSK)
      return settings.equals(TerminalPolicy.Settings.disabled());
    var currency = snapshot.binding().nativeCurrency();
    if (settings.currencies().stream().anyMatch(c -> c != currency)) return false;
    for (var id : settings.denominations()) {
      var d = catalog.get(id);
      if (d == null
          || d.currency() != currency
          || !settings.currencies().contains(currency)
          || snapshot.kind() == TerminalPolicy.Kind.ATM && !d.bill()) return false;
    }
    return true;
  }

  /** Bounded fail-closed scan of unresolved cases only, including pre-V22 cases. */
  public static boolean hasPending(ReadView reads, WorldId world, UUID generation) {
    var deposits =
        reads.query(
            """
                SELECT id,owner FROM deposits d WHERE state IN('RESERVED','SAVED','QUARANTINED')
                OR EXISTS(SELECT 1 FROM deposit_returns r WHERE r.deposit_id=d.id AND r.state NOT IN('DELIVERED','CLAIMED'))
                OR EXISTS(SELECT 1 FROM custody_cases c WHERE c.source LIKE 'deposit-return:'||d.id||':%' AND c.state NOT IN('DELIVERED','COMPENSATED'))
                ORDER BY id LIMIT 257
                """,
            List.of(), 257);
    if (deposits.size() > 256) return true;
    for (var row : deposits) {
      var r =
          Deposits.find(reads, Journal.actor(row.get("owner")), UUID.fromString(row.get("id")))
              .orElseThrow();
      if (r.preview().binding().generation().equals(generation)) return true;
    }
    var withdrawals =
        reads.query(
            """
                SELECT id,owner FROM withdrawals w WHERE state IN('RESERVED','QUARANTINED')
                OR EXISTS(SELECT 1 FROM custody_cases c WHERE c.id=w.id AND c.state NOT IN('DELIVERED','COMPENSATED'))
                ORDER BY id LIMIT 257
                """,
            List.of(),
            257);
    if (withdrawals.size() > 256) return true;
    for (var row : withdrawals) {
      var r =
          Withdrawals.find(reads, Journal.actor(row.get("owner")), UUID.fromString(row.get("id")))
              .orElseThrow();
      if (r.preview().binding().generation().equals(generation)) return true;
    }
    return false;
  }

  static void unbound(FoundationStore.Transaction tx, TerminalPolicy.Snapshot snapshot) {
    if (snapshot.kind() == TerminalPolicy.Kind.REGISTER) return;
    write(
        tx,
        new Binding(
            snapshot.world(),
            snapshot.anchor(),
            snapshot.generation(),
            null,
            0,
            null,
            MigrationState.UNBOUND_BANK,
            snapshot.settings()));
  }

  static void write(FoundationStore.Transaction tx, Binding binding) {
    tx.execute(
        "INSERT INTO terminal_bank_bindings VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(world_id,dimension,x,y,z,generation) DO UPDATE SET bank_key=excluded.bank_key,catalog_revision=excluded.catalog_revision,native_currency=excluded.native_currency,migration_state=excluded.migration_state",
        values(binding));
  }

  private static List<String> parameters(WorldId world, TerminalPolicy.Anchor anchor) {
    return List.of(
        world.value().toString(),
        anchor.dimension(),
        Integer.toString(anchor.x()),
        Integer.toString(anchor.y()),
        Integer.toString(anchor.z()));
  }

  private static List<String> values(Binding b) {
    var f = new ArrayList<>(parameters(b.world(), b.anchor()));
    f.add(b.generation().toString());
    f.add(b.institution() == null ? "" : b.institution().key());
    f.add(Long.toString(b.catalogRevision()));
    f.add(b.nativeCurrency() == null ? "" : b.nativeCurrency().name());
    f.add(b.migrationState().name());
    f.add(b.legacySettings().mode().name());
    f.add(
        b.legacySettings().currencies().stream()
            .map(Enum::name)
            .sorted()
            .collect(java.util.stream.Collectors.joining(",")));
    f.add(String.join("\n", b.legacySettings().denominations()));
    return f;
  }

  private static Binding read(Map<String, String> row) {
    var currencies = EnumSet.noneOf(EconomyCurrency.class);
    if (!row.get("legacy_currencies").isEmpty())
      for (var c : row.get("legacy_currencies").split(","))
        currencies.add(EconomyCurrency.valueOf(c));
    var denoms = new TreeSet<String>();
    if (!row.get("legacy_denominations").isEmpty())
      denoms.addAll(List.of(row.get("legacy_denominations").split("\n")));
    return new Binding(
        new WorldId(UUID.fromString(row.get("world_id"))),
        new TerminalPolicy.Anchor(
            row.get("dimension"),
            Integer.parseInt(row.get("x")),
            Integer.parseInt(row.get("y")),
            Integer.parseInt(row.get("z"))),
        UUID.fromString(row.get("generation")),
        row.get("bank_key").isEmpty()
            ? null
            : new InstitutionCatalog.InstitutionId(row.get("bank_key")),
        Long.parseLong(row.get("catalog_revision")),
        row.get("native_currency").isEmpty()
            ? null
            : EconomyCurrency.valueOf(row.get("native_currency")),
        MigrationState.valueOf(row.get("migration_state")),
        new TerminalPolicy.Settings(
            TerminalPolicy.Mode.valueOf(row.get("legacy_mode")), currencies, denoms));
  }

  static String encode(Binding binding, long revision) {
    var f = new ArrayList<String>();
    for (var value : values(binding))
      f.add(
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    f.add(Long.toString(revision));
    return String.join("|", f);
  }

  public static void validateFact(String payload) {
    var f = payload.split("\\|", -1);
    if (f.length != 14) throw new IllegalArgumentException("Terminal binding fact");
    var names =
        List.of(
            "world_id",
            "dimension",
            "x",
            "y",
            "z",
            "generation",
            "bank_key",
            "catalog_revision",
            "native_currency",
            "migration_state",
            "legacy_mode",
            "legacy_currencies",
            "legacy_denominations");
    var row = new HashMap<String, String>();
    for (int i = 0; i < 13; i++)
      row.put(
          names.get(i), new String(Base64.getUrlDecoder().decode(f[i]), StandardCharsets.UTF_8));
    var b = read(row);
    long revision = Long.parseLong(f[13]);
    if (!b.bound() || revision < 2 || !encode(b, revision).equals(payload))
      throw new IllegalArgumentException("Terminal binding fact");
  }

  private static Reject reject(String code) {
    return new Reject(new Result(1, code, ""));
  }

  private static Decline decline(String code) {
    return new Decline(new Result(1, code, ""));
  }
}
