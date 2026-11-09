package ca.emilybelnavis.mcmods.economy.clock;

import ca.emilybelnavis.mcmods.economy.journal.Journal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** Stored decisions, not commands to rerun. Output is opaque to the clock primitive. */
public record EconomicRecord(
    String system,
    long startMillis,
    long endMillis,
    int simulationVersion,
    long economicSequence,
    long randomSeed,
    UUID updateId,
    String output) {
  public static final String TYPE = "foundation:economic-update";

  public EconomicRecord {
    new Journal.Kind(system, "foundation:interval");
    if (startMillis < 0
        || endMillis <= startMillis
        || simulationVersion < 1
        || economicSequence < 1) throw new IllegalArgumentException("Economic record bounds");
    java.util.Objects.requireNonNull(updateId);
    validateOutput(output);
  }

  static void validateOutput(String output) {
    new Journal.Result(1, "OUTPUT", output);
    if (output.getBytes(StandardCharsets.UTF_8).length > 8192)
      throw new IllegalArgumentException("Economic output bounds");
  }

  public String encode() {
    return system
        + "\n"
        + startMillis
        + "\n"
        + endMillis
        + "\n"
        + simulationVersion
        + "\n"
        + economicSequence
        + "\n"
        + randomSeed
        + "\n"
        + updateId
        + "\n"
        + Base64.getEncoder().encodeToString(output.getBytes(StandardCharsets.UTF_8));
  }

  public static EconomicRecord decode(String payload) {
    String[] fields = payload.split("\n", -1);
    if (fields.length != 8) throw new IllegalArgumentException("Economic record format");
    byte[] bytes = Base64.getDecoder().decode(fields[7]);
    String output;
    try {
      output =
          StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    } catch (java.nio.charset.CharacterCodingException e) {
      throw new IllegalArgumentException(e);
    }
    return new EconomicRecord(
        fields[0],
        Long.parseLong(fields[1]),
        Long.parseLong(fields[2]),
        Integer.parseInt(fields[3]),
        Long.parseLong(fields[4]),
        Long.parseLong(fields[5]),
        UUID.fromString(fields[6]),
        output);
  }
}
