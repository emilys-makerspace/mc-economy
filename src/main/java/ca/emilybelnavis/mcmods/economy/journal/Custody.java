package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.trade.custody.NpcDeliveries;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;

/** Worker-confined custody protocol. Minecraft effects are applied by trusted server adapters. */
public final class Custody {
    public static final String FACT = "economy:custody";
    public static final int MAX_PAGE_SIZE = 63;
    private static final SystemActorId RECOVERY = new SystemActorId(
            UUID.fromString("9f338063-8a19-451c-b508-cf2c4dfe327a"));

    // Startup is gated against customer mutations. Losing this cursor on a crash safely rescans facts.
    private static final Map<FoundationStore,String> RECONCILIATION_CURSORS=Collections.synchronizedMap(new WeakHashMap<>());

    public enum State { RESERVED, WITNESSED, COMMITTED, DELIVERING, ACKNOWLEDGED, CLAIM, QUARANTINED, DELIVERED, COMPENSATED }
    public enum Action {
        RESERVE, RESERVATION_SAVED, COMMIT, BEGIN_DELIVERY, ACKNOWLEDGE, DELIVERY_SAVED,
        CLAIM, COLLECT, QUARANTINE, CLOSE_DELIVERED, REDELIVER, COMPENSATE;
        public Kind kind() { return new Kind("economy:custody", "economy:" + name().toLowerCase(Locale.ROOT)); }
    }
    public enum EvidenceKind { UNKNOWN, RESERVATION_SAVED, DELIVERY_SAVED, UNDELIVERED, DELIVERED, LOST }

    /** Serialized exact stack components/counts and locations, supplied by a trusted adapter. */
    public record Terms(ActorId owner, String items, String source, String destination, String financialIntent) {
        public Terms {
            Objects.requireNonNull(owner);
            bounded(items, 4096); bounded(source, 256); bounded(destination, 256); bounded(financialIntent, 2048);
            financialOperation(financialIntent);
        }
    }

    /** References an externally observable witness. Ledger acknowledgment is never a save witness. */
    public record Evidence(EvidenceKind kind, String reference, String location, String saveToken) {
        public Evidence {
            Objects.requireNonNull(kind);
            bounded(reference, 256); bounded(location, 256); bounded(saveToken, 256);
        }
        public static Evidence unknown() { return new Evidence(EvidenceKind.UNKNOWN, "unknown", "unknown", "unknown"); }
    }

    public record Case(UUID id, Terms terms, State state, UUID reservationOperation, UUID financialOperation,
                       UUID deliveryAttempt, String reservationWitness, String deliveryWitness,
                       String acknowledgment, Evidence evidence, String reason, long revision, UUID compensationOperation) {}
    public record Page(List<Case> cases, UUID nextCursor) {
        public Page { cases = List.copyOf(cases); }
    }

    /** Live access, operator permission and final capacity admission belong to trusted server policy. */
    @FunctionalInterface public interface Authority {
        boolean allows(Action action, Intent intent, Case current, JournalEngine.ReadView reads);
    }
    @FunctionalInterface public interface EvidenceVerifier {
        boolean verifies(Case current, Evidence evidence, JournalEngine.ReadView reads);
    }
    /** Composes frozen financial intent into this custody operation's transaction. */
    @FunctionalInterface public interface Financial {
        Decision prepare(Intent intent, Case current, JournalEngine.ReadView reads) throws Exception;
    }

    @FunctionalInterface public interface Capacity {
        boolean fits(Intent intent, Case current, JournalEngine.ReadView reads);
    }

    private Custody() {}

    public static String financialIntent(Intent original) {
        return original.operationId().value() + "\n" + actorKey(original.actor()) + "\n"
                + original.kind().service() + "\n" + original.kind().operationType() + "\n" + original.canonical();
    }

    public static Financial financial(Intent original, Registration registration) {
        var frozen = financialIntent(original);
        return (intent, current, reads) -> {
            if (!current.terms().financialIntent().equals(frozen)
                    || !intent.operationId().equals(original.operationId())) return reject("CUSTODY_FINANCIAL_INTENT");
            return registration.handler().prepare(original, reads);
        };
    }

    private static UUID financialOperation(String intent) {
        int separator = intent.indexOf('\n');
        if (separator != 36 || intent.split("\n", 5).length != 5) throw new IllegalArgumentException("Financial intent identity");
        var fields = intent.split("\n", 5);
        actor(fields[1]);
        new Kind(fields[2], fields[3]);
        if (fields[4].isBlank()) throw new IllegalArgumentException("Financial canonical intent");
        return UUID.fromString(fields[0]);
    }

    public static CanonicalRequest reserveRequest(Terms terms) {
        return new CanonicalRequest(1, Map.of("owner", actorKey(terms.owner()), "items", terms.items(),
                "source", terms.source(), "destination", terms.destination(), "financial", terms.financialIntent()));
    }

    public static CanonicalRequest request(UUID custody, UUID attempt, Evidence evidence, String reason) {
        Objects.requireNonNull(custody); Objects.requireNonNull(evidence);
        bounded(reason, 1024);
        return new CanonicalRequest(1, Map.of("custody", custody.toString(), "attempt", attempt == null ? "" : attempt.toString(),
                "evidence", encodeEvidence(evidence), "reason", reason));
    }

    public static Registration registration(Action action, Authority authority, EvidenceVerifier verifier, Financial financial) {
        return registration(action, authority, verifier, financial, (intent, current, reads) -> false);
    }

    public static Registration registration(Action action, Authority authority, EvidenceVerifier verifier,
                                              Financial financial, Capacity capacity) {
        Objects.requireNonNull(action); Objects.requireNonNull(authority); Objects.requireNonNull(verifier);
        Objects.requireNonNull(capacity);
        return new Registration(1, (intent, reads) -> prepare(action, authority, verifier, financial, capacity, intent, reads));
    }

    private static Decision prepare(Action action, Authority authority, EvidenceVerifier verifier, Financial financial,
                                    Capacity capacity, Intent intent, ReadView reads) throws Exception {
        if (!intent.kind().equals(action.kind())) return reject("CUSTODY_REQUEST");
        if (action == Action.RESERVE) {
            Terms terms;
            try {
                var f = intent.request().fields();
                terms = new Terms(actor(f.get("owner")), f.get("items"), f.get("source"), f.get("destination"), f.get("financial"));
                if (!reserveRequest(terms).equals(intent.request())) return reject("CUSTODY_REQUEST");
            } catch (RuntimeException malformed) { return reject("CUSTODY_REQUEST"); }
            var current = new Case(intent.operationId().value(), terms, State.RESERVED, intent.operationId().value(),
                    null, null, null, null, null, Evidence.unknown(), "reserved", 1, null);
            if (!authority.allows(action, intent, current, reads)) return decline("CUSTODY_ACCESS");
            return commit(action, current, null, true);
        }
        UUID id; UUID attempt; Evidence evidence; String reason;
        try {
            var fields = intent.request().fields();
            id = UUID.fromString(fields.get("custody"));
            attempt = fields.get("attempt").isEmpty() ? null : UUID.fromString(fields.get("attempt"));
            evidence = decodeEvidence(fields.get("evidence")); reason = fields.get("reason");
            if (!request(id, attempt, evidence, reason).equals(intent.request())) return reject("CUSTODY_REQUEST");
        } catch (RuntimeException malformed) { return reject("CUSTODY_REQUEST"); }
        var current = raw(reads, id).orElse(null);
        if (current == null || !authority.allows(action, intent, current, reads)) return decline("CUSTODY_ACCESS");
        if (terminal(current.state()) && action != Action.QUARANTINE) return reject("CUSTODY_TERMINAL");
        var state = current.state();
        var next = state;
        UUID financialOperation = current.financialOperation();
        UUID compensationOperation = current.compensationOperation();
        UUID deliveryAttempt = current.deliveryAttempt();
        String reservationWitness = current.reservationWitness();
        String deliveryWitness = current.deliveryWitness();
        String acknowledgment = current.acknowledgment();
        Commit composed = null;
        switch (action) {
            case RESERVATION_SAVED -> {
                if (state != State.RESERVED) return reject("CUSTODY_STATE");
                if (!proof(current, evidence, EvidenceKind.RESERVATION_SAVED, verifier, reads)) return decline("CUSTODY_EVIDENCE");
                reservationWitness = encodeEvidence(evidence); next = State.WITNESSED;
            }
            case COMMIT -> {
                if (state != State.WITNESSED) return reject("CUSTODY_STATE");
                if (!capacity.fits(intent, current, reads)) return decline("CUSTODY_CAPACITY");
                if (financial == null) return decline("CUSTODY_FINANCIAL_HANDLER");
                if (!intent.operationId().value().equals(financialOperation(current.terms().financialIntent())))
                    return reject("CUSTODY_FINANCIAL_INTENT");
                var decision = financial.prepare(intent, current, reads);
                if (!(decision instanceof Commit accepted)) return decision;
                composed = accepted; financialOperation = intent.operationId().value(); next = State.COMMITTED;
            }
            case BEGIN_DELIVERY, COLLECT -> {
                if (action == Action.BEGIN_DELIVERY && state != State.COMMITTED) return reject("CUSTODY_STATE");
                if (action == Action.COLLECT && state != State.CLAIM) return reject("CUSTODY_STATE");
                if (action == Action.COLLECT && !intent.actor().equals(current.terms().owner())) return decline("CUSTODY_ACCESS");
                if (attempt == null || !attempt.equals(intent.operationId().value())) return reject("CUSTODY_ATTEMPT");
                if (!capacity.fits(intent, current, reads)) return decline("CUSTODY_CAPACITY");
                deliveryAttempt = attempt; acknowledgment = null; deliveryWitness = null; next = State.DELIVERING;
            }
            case ACKNOWLEDGE -> {
                if (state != State.DELIVERING || !Objects.equals(attempt, deliveryAttempt)) return reject("CUSTODY_ATTEMPT");
                acknowledgment = intent.operationId().value().toString(); next = State.ACKNOWLEDGED;
            }
            case DELIVERY_SAVED -> {
                if ((state != State.DELIVERING && state != State.ACKNOWLEDGED) || !Objects.equals(attempt, deliveryAttempt))
                    return reject("CUSTODY_ATTEMPT");
                if (!proof(current, evidence, EvidenceKind.DELIVERY_SAVED, verifier, reads)) return decline("CUSTODY_EVIDENCE");
                deliveryWitness = encodeEvidence(evidence); next = State.DELIVERED;
            }
            case CLAIM -> {
                if (state != State.COMMITTED && state != State.DELIVERING && state != State.ACKNOWLEDGED)
                    return reject("CUSTODY_STATE");
                if (!proof(current, evidence, EvidenceKind.UNDELIVERED, verifier, reads)) return decline("CUSTODY_EVIDENCE");
                next = State.CLAIM;
            }
            case QUARANTINE -> {
                if (state == State.QUARANTINED) return reject("CUSTODY_STATE");
                next = State.QUARANTINED;
            }
            case CLOSE_DELIVERED, REDELIVER, COMPENSATE -> {
                if (state != State.QUARANTINED || reason.isBlank()) return reject("CUSTODY_RESOLUTION");
                if (action != Action.CLOSE_DELIVERED && compensationOperation != null)
                    return reject("CUSTODY_ALREADY_COMPENSATED");
                var expected = action == Action.CLOSE_DELIVERED ? EvidenceKind.DELIVERED : EvidenceKind.LOST;
                if (!proof(current, evidence, expected, verifier, reads)) return decline("CUSTODY_EVIDENCE");
                if (action == Action.CLOSE_DELIVERED) next = State.DELIVERED;
                if (action == Action.REDELIVER) next = State.CLAIM;
                if (action == Action.COMPENSATE) {
                    if (financial == null) return decline("CUSTODY_FINANCIAL_HANDLER");
                    var decision = financial.prepare(intent, current, reads);
                    if (!(decision instanceof Commit accepted)) return decision;
                    var retirement = CashIssuance.retireClaim(reads, current.id(), accepted);
                    if (!(retirement instanceof Commit retired)) return retirement;
                    composed = retired; compensationOperation = intent.operationId().value(); next = State.COMPENSATED;
                }
            }
            default -> throw new IllegalStateException("Unexpected custody action");
        }
        var changed = new Case(id, current.terms(), next, current.reservationOperation(), financialOperation,
                deliveryAttempt, reservationWitness, deliveryWitness, acknowledgment, evidence, reason,
                Math.addExact(current.revision(), 1), compensationOperation);
        return commit(action, changed, composed, false);
    }

    /** A financial commit and its exact intended delivery share one durable transaction. */
    public static Commit committedDelivery(Intent intent, UUID reservation, ActorId owner, String items,
                                           String source, String destination, ReadView reads) {
        if (raw(reads, reservation).isPresent()) throw new IllegalStateException("Custody delivery already exists");
        var terms = new Terms(owner, items, source, destination, financialIntent(intent));
        var value = new Case(reservation, terms, State.COMMITTED, reservation, intent.operationId().value(),
                null, null, null, null, Evidence.unknown(), "committed withdrawal delivery", 1, null);
        return commit(Action.COMMIT, value, null, true);
    }

    /**
     * Trusted composition boundary for originals already proven undelivered by a deposit return.
     * The caller commits this claim and the return/quantity-lock transition together.
     */
    public static Commit returnClaim(Intent intent, UUID id, UUID reservation, ActorId owner, String items,
                                     String location, Evidence evidence, String reason, ReadView reads) {
        return returnCase(intent, id, reservation, owner, items, location, evidence, reason, State.CLAIM, reads);
    }

    /** Trusted composition for known undelivered originals or an unresolved original return. */
    public static Commit returnCase(Intent intent, UUID id, UUID reservation, ActorId owner, String items,
                                    String location, Evidence evidence, String reason, State state, ReadView reads) {
        Objects.requireNonNull(intent); Objects.requireNonNull(id); Objects.requireNonNull(reservation);
        if (state != State.CLAIM && state != State.QUARANTINED)
            throw new IllegalArgumentException("Original return state");
        if (!evidence.location().equals(location)
                || (state == State.CLAIM && evidence.kind() != EvidenceKind.UNDELIVERED))
            throw new IllegalArgumentException("Return claim requires undelivered proof");
        bounded(reason, 1024);
        if (raw(reads, id).isPresent()) return null;
        var terms = new Terms(owner, items, location, location, financialIntent(intent));
        var value = new Case(id, terms, state, reservation, intent.operationId().value(),
                null, null, null, null, evidence, reason, 1, null);
        return commit(state == State.CLAIM ? Action.CLAIM : Action.QUARANTINE, value, null, true);
    }

    private static boolean proof(Case current, Evidence evidence, EvidenceKind expected, EvidenceVerifier verifier, ReadView reads) {
        String location = expected == EvidenceKind.RESERVATION_SAVED ? current.terms().source() : current.terms().destination();
        return evidence.kind() == expected && evidence.location().equals(location)
                && verifier.verifies(current, evidence, reads);
    }

    private static Commit commit(Action action, Case value, Commit base, boolean insert) {
        var records = new ArrayList<Journal.Record>();
        if (base != null) records.addAll(base.records());
        records.add(new Journal.Record(FACT, 1, encode(action, value)));
        return new Commit(records, new Result(1, "CUSTODY_" + action.name(), value.id().toString()), tx -> {
            if (base != null) base.projections().apply(tx);
            if (value.state() == State.DELIVERED || value.state() == State.COMPENSATED)
                tx.execute("UPDATE custody_cash_reservations SET state='RELEASED' WHERE custody_id=? AND state='ACTIVE'",
                        List.of(value.id().toString()));
            if (value.state() == State.DELIVERED || value.state() == State.COMPENSATED)
                tx.execute("UPDATE register_stock_locks SET state='RELEASED' WHERE return_custody_id=?",
                        List.of(value.id().toString()));
            var parameters = values(value);
            if (insert) tx.execute("INSERT INTO custody_cases VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", parameters);
            else {
                var update = new ArrayList<>(parameters.subList(1, parameters.size()));
                update.add(value.id().toString()); update.add(Long.toString(value.revision() - 1));
                int changed = tx.execute("""
                        UPDATE custody_cases SET owner=?,items=?,source=?,destination=?,financial_intent=?,state=?,
                        reservation_operation=?,financial_operation=?,delivery_attempt=?,reservation_witness=?,
                        delivery_witness=?,acknowledgment=?,evidence=?,reason=?,revision=?,compensation_operation=? WHERE id=? AND revision=?
                        """, update);
                if (changed != 1) throw new IllegalStateException("Custody revision changed");
            }
        });
    }

    private static List<String> values(Case c) {
        return Arrays.asList(c.id().toString(), actorKey(c.terms().owner()), c.terms().items(), c.terms().source(),
                c.terms().destination(), c.terms().financialIntent(), c.state().name(), c.reservationOperation().toString(),
                text(c.financialOperation()), text(c.deliveryAttempt()), c.reservationWitness(), c.deliveryWitness(),
                c.acknowledgment(), encodeEvidence(c.evidence()), c.reason(), Long.toString(c.revision()), text(c.compensationOperation()));
    }

    private static Optional<Case> raw(ReadView reads, UUID id) {
        return reads.query("SELECT * FROM custody_cases WHERE id=?", List.of(id.toString()), 1).stream().map(Custody::read).findFirst();
    }

    /** Owner-bound claims are retained indefinitely until delivered or explicitly resolved. */
    public static Page claims(ReadView reads, ActorId actor, UUID after, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) throw new IllegalArgumentException("Custody page size");
        var rows = reads.query("""
                SELECT * FROM custody_cases WHERE owner=? AND id>? AND state IN ('CLAIM','COMMITTED')
                    AND (state='CLAIM' OR (state='COMMITTED' AND items LIKE 'issued-card-v1%'
                        AND delivery_attempt IS NULL AND acknowledgment IS NULL AND delivery_witness IS NULL))
                    ORDER BY id LIMIT ?
                """, List.of(actorKey(actor), after == null ? "" : after.toString(), Integer.toString(limit + 1)), limit + 1);
        var cases = rows.stream().limit(limit).map(Custody::read).toList();
        return new Page(cases, rows.size() > limit ? cases.getLast().id() : null);
    }

    public static Optional<Case> find(ReadView reads, ActorId actor, UUID id, boolean operator) {
        return raw(reads, id).filter(value -> operator || value.terms().owner().equals(actor));
    }

    /** Startup gate: inspect at most sixteen cases; never materialize an item during recovery. */
    public static boolean reconcileBatch(FoundationStore store) {
        String after=RECONCILIATION_CURSORS.getOrDefault(store, "");
        var rows=store.transaction(tx->tx.query("""
                SELECT id,revision FROM custody_cases
                WHERE state<>'QUARANTINED' AND id>? ORDER BY id LIMIT 16
                """,List.of(after),16));
        var world=store.snapshot().worldId();
        java.util.function.BiPredicate<Case,ReadView> verifiedCard=(current,reads)-> {
            try {
                var debit=ca.emilybelnavis.mcmods.economy.card.IssuedCards.find(reads,world,current.id()).orElse(null);
                var credit=debit==null?ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.token(reads,world,current.id()).orElse(null):null;
                if(debit==null && credit==null)return false;
                var id=debit!=null?debit.id():credit.id();
                var owner=debit!=null?debit.owner():credit.owner();
                var custody=debit!=null?debit.custodyId():credit.custodyId();
                var items=debit!=null?ca.emilybelnavis.mcmods.economy.card.IssuedCards.deliveryItems(debit):ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.deliveryItems(credit);
                if(!custody.equals(current.id()) || !owner.equals(current.terms().owner())
                        || !id.equals(current.reservationOperation()) || !id.equals(current.financialOperation())
                        || !current.terms().items().equals(items)
                        || !current.terms().source().equals("issued-card:"+id)
                        || !current.terms().destination().equals(actorKey(owner)))return false;
                var outcome=reads.query("""
                        SELECT actor,service,operation_type,canonical_request,status FROM operation_outcomes
                        WHERE world_id=? AND operation_id=?
                        """,List.of(world.value().toString(),id.toString()),1);
                if(outcome.isEmpty())return false;
                var original=outcome.getFirst();
                var kind=new Kind(original.get("service"),original.get("operation_type"));
                boolean issuance=debit!=null?kind.equals(ca.emilybelnavis.mcmods.economy.card.IssuedCards.ISSUE)
                        :Set.of(ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.ISSUE,ca.emilybelnavis.mcmods.economy.credit.CreditFacilities.REPLACE).contains(kind);
                return issuance && original.get("status").equals("COMMITTED") && original.get("actor").equals(actorKey(owner))
                        && current.terms().financialIntent().equals(id+"\n"+original.get("actor")+"\n"
                                +original.get("service")+"\n"+original.get("operation_type")+"\n"+original.get("canonical_request"));
            } catch(IllegalArgumentException malformed) {return false;}
        };
        java.util.function.Predicate<Case> neverAttempted=current->current.deliveryAttempt()==null
                && (current.acknowledgment()==null || current.acknowledgment().isBlank())
                && (current.deliveryWitness()==null || current.deliveryWitness().isBlank())
                && current.evidence().kind()==EvidenceKind.UNKNOWN;
        var quarantine=registration(Action.QUARANTINE,(action,intent,current,reads)->intent.actor().equals(RECOVERY),
                (current,evidence,reads)->false,null);
        var claim=registration(Action.CLAIM,(action,intent,current,reads)->intent.actor().equals(RECOVERY)
                        && current.state()==State.COMMITTED && neverAttempted.test(current) && verifiedCard.test(current,reads),
                (current,evidence,reads)->verifiedCard.test(current,reads) && neverAttempted.test(current)
                        && evidence.kind()==EvidenceKind.UNDELIVERED && evidence.reference().equals("card-committed:"+current.id())
                        && evidence.location().equals(current.terms().destination()) && evidence.saveToken().equals("no-delivery-attempt"),null);
        var engine=new JournalEngine(store,(actor,kind)->actor.equals(RECOVERY)
                && (kind.equals(Action.QUARANTINE.kind()) || kind.equals(Action.CLAIM.kind())),
                Map.of(Action.QUARANTINE.kind(),quarantine,Action.CLAIM.kind(),claim));
        for(var row:rows) {
            var id=UUID.fromString(row.get("id"));
            var current=store.transaction(tx->raw(tx::query,id).orElseThrow());
            if (store.<Boolean>transaction(tx->NpcDeliveries.isClaimable(tx::query,current))) continue;
            boolean card=store.transaction(tx->verifiedCard.test(current,tx::query));
            if(card && (current.state()==State.DELIVERED || current.state()==State.CLAIM
                    && current.evidence().kind()==EvidenceKind.UNDELIVERED
                    && (current.acknowledgment()==null || current.acknowledgment().isBlank())
                    && (current.deliveryWitness()==null || current.deliveryWitness().isBlank())))continue;
            var action=card && current.state()==State.COMMITTED && neverAttempted.test(current)?Action.CLAIM:Action.QUARANTINE;
            var evidence=action==Action.CLAIM?new Evidence(EvidenceKind.UNDELIVERED,"card-committed:"+id,
                    current.terms().destination(),"no-delivery-attempt"):Evidence.unknown();
            var operation=UUID.nameUUIDFromBytes(("economy:custody-reconcile:"+id+":"+row.get("revision")+(action==Action.CLAIM?":CLAIM":""))
                    .getBytes(StandardCharsets.UTF_8));
            var reason=action==Action.CLAIM?"committed card has no physical delivery attempt":"startup save evidence requires reconciliation";
            var outcome=engine.submit(new Intent(new OperationId(operation),new RequestId(UUID.randomUUID()),RECOVERY,
                    action.kind(),request(id,null,evidence,reason),null,null));
            if(outcome.status()!=Status.COMMITTED)throw new IllegalStateException("Custody reconciliation failed");
        }
        String cursor=rows.isEmpty()?after:rows.getLast().get("id");
        boolean complete=store.transaction(tx->tx.query("""
                SELECT id FROM custody_cases WHERE state<>'QUARANTINED' AND id>? LIMIT 1
                """,List.of(cursor),1).isEmpty());
        if(complete)RECONCILIATION_CURSORS.remove(store);else RECONCILIATION_CURSORS.put(store,cursor);
        return complete;
    }

    private static Case read(Map<String, String> row) {
        return new Case(UUID.fromString(row.get("id")), new Terms(actor(row.get("owner")), row.get("items"),
                row.get("source"), row.get("destination"), row.get("financial_intent")), State.valueOf(row.get("state")),
                UUID.fromString(row.get("reservation_operation")), id(row.get("financial_operation")), id(row.get("delivery_attempt")),
                row.get("reservation_witness"), row.get("delivery_witness"), row.get("acknowledgment"),
                decodeEvidence(row.get("evidence")), row.get("reason"), Long.parseLong(row.get("revision")), id(row.get("compensation_operation")));
    }

    private static boolean terminal(State state) { return state == State.DELIVERED || state == State.COMPENSATED; }
    private static String text(UUID id) { return id == null ? null : id.toString(); }
    private static UUID id(String value) { return value == null ? null : UUID.fromString(value); }
    private static void bounded(String value, int maximum) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > maximum) throw new IllegalArgumentException("Custody field bounds");
    }
    private static String encodeEvidence(Evidence value) {
        return value.kind().name() + ":" + pack(value.reference()) + ":" + pack(value.location()) + ":" + pack(value.saveToken());
    }
    static Evidence decodeEvidence(String value) {
        var parts = value.split(":", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Custody evidence fields");
        return new Evidence(EvidenceKind.valueOf(parts[0]), unpack(parts[1]), unpack(parts[2]), unpack(parts[3]));
    }
    private static String pack(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }
    private static String unpack(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
    private static String encode(Action action, Case c) {
        var fields = new ArrayList<String>();
        fields.add(action.name());
        for (var value : values(c)) fields.add(pack(value));
        return String.join("|", fields);
    }
    public static void validateFact(String payload) {
        snapshot(payload);
    }

    static Case snapshot(String payload) {
        var parts = payload.split("\\|", -1);
        if (parts.length != 18) throw new IllegalArgumentException("Custody fact fields");
        Action.valueOf(parts[0]);
        var row = new HashMap<String, String>();
        var names = List.of("id", "owner", "items", "source", "destination", "financial_intent", "state", "reservation_operation",
                "financial_operation", "delivery_attempt", "reservation_witness", "delivery_witness", "acknowledgment", "evidence", "reason", "revision", "compensation_operation");
        for (int index = 0; index < names.size(); index++) {
            var value = unpack(parts[index + 1]);
            row.put(names.get(index), value.isEmpty() ? null : value);
        }
        var value = read(row);
        if (value.revision() < 1) throw new IllegalArgumentException("Custody revision");
        if (value.financialOperation() != null
                && !value.financialOperation().equals(financialOperation(value.terms().financialIntent())))
            throw new IllegalArgumentException("Custody financial operation");
        if (value.reservationWitness() != null
                && decodeEvidence(value.reservationWitness()).kind() != EvidenceKind.RESERVATION_SAVED)
            throw new IllegalArgumentException("Custody reservation witness");
        if (value.deliveryWitness() != null
                && decodeEvidence(value.deliveryWitness()).kind() != EvidenceKind.DELIVERY_SAVED)
            throw new IllegalArgumentException("Custody delivery witness");
        if (value.acknowledgment() != null) UUID.fromString(value.acknowledgment());
        bounded(value.reason(), 1024);
        return value;
    }
    private static Reject reject(String code) { return new Reject(new Result(1, code, "")); }
    private static Decline decline(String code) { return new Decline(new Result(1, code, "")); }
}
