package ca.emilybelnavis.mcmods.economy.corporation;

import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.api.*;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import ca.emilybelnavis.mcmods.economy.storage.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorporationsTest {
  @TempDir Path path;
  final PlayerId founder = new PlayerId(UUID.randomUUID());
  final AccountId account = new AccountId(UUID.randomUUID());

  private Journal.Outcome submit(
      SqliteStorage store, Corporations.Command command, OperationId op) {
    var owner = new PartyRef(store.snapshot().worldId(), new PartyRef.Player(founder));
    var fields = new HashMap<>(command.fields());
    fields.put("economy:owner", Journal.actorKey(founder));
    var intent =
        new Journal.Intent(
            op,
            new RequestId(UUID.randomUUID()),
            founder,
            Corporations.KIND,
            new Journal.CanonicalRequest(1, fields),
            null,
            null);
    return new JournalEngine(
            store,
            (actor, kind) -> actor.equals(founder),
            Map.of(Corporations.KIND, Corporations.handler(owner, command, () -> 0L)))
        .submit(intent);
  }

  private Journal.Outcome submit(SqliteStorage store, Corporations.Command command) {
    return submit(store, command, new OperationId(UUID.randomUUID()));
  }

  @Test
  void issuedSharesAndRenamedIdentifierSurviveReplayAndReopen() throws Exception {
    var registration =
        new Corporations.Register(
            "Aster Works",
            new Corporations.Fee(account, 1, EconomyCurrency.CAD, 50000, null),
            new Corporations.ShareIssue(500, new Money(EconomyCurrency.CAD, 120)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Corporations.Register("Aster ", registration.fee()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Corporations.Register("A".repeat(33), registration.fee()));
    assertThrows(
        ArithmeticException.class,
        () -> new Corporations.ShareIssue(Long.MAX_VALUE, new Money(EconomyCurrency.CAD, 2)));
    var operation = new OperationId(UUID.randomUUID());
    UUID id;
    var store = new SqliteStorage();
    try {
      store.initialize(path.toRealPath());
      store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO account_catalog VALUES(?,?,'BANK','CAD','OPEN',1,1)",
                List.of(account.value().toString(), founder.value().toString()));
            LedgerProjection.write(tx, account.value(), EconomyCurrency.CAD, 100000);
            return null;
          });
      var first = submit(store, registration, operation);
      assertEquals("CORPORATION_ACTIVE", first.result().code());
      id = UUID.fromString(first.result().payload().split("\\|")[0]);
      assertEquals(first, submit(store, registration, operation));
      assertEquals(
          "CORPORATION_NAME_RESERVED",
          submit(store, new Corporations.Register("ＡＳＴＥＲ-WORKS", registration.fee()))
              .result()
              .code());

      assertEquals(
          "CORPORATION_ACTIVE",
          submit(store, new Corporations.Change(id, 1, Corporations.Action.RENAME, "Aster Studio"))
              .result()
              .code());
      assertEquals(
          "CORPORATION_ACTIVE",
          submit(store, new Corporations.Register("ＡＳＴＥＲ WORKS", registration.fee()))
              .result()
              .code());
      assertEquals(
          "CORPORATION_WINDING_DOWN",
          submit(store, new Corporations.Change(id, 2, Corporations.Action.WIND_DOWN, ""))
              .result()
              .code());
      assertEquals(
          "CORPORATION_ACTIVE",
          submit(store, new Corporations.Change(id, 3, Corporations.Action.CANCEL, ""))
              .result()
              .code());
      submit(store, new Corporations.Change(id, 4, Corporations.Action.WIND_DOWN, ""));
      store.transaction(
          tx -> {
            tx.execute(
                "INSERT INTO corporation_claims VALUES(?, 'fixture', 'deposit', 'FUNDS')",
                List.of(id.toString()));
            return null;
          });
      assertEquals(
          "CORPORATION_DISSOLUTION_BLOCKED",
          submit(store, new Corporations.Change(id, 5, Corporations.Action.DISSOLVE, ""))
              .result()
              .code());
      store.transaction(
          tx -> {
            tx.execute("DELETE FROM corporation_claims WHERE company=?", List.of(id.toString()));
            return null;
          });
      assertEquals(
          "CORPORATION_DISSOLVED",
          submit(store, new Corporations.Change(id, 5, Corporations.Action.DISSOLVE, ""))
              .result()
              .code());
    } finally {
      store.close();
    }
    var reopened = new SqliteStorage();
    try {
      reopened.initialize(path.toRealPath());
      assertEquals(Journal.Status.COMMITTED, submit(reopened, registration, operation).status());
      assertEquals(
          0L,
          reopened
              .<Long>transaction(
                  tx -> Settlement.posted(tx::query, account.value(), EconomyCurrency.CAD))
              .longValue());
      var company =
          reopened.transaction(tx -> Corporations.find(tx::query, founder, id).orElseThrow());
      assertEquals(Corporations.State.DISSOLVED, company.state());
      assertEquals(6, company.revision());
      var profile =
          reopened.transaction(tx -> CorporateCapital.profile(tx::query, id).orElseThrow());
      assertEquals("aster-studio", profile.identifier());
      assertEquals(1, profile.shares().size());
      assertEquals("A", profile.shares().getFirst().code());
      assertEquals(new Money(EconomyCurrency.CAD, 60000), profile.required());
      assertFalse(profile.funded());
    } finally {
      reopened.close();
    }
  }
}
