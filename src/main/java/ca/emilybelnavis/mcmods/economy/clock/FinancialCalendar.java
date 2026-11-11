package ca.emilybelnavis.mcmods.economy.clock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Version-one elapsed-UTC calendar. None of these helpers reads game time or the wall clock. */
public final class FinancialCalendar {
  public static final int VERSION = 1;
  public static final long DAY_MILLIS = 1_200_000L;
  public static final long WEEK_MILLIS = 8_400_000L;
  public static final long MONTH_MILLIS = 33_600_000L;
  public static final long YEAR_MILLIS = 436_800_000L;

  private FinancialCalendar() {}

  public enum Period {
    DAY(DAY_MILLIS),
    WEEK(WEEK_MILLIS),
    MONTH(MONTH_MILLIS),
    YEAR(YEAR_MILLIS);

    private final long millis;

    Period(long millis) {
      this.millis = millis;
    }

    public long millis() {
      return millis;
    }
  }

  /** Declaration order is the normative same-instant financial dependency order. */
  public enum Stage {
    POLICY_FX,
    ACCRUAL,
    STATEMENT,
    PAYMENT,
    TRANSITION,
    REPORTING,
    SCORE,
    TIER,
    ENTITY
  }

  public record Boundary(
      UUID id, long anchorMillis, Period period, long index, int version, long instantMillis) {
    public Boundary {
      Objects.requireNonNull(id);
      Objects.requireNonNull(period);
      if (version != VERSION) throw new IllegalArgumentException("Unsupported calendar version");
      if (instantMillis != at(anchorMillis, period, index)) {
        throw new IllegalArgumentException("Boundary instant does not match its anchor");
      }
    }
  }

  /** Subject is the facility ID, or world ID for world-aligned boundaries. */
  public static Boundary boundary(
      UUID world, UUID subject, long anchorMillis, Period period, long index) {
    Objects.requireNonNull(world);
    Objects.requireNonNull(subject);
    long instant = at(anchorMillis, period, index);
    String canonical =
        "economy:a6/calendar|"
            + VERSION
            + "|"
            + world
            + "|"
            + subject
            + "|"
            + anchorMillis
            + "|"
            + period.name()
            + "|"
            + index;
    UUID id = UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8));
    return new Boundary(id, anchorMillis, period, index, VERSION, instant);
  }

  public static long at(long anchorMillis, Period period, long index) {
    requireInstant(anchorMillis);
    Objects.requireNonNull(period);
    if (index < 1) throw new IllegalArgumentException("Boundary index must be positive");
    return Math.addExact(anchorMillis, Math.multiplyExact(index, period.millis()));
  }

  /** Complete elapsed periods only; before an anchor there are no earned periods. */
  public static long completedPeriods(long anchorMillis, long instantMillis, Period period) {
    requireInstant(anchorMillis);
    requireInstant(instantMillis);
    Objects.requireNonNull(period);
    return instantMillis <= anchorMillis ? 0 : (instantMillis - anchorMillis) / period.millis();
  }

  public static long cardMinimumDue(long statementMillis) {
    return offsetDays(statementMillis, 14);
  }

  public static long reportAt(long dueMillis) {
    return offsetDays(dueMillis, 2);
  }

  public static long defaultAt(long oldestUnmetDueMillis) {
    return offsetDays(oldestUnmetDueMillis, 84);
  }

  public static long reportExpiry(long defaultMillis) {
    return offsetYears(defaultMillis, 7);
  }

  public static long collectionExpiry(long acknowledgementMillis) {
    return offsetYears(acknowledgementMillis, 2);
  }

  private static long offsetDays(long instant, long days) {
    requireInstant(instant);
    return Math.addExact(instant, Math.multiplyExact(days, DAY_MILLIS));
  }

  private static long offsetYears(long instant, long years) {
    requireInstant(instant);
    return Math.addExact(instant, Math.multiplyExact(years, YEAR_MILLIS));
  }

  private static void requireInstant(long instant) {
    if (instant < 0)
      throw new IllegalArgumentException("UTC epoch milliseconds must be nonnegative");
  }

  /** Work limits affect throughput only, never financial calendar lengths or historical meaning. */
  public record Config(int pageSize) {
    private static final Set<String> KEYS = Set.of("pageSize");

    public Config {
      if (pageSize < 1 || pageSize > 16)
        throw new IllegalArgumentException("pageSize must be between 1 and 16");
    }

    public static Config defaults() {
      return new Config(16);
    }

    public static Config load(Path path) {
      Objects.requireNonNull(path);
      Properties values = new Properties();
      try (var reader = Files.newBufferedReader(path)) {
        values.load(reader);
        for (String key : values.stringPropertyNames()) {
          if (!KEYS.contains(key))
            throw new IllegalArgumentException("Unknown calendar setting " + key);
        }
        Config defaults = defaults();
        return new Config(integer(values, "pageSize", defaults.pageSize()));
      } catch (NoSuchFileException e) {
        return defaults();
      } catch (IOException | IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "Invalid financial calendar configuration at " + path + ": " + e.getMessage(), e);
      }
    }

    private static int integer(Properties values, String key, int fallback) {
      String raw = values.getProperty(key);
      if (raw == null) return fallback;
      try {
        return Integer.parseInt(raw.strip());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(key + " must be an integer", e);
      }
    }
  }
}
