package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.Money;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.banking.BankingDashboardView.Availability;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.CAD;
import static org.junit.jupiter.api.Assertions.*;

class BankingDashboardTest {
    @TempDir Path world;

    @Test void oneReadCapturesOwnerFundsAndFourNewestOriginalMovementsWithoutScanningHistory() throws Exception {
        var actor = new PlayerId(UUID.randomUUID());
        var stranger = new PlayerId(UUID.randomUUID());
        var account = new AccountId(UUID.randomUUID());
        var destination = new AccountId(UUID.randomUUID());
        var store = new SqliteStorage();
        UUID worldId;
        try {
            store.initialize(world.toRealPath());
            worldId = store.snapshot().worldId().value();
            var owner = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(actor));
            store.transaction(tx -> {
                tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,0)",
                        List.of(account.value().toString(), actor.value().toString()));
                tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                        List.of(destination.value().toString(), stranger.value().toString()));
                return null;
            });
            var kind = new Journal.Kind("test:dashboard", "test:post");
            var engine = new JournalEngine(store, (who, requested) -> who.equals(actor), Map.of(kind,
                    Settlement.registration((intent, legs, reads) -> legs.stream().anyMatch(
                            leg -> leg.role() == PostingBatch.Role.ISSUANCE)
                            ? PostingBatch.Authority.ISSUE : PostingBatch.Authority.TRANSFER)));
            post(engine, kind, actor, leg(account, 10_000), new PostingBatch.Leg(Settlement.issuanceAccount(CAD),
                    PostingBatch.Role.ISSUANCE, CAD, new Money(CAD, -10_000)));
            var transfers = new ArrayList<Journal.Outcome>();
            for (int i = 1; i <= 5; i++) transfers.add(post(engine, kind, actor, leg(account, -100L * i), leg(destination, 100L * i)));
            long capturedAt = System.currentTimeMillis();
            store.transaction(tx -> {
                var view = BankingDashboard.read(tx::query, owner, account, null, capturedAt);
                assertEquals(owner.world().value(), view.world());
                assertEquals(capturedAt, view.capturedAt());
                assertEquals(account.value(), view.recentAccount());
                assertEquals(new Money(CAD, 8_500), view.accounts().rows().getFirst().available());
                assertEquals(List.of(-500L, -400L, -300L, -200L),
                        view.recent().rows().stream().map(r -> r.amount().minorUnits()).toList());
                assertEquals(transfers.getLast().transactionId().value(), view.recent().rows().getFirst().transaction());
                assertTrue(view.recent().hasMore());
                assertEquals(Availability.AVAILABLE, view.credit().availability());
                assertTrue(view.credit().rows().isEmpty());
                assertEquals(Availability.AVAILABLE, view.loans().availability());
                assertTrue(view.upcoming().rows().isEmpty());

                var terminalHistory = TerminalMenus.detail(tx::query, owner, "", "bank/" + account.value(), 0, capturedAt);
                assertEquals("account_overview", terminalHistory.page());
                assertEquals(9, terminalHistory.lines().size(), "Three balances, five transactions and page counter");
                assertEquals("Page 1 of 2", terminalHistory.lines().getLast());
                assertEquals("terminal_history_next", terminalHistory.choices().getFirst().action());
                var secondPage = TerminalMenus.detail(tx::query, owner, "", "bank/" + account.value(), 1, capturedAt);
                assertEquals("Page 2 of 2", secondPage.lines().getLast());
                assertEquals("terminal_history_prev", secondPage.choices().getFirst().action());
                assertThrows(java.util.NoSuchElementException.class, () -> TerminalMenus.detail(tx::query,
                        new PartyRef(owner.world(), new PartyRef.Player(stranger)), "", "bank/" + account.value(), 0, capturedAt));
                tx.execute("INSERT INTO bank_account_providers VALUES(?,'economy:bank/crownstone',0,1,0)", List.of(account.value().toString()));
                var terminalAccounts = TerminalMenus.accounts(tx::query, owner, "economy:bank/crownstone", false, 0);
                assertEquals(1, terminalAccounts.choices().size());
                assertEquals("terminal_account", terminalAccounts.choices().getFirst().action());
                assertTrue(TerminalMenus.accounts(tx::query, owner, "economy:bank/other", false, 0).choices().isEmpty());
                new ca.emilybelnavis.mcmods.economy.banking.BankingNetwork.Action(1, UUID.randomUUID(), 1,
                        terminalAccounts.choices().getFirst().action(), terminalAccounts.choices().getFirst().value());

                var denied = BankingDashboard.read(tx::query, owner, destination, null, capturedAt);
                assertNull(denied.recentAccount());
                assertEquals(Availability.UNAVAILABLE, denied.recent().availability());
                assertTrue(denied.recent().rows().isEmpty());

                return null;
            });
            store.transaction(tx -> {
                tx.execute("UPDATE account_catalog SET status='CLOSED',revision=2 WHERE account_id=?", List.of(account.value().toString()));
                var retained = BankingDashboard.read(tx::query, owner, account, null, capturedAt);
                assertTrue(retained.accounts().rows().isEmpty());
                assertEquals(4, retained.recent().rows().size());
                return null;
            });
        } finally {
            store.close();
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + world.resolve("economy/economy.sqlite"));
             var query = connection.prepareStatement("EXPLAIN QUERY PLAN " + BankingDashboard.RECENT_QUERY)) {
            query.setString(1, account.value().toString()); query.setString(2, worldId.toString());
            var plan = new ArrayList<String>();
            try (var rows = query.executeQuery()) { while (rows.next()) plan.add(rows.getString("detail")); }
            assertTrue(plan.stream().anyMatch(row -> row.contains("SEARCH r USING INDEX ledger_posting_account")), plan.toString());
            assertFalse(plan.stream().anyMatch(row -> row.contains("TEMP B-TREE")), plan.toString());
        }
    }

    private static PostingBatch.Leg leg(AccountId account, long amount) {
        return new PostingBatch.Leg(account.value(), PostingBatch.Role.ACCOUNT, CAD, new Money(CAD, amount));
    }
    private static Journal.Outcome post(JournalEngine engine, Journal.Kind kind, PlayerId actor,
                                         PostingBatch.Leg first, PostingBatch.Leg second) {
        var result = engine.submit(new Journal.Intent(new OperationId(UUID.randomUUID()), new RequestId(UUID.randomUUID()),
                actor, kind, Settlement.request(List.of(first, second)), null, null));
        assertEquals(Journal.Status.COMMITTED, result.status(), result.result().code());
        return result;
    }
}
