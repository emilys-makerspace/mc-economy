package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.AccountFunds;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity;
import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.FundingEligibility;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.OperationId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.RequestId;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.journal.LedgerHistory;
import ca.emilybelnavis.mcmods.economy.journal.OwnerHistory;
import ca.emilybelnavis.mcmods.economy.banking.BankingDashboardView;
import ca.emilybelnavis.mcmods.economy.banking.BankingHistoryView;
import ca.emilybelnavis.mcmods.economy.journal.SameCurrency;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AccountQueriesTest {
    @TempDir Path world;
    private final PlayerId owner = new PlayerId(UUID.randomUUID());
    private final PlayerId other = new PlayerId(UUID.randomUUID());

    @Test void ownedLookupHidesForeignAndMissingIdsAndRetainsClosedIdentity() throws Exception {
        var store = new SqliteStorage();
        var services = open(store);
        try {
            change(services, store, tx -> {
                insert(tx, owner, 1, "CAD", "CLOSED");
                insert(tx, other, 2, "JPY", "OPEN");
                insert(tx, owner, 4, "EUR", "OPEN");
            });
            var grant = grant(services, owner, owner, false);
            var closed = await(services, services.findOwnedAccount(grant, account(1))).orElseThrow();
            assertEquals(AccountIdentity.Status.CLOSED, closed.status());
            assertEquals(Optional.empty(), await(services, services.findOwnedAccount(grant, account(2))));
            assertEquals(Optional.empty(), await(services, services.findOwnedAccount(grant, account(3))));
            assertEquals(Optional.empty(), await(services, services.accountFunds(grant, account(2))));
            assertEquals(Optional.empty(), await(services, services.accountFunds(grant, account(3))));
            assertEquals(Optional.of(new AccountFunds(EconomyCurrency.EUR, 0, 0, 0)),
                    await(services, services.accountFunds(grant, account(4))));
            assertEquals(Optional.of(FundingEligibility.Status.INSUFFICIENT_FUNDS),
                    await(services, services.evaluateFunding(grant, account(4), 1, new Money(EconomyCurrency.EUR, 1))));
        } finally {
            assertTrue(services.stop(Duration.ofSeconds(5)));
        }
    }

    @Test void keysetPagingBoundsRequestsAndRemainsStableAcrossClosureAndInsert() throws Exception {
        var store = new SqliteStorage();
        var services = open(store);
        try {
            change(services, store, tx -> {
                insert(tx, owner, 1, "CAD", "OPEN");
                insert(tx, owner, 3, "USD", "OPEN");
                insert(tx, other, 2, "JPY", "OPEN");
            });
            var grant = grant(services, owner, owner, false);
            assertThrows(IllegalArgumentException.class, () -> services.listOwnedAccounts(grant, null, 0));
            assertThrows(IllegalArgumentException.class, () ->
                    services.listOwnedAccounts(grant, null, AccountQueries.MAX_PAGE_SIZE + 1));
            assertThrows(IllegalArgumentException.class, () -> services.listOwnedAccounts(grant, "?", 1));
            assertThrows(IllegalArgumentException.class, () -> services.listOwnedAccounts(grant, "a".repeat(4096), 1));
            var first = await(services, services.listOwnedAccounts(grant, null, 1)).orElseThrow();
            assertEquals(List.of(account(1)), first.accounts().stream().map(AccountIdentity::id).toList());
            assertNotNull(first.nextCursor());
            var otherGrant = grant(services, other, other, false);
            assertEquals(Optional.empty(), await(services, services.listOwnedAccounts(otherGrant, first.nextCursor(), 1)));

            change(services, store, tx -> {
                tx.execute("UPDATE account_catalog SET status='CLOSED',revision=revision+1 WHERE account_id=?",
                        List.of(account(1).value().toString()));
                insert(tx, owner, 4, "EUR", "OPEN");
            });
            var second = await(services, services.listOwnedAccounts(grant, first.nextCursor(), 2)).orElseThrow();
            assertEquals(List.of(account(3), account(4)), second.accounts().stream().map(AccountIdentity::id).toList());
            assertNull(second.nextCursor());
        } finally {
            assertTrue(services.stop(Duration.ofSeconds(5)));
        }
    }

    @Test void inspectionAuditsAuthorizedCallsAndRechecksLiveAudienceBeforeDelivery() throws Exception {
        var store = new SqliteStorage();
        var services = open(store);
        try {
            change(services, store, tx -> insert(tx, owner, 1, "CAD", "OPEN"));
            var ordinary = grant(services, owner, owner, false);
            assertEquals(Optional.empty(), await(services, services.inspectAccount(ordinary, account(1), () -> true)));
            assertTrue(audits(services, store).isEmpty());

            var inspection = grant(services, other, owner, true);
            var inspected = await(services, services.inspectAccount(inspection, account(1), () -> true)).orElseThrow();
            assertEquals(account(1), inspected.id());
            var page = await(services, services.inspectAccounts(inspection, null, 1, () -> true)).orElseThrow();
            assertEquals(List.of(account(1)), page.accounts().stream().map(AccountIdentity::id).toList());

            // Keep the live source through the read, then revoke it before delivery.
            var testThread = Thread.currentThread();
            var liveChecks = new AtomicInteger();
            var revoked = services.inspectAccount(inspection, account(1), () -> {
                assertSame(testThread, Thread.currentThread());
                return liveChecks.getAndIncrement() < 2;
            });
            assertEquals(Optional.empty(), await(services, revoked));
            assertEquals(3, liveChecks.get());

            var audit = audits(services, store);
            assertEquals(3, audit.size());
            assertEquals(3, audit.stream().map(Journal.Envelope::operationId).distinct().count());
            for (var envelope : audit) {
                assertEquals(InspectionAudit.KIND, envelope.kind());
                assertEquals(other, envelope.actor());
                assertEquals("player:" + owner.value(), envelope.records().getFirst().payload());
                assertNotNull(envelope.auditInstant().value());
            }
        } finally {
            assertTrue(services.stop(Duration.ofSeconds(5)));
        }
    }

    @Test void ledgerServiceScopesHistoryAndPreservesPaymentReplay() throws Exception {
        var store = new SqliteStorage();
        var services = open(store);
        try {
            change(services, store, tx -> {
                tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                        List.of(account(1).value().toString(), owner.value().toString()));
                tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                        List.of(account(2).value().toString(), other.value().toString()));
            });
            var ledger = services.ledger();
            var grant = grant(services, owner, owner, false);
            var otherGrant = grant(services, other, other, false);
            var inspection = grant(services, other, owner, true);
            var adjustment = new SameCurrency.Adjustment(owner, new SameCurrency.Revision(account(1), 1),
                    new Money(EconomyCurrency.CAD, 10_000));
            var credit = ledger.adjustments(SameCurrency.CREDIT, (actor, kind) -> actor.equals(owner),
                    (intent, instruction, reads) -> true, null);
            var funded = new Journal.Intent(new OperationId(UUID.randomUUID()), new RequestId(UUID.randomUUID()),
                    owner, SameCurrency.CREDIT, adjustment.request(), null, null);
            var fundingOutcome = await(services, credit.submit(funded).completion()).outcome();
            assertEquals(Journal.Status.COMMITTED, fundingOutcome.status());

            var payment = await(services, ledger.planPayment(grant, other, 30)).orElseThrow();
            var operation = new OperationId(UUID.randomUUID());
            var initial = await(services, ledger.submit(grant, operation, new RequestId(UUID.randomUUID()), payment).completion()).outcome();
            assertEquals(Journal.Status.COMMITTED, initial.status());
            assertEquals(initial, await(services, ledger.submit(grant, operation, new RequestId(UUID.randomUUID()), payment).completion()).outcome());
            assertEquals(Optional.of(initial), await(services, ledger.lookup(grant, operation, new RequestId(UUID.randomUUID()), payment)));
            assertThrows(IllegalArgumentException.class, () -> ledger.submit(otherGrant, operation, new RequestId(UUID.randomUUID()), payment));
            assertEquals(7_000, await(services, ledger.primaryFunds(grant)).orElseThrow().funds().availableMinorUnits());

            var visible = await(services, ledger.history(grant, account(1), null, 10)).orElseThrow();
            assertEquals(initial.transactionId(), visible.entries().getLast().transactionId());
            assertTrue(await(services, ledger.history(otherGrant, account(1), null, 10)).isEmpty());
            assertTrue(await(services, ledger.history(inspection, account(1), null, 10)).isEmpty());
            assertEquals(Optional.of(visible), await(services, services.inspectAccounts(inspection,
                    Map.of("ledger_history", account(1).value().toString()), () -> true,
                    reads -> LedgerHistory.page(reads, inspection.target(), account(1), null, 10))));

            // The menu-facing service must freeze pages while new payments continue to commit.
            var query = new OwnerHistory.Query(account(1).value(), null, null, null, OwnerHistory.Type.ALL, 1);
            var first = await(services, ledger.ownerHistory(grant, query, null, null)).orElseThrow();
            assertEquals(OwnerHistory.Availability.AVAILABLE, first.availability());
            assertEquals(List.of(-3_000L), first.rows().stream().map(row -> row.amount().minorUnits()).toList());
            assertNotNull(first.next());
            var nextPayment = await(services, ledger.planPayment(grant, other, 10)).orElseThrow();
            var newer = await(services, ledger.submit(grant, new OperationId(UUID.randomUUID()),
                    new RequestId(UUID.randomUUID()), nextPayment).completion()).outcome();
            assertEquals(Journal.Status.COMMITTED, newer.status());
            var second = await(services, ledger.ownerHistory(grant, query, first.highWater(), first.next())).orElseThrow();
            assertEquals(first.highWater(), second.highWater());
            assertEquals(List.of(10_000L), second.rows().stream().map(row -> row.amount().minorUnits()).toList());
            assertNull(second.next());
            var refreshed = await(services, ledger.ownerHistory(grant, query, null, null)).orElseThrow();
            assertTrue(refreshed.highWater() > first.highWater());
            assertEquals(newer.transactionId().value(), refreshed.rows().getFirst().transaction());
            assertTrue(await(services, ledger.historyDetail(grant, newer.transactionId().value(), first.highWater())).isEmpty());
            var detail = await(services, ledger.historyDetail(grant, initial.transactionId().value(), first.highWater())).orElseThrow();
            assertEquals(List.of(account(1).value()), detail.movements().stream().map(row -> row.account()).toList());

            // Foreign accounts and inspection capabilities cannot become owner-menu authority.
            assertEquals(OwnerHistory.Availability.DENIED,
                    await(services, ledger.ownerHistory(otherGrant, query, null, null)).orElseThrow().availability());
            assertTrue(await(services, ledger.historyDetail(otherGrant, fundingOutcome.transactionId().value(), refreshed.highWater())).isEmpty());
            assertEquals(List.of(account(1)), await(services, ledger.historyAccounts(grant, null)).orElseThrow()
                    .stream().map(AccountIdentity::id).toList());
            assertThrows(IllegalArgumentException.class, () -> ledger.ownerHistory(inspection, query, null, null));
            assertThrows(IllegalArgumentException.class, () -> ledger.historyDetail(inspection, initial.transactionId().value(), first.highWater()));
            assertThrows(IllegalArgumentException.class, () -> ledger.historyAccounts(inspection, null));
            assertThrows(IllegalArgumentException.class, () -> ledger.dashboard(inspection, account(1), null));
            var dashboard = await(services, ledger.dashboard(grant, account(1), null)).orElseThrow();
            assertEquals(new Money(EconomyCurrency.CAD, 6_000), dashboard.accounts().rows().getFirst().available());
            assertEquals(newer.transactionId().value(), dashboard.recent().rows().getFirst().transaction());
            var foreign = await(services, ledger.dashboard(otherGrant, account(1), null)).orElseThrow();
            assertNull(foreign.recentAccount());
            assertEquals(BankingDashboardView.Availability.UNAVAILABLE, foreign.recent().availability());
            assertTrue(foreign.recent().rows().isEmpty());

        } finally {
            assertTrue(services.stop(Duration.ofSeconds(5)));
        }
    }

    @Test void historyAndDashboardRejectCapabilitiesFromAnotherServerSession() throws Exception {
        var services = open(new SqliteStorage());
        try {
            var party = new PartyRef(services.partyWorld(), new PartyRef.Player(owner));
            var stale = PartyAuthorization.admit(services.partyWorld(), UUID.randomUUID(), owner, false, party, false).orElseThrow();
            var ledger = services.ledger();
            var rates = new BankingHistory.RateQuery(BankingHistoryView.Family.EFFECTIVE,
                    EconomyCurrency.CAD, EconomyCurrency.USD, null, null, null);
            assertThrows(IllegalArgumentException.class, () -> ledger.ownerHistory(stale, OwnerHistory.Query.all(), null, null));
            assertThrows(IllegalArgumentException.class, () -> ledger.historyDetail(stale, UUID.randomUUID(), 0));
            assertThrows(IllegalArgumentException.class, () -> ledger.historyAccounts(stale, null));
            assertThrows(IllegalArgumentException.class, () -> ledger.dashboard(stale, null, null));
            assertThrows(IllegalArgumentException.class, () -> ledger.historyRates(stale, rates));
        } finally {
            assertTrue(services.stop(Duration.ofSeconds(5)));
        }
    }

    private ServerServices open(SqliteStorage store) throws Exception {
        var services = new ServerServices(world.toRealPath(), store);
        await(services, services.readiness());
        return services;
    }

    private PartyAuthorization.Grant grant(ServerServices services, PlayerId actor, PlayerId target, boolean inspection) {
        var worldId = services.partyWorld();
        var party = new PartyRef(worldId, new PartyRef.Player(target));
        return PartyAuthorization.admit(worldId, services.sessionId(), actor, inspection, party, inspection).orElseThrow();
    }

    private static void change(ServerServices services, SqliteStorage store,
                               Consumer<FoundationStore.Transaction> fixture) throws Exception {
        await(services, services.storage(() -> store.transaction(tx -> {
            fixture.accept(tx);
            return null;
        })));
    }

    private static List<Journal.Envelope> audits(ServerServices services, SqliteStorage store) throws Exception {
        return await(services, services.storage(() -> new JournalEngine(store, (actor, kind) -> false, Map.of()).readAfter(0, 8)));
    }

    private static void insert(FoundationStore.Transaction tx, PlayerId owner, int serial, String currency, String status) {
        tx.execute("INSERT INTO account_catalog(account_id,owner_id,purpose,currency,status,revision,is_primary) "
                        + "VALUES(?,?,'BANK',?,?,1,0)",
                List.of(account(serial).value().toString(), owner.value().toString(), currency, status));
    }

    private static AccountId account(int serial) {
        return new AccountId(new UUID(0, serial));
    }

    private static <T> T await(ServerServices services, CompletionStage<T> stage) throws Exception {
        var future = stage.toCompletableFuture();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!future.isDone() && System.nanoTime() < deadline) {
            services.tick();
            Thread.sleep(1);
        }
        return future.get(1, TimeUnit.SECONDS);
    }
}
