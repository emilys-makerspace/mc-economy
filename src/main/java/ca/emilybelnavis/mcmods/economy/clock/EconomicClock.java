package ca.emilybelnavis.mcmods.economy.clock;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** UTC interval debt, durable completions and deterministic versioned simulation inputs. No game-time input. */
public final class EconomicClock {
    public record Update(UUID id, String system, long startMillis, long endMillis, int version, long randomSeed) {}
    public record Output(String payload, JournalEngine.Projection effects, List<Journal.Record> records) {
        public Output(String payload, JournalEngine.Projection effects) {
            this(payload, effects, List.of());
        }

        public Output {
            EconomicRecord.validateOutput(payload);
            Objects.requireNonNull(effects);
            records = List.copyOf(records);
        }
    }
    @FunctionalInterface public interface Simulation { Output compute(Update update) throws Exception; }
    public record Version(int number, String implementationId, Simulation simulation) {
        public Version { if (number < 1 || implementationId == null || !implementationId.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Simulation version"); Objects.requireNonNull(simulation); }
    }
    public record SystemDefinition(String id, long cadenceMillis, long originMillis, int initialVersion,
                                   Map<Integer, Version> versions, Set<String> dependsOn) {
        public SystemDefinition {
            new Journal.Kind(id, "foundation:interval");
            if (cadenceMillis < 1 || originMillis < 0 || versions.isEmpty() || versions.size() > 16 || !versions.containsKey(initialVersion)) throw new IllegalArgumentException("Clock definition");
            Math.addExact(originMillis, cadenceMillis);
            versions = Map.copyOf(versions); dependsOn = Set.copyOf(dependsOn);
            for (var entry : versions.entrySet()) if (entry.getKey() != entry.getValue().number()) throw new IllegalArgumentException("Version key");
        }
    }
    /** Trusted storage readers for an economic system whose origin is set by a committed domain event. */
    public record DeferredSystem(Origin origin, Prepare prepare, InitialVersion initialVersion) {
        public DeferredSystem(Origin origin, Prepare prepare) { this(origin, prepare, null); }
        public DeferredSystem {
            Objects.requireNonNull(origin);
            Objects.requireNonNull(prepare);
        }
    }

    /** Historical opening facts select a first implementation without rewriting legacy schedules. */
    @FunctionalInterface public interface InitialVersion { int read(Queries reads); }

    @FunctionalInterface public interface Origin {
        /** Null means that the system is not active and has no debt. */
        Long read(Queries reads);
    }

    @FunctionalInterface public interface Prepare {
        /** Return detached input with value equality so commit can revalidate the staged calculation. */
        Calculation read(Update update, Queries reads);
    }

    @FunctionalInterface public interface Calculation {
        Output compute() throws Exception;
    }

    public record Progress(long completedSequence, long outstandingIntervals, boolean waitingForWall, boolean ready) {}
    private record Input(Update update, Version implementation, long sequence, Calculation calculation) {
        Output compute() throws Exception {
            return calculation == null ? implementation.simulation().compute(update) : calculation.compute();
        }
    }
    public record PreparedStep(Progress progress, Work work) {}
    private static final Journal.Kind KIND = new Journal.Kind("foundation:clock", "foundation:interval");
    private final FoundationStore store;
    private final Clock wall;
    private final Map<String, SystemDefinition> definitions;
    private final Map<String, DeferredSystem> deferred;
    private final Map<String,String> installedSuccessors;
    private final Map<String, Integer> order = new HashMap<>();
    private final JournalEngine journal;
    public EconomicClock(FoundationStore store, Clock wall, Collection<SystemDefinition> systems) {
        this(store, wall, systems, Map.of());
    }

    public EconomicClock(FoundationStore store, Clock wall, Collection<SystemDefinition> systems,
                         Map<String, DeferredSystem> deferred) {
        this(store,wall,systems,deferred,Map.of());
    }
    public EconomicClock(FoundationStore store,Clock wall,Collection<SystemDefinition> systems,Map<String,DeferredSystem> deferred,Map<String,String> successors) {
        this.store = store; this.wall = wall;
        this.deferred = Map.copyOf(deferred);
        this.installedSuccessors=Map.copyOf(successors);
        if (systems.size() > 64) throw new IllegalArgumentException("System registry bound");
        Map<String, SystemDefinition> copy = new TreeMap<>();
        for (var system : systems) if (copy.putIfAbsent(system.id(), system) != null) throw new IllegalArgumentException("Duplicate system");
        definitions = Map.copyOf(copy);
        if (!definitions.keySet().containsAll(this.deferred.keySet())) throw new IllegalArgumentException("Unknown deferred system");
        while (order.size() < definitions.size()) {
            boolean advanced = false;
            for (var system : copy.values()) {
                if (!definitions.keySet().containsAll(system.dependsOn())) throw new IllegalArgumentException("Missing dependency");
                if (!order.containsKey(system.id()) && order.keySet().containsAll(system.dependsOn())) { order.put(system.id(), order.size()); advanced = true; }
            }
            if (!advanced) throw new IllegalArgumentException("Cyclic clock dependencies");
        }
        journal = journal((intent, reads) -> {
            var input = input(intent, reads::query);
            return plan(intent, input, Objects.requireNonNull(input.compute()));
        });
    }
    public void prepare() {
        store.transaction(tx -> {
            var persisted = tx.query("SELECT * FROM economic_systems ORDER BY system_id", List.of(), 64);
            for (var row : persisted) if (!definitions.containsKey(row.get("system_id"))) throw failure("Missing persisted economic system " + row.get("system_id"));
            for (var definition : definitions.values()) {
                var existing = tx.query("SELECT * FROM economic_systems WHERE system_id=?", List.of(definition.id()), 1);
                Long origin = effectiveOrigin(definition, tx::query);
                if (origin == null) {
                    if (!existing.isEmpty()) throw failure("Deferred clock lacks its opening record");
                    continue;
                }
                if (existing.isEmpty()) {
                    register(tx, definition, origin, deferred.containsKey(definition.id()), initialVersion(definition, tx::query));
                } else {
                    var row = existing.getFirst();
                    if ((row.get("active_observed_ms") != null) != deferred.containsKey(definition.id())) {
                        throw failure("Economic observation scope changed");
                    }
                    if (num(row,"cadence_ms") != definition.cadenceMillis() || num(row,"origin_ms") != origin) throw failure("Cadence/origin change requires explicit design");
                    var latest = tx.query("SELECT * FROM economic_updates WHERE system_id=? ORDER BY economic_sequence DESC LIMIT 1", List.of(definition.id()), 1);
                    if (latest.isEmpty()) {
                        if (num(row,"frontier_ms") != origin || num(row,"last_sequence") != 0) throw failure("Invalid initial clock checkpoint");
                    } else if (num(row,"frontier_ms") != num(latest.getFirst(),"interval_end_ms") || num(row,"last_sequence") != num(latest.getFirst(),"economic_sequence")) throw failure("Clock checkpoint disagrees with completion");
                }
                var versions = tx.query("SELECT * FROM economic_versions WHERE system_id=? ORDER BY effective_end_ms", List.of(definition.id()), 16);
                if (Integer.parseInt(versions.getFirst().get("version")) != initialVersion(definition, tx::query) || num(versions.getFirst(),"effective_end_ms") != Math.addExact(origin,definition.cadenceMillis())) throw failure("Initial version schedule changed");
                for (var version : versions) {
                    long boundary=num(version,"effective_end_ms");
                    if(boundary<=origin||(boundary-origin)%definition.cadenceMillis()!=0)throw failure("Invalid simulation effective boundary");
                    var implementation = definition.versions().get(Integer.parseInt(version.get("version")));
                    if (implementation == null || !signature(definition,implementation,origin).equals(version.get("implementation_id"))) throw failure("Historical simulator missing or changed");
                }
            }
            for(var row:tx.query("SELECT payload FROM fixed_system_retirements ORDER BY system_id",List.of(),64)) {
                var retirement=FixedRetirements.decode(row.get("payload"));var definition=definitions.get(retirement.system());
                if(definition==null||!FixedRetirements.retains(installedSuccessors.get(retirement.successor()),retirement.successorImplementations())
                        ||!FixedRetirements.versions(tx::query,retirement.system()).equals(retirement.versions())
                        ||!signature(definition,Objects.requireNonNull(definition.versions().get(retirement.version())),retirement.originMillis()).equals(retirement.signature()))throw failure("Retired fixed implementation/successor missing or changed");
            }
            var meta = tx.query("SELECT * FROM economic_metadata", List.of(), 1).getFirst();
            if (!tx.query("SELECT u.update_id FROM economic_updates u WHERE NOT EXISTS(SELECT 1 FROM journal_records r WHERE r.sequence=u.journal_sequence AND r.record_type='foundation:economic-update') LIMIT 1",List.of(),1).isEmpty()) throw failure("Completion lacks authoritative economic record");
            var count = tx.query("SELECT count(*) AS count,COALESCE(max(economic_sequence),0) AS maximum FROM economic_updates", List.of(), 1).getFirst();
            if (num(meta,"global_sequence") != num(count,"maximum") || num(count,"count") != num(count,"maximum")) throw failure("Economic sequence/completion mismatch");
            return null;
        });
    }
    private int initialVersion(SystemDefinition definition, Queries reads) {
        var system = deferred.get(definition.id());
        int version = system == null || system.initialVersion() == null ? definition.initialVersion()
                : system.initialVersion().read(reads);
        if (!definition.versions().containsKey(version)) throw failure("Missing initial historical implementation");
        return version;
    }

    private Long effectiveOrigin(SystemDefinition definition, Queries reads) {
        DeferredSystem system = deferred.get(definition.id());
        if (system == null) return definition.originMillis();
        Long origin = system.origin().read(reads);
        if (origin != null && (origin < 0 || origin % definition.cadenceMillis() != 0
                || origin > Long.MAX_VALUE - definition.cadenceMillis())) {
            throw failure("Invalid deferred origin for " + definition.id());
        }
        return origin;
    }

    private static void register(FoundationStore.Transaction tx, SystemDefinition definition, long origin, boolean deferred, int initialVersion) {
        tx.execute("INSERT INTO economic_systems(system_id,cadence_ms,origin_ms,frontier_ms,last_sequence,active_observed_ms) "
                        + "VALUES(?,?,?,?,0,?)",
                Arrays.asList(definition.id(), str(definition.cadenceMillis()), str(origin), str(origin),
                        deferred ? str(origin) : null));
        var version = definition.versions().get(initialVersion);
        tx.execute("INSERT INTO economic_versions VALUES(?,?,?,?)",
                List.of(definition.id(), str(version.number()), signature(definition, version, origin),
                        str(Math.addExact(origin, definition.cadenceMillis()))));
    }

    private void activateOpenedSystems(FoundationStore.Transaction tx) {
        for (String id : deferred.keySet()) {
            if (!tx.query("SELECT system_id FROM economic_systems WHERE system_id=?", List.of(id), 1).isEmpty()) continue;
            var definition = definitions.get(id);
            Long origin = effectiveOrigin(definition, tx::query);
            if (origin != null) register(tx, definition, origin, deferred.containsKey(definition.id()), initialVersion(definition, tx::query));
        }
    }

    /** Trusted reservation; an original receipt is returned before current successor/time checks. */
    /** Pure retirement component for an atomic prospective macro activation. */
    public JournalEngine.Commit prepareRetirement(JournalEngine.ReadView reads,UUID operation,String system,long takeover,DeadlineDispatcher.Successor successor) {
        return FixedRetirements.prepare(reads,store.snapshot().worldId(),Objects.requireNonNull(definitions.get(system)),takeover,successor,Math.max(0,wall.millis()),operation);
    }
    public FixedRetirements.Receipt retire(String system,long takeoverMillis,DeadlineDispatcher dispatcher,String successorId) {
        var original=store.transaction(tx->FixedRetirements.read(tx::query,system));
        if(original!=null){if(original.takeoverMillis()!=takeoverMillis||!original.successor().equals(successorId))throw new IllegalArgumentException("Original retirement changed");return original;}
        var successor=dispatcher.successorAt(successorId,takeoverMillis);
        if(!FixedRetirements.retains(installedSuccessors.get(successorId),successor.implementations()))throw new IllegalArgumentException("Successor missing from fixed/domain registry");
        return FixedRetirements.retire(store,wall,Objects.requireNonNull(definitions.get(system)),takeoverMillis,successor);
    }

    /** Explicit future boundary; overdue intervals always retain the historical scheduled version. */
    public void scheduleVersion(String system, int version, long effectiveEndMillis) {
        var definition = Objects.requireNonNull(definitions.get(system)); var implementation = Objects.requireNonNull(definition.versions().get(version));
        store.transaction(tx -> {
            activateOpenedSystems(tx);
            if(FixedRetirements.read(tx::query,system)!=null)throw new IllegalArgumentException("Fixed system retirement freezes its version schedule");
            var rows = tx.query("SELECT frontier_ms,origin_ms,active_observed_ms FROM economic_systems WHERE system_id=?", List.of(system), 1);
            if (rows.isEmpty()) throw new IllegalArgumentException("Economic system has no opening");
            long frontier = num(rows.getFirst(), "frontier_ms");
            long origin = num(rows.getFirst(), "origin_ms");
            long observed = observedTarget(rows.getFirst(),
                    tx.query("SELECT observed_wall_ms FROM economic_metadata", List.of(), 1).getFirst());
            if (effectiveEndMillis <= Math.max(Math.max(frontier, observed), wall.millis()) || effectiveEndMillis < origin
                    || (effectiveEndMillis - origin) % definition.cadenceMillis() != 0) throw new IllegalArgumentException("Version boundary must be aligned and strictly in the future");
            tx.execute("INSERT INTO economic_versions VALUES(?,?,?,?)", List.of(system, str(version), signature(definition,implementation,origin), str(effectiveEndMillis)));
            return null;
        });
    }
    public Progress runBatch(int maximumUpdates) {
        if (maximumUpdates < 1 || maximumUpdates > 16) throw new IllegalArgumentException("Clock batch bound");
        long now = Math.max(0, wall.millis());
        store.transaction(tx -> { observe(tx, now); return null; });
        for (int i = 0; i < maximumUpdates; i++) {
            var intent = store.transaction(tx -> nextIntent(tx, now));
            if (intent == null) break;
            submit(journal, intent);
        }
        return progress(now);
    }

    public PreparedStep prepareStep() {
        long now = Math.max(0, wall.millis());
        return store.transaction(tx -> {
            observe(tx, now);
            var progress = progress(tx, now);
            var intent = nextIntent(tx, now);
            return new PreparedStep(progress, intent == null ? null : new Work(intent, input(intent, tx::query)));
        });
    }

    public static final class Work {
        private final Journal.Intent intent;
        private final Input prepared;

        private Work(Journal.Intent intent, Input prepared) {
            this.intent = intent;
            this.prepared = prepared;
        }

        public Output calculate() {
            try {
                return Objects.requireNonNull(prepared.compute());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IllegalStateException("Economic simulation failed for " + prepared.update().id(), e);
            }
        }

    }

    public Progress commit(Work work, Output output) {
        long now = Math.max(0, wall.millis());
        if (work.prepared.update().endMillis() > now) return progress(now);
        var stagedJournal = journal((request, reads) -> {
            var current = input(request, reads::query);
            if (!work.prepared.equals(current)) throw failure("Economic inputs changed before commit");
            return plan(request, current, Objects.requireNonNull(output));
        });
        submit(stagedJournal, work.intent);
        return progress(Math.max(0, wall.millis()));
    }

    private void observe(FoundationStore.Transaction tx, long now) {
        activateOpenedSystems(tx);
        tx.execute("UPDATE economic_systems SET active_observed_ms=min(CAST(? AS INTEGER),COALESCE((SELECT first_excluded_ms-cadence_ms FROM fixed_system_retirements r WHERE r.system_id=economic_systems.system_id),CAST(? AS INTEGER))) "
                + "WHERE active_observed_ms<min(CAST(? AS INTEGER),COALESCE((SELECT first_excluded_ms-cadence_ms FROM fixed_system_retirements r WHERE r.system_id=economic_systems.system_id),CAST(? AS INTEGER)))", List.of(str(now),str(now),str(now),str(now)));
        tx.execute("UPDATE economic_metadata SET observed_wall_ms=max(observed_wall_ms,CAST(? AS INTEGER)) WHERE singleton=1", List.of(str(now)));
    }

    private Journal.Intent nextIntent(FoundationStore.Transaction tx, long now) {
        activateOpenedSystems(tx);
        var next = tx.query("SELECT * FROM economic_systems", List.of(), 64).stream()
                .filter(row -> nextEnd(row) <= now && !FixedRetirements.excludes(tx::query,row.get("system_id"),nextEnd(row)))
                .min(Comparator.<Map<String,String>>comparingLong(this::nextEnd)
                        .thenComparingInt(row -> order.get(row.get("system_id"))).thenComparing(row -> row.get("system_id"))).orElse(null);
        if (next == null) return null;
        String system = next.get("system_id");
        long end = nextEnd(next);
        int version = Integer.parseInt(tx.query("SELECT version FROM economic_versions WHERE system_id=? AND effective_end_ms<=? ORDER BY effective_end_ms DESC LIMIT 1", List.of(system,str(end)), 1).getFirst().get("version"));
        UUID update = stable("update|" + store.snapshot().worldId().value() + "|" + system + "|" + end + "|" + version);
        return new Journal.Intent(new OperationId(stable("operation|"+update)), new RequestId(UUID.randomUUID()), actor(system), KIND,
                new Journal.CanonicalRequest(1, Map.of("system",system,"start",next.get("frontier_ms"),"end",str(end),"version",str(version))), update, null);
    }

    /** Earliest installed fixed-system boundary, including future work. Worker-only merge hook. */
    public Long nextDueMillis() {
        return store.transaction(tx -> {
            activateOpenedSystems(tx);
            return tx.query("SELECT * FROM economic_systems", List.of(), 64).stream()
                    .filter(row->!FixedRetirements.excludes(tx::query,row.get("system_id"),nextEnd(row)))
                    .map(this::nextEnd).min(Long::compareTo).orElse(null);
        });
    }

    private long nextEnd(Map<String, String> row) {
        return Math.addExact(num(row, "frontier_ms"), definitions.get(row.get("system_id")).cadenceMillis());
    }

    private JournalEngine journal(JournalEngine.Handler planner) {
        return new JournalEngine(store, (actor, kind) -> actor instanceof SystemActorId && kind.equals(KIND),
                Map.of(KIND, new JournalEngine.Registration(1, planner)));
    }

    private static void submit(JournalEngine engine, Journal.Intent intent) {
        var outcome = engine.submit(intent);
        if (outcome.status() != Journal.Status.COMMITTED) throw new StorageException(StorageException.Kind.UNSAFE_CONNECTION,
                "Economic update unresolved; retain original ID " + intent.economicUpdateId());
    }

    private Progress progress(long now) {
        return store.transaction(tx -> progress(tx, now));
    }
    public Progress currentProgress() {
        return progress(Math.max(0, wall.millis()));
    }

    /** Persist newly observed debt even if an intervening rewind precedes the scheduler's next poll. */
    public Progress observeCurrentDebt() {
        long now = Math.max(0, wall.millis());
        return store.transaction(tx -> {
            var progress = progress(tx, now);
            if (progress.outstandingIntervals() > 0) observe(tx, now);
            return progress;
        });
    }

    private Progress progress(FoundationStore.Transaction tx, long now) {
        activateOpenedSystems(tx);
        var meta = tx.query("SELECT * FROM economic_metadata", List.of(), 1).getFirst();
        long debt = 0;
        boolean waiting = false;
        for (var row : tx.query("SELECT * FROM economic_systems", List.of(), 64)) {
            long target = FixedRetirements.eligibleTarget(tx::query,row.get("system_id"),Math.max(now, observedTarget(row, meta)));
            long frontier = num(row, "frontier_ms");
            long cadence = definitions.get(row.get("system_id")).cadenceMillis();
            debt = Math.addExact(debt, Math.max(0, target - frontier) / cadence);
            waiting |= (num(row, "last_sequence") > 0 && now < frontier) || (target > now && target - frontier >= cadence);
        }
        return new Progress(num(meta, "global_sequence"), debt, waiting, debt == 0 && !waiting);
    }

    private static long observedTarget(Map<String, String> system, Map<String, String> metadata) {
        return system.get("active_observed_ms") == null
                ? num(metadata, "observed_wall_ms") : num(system, "active_observed_ms");
    }

    @FunctionalInterface
    public interface Queries {
        List<Map<String, String>> query(String sql, List<String> parameters, int maximumRows);
    }
    private Input input(Journal.Intent intent, Queries reads) {
        String system = intent.request().fields().get("system"); var definition = Objects.requireNonNull(definitions.get(system));
        var state = reads.query("SELECT * FROM economic_systems WHERE system_id=?", List.of(system), 1).getFirst();
        long start = Long.parseLong(intent.request().fields().get("start")), end = Long.parseLong(intent.request().fields().get("end"));
        int version = Integer.parseInt(intent.request().fields().get("version"));
        if(FixedRetirements.excludes(reads::query,system,end))throw failure("Economic interval was prospectively retired");
        var scheduled = reads.query("SELECT * FROM economic_versions WHERE system_id=? AND effective_end_ms<=? ORDER BY effective_end_ms DESC LIMIT 1", List.of(system,str(end)),1).getFirst();
        if (start != num(state,"frontier_ms") || end != Math.addExact(start,definition.cadenceMillis()) || Integer.parseInt(scheduled.get("version")) != version || !actor(system).equals(intent.actor())) throw failure("Economic request/checkpoint mismatch");
        var implementation = definition.versions().get(version);
        if (implementation == null || !signature(definition,implementation,num(state,"origin_ms")).equals(scheduled.get("implementation_id"))) throw failure("Historical simulator unavailable");
        var meta = reads.query("SELECT * FROM economic_metadata", List.of(), 1).getFirst();
        long sequence = Math.addExact(num(meta,"global_sequence"),1);
        long seed = randomSeed(meta.get("seed"), intent.economicUpdateId(), version);
        var update = new Update(intent.economicUpdateId(),system,start,end,version,seed);
        DeferredSystem conditional = deferred.get(system);
        Calculation calculation = conditional == null ? null : Objects.requireNonNull(conditional.prepare().read(update, reads));
        return new Input(update, implementation, sequence, calculation);
    }
    private JournalEngine.Decision plan(Journal.Intent intent, Input input, Output output) {
        var update = input.update();
        String system = update.system();
        long start = update.startMillis(), end = update.endMillis(), sequence = input.sequence(), seed = update.randomSeed();
        int version = update.version();
        var fact = new EconomicRecord(system,start,end,version,sequence,seed,update.id(),output.payload());
        List<Journal.Record> records = new ArrayList<>(output.records().size() + 1);
        records.add(new Journal.Record(EconomicRecord.TYPE, 1, fact.encode()));
        records.addAll(output.records());
        return new JournalEngine.Commit(records, new Journal.Result(1,"ECONOMIC_UPDATED",str(sequence)), tx -> {
            output.effects().apply(tx);
            var journalRow = tx.query("SELECT transaction_id,sequence FROM journal_transactions WHERE world_id=? AND operation_id=?", List.of(store.snapshot().worldId().value().toString(),intent.operationId().value().toString()),1).getFirst();
            tx.execute("INSERT INTO economic_updates VALUES(?,?,?,?,?,?,?,?,?,?)", List.of(update.id().toString(),str(sequence),system,str(start),str(end),str(version),str(seed),output.payload(),journalRow.get("transaction_id"),journalRow.get("sequence")));
            tx.execute("UPDATE economic_metadata SET global_sequence=? WHERE singleton=1", List.of(str(sequence)));
            tx.execute("UPDATE economic_systems SET frontier_ms=?,last_sequence=? WHERE system_id=?",List.of(str(end),str(sequence),system));
        });
    }
    /** Pin algorithm identity and same-time dependency meaning together across restarts. */
    public static String signature(SystemDefinition definition,Version version) {
        return signature(definition, version, definition.originMillis());
    }

    public static String signature(SystemDefinition definition, Version version, long effectiveOrigin) {
        String text=definition.id()+"|"+definition.cadenceMillis()+"|"+effectiveOrigin+"|"+version.implementationId()+"|"+String.join("\n",new TreeSet<>(definition.dependsOn()));
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private SystemActorId actor(String system) { return new SystemActorId(stable("actor|"+store.snapshot().worldId().value()+"|"+system)); }
    private static UUID stable(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
    private static long randomSeed(String seed, UUID id, int version) {
        try { return java.nio.ByteBuffer.wrap(java.security.MessageDigest.getInstance("SHA-256").digest((seed+"|"+id+"|"+version).getBytes(StandardCharsets.UTF_8))).getLong(); }
        catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static long num(Map<String,String> row,String field) { return Long.parseLong(row.get(field)); }
    private static String str(long value) { return Long.toString(value); }
    private static StorageException failure(String message) { return new StorageException(StorageException.Kind.SCHEMA,message); }
}
