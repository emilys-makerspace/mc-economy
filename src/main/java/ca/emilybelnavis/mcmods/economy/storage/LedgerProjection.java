package ca.emilybelnavis.mcmods.economy.storage;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.journal.PostingBatch;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Worker-only materialization of immutable ledger posting facts. */
public final class LedgerProjection {
    static final String CREATE_TABLE = """
            CREATE TABLE ledger_balances (
              account_id TEXT NOT NULL,
              currency TEXT NOT NULL,
              minor_units INTEGER NOT NULL CHECK(typeof(minor_units)='integer' AND minor_units<>0),
              PRIMARY KEY(account_id,currency)
            )
            """.strip();

    private LedgerProjection() {}

    static void validateSchema(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT sql FROM sqlite_schema WHERE type='table' AND name='ledger_balances'");
             var row = query.executeQuery()) {
            if (!row.next() || !CREATE_TABLE.equals(row.getString(1))) {
                throw new StorageException(StorageException.Kind.SCHEMA, "Missing or changed ledger balance schema");
            }
        }
    }

    private record Key(UUID account, EconomyCurrency currency) {
        List<String> parameters() {
            return List.of(account.toString(), currency.name());
        }
    }

    private record Posting(Key key, long units) {}

    public static void write(FoundationStore.Transaction tx, UUID account, EconomyCurrency currency, long units) {
        if (units == 0) {
            tx.execute("DELETE FROM ledger_balances WHERE account_id=? AND currency=?",
                    List.of(account.toString(), currency.name()));
            return;
        }
        tx.execute("""
                INSERT INTO ledger_balances(account_id,currency,minor_units) VALUES(?,?,?)
                ON CONFLICT(account_id,currency) DO UPDATE SET minor_units=excluded.minor_units
                """, List.of(account.toString(), currency.name(), Long.toString(units)));
    }

    public static void validatePosting(String payload) {
        decode(payload, 1);
    }

    public static void validatePostingV2(String payload) { decode(payload, 2); }

    public static void validatePostingV3(String payload) { decode(payload, 3); }

    public static void validatePostingV4(String payload) { decode(payload, 4); }

    private static Posting decode(String payload, int version) {
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 5) throw new IllegalArgumentException("Invalid posting fields");
        var account = UUID.fromString(fields[0]);
        var role = PostingBatch.Role.valueOf(fields[1]);
        var currency = EconomyCurrency.valueOf(fields[2]);
        long units = Long.parseLong(fields[3]);
        var authority = PostingBatch.Authority.valueOf(fields[4]);
        if (version < 4 && role == PostingBatch.Role.NPC_BRIDGE) throw new IllegalArgumentException("Posting version");
        if (version == 4 && (role == PostingBatch.Role.FEE || authority == PostingBatch.Authority.CAPITAL_ISSUE
                || !payload.equals(account+"|"+role+"|"+currency+"|"+units+"|"+authority))) throw new IllegalArgumentException("NPC posting format");
        if (version < 3 && (role == PostingBatch.Role.INSTITUTION_CASH || role == PostingBatch.Role.INSTITUTION_CLAIM || authority == PostingBatch.Authority.CAPITAL_ISSUE)) throw new IllegalArgumentException("Posting version");
        if (units == 0 || version == 1 && (currency == EconomyCurrency.CHF || units % currency.minorUnitsPerMajor() != 0)) {
            throw new IllegalArgumentException("Invalid posting amount");
        }
        return new Posting(new Key(account, currency), units);
    }

    /** Replace the projection atomically by folding transactions in sequence order. */
    public static void rebuild(FoundationStore store) {
        store.transaction(tx -> {
            tx.execute("DELETE FROM ledger_balances", List.of());
            long sequence = 0;
            int position = -1;
            Map<Key, Long> deltas = new LinkedHashMap<>();
            while (true) {
                var rows = tx.query("""
                        SELECT sequence,position,record_version,payload FROM journal_records
                        WHERE record_type='economy:posting' AND (sequence,position)>(?,?)
                        ORDER BY sequence,position LIMIT 64
                        """, List.of(Long.toString(sequence), Integer.toString(position)), 64);
                if (rows.isEmpty()) break;
                for (var row : rows) {
                    long nextSequence = Long.parseLong(row.get("sequence"));
                    if (nextSequence != sequence) {
                        apply(tx, deltas);
                        deltas.clear();
                        sequence = nextSequence;
                    }
                    position = Integer.parseInt(row.get("position"));
                    int version = Integer.parseInt(row.get("record_version"));
                    if (version != 1 && version != 2 && version != 3 && version != 4) {
                        throw new StorageException(StorageException.Kind.SCHEMA, "Unsupported ledger posting version");
                    }
                    var posting = decode(row.get("payload"), version);
                    deltas.merge(posting.key(), posting.units(), Math::addExact);
                }
            }
            apply(tx, deltas);
            return null;
        });
    }

    private static void apply(FoundationStore.Transaction tx, Map<Key, Long> deltas) {
        deltas.forEach((key, delta) -> {
            var rows = tx.query("SELECT minor_units FROM ledger_balances WHERE account_id=? AND currency=?",
                    key.parameters(), 1);
            long previous = rows.isEmpty() ? 0 : Long.parseLong(rows.getFirst().get("minor_units"));
            write(tx, key.account(), key.currency(), Math.addExact(previous, delta));
        });
    }
}
