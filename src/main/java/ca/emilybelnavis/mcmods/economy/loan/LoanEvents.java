package ca.emilybelnavis.mcmods.economy.loan;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.storage.StorageException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Trusted worker source of original loan obligations and their ordered satisfaction snapshots. */
public final class LoanEvents {
    private static final String TYPES = "record_type IN('economy:a6/loan','economy:a6/loan-installment')";
    public static final String INDEX = "CREATE INDEX loan_event_feed ON journal_records(sequence,position) WHERE " + TYPES;

    private LoanEvents() {}

    public record Cursor(WorldId world, long sequence, int position, long frontier) {
        public Cursor {
            Objects.requireNonNull(world);
            if (sequence < 0 || frontier < sequence || position < -1 || position > 63 || sequence == 0 && position != -1) {
                throw new IllegalArgumentException("Loan event cursor");
            }
        }
    }

    public record Envelope(long sequence, int position, WorldId world, UUID transaction, UUID operation,
                           Instant auditedAt, UUID economicUpdate, UUID correctionOf) {}
    public sealed interface Payload permits ContractChanged, InstallmentClosed {}
    public record ContractChanged(Loans.Contract snapshot) implements Payload {}
    public record InstallmentClosed(Loans.InstallmentSnapshot snapshot) implements Payload {}
    public record Event(Envelope envelope, int recordVersion, Payload payload) {}
    public record Page(List<Event> events, Cursor checkpoint, Cursor nextCursor) {
        public Page { events = List.copyOf(events); }
    }

    public static Cursor open(JournalEngine.ReadView reads) {
        return open(reads, null);
    }

    public static Cursor open(JournalEngine.ReadView reads, Cursor checkpoint) {
        var world = world(reads);
        long frontier = LoanDiagnostics.frontier(reads);
        if (checkpoint != null && (!checkpoint.world().equals(world) || checkpoint.frontier() > frontier)) {
            throw new IllegalArgumentException("Loan event world/frontier");
        }
        return new Cursor(world, checkpoint == null ? 0 : checkpoint.sequence(),
            checkpoint == null ? -1 : checkpoint.position(), frontier);
    }

    public static Page read(JournalEngine.ReadView reads, Cursor cursor, int limit) {
        Objects.requireNonNull(cursor);
        if (limit < 1 || limit > 16) throw new IllegalArgumentException("Loan event page bounds");
        if (!cursor.world().equals(world(reads)) || cursor.frontier() > LoanDiagnostics.frontier(reads)) {
            throw new IllegalArgumentException("Loan event world/frontier");
        }
        var rows = reads.query(
            "SELECT r.sequence,r.position,r.record_type,r.record_version,r.payload,t.world_id,t.transaction_id,t.operation_id,t.audit_utc,t.economic_update_id,t.correction_of FROM journal_records r JOIN journal_transactions t ON t.sequence=r.sequence WHERE r."
                + TYPES + " AND r.sequence<=? AND (r.sequence,r.position)>(?,?) ORDER BY r.sequence,r.position LIMIT ?",
            List.of(Long.toString(cursor.frontier()), Long.toString(cursor.sequence()),
                Integer.toString(cursor.position()), Integer.toString(limit + 1)), limit + 1);
        var events = rows.stream().limit(limit).map(LoanEvents::event).toList();
        for (var event : events) {
            if (!event.envelope().world().equals(cursor.world())) throw new IllegalArgumentException("Loan envelope world");
        }
        Cursor end = cursor;
        if (!events.isEmpty()) {
            var last = events.getLast().envelope();
            end = new Cursor(cursor.world(), last.sequence(), last.position(), cursor.frontier());
        }
        return new Page(events, end, rows.size() > limit ? end : null);
    }

    private static Event event(Map<String, String> row) {
        int version = Integer.parseInt(row.get("record_version"));
        if (version != 1 && !(version == 2 && row.get("record_type").equals(Loans.FACT))) throw new IllegalArgumentException("Unsupported loan source version");
        var world = new WorldId(UUID.fromString(row.get("world_id")));
        String body = row.get("payload");
        Payload payload;
        WorldId sourceWorld;
        switch (row.get("record_type")) {
            case Loans.FACT -> {
                Loans.validateFact(body,version);
                var snapshot = Loans.decode(body,version);
                payload = new ContractChanged(snapshot);
                sourceWorld = snapshot.world();
            }
            case Loans.INSTALLMENT_FACT -> {
                var snapshot = Loans.decodeInstallment(body);
                payload = new InstallmentClosed(snapshot);
                sourceWorld = snapshot.world();
            }
            default -> throw new IllegalArgumentException("Unsupported loan source");
        }
        if (!sourceWorld.equals(world)) throw new IllegalArgumentException("Loan source world binding");
        var envelope = new Envelope(Long.parseLong(row.get("sequence")), Integer.parseInt(row.get("position")), world,
            UUID.fromString(row.get("transaction_id")), UUID.fromString(row.get("operation_id")), Instant.parse(row.get("audit_utc")),
            nullable(row.get("economic_update_id")), nullable(row.get("correction_of")));
        return new Event(envelope, version, payload);
    }

    private static UUID nullable(String value) {
        return value == null || value.isEmpty() ? null : UUID.fromString(value);
    }

    private static WorldId world(JournalEngine.ReadView reads) {
        return new WorldId(UUID.fromString(reads.query("SELECT world_id FROM foundation_metadata", List.of(), 1).getFirst().get("world_id")));
    }

    public static void validateSchema(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name='loan_event_feed'");
             var rows = query.executeQuery()) {
            if (!rows.next() || !INDEX.equals(rows.getString(1))) {
                throw new StorageException(StorageException.Kind.SCHEMA, "Missing loan event feed index");
            }
        }
    }
}
