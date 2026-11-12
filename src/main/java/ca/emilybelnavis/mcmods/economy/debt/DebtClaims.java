package ca.emilybelnavis.mcmods.economy.debt;

import ca.emilybelnavis.mcmods.economy.api.EconomyCurrency;
import ca.emilybelnavis.mcmods.economy.api.Identifiers.WorldId;
import ca.emilybelnavis.mcmods.economy.institution.*;
import ca.emilybelnavis.mcmods.economy.journal.*;
import java.util.*;

/**
 * Constant-size reconciliation witnesses maintained with every saved facility and ownership
 * transfer.
 */
public final class DebtClaims {
  private DebtClaims() {}

  public static List<String> statements() {
    var sql = new ArrayList<String>();
    sql.add(
        "CREATE TABLE debt_claim_totals(world_id TEXT NOT NULL,party TEXT NOT NULL,role TEXT NOT NULL,currency TEXT NOT NULL,minor INTEGER NOT NULL CHECK(typeof(minor)='integer' AND minor>=0),PRIMARY KEY(world_id,party,role,currency))");
    for (String kind : List.of("CARD", "LOAN")) {
      String table = kind.equals("CARD") ? "credit_facilities" : "loans";
      String creditor = kind.equals("CARD") ? "issuer" : "lender";
      String id = kind.equals("CARD") ? "facility_id" : "loan_id";
      String borrower = kind.equals("CARD") ? "economy:borrower/" : "economy:loan-borrower/";
      sql.add(
          "INSERT INTO debt_claim_totals SELECT world_id,'"
              + borrower
              + "'||owner_id,'LIABILITY',currency,SUM(debt_minor) FROM "
              + table
              + " GROUP BY world_id,owner_id,currency");
      sql.add(
          "INSERT INTO debt_claim_totals SELECT world_id,"
              + creditor
              + ",'RECEIVABLE',currency,SUM(debt_minor) FROM "
              + table
              + " WHERE true GROUP BY world_id,"
              + creditor
              + ",currency ON CONFLICT(world_id,party,role,currency) DO UPDATE SET minor=minor+excluded.minor");
      String party =
          "COALESCE((SELECT creditor FROM debt_cases WHERE world_id=NEW.world_id AND kind='"
              + kind
              + "' AND facility_id=NEW."
              + id
              + "),NEW."
              + creditor
              + ")";
      String insert =
          "INSERT INTO debt_claim_totals VALUES(NEW.world_id,"
              + party
              + ",'RECEIVABLE',NEW.currency,NEW.debt_minor) ON CONFLICT(world_id,party,role,currency) DO UPDATE SET minor=minor+excluded.minor; INSERT INTO debt_claim_totals VALUES(NEW.world_id,'"
              + borrower
              + "'||NEW.owner_id,'LIABILITY',NEW.currency,NEW.debt_minor) ON CONFLICT(world_id,party,role,currency) DO UPDATE SET minor=minor+excluded.minor;";
      String update =
          "UPDATE debt_claim_totals SET minor=minor+NEW.debt_minor-OLD.debt_minor WHERE world_id=NEW.world_id AND party="
              + party
              + " AND role='RECEIVABLE' AND currency=NEW.currency; UPDATE debt_claim_totals SET minor=minor+NEW.debt_minor-OLD.debt_minor WHERE world_id=NEW.world_id AND party='"
              + borrower
              + "'||NEW.owner_id AND role='LIABILITY' AND currency=NEW.currency;";
      sql.add(
          "CREATE TRIGGER debt_claim_"
              + kind.toLowerCase(Locale.ROOT)
              + "_insert AFTER INSERT ON "
              + table
              + " BEGIN "
              + insert
              + " END");
      sql.add(
          "CREATE TRIGGER debt_claim_"
              + kind.toLowerCase(Locale.ROOT)
              + "_update AFTER UPDATE ON "
              + table
              + " BEGIN "
              + update
              + " END");
    }
    sql.add(
        "CREATE TRIGGER debt_claim_sale AFTER UPDATE OF creditor ON debt_cases WHEN OLD.creditor<>NEW.creditor BEGIN UPDATE debt_claim_totals SET minor=minor-OLD.principal_minor-OLD.interest_minor WHERE world_id=OLD.world_id AND party=OLD.creditor AND role='RECEIVABLE' AND currency=OLD.currency; INSERT INTO debt_claim_totals VALUES(NEW.world_id,NEW.creditor,'RECEIVABLE',NEW.currency,NEW.principal_minor+NEW.interest_minor) ON CONFLICT(world_id,party,role,currency) DO UPDATE SET minor=minor+excluded.minor; END");
    return List.copyOf(sql);
  }

  public static boolean matches(
      JournalEngine.ReadView reads,
      WorldId world,
      InstitutionCatalog.InstitutionId party,
      FinancialAccounts.Role role,
      EconomyCurrency currency) {
    var rows =
        reads.query(
            "SELECT minor FROM debt_claim_totals WHERE world_id=? AND party=? AND role=? AND currency=?",
            List.of(world.value().toString(), party.key(), role.name(), currency.name()),
            1);
    long expected = rows.isEmpty() ? 0 : Long.parseLong(rows.getFirst().get("minor"));
    if (role == FinancialAccounts.Role.LIABILITY) expected = Math.negateExact(expected);
    return Settlement.posted(reads, FinancialAccounts.id(world, party, role, currency), currency)
        == expected;
  }

  /** Frozen overdrafts live in their case, so case changes maintain their claim totals directly. */
  public static List<String> overdraftStatements() {
    return List.of(
        "CREATE TRIGGER debt_claim_overdraft_insert AFTER INSERT ON debt_cases WHEN NEW.kind='OVERDRAFT' BEGIN INSERT INTO debt_claim_totals VALUES(NEW.world_id,NEW.creditor,'RECEIVABLE',NEW.currency,NEW.principal_minor+NEW.interest_minor) ON CONFLICT(world_id,party,role,currency) DO UPDATE SET minor=minor+excluded.minor; INSERT INTO debt_claim_totals VALUES(NEW.world_id,'economy:overdraft-borrower/'||NEW.owner_id,'LIABILITY',NEW.currency,NEW.principal_minor+NEW.interest_minor) ON CONFLICT(world_id,party,role,currency) DO UPDATE SET minor=minor+excluded.minor; END",
        "CREATE TRIGGER debt_claim_overdraft_update AFTER UPDATE ON debt_cases WHEN NEW.kind='OVERDRAFT' BEGIN UPDATE debt_claim_totals SET minor=minor+NEW.principal_minor+NEW.interest_minor-OLD.principal_minor-OLD.interest_minor WHERE world_id=NEW.world_id AND party='economy:overdraft-borrower/'||NEW.owner_id AND role='LIABILITY' AND currency=NEW.currency; UPDATE debt_claim_totals SET minor=minor+NEW.principal_minor+NEW.interest_minor-OLD.principal_minor-OLD.interest_minor WHERE NEW.creditor=OLD.creditor AND world_id=NEW.world_id AND party=NEW.creditor AND role='RECEIVABLE' AND currency=NEW.currency; END");
  }

  public static InstitutionCatalog.InstitutionId borrower(
      DebtRecords.Kind kind, ca.emilybelnavis.mcmods.economy.api.Identifiers.PlayerId owner) {
    String prefix =
        switch (kind) {
          case CARD -> "economy:borrower/";
          case LOAN -> "economy:loan-borrower/";
          case OVERDRAFT -> "economy:overdraft-borrower/";
        };
    return new InstitutionCatalog.InstitutionId(prefix + owner.value());
  }
}
