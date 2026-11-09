package ca.emilybelnavis.mcmods.economy.banking;

import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Presentation of a server operation. Durable journal identity remains in the original intent. */
public final class BankingOperation {
    private BankingOperation() {}
    public enum State { READY, REVIEW, PROCESSING, COMPLETED, DECLINED, EXPIRED_STALE, OUTCOME_UNCERTAIN }
    public enum ArgumentKind { MONEY, CURRENCY, INTEGER, UTC_INSTANT, ID, MESSAGE_KEY }
    /** MONEY uses the exact minor-unit integer in number and the currency code in value. */
    public record Argument(ArgumentKind kind, String value, long number) {
        public Argument {
            Objects.requireNonNull(kind); bounded(value, 128);
            if (kind == ArgumentKind.MONEY || kind == ArgumentKind.CURRENCY)
                ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.fromCode(value);
            if (kind == ArgumentKind.UTC_INSTANT && number < 0) throw new IllegalArgumentException("BANKING_ARGUMENT_TIME");
            if (kind == ArgumentKind.ID) UUID.fromString(value);
            if (kind == ArgumentKind.MESSAGE_KEY && !value.matches("[a-z0-9_.]+")) throw new IllegalArgumentException("BANKING_ARGUMENT_KEY");
        }
    }
    public record View(State state, String reasonKey, List<Argument> args, UUID operationId,
                       UUID requestId, UUID receiptId, UUID reviewId, long expiresAt) {
        public View {
            Objects.requireNonNull(state); bounded(reasonKey, 128);
            if (!reasonKey.matches("[a-z0-9_.]+")) throw new IllegalArgumentException("BANKING_REASON_KEY");
            args = List.copyOf(args);
            if (args.size() > 8 || expiresAt < 0 || (state == State.REVIEW) != (reviewId != null))
                throw new IllegalArgumentException("BANKING_OPERATION_BOUNDS");
        }
        public static View of(State state, String reason) {
            return new View(state, reason, List.of(), null, null, null,
                    state == State.REVIEW ? UUID.randomUUID() : null, 0);
        }
        public static View ready() { return of(State.READY, "screen.economy.operation.ready"); }
        public static View original(State state, UUID operation, UUID request, UUID receipt) {
            return new View(state, "screen.economy.operation." + state.name().toLowerCase(Locale.ROOT),
                    List.of(), operation, request, receipt, null, 0);
        }
        public boolean confirming() { return state == State.REVIEW; }
        public boolean unresolved() { return state == State.PROCESSING || state == State.OUTCOME_UNCERTAIN; }
        public void write(RegistryFriendlyByteBuf b) {
            b.writeEnum(state); b.writeUtf(reasonKey, 128); b.writeVarInt(args.size());
            for (var a : args) { b.writeEnum(a.kind()); b.writeUtf(a.value(), 128); b.writeLong(a.number()); }
            uuid(b, operationId); uuid(b, requestId); uuid(b, receiptId); uuid(b, reviewId); b.writeLong(expiresAt);
        }
        public static View read(RegistryFriendlyByteBuf b) {
            var state = b.readEnum(State.class); var reason = b.readUtf(128); int size = b.readVarInt();
            if (size < 0 || size > 8) throw new IllegalArgumentException("BANKING_ARGUMENTS");
            var args = new ArrayList<Argument>(size);
            for (int i = 0; i < size; i++) args.add(new Argument(b.readEnum(ArgumentKind.class), b.readUtf(128), b.readLong()));
            return new View(state, reason, args, uuid(b), uuid(b), uuid(b), uuid(b), b.readLong());
        }
    }
    public static View outcome(ca.emilybelnavis.mcmods.economy.journal.Journal.Outcome outcome) {
        var state = switch (outcome.status()) {
            case COMMITTED -> State.COMPLETED;
            case DURABLY_REJECTED -> staleCode(outcome.result().code()) ? State.EXPIRED_STALE : State.DECLINED;
            default -> State.OUTCOME_UNCERTAIN;
        };
        return new View(state, "screen.economy.result." + outcome.result().code().toLowerCase(Locale.ROOT),
                List.of(), outcome.operationId().value(), outcome.originalRequestId().value(),
                outcome.transactionId() == null ? null : outcome.transactionId().value(), null, 0);
    }
    private static boolean staleCode(String code) {
        return Set.of("FX_QUOTE_STALE", "FX_QUOTE_CONSUMED", "PRICING_QUOTE_STALE", "ACCOUNT_STALE", "DESTINATION_STALE",
                "FX_PROVIDER_STALE", "QUOTE_EXPIRED", "REVIEW_EXPIRED").contains(code);
    }
    /** Finite control identity, never a translated label or status-text heuristic. */
    public static boolean confirmation(String action) {
        return Set.of("configure_confirm", "deposit_confirm", "withdraw_confirm", "exchange_confirm", "buy_confirm", "account_close_confirm",
                "brand_confirm", "card_confirm", "branch_accept", "finance_credit_confirm", "finance_credit_raise_ok",
                "finance_credit_repl_ok", "finance_credit_pay_ok", "finance_credit_lost_ok", "finance_loan_confirm", "finance_loan_pay_confirm",
                "finance_debt_confirm", "credit_adv_confirm", "auto_confirm").contains(action);
    }
    public static View offered(boolean busy, List<BankingNetwork.Choice> choices) {
        return busy ? View.of(State.PROCESSING, "screen.economy.operation.processing")
                : choices.stream().anyMatch(c -> confirmation(c.action()))
                ? View.of(State.REVIEW, "screen.economy.operation.review") : View.ready();
    }
    private static void bounded(String value, int limit) {
        if (value == null || value.length() > limit || value.getBytes(StandardCharsets.UTF_8).length > limit * 4)
            throw new IllegalArgumentException("BANKING_OPERATION_TEXT");
    }
    private static void uuid(RegistryFriendlyByteBuf b, UUID id) { b.writeBoolean(id != null); if (id != null) b.writeUUID(id); }
    private static UUID uuid(RegistryFriendlyByteBuf b) { return b.readBoolean() ? b.readUUID() : null; }
}
