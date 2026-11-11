package ca.emilybelnavis.mcmods.economy.loan;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoanEventsTest {
  @TempDir Path path;

  @Test
  void immutableFrontierPreservesOriginalDueAndLaterSatisfactionAcrossPages() throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      var loan = fixture.originate();
      var kind = new Journal.Kind("fixture:loan-source", "fixture:month");
      var month =
          fixture.submit(
              kind,
              new JournalEngine.Registration(
                  1, (intent, reads) -> Loans.month(reads, loan.id(), loan.nextMonth())),
              fixture.intent(
                  kind,
                  new Journal.CanonicalRequest(1, Map.of("due", Long.toString(loan.nextMonth())))));
      assertEquals(Journal.Status.COMMITTED, month.status());
      var start = fixture.store.transaction(tx -> LoanEvents.open(tx::query));
      fixture.clock.set(loan.nextMonth() + 1);
      var quote =
          fixture.store.transaction(
              tx ->
                  Loans.quote(
                      tx::query,
                      fixture.owner,
                      loan.id(),
                      fixture.account,
                      100,
                      false,
                      fixture.clock.get()));
      assertEquals(
          "LOAN_REPAID",
          fixture
              .submit(
                  Loans.REPAY,
                  Loans.repayment(fixture.owner, quote, fixture.clock::get),
                  fixture.intent(Loans.REPAY, quote.request().request()))
              .result()
              .code());

      var events = new ArrayList<LoanEvents.Event>();
      var cursor = start;
      LoanEvents.Cursor checkpoint;
      do {
        var requested = cursor;
        var page = fixture.store.transaction(tx -> LoanEvents.read(tx::query, requested, 1));
        assertTrue(page.events().size() <= 1);
        events.addAll(page.events());
        checkpoint = page.checkpoint();
        cursor = page.nextCursor();
      } while (cursor != null);
      assertEquals(3, events.size());
      assertTrue(events.stream().allMatch(e -> e.envelope().sequence() <= start.frontier()));
      var original =
          events.stream()
              .map(LoanEvents.Event::payload)
              .filter(LoanEvents.InstallmentClosed.class::isInstance)
              .map(LoanEvents.InstallmentClosed.class::cast)
              .findFirst()
              .orElseThrow()
              .snapshot();
      String identity =
          UUID.nameUUIDFromBytes(
                  ("economy:loan:v1|installment|"
                          + loan.world().value()
                          + "|"
                          + loan.id()
                          + "|"
                          + loan.nextMonth())
                      .getBytes(StandardCharsets.UTF_8))
              .toString();
      assertEquals(identity, original.id());
      assertEquals(loan.nextMonth(), original.dueAt());
      assertEquals(loan.servicing().installmentMinor(), original.originalMinor());
      assertEquals(month.operationId().value(), events.getLast().envelope().operation());

      var completed = checkpoint;
      var renewed = fixture.store.transaction(tx -> LoanEvents.open(tx::query, completed));
      var paymentPage = fixture.store.transaction(tx -> LoanEvents.read(tx::query, renewed, 16));
      assertEquals(1, paymentPage.events().size());
      var changed =
          ((LoanEvents.ContractChanged) paymentPage.events().getFirst().payload()).snapshot();
      var requirement = changed.servicing().requirements().getFirst();
      assertEquals(identity, requirement.id());
      assertEquals(original.dueAt(), requirement.dueAt());
      assertEquals(original.originalMinor() - 100, requirement.unmetMinor());
      assertEquals(original.originalMinor(), requirement.originalMinor());
      assertNull(paymentPage.nextCursor());
    }
  }

  @Test
  void sourceRejectsWrongWorldFutureFrontierAndUnsupportedHistoricalVersion() throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      fixture.originate();
      var cursor = fixture.store.transaction(tx -> LoanEvents.open(tx::query));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.store.transaction(
                  tx ->
                      LoanEvents.read(
                          tx::query,
                          new LoanEvents.Cursor(
                              new WorldId(UUID.randomUUID()), 0, -1, cursor.frontier()),
                          16)));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.store.transaction(
                  tx ->
                      LoanEvents.read(
                          tx::query,
                          new LoanEvents.Cursor(cursor.world(), 0, -1, cursor.frontier() + 1),
                          16)));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.store.transaction(tx -> LoanEvents.read(tx::query, cursor, 17)));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.store.transaction(
                  tx -> {
                    JournalEngine.ReadView unsupported =
                        (sql, parameters, limit) -> {
                          var rows = tx.query(sql, parameters, limit);
                          if (!sql.startsWith("SELECT r.sequence")) return rows;
                          return rows.stream()
                              .map(
                                  row -> {
                                    var copy = new HashMap<>(row);
                                    copy.put("record_version", "2");
                                    return Collections.unmodifiableMap(copy);
                                  })
                              .toList();
                        };
                    return LoanEvents.read(unsupported, cursor, 16);
                  }));
    }
  }

  @Test
  void eventLookupUsesPartialFeedIndexAndBoundedEnvelopeJoin() throws Exception {
    try (var fixture = new LoanTestFixture(path)) {
      fixture.originate();
      var cursor = fixture.store.transaction(tx -> LoanEvents.open(tx::query));
      var sizes = new ArrayList<Integer>();
      fixture.store.transaction(
          tx -> {
            JournalEngine.ReadView measured =
                (sql, arguments, limit) -> {
                  sizes.add(limit);
                  return tx.query(sql, arguments, limit);
                };
            LoanEvents.read(measured, cursor, 16);
            return null;
          });
      assertTrue(sizes.stream().allMatch(size -> size <= 17));
      try (var connection =
              DriverManager.getConnection(
                  "jdbc:sqlite:file:" + path.resolve("economy/economy.sqlite") + "?mode=ro");
          var statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + LoanEvents.QUERY)) {
        statement.setLong(1, cursor.frontier());
        statement.setLong(2, 0);
        statement.setInt(3, -1);
        statement.setInt(4, 17);
        var plan = new StringBuilder();
        try (var rows = statement.executeQuery()) {
          while (rows.next()) plan.append(rows.getString(4)).append('\n');
        }
        assertTrue(plan.toString().contains("loan_event_feed"), plan.toString());
        assertFalse(plan.toString().contains("USE TEMP B-TREE"), plan.toString());
      }
    }
  }
}
