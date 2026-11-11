package ca.emilybelnavis.mcmods.economy.storage;

import ca.emilybelnavis.mcmods.economy.journal.Journal;
import ca.emilybelnavis.mcmods.economy.recovery.RecordCatalog;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** A bounded, read-only validation pass, before migrations or replay. One page per startup step. */
public final class JournalCompatibility {
  private final Connection connection;
  private final RecordCatalog catalog;
  private final String world;
  private String cursor = "";
  private long validated;

  public JournalCompatibility(Connection connection, RecordCatalog catalog, String world) {
    this.connection = connection;
    this.catalog = catalog;
    this.world = world;
  }

  public long validated() {
    return validated;
  }

  public boolean step() throws SQLException {
    int count = 0;
    try (var query =
        connection.prepareStatement(
            "SELECT * FROM operation_outcomes WHERE world_id=? AND operation_id>? ORDER BY operation_id LIMIT 16")) {
      query.setString(1, world);
      query.setString(2, cursor);
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          if (Thread.currentThread().isInterrupted())
            throw new StorageException(
                StorageException.Kind.CORRUPTION_IO, "Journal validation interrupted");
          validate(rows);
          cursor = rows.getString("operation_id");
          count++;
          validated++;
        }
      }
    } catch (IllegalArgumentException | java.io.IOException e) {
      throw new StorageException(
          StorageException.Kind.CORRUPTION_IO, "Invalid journal envelope", e);
    }
    return count < 16;
  }

  private void validate(ResultSet outcome) throws SQLException, java.io.IOException {
    uuid(outcome.getString("operation_id"));
    uuid(outcome.getString("request_id"));
    String[] actor = outcome.getString("actor").split(":", 2);
    if (actor.length != 2 || !Set.of("player", "organization", "system").contains(actor[0]))
      throw new IllegalArgumentException("Actor");
    uuid(actor[1]);
    new Journal.Kind(outcome.getString("service"), outcome.getString("operation_type"));
    new Journal.Result(
        outcome.getInt("result_version"),
        outcome.getString("result_code"),
        outcome.getString("result_payload"));
    Instant.parse(outcome.getString("audit_utc"));
    String[] links =
        validateCanonical(
            outcome.getString("canonical_request"), outcome.getInt("canonical_version"));
    if (outcome.getString("status").equals("DURABLY_REJECTED")) {
      if (outcome.getString("transaction_id") != null || outcome.getString("sequence") != null)
        throw new IllegalArgumentException("Rejected effect");
      return;
    }
    if (!outcome.getString("status").equals("COMMITTED"))
      throw new IllegalArgumentException("Outcome status");
    uuid(outcome.getString("transaction_id"));
    try (var query =
        connection.prepareStatement("SELECT * FROM journal_transactions WHERE sequence=?")) {
      query.setLong(1, outcome.getLong("sequence"));
      try (var tx = query.executeQuery()) {
        if (!tx.next()
            || tx.getInt("envelope_version") != 1
            || !world.equals(tx.getString("world_id"))
            || !outcome.getString("operation_id").equals(tx.getString("operation_id"))
            || !outcome.getString("transaction_id").equals(tx.getString("transaction_id"))
            || !outcome.getString("audit_utc").equals(tx.getString("audit_utc")))
          throw new IllegalArgumentException("Transaction linkage/version");
        if (!links[0].equals(Objects.toString(tx.getString("economic_update_id"), ""))
            || !links[1].equals(Objects.toString(tx.getString("correction_of"), "")))
          throw new IllegalArgumentException("Canonical economic/correction linkage");
        if (tx.getString("economic_update_id") != null) uuid(tx.getString("economic_update_id"));
        if (tx.getString("correction_of") != null) uuid(tx.getString("correction_of"));
        int position = 0;
        long bytes = 0;
        ca.emilybelnavis.mcmods.economy.clock.EconomicRecord economic = null;
        String fxPublication = null;
        try (var records =
            connection.prepareStatement(
                "SELECT * FROM journal_records WHERE sequence=? ORDER BY position")) {
          records.setLong(1, tx.getLong("sequence"));
          try (var record = records.executeQuery()) {
            while (record.next()) {
              if (record.getInt("position") != position++)
                throw new IllegalArgumentException("Record ordering");
              var entry =
                  new Journal.Entry(
                      record.getInt("position"),
                      record.getString("record_type"),
                      record.getInt("record_version"),
                      record.getString("payload"));
              bytes += entry.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
              catalog.validate(entry.type(), entry.version(), entry.payload());
              if (entry.type().equals(ca.emilybelnavis.mcmods.economy.fx.FxFacts.PUBLICATION))
                ca.emilybelnavis.mcmods.economy.fx.FxFacts.Publication.decode(
                    entry.payload(), entry.version());
              ca.emilybelnavis.mcmods.economy.clock.DeadlineDispatcher.validateStored(
                  entry.type(), entry.payload(), connection);
              if (entry.type().equals(ca.emilybelnavis.mcmods.economy.clock.EconomicRecord.TYPE)) {
                validateEconomic(entry.payload(), tx);
                economic =
                    ca.emilybelnavis.mcmods.economy.clock.EconomicRecord.decode(entry.payload());
              }
              if (entry.type().equals(ca.emilybelnavis.mcmods.economy.fx.FxFacts.PUBLICATION)) {
                if (fxPublication != null)
                  throw new IllegalArgumentException("Multiple FX publications in one transaction");
                fxPublication = entry.payload();
              }
            }
          }
        }
        if (economic != null
            && economic.system().equals(ca.emilybelnavis.mcmods.economy.fx.FxService.SYSTEM)) {
          if (!economic.output().equals(fxPublication))
            throw new IllegalArgumentException("Missing or mismatched FX clock publication");
          var published =
              ca.emilybelnavis.mcmods.economy.fx.FxFacts.Publication.decode(fxPublication);
          if (!published.updateId().equals(economic.updateId())
              || published.day().toEpochDay() * 86_400_000L != economic.endMillis()) {
            throw new IllegalArgumentException("FX publication identity or day mismatch");
          }
        } else if (fxPublication != null
            && (!outcome
                    .getString("operation_type")
                    .equals(ca.emilybelnavis.mcmods.economy.fx.FxService.OPEN.operationType())
                || !ca.emilybelnavis.mcmods.economy.fx.FxFacts.Publication.decode(fxPublication)
                    .updateId()
                    .toString()
                    .equals(tx.getString("operation_id")))) {
          throw new IllegalArgumentException("FX opening linkage mismatch");
        }
        if (position != tx.getInt("record_count") || position < 1 || bytes > 131072)
          throw new IllegalArgumentException("Record completeness/bounds");
      }
    }
  }

  private void validateEconomic(String payload, ResultSet journal) throws SQLException {
    var fact = ca.emilybelnavis.mcmods.economy.clock.EconomicRecord.decode(payload);
    if (!fact.updateId().toString().equals(journal.getString("economic_update_id")))
      throw new IllegalArgumentException("Economic update envelope mismatch");
    try (var query =
        connection.prepareStatement("SELECT * FROM economic_updates WHERE update_id=?")) {
      query.setString(1, fact.updateId().toString());
      try (var row = query.executeQuery()) {
        if (!row.next()
            || row.getLong("economic_sequence") != fact.economicSequence()
            || !row.getString("system_id").equals(fact.system())
            || row.getLong("interval_start_ms") != fact.startMillis()
            || row.getLong("interval_end_ms") != fact.endMillis()
            || row.getInt("version") != fact.simulationVersion()
            || !row.getString("random_seed").equals(Long.toString(fact.randomSeed()))
            || !row.getString("output").equals(fact.output())
            || !row.getString("transaction_id").equals(journal.getString("transaction_id"))
            || row.getLong("journal_sequence") != journal.getLong("sequence"))
          throw new IllegalArgumentException("Economic completion differs from journal");
      }
    }
  }

  private static void uuid(String value) {
    if (!UUID.fromString(value).toString().equals(value))
      throw new IllegalArgumentException("UUID encoding");
  }

  private static String[] validateCanonical(String value, int version) throws java.io.IOException {
    if (value.length() > 22000) throw new IllegalArgumentException("Canonical size");
    byte[] bytes = Base64.getDecoder().decode(value);
    if (bytes.length > 16384 || version < 1 || version > 1024)
      throw new IllegalArgumentException("Canonical version/bounds");
    var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
    if (input.readInt() != version)
      throw new IllegalArgumentException("Canonical version mismatch");
    int size = input.readInt();
    if (size < 0 || size > 64) throw new IllegalArgumentException("Canonical fields");
    String previous = null;
    for (int i = 0; i < size; i++) {
      String key = read(input);
      read(input);
      if (previous != null && key.compareTo(previous) <= 0)
        throw new IllegalArgumentException("Canonical field order");
      previous = key;
    }
    String economic = read(input);
    if (!economic.isEmpty()) uuid(economic);
    String correction = read(input);
    if (!correction.isEmpty()) uuid(correction);
    if (input.available() != 0) throw new IllegalArgumentException("Canonical trailing bytes");
    return new String[] {economic, correction};
  }

  private static String read(java.io.DataInputStream input) throws java.io.IOException {
    int length = input.readInt();
    if (length < 0 || length > input.available())
      throw new IllegalArgumentException("Canonical string bounds");
    byte[] bytes = input.readNBytes(length);
    try {
      return java.nio.charset.StandardCharsets.UTF_8
          .newDecoder()
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString();
    } catch (java.nio.charset.CharacterCodingException e) {
      throw new IllegalArgumentException("Invalid UTF-8", e);
    }
  }
}
