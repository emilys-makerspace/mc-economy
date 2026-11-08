package ca.emilybelnavis.mcmods.economy.banking;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.journal.WithdrawalPlanner;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankingBoundaryTest {
    private static final UUID SESSION = UUID.randomUUID();

    @Test void issuanceAndReplacementRequireKioskButLostReportingAndClaimsRemainOwnerActions() {
        for(var access:List.of(BankingMenu.Access.ATM,BankingMenu.Access.TELLER,BankingMenu.Access.CARD)) {
            assertTrue(access.allows("cards"));assertTrue(access.allows("card_lost"));
            assertFalse(access.allows("card_preview"));assertFalse(access.allows("card_confirm"));
        }
        assertTrue(BankingMenu.Access.KIOSK.allows("card_preview"));
        assertTrue(BankingMenu.Access.KIOSK.allows("card_confirm"));
        assertTrue(BankingMenu.Access.KIOSK.allows("collect"));
        assertFalse(BankingMenu.Access.ADMIN.allows("card_confirm"));
        assertFalse(BankingMenu.Access.REGISTER.allows("card_lost"));
        assertFalse(BankingMenu.Access.CARD.allows("credit_apply"));
    }

    @Test void creditProfileIsReadOnlyAndDoesNotExposeScoringOrUnderwritingMutations() {
        for(var access:List.of(BankingMenu.Access.ATM,BankingMenu.Access.TELLER,BankingMenu.Access.KIOSK,BankingMenu.Access.CARD)) {
            assertTrue(access.allows("credit_profile"));assertTrue(access.allows("credit_history"));assertTrue(access.allows("credit_next"));
            assertFalse(access.allows("score_recalculate"));assertFalse(access.allows("credit_establish"));assertFalse(access.allows("loan_apply"));
        }
        assertFalse(BankingMenu.Access.REGISTER.allows("credit_profile"));
        assertFalse(BankingMenu.Access.ADMIN.allows("credit_history"));
        assertThrows(IllegalArgumentException.class,()->new BankingNetwork.Action(1,SESSION,0,"score_recalculate",""));
    }

    @Test void creditFacilityDiagnosticsRemainOwnerReadActionsOnBankingSurfaces() {
        var reads=List.of("credit_facilities","credit_facilities_next","credit_facility","credit_statements","credit_statements_next");
        for(var action:reads) {
            assertTrue(BankingNetwork.ACTIONS.contains(action));
            assertDoesNotThrow(()->new BankingNetwork.Action(1,SESSION,0,action,""));
            for(var access:List.of(BankingMenu.Access.ATM,BankingMenu.Access.TELLER,BankingMenu.Access.KIOSK,BankingMenu.Access.CARD))assertTrue(access.allows(action),access+" "+action);
            for(var access:List.of(BankingMenu.Access.REGISTER,BankingMenu.Access.ADMIN,BankingMenu.Access.WORLD_SETUP))assertFalse(access.allows(action),access+" "+action);
        }
        for(var mutation:List.of("credit_limit_edit","credit_balance_edit","credit_recalculate","credit_draw","credit_repay"))assertFalse(BankingNetwork.ACTIONS.contains(mutation));
    }

    @Test void actionBoundsCountUtf8BytesAndRejectForgedIdentityFields() {
        assertDoesNotThrow(() -> new BankingNetwork.Action(1, SESSION, 0, "withdraw_preview", "é".repeat(1024)));
        assertThrows(IllegalArgumentException.class,
                () -> new BankingNetwork.Action(1, SESSION, 0, "withdraw_preview", "é".repeat(1025)));
        assertThrows(IllegalArgumentException.class,
                () -> new BankingNetwork.Action(1, SESSION, 0, "deposit_confirm", "😀".repeat(513)));
        assertThrows(IllegalArgumentException.class,
                () -> new BankingNetwork.Action(1, SESSION, 0, "issue_cash", "CAD 100"));
        assertThrows(IllegalArgumentException.class,
                () -> new BankingNetwork.Action(-1, SESSION, 0, "accounts", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new BankingNetwork.Action(101, SESSION, 0, "accounts", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new BankingNetwork.Action(1, SESSION, -1, "accounts", ""));
    }

    @Test void terminalModesExposeOnlyTheirOwnServerAuthorizedActions() {
        assertTrue(BankingMenu.Access.ATM.allows("withdraw_confirm"));
        assertTrue(BankingMenu.Access.ATM.allows("home"));
        assertTrue(BankingMenu.Access.TELLER.allows("manage_accounts"));
        assertTrue(BankingMenu.Access.CARD.allows("manage_accounts"));
        assertFalse(BankingMenu.Access.REGISTER.allows("manage_accounts"));
        assertFalse(BankingMenu.Access.WORLD_SETUP.allows("home"));
        assertFalse(BankingMenu.Access.TELLER.allows("account_close_preview"));
        assertFalse(BankingMenu.Access.TELLER.allows("account_close_confirm"));
        assertFalse(BankingMenu.Access.ATM.allows("account_close_confirm"));
        assertFalse(BankingMenu.Access.CARD.allows("account_close_confirm"));
        assertFalse(BankingMenu.Access.REGISTER.allows("account_close_confirm"));
        assertFalse(BankingMenu.Access.ATM.allows("shop"));
        assertFalse(BankingMenu.Access.TELLER.allows("settings"));
        assertTrue(BankingMenu.Access.REGISTER.allows("buy_confirm"));
        assertFalse(BankingMenu.Access.REGISTER.allows("deposit"));
        assertTrue(BankingMenu.Access.ADMIN.allows("configure"));
        assertTrue(BankingMenu.Access.ADMIN.allows("banking"));
        assertFalse(BankingMenu.Access.CARD.allows("configure"));
        assertFalse(BankingMenu.Access.ADMIN.allows("withdraw"));
        assertFalse(BankingMenu.Access.CARD.allows("deposit"));
    }

    @Test void branchLifecycleAndBrandingStayInTheirSeparateServerModes() {
        var lifecycle = List.of("branch_open", "branch_assign", "branch_switch", "branch_accept", "branch_close");
        for (String action : lifecycle) {
            assertTrue(BankingMenu.Access.KIOSK.allows(action), action);
            for (var access : List.of(BankingMenu.Access.ATM, BankingMenu.Access.TELLER, BankingMenu.Access.CARD,
                    BankingMenu.Access.REGISTER, BankingMenu.Access.ADMIN, BankingMenu.Access.WORLD_SETUP))
                assertFalse(access.allows(action), access + " " + action);
        }
        for (String action : List.of("brand_preview", "brand_confirm")) {
            assertTrue(BankingMenu.Access.ADMIN.allows(action), action);
            for (var access : BankingMenu.Access.values()) if (access != BankingMenu.Access.ADMIN)
                assertFalse(access.allows(action), access + " " + action);
        }
        for (String action : List.of("deposit", "deposit_confirm", "withdraw", "withdraw_confirm", "fx", "exchange_confirm",
                "configure", "account_open", "account_close_preview"))
            assertFalse(BankingMenu.Access.KIOSK.allows(action), action);
        for (String action : List.of("account_open", "account_close_preview", "account_close_confirm"))
            for (var access : List.of(BankingMenu.Access.ATM, BankingMenu.Access.TELLER, BankingMenu.Access.CARD))
                assertFalse(access.allows(action), access + " " + action);
        assertTrue(BankingMenu.Access.KIOSK.allows("account_close_confirm"));
        assertTrue(BankingMenu.Access.KIOSK.allows("branch_products"));
    }

    @Test void unsupportedProductsHaveNoFakeMutationPacketOrAccessGrant() {
        for (String action : List.of("card_replace", "credit_apply", "loan_apply", "subprime_apply", "issue_card")) {
            assertFalse(BankingNetwork.ACTIONS.contains(action), action);
            assertThrows(IllegalArgumentException.class, () -> new BankingNetwork.Action(1, SESSION, 0, action, ""));
            for (var access : BankingMenu.Access.values()) assertFalse(access.allows(action), access + " " + action);
        }
    }

    @Test void placerCanConfigureWithoutOperatorPrivileges() {
        UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
        assertTrue(BankingController.mayConfigure(owner, owner, false));
        assertFalse(BankingController.mayConfigure(owner, stranger, false));
        assertTrue(BankingController.mayConfigure(owner, stranger, true));
        assertFalse(BankingController.mayConfigure(null, stranger, false));
    }

    @Test void normalPresentationHidesInternalIdentifiersAndProtocols() {
        var id = UUID.randomUUID();
        assertEquals(List.of("1. CAD primary — open", "History for CAD", "12.50 CAD", "1. Register purchase"), BankingPresentation.lines(List.of(
                "1. CAD primary — open", "History for CAD " + id, "#42 12.50 CAD", "Transaction " + id,
                "1. " + id + " — register-goods-v1")).stream().map(line -> UiText.render(line, "en_gb")).toList());
        assertEquals("Outcome needs checking. Use Retry.", UiText.render(BankingPresentation.status("UNKNOWN — retry preserves operation " + id), "en_gb"));
        assertEquals("Request processed. Review the current result.",
                UiText.render(BankingPresentation.status("REGISTER_CONFIGURED"), "en_gb"));
    }

    @Test void transactionHistoryHeadingSurvivesIdentifierFiltering() {
        assertEquals(List.of("Transaction History", "Balance: 50.00 CAD", "12.50 CAD"), BankingPresentation.lines(
                List.of("Transaction History", "Balance: 50.00 CAD", "#42 12.50 CAD", "Transaction " + UUID.randomUUID())));
    }

    @Test void snapshotRejectsAggregateOverflowEvenWhenEachLineFits() {
        String line = "界".repeat(250); // 250 Java characters, 750 UTF-8 bytes per valid line.
        assertDoesNotThrow(() -> snapshot("history", Collections.nCopies(16, line), ""));
        assertThrows(IllegalArgumentException.class,
                () -> snapshot("history", Collections.nCopies(17, line), ""));
        assertThrows(IllegalArgumentException.class,
                () -> snapshot("history", Collections.nCopies(33, "small"), ""));
        assertThrows(IllegalArgumentException.class,
                () -> snapshot("../history", List.of("small"), ""));
        assertThrows(IllegalArgumentException.class,
                () -> snapshot("account details", List.of("small"), ""));
        assertThrows(IllegalArgumentException.class,
                () -> snapshot("history", List.of("small"), "x".repeat(257)));
    }

    @Test void snapshotFreezesTheAuthoritativeRowsBeforePublication() {
        var rows = new ArrayList<>(List.of("original operation"));
        var snapshot = snapshot("history", rows, "Ready");
        rows.set(0, "later unrelated operation");
        assertEquals(List.of("original operation"), snapshot.lines());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.lines().add("forged row"));
    }

    @Test void withdrawalAmountMustBeExactlyRepresentableInItsCurrency() {
        assertEquals(75, BankingWithdrawals.parse("CAD 0.75").cash().minorUnits());
        assertEquals(EconomyCurrency.CAD, BankingWithdrawals.parse("cad 0.75").cash().currency());
        assertEquals(1, BankingWithdrawals.parse("JPY 1.0").cash().minorUnits());
        assertThrows(ArithmeticException.class, () -> BankingWithdrawals.parse("CAD 0.001"));
        assertThrows(ArithmeticException.class, () -> BankingWithdrawals.parse("JPY 0.5"));
        assertThrows(IllegalArgumentException.class, () -> BankingWithdrawals.parse("CAD -1"));
        assertThrows(IllegalArgumentException.class, () -> BankingWithdrawals.parse("CAD 0"));
        assertThrows(IllegalArgumentException.class, () -> BankingWithdrawals.parse("INVALID 10"));
        assertThrows(ArithmeticException.class, () -> BankingWithdrawals.parse("CAD 9223372036854775807"));
    }

    @Test void explicitEmptyEditsRemainDifferentFromAutomaticPlanning() {
        assertNull(BankingWithdrawals.parse("CAD 1").edits());
        assertEquals(Map.of(), BankingWithdrawals.parse("CAD 1;").edits());
        assertEquals(Map.of("economy:cad_bill_20", 3), BankingWithdrawals.parse("CAD 60;economy:cad_bill_20=3").edits());
        assertThrows(UnsupportedOperationException.class,
                () -> BankingWithdrawals.parse("CAD 60;economy:cad_bill_20=3").edits().put("economy:cad_bill_50", 1));
    }

    @Test void parserDoesNotGrantAuthorityToCoinOrForeignBillEdits() {
        var coinEdit = BankingWithdrawals.parse("CAD 1;economy:cad_coin_100=1");
        var coin = new WithdrawalPlanner.Denomination("economy:cad_coin_100", EconomyCurrency.CAD, 100, false);
        assertEquals(1, coinEdit.edits().get("economy:cad_coin_100"));
        assertThrows(IllegalArgumentException.class, () -> WithdrawalPlanner.edited(EconomyCurrency.CAD, 100,
                false, List.of(coin), coinEdit.edits()));
        var foreignEdit = BankingWithdrawals.parse("CAD 5;economy:eur_bill_5=1");
        var cadBill = new WithdrawalPlanner.Denomination("economy:cad_bill_5", EconomyCurrency.CAD, 500, true);
        assertThrows(IllegalArgumentException.class, () -> WithdrawalPlanner.edited(EconomyCurrency.CAD, 500,
                true, List.of(cadBill), foreignEdit.edits()));
    }

    @Test void adversarialBillEditsRejectBeforePlannerWork() {
        assertThrows(IllegalArgumentException.class, () -> BankingWithdrawals.parse("CAD 60;economy:cad_bill_20=-1"));
        assertThrows(IllegalArgumentException.class,
                () -> BankingWithdrawals.parse("CAD 60;economy:cad_bill_20=1,economy:cad_bill_20=2"));
        assertThrows(IllegalArgumentException.class, () -> BankingWithdrawals.parse("CAD 60;economy:cad_bill_20=2305"));
        assertThrows(IllegalArgumentException.class, () -> BankingWithdrawals.parse("CAD 60;economy:cad_bill_20=2147483648"));
        assertThrows(IllegalArgumentException.class, () -> BankingWithdrawals.parse("CAD 60;economy:cad_bill_20=3;extra"));
        String many = String.join(",", IntStream.range(0, 132).mapToObj(index -> "economy:bill_" + index + "=1").toList());
        assertThrows(IllegalArgumentException.class, () -> BankingWithdrawals.parse("CAD 60;" + many));
    }

    @Test void offeredControlsAreBoundedAndFrozenWithTheirSnapshot() {
        var choice = new BankingNetwork.Choice("account_select", UUID.randomUUID() + "|history", "CAD primary account", true);
        var offered = new ArrayList<>(List.of(choice));
        var snapshot = new BankingNetwork.Snapshot(1, SESSION, 1, "accounts", List.of(), "Ready", false, offered);
        offered.clear();
        assertEquals(List.of(choice), snapshot.choices());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.choices().clear());
        assertThrows(IllegalArgumentException.class, () -> new BankingNetwork.Choice("issue_cash", "", "Forged", false));
        assertThrows(IllegalArgumentException.class, () -> new BankingNetwork.Choice("collect", "x".repeat(129), "Items", false));
        assertThrows(IllegalArgumentException.class, () -> new BankingNetwork.Snapshot(1, SESSION, 1, "accounts", List.of(),
                "Ready", false, Collections.nCopies(33, choice)));
        assertDoesNotThrow(() -> new BankingNetwork.Choice("destination", UUID.randomUUID().toString(), "EUR account", false));
    }

    private static BankingNetwork.Snapshot snapshot(String page, List<String> lines, String status) {
        return new BankingNetwork.Snapshot(1, SESSION, 1, page, lines, status, false);
    }

    @Test void historyWireRoundTripRetainsFrozenQueryOriginalDetailAndExactFx() {
        var tx = UUID.randomUUID(); var op = UUID.randomUUID(); var account = UUID.randomUUID();
        var generation = UUID.randomUUID();
        var amount = new ca.emilybelnavis.mcmods.economy.api.Money(EconomyCurrency.CAD, -1000);
        var query = new ca.emilybelnavis.mcmods.economy.journal.OwnerHistory.Query(account, EconomyCurrency.CAD,
                1000L, 9000L, ca.emilybelnavis.mcmods.economy.journal.OwnerHistory.Type.FX, 8);
        var detail = new ca.emilybelnavis.mcmods.economy.journal.HistoryDetails.Detail(tx, op, 42, 2000,
                "economy:ledger", "economy:exchange", "COMMITTED", List.of(
                    new ca.emilybelnavis.mcmods.economy.journal.HistoryDetails.Movement(account, 0, amount, 1, 4)), false,
                new ca.emilybelnavis.mcmods.economy.journal.HistoryDetails.Fee(
                    ca.emilybelnavis.mcmods.economy.journal.HistoryDetails.Availability.KNOWN,
                    new ca.emilybelnavis.mcmods.economy.api.Money(EconomyCurrency.CAD, 0)),
                new ca.emilybelnavis.mcmods.economy.journal.HistoryDetails.Counterparty(
                    ca.emilybelnavis.mcmods.economy.journal.HistoryDetails.Visibility.PRIVATE, ""),
                List.of(new ca.emilybelnavis.mcmods.economy.journal.HistoryDetails.CapturedFx(UUID.randomUUID(), "EFFECTIVE",
                    1000, "CAD", "USD", "1.25", "1.00", 2,
                    ca.emilybelnavis.mcmods.economy.journal.HistoryDetails.Availability.KNOWN)), List.of(), false);
        var history = new BankingHistoryView(generation, 42, query, List.of(), detail, null, "AVAILABLE");
        var snapshot = new BankingNetwork.Snapshot(1, SESSION, 7, "history_detail", List.of("Recorded UTC"),
                "Captured facts", false, List.of(), BankingOperation.View.ready(), null, history);
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        try {
            BankingNetwork.Snapshot.CODEC.encode(buffer, snapshot);
            assertTrue(buffer.readableBytes() <= BankingNetwork.MAX_SNAPSHOT_BYTES);
            assertEquals(snapshot, BankingNetwork.Snapshot.CODEC.decode(buffer));
        } finally { buffer.release(); }
        assertFalse(BankingMenu.Access.REGISTER.allows("history_detail"));
        assertTrue(BankingMenu.Access.CARD.allows("history_rates"));
    }

    @Test void protocolPreservesStructuredOriginalOutcomeRegardlessOfPresentationLanguage() {
        var operation = UUID.randomUUID(); var request = UUID.randomUUID();
        var view = BankingOperation.View.original(BankingOperation.State.OUTCOME_UNCERTAIN, operation, request, null);
        var snapshot = new BankingNetwork.Snapshot(1, SESSION, 7, "fx", List.of("Montant en CAD"),
                "Résultat à vérifier", false, List.of(new BankingNetwork.Choice("bank_retry", "", "Vérifier", false)), view, null);
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        try {
            BankingNetwork.Snapshot.CODEC.encode(buffer, snapshot);
            assertTrue(buffer.readableBytes() <= BankingNetwork.MAX_SNAPSHOT_BYTES);
            var decoded = BankingNetwork.Snapshot.CODEC.decode(buffer);
            assertEquals(snapshot, decoded);
            assertEquals(operation, decoded.operation().operationId());
            assertEquals(request, decoded.operation().requestId());
            assertEquals(BankingOperation.State.OUTCOME_UNCERTAIN, decoded.operation().state());
            assertEquals(List.of("bank_retry"), decoded.choices().stream().map(BankingNetwork.Choice::action).toList());
        } finally { buffer.release(); }
    }

    @Test void typedPayloadRefusesMalformedMoneyAndUnboundedArgumentsBeforePublication() {
        assertThrows(IllegalArgumentException.class, () -> new BankingOperation.Argument(BankingOperation.ArgumentKind.MONEY, "XYZ", 100));
        var arg = new BankingOperation.Argument(BankingOperation.ArgumentKind.INTEGER, "", 1);
        assertThrows(IllegalArgumentException.class, () -> new BankingOperation.View(BankingOperation.State.READY,
                "screen.economy.operation.ready", Collections.nCopies(9, arg), null, null, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new BankingOperation.View(BankingOperation.State.REVIEW,
                "screen.economy.operation.review", List.of(), null, null, null, null, 0));
        assertEquals(BankingOperation.State.READY, snapshot("fx", List.of(), "Confirm preview then retry").operation().state());
        assertEquals(BankingOperation.State.REVIEW, BankingOperation.offered(false,
                List.of(new BankingNetwork.Choice("exchange_confirm", "", "Confirmer", false))).state());
    }
}
