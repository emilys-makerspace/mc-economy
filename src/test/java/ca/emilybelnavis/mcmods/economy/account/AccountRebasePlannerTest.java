package ca.emilybelnavis.mcmods.economy.account;

import static ca.emilybelnavis.mcmods.economy.account.AccountRebasePlanner.MAX_PRIMARIES;
import static ca.emilybelnavis.mcmods.economy.api.EconomyCurrency.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.emilybelnavis.mcmods.economy.account.AccountRebasePlanner.Claims;
import ca.emilybelnavis.mcmods.economy.account.AccountRebasePlanner.ClaimsReader;
import ca.emilybelnavis.mcmods.economy.account.AccountRebasePlanner.Denomination;
import ca.emilybelnavis.mcmods.economy.account.AccountRebasePlanner.Status;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.AccountId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.fx.DenominationChange;
import ca.emilybelnavis.mcmods.economy.storage.FoundationStore;
import ca.emilybelnavis.mcmods.economy.storage.SqliteStorage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountRebasePlannerTest {
  @TempDir Path worldPath;
  private static final Claims CLEAR = new Claims(false, false, false);
  private static final Denomination CURRENT = new Denomination(CAD, 4);
  private final UUID session = UUID.randomUUID();
  private final PlayerId actor = player(50_000);

  @Test
  void mapsCollisionsAndPreservesUnrelatedAccountsAndTrading() throws Exception {
    var store = open();
    try {
      var world = store.snapshot().worldId();
      var withCollision = player(1);
      var withoutCollision = player(2);
      var primary = account(1);
      var collision = account(2);
      var foreign = account(3);
      var trading = account(4);
      var otherPrimary = account(5);
      store.transaction(
          tx -> {
            insert(tx, withCollision, primary, "BANK", "CAD", "OPEN", 4, true);
            insert(tx, withCollision, collision, "BANK", "USD", "OPEN", 3, false);
            insert(tx, withCollision, foreign, "BANK", "EUR", "OPEN", 2, false);
            insert(tx, withCollision, trading, "TRADING", "", "OPEN", 1, false);
            insert(tx, withoutCollision, otherPrimary, "BANK", "CAD", "OPEN", 5, true);
            insert(tx, withoutCollision, account(6), "BANK", "USD", "CLOSED", 2, false);
            return null;
          });

      var confirmed = confirm(world);
      var affected = new HashSet<AccountId>();
      ClaimsReader claims =
          (reads, id) -> {
            affected.add(id);
            return Optional.of(
                id.equals(foreign) || id.equals(trading) ? new Claims(true, true, true) : CLEAR);
          };
      var before = rows(store);
      var result = plan(store, confirmed, CURRENT, claims);
      assertEquals(Status.READY, result.status());
      assertEquals(Set.of(primary, collision, otherPrimary), affected);
      assertEquals(2, result.plan().mappings().size());
      var first = result.plan().mappings().getFirst();
      assertEquals(withCollision, first.owner());
      assertEquals(primary, first.primary().id());
      assertEquals(CAD, first.primary().currency());
      assertEquals(4, first.primary().revision());
      assertEquals(collision, first.collision().id());
      assertEquals(USD, first.collision().currency());
      assertEquals(3, first.collision().revision());
      var second = result.plan().mappings().getLast();
      assertEquals(withoutCollision, second.owner());
      assertEquals(otherPrimary, second.primary().id());
      assertNull(second.collision());
      assertEquals(result.plan(), plan(store, confirmed, CURRENT, claims).plan());
      assertEquals(before, rows(store));
      store.transaction(
          tx ->
              tx.execute(
                  "UPDATE account_catalog SET revision=revision+1 WHERE account_id=?",
                  List.of(collision.value().toString())));
      var refreshed = plan(store, confirmed, CURRENT, claims);
      assertEquals(Status.READY, refreshed.status());
      assertNotEquals(result.plan(), refreshed.plan());
      assertEquals(4, refreshed.plan().mappings().getFirst().collision().revision());
    } finally {
      store.close();
    }
  }

  @Test
  void deniesWrongScopeStaleRevisionAndUnknownOrEncumberedLedger() throws Exception {
    var store = open();
    try {
      var world = store.snapshot().worldId();
      var owner = player(1);
      var primary = account(1);
      var collision = account(2);
      store.transaction(
          tx -> {
            insert(tx, owner, primary, "BANK", "CAD", "OPEN", 1, true);
            insert(tx, owner, collision, "BANK", "USD", "OPEN", 1, false);
            return null;
          });
      var confirmed = confirm(world);
      ClaimsReader untouched = (reads, id) -> fail("Invalid intent cannot read claims");
      assertEquals(
          Status.DENOMINATION_UNAVAILABLE, plan(store, confirmed, null, untouched).status());
      assertEquals(
          Status.STALE_DENOMINATION,
          plan(store, confirmed, new Denomination(CAD, 5), untouched).status());
      assertEquals(
          Status.SAME_CURRENCY,
          plan(store, confirmed, new Denomination(USD, 4), untouched).status());
      assertEquals(
          Status.UNAUTHORIZED,
          store
              .transaction(
                  tx ->
                      AccountRebasePlanner.plan(
                          tx::query, world, session, player(50_001), confirmed, CURRENT, untouched))
              .status());
      assertEquals(
          Status.UNAUTHORIZED,
          store
              .transaction(
                  tx ->
                      AccountRebasePlanner.plan(
                          tx::query,
                          world,
                          UUID.randomUUID(),
                          actor,
                          confirmed,
                          CURRENT,
                          untouched))
              .status());
      assertEquals(
          Status.UNAUTHORIZED,
          store
              .transaction(
                  tx ->
                      AccountRebasePlanner.plan(
                          tx::query,
                          new WorldId(UUID.randomUUID()),
                          session,
                          actor,
                          confirmed,
                          CURRENT,
                          untouched))
              .status());

      var missing =
          plan(
              store,
              confirmed,
              CURRENT,
              (reads, id) -> id.equals(collision) ? Optional.empty() : Optional.of(CLEAR));
      assertEquals(Status.LEDGER_UNAVAILABLE, missing.status());
      assertNull(missing.plan());
      for (Claims blocked :
          List.of(
              new Claims(true, false, false),
              new Claims(false, true, false),
              new Claims(false, false, true))) {
        for (AccountId affected : List.of(primary, collision)) {
          var result =
              plan(
                  store,
                  confirmed,
                  CURRENT,
                  (reads, id) -> Optional.of(id.equals(affected) ? blocked : CLEAR));
          assertEquals(Status.ENCUMBERED, result.status());
          assertNull(result.plan());
        }
      }
      assertEquals(
          Status.CATALOG_MISMATCH,
          plan(store, confirmed, new Denomination(EUR, 4), untouched).status());
    } finally {
      store.close();
    }
  }

  @Test
  void boundsWorldVolumeBeforeReadingClaimsBeyondCap() throws Exception {
    var store = open();
    try {
      var world = store.snapshot().worldId();
      store.transaction(
          tx -> {
            for (int i = 1; i <= MAX_PRIMARIES; i++) {
              var owner = player(i);
              insert(tx, owner, account(i), "BANK", "CAD", "OPEN", 1, true);
              insert(tx, owner, account(MAX_PRIMARIES + i), "BANK", "USD", "OPEN", 1, false);
            }
            return null;
          });
      var confirmed = confirm(world);
      var limits = new ArrayList<Integer>();
      var reads = new AtomicInteger();
      ClaimsReader claims =
          (query, id) -> {
            reads.incrementAndGet();
            return Optional.of(CLEAR);
          };
      var maximum =
          store.transaction(
              tx ->
                  AccountRebasePlanner.plan(
                      (sql, parameters, limit) -> {
                        limits.add(limit);
                        return tx.query(sql, parameters, limit);
                      },
                      world,
                      session,
                      actor,
                      confirmed,
                      CURRENT,
                      claims));
      assertEquals(Status.READY, maximum.status());
      assertEquals(MAX_PRIMARIES, maximum.plan().mappings().size());
      assertEquals(MAX_PRIMARIES * 2, reads.get());
      assertEquals(List.of(MAX_PRIMARIES + 1), limits);

      store.transaction(
          tx -> {
            insert(
                tx,
                player(MAX_PRIMARIES + 1),
                account(2 * MAX_PRIMARIES + 1),
                "BANK",
                "CAD",
                "OPEN",
                1,
                true);
            return null;
          });
      var oversized =
          plan(
              store,
              confirmed,
              CURRENT,
              (query, id) -> fail("Oversized plans must stop before claims"));
      assertEquals(Status.PLAN_TOO_LARGE, oversized.status());
      assertNull(oversized.plan());
    } finally {
      store.close();
    }
  }

  private AccountRebasePlanner.Result plan(
      SqliteStorage store,
      DenominationChange.Confirmed confirmed,
      Denomination current,
      ClaimsReader claims) {
    var world = store.snapshot().worldId();
    return store.transaction(
        tx ->
            AccountRebasePlanner.plan(
                tx::query, world, session, actor, confirmed, current, claims));
  }

  private DenominationChange.Confirmed confirm(WorldId world) {
    var protocol = new DenominationChange(world, session);
    var first = protocol.first(actor, USD, CURRENT.revision(), true, 1);
    return protocol.confirm(first, actor, CURRENT.revision(), true, 2);
  }

  private SqliteStorage open() throws Exception {
    var store = new SqliteStorage();
    store.initialize(worldPath.toRealPath());
    return store;
  }

  private static List<?> rows(SqliteStorage store) {
    return store.transaction(
        tx -> tx.query("SELECT * FROM account_catalog ORDER BY account_id", List.of(), 10));
  }

  private static void insert(
      FoundationStore.Transaction tx,
      PlayerId owner,
      AccountId id,
      String purpose,
      String currency,
      String status,
      long revision,
      boolean primary) {
    tx.execute(
        """
                INSERT INTO account_catalog(account_id,owner_id,purpose,currency,status,revision,is_primary)
                VALUES(?,?,?,NULLIF(?,''),?,?,?)
                """,
        List.of(
            id.value().toString(),
            owner.value().toString(),
            purpose,
            currency,
            status,
            Long.toString(revision),
            primary ? "1" : "0"));
  }

  private static PlayerId player(int serial) {
    return new PlayerId(new UUID(0, serial));
  }

  private static AccountId account(int serial) {
    return new AccountId(new UUID(1, serial));
  }
}
