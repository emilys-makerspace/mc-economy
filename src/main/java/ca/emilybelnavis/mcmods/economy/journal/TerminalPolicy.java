package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;

/** Worker-side settings for trusted terminal placement and configuration adapters. */
public final class TerminalPolicy {
    public static final String FACT = "economy:terminal_policy";
    public static final int MAX_CURRENCIES = 11;
    public static final int MAX_DENOMINATIONS = 131;

    public enum Kind { ATM, TELLER, REGISTER, KIOSK }
    public enum Mode {
        BOTH, DEPOSIT, WITHDRAW, DISABLED;
        public boolean deposits() { return this == BOTH || this == DEPOSIT; }
        public boolean withdrawals() { return this == BOTH || this == WITHDRAW; }
    }
    public enum Action {
        PLACE, CONFIGURE, REMOVE;
        public Journal.Kind kind() {
            return new Journal.Kind("economy:terminal_policy", "economy:" + name().toLowerCase(Locale.ROOT));
        }
    }

    public record Anchor(String dimension, int x, int y, int z) {
        public Anchor {
            key(dimension);
            if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000 || y < -2048 || y > 2047)
                throw new IllegalArgumentException("Terminal anchor bounds");
        }
    }
    public record Denomination(EconomyCurrency currency, boolean bill) {
        public Denomination { Objects.requireNonNull(currency); }
    }
    public record Settings(Mode mode, Set<EconomyCurrency> currencies, Set<String> denominations) {
        public Settings {
            Objects.requireNonNull(mode);
            Objects.requireNonNull(currencies);
            Objects.requireNonNull(denominations);
            if (currencies.size() > MAX_CURRENCIES || denominations.size() > MAX_DENOMINATIONS)
                throw new IllegalArgumentException("Terminal allowlist bounds");
            currencies = Set.copyOf(currencies);
            denominations.forEach(TerminalPolicy::key);
            denominations = Collections.unmodifiableSet(new TreeSet<>(denominations));
            if (mode != Mode.DISABLED && (currencies.isEmpty() || denominations.isEmpty()))
                throw new IllegalArgumentException("Enabled terminal requires allowlists");
        }
        public static Settings disabled() { return new Settings(Mode.DISABLED, Set.of(), Set.of()); }
    }
    public record Snapshot(WorldId world, Anchor anchor, UUID generation, ActorId owner, Kind kind,
                           long revision, boolean active, Settings settings, TerminalBanking.Binding binding) {
        public Snapshot(WorldId world, Anchor anchor, UUID generation, ActorId owner, Kind kind,
                        long revision, boolean active, Settings settings) {
            this(world, anchor, generation, owner, kind, revision, active, settings, null);
        }
        public Snapshot {
            Objects.requireNonNull(world); Objects.requireNonNull(anchor); Objects.requireNonNull(generation);
            Objects.requireNonNull(owner); Objects.requireNonNull(kind); Objects.requireNonNull(settings);
            if (revision < 1) throw new IllegalArgumentException("Terminal revision");
            if (kind == Kind.REGISTER && settings.mode() != Mode.DISABLED)
                throw new IllegalArgumentException("Register cash modes disabled");
        }
    }

    /** Trusted, live placement or menu/session proof. Never capture Minecraft objects in worker callbacks. */
    @FunctionalInterface public interface Authority {
        boolean allows(Action action, Intent intent, Snapshot current, ReadView reads);
    }
    @FunctionalInterface public interface Operators {
        boolean allows(ActorId actor, ReadView reads);
    }

    private TerminalPolicy() {}

    /** Historical pre-A6 defaults, retained solely for legacy teller operation revalidation. */
    public static Settings defaultSettings(Kind kind, Map<String, Denomination> catalog) {
        Objects.requireNonNull(kind);
        var immutable = Map.copyOf(catalog);
        if (kind == Kind.REGISTER || kind == Kind.KIOSK) return Settings.disabled();
        var denominations = new TreeSet<String>();
        immutable.forEach((id, denomination) -> {
            if (kind == Kind.TELLER || denomination.bill()) denominations.add(id);
        });
        return new Settings(Mode.BOTH, EnumSet.allOf(EconomyCurrency.class), denominations);
    }

    /** Frozen legacy teller semantics; bound terminals retain their explicit native-currency settings. */
    public static Snapshot effective(Snapshot snapshot, Map<String, Denomination> catalog) {
        Objects.requireNonNull(snapshot);
        if (snapshot.kind() == Kind.KIOSK && snapshot.binding() != null && snapshot.binding().bound()) {
            var currency = snapshot.binding().nativeCurrency();
            var denominations = new TreeSet<String>();
            catalog.forEach((id, denomination) -> { if (denomination.currency() == currency) denominations.add(id); });
            return new Snapshot(snapshot.world(), snapshot.anchor(), snapshot.generation(), snapshot.owner(), snapshot.kind(),
                    snapshot.revision(), snapshot.active(), new Settings(Mode.BOTH, Set.of(currency), denominations), snapshot.binding());
        }
        if (snapshot.kind() != Kind.TELLER || snapshot.binding() != null && snapshot.binding().bound()) return snapshot;
        return new Snapshot(snapshot.world(), snapshot.anchor(), snapshot.generation(), snapshot.owner(), snapshot.kind(),
                snapshot.revision(), snapshot.active(), defaultSettings(Kind.TELLER, catalog));
    }

    /** Canonical placement identity; service defaults are selected from the trusted catalog at admission. */
    public static CanonicalRequest placeRequest(Anchor anchor, UUID generation, ActorId owner, Kind kind,
                                                UUID expectedGeneration, long expectedRevision) {
        Objects.requireNonNull(generation); Objects.requireNonNull(owner); Objects.requireNonNull(kind);
        expected(expectedGeneration, expectedRevision, true);
        var fields = base(anchor, expectedGeneration, expectedRevision);
        fields.put("generation", generation.toString());
        fields.put("owner", actorKey(owner));
        fields.put("kind", kind.name());
        return new CanonicalRequest(1, fields);
    }

    public static CanonicalRequest configureRequest(Anchor anchor, UUID generation, long expectedRevision, Settings settings) {
        expected(generation, expectedRevision, false);
        Objects.requireNonNull(settings);
        var fields = base(anchor, generation, expectedRevision);
        fields.put("mode", settings.mode().name());
        fields.put("currencies", currencies(settings.currencies()));
        fields.put("denominations", String.join("\n", settings.denominations()));
        return new CanonicalRequest(1, fields);
    }

    public static CanonicalRequest removeRequest(Anchor anchor, UUID generation, long expectedRevision) {
        expected(generation, expectedRevision, false);
        return new CanonicalRequest(1, base(anchor, generation, expectedRevision));
    }

    public static Registration registration(Action action, WorldId world, Map<String, Denomination> catalog,
                                             Authority authority, Operators operators) {
        Objects.requireNonNull(action); Objects.requireNonNull(world);
        var immutableCatalog = Map.copyOf(catalog);
        immutableCatalog.keySet().forEach(TerminalPolicy::key);
        Objects.requireNonNull(authority); Objects.requireNonNull(operators);
        return new Registration(1, (intent, reads) -> prepare(action, world, immutableCatalog, authority, operators, intent, reads));
    }

    private static Decision prepare(Action action, WorldId world, Map<String, Denomination> catalog,
                                    Authority authority, Operators operators, Intent intent, ReadView reads) {
        if (!intent.kind().equals(action.kind())) return reject("TERMINAL_REQUEST");
        Anchor anchor; UUID expectedGeneration; long expectedRevision;
        UUID generation = null; ActorId owner = null; Kind kind = null; Settings settings = null;
        try {
            var fields = intent.request().fields();
            anchor = new Anchor(fields.get("dimension"), Integer.parseInt(fields.get("x")),
                    Integer.parseInt(fields.get("y")), Integer.parseInt(fields.get("z")));
            expectedGeneration = fields.get("expected_generation").isEmpty() ? null : uuid(fields.get("expected_generation"));
            expectedRevision = Long.parseLong(fields.get("expected_revision"));
            CanonicalRequest canonical;
            switch (action) {
                case PLACE -> {
                    generation = uuid(fields.get("generation"));
                    owner = actor(fields.get("owner")); kind = Kind.valueOf(fields.get("kind"));
                    canonical = placeRequest(anchor, generation, owner, kind, expectedGeneration, expectedRevision);
                }
                case CONFIGURE -> {
                    settings = new Settings(Mode.valueOf(fields.get("mode")), parseCurrencies(fields.get("currencies")),
                            parseDenominations(fields.get("denominations")));
                    canonical = configureRequest(anchor, expectedGeneration, expectedRevision, settings);
                }
                case REMOVE -> canonical = removeRequest(anchor, expectedGeneration, expectedRevision);
                default -> throw new IllegalStateException("Terminal action");
            }
            if (!canonical.equals(intent.request())) return reject("TERMINAL_REQUEST");
        } catch (IllegalArgumentException | NullPointerException | IndexOutOfBoundsException malformed) {
            return reject("TERMINAL_REQUEST");
        }

        var current = raw(reads, world, anchor).orElse(null);
        if (!authority.allows(action, intent, current, reads)) return decline("TERMINAL_ACCESS");
        boolean operator = operators.allows(intent.actor(), reads);
        if (action != Action.PLACE && (current == null || (!current.owner().equals(intent.actor()) && !operator)))
            return decline("TERMINAL_ACCESS");
        if (action == Action.PLACE && (!owner.equals(intent.actor()) && !operator))
            return decline("TERMINAL_ACCESS");
        if (action == Action.PLACE && current != null && current.active()
                && !current.owner().equals(intent.actor()) && !operator)
            return decline("TERMINAL_ACCESS");
        if (current == null) {
            if (expectedGeneration != null || expectedRevision != 0 || action != Action.PLACE)
                return reject("TERMINAL_STALE");
        } else if (!current.generation().equals(expectedGeneration) || current.revision() != expectedRevision) {
            return reject("TERMINAL_STALE");
        }
        if (action != Action.PLACE && !current.active()) return reject("TERMINAL_REMOVED");
        if (current != null && current.revision() == Long.MAX_VALUE) return reject("TERMINAL_REVISION_LIMIT");

        if(action==Action.CONFIGURE&&!KioskUpgrades.usable(reads,world,current.generation()))return decline("KIOSK_UPGRADE_PENDING");
        Snapshot changed;
        if (action == Action.PLACE) {
            if (!reads.query("SELECT generation FROM terminal_generations WHERE world_id=? AND generation=?",
                    List.of(world.value().toString(), generation.toString()), 1).isEmpty())
                return reject("TERMINAL_GENERATION_USED");
            changed = new Snapshot(world, anchor, generation, owner, kind, current == null ? 1 : current.revision() + 1,
                    true, Settings.disabled());
        } else {
            if (action == Action.CONFIGURE && !TerminalBanking.validSettings(current, settings, catalog))
                return reject("TERMINAL_POLICY");
            changed = new Snapshot(world, anchor, current.generation(), current.owner(), current.kind(),
                    current.revision() + 1, action != Action.REMOVE, action == Action.CONFIGURE ? settings : current.settings(), current.binding());
        }
        if(action==Action.CONFIGURE && changed.binding()!=null && changed.binding().bound() && settings.mode()!=Mode.DISABLED) {
            var binding=changed.binding();
            changed=new Snapshot(changed.world(),changed.anchor(),changed.generation(),changed.owner(),changed.kind(),changed.revision(),changed.active(),changed.settings(),
                    new TerminalBanking.Binding(binding.world(),binding.anchor(),binding.generation(),binding.institution(),binding.catalogRevision(),binding.nativeCurrency(),TerminalBanking.MigrationState.ENABLED,binding.legacySettings()));
        }
        var previous = current;
        var result=changed;
        var facts=new ArrayList<Journal.Record>();facts.add(new Journal.Record(FACT,1,encode(action,result)));
        if(action==Action.CONFIGURE && result.binding()!=null && result.binding().bound())facts.add(new Journal.Record(TerminalBanking.FACT,1,TerminalBanking.encode(result.binding(),result.revision())));
        return new Commit(facts,
                new Result(1, "TERMINAL_" + action.name(), result.generation().toString()), tx -> {
            if (action == Action.PLACE) tx.execute("""
                    INSERT INTO terminal_generations(world_id,generation,dimension,x,y,z) VALUES(?,?,?,?,?,?)
                    """, List.of(world.value().toString(), result.generation().toString(), anchor.dimension(),
                    Integer.toString(anchor.x()), Integer.toString(anchor.y()), Integer.toString(anchor.z())));
            project(tx, previous, result);
            if (action == Action.PLACE) TerminalBanking.unbound(tx, result);
            if (action == Action.CONFIGURE && result.binding()!=null && result.binding().bound()) TerminalBanking.write(tx,result.binding());
        });
    }

    /** Owner/operator inspection includes the tombstone needed for a subsequent placement token. */
    public static Optional<Snapshot> find(ReadView reads, WorldId world, Anchor anchor, ActorId actor, boolean operator) {
        return raw(reads, world, anchor).filter(snapshot -> operator || snapshot.owner().equals(actor));
    }

    /** Trusted admission guard. Removed and replaced generations cannot validate an old session. */
    public static boolean matches(ReadView reads, WorldId world, Anchor anchor, UUID generation, long revision) {
        return raw(reads, world, anchor).filter(snapshot -> snapshot.active() && snapshot.generation().equals(generation)
                && snapshot.revision() == revision && Branches.serviceUsable(reads,world,generation) && KioskUpgrades.usable(reads,world,generation)).isPresent();
    }

    static Optional<Snapshot> raw(ReadView reads, WorldId world, Anchor anchor) {
        return reads.query("SELECT * FROM terminal_settings WHERE world_id=? AND dimension=? AND x=? AND y=? AND z=?",
                anchorParameters(world, anchor), 1).stream().map(TerminalPolicy::read).map(snapshot -> new Snapshot(
                        snapshot.world(), snapshot.anchor(), snapshot.generation(), snapshot.owner(), snapshot.kind(),
                        snapshot.revision(), snapshot.active(), snapshot.settings(),
                        TerminalBanking.find(reads,world,anchor,snapshot.generation()).orElse(null))).findFirst();
    }

    static void project(FoundationStore.Transaction tx, Snapshot previous, Snapshot changed) {
        if (previous == null) {
            tx.execute("INSERT INTO terminal_settings VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", values(changed));
            return;
        }
        var parameters = new ArrayList<String>();
        parameters.add(changed.generation().toString()); parameters.add(actorKey(changed.owner()));
        parameters.add(changed.kind().name()); parameters.add(Long.toString(changed.revision()));
        parameters.add(changed.active() ? "1" : "0"); parameters.add(changed.settings().mode().name());
        parameters.add(currencies(changed.settings().currencies()));
        parameters.add(String.join("\n", changed.settings().denominations()));
        parameters.addAll(anchorParameters(changed.world(), changed.anchor()));
        parameters.add(previous.generation().toString()); parameters.add(Long.toString(previous.revision()));
        int count = tx.execute("""
                UPDATE terminal_settings SET generation=?,owner=?,terminal_kind=?,revision=?,active=?,mode=?,currencies=?,
                denominations=? WHERE world_id=? AND dimension=? AND x=? AND y=? AND z=? AND generation=? AND revision=?
                """, parameters);
        if (count != 1) throw new IllegalStateException("Terminal policy changed");
    }

    private static List<String> values(Snapshot value) {
        var fields = new ArrayList<>(anchorParameters(value.world(), value.anchor()));
        fields.add(value.generation().toString()); fields.add(actorKey(value.owner())); fields.add(value.kind().name());
        fields.add(Long.toString(value.revision())); fields.add(value.active() ? "1" : "0");
        fields.add(value.settings().mode().name()); fields.add(currencies(value.settings().currencies()));
        fields.add(String.join("\n", value.settings().denominations()));
        return fields;
    }

    private static List<String> anchorParameters(WorldId world, Anchor anchor) {
        return List.of(world.value().toString(), anchor.dimension(), Integer.toString(anchor.x()),
                Integer.toString(anchor.y()), Integer.toString(anchor.z()));
    }

    private static Map<String, String> base(Anchor anchor, UUID expectedGeneration, long expectedRevision) {
        Objects.requireNonNull(anchor);
        var fields = new HashMap<String, String>();
        fields.put("dimension", anchor.dimension()); fields.put("x", Integer.toString(anchor.x()));
        fields.put("y", Integer.toString(anchor.y())); fields.put("z", Integer.toString(anchor.z()));
        fields.put("expected_generation", expectedGeneration == null ? "" : expectedGeneration.toString());
        fields.put("expected_revision", Long.toString(expectedRevision));
        return fields;
    }

    private static Snapshot read(Map<String, String> row) {
        return new Snapshot(new WorldId(uuid(row.get("world_id"))),
                new Anchor(row.get("dimension"), Integer.parseInt(row.get("x")), Integer.parseInt(row.get("y")), Integer.parseInt(row.get("z"))),
                uuid(row.get("generation")), actor(row.get("owner")), Kind.valueOf(row.get("terminal_kind")),
                Long.parseLong(row.get("revision")), switch (row.get("active")) {
                    case "1" -> true; case "0" -> false; default -> throw new IllegalArgumentException("Terminal active flag");
                }, new Settings(Mode.valueOf(row.get("mode")), parseCurrencies(row.get("currencies")),
                parseDenominations(row.get("denominations"))));
    }

    private static String currencies(Set<EconomyCurrency> currencies) {
        return currencies.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(","));
    }
    private static Set<EconomyCurrency> parseCurrencies(String value) {
        if (value.isEmpty()) return Set.of();
        if (value.length() > 64) throw new IllegalArgumentException("Terminal currencies");
        var result = EnumSet.noneOf(EconomyCurrency.class);
        for (var entry : value.split(",", -1)) {
            if (!result.add(EconomyCurrency.valueOf(entry))) throw new IllegalArgumentException("Duplicate currency");
        }
        return result;
    }
    private static Set<String> parseDenominations(String value) {
        if (value.isEmpty()) return Set.of();
        if (value.getBytes(StandardCharsets.UTF_8).length > 12_000) throw new IllegalArgumentException("Terminal denominations");
        var entries = value.split("\n", -1);
        if (entries.length > MAX_DENOMINATIONS) throw new IllegalArgumentException("Terminal denominations");
        var result = new TreeSet<String>();
        for (var entry : entries) {
            key(entry);
            if (!result.add(entry)) throw new IllegalArgumentException("Duplicate denomination");
        }
        return result;
    }
    private static void expected(UUID generation, long revision, boolean freshAllowed) {
        if (generation == null ? !freshAllowed || revision != 0 : revision < 1)
            throw new IllegalArgumentException("Expected terminal revision");
    }
    private static UUID uuid(String value) {
        var parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) throw new IllegalArgumentException("Canonical terminal UUID");
        return parsed;
    }
    private static void key(String value) {
        if (value == null || value.length() > 96 || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("Terminal namespaced identity");
    }
    static String encode(Action action, Snapshot value) {
        var fields = new ArrayList<String>(); fields.add(action.name());
        for (var field : values(value)) fields.add(Base64.getUrlEncoder().withoutPadding().encodeToString(field.getBytes(StandardCharsets.UTF_8)));
        return String.join("|", fields);
    }
    public static void validateFact(String payload) {
        var fields = payload.split("\\|", -1);
        if (fields.length != 14) throw new IllegalArgumentException("Terminal fact fields");
        var action = Action.valueOf(fields[0]);
        var names = List.of("world_id", "dimension", "x", "y", "z", "generation", "owner", "terminal_kind",
                "revision", "active", "mode", "currencies", "denominations");
        var row = new HashMap<String, String>();
        for (int index = 0; index < names.size(); index++)
            row.put(names.get(index), new String(Base64.getUrlDecoder().decode(fields[index + 1]), StandardCharsets.UTF_8));
        var snapshot = read(row);
        if (!encode(action, snapshot).equals(payload)) throw new IllegalArgumentException("Noncanonical terminal fact");
    }

    private static Reject reject(String code) { return new Reject(new Result(1, code, "")); }
    private static Decline decline(String code) { return new Decline(new Result(1, code, "")); }
}
