package ca.emilybelnavis.mcmods.economy.simulation.activity;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.cash.CashCatalog;
import ca.emilybelnavis.mcmods.economy.credit.CreditAuditFacts;
import ca.emilybelnavis.mcmods.economy.credit.CreditFacilities;
import ca.emilybelnavis.mcmods.economy.debt.DebtFinance;
import ca.emilybelnavis.mcmods.economy.debt.DebtLifecycle;
import ca.emilybelnavis.mcmods.economy.debt.DebtRecords;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.loan.LoanDisbursementFx;
import ca.emilybelnavis.mcmods.economy.loan.Loans;
import ca.emilybelnavis.mcmods.economy.trade.settlement.NpcFacts;
import ca.emilybelnavis.mcmods.economy.trade.settlement.ReferenceNpcFacts;
import ca.emilybelnavis.mcmods.economy.trade.cash.ReferenceCashFacts;
import ca.emilybelnavis.mcmods.economy.trade.cash.NpcCashFacts;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

import static ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityFacts.Kind.*;

/** Semantic inputs from trusted prepared commits, never from client event declarations. */
public final class ActivityClassifier {
    private ActivityClassifier() {}

    public static List<ActivityFacts.Draft> classify(Journal.Intent intent, JournalEngine.ReadView reads,
                                                    JournalEngine.Commit commit) {
        var rows = reads.query("SELECT observed_wall_ms FROM financial_calendar WHERE singleton=1", List.of(), 1);
        long effective = rows.isEmpty() ? 0 : number(rows.getFirst().get("observed_wall_ms"));
        return classify(intent, reads, commit, effective);
    }

    /** The runtime supplies its captured trusted financial time for ordinary non-domain commits. */
    public static List<ActivityFacts.Draft> classify(Journal.Intent intent, JournalEngine.ReadView reads,
                                                    JournalEngine.Commit commit, long effectiveMillis) {
        return classify(intent, reads, commit, effectiveMillis, item -> {
            var value = CashCatalog.byId(item).orElseThrow(() -> invalid("Unknown admitted cash denomination"));
            return new Deposits.Denomination(value.currency(), value.minorUnits(), value.bill());
        });
    }

    /** Allows real-service fixtures to supply their authoritative cash catalog without loading item registries. */
    public static List<ActivityFacts.Draft> classify(Journal.Intent intent, JournalEngine.ReadView reads,
                                                    JournalEngine.Commit commit, long effectiveMillis,
                                                    Map<String, Deposits.Denomination> cashCatalog) {
        var catalog = Map.copyOf(cashCatalog);
        return classify(intent, reads, commit, effectiveMillis, item -> {
            var value = catalog.get(item);
            if (value == null) throw invalid("Unknown admitted cash denomination");
            return value;
        });
    }

    private static List<ActivityFacts.Draft> classify(Journal.Intent intent, JournalEngine.ReadView reads,
                                                     JournalEngine.Commit commit, long effectiveMillis,
                                                     Function<String, Deposits.Denomination> cashCatalog) {
        if (effectiveMillis < 0) throw invalid("Negative activity effective time");
        var collector = new Collector(intent, reads, effectiveMillis, cashCatalog);
        collector.prepare(commit.records());
        for (var fact : commit.records()) collector.observe(fact);
        return List.copyOf(collector.result);
    }

    private static final class Collector {
        private final Journal.Intent intent;
        private final JournalEngine.ReadView reads;
        private final long ordinaryEffective;
        private final Function<String, Deposits.Denomination> cashCatalog;
        private final List<ActivityFacts.Draft> result = new ArrayList<>();
        private final Set<UUID> registerSales = new HashSet<>();
        private final Set<UUID> npcTrades = new HashSet<>();
        private boolean npcAdapter;
        private boolean npcCashAdapter;
        private final Map<UUID, Long> captureTimes = new HashMap<>();
        private final Set<UUID> creditPayments = new HashSet<>();
        private final Set<UUID> principalPayments = new HashSet<>();
        private final Map<UUID, Loans.Contract> loans = new HashMap<>();
        private final Map<UUID, CreditFacilities.Facility> cards = new HashMap<>();

        private Collector(Journal.Intent intent, JournalEngine.ReadView reads, long effective,
                          Function<String, Deposits.Denomination> cashCatalog) {
            this.intent = intent;
            this.reads = reads;
            this.ordinaryEffective = effective;
            this.cashCatalog = cashCatalog;
        }

        private void prepare(List<Journal.Record> facts) {
            if(facts.stream().anyMatch(f->f.type().equals(NpcFacts.RECEIPT)||f.type().equals(NpcCashFacts.RECEIPT)||f.type().equals(ReferenceNpcFacts.RECEIPT)||f.type().equals(ReferenceCashFacts.RECEIPT))&&facts.stream().anyMatch(f->f.type().equals(FxSettlement.FACT)))
                throw invalid("NPC FX semantic source cannot be duplicated");
            for (var fact : facts) {
                switch (fact.type()) {
                    case NpcCashFacts.ADAPTER -> {
                        version(fact,1);var adapter=NpcCashFacts.decodeAdapter(fact.payload());
                        var state=ActivityStore.activation(reads);
                        if(state==null||!state.world().equals(adapter.world())||npcCashAdapter)throw invalid("NPC cash activity adapter identity");
                        npcCashAdapter=true;
                    }
                    case NpcFacts.ADAPTER -> {
                        version(fact,1);var adapter=NpcFacts.decodeAdapter(fact.payload());
                        var state=ActivityStore.activation(reads);
                        if(state==null||!state.world().equals(adapter.world())||npcAdapter)throw invalid("NPC activity adapter identity");
                        npcAdapter=true;
                    }
                    case ReferenceCashFacts.ADAPTER -> {
                        version(fact,1);var adapter=ReferenceCashFacts.decodeAdapter(fact.payload());
                        var state=ActivityStore.activation(reads);
                        if(state==null||!state.world().equals(adapter.world())||npcCashAdapter)throw invalid("NPC cash activity adapter identity");
                        npcCashAdapter=true;
                    }
                    case ReferenceNpcFacts.ADAPTER -> {
                        version(fact,1);var adapter=ReferenceNpcFacts.decodeAdapter(fact.payload());
                        var state=ActivityStore.activation(reads);
                        if(state==null||!state.world().equals(adapter.world())||npcAdapter)throw invalid("NPC activity adapter identity");
                        npcAdapter=true;
                    }
                    case Loans.FACT -> {
                        version(fact, 1, 2);
                        var loan = Loans.decode(fact.payload());
                        if (loans.putIfAbsent(loan.id(), loan) != null) throw invalid("Repeated loan snapshot");
                    }
                    case CreditFacilities.FACT -> {
                        version(fact, 1);
                        var card = CreditFacilities.decodeSnapshot(fact.payload());
                        if (cards.putIfAbsent(card.id(), card) != null) throw invalid("Repeated facility snapshot");
                    }
                    case CreditAuditFacts.PAYMENT -> {
                        version(fact, 1);
                        var payment = CreditAuditFacts.decodePayment(fact.payload());
                        if (!creditPayments.add(payment.facility())) throw invalid("Repeated card payment allocation");
                    }
                    case MerchantPayments.FACT -> {
                        version(fact, 1);
                        MerchantPayments.validateFact(fact.payload());
                        var fields = fact.payload().split("\\|", -1);
                        if (fields[0].equals("CAPTURE")) captureTimes.put(uuid(fields[1]), number(fields[2]));
                    }
                    case RegisterSales.FACT -> {
                        version(fact, 1);
                        RegisterSales.validateFact(fact.payload());
                        var fields = fields(fact.payload(), 2);
                        if (fields[0].equals("COMMIT")) registerSales.add(uuid(fields(unpack(fields[1]), 8)[0]));
                    }
                    default -> { }
                }
            }
        }

        private void observe(Journal.Record fact) {
            switch (fact.type()) {
                case NpcFacts.RECEIPT -> npc(fact);
                case NpcCashFacts.RECEIPT -> npcCash(fact);
                case ReferenceNpcFacts.RECEIPT -> referenceNpc(fact);
                case ReferenceCashFacts.RECEIPT -> referenceCash(fact);
                case RegisterSales.FACT -> register(fact);
                case MerchantPayments.FACT -> merchant(fact);
                case Loans.EVENT_FACT -> loan(fact);
                case CreditFacilities.EVENT_FACT -> card(fact);
                case CreditAuditFacts.PAYMENT -> cardPayment(fact);
                case DebtFinance.ACCOUNTING_FACT -> collection(fact);
                case FxSettlement.FACT -> fx(fact);
                case LoanDisbursementFx.FACT -> loanFx(fact);
                case Deposits.FACT -> deposit(fact);
                case Withdrawals.FACT -> withdrawal(fact);
                case CashIssuance.FACT -> version(fact, 1); // Serial conservation is not itself monetary creation.
                case "economy:posting" -> operator(fact);
                default -> { }
            }
        }

        private void npcCash(Journal.Record fact) {
            version(fact,1);var trade=NpcCashFacts.decodeReceipt(fact.payload());
            var state=ActivityStore.activation(reads);
            if(state==null||!state.ready()||!state.world().equals(trade.world())
                    ||!trade.operation().equals(intent.operationId().value())||!trade.actor().equals(intent.actor())
                    ||!npcTrades.add(trade.operation()))throw invalid("NPC cash activity original identity");
            if(!npcCashAdapter) {
                var rows=reads.query("SELECT payload FROM journal_records WHERE record_type=? AND record_version=1 LIMIT 1",List.of(NpcCashFacts.ADAPTER),1);
                if(rows.isEmpty()||!NpcCashFacts.decodeAdapter(rows.getFirst().get("payload")).world().equals(state.world()))throw invalid("NPC cash activity adapter unavailable");
            }
            add("npc-cash",1,GOODS_SALE,trade.currency(),trade.goodsPrincipal(),trade.trader(),trade.effectiveMillis(),null,null);
            add("npc-cash",1,trade.side()==ca.emilybelnavis.mcmods.economy.trade.pricing.Pricing.Side.BUY?NPC_RETIRE:NPC_CREATE,
                    trade.currency(),trade.goodsPrincipal(),trade.trader(),trade.effectiveMillis(),null,null);
            if(trade.operatorCashMinor()>0) add("npc-cash",1,OPERATOR_CASH_CREATE,trade.cashCurrency(),trade.operatorCashMinor(),trade.inputCustody(),trade.effectiveMillis(),null,null);
            if(trade.publication()!=null) {
                add("npc-cash",1,FX_SELL,trade.fxSourceCurrency(),trade.fxSourcePrincipal(),trade.quote(),trade.effectiveMillis(),null,trade.publication());
                add("npc-cash",1,FX_BUY,trade.fxTargetCurrency(),trade.fxTargetPrincipal(),trade.quote(),trade.effectiveMillis(),null,trade.publication());
            }
        }

        private void npc(Journal.Record fact) {
            version(fact,1);var trade=NpcFacts.decodeReceipt(fact.payload());
            var state=ActivityStore.activation(reads);
            if(state==null||!state.ready()||!state.world().equals(trade.world())
                    ||!trade.operation().equals(intent.operationId().value())||!trade.actor().equals(intent.actor())
                    ||!npcTrades.add(trade.operation()))throw invalid("NPC activity original identity");
            if(!npcAdapter) {
                var rows=reads.query("SELECT payload FROM journal_records WHERE record_type=? AND record_version=1 LIMIT 1",List.of(NpcFacts.ADAPTER),1);
                if(rows.isEmpty()||!NpcFacts.decodeAdapter(rows.getFirst().get("payload")).world().equals(state.world()))throw invalid("NPC activity adapter unavailable");
            }
            add("npc-account",1,GOODS_SALE,trade.currency(),trade.goodsPrincipal(),trade.trader(),trade.effectiveMillis(),null,null);
            add("npc-account",1,trade.side()==ca.emilybelnavis.mcmods.economy.trade.pricing.Pricing.Side.BUY?NPC_RETIRE:NPC_CREATE,
                    trade.currency(),trade.goodsPrincipal(),trade.trader(),trade.effectiveMillis(),null,null);
            if(trade.publication()!=null) {
                add("npc-account",1,FX_SELL,trade.fxSourceCurrency(),trade.fxSourcePrincipal(),trade.quote(),trade.effectiveMillis(),null,trade.publication());
                add("npc-account",1,FX_BUY,trade.fxTargetCurrency(),trade.fxTargetPrincipal(),trade.quote(),trade.effectiveMillis(),null,trade.publication());
            }
        }

        private void referenceCash(Journal.Record fact) {
            version(fact,1);var trade=ReferenceCashFacts.decodeReceipt(fact.payload());
            var state=ActivityStore.activation(reads);
            if(state==null||!state.ready()||!state.world().equals(trade.world())
                    ||!trade.operation().equals(intent.operationId().value())||!trade.actor().equals(intent.actor())
                    ||!npcTrades.add(trade.operation()))throw invalid("NPC cash activity original identity");
            if(!npcCashAdapter) {
                var rows=reads.query("SELECT payload FROM journal_records WHERE record_type=? AND record_version=1 LIMIT 1",List.of(ReferenceCashFacts.ADAPTER),1);
                if(rows.isEmpty()||!ReferenceCashFacts.decodeAdapter(rows.getFirst().get("payload")).world().equals(state.world()))throw invalid("NPC cash activity adapter unavailable");
            }
            add("npc-cash",1,GOODS_SALE,trade.currency(),trade.goodsPrincipal(),trade.trader(),trade.effectiveMillis(),null,null);
            add("npc-cash",1,trade.side()==ca.emilybelnavis.mcmods.economy.trade.pricing.Pricing.Side.BUY?NPC_RETIRE:NPC_CREATE,
                    trade.currency(),trade.goodsPrincipal(),trade.trader(),trade.effectiveMillis(),null,null);
            if(trade.operatorCashMinor()>0) add("npc-cash",1,OPERATOR_CASH_CREATE,trade.cashCurrency(),trade.operatorCashMinor(),trade.inputCustody(),trade.effectiveMillis(),null,null);
            if(trade.publication()!=null) {
                add("npc-cash",1,FX_SELL,trade.fxSourceCurrency(),trade.fxSourcePrincipal(),trade.quote(),trade.effectiveMillis(),null,trade.publication());
                add("npc-cash",1,FX_BUY,trade.fxTargetCurrency(),trade.fxTargetPrincipal(),trade.quote(),trade.effectiveMillis(),null,trade.publication());
            }
        }

        private void referenceNpc(Journal.Record fact) {
            version(fact,1);var trade=ReferenceNpcFacts.decodeReceipt(fact.payload());
            var state=ActivityStore.activation(reads);
            if(state==null||!state.ready()||!state.world().equals(trade.world())
                    ||!trade.operation().equals(intent.operationId().value())||!trade.actor().equals(intent.actor())
                    ||!npcTrades.add(trade.operation()))throw invalid("NPC activity original identity");
            if(!npcAdapter) {
                var rows=reads.query("SELECT payload FROM journal_records WHERE record_type=? AND record_version=1 LIMIT 1",List.of(ReferenceNpcFacts.ADAPTER),1);
                if(rows.isEmpty()||!ReferenceNpcFacts.decodeAdapter(rows.getFirst().get("payload")).world().equals(state.world()))throw invalid("NPC activity adapter unavailable");
            }
            add("npc-account",1,GOODS_SALE,trade.currency(),trade.goodsPrincipal(),trade.trader(),trade.effectiveMillis(),null,null);
            add("npc-account",1,trade.side()==ca.emilybelnavis.mcmods.economy.trade.pricing.Pricing.Side.BUY?NPC_RETIRE:NPC_CREATE,
                    trade.currency(),trade.goodsPrincipal(),trade.trader(),trade.effectiveMillis(),null,null);
            if(trade.publication()!=null) {
                add("npc-account",1,FX_SELL,trade.fxSourceCurrency(),trade.fxSourcePrincipal(),trade.quote(),trade.effectiveMillis(),null,trade.publication());
                add("npc-account",1,FX_BUY,trade.fxTargetCurrency(),trade.fxTargetPrincipal(),trade.quote(),trade.effectiveMillis(),null,trade.publication());
            }
        }

        private void register(Journal.Record fact) {
            var outer = fields(fact.payload(), 2);
            if (!outer[0].equals("COMMIT")) return;
            var reservation = fields(unpack(outer[1]), 8);
            var quote = fields(unpack(reservation[3]), 5);
            var price = fields(unpack(quote[0]), 12);
            UUID id = uuid(reservation[0]);
            add("register", 1, GOODS_SALE, currency(price[7]), number(price[11]), id,
                    captureTimes.getOrDefault(id, ordinaryEffective), null, null);
        }

        private void merchant(Journal.Record fact) {
            version(fact, 1);
            MerchantPayments.validateFact(fact.payload());
            var fields = fact.payload().split("\\|", -1);
            boolean refund = fields[0].equals("REFUND");
            if (!refund && !fields[0].equals("CAPTURE")) return;
            UUID invoice = uuid(fields[1]);
            if (!refund && registerSales.contains(invoice)) return;
            var invoices = reads.query("SELECT currency,units FROM payment_invoices WHERE id=?", List.of(invoice.toString()), 1);
            if (invoices.size() != 1) throw invalid("Missing captured invoice terms");
            UUID reversal = null;
            if (refund) {
                var receipts = reads.query("SELECT operation_id FROM payment_receipts WHERE invoice_id=? AND action='CAPTURE'",
                        List.of(invoice.toString()), 1);
                if (receipts.size() != 1) throw invalid("Missing original merchant capture");
                reversal = uuid(receipts.getFirst().get("operation_id"));
            }
            var terms = invoices.getFirst();
            add("merchant", 1, refund ? GOODS_REFUND : GOODS_SALE, currency(terms.get("currency")),
                    number(terms.get("units")), invoice, number(fields[2]), reversal, null);
        }

        private void loan(Journal.Record fact) {
            version(fact, 1);
            var event = event(fact.payload());
            if (!event.kind.equals("ORIGINATED") && !event.kind.equals("PAYMENT")) return;
            var loan = loans.get(event.subject);
            if (loan == null) throw invalid("Missing loan activity snapshot");
            var details = event.details.split("\\|", -1);
            long principal = number(details[1]);
            if (event.kind.equals("PAYMENT") && !principalPayments.add(event.subject)) throw invalid("Repeated loan principal payment");
            add("loan", 1, event.kind.equals("ORIGINATED") ? CREDIT_DRAW : CREDIT_REPAYMENT,
                    loan.currency(), principal, event.subject, event.effective, null, null);
        }

        private void card(Journal.Record fact) {
            version(fact, 1);
            var event = event(fact.payload());
            boolean draw = event.kind.equals("DRAW_PURCHASE") || event.kind.equals("DRAW_ADVANCE");
            boolean repay = event.kind.equals("REPAYMENT");
            if (!draw && !repay || repay && creditPayments.contains(event.subject)) return;
            var card = cards.get(event.subject);
            if (card == null) throw invalid("Missing credit activity snapshot");
            var details = event.details.split("\\|", -1);
            long principal = number(details[draw ? 0 : 1]);
            if (repay && !principalPayments.add(event.subject)) throw invalid("Repeated card principal payment");
            add("credit", 1, draw ? CREDIT_DRAW : CREDIT_REPAYMENT, card.currency(), principal,
                    event.subject, event.effective, null, null);
        }

        private void cardPayment(Journal.Record fact) {
            var payment = CreditAuditFacts.decodePayment(fact.payload());
            if (!principalPayments.add(payment.facility())) throw invalid("Repeated card principal payment");
            add("credit", 1, CREDIT_REPAYMENT, payment.currency(), payment.principalMinor(), payment.facility(),
                    payment.effective(), null, null);
        }

        private void collection(Journal.Record fact) {
            version(fact, 1);
            DebtFinance.validateAccounting(fact.payload());
            var fields = fields(fact.payload(), 6);
            if (!fields[2].equals("PAYMENT")) return;
            var debt = DebtLifecycle.findCase(reads, uuid(fields[1])).orElseThrow(() -> invalid("Missing collection case"));
            if (!principalPayments.add(debt.facility())) throw invalid("Repeated collection principal payment");
            long before;
            long after;
            if (debt.kind() == DebtRecords.Kind.LOAN) {
                before = Loans.find(reads, debt.facility()).orElseThrow().servicing().principalMinor();
                var loan = loans.get(debt.facility());
                if (loan == null) throw invalid("Missing collection loan snapshot");
                after = loan.servicing().principalMinor();
            } else {
                before = CreditFacilities.find(reads, debt.facility()).orElseThrow().principalMinor();
                var card = cards.get(debt.facility());
                if (card == null) throw invalid("Missing collection card snapshot");
                after = card.principalMinor();
            }
            long principal = Math.subtractExact(before, after);
            add("debt", 1, CREDIT_REPAYMENT, debt.currency(), principal, debt.facility(), number(fields[3]), null, null);
        }

        private void fx(Journal.Record fact) {
            version(fact, 1, 2, 3);
            if (fact.version() == 1) FxSettlement.validateFact(fact.payload());
            else if (fact.version() == 2) FxSettlement.validateFactV2(fact.payload());
            else FxSettlement.validateFactV3(fact.payload());
            var fields = fields(fact.payload(), 10);
            exchange("fx", fact.version(), uuid(fields[0]), uuid(fields[1]), currency(fields[3]), number(fields[6]),
                    currency(fields[4]), number(fields[9]), ordinaryEffective);
        }

        private void loanFx(Journal.Record fact) {
            version(fact, 1);
            var fields = fields(fact.payload(), 15);
            for (int index : new int[]{0, 1, 2, 3, 4, 14}) uuid(fields[index]);
            number(fields[9]); number(fields[10]); currency(fields[11]);
            var loan = loans.get(uuid(fields[2]));
            exchange("fx", 1, uuid(fields[0]), uuid(fields[4]), currency(fields[5]), number(fields[6]),
                    currency(fields[7]), number(fields[8]), loan == null ? ordinaryEffective : loan.anchor());
        }

        private void deposit(Journal.Record fact) {
            version(fact, 1, 2, 3);
            if (fact.version() == 1) Deposits.validateFact(fact.payload());
            else if (fact.version() == 2) Deposits.validateFactV2(fact.payload());
            else Deposits.validateFactV3(fact.payload());
            var fields = fields(fact.payload(), 8);
            if (!fields[0].equals("COMMIT")) return;
            UUID id = uuid(fields[1]);
            var reservation = Deposits.find(reads, intent.actor(), id).orElseThrow(() -> invalid("Missing committed deposit"));
            var preview = reservation.preview();
            var unmarked = new EnumMap<EconomyCurrency, Long>(EconomyCurrency.class);
            for (var slot : preview.payable()) if (slot.unmarked()) {
                var denomination = cashCatalog.apply(slot.claim().item());
                long amount = Math.multiplyExact(denomination.minorUnits(), slot.claim().count());
                unmarked.merge(denomination.currency(), amount, Math::addExact);
            }
            for (var entry : unmarked.entrySet()) add("cash", fact.version(), OPERATOR_CASH_CREATE,
                    entry.getKey(), entry.getValue(), id, ordinaryEffective, null, null);
            for (var group : preview.groups()) if (!group.skipped() && group.currency() != preview.binding().currency()) {
                long principal = group.pricing() == null ? group.subtotal().minorUnits() : number(fields(group.pricing(), 5)[1]);
                exchange("fx", fact.version(), id, publication(preview.binding().fxRevision()), group.currency(), principal,
                        preview.binding().currency(), group.credit().minorUnits(), ordinaryEffective);
            }
        }

        private void withdrawal(Journal.Record fact) {
            version(fact, 1, 2, 3);
            if (fact.version() == 1) Withdrawals.validateFact(fact.payload());
            else if (fact.version() == 2) Withdrawals.validateFactV2(fact.payload());
            else Withdrawals.validateFactV3(fact.payload());
            var fields = fields(fact.payload(), 8);
            if (!fields[0].equals("COMMIT")) return;
            UUID id = uuid(fields[1]);
            var preview = Withdrawals.find(reads, intent.actor(), id)
                    .orElseThrow(() -> invalid("Missing committed withdrawal")).preview();
            if (preview.binding().currency() == preview.cash().currency()) return;
            long principal = preview.pricing() == null ? preview.debit().minorUnits() : number(fields(preview.pricing(), 5)[1]);
            exchange("fx", fact.version(), id, publication(preview.binding().fxRevision()), preview.binding().currency(),
                    principal, preview.cash().currency(), preview.cash().minorUnits(), ordinaryEffective);
        }

        private void operator(Journal.Record fact) {
            if (!intent.kind().equals(SameCurrency.CREDIT) && !intent.kind().equals(SameCurrency.DEBIT)) return;
            version(fact, 1, 2, 3);
            var fields = fields(fact.payload(), 5);
            if (!fields[1].equals("ACCOUNT")) return;
            boolean issue = intent.kind().equals(SameCurrency.CREDIT);
            if (!fields[4].equals(issue ? "ISSUE" : "REDEEM")) throw invalid("Operator adjustment posting authority");
            long signed = signedNumber(fields[3]);
            long amount = issue ? signed : Math.negateExact(signed);
            if (amount <= 0) throw invalid("Operator adjustment sign");
            add("operator", fact.version(), issue ? OPERATOR_CREATE : OPERATOR_RETIRE, currency(fields[2]), amount,
                    uuid(fields[0]), ordinaryEffective, null, null);
        }

        private void exchange(String source, int version, UUID subject, UUID publication, EconomyCurrency sold,
                              long debit, EconomyCurrency bought, long credit, long effective) {
            if (sold == bought) throw invalid("Same-currency exchange observation");
            add(source, version, FX_SELL, sold, debit, subject, effective, null, publication);
            add(source, version, FX_BUY, bought, credit, subject, effective, null, publication);
        }

        private void add(String source, int version, ActivityFacts.Kind kind, EconomyCurrency currency, long amount,
                         UUID subject, long effective, UUID reversal, UUID publication) {
            if (amount < 0) throw invalid("Negative semantic activity principal");
            if (amount == 0) return;
            if (result.size() >= 32) throw invalid("Activity observation bound");
            result.add(new ActivityFacts.Draft(source, version, kind, currency, amount, subject, effective, reversal, publication));
        }
    }

    private record Event(String kind, UUID subject, long effective, String details) {}

    private static Event event(String payload) {
        var fields = fields(payload, 4);
        String kind = unpack(fields[0]);
        if (!kind.matches("[A-Z][A-Z0-9_]{0,63}")) throw invalid("Activity event kind");
        String detail = unpack(fields[3]);
        if (detail.length() > 4096) throw invalid("Activity event detail bound");
        return new Event(kind, uuid(unpack(fields[1])), number(unpack(fields[2])), detail);
    }

    private static void version(Journal.Record fact, int... supported) {
        for (int version : supported) if (fact.version() == version) return;
        throw invalid("Unsupported activity source version: " + fact.type() + "/" + fact.version());
    }

    private static String[] fields(String payload, int size) {
        var values = payload.split("\\|", -1);
        if (values.length != size) throw invalid("Activity source field count");
        return values;
    }

    private static String unpack(String encoded) {
        var bytes = Base64.getUrlDecoder().decode(encoded);
        var value = new String(bytes, StandardCharsets.UTF_8);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)).equals(encoded))
            throw invalid("Noncanonical activity source encoding");
        return value;
    }

    private static UUID uuid(String text) {
        var id = UUID.fromString(text);
        if (!id.toString().equals(text)) throw invalid("Noncanonical activity source UUID");
        return id;
    }

    private static UUID publication(String revision) {
        var fields = revision.split("/", -1);
        return uuid(fields.length == 3 ? fields[1] : revision);
    }

    private static long number(String text) {
        long value = signedNumber(text);
        if (value < 0) throw invalid("Negative activity source amount/time");
        return value;
    }

    private static long signedNumber(String text) {
        long value = Long.parseLong(text);
        if (!Long.toString(value).equals(text)) throw invalid("Noncanonical activity source number");
        return value;
    }

    private static EconomyCurrency currency(String value) { return EconomyCurrency.valueOf(value); }
    private static IllegalArgumentException invalid(String detail) { return new IllegalArgumentException(detail); }
}
