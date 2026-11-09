package ca.emilybelnavis.mcmods.economy.pricing;

import ca.emilybelnavis.mcmods.economy.api.Identifiers.*;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.time.Instant;
import java.util.*;

/** New deposits cannot bypass specialist service restrictions through ordinary ledger transfers. */
public final class PricingAdmission {
  private PricingAdmission() {}

  public static JournalEngine.Decision check(
      Journal.Intent intent,
      JournalEngine.ReadView reads,
      JournalEngine.Commit commit,
      WorldId world,
      Instant now,
      WealthValuation valuation) {
    if (Set.of(
            BankProviders.PRIMARY,
            BankProviders.SECONDARY,
            BankProviders.ASSIGN,
            BankProviders.SWITCH_SOURCE,
            BankProviders.SWITCH_TARGET,
            BankProviders.CLOSE)
        .contains(intent.kind())) {
      if (reads
          .query(
              "SELECT world_id FROM institution_capital_manifest WHERE world_id=? AND state='ACTIVE'",
              List.of(world.value().toString()),
              1)
          .isEmpty())
        return new JournalEngine.Decline(new Journal.Result(1, "INSTITUTIONS_UNAVAILABLE", ""));
    }
    if (!Set.of(
            SameCurrency.PAY,
            SameCurrency.TRANSFER,
            SameCurrency.CREDIT,
            FxSettlement.EXCHANGE,
            ca.emilybelnavis.mcmods.economy.trade.settlement.NpcTrades.SETTLE)
        .contains(intent.kind())) return commit;
    var checked = new HashSet<String>();
    for (var record : commit.records())
      if (record.type().equals("economy:posting")) {
        var p = record.payload().split("\\|", -1);
        if (!p[1].equals("ACCOUNT") || Long.parseLong(p[3]) <= 0 || !checked.add(p[0])) continue;
        var rows =
            reads.query(
                "SELECT a.owner_id,p.bank_key FROM account_catalog a JOIN bank_account_providers p ON p.account_id=a.account_id WHERE a.account_id=? AND a.purpose='BANK' AND a.status='OPEN'",
                List.of(p[0]),
                1);
        if (rows.isEmpty()) continue;
        var row = rows.getFirst();
        var bank =
            InstitutionCatalog.find(new InstitutionCatalog.InstitutionId(row.get("bank_key")))
                .orElse(null);
        if (bank == null || bank.tier1()) continue;
        var snapshot =
            valuation.snapshot(
                reads, world, new PlayerId(UUID.fromString(row.get("owner_id"))), now);
        if (!snapshot.available() || !snapshot.eligible())
          return new JournalEngine.Reject(new Journal.Result(1, "SERVICE_ACCOUNT_UNAVAILABLE", ""));
      }
    return commit;
  }
}
