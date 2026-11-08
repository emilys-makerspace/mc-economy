package ca.emilybelnavis.mcmods.economy.debt;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.credit.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.loan.Loans;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static ca.emilybelnavis.mcmods.economy.debt.DebtRecords.*;

/** Reconstructs only histories whose original obligations never required a retrospective monetary default. */
public final class DebtLegacyReconciliation {
    public static final String FACT = "economy:a6/debt-legacy-checkpoint";
    private static final Journal.Kind OPERATION = new Journal.Kind("economy:debt", "economy:legacy-reconciliation");
    private DebtLegacyReconciliation() {}

    private record State(Kind kind, String cursor, String facility, long sequence, int position,
                         long frontier, long targetAt, long sourceAt, String sourceBody, boolean ready, long revision, boolean tracked) {}
    private record Requirement(UUID id, long dueAt, long original, long unmet) {}
    private record Snapshot(WorldId world, PlayerId owner, UUID facility, EconomyCurrency currency,
                            String status, long revision, long savedAt, List<Requirement> requirements) {}
    private record Source(long sequence, int position, String body, long effectiveAt, Snapshot snapshot) {}

    /** One source snapshot, report boundary or facility-selection step per committed operation. */
    public static boolean step(FoundationStore store) {
        var state = store.transaction(tx -> state(tx::query));
        if (state.ready()) return true;
        var world = store.snapshot().worldId();
        var operation = DebtLifecycle.stable("legacy|" + world.value() + "|" + state);
        var actor = new SystemActorId(DebtLifecycle.stable("legacy-actor|" + world.value()));
        var registration = new JournalEngine.Registration(1, (intent, reads) -> {
            if (!state.equals(state(reads))) throw new IllegalStateException("Debt legacy cursor changed");
            return prepare(reads, world, state);
        });
        var outcome = new JournalEngine(store, (a, k) -> true, Map.of(OPERATION, registration)).submit(
            new Journal.Intent(new OperationId(operation), new RequestId(operation), actor, OPERATION,
                new Journal.CanonicalRequest(1, Map.of("checkpoint", encode(state))), null, null));
        if (outcome.status() != Journal.Status.COMMITTED) throw new IllegalStateException("Debt legacy reconciliation page unavailable");
        return false;
    }

    private static JournalEngine.Commit prepare(JournalEngine.ReadView reads, WorldId world, State state) {
        if (state.facility().isEmpty()) return select(reads, world, state);
        var source = next(reads, state);
        if (state.tracked()) return verifyTracked(reads, world, state, source);
        long through = source == null ? Math.max(state.targetAt(), state.sourceAt()) : Math.subtractExact(source.effectiveAt(), 1);
        if (source != null && source.effectiveAt() < state.sourceAt()) fail("DEBT_LEGACY_SOURCE_TIME_CONFLICT");
        var dueReport = reads.query("SELECT body FROM debt_obligations WHERE world_id=? AND kind=? AND facility_id=? AND reported=0 AND due_at<=? ORDER BY due_at,obligation_id LIMIT 1",
            List.of(world.value().toString(), state.kind().name(), state.facility(), Long.toString(through - 2 * DAY)), 1);
        var dueDefault = reads.query("SELECT body FROM debt_obligations WHERE kind=? AND facility_id=? AND unmet_minor>0 AND due_at<=? ORDER BY due_at,obligation_id LIMIT 1",
            List.of(state.kind().name(), state.facility(), Long.toString(through - DEFAULT_DELAY)), 1);
        if (!dueDefault.isEmpty()) {
            var obligation = decodeObligation(dueDefault.getFirst().get("body"));
            long defaultAt = Math.addExact(obligation.dueAt(), DEFAULT_DELAY);
            if (dueReport.isEmpty() || Math.addExact(decodeObligation(dueReport.getFirst().get("body")).dueAt(), 2 * DAY) >= defaultAt)
                fail("DEBT_LEGACY_DEFAULT_REQUIRES_ACCOUNTING_RECONCILIATION");
        }
        if (!dueReport.isEmpty()) {
            var obligation = decodeObligation(dueReport.getFirst().get("body"));
            var decision = DebtLifecycle.reportDue(reads, state.kind(), obligation.id(), Math.addExact(obligation.dueAt(), 2 * DAY));
            if (!(decision instanceof JournalEngine.Commit commit)) throw new IllegalStateException("Legacy report transition unavailable");
            return append(commit, state);
        }
        if (source == null) return finish(reads, world, state);
        if (!source.snapshot().world().equals(world) || !source.snapshot().facility().toString().equals(state.facility())) fail("DEBT_LEGACY_SOURCE_BINDING");
        String coreTable = state.kind() == Kind.CARD ? "credit_facilities" : "loans";
        String coreId = state.kind() == Kind.CARD ? "facility_id" : "loan_id";
        var currentRows = reads.query("SELECT body FROM " + coreTable + " WHERE " + coreId + "=?", List.of(state.facility()), 1);
        if(currentRows.isEmpty())fail("DEBT_LEGACY_SOURCE_BINDING");
        var current = snapshot(state.kind(), currentRows.getFirst().get("body"));
        if(!source.snapshot().owner().equals(current.owner()) || source.snapshot().currency()!=current.currency())fail("DEBT_LEGACY_SOURCE_BINDING");
        if (source.snapshot().status().equals("DEFAULTED")) fail("DEBT_LEGACY_DEFAULT_REQUIRES_ACCOUNTING_RECONCILIATION");
        var records = new ArrayList<Journal.Record>();
        var changes = synchronize(reads, state.kind(), source.snapshot(), source.effectiveAt(), records);
        var changed = new State(state.kind(), state.cursor(), state.facility(), source.sequence(), source.position(), state.frontier(),
            state.targetAt(), source.effectiveAt(), source.body(), false, state.revision(), state.tracked());
        return checkpoint(changed, records, tx -> {
            for (var change : changes) DebtRecords.write(tx, change);
            for (var record : records) if (record.type().equals(REPORT_FACT)) DebtRecords.write(tx, decodeReport(record.payload()));
        });
    }

    private static JournalEngine.Commit verifyTracked(JournalEngine.ReadView reads, WorldId world, State state, Source source) {
        if (source == null) {
            String table = state.kind() == Kind.CARD ? "credit_facilities" : "loans";
            String id = state.kind() == Kind.CARD ? "facility_id" : "loan_id";
            var current = reads.query("SELECT body FROM " + table + " WHERE " + id + "=?", List.of(state.facility()), 1);
            if (current.isEmpty() || state.sourceBody().isEmpty() || !state.sourceBody().equals(current.getFirst().get("body"))) fail("DEBT_TRACKED_HISTORY_REQUIRES_RECONCILIATION");
            return checkpoint(new State(state.kind(), state.facility(), "", 0, -1, 0, 0, 0, "", false, state.revision(), false), List.of(), tx -> {});
        }
        var snapshot = source.snapshot();
        if (!snapshot.world().equals(world) || !snapshot.facility().toString().equals(state.facility())) fail("DEBT_LEGACY_SOURCE_BINDING");
        String table = state.kind() == Kind.CARD ? "credit_facilities" : "loans";
        String id = state.kind() == Kind.CARD ? "facility_id" : "loan_id";
        var currentRows = reads.query("SELECT body FROM " + table + " WHERE " + id + "=?", List.of(state.facility()), 1);
        if (currentRows.isEmpty()) fail("DEBT_LEGACY_SOURCE_BINDING");
        var current = snapshot(state.kind(), currentRows.getFirst().get("body"));
        if (!snapshot.owner().equals(current.owner()) || snapshot.currency() != current.currency()) fail("DEBT_LEGACY_SOURCE_BINDING");
        for (var original : snapshot.requirements()) if (original.original() > 0) {
            var obligation = DebtLifecycle.obligation(reads, state.kind(), original.id()).orElse(null);
            if (obligation == null || !obligation.reported() && Math.addExact(obligation.dueAt(), 2 * DAY) <= state.targetAt())
                fail("DEBT_TRACKED_HISTORY_REQUIRES_RECONCILIATION");
            if (!obligation.world().equals(world) || !obligation.owner().equals(snapshot.owner()) || !obligation.facility().equals(snapshot.facility())
                    || obligation.currency() != snapshot.currency() || obligation.dueAt() != original.dueAt() || obligation.originalMinor() != original.original())
                fail("DEBT_LEGACY_OBLIGATION_BINDING");
        }
        var next = new State(state.kind(), state.cursor(), state.facility(), source.sequence(), source.position(), state.frontier(), state.targetAt(),
            source.effectiveAt(), source.body(), false, state.revision(), true);
        return checkpoint(next, List.of(), tx -> {});
    }

    private static JournalEngine.Commit select(JournalEngine.ReadView reads, WorldId world, State state) {
        String table = state.kind() == Kind.CARD ? "credit_facilities" : "loans";
        String id = state.kind() == Kind.CARD ? "facility_id" : "loan_id";
        var rows = reads.query("SELECT " + id + " AS id FROM " + table + " WHERE " + id + ">? ORDER BY " + id + " LIMIT 1", List.of(state.cursor()), 1);
        if (rows.isEmpty()) {
            return checkpoint(new State(Kind.LOAN, "", "", 0, -1, 0, 0, 0, "", state.kind() == Kind.LOAN, state.revision(), false), List.of(), tx -> {});
        }
        String facility = rows.getFirst().get("id");
        boolean tracked = !reads.query("SELECT facility_id FROM debt_facility_states WHERE world_id=? AND kind=? AND facility_id=?",
            List.of(world.value().toString(), state.kind().name(), facility), 1).isEmpty();
        long frontier = number(reads.query("SELECT COALESCE(MAX(sequence),0) AS n FROM journal_transactions", List.of(), 1).getFirst().get("n"));
        var calendar = reads.query("SELECT last_effective_ms FROM financial_calendar WHERE singleton=1", List.of(), 1);
        long target = calendar.isEmpty() ? 0 : number(calendar.getFirst().get("last_effective_ms"));
        return checkpoint(new State(state.kind(), state.cursor(), facility, 0, -1, frontier, target, 0, "", false, state.revision(), tracked), List.of(), tx -> {});
    }

    private static Source next(JournalEngine.ReadView reads, State state) {
        String types = state.kind() == Kind.CARD ? "record_type IN('economy:a6/credit-event','economy:a6/credit-facility')" : "record_type IN('economy:a6/loan-event','economy:a6/loan')";
        String fact = state.kind() == Kind.CARD ? CreditFacilities.FACT : Loans.FACT;
        String identity = Base64.getUrlEncoder().withoutPadding().encodeToString(state.facility().getBytes(StandardCharsets.UTF_8));
        var rows = reads.query("SELECT sequence,position,record_version,payload FROM journal_records WHERE " + types + " AND record_type=? AND substr(payload,instr(payload,'|')+1,48)=? AND sequence<=? AND (sequence,position)>(?,?) ORDER BY sequence,position LIMIT 1",
            List.of(fact, identity, Long.toString(state.frontier()), Long.toString(state.sequence()), Integer.toString(state.position())), 1);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (!row.get("record_version").equals("1") && !(state.kind()==Kind.LOAN && row.get("record_version").equals("2"))) fail("DEBT_LEGACY_SOURCE_VERSION_UNAVAILABLE");
        var snapshot = snapshot(state.kind(), row.get("payload"));
        long at = snapshot.savedAt();
        var siblings = reads.query("SELECT record_type,record_version,payload FROM journal_records WHERE sequence=? ORDER BY position LIMIT 64", List.of(row.get("sequence")), 64);
        for (var sibling : siblings) {
            String type = sibling.get("record_type");
            if (type.equals(CreditAuditFacts.PAYMENT)) {
                if (!sibling.get("record_version").equals("1")) fail("DEBT_LEGACY_SOURCE_VERSION_UNAVAILABLE");
                var payment = CreditAuditFacts.decodePayment(sibling.get("payload"));
                if (payment.facility().toString().equals(state.facility())) at = Math.max(at, payment.effective());
            }
            if (type.equals(CreditFacilities.EVENT_FACT) || type.equals(Loans.EVENT_FACT)) {
                if (!sibling.get("record_version").equals("1")) fail("DEBT_LEGACY_SOURCE_VERSION_UNAVAILABLE");
                var fields = sibling.get("payload").split("\\|", -1);
                if (fields.length != 4) fail("DEBT_LEGACY_SOURCE_TIME_UNAVAILABLE");
                String selected = new String(Base64.getUrlDecoder().decode(fields[1]), StandardCharsets.UTF_8);
                if (selected.equals(state.facility())) at = Math.max(at, number(new String(Base64.getUrlDecoder().decode(fields[2]), StandardCharsets.UTF_8)));
            }
        }
        return new Source(number(row.get("sequence")), Integer.parseInt(row.get("position")), row.get("payload"), at, snapshot);
    }

    private static List<Obligation> synchronize(JournalEngine.ReadView reads, Kind kind, Snapshot snapshot, long at, List<Journal.Record> records) {
        var existing = reads.query("SELECT body FROM debt_obligations WHERE kind=? AND facility_id=? AND unmet_minor>0 ORDER BY due_at,obligation_id LIMIT 17", List.of(kind.name(), snapshot.facility().toString()), 17);
        if (existing.size() > 16 || snapshot.requirements().size() > 16) fail("DEBT_LEGACY_OBLIGATION_BOUND");
        var incoming = new LinkedHashMap<UUID, Requirement>();
        for (var requirement : snapshot.requirements()) incoming.put(requirement.id(), requirement);
        for (var row : existing) {
            var old = decodeObligation(row.get("body"));
            incoming.putIfAbsent(old.id(), new Requirement(old.id(), old.dueAt(), old.originalMinor(), 0));
        }
        var changes = new ArrayList<Obligation>();
        for (var requirement : incoming.values()) {
            if (requirement.original() == 0) continue;
            var old = DebtLifecycle.obligation(reads, kind, requirement.id()).orElse(null);
            if (old != null && (!old.facility().equals(snapshot.facility()) || !old.owner().equals(snapshot.owner())
                    || !old.world().equals(snapshot.world()) || old.currency() != snapshot.currency()
                    || old.originalMinor() != requirement.original() || old.dueAt() != requirement.dueAt())) fail("DEBT_LEGACY_OBLIGATION_BINDING");
            if (old != null && old.unmetMinor() == requirement.unmet()) continue;
            var next = new Obligation(snapshot.world(), requirement.id(), kind, snapshot.facility(), snapshot.owner(), snapshot.currency(),
                requirement.original(), requirement.unmet(), requirement.dueAt(), old != null && old.reported(), old != null && old.metAtReport(),
                old == null ? 1 : Math.addExact(old.revision(), 1), at);
            changes.add(next); records.add(DebtRecords.fact(next));
            if (old != null && old.reported() && !old.metAtReport() && old.unmetMinor() > 0 && next.unmetMinor() == 0) {
                UUID reportId = DebtLifecycle.stable("report|" + next.world().value() + "|" + kind + "|" + next.id());
                var prior = reads.query("SELECT body FROM debt_reports WHERE world_id=? AND report_id=? ORDER BY revision DESC LIMIT 1", List.of(next.world().value().toString(), reportId.toString()), 1);
                if (prior.isEmpty()) fail("DEBT_LEGACY_REPORT_SOURCE_UNAVAILABLE");
                var report = decodeReport(prior.getFirst().get("body"));
                var cure = new Report(next.world(), reportId, kind, next.facility(), next.owner(), next.id(), next.dueAt(), Math.addExact(next.dueAt(), 2 * DAY),
                    false, true, next.originalMinor(), 0, Math.addExact(report.revision(), 1), at);
                records.add(DebtRecords.fact(cure));
            }
        }
        return changes;
    }

    private static JournalEngine.Commit finish(JournalEngine.ReadView reads, WorldId world, State state) {
        String table = state.kind() == Kind.CARD ? "credit_facilities" : "loans";
        String id = state.kind() == Kind.CARD ? "facility_id" : "loan_id";
        var current = reads.query("SELECT body FROM " + table + " WHERE " + id + "=?", List.of(state.facility()), 1);
        if (current.isEmpty() || state.sourceBody().isEmpty() || !state.sourceBody().equals(current.getFirst().get("body"))) fail("DEBT_LEGACY_SOURCE_HISTORY_INCOMPLETE");
        var snapshot = snapshot(state.kind(), state.sourceBody());
        if (!snapshot.world().equals(world)) fail("DEBT_LEGACY_SOURCE_BINDING");
        String status = snapshot.status().equals("ACTIVE") ? snapshot.requirements().stream().anyMatch(r -> r.unmet() > 0 && r.dueAt() < state.targetAt()) ? "DELINQUENT" : "CURRENT" : snapshot.status();
        long at = Math.max(state.sourceAt(), state.targetAt());
        var stateFact = new Journal.Record(DebtLifecycle.STATE, 1, world.value() + "|" + state.kind() + "|" + state.facility() + "|" + snapshot.owner().value() + "|" + status + "|" + at + "|" + snapshot.revision());
        var next = new State(state.kind(), state.facility(), "", 0, -1, 0, 0, 0, "", false, state.revision(), false);
        return checkpoint(next, List.of(stateFact), tx -> {
            tx.execute("INSERT INTO debt_facility_states VALUES(?,?,?,?,?,?,?)", List.of(world.value().toString(), state.kind().name(), state.facility(), snapshot.owner().value().toString(), status, Long.toString(at), Long.toString(snapshot.revision())));
            for (var requirement : snapshot.requirements()) if (requirement.original() > 0 && Math.addExact(requirement.dueAt(), 1) > at)
                tx.execute("INSERT OR IGNORE INTO debt_due_queue VALUES(?,?,?,?)", List.of(world.value().toString(), state.kind().name(), requirement.id().toString(), Long.toString(Math.addExact(requirement.dueAt(), 1))));
            var oldest = snapshot.requirements().stream().filter(r -> r.unmet() > 0).mapToLong(Requirement::dueAt).min();
            if (snapshot.status().equals("ACTIVE") && oldest.isPresent()) tx.execute("INSERT OR REPLACE INTO debt_default_heads VALUES(?,?,?,?)", List.of(world.value().toString(), state.kind().name(), state.facility(), Long.toString(Math.addExact(oldest.getAsLong(), DEFAULT_DELAY))));
        });
    }

    private static JournalEngine.Commit append(JournalEngine.Commit base, State state) {
        return checkpoint(state, base.records(), base.projections());
    }

    private static JournalEngine.Commit checkpoint(State state, List<Journal.Record> supplied, JournalEngine.Projection mutation) {
        var advanced = new State(state.kind(), state.cursor(), state.facility(), state.sequence(), state.position(), state.frontier(), state.targetAt(), state.sourceAt(), state.sourceBody(), state.ready(), Math.addExact(state.revision(), 1), state.tracked());
        var records = new ArrayList<>(supplied);
        records.add(new Journal.Record(FACT, 1, encode(advanced)));
        if (records.size() > 64) fail("DEBT_LEGACY_RECORD_BOUND");
        return new JournalEngine.Commit(records, new Journal.Result(1, "DEBT_LEGACY_PAGE", state.ready() ? "READY" : state.facility()), tx -> {
            mutation.apply(tx);
            tx.execute("UPDATE debt_legacy_reconciliation SET kind=?,cursor=?,facility=?,sequence=?,position=?,frontier=?,target_at=?,source_at=?,source_body=?,ready=?,revision=?,tracked=? WHERE singleton=1",
                List.of(state.kind().name(), state.cursor(), state.facility(), Long.toString(state.sequence()), Integer.toString(state.position()), Long.toString(state.frontier()), Long.toString(state.targetAt()), Long.toString(state.sourceAt()), state.sourceBody(), state.ready() ? "1" : "0", Long.toString(advanced.revision()), state.tracked() ? "1" : "0"));
        });
    }

    private static State state(JournalEngine.ReadView reads) {
        var row = reads.query("SELECT * FROM debt_legacy_reconciliation WHERE singleton=1", List.of(), 1).getFirst();
        return new State(Kind.valueOf(row.get("kind")), row.get("cursor"), row.get("facility"), number(row.get("sequence")), Integer.parseInt(row.get("position")),
            number(row.get("frontier")), number(row.get("target_at")), number(row.get("source_at")), row.get("source_body"), row.get("ready").equals("1"), number(row.get("revision")), row.get("tracked").equals("1"));
    }

    private static Snapshot snapshot(Kind kind, String body) {
        if (kind == Kind.CARD) {
            var card = CreditFacilities.decodeSnapshot(body);
            return new Snapshot(card.world(), card.owner(), card.id(), card.currency(), card.status().name(), card.revision(), card.lastDay(),
                card.requirements().stream().map(r -> new Requirement(UUID.fromString(r.id()), r.dueAt(), r.originalMinor(), r.unmetMinor())).toList());
        }
        var loan = Loans.decode(body);
        return new Snapshot(loan.world(), loan.owner(), loan.id(), loan.currency(), loan.status().name(), loan.revision(), loan.servicing().lastAccrued(),
            loan.servicing().requirements().stream().map(r -> new Requirement(UUID.fromString(r.id()), r.dueAt(), r.originalMinor(), r.unmetMinor())).toList());
    }

    private static String encode(State state) {
        return String.join("|", state.kind().name(), state.cursor(), state.facility(), Long.toString(state.sequence()), Integer.toString(state.position()),
            Long.toString(state.frontier()), Long.toString(state.targetAt()), Long.toString(state.sourceAt()), hash(state.sourceBody()), state.ready() ? "1" : "0", Long.toString(state.revision()), state.tracked() ? "1" : "0");
    }

    public static RecordCatalog register(RecordCatalog catalog) {
        return catalog.with(new RecordCatalog.Key(FACT, 1), body -> {
            var fields = body.split("\\|", -1);
            if (fields.length != 12 || (!Set.of("0", "1").contains(fields[9]) || !Set.of("0", "1").contains(fields[11])) || !fields[8].matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Debt legacy checkpoint fields");
            Kind.valueOf(fields[0]);
            if(number(fields[10])<1)throw new IllegalArgumentException("Debt legacy checkpoint revision");
            if (!fields[1].isEmpty()) UUID.fromString(fields[1]);
            if (!fields[2].isEmpty()) UUID.fromString(fields[2]);
            long sequence = number(fields[3]); int position = Integer.parseInt(fields[4]);
            if (sequence < 0 || position < -1 || position > 63 || sequence == 0 && position != -1 || number(fields[5]) < sequence || number(fields[6]) < 0 || number(fields[7]) < 0)
                throw new IllegalArgumentException("Debt legacy checkpoint cursor");
        });
    }

    private static String hash(String body) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static long number(String text) {
        long value = Long.parseLong(text);
        if (!Long.toString(value).equals(text)) throw new IllegalArgumentException("Debt legacy number");
        return value;
    }
    private static void fail(String code) { throw new StorageException(StorageException.Kind.MIGRATION, code); }
}
