package ca.emilybelnavis.mcmods.economy.banking;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class BankingOperationTest {
  private final OperationId operation = new OperationId(UUID.randomUUID());
  private final RequestId request = new RequestId(UUID.randomUUID());

  @Test
  void uncertainOutcomeRetainsOriginalRetryIdentityUntilACommittedReceiptArrives() {
    var uncertain = roundTrip(BankingOperation.outcome(outcome(Journal.Status.UNKNOWN, "UNKNOWN")));
    assertTrue(uncertain.unresolved());
    assertFalse(uncertain.confirming());
    assertEquals(operation.value(), uncertain.operationId());
    assertEquals(request.value(), uncertain.requestId());
    assertNull(uncertain.receiptId());
    assertNull(uncertain.reviewId());

    var transaction = new TransactionId(UUID.randomUUID());
    var committed =
        new Journal.Outcome(
            operation,
            request,
            Journal.Status.COMMITTED,
            transaction,
            new JournalSequence(42),
            new Journal.Result(1, "PAYMENT_COMMITTED", ""));
    var completed = roundTrip(BankingOperation.outcome(committed));
    assertEquals(BankingOperation.State.COMPLETED, completed.state());
    assertFalse(completed.unresolved());
    assertFalse(completed.confirming());
    assertEquals(uncertain.operationId(), completed.operationId());
    assertEquals(uncertain.requestId(), completed.requestId());
    assertEquals(transaction.value(), completed.receiptId());
  }

  @Test
  void expiredReviewAndDeclinedPaymentCannotOfferConfirmationOrInventAReceipt() {
    var stale =
        roundTrip(
            BankingOperation.outcome(outcome(Journal.Status.DURABLY_REJECTED, "FX_QUOTE_STALE")));
    var declined =
        roundTrip(
            BankingOperation.outcome(
                outcome(Journal.Status.DURABLY_REJECTED, "INSUFFICIENT_FUNDS")));
    assertEquals(BankingOperation.State.EXPIRED_STALE, stale.state());
    assertEquals(BankingOperation.State.DECLINED, declined.state());
    assertNotEquals(stale.reasonKey(), declined.reasonKey());
    for (var result : List.of(stale, declined)) {
      assertFalse(result.confirming());
      assertFalse(result.unresolved());
      assertNull(result.reviewId());
      assertNull(result.receiptId());
      assertEquals(operation.value(), result.operationId());
      assertEquals(request.value(), result.requestId());
    }
  }

  private Journal.Outcome outcome(Journal.Status status, String code) {
    return new Journal.Outcome(
        operation, request, status, null, null, new Journal.Result(1, code, ""));
  }

  private static BankingOperation.View roundTrip(BankingOperation.View view) {
    var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    try {
      view.write(buffer);
      var decoded = BankingOperation.View.read(buffer);
      assertEquals(0, buffer.readableBytes());
      return decoded;
    } finally {
      buffer.release();
    }
  }
}
