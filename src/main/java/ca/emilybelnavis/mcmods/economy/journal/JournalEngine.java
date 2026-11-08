package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.Journal.Record;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.time.Instant;
import java.util.*;
import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;

/** Worker-confined durable mechanism. Register trusted handlers, never client-provided SQL/records. */
public final class JournalEngine {
    @FunctionalInterface public interface AccessPolicy { boolean allows(ActorId actor, Kind kind); }
    @FunctionalInterface public interface ReadView { List<Map<String, String>> query(String sql, List<String> parameters, int maxRows); }
    @FunctionalInterface public interface Projection { void apply(FoundationStore.Transaction transaction) throws Exception; }
    @FunctionalInterface public interface Handler { Decision prepare(Intent intent, ReadView reads) throws Exception; }
    public sealed interface Decision permits Commit, Reject, Retry, Decline {}
    public record Commit(List<Record> records, Result result, Projection projections) implements Decision {
        public Commit {
            records = List.copyOf(records); Objects.requireNonNull(result); Objects.requireNonNull(projections);
            if (records.isEmpty() || records.size() > 64) throw new IllegalArgumentException("Record count");
            long bytes = records.stream().mapToLong(r -> r.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum();
            if (bytes > 131072) throw new IllegalArgumentException("Transaction record payload bounds");
        }
    }
    public record Decline(Result result) implements Decision { public Decline { Objects.requireNonNull(result); } }
    public record Retry(Result result) implements Decision { public Retry { Objects.requireNonNull(result); } }
    public record Reject(Result result, Projection projection) implements Decision {
        public Reject(Result result) { this(result, tx -> {}); }
        public Reject { Objects.requireNonNull(result); Objects.requireNonNull(projection); }
    }
    public record Registration(int canonicalVersion, Handler handler) { public Registration { if (canonicalVersion < 1 || canonicalVersion > 1024) throw new IllegalArgumentException("Canonical version"); Objects.requireNonNull(handler); } }
    private final FoundationStore store;
    private final AccessPolicy access;
    private final Map<Kind, Registration> handlers;

    public JournalEngine(FoundationStore store, AccessPolicy access, Map<Kind, Registration> handlers) {
        this.store = Objects.requireNonNull(store); this.access = Objects.requireNonNull(access); this.handlers = Map.copyOf(handlers);
    }
    record Attempt(Outcome outcome, boolean replay) {}

    public Outcome submit(Intent intent) {
        return submitAttempt(intent).outcome();
    }

    Attempt submitAttempt(Intent intent) {
        boolean[] replay = {false};
        Outcome outcome = resolve(intent, replay);
        return new Attempt(outcome, replay[0]);
    }

    private Outcome resolve(Intent intent, boolean[] replay) {
        if (!access.allows(intent.actor(), intent.kind())) return nonterminal(intent, Status.NOT_ACCEPTED, "ACCESS_DENIED");
        try { return store.transaction(tx -> process(tx, intent, replay)); }
        catch (StorageException error) {
            if (error.kind() != StorageException.Kind.UNKNOWN_COMMIT && error.kind() != StorageException.Kind.UNSAFE_CONNECTION) throw error;
            // Never execute a new write while resolving uncertainty. Only reopen and look up the original ID.
            store.recover();
            return store.transaction(tx -> {
                if (!access.allows(intent.actor(), intent.kind())) return nonterminal(intent, Status.UNKNOWN, "OUTCOME_UNDISCLOSED");
                var rows = tx.query("SELECT * FROM operation_outcomes WHERE world_id=? AND operation_id=?",
                        List.of(world(), intent.operationId().value().toString()), 1);
                if (rows.isEmpty()) return nonterminal(intent, Status.NOT_ACCEPTED, "ABSENT_RETRY_SAME_ID");
                var row = rows.getFirst();
                if (!row.get("actor").equals(actorKey(intent.actor())) || !row.get("service").equals(intent.kind().service())
                        || !row.get("operation_type").equals(intent.kind().operationType())) return nonterminal(intent, Status.UNKNOWN, "OUTCOME_UNDISCLOSED");
                if (!row.get("canonical_request").equals(intent.canonical())) return nonterminal(intent, Status.NOT_ACCEPTED, "OPERATION_CONFLICT");
                return outcome(row);
            });
        }
    }
    public Optional<Outcome> lookup(Intent intent) {
        if (!access.allows(intent.actor(), intent.kind())) return Optional.empty();
        return store.transaction(tx -> {
            var rows = tx.query("SELECT * FROM operation_outcomes WHERE world_id=? AND operation_id=? AND actor=? AND service=? AND operation_type=?",
                    List.of(world(), intent.operationId().value().toString(), actorKey(intent.actor()), intent.kind().service(), intent.kind().operationType()), 1);
            if (rows.isEmpty()) return Optional.empty();
            if (!rows.getFirst().get("canonical_request").equals(intent.canonical()))
                return Optional.of(nonterminal(intent, Status.NOT_ACCEPTED, "OPERATION_CONFLICT"));
            return Optional.of(outcome(rows.getFirst()));
        });
    }
    private Outcome process(FoundationStore.Transaction tx, Intent intent, boolean[] replay) throws Exception {
        var existing = tx.query("SELECT * FROM operation_outcomes WHERE world_id=? AND operation_id=?",
                List.of(world(), intent.operationId().value().toString()), 1);
        if (!existing.isEmpty()) {
            var row = existing.getFirst();
            if (!row.get("actor").equals(actorKey(intent.actor()))) return nonterminal(intent, Status.NOT_ACCEPTED, "ACCESS_DENIED");
            if (!row.get("service").equals(intent.kind().service()) || !row.get("operation_type").equals(intent.kind().operationType())
                    || !row.get("canonical_request").equals(intent.canonical())) return nonterminal(intent, Status.NOT_ACCEPTED, "OPERATION_CONFLICT");
            replay[0] = true;
            return outcome(row);
        }
        var retry = tx.query("SELECT * FROM journal_retry_intents WHERE operation_id=?",
                List.of(intent.operationId().value().toString()), 1);
        if (!retry.isEmpty()) {
            var row = retry.getFirst();
            if (!row.get("actor").equals(actorKey(intent.actor()))) return nonterminal(intent, Status.NOT_ACCEPTED, "ACCESS_DENIED");
            if (!row.get("service").equals(intent.kind().service())
                    || !row.get("operation_type").equals(intent.kind().operationType())
                    || !row.get("canonical_request").equals(intent.canonical()))
                return nonterminal(intent, Status.NOT_ACCEPTED, "OPERATION_CONFLICT");
        }
        Registration registration = handlers.get(intent.kind());
        if (registration == null || registration.canonicalVersion() != intent.request().version())
            return nonterminal(intent, Status.NOT_ACCEPTED, "UNSUPPORTED_OPERATION");
        if (intent.correctionOf() != null && tx.query("SELECT transaction_id FROM journal_transactions WHERE world_id=? AND transaction_id=?",
                List.of(world(), intent.correctionOf().value().toString()), 1).isEmpty())
            return nonterminal(intent, Status.NOT_ACCEPTED, "INVALID_CORRECTION");
        ReadView reads = (sql, parameters, maxRows) -> {
            if (!sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT ")) throw new IllegalArgumentException("Preparation is read-only");
            return tx.query(sql, parameters, maxRows);
        };
        Decision decision = Objects.requireNonNull(registration.handler().prepare(intent, reads));
        if (decision instanceof Commit commit) decision = Objects.requireNonNull(store.decorateCommit(intent, reads, commit));
        if (decision instanceof Decline declined)
            return new Outcome(intent.operationId(), intent.requestId(), Status.NOT_ACCEPTED, null, null, declined.result());
        if (decision instanceof Retry deferred) {
            if (retry.isEmpty()) tx.execute("INSERT INTO journal_retry_intents VALUES(?,?,?,?,?)",
                    List.of(intent.operationId().value().toString(), actorKey(intent.actor()),
                            intent.kind().service(), intent.kind().operationType(), intent.canonical()));
            return new Outcome(intent.operationId(), intent.requestId(), Status.NOT_ACCEPTED, null, null, deferred.result());
        }
        if (!retry.isEmpty()) tx.execute("DELETE FROM journal_retry_intents WHERE operation_id=?",
                List.of(intent.operationId().value().toString()));
        String audit = Instant.now().toString();
        TransactionId transactionId = null; JournalSequence sequence = null; Result result;
        Status status;
        if (decision instanceof Commit commit) {
            for (var record : commit.records()) store.recordCatalog().validate(record.type(), record.version(), record.payload());
            transactionId = new TransactionId(UUID.randomUUID());
            tx.execute("INSERT INTO journal_transactions(transaction_id,world_id,operation_id,envelope_version,record_count,audit_utc,economic_update_id,correction_of) VALUES(?,?,?,?,?,?,?,?)",
                    Arrays.asList(transactionId.value().toString(), world(), intent.operationId().value().toString(), "1", Integer.toString(commit.records().size()), audit,
                            intent.economicUpdateId() == null ? null : intent.economicUpdateId().toString(), intent.correctionOf() == null ? null : intent.correctionOf().value().toString()));
            sequence = new JournalSequence(Long.parseLong(tx.query("SELECT last_insert_rowid() AS sequence", List.of(), 1).getFirst().get("sequence")));
            for (int position = 0; position < commit.records().size(); position++) {
                Record record = commit.records().get(position);
                tx.execute("INSERT INTO journal_records(sequence,position,record_type,record_version,payload) VALUES(?,?,?,?,?)",
                        List.of(Long.toString(sequence.value()), Integer.toString(position), record.type(), Integer.toString(record.version()), record.payload()));
            }
            commit.projections().apply(tx);
            result = commit.result(); status = Status.COMMITTED;
        } else {
            var rejection = (Reject) decision;
            rejection.projection().apply(tx);
            result = rejection.result();
            status = Status.DURABLY_REJECTED;
        }
        tx.execute("INSERT INTO operation_outcomes(world_id,operation_id,actor,service,operation_type,canonical_version,canonical_request,request_id,status,result_version,result_code,result_payload,transaction_id,sequence,audit_utc) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                Arrays.asList(world(), intent.operationId().value().toString(), actorKey(intent.actor()), intent.kind().service(), intent.kind().operationType(),
                        Integer.toString(intent.request().version()), intent.canonical(), intent.requestId().value().toString(), status.name(),
                        Integer.toString(result.version()), result.code(), result.payload(), transactionId == null ? null : transactionId.value().toString(),
                        sequence == null ? null : Long.toString(sequence.value()), audit));
        if (sequence != null && store.snapshot().schemaVersion() >= 53)
            ca.emilybelnavis.mcmods.economy.storage.OwnerHistorySchema.committed(tx, sequence.value());
        return new Outcome(intent.operationId(), intent.requestId(), status, transactionId, sequence, result);
    }
    /** Authorized internal replay boundary only. No side effects, deliveries, or cache publication. */
    public List<Envelope> readAfter(long exclusiveSequence, int limit) {
        if (exclusiveSequence < 0 || limit < 1 || limit > 64) throw new IllegalArgumentException("Journal page bounds");
        return store.transaction(tx -> {
            var sequences = tx.query("SELECT sequence FROM journal_transactions WHERE world_id=? AND sequence>? ORDER BY sequence LIMIT ?",
                    List.of(world(), Long.toString(exclusiveSequence), Integer.toString(limit)), limit);
            List<Envelope> result = new ArrayList<>();
            long bytes = 0;
            for (var selected : sequences) {
                var row = tx.query("SELECT t.*,o.actor,o.service,o.operation_type,o.request_id,o.canonical_version,o.result_version,o.result_code,o.result_payload FROM journal_transactions t JOIN operation_outcomes o ON o.world_id=t.world_id AND o.operation_id=t.operation_id WHERE t.world_id=? AND t.sequence=?",
                        List.of(world(), selected.get("sequence")), 1).getFirst();
                for (String value : row.values()) if (value != null) bytes += value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                var recordRows = tx.query("SELECT position,record_type,record_version,payload FROM journal_records WHERE sequence=? ORDER BY position", List.of(row.get("sequence")), 64);
                List<Entry> records = new ArrayList<>();
                for (var record : recordRows) {
                    if (Integer.parseInt(record.get("position")) != records.size()) throw new StorageException(StorageException.Kind.CORRUPTION_IO, "Journal record ordering damaged");
                    records.add(new Entry(records.size(), record.get("record_type"), Integer.parseInt(record.get("record_version")), record.get("payload")));
                    for (String value : record.values()) if (value != null)
                        bytes += value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                }
                if (records.size() != Integer.parseInt(row.get("record_count"))) throw new StorageException(StorageException.Kind.CORRUPTION_IO, "Incomplete journal envelope");
                // Whole envelopes only; callers continue after the last returned sequence.
                if (bytes > 1_048_576 && !result.isEmpty()) break;
                result.add(new Envelope(store.snapshot().worldId(), new TransactionId(UUID.fromString(row.get("transaction_id"))),
                        new OperationId(UUID.fromString(row.get("operation_id"))), new RequestId(UUID.fromString(row.get("request_id"))), actor(row.get("actor")),
                        new Kind(row.get("service"), row.get("operation_type")), new JournalSequence(Long.parseLong(row.get("sequence"))),
                        new AuditInstant(Instant.parse(row.get("audit_utc"))), Integer.parseInt(row.get("envelope_version")), Integer.parseInt(row.get("canonical_version")), records,
                        storedResult(row), row.get("economic_update_id") == null ? null : UUID.fromString(row.get("economic_update_id")),
                        row.get("correction_of") == null ? null : new TransactionId(UUID.fromString(row.get("correction_of")))));
            }
            return List.copyOf(result);
        });
    }
    private String world() { return store.snapshot().worldId().value().toString(); }
    private static Outcome outcome(Map<String, String> row) {
        return new Outcome(new OperationId(UUID.fromString(row.get("operation_id"))), new RequestId(UUID.fromString(row.get("request_id"))), Status.valueOf(row.get("status")),
                row.get("transaction_id") == null ? null : new TransactionId(UUID.fromString(row.get("transaction_id"))),
                row.get("sequence") == null ? null : new JournalSequence(Long.parseLong(row.get("sequence"))), storedResult(row));
    }
    private static Result storedResult(Map<String, String> row) { return new Result(Integer.parseInt(row.get("result_version")), row.get("result_code"), row.get("result_payload")); }
}
