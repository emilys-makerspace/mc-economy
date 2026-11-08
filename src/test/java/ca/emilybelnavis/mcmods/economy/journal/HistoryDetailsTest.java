package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.institution.InstitutionCatalog;
import ca.emilybelnavis.mcmods.economy.pricing.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import ca.emilybelnavis.mcmods.economy.clock.EconomicClock;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

class HistoryDetailsTest {
    @TempDir Path directory;

    @Test void pricedOriginalWithdrawalRetainsExactFeeAndCapturedCashFxWithoutDoubleCountingPricingFact() throws Exception {
        var store = new SqliteStorage(); var player = new PlayerId(UUID.randomUUID());
        UUID account = UUID.randomUUID(), generation = UUID.randomUUID();
        var anchor = new TerminalPolicy.Anchor("minecraft:overworld", 1, 64, 1);
        var bank = new InstitutionCatalog.InstitutionId("economy:bank/crownstone");
        var now = Instant.parse("2026-11-07T12:00:00Z");
        var pricing = new ServicePricing(new WealthValuation());
        String note = "economy:usd_bill_20";
        var terminalCatalog = Map.of(note, new TerminalPolicy.Denomination(USD, true));
        var cashCatalog = Map.of(note, new WithdrawalPlanner.Denomination(note, USD, 2_000, true));
        try {
            store.initialize(directory.toRealPath()); var world = store.snapshot().worldId();
            var opened = new JournalEngine(store, (actor, kind) -> true, Map.of(FxService.OPEN, FxService.opening(Clock.fixed(now, ZoneOffset.UTC))))
                    .submit(intent(player, FxService.OPEN, new Journal.CanonicalRequest(1, Map.of("currency", "CAD"))));
            assertEquals(Journal.Status.COMMITTED, opened.status());
            var publication = store.transaction(tx -> FxService.latest(tx::query));
            store.transaction(tx -> {
                tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)", List.of(account.toString(), player.value().toString()));
                tx.execute("INSERT INTO bank_account_providers VALUES(?,?,0,1,0)", List.of(account.toString(), bank.key()));
                tx.execute("INSERT INTO institution_parties VALUES(?,?,?,?,?,'HYBRID',1)", List.of(world.value().toString(), bank.key(),
                        new InstitutionCatalog.SystemActor(world, bank).actorId().value().toString(), "Fixture bank", "CAD"));
                return null;
            });
            var seedRequest = new SameCurrency.Adjustment(player, new SameCurrency.Revision(new AccountId(account), 1), new Money(CAD, 10_000));
            var seeded = new JournalEngine(store, (actor, kind) -> actor.equals(player),
                    SameCurrency.registrations(world, (intent, instruction, reads) -> true))
                    .submit(intent(player, SameCurrency.CREDIT, seedRequest.request()));
            assertEquals(Journal.Status.COMMITTED, seeded.status());
            var placed = terminal(store, player, TerminalPolicy.Action.PLACE,
                    TerminalPolicy.placeRequest(anchor, generation, player, TerminalPolicy.Kind.TELLER, null, 0), terminalCatalog);
            assertEquals(Journal.Status.COMMITTED, placed.status());
            var bound = new JournalEngine(store, (actor, kind) -> true, Map.of(TerminalBanking.BIND,
                    TerminalBanking.registration(world, terminalCatalog, (a, i, c, r) -> true, (a, r) -> false)))
                    .submit(intent(player, TerminalBanking.BIND, TerminalBanking.bindRequest(anchor, generation, 1,
                            new InstitutionCatalog.InstitutionId("economy:bank/crosswind"), true)));
            assertEquals(Journal.Status.COMMITTED, bound.status());
            var current = store.transaction(tx -> TerminalPolicy.find(tx::query, world, anchor, player, false).orElseThrow());
            assertEquals(Journal.Status.COMMITTED, terminal(store, player, TerminalPolicy.Action.CONFIGURE,
                    TerminalPolicy.configureRequest(anchor, generation, 2, TerminalBanking.settings(current, TerminalPolicy.Mode.BOTH, terminalCatalog)), terminalCatalog).status());
            String fxRevision = publication.day() + "/" + publication.updateId() + "/" + publication.policyId();
            var context = new CashPricing.Context(pricing, world, player, account, now);
            Withdrawals.FxReader fx = reads -> new Withdrawals.FxSnapshot(fxRevision, publication.conversionRates(), context);
            var preview = store.transaction(tx -> Withdrawals.preview(tx::query, world, player,
                    new Withdrawals.Binding(account, 1, CAD, anchor, generation, 3, fxRevision), new Money(USD, 2_000), null, cashCatalog, fx));
            assertNotNull(preview.pricing());
            long capturedFee = Long.parseLong(preview.pricing().split("\\|", -1)[3]);
            var reserve = intent(player, Withdrawals.Action.RESERVE.kind(), Withdrawals.reserveRequest(preview));
            assertEquals(Journal.Status.COMMITTED, withdrawal(store, player, Withdrawals.Action.RESERVE, reserve, cashCatalog, fx).status());
            var committed = withdrawal(store, player, Withdrawals.Action.COMMIT,
                    intent(player, Withdrawals.Action.COMMIT.kind(), Withdrawals.request(reserve.operationId().value(), "fits", "fixture")), cashCatalog, fx);
            assertEquals(Journal.Status.COMMITTED, committed.status(), committed.result().code());
            var owner = new PartyRef(world, new PartyRef.Player(player));
            var detail = store.transaction(tx -> HistoryDetails.read(tx::query, owner, committed.transactionId().value(), committed.sequence().value()).orElseThrow());
            assertEquals(HistoryDetails.Availability.KNOWN, detail.fee().availability());
            assertEquals(new Money(CAD, capturedFee), detail.fee().amount());
            assertEquals(1, detail.fx().size()); assertEquals(publication.updateId(), detail.fx().getFirst().publication());
            assertEquals("CAD", detail.fx().getFirst().source()); assertEquals("USD", detail.fx().getFirst().target());
            assertEquals(HistoryDetails.Availability.KNOWN, detail.fx().getFirst().availability());
            assertTrue(detail.provenance().stream().anyMatch(p -> p.type().equals(Withdrawals.FACT) && p.version() == 3));
            var payload = store.transaction(tx -> tx.query("SELECT payload FROM journal_records WHERE sequence=? AND record_type=?",
                    List.of(Long.toString(committed.sequence().value()), Withdrawals.FACT), 1).getFirst().get("payload"));
            assertEquals(preview, Withdrawals.decodeFact(payload, 3).reservation().preview());
            assertThrows(IllegalArgumentException.class, () -> Withdrawals.decodeFact(payload, 2));
        } finally { store.close(); }
    }

    private static Journal.Outcome terminal(SqliteStorage store, PlayerId player, TerminalPolicy.Action action,
            Journal.CanonicalRequest request, Map<String, TerminalPolicy.Denomination> catalog) {
        return new JournalEngine(store, (actor, kind) -> true, Map.of(action.kind(), TerminalPolicy.registration(action,
                store.snapshot().worldId(), catalog, (a, i, c, r) -> true, (a, r) -> false))).submit(intent(player, action.kind(), request));
    }
    private static Journal.Outcome withdrawal(SqliteStorage store, PlayerId player, Withdrawals.Action action, Journal.Intent intent,
            Map<String, WithdrawalPlanner.Denomination> catalog, Withdrawals.FxReader fx) {
        return new JournalEngine(store, (actor, kind) -> true, Map.of(action.kind(), Withdrawals.registration(action,
                store.snapshot().worldId(), catalog, fx, (a, i, c, r) -> true, (a, i, c, r) -> true, WithdrawalDelivery::prepare))).submit(intent);
    }

    @Test void originalFxFeeAndDirectionSurviveNewRatesWhileOwnerAndMissingProofStayExplicit() throws Exception {
        var player = new PlayerId(UUID.randomUUID()); var source = new AccountId(UUID.randomUUID()); var target = new AccountId(UUID.randomUUID());
        var store = new SqliteStorage();
        Journal.Outcome exchange; UUID publication;
        PartyRef owner;
        String originalUsd;
        var wall = new MutableClock(Instant.parse("2026-11-07T00:00:00Z"));
        try {
            store.initialize(directory.toRealPath()); owner = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(player));
            store.transaction(tx -> {
                tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)", List.of(source.value().toString(), player.value().toString()));
                tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK','USD','OPEN',1,0)", List.of(target.value().toString(), player.value().toString()));
                return null;
            });
            var opened = new JournalEngine(store, (actor, kind) -> actor.equals(player), Map.of(FxService.OPEN, FxService.opening(wall)))
                    .submit(intent(player, FxService.OPEN, new Journal.CanonicalRequest(1, Map.of("currency", "CAD"))));
            assertEquals(Journal.Status.COMMITTED, opened.status());
            var original = store.transaction(tx -> FxService.latestDaily(tx::query));
            publication = original.updateId(); originalUsd = original.rates().get(USD).toPlainString();
            var adjustment = new SameCurrency.Adjustment(player, new SameCurrency.Revision(source, 1), new Money(CAD, 10_000));
            var seed = new JournalEngine(store, (actor, kind) -> actor.equals(player),
                    SameCurrency.registrations(owner.world(), (intent, instruction, reads) -> true))
                    .submit(intent(player, SameCurrency.CREDIT, adjustment.request()));
            assertEquals(Journal.Status.COMMITTED, seed.status());
            exchange = exchange(store, player, source, target, publication, 2_000, 100, 1_000);
            long frozen = exchange.sequence().value();
            var edited = new JournalEngine(store, (actor, kind) -> actor.equals(player), Map.of(FxService.EDIT, FxService.editing(wall)))
                    .submit(intent(player, FxService.EDIT, new Journal.CanonicalRequest(1, Map.of("currency", "USD", "baseline", "4", "variation", "0", "pull", "1000000", "bounds", "0"))));
            assertEquals(Journal.Status.COMMITTED, edited.status(), edited.result().code());
            var clock = new EconomicClock(store, wall, List.of(FxService.definition()), Map.of(FxService.SYSTEM, FxService.deferred()));
            clock.prepare(); wall.now = Instant.parse("2026-11-08T00:00:00Z");
            assertTrue(clock.runBatch(16).ready());
            var later = store.transaction(tx -> FxService.latestDaily(tx::query));
            assertNotEquals(publication, later.updateId());
            assertEquals("4.000000000000", later.rates().get(USD).toPlainString());
            assertNotEquals(originalUsd, later.rates().get(USD).toPlainString());
            var detail = store.transaction(tx -> HistoryDetails.read(tx::query, owner, exchange.transactionId().value(), frozen).orElseThrow());
            assertEquals(exchange.operationId().value(), detail.operation());
            assertEquals(2, detail.movements().size());
            assertEquals(new Money(CAD, 100), detail.fee().amount());
            assertEquals(HistoryDetails.Availability.KNOWN, detail.fee().availability());
            assertEquals(publication, detail.fx().getFirst().publication());
            assertEquals("LEGACY_DAILY", detail.fx().getFirst().family());
            assertEquals("1.000000000000", detail.fx().getFirst().numerator());
            assertEquals(originalUsd, detail.fx().getFirst().denominator());
            assertEquals(HistoryDetails.Visibility.NOT_RECORDED, detail.counterparty().visibility(), "An arbitrary clearing posting is not a counterparty");
            var stranger = new PartyRef(owner.world(), new PartyRef.Player(new PlayerId(UUID.randomUUID())));
            assertTrue(store.transaction(tx -> HistoryDetails.read(tx::query, stranger, exchange.transactionId().value(), frozen)).isEmpty());
            assertTrue(store.transaction(tx -> HistoryDetails.read(tx::query, owner, exchange.transactionId().value(), frozen - 1)).isEmpty());

            UUID unavailablePublication = UUID.randomUUID();
            var missing = exchange(store, player, source, target, unavailablePublication, 100, 0, 100);
            var absent = store.transaction(tx -> HistoryDetails.read(tx::query, owner, missing.transactionId().value(), missing.sequence().value()).orElseThrow());
            assertEquals(new Money(CAD, 0), absent.fee().amount(), "Captured zero fee remains different from no recorded fee");
            assertEquals(unavailablePublication, absent.fx().getFirst().publication());
            assertEquals(HistoryDetails.Availability.UNAVAILABLE, absent.fx().getFirst().availability());
            assertEquals("", absent.fx().getFirst().numerator(), "Missing proof must not use today's rate");
            var uncaptured = store.transaction(tx -> HistoryDetails.read(tx::query, owner, seed.transactionId().value(), seed.sequence().value()).orElseThrow());
            assertEquals(HistoryDetails.Availability.NOT_RECORDED, uncaptured.fee().availability());
        } finally { store.close(); }
        var reopened = new SqliteStorage();
        try {
            reopened.initialize(directory.toRealPath());
            var retained = reopened.transaction(tx -> HistoryDetails.read(tx::query, owner, exchange.transactionId().value(), exchange.sequence().value()).orElseThrow());
            assertEquals(publication, retained.fx().getFirst().publication());
            assertEquals(originalUsd, retained.fx().getFirst().denominator());
        } finally { reopened.close(); }
    }

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    private static Journal.Outcome exchange(SqliteStorage store, PlayerId player, AccountId source, AccountId target,
                                            UUID publication, long debit, long fee, long credit) {
        var quote = new FxQuotes.Quote(UUID.randomUUID(), player, store.snapshot().worldId(), new FxQuotes.Pair(CAD, USD),
                FxQuotes.Side.EXACT_DEBIT, new Money(CAD, debit), publication, 1, Instant.MAX,
                new Money(CAD, fee), new Money(CAD, debit), new Money(USD, credit));
        var registration = FxSettlement.registration(store.snapshot().worldId(), (id, actor, world, reads) -> quote,
                (intent, request, reads) -> true);
        var request = new FxSettlement.Exchange(quote.id(), player, new SameCurrency.Revision(source, 1), new SameCurrency.Revision(target, 1));
        var result = new JournalEngine(store, (actor, kind) -> actor.equals(player), Map.of(FxSettlement.EXCHANGE, registration))
                .submit(intent(player, FxSettlement.EXCHANGE, request.request()));
        assertEquals(Journal.Status.COMMITTED, result.status(), result.result().code()); return result;
    }
    private static Journal.Intent intent(PlayerId player, Journal.Kind kind, Journal.CanonicalRequest request) {
        return new Journal.Intent(new OperationId(UUID.randomUUID()), new RequestId(UUID.randomUUID()), player, kind, request, null, null);
    }
}
