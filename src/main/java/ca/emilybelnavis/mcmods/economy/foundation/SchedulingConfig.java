package ca.emilybelnavis.mcmods.economy.foundation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

/** Scheduling limits loaded once for each server lifecycle. */
public record SchedulingConfig(
    int calculationWorkers,
    int storageQueueCapacity,
    int calculationQueueCapacity,
    int callbackBudgetMillis,
    int sqliteLockTimeoutMillis,
    int drainSeconds,
    boolean debugGui) {
  private static final Set<String> KEYS =
      Set.of(
          "calculationWorkers",
          "storageQueueCapacity",
          "calculationQueueCapacity",
          "callbackBudgetMillis",
          "sqliteLockTimeoutMillis",
          "drainSeconds",
          "debugGui");

  public static final class InvalidConfiguration extends IllegalArgumentException {
    private final String safeReason;

    private InvalidConfiguration(String message, String safeReason, Throwable cause) {
      super(message, cause);
      this.safeReason = safeReason;
    }

    public String safeReason() {
      return safeReason;
    }
  }

  public SchedulingConfig {
    requireRange("calculationWorkers", calculationWorkers, 1, 16);
    requireRange("storageQueueCapacity", storageQueueCapacity, 1, 4096);
    requireRange("calculationQueueCapacity", calculationQueueCapacity, 1, 4096);
    requireRange("callbackBudgetMillis", callbackBudgetMillis, 1, 10);
    requireRange("sqliteLockTimeoutMillis", sqliteLockTimeoutMillis, 1, 60000);
    requireRange("drainSeconds", drainSeconds, 1, 300);
  }

  public SchedulingConfig(
      int calculationWorkers,
      int storageQueueCapacity,
      int calculationQueueCapacity,
      int callbackBudgetMillis,
      int sqliteLockTimeoutMillis,
      int drainSeconds) {
    this(
        calculationWorkers,
        storageQueueCapacity,
        calculationQueueCapacity,
        callbackBudgetMillis,
        sqliteLockTimeoutMillis,
        drainSeconds,
        false);
  }

  public static SchedulingConfig defaults() {
    return new SchedulingConfig(2, 256, 64, 1, 5000, 30, false);
  }

  public static SchedulingConfig load(Path path) {
    var defaults = defaults();
    var properties = new Properties();
    try (var reader = Files.newBufferedReader(path)) {
      properties.load(reader);
      for (String key : properties.stringPropertyNames()) {
        if (!KEYS.contains(key)) throw new IllegalArgumentException("Unknown setting " + key);
      }
      return new SchedulingConfig(
          value(properties, "calculationWorkers", defaults.calculationWorkers()),
          value(properties, "storageQueueCapacity", defaults.storageQueueCapacity()),
          value(properties, "calculationQueueCapacity", defaults.calculationQueueCapacity()),
          value(properties, "callbackBudgetMillis", defaults.callbackBudgetMillis()),
          value(properties, "sqliteLockTimeoutMillis", defaults.sqliteLockTimeoutMillis()),
          value(properties, "drainSeconds", defaults.drainSeconds()),
          booleanValue(properties, "debugGui", defaults.debugGui()));
    } catch (NoSuchFileException e) {
      return defaults;
    } catch (IOException | IllegalArgumentException e) {
      String reason =
          e instanceof InvalidConfiguration invalid
              ? invalid.safeReason()
              : "file is unreadable or contains an unrecognized setting";
      throw new InvalidConfiguration(
          "Invalid economy scheduling configuration at " + path + ": " + e.getMessage(), reason, e);
    }
  }

  private static int value(Properties properties, String key, int fallback) {
    String raw = properties.getProperty(key);
    if (raw == null) return fallback;
    try {
      return Integer.parseInt(raw.strip());
    } catch (NumberFormatException e) {
      throw new InvalidConfiguration(key + " must be an integer", key + " must be an integer", e);
    }
  }

  private static boolean booleanValue(Properties properties, String key, boolean fallback) {
    String raw = properties.getProperty(key);
    if (raw == null) return fallback;
    return switch (raw.strip().toLowerCase(java.util.Locale.ROOT)) {
      case "true" -> true;
      case "false" -> false;
      default ->
          throw new InvalidConfiguration(
              key + " must be true or false", key + " must be true or false", null);
    };
  }

  private static void requireRange(String key, int value, int minimum, int maximum) {
    if (value < minimum || value > maximum) {
      String reason = key + " must be between " + minimum + " and " + maximum;
      throw new InvalidConfiguration(reason + ", got " + value, reason, null);
    }
  }
}
