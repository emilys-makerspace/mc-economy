package ca.emilybelnavis.mcmods.economy.credit;

import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import java.util.List;
import java.util.Objects;

/** Production new-exposure admission requires the accepted reporting and servicing dependency. */
public final class CreditSafety {
  public static final String STATUS = "CREDIT_LIFECYCLE_UNAVAILABLE";

  private CreditSafety() {}

  public static boolean installed(JournalEngine.ReadView reads) {
    for (String table :
        List.of("debt_bootstrap", "debt_legacy_reconciliation", "credit_reporting_bootstrap")) {
      var rows = reads.query("SELECT ready FROM " + table + " WHERE singleton=1", List.of(), 1);
      if (rows.isEmpty() || !rows.getFirst().get("ready").equals("1")) return false;
    }
    for (String provider :
        List.of(
            "economy:a6/debt-default",
            "economy:a6/debt-due",
            "economy:a6/debt-expiry",
            "economy:a6/debt-sale",
            "economy:a6/debt-report",
            CreditScoreRuntime.PROVIDER)) {
      if (reads
          .query(
              "SELECT provider FROM financial_deadline_providers WHERE provider=?",
              List.of(provider),
              1)
          .isEmpty()) return false;
    }
    return true;
  }

  public static boolean available(JournalEngine.ReadView reads) {
    if (!installed(reads)) return false;
    if (!reads
        .query(
            "SELECT event_id FROM financial_deadline_events WHERE state='APPLYING' LIMIT 1",
            List.of(),
            1)
        .isEmpty()) return false;
    return reads
        .query(
            "SELECT owner_id FROM credit_score_heads WHERE next_at<=(SELECT last_effective_ms FROM financial_calendar WHERE singleton=1) ORDER BY next_at,owner_id LIMIT 1",
            List.of(),
            1)
        .isEmpty();
  }

  public static String reportingStatus(JournalEngine.ReadView reads) {
    if (!installed(reads)) return CreditScoring.PRODUCTION_STATUS;
    return available(reads) ? "ACTIVE_REPORTING_28_DAY_CADENCE" : "REPORTING_CATCHUP_REQUIRED";
  }

  /** Original committed outcomes replay before the current dependency and underwriting checks. */
  public static JournalEngine.Registration production(JournalEngine.Registration foundation) {
    Objects.requireNonNull(foundation);
    return new JournalEngine.Registration(
        foundation.canonicalVersion(),
        (intent, reads) ->
            available(reads)
                ? foundation.handler().prepare(intent, reads)
                : new JournalEngine.Decline(
                    new Journal.Result(
                        1, STATUS, "Credit reporting or servicing catch-up is unavailable.")));
  }
}
