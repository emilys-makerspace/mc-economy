package ca.emilybelnavis.mcmods.economy.foundation;

import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.journal.JournalEngine;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import java.util.List;
import java.util.UUID;

/** Durable authorization receipt. The envelope carries world, operator, UTC time and sequence. */
public final class InspectionAudit {
  static final Journal.Kind KIND = new Journal.Kind("economy:accounts", "economy:inspect");
  private static final String TYPE = "economy:account_inspection";

  private InspectionAudit() {}

  public static RecordCatalog register(RecordCatalog catalog) {
    return catalog.with(new RecordCatalog.Key(TYPE, 1), InspectionAudit::validate);
  }

  static JournalEngine.Registration registration(String owner) {
    return new JournalEngine.Registration(
        1,
        (intent, reads) ->
            new JournalEngine.Commit(
                List.of(new Journal.Record(TYPE, 1, owner)),
                new Journal.Result(1, "INSPECTION_AUDITED", ""),
                tx -> {}));
  }

  private static void validate(String payload) {
    var parts = payload.split(":", 2);
    if (parts.length != 2
        || !(parts[0].equals("player") || parts[0].equals("system"))
        || !UUID.fromString(parts[1]).toString().equals(parts[1]))
      throw new IllegalArgumentException("Invalid inspection target");
  }
}
