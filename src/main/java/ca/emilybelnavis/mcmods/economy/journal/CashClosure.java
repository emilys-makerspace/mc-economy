package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.account.AccountLifecycle;
import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.cash.CashCatalog;
import ca.emilybelnavis.mcmods.economy.institution.BranchAdmission;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiPredicate;

/** Native-currency closure payout, issued and protected atomically with account closure. */
public final class CashClosure {
    private CashClosure() {}

    public record Instruction(AccountLifecycle.Close close, Money amount) {
        public Instruction {
            Objects.requireNonNull(close); Objects.requireNonNull(amount);
            if (amount.minorUnits() < 0) throw new IllegalArgumentException("Closure amount");
            if (amount.minorUnits() > 0) plan(amount);
        }
        public Journal.CanonicalRequest request() {
            var fields = new HashMap<>(close.request().fields());
            fields.put("payout", "physical-cash-v1");
            fields.put("currency", amount.currency().code());
            fields.put("amount", Long.toString(amount.minorUnits()));
            return new Journal.CanonicalRequest(1, fields);
        }
        public int stacks() {
            return amount.minorUnits() == 0 ? 0 : plan(amount).lines().stream().mapToInt(l -> (l.count() + 63) / 64).sum();
        }
    }

    public static WithdrawalPlanner.Plan plan(Money amount) {
        var catalog = CashCatalog.all();
        var ids = catalog.stream().map(CashCatalog.Denomination::id).collect(java.util.stream.Collectors.toSet());
        var denominations = catalog.stream().filter(d -> d.currency() == amount.currency())
                .filter(d -> !ids.contains(d.id() + "_horizontal"))
                .map(d -> new WithdrawalPlanner.Denomination("economy:" + d.id(), d.currency(), d.minorUnits(), d.bill())).toList();
        var result = WithdrawalPlanner.plan(amount.currency(), amount.minorUnits(), false, denominations);
        if (result.lines().size() > 24 || result.lines().stream().mapToInt(l -> (l.count() + 63) / 64).sum() > 36)
            throw new IllegalArgumentException("Closure cash does not fit one payout");
        return result;
    }

    public static JournalEngine.Registration registration(PartyRef owner, Instruction instruction,
            FundedClosure.ClaimsReader claims, BranchAdmission.Proof proof,
            BiPredicate<Journal.Intent, JournalEngine.ReadView> authority) {
        return AccountLifecycle.closingAtBranch(owner, instruction.close(), (reads, source) -> claims.read(reads, source).map(state ->
                new AccountLifecycle.ClosureState(Settlement.posted(reads, source.id().value(), source.currency()),
                        Holds.hasActiveClaim(reads, source.id().value()), state.restricted(), state.unresolvedObligation())),
                proof, instruction.request(), (intent, reads) -> authority.test(intent, reads)
                        && AccountCatalog.find(reads, owner, instruction.close().source()).map(source ->
                        source.currency() == instruction.amount().currency() && Settlement.posted(reads, source.id().value(), source.currency())
                                == instruction.amount().minorUnits()).orElse(false),
                (intent, reads) -> payout(owner, instruction, intent, reads));
    }

    private static JournalEngine.Decision payout(PartyRef owner, Instruction instruction, Journal.Intent intent, JournalEngine.ReadView reads) {
        var amount = instruction.amount();
        var legs = List.of(new PostingBatch.Leg(instruction.close().source().value(), PostingBatch.Role.ACCOUNT, amount.currency(), amount.negate()),
                new PostingBatch.Leg(Settlement.issuanceAccount(amount.currency()), PostingBatch.Role.ISSUANCE, amount.currency(), amount));
        var debit = Settlement.prepareMinor(reads, legs, PostingBatch.Authority.REDEEM, null);
        if (!(debit instanceof JournalEngine.Commit financial)) return debit;
        var lots = new ArrayList<CashIssuance.Lot>();
        for (var line : plan(amount).lines()) {
            var id = UUID.nameUUIDFromBytes(("economy:closure:" + intent.operationId().value() + ":" + line.denomination().id()).getBytes(StandardCharsets.UTF_8));
            lots.add(new CashIssuance.Lot(owner.world(), id, line.denomination().id(), line.count(), line.count(), CashIssuance.State.ACTIVE, 1,
                    "closure:" + intent.operationId().value()));
        }
        var issuance = CashIssuance.composeIssuance(owner.world(), lots, financial, reads);
        if (!(issuance instanceof JournalEngine.Commit issued)) return issuance;
        var player = ((PartyRef.Player) owner.owner()).id();
        var custody = Custody.committedDelivery(intent, intent.operationId().value(), player, WithdrawalDelivery.items(lots),
                "closure:" + instruction.close().source().value(), Journal.actorKey(player), reads);
        var records = new ArrayList<>(issued.records()); records.addAll(custody.records());
        return new JournalEngine.Commit(records, issued.result(), tx -> {
            issued.projections().apply(tx); custody.projections().apply(tx);
            for (var lot : lots) tx.execute("INSERT INTO custody_cash_reservations VALUES(?,?,?,'ACTIVE')",
                    List.of(intent.operationId().value().toString(), lot.id().toString(), Integer.toString(lot.issued())));
        });
    }
}
