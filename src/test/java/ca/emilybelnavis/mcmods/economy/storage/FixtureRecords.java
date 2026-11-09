package ca.emilybelnavis.mcmods.economy.storage;

import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;

/** Explicit test-only historical record registry. Never packaged. */
public final class FixtureRecords {
  private FixtureRecords() {}

  public static RecordCatalog catalog() {
    return RecordCatalog.core()
        .with(new RecordCatalog.Key("fixture:first", 1), p -> {})
        .with(new RecordCatalog.Key("fixture:second", 2), p -> {})
        .with(new RecordCatalog.Key("fixture:large", 1), p -> {});
  }
}
