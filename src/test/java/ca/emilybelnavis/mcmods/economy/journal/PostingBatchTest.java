package ca.emilybelnavis.mcmods.economy.journal;

import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Authority.*;
import static ca.emilybelnavis.mcmods.economy.journal.PostingBatch.Role.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Money;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingBatchTest {
  @Test
  void checksPostingAcceptance() {
    var issuance = List.of(leg(ACCOUNT, CAD, 10_000), leg(ISSUANCE, CAD, -10_000));
    var commit =
        assertInstanceOf(JournalEngine.Commit.class, PostingBatch.prepare(issuance, ISSUE));
    assertEquals(2, commit.records().size());
    assertTrue(commit.records().get(1).payload().endsWith("|ISSUANCE|CAD|-10000|ISSUE"));
    reject("POSTING_AUTHORITY", issuance, TRANSFER);
    reject(
        "POSTING_OVERFLOW",
        List.of(
            leg(ACCOUNT, CAD, Long.MAX_VALUE - Long.MAX_VALUE % 100),
            leg(ACCOUNT, CAD, 100),
            leg(ISSUANCE, CAD, -100)),
        ISSUE);
    reject("POSTING_IMBALANCE", List.of(leg(ACCOUNT, CAD, 100), leg(ACCOUNT, USD, -100)), TRANSFER);
  }

  private static PostingBatch.Leg leg(
      PostingBatch.Role role, EconomyCurrency currency, long units) {
    return new PostingBatch.Leg(UUID.randomUUID(), role, currency, new Money(currency, units));
  }

  private static void reject(
      String code, List<PostingBatch.Leg> legs, PostingBatch.Authority authority) {
    var reject =
        assertInstanceOf(JournalEngine.Reject.class, PostingBatch.prepare(legs, authority));
    assertEquals(code, reject.result().code());
    assertEquals("", reject.result().payload());
  }
}
