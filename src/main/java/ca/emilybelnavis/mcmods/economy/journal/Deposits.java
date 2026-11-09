package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.pricing.CashPricing;
import ca.emilybelnavis.mcmods.economy.pricing.ServicePricing;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.journal.JournalEngine.*;
import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Role.*;

/** Immutable 24-slot deposit intent. Minecraft removal and return effects belong to server adapters. */
public final class Deposits {
    public static final String FACT = "economy:deposit";
    public static final int MAX_SLOTS = 24;
    private static final String CHF_SETTLEMENT = "CHF_MINOR_V2";
    private static final String PRICED_SETTLEMENT = "PRICED_MINOR_V3";
    public static final int MAX_COMPONENT_BYTES = 32_768;
    private static final SystemActorId RECOVERY = new SystemActorId(
            UUID.fromString("45d57bca-f394-4c34-8fb4-df79bf99c520"));
    public enum State { RESERVED, SAVED, COMMITTED, CANCELLED, QUARANTINED }
    public enum Action {
        RESERVE, SAVED, COMMIT, CANCEL, QUARANTINE;
        public Kind kind() { return new Kind("economy:deposits", "economy:" + name().toLowerCase(Locale.ROOT)); }
    }
    public record Denomination(EconomyCurrency currency, long minorUnits, boolean bill) {
        public Denomination {
            Objects.requireNonNull(currency);
            if (minorUnits <= 0) throw new IllegalArgumentException("DEPOSIT_DENOMINATION");
        }
    }
    public record Slot(int index, CashIssuance.Claim claim, String components, boolean unmarked) {
        public Slot {
            Objects.requireNonNull(claim); text(components, 2048);
            if (index < 0 || index >= MAX_SLOTS) throw new IllegalArgumentException("DEPOSIT_SLOT");
        }
        public Slot(int index, CashIssuance.Claim claim, String components) { this(index, claim, components, false); }
    }
    public record Binding(UUID account, long accountRevision, EconomyCurrency currency, TerminalPolicy.Anchor terminal,
                          UUID generation, long terminalRevision, String fxRevision) {
        public Binding {
            Objects.requireNonNull(account); Objects.requireNonNull(currency); Objects.requireNonNull(terminal);
            Objects.requireNonNull(generation); text(fxRevision, 256);
            if (accountRevision < 1 || terminalRevision < 1) throw new IllegalArgumentException("DEPOSIT_REVISION");
        }
    }
    public record FxSnapshot(String revision, CurrencyConversion.Rates rates, CashPricing.Context pricing) {
        public FxSnapshot(String revision, CurrencyConversion.Rates rates) { this(revision,rates,null); }
        public FxSnapshot { text(revision, 256); Objects.requireNonNull(rates); }
    }
    public record Group(EconomyCurrency currency, int count, Money subtotal, Money credit, boolean skipped, String pricing) {
        public Group(EconomyCurrency currency,int count,Money subtotal,Money credit,boolean skipped){this(currency,count,subtotal,credit,skipped,null);}
        public Group { if(pricing!=null)text(pricing,1024); }
    }
    public record Preview(Binding binding, List<Slot> slots, List<Group> groups, Money credit,
                          List<Slot> payable, List<Slot> returned) {
        public Preview {
            Objects.requireNonNull(binding); Objects.requireNonNull(credit);
            slots = List.copyOf(slots); groups = List.copyOf(groups);
            payable = List.copyOf(payable); returned = List.copyOf(returned);
            validateSlots(slots);
            boolean minorSettlement = binding.currency() == EconomyCurrency.CHF
                    || groups.stream().anyMatch(group -> group.currency() == EconomyCurrency.CHF || group.pricing()!=null);
            if (credit.currency() != binding.currency() || credit.minorUnits() < 0
                    || !minorSettlement && credit.minorUnits() % binding.currency().minorUnitsPerMajor() != 0)
                throw new IllegalArgumentException("DEPOSIT_CREDIT");
            if (groups.isEmpty() || groups.size() > 11) throw invalid("DEPOSIT_GROUP");
            var currencies = EnumSet.noneOf(EconomyCurrency.class);
            long total = 0;
            int count = 0;
            for (var group : groups) {
                if (!currencies.add(group.currency()) || group.count() < 1 || group.count() > MAX_SLOTS * 64
                        || group.subtotal().currency() != group.currency() || group.subtotal().minorUnits() < 1
                        || group.credit().currency() != binding.currency() || group.credit().minorUnits() < 0
                        || group.credit().minorUnits() % (group.pricing()!=null?1:quantum(group.currency(), binding.currency())) != 0
                        || group.skipped() != (group.credit().minorUnits() == 0)) throw invalid("DEPOSIT_GROUP");
                total = Math.addExact(total, group.credit().minorUnits());
                count = Math.addExact(count, group.count());
            }
            if (total != credit.minorUnits() || count != slots.stream().mapToInt(s -> s.claim().count()).sum())
                throw invalid("DEPOSIT_GROUP");
            var originals = new HashMap<Integer, Slot>();
            for (var slot : slots) originals.put(slot.index(), slot);
            var partition = new HashSet<Integer>();
            for (var slot : payable) if (!slot.equals(originals.get(slot.index())) || !partition.add(slot.index()))
                throw invalid("DEPOSIT_PARTITION");
            for (var slot : returned) if (!slot.equals(originals.get(slot.index())) || !partition.add(slot.index()))
                throw invalid("DEPOSIT_PARTITION");
            if (partition.size() != slots.size()) throw invalid("DEPOSIT_PARTITION");
        }
    }
    public record Reservation(UUID id, ActorId owner, State state, Preview preview,
                              String evidence, String reason, long revision) {}
    @FunctionalInterface public interface FxReader { FxSnapshot current(ReadView reads); }
    @FunctionalInterface public interface Authority {
        boolean allows(Action action, Intent intent, Reservation current, ReadView reads);
    }
    @FunctionalInterface public interface EvidenceVerifier {
        boolean verifies(Action action, Intent intent, Reservation current, String evidence, ReadView reads);
    }
    private Deposits() {}

    /** Preview is worker-side and checks current account, terminal, publication and authentic quantity. */
    public static Preview preview(ReadView reads, WorldId world, ActorId owner, Binding binding,
                                  List<Slot> slots, Map<String, Denomination> catalog, FxReader fx) {
        if (!TerminalBanking.bound(reads,world,binding.terminal(),binding.generation())) throw invalid("UNBOUND_BANK");
        var result = calculate(reads, world, owner, binding, slots, Map.copyOf(catalog), fx, null, Map.of());
        // Reject an unreservable preview before an adapter moves any originals.
        var validationId = new UUID(0, 0);
        new Intent(new OperationId(validationId), new RequestId(validationId), owner,
                Action.RESERVE.kind(), reserveRequest(result), null, null);
        return result;
    }

    private static Preview calculate(ReadView reads, WorldId world, ActorId owner, Binding binding,
                                     List<Slot> slots, Map<String, Denomination> catalog, FxReader fx, UUID ownDeposit, Map<EconomyCurrency,String> capturedPricing) {
        validateSlots(slots);
        if (!(owner instanceof PlayerId player)) throw invalid("DEPOSIT_ACCOUNT");
        var account = reads.query("""
                SELECT account_id FROM account_catalog WHERE account_id=? AND owner_id=? AND purpose='BANK'
                AND currency=? AND status='OPEN' AND revision=?
                """, List.of(binding.account().toString(), player.value().toString(), binding.currency().name(),
                Long.toString(binding.accountRevision())), 1);
        if (account.isEmpty()) throw invalid("DEPOSIT_ACCOUNT_STALE");
        var terminal = TerminalPolicy.find(reads, world, binding.terminal(), owner, true).orElseThrow(() -> invalid("DEPOSIT_TERMINAL"));
        var terminalCatalog = catalog.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> new TerminalPolicy.Denomination(entry.getValue().currency(), entry.getValue().bill())));
        terminal = TerminalPolicy.effective(terminal, terminalCatalog);
        if (!terminal.active() || !terminal.generation().equals(binding.generation())
                || terminal.revision() != binding.terminalRevision()) throw invalid("DEPOSIT_TERMINAL_STALE");
        if (!terminal.settings().mode().deposits() || terminal.kind() == TerminalPolicy.Kind.REGISTER)
            throw invalid("DEPOSIT_MODE");
        var publication = fx.current(reads);
        if (publication == null || !publication.revision().equals(binding.fxRevision())) throw invalid("DEPOSIT_FX_STALE");
        var provider=ca.emilybelnavis.mcmods.economy.institution.BankProviders.find(reads,new AccountId(binding.account())).orElse(null);
        boolean assigned=provider!=null&&!provider.bankKey().equals(ca.emilybelnavis.mcmods.economy.institution.BankProviders.UNASSIGNED_PROVIDER);
        if(publication.pricing()!=null&&(!publication.pricing().world().equals(world)||!publication.pricing().owner().equals(player)||!publication.pricing().account().equals(binding.account())))throw invalid("PRICING_CONTEXT_MISMATCH");
        if(assigned) {
            var bank=ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.require(new ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog.InstitutionId(provider.bankKey()));
            if(!bank.tier1()) {
                if(publication.pricing()==null)throw invalid("PRICING_CONTEXT_UNAVAILABLE");
                var valuation=publication.pricing().pricing().valuation().snapshot(reads,world,player,publication.pricing().now());
                if(!valuation.available()||!valuation.eligible())throw invalid("TIER2_UNAVAILABLE");
            }
        }
        var amounts = new EnumMap<EconomyCurrency, Long>(EconomyCurrency.class);
        var counts = new EnumMap<EconomyCurrency, Integer>(EconomyCurrency.class);
        var quantities = new HashMap<UUID, Integer>();
        for (var slot : slots) {
            var claim = slot.claim();
            var denomination = catalog.get(claim.item());
            if (denomination == null || !terminal.settings().denominations().contains(claim.item())
                    || !terminal.settings().currencies().contains(denomination.currency())
                    || (terminal.kind() == TerminalPolicy.Kind.ATM && !denomination.bill())) throw invalid("DEPOSIT_DENOMINATION");
            if (!claim.world().equals(world)) throw invalid("DEPOSIT_WORLD");
            if (!slot.unmarked()) {
                var lot = CashIssuance.find(reads, world, claim.lot()).orElseThrow(() -> invalid("DEPOSIT_UNRECOGNIZED"));
                if (!lot.item().equals(claim.item()) || lot.state() != CashIssuance.State.ACTIVE) throw invalid("DEPOSIT_UNRECOGNIZED");
                quantities.merge(lot.id(), claim.count(), Math::addExact);
            }
            amounts.merge(denomination.currency(), Math.multiplyExact(denomination.minorUnits(), claim.count()), Math::addExact);
            counts.merge(denomination.currency(), claim.count(), Math::addExact);
        }
        for (var entry : quantities.entrySet()) {
            var lot = CashIssuance.find(reads, world, entry.getKey()).orElseThrow();
            var locked = reads.query("""
                    SELECT COALESCE(SUM(count),0) AS total FROM deposit_cash_reservations
                    WHERE lot_id=? AND state='ACTIVE' AND deposit_id<>?
                    """, List.of(entry.getKey().toString(), ownDeposit == null ? "" : ownDeposit.toString()), 1);
            long reserved = Long.parseLong(locked.getFirst().get("total"));
            var custodyLocks = reads.query("""
                    SELECT COALESCE(SUM(count),0) AS total FROM custody_cash_reservations
                    WHERE lot_id=? AND state='ACTIVE'
                    """, List.of(entry.getKey().toString()), 1);
            reserved = Math.addExact(reserved, Long.parseLong(custodyLocks.getFirst().get("total")));
            if (entry.getValue() > lot.remaining() - reserved) throw invalid("DEPOSIT_CASH_RESERVED");
        }
        if(publication.pricing()!=null && CashPricing.bank(publication.pricing(),reads)!=null
                && amounts.keySet().stream().filter(c->c!=binding.currency()).count()>4)throw invalid("PRICING_BATCH_LIMIT");
        var groups = new ArrayList<Group>();
        var payableCurrencies = EnumSet.noneOf(EconomyCurrency.class);
        long total = 0;
        for (var entry : amounts.entrySet()) {
            var subtotal = new Money(entry.getKey(), entry.getValue());
            CashPricing.Quote price=null;
            if(entry.getKey()!=binding.currency()) {
                if(assigned&&publication.pricing()==null)throw invalid("PRICING_CONTEXT_UNAVAILABLE");
                String captured=capturedPricing.get(entry.getKey());
                price=captured==null?CashPricing.deposit(publication.pricing(),reads,subtotal):CashPricing.requireFresh(publication.pricing(),reads,captured);
                if(price!=null&&(price.pricing().service()!=ServicePricing.Service.FX||price.payer()!=null||price.fee().currency()!=entry.getKey()||Math.addExact(price.principalMinor(),price.fee().minorUnits())!=subtotal.minorUnits()))throw invalid("DEPOSIT_PRICING");
            } else if(capturedPricing.containsKey(entry.getKey()))throw invalid("DEPOSIT_PRICING");
            var principal=price==null?subtotal:new Money(entry.getKey(),price.principalMinor());
            long credit;
            try {
                credit = CurrencyConversion.deposit(List.of(principal), binding.currency(), publication.rates()).credit().minorUnits();
            } catch (CurrencyConversion.Rejected rejection) {
                if (rejection.failure() != CurrencyConversion.Failure.ZERO_CREDIT) throw rejection;
                credit = 0;
            }
            long quantum = price==null?quantum(entry.getKey(), binding.currency()):1;
            credit = Math.multiplyExact(credit / quantum, quantum);
            if (credit > 0) payableCurrencies.add(entry.getKey());
            groups.add(new Group(entry.getKey(), counts.get(entry.getKey()), subtotal,
                    new Money(binding.currency(), credit), credit == 0,price==null?null:price.encoded()));
            total = Math.addExact(total, credit);
        }
        var payable = slots.stream().filter(slot -> payableCurrencies.contains(catalog.get(slot.claim().item()).currency())).toList();
        var returned = slots.stream().filter(slot -> !payableCurrencies.contains(catalog.get(slot.claim().item()).currency())).toList();
        return new Preview(binding, slots, groups, new Money(binding.currency(), total), payable, returned);
    }

    /** Slot fields remain separate to keep every canonical field inside the journal's 16 KiB bound. */
    public static CanonicalRequest reserveRequest(Preview preview) {
        var fields = new TreeMap<String, String>();
        fields.put("binding", encodeBinding(preview.binding()));
        fields.put("groups", encodeGroups(preview.groups()));
        fields.put("credit", Long.toString(preview.credit().minorUnits()));
        if (priced(preview)) fields.put("settlement", PRICED_SETTLEMENT);
        else if (minorSettlement(preview)) fields.put("settlement", CHF_SETTLEMENT);
        for (var slot : preview.slots()) fields.put("slot_" + slot.index(), encodeSlot(slot));
        return new CanonicalRequest(1, fields);
    }
    public static CanonicalRequest request(UUID reservation, String evidence, String reason) {
        Objects.requireNonNull(reservation); text(evidence, 512); text(reason, 512);
        return new CanonicalRequest(1, Map.of("reservation", reservation.toString(), "evidence", evidence, "reason", reason));
    }
    public static Registration registration(Action action, WorldId world, Map<String, Denomination> catalog,
                                             FxReader fx, Authority authority, EvidenceVerifier verifier) {
        var immutable = Map.copyOf(catalog);
        return new Registration(1, (intent, reads) -> prepare(action, world, immutable, fx, authority, verifier, intent, reads));
    }

    private static Decision prepare(Action action, WorldId world, Map<String, Denomination> catalog, FxReader fx,
                                    Authority authority, EvidenceVerifier verifier, Intent intent, ReadView reads) throws Exception {
        if (!intent.kind().equals(action.kind())) return reject("DEPOSIT_REQUEST");
        if (action == Action.RESERVE) {
            Preview supplied;
            try {
                supplied = decodeRequest(intent.request(), catalog);
                if (!TerminalBanking.bound(reads,world,supplied.binding().terminal(),supplied.binding().generation())) return reject("UNBOUND_BANK");
                var actual = calculate(reads, world, intent.actor(), supplied.binding(), supplied.slots(), catalog, fx, null, pricing(supplied));
                if (!reserveRequest(actual).equals(intent.request())) return reject("DEPOSIT_PREVIEW");
                supplied = actual;
            } catch (IllegalArgumentException | ArithmeticException | NullPointerException malformed) { return rejected(malformed); }
            var reservation = new Reservation(intent.operationId().value(), intent.actor(), State.RESERVED, supplied, "pending", "reserved", 1);
            if (!authority.allows(action, intent, reservation, reads)) return decline("DEPOSIT_ACCESS");
            return commit(action, reservation, null, true, List.of(), Set.of());
        }
        UUID id; String evidence; String reason;
        try {
            var fields = intent.request().fields();
            id = UUID.fromString(fields.get("reservation")); evidence = fields.get("evidence"); reason = fields.get("reason");
            if (!request(id, evidence, reason).equals(intent.request())) return reject("DEPOSIT_REQUEST");
        } catch (IllegalArgumentException | NullPointerException malformed) { return reject("DEPOSIT_REQUEST"); }
        var current = raw(reads, id).orElse(null);
        if (current == null || (action != Action.QUARANTINE && !current.owner().equals(intent.actor()))
                || !authority.allows(action, intent, current, reads)) return decline("DEPOSIT_ACCESS");
        if (current.state() == State.COMMITTED || current.state() == State.CANCELLED || current.state() == State.QUARANTINED)
            return reject("DEPOSIT_STATE");
        Commit composed = null;
        List<Slot> returns = List.of();
        Set<UUID> released = Set.of();
        State next;
        if (action == Action.SAVED) {
            if (current.state() != State.RESERVED) return reject("DEPOSIT_STATE");
            if (!verifier.verifies(action, intent, current, evidence, reads)) return decline("DEPOSIT_EVIDENCE");
            next = State.SAVED;
        } else if (action == Action.CANCEL) {
            // Cancellation needs proof the original reserved stacks remain safely in server custody.
            if (!verifier.verifies(action, intent, current, evidence, reads)) return decline("DEPOSIT_EVIDENCE");
            returns = current.preview().slots(); next = State.CANCELLED;
        } else if (action == Action.QUARANTINE) {
            returns = current.preview().slots();
            next = State.QUARANTINED;
        } else if (action == Action.COMMIT) {
            if (current.state() != State.SAVED) return reject("DEPOSIT_STATE");
            if (!verifier.verifies(action, intent, current, evidence, reads)) return decline("DEPOSIT_EVIDENCE");
            try {
                var checked = calculate(reads, world, current.owner(), current.preview().binding(), current.preview().slots(),
                        catalog, fx, current.id(), pricing(current.preview()));
                if (!reserveRequest(checked).equals(reserveRequest(current.preview()))) return reject("DEPOSIT_STALE");
            } catch (IllegalArgumentException | ArithmeticException stale) { return rejected(stale); }
            var preview = current.preview();
            returns = preview.returned();
            if (!preview.payable().isEmpty()) {
                var amount = preview.credit();
                var legs = List.of(new PostingBatch.Leg(Settlement.issuanceAccount(amount.currency()), ISSUANCE,
                                amount.currency(), amount.negate()),
                        new PostingBatch.Leg(preview.binding().account(), ACCOUNT, amount.currency(), amount));
                var minor = minorSettlement(preview);
                var financialRegistration = minor
                        ? Settlement.registrationMinor((i, l, r) -> PostingBatch.Authority.ISSUE)
                        : Settlement.registration((i, l, r) -> PostingBatch.Authority.ISSUE);
                var financial = new Intent(intent.operationId(), intent.requestId(), intent.actor(),
                        new Kind("economy:deposits", "economy:credit"),
                        minor ? Settlement.minorRequest(legs) : Settlement.request(legs), null, null);
                var marked = preview.payable().stream().filter(slot -> !slot.unmarked()).map(Slot::claim).toList();
                Decision decision;
                if (marked.isEmpty()) {
                    decision = financialRegistration.handler().prepare(financial, reads);
                } else {
                    var redeem = CashIssuance.registration(CashIssuance.Action.REDEEM, world,
                            (a, i, lots, r) -> true, (a, i, lots, proof, r) -> true,
                            CashIssuance.financial(financial, financialRegistration), current.id());
                    var request = CashIssuance.redeemRequest(marked, evidence, financial);
                    decision = redeem.handler().prepare(new Intent(intent.operationId(), intent.requestId(), intent.actor(),
                            CashIssuance.Action.REDEEM.kind(), request, null, null), reads);
                }
                if (!(decision instanceof Commit accepted)) return decision;
                var prices=new ArrayList<CashPricing.Quote>();
                var context=fx.current(reads).pricing();
                for(var group:preview.groups())if(!group.skipped()&&group.pricing()!=null)prices.add(CashPricing.requireFresh(context,reads,group.pricing()));
                var withFees=prices.isEmpty()?accepted:FeeSettlement.composeCash(reads,accepted,prices);
                if(!(withFees instanceof Commit feeCommit))return withFees;
                composed = feeCommit;
                released = preview.payable().stream().map(slot -> slot.claim().lot()).collect(java.util.stream.Collectors.toSet());
            }
            next = State.COMMITTED;
        } else return reject("DEPOSIT_REQUEST");
        var changed = new Reservation(id, current.owner(), next, current.preview(), evidence, reason, Math.addExact(current.revision(), 1));
        return commit(action, changed, composed, false, returns, released);
    }

    private static Commit commit(Action action, Reservation value, Commit base, boolean insert,
                                 List<Slot> returns, Set<UUID> released) {
        var records = new ArrayList<Journal.Record>();
        if (base != null) records.addAll(base.records());
        records.add(new Journal.Record(FACT, priced(value.preview()) ? 3 : minorSettlement(value.preview()) ? 2 : 1, encodeReservation(action, value)));
        return new Commit(records, new Result(1, "DEPOSIT_" + action.name(), value.id().toString()), tx -> {
            if (base != null) base.projections().apply(tx);
            if (insert) {
                tx.execute("INSERT INTO deposits VALUES(?,?,?,?,?,?,?)", values(value));
                var quantities = new LinkedHashMap<UUID, Integer>();
                for (var slot : value.preview().slots()) quantities.merge(slot.claim().lot(), slot.claim().count(), Math::addExact);
                for (var entry : quantities.entrySet()) tx.execute("INSERT INTO deposit_cash_reservations VALUES(?,?,?,'ACTIVE')",
                        List.of(value.id().toString(), entry.getKey().toString(), Integer.toString(entry.getValue())));
            } else {
                int changed = tx.execute("UPDATE deposits SET state=?,preview=?,evidence=?,reason=?,revision=? WHERE id=? AND revision=?",
                        List.of(value.state().name(), encodePreview(value.preview()), value.evidence(), value.reason(),
                                Long.toString(value.revision()), value.id().toString(), Long.toString(value.revision() - 1)));
                if (changed != 1) throw new IllegalStateException("Deposit revision changed");
            }
            for (var slot : returns) tx.execute("""
                    INSERT INTO deposit_returns(deposit_id,slot,world_id,lot_id,item_id,count,components,state,revision,attempt)
                    VALUES(?,?,?,?,?,?,?,'PENDING',1,NULL)
                    """, List.of(value.id().toString(), Integer.toString(slot.index()), slot.claim().world().value().toString(),
                    slot.claim().lot().toString(), slot.claim().item(), Integer.toString(slot.claim().count()), slot.components()));
            for (var lot : released) tx.execute("UPDATE deposit_cash_reservations SET count=0,state='RELEASED' WHERE deposit_id=? AND lot_id=?",
                    List.of(value.id().toString(), lot.toString()));
        });
    }

    public static Optional<Reservation> find(ReadView reads, ActorId actor, UUID id) {
        return raw(reads, id).filter(value -> value.owner().equals(actor));
    }
    /** Startup never infers a return or fresh settlement from an interrupted reservation. */
    public static boolean reconcileBatch(FoundationStore store) {
        var rows = store.transaction(tx -> tx.query(
                "SELECT id,revision FROM deposits WHERE state IN ('RESERVED','SAVED') ORDER BY id LIMIT 16",
                List.of(), 16));
        var world = store.snapshot().worldId();
        var action = Action.QUARANTINE;
        var engine = new JournalEngine(store, (actor, kind) -> actor.equals(RECOVERY) && kind.equals(action.kind()),
                Map.of(action.kind(), registration(action, world, Map.of(), reads -> null,
                        (a, intent, current, reads) -> intent.actor().equals(RECOVERY),
                        (a, intent, current, evidence, reads) -> false)));
        for (var row : rows) {
            var id = UUID.fromString(row.get("id"));
            var operation = UUID.nameUUIDFromBytes(("economy:deposit-recovery:" + id + ":" + row.get("revision"))
                    .getBytes(StandardCharsets.UTF_8));
            var result = engine.submit(new Intent(new OperationId(operation), new RequestId(UUID.randomUUID()), RECOVERY,
                    action.kind(), request(id, "startup-unknown", "reservation save requires independent reconciliation"), null, null));
            if (result.status() != Status.COMMITTED) throw new IllegalStateException("Deposit reconciliation failed");
        }
        return store.transaction(tx -> tx.query(
                "SELECT id FROM deposits WHERE state IN ('RESERVED','SAVED') LIMIT 1", List.of(), 1).isEmpty());
    }

    private static Optional<Reservation> raw(ReadView reads, UUID id) {
        return reads.query("SELECT * FROM deposits WHERE id=?", List.of(id.toString()), 1).stream().map(Deposits::read).findFirst();
    }
    private static Reservation read(Map<String,String> row) {
        return new Reservation(UUID.fromString(row.get("id")), actor(row.get("owner")), State.valueOf(row.get("state")),
                decodePreview(row.get("preview")), row.get("evidence"), row.get("reason"), Long.parseLong(row.get("revision")));
    }
    private static List<String> values(Reservation value) {
        return List.of(value.id().toString(), actorKey(value.owner()), value.state().name(), encodePreview(value.preview()),
                value.evidence(), value.reason(), Long.toString(value.revision()));
    }
    private static void validateSlots(List<Slot> slots) {
        if (slots == null || slots.isEmpty() || slots.size() > MAX_SLOTS) throw invalid("DEPOSIT_SLOT_COUNT");
        var indexes = new HashSet<Integer>();
        int bytes = 0;
        for (var slot : slots) {
            if (!indexes.add(slot.index())) throw invalid("DEPOSIT_SLOT_DUPLICATE");
            bytes = Math.addExact(bytes, slot.components().getBytes(StandardCharsets.UTF_8).length);
        }
        if (bytes > MAX_COMPONENT_BYTES) throw invalid("DEPOSIT_COMPONENT_BUDGET");
    }
    private static String encodeSlot(Slot slot) {
        var claim = slot.claim();
        return slot.index() + "|" + claim.world().value() + "|" + claim.lot() + "|" + claim.item() + "|" + claim.count()
                + "|" + pack(slot.components()) + "|" + slot.unmarked();
    }
    private static Slot decodeSlot(String encoded) {
        var fields = encoded.split("\\|", -1);
        if (fields.length != 6 && fields.length != 7) throw invalid("DEPOSIT_SLOT");
        return new Slot(Integer.parseInt(fields[0]), new CashIssuance.Claim(new WorldId(UUID.fromString(fields[1])),
                UUID.fromString(fields[2]), fields[3], Integer.parseInt(fields[4])), unpack(fields[5]),
                fields.length == 7 && Boolean.parseBoolean(fields[6]));
    }
    private static String encodeBinding(Binding b) {
        var a = b.terminal();
        return b.account() + "|" + b.accountRevision() + "|" + b.currency() + "|" + a.dimension() + "|" + a.x() + "|" + a.y()
                + "|" + a.z() + "|" + b.generation() + "|" + b.terminalRevision() + "|" + pack(b.fxRevision());
    }
    private static Binding decodeBinding(String encoded) {
        var p = encoded.split("\\|", -1);
        if (p.length != 10) throw invalid("DEPOSIT_BINDING");
        return new Binding(UUID.fromString(p[0]), Long.parseLong(p[1]), EconomyCurrency.valueOf(p[2]),
                new TerminalPolicy.Anchor(p[3], Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6])),
                UUID.fromString(p[7]), Long.parseLong(p[8]), unpack(p[9]));
    }
    private static String encodeGroups(List<Group> groups) {
        return groups.stream().map(g -> g.currency() + "|" + g.count() + "|" + g.subtotal().minorUnits() + "|"
                + g.credit().minorUnits() + "|" + g.skipped()+(g.pricing()==null?"":"|"+pack(g.pricing()))).reduce((a,b) -> a + "\n" + b).orElse("");
    }
    private static Preview decodeRequest(CanonicalRequest request, Map<String,Denomination> catalog) {
        var fields = request.fields();
        var binding = decodeBinding(fields.get("binding"));
        var slots = fields.entrySet().stream().filter(e -> e.getKey().startsWith("slot_"))
                .map(e -> decodeSlot(e.getValue())).sorted(Comparator.comparingInt(Slot::index)).toList();
        var groups = decodeGroups(fields.get("groups"), binding.currency());
        var payableCurrencies = EnumSet.noneOf(EconomyCurrency.class);
        for (var g : groups) if (!g.skipped()) payableCurrencies.add(g.currency());
        var payable = slots.stream().filter(s -> payableCurrencies.contains(catalog.get(s.claim().item()).currency())).toList();
        var returned = slots.stream().filter(s -> !payableCurrencies.contains(catalog.get(s.claim().item()).currency())).toList();
        var preview = new Preview(binding, slots, groups, new Money(binding.currency(), Long.parseLong(fields.get("credit"))), payable, returned);
        if (!reserveRequest(preview).equals(request)) throw invalid("DEPOSIT_REQUEST");
        return preview;
    }
    private static List<Group> decodeGroups(String value, EconomyCurrency currency) {
        if (value.isEmpty()) return List.of();
        var result = new ArrayList<Group>();
        for (var row : value.split("\n", -1)) {
            var p = row.split("\\|", -1);
            if (p.length != 5 && p.length != 6) throw invalid("DEPOSIT_GROUP");
            var source = EconomyCurrency.valueOf(p[0]);
            result.add(new Group(source, Integer.parseInt(p[1]), new Money(source, Long.parseLong(p[2])),
                    new Money(currency, Long.parseLong(p[3])), Boolean.parseBoolean(p[4]),p.length==6?unpack(p[5]):null));
        }
        if (result.size() > 11) throw invalid("DEPOSIT_GROUP");
        return List.copyOf(result);
    }
    private static String encodePreview(Preview preview) {
        var fields = new ArrayList<String>();
        fields.add(pack(encodeBinding(preview.binding()))); fields.add(Long.toString(preview.credit().minorUnits()));
        fields.add(pack(encodeGroups(preview.groups())));
        fields.add(pack(preview.slots().stream().map(Deposits::encodeSlot).reduce((a,b) -> a + "\n" + b).orElse("")));
        fields.add(preview.payable().stream().map(s -> Integer.toString(s.index())).reduce((a,b)->a+","+b).orElse(""));
        if (priced(preview)) fields.add(PRICED_SETTLEMENT);
        else if (minorSettlement(preview)) fields.add(CHF_SETTLEMENT);
        return String.join(";", fields);
    }
    private static Preview decodePreview(String value) {
        var parts = value.split(";", -1);
        if (parts.length != 5 && parts.length != 6) throw invalid("DEPOSIT_PREVIEW");
        var binding = decodeBinding(unpack(parts[0]));
        var groups = decodeGroups(unpack(parts[2]), binding.currency());
        var slots = Arrays.stream(unpack(parts[3]).split("\n", -1)).map(Deposits::decodeSlot).toList();
        var payableIndexes = parts[4].isEmpty() ? Set.<Integer>of() : Arrays.stream(parts[4].split(",")).map(Integer::valueOf).collect(java.util.stream.Collectors.toSet());
        var preview = new Preview(binding, slots, groups, new Money(binding.currency(), Long.parseLong(parts[1])),
                slots.stream().filter(s -> payableIndexes.contains(s.index())).toList(),
                slots.stream().filter(s -> !payableIndexes.contains(s.index())).toList());
        if (minorSettlement(preview) != (parts.length == 6)
                || parts.length == 6 && !(priced(preview)?PRICED_SETTLEMENT:CHF_SETTLEMENT).equals(parts[5])) throw invalid("DEPOSIT_SETTLEMENT_VERSION");
        return preview;
    }
    private static String encodeReservation(Action action, Reservation value) {
        return action.name() + "|" + value.id() + "|" + actorKey(value.owner()) + "|" + value.state() + "|"
                + pack(encodePreview(value.preview())) + "|" + pack(value.evidence()) + "|" + pack(value.reason()) + "|" + value.revision();
    }
    public static void validateFact(String payload) { validateFact(payload, 1); }
    private static void validateFact(String payload, int version) { decodeFact(payload, version); }
    public static void validateFactV2(String payload) { validateFact(payload, 2); }
    public static void validateFactV3(String payload) { validateFact(payload, 3); }
    /** Immutable retained fact adapter; validation and canonical-version rules remain identical to startup. */
    public record Fact(Action action, Reservation reservation) {}
    public static Fact decodeFact(String payload, int version) {
        var p = payload.split("\\|", -1);
        if (p.length != 8) throw invalid("DEPOSIT_FACT");
        var action = Action.valueOf(p[0]);
        var reservation = new Reservation(UUID.fromString(p[1]), actor(p[2]), State.valueOf(p[3]),
                decodePreview(unpack(p[4])), unpack(p[5]), unpack(p[6]), Long.parseLong(p[7]));
        text(reservation.evidence(),512); text(reservation.reason(),512);
        if (reservation.revision() < 1) throw invalid("DEPOSIT_REVISION");
        if ((priced(reservation.preview())?3:minorSettlement(reservation.preview())?2:1)!=version) throw invalid("DEPOSIT_SETTLEMENT_VERSION");
        if (version>=2 && !encodeReservation(action, reservation).equals(payload)) throw invalid("DEPOSIT_FACT");
        return new Fact(action, reservation);
    }
    private static boolean priced(Preview preview){return preview.groups().stream().anyMatch(g->g.pricing()!=null);}
    private static Map<EconomyCurrency,String> pricing(Preview preview){
        var result=new EnumMap<EconomyCurrency,String>(EconomyCurrency.class);
        for(var group:preview.groups())if(group.pricing()!=null)result.put(group.currency(),group.pricing());
        return result;
    }
    /** CHF-aware groups settle to account minor units; all legacy groups keep their historical quantum. */
    private static long quantum(EconomyCurrency source, EconomyCurrency account) {
        return source == EconomyCurrency.CHF || account == EconomyCurrency.CHF ? 1 : account.minorUnitsPerMajor();
    }
    private static boolean minorSettlement(Preview preview) {
        return priced(preview)||preview.binding().currency() == EconomyCurrency.CHF
                || preview.groups().stream().anyMatch(group -> group.currency() == EconomyCurrency.CHF);
    }
    private static String pack(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String unpack(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > max) throw invalid("DEPOSIT_FIELD");
    }
    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
    private static Decision rejected(Exception error) {
        return reject(error.getMessage() != null && error.getMessage().startsWith("DEPOSIT_") ? error.getMessage() : "DEPOSIT_REQUEST");
    }
    private static Reject reject(String code) { return new Reject(new Result(1,code,"")); }
    private static Decline decline(String code) { return new Decline(new Result(1,code,"")); }
}
