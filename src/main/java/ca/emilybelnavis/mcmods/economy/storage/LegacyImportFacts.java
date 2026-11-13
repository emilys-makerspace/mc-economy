package ca.emilybelnavis.mcmods.economy.storage;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.fx.FxService;
import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/** Explicit legacy opening evidence; old transaction history remains in the read-only archive. */
public final class LegacyImportFacts {
  public static final String FACT = "economy:legacy-account-import";
  private static final Journal.Kind IMPORT =
      new Journal.Kind("economy:database-upgrade", "economy:import-account");

  private LegacyImportFacts() {}

  public static void validate(String payload) {
    String[] f = payload.split("\\|", -1);
    if (f.length != 7 || !f[0].matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Legacy import fact");
    for (int i : List.of(1, 2))
      if (!UUID.fromString(f[i]).toString().equals(f[i]))
        throw new IllegalArgumentException("Legacy import identity");
    if (!Set.of("BANK", "TRADING").contains(f[3])
        || !Set.of("0", "1").contains(f[5])
        || f[3].equals("TRADING") && f[5].equals("1"))
      throw new IllegalArgumentException("Legacy import account");
    EconomyCurrency.fromCode(f[4]);
    long balance = Long.parseLong(f[6]);
    if (balance < 0 || !Long.toString(balance).equals(f[6]))
      throw new IllegalArgumentException("Legacy import balance");
  }

  static void importAccounts(
      SqliteStorage store, LegacyDatabase.Snapshot source, String token, UUID administrator) {
    var actor = new PlayerId(administrator);
    for (var account : source.accounts()) {
      String payload =
          String.join(
              "|",
              token,
              account.id().toString(),
              account.owner().toString(),
              account.kind(),
              account.currency().name(),
              account.primary() ? "1" : "0",
              Long.toString(account.balance()));
      validate(payload);
      UUID issuance = id("legacy-issuance|" + token + "|" + account.id());
      var records = new ArrayList<Journal.Record>();
      records.add(new Journal.Record(FACT, 1, payload));
      if (account.balance() > 0) {
        records.add(
            new Journal.Record(
                "economy:posting",
                2,
                account.id()
                    + "|ACCOUNT|"
                    + account.currency()
                    + "|"
                    + account.balance()
                    + "|ISSUE"));
        records.add(
            new Journal.Record(
                "economy:posting",
                2,
                issuance
                    + "|ISSUANCE|"
                    + account.currency()
                    + "|"
                    + -account.balance()
                    + "|ISSUE"));
      }
      var handler =
          new JournalEngine.Registration(
              1,
              (intent, reads) ->
                  new JournalEngine.Commit(
                      records,
                      new Journal.Result(1, "LEGACY_ACCOUNT_IMPORTED", ""),
                      tx -> {
                        tx.execute(
                            "INSERT INTO account_catalog(account_id,owner_id,purpose,currency,status,revision,is_primary) VALUES(?,?,?,NULLIF(?,''),'OPEN',1,?)",
                            List.of(
                                account.id().toString(),
                                account.owner().toString(),
                                account.kind(),
                                account.kind().equals("BANK") ? account.currency().name() : "",
                                account.primary() ? "1" : "0"));
                        LedgerProjection.write(
                            tx, account.id(), account.currency(), account.balance());
                        LedgerProjection.write(
                            tx, issuance, account.currency(), -account.balance());
                      }));
      submit(
          store,
          actor,
          IMPORT,
          handler,
          id("legacy-account|" + token + "|" + account.id()),
          Map.of("source", token, "account", account.id().toString()));
    }
    submit(
        store,
        actor,
        FxService.OPEN,
        FxService.opening(Clock.systemUTC()),
        id("legacy-denomination|" + token),
        Map.of("currency", source.denomination().name()));
    // Rebuild from the new immutable opening postings, then compare every imported balance.
    LedgerProjection.rebuild(store);
    store.transaction(
        tx -> {
          for (var account : source.accounts()) {
            var rows =
                tx.query(
                    "SELECT minor_units FROM ledger_balances WHERE account_id=? AND currency=?",
                    List.of(account.id().toString(), account.currency().name()),
                    1);
            long actual = rows.isEmpty() ? 0 : Long.parseLong(rows.getFirst().get("minor_units"));
            if (actual != account.balance())
              throw new IllegalStateException("Legacy balance reconciliation failed");
          }
          return null;
        });
  }

  private static void submit(
      SqliteStorage store,
      PlayerId actor,
      Journal.Kind kind,
      JournalEngine.Registration handler,
      UUID operation,
      Map<String, String> fields) {
    var engine =
        new JournalEngine(
            store,
            (who, candidate) -> who.equals(actor) && candidate.equals(kind),
            Map.of(kind, handler));
    var outcome =
        engine.submit(
            new Journal.Intent(
                new OperationId(operation),
                new RequestId(operation),
                actor,
                kind,
                new Journal.CanonicalRequest(1, fields),
                null,
                null));
    if (outcome.status() != Journal.Status.COMMITTED)
      throw new IllegalStateException("Legacy import did not commit: " + outcome.result().code());
  }

  private static UUID id(String text) {
    return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
  }
}
