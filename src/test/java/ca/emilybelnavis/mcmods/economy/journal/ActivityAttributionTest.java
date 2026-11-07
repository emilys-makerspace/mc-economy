package ca.emilybelnavis.mcmods.economy.journal;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.clock.DeadlineDispatcher;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.simulation.activity.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD;
import static ca.emilybelnavis.mcmods.economy.journal.Journal.*;
import static ca.emilybelnavis.mcmods.economy.simulation.activity.ActivityFacts.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real journal/SQLite witnesses for the source-effect and observation transaction boundary. */
class ActivityAttributionTest {
    @TempDir Path path;
    private long now = Instant.parse("2026-11-07T12:00:00Z").toEpochMilli();
    private final Clock wall = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    };
    private final PlayerId buyer = new PlayerId(UUID.randomUUID());
    private final PlayerId seller = new PlayerId(UUID.randomUUID());
    private final AccountId source = new AccountId(UUID.randomUUID());
    private final AccountId target = new AccountId(UUID.randomUUID());

    @Test void productionStartupRegistersCompleteSourceBeforeReadyWithoutInstallingRateProvider() throws Exception {
        var runtime = new ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime(
                ca.emilybelnavis.mcmods.economy.recovery.FoundationRuntime.Configuration.production(wall));
        try {
            runtime.initialize(path.toRealPath());
            boolean ready = false;
            for (int turn = 0; turn < 1_000; turn++) if (runtime.startupStep().ready()) { ready = true; break; }
            assertTrue(ready, "Bounded production startup must finish source registration");
            assertTrue(runtime.<Boolean>transaction(tx -> ActivityStore.ready(tx::query)));
            assertEquals(ActivityFacts.SOURCE_MANIFEST, runtime.transaction(tx -> tx.query(
                    "SELECT sources FROM activity_activation WHERE singleton=1", List.of(), 1).getFirst().get("sources")));
            assertEquals(13, runtime.transaction(tx -> tx.query("SELECT provider FROM financial_deadline_providers", List.of(), 64)).size());
            assertEquals(0, runtime.transaction(tx -> tx.query("SELECT id FROM activity_observations", List.of(), 16)).size());
        } finally { runtime.close(); }
    }

    @Test void captureRefundAndOriginalReplayRemainSingleObservationsAcrossRestart() throws Exception {
        Intent capture;
        Outcome receipt;
        try (var fixture = new ClosingStore()) {
            var store = fixture.store;
            accounts(store);
            observe(store);
            adjust(store, source, buyer, 10_000, true);
            assertEquals(Status.COMMITTED, merchant(store, MerchantPayments.Action.GRANT, seller,
                    MerchantPayments.merchantRequest(seller)).status());
            var invoice = merchant(store, MerchantPayments.Action.OPEN, seller,
                    new MerchantPayments.Terms(buyer, source.value(), target.value(), new Money(CAD, 3_000)).request()).operationId().value();
            assertEquals(Status.COMMITTED, merchant(store, MerchantPayments.Action.AUTHORIZE, buyer,
                    MerchantPayments.invoiceRequest(invoice)).status());
            capture = intent(seller, MerchantPayments.Action.CAPTURE.kind(), MerchantPayments.invoiceRequest(invoice));
            receipt = merchantEngine(store, MerchantPayments.Action.CAPTURE).submit(capture);
            assertEquals(Status.COMMITTED, receipt.status(), receipt.result().code());
            assertEquals(3_000, total(store, GOODS_SALE));
            assertEquals(10_000, total(store, OPERATOR_CREATE));
            assertEquals(7_000, balance(store, source));
        }
        try (var fixture = new ClosingStore()) {
            var store = fixture.store;
            observe(store);
            assertEquals(receipt, merchantEngine(store, MerchantPayments.Action.CAPTURE).submit(capture));
            assertEquals(1, observations(store).stream().filter(o -> o.draft().kind() == GOODS_SALE).count());
            UUID invoice = UUID.fromString(capture.request().fields().get("invoice"));
            assertEquals(Status.COMMITTED, merchant(store, MerchantPayments.Action.REFUND, seller,
                    MerchantPayments.invoiceRequest(invoice)).status());
            assertEquals(3_000, total(store, GOODS_REFUND));
            var refund = observations(store).stream().filter(o -> o.draft().kind() == GOODS_REFUND).findFirst().orElseThrow();
            assertEquals(capture.operationId().value(), refund.draft().reversal());
            assertEquals(10_000, balance(store, source));
            assertEquals(0, total(store, OPERATOR_RETIRE));
        }
    }

    @Test void registerCheckoutCountsOneSaleDespiteItsEmbeddedMerchantCapture() throws Exception {
        try (var fixture = new ClosingStore()) {
            var store = fixture.store;
            accounts(store);
            observe(store);
            adjust(store, source, buyer, 1_000, true);
            UUID generation = UUID.randomUUID(), stock = UUID.randomUUID();
            var anchor = new TerminalPolicy.Anchor("minecraft:overworld", 7, 65, 9);
            String template = "activity-real-service-goods";
            RegisterSales.Source[] inventory = {new RegisterSales.Source(stock, 1, 4, template, true, "before")};
            var place = TerminalPolicy.Action.PLACE;
            var placing = new JournalEngine(store, (a, k) -> true, Map.of(place.kind(), TerminalPolicy.registration(place,
                    store.snapshot().worldId(), Map.of(), (a, i, c, r) -> true, (a, r) -> false)));
            assertEquals(Status.COMMITTED, placing.submit(intent(seller, place.kind(), TerminalPolicy.placeRequest(anchor,
                    generation, seller, TerminalPolicy.Kind.REGISTER, null, 0))).status());
            java.util.function.Function<RegisterSales.Action, JournalEngine> engine = action -> new JournalEngine(store,
                    (a, k) -> true, Map.of(action.kind(), RegisterSales.registration(action, store.snapshot().worldId(),
                    (a, i, c, r) -> true, (a, r) -> true, (a, i, c, r) -> true,
                    (below, requested, reads) -> inventory[0], (a, i, c, proof, r) -> true,
                    RegisterSaleSettlement::checkout,
                    (i, c, uncertain, r) -> RegisterSaleSettlement.returnOriginals(i, c, uncertain, r,
                            (below, requested, reads) -> inventory[0]))));
            assertEquals(Status.COMMITTED, engine.apply(RegisterSales.Action.CONFIGURE).submit(intent(seller,
                    RegisterSales.Action.CONFIGURE.kind(), RegisterSales.configureRequest(anchor, generation, 1, 0,
                            template, 4, new Money(CAD, 100)))).status());
            var quote = store.transaction(tx -> RegisterSales.quote(tx::query, store.snapshot().worldId(), buyer,
                    anchor, generation, 1, (below, requested, reads) -> inventory[0]));
            var reserve = intent(buyer, RegisterSales.Action.RESERVE.kind(), RegisterSales.reserveRequest(quote));
            assertEquals(Status.COMMITTED, engine.apply(RegisterSales.Action.RESERVE).submit(reserve).status());
            UUID sale = reserve.operationId().value();
            inventory[0] = new RegisterSales.Source(stock, 2, 0, template, true, "removed");
            assertEquals(Status.COMMITTED, engine.apply(RegisterSales.Action.SAVED).submit(intent(buyer,
                    RegisterSales.Action.SAVED.kind(), RegisterSales.request(sale, "persisted-stock-proof", "reserved"))).status());
            var committed = engine.apply(RegisterSales.Action.COMMIT).submit(intent(buyer,
                    RegisterSales.Action.COMMIT.kind(), RegisterSales.request(sale, "persisted-stock-proof", "buy")));
            assertEquals(Status.COMMITTED, committed.status());
            assertEquals(900, balance(store, source));
            assertEquals(100, total(store, GOODS_SALE));
            assertEquals(1, observations(store).stream().filter(o -> o.draft().kind() == GOODS_SALE).count());
            assertEquals(0, total(store, OPERATOR_RETIRE));
        }
    }

    @Test void observationWriteFailureRollsBackSourceMoneyAndTheOriginalOperationCanRetry() throws Exception {
        try (var fixture = new ClosingStore()) {
            var store = fixture.store;
            accounts(store);
            observe(store);
            var request = new SameCurrency.Adjustment(buyer, new SameCurrency.Revision(source, 1), new Money(CAD, 500));
            var operation = intent(buyer, SameCurrency.CREDIT, request.request());
            var engine = adjustments(store);
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path.resolve("economy/economy.sqlite"));
                 var sql = connection.createStatement()) {
                sql.execute("CREATE TRIGGER activity_test_failure BEFORE INSERT ON activity_observations BEGIN SELECT RAISE(ABORT,'witness observation failure'); END");
            }
            assertThrows(RuntimeException.class, () -> engine.submit(operation));
            store.recover();
            assertEquals(0, balance(store, source));
            assertEquals(0, total(store, OPERATOR_CREATE));
            assertTrue(engine.lookup(operation).isEmpty());
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path.resolve("economy/economy.sqlite"));
                 var sql = connection.createStatement()) {
                sql.execute("DROP TRIGGER activity_test_failure");
            }
            assertEquals(Status.COMMITTED, engine.submit(operation).status());
            assertEquals(500, balance(store, source));
            assertEquals(500, total(store, OPERATOR_CREATE));
        }
    }

    @Test void backwardWallRetainsAccountingDayAndOriginalTimeWithoutInventingRateWork() throws Exception {
        try (var fixture = new ClosingStore()) {
            var store = fixture.store;
            accounts(store);
            observe(store);
            long original = now;
            now += ActivityFacts.DAY_MILLIS;
            adjust(store, source, buyer, 100, true);
            long retained = now;
            now = original;
            adjust(store, source, buyer, 100, true);
            var observations = observations(store);
            assertEquals(2, observations.size());
            assertEquals(1, observations.getLast().day());
            assertEquals(retained, observations.getLast().accountingMillis());
            assertEquals(original, observations.getLast().draft().effectiveMillis());
            assertEquals(200, total(store, OPERATOR_CREATE));
            assertEquals(0, store.transaction(tx -> tx.query("SELECT provider FROM financial_deadline_providers", List.of(), 16)).size());
        }
    }

    @Test void finiteCapitalSeedsInPagesAndCashChangesUpdateCapacityWithoutCountingCapitalAsActivity() throws Exception {
        try (var fixture = new ClosingStore()) {
            var store = fixture.store;
            accounts(store);
            var opening = new JournalEngine(store, (a, k) -> true, Map.of(FxService.OPEN, FxService.opening(wall)));
            assertEquals(Status.COMMITTED, opening.submit(intent(buyer, FxService.OPEN,
                    new CanonicalRequest(1, Map.of("currency", "CAD")))).status());
            var capital = new InstitutionCapital(store, new SystemActorId(UUID.randomUUID()), CapitalPosting.poster());
            for (int i = 0; i < 26; i++) assertEquals(Status.COMMITTED, capital.advance(wall.instant()).status());
            observe(store);
            assertTrue(observations(store).isEmpty());
            var bank = new InstitutionCatalog.InstitutionId("economy:bank/crownstone");
            var cash = FinancialAccounts.account(store.snapshot().worldId(), bank, FinancialAccounts.Role.CASH, CAD);
            long before = capacity(store, cash.id());
            long totalBefore = capacityTotal(store);
            assertEquals(36_000_000_000L, before);
            var kind = new Kind("fixture:activity", "fixture:bank-cash-transfer");
            var registration = new JournalEngine.Registration(1, (i, reads) -> InstitutionSettlement.prepare(reads,
                    List.of(new PostingBatch.Leg(cash.id(), PostingBatch.Role.INSTITUTION_CASH, CAD, new Money(CAD, -1_000)),
                            new PostingBatch.Leg(source.value(), PostingBatch.Role.ACCOUNT, CAD, new Money(CAD, 1_000))), List.of()));
            var engine = new JournalEngine(store, (a, k) -> true, Map.of(kind, registration));
            assertEquals(Status.COMMITTED, engine.submit(intent(buyer, kind, new CanonicalRequest(1, Map.of()))).status());
            assertEquals(before - 1_000, capacity(store, cash.id()));
            assertEquals(Status.COMMITTED, engine.submit(intent(buyer, kind, new CanonicalRequest(1, Map.of()))).status());
            assertEquals(before - 2_000, capacity(store, cash.id()));
            assertEquals(totalBefore - 2_000, store.transaction(tx -> Long.parseLong(tx.query(
                    "SELECT free_minor FROM activity_capacity_totals_history WHERE currency='CAD' AND accounting_ms<=? ORDER BY accounting_ms DESC,journal_sequence DESC LIMIT 1",
                    List.of(Long.toString(now)), 1).getFirst().get("free_minor"))).longValue());
            assertTrue(observations(store).isEmpty());
        }
    }

    private final class ClosingStore implements AutoCloseable {
        final SqliteStorage store;
        ClosingStore() throws Exception { store = open(); }
        public void close() throws Exception { store.close(); }
    }

    private SqliteStorage open() throws Exception {
        var store = new SqliteStorage();
        store.initialize(path.toRealPath());
        return store;
    }

    private void accounts(SqliteStorage store) {
        store.transaction(tx -> {
            tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)", List.of(source.value().toString(), buyer.value().toString()));
            tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)", List.of(target.value().toString(), seller.value().toString()));
            return null;
        });
    }

    private void observe(SqliteStorage store) {
        new DeadlineDispatcher(store, wall, List.of(), 16).prepare();
        var activity = new ActivityRuntime(store, wall);
        store.commitObserver((i, r, c) -> activity.observe(i, r, c, now));
        for (int turn = 0; turn < 100; turn++) if (activity.prepare()) return;
        fail("Activity initialization did not finish within bounded turns");
    }

    private Outcome adjust(SqliteStorage store, AccountId account, PlayerId owner, long amount, boolean credit) {
        var kind = credit ? SameCurrency.CREDIT : SameCurrency.DEBIT;
        var request = new SameCurrency.Adjustment(owner, new SameCurrency.Revision(account, 1), new Money(CAD, amount));
        var outcome = adjustments(store).submit(intent(owner, kind, request.request()));
        assertEquals(Status.COMMITTED, outcome.status());
        return outcome;
    }

    private JournalEngine adjustments(SqliteStorage store) {
        return new JournalEngine(store, (a, k) -> true, SameCurrency.registrations(store.snapshot().worldId(), (i, instruction, r) -> true));
    }

    private JournalEngine merchantEngine(SqliteStorage store, MerchantPayments.Action action) {
        return new JournalEngine(store, (a, k) -> true, Map.of(action.kind(), MerchantPayments.registration(action,
                (a, actor, merchant, terms, reads) -> true, wall)));
    }

    private Outcome merchant(SqliteStorage store, MerchantPayments.Action action, ActorId actor, CanonicalRequest request) {
        return merchantEngine(store, action).submit(intent(actor, action.kind(), request));
    }

    private static Intent intent(ActorId actor, Kind kind, CanonicalRequest request) {
        return new Intent(new OperationId(UUID.randomUUID()), new RequestId(UUID.randomUUID()), actor, kind, request, null, null);
    }

    private static long balance(SqliteStorage store, AccountId account) {
        return store.transaction(tx -> Settlement.posted(tx::query, account.value(), CAD));
    }

    private static List<ActivityFacts.Observation> observations(SqliteStorage store) {
        return store.transaction(tx -> tx.query("SELECT payload FROM journal_records WHERE record_type=? ORDER BY sequence", List.of(ActivityFacts.BATCH), 1024))
                .stream().flatMap(row -> ActivityFacts.decodeBatch(row.get("payload")).observations().stream()).toList();
    }

    private static long total(SqliteStorage store, ActivityFacts.Kind kind) {
        return store.transaction(tx -> Long.parseLong(tx.query("SELECT COALESCE(sum(minor),0) AS total FROM activity_day_counters WHERE kind=?",
                List.of(kind.name()), 1).getFirst().get("total")));
    }

    private static long capacity(SqliteStorage store, UUID account) {
        return store.transaction(tx -> Long.parseLong(tx.query("SELECT free_minor FROM activity_capacity WHERE account_id=?", List.of(account.toString()), 1).getFirst().get("free_minor")));
    }

    private static long capacityTotal(SqliteStorage store) {
        return store.transaction(tx -> Long.parseLong(tx.query("SELECT free_minor FROM activity_capacity_totals WHERE currency='CAD'", List.of(), 1).getFirst().get("free_minor")));
    }
}
