package ca.emilybelnavis.mcmods.economy.simulation.activity;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.institution.FinancialAccounts;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCapital;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.interest.DepositYield;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import java.time.Clock;
import java.util.*;

/** Worker-owned source capture. This adapter installs no model, rate provider or NPC settlement. */
public final class ActivityRuntime {
    private final FoundationStore store;
    private final Clock wall;
    private final WorldId world;

    public ActivityRuntime(FoundationStore store, Clock wall) {
        this.store = Objects.requireNonNull(store);
        this.wall = Objects.requireNonNull(wall);
        this.world = store.snapshot().worldId();
    }

    /** At most one manifest or <=16-account seed commit per startup turn. */
    public boolean prepare() {
        if (!store.transaction(tx -> ActivityStore.initialized(tx::query))) {
            ActivityStore.bootstrap(store, wall);
            return false;
        }
        var state = store.transaction(tx -> ActivityStore.activation(tx::query));
        if (state.ready()) return true;
        var page = store.transaction(tx -> tx.query("""
                SELECT account_id,institution_key,currency FROM institution_financial_accounts
                WHERE world_id=? AND role='CASH' AND account_id>? ORDER BY account_id LIMIT 16
                """, List.of(world.value().toString(), state.capacityCursor()), 16));
        var capacities = store.transaction(tx -> {
            var result = new ArrayList<ActivityFacts.Capacity>();
            for (var row : page) {
                var cash = new Cash(UUID.fromString(row.get("account_id")), row.get("institution_key"),
                        EconomyCurrency.valueOf(row.get("currency")));
                if (lendingProvider(cash.bank())) result.add(capacity(tx::query, cash, null, null));
            }
            return List.copyOf(result);
        });
        String cursor = page.isEmpty() ? state.capacityCursor() : page.getLast().get("account_id");
        ActivityStore.seed(store, capacities, cursor, page.size() < 16, Math.max(0, wall.millis()));
        return false;
    }

    public JournalEngine.Decision observe(Journal.Intent intent, JournalEngine.ReadView reads,
                                          JournalEngine.Commit commit, long originalEffectiveMillis) {
        if (!ActivityStore.initialized(reads)) return commit;
        // These are already captured original seed/manifest outcomes, not another economic source.
        if (commit.records().stream().anyMatch(r -> r.type().equals(ActivityFacts.MANIFEST)
                || r.type().equals(ActivityFacts.BATCH))) return commit;
        var observations = ActivityClassifier.classify(intent, reads, commit, originalEffectiveMillis);
        var capacities = changedCapacities(reads, commit);
        return ActivityStore.decorate(intent, reads, commit, observations, capacities, Math.max(0, wall.millis()));
    }

    private record Cash(UUID id, String bank, EconomyCurrency currency) {}
    private record Floor(EconomyCurrency currency, long minor) {}
    private record ClaimKey(String bank, UUID account, EconomyCurrency currency) {}

    private List<ActivityFacts.Capacity> changedCapacities(JournalEngine.ReadView reads, JournalEngine.Commit commit) {
        var planned = new LinkedHashMap<UUID, Cash>();
        var floors = new HashMap<String, Floor>();
        var claims = new LinkedHashMap<ClaimKey, Boolean>();
        var banks = new TreeSet<String>();
        for (var fact : commit.records()) {
            if (fact.type().equals(InstitutionCapital.ACCOUNT)) {
                requireVersion(fact, 1);
                var p = fact.payload().split("\\|", -1);
                if (p.length != 5 || !p[1].equals(world.value().toString())) throw new IllegalArgumentException("Activity institution identity");
                if (p[3].equals("CASH")) {
                    var cash = new Cash(UUID.fromString(p[0]), p[2], EconomyCurrency.valueOf(p[4]));
                    planned.put(cash.id(), cash);
                    if(lendingProvider(cash.bank()))banks.add(cash.bank());
                }
            } else if (fact.type().equals(InstitutionCapital.ALLOCATION)) {
                requireVersion(fact, 1);
                var p = fact.payload().split("\\|", -1);
                if (p.length != 13 || !p[0].equals(world.value().toString())) throw new IllegalArgumentException("Activity capital identity");
                if (lendingProvider(p[2])) {
                    floors.put(p[2], new Floor(EconomyCurrency.valueOf(p[5]), Long.parseLong(p[11])));
                    banks.add(p[2]);
                }
            } else if (fact.type().equals(DepositYield.FACT)) {
                requireVersion(fact, 1);
                changedClaim(fact.payload(), claims, banks);
            } else if (fact.type().equals(DepositYield.STATE_BATCH)) {
                requireVersion(fact, 1);
                for (String line : fact.payload().split("\n", -1)) {
                    int separator = line.indexOf('\t');
                    if (separator < 0) throw new IllegalArgumentException("Activity yield state batch");
                    changedClaim(line.substring(separator + 1), claims, banks);
                }
            }
        }
        for (var fact : commit.records()) {
            if (!fact.type().equals("economy:posting")) continue;
            if (fact.version() < 1 || fact.version() > 4) throw new IllegalArgumentException("Activity posting version");
            var p = fact.payload().split("\\|", -1);
            if (p.length != 5) throw new IllegalArgumentException("Activity posting fields");
            if (!p[1].equals("INSTITUTION_CASH")) continue;
            UUID id = UUID.fromString(p[0]);
            Cash cash = planned.get(id);
            if (cash == null) {
                var account = FinancialAccounts.find(reads, id).orElseThrow(() -> new IllegalArgumentException("Activity cash account missing"));
                if (!account.world().equals(world) || account.role() != FinancialAccounts.Role.CASH)
                    throw new IllegalArgumentException("Activity cash account role");
                cash = new Cash(account.id(), account.owner().key(), account.currency());
            }
            if (lendingProvider(cash.bank())) banks.add(cash.bank());
        }
        if (banks.size() > 25) throw new IllegalArgumentException("Activity institution bound");
        var after = FeeSettlement.overlay(reads, commit.records());
        var result = new ArrayList<ActivityFacts.Capacity>();
        for (String bank : banks) {
            var cash = new LinkedHashMap<UUID, Cash>();
            for (var row : reads.query("""
                    SELECT account_id,currency FROM institution_financial_accounts
                    WHERE world_id=? AND institution_key=? AND role='CASH' ORDER BY account_id LIMIT 12
                    """, List.of(world.value().toString(), bank), 12)) {
                var c = new Cash(UUID.fromString(row.get("account_id")), bank, EconomyCurrency.valueOf(row.get("currency")));
                cash.put(c.id(), c);
            }
            for (var c : planned.values()) if (c.bank().equals(bank)) cash.put(c.id(), c);
            if (cash.size() > 11) throw new IllegalArgumentException("Activity cash currency bound");
            boolean distressed = distressedAfter(reads, bank, claims);
            for (var c : cash.values()) result.add(capacity(after, c, floors.get(bank), distressed));
        }
        return List.copyOf(result);
    }

    private ActivityFacts.Capacity capacity(JournalEngine.ReadView reads, Cash cash, Floor planned, Boolean distressed) {
        long balance = Settlement.posted(reads, cash.id(), cash.currency());
        if (balance < 0) throw new IllegalArgumentException("Activity negative bank cash");
        Floor floor = planned;
        if (floor == null) {
            var rows = reads.query("SELECT base_currency,operating_floor_minor FROM institution_capital_allocations WHERE world_id=? AND institution_key=?",
                    List.of(world.value().toString(), cash.bank()), 1);
            if (!rows.isEmpty()) floor = new Floor(EconomyCurrency.valueOf(rows.getFirst().get("base_currency")),
                    Long.parseLong(rows.getFirst().get("operating_floor_minor")));
        }
        boolean blocked = distressed != null ? distressed : DepositYield.distressed(reads, new InstitutionCatalog.InstitutionId(cash.bank()));
        long free = floor == null || blocked ? 0 : Math.max(0, Math.subtractExact(balance,
                floor.currency() == cash.currency() ? floor.minor() : 0));
        return new ActivityFacts.Capacity(cash.id(), cash.bank(), cash.currency(), free);
    }

    private static void changedClaim(String encoded, Map<ClaimKey, Boolean> changes, Set<String> banks) {
        var p = encoded.split("\\|", -1);
        if (p.length != 20) throw new IllegalArgumentException("Activity yield claim fields");
        var key = new ClaimKey(p[2], UUID.fromString(p[0]), EconomyCurrency.valueOf(p[3]));
        changes.put(key, Long.parseLong(p[11]) > 0 && Long.parseLong(p[12]) > 0 && Long.parseLong(p[18]) > 0);
        if (lendingProvider(key.bank())) banks.add(key.bank());
    }

    private static boolean distressedAfter(JournalEngine.ReadView reads, String bank, Map<ClaimKey, Boolean> changes) {
        var changed = changes.entrySet().stream().filter(e -> e.getKey().bank().equals(bank)).toList();
        if (changed.stream().anyMatch(Map.Entry::getValue)) return true;
        if (changed.isEmpty()) return DepositYield.distressed(reads, new InstitutionCatalog.InstitutionId(bank));
        var sql = new StringBuilder("SELECT account_id FROM deposit_yield_claims WHERE bank_key=? AND due_ms>0 AND recognized_minor>0 AND due_minor>0");
        var args = new ArrayList<String>();
        args.add(bank);
        for (var entry : changed) {
            sql.append(" AND NOT(account_id=? AND currency=?)");
            args.add(entry.getKey().account().toString());
            args.add(entry.getKey().currency().name());
        }
        sql.append(" LIMIT 1");
        return !reads.query(sql.toString(), args, 1).isEmpty();
    }

    private static boolean lendingProvider(String key) {
        var id = new InstitutionCatalog.InstitutionId(key);
        return id.equals(InstitutionCatalog.IRONBRIDGE) || InstitutionCatalog.find(id).isPresent();
    }

    private static void requireVersion(Journal.Record record, int version) {
        if (record.version() != version) throw new IllegalArgumentException("Unsupported activity source version");
    }
}
