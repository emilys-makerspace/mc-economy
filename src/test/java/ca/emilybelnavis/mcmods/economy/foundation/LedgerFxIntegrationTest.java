package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.banking.BankingHistoryView;
import ca.emilybelnavis.mcmods.economy.fx.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

class LedgerFxIntegrationTest {
    @TempDir Path world;
    private final PlayerId owner = new PlayerId(UUID.randomUUID());
    private final AccountId source = new AccountId(UUID.randomUUID());
    private final AccountId target = new AccountId(UUID.randomUUID());
    private final SqliteStorage store = new SqliteStorage();
    private ServerServices services;
    private LedgerService ledger;
    private PartyAuthorization.Grant grant;
    private Journal.Outcome opening;

    @BeforeEach void openAndFund() throws Exception {
        services = new ServerServices(world.toRealPath(), store);
        await(services.readiness());
        ledger = services.ledger();
        var party = new PartyRef(services.partyWorld(), new PartyRef.Player(owner));
        grant = PartyAuthorization.admit(services.partyWorld(), services.sessionId(), owner, false, party, false).orElseThrow();
        var selection = ledger.trustedJournal(FxService.OPEN, (actor, kind) -> owner.equals(actor),
                FxService.opening(Clock.systemUTC()), null);
        opening = await(selection.submit(intent(FxService.OPEN,
                new Journal.CanonicalRequest(1, Map.of("currency", "CAD")))).completion()).outcome();
        assertEquals(Journal.Status.COMMITTED, opening.status());
        await(services.storage(() -> store.transaction(tx -> {
            tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                    List.of(source.value().toString(), owner.value().toString()));
            tx.execute("INSERT INTO account_catalog VALUES(?,?,'BANK','USD','OPEN',1,0)",
                    List.of(target.value().toString(), owner.value().toString()));
            return null;
        })));
        var credit = ledger.adjustments(SameCurrency.CREDIT, (actor, kind) -> owner.equals(actor),
                (intent, instruction, reads) -> true, null);
        var funding = new SameCurrency.Adjustment(owner, new SameCurrency.Revision(source, 1), new Money(CAD, 10_000));
        assertEquals(Journal.Status.COMMITTED, await(credit.submit(intent(SameCurrency.CREDIT, funding.request()))
                .completion()).outcome().status());
    }

    @AfterEach void stop() {
        if (services != null) assertTrue(services.stop(Duration.ofSeconds(5)));
    }

    @Test void exchangeCommitsOnceAndLookupReturnsOriginalReceiptWithoutReusingTheQuote() throws Exception {
        var quote = quote();
        var exchange = exchange(quote);
        var operation = new OperationId(UUID.randomUUID());
        assertTrue(await(ledger.lookupExchange(grant, operation, new RequestId(UUID.randomUUID()), exchange)).isEmpty());
        var committed = await(ledger.exchange(grant, operation, new RequestId(UUID.randomUUID()), exchange).completion()).outcome();
        assertEquals(Journal.Status.COMMITTED, committed.status(), committed.result().code());
        assertEquals(8_000, balance(source));
        long received = balance(target);
        assertTrue(received > 0);
        assertEquals(quote.credit().minorUnits() / USD.minorUnitsPerMajor() * USD.minorUnitsPerMajor(), received,
                "Unassigned legacy FX settles the quoted payout in whole coins");

        assertEquals(committed, await(ledger.exchange(grant, operation, new RequestId(UUID.randomUUID()), exchange)
                .completion()).outcome());
        assertEquals(committed, await(ledger.lookupExchange(grant, operation, new RequestId(UUID.randomUUID()), exchange)).orElseThrow());
        var reuse = await(ledger.exchange(grant, new OperationId(UUID.randomUUID()), new RequestId(UUID.randomUUID()), exchange)
                .completion()).outcome();
        assertEquals("FX_QUOTE_CONSUMED", reuse.result().code());
        assertEquals(8_000, balance(source));
        assertEquals(received, balance(target));

        var stranger = new PlayerId(UUID.randomUUID());
        var other = PartyAuthorization.admit(services.partyWorld(), services.sessionId(), stranger, false,
                new PartyRef(services.partyWorld(), new PartyRef.Player(stranger)), false).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> ledger.exchange(other, operation, new RequestId(UUID.randomUUID()), exchange));
        assertThrows(IllegalArgumentException.class, () -> ledger.lookupExchange(other, operation, new RequestId(UUID.randomUUID()), exchange));
    }

    @Test void revokedAuthorityIsDurableAndDoesNotConsumeTheOwnersQuote() throws Exception {
        var exchange = exchange(quote());
        var operation = new OperationId(UUID.randomUUID());
        var rejected = await(ledger.exchange(grant, operation, new RequestId(UUID.randomUUID()), exchange,
                (intent, reads) -> false).completion()).outcome();
        assertEquals(Journal.Status.DURABLY_REJECTED, rejected.status());
        assertEquals("AUTHORITY_REVOKED", rejected.result().code());
        assertEquals(10_000, balance(source));
        assertEquals(0, balance(target));
        assertEquals(rejected, await(ledger.exchange(grant, operation, new RequestId(UUID.randomUUID()), exchange)
                .completion()).outcome());
        assertEquals(rejected, await(ledger.lookupExchange(grant, operation, new RequestId(UUID.randomUUID()), exchange)).orElseThrow());
        var freshOperation = await(ledger.exchange(grant, new OperationId(UUID.randomUUID()), new RequestId(UUID.randomUUID()), exchange)
                .completion()).outcome();
        assertEquals(Journal.Status.COMMITTED, freshOperation.status(), freshOperation.result().code());
        assertEquals(8_000, balance(source));
    }

    @Test void historicalRatesRetainOriginalPublicationAndReadsDoNotCreateFinancialOutcomes() throws Exception {
        long before = await(services.storage(store::lastCommittedSequence));
        var query = new BankingHistory.RateQuery(BankingHistoryView.Family.DAILY, CAD, USD, null, null, null);
        var page = await(ledger.historyRates(grant, query)).orElseThrow();
        assertEquals(BankingHistoryView.Family.DAILY, page.view().family());
        assertEquals(CAD, page.view().source());
        assertEquals(USD, page.view().target());
        assertEquals(opening.operationId().value(), page.view().rows().getFirst().publication());
        assertEquals("AVAILABLE", page.view().rows().getFirst().status());
        assertTrue(page.view().rows().getFirst().version() > 0);
        var earlier = new BankingHistory.RateQuery(BankingHistoryView.Family.DAILY, CAD, USD, 0L, null, null);
        var outsideRetainedHistory = assertThrows(ExecutionException.class, () -> await(ledger.historyRates(grant, earlier)));
        assertInstanceOf(IllegalArgumentException.class, outsideRetainedHistory.getCause());
        var effective = new BankingHistory.RateQuery(BankingHistoryView.Family.EFFECTIVE, CAD, USD, 0L, null, null);
        assertTrue(await(ledger.historyRates(grant, effective)).orElseThrow().view().rows().isEmpty());
        assertEquals(before, await(services.storage(store::lastCommittedSequence)));
        assertEquals(10_000, balance(source));
        assertEquals(0, balance(target));
    }

    private FxQuotes.Quote quote() throws Exception {
        return await(ledger.quote(grant, new RequestId(UUID.randomUUID()), new FxQuotes.Pair(CAD, USD),
                FxQuotes.Side.EXACT_DEBIT, new Money(CAD, 2_000)));
    }
    private FxSettlement.Exchange exchange(FxQuotes.Quote quote) {
        return new FxSettlement.Exchange(quote.id(), owner, new SameCurrency.Revision(source, 1), new SameCurrency.Revision(target, 1));
    }
    private long balance(AccountId account) throws Exception {
        return await(services.accountFunds(grant, account)).orElseThrow().postedMinorUnits();
    }
    private Journal.Intent intent(Journal.Kind kind, Journal.CanonicalRequest request) {
        return new Journal.Intent(new OperationId(UUID.randomUUID()), new RequestId(UUID.randomUUID()), owner, kind, request, null, null);
    }
    private <T> T await(CompletionStage<T> stage) throws Exception {
        var future = stage.toCompletableFuture();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!future.isDone() && System.nanoTime() < end) { services.tick(); Thread.sleep(1); }
        return future.get(1, TimeUnit.SECONDS);
    }
}
