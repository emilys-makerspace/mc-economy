package ca.emilybelnavis.mcmods.economy.support;

import ca.emilybelnavis.mcmods.economy.account.AccountCatalog;
import ca.emilybelnavis.mcmods.economy.api.AccountIdentity.Purpose;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.api.PartyRef;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.util.*;

/** Test-only historical world setup. Never installed by production lifecycle services. */
public final class LegacyAccounts {
  private LegacyAccounts() {}

  /** Archived A3 fixture for tests of historical FX activation, not a production admission. */
  public static JournalEngine.Registration opening(
      PartyRef owner, ca.emilybelnavis.mcmods.economy.account.AccountLifecycle.Open open) {
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          if (!ca.emilybelnavis.mcmods.economy.fx.FxService.availableCurrencies(reads)
              .contains(open.currency()))
            return new JournalEngine.Reject(new Journal.Result(1, "CURRENCY_UNAVAILABLE", ""));
          String id = UUID.randomUUID().toString();
          var player = (PartyRef.Player) owner.owner();
          return new JournalEngine.Commit(
              List.of(
                  new Journal.Record(
                      "economy:bank-lifecycle",
                      1,
                      String.join(
                          "|",
                          "opened",
                          id,
                          player.id().value().toString(),
                          open.currency().code(),
                          "1"))),
              new Journal.Result(1, "BANK_OPENED", id),
              tx ->
                  tx.execute(
                      "INSERT INTO account_catalog VALUES(?,?,'BANK',?,'OPEN',1,0)",
                      List.of(id, player.id().value().toString(), open.currency().code())));
        });
  }

  public static JournalEngine.Registration initialization(PartyRef owner, Purpose purpose) {
    var player = (PartyRef.Player) owner.owner();
    return new JournalEngine.Registration(
        1,
        (intent, reads) -> {
          if (!intent.actor().equals(player.id())
              || !intent
                  .request()
                  .fields()
                  .equals(Map.of("economy:owner", Journal.actorKey(player.id()))))
            throw new IllegalArgumentException("Fixture owner mismatch");
          var existing =
              purpose == Purpose.BANK
                  ? AccountCatalog.primary(reads, owner)
                  : AccountCatalog.trading(reads, owner);
          if (existing.isPresent())
            return AccountCatalog.initialization(owner, purpose).handler().prepare(intent, reads);
          var denomination = RebaseSettlement.denomination(reads).orElse(null);
          if (purpose == Purpose.BANK && denomination == null)
            return new JournalEngine.Reject(new Journal.Result(1, "DENOMINATION_PENDING", ""));
          var id = UUID.randomUUID().toString();
          var currency = purpose == Purpose.BANK ? denomination.currency().code() : "";
          var fact =
              String.join(
                  "|", "created", id, player.id().value().toString(), purpose.name(), currency);
          return new JournalEngine.Commit(
              List.of(new Journal.Record("economy:account-initialization", 1, fact)),
              new Journal.Result(1, "ACCOUNT_READY", id),
              tx ->
                  tx.execute(
                      "INSERT INTO account_catalog(account_id,owner_id,purpose,currency,status,revision,is_primary) VALUES(?,?,?,NULLIF(?,''),'OPEN',1,?)",
                      List.of(
                          id,
                          player.id().value().toString(),
                          purpose.name(),
                          currency,
                          purpose == Purpose.BANK ? "1" : "0")));
        });
  }
}
